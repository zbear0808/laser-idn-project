# Every open question, with its context, the options and a recommendation

**Written:** 2026-11-19   **Status:** Open.
- Each question has a recommendation (★), and the phase docs are written assuming it.
- **You only need to answer the ones where you disagree.** "All ★ except Q-Z3-1 B" is a complete answer.
- The questions are grouped by the phase they block. The earlier a phase comes, the sooner its questions matter.
- Already decided: D-1 to D-34 (`00-overview.md` §2).
- **Round 3 (2026-11-19):** Q-Z2-1 **B** and Q-Z1-1 **A** are confirmed (D-33, D-34). Q1 is still open.

## Before anything starts

**Q1 · How many lasers do you usually run, and how are they laid out?** (Still open. Until it's answered, the plan assumes a strip that scrolls past about 8 cards.)
This sizes the rig strip (it scrolls past about 8 cards; more than 12 calls for compact cards), the test fixtures, and which layouts the defaults are tuned for.
- **A** 1–2
- **B** 3–4
- **C** 5–8
- **D** more than 8

Also tell me the layout: a line on a truss, sides + center, front and back, or with audience scanners.

## Overview's own design calls (`00-overview.md`)

**Q-Z1 · More zones than a map has lanes.** A cue uses zones A–F, but Left / Right has two lanes.
- **A ★ Wrap:** C → Left, D → Right… Patterns work under every map, and nothing is lost.
- **B** The extra zones go to All.
- **C** The extra zones go nowhere, with a warning.

**Q-Z2 · A target that names both halves of one laser.** For example, an item → Zone A + Zone B on a one-laser rig.
- **A ★** Drawn once, on the whole laser.
- **B** Drawn twice, once in each half.

**Q-Z3 · A laser that goes offline mid-show.**
- **A ★** It keeps its place. The others keep their Left/Odd roles, and its content just isn't seen.
- **B** The groups re-fold without it. The others cover its role, but Odd/Even reshuffles live.

**Q-Z4 · Where a default target can be set.**
- **A ★** Grid column and show default (plus item, track, group and cue).
- **B** Also per vibe.
- **C** Only cue and below.

**Q-Z5 · What "Used by N cues" counts.**
- **A ★** Direct references, plus those through the current zone map, shown apart ("3 cues · 9 more through Zone A").
- **B** Direct references only.

**Q-Z6 · Zone names.**
- **A ★** Letters, with an optional label per show ("A · Beams").
- **B** Free names instead of letters.

## Z0 · Fix-ups (`02-phase-z0-fixups.md`)

**Q-Z0-1 · Deleting a group: where its cues go by default.**
- **A ★** All, so they keep playing.
- **B** Nothing, so they inherit, which follows a column or show default once Z4 lands.

**Q-Z0-2 · Removing a projector that is streaming.**
- **A ★** Allowed after the confirm. Its engine stops.
- **B** Only when it is turned off.

## Z1 · Safety (`03-phase-z1-safety.md`)

**Q-Z1-1 · Until ZR, opening a show replaces this machine's safety zones with the show's**, as it already does for every projector setting. **Answered A (D-34).**
- **A ★ (chosen)** Accept it until ZR, and say so after Open: "Safety zones and calibration come from friday.zip".
- **B** Pull ZR's "never overwrite safety" rule forward into Z1.

**Q-Z1-2 · Identify while streaming is off.**
- **A ★** The button is disabled, with "Turn on Stream to see it on the laser".
- **B** Briefly stream just that laser.

**Q-Z1-3 · The test pattern's brightness ceiling** (the slider stops at 50% today).
- **A ★** Keep 50%.
- **B** Allow 100%, with a warning colour above 50%.

## Z2 · Rig strip and groups (`04-phase-z2-rig-strip-and-groups.md`)

**Q-Z2-1 · Exclude from All also takes a laser out of Left/Right/Center/Odd/Even.** **Answered B (D-33)**, and already applied to the spec and to Z2. The other options were **A** (Exclude only means "not in All") and **C** (a second switch).

**Q-Z2-2 · Ticking "Scans the audience" on a laser that is in All** makes every All cue an audience cue for vibes.
- **A ★** Offer to exclude it, with an amber line and a button. Nothing happens by itself.
- **B** Exclude it automatically, with a note.
- **C** Do neither.

**Q-Z2-3 · Disabled lasers on the strip.**
- **A ★** In their place, dimmed.
- **B** In a tray after the strip ("Not in the rig").
- **C** Hidden behind a "Show the lasers that are off (3)" toggle.

**Q-Z2-4 · The discovered-devices list.**
- **A ★** It goes. Each output is a card, and a multi-output card's menu turns on every output.
- **B** It stays, as a collapsible "Devices found" panel.

## Z3 · Routing core (`05-phase-z3-routing-core.md`)

**Q-Z3-1 · The middle laser set to "both", with one cue on Left and another on Right.**
- **A ★** It splits: the first cue in its left half, the second in its right.
- **B** It draws both whole, overlaid, and splits only when one cue lights both sides.

**Q-Z3-2 · A rig card when its laser isn't streaming.**
- **A ★** "Not streaming", and no meter.
- **B** What it would draw, in grey, with an estimated meter.
- **C** What it would draw, through its calibration.

**Q-Z3-3 · How long a cue's ⚠ (too heavy) stays.**
- **A ★** While it plays.
- **B** For the rest of the session.
- **C** Saved with the show.

**Q-Z3-4 · What 100% on the load meter means.**
- **A ★** The most points that still reach the laser's minimum fps.
- **B** The points that reach 60 fps.

**Q-Z3-5 · A laser the fit guard thinned back up to its minimum.**
- **A ★** Its card notes the cut ("−28% pts"), and there is no toolbar chip.
- **B** The toolbar also shows a THINNED chip.

## Z4 · Zones, maps and targets (`06-phase-z4-zones-maps-targets.md`)

**Z4-Q1 · When the map pads show.**
- **A ★** Whenever the show uses a zone anywhere.
- **B** Only while a playing cue uses zones.
- **C** Always.

**Z4-Q2 · Learning a key for a map pad again.**
- **A ★** It moves the key (one key per pad, like the vibe pads).
- **B** It adds a second key.

**Z4-Q3 · Which zones the Target menu lists.**
- **A ★** A and B, every zone the show uses, and "New zone".
- **B** Always A–D.
- **C** As many as the active map has lanes.

## ZR · The rig file (`09-phase-zr-rig-file.md`)

**Q-ZR-1 · Where the rig file lives.**
- **A ★** `~/LaserShowProjects/rig.edn`, beside your shows.
- **B** The system's hidden app-data folder.
- **C** Next to the app.

**Q-ZR-2 · Does a saved show still carry a copy of the rig?**
- **A ★** Yes, so older builds and other machines still open it with its setup.
- **B** No.

**Q-ZR-3 · Opening a show on a rig with no laser turned on.**
- **A ★** Ask "Set up your rig from this show?" (Set up / Not now).
- **B** Set it up silently, with a notice.
- **C** Never.

**Q-ZR-4 · Two app windows at once.**
- **A ★** The second one gets the rig read-only, with a banner.
- **B** The last one to write wins.
- **C** Each reloads when the file changes.

**Q-ZR-5 · Undo for rig edits.**
- **A ★** Undoable in the one history, and they don't mark the show unsaved.
- **B** Not undoable.
- **C** A separate rig history.

**Q-ZR-6 · "Forget this laser".**
- **A ★** In ZR, on a disabled card (Z0's remove).
- **B** Wait for discovery clean-up (needs/08 R-18).

## Z5 · Zone patterns (`07-phase-z5-zone-patterns.md`)

**Z5-Q1 · What a pattern pad switches.**
- **A ★** The style of every pattern that follows the pads, on cues and in cells. One learnable row.
- **B** One chosen pattern ("Pads act on: Cue A1").
- **C** A pad per pattern and style.

**Z5-Q2 · Map pads against a running Map cycle.**
- **A ★** A press pauses the cycle on that map until CYCLE is pressed. The last thing you did wins (now in the spec, §4).
- **B** The press holds only until the cycle's next step.
- **C** The cycle always wins while it's on.

**Z5-Q3 · Is the pads' live pattern style saved with the show?**
- **A ★** No. A show always opens "As set".
- **B** Yes, like the zone map.

**Z5-Q4 · "Every laser, left to right" as a pattern's zones.**
- **A ★** Yes. A chase runs over however many lasers are on, and one laser chases its halves.
- **B** No. Chases use zones and maps only.

**Z5-Q5 · Random.**
- **A ★** Evenly spread, never the same zone twice in a row, the same every run.
- **B** Seeded pseudo-random with no repeats.
- **C** Different every time.

**Z5-Q6 · Where a cue's pattern counts from.**
- **A ★** The cue's start, so A always comes first.
- **B** The show's beats, so every cue alternates in step.

## Z6 · Step grid (`08-phase-z6-step-grid.md`)

**Z6-Q1 · A new grid's length and speed.**
- **A ★** 8 steps at 2 a beat.
- **B** 16 at 4 a beat.
- **C** 16 at 2 a beat.

**Z6-Q2 · Does a grid follow the pattern pads?**
- **A ★** Not by default. A tick box turns it on.
- **B** Yes.

**Z6-Q3 · Painting with a drag.**
- **A ★** The first cell decides whether you light or clear.
- **B** Each cell you cross flips.

**Z6-Q4 · How long a grid can be.**
- **A ★** 1–64 steps, with 8, 16 and 32 one click away.
- **B** Only 8, 16 or 32.

**Z6-Q5 · Where the grid opens.**
- **A ★** Its own window.
- **B** Inside the pattern window.
