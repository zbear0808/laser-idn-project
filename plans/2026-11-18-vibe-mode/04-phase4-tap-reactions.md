# Phase 4: tap reactions (presses move effects, not only cues)

**Written:** 2026-11-18   **Written for commit:** `b1da0f2` (`master`)
**Status:** Done (4a `6715fb4`, 4b `f5a4f84`), 2026-11-19; as built: §10 (4a) and §11 (4b). V9 answered A with a palette (2026-11-18)   **Last verified against:** `f5a4f84` (§11)
**Supersedes / Superseded by:** with V9 A it replaces Phase 5's palette hue step (`:palette`, `[:vibe-runtime :palette-idx]`, the `:macro/set` effect)   **Related findings:** review 02 T3 (live actions must not flood undo); review 05 F18 (velocity) stays open; `plans/2026-11-17-modulator-time-base.md` §9 ("once" on the effects grid counts from the clock's start)

A press today can only start or stop a cue. This phase lets a press **move effects**: punch the size on every HIT, step the colour or the spin, scatter the position on ACCENT, swell while FLASH is held, and let the tapping rate itself drive an effect. It works for the vibe roles and for anyone playing the Grid tab with a key bound to HIT.

The design has four parts:

- **Pulses.** Every role press (HIT, ACCENT, FLASH), every vibe change and every cue start is counted in app state. The show clock stamps each one in beats on a clock of its own that Stop and Retrigger don't reset, and every effect sees the stamps when it renders.
- **"Restart on".** A new choice on every time-based modulator (the waves, Random, Decay) and on time-driven keyframe animations, so an LFO, a once-mode sweep or a keyframe animation starts again from a press.
- **Envelope, Step, Energy.** Three new modulator types in the existing modulator editor, under "On press".
- **Tap reactions.** One-click templates that make an effect cell with one of the above, in a vibe's FX set or on the effects grid.

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| What a press can do | `events/handlers/triggers.clj:59-89`; Phase 1 `:vibe/press` | start or stop cues, toggle effect cells, stop the transport, tap the tempo |
| A modulator's time | `animation/time.clj:135-141` (loop: effective beats or seconds), `:143-154` (`elapsed`), `:160-169` (once progress); `animation/modulator_evaluators.clj:143-155` (`eval-decay` uses `elapsed`) | counted from its context's start: the cue's run (`animation/cue_timing.clj:187-212`), a timeline clip (`timeline/playback.clj:28-47`), or the global clock for the effects grid (`animation/cue_timing.clj:215-243`, `services/frame_service.clj:102-114`) |
| Re-firing an envelope | probe v15 | a decay on an effects-grid cell is 1.000 after 8 beats and still 1.000 after a press; as a cue's own effect it is 1.500 only on the cue that was just (re)started |
| The only manual restart | `views/components/modulator_param_control.clj:224-239` (the "Retrigger" button), `events/handlers/modulator.clj:211-221` | dispatches `:transport/retrigger`: **every** clock restarts (probe v14: two cues and the global clock from 3.97 beats to 0.03) |
| Random | `animation/modulator_evaluators.clj:165-198` | a hash of the step number only, so every restart of a once-mode random lands on the same value (probe v16: 0.883 five times) |
| What reaches a modulator | `services/show_clock.clj:48-60` (`render-cue` adds `:input-values`); `animation/modulation.clj:42-71, 73-101` | `make-context` and `make-base-context` build their map from a fixed list of keys: a new key on the timing context never reaches an evaluator (probe v13: `:pulses` dropped by both, kept by `clip-timing-ctx`) |
| One modulator per parameter, resolved once per chain | `animation/effects.clj:417-470` (`make-param-resolver`, global path `:467-470`) | a non-per-point modulator costs one evaluation per modulated parameter each time a chain is applied, which is once per zone frame of every playing cue (`services/frame_service.clj:399-403`), never per point (probe v11: modulated intensity 41–62 µs against 38–62 µs static, 1,000 points; v17: four effects 216–228 µs) |
| The modulator editor | `views/components/modulator_param_control.clj:46-63` (type list from `modulator-registry/all-ui-modulators`, `animation/modulator_registry.clj:126-133`), `:115-141` (a `:choice` param shows `(name item)`) | a registered type and its params appear with no UI work |
| Categories | `animation/modulator_registry.clj:45-55` | `#{:wave :special :external :internal}` |
| Keyframe animations | `animation/keyframes.clj:43-60` (`calculate-time-position`), `:218-269`; `animation/effects.clj:271-273`; the panel's time settings `views/components/visual_editors/keyframe_modulator_panel.clj:202-254, 278-330`, saved by `:keyframe/update-setting` (`events/handlers/keyframe.clj:175-188, 479-482`, any key) | time-driven keyframes use the same time base as the waves |
| Effects a press could drive | `animation/effects/shape.clj:22-46` (`:scale`), `:61-81` (`:translate`), `:97-111` (`:rotation`); `animation/effects/intensity.clj:20-31` (`:intensity`); `animation/effects/color.clj:30-43` (`:hue-shift`) | all take modulators on every parameter |
| Effect cells | `services/frame_service.clj:83-100` (active cells → one chain on every cue and zone); Phase 1 FX sets (`:fx`, `[:flash :fx]`) | a vibe's FX set is on while the vibe is current; flash FX only while FLASH is held |
| Calibration effects | `services/show_clock.clj:89-91` | run at the first cue's timing, with the input values |
| Where runtime facts for the clock live | `state/domains.clj:58-87` (`playback`: intents and requests); `state/history.clj:48-50` (`[:playback]` is not a document root) | the clock reads them once per tick (`animation/show_time.clj:220-240`) |

## 2. Root cause

Nothing carries a press to the render. A press is an event on the agent; effects are evaluated on the clock thread from time alone, measured from a cue's start or the global clock's. So the only ways to make a press move an effect are to restart the cue that holds it or to restart every clock.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| Intents in `[:playback]` stamped by the clock at its next tick (`animation/show_time.clj:126-158`; `animation/cue_timing.clj:322-333`) | pulses work the same way: the handler counts, the clock stamps |
| `cue-timing/update-global-clock` (`animation/cue_timing.clj:73-118`) | advances the pulse clock; no request ever reaches it |
| The `:input-values` path into every render (`services/show_clock.clj:53`; `services/frame_service.clj:110-114`) | `:pulses` goes the same way, except into calibration |
| The registry and the modulator editor (`animation/modulators.clj`, `animation/modulator_registry.clj:61-106`, `views/components/modulator_param_control.clj:46-63, 200-260`) | three registrations and one param; the editor draws them |
| `decay-curve-factor` (`animation/modulator_evaluators.clj:130-141`) | the Envelope's release curve |
| `hash-unit` (`animation/modulator_evaluators.clj:165-175`) | Random with "Restart on" mixes in the press count |
| `:keyframe/update-setting` (`events/handlers/keyframe.clj:479-482`) | saves the keyframe "Restart on" with no new event |
| Phase 1's FX sets and `switch-to`, Phase 5's `:vibe/add-master`/`:vibe/add-morph` pattern | a reaction is an effect cell made from a template, in a vibe's FX set |
| `ext-clock-redraw` (`views/components/clock_view.clj:58-71`) | the pads' pulse bar, from the snapshot |

## 4. Changes per namespace

- `laser-show.animation.pulses` (NEW, pure): the pulse maths, shared by the clock and the evaluators, so `show_time.clj` stays about runs. `sources` (`[:hit :accent :flash :vibe :any :cue]`); `stamp [stamps app-pulses pulse-clock]`; `view [stamps pulse-clock]`; `rebase [context source loop?]`; `envelope [config pulse]`, `step-index [order steps count]`, `step-value [config pulse]`, `energy [config pulse]`.
- `laser-show.state.domains` (existing, `playback` `:58-87`): NEW `:pulses {:default {}}`: `{source {:id n :held? bool}}`.
- `laser-show.vibes.engine` (existing from Phase 1): `press` sets `[:playback :pulses role]` to `{:id (inc id) :held? true}` and `release` sets `:held? false`, for `:hit :accent :flash`; `switch-to` (every switch: a VIBE tap, `:vibe/select`, Phase 6's `:vibe/advance`) increments `[:playback :pulses :vibe :id]`. A lock toggle, a blackout, and any press during blackout write nothing. A press with no vibes at all still pulses (and taps the tempo, V3), so a Grid-tab player can bind HIT just to move effects.
- `laser-show.events.handlers.grid` (existing): `start-cue` (Phase 1's public `trigger-cue`, `:32-42`) increments `[:playback :pulses :cue :id]`: every cue start from the grid, a cell trigger or a vibe pick.
- `laser-show.animation.show-time` (existing): `initial-runtime` (`:33-43`) gets `:pulse-clock` (shaped like `:global`) and `:pulses {}`; `advance` (`:162-176`) also advances `:pulse-clock` with `cue-timing/update-global-clock`; `apply-request` (`:89-104`) is unchanged, so Stop, Retrigger, align and shift never touch the pulse clock; `step` (`:220-240`) calls `pulses/stamp` after `advance`. NEW `pulse-view [runtime]`.
- `laser-show.services.show-clock` (existing): `tick!` (`:137-162`) makes `(show-time/pulse-view runtime)` once, passes it to `render` and puts it in the snapshot as `:pulses`; `render` (`:71-92`) passes it to `frame-service/render-context`; `render-cue` (`:48-60`) puts `:pulses` on the cue's context beside `:input-values`. The calibration context (`:89-91`) gets none.
- `laser-show.services.frame-service` (existing, `render-context` `:102-114`): a 4-arity with `pulses`, also put on `:global-ctx`; the 3-arity (the fallback at `:357-361`) passes nil.
- `laser-show.animation.modulation` (existing): `make-context` (`:42-71`) and `make-base-context` (`:73-101`) carry `:pulses`. `evaluate-modulator` (`:171-182`): when the merged config's `:restart-on` isn't `:own`, the evaluator gets `(pulses/rebase context source (not= :once (:loop-mode config)))` (Decay counts as once).
- `laser-show.animation.modulator-evaluators` (existing): NEW `eval-envelope`, `eval-step`, `eval-energy` (each a call into `laser-show.animation.pulses`), added to `modulator-evaluators` (`:408-425`). `eval-random` (`:177-198`) adds `(* 7919 pulse-count)` to the hashed step when the context has `:pulse-count` (set by `rebase`), so each press gives a new value.
- `laser-show.animation.modulators` (existing): `wave-params`, `square-params`, `random-params`, `decay-params` (`:21-57`) get `{:key :restart-on :label "Restart on" :type :choice :choices [:own :hit :accent :flash :vibe :any :cue] :default :own}`. NEW `envelope-params`, `step-params`, `energy-params` (§7) and three `register-modulator!` calls, category `:pulse`, `:per-point? false`, `:retrigger? false`.
- `laser-show.animation.modulator-registry` (existing, `:45-55`): `valid-categories` gets `:pulse`.
- `laser-show.animation.keyframes` (existing, `calculate-time-position` `:43-60`): a time-driven keyframe config with `:restart-on` rebases its context first.
- `laser-show.views.components.modulator-param-control` (existing): `modulator-param-choice` (`:115-141`) shows a param def's optional `:labels` (`{:hit "HIT" :vibe "Vibe change" :any "HIT, ACCENT or FLASH" :cue "Any cue start" :own "Its cue's start" …}`), else `(name item)` as today. NEW param type `:number-list` (the Step's palette): a text field that shows the values as "200 260 320" and sends `:text-field-type :number-list`. `modulator-type-selector` (`:46-63`) leaves the `:pulse` types out when its event template's `:domain` is `:projector-effects` (the calibration editor's, `views/tabs/projectors.clj:567-582`).
- `laser-show.events.handlers.modulator` (existing, `handle-update-mod-param` `:160-209`): `:text-field-type :number-list` splits the text on commas and spaces into a vector of doubles, ignoring what doesn't parse ("" clears the palette).
- `laser-show.views.components.visual-editors.keyframe-modulator-panel` (existing): the two time-driver settings rows (`:202-254`, `:278-330`) get a **Restart on** combo, `:keyframe/update-setting {:setting-key :restart-on}`.
- `laser-show.vibes.reactions` (NEW, pure): `templates` (§7), `reaction-cell [kind vibe-name]`, `first-empty-effect-cell [state]`. Phase 5's `:vibe/add-master` and `:vibe/add-morph` use the same `first-empty-effect-cell`.
- `laser-show.vibes.library` (existing from Phase 1): NEW `listens-to [chain]` (the sources any effect in it reacts to: a `:pulse` type's `:source`, a `:restart-on`, a keyframe's `:restart-on`); `reactions [state vibe-id]` (`{role [{:cell :name}]}` over the vibe's FX set, its flash FX, the active effects-grid cells and the effects of its pool cues). `lint` (Phase 3) gets the rows in §7.
- `laser-show.events.handlers.vibes` (existing): NEW `:vibe/add-reaction {:vibe :kind}`: the template in the first empty effect cell, named "Groove · Punch size", added to the vibe's `:fx` (`:strobe` to `[:flash :fx]`); switched on now if the vibe is current (and recorded in `[:vibe-runtime :fx-on]`, Phase 1, so leaving the vibe switches it off).
- `laser-show.events.handlers.effects` (existing): NEW `:effects/new-reaction {:col :row :kind}`: an empty effects cell becomes the template, `:active? true`. This is the Grid-tab way in, without vibes.
- `laser-show.events.handlers.history` (existing, `event-descriptions` `:125-147`): both events "Add Tap Reaction".
- `laser-show.views.components.grid-cell` (existing, the effects-cell menu `:326-360`): **New tap reaction ▸** (the templates, grouped HIT, ACCENT, FLASH, VIBE, any: ten here, four more from Phase 8) on an empty cell.
- `laser-show.views.tabs.vibes` (Phase 3): a **Tap reactions ▸** menu in each role's section (and one beside the vibe's name for the VIBE reaction, Fade in), and the list of cells that react to that role, from `library/reactions`.
- `laser-show.views.components.vibe-pads` (Phase 2): under each pad a **pulse bar** drawn by `ext-clock-redraw` from the snapshot's `:pulses` (full on a press, fading over half a beat, so even a 20 ms tap shows), and a line naming the pad's reactions; "effects only" when the pool has nothing playable but the role has reactions. The VIBE pulse flashes the header.
- `laser-show.subs` (existing): `vibe-pad` (Phase 2) gains `:reactions` and `:effects-only?`, memoized on the chains and the library.
- `laser-show.css.vibes` (Phase 2): `.vibe-pad-pulse`.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:playback :pulses]` (NEW) `{:hit {:id 12 :held? false} :vibe {:id 3} :cue {:id 40} …}` | app state, session. `[:playback]` is not a document root (`state/history.clj:48-50`) | the vibe engine (`:vibe/press`, `:vibe/release`, `switch-to`); `grid/start-cue` | no / no |
| `:pulse-clock`, `:pulses` in `show-time/!runtime` (NEW) | the clock's runtime atom | `show-time/step`, only | – |
| `:pulses` in the snapshot (NEW) | the published snapshot | `show-clock/tick!` | – |
| `:restart-on`, the three new types inside effect params | document (effect chains, cue chains, timelines) | the existing modulator and keyframe events | yes / yes |
| reaction cells | document (`[:chains :effect-chains]`); `:active?` is live (`state/history.clj:19-21`) | `:vibe/add-reaction`, `:effects/new-reaction` | yes / yes (adding the cell) |

The press itself stays off undo and dirty: Phase 1's vibe events carry `:source-trigger`, and `[:playback]` isn't a document root. High-frequency data stays out of app state: the ages are computed on the clock thread and never written back.

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:vibe/press`, `:vibe/release`, `:vibe/select` | Phase 1 | Phase 1's trigger mapping and pads; they gain the pulse write | yes |
| `:vibe/add-reaction {:vibe :kind}` | NEW | the Vibes tab's Tap reactions menu (`views/tabs/vibes.clj`, Phase 3) | yes |
| `:effects/new-reaction {:col :row :kind}` | NEW | the effects-cell menu (`views/components/grid_cell.clj:326-360`) | yes |
| `:modulator/set-type`, `:modulator/update-param` | yes (`events/handlers/modulator.clj:256-268`) | the modulator editor | yes |
| `:keyframe/update-setting` | yes (`events/handlers/keyframe.clj:479-482`) | the keyframe panel's Restart on combo | yes |
| `:modulator/retrigger` | yes, unchanged | the editor's Retrigger button | yes |

No new effect key.

## 7. Data examples and invariants

### What a press writes (app state)

| Event | `[:playback :pulses]` after |
|---|---|
| HIT press (count was 11) | `:hit {:id 12 :held? true}` |
| HIT release | `:hit {:id 12 :held? false}` |
| FLASH press, then release 2 beats later | `:flash {:id 1 :held? true}`, then `{:id 1 :held? false}` |
| VIBE tap (release under 600 ms), `:vibe/select`, or an auto-advance | `:vibe {:id (inc id)}` |
| VIBE hold (lock toggle) | unchanged |
| any press while blackout is on | unchanged |
| a grid click that starts a cue, a cell trigger, a vibe pick | `:cue {:id (inc id)}` |

### What the clock stamps (`pulses/stamp`, in `step`)

Pulse clock at 120 BPM. `:recent` holds press beats, newest first, at most 16.

| App state `:hit` | Stamp before | Pulse clock now | Stamp after |
|---|---|---|---|
| `{:id 1 :held? true}` | none | 10.0 beats | `{:id 1 :count 1 :press-beat 10.0 :press-ms 5000.0 :held? true :release-beat nil :recent [10.0]}` |
| `{:id 1 :held? false}` | id 1, held | 10.2 | `:held? false :release-beat 10.2` |
| `{:id 1 :held? false}` | id 1, released | 10.3 | unchanged |
| `{:id 3 :held? true}` | id 1 | 11.0 | `:id 3 :count 3 :press-beat 11.0 :recent [11.0 11.0 10.0]`: two presses in one tick count twice |
| `{:id 4 :held? false}` | id 3 | 12.0 | `:id 4 :count 4 :press-beat 12.0 :held? false :release-beat 12.0 :recent [12.0 11.0 11.0 10.0]`: a press and its release inside one tick, as from a pad that sends its note-off at once (a 10 ms tap is inside one 16.7 ms tick about 4 times in 10). The Envelope plays its attack, then its release |
| any | any, and a `:restart` request in the same tick (Stop, Retrigger) | | the pulse clock and every stamp unchanged |

`pulse-view` at pulse clock 10.5, after row 2: `{:hit {:count 1 :beats 0.5 :ms 250.0 :held? false :since-release 0.3 :recent [0.5]}}`. A source with no press yet is absent. `:any` is derived from `:hit`, `:accent` and `:flash`: the counts summed, the newest press's ages, the three `:recent` merged.

### Restart on (`pulses/rebase`)

The context the evaluator sees: `:accumulated-beats` and `:effective-beats` = beats since the press, `:accumulated-ms` and `:time-ms` = ms since it, `:phase-offset 0.0`, `:pulse-count` = the count. With no press yet: a loop-mode modulator gets its context unchanged (it runs as today until the first press), a once-mode one or a Decay is "done" (at rest).

| Modulator | Press | Today (`:own`) | With `:restart-on :hit` |
|---|---|---|---|
| Triangle once, 0→30°, period 1, phase 0.5 | none yet | swings once when its cue starts | 0° (at rest) |
| the same | HIT, 0.5 beats ago | at rest after the first beat of its cue | 30° (the top of the swing); 0° again 1 beat after the press |
| Sine loop, period 4 | HIT 1 beat ago | phase from its cue's start | phase 0.25: every HIT restarts the sweep |
| Decay 1.5 → 1.0 over 1 beat, exp | HIT 0.5 beats ago, on an effects-grid cell | 1.000 for ever (v15) | 1.038 |
| Random once, period 1 | five HITs | 0.883 every time (v16) | a different value after each press |
| Square loop 0↔1, period 1/8, duty 0.5 (the Strobe template) | FLASH press | phase from the cell's start | the first flash starts on the press |
| Time-driven keyframes, once, period 2 | ACCENT 1 beat ago | from its cue's start | position 0.5 of the animation |

### Envelope (`:envelope`, "Punch or hold on each press")

Params: `:source` (default `:hit`), `:min` (rest), `:max` (peak), `:attack` (beats 0–4, default 0.0), `:release` (beats 0.0625–16, default 1.0), `:curve` (`:linear :exp :log`, default `:exp`), `:hold?` (default true: stay at the peak while the key is down).

Value at `a` beats since the press: no press → `:min`; `a < attack` → linear from min to max; held and `:hold?` → `:max`; otherwise the release, from `start = max(attack, a − since-release)` (or `attack` when `:hold?` is false or the source has no release, `:vibe` and `:cue`), at progress `p = (a − start) / release`, value `min + decay-curve-factor(curve, p) · (max − min)`.

| Config | Press | Value |
|---|---|---|
| Punch size: `:min 1.0 :max 1.25 :release 0.5 :curve :exp :hold? false` | none | 1.000 |
| the same | 0 beats ago | 1.250 |
| the same | 0.1 beats ago | 1.091 |
| the same | 0.25 beats ago | 1.019 |
| the same | 0.5 beats ago or more | 1.000 |
| Swell: `:min 1.0 :max 1.4 :attack 0.5 :release 1.0 :curve :linear :hold? true`, FLASH | pressed 0.25 beats ago, held | 1.200 |
| the same | held for 2 beats | 1.400 |
| the same | released 0.5 beats ago (pressed 2.5 ago) | 1.200 |
| the same | released 0.2 beats after the press, now 1.0 beat after it | 1.200 (the attack ends at 0.5, then the release runs) |
| Fade in: `:min 1.0 :max 0.0 :release 1.0 :curve :linear :hold? false`, VIBE | no switch yet | 1.000 |
| the same | a switch 0 beats ago | 0.000 (the peak is below the rest: a dip) |
| the same | a switch 0.25 beats ago | 0.250 |
| the same | a switch 1 beat ago or more | 1.000 |

### Step (`:step`, "Next value on each press")

Params: `:source` (default `:hit`), `:min`, `:max`, `:steps` (2–16, default 4), `:order` (`:up :down :ping-pong :scatter`, default `:up`), `:wrap?` (default false: the range is a line; true: a circle, so `max` ≡ `min`), `:glide` (beats 0–4, default 0.0), `:values` (the **palette**, a list of numbers, default empty).

Positions: the palette's values in the order typed, when it isn't empty (then `n` is its length and `:min`, `:max` and `:steps` are not used); otherwise `min + i·(max − min)/(steps − 1)`, or `/steps` with `:wrap?`. Index at count `k` (0 before the first press): `:up` `k mod n`; `:down` `(n − 1) − (k mod n)`; `:ping-pong` `i = k mod (2n − 2)`, `i < n ? i : 2n − 2 − i`; `:scatter` `(⌊n/2⌋ + ⌊frac(k·φ)·n⌋) mod n` with φ = 0.6180339887 (for n = 2, as `:up`; for n = 1 the one value). A glide moves from the last position to the new one over `:glide` beats (smoothstep), the shorter way round with `:wrap?` (for a palette, the circle is 360, so hues glide the short way).

| Config | Count 0 1 2 3 4 5 6 7 8 |
|---|---|
| Step spin: `:min 0 :max 360 :steps 8 :wrap? true` | 0 45 90 135 180 225 270 315 0 |
| Step colour: `:min 0 :max 180 :steps 4` (hue shift) | 0 60 120 180 0 60 120 180 0 |
| A palette: `:values [200 215 320]` (set hue) | 200 215 320 200 215 320 200 215 320 |
| A palette in ping-pong: `:values [0 90 270] :order :ping-pong` | 0 90 270 90 0 90 270 90 0 |
| Ping-pong, `:min 0 :max 3 :steps 4` | 0 1 2 3 2 1 0 1 2 |
| Flip: `:min -1 :max 1 :steps 2 :order :down` (x-scale) | 1 −1 1 −1 1 −1 1 −1 1 |
| Scatter: `:min -0.4 :max 0.4 :steps 9 :order :scatter` (x) | 0.0 −0.4 0.2 −0.2 0.4 0.0 −0.3 0.2 … |
| Step spin with `:glide 0.25`, count 8, 0.125 beats after the press | 337.5 (half way from 315 forward to 360 ≡ 0) |

### Energy (`:energy`, "Rises as you tap, falls when you stop")

Params: `:source` (default `:any`), `:min`, `:max`, `:rise` (per press, 0.05–1, default 0.25), `:fall` (per beat, 0.01–2, default 0.25). Over the `:recent` presses, oldest first: `e ← clamp01(e − fall·Δbeats + rise)`; then `e ← max(0, e − fall·age-of-newest)`; value `min + e·(max − min)`.

| Taps (`:min 1.0 :max 1.4`, rise 0.25, fall 0.25) | Value at the last tap |
|---|---|
| none | 1.000 |
| one | 1.100, back to 1.000 one beat later |
| one per beat | 1.100 at each tap, 1.000 just before the next |
| one per half beat, the 4th | 1.250 |
| one per quarter beat, the 5th and later | 1.400 (full) |

### The templates (`vibes.reactions/templates`)

| Kind | Menu | Role | Effect ← modulator | At rest | Goes into |
|---|---|---|---|---|---|
| `:punch-size` | Punch: size | HIT | `:scale` x and y ← Envelope 1.0→1.25, release ½ beat, exp, no hold | identity | FX set |
| `:pump` | Pump: brightness (35 % between hits) | HIT | `:intensity :amount` ← Envelope 0.35→1.0, release ¾ beat, exp, no hold | **35 % by design** | FX set |
| `:step-colour` | Step: colour | HIT | `:hue-shift :degrees` ← Step 0→180, 4 steps | identity | FX set |
| `:step-spin` | Step: spin | HIT | `:rotation :angle` ← Step 0→360, 8 steps, wrap, glide ¼ beat | identity | FX set |
| `:flip` | Flip | ACCENT | `:scale :x-scale` ← Step −1→1, 2 steps, down | identity | FX set |
| `:scatter` | Scatter: position | ACCENT | `:translate :x` ← Step −0.4→0.4, 9 steps, scatter | identity | FX set |
| `:swell` | Swell while held | FLASH | `:scale` x and y ← Envelope 1.0→1.4, attack ½ beat, release 1 beat, linear, hold | identity | FX set (so the release tail plays after FLASH lets go) |
| `:strobe` | Strobe while held | FLASH | `:intensity :amount` ← Square 0↔1, period ⅛ beat, Restart on FLASH | off: the cell is only on while FLASH is held | flash FX |
| `:fade-in` | Fade in | VIBE | `:intensity :amount` ← Envelope on `:vibe`, rest 1.0, peak 0.0, release 1 beat, linear, no hold | identity (full until the vibe's first switch) | FX set |
| `:energy-size` | Energy: size | any | `:scale` x and y ← Energy 1.0→1.4, rise 0.2, fall 0.25 | identity | FX set |

Phase 8 adds four zone reactions to this table (`:zone-hop`, `:zone-swap`, `:zone-shift`, `:zone-chase`: `08-phase8-zone-effects.md` §7). They are made by the same `:vibe/add-reaction`, and Step and Restart on drive them like any other parameter.

```clojure
;; :vibe/add-reaction {:vibe :vibe-2 :kind :punch-size}, effects-grid [0 3] taken by Master, [1 3] empty
[:chains :effect-chains [1 3]]
;; => {:name "Groove · Punch size" :active? false
;;     :items [{:id #uuid "…" :effect-id :scale :enabled? true
;;              :params {:x-scale {:type :envelope :active? true :source :hit :min 1.0 :max 1.25
;;                                 :attack 0.0 :release 0.5 :curve :exp :hold? false}
;;                       :y-scale {:type :envelope :active? true :source :hit :min 1.0 :max 1.25
;;                                 :attack 0.0 :release 0.5 :curve :exp :hold? false}}}]}
[:config :vibes :library :vibe-2 :fx]   ;; => [… [1 3]]
```

To combine two reactions on one parameter, stack two effects (two `:scale` effects multiply). An effect cell holds a chain, so one reaction can also move several effects on one press (Punch size, Step spin and a hue Envelope in one cell make an "Impact"); each effect in the chain costs its own share (invariant 12).

### Lint rows (Phase 3's table gains)

| Level | Condition | Message |
|---|---|---|
| error (replaces "HIT has no cues to play") | a role with no playable cue **and** no reaction | "Groove: HIT does nothing (no cues, no tap reactions)" |
| info | a role with no playable cue but a reaction | "Groove: HIT moves effects only (Punch size)" |
| warn | a reaction listens to a role with no key bound | "Groove · Scatter listens to ACCENT, which has no key yet" |

### Invariants

1. A press changes no document root and adds no undo step.
2. Until a source's first press, every Envelope, Step and Energy sits at rest and every loop modulator with Restart on runs as today. No phantom punch at start-up or after Stop.
3. A saved modulator without `:restart-on` (every project today) evaluates exactly as before.
4. Calibration effects never react to a press, and the calibration editor doesn't offer the `:pulse` types.
5. Stop, Retrigger, a tap's beat alignment and a Link shift never change a pulse's age.
6. A Step in `:scatter` order with 3 or more steps never gives the same position twice in a row (from φ: consecutive `frac(k·φ)` differ by 0.382 or 0.618, so `n ≥ 3` puts them in different bins).
7. A press reaches every listener at the same tick, and that is the tick its own cue starts. Punch and cue stay together.
8. Pulse evaluation is pure: the same context and config always give the same value. Randomness comes from the press count only.
9. During blackout no press pulses.
10. Two presses in one tick count twice (count and energy), stamped at that tick.
11. Adding a reaction is one undo step. Every template is identity at rest except Pump, whose menu name says its rest level. "Identity" means the same frame to within 1e-9, not `=`: Hue Shift goes through HSV and is 5.6e-17 off at 0°, while Intensity, Scale, Rotation and Translate are exactly neutral at their rest values (probe v17).
12. Cost: the new types resolve once per effect parameter per chain, the path `:midi` takes (`animation/effects.clj:467-470`). Probe v17 ran that path with `:midi` standing in: on a 1,000-point frame one effect costs 53–70 µs and 240–310 KB of garbage, four effects 216–228 µs and about 1.5 MB, **and the same when every effect is at rest**: an effect at its rest value is not skipped. It is paid for every zone frame of every playing cue (`services/frame_service.clj:399-403`), so four cues in one zone with a four-effect FX set is about 0.9 ms of the 16.7 ms tick and 6 MB of garbage, and twice that with two zones. *Unverified* with the real types and under show load: re-run v17 with an `:envelope`, a `:step` and an `:energy` once they exist and write the numbers here. `pulse-view` makes at most seven small maps per tick.
13. An Envelope's peak may be below its rest (Fade in starts dark and rises to full). Phase 4 registers `:min` and `:max` with no rule between them.

## 8. Tests (named test vars + fixtures)

Pure first; states from `domains/build-initial-state` and the starter cues and vibes, clock steps by hand with `show-time/step`, as Phase 1 does.

- `laser-show.animation.pulses-test` (NEW): `stamp-new-press-test`, `stamp-release-test`, `two-presses-in-one-tick-count-twice-test`, `press-and-release-in-one-tick-test` (the last stamp row: held? false and `:release-beat` both at the press beat, and a Swell still plays its attack and then its release), `pulse-clock-ignores-restart-align-and-shift-test`, `view-ages-test`, `any-merges-the-three-roles-test`, `rebase-test` (the Restart on table), `no-press-yet-is-at-rest-test`, `recent-is-capped-at-16-test`, `envelope-examples-test`, `envelope-release-during-attack-test`, `envelope-peak-below-rest-test` (the Fade in rows), `step-orders-test`, `step-palette-test` (the two palette rows), `step-glide-and-wrap-test`, `scatter-never-repeats-test` (steps 3–16, 10,000 presses), `energy-examples-test`.
- `laser-show.events.modulator-handlers-test` (existing): `number-list-param-parses-the-palette-test` ("200, 215 320" → `[200.0 215.0 320.0]`; bad tokens dropped; "" → `[]`).
- `laser-show.animation.modulation-test` (existing): `make-context-carries-pulses-test` (probe v13, turned around), `restart-on-once-and-decay-test` (v15), `restart-on-loop-resets-the-phase-test`, `restart-on-random-gives-a-new-value-per-press-test` (v16), `restart-on-own-is-unchanged-test`; `ui-modulator-list-test` (`:195`) also expects `:envelope :step :energy`. `laser-show.animation.keyframes-test` (existing): `keyframe-restart-on-press-test`.
- `laser-show.animation.show-time-test` (existing): `step-stamps-pulses-test`, `restart-request-keeps-pulses-test`.
- `laser-show.services.show-clock-test` (existing): `render-context-carries-pulses-test`, `projector-effects-never-see-pulses-test`; `snapshot-shape-test` (`:190`) gains `:pulses`; `one-state-and-input-read-per-tick-test` (`:341`) must pass unchanged.
- `laser-show.services.frame-service-test` (existing): `timeline-clip-effects-see-pulses-test` (clips keep them, v13).
- `laser-show.vibes.engine-test` (existing from Phase 1): `role-press-and-release-write-pulses-test`, `every-vibe-switch-pulses-test` (tap, select, advance), `lock-toggle-writes-no-pulse-test`, `blackout-writes-no-pulse-test`, `empty-pool-still-pulses-test`, `no-vibes-still-pulses-test`.
- `laser-show.events.handlers.grid-trigger-test` (existing): `cue-start-pulses-test` (click, cell trigger, momentary).
- `laser-show.vibes.reactions-test` (NEW): `every-template-is-valid-test` (registered effect and modulator types, params in range), `templates-are-identity-at-rest-test` (renders a cue through `frame-service/generate-frames-by-zone` with and without each cell, as Phase 5's master test does, and compares the frames to within 1e-9, invariant 11; Pump checked at 35 %), `punch-scales-then-returns-test` (frames at 0, 0.25 and 0.5 beats after a press), `strobe-goes-into-flash-fx-test`, `fade-in-starts-dark-and-returns-to-full-test` (a vibe switch that also switches the cell on: frames at 0, 0.5 and 1 beat after it are dark, half and full, and no frame is at full brightness before the dip, because the cell and the pulse arrive in the same tick, invariant 7).
- `laser-show.vibes.library-test` (existing): `reactions-index-test`, `lint-effects-only-role-is-info-test`, `lint-reaction-on-an-unbound-role-test`.
- `laser-show.events.handlers.vibes-test` (existing): `add-reaction-uses-the-first-empty-effect-cell-test`, `add-reaction-to-the-current-vibe-switches-it-on-test`, `add-reaction-is-one-undo-step-test`. `laser-show.events.effects-handlers-test` (existing): `new-reaction-fills-an-empty-cell-test`. The history-label test (`events/history_handlers_test.clj:252-257`) passes with the two labels.
- UI, `laser-show.ui-test.modulator-editor-test` (NEW; `with-toolkit` once, `with-app` each): `pulse-types-are-in-the-dropdown-test`, `source-choice-shows-its-labels-test`, `calibration-editor-hides-pulse-types-test`. `laser-show.ui-test.grid-test` (existing): `new-tap-reaction-on-an-effects-cell-test`. `laser-show.ui-test.perform-test` (Phase 2): `pad-pulse-bar-flashes-on-a-press-test`, `pad-names-its-reactions-test`, `effects-only-pad-says-so-test`. `laser-show.ui-test.vibes-test` (Phase 3): `tap-reaction-menu-adds-a-cell-to-the-vibe-test`.
- Evidence: invariant 12's re-run of probe v17 with the real Envelope, Step and Energy types (the `:midi` stand-in numbers are in `00-overview.md` §3), written here.

Run: `clj -M:mac-arm:test pulses modulation keyframes show-time show-clock frame-service engine grid-trigger reactions library vibes effects history`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.modulator-editor-test`, `-n laser-show.ui-test.grid-test`, then the Phase 2 and 3 namespaces, then both full suites.

## 9. Out of scope / alternatives considered

- **A handler that writes the effect parameter on each press** (`:chain/update-param` per tap): rejected. Every tap would be an undo step and a document change (review 02 T3), and it can't make an envelope, which needs time.
- **Pulses in the input store**, beside the faders: rejected. The store holds values, not events; a step needs a count and an envelope a stamp, and whether a press counts (blackout, roles) is decided by the engine on the agent.
- **Back-dating a stamp to the input's own timestamp**: rejected for now. The cue the same press starts begins at the tick, and stamping both at the tick keeps them together (invariant 7). Revisit with the input-timestamp work (review 02 #10).
- **Velocity → envelope depth**: later, with review 05 F18.
- **A fader that scales a reaction** (Morph → punch depth): no nested modulation; stack a fader-driven effect on the reaction instead (Phase 5).
- **A colour-swatch editor for a palette**: later; a palette is typed as numbers ("200 215 320").
- **"Next keyframe on each press"** (a count driver for keyframes): later; Step covers the common case.
- **The Retrigger button restarting only its own modulator**: unchanged here (v14); with Restart on, a press is the targeted restart.
- **Pulses into calibration effects**: never (invariant 4).
- **A fade on BLACKOUT**: rejected. The one sure kill is instant (design rule 5, V2), and BLACKOUT never pulses (invariant 9). A slow fade-out is the Intensity fader at 0, or a Fade-out reaction on a later key.
- **More templates**: not in the first set. A Lens bulge on HIT (Lens Distortion `:k1` ← Envelope, a small range), a Comet flare on HIT (a Trace Fade running all the time, its `:min-brightness` ← Envelope 0→1, so each hit lights the whole shape and it settles back to the comet) and a Saturation pulse on ACCENT are each one Envelope on one parameter that the modulator editor offers, so they can be built the day Phase 4 lands and promoted to templates after a look on hardware. A comet that *restarts* on a press is not among them: its head position comes from the cue's clock (`animation/effects/intensity.clj:115-140`) and has no parameter a press can drive.

## 10. As built: 4a (2026-11-19)

Committed as `6715fb4` on top of `739bd74` (built as `12455cd` on branch `vibe-phase4` from `f9fc352`, with Phases 1 and 2 and UX Phase 1 in it; the cherry-pick past vibe Phase 3 and UX Phase 2 conflicted only where both added to the same place: the edit events and labels in `events/handlers/vibes.clj` and `history.clj`, and new tests at the ends of `vibes_test.clj`, `subs_test.clj`, `engine_test.clj` and `grid_test.clj` (and its imports), and the fuzz test's docstring. Each was resolved by keeping both sides, and the focused unit tests then passed: 496 tests, 3,090 assertions, 0 failures). §1–9 cite `b1da0f2`; the lines below are at `12455cd`: the same in `6715fb4` except in the files the other phases also changed (css/vibes.clj, events/handlers/history.clj, events/handlers/vibes.clj, state/domains.clj, subs.clj, vibes/engine.clj, views/components/grid_cell.clj, events/handlers/vibes_test.clj, subs_test.clj, ui_test/grid_test.clj, vibes/engine_fuzz_test.clj, vibes/engine_test.clj), where they are shifted by their lines. 4a is everything but the Vibes tab: its menus and lists, and the lint rows, need Phase 3 (what is left is at the end).

**Where it is now** (paths under `src/laser_show/`)
- `animation/pulses.clj` (NEW, pure): `sources` `:24`, `seed` `:40`, `stamp` `:69`, `view` `:105`, `rebase` `:132`, `envelope` `:158`, `step-index` `:183`, `step-value` `:230`, `energy` `:249`.
- `animation/show_time.clj`: `initial-runtime` has `:pulse-clock` and `:pulses` (`:46`); `advance` moves the pulse clock (`:183`); `step` stamps after `advance` (`stamp-pulses` `:234`); `pulse-view` `:267`; `reset-runtime!` `:358` also seeds the stamps from app state. `apply-request` is unchanged (its docstring says the pulse clock is never touched).
- `services/show_clock.clj`: `render-cue` puts `:pulses` beside `:input-values` (`:58`); `tick!` makes the view once (`:158`) and puts it in the snapshot (`:164`); the calibration context gets none. `services/frame_service.clj`: `render-context` `:102`, a 4-arity whose `:pulses` also goes on `:global-ctx` (`:117`, `:121`); the 3-arity passes nil.
- `animation/modulation.clj`: `make-context` `:43` and `make-base-context` `:76` keep `:pulses`; `evaluate-modulator` `:175` rebases on `:restart-on`. `animation/modulator_evaluators.clj`: `eval-random` mixes in the press count (`:189`); `eval-envelope` `:197`, `eval-step` `:202`, `eval-energy` `:207`. `animation/modulators.clj`: public `restart-on-param` `:31` (the keyframe panel shares it), `source-param` `:38`, the three param vectors `:86`, `:96`, `:107`, registrations `:224`, `:235`, `:246`. `animation/modulator_registry.clj:51`: `:pulse`. `animation/keyframes.clj`: `calculate-time-position` `:44`. `animation/time.clj`: `decay-curve-factor` `:218` (moved, see below).
- `vibes/engine.clj`: `pulse-press` `:107`, `pulse-release` `:113`, `release-pulses` `:121`, `change-vibe` `:191`, `with-tap` `:235`; `press` `:425`, `release` `:440`, `select` `:454`, `release-all` `:462`; BLACKOUT lets go of the pulse holds (`:415`). `events/handlers/grid.clj`: `start-cue` pulses `:cue` (`:50`).
- `vibes/reactions.clj` (NEW): `templates` `:33`, `template` `:68`, `reaction-cell` `:73`, `first-empty-effect-cell` `:83`, `add-to-vibe` `:96` (what `:vibe/add-reaction` does), `listens-to` `:155`, `reactions` `:183`.
- `events/handlers/vibes.clj`: `pad-holds?` `:42` (the pad's release gate), `:vibe/add-reaction` `:92`. `events/handlers/effects.clj`: `handle-effects-new-reaction` `:88`, routed `:198`. `events/handlers/history.clj:149-150`: both "Add Tap Reaction". `events/handlers/modulator.clj`: `parse-number-list` `:160`, used for `:text-field-type :number-list` (`:204`). `state/domains.clj:86`: `[:playback :pulses]`.
- `views/components/modulator_param_control.clj`: `modulator-choices` `:47`; ids `modulator-type-<param>` and `modulator-<param>-<key>` (`param-id` `:77`) on the type, choice, slider, checkbox and list controls; `:labels` in `modulator-param-choice`; `number-list-text` `:164`, `modulator-param-number-list` `:171`; no Restart on for `:projector-effects` (`:266`); the `:number-list` case `:314`. `views/components/visual_editors/keyframe_modulator_panel.clj`: the Restart on combo `#keyframe-restart-on` (`:266`), its prop (`:509`).
- `views/components/grid_cell.clj`: `new-reaction-menu` `:319`, on an empty effects cell's menu (`:385`). Nothing else in the file changed but one require.
- `views/components/vibe_pads.clj`: `redraw-pulse!` `:30`, "Effects only" `:42`, the reactions line `#vibe-pad-<role>-reactions` `:118`, the pulse bar `#vibe-pad-<role>-pulse` `:134`. `views/tabs/perform.clj:102`: `#vibe-header-flash`, over the header. `css/vibes.clj`: `.vibe-header-flash` `:28`, `.vibe-pad-reactions` `:116`, `.vibe-pad-pulse` `:122`.
- `subs.clj` (inside Phase 2's Vibe mode section): `vibe-held?` `:970`, `reaction-inputs` `:1001`, `vibe-reactions` `:1007`, `vibe-pad-reactions` `:1025`, `vibe-pad` `:1034` (two keys added).

**Differences from the plan**

| Plan | As built | Why |
|---|---|---|
| `listens-to`, `reactions` in `vibes/library.clj` | in `vibes/reactions.clj` | Phase 3 rewrites `library.clj` (the brief) |
| a pulse is `{:id n :held? bool}` | the same, plus `:src` (`:input` or `:pad`) while held, dropped at the release | a press that plays nothing leaves no engine hold, so the pad's release gate (Phase 2 lets a pad's release through only while the pad's own press holds the role) and `:vibe/release-all {:only}` read the pulse's `:src` (`pad-holds?`). Without it an effects-only pad's release never reached the engine and its pulse stayed held (red below) |
| `release` acts only when the role is held | it lets go of the pulse whenever the pulse is held; the engine's `:held` is as Phase 1 left it | brief note 1: a press that plays nothing must still end its hold. The pad also lights while its pulse is held (`vibe-held?`), so an effects-only press shows |
| a blackout writes nothing | BLACKOUT lets go of every pulse hold, no id moves | it clears the engine's holds; a Swell held when BLACKOUT came on would be at its peak when it went off. Phase 1's "a release with nothing held changes nothing" stays exact during blackout |
| `switch-to` pulses `:vibe` | `change-vibe` (VIBE tap, `:vibe/select`) pulses it when the vibe changes, outside blackout; `ensure-current` doesn't | a press that makes the first vibe current (or falls back from a vanished one) isn't a change: a Fade in would make the session's first HIT dark. Phase 6's `:vibe/advance` should call `change-vibe` |
| — | an empty-pool or no-vibes HIT taps the tempo; Phase 1's `empty-pool-reports-a-problem-test` now expects the tap and the pulse | brief note 1 (V3) |
| Energy: `e ← clamp01(e − fall·Δbeats + rise)` | `e ← clamp01(max(0, e − fall·Δbeats) + rise)` | as written, a press after a pause longer than `e/fall` adds nothing (one tap, 8 beats, a tap: 1.0, not 1.1). §7's rows give the same values either way |
| `decay-curve-factor` stays in the evaluators | moved to `animation/time.clj`; `modulator_evaluators.clj:129` keeps a public alias | `pulses` can't require the evaluators, which require it; probe v18 calls the alias |
| stamps (§7) | `:count` grows by how far the id moved, at least 1 (ids start again with a new project); a source app state no longer has is forgotten; `reset-runtime!` with state seeds them (`pulses/seed`), as it counts requests applied | restarting the clock replays no press |
| `pulse-view` example: `{:hit …}` | also `:any`, whenever HIT, ACCENT or FLASH has a press | `:any` is derived |
| no press yet: a once-mode one is "done" | beats and ms set to 1e9 | finite: no NaN in the phase maths |
| `first-empty-effect-cell` | the bottom row first, left to right, then the row above | §7's example ([0 3] taken, [1 3] chosen) and Phase 5's Master at [0 3] |
| `:effects/new-reaction`: the template, `:active? true` | the Strobe starts off; a cell with no items left counts as empty, one with a disabled effect doesn't | on, the Strobe would strobe for ever (it is meant for the flash FX); a disabled effect is content |
| the calibration editor leaves the pulse types out | in the control (`modulator-choices`), and it leaves out Restart on too; the UI test mounts the control as that editor would | the projector tab shows no modulator controls (`views/tabs/projectors.clj` uses `param-controls-list`), so it is true by construction today |
| the keyframe panel's two time rows | one combo, in `time-settings-row` | `settings-row` (`:278-330` at `b1da0f2`) is drawn nowhere; left alone |
| the types "under On press" | sorted together by their category; their descriptions start "On press:" | the dropdown has no headings |
| `reactions`: `{role [{:cell :name}]}` | `{role [{:grid :effects\|:cues :cell :name}]}`; the name without the vibe's own prefix; a `:cue` source is no role's; the bed's effects count; an effects-grid cell that is on counts in every vibe | an effect cell and a cue cell can share coordinates; the lists sit under their vibe; a cue start isn't a press of a role |
| `vibe-pad` `:effects-only?` | true when HIT or ACCENT has no playable cell, or FLASH no playable cue, and the role has reactions; "Effects only" before "Nothing to play" | |
| — | labelled choice combos are 170 px wide; the Envelope's `:min` and `:max` read "Rest" and "Peak"; `:source` reads "Follows" | "HIT, ACCENT or FLASH" was cut |

**Found, not done**
- A pad pressed after a key that played nothing ends that pulse at the pad's release, before the key's (two sources, one role).
- A Step whose count jumps by two in one tick glides from a position never shown; a press during a glide starts the next glide from the last position, not from where the glide was.
- An Envelope (any new modulator) takes the parameter's bounds as `:min`/`:max` when chosen (`modulator_defs.clj`): a Scale's starts at rest −5, peak 5. The templates set their own.
- Probes v15 and v16 still print the old numbers: they build their modulators without `:restart-on`. v13 now prints `true` three times. The "after" of each is a test below.

**Tests**: 61 new (unit 951 → 1003, and 9 UI)
- NEW `laser-show.animation.pulses-test` (19): the 18 of §8, and `forgotten-source-test`.
- NEW `laser-show.vibes.reactions-test` (8): `every-template-is-valid-test`, `templates-are-identity-at-rest-test` (eight starter cues through `generate-frames-by-zone`, 1e-9, Pump at 35 %), `punch-scales-then-returns-test`, `strobe-goes-into-flash-fx-test`, `fade-in-starts-dark-and-returns-to-full-test` (through `show-clock/tick!`: dark at the first tick after the switch, half, full), `reactions-index-test` (here, not in `library-test`: the index lives here), `first-empty-effect-cell-test`, `reaction-cell-test`.
- `laser-show.vibes.engine-test` (+6): the six of §8. `empty-pool-reports-a-problem-test` changed, as above. `laser-show.vibes.engine-fuzz-test`: NEW `check-pulses` after every vibe event (a press pulses, held; every release lets go; release-all lets go of its source's; BLACKOUT never pulses; `:vibe` once per change) and no pulse held during blackout; the empty-pool and not-held-release checks allow exactly the pulse.
- `laser-show.animation.modulation-test` (+5): the five of §8; `ui-modulator-list-test` also expects the three types under `:pulse`. `laser-show.animation.keyframes-test` (+1), `show-time-test` (+2), `show-clock-test` (+2; `snapshot-shape-test` gains `:pulses`; `one-state-and-input-read-per-tick-test` unchanged and green), `frame-service-test` (+1), `events.modulator-handlers-test` (+1), `events.handlers.grid-trigger-test` (+1), `events.effects-handlers-test` (+1), as §8 names them.
- `laser-show.events.handlers.vibes-test` (+4): the three of §8 and `pad-release-ends-a-press-that-played-nothing-test`. `laser-show.subs-test` (+1): `pad-names-its-reactions-sub-test`; `vibe-pad-sub-test` expects the two new keys.
- UI: NEW `laser-show.ui-test.modulator-editor-test` (5): the three of §8, `step-palette-field-test`, `keyframe-restart-on-combo-test`. `perform-test` (+3) and `grid-test` (+1), as §8 names them. The grid test clicks the submenu's item with `Motion/HORIZONTAL_FIRST`: the robot's straight path crosses the parent menu's other items, which closes the submenu.

**Red first**
- `pulses-test` before `pulses.clj`: the namespace didn't load. After it, the show-time tests: 10 failures (no stamps: the engine wrote no pulses yet).
- `modulation`, `keyframes`, `modulator-handlers`, `frame-service`, `show-clock` before the render path and the evaluators: 26 failures (`:pulses` dropped by `make-context` and `make-base-context`, as probe v13; no Restart on; no `:number-list`; no `:pulse` types; no `:pulses` in the snapshot). `restart-on-own-is-unchanged-test` passed, as it should.
- `engine`, `grid-trigger`, `show-time` before the engine and `start-cue`: 36 failures. Then the fuzz failed on all three seeds with "releasing :flash, which isn't held, changed the state": the decoupled release, meant; its checks were changed as above.
- `pad-release-ends-a-press-that-played-nothing-test` before `pad-holds?`: 2 failures (the pad's release never sent; the pulse stayed `{:id 1 :held? true :src :pad}`).
- `reactions`, `vibes`, `effects-handlers` before the handlers: 31 failures, three of them mistakes in the tests (the beam-fan's x of ±0.99 is clipped at ±1 when scaled; an effects-grid cell that is on reacts in every vibe).
- `subs-test` before the subs: 9 failures.
- UI: `modulator-editor-test` against `f9fc352`'s editor: 4 errors (no such controls); the four new perform and grid tests against `f9fc352`'s views: 5 errors.
- Mutants, each against the engine and fuzz tests: a release that lets go of the pulse only while the engine holds the role (2 unit tests, 4 assertions; fuzz 3 of 3 seeds); BLACKOUT keeping the pulse holds (2 unit tests; fuzz 3 of 3); `ensure-current` pulsing `:vibe` (1 unit test, 2 assertions; fuzz 3 of 3). All killed.

**Cost (invariant 12)**: probe v17's loop (1,000-point frame, 3,000 warm calls then 3,000 timed, garbage from `ThreadMXBean`) in a scratch namespace under `$TMPDIR`, two JVMs, two rounds each, load average 2.1–2.8 (another session's tests running), the real types next to v17's `:midi` stand-in in the same JVM. Four effects are Intensity ← Envelope, Scale ← Energy, Rotation ← Step (8 steps, wrap, glide), Hue Shift ← a Step palette; "live" is a HIT 0.1 beat ago (mid-release, mid-glide) with 16 recent presses.

| Chain | Median | p99 | Garbage |
|---|---|---|---|
| one effect, `:midi` | 32–50 µs | 36–84 µs | 180 KB |
| one effect, Envelope, live / at rest | 32–35 µs | 38–61 µs | 180 KB |
| four effects, `:midi` | 164–176 µs | 184–547 µs | 861–940 KB |
| four effects, Envelope, Energy, Step, palette, live | 172–184 µs | 201–542 µs | 946 KB |
| the same, at rest | 166–183 µs | 188–767 µs | 945 KB |
| four effects, static values | 161–173 µs | 176–529 µs | 931 KB |
| `pulses/stamp` per tick (5 sources) | 3.5–3.7 µs | 5–14 µs | 5.7 KB |
| `pulses/view` per tick (5 sources, 16 recent each) | 7.0–7.5 µs | 16–58 µs | 14.9 KB |

So the real types cost what `:midi` does, within the noise (1–10 % apart in the same round), and 3–7 % more than static values; at rest costs the same as live (nothing is skipped, R-5); the clock's own pulse work is about 11 µs and 21 KB a tick. Four cues in one zone with a four-effect FX set is about 0.7 ms of the 16.7 ms tick and 3.8 MB of garbage a tick; twice that with two zones. Lower than v17's 216–228 µs for the same chain; the comparison that matters is the one inside each JVM.

**Verification, 2026-11-19, macOS arm64, JDK 25**

| Check | Result |
|---|---|
| `clj -M:mac-arm:test pulses modulation keyframes show-time show-clock frame-service engine grid-trigger reactions vibes effects history modulator` | 399 tests, 2,465 assertions, 0 failures (baseline at `f9fc352`: 347, 2,009) |
| `clj -M:mac-arm:test subs` | 15 tests, 114 assertions, 0 failures |
| `VIBE_FUZZ_SEEDS=200 clj -M:mac-arm:test -n laser-show.vibes.engine-fuzz-test` | seeds 1–200, 2,000 steps each, with the pulse checks: 0 failures (23 s) |
| `clj -M:mac-arm:test` (full unit suite) | 1,003 tests, 33,496 assertions, 0 failures (32 s; baseline 951) |
| `clj -M:test-ui:mac-arm:test-ui-headless -n` modulator-editor-test, perform-test, grid-test, cue-chain-list-test, reentry-test, timeline-editor-test, main-window-test, setup-dialogs-test | 69 tests, 316 assertions, 0 failures (`modulator-editor-test` twice more on its own: 0 failures) |
| full UI suite | not run here: it runs after the merge |
| after the merge, on `master` at `d298801` (vibe Phases 3 and 4a, UX Phases 2 and 3): `clj -M:mac-arm:test`, then the full UI suite | 1,048 tests, 33,931 assertions, 0 failures; UI 128 tests, 612 assertions, 0 failures |
| `clj -M:kondo --lint` on the 42 touched files | 0 errors, 0 warnings |
| probes v13–v16 and v18 | the probe namespace still loads; v13 prints `true` for both contexts and the clip |

**What 4b has left** (after Phase 3 merges)
- The Vibes tab (`views/tabs/vibes.clj`): a **Tap reactions ▸** menu in each role's section from `reactions/templates` (by `:role`; Fade in beside the vibe's name), dispatching `:vibe/add-reaction {:vibe :kind}` (done and tested); under each role, the cells that react to it, from `reactions/reactions` (done).
- The lint rows of §7 in Phase 3's `library/lint`, from `reactions/reactions`, `reactions/listens-to` and `library/binds-role?`: "does nothing" (error, replacing "HIT has no cues to play"), "moves effects only (…)" (info), "listens to ACCENT, which has no key yet" (warn); tests `lint-effects-only-role-is-info-test`, `lint-reaction-on-an-unbound-role-test`.
- UI: `laser-show.ui-test.vibes-test/tap-reaction-menu-adds-a-cell-to-the-vibe-test`.
- At the merge: `add-to-vibe` switches the new cell on itself and records it in `[:vibe-runtime :fx-on]`; Phase 3's `engine/reconcile` may do that instead. 4a touched Phase 3's files only locally: `events/handlers/vibes.clj` (one `handle` case, `pad-holds?`), `events/handlers/history.clj` (two labels after `:vibe/clear-control`), `subs.clj` (one require, four private subs before `vibe-pad`, two keys in it), `css/vibes.clj` (three rules), `state/domains.clj` (`:pulses` in `playback`, not `[:ui]`), `vibes/engine.clj` (no new public fn).

## 11. As built: 4b (2026-11-19)

Committed as `f5a4f84` on top of `818cd82` (built as `8cb5688` on branch `vibe-phase4b` from `d298801`, then cherry-picked past two plan-only commits with no conflicts). The lines below are at `8cb5688`, and the same in `f5a4f84`. Phase 5 (the faders) was built at the same time on another branch; the files both change are listed at the end.

**Where it is now** (paths under `src/laser_show/`)
- `views/tabs/vibes.clj`, the section "Tap reactions" `:175-215`: `role-names` `:177`; `tap-menu` `:180`, a menu button "Tap reactions ▾" (`#vibe-tap-menu-<role>`) whose items (`#vibe-tap-<kind>`) are `reactions/templates` under a heading per role, each dispatching `:vibe/add-reaction {:vibe :kind}`; `role-reactions` `:202`, the menu and `#vibe-reactions-<role>`, the names of the vibe's cells that move on the role's press ("none yet" without). VIBE's is beside the name (`editor-header`, `:227`: Fade in); HIT's and ACCENT's under the pool options, and FLASH's under the solo box of the FLASH cue and FLASH effects slots (`slot-options` `:301`, `with-reactions` `:304`), each also offering Energy: size under "HIT, ACCENT or FLASH". The seventh rule of thumb (`rules` `:346`), which Phase 3 left to this phase.
- `subs.clj`: `lint-world` `:1198` also hands on the effect chains (`:1204`); NEW `vibe-edited-reactions` `:1214`, the reactions of the vibe edited, by role.
- `vibes/library.clj`: requires `vibes.reactions` (`:23`); NEW private `heard-roles` `:394` (`learned` `:400` counts it), `moves-only` `:428`, `source-names` `:435`, `unheard` `:438`; `vibe-problems` `:453` has the three rows (HIT `:472`, ACCENT `:475`, FLASH `:479`, `unheard` last); `lint` `:486` passes the controls a key is bound to (`:497`).
- `vibes/reactions.clj`: no longer requires `vibes.library` (it reads `[:config :vibes :library]` itself), so the lint can require it; `add-to-vibe` `:95` makes the cell, off, and puts it in the vibe, nothing more; `roles-of` `:156` is public (the lint's); `reactions` `:176` takes `:own? true` (`:187`).
- `events/handlers/vibes.clj`: NEW private `handle-add-reaction` `:139`, routed `:206`.
- `events/handlers/input_mapper.clj`: `learning?` `:34` (its docstring); while a learn waits only a note-on or a trigger is held back (`:84`).

**`add-to-vibe` or `engine/reconcile`: one path.** 4a's `add-to-vibe` switched the new cell on itself; Phase 3's `reconcile` does that for every other edit. Case by case they agree: a vibe that doesn't play, or none played yet (off); the vibe that plays (on, and in `[:vibe-runtime :fx-on]`, so leaving it switches it off); blackout (off until it ends, when the look is entered again); the Strobe (in the flash FX: off until FLASH is held). They differ after an undo took the vibe that played away (an undo runs no handler): every edit then leaves its look and enters the first of `:order` (03 §10 correction 4), and 4a's add left the runtime on the vibe that went, and the new cell off in the vibe the strip shows as playing. So `add-to-vibe` is the document edit only, and `handle-add-reaction` runs it through `reconcile` as `edit` (`:91`) runs the tab's other edits. Test `add-reaction-is-an-edit-like-the-others-test`; 4a's three `:vibe/add-reaction` tests pass unchanged.

**Differences from the plan**

| Plan | As built | Why |
|---|---|---|
| the role's list and the lint from `reactions/reactions`: the vibe's FX set and flash FX, the effects grid's cells that are on, its cues | the vibe's own cells (`:own? true`), not effect cells switched on by hand; the Perform pads count those, as in 4a | such a cell is no vibe's and can go off at any time. `:effects/new-reaction` makes one on, which would turn every empty HIT's error into "moves effects only" and be named under every vibe ("Lines · Scatter listens to ACCENT": the first mutant below) |
| error "does nothing" for any role with no playable cue and no reaction | HIT's only (it replaces Phase 3's HIT row); ACCENT gets the info row only; FLASH keeps Phase 3's warn "FLASH does nothing in this vibe", now also needing no reaction, and gets the info row when its cue is missing or can't play | Phase 3 has no row for an empty ACCENT (every new vibe would get a second error), and flash effects are something to FLASH (Phase 3's row) |
| warn: a reaction listens to a role with no key | per source (`reactions/listens-to`): one that follows HIT, ACCENT or FLASH only when none of the three has a key ("…, none of which has a key yet"); none while no key is learned at all | Energy: size moves on any of the three. Without the last rule a project with no controller showed a warning for every reaction (four on the headless screenshot) under "0 of 5 controls learned" |
| a **Tap reactions ▸** menu in each role's section, one beside the name for Fade in | menu buttons ("Tap reactions ▾") with headings, as the effects cell's menu; Energy: size (role `:any`) in each role's menu, Fade in alone in the header's | the sections aren't menus; Energy: size follows all three, and its name shows in each of their lists |
| the lint from `reactions/reactions`, `reactions/listens-to` and `library/binds-role?` | as planned; `reactions` no longer requires the library | the library's lint requires `reactions`, and the other way round would be a cycle |
| — | `mapper-ignores-discrete-events-while-learning-test` expects the releases to go through; its name stays | the keyboard plan's Phase 5 names it |

**Found, not done**
- A key that holds a role and loses its binding meanwhile (a learn moves the role to another key, or the pad's Clear) sends its release to nothing: the role stays held until its next press or BLACKOUT.
- A HIT with no cue but an effect cell switched on by hand reads "Effects only" on its pad (4a counts that cell) and "does nothing" in the lint (above).
- `reactions` without `:own?`, for a vibe that doesn't play, also counts the cells the playing vibe's FX set switched on. Only the pads ask it that way, for the vibe that plays, whose own they are.

**Tests**: 6 new unit tests (1,048 → 1,054) and 1 UI test
- `laser-show.events.handlers.input-mapper-test` (+1): `a-key-held-when-a-learn-starts-still-lets-go-test` (HIT's key held, a learn for ACCENT starts, the key let go: the role, its pulse and its cue are let go, and the learn still waits). `mapper-ignores-discrete-events-while-learning-test` changed, as above.
- `laser-show.vibes.library-test` (+2): `lint-effects-only-role-is-info-test`, `lint-reaction-on-an-unbound-role-test`; `lint-test`'s HIT row reads "does nothing".
- `laser-show.vibes.reactions-test` (+1): `own-reactions-leave-the-grid-out-test`. `laser-show.events.handlers.vibes-test` (+1): `add-reaction-is-an-edit-like-the-others-test`. `laser-show.subs-test` (+1): `vibe-edited-reactions-sub-test`; `vibe-lint-sub-test` reads "does nothing" and sees an effect cell's info row.
- UI `laser-show.ui-test.vibes-test` (+1): `tap-reaction-menu-adds-a-cell-to-the-vibe-test` (HIT's menu and its items; Punch: size into the first empty effect cell, on at once while Shapes plays, the undo label, HIT's list and the FX set's count; the Strobe from FLASH's menu, off; Fade in from the header's; the lint's row for HIT, ACCENT's key learned). Phase 2's and 3's learn tests (`learn-binds-a-role-to-a-note-test`, `a-learned-note-plays-through-the-mapper-test`, `learn-all-binds-five-roles-in-order-test`, the trigger editor's two and `input-test`'s) pass unchanged.

**Red first**
- `input-mapper-test` before the mapper change: 5 failures (after the release HIT was still held, its pulse held and its cue playing; the two releases were dropped, 5 events sent, not 7).
- `library-test`, `reactions-test` and `events.handlers.vibes-test` before the rest: 17 failures and 1 error (the old HIT text; no info rows; no warn rows; the add after an undo left `:current` on the undone `:vibe-3` and the cell off, 3 failures; `reactions` took no options). `subs-test` didn't compile (`No such var: subs/vibe-edited-reactions`).
- `tap-reaction-menu-adds-a-cell-to-the-vibe-test` against `d298801`'s `views/tabs/vibes.clj` (stashed): 1 error, timed out waiting for `#vibe-reactions-hit`.
- Mutants, each against `library-test` and `subs-test`: the lint counting the grid's cells that are on (2 failures, one naming Scatter under Lines too); warn rows while no key is learned (1); Energy: size warned of as soon as one of the three has no key (1). All killed.

**Verification, 2026-11-19, macOS arm64, JDK 25**

| Check | Result |
|---|---|
| `clj -M:mac-arm:test vibes reactions library input-mapper input subs history` | 274 tests, 1,776 assertions, 0 failures (baseline at `d298801`: 268, 1,734) |
| `clj -M:test-ui:mac-arm:test-ui-headless -n` vibes-test, perform-test, grid-test, trigger-editor-test | 38 tests, 270 assertions, 0 failures; `vibes-test` once more on its own: 10 tests, 92 assertions, 0 failures |
| `clj -M:mac-arm:test` (full unit suite) | 1,054 tests, 33,973 assertions, 0 failures (baseline at `d298801`: 1,048, 33,931) |
| `clj -M:kondo --lint` on the 12 touched files and `testfx.clj` | 0 errors, 0 warnings |
| layout | headless screenshots at 1200×800: the header's menu fits beside the energy dots, a role's under its options, the lint's rows under the picker |
| full UI suite | not run here: it runs after the merge |
| after the merge, on `master` at `2d1f813`: `clj -M:mac-arm:test`, then the full UI suite | 1,054 tests, 33,973 assertions, 0 failures; UI 129 tests, 625 assertions, 0 failures |

Phase 5's files that this branch changes, for the merge: `events/handlers/vibes.clj` (the `:vibe/add-reaction` line in `handle`, and `handle-add-reaction` after `handle-edit`), `vibes/library.clj` (its require, and `:394-512`, the lint), `subs.clj` (`lint-world`, and the sub after `vibe-lint`) and `events/handlers/input_mapper.clj` (the discrete branch, and two docstrings). A Morph cell that `:vibe/add-morph` puts in a vibe's FX set switches on the same way if it too goes through `engine/reconcile`.
