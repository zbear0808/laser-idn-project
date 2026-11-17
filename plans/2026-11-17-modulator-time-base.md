# One time base for modulators (review 03 MR1)

**Written:** 2026-11-17   **Written for commit:** `a4a1405` (`master`)
**Status:** Proposed   **Last verified against:** `a4a1405` (probe in §1, macOS arm64, JDK 25.0.5)
**Supersedes / Superseded by:** none. Takes over review 03 MR1 steps 1 (without the key rename), 2, 4 and 5; step 3 stays open (§9)   **Related findings:** review 03 #1 (once-mode never progresses, High) and #16 (timing helpers duplicated, not nil-safe); 03 MR1; the output runtime's show clock (O10), which needs this first (`plans/2026-11-16-output-runtime/00-overview.md` §4); timeline Phase 4's note that clip-local once-mode needs MR1 (`plans/2026-11-08-timeline-rework/04-phase4-transport-and-time.md:109`)

Pick "Mode: once" on a wave modulator (`modulators.clj:27,37,48`) or a keyframe modulator (`keyframe_modulator_panel.clj:248,324`) in a grid cue and nothing moves: the modulator stays at its start value forever. The same happens on a timeline's track and folder effects. Only clip effects work, because the timeline rework gave clips their own clock. About half a day.

## 1. Current behaviour (file:line)

| What | Where | Today |
|---|---|---|
| A cue's timing context | `get-cue-timing-context` (`cue_timing.clj:191-213`) | `:time-ms` is `accumulated-ms`, **relative** to the trigger; `:trigger-time` is the cue's start on the **wall clock** (`create-cue-timing-state` `:262`, from `System/currentTimeMillis` at `grid.clj:38`) |
| Once-mode progress | `calculate-once-progress` (`time.clj:158-180`) | `elapsed = time-ms − trigger-time`: relative minus absolute, about −1.8 × 10¹² ms, clamped to 0. Used by the waves (`calculate-once-mode-phase-data` `:182-213`, called from `modulator_evaluators.clj:30-71`), random (`:73-91`) and time-driven keyframes (`keyframes.clj:43-62`) |
| Beats-unit duration | `calculate-once-progress` (`time.clj:175-177`) | Converts the duration in beats to ms at the **current** BPM, so a tempo change mid-animation moves the progress |
| How contexts are built | `effects.clj:243-246` (keyframes: `(merge {:time-ms … :bpm … :trigger-time …} timing-ctx)`), `:390`, `:443-447`, `:454` | The timing context's values win, so every production context carries the bad pair. `make-context` gives any context without accumulators `0.0` ones (`modulation.clj:66-67`) |
| Timeline clips | `clip-timing-ctx` (`playback.clj:28-46`) | Sets `:trigger-time 0` and a clip-local `:time-ms`, so the subtraction works: clips are the one place once-mode runs |
| Timeline track and folder effects | `render-track-tree` (`frame_service.clj:538-557`, called at `:610`) | Use the cue's context, so they freeze like grid cues |
| Effects grid (global effects) | `apply-global-effects-to-frame` (`frame_service.clj:502-519`), `get-global-timing-context` (`cue_timing.clj:216-246`, `:trigger-time 0`) | Runs, but "once" counts from the global clock's start: app start, or the last Stop or Retrigger (`handlers/timing.clj:68-86`) |
| Decay modulator | `decay-elapsed` (`modulator_evaluators.clj:136-145`, since `a702e9f`) | Already right: elapsed comes from the raw accumulators (beats without the phase glide, or ms), falling back to `time-ms − trigger-time` only when a context has none |
| Dead code | `calculate-modulator-phase`'s once branch and `trigger-override` (`time.clj:238-250`), `resolve-trigger-time` (`:145-152`) | Both callers pass `:loop` (`modulator_evaluators.clj:28,91`); nothing else calls them |
| Timing helpers (03 #16) | `update-cue-timing` (`cue_timing.clj:123-188`), `update-global-clock` (`:72-121`), `align-global-clock-phase` / `align-cue-timing-phase` (`:32-61`), `get-cue-timing-context` (`:206-213`) | The phase glide (snap below 0.02 beats, else exponential decay) is written twice; the two align fns are copies; `update-cue-timing` and `get-cue-timing-context` do arithmetic on keys without defaults, so a partial timing map throws in the preview tick |
| Tests | `modulation_test.clj:400-583` | Every once-mode test builds its context by hand with `:trigger-time 0` and an absolute `:time-ms`, which never happens in the app. No test goes through `get-cue-timing-context`. `playback_test.clj:6-15` pins the clip context, `:trigger-time 0` included |

**Probe** (a scratch script against `a4a1405`: a cue started with `cue-timing/activate-cue` at the wall-clock time, advanced in 33 ms ticks at 120 BPM, then rendered through `effects/apply-effect-chain` or `frame-service/generate-frames-by-zone`; a 4-beat once-mode sawtooth on `:scale` `:x-scale`):

| Case | 0 ms | 500 ms | 1 000 ms | 1 500 ms | 3 000 ms |
|---|---|---|---|---|---|
| Grid cue, the sawtooth (x of a point at 0.5) | 0.500 | 0.500 | 0.500 | 0.500 | 0.500 |
| Grid cue, a once keyframe modulator (x) | 0.500 | 0.500 | 0.500 | 0.500 | 0.500 |
| Timeline, the sawtooth on the **track**'s effects (circle width) | 0.999 | – | 0.999 | – | 0.999 |
| Timeline, the same on the **clip**'s effects | 0.999 | – | 0.500 | – | 0.000 |

And on the global clock, 2 beats into the 4-beat sawtooth: 0.500 at 120 BPM, but **0.750** once the BPM is 60, with the same 2 beats played.

## 2. Root cause

The timing context mixes a relative time (`:time-ms`) with an absolute one (`:trigger-time`), and once-mode subtracts them. Only the clip context makes the pair consistent, by setting `:trigger-time` to 0. Separately, beats-unit progress goes through milliseconds at today's BPM instead of counting beats.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `decay-elapsed` (`modulator_evaluators.clj:136-145`) | Moves to `laser-show.animation.time` as `elapsed` and becomes the one way any modulator asks "how long since the cue started" |
| The accumulators every production context has: `:accumulated-beats`, `:accumulated-ms` (`cue_timing.clj:207-211`, `:240-245`, `playback.clj:38-46`) | Once-mode progress reads them directly: no subtraction, and beats stay beats |
| `reset-cue-timing` (`cue_timing.clj:270-284`), timeline seek | Triggering a cell again (`trigger-cue`, `grid.clj:33-44`, fresh timing) and `:transport/retrigger` (`handlers/timing.clj:79-86`) already reset the accumulators, and seek moves them, so once-mode restarts or follows with no new code |
| The phase-glide maths in `update-global-clock` (`cue_timing.clj:100-106`) | One private helper used by both clocks |
| `modulation-test`'s once-mode cases (`modulation_test.clj:400-583`) | Stay as they are: a context without accumulators still falls back to `time-ms − trigger-time` |

## 4. Changes per namespace

- `laser-show.animation.time`:
  - NEW `elapsed [context time-unit]` (moved from `decay-elapsed`): `:seconds` → `accumulated-ms / 1000`; otherwise `accumulated-beats`. A context with no accumulators falls back to `time-ms − trigger-time`, converted at its BPM.
  - `calculate-once-progress` becomes `[context duration time-unit]` → `elapsed / duration`, clamped to [0, 1]; a duration ≤ 0 is done (1.0), as today.
  - `calculate-once-mode-phase-data` (`:182-213`) passes the context through.
  - `calculate-modulator-phase` (`:219-254`) becomes NEW `calculate-loop-phase [context period phase time-unit]`, its loop branch. `resolve-trigger-time` goes.
- `laser-show.animation.modulator-evaluators`: `calculate-loop-mode-phase` (`:24-28`) and the random loop branch (`:91`) call `calculate-loop-phase`; the random once branch (`:78-89`) calls the new `calculate-once-progress`; `eval-decay` (`:160-172`) uses `time/elapsed`; `decay-elapsed` goes.
- `laser-show.animation.keyframes` (`calculate-time-position`, `:43-62`): the once branch calls `calculate-once-progress` with the context, after `(merge {:time-ms time-ms} context)` so the number-only `eval-keyframe` arity (`:243-249`) still works.
- `laser-show.animation.cue-timing`:
  - `get-cue-timing-context` (`:191-213`) and `get-global-timing-context` (`:216-246`) no longer return `:trigger-time`; a timing context is relative time only. `get-cue-timing-context` defaults missing keys to 0.
  - (03 #16) NEW private `glide-phase [phase target delta-beats resync-rate]`, used by `update-global-clock` and `update-cue-timing`; NEW private `with-aligned-target`, used by both align fns; `update-cue-timing` defaults missing accumulators and phase keys to 0.
- `laser-show.timeline.playback` (`clip-timing-ctx`, `:28-46`): no `:trigger-time`.
- `laser-show.animation.effects` (`:243-246`): the keyframe context no longer merges in `:trigger-time`. `apply-effect-chain`'s `trigger-time` argument stays (§9) but no modulator reads it.
- `laser-show.services.frame-service` (`get-timing-context`, `:233-252`, deprecated): no `:trigger-time` in its zero context.

## 5. State placement

No state changes. `[:playback :active-cues cell :trigger-time]` stays in the cue timing state (`cue_timing.clj:262`; the preview and the engines read it for the frame cache and the legacy `elapsed`, `frame_service.clj:708`, `multi_engine.clj:153,172`). It just stops being copied into modulation contexts. Nothing enters undo or dirty tracking.

## 6. Events and effects

None. Triggering a cell (`grid.clj:33-44`), `:transport/retrigger` and `:transport/stop` (`handlers/timing.clj:68-86`) and timeline seek already reset or move the accumulators that once-mode now reads.

## 7. Data examples and invariants

A 4-beat once-mode sawtooth (`:min 0 :max 1 :period 4`), at 120 BPM unless said:

| Context | Progress today | After | Sawtooth after |
|---|---|---|---|
| Grid cue, `accumulated-beats 2.0`, `accumulated-ms 1000`, `trigger-time` 1.8 × 10¹² | 0 | 0.5 | 0.5 |
| Same cue after 4.0 beats, and after 9.0 | 0 | 1.0 (done) | 0.0, held |
| Global clock, `accumulated-beats 2.0`, `accumulated-ms 1000`, now at 60 BPM | 0.25 | 0.5 | 0.5 |
| Retriggered (`reset-cue-timing`: accumulators back to 0) | 0 | 0 | 1.0 (starts again) |
| Timeline track effect, playhead at beat 3.0 | 0 | 0.75 | 0.25 |
| `:time-unit :seconds`, period 2, `accumulated-ms 500` | 0 | 0.25 | 0.75 |
| Hand-built `{:time-ms 1000 :trigger-time 0 :bpm 120}` (no accumulators) | 0.5 | 0.5 | 0.5 |

**Invariants:**
1. A timing context carries relative time only: no key in it is a wall-clock time.
2. A once-mode modulator's progress is the time since its cue started (or since the clip started, for clips) divided by its duration, in its own unit; beats are counted as beats, so a tempo change never moves a beats-unit modulator back or forward.
3. Retriggering a cue restarts its once-mode modulators.
4. A context without accumulators behaves as today (the hand-built test contexts).
5. Loop-mode modulators are unchanged.

## 8. Tests (named test vars + fixtures)

- `laser-show.animation.time-test` (NEW, pure): `elapsed-examples-test` (both units, the fallback), `once-progress-examples-test` (the table above).
- `laser-show.animation.cue-timing-test`: `timing-context-is-relative-test` (no `:trigger-time`; built from a cue started at a wall-clock time), `update-cue-timing-is-nil-safe-test` (a map with only `:last-frame-time` advances), `align-phase-unchanged-test` (both align fns give today's targets).
- `laser-show.animation.modulation-test` (the app's path: `activate-cue` at `System/currentTimeMillis`, `update-cue-timing` × N, `get-cue-timing-context`, `effects/apply-effect-chain`): `once-mode-progresses-in-a-grid-cue-test` (sine, sawtooth, square, random and a time-driven keyframe), `once-mode-restarts-on-retrigger-test`, `beats-once-mode-ignores-tempo-changes-test`, `seconds-once-mode-uses-elapsed-ms-test`. The existing hand-built once-mode tests stay unchanged (invariant 4).
- `laser-show.services.frame-service-test`: `once-mode-on-a-track-effect-follows-the-playhead-test` (the probe's timeline case).
- `laser-show.timeline.playback-test/clip-timing-ctx-test`: the pinned context loses `:trigger-time`.

Run: `clj -M:mac-arm:test time-test cue-timing modulation frame-service playback keyframe decay`, then the full unit suite.

## 9. Out of scope / alternatives considered

- **MR1 step 3, one modulation context built per effect chain** (`effects.clj:243-246,390,443-447,454`): a performance claim without a measurement yet (planning guide rule 9). With it would go `apply-effect-chain`'s unused `trigger-time` argument and its threading through about fifteen frame-service functions. The show clock (output runtime O10) rewrites those calls anyway, so both belong there.
- **Once-mode on effects-grid cells** counting from when the cell was switched on: needs a start time per cell and a context per cell instead of one global chain. A feature; until then "once" on a global effect counts from the global clock's start.
- **Seconds on timeline track effects.** Clips derive their ms from the playhead (`playback.clj:36-37`), but the cue-level context counts real ms since the cue started, so a seconds-unit modulator on a track effect doesn't follow a seek. Today's loop-mode behaviour; the show clock should define one timeline time for both.
- **Renaming the accumulators to `:elapsed-ms` / `:elapsed-beats`** (MR1 step 1 as written): about twenty readers would change and nothing would behave differently.
- **Defaulting a missing timing context to the wall clock**: contexts without accumulators exist only in tests, and today's fallback already serves them.
