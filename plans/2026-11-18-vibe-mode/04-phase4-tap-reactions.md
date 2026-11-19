# Phase 4: tap reactions (presses move effects, not only cues)

**Written:** 2026-11-18   **Written for commit:** `b1da0f2` (`master`)
**Status:** Proposed. Needs Phase 1; the pad parts need Phase 2 and the Vibes tab parts Phase 3; V9 answered A with a palette (2026-11-18)   **Last verified against:** `b1da0f2` (probes v11, v13–v18 in `00-overview.md` §3)
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
