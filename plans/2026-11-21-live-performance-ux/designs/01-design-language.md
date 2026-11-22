# Shared design language (used by every design)

**Mockup:** `../mockups/s1-design-language.png`. These rules come from the research. The source of each is in brackets: `r01`–`r05` are the notes in `../research/`, `#n` is a pattern or rule number there.

## Roles and colours
- Five roles, five colours, always the same: **HIT** sky blue `#56B4E9`, **ACCENT** orange `#E69F00`, **FLASH** yellow `#F0E442`, **VIBE** reddish purple `#CC79A7`, **BLACKOUT** vermillion `#D55E00`. These are Okabe–Ito colours, which stay distinct for colour-blind users (r04 rule 5).
- A colour means its role everywhere: on the pad, on the cells the role can play, on the lasers a press moves, and on the controller's LEDs (r03 #13, r02 #16).
- BLACKOUT is the only red-family control. Red elsewhere means safety only (r04 rule 20).
- Zones are letters with their own colours: **A** blue, **B** green, **C** light grey, **D** ochre. A dashed chip means the zone is empty.

## The light grammar (screen and LEDs)
Every state has a second cue besides colour (r04 rule 5). Flashing is one flash per beat (r03 #1).

| State | Look | Meaning |
|---|---|---|
| Empty | dashed outline, dim, says why ("nothing to play") | the control would do nothing (r04 rule 17) |
| Ready | colour band and outline | press it and something happens |
| On | full colour, with a decay bar for a shot | doing it now |
| Queued | flashes on the beat, a ring fills to the bar | waiting for the beat or bar (r02 #2, r03 #4) |
| Running by itself | slow pulse, an **AUTO** tag | the app is doing it, not you (r04 rule 14) |

## What a press does
A corner glyph says what a press does (r02 #1, r03 #2, r01 #4):
- **▶ Shot** plays and ends by itself.
- **▮ Hold** is on while held.
- **⟳ Latch** is press on, press off.
- **⏱** means it waits for the bar.

Chips under the name say what else the press moves (size, bright, A→B) (r04 rule 13).

## Laser tiles
- Tiles always sit in rig order, left to right (r04 rule 18).
- A **white ring** means the laser is really emitting now, after intensity, blackout, zones and safety (r02 #8). Amber dashes plus a reason mean it is dark.
- An audience laser is hatched until it is allowed (r05 #13–14).

## Status band
One full-width band says the state of the whole show, readable from the room (r01 #14):
- **LIVE**: green.
- **DEGRADED**: amber.
- **BLACKOUT**: vermillion.
- **SHOW LOCK**: violet.

## Rules every design keeps (r04 §8)
1. No performance control opens a dialog, menu or popover.
2. The live view shows at most four concepts at once (vibe, roles, zones, intensity).
3. Alternate functions work only while something is held (quasimodes).
4. BLACKOUT is immediate and can't be remapped. Leaving it takes a different gesture.
5. Live labels are readable at 2 m: role names 24 px or more on a laptop screen at arm's length, and pad text 15 px or more.
6. Show mode is dim, with no large white fields.
7. Anything that is greyed out says why.
