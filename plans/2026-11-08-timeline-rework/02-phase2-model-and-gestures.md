# Phase 2 — Timeline model, edits as data, local gestures

**Written:** 2026-11-08   **Written for commit:** `d0a7dc2`
**Status:** Done (2026-11-11, branch `timeline/phase2-model-gestures`, commits `7dda99a`…`afd2306`)   **Last verified against:** `afd2306`

> **How the implementation differs from this plan:**
> - **Anchored multi-clip moves.** `:move-clips` takes an optional `:anchor-id`, the clip under the pointer. Snapping is applied where that clip lands, and every clip then moves by the same delta, clamped so the earliest stays at ≥ 0. Without an anchor (nudge), the earliest clip is the anchor. Ops also accept `:snap? false`, which the gesture sends while Shift is held.
> - **Cross-track drags are single-clip only:** `:to-track` is set only when one clip is dragged.
> - **`apply-op` normalizes its result,** and returns the input itself when the result is equal, so `identical?` detects no-ops. The pipeline middleware then finds nothing left to do.
> - **Pressing a selected clip keeps the selection** so it can be dragged as a group; a click without movement collapses the selection on release. Backspace deletes too.
> - **`interactive-canvas` changes beyond the plan.** Callbacks get `:modifiers {:shift? :shortcut? :alt?}`. A press on a focusable canvas is consumed, so an enclosing ScrollPane doesn't take the keyboard focus back (Escape during a drag didn't reach the canvas otherwise). `on-key` can return `:cancel-drag?`.
> - **Zoom is per cue** (`[:ui :timeline-editor [col row] :zoom-x]`), as §5 says. View events that don't carry `:col`/`:row` fall back to the open editor's cell.
> - **Still open:** `events/handlers/timeline` still requires `track-logic` for the zoom-at-cursor geometry, and that handler still reads live ScrollPanes on the agent thread. `:timeline/update-tracks` stays for the list sidebar. Both go in Phase 3 with the viewport and header column. `subs/timeline-ui` and `timeline-prefs` aren't used outside the dialog yet. The keyboard-shortcut plan still needs to absorb the canvas keys (Escape, Ctrl/Cmd+D, Ctrl/Cmd+A).
**Supersedes / Superseded by:** none   **Related findings:** TL-13, TL-14, TL-19, TL-28 (subs part); 01 F11, F12, F14, M1 steps 2/3/5, M3 steps 1/2/4; 02 #16, #20, #21, Q13

## 1. Current behaviour (file:line)

- **Timeline logic lives in three places.**
  - Tree and geometry helpers are in a *views* namespace (`views/components/visual_editors/timeline/track_logic.clj`), required by events (`events/handlers/timeline.clj:21`, 02 #20) and, after Phase 1, by `frame_service`.
  - Edit rules (clamp, snap, min length) are re-implemented per handler. Snap settings are read the same way in six handlers (`timeline.clj:240-241,277-278,300-301,325-326,481-482,521-522`).
  - Window gating is private to the renderer (`frame_service.clj:266-283`).
- **Every edit is its own event.** There are 24 editing event types (`timeline.clj:627-668`). Mouse drags dispatch `:timeline/update-item-timing`, `:timeline/move-item-to-track` and `:timeline/select-track` on **every mouse move** (`timeline_editor.clj:485-518`). Each one writes the document, re-renders the whole dialog and redraws the full canvas.
- **Canvas interaction state is global and async.** `interactive-canvas` stores drag/hover state in `[:ui :canvas-drag id]` through `:canvas/update-drag` events (`canvas_interaction.clj:73-80`, `ui.clj:132-140`). It then reads it back synchronously on the next mouse event (`canvas_interaction.clj:39-42`), which may run before the agent has applied the write (01 F14). Undo transactions begin on press and commit on release only if `:dragging?` already reached state (`:180-213`), which is TL-13. The only readers of `[:ui :canvas-drag]` are `canvas_interaction.clj:42` and `ui.clj:135,140`.
- **UI state isn't keyed by cue.** `[:ui :timeline …]` (`timeline_cue_editor.clj:105`) and `[:list-ui :timeline-tracks]` (`timeline_editor.clj:773`) are shared by every cell. Neither is declared in `domains.clj` (02 #21).
- **The dialog over-subscribes.** It subscribes to all of `:chains` (`timeline_cue_editor.clj:96,230`) and to all of `ex/active-cues` (`:108-110`), so the whole dialog re-renders on every 30 Hz tick while anything plays (01 F11/F12). It also rebuilds `(into {} (map (juxt :id identity)) zone-groups)` on every render (`:150`).
- **Selection is single-clip only.** `on-press` always uses `:mode :replace` (`timeline_editor.clj:423-425`), even though `handle-select-items` already supports `:add` and `:toggle` (`timeline.clj:195-212`).

## 2. Root cause

Root causes 1 and 2 (`00-overview.md` §2). Nothing owns the timeline invariants, and transient gesture state is treated as application state.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| Phase 1 pure helpers in `track_logic.clj` (`01` §4.1) | Moved verbatim into `laser-show.timeline.model` |
| `tl/auto-initialize-tracks`, `ensure-leaf-track`, `first-leaf-track`, `find-track-by-id`, `create-track` (`track_logic.clj:26-57,168-194,240-300`) | Moved into `laser-show.timeline.model` |
| `handlers/list/helpers.clj` `move-items` (`:273`), `remove-by-ids` (`:122`), `group-items-by-ids` (`:369`), `ungroup-by-id` (`:428`), `update-by-id` (`:148`) | Track tree ops inside `apply-op` |
| `chains/deep-copy-items` (`chains.clj:231-239`) | `:duplicate-clips` op |
| `:history/description` on events (working-tree `handlers/history.clj:202`) | Undo label for `:timeline/apply-op` |
| `interactive-canvas` API: `:preview-value`, `on-release`, `:drag-updates` (`canvas_interaction.clj:92-134`) | Timeline drags preview locally and commit once on release |
| `handle-select-items` modes (`timeline.clj:195-212`) | Shift/Ctrl click |
| `subs/cue-cell-chain` (`subs.clj:169-176`) | Per-cell chain subscription for the dialog |
| 01 M1 steps 2, 3, 5 and 01 M3 steps 1, 2, 4 | Implemented here (ownership in `00-overview.md` §6) |

## 4. Changes per namespace

### 4.1 `laser-show.timeline.model` (NEW, `src/laser_show/timeline/model.clj`)
It's pure and depends only on `animation.chains`, `animation.cue-chains`, `events.handlers.list.helpers` and `common.util`. It must not require views, events or state. Justification: this is the single owner of the timeline invariants (root cause 1). It's used by handlers, views, `frame_service` and `persistent`.

- Constants and accessors from Phase 1: `default-clip-length`, `conversion-length`, `min-clip-length`, `clip-start`, `clip-length`, `clip-end`, `snap`.
- Tracks: `find-track`, `leaf-track?`, `leaf-track-ids`, `first-leaf-track`, `insert-track [tracks selected-id]` (was `ensure-leaf-track`), `parent-track`, `muted-track-ids`, `create-track`, `auto-initialize-tracks`.
- Document: `normalize-timeline-cue`, `normalize-timeline-cues`, `convert-to-timeline`.
- NEW `active-at? [item beats] → boolean` replaces `frame_service/in-timeline-window?` (`frame_service.clj:266-283`) with the same semantics (no keys = always on), so nested children and non-timeline cues don't change.
- NEW `apply-op [cue-chain op opts] → cue-chain`, where `opts = {:snap {:enabled? :value} :zone-groups m}`. Unknown op → identity, plus a `log/warn` at the handler. Ops:

| `:op` | Keys | Effect |
|---|---|---|
| `:move-clips` | `:clip-ids`, `:delta`, `:to-track` (optional) | `move-clip` each (start clamped so the earliest stays ≥ 0, same delta for all); `:to-track` only applied if it's a leaf |
| `:trim-start` / `:trim-end` | `:clip-id`, `:to` | Phase 1 `trim-start` / `trim-end` |
| `:delete-clips` | `:clip-ids` | `remove-by-ids` |
| `:duplicate-clips` | `:clip-ids` | deep copies placed right after the span of the originals, same tracks |
| `:add-clip` | `:preset-id`, `:track-id`, `:start` | `cue-chains/create-preset-instance` + timing + `insert-track` |
| `:add-effect` | `:clip-id`, `:effect-id`, `:params` | appends to the clip's `:effects` (params via a shared `params-from-effect-def`, 02 Q13) |
| `:set-clip-track` | `:clip-id`, `:track-id` | leaf only |
| `:set-clip-zone` / `:set-track-zone` | `:id`, `:zone-group-id` (`nil` = inherit) | as `timeline.clj:405-433` |
| `:add-track` / `:add-folder` | `:parent-id` (optional), `:name` (optional) | `create-track` (no zone, palette colour) |
| `:delete-tracks` | `:track-ids` | removes the tracks and their descendants **and their clips** (D4) |
| `:move-tracks` | `:track-ids`, `:target-id`, `:position` | `move-items` |
| `:rename-track`, `:set-track-color` | `:track-id`, value | `update-by-id` |
| `:set-track-muted`, `:set-collapsed` | `:track-id`, boolean | `update-by-id` on `:muted?` / `:collapsed?` (live fields, not undoable, D10) |
| `:set-loop` / `:trim-loop-start` / `:trim-loop-end` / `:move-loop` | values | Phase 1 loop helpers; `:set-loop` with `:enabled?` toggles |

- NEW `op-label [op] → string`, e.g. "Move Clip", "Move 3 Clips", "Trim Clip", "Delete Track". Used for undo labels.
- The result of `apply-op` is always normalized. That's a postcondition and a test, not a second normalization pass.

### 4.2 `laser-show.views.components.visual-editors.timeline.track-logic` (existing)
After the move, only geometry remains: `track-at-y`, `droppable-track?`, `calculate-canvas-width`, `calculate-horizontal-scroll`, `calculate-zoom-at-cursor` (`track_logic.clj:196-237,308-381`). Phase 3 replaces these with `laser-show.timeline.layout` and deletes the namespace. Move the matching tests to `test/laser_show/timeline/model_test.clj`.

### 4.3 `laser-show.events.handlers.timeline` (existing)
- NEW `:timeline/apply-op {:col :row :op {...}}`. It reads the chain at `[:chains :cue-chains [col row]]`, applies `model/apply-op` with the snap prefs, writes back, and clears `:selection` ids that no longer exist.
- These events become thin adapters that build an op and delegate. They're kept because existing dispatchers use them:
  - `:timeline/add-preset-to-track`: preset bank template (`timeline_editor.clj:997-1001`)
  - `:timeline/add-effect-to-item`: effect bank template (`:1016-1019`)
  - `:timeline/add-track` / `add-folder`: toolbar (`:632,638`)
  - `:timeline/delete-selection`: Delete key (`:541`)
  - `:timeline/nudge-selection`: arrow keys (`:544,548`); keeps the undo plan's `:history/coalesce-key`
  - `:timeline/toggle-loop`: toolbar (`:656`)
  - `:timeline/set-track-zone` / `set-clip-zone`: context menu and inspector (`:735,745,875,937`)
- Deleted (the canvas and inspector dispatch `:timeline/apply-op` instead): `:timeline/update-item-timing`, `:timeline/move-item-to-track`, `:timeline/update-loop-timing` (`timeline.clj:644,649,657`).
- NEW private `snap-opts [state]` reads `[:ui :timeline-prefs :snap]` once (replaces the six copies).
- `:timeline/update-tracks` stays until Phase 3 replaces the sidebar, with the Phase 1 clip removal.

### 4.4 `laser-show.views.components.visual-editors.canvas-interaction` (existing; 01 M3 steps 1, 2, 4)
- Interaction state moves to an atom kept in the canvas `userData`: `{:canvas-id id :interaction (atom {...})}`. `.setUserData` is already called at `:145`. `get-drag-state` (`:39-42`) derefs it, and `update-drag-state!` (`:73-80`) `swap!`s it synchronously on the FX thread. Delete `:canvas/update-drag` / `:canvas/clear-drag` (`ui.clj:132-140,161-162`; routed at `events/handlers.clj:48`), plus `[:ui :canvas-drag]`, whose only readers are `canvas_interaction.clj:42` and `ui.clj:135,140`.
- The interaction atom records `:transaction-open?` when `:history/begin-transaction` is dispatched, and release commits whenever it's true. This fixes TL-13 for every canvas.
- NEW prop `:history-transaction?` (default `true`, so other canvases are unchanged). The timeline passes `false` because it commits once on release.
- `on-press` honours `:preview-value` (01 M3 step 4).
- `:draw` (`:168-169`) keeps rendering `(or (:preview-value @interaction) value)`.

### 4.5 `laser-show.views.components.visual-editors.timeline.gesture` (NEW, `…/visual_editors/timeline/gesture.clj`)
Pure gesture state machine, so drag logic is testable without JavaFX. Justification: `on-drag` (`timeline_editor.clj:449-518`) is today the most bug-dense function in the timeline (TL-10, TL-12, TL-14).

- `start [hit {:keys [x y beat shift? ctrl? alt?]} cue view] → gesture | nil`. Kinds: `:move`, `:trim-start`, `:trim-end`, `:loop-move`, `:loop-trim-start`, `:loop-trim-end`, `:marquee`, `:scrub` (ruler).
- `update [gesture {:keys [x y beat shift?]} cue view] → gesture`, with `:op` set to the op to commit if released now. Shift held = snap off for this gesture.
- `finish [gesture] → {:op op :select {...}}`, or a selection-only result when nothing moved (movement < 3 px).
- `cancel [gesture] → nil`.
- `preview [cue gesture] → cue` = `(model/apply-op cue (:op gesture) opts)`.

### 4.6 `laser-show.views.components.visual-editors.timeline-editor` (existing)
- The canvas passes `:history-transaction? false`.
- `on-press` calls `gesture/start`. It returns `:drag-start` and, for clicks on clips, the selection events: `:replace`, Ctrl = `:toggle`, Shift = `:add`.
- `on-drag` returns `:preview-value` (the value with `:tracks` rebuilt from `gesture/preview`) and **no dispatch**.
- NEW `on-release` dispatches `{:event/type :timeline/apply-op :col :row :op op :history/description (model/op-label op)}` once.
- `on-key`:
  - Escape → `gesture/cancel` and clears the preview (and the selection when idle)
  - Delete → `:timeline/delete-selection`
  - arrows → nudge
  - Ctrl+D → `{:op :duplicate-clips}`
  - Ctrl+A → select all
  - Coordinate with `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md`.
- Marquee: dragging on an empty lane shows a rectangle in the preview; release selects the clips it intersects.
- `timeline-canvas` becomes a context component that subscribes itself to `subs/timeline-playhead` (below), so playback ticks redraw only the canvas (01 M1 step 2).
- The sidebar `list-editor` gets `:component-id [:timeline-tracks col row]`.

### 4.7 `laser-show.views.dialogs.timeline-cue-editor` (existing)
- Replace `(fx/sub-val context :chains)` (`:96`, `:230`) with `(fx/sub-ctx context subs/cue-cell-chain col row)` (`subs.clj:169`).
- Remove the `ex/active-cues` subscription (`:108-110`).
- Zone groups via NEW `subs/zone-groups-by-id` (`:150`).
- Read UI state via NEW `subs/timeline-ui`.

### 4.8 `laser-show.subs` (existing)
- NEW `timeline-ui [context col row]` → `[:ui :timeline-editor [col row]]` merged over defaults.
- NEW `timeline-prefs [context]` → `[:ui :timeline-prefs]`.
- NEW `timeline-playhead [context col row]` → `(get-in active-cues [[col row] :accumulated-beats])`. This sub still re-runs at 30 Hz, but only the canvas depends on it.
- NEW `zone-groups-by-id [context]`.

### 4.9 `laser-show.state.domains` (existing)
- Declare `[:ui :timeline-editor]` (map of `[col row]` → per-cue UI) and `[:ui :timeline-prefs]` (`{:snap {:enabled? true :value 0.25} :add-panel {...}}`).
- Remove `[:ui :timeline]` and `[:ui :canvas-drag]`. This closes those entries of 02 #21.

### 4.10 `laser-show.services.frame-service` and `laser-show.state.persistent` (existing)
- Require `laser-show.timeline.model` instead of `track-logic`.
- `in-timeline-window?` (`frame_service.clj:266-283`) is deleted in favour of `model/active-at?`.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:chains :cue-chains k …]` via `:timeline/apply-op` | document | `apply-op` handler | Yes: one entry per op, labelled by `op-label`. **Except** `:set-track-muted` and `:set-collapsed`, which only touch live fields (`:muted?`, `:collapsed?`, D10), so `wrap-history` pushes nothing and the project stays clean |
| `[:ui :timeline-editor [col row]]` NEW: `{:selection #{} :cursor 0.0 :zoom 50.0 :scroll-hvalue 0.0}` | UI state | timeline handlers | No |
| `[:ui :timeline-prefs]` NEW: `{:snap … :add-panel {:open? :preset-tab :effect-tab}}` | UI state | timeline handlers | No |
| `[:list-ui [:timeline-tracks col row]]` | UI state | list handlers | No |
| Canvas interaction (drag, hover, preview, `:transaction-open?`) | **view-local** atom in the Canvas `userData` | `interactive-canvas` mouse handlers, FX thread | No |
| `[:ui :timeline]`, `[:ui :canvas-drag]` | **deleted** | — | — |

## 6. Events and effects

| Event / effect key | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:timeline/apply-op` | NEW | `timeline_editor.clj` `on-release`, inspector, context menu | yes |
| `:timeline/add-preset-to-track`, `add-effect-to-item`, `add-track`, `add-folder`, `delete-selection`, `nudge-selection`, `toggle-loop`, `set-track-zone`, `set-clip-zone` | yes → adapters | as listed in §4.3 | yes |
| `:timeline/update-item-timing`, `move-item-to-track`, `update-loop-timing` | yes → **deleted** | — | — |
| `:canvas/update-drag`, `:canvas/clear-drag` | yes → **deleted** | — | — |
| `:history/begin-transaction` / `commit-transaction` | yes | `canvas_interaction.clj:181,212`; skipped when `:history-transaction? false` | yes |

No new side-effect keys.

## 7. Data examples and invariants

### 7.1 `apply-op`
Snap 0.25. Tracks `t1`, `t2` are leaves; `f` is a folder.

| Op | Before | After |
|---|---|---|
| `{:op :move-clips :clip-ids #{a b} :delta -3}` | a@1 len 4, b@6 len 2 | a@0, b@5 (delta clamped to −1 for both) |
| `{:op :move-clips :clip-ids #{a} :delta 0 :to-track :f}` | a on t1 | unchanged (`:f` isn't a leaf) |
| `{:op :delete-tracks :track-ids #{:t1}}` | a on t1, b on t2 | track t1 and clip a removed |
| `{:op :duplicate-clips :clip-ids #{a b}}` | a@0 len 4, b@4 len 2 on t1 | new a′@6 len 4, b′@10 len 2 on t1, fresh ids |
| `{:op :add-clip :preset-id :circle :start 3.1}` with no `:track-id`, selection none | — | clip@3.0 len 4 on the first leaf |

**Invariants:**
1. The output is normalized.
2. Ops never touch other cues.
3. `:move-clips` keeps every relative offset between the moved clips.
4. Unknown ids → identity.
5. `(apply-op c op)` is deterministic except for `random-uuid` in add/duplicate.

### 7.2 Gesture
Zoom 50 px/beat, snap 0.25, clip `a`@2 len 4 on row 1.

| Input sequence | `finish` result |
|---|---|
| press x=125 (beat 2.5, centre of a), release without moving | `{:select {:ids [a] :mode :replace}}`, no op |
| press x=125, drag to x=200, release | `{:op {:op :move-clips :clip-ids #{a} :delta 1.5}}` |
| press x=101 (left edge), drag to x=40 | `{:op {:op :trim-start :clip-id a :to 0.75}}` |
| press x=125, drag down into row 2 (leaf t2) | `{:op {:op :move-clips :clip-ids #{a} :delta 0.0 :to-track :t2}}` |
| press x=125, drag, Escape | `nil` (`cancel`) |

**Invariants:**
- The preview shown during a drag equals `apply-op` of the op that release would commit.
- A gesture never dispatches before release.
- Shift-drag produces unsnapped values.

## 8. Tests (named test vars + fixtures)

- `laser-show.timeline.model-test` (NEW, `test/laser_show/timeline/model_test.clj`):
  - `apply-op-examples-test` (§7.1)
  - `apply-op-output-is-normalized-test`: seeded random op sequences, 500 steps
  - `move-clips-keeps-relative-offsets-test`
  - `delete-tracks-deletes-clips-test`
  - `active-at?-matches-legacy-window-test`: compares against the old `in-timeline-window?` table copied into the test
  - `op-label-test`
  - plus the moved `track_logic_test.clj` cases
- `laser-show.views.components.visual-editors.timeline.gesture-test` (NEW): `gesture-examples-test` (§7.2), `preview-equals-commit-test`, `click-without-move-is-selection-only-test`.
- `laser-show.events.timeline-handlers-test` (extend): `apply-op-single-undo-entry-test` (through `events/handle-event-pipeline`), `apply-op-prunes-selection-test`, `ui-state-is-per-cell-test`.
- `laser-show.subs-test` (extend): `timeline-playhead-only-depends-on-cell-test`.
- TestFX (`laser-show.ui-test.e2e-test`, extend):
  - `timeline-drag-clip-commits-once`: drag with a NEW `ui/drag-by!` helper in `testfx.clj` (move to node + offset, press, move by dx/dy, release), then assert the start changed and there's exactly one new undo entry.
  - `timeline-escape-cancels-drag`.
- Existing canvas tests: re-run the `rotate`/`scale`/`spatial`/`curve` canvas TestFX flows (01 M3 risk note).

## 9. Out of scope / alternatives considered

- **Out of scope:** copy/paste of clips between cues (duplicate covers in-cue use), solo, ripple edits.
- **Alternatives considered:**
  - **Keep live document writes during drag and throttle them**, so the lasers follow the drag. Rejected for now: the drag preview is what the editor needs, and the output follows on release. If live output during a drag is wanted later, `on-drag` can dispatch `:timeline/apply-op` throttled to 10 Hz inside a transaction, with no model changes.
  - **A `defmulti` per op.** Rejected: a `case` in one namespace keeps the invariants in one readable place; there are about 20 ops.
  - **Keep the 24 specific events.** Rejected: one op vocabulary gives one place for invariants and labels, and it lets the gesture machine be pure.
