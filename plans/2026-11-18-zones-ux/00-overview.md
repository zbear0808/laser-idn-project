# Zones and projectors: set up once, let cues adapt

**Written:** 2026-11-18   **Written for commit:** `67735d0` (`master`)
**Status:** Draft for discussion. Three options (§5), with a recommended path (§6) and open questions (§8). Nothing has been built yet.
**Requirements:** `plans/2026-11-19-zones-needs/` (needs, conflicts, core features). Check these options against it.
**Related:** review 02 #17 / M9 (a deleted zone group leaves dangling references), review 04 (calibration pattern, zone precedence written down two ways), UX plan F-18 (the Zones subtitle), vibe plan G6 (zone-scoped vibes) and R-5 (per-zone effect cost)

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`. **NEW** marks anything that doesn't exist yet.

## Start here

Setting up several lasers is one of the worst parts of a laser show. Today it goes like this. You turn on the projectors. You go to the Zone Groups tab to make groups. You tick projectors into them (or tick chips on the Projectors tab, which is a second place to do the same thing). Then, inside **every cue**, you pick a zone group for the cue, its items or its tracks. If you skip all of that, every laser draws the same picture.

What we want:
1. **Setup is quick and mostly automatic.** You say where each projector is and what it's for, and the useful groups appear by themselves.
2. **Cues don't need to know the rig.** A cue made with no zone setup still looks good on 1, 2 or 8 projectors. Moving to a different rig doesn't mean editing cues.
3. **Lasers can draw different things without per-cue wiring.** Variation comes from defaults that cascade and from "distribution" (mirror, alternate, chase, spread, offset).
4. **Effects can change the grouping**: chase across zones, sweep, alternate on the beat, switch layouts live.
5. **Safety zones are real.** At the moment they don't exist at all (§2).

## 1. First take, before reading any code

This was written before looking at anything. A copy is at `$TMPDIR/opencode/zones-ux-v0-draft.md`.

- **Output = projector + its safety zone**, always handled as one unit.
- **Stage map**: you place outputs visually, and groups appear automatically from where they are: All, Left, Right, Center, Odd, Even, Outer, Inner, mirrored pairs, and each output on its own.
- **Roles, not hard targets**: cues point at `All`, `A`, `B`, `Left`… A **Layout** decides which outputs fill each role. Change the layout and every cue goes somewhere new, with no cue edits.
- **Inherit by default**: a cue's target comes down from the show, then the bank or page, then the column or layer, then the cue, then the effect. If you set nothing up, that's `All` + `Unison`, which is today's behaviour.
- **Distribution modes**: Unison, Mirror, Alternate, Chase, Spread (one wide canvas), Random, Offset. Each can be modulated.
- **Zone effects**: an output's index or position as a modulation source, zone chase, a moving mask or sweep, layout morph.
- **Live**: zone pads to solo or mute a group, and big global buttons to override the distribution.
- **Safety**: safety zones are applied last, on each output, and nothing can get around them.

## 2. What the code does today

These findings come from a full code survey. The ones marked ✔ were checked by hand.

| Area | Today | Why it matters |
|---|---|---|
| Safety zones | ✔ **None.** The only mention is the Projectors tab subtitle (`views/tabs/projectors.clj:826`). The corner pin only warps the picture; it doesn't clip it. Calibration mode outputs nothing | This is the biggest gap. Every option needs it first |
| Model | Each projector has `:zone-groups [ids]`. The zone groups are `{:id :name :description :color}`, and there are six defaults (`state/domains.clj:340-389`). There is nothing between a projector and a group | "Projector with its zone" doesn't exist as a thing you can work with |
| `:all` | ✔ Just a name. A projector only gets `:all` content if it lists `:all` (`backend/multi_engine.clj:47-73`). New projectors start with `[:all]` | `:left`, `:right`, `:center`, `:graphics` and `:crowd` start empty, so a cue sent to them shows nothing anywhere |
| Routing | Each item resolves to **one** group ID. In order: the item's `:zone-group-id`, then a Zone Selector effect, then the track (and its folders), then the parent group, then the cue default, then `:all` (`routing/zone_effects.clj:127-156`) | There are four ways to say the same thing, and each editor shows a different mix of them |
| Rendering | Each cue is rendered once per tick into `{group-id → frame}`, then the effects grid runs on every group's frame (`services/frame_service.clj:338-405`) | It's cheap. Nothing renders per output, so there's no per-projector variation |
| Projector frame | ✔ Each projector joins the frames of every group it lists, cue by cue, then runs its calibration chain (`multi_engine.clj:119-150`) | A projector in two groups draws both pictures, which roughly doubles its point load |
| Zone modulation | The keyframe editor lets you keyframe `:target-zone` and draws zone segments, but routing reads the raw static value | A "zone chase" looks supported in the UI and does nothing |
| Effects grid | A Zone Selector placed there does nothing, because global effects run after routing | You can't scope global effects to a zone |
| Virtual projectors | They have a domain, subscriptions and a listing, but you can't create one and no engine streams one | These are half of a "projection zone" concept already |
| Persistence | Projectors and groups live in the project file (`hardware.edn`). New Project wipes them. Cues store hard group IDs | The rig and the show are tangled together. The rig can't be reused across shows, and shows can't be moved between rigs |
| UI | Projectors tab (connection, zone chips, calibration) and Zone Groups tab (list, an assignment checkbox list, details). Targets are picked per cue in the Cue Chain Editor and the Timeline editor | Assignment happens in two places, the checkbox may toggle twice (`views/tabs/zones.clj:164-179`), there's no zone badge on the grid, "Cues targeting" always reads 0, deleting a group leaves dangling references, and nothing previews a single projector |

## 3. Revised model

The first take holds up. The code points to three changes:

1. **"Output" should be the "projection zone"** (Beyond calls it that): a projector plus its geometry, its safety and its tags. The half-built **virtual projectors** are the natural place for this. One physical projector can carry several zones, for example a graphics zone and an audience-scanning zone with stricter safety.
2. **Routing should become per-output weights, not one group ID.** Each tick, every cue or track works out a gain from 0 to 1 for every output. Hard group assignment is the case where the weights are only 0 or 1. Chase, sweep and alternate-on-beat are modulated weights. Weights are cheap: you still render once and only change the gain per output.
3. **Per-output variation comes in three cost tiers:**

| Tier | Examples | Cost |
|---|---|---|
| Weights | route, chase, sweep, solo or mute, alternate | about zero. Render once and apply a gain per output |
| Per-output post-transform | Mirror (flip the right side), Spread (slice one wide canvas by stage position), small rotate or offset per output | cheap. Render once, then one transform per output |
| Per-output render | an LFO phase offset by output index, a different colour per output | renders × outputs. Only when a parameter actually reads an output source; Unison stays at one render |

The pipeline would become: **cue render (once) → effects grid → per-output: weight × transform → projector colour calibration → zone geometry → safety (final, can't be bypassed) → encode.**

## 4. UX building blocks (every option uses some of these)

### 4.1 One "Rig" tab instead of Projectors + Zone Groups
- **Left: stage map** (front view). Each output is a card you can drag along a truss line, with Identify, a status dot and a live thumbnail of what it's drawing. A template picker: Line, Arc, Sides + Center, Stacked, Surround.
- **Right: inspector** for the selected output, in sections: Connection, Geometry (corner pin, axis), **Safety**, Colour, Tags (Graphics, Audience scanning).
- **Bottom: groups.** Automatic groups are generated from the map and refresh when you add a projector, with a lock icon so they can't be edited. Your own groups sit next to them, and there's an outputs × groups matrix for exceptions.
- **Auto-assign, in increasing effort:**
  1. A rough order guessed from IP and name (`L`, `Left`, `SR`…).
  2. Pick a template and drop cards into its slots.
  3. An **Identify walk**: each projector flashes in turn, you click where it lit up, and you're done.

### 4.2 Safety zone editor (NEW)
- With the projector outputting a real test pattern at low brightness, you drag a **scan-limit polygon** and add **blocked regions**: no-beam areas such as the audience heads, a balcony or a camera.
- Lines that cross a boundary are clipped, with blanking at the crossing point.
- Audience-scanning zones get extra limits (brightness cap, minimum scan speed); the vibe audience rules already read `:crowd-scanning` tags.
- "Copy to similar outputs" (mirrored for the other side).
- Safety runs last, after the zone geometry, so content and effects can never get around it.

### 4.3 Targets in cues: one control, inherited by default
- A single **Target** chip, shown the same way on every level: show default → grid column (or page) → cue → track or item. It reads **Inherit** by default.
- The Zone Selector effect goes away as a way to route. A track or item target replaces it, and the "modulate the routing" job moves to zone effects (§4.5).
- **Grid cells show a small target badge.** Column headers can set a target, so you can say "column 3 = Left" once.

### 4.4 Distribution (on a cue or track; default Unison)
`Unison | Mirror | Alternate (odd/even) | Chase | Spread | Offset | Random`, with order options (L→R, R→L, ping-pong, center-out) and a rate (beats). These are plain parameters, so modulators, keyframes and vibe pulses can drive them.

### 4.5 Zone effects and modulation (NEW)
- **Output modulation sources**: `output-index`, `output-x`, `output-side`, `output-count`, next to the existing per-point sources.
- A **"spread phase across outputs"** toggle on wave modulators, which turns one LFO into a wave that rolls across the rig.
- **Zone Chase / Step**: steps the weights through a group on the beat, on a HIT pulse, or at random.
- **Sweep**: a soft window that moves across the stage map.
- **Solo/Mute zone** effect cell: lets the effects grid finally scope by zone (vibe G6).
- Fix routing to read modulated and keyframed zone parameters, so what the editor offers actually plays.

### 4.6 Live
A **zone pad strip** on Grid or Perform: tap = solo, shift = mute, with Unison / Mirror / Alternate / Chase overrides. A per-output preview strip shows each projector's actual output, after calibration and safety.

## 5. Options

### Option A: "Smart groups + inherit" (small step, least risk)
- Keep "a cue targets a group ID".
- Add:
  - safety zones;
  - make `All` implicit: every enabled output gets it unless excluded;
  - automatic groups from stage order;
  - one Rig tab;
  - the target cascade (show → column → cue → track/item) and grid badges;
  - clean up references when a group is deleted;
  - make keyframed Zone Selectors route, which gives a basic zone chase.
- **Good:** small migration, little new thinking, and it fixes most of the setup pain.
- **Bad:** cues are still bound to group names. There's no per-output variation (no mirror, spread or offset), and "different images" still needs layers sent to different groups.
- **Size:** M (about 6–9 d, about half of it safety zones and the test pattern).

### Option B: "Roles + layouts" (the routing is swappable)
- Cues target a few **roles** (for example `Main`, `A`, `B`, `C`, `Accent`, `Audience`).
- A **Layout** maps roles to groups or outputs:
  - "Unison": everything → All.
  - "Split": A = Left, B = Right.
  - "Alternate": A = Odd, B = Even.
  - "Trio": A/B/C = L/C/R.
- You switch layouts live from layout pads, a layout cue or effect cell, or modulated on the beat.
- One cue with an A layer and a B layer looks different under each layout, and nobody edits it.
- The default layout sends every role to All, so a show that sets up nothing looks the same as today.
- **Migration:** today's group IDs become roles, and today's projector assignments become the "Default" layout.
- **Good:** this answers "don't configure every cue for one zone setup" directly. It carries over between venues, and the grouping can be performed and modulated.
- **Bad:** it adds another concept (role vs group). The UI has to hide layouts until you make a second one. Variation still comes from routing, not per-output transforms.
- **Size:** L (A's foundation + about 5–7 d).

### Option C: "Distribution engine" (cues are rig-aware by themselves)
- Every cue or track has a **Distribution** (§4.4) and can read the output sources (§4.5).
- Routing becomes per-output weights plus per-output transforms. A per-output render happens only when a parameter needs one.
- One cue, with zero zone setup, can mirror, alternate, chase or spread across however many projectors are on the stage map.
- **Good:** this is the strongest answer to "lasers make different images without configuring". It's the most expressive and gives the best busking (pairs well with vibe pulses).
- **Bad:**
  - it's the biggest change to `frame_service` and `multi_engine`;
  - CPU grows with outputs × cues when per-output rendering is used, so it needs a Unison fast path and caching;
  - the stage map positions have to be roughly right;
  - fixed "this text only on the graphics laser" routing still needs groups.
- **Size:** L–XL (A's foundation + about 8–12 d).

### Comparison

| | A | B | C |
|---|---|---|---|
| Setup effort for the user | low | low (+ optional layouts) | low (positions matter) |
| Cue edits when the rig changes | some (group names) | none | none |
| Different images per laser | by layers + groups | by layers + layouts | automatic (distribution) |
| Modulating the grouping | zone chase via keyframes | layout switching | everything (weights, transforms, phase) |
| Engine change | small | small–medium | large |
| New concepts | none | role, layout | distribution, output sources |

## 6. Recommended path

Build them as layers that ship one at a time. B and C don't compete: B decides **where** each layer goes, and C decides **how** one layer spreads across its outputs.

| Phase | What | Needs |
|---|---|---|
| 0 | **Foundation:** projection zones (made real out of virtual projectors), a working test pattern / calibration output, the safety zone stage and editor, per-output preview | none |
| 1 | **Option A:** Rig tab, stage order and automatic groups, implicit All, the target cascade + grid badges, delete cleanup, fix the double toggle, keyframed zones route | 0 |
| 2 | **Weights + cheap distribution:** routing as per-output weights; Unison/Alternate/Chase/Sweep/Mirror/Spread (render once); zone pads | 1 |
| 3 | **Per-output sources:** `output-index`/`output-x`, phase spread, per-output render with a Unison fast path | 2 |
| 4 | **Roles + layouts** (Option B), if Phases 1–3 still leave you editing cues per rig | 1 |

Phase 0 is needed no matter which option you pick. Phases 1 and 2 already cover most of the stated pain.

## 7. Risks and notes

- **Point budget.** An output that receives several groups or cues joins them all, so A + B + crowd on one projector can flicker. The per-output preview should warn about this, for example "this output is at 140% of its scan budget".
- **Safety fails closed.** If the safety stage throws, that output sends blank frames. Today a failing calibration effect lets the frame through (review 03, 02-phase2-output-safety).
- **Rig vs show.** Positions, safety and geometry belong to the physical rig. Roles, layouts and distribution belong to the show. Where the rig is stored decides whether a show can be moved between venues (§8).
- **Migration.** Existing `:zone-group-id`, `:destination-zone` and `:target-zone` values map to group targets or roles. The Zone Selector effect is kept for loading old files and hidden from the bank.

## 8. Questions to refine this

**Scope and priority**
1. Which matters most to you first: (a) fast setup, (b) safety zones, (c) different images per laser without per-cue work, (d) modulating the grouping live?
2. Do A → C → B (§6) in that order, or go straight to one option?
3. How many projectors do you usually run (2? 4? 8+?), and in what layouts: a line on a truss, sides + center, surround?

**Projectors, zones and safety**
4. Should one physical projector be able to carry **several zones** (a graphics zone and an audience zone), or is one zone per projector enough for now?
5. Safety zone shape: is a scan-limit polygon plus blocked regions enough, or do you also need a per-zone brightness cap, minimum scan speed or "no static beams" rules?
6. When the safety stage fails, should that output go dark (fail closed), or warn and keep the last good frame?
7. Is an "Identify walk" (each laser flashes, you click where it is) worth building, or is dragging onto a template enough?
8. Should the rig (projectors, positions, safety) live **outside** the show file, in an app-level or "venue" file, so New Project doesn't wipe it and a show can load on a different rig? Or stay in the project?

**Groups and targets**
9. Should `All` be implicit (every enabled output, opt-out) instead of a group you have to tick?
10. Which automatic groups do you actually use: Left/Right/Center, Odd/Even, Outer/Inner, mirrored pairs, each output, by tag (graphics/audience)?
11. Should the six built-in groups (`left`, `right`, `center`, `graphics`, `crowd`) be removed, kept empty, or filled automatically?
12. Where do you want to set a default target most: per grid column, per page or bank, per vibe, or only per cue?
13. Is it fine to remove the Zone Selector *effect* as a routing method (keeping item/track/cue targets), or do you rely on it?

**Roles and layouts (Option B)**
14. Do you think in "A/B" layers ("main look" + "accent") or in places ("left", "center")? This decides whether roles are worth having.
15. Should layout switching be a cue in the grid, a separate pad strip, part of vibes, or all of these?
16. Should a cue be able to override the layout ("this cue always splits L/R"), or does the layout always win?

**Distribution and effects (Option C)**
17. Which distribution modes would you use most: Mirror, Alternate, Chase, Spread (one wide canvas), Offset (phase wave), Random?
18. For Spread, should the stage map model real geometry (the angle each laser covers), or is "evenly divide the canvas" good enough?
19. Should distribution belong to a cue, a track, an effect cell on the effects grid (global), or all three?
20. Should zone effects react to the vibe pulses (HIT steps the chase, ACCENT flips the mirror)?
21. How much CPU headroom do you have? Is rendering per output (outputs × cues) acceptable when a cue uses per-output phase?

**Live and preview**
22. Do you want a zone pad strip (solo/mute/override) on the Grid tab, the Perform tab, or both?
23. Is a per-output preview strip (the real output after calibration and safety) worth the screen space, all the time or on demand?
24. Should the grid show each cell's target or distribution as a badge, or would that clutter it?

**Migration**
25. Are there existing shows we must load unchanged, or can old zone setups be converted once with a prompt?
