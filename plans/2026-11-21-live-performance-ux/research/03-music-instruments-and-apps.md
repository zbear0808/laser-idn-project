# 03 — Music instruments, controllers and apps: performance design

Research date: 2026-11-21. Scope: what live-music hardware and apps teach a laser busking UI.

## 1. Summary

Music gear has spent 20 years on the same problem as this app: one person, a few controls, a dark stage, and a loop that keeps running. The same few answers keep coming back. (1) **Every press has a declared behaviour**: momentary, latched or toggled, and either "now" or "on the next bar". The state is shown with a small, fixed light language: off = empty, dim = loaded, bright = active, flashing = queued, pulsing = playing. (2) **Complex changes become one gesture**: a modifier turns the pads into punch-in effects (Teenage Engineering, Koala), a pad fires a stored multi-parameter "scene" or a pressure-scaled "performance macro" (Elektron Analog Rytm), and one crossfader or XY pad morphs between two snapshots (Octatrack, Kaoss Pad, Maschine Lock, Drambo). (3) **Perform and edit are separate modes**, and the perform mode has a safety net: changes snap back on release or on exit, there is a temporary save/reload, and stop has panic tiers. (4) **Honesty lights** show when what you hear differs from what is stored (Ableton's "Back to Arrangement" button). The main user complaints are hidden button combos, values that jump when a knob takes over, snapshots you cannot trigger directly, effects you cannot scope to one part, and touchscreens you have to look at.

## 2. Per product

### Ableton Live (Session View, macros)
- Clip launch modes: **Trigger** (down starts, up ignored), **Gate** (down starts, up stops), **Toggle** (next down stops), **Repeat** (retriggers at the quantize rate while held). https://www.ableton.com/en/manual/launching-clips/
- **Launch quantization** per clip, or "Global". **Legato** mode makes a newly launched clip take over the play position of the previous clip in the track, so you can swap loops without losing sync. Same URL.
- **Follow Actions** decide what plays next (next, previous, any, other, **Jump** to a slot/scene) with chance A/B as percentages. Live 11 added Jump and scene follow actions. https://help.ableton.com/hc/en-us/articles/360019101360-Updates-to-Follow-Actions-in-Live-11 , https://ableton.com/en/release-notes/live-11
- Session clips override the Arrangement. A **Back to Arrangement** button "lights up to remind you that what you hear differs from the Arrangement". There is one per track too. https://www.ableton.com/en/manual/session-view/ , https://www.ableton.com/en/manual/live-concepts/
- Racks have up to **16 macros**. A **Rand** button randomises all mapped macros. Each macro can be excluded from randomise and/or from variations. **Macro Variations** store snapshots of the macros. https://www.ableton.com/en/live-manual/11/instrument-drum-and-effect-racks/ , https://help.ableton.com/hc/en-us/articles/360019103480-Macros-and-Variations-FAQ
- Clicking a variation only *selects* it. You launch it with the arrow icon or Enter. Recalling a variation turns off recorded automation. Same FAQ.

### Ableton Push 3
- Session pads: clip colour = has a clip; **flashing green = queued**; unlit = empty. Session Overview: white = the block you are viewing, green = clips playing in that block. https://www.ableton.com/en/push/manual
- "If you see a parameter in the display, the encoder above it will affect it." Encoders are touch-sensitive: touching one maps or reveals it. Automated parameters get a white dot. https://www.ableton.com/en/push/manual/ , https://cdn-resources.ableton.com/resources/pdfs/push-manual/3/2024-11-05/push3-manual-en.pdf
- Users say Push is built for Session View and is weak at Arrangement navigation. https://www.facebook.com/groups/abletonliveusers/posts/9563584513751760

### Novation Launchpad (Pro MK3, X, Mini MK3) and Launch Control XL
- Pad colour matches the clip colour on screen. Pressed clip **flashes green = queued**, **pulses green = playing**. Record: flashing red = queued to record, pulsing red = recording. https://userguides.novationmusic.com/hc/en-gb/articles/25494530115346-Launchpad-Pro-MK3-interface
- Flashing has a 50% duty cycle synced to MIDI clock: **one flash period = one beat**. Pulsing goes dark to full. https://fael-downloads-prod.focusrite.com/customer/prod/s3fs-public/downloads/LPP3_prog_ref_guide_200415.pdf
- Mode buttons: active mode lit green, available modes lit white. Three "Custom" modes can be edited in Novation Components. https://files.kraftmusic.com/media/ownersmanual/Novation_Launchpad_Mini_MK3_User_Guide.pdf
- Launchpad X "Stop Clip" overlays the bottom row with stop triggers that fire "at the end of the phrase". https://userguides.novationmusic.com/hc/en-gb/articles/23731420256018-Using-Launchpad-X-s-Session-mode
- Launch Control XL: 8 faders, 24 knobs with LEDs, 16 buttons. Each button row has two functions (Solo/Arm, Mute/Select), switched by the leftmost button. State is **dim = off, bright = on**, in one colour per function. https://fael-downloads-prod.focusrite.com/customer/prod/downloads/launch_control_xl_3_user_guide_v1_en.pdf , https://cdn.algam.net/pub/collections//documents_AE/NOVATION/Launch%20Control%20XL%20Getting%20Started%20EN.pdf

### Akai APC40 mkII / APC64 / MPC
- APC40 mkII: each track has a **Crossfade Assign (A|B)** button that cycles A, B, Off. The global crossfader then blends the A tracks against the B tracks. https://cdn.inmusicbrands.com/akai/attachments/apc40II/APC40%20mkII%20-%20User%20Guide%20-%20v1.0.pdf
- APC64: pads flash green while waiting for launch quantization, then pulse green while playing. It has eight touch strips. https://cdn.inmusicbrands.com/akai/APC64/APC64%20-%20User%20Guide%20-%20v1.0.pdf . Users like that touch strips "jump straight to a value". https://www.elektronauts.com/t/akai-professional-apc64-usb-controller-and-sequencer/201434?page=4
- MPC: **Note Repeat** works while held. Shift + Note Repeat *latches* it. **16 Levels** copies the last pad onto all 16 pads with rising velocity, tune or filter values. Q-Link knobs are touch-sensitive. https://m.media-amazon.com/images/I/819AiKysl1L.pdf . The MPC Live III touch strip has modes (Q-Link, Pad Level, Track Level, Touch FX, Notes, Pitch Bend, Mod). https://cdn.inmusicbrands.com/akai/MPC%20Live%20III%20-%20User%20Guide%20-%203.6.pdf

### Native Instruments Maschine
- **Lock** stores a snapshot of all parameters. Press again to update it. Extended Lock lists the snapshots and can **morph** between them with a set mode and time. https://docs.native-instruments.com/online-guides/maschine-mk3-manual/en/playing-on-the-controller
- The **Smart Strip** has four mode buttons (Pitch, Mod, Perform, Notes) and an LED bar showing the value. In Perform mode the effect "will sound as long as your finger is held on the Smart Strip". https://docs.native-instruments.com/online-guides/maschine-mk3-manual/en/quick-reference
- Users ask to automate lock morphs and trigger them from arrangements. https://community.native-instruments.com/discussion/630/automating-lock-snapshot-morph-settings-on-maschine-mk3

### Novation Circuit Tracks
- A queued scene's pad **flashes green**. At the end of the current pattern, the new scene starts "without losing sync". https://fael-downloads-prod.focusrite.com/customer/prod/downloads/circuit_tracks_user_guide_v3_en.pdf
- To chain scenes, hold the first pad and press the last. Everything in between lights green. Same URL.

### Polyend Play / Tracker
- Play Perform mode: select tracks with the Select pads. Each Perform pad holds a punch-in effect. **Hold = momentary, tap and release = latch**. Leaving Perform mode turns the effects off. Coming back re-activates the latched pads, and knob changes revert, "a safety net while performing live". https://polyend.com/manuals/play
- Tracker: Performance mode "by default all the tracks are off (this prevents unexpected things...)". Pattern changes are sequential (at the end of the phrase). Changes are discarded when you leave. https://www.allfordj.ru/upload/iblock/4a8/rukovodstvo_polzovatelya_polyend_tracker.pdf . It has a **Green mode** for playing, "in which making mistakes ... is less likely", and a **Red mode** for editing the FX. https://files.kraftmusic.com/media/ownersmanual/Polyend_Tracker_Manual_v1_5.pdf

### Elektron (Digitakt, Octatrack, Analog Rytm, Syntakt/Digitone)
- **Parameter locks**: hold a step, turn a knob, and that step gets its own value. The screen inverts the locked value and the step key flashes. https://synth.market/media/shop_items_docs/Elektron_Octatrack_MK2_Quick_Guide.pdf
- Digitakt **Fill**: hold [PAGE] for momentary fill. [YES]+[PAGE] = fill for one cycle, starting when the pattern loops. Releasing in a set order latches it. https://www.elektron.se/wp-content/uploads/2025/07/Digitakt-2-User-Manual_ENG_OS1.15A_250708.pdf
- Digitakt **temporary save / reload** pattern (FUNC+YES / FUNC+NO) works as a live undo point. https://manualslib.com/manual/1275776/Elektron-Digitakt.html?page=35 . One user reports a reboot overwrote the temporary save. https://elektronauts.com/t/issue-with-temporary-save/170599
- **Perform Kit** mode (Digitakt II, Digitone II): live tweaks carry across pattern changes and are not auto-saved. https://wired.com/review/elektron-digitakt-ii , https://reddit.com/r/Elektron/comments/1i9xsb6/psa_turn_off_performance_mode
- Octatrack: 16 scenes per part. Assign one to slot A and one to slot B. The **crossfader interpolates** between their locked values. Scene locks beat parameter locks. **FUNC+SCENE mutes a scene**, so you can prepare an effect and "throw it in" by unmuting. Holding a SCENE key lights the assigned scene red, the other slot green, and scenes with locks half-bright green. https://www.manualsdir.com/manuals/657165/elektron-octatrack.html?page=74 , https://jungkonzept.de/sound/octatrack-mk2-scenes-crossfader-planen/
- Octatrack **STOP+STOP** stops everything, kills the echo-freeze tail and sends all-notes-off. **CUE** sends a track to the headphones; "CUE MUTES TRACK" is an option. https://images.equipboard.com/uploads/item/manual/55843/elektron-octatrack-mkii-manual.pdf . A community reference says that while FUNC is held, several mute toggles apply together on release (not verified in the official manual). https://docs.google.com/document/u/0/d/1K39_3D0UcjgCqGfXt4BLIpHa1x00A6FFlEhKsZ98xGI/mobilebasic
- Analog Rytm **Scene mode**: 12 pads, each a fixed set of values, tap on / tap off. Dim blue = has a scene, bright blue = active. **Performance mode**: pads are "performance macros". Locks are *modulation depths*, applied "relative to the pressure applied to the pad", and shown green. https://elektron-software.s3.eu-west-1.amazonaws.com/firmware/Analog+Rytm+MKII+User+Manual_ENG_OS1.70_231122.pdf

### Teenage Engineering (OP-1, OP-Z, EP-133 K.O. II, TX-6)
- OP-Z punch-in: hold **shift + a piano key**. **Low octave = current track, high octave = the track group.** Can be recorded to a performance track. 12 effects (duck, filter sweep, loop, stereo, pitch, echo, ramp/fill, random...). https://teenage.engineering/guides/op-z/punch-in-effects
- OP-Z: **shift + colour dial = temporary tweak, which reverts on release**. Four colour dials (green, blue, yellow, red). Parameter pages are colour-coded. Triple-click an index button to lock that screen. **Stop while stopped = panic. Stop twice = "super panic"** (also clears audio buffers). https://teenage.engineering/guides/op-z/interface-overview
- OP-1: four colour encoders with fixed roles, for example envelope Blue=Attack, Green=Decay, White=Sustain, Orange=Release. https://teenage.engineering/_img/54b7f9bf8681400300255cab_original.pdf
- EP-133: holding FX turns the 12 pads into **pressure-sensitive punch-in FX that can be combined**. https://teenage.engineering/guides/ep-133/effects . Each pad's pressure axis is listed (for example beat repeat = length, tape stop = slow-down, LPF = cutoff). https://www.soundtech.co.uk/musicians-blog/our-guide-ep133-koii-sampler-composer-teenage-engineering.html
- TX-6: six channels, each with a fader, mute, solo and three knobs, plus 8 effects. Small, with limited controls. https://gearspace.com/threads/teenage-engineering-releases-tx-6-ultra-portable-mixer-and-audio-interface.1378402/post-15960717

### Roland SP-404 MKII, TR-8S; Korg Kaoss Pad
- SP-404 MKII: five effect buttons. **Hold VALUE + an effect button = "Effect Grab"**: the effect is on only while held. A BUS FX button swaps buses. **MUTE BUS** outputs only the effect sound. https://static.roland.com/assets/media/pdf/SP-404mk2_reference_v200_eng04_W.pdf
- TR-8S **Scatter**: 10 types × 10 depths. Hold MANUAL TRIG to engage it. It applies from the *second* cycle of the loop. https://static.roland.com/assets/media/pdf/TR-8S_Reference_eng01_W.pdf , https://rolandcorp.com.au/blog/tr-8-and-effects-part-1-onboard-effects
- Kaoss Pad KP3+: one XY pad drives each effect. **Hold** keeps the touch state. **8 program memories** switch effects instantly. **FX Release** gives a natural decay. **Pad Motion** records your gesture. https://www.korg.com/us/products/dj/kaoss_pad_kp3_plus , https://cdn.korg.com/us/support/download/files/06805ad7e1b204e48c5f891004c51d25.pdf?response-content-disposition=inline%3Bfilename%3DKC4_KAOSSPAD_OM_E1.pdf&response-content-type=application%2Fpdf%3B

### iPad/phone apps and Bitwig
- Koala Sampler: **16 live effects, tap and hold, all usable at once with multi-touch**. A HOLD button latches them. https://manual.koalasampler.com/mobile/3-quick-guide
- Loopy Pro clip states: playing = clip colour as the background; stopped = black; muted = dim colour; recording = orange-red; stopping = a dim "wiper" animation. https://loopypro.com/manual/ . Play/stop quantization is set globally, then per colour, then per clip, and can be overridden per action. https://loopypro.com/manual
- Drambo: one **Morph slider** moves "many parameters across multiple tracks". https://beepstreet.com/ios/drambo
- Patterning 2: a circular sequencer that shows all layers at once. https://idmmag.com/tech/tech_news/patterning-2-ios-based-advanced-drum-machine-sequencer
- Endlesss: you jam into a looper bar, then tap to commit it as a "rifff". https://musictech.com/reviews/endlesss . A reviewer found the inputs clunky. https://engadget.com/2020-03-31-endless-music-collaboration-app.html
- Animoog Z, Beatmaker, Groovebox: I found no specific UI facts worth citing. Not verified.
- Apple's guideline for touch targets is at least 44×44 pt (about 7 mm). https://developer.apple.com/design/human-interface-guidelines/buttons
- Bitwig: the 8 remote controls are "always colored in rainbow order... to help you mentally connect each particular hardware control with its ever-changing software assignment". They come in pages. **Select-4** uses one fader to blend four sources; there are also Vector-4 and Buttons modulators. https://www.bitwig.com/userguide/latest/midi_controllers , https://www.bitwig.com/userguide/latest/modulator

## 3. Patterns worth stealing

1. **One light language for state.** Off = empty, dim = loaded, bright = active, flashing (one period per beat) = queued, pulsing = running. *From:* Launchpad, Push, APC64, Rytm, Loopy Pro. *Pains:* P1, P6. *Idea:* every cue, effect, zone and pad uses these five states. A queued cue flashes on the beat, and a thin ring around it fills toward the bar line.

2. **Each pad declares its press type.** Trigger / gate / toggle / repeat. *From:* Ableton launch modes, MPC note repeat. *Pains:* P1, P3. *Idea:* a small glyph in the corner of each pad (▶ trigger, ▮ gate, ⇄ toggle, ↻ repeat). FLASH defaults to gate (lit while held). HIT in repeat mode retriggers on every 1/4 while held.

3. **Hold = momentary, quick tap = latch.** *From:* Polyend Play, Digitakt fill, Koala HOLD. *Pains:* P1, P3, P8. *Idea:* the VIBE and FLASH pads follow this rule. A latched pad shows a padlock and a solid outline. A momentary press shows only a fill while held.

4. **"Now" or "next bar" is visible.** One global quantize, with a per-item override, plus legato swap. *From:* Ableton quantize/legato, Circuit scene queue, Polyend Tracker sequential changes. *Pains:* P2, P5. *Idea:* a big "Q: 1 BAR" chip in the Perform header, cycled from the gamepad. Vibe and zone-map changes queue to the bar and flash until they land. Swapping cues inside a vibe keeps the chase phase (legato).

5. **Modifier turns the pads into punch-in impacts.** *From:* EP-133 FX, OP-Z shift + keys, SP-404 Effect Grab. *Pains:* P1, P3, P7. *Idea:* while ACCENT (or a sixth "FX" shift) is held, the other four pads become four impacts (punch, pump, strobe, scatter). Their labels swap on screen while the modifier is held, then snap back.

6. **Scope chosen by where you press.** *From:* OP-Z (low octave = this track, high octave = the whole group). *Pains:* P3, P5. *Idea:* an impact pad has two halves. The top half hits All lasers, the bottom half hits only the selected zone. The affected projector thumbnails flash in the same colour as the pad.

7. **Scene (absolute) vs performance macro (relative).** *From:* Analog Rytm. *Pains:* P3, P7. *Idea:* two words only. A **Look** sets values (colour = red, zone map = B), shown in blue. A **Push** adds a relative amount scaled by velocity or fader (+30% size), shown in green. Every reaction is one or the other.

8. **Two snapshots, one fader.** *From:* Octatrack scenes + crossfader, Maschine Lock morph, Drambo Morph, Bitwig Select-4. *Pains:* P1, P7. *Idea:* the Morph fader gets visible "A" and "B" end labels showing which looks sit there. Holding A or B plus tapping a cue assigns it. Above the fader, a mini preview shows the blend.

9. **Per-item A / B / Off assign button.** *From:* APC40 crossfade assign. *Pains:* P4, P5. *Idea:* every projector thumbnail has one chip that cycles L / R / Off (or A–D). That one chip is the *only* place you set groups, and the Morph fader can crossfade L against R.

10. **Prepare it muted, then throw it in.** *From:* Octatrack scene mute, Launchpad stop "at end of phrase", TR-8S scatter starting next cycle. *Pains:* P5, P1. *Idea:* arm a zone pattern (chase/swap) "dark". It shows dashed outlines on the projector strip until the DROP press, and then lands on the next bar.

11. **Tweaks snap back.** *From:* OP-Z shift + dial reverts on release, Polyend leaving Perform resets knobs, Digitakt temp save/reload, Perform Kit. *Pains:* P8, P6. *Idea:* a "RESET LOOK" action (double-tap VIBE) returns every live-tweaked value to the stored vibe. Any value that differs from storage shows a small "•" dot (like Push's automation dot).

12. **Honesty light when output ≠ plan.** *From:* Ableton Back to Arrangement. *Pains:* P6. *Idea:* an orange header bar saying "LIVE OVERRIDE: 3 changes not in timeline / vibe", with a one-press "back to plan". Also, any pad with no target is drawn hatched, never lit.

13. **Fixed colour per control, everywhere.** *From:* OP-1 and OP-Z colour encoders, Bitwig rainbow remote controls. *Pains:* P1, P7. *Idea:* HIT, ACCENT, FLASH, VIBE and BLACKOUT always have the same five colours in every tab, the MIDI LED feedback and the thumbnails. When a projector reacts to FLASH, its thumbnail border flashes in FLASH's colour.

14. **Panic tiers.** *From:* OP-Z stop / "super panic", Octatrack STOP+STOP. *Pains:* P8. *Idea:* BLACKOUT once = output dark (state kept). Twice within 0.5 s = dark *and* all latches, reactions and stuck keys cleared. The screen spells out which level you are at.

15. **A safe "green" perform mode.** *From:* Polyend Tracker Green/Red mode; its Performance mode starts with all effects off. *Pains:* P8, P7. *Idea:* Show Lock = green frame. Only the five pads and two faders respond, and editing needs a 2-second hold to enter "red".

16. **Randomise with exclusions; snapshots you can launch.** *From:* Ableton Rand + "exclude from randomization/variations". *Pains:* P2. *Idea:* a "Shuffle" button on a vibe re-picks pool cues and colours, but items with a pin icon (intensity, safety, zone map) are never touched. Good results can be saved as a new vibe with one press.

17. **Range-select to group.** *From:* Circuit (hold the first pad, press the last to chain). *Pains:* P2. *Idea:* in the cue grid, hold one cell and tap another. The whole range becomes a new vibe's hit pool, with a name prompt and no menu.

18. **Touch reveals the target.** *From:* Push and MPC touch-sensitive encoders. *Pains:* P3, P6. *Idea:* hovering over or touching a fader (or holding a pad without "firing", e.g. with Shift) highlights every effect and projector it drives, and dims the rest.

19. **Settings inherit: global → group → item.** *From:* Loopy Pro (global, colour, clip). *Pains:* P2, P5, P7. *Idea:* the tap reaction and quantize are set once per vibe. A cue only shows a setting when it overrides the vibe, marked "≠".

20. **One XY or pressure control for a complex effect.** *From:* Kaoss Pad, EP-133 pressure, Rytm pressure macros. *Pains:* P1, P3. *Idea:* an optional "Impact XY" (gamepad stick or touch). X = spread across zones (centre to all), Y = strength. Release decays like Kaoss "FX Release" instead of cutting.

## 4. Anti-patterns / what users complain about

- **Hidden button combos and menu diving.** Elektron owners still "accidentally erase stuff while jamming". https://gearspace.com/threads/what-makes-elektron-gear-so-appealing.1099213 . A long thread says "Elektron interfaces are unnecessarily difficult". https://elektronauts.com/t/elektron-interfaces-are-unnecessarily-difficult/168607?page=10
- **Unclear priority between layers.** Octatrack users were surprised that scene locks override parameter locks even when the fader is still. https://www.elektronauts.com/t/bug-scene-locks-are-overriding-parameter-locks/3289 . Another thread: "Scenes – why do I find them so awkward?" https://elektronauts.com/t/scenes-why-do-i-find-them-so-awkward/145405?page=2
- **Values jump** when a controller takes over a parameter, and takeover modes do not always work. https://forum.ableton.com/viewtopic.php?t=252671 , https://forum.ableton.com/viewtopic.php?t=201772
- **Snapshots you cannot trigger directly.** At Live 11 launch, individual Macro Variations could not be MIDI-mapped. https://cdm.link/monster-cheat-sheet-whats-new-in-ableton-live-11-and-how-to-start-using-it/ . Clicking a variation only selects it, which confuses people. https://help.ableton.com/hc/en-us/articles/360019103480-Macros-and-Variations-FAQ
- **Effects you cannot scope.** Koala users ask how to put performance effects on one sample rather than the master. https://www.reddit.com/r/KoalaSampler/comments/189h7g8/effects_on_single_samples
- **Scene launch fires things you did not want.** For example, a riser launches with every scene. https://reddit.com/r/ableton/comments/1of5fes/scene_launch_question . A third-party Launchpad script disables scene buttons "to prevent accidental" launches. https://microwhat.com/product/launchpad-mini-mk3-live-script
- **Lost live changes and confusing modes.** "PSA: Turn off performance mode": Perform Kit changes are lost when you leave the mode. https://reddit.com/r/Elektron/comments/1i9xsb6/psa_turn_off_performance_mode . Another user calls it "an overly complicated way to achieve something simple". https://elektronauts.com/t/digitakt-ii-kits-question/212407?page=7
- **Touchscreens make you look.** "No tactile response and having to look at what your fingers are touching. Not ideal for live gigging." https://forums.musicplayer.com/topic/164596-ipad-synth-apps-now-better-still-not-atractive-for-you . Another user says you have to hover over the screen instead of resting a finger on a button. https://reddit.com/r/synthesizers/comments/6s3eeu/the_more_i_watch_demoes_of_ipad_apps_the_more_i
- **Hardware crossfader jumps at its end stop.** https://elektronauts.com/t/crossfader-issue/3213
- **Snapshot morphs cannot be sequenced or automated** (Maschine). https://community.native-instruments.com/discussion/630/automating-lock-snapshot-morph-settings-on-maschine-mk3

## 5. Open questions for the app

1. Should there be one global quantize ("Q: bar / beat / now") or one per pad role? The music precedent is global plus a per-item override.
2. Is a held modifier (a sixth button) acceptable within P1's limit of 5 buttons? Or should ACCENT double as the "FX shift" (hold = shift, tap = accent)? Note this clashes with pattern 3.
3. Should reactions be split strictly into absolute **Looks** and relative **Pushes** (the Rytm model), and can the existing reaction types (punch, pump, colour step, flip, scatter, strobe) be sorted into those two?
4. Should the Morph fader become an A/B snapshot crossfader (Octatrack) and replace the current "Morph" meaning, or sit next to it?
5. Can the projector strip be the *only* place where groups are set (one L/R/Off or A–D chip per projector, APC40 style), so the second place you tick projectors goes away (P4)?
6. What does "snap back" mean for a laser show: revert at the next bar, on release, or only on RESET? Does it hold across a vibe change?
7. Panic tiers: does BLACKOUT-twice clear *everything*, including zone maps, or only reactions and latches? How is that shown when the room is dark?
8. MIDI feedback: should the app drive controller LEDs with the same five-state light language (flash = one beat), so the hardware and the screen match (P6)?
9. Touch support: if a tablet perform view is planned, use targets of at least 44 pt (about 7 mm) and much larger for a dark stage. Which controls must also exist as physical buttons?
10. Is "Shuffle vibe" with pinned exclusions enough to make building vibes take minutes (P2), or is range-select grouping the bigger win?
