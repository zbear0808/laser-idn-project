# Phase 4 — One timing context down the render

**Written:** 2026-11-17   **Written for commit:** `f7fdd79` (`master`)
**Status:** Done 2026-11-18 (`378db47`; as built in §10)   **Last verified against:** `378db47` (§10; §1, §3 and §4 cite `7d67aff`, the rest is as written for `f7fdd79`)
**Supersedes / Superseded by:** none. Takes over review 03 MR1 step 3, which `plans/2026-11-17-modulator-time-base.md` §9 left open   **Related findings:** S-5 (rest), S-7 (`00-overview.md` §2); review 04 #23 (rest), #25 (deprecated helpers); overview T4 ("a single relative timing context down the effect chain")

Since the modulator time base (`803140d`), every modulator reads its time from the timing context. But the render still threads `elapsed-ms`, `bpm` and `trigger-time` through about fifteen functions and every effect chain. Those values lose to the context's own wherever they meet (`effects.clj:243-245,389,443-446,453`).

Each modulated parameter also builds a modulation context of its own: 16 per render of the busy cue, at least 6 % of the render (c7). On top of that, each render step reads app state and the input store again (S-5).

This phase passes one timing context per cue and one render context per tick, and builds one modulation context per effect chain. About 0.5–1 day, with no decision needed.

## 1. Current behaviour (file:line)

| What | Where | Today (at `7d67aff`) |
|---|---|---|
| Render arguments | `render-preset-instance` (`frame_service.clj:145-152`, `_elapsed-ms` unused), `apply-item-effects` (`:171-187`), `concatenate-frames` (`:205-229`, `_elapsed-ms` unused), `render-item-tree-by-zone` (`:282-352`), `apply-global-effects-to-frame` (`:354-372`), `merge-zone-maps` (`:374-389`), `render-track-tree` (`:391-410`), `generate-frames-by-zone` (`:412-474`); `apply-projector-effects` (`multi_engine.clj:94-111`, called at `:150-151`) | Each takes some of `elapsed-ms bpm trigger-time timing-ctx`. The show clock passes `(long (:accumulated-ms timing))`, the cue's tempo and 0 (`show_clock.clj:53-54`); clips get `playback/clip-elapsed-ms` (`frame_service.clj:452`, `playback.clj:49-52`) |
| Dead code | `render-item-with-effects` (`frame_service.clj:231-263`) and `generate-frame-from-cue-chain` (`:265-280`): the second has no caller since zone routing (`a2043c2`), and the first only serves it | They still take all four arguments |
| The effect chain | `apply-effect-chain` (`effects.clj:325-366`) has arities from `[frame chain time-ms]` to `[frame chain time-ms bpm trigger-time timing-ctx]` (`:343-349`); the 3-arity reads the BPM from app state (`:344`). Then `effect-transducers` (`:264-272`), `compose-effect-transducers` (`:279-294`), `apply-effects-one-by-one` (`:314-319`), `make-effect-transducer` (`:202-262`, which ignores `trigger-time`), and each effect's `(xf-fn time-ms bpm params frame-ctx)` (`:259`) | The only real input is `timing-ctx`: every resolver merges `{:time-ms :bpm}` under it, so its own `:time-ms` and `:bpm` win. `time-ms` matters only in trace-fade's fallback for contexts without accumulators (`intensity.clj:117-123`) |
| Modulation contexts | `make-param-resolver` builds one per modulated parameter (`effects.clj:453`, and the per-point base at `:442-446`); `resolve-params-global` (`:389`); the keyframe path (`:241-246`). Each is `(mod/make-context (merge {:time-ms … :bpm …} timing-ctx))` (`modulation.clj:42-71`, `make-base-context` `:73-101`) | c7: 1 per render for the simple cue (3 % of it), 16 for the busy cue (5 %, at `7d67aff`, 03 §10) |
| Reads per tick | `get-active-global-effects` (`frame_service.clj:106-124`) reads app state (`:112`) for every zone of every cue (called at `:358`), and once more for the profiler's effect count (`show_clock.clj:65`). `apply-global-effects-to-frame` reads the input store for every zone of every cue (`frame_service.clj:361`), after the clock read it once for the cues (`show_clock.clj:77`) | One tick can mix several app states and several input values (review 04 #23). The tick's own `get-raw-state` (`show_clock.clj:135`) is the one read that's needed |
| Global effects' timing | `apply-global-effects-to-frame` builds `get-global-timing-context` at the BPM it is given (`frame_service.clj:362-363`), which is the cue's tempo (`:470`) | A timeline with a locked tempo runs the global effects at its tempo. That is the behaviour to keep (`00-overview.md` §7) |
| Deprecated helpers | `get-active-cell-data` (`:59-72`), `get-bpm` (`:74-77`), `is-playing?` (`:79-82`) | Only tests call them (`frame_service_test.clj:63-125`) |
| Call sites of `apply-effect-chain` | `src`: `multi_engine.clj:104`, `frame_service.clj:181,366`. Tests: `effects_test.clj` (13), `effect_presets_test.clj:96`, `effects/calibration_test.clj:15`, `modulation_test.clj:613`, `multi_engine_test.clj:344`; probes: output runtime p8/p9 (`output_probes_test.clj:324-340`) | – |

## 2. Root cause

The timing context arrived after the render API was already threading wall-clock arguments. Nobody removed the old arguments. Contexts and state reads are built where each value is used, not once where the frame starts.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `get-cue-timing-context` (`cue_timing.clj:187-212`), `get-global-timing-context` (`:215-243`), `playback/clip-timing-ctx` (`playback.clj:28-47`) | The one timing context per cue, per clip and for the global effects, as today |
| `mod/make-context`, `make-base-context`, `with-point-context` (`modulation.clj:42-113`) | Called at most once per chain instead of once per parameter |
| `get-active-global-effects` (`frame_service.clj:106-124`) | Called once per tick by the clock, with the tick's state |
| Phase 2's `engine-frame-equals-direct-render-test`, `presets-change-the-frame-test` (`effect_presets_test.clj:89-102`), and the coverage test that every effect parameter changes the output (`0153d5c`) | They pin the frames, so this refactor can't change any |

## 4. Changes per namespace

- `laser-show.services.show-clock`: `render` (`show_clock.clj:70-88`) builds a render context once per tick, with NEW `frame-service/render-context [state input-values global-clock]`:
  - `:input-values`: the tick's one `input-store/get-all-continuous-values`;
  - `:global-effects`: `get-active-global-effects` of the tick's state, or nil;
  - `:global-ctx`: the global clock's timing context with the input values. Each cue's render sets its own tempo on it, so the global effects keep running at the cue's tempo (§1).
  - `render-cue` passes it to `generate-frames-by-zone`; `record-render!` counts `(:global-effects render-ctx)`. The projector context keeps the tick's input values.
- `laser-show.services.frame-service`. The render functions take `timing-ctx` (and the render context where they need it) instead of `elapsed-ms bpm trigger-time`:
  - `generate-frames-by-zone [cue-chain timing-ctx]` and `[cue-chain timing-ctx render-ctx]` (`:412-474`). The 2-arity builds the render context from app state, the input store and the show clock as it calls, as today's per-zone reads do, for tests and tools. The cue's tempo is `(:bpm timing-ctx)`, also for `clip-timing-ctx` (`:450`).
  - `render-item-tree-by-zone [item parent-zone-id timing-ctx tracks default-zone-id]`, `apply-item-effects [frame item timing-ctx]`, `render-track-tree [tracks clip-maps-by-track timing-ctx]`, `merge-zone-maps [zone-maps]`, `concatenate-frames [frames]`, `render-preset-instance [item]`.
  - `apply-global-effects-to-frame [frame global-effects global-ctx]`; `get-active-global-effects [state]`.
  - The dead `render-item-with-effects` and `generate-frame-from-cue-chain` go. So do `get-active-cell-data`, `get-bpm` and `is-playing?`, with their tests.
- `laser-show.timeline.playback`: `clip-elapsed-ms` (`:49-52`) goes; its only caller was the argument it fed.
- `laser-show.animation.effects`:
  - `apply-effect-chain` has two arities: `[frame chain]` (no modulation time) and `[frame chain timing-ctx]`. The effects get `(:time-ms timing-ctx)` and `(:bpm timing-ctx)` as their time and tempo, which is what their resolvers used anyway.
  - It builds `frame-ctx {:point-count n :timing-ctx timing-ctx :mod-ctx (delay (mod/make-context …)) :base-ctx (delay (mod/make-base-context …))}` once per call. The delays build each context at most once per chain, and only when a modulated parameter needs it: a chain with only static parameters builds none.
  - `make-effect-transducer [effect frame-ctx]`, `effect-transducers` and `compose-effect-transducers [chain frame-ctx]`, `apply-effects-one-by-one [frame chain frame-ctx]`. The effects' own `[time-ms bpm params ctx]` signature stays (§9).
  - `make-param-resolver` and `resolve-params-global` keep their signatures and use frame-ctx's contexts when they're there, so a caller that passes a bare timing context, or a frame-ctx made by hand, still works. The keyframe path (`:241-246`) uses `:mod-ctx`.
- `laser-show.backend.multi-engine`: `apply-projector-effects [frame projector-id timing-ctx]` (`:94-111`). `combine-zone-frames` calls `concatenate-frames` without the 0 (`:88`).
- Tests call `(apply-effect-chain frame chain {:time-ms t :bpm 120.0})` where they passed `t 120.0`, and `(merge {:time-ms t :bpm b} ctx)` where they passed both. A context without accumulators falls back to `time-ms` at its BPM (`time.clj:143-154`), so they test the same thing.

## 5. State placement

No state changes. `render-ctx` is a local value built and dropped within each tick.

## 6. Events and effects

None.

## 7. Data examples and invariants

| Call today | After |
|---|---|
| `(generate-frames-by-zone cue 1000 120.0 0 ctx)` (the clock) | `(generate-frames-by-zone cue ctx render-ctx)` |
| `(generate-frames-by-zone cue 1000 120.0 0 ctx)` (tests) | `(generate-frames-by-zone cue ctx)` |
| `(apply-effect-chain frame chain 250 120.0 0 ctx)` | `(apply-effect-chain frame chain ctx)` |
| `(apply-effect-chain frame chain 0 120.0)` (tests) | `(apply-effect-chain frame chain {:time-ms 0 :bpm 120.0})` |
| `(apply-effect-chain frame {:effects [static-fx]} 0 120.0)` | `(apply-effect-chain frame {:effects [static-fx]})` |

| Render (c7) | Modulation contexts today | After |
|---|---|---|
| 1 preset, 1 modulated effect | 1 | 1 |
| 4 presets × 3 modulated effects (4 chains) | 16 | 4 |
| a chain whose parameters are all static | 0 | 0 |

**Invariants:**
1. For the same timing, every frame is what it was before this phase.
2. One app-state read, one input-store read and one global-effects lookup per tick, whatever the number of cues and zones.
3. At most one modulation context and one per-point base context per `apply-effect-chain` call.
4. No render function takes `elapsed-ms`, `bpm` or `trigger-time`.

## 8. Tests

- `laser-show.animation.effects-test`:
  - `one-context-per-chain-test`: a chain with three modulated parameters makes one `make-context` call, and a chain of static ones none;
  - `chain-takes-a-timing-context-test`: the frames of a modulated chain at a timing context, against the same effects applied one by one through `make-param-resolver` with a bare context (the old way of building a context per parameter).
  - `chain-without-errors-is-unchanged-test` (`:413-431`) calls `compose-effect-transducers` and `apply-effects-one-by-one` with a frame context.
- `laser-show.services.show-clock-test/one-state-and-input-read-per-tick-test`: counts `get-raw-state` and `get-all-continuous-values` calls over one tick with three cues on two zones and a global effect.
- These pass unchanged apart from their call sites (invariant 1):
  - Phase 2's `engine-frame-equals-direct-render-test`;
  - `effect_presets_test/presets-change-the-frame-test`;
  - the effect coverage tests (`effects_coverage_test.clj`);
  - `modulation_test`, `frame_service_test`, `frame_concatenation_test`, `reproduction_test`, `zone_routing_integration_test`, `keyframe_integration_test` and the timeline tests.
- Gone: `frame_service_test`'s `is-playing-test` and its three `get-active-cell-data-*` tests, and `playback_test`'s `clip-elapsed-ms` assertion.
- The probes that render (c2, c3, c7 here; p8 and p9 in the output runtime's) move to the new calls.

Run: `clj -M:mac-arm:test effects modulation frame-service show-clock playback zone`, then both full suites. Run probe c7 again and record the before and after render times: the gain is expected to be a few per cent and is *unverified* until then.

## 9. Out of scope / alternatives considered

- **The 18 effects' own `[time-ms bpm params ctx]` signature** (`effects/*.clj`). Each forwards `time-ms` and `bpm` to `make-param-resolver`. Dropping them would change every effect definition and change nothing that's drawn.
- **Renaming the accumulators** (MR1 step 1's rename, also left out of the time base plan, §9 there).
- **Per-point allocation** in the effect chain (review 03 LE1).
- **Caching contexts across ticks.** A context changes every tick, so there's nothing to reuse.

---

## 10. As built (2026-11-18)

Committed as `378db47`, on top of `936f49c` (Phase 3's as-built docs). Before building, §1 was re-checked against `7d67aff`, and §3, §4, §7 and §8 were refined: what the render context holds and who builds it, the global effects keeping the cue's tempo, the two arities of `generate-frames-by-zone`, the dead render path, the delays that make a chain's contexts on first use, and the tests. §2, §5, §6 and §9 are as written for `f7fdd79`.

**Where it is now** (at `378db47`)
- `animation/effects.clj`: NEW `timing-of` `:200`, `mod-context` `:206`, `base-context` `:215` and `frame-context` `:222`. `make-effect-transducer [effect frame-ctx]` `:233`, whose keyframe path takes the chain's context (`:273`); `effect-transducers` `:291` and `compose-effect-transducers` `:306` take `[chain frame-ctx]`, and `apply-effects-one-by-one` `:338` takes `[frame chain frame-ctx]`. `apply-effect-chain` `:349` has `[frame chain]` and `[frame chain timing-ctx]`. `resolve-params-global` `:389` and `make-param-resolver` `:417` keep their signatures and use the chain's contexts. The old 3-arity's read of the BPM from app state is gone, and with it the requires of `state.core` and `state.extractors`.
- `services/frame_service.clj`: `get-active-global-effects [state]` `:83`; NEW `render-context [state input-values global-clock]` `:102`; `render-preset-instance [item]` `:135`; `apply-item-effects [frame item timing-ctx]` `:161`; `concatenate-frames [frames]` `:195`; `render-item-tree-by-zone [item parent-zone-id timing-ctx tracks default-zone-id]` `:219`; `apply-global-effects-to-frame [frame global-effects global-ctx]` `:288`; `merge-zone-maps [zone-maps]` `:302`; `render-track-tree [tracks clip-maps-by-track timing-ctx]` `:319`; `generate-frames-by-zone` `:338`, with `[cue-chain timing-ctx]` and `[cue-chain timing-ctx render-ctx]`. `get-active-cell-data`, `get-bpm`, `is-playing?`, `render-item-with-effects` and `generate-frame-from-cue-chain` are gone (497 → 428 lines).
- `services/show_clock.clj`: `render-cue` `:48` passes the tick's render context; `record-render!` `:62` counts its global effects; `render [state global-clock runs]` `:71` builds it once per tick, from the runtime's global clock (`tick!` `:137`, the call at `:152`).
- `backend/multi_engine.clj`: `combine-zone-frames` `:75`; `apply-projector-effects [frame projector-id timing-ctx]` `:94`, called with the snapshot's projector context at `:149`.
- `timeline/playback.clj`: `clip-elapsed-ms` is gone.

**Differences from the plan above**

| Plan | As built | Why |
|---|---|---|
| `resolve-params-global` uses the frame context's contexts when they're there (§4) | It also makes none when no parameter is a modulator | The chain's context is made by the first resolver that asks for it. `blackout`, `rgb-curves` and `axis-transform` resolve through `resolve-params-global`, which always made one, so §7's "a chain whose parameters are all static: 0" held only for the effects that resolve through `make-param-resolver`. Now it holds for every effect: a projector's static RGB curve no longer makes a context for every frame |
| "A context without accumulators falls back to `time-ms` at its BPM (`time.clj:143-154`), so they test the same thing" (§4) | Only trace fade falls back, because it reads the bare timing context (`intensity.clj:115-123`). `mod/make-context` defaults the accumulators to 0 (`modulation.clj:66-67`), so a modulator in such a context is at beat 0 whatever its `time-ms` | The tests still test the same thing: each resolver used to merge `{:time-ms :bpm}` under the context, and the tests now pass the context that merge made |
| The probes that render move to the new calls (§8) | c3 and c7 render with a render context made once, as the clock makes one per tick. c2 needed no change: it wraps `generate-frames-by-zone` with a function of any arity | c3 and c7 measure a cue's render in the clock, which no longer reads app state |
| – | NEW probe c9 (`probes/same_frames_probes_test.clj`): the same scene's frames in two checkouts | Invariant 1 over the whole render, not only the paths the tests pin |

**Tests**
- `effects-test`:
  - NEW `one-context-per-chain-test`. Three modulated parameters in two effects make 1 context. Two per-point parameters on the interpreter path make 1 base context; the test turns compiling off (`mod/compile-per-point-modulator` returns nil), since every registered per-point modulator compiles. A static chain whose `blackout` resolves through `resolve-params-global` makes none.
  - NEW `chain-takes-a-timing-context-test`. The chain's one context gives the frame that a context per parameter gives (the effects' own transducers, run with a bare frame context), and a quarter beat later another frame. Trace fade without accumulators reads the context's `:time-ms` and `:bpm`, and the 2-arity applies a static chain.
  - `chain-without-errors-is-unchanged-test` calls `compose-effect-transducers` and `apply-effects-one-by-one` with a frame context. The other call sites pass `{:time-ms 0 :bpm 120.0}`, or nothing for static chains.
- `show-clock-test`: NEW `one-state-and-input-read-per-tick-test`: three cues on two zones and a global effect, one tick, 1 app-state read and 1 input-store read. It counts reads on its own thread, and stubs `events/dispatch!`, which without the app runs the tick's events inline, and they read app state themselves.
- `frame-service-test` loses `is-playing-test` and the three `get-active-cell-data-*` tests, and `playback-test` its `clip-elapsed-ms` assertion. The other tests that render (`frame-concatenation`, `zone-routing-integration`, `reproduction`, `timeline-handlers`, `modulation`, `effect-presets`, `calibration`, `multi-engine`) changed only their calls.
- Red first: the new and changed tests against the old code gave 7 failures and 3 errors in 4 tests:
  - `one-context-per-chain-test`: 3 contexts instead of 1, 2 base contexts instead of 1, and 1 context for the static chain (blackout's `resolve-params-global`);
  - `chain-takes-a-timing-context-test`: the old 3-arity took the context for `time-ms`, so the modulators read beat 0. A quarter beat later gave the same frame, and trace fade got no BPM and ran at time 0. The 2-arity didn't exist;
  - `chain-without-errors-is-unchanged-test`: `compose-effect-transducers` had no 2-arity;
  - `one-state-and-input-read-per-tick-test`: `{:app-state 5 :input-store 4}`. That is the tick's read, one per zone frame for the global effects (3) and one for the profiler's effect count; and the clock's input read and one per zone frame.
- The new tests passed the first time they ran against the new code.

**Invariant 1, the same frames** (probe c9; the check was a scratch file first, then became this probe): a scene of three cues played 4 s of ticks on a fake clock at `936f49c` and on the new code. It kept every 7th tick's cue frames by zone, one projector's frame, the preview and each cue rendered again directly. The scene: grid cues with sine, triangle, MIDI and per-point modulators, trace fade in beats and in seconds, a group effect and a time keyframe modulator; a timeline at a locked 90 BPM with a track effect and a once-mode modulator; two global effects, one of them on MIDI; and RGB curves, corner pin and a modulated translate on the projector. The 35 ticks (12,075 points in cue frames, 35 projector frames, a new frame for every cue on every tick) were byte-identical, in the scratch run and again with the probe.

**Probes** (before = `936f49c`, after = `378db47`). c3 and c7 ran in two rounds, alternating a checkout of `936f49c` and the new code, at load averages 2.7 and 3.8. The two runs at `936f49c` before building (load 2.7–3.3) and the full probe run after (load 3.9) fall in the same ranges.

| Probe | Before | After |
|---|---|---|
| c7, 4 presets × 3 modulated effects | **16 contexts per render**, 9.5 and 8.1 µs of the render inside them (6 % both times) | **4**, 2.3 and 2.1 µs (2 %). The full run after: 4, 2.2 µs (1 %) |
| c7, 1 preset, 1 modulated effect | 1 context, 0.8 and 0.7 µs (5 % and 4 %) | 1, 1.2 and 0.8 µs (7 % and 4 %): unchanged |
| c3, 4 presets × 3 effects (346 points) | medians 160 and 159 µs (p95 247 and 242) | 148 and 136 µs (p95 232 and 402). The full run after: 154 µs (p95 264) |
| c3, 1 preset, 1 effect (65 points) | 38 and 36 µs | 36 and 35 µs |
| c9 (NEW) | 35 ticks, 12,075 points in cue frames, 35 projector frames | byte-identical |
| c1, c2, c4–c6, c8; output runtime p8, p9 | as in 03 §10 | the same: c1 120 distinct ticks in 120 frames; c2 60–60.5 renders a second with 0 to 4 projectors; c4 6 writes a second; c5 0 times backwards; c6 60 distinct frames in the second after a reload; c8 90 µs and 1.1 KB per redraw; p8 a failing calibration passes the frame through; p9 a blanked point stays blanked |

The busy cue's render saves about 6 µs inside the contexts, about 4 % of it, as §8 expected. Its medians fell by 7 and 14 % in the two rounds, but they vary by about as much between runs, so only the time inside the contexts is a firm number.

**Verification, 2026-11-18, macOS arm64, JDK 25.0.5, headless, load average 1.5–3.9**

| Check | Result |
|---|---|
| `clj -M:mac-arm:test effects modulation frame-service frame-concatenation show-clock playback zone multi-engine timeline-handlers reproduction effect-presets calibration` | 231 tests, 1,625 assertions, 0 failures |
| `clj -M:mac-arm:test` (full unit suite) | 866 tests, 32,463 assertions, 0 failures (867 / 32,462 at `936f49c`) |
| `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.preview-test -n laser-show.ui-test.timeline-editor-test -n laser-show.ui-test.streaming-status-test` | 35 tests, 144 assertions, 0 failures |
| `clj -M:test-ui:mac-arm:test-ui-headless` (full UI suite) | 61 tests, 222 assertions, 0 failures, as at `936f49c` |
| The same with `-J-Dlaser-show.dev=true` | 61 tests, 222 assertions, 0 failures. One lost-update WARN, the same one as at `7d67aff` (03 §10): `cue-chain-list-test` writes app state while its own drag events are queued |
| `clj -M:mac-arm:test-timing engine-wire output-lifecycle show-clock` | 5 tests, 25 assertions, 0 failures in both of 2 runs |
| `clj -M:mac-arm:test-integration` | 17 tests, 57 assertions, 0 failures |
| `clj -M:kondo --lint` on the files changed | 21 files: 0 errors; the 2 warnings `reproduction_test.clj` had before (unused requires). Over `src test dev`: 8 errors and 22 warnings, as at `936f49c` |

The full unit suite has 4 tests fewer and 3 more. Its assertions are 2 more from this phase (8 gone with the removed tests, 10 new), and 1 fewer from `engine-wire-test`. Counted per namespace, `engine-wire-test` had 125 assertions in one checkout and 126 in the other: `config-repeats-within-250ms-test` asserts once for each frame the engine sent before it stopped.

Observed, not changed:
- Each projector's engine still reads app state on its own thread, once per tick for its zone groups (`multi_engine.clj:132`) and again for its projector effects (`:101`). So a projector's frame joins the tick's cue frames with the projector effects of the app state at send time. Invariant 2 is about the tick's render; these reads are outside it (review 04 #23, the engines' part).
- The global effects still run once for each zone frame of each cue. Each run is an effect chain with its own context, at its cue's tempo.

Not testable here: a real DAC. Nothing on screen changes in this phase, so there was no look at the running app.
