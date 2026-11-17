# Phase 2 — Nothing unsafe reaches the wire

**Written:** 2026-11-16   **Written for commit:** `70e049e` (`master`)
**Status:** Proposed. Needs O2–O4 (and Phase 1)   **Last verified against:** `70e049e`
**Supersedes / Superseded by:** none   **Related findings:** O-2 to O-8 (`00-overview.md` §2); review 04 findings 8, 16, 26 (fail-closed part of LE3), QW6; 03 findings 6, 7, 9, 10, MR4, the output half of MR5

Today, when something goes wrong while a frame is produced, the frame goes out anyway, minus whatever failed:
- a NaN coordinate becomes a full-brightness point at the centre;
- a failing calibration or intensity effect is skipped;
- an `Error` silently ends the thread that was sending or timing.

This phase puts one last check in front of the encoder. Every failure then turns into a dark frame that is logged once, and every long-lived loop on the output path survives. It also adds a master output switch (O4), which blanks every engine, orphans included, until Phase 4 fixes ownership. About 1.5–2 days.

## 1. Current behaviour (file:line)

| What | Where | Today |
|---|---|---|
| NaN and ±Inf coordinates | `common/util.clj:13-27` (`clamp` tests with `<` and `>`, both false for NaN, so NaN passes); `idn/output_config.clj:99-111` | NaN becomes 0 on the wire and keeps its colour: a lit point at the centre. ±Inf clamps to the edge. A NaN colour becomes 0 (p1). |
| `nil` coordinates | `idn/stream.clj:300-304` (`(point t/X)` into `double`) | `NullPointerException` in the encoder (p1). The engine catches it and sends nothing (p2). |
| "Is this point dark?" | `animation/types.clj:94-101` | `blanked?` is false for NaN colours, so every effect that skips dark points treats a NaN colour as lit (p1) |
| First sample and minimum size (Stream §6.2: the first sample "SHALL be invisible"; a non-empty frame "SHALL contain at least 2 samples") | nowhere | Not enforced. Most generators start dark (`generators.clj:60,87,118`), but `wave-frame` starts lit (`:279-298`), and with 1 point it divides by zero (`:291,294`), giving a NaN point. |
| Effect errors | `animation/effects.clj:252-258`: a transducer that fails to build becomes `(map identity)`; `services/frame_service.clj:315-319`: a failing item chain gives back the item's input; `:497-501`: a failing global chain gives back its input; `backend/multi_engine.clj:111-116`: a failing projector chain gives back the **uncalibrated** frame | Every layer drops the failing effects and sends the rest (p8). That includes the corner pin (scan-area limit), intensity and the blackout effect (`intensity.clj:34-51`). |
| Frame provider errors | `multi_engine.clj:207-212` | Returns nil, so the engine sends an empty frame (`streaming_engine.clj:219`). This one already fails closed. |
| Encoder and send errors | `streaming_engine.clj:256-259` | That frame isn't sent (p2). In discrete mode the DAC keeps drawing the last frame it got (Stream §3.3) until its link timeout, 1 s by default (Hello §7.2). |
| Errors in long-lived loops | engine loop catches `Exception` only (`streaming_engine.clj:256`) and sleeps outside the `try` (`:261-264`); the preview `TimerTask` catches `Exception` only (`frame_service.clj:859-862`), and a `java.util.Timer` cancels itself when a task throws | An `Error` (an `AssertionError` from a `:pre`, a `StackOverflowError`) ends the engine thread (p3) or the preview timer (p11). Nobody is told; `running?` stays true. |
| Calibration and blanking | `effects/calibration.clj:18-37` | `rgb-curves` maps every point through its curves, blanked ones included, so raising a curve's low end lights every travel move (p9). The colour effects skip blanked points (`color.clj:20,51,126`). |
| Logging | `streaming_engine.clj:259` (full stack via `u/exception->map`), `multi_engine.clj:115,208,211`, `animation/effects.clj:255-256`, `frame_service.clj:318,500` | ERROR on every failing frame, on the real-time threads. The only rate-limited log is the truncation warning (`stream.clj:331-345`). |
| Master output | `triggers.clj:76-77` (`:stop-all` and `:blackout` both send `:transport/stop`); `handlers/timing.clj:68-77` | The only way to darken every projector at once is to stop the transport, and that rewinds every cue and the global clock. Output is live as soon as Stream is pressed and something plays. |

## 2. Root cause

Each layer catches its own errors and keeps the show going by passing the input through. For a laser that is the wrong default: the stages that get skipped are the ones that keep the beam safe (calibration, intensity, blackout). And nothing sits between the last effect and the encoder that owns "this frame is valid".

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `t/blanked-point`, `t/blanked?` (`types.clj:86-101`) | What the sanitiser and the fail-closed paths produce (`blanked?` gets a NaN guard) |
| The throttled truncation warning (`stream.clj:331-345`, a `compare-and-set!` on a timestamp) | The pattern for the throttled logger in `output-errors` |
| `concatenate-frames` blanking jumps (`frame_service.clj:338-362`) | Unchanged. Zone frames are already joined dark; the sanitiser handles the frame's very first sample. |
| `create-idn-stream-packets` (`streaming_engine.clj:136-172`) | The sanitiser runs right before it, in the engine, so it covers every provider |
| The provider closure per projector (`multi_engine.clj:139-212`) | The arm check (O4) goes at its top. Every engine, orphans included, calls one of these closures. |
| Effect registry `:category` (`animation/effects.clj:79-104`, categories at `:64`) | O2-B needs it to tell safety effects (`:calibration`, `:intensity`) from the rest |
| Fake DAC (Phase 1) | Every test below observes the wire |

## 4. Changes per namespace

- `laser-show.idn.frame-rules` (NEW, pure): `sanitize-frame [frame] → frame`. Per point:
  - `nil` or non-finite x/y: a blanked point at the last valid position (0, 0 if there is none);
  - a `nil` or non-finite colour channel: 0.
  
  Then for the whole frame:
  - a lit first sample gets a dark copy of it prepended;
  - a frame with exactly one sample gets it duplicated.
  
  Empty stays empty. Range clamping stays in the encoder (O3-A); O3-B adds blanking of out-of-range points. Examples in §7.
- `laser-show.backend.output-errors` (NEW, runtime atom `{key {:message :count :first-ms :last-ms :logged-ms}}`):
  - `report! [key ^Throwable e]` logs WARN with the stack for a new key or message, then at most every 10 s with a count;
  - `ok! [key]` removes the key and logs "recovered after N" when it was present;
  - `snapshot []` returns a copy for the status in Phase 4.
  
  Keys: `[:engine pid]`, `[:provider pid]`, `[:projector-effects pid]`, `[:item-effects item-id]`, `[:global-effects]`, `[:effect instance-id]`, `[:preview]`.
- `laser-show.backend.streaming-engine` (`streaming_engine.clj:212-264`):
  - Each iteration runs `provider → sanitize-frame → packets → send` inside `try` … `catch Throwable`.
  - On an error it reports `[:engine id]` and then tries to send an empty frame. If that send fails too, the error is reported and the loop carries on.
  - Pacing runs after the `try` in all cases.
  - On success it calls `ok!`.
- `laser-show.backend.multi-engine`:
  - `apply-projector-effects` (`:102-117`): on `Throwable`, reports and returns `(t/empty-frame)` instead of `frame`.
  - The provider's catches (`:207-212`) become `Throwable` and report; it still returns nil.
  - The arm check for O4-A goes at the top of the provider (`:153`), with `!armed?` (NEW `defonce` atom), `armed?` and `set-armed!`.
- `laser-show.services.frame-service`:
  - `apply-item-effects` (`:306-320`) and `apply-global-effects-to-frame` (`:487-502`): on an error they report and return `(t/empty-frame)` (O2-A; with O2-B they keep today's pass-through).
  - The preview task (`:855-863`) catches `Throwable` and reports `[:preview]`.
- `laser-show.animation.effects` (`make-effect-transducer`, `:252-258`): reports `[:effect (:id effect-instance)]` once. It returns a transducer that blanks every point:
  - O2-A: for every effect;
  - O2-B: only for `:calibration` and `:intensity` effects, the rest keep `(map identity)`.
- `laser-show.animation.types` (`blanked?`, `:94-101`): a NaN channel counts as dark. A point that isn't provably lit is treated as unlit.
- `laser-show.animation.effects.calibration` (`rgb-curves-xf`, `:18-37`): blanked points stay blanked.
- `laser-show.animation.generators` (`wave-frame`, `:279-298`): starts with a blanked point; `num-points` is raised to at least 2.
- O4-A: the master output switch
  - `domains.clj` (backend `defstate`, `:398-…`): `:output {:default {:armed? false}}`. `:backend` isn't saved (`persistent.clj:48-61`).
  - `events/core.clj`: NEW effect `:output/set-armed` (calls `multi-engine/set-armed!`).
  - `events/handlers/connection.clj` + routing in `handlers.clj` (`"output"` beside `"idn"`, `:49`): NEW `:output/arm`, `:output/disarm`, `:output/toggle-armed`. Each writes the UI mirror `[:backend :output :armed?]` and returns `:output/set-armed`.
  - `events/handlers/triggers.clj:77`: `:blackout` sends `:output/disarm`. The transport keeps running, so re-arming resumes in time. No view offers this trigger action yet.
  - `subs.clj`: NEW `output-armed?`.
  - `views/toolbar.clj` (next to Stream, `:235-243`): NEW `#output-arm-btn`. It reads "Output off" (neutral) when disarmed and "OUTPUT ON" (red) when armed.
  - `app.clj`: `start!` and `stop!` (`:118-175`, `:209-228`) set the gate to disarmed.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `output-errors` registry NEW | runtime atom (`backend/output_errors.clj`) | engine threads, the preview timer, frame code | No / no |
| `multi-engine/!armed?` NEW (O4-A) | runtime atom. Engines read it on every frame, so an event that resets the whole state (`events/core.clj:70-74`) can't switch output back on | the `:output/set-armed` effect; `app/start!` and `stop!` | No / no |
| `[:backend :output :armed?]` NEW | app state, backend (not saved) | `:output/*` handlers (display only) | No / no |

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:output/arm`, `:output/disarm`, `:output/toggle-armed` | NEW | `#output-arm-btn`; the `:blackout` trigger action (`triggers.clj:77`) | yes |
| `:output/set-armed` | NEW effect | the three handlers above | effect (atom write) |
| `:transport/stop` from `:blackout` | changed: no longer sent | – | – |

## 7. Data examples and invariants

`sanitize-frame` with O3-A (`d` = a dark point, so `[x y 0 0 0]`):

| Input | Output |
|---|---|
| `[]` | `[]` |
| `[[0.5 0.5 1 1 1]]` | `[[0.5 0.5 0 0 0] [0.5 0.5 1 1 1]]` |
| `[[0 0 0 0 0]]` | `[[0 0 0 0 0] [0 0 0 0 0]]` |
| `[d(0,0) [NaN NaN 1 1 1] [0.5 0 1 1 1]]` | `[d(0,0) d(0,0) [0.5 0 1 1 1]]` |
| `[d(0,0) [0.2 nil 1 1 1]]` | `[d(0,0) d(0,0)]` |
| `[d(0,0) [0.2 0.2 NaN 1 1]]` | `[d(0,0) [0.2 0.2 0 1 1]]` |
| `[[NaN NaN 1 1 1]]` | `[d(0,0) d(0,0)]` |
| `[d(-0.5,0) [2.0 0 1 1 1]]` | unchanged (the encoder clamps 2.0 to 32767, as today). O3-B: `[d(-0.5,0) d(1.0,0)]` |

What an error does, before and after, with O2-A:

| Failure | Today | After |
|---|---|---|
| An effect's transducer fails to build (cue, zone or global) | the effect is skipped | that chain's points are blanked |
| An effect throws per point in a cue item's chain | the item's frame without its effects | that item contributes nothing |
| The global chain throws | the frame without global effects | empty frame |
| A projector chain (calibration) throws | uncalibrated frame (p8) | empty frame for that projector |
| The provider throws | empty frame | empty frame (unchanged) |
| The encoder throws | nothing sent (p2) | empty frame sent (after the sanitiser it shouldn't happen at all) |
| An `Error` in the engine loop | thread ends (p3) | empty frame, the loop continues |
| An `Error` in the preview tick | timer cancelled (p11) | reported; the next tick runs |

Logging for one key: the first failure is a WARN with the stack. While it keeps failing, one WARN every 10 s says "still failing: 600 times in the last 10 s". When it works again, one INFO says "recovered after 1 834 failures".

The arm switch with O4-A:

| `armed?` | `[:playback :playing?]` | Every engine sends |
|---|---|---|
| false (the default at start) | any | empty frames |
| true | false | empty frames (as today) |
| true | true | content |
| blackout trigger | unchanged | `armed?` becomes false, so empty frames; cues keep running |

**Invariants:**
1. Nothing lit leaves an engine created by multi-engine unless `armed?` is true (O4-A).
2. No NaN, ±Inf or `nil` value reaches the encoder.
3. Every non-empty frame on the wire starts with a dark sample and has at least 2 samples.
4. An error anywhere while producing a frame gives an empty or blanked frame on the wire, never the previous frame and never an uncalibrated one.
5. `sanitize-frame` never lights a point that was dark, and applying it twice changes nothing.
6. The engine thread and the preview timer survive any `Throwable` from producing a frame.
7. Each error key logs at most once per 10 s.

## 8. Tests

Each probe gets turned into a failing test first.

- `laser-show.idn.frame-rules-test/sanitize-examples-test` (the table above) and `/sanitize-invariants-test`: generated frames with NaN, ±Inf and `nil`; idempotence; never lights a dark point.
- `laser-show.backend.engine-wire-test` (fake DAC):
  - `nan-point-goes-out-dark-test` (p1)
  - `nil-point-sends-an-empty-frame-test` (p2: frames keep arriving)
  - `error-in-provider-keeps-the-engine-alive-test` (p3)
  - `first-sample-is-dark-test`
  - `disarmed-output-sends-empty-frames-test`
- `laser-show.backend.multi-engine-test/failing-calibration-blanks-the-projector-test` (p8, both kinds).
- `laser-show.animation.effects-test/effect-that-fails-to-build-blanks-its-chain-test` (O2-A), or `/failing-intensity-effect-blanks-test` (O2-B).
- `laser-show.animation.effects.calibration-test/rgb-curves-keep-blanked-points-dark-test` (p9) (NEW ns if none exists).
- `laser-show.animation.types-test/nan-colour-is-not-lit-test`.
- `laser-show.animation.generators-test/wave-frame-starts-dark-test`, `/one-point-wave-is-finite-test`.
- `laser-show.services.frame-service-test/preview-timer-survives-an-error-test` (p11).
- `laser-show.backend.output-errors-test/logs-once-then-throttled-test`, `/ok-logs-recovery-once-test`.
- O4-A: `laser-show.events.connection-handlers-test/blackout-trigger-disarms-and-keeps-playing-test`, `/arm-toggles-the-gate-test`; UI: `laser-show.ui-test.toolbar-test/output-arm-button-test` (`#output-arm-btn` shows the state; clicking toggles the atom).

Run: `clj -M:mac-arm:test frame-rules engine-wire multi-engine effects calibration generators frame-service output-errors connection-handlers`, then `clj -M:test-ui:mac-arm:test-ui-headless toolbar`, then both full suites. Re-run the probes: p1, p2, p3, p8, p9 and p11 should now show the new behaviour.

## 9. Out of scope / alternatives considered

- **Blanking out-of-range points inside every effect** (03 MR5, unclamped `t/with-xy`): stays with the effects plan. Here only the output-side failsafe is added (O3-B is the last-line version of it).
- **A test-pattern output while calibrating** (04 LE3): the calibrating id exists (`subs.clj:524-529`) but is a separate feature.
- **Showing errors in the UI**: the registry is filled here; Phase 4 (O8) publishes it.
- **Keeping the last good frame on an error instead of blanking:** rejected. A frozen frame is a static beam, which is the riskiest thing a projector can draw.

---

## Decisions

> **DECISION O2 — What does an effect error do to the output?** *(open · needed before Phase 2 · recommended: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | Fail closed: the chain that failed contributes nothing (a cue item, the global chain, or the whole projector), its points blanked, until it stops failing. Logged once per effect or chain, with a count. | A broken corner pin, dimmer or blackout can never make the output brighter or move it outside the calibrated area; the rule is the same everywhere | A broken colour effect on one clip makes that clip go dark instead of showing it uncoloured |
> | B | Fail closed only for safety effects (`:calibration` and `:intensity`, plus the projector chain); everything else keeps today's skip-the-effect behaviour, logged once | Cosmetic effects keep "the show goes on" | Two policies; a shape effect that throws can still move content out of a zone |
> | C | Keep failing open; only fix the logging | No visual change | O-2 stays: a failing corner pin sends uncalibrated output |
>
> **Your answer:** _pending_

> **DECISION O3 — What does the last-line sanitiser change?** *(open · needed before Phase 2 · recommended: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | `nil` or non-finite x/y → a blanked point at the last valid position; non-finite colour → 0; a lit first sample gets a dark copy in front; a single sample is doubled. Range clamping stays in the encoder, as today | Fixes O-3 with no visible change for valid frames | A point at x = 2.0 still piles up lit on the edge |
> | B | A, plus points outside [-1, 1] are blanked at their clamped position | No lit beam parked on the field edge, whatever the effects do | Visible change: content scaled or moved off the field is cut at the edge rather than squashed onto it |
>
> **Your answer:** _pending_

> **DECISION O4 — A master output switch?** *(open · needed before Phase 2 · recommended: A)*
>
> Today the only way to darken every projector is to stop the transport, which rewinds every cue (`handlers/timing.clj:68-77`). Output is live as soon as Stream is pressed and something plays.
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | A runtime "Laser output" switch, **off every time the app starts** and never saved. A toolbar button arms and disarms it, and the blackout trigger disarms it without stopping the transport. Every engine checks it on every frame, orphans included. | An interlock like most laser software has; one click (or MIDI/OSC) darkens everything, even engines Stop failed to reach (O-1) until Phase 4 | One extra click per session before any light |
> | B | The same switch, but on at start | Today's workflow is unchanged; blackout still works without rewinding | No interlock at startup |
> | C | No switch; blackout keeps stopping the transport | Nothing new | O-8 stays, and so does the gap in O-1 |
>
> **Your answer:** _pending_
