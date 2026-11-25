# 09 — Unconventional control systems for a laser instrument

Research date: 2026-11-21. Scope: ways to perform light without a pad-and-fader screen, with evidence from real stages and from other fields. Facts have URLs. Items I could not check are marked **not verified**. (Web search ran out partway through, so some vendor claims could not be checked.)

## Summary

The control systems that hold up on stage share three traits. They give **fast, fixed latency** (under about 10 ms for rhythmic triggers, about 20 ms for smooth gestures). They have a **clear "armed" state**, so stray movement does nothing. And they **fail safe**: if the link drops, the light goes to a known state. Wired MIDI/USB and Wi-Fi OSC meet the latency bar. Bluetooth MIDI often does too, but it can take a long time to reconnect. Camera tracking is cheap and expressive. But depth cameras are blinded by the haze that laser shows need, so camera control belongs in hazeless zones, at the operator desk, or under an IR plan. Foot controllers are proven and reliable, but feet only do a few things well: stomp, hold, double-tap, and rock a pedal. Games give us the best ideas for making control feel good. They forgive timing (input buffering, coyote time), they make hits feel heavy (hit-stop), and they reward each input with several layers of feedback ("juice"). Live TV gives us the best safety idea: **preview/program**, where you stage the next look first, then "take" it. Laser rules (FDA variance, ILDA audience-scanning guidance) require a trained operator with an E-stop and scan-fail detection. So any gesture or crowd input must sit *upstream* of a human-held safety layer, never in place of it.

---

## 1. Phones and tablets as instruments

**Facts**
- TouchOSC is a modular control-surface toolkit. It sends MIDI and OSC over wired and wireless links at the same time, and it has a scripting engine. https://hexler.net/touchosc/manual/introduction
- Open Stage Control is a libre, modular OSC/MIDI controller. It runs as a server and you open the layout in a browser, so any phone can be a client. https://openstagecontrol.ammd.net/
- A forum note from users: "Expect more latency over wireless than wired networking." https://community.gigperformer.com/t/beginners-question-about-using-osc-on-gigs/2561
- A Reddit thread is titled "TouchOSC fails during live performance". The performer says it failed "when I need it the most". (I could only see the title and snippet. The cause is **not verified**.) https://www.reddit.com/r/TouchOSC/comments/1ien1dj/touchosc_fails_during_live_performance/
- IRCAM's 2025 review of wireless motion sensors in performance (Fléty & Bevilacqua) is the best single source here: https://pmc.ncbi.nlm.nih.gov/articles/PMC12526951/
  - Musical controllers need latency and jitter **under 10 ms**. Triggering from onsets needs under 10 ms. Slow, fluid continuous control can tolerate **up to 20 ms**.
  - Typical gesture streams run at 100–400 Hz. They are usually sent at 50–200 Hz.
  - On stage, Wi-Fi beats Bluetooth for two reasons: you can scan the venue and pick a clear channel, and it has more range and power. Old Bluetooth could take **10–30 s to reconnect** after a drop, "making reliable use in live performance nearly impossible". BLE 5 is better.
  - Wired MIDI 1.0 tops out at about 1,041 messages per second. That is about **10 sensors** at a 10 ms latency budget. OSC over Wi-Fi is preferred for many sensors and multiple users.
  - Raw accelerometer data mixes tilt (gravity) with motion, so it needs processing first. Useful derived signals: orientation, a "movement intensity" (always positive, zero when still), a "stillness" detector, and onset detection with a re-trigger gate.
- How performers hold them: **not verified** by a source. Common practice is landscape, two thumbs, phone on a stand or strapped to a wrist.
- Lightkey remote, Luminair, Lemur and Resolume touch layouts: **not verified** (pages could not be reached).

**Takeaway for us:** a phone is a fine *second surface* (macro pads, an XY "beam steering" pad, tilt for a filter). Run it on a dedicated Wi-Fi access point on a clean channel. Show a link-health light. If packets stop, hold or fade the output, never freeze it at full.

## 2. Feet and hands-free

**Facts**
- Morningstar MIDI foot controllers support "Action-Based Control". One switch sends different messages on **Press, Release, Double Tap or Long Press**. They can also jump banks and send tap tempo and MIDI clock. https://www.morningstar.io/
- Chauvet DJ Foot-C 2 is a 36-channel DMX foot controller with dedicated colour buttons, built for bands to run "chases, scenes, fades… with just the tap of a foot". https://www.sweetwater.com/store/detail/FootC2--chauvet-dj-foot-c-2-36-ch-dmx-foot-controller
- The dead-man's pedal is a classic foot control. The Wikipedia page notes a key failure: in the 2003 Waterfall train disaster, the driver died and his body *kept the pedal pressed*. Modern fixes are a **three-position pedal** (must stay in the middle) and a **vigilance timer** (release and re-press on cue). https://en.wikipedia.org/wiki/Dead_man%27s_switch
- Boss RC loopers and Chase Bliss pedals: **not verified** (no page fetched).

**What a foot does reliably (from the sources above plus common practice):** a stomp (a trigger), a hold (a momentary effect, like a "blackout while held"), a double-tap (a second function), a long press (a mode change), and a rocking expression pedal (one continuous value). Feet are poor at aiming, at pressing many small switches, and at fast sequences. Spacing and a solid click matter more than switch count.

## 3. Camera and body

**Facts**
- MediaPipe Hand Landmarker finds 21 3D keypoints per hand. On a Pixel 6 it takes **17 ms on CPU and 12 ms on GPU** per frame. It has adjustable confidence thresholds: detection, presence and tracking (default 0.5). In live mode it drops frames when busy so results stay current. https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker
  - Camera frame time adds to this. At 30 fps a frame is about 33 ms, so expect roughly **50–80 ms** from motion to output. (That is my estimate, **not verified**.) It works for sweeping gestures. It is too slow for tight beat triggers.
- There is a free, GPU-accelerated MediaPipe plugin for TouchDesigner (face, hand, pose), with master classes on building hand-tracked interfaces. https://derivative.ca/community-post/tutorial/hand-tracking-master-class-touchdesigner-mediapipe/71352
- **Haze kills depth cameras.** In the TouchDesigner forum, one practitioner says: "the smallest amount of haze will totally destroy your Kinect's vision". Haze shows up as "a very thick sometimes black volume". Others say low dry-ice fog *might* work, and everyone says to test with the real hazer. https://forum.derivative.ca/t/kinect-performance-with-fog-machine/261519
- Ultraleap cameras use their own IR LEDs. That means "they are not affected by dark lighting conditions, but performance can be affected by bright lights and/or reflective surfaces". https://docs.ultraleap.com/pico/tips-for-demos.html. Their range is short: about 80 cm on the Leap Motion Controller. https://docs.ultraleap.com/xr-guidelines/Getting%20started/design-principles.html
- Several Kinects aimed at the same spot interfere with each other. One fix is to vibrate the sensors ("Shake'n'Sense"). https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/shake27n27Sense.pdf
- Conductor interfaces: Max Mathews built the **Radio-Baton** plus a "Conductor Program" to interpret pre-written scores. You beat time and shape the expression, and the computer plays the notes. Soloists used it to play their own accompaniment. https://ccrma.stanford.edu/radiobaton/. The IRCAM review traces a line of baton-style controllers from it. https://pmc.ncbi.nlm.nih.gov/articles/PMC12526951/
- Notch body tracking, Orbbec cameras, and dancers driving lights: **not verified** this session.

**Takeaway:** use camera control where the camera sees the performer in clean air. Good spots are the operator's desk (a hand over a downward camera) or a hazeless DJ booth. Do not depend on stage-wide body tracking in heavy haze. The "conductor" model fits lasers very well: the show is pre-built, and the performer controls *tempo, intensity and accents*, not every cue.

## 4. Wearables and objects

**Facts**
- **Genki Wave** is a MIDI ring worn on the index finger. Over standard BLE MIDI its latency is about **7–20 ms**, or about 7 ms with a WIDI Master dongle. Genki claims under 1 ms with its own protocol. It reaches up to 10 m, and Softwave links up to 2 rings. https://genkiinstruments.github.io/general/faq/wave-for-music/. One user thread warns of connection problems ("Do not buy… until you read this"). **Details not verified.** https://www.reddit.com/r/synthesizers/comments/qk2fca/do_not_buy_genki_instruments_wave_until_you_read/
- Bluetooth MIDI facts from CME, a vendor, so possibly biased: Apple caps the BLE connection interval at **11.25 ms on iOS** and **7.5 ms on macOS**. Two dedicated dongles get about 3–10 ms. Jitter rises in group (one-to-many) mode. https://medium.com/@thomasgerbrands/the-truth-about-bluetooth-midi-54a3dc633052
- **MiMU gloves** (Imogen Heap) use flex sensors and IMUs. They send over Wi-Fi to Ableton or Logic. They are a full gestural instrument with gesture-to-mapping software. https://en.wikipedia.org/wiki/Mi.Mu_Gloves, https://mimugloves.com/gloves/
- **Reactable**: a camera under a translucent table tracks printed fiducial markers on blocks (position and rotation). It sends the data as TUIO over OSC. Rotating a block changes a value, and putting blocks near each other connects them. Björk toured with it in 2007. https://en.wikipedia.org/wiki/Reactable. reacTIVision is open source and comes with 216 printable markers. https://reactivision.sourceforge.net/
- **Midi Fighter 3D** has arcade buttons plus a gyro and accelerometer. Tilting it left, right, forward or back maps to effects. https://djtechtools.com/2012/02/27/introducing-the-midi-fighter-3d/
- Senstroke, Pocket Drum, Neova and Teenage Engineering modular setups: **not verified** (the Senstroke site failed to load).

**Takeaway:** tangible blocks on a lit table are a strong fit for lasers. Each block is a projector or effect, rotating it sets a parameter, and putting two blocks together links them. It is cheap (a webcam plus printed markers), it lives at the desk away from haze, and the audience can see what the performer is doing.

## 5. Crowd and multi-person

**Facts**
- **PixMob** wristbands receive **infrared** from transmitters built into wash fixtures or moving-head bodies. The "video" version beams position-dependent data, so the crowd becomes a low-res screen. At the 2014 Super Bowl that was 80,000 pixels. https://en.wikipedia.org/wiki/PixMob
- **Xylobands** use **radio**, with a range of about 250–300 m. They were first used at scale on Coldplay's 2011–12 tour. The band reported "initial troubles" in production rehearsals before they worked. Return rates for reuse averaged 86%. https://en.wikipedia.org/wiki/Xyloband
- **Live TV two-person workflow** (director plus technical director): it runs on spoken "ready / take" calls. "Ready camera one?… Take one." Graphics answers "Graphic ready." The TD stages a shot in preview, then takes it. https://www.videomaker.com/article/f1/17834-how-to-use-a-live-switcher/
- Phone voting, crowd-noise meters, DJ+VJ b2b hand-offs: no strong source found. **Not verified.**

**Takeaway:** crowd input should only change *safe, bounded* things: colour palette, which pre-approved look plays next, or intensity inside a capped range. It should never change beam position or power into the audience. For two people, copy broadcast: one person *proposes* (preview) and the other *takes* (program), with a spoken or on-screen "ready" state.

## 6. Other domains worth borrowing from

- **HOTAS (fighter cockpits).** Every vital switch sits on the throttle and stick, so the pilot never lets go. This helps under stress, turbulence and G-force. It is now standard in F-16, A-10 and Typhoon jets, F1 steering wheels, and drone ground stations. https://en.wikipedia.org/wiki/HOTAS. *For lasers:* a left-hand "throttle" (master intensity, plus thumb buttons for blackout and strobe) and a right-hand "stick" (beam position or zoom, trigger = hit).
- **Vision mixers.** These have a program bus (live) and a preview bus (next). A **T-bar** sets transition speed by hand, an **Auto** button runs a timed transition, a **Cut/Take** button switches instantly, and **Fade to Black** sits last in the chain. https://en.wikipedia.org/wiki/Vision_mixer. Blackmagic says the two-step preview/program process "means fewer mistakes". https://www.blackmagicdesign.com/products/atemmini
- **Fighting games.** Hitstop briefly freezes both characters on impact. In Smash, Sakurai scales the freeze to damage and even to which part of the sword hit. https://sourcegaming.info/2015/11/11/thoughts-on-hitstop-sakurais-famitsu-column-vol-490-1/. Hitstop also gives players a window to confirm a hit and buffer the next input. https://forums.supercombo.gg/d/601-hit-stop-frames. Motion inputs (quarter-circle, then button) work as "phrases". That maps to gesture-then-confirm on a laser rig.
- **Beat Saber.** Blocks sit on a 4×3 grid with a cut direction. Score depends on swing length, angle and accuracy. An energy bar forgives a few misses. "No Fail" mode exists. https://en.wikipedia.org/wiki/Beat_Saber. *For lasers:* direction-coded swings make good beat triggers, and a skill meter can unlock bigger looks.
- **Game feel (Steve Swink).** Game feel = "real-time control of virtual objects in a simulated space, with interactions emphasized by polish". https://en.wikipedia.org/wiki/Game_feel, https://gamifique.files.wordpress.com/2011/11/2-game-feel.pdf
- Tilt Brush, Patchworld, racing-game UX, and live sports graphics: **not verified** this session.

## 7. Safety for non-screen control

**Facts**
- **US law.** Light-show projectors above Class IIIa (5 mW visible) need an FDA **variance** before use. The show producer counts as a "manufacturer". https://www.fda.gov/radiation-emitting-products/home-business-and-entertainment-products/laser-light-shows
- **ILDA audience-scanning guidance** https://www.ilda.com/audiencescanningsafety.htm:
  - MPE (maximum permissible exposure) levels: **2.5 mW/cm²** for a static beam, **10 mW/cm²** for a continuously scanned beam, and the ILDA "10×" proposal of **100 mW/cm²**, which only applies under strict conditions.
  - Those conditions include a **scan-fail detection system** that cuts the beam within 10 ms (ideally 1 ms). They also require "a trained laser show operator who can use an **E-stop**" and who monitors the show continuously, plus signage.
  - A "hot spot" happens when the beam dwells too long in one place. Jumping between two points still counts as one. **Beam attenuation maps** cut power in audience zones.
- **Dead-man switches.** These cut power if the operator lets go. Their known failure is being held down by accident, or on purpose. The fixes are a three-position grip and vigilance prompts. https://en.wikipedia.org/wiki/Dead_man%27s_switch
- **The Midas touch problem.** Every gesture risks being read as a command. "Clutching" (an explicit engage action) is a proven mitigation. https://dl.acm.org/doi/10.1145/3743049.3743055. Research on smart rings compares clutch events to cut false positives. https://dl.acm.org/doi/10.1145/3780045.3780047
- **Confidence thresholds.** MediaPipe exposes detection, presence and tracking confidence settings. Raise them for safety-relevant gestures and accept more misses. https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker
- **Re-trigger gates.** An onset detector should ignore new onsets for a set window after each trigger. https://pmc.ncbi.nlm.nih.gov/articles/PMC12526951/
- Two-hand controls (as in ISO 13851, used on presses): **not verified**. The principle is that both hands must act within a short window for a dangerous action.

**Rules we should adopt:** (1) Gestures, phones and crowd input can only choose among **pre-approved, pre-measured** looks. (2) Anything that adds power or lowers beams toward the audience needs **arming plus a confirm**, like preview-then-take. (3) A physical, wired E-stop and a held "live" grip always win over every other input. (4) Losing the link, losing tracking, or low confidence leads to a defined safe state, never the last value.

---

## 20 control-system ideas for a laser instrument

Cheap prototype key: **P** = phone, **W** = webcam, **G** = gamepad, **M** = MIDI pedal or controller.

| # | Device / system | Performer does | Lasers do | Why it fits music | Setup | Risk | Cheap proto |
|---|---|---|---|---|---|---|---|
| 1 | **HOTAS rig** (flight throttle + stick) | Left hand rides intensity; right hand steers; trigger = hit | Throttle = master brightness; stick = beam position/zoom; trigger fires accents | Two hands, eyes up; builds and drops map to the throttle | Low | Low | G (any HOTAS/gamepad) |
| 2 | **Preview/Program + T-bar** | Stage the next look on preview, then pull the T-bar or hit Take on the downbeat | Crossfade speed follows the bar; Cut = instant | Transition speed becomes expressive; fewer mistakes | Low | Low (it is a safety gain) | M (any fader) |
| 3 | **Foot "stomp board"** (Morningstar-style) | Stomp = hit, hold = momentary blackout/strobe, double-tap = next scene, long press = mode | Accents and scene changes while the hands play elsewhere | Feet already keep time | Low | Low | M |
| 4 | **Expression-pedal "tension"** | Rock the pedal through a build | Opens beam count, speed and spread; heel = calm, toe = full | Builds are one long continuous gesture | Low | Low | M |
| 5 | **Conductor baton** (IMU stick or phone) | Beat time; bigger beats = more intensity; a held fermata freezes the look | Pre-built show follows your tempo; accents on your downbeats | Radio-Baton model: you interpret, the machine plays the notes | Medium | Medium (tempo drift) | P |
| 6 | **Phone tilt "beam sculptor"** | Tilt and roll a held phone; thumb holds a clutch button | Tilt aims or warps the shape only while the clutch is held | Clutch avoids Midas touch; feels like playing a theremin | Low | Low–Med (Wi-Fi) | P |
| 7 | **Shake-to-strobe** | Shake the phone or wristband in rhythm | Strobe rate and intensity follow movement intensity | Body rhythm becomes light rhythm | Low | Medium (fatigue, flicker limits) | P |
| 8 | **Desk hand-tracker** (webcam looking down, no haze) | Pinch = grab a beam; spread fingers = fan beams; fist = hold | Direct shaping of a live effect | Visible, theatrical, in clean air | Low | Medium (50–80 ms lag; misreads) | W |
| 9 | **Tangible table** (reacTIVision blocks) | Place a block = add a projector or effect; rotate = parameter; push blocks together = link | Each block maps to a zone or effect; links chain them | Spatial patching you can see; audience can follow | Medium | Low | W + printed markers |
| 10 | **Fight-stick combos** | Directional motion + button (e.g. ↓↘→ + punch) | Each "special move" fires a pre-built signature cue | Phrases fit musical phrases; buffered so they land on the beat | Low | Low | G |
| 11 | **Beat Saber lanes** (VR or tracked controllers) | Swing in a direction on the beat | The cut direction sets the beam sweep direction; accuracy scales the size | Direction-coded hits on the beat; skill shows | High | Medium | G (gyro gamepad) |
| 12 | **Ring on the index finger** (Genki-style) | Small wrist tilts while playing keys or decks | Subtle sway and colour drift; tap the ring = accent | Adds light to an existing instrument with no free hands needed | Low | Medium (BLE dropouts) | P on wrist |
| 13 | **Glove "poses"** | Hold a pose (fist, open, point) to arm, then move to perform | Pose picks the effect family; motion drives it | Pose acts as a clutch; very theatrical | High | Medium | W (pose classifier) |
| 14 | **Drum-pad / drumstick hits** | Hit pads or sensored sticks | Each drum maps to a projector; velocity = size or brightness | Drummers already move in sync with the music | Low | Low | M (any pad) |
| 15 | **Racing wheel + pedals** | Steer through the drop; gas = intensity; brake = pull everything in | Wheel rotates the whole scene; pedals set energy | Wheel has detents and force feel; easy for anyone to learn | Low | Low | G (wheel) |
| 16 | **Two-person "director + TD"** | LD proposes and calls "ready"; operator takes on cue | Same rig; roles split by bus | Broadcast-proven; supports b2b hand-off | Low | Low | P + M |
| 17 | **Crowd palette vote** | Audience phones pick a colour or next look from a short list | Only the palette or next look changes, within caps | Crowd ownership, with no risk to beam position | Medium | Medium (abuse, Wi-Fi load) | P (web page) |
| 18 | **Crowd noise meter** | Crowd cheers | Fills a "charge" bar; when full, unlocks the big finale look | Rewards the crowd; a natural build | Low | Low | P mic / USB mic |
| 19 | **Dead-man "live grip"** | Hold a grip button to keep audience-zone beams live | Release = audience zones blank at once; vigilance re-press | Safety as part of the instrument | Low | Lowers risk | M / G trigger |
| 20 | **Metronomic "breath" pad** (pressure pad or sensor) | Press harder over a phrase; release on the phrase end | Beam thickness and brightness "breathe" with the phrase | Phrasing, not just beats | Low | Low | M (aftertouch pad) |

---

## Game feel for light: 10 principles

1. **Anticipation.** In animation, a wind-up before the action tells the viewer what is coming (https://en.wikipedia.org/wiki/Twelve_basic_principles_of_animation). *Light:* a short pre-hit cue (a dim, a pull-in, a riser shimmer) in the half-beat before a drop. Let the performer arm it in advance and fire it on the beat.
2. **Squash and stretch.** Shapes deform to show force (same source). *Light:* on a hit, the shape briefly overshoots (expands about 10–20%) and settles. Use easing curves, not linear ramps. Easing curves are cited in the juice talk: http://www.robertpenner.com/easing/.
3. **Juice.** "A juicy game feels alive and responds to everything you do", with "cascading action and response for minimal user input" (Jonasson & Purho, GDC). https://www.youtube.com/watch?v=Fy0aCDmgnxg, https://www.gdcvault.com/play/1016487/juice-it-or-lose. *Light:* one stomp produces a flash, a beam burst, and a colour kick on the controller LEDs.
4. **Hit-stop.** A brief freeze on impact, longer for bigger hits (Sakurai). https://sourcegaming.info/2015/11/11/thoughts-on-hitstop-sakurais-famitsu-column-vol-490-1/. *Light:* on a big accent, freeze all motion for 60–120 ms (my estimate, **not verified**), then release. Stillness after motion reads as weight.
5. **Input buffering.** Remember a press made slightly early and run it on the first valid frame. https://gamedesign.gg/glossary/jump-buffering/, https://maddythorson.medium.com/celeste-forgiveness-31e4a40399f1. *Light:* a press up to about 1/8 note early snaps to the next beat. This is quantise-to-grid as a forgiveness feature, and it should be adjustable.
6. **Coyote time.** Still accept the jump shortly after leaving the ledge (Celeste). Same sources. *Light:* a press a few tens of ms *late* still counts as "on the beat" and fires right away, as if it were on time.
7. **Widened windows for hard moves.** Celeste gives harder moves bigger timing windows. "Everything is fudged a tiny bit in the player's favor." Same source. *Light:* hard gestures (a two-hand combo, a fight-stick motion) get looser recognition, so long as they map to safe looks.
8. **Feedback layering (polish).** Swink treats polish as the layer that sells the interaction. https://gamifique.files.wordpress.com/2011/11/2-game-feel.pdf. *Light:* every input confirms on three channels: the beam, a light on the controller, and a haptic buzz or click. Then the performer never wonders "did that register?"
9. **Hang time / soft peaks.** Celeste halves gravity at the top of a jump while the button is held. Same source. *Light:* while a pedal or button is held at the peak of a build, slow the motion and hold the brightness. Release drops it.
10. **Hitstop as a confirm window.** In fighters, hitstop gives time to confirm a hit and buffer a follow-up. https://forums.supercombo.gg/d/601-hit-stop-frames. *Light:* during the freeze, the performer can choose the follow-up look (a "combo"). If nothing comes, it falls back to a default.

---

## Anti-patterns and failures

- **Depth cameras in haze.** Practitioners report that small amounts of haze blind a Kinect. The same thread shows an install cancelling its fog machine rather than losing tracking. https://forum.derivative.ca/t/kinect-performance-with-fog-machine/261519
- **Relying on Bluetooth for critical cues.** Reconnects can take 10–30 s on older stacks (https://pmc.ncbi.nlm.nih.gov/articles/PMC12526951/). iOS and macOS add a connection interval of 7.5–11.25 ms (https://medium.com/@thomasgerbrands/the-truth-about-bluetooth-midi-54a3dc633052). Users also report connection trouble with BLE rings (**not verified**).
- **Shared, congested Wi-Fi.** Expect more latency than wired. A public thread reports TouchOSC failing mid-show (cause **not verified**). https://community.gigperformer.com/t/beginners-question-about-using-osc-on-gigs/2561, https://www.reddit.com/r/TouchOSC/comments/1ien1dj/touchosc_fails_during_live_performance/
- **Always-on gestures (Midas touch).** Every movement becomes a command. Use a clutch. https://dl.acm.org/doi/10.1145/3743049.3743055
- **Dead-man switches that can be held without the operator.** A slumped body held the pedal down in the Waterfall disaster. Kill cords get clipped to the boat instead of the person. https://en.wikipedia.org/wiki/Dead_man%27s_switch
- **Over-juicing.** A GDC Europe counter-talk argued that too much polish *reduces* immersion. https://www.gamedeveloper.com/design/video-indies-resist-the-urge-to-juice-it-or-lose-it-. For lasers, too much flash also eats into the safety and flicker budget.
- **Hot spots from "simple" effects.** A beam that jumps between two points is still a hazard. A working scan-fail system would also blank it, so the effect would just vanish. https://www.ilda.com/audiencescanningsafety.htm
- **Under-rehearsed crowd tech.** Coldplay's Xylobands had problems in rehearsals before the tour worked. https://en.wikipedia.org/wiki/Xyloband
- **MIDI 1.0 bottleneck.** Sending many continuous sensors over one DIN link breaks the 10 ms budget at about 10 sensors. https://pmc.ncbi.nlm.nih.gov/articles/PMC12526951/
- **Bright stage light vs IR trackers.** Ultraleap notes that bright lights and reflections degrade tracking. https://docs.ultraleap.com/pico/tips-for-demos.html

---

## Open questions

1. What is our real end-to-end latency budget (input → IDN packet → galvos)? Can a camera path ever be used for beat triggers, or only for continuous control?
2. Which inputs may *ever* affect audience-zone beams? Should the rule be "only the held live grip plus a pre-measured look"?
3. Do we build a preview/program model into the core engine, so every control system (phone, pedal, camera, crowd) feeds "preview" by default?
4. How do we show link health and tracking confidence to a performer who is not looking at a screen? Options include LEDs on controllers, a haptic pulse, or a dedicated "status beam" on a wall.
5. What quantise and forgiveness windows (buffer, coyote) feel right at 120–175 BPM? Should they be per performer?
6. Can we test MediaPipe hand tracking with laser beams in the camera view? Do the beams and their reflections confuse it? (**not verified**: no source found.)
7. Is IR illumination plus an IR-pass filter enough for tracking in light haze, or do we need a hazeless booth?
8. For two-person shows, how do we pass control between operators (b2b) without a jump in the output? A "ready/take" handshake? Matched levels before the swap?
9. What do regulators and venues require, for each country, about who holds the E-stop when control is gestural or shared?
10. Which of the 20 ideas should we prototype first with a phone, webcam, gamepad and MIDI pedal, and how will we measure "feel" (timing error, misfires per minute, performer ratings)?
