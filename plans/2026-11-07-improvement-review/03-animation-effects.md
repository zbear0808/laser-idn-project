# 03 — Animation & Effects: Improvement Plan

Scope: `src/laser_show/animation/**`, `resources/effect-presets/**`. Context read: `plan/Modulation-Optimization-Plan.md`, `plans/v3/layered-effects-refactor.md`, `plans/TODO/layered-effects-refactor.md`, `docs/STALE_CLOSURES.md`, `docs/CLJFX_STATE_MANAGEMENT.md`, `docs/PROFILING.md`.

## 1. Summary

The effect pipeline is well designed overall. Effects are pure data (`{:effect-id … :params …}`), modulators are serializable config maps, and an effect chain becomes one composed transducer that runs over `[x y r g b]` vectors. Global modulators are resolved once per frame, and per-point modulators have "compiled" fast paths. The weak spots are in **timing semantics and evaluator correctness**. Several modulator settings that users can see in the UI do nothing or behave wrongly: the `:seconds` unit in loop mode, all decay settings, the random modulator, once-mode inside real cues, and the blackout effect. These bugs survive because the tests drive evaluators with hand-built contexts that don't match what `frame_service` really passes in. Performance is acceptable but not allocation-free, despite what `Modulation-Optimization-Plan.md` §1 says. Every effect allocates a new 5-vector per point, calls `u/clamp` with boxed values, and boxes values on every resolver call. Some per-frame work is also rebuilt from scratch each frame: default params, RGB-curve LUTs, and spatial-keyframe tables. The shipped `resources/effect-presets/*.edn` have no loader, and 2 of the 8 point at an effect that doesn't exist.

**About the existing plans.** `plans/v3/layered-effects-refactor.md` and `plans/TODO/layered-effects-refactor.md` are generic. They name namespaces that don't exist (`laser-show.effects.layered`, a `.cljs` UI, `clojure.spec`) and don't match this code. I recommend marking both as superseded by this plan. Their one concrete goal, "no lazy seqs in the hot path", is covered by finding 15 below. The `Modulation-Optimization-Plan.md` checklist (MIDI double-normalization, OSC path, input store) appears to be implemented: `eval-midi` no longer divides by 127, and `frame_service` reads `input-store/get-all-continuous-values`. Nothing here repeats it.

## 2. Findings

1. **Once-mode modulators never progress inside cues (High).** `cue_timing.clj:169-191` (`get-cue-timing-context`) sets `:time-ms` to `accumulated-ms`, which is *relative* to the trigger. It sets `:trigger-time` to the wall-clock ms from `grid.clj:35-37` (`System/currentTimeMillis`), which is *absolute*. `effects.clj:236-239`, `:394-398` and `:405` all do `(merge {:time-ms … :trigger-time …} timing-ctx)`, so the timing-ctx values win. `time.clj:153-162` (`calculate-once-progress`) then computes `elapsed = accumulated-ms − wall-clock ≈ −1.7e12`, which clamps progress to 0. Result: every `:loop-mode :once` wave and once-mode keyframe stays stuck at its start value. For global effects (`frame_service.clj:473-477`, `:trigger-time 0`), "once" counts from app start. Tests (`modulation_test.clj:310-492`, `reproduction_test.clj:62`) always use `trigger-time 0` with absolute `time-ms`, so they never hit the real combination.
2. **`:time-unit :seconds` is ignored in loop mode (High).** `time.clj:234-237` (loop branch of `calculate-modulator-phase`) always uses beats. The `time-unit` argument passed from `modulator_evaluators.clj:27-28` is dropped. The same bug exists for time-driven keyframes at `keyframes.clj:52-63`. The UI shows "Unit: beats/seconds" for every wave modulator (`modulators.clj:26`), and the preset README says `:seconds` is "Real-time, independent of BPM".
3. **The random modulator is effectively constant (High).** `modulator_evaluators.clj:154-165` seeds a new `java.util.Random` with consecutive integers and takes the first `nextDouble`. With `java.util.Random`, consecutive seeds give almost identical first outputs. I checked: seeds 0–7 give 0.7306–0.7311. So "random" stays at about 73% of the range and barely moves. It also allocates a `Random` on every evaluation.
4. **Decay settings do nothing (High).** The UI params in `modulators.clj:51-57` are `:duration`, `:time-unit` and `:decay-curve` (`:linear :exp :log`). The evaluator `eval-exp-decay` (`modulator_evaluators.clj:134-148`) reads `:decay-type`, which no param sets, so it always takes the linear branch. It ignores `:duration` and `:time-unit`, has no `:log` case, and uses `fmod beats 1.0`. So it is a per-beat ramp, even though it is registered `:retrigger? true` (`modulators.clj:153-161`).
5. **Blackout never turns on (High).** The param is defined as `:key :enabled` (`effects/intensity.clj:47`), but the transducer reads `(:enabled? resolved)` (`intensity.clj:36`). Because the effect resolves to an identity stage, the bug is invisible.
6. **`update-point-xy` clamps after every effect, which breaks chaining (Med-High).** `types.clj:107-113` clamps x/y to [-1,1] inside every shape effect. Chains that leave the visible area and come back are destroyed. For example, scale 2.0 → scale 0.5 squashes the shape onto a box, and translate +1.5 → rotate pushes points onto the border. Clamping out-of-range points also leaves *lit* points on the frame edge (a bright border line) instead of blanking them. Colors have the same problem through `update-point-rgb` (`types.clj:115-122`): intensity 2.0 → intensity 0.5 doesn't return the original color.
7. **NaN is never caught (Med).** `u/clamp` (`common/util.clj:13-27`) lets NaN through because both comparisons are false. NaN can come from trace-fade with `tail-length` modulated to 0 (`intensity.clj:159`, `(/ distance tail-length)`), `period` → 0, or keyframe ranges. Nothing between the effects and IDN output catches it.
8. **The modulator dropdown shows the internal `:unified-keyframe-param` (Med).** The docstring of `modulator_registry.clj:126-131` (`all-ui-modulators`) says it filters out `:internal`/`:external`, but the code doesn't filter. `modulator_param_control.clj:53` uses it, so users can pick `:unified-keyframe-param`, which always evaluates to 0.0 (`modulator_evaluators.clj:601-602`).
9. **Bad config types throw and spam the log every frame (Med).** `compile-unified-keyframe-param` builds `double-array`s from keyframe params (`modulator_evaluators.clj:555-557`), so a boolean, choice or color param throws `ClassCastException`. The `try` at `effects.clj:252-258` catches it, logs `log/error` **every frame**, and quietly replaces the effect with `(map identity)`. The same happens for any malformed modulator config. There is no rate limiting and no visible state for the UI.
10. **Calibration effects that throw fail open (Med, safety-adjacent).** When a projector calibration effect (corner-pin, axis-transform, rgb-curves) throws, `effects.clj:252-258` turns it into identity. `multi_engine.clj:110-115` also falls back to the *uncalibrated* frame on any exception. An uncorrected (unmirrored, un-pinned) frame can then project outside the intended area. For the `:calibration` category, failing closed (blank frame) is safer.
11. **Spatial keyframes and RGB-curve LUTs are rebuilt every frame (Med, perf).** `effects.clj:180-193` builds new synthetic configs every frame. That means `compile-unified-keyframe-param` sorts keyframes and allocates 2 arrays plus a vector **per param, per effect, per frame** (`modulator_evaluators.clj:517-560`). `rgb-curves-xf` (`calibration.clj:18-27`) builds 3×256-entry Catmull-Rom LUTs per projector per frame, with a linear segment search for each entry (`curves.clj:86-95`).
12. **Default merging runs on every evaluation (Med, perf).** `effects.clj:133-144` (`get-default-params`/`merge-with-defaults`) runs `map-into` over the parameter vector for every effect, every frame, and again for every zone, since `frame_service` calls `apply-effect-chain` per item/zone. `modulation.clj:164-182` (`get-defaults-for-type` + `merge`) does the same for **every modulator evaluation**, and per point on the interpreter path. Both values are static once registered.
13. **Per-point hot path allocates and boxes (Med, perf).** Each of N effects:
    - allocates a new persistent 5-vector holding 5 boxed `Double`s per point (`types.clj:110-122`);
    - calls the multi-arity, nil-checking, boxed `u/clamp` five times per point;
    - calls resolvers through untyped `IFn.invoke(Object,Object,Object)`, which boxes x, y and idx and unboxes the result, even though the compiled resolvers are `^double [^double ^double ^long]` prim fns (`modulator_evaluators.clj:412`, `:562`; call sites like `shape.clj:16-17`);
    - uses `map-indexed`, which boxes idx through a volatile.

    Color effects add 2–4 more tuple vectors per point through destructuring (`color.clj:25-27`, `:198-202`; `colors.clj:58-103`). The `util.clj:17` docstring says "use clamp-double instead", but `clamp-double` doesn't exist. So `Modulation-Optimization-Plan.md` §1.4 ("allocation-free") is not accurate.
14. **The interpreter fallback allocates per point (Low-Med, perf).** `modulation.clj:103-113` (`with-point-context`) does `(merge base-ctx (u/->map x y point-index))`, creating 2 maps per point per param. It only runs for per-point types with no compiler, which is none today. Any new per-point modulator without a compiler would quietly hit this path.
15. **Composition overhead on every frame (Low, perf).** `effects.clj:274-281` composes with lazy `keep` + `apply comp`, and identity stages stay in the chain: the zone selector (`effects/zone.clj:35-41`), blackout-off (`intensity.clj:40`), and errored effects. Each identity stage still costs a `map` step per point.
16. **Timing logic is duplicated and not nil-safe (Low-Med).** `update-cue-timing` (`cue_timing.clj:120-166`) repeats `update-global-clock` (`:84-118`) but uses raw `(- phase-offset phase-offset-target)` and `(+ accumulated-beats …)` without `or` defaults, so a partial cue-timing map (old saved state, tests) throws an NPE in the frame loop. `align-global-clock-phase` and `align-cue-timing-phase` (`:32-58`) are copies of each other.
17. **Spatial math and keyframe search are written 3–4 times (Low, maintainability).**
    - Spatial position: `keyframes.clj:65-95`, `modulator_evaluators.clj:279-299`, `:524-549`, plus the `compile-*` fns.
    - `find-surrounding-keyframes-clamped`: `keyframes.clj:181-215` and `modulator_evaluators.clj:301-324`.
    - `spatial-driver?` set: `keyframes.clj:38-41`.
    - Parameter vectors are copied into `modulators.clj:17-95` "to avoid circular dependency" ("copied from modulator-defs"), but `modulator_defs.clj` no longer defines them.

    These copies can drift apart. For example, `:point-index` handles `pc<=1` slightly differently in each copy.
18. **The `:input` modulator is half-built (Low).** It has an evaluator (`modulator_evaluators.clj:201-210`, map `:386`) and params (`modulators.clj:77-83`), but it is never registered. So `modulator-config?` is false, and a `{:type :input}` param is passed raw into the effect math (`ClassCastException` → finding 9). Its default `:source-key` is the string `"[:midi 1 1]"`, but the lookup expects a vector key.
19. **MIDI channel numbering is ambiguous (Low).** The UI channel range is 1–16 (default 1, `modulators.clj:66`). `eval-midi` (`modulator_evaluators.clj:177-184`) cross-falls back 1↔0, so a modulator on ch 0/1 can respond to the other channel's CC. This needs one canonical base, agreed with the input area.
20. **Effect presets are orphaned and partly broken (Med for the preset format).**
    - No code loads `resources/effect-presets/` (grep finds no references in `src/` or `test/`).
    - `x-position-oscillator.edn` and `y-position-oscillator.edn` use `:effect-id :offset` with `:x-offset`/`:y-offset`. The real effect is `:translate` with `:x`/`:y` (`shape.clj:61-81`), and `make-effect-transducer` drops unknown ids silently (`effects.clj:215`).
    - Preset effects have no `:id` UUIDs, which chain operations expect (`chains.clj:106-135`).
    - There is no format version.
    - The README says effect IDs are in `effects.clj` (they're in `effects/*.clj`) and doesn't list `trace-fade.edn`.
21. **Parameter schema is inconsistent (Low).**
    - Boolean type is `:bool` in some places (`intensity.clj:49`, `calibration.clj:86-94`, the `register-effect!` docstring at `effects.clj:89`) and `:boolean` in others (`shape.clj:41`, `modulators.clj:89`).
    - Scale's `:uniform?` param is never read by `scale-xf` (`shape.clj:9-20`).
    - The effect `:timing` key is required (`effects.clj:99`) but nothing reads it.
    - Docstrings mention `t/update-point-all`, which doesn't exist (`effects.clj:26`, `:166`).
22. **The animation layer reads global state (Low, layering).** `effects.clj:36-37`, `:303-304`: the 3-arity `apply-effect-chain` calls `state/get-raw-state` to get BPM. The animation namespaces should stay pure. All production callers already pass BPM.
23. **Preset generation runs every frame and can't take modulated params (Low-Med).** `frame_service.clj:257-264` regenerates each preset each frame with the same params. `generators.clj:235-242` goes through `(apply generator-fn (into [] cat params))` kwargs. If a preset param ever holds a modulator map (which `presets.clj:21-26` hints at: "enables per-channel modulator support"), `(double {...})` throws.
24. **The diagnostics capture API is dead code (Low).** `modulation_diagnostics.clj:27-32`, `:121-135`: `enable-capture!` sets flags, but nothing in the render path writes `:captured-frames`.
25. **Test gaps (Med).**
    - No tests for blackout, threshold, trace-fade, rgb-curves, axis-transform, corner-pin, lens-distortion, translate, rotation or oklab-hue-shift. `effects_test.clj` only exercises `:set-color`, `:set-hue`, `:hue-shift` and `:scale`.
    - Tests use `:effect-id :rotate`, which isn't registered (`effects_test.clj:14`, `chains_test.clj:24`), so they pass without testing anything.
    - No test runs modulators through `get-cue-timing-context` (finding 1).
    - No test checks seconds-unit looping, randomness spread, decay params, or that each registered param key is actually read by its effect.
    - No micro-benchmark for the per-point path.

## 3. Proposed improvements

### Quick wins

**QW1 — Fix blackout key** (S, risk Low). In `effects/intensity.clj:36`, read `:enabled` (or rename the param to `:enabled?` and migrate saved data; renaming matches the `?` convention but needs a migration). Add a test that blackout with `{:enabled true}` produces all-zero RGB.

**QW2 — Fix the random modulator** (S, risk Low). Replace `java.util.Random` in `modulator_evaluators.clj:154-165` with a primitive integer hash such as SplitMix64: `(let [z (* (+ seed 0x9E3779B97F4A7C15) …)] …)` → `(/ (double (>>> z 11)) 9007199254740992.0)`. This removes the allocation. Add a test that 64 consecutive steps cover ≥ 0.8 of the [min,max] range.

**QW3 — Honour `:time-unit :seconds` in loop mode** (S, risk Med, since it changes playback of saved shows that set seconds but heard beats). In `time.clj:234-237`, when `time-unit = :seconds`, compute `(/ (:accumulated-ms ctx (or time-ms 0)) 1000.0)` instead of beats. Apply the same change to `keyframes.clj:52-63`. Tests: at 60 BPM and 120 BPM, a 1-second-period sine gives the same value at accumulated-ms = 250.

**QW4 — Make decay match its UI** (S-M, risk Low). Rewrite `eval-exp-decay` to read `:duration`, `:time-unit` and `:decay-curve` (`:linear`/`:exp`/`:log`), with progress measured from cue trigger (`accumulated-beats`/`-ms`) and clamped at 1, matching `:retrigger? true`. Rename the evaluator key from `:exp-decay` to `:decay` to match the registered id.

**QW5 — Filter internal modulators from the UI** (S, risk Low). In `modulator_registry.clj:126-131`, add `(remove (comp #{:internal} :category))`, as the docstring already says. Decide with the views area whether `:external` (MIDI/OSC) should stay visible. They're in use today, so the docstring is the part that's wrong for those.

**QW6 — Repair the effect presets** (S, risk Low). Change the x/y oscillator `.edn` files to `:translate` with `:x`/`:y`. Add `:format-version 1`. Fix the README paths and list `trace-fade`. Add a test that loads every `resources/effect-presets/*.edn` and checks that each `:effect-id` is registered and each `:params` key is in that effect's `:parameters`.

**QW7 — Log unknown effect ids once** (S, risk Low). `effects.clj:215` silently returns nil for unknown effects. Add a `log/warn` deduplicated per id (a `defonce` set of ids already warned about).

**QW8 — Clean up schema and docs** (S, risk Low). Pick `:boolean` and update `intensity.clj:49` and `calibration.clj:86-94` (check `views` param-controls dispatch first). Remove the `t/update-point-all` and `clamp-double` mentions. Either implement `:uniform?` in `scale-xf` or remove it. Fix the tests that use `:rotate` so they use `:rotation`.

**QW9 — Delete or wire up dead code** (S, risk Low). Either delete the `modulation_diagnostics` capture half or add one `when (:enabled? @!diagnostic-state)` hook in `make-effect-transducer`. Either register `:input` with a vector `:source-key` default or delete `eval-input`/`generic-input-params`. Remove the 3-arity `apply-effect-chain` and the `state` requires from `effects.clj`.

### Medium refactors

**MR1 — One canonical time base for modulators** (M, risk Med). Fixes findings 1 and 16.
1. In `cue_timing/get-cue-timing-context` and `get-global-timing-context`, define the context contract as *relative* time: `:elapsed-ms` (= accumulated-ms), `:elapsed-beats`, `:effective-beats`, and `:bpm`. Drop `:trigger-time`/`:time-ms` from the timing ctx.
2. Change `time/calculate-once-progress` and `calculate-once-mode-phase-data` to use `:elapsed-ms`/`:elapsed-beats` directly. Then no subtraction is needed, and once-mode in `:beats` follows BPM changes correctly (which wall-clock ms doesn't do today).
3. Replace the three `(merge {:time-ms …} timing-ctx)` sites in `effects.clj` with a single `effects/build-mod-context` built **once per `apply-effect-chain`** and stored in `frame-ctx`. That also removes N `make-context` calls per frame.
4. Make `update-cue-timing` nil-safe and share one `advance-accumulators` helper with `update-global-clock`. Merge the two `align-*-phase` fns into one.
5. Tests: drive `apply-effect-chain` with a context from `create-cue-timing-state` → `update-cue-timing` ×N → `get-cue-timing-context`, and assert once-mode sine/sawtooth/keyframe progress. Touches `cue_timing.clj`, `time.clj`, `effects.clj`, `keyframes.clj`, `modulator_evaluators.clj` and their tests, plus the call sites in `frame_service.clj` and `multi_engine.clj` (output-pipeline area).

**MR2 — Precompute registry data at registration** (S-M, risk Low). Fixes finding 12.
- Store `:defaults` (a map) on each effect def in `register-effect!` and on each modulator in `register-modulator!`.
- `merge-with-defaults` becomes `(merge (:defaults def) params)`.
- `evaluate-modulator` and `compile-per-point-modulator` read `(:defaults mod)`.
- Optionally collapse `get-evaluator`/`get-compiler`/`per-point?` into one registry lookup that returns the full modulator map.

Files: `effects.clj`, `modulation.clj`, `modulator_registry.clj`.

**MR3 — Cache compiled per-frame artifacts** (M, risk Low). Fixes finding 11.
- Add a small bounded identity/value cache (e.g. `clojure.core.cache` LRU, or a `defonce` `java.util.WeakHashMap`, since configs are persistent maps and equality is cheap).
- Key `compile-unified-keyframe-param` on `[keyframe-mod param-key point-count]`.
- Key `curves/generate-curve-lut` on `points`, and return a `double-array` instead of a vector so `aget` replaces boxed `nth` at `calibration.clj:35-37`.
- Use `Math/round` for the LUT index, or interpolate between entries (finding: `long` truncation at `calibration.clj:31-33` biases colors down).

**MR4 — Error policy for effects** (M, risk Med). Fixes findings 9 and 10.
- Validate/coerce params before compiling. Non-numeric keyframe params fall back to step behaviour instead of `double-array` throwing.
- Replace the per-frame `log/error` with a rate-limited logger keyed by effect instance id, and record failures in a lock-free `!effect-errors` atom so the UI can show a badge (coordinate with views/events; don't push this into the cljfx root atom every frame, see `CLJFX_STATE_MANAGEMENT.md`).
- For effects whose category includes `:calibration`, return a "blank all points" transducer on error instead of identity. Coordinate the same fail-closed behaviour in `multi_engine.clj:110-115` (output area).

**MR5 — Clamp/blank once, at the end of the chain** (M, risk Med; visual behaviour change). Fixes findings 6 and 7.
- Add unclamped `t/with-xy` and `t/with-rgb`, and use them in all effects.
- Add one final stage in `apply-effect-chain`: replace NaN/Inf with a blanked point, clamp RGB to [0,1], and **blank** (rather than clamp) points whose x/y are out of range, keeping their clamped position so the beam stays on the edge.
- Keep `update-point-xy`/`-rgb` as deprecated aliases for one release.
- Add a property-style test that scale(2)→scale(0.5) and translate(+1.5)→translate(−1.5) return the input frame.
- Review calibration ordering, since corner-pin may legitimately need input outside [-1,1].

**MR6 — Unify spatial and keyframe helpers** (M, risk Low). Fixes finding 17.
- Create `laser-show.animation.spatial` with `^double position-for-driver` and a primitive `find-segment` over `double[]` positions.
- Use it from `keyframes.clj`, the `eval-*`/`compile-*` per-point fns, and `compile-unified-keyframe-param`.
- Move the parameter-def vectors into `modulator_defs.clj` (it already depends only on the registry) and delete the copies in `modulators.clj`.
- Delete the interpreter-only `eval-unified-keyframe-param` path, or keep it only as a test oracle for the compiled path.

**MR7 — Effect preset loader and format** (M, risk Low). Fixes finding 20.
- Add `laser-show.animation.effect-presets` with `list-presets` (reads `io/resource` `effect-presets/` entries), `load-preset` (`edn/read-string`, version check), and `instantiate` (assigns fresh `:id` UUIDs via `chains/deep-copy-items` and `merge`s defaults).
- Reuse the QW6 validation as `validate-preset`.
- UI wiring belongs to views/events. This area only provides the pure API.

### Larger efforts

**LE1 — Low-allocation per-point execution** (L, risk Med). Fixes findings 13–15. Do this only after a benchmark shows it matters (see LE3).
1. Define a resolver protocol of `clojure.lang.IFn$DDLD`. Wrap constants in `(fn ^double [^double _ ^double _ ^long _] c)` so every resolver is a prim fn. Type-hint call sites (`(.invokePrim ^IFn$DDLD get-x px py idx)`) to remove boxing.
2. Change the effect contract from "returns a transducer over vectors" to "returns a point kernel" that mutates `double[5]` scratch: `(fn [^doubles p ^long idx] …)`. Add a `kernel->xf` adapter so old effects keep working.
3. `apply-effect-chain` runs all kernels per point over a single scratch array and creates **one** output vector per point at the end. Use a plain `loop` with a primitive index instead of `map-indexed`.
4. Color kernels write HSV/Oklab into scratch locals instead of returning tuple vectors. Add primitive versions of `colors/normalized->hsv` etc. that write into a `double[3]`.
5. Drop identity stages: `make-effect-transducer` returns nil for zone-selector, blackout-off and errored non-calibration effects.

Files: `types.clj`, `effects.clj`, all of `effects/*.clj`, `colors.clj`, `modulator_evaluators.clj`. The point format at the module boundary stays `[x y r g b]`, so other areas are unaffected.

**LE2 — Preset (shape) frame caching and modulated preset params** (M-L, risk Low-Med). Fixes finding 23.
- Memoize `presets/generate-frame-with-params` on `[preset-id params]` (bounded LRU), since most cues render the same static shape every frame.
- If modulated preset params are wanted, resolve them with `mod/resolve-params` before generation (global modulators only; per-point makes no sense for generators). Only memoize when the resolved params are unchanged.
- Replace the kwargs `apply` with map-arg generators.

Touches `presets.clj` and `generators.clj`, and the call site at `frame_service.clj:257-264` (output area).

**LE3 — Animation test and benchmark suite** (M, risk Low). Fixes finding 25.
- Add one table-driven test namespace that runs **every registered effect** with defaults and with a sine modulator on each numeric param. Assert no exception, the output has the same count as the input, all values are finite and in range, and every registered `:parameters` key changes the output for at least one value (this would have caught blackout and `:uniform?`).
- Add evaluator tests for decay params, random spread, and seconds-mode looping.
- Add a criterium (or `timing/nanotime`) benchmark: 1,000 points × a 6-effect chain (scale + rotation + hue-shift + intensity + trace-fade + rgb-curves), reporting ns/frame and allocated bytes/frame (via `ThreadMXBean.getThreadAllocatedBytes`). It goes in the dev alias so LE1/MR3 can be measured. Coordinate with the testing/build area for alias and CI placement.

## 4. Suggested order of work

1. **Correctness quick wins first** (each is independent, about one day total): QW1 blackout → QW2 random → QW5 UI filter → QW6 presets + validation test → QW7 → QW8 → QW9.
2. **Start LE3 tests and the benchmark early**, before the refactors, as a safety net and baseline. They catch regressions in QW3/QW4/MR1.
3. **QW3 + QW4 + MR1 together**, since they all touch the time/phase code in `time.clj` and `modulator_evaluators.clj`. Do MR1 first so seconds-mode and decay are built on the new `:elapsed-ms`/`:elapsed-beats` contract.
4. **MR2 → MR3** (cheap performance gains; measure against the LE3 baseline).
5. **MR4 error policy**, then **MR5 end-of-chain clamp/blank** (MR5 changes visuals, so release it with a note).
6. **MR6 dedupe** before **LE1**, since LE1 rewrites the same per-point code.
7. **MR7 preset loader** and **LE2** whenever the views area is ready to expose them.
8. **LE1** only if the benchmark shows the per-point path dominates frame time in `frame-profiler` stats.

**Dependencies on other areas**
- **Output pipeline (IDN/backend/routing):**
  - MR1 changes what `frame_service.clj:621-627`, `:473-477` and `multi_engine.clj:175,193` pass as timing-ctx/trigger-time.
  - MR4 needs `multi_engine.clj:110-115` to fail closed for calibration.
  - MR5 overlaps with any output-stage clamping/NaN handling. Agree on one owner so clamping isn't done twice.
  - LE2 touches `render-preset-instance`.
  - The output area should also stop re-running global effects per zone (`frame_service.clj` docstring at `:483-488`) or share the composed transducer between zones.
- **Input/triggers:** the MIDI channel base (finding 19) and the `:input` modulator source-key format (finding 18). `grid.clj:35-43` trigger-time semantics change with MR1.
- **Views/UI:** QW5 (modulator dropdown), the QW8 `:bool`/`:boolean` dispatch in param controls, the MR4 error badge, MR7 preset browser, and the scale `:uniform?` decision.
- **Events/state:** QW1 if `:enabled` is renamed (saved-project migration); MR4 error store (must not go through the cljfx root atom every frame); MR1 if old saved `:active-cues` timing maps must stay loadable.
- **Testing/build/tooling:** the LE3 benchmark alias and CI integration; removing the vacuous `:rotate` tests.
