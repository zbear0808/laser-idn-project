# Rig setup UX: what comes after the ordered strip

**Written:** 2026-11-19   **Written for commit:** `f7ec338` (`master`)
**Status:** Future / not scheduled. This doc lists improvements to rig setup and arrangement **after** the first version (the ordered strip). Nothing here is part of v1, and nothing here has been picked for a phase yet.
**Builds on:** the v1 rig strip in `07-plan.md` (D-7, §2, §4.1, phases Z1–Z2), C-3, C-4, C-5, C-10 in `04-core-features.md`, split zones in `06-zone-patterns-and-split-zones.md`, §4.1 of `plans/2026-11-18-zones-ux/00-overview.md`.

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`. IDs: **P-*** are pain points, **R-*** are future rig improvements, **Q-R*** are questions. N-*, T-*, C-*, D-* and Q-* refer to the other docs in this folder. The R-* numbers here are **not** the same as the risk numbers R-1…R-7 in `07-plan.md` §8.

## 1. Baseline: v1, the ordered strip (`07-plan.md`)

- Discovery finds projectors and shows each one with its IP and name. The first order comes from the IP, then the name. The user sorts them left to right (drag, or select and ◀ ▶ / Alt+←→), saved as `[:rig :order]`.
- Automatic groups (`:auto/all`, `:auto/left`, `:auto/right`, `:auto/center`, `:auto/odd`, `:auto/even`) are worked out from the order and never saved. All is every enabled projector, unless that projector has `:exclude-from-all?` set.
- Group rows form a matrix lined up under the strip: automatic rows are locked, and custom rows are boxes you click.
- One laser can be split into two overlapping regions, each `[:rig :split-width]` (0.75) of the safe width. The middle laser of an odd count is not split unless you choose to (`[:rig :middle]`).
- Each card has Identify, a thumbnail of its real output and a load meter. The inspector has rectangle safety zones with Copy to… and Mirror to….
- Everything is 1D: there is no height, depth, angle, row or tag editing in the arrangement.
- The brief for this doc also lists one automatic group per projector. `07-plan.md` §2 doesn't list one yet, so that gap should be settled in v1 rather than here.

## 2. What v1 will still get wrong

| ID | Pain point | Why the strip can't fix it | Evidence in the code |
|---|---|---|---|
| P-1 | **Front/back and high/low.** Two lasers at the same x (one on the truss, one on the floor) | The strip has one axis. Whichever goes first lands in "Left", and the other ends up in the wrong half or as the "middle" laser | v1 only stores an order |
| P-2 | **Audience scanners vs truss lasers.** An audience laser at stage left gets put in Left and Odd, so text and beams meant for the truss land on the crowd | Order says nothing about purpose. v1's Exclude from All keeps it out of All, but not out of Left, Odd or a zone map. Tags exist in the model, but there is no way to edit them | `:tags` in `state/domains.clj:299`; shown read-only in `views/tabs/zones.clj:41-57`; no editor (B-10) |
| P-3 | **Several zones per projector.** A graphics zone plus an audience zone with stricter safety on one unit | v1 has one temporary split (two overlapping halves), not two permanent outputs with separate safety and tags | Virtual projectors have a domain (`state/domains.clj:303-318`) but no create handler, and engines are only made for real projectors (`backend/multi_engine.clj:212-227`) |
| P-4 | **Safety across many outputs.** v1 copies and mirrors rectangles to a short list of projectors, but it can't copy just one part (only the blocked areas, only the corner pin), can't choose targets by row or tag, and doesn't say whether a copy was ever checked on the real laser | v1's Copy to… / Mirror to… is a one-shot copy of a whole safety zone | Calibration is a per-projector effect chain (`views/tabs/projectors.clj:619-645`); safety is **NEW** in Z1 |
| P-5 | **Which physical laser is which?** Two cards called `192.168.1.41` and `192.168.1.42` don't say which one is stage left | Discovery only gives an IP, a 16-byte unit ID and a 20-byte host name. Calibration mode doesn't output anything yet | `idn/hello.clj:359-384`; default names `events/handlers/projector.clj:130-132`; the calibration toggle only sets a flag (`events/handlers/projector.clj:369-375`, B-2) |
| P-6 | **Venue changes.** A different DHCP lease gives the same laser a new IP, and it comes back as a *new*, disabled projector with no calibration, safety or position | The projector ID is built from the IP and service ID. The unit ID is stored but never used to match | `make-projector-id` `events/handlers/projector.clj:66-73`; `:unit-id` stored at `:139`; new ones disabled at `:140` |
| P-7 | **Large rigs (8–20+ projectors).** One long strip of cards, one click per change | No multi-select, no bulk edit, no filter. "Enable All" works only per IP address | `views/tabs/projectors.clj:226-277, 345-373`; `events/handlers/projector.clj:199-208` |
| P-8 | **Multi-service devices.** A dual-head DAC shows as two cards with the same IP, often named "Output 1" and "Output 2" | The strip treats them as unrelated. Nothing says which head is which physical aperture | One projector per service: `events/handlers/projector.clj:147-156`; card label `views/tabs/projectors.clj:104-127` |
| P-9 | **Offline or missing projectors.** A saved projector that doesn't answer the scan | The strip either keeps a gap or closes up. Either way the automatic groups change, or silently don't | Discovery only lists responders (`idn/hello.clj:604-636`); status dots only come from streaming (`views/tabs/projectors.clj:321-327`) |
| P-10 | **Rented or borrowed rigs.** Different units every time, unknown names, no calibration | The show has no idea what rig it expects, so there is nothing to map onto | The rig lives inside the project's `hardware.edn` (`state/persistent.clj:53-56`); New Project resets it (`:176-185`) |
| P-11 | **Shared or unusual networks.** Another operator's lasers on the same festival network; several subnets; static IPs | Every device that answers is added to the list automatically, and can't be deleted. The broadcast address has no UI | `events/handlers/projector.clj:12, 159-186`; `:broadcast-address` default `state/domains.clj:336-337`; scan on startup `app.clj:175` |
| P-12 | **Spread and real mirroring need angles.** Order is enough for chase and odd/even, not for one wide canvas | No coverage angles (T-11, Q-d) | — |
| P-13 | **Timing differences.** A chase on the beat looks ragged when projectors differ in latency | No per-output offset (T-12) | — |

## 3. Future improvements

Each item says what it is, what the user sees and clicks, what it needs from the model, its size (S ≈ 1–2 d, M ≈ 3–5 d, L ≈ 1–2 weeks, XL = more), and the needs, conflicts and pain points it serves.

### Knowing which laser is which

**R-1. Better Identify: number on the laser, and "Check order"**
- **What:** v1's Identify flashes one laser's test pattern (Z1). This adds two things: the laser draws its strip number large ("3") so you can read it from the floor, and a **Check order** sweep.
- **UX:** click Identify on a card, or hold it to keep the pattern on. "Check order" flashes the lasers one by one from left to right at a steady pace, with the matching card glowing, so a wrong order is obvious at a glance.
- **Model:** a number glyph in the test pattern (`services/test_pattern.clj`, NEW in Z1), still clipped by safety. Nothing saved.
- **Size:** S on top of Z1.  **Serves:** N-5, S-8, P-5.

**R-2. Identify walk**
- **What:** the app flashes each unplaced laser in turn, and you say where it is. Placing a 12-laser rig becomes 12 clicks.
- **UX:** "Walk the rig" opens a strip of empty slots. The current laser flashes; you click its slot, press its number (1–9, 0 for 10), or press S to skip. The next one flashes at once. Esc stops, keeping what was placed.
- **Model:** nothing new; it writes the v1 order (and the row, once R-6 exists).
- **Size:** S–M.  **Serves:** N-5, P-5, P-7, P-10.

**R-3. Guess the order from names, host names and IPs**
- **What:** v1 starts the order from the IP. This reads the names first: `L`, `Left`, `SL`, `DSL`, `R`, `SR`, `C`, `Center`, `1`…`n`, `L1`/`L2`, and only then falls back to the last octet of the IP.
- **UX:** a banner "Order guessed from names. Accept · Undo". Guessed cards carry a small "?" badge until accepted or moved. Names that suggest an audience laser ("aud", "crowd") are **never** acted on, only shown as a hint to add the tag.
- **Model:** a pure `guess-order [projectors]` function; a `:placement :guessed | :set` flag per output.
- **Size:** S.  **Serves:** N-5, P-5.

**R-4. Stable identity: match projectors by unit ID, not IP**
- **What:** a projector keeps its order, groups, geometry and safety when its IP changes.
- **UX:** after a scan, a note: "SR Beam moved from .41 to .57 (same unit). Updated." If two candidates match, the user picks one. Nothing is merged silently when the match is unclear.
- **Model:** identity = `[unit-id service-id]`, with the host as a changeable address. Fall back to IP + service ID when the unit ID is blank or all zeros. Changes `make-projector-id` (`events/handlers/projector.clj:66-73`) and the scan merge (`:159-186`), plus a one-time migration of saved IDs.
- **Size:** M.  **Serves:** N-4, N-21, S-6, S-7, P-6.

**R-5. Group the heads of one device**
- **What:** the heads of a multi-service DAC are drawn as one bracketed unit on the strip ("FB4-Rack · head 1 / head 2"), so you can't mistake them for separate boxes.
- **UX:** a bracket under the cards with the device name. Identify on the bracket flashes the heads one after the other. Dragging the bracket moves both; dragging one card splits them.
- **Model:** derived from host + unit ID; nothing new stored.
- **Size:** S.  **Serves:** P-8.

### Real stage shapes

**R-6. Rows (tiers): truss, floor, audience**
- **What:** the strip becomes a few stacked strips. Each row has its own left-to-right order.
- **UX:** rows labelled "Truss", "Floor", "Audience" (rename, add, remove). Drag a card between rows. Automatic groups appear per row ("Truss", "Floor", "Truss Left"…) next to the rig-wide ones. The Audience row adds the audience tag to everything in it, and shows a safety badge on each card.
- **Model:** `:row` per output plus an ordered row list. The v1 order becomes order within a row. Rig-wide Left/Right/Odd/Even are computed from x across rows, or from one chosen "main" row (Q-R4).
- **Size:** M.  **Serves:** N-3, N-10, T-3, S-3, P-1, P-2.

**R-7. Tags, with a real editor**
- **What:** Graphics, Audience, Beam and user tags, edited on the card.
- **UX:** tag chips on each card; click "+ tag" to add. Tag groups ("Graphics", "Audience") are made automatically. Adding Audience opens a check: "This output can scan the audience. Its safety zone must be checked before it streams", and offers to set v1's **Exclude from All** at the same time.
- **Model:** `:tags` already exists (`state/domains.clj:299`); add handlers and per-tag automatic groups (`:auto/tag-graphics`…) in the resolver (`routing/rig.clj`, NEW in Z2). The vibe audience rules already read `:crowd-scanning` (B-10).
- **Size:** S.  **Serves:** N-3, N-10, T-3, S-3, P-2.

**R-8. Templates: Line, Arc, Sides + Center, Stacked, Surround**
- **What:** pick a shape and the strip (or map, R-9) shows labelled slots to drop cards into.
- **UX:** a template picker above the strip with small pictures. Choosing one with *n* slots for *n* projectors fills them in the current order; dragging a card swaps slots. The template also sets rows (Stacked), sides (Sides + Center) and rough angles (Arc), so later features have something to work with.
- **Model:** a template is data: slots with row, x, optional height and aim. Applying one writes the per-output fields; nothing keeps a live link to the template.
- **Size:** M (S for Line and Sides + Center only).  **Serves:** N-5, T-11, P-1.

**R-9. 2D stage map (front view, optional top view)**
- **What:** cards on a canvas instead of a strip. Height is free, not just a row.
- **UX:** front view: drag cards anywhere; a faint truss line and a floor line to snap to. The strip order is read from x, so v1 groups keep working. A **High / Low** split appears when cards sit at clearly different heights. An optional **top view** (tab) for depth: front/back, a surround ring, lasers behind the audience. The strip stays as a compact view of the same data.
- **Model:** `:position {:x :y :z}`, normalised to the stage. Order is derived (sort by x, with a stable tie-break), never stored twice.
- **Size:** L.  **Serves:** N-5, T-11, P-1, P-12.

**R-10. Coverage angles, for Spread and true mirroring**
- **What:** each output's horizontal (and optionally vertical) coverage, so Spread can slice one wide canvas by angle and show the overlaps.
- **UX:** in the map, each card shows a fan. Drag its edges to widen it or turn it. Overlaps are shaded. Default: "divide evenly", which needs no input.
- **Model:** `:coverage {:aim-deg :h-deg :v-deg}`. Templates fill rough values.
- **Size:** M (after R-9).  **Serves:** T-11, Q-d, P-12.

### Several zones and safety across outputs

**R-11. Several zones per projector (a UI for virtual projectors)**
- **What:** a projector carries two or more permanent zones, for example "Graphics" (upper half) and "Audience" (lower band, stricter safety). Each zone has its own geometry, safety zone, tags and groups.
- **UX:** "+ Add zone" on a card splits it into sub-cards under one header. Each sub-card can sit in a different row (the audience zone goes to the Audience row). A shared load meter on the header, since both zones share one scan budget.
- **Model:** make the virtual-projector domain real (`state/domains.clj:303-318`): create and delete handlers, engines join a projector's zones into one frame, safety per zone and then for the whole frame. v1's regions are temporary halves chosen by the resolver (`07-plan.md` §2); a permanent zone is a different thing with its own safety. Both share the D-1 limit of 2 regions and the fit guard (Q-R7).
- **Size:** L.  **Serves:** T-6, Q4, S-3, P-3.

**R-12. Copy and mirror, done properly**
- **What:** v1's Copy to… / Mirror to… copies a whole rectangle safety zone to a short list. This makes it pick parts, pick targets by row or tag, cover geometry and colour too, and track whether a copy was checked.
- **UX:** "Copy to…" opens the strip with checkboxes, pre-ticked for outputs in the same row with the same tags. Tick what to copy: scan limit, blocked areas, corner pin, colour, scan rate, fit guard. "Mirror to opposite side" picks the output at the mirrored strip position and flips x. A copied safety zone shows "copied, not checked" on the card until someone has looked at it on the real laser (R-1).
- **Model:** pure transforms on rectangles (later polygons) and corner pins. A `:safety-status :checked | :copied | :none` per output.
- **Size:** M.  **Serves:** N-6, N-7, P-4.

### Groups and live state

**R-13. Group matrix that scales**
- **What:** v1 already has the matrix under the strip (locked automatic rows, clickable custom rows). It replaces today's two places for assignment (`views/tabs/zones.clj:135-186`, `views/tabs/projectors.clj:472-501`). This makes it work with 15 groups and 20 outputs.
- **UX:** drag across cells to paint or clear; collapse the automatic rows; filter rows by name; sticky column headers when the strip scrolls; row header with colour and count; with rows (R-6), one column block per row. Hovering a column highlights its card.
- **Model:** none new.
- **Size:** S–M.  **Serves:** N-2, N-3, P-7.

**R-14. Thumbnails and load meters for big rigs**
- **What:** v1 has a thumbnail and a load meter on each card (C-10, `07-plan.md` §4.1). With 12–20 cards that is too small to read and costs CPU. This adds a large view and keeps the cost down.
- **UX:** click a thumbnail to open a big preview of that output, with the safety zone drawn over it. A "Wall" view shows every output's thumbnail in a grid. In a compact strip, the meter shrinks to a coloured dot (green, orange near the limit, red when the fit guard is acting), with the "−30% pts" note on hover.
- **Model:** a throttled per-output frame tap (about 10 fps), not drawn for cards that are off screen. The fps and "limited by scan rate" are already reported (`views/tabs/projectors.clj:283-294`).
- **Size:** S–M.  **Serves:** N-17, N-25, S-8, P-7.

**R-15. Latency offset per output**
- **What:** shift an output a few milliseconds earlier or later, so chases line up.
- **UX:** an "Offset (ms)" field in the inspector. A "Line up" helper flashes all lasers on the beat; the user nudges each one until they flash together. The IDN ping round trip (`idn/hello.clj:494-526`) is shown as a hint, not applied.
- **Model:** `:latency-ms` per output, applied when the frame is picked for that output. Only if T-12 turns out to be visible.
- **Size:** S–M.  **Serves:** T-12, P-13.

### Big rigs and speed

**R-16. Bulk operations**
- **What:** do one thing to many outputs.
- **UX:** shift-click, ctrl-click and rubber-band select on the strip, plus "Select row / tag / device / missing". A bulk bar appears: enable, disable, tag, add to group, set scan rate, copy safety (R-12), rename with a pattern ("Truss {n}"), move as a block.
- **Model:** a selection set in UI state; each action is one undo step.
- **Size:** M.  **Serves:** N-5, P-7.

**R-17. Keyboard-only flow**
- **What:** set up a rig without the mouse.
- **UX:** arrows move the focus between cards; Shift + arrows move the focused card; Up/Down change row; `I` identifies; Space enables or disables; `T` opens tags; `G` opens groups; Enter opens the inspector; number keys jump to a slot (and answer the Identify walk, R-2). A cheat-sheet on `?`.
- **Model:** none.
- **Size:** S–M.  **Serves:** N-5, P-7. Also makes the strip easy to drive in UI tests.

**R-18. Discovery hygiene**
- **What:** control what discovery adds and from where.
- **UX:** a network field (broadcast address or subnet, with a picker for the machine's interfaces); "Rescan" per card; "Ignore this device" for other people's lasers on a shared network (ignored ones are folded away); "Delete" for projectors that are gone for good.
- **Model:** an ignore list by unit ID; delete handlers (B-10). The broadcast address already has a state key (`state/domains.clj:336-337`).
- **Size:** S.  **Serves:** S-7, P-11.

### Venues, touring and rented rigs

**R-19. Missing, offline and extra projectors**
- **What:** a clear rule for a rig that doesn't match what was saved.
- **UX:** a missing projector stays in its place as a dashed "ghost" card ("SL Beam: not found"). New, unplaced projectors wait in a tray at the end of the strip ("2 new: place them"). A per-rig setting chooses whether ghosts keep their slot (groups stay the same, content to them goes nowhere) or the strip closes up (groups are recomputed). A projector that drops out during the show turns red but keeps its slot, so nothing else moves.
- **Model:** `:presence :found | :missing | :new` (runtime), plus the keep-or-close setting.
- **Size:** M.  **Serves:** N-4, N-13, N-22, S-7, T-1, P-9.

**R-20. Replace a projector (hot swap)**
- **What:** a spare takes over a dead unit's place.
- **UX:** right-click a ghost or red card → "Replace with…" → pick from the tray. The new unit takes the old one's slot, row, tags, groups and geometry. Its safety is marked "copied, not checked" (R-12).
- **Model:** a move of the output's fields to a new identity (R-4).
- **Size:** S (after R-4 and R-19).  **Serves:** S-7, P-6, P-9.

**R-21. Rig file and venue profiles, separate from the show**
- **What:** save and open the rig on its own: "Club X", "Festival main stage", "Home".
- **UX:** a Rig menu: Save rig as…, Open rig…, Recent venues. The status bar shows the current rig name. New Project keeps the rig. A venue can carry notes and a reference photo of the stage.
- **Model:** the split leaned towards in T-8: physical things (identity, address, position, safety, calibration, latency) in the rig file; creative things (custom groups, targets) in the show. Waits on Q8.
- **Size:** L.  **Serves:** N-21, S-6, T-8, P-6, P-10.

**R-22. Loading a show on a different rig: the mapping report**
- **What:** when a show opens on a rig it wasn't built on, say what changed instead of failing quietly.
- **UX:** a report after opening: "Left: 2 → 3 outputs. Graphics: no output has this tag (14 cues). Pinned to 'SR Beam': not on this rig (3 cues)." Each line has a fix: pick an output, tag one, or re-target to All. Safety and calibration on this rig are never touched by opening a show.
- **Model:** the show stores a small "authored on" summary (rows, tags, group sizes, pinned output IDs). A pure diff against the current rig.
- **Size:** M.  **Serves:** N-13, N-21, N-22, S-4, S-6, P-10.

**R-23. Test rigs for the preview rig simulator**
- **What:** save made-up rigs ("Club 2 sides", "Festival 6 + 2 audience") to preview cues on, as `06-zone-patterns-and-split-zones.md` suggests (Q-l).
- **UX:** in the preview's "Preview on" dropdown: This rig, 1 / 2 / 3 / 4 / 6 / 8 lasers, then saved test rigs, then "Save current rig as test rig…". A test rig can also be built from a template (R-8). Test rigs never stream.
- **Model:** test rigs are rig data without addresses (order, middle policy, split width, rows, tags), stored with the app settings, not the show. The preview calls the same resolver as the engines with the invented rig (`07-plan.md` §3).
- **Size:** S–M.  **Serves:** N-12, N-26, S-4.

**R-24. Pre-show rig check**
- **What:** one button that lists what is wrong before doors open.
- **UX:** "Check rig" gives a list: unplaced outputs, outputs with no safety zone, audience outputs whose safety isn't checked, copied safety not checked, missing projectors, two cards with the same IP and service, outputs near their scan limit. Each line has a "Go there" link.
- **Model:** pure checks over the rig.
- **Size:** S–M.  **Serves:** N-7, N-9, N-17, S-8.

**R-25. Lock the rig during a show**
- **What:** no accidental drag changes the groups mid-show.
- **UX:** a lock toggle on the strip. Locked: cards can be identified and inspected, not moved, retagged or regrouped. Unlocking while streaming asks first.
- **Model:** a flag; matches T-1's "freeze automatic groups" and T-14's confirm for live safety edits.
- **Size:** S.  **Serves:** T-1, T-14, S-5.

**R-26. Projector profiles (fixture library)**
- **What:** known models carry defaults: scan rate, colour depth, typical coverage angle, number of heads.
- **UX:** a "Model" picker on the card; choosing one fills those fields. Unknown models keep today's defaults.
- **Model:** a small built-in EDN table plus user entries.
- **Size:** S.  **Serves:** N-5, P-10, R-10.

**R-27. Camera-assisted detection — far future**
- **What:** a webcam or phone camera watches the stage while the app flashes each laser in turn, and fills in the map positions (and maybe coverage) on its own.
- **UX:** "Detect with camera": choose a camera, point it at the stage, press Start. Results arrive as guesses (R-3's "?" badge) for the user to accept.
- **Model:** positions as in R-9; a vision step outside the core.
- **Size:** XL.  **Serves:** N-5, P-5, P-7. Risky: haze, ambient light, camera sensors and laser beams, and a lot of new code. Listed so the model for R-9 doesn't rule it out, not as a plan.

### All items at a glance

| ID | Item | Size | Main needs / conflicts | Depends on |
|---|---|---|---|---|
| R-1 | Number on the laser + "Check order" | S | N-5, S-8 | Z1 (Identify) |
| R-2 | Identify walk | S–M | N-5 | R-1 |
| R-3 | Guess order from names / IPs | S | N-5 | Z2 |
| R-4 | Stable identity by unit ID | M | N-21, S-6 | — |
| R-5 | Group heads of one device | S | P-8 | — |
| R-6 | Rows / tiers | M | N-3, N-10, T-3 | Z2 |
| R-7 | Tags editor + tag groups | S | N-3, N-10 | Z2 |
| R-8 | Templates | M | N-5, T-11 | R-6 |
| R-9 | 2D stage map (front + top) | L | N-5, T-11 | R-6 |
| R-10 | Coverage angles | M | T-11, Q-d | R-9 |
| R-11 | Several zones per projector | L | T-6, Q4 | Z1, Z3 |
| R-12 | Copy / mirror: parts, targets, "checked" status | M | N-6, N-7 | Z1 |
| R-13 | Group matrix that scales | S–M | N-2, N-3 | Z2 |
| R-14 | Big previews + compact meters | S–M | N-17, N-25 | Z2 |
| R-15 | Latency offset | S–M | T-12 | R-1 |
| R-16 | Bulk operations | M | N-5 | — |
| R-17 | Keyboard-only flow | S–M | N-5 | — |
| R-18 | Discovery hygiene | S | S-7 | — |
| R-19 | Missing / offline / extra | M | N-4, N-13, N-22 | R-4 |
| R-20 | Replace a projector | S | S-7 | R-4, R-19 |
| R-21 | Rig file + venue profiles | L | N-21, T-8 | R-4, Q8 |
| R-22 | Mapping report on load | M | N-13, N-22 | R-6, R-7 |
| R-23 | Test rigs for the simulator | S–M | N-12, N-26 | Z3 resolver, preview simulator |
| R-24 | Pre-show rig check | S–M | N-7, S-8 | R-12 |
| R-25 | Lock the rig | S | T-1, T-14 | — |
| R-26 | Projector profiles | S | N-5 | — |
| R-27 | Camera detection (far future) | XL | N-5 | R-9 |

## 4. Suggested order

All phases assume `07-plan.md` Z1–Z3 have landed (safety, Identify, the strip, the resolver).

| Phase | Items | Why this order |
|---|---|---|
| F1: know which laser is which | R-1, R-4, R-18, R-7, R-3, R-5 | All small to medium. Identity and Identify are what every later step relies on, and R-4 removes the worst surprise (a laser "forgets" its setup when its IP changes). Tags separate audience lasers from the truss before any fancy layout |
| F2: real stages, real safety | R-6, R-12, R-19, R-25, R-24 | Rows fix front/back and audience-vs-truss (P-1, P-2), the most common gap after v1. A "checked / copied" safety status is what makes v1's copy safe to rely on. Missing-projector rules and the lock keep the automatic groups predictable (T-1) |
| F3: big and touring rigs | R-16, R-17, R-13, R-14, R-20, R-2 | Matters once rigs reach 8+ outputs. The Identify walk only if placement is still slow (as `04-core-features.md` says) |
| F4: rig vs show | R-21, R-22, R-26 | Needs Q8 answered. The mapping report is useful even before a separate rig file exists (open a show from another machine) |
| F5: geometry | R-8, R-9, R-10, R-11, R-15, R-23 | Only when Spread, surround layouts or several zones per projector are actually wanted (Q-d, Q4, T-12). R-23 goes with the zone-patterns work |
| Far future | R-27 | Only if everything else is done and placement is still the bottleneck |

**Top five, if only five get built:** R-4 (stable identity), R-1 (number on the laser + Check order), R-6 (rows, with an Audience row), R-12 (copy and mirror with a "checked" status), R-19 (missing, offline and extra projectors).

## 5. Risks

- **Wrong guesses send content to the wrong laser.** R-3, R-4 and R-27 all guess. A guess must look like a guess (badge) until accepted, and audience status is never inferred.
- **Copied safety feels checked when it isn't.** R-12 and R-20 must mark copied zones as unchecked, and R-24 must list them.
- **Moving a card changes the show (T-1).** Rows, maps and templates all change automatic groups. Lock (R-25), the explain-why target chip (C-7) and the mapping report (R-22) are the safety net.
- **Two sources of truth.** If the map (R-9) and the strip both store an order, they will drift. The map stores positions, and the strip order is computed from them.
- **Unit IDs aren't always useful.** Some devices may report a blank or shared unit ID, or the same host name on every unit. R-4 needs a fallback and a manual "this is the same unit" action.
- **Thumbnails cost CPU.** 20 live canvases in JavaFX can be heavy. Throttle them and stop drawing ones that are off screen.
- **Identify during a show** takes that laser off the show for a moment. It should ask first while streaming.
- **Scope creep.** Each item here is small alone, but together they are a large rig editor. The phase table is there to stop at the point where setup is "fast enough".

## 6. Questions for the user

1. **Q-R1.** Do your projectors keep the same IP between venues (static), or get one from DHCP? This decides how urgent R-4 is.
2. **Q-R2.** Which DACs and projectors do you use, and do they report useful host names and unit IDs? Are any multi-head?
3. **Q-R3.** How many rows does a real rig of yours have (truss, floor, audience, balcony)? Are rows enough, or do you need a free 2D map?
4. **Q-R4.** With rows, should rig-wide Left/Right/Odd/Even use every row, or only one "main" row (the truss)?
5. **Q-R5.** Should tagging an output as Audience turn on v1's Exclude from All automatically? It is safer, but it is one more thing happening by itself.
6. **Q-R6.** When a projector is missing, should it keep its slot (groups stay the same) or should the strip close up (groups recompute)? Which should be the default?
7. **Q-R7.** Permanent zones (R-11) and v1's temporary regions both use up the D-1 limit of 2 regions per laser. If a projector has a permanent graphics zone, may the resolver still split it for Left/Right, or does a permanent zone turn off splitting on that laser?
8. **Q-R8.** Should a copied safety zone block streaming to an audience output until it has been checked on the real laser, or only warn?
9. **Q-R9.** Where would venue profiles live: next to the app, next to the show, or on a USB stick that travels with the rig? (Depends on Q8.)
10. **Q-R10.** Have you seen chases look ragged across projectors? If not, R-15 can wait.
11. **Q-R11.** Do you share networks with other operators' lasers? Should discovery stop adding every device it sees and only add the ones you click?
12. **Q-R12.** Is a top view (depth, surround) ever needed, or is front view plus rows enough?
13. **Q-R13.** For rented rigs, would it help if a show could say what it expects ("4 truss + 2 audience") and prompt you to fill those slots on arrival?
14. **Q-R14.** Is camera detection (R-27) interesting at all, or should it be dropped from the list?
