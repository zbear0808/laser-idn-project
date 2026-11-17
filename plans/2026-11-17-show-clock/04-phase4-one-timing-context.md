# Phase 4 — One timing context down the render

**Written:** 2026-11-17   **Written for commit:** `f7fdd79` (`master`)
**Status:** Proposed   **Last verified against:** `f7fdd79` (citations; probe c7 in `00-overview.md` §1). Phases 1 and 2 change the frame-service callers cited here; re-check them when they land
**Supersedes / Superseded by:** none. Takes over review 03 MR1 step 3, which `plans/2026-11-17-modulator-time-base.md` §9 left open   **Related findings:** S-5 (rest), S-7 (`00-overview.md` §2); review 04 #23 (rest), #25 (deprecated helpers); overview T4 ("a single relative timing context down the effect chain")

Since the modulator time base (`803140d`), every modulator reads its time from the timing context. But the render still threads `elapsed-ms`, `bpm` and `trigger-time` through about fifteen functions and every effect chain. Those values lose to the context's own wherever they meet (`effects.clj:243-245,389,443-446,453`).

Each modulated parameter also builds a modulation context of its own: 16 per render of the busy cue, at least 6 % of the render (c7). On top of that, each render step reads app state and the input store again (S-5).

This phase passes one timing context per cue and one render context per tick, and builds one modulation context per effect chain. About 0.5–1 day, with no decision needed.

## 1. Current behaviour (file:line)

| What | Where | Today |
|---|---|---|
| Render arguments | `render-preset-instance` (`frame_service.clj:292-299`, `_elapsed-ms` unused), `apply-item-effects` (`:318-334`), `concatenate-frames` (`:352-376`, `_elapsed-ms` unused), `render-item-with-effects` (`:378-410`), `generate-frame-from-cue-chain` (`:412-427`), `render-item-tree-by-zone` (`:429-499`), `apply-global-effects-to-frame` (`:501-518`), `merge-zone-maps` (`:520-535`), `render-track-tree` (`:537-556`), `generate-frames-by-zone` (`:558-621`); `apply-projector-effects` (`multi_engine.clj:95-112`) | Each takes some of `elapsed-ms bpm trigger-time timing-ctx`. Clips get `playback/clip-elapsed-ms` (`frame_service.clj:599`, `playback.clj:49-52`) |
| The effect chain | `apply-effect-chain` (`effects.clj:326-366`) has arities from `[frame chain time-ms]` to `[frame chain time-ms bpm trigger-time timing-ctx]` (`:343-349`); the 3-arity reads the BPM from app state (`:344`). Then `effect-transducers` (`:264-272`), `make-effect-transducer` (`:219-262`, which ignores `trigger-time`), and each effect's `(xf-fn time-ms bpm params frame-ctx)` (`:259`) | The only real input is `timing-ctx`. `time-ms` matters only in trace-fade's fallback for contexts without accumulators (`intensity.clj:115-123`) |
| Modulation contexts | `make-param-resolver` builds one per modulated parameter (`effects.clj:453`, and the per-point base at `:442-446`); `resolve-params-global` (`:389`); the keyframe path (`:243-245`). Each is `(mod/make-context (merge {:time-ms … :bpm …} timing-ctx))` | c7: 1 per render for the simple cue (2 % of it), 16 for the busy cue (≥ 6 %) |
| Reads per frame | `generate-frames-by-zone` reads app state per cue (`frame_service.clj:591`). `get-active-global-effects` (`:253-271`) reads it per zone per cue, called from `:504`. `apply-global-effects-to-frame` reads the input store per zone per cue (`:507`) | One frame can mix several app states and several input values (review 04 #23) |
| Deprecated helpers | `get-active-cell-data` (`:168-181`), `get-bpm` (`:201-204`), `is-playing?` (`:206-209`) | After Phase 2, only tests call them (`frame_service_test.clj:174-237`) |
| Call sites of `apply-effect-chain` | `src`: `multi_engine.clj:105`, `frame_service.clj:328,512`. Tests: `effects_test.clj` (12), `effect_presets_test.clj:96`, `effects/calibration_test.clj:15`, `modulation_test.clj:593,613` | – |

## 2. Root cause

The timing context arrived after the render API was already threading wall-clock arguments. Nobody removed the old arguments. Contexts and state reads are built where each value is used, not once where the frame starts.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `get-cue-timing-context` (`cue_timing.clj:193-218`), `playback/clip-timing-ctx` (`playback.clj:28-47`) | The one timing context per cue and per clip, as today |
| `mod/make-context`, `make-base-context`, `with-point-context` (`modulation.clj:42-113`) | Called once per chain instead of once per parameter |
| `get-active-global-effects` (`frame_service.clj:253-271`) | Called once per tick by the clock, with the tick's state |
| Phase 2's `engine-frame-equals-direct-render-test`, `presets-change-the-frame-test` (`effect_presets_test.clj:89-`), and the coverage test that every effect parameter changes the output (`0153d5c`) | They pin the frames, so this refactor can't change any |

## 4. Changes per namespace

- `laser-show.services.show-clock`: `tick!` builds NEW `render-ctx` once per tick:
  - `:state`: the tick's single `get-raw-state`;
  - `:input-values`: one `input-store/get-all-continuous-values`;
  - `:global-effects`: `get-active-global-effects` of that state, or nil;
  - `:global-ctx`: the global clock's timing context at the global BPM, with the input values.
  - It passes this to every cue's render, and `:input-values` to the projector context.
- `laser-show.services.frame-service`. The render functions take `timing-ctx` (and `render-ctx` where they need state) instead of `elapsed-ms bpm trigger-time`:
  - `generate-frames-by-zone [cue-chain timing-ctx render-ctx]` (`:558-621`). The cue's tempo is `(:bpm timing-ctx)`, also for `clip-timing-ctx` (`:597`).
  - `render-item-tree-by-zone [item parent-zone-id timing-ctx tracks default-zone-id]`, `render-item-with-effects [item timing-ctx]`, `generate-frame-from-cue-chain [cue-chain timing-ctx]`, `apply-item-effects [frame item timing-ctx]`, `render-track-tree [tracks clip-maps-by-track timing-ctx]`, `merge-zone-maps [zone-maps]`, `concatenate-frames [frames]`, `render-preset-instance [item]`.
  - `apply-global-effects-to-frame [frame render-ctx]` uses `:global-effects` and `:global-ctx`.
  - `get-active-cell-data`, `get-bpm` and `is-playing?` go, with their tests.
- `laser-show.timeline.playback`: `clip-elapsed-ms` (`:49-52`) goes; its only caller was the argument it fed.
- `laser-show.animation.effects`:
  - `apply-effect-chain` has two arities: `[frame chain]` (no modulation time) and `[frame chain timing-ctx]`.
  - It builds `frame-ctx {:point-count n :timing-ctx timing-ctx :mod-ctx (mod/make-context timing-ctx) :base-ctx (mod/make-base-context …)}` once per call.
  - `make-effect-transducer [effect frame-ctx]` hands each effect's transducer `(:time-ms timing-ctx)` and `(:bpm timing-ctx)`. The effects' own `[time-ms bpm params ctx]` signature stays (§9).
  - `make-param-resolver` and `resolve-params-global` use `:mod-ctx` / `:base-ctx` from the frame context when they're there, so callers that pass a bare timing context still work. The keyframe path (`:241-246`) uses `:mod-ctx`.
- `laser-show.backend.multi-engine`: `apply-projector-effects [frame projector-id timing-ctx]` (`:95-112`).
- Tests call `(apply-effect-chain frame chain {:time-ms t :bpm 120.0})` where they passed `t 120.0`. A context without accumulators falls back to `time-ms` at its BPM (`time.clj:108-154`), so they test the same thing.

## 5. State placement

No state changes. `render-ctx` is a local value built and dropped within each tick.

## 6. Events and effects

None.

## 7. Data examples and invariants

| Call today | After |
|---|---|
| `(generate-frames-by-zone cue 1000 120.0 1794905667401 ctx)` | `(generate-frames-by-zone cue ctx render-ctx)` |
| `(apply-effect-chain frame chain 250 120.0 nil ctx)` | `(apply-effect-chain frame chain ctx)` |
| `(apply-effect-chain frame chain 0 120.0)` (tests) | `(apply-effect-chain frame chain {:time-ms 0 :bpm 120.0})` |
| `(apply-effect-chain frame {:effects [static-fx]} 0 120.0)` | `(apply-effect-chain frame {:effects [static-fx]})` |

| Render (c7) | Modulation contexts today | After |
|---|---|---|
| 1 preset, 1 modulated effect | 1 | 1 |
| 4 presets × 3 modulated effects (4 chains) | 16 | 4 |

**Invariants:**
1. For the same timing, every frame is what it was before this phase.
2. One app-state read, one input-store read and one global-effects lookup per tick, whatever the number of cues and zones.
3. One modulation context per `apply-effect-chain` call.
4. No render function takes `elapsed-ms`, `bpm` or `trigger-time`.

## 8. Tests

- `laser-show.animation.effects-test`:
  - `one-context-per-chain-test`: a chain with three modulated parameters makes one `make-context` call;
  - `chain-takes-a-timing-context-test`: the same frames as the old 6-arity with the same context.
- `laser-show.services.show-clock-test/one-state-and-input-read-per-tick-test`: counts `get-raw-state` and `get-all-continuous-values` calls over one tick with three cues on two zones and a global effect.
- These pass unchanged apart from their call sites (invariant 1):
  - Phase 2's `engine-frame-equals-direct-render-test`;
  - `effect_presets_test/presets-change-the-frame-test`;
  - the effect coverage tests (`effects_coverage_test.clj`);
  - `modulation_test`, `frame_service_test`, `zone_routing_integration_test` and the timeline tests.

Run: `clj -M:mac-arm:test effects modulation frame-service show-clock playback zone`, then both full suites. Run probe c7 again and record the before and after render times: the gain is expected to be a few per cent and is *unverified* until then.

## 9. Out of scope / alternatives considered

- **The 18 effects' own `[time-ms bpm params ctx]` signature** (`effects/*.clj`). Each forwards `time-ms` and `bpm` to `make-param-resolver`. Dropping them would change every effect definition and change nothing that's drawn.
- **Renaming the accumulators** (MR1 step 1's rename, also left out of the time base plan, §9 there).
- **Per-point allocation** in the effect chain (review 03 LE1).
- **Caching contexts across ticks.** A context changes every tick, so there's nothing to reuse.
