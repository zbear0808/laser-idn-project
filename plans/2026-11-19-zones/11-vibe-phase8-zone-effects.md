# Phase 8: zone effects (chase, swap, shift)

**Written:** 2026-11-18   **Written for commit:** `b1da0f2` (`master`; the files cited are the same at `67735d0`, and `state/domains.clj` and `events/handlers/effects.clj` are cited at `b1da0f2` because Phase 1 is editing both)
**Status:** Done in zones Phase Z5 (`1d3d3b8`..`376d5f6`, on `master`; what it covers is §12.1), except 8b's four tap reactions, which are being built on `vibe-8b` (§12, 2026-11-20). V10 is answered A (§11; it was the vibe overview's §7)   **Last verified against:** `ea4d153` (§12); `b1da0f2` for §1–§9 (probes v19–v24, §11; they were the vibe overview's §3)
**Supersedes / Superseded by:** none   **Related findings:** V6 and R-4 (a random pick must not scan the crowd), extended here to effects that move a picture. The effects-grid editor offering the zone selector, which does nothing there, is fixed on the way. The zones plan (`plans/2026-11-18-zones-ux/00-overview.md`, a draft written after this phase) asks for the same effects in its §4.5 and question 20; §9 says how the two fit

> **Moved 2026-11-20.** This was Phase 8 of the vibe-mode plan, `plans/2026-11-18-vibe-mode/`, which was deleted when its Phases 1–7 were done (the folder is in git history at `8b4b7d7`). It lives here because the zones plan builds the same effects (Z5, `07-phase-z5-zone-patterns.md`). In this doc, `00-overview.md` and `01-…` to `07-…` mean that plan's docs, not this folder's: the parts it relies on are copied in §11 below (V10, still open, and probes v19–v24). The probes moved with it: `probes/vibe_probes_test.clj` in this folder.


A cue's picture goes to one zone, decided once per item before any effect or clock runs. This phase adds three effects that move a *finished* picture between zones:

- **Zone chase**: the picture runs across the zones, one at a time, on the beat.
- **Zone swap**: two zones trade pictures.
- **Zone shift**: the pictures move along the zones.

They live on the effects grid, so a Grid-tab cell, a vibe's FX set or a held FLASH can switch them on, and the Phase 4 reactions can drive them: a HIT hops the picture to the next projector, an ACCENT swaps left and right. They only re-route what is already drawn. No point and no colour changes.

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| A zone | `state/domains.clj:314-363` (defaults `:all` `:334`, `:left` `:339`, `:crowd` `:359-362`), `:248-273` (projectors), `:275-290` (virtual projectors) | a zone group has an id, a name and a colour. A projector lists the groups it belongs to, for example `[:all :left]` |
| Where a cue's picture goes | `routing/zone_effects.clj:127-156`; `services/frame_service.clj:219-286` | each item resolves to **one** zone, once: its `:zone-group-id`, or a zone selector's `:target-zone`, or its track's, or its parent's, or the cue's destination, else `:all`. The render returns `{zone-id frame}` per cue (probe v19) |
| The zone selector | `animation/effects/zone.clj:21-53`; `routing/zone_effects.clj:58-64, 143-145` | `:target-zone` is read straight from the stored params: no modulator, keyframe or clock. The effect is an identity transducer, so in an effects-grid cell it does nothing (probe v20) |
| Global effects | `services/frame_service.clj:83-114, 288-300, 398-403` | the active cells' items become one point-effect chain, applied to each zone frame of each cue at the cue's tempo, after the zone frames are formed |
| Who draws what | `backend/multi_engine.clj:47-73, 119-150` | per cue, a projector takes the frames of the zones it lists (in its order), joins them with blanking, joins the cues, then runs its calibration. Nothing for its zones: nil, and the engine sends an empty frame (`backend/streaming_engine.clj:368-373`), so a projector that loses its picture goes dark |
| The preview | `services/frame_service.clj:407-428` | built from the same cues as the engines' frames |
| Effect registry | `animation/effects.clj:55-62, 77-101` | `:zone` is a category (the selector only). `register-effect!` requires an `:apply-transducer` fn and ignores other keys |
| Effect pickers | `views/components/effect_bank.clj:20-31, 90-121`; callers `views/dialogs/effect_chain_editor.clj:33-44` (effects grid), `views/dialogs/cue_chain_editor.clj:36-49` (cue items), `views/components/visual_editors/timeline/browser.clj:57-62` (clips) | a Zone tab when `:include-zone?` is true. Both editors pass true, so the effects-grid editor offers the selector, which does nothing there |
| Parameter controls | `views/components/parameter_controls.clj:196-235`; `views/components/modulator_param_control.clj:465-491` | `:choice` and `:bool` have controls, `:float` and `:int` a slider that takes modulators, **any other type falls through to a slider**. The selector has a renderer of its own (`views/components/effect_param_ui.clj:120-129`, `views/components/visual_editors/custom_param_renderers.clj:494-539`) |
| A parameter update | `events/handlers/chain/params.clj:72-87` | stores whatever `:value` it is given, so a vector works |
| A parameter's value at a time | `animation/effects.clj:233-289, 389-409` | a keyframe animation or the params, merged over the defaults, modulators resolved once by `resolve-params-global` |
| Order of the cells | `services/frame_service.clj:83-100` | `(vals effect-chains)`: **insertion order up to 8 cells, hash order after** (probe v24) |
| Waves start at the peak | `animation/time.clj:30-67`; `animation/modulator_evaluators.clj:105-111` | a Sawtooth falls from Max to Min, a Square starts high (probe v21) |
| Removing a zone group | `events/handlers/zone_groups.clj:43-78` | the group leaves every projector. Effect params that name it keep naming it |
| Audience (Phase 1) | `vibes/library.clj` `audience-groups`, `audience-cell?`, `playable-cells`; `vibes/engine.clj` `enter-look`, `press-flash` | decided for cues only. FX sets are switched on without a check |

## 2. Root cause

A picture's zone is fixed per item, from stored values, before any effect or clock. Between "the cue rendered `{zone frame}`" and "a projector takes its zones" nothing can move a picture, so a press or the beat cannot send it to another projector. The one zone effect that exists is a static label read at routing time.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| The cue's `combined-by-zone` map (`services/frame_service.clj:387-396`) | the new stage's input and output: a map from zone id to frame |
| `concatenate-frames`, `merge-zone-maps` (`:195-217, 302-317`) | the join rule when two pictures land in one zone |
| Effects-grid cells, `get-active-global-effects`, `render-context` (`:83-114`) | zone effects are ordinary effect instances in ordinary cells; a second reader collects them |
| The effect registry and its `:zone` category (`animation/effects.clj:55-101`, `animation/effects/zone.clj`) | three registrations beside the selector, plus two optional keys |
| `merge-with-defaults`, `kf/eval-keyframe`, `resolve-params-global` (`animation/effects.clj:139-142, 256-277, 389-409`) | a zone effect's parameters at a time, so a modulator or a keyframe animation works on them |
| `chains/flatten-chain` (`animation/chains.clj:67-88`) | groups and `:enabled?` flags |
| Every modulator (`animation/modulators.clj`) | `:offset`, `:rate` and `:amount` are `:float`, so waves, `:midi`, `:macro` (Phase 5) and Phase 4's Step, Envelope and Energy drive them with no change |
| The effect bank's Zone tab (`views/components/effect_bank.clj:20-31`), `zone-group-chip` (`views/components/zone_chips.clj:9-37`), the `zone-groups-list` sub (`subs.clj:635-640`), `:chain/update-param` | the pickers and controls |
| Phase 1: FX sets, `audience-groups`, `enter-look`, `press-flash`. Phase 4: Step, `pulses/rebase`, `vibes.reactions`, `library/listens-to` | 8b |

## 4. Changes per namespace

**8a: the effects**

- `laser-show.routing.zone-remap` (NEW, pure): the three remaps over `{zone-id frame}` and the stage that applies a list of them. A new namespace because its input is zone frames, not items (`routing/zone_effects.clj` is about items), and both `frame-service` and the tests need it with nothing else loaded. Functions: `step-index [offset rate beats]` (floor, with a 1e-9 allowance), `ring-index [step n path]`, `lane [zones known]` (distinct, known ids only), `shift`, `swap`, `chase`, and `apply-zone-effects [zone-frames effects ctx opts]` with `opts {:known #{zone-id} :join concatenate-frames}`. The join function is passed in because `frame-service` requires `routing`, and requiring it back would be a cycle. With no effects it returns the very map it was given.
- `laser-show.animation.effects` (existing): `register-effect!`'s docstring (`:77-92`) lists two optional keys, `:apply-zones` `(fn [params zone-frames opts] zone-frames')` and `:scope` (a set of `:item` and `:global`; absent means both). NEW `zone-remap-effect? [effect-def]` (has `:apply-zones`). NEW `resolve-effect-params [effect-instance timing-ctx]`: the keyframe-or-params choice and the defaults merge of `make-effect-transducer` (`:256-279`), without the spatial driver, then `resolve-params-global`.
- `laser-show.animation.effects.zone` (existing, `:43-53`): `:zone-selector` gets `:scope #{:item}`. NEW registrations `:zone-chase`, `:zone-shift`, `:zone-swap` (category `:zone`, `:timing :bpm`, `:scope #{:global}`, an identity `:apply-transducer`, and `:apply-zones` calling `zone-remap`). §7 has their parameters.
- `laser-show.services.frame-service` (existing): NEW `get-active-zone-effects [state]`: the items of the active cells in **reading order** (row, then column), flattened, that are zone-remap effects. `render-context` (`:102-114`) gains `:zone-effects` and `:zone-ids` (the keys of `[:zone-groups]`), and its `:global-effects` leaves the zone-remap effects out, so the point chain gains no identity stage. `generate-frames-by-zone` (`:362-405`) runs `apply-zone-effects` on `combined-by-zone` at `global-ctx` **before** the per-zone point effects, as items that share a zone are joined before them today (`:387-405`), so a point effect sees the picture a projector will draw. `:398` puts the cue's tempo on `global-ctx` only `when` there are point effects, so that test also asks for zone effects. An exception is reported under `[:zone-effects]` and the frames go on unchanged, as `apply-global-effects-to-frame` does (`:288-300`). `get-active-global-effects` is unchanged.
- `laser-show.services.show-clock` (existing, `:62-69`): `record-render!` counts the zone effects too. Nothing else: the snapshot's `:zone-frames` are already remapped, so the engines and the preview agree.
- `laser-show.views.components.effect-bank` (existing): a `:scope` prop (`:item` or `:global`) that hides effects whose `:scope` excludes it; the memo (`:61-72`) is kept per scope. With `:global` the Zone tab also shows one hint line: "Zone effects run in grid order: row by row, left to right." Callers: `views/dialogs/effect_chain_editor.clj:36-44` passes `:global`, `views/dialogs/cue_chain_editor.clj:40-49` and `views/components/visual_editors/timeline/browser.clj:57-62` pass `:item`. This also takes the inert selector out of the effects-grid editor.
- `laser-show.views.components.parameter-controls` (existing, `:196-235`): two cases. `:zone-group-id`: a combo of the zone groups with a colour dot, as `views/components/visual_editors/custom_param_renderers.clj:515-539` draws it. `:zone-group-ids`: the zone chips numbered in the order they were clicked, and a Clear button. `modulatable-param-control` (`:465-491`) already hands non-numeric types to `param-control`. Both read the groups with `subs/zone-groups-list`.
- `laser-show.views.components.zone-chips` (existing): NEW `zone-order-chip` (the chip with its number).

**8b: with vibes (needs Phases 1 and 4)**

- `laser-show.vibes.library` (existing from Phase 1): `zone-group-ids` becomes public. NEW `audience-fx? [state cell groups]` and `playable-fx [state vibe cells]` (the cells a vibe may switch on, as `playable-cells` is for cues). `lint` gets the rows in §7. `listens-to` (Phase 4) also reads a zone chase's own `:restart-on`.
- `laser-show.vibes.engine` (existing from Phase 1): `enter-look` and `press-flash` switch on `playable-fx` instead of every existing cell. A refused cell sets `[:vibe-runtime :problem]` to `{:why :audience-fx :cell cell}`.
- `laser-show.vibes.reactions` (existing from Phase 4): four templates (§7) and `lane [state]`, the default zone list for a new reaction.
- `laser-show.subs` (existing from Phase 2): `vibe-header` carries `[:vibe-runtime :problem]` when it has no role, and Perform's status line prints it ("Zone chase reaches the crowd: allow it in this vibe").
- `laser-show.animation.effects.zone` (8b): `:zone-chase` gains `:restart-on`, evaluated with `pulses/rebase` (Phase 4).
- `README.md` (Phase 7 §7): a "Zone effects" paragraph.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| a zone effect and its parameters, in `[:chains :effect-chains [col row] :items]` | document (existing path) | the effect picker, `:chain/update-param` | yes / yes |
| the cell's `:active?` | document, but live (`state/history.clj:19-21`) | `:effects/toggle-cell`, the vibe engine | no / no |
| `:zone-effects`, `:zone-ids` in the render context | computed by the show clock each tick, never stored | `frame-service/render-context` | – |
| `[:vibe-runtime :problem]` `{:why :audience-fx :cell c}` (8b) | app state, session (Phase 1) | the engine | no / no |

No new key. A zone effect is saved with the project as any effect is. A build that doesn't know the effect id skips it (`animation/effects.clj:250-252`).

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:chain/update-param` (a vector or a keyword as `:value`) | yes (`events/handlers/chain/params.clj:72-87`) | the new parameter controls | yes |
| `:effect-chain/add-effect-from-bank` | yes (`events/handlers/effects.clj:139-165, 204` at `b1da0f2`) | the effects-grid editor's bank (`views/dialogs/effect_chain_editor.clj:41-43`) | yes |
| `:effects/toggle-cell` | yes (`events/handlers/effects.clj:26-45` at `b1da0f2`) | a cell click, a trigger | yes |
| `:vibe/add-reaction {:vibe :kind}` | Phase 4 | the Vibes tab's Tap reactions menu | yes; gains four kinds (8b) |

No new event and no new effect key. The call path of the stage: `show-clock/tick!` (`services/show_clock.clj:137-162`) → `render` (`:71-92`) → `render-cue` (`:48-60`) → `frame-service/generate-frames-by-zone`.

## 7. Data examples and invariants

### The three effects

| Effect | Parameter | Type | Default | Meaning |
|---|---|---|---|---|
| `:zone-chase` | `:zones` | `:zone-group-ids` (NEW) | `[:left :center :right]` | the path, in order |
| | `:from` | `:zone-group-id` | `:all` | the zone whose picture runs along the path |
| | `:rate` | `:float` −8…8 | 1.0 | zones per beat; negative runs backwards, 0 stands still |
| | `:offset` | `:float` −16…16 | 0.0 | zones along the path at beat 0 |
| | `:path` | `:choice` | `:loop` | `:loop` or `:bounce` |
| | `:restart-on` (8b) | `:choice` | `:own` | `:own` is the global clock; a press source counts from that press (Phase 4) |
| `:zone-shift` | `:zones` | `:zone-group-ids` | `[:left :center :right]` | the ring |
| | `:offset`, `:rate` | `:float` | 0.0, 0.0 | as above |
| `:zone-swap` | `:zone-a`, `:zone-b` | `:zone-group-id` | `:left`, `:right` | the two zones |
| | `:amount` | `:float` 0…1 | 1.0 | 0.5 or more swaps; less leaves them |

```clojure
[:chains :effect-chains [4 3]]
;; => {:name "Zone chase" :active? true
;;     :items [{:id #uuid "…" :effect-id :zone-chase :enabled? true
;;              :params {:zones [:left :center :right] :from :all :rate 1.0 :offset 0.0 :path :loop}}]}
```

The step is `floor(offset + rate · beats + 1e-9)` (the allowance keeps a Step's 2.9999999999 on zone 3). Chase and shift index the list with it: `:loop` is `step mod n`, `:bounce` is `m = step mod (2n − 2)` then `m < n ? m : 2n − 2 − m`. Zone ids that are not in `[:zone-groups]` or repeat are dropped from the list first, and `n` counts what is left. `beats` is the global clock's (`:effective-beats` of `global-ctx`). With `:restart-on` set to a press source (8b) it is the beats since that source's last press (`pulses/rebase`, Phase 4 §7), and until the first press the chase runs on the global clock, as a loop modulator does (Phase 4 invariant 2).

### What each one does (probes v19, v21)

Projectors: `p-left` in `[:all :left]`, `p-center` in `[:all :center]`, `p-right` in `[:all :right]`, `p-crowd` in `[:crowd]` (tagged `:crowd-scanning`), `p-gfx` in `[:graphics]`.

**Chase.** A cue routed to `:all` (a 32-point circle, 33 points), `:zones [:left :center :right]`, `:from :all`, `:rate 1.0`:

| Global beat | Zone frames after | Projectors that send something |
|---|---|---|
| 0.0 | `{:left 33}` | `p-left` (33) |
| 1.0 | `{:center 33}` | `p-center` |
| 2.0 | `{:right 33}` | `p-right` |
| 3.0 | `{:left 33}` | `p-left` |

Before the effect all three side projectors send the 33 points together (the cue is in `:all`). `p-crowd` and `p-gfx` never send anything: they are not in `:all` and not in the list. The picture is **moved**, not copied: `:all` is empty afterwards.

| Path, 3 zones, rate 1, beats 0 to 5 | Zone index |
|---|---|
| `:loop` | 0 1 2 0 1 2 |
| `:bounce` | 0 1 2 1 0 1 |
| a hop: `:rate 0`, `:offset` 0, 1, 2 | left, center, right |
| `:rate −1` from beat 0 | beats 0, 1, 2, 3: left, right, center, left (step −1 is index 2) |

**Shift.** A cue with three items, one routed to each side zone (probe v19: circle 21 points in `:left`, triangle 10 in `:center`, square 49 in `:right`), `:zones [:left :center :right]`:

| `:offset` | `:left` | `:center` | `:right` |
|---|---|---|---|
| 0 | 21 | 10 | 49 |
| 1 | 49 | 21 | 10 |
| 2 | 10 | 49 | 21 |
| 3 (a full turn) | 21 | 10 | 49 |

The cue routed to `:all` is unchanged by a shift: it has nothing in the three zones. Use a chase for it.

**Swap.** The same cue, `:zone-a :left`, `:zone-b :right`: amount 1.0 gives left 49, center 10, right 21, and the frames really trade places; amount 0.49 changes nothing. A swap with one side empty moves the other picture across.

### Why a picture is moved and never copied (probe v22)

`p-left` is in `[:all :left]`. The 33-point frame under `:left` alone: `p-left` sends 33 points. The same frame under `:all` **and** `:left`: 68 points, the frame twice and the blanking jump between. A copy would double what any projector in two of the zones draws, so every remap here moves.

### Driving the numbers (probe v21)

| Driver | Result |
|---|---|
| `:rate 1.0` and nothing else | a chase on the beat, no modulator |
| `:offset` ← Step 0→n−1, `:rate 0` (Phase 4) | a hop: one zone per press |
| `:offset` ← Sawtooth 0→3 over 4 beats, `:rate 0` | **falls**: 3.00, 2.25, 1.50, 0.75 at beats 0 to 3, so the chase runs backwards: left, right, center, left. A wave starts at its peak (`animation/time.clj:30-67`). For a rising ramp swap Min and Max, or use `:rate` |
| `:amount` ← Square 0→1, period 2 beats | swapped for the first beat, normal for the second |
| `:amount` ← Envelope on `:hit`, rest 0, peak 1, release ½ beat, linear (Phase 4) | swapped for the first quarter beat after each hit |
| `:offset` ← Step with a Glide | a glide only delays the switch to its end. Leave it at 0 |

### Order (probe v24)

Twelve cells put in the grid in the order (row·10 + col) `13 10 12 2 1 11 20 21 23 22 3 0` are chained as `22 0 1 11 3 20 2 13 12 21 23 10`: hash order. Six cells keep the order they were put in. Several point effects commute (intensity and scale, for one), so this can go unnoticed. Zone effects don't: a shift then a swap is not a swap then a shift. Zone effects therefore run in **reading order**: cells by row, then column, and each cell's chain in its own order. The effects grid says so in its hint. The order of *point* effects is left as it is (§9).

### The audience rule (V10, 8b)

`audience-fx?` is true when an enabled zone effect in the cell names an audience group (`audience-groups`, Phase 1: `:crowd`, and every zone of a crowd-scanning projector) in a place a picture can **go**: `:zones`, `:zone-a`, `:zone-b`. `:from` is where a picture comes from, so it does not count.

| Cell | Why | `audience-fx?` |
|---|---|---|
| chase `[:left :center :right]` from `:all` | no listed zone is an audience group | false |
| chase `[:left :crowd]` | `:crowd` is listed | **true** |
| swap `:center` and `:crowd` | `:crowd` is `:zone-b` | **true** |
| chase `[:left :center]` from `:crowd` | `:from` is where the picture comes from, not where it goes | false |
| the crowd effect is disabled, or the cell holds no zone effect | nothing can move a picture | false |

A vibe without `:allow-audience? true` does not switch such a cell on (`playable-fx`), and Perform says "Zone chase reaches the crowd: allow it in this vibe". Clicking the cell on the effects grid is a decision and is not blocked.

### The reactions (8b)

`vibes.reactions/lane` is the default list: the zone groups that have an enabled projector and are not audience groups, `:left :center :right` first and in that order, the others by name. Fewer than two: the menu entries are greyed with "needs two zones with projectors (Zone Groups tab)". Below, `n` is the list's length.

| Kind | Menu | Role | Effect ← driver | At rest | Goes into |
|---|---|---|---|---|---|
| `:zone-hop` | Zone hop: next zone (one zone at a time) | HIT | `:zone-chase` `:rate 0`, `:offset` ← Step 0→n−1, n steps | **the picture sits in the first zone until the first hit, by design** | FX set |
| `:zone-swap` | Zone swap | ACCENT | `:zone-swap` `:amount` ← Step 0→1, 2 steps | identity | FX set |
| `:zone-shift` | Zone shift: next zone | ACCENT | `:zone-shift` `:offset` ← Step 0→n−1, n steps | identity | FX set |
| `:zone-chase` | Zone chase while held | FLASH | `:zone-chase` `:rate 1`, `:restart-on :flash` | off: the cell is only on while FLASH is held | flash FX |

They are made as Phase 4's are (`:vibe/add-reaction`, one undo step, switched on at once if the vibe is current) and named "Groove · Zone swap". To use one, route the cue's items to zones (swap and shift) or leave them in `:all` (hop and chase).

### Lint rows (Phase 3's table gains, 8b)

| Level | Condition | Message |
|---|---|---|
| error | a swap with the same zone twice, or a chase or shift with fewer than two known zones | "Groove · Zone swap needs two different zones" |
| warn | a zone effect names a zone group that was deleted | "Zone chase lists Center, which no longer exists" |
| warn | a listed zone has no enabled projector | "Zone chase: Center has no projector, so the picture vanishes on that step" |
| info | a projector in the chase's `:from` zone is in none of the listed zones | "p-master is in All but not in Left, Center or Right: it goes dark while the chase runs" |
| info | none of the vibe's cues sends anything to the zone a chase takes from (`zone-group-ids`), or to the zones a shift or swap lists | "Groove · Zone swap has nothing to swap: no cue here is routed to Left or Right" |
| error | an audience zone is listed and the vibe doesn't allow the audience (V10) | "Groove · Zone chase reaches the crowd: allow it in this vibe, or remove Crowd Scanning" |

### Invariants

1. A remap moves pictures between keys and joins two pictures that land in one zone (with the blanking jump of `concatenate-frames`). It changes no point and no colour. Every point that went in comes out, plus the jump points of a join.
2. With no active zone effect, `generate-frames-by-zone` returns what it returns today, and `apply-zone-effects` returns the same map object. A project without zone effects renders exactly as before.
3. A shift by `k` and by `k + n` are equal. A shift by 0 returns the same map. Pictures outside the list stay where they are.
4. A swap is its own inverse. An amount below 0.5 returns the same map. Pictures outside the two zones stay.
5. A chase leaves its `:from` zone empty (unless the step lands on it) and puts that picture in exactly one zone of the list. A chase with no picture in `:from` returns the same map. A list of one zone sends the picture there, and a list of none is the identity.
6. Zone ids that are unknown or repeated are dropped before use. A list with fewer than two zones is the identity for a shift or a swap.
7. Zone effects compose in reading order of the cells, then chain order within a cell.
8. A zone effect is meaningful only on the effects grid. In a cue item, group, track or clip chain it is neither offered (`:scope`) nor applied.
9. A parameter is resolved once per cue per tick. Zone effects never run on calibration or on a projector's joined frame (`backend/multi_engine.clj:148-150`), so a safety stage added there later, as the zones plan proposes, sees everything they send.
10. A zone cell that a press switches on, and the moves it makes, show from the same tick as the cue the press starts (the clock reads app state once per tick), so no frame shows the new cue in its old zone.
11. The preview shows the remapped zones, because it is built from the same snapshot as the engines' frames.
12. Cost (probe v23, 1,000-point frames, two runs): a re-key is 0.2–0.8 µs and about 1 KB of garbage, a re-key that joins two frames 11–12 µs median (p99 12–50 µs) and about 8 KB. Next to a four-effect point chain (216–228 µs, v17) it is nothing, and the point effects afterwards run on as many zone frames as before or fewer (a join makes one of two), never more. *Unverified on the running show.*
13. 8b: a vibe never switches on an audience-capable zone cell unless it allows the audience (V10). A refused cell is reported, never silent.

## 8. Tests (named test vars + fixtures)

Pure first. Fixtures follow `test/laser_show/routing/zone_routing_integration_test.clj:54-85` (a cue with one item per zone), its setup (`:124-135`), and `test/laser_show/backend/multi_engine_test.clj:31-35, 272-309` (a snapshot, `projector-frame`). The numbers in §7 come from probes v19–v24, which become these tests.

- `laser-show.routing.zone-remap-test` (NEW): `step-index-test` (floor, negatives, 2.9999999999), `ring-index-loop-and-bounce-test` (the table), `shift-moves-pictures-along-test`, `shift-by-a-full-turn-is-the-same-test`, `shift-leaves-other-zones-alone-test`, `swap-trades-two-pictures-test`, `swap-below-half-is-the-same-test`, `swap-is-its-own-inverse-test`, `swap-with-one-side-empty-moves-the-other-test`, `chase-moves-the-from-picture-test`, `chase-joins-an-occupied-zone-test`, `chase-without-a-picture-is-the-same-test`, `chase-backwards-test`, `unknown-and-repeated-zones-are-dropped-test`, `short-lists-are-the-identity-test`, `no-effects-returns-the-same-map-test`, `points-are-conserved-test` (generated maps and parameters: totals equal, plus the join jumps).
- `laser-show.animation.effects-test` (existing): `resolve-effect-params-uses-keyframes-then-params-test`, `zone-effects-register-with-apply-zones-and-scope-test`, `zone-effect-is-an-identity-transducer-test`.
- `laser-show.services.frame-service-test` (existing, pattern `:69-88`): `zone-effects-move-pictures-before-point-effects-test`, `zone-effects-run-in-reading-order-test` (twelve cells, probe v24), `inactive-zone-effect-changes-nothing-test`, `global-zone-selector-stays-inert-test` (probe v20), `render-context-splits-zone-and-point-effects-test`, `zone-effects-alone-run-at-the-cues-tempo-test` (no point effects in the grid, a cue with its own tempo), `a-throwing-zone-effect-leaves-the-frames-test`, `timeline-cue-is-remapped-too-test`.
- `laser-show.backend.multi-engine-test` (existing, pattern `:272-309`; also `test/laser_show/routing/zone_routing_integration_test.clj:586-634`): `a-chase-reaches-one-projector-at-a-time-test` (the chase table through `projector-frame`), `a-moved-picture-is-drawn-once-test` (33 points), `a-copied-picture-would-be-drawn-twice-test` (68, as the reason for invariant 1), `a-projector-in-no-listed-zone-sends-nothing-test`.
- `laser-show.services.show-clock-test` (existing, `:190-212`): `snapshot-zone-frames-are-remapped-test`, `preview-follows-the-remap-test`.
- `laser-show.events.zone-groups-handlers-test` (existing): `removed-zone-in-an-effect-is-skipped-at-render-test`.
- UI, `laser-show.ui-test.zone-effects-test` (NEW; `with-toolkit` once, `with-app` each): `effects-grid-editor-zone-tab-lists-the-three-effects-test`, `cue-editor-zone-tab-lists-only-the-selector-test`, `zone-list-keeps-click-order-test`, `zone-list-toggle-removes-a-zone-test`, `swap-editor-picks-two-zones-test`, `modulator-button-appears-on-offset-rate-and-amount-test`. The existing `laser-show.ui-test.grid-test` and the cue and effect chain editor tests pass unchanged.
- 8b, `laser-show.vibes.library-test` (existing): `audience-fx-test` (the table), `playable-fx-skips-audience-cells-test`, `lint-zone-effect-rows-test` (one assertion per row). `laser-show.vibes.engine-test` (existing): `audience-zone-cell-is-not-switched-on-test`, `allowed-audience-zone-cell-switches-on-test`, `manual-toggle-is-not-blocked-test`, `refused-cell-is-reported-test`. `laser-show.vibes.reactions-test` (existing from Phase 4): `zone-templates-are-valid-test`, `zone-templates-need-two-zones-test`, `zone-swap-is-identity-at-rest-test`, `zone-hop-moves-the-picture-on-each-hit-test` (frames after 0, 1 and 2 presses), `zone-chase-goes-into-flash-fx-test`. `laser-show.animation.pulses-test` (Phase 4): `zone-chase-restarts-on-its-press-test`. UI, `laser-show.ui-test.perform-test` (existing from Phase 2): `refused-zone-cell-is-explained-in-the-status-line-test`.
- Evidence: invariant 12's re-run of probe v23 with the real functions, written here.

Run: `clj -M:mac-arm:test zone-remap effects frame-service multi-engine show-clock zone-groups`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.zone-effects-test`, then `-n laser-show.ui-test.grid-test`, then both full suites. 8b adds `library engine reactions pulses`.

## 9. Out of scope / alternatives considered

- **Zone effects on items, groups, tracks and clips** (the `:item` scope): later. A group already splits its children's pictures by zone (`services/frame_service.clj:253-284`), which is where a remap would go. Today they are global only, and the cue editor's Zone tab keeps just the selector.
- **A copy to several zones** (a chase two zones wide, "all at once"): a projector in two of the zones would draw it twice (probe v22). It needs the engine to draw a frame it receives through two zones once, by identity, in `projector-frame` (`backend/multi_engine.clj:119-150`), and a lint for projectors in two listed zones. Later.
- **One general "zone map" effect** (an arbitrary table from zone to zone): rejected. The three named effects are what is asked for, each has three or four numbers, and a table has no number a modulator can drive.
- **Keyframing a zone selector's `:target-zone`** (the keyframe panel already treats `:zone-group-id` params as steps, `views/components/visual_editors/keyframe_modulator_panel.clj:33`): rejected. Routing reads the stored value directly (`routing/zone_effects.clj:143-145`), and it would move one item, not a cue.
- **Fading between zones**: a chase, hop, swap and shift are cuts. A fade is a Fade in or a Pump (Phase 4) stacked on the same cell's brightness.
- **A left-to-right order on zone groups**, so the default list follows the room: later. The list on each effect is the order.
- **A zone effect for one voice only** (move just the HIT cue): later. Global effects act on every playing cue, as Phase 4's reactions do.
- **Fixing the order of point effects across cells** (probe v24): a separate change. It would alter how existing projects with nine or more effect cells look, and with fewer it would replace insertion order by reading order.
- **Waiting for the zones plan's per-output weights** (`plans/2026-11-18-zones-ux/00-overview.md` §3, its Phase 2): not waited for. That plan is a draft with 25 open questions; this phase is a few days on today's model and needs none of its setup work. Nothing here blocks it. The effects' parameters (`:zones`, `:from`, `:offset`, `:rate`, `:path`, `:amount`) don't depend on how routing is done, so a weight model can adopt them: a chase becomes a one-hot weight over the list, a swap swaps two weights, a shift rotates them. What would change is invariant 1: a weight of 1 on two outputs is a copy, and the zones plan already names the price (a projector in two groups draws both, its §2 and §7). Its answer to question 20 is yes here: a HIT can step a chase.
- **Making keyframed zone selectors route** (the zones plan's Option A, for "a basic zone chase"): a different mechanism that moves one item, and the plan removes the selector as a way to route (its §4.3). Phase 8 neither needs nor blocks it: `:scope #{:item}` only decides where the selector is offered.
- **A "which zone is lit" readout on Perform**: later; the preview's zone cells already show it.

## 11. Carried over from the vibe plan (2026-11-20)

The vibe overview (`plans/2026-11-18-vibe-mode/00-overview.md`, in git history at `8b4b7d7`) held this phase's open decision and its probe results. They are copied here as they were.

### Decision V10 (answered A, 2026-11-20)

> **DECISION V10: Zone effects and the audience** *(answered: A)*
>
> V6 keeps a random pick from choosing a cue that can scan the crowd. A zone effect can send a picture there too: a chase that lists the crowd zone sweeps every cue through it, and a vibe switches its FX set on by itself (`08-phase8-zone-effects.md` §7, "The audience rule"). The crowd zone is `:crowd`, or any zone of a projector tagged `:crowd-scanning`; a zone only counts where the picture *goes* (`:zones`, `:zone-a`, `:zone-b`), not where it comes from (`:from`).
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | A vibe doesn't switch such a cell on unless it has `:allow-audience? true`. The Vibes tab flags it (lint error), Perform says why. Clicking the cell on the effects grid is never blocked | the same rule as V6, so one thing to remember: a vibe never reaches the crowd by itself unless you said so | one checkbox when you do mean it |
> | B | Warn only (the lint row, no block) | never blocks a vibe | at 1 am a missed warning sweeps the audience |
> | C | The zone list can't hold an audience zone at all (the picker hides them) | nothing to remember | no audience chase, even by hand |
>
> **Your answer:** **A** (2026-11-20): a vibe doesn't switch on a zone effect that reaches the audience unless it allows the audience. The zones plan builds the shared stage in Z5 (`07-phase-z5-zone-patterns.md`), so 8b comes after Z5 and builds on it.

### Probes v19–v24 (the overview's §3)

Run them from the repo root with `P=plans/2026-11-19-zones` in the command at the top of `probes/vibe_probes_test.clj`, and `v19 v20 v21 v22 v23 v24` as name patterns after `-n probes.vibe-probes-test`.

| Probe | Question | Result | So |
|---|---|---|---|
| v19 (ran) | What are a cue's zone frames, and who draws them? | One item routed to each of left, right and center: `{:left 21 :center 10 :right 49}` points, and each side projector sends only its own zone. A cue routed to `:all`: `{:all 33}`, which all three side projectors (each in `[:all …]`) send; the crowd and graphics projectors send nothing. The project's zone groups: `:all :center :crowd :graphics :left :right` | a zone frame is the unit a zone effect moves. A projector that loses its zone goes dark (`backend/streaming_engine.clj:373`) |
| v20 (ran) | Does a zone selector in an effects-grid cell do anything? | No: the zone frames are identical with and without an active cell holding one | the effects-grid editor offers an effect that does nothing; zone effects need a reader of their own (Phase 8) |
| v21 (ran) | What do chase, shift and swap do to real zone frames? (prototypes in the probe file) | A chase of the `:all` cue over left, center, right on beats 0 to 3: left, center, right, left, with one projector sending each time. Bounce: 0 1 2 1 0 1. A shift by 1 turns 21/10/49 points into 49/21/10, by 3 the same as by 0. A swap trades left and right. A Sawtooth 0→3 over 4 beats **falls** 3.00, 2.25, 1.50, 0.75 | the tables of 08 §7. Waves start at their peak, so the effects carry their own `:rate` |
| v22 (ran) | What if a picture were copied into two zones? | A projector in `[:all :left]`: the 33-point frame under `:left` alone sends 33 points, under `:all` and `:left` **68** | a remap moves and never copies (08 invariant 1) |
| v23 (ran) | What does a remap cost? | 1,000-point frames, two runs (load 1.8 and 2.5): shift 0.7–0.8 µs, swap 0.2 µs, a chase that only re-keys 0.3 µs (about 1 KB of garbage each); a chase that joins two frames 11–12 µs (p99 12–50 µs, about 8 KB) | nothing next to a point chain (v17); 08 invariant 12 |
| v24 (ran) | In what order do the effects grid's cells become one chain? | Six cells: the order they were put in. Twelve cells put in as 13 10 12 2 1 11 20 21 23 22 3 0 (row·10 + col) come out 22 0 1 11 3 20 2 13 12 21 23 10: hash order | zone effects don't commute, so they run in reading order (08 §7). The order of point effects is a separate finding (08 §9) |

## 12. After Z5: what's left, and how it's built (2026-11-20, at `ea4d153`)

Zones Phase Z5 (this folder's `07-phase-z5-zone-patterns.md`, "Z5" below; as built in its §13, `1d3d3b8`..`376d5f6`) built this phase's 8a and most of its 8b over refs, as one `:zone-pattern` effect with styles instead of three effects (Z5 §11). What it leaves is 8b's four tap reactions (Z5 §11, "Phase 8b's reactions"). This section says what is covered and plans the rest, against `ea4d153`. Paths are under `src/laser_show/`.

### 12.1 What Z5 already covers

**§4, 8a**

| Here | As Z5 built it | Left |
|---|---|---|
| `routing.zone-remap`: `step-index`, `ring-index`, `lane`, `shift`, `swap`, `chase`, `apply-zone-effects` | `routing/zone_remap.clj` with these names: `step-index`, `ring-index`, `lane` (over refs, with Every laser), `apply-zone-stage` (this `apply-zone-effects`). Chase, shift and swap are styles of `run-pattern`, and a join is `animation.frames/concatenate-frames`, with no cycle (Z3) | – |
| `animation.effects`: `:apply-zones`, `:scope`, `zone-remap-effect?`, `resolve-effect-params` | `zone-stage-effect?`, `resolve-effect-params`, `choice-at` | – |
| `animation.effects.zone`: `:zone-chase`, `:zone-shift`, `:zone-swap`; the selector's `:scope #{:item}` | one `:zone-pattern` (and the Map cycle), because a pad or a modulator can't switch an effect id (Z5 §11); Z4 hid the selector | – |
| `frame-service`: `get-active-zone-effects` in reading order, `:zone-effects` in the render context, the stage before the point effects, a throw reported | `get-active-zone-effects` (`services/frame_service.clj:132-143`), `stage-patterns` and `run-zone-stage` (`:400-434`, reported under `[:zone-patterns :effects id]`) | – |
| `show-clock`: `record-render!` counts the zone effects | not built: the profiler counts the point chain (`services/show_clock.clj:91-98`) | left out (§12.8) |
| `effect-bank`: `:scope`, the Zone tab's hint | built | – |
| `parameter-controls`: `:zone-group-id`, `:zone-group-ids`; `zone-chips/zone-order-chip` | `:ref`, `:ref-list`, `:map-list`; the lane picker (`views/components/zone_pattern_editor.clj`) | – |

**§4, 8b**

| Here | As Z5 built it | Left |
|---|---|---|
| `vibes.library`: `audience-fx?`, `playable-fx`, §7's lint rows; `listens-to` reads a chase's `:restart-on` | `audience-fx?` and `playable-fx` (`vibes/library.clj:173-191`), Z5 §8.8's rows (`stage-rows`, `:556-575`); `reactions/effect-sources` reads `:step-on` and `:restart-on` (`vibes/reactions.clj:144-157`, Z5 S-10) | two info rows (§12.8) |
| `vibes.engine`: `enter-look` and `press-flash` switch on `playable-fx`; a refused cell is `{:why :audience-fx :cell}` | `take-fx`, `press-flash`, `refresh-fx` (`vibes/engine.clj:178-194`, `:418-444`, `:655-664`) | – |
| `vibes.reactions`: four templates and `lane [state]` | – | **§12.2–§12.7** |
| `subs`, Perform: the refusal on the status line | `subs/vibe-refused-text` (`subs.clj:1900-1911`), Perform's `#vibe-refused`: "B3 (Zone pattern) can reach the crowd: tick Allow audience cells in this vibe" | – |
| `:zone-chase` gains `:restart-on` | `:zone-pattern`'s `:restart-on`, through `pulses/rebase` (`routing/zone_remap.clj:88-103`) | – |
| `README.md`: a "Zone effects" paragraph | not written | a line for the four, and the audience rule for cells (§12.6) |

**§7**

- The three effects' parameters are `:zone-pattern`'s (Z5 §8.2). `:zone-a` and `:zone-b` are the lane's first two (Z5 S-6), and a Swap trades on odd steps instead of reading an `:amount`.
- The step, loop and bounce, dropped refs, and the chase, shift and swap tables: Z5 §8.2's styles table and `zone-remap-test`.
- Moved, never copied (probe v22): changed. A copy is one frame under the union of its places (Z5 §8.4).
- Driving the numbers: any modulator on Rate and Offset; Style, Moves and Path pick by index (Z5 S-8).
- Order: reading order (`frame-service-test/grid-patterns-run-in-reading-order-test`).
- The audience rule: Z5 §8.8, over refs. A pattern reaches its whole lane, `:from` doesn't count, and a zone counts through every map.
- **The reactions (8b): not built.** §12.2–§12.7.
- The lint rows: 1 (two zones), 2 (a group that's gone), 3 (a place with no laser) and 6 (the audience, at warn, the level Phase 3 gives an audience cue) are Z5 §8.8's. Rows 4 (a laser in `:from` goes dark) and 5 (nothing to swap) aren't built (§12.8).
- Invariants 1–13: Z5 §8.9 (1 as changed for copies; 13 is its 13).

**§8, the tests**

- 8a's: Z5's, under its own names (Z5 §13, "Tests added"): `zone-remap-test` (23), `effects-test` (3), `frame-service-test` (10), `compose-test`'s fold tables instead of `multi-engine-test`, `show-clock-test`, `zone-remap-test/lane-expands-every-laser-and-drops-unknown-test` for a removed group, and the UI's `zone-patterns-test`.
- 8b's `library-test` and `engine-test`: Z5's `audience-fx-test` (with `playable-fx`), `lint-zone-pattern-rows-test`, and the four engine tests by the same names. `pulses-test/zone-chase-restarts-on-its-press-test` is `zone-patterns-test/restart-on-rebases-test`. Perform's `refused-zone-cell-is-explained-in-the-status-line-test` exists only as a sub test (`subs-test/vibe-pick-fx-blocks-audience-cells-test`).
- **8b's five `reactions-test` tests: not built.** §12.7.

### 12.2 The four templates

They join `reactions/templates` (`vibes/reactions.clj:33-64`) in its menu order, each after its role's Phase 4 ones, so the menus that list them group them by role. Each is one `:zone-pattern`: Z5's preset of its style for a cell (`zone-patterns/preset-instance style :cell`, `routing/zone_patterns.clj:110-119`) with its press. It steps on the press itself (`:step-on`, Z5 §8.3: the source's `:count` in the tick's pulse view), not through a Step modulator on its offset as §7 had it. The rest is Z5's defaults: rate 1, offset 0, width 1, `:source :auto` (the All picture when the cue draws into All, else what is in the places), `:from :all`, the show's beats, and it follows the pattern pads (Z5-Q1 A: Off stops it, a style pad switches it; Z8b-Q4).

| Kind | Menu (role) | Cell | Style, places (§12.3) | Steps | At rest (before its source's first press, as Phase 4's invariant 11 means it) | Goes into |
|---|---|---|---|---|---|---|
| `:zone-hop` | "Zone hop: next zone (one zone at a time)" (HIT) | "Groove · Zone hop" | Chase over Every laser | on each HIT: the next laser | the All picture sits on the first laser (a lone laser's left half) until the first HIT, by design (§7) | FX set |
| `:zone-swap` | "Zone swap" (ACCENT) | "Groove · Zone swap" | Swap, A ⇄ B | on each ACCENT: the 1st trades A and B, the 2nd trades them back | as drawn: step 0 trades nothing | FX set |
| `:zone-shift` | "Zone shift: next zone" (ACCENT) | "Groove · Zone shift" | Shift, A → B → C → D | on each ACCENT: what is in each zone moves one zone on | as drawn: a shift by 0 | FX set |
| `:zone-chase` | "Zone chase while held" (FLASH) | "Groove · Zone chase" | Chase over Every laser | on the beat (a laser a beat), Restart on FLASH: each FLASH starts it from the first laser | off: on only while FLASH is held | flash FX |

They are made as Phase 4's are. `:vibe/add-reaction {:vibe :kind}` (`events/handlers/vibes.clj:171-180`) puts the cell in the first empty effect cell from the bottom row up (a new project's Master holds `[0 3]`), last in the FX set or, for the chase, in the flash FX, as one undo step ("Add Tap Reaction"), and `engine/reconcile` switches it on at once if the vibe plays (the chase waits for FLASH). Through Z5's `listens-to` each is one of its role's reactions: the role's line on the Vibes tab and its Perform pad name it, and Phase 4b's lint rows count it ("Groove: HIT moves effects only (Zone hop)", "Groove · Zone hop listens to HIT, which has no key yet"). Once one exists, Z5's pattern pads and the zone map pads show, as for any zone pattern (Z5-Q10 A). The swap and the shift move what the cues draw into zones A and B (or C, D); the hop and the chase move what is drawn into All (§7: "route the cue's items to zones (swap and shift) or leave them in `:all` (hop and chase)").

### 12.3 The default places

- **Hop and chase: Every laser, left to right** (`:lane/lasers`, Z5-Q4 A): the lasers that are on and in All, in rig order, and a lone laser's two halves. It follows the rig, so the vibe works on whatever rig it plays on. One case can't keep it: a laser in All, on or off, that can reach the audience (its own group is an audience group: it scans the audience). Every laser would sweep it (Z5 §8.8's second row), so the places are instead **the lasers that are on and in All and can't**, left to right, each by its own group (`:auto.laser/<id>`). That list is fixed when made; the pattern window edits it.
- **Swap and shift: zones**, as Z5's Swap and Shift presets: A ⇄ B, and A → B → C → D. A swap or a shift trades what is drawn into its places (a permutation re-keys only the frame keys that name a lane place, `routing/zone_remap.clj:246-253`), and cues draw into All or zones, so over lasers they would trade only what a cue pins to a laser. Under the default map, Left / Right, A is the left and B the right: an ACCENT swaps left and right, as this phase's opening says. Another map sends them elsewhere, as it does every zone.

So §7's three wishes hold: not audience (the moves by their places, the trades by the rule, §12.5), at least two places (§12.4), left to right (rig order; A before B).

### 12.4 When an entry is greyed

An entry is greyed while its places on this rig are fewer than two: the count behind Z5's "needs two zones" lint error (`routing/zone_patterns.clj:297-307`: `zone-remap/lane` over the lasers that are on and in All; a zone is always one place). So the swap and the shift never are (A and B are two places on any rig), and one laser on its own is two places (its halves). The hop and the chase are greyed when:

| Rig | Their places | Hint, after the entry's text |
|---|---|---|
| no laser on and in All | Every laser, empty | "— needs a laser that's on and in All (Projectors tab)" |
| a laser in All can reach the audience, and fewer than two that are on and in All can't | those lasers | "— needs two lasers in All that don't scan the audience (Projectors tab)" |

For example "Zone hop: next zone (one zone at a time) — needs a laser that's on and in All (Projectors tab)". `:vibe/add-reaction` with a greyed kind changes nothing and adds no undo step, as with an unknown kind.

### 12.5 The audience rule (V10 A, as Z5 built it)

Nothing new: the cell is one more zone pattern in the vibe's FX set (the chase: its flash FX), so Z5's rule applies as it is (Z5 §8.8, invariant 13).
- `:vibe/add-reaction` ends in `engine/reconcile` → `refresh-fx` → `take-fx` (`vibes/engine.clj:178-194`), which switches on only `library/playable-fx`. A cell whose lane can reach the crowd (`audience-fx?`: a lane ref passes `audience-ref?`) stays off in a vibe that doesn't allow the audience, and `[:vibe-runtime :problem]` is `{:why :audience-fx :cell c}`. Perform says "A4 (Zone pattern) can reach the crowd: tick Allow audience cells in this vibe", and the lint warns "Groove: A4 (Zone pattern) can send pictures to the crowd (Crowd), so this vibe never switches it on. Allow audience cells, or take B out of its zones". Ticking Allow audience cells switches it on at once (`refresh-fx`). FLASH leaves a refused chase off and says so (`press-flash`). A click on the Effects grid is never blocked.
- With §12.3's places a new hop or chase never reaches the crowd. A swap or a shift does when a map sends one of its zones to an audience group (Z4's "Crowd sweep": B → Crowd), or while a scanner is in All (Unison sends every zone to All). The vibe then leaves it off and says why, and the user decides.

### 12.6 What changes

| Namespace | Change |
|---|---|
| `vibes/reactions.clj` | the four templates in `templates`; NEW `zone-reaction?` (a template holding a zone pattern), `lane [state groups kind]` (§12.3; groups is `library/audience-groups`, which this namespace can't require, since library requires it), `places [state zones]` (§12.4's count), `zone-hint [state groups kind]` (§12.4's text, nil when it isn't greyed); `reaction-cell` takes the places; `add-to-vibe` takes groups (`#{}` without), puts §12.3's places in and makes nothing for a greyed kind |
| `events/handlers/vibes.clj` | `handle-add-reaction` passes `library/audience-groups` |
| `subs.clj` | NEW `vibe-tap-hints`: `{kind hint}` of the greyed entries, from the rig and the audience groups |
| `views/tabs/vibes.clj` | `tap-menu` greys an entry and adds its hint |
| `README.md` | Tap reactions names the four; Safety: an effect cell whose zone pattern can reach the crowd isn't switched on either |

No change to `vibes/library.clj`, `vibes/engine.clj`, `routing/`, `events/show_lock.clj` (it already refuses `:vibe/add-reaction`, `:45`) or `events/handlers/history.clj` ("Add Tap Reaction", `:224`). The Effects grid's New tap reaction ▸ (`views/components/grid_cell.clj:398-418`) lists `templates` by role, so it offers the four too, with no change there: `:effects/new-reaction` (`events/handlers/effects.clj:91-105`) makes the template as it is (Every laser, not narrowed, since the grid has no audience rule; A ⇄ B; A–D), on but the chase, as the Strobe, and not greyed. Asked as Z8b-Q1.

### 12.7 Tests

Fixtures: `starter`, `ctx` and `press` (`test/laser_show/vibes/reactions_test.clj:18-33`); the show clock by hand, as `fade-in-starts-dark-and-returns-to-full-test` runs it (`:157-177`); `run` (`test/laser_show/events/handlers/vibes_test.clj:20-28`: the real pipeline, so the show lock and the history are in it); Z5's way of rendering a cell's pattern (`render-with`, `test/laser_show/services/frame_service_test.clj:529-538`); `crowd-rig`'s scanner (`test/laser_show/vibes/engine_test.clj:703-713`). A rig of three lasers, `p1`–`p3` at `10.0.0.1`–`.3`, so rig order is theirs.

- `laser-show.vibes.reactions-test`: `every-template-is-valid-test` (fourteen kinds: HIT 5, ACCENT 4, FLASH 3, VIBE 1, any 1); `templates-are-identity-at-rest-test` keeps to the point effects; NEW `zone-templates-are-zone-patterns-test` (each is Z5's preset of its style with its press, its menu, name, role and slot, and its role hears it), `zone-reactions-at-rest-test`, `zone-hop-moves-the-picture-on-each-hit-test` (through the engine and the show clock: 0, 1, 2, 3 HITs → p1, p2, p3, p1), `zone-swap-and-shift-on-each-accent-test`, `zone-chase-restarts-on-each-flash-test`, `lane-leaves-a-scanner-out-test`, `zone-reactions-need-two-places-test` (§12.4's table, one laser, an excluded scanner).
- `laser-show.events.handlers.vibes-test`: NEW `add-zone-reaction-test` (each kind: the cell, its name, its slot, on at once in the vibe that plays but the chase, one undo step "Add Tap Reaction", undone; refused while the show is locked), `greyed-zone-reaction-changes-nothing-test`, `zone-reaction-that-can-reach-the-crowd-stays-off-test` (V10 A: a map sends B to Crowd: the swap stays off, the problem names its cell, Allow audience cells switches it on; a scanner in All: the hop leaves it out and comes on).
- `laser-show.subs-test`: NEW `vibe-tap-hints-sub-test`.
- UI `laser-show.ui-test.vibes-test`: `tap-reaction-menu-adds-a-cell-to-the-vibe-test` (HIT's list gains the hop, greyed with its hint: the starter show has no laser); NEW `zone-reactions-in-the-tap-menus-test` (ACCENT's and FLASH's lists; with two lasers the hop isn't greyed; Zone swap makes "Shapes · Zone swap", on).
- UI `laser-show.ui-test.grid-test/new-tap-reaction-on-an-effects-cell-test`: fourteen templates.
- Unchanged: `history-handlers-test/history-labels-name-real-events-test`, `show-lock-test`, and the other `vibes-test` and `perform-test` tests.

Run: `clj -M:mac-arm:test reactions vibes-test subs-test library engine show-lock history-handlers`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.vibes-test`, then `-n laser-show.ui-test.grid-test`, then both full suites.

### 12.8 Not built, from §4 and §7

- Lint rows 4 and 5 (info): a laser in the `:from` place that goes dark while a move runs, and a swap or shift with nothing to trade ("no cue here draws into A or B"). Z5 §8.8's table replaced this one without them. The second would tell a user why a new Zone swap does nothing while every cue draws into All. Asked as Z8b-Q3.
- `record-render!` counting the zone stage: a profiler count.
- A UI test of Perform's refusal line: Z5 tests the sub only.

### 12.9 Commit plan

| # | Commit | Red first |
|---|---|---|
| 1 | `plans: vibe Phase 8 after Z5: what Z5 covers, and the four zone reactions to build (§12)` | – |
| 2 | `feat(vibes): Zone hop on HIT, Zone swap and Zone shift on ACCENT, and Zone chase while FLASH is held, as zone patterns that step on the press, over every laser (none that scans the audience) or zones A and B; one undo step, on at once in the vibe that plays` | `reactions-test`, handlers `vibes-test` |
| 3 | `feat(ui): the Tap reactions menus offer the four zone reactions, greyed with a hint while the rig has too few places for them` | `subs-test`, UI `vibes-test` and `grid-test` |
| 4 | `plans: vibe Phase 8 done (<sha>): as built, red first, both suites` | – |

### 12.10 Questions (★ = built)

> **Z8b-Q1 · The Effects grid's New tap reaction ▸ and the four.**
> - **A ★** It offers them, as every template: made as they are (Every laser, not narrowed, since a click on the grid is a decision (V10); A ⇄ B; A–D), on at once but the chase, and not greyed. The hop moves the All picture to the first laser at once, as it does in the vibe that plays.
> - **B** It doesn't: the grid's New zone pattern ▸ makes patterns, off, and Step on is set in the pattern window.
> - **C** It offers them, with the hop off until switched on (Z5's rule for a new pattern cell).

> **Z8b-Q2 · The swap's and the shift's places.**
> - **A ★** Zones (A ⇄ B; A → B → C → D): what the cues draw into, through the map in use.
> - **B** Every laser, as the moves: they then trade only what a cue pins to a laser.
> - **C** All four over zones: the hop and the chase then move between the map's sides, not lasers.

> **Z8b-Q3 · §7's two info rows that Z5 didn't build** (a laser goes dark while a move runs; a swap or shift with nothing to trade).
> - **A ★** Later, with the rest of the patterns' lint.
> - **B** Now, at least "has nothing to swap: no cue in this vibe draws into A or B", which a new Zone swap often meets.

> **Z8b-Q4 · Do the four follow the pattern pads?**
> - **A ★** Yes, as every zone pattern does (Z5's default): Off stops them, a style pad switches them, As set gives them back.
> - **B** No (`:follow-pads? false`, as Z6's grid): a tap reaction keeps its own style whatever the pads say.
