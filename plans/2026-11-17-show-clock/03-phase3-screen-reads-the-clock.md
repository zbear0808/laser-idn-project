# Phase 3 — The screen reads the clock

**Written:** 2026-11-17   **Written for commit:** `f7fdd79` (`master`)
**Status:** Done 2026-11-18 (`7d67aff`; as built in §10). S2 answered B, 2026-11-17; the preview's draw loop is in scope (audit A12, `00-overview.md` §9)   **Last verified against:** `7d67aff` (§10; §1 cites `dd106de`, §2–3 `f7fdd79`)
**Supersedes / Superseded by:** none   **Related findings:** S-3 (rest), S-6 (`00-overview.md` §2); review 02 M1 step 3; 01 F11 (stats part); overview T1, T5

After Phase 2, a 30 Hz mirror is the clock's only write to app state. It covers the preview frame, the positions, the global clock, MTC text and stats. Every view that reads those paths re-renders 30 times a second, and the preview draws at 30 fps.

With S2 B, the three widgets that really move every frame draw straight from the clock's latest tick on each screen refresh:
- the preview cells;
- the timeline playhead;
- the timeline's position readout.

Everything else reaches app state as an event, and only when it changes: the beat square (2 a second at 120 BPM), the stats (at most 4 a second) and the MTC text (at most 10 a second, as today). The mirror goes, and with it the last writer outside the event flow, together with the dev check's exemption list. About 1.5–2 days, with the draw loop (A12).

## 1. Current behaviour (file:line)

| What | Where | Today (after Phases 1–2, at `dd106de`) |
|---|---|---|
| Preview cells | `preview-cell` (`preview.clj:261-300`) subscribes to `preview-frame-data` (`:266`; `subs.clj:463-467` → `current-frame` `:443-450` → `ex/streaming-data`, `extractors.clj:181-182`). Its canvas draws with `:draw #(draw-preview % display-frame)` (`:296-300`): `draw-preview` (`:113-135`), `draw-frame-lines` (`:82-96`) and `draw-frame-points` (`:70-80`), on the point maps `frame->preview-data` makes (`frame_service.clj:503-523`, called by `preview->mirror` `:525-532` in the mirror) | The whole cell re-renders 30 times a second (labels and zone popup included) and draws at 30 fps. Probe c8: 367 µs and 795 KB per redraw of the 3x2 grid on the FX thread (11 ms and 23 MB a second at 30 redraws), plus 16 µs and 69 KB of conversion to maps every second tick on the clock thread |
| Stats | `preview-grid-header` (`preview.clj:336-359`, `frame-stats-label` `:328-334`) and `frame-stats-status` (`status_bar.clj:67-`) subscribe to `frame-stats` (`subs.clj:452-457`), which shares `ex/streaming-data` with the preview frame | Both re-render 30 times a second (review 01 F11) |
| Beat indicator | `beat-indicator` (`toolbar.clj:141-154`) uses only `:beat-index` from `beat-position` (`subs.clj:56-71`, on `ex/global-accumulated-beats`, `extractors.clj:107-115`), which returns a new map on every mirror write | It re-renders 30 times a second for an index that changes twice a second at 120 BPM |
| Timeline playhead and readout | `overlay-canvas` (`timeline_editor.clj:529-547`) and `readout` (`:571-590`) subscribe to `timeline-playhead` (`subs.clj:191-196`), the mirror's `[:playback :positions]`. `follow-playhead!` (`:518-527`) scrolls on page flips | Redrawn 30 times a second, from a copy that a slow event can set back for up to 33 ms (01 §10) |
| MTC status | `mtc-chip` (`timeline_editor.clj:614-`) and the inspector (`inspector.clj:186`) read `mtc-status` (`subs.clj:208-212`) from `[:backend :input :mtc]`, kept to at most 10 updates a second by `mtc/mirror-status` (`mtc.clj:193-203`) | Fine as it is; only its writer changes |
| The writer | Phase 1's mirror: `mirror!` (`show_clock.clj:104-119`), one `state/swap-state!` every `MIRROR_EVERY` (`:39`) ticks on `show-time/mirror-paths` (`show_time.clj:48-56`). The dev check ignores those paths (`without-mirror-paths`, `events/core.clj:118-133`) | c4 after Phases 1–2: 30 writes a second |

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
  - `:on-created` starts an `AnimationTimer`, kept in the node's properties; `:on-deleted` stops it, and `:on-advanced` moves it to the new instance.
  - On each pulse the timer reads `(show-time/latest)`. When the tick, the node's user data or whether the snapshot is stale changed since the last draw, it calls `(redraw! node snapshot (.getUserData node))`. It skips the pulse while the node's window isn't showing (a closed dialog stays mounted, `timeline_cue_editor.clj:218-219`), so it draws again when the window comes back.
  - A redraw that throws is logged once per node, and the timer keeps running.
  - NEW `running-timers []` counts the running timers, for the test that they stop.
- `laser-show.views.components.preview`:
  - `preview-cell` (`:261-300`) no longer subscribes to the frame. Its canvas goes through `ext-clock-redraw` with `:user-data {:zone-id … :width … :height …}`.
  - It draws `(:points @(:preview snapshot))` for the master view, or `(get-in @(:preview snapshot) [:zone-frames zone-id])` for a zone. The first cell to draw a tick forces its `:preview` delay, on the FX thread (A3).
  - With no snapshot it draws "No Preview", as today. With a snapshot older than `STALE_MS` it draws "Show clock stalled", since the engines send empty frames then (02 §4).
  - The cell itself re-renders only when its zone, labels or popup change.
  - `draw-preview [canvas points]` takes the tick's point vectors. `frame->preview-data`, `laser-point->preview-point` and `preview->mirror` (`frame_service.clj:503-532`) go.
  - First, the draw loop is rewritten (review 01 M2 steps 1–5, F13; audit A12), because this phase redraws on every tick, twice as often as today's 30 Hz. Today `draw-frame-lines` builds `(partition 2 1 points)` (`:86`), both helpers destructure a map per point, and each point or segment makes a new `Color` (`:78`, `:93`). After: one indexed loop per frame with no seqs or maps, `setStroke`/`setFill` only when the colour changes, `.setLineWidth` once per draw, and the `Font` of "No Preview" (`:102`) made once. Blanked points are skipped as `point-blanked?` (`:59-68`) does today, and so are points with a coordinate or channel that isn't finite, which the engines drop too (`frame_rules/sanitize-frame`).
- `laser-show.views.components.visual-editors.timeline-editor`:
  - `overlay-canvas` (`:529-547`) goes through `ext-clock-redraw`, with `:user-data {:col :row :view :cursor-beat :follow? :swap-state}`.
  - Its redraw takes the cue's beats from the tick (NEW `show-time/cue-beats [snapshot cell]`), calls `draw-overlay!` and, on a page flip, `swap-state` (the timer already runs on the FX thread). It remembers the scroll it asked for, so ticks before the re-render don't ask again. `follow-playhead!` goes.
  - The `readout` labels (`:571-590`) keep their cursor text from cljfx. Their timer sets the playhead's text while the tick has a position for the cue, and the cursor's again when it has none.
- `laser-show.views.toolbar`: `beat-indicator` (`:141-154`) subscribes to NEW `subs/beat-index`. The beat squares (`:131-139`) get ids `beat-square-0`…`3`.
- `laser-show.subs`:
  - NEW `beat-index` reads `[:timing :beat-index]`; it replaces `beat-position` (`:56-71`).
  - `timeline-playhead` (`:191-196`), `current-frame` (`:443-450`) and `preview-frame-data` (`:463-467`) go.
  - `frame-stats` (`:452-457`) is unchanged, but now changes at most 4 times a second.
- `laser-show.animation.show-time`:
  - The snapshot gains `:positions` (`positions`, `:239`), so the screen reads where each cue is in the tick it draws.
  - NEW `cue-beats [snapshot cell]`, NEW `status-paths` and NEW `app-status [state]`: the three app-state paths the clock reports on, and what app state holds there.
  - `mirror-paths` (`:48-56`) goes.
- `laser-show.services.show-clock`:
  - The mirror goes (`mirror!` `:104-119`, `MIRROR_EVERY` `:39`), and so does forcing the preview on the clock thread.
  - NEW status, computed every tick: `{:beat-index (int (mod global-effective-beats 4.0)) :frame-stats … :mtc …}`. Frame stats are recomputed at most every 250 ms (and keep their value while the profiler has none); MTC goes through `mirror-status`. All three use the step's `now-ms`.
  - The status is dispatched as `:show-clock/status` when it differs from app state. The same status goes again at most every 250 ms while app state still differs (as the reports do, 01 §4), so a busy agent isn't sent one per tick.
  - Its bookkeeping (`{:value :stats-at-ms :sent :sent-at-ms}`) is the runtime's `:status`, so `reset-runtime!` resets it with show time. The first tick starts from what app state holds.
- `laser-show.events.handlers.timing`: NEW `:show-clock/status` writes `[:timing :beat-index]`, `[:backend :streaming :frame-stats]` and `[:backend :input :mtc]`.
- `laser-show.events.core`: `without-mirror-paths` (`:118-119`) goes, so the lost-update check compares whole states. The namespace doc (`:36-38`) says nothing writes app state outside the event flow.
- `laser-show.state.extractors`: `global-clock` and `global-accumulated-beats` (`:101-115`) go; `beat-index` is NEW.
- `laser-show.state.domains`:
  - `[:timing :global-clock]` (`:55-58`) is replaced by `[:timing :beat-index]` (0).
  - `[:playback :positions]` (`:85-86`) and `[:backend :streaming :current-frame]` (`:408`) go.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:timing :beat-index]` NEW, 0–3 | app state | `:show-clock/status` | No / no |
| `[:backend :streaming :frame-stats]` | app state | `:show-clock/status`, at most 4 Hz | No / no |
| `[:backend :input :mtc]` | app state | `:show-clock/status`, at most 10 Hz (as today) | No / no |
| `[:timing :global-clock]`, `[:playback :positions]`, `[:backend :streaming :current-frame]` | removed | – | – |
| The snapshot's `:positions` NEW, `{cell beats}` | runtime atom (`show-time/!snapshot`) | the clock thread, once per tick | No / no |
| `(:status runtime)` NEW: `{:value :stats-at-ms :sent :sent-at-ms}` | runtime atom (`show-time/!runtime`) | the clock thread | No / no |
| Widget user data (`{:zone-id :width :height}`, the overlay's view) | the JavaFX node | the view, on each render | – |

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:show-clock/status` | NEW | the clock thread, through `events/dispatch!`, only when app state differs | yes |

## 7. Data examples and invariants

App-state writes while a show plays (expected; `app-state-is-quiet-while-a-cue-plays-test` measures them):

| Situation | Writes per second |
|---|---|
| One cue at 120 BPM, no timecode | 6: 2 beat squares and 4 stats updates (today 61, c4) |
| Streaming to projectors | up to 4 more: the output status (`output_runtime.clj:28,209-219`), whose fps changes most times it's checked |
| The same at 174 BPM | 6.9 |
| MIDI timecode running | at most 10 more (the MTC text) |
| Nothing playing | 2 (the beat square keeps moving with the global clock); the stats don't change, so they aren't sent |

What a preview cell draws:

| Cell's zone | Points drawn |
|---|---|
| nil (master) | `(:points @(:preview snapshot))`: every cue and zone, blanking between them |
| `:left` | `(get-in @(:preview snapshot) [:zone-frames :left])` |
| a zone nothing plays to | none ("No Preview", as today) |
| any, before the first tick (`start-test!`) | none ("No Preview") |
| any, the newest tick older than `STALE_MS` | none ("Show clock stalled"): the engines send empty frames then |

When the status goes out (120 BPM, ticks every 16.7 ms; `now-ms` from the step):

| App state | The clock's status | Sent? |
|---|---|---|
| beat 0 | beat 0 (unchanged) | no |
| beat 0 | beat 1 (the global clock passed beat 1) | yes, `:show-clock/status` |
| beat 0, the event still queued 16.7 ms later | beat 1, sent 16.7 ms ago | no: at most every 250 ms while app state still differs |
| beat 0, 250 ms later (the write was lost) | beat 1 | yes, again |
| stats from 100 ms ago | the stats aren't due for 150 ms | no |

**Invariants:**
1. While a show plays, app state changes only through events: the beat squares (BPM / 60 a second), at most 4 stats updates and at most 10 MTC updates a second. That is 6 a second at 120 BPM without timecode, plus at most 4 output status updates while streaming.
2. A preview cell redraws when the tick or its zone changes, at most once per screen refresh. It never re-renders its cljfx tree for a new tick.
3. The dev-mode lost-update check has no exemptions.
4. The playhead and readout show the cue's position in the newest tick the FX thread has seen.
5. A preview cell never shows a tick older than `STALE_MS`: what it shows matches what an engine on its zone sends (02 §7, invariants 4 and 7).

## 8. Tests

- `laser-show.services.show-clock-test`:
  - `app-state-is-quiet-while-a-cue-plays-test` (c4): 2 s of `tick!` at 60 Hz, one cue at 120 BPM, at most 14 writes, all on the status paths;
  - `status-is-sent-only-when-it-changes-test` (the §7 table);
  - `timecode-status-reaches-app-state-test`, replacing `mirror-carries-the-timecode-status-test`;
  - `preview-is-built-after-the-engines-wake-test`: no tick forces its preview on the clock thread any more, and forcing it gives the tick's preview data;
  - `snapshot-shape-test` checks `:positions`. `mirror-writes-once-per-two-ticks-test` goes.
- `laser-show.events.core-test/lost-update-check-has-no-exemptions-test`: a write to any of the old mirror paths between an event's snapshot and its write-back is a WARN.
- `laser-show.subs-test`: `beat-index-sub-test` replaces `timeline-playhead-only-depends-on-cell-test`. The tests that a playback tick leaves a subscription alone use a status write instead of the mirror's positions.
- `laser-show.state.history-test` uses `[:timing :beat-index]` where it used `[:timing :global-clock]` as runtime state outside the document. `frame_service_test` drops the `[:timing :global-clock]` writes nothing reads since Phase 1 (`:133`, `:161`), and `preview->mirror-test`.
- UI, `laser-show.ui-test.preview-test`:
  - NEW `preview-draws-the-clocks-tick-test`: tick the clock with a cue playing; the canvas's pixels change between two ticks;
  - NEW `preview-cell-shows-only-its-zone-test`;
  - NEW `preview-shows-a-stalled-clock-test`;
  - NEW `preview-timers-stop-with-their-cells-test`: a tab without the preview stops every cell's timer, and coming back starts them again.
- UI, `laser-show.ui-test.timeline-editor-test`:
  - NEW `timeline-readout-follows-the-clock-test`: after `show-clock/tick!` calls, the readout text moves on;
  - `timeline-play-button-starts-from-cursor` and `timeline-follows-midi-timecode` read the clock instead of app state.
- UI, NEW `laser-show.ui-test.toolbar-test/beat-indicator-moves-on-each-beat-test`.
- `timeline_visual_test` (`:75-81,99`) publishes a snapshot with the cue at beat 6.0 instead of writing app state.
- Probes: c4 counts writes to the status paths; NEW c8 (`probes/preview_draw_probes_test.clj`) times one redraw of the 3x2 grid on the FX thread. The keyboard plan's probe p2 (`plans/2026-11-13-keyboard-focus-window-lifecycle/probes/keyboard_probes_test.clj:91-96`) tells a retrigger by `[:timing :global-clock]`, which no retrigger has changed since Phase 1; it reads the restart request instead.

Run: `clj -M:mac-arm:test show-clock subs events.core history frame-service`, `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.preview-test -n laser-show.ui-test.timeline-editor-test -n laser-show.ui-test.toolbar-test`, then both full suites (the UI one in dev mode too) and the probes. Also look at the running app with plorer: the preview at the display's rate, and the playhead moving smoothly. Record the preview's draw time and allocation per redraw before and after the new loop, with the app's 6-cell layout playing, as review 01 M2 asks: probe c8 does it on the FX thread with the thread's allocation counter instead of JFR (`deps.edn` `:jfr`; `docs/PROFILING.md`), so it reruns from the command line.

## 9. Out of scope / alternatives considered

- **If S2 is A** (keep the mirror), this phase shrinks to about half a day:
  - `beat-position` becomes `beat-index`, so the toolbar re-renders on beats only;
  - the stats get their own path at 4 Hz, so the status bar and preview header stop re-rendering 30 times a second.
  - The mirror and the dev check's exemptions stay.
- The rest of review 01 M2's step 6: a flat `double[]` preview payload shared with the output. The new draw loop works on today's point vectors.
- Not drawing the preview while its tab is hidden (`01-views-ui.md:265`).
- One timer for all widgets instead of one each. JavaFX runs every `AnimationTimer` in the same pulse, so separate timers cost little (*unverified*; the UI tests and a plorer check will show).

---

## Decisions

> **DECISION S2: How does the screen get the moving parts (preview, playhead, beat)?** *(answered 2026-11-17: B)*
>
> Today the preview timer writes app state 61 times a second (c4). Every view that reads those paths re-renders 30 times a second, and the preview draws at 30 fps. Phase 1 halves this with one mirror write per two ticks.
>
> | | What | For | Against |
> |---|---|---|---|
> | A | Keep Phase 1's mirror. The clock copies the preview frame, positions, beat, MTC text and stats into app state every second tick; the beat indicator and stats get narrower subscriptions | Views stay as they are. About half a day | App state still changes 30 times a second while anything plays. The dev check keeps ignoring those paths. The preview stays at 30 fps |
> | **B (recommended)** | The preview cells, the timeline playhead and its readout draw straight from the clock's newest tick on each screen refresh. App state only gets what changes rarely, through events: the beat square, MTC text (at most 10 Hz) and stats (at most 4 Hz) | App state stays quiet while a show plays (about 6 writes a second instead of 61). The preview runs at the display's rate. The event agent becomes the only writer of app state, which closes T1's last item here | About a day more. A new pattern in the views: an animation timer on two canvases and one label. UI tests read the clock instead of app state |
> | C | As A, but the mirror goes through the event agent as an event | One writer, so no exemptions | 30 runs of the whole event pipeline a second, queued alongside input events |
>
> **Your answer:** B ("i trust your recommendations", 2026-11-17, after the audit in `00-overview.md` §9), with the preview's draw loop rewritten in this phase (A12), since B doubles how often it runs.

---

## 10. As built (2026-11-18)

Committed as `7d67aff`, on top of `c147325` (Phases 1–2's as-built docs). Before building, §1 was re-checked against `dd106de`, and §4, §5, §7 and §8 gained what turned out to be missing: the snapshot's positions, a stalled clock in the preview, where the status bookkeeping lives and how often it is sent again, who sets the readout's text, and the tests the plan had missed. §2, §3 and §6 are as written for `f7fdd79`.

**Where it is now** (at `7d67aff`)
- `views/components/clock_view.clj` NEW: `!running` `:17`, `running-timers` `:21`, `showing?` `:27`, `start-timer!` `:30`, `stop-timer!` `:53`, `ext-clock-redraw` `:58`.
- `views/components/preview.clj`: NEW `value` `:38`, `on-canvas?` `:44`, `lit?` `:47`, `draw-lines!` `:66`, `draw-dots!` `:92`, `draw-message` `:111`; `draw-preview [canvas points message]` `:117`; `get-zone-filtered-points` `:132`, now on the tick's preview; NEW `redraw-cell!` `:142`; `preview-cell` `:271`. `point-blanked?`, `draw-frame-points`, `draw-frame-lines`, `draw-no-content` and the colour helpers are gone.
- `views/components/visual_editors/timeline_editor.clj`: `draw-overlay!` `:494`; NEW `follow!` `:520` and `redraw-overlay!` `:537`; `overlay-canvas` `:551`; NEW `readout-texts` `:588` and `redraw-readout!` `:596`; `readout` `:611`. `follow-playhead!` is gone.
- `views/toolbar.clj`: `beat-square` `:131`, with ids `beat-square-0`…`3`; `beat-indicator` `:142`.
- `subs.clj`: NEW `beat-index` `:56`; `mtc-status` `:191`; `frame-stats` `:429`. `beat-position`, `timeline-playhead`, `current-frame` and `preview-frame-data` are gone.
- `animation/show_time.clj`: NEW `status-paths` `:51` and `app-status` `:58`; `report-interval-ms` `:63`; `positions` `:245`; `global-beats` `:282`, with a NEW arity for a given runtime; NEW `cue-beats` `:287`. `mirror-paths` is gone.
- `services/show_clock.clj`: NEW `stats-interval-ms` `:39`; `frame-stats` `:93`; NEW `status-step` `:104`; `tick!` `:133`, which puts `:positions` in the snapshot and works out the status after `publish!`. `MIRROR_EVERY` and `mirror!` are gone.
- `events/handlers/timing.clj`: NEW `handle-show-clock-status` `:147`, routed at `:251`. `events/core.clj`: `check-lost-update!` `:121`, with no exemptions (`without-mirror-paths` is gone); the namespace doc's list of writers outside the pipeline `:36`.
- `state/extractors.clj`: NEW `beat-index` `:101`; `global-clock` and `global-accumulated-beats` are gone. `state/domains.clj`: NEW `[:timing :beat-index]` `:55`; `[:timing :global-clock]`, `[:playback :positions]` and `[:backend :streaming :current-frame]` are gone.
- `services/frame_service.clj`: `laser-point->preview-point`, `frame->preview-data` and `preview->mirror` are gone (532 → 497 lines).
- `test/laser_show/ui_test/testfx.clj`: NEW `node-pixels` `:568` and `on-fx-thread` `:582`.

**Differences from the plan above**

| Plan | As built | Why |
|---|---|---|
| The status is computed every tick (§4) | After `publish!`: the step, the render and the engines' wake-up come first | Every 250 ms the frame stats sort the profiler's last second, and the engines shouldn't wait for that |
| The overlay remembers the scroll it asked for (§4) | It remembers the pair (the view's scroll when it asked, the scroll it asked for), so it asks again once the view has changed | With the scroll alone, a flip to a value the view had already reached once, and left by hand, would never be asked for |
| A cell draws `(get-in @(:preview snapshot) [:zone-frames zone-id])` (§4) | Through `get-zone-filtered-points`, which stays and now reads the tick's preview | Its unit test (`views.components.preview-test`) pins the lookup: `:all` is a zone like any other, and a missing zone shows nothing |
| – | NEW: a preview canvas's accessible text says what it shows: "Laser preview", "No Preview" or "Show clock stalled" | For screen readers; the UI tests read it instead of comparing the pixels of glyphs |
| – | NEW test helpers `ui/node-pixels` and `ui/on-fx-thread` | The preview tests compare a canvas's pixels |
| A widget redraws when the tick changed (§4, invariant 2) | The preview cell and the overlay also skip the draw when what they would draw is what they drew last: a cell that shows "No Preview", or a paused cue's playhead, isn't drawn again on every tick | Found in the running app: a new tick every 16.7 ms meant every empty cell drew "No Preview" again each time |

**Tests**
- `show-clock-test`: NEW `app-state-is-quiet-while-a-cue-plays-test` (2 s of ticks, at most 14 writes, all on the status paths), `status-is-sent-only-when-it-changes-test` (the §7 table) and `timecode-status-reaches-app-state-test`; `preview-is-built-after-the-engines-wake-test` (nothing forces the preview on the clock thread) and `snapshot-shape-test` (`:positions`, `cue-beats`) are updated; `mirror-writes-once-per-two-ticks-test` and `mirror-carries-the-timecode-status-test` are gone.
- `core-test`: NEW `lost-update-check-has-no-exemptions-test`; `lost-update-detector-test` loses its case for the mirror.
- `subs-test`: NEW `beat-index-sub-test` replaces `timeline-playhead-only-depends-on-cell-test`; three tests write the status where they wrote the mirror's positions. `history-test` and `frame-service-test` as in §8.
- UI, `preview-test`: NEW `preview-draws-the-clocks-tick-test`, `preview-cell-shows-only-its-zone-test`, `preview-shows-a-stalled-clock-test` and `preview-timers-stop-with-their-cells-test`. Their waits tick again on every try, since a cell draws only a tick newer than `STALE_MS`. *(2026-11-18, `160a4e5`: they tick from a thread every 16 ms instead (`with-clock`). On the macOS CI runner one try took longer than `STALE_MS`, so `preview-cell-shows-only-its-zone-test` saw the cells say "Show clock stalled".)*
- UI, `timeline-editor-test`: NEW `timeline-readout-follows-the-clock-test`; `tick-clock!` ticks once, and `position` reads the clock. UI, `toolbar-test`: NEW `beat-indicator-moves-on-each-beat-test`. `timeline-visual-test` publishes a snapshot with the cue at beat 6.0 and waits for the readout to show it before its screenshot.
- Red first: with the tests and no implementation, `core-test` had 5 failures, one per old mirror path except `[:timing :beat-index]`, which was never exempt. `show-clock-test` and `subs-test` didn't compile (no `show-time/cue-beats`, no `subs/beat-index`). The UI tests `beat-indicator-moves-on-each-beat-test`, `timeline-readout-follows-the-clock-test` and `timeline-follows-midi-timecode` timed out (one tick wrote no mirror), and `preview-test` couldn't load (no `clock-view`).
- The first green run found two mistakes, both fixed before the runs below: the readout's redraw read its labels with `nth`, which a node's children list doesn't support, and `preview-draws-the-clocks-tick-test` expected the square to get wider, but the sine starts at its peak (`time.clj:39-43`).
- `output-lifecycle-test/^:timing every-projector-sends-every-tick-once-test` (c1, Phase 2) failed its order check in its first run here: its cue turned by a 1-beat sine, which passes through the same angle twice a beat, so the samples of a frame could name two ticks 250 ms apart. Its cue now turns at constant speed (a 16-beat sawtooth), so every tick's frame differs. The engines didn't change in this phase.

**Probes** (before = `dd106de`, after = `7d67aff`)

| Probe | Before | After |
|---|---|---|
| c4 | 30 app-state writes a second, each changing the global clock, the positions and the preview frame | **6**: 2 move the beat square and 4 update the stats; nothing else changes |
| c8 (NEW) | 367 µs and 795 KB per redraw of the 3x2 grid on the FX thread: 11 ms and 23 MB a second at 30 redraws. Plus 16 µs and 69 KB of conversion to maps every second tick on the clock thread | 88 µs and 1.1 KB per redraw: 5.3 ms and 0.1 MB a second at 60. No conversion: the tick's join (`preview-data`) costs 2 µs, once per tick |
| c1–c3, c5–c7 | as in 01 §10 and 02 §10 | the same: c1 120 distinct ticks in 120 frames; c2 60–60.5 renders a second with 0 to 4 projectors; c3 36 and 179 µs medians; c5 0 times backwards; c6 61 distinct frames; c7 1 and 16 contexts (3 % and 5 %) |

**In the running app** (test mode, headless with the software renderer, the clock started by hand, the 3x2 layout; the forms were in `debug-output/`, which git ignores):
- The preview showed the playing square in the master cell and "No Preview" in the five zone cells; the cell texts read the same. A square turning at constant speed turned on screen.
- App state changed 6 to 7 times a second while a cue played: the beat square and the stats.
- Before the skip above, each cell redrew 44 times a second and the overlay and readout 28.5, against 51 and 30 FX pulses a second in the same windows; the clock ticked 60 times a second. Headless, the pulse came at 30–51 a second, so this shows the pulse rate, not a display's. With the skip, a cue turning at constant speed was drawn on 36.5 of 39 pulses a second, and the five empty cells and a paused cue's playhead weren't drawn at all.
- In the timeline editor the playhead and the readout moved together: the readout read 3.2.4, 3.3.2, 3.3.4, 3.4.3, 4.1.1, 4.1.4, 4.2.2 and 4.2.4 at 250 ms intervals, half a beat apart at 120 BPM.

**Verification, 2026-11-18, macOS arm64, JDK 25.0.5, headless, load average 1–5**

| Check | Result |
|---|---|
| `clj -M:mac-arm:test show-clock subs events.core history frame-service timing-handlers multi-engine show-time views.components.preview` | 156 tests, 609 assertions, 0 failures |
| `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.preview-test -n laser-show.ui-test.timeline-editor-test -n laser-show.ui-test.toolbar-test` | 35 tests, 134 assertions, 0 failures |
| `clj -M:mac-arm:test` (full unit suite) | 867 tests, 32,462 assertions, 0 failures (866 / 32,450 at `dd106de`), at `7d67aff` |
| `clj -M:test-ui:mac-arm:test-ui-headless` (full UI suite) | 61 tests, 222 assertions, 0 failures (55 / 209 at `dd106de`), run before the redraw skip |
| The same with `-J-Dlaser-show.dev=true` | 61 tests, 222 assertions, 0 failures, at `7d67aff`. One lost-update WARN, the same one as at `dd106de` (01 §10): `cue-chain-list-test` writes app state while its own drag events are queued |
| `clj -M:mac-arm:test-timing engine-wire output-lifecycle show-clock` | 5 tests, 25 assertions, 0 failures in 2 of 3 runs. In the third, `engine-wire-test/engine-holds-60-fps-test` (an engine pacing itself, which this phase doesn't touch) sent 117 frames in 2 s against its bound of 118; on its own it passed 4 runs of 4 |
| `clj -M:mac-arm:test-integration` | 17 tests, 57 assertions, 0 failures |
| `clj -M:kondo --lint` on the files changed | 26 files: 0 errors; the 3 warnings `history_test.clj` had before. Over `src test dev`: 8 errors and 22 warnings, as at `dd106de` |

Observed, not changed:
- The timeline editor's dialog stays mounted while it is closed (`timeline_cue_editor.clj:218-219`), so its overlay and readout keep their timers, which skip every pulse while the window is hidden.

Not testable here: a real DAC, and the hidden-window check (output runtime `05-phase5-frame-timing.md` §8). That check should now also watch the preview's redraw rate: an `AnimationTimer` runs on the FX pulse, which macOS App Nap and Windows' timer throttling may slow while the window is hidden.
