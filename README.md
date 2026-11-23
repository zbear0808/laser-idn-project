# Laser Show (i'm bad at naming things)

A Clojure project for controlling laser shows with multiple projectors using the ILDA Digital Network protocols. Features a launchpad-style interface, multi-input support (keyboard, MIDI, OSC), and a vibe mode for busking with five keys and two faders


<img width="2880" height="1800" alt="{DC4D3125-9F45-4BF8-9EB9-6AF6D73751A2}" src="https://github.com/user-attachments/assets/fe47f5a2-5038-4c44-8fd7-57c76548429f" />


## Table of Contents

- [Animation System](#animation-system)
- [IDN Protocol Support](#idn-protocol-support)
- [Input System](#input-system)
- [Vibe Mode](#vibe-mode)
- [Testing](#testing)
- [Current Work](#current-work)
- [Non-Goals](#non-goals)
- [Future Possibilities](#future-possibilities)
- [License](#license)
- [Contributing](#contributing)


### Installation Steps

1. **Download** the appropriate file for your system from the [releases page](https://github.com/zbear0808/laser-idn-project/releases)


### Troubleshooting Downloads
I partially tested mac or linux builds, they're just generated from my github action, almost all of my dev testing is done on windows.

→ Try the universal JAR: `java -jar laser-show-X.X.X-standalone.jar`
this should work regardless of your OS, but you'd need JDK 25+ installed (see [macOS Setup Guide](docs/MACOS_SETUP.md) or [Linux Setup Guide](docs/LINUX_SETUP.md)).

## Animation System

The laser show uses a frame-based animation system with the following core types:

- **LaserPoint**: Individual point with X/Y coordinates [-1, 1] and RGB color [0, 1.0]
- **LaserFrame**: Vector of points representing a single frame

In a geometric effect’s **Advanced** section, **Clamp after this transform** is on by default. Turn it off to let later transforms recover coordinates outside `[-1, 1]`. For example, an X coordinate of `0.75` scaled by 2 and then by ½ ends at `0.5` with the first clamp on, or `0.75` with it off. The finished cue is bounded after its group, track and global effects; the projector’s calibration chain also finishes bounded, before its safety zone is applied.

## IDN Protocol Support

Implements ILDA Digital Network Hello Protocol and IDN Stream Protocol:

**IDN Hello Default Port**: UDP 7255

Frame streaming with X/Y coordinates (16-bit) and RGB color (16-bit each). See [`src/laser_show/backend/idn_stream.clj`](src/laser_show/backend/idn_stream.clj) and [`src/laser_show/backend/streaming_engine.clj`](src/laser_show/backend/streaming_engine.clj).

## Input System

Keyboard, MIDI, OSC and gamepad controls use the same input [router](src/laser_show/input/).

To play a cue from your keyboard, right-click its cell, choose **Configure Triggers...**, click **Learn Next Input**, and press a key such as **1**. Choose **While held (momentary)** before learning if the cue should stop on release. Bindings are saved with the show. **Settings → Keyboard** turns keyboard triggers on or off for the session.

Keys work in the main window and non-modal editors. Text fields keep what you type, and a focused editor keeps the keys it edits with: for example, Space still toggles a step in the step grid. Elsewhere a bound Space overrides Play/Stop. Escape cannot be bound; Ctrl/Cmd/Alt shortcuts keep their usual behavior. Keys follow your keyboard layout, Shift does not create a separate binding, and leaving the window releases held keys.

## Vibe Mode

Busk a show with five keys and two faders. Cues are grouped into **vibes**, each a feel ("Calm", "Groove", "Peak"): a pool of cues for each key, an optional bed underneath, and an FX set of effect cells that switch on with the vibe. A key means the same in every vibe, only what is behind it changes, and a pool never plays the same cue twice in a row. Set up on the **Vibes** tab, play on the **Perform** tab (with the mouse, a touchscreen, or learned keyboard, MIDI, OSC or gamepad controls). See [`src/laser_show/vibes/`](src/laser_show/vibes/).

| Key | Press | Release |
|---|---|---|
| **HIT** | the next cue of the vibe's HIT pool; taps the tempo | a quick tap (under 150 ms) decays after the vibe's beats (1 by default); held longer, it stops |
| **ACCENT** | the same from a second pool, layered over HIT | the same (2 beats by default) |
| **FLASH** | the vibe's flash cue and flash effects; a solo flash steps the others aside | the look returns |
| **VIBE** | – | a tap goes to the next vibe; a hold of 0.6 s locks the vibe |
| **BLACKOUT** | everything dark, the other keys ignored; press again to come back | – |

| Fader | |
|---|---|
| **1 Intensity** | master brightness (the Master effect cell a new project has); full until moved |
| **2 Morph** | whatever the vibe wires to it (**Add Morph to** on the Vibes tab: size, spin or colour); nothing until wired |

**Setup:** make cues on the Grid tab → on the Vibes tab add vibes (**New vibe**, or **One per grid row**) and click cells into each slot → **Learn all** and press your five keys in order, and **Learn** each fader and move it → play on the Perform tab. The tab's checklist says what is left, its lint what to fix (the tab's label counts the errors).

**Tap reactions:** a press can also move effects. Each role's **Tap reactions** menu adds an effect cell to the vibe that follows its presses: Punch, Pump, Step colour or Step spin on HIT, Flip or Scatter on ACCENT, Swell or Strobe while FLASH is held, Fade in on a vibe change, and Energy, which grows the look as you tap faster. They are ordinary modulators you can edit. The zone reactions move pictures between places instead: **Zone hop** puts the All picture on the next laser at each HIT, **Zone swap** and **Zone shift** trade what the cues draw into zones A and B (A to D) at each ACCENT, and **Zone chase** runs the All picture across the lasers, a laser a beat, while FLASH is held. They are zone patterns you can edit; the hop and the chase need a laser that's on.

**Auto-advance:** with **AUTO** on (Perform tab, off by default), a vibe that has played 45 s changes to the next at a phrase gap (no key down, two beats without a HIT), or when FLASH is let go. A locked vibe stays.

**Safety:**
- A cue that can reach the audience (the Crowd Scanning zone group, or a projector tagged crowd-scanning) is never picked unless the vibe ticks **Allow audience cells**. Perform then says AUDIENCE ALLOWED, and AUDIENCE on each pad that may play one. Unticking it stops such a cue at once. Nor does a vibe switch on an effect cell whose zone pattern can send pictures there (Perform says which), and a new Zone hop or Zone chase leaves out a laser that scans the audience.
- **BLACKOUT** is the one sure way to dark.
- **SHOW LOCK** (Perform header) refuses what would change the show under you until you unlock it: moving, clearing or pasting cells, undo and redo, New, Open and Save, vibe edits, and projector and zone group changes. The pads, BLACKOUT, Stop, Play, grid clicks, the tempo, streaming and learning a key always work, and Exit still asks about unsaved changes.
- A key held 60 s is let go of as stuck, and its pad says so. MIDI All Notes Off (CC 123), All Sound Off (CC 120) and disconnecting a MIDI device let go of every key.
- Playing while nothing streams shows **NO OUTPUT** on Perform.

### Vibe mode cheat sheet

```
HIT       tap: a cue that decays    hold: plays until you let go
ACCENT    the same from a second pool, on top of HIT
FLASH     hold for the big moment; let go and the look returns
VIBE      tap: next vibe            hold 0.6 s: lock the vibe
BLACKOUT  dark, every other key off; press again to come back

Fader 1   Intensity (full until moved)
Fader 2   Morph (what this vibe wires to it)

Pads: blue = held · red = BLACKOUT on · orange border = learning ·
dim = no vibes, or BLACKOUT · AUDIENCE = may reach the crowd ·
the bar under a pad flashes on each press
```

## Testing

Run the unit tests (add your platform alias: `:win`, `:mac`, `:mac-arm` or
`:linux`). This skips the TestFX UI namespaces and wall-clock timing tests:

```bash
clj -M:win:test
```

Run one unit test namespace:

```bash
clj -M:win:test -n laser-show.events.grid-handlers-test
```

Other test aliases:

```bash
clj -M:win:test-integration   # test/integration only
clj -M:win:test-all           # unit + integration
clj -M:win:test-timing        # timing-precision tests (OS/load sensitive)
```

Every test alias takes name patterns: only tests whose `namespace/name`
contains one of them (ignoring case) run. `--list` shows what would run.
A pattern that matches nothing fails instead of running 0 tests.

```bash
clj -M:win:test history          # unit tests with "history" in the name
```

The TestFX UI tests live in `test/laser_show/ui_test/`, one namespace per
feature (toolbar, main window, grid, trigger editor, menu bar, preview, cue
chain list, timeline editor). Add `:test-ui-headless` to run them in the
background: no windows, no mouse movement, no display needed. It uses the
headless platform built into JavaFX 26+, so it works the same on Windows,
macOS and Linux (and in CI):

```bash
clj -M:test-ui:win:test-ui-headless --list        # list every UI test
clj -M:test-ui:win:test-ui-headless tap-tempo     # one test, by part of its name
clj -M:test-ui:win:test-ui-headless -n laser-show.ui-test.toolbar-test   # one feature area
clj -M:test-ui:win:test-ui-headless               # whole UI suite
```

Drop `:test-ui-headless` to watch the tests run in real windows (this moves
your mouse). On macOS, visible runs need Accessibility permission for your
terminal/IDE (System Settings → Privacy & Security → Accessibility), or the
OS silently drops the robot's clicks.

When a UI test fails, a screenshot is saved to
`test-results/<namespace>/<test>.png`.

**Test Suite is pretty bad**

Decent coverage of IDN protocol implementation, packet logging

ok coverage of everything else.
 See [`test/`](test/) directory for test files.

## Current Work
general performance improvements for frame generation



## Non-Goals

This project intentionally does not include the following features:

### High-Quality / Realistic Visualization

Real-time, photorealistic laser visualization is computationally expensive and would be better suited as a standalone application. The current preview is already the highest cpu user in the app, so i don't want to make it worse. 

**If you need visualization**: Check out the [IDN Tools project](https://gitlab.com/laser_light_lab_uni_bonn/idn-npp/idn-tools) which includes visualization tools for the IDN protocol.

### FFT / Live Audio Analysis

Live audio analysis (FFT) is typically noisy and unreliable for direct show control. The signal requires heavy smoothing and filtering to be usable, which is better handled by specialized tools. 

**Instead**: Use external applications (Python scripts, TouchDesigner, Max/MSP, etc.) to analyze audio and send pre-processed OSC or MIDI cc to this application. This separation of concerns allows you to use the best tool for audio analysis while keeping this application focused on laser control.
also, if you're looking for live audio bpm syncing use [WLEDAudioSyncRTBeat](https://github.com/zak-45/WLEDAudioSyncRTBeat)  

### Live Audio Playback

 This is because features that i want to implement down the road like timecode syncing and tempo adjustment become MUCH harder when you also have to deal with an audio stream, and that's not even considering various formats' audio decoding. 

**Instead**: Use existing tools that can already play various audio formats with tempo adjustment (TouchDesigner, Max for Ableton Live) to handle audio playback and send timecode (MIDI timecode or LTC) to this application. This app will sync to the incoming timecode and/or MIDI messages, (note we don't have a timeline feature yet, but it's planned)

## Future Possibilities

The following feature is planned for future development but is not currently being worked on due to its complexity:

### Timeline System

A timeline system for fully pre-recorded shows:

- **Timecode Integration**: Full support for MIDI timecode (MTC) and LTC timecode
- **Seeking/Scrubbing**: Navigate within the timeline, jump to specific cues
- **Pre-compilation**: Option to pre-render all IDN frames for maximum stability during playback
- **Live Recording**: Record MIDI/OSC inputs to record triggered cues and modified effect parameters and play them back
- **Beat Quantization**: Snap cue triggers to beat boundaries when recording to timeline



**Status**: the timeline editor works: tracks and clips, play/pause/seek/stop from the
editor (Space plays from the cursor), timelines stop at their end unless they loop, a
lockable per-timeline tempo, and a timecode readout and ruler (`Bars | Time`). A timeline
can follow incoming MIDI timecode (inspector → "Follow timecode: MTC"; beat 0 sits at its
"Starts at" timecode) from any connected, enabled MIDI input. LTC isn't supported; use an
LTC→MTC converter.
Open it from a grid cell's menu ("Create Timeline Cue" / "Open in Timeline Editor"), or through the REPL:

```clojure
(laser-show.events.core/dispatch!
  {:event/type :ui/open-dialog
   :dialog-id :timeline-cue-editor
   :data {:col 0 :row 0}})
```



For more help, see [`QUICKSTART.md`](QUICKSTART.md).

## License

agplv3

## Contributing

This is an independent project. Feel free to fork and modify for your needs.
