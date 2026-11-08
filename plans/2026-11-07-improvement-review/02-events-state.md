# Improvement Review 02 — Events & State

Scope: `src/laser_show/events/**`, `src/laser_show/state/**`, `src/laser_show/subs.clj`, `src/laser_show/app.clj`, `src/laser_show/common/**`.

Related plans (not duplicated here, referenced where they overlap):
- `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md`: clipboard type unification, atomic cut, `[:ui :focus-context]`. Overlaps with findings 15 and 16.
- `plans/2026-10-07-timeline-track-drag-and-selection.md`: timeline selection as a single source of truth. Overlaps with finding 20.
- `plans/2026-10-08-canvas-draw-prop-unification.md`: `:canvas/update-drag` flow.
- `plans/v3/plan-unsaved-guard.md` and `plans/plan-project-persistence.md` / `plans/v3/plan-project-persistence.md`: **stale**. They refer to namespaces that don't exist (`laser-show.persistence`, `!app-state`, `:ui-state :unsaved?`). The real code is `state/persistent.clj` plus `[:project :dirty?]`. The unsaved-changes guard depends on fixing dirty tracking first (findings 1 and 6).

---

## 1. Summary

The architecture is sound in principle. There is one cljfx context atom, handlers are pure and return effect maps through `fx/wrap-co-effects`/`fx/wrap-effects` on the cljfx async agent, and undo is a document-snapshot middleware (`wrap-history`). In practice the purity contract is often broken. Several handlers write to the atom directly (persistence, clipboard, input learn), call JavaFX, clipboard or MIDI/OSC APIs on the agent thread, or block on the FX thread. Because the `:state` effect does a whole-map `reset!` from a snapshot taken when the event started, those out-of-band writes are silently undone. The preview timer and engine threads writing at 30 Hz make the same race constant. As a result, File > Save doesn't remember the file or clear the dirty flag. The boundary between the persisted "document" and live performance state is drawn too wide: MIDI effect toggles, BPM changes, keyframe selection and preview layout all create undo entries. Subscription granularity also makes every grid cell recompute on every preview tick. Test coverage is decent for list helpers, history and grid/timing handlers. Nothing tests the wired `event-handler` pipeline, the subs, `chain/params`, `effect_params`, the `input` or `connection` handlers, or file save/load through handlers.

---

## 2. Findings

### Correctness / bugs

1. **File > Save / Save As reverts its own result (High).**
   `menu.clj:76-96` calls `persistent/save-project!`, which `swap!`s `[:project :current-file/:dirty?/:last-saved]` (`persistent.clj:134-140`). The handler then returns `{:state state}`, the pre-save co-effect snapshot. `effect-state` (`events/core.clj:65-69`) `reset!`s to that snapshot, which wipes the update.
   Result: after Save As the current file is forgotten, so the next Ctrl+S opens Save As again. The title keeps its `*` and `dirty?` stays true. `persistent_test.clj:106-207` calls `persistent/*` directly, so it never catches this.
   Also, `collect-data-for-paths` (`persistent.clj:106`) reads through `state/get-in-state` → `get-state` → `fx/sub-val` (`state/core.clj:128-139`, `77-78`) on the agent thread. `docs/CLJFX_STATE_MANAGEMENT.md` §Thread Safety says not to do this.

2. **Lost updates between the agent's whole-state `reset!` and other writers (High).**
   `co-effect-state` reads the raw map at event start (`events/core.clj:48-56`). `effect-state` replaces the whole map at the end. Anything written to the atom in between is lost:
   - `frame_service.clj:95` (global clock / active-cue accumulators) and `:756` (`current-frame`, `frame-stats`), both at 30 Hz.
   - `multi_engine.clj:292/315/374` (`multi-engine-state`, written from the `future`s started by `:multi-engine/*` effects).
   - `clipboard.clj:31-34` (internal clipboard, written from inside handlers).
   - `input.clj:222-238` (learn result written from a `future`).
   - `app.clj:150` (link state).

   Clock rollbacks are mostly self-healing. Engine state and clipboard are not. For example, `list.clj:232-245` copies, sets the internal clipboard, then returns `{:state state}`, which reverts it. Paste then only works while the OS clipboard still holds our EDN.

3. **The MIDI/OSC enable toggles write the wrong key (High).**
   `input.clj:38,43,130,135` write `:enabled`, and `subs.clj:639,675` read `:enabled`. The domain (`domains.clj:401,409`) and the input code (`osc.clj:133`, `midi.clj:91`) use `:enabled?`. OSC defaults to `:enabled? false` and can never be enabled from the UI. MIDI can never be disabled.

4. **Handlers return effect keys that aren't registered, so the effect loop throws (High).**
   cljfx `wrap-effects` throws `"Effect … does not exist"` for an unknown key.
   - `:system/exit` (`menu.clj:111`): File > Exit does nothing except log an agent error.
   - `:system/open-url` (`menu.clj:221`).
   - `:learn-promise` (`input.clj:103,190`): every MIDI/OSC learn start throws after `:state` is applied.

   Effects run in map order, so whether `:state` was applied depends on key order.

5. **Live-performance actions pollute undo and clear the redo stack (High).**
   `wrap-history` (`handlers/history.clj:133-157`) snapshots whenever `extract-document-state` (`state/history.clj:16-28`) changes. That document includes `[:timing :bpm]`, all of `:config` and all of `:chains`. So all of the following each push an undo entry and clear redo, and Undo then reverts live show actions:
   - MIDI/OSC `:effects/toggle-cell` (writes `[:chains :effect-chains k :active?]`, `effects.clj:39-41`).
   - Tap tempo and Link BPM sync (`timing.clj:26`).
   - `:keyframe/select` (`:selected-keyframe` lives in the document, `keyframe.clj:204-213`).
   - `:list/toggle-collapse` (`:collapsed?` on items, `list.clj:337-345`).
   - `:preview/*` layout and zone filter (`ui.clj:60-117` write `[:config :preview]`).
   - Network scan auto-creating projectors (`projector.clj:159-174`).
   - Opening the timeline editor (`timeline.clj:569-600`).

   Transactions are also global (`history.clj:141-143`): a MIDI-driven BPM change during a slider drag gets folded into the slider's undo step.

6. **The dirty flag is maintained by hand and inconsistently (Med).**
   `h/mark-dirty` is called in about 40 places. These document-changing handlers never call it:
   - `:chain/update-param`, `-from-text`, `-color-param`, curve and spatial edits (`chain/core.clj:39-72`).
   - `:effects/toggle-cell`.
   - `:timing/set-bpm`.
   - `:preview/*`.
   - `:input/remove-trigger` and the other trigger-map edits (`input.clj:250-285`).
   - `:keyframe/select`.

   Meanwhile `apply-document-state` always marks the project dirty (`state/history.clj:44`). So unsaved-change detection can't be trusted, which blocks the unsaved-guard plan.

7. **Open Project merges into the current document instead of replacing it (High).**
   `merge-data-at-paths!` (`persistent.clj:59-73`) does `update-in path merge`. Cue cells, effect cells, projectors and zone groups from the previous project stay behind. Other gaps:
   - Playback (`active-cues`), `:list-ui` and open dialogs that point at old cells are not reset.
   - No `:multi-engine/refresh` runs.
   - On failure, `load-project!` returns `false`, but `handle-file-open-result` (`menu.clj:43-54`) still clears history and gives no user feedback.
   - The docstring says "Runs migrations" (`persistent.clj:181`), but there is no format version and no migration.

8. **New Project wipes runtime and backend state (Med-High).**
   `persistent/new-project!` (`persistent.clj:212-216`) resets *every* registered domain. That includes:
   - `:backend`: `:streaming :running?` goes false while the engines keep running, the link state is lost while Carabiner stays connected, and MIDI device/OSC server handles are dropped without being closed.
   - `:projector-ui` discovered devices.

   It also skips `templates/apply-starter-cue-chains` (unlike `app.clj:131`), and it mutates the atom from inside a handler (`menu.clj:28-29`).

9. **The trigger system emits or receives events nothing handles (Med).**
   - `:trigger-group/toggle-lock` (`triggers.clj:132`): the `"trigger-group"` namespace is not routed in `handlers.clj:37-68`, so it logs "Unknown event domain".
   - `triggers.clj:52-57` emits `:timeline/start`, `:timeline/stop` and `:preset/apply`. None of these exists (the timeline handler logs a warning; `preset` is an unknown domain).
   - `:blackout` maps to `:transport/stop` (`:50`).

10. **The co-effect `:time` overwrites the input timestamp (Med).**
    `input_mapper.clj:68-70` passes `:time (:timestamp event)`. `wrap-co-effects` then `assoc`s `:time (System/currentTimeMillis)` over it. Tap tempo (`timing.clj:33`) and trigger runtime (`triggers.clj:87`) therefore use the time the agent dequeued the event, not the time the input arrived, so queue jitter feeds straight into BPM.
    `grid.clj:35` ignores event time entirely: `(h/current-time-ms {:time (System/currentTimeMillis)})`.

11. **Sync-to-downbeat is broken (Med).**
    `timing.clj:106-112` reads `:dispatch-fn` from the event, but no co-effect provides it, so it is always nil. It also reads `[:playback :accumulated-beats]`, which doesn't exist. Beats live in `[:timing :global-clock]` / `ex/global-accumulated-beats`. On top of that it does Link network I/O inside a "pure" handler.

12. **Modulator/keyframe mutual exclusion never fires (Med).**
    `modulator.clj:29-42` builds paths like `[:grid :effect-chains …]` and `[:projectors id :effects]`, a pre-`:chains` layout. `keyframe-enabled?` is therefore always false.
    `handle-toggle` (`:90-103`) also sequences two events with a 10 ms `:dispatch-later` hack, which produces two undo entries. All modulator handlers trust `:current-value` captured at render time (`:61,123,170,236`), not the value in state, so rapid slider events can overwrite each other.

13. **`(or (:fx/event e) (:value e))` turns `false` into `nil` (Med).**
    - `chain/params.clj:83`: unchecking a boolean param writes `nil`.
    - `keyframe.clj:515,529,538-544`: `:keyframe/set-normalize false` becomes `nil`.
    - `list.clj:362-368` is fine, but the same pattern is copied elsewhere.

14. **`:ui/update-dialog-data` merges co-effect keys into dialog state (Low).**
    `reserved-keys` (`ui.clj:51`) omits `:time` (and `:history/description`). Every call without `:updates` writes `:time <ms>` into `[:ui :dialogs id]`, which also invalidates that dialog's subscriptions.

15. **Clipboard handling is impure, blocking and inconsistent (Med).**
    - `copy-to-system-clipboard!` and `read-from-system-clipboard` block the agent thread with `@(fx/on-fx-thread …)` (`clipboard.clj:64,82`). They are called from handlers: `helpers.clj:71-72`, `list.clj:241-243,251`, `cue_chain.clj:166-177`. While the agent waits on the FX thread, MIDI triggers queue up behind it.
    - Grid/effects paste reads only the internal clipboard (`helpers.clj:93`). List paste reads the system clipboard first (`clipboard.clj:104-109`).
    - Grid paste does not regenerate item UUIDs (`helpers.clj:92-100`, `transform-fn` never passed), so the same IDs end up in several cells. Timeline selection and `expanded-tracks` are keyed by ID globally (`timeline.clj:201,345`).
    - `:clipboard/*` effects (`events/core.clj:125-149`) are emitted by nothing. The handlers they would dispatch to are TODO stubs that log errors (`effects.clj:173-176`, `projector.clj:341-358`).

16. **List operations take two steps and get the wrong undo labels (Med).**
    `list.clj:144-163, 213-357` update `:list-ui` and then `:dispatch` an `on-change-event` carrying `:items` computed from the *current* snapshot. That second event is queued behind anything already in the agent, so it can overwrite edits made in between. History sees two events: the first doesn't touch the document, so the snapshot is labelled "Modify Chain" (from `:chain/set-items`), not the "Delete Item(s)", "Group Items" or "Reorder Items" mapped in `history.clj:110-114`.
    The description table also lists events that don't exist: `:timeline/delete-track`, `:timeline/move-track`, `:projectors/add-projector`, `:projectors/delete-projector`, `:zone-groups/add-group`, `:zone-groups/delete-group` (`history.clj:120-127`).

17. **Removing a zone group leaves dangling references (Med).**
    `zone_groups.clj:45-76` cleans up projectors and virtual projectors but not:
    - `[:chains :cue-chains * :destination-zone :zone-group-id]`
    - item- and track-level `:zone-group-id`
    - `[:config :preview :grid-cells * :zone-group-id]`
    - `[:config :preview :zone-group-filter]`

    New IDs come from `System/currentTimeMillis` (`zone_groups.clj:34`, `projector.clj:232`), so two creates in the same millisecond collide.

18. **Undo/redo and load don't resync the output engines (Med).**
    `handle-undo`/`handle-redo` (`handlers/history.clj:47-79`) restore `:projectors` and `:chains :projector-effects`, but unlike `projector.clj:202,221,287` they never emit `:multi-engine/refresh`. Undoing "enable projector" while streaming leaves the engine running. Undo can also remove a cue that is still in `[:playback :active-cues]`.

### Side-effect isolation / conventions

19. **Handlers do I/O and JavaFX work on the agent thread (Med).**
    - MIDI connect and OSC server start: `input.clj:53-63,113-121,137-147`.
    - Link enable/disable: `timing.clj:139-161`.
    - `TextField.getText` and `ColorPicker.getValue` read from the event source: `chain/params.clj:99-105,122-139`, `keyframe.clj:460-474`, `modulator.clj:175-183`, `helpers.clj:36-47`.
    - Scroll-pane manipulation through a `defonce` atom holding `ScrollPane` instances: `timeline.clj:72-177`.
    - Synchronous zip I/O for save/load: `menu.clj:49,83,94`.
    - Router handler registration: `input.clj:96,183,212`.

    These handlers can't be tested without JavaFX, and they block the single event agent that live MIDI/OSC triggers share.

20. **The events layer depends on a views namespace (Low-Med).**
    `timeline.clj:21` requires `laser-show.views.components.visual-editors.timeline.track-logic`. That pure logic should live under `animation/` or `state/`.

21. **Many state keys are not declared in `domains.clj` (Med).**
    The file claims to be "the single source of truth for ALL application state" (`domains.clj:17`), but these keys appear only in handlers:
    - `[:effects :selected-cell]` (`effects.clj:41`, `menu.clj:140`, `subs.clj:86`)
    - `[:ui :canvas-drag]`, `[:ui :timeline …]`, `[:ui :projector-effect-ui-state]`, `[:ui :preview-visible?]`, `[:ui :fullscreen?]`
    - `[:debug …]` (`menu.clj:243,255`)
    - `[:playback :active-cell]`, `[:playback :trigger-time]`, `[:playback :phase-offset-target]` (`grid.clj:42-43`, `timing.clj:120`)
    - `[:backend :input :learning-target]`, `[:backend :streaming :engine-count]`
    - `[:list-ui :timeline-tracks]`

    Some declared keys are dead or legacy: `[:ui :components]`, `[:ui :preview :frame]`, `[:playback :active-cue]` / `:cue-queue`, `[:styles :menu-theme]` (written in `app.clj:104`, read nowhere). Mutable objects also live in state: device handles, OSC server, router handler fns, learn promises (`domains.clj:401-421`).

22. **The `dispatch!` fallback is a separate, divergent effect interpreter (Med).**
    `events/core.clj:408-444` handles only `:state`, `:dispatch`, three clipboard effects and scan. It silently drops `:dispatch-later`, `:timing/calculate-bpm`, `:multi-engine/*` and `:fx/show-file-chooser`. Tests that go through `dispatch!` before `start-test!` therefore run different semantics from production.
    `:timing/calculate-bpm` (`:85-99`) is pure maths dressed up as an effect: it re-reads global state and depends on `:state` having been applied first.

23. **`stop!` is not symmetric with `start!` (Med).**
    `app.clj:166-178` unmounts and stops the input mapper only. It leaves running:
    - the preview timer (`frame-service/start-preview-updates!`, `:136`)
    - the gamepad thread (`:161`)
    - the Carabiner connection (`:149`)
    - the streaming engines and MIDI/OSC resources

    It also doesn't clear `events/*dispatch-fn` or `input-mapper/*dispatch-fn`. REPL restarts and TestFX fixtures leak threads and sockets.

24. **Duplicated logic (Low).**
    - `migrate-cell-triggers` and `remove-cell-triggers` are copied in `grid.clj:57-79` and `effects.clj:43-65`.
    - `handle-grid-trigger-cell` and `handle-grid-cell-clicked` are about 90% identical (`grid.clj:81-139`).
    - The "params-map from effect def" reduce is repeated in `effects.clj:147-150`, `cue_chain.clj:105-108` and `timeline.clj:538-541`.
    - Every `chain/params.clj` wrapper rebuilds `(vec (concat items-path effect-path [:params]))`.
    - Snap settings are read the same way in 6 timeline handlers.

### Performance (real-time)

25. **Every grid cell recomputes on every preview tick (High, perf).**
    `cell-display-data` (`subs.clj:185-212`) depends on `(fx/sub-val context ex/active-cues)`. That map is rewritten at 30 Hz by `frame_service.clj:95` (per-cue accumulators). It also depends on `(fx/sub-val context :chains)`, which changes on every parameter drag anywhere.
    So all 32 cue cells redo a `tree-seq` + `filterv` and rebuild their result map on every tick, and the cljfx renderer then diffs 32 new descriptions. `effect-cell-display-data` (`:253-262`) has the same `:chains` dependency.
    The other context-wide 30 Hz writes have the same effect: `current-frame` and `frame-stats` into `[:backend :streaming]`, and the global clock. `docs/CLJFX_STATE_MANAGEMENT.md` Best Practice #5 says to keep 60 fps data out of the main context.
    `list-ordered-ids`' fallback `(fx/sub-val context identity)` (`subs.clj:544`) depends on the whole state.

26. **Latency on the live trigger path (Med).**
    A MIDI note goes `input-mapper` → agent `:trigger/input` → `:dispatch` → agent `:grid/trigger-cell` (`triggers.clj:91-93`). That is two hops through one FIFO agent that also runs synchronous zip I/O, MIDI device opening and FX-thread clipboard waits. Each hop also runs `wrap-history`'s two `extract-document-state` calls.
    `grid.clj:25-29,90,122-136` and `keyframe.clj:209` log at INFO on every trigger or select.

27. **Smaller issues in `common/` (Low).**
    - `util/remove-keys` and `remove-vals` pass arguments in the wrong order (`util.clj:105-115`: `(filter-keys (complement f) m)` but the signature is `[m f]`). They throw on any use; currently unused.
    - The `->map` docstring sits after the arg vector (`util.clj:127-131`).
    - `timing.clj:34-36`: the doc says 15 ms but the value is 5 ms. The docstring claims "10-20% CPU" for a `Thread/yield` spin that will use a whole core (`:65-68`).
    - `state/core.clj:206-232` documents an `update-in-state!` that doesn't exist.
    - `chain.clj:7` refers to a missing `chain/structure.clj`.

### Tests

28. **Missing tests (Med).** No tests exist for:
    - `events/core` (the wired `event-handler`, effect registration, the `dispatch!` fallback)
    - `subs.clj`
    - `chain/core.clj` and `chain/params.clj`
    - `effect_params.clj`
    - `handlers/input.clj`, `connection.clj`, `files.clj`
    - `menu.clj` file operations (only edit copy/paste/clear are tested, `menu_handlers_test.clj:37-76`)
    - `state/extractors.clj`, `templates.clj`, `common/util.clj`

    No test asserts that every emitted event type is routed, or that every returned effect key is registered. A test like that would have caught findings 4 and 9.

---

## 3. Proposed improvements

### Quick wins

| # | Change | Files | Effort | Risk |
|---|---|---|---|---|
| Q1 | **Fix save.** Split `persistent` into pure `(project-files state)` and `(mark-saved state path now)`. In `handle-file-save`/`-save-as-result`, return `{:state (mark-saved state path time)}` after a successful write instead of the stale `state`. Read the state passed in, not `get-in-state`. | `state/persistent.clj`, `events/handlers/menu.clj`, `test/.../menu_handlers_test.clj` | S | Low |
| Q2 | **Register or remove the missing effects.** Add `:system/exit` (`Platform/exit` + `System/exit` outside dev mode) and `:system/open-url` (`HostServices`/`Desktop`). Stop returning `:learn-promise`; it is already stored in state. | `events/core.clj`, `handlers/menu.clj`, `handlers/input.clj` | S | Low |
| Q3 | **Fix the enable key.** Change `:enabled` to `:enabled?` in `input.clj:38,43,130,135` and `subs.clj:639,675`. | `handlers/input.clj`, `subs.clj` | S | Low |
| Q4 | **Fix trigger routing.** Route `"trigger-group"` in `handlers.clj`. Either implement `:timeline/start`/`:stop`/`:preset/apply` or drop them from `action-event` with a debug log. | `events/handlers.clj`, `handlers/triggers.clj` | S | Low |
| Q5 | **Keep the input timestamp.** Rename the input-mapper key to `:input-time`. Make `h/current-time-ms` prefer `:input-time` over `:time`. Use event time in `grid/trigger-cue`. | `input_mapper.clj`, `events/helpers.clj`, `handlers/grid.clj`, `handlers/timing.clj` | S | Low |
| Q6 | **Fix false-to-nil.** Replace `(or (:fx/event e) (:value e))` with an `h/event-value` helper using `contains?`/`some?`. | `chain/params.clj`, `handlers/keyframe.clj`, `handlers/modulator.clj`, `events/helpers.clj` | S | Low |
| Q7 | **Fix modulator paths.** In `get-keyframe-enabled?`, use `(chain/chain-config domain entity-key)`'s `:items-path`. In `handle-toggle`, compose `keyframe/handle-toggle-enabled` and the param update in one `:state` result (no `:dispatch-later`). | `handlers/modulator.clj` | S | Low |
| Q8 | **Fix sync-to-downbeat.** Read `ex/global-accumulated-beats`. Move the Link call into a `:link/sync-to-downbeat` effect that receives `dispatch`. | `handlers/timing.clj`, `events/core.clj` | S | Low |
| Q9 | **Compute tap-tempo BPM in the handler.** Do it in `handle-timing-tap-tempo` and delete the `:timing/calculate-bpm` effect. | `handlers/timing.clj`, `events/core.clj` | S | Low |
| Q10 | **Narrow the grid-cell subscriptions.** In `cell-display-data`, use `(fx/sub-val context get-in [:chains :cue-chains [col row]])` and a boolean `(fx/sub-val context (fn [s] (contains? (get-in s [:playback :active-cues]) [col row])))`. Do the same for `effect-cell-display-data`. Removes per-tick recompute for 64 cells. | `subs.clj` | S | Low |
| Q11 | **Small fixes.** Add `:time` and `:history/description` to `ui.clj` reserved keys. Fix `util/remove-keys`/`remove-vals` argument order and the `->map` docstring. Correct the docstrings in `state/core.clj`, `chain.clj` and `common/timing.clj`. Turn the hot-path INFO logs in `grid.clj` and `keyframe.clj` down to DEBUG. | `handlers/ui.clj`, `common/util.clj`, `common/timing.clj`, `state/core.clj`, `handlers/grid.clj`, `handlers/keyframe.clj` | S | Very low |
| Q12 | **Fix undo labels.** Update `format-event-description` to real event names (`:zone-groups/create-new`, `:zone-groups/remove`, `:projectors/add-manual`, `:timeline/update-tracks` …). Add a test that every key in that `case` is a routed event type. | `handlers/history.clj`, new test | S | Low |
| Q13 | **Deduplicate trigger-map helpers.** Move `migrate-cell-triggers`/`remove-cell-triggers` and a `params-from-effect-def` helper into `events/helpers.clj`. Merge the trigger-cell and cell-clicked bodies into one `toggle-or-trigger` function. | `handlers/grid.clj`, `handlers/effects.clj`, `handlers/cue_chain.clj`, `handlers/timeline.clj`, `events/helpers.clj` | S | Low |

### Medium refactors

**M1. Remove lost updates. There should be one writer per subtree (M, Med risk).**
1. Instrument first. In `effect-state`, `swap!` and compare `(:cljfx.context/m ctx)` with the co-effect snapshot using `identical?`. Log a warning with the event type when they differ (keep the snapshot on the event as `::base`). This measures how often the race happens.
2. Route every out-of-band writer in this area through `dispatch!`:
   - clipboard internal state: the handler returns the `[:ui :clipboard]` change itself, no `set-internal-clipboard!`
   - `input.clj` learn `future`: dispatch `:input/learn-captured`
   - `app.clj` link init: dispatch `:timing/link-initialized`
3. With the output-pipeline and animation owners, move per-tick runtime (`[:timing :global-clock]`, the accumulators in `[:playback :active-cues]`, `[:backend :streaming :current-frame/:frame-stats/:multi-engine-state]`) into a separate runtime atom, as Best Practice #5 says. Mirror it into the context at a throttled rate (e.g. 15-30 Hz) through a single `:runtime/mirror` path that only touches those keys. After this, `effect-state` can `swap!` with a merge that never clobbers runtime keys.

- Files: `events/core.clj`, `state/core.clj`, `state/clipboard.clj`, `handlers/input.clj`, `app.clj`. Cross-area: `services/frame_service.clj`, `backend/multi_engine.clj`.
- Risk: medium. Animation timing reads must switch to the runtime atom.

**M2. Draw an explicit document/live boundary for history and dirty tracking (M, Med risk).**
1. Write down what counts as "document":
   - `:chains`, minus `:active?` on effect cells, `:selected-keyframe` and `:collapsed?`
   - `:projectors`, `:virtual-projectors`, `:zone-groups`
   - `[:config :input]` and `[:config :cue]`
   - `[:grid :size]`
   - `[:timing :bpm]`, only when changed by a user edit

   Put this in `state/history.clj` as `document-paths` plus `ignored-subpaths`. Move preview layout and window size into a separate `:prefs` area: still persisted, but not undoable.
2. Allow `:history/skip? true` on events coming from triggers, Link and tap tempo. `wrap-history` honours it, and `triggers/action-event` sets it.
3. Derive `[:project :dirty?]` inside `wrap-history` (and undo/redo) whenever the document changes. Delete the ~40 `h/mark-dirty` calls.
4. Scope transactions by `:source-id`. Changes from events with a different source during a transaction get their own snapshot instead of joining the open one. Auto-commit a transaction older than N seconds so a missed mouse-up can't disable undo for good.
5. After undo/redo, emit `:multi-engine/refresh` if streaming. Prune `[:playback :active-cues]` entries whose cell no longer exists.

- Files: `state/history.clj`, `handlers/history.clj`, `events/helpers.clj`, all handlers that call `mark-dirty`, `handlers/triggers.clj`, `state/domains.clj`.
- Risk: medium. It changes what users can undo, so it needs history tests (L3).

**M3. Make persistence correct and asynchronous (M, Med risk). Depends on Q1.**
1. **Load replaces the document.** Take the document part of `domains/build-initial-state`, apply the loaded data, keep the runtime and backend domains, and reset `:playback`, `:list-ui`, `[:ui :dialogs]` and history. Emit `:multi-engine/refresh`.
2. **Version the format.** Add `:format-version` to `project-metadata.edn`, plus a `migrate` multimethod keyed by version.
3. **New project resets only document and UI domains**, then applies the starter templates.
4. **Do the I/O in effects.** Add `:project/write-zip` and `:project/read-zip` effects that run in a `future` and dispatch `:project/saved`, `:project/save-failed`, `:project/loaded` and `:project/load-failed`. The pure handler applies the result. Surface failures as `[:project :error]` in state for a status-bar or toast view.
5. **Remove the dead `effect-save-project`/`effect-load-project` stubs** (`events/core.clj:101-121`) and `:project/set-folder` (`files.clj:21-24`).

- Files: `state/persistent.clj`, `state/serialization.clj`, `handlers/menu.clj`, `handlers/files.clj`, `events/core.clj`, `state/domains.clj`, `test/.../persistent_test.clj`.
- Risk: medium. Data-format change; needs round-trip tests on old zip fixtures.

**M4. Move side effects out of handlers (M, Med risk). Depends partly on the views area.**
- **Clipboard.** Add a `:clipboard/write` effect (async `Platform/runLater`, no blocking). For paste, add `:clipboard/read` which reads on the FX thread and dispatches `:…/paste-with` carrying the data. Handlers stay pure.
- **Devices.** Add `:midi/connect`, `:midi/disconnect`, `:osc/start` and `:osc/stop` effects. These dispatch `:input/*-connected` with *serializable* status only. Keep device and server handles in an `input/registry` atom, not in the context (fixes the objects-in-state part of finding 21).
- **Text and color parsing.** Views pass `:value` (parsed double or `[r g b]`) in the event map instead of the raw `ActionEvent`. Remove `.getSource` from `chain/params.clj`, `keyframe.clj`, `modulator.clj` and `helpers.clj`. Coordinate with the views area.
- **Timeline scroll panes.** Move `scroll-panes` and the `ScrollPane` reads and writes into the view or a `:fx/scroll` effect. Handlers only update `[:ui :timeline :scroll-hvalue]`. Move `track-logic` out of `views/` (finding 20).
- **Clean up the clipboard effects.** Delete the unused `:clipboard/*-effects` and the TODO stubs (`effects.clj:173`, `projector.clj:341-358`), or implement them inside the clipboard unification from the keyboard-shortcuts plan.

- Files: `events/core.clj`, `state/clipboard.clj`, `events/helpers.clj`, `handlers/list.clj`, `handlers/cue_chain.clj`, `handlers/input.clj`, `handlers/timing.clj`, `handlers/timeline.clj`, `chain/params.clj`, `handlers/keyframe.clj`, `handlers/modulator.clj`. Cross-area: views that build these events.
- Risk: medium. Touches many event producers.

**M5. Make list operations atomic (M, Low-Med risk).**
Replace the `:on-change-event`/`:dispatch` round-trip with a pure registry:
`(defmulti apply-items (fn [{:keys [domain]}] domain))`, with `:effect-chains`, `:cue-chains`, `:item-effects`, `:timeline-tracks` → `(fn [state params items] state')`.
Each list handler then returns one `:state` with both the new items and the new selection. That removes the stale-items window and gives the correct undo label (finding 16). It also matches the "atomic cut" requirement in the keyboard-shortcuts plan.

- Files: `handlers/list.clj`, `handlers/chain/core.clj`, `handlers/cue_chain.clj`, `handlers/timeline.clj`, `test/.../list_handlers_test.clj`.

**M6. Make `domains.clj` the real schema (M, Low risk).**
1. Declare every key in finding 21. Delete legacy keys (`:active-cell`, `:active-cue`, `:cue-queue`, `:components`, `[:ui :preview :frame]`, `:styles :menu-theme`), migrating readers to `active-cues`.
2. Fix `[:effects :selected-cell]` by adding a `defstate effects-ui`.
3. Add a test that runs a scripted event sequence through `handle-event-pipeline` and asserts no top- or second-level key exists that isn't registered. Optionally add malli/spec `:schema` metadata to `defstate`.

- Files: `state/domains.clj`, `state/core.clj`, `state/extractors.clj`, affected handlers and subs, new test.

**M7. Share one effect interpreter between production and tests (S-M, Low risk).**
1. Build `dispatch-sync!` with `(fx/wrap-effects (fx/wrap-co-effects handle-event-pipeline …) effect-map)` from the *same* effect map. Use it as the pre-app `dispatch!` fallback.
2. In tests, use a test effect map that records effects instead of performing I/O. Delete the hand-rolled fallback (`events/core.clj:408-444`).
3. Add `:dispatch-n` so handlers can sequence follow-up events without hacks.

- Files: `events/core.clj`, test fixtures.

**M8. Make the lifecycle symmetric (S, Low risk).**
`stop!` should call `frame-service/stop-preview-updates!`, `gamepad/stop!`, the link disconnect, `multi-engine/stop-engines!` and MIDI/OSC shutdown, and reset both dispatch fns to nil. Keep it idempotent, which the TestFX fixtures need.
- Files: `app.clj`; small `stop!` fns in other areas where missing.

**M9. Clean up references when deleting a zone group, and fix ID generation (S, Low risk).**
Add a pure `(remove-zone-group-references state id)` covering cue chains, items, tracks and preview cells. Use `(keyword (str "group-" (random-uuid)))` for new group and manual projector IDs.
- Files: `handlers/zone_groups.clj`, `handlers/projector.clj`, tests.

### Larger efforts

**L1. Event registry and contract checks (M-L, Low risk).**
1. Replace the namespace-string `case` in `handlers.clj` and the per-domain `case` dispatchers with one registry: `{event-type {:handler f :doc … :history/label … :history/skip? …}}`. Define it per namespace, e.g. `(def handlers {…})` merged in `handlers.clj`.
2. History labels and skip flags come from the registry, which removes the stale `format-event-description` table.
3. Add contract tests:
   - (a) every `:event/type` keyword literal under `src/` (grep `:event/type :x/y`) is registered
   - (b) every key a handler returns is a registered effect
   - (c) optionally, a malli schema per event checked in dev mode

   These would have caught findings 4, 9 and 16.
- Files: `events/handlers.clj`, all `events/handlers/*.clj`, `handlers/history.clj`, new tests.

**L2. Separate live-input dispatch from editor events (L, Med-High risk). Shared with the input/triggers area.**
1. Resolve a trigger into its domain change in one handler call: `triggers/handle-input` calls `grid/trigger-cue` directly and returns one `:state`, with no `:dispatch` hop.
2. Process live input on its own agent, or a priority lane, that only touches playback and runtime subtrees. Combined with M1's runtime atom, this means file I/O, clipboard waits and device opening can never delay a cue.
3. Measure end-to-end note-to-frame latency before and after with the existing profiler (`docs/PROFILING.md`).
- Files: `handlers/triggers.clj`, `input_mapper.clj`, `events/core.clj`, `app.clj`. Cross-area: input, output pipeline.

**L3. Events/state test suite (M-L, Low risk).**
1. Handler unit tests for `chain/core` + `chain/params`, `effect_params`, `input` (with device effects stubbed after M4), `connection`, `files`, and `menu` file ops through the *wired* pipeline with a temp dir. These cover Q1, M3 and finding 4.
2. Property-style history tests: undo∘redo = id over random document edits, plus "live events never change the undo stack" (M2).
3. Subscription tests: build `(fx/create-context state cache/lru-cache-factory)`, swap an unrelated path or the active-cue accumulators, and assert `cell-display-data` returns an `identical?` value (guards Q10). Also cover `extractors`, `templates` and `common/util`.
4. Consider raising the LRU threshold (`state/core.clj:44-47`, 512) once subs are parameterised per cell. Measure first.
- Files: `test/laser_show/events/**`, `test/laser_show/state/**`, new `test/laser_show/subs_test.clj`, `test/laser_show/common/util_test.clj`.

---

## 4. Suggested order of work

1. **Stop the visible breakage (1-2 days):** Q1, Q2, Q3, Q4, Q6, Q7, Q8, Q11, Q12. Add the pipeline tests from L3.1 alongside them, so each fix has a regression test.
2. **Quick real-time wins:** Q10, Q5, Q9, and the logging part of Q11. Measure frame-time and FX-thread load before and after with `docs/PROFILING.md`.
3. **Persistence:** M3, then M9. These unblock the unsaved-changes guard, which should be re-planned against `[:project :dirty?]` instead of the stale `plans/v3/plan-unsaved-guard.md`.
4. **State integrity:** M1 step 1 (instrumentation) → M7 → M8 → M6 → M1 steps 2-3.
5. **History semantics:** M2 (needs M6's schema and L3.2's tests), then M5.
6. **Purity:** M4 (with the views owner), then L1 (registry and contract tests).
7. **Live path:** L2, last, with the input/triggers and output owners.

**Dependencies on other areas**
- **Output pipeline (IDN/backend/routing):** M1.3 (runtime atom for `frame_service`/`multi_engine` writes), M2.5 and M3.1 (`:multi-engine/refresh` after undo/load), M8 (engine stop), L2. `frame_service.clj:95,756` and `multi_engine.clj:292-374` are the main sources of the race in finding 2.
- **Animation/effects:** M1.3 (cue timing reads from the runtime atom), Q7 (modulator/keyframe exclusivity), M6 (`active-cell` removal touches `frame_service` readers).
- **Views/UI:** M4 (events carry parsed values, scroll-pane handling moves into views), Q10 (grid cells), M5 (list component's `:on-change-event` props), M6 (subs renamed or removed), and the keyboard-shortcuts plan (clipboard and focus context).
- **Input/triggers:** Q3, Q4, Q5, M4 (device effects and a registry outside context state), L2.
- **Testing/build/tooling:** L1's grep-based contract test and L3 need a fast unit test alias. Subs tests need cljfx contexts and no FX toolkit, so they can run in `clj -M:test` and don't need the TestFX suite.


---

## Carried over from old plans (2026-11-08 audit, see 07-old-plans-audit.md)
- **Export dialog is a no-op** (`events/handlers/menu.clj:98-103`). Implement it as a registered effect or remove the menu item.
- **Help > Documentation opens a placeholder URL** (`github.com/your-repo/docs`, `menu.clj:221`). Replace it with the real docs URL when registering `:system/open-url`.
- **Zone cue count is hard-coded:** `subs.clj:613-621` `zone-group-usage` returns `:cue-count 0 ;; TODO`, shown as "Cues targeting: 0" at `views/tabs/zones.clj:269`. Compute it with `zone_effects/resolve-item-zone-destination` over the cue chains, behind a narrow subscription.
- **Clipboard effect stubs:** `:clipboard/paste-effects` is never emitted (`events/core.clj:130-139`, `effects.clj:172-176`). Delete the stub, or implement it as part of the keyboard/clipboard consolidation.
- **Duplicate grid events:** `:grid/trigger-cell` and `:grid/cell-clicked` are about 90% the same (`grid.clj:81-139`). Unify them.
