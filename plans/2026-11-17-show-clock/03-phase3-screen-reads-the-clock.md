# Phase 3 — The screen reads the clock

**Written:** 2026-11-17   **Written for commit:** `f7fdd79` (`master`)
**Status:** Proposed. Needs S2 (below); this doc is written for its recommended option, B   **Last verified against:** `f7fdd79` (citations). Phases 1 and 2 change the subscriptions and the preview data cited here; re-check them when they land
**Supersedes / Superseded by:** none   **Related findings:** S-3 (rest), S-6 (`00-overview.md` §2); review 02 M1 step 3; 01 F11 (stats part); overview T1, T5

After Phase 2, a 30 Hz mirror is the clock's only write to app state. It covers the preview frame, the positions, the global clock, MTC text and stats. Every view that reads those paths re-renders 30 times a second, and the preview draws at 30 fps.

With S2 B, the three widgets that really move every frame draw straight from the clock's latest tick on each screen refresh:
- the preview cells;
- the timeline playhead;
- the timeline's position readout.

Everything else reaches app state as an event, and only when it changes: the beat square (2 a second at 120 BPM), the stats (at most 4 a second) and the MTC text (at most 10 a second, as today). The mirror goes, and with it the last writer outside the event flow, together with the dev check's exemption list. About 1–1.5 days.

## 1. Current behaviour (file:line)

| What | Where | Today (after Phase 2) |
|---|---|---|
| Preview cells | `preview-cell` (`preview.clj:261-300`) subscribes to `preview-frame-data` (`:266`), which reads `subs.clj:462-466` → `current-frame` (`:442-449`) → `ex/streaming-data`. It draws through `:draw #(draw-preview % display-frame)` (`:296-300`): `draw-preview` (`:113-135`), `draw-frame-points` (`:70-80`) and `draw-frame-lines` (`:82-98`), on maps from `frame->preview-data` (`frame_service.clj:778-798`) | The whole cell re-renders 30 times a second (labels and zone popup included) and draws at 30 fps |
| Stats | `preview-grid-header` (`preview.clj:336-359`, `frame-stats-label` `:328-334`) and `frame-stats-status` (`status_bar.clj:67-`) subscribe to `frame-stats` (`subs.clj:451-456`). It shares `ex/streaming-data` with the preview frame | Both re-render 30 times a second (review 01 F11) |
| Beat indicator | `beat-indicator` (`toolbar.clj:141-154`) uses only `:beat-index` from `beat-position` (`subs.clj:56-71`), which returns a new map (with `:beat-phase`) every tick | It re-renders 30 times a second for an index that changes twice a second at 120 BPM |
| Timeline playhead and readout | `overlay-canvas` (`timeline_editor.clj:529-547`) and `readout` (`:571-590`) subscribe to `timeline-playhead` (`subs.clj:191-195`). `follow-playhead!` (`:518-527`) scrolls on page flips | Redrawn 30 times a second |
| MTC status | `mtc-chip` (`timeline_editor.clj:614-629`) and the inspector (`inspector.clj:186`) read `mtc-status` (`subs.clj:207-211`) from `[:backend :input :mtc]`, kept to at most 10 updates a second by `mtc/mirror-status` (`mtc.clj:193-203`) | Fine as it is; only its writer changes |
| The writer | Phase 1's mirror: one `state/swap-state!` every second tick on `mirror-paths`. The dev check ignores those paths (`events/core.clj:119-133`) | c4 after Phase 1: 30.5 writes a second |

## 2. Root cause

The views take per-frame data through app state. Each value they need is one of two kinds: it changes every frame, or it changes a few times a second. They come through the same 30 Hz write.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `fx/ext-on-instance-lifecycle` with `:on-created`, plus `:user-data` for the latest props (`projectors.clj:49-75`; `canvas_interaction.clj:183-`) | Starts and stops one `AnimationTimer` per widget. The timer reads the props the widget last rendered with from `.getUserData` |
| `draw-preview` and its helpers (`preview.clj:59-135`) | Kept. They read the tick's point vectors (`t/X` … `t/blanked?`) instead of preview maps |
| `draw-overlay!` and `follow-playhead!` (`timeline_editor.clj:492-527`) | Called from the overlay's timer |
| `bar-beat-sub` and the `tc` functions used by `readout` (`:571-590`) | The readout's timer sets the two labels' texts with them |
| `output-runtime/publish-status!` (`output_runtime.clj:209-219`): dispatch only while app state differs | The clock's `:show-clock/status` |
| `mtc/mirror-status` (`mtc.clj:193-203`) | Unchanged limit, now in that status |

## 4. Changes per namespace

- **`laser-show.views.components.clock-view`** (NEW, small). There are three widgets, and each needs the same start, stop and redraw-on-new-tick logic.
  - `ext-clock-redraw [{:keys [desc redraw!]}]` wraps a desc in `fx/ext-on-instance-lifecycle`.
  - `:on-created` starts an `AnimationTimer`; `:on-deleted` stops it.
  - On each pulse the timer reads `(show-time/latest)`. When the tick or the node's user data changed since the last draw, it calls `(redraw! node snapshot (.getUserData node))`.
- `laser-show.views.components.preview`:
  - `preview-cell` (`:261-300`) no longer subscribes to the frame. Its canvas goes through `ext-clock-redraw` with `:user-data {:zone-id …}`.
  - It draws `(:points (:preview snapshot))` for the master view, or `(get-in snapshot [:preview :zone-frames zone-id])` for a zone.
  - The cell itself re-renders only when its zone, labels or popup change.
  - The draw helpers (`:59-98`) take point vectors. `frame->preview-data` and `laser-point->preview-point` (`frame_service.clj:778-798`) go.
- `laser-show.views.components.visual-editors.timeline-editor`:
  - `overlay-canvas` (`:529-547`) goes through `ext-clock-redraw`, with `:user-data {:col :row :view :cursor-beat :follow? :swap-state}`.
  - Its redraw takes the cue's beats from the tick (NEW `show-time/cue-beats [snapshot cell]`), calls `draw-overlay!` and, on a page flip, `swap-state` (the timer already runs on the FX thread).
  - The `readout` labels (`:571-590`) get their text from the same timer while the cue plays, and from the cursor when it doesn't.
- `laser-show.views.toolbar`: `beat-indicator` (`:141-154`) subscribes to NEW `subs/beat-index`.
- `laser-show.subs`:
  - NEW `beat-index` reads `[:timing :beat-index]`; it replaces `beat-position` (`:56-71`).
  - `timeline-playhead` (`:191-195`), `current-frame` and `preview-frame-data` (`:442-466`) go.
  - `frame-stats` (`:451-456`) is unchanged, but now changes at most 4 times a second.
- `laser-show.services.show-clock`:
  - The mirror goes, and `show-time/mirror-paths` with it.
  - NEW status, checked every tick: `{:beat-index (int (mod global-effective-beats 4.0)) :frame-stats … :mtc …}`. Frame stats refresh at most every 250 ms; MTC goes through `mirror-status`.
  - The status is dispatched as `:show-clock/status` when it differs from app state.
- `laser-show.events.handlers.timing`: NEW `:show-clock/status` writes `[:timing :beat-index]`, `[:backend :streaming :frame-stats]` and `[:backend :input :mtc]`.
- `laser-show.events.core`: `without-timer-paths` (`:119-120`) goes, so the lost-update check compares whole states. The namespace doc (`:36-39`) says nothing writes app state outside the event flow.
- `laser-show.state.extractors`: `global-clock` and `global-accumulated-beats` (`:101-115`) go; `beat-index` is NEW.
- `laser-show.state.domains`:
  - `[:timing :global-clock]` (`:55-58`) is replaced by `[:timing :beat-index]` (0).
  - `[:playback :positions]` and `[:backend :streaming :current-frame]` go.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:timing :beat-index]` NEW, 0–3 | app state | `:show-clock/status` | No / no |
| `[:backend :streaming :frame-stats]` | app state | `:show-clock/status`, at most 4 Hz | No / no |
| `[:backend :input :mtc]` | app state | `:show-clock/status`, at most 10 Hz (as today) | No / no |
| `[:timing :global-clock]`, `[:playback :positions]`, `[:backend :streaming :current-frame]` | removed | – | – |
| Widget user data (`{:zone-id}`, the overlay's view) | the JavaFX node | the view, on each render | – |

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:show-clock/status` | NEW | the clock thread, through `events/dispatch!`, only when app state differs | yes |

## 7. Data examples and invariants

App-state writes while a show plays (expected; `app-state-is-quiet-while-a-cue-plays-test` measures them):

| Situation | Writes per second |
|---|---|
| One cue at 120 BPM, no timecode | 6: 2 beat squares and 4 stats updates (today 61, c4) |
| The same at 174 BPM | 6.9 |
| MIDI timecode running | at most 10 more (the MTC text) |
| Nothing playing | 2 (the beat square keeps moving with the global clock); the stats don't change, so they aren't sent |

What a preview cell draws:

| Cell's zone | Points drawn |
|---|---|
| nil (master) | `(:points (:preview snapshot))`: every cue and zone, blanking between them |
| `:left` | `(get-in snapshot [:preview :zone-frames :left])` |
| a zone nothing plays to | none ("No Preview", as today) |

**Invariants:**
1. While a show plays, app state changes only through events: the beat squares (BPM / 60 a second), at most 4 stats updates and at most 10 MTC updates a second. That is 6 a second at 120 BPM without timecode.
2. A preview cell redraws when the tick or its zone changes, at most once per screen refresh. It never re-renders its cljfx tree for a new tick.
3. The dev-mode lost-update check has no exemptions.
4. The playhead and readout show the cue's position in the newest tick the FX thread has seen.

## 8. Tests

- `laser-show.services.show-clock-test`:
  - `app-state-is-quiet-while-a-cue-plays-test` (c4): 2 s of `tick!` at 60 Hz, one cue at 120 BPM, at most 14 writes;
  - `status-is-sent-only-when-it-changes-test`.
- `laser-show.events.core-test/lost-update-check-has-no-exemptions-test`.
- `laser-show.subs-test`: `beat-index-sub-test` replaces `timeline-playhead-only-depends-on-cell-test`.
- UI, NEW `laser-show.ui-test.preview-test`:
  - `preview-draws-the-clocks-tick-test`: tick the clock with a cue playing; the canvas's pixels change between two ticks;
  - `preview-cell-shows-only-its-zone-test`.
- UI, `laser-show.ui-test.timeline-editor-test`:
  - NEW `timeline-readout-follows-the-clock-test`: after `show-clock/tick!` calls, the readout text moves on;
  - `timeline-play-button-starts-from-cursor` and `timeline-follows-midi-timecode` read the clock instead of app state.
- UI, NEW `laser-show.ui-test.toolbar-test/beat-indicator-moves-on-each-beat-test`.
- `timeline_visual_test` (`:75-77,95`) seeds the clock's runtime with a run at beat 6.0 instead of app state.

Run: `clj -M:mac-arm:test show-clock subs events.core`, `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.preview-test -n laser-show.ui-test.timeline-editor-test -n laser-show.ui-test.toolbar-test`, then both full suites. Also look at the running app with plorer: the preview at the display's rate, and the playhead moving smoothly.

## 9. Out of scope / alternatives considered

- **If S2 is A** (keep the mirror), this phase shrinks to about half a day:
  - `beat-position` becomes `beat-index`, so the toolbar re-renders on beats only;
  - the stats get their own path at 4 Hz, so the status bar and preview header stop re-rendering 30 times a second.
  - The mirror and the dev check's exemptions stay.
- The preview draw loop's other allocations (review 01 F13: `partition 2 1`, a new `Color` per point).
- Not drawing the preview while its tab is hidden (`01-views-ui.md:265`).
- One timer for all widgets instead of one each. JavaFX runs every `AnimationTimer` in the same pulse, so separate timers cost little (*unverified*; the UI tests and a plorer check will show).

---

## Decisions

> **DECISION S2: How does the screen get the moving parts (preview, playhead, beat)?** *(open · needed before Phase 3 · recommended: B)*
>
> Today the preview timer writes app state 61 times a second (c4). Every view that reads those paths re-renders 30 times a second, and the preview draws at 30 fps. Phase 1 halves this with one mirror write per two ticks.
>
> | | What | For | Against |
> |---|---|---|---|
> | A | Keep Phase 1's mirror. The clock copies the preview frame, positions, beat, MTC text and stats into app state every second tick; the beat indicator and stats get narrower subscriptions | Views stay as they are. About half a day | App state still changes 30 times a second while anything plays. The dev check keeps ignoring those paths. The preview stays at 30 fps |
> | **B (recommended)** | The preview cells, the timeline playhead and its readout draw straight from the clock's newest tick on each screen refresh. App state only gets what changes rarely, through events: the beat square, MTC text (at most 10 Hz) and stats (at most 4 Hz) | App state stays quiet while a show plays (about 6 writes a second instead of 61). The preview runs at the display's rate. The event agent becomes the only writer of app state, which closes T1's last item here | About a day more. A new pattern in the views: an animation timer on two canvases and one label. UI tests read the clock instead of app state |
> | C | As A, but the mirror goes through the event agent as an event | One writer, so no exemptions | 30 runs of the whole event pipeline a second, queued alongside input events |
>
> **Your answer:** _pending_
