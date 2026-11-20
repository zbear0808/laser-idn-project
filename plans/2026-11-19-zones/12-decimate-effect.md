# Decimate: an effect that thins a frame to a target fps

**Written:** 2026-11-20 at `c067030` (`master`, Z0–Z3 and ZR in).   **Status:** Proposed; asked for by the user on 2026-11-20 ("a decimate effect that will reduce point counts by a factor to target a certain fps at a given kpps… default to 25 kpps and 30 fps… try to never mess with anchor blanking points").   **Needs:** Z3's fit guard (`animation/fit_guard.clj`: `budget`, `keep-mask`, `thin`; 05 §13).   **Size:** ~0.5 d. It can be built alongside Z4.

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
