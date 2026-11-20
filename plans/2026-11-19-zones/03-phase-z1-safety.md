# Phase Z1: safety zones, the test pattern and Identify

**Written:** 2026-11-19   **Written for commit:** `d298801` (`master`). **Re-check every path and line at HEAD before starting.**
**Status:** Proposed. Not to be built yet (D-8).   **Needs:** nothing. It goes well with Z0, whose confirm dialog it reuses (02 §4); without Z0 it brings that dialog itself. It can be built alongside Z2.   **Size:** ~4–5 d.
**Implements:** spec §10 (`01-model-and-routing-spec.md`) and D-28 (edits on a live laser) and D-29 (fail closed). It also gives Z3 what its doc assumes (05 §1, "Z1 row"): `[:projectors id :safety :limit]`, a stage after the projector effects that reports under `[:safety id]`, and a test-pattern branch that replaces a laser's content.

## After this phase you can…

- **Draw where each laser may shine.**
  - Drag the **scan limit** (a rectangle).
  - Add **blocked areas** (rectangles where light never goes: the audience's heads, a balcony, a camera).
  - Lines that cross an edge are cut at the edge, and the part outside stays dark.
  - No cue, effect, calibration or (later) zone setting can get past it.
- **See it on the real laser while you edit.** **Show test pattern** draws an outline of the limit, the blocked areas and a grid, at low brightness, through the laser's calibration.
- **Find a laser.** **Identify** flashes its test pattern for 3 seconds.
- **Copy a laser's safety zone to others, or mirror it** to the other side.
- **Trust what happens when it breaks.** If the safety step ever fails, that laser goes dark and its card says "Safety error: output off". The others go on.
- **Edit safely while the laser is live.** Making the area smaller applies at once. Making it bigger asks "Allow light in a larger area?" first.

## 1. Current behaviour (file:line, at `d298801`)

| What | Where | Behaviour |
|---|---|---|
| Safety zones | – | **None** (B-1). The Projectors subtitle claims them (`views/tabs/projectors.clj:800`; Z0 removes the claim) |
| A projector's frame | `backend/multi_engine.clj:119-150` | Each cue's zone frames, joined, then `apply-projector-effects` (`:94-111`: RGB curves, corner pin, axis transform; a failing effect is skipped, `animation/effects.clj:349-385`). Nil while nothing plays (`:131`) |
| Before sending | `backend/streaming_engine.clj:366-378` | `frame-rules/sanitize-frame` (`idn/frame_rules.clj:19-31`): it drops non-finite points, puts a dark copy in front of a lit first point, and doubles a lone point. The encoder clamps to ±1 |
| Errors | `backend/output_errors.clj:42-75`; `backend/output_runtime.clj:172-181` | A key such as `[:provider id]` is reported. The status shows the first message among `[:engine id] [:provider id] [:projector-effects id]` |
| Calibration mode | `events/handlers/projector.clj:373-386`; `views/tabs/projectors.clj:641-682`; `state/domains.clj` `projector-ui` (`:calibrating-projector-id`, `:calibration-brightness` 0.1) | **Start Calibration** and the brightness slider (0.05–0.5) set state, and **nothing reads it**. `services/frame_service.clj:25-28` describes a test pattern that doesn't exist |
| Identify | – | None |
| The show clock | `services/show_clock.clj:149-170` | Ticks at 60 Hz whether or not anything plays, and publishes `:playing?` |
| Engines | `backend/output_runtime.clj:41-50` | Exist only while streaming is on, for enabled projectors with a host. Projector settings other than the fingerprint (host, port, service, output config, scan rate) are read live, with no restart |
| Opening a show | `state/persistent.clj:162-174` | Replaces `[:projectors]` with the show's. So after Z1, **the show's safety zones replace this machine's** until ZR (09) splits the rig out. See R-Z1-3 |
| A canvas editor to copy | `views/components/visual_editors/canvas_interaction.clj:1-210` (`interactive-canvas`: drag state, hover, preview value, an undo transaction per drag); used by the corner pin editor (`views/tabs/projectors.clj:557-575`) | The safety editor is built on it |
| Points | `animation/types.clj:59-133` | `[x y r g b]`, positions −1…1, dark when r = g = b = 0 (`blanked?`, `:94-102`) |

## 2. Why

Nothing limits where light goes. The corner pin warps the picture but never clips it, and everything downstream assumes the content is safe. The calibration button promises a test pattern that nobody draws. Every later phase (split lasers, patterns, maps) moves light around in new ways, so the limit has to come first and has to be last in line.

## 3. Reuse / extend

| Existing thing | How it's reused |
|---|---|
| `projector-frame` (`backend/multi_engine.clj:119-150`) | The safety stage goes after `apply-projector-effects` (`:148-150`), and the test-pattern branch goes before the `:playing?` check (`:131`) |
| `output-errors/report!`, `ok!` and the status lookup (`backend/output_runtime.clj:173`) | The key `[:safety id]` is added to the lookup, so the card shows the error |
| `canvas_interaction/interactive-canvas` | The safety editor's canvas: drag edges and corners, one undo step per drag |
| `[:projector-ui :calibrating-projector-id]`, `:calibration-brightness`, `:projectors/toggle-calibration`, `:projectors/set-calibration-brightness` (`events/handlers/projector.clj:373-386`) | Kept as they are, now read by the engine. The button text becomes "Show test pattern" / "Hide test pattern", and the ids `#projector-calibration-button` and `#calibration-brightness-slider` stay (UX tests use them) |
| The `:time` co-effect (`events/core.clj:88-91, 503-505`) | Identify's end time |
| Z0's confirm dialog (`views/dialogs/confirm.clj`, 02 §4) | "Allow light in a larger area?" |
| The seeded-random test style (no `test.check` in `deps.edn`) | The property tests loop over seeded random frames and zones |

## 4. Changes per namespace

### NEW `laser-show.animation.safety` (pure)

It sits beside Z3's `animation.fit-guard`: pure frame-to-frame functions, no state.

| Function | Behaviour |
|---|---|
| `default-safety` | `{:limit [-1.0 -1.0 1.0 1.0] :blocked []}` |
| `normalize [s]` | Fills in the defaults. Orders each rectangle's corners and clamps it to −1…1. The limit is at least 0.05 wide and high. Blocked areas are clipped to the limit, and those smaller than 0.01 are dropped. **At most 8 blocked areas** |
| `identity? [s]` | The full limit and no blocked areas. `apply-safety` then returns the very same frame (`identical?`), so a laser with no safety zone costs nothing and sends exactly what it sends today |
| `inside? [s x y]` | Inside the limit (edges count as inside) and not in any blocked area (edges count as blocked) |
| `intervals [s p q]` | The parts `[[t0 t1] …]` of the segment p→q (t from 0 to 1) that are allowed. The limit is clipped with Liang–Barsky, then each blocked area's interval is subtracted. The parts are sorted, don't overlap, and those shorter than 1e-6 are dropped. A zero-length segment (p = q, a dwell) is `[[0 1]]` if `inside?`, else `[]` |
| `apply-safety [s frame]` | The algorithm below |
| `widens? [old new]` | True when the new allowed area isn't inside the old one: the new limit pokes out of the old one, or an old blocked area is neither inside some new blocked area nor wholly outside the new limit. This is conservative: splitting one blocked area in two counts as widening |
| `mirror-x [s]` | Each rectangle `[x0 y0 x1 y1]` becomes `[(- x1) y0 (- x0) y1]` |

**`apply-safety`.** In laser frames, a point's colour is the colour of the travel *to* that point from the one before. So for each input point q (with p, the point before it):

1. A non-finite q is **dropped**. That's the same rule `sanitize-frame` applies later, but here it runs before the maths.
2. **q dark:** emit q dark, **moved into the limit** (`clamp`), so the mirrors never point outside the limit, even dark.
3. **q lit:** take `I = intervals(p, q)`. For the first point there is no p, so `p = q`.
   - If `I = [[0 1]]`, emit q as it is. This is the common case.
   - Otherwise, for each `[a b]` in I:
     - if `a > 0`, emit **dark** points at `s(a)` (1 + `edge-dwell` of them), so the beam travels there dark;
     - emit q's colour at `s(b)`;
     - if `b < 1`, emit one **dark** point at `s(b)`, so the beam turns off right at the edge.

     After the last part, if it ends before 1, or if I is empty, emit q **dark and clamped**.
   - `s(t)` is the point along p→q at t.
4. `edge-dwell` is 1 by default (a constant in v1, not a setting).

The cost is `O(n · (k + 1))` for n points and k ≤ 8 blocked areas. With no blocked areas and every point inside, there is one limit test per point and no allocation beyond the output vector. `concatenate-frames`' blanking jumps are dark, so they are only clamped.

### NEW `laser-show.animation.test-pattern` (pure)

- `content-layer [brightness]`: a 5 × 5 grid of lines across −0.9…0.9 plus a small circle in the middle, about 400 points, white at `brightness`. It goes **through the projector effects**, so you see the corner pin.
- `safety-layer [s brightness]`: the limit's outline, plus each blocked area's outline drawn **0.01 outside** that area (so it isn't blanked), as dashes. About 150 points per rectangle. It is drawn in **output** space, after the projector effects, so it shows where the limit really is.
- `identify-on? [now-ms until-ms]`: lit for 250 ms, dark for 250 ms, until `until-ms`.

### Existing namespaces

- `laser-show.backend.multi-engine`, `projector-frame` (`:119-150`):

  ```clojure
  (let [s       (safety/normalize (get-in raw [:projectors id :safety]))
        pattern (test-pattern-mode raw id now-ms)]   ; :calibrate, :identify-on, :identify-off or nil
    (cond
      (get-in raw [:vibe-runtime :blackout?]) nil                     ; BLACKOUT wins over everything
      pattern (guard-safety id s (join (apply-projector-effects (content-layer b) id ctx)
                                       (safety-layer s b)))           ; even with nothing playing
      playing? (some->> (composed-frame …)                            ; as today (Z3 changes this part)
                        (apply-projector-effects id ctx)
                        (guard-safety id s))))
  ```

  - `guard-safety` runs `apply-safety` inside `try`.
    - On an exception it reports `[:safety id]` and returns an **empty frame** with the tick metadata, so the engine sends a void frame and counts the tick.
    - On success it calls `ok!`.
  - `:identify-off` gives an empty frame (the flashing).
  - `test-pattern-mode` reads `[:projector-ui :calibrating-projector-id]` and `[:projector-ui :identify id]`.
  - Each pattern frame carries the snapshot's `{:tick :at-ns}` metadata, as content frames do.
- `laser-show.backend.output-runtime`, `engine-status` (`:173`): the error lookup becomes `[:engine :provider :projector-effects :safety]`, and a `:safety` error's text is "Safety error: output off · <message>".
- `laser-show.events.handlers.projector`:
  - **`:projectors/set-safety {:projector-id :safety :confirmed?}`** (NEW). It normalises the value.
    - If the laser is **outputting** (below), `(safety/widens? old new)`, and the event isn't `:confirmed?`, it opens the confirm dialog with `:confirm-event (assoc event :confirmed? true)` and **changes nothing**.
    - Otherwise it writes `[:projectors id :safety]`.
  - **`:projectors/add-blocked-area {:projector-id}`** adds a 0.4 × 0.2 rectangle in the middle of the limit. That shrinks the area, so it applies at once.
  - **`:projectors/remove-blocked-area {:projector-id :index}`** goes through the same check as `set-safety` (removing an area widens).
  - **`:projectors/copy-safety {:from :to [ids] :mirror? :confirmed?}`**: one confirm for every target it would widen while live ("Allow light in a larger area on Laser R and Laser C?"), then one undo step.
  - **`:projectors/identify {:projector-id :time}`** sets `[:projector-ui :identify id]` to `(+ time 3000)`. Pressing it again restarts the 3 seconds.
  - `toggle-calibration` (`:373-379`) is unchanged.
  - **Outputting** means `[:backend :streaming :running?]` is true and the laser's status `:state` is `:streaming` or `:starting` (`backend/output_runtime.clj:183-207`). An off or disabled laser can be edited freely.
- `laser-show.state.domains`:
  - The projectors docstring (`:276-301`) gains `:safety`.
  - `projector-ui` gains `:identify {:default {}}`. This is UI state, never saved.
- `laser-show.views.tabs.projectors`:
  - A NEW **SAFETY ZONE** section in the config panel (`:684-787`; in Z2's inspector after Z2), above the calibration controls.
  - The calibration controls (`:641-682`) are renamed "TEST PATTERN" and gain an **Identify** button (`#projector-identify-button`).
  - The subtitle (`:800`) says "Scan for IDN devices • Configure color calibration and safety zones" again.
- NEW `laser-show.views.components.safety-editor`: the canvas (§7) on `interactive-canvas`, the number fields, and the buttons. A drag that only shrinks the area dispatches `set-safety` while it moves. A drag that widens it, while the laser is outputting, shows a **dashed** preview and dispatches once on release, which triggers the confirm. Cancel snaps the rectangle back.
- `laser-show.services.frame-service`: the docstring's "Calibration Mode" paragraph (`:25-28`) now points at `animation.test-pattern` and `multi-engine`.

## 5. State placement

| Path | Lives in | Written by | Undo? | Unsaved? |
|---|---|---|---|---|
| `[:projectors id :safety]` | document (`hardware.edn`, until ZR moves it to the rig file) | the safety events | yes, one step per event or drag | yes (after ZR: the rig saves itself, 09) |
| `[:projector-ui :calibrating-projector-id]`, `:calibration-brightness` | UI (exists) | the existing events | no | no |
| `[:projector-ui :identify id]` → until-ms | UI, NEW | `:projectors/identify` | no | no |
| the `[:safety id]` error | `output-errors`, runtime | the engine thread | – | – |

Safety isn't part of the engine fingerprint (`backend/output_runtime.clj:35-39`), so an edit applies at the next frame with no restart.

## 6. Events and effects

| Event | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:projectors/set-safety` | NEW | the canvas, the number fields, Reset | yes |
| `:projectors/add-blocked-area`, `:projectors/remove-blocked-area` | NEW | the buttons | yes |
| `:projectors/copy-safety` | NEW | Copy to… / Mirror to… | yes |
| `:projectors/identify` | NEW | Identify (the inspector; Z2's card menu) | yes (uses `:time`) |
| `:projectors/toggle-calibration`, `:projectors/set-calibration-brightness` | yes | Show test pattern, the slider | yes |

## 7. UX

```
SAFETY ZONE
┌────────────────────────────────────────┐
│ ┌────────────────────────────────────┐ │  ── green: the limit (drag an edge or a corner)
│ │                                    │ │  ▒▒ red, hatched: blocked areas (drag to move, edges to resize)
│ │            ▒▒▒▒▒▒▒▒▒▒              │ │  grey, thin: what the laser draws now, behind
│ │            ▒▒▒▒▒▒▒▒▒▒  Blocked 1   │ │
│ └────────────────────────────────────┘ │
└────────────────────────────────────────┘
Limit       x [-0.90] to [ 0.90]   y [-0.80] to [ 0.95]
Blocked 1   x [-0.20] to [ 0.20]   y [-1.00] to [-0.40]   [✕]
[+ Add blocked area]   [Copy to… ▾]   [Mirror to… ▾]   [Reset]

TEST PATTERN   [Show test pattern]   [Identify]   Brightness ━━●━━━━ 10%
```

| Situation | Copy |
|---|---|
| Growing the area on a live laser | Title "Allow light in a larger area?". Message: "Laser L is on. Light can reach places it couldn't before." Buttons [Cancel] [Allow] |
| Removing a blocked area on a live laser | The same dialog. Message: "Laser L is on. Light can reach Blocked 1 again." |
| Copy or Mirror to live lasers that grow | "Allow light in a larger area on Laser R and Laser C?" |
| Safety failed | On the card or in the status: "Safety error: output off". Tooltip: the message |
| Identify while streaming is off | The button is disabled. Tooltip: "Turn on Stream to see it on the laser" |
| Identify on a turned-off laser | The button is disabled. Tooltip: "Laser L is turned off" |
| Too many blocked areas | "+ Add blocked area" is disabled at 8. Tooltip: "Up to 8 blocked areas" |
| The test pattern on | The button says "Hide test pattern". The laser draws only the pattern, even with nothing playing. BLACKOUT still makes it dark |

- **Y goes up** in the canvas, as on the laser.
- The number fields commit on Enter or when they lose focus, following the same widening rule.
- **Reset** sets the full limit and no blocked areas, which widens, so it asks on a live laser.

## 8. Data examples and invariants

```clojure
(get-in state [:projectors :projector-10-0-0-5-1 :safety])
;; => {:limit [-0.9 -0.8 0.9 0.95] :blocked [[-0.2 -1.0 0.2 -0.4]]}

;; one lit line straight through the blocked area
(safety/apply-safety s [[-0.5 -0.7 0 0 0] [0.5 -0.7 1 1 1]])
;; => [[-0.5 -0.7 0 0 0]      ; dark start
;;     [-0.2 -0.7 1 1 1]      ; lit up to the blocked area's left edge
;;     [-0.2 -0.7 0 0 0]      ; off at the edge
;;     [ 0.2 -0.7 0 0 0]      ; dark across the blocked area (1 + edge-dwell points)
;;     [ 0.2 -0.7 0 0 0]
;;     [ 0.5 -0.7 1 1 1]]     ; lit again to the end

(safety/widens? {:limit [-0.9 -0.9 0.9 0.9] :blocked []} {:limit [-1 -0.9 0.9 0.9] :blocked []})  ;; => true
(safety/widens? {:limit [-0.9 -0.9 0.9 0.9] :blocked []} {:limit [-0.8 -0.9 0.9 0.9] :blocked []}) ;; => false
```

**Invariants**

1. **Nothing lit outside.** Every lit segment of the output (a lit point and the point before it) lies inside the limit and outside every blocked area, up to 1e-9.
2. **Nothing aimed outside.** Every point of the output, dark or lit, is inside the limit.
3. **Nothing new lit.** Every lit output point has the colour of the input point it came from, and lies on that input segment.
4. **No zone, no change.** `identity?` safety returns the same frame (`identical?`), so old shows send what they sent before (spec invariant 3).
5. **Fails closed.** An exception in the stage makes that laser send an empty frame and report `[:safety id]`. The other lasers are unaffected.
6. **Last.** Only `sanitize-frame` and the encoder run after the stage, and neither can light a point (`idn/frame_rules.clj:19-31` only adds a dark first point).
7. **The test pattern goes through safety**, and BLACKOUT makes it dark.
8. **Live widening needs a yes.** While a laser is outputting, no event widens its allowed area without `:confirmed?`.
9. **Applied at once.** An edit is in force from the next frame, with no engine restart.

## 9. Tests

- `laser-show.animation.safety-test` (NEW):
  - `normalize-test` (ordered corners, clamps, minimum sizes, at most 8);
  - `intervals-test`: the limit, one blocked area, two blocked areas, touching edges, a zero-length segment;
  - `apply-safety-example-test`: the example in §8;
  - `identity-returns-the-same-frame-test`;
  - `nothing-lit-outside-test` and `nothing-aimed-outside-test`: 2,000 seeded random frames × random zones, invariants 1–3;
  - `non-finite-points-are-dropped-test`;
  - `widens-test` (a table);
  - `mirror-x-test`.
- `laser-show.animation.test-pattern-test` (NEW):
  - `content-layer-is-white-at-brightness-test`;
  - `safety-layer-outlines-stay-visible-test` (after `apply-safety`, each outline is still lit);
  - `identify-flashes-test`.
- `laser-show.backend.multi-engine-test` (existing; patterns `:31-35`, `:355-395`, `:452-470`):
  - `safety-runs-after-projector-effects-test` (a corner pin that pushes a point out: it ends up dark, at the limit);
  - `failing-safety-sends-an-empty-frame-test` (`with-redefs` makes `apply-safety` throw: an empty frame, `[:safety id]` reported, the other projector still draws);
  - `failing-calibration-is-still-clipped-test` (the B-12 case: a skipped corner pin, and safety still clips);
  - `test-pattern-shows-with-nothing-playing-test`;
  - `blackout-hides-the-test-pattern-test`;
  - `identify-flashes-then-stops-test`;
  - `no-safety-zone-sends-the-same-frame-test` (same as `engine-frame-equals-direct-render-test`).
- `laser-show.backend.output-runtime-test` (or `output_lifecycle_test`): `safety-error-shows-in-status-test`.
- `laser-show.events.projector-handlers-test` (existing):
  - `set-safety-normalizes-test`;
  - `shrinking-a-live-zone-applies-at-once-test`;
  - `growing-a-live-zone-asks-first-test`;
  - `confirmed-growth-applies-test`;
  - `growing-an-off-laser-applies-at-once-test`;
  - `removing-a-blocked-area-asks-when-live-test`;
  - `copy-and-mirror-safety-test`;
  - `identify-sets-its-end-time-test`.
- `laser-show.events.output-sync-test` (existing, `:69`): `safety-change-requests-no-refresh-test`.
- UI, NEW `laser-show.ui-test.safety-editor-test`:
  - `drag-the-limit-in-test`;
  - `add-and-remove-a-blocked-area-test`;
  - `number-fields-commit-test`;
  - `growing-while-streaming-asks-test` (with the fake engine of `streaming_status_test`);
  - `identify-needs-stream-test`.

  The existing `calibration-brightness-slider-test` passes with the new button text, because it uses the slider's id.

Run: `clj -M:mac-arm:test safety test-pattern multi-engine projector-handlers output-sync output-lifecycle`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.safety-editor-test`, `-n laser-show.ui-test.setup-dialogs-test`, `-n laser-show.ui-test.streaming-status-test`, then both full suites. **Before calling Z1 done, try it on a real projector** at low brightness: a blocked area, and a line swept through it.

## 10. Commit plan

Write the failing tests first in each step.

| # | Commit | Tests |
|---|---|---|
| 1 | `feat(output): safety zones: a scan limit and blocked areas cut every lit line at their edges and keep the mirrors inside the limit` (pure, not yet wired) | `safety-test` |
| 2 | `feat(output): every frame a projector sends goes through its safety zone last; if that step fails the projector goes dark and says why` | the `multi-engine` safety tests, `safety-error-shows-in-status-test` |
| 3 | `feat(output): the test pattern: a grid through the projector's calibration and its safety outline on top, at the calibration brightness, shown even with nothing playing` | `test-pattern-test`, `test-pattern-shows-…`, `blackout-hides-…` |
| 4 | `feat(projectors): Identify flashes a projector's test pattern for 3 seconds` | `identify-…` |
| 5 | `feat(projectors): the safety zone events: shrinking applies at once, growing a live laser's area asks first` | the handler tests |
| 6 | `feat(ui): a safety zone editor in the projector panel: drag the limit and blocked areas or type them, copy or mirror them to other projectors` | `safety-editor-test` |
| 7 | `docs: the Projectors tab and QUICKSTART mention safety zones again` | the subtitle `assert-text!` |

## 11. Out of scope / alternatives considered

- **Polygons:** later (D-31, needs/08). Rectangles cover scan limits and most blocked areas, and keep the maths and the editor simple.
- **Audience limits** (a brightness cap, a minimum scan speed, no static beams): later (D-31).
- **Safety in content space** (before calibration): rejected. A corner pin could move light past it. The limit has to be where the mirrors are.
- **Inverting the corner pin** so content can be fitted into the limit: later. Z3 approximates with the limit's box (spec §7, 00 R-3).
- **Identify starting an engine for one laser** when streaming is off: rejected for v1. It would bypass the operator's Stream switch. The button explains instead.
- **An off switch for safety:** rejected. The default zone changes nothing, so there's nothing to switch off.
- **Holding the last good frame when safety fails (Q6 B):** rejected (D-29).

## 12. Spec questions and open questions

**Spec questions**
- **S-Z1-1 · Edges.** The spec doesn't say whether edges are inside. **Chosen:** the limit's edges count as allowed, so its outline can be drawn, and blocked areas' edges count as blocked, which is the stricter choice.
- **S-Z1-2 · The test pattern's two layers.** The spec says the pattern "goes through the same stage". **Chosen:** the grid goes through calibration and the outline doesn't, and both go through safety. Otherwise the outline would be warped by the corner pin and would no longer show the true limit.
- **S-Z1-3 · BLACKOUT vs the test pattern.** Not in the spec. **Chosen:** BLACKOUT wins. Stop (the transport) doesn't stop the pattern, because calibrating usually happens with nothing playing.
- **S-Z1-4 · "Outputting".** Not defined in the spec. **Chosen:** streaming is on and the laser has an engine that is `:streaming` or `:starting`.

**Open questions** (★ = recommended)
- **Q-Z1-1 · Until ZR, opening a show replaces the safety zones with the show's** (today's behaviour for every projector setting). ★ **A** Accept it until ZR, with a notice after Open: "Safety zones and calibration come from friday.zip". **B** Pull the "never overwrite safety" part of ZR into Z1: keep this machine's `:safety` on Open.
- **Q-Z1-2 · Identify with streaming off.** ★ **A** Disabled, with a tooltip. **B** Briefly stream just that laser.
- **Q-Z1-3 · Test-pattern brightness ceiling.** The slider stops at 50% today (`events/handlers/projector.clj:386`). ★ **A** Keep 50%. **B** 100%, with a warning colour above 50%.
