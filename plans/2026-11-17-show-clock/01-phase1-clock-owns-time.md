# Phase 1 — The clock owns show time

**Written:** 2026-11-17   **Written for commit:** `f7fdd79` (`master`)
**Status:** Proposed. Audit changes A10 and A11 (`00-overview.md` §9) folded in 2026-11-17   **Last verified against:** `f7fdd79` (citations; probes c1, c4–c6 in `00-overview.md` §1)
**Supersedes / Superseded by:** none   **Related findings:** S-1, S-3, S-4 (`00-overview.md` §2); output runtime O-17; review 04 #11, #25 (part); 02 M1 step 3; overview T1; timeline TL-28 (clock part)

Today the 30 Hz preview timer advances show time, inside app state. Event handlers write the same paths from their own snapshots, so a slow event can put a cue back in time (c5).

This phase moves show time into a runtime atom that only a new 60 Hz clock thread writes. App state keeps what events decide: which cues play, how each started, and transport requests (restart, align, phase shift, seek). The clock reads that once per tick. It sends back what it learns (a timeline ended, a timecode chase started or stopped) as events. Every second tick it mirrors positions and the preview frame into app state, so that views and subscriptions work unchanged until Phase 3. The preview timer goes away.

Engines and the preview still render for themselves in this phase; they just read positions from the clock. About 2 days, with no decision needed.

## 1. Current behaviour (file:line)

| What | Where | Today |
|---|---|---|
| Who advances show time | `update-timing-accumulators!` (`frame_service.clj:121-158`). Its only caller is `update-preview-frame!` (`:821-847`, at `:827`), on a `java.util.Timer` named `preview-update` every 33 ms (`start-preview-updates!`, `:863-883`) | Uses the wall clock (`System/currentTimeMillis`, `:827`). Two `state/swap-state!` per tick (`:141`, `:843`), outside the event flow |
| Where positions live | `[:timing :global-clock]` (`domains.clj:55-58`) and `[:playback :active-cues cell]` (`domains.clj:75-82`). Built by `create-cue-timing-state` (`cue_timing.clj:252-270`) through `activate-cue` (`:309-328`) | App state, written both by the timer and by events |
| Events that write positions | Grid `trigger-cue` (`grid.clj:33-44`; reads `System/currentTimeMillis` at `:38`, ignoring the event's `:time`), move-cell (`:149-169`), clear-cell (`:187-195`). Timeline play, pause, stop and seek (`timeline.clj:378-426`). Transport stop and retrigger (`handlers/timing.clj:68-86`), tap tempo (`:29-46`), set-phase-offset-target (`:115-137`). The history prune (`history.clj:64-72`) and `reset-session` (`persistent.clj:135-150`) | Each handler computes a whole new state from its snapshot. The positions it starts from are up to 33 ms old |
| Who reads positions | Engines: `projector-frame` (`multi_engine.clj:134-188`), cue timings at `:141,156`, the first cue's for projector effects at `:171-175`. The preview: `generate-current-frame` (`frame_service.clj:673-772`, `:711`). Global effects: the global clock at `:506`. Subscriptions: `beat-position` (`subs.clj:56-71`), `timeline-playhead` (`:191-195`). Handlers: the global beats for a new cue's phase target (`grid.clj:43`, `timeline.clj:390`) and `sync-to-downbeat` (`handlers/timing.clj:107-113`) | Positions are whatever the last write left |
| Timeline end, MTC chase | `advance-active-cues` (`frame_service.clj:84-107`) deactivates ended timelines; `apply-chase` (`playback.clj:69-103`) activates and positions chasing ones; the MTC status mirror (`mtc.clj:193-203`) | All three run inside the timer's swap |
| Dev-mode lost-update check | `preview-timer-paths` (`frame_service.clj:109-119`); `check-lost-update!` (`events/core.clj:122-133`) | It ignores seven paths, `[:playback :active-cues]` among them, so c5 goes unreported |
| Lifecycle | Started at `app.clj:152`, stopped at `app.clj:228`. A code reload stops it and starts it again (`dev/reload.clj:117-137`). `start-test!` (`app.clj:249-267`) never starts it, so UI tests call `update-timing-accumulators!` by hand (`timeline_editor_test.clj:248-250,277`) | The output freezes while it's stopped (c6) |

## 2. Root cause

Show time is high-frequency runtime data, but it lives in app state (against `docs/CLJFX_STATE_MANAGEMENT.md:650-663`). Two kinds of writer change the same paths there: events, and the timer outside the event flow. Its only driver is the preview.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `update-global-clock`, `update-cue-timing`, `align-global-clock-phase`, `align-cue-timing-phase`, `reset-global-clock`, `reset-cue-timing`, `seek`, `get-cue-timing-context` (`cue_timing.clj:55-344`) | Unchanged maths. The clock calls them on its own maps, at 60 Hz |
| `activate-cue` (`cue_timing.clj:309-328`) | Its timing half becomes NEW `start-timing`, which the clock uses for new runs. `activate-cue` keeps the bookkeeping (`:playing?`, `:active-cell`) and writes an intent |
| `advance-active-cues` (`frame_service.clj:84-107`) | Moves into the pure step, with the same per-cue tempo, loop and timeline arguments (`:93-103`) |
| `apply-chase` (`playback.clj:69-103`) | Unchanged, applied to the clock's chase map. Its `activate-cue` call (`:90`) becomes `start-timing` |
| `mtc/current` (`mtc.clj:222-225`), `mtc/mirror-status` (`:193-203`) | Read once per tick; the status keeps its 10 Hz limit |
| `timing/park-until` (`common/timing.clj:92-104`), `next-deadline` (`streaming_engine.clj:251-259`) | The clock's pacing, like Phase 5's engines. `next-deadline` moves to `laser-show.common.timing`, so both share one copy |
| `output-errors` `report!` / `ok!` | The tick reports under `[:show-clock]` (today `[:preview]`, `frame_service.clj:877-879`) |
| `output-runtime/publish-status!` (`output_runtime.clj:209-219`): dispatch only while app state differs | Used the same way for the clock's reports, so a lost report is sent again |
| `events/dispatch!` (`events/core.clj:549-`) | How the clock's reports reach the agent |

## 4. Changes per namespace

Show time is split across two NEW namespaces, the way `laser-show.input.mtc` already splits the timecode: pure steps, one runtime atom and readers in one (`mtc.clj:152-225`), callers on other threads elsewhere.
- **`show-time`** is the data: everything that reads show time requires it.
- **`show-clock`** is the thread, and only `app` requires it.

This avoids a require cycle. The thread needs `frame-service` (to render) and `events.core` (to dispatch), while `frame-service`, `multi-engine` and `events.core` need to read show time.

- **`laser-show.animation.show-time`** (NEW). The step is pure and has many cases to test, so it doesn't go into the 880-line `frame-service`.
  - `step [runtime state mtc-pos now-ms] → {:runtime r :reports [event …]}`. In this order:
    1. **Requests.** `[:playback :requests]` holds `:restart`, `:align` and `:shift`. A request applies once when its `:id` is non-nil and differs from the one in `(:applied runtime)`:
       - restart: `reset-global-clock` and `reset-cue-timing` on every run (today's stop and retrigger, `handlers/timing.clj:60-86`);
       - align: the two align fns, so timeline runs are unchanged (`:29-46`);
       - shift: the target becomes current phase + `:offset` on the global clock and on non-timeline runs (`:115-137`).
    2. **Runs.** Each intent in `[:playback :active-cues]` names a run.
       - A run id the runtime doesn't have yet starts with `start-timing`, at `now-ms`, from the global clock's effective beats.
       - Existing runs take `:cell`, `:paused?` and `:timeline?` from the intent.
       - A `:seek` whose `:id` differs from the run's `:seek-id` applies `seek`.
       - Runs that no intent names are dropped.
       - Intents with `:chasing? true` are left to step 4.
    3. **Advance.** The global clock goes through `update-global-clock`. Each run goes through `update-cue-timing`, with `playback/cue-bpm`, the chain's `:loop` and `playback/timeline-cfg`, as `advance-active-cues` does today.
    4. **Chase.** `apply-chase` runs over `{:active-cues (:chase runtime)}`.
    5. **Reports.**
       - `{:event/type :show-clock/cue-ended :cell c :run r}` while a run is `:ended?` and app state still has its intent. The run stays, held at its end, but isn't rendered.
       - `{:event/type :show-clock/chase :cell c :active? b}` while the chase map and app state's chasing intents disagree.
       - Each report is sent at most once every 250 ms per cell (`report-interval-ms`).
  - `positions [runtime] → {cell beats}`, used by the mirror and by Phase 3.
  - `!runtime` (defonce atom), initially `{:tick 0 :global {…} :runs {} :chase {} :applied {} :sent {}}`. Only the clock thread writes it; `show-clock/tick!` writes it in tests. `reset-runtime! []` is for tests and `show-clock/start!`.
  - Readers, used by the render (which still runs on the engines' threads and in the mirror) and by effects:
    - `active-runs [state]` → `[{:cell :cue-chain :cue-timing} …]`, in today's order. It joins app-state intents with their runs, leaving out ended runs and chains without items (as `frame_service.clj:195`), and adds the active chase entries;
    - `global-clock []`;
    - `global-beats []`.
  - `mirror-paths`: `[[:timing :global-clock] [:playback :positions] [:backend :input :mtc] [:backend :streaming :current-frame] [:backend :streaming :frame-stats]]`. It replaces `frame-service/preview-timer-paths`.
- **`laser-show.services.show-clock`** (NEW): the thread. It is kept out of `frame-service` for the same reason `output-runtime` is its own namespace (`output_runtime.clj:1-18`): it owns a thread.
  - Constants: `TICK_HZ` 60 and `MIRROR_EVERY` 2.
  - `tick! [now-ns]`, public:
    - one `state/get-raw-state`, one `mtc/current`, then `show-time/step`, and the result goes into `show-time/!runtime`;
    - every second tick, the mirror (§5): one `state/swap-state!` that touches only `mirror-paths`. The preview frame in it comes from `frame-service/get-preview-frame`, as today;
    - then each report through `events/dispatch!`.
    - `now-ms` is fractional milliseconds since the first tick after `start!` or a reset: `(/ (- now-ns t0) 1e6)` on `System/nanoTime`. Whole milliseconds would step 16, 17, 17, 16 ms from tick to tick, about 6 % step jitter at 60 Hz; `update-global-clock` and `update-cue-timing` already take doubles (`cue_timing.clj:102,158`) (audit A10).
  - `start! []`, `stop! []`, idempotent. They run one daemon thread, `show-clock`, that loops:
    - `park-until` the deadline;
    - `(tick! (System/nanoTime))`, catching any `Throwable` into `output-errors` `[:show-clock]`;
    - the next deadline from `next-deadline`, which skips missed ticks.
    - `start!` resets the runtime: a stopped app keeps no positions, as today.
- `laser-show.animation.cue-timing`:
  - NEW `start-timing [now-ms global-beats opts]`: what `activate-cue` computes today (`:314-322`).
  - `activate-cue [playback cell run-id {:keys [timeline? start-beat]}]` writes the intent `{:run run-id :timeline? … :start-beat …}` and the bookkeeping. It no longer takes a time.
  - NEW `request-seek [intent beat id]`.
  - `deactivate-cue` is unchanged.
- `laser-show.events.handlers.grid`: `trigger-cue` (`:33-44`) calls `activate-cue` with `(random-uuid)`, as handlers already do for new ids (`timeline.clj:278`), and reads no time. Move and clear (`:149-169`, `:187-195`) are unchanged: the intent moves, or goes, with its run id.
- `laser-show.events.handlers.timeline`:
  - `handle-play` (`:378-397`): a new run at `start-beat` for a stopped timeline. A paused one gets `:paused? false`, plus a `request-seek` when `:from` is given.
  - `handle-seek` (`:419-426`) uses `request-seek`.
  - `handle-pause` and `handle-stop` (`:399-417`) are unchanged.
- `laser-show.events.handlers.timing`:
  - stop (`:68-77`) and retrigger (`:79-86`) write `[:playback :requests :restart]` `{:id (random-uuid)}`. Stop still sets `:playing? false`.
  - Tap tempo (`:29-46`) writes `:align` (the tap times are unchanged).
  - `set-phase-offset-target` (`:115-137`) writes `:shift {:id … :offset …}`. Its write to `[:playback :phase-offset-target]` (`:121`), which nothing reads, goes.
  - `sync-to-downbeat` (`:107-113`) returns `{:link/sync-to-downbeat {}}`. The effect (`events/core.clj:286-289`) reads `(show-time/global-beats)` itself.
  - NEW `:show-clock/cue-ended` deactivates the cell if its intent still has that run.
  - NEW `:show-clock/chase` adds `{:chasing? true :timeline? true}` (plus `:playing? true` and `:active-cell`, as `activate-cue` does) or deactivates.
  - The `"show-clock"` domain is routed to `timing/handle` (`events/handlers.clj:44-45`).
- `laser-show.services.frame-service`:
  - These go: `update-timing-accumulators!`, `advance-active-cues`, `update-preview-frame!`, `start-preview-updates!`, `stop-preview-updates!`, `preview-timer-paths` and the deprecated `get-timing-context` (`:233-252`, used by one test).
  - `get-all-active-cues-data` (`:183-199`) returns `show-time/active-runs`.
  - `apply-global-effects-to-frame` (`:501-518`) takes the global clock from `show-time/global-clock`.
- `laser-show.backend.multi-engine`: `projector-frame` (`:134-188`) takes its cues from `show-time/active-runs`. Its duplicate `get-active-cues-data` (`:114-132`) goes (review 04 #25).
- `laser-show.subs`: `timeline-playhead` (`:191-195`) reads `[:playback :positions cell]`. `beat-position` keeps reading `[:timing :global-clock]`, which is now the mirror.
- `laser-show.events.core`: `without-timer-paths` (`:119-120`) uses `show-time/mirror-paths`. The namespace doc (`:36-39`) names the clock's mirror as the one writer outside the event flow.
- `laser-show.app`: `start!` calls `show-clock/start!` (`:152`), and `stop!` calls `show-clock/stop!` (`:228`). `start-test!` starts no clock, so tests tick it by hand as they do today.
- `laser-show.dev.reload` (`:117-137`): steps 1 and 3 go. The clock keeps running through a reload. Its loop calls `tick!` through the var, so it picks up reloaded code.
- `laser-show.state.domains`: `:playback` gets NEW `:requests {}` and `:positions {}`; the `:active-cues` doc (`:75-82`) describes intents; the `:global-clock` doc says it's a mirror.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `show-time/!runtime` NEW | runtime atom | the clock thread (`tick!` in tests) | No / no |
| `[:playback :active-cues cell]`: now an intent `{:run :timeline? :start-beat :paused? :seek :chasing?}`, with no positions | app state (playback, not saved) | grid, timeline and transport handlers; `:show-clock/cue-ended`, `:show-clock/chase` | No / no (playback isn't document state, `history.clj:48-61`) |
| `[:playback :requests]` NEW: `{:restart {:id} :align {:id} :shift {:id :offset}}` | app state (playback) | transport stop and retrigger, tap tempo, set-phase-offset-target | No / no |
| `[:timing :global-clock]`: now a mirror only | app state | the clock, every second tick | No / no (only `[:timing :bpm]` is document, `history.clj:59`) |
| `[:playback :positions]` NEW mirror: `{cell beats}` | app state | the clock, every second tick | No / no |
| `[:backend :streaming :current-frame]`, `[:backend :streaming :frame-stats]`, `[:backend :input :mtc]` | app state | the clock, every second tick (MTC at most 10 Hz, as today) | No / no |
| `[:playback :phase-offset-target]` | removed (never read) | – | – |

`reset-session` (`persistent.clj:135-150`) resets `:playback`, so intents and requests go back to `{}`. A request id of nil never applies, so a new document can't restart anything. Its runs disappear at the next tick.

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:show-clock/cue-ended`, `:show-clock/chase` | NEW | the clock thread, through `events/dispatch!` | yes |
| `:grid/trigger-cell`, `:grid/cell-clicked`, `:grid/activate-cell` | changed (write an intent) | `grid_cell.clj:100,284` (cell-clicked); triggers (`triggers.clj:50` activate, `:69` trigger) | yes |
| `:grid/move-cell`, `:grid/clear-cell` | unchanged | `grid_cell.clj:103,246,287`, `drag_drop_cell.clj:16`, `menu.clj:280` | yes |
| `:timeline/play`, `:timeline/toggle-play`, `:timeline/seek` | changed (an intent or a seek request) | `timeline_editor.clj:380,401,424,649,655`, `timeline_cue_editor.clj:188`, `triggers.clj:80` | yes |
| `:timeline/pause`, `:timeline/stop` | unchanged | `timeline_editor.clj:658`, `triggers.clj:83` | yes |
| `:transport/stop`, `:transport/retrigger` | changed (a restart request) | `title_bar.clj:159,165`, `toolbar.clj:47`, `modulator.clj:221`, `triggers.clj:76-77` | yes |
| `:timing/tap-tempo`, `:timing/set-phase-offset-target` | changed (align and shift requests) | `toolbar.clj:129`, `triggers.clj:78,165`; `link.clj:240` | yes |
| `:timing/sync-to-downbeat` / effect `:link/sync-to-downbeat` | changed: the effect reads the clock's beats; `test-sync-to-downbeat-requests-effect` (`timing_handlers_link_test.clj:139-144`) asserts the new effect value | no dispatcher in `src/` today (routed at `handlers/timing.clj:234`); effect at `events/core.clj:286-289` | handler yes; effect no |

## 7. Data examples and invariants

All at 120 BPM, where one 16.67 ms tick is 1/30 beat. `r1` and the other ids stand for UUIDs.

| App state before the tick | Runtime before | After the tick | Reports |
|---|---|---|---|
| `[0 0] {:run r1}` (just triggered); global effective beats 7.25 | no `r1` | `r1` at 0 beats, phase target 0.25 (`(mod 7.25 1.0)`, as `create-cue-timing-state`), `:last-frame-time` = now | – |
| unchanged | `r1` at 0 | `r1` at 0.0333, gliding toward 0.25 | – |
| `[1 1] {:run r1}` (moved from `[0 0]`) | `r1` at 3.5, cell `[0 0]` | `r1` at 3.533, cell `[1 1]` | – |
| `[0 0]` removed (untrigger, clear, prune, new document) | `r1` at 3.5 | no `r1` | – |
| `[0 0] {:run r2 :timeline? true :paused? true}` | `r2` at 2.0 | `r2` at 2.0 (held) | – |
| `[0 0] {:run r2 :timeline? true :seek {:id s1 :beat 9.0}}` | `r2` at 3.0, `:seek-id` nil | `r2` at 9.033 (seeked, then advanced); `:seek-id s1` | – |
| the same intent again | `r2` at 9.033, `:seek-id s1` | `r2` at 9.067: a seek applies once | – |
| timeline `r2`, length 16, no loop | `r2` at 15.99 | `r2` at 16.0, `:ended? true`, not rendered | `:show-clock/cue-ended {:cell [0 0] :run r2}`, sent again after 250 ms while the intent is still there |
| the cue was retriggered before that report arrived: `[0 0] {:run r3}` | – | – | the report for `r2` changes nothing |
| `:requests {:restart {:id x1}}` (Stop) | global at 611.2, `r1` at 3.5 | global and `r1` at 0, then advanced by one tick. `:playing? false`, so nothing renders, and `r1` keeps counting (as today) | – |
| `:requests {:align {:id a1}}` (tap) | global effective 12.35, `r1` effective 4.35, a timeline `r2` | global and `r1` targets −0.35, `r2` unchanged (the numbers of `timing-tap-tempo-phase-alignment-test`) | – |
| `:requests {}` (new document) | `:applied {:restart x1}` | nothing applies | – |
| chasing timeline at `[2 0]` (tc-start 0, 120 BPM); MTC running at 10.0 s | no chase entry | `[2 0]` at beat 20.0, `:chasing? true` | `:show-clock/chase {:cell [2 0] :active? true}` |
| a 300 ms event handler finishes and writes back its snapshot (c5) | `r1` at 5.0 | `r1` at 5.033: positions aren't in app state, so the write-back can't touch them | – |

**Invariants:**
1. Only the clock thread writes the runtime. App state's active cues hold no positions.
2. A position moves back only through a seek, a restart or a loop wrap.
3. Show time advances once per tick, 60 times a second, by the elapsed `System/nanoTime`. A late tick is never followed by a burst (`next-deadline`).
4. Removing an intent stops its cue within one tick. Moving one keeps the position.
5. Each request applies exactly once. A request with a nil id never applies.
6. The agent is the only writer of `[:playback :active-cues]`, `:playing?` and `:active-cell`. The clock writes app state only on `mirror-paths`.
7. Given the same runtime, state, MTC position and `now-ms`, `step` returns the same result.
8. An exception in a tick is reported once and the next tick runs. A code reload doesn't stop the clock.
9. For a constant tempo, advancing in 60 Hz ticks or in 30 Hz ticks gives the same beats after 1 s, within 1e-9: 2.0 at 120 BPM.

## 8. Tests

Pure, in `laser-show.animation.show-time-test` (NEW). Each calls `step` with app-state maps built by the changed handlers through `events/event-handler`, so intents have the real shape:
- `new-run-starts-at-its-start-beat-test`
- `removed-intent-drops-the-run-test`
- `moved-cue-keeps-its-position-test`
- `paused-run-holds-test`
- `seek-request-applies-once-test`
- `restart-request-resets-everything-once-test` (today's `transport-stop-test` numbers)
- `align-request-targets-the-nearest-beat-test` (today's `timing-tap-tempo-phase-alignment-test` numbers)
- `shift-request-adds-the-offset-test`
- `nil-request-ids-never-apply-test`
- `timeline-end-reports-until-the-intent-goes-test`
- `report-for-an-old-run-changes-nothing-test`
- `chase-activates-and-reports-test`
- `sixty-hz-ticks-match-thirty-hz-ticks-test` (invariant 9)
- `timeline-cue-stops-at-end-test`, `locked-tempo-ignores-global-bpm-test`, `chasing-timeline-follows-mtc-test`, `non-chasing-cues-ignore-mtc-test`: moved from `frame_service_test.clj:580-653` (they call `update-timing-accumulators!`, which goes)

The clock, in `laser-show.services.show-clock-test` (NEW):
- `ticks-advance-cues-at-60-hz-test` (`^:timing`): after 1 s of the real thread, at least 55 ticks and the cue near 2.0 beats
- `clock-survives-an-error-test` (replaces `preview-timer-survives-an-error-test`, `frame_service_test.clj:703-716`)
- `slow-event-cannot-move-a-cue-back-test` (c5, with the handler slowed as in the probe)
- `mirror-writes-once-per-two-ticks-test` (c4: 30 writes for 60 ticks, only on `mirror-paths`)
- `reports-reach-app-state-test` (a timeline end deactivates the cell through the event)
- `reload-keeps-the-clock-running-test` (c6: `dev.reload/reload-app-code!` with `discover-namespaces` redefined to return no namespaces)

Updated:
- `timing_handlers_test`: `transport-stop-test`, `transport-retrigger-test` and `timing-tap-tempo-phase-alignment-test` assert requests (their numbers move to `show-time-test`).
- `timeline_handlers_test`: `play-from-cursor-test`, `pause-freezes-position-test` and `seek-inactive-sets-cursor-only-test` assert intents and seek requests.
- `cue_timing_test/activate-deactivate-match-grid-behaviour-test`.
- `subs_test/timeline-playhead-only-depends-on-cell-test` uses the mirror path.
- `modulation_test` (the `cue-timing` helper, `:599`) uses `start-timing`.
- UI tests: `timeline_editor_test` (`timeline-play-button-starts-from-cursor`, `timeline-follows-midi-timecode`) call `show-clock/tick!` where they called `update-timing-accumulators!`. `timeline_visual_test` (`:75-77,95`) sets `[:playback :positions]`.
- Gone: `get-timing-context-test` (`frame_service_test.clj:147`).

The dev-mode lost-update check must log no WARN across the full UI suite (as in output runtime Phase 3, `03-phase3-event-reentry.md` §10).

Run: `clj -M:mac-arm:test show-time show-clock timing-handlers timeline-handlers cue-timing subs frame-service`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.timeline-editor-test`. Then both full suites, the integration suite and the probes: c4 should show 30.5, c5 0 times backwards and c6 a moving output.

## 9. Out of scope / alternatives considered

- **Rendering once per tick**: Phase 2. Until then each engine renders from the clock's positions, which move at 60 Hz. Its own 60 fps deadline drifts against the clock's, so a few frames still repeat (c1 improves but isn't fixed). A new cue also starts up to one tick later than today: `active-runs` leaves out an intent the clock hasn't started yet, and the engine then renders on its own deadline (about 8 ms later on average, 16.7 ms at worst). So Phase 2 lands right after this phase, with no show in between (audit A11).
- **The views reading the clock directly**: Phase 3 (S2). Until then the mirror keeps every subscription working.
- **Anchors instead of ticks.** Positions could be computed for any instant from a start time and a tempo, with no integration and no thread for timing. That would be exact at every engine's own send time. But every tempo change (Link, tap, a timeline's locked tempo, a loop edit) would have to re-anchor every running cue in the handler. The phase glide, loop wraps and the "skip deltas over 1 s" guard (`cue_timing.clj:103,160`) would need closed forms. The timeline plan already rejected a separate anchored clock for the same reason (`plans/2026-11-08-timeline-rework/04-phase4-transport-and-time.md:228`). Integrating at 60 Hz keeps today's maths and its tests.
- **Events sending commands to the clock** (`:show-clock/seek` and similar effects) instead of writing intents and requests. The clock would still have to reconcile its cues against app state's active cues, for undo pruning, clear-cell and new documents. With intents it uses one mechanism for everything, and the handlers stay pure state updates.
- **Starting a cue at its event's time** instead of at the next tick: at most 16.7 ms earlier. It needs the input timestamps that 02 #10 says are lost (`00-overview.md` §7).
