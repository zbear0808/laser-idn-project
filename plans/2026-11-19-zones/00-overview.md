# Zones: set up the rig once, let cues adapt to it

**Written:** 2026-11-19   **Written for commit:** `f9fc352` (`master`). Other work is landing in parallel (vibe phases, UX phases), so **re-check every cited path and line at HEAD before starting a phase.**
**Status:** Proposed, with the design decided (§2). **Not to be built yet**: the repo is still changing. The open questions are in §9.
**Supersedes:** `plans/2026-11-19-zones-needs/07-plan.md` (the draft this grew from) and the options in `plans/2026-11-18-zones-ux/00-overview.md`. **Requirements:** `plans/2026-11-19-zones-needs/` (needs, conflicts, scenarios). **Future rig setup:** `plans/2026-11-19-zones-needs/08-rig-setup-ux-future.md`. **Must fit with:** vibe Phase 8 (`plans/2026-11-18-vibe-mode/08-phase8-zone-effects.md`), see §6.

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`. **NEW** marks anything that doesn't exist yet.

## Start here

Setting up several lasers is one of the worst parts of running a show:
- you make groups by hand;
- you tick projectors into them in two places;
- you pick a group inside every cue.

If you skip all that, every laser draws the same picture. There are no safety zones at all.

After this plan:

1. **Put your lasers in order** (left to right) on the Projectors tab, and draw each one's **safety zone**.
2. **The groups appear by themselves**: All, Left, Right, Center, Odd, Even and one per laser. They adapt to the rig:
   - with **one laser**, Left and Right (and Odd and Even) are two overlapping halves of it, each 75% of the safe width;
   - with an **odd number**, the middle laser is **split** between the two sides by default.
3. **Cues never name the venue.** A cue draws into **All**, or into **zones A, B, C…** (as many as it likes), or it is pinned to a group. A **zone map** says what the zones mean tonight:
   - **Left / Right**: A = Left, B = Right, C = Left again…
   - **Odd / Even**
   - **Unison**: everything on every laser.

   You switch maps live, on the beat.
4. **Zone patterns** (alternate, chase, swap, shift, cycling maps) move content between zones on the beat or on a press, from a cue or from an effects-grid cell. A drum-machine **step grid** comes later.
5. **Every laser draws each thing once.** Overlapping groups never double the points. A **fit guard** thins the points when a laser's frame rate gets too low, and the UI says which laser and which cue is too heavy.
6. **Safety always has the last word**, and it fails closed.
7. Later, **the rig gets its own file**, so New Project keeps it and opening a show never overwrites your safety zones.

The precise rules are in **`01-model-and-routing-spec.md`**. Every phase doc implements part of it.

## 1. Docs

| Doc | What |
|---|---|
| `00-overview.md` | This doc: decisions, phases, how they fit with other plans, risks, questions |
| `01-model-and-routing-spec.md` | **The contract**: refs, targets, inheritance, automatic groups, zone maps, routing per tick, de-duplication, regions, fit guard, switch timing, safety, migration, state placement, invariants |
| `02-phase-z0-fixups.md` | Z0: bugs to fix whatever the design |
| `03-phase-z1-safety.md` | Z1: safety zones, test pattern, Identify |
| `04-phase-z2-rig-strip-and-groups.md` | Z2: the ordered rig strip, stable identity, automatic groups, implicit All, the two tabs |
| `05-phase-z3-routing-core.md` | Z3: routing in the snapshot, de-duplication, split lasers, fit guard, per-projector preview, low-fps warnings |
| `06-phase-z4-zones-maps-targets.md` | Z4: zones, zone maps, one target control, inheritance, badges, map pads, migration |
| `07-phase-z5-zone-patterns.md` | Z5: zone patterns on cues and effect cells (shared with vibe Phase 8), Map cycle, pattern pads |
| `08-phase-z6-step-grid.md` | Z6: the drum-machine step grid |
| `09-phase-zr-rig-file.md` | ZR: the rig in its own file (physical setup outside the show) |
| `10-questions.md` | **Every open question in one place**, each with its context, the options and a recommendation, grouped by phase |

The phase docs were written against slightly different commits: 04, 05, 06 and 09 at `90e35c7`; 07 and 08 at `739bd74`; 02 and 03 at `d298801`. Vibe Phases 3 and 4a and UX Phase 3 landed in between, so **re-check each doc's citations at HEAD when its phase starts.**

## 2. Decisions

D-1 to D-13 were made on 2026-11-19 (round 1, recorded in `07-plan.md`). D-14 to D-32 were made the same day (round 2: your answers, plus your "the recommendations are fine" for the rest). The plan's own design calls are marked *plan*, and §9 asks you to confirm them.

| # | Decision | From |
|---|---|---|
| D-1 | At most **2 regions** on one laser at a time. A **fit guard** protects the frame rate | Q-f |
| D-2 | **One laser: Left/Right split by default**, into overlapping regions, each **75% of the safe width** (the middle 50% overlaps), computed inside the safety zone | Q-g |
| D-3 | ~~The middle laser is not split by default~~, replaced by D-17 | Q-h |
| D-4 | Not rigid: to a cue, zones are just zones, with any content, each possibly a different group | Q-i |
| D-5 | Zone patterns live on **cues and effect cells**. Cues are built without thinking about the venue, and **the performer changes the mapping live** | Q-j |
| D-6 | The **step grid** is its own phase (Z6) | Q-k |
| D-7 | Rig setup v1 is an **ordered strip** (the detected IPs and names, sorted left to right). The future work is in needs/08 | Q-l |
| D-8 | Plan only for now. Every phase re-checks at HEAD | – |
| D-9 | ~~The middle laser defaults to both~~, replaced by D-17 | Q-M1 |
| D-10 | **Low fps is allowed.** The laser is **highlighted** when it drops below its minimum, and **heavy cues are warned about** | Q-M3 |
| D-11 | A switch is **within 50 ms of a beat → now; otherwise the next beat** | Q-M5 |
| D-12 | **Built-in maps: Left/Right, Odd/Even, Unison** | Q-M6 |
| D-13 | **The one-laser split applies to Odd/Even too** | Q-M7 |
| D-14 | **The zone map reopens on the last one used.** It is live state saved with the show | Q-M2 |
| D-15 | **Both tabs stay.** Projectors (projector first) and Zone Groups (group first) both edit membership, through the same events | Q-M8 |
| D-16 | **A cue can draw into any number of zones.** One projector can be reached through several groups, so it **de-duplicates** | Q-M4 |
| D-17 | **The middle laser defaults to `:split`** (always split). The choices are split, both, left, right, none | Q-M10 |
| D-18 | **Fit guard: warn, and reduce points automatically** (floor 60%). **Dimming is off unless you turn it on for a laser.** Minimum **20 fps** by default (range 5–60). Cue warnings use the same number | Q-M3b |
| D-19 | Switches go to the **next beat**. Shift or a pad setting makes it the **next bar**. **With no tempo, at once** | Q-M5b |
| D-20 | **All is implicit**: every enabled projector, each with an opt-out | needs C-5 |
| D-21 | *plan*: the automatic groups follow the **enabled** projectors. One that is **offline keeps its place**, so Odd/Even doesn't reshuffle when a laser dies | §9 Q-Z3 |
| D-22 | **Old shows are converted silently on load** and route exactly as before | Q17 |
| D-23 | **Stable identity** (unit id + service id) in Z2, so a new IP doesn't lose a laser's setup | Q-M9 |
| D-24 | *plan*: targets inherit from item → track → group → cue → **grid column** → **show default** → All | needs N-14 |
| D-25 | *plan*: zones **wrap** over a map's lanes (with 2 lanes, A, B, C, D go to Left, Right, Left, Right). Maps can override a zone | §9 Q-Z1 |
| D-26 | *plan*: de-duplication. **Each key once, each picture once.** Both halves of one laser together mean the whole laser | §9 Q-Z2 |
| D-27 | **`:both` middle splits while both sides are lit with different content** | Q-M11 |
| D-28 | **Editing safety on a live laser:** shrinking applies at once, and growing asks first | Q-e |
| D-29 | **If safety fails, that laser goes dark** and says why. The others go on | Q6 |
| D-30 | **The rig gets its own file** (physical setup: identity, order, safety, calibration, fit, group membership). The show keeps targets, maps and group definitions. **Opening a show never overwrites safety or calibration** (ZR) | Q8 |
| D-31 | Audience limits beyond the rectangles (a brightness cap, scan speed) come later | Q5 |
| D-32 | Permanent zones on one laser (for example a fixed graphics half) come later | Q4 |

## 3. The pipeline after Z3

```
show clock, each tick
  for each cue:  render once → {target-key → frame}                 (01 §1: keys are targets)
                 cue zone pattern (Z5)          → re-key             (cuts between zones)
                 effects grid: zone effects (Phase 8 / Z5)  → re-key  (reading order)
                               point effects    → once per distinct frame object (01 §6.1)
  routing = resolver(rig, groups, effective map)                    (01 §5, pure, memoised)
  snapshot {:cues [{:cell :run :zone-frames}] :routing … :projector-ctx …}

each projector engine (multi_engine)
  per cue: paths → de-duplicate → buckets :L | whole | :R → fit regions → join     (01 §6, §7)
  join the cues → fit guard (points, then optional dim) → stats                    (01 §8)
  projector calibration effects (as today)
  SAFETY: limit + blocked rectangles, fail closed                                  (01 §10)
  encode, send
```

There is still **one render per cue per tick** (spec invariant 8). Everything per projector is a cheap transform of frames that were already rendered.

## 4. Phases

| Phase | Goal: what you can do after it | Size | Needs | Doc |
|---|---|---|---|---|
| **Z0** | Fix-ups: deleting a group asks first and re-targets its cues; a real "used by N cues"; Axis Transform can be edited; pasting into a projector chain; removing a projector; "All" listed once; group ids that don't collide. (The double toggle turned out to be already fine, and calibration failures stay as the output plan decided, now covered by Z1) | ~1.5 d | – | 02 |
| **Z1** | **Draw a blocked box and nothing lights inside it.** Safety rectangles with live editing; the test pattern and Identify work | ~4–5 d | – | 03 |
| **Z2** | **Drag your lasers into order and the groups appear.** The rig strip on the Projectors tab, stable identity, automatic groups, implicit All with opt-out, the Zone Groups tab redone group-first and following the order | ~5 d | Z0 | 04 |
| **Z3** | **One laser shows Left and Right halves.** Routing in the snapshot, de-duplication, regions and the middle policy, fit guard, a thumbnail per projector, low-fps badges, cue ⚠ | ~6 d | Z1, Z2 | 05 |
| **Z4** | **A cue drawn into Zone A follows the zone map you switch live.** Zones and maps, one Target control, inheritance with "from …", grid badges, map pads with switch timing, migration | ~6 d | Z3 | 06 |
| **ZR** | **New Project keeps your rig. Opening someone's show never touches your safety zones.** The rig file, and matching a show's groups onto it | ~3–4 d | Z2 (best after Z4) | 09 |
| **Z5** | **Alternate, chase, swap and shift between zones on the beat or on HIT; cycle maps.** Zone patterns on cues and on effect cells, shared with vibe Phase 8; pattern pads | ~4.5 d | Z4 (+ Phase 8a, if it landed) | 07 |
| **Z6** | **Program a zone pattern on a drum-machine grid** | ~3 d | Z5 | 08 |

That's about **33–36 days**. Suggested order: **Z0 → (Z1 ∥ Z2) → Z3 → Z4 → ZR → Z5 → Z6**. ZR can move earlier if the rig being wiped by New Project gets in the way.

**How each phase is worked.** Every phase doc ends with a **commit plan**: small steps, each one a commit. Write the failing tests first, then the code, then run the named tests. Use targeted UI tests first and the full suites last (the `targeted-ui-testing` skill):
- unit tests: `clj -M:mac-arm:test <names>`
- UI tests: `clj -M:test-ui:mac-arm:test-ui-headless -n <ns>`

Each phase ends by writing an "as built" note back into its doc, as the vibe and UX plans do.

## 5. Dependencies, in a picture

```
Z0 ──> Z2 ──┐
            ├──> Z3 ──> Z4 ──> Z5 ──> Z6
Z1 ─────────┘           │
           Z2 ──────────┴──> ZR (any time after Z2, best after Z4)
```

## 6. Fitting with the other plans

- **Vibe Phase 8** (zone chase, swap and shift on the effects grid; planned, not built at `f9fc352`):
  - It re-keys `{zone-id → frame}` in a pure `routing/zone_remap.clj` stage. **Z5 uses the same stage**, extended to refs (zones, automatic groups, custom groups), and adds a cue-level stage and the Map cycle effect.
  - Phase 8's default lists become `[:auto/left :auto/center :auto/right]`.
  - Its invariant 1 ("moved, never copied", because a projector in two zones draws a copy twice) can relax once Z3's "each picture once" rule lands.
  - Its audience rule is kept and extended: a map whose lane is an audience group counts.
  - **Either can land first.** If 8a lands first, Z5 extends it; if Z4 lands first, 8a takes refs from the start.
- **Vibe audience rule** (`vibes/library.clj` `audience-groups`): Z2's tag editor finally sets `:crowd-scanning`, and Z4 gives zones and maps to the audience checks.
- **UX plan** (`plans/2026-11-18-ux-improvements/`): the Projectors tab is being reworked there as well. Before Z2, check what it has landed (for example the status line and "No projectors enabled" link from `92c191a`).

## 7. Risks

| # | Risk | Mitigation |
|---|---|---|
| R-1 | Two images on one laser flicker or look dim | Fit guard, at most 2 regions, load meter. Tried on real projectors before Z3 is called done |
| R-2 | The migration changes how an old show routes | Spec §11 maps every case. A fixture show for each case must route identically (spec invariant 3) |
| R-3 | Regions are worked out in content space, but the scan limit is in output space after the corner pin | Exact with no corner pin. v1 approximates, and safety still clips. Inverting the geometry is later work |
| R-4 | Bugs in the safety stage | Fail closed. Pure functions. Property tests: nothing lit outside the limit, nothing lit inside a blocked rectangle, nothing gets through after an exception |
| R-5 | Too many concepts (groups, zones, maps, patterns) | Defaults hide them: a cue with no setup is All, the map is Left / Right, the map pads only show when a playing cue uses zones |
| R-6 | Wrapped zones overlay several pictures on one side (spec §6.2 j) | Expected. Load meter and fit guard. Custom maps with more lanes |
| R-7 | Other plans landing in parallel collide with this one | Re-check at HEAD for each phase. §6 |
| R-8 | Stable identity mismatches a multi-head device or a missing unit id | Z2 falls back to host + service, and shows which identity it used |
| R-9 | The rig file and the show disagree (ZR) | ZR's matching report. Safety and calibration only ever come from the rig |
| R-10 | The fit guard thins points in a way that changes the look | It only applies below the minimum, with a 60% floor; it can be turned off per laser; its cut is shown |

## 8. What this plan does not do (it's in needs/08, or later)

- the stage map in 2D, templates, rows, the Identify walk;
- several permanent zones on one laser (D-32);
- polygon safety and audience limits (D-31);
- fades between zones (the cuts would need gains per output);
- per-output render sources (an output-index LFO);
- Spread across a wide canvas;
- named venue profiles (ZR keeps a single rig file per machine);
- latency offsets per laser.

## 9. Open questions

**All the open questions, from this overview and from every phase doc, are collected in `10-questions.md`.** Each has its context, the options and a recommendation (★), and the docs are written assuming the ★ answer. The ones below are this overview's own *plan* decisions.

**Q-Z1 · More zones than a map has lanes (D-25).**
A cue may use zones A–F, but Left / Right has 2 lanes.
- **A ★ Wrap:** C → Left, D → Right… Patterns work under every map, and content is never lost.
- **B Extra zones go to All.**
- **C Extra zones go nowhere,** with a warning, so the author has to map them.

**Q-Z2 · A target that names both halves of one laser (D-26).**
For example, an item → `[Zone A, Zone B]` on a one-laser rig.
- **A ★** Draw it once on the whole laser.
- **B** Draw it twice, once in each half.

**Q-Z3 · When a laser goes offline mid-show (D-21).**
- **A ★** It keeps its place, so the others keep their Left/Odd roles and its content just isn't seen.
- **B** The groups re-fold without it, so the remaining lasers cover its role, but Odd/Even reshuffles live.

**Q-Z4 · Where targets can be set by default (D-24).**
- **A ★** Grid column and show default (plus item, track, group and cue).
- **B** Also per vibe.
- **C** Only cue and below.

**Q1 · How many lasers do you usually run, and in what layout?** (Still open.) This sizes the strip (scrolling past about 8 cards) and the test rigs. Options: 1–2 · 3–4 · 5–8 · more than 8, plus the layout (truss line, sides + center, front/back, audience).

**Q-Z5 · What "used by N cues" should count.**
- **A ★** Cues that name the group directly, plus those that reach it through the *current* zone map, shown separately ("3 direct · 9 through Zone A").
- **B** Only direct references.

**Q-Z6 · Zone labels.**
- **A ★** Zones are letters, with an optional label per show ("A · Beams").
- **B** Free names instead of letters.
