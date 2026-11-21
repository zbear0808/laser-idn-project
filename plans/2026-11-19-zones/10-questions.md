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

**Q-Z0-3 · A calibration effect that fails** (for example, a broken corner pin). Today it is skipped and the rest of the chain runs, as the output plan decided, and only the log says so.
- **A ★** Keep the skip, and say it on the projector's status line in amber: "Streaming · 59.8 fps · Corner Pin skipped". Z1's safety step still clips whatever the skip lets through.
- **B** Fail closed for calibration too: that laser goes dark with "Calibration error: output off".

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

**Q-Z1-4 · Undo or Redo that would make a live laser's area bigger.** There is one undo history for the whole show, so a ⌘Z meant for something else could undo a safety zone you made smaller during the show.
- **A ★** Ask first, with the same "Allow light in a larger area?" dialog.
- **B** Just restore it, since it puts back a zone that was in force before.

Either way, Escape during a drag puts the old zone back without asking.

**Q-Z1-5 · Safety zone edits while the show lock is on** (vibe Phase 7's switch on the Perform tab; it refuses edits that change the show, and never BLACKOUT). **Answered A** (2026-11-20), as built in `0002052`.
- **A ★** Making an area smaller always works; making it larger is refused while the lock is on. The lock never stands between you and a safer show, and nothing gets wider by accident during one.
- **B** Refuse every safety edit while locked: unlock first (BLACKOUT is always there for an emergency).
- **C** Leave safety edits alone, as now.

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

**Raised while building Z3** (2026-11-20; 05 §12 has the context; built as ★ A):

**Q-Z3-6 · The show lock and a laser's fit guard settings.**
- **A ★ (built)** The lock lets them through: a fit edit restarts and re-routes nothing, lets no light anywhere new, and lowering a laser's minimum is what a cue's ⚠ asks you to do mid-show.
- **B** The lock refuses them, as it refuses the rig's order, middle laser and split width.

**Q-Z3-7 · The fit guard costs about twice its estimate** (about 190 µs per 2,000 points, against 50–100).
- **A ★ (built)** Leave it: it runs on the laser's own thread, only for a frame over budget.
- **B** Make it faster now, with primitive arrays.

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

**Raised while building Z4** (2026-11-20; 06 §12 has the context; built as ★ A):

**Z4-Q4 · The show lock and targets.**
- **A ★ (built)** Refuse every target edit (a cue's, an item's, a track's, a clip's, a column's, the show's), zone labels and every map edit; a pad, its key, a due switch, Use now and selecting a map go through.
- **B** Refuse only the column and show defaults and the maps; a cue's own target goes through like its other edits.

**Z4-Q5 · Where ZONES AND MAPS sits on the Zone Groups tab.**
- **A ★ (built)** Under the lasers and the details, so the groups list keeps the tab's height.
- **B** Under all three columns. **C** A tab of its own, "Zones".

**Z4-Q6 · How loud the Vibes tab is about a zone cue that can reach the crowd.**
- **A ★ (built)** A warning, like the other audience rows.
- **B** An error, as V10 makes it for a zone effect.

**Z4-Q7 · Z0's walker still reads the old zone keys.**
- **A ★ (built)** Keep them until Z5, then take them out with their fixtures.
- **B** Take them out now.

## Decimate (`12-decimate-effect.md`)

**Q-D-1 · The load meter with Decimate in a laser's calibration chain.** **Answered B** (2026-11-20); built in `9585cae`.
- **A ★ (built)** What the laser is asked for over the guard's budget: a 3,000-point cue reads 240%, red, while the laser draws 30 fps; the note's "−72% pts" says what was cut.
- **B** What Decimate left over the budget: 833 points read 67%, so the meter, the fps and LOW FPS all describe what the laser draws.

**Q-D-2 · A cue's ⚠ for a laser that decimates it.** **Answered B** (2026-11-20); built in `114e49e`.
- **A ★ (built)** Keep warning: the cue is heavy, and the laser thins it.
- **B** Count each laser's calibration chain: a laser with Decimate counts a cue at no more than Decimate's budget.

**Q-D-3 · The meter when Decimate in a laser's chain leaves the count as it is** (the guard got under Decimate's budget first, or only anchors are left). Raised in the follow-up (12 §7). **Answered B** (2026-11-20); built after the follow-up landed.
- **A** What the laser is asked for, as without Decimate: a heavier cue can then read lower (2,700 points 216%, 2,800 points 133%, with Decimate at 15 fps under a 20 fps minimum).
- **B ★** What the laser draws, whenever its chain has an enabled Decimate, so the meter, the fps and LOW FPS always agree.

**Q-D-4 · The ⚠'s advice when a laser's Decimate sets the count.** Raised in the follow-up (12 §7). **Answered B** (2026-11-20); built after the follow-up landed.
- **A** As for any cue: "Simplify it, or lower Laser C's minimum."
- **B ★** Name Decimate: "Laser C's Decimate keeps ~830 points: set its scan rate to Laser C's 12 kpps, or lower Laser C's minimum."

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

**Raised while building ZR** (2026-11-20; 09 §12 has the context; the build follows ★):

**Q-ZR-7 · Which of a show's lasers the set-up and Import bring.**
- **A ★ (built)** Only the lasers the show had on.
- **B** All of them, the ones that were off coming in off.

**Q-ZR-8 · An imported laser's On box.**
- **A ★ (built)** As the show had it. While streaming, its engine starts at the show's address, which on this network may be another device.
- **B** Off, as a scan finds a new laser, until you tick On on its card.

**Q-ZR-9 · A pin to a laser this rig doesn't have, after Save.**
- **A** Save also keeps the show's entries for those lasers, so the pin finds its unit again on the next rig.
- **B ★ (built)** As is: the report says the pin is missing, and Pin them to… fixes it.

**Q-ZR-10 · The show lock and "Add them to this show"** (it only adds group definitions).
- **A ★ (built)** Allowed while locked.
- **B** Refused, as every other report button is.

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
