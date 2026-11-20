# Decimate: an effect that thins a frame to a target fps

**Written:** 2026-11-20 at `c067030` (`master`, Z0–Z3 and ZR in).   **Status:** **Done, on `master`** (`e7b93e4`..`d6fbb15`, fast-forwarded from `3229c95`). The as-built note is §6. Not checked on a real projector. Asked for by the user on 2026-11-20 ("a decimate effect that will reduce point counts by a factor to target a certain fps at a given kpps… default to 25 kpps and 30 fps… try to never mess with anchor blanking points").   **Needs:** Z3's fit guard (`animation/fit_guard.clj`: `budget`, `keep-mask`, `thin`; 05 §13).   **Size:** ~0.5 d. It can be built alongside Z4.

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
