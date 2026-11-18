# Phase 5 — Frame timing: scan rate, pacing, timestamps (and the show clock, later)

**Written:** 2026-11-16   **Written for commit:** `70e049e` (`master`)
**Status:** Done 2026-11-17 (`eba8b2f`, `2a7e406`, `bfe12a5`; as built in §10), except the hardware check (§8). O9 answered 2026-11-17: A, with a default of 25 000 pts/s and a projector status that shows the fps the projector actually draws (§4, §7). O10 answered 2026-11-17: A, planned in `plans/2026-11-17-show-clock/`   **Last verified against:** `bfe12a5` (§10; §1–9 cite `f39a998`, re-checked 2026-11-17 after Phases 2–4)
**Supersedes / Superseded by:** none   **Related findings:** O-14 to O-17 (`00-overview.md` §2); review 04 findings 5, 7, 11, 14, QW3, MR2 step 1, MR3, LE1; 03 MR1; 02 M1 step 3

Every frame is told to the DAC to take 16.7 ms to draw, whatever its size. A 10-point frame is drawn at 600 points/s and a 3000-point frame at 180 000 points/s, on a projector whose stored scan rate is 30 000 (p10). The first is a slow, bright beam; the second drives the galvos at six times their rate.

Frames are also paced by sleeping the rest of each frame, which gives 44–48.5 fps here instead of 60 (p12, p13). And timestamps are whole milliseconds from a clock that can step backwards.

The projector's status line ("Streaming · 46.0 fps", Phase 4) reports how often the engine *sends*. It says nothing about how often the projector *draws* a new frame, which the scan rate limits.

This phase fixes all four: about 1–1.5 days, plus a check on real hardware before release. As of 2026-11-16 no IDN DAC is available, so the code and its fake-DAC tests can be done, but the hardware check has to wait. The show clock (O10) is decided here and built later.

## 1. Current behaviour (file:line)

| What | Where | Today |
|---|---|---|
| Frame duration | `streaming_engine.clj:153` → `stream/frame-duration-us` (`stream.clj:543-546`) = 10⁶ / fps | 16 666 µs whatever the sample count (p10) |
| Samples on the wire | `frame-rules/sanitize-frame` (`streaming_engine.clj:253`, since Phase 2) puts a dark copy in front of a lit first point; `frame->packet` truncates to one message (`stream.clj:333-345`, `max-points-per-message` `:209-218`) | A lit 10-point frame goes out as 11 samples, a lit 3000-point one as 3001 (p10) |
| Scan rate | `make-projector-config` defaults `:scan-rate` to 30 000 (`projector.clj:89,100`); documented at `domains.clj:267`; part of the Phase 4 fingerprint (`output_runtime.clj:35-39`) | Read nowhere else: a scan-rate change restarts the engine, which then ignores it. There is no field for it in the projector settings (`projectors.clj:353-418`). |
| What the spec says | Stream §6.2: the duration is the frame's, in µs, and "the first sample SHALL not be taken into account when calculating the sample timing". §3.3 (discrete mode): frames that started must finish, newer frames replace older ones, and a frame is repeated if no new one has arrived (unless the once flag is set) | – |
| Timestamps | `current-timestamp-us` (`streaming_engine.clj:115-120`): `(* (System/currentTimeMillis) 1000)` minus the start time (`:315`) | Millisecond resolution: every timestamp is a multiple of 1000 (p10). Goes backwards if the wall clock does. Stream §2.1: "the timestamp has to increase monotonically". The close pair uses the same clock (`:353`). |
| Config resend | `should-resend-config?` (`:122-128`) on wall-clock ms, every 200 ms (`:41`) | If the clock steps back, config stops being resent until the clock catches up |
| Pacing | `streaming-loop` (`:223-290`): time the iteration on the wall clock, then `(Thread/sleep (long (- 16.67 elapsed)))` (`:287-290`) | Each frame's lateness is lost for good: 44–48.5 fps measured (p12), with gaps from 2 to 56 ms. A 16 ms sleep often returns after 24 ms on this Mac; a deadline loop with `parkNanos` held 59.8 fps on the same machine (p13). |
| The fps the UI shows | `engine-status` (`output_runtime.clj:172-180`) shows the engine's `:actual-fps`: an EMA of 1000 / (ms since the last send) (`streaming_engine.clj:134-140,260-274`), shown by `output-status-text` (`projectors.clj:261-268`) | The send rate, at ms resolution. Nothing tells the operator that a frame takes longer to draw than one interval. |
| Unused helper | `stream/microseconds-now` (`stream.clj:538-541`) | Returns a `Ratio`; no callers |
| Show time | `update-timing-accumulators!` (`frame_service.clj:121`) runs only inside the 30 Hz preview tick (`update-preview-frame!` `:822-848`, timer `:864-884`). It is started at `app.clj:152` and stopped during code reloads (`dev/reload.clj:120,136`). | Engines take beats from that tick (`multi_engine.clj:156,174`) but elapsed ms from the wall clock (`:145,154,173`). Beat-synced motion therefore steps at 30 Hz on 60 fps output, and it freezes when the tick stops (p11; Phase 2 keeps the tick alive after errors). |

## 2. Root cause

The engine was written for a fixed-rate stream: "send a frame every 1/60 s and say it takes 1/60 s". But the DAC draws at whatever rate the duration implies, so the scan speed is set by accident by the point count. Pacing and timestamps use the wall clock, which neither keeps an average rate nor is monotonic. And the only rate anyone measures is the send rate, so a frame that is too big for the scan rate can't show up in the UI.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `:scan-rate` on every projector (`projector.clj:89,100`; saved with the project's hardware, `persistent.clj:53-56`) | The duration's input; no migration needed |
| `write-frame-chunk-header!` (`stream.clj:277-287`), 24-bit duration | Unchanged; durations are clamped to `0xFFFFFF` |
| `max-points-per-message` (`stream.clj:209-218`) | How many samples of a frame go on the wire, so the duration counts what is sent |
| The u32 timestamp mask (`stream.clj:255`) | Wraps the new µs timestamps |
| `timing/fps->interval-nanos` (`common/timing.clj:131-140`) | The pacing interval |
| `timing/precise-sleep-until` (`common/timing.clj:38-68`) | Not reused: it busy-waits the last 5 ms of every wait, so at 60 fps each engine would spin about 30 % of the time (*unverified*: 5 ms of every 16.7 ms frame). Its NEW sibling `park-until` parks instead (§4). |
| Phase 4's projector fingerprint includes `:scan-rate` (`output_runtime.clj:35-39`) | A scan-rate edit restarts only that projector's engine |
| Phase 4's status (`engine-status`, `output_runtime.clj:172-180`) and status line (`output-status-text`, `projectors.clj:261-268`) | Carry the fps the projector draws and say when the scan rate limits it |
| Phase 4's `commit-field` (`projectors.clj:48-74`) and the Port field's validation (`parse-port` `:76-81`, `valid-port?` `projector.clj:256-259`) | The scan-rate field commits on Enter or focus loss, validated in the view and in the handler |
| Phase 1 fake DAC (`test/laser_show/idn/fake_dac.clj`): `:duration-us`, `:timestamp`, arrival times | The tests below |

## 4. Changes per namespace

- `laser-show.common.timing` (existing): NEW `park-until [deadline-ns keep-waiting?]`. Parks with `LockSupport/parkNanos` until `System/nanoTime` reaches the deadline, and returns early once `(keep-waiting?)` is false, so a stop never waits for a deadline. The low-CPU sibling of `precise-sleep-until`.
- `laser-show.idn.stream`:
  - NEW constants `DEFAULT_SCAN_RATE` 25 000, `MIN_SCAN_RATE` 1 000 and `MAX_SCAN_RATE` 100 000 (pts/s). At the minimum, the largest message (about 9 300 samples at 16/8) takes 9.3 s, which fits the 24-bit duration (16.7 s).
  - NEW `clamp-scan-rate [v]`: a long within the range, or nil unless `v` is a finite number.
  - NEW `scan-duration-us [samples scan-rate]` = `round(10⁶ × (samples − 1) / rate)`, clamped to [1, 0xFFFFFF]. The first sample isn't timed (Stream §6.2).
  - `frame-duration-us` stays, for empty frames. `microseconds-now` is removed.
- `laser-show.backend.streaming-engine`:
  - `create-engine` (`:50-109`) takes `:scan-rate` (through `clamp-scan-rate`, default `DEFAULT_SCAN_RATE`) and keeps its output config's per-message sample limits. `:start-time-us` and `:last-config-time-ms` become `:start-ns` and `:last-config-ns`.
  - `current-timestamp-us` (`:115-120`) and `should-resend-config?` (`:122-128`) use `System/nanoTime`: timestamps are µs since start, and config goes out when none has been sent yet or the last one is 200 ms old.
  - `create-idn-stream-packets` (`:142-178`) and `send-frame!` (`:198-221`): a non-empty frame's duration is `scan-duration-us` of the samples that go on the wire (all of them when fragmentation splits the frame, otherwise at most one message's worth). An empty frame keeps `frame-duration-us`. `send-frame!` returns the packet count and the duration.
  - The loop (`:223-290`) paces by deadline. The deadline grows by one interval per frame, and the thread parks until it (`park-until`). A frame that ends more than one interval past the next deadline moves that deadline to now plus the interval: frames are skipped, never sent in a burst. No frame, the error path's empty frame included, goes out less than half an interval after the one before.
  - Stats: NEW private `update-stats`, on `System/nanoTime`, smoothed over about 10 frames (an EMA, as today):
    - `:send-fps`: frames sent per second;
    - `:draw-fps`: new frames the projector draws per second, 10⁶ / max(send interval, frame duration);
    - `:frame-duration-us` of the last frame;
    - `:scan-limited?`: the smoothed frame time is more than 2 % above the smoothed send interval.

    `:last-frame-time`, `:actual-fps` and `calculate-actual-fps` (`:134-140`) go.
- `laser-show.backend.multi-engine` (`create-engine-for-projector`, `:217-248`): passes `(:scan-rate projector)`.
- `laser-show.backend.output-runtime` (`engine-status`, `:172-180`): `:fps` is the engine's `:draw-fps`, rounded to 0.1, plus `:scan-limited? true` when it is.
- `laser-show.events.handlers.projector`: `make-projector-config` (`:78-102`) defaults `:scan-rate` to `stream/DEFAULT_SCAN_RATE` (was 30 000). `update-settings` (`:261-275`) clamps a numeric `:scan-rate` with `clamp-scan-rate` and drops anything else, as it does for the port.
- `laser-show.views.tabs.projectors`:
  - NEW `parse-scan-rate [text]`: pts/s from "25000", "25 000", "25,000", "25k" or "27.5k"; nil otherwise.
  - `projector-basic-settings` (`:353-418`): a NEW row "Scan rate: [field] pts/s". The field, `#projector-scan-rate-field`, is a `commit-field` showing the projector's scan rate (the default if it has none). Like Host and Port, it commits on Enter or focus loss. A value that doesn't parse, or that clamps to the stored rate, puts the stored rate back; anything else dispatches `:projectors/update-settings`.
  - `output-status-text` (`:261-268`): "Streaming · 25.0 fps (limited by scan rate)" when `:scan-limited?`.
- `laser-show.state.domains` (`:267`): the docstring gives the default and the range.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:projectors id :scan-rate]` (existing; becomes read) | document (the `:hardware` file) | `make-projector-config` (25 000), `:projectors/update-settings` (clamped) | Yes / yes (`:projectors` is a document root, `history.clj:48-50`) |
| `[:backend :streaming :status :projectors id]` `:fps` (now the draw rate) and NEW `:scan-limited?` | app state, the output status | the status thread through `:output/status` (≤ 4 Hz, Phase 4) | No / no |
| `:start-ns`, `:last-config-ns`, `:stats` of each engine | the engine's atoms, in the output runtime's registry | that engine's streaming thread | – |

Projectors saved before this phase keep the scan rate stored with them: 30 000 for every one, since that was the old default and there was no field. The field shows it, and an edit applies.

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Change |
|---|---|---|---|
| `:projectors/update-settings` with `{:scan-rate n}` | exists (`projector.clj:261`) | NEW caller: the scan-rate field | The value is clamped; a non-number is dropped |
| `:output/status` | exists (`connection.clj:28`) | the status thread (`output_runtime.clj:204-214`) | The status map carries the draw rate and `:scan-limited?` |

No new effect keys.

## 7. Data examples and invariants

Frame durations at the default 25 000 pts/s, counting samples on the wire (a lit frame of n points goes out as n + 1 samples). "Shown" is the status line with frames sent every 16 667 µs.

| Samples | Scan rate | Duration today (µs) | After (µs) | Times drawn per 16.7 ms | Shown |
|---|---|---|---|---|---|
| 0 (idle) | any | 16 666 | 16 666 (empty frame) | – | Streaming · 60.0 fps |
| 2 | 25 000 | 16 666 | 40 | ≈ 417 (repeated) | Streaming · 60.0 fps |
| 11 | 25 000 | 16 666 | 400 | ≈ 42 | Streaming · 60.0 fps |
| 418 | 25 000 | 16 666 | 16 680 | 1 | Streaming · 60.0 fps |
| 501 | 25 000 | 16 666 | 20 000 | 0.83 | Streaming · 50.0 fps (limited by scan rate) |
| 1 001 | 25 000 | 16 666 | 40 000 | 0.42 (the DAC drops frames, §3.3) | Streaming · 25.0 fps (limited by scan rate) |
| 3 001 | 25 000 | 16 666 | 120 000 | 0.14 | Streaming · 8.3 fps (limited by scan rate) |
| 3 001 | 50 000 | 16 666 | 60 000 | 0.28 | Streaming · 16.7 fps (limited by scan rate) |
| 1 | any | – | 1 (the clamp; `sanitize-frame` never sends 1) | – | – |
| 20 000 | 1 000 | – | 16 777 215 (the 24-bit clamp) | – | – |

So small frames are repeated many times per frame period: brighter, steadier and faster-moving beams. Frames too big for the scan rate refresh more slowly: they flicker rather than distort, and the status line says so. The shipped presets default to 8–128 points (at most 256, `presets.clj:48-116`), so one preset stays below 418 samples; several presets or cues in one frame can go over.

`clamp-scan-rate`: `25000` → 25000; `500` → 1000; `200000` → 100000; `27500.4` → 27500; `nil`, `"25000"`, `##NaN` → nil (dropped).

`parse-scan-rate`: `"25000"`, `"25 000"`, `"25,000"`, `"25k"`, `"25K"` → 25000; `"27.5k"` → 27500; `""`, `"abc"`, `"-5"`, `"2.5"` → nil.

Pacing, with an interval of 16.67 ms (times in ms from the deadline the frame was due at):

| Frame ends at | Next deadline | Why |
|---|---|---|
| 2 | 16.67 | on time |
| 25 | 16.67, so the next frame starts at once, but goes out no sooner than 25 + 8.33 | less than one interval late |
| 50 | 50 + 16.67 = 66.67 | more than one interval late: skip |

Stats: send interval 16 667 µs and duration 40 000 µs → `:draw-fps` 25.0, `:send-fps` 60.0, `:scan-limited?` true. Interval 16 667 µs and duration 400 µs → 60.0, 60.0, false. Interval 20 833 µs (an engine that only manages 48 fps) and duration 400 µs → 48.0, 48.0, false.

**Invariants:**
1. On the wire, the point rate of every non-empty frame equals the projector's scan rate: duration = round(10⁶ × (samples − 1) / scan rate), clamped to [1, 0xFFFFFF].
2. Timestamps on a channel strictly increase and aren't all whole milliseconds.
3. Config is repeated at least every 250 ms, whatever the wall clock does.
4. Over 2 s, the engine sends 60 ± 1 frames per second; after a slow frame it skips ahead, and it never sends two frames less than half an interval (8.3 ms) apart.
5. The fps shown for a projector is the rate at which it draws new frames: the lower of the send rate and 10⁶ / frame duration, smoothed over about 10 frames. It says "limited by scan rate" when the frame duration sets it.
6. New projectors get 25 000 pts/s. A scan-rate edit is clamped to 1 000–100 000 and restarts only that projector's engine.

## 8. Tests

- `laser-show.backend.idn-stream-test`: `scan-duration-examples-test` (the duration column above, with the clamping) and `clamp-scan-rate-test`.
- `laser-show.common.timing-test`: `park-until-never-returns-early-test`, `park-until-returns-when-told-to-stop-test`.
- `laser-show.backend.streaming-engine-test` (pure, through the private vars): `next-deadline-examples-test` (the pacing table), `stats-report-the-fps-the-projector-draws-test` (the stats examples).
- `laser-show.backend.engine-wire-test` (fake DAC):
  - `duration-follows-scan-rate-test` (p10): 11 and 1 001 samples at the 25 000 default, half the duration at 50 000, empty frames at 16 666.
  - `timestamps-are-monotonic-microseconds-test` (p10).
  - `late-frame-skips-instead-of-bursting-test`: the provider sleeps 50 ms once. Afterwards no two frames are less than 8 ms apart (engine clock), and the frame after the slow one comes at least an interval later.
  - `draw-fps-follows-the-scan-rate-test`: 1 001-sample frames at 25 000 report 25 ± 1 fps and `:scan-limited?`; 11-sample frames report the send rate and aren't scan-limited.
- `^:timing` `laser-show.backend.engine-wire-test/engine-holds-60-fps-test` (p12, p13), run with `clj -M:mac-arm:test-timing`.
- `laser-show.backend.multi-engine-test/engine-uses-the-projector-scan-rate-test`.
- `laser-show.backend.output-lifecycle-test/status-shows-the-fps-the-projector-draws-test`: with `projector-frame` redefined to 1 000 lit points, the published status says 25 ± 1 fps and `:scan-limited? true`.
- `laser-show.events.projector-handlers-test`: `scan-rate-is-clamped-test`, `new-projectors-default-to-25k-test`.
- `laser-show.views.tabs.projectors-test` (NEW, pure): `output-status-text-test`, `parse-scan-rate-test`.
- UI, `laser-show.ui-test.streaming-status-test` (next to `host-field-commits-on-enter-test`): `scan-rate-field-commits-on-enter-test`, `projector-status-says-when-the-scan-rate-limits-the-fps-test`.
- **Hardware check, before release** (manual, not automatic): on one DAC, compare a 10-point square and a 3000-point frame before and after; confirm small frames repeat and big frames slow down instead of distorting, and that the status line's fps matches what is drawn. Record the DAC model and firmware in this doc. Added by the 2026-11-17 audit (`plans/2026-11-17-show-clock/00-overview.md` §9):
  - Every 0.5 s, send one channel message as "with acknowledge request" (0x41) and log the reply (0x47, Hello §6.3). It carries the DAC's own latency estimate, its link quality and its error flags (SeqErr, BPErr, LAErr, OVErr, MCLErr), so the DAC itself says whether it accepts the timestamps, sequence numbers and frame sizes.
  - Send 1,000-point frames at 25 000 pts/s (drawn at 25 fps, sent at 60) and change them: the change must show within about one frame (40 ms). That means the DAC drops older frames rather than queueing them (Stream §3.3). The status line's fps and the show clock's latency both assume it.
  - Quit the app, and separately kill the JVM, while streaming: the projector goes dark within 1 s (Hello §7.2).
  - Look at the jumps between two shapes in one frame. Each jump gets 2 blank points today (`frame_service.clj:336-350`).
- **Hidden-window check, before a show** (manual, no DAC needed; added 2026-11-17, to try later): stream for a minute with the app hidden and watch each projector's fps in its status line. On Windows, minimize it, then cover it with a full-screen app; on macOS, put a full-screen app in front. All the pacing numbers above are from macOS with the app in front. On Windows, `LockSupport/parkNanos` rounds to whole milliseconds and depends on the process's timer resolution. JavaFX raises that to 1 ms, but Windows 11 may not honour it for a hidden window. On macOS, App Nap throttles the timers of background apps. If the fps drops, use `SetProcessInformation` with `PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION` on Windows (through FFM; the app already enables native access), and `NSAppSleepDisabled` in the macOS bundle's `Info.plist`. The show clock's `STALE_MS` is settled after this check.

Run: `clj -M:mac-arm:test idn-stream common.timing engine-wire streaming-engine multi-engine output-lifecycle projector-handlers views.tabs.projectors`, `clj -M:mac-arm:test-timing engine-holds`, `clj -M:test-ui:mac-arm:test-ui-headless streaming-status`, then both full suites and the probes (p10, p12, p13).

## 9. Out of scope / alternatives considered

- **Moving existing projectors to 25 000:** a project has no format version, so after this phase a stored 30 000 can't be told apart from one the user typed. The field shows the stored rate instead.
- **A points-per-frame budget next to the field** ("416 points per frame at 60 fps"): the status line already says when a frame is too big for the scan rate.
- **Continuous graphic mode or once-flag frames** (Stream §3.3) for tighter timing control: not needed for this app's frame-based content.
- **The show clock** (O10 option A): its own plan, `plans/2026-11-17-show-clock/`.

---

## Decisions

> **DECISION O9 — How long does each frame take to draw?** *(answered 2026-11-17: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (chosen)** | From the point count and the projector's scan rate (editable per projector), as the spec intends. Checked on one real DAC before release | The galvos never run faster than the projector's rating; small frames stop being slow, bright beams | Big frames refresh below 60 Hz (they visibly flicker), which shows content that has too many points |
> | B | Same, behind a per-projector "Use scan rate" toggle that starts off | Nothing changes until someone opts in | The galvo overdrive stays the default |
> | C | Keep 1/fps | No change | O-14 stays |
>
> **Your answer:** A: "we should show the fps we are actually getting and base it on the scan rate we have set. (By default we should do 25 kpps)". So the default is 25 000 pts/s (not 30 000), and the projector's status line shows the fps it draws, with "limited by scan rate" when its frames are too big (§4, §7).

> **DECISION O10 — Where does show time advance?** *(answered 2026-11-17: A)*
>
> Today beats advance only in the 30 Hz preview tick (`frame_service.clj:822-848`). Engines at 60 fps therefore see each beat position twice, and the timeline end and MTC chase also run there (`frame_service.clj:84-107,121`).
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (chosen)** | A separate plan for one show clock at the output rate. It owns timing in a runtime atom, renders each cue's zone frames once per tick, and publishes a snapshot that engines send and the preview samples; app state gets a 30 Hz mirror (review 02 M1 step 3, 03 MR1, 04 LE1) | Smooth motion, one time base, rendering once instead of once per projector; app state stops changing 30 times a second | 3–5 days, touches timing semantics; needs 03 MR1 first (done 2026-11-17 except its step 3, which this plan would take on: `plans/2026-11-17-modulator-time-base.md` §9) |
> | B | In this phase, advance timing from a dedicated 60 Hz deadline-paced thread instead of the preview tick, still in app state | Fixes the 30 Hz step now | Twice as many app-state writes; every UI subscription on timing reruns 60 times a second |
> | C | Leave it (Phase 2 already keeps the tick alive after an error) | Nothing to do | O-17 stays |
>
> **Your answer:** A: "Go write the plan" (2026-11-17), after the recommendation to do the show clock next. The plan is `plans/2026-11-17-show-clock/`: four phases, about 4–5.5 days. Its own choices are decisions S1–S3 there: who renders and when each engine sends, how the screen gets the moving parts, and what the lasers show if the clock stops. Each projector keeps its own streaming thread (O6).

---

## 10. As built (2026-11-17)

Committed as `eba8b2f` (pacing, timestamps, config repeat and send rate: `common/timing.clj`, `streaming_engine.clj`, `output_runtime.clj`, `stream.clj`), `2a7e406` (the scan rate: `stream.clj`, `streaming_engine.clj`, `multi_engine.clj`, `output_runtime.clj`, `handlers/projector.clj`, `domains.clj`) and `bfe12a5` (the projectors tab), on top of `39c5461` (this doc's re-check and the O9 answer). §1–9 describe the code before this phase; their citations are against `f39a998`.

**Where it is now** (at `bfe12a5`)
- `common/timing.clj`: NEW `max-park-nanos` `:87`, `park-until` `:92`.
- `idn/stream.clj`: NEW `DEFAULT_SCAN_RATE` `:547`, `MIN_SCAN_RATE` `:551`, `MAX_SCAN_RATE` `:557`, `clamp-scan-rate` `:561`, `scan-duration-us` `:570`; `microseconds-now` removed.
- `backend/streaming_engine.clj`: `initial-stats` `:51`; `create-engine` `:64` (`:scan-rate`, `:max-samples`, `:start-ns`, `:last-config-ns`); `current-timestamp-us` `:135`; `should-resend-config?` `:141`; NEW `wire-duration-us` `:154`; `create-idn-stream-packets` `:169` (now given the config flag and the duration); `send-frame!` `:220` (decides the config, returns `[packet-count duration-us]`); NEW `next-deadline` `:251`; NEW `update-stats` `:261`; `streaming-loop` `:297`; `start!` `:375`; `get-stats` `:471`. `calculate-actual-fps`, `:last-frame-time` and `:actual-fps` are gone.
- `backend/multi_engine.clj`: `create-engine-for-projector` `:217` passes `:scan-rate` (`:249`).
- `backend/output_runtime.clj`: `engine-status` `:172`, `status` `:183`.
- `events/handlers/projector.clj`: `make-projector-config` `:79` (25 000), NEW `clamp-scan-rate-update` `:262`, `update-settings` `:270`.
- `views/tabs/projectors.clj`: NEW `parse-scan-rate` `:84`, `output-status-text` `:273`, `projector-basic-settings` `:369` (the Scan rate row, `#projector-scan-rate-field` `:431`).

**Differences from the plan above**

| Plan | As built | Why |
|---|---|---|
| `park-until` parks until the deadline (§4) | It parks at most 5 ms at a time (`max-park-nanos`) and checks `keep-waiting?` between parks | A stop is noticed within 5 ms, without `stop-all!` having to `unpark` each thread |
| The loop's IDN time (not in §4) | `profiler/record-idn-streaming!` gets producing plus sending, not the half-interval wait between them | The wait isn't work, and only happens after a frame overran |
| When the config counts as sent (not in §4) | As before, when its packets are built; now decided in `send-frame!` rather than `create-idn-stream-packets` | The duration needs the decision first: one message holds a few samples fewer with config |
| `:scan-limited?` in the status (§5) | Present only when true; always a boolean in the engine's stats | Keeps the status map small |

**Tests**
- Pure: `idn-stream-test/scan-duration-examples-test` (the §7 durations and both clamps), `clamp-scan-rate-test`; `common.timing-test/park-until-never-returns-early-test`, `park-until-returns-when-told-to-stop-test`; `streaming-engine-test/next-deadline-examples-test`, `stats-report-the-fps-the-projector-draws-test`, and NEW beyond §8 `stats-measure-the-send-rate-test`; `projector-handlers-test/scan-rate-is-clamped-test` (the other updates still apply), `new-projectors-default-to-25k-test`; `multi-engine-test/engine-uses-the-projector-scan-rate-test`; NEW `views.tabs.projectors-test/output-status-text-test`, `parse-scan-rate-test`.
- Fake DAC: `engine-wire-test/timestamps-are-monotonic-microseconds-test`, `late-frame-skips-instead-of-bursting-test` (it checks gaps between timestamps on the engine clock, so receiver jitter can't fail it), `duration-follows-scan-rate-test`, `draw-fps-follows-the-scan-rate-test`, `^:timing engine-holds-60-fps-test`; `output-lifecycle-test/status-shows-the-fps-the-projector-draws-test` (`projector-frame` redefined to 1 000 lit points).
- UI: `streaming-status-test/scan-rate-field-commits-on-enter-test` (typing applies nothing; "30k" and Enter give 30 000; "fast" is put back; "500" becomes 1 000; "25,000" applies on focus loss), `projector-status-says-when-the-scan-rate-limits-the-fps-test` ("Streaming · 25.0 fps (limited by scan rate)").
- Red first: at `39c5461` the two new wire tests failed 3 assertions (every timestamp a whole millisecond; 1 ms after the slow frame; gaps of 17–24 ms). At `eba8b2f` the scan-rate tests had 18 failures and 3 errors in 9 tests. At `2a7e406` both UI tests timed out (no field, no label).
- Looked at: a screenshot of the projectors tab while streaming, with one projector sending 1 000-point frames and one idle: "Streaming · 25.0 fps (limited by scan rate)" and "Streaming · 59.2 fps", and the Scan rate row under Host and Port.

**Probes** (`probes/output_probes_test.clj`; p10 now leaves the first sample out of the rate, as Stream §6.2 does. Before = `39c5461`, after = `bfe12a5`)

| Probe | Before | After |
|---|---|---|
| p10 | 11 and 3 001 samples both 16 666 µs: 600 and 180 007 points/s; every timestamp a whole millisecond | 400 and 120 000 µs: **25 000 points/s for both**, the default; timestamps in µs (25 different values `mod 1000`) |
| p12 | 92 frames in 2 s = **46.0 fps**; gaps 2 / 22 / 25 / 56 ms (min / median / p95 / max) | 121 frames = **60.5 fps**; gaps 15 / 17 / 18 / 19 ms |
| p13 | sleep-the-remainder 44.8 fps, deadline 59.8 fps | 45.7 and 60.0 fps. p13 compares the two loops on their own; the engine now uses the second |
| p1–p9, p11 | as in 04 §10 | the same findings |

**Verification, 2026-11-17, macOS arm64, JDK 25.0.5, headless, load average 4–9**

| Check | Result |
|---|---|
| `clj -M:mac-arm:test common.timing engine-wire streaming-engine` (with `eba8b2f`'s changes) | 36 tests, 156 assertions, 0 failures |
| `clj -M:mac-arm:test output-lifecycle multi-engine output-sync` (same) | 38 tests, 209 assertions, 0 failures |
| `clj -M:mac-arm:test idn-stream common.timing engine-wire streaming-engine multi-engine output-lifecycle output-sync projector-handlers` (with `2a7e406`'s changes) | 110 tests, 594 assertions, 0 failures |
| `clj -M:mac-arm:test-timing engine-holds output-lifecycle` (with each of the two) | 3 tests, 8 assertions, 0 failures |
| `clj -M:mac-arm:test views.tabs.projectors` | 2 tests, 20 assertions, 0 failures |
| `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.streaming-status-test` | 6 tests, 24 assertions, 0 failures |
| `clj -M:mac-arm:test` (full unit suite) | 813 tests, 29,257 assertions, 0 failures (796 / 31,328 at Phase 4) |
| `clj -M:test-ui:mac-arm:test-ui-headless` (full UI suite) | 55 tests, 209 assertions, 0 failures (53 / 202 at Phase 4) |
| `clj -M:kondo --lint` on every touched file | 0 errors; 1 warning, the unused `laser-show.common.util` in `handlers/projector.clj`, which predates this phase |

The full suite's assertion count is lower than at Phase 4, but not because of this phase. Counted per namespace at `f39a998` and at `bfe12a5`, the eight namespaces this phase touched gained 128 assertions, and the only other change is `timeline.model-test` (6,178 → 3,967). Its `apply-op-output-is-normalized-test` makes one assertion per clip after each of 500 random edits, and its `shuffle` wasn't seeded, so the count changed from run to run (6,146, 6,146 and 6,188 in three runs of that namespace). Fixed later the same day in `ecc26a1`: it shuffles with its seeded `Random`, and makes 6,906 assertions every run.

Not testable here: a real DAC (none available). The hardware check in §8 is still to do before release.
