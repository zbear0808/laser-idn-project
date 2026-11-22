# What the research agrees on

Five research notes in `../research/` cover laser and lighting software (r01), VJ and DJ software (r02), music instruments and apps (r03), HCI for live performance (r04), and rig setup and spatial mapping (r05). Read each note's §3 for its patterns, and r04 §8 for its 25 rules and §9 for a 15-question checklist.

These are the findings that came up in **three or more notes**. Each one can be seen in at least one design.

| # | Finding | Where it comes from | Pains |
|---|---|---|---|
| F1 | **Every press declares its behaviour**: shot, hold or latch, and "now" or "on the bar". A corner glyph shows which, and one global default can be overridden per pad | Ableton launch modes, Resolume trigger styles, grandMA Flash/Temp/Toggle, BEYOND Flash/Solo-Flash (r01 #4, r02 #1, r03 #2–4) | P1, P3 |
| F2 | **One light language on screen and on the controller**: empty, ready, on, queued (flashing once per beat), running by itself | Launchpad, Push, APC, Traktor F1, BEYOND MIDI feedback (r02 #16, r03 #1, r04 rule 6) | P1, P6 |
| F3 | **Fixed colour per role**, the same everywhere, colour-blind safe, and never colour alone | OP-1/OP-Z colour encoders, Bitwig rainbow controls, LaserOS APC40 colour map, WCAG and Okabe–Ito (r01, r03 #13, r04 rule 5) | P1, P7 |
| F4 | **One gesture for a complex change**: a held modifier turns pads into punch-ins, a scene snapshot, a macro dial with per-target ranges, or a crossfader between two looks | EP-133/OP-Z punch-in, Octatrack scenes and crossfader, Resolume Dashboard, Q-Shift A/B states, Rytm performance macros (r01 #6, r02 #4, r03 #5–8) | P3 |
| F5 | **Show what a press moved, and what is acting now**: the affected outputs flash in the pad's colour, there is an "Active now" stack, a "running but not from this control" border, and a count of what isn't at default with one reset | SoundSwitch Active Looks, QLC+ orange border, grandMA3 "1/4 non-default", Lightkey bubbles, Fels' transparency (r01 #15–16, r02 #14, r04 rule 13) | P3, P6 |
| F6 | **Mark what the app did by itself**: an AUTO tag and a "why" line, AUTO buttons that hand control back, and one freeze. Correct automation can still feel like losing control | rekordbox Lighting AUTO, SoundSwitch, Virtual LJ, Amershi's guidelines, Coyle et al. (r01, r02 #12–13, r04 rules 14–15) | P5, P6, P7 |
| F7 | **Prepare, then commit**: a NEXT slot with a preview that isn't sent, blind edits, and changes that land on the bar | Traktor cue channel, Resolume preview, Millumin silent edit, ETC Blind, Ableton scene launch (r02 #7, r03 #10, r04 rule 19) | P2, P5, P6 |
| F8 | **Structure you can see beats hidden rules.** Coloured columns where one cue per column plays replaced BEYOND's limiters, which users couldn't predict | BEYOND Cue Groups, Ableton Session View, QLC+ Solo Frame (r01 #1 and §4, r03 #17) | P2, P7 |
| F9 | **Routing is separate from content.** Cues name roles or zones; a switchable map decides where they land; "0" means home | BEYOND Destination cue and zone keypad, disguise Stage/Feed, Modulaser output groups, SoundSwitch categories (r01 #2–3, r02 #15, r05 #8, #19) | P5 |
| F10 | **Setup is find → place → roles**: a two-way identify, "number all", placement that answers back, templates, guess-then-walk, and a venue matcher that remembers each unit | Q-SYS/Dante/d&b identify, Resolume Identify Displays, Hue Entertainment, Nanoleaf, L-Acoustics Unit Matcher (r05 #1–5, #12) | P4 |
| F11 | **The rig belongs to the app or venue, not the show.** An import never overwrites, and a missing laser is "not here" rather than "broken" | Modulaser, rekordbox Venue, Q-SYS optional devices, BEYOND's fail-closed unknown zones (r01 #10, r02 #15, r05 #5–7) | P4, P6 |
| F12 | **Safety is unmistakable and can't be bypassed**: one stop control that can't be remapped, two-step arming, safety settings that can't be MIDI-mapped, red keep-out over green keep-within, a horizon light, "unverified at this venue", and audience lasers that never get rerouted cues | Modulaser arm/confirm, LaserOS, PASS, FDA variance conditions, MIL-STD guards (r01 #12–13, r04 rules 9–10, 22, r05 #13–17) | P8 |
| F13 | **The operator should be watching the room.** FDA variances require the operator to see the beams and the audience. The screen should confirm, stay dim, and be mirrored on the controller's LEDs | FDA variance, Bullock, US Navy dim-white finding (r04 §4–5) | P1, P8 |
| F14 | **Small kits win.** DJs still pack small crates, and players of a one-button instrument still develop styles. Rich multi-effect mappings take practice before they pay off | Ahmed et al., Hunt & Kirk, Magnusson (r04 §2, §7) | P2, P3, P7 |

## Warnings, from the complaints

- **Hidden override layers** are the top complaint about auto-lighting tools: lights that are always on, and not knowing "what SS actually did" (r02 §4). Every automatic action must be visible.
- **Auto-analysis makes everything look the same** ("every song is cool high", r02 §4). Automation should vary its looks and stay easy to override.
- **Screen and hardware out of sync** (DDJ-200 pad modes, r02 §4). LED feedback must come from engine state, not from the click (r02 #17).
- **Outputs swap order after a reboot** (Resolume, theatre). Bind lasers by unit id, never by list position (r05 §4).
- **Realtime re-routing near the audience is dangerous**, in the words of Pangolin's own developer (r05). Live zone maps must never send rerouted content to audience lasers.
- **Hidden button combos**: Elektron users still erase things by accident (r03 §4). Quasimodes should be few and visible.
