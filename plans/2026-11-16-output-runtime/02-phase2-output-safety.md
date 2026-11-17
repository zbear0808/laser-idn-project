# Phase 2 — Nothing unsafe reaches the wire

**Written:** 2026-11-16   **Written for commit:** `70e049e` (`master`)
**Status:** Proposed; decisions answered 2026-11-16 (O2: skip only the failing effect, O3: filter bad points, O4: no switch). Needs Phase 1   **Last verified against:** `70e049e`
**Supersedes / Superseded by:** none   **Related findings:** O-2 to O-7 (`00-overview.md` §2); review 04 findings 8, 16, QW6; 03 findings 6, 7, 9, 10, MR4

When something goes wrong while a frame is produced, today's code makes it worse than it needs to be:
- a NaN coordinate becomes a full-brightness point at the centre;
- one failing effect takes its whole chain down with it (every effect of that clip, all global effects, or all of a projector's calibration);
- an `Error` silently ends the thread that was sending or timing.

This phase:
- removes bad points right before the encoder;
- skips only the effect that failed and logs it once;
- keeps every long-lived loop on the output path alive.

There is no new UI. About 1.5 days.

## 1. Current behaviour (file:line)

| What | Where | Today |
|---|---|---|
| NaN and ±Inf coordinates | `common/util.clj:13-27` (`clamp` tests with `<` and `>`, both false for NaN, so NaN passes); `idn/output_config.clj:99-111` | NaN becomes 0 on the wire and keeps its colour: a lit point at the centre. ±Inf clamps to the edge. A NaN colour becomes 0 (p1). |
| `nil` coordinates | `idn/stream.clj:300-304` (`(point t/X)` into `double`) | `NullPointerException` in the encoder (p1). The engine catches it and sends nothing (p2). |
| "Is this point dark?" | `animation/types.clj:94-101` | `blanked?` is false for NaN colours. And `rgb-curves` turns a NaN colour into LUT entry 0 (`calibration.clj:31-33`: `Math/max` and `Math/min` pass NaN, `long` of NaN is 0), which is a lit colour whenever the curve's low end is raised. |
| First sample and minimum size (Stream §6.2: the first sample "SHALL be invisible"; a non-empty frame "SHALL contain at least 2 samples") | nowhere | Not enforced. Most generators start dark (`generators.clj:60,87,118`), but `wave-frame` starts lit (`:279-298`), and with 1 point it divides by zero (`:291,294`), giving a NaN point. |
| Effect errors | `animation/effects.clj:252-258`: a transducer that fails to build becomes `(map identity)`, which skips that effect only; `services/frame_service.clj:315-319`: an effect that throws per point gives back the item's input, so **every** effect of that item is skipped; `:497-501`: the same for the global chain; `backend/multi_engine.clj:111-116`: the same for the projector chain, so all calibration is skipped (p8) | One failing effect takes its whole chain down |
| Frame provider errors | `multi_engine.clj:207-212` | Returns nil, so the engine sends an empty frame (`streaming_engine.clj:219`) |
| Encoder and send errors | `streaming_engine.clj:256-259` | That frame isn't sent (p2). In discrete mode the DAC keeps drawing the last frame it got (Stream §3.3) until its link timeout, 1 s by default (Hello §7.2). |
| Errors in long-lived loops | engine loop catches `Exception` only (`streaming_engine.clj:256`) and sleeps outside the `try` (`:261-264`); the preview `TimerTask` catches `Exception` only (`frame_service.clj:859-862`), and a `java.util.Timer` cancels itself when a task throws | An `Error` (an `AssertionError` from a `:pre`, a `StackOverflowError`) ends the engine thread (p3) or the preview timer (p11). Nobody is told; `running?` stays true. |
| Calibration and blanking | `effects/calibration.clj:18-37` | `rgb-curves` maps every point through its curves, blanked ones included, so raising a curve's low end lights every travel move (p9). The colour effects skip blanked points (`color.clj:20,51,126`). |
| Logging | `streaming_engine.clj:259` (full stack via `u/exception->map`), `multi_engine.clj:115,208,211`, `animation/effects.clj:255-256`, `frame_service.clj:318,500` | ERROR on every failing frame, on the real-time threads. The only rate-limited log is the truncation warning (`stream.clj:331-345`). |

## 2. Root cause

There's no single last stage before the encoder that owns "this frame is valid". Error handling sits at the chain level: a chain's effects are composed into one transducer (`compose-effect-transducers`, `animation/effects.clj:260-280`) and run in one pass (`apply-effect-chain`, `:287-318`). When one effect throws partway through, the caller can only fall back to the frame from before the whole chain.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `make-effect-transducer` (`animation/effects.clj:195-258`), `compose-effect-transducers` (`:260-280`) | The fast path stays the composed single pass. Only when it throws does the chain run effect by effect (§4). |
| `t/blanked?`, `t/blanked-point` (`types.clj:86-101`) | `blanked?` gets a NaN guard; the sanitiser uses both |
| The throttled truncation warning (`stream.clj:331-345`, a `compare-and-set!` on a timestamp) | The pattern for the throttled logger in `output-errors` |
| `concatenate-frames` blanking jumps (`frame_service.clj:338-362`) | Unchanged. Zone frames are already joined dark; the sanitiser handles the frame's very first sample. |
| `create-idn-stream-packets` (`streaming_engine.clj:136-172`) | The sanitiser runs right before it, in the engine, so it covers every provider |
| Fake DAC (Phase 1) | The wire tests below |

## 4. Changes per namespace

- `laser-show.idn.frame-rules` (NEW, pure): `sanitize-frame [frame] → frame`.
  1. It drops every point with a `nil` or non-finite value in x, y, r, g or b (O3).
  2. If the first remaining point is lit, it prepends a dark copy of it.
  3. If exactly one point remains, it duplicates it.

  An empty frame stays empty, and range clamping stays in the encoder, as today. Examples in §7.
- `laser-show.backend.output-errors` (NEW, runtime atom `{key {:message :count :first-ms :last-ms :logged-ms}}`):
  - `report! [key ^Throwable e]` logs WARN with the stack for a new key or message, then at most every 10 s with a count;
  - `ok! [key]` removes the key and logs "recovered after N" when it was present;
  - `snapshot []` returns a copy, for the status in Phase 4.
  
  Keys: `[:engine pid]`, `[:provider pid]`, `[:projector-effects pid]`, `[:item-effects item-id]`, `[:global-effects]`, `[:effect instance-id]`, `[:preview]`.
- `laser-show.animation.effects` (O2):
  - `apply-effect-chain` (`:287-318`) tries the composed pass first, as today. If that throws, it runs the chain's effects one at a time on the frame and skips each one that throws, reporting `[:effect (:id effect)]`. Applying transducers one after another gives the same result as composing them, so frames only change when an effect fails. The cost is about one extra pass per effect, and only on frames where something fails.
  - `make-effect-transducer` (`:252-258`) keeps `(map identity)` when an effect fails to build, but reports it through `output-errors` instead of logging every frame.
- `laser-show.backend.streaming-engine` (`streaming_engine.clj:212-264`):
  - Each iteration runs `provider → sanitize-frame → packets → send` inside `try` … `catch Throwable`.
  - On an error it reports `[:engine id]` and tries to send an empty frame. If that send fails too, the error is reported and the loop carries on.
  - Pacing runs after the `try` in all cases, and success calls `ok!`.
- `laser-show.backend.multi-engine`:
  - `apply-projector-effects` (`:102-117`) and the provider's catches (`:207-212`) catch `Throwable` and report through `output-errors`.
  - Their fallbacks are unchanged: the frame without projector effects, and nil (an empty frame). With the per-effect fallback in `apply-effect-chain`, they now fire only for errors outside an effect.
- `laser-show.services.frame-service`: the same for `apply-item-effects` (`:306-320`) and `apply-global-effects-to-frame` (`:487-502`): `Throwable`, reported, today's fallback. The preview task (`:855-863`) catches `Throwable` and reports `[:preview]`, so the timer keeps running.
- `laser-show.animation.types` (`blanked?`, `:94-101`): a NaN channel counts as dark. With the next change, a NaN colour stays NaN through calibration and is then dropped by the sanitiser.
- `laser-show.animation.effects.calibration` (`rgb-curves-xf`, `:18-37`): blanked points stay blanked.
- `laser-show.animation.generators` (`wave-frame`, `:279-298`): starts with a blanked point; `num-points` is raised to at least 2.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `output-errors` registry NEW | runtime atom (`backend/output_errors.clj`) | engine threads, the preview timer, frame code | No / no |

No app-state keys and no UI.

## 6. Events and effects

None. Everything here runs on the engine threads, the preview timer and in pure frame code.

## 7. Data examples and invariants

`sanitize-frame` (`d` = a dark point `[x y 0 0 0]`):

| Input | Output |
|---|---|
| `[]` | `[]` |
| `[[0.5 0.5 1 1 1]]` | `[[0.5 0.5 0 0 0] [0.5 0.5 1 1 1]]` |
| `[[0 0 0 0 0]]` | `[[0 0 0 0 0] [0 0 0 0 0]]` |
| `[d(0,0) [NaN NaN 1 1 1] [0.5 0 1 1 1]]` | `[d(0,0) [0.5 0 1 1 1]]` |
| `[d(0,0) [0.2 nil 1 1 1]]` | `[d(0,0) d(0,0)]` (one point left, doubled) |
| `[d(0,0) [0.2 0.2 NaN 1 1]]` | `[d(0,0) d(0,0)]` |
| `[[NaN NaN 1 1 1]]` | `[]` |
| `[[+Inf 0 1 1 1] [0.5 0 1 1 1]]` | `[[0.5 0 0 0 0] [0.5 0 1 1 1]]` (the first remaining point is lit, so a dark copy goes in front) |
| `[d(-0.5,0) [2.0 0 1 1 1]]` | unchanged (the encoder clamps 2.0 to 32767, as today) |

What an effect error does (O2):

| Failure | Today | After |
|---|---|---|
| An effect fails to build | that effect is skipped; ERROR every frame | that effect is skipped; logged once |
| An effect throws per point in a chain of `[a b c]` (b fails) | none of `a b c` is applied | `a` and `c` are applied, `b` is skipped; logged once |
| A projector chain `[corner-pin rgb-curves]` with a failing corner pin | uncalibrated frame (p8) | `rgb-curves` is applied, the corner pin is skipped; logged once |
| The provider throws | empty frame | empty frame (unchanged) |
| The encoder or send throws | nothing sent (p2) | an empty frame is attempted. After the sanitiser, encoding shouldn't fail at all. |
| An `Error` in the engine loop | thread ends (p3) | reported; the loop continues |
| An `Error` in the preview tick | timer cancelled (p11) | reported; the next tick runs |

Logging for one key:
- the first failure logs a WARN with the stack;
- while it keeps failing, a WARN every 10 s says "still failing: 600 times in the last 10 s";
- when it works again, one INFO says "recovered after 1 834 failures".

**Invariants:**
1. No NaN, ±Inf or `nil` value reaches the encoder.
2. Every non-empty frame on the wire starts with a dark sample and has at least 2 samples.
3. `sanitize-frame` never lights a dark point, never moves a point, and applying it twice changes nothing.
4. A failing effect only takes itself out. The other effects of its chain still apply, and when nothing fails the output is exactly what it is today.
5. The engine thread and the preview timer survive any `Throwable` from producing a frame.
6. Each error key logs at most once per 10 s.

## 8. Tests

Each probe first becomes a failing test.

- `laser-show.idn.frame-rules-test/sanitize-examples-test` (the table above) and `/sanitize-invariants-test`: generated frames with NaN, ±Inf and `nil`; idempotence; never lights or moves a point.
- `laser-show.backend.engine-wire-test` (fake DAC):
  - `nan-point-is-filtered-test` (p1: the bad point is gone, the rest arrives)
  - `nil-point-is-filtered-test` (p2: frames keep arriving)
  - `error-in-provider-keeps-the-engine-alive-test` (p3)
  - `first-sample-is-dark-test`
  - `send-error-keeps-the-engine-alive-test` (with `with-redefs` making one send throw)
- `laser-show.animation.effects-test`:
  - `failing-effect-skips-only-itself-test`
  - `effect-that-fails-to-build-is-skipped-test`
  - `chain-without-errors-is-unchanged-test` (composed and fallback paths give equal frames)
- `laser-show.backend.multi-engine-test/failing-calibration-effect-skips-only-itself-test` (p8: the other projector effects still apply).
- `laser-show.animation.effects.calibration-test/rgb-curves-keep-blanked-points-dark-test` (p9) (NEW ns if none exists).
- `laser-show.animation.types-test/nan-colour-is-not-lit-test`.
- `laser-show.animation.generators-test/wave-frame-starts-dark-test`, `/one-point-wave-is-finite-test`.
- `laser-show.services.frame-service-test/preview-timer-survives-an-error-test` (p11).
- `laser-show.backend.output-errors-test/logs-once-then-throttled-test`, `/ok-logs-recovery-once-test`.

Run: `clj -M:mac-arm:test frame-rules engine-wire effects multi-engine calibration types generators frame-service output-errors`, then both full suites. Re-run the probes: p1, p2, p3, p8, p9 and p11 should now show the new behaviour.

## 9. Out of scope / alternatives considered

- **Blanking the whole chain on any effect error (fail closed)**: the option O2-A. Not chosen; see O2.
- **A master output switch**: the option O4-A. Not chosen; blackout keeps stopping the transport (`triggers.clj:76-77`).
- **Blanking out-of-range points** (O3-B, and 03 MR5 for the effect side): not chosen; the encoder keeps clamping.
- **Showing errors in the UI**: the registry is filled here; Phase 4 (O8) publishes it.

---

## Decisions

> **DECISION O2 — What does an effect error do to the output?** *(answered 2026-11-16)*
>
> | | What |
> |---|---|
> | A | Fail closed: the chain that failed contributes nothing until it stops failing |
> | B | Fail closed only for `:calibration` and `:intensity` effects |
> | C | Keep today's behaviour; only fix the logging |
> | **Chosen** | **Skip only the effect that throws**; the rest of its chain still applies; log it once |
>
> **Your answer:** "If an effect throws just skip that effect." The trade-off is accepted: a failing corner pin (scan-area limit), dimmer or blackout effect is skipped, and the projector's frame goes out without it, as it does today.

> **DECISION O3 — What does the last-line sanitiser change?** *(answered 2026-11-16)*
>
> **Your answer:** "Bad points can get filtered out." Points with a `nil` or non-finite value are removed rather than replaced with dark points. The spec's frame rules (a dark first sample, at least 2 samples) are kept. Range clamping stays in the encoder (O3-B, blanking out-of-range points, not chosen).

> **DECISION O4 — A master output switch?** *(answered 2026-11-16: C)*
>
> **Your answer:** "Let's not add a separate laser output button. I'm fine with how it currently works." No switch. The blackout trigger keeps stopping the transport.
