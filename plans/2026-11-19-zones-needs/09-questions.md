# Open questions, with context and options

**Written:** 2026-11-19. These are the questions still open on `07-plan.md`. Each one gives the context, the options and a recommendation (marked ★). Answering with an option letter is enough. The questions about future rig setup are in `08-rig-setup-ux-future.md` (Q-R1 to Q-R14).

**Answered 2026-11-19 (round 2)**, recorded as D-14 to D-32 in `plans/2026-11-19-zones/00-overview.md` §2:

| Question | Answer |
|---|---|
| Q-M2 | B: reopen on the last map used |
| Q-M8 | C: keep both tabs; membership can be edited from either |
| Q-M4 | Neither option: there's no limit on zones, and the overlaps de-duplicate |
| Q-M11 | B: split while both sides are lit |
| Q-M10 | A: keep split, and make it the default |
| Q-M3b | B ★ |
| Q-M5b | A ★ / A ★ |
| Q-M9 | B ★ |
| Q4 | A ★ |
| Q5 | A ★ |
| Q6 | A ★ |
| Q8 | C ★ |
| Q17 | A ★ |
| Q-e | B ★ |

Q1 is still open.

Words used below:
- **Group:** a set of lasers. *Automatic* groups (All, Left, Right, Center, Odd, Even, one per laser) come from the left-to-right order. *Custom* groups (Graphics, Crowd…) are the ones you make.
- **Zone (A, B…):** an abstract place a cue draws into. A cue says "this track goes to Zone A", never "to Laser 3".
- **Zone map:** the live setting that says what the zones mean right now. Under **Left/Right**, A = Left and B = Right. Under **Odd/Even**, A = Odd and B = Even. Under **Unison**, every zone = All.

## Part 1: questions from your last answers that need more detail

### Q-M2: Which zone map is active when a show opens?
**Context.** The zone map is a performer setting, like the BPM. You flip it during the show (Left/Right → Odd/Even → back). The question is what happens when you **close and reopen the show**, or restart the app. Does it come back on whatever map you last used, or on a known starting map?
- Remembering the last one is convenient.
- But you could open the show tomorrow, find everything alternating odd/even, and not know why.

| Option | What it means |
|---|---|
| **A** | Always start on Left/Right (the built-in default). Live switches are never saved |
| **B** | Remember the last map used and reopen on it |
| **C ★** | Each show has a **start map** setting (Left/Right by default) that you choose on purpose. Live switches don't change it, so the show always opens the same way |

### Q-M4: How many zones can a cue draw into?
**Context.** This isn't about the maps; you've already picked Left/Right and Odd/Even as the main ones. It's about the **letters a cue can use** when you design it.
- With two zones (A and B), a cue can say "this circle goes to A, this wave goes to B". That covers every two-sided look: left vs right, odd vs even.
- A third zone **C** would be the middle (Center), for looks like "sides do X, middle does Y".
- Zones **C and D** would allow four-part looks, such as a 4-step chase with different content on each step.
- More letters give more freedom, but each one is another choice in every target menu. With the 2-regions-per-laser limit, a 4-zone look on 1 or 2 lasers has to fold, which means some zones share a laser.

| Option | Letters | Map rows |
|---|---|---|
| **A** | A, B | Left/Right: A = Left, B = Right. Odd/Even: A = Odd, B = Even |
| **B ★** | A, B, plus **C = Center** | As in A, and C = Center in every map |
| **C** | A, B, C, D | As in B, and D = All (or chosen per map) |
| **D** | Start with B, and keep D in the model for later | – |

Recommendation: **B**, keeping D in the data model so it can be added later without converting anything.

### Q-M8: What happens to the Zone Groups tab?
**Context.** Zone groups **do stay**. Custom groups like Graphics and Crowd, and the automatic ones, are still the thing a target points at. The question is only **where you edit them**.
- **Today** there are two places. The Zone Groups tab has a list, an assignment checkbox list and a details panel. The Projectors tab has the zone chips. Both edit the same thing.
- **In the plan**, the Rig screen shows the lasers in a left-to-right strip. Under it is one row per group, with a box under each laser, so you tick membership against the layout.
- That leaves the rest: creating, renaming, recolouring and deleting groups, seeing "used by 12 cues", and editing zone maps. That needs a home.

| Option | What it means |
|---|---|
| **A ★** | **Merge.** The Zone Groups tab is removed. The Rig screen gets a **Groups & maps** panel under the strip: rows for membership, a click on a row's name for rename, colour, delete and usage, and a small zone-map table. One place for everything |
| **B** | **Split by job.** Membership is only ticked on the Rig strip. A slimmed-down **Groups** tab keeps the list, rename, colour, delete, usage and the zone-map editor |
| **C** | Keep both tabs as they are, with membership editable in both (two places, as today) |

## Part 2: follow-ups to the decisions you made

### Q-M11: The middle laser is in "both" sides. What does it draw when the two sides show *different* things at the same time?
**Context.** You picked **both** as the default for the middle laser of an odd count, so it's in Left and in Right.
- For **alternating** patterns that's easy: only one side is lit at a time, so the middle laser just shows whatever is lit. It follows along.
- But some cues draw into A and B **at the same moment** with **different** content (circle on the left, wave on the right). Then the middle laser gets both pictures.

| Option | What the middle laser draws |
|---|---|
| **A** | **Overlay:** both pictures, full size, on top of each other. Twice the points, so the fps drops and the fit guard steps in |
| **B ★** | **Split while both are lit:** when both sides are lit with different content, it uses its left region (75%) for A and its right region for B, like a single laser does. When only one is lit, it goes back to the full frame |
| **C** | **One side wins:** it shows A only (or a chosen side) while both are lit |
| **D** | **Center first:** it shows Zone C's content if the cue has any, otherwise falls back to B or C |

### Q-M10: Should "split" stay as a fixed choice for the middle laser?
**Context.** You listed left, right, both and neither. The earlier plan also had **split**: the middle laser is *always* divided, with its left region in Left and its right region in Right. If Q-M11 is B, that already splits whenever it's needed, so a fixed split mostly matters for looks that are deliberately symmetric.

| Option | |
|---|---|
| **A ★** | Keep it as a fifth choice (both · left · right · none · split) |
| **B** | Drop it. Four choices, and splitting only happens automatically (Q-M11 B) |

### Q-M3b: Low frame rates, what is the default?
**Context.** You said low minimums should be allowed, with a highlight when the fps drops too low and warnings on poorly optimised cues. Both are now in the plan (D-10). Still open:
- whether the app should **change the picture automatically** (fewer points, then dimming) or only **warn**;
- the default threshold.

| Option | What happens below the threshold |
|---|---|
| **A** | **Warn only.** An amber highlight on the laser and the toolbar, a ⚠ on the cue. The picture isn't touched |
| **B ★** | **Warn + reduce points automatically** (down to a floor of 60%). Dimming is off unless you turn it on per laser |
| **C** | **Warn + reduce points + dim** (the earlier plan: point floor 60%, brightness floor 50%) |

The default threshold: **20 fps** ★, 25 fps, or 30 fps? The range is 5–60 per laser. Should the cue ⚠ use the same number, or warn a bit earlier, for example 1.5× the threshold? ★ same number.

### Q-M5b: Switch timing, the details
**Context.** Your rule: within 50 ms of a beat, switch now; otherwise wait for the next beat. In detail:
- A press 0–50 ms **after** a beat applies at once, timed as if it landed on that beat, so a chase stays in phase.
- A press up to 50 ms **before** a beat waits that last moment and lands on the beat.
- Every other press waits for the next beat.

Two things are left:

| Question | Options |
|---|---|
| **Beat or bar?** | **A ★** Always the next beat, and holding Shift (or a pad setting) waits for the next bar. **B** Beat only |
| **No tempo running** (clock stopped, or the BPM was never set) | **A ★** Switch at once. **B** Use the last known BPM anyway |

## Part 3: still open from earlier (with context now)

### Q-M9: Do your projectors' IP addresses change?
**Context.** The app recognises a projector by its **IP address + output number**. If a laser gets a different IP (a DHCP router at a new venue, or a power cycle), the app thinks it's a **new** laser, disabled and unconfigured. Its place in the left-to-right order, its safety zone and its groups are lost. A fix is to recognise lasers by the device's own **unit ID** instead.

| Option | |
|---|---|
| **A** | My lasers have fixed IPs: the fix can come later (doc 08 R-4) |
| **B ★** | IPs do change, or I'm not sure: build the fix into the Rig strip phase (Z2), about +1 day |

### Q1: How many lasers, and how are they laid out?
**Context.** This sets how wide the strip has to be, whether it needs scrolling, and which rigs the defaults are tuned for.

Options: **A** 1–2 · **B** 3–4 · **C** 5–8 · **D** more than 8. Also tell us the usual layout: a line on a truss, sides + center, front and back, plus audience scanners.

### Q4: Several zones on one laser, permanently?
**Context.** The split (two regions on one laser) is automatic and only lasts while a cue needs it. Some setups want a **permanent** split, for example "the left half of this laser is always the graphics zone, with tighter safety".

| Option | |
|---|---|
| **A ★** | Not now. Automatic split only (doc 08 has the permanent version for later) |
| **B** | Needed soon. Pull it into the plan after Z3 |

### Q5: Audience scanning limits
**Context.** In v1, safety is a scan-limit rectangle plus blocked rectangles. Lasers that scan the audience usually also need limits on the beam itself.

| Option | |
|---|---|
| **A ★** | Rectangles are enough for v1. Audience limits come later |
| **B** | v1 also needs a **brightness cap** for audience-tagged lasers |
| **C** | v1 needs a brightness cap + a **minimum scan speed / no static beams** |

### Q6: If the safety step itself fails
**Context.** Safety is code. If it throws an error (a bug, or bad data), the app has to decide what that laser does.

| Option | |
|---|---|
| **A ★** | **Go dark** on that laser and show "Safety error: output off". The other lasers keep going |
| **B** | Keep sending the **last frame that passed**, and warn |

### Q8: Where is the rig saved?
**Context.** Today projectors and groups are saved **inside the show file**, so New Project wipes your rig, and opening a show from another venue replaces the rig in front of you. This affects touring and reusing a rig across shows.

| Option | |
|---|---|
| **A** | Keep it in the show file (as today) |
| **B** | A separate **rig file** per machine or venue. Shows only hold the creative side (cues, zone maps, start map) |
| **C ★** | **Split.** The physical part (IPs, order, safety, calibration) lives in the rig file. The show keeps a copy of its custom groups and maps. Opening a show **never overwrites safety or calibration** |

The plan keeps today's storage until this is decided. It doesn't block Z0 to Z3.

### Q17: Converting old shows
**Context.** Old shows store routing in four places (item zone, Zone Selector effect, track zone, cue default) under the old group names. The plan converts them **once, on load**, and checks with test projects that they still route exactly as before.

| Option | |
|---|---|
| **A ★** | Convert on load, automatically, without asking |
| **B** | Convert on load, but show a one-time "this show was updated" note, and save a backup of the original |
| **C** | Never convert. Keep reading the old fields forever |

### Q-e: Changing a safety zone while that laser is live
**Context.** Dragging a safety edge while the show is running changes where the beam can go **immediately**. That can be exactly what you want (a quick fix at sound check), or a mistake that sends light where it shouldn't.

| Option | |
|---|---|
| **A** | No confirmation. Every change is undoable and recorded in the history |
| **B ★** | Changes that **make the zone smaller or safer** apply at once. Changes that **make it bigger** need a confirm click while the laser is outputting |
| **C** | Every safety change on a live laser needs confirming |
