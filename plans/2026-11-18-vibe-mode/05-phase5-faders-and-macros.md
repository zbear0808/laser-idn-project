# Phase 5: the faders (macros, CC learn, master intensity, Morph)

**Written:** 2026-11-18   **Written for commit:** `b1da0f2` (`master`)
**Status:** Done in `9f5d78a` (2026-11-19; as built in §10), and the fader slots fixed after the merge in `0faa18c` (§11). Needed Phases 1 and 2; V5 A (1 Intensity built in, 2 Morph per vibe, unwired by default); V9 A (the hue step is Phase 4's, so this phase has no palette and no `:macro/set`)   **Last verified against:** `9f5d78a` (§10; probes v6, v9, v11 in `00-overview.md` §3 before it)
**Supersedes / Superseded by:** none   **Related findings:** review 05 F19 (channel numbering) is avoided, not fixed: the macro hides the channel; review 05 F18 (velocity is dropped) stays open

Two faders that work the moment a controller is plugged in, and a master dimmer that needs no setup. The fader path stays off the event agent end to end.

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| A CC becomes a store value | `events/handlers/input_mapper.clj:41-60`; `input/midi.clj:72-87`; `input/store.clj:7-35` | the mapper writes `[:midi channel control]` (0..1) to a lock-free atom, **not** to app state. Probe v9: 0 events reach the app |
| An unmoved fader | `input/store.clj:18-24` | reads 0.0 (probe v9) |
| Modulators read the store | `animation/modulator_evaluators.clj:204-233` (`eval-midi`, `eval-osc`); registered `animation/modulators.clj:226-247` | channel is 1-based in the UI with 0/1 fallbacks (`:204-221`). An unseen fader gives `min` |
| A generic `:input` modulator | evaluator `:234-244`, in the map `:419`; params `animation/modulators.clj:77-83` | **never registered**, so the UI doesn't offer it (`animation/modulator_registry.clj:126-133`). Its `:source-key` is text, which can't match the vector keys of the store |
| The store reaches every render | `services/frame_service.clj:102-114`; `services/show_clock.clj:53, 80-91` | every cue, the global effects and the projector effects get `:input-values` |
| Global effects | `services/frame_service.clj:83-100, 288-300` | the active effect cells become one chain, applied to each zone frame of each cue. Probe v11: an `:intensity` effect on 1,000 points costs 38–62 µs (static) to 41–62 µs (modulated), 240–310 KB of garbage |
| Intensity and hue | `animation/effects/intensity.clj:11-31` (`:amount` 0–3); `animation/effects/color.clj:133-148` (`:set-hue` 0–360), `:28-48` (`:hue-shift` ±180); `animation/effects/shape.clj:22-45` (`:scale`), `:95-110` (`:rotation`) | the effects the faders drive (stepping the colour per press is Phase 4's) |
| Learn | `events/handlers/input.clj:223-278` | captures `note-on` and `trigger` only (`:235-236`). Probe v6: a CC leaves it waiting |
| Gamepad axes | `input/gamepad.clj:29-47, 73-76`; `events/handlers/input_mapper.clj:55-60` | arrive as `control-change :gamepad ctrl axis`, stored as `[:gamepad ctrl axis]`: a trigger or stick can be a fader |
| Unmapped OSC values | `events/handlers/input_mapper.clj:68-72` | stored at `[:osc path]` from the discrete branch |
| Effect cells | `state/domains.clj:375-394`; `events/handlers/effects.clj:26-45` | `:active?` is live (not saved as a change, `state/history.clj:19-21`) |
| Starter content | `state/templates.clj:17-63` | cue cells only |
| Effect keys and their test | `events/core.clj:469-519`; `test/laser_show/events/core_test.clj:37-45` | a new effect key must be added to the map and to that test |

## 2. Root cause

A fader can only drive a parameter by hard-coding its channel and CC number into every modulator, so changing controllers means editing them all. Nothing gives an unmoved fader a safe value. Learn can't capture a CC. And "which fader does what in this vibe" has no home.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| The CC path to the lock-free store (`events/handlers/input_mapper.clj:41-60`) | unchanged; one more write beside it |
| The modulator registry and its UI list (`animation/modulator_registry.clj:61-133`) | registering `:macro` makes it a choice on every modulatable parameter, with no UI work |
| `:intensity`, `:scale`, `:rotation`, `:hue-shift` | the master and Morph templates |
| Global effects from active effect cells (`services/frame_service.clj:83-100`) | the master is a plain effect cell: visible, editable, deletable |
| An FX set per vibe (`:fx`, Phase 1 `switch-to`) | where Morph lives, so the same fader means different things in different vibes |
| `ext-clock-redraw` (`views/components/clock_view.clj:58-71`) | the meters read the store on each screen refresh |
| The learn flow (`events/handlers/input.clj:223-278`) | one more capture mode |
| `vibes.reactions/first-empty-effect-cell` and the template pattern (Phase 4) | where the Master and Morph cells go, the same way a tap reaction's does |

## 4. Changes per namespace

- `laser-show.animation.modulator-evaluators` (existing): NEW `eval-macro` `[{:keys [slot min max default]} {:keys [input-values]}]` → `min + v·(max − min)`, where `v` is `(get input-values [:macro slot] default)` and `default` itself defaults to 0.0. Added to `modulator-evaluators` (`:408-424`).
- `laser-show.animation.modulators` (existing): register `:macro` (name "Fader", category `:external`, params `:slot` choice of `:slider-1 :slider-2`, `:min`, `:max`, `:default`) beside `:midi` and `:osc` (`:226-247`).
- `laser-show.events.handlers.input-mapper` (existing, `:41-60, 68-72`): NEW pure `store-keys [event]` (the one definition of what store key(s) an event writes, shared with learn). After writing a key, look it up in `(get-in (state/get-raw-state) [:config :input :macro-map])` and, if a slot is bound, also write `[:macro slot]`. This is a map lookup on the MIDI thread; no agent hop.
- `laser-show.events.handlers.input` (existing, `:223-270`): `:input/start-learn` takes `:capture :continuous`. The router handler remembers the first value it sees per store key and delivers when some key has moved by 0.08 or more (about 10 of 127 steps). `:input/learn-captured` for a slider target writes `[:config :input :macro-map key] slot` and removes the slot's previous key.
- `laser-show.events.handlers.vibes` (existing): `:vibe/clear-control` also clears a slider. NEW `:vibe/add-master` (creates the Master cell in the first empty effect cell, `vibes.reactions/first-empty-effect-cell` from Phase 4), `:vibe/add-morph {:vibe :kind}` (creates a Morph effect cell from a template and adds it to the vibe's `:fx`).
- `laser-show.state.domains` (existing): `config` `:input` default gets `:macro-map {}` (`:243-244`). A project saved before this has no `:macro-map`, because the load merges shallowly (`state/persistent.clj:126`), so every reader treats nil as `{}`.
- `laser-show.state.templates` (existing): NEW `create-starter-effects`, `apply-starter-effects` (only when the effects grid is empty): cell `[0 3]` "Master", `:active? true`, one `:intensity` effect.
- `laser-show.views.components.slider-meters` (NEW): two meters under the pads, drawn by `ext-clock-redraw` from the store: a bar, a percentage, "–" until the first value, and "not wired" on slider 2 when the vibe's FX set has no `:macro` modulator for it (a pure check, `vibes.library/slot-wired?`).
- `laser-show.views.components.vibe-controls` (existing, Phase 3): rows for SLIDER 1 and 2 (Learn, Clear) and the buttons **Add master intensity** and **Add Morph ▸ Size / Spin / Colour**.
- `laser-show.views.tabs.perform` (existing): the meters.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:config :input :macro-map]` (NEW) `{[:midi 0 1] :slider-1}` | document | learn, `:vibe/clear-control` | yes / yes |
| `[:macro :slider-1 …]` in the input store (NEW keys) | the runtime store atom, **not** app state | the mapper | – |
| the Master and Morph effect cells | document (effect chains) | `:vibe/add-master`, `:vibe/add-morph`, the starter template | yes / yes (`:active?` alone is live) |

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:input/start-learn {… :capture :continuous}` | gains an option | Controls panel, pad menu | yes |
| `:vibe/add-master`, `:vibe/add-morph` | NEW | Controls panel | yes |
| `:vibe/clear-control` | Phase 2 | gains sliders | yes |

## 7. Data examples and invariants

### The macro modulator

| Modulator | Store | Result |
|---|---|---|
| `{:type :macro :slot :slider-1 :min 0.0 :max 1.0 :default 1.0}` | `{}` | `1.0` (unseen: full) |
| the same | `{[:macro :slider-1] 0.25}` | `0.25` |
| `{:type :macro :slot :slider-2 :min 0.5 :max 1.5 :default 0.5}` | `{}` | `1.0` (neutral until moved) |
| `{:type :macro :slot :slider-2 :min 0.0 :max 1.0}` (no `:default`) | `{}` | `0.0` |

### The mapper

| `macro-map` | Event | Store writes |
|---|---|---|
| `{[:midi 0 1] :slider-1}` | CC 1, channel 0, 0.5 | `[:midi 0 1]` 0.5 and `[:macro :slider-1]` 0.5 |
| the same | CC 2, channel 0 | `[:midi 0 2]` only |
| `{[:gamepad 0 :trigger-left] :slider-2}` | the left trigger at 0.8 | `[:gamepad 0 :trigger-left]` 0.8 and `[:macro :slider-2]` 0.8 |
| `{[:osc "/fader/1"] :slider-1}` | an unmapped OSC value 0.3 | `[:osc "/fader/1"]` 0.3 and `[:macro :slider-1]` 0.3 |

### Learning a fader

```clojure
;; :input/start-learn {:target-action {:type :macro :slot :slider-1} :capture :continuous}
;; the router sees CC [:midi 0 7] at 0.50 (first value for that key), then 0.52  -> nothing
;; then CC [:midi 0 1] at 0.10, then 0.31                                        -> captured: 0.21 >= 0.08
;; :config :input :macro-map  =>  {[:midi 0 1] :slider-1}
;; learning slider-1 again on [:midi 0 9] removes [:midi 0 1] from the map: one source per slot
```

### The starter templates (neutral until a fader moves)

```clojure
;; Master (effect cell [0 3], active)
{:name "Master" :active? true
 :items [{:type :effect :effect-id :intensity :enabled? true
          :params {:amount {:type :macro :slot :slider-1 :min 0.0 :max 1.0 :default 1.0}}}]}
;; Morph / Size (added to a vibe's :fx; the default puts it at 1.0x)
{:name "Morph: Size" :active? false
 :items [{:type :effect :effect-id :scale :enabled? true
          :params {:x-scale {:type :macro :slot :slider-2 :min 0.5 :max 1.5 :default 0.5}
                   :y-scale {:type :macro :slot :slider-2 :min 0.5 :max 1.5 :default 0.5}}}]}
;; Morph / Spin:   :rotation  :angle    min 0    max 360  default 0.0
;; Morph / Colour: :hue-shift :degrees  min -180 max 180  default 0.5
```

An FX-set cell is switched on by the vibe (Phase 1 `switch-to`), so `:active? false` in the template is right.

### Hue stepping

Not here (V9 A): a Step modulator on HIT does it, in Phase 4 ("Step: colour"). A palette of 200°, 260°, 320° is a Step from 200 to 320 in 3 steps.

### Invariants

1. A fader never visits the event agent: the mapper writes the store on the MIDI thread, and the show clock reads it at the next tick (at most 16.7 ms later).
2. With the Master cell active and the fader never moved, the output frames are identical to the output without the cell.
3. The fader's range is 0..1 for the Master. At 0 the show is dark, and Perform says so ("Intensity 0 %").
4. Moving to another controller changes one `macro-map` entry, not any modulator.
5. A slot has one source key. A key has one slot.
6. Slider 2 does nothing in a vibe that doesn't wire it, and the meter says "not wired".
7. A project saved without `:macro-map` loads and works (nil reads as `{}`).
8. Learning a fader never captures a note, and learning a role never captures a CC.
9. A noisy CC that wiggles by less than 0.08 is never captured.

## 8. Tests (named test vars + fixtures)

- `laser-show.animation.modulation-test` (existing): NEW `macro-reads-its-slot-test`, `macro-default-when-unseen-test`, `macro-is-registered-and-listed-test` (it is in `modulator-registry/all-ui-modulators`).
- `laser-show.events.handlers.input-mapper-test` (NEW or the keyboard plan's, same name): `bound-cc-also-writes-its-macro-test`, `unbound-cc-writes-only-its-own-key-test`, `gamepad-axis-can-be-a-macro-test`, `unmapped-osc-value-can-be-a-macro-test`, `missing-macro-map-is-empty-test`. Fixtures: `test/laser_show/input/fixtures.clj` (real-shaped MIDI and OSC messages) and the pattern of `test/laser_show/input/osc_test.clj:40-60` (start the mapper, `router/dispatch!` real events, read the store); the test stops the mapper after, as probe v9 does.
- `laser-show.events.handlers.input-test` (existing): NEW `learn-cc-needs-movement-test`, `learn-cc-binds-a-slot-and-replaces-its-old-source-test`, `learn-continuous-ignores-notes-test`, `learn-a-role-ignores-cc-test` (probe v6, turned around).
- `laser-show.state.templates-test` (existing from Phase 1): NEW `master-cell-is-identity-until-the-fader-moves-test` (renders a cue through `frame-service/generate-frames-by-zone` with and without the cell and compares frames), `master-cell-dims-with-the-fader-test`, `morph-templates-are-neutral-until-moved-test` (compares to within 1e-9: Morph / Colour's Hue Shift goes through HSV and is 5.6e-17 off at rest, probe v17; the Master's Intensity is exactly identical).
- `laser-show.events.handlers.vibes-test` (existing): NEW `add-master-uses-the-first-empty-effect-cell-test`, `add-morph-puts-the-cell-in-the-fx-set-test`.
- UI: `laser-show.ui-test.perform-test` (existing): NEW `meters-follow-the-store-and-say-not-wired-test`. `laser-show.ui-test.vibes-test` (existing): NEW `learn-a-fader-binds-a-slot-test` (synthetic CCs through `router/dispatch!` with `input-events/control-change`).
- Evidence: re-run probe v11 against a busy cue (`probes/` of the show clock plan has one) and write the number here. *Unverified under load* until then.

Run: `clj -M:mac-arm:test modulation input-mapper input templates vibes`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.perform-test`, then `-n laser-show.ui-test.vibes-test`, then both full suites.

## 9. Out of scope / alternatives considered

- **A render-path master multiply** (apply once per tick on the joined frame): cheaper than a per-zone effect, but it changes the render path and the "frames stay identical" tests of the show clock plan. Revisit if probe v11 under load says the cell costs too much.
- **Registering the existing `:input` modulator** with a parsed key: rejected: free text keys, no default, and the same indirection problem.
- **Per-vibe slider target as data** (as `beyond-vibe`'s `slider_pangoscript_target`): rejected: it needs a parameter override in the render path and a way to pick a parameter. A FX set with a `:macro` modulator is the same thing with the existing editors.
- **Fader smoothing and pickup** (a fader's physical position is unknown until it moves): the Master is full until moved, and Perform says "–" until a value arrives. Smoothing is later.
- **Velocity → intensity** (review 05 F18): later, and it would use the same `:macro` slot mechanism.
- **Fixing the 0/1 channel fallback** in `:midi` (review 05 F19): untouched; macros avoid channels entirely.
- **A per-vibe `:palette` stepped by HIT through a `:hue` slot and a `:macro/set` effect** (this doc's first version; V9 B): replaced by Phase 4's Step modulator, which steps any parameter on any role with no engine code and no new effect key.

## 10. As built (2026-11-19)

Committed as `9f5d78a` on top of `eb20782` (built as `58834e6` on branch `vibe-phase5` from `d298801`, UX Phase 3 done and vibe Phases 1–3 and 4a in it; the cherry-pick past vibe Phase 4b conflicted only in three test files' docstrings and the new tests at their ends, resolved by keeping both). §1–9 cite `b1da0f2`; the lines below are at `58834e6`: the same in `9f5d78a` except in the files 4b also changed (events/handlers/input_mapper.clj, events/handlers/vibes.clj, subs.clj, vibes/reactions.clj, events/handlers/input_mapper_test.clj, events/handlers/vibes_test.clj, subs_test.clj, ui_test/vibes_test.clj), where they are shifted by its lines. Vibe Phase 4b was being built at the same time; in the files it changes this phase's edits are local: `events/handlers/vibes.clj` (`handle-clear-control` takes a `:slot`, NEW `handle-add-morph` after it, two `case` lines after `:vibe/clear-control`, a sentence at the end of the ns docstring), `events/handlers/history.clj` (two labels after `:vibe/clear-control`) and `events/handlers/input_mapper.clj` (two NEW fns before `update-input-value!`, its body, and the continuous branch; the ns docstring, the `learning?` filter and the discrete branch are as they were). `views/tabs/vibes.clj` and `vibes/library.clj` are untouched.

**Where it is now** (paths under `src/laser_show/`)
- `animation/modulator_evaluators.clj`: NEW `eval-macro` `:257`, in `modulator-evaluators` `:445`. `animation/modulators.clj`: `macro-params` `:133` (`:slot`, shown as "1: Intensity" and "2: Morph"; `:min`; `:max`; `:default`, "Until moved", 0..1, registry default 0.0), the registration `:352` ("Fader", `:external`, `:icon-name :sliders`). `animation/modulator_registry.clj:50`: the category's docstring.
- `events/handlers/input_mapper.clj`: NEW public `store-keys` `:28`; NEW private `update-macro-value!` `:49` (the `[:config :input :macro-map]` lookup on the input's thread, and the `[:macro slot]` write), called by `update-input-value!` `:58`, so every store write the mapper makes can drive a slot; the continuous branch writes `store-keys` (`:84`).
- `events/handlers/input.clj`: `learn-target` `:233` keeps `:slot`; NEW `fader-travel` `:255` (0.08) and `fader-catcher` `:260`; `handle-input-start-learn` `:276` takes `:capture :continuous` (`:288`); NEW `handle-fader-captured` `:369` ("Learn Fader", `:386`), routed for a `:macro` target at `:481`.
- `vibes/faders.clj` (NEW, pure): `slots` `:18`, `master-cell` `:28`, `morph-templates` `:36`, `morph-cell` `:50`, `master-at` `:71`, `add-master` `:80`, `add-morph` `:90`, `slot-wired?` `:105`; private `fader` `:22`, `follows?` `:60`, `wires?` `:66`. `vibes/reactions.clj:124`: `effects-in` is public, the walker `slot-wired?` and `master-at` use.
- `events/handlers/vibes.clj`: `handle-clear-control` `:86`, NEW `handle-add-morph` `:98`, routed `:192-193`. `events/handlers/history.clj:149-150`: "Add Master Intensity", "Add Morph"; a fader's Clear is "Clear Control".
- `state/domains.clj:266`: `[:config :input]` defaults to `{:trigger-map {} :macro-map {}}`. `state/templates.clj`: NEW `create-starter-effects` `:105`, `apply-starter-effects` `:112`, applied by `persistent/new-project-state` (`state/persistent.clj:201`), `app/start!` and `app/start-test!` (`app.clj:147, 264`), never by a load.
- `subs.clj`, a section of its own at the end (`:1239`): `vibe-fader-bindings` `:1247`, `vibe-fader-control` `:1259`, `vibe-meter` `:1268`, `vibe-fader-actions` `:1284`; private `fader-learning?` `:1256`, `fader-cells` `:1280`.
- `views/components/slider_meters.clj` (NEW): `meter-text` `:17`, `redraw-meter!` `:24` (the `ext-clock-redraw` fn: it reads `[:macro slot]` from the store), private `meter` `:34`, `meter-row` `:59`; ids `#vibe-meter-slider-1`, `-text`, `-bar`. `views/tabs/perform.clj:141`: the meters between the pads and the status line.
- `views/components/vibe_controls.clj`: NEW private `fader-row` `:95` (`#vibe-control-slider-1`, `-keys`, `-learn`, `-clear`) and `fader-buttons` `:135` (`#vibe-add-master`; `#vibe-add-morph` and its items `#vibe-add-morph-size`, `-spin`, `-colour`), under FADERS after Learn all in `controls-panel` `:163`. `views/components/trigger_bank.clj:49`: NEW `fader-label` ("MIDI Ch 1 CC 7", "OSC /fader/1", "Gamepad 1 trigger left"). `css/vibes.clj`: `.vibe-meter` `:143`, `.vibe-meter.vibe-meter-off` `:148`, `.vibe-meter-text` `:151`, `.vibe-meter-bar` `:155`, `#vibe-add-morph` `:306` (a menu button's label has padding of its own).

**Differences from the plan**

| Plan | As built | Why |
|---|---|---|
| `store-keys`: the one definition of what an event writes, shared with learn | the continuous branch writes `store-keys`; the discrete branch's unmapped-OSC write (`input_mapper.clj:92-96`) is as it was, `store-keys` names it too, and `store-keys-name-what-the-mapper-writes-test` dispatches fourteen events (CCs from MIDI, OSC, a gamepad and a keyboard source, unmapped OSC values, notes, buttons) and compares the store's keys with `store-keys` for each | Phase 4b edits the `learning?` filter on the next line (`:97`), and the brief keeps this file's edits apart from it |
| "after writing a key, look it up" | in its own fn, `update-macro-value!`, beside the store write, `update-input-value!` calls it | the brief; so the unmapped OSC row of §7 needs nothing more |
| learn "delivers when some key has moved" | the catcher delivers the event with the key that moved as `:store-key`, and `handle-fader-captured` binds it only if `store-keys` of that event names it | a note handed to a fader's learn binds nothing, even with a `:store-key` (invariant 8 at both ends). A mapped OSC fader writes `[:osc path]` and `[:osc 0 control]`; the first that moved is bound |
| `:capture :continuous` | also implied by a `{:type :macro}` target; a `:continuous` capture for a role catches a CC that then binds nothing | a fader's learn can't fall back to waiting for a press |
| — (not said) | `learn-target` keeps `:slot` | else every fader's learn is `{:type :macro}`, and a capture for slider 1 would bind while slider 2 is learned (Phase 2's role precedent) |
| `vibes.library/slot-wired?`, over the vibe's FX set | `vibes.faders/slot-wired?`, over what plays in the vibe: its FX set, its flash effects, the effects-grid cells that are on, the cues of its pools, flash and bed; a modulator that's off or a disabled effect doesn't count | `library.clj` is 4b's (lint), and the walker it needs (`reactions/effects-in`) is in a namespace that requires `library`. "Not wired" has to mean "moving it changes nothing here" (invariant 6), which a Morph in a pool's cue or a grid cell that is always on contradicts |
| "not wired" on slider 2 | on either: Intensity says so while its Master is off or deleted | it then does nothing |
| NEW in §4: none for the templates | NEW `vibes.faders`: the templates, `add-master`, `add-morph`, `master-at`, `slot-wired?` | the handlers in 4b's `vibes.clj` stay a fn and two lines; `state.templates` and the subs share them |
| Master item `{:type :effect :effect-id :intensity …}` | no `:type` | effect items have none (`events/handlers/effects.clj:150`, the reaction templates) |
| Morph cells "Morph: Size" | "Groove · Morph: Size", as a tap reaction's (4a); "Morph: Size" without a vibe | the effects grid shows whose it is |
| `:vibe/add-master`: the Master in the first empty cell | and nothing while a Master is there (`master-at`: an Intensity whose amount follows fader 1, on or off); the button is disabled then | two Masters square the fader (0.5 gives 0.25) |
| `:vibe/add-morph` adds the cell to the FX set | through `engine/reconcile`, as Phase 3's FX-set edits: on at once in the vibe that plays, waiting during blackout | 4a's `add-to-vibe` switches on itself; reconcile is the one place that does it now |
| the Controls rows "SLIDER 1 and 2" | "INTENSITY" and "MORPH" under a FADERS title, after Learn all; class `.vibe-fader`, so `.vibe-control` and Learn all stay the five; "Move a fader…" while learning; "Add Morph to Shapes" names the vibe edited | V5's names; brief note 3 |
| the meters: a bar, a percentage, "–", "not wired" | "Intensity 45 %", "Morph –", "· not wired" after the value, the meter dimmed | |
| the starter Master | at `[0 3]` in a new project, on; `effects-only-pad-says-so-test` now looks at `[1 3]` (at `[0 3]` it passed because the Master is on), `new-project-resets-document-only-test` expects the Master, and `hints-describe-the-clicks-test` empties the effects grid first | a new project's Effects tab has a cell, so UX Phase 3's empty-grid line shows only in a project with none: the original test fails (below) |

**Found, not done**
- A fader's slot outlives its control: `[:macro slot]` stays in the input store when Clear, a learn on another control (until that one moves), an undo of the learn, New or Open takes the binding away, so the Master stays where the old control left it, dark if at 0 (Perform then says "Intensity 0 %"), with nothing bound to move it, until a control learned for it moves or the app restarts. A pure handler can't touch the store, and an effect key that could is what V9 A ruled out. Two ways: a store effect on Clear and on load, or derive the slots from the macro map once per tick in `frame-service/render-context` (and in the meters) instead of storing them. *Fixed after the merge, the second way (§11).*
- Right after a learn the slot has no value until the control moves again (the move that was learned came before the binding): the Master stays full and the meter says "–" for that moment. *Fixed with it (§11): the slot reads the control's value, which the learn's own move left in the store.*
- While a fader's learn waits, the mapper's `learning?` filter (Phase 3, K-15) drops presses too: the pads and keys do nothing until the learn ends. 4b narrows the filter to presses only.
- A new project no longer shows the Effects tab's empty-grid line (UX Phase 3): the Master is a cell. It still shows in a project without effect cells. *Fixed after the merge (§11): the Master rule.*

**Tests**: 29 new unit tests (1,048 → 1,077) and 4 UI tests; 3 changed
- `laser-show.animation.modulation-test` (+3): `macro-reads-its-slot-test` (§7's rows, through `resolve-param`; another slot's value, or the control's own key, isn't the slot's), `macro-default-when-unseen-test` (R-6: unseen is `:default`; none is 0.0, the evaluator alone too), `macro-is-registered-and-listed-test` (in `all-ui-modulators`, "Fader", `:external`, its params).
- `laser-show.events.handlers.input-mapper-test` (+6): the five of §8, on real-shaped messages (`input/fixtures.clj` through `midi/midi-msg->event` and `osc/osc-msg->events`, the gamepad's `diff-controller-states`), each starting the mapper and stopping it after; `bound-cc-also-writes-its-macro-test` also checks nothing is dispatched (invariant 1); and `store-keys-name-what-the-mapper-writes-test`.
- `laser-show.events.handlers.input-test` (+4): the four of §8. `learn-cc-needs-movement-test` has §7's example, MIDI steps (from 64, 74 and 54 are 10 of 127 either way: nothing; 75, 11 steps: caught), an OSC fader and a gamepad trigger; `learn-cc-binds-a-slot-and-replaces-its-old-source-test` has invariant 5 both ways, the same control again (no undo step), a capture for the other slot, the undo, and a fader's learn ending Learn all.
- `laser-show.state.templates-test` (+4): `starter-effects-test`, and the three of §8, rendering the starter cues coloured (white hides a hue shift) through `frame-service/generate-frames-by-zone`: the Master at rest equal to no cell (`=`, and within 1e-9), also through the `render-context` a new project's state gives; a quarter up is a quarter as bright, 0 is dark; each Morph within 1e-9 at rest, and Size 1.5x at the top, Spin and Colour moving with fader 2 and not fader 1.
- NEW `laser-show.vibes.faders-test` (3): `templates-are-valid-test` (registered effects and modulators, values in range), `master-at-test`, `slot-wired-test`.
- `laser-show.events.handlers.vibes-test` (+4): `add-master-uses-the-first-empty-effect-cell-test` (one undo step; a second, or a full grid, adds nothing), `add-morph-puts-the-cell-in-the-fx-set-test` (the three kinds; on at once in the vibe that plays, off when it's left, waiting during blackout; undo), `clear-control-clears-a-fader-test`, and `starter-project-reactions-find-a-free-cell-test` (brief note 1: a new project's tap reaction goes to `[1 3]`, a Morph to `[2 3]`, and 29 more fill the grid around the Master; one more adds nothing).
- `laser-show.state.persistent-test` (+3): `new-project-has-the-master-test`, `macro-map-survives-save-and-load-test`, `old-project-without-macro-map-loads-test` (invariant 7: nil, and no Master added). `laser-show.subs-test` (+1): `fader-subs-test` (a press changes no meter). `laser-show.views.components.trigger-bank-test` (+1): `fader-label-test`.
- UI: `laser-show.ui-test.perform-test` (+1): `meters-follow-the-store-and-say-not-wired-test` (store values and a learned CC through the mapper, ticked by hand; "Intensity 0 %"; a Morph wires fader 2 in the vibe that plays, not in the other). `laser-show.ui-test.vibes-test` (+2): `learn-a-fader-binds-a-slot-test` (synthetic CCs and a note through `router/dispatch!` with `input-events/control-change`: a note and a wiggle bind nothing, CC 1 binds, its moves reach the slot through the mapper, CC 9 takes the slot, Clear), `fader-buttons-add-the-master-and-a-morph-test` (Add Morph's menu; the Master put back). `laser-show.ui-test.modulator-editor-test` (+1): `fader-type-follows-a-slot-test` (Fader in the dropdown, its slot labels).
- Changed: `menu-file-handlers-test/new-project-resets-document-only-test`, `perform-test/effects-only-pad-says-so-test`, `grid-test/hints-describe-the-clicks-test` (the table above).

**Red first**
- Against `d298801`'s `src/` with the tests written: modulation, input, handlers vibes and menu-file loaded: 132 tests, 970 assertions, 65 failures, 3 errors (no `:macro` evaluator: the NPEs; the vibe events unrouted, returning `{}`; `:capture` ignored, so a CC never caught; no Master in a new project). The other five didn't compile: `input-mapper-test` (`No such var: input-mapper/store-keys`), `persistent-test` and `subs-test` (`templates/apply-starter-effects`), `templates-test` and `faders-test` (no `laser-show.vibes.faders`), `trigger-bank-test` (`trigger-bank/fader-label`).
- UI, with the views set aside (`vibe_controls.clj`, `perform.clj` and `css/vibes.clj` as at `d298801`, no `slider_meters.clj`): the three new tests timed out waiting for the meter, the Add Morph button and the fader rows (5 tests, 20 assertions, 3 errors); the two changed ones passed. `hints-describe-the-clicks-test` as it was, against the new code: 1 failure (the empty-grid line doesn't show).

**Cost** (the doc's Evidence): probe v11's loop (3,000 warm calls, 3,000 timed, garbage from `ThreadMXBean`) and the show clock plan's busy cue (4 presets with 3 modulated effects each, 346 points, rendered with `generate-frames-by-zone` and a `render-context` made once), in a scratch namespace under `$TMPDIR`; the committed probe files are untouched. Two JVMs, load average 2.9–3.9 (other sessions' tests running).

| Render | Median | p99 | Garbage |
|---|---|---|---|
| v11, 1,000 points: Intensity at a static 1.0 | 41.2–41.9 µs | 49–73 µs | 248 KB |
| the same, `:midi` (v11's stand-in) | 41.4–42.5 µs | 50–51 µs | 250 KB |
| the Master, its fader never moved | 42.5–44.4 µs | 49–58 µs | 250 KB |
| the Master, fader at 0.6 | 41.4–44.8 µs | 48–58 µs | 250 KB |
| the Master and a Morph (Size), both moved | 64.3–67.3 µs | 78–87 µs | 447 KB |
| busy cue, no effect cells | 121.0–124.7 µs | 202–386 µs | 496 KB |
| busy cue, the Master at rest | 135.8–141.3 µs | 162–166 µs | 584 KB |
| busy cue, the Master moved | 133.3–135.8 µs | 159–164 µs | 584 KB |
| busy cue, the Master and the three Morphs, at rest | 177.9–180.7 µs | 205–272 µs | 889 KB |
| busy cue, the same, moved | 182.9–183.1 µs | 219–286 µs | 889 KB |
| the mapper, one CC no fader is learned on (router to store) | 0.7–0.8 µs | 1.6–1.8 µs | 0.7 KB |
| the mapper, one CC learned for fader 1 (two store writes) | 0.8–1.0 µs | 0.9–1.3 µs | 0.9 KB |

So the Master costs what one modulated Intensity does (`:macro` resolves once per chain, as `:midi`), within the noise of a static one, the same at rest as moved (nothing is skipped, R-5): about 9–20 µs and 88 KB on the busy cue, 7–16 % of its render. Four cues of 1,000 points in one zone: about 0.18 ms of the 16.7 ms tick and 1 MB of garbage a tick. 04 §10 measured the same four effect types (Intensity, Scale, Rotation, Hue Shift) at 172–184 µs on 1,000 points; on the busy cue's 346 points the Master and the three Morphs add 53–62 µs. A learned CC adds a map lookup and a store write on the controller's thread, about 0.1–0.2 µs.

**Verification, 2026-11-19, macOS arm64, JDK 25**

| Check | Result |
|---|---|
| `clj -M:mac-arm:test modulation input-mapper input templates vibes reactions subs persistent history` | 366 tests, 2,509 assertions, 0 failures |
| the same with `trigger-bank menu-file faders` | 398 tests, 2,653 assertions, 0 failures |
| `clj -M:test-ui:mac-arm:test-ui-headless -n` perform-test, vibes-test, grid-test, modulator-editor-test | 41 tests, 282 assertions, 0 failures |
| `clj -M:mac-arm:test` (full unit suite) | 1,077 tests, 34,178 assertions, 0 failures (baseline at `d298801`: 1,048 tests, 33,931 assertions) |
| `clj -M:kondo --lint` on every touched file, with `testfx.clj` | 0 errors; 1 warning, there before (the redundant `let` at `input.clj:423`, in `handle-input-update-cell-trigger-action`) |
| full UI suite | not run here: it runs after the merge |
| after the merge, on `master` at `8bb1332`: `clj -M:mac-arm:test`, then the full UI suite | 1,083 tests, 34,220 assertions, 0 failures; UI 133 tests, 657 assertions, 0 failures and 1 error: 4b's `tap-reaction-menu-adds-a-cell-to-the-vibe-test` was written on a grid without the Master, so it waited for the new reaction at [0 3], which the Master now holds. `43fa00d` moves its expected cells to [1 3], [2 3] and [3 3]; `vibes-test` then 12 tests, 118 assertions, 0 failures |
| layout | headless screenshots at 1280×800 and 1200×800: the Controls panel's fader rows and buttons fit the 300 px column; the meters share the Perform tab's width under the pads |
| manual (unverified) | a real MIDI controller, gamepad and OSC app; a visible (non-headless) run |

## 11. After the merge: the fader slots (2026-11-19)

Committed as `0faa18c` on top of `7559aad` (built as `ca4554e` on branch `vibe-phase5` over `8bb1332`, Phase 5 on `master`, and `d7edf3c`, `master`'s `43fa00d` cherry-picked: 4b's `tap-reaction-menu-adds-a-cell-to-the-vibe-test` expects its reactions after the Master; the cherry-pick onto `master` had no conflicts). Lines are at `ca4554e`, and the same in `0faa18c`. It fixes §10's first finding, the second way: a fader's value is derived from the binding that exists, never from a stored copy. With the fader at 0, a Clear, a learn on another control, an undo of the learn, File > New or Open used to leave the Master dark, with nothing bound to raise it, until a restart.

**Where** (paths under `src/laser_show/`)
- `vibes/faders.clj`: NEW `fader-values` `:27` (`{slot value}`: the value in the input store of the control learned for the slot now; a slot with nothing learned, or whose control has no value yet, is absent, so its Faders sit at their `:default`) and `with-faders` `:41` (the input values with those at `[:macro slot]` and no other `[:macro slot]`, whatever the store holds; the same map, no garbage, when there is nothing to derive).
- `services/frame_service.clj`: `render-context` (`:102`) gives every render the derived values (`:119`): each cue, the effects grid's chain, and the 2-arity `generate-frames-by-zone`.
- `services/show_clock.clj`: `tick!` reads the store once (`:155`), every tick, playing or not: the meters need it when nothing plays. `render` takes the values; calibration gets the render context's (`:102`), so a Fader there follows the binding too. The snapshot carries `:faders` (`:172`, `fader-values`).
- `views/components/slider_meters.clj`: `redraw-meter!` (`:24`) reads the snapshot's `:faders`: the values the render used, from the same pure fn. The meters no longer read the store.
- `events/handlers/input_mapper.clj`: `update-macro-value!` is gone, and with it the `[:macro slot]` write and the app-state read per CC on the controller's thread; `update-input-value!` (`:49`) writes the control's own key only. Nothing else needed the stored copy: the render and the meters derive, and the evaluator reads the render's values.
- The Effects tab's empty-grid line, the first of the two ways: **the Master rule**. `subs/effects-grid-empty?` (`:347`) leaves out a cell that is a Master and nothing more (`faders/master-only?` `:108`: its one enabled effect an Intensity whose amount follows fader 1), so a new project's grid says what effect cells are for again, as UX Phase 3 meant, until a cell has anything else.
- Docstrings: `animation/show_time.clj` (`:faders` in the snapshot), `state/domains.clj:267`, `eval-macro`, `handle-fader-captured`, `views/tabs/effects.clj`.

**Tests**: 6 new unit tests (1,083 → 1,089) and 1 UI test; 7 changed
- NEW `vibes.faders-test/fader-values-follow-the-bindings-test`; `services.show-clock-test` (+4), each through `show-clock/tick!` with a cue playing, the Master on and the input mapper on the router: `cleared-fader-leaves-the-master-full-test`, `relearned-fader-holds-its-default-until-it-moves-test` (on CC 9, which hasn't moved, the default holds until it does, and CC 7 moves nothing; learned for real, catcher and mapper both listening, the fader is where the control was left, at once), `undone-learn-leaves-the-master-full-test` (and the redo follows the fader again), `new-and-open-give-a-full-master-test` (the store still holds the old fader at 0; a project that learned the same control follows it at once); `snapshot-shape-test` gains `:faders`; `one-state-and-input-read-per-tick-test` unchanged and green. `events.handlers.input-mapper-test`: NEW `cc-reads-no-app-state-test`; `bound-cc-also-writes-its-macro-test` is now `bound-cc-moves-its-fader-test`, and it and the gamepad and OSC rows check the store holds the control's key only and `fader-values` gives the slot. `subs-test/effects-grid-empty-sub-test` gains the Master rule.
- UI: NEW `perform-test/meters-say-a-dash-for-an-unbound-slot-test` (after Clear, and for a slot with a stray `[:macro slot]` in the store); `meters-follow-the-store-and-say-not-wired-test` moves learned controls through the mapper; `vibes-test/learn-a-fader-binds-a-slot-test` reads the derived value; `grid-test/hints-describe-the-clicks-test` is back to a new project's grid, with its Master, and an empty one.

**Red first**, with `fader-values`, `with-faders` and `master-only?` written and nothing using them: faders, input-mapper, show-clock and subs: 58 tests, 401 assertions, 22 failures. After the Clear, the re-learn, the undo, New and Open the Master stayed dark (0.0 where full is 192.0). After a real learn it showed CC 9's old 96.0, not 48.0. The snapshot had no `:faders`. The mapper wrote `[:macro slot]` and read app state once a CC (16 reads). The empty-grid sub was false with only the Master. UI: 4 tests, 38 assertions: `meters-say-a-dash-for-an-unbound-slot-test` timed out (the meter kept "Intensity 30 %" after Clear), and `hints-describe-the-clicks-test` failed (no line with the Master); the two adapted tests passed.

**Verification, 2026-11-19, macOS arm64, JDK 25**

| Check | Result |
|---|---|
| `clj -M:mac-arm:test faders input-mapper input show-clock frame-service modulation templates persistent vibes` | 336 tests, 2,277 assertions, 0 failures; `-n laser-show.subs-test` 25 tests, 191 assertions, 0 failures |
| `clj -M:test-ui:mac-arm:test-ui-headless -n` perform-test, vibes-test, grid-test (grid-test has the Effects tab's line) | 37 tests, 291 assertions, 0 failures |
| `clj -M:mac-arm:test` (full unit suite) | 1,089 tests, 34,272 assertions, 0 failures (baseline at `8bb1332`: 1,083 tests, 34,221 assertions) |
| `clj -M:kondo --lint` on the touched files, with `testfx.clj` and `vibes/reactions.clj` | 0 errors; 1 warning, there before (`input.clj:423`). Without `reactions.clj`, kondo's cache still had its 2-argument `reactions` from before 4b and reported `subs.clj:1222` |
| full UI suite | not run here: it runs after the merge |
| after the merge, on `master` at `70f7db8`: `clj -M:mac-arm:test`, then the full UI suite | 1,089 tests, 34,273 assertions, 0 failures; UI 134 tests, 675 assertions, 0 failures |

