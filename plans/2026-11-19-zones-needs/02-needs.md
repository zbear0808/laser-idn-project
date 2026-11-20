# Needs

Each need gets a priority:
- **Must**: without it the problem isn't solved.
- **Should**: expected, but it can come in a later phase.
- **Could**: nice to have.

"Done when" is the test we would demo. A need marked **(assumed)** is our guess and is waiting on a question in `03-conflicts.md`.

## Setup

| ID | Need | Priority | Done when |
|---|---|---|---|
| N-1 | **Zero-config works.** With no zone setup at all, every enabled output plays every cue | Must | A fresh project with any number of projectors shows content on all of them, and nothing has to be ticked |
| N-2 | **One place to set up the rig.** Connection, position, geometry, safety, calibration and groups are all edited in one screen | Must | No step in setting up the rig needs a second tab |
| N-3 | **Groups appear by themselves.** Useful groups (left, right, center, odd, even and so on) come from the rig and aren't built by hand | Must | Placing the outputs is enough to target "Left" or "Odd" |
| N-4 | **Rig changes update everything.** Adding, removing or disabling an output updates the automatic groups and the routing | Must | Add a 5th projector: it joins All plus the right automatic groups and shows content, with no other step |
| N-5 | **Fast placement.** Telling the app where each projector is takes seconds per projector (templates, drag, identify) | Should | Placing a 6-projector rig takes under 2 minutes |
| N-6 | **Copy setup across outputs.** Safety, geometry and calibration can be copied to similar outputs, mirrored for the other side | Should | Set up one side, then mirror it to the other |

## Safety

| ID | Need | Priority | Done when |
|---|---|---|---|
| N-7 | **Real safety zones for each output.** A scan limit plus blocked regions; crossing lines are clipped and blanked | Must | Content drawn through a blocked region never lights it, whatever the cue or effect |
| N-8 | **Safety can't be bypassed.** Safety is applied last, after all content, effects, routing and calibration. No modulation, layout or override can get past it | Must | No path from a cue or effect skips the safety stage. A test proves this |
| N-9 | **Safety fails closed.** If the safety stage errors, that output goes dark and the UI says why | Must **(assumed, Q6)** | An injected safety error blanks that output and shows an error |
| N-10 | **Audience outputs get stricter limits.** Brightness cap, minimum scan speed, no static beams, and the existing `:crowd-scanning` rules | Should **(Q5)** | An audience-tagged output can't exceed its cap, even at full intensity |

## Routing and defaults

| ID | Need | Priority | Done when |
|---|---|---|---|
| N-11 | **Exact targets when you want them.** A cue, track or item can be pinned to a specific group, and nothing automatic moves it | Must | A pinned "text → graphics" track stays there under every layout or distribution |
| N-12 | **Cues don't need to know the rig.** A cue made without zone setup looks intentional on 1, 2 or 8 outputs | Must | One library cue looks right on S-1, S-2 and S-3 without edits |
| N-13 | **A rig change doesn't mean editing cues.** Changing the rig or the zone layout re-routes the show; cue data stays the same | Must | Swap a 2-projector rig for a 6-projector rig: zero cue edits, and nothing goes dark that had a home before |
| N-14 | **Set the target once, higher up.** A target is set at the show, column or page, or vibe level, and cues inherit it | Should **(Q10)** | "Column 3 = Left" set once applies to every cue in column 3 |
| N-15 | **One way to say "where".** One target control, shown the same way in every editor. The four routing methods we have today become one | Must | Cue Chain Editor, Timeline and grid all show the same target, worked out the same way |
| N-16 | **Different images per laser without per-cue wiring.** Variation between outputs comes from rig-level or distribution settings, not from hand-routing each cue | Must | S-2 alternates or mirrors with no cue edits |

## Modulation and live

| ID | Need | Priority | Done when |
|---|---|---|---|
| N-17 | **See what each output really draws.** A per-output preview after calibration and safety, plus a point budget warning | Must | Each projector has a thumbnail of its actual output and a load indicator |
| N-18 | **Grouping can be modulated.** Chase, sweep, alternate on the beat, or step on a pulse. Whatever the editor offers really plays | Must | A keyframed or modulated zone change actually moves content between outputs |
| N-19 | **Live zone control.** Solo, mute and distribution override per group, from pads, MIDI or vibes | Should | Tapping "Left" solos the left outputs; releasing it restores them |
| N-20 | **Predictable, explainable routing.** For any cue you can see where it goes and why (which level set it) | Must | Hovering a target shows "Left, from column 3" |

## Rig vs show, and the rest

| ID | Need | Priority | Done when |
|---|---|---|---|
| N-21 | **The rig survives a new show, and a show can run on another rig.** Clear rules for what belongs to the rig and what belongs to the show | Should **(Q8)** | New Project keeps the rig. Opening a show from another venue maps onto the rig that's here |
| N-22 | **References never dangle.** Deleting or renaming a group, or losing an output, never leaves cues pointing at nothing without a warning | Must | Deleting a group asks first, then re-targets or flags the cues that use it |
| N-23 | **Old shows load.** Existing `:zone-group-id`, `:destination-zone` and `:target-zone` data keeps working or is converted once | Must **(Q17)** | Every show in the test fixtures loads and routes the same way as before |
| N-24 | **No CPU regression in unison.** Zone features cost close to nothing when everything plays the same thing | Must | Unison on 8 outputs costs about the same as today's single `:all` render |
| N-25 | **Within the point budget.** Overlapping targets don't silently overload a projector | Should | Overlap gets a warning, or a policy decides how to handle it (T-7) |

## Wanted, not core (see `06-zone-patterns-and-split-zones.md`)

| ID | Need | Priority | Done when |
|---|---|---|---|
| N-26 | **Zone patterns fit any rig.** A cue that switches between left/right, odd/even or a chase is written against abstract slots and folds sensibly onto 1 to N lasers | Could | One Alternate L/R cue looks intentional on 1, 2, 3 and 4 lasers, with no edits |
| N-27 | **Split one laser into sub-zones.** When there are fewer lasers than slots, one laser draws several slots in separate regions, as if it were several zones in the group | Could | On 1 laser, Alternate L/R alternates between the left and right halves, and safety still applies |
