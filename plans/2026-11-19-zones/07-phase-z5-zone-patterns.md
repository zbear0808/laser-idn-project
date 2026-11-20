# Phase Z5: zone patterns on cues and effect cells, the Map cycle, pattern pads

**Written:** 2026-11-19   **Written for commit:** `739bd74` (`master`; `src/` is as vibe Phase 3 left it in `38f92ad`, and every line below is at that source). Z2–Z4 edit several of these files first, so **re-check every path and line at HEAD before starting.**
**Status:** Proposed. Not to be built yet (D-8).   **Needs:** Z4 (`06`), and through it Z3's routing and "each picture once" (`05`) and Z2's rig (`04`). Vibe Phase 8a has **not** landed (there is no `routing/zone_remap.clj` at `739bd74`), so Z5 builds the stage Phase 8 designed, over refs. If 8a lands first, Z5 extends it instead (§3). V10 is still open (vibe `00` §7): this doc plans with its A.
**Update 2026-11-19 (at `d298801`):** vibe **Phase 4a has landed** (`6715fb4`): pulses, Restart on, and the Envelope, Step and Energy modulators, with `listens-to` and the reactions in `vibes/reactions`. So the Phase 4 path in §4.2 is **the path from the start**: HIT, ACCENT, FLASH, a vibe change and a cue start step patterns. §10's extra commit ("whichever lands second…") becomes part of Z5's own commits. Re-read the pulse names at HEAD; they may differ from Phase 4's plan (see `plans/2026-11-18-vibe-mode/04-phase4-tap-reactions.md`, "as built").
**Implements spec** (`01-model-and-routing-spec.md`): §4 (the Map cycle in the effective map, with S-2), §6 step 4 and §6.1 for pattern copies (with S-1), §6.2 a, §9 (pattern-pad switches), invariants 1, 3, 4, 8 and 10. Overview §6 (vibe Phase 8), and D-4, D-5, D-11, D-16, D-19, D-25, D-26.   **Size:** ~4.5 d

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`. **NEW** marks what doesn't exist yet. Z2–Z4 names are the ones their docs propose; `rig/resolve-ref` is Z3's name for the one ref reader (Z4 writes it `ref-entries`).

After this phase you can:
- **Give a cue a zone pattern** from the Pattern chip in its editor's footer: Alternate, Chase, Ping-pong, Random, Shift or Swap. Its All picture moves between zones (or its zones' own content takes turns), on the beat, counted from the cue's start or on the show's beats.
- **Put a pattern in an effects-grid cell.** Every playing cue follows it, after the cue's own pattern, in grid order.
- **Step a pattern on a press.** A learned Zone step key, or the STEP pad, moves it one step. With vibe Phase 4, HIT, ACCENT or FLASH can too.
- **Cycle the zone map** with a Map cycle cell: Left / Right, then Odd / Even, a bar each. Press a map pad and the cycle pauses on that map. Press CYCLE and it carries on.
- **Switch every pattern's style live** from pattern pads on the Grid tab and Perform, or from any learned key: on the next beat, or the next bar with Shift.
- **Play the same cue on any rig.** Alternate is the two halves of one laser, Left and Right on two, odd and even lasers under Odd / Even. A Chase across "Every laser" runs over however many lasers are on (§8.5).
- **Trust it.** A picture lit in two zones is drawn once on each laser, every move is a cut, and a vibe never switches on a pattern that can reach the crowd unless it allows the audience.

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| A cue's frames | `services/frame_service.clj:338-405` (`combined-by-zone` `:387-396`) | `{key frame}`, one key per item's zone. Items under one key are joined (`merge-zone-maps`, `:302-317`). Nothing moves a frame to another key afterwards |
| Effects-grid effects | `services/frame_service.clj:83-100`, `:102-114`, `:397-405` | The active cells' items in `(vals effect-chains)` order (insertion order up to 8 cells, then hash order: vibe probe v24) become one point chain. It runs once per key of each cue, at the cue's tempo. `global-ctx` is only set when there are point effects (`:398`) |
| The zone category | `animation/effects.clj:55-62`; `animation/effects/zone.clj:43-53` | Holds only the Zone Selector, an identity transducer that routing reads raw. Z4 hides it (`06` §4.2) |
| The registry | `animation/effects.clj:77-101` | Needs an `:apply-transducer`; other keys are kept but unread. An unknown effect id is skipped (`:250-252`) |
| A parameter's value | `animation/effects.clj:256-279`, `:389-409`; `animation/keyframes.clj:218-269`; `animation/interpolation.clj:37-50` | Keyframes or params, defaults merged, modulators resolved once. Between keyframes numbers interpolate, and anything else holds the earlier keyframe's value (`:47-49`) |
| What takes a modulator | `views/components/modulator_param_control.clj:465-491`; `views/components/parameter_controls.clj:196-232` | `:float` and `:int` only. A `:choice` is a combo box. An unknown type falls through to a slider |
| Custom editors | `views/components/effect_param_ui.clj:86-104`, `:183` | A renderer per `:ui-hints :renderer`. An active modulator forces numeric mode, except for single-parameter renderers (`:92-94`) |
| Where a chain lives | `events/handlers/chain/helpers.clj:14-45`; `events/handlers/chain/params.clj:72-87` | `chain-config` maps a domain to an items vector, and every param, modulator and keyframe event goes through it (`:timeline-items` and `:timeline-tracks` already map extra domains onto a cue). `:chain/update-param` stores any value |
| The banks | `views/components/effect_bank.clj:26-31`, `:90-121`; `views/dialogs/effect_chain_editor.clj:33-44`; `views/dialogs/cue_chain_editor.clj:36-49` | Both editors pass `:include-zone? true` |
| The cue editors' footers | `views/dialogs/cue_chain_editor.clj:602-643`; `views/dialogs/timeline_cue_editor.clj:142-171` | Zone, trigger mode, buttons. Z4 makes the first a Target field |
| The clock | `services/show_clock.clj:48-60`, `:71-92`, `:137-162`; `animation/show_time.clj:190-218`, `:285-288` | Renders once per cue per tick. Z3 makes the routing after the render. Reports go back as events |
| Live switching | Z4 (`06` §4.1, §6): `zone-maps/switch-at`, `schedule`, `effective-map-id [state beats cycle]` with `cycle` always nil, `:show-clock/read`, map pads | The map pads' timing (spec §9) |
| Presses | `events/handlers/triggers.clj:60-78`, `:140-152`; `events/handlers/input.clj:225-230`, `:284-334` | Vibe actions skip the bookkeeping, and a vibe role keeps one key. No press reaches a render (vibe Phase 4 isn't built) |
| Effect cells and vibes | `vibes/engine.clj:69-80`, `:117-128`, `:156-163`, `:323-349`; `vibes/library.clj:81-139`, `:422-468` | A vibe's FX set and flash FX switch on every existing cell, unchecked. Only cues get the audience rule (V6), and the lint only looks at cues |
| Effect cells and saving | `state/history.clj:14-22`, `:118-126`; `state/persistent.clj:94-117` | `:active?` is live. Cells a vibe switched on are saved off |

## 2. Why / root cause

- A cue's frames are keyed once, before any clock runs. Nothing between the render and the lasers can move a picture to another zone on the beat or on a press. Phase 8 designed that stage for the effects grid. The zones plan needs it on cues too, over refs, and able to copy.
- Copying was unsafe (probe v22: 68 points instead of 33) until Z3 drew each key once. But a copy that joins an occupied zone becomes a new object, which identity can't recognise, so Z5 keeps a copy as **one frame under the union of its places** (S-1).
- A choice can't follow a modulator, so a style can't change on an LFO or a press.
- The effective map has a hook for a cycle (`06` §4.1) but nothing fills it. And with spec §4's order, a running cycle would override a pad press on the tick after it lands (S-2).

## 3. Reuse / extend

| Existing or planned (file:line) | How Z5 uses it |
|---|---|
| Phase 8a's design (`plans/2026-11-18-vibe-mode/08-phase8-zone-effects.md` §4, §7): `step-index`, `ring-index`, `lane`, `:apply-zones`, `:scope`, `resolve-effect-params`, `get-active-zone-effects`, reading order, the stage before the point effects | Built here with the same names, so 8b builds on them. **If 8a has landed:** extend its `zone_remap.clj` instead. Its `:zone-chase`, `:zone-shift` and `:zone-swap` stay registered and run as `:zone-pattern` with a fixed style (`:zone-a` and `:zone-b` become the lane) |
| Phase 4's `:scatter` order (`04` §7, invariant 6) | Random: a closed form that never gives the same zone twice in a row |
| Phase 4's `[:playback :pulses]` shape (`04` §4) | The Zone step key counts at `[:playback :pulses :step :id]`, so when Phase 4 lands, `:step` is one more source |
| Z3 (`05` §4): frame keys in normal form, `compose/key-order`, `animation/frames/concatenate-frames`, `rig/resolve-ref`, `cached-routing`, `apply-global-effects-once`, `:item-points` | Union keys for copies, the join order, a join without a require cycle, lanes and known refs, and the point effects after the stage |
| Z4 (`06` §4): `routing.targets`, `zone-maps` (`switch-at`, `due?`, `schedule`, the `cycle` slot of `effective-map-id`), `:show-clock/read`, the map pads and `redraw-blink!`, `target_control` scopes, `audience-ref?` | The pattern pads' timing and look, the Map cycle, the lane picker, the audience rule |
| `effect-parameter-editor` and `chain-config` (`events/handlers/chain/helpers.clj:31-45`) | A cue's pattern is stored as a one-item chain, so params, modulators and keyframes edit it with no new event |
| `:effects/toggle-cell` (`events/handlers/effects.clj:27-46`), the engine's `take-fx` (`vibes/engine.clj:117-128`) | Where switching a Map cycle cell on resumes the cycle |
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
- `scatter-index [step n seed]` → for `n = 2`, `step mod 2`. Otherwise `(⌊n/2⌋ + ⌊frac(step·φ + c)·n⌋) mod n`, with `φ = 0.6180339887` and `c = frac(seed · 0.4142135624)` (Phase 4's `:scatter`, shifted by a seed).
- `lane [refs known? lasers]` → the refs in order. NEW token `:lane/lasers` ("Every laser, left to right") expands to `:auto.laser/<id>` for each laser in E, in rig order, or to `[:auto/left :auto/right]` with one laser. Unknown refs and repeats are dropped (Phase 8).
- `lit-set [style params lane step]` and `permutation [style lane step]` (§8.2).
- `rekey [frames f]` → frames with each key mapped by `f`: **the same map object** when `f` leaves every key alone.
- `apply-pattern [frames params ctx]`: the effective style (a pad's, or `:off`, §4.2), `t`, the step, the lane, then move, gate or permute. Phase 8's `chase`, `shift` and `swap` are this with a fixed style.
- `apply-zone-stage [frames patterns ctx]` (Phase 8's `apply-zone-effects`): `apply-pattern` over the patterns in order.
- `rekey-item-points [item-points f]`: Z3's `:item-points` follow the same keys, so an editor's "Most points" still finds the item's laser.
- `ctx` is `{:beats {:cue b :global b} :counts {source n} :routing r :known? f :lasers [pid …] :live-style s}`.

### 4.2 The model: `laser-show.routing.zone-patterns` (NEW, pure)

- `styles` `[:alternate :chase :ping-pong :random :shift :swap]` (Z6 adds `:steps`). `presets` (§8.2). `preset-instance [style where]` → an effect instance, with `:clock :cue` when `where` is `:cue`.
- `step-sources` → `[:clock :step]`, plus Phase 4's `pulses/sources` once that has landed. `step-counts [state pulse-view]` → `{source n}`: the pulse view's `:count` with Phase 4, else `[:playback :pulses source :id]`. They are the same numbers.
- `effective-style [params live]`: the pad's style when the pattern has `:follow-pads?`. `:off` means "don't move".
- `live-style [state beats]`: `[:pattern-pads :pending]` once it's due, else `[:pattern-pads :style]`. `schedule-style [state style bar? clock]`: Z4's rule, through NEW `zone-maps/schedule-slot` (§4.3).
- `active-cycle [state]` → `{:cell :params}`: the last enabled `:zone-map-cycle` in an active cell, in reading order. `cycle-pick [params counts beats maps]` → a map id (unknown ids dropped).
- `reach [state params]` → every ref the pattern can send content to: its whole lane, expanded over the rig. A pad can switch any pattern to a style that moves, so the style doesn't narrow it.
- `warnings [state params names]` (§8.8) and `describe [params names]` ("Alternate · A ⇄ B · 1 a beat").

### 4.3 Effects, parameters, maps

- `animation/effects.clj`: `register-effect!`'s docstring (`:78-92`) lists `:apply-zones`, `:select-map`, `:scope` and Z4's `:hidden?`. NEW `zone-stage-effect?`, NEW `choice-at [choices v]` (index `⌊v + 1e-9⌋`, clamped), NEW `resolve-effect-params [instance timing-ctx]` (Phase 8's: the choice at `:256-279`, defaults, `resolve-params-global`, then each `:modulatable?` choice whose raw value was a modulator goes through `choice-at`).
- `animation/effects/zone.clj`: NEW `:zone-pattern` ("Zone pattern") and `:zone-map-cycle` ("Map cycle"). Both are category `:zone`, `:timing :bpm`, `:scope #{:global}`, with an identity `:apply-transducer`. Renderers `:zone-pattern` and `:map-cycle`. Their parameters are in §8.2.
- NEW parameter types `:ref`, `:ref-list` (ordered; any ref and `:lane/lasers`) and `:map-list`. A `:choice` may say `:modulatable? true`.
- `routing/zone_maps.clj` (Z4's): NEW `schedule-slot [slot value bar? clock]` is what `schedule` does to `:active` and `:pending`, now shared with the pattern pads. `effective-map-id` gets the cycle's pick and `[:zone-map :cycle-paused?]`. NEW pad id `:cycle`: pressing it resumes the cycle. A map switch that lands while a cycle runs sets `:cycle-paused? true`. NEW `resume-cycle [state cells]` clears it when one of `cells` holds a Map cycle.

### 4.4 The render and the clock

- `services/frame_service.clj`:
  - `get-active-global-effects` (`:83-100`) leaves out zone-stage effects. NEW `get-active-zone-effects [state]`, in reading order: row, then column, then chain order.
  - `render-context` (`:102-114`) gains `:zone-effects` and the clock's `:zone-ctx`.
  - `generate-frames-by-zone` (`:338-405`): after `combined-by-zone` (`:387-396`) come the cue's `:zone-patterns` (params at the cue's `timing-ctx`), then `:zone-effects` (params at `global-ctx` with the cue's tempo; `:398` sets it whenever a stage or a point effect needs it), then Z3's `apply-global-effects-once`. A throw is reported under `[:zone-patterns cell]` and leaves the frames as they were, as `:288-300` does. The result's metadata gains `:zone-steps {pattern-id step}`.
- `services/show_clock.clj`, `tick!` (`:137-162`), in this order: `step`; `zone-ctx` (live style, counts, known refs, laser order); the Map cycle's pick; Z4's `effective-map-id` with it; Z3's cached routing, **moved before the render** (S-3); `render` with both. The snapshot gains `:zone-steps` and `:routing :cycle {:cell :map :paused?}`.
- `animation/show_time.clj`, `pending-reports` (`:190-205`): `{:event/type :show-clock/pattern-due :id :style}` while `[:pattern-pads :pending]` is due.

### 4.5 State, history, saving

- `state/domains.clj`: NEW domain `pattern-pads` `{:style nil :pending nil}`. Z4's `zone-map` gains `:cycle-paused? false`. `playback` (`:58-89`) gains `:pulses {}`, unless Phase 4 has added it. `config` gains `:pattern-pads {}`.
- `state/history.clj`: nothing joins the document roots. The policy table (`:14-22`) gains the row "live, own domain: `[:pattern-pads]` (not saved)".
- `state/persistent.clj`, `reset-session` (`:151-168`): resets `:pattern-pads` and `[:zone-map :cycle-paused?]`.
- `events/handlers/chain/helpers.clj`, `chain-config` (`:31-45`): NEW domain `:cue-zone-patterns`, items-path `[:chains :cue-chains k :zone-patterns]`, ui-path `[:ui :dialogs :zone-pattern-editor]`.

### 4.6 Events

- NEW `events/handlers/zone_patterns.clj`, routed for `"zone-patterns"` and `"pattern-pads"` (`events/handlers.clj:38-67`): the events of §6.
- `events/handlers/timing.clj`: NEW `:show-clock/pattern-due`. `:transport/stop` and `:transport/retrigger` also commit a pending pattern switch, as Z4's do for maps.
- `events/handlers/triggers.clj` (`:60-113`, `:146-148`): actions `{:type :pattern-pad :target style}` and `{:type :zone-step}`, on press only, with no bookkeeping.
- `events/handlers/input.clj`: learning a pattern pad or the Zone step key again moves its key. Z4 generalises the role rule at `:325` to `:vibe` and `:zone-map`, and Z5 adds `:pattern-pad` and `:zone-step`. `action-name` (`:284-293`) says "pattern pad Chase" and "zone step".
- `events/handlers/effects.clj`, `toggle-cell` (`:27-46`), and the engine's `take-fx` and `press-flash`: switching a cell on calls `zone-maps/resume-cycle`.
- `events/handlers/history.clj` (`:125-159`): the labels of §6.

### 4.7 Vibes: the audience rule and the lint

- `vibes/library.clj`: NEW `audience-fx? [state cell groups]` and `playable-fx [state vibe cells]` (Phase 8b's names). `audience-cue?` (`:106-109`, on Z4's targets by then) also counts each of the cue's patterns' `reach`. `vibe-problems` (`:422-442`) gains §8.8's rows for the FX set and the flash FX.
- `vibes/engine.clj`: `take-fx` (`:117-128`) and `press-flash` (`:323-349`) switch on `playable-fx` only. A refused cell sets `[:vibe-runtime :problem] {:why :audience-fx :cell c}`.
- `subs.clj`, `vibe-pick-fx` (`:1122-1126`), gains `:blocked :crowd`, as `vibe-pick-cell` has.

### 4.8 Views

- NEW `views/components/zone_pattern_editor.clj`: `pattern-chip`, `pattern-visual` (the renderer), `lane-control`, `fold-line`, `now-line` (an `ext-clock-redraw` on `:zone-steps`), `map-cycle-visual`.
- NEW `views/dialogs/zone_pattern_editor.clj`: the "Zone Pattern — Cue A1" window, the shared `effect-parameter-editor` on `:cue-zone-patterns`.
- `views/components/effect_param_ui.clj`: `:zone-pattern` and `:map-cycle` join the renderer `case` (`:183`) and the set that keeps visual mode with modulators (`:92-94`).
- `views/components/parameter_controls.clj` (`:196-232`): `:ref`, `:ref-list`, `:map-list`. `views/components/modulator_param_control.clj` (`:465-491`): a `:modulatable?` choice goes to NEW `modulator-choice-control` (a combo box when static; when modulated, Min and Max are indices, labelled "0 Alternate · 1 Chase …").
- `views/components/effect_bank.clj` (`:90-121`): a `:scope` prop (Phase 8). The effects-grid editor passes `:global`; the cue editor and the timeline browser pass `:item`, so with Z4's hidden selector the item bank has no Zone tab.
- Both footers get a `Pattern:` chip after Z4's Target field.
- NEW `views/components/pattern_pads.clj`: `pattern-pad`, `step-pad`, `pattern-strip` (Grid tab: a flow pane that wraps when narrow) and `pattern-pad-row` (Perform), on Z4's pad shell and `redraw-blink!`. Z4's map strip and row gain the CYCLE pad, and a `:cycling` pseudo-class set from the snapshot.
- `views/tabs/grid.clj`: `:header-extra` is the map strip above the pattern strip. `views/tabs/perform.clj` (`:116-127`): "ZONE PATTERN" under Z4's "ZONE MAP".
- `views/components/grid_cell.clj`: Z4's cue badge gains the pattern's glyph. An effects cell's tooltip names the style and the lane.
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
| `[:playback :pulses :step] {:id n}` | session (`[:playback]` isn't a document root) | `:pattern-pads/step` | no / no |
| `[:zone-map :cycle-paused?]` | Z4's `zone-map` domain, runtime | a map switch landing, CYCLE, a cycle cell switched on, `reset-session` | no / no |
| snapshot `:zone-steps`, `:routing :cycle` | per tick | the show clock | – |

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? / undo label |
|---|---|---|---|
| `:zone-patterns/set-cue-pattern {:col :row :style}` | NEW | the Pattern chip | yes / "Change Zone Pattern". nil removes it. A style on a cue that has a pattern changes `:style` only |
| `:zone-patterns/new-cell {:col :row :style}` | NEW | an empty effects cell's "New zone pattern ▸" | yes / "New Zone Pattern". The cell starts off, and its editor opens |
| `:chain/update-param`, `:modulator/*`, `:keyframe/*` with `:domain :cue-zone-patterns` or `:effect-chains` | yes (`events/handlers/chain/params.clj:72-87`, `events/handlers/modulator.clj`, `events/handlers/keyframe.clj`) | the pattern editor | yes / their labels |
| `:ui/open-dialog :zone-pattern-editor {:col :row}` | yes, new id | the chip's "Edit pattern…" | yes |
| `:pattern-pads/pointer {:style :fx/event}` | NEW | a pad's mouse or touch press | yes: `pointer-action`'s `:press` (`events/handlers/vibes.clj:27-47`) → `:pattern-pads/press`, `:bar?` from Shift |
| `:pattern-pads/press {:style :bar? :src :input-time}` | NEW | the pointer, a learned key | yes: `{:show-clock/read {:on-read {:event/type :pattern-pads/schedule …}}}` |
| `:show-clock/read` | Z4's inline effect | `:pattern-pads/press` | effect |
| `:pattern-pads/schedule {:style :bar? :input-time :clock}` | NEW | `:show-clock/read` | yes: `schedule-style` |
| `:show-clock/pattern-due {:id :style}` | NEW | the clock's reports | yes: commits only if `:id` is still pending |
| `:pattern-pads/step {:src :input-time}` | NEW | the STEP pad, a learned key | yes: `[:playback :pulses :step :id]` + 1; nothing during vibe blackout |
| `:pattern-pads/set-pad-bar {:style :bar?}`, `:pattern-pads/clear-control {:style}` | NEW | a pad's menu | yes / "Change Pad Timing", "Clear Control" |
| `:zone-map/press {:map :cycle}` | Z4's, NEW id | the CYCLE pad, a learned key | yes: schedules the resume |
| `:effects/toggle-cell` | yes (`events/handlers/effects.clj:27-46`) | a cell, a trigger | yes: switching on a Map cycle cell resumes the cycle |

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
│ Offset  [ 0.00 ] ∿                                                                    │
│ Now     B (step 5)                                                                    │
│ ⓘ This rig (1 laser, Left / Right): A is its left half, B its right half.            │
│ [Visual | Numeric]                                             [Remove]  [Close]     │
└───────────────────────────────────────────────────────────────────────────────────────┘
```
- A style on a cue without a pattern adds that preset. On a cue with one, it changes the style only: the zones, the rate and the rest stay, as when a pad switches it.
- The window shows only the rows the style uses: Path and Width for Chase, Width for Ping-pong and Random, Seed for Random, Zones only (two pickers) for Swap. Rate, Offset and Style have ∿ to take a modulator. Numeric mode is the full parameter list, and the keyframe panel works as on any effect.
- "Now" and the fold line come from the snapshot and the current routing. While the cue isn't playing, "Now" says "– (not playing)".

### 7.2 The effects grid

- The effects-grid editor's Zone tab lists **Zone pattern** and **Map cycle**, with the hint "Zone patterns run after each cue's own pattern, cell by cell, row by row, left to right." The cue editor's item bank has no Zone tab.
- An empty effects cell's menu gains "New zone pattern ▸ Alternate, Chase, Ping-pong, Random, Shift, Swap, Map cycle".
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
- **STEP** steps every pattern whose "Step on" is the Zone step key, and shows the count. It doesn't wait for a beat (it's a hit, like HIT).
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
| Step on | "The beat" · "The Zone step key" · with Phase 4: "HIT" · "ACCENT" · "FLASH" · "A vibe change" · "HIT, ACCENT or FLASH" · "Any cue start" |
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
[:playback :pulses]       {:step {:id 7}}
[:zone-map]               {:active :map/left-right :pending nil :cycle-paused? false}
;; snapshot
{:cues [{:cell [0 0] :zone-frames {:zone/b #<33 points>}}] :zone-steps {#uuid "…p1" 9}
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
| `:step-on` | `:choice` | `:clock` | all | `:clock`, `:step`, and Phase 4's sources |
| `:clock` | `:choice` | `:cue` on a cue, `:global` in a cell | `:step-on :clock` | the cue's beats from its start, or the show's |
| `:path` | `:choice`, modulatable | `:loop` | Chase | `:loop`, `:bounce` |
| `:width` | `:int` 1…8 | 1 | Chase, Ping-pong, Random | zones lit at once |
| `:seed` | `:int` 0…99 | 0 | Random | another order |
| `:follow-pads?` | `:bool` | true | all | pattern pads may switch its style |
| `:restart-on` (Phase 4) | `:choice` | `:own` | `:step-on :clock` | Phase 8b's: count beats from that source's last press |

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
| `:clock`, `:clock :global` | the show's beats; bars count from global beat 0, as the beat squares do (`services/show_clock.clj:121`) | every cue moves together |
| `:step` | Zone step presses this session | each press: one step, at the next tick |
| `:hit` … (Phase 4) | that source's press count | each HIT: one step |

Rate 0.5 is a step every 2 beats, rate 2 every half beat, rate −1 runs backwards. Rate 0 with a modulated offset lets the modulator pick (Phase 8 §7's driving table). A step on the clock lands exactly where `offset + rate·t` crosses a whole number: D-11's window is for pad switches only. Modulators run where the pattern lives: a cue's at the cue's clock, a cell's at the global clock with the cue's tempo, as every effect's do.

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

Run the other way round, the Swap would find nothing in A or B and do nothing. Every output is a pure function of the frames, the params, `t`, the counts and the routing: Random is a closed form of the step, with no random state.

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

Lint rows (`vibes/library.clj` `vibe-problems`, for the FX set and the flash FX):

| Level | Condition | Message |
|---|---|---|
| warn (the level Phase 3 gives an audience cue) | an audience-capable pattern or cycle, and the vibe doesn't allow the audience | "Groove: B3 (Zone pattern) can send pictures to the crowd (Crowd Scanning), so this vibe never switches it on. Allow audience cells, or take Crowd Scanning out of its zones" |
| error | a style that moves needs two zones, and the lane has fewer known ones | "Groove: B3 (Zone pattern) needs two zones (it has one: A)" |
| warn | a lane names a group or a laser that's gone | "Groove: B3 (Zone pattern) lists Front, which no longer exists" |
| warn | a lane ref reaches no laser under the active map | "Groove: B3 (Zone pattern): Graphics has no laser, so the picture vanishes on that step" |
| info | the active map is Unison and the lane is zones only | "Groove: B3 (Zone pattern) doesn't move under Unison" |
| warn | two Map cycles in one vibe | "Groove: two Map cycles (B3, C1): C1 wins" |
| info | it steps on a key with no binding | "Groove: B3 (Zone pattern) steps on the Zone step key, which has no key yet" |

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

## 9. Tests (named test vars, fixtures)

Fixtures: the cues and setup of `test/laser_show/routing/zone_routing_integration_test.clj:26-135`; Phase 8's probe v19 rig (`plans/2026-11-18-vibe-mode/probes/vibe_probes_test.clj:568-569`); `test-timing-ctx` (`test/laser_show/services/frame_service_test.clj:60-67`); the clock by hand, `init!` and `ticks!` (`test/laser_show/services/show_clock_test.clj:44-66`); Z3's `compose` fixtures and Z4's legacy shows; `with-input-mapper` (`test/laser_show/ui_test/perform_test.clj:25-35`); `with-toolkit` and `with-app` (`test/laser_show/ui_test/fixtures.clj:17-58`).

- `laser-show.routing.zone-remap-test` (NEW): `step-index-test` (floor, negatives, 2.9999999999, presses), `ring-index-loop-and-bounce-test`, `scatter-sequence-test` (C A C B D C for n = 4), `scatter-never-repeats-test` (n 2–16, seeds 0–9, 10,000 steps), `lane-expands-every-laser-and-drops-unknown-test` (one laser gives its halves), `styles-table-test` (§8.2's table), `move-takes-the-picture-from-all-test`, `copy-is-one-union-key-test`, `move-into-an-occupied-zone-joins-test`, `empty-step-hides-the-picture-test`, `gate-keeps-lit-content-test`, `gate-narrows-a-union-key-test`, `auto-source-test`, `from-any-moves-everything-test`, `shift-and-swap-test`, `keys-outside-the-lane-stay-test`, `join-order-is-key-order-test`, `join-never-holds-a-frame-twice-test`, `no-pattern-is-the-same-map-test`, `off-and-pads-only-change-followers-test`, `item-points-follow-the-keys-test`, and the seeded `remap-invariants-test` (invariants 1, 3, 5 on generated frames and params).
- `laser-show.routing.zone-patterns-test` (NEW): `presets-are-valid-test`, `describe-test`, `cycle-pick-test`, `last-cycle-in-reading-order-wins-test`, `reach-is-the-whole-lane-test`, `warnings-test`, `schedule-style-test` (now, next beat, bar, replace, cancel, no clock), `live-style-is-pending-once-due-test`, `step-counts-test`.
- `laser-show.routing.zone-maps-test` (Z4's): `schedule-slot-is-the-map-rule-test`, `effective-map-order-with-a-cycle-test`, `paused-cycle-gives-the-active-map-test`. `laser-show.events.zone-maps-handlers-test` (Z4's): `map-switch-pauses-a-running-cycle-test`, `cycle-pad-resumes-test`, `switching-a-cycle-cell-on-resumes-test`, `no-cycle-nothing-to-pause-test`.
- `laser-show.animation.effects-test`: `resolve-effect-params-keyframes-then-params-test`, `modulated-choice-picks-by-index-test`, `zone-stage-effects-register-test`. `laser-show.animation.effects-coverage-test`: `expected-effect-ids` (`:28-34`) gains the two ids, and their parameters join `param-exemptions` (`:19-26`) as routing parameters.
- `laser-show.services.frame-service-test`: `cue-pattern-runs-before-grid-patterns-test`, `grid-patterns-run-in-reading-order-test` (twelve cells, probe v24), `stage-runs-before-point-effects-test`, `copy-gets-point-effects-once-test`, `throwing-pattern-leaves-the-frames-test`, `timeline-cue-pattern-loops-with-the-cue-test`, `cue-and-global-clock-test`, `no-pattern-renders-as-before-test`.
- `laser-show.routing.compose-test` (Z3's): `example-a-alternate-on-one-laser-test` (spec §6.2 a, now with the real pattern), `fold-tables-test` (§8.5: n = 1–5 × three maps × both styles, and Every laser), `copy-is-drawn-once-per-laser-test` (§8.4: 68, not 103).
- `laser-show.services.show-clock-test`: `map-cycle-sets-the-effective-map-test`, `pending-pattern-switch-is-effective-once-due-test`, `pattern-due-is-reported-test`, `snapshot-carries-zone-steps-and-cycle-test`, `routing-is-made-before-the-render-test`. `renders-once-per-tick-test` (`:175-188`) and `one-state-and-input-read-per-tick-test` (`:342`) pass unchanged.
- `laser-show.events.zone-patterns-handlers-test` (NEW): `set-cue-pattern-adds-a-preset-test`, `set-cue-pattern-keeps-zones-test`, `new-cell-test`, `press-reads-the-clock-then-schedules-test`, `press-within-50-ms-switches-now-test`, `shift-waits-for-the-bar-test`, `no-clock-switches-at-once-test`, `input-time-backs-the-beats-off-test`, `pattern-switch-is-live-test`, `step-counts-and-blackout-ignores-test`, `stop-commits-the-pending-switch-test`, `cue-pattern-domain-edits-params-test`.
- `laser-show.events.handlers.triggers-test` (beside the vibe action tests, `:109-148`): `pattern-pad-action-presses-test`, `zone-step-action-steps-test`. `laser-show.events.handlers.input-test` (beside `relearning-a-role-moves-its-binding-test`, `:120`): `relearning-a-pattern-pad-moves-its-key-test`. `laser-show.events.history-handlers-test`: the new labels name routed events.
- `laser-show.state.persistent-test`: `cue-patterns-survive-save-and-load-test`, `pattern-pads-are-not-saved-test`, `session-reset-clears-pads-and-pause-test`. `laser-show.state.history-test`: `pattern-switch-is-not-undoable-test`.
- `laser-show.vibes.library-test` (`with-projector`, `:47-49`): `audience-fx-test` (§8.8's table), `cue-pattern-reach-is-audience-test`, `lint-zone-pattern-rows-test`. `laser-show.vibes.engine-test`: `audience-pattern-cell-is-not-switched-on-test`, `allowed-audience-cell-switches-on-test`, `refused-cell-is-reported-test`, `manual-toggle-is-not-blocked-test`.
- `laser-show.subs-test`: `pattern-pad-sub-test`, `zone-patterns-in-use-test`, `vibe-pick-fx-blocks-audience-cells-test`. NEW `laser-show.views.components.zone-pattern-editor-test`: `chip-text-test`, `rows-per-style-test`.
- UI, NEW `laser-show.ui-test.zone-patterns-test`: `footer-chip-adds-a-pattern-test`, `pattern-window-shows-the-fold-test`, `offset-takes-a-modulator-test`, `effects-zone-tab-lists-pattern-and-cycle-test`, `item-bank-has-no-zone-tab-test`, `pattern-pad-switches-test` (no clock thread in `start-test!`: at once), `pending-pattern-pad-blinks-test`, `pattern-pad-learns-a-note-test`, `step-pad-steps-a-pattern-test`, `cycle-pad-resumes-test`. `perform-test`: `pattern-row-shows-when-patterns-are-used-test`. `main-window-test/cue-chain-editor-footer-fits-test` (`:178-198`) still fits with the chip.
- Probe z5 (Z3's `plans/2026-11-19-zones/probes/zones_probes_test.clj`): the stage on 1,000-point frames with one cue pattern and three cell patterns.

Run: `clj -M:mac-arm:test zone-remap zone-patterns zone-maps effects frame-service compose show-clock zone-patterns-handlers zone-maps-handlers triggers handlers.input history persistent library engine subs zone-pattern-editor`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.zone-patterns-test`, then `-n` for `perform-test`, `grid-test` and `main-window-test`, then both full suites (the `targeted-ui-testing` skill).

## 10. Commit plan

Each step is one commit: its tests first (run them red), then the code, then its tests green.

| # | Commit message | Red first | ~d |
|---|---|---|---|
| 1 | `refactor(zones): the rule for when a live switch lands is shared, so map pads and pattern pads switch alike` | `schedule-slot-is-the-map-rule-test`; Z4's tests unchanged | 0.25 |
| 2 | `feat(zones): the zone stage: a pattern moves, copies, hides or trades pictures between places, a copy is one picture under all its places, joined in a fixed order` | `zone-remap-test`, `zone-patterns-test` | 0.75 |
| 3 | `feat(effects): Zone pattern and Map cycle are effects, their parameters follow keyframes and modulators, and a style can follow a modulator` | `effects-test`, `effects-coverage-test` | 0.25 |
| 4 | `feat(render): a cue's own zone pattern, then the effects grid's in grid order, move its pictures between zones before the point effects, so one laser alternates its halves and a rig of five its sides` | `frame-service-test`, `compose-test` (fold tables) | 0.5 |
| 5 | `feat(zones): a Map cycle cell switches the zone map every bar or on a step; a map pad press pauses it and CYCLE carries on` | `show-clock-test`, `zone-maps-test`, `zone-maps-handlers-test` | 0.5 |
| 6 | `feat(zones): pattern pads switch every pattern's style on the next beat (Shift: the bar) from a pad or any learned key, and a Zone step key steps the patterns that step on it` | `zone-patterns-handlers-test`, `triggers-test`, `input-test`, `persistent-test`, `history-test` | 0.5 |
| 7 | `feat(ui): a Pattern chip in both cue editors, a pattern window that says how it folds onto this rig, and Zone pattern and Map cycle on the effects grid's Zone tab` | UI chip and window tests, `zone-pattern-editor-test` | 0.75 |
| 8 | `feat(ui): pattern pads on the Grid tab and on Perform, blinking while a switch waits, with a STEP pad, and a CYCLE pad on the map strip` | UI pad tests, `perform-test` | 0.5 |
| 9 | `feat(vibes): a vibe never switches on a zone pattern or Map cycle that can reach the crowd unless it allows the audience, and the lint says which` | `library-test`, `engine-test`, `subs-test` | 0.25 |
| 10 | `plans: zones Z5 done (<sha>): as built, deviations, tests, red-first results and probe z5` | – | – |

About 4.25 days. If it runs long, `:width`, `:seed` and the choice modulator can move to Z6. Whichever of Z5 and vibe Phase 4 lands second adds one commit: `feat(zones): patterns step on HIT, ACCENT, FLASH, a vibe change or any cue start, and can restart on one` (Phase 4's sources in `step-sources`, `:restart-on`, the counts from the pulse view). If Phase 8a lands first, step 2 extends its namespace, and its three effect ids stay as fixed styles.

## 11. Out of scope / alternatives considered

- **Fades between zones**: cuts only (overview §8). A fade needs gains per output.
- **Patterns on items, groups and tracks**: later. D-5 puts them on cues and cells, and a cue's `:zone-patterns` list already runs in order.
- **Per-cue pattern pads** (a pad that switches one cue's pattern): Z5-Q1 B/C. **A Hold pad** that freezes the step: later; Off keeps content where it was drawn.
- **The rig simulator** ("Preview on: 1, 2, 3 lasers", needs `06`): later. The fold line names this rig, and `rig/routing` with an invented rig is the simulator's core (spec invariant 9).
- **Center-out, time-share and other fold policies** (needs `06`): Center-out is one of Z6's grid starting points. Time-share isn't needed: at most two regions (D-1), and the lanes wrap (D-25).
- **One effect per style** (Phase 8's three): rejected. A pad and a modulator couldn't switch the style, and Z6's grid would be a fourth effect. Phase 8's ids stay only if 8a lands first.
- **Copies as one object under several keys** (spec §6.1's wording): rejected, because a join breaks the identity (§8.4). Z3's sharing stays, as a second line of defence.
- **Latest takes precedence between pads and the cycle** (Z5-Q2 B), or the cycle always winning (C): see Z5-Q2.
- **A Map cycle on a cue**: rejected. The map is global, so a cue's cycle would move every other cue.
- **Phase 8b's reactions** (`08` §7's `:zone-hop`, `:zone-swap`, `:zone-shift`, `:zone-chase`): with Phase 4, they become `:zone-pattern` templates (`:step-on :hit` instead of a Step modulator on the offset). That's Phase 8b's work.
- **Weights or levels per zone**: Z6 keeps steps on or off (overview §8).

## 12. Spec questions and open questions

**Spec questions** (where `01` is unclear or the code disagrees, and what this doc chose)

| # | Spec | The problem | Chosen |
|---|---|---|---|
| S-1 | §6 step 4 and §6.1: a pattern copy is "the same object" under several keys | A copy landing in an occupied zone is joined into a new object, so a laser reached by both keys draws it twice (§8.4: 103 points instead of 68) | A copy is one frame under the union of its places (§1's normal form). Each key is drawn once (step 3), so it never relies on identity. Where nothing is joined, the output is the same |
| S-2 | §4: pending due → cycle → active | Once a pad press commits to `:active`, the cycle beats it again on the next tick, so a press would last one tick | A map switch landing while a cycle runs pauses the cycle (`[:zone-map :cycle-paused?]`), and CYCLE or a cycle cell switched on resumes it (§8.6). Asked as Z5-Q2 |
| S-3 | §5 and Z3 (`05` §4): the routing is made after the render | The stage needs the rig for `:lane/lasers` and known refs, and the cycle's pick must come before the routing | `tick!`: step, zone ctx, cycle pick, effective map, routing, render. The routing never depends on the frames (Z3's S-3), so nothing else changes |
| S-4 | §9: "a pattern-pad switch (Z5)" | It doesn't say what a pattern pad switches | The style of every pattern that follows the pads (Z5-Q1) |
| S-5 | §6 step 6: vector keys sort after single refs | A copied picture (a union key) is drawn after the zone-specific content in its bucket | Accepted: it changes the order only |
| S-6 | §11's Phase 8 row: `:zones`, `:from`, `:zone-a`, `:zone-b` take refs | Z5 has one effect, and Swap's pair is its lane's first two refs | `:zone-a` and `:zone-b` exist only if 8a lands first, and they read as the lane `[a b]` |
| S-7 | §1 and §3: refs | "Every laser" isn't a ref: a target can't name it | It's a lane token, expanded to `:auto.laser/*` refs (one laser: its halves), so the routing and frame keys only ever see refs. Asked as Z5-Q4 |
| S-8 | spec silent; the brief asks for modulatable choices | Only `:float` and `:int` take modulators (`modulator_param_control.clj:465-491`) | A choice that says `:modulatable? true` reads a modulator's value as an index (`⌊v + 1e-9⌋`, clamped). Only Z5's effects say so |
| S-9 | V10 (vibe `00` §7) is still open | The audience rule for cells needs it | Planned with V10 A. With B, the refusal becomes a lint warning only; with C, the lane picker hides audience refs |

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
