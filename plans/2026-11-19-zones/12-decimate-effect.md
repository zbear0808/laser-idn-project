# Decimate: an effect that thins a frame to a target fps

**Written:** 2026-11-20 at `c067030` (`master`, Z0–Z3 and ZR in).   **Status:** **Done, on `master`** (`e7b93e4`..`d6fbb15`, fast-forwarded from `3229c95`). The as-built note is §6. The follow-up for Q-D-1 B and Q-D-2 B is §7 (`5adfd4b`..`16a3e10`, on branch `decimate-b`, not merged). Not checked on a real projector. Asked for by the user on 2026-11-20 ("a decimate effect that will reduce point counts by a factor to target a certain fps at a given kpps… default to 25 kpps and 30 fps… try to never mess with anchor blanking points").   **Needs:** Z3's fit guard (`animation/fit_guard.clj`: `budget`, `keep-mask`, `thin`; 05 §13).   **Size:** ~0.5 d. It can be built alongside Z4.

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`. **NEW** marks anything that doesn't exist yet.

## After this you can…

- Add **Decimate** to a cue, an effects-grid cell or a laser's calibration chain. A frame with more points than its budget, `kpps × 1000 / fps`, loses points until it would draw at the target fps. The defaults, 25 kpps and 30 fps, give 833 points.
- It never removes an anchor: a blanked point, the first or last point of a lit line, a dwell point (two points in one place, at a corner or a line's end) or a corner (a turn of more than 45°). A frame that is mostly anchors can stay over its budget; the anchors win.
- With Decimate in a laser's calibration chain, that laser's card (load meter, fps, LOW FPS) counts what Decimate left.

## 1. What exists

| What | Where | Behaviour |
|---|---|---|
| The fit guard | `animation/fit_guard.clj` `guard`, `budget`, `target-points`, `keep-mask`, `thin` (05 §13) | per laser, automatic: below its minimum fps (default 20) it thins, never under 60% of the points, with the same anchors kept, then dims if allowed |
| Effects | `animation/effects.clj` `register-effect!`; namespaces under `animation/effects/` | each effect returns a transducer; a chain is one composed pass (`apply-effect-chain`); `:category` picks the bank tab (`views/components/effect_bank.clj`) and `:calibration` the laser's Add Effect menu (`views/tabs/projectors.clj` `get-calibration-effects`) |
| Where a laser's frame is made | `backend/multi_engine.clj` `show-frame` | compose → `fit-guard/guard` → projector effects → `guard-safety`; the guard's record (`:fit` metadata) feeds the card and LOW FPS |

## 2. Changes

- NEW `:decimate` effect, in NEW `animation/effects/points.clj` (required by `services/frame_service.clj` with the other effect namespaces):
  - **Name:** "Decimate". **Category:** `#{:shape :calibration}`, like Corner Pin, so it's in the bank's Shape tab and in a laser's Add Effect menu. **Timing:** `:static`.
  - **Parameters:**
    - `:kpps`, "Scan rate (kpps)", `:int`, default 25, 1–100;
    - `:fps`, "Target fps", `:int`, default 30, 5–120.

    Both may be modulated: they're resolved once per frame (`effects/resolve-params-global`).
  - Best last in its chain (§5); the effect list's tooltip or hint for it says "Fewer points, keeping blanking, corners and line ends. Put it last." if the editor shows effect descriptions, else nothing.
  - **Budget:** `(fit-guard/budget (* 1000 kpps) fps)`, ⌊kpps·1000 / fps⌋.
  - **Under budget** the frame is unchanged, the same points. **Over budget**, `(fit-guard/thin frame (- n budget))` takes out up to n − budget of the points `keep-mask` doesn't keep, spread evenly along each lit run. Unlike the guard, there's no floor: the target is the user's.
  - It needs the whole frame (corners and run ends look at neighbours), so it's a completing transducer: it buffers the points and emits the kept ones when the pass completes, as `partition-all` does, and passes `reduced` on. Effects after it in a chain get the thinned frame.
- `backend/multi_engine.clj` `show-frame`: after the projector effects, when they changed the point count, the guard's record takes the new count. `:points`, `:guard-fps` and `:reduced-pct` are then counted against `:points-in`, so the card and LOW FPS read what goes to the safety stage. `:budget`, `:min-fps` and `:dim-pct` stay the guard's. Today no projector effect changes the count, so nothing else changes.
- `test/laser_show/animation/effects_coverage_test.clj`:
  - `expected-effect-ids` gains `:decimate`;
  - `param-exemptions` gains `[:decimate :kpps]` and `[:decimate :fps]`: the 6-point test frame is under every budget the limits allow, and `decimate-test` covers them.

## 3. Tests

- NEW `laser-show.animation.effects.points-test`:
  - `decimate-reaches-the-target-fps-test`: a 3,000-point lit circle at the defaults keeps 833 points, so 25,000 / 833 ≥ 30 fps; at 40 kpps, 1,333; at 60 fps, 416;
  - `decimate-keeps-every-anchor-test`: a frame of lit runs with blanked jumps, dwell points and corners. The output is a subsequence of the input, and every point `keep-mask` keeps is still there, in order;
  - `under-budget-is-unchanged-test`: 500 points are left as they are;
  - `anchors-win-over-the-budget-test`: a frame that is only anchors (short dashes) comes back whole, though over budget;
  - `decimate-in-a-chain-test`: `apply-effect-chain` with Scale, Decimate and Translate. The count drops to the budget, and Translate moves the kept points;
  - `modulated-fps-test`: an `:fps` modulator is read once per frame.
- `laser-show.backend.multi-engine-test`: `decimate-in-a-calibration-chain-counts-on-the-card-test` (a laser with Decimate in its chain and a 3,000-point cue: the record's `:points` is 833, `:guard-fps` ≥ 30, so not low).
- UI (`laser-show.ui-test.setup-dialogs-test` or a new namespace): `decimate-is-offered-test`, checking that Decimate is in a laser's Add Effect menu, and in the effect bank's Shape tab.

## 4. Commit plan

Failing tests first in each step.

| # | Commit | Tests |
|---|---|---|
| 1 | `feat(effects): Decimate thins a frame to a target fps at a given scan rate (25 kpps and 30 fps by default), never taking out a blanked point, a line's ends, a dwell point or a corner` | `points-test`, the coverage test |
| 2 | `feat(output): with Decimate in a laser's calibration chain, its card and LOW FPS count what Decimate left` | the multi-engine test, `decimate-is-offered-test` |
| 3 | `plans: Decimate done (<sha>): as built` | – |

## 5. Out of scope

- A per-laser default kpps read from the laser's own scan rate: the effect doesn't know which laser it's on, and a cue reaches several. The default is 25, the app's default scan rate.
- A floor like the guard's 60%: Decimate is asked for by name, and its anchors keep the shape.
- Telling later effects the new count: effects after Decimate in the same chain get the frame context made for the chain, so a point-index modulator after it reads the count from before. The editor's hint says to put Decimate last.
- Replacing the fit guard: the guard stays automatic, per laser, with its floor and its record. Decimate is a tool you place.

## 6. As built (2026-11-20, `e7b93e4`..`d6fbb15`, on `master`)

Built in worktree `wt-decimate` off `master` at `3229c95`, while Z4 was built in its own worktree. `master` hasn't moved, so the branch fast-forwards it. "For the merge" below has a trial merge with Z4's branch as it stood.

### Where it landed

| # | Commit | Step (§4) |
|---|---|---|
| 1 | `e7b93e4` | NEW `animation/effects/points.clj`: `decimate` (pure), `decimate-xf` and the `:decimate` registration; `services/frame_service.clj` requires it; the coverage test's ids and exemptions |
| 2 | `d6fbb15` | NEW `fit-guard/recount`; `multi-engine/show-frame` gives the guard's record the count the projector effects left; ids on a laser's Add Effect menu (`views/tabs/projectors.clj`) |
| 3 | this commit | this note |

### Deviations from the plan

- **The load meter doesn't change.** It is `:points-in` over the guard's `:budget` (`load-warning/load-percent`), and §2 keeps both. So of "After this you can…"'s three, the card's fps, its note ("−72% pts") and LOW FPS count what Decimate left, but the meter still shows what the laser is asked for: 3,000 points at 25 kpps read 240%, red, because the guard acts first (it thins to 1,800, then Decimate to 833). Asked below (Q-D-1).
- **The record's arithmetic is `fit-guard/recount`,** beside `guard`, whose record it counts again: `:points`, `:guard-fps` and `:reduced-pct` for the new count against `:points-in`; `:budget`, `:min-fps`, `:dim-pct` and `:est-fps` stay. The same count gives the same record (identical). A frame left with no point would be `:dark?` with nil fps, as the guard has it, so `smooth` never reads a nil fps. No projector effect empties a lit frame today (Decimate never removes a run's ends), so that case is reached only by its unit test. `show-frame` takes the count inside the safety stage's function, after the projector effects and before `apply-safety`, which stays last and fails closed.
- **No hint.** Neither the effect bank nor the editors show effect descriptions, so by §2 there is none. The advice ("fewer points, keeping blanking, corners and line ends; best last") is in the namespace's docstring.
- **Small rules the plan left open:**
  - `:kpps` and `:fps`, once resolved, are clamped to their ranges (1–100, 5–120), and a value that isn't a finite number takes the default. A modulator can go past a slider's ends, and 0 fps would divide by zero. A modulated value isn't rounded: 27.5 fps gives ⌊25,000 / 27.5⌋ = 909 points.
  - The two parameters are `def`s (`kpps-param`, `fps-param`), so the clamp reads the ranges the sliders show. The other effect namespaces write theirs inline.
  - `points/decimate frame kpps fps` is public, the pure function the transducer calls.
  - The transducer buffers into a transient vector made for each pass. A buffer made with the transducer would keep the points of a pass that failed, and the chain's one-by-one fallback reuses the same transducers.
- **Ids on a laser's Add Effect menu,** for the UI test: `#projector-add-effect-menu`, and `#projector-add-effect-<effect id>` on each item.
- **Tests beyond §3:** `decimate-through-the-one-by-one-fallback-test`, `decimate-passes-reduced-on-test` and `recount-counts-what-is-drawn-test`. `under-budget-is-unchanged-test` also checks 833 points (left alone) and 834 (one goes). The multi-engine test also reads the provider's smoothed record (not low) and checks that a safety limit's crossing points aren't counted (837 points sent, 833 in the record). The UI test also adds Decimate from the menu (25 kpps, 30 fps) and opens its editor (both parameters show).

### Tests added

- `laser-show.animation.effects.points-test` (NEW): the six §3 tests, `decimate-through-the-one-by-one-fallback-test` (an effect that fails 100 points in before Decimate, and one that fails on what Decimate emits: each gives Scale, Decimate and Translate's frame, and only the failing effects are reported; with no error, one effect at a time equals the composed pass) and `decimate-passes-reduced-on-test` (`take` after it and before it).
- `laser-show.animation.effects-coverage-test`: `:decimate` in `expected-effect-ids`; `[:decimate :kpps]` and `[:decimate :fps]` exempt (the 6-point frame is under every budget: 1 kpps at 120 fps is 8 points).
- `laser-show.animation.fit-guard-test`: `recount-counts-what-is-drawn-test`.
- `laser-show.backend.multi-engine-test`: `decimate-in-a-calibration-chain-counts-on-the-card-test`.
- UI, `laser-show.ui-test.setup-dialogs-test`: `decimate-is-offered-test`. The Projectors tab scrolls to the Add Effect menu first: the inspector starts below the rig strip.

### Red first

| Step | Before the change |
|---|---|
| 1 | Neither test namespace could load (no `animation.effects.points`). The coverage test without its new `require`: 3 failures (`all-expected-effects-registered-test` 1, `exemptions-are-current-test` 2). Against a stub that registers Decimate with its parameters and an identity transducer: 15 failures, 0 errors, in 7 of the 8 tests (`decimate-reaches-the-target-fps-test` 4, `decimate-in-a-chain-test` 3, `modulated-fps-test` 4, and the count in each of `decimate-keeps-every-anchor-test`, `under-budget-is-unchanged-test` (834 points), the fallback test and `decimate-passes-reduced-on-test`). `anchors-win-over-the-budget-test` passed against it (identity keeps every point), so a naive stub, every k-th point with anchors ignored, was run too: 14 failures, `anchors-win-over-the-budget-test` and the anchor check of `decimate-keeps-every-anchor-test` among them. The real code with its buffer made with the transducer, not per pass: 1 failure, the fallback test's "fails 100 points in" |
| 2 | `decimate-in-a-calibration-chain-counts-on-the-card-test` against step 1's engine: 6 failures (`:points` 1,800, 13.9 fps, low). `recount-counts-what-is-drawn-test` couldn't compile (no `fit-guard/recount`); against a stub that returns the record: 5 failures. UI: against step 1's views (no ids on the menu), 1 error. With the ids and Decimate not registered (frame-service's `require` taken out for the run): 1 failure ("Decimate" not among Corner Pin, RGB Curves and Axis Transform) and 1 error |

### Verification

| Where | Unit suite (`clj -M:mac-arm:test`) | UI suite (`clj -M:test-ui:mac-arm:test-ui-headless`) |
|---|---|---|
| `3229c95` (`master`, the base), unpacked with `git archive` into a temporary folder | 1,437 tests, 541,736 assertions, 0 failures | 194 tests, 879 assertions, 0 failures: 05 §13's run of the same `src/` and `test/` trees, not run again |
| **`d6fbb15`, the branch's last code** | **1,447 tests, 541,789 assertions, 0 failures** | **195 tests, 884 assertions, 0 failures** |
| the trial merge with `zones-z4` at `c9fe903` (tree `68bdc7a`, below) | 1,525 tests, 542,731 assertions, 0 failures | 207 tests, 992 assertions, 0 failures |

Each step also ran its targeted namespaces: step 1 the two above, then `frame-service-test`, `effects-test`, `fit-guard-test`, `effect-presets-test`, `calibration-test` and `show-clock-test` (85 tests, 8,252 assertions); step 2 `fit-guard-test`, `multi-engine-test`, `output-lifecycle-test`, `streaming-engine-test`, `subs-test`, `load-warning-test` and `points-test` (140 tests, 8,624 assertions), and the UI namespaces whose views it changed, `setup-dialogs-test`, `rig-card-test`, `rig-strip-test` and `safety-editor-test` (42 tests, 126 assertions). `streaming-engine-test/new-tick-goes-out-without-the-minimum-gap-test` passed in every full run. clj-kondo reports no errors or warnings on the touched files. No test wrote under the home folder: `~/LaserShowProjects` doesn't exist.

**Not done: the check on a real projector.** Decimate hasn't been seen on a laser. At 25 and 40 kpps: a 3,000-point cue with Decimate last in a laser's calibration chain (the card reads about 30 fps and isn't amber), then with Decimate last in the cue instead, watching corners and line ends for rounding or flicker.

### For the merge

A trial merge with `git merge-tree --write-tree`, which writes no branch and no worktree. The merged tree was unpacked with `git archive` into a temporary folder, and both suites were run there.

- **Into `master` at `3229c95`:** a fast-forward.
- **With `zones-z4` at `c9fe903`** (Z4 up to its map pads, before its step 14): no conflicts, tree `68bdc7a`, suites as above.
- Files this branch touches:
  - NEW `src/laser_show/animation/effects/points.clj` and `test/laser_show/animation/effects/points_test.clj`;
  - `services/frame_service.clj`: one line in the `ns` form, `[laser-show.animation.effects.points]` between `calibration` and `zone`. Z4 edits the same form higher up, and the render; they merged cleanly;
  - `test/laser_show/animation/effects_coverage_test.clj`: a `require`, `:decimate` on its own line before `:zone-selector` in `expected-effect-ids`, two exemptions at the end of `param-exemptions`. Z4's step 14 (the Zone Selector leaves the bank) may edit lines beside these; a conflict there is about adjacent lines only: keep both sides;
  - `backend/multi_engine.clj`: `show-frame` only (its docstring, a volatile and `fit-guard/recount`);
  - `animation/fit_guard.clj`: NEW `recount`, after `guard`;
  - `views/tabs/projectors.clj`: two `:id`s in `projector-add-effect-menu`;
  - `test/laser_show/animation/fit_guard_test.clj`: one test, after `dark-frames-leave-low-as-it-was-test`;
  - `test/laser_show/backend/multi_engine_test.clj`: a `decimate` def and one test at the end (Z4 edits `:332-372`);
  - `test/laser_show/ui_test/setup_dialogs_test.clj`: three helpers and one test at the end, the `ns` form untouched (Z4 edits `delete-group-asks-and-retargets-test`);
  - this doc.
- Not touched: `views/components/effect_bank.clj` (Decimate reaches the Shape tab by its category alone; Z4's step 14 skips `:hidden?` effects there, which Decimate isn't), `animation/effects.clj`, `00-overview.md` and `10-questions.md`.

### Found on the way

- **Decimate counts the frame it is given.** In a laser's calibration chain that is what the laser draws. In a cue or an effects-grid cell it is one cue's frame for one target (the effects grid runs once on each of a cue's frames), so a laser that draws two such cues gets about twice the budget, and the guard acts. §5 says why (the effect doesn't know which laser it's on); the editor could say so if it ever shows descriptions.
- **A cue's ⚠ still warns** for a laser whose calibration chain decimates the cue to its target: `compose/cue-warnings` counts the cue's points on the laser before the guard and the calibration, as it does for the guard's own thinning (Q-D-2).
- **The preview's laser cell doesn't show Decimate in a calibration chain.** It draws what the laser draws before its calibration (`frame-service/preview-data`), as for Corner Pin. The card's thumbnail shows what was sent.
- **Its cost is the guard's** (Q-Z3-7): 3,000 → 833 points through a chain of one Decimate, median 335 µs, of which 273 µs is `decimate` itself, that is `keep-mask` and `thin`. An 800-point frame under the budget: 23 µs. In a laser's calibration chain over the guard's budget both thin, the guard first (188–206 µs for 2,000 points in 05 §13's probe z2).

### Questions for the user

**Q-D-1 · The load meter with Decimate in a laser's calibration chain.**
- **A ★ (built)** As now: what the laser is asked for over the guard's budget. 3,000 points read 240%, red, while the laser draws 30 fps. Past 100% the guard still acts, which is what the meter shows (Q-Z3-4 A), and the note's "−72% pts" says what was cut.
- **B** What Decimate left over the budget: 833 points read 67%.

**Q-D-2 · A cue's ⚠ for a laser that decimates it.**
- **A ★ (built)** Keep warning: the cue is heavy, and the laser thins it. The warning already ignores the guard.
- **B** Count each laser's calibration chain, which the warning would have to run for every laser.

## 7. Follow-up (Q-D-1 B, Q-D-2 B) (2026-11-20, `5adfd4b`..`16a3e10`, on branch `decimate-b`, not merged)

The user answered both of §6's questions B (`10-questions.md` § Decimate). Built in worktree `wt-decimate-b` off `master` at `cc9e000`, after the commit that records the answers (`6484e62`). `master` has since moved to `4c5fcc8` (Z4 landed). "For the merge" has a trial merge with it.

### Where it landed

| # | Commit | What |
|---|---|---|
| 1 | `5adfd4b` | Q-D-1 B, the meter: `fit-guard/recount` and `smooth`, `output-runtime/load-of`, `load-warning/load-percent` and `meter-tooltip`; docstrings in `output-runtime/status` and `subs/projector-load` |
| 2 | `16a3e10` | Q-D-2 B, the ⚠: NEW `points/chain-budget`; `routing.rig`'s inputs, cache key and `:outputs`; `compose/cue-warnings` |
| 3 | this commit | this note |

The two code commits share no file. Step 1 passes its tests on its own: they were run with step 2's files stashed.

### What changed

**The meter (Q-D-1 B).** With Decimate (or any projector effect that changes the count) in a laser's calibration chain, the card's meter is what the laser draws over the guard's budget. A 3,000-point cue at 25 kpps, with Decimate at 30 fps, reads 833 / 1,250 = **67%**, green, and its tooltip says "833 points a frame. 1,250 fit 20 fps at 25,000 pts/s". The fps (30), the note ("−72% pts", the guard's cut and Decimate's together) and LOW FPS (off) already counted what Decimate left (§6), so all four now describe the same frame.
- `fit-guard/recount`, which `show-frame` calls when the projector effects changed the count, adds `:effect-points`, that count. `:points-in` still means what the guard was given, so `:reduced-pct` and `:est-fps` are as §6 has them.
- `load-warning/load-percent` and `meter-tooltip` count `:effect-points` when it's there, else `:points-in` (`metered`).
- Without such an effect `recount` returns the guard's own record, so neither the record nor the card's `:load` has the key. The meter is then what the laser is asked for, as before (Q-Z3-4 A).

**The ⚠ (Q-D-2 B).** A laser whose calibration chain has an enabled Decimate counts each cue at no more than that Decimate's budget, ⌊kpps·1000 / fps⌋, the smallest if it has several. So a cue the laser decimates to its target gets no ⚠ for it. A 2,000-point cue on two lasers, with Decimate in one of them, warns for the other only; with Decimate in both, it doesn't warn.
- NEW `points/chain-budget items` reads the budget from the chain's params. Nothing runs: the chain isn't applied per cue. Each value is clamped as the effect clamps it (1–100 kpps, 5–120 fps). **A modulated `:kpps` or `:fps` is read at its default** (25, 30), and under an enabled keyframe modulator both are, since its keyframes then set the params. A Decimate in a group counts (`chains/flatten-chain`); a disabled one, or one in a disabled group, doesn't.
- `routing.rig`: `routing-inputs` adds `:projector-effects` (`[:chains :projector-effects]`), and `cached-routing`'s key has it. So adding, changing or removing a laser's Decimate makes a new routing on the next tick, and an edit of a cue's chain doesn't (`[:chains :projector-effects]` keeps its identity). A laser's `:outputs` entry has `:decimate-budget` while its chain has an enabled Decimate, and only then.
- `compose/cue-warnings` counts a cue on such a laser at `(min points decimate-budget)`. The warning's `:points` is that count, so its points and its fps agree. Anchors can leave more than the budget (§1), so this is an estimate, as the warning already is.
- `services/show_clock.clj` is unchanged: it builds the routing with `routing-inputs` and gives it to `cue-warnings`.

### Deviations and small rules

- **`fit-guard/smooth` keeps the meter's count through dark frames,** which the brief didn't list. Decimate can't thin a dark frame (every blanked point is an anchor), so a dark frame is never recounted. During a Strobe the meter would then flick between 67% and 240% as the status (4 times a second) caught a lit frame or a dark one. A dark record now keeps the previous record's `:effect-points`, or its absence, as S-14 keeps the averages and the low flag.
- **`:effect-points` reaches the card exact,** not to the nearest 10 like the other counts (`output-runtime/load-of`). At 830 the meter would read 66%. Decimate's count is its budget, which holds still, so the status doesn't change more often for it.
- **A calibration chain that can't be read gives no budget.** `rig.clj` `decimate-budget` catches, as `halves` does, so a bad chain can't stop the routing for the whole rig; that laser's cues are counted as they are.
- **The warning's `:points` is the count on the laser,** at most the budget. Its `:item` (the heaviest item) keeps the item's own count, which can be the larger ("Found on the way").
- **Not touched:**
  - `backend/multi_engine.clj`: `show-frame` already recounts when the count changes;
  - `views/components/rig_strip.clj`, because the Projectors tab layout pass works there. `load-meter` draws `load-percent` and `meter-tooltip`; its docstring ("the points the laser is asked for") now has the exception that `load-percent`'s docstring states;
  - `services/show_clock.clj`, `00-overview.md` and `10-questions.md`.
- **The cost:** an edit of any laser's calibration chain (dragging a Corner Pin handle, say) now works the routing out again, at most once a tick. Not measured here; 05 §8 (invariant 12) estimates the resolver at tens of µs after an edit.

### Tests added

- `laser-show.animation.fit-guard-test`: `recount-counts-what-is-drawn-test` checks `:effect-points` (833; none in the guard's own record; and once a frame left with no point is smoothed, the meter as it was); NEW `dark-frames-leave-the-meter-as-it-was-test` (lit frames Decimate thinned, every other one dark: 833 throughout; without Decimate a dark frame gets none; a lit frame that wasn't recounted drops it).
- `laser-show.backend.multi-engine-test`: `decimate-in-a-calibration-chain-counts-on-the-card-test` checks `:effect-points` (833) on the record and on the provider's smoothed one, and that a dark 3,000-point frame keeps the meter at 833; `fit-guard-runs-before-projector-effects-test` checks that a chain that keeps the count (identity RGB curves) adds no `:effect-points`.
- `laser-show.backend.output-lifecycle-test`: NEW `status-load-counts-what-decimate-left-test`. Two lasers stream to the fake DAC, one frame as `show-frame` makes it with Decimate, the other without: the card's `:load` is `{… :points 830 :effect-points 833 …}` and `load-percent` 67; without Decimate, no key and 240.
- `laser-show.views.components.load-warning-test`: `load-meter-test` (67% and the tooltip with `:effect-points`; 240% without).
- `laser-show.animation.effects.points-test`: NEW `chain-budget-reads-the-params-test` (the defaults, 40 kpps, 60 fps, several, clamped, modulated, keyframes, groups, disabled, none; and the count Decimate leaves equals the budget).
- `laser-show.routing.rig-test`: NEW `outputs-carry-each-lasers-decimate-budget-test` (833, 416 for two, none for a disabled one, a chain that can't be read or none, and nothing else changes); `cached-routing-follows-an-edit-test` (a laser's calibration chain is an edit; Decimate added, then its fps changed: nil, 833, 416); `cached-routing-is-reused-while-inputs-are-identical-test` (an edit of a cue's chain isn't one).
- `laser-show.routing.compose-test`: NEW `cue-warnings-count-a-decimated-cue-at-its-budget-test` (warned without Decimate, moved to the other laser, gone with both, counted at 1,666 for a target under the minimum, a disabled Decimate counts for nothing, a cue under the budget).
- `laser-show.services.show-clock-test`: NEW `decimate-put-in-a-calibration-chain-moves-the-warning-test` (end to end through the routing cache: Decimate put in p1's chain while the cue plays moves the ⚠ to p2; in both, no ⚠).
- UI, `laser-show.ui-test.rig-card-test`: NEW `decimated-laser-meters-what-it-draws-test` (a 3,000-point circle with Decimate in the laser's chain: "67%", green, "−72% pts", not low).

### Red first

The tests were written first and run against `6484e62`'s code (§6's), the 7 unit namespaces together: 159 tests, 80,114 assertions, **18 failures**, 0 errors. Only the "can't be read" check came with the code (below).

| Test | Before the change |
|---|---|
| `recount-counts-what-is-drawn-test` | 1 failure (no `:effect-points`) |
| `dark-frames-leave-the-meter-as-it-was-test` | 1 (the dark records had none) |
| `decimate-in-a-calibration-chain-counts-on-the-card-test` | 3 (the record, the smoothed record, the dark frame) |
| `status-load-counts-what-decimate-left-test` | 2 (the `:load` without the key; 240 for 67) |
| `load-meter-test` | 2 (240 for 67; "3,000 points a frame") |
| `cue-warnings-count-a-decimated-cue-at-its-budget-test` | 4 (still p1, warned with both, 2,000 points for 1,666, the 900-point cue warned) |
| `outputs-carry-each-lasers-decimate-budget-test` | 1 (no budgets) |
| `cached-routing-follows-an-edit-test` | 2 (the routing reused after Decimate was added; no budget) |
| `decimate-put-in-a-calibration-chain-moves-the-warning-test` | 2 (still p1; still warned with both) |
| `chain-budget-reads-the-params-test` | its namespace couldn't compile (no `points/chain-budget`); against a stub that returns nil, 9 failures |
| `decimated-laser-meters-what-it-draws-test` (UI) | 1 error: timed out waiting for "67%" |

- The "can't be read" laser in `outputs-carry-each-lasers-decimate-budget-test` came with the code. It was seen red by taking out `decimate-budget`'s `try`: 1 error (`IllegalArgumentException: Don't know how to create ISeq from: java.lang.Long`), because the whole routing threw.
- Passing before the change, by design, as checks of what mustn't change:
  - the new check in `fit-guard-runs-before-projector-effects-test`;
  - the "without Decimate" and "lit frame" parts of `dark-frames-leave-the-meter-as-it-was-test`, and "the meter as it was" in `recount-counts-what-is-drawn-test`;
  - "a cue's chain isn't one" in `cached-routing-is-reused-while-inputs-are-identical-test`;
  - the compose test's "without Decimate", its disabled Decimate and the 900-point cue's first check;
  - the laser without Decimate in `status-load-counts-what-decimate-left-test`.

### Verification

| Where | Unit suite (`clj -M:mac-arm:test`) | UI suite (`clj -M:test-ui:mac-arm:test-ui-headless`) |
|---|---|---|
| `d6fbb15` (the base's code: `cc9e000` and `6484e62` only add plans), §6's run | 1,447 tests, 541,789 assertions, 0 failures | 195 tests, 884 assertions, 0 failures |
| **`16a3e10`, the branch's last code** | **1,453 tests, 541,840 assertions, 0 failures, 0 errors** | **196 tests, 888 assertions, 0 failures, 0 errors** |
| the trial merge with `master` `4c5fcc8` (tree `d21ee47`, made before this note; its conflict resolved as below) | 1,528 tests, 542,907 assertions, 0 failures, 0 errors | 212 tests, 1,019 assertions, 0 failures, 0 errors |

`master` itself, the same tree as 06's trial merge, has 1,522 tests and 542,856 assertions, and 211 UI tests and 1,015 assertions (06's numbers, not run again). This branch adds 6 unit tests (51 assertions) and 1 UI test (4 assertions) both on its own and on the merge.

- **Targeted runs while working:**
  - the 8 unit namespaces above after the code: 168 tests, 80,159 assertions, 0 failures;
  - step 1 alone: `fit-guard-test`, `multi-engine-test`, `output-lifecycle-test`, `load-warning-test`, `subs-test` and `streaming-engine-test`, 134 tests, 8,613 assertions, 0 failures;
  - UI: `rig-card-test` (6 tests, 25 assertions), then `cue-load-test`, `rig-strip-test`, `toolbar-test`, `setup-dialogs-test` and `streaming-status-test` (50 tests, 167 assertions), 0 failures.
- `streaming-engine-test/new-tick-goes-out-without-the-minimum-gap-test` passed in every full run.
- clj-kondo reports nothing new on the touched files. Its two warnings (`rig_test.clj:121`, `load_warning_test.clj:7`) and the redundant coercions it notes (`rig.clj` `known-ref?`, `points.clj` `decimate`) were there before.
- No test wrote under the home folder: `~/LaserShowProjects` doesn't exist.

**Not done: the check on a real projector.** At 25 kpps, a 3,000-point cue on a laser with Decimate last in its calibration chain: the card reads 67%, green, about 30 fps, "−72% pts", and the cue's cell shows no ⚠ for that laser. The same cue on a second laser without Decimate: 240%, red, LOW FPS, and the ⚠ names that laser.

### For the merge

`git merge-tree --write-tree master decimate-b` (`master` at `4c5fcc8`, base `cc9e000`) writes no branch and no worktree. **One file conflicts: `src/laser_show/routing/rig.clj`, in two places.** Z4 rewrapped two docstrings that this branch also edits:

1. **`routing-inputs`' docstring.** Keep `master`'s and add `:projector-effects (the lasers' calibration chains, for their Decimate budgets)` to its list.
2. **`routing`'s docstring and argument vector.** Keep `master`'s docstring, add `:decimate-budget` to the `:outputs` shape, and add before "Pure." the sentence on it: ":decimate-budget, only while its calibration chain has an enabled Decimate: that Decimate's budget (points/chain-budget), the most points the cue warnings count a cue at on it (zones plan 12, Q-D-2 B)". The argument vector is `[{:keys [rig projectors zone-groups zone-maps projector-effects] map-id :map}]`.

Everything else in `rig.clj` merges on its own: the `ns` form, `routing-keys`, `routing-inputs`' map, `decimate-budget`, and `routing`'s body, where Z4's `zone-map`, `:lanes` and `:overrides` sit beside this branch's `:outputs`. The trial merge's tree was unpacked with `git archive` into a temporary folder, the two places resolved that way, and both suites run there (above).

- Merged cleanly: `subs.clj` (this branch's docstring is at `projector-load`, away from Z4's subs), `10-questions.md`, `multi_engine_test.clj`, `compose_test.clj`, `rig_test.clj` and `show_clock_test.clj`. `compose.clj` hasn't changed on `master`, and `show_clock.clj` hasn't changed on this branch.
- Files this branch touches:
  - `src/laser_show/animation/fit_guard.clj`: `recount` and `smooth`;
  - `src/laser_show/backend/output_runtime.clj`: `load-of` and `status`' docstring;
  - `src/laser_show/views/components/load_warning.clj`: NEW private `metered`, `load-percent` and `meter-tooltip`;
  - `src/laser_show/subs.clj`: `projector-load`'s docstring;
  - `src/laser_show/animation/effects/points.clj`: the `ns` form (docstring and a `require`) and NEW `chain-budget`;
  - `src/laser_show/routing/rig.clj`: a `require`, `routing-keys`, `routing-inputs`, NEW private `decimate-budget`, `routing`;
  - `src/laser_show/routing/compose.clj`: `cue-warnings`;
  - tests:
    - `fit_guard_test.clj`: `recount-counts-what-is-drawn-test`, and one test after it;
    - `multi_engine_test.clj`: `fit-guard-runs-before-projector-effects-test` and `decimate-in-a-calibration-chain-counts-on-the-card-test`, both outside Z4's `:332-372`;
    - `output_lifecycle_test.clj`: three `require`s, and one test after `status-carries-each-lasers-load-test`;
    - `load_warning_test.clj`: `load-meter-test`;
    - `points_test.clj`: its `require` takes `:as points`, and one test at the end;
    - `rig_test.clj`: a `decimate` helper and one test after `outputs-carry-scan-rate-fit-and-region-fits-test`, and the two `cached-routing-*` tests;
    - `compose_test.clj`: one test at the end;
    - `show_clock_test.clj`: a `decimate` def and one test after `edited-cue-names-its-heaviest-item-test`;
    - `ui_test/rig_card_test.clj`: one test after `thinned-laser-shows-its-cut-test`;
  - this doc: the status line and this section.

### Found on the way

- **The meter when Decimate leaves the count as it is.** When the projector effects don't change the count, nothing is recounted, and the meter shows what the laser is asked for, as without Decimate. That happens in two cases:
  - the guard has already taken the frame under Decimate's budget, which needs Decimate's target under the laser's minimum. At 25 kpps with a minimum of 20 and Decimate at 15 fps (1,666 points), a 2,700-point circle reads 216% (the guard leaves 1,620), and a 2,800-point one reads 133% (Decimate leaves 1,666). A heavier cue reads lower;
  - only anchors are left after the guard (text, dots). Then the meter is what the laser was asked for while the laser draws the guard's count, as without Decimate.

  Asked below (Q-D-3).
- **The note's cut blinks during a Strobe,** with or without Decimate. S-14 keeps a dark frame's own `:reduced-pct` (0), so the status shows "−72% pts" or nothing, depending on which frame it catches. This comes from reading the code; it wasn't seen in the UI, and it isn't changed here.
- **The ⚠'s advice can miss when Decimate sets the count.** With Decimate at its 25 kpps on a 12 kpps laser, the laser draws 833 points at about 14 fps, so the cue warns: "⚠ ~830 points: about 14 fps on Laser C (12 kpps). Most points: Circle (2,000). Simplify it, or lower Laser C's minimum." Simplifying the cue helps only at 600 points or fewer; setting Decimate's scan rate to the laser's 12 kpps clears it (400 points, 30 fps). And the item's 2,000 is more than the warning's 830. Asked below (Q-D-4).

### Questions for the user

**Q-D-3 · The meter when Decimate in a laser's chain leaves the count as it is** (the guard got under Decimate's budget first, or only anchors are left).
- **A ★ (built)** What the laser is asked for, as without Decimate (Q-Z3-4 A). Simple, but a heavier cue can read lower: 2,700 points read 216% and 2,800 read 133% with Decimate at 15 fps under a 20 fps minimum.
- **B** What the laser draws, whenever its chain has an enabled Decimate: the routing knows (`:decimate-budget`), so the meter would always be the count that reaches the safety stage.

**Q-D-4 · The ⚠'s advice when the laser's Decimate sets the count.**
- **A ★ (built)** As for any cue: "Simplify it, or lower Laser C's minimum."
- **B** Name Decimate: "Laser C's Decimate keeps ~830 points: set its scan rate to Laser C's 12 kpps, or lower Laser C's minimum."
