# Phase 5 — Frame timing: scan rate, pacing, timestamps (and the show clock, later)

**Written:** 2026-11-16   **Written for commit:** `70e049e` (`master`)
**Status:** Proposed. Needs O9 (O10 is for after this phase) and Phase 1   **Last verified against:** `70e049e`
**Supersedes / Superseded by:** none   **Related findings:** O-14 to O-17 (`00-overview.md` §2); review 04 findings 5, 7, 11, 14, QW3, MR2 step 1, MR3, LE1; 03 MR1; 02 M1 step 3

Every frame is told to the DAC to take 16.7 ms to draw, whatever its size. A 10-point frame is drawn at 600 points/s and a 3000-point frame at 180 000 points/s, on a projector whose stored scan rate is 30 000 (p10). The first is a slow, bright beam; the second drives the galvos at six times their rate.

Frames are also paced by sleeping the rest of each frame, which gives 44–48.5 fps here instead of 60 (p12, p13). And timestamps are whole milliseconds from a clock that can step backwards.

This phase fixes all three: about 1–1.5 days, plus a check on real hardware before release. As of 2026-11-16 no IDN DAC is available, so the code and its fake-DAC tests can be done, but the hardware check has to wait. The show clock (O10) is decided here and built later.

## 1. Current behaviour (file:line)

| What | Where | Today |
|---|---|---|
| Frame duration | `streaming_engine.clj:147` → `stream/frame-duration-us` (`stream.clj:543-546`) = 10⁶ / fps | 16 666 µs whatever the point count (p10) |
| Scan rate | `make-projector-config` defaults `:scan-rate` to 30 000 (`projector.clj:89,100`); documented at `domains.clj:267` | Never read; grep finds only those lines. There is no field for it in the projector settings (`projectors.clj:296-335`). |
| What the spec says | Stream §6.2: the duration is the frame's, in µs, and "the first sample SHALL not be taken into account when calculating the sample timing". §3.3 (discrete mode): frames that started must finish, newer frames replace older ones, and a frame is repeated if no new one has arrived (unless the once flag is set) | – |
| Timestamps | `current-timestamp-us` (`streaming_engine.clj:109-114`): `(* (System/currentTimeMillis) 1000)` minus the start time | Millisecond resolution: every timestamp is a multiple of 1000 (p10). Goes backwards if the wall clock does. Stream §2.1: "the timestamp has to increase monotonically". |
| Config resend | `should-resend-config?` (`:116-122`) on wall-clock ms, every 200 ms (`:40`) | If the clock steps back, config stops being resent until the clock catches up |
| Pacing | `streaming-loop` (`:190-264`): time the iteration on the wall clock, then `(Thread/sleep (long (- 16.67 elapsed)))` (`:261-264`) | Each frame's lateness is lost for good: 44–48.5 fps measured (p12). A 16 ms sleep often returns after 24 ms on this Mac; a deadline loop with `parkNanos` held 59.8 fps on the same machine (p13). |
| Unused helper | `stream/microseconds-now` (`stream.clj:538-541`) | Returns a `Ratio`; no callers |
| Show time | `update-timing-accumulators!` (`frame_service.clj:108-145`) runs only inside the 30 Hz preview tick (`:805-831`, timer `:847-866`). It is started at `app.clj:144` and stopped during code reloads (`dev/reload.clj:120,136`). | Engines take beats from that tick (`multi_engine.clj:173-174`) but elapsed ms from the wall clock (`:171`). Beat-synced motion therefore steps at 30 Hz on 60 fps output, and it freezes when the tick stops (p11; Phase 2 keeps the tick alive after errors). |

## 2. Root cause

The engine was written for a fixed-rate stream: "send a frame every 1/60 s and say it takes 1/60 s". But the DAC draws at whatever rate the duration implies, so the scan speed is set by accident by the point count. Pacing and timestamps use the wall clock, which neither keeps an average rate nor is monotonic.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `:scan-rate` on every projector (`projector.clj:89,100`; saved with the project's hardware, `persistent.clj:53-56`) | The duration's input; no migration needed |
| `write-frame-chunk-header!` (`stream.clj:277-287`), 24-bit duration | Unchanged; durations are clamped to `0xFFFFFF` |
| The u32 timestamp mask (`stream.clj:255`) | Wraps the new µs timestamps |
| Phase 4's projector fingerprint includes `:scan-rate` | A scan-rate edit restarts only that projector's engine |
| Phase 1 fake DAC: `:duration-us`, `:timestamp`, arrival times | The tests below |

## 4. Changes per namespace

- `laser-show.idn.stream`: NEW `scan-duration-us [point-count scan-rate]` = `round(10⁶ × (n − 1) / rate)` for n ≥ 2, clamped to [1, 0xFFFFFF]. The first sample isn't timed (Stream §6.2). Empty frames keep `frame-duration-us`. `microseconds-now` is removed.
- `laser-show.backend.streaming-engine`:
  - `create-engine` (`:49-103`) takes `:scan-rate`, defaulting to 30 000. `create-idn-stream-packets` (`:136-172`) uses `scan-duration-us` for non-empty frames (O9-A; with O9-B only when the projector's toggle is on).
  - `current-timestamp-us` (`:109-114`) and `should-resend-config?` (`:116-122`) use `System/nanoTime`. The engine keeps its start in ns, and timestamps are µs since start.
  - The loop (`:212-264`) paces by deadline. `next-deadline-ns` grows by the interval each frame, and the thread parks with `LockSupport/parkNanos` until it. A frame more than one interval late moves the deadline to now plus the interval: frames are skipped, never sent in a burst.
- `laser-show.backend.multi-engine` (`create-engine-for-projector`, `:218-248`): passes `(:scan-rate projector)`.
- `laser-show.events.handlers.projector` (`update-settings`, `:268-281`): a `:scan-rate` update is clamped to 1 000–100 000.
- `laser-show.views.tabs.projectors` (settings, `:296-335`): NEW "Scan rate (pts/s)" field, `#projector-scan-rate`. Like Host and Port after Phase 4, it commits on Enter or focus loss.

## 5. State placement

No new keys. `[:projectors id :scan-rate]` already exists and is saved; it becomes read.

## 6. Events and effects

| Event / effect | Exists? | Change |
|---|---|---|
| `:projectors/update-settings` with `{:scan-rate n}` | exists | NEW caller (the scan-rate field); the value is clamped |

## 7. Data examples and invariants

| Points | Scan rate | Duration today (µs) | After (µs) | Times drawn per 16.7 ms |
|---|---|---|---|---|
| 0 | any | 16 666 | 16 666 (empty frame) | – |
| 2 | 30 000 | 16 666 | 33 | ≈ 500 (repeated) |
| 10 | 30 000 | 16 666 | 300 | ≈ 55 |
| 500 | 30 000 | 16 666 | 16 633 | 1 |
| 1 000 | 30 000 | 16 666 | 33 300 | 0.5 (the DAC drops every other frame, §3.3) |
| 3 000 | 30 000 | 16 666 | 99 967 | ≈ 0.17 |
| 3 000 | 60 000 | 16 666 | 49 983 | ≈ 0.33 |

So small frames are repeated many times per frame period: brighter, steadier and faster-moving beams. Frames too big for the scan rate refresh more slowly: they flicker rather than distort.

**Invariants:**
1. On the wire, the point rate of every non-empty frame equals the projector's scan rate, rounded to whole µs.
2. Timestamps on a channel strictly increase and aren't all whole milliseconds.
3. Config is repeated at least every 250 ms, whatever the wall clock does.
4. Over 2 s, the engine sends 60 ± 1 frames per second; after a slow frame it skips ahead, and never sends two frames less than 8 ms apart.

## 8. Tests

- `laser-show.backend.idn-stream-test/scan-duration-examples-test`: the table above, including the clamping.
- `laser-show.backend.engine-wire-test` (fake DAC):
  - `duration-follows-scan-rate-test` (p10)
  - `timestamps-are-monotonic-microseconds-test` (p10)
  - `late-frame-skips-instead-of-bursting-test`: the provider sleeps 50 ms once; no two frames arrive within 8 ms afterwards.
- `^:timing` `laser-show.backend.engine-wire-test/engine-holds-60-fps-test` (p12, p13), run with `clj -M:mac-arm:test-timing`.
- `laser-show.events.projector-handlers-test/scan-rate-is-clamped-test`.
- `laser-show.ui-test.projector-settings-test/scan-rate-field-commits-on-enter-test` (NEW namespace).
- **Hardware check, before release** (manual, not automatic): on one DAC, compare a 10-point square and a 3000-point frame before and after; confirm small frames repeat and big frames slow down instead of distorting. Record the DAC model and firmware in this doc.

Run: `clj -M:mac-arm:test idn-stream engine-wire projector-handlers`, `clj -M:mac-arm:test-timing engine-holds`, then both full suites and the probes (p10, p12, p13).

## 9. Out of scope / alternatives considered

- **Warning in the UI when a frame needs more time than one frame period at the scan rate:** useful, but it needs a design for where to show it. Log it once per projector, using Phase 2's `output-errors`, for now.
- **Continuous graphic mode or once-flag frames** (Stream §3.3) for tighter timing control: not needed for this app's frame-based content.
- **The show clock** (O10 option A): its own plan.

---

## Decisions

> **DECISION O9 — How long does each frame take to draw?** *(open · needed before Phase 5 · recommended: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | From the point count and the projector's scan rate (default 30 000 pts/s, editable per projector), as the spec intends. Checked on one real DAC before release | The galvos never run faster than the projector's rating; small frames stop being slow, bright beams | Big frames refresh below 60 Hz (they visibly flicker), which shows content that has too many points |
> | B | Same, behind a per-projector "Use scan rate" toggle that starts off | Nothing changes until someone opts in | The galvo overdrive stays the default |
> | C | Keep 1/fps | No change | O-14 stays |
>
> **Your answer:** _pending_

> **DECISION O10 — Where does show time advance?** *(open · needed after Phase 5 · recommended: A)*
>
> Today beats advance only in the 30 Hz preview tick (`frame_service.clj:805-831`). Engines at 60 fps therefore see each beat position twice, and the timeline end and MTC chase also run there (`frame_service.clj:83-106,142-145`).
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | A separate plan for one show clock at the output rate. It owns timing in a runtime atom, renders each cue's zone frames once per tick, and publishes a snapshot that engines send and the preview samples; app state gets a 30 Hz mirror (review 02 M1 step 3, 03 MR1, 04 LE1) | Smooth motion, one time base, rendering once instead of once per projector; app state stops changing 30 times a second | 3–5 days, touches timing semantics; needs 03 MR1 first |
> | B | In this phase, advance timing from a dedicated 60 Hz deadline-paced thread instead of the preview tick, still in app state | Fixes the 30 Hz step now | Twice as many app-state writes; every UI subscription on timing reruns 60 times a second |
> | C | Leave it (Phase 2 already keeps the tick alive after an error) | Nothing to do | O-17 stays |
>
> **Your answer:** _pending_
