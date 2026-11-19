# Phase 5: the faders (macros, CC learn, master intensity, Morph)

**Written:** 2026-11-18   **Written for commit:** `b1da0f2` (`master`)
**Status:** Proposed. Needs Phases 1 and 2; V5 (what the sliders mean); V9 (with V9 A the hue step is Phase 4's, so this phase has no palette and no `:macro/set`)   **Last verified against:** `b1da0f2` (probes v6, v9, v11 in `00-overview.md` §3)
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
| Global effects | `services/frame_service.clj:83-100, 288-300` | the active effect cells become one chain, applied to each zone frame of each cue. Probe v11: an `:intensity` effect on 1,000 points costs 38–40 µs (static) to 41–57 µs (modulated), about 240 KB of garbage |
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
- `laser-show.state.templates-test` (existing from Phase 1): NEW `master-cell-is-identity-until-the-fader-moves-test` (renders a cue through `frame-service/generate-frames-by-zone` with and without the cell and compares frames), `master-cell-dims-with-the-fader-test`, `morph-templates-are-neutral-until-moved-test`.
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
