# Scenarios

These are the situations the zone system has to handle. Each one lists what has to be true for it to feel good, with the needs it tests from `02-needs.md`.

The rig sizes are guesses until **Q1** (how many projectors you run, and how they are laid out) is answered.

## S-1: One laser, no setup
**Who:** someone at home or at a first gig.

They plug in one projector, press a pad, and get light.
- **Must feel like:** there are no zone concepts at all. Nothing has to be ticked.
- A safety zone is offered, but it can't be silently skipped when audience scanning is involved.
- **Tests:** N-1, N-2, N-9.

## S-2: Two lasers, left and right, in a club
The most common small rig. They want both lasers to do the same thing most of the time, and on drops they want them to mirror or alternate.
- **Must feel like:** setting the left and right positions once gives mirror and alternate everywhere. No cue edits.
- **Tests:** N-3, N-12, N-14, N-16.

## S-3: Four to six lasers on a truss, plus audience scanners
The lasers are spread across the stage, and two of them are low and point at the audience.
- Graphics and text should go only to the upstage lasers.
- Beams can go anywhere the safety zone allows.
- Audience content gets strict limits.
- **Must feel like:** the projectors are placed and given safety zones in minutes. "Text only on graphics lasers" is a rule set once, not something done in every cue. Audience limits can't be broken by a cue or an effect.
- **Tests:** N-5, N-6, N-7, N-10, N-11.

## S-4: Busking with no prep
A guest set, a rig they didn't build, cues from their own library. They have minutes, not hours.
- **Must feel like:** opening the show on this rig just works. The cues spread across whatever is there in a sensible way, and the zone pads give them live control.
- **Tests:** N-12, N-13, N-18, N-19.

## S-5: A programmed timeline show
Timecoded, rehearsed, run the same way every night. Every beam's placement matters.
- **Must feel like:** precise targets per track and clip. The automatic behaviour never moves something they placed on purpose.
- **Tests:** N-11, N-15, N-20.

## S-6: A touring show across venues
The same show file. Each night has a different number of projectors, a different layout and different safety zones.
- **Must feel like:** loading tonight's rig (or building it fast) re-routes the whole show. Cues are untouched. Anything that no longer has a home is reported, not silently dropped.
- **Tests:** N-8, N-13, N-21.

## S-7: Rig changes during setup or the show
Someone adds a projector halfway through setup, or one dies during the show.
- **Must feel like:**
  - A new projector joins the automatic groups and picks up content straight away.
  - A dead projector shows up in the UI; the rest keep going.
  - The groups don't need rebuilding by hand.
- **Tests:** N-4, N-17, N-22.

## S-8: Calibrating and checking
Before doors open, they walk the rig. Which physical laser is which? Is each safety zone right? What is each laser drawing right now?
- **Must feel like:**
  - Identify flashes the real laser.
  - The test pattern respects the safety zone.
  - A live preview of each output shows the real output.
- **Tests:** N-4, N-7, N-17.
