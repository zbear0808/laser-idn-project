# Phase Z5: zone patterns on cues and effect cells, the Map cycle, pattern pads

**Written:** 2026-11-19   **Written for commit:** `f178f56` (`master`; first written at `739bd74`, then re-verified here: `src/` is as vibe Phase 5 left it in `9f5d78a`, after vibe Phase 4b (`f5a4f84`), 4a (`6715fb4`) and UX Phase 3 (`b9b9d55`), and `test/` as `43fa00d` left it; every line below is at that source). Z2–Z4 edit several of these files first, so **re-check every path and line at HEAD before starting.**
**Status:** Proposed. Not to be built yet (D-8).   **Needs:** Z4 (`06`), and through it Z3's routing and "each picture once" (`05`) and Z2's rig (`04`). Vibe Phase 8a has **not** landed (`routing/` holds only `zone_effects.clj` at `f178f56`), so Z5 builds the stage Phase 8 designed, over refs. If 8a lands first, Z5 extends it instead (§3). Vibe **Phase 4 has landed** (4a `6715fb4`, 4b `f5a4f84`): pulses, Restart on, the Envelope, Step and Energy modulators, and tap reactions with `listens-to`. So patterns step on its pulses from the start (§4.2), and the names below are as built (`plans/2026-11-18-vibe-mode/04-phase4-tap-reactions.md` §10–11). Vibe **Phase 5 has landed** too (`9f5d78a`): its Fader modulator can drive a pattern's rate, offset or style (§8.3), and a new project's Master cell is a point effect, outside the zone stage (§7.2). V10 is still open (vibe `00` §7, line 274): this doc plans with its A.
**Implements spec** (`01-model-and-routing-spec.md`): §4 (the Map cycle in the effective map, and its pause: S-2), §5 (the order within a tick: S-3), §6.1 (pattern copies: S-1), §6.2 a, §9 (pattern-pad switches), invariants 1, 3, 4, 8 and 10. S-1 to S-3 started in this doc and are now in the spec. Overview §6 (vibe Phase 8), and D-4, D-5, D-11, D-16, D-19, D-25, D-26.   **Size:** ~4.5 d

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`. **NEW** marks what doesn't exist yet. Z2–Z4 names are the ones their docs propose; `rig/resolve-ref` is Z3's name for the one ref reader (Z4 writes it `ref-entries`).

After this phase you can:
- **Give a cue a zone pattern** from the Pattern chip in its editor's footer: Alternate, Chase, Ping-pong, Random, Shift or Swap. Its All picture moves between zones (or its zones' own content takes turns), on the beat, counted from the cue's start or on the show's beats.
- **Put a pattern in an effects-grid cell.** Every playing cue follows it, after the cue's own pattern, in grid order.
- **Step a pattern on a press.** HIT, ACCENT or FLASH, a vibe change or any cue start (vibe Phase 4's pulses) moves it one step, and so do a learned Zone step key and the STEP pad. A pattern can also restart on a press, as Phase 4's modulators do. Its rate, offset and style take any modulator, so a Step can change its style on each HIT, and the Morph fader (vibe Phase 5) can speed it up.
- **Cycle the zone map** with a Map cycle cell: Left / Right, then Odd / Even, a bar each. Press a map pad and the cycle pauses on that map. Press CYCLE and it carries on.
- **Switch every pattern's style live** from pattern pads on the Grid tab and Perform, or from any learned key: on the next beat, or the next bar with Shift.
- **Play the same cue on any rig.** Alternate is the two halves of one laser, Left and Right on two, odd and even lasers under Odd / Even. A Chase across "Every laser" runs over however many lasers are on (§8.5).
- **Trust it.** A picture lit in two zones is drawn once on each laser, every move is a cut, and a vibe never switches on a pattern that can reach the crowd unless it allows the audience.

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| A cue's frames | `services/frame_service.clj:345-412` (`combined-by-zone` `:394-403`) | `{key frame}`, one key per item's zone. Items under one key are joined (`merge-zone-maps`, `:309-324`). Nothing moves a frame to another key afterwards |
| Effects-grid effects | `services/frame_service.clj:83-100`, `:102-121`, `:404-412` | The active cells' items in `(vals effect-chains)` order (insertion order up to 8 cells, then hash order: vibe probe v24) become one point chain. It runs once per key of each cue, at the cue's tempo. `global-ctx` is only set when there are point effects (`:405`) |
| The zone category | `animation/effects.clj:55-62`; `animation/effects/zone.clj:43-53` | Holds only the Zone Selector, an identity transducer that routing reads raw. Z4 hides it (`06` §4.2) |
| The registry | `animation/effects.clj:77-101` | Needs an `:apply-transducer`; other keys are kept but unread. An unknown effect id is skipped (`:250-252`) |
| A parameter's value | `animation/effects.clj:256-279`, `:389-409`; `animation/keyframes.clj:225-276`; `animation/interpolation.clj:37-50` | Keyframes or params, defaults merged, modulators resolved once. Between keyframes numbers interpolate, and anything else holds the earlier keyframe's value (`:47-49`) |
| What takes a modulator | `views/components/modulator_param_control.clj:534-560`; `views/components/parameter_controls.clj:196-232` | `:float` and `:int` only. A `:choice` is a combo box that shows `(name item)` (`:108-136`); only a modulator's own choices show `:labels` (`modulator_param_control.clj:133-162`). An unknown type falls through to a slider |
| Custom editors | `views/components/effect_param_ui.clj:86-104`, `:183` | A renderer per `:ui-hints :renderer`. An active modulator forces numeric mode, except for single-parameter renderers (`:92-94`) |
| Where a chain lives | `events/handlers/chain/helpers.clj:14-45`; `events/handlers/chain/params.clj:72-87` | `chain-config` maps a domain to an items vector, and every param, modulator and keyframe event goes through it (`:timeline-items` and `:timeline-tracks` already map extra domains onto a cue). `:chain/update-param` stores any value |
| The banks | `views/components/effect_bank.clj:26-31`, `:90-121`; `views/dialogs/effect_chain_editor.clj:33-44`; `views/dialogs/cue_chain_editor.clj:36-49` | Both editors pass `:include-zone? true` |
| The cue editors' footers | `views/dialogs/cue_chain_editor.clj:608-653`; `views/dialogs/timeline_cue_editor.clj:142-171` | Zone, trigger mode, buttons, each with a tooltip (`test/laser_show/ui_test/main_window_test.clj:201-215`). Z4 makes the first a Target field |
| The clock | `services/show_clock.clj:50-65`, `:76-98`, `:143-170`; `animation/show_time.clj:201-229`, `:310-313` | Renders once per cue per tick. Z3 makes the routing after the render. Reports go back as events |
| Live switching | Z4 (`06` §4.1, §6): `zone-maps/switch-at`, `schedule`, `effective-map-id [state beats cycle]` with `cycle` always nil, `:show-clock/read`, map pads | The map pads' timing (spec §9) |
| Presses as pulses (vibe Phase 4a) | `[:playback :pulses]` (`state/domains.clj:86-87`), written by the vibe engine (`vibes/engine.clj:108-130`, `:206-214`) and `grid/start-cue` (`events/handlers/grid.clj:50`); stamped in `show-time/step` (`animation/show_time.clj:234-262`); `show-time/pulse-view` (`:267-271`) | Every HIT, ACCENT and FLASH press, vibe change and cue start counts. The clock stamps them once per tick (`animation/pulses.clj:69-84`) on a clock Stop never resets, and puts the view `{source {:count :beats :ms :held? …}}` (`:105-122`) in the snapshot (`services/show_clock.clj:158-164`), on every cue's timing context (`:58`) and on the effects grid's (`services/frame_service.clj:115-121`). `pulses/rebase` (`animation/pulses.clj:132-150`) is Restart on. The sources are `[:hit :accent :flash :vibe :any :cue]` (`:24-27`). During blackout no press pulses: only BLACKOUT does anything (`vibes/engine.clj:440-453`). Nothing steps a zone |
| Tap reactions (vibe Phase 4a, 4b) | `vibes/reactions.clj:32-63`, `:143-159`; `events/handlers/effects.clj:88-102`; `views/components/grid_cell.clj:329-349`, `:395-396`; `views/components/vibe_pads.clj:30-36`; `vibes/library.clj:428-451` | One click on an empty effects cell makes a template, switched on (identity at rest). `listens-to` finds the sources a chain follows, but only in modulator configs (a pulse type's `:source`, a `:restart-on`). The Perform pads name and flash their reactions, and the lint says when one follows a key nobody bound |
| Learnable keys | `events/handlers/triggers.clj:60-78`, `:140-152`; `events/handlers/input.clj:233-238`, `:317-367`, `:369-388` | Vibe actions skip the bookkeeping, and a vibe role keeps one key (`:341`, `:358`). Since vibe Phase 5 a fader learns a moved control into `[:config :input :macro-map]` instead (`handle-fader-captured`, `:369-388`) |
| Faders (vibe Phase 5) | `animation/modulators.clj:133-141`, `:351-360`; `animation/modulator_evaluators.clj:257-265`; `vibes/faders.clj:28-34`, `:60-69`; `state/templates.clj:105-110` | A Fader modulator (`:macro`) on any modulatable parameter follows fader 1 or 2 through the render's `:input-values` (on both timing contexts: `services/show_clock.clj:57`, `services/frame_service.clj:115-121`). A new project starts with the Master at `[0 3]`, an Intensity on fader 1. A vibe "wires" a fader when an effect it plays follows it |
| Effect cells and vibes | `vibes/engine.clj:74-85`, `:149-160`, `:188-195`, `:373-397`; `vibes/library.clj:82-140`, `:453-512` | A vibe's FX set and flash FX switch on every existing cell, unchecked. Only cues get the audience rule (V6). The lint checks cues, and since Phase 4b the roles' reactions |
| Effect cells and saving | `state/history.clj:14-22`, `:118-126`; `state/persistent.clj:94-117` | `:active?` is live. Cells a vibe switched on are saved off |

## 2. Why / root cause

- A cue's frames are keyed once, before any clock runs. Nothing between the render and the lasers can move a picture to another zone on the beat or on a press. Phase 8 designed that stage for the effects grid. The zones plan needs it on cues too, over refs, and able to copy.
- Copying was unsafe (probe v22: 68 points instead of 33) until Z3 drew each key once. But a copy that joins an occupied zone becomes a new object, which identity can't recognise, so Z5 keeps a copy as **one frame under the union of its places** (S-1, now spec §6.1).
- A choice can't follow a modulator, so a style can't change on an LFO or a press.
- The effective map has a hook for a cycle (`06` §4.1) but nothing fills it. And without a pause, a running cycle would override a pad press on the tick after it lands (S-2, now spec §4).
- Phase 4's presses reach every render as pulses, but nothing turns a press into a zone step, and `listens-to` can't see a source named by a plain parameter.

## 3. Reuse / extend

| Existing or planned (file:line) | How Z5 uses it |
|---|---|
| Phase 8a's design (`plans/2026-11-18-vibe-mode/08-phase8-zone-effects.md` §4, §7): `step-index`, `ring-index`, `lane`, `:apply-zones`, `:scope`, `resolve-effect-params`, `get-active-zone-effects`, reading order, the stage before the point effects | Built here with the same names, so 8b builds on them. **If 8a has landed:** extend its `zone_remap.clj` instead. Its `:zone-chase`, `:zone-shift` and `:zone-swap` stay registered and run as `:zone-pattern` with a fixed style (`:zone-a` and `:zone-b` become the lane) |
| Phase 4a's `pulses/step-index` with `:scatter` (`animation/pulses.clj:183-200`, its invariant 6) | Random: a closed form that never gives the same zone twice in a row |
| Phase 4a's pulses: `[:playback :pulses]` (`state/domains.clj:86-87`), `pulses/sources` (`animation/pulses.clj:24-27`), the pulse view in the snapshot and on every render context (`services/show_clock.clj:58`, `:158-164`; `services/frame_service.clj:115-121`), `pulses/rebase` (`animation/pulses.clj:132-150`) | Step on a press reads its source's `:count` from the tick's view, and Restart on is `rebase`. The Zone step key is one more source, `:zone-step`, at `[:playback :pulses :zone-step]`, so the clock stamps and views it with no change to `stamp` or `view` |
| Phase 4a's choice lists and labels: `source-labels`, `restart-on-param`, `source-param` (`animation/modulators.clj:21-42`) | The Step on and Restart on choices and their words ("HIT", "Vibe change", "Any cue start"), with `:zone-step` added, so Envelope, Step, Energy and Restart on can follow the Zone step key too |
| Phase 4a and 4b's reactions: `reactions/listens-to`, `roles-of` (`vibes/reactions.clj:143-159`), the pads' reactions line, the lint's `unheard` (`vibes/library.clj:438-451`) | A pattern that steps on HIT is one of HIT's reactions (S-10): named on its pad, and in the lint when nobody bound its key |
| Phase 4a's `new-reaction-menu` and `:effects/new-reaction` (`views/components/grid_cell.clj:329-349`, `:395-396`; `events/handlers/effects.clj:88-102`) | "New zone pattern ▸" on an empty effects cell, beside "New tap reaction ▸" |
| `vibe-pads/redraw-pulse!` (`views/components/vibe_pads.clj:30-36`) | The STEP pad flashes on each press, from the snapshot's pulses |
| Z3 (`05` §4): frame keys in normal form, `compose/key-order`, `animation/frames/concatenate-frames`, `rig/resolve-ref`, `cached-routing`, `apply-global-effects-once`, `:item-points` | Union keys for copies, the join order, a join without a require cycle, lanes and known refs, and the point effects after the stage |
| Z4 (`06` §4): `routing.targets`, `zone-maps` (`switch-at`, `due?`, `schedule`, the `cycle` slot of `effective-map-id`), `:show-clock/read`, the map pads and `redraw-blink!`, `target_control` scopes, `audience-ref?` | The pattern pads' timing and look, the Map cycle, the lane picker, the audience rule |
| `effect-parameter-editor` and `chain-config` (`events/handlers/chain/helpers.clj:31-45`) | A cue's pattern is stored as a one-item chain, so params, modulators and keyframes edit it with no new event |
| `:effects/toggle-cell` (`events/handlers/effects.clj:29-48`), the engine's `take-fx` (`vibes/engine.clj:149-160`) | Where switching a Map cycle cell on resumes the cycle |
| `ext-clock-redraw` (`views/components/clock_view.clj:58-71`) | The pattern window's "Now" line, and the CYCLE pad's mark, drawn from the snapshot |

## 4. Changes per namespace

### 4.1 The stage: `laser-show.routing.zone-remap` (NEW, pure)

A frame key is a set of places (spec §1). A pattern turns each key into a new key, or drops it:

| Kind | Styles | Key `k` becomes | Notes |
|---|---|---|---|
| **move** | lit-set styles, picture source | `(k − {from}) ∪ S` for each `k` that holds `from`. With `:from :any`, every `k` becomes `S` | `S` is the step's lit zones. An empty `S` hides the picture. Other keys stay |
| **gate** | lit-set styles, zones source | `(k − L) ∪ (k ∩ L ∩ S)`, nil if empty | `L` is the lane. Keys with no lane ref stay |
| **permute** | Shift, Swap | each lane ref replaced by its image | A bijection: nothing hides, nothing is copied |

The new keys are put in normal form. Frames that land on one key are joined with Z3's `concatenate-frames`, in `compose/key-order` of their old keys, and an object already in the join isn't added again.

- `step-index [offset rate t]` → `⌊offset + rate·t + 1e-9⌋` (Phase 8).
- `ring-index [step n path]` → `:loop`: `step mod n`. `:bounce`: `m = step mod (2n − 2)`, then `m < n ? m : 2n − 2 − m`. `n = 1` gives 0.
- `scatter-index [step n seed]` → Phase 4a's `pulses/step-index :scatter` (`animation/pulses.clj:183-200`) at `step + seed`, for `n ≥ 3`: a golden-ratio walk that never gives the same place twice in a row, and its fraction is floored, so a negative step (a backwards rate) works too. For `n = 2` it is `step mod 2`: Phase 4a uses `rem` there, which is negative for a negative step (its press counts never are). `n = 1` gives 0.
- `lane [refs known? lasers]` → the refs in order. NEW token `:lane/lasers` ("Every laser, left to right") expands to `:auto.laser/<id>` for each laser in E, in rig order, or to `[:auto/left :auto/right]` with one laser. Unknown refs and repeats are dropped (Phase 8).
- `lit-set [style params lane step]` and `permutation [style lane step]` (§8.2).
- `rekey [frames f]` → frames with each key mapped by `f`: **the same map object** when `f` leaves every key alone.
- `apply-pattern [frames params ctx]`: the effective style (a pad's, or `:off`, §4.2), `t`, the step, the lane, then move, gate or permute. Phase 8's `chase`, `shift` and `swap` are this with a fixed style.
- `apply-zone-stage [frames patterns ctx]` (Phase 8's `apply-zone-effects`): `apply-pattern` over the patterns in order.
- `rekey-item-points [item-points f]`: Z3's `:item-points` follow the same keys, so an editor's "Most points" still finds the item's laser.
- `ctx` is `{:beats {:cue b :global b} :pulses view :routing r :known? f :lasers [pid …] :live-style s}`, where `view` is the tick's pulse view (`show-time/pulse-view`), already on both timing contexts.

### 4.2 The model: `laser-show.routing.zone-patterns` (NEW, pure)

- `styles` `[:alternate :chase :ping-pong :random :shift :swap]` (Z6 adds `:steps`). `presets` (§8.2). `preset-instance [style where]` → an effect instance, with `:clock :cue` when `where` is `:cue`.
- `step-sources` → `(into [:clock] pulses/sources)`: the beat, or any of Phase 4a's pulse sources, `:hit :accent :flash :vibe :any :cue` (`animation/pulses.clj:24-27`), to which Z5 adds `:zone-step`, the Zone step key.
- `pattern-t [params ctx]` → what the step counts:
  - `:step-on :clock`: the beats of the pattern's `:clock` (the cue's window beats, or the global clock's). With a `:restart-on` source, the beats since that source's newest press: `(:effective-beats (pulses/rebase timing-ctx source true))` (`animation/pulses.clj:132-150`, Restart on as built). Before the first press the pattern runs on its own clock, as a looping modulator does;
  - a pulse source: its `:count` in the tick's pulse view (`animation/pulses.clj:105-122`), 0 before its first press. That is the presses since the show clock started (`show-time/reset-runtime!` seeds the stamps with no counts, `animation/show_time.clj:358-368`, `animation/pulses.clj:40-45`) or since the show was opened (`reset-session` clears `[:playback]`). Stop and Retrigger don't reset it (S-11).
- `effective-style [params live]`: the pad's style when the pattern has `:follow-pads?`. `:off` means "don't move".
- `live-style [state beats]`: `[:pattern-pads :pending]` once it's due, else `[:pattern-pads :style]`. `schedule-style [state style bar? clock]`: Z4's rule, through NEW `zone-maps/schedule-slot` (§4.3).
- `active-cycle [state]` → `{:cell :params}`: the last enabled `:zone-map-cycle` in an active cell, in reading order. `cycle-pick [params pulses beats maps]` → a map id (unknown ids dropped), stepping as `pattern-t` says.
- `reach [state params]` → every ref the pattern can send content to: its whole lane, expanded over the rig. A pad can switch any pattern to a style that moves, so the style doesn't narrow it.
- `warnings [state params names]` (§8.8) and `describe [params names]` ("Alternate · A ⇄ B · 1 a beat").
- `animation/pulses.clj` (Phase 4a's): `sources` (`:24-27`) gains `:zone-step`. It has no release, like `:vibe` and `:cue`, and isn't part of `:any`. `stamp` (`:69-84`) and `view` (`:105-122`) need no change, because they take every source app state has. The first version of this doc called the source `:step`; it is `:zone-step` so that nobody reads it as Phase 4a's Step modulator (`:step`).

### 4.3 Effects, parameters, maps

- `animation/effects.clj`: `register-effect!`'s docstring (`:78-92`) lists `:apply-zones`, `:select-map`, `:scope` and Z4's `:hidden?`. NEW `zone-stage-effect?`, NEW `choice-at [choices v]` (index `⌊v + 1e-9⌋`, clamped), NEW `resolve-effect-params [instance timing-ctx]` (Phase 8's: the choice at `:256-279`, defaults, `resolve-params-global`, then each `:modulatable?` choice whose raw value was a modulator goes through `choice-at`).
- `animation/effects/zone.clj`: NEW `:zone-pattern` ("Zone pattern") and `:zone-map-cycle` ("Map cycle"). Both are category `:zone`, `:timing :bpm`, `:scope #{:global}`, with an identity `:apply-transducer`. Renderers `:zone-pattern` and `:map-cycle`. Their parameters are in §8.2.
- NEW parameter types `:ref`, `:ref-list` (ordered; any ref and `:lane/lasers`) and `:map-list`. A `:choice` may say `:modulatable? true`.
- `animation/modulators.clj` (Phase 4a's): `restart-on-param` and `source-param` (`:31-42`) offer `:zone-step` too, and `source-labels` (`:21-29`) becomes public and names it "Zone step key". So an Envelope, Step or Energy, and any Restart on (the keyframe panel's shares `restart-on-param`), can follow the Zone step key, and the pattern's own Step on and Restart on choices take the same words.
- `routing/zone_maps.clj` (Z4's): NEW `schedule-slot [slot value bar? clock]` is what `schedule` does to `:active` and `:pending`, now shared with the pattern pads. `effective-map-id` gets the cycle's pick and `[:zone-map :cycle-paused?]`. NEW pad id `:cycle`: pressing it resumes the cycle. A map switch that lands while a cycle runs sets `:cycle-paused? true`. NEW `resume-cycle [state cells]` clears it when one of `cells` holds a Map cycle.

### 4.4 The render and the clock

- `services/frame_service.clj`:
  - `get-active-global-effects` (`:83-100`) leaves out zone-stage effects. NEW `get-active-zone-effects [state]`, in reading order: row, then column, then chain order.
  - `render-context` (`:102-121`, the 4-arity Phase 4a added, whose `:pulses` also go on `:global-ctx`) gains `:zone-effects` and the clock's `:zone-ctx`.
  - `generate-frames-by-zone` (`:345-412`): after `combined-by-zone` (`:394-403`) come the cue's `:zone-patterns` (params at the cue's `timing-ctx`), then `:zone-effects` (params at `global-ctx` with the cue's tempo; `:405` sets it whenever a stage or a point effect needs it), then Z3's `apply-global-effects-once`. Both timing contexts carry the tick's pulses already (`services/show_clock.clj:58`, `services/frame_service.clj:115-121`), so Step on and Restart on need nothing more. A throw is reported under `[:zone-patterns cell]` and leaves the frames as they were, as `:295-307` does. The result's metadata gains `:zone-steps {pattern-id step}`.
- `services/show_clock.clj`, `tick!` (`:143-170`), in this order (spec §5, S-3): `step` (`:154`); the pulse view, which Phase 4a already makes here (`:158`); `zone-ctx` (live style, known refs, laser order; the counts are the pulse view's); the Map cycle's pick; Z4's `effective-map-id` with it; Z3's cached routing, **moved before the render**; `render` (`:159`) with both. The snapshot gains `:zone-steps` and `:routing :cycle {:cell :map :paused?}`, beside Phase 4a's `:pulses` (`:164`).
- `animation/show_time.clj`, `pending-reports` (`:201-216`): `{:event/type :show-clock/pattern-due :id :style}` while `[:pattern-pads :pending]` is due.

### 4.5 State, history, saving

- `state/domains.clj`: NEW domain `pattern-pads` `{:style nil :pending nil}`. Z4's `zone-map` gains `:cycle-paused? false`. `config` gains `:pattern-pads {}`. Phase 4a's `[:playback :pulses]` (`:86-87`) needs nothing: `:zone-step` is one more source in it, and its docstring names it.
- `state/history.clj`: nothing joins the document roots. The policy table (`:14-22`) gains the row "live, own domain: `[:pattern-pads]` (not saved)".
- `state/persistent.clj`, `reset-session` (`:151-169`): resets `:pattern-pads` and `[:zone-map :cycle-paused?]`. It resets `[:playback]` already (`:158`), and the Zone step count with it.
- `events/handlers/chain/helpers.clj`, `chain-config` (`:31-45`): NEW domain `:cue-zone-patterns`, items-path `[:chains :cue-chains k :zone-patterns]`, ui-path `[:ui :dialogs :zone-pattern-editor]`.

### 4.6 Events

- NEW `events/handlers/zone_patterns.clj`, routed for `"zone-patterns"` and `"pattern-pads"` (`events/handlers.clj:38-67`): the events of §6.
- `events/handlers/timing.clj`: NEW `:show-clock/pattern-due`. `:transport/stop` and `:transport/retrigger` also commit a pending pattern switch, as Z4's do for maps.
- `events/handlers/triggers.clj` (`:60-113`, `:146-148`): actions `{:type :pattern-pad :target style}` and `{:type :zone-step}`, on press only, with no bookkeeping.
- `events/handlers/input.clj`: learning a pattern pad or the Zone step key again moves its key. `learn-target` (`:233-238`, which Phase 5 made keep `:slot` too) needs nothing for `{:type :pattern-pad :target style}`. Z4 generalises the role rule (`:341`, `:358`) to `:vibe` and `:zone-map`, and Z5 adds `:pattern-pad` and `:zone-step`. `action-name` (`:317-326`) says "pattern pad Chase" and "zone step". Like a vibe pad's, a pattern pad's learn takes a note or a button, never a CC (Phase 5's fader learn is `handle-fader-captured`, `:369-388`).
- `events/handlers/effects.clj`, `toggle-cell` (`:29-48`), and the engine's `take-fx` and `press-flash`: switching a cell on calls `zone-maps/resume-cycle`.
- `events/handlers/history.clj` (`:125-163`): the labels of §6.

### 4.7 Vibes: the audience rule and the lint

- `vibes/library.clj`: NEW `audience-fx? [state cell groups]` and `playable-fx [state vibe cells]` (Phase 8b's names). `audience-cue?` (`:107-110`, on Z4's targets by then) also counts each of the cue's patterns' `reach`. `vibe-problems` (`:453-484`) gains §8.8's rows for the FX set and the flash FX, beside Phase 4b's reaction rows (`moves-only`, `unheard`, `:428-451`).
- `vibes/reactions.clj` (Phase 4a's): `effect-sources` (`:143-146`) also reads a zone pattern's or a Map cycle's `:step-on` and `:restart-on` when they name a source (S-10). Then `listens-to` (`:148-154`) and `reactions` (`:176-202`) count a pattern that steps on HIT as one of HIT's reactions, with no other change: the Perform pad names it, and Phase 4b's lint rows count it ("HIT moves effects only (Run)", "Groove · Run listens to HIT, which has no key yet"). `roles-of` (`:156-159`) stays as it is: `:zone-step`, like `:cue`, is no role's.
- `vibes/faders.clj` (Phase 5's) needs nothing: `wires?` (`:66-69`) looks for a Fader modulator in any effect's params, so a pattern whose Rate follows fader 2 counts as wiring Morph.
- `vibes/engine.clj`: `take-fx` (`:149-160`) and `press-flash` (`:373-397`) switch on `playable-fx` only. A refused cell sets `[:vibe-runtime :problem] {:why :audience-fx :cell c}`.
- `subs.clj`, `vibe-pick-fx` (`:1171-1175`), gains `:blocked :crowd`, as `vibe-pick-cell` (`:1162-1169`) has.

### 4.8 Views

- NEW `views/components/zone_pattern_editor.clj`: `pattern-chip`, `pattern-visual` (the renderer), `lane-control`, `fold-line`, `now-line` (an `ext-clock-redraw` on `:zone-steps`), `map-cycle-visual`.
- NEW `views/dialogs/zone_pattern_editor.clj`: the "Zone Pattern — Cue A1" window, the shared `effect-parameter-editor` on `:cue-zone-patterns`.
- `views/components/effect_param_ui.clj`: `:zone-pattern` and `:map-cycle` join the renderer `case` (`:183`) and the set that keeps visual mode with modulators (`:92-94`).
- `views/components/parameter_controls.clj` (`:196-232`): `:ref`, `:ref-list`, `:map-list`. `param-choice` (`:108-136`) shows a param's `:labels`, as Phase 4a's `modulator-param-choice` does (`modulator_param_control.clj:133-162`), so numeric mode says "HIT" and "Vibe change", not `hit` and `vibe`. `views/components/modulator_param_control.clj` (`:534-560`): a `:modulatable?` choice goes to NEW `modulator-choice-control` (a combo box when static; when modulated, Min and Max are indices, labelled "0 Alternate · 1 Chase …", and a new modulator starts from 0 to n − 1, the bounds its synthetic spec gives).
- `views/components/effect_bank.clj` (`:90-121`): a `:scope` prop (Phase 8). The effects-grid editor passes `:global`; the cue editor and the timeline browser pass `:item`, so with Z4's hidden selector the item bank has no Zone tab.
- Both footers get a `Pattern:` chip after Z4's Target field.
- NEW `views/components/pattern_pads.clj`: `pattern-pad`, `step-pad`, `pattern-strip` (Grid tab: a flow pane that wraps when narrow) and `pattern-pad-row` (Perform), on Z4's pad shell and `redraw-blink!`. The STEP pad flashes on each press with Phase 4a's `vibe-pads/redraw-pulse!` (`views/components/vibe_pads.clj:30-36`) on `:zone-step`, and shows the count from the same snapshot. Z4's map strip and row gain the CYCLE pad, and a `:cycling` pseudo-class set from the snapshot.
- `views/tabs/grid.clj`: `:header-extra` is the map strip above the pattern strip. `views/tabs/perform.clj` (`:129-142`): "ZONE PATTERN" under Z4's "ZONE MAP"; both go after Phase 5's fader meters (`slider-meters/meter-row`, `:141`) and before the status line.
- `views/components/grid_cell.clj`: Z4's cue badge gains the pattern's glyph. An effects cell's tooltip names the style and the lane. An empty effects cell's menu (`:395-396`) gains "New zone pattern ▸" after Phase 4a's "New tap reaction ▸" (`new-reaction-menu`, `:329-349`).
- `subs.clj`: NEW `cue-zone-pattern [col row]`, `zone-patterns-in-use?`, `pattern-pad [style]`, `pattern-fold [params]`, `map-cycle-state`.
- `css/zones.clj` (Z4's): `.pattern-pad` (`-active`, `-pending`, `-learning`, `:blink`), `.pattern-chip`, `.zone-map-pad:cycling`.

## 5. State placement

| Path | Lives in | Written by | Undo / unsaved? |
|---|---|---|---|
| `:zone-patterns [instance]` on a cue (v1 edits one) | `[:chains :cue-chains]`, document | `:zone-patterns/set-cue-pattern`; `:chain/*`, `:modulator/*`, `:keyframe/*` on `:cue-zone-patterns` | yes / yes |
| `:zone-pattern` and `:zone-map-cycle` effects in a cell | `[:chains :effect-chains]`, document | the bank, `:zone-patterns/new-cell`, `:chain/*` | yes / yes |
| the cell's `:active?` | document, live (`state/history.clj:118-126`) | toggles, triggers, vibes | no / no |
| `[:pattern-pads :style]` (nil = As set, `:off`, a style) | NEW `pattern-pads` domain, not a document root, **not saved** (Z5-Q3) | `schedule-slot`, `:show-clock/pattern-due`, Stop, Retrigger | no / no |
| `[:pattern-pads :pending] {:id :style :at-beat :bar?}` | the same, runtime | `schedule-slot`, the commit, `reset-session` | no / no |
| `[:config :pattern-pads style] {:bar? true}` | `config`, document | `:pattern-pads/set-pad-bar` | yes / yes |
| trigger-map `{:type :pattern-pad :target style}`, `{:type :zone-step}`, `{:type :zone-map :target :cycle}` | `[:config :input :trigger-map]` | learn, `:pattern-pads/clear-control` | yes / yes |
| `[:playback :pulses :zone-step] {:id n}` | session, in Phase 4a's pulses (`state/domains.clj:86-87`; `[:playback]` isn't a document root) | `:pattern-pads/step` | no / no |
| `[:zone-map :cycle-paused?]` | Z4's `zone-map` domain, runtime | a map switch landing, CYCLE, a cycle cell switched on, `reset-session` | no / no |
| snapshot `:zone-steps`, `:routing :cycle` | per tick | the show clock | – |

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? / undo label |
|---|---|---|---|
| `:zone-patterns/set-cue-pattern {:col :row :style}` | NEW | the Pattern chip | yes / "Change Zone Pattern". nil removes it. A style on a cue that has a pattern changes `:style` only |
| `:zone-patterns/new-cell {:col :row :style}` | NEW | an empty effects cell's "New zone pattern ▸" | yes / "New Zone Pattern". The cell starts off and its editor opens. A tap reaction starts on (`:effects/new-reaction`, `events/handlers/effects.clj:88-102`) because it's identity at rest, but a pattern moves pictures at once |
| `:chain/update-param`, `:modulator/*`, `:keyframe/*` with `:domain :cue-zone-patterns` or `:effect-chains` | yes (`events/handlers/chain/params.clj:72-87`, `events/handlers/modulator.clj`, `events/handlers/keyframe.clj`) | the pattern editor | yes / their labels |
| `:ui/open-dialog :zone-pattern-editor {:col :row}` | yes, new id | the chip's "Edit pattern…" | yes |
| `:pattern-pads/pointer {:style :fx/event}` | NEW | a pad's mouse or touch press | yes: `pointer-action`'s `:press` (`events/handlers/vibes.clj:34-54`) → `:pattern-pads/press`, `:bar?` from Shift |
| `:pattern-pads/press {:style :bar? :src :input-time}` | NEW | the pointer, a learned key | yes: `{:show-clock/read {:on-read {:event/type :pattern-pads/schedule …}}}` |
| `:show-clock/read` | Z4's inline effect | `:pattern-pads/press` | effect |
| `:pattern-pads/schedule {:style :bar? :input-time :clock}` | NEW | `:show-clock/read` | yes: `schedule-style` |
| `:show-clock/pattern-due {:id :style}` | NEW | the clock's reports | yes: commits only if `:id` is still pending |
| `:pattern-pads/step {:src :input-time}` | NEW | the STEP pad, a learned key | yes: `[:playback :pulses :zone-step :id]` + 1, as `grid/start-cue` pulses `:cue` (`events/handlers/grid.clj:50`); the clock stamps it at its next tick. Nothing while `[:vibe-runtime :blackout?]`, as no press pulses then (`vibes/engine.clj:440-453`, Phase 4's invariant 9) |
| `:pattern-pads/set-pad-bar {:style :bar?}`, `:pattern-pads/clear-control {:style}` | NEW | a pad's menu | yes / "Change Pad Timing", "Clear Control" |
| `:zone-map/press {:map :cycle}` | Z4's, NEW id | the CYCLE pad, a learned key | yes: schedules the resume |
| `:effects/toggle-cell` | yes (`events/handlers/effects.clj:29-48`) | a cell, a trigger | yes: switching on a Map cycle cell resumes the cycle |

No handler reads the clock: `:show-clock/read` hands it the beats (Z4's SQ-3). The clock writes no app state: the commit is `:show-clock/pattern-due`.

## 7. UX

### 7.1 A cue's pattern

```
 Footer:  Target: [ (All) ▾ ]   Pattern: [ Alternate · A ⇄ B ▾ ]   Trigger: [Toggle ▾]   ⚡ Triggers...   …   Close
                                ┌──────────────────────────────────────┐
                                │   None                                │
                                │ ✓ Alternate   A ⇄ B                   │
                                │   Chase       across every laser      │
                                │   Ping-pong   across every laser      │
                                │   Random      across every laser      │
                                │   Shift       A → B → C → D           │
                                │   Swap        A ⇄ B (what's in them)  │
                                │ ───────────────────────────────────── │
                                │   Edit pattern…                       │
                                └──────────────────────────────────────┘
┌ Zone Pattern — Cue A1 "Beams" ─────────────────────────────────────────────────────┐
│ Style   [Alternate ▾]                    ☑ Follows the pattern pads                  │
│ Zones   [A → Left ×] [B → Right ×] [+ ▾]                                             │
│ Moves   (•) Automatic  ( ) The All picture  ( ) What's already in the zones         │
│ Rate    [1 ▾] step a beat      Step on [The beat ▾]      Count from [the cue's start ▾] │
│                             Restart on [No ▾]                                         │
│ Offset  [ 0.00 ] ∿                                                                    │
│ Now     B (step 5)                                                                    │
│ ⓘ This rig (1 laser, Left / Right): A is its left half, B its right half.            │
│ [Visual | Numeric]                                             [Remove]  [Close]     │
└───────────────────────────────────────────────────────────────────────────────────────┘
```
- A style on a cue without a pattern adds that preset. On a cue with one, it changes the style only: the zones, the rate and the rest stay, as when a pad switches it.
- The window shows only the rows the style uses: Path and Width for Chase, Width for Ping-pong and Random, Seed for Random, Zones only (two pickers) for Swap. While Step on is the beat, a "Restart on" row offers Phase 4a's sources, to start the pattern again from its first step at each press of one. Rate, Offset and Style have ∿ to take a modulator. Numeric mode is the full parameter list, and the keyframe panel works as on any effect.
- "Now" and the fold line come from the snapshot and the current routing. While the cue isn't playing, "Now" says "– (not playing)".

### 7.2 The effects grid

- The effects-grid editor's Zone tab lists **Zone pattern** and **Map cycle**, with the hint "Zone patterns run after each cue's own pattern, cell by cell, row by row, left to right." The cue editor's item bank has no Zone tab.
- An empty effects cell's menu gains "New zone pattern ▸ Alternate, Chase, Ping-pong, Random, Shift, Swap, Map cycle", after Phase 4a's "New tap reaction ▸". A new project's Master (vibe Phase 5: `create-starter-effects`, `state/templates.clj:105-110`, puts `faders/master-cell` at `[0 3]`) is a point effect, an Intensity on fader 1, so it isn't in the zone stage and doesn't change its reading order.
- A pattern cell's tooltip: "Zone pattern: Chase across every laser, 2 steps a beat (show beats)".

### 7.3 Pattern pads

```
 Grid tab:  Zone map: [● Left / Right] [Odd / Even] [Unison] [↻ CYCLE]
            Pattern:  [● As set] [Off] [Alternate] [Chase] [Ping-pong] [Random] [Shift] [Swap] │ [STEP]
 Perform:   ZONE PATTERN
            ┌─────────┐ ┌─────────┐ ┌──────────┐ ┌──────────┐ ┌─────────┐ ┌─────┐
            │ AS SET  │ │ OFF     │ │ ALTERNATE│ │ CHASE    │ │ … SWAP  │ │STEP │
            │ now     │ │         │ │ next beat│ │          │ │         │ │  7  │
            │ C3      │ │         │ │ D3       │ │ Right-click to learn │ │ E3  │
            └─────────┘ └─────────┘ └──────────┘ └──────────┘ └─────────┘ └─────┘
```
- **What a pad switches:** the style of every pattern that follows the pads, on cues and in cells (Z5-Q1). Each keeps its own zones and rate. **As set** gives every pattern its own style back. **Off** stops them moving: content stays where it was drawn.
- **When:** as the map pads (spec §9, Z4 §8.4): at once within 50 ms of a beat, else the next beat; the next bar with Shift or the pad's "Switch on the bar"; at once with no clock. One switch waits at a time: a new press replaces it, and pressing the active pad cancels it. The waiting pad blinks amber and says "next beat" or "next bar".
- **STEP** steps every pattern whose "Step on" is the Zone step key. It shows the count and flashes on each press, as the vibe pads' pulse bars do. It doesn't wait for a beat: it's a press, like HIT. A pattern can step on HIT, ACCENT or FLASH just as well; STEP is for a key that does nothing else (HIT also plays cues and taps the tempo).
- The strip and the row show while the show has a zone pattern anywhere. The pads never take the keyboard focus. Their right-click menu: "Switch on the bar", "Learn control…", "Cancel learning", "Clear control". Learning again moves the key.

### 7.4 The Map cycle

- A Map cycle cell's editor: "Maps [Left / Right ×] [Odd / Even ×] [+ ▾]", "Every [1 bar ▾]" (the rate), "Path", "Step on", Offset. Its tooltip: "Map cycle: Left / Right, Odd / Even, a bar each".
- While a Map cycle cell is on, the map strip shows **↻ CYCLE**, lit, and marks the map the cycle shows now with ↻.
- Pressing a map pad switches as in Z4. When it lands, the cycle **pauses**: CYCLE says "paused", and the pressed map stays. Pressing CYCLE (on the beat, like any pad) or switching a Map cycle cell on resumes it. With no cycle cell on, CYCLE is dim: "Switch on a Map cycle cell on the Effects grid".
- Z4's Target menu header says "ZONES · map: cycling (Left / Right, Odd / Even)" while a cycle runs.

| Where | Exact text |
|---|---|
| chip, none | "Pattern: None" |
| chip tooltip | "Zone pattern: Alternate. Moves the All picture A ⇄ B, 1 step a beat, from the cue's start." |
| Moves | "Automatic: the All picture if the cue draws into All, else what's in the zones" · "The All picture" · "What's already in the zones" |
| Step on | "The beat", then Phase 4a's words for the sources (`animation/modulators.clj:21-29`): "HIT" · "ACCENT" · "FLASH" · "Vibe change" · "HIT, ACCENT or FLASH" · "Any cue start" · and Z5's "Zone step key" |
| Restart on | "No" (`:own`), then the same words. Phase 4a calls `:own` "Its cue's start", but a cell's pattern counts the show's beats, so the pattern says "No" |
| Count from | "the cue's start" · "the show's beats" |
| lane picker | "Every laser, left to right", then Z4's zones and groups (a NEW `:pattern` scope: no Inherit, no Combine, no Pin) |
| pad tooltips | As set: "Every pattern plays the style it was made with" · Off: "Patterns that follow the pads stop moving" · Alternate: "Every other zone, in turn" · Chase: "One zone after another" · Ping-pong: "Along the zones and back" · Random: "A different zone each step, never the same twice" · Shift: "What's in the zones moves along" · Swap: "The first two zones trade what's in them" · STEP: "Steps the patterns that step on the Zone step key" |
| fold line | "This rig (3 lasers, Left / Right): A is Laser 1 and the left half of Laser 2; B the right half of Laser 2 and Laser 3." Under Unison: "Under Unison every zone is All, so this pattern doesn't move." |
| refused cell (Perform status) | "B3 (Zone pattern) can reach the crowd: tick Allow audience cells in this vibe" |

## 8. Data examples and invariants

### 8.1 Shapes

```clojure
[:chains :cue-chains [0 0]]
{:name "Beams"
 :items [{:type :preset :id #uuid "…01" :preset-id :beam-fan}]          ; no target: All
 :zone-patterns [{:id #uuid "…p1" :effect-id :zone-pattern :enabled? true
                  :params {:style :alternate :zones [:zone/a :zone/b] :source :auto :from :all
                           :rate 1.0 :offset 0.0 :step-on :clock :clock :cue :follow-pads? true}}]}
[:chains :effect-chains [0 2]]
{:name "Run" :active? true
 :items [{:id #uuid "…p2" :effect-id :zone-pattern :enabled? true
          :params {:style :chase :zones [:lane/lasers] :path :bounce :width 1 :rate 2.0
                   :offset {:type :sine :min 0.0 :max 3.0 :period 8.0} :step-on :clock :clock :global}}]}
[:chains :effect-chains [1 2]]
{:name "Maps" :active? true
 :items [{:id #uuid "…c1" :effect-id :zone-map-cycle :enabled? true
          :params {:maps [:map/left-right :map/odd-even] :rate 0.25 :offset 0.0 :path :loop :step-on :clock}}]}
[:pattern-pads]           {:style :chase :pending {:id #uuid "…" :style :off :at-beat 33.0 :bar? false}}
[:config :pattern-pads]   {:random {:bar? true}}
[:config :input :trigger-map {:source :midi :channel 0 :note 40}] {:type :pattern-pad :target :chase}
[:config :input :trigger-map {:source :midi :channel 0 :note 41}] {:type :zone-step}
[:playback :pulses]       {:zone-step {:id 7} :hit {:id 31 :held? false}}   ; Phase 4a's, plus Z5's source
[:zone-map]               {:active :map/left-right :pending nil :cycle-paused? false}
;; snapshot
{:cues [{:cell [0 0] :zone-frames {:zone/b #<33 points>}}] :zone-steps {#uuid "…p1" 9}
 :pulses {:zone-step {:count 7 :beats 0.4 …} :hit {:count 31 …}}                 ; Phase 4a's pulse view
 :routing {:map :map/odd-even :cycle {:cell [1 2] :map :map/odd-even :paused? false} …}}
```

### 8.2 Styles and parameters

`:zone-pattern` (one effect for every style, so a pad or a modulator can switch the style):

| Key | Type | Default | Used by | Meaning |
|---|---|---|---|---|
| `:style` | `:choice`, modulatable | `:alternate` | all | `:alternate :chase :ping-pong :random :shift :swap` (Z6: `:steps`) |
| `:zones` | `:ref-list` | per preset | all | the lane, in order: zones, All, groups, lasers or `:lane/lasers` |
| `:source` | `:choice`, modulatable | `:auto` | lit-set styles | `:auto` (move when a key holds `:from`, else gate), `:picture` (move), `:zones` (gate) |
| `:from` | `:ref` or `:any` | `:all` | move | where the picture comes from. `:any` is everything the cue draws |
| `:rate` | `:float` −8…8 | 1.0 | all | steps a beat, or a press. Negative runs backwards; 0 stands still |
| `:offset` | `:float` −64…64 | 0.0 | all | steps at t = 0 |
| `:step-on` | `:choice` | `:clock` | all | `:clock`, or a pulse source: Phase 4a's `:hit :accent :flash :vibe :any :cue`, or `:zone-step` |
| `:clock` | `:choice` | `:cue` on a cue, `:global` in a cell | `:step-on :clock` | the cue's beats from its start, or the show's |
| `:path` | `:choice`, modulatable | `:loop` | Chase | `:loop`, `:bounce` |
| `:width` | `:int` 1…8 | 1 | Chase, Ping-pong, Random | zones lit at once |
| `:seed` | `:int` 0…99 | 0 | Random | starts the order that many steps on, so two Random patterns don't move together |
| `:follow-pads?` | `:bool` | true | all | pattern pads may switch its style |
| `:restart-on` | `:choice` | `:own` | `:step-on :clock` | `:own`, or a pulse source: the beats count from that source's newest press (`pulses/rebase`, as Phase 4a's modulators restart) |

`:zone-map-cycle`: `:maps` (`:map-list`, `[:map/left-right :map/odd-even]`), `:rate` (0.25: a map a bar), `:offset`, `:path`, `:step-on`. It always counts the show's beats.

| Style | Default lane | Step `k` (lane `L` of `n` refs) |
|---|---|---|
| Alternate | `[:zone/a :zone/b]` | lit: the even places of `L` on even steps, the odd places on odd steps |
| Chase | `[:lane/lasers]` | lit: `width` places from `ring-index(k, n, path)`, wrapping. With `:bounce`, the window bounces inside `L` (`n − width + 1` positions) |
| Ping-pong | `[:lane/lasers]` | Chase with `:bounce` |
| Random | `[:lane/lasers]` | lit: `width` places from `scatter-index(k, n, seed)`, wrapping |
| Shift | `[:zone/a … :zone/d]` | permute: `L[i]` → `L[(i + k) mod n]` |
| Swap | `[:zone/a :zone/b]` | permute: on odd steps `L[0]` ⇄ `L[1]` |

On the lane A, B, C, D:

| k | Alternate | Chase | Chase, width 2 | Ping-pong | Random (seed 0) | Shift: A's content is in | Swap |
|---|---|---|---|---|---|---|---|
| 0 | A C | A | A B | A | C | A | as drawn |
| 1 | B D | B | B C | B | A | B | A ⇄ B |
| 2 | A C | C | C D | C | C | C | as drawn |
| 3 | B D | D | D A | D | B | D | A ⇄ B |
| 4 | A C | A | A B | C | D | A | as drawn |
| 5 | B D | B | B C | B | C | B | A ⇄ B |

### 8.3 Time and steps

The step is `⌊offset + rate·t + 1e-9⌋`, with:

| `:step-on` | `t` | Example: rate 1, offset 0 |
|---|---|---|
| `:clock`, `:clock :cue` | the cue's beats from its start (a looping timeline's window beats, so it loops with the cue) | step 0 at the cue's start, step 1 a beat later |
| `:clock`, `:clock :global` | the show's beats; bars count from global beat 0, as the beat squares do (`services/show_clock.clj:127`) | every cue moves together |
| `:clock`, `:restart-on :flash` | the beats since the newest FLASH press (`pulses/rebase`); its own clock before the first | the chase starts again from its first zone at each FLASH |
| `:zone-step` | the Zone step key's `:count` in the pulse view | each press: one step, at the next tick |
| `:hit`, `:accent` … | that source's `:count` | each HIT: one step |

Rate 0.5 is a step every 2 beats, rate 2 every half beat, rate −1 runs backwards. Rate 0 with a modulated offset lets the modulator pick (Phase 8 §7's driving table). A step on the clock lands exactly where `offset + rate·t` crosses a whole number: D-11's window is for pad switches only. Modulators run where the pattern lives: a cue's at the cue's clock, a cell's at the global clock with the cue's tempo, as every effect's do. Two landed modulators are worth naming:
- **Phase 4a's Step** (`step-params`, `animation/modulators.clj:96-105`) on Style, `:min 0 :max 5 :steps 6`, following HIT: each HIT moves to the next style. On Offset, with the palette `[0 4]` and following ACCENT, each ACCENT jumps an eight-step pattern half way round.
- **Phase 5's Fader** (`:macro`, `animation/modulators.clj:133-141`, registered at `:351-360`) on Rate, `:slot :slider-2`, `:min 0.5 :max 4`: the Morph fader speeds the pattern up, and a vibe that plays it counts as wiring Morph (§4.7).

### 8.4 Copies (Phase 8's invariant 1, changed)

**Copies are allowed only in lit-set styles with a picture source and more than one lit zone** (Alternate on a lane of three or more, a width of 2 or more, Z6's grid). Gates only take places away, and permutations are bijections, so neither copies.

A copy is **one frame under the union of its places**, never one object under several keys. Three lasers, Left / Right, middle `:split`. The cue draws a circle X (33 points) into A and its All picture P (33 points); Alternate over A–D:

| Even step | Keys | p1 draws | p2 draws |
|---|---|---|---|
| Z5: a union key | `{:zone/a X, [:zone/a :zone/c] P}` | X, P (68 points: one join) | X and P in its left half |
| identical objects under `:zone/a` and `:zone/c` | `:zone/a` is X joined with P, a new object; `:zone/c` is P | X, P, P (103 points) | X, P, P in its left half |

So Phase 8's invariant 1 becomes: *a remap changes no point and no colour. It moves pictures between keys (a key is a set of places), may hide a picture (a gate or an empty step), and joins pictures that land on one key. A picture lit in several places is one frame under the union of those places, so every laser any of them reaches draws it once (spec §6 steps 1–3), and no join holds one object twice. The points that come out are those of the frames that aren't hidden, plus the jump points of the joins.*

### 8.5 Folding onto the rig

The cue draws only its All picture P. Lanes wrap (D-25), the middle laser splits (spec §3), and each place set is one key (§8.4).

**Alternate over A–D** (A C on even steps, B D on odd ones):

| Lasers | Left / Right: even | odd | Odd / Even: even | odd | Unison |
|---|---|---|---|---|---|
| 1 | p1's left half | p1's right half | p1's left half | p1's right half | p1 whole, every step |
| 2 | p1 | p2 | p1 | p2 | p1, p2 |
| 3 | p1, p2's left half | p2's right half, p3 | p1, p3 | p2 | all three |
| 4 | p1, p2 | p3, p4 | p1, p3 | p2, p4 | all four |
| 5 | p1, p2, p3's left half | p3's right half, p4, p5 | p1, p3, p5 | p2, p4 | all five |

**Chase over A–D** lights A, B, C, D in turn. Under Left / Right that's Left, Right, Left, Right, and under Odd / Even it's Odd, Even, Odd, Even: per laser, the Alternate table again, with steps 0 and 2 as "even" and 1 and 3 as "odd". Under Unison every laser draws P on every step.

**Chase across Every laser** (`:lane/lasers`) doesn't use zones, so the map doesn't matter:

| Lasers | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|
| Steps | left half, right half | p1, p2 | p1, p2, p3 | p1 … p4 | p1 … p5 |

Also:
- With the middle on `:both`, p2 is in Left and Right whole, so under Alternate it draws P on every step. Only one side is lit at a time, so it never auto-splits (D-27).
- A two-wide Chase under a two-lane map lights both lanes on every step, so nothing moves. Each laser still draws P once.
- Spec §6.2 a is Alternate on one laser: `{:zone/a P}`, then `{:zone/b P}`, drawn in `:L`, then in `:R`.

### 8.6 The Map cycle and the pads

Cell B2 holds `[:map/left-right :map/odd-even]` at rate 0.25 (a bar each); 120 BPM:

| Global beats | What happens | Effective map | Map strip |
|---|---|---|---|
| 0–3.99 | B2 on | Left / Right (step 0) | CYCLE lit, ↻ on Left / Right |
| 4–7.99 | | Odd / Even (step 1) | ↻ on Odd / Even |
| 9.30 | Unison pressed | pending at 10 | Unison blinks |
| 10 | it lands | Unison; the cycle pauses | Unison lit, CYCLE "paused" |
| 13.98 | CYCLE pressed (within 0.1 beat of 14) | pending at 14 | CYCLE blinks |
| 14 | it lands | the cycle's step 3: Odd / Even | CYCLE lit |
| 20 | B2 switched off | the active map, Unison | no CYCLE pad |

The effective map at a tick (spec §4 with S-2): **(1)** a pending switch once due (`:cycle` means the cycle's pick); **(2)** the cycle's pick, if a cycle cell is on and not paused; **(3)** `[:zone-map :active]`. Two Map cycles on at once: the later one in reading order wins, and the lint says so. Pattern pads land as Z4 §8.4 times map pads: a press at 16.04 switches now, one at 16.50 at beat 17, and one at 16.50 with Shift at bar 5 (beat 20).

### 8.7 Order and determinism

Per cue, per tick: **the cue's own patterns** (in their order) → **the cells' patterns** (reading order: row, column, then chain order, Phase 8 §7) → **the point effects**, once per distinct frame (Z3 §6.1). The Map cycle touches no frame: it is read once per tick, before the routing.

The order matters. A cue's Alternate over A, B takes P from All, and then a cell's Swap of A and B runs on top:

| Cue step | Cell step | After the cue's pattern | After the cell's Swap |
|---|---|---|---|
| 0 | 1 | `{:zone/a P}` | `{:zone/b P}` |
| 1 | 1 | `{:zone/b P}` | `{:zone/a P}` |

Run the other way round, the Swap would find nothing in A or B and do nothing. Every output is a pure function of the frames, the params, `t`, the tick's pulse view and the routing: Random is a closed form of the step, with no random state.

### 8.8 The audience rule and the lint

A pattern **reaches its whole lane**, expanded over the rig: a pad can switch any pattern to a style that moves. `:from` is where the picture comes from, so it doesn't count (Phase 8). A ref counts when Z4's `audience-ref?` says so: it reaches a `:crowd-scanning` laser, or it's a zone that some map sends there. Rig: p1–p4; p5 tagged `:crowd-scanning`; Z4 §8.6's "Crowd sweep" map sends B to Crowd.

| Cell or cue | Why | Audience-capable? |
|---|---|---|
| Chase across Every laser; p5 excluded from All (Q-Z2-1 B: so it's not in E) | p5 isn't in the lane | no |
| the same, p5 not excluded | p5 is in the lane | **yes** |
| Alternate over A, B | Crowd sweep sends B to Crowd | **yes** |
| a lane `[:auto/left :crowd]` | `:crowd` is listed | **yes** |
| a lane `[:zone/a :zone/c]` with `:from :crowd` | `:from` doesn't count; no map sends A or C there | no |
| a Map cycle with Crowd sweep in it | one of its maps has an audience lane | **yes** |
| the pattern effect is disabled | nothing moves | no |

A cue is audience-capable when its targets are (Z4) or one of its patterns is. A vibe never switches on an audience-capable cell unless it allows the audience (V10 A), and Perform says why (§7.4). Clicking the cell on the effects grid is a decision, and it isn't blocked.

Lint rows (`vibes/library.clj` `vibe-problems`, `:453-484`, for the FX set and the flash FX):

| Level | Condition | Message |
|---|---|---|
| warn (the level Phase 3 gives an audience cue) | an audience-capable pattern or cycle, and the vibe doesn't allow the audience | "Groove: B3 (Zone pattern) can send pictures to the crowd (Crowd Scanning), so this vibe never switches it on. Allow audience cells, or take Crowd Scanning out of its zones" |
| error | a style that moves needs two zones, and the lane has fewer known ones | "Groove: B3 (Zone pattern) needs two zones (it has one: A)" |
| warn | a lane names a group or a laser that's gone | "Groove: B3 (Zone pattern) lists Front, which no longer exists" |
| warn | a lane ref reaches no laser under the active map | "Groove: B3 (Zone pattern): Graphics has no laser, so the picture vanishes on that step" |
| info | the active map is Unison and the lane is zones only | "Groove: B3 (Zone pattern) doesn't move under Unison" |
| warn | two Map cycles in one vibe | "Groove: two Map cycles (B3, C1): C1 wins" |
| info | it steps on the Zone step key, and no key is learned for it | "Groove: B3 (Zone pattern) steps on the Zone step key, which has no key yet" |

A pattern that steps or restarts on HIT, ACCENT, FLASH or a vibe change needs no row of its own. Through `listens-to` (§4.7, S-10) it is one of that role's tap reactions, so Phase 4b's rows name it: "Groove: HIT moves effects only (Run)" (`moves-only`, `:428-433`) when HIT plays no cue, instead of "HIT does nothing", and "Groove · Run listens to HIT, which has no key yet" (`unheard`, `:438-451`).

The pattern window shows the same warnings, without the vibe's name (`zone-patterns/warnings`).

### 8.9 Invariants

1. **A remap changes no point and no colour** (§8.4). Every point out belongs to an input frame that wasn't hidden, or is a join's jump point.
2. **No pattern, no change.** With no pattern on the cue, none in an active cell and no Map cycle, `generate-frames-by-zone` returns what it returned before Z5, and the stage returns the same map object (Phase 8 invariant 2, spec invariant 3).
3. **Each picture once.** After the stage, no frame object is under two keys unless it already was, and no join holds one object twice. So a laser draws a copy once (spec invariant 4).
4. **Cuts only.** Keys change only at step boundaries. No point is ever between two zones.
5. **Deterministic.** The same frames, params, `t`, counts and routing give the same output, whatever the order of the keys in the map.
6. **Order:** the cue's patterns, then the cells' in reading order, then the point effects. Spec invariant 8 holds: there is one render per cue per tick, and neither the stage nor the cycle renders.
7. **Before safety.** The stage runs inside the render, before composition, the fit guard, the projector effects and Z1's safety (spec invariant 1, Phase 8 invariant 9).
8. **Lanes.** Unknown and repeated refs are dropped. A Shift by `k` and by `k + n` are equal, a Swap is its own inverse, and Shift by 0 and Swap on even steps return the same map (Phase 8 invariants 3, 4, 6). A one-ref lane sends the picture there; an empty lane is the identity.
9. **Keys don't depend on the map.** A map switch or a cycle step re-routes the same frame objects (Z4 invariant 2). Only the rig (`:lane/lasers`, known refs) feeds the stage.
10. **Unison:** every zone is All (spec invariant 10), so zone-only patterns don't move under it.
11. **Live switches** (pattern pads, CYCLE) add no undo step and don't mark the show unsaved. They land as spec §9 says, and the clock writes no app state.
12. **Who wins:** a map switch that lands while a cycle runs pauses it, and CYCLE or a cycle cell switched on resumes it (§8.6).
13. **Audience:** a vibe never switches on a pattern or a cycle that can reach the crowd unless it allows the audience (V10 A). A cue whose pattern can reach the crowd is audience-capable.
14. **Old builds:** a build without Z5 skips the unknown effect ids (`animation/effects.clj:250-252`) and ignores `:zone-patterns`, so it draws the cue unpatterned.
15. **Cost** (estimated; probe z5 measures it): a re-key is 0.2–0.8 µs, and a join of two 1,000-point frames 11–12 µs (Phase 8 probe v23). A pattern costs a few µs per cue per tick; the point effects then run on as many frames or fewer.
16. **Presses** (NEW with Phase 4). A pattern sees a press only through the tick's pulse view. So it steps, or restarts at step `⌊offset⌋`, at the first tick after the press, and every cue and cell sees the same count in that tick. The Zone step key, like HIT, does nothing during blackout (Phase 4's invariant 9).

## 9. Tests (named test vars, fixtures)

Fixtures: the cues and setup of `test/laser_show/routing/zone_routing_integration_test.clj:26-135`; Phase 8's probe v19 rig (`plans/2026-11-18-vibe-mode/probes/vibe_probes_test.clj:568-569`); `test-timing-ctx` (`test/laser_show/services/frame_service_test.clj:60-67`); the clock by hand, `init!` and `ticks!` (`test/laser_show/services/show_clock_test.clj:44-66`); Phase 4a's `clock`, `stamps-at` and `pulse` (`test/laser_show/animation/pulses_test.clj:9-28`), and `starter`, `ctx`, `press` and `render` (`test/laser_show/vibes/reactions_test.clj:17-42`: a timing context with a press in it, and the starter cues through a cell); Z3's `compose` fixtures and Z4's legacy shows; `with-input-mapper` (`test/laser_show/ui_test/perform_test.clj:29-39`); `with-toolkit` and `with-app` (`test/laser_show/ui_test/fixtures.clj:17-58`). A new project's effects grid has the Master at `[0 3]` (vibe Phase 5), so a UI test that needs an empty effects cell takes another one, as `43fa00d` had to for a Phase 4b test.

- `laser-show.routing.zone-remap-test` (NEW): `step-index-test` (floor, negatives, 2.9999999999, presses), `ring-index-loop-and-bounce-test`, `scatter-sequence-test` (C A C B D C for n = 4: Phase 4a's `step-index :scatter`), `scatter-never-repeats-test` (as Phase 4a's, `pulses_test.clj:242`, but n 2–16, seeds 0–9, 10,000 steps each way), `scatter-runs-backwards-test` (negative steps stay in range, n = 2 too), `lane-expands-every-laser-and-drops-unknown-test` (one laser gives its halves), `styles-table-test` (§8.2's table), `move-takes-the-picture-from-all-test`, `copy-is-one-union-key-test`, `move-into-an-occupied-zone-joins-test`, `empty-step-hides-the-picture-test`, `gate-keeps-lit-content-test`, `gate-narrows-a-union-key-test`, `auto-source-test`, `from-any-moves-everything-test`, `shift-and-swap-test`, `keys-outside-the-lane-stay-test`, `join-order-is-key-order-test`, `join-never-holds-a-frame-twice-test`, `no-pattern-is-the-same-map-test`, `off-and-pads-only-change-followers-test`, `item-points-follow-the-keys-test`, and the seeded `remap-invariants-test` (invariants 1, 3, 5 on generated frames and params).
- `laser-show.routing.zone-patterns-test` (NEW): `presets-are-valid-test`, `describe-test`, `cycle-pick-test`, `last-cycle-in-reading-order-wins-test`, `reach-is-the-whole-lane-test`, `warnings-test`, `schedule-style-test` (now, next beat, bar, replace, cancel, no clock), `live-style-is-pending-once-due-test`, `pattern-t-test` (the cue's beats, the show's, a source's `:count`, 0 before its first press, `:any` as the three summed), `restart-on-rebases-test` (the beats since the newest press; its own clock before the first).
- `laser-show.animation.pulses-test`: `zone-step-is-stamped-and-viewed-test`, `zone-step-is-not-in-any-test`. `laser-show.animation.modulation-test`: `zone-step-is-a-source-test` (every pulse type's Follows and every Restart on list `:zone-step`, as "Zone step key"). The UI's `modulator-editor-test/source-choice-shows-its-labels-test` (`:84-99`) passes unchanged.
- `laser-show.vibes.reactions-test` (beside `reactions-index-test`, `:181`): `pattern-on-hit-is-a-hit-reaction-test`, `pattern-restart-on-is-heard-test`, `clock-pattern-listens-to-nothing-test`, `zone-step-is-no-roles-test`.
- `laser-show.routing.zone-maps-test` (Z4's): `schedule-slot-is-the-map-rule-test`, `effective-map-order-with-a-cycle-test`, `paused-cycle-gives-the-active-map-test`. `laser-show.events.zone-maps-handlers-test` (Z4's): `map-switch-pauses-a-running-cycle-test`, `cycle-pad-resumes-test`, `switching-a-cycle-cell-on-resumes-test`, `no-cycle-nothing-to-pause-test`.
- `laser-show.animation.effects-test`: `resolve-effect-params-keyframes-then-params-test`, `modulated-choice-picks-by-index-test`, `zone-stage-effects-register-test`. `laser-show.animation.effects-coverage-test`: `expected-effect-ids` (`:28-34`) gains the two ids, and their parameters join `param-exemptions` (`:19-26`) as routing parameters.
- `laser-show.services.frame-service-test`: `cue-pattern-runs-before-grid-patterns-test`, `grid-patterns-run-in-reading-order-test` (twelve cells, probe v24), `stage-runs-before-point-effects-test`, `copy-gets-point-effects-once-test`, `throwing-pattern-leaves-the-frames-test`, `timeline-cue-pattern-loops-with-the-cue-test`, `cue-and-global-clock-test`, `pattern-steps-on-a-press-test` (reactions-test's `press` in both timing contexts), `no-pattern-renders-as-before-test`.
- `laser-show.routing.compose-test` (Z3's): `example-a-alternate-on-one-laser-test` (spec §6.2 a, now with the real pattern), `fold-tables-test` (§8.5: n = 1–5 × three maps × both styles, and Every laser), `copy-is-drawn-once-per-laser-test` (§8.4: 68, not 103).
- `laser-show.services.show-clock-test`: `map-cycle-sets-the-effective-map-test`, `pending-pattern-switch-is-effective-once-due-test`, `pattern-due-is-reported-test`, `snapshot-carries-zone-steps-and-cycle-test`, `routing-is-made-before-the-render-test`, `zone-step-press-steps-at-the-next-tick-test`. `renders-once-per-tick-test` (`:175-188`) and `one-state-and-input-read-per-tick-test` (`:342`) pass unchanged.
- `laser-show.events.zone-patterns-handlers-test` (NEW): `set-cue-pattern-adds-a-preset-test`, `set-cue-pattern-keeps-zones-test`, `new-cell-test`, `press-reads-the-clock-then-schedules-test`, `press-within-50-ms-switches-now-test`, `shift-waits-for-the-bar-test`, `no-clock-switches-at-once-test`, `input-time-backs-the-beats-off-test`, `pattern-switch-is-live-test`, `step-pulses-and-blackout-ignores-test`, `stop-commits-the-pending-switch-test`, `cue-pattern-domain-edits-params-test`.
- `laser-show.events.handlers.triggers-test` (beside the vibe action tests, `:109-146`): `pattern-pad-action-presses-test`, `zone-step-action-steps-test`. `laser-show.events.handlers.input-test` (beside `relearning-a-role-moves-its-binding-test`, `:147`): `relearning-a-pattern-pad-moves-its-key-test`. `laser-show.events.history-handlers-test`: the new labels name routed events.
- `laser-show.state.persistent-test`: `cue-patterns-survive-save-and-load-test`, `pattern-pads-are-not-saved-test`, `session-reset-clears-pads-and-pause-test`. `laser-show.state.history-test`: `pattern-switch-is-not-undoable-test`.
- `laser-show.vibes.library-test` (`with-projector`, `:48-50`): `audience-fx-test` (§8.8's table), `cue-pattern-reach-is-audience-test`, `lint-zone-pattern-rows-test`, and beside Phase 4b's `lint-effects-only-role-is-info-test` (`:315`) and `lint-reaction-on-an-unbound-role-test` (`:346`), `lint-counts-a-pattern-on-hit-test`. `laser-show.vibes.engine-test`: `audience-pattern-cell-is-not-switched-on-test`, `allowed-audience-cell-switches-on-test`, `refused-cell-is-reported-test`, `manual-toggle-is-not-blocked-test`.
- `laser-show.subs-test`: `pattern-pad-sub-test`, `zone-patterns-in-use-test`, `vibe-pick-fx-blocks-audience-cells-test`. NEW `laser-show.views.components.zone-pattern-editor-test`: `chip-text-test`, `rows-per-style-test`.
- UI, NEW `laser-show.ui-test.zone-patterns-test`: `footer-chip-adds-a-pattern-test`, `pattern-window-shows-the-fold-test`, `offset-takes-a-modulator-test`, `effects-zone-tab-lists-pattern-and-cycle-test`, `item-bank-has-no-zone-tab-test`, `pattern-pad-switches-test` (no clock thread in `start-test!`: at once), `pending-pattern-pad-blinks-test`, `pattern-pad-learns-a-note-test`, `step-pad-steps-a-pattern-test`, `cycle-pad-resumes-test`. `perform-test`: `pattern-row-shows-when-patterns-are-used-test` (under Phase 5's meter row). `main-window-test/cue-chain-editor-footer-fits-test` (`:179-199`) still fits with the chip, and `cue-chain-editor-footer-says-what-it-does-test` (`:201-215`) finds the chip's tooltip.
- Probe z5 (Z3's `plans/2026-11-19-zones/probes/zones_probes_test.clj`): the stage on 1,000-point frames with one cue pattern and three cell patterns.

Run: `clj -M:mac-arm:test zone-remap zone-patterns zone-maps effects frame-service compose show-clock pulses modulation reactions zone-patterns-handlers zone-maps-handlers triggers handlers.input history persistent library engine subs zone-pattern-editor`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.zone-patterns-test`, then `-n` for `perform-test`, `grid-test`, `main-window-test` and `modulator-editor-test`, then both full suites (the `targeted-ui-testing` skill).

## 10. Commit plan

Each step is one commit: its tests first (run them red), then the code, then its tests green.

| # | Commit message | Red first | ~d |
|---|---|---|---|
| 1 | `refactor(zones): the rule for when a live switch lands is shared, so map pads and pattern pads switch alike` | `schedule-slot-is-the-map-rule-test`; Z4's tests unchanged | 0.25 |
| 2 | `feat(zones): the zone stage: a pattern moves, copies, hides or trades pictures between places, a copy is one picture under all its places, joined in a fixed order` | `zone-remap-test`, `zone-patterns-test` | 0.75 |
| 3 | `feat(effects): Zone pattern and Map cycle are effects, their parameters follow keyframes and modulators, and a style can follow a modulator` | `effects-test`, `effects-coverage-test` | 0.25 |
| 4 | `feat(render): a cue's own zone pattern, then the effects grid's in grid order, move its pictures between zones before the point effects, on the beat or on HIT, ACCENT, FLASH, a vibe change or a cue start, and can restart on one, so one laser alternates its halves and a rig of five its sides` | `frame-service-test`, `compose-test` (fold tables), `zone-patterns-test` (`pattern-t-test`, `restart-on-rebases-test`) | 0.5 |
| 5 | `feat(zones): a Map cycle cell switches the zone map every bar or on a step; a map pad press pauses it and CYCLE carries on` | `show-clock-test`, `zone-maps-test`, `zone-maps-handlers-test` | 0.5 |
| 6 | `feat(zones): pattern pads switch every pattern's style on the next beat (Shift: the bar) from a pad or any learned key, and a Zone step key steps the patterns that step on it, as one more pulse source any pulse modulator can follow` | `zone-patterns-handlers-test`, `triggers-test`, `input-test`, `persistent-test`, `history-test`, `pulses-test`, `modulation-test` | 0.5 |
| 7 | `feat(ui): a Pattern chip in both cue editors, a pattern window that says how it folds onto this rig, and Zone pattern and Map cycle on the effects grid's Zone tab` | UI chip and window tests, `zone-pattern-editor-test` | 0.75 |
| 8 | `feat(ui): pattern pads on the Grid tab and on Perform, blinking while a switch waits, with a STEP pad, and a CYCLE pad on the map strip` | UI pad tests, `perform-test` | 0.5 |
| 9 | `feat(vibes): a vibe never switches on a zone pattern or Map cycle that can reach the crowd unless it allows the audience, a pattern that steps on HIT is one of HIT's tap reactions, and the lint says which` | `library-test`, `engine-test`, `subs-test`, `reactions-test` | 0.5 |
| 10 | `plans: zones Z5 done (<sha>): as built, deviations, tests, red-first results and probe z5` | – | – |

About 4.5 days. If it runs long, `:width`, `:seed` and the choice modulator can move to Z6. Vibe Phase 4 has landed, so stepping on a press is part of steps 4, 6 and 9, not a commit of its own. If Phase 8a lands first, step 2 extends its namespace, and its three effect ids stay as fixed styles.

## 11. Out of scope / alternatives considered

- **Fades between zones**: cuts only (overview §8). A fade needs gains per output.
- **Patterns on items, groups and tracks**: later. D-5 puts them on cues and cells, and a cue's `:zone-patterns` list already runs in order.
- **Per-cue pattern pads** (a pad that switches one cue's pattern): Z5-Q1 B/C. **A Hold pad** that freezes the step: later; Off keeps content where it was drawn.
- **The rig simulator** ("Preview on: 1, 2, 3 lasers", needs `06`): later. The fold line names this rig, and `rig/routing` with an invented rig is the simulator's core (spec invariant 9).
- **Center-out, time-share and other fold policies** (needs `06`): Center-out is one of Z6's grid starting points. Time-share isn't needed: at most two regions (D-1), and the lanes wrap (D-25).
- **One effect per style** (Phase 8's three): rejected. A pad and a modulator couldn't switch the style, and Z6's grid would be a fourth effect. Phase 8's ids stay only if 8a lands first.
- **Copies as one object under several keys** (spec §6.1's first wording, before it took S-1): rejected, because a join breaks the identity (§8.4). Z3's sharing stays, as a second line of defence.
- **Latest takes precedence between pads and the cycle** (Z5-Q2 B), or the cycle always winning (C): see Z5-Q2.
- **A Map cycle on a cue**: rejected. The map is global, so a cue's cycle would move every other cue.
- **Phase 8b's reactions** (`08` §7's `:zone-hop`, `:zone-swap`, `:zone-shift`, `:zone-chase`): they become `:zone-pattern` templates in Phase 4a's `reactions/templates` (`vibes/reactions.clj:32-63`), with `:step-on :hit` instead of a Step modulator on the offset, so "New tap reaction ▸" would offer them. That's Phase 8b's work.
- **A Morph template for patterns** ("Morph: Pattern speed"): later. Phase 5's `morph-templates` (`vibes/faders.clj:36-46`) are Size, Spin and Colour. A Fader on a pattern's Rate works without one (§8.3).
- **Weights or levels per zone**: Z6 keeps steps on or off (overview §8).

## 12. Spec questions and open questions

**Spec questions** (where `01` is unclear or the code disagrees, and what this doc chose)

| # | Spec | The problem | Chosen |
|---|---|---|---|
| S-1 | §6 step 4 and §6.1 said a pattern copy is "the same object" under several keys. **Now spec §6.1** ("Pattern copies (Z5 S-1)") | A copy landing in an occupied zone is joined into a new object, so a laser reached by both keys draws it twice (§8.4: 103 points instead of 68) | A copy is one frame under the union of its places (§1's normal form). Each key is drawn once (step 3), so it never relies on identity. Where nothing is joined, the output is the same |
| S-2 | §4 said: pending due → cycle → active. **Now spec §4** (the pause) and §12 (`[:zone-map :cycle-paused?]`) | Once a pad press commits to `:active`, the cycle beats it again on the next tick, so a press would last one tick | A map switch landing while a cycle runs pauses the cycle (`[:zone-map :cycle-paused?]`), and CYCLE or a cycle cell switched on resumes it (§8.6). Asked as Z5-Q2 |
| S-3 | §5 and Z3 (`05` §4) made the routing after the render. **Now spec §5** ("Order within a tick (Z5 S-3)") | The stage needs the rig for `:lane/lasers` and known refs, and the cycle's pick must come before the routing | `tick!`: step, Phase 4a's pulse view (already there), zone ctx, cycle pick, effective map, routing, render. The routing never depends on the frames (Z3's S-3), so nothing else changes |
| S-4 | §9: "a pattern-pad switch (Z5)" | It doesn't say what a pattern pad switches | The style of every pattern that follows the pads (Z5-Q1) |
| S-5 | §6 step 6: vector keys sort after single refs | A copied picture (a union key) is drawn after the zone-specific content in its bucket | Accepted: it changes the order only |
| S-6 | §11's Phase 8 row: `:zones`, `:from`, `:zone-a`, `:zone-b` take refs | Z5 has one effect, and Swap's pair is its lane's first two refs | `:zone-a` and `:zone-b` exist only if 8a lands first, and they read as the lane `[a b]` |
| S-7 | §1 and §3: refs | "Every laser" isn't a ref: a target can't name it | It's a lane token, expanded to `:auto.laser/*` refs (one laser: its halves), so the routing and frame keys only ever see refs. Asked as Z5-Q4 |
| S-8 | spec silent; the brief asks for modulatable choices | Only `:float` and `:int` take modulators (`modulator_param_control.clj:534-560`) | A choice that says `:modulatable? true` reads a modulator's value as an index (`⌊v + 1e-9⌋`, clamped). Only Z5's effects say so |
| S-9 | V10 (vibe `00` §7, line 274) is still open | The audience rule for cells needs it | Planned with V10 A. With B, the refusal becomes a lint warning only; with C, the lane picker hides audience refs |
| S-10 (NEW) | spec silent (it doesn't cover vibes); vibe Phase 4a's `listens-to` (`vibes/reactions.clj:143-154`), which 4b's lint reads | `listens-to` reads only modulator configs: a pulse type's `:source` and a `:restart-on`. A pattern's `:step-on :hit` is a plain parameter, so a pattern that steps on HIT wouldn't be one of HIT's reactions: no name on the pad, "HIT does nothing" in the lint, and no "no key yet" row | `effect-sources` also reads a zone-stage effect's `:step-on` and `:restart-on` (§4.7). `roles-of` is unchanged, so `:zone-step`, like `:cue`, is no role's. The vibe plan should say yes, since it changes 4a's `effect-sources` |
| S-11 (NEW) | spec silent; vibe Phase 4a's pulse view | What a press-stepped pattern counts. App state's `[:playback :pulses source :id]` and the view's `:count` were planned as the same number, but they aren't: a restarted show clock seeds its stamps with no counts (`animation/pulses.clj:40-45`), so `:count` starts again from 0 while `:id` goes on. Stop and Retrigger reset neither | The view's `:count`, as Phase 4a's Step does: it is already on every render's context, and a pattern and a Step on the same key stay in step. The show clock only restarts with the app (`app.clj:163`), and New and Open clear both |

**Open questions for you** (★ = recommended)

> **Z5-Q1 · What a pattern pad switches.**
> - **A ★** The style of every pattern that follows the pads, on cues and in cells. One row of pads is learnable on any controller, and each pattern keeps its zones and rate.
> - **B** One chosen pattern: the strip says "Pads act on: [Cue A1 ▾]".
> - **C** A pad per pattern and style, each learned on its own.

> **Z5-Q2 · Map pads against a running Map cycle.**
> - **A ★** A press pauses the cycle on the pressed map, until CYCLE is pressed or a cycle cell is switched on. The last thing the performer did wins.
> - **B** Latest takes precedence: the press holds only until the cycle's next step.
> - **C** The cycle always wins while it's on, and the map pads say "Map cycle running".

> **Z5-Q3 · Is the pads' live style saved with the show?**
> - **A ★** No: a show always opens "As set", so what the editor shows is what plays.
> - **B** Yes, like the zone map (D-14).

> **Z5-Q4 · "Every laser, left to right" in a pattern's zones.**
> - **A ★** Yes. A chase runs over however many lasers are on, and one laser chases its halves. It ignores the zone map.
> - **B** No: chases use zones, with a custom map per rig (a four-zone chase alternates under Left / Right).

> **Z5-Q5 · Random.**
> - **A ★** Phase 4's golden-ratio scatter: evenly spread, never the same zone twice in a row, the same on every run (rehearsal is the show).
> - **B** Seeded pseudo-random with a no-repeat rule: more irregular, still reproducible.
> - **C** Different every time it plays.

> **Z5-Q6 · Where a cue's pattern counts from by default.**
> - **A ★** The cue's start, so A always comes first.
> - **B** The show's beats, so every cue alternates in step with the others.
