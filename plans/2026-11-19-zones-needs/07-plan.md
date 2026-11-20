# Plan: zones, rig order, zone maps, split lasers and zone patterns

**Written:** 2026-11-19   **Written for commit:** `c556d71` (`master`). Other work is landing in parallel. **Re-check every path and line against HEAD before starting any phase.**
**Status:** **Superseded** by `plans/2026-11-19-zones/` (`00-overview.md` and `01-model-and-routing-spec.md`), which add D-14 to D-32 and the phase docs. This doc is kept as the record of round 1. Where they differ, the new folder wins: for example, the middle laser now defaults to `:split` (D-17), zones are unlimited and wrap over a map's lanes (D-16, D-25), and both tabs stay (D-15).
**Builds on:** `02-needs.md`, `03-conflicts.md`, `04-core-features.md`, `06-zone-patterns-and-split-zones.md`. **Future rig setup UX:** `08-rig-setup-ux-future.md`. **Must fit with:** vibe Phase 8 (`plans/2026-11-18-vibe-mode/08-phase8-zone-effects.md`: zone chase, swap and shift on the effects grid), see §7.

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`. **NEW** marks anything that doesn't exist yet.

## Start here

You put the projectors in **left-to-right order** once. From that order the app builds **automatic groups**: All, Left, Right, Center, Odd and Even.

Cues never name the venue. They draw into **All**, or into abstract **zones A–D**. A **zone map**, which the performer can switch live, decides what A–D mean tonight: Left/Right, Odd/Even, or Unison.

When there aren't enough lasers, the groups adapt:
- With **one laser**, Left and Right become two overlapping halves of that laser.
- With an **odd number**, the middle laser belongs to **both** sides by default. You can change it to left, right or neither.

**Zone patterns** (Alternate, Odd/Even, Chase and so on) move content between zones on the beat or on a press. They can sit on a cue or on an effects-grid cell.

Every laser has a **safety zone** that is applied last. A **fit guard** cuts points, then brightness, when a laser's frame rate gets too low.

## 1. Decisions (2026-11-19)

| # | Decision | Answer to |
|---|---|---|
| D-1 | **At most 2 regions on one laser at the same time.** When the estimated frame rate falls too low, a **fit guard** first reduces points, then reduces brightness | Q-f |
| D-2 | **One laser: Left/Right split by default**, into **overlapping** regions. Each region is 75% of the **safe area's** width, so the middle 50% overlaps. The regions are computed inside the safety zone, and safety still clips the final frame | Q-g |
| D-3 | **The middle laser of an odd count is not split by default.** Splitting it is one toggle on the rig strip | Q-h |
| D-4 | **Not rigid.** To a cue, zones A–D are just different zones. Each can hold the same content, different content, or several tracks. Each can resolve to a different zone group | Q-i |
| D-5 | **Zone patterns live on cues and on effect cells.** A cue is built without thinking about the venue. **The performer changes the mapping live** (zone maps) | Q-j |
| D-6 | **The drum-machine step grid is its own later phase** (Z6) | Q-k |
| D-7 | **Rig setup v1 is an ordered strip.** It shows the detected IPs and names; you sort them left to right, and that strip is also where you decide which lasers go into which custom groups. Everything beyond that is in `08-rig-setup-ux-future.md` | Q-l |
| D-8 | **Plan only for now.** Each phase re-checks the code at HEAD before it starts | – |
| D-9 | **Middle laser of an odd count: default `:both`** (it is in Left and in Right). It can be changed to left, right or neither (and split, see Q-M10). This replaces D-3's "not split" default with something more specific | Q-M1 |
| D-10 | **Low frame rates are allowed.** The minimum fps is a setting that can go low. A UI element is **highlighted** when a laser's frame rate is below its minimum, and **cues that are poorly optimised get warnings** | Q-M3 |
| D-11 | **Quantised switching with a 50 ms window.** A switch of zone map or pattern style happens **at once** if it is within 50 ms of a beat. Otherwise it **waits for the next beat** | Q-M5 |
| D-12 | **Built-in maps: Left/Right, Odd/Even, Unison.** Left/Right and Odd/Even are the main ones | Q-M6 |
| D-13 | **The one-laser split also applies to Odd/Even** | Q-M7 |

These change some leanings in `03-conflicts.md`:
- **T-3:** cues target zones A–D or automatic groups, and zone maps bind those to the venue. So roles and layouts (option B) are now part of the core, renamed *zones A–D* and *zone maps*.
- **T-6:** sub-zones exist, at most 2 per laser.
- **T-11:** order only.

## 2. The model

| Thing | What it is | Stored or derived |
|---|---|---|
| **Output** | A projector. In v1 it may be split into at most 2 **regions** | stored (`:projectors`) |
| **Rig order** | The projectors from left to right | **NEW** stored: `[:rig :order]` |
| **Middle policy** | What the middle laser of an odd count does: `:both` (the default), `:left`, `:right`, `:none`, `:split` | **NEW** stored: `[:rig :middle]` |
| **Split width** | The width of each region as a fraction of the safe area (default 0.75) | **NEW** stored: `[:rig :split-width]` |
| **Automatic groups** | `:auto/all`, `:auto/left`, `:auto/right`, `:auto/center`, `:auto/odd`, `:auto/even`. They are computed from the order, the policy and which projectors are enabled. They are locked and always up to date | **derived**, never saved |
| **Custom groups** | Today's zone groups: a name, a colour and manual membership | stored (`:zone-groups`) |
| **Zones A–D** | Abstract targets a cue can draw into (`:zone/a` … `:zone/d`) | ids only |
| **Zone map** | `{zone → group}`, for example A = Left, B = Right, C = Center, D = All. There are built-in maps, plus custom ones | **NEW** stored: `[:zone-maps]` |
| **Active map** | The map that is playing now; the performer switches it | **NEW** `[:zone-map-active]`, live (§9 Q-M2) |
| **Zone pattern** | Steps over zones in time (alternate, chase…) at a rate or on a press | on the cue `:zone-pattern`, or an effect in an effects-grid cell |
| **Safety zone** | v1: a scan-limit rectangle plus blocked rectangles, per projector | **NEW** `[:projectors id :safety]` |
| **Fit guard** | Minimum frame rate, point reduction, dimming, per projector | **NEW** `[:projectors id :fit]` |

### How the automatic groups fold (middle = `:both`, the default)

`p1…pn` is the rig order, and `p1[L]` is p1's left region. With one laser the regions are `[0, 0.75]` and `[0.25, 1]` of the safe width. The one-laser split applies to Left/Right and to Odd/Even (D-13).

| n | Left | Right | Center | Odd | Even |
|---|---|---|---|---|---|
| 1 | p1[L] | p1[R] | p1 | p1[L] | p1[R] |
| 2 | p1 | p2 | p1, p2 | p1 | p2 |
| 3 | p1, p2 | p2, p3 | p2 | p1, p3 | p2 |
| 4 | p1, p2 | p3, p4 | p2, p3 | p1, p3 | p2, p4 |
| 5 | p1, p2, p3 | p3, p4, p5 | p3 | p1, p3, p5 | p2, p4 |

The middle laser of an odd count (n ≥ 3) has a **middle policy**, set with one control on the rig strip (D-9):

| Policy | What the middle laser does |
|---|---|
| `:both` | **default.** In Left and in Right (the table above) |
| `:left` | Left only |
| `:right` | Right only |
| `:none` | Neither, so it only plays All, Center, Odd/Even and pins |
| `:split` | Its `[L]` region in Left and its `[R]` region in Right (§9 Q-M10) |

With `:both`, if a cue lights Left and Right **at the same moment with different content**, the middle laser has to draw both pictures. §9 Q-M11 asks how.
- **All** = every enabled projector, unless the projector opts out (`:exclude-from-all?`, for an audience scanner, for example).
- **Each projector** also gets its own automatic group, `:auto/one <projector-id>` (named after the projector). A pin to one laser uses this group, and a warning shows when that laser is missing.

### Built-in zone maps

| Map | A | B | C | D |
|---|---|---|---|---|
| **Left / Right** (default) | Left | Right | Center | All |
| **Odd / Even** | Odd | Even | Center | All |
| **Unison** | All | All | All | All |

You can add custom maps, which are rows of zone → group (automatic or custom). Switching the map is a performer action: pads on the Grid toolbar and on Perform, a MIDI-learnable trigger, or an effects-grid cell.

### Targets (one control, inherited)

A target is `:all`, a zone (`:zone/a`…), or a group (`:auto/*` or custom). A **pin** to a group ignores the zone map (N-11).

The target is inherited down **show default → grid column → cue → track/item**. Every level shows a target chip with the value it ends up with and where that came from ("Zone A → Left, from column 3"). Grid cells get a badge.

Today's four ways to route are converted into this one control (§6).

## 3. The pipeline

```
each tick, each cue:
  render items/tracks           → {target-key → frame}   target-key ∈ :all | :zone/* | group id
  cue zone pattern (Z5)         → remap keys                 (cuts between zones, within the cue)
effects grid:
  zone effects / patterns       → remap keys                 (vibe Phase 8 stage, reading order)
  point effects                 → per key                    (as today)
snapshot {:cues [{:zone-frames}] …}

each projector engine:
  resolve keys via active map + rig resolver   → for this projector: [(cue, key, region|whole)]
  de-duplicate                                 → each (cue, key) at most once per projector (C-6)
  fit region frames into their regions         → at most 2 regions, joined with blanking
  fit guard                                    → reduce points, then dim, if fps < minimum
  projector calibration (as today)
  safety: clip to the limit, blank the blocked regions (fail closed)   ← always last
  encode/send
```

- The **resolver** is a new pure namespace, `routing/rig.clj` (**NEW**). Given `(rig order, enabled projectors, middle policy, split width, custom groups, zone maps, active map)`, it returns `{target-key → [[projector-id region-or-nil] …]}`. It is memoised on its inputs, and its output is the same for every engine. The future "preview on N lasers" simulator (doc 08) is this same function with an invented rig.
- The **engine change** is in `backend/multi_engine.clj:47-150`. Today `get-projector-zone-groups`, `extract-frames-for-zones` and `combine-zone-frames` work from the projector's `:zone-groups` list. They become: a lookup in the resolver output, de-duplication, fitting into regions, and the guard. The safety stage goes after `apply-projector-effects` (`:94-111`).
- **What it costs.** Cues still render once per tick. Each projector does a map lookup and, for split lasers, two affine fits. The fit guard's resampling costs O(points) per projector, and only when it triggers. There is no per-output render (N-24).

### Fitting into a region

- Region `[x0, x1]` is a fraction of the safe area's width, and the full height is used. Content in `[-1, 1]` is scaled by the region's width (uniformly, so shapes keep their aspect) and moved to the region's centre.
- In v1 the safe area is the bounding box of the scan-limit rectangle in content coordinates. The corner pin and the axis transform are not inverted. That's close enough for regions; the actual clipping is done by safety. See §8 R-3.

### Fit guard (D-1, D-10)

- Estimated fps = `scan-rate / (points + blanking points)`.
- `:min-fps` is set per projector, with a wide range (5 to 60). It **can be set low**. The default is §9 Q-M3.
- When the estimated fps is below `:min-fps`, and **only if auto-reduce is on** (Q-M3):
  1. **Points:** resample the lit runs evenly down to the number of points that reaches `:min-fps`. Corners (dwell points) are kept. There is a floor of 60% of the original (configurable).
  2. **Brightness:** if the floor is reached and the frame rate is still too low, scale the colours by `fps / min-fps`, but never below 50%.
- **Low-fps highlight (D-10).** The projector's card on the rig strip turns amber when it is below its minimum, and its fps figure is shown in bold. The toolbar's output status gets a small amber "LOW FPS · Laser R 18 fps" badge, so it can be seen from the Grid and Perform tabs. Clicking the badge opens that card.
- **Cue warnings (D-10).** The show clock already counts what each cue renders. From that count and the scan rates of the projectors each cue reaches, every cue gets an estimated fps.
  - A cue that would play below the minimum on this rig gets a small ⚠ on its grid cell and a line in its editor: "~2,400 points: about 10 fps on Laser L (25 kpps). Simplify, or lower the minimum".
  - The warning names the item that adds the most points, so you know what to simplify.
  - The estimate uses the current rig. With the future rig simulator, the same check can run for any rig size.
- The load meter on the rig strip shows what is happening, for example "split · 2 regions · −30% pts · −12% bright".
- This applies to any overloaded projector, not only split ones, which also answers T-7 and N-25.

## 4. UX

### 4.1 Rig strip (Z2), replacing the Projectors list and the Zone Groups tab

```
┌ Rig ───────────────────────────────────────────────────────────────────────┐
│ [Scan] [Add manual…]   Middle laser: (•) both ( ) left ( ) right ( ) none ( ) split │
│                          Split width: [75%]                                │
│  ◀ ┌──────────┐ ┌──────────┐ ┌──────────┐ ▶                                 │
│    │ Laser L  │ │ Laser C  │ │ Laser R  │      drag to reorder,             │
│    │10.0.0.5  │ │10.0.0.6  │ │10.0.0.7  │      or select + ◀ ▶ / Alt+←→      │
│    │● 60 fps  │ │● 58 fps  │ │● 41 fps⚠ │      thumbnail = actual output    │
│    │[thumb]   │ │[thumb]   │ │[thumb]   │      load meter + fit-guard note  │
│    │[Identify]│ │[Identify]│ │[Identify]│                                   │
│    └──────────┘ └──────────┘ └──────────┘                                   │
│ Auto   Left  ■        ·        ·          (locked, coloured by group)       │
│        Right ·        ·        ■                                            │
│        Center·        ■        ·                                            │
│        Odd   ■        ·        ■                                            │
│        Even  ·        ■        ·                                            │
│ Custom Graphics [ ]   [■]      [ ]        (click a box to toggle)  [+ group]│
│ Zone maps: [Left / Right ●] [Odd / Even] [Unison] [+ map]                    │
└────────────────────────────────────────────────────────────────────────────┘
 Selected card → inspector: Connection · Safety · Calibration · Fit guard · Exclude from All
```

- The order of newly discovered projectors starts from the IP, and failing that the name. It is saved as soon as you drag.
- The group rows are a matrix lined up under the strip, so "which lasers go into which groups" is decided against the visual layout (D-7). A split laser shows half-boxes `◧ ◨`.
- **Identify** flashes that laser's test pattern (Z1). The thumbnails and the load meter are the per-output preview (C-10).

### 4.2 Safety editor (Z1)

- The inspector's Safety section shows a canvas with the scan-limit rectangle and blocked rectangles, which you drag. **Edit live** sends the test pattern at calibration brightness (`:calibration-brightness`, `state/domains.clj`). The pattern is the outline of the limit plus a grid, clipped by the safety zone itself.
- Buttons: Copy to… and Mirror to… (a short list of projectors).
- An error in the safety stage blanks that projector, and the card shows "Safety error: output off".

### 4.3 Targets in cues (Z4)

- One **Target** chip in the Cue Chain Editor, the Timeline (tracks and clips) and the grid column header. Its menu lists Inherit, All, Zone A–D (each with "→ Left" from the active map), Groups (automatic, then custom) and Pin.
- Grid badges are small: `A`, `B`, `L/R`, a pin icon.
- The Zone Selector effect no longer appears in the bank. Old instances are converted (§6).

### 4.4 Zone patterns (Z5)

- **On a cue:** a "Zone pattern" section in the Cue Chain Editor and the Timeline settings, with:
  - a preset: Off, Alternate A/B, Odd/Even (A/B under the Odd/Even map), Chase A→B→C, Ping-pong, Center-out (C, then A + B), Random;
  - a rate in beats, or **Step on** HIT, ACCENT or a trigger;
  - a phase;
  - a **source**: "All", which moves the cue's All picture between the zones, or "Zones", which cycles content that is already in A–D.
- **On an effect cell:** the same presets as an effect in the effects-grid Zone tab, which applies them to every playing cue. These are Phase 8's chase, swap and shift taught about zones, plus **Zone alternate** (§7).
- The parameters are floats or choices, so modulators, keyframes and Phase 4 pulses can drive them.
- v1 patterns are **cuts**. Fades come later, once routing has weights.

### 4.5 Live

A **zone map** pad strip on the Grid toolbar and on Perform: tap to switch. It can be MIDI learned. The zone pads for solo and mute come later.

**Timing of a switch (D-11).** This applies to a zone map switch and to a change of pattern style.
- If the press is **within 50 ms of a beat**, the switch happens at once, counted as on that beat.
- Otherwise it is **queued for the next beat**. The pad blinks while it waits.
- The window is a setting (50 ms by default). Q-M5b asks about the details.

## 5. Phases

| Phase | Goal | Size | Needs | Main files |
|---|---|---|---|---|
| **Z0** | **Fix-ups** that don't depend on the design: confirm and clean up on group delete (B-5), the double toggle (B-6), the "Cues targeting" count (B-7), the Axis Transform parameters (B-8), "All" listed twice (B-9), id collisions (B-11), calibration failing closed (B-12) | ~1.5 d | – | `events/handlers/zone_groups.clj`, `views/tabs/zones.clj`, `subs.clj`, `events/handlers/projector.clj`, `views/components/visual_editors/custom_param_renderers.clj`, `backend/multi_engine.clj` |
| **Z1** | **Safety + test pattern:** a rectangle scan limit and blocked rectangles, clipping with blanking at the edges, fail closed, a test pattern and Identify, the safety editor | ~4–5 d | – | **NEW** `animation/safety.clj`, **NEW** `services/test_pattern.clj`, `backend/multi_engine.clj`, `views/tabs/projectors.clj` |
| **Z2** | **Rig strip + automatic groups + implicit All:** the order, the middle policy, the split width, the auto-group matrix, custom rows, thumbnails and the load meter; the Zone Groups tab merged into it; migration | ~4 d | Z0 | **NEW** `routing/rig.clj` (groups part), `state/domains.clj`, `views/tabs/projectors.clj` (it becomes Rig), `views/tabs/zones.clj` (removed), `views/root.clj`, `state/persistent.clj` |
| **Z3** | **Engine: resolver, de-duplication, regions, fit guard.** One laser now splits Left and Right. A projector in overlapping groups draws each cue once | ~3–4 d | Z1, Z2 | `routing/rig.clj`, `backend/multi_engine.clj`, **NEW** `animation/fit_guard.clj`, `services/frame_service.clj` (the preview reads the resolver) |
| **Z4** | **Zones A–D, zone maps and one target control:** the cascade with explanations, grid badges, the map pads, conversion from the old routing methods | ~5 d | Z3 | `routing/zone_effects.clj`, `services/frame_service.clj`, `views/dialogs/cue_chain_editor.clj`, `views/components/visual_editors/timeline/*`, `views/tabs/grid*`, `events/handlers/cue_chain.clj`, `timeline/model.clj` |
| **Z5** | **Zone patterns on cues and effect cells:** the presets, rate, Step on, source; one remap engine shared with Phase 8 | ~4 d | Z4 (and Phase 8a if it has landed) | `routing/zone_remap.clj` (Phase 8's, or **NEW** here), `animation/effects/zone.clj`, the cue editors |
| **Z6** | **Step grid:** drum-machine rows (zones A–D) by steps (8/16/32), on or off per cell, with a rate or Step on | ~3 d | Z5 | the pattern editor |
| later | Everything in `08-rig-setup-ux-future.md` (stage map, templates, Identify walk, tags, several zones per projector, venue files…), polygon safety, audience limits (Q5), weights and fades, per-output render sources, Spread | – | – | – |

That's about 25 days. Z0 can happen whenever. Z1 and Z2 can run in parallel. **Z3 is the point where the one-laser split works.** After Z4, cues no longer depend on the rig.

## 6. Migration (N-23)

| Old data | Converted to |
|---|---|
| A projector without `:all` in `:zone-groups` | `:exclude-from-all? true` |
| The built-in groups `:left`, `:right`, `:center` with **no members** | Removed. References point to `:auto/left` and so on |
| The same groups **with members** | Kept as custom groups, renamed "Left (manual)" and so on. References are unchanged, so the routing is identical |
| `:graphics`, `:crowd`, and custom groups | Unchanged (custom) |
| The item's `:zone-group-id`, the track's `:zone-group-id`, the cue's `:destination-zone` | The target at that level (the field names may stay as they are) |
| An enabled Zone Selector with `:target-zone` | The item's target (only if the item has none), and the effect is removed |
| Phase 8's `:zones` lists | Accept zone ids and `:auto/*` ids. Old group ids still work |

- Conversion runs on load in `state/persistent.clj`, and it is idempotent.
- No projector order is saved in old files, so the order starts from the IP.
- A test fixture project for each case is loaded and checked so it routes exactly as before.

## 7. Fitting with vibe Phase 8 (zone chase, swap, shift)

- Phase 8 moves pictures between zone keys in `{zone-id → frame}` on the effects grid (a pure `routing/zone_remap.clj`). That is the same stage this plan's patterns need, so **we share it and don't build a second one.**
  - Z5's effect-cell patterns are Phase 8's effects, plus **Zone alternate**.
  - The cue-level pattern is the same remap applied inside one cue, before the grid's.
- **Phase 8's defaults** `[:left :center :right]` become `[:auto/left :auto/center :auto/right]` (or zones A–C), so they work on every rig without anyone filling in groups.
- **Phase 8's invariant 1** ("moved, never copied", because a projector in two zones would draw it twice) can be relaxed once Z3's de-duplication lands. That lifts Phase 8's "copy to several zones" item out of scope.
- **Phase 8's audience rule** stays, and is extended: a zone map that binds a zone to an audience group counts as audience-capable.
- **Order of work:** if Phase 8a lands first, Z4 and Z5 extend it. If Z4 lands first, Phase 8a should take zone ids from the start. Either order works.

## 8. Risks

| # | Risk | Mitigation |
|---|---|---|
| R-1 | Two images on one laser flicker or look dim | Fit guard (D-1), at most 2 regions, a load meter, tested on real projectors before Z3 is called done |
| R-2 | Changing `:left`, `:right` and `:center` meaning breaks old shows | Manual groups are kept and renamed. The automatic ones get new `:auto/*` ids (§6) |
| R-3 | Regions computed in content space don't match the safe area after the corner pin | v1 uses a bounding box. Safety still clips. A later version inverts the geometry |
| R-4 | Safety stage bugs | Fail closed, pure functions, property tests (nothing lit leaves the limit, nothing lit enters a blocked rectangle) |
| R-5 | With middle = `:both`, a cue that lights Left and Right at the same time with different content makes the middle laser draw two pictures | The fit guard and the load meter show it. The policy is one click to change. Q-M11 decides what is drawn |
| R-6 | Concept count (groups, zones, maps, patterns) | Defaults hide it: a cue with no setup is All; Left / Right is the active map; the map pads only show when a cue uses zones |
| R-7 | Conflicts with work landing in parallel (vibe Phase 2–8, UX phases) | Re-check against HEAD per phase; keep §7 in mind |
| R-8 | A projector's id is built from its IP and service id (`events/handlers/projector.clj:66-73`). After a DHCP change the laser comes back as a new projector and loses its place in the order, its safety zone and its groups | Z2 stores the order and membership by projector id, as everything else is today. Stable identity (by unit id + service id) is doc 08 R-4; it is the first thing to do after Z3, or it can be pulled into Z2 if venue IP changes happen often (Q-M9) |

## 9. Open questions

The open questions, each with its context, the options and a recommendation, are in **`09-questions.md`**. Answered so far:
- Q-M1 → D-9
- Q-M3 → D-10 (details still open as Q-M3b)
- Q-M5 → D-11 (details still open as Q-M5b)
- Q-M6 → D-12
- Q-M7 → D-13

Doc 08 has its own questions, Q-R1 to Q-R14, about future rig setup.
