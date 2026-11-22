# Phase Z1: safety zones, the test pattern and Identify

**Written:** 2026-11-19 at `d298801`. **Reviewed and re-checked at `70f7db8`** (`master`, after vibe Phases 4b and 5 and the fader fix `0faa18c`): every line below is at `70f7db8`. **Re-check every path and line at HEAD before starting.**
**Status:** **Done in code, on `master`** (`fa15044`..`1424955`; built in worktree `zones-z1` alongside Z2 and vibe Phase 7). **Still owed: the real-projector check in §9**, which decides `edge-dwell` (1 until then). The as-built note is §13. Q-Z1-1 is answered (D-34); Q-Z1-2, -3 and -4 were built as ★ A, and so was Q-Z1-5 (the show lock), in `0002052` after the phase.   **Needs:** nothing. It goes well with Z0, whose confirm dialog it reuses (02 §4); without Z0 it brings that dialog itself. It can be built alongside Z2.   **Size:** ~4–5 d.
**Implements:** spec §10 (`01-model-and-routing-spec.md`), D-28 (edits on a live laser), D-29 (fail closed) and D-34 (a notice after Open). It also gives Z3 what its doc assumes (05 §1, "Z1 row"): `[:projectors id :safety :limit]`, a stage after the projector effects that reports under `[:safety id]`, and a test-pattern branch that replaces a laser's content before its projector effects, with no composition and no fit guard (05 S-13).

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`. **NEW** marks anything that doesn't exist yet.

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
- **Edit safely while the laser is live.** Making the area smaller applies at once. Making it bigger, by an edit or by Undo, asks "Allow light in a larger area?" first.
- **Know where the zones came from.** Until ZR, opening a show brings its safety zones and calibration with it, and a notice says so: "Safety zones and calibration come from friday.zip" (D-34).

## 1. Current behaviour (file:line, at `70f7db8`)

| What | Where | Behaviour |
|---|---|---|
| Safety zones | – | **None** (B-1). The Projectors subtitle claims them (`views/tabs/projectors.clj:799-801`; Z0 removes the claim) |
| A projector's frame | `backend/multi_engine.clj:119-150` | The stale check (`:128-130`). Then, while something plays, each cue's zone frames, joined, then `apply-projector-effects` (`:94-111`: RGB curves, corner pin, axis transform). A failing effect is skipped (`animation/effects.clj:349-385`), and if the chain throws anyway the frame goes out uncalibrated (`:108-110`). Nil while nothing plays (`:131`). The provider catches anything else, reports `[:provider id]` and sends nothing (`:164-171`) |
| Before sending | `backend/streaming_engine.clj:366-378` | `frame-rules/sanitize-frame` (`idn/frame_rules.clj:19-31`) drops non-finite points, puts a dark copy in front of a lit first point, and doubles a lone point. The encoder clamps positions to ±1 (`idn/output_config.clj:99-111`) |
| Errors | `backend/output_errors.clj:42-73`; `backend/output_runtime.clj:172-181`; `views/tabs/projectors.clj:255-266` | A key such as `[:provider id]` is reported. The status takes the first message among `[:engine id] [:provider id] [:projector-effects id]` (`:173`), and the projector's status line prints "Error: <message>" |
| Calibration mode | `events/handlers/projector.clj:373-386`; `views/tabs/projectors.clj:641-682`; `state/domains.clj:330-333` (`:calibrating-projector-id`, `:calibration-brightness` 0.1) | **Start Calibration** and the brightness slider (0.05–0.5) set state, and **nothing reads it** (B-2). `services/frame_service.clj:25-28` describes a test pattern that doesn't exist. The calibrating id survives Open and New: `reset-session` resets only the selection (`state/persistent.clj:167-168`) |
| Identify | – | None |
| The show clock | `services/show_clock.clj:45, 148-180, 189-204` | Ticks 60 times a second whether or not anything plays, and publishes `:playing?`. With nothing playing, `:projector-ctx` is nil (`:90`) |
| Engines | `backend/output_runtime.clj:35-50` | Exist only while streaming is on, for enabled projectors with a host. Virtual projectors never get one. Projector settings other than the fingerprint (host, port, service, output config, scan rate) are read live, with no restart |
| The effects grid | `services/frame_service.clj:84-101, 299-311, 413`; `state/templates.clj:105-118`; `vibes/faders.clj:56-74` | Its active cells run inside each cue's render, before the projector effects. Since vibe Phase 5, a new project has a **Master** at [0 3] whose Intensity follows fader 1, and the Morph cells scale, spin or recolour with fader 2 |
| BLACKOUT | `[:vibe-runtime :blackout?]` (`state/domains.clj:119-120`), set by `vibes/engine.clj:414-432` | Stops the vibe's voices, its effect cells and the transport. A grid cue started by hand still plays, and Perform's banner says so |
| Opening a show | `state/persistent.clj:145-149, 179-191`; the handler `events/handlers/menu.clj:88-92` | Replaces `[:projectors]` with the show's, so after Z1 **the show's safety zones replace this machine's** until ZR (09) splits the rig out. Accepted until then, with a notice (D-34). Open and New clear the old notice (`state/persistent.clj:165`; `test/laser_show/events/menu_file_handlers_test.clj:269-280`). New Project resets `[:projectors]` too (`:193-203`), so a laser found again by a scan comes back with no zone |
| Notices | `events/helpers.clj:105-123` (`show-notice`); `subs.clj:88-91` | One notice at the left of the status bar, cleared after 6 s or on a click |
| Undo | `events/handlers/history.clj:74-102` | Undo and Redo restore the whole document, `[:projectors]` included, with no check |
| A canvas editor to copy | `views/components/visual_editors/canvas_interaction.clj:107-356` (`interactive-canvas`: drag state, hover, a preview value, an undo transaction per drag, opened at `:227`, committed on release after the release's dispatch `:248-265`, aborted by Escape `:297-310`); used by the corner pin editor (`views/tabs/projectors.clj:559-575`) | The safety editor is built on it |
| Number fields | `views/components/commit_field.clj:7-40` | Apply on Enter or when focus leaves |
| Points | `animation/types.clj:59-133` | `[x y r g b]`, positions −1…1, dark when r, g and b are all under 1e-6 (`blanked?`, `:94-102`). The encoder sends those as 0 at 8 and at 16 bits (`idn/output_config.clj:113-122`) |

## 2. Why

Nothing limits where light goes. The corner pin warps the picture but never clips it, and everything downstream assumes the content is safe. The calibration button promises a test pattern that nobody draws. Every later phase (split lasers, patterns, maps) moves light around in new ways, so the limit has to come first and has to be last in line.

## 3. Reuse / extend

| Existing thing | How it's reused |
|---|---|
| `projector-frame` (`backend/multi_engine.clj:119-150`) | The safety stage goes after `apply-projector-effects` (`:148-150`). The test-pattern branch goes after the stale check and before the `:playing?` check (`:131`) |
| `output-errors/report!`, `ok!` and the status lookup (`backend/output_runtime.clj:173`) | The key `[:safety id]` is added to the lookup, so the card shows the error |
| `concatenate-frames` (`services/frame_service.clj:206-228`) | Joins the test pattern's grid and outline with a dark jump |
| `canvas_interaction/interactive-canvas` | The safety editor's canvas: drag edges and corners, one undo step per drag |
| `commit-field` | The number fields |
| `[:projector-ui :calibrating-projector-id]`, `:calibration-brightness`, `:projectors/toggle-calibration`, `:projectors/set-calibration-brightness` (`events/handlers/projector.clj:373-386`) | Kept as they are, now read by the engine. The button text becomes "Show test pattern" / "Hide test pattern", and the ids `#projector-calibration-button` and `#calibration-brightness-slider` stay (`calibration-brightness-slider-test` uses them) |
| The `:time` co-effect (`events/core.clj:88-91, 500-505`) and `:dispatch-later` (`:162-168`) | Identify's end time, and clearing it |
| Z0's confirm dialog (`views/dialogs/confirm.clj`, 02 §4) | "Allow light in a larger area?" (edits, Undo and Redo), and Identify while a cue plays |
| `show-notice` (`events/helpers.clj:109-123`) | The notice after Open |
| The seeded-random test style (`test/laser_show/idn/frame_rules_test.clj:23-57`; no `test.check` in `deps.edn`) | The property tests loop over seeded random frames and zones |

## 4. Changes per namespace

### NEW `laser-show.animation.safety` (pure)

It sits beside Z3's `animation.fit-guard`: pure frame-to-frame functions, no state.

| Function | Behaviour |
|---|---|
| `default-safety` | `{:limit [-1.0 -1.0 1.0 1.0] :blocked []}` |
| `normalize [s]` | Fills in the defaults, turns every number into a double, orders each rectangle's corners and clamps it to −1…1. **It never makes the allowed area larger**: nothing is grown, moved or dropped. Malformed data throws (a missing corner, a non-number, a NaN, a limit with no width or height), so the stage fails closed and the card says why. The editor, not `normalize`, keeps the limit at least 0.05 wide and high, blocked areas at least 0.01, and at most 8 of them |
| `identity? [s]` | The full limit and no blocked areas. `apply-safety` then returns the very same frame (`identical?`), so a laser with no safety zone costs nothing and sends exactly what it sends today |
| `inside? [s x y]` | Inside the limit (edges count as inside) and not in any blocked area (edges count as blocked) |
| `intervals [s p q]` | The parts `[[t0 t1] …]` of the segment p→q (t from 0 to 1) that are allowed. The limit is clipped with Liang–Barsky, then each blocked area's interval is subtracted. The parts are sorted and don't overlap. Allowed parts shorter than 1e-6 are dropped, and so is a blocked part that short (a touch at a corner). A zero-length segment (p = q, a dwell) is `[[0 1]]` if `inside?`, else `[]` |
| `apply-safety [s frame]` | The algorithm below |
| `widens? [old new]` | True when the new zone may let light somewhere the old one doesn't: the new limit pokes out of the old one, or the part of an old blocked area that lies inside the new limit isn't inside one new blocked area. Both zones are normalized first, it allows 1e-9 for rounding, and a zone that can't be read allows nothing. It is **sound** (it never says no to a change that lets light somewhere new) and conservative: splitting one blocked area in two counts as widening |
| `mirror-x [s]` | Each rectangle `[x0 y0 x1 y1]` becomes `[(- x1) y0 (- x0) y1]` |

**`apply-safety`.** A point's colour is the colour of the move *to* it from the point before: the DAC holds a sample's colour while the mirrors travel to it, as `create-blanking-jump` (`services/frame_service.clj:190-204`) and `sanitize-frame` assume. A move is **allowed** when all of it is (`intervals` gives `[[0 1]]`). For each input point q, with p the last point kept before it:

1. A non-finite q is **dropped**, and p stays what it was. `sanitize-frame` drops them later too, but here it has to happen before the maths: dropping a point after clipping would join its neighbours with a line nobody clipped.
2. **q dark:** emit q dark, **moved into the limit** (`clamp`), so the mirrors never aim outside it, even dark. A dark point in a blocked area stays where it is.
3. **q lit:** take `I = intervals(p, q)`. The first point has no p, so `p = q`.
   - If `I = [[0 1]]`, emit q as it is. This is the common case.
   - Otherwise, for each part `[a b]` of I: if `a > 0`, emit a **dark** point at `s(a)`, so the beam travels there dark; then emit q's colour at `s(b)`. After the last part, if it ends before 1, or if I is empty, emit q **dark and clamped**.
   - `s(t)` is the point along p→q at t. A point at an edge is put exactly on that edge, then clamped into the limit.
4. **The light switches only where the beam stands still.** With blocked areas, each dark stretch of the output (the dark points between two lit ones, or before the first, or after the last) that has a move that isn't allowed starts with `edge-dwell` dark copies of the lit point before it and ends with `edge-dwell` dark copies of its own last point. So the beam turns off before it moves, and arrives before it turns on. The jumps into and out of the frame count as moves that aren't allowed, since what comes next (another frame, or this one again) isn't known: a frame that starts lit gets `1 + edge-dwell` dark copies of its first point in front, and one that ends lit gets `edge-dwell` dark copies of its last point. With only a limit, nothing is added: every move between two points inside a rectangle stays inside it.
5. **`edge-dwell` is 1 in v1** (a constant, not a setting). A projector that shows each colour up to `edge-dwell` samples early or late then still lights nothing in a blocked area (invariant 4), and the mirrors get that many samples to arrive before the light comes back. Galvos can lag by more than a sample at 25 kpps, so the hardware check (§9) sets the real value.

What the steps mean for the awkward cases:
- **A point exactly on an edge.** The limit's edges are allowed, blocked areas' edges are blocked (S-Z1-1). A line along a blocked edge stays dark. A lit point computed at a blocked edge lies on it, within invariant 1's 1e-9.
- **A segment through several areas** gets one part per gap between them. Each gap is a dark stretch with a move that isn't allowed, so both its ends are held.
- **Consecutive dark points** are each clamped. They get no extra points unless a move among them isn't allowed.
- **Frames joined by `concatenate-frames`** (`services/frame_service.clj:206-228`): the jump is a dark point at each end. Its points are only clamped. If the jump crosses a blocked area, step 4 holds both ends, so the next shape lights up only once the beam has stopped.
- **The first point** and **dwell points** (p = q) are zero-length segments, lit only where they are allowed.

The cost is `O(n · (k + 1))` for n points and k blocked areas. With no blocked areas and every point inside, there is one limit test per point, and the input frame comes back as it is.

### NEW `laser-show.animation.test-pattern` (pure)

It is pure, so it lives in `animation/` (needs/08 R-1 calls it `services/test_pattern.clj`; R-1's number glyph goes here).
- `content-layer [brightness]`: a 5 × 5 grid of lines across −0.9…0.9 plus a small circle in the middle, about 400 points, white at `brightness`. It goes **through the projector effects**, so you see the corner pin.
- `safety-layer [s brightness]`: the limit's outline, plus each blocked area's outline drawn **0.01 outside** that area (so it isn't blanked), as dashes. About 150 points per rectangle. It is drawn in **output** space, after the projector effects, so it shows where the limit really is.
- `identify-on? [now-ms until-ms]`: lit for 250 ms, dark for 250 ms, until `until-ms`.

### Existing namespaces

- `laser-show.backend.multi-engine`, `projector-frame` (`:119-150`):

  ```clojure
  (when snapshot
    ;; the stale check first, as today (:128-130)
    (let [raw     (state/get-raw-state)
          ctx     (:projector-ctx snapshot)                   ; nil while nothing plays
          pattern (test-pattern-mode raw id now-ms)]          ; :identify-on, :identify-off, :calibrate or nil
      (cond
        (and pattern (blackout? raw)) (stamp snapshot [])     ; BLACKOUT darkens the pattern, and only the pattern
        (= pattern :identify-off)     (stamp snapshot [])     ; the dark half of a flash
        pattern                                               ; even with nothing playing; no composition, no fit guard
        (guard-safety raw id snapshot
                      (fn [s] (concatenate-frames [(apply-projector-effects (content-layer b) id ctx)
                                                   (safety-layer s b)])))
        (:playing? snapshot)
        (when-let [joined (…each cue's zone frames, joined, as today…)]   ; Z3 changes this part
          (guard-safety raw id snapshot (fn [_] (apply-projector-effects joined id ctx)))))))
  ```

  - `guard-safety` puts reading and normalizing `[:projectors id :safety]`, `(f s)` and `apply-safety` in one `try`, so a zone it can't read fails closed too.
    - On an exception it reports `[:safety id]` and returns an **empty frame**, so the engine sends a void frame.
    - On success it calls `ok!`.
    - Either way the frame carries the snapshot's `{:tick :at-ns}` (`stamp`), as content frames do, so the engine counts the tick.
    - Without this catch the provider's (`:164-171`) would still send nothing. This one gives the error its name.
  - `test-pattern-mode` reads `[:projector-ui :calibrating-projector-id]` and `[:projector-ui :identify id]`. Identify wins while it flashes, and stops counting at its end time, even before `identify-ended` clears it.
  - With nothing playing, `ctx` is nil, so modulators in the projector effects read beat 0 (`animation/effects.clj:360-363`).
  - The pattern skips the render, so the effects grid, the Master included, never touches it (S-Z1-8).
- `laser-show.backend.output-runtime`, `engine-status` (`:172-181`): the lookup becomes `[:safety :engine :provider :projector-effects]`, safety first because it is why the laser is dark. A `:safety` error gives `{:state :error :kind :safety :error "Safety error: output off" :detail <message>}`.
- `laser-show.views.tabs.projectors`, `output-status-text` (`:255-266`): a `:kind :safety` error is shown as it is ("Safety error: output off", not "Error: …"), with `:detail` as the tooltip.
- `laser-show.events.handlers.projector`:
  - **`:projectors/set-safety {:projector-id :safety :confirmed?}`** (NEW). It normalises the value, and refuses one `normalize` can't read.
    - If the laser is **outputting** (below), `(safety/widens? old new)`, and the event isn't `:confirmed?`, it opens the confirm dialog with `:confirm-event` = the event without `:state`, `:time` and `:fx/event`, plus `:confirmed? true`, and **changes nothing**. While that question is open, another widening is dropped.
    - Otherwise it writes `[:projectors id :safety]`.

    ZR's `:rig/use-show-setup` (09 §6) puts the show's zone through the same check.
  - **`:projectors/add-blocked-area {:projector-id}`** adds a 0.4 × 0.2 rectangle in the middle of the limit. That shrinks the area, so it applies at once. It does nothing at 8.
  - **`:projectors/remove-blocked-area {:projector-id :index}`** goes through the same check as `set-safety` (removing an area widens).
  - **`:projectors/copy-safety {:from :to [ids] :mirror? :confirmed?}`**: one confirm for every target it would widen while live ("Allow light in a larger area on Laser R and Laser C?"), then one undo step.
  - **`:projectors/identify {:projector-id :time :confirmed?}`** sets `[:projector-ui :identify id]` to `(+ time 3000)`, and a `:dispatch-later` of 3000 ms sends NEW `:projectors/identify-ended {:projector-id :until}`, which clears it only if it still holds that time. Pressing it again restarts the 3 seconds. It does nothing for a laser that isn't outputting. While a cue plays, it asks first (Z0's confirm, needs/08 §5): "Identify Laser L? It shows the test pattern for 3 seconds instead of the show."
  - Each of these gives `{:state state}` for a projector that doesn't exist (the undo-labels test calls every labelled event bare).
  - `toggle-calibration` (`:373-379`) is unchanged.
  - Z0's `:projectors/remove` also clears `[:projector-ui :identify id]`.
  - **Outputting** means streaming is on (`[:backend :streaming :running?]`) and the laser is one the engines are built for (`output-runtime/wanted`: enabled, with a host). It's the operator's intent, not the engine's status, because the status lags up to 250 ms and an engine in error may come back (S-Z1-4). An off or disabled laser can be edited freely.
- `laser-show.events.handlers.history`:
  - `handle-undo` and `handle-redo` (`:84-102`, Q-Z1-4) compare each outputting laser's zone before and after the restore, with the same `outputting?` and `widens?`. If one grows and the event isn't `:confirmed?`, they open the same confirm with `{:event/type :history/undo :confirmed? true}` (or redo) and change nothing.
  - The labels (`:125-163`) gain "Change Safety Zone", "Add Blocked Area", "Remove Blocked Area" and "Copy Safety Zone".
- `laser-show.state.persistent`, `reset-session` (`:151-169`): Open and New also reset `:calibrating-projector-id` and `:identify` (`:167-168`), so the next show's laser with the same id doesn't start in the test pattern.
- `laser-show.events.handlers.menu`, `handle-file-project-loaded` (`:88-92`), D-34:
  - After `apply-loaded-project` it returns `(h/show-notice new-state (str "Safety zones and calibration come from " (file-name file-path)) :info)` (`file-name` is at `:94-97`).
  - It skips the notice when neither this machine nor the show has a projector.
  - Open still clears the notice that was there before, since `reset-session` runs first.
  - ZR takes the notice away, since Open no longer touches the rig there (09).
- `laser-show.state.domains`:
  - The projectors docstring (`:279-302`) gains `:safety`.
  - `projector-ui` (`:322-341`) gains `:identify {:default {}}`. This is UI state, never saved.
- `laser-show.views.tabs.projectors`:
  - A NEW **SAFETY ZONE** section in the config panel (`:684-783`; in Z2's inspector after Z2), above the calibration controls.
  - The calibration controls (`:641-682`) are renamed "TEST PATTERN" and gain an **Identify** button (`#projector-identify-button`).
  - The subtitle (`#projectors-subtitle`, Z0) says "Scan for IDN devices • Configure color calibration and safety zones" again.
- NEW `laser-show.views.components.safety-editor`: the canvas (§7) on `interactive-canvas`, the number fields, and the buttons.
  - The canvas shows **output** coordinates, as the laser gets them after its calibration, with Y up. On a laser whose axis transform flips it (an upside-down mount), the canvas is flipped against the room too. The test pattern on the laser shows the truth.
  - A drag that only shrinks the area dispatches `set-safety` while it moves, inside the canvas's one undo transaction. Escape aborts it, which puts back the zone from before the drag without asking (invariant 9).
  - A drag that widens it, while the laser is outputting, shows a **dashed** preview and dispatches once on release, which asks. Until Allow, the canvas shows the stored zone again, and Cancel leaves it so.
  - A number field whose change is waiting for the question, or was refused, shows the stored value again when the dialog closes: its `:fx/key` includes the dialog's open state, because cljfx won't reset a field whose description didn't change.
- `laser-show.services.frame-service`: the docstring's "Calibration Mode" paragraph (`:25-28`) now points at `animation.test-pattern` and `multi-engine`.
- `QUICKSTART.md` (step 3, `:45`): one sentence on drawing a safety zone with the test pattern on.

## 5. State placement

| Path | Lives in | Written by | Undo? | Unsaved? |
|---|---|---|---|---|
| `[:projectors id :safety]` | document (`hardware.edn`, until ZR moves it to the rig file) | the safety events | yes, one step per event or drag | yes (after ZR: the rig saves itself, 09) |
| `[:projector-ui :calibrating-projector-id]`, `:calibration-brightness` | UI (exists) | the existing events; Open and New reset the id | no | no |
| `[:projector-ui :identify id]` → until-ms | UI, NEW | `:projectors/identify`, `-ended`; Open and New | no | no |
| `[:ui :notice]` after Open | UI (exists) | `handle-file-project-loaded` | no | no |
| the `[:safety id]` error | `output-errors`, runtime | the engine thread | – | – |

Safety isn't part of the engine fingerprint (`backend/output_runtime.clj:35-39`), so an edit applies at the next frame with no restart.

## 6. Events and effects

| Event | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:projectors/set-safety` | NEW | the canvas, the number fields, Reset | yes |
| `:projectors/add-blocked-area`, `:projectors/remove-blocked-area` | NEW | the buttons | yes |
| `:projectors/copy-safety` | NEW | Copy to… / Mirror to… | yes |
| `:projectors/identify`, `:projectors/identify-ended` | NEW | Identify (the inspector; Z2's card menu); its `:dispatch-later` | yes (uses `:time`) |
| `:projectors/toggle-calibration`, `:projectors/set-calibration-brightness` | yes | Show test pattern, the slider | yes |
| `:history/undo`, `:history/redo` | yes, changed (Q-Z1-4) | ⌘Z and ⇧⌘Z, the Edit menu; the confirm dialog | yes |
| `:file/project-loaded` | yes, changed (the notice) | the Open flow | yes |
| `:ui/confirm` | Z0 | the confirm dialog | yes |

## 7. UX

```
SAFETY ZONE
┌────────────────────────────────────────┐
│ ┌────────────────────────────────────┐ │  ── green: the limit (drag an edge or a corner)
│ │                                    │ │  ▒▒ red, hatched: blocked areas (drag to move, edges to resize)
│ │            ▒▒▒▒▒▒▒▒▒▒              │ │  - - dashed: a larger area waiting for Allow
│ │            ▒▒▒▒▒▒▒▒▒▒  Blocked 1   │ │
│ └────────────────────────────────────┘ │
└────────────────────────────────────────┘
Limit       x [-0.90] to [ 0.90]   y [-0.80] to [ 0.95]
Blocked 1   x [-0.20] to [ 0.20]   y [-1.00] to [-0.40]   [✕]
[+ Add blocked area]   [Copy to… ▾]   [Mirror to… ▾]   [Reset]

TEST PATTERN   [Show test pattern]   [Identify]   Brightness ━━●━━━━ 10%
```

A disabled button shows no tooltip in JavaFX, so where one is disabled the reason is a short note beside it.

| Situation | Copy |
|---|---|
| Growing the area on a live laser | Title "Allow light in a larger area?". Message: "Laser L is on. Light can reach places it couldn't before." Buttons [Cancel] [Allow] |
| Removing a blocked area on a live laser | The same dialog. Message: "Laser L is on. Light can reach Blocked 1 again." |
| Copy or Mirror to live lasers that grow | "Allow light in a larger area on Laser R and Laser C?" |
| Undo or Redo that grows a live laser's area | The same title. Message: "Undo gives Laser L a larger area while it is on." Buttons [Cancel] [Undo] (or [Redo]) |
| Identify while a cue plays | Title "Identify Laser L?". Message: "It shows the test pattern for 3 seconds instead of the show." Buttons [Cancel] [Identify] |
| Safety failed | On the card or in the status: "Safety error: output off". Tooltip: the message |
| After Open | The notice: "Safety zones and calibration come from friday.zip" |
| Identify while streaming is off | The button is disabled, and the note says "Turn on Stream to see it on the laser" |
| Identify on a turned-off laser | Disabled. Note: "Laser L is turned off" |
| The test pattern on while streaming is off | The note: "Not streaming: turn on Stream to see it on the laser" |
| The test pattern on during BLACKOUT | The note: "BLACKOUT is on (Perform), so the pattern stays dark" |
| Too many blocked areas | "+ Add blocked area" is disabled at 8. Note: "Up to 8 blocked areas" |
| The test pattern on | The button says "Hide test pattern". The laser draws only the pattern, even with nothing playing |

- **Y goes up** in the canvas, in the laser's own coordinates after its calibration. A laser mounted upside down shows its zone upside down; the test pattern shows where it really is.
- The number fields commit on Enter or when they lose focus, following the same widening rule.
- **Reset** sets the full limit and no blocked areas, which widens, so it asks on a live laser.

## 8. Data examples and invariants

```clojure
(get-in state [:projectors :projector-10-0-0-5-1 :safety])
;; => {:limit [-0.9 -0.8 0.9 0.95] :blocked [[-0.2 -1.0 0.2 -0.4]]}

;; one lit line straight through the blocked area (edge-dwell 1)
(safety/apply-safety s [[-0.5 -0.7 0.0 0.0 0.0] [0.5 -0.7 1.0 1.0 1.0]])
;; => [[-0.5 -0.7 0.0 0.0 0.0]      ; dark start, held: the jump into the frame isn't known
;;     [-0.5 -0.7 0.0 0.0 0.0]
;;     [-0.2 -0.7 1.0 1.0 1.0]      ; lit up to the blocked area's left edge
;;     [-0.2 -0.7 0.0 0.0 0.0]      ; off where it stands
;;     [ 0.2 -0.7 0.0 0.0 0.0]      ; across the blocked area, dark
;;     [ 0.2 -0.7 0.0 0.0 0.0]      ; held, then on
;;     [ 0.5 -0.7 1.0 1.0 1.0]      ; lit again to the end
;;     [ 0.5 -0.7 0.0 0.0 0.0]]     ; the frame ends lit: off where it stands

(safety/widens? {:limit [-0.9 -0.9 0.9 0.9] :blocked []} {:limit [-1 -0.9 0.9 0.9] :blocked []})   ;; => true
(safety/widens? {:limit [-0.9 -0.9 0.9 0.9] :blocked []} {:limit [-0.8 -0.9 0.9 0.9] :blocked []})  ;; => false
;; a blocked area that stuck out of the limit, cut back to it: nothing new is allowed
(safety/widens? {:limit [-0.9 -0.9 0.9 0.9] :blocked [[-1.0 -1.0 -0.5 -0.5]]}
                {:limit [-0.9 -0.9 0.9 0.9] :blocked [[-0.9 -0.9 -0.5 -0.5]]})                       ;; => false
```

**Invariants**

1. **Nothing lit outside.** Every lit move of the output (a lit point and the point before it) stays inside the limit and out of every blocked area's interior, to within 1e-9.
2. **Nothing aimed outside.** Every point of the output, dark or lit, is inside the limit, so neither a colour shift nor the mirrors' lag can light outside it.
3. **Nothing new lit.** Every lit output point has the colour of the input point it came from, and lies on that input segment.
4. **A colour off by a sample stays out.** With every colour moved up to `edge-dwell` samples earlier or later, nothing inside a blocked area is lit.
5. **No zone, no change.** `identity?` safety returns the same frame (`identical?`), so old shows send what they sent before (spec invariant 3).
6. **Fails closed.** An exception in the stage, or a zone it can't read, makes that laser send an empty frame and report `[:safety id]`. The other lasers are unaffected.
7. **Last.** Only `sanitize-frame` and the encoder run after the stage. Neither can light a point. On the stage's output `sanitize-frame` can only add a dark copy in front or double a lone point, and the encoder's rounding moves a point by at most one step (1/32767 at 16 bits).
8. **The test pattern goes through safety**, and BLACKOUT makes it dark. BLACKOUT doesn't change what grid cues do, and the effects grid (the Master included) doesn't touch the pattern.
9. **Live widening needs a yes.** While a laser is outputting, no edit, Undo or Redo widens its allowed area without `:confirmed?`, and `normalize` never widens one. Two things put a larger area back without asking: Escape during a drag (the zone from before the drag), and Open (D-34, with its notice).
10. **Applied at once.** An edit is in force from the next frame, with no engine restart. *Since `plans/2026-11-20-state-integrity/01-engine-snapshot.md` (`dba29bb`): from the first tick that reads it, on every laser at once; the zone comes with the tick's snapshot, not from app state.*

## 9. Tests

- `laser-show.animation.safety-test` (NEW):
  - `normalize-test` (ordered corners, clamps, doubles) and `normalize-never-widens-test` (a tiny limit, a tiny blocked area and a ninth one are all kept);
  - `malformed-zone-throws-test` (including a limit with no width);
  - `intervals-test`: the limit, one blocked area, two blocked areas, touching edges, a corner touch, a zero-length segment;
  - `apply-safety-example-test`: the example in §8;
  - `identity-returns-the-same-frame-test`, and a frame wholly inside a zone with only a limit;
  - `nothing-lit-outside-test` and `nothing-aimed-outside-test`: 2,000 seeded random frames × random zones, invariants 1–3;
  - `colour-shift-stays-out-test`: the same frames with every colour moved up to `edge-dwell` samples either way, invariant 4;
  - `jump-across-a-blocked-area-is-held-at-both-ends-test`, `frame-starts-and-ends-dark-with-blocked-areas-test`;
  - `non-finite-points-are-dropped-before-clipping-test`;
  - `sanitize-changes-nothing-but-a-dark-start-test` (invariant 7);
  - `widens-test` (a table, with the cases in §8) and `widens-is-sound-test` (seeded pairs: whenever it says no, no sampled point is allowed by the new zone and not by the old);
  - `mirror-x-test`.
- `laser-show.animation.test-pattern-test` (NEW):
  - `content-layer-is-white-at-brightness-test`;
  - `safety-layer-outlines-stay-visible-test` (after `apply-safety`, each outline is still lit);
  - `identify-flashes-test`.
- `laser-show.backend.multi-engine-test` (existing; patterns `:31-35`, `:355-395`, `:452-470`):
  - `safety-runs-after-projector-effects-test` (a corner pin that pushes a point out: it ends up dark, at the limit);
  - `failing-safety-sends-an-empty-frame-test` (`with-redefs` makes `apply-safety` throw: an empty frame, `[:safety id]` reported, the other projector still draws);
  - `unreadable-safety-fails-closed-test`;
  - `failing-calibration-is-still-clipped-test` (the B-12 case: a skipped corner pin, and safety still clips);
  - `test-pattern-shows-with-nothing-playing-test`;
  - `master-doesnt-dim-the-test-pattern-test` (the Master at 0 and a Morph Size on: the pattern is what it is without them);
  - `blackout-hides-the-test-pattern-test`, `blackout-leaves-a-grid-cue-alone-test`;
  - `identify-flashes-then-stops-test`;
  - `no-safety-zone-sends-the-same-frame-test` (same as `engine-frame-equals-direct-render-test`).

  `idle-frame-provider-returns-nil-without-exceptions-test` (`:421`) passes unchanged: no pattern and nothing playing still give nil.
- `laser-show.backend.output-lifecycle-test` (existing): `safety-error-shows-in-status-test` (`:kind :safety`, "Safety error: output off"). `laser-show.views.tabs.projectors-test` (existing, `:10-17`): `output-status-text-test` gains the safety case.
- `laser-show.events.projector-handlers-test` (existing):
  - `set-safety-normalizes-test`;
  - `shrinking-a-live-zone-applies-at-once-test`;
  - `growing-a-live-zone-asks-first-test`, `parked-event-carries-no-state-test`;
  - `confirmed-growth-applies-test`;
  - `growing-an-off-laser-applies-at-once-test`;
  - `outputting-is-streaming-and-wanted-test`;
  - `removing-a-blocked-area-asks-when-live-test`;
  - `copy-and-mirror-safety-test`;
  - `identify-sets-its-end-time-test`, `identify-ended-clears-only-its-own-test`, `identify-needs-an-outputting-laser-test`, `identify-asks-while-a-cue-plays-test`;
  - `safety-events-on-a-missing-projector-change-nothing-test`.
- `laser-show.events.history-handlers-test` (existing): `undo-that-grows-a-live-zone-asks-test` (Redo too; a confirmed Undo restores, and a laser that isn't outputting just restores). `history-labels-name-real-events-test` (`:252-257`) covers the new labels.
- `laser-show.events.output-sync-test` (existing, `:69`): `safety-change-requests-no-refresh-test`.
- `laser-show.events.menu-file-handlers-test` (existing): `open-says-where-safety-comes-from-test` (the show has projectors; only this machine had; neither, so no notice). `new-and-open-take-the-notice-away-test` (`:269-280`) changes for Open: the old notice is gone, and the new one is the safety notice.
- `laser-show.state.persistent-test` (existing): `open-and-new-stop-the-test-pattern-test`, `safety-round-trips-test` (Save, then Open, gives the same `:safety`).
- UI, NEW `laser-show.ui-test.safety-editor-test` (`with-toolkit` once, `with-app` each):
  - `drag-the-limit-in-test`;
  - `add-and-remove-a-blocked-area-test`;
  - `number-fields-commit-test`;
  - `growing-while-streaming-asks-test` (with the fake DAC of `test/laser_show/ui_test/streaming_status_test.clj:23-36`), and `cancelled-field-shows-the-stored-zone-test`;
  - `identify-needs-stream-test` (the button is disabled and the note says why).

  The existing `calibration-brightness-slider-test` (`test/laser_show/ui_test/setup_dialogs_test.clj:173-183`) passes with the new button text, because it uses the ids.

Run: `clj -M:mac-arm:test safety test-pattern multi-engine projector-handlers history-handlers output-sync output-lifecycle projectors-test menu-file persistent`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.safety-editor-test`, `-n laser-show.ui-test.setup-dialogs-test`, `-n laser-show.ui-test.streaming-status-test`, then both full suites. **Before calling Z1 done, try it on a real projector** at low brightness: a blocked area, a line swept through it and a shape that jumps across it, at 25 and 40 kpps, and a look for light bleeding into the area where it comes back on (that decides `edge-dwell`).

## 10. Commit plan

Write the failing tests first in each step.

| # | Commit | Tests |
|---|---|---|
| 1 | `feat(output): safety zones: a scan limit and blocked areas cut every lit line at their edges, switch the light only where the beam stands still, and keep the mirrors inside the limit` (pure, not yet wired) | `safety-test` |
| 2 | `feat(output): every frame a projector sends goes through its safety zone last; if that step fails the projector goes dark and says why` | the `multi-engine` safety tests, `safety-error-shows-in-status-test` |
| 3 | `feat(output): the test pattern: a grid through the projector's calibration and its safety outline on top, at the calibration brightness, shown even with nothing playing, dark during BLACKOUT and never dimmed by the Master` | `test-pattern-test`, `test-pattern-shows-…`, `master-doesnt-dim-…`, `blackout-…`, `open-and-new-stop-the-test-pattern-test` |
| 4 | `feat(projectors): Identify flashes a projector's test pattern for 3 seconds` | `identify-…` |
| 5 | `feat(projectors): the safety zone events: shrinking applies at once, and growing a live laser's area asks first, by an edit, Undo or Redo` | the handler tests, `undo-that-grows-a-live-zone-asks-test` |
| 6 | `feat(projectors): after Open, a notice says the show's safety zones and calibration are now in use` | `open-says-where-safety-comes-from-test`, the changed `new-and-open-take-the-notice-away-test` |
| 7 | `feat(ui): a safety zone editor in the projector panel: drag the limit and blocked areas or type them, copy or mirror them to other projectors` | `safety-editor-test` |
| 8 | `docs: the Projectors tab mentions safety zones again, and QUICKSTART says how to draw one` | Z0's subtitle test, now with the new text |

## 11. Out of scope / alternatives considered

- **Polygons:** later (D-31, needs/08). Rectangles cover scan limits and most blocked areas, and keep the maths and the editor simple.
- **Audience limits** (a brightness cap, a minimum scan speed, no static beams): later (D-31).
- **Safety in content space** (before calibration): rejected. A corner pin could move light past it. The limit has to be where the mirrors are.
- **Inverting the corner pin** so content can be fitted into the limit: later. Z3 approximates with the limit's box (spec §7, 00 R-3).
- **What the laser draws now, behind the zone in the editor:** after Z3, from `show-time/output-frame` (05 §4). Until then nothing records a projector's output.
- **The canvas in the room's orientation** (undoing the axis transform for display): later.
- **Identify starting an engine for one laser** when streaming is off: rejected for v1 (Q-Z1-2). It would bypass the operator's Stream switch. The button explains instead.
- **An off switch for safety:** rejected. The default zone changes nothing, so there's nothing to switch off.
- **Holding the last good frame when safety fails (Q6 B):** rejected (D-29).
- **Keeping this machine's zones on Open** (Q-Z1-1 B): not in Z1 (D-34). ZR does it.
- **`edge-dwell` as a time or a per-projector setting:** later, if the hardware check shows that one count doesn't suit every scan rate or projector.

## 12. Spec questions and open questions

**Spec questions**
- **S-Z1-1 · Edges.** The spec doesn't say whether edges are inside. **Chosen:** the limit's edges count as allowed, so its outline can be drawn, and blocked areas' edges count as blocked, which is the stricter choice.
- **S-Z1-2 · The test pattern's two layers.** The spec says the pattern "goes through the same stage", and Z3 (05 §1) assumes a branch before the projector effects. **Chosen:** the grid goes through calibration and the outline doesn't, and both go through safety. Otherwise the outline would be warped by the corner pin and would no longer show the true limit. So the outline's colour isn't corrected either.
- **S-Z1-3 · BLACKOUT vs the test pattern.** Not in the spec. **Chosen:** BLACKOUT darkens the test pattern and Identify, and changes nothing else (a grid cue started by hand still plays, as today). Stop (the transport) doesn't stop the pattern, because calibrating usually happens with nothing playing.
- **S-Z1-4 · "Outputting".** Not defined in the spec. **Chosen:** streaming is on and the laser is in `output-runtime/wanted`. The engine's own status (`:streaming`, `:starting`) was the first idea, but it reaches app state up to 250 ms late, and an engine in error can recover, so a widening could slip through unasked.
- **S-Z1-5 · "The blanking points the frame rules need".** The frame rules only ask for a dark first sample and two samples (`idn/frame_rules.clj:19-31`). **Chosen:** with blocked areas, each dark stretch that crosses anything not allowed is held `edge-dwell` samples at both ends, and the frame starts and ends dark. With only a limit, nothing is added (§4, step 4). Spec invariant 1 says only the encoder runs after safety; `sanitize-frame` runs too, and on the stage's output it can only add a dark first point.
- **S-Z1-6 · Mixed edits.** The spec lists pure cases (shrink the limit, add a blocked area…). **Chosen:** a move or any mixed edit asks if it lets light anywhere new (`widens?`).
- **S-Z1-7 · For Z3.** Z3 reads `[:projectors id :safety :limit]`. The events store it normalized, but a hand-edited file might not be, so Z3's region fit should read it through `safety/normalize`.
- **S-Z1-8 · The effects grid and the faders** (vibe Phase 5). Not in the spec. The Master dims, and the Morphs scale, spin or recolour, all inside the render, before the projector effects. **Chosen:** safety needs nothing for them, since it clips whatever they do. The test pattern and Identify skip them: a Master pulled down to 0 can't hide the pattern, and the pattern's slider stays its only brightness.

**Open questions** (★ = recommended)
- **Q-Z1-1 · Until ZR, opening a show replaces the safety zones with the show's** (today's behaviour for every projector setting). **Answered A on 2026-11-19 (D-34):** accept it until ZR, with a notice after Open: "Safety zones and calibration come from friday.zip" (§4, §7). B was to pull ZR's "never overwrite safety" forward into Z1.
- **Q-Z1-2 · Identify with streaming off.** ★ **A** Disabled, with a note: "Turn on Stream to see it on the laser". **B** Briefly stream just that laser.
- **Q-Z1-3 · Test-pattern brightness ceiling.** The slider stops at 50% today (`events/handlers/projector.clj:386`). ★ **A** Keep 50%. **B** 100%, with a warning colour above 50%.
- **Q-Z1-4 · Undo or Redo that would grow a live laser's area.** Undo is one history for the whole show, so a ⌘Z meant for something else can undo a zone made smaller during the show. ★ **A** Ask with the same dialog (§4, `history`). **B** Just restore: it puts back a zone that was in force before. Either way, Escape during a drag doesn't ask: it puts back the zone from before the drag.

## 13. As built (2026-11-19, `fa15044`..`1424955`, on `master`)

Built in worktree `zones-z1` off `989c315` (Z0 on `master`), at the same time as Z2 (worktree `zones-z2`) and vibe Phase 7. Rebased onto `8b4b7d7` (vibe Phase 7 in), where the only conflict was two tests added at the same place in `persistent_test.clj` (both kept), and fast-forwarded onto `master`. Z2 landed on top of it (04 §13).

### Where it landed

| # | Commit | Step (§10) |
|---|---|---|
| 1 | `fa15044` | NEW `animation/safety.clj`: `normalize`, `identity?`, `inside?`, `intervals`, `apply-safety`, `widens?`, `mirror-x`, `edge-dwell` (1) |
| 2 | `df65be4` | `multi-engine` `guard-safety` and `stamp`; `output-runtime/engine-status` looks at `[:safety id]` first and gives `{:state :error :kind :safety :error "Safety error: output off" :detail msg}`; the status line shows it as it is, with `:detail` as the tooltip |
| 3 | `fb801f2` | NEW `animation/test_pattern.clj` (`content-layer`, `safety-layer`, `identify-on?`, `identify-ms`); `multi-engine` `test-pattern-mode`, `test-pattern-frame`, `show-frame`; `[:projector-ui :identify]`; `reset-session` clears the test pattern and Identify; the `frame-service` docstring |
| 4 | `1b14cc4` | `:projectors/identify`, `:projectors/identify-ended`; `multi-engine/now-ms`, a var; Z0's remove also clears `:identify` |
| 5 | `fbe0f40` | `:projectors/set-safety`, `add-blocked-area`, `remove-blocked-area`, `copy-safety`; the Undo and Redo check; the four undo labels; NEW `events/safety_guard.clj` |
| 6 | `9b8798b` | the D-34 notice after Open |
| 7 | `17a3ad7` | NEW `views/components/safety_editor.clj` (SAFETY ZONE); the calibration row becomes TEST PATTERN with Identify and its notes |
| 8 | `8b95459` | `#projectors-subtitle` mentions safety zones again; one sentence in QUICKSTART step 3 |
| – | `1424955` | `safety-round-trips-test` (§9 listed it; written last) |

### Deviations from the plan

- **`outputting?` and the shared "ask first" live in a NEW `events/safety_guard.clj`**, not in the projector handlers. `history.clj` needs them too, and requiring the projector handlers from it would risk a require cycle. It holds `outputting?`, `ask` (the shared confirm, with the parked event), `parked`, `asking?`, `growing` (the outputting projectors whose zone a change widens), `projector-name` and `names-text`. Identify uses it too.
- **`projector-frame` is split in two.** It keeps the stale check and the test-pattern branches; NEW `show-frame` does each cue's zones, the projector effects and `guard-safety`. Z3 changes `show-frame`.
- **More than one projector in a question:** an Undo that widens two says "Undo gives Laser L and Laser R a larger area while they are on." While the confirm is already open, another widening (an edit, Undo or Redo) is dropped.
- **The editor's buttons are in two rows**, [+ Add blocked area] [Reset], then [Copy to…] [Mirror to…]: §7's one row doesn't fit the panel's right column at 1200 px. **Copy to… and Mirror to… are hidden, not disabled,** when there is no other projector or the zone can't be read, so they need no note. Each lists the other projectors, and "Every other projector" when there are two or more.
- **TEST PATTERN is a small section,** not one row: its header, then [Show test pattern] [Identify], then one note, then Brightness while the pattern is on (as before). In one row the buttons were cut short. **One note at a time:** with the pattern on and streaming off, only "Not streaming: turn on Stream to see it on the laser" shows, which covers Identify too.
- **A zone that can't be read** shows "This safety zone can't be read, so the projector stays dark. Reset it to draw a new one." (`#safety-unreadable`; not in §7). The canvas draws the default zone and can't be dragged, and Add blocked area is off. `widens?` counts any readable zone as wider than an unreadable one, so Reset or a typed number asks on a live laser.
- **While a widening drag waits for its answer,** the canvas also draws the stored limit in grey under the dashed preview.
- **`add-blocked-area` cuts its 0.4 × 0.2 area to the limit** when the limit is smaller. It, `remove-blocked-area` (also with a bad index) and `copy-safety` change nothing when the zone can't be read. `copy-safety` skips the source and unknown ids.
- **The number fields** take a comma as the decimal point, and refuse a number outside -1..1, showing the stored value again. The fix for `cancelled-field-shows-the-stored-zone-test` puts the `:fx/key` (index, dialog open, stored zone) on each field in its row's children. Inside the field's own description it did nothing.
- **Tests beyond §9:** `nothing-new-lit-test` (invariant 3 on its own), `test-pattern-replaces-the-show-test`, `add-blocked-area-test`, `safety-events-have-undo-labels-test`, `escape-puts-back-the-zone-test`. Z0's `subtitle-promises-no-safety-zones-test` is now `subtitle-mentions-safety-zones-test`.

### Tests added

- `laser-show.animation.safety-test` (NEW): 17 tests, 426,124 assertions (the seeded random frames).
- `laser-show.animation.test-pattern-test` (NEW): `content-layer-is-white-at-brightness-test`, `safety-layer-outlines-stay-visible-test`, `identify-flashes-test`.
- `laser-show.backend.multi-engine-test`: `safety-runs-after-projector-effects-test`, `no-safety-zone-sends-the-same-frame-test`, `failing-safety-sends-an-empty-frame-test`, `unreadable-safety-fails-closed-test`, `failing-calibration-is-still-clipped-test`, `test-pattern-shows-with-nothing-playing-test`, `test-pattern-replaces-the-show-test`, `master-doesnt-dim-the-test-pattern-test`, `blackout-hides-the-test-pattern-test`, `blackout-leaves-a-grid-cue-alone-test`, `identify-flashes-then-stops-test`.
- `laser-show.backend.output-lifecycle-test`: `safety-error-shows-in-status-test`. `laser-show.views.tabs.projectors-test`: `output-status-text-test` gains the safety case.
- `laser-show.events.projector-handlers-test`: `outputting-is-streaming-and-wanted-test`, the four `identify-…` tests, `set-safety-normalizes-test`, `shrinking-a-live-zone-applies-at-once-test`, `growing-a-live-zone-asks-first-test`, `parked-event-carries-no-state-test`, `confirmed-growth-applies-test`, `growing-an-off-laser-applies-at-once-test`, `add-blocked-area-test`, `removing-a-blocked-area-asks-when-live-test`, `copy-and-mirror-safety-test`, `safety-events-on-a-missing-projector-change-nothing-test`.
- `laser-show.events.history-handlers-test`: `undo-that-grows-a-live-zone-asks-test`, `safety-events-have-undo-labels-test`.
- `laser-show.events.output-sync-test`: `safety-change-requests-no-refresh-test`.
- `laser-show.events.menu-file-handlers-test`: `open-says-where-safety-comes-from-test`; `new-and-open-take-the-notice-away-test` changed for Open.
- `laser-show.state.persistent-test`: `open-and-new-stop-the-test-pattern-test`, `safety-round-trips-test`.
- UI, `laser-show.ui-test.safety-editor-test` (NEW): `drag-the-limit-in-test`, `escape-puts-back-the-zone-test`, `drag-a-blocked-area-test`, `add-and-remove-a-blocked-area-test`, `number-fields-commit-test`, `growing-while-streaming-asks-test`, `cancelled-field-shows-the-stored-zone-test`, `identify-needs-stream-test`.
- UI, `laser-show.ui-test.setup-dialogs-test`: `subtitle-mentions-safety-zones-test`.

### Red first

| Step | Before the change |
|---|---|
| 1 | `safety-test` couldn't load: no namespace |
| 2 | the engine and status tests: 9 failures, 2 errors |
| 3, 4 | red only as a load or compile error that stopped the run (the functions didn't exist yet), so these tests were **never seen failing one by one** |
| 5 | the same: the run stopped because `events.safety-guard` didn't exist. `safety-change-requests-no-refresh-test` was written after the handlers and passed on its first run |
| 6 | 5 failures (4 in `open-says-where-safety-comes-from-test`, 1 in the changed notice test) |
| 7 | the editor was written **before** its tests. Their first run found one real bug: a refused field kept its text (`cancelled-field-shows-the-stored-zone-test` timed out), and one wrong number in a test. `escape-puts-back-the-zone-test` was checked red by turning off the editor's Escape handling: 1 error |
| 8 | `subtitle-mentions-safety-zones-test`: 1 error (the old text) |
| – | `safety-round-trips-test` passed on its first run: Save already writes the whole projector map |

### Verification

| Where | Unit suite (`clj -M:mac-arm:test`) | UI suite (`clj -M:test-ui:mac-arm:test-ui-headless`) |
|---|---|---|
| the branch on `989c315`, at step 8 | 1,205 tests, 460,863 assertions, 0 failures | 151 tests, 722 assertions, 0 failures |
| the same, with `safety-round-trips-test` | 1,206 tests, 460,864 assertions, 0 failures | (no change to the app or UI tests) |
| rebased onto `8b4b7d7` (vibe Phase 7 in), as landed | 1,232 tests, 461,146 assertions, 0 failures | 156 tests, 749 assertions, 0 failures |

**Not done: the real-projector check (§9).** Nothing here has been seen on a laser. Before relying on a blocked area in front of people, try one at low brightness: a line swept through it and a shape that jumps across it, at 25 and 40 kpps, and look for light bleeding into the area where the beam comes back on. That decides `edge-dwell`.

### For the merge (done in Z2's landing, 04 §13)

- Z2's cards took the device and configured lists' place, so Z1's two status changes moved into `views/components/rig_strip.clj`: `output-status-text` shows a safety error as it is, and `card-status` gives `#projector-status-<id>` the `:detail` tooltip.
- Z2's `handle-projectors-remove` also clears `[:projector-ui :identify id]`; the requires and the undo labels keep both sides.
- The subtitle is Z2's ("Put your lasers in order, left to right. The groups follow the order.", 04 §7), so `subtitle-mentions-safety-zones-test` is `subtitle-says-to-put-the-lasers-in-order-test`. QUICKSTART keeps Z1's sentence.
- The SAFETY ZONE and TEST PATTERN sections sit in the inspector's right column, below the rig strip. At the default 1200 × 800 the inspector shows only its top, so they need a scroll; `safety-editor-test` and `calibration-brightness-slider-test` scroll to them first (`ui/scroll-into-view!`).

### After the phase

- **`0002052`, Q-Z1-5 A:** while the show lock is on, a safety zone edit that makes no zone larger goes through (shrinking the limit, adding a blocked area, a copy that only shrinks), and one that would make a zone larger is refused with "Larger safety zone refused" under the switch and "Show lock is on: Larger safety zone refused. Unlock it on the Perform tab" on the status bar. The lock asks the handler what the edit would do, answered yes, so Allow on a question asked before the lock went on is refused too. `show-lock-test/locked-safety-zones-only-get-smaller-test`; red first: 14 failures.
