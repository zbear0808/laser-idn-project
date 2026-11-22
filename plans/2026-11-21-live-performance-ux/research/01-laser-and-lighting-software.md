# 01 — Laser and stage-lighting control software: live and setup UX

Research date: 2026-11-21. Sources are official manuals, wikis and forums. Reddit pages were blocked (HTTP 403), so Reddit points come from search snippets only and are marked as such. The web search quota ran out partway through. Medialas (Lasershow Designer QS / Mamba), Kvant ShowNET in depth, Freestyler and Lightjams were **not researched** and are not covered below.

## 1. Summary

The laser tools and the lighting consoles fix the same problems in different ways. Pangolin learned over 20 years that "flexibility brings complexity". Its newer answer, **Cue Groups**, puts cues in coloured columns, and only one cue per column can play. Routing is a separate one-click "Destination" cue with a tiny zone-map thumbnail. Zones are also picked live from the numeric keypad, with "0" meaning "go back to the cue's own zones". Pangolin's **Q-Shift** pads and **Drop effects** are close to this app's tap reactions. Modulaser, a newer IDN-capable app, has the cleanest multi-laser model of the laser tools: output groups as the only route, a rig saved with the app and not the show, two-step "arm all" with a confirm, and a safety pipeline that remote inputs cannot bypass. Lighting consoles add tested busking parts: speed masters with tap/×2/÷2/reset, MAtricks wings and blocks for spreading effects, run-only locked screens, and signs that show what is not at default or not controlled by this button. The main complaints are hidden rules: the user cannot tell which cue will stop, cues silently do nothing after a settings reset, swapping zones on the beat needs scripting, and effects hide manual changes.

## 2. Products

### 2.1 Pangolin BEYOND (and QuickShow)

**Cue grid and pages.**
- The default grid is 10 columns × 6 rows = 60 visible cues. The letter keys trigger cues: unshifted keys for some rows, shifted keys for others. There are up to 32 pages in the older doc ([Cue Grid](https://wiki.pangolin.com/doku.php?id=beyond:cue_grid)).
- A newer doc says each page always holds 100 cues, and there can be up to 250 pages. Pangolin "strongly recommend keeping the Workspace as compact as possible" ([Workspace Grids and Pages](https://wiki.pangolin.com/doku.php?id=beyond:workspace_grids_and_pages)).
- There are two grids, main and secondary. The secondary grid can be moved to a second monitor. Each grid has its own click mode: **Select, Toggle, Restart, Flash, Solo-Flash** (same page).
- Pages belong to **Categories**, which act as filters. F-keys can be assigned to pages (same page).
- If you multi-select cues, you can change Cue Properties on all of them at once. Pangolin flags this as the time-saver "if you need to change Zone settings of a group of cues" (same page). This is the per-cue rig wiring this app wants to avoid (P5).

**Cue Groups (2017).**
- "You can start one Cue in any one Group. As soon as you start a new Cue, the currently playing Cue stops." Flash and FlashSolo cues are the exception: they last as long as the button is held ([Cue Groups](https://wiki.pangolin.com/doku.php?id=beyond:cue_groups)).
- The default grouping is by grid position: 1–4 columns or rows per group. "Visual placement matters." Each group has its own colour. Cue Properties can override the group (same page).
- Pangolin's reasoning: "What the restriction gives is speed. What the Group gives is simplicity… flexibility brings complexity, and it is hard to operate a complex system" (same page).
- History from the same page: LivePRO used 4 fixed tracks, and you had to pick a track first. QuickShow used "trackless" playback with One Cue / One Per / Multi-Cue modes and Limiters. Pangolin admits that with Limiters "it's not always easy to tell which Cue will stop when you click next one".
- **Multi-Zone Effects** are one effect that holds several effects, one per zone (same page).

**Destination cues (routing as a cue).**
- A Destination cue "at a single press re-routes the Output from all the Cues within its Group" ([Cue Groups](https://wiki.pangolin.com/doku.php?id=beyond:cue_groups)).
- The routing order is: running Destination cue, then the group's default destination, then the cue's settings, then the content's settings (same page).
- The Destination cue's grid cell is a mini 3×3 map. Orange rectangles mark the zones that will be used, and grey marks the others ([Destination in a cue](https://wiki.pangolin.com/doku.php?id=beyond:destination_in_cue)).
- It can be static or a chase made in a step editor (rows = zones, columns = steps). Step timing can be Clock (seconds), Metronome (beats), Event, Manual (script) or Input (a MIDI/DMX value maps across the steps) (same page).
- Toggle/Restart Destination cues replace each other. A Flash Destination *adds* routing. A Flash-Solo Destination temporarily blocks the others (same page).

**Zone hot keys.**
- The preview can be split into 4, 9 or 16 zones. Click a zone, and "most laser cues will be directed to the zone you selected" ([Controlling multiple lasers](https://wiki.pangolin.com/doku.php?id=beyond:controlling_multiple_lasers)).
- The 3×3 layout matches the numeric keypad. The right hand picks the zone and the left hand fires cues. **Key 0 = "use cue-preferred zones."** It only works with Num Lock on. Timeline shows and some cues ignore it (same page).

**Live Control, FX, time.**
- Live Control (size, position, rotation, colour, brightness, scan rate, speed) works on four levels: Cue, Master, Projection Zone and ProTrack. It is calculated Cue → Master → Zone. "Reset" returns to neutral ([Live Control](http://wiki.pangolin.com/doku.php?id=beyond:livecontrol)).
- **Physics**: a mass-spring filter sits between a slider and the value it sets (same page).
- **Globe/Resync**: effects share one clock by default. When you touch a speed slider, that effect line leaves sync. When you reset the slider, it goes back to sync. Pangolin warns that speed control "requires that you as the client, pay close attention" (same page).
- **QuickFX grid**: 4–6 effect lines of 25–100 effects. "One Per Line" or "Four Per Line" sets how many effects can run per line. **Drop Effects** are one-time effects, "like dropping a stone into the pool… after some time, the wave disappears", and their duration is set in seconds or beats (¼, ½, 1, 2, 4). An "Action" slider morphs from no effect to full effect (same page).
- Time tools include reverse, set/jump, an A–B loop and a DJ-style "disk" (same page).

**QuickShow extras.**
- **Q-Shift** pads give fast offset effects across lasers. In 2D mode, Y = effect amount and X = −100…+100 offset. Each pad has **0% and 100% bumper buttons** for a quick, temporary on or off ([Q-Shift](https://wiki.pangolin.com/doku.php?id=quickshow:qshift)).
- Each pad stores an A state and a B state taken from Live Control. Tempo is set as a beat fraction (4/1, 4/4, 1/4). "Period shift per item / per selection" sets the spread (same page).
- **DIV** pairs zones so they mirror each other, and **MOD** splits zones into alternating groups. The Destination tab sets the zone *order*, for example outside-in for symmetrical looks (same page).
- BPM: click the BPM label or press SPACE in time with the music ([Toolbar overview](https://wiki.pangolin.com/doku.php?id=quickshow:overview)).
- **Blackout** "causes all output to immediately stop", and it also resets the cue triggering mode (same page).
- **Virtual LJ** plays cues on the beat automatically. You can still fire cues on top: "like having two laserists" ([Virtual Laser Jockey](http://wiki.pangolin.com/doku.php?id=quickshow:virtual_laser_jockey), from a search snippet).

**Safety.**
- The **Beam Attenuation Map** is a 64×64 brightness map per projection zone. Each cell can be set from 0 to 100% ([Projection Zones](http://wiki.pangolin.com/doku.php?id=quickshow:projection_zones); [BAM](https://wiki.pangolin.com/doku.php?id=quickshow:bam)).

**MIDI.**
- BEYOND sends cell state back to the controller. The grid defines LED states for empty, used, focused and playing ([BEYOND MIDI settings](https://wiki.pangolin.com/doku.php?id=beyond:midi_settings-new), from a search snippet).
- Loading the APC40 profile changes the grid to 8×6 ([forum](https://forums.pangolin.com/threads/apc40-midi-questions.13248), from a search snippet).
- Version 5.5 added a mobile interface for zone alignment, live controls and quick text ([5.5 release](https://pangolin.com/en-jp/blogs/news/beyond-and-quickshow-5-5-release), from a search snippet).

**Not verified:** FB4 on-board safety features, and the exact feature split between BEYOND Essentials and Advanced.

### 2.2 Modulaser (ILDA/IDN, Mac/Win/Linux)

**Output groups.**
- Clips go to **output groups**, never to a laser directly. A laser can be in several groups, and "the most recently assigned group wins". "Clearing a group reveals the previous assignment underneath" ([Multiple Lasers](https://modulaser.app/docs/guides/multiple-lasers)).
- Each group has an opacity value that can be mapped to a fader. Each group also has its own effect chain: Strobe, Colorize, Chase, Scale Pulse, Flash, Rotation (same page).
- **Live target**: a monitor button picks the target group. Hovering a clip previews it through that group, and a click sends it out (same page).

**Spreading across lasers.**
- **Chase** has three settings: Rate (from bpm/32 to 8×bpm), Width (how many lasers are lit) and Fade (hard cut or smooth roll) (same page).
- **Phase Spread** offsets the animation per laser. With 4 lasers at 100%, the offsets are 0, 25, 50 and 75%. These controls only appear when 2 or more lasers exist (same page).

**Rig and safety.**
- "Lasers, output groups, and laser profiles are saved with Modulaser, not in the project file, so every project you open uses the same rig." The rig can be exported and imported. An import never overwrites: name clashes get "(imported)" (same page).
- **Arm** and **Disarm** buttons work on every DAC at once (same page).
- Projection maps can be copied from one laser to another with "Paste projection map" (same page).
- **Blind zones** are four-corner shapes with 0–100% power reduction. Where zones overlap, the highest reduction wins. Zones are fixed in the laser's scan field, so they stay put when the projection area moves. The 3D Beam View uses the same pipeline, "so the preview matches what the laser actually outputs" ([Projection Mapping](https://modulaser.app/docs/guides/projection-mapping)).
- Test patterns pause the show while active (same page).

**Remote control.**
- A phone must pair once with a 6-digit code that expires after 2 minutes.
- "Arm all" needs a second tap on **Confirm arm within 5 seconds**. DAC health is shown before you arm.
- "A dropped connection or a malformed request never holds light on." Every remote action "passes through the same non-bypassable safety pipeline" ([Remote Control](https://modulaser.app/docs/guides/remote-control)).

### 2.3 LaserOS / LaserCube (Wicked Lasers)

**Status bar.**
- The background colour carries meaning: **green** = laser data arriving over the network, **cyan** = ILDA input, **purple** = Cube Link slave that has found its master. An **I-LOCK** warning shows while the laser is not armed ([Control modes](https://wiki.laseros.com/academy/control-modes)).

**Surprise start.**
- In Playlist mode, "Power-On Play" is on by default. The last playlist starts as soon as the interlock is enabled (same page).

**Effects.**
- There are 78 effects. The manual recommends keeping them disabled when not needed "so the projection doesn't change by accident". A Master Effect slider at 0% means no effect at all (same page).

**MIDI (APC40 MK2).**
- The default mapping is colour-coded by function: blue = cues/playlist, red = effects, yellow = image (power, rotation, zoom, position) (same page).
- Since firmware 2.2, **Colour Balance and Safety Zone controls cannot be MIDI-mapped** (same page).

**Multiple cubes.**
- Cube Link sets each cube's Group number. A 2-channel show sends part 1 to Group 1 and part 2 to Group 2 (same page).
- Tempo: "Tap BPM", then "Sync Beat" on the first beat of a bar (same page).

### 2.4 Laserworld Showeditor

**Live window.**
- Figures are assigned to keyboard keys. The window shows a preview of each figure at its key position. The active figure has a green frame. **Space switches off all output.** Output starts only after "Laser ON" ([3.7 Make a Live Laser Show](https://showeditor.de/en/manual/3-first-steps-quick-start/3-7-make-a-live-laser-show.html)).

**Per-key options.**
- "Use Key Up Event (Flash)" makes a key momentary.
- "Switch off unused tracks" stops figures on other channels.
- An output track can be picked per key.
- The **six effect sliders can be redefined per key**, "so the layout of the sliders can change depending on the active key" (same page).

**Safety.**
- V7.1.Q added "Quick-Zero of safety zones with a button to quickly check laser safety zone setup. Applies to all zones" ([Showeditor download/changelog](https://laserworld.com/en/download-file-2212-Showeditor_Installer.html)). What "Quick-Zero" does exactly is **not verified**.

### 2.5 grandMA3

**Executor buttons.**
- Flash = full while held, no fade. Temp = on while held, with fade times. Toggle = latch. Go+ / Go- step the cue list ([Assign Object to an Executor](https://help.malighting.com/grandMA3/2.3/HTML/executor_assign.html); [cheat sheet](https://vanengelandt.net/blog/grandma3-shortcuts-cheat-sheet.html)).
- "Lock Executor" protects an executor from edits but still lets it play back (same Assign page).

**Masters window.**
- Speed masters have **Learn** (tap), **DS** (double speed), **HS** (half speed) and **Speed1** (reset to 60 BPM). An asterisk shows when a multiplier is applied. There is "All Learn" (tap every master at once) ([Masters Window](https://help.malighting.com/grandMA3/2.5/HTML/ws_masters_window.html)).
- Group masters have a Flash/Black mode: hold to go to 100% or to 0% for a moment.
- Each tab shows a green count of masters **not at their default value**, for example "1/4". A "Non-Default Only" filter hides the rest. Disabled masters show a red bar (same page).

**MAtricks.**
- MAtricks splits a selection into sub-selections. **Wings** split it into mirrored halves that are read from opposite ends. **Blocks** treat N fixtures as one ([Wings](https://help.malighting.com/grandMA3/2.0/HTML/matricks_wings.html); [Blocks](https://help.malighting.com/grandMA3/2.0/HTML/matricks_block.html)).

**Phasers.**
- A phaser is a list of steps with Width, Transition, Accel and Decel. On top sit Speed, SpeedMaster, Phase (offset per fixture, in degrees) and Measure (beats per loop) ([Phasers](https://help.malighting.com/grandMA3/2.3/HTML/phaser.html)).
- To build one, hold the **Step** key and tap presets, one per step.
- **Stomp** freezes a running phaser to a static look.
- **Sync** makes a phaser start with predictable timing (same page).

### 2.6 ChamSys MagicQ

**Execute window.**
- It is a user-designed grid of buttons and faders, with View Design, View Execute and **View Max** (full screen) ([Execute Window](https://docs.chamsys.co.uk/magicq/1.9.7.x/manual/execute.html)).
- MagicQ can boot straight into it, and it can be password-protected so "operators only see the Execute Window". A **Key Lock** button locks it until a password is entered (same page).

**Regions.**
- Regions group items in the window. A Group placed in a Region makes that Region's palettes affect only those heads, "regardless of head selection" (same page).
- Solo cue stacks in a Region replace each other (same page).
- **Rate items** add a Rate Tap, Fade/Snap, and Parts = 2/3/4 (same page).

**FX engine.**
- FX can be spread across heads, with Parts, Segments, Chunks, Width and direction ([MagicQ manual TOC](https://docs.chamsys.co.uk/magicq/1.9.7.x/manual/execute.html)).
- The FX library has a **One Shot** option (same TOC).
- An optional setting lets you set a playback's speed by holding DBO and tapping its flash button ([Settings](https://docs.chamsys.co.uk/magicq/1.9.9.x/manual/settings.html)).

### 2.7 Avolites Titan

**Shape Generator.**
- Size, speed and spread are on three wheels. Spread (or phase) runs the shape along the fixtures. The fixture order comes from the order of selection and can be edited ([Shape Generator](https://manual.avolites.com/docs/effects/shape-generator/)).
- **Beats** divides the speed, and "Match to Spread" sets the beat count equal to the spread. **Cycles** can stop a shape after N runs, for example 1.5 (same page).
- **Mask FX** is a stored "mask" that stops chosen shapes on chosen fixtures while its playback is active, for example "to freeze the look at the end of a song" (same page).
- In **Overlay** mode (the default) a running shape wins, so "changing colour using the wheels will have no visible effect" until the shape stops (same page).

**Key Frame Shapes.**
- Key Frame Shapes fade between stored absolute states, with a phase offset per layer ([Key Frame Shapes](https://manual.avolites.com/docs/en/effects/key-frame-shapes)).

### 2.8 Lightkey (Mac)

- Everything sits in one full-screen window: Live Preview, Preset Palette, a custom Control Panel and a Shortcuts strip ([Lightkey home](https://lightkeyapp.com/en)).
- The "white-on-dark UI has been specially designed for use in low-light environments—it even adapts itself to the light colors" (same page).
- "Little 'bubbles' in the Preview show you at a glance which fixture properties are overridden or defined by a preset, sequence, or cue" (same page).
- An interactive setup assistant covers DMX output, patching and the stage layout.
- Tempo comes from tap, MIDI Clock, Ableton Link or OS2L, with a per-cue Beat Multiplier (same page).
- The help center's first troubleshooting step is: "Is the unwanted behavior also visible in the Preview?" ([Lightkey Help](https://lightkeyapp.com/en/help), from a search snippet).

### 2.9 QLC+ (Virtual Console)

**Button behaviours.**
- A button can be: Toggle, Flash (scenes only, with an optional fade-out), Toggle Blackout, **Stop all Functions ("a panic button")**, or Adjust Function intensity ([Button v5](https://docs.qlcplus.org/v5/virtual-console/button)).
- A **Solo Frame** makes the buttons inside it replace each other (same page).
- If a button's function was started from another widget, the "Button border will turn to orange, to indicate that the Function is active but not actually controlled by the Button" ([forum](https://qlcplus.org/forum/viewtopic.php?t=16864), from a search snippet).

### 2.10 SoundSwitch

**Autoloops.**
- Autoloops take over when a track has no script. They are 8–128 bars long, locked to the DJ software's beatgrid, and stored in 4 banks of 32 ([Autoloops Explained](https://support.soundswitch.com/en/support/solutions/articles/69000847100-soundswitch-autoloops-explained)).
- Advice from the same page: "keep things simple… Solid color loops, color transition effects, and slow intensity fades tend to work well."
- Start with **Master Tracks**, "as this information is standardized across all your venues" (same page).

**Live control.**
- Autoloops cannot be fired by hand. Only Next, Previous and Repeat work, while Static Looks and overrides can be fired by hand ([r/SoundSwitch](https://reddit.com/r/SoundSwitch/comments/z8q5ml/not_working_in_performance_mode), from a search snippet).
- The Control One hardware has dedicated pads for strobe and smoke, and a pad matrix that toggles between Autoloops and Static Looks ([Control One](https://knightsoundandlight.com/products/soundswitch-control-one), from a search snippet).

### 2.11 Others (light coverage)

- **Daslight:** BPM triggering is set per scene in LIVE mode with a right-click ([Daslight forum](https://forum.daslight.com/viewtopic.php?t=2651), from a search snippet).
- **tomic.lighting:** a group fader button can be ON/OFF, FLASH, SOLO or DBO, and DBO "darkens only this group while held" ([tomic guide](https://tomic.lighting/guide), from a search snippet).
- **Obsidian Onyx:** RAO (release all overrides) and "Release All Cuelist Dimmer First" commands exist ([Onyx manual](https://files.obsidiancontrol.com/s/onyx_manual_4_10_1263/download), from a search snippet). Not studied further.

## 3. Patterns worth stealing

1. **Coloured columns, one cue per column.**
   - Source: BEYOND Cue Groups.
   - Addresses: P1, P2, P7.
   - Idea: Show a vibe as columns: Bed, Hit pool, Accent pool, Flash. Pressing any cell in a column replaces what that column is playing. Each column has a bright colour band at the top, and the playing cell glows in that colour. Making a vibe = dragging cues into columns, with no role dialog.

2. **Routing is its own one-press cue, with a mini map.**
   - Source: BEYOND Destination cue.
   - Addresses: P5, P6.
   - Idea: Each zone-map button shows a 4-box thumbnail (A–D). Lit boxes show where content will land. A chase map animates its boxes on the beat. Cues never store lasers.

3. **Keypad zone override with "0 = home".**
   - Source: BEYOND zone hot keys.
   - Addresses: P1, P5.
   - Idea: Second-hand keys 1–4 = send next presses to zone A–D. 5 = All, 0 = back to the vibe's own zone map. A big on-screen strip shows the current override, and it turns amber while an override is active.

4. **Flash adds, Solo-flash overrides, Toggle replaces.**
   - Source: BEYOND Destination modes; QLC+ Solo Frame; grandMA Flash/Temp.
   - Addresses: P3, P1.
   - Idea: Show each pad's mode as a corner glyph: ▮ held (momentary), ⟳ latch, ★ solo-while-held. A held FLASH pad can be "solo", so it blanks the other zones while you hold it.

5. **Drop effect: a one-shot with a set decay.**
   - Source: BEYOND Drop Effects; MagicQ One Shot; Avolites Cycles.
   - Addresses: P3.
   - Idea: Every tap reaction is a "drop" with a duration in beats (¼, ½, 1, 2). The pad shows a shrinking ring while the drop decays. Several drops stack, and a small counter shows how many are live.

6. **Two-axis reaction pad with 0%/100% bumpers.**
   - Source: QuickShow Q-Shift.
   - Addresses: P3, P5.
   - Idea: The Morph fader becomes "amount". A secondary knob sets "spread across lasers" (−100…+100). Two small bumpers sit beside it: "kill" and "full" while held.

7. **Three knobs for any spread: Rate, Width, Fade.**
   - Source: Modulaser Chase; MAtricks.
   - Addresses: P5, P7.
   - Idea: Replace the separate chase / swap / shift / alternate concepts with one "Move" control that has Rate (beat division), Width (lasers lit) and Fade (cut ↔ roll). Presets on top: "Chase", "Swap", "Alternate".

8. **Symmetry by pairing, not by programming.**
   - Source: Q-Shift DIV and destination order; MAtricks Wings.
   - Addresses: P5, P4.
   - Idea: A "Mirror" toggle on the rig strip folds the lasers into pairs from the outside in, so any chase becomes symmetric. The strip shows the pairs as linked brackets.

9. **Phase offset that appears only when there are 2+ lasers.**
   - Source: Modulaser Phase Spread.
   - Addresses: P7, P5.
   - Idea: Hide all spread and offset controls in single-laser rigs. Show "Offset across rig" as one knob once a second projector is in the rig strip.

10. **The rig lives with the app, not the show.**
    - Source: Modulaser.
    - Addresses: P4, P5.
    - Idea: The rig strip, safety zones and zone map defaults are saved in a "Venue" file. Shows never reference projector IDs. "Venue ▸ Load / Duplicate / Export". Importing never overwrites, and name clashes get "(imported)".

11. **Most recent target wins; clearing reveals the one underneath.**
    - Source: Modulaser output groups.
    - Addresses: P5, P6.
    - Idea: Zone map overrides form a visible stack, shown as chips: "Base: L/R split → Override: All". Releasing the top chip returns to the one under it, so the user never sees a mystery state.

12. **Two-step arm, with health shown before arming.**
    - Source: Modulaser.
    - Addresses: P8, P6.
    - Idea: A big ARM button turns into "CONFIRM (5 s)". Each projector tile shows OK / no packets / no IDN reply before you arm. Nothing emits until confirmed.

13. **A non-bypassable safety pipeline, and dropped inputs never hold light.**
    - Source: Modulaser remote; LaserOS (safety zones cannot be MIDI-mapped).
    - Addresses: P8.
    - Idea: Safety zones and the audience-scan limit sit after every effect and every input source. A momentary press from MIDI, OSC or a gamepad auto-releases if its device disconnects or goes silent for N seconds. Safety settings cannot be MIDI-learned at all.

14. **The status background colour tells the state from across the room.**
    - Source: LaserOS status bar; Showeditor's green frame on the active key.
    - Addresses: P1, P6.
    - Idea: A full-width top bar shows green = streaming to all lasers, amber = some lasers missing or overloaded, red = disarmed or blackout, purple = show locked. It is readable at 2 m.

15. **Count what is not at default.**
    - Source: grandMA3 Masters window ("1/4", Non-Default Only).
    - Addresses: P6, P7.
    - Idea: A "3 changed" chip on the Perform tab lists every fader, override, latched FX and zone override that is not at home. One button resets all of them.

16. **"Running, but not from this control" border.**
    - Source: QLC+ orange border; Lightkey preview bubbles.
    - Addresses: P6, P3.
    - Idea: A pad whose look is active because something else fired it (timeline, OSC, vibe auto-advance) gets a dashed outline. Laser thumbnails show small badges for what is acting on them: "FX", "Pump", "Zone override".

17. **Speed master with tap, ×2, ÷2 and reset.**
    - Source: grandMA3 Learn/DS/HS/Speed1; QuickShow space-bar tap; LaserOS Sync Beat.
    - Addresses: P1.
    - Idea: One BPM block with TAP, ×2, ÷2 and "1" (downbeat sync). An asterisk shows when ×2 or ÷2 is applied. All beat-based reactions and zone patterns follow it.

18. **Run-only locked screen.**
    - Source: ChamSys Execute window (View Max, password, Key Lock); grandMA Lock Executor.
    - Addresses: P8, P1.
    - Idea: "Show Lock" hides every edit tab and leaves only Perform. Unlocking needs a press-and-hold plus a PIN. A lock icon stays in the status bar.

19. **Smoothing on faders.**
    - Source: BEYOND Physics (mass-spring filter).
    - Addresses: P1.
    - Idea: Add a small, optional glide on the Intensity and Morph faders, so shaky hands or jumpy MIDI faders don't strobe the output. Show a ghost marker for the target value and a solid marker for the actual value.

20. **Freeze / mask instead of stop.**
    - Source: Avolites Mask FX; grandMA3 Stomp.
    - Addresses: P3, P8.
    - Idea: A held "FREEZE" action holds the current frame with all reactions stopped, then lets go. This is softer than blackout for song endings.

## 4. Anti-patterns and complaints

- **Hidden limiter rules.** Pangolin's own doc says that with QuickShow's limiters "it's not always easy to tell which Cue will stop when you click next one" ([Cue Groups](https://wiki.pangolin.com/doku.php?id=beyond:cue_groups)). Lesson: replacement rules must be visible in the layout (columns), not in a settings tab.
- **Invisible players.** A Pangolin developer says the trackless players "are almost invisible for us, and we can not operate with them directly". A user who wanted to swap centre and outer cues every 2/4/8/16 beats was sent to PangoScript and Zone effects, and it still did not work ([Zone Hot Swapping](https://forums.pangolin.com/threads/zone-hot-swapping.1921)). This app's built-in zone swap is a real advantage.
- **Silent failure after a settings reset.** A Reddit user found that their grid cues stopped working because "Workspace Group Settings were reset and the groups didn't include any used zones" ([r/Laserist](https://reddit.com/r/Laserist/comments/1ozx9ow/check_out_my_new_lasers_but_why_are_my_grid_cues), from a search snippet). This is a P6 case.
- **Effects that hide your hands.** In Avolites Overlay mode, a running shape means "changing colour using the wheels will have no visible effect" ([Shape Generator](https://manual.avolites.com/docs/effects/shape-generator/)).
- **Speed and phase drift.** Changing effect speed in BEYOND "create[s] a shift of the phase". Per-line speeds also leave global sync quietly, and only the Globe/Resync icon shows it ([Live Control](http://wiki.pangolin.com/doku.php?id=beyond:livecontrol)).
- **Controls that change meaning.** Showeditor can redefine its six effect sliders per key ([3.7](https://showeditor.de/en/manual/3-first-steps-quick-start/3-7-make-a-live-laser-show.html)). This makes it flexible, but in the dark the same fader does different things (our inference, not a user report).
- **Profiles with side effects.** Loading BEYOND's APC40 profile changes the grid to 8×6 ([forum](https://forums.pangolin.com/threads/apc40-midi-questions.13248), from a search snippet).
- **Surprise emission.** LaserCube Power-On Play starts the last playlist as soon as the interlock is ready ([Control modes](https://wiki.laseros.com/academy/control-modes)).
- **Per-cue zone wiring as the normal workflow.** BEYOND presents multi-selecting cues to edit Zone settings as a time-saver ([Workspace](https://wiki.pangolin.com/doku.php?id=beyond:workspace_grids_and_pages)). That means the base model ties cues to lasers.
- **Preview vs. output doubt.** Lightkey's first troubleshooting question is whether the fault is visible in the Preview ([Help](https://lightkeyapp.com/en/help), from a search snippet). Modulaser makes a point of using one pipeline for both ([Projection Mapping](https://modulaser.app/docs/guides/projection-mapping)).
- **Not verified:** Reddit threads "live busking with 14 lasers" and "Busking Tips?" exist, but they could not be read (403).

## 5. Open questions for the app

1. Should vibe roles become **visible columns with one-per-column exclusivity**, as in BEYOND Cue Groups, instead of named pools in a side panel?
2. Should a zone map be a **pressable cue**, either in the grid or as a sixth control, with a thumbnail? Or should it stay a separate selector?
3. Can chase / swap / shift / alternate collapse into one **Rate / Width / Fade (+ Mirror)** control without losing the step grid? Is the step grid an advanced editor only?
4. Do tap reactions need a **duration in beats** and a visible decay, so overlapping "impacts" can be read?
5. Where does the rig live: per show, or per **venue file** that shows never reference? How are lasers matched when the venue changes (IDN name, IP, or order in the strip)?
6. Should arming be **two-step with per-laser health**, separate from the blackout pad?
7. How long should a momentary MIDI/OSC/gamepad press survive when its device goes silent? Is the auto-release timer user-visible?
8. Should safety zones and the scan limit be **impossible to map** to any controller, as in LaserOS 2.2?
9. Is a "changed from default" counter plus a one-press "home" reset enough to cover P6, or does each laser thumbnail also need per-laser badges?
10. Is a global speed master with ×2/÷2 enough, or does a user ever need per-reaction speed? Pangolin's experience suggests per-line speed causes drift and confusion.
