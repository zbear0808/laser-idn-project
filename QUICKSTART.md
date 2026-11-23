# Quick Start Guide

## Installation

1. **Install Java** (if not already installed)
   - Download from https://adoptium.net/
   - JDK 25+ is required (for JavaFX 28-ea compatibility)
   - **macOS setup:** See [docs/MACOS_SETUP.md](docs/MACOS_SETUP.md) for full guide (Homebrew, Temurin, Apple Silicon/Intel).
   - **Linux setup:** See [docs/LINUX_SETUP.md](docs/LINUX_SETUP.md).

2. **Install Clojure CLI tools**
   - **macOS:** `brew install clojure/tools/clojure` (see [docs/MACOS_SETUP.md](docs/MACOS_SETUP.md))
   - **Windows:** https://clojure.org/guides/install_clojure#_windows or `scoop install clojure`
   - **Linux:** See [docs/LINUX_SETUP.md](docs/LINUX_SETUP.md)


## Running the Applications

### Platform Selection

JavaFX requires platform-specific native libraries. Add your platform alias:
- **`:win`** - Windows
- **`:mac`** - macOS (Intel)
- **`:mac-arm`** - macOS (Apple Silicon)
- **`:linux`** - Linux

### Option 1: Laser Show (GUI Application)

```bash
# Windows
clj -M:win:laser-show

# macOS (use :mac-arm on Apple Silicon)
clj -M:mac:laser-show

# Linux
clj -M:linux:laser-show
```

This launches a graphical interface.

**Usage:**
1. The **Grid** tab is the performer's screen: 8 × 4 pads, one cue each (a new project starts with a row of shapes). Click a pad to play it and again to stop it; the preview beside the grid shows what goes out.
2. Double-click a pad, or right-click it and choose Edit Cue... (Create Cue on an empty pad), to pick its presets and effects. Drag a pad onto another to move it.
3. In **Projectors**, click **Scan Network** to find the IDN projectors on your network and tick **On** on the cards of the outputs to use (found ones start off), or **Add Manual...** to type one's address. Drag the cards into the order the lasers stand in, left to right: Left, Right, Center and the other automatic groups follow that order. Then select a card and draw its **Safety Zone**: drag the green limit and add red blocked areas where the light must never go; with Stream on, **Show test pattern** draws the zone on the laser so you can check it.
4. Click **Stream** in the toolbar to send the show to the enabled projectors. **Stop** blacks the output out and keeps the cues; **Play** brings them back. Space is the default Play/Stop shortcut unless you've bound it to another action.

The **Perform** tab has five big pads for playing vibe mode from across the room.

### Vibe mode in ten minutes

Vibe mode plays your cues with five keys and two faders (see [Vibe Mode](README.md#vibe-mode) in the README). A new project already has two vibes over the starter shapes, so you can try the Perform tab's pads at once. To set up your own, follow the checklist at the top of the **Vibes** tab:

1. **Make cues on the Grid tab.**
2. **Add a vibe** on the Vibes tab (**New vibe**), or click **One per grid row** for a vibe from each row of the grid that has cues. Name it after a feel, and pick its colour and energy.
3. **Put HIT cues in every vibe:** pick a slot above the grid picker (HIT, ACCENT, FLASH cue, BED, FX set, FLASH effects) and click cells to put them in or take them out. 3–6 cues for HIT, one to three bigger ones for ACCENT.
4. **Learn the five controls:** click **Learn all**, then press a keyboard key or controller button for HIT, ACCENT, FLASH, VIBE and BLACKOUT, in that order. Under FADERS, click **Learn** for Intensity (and Morph) and move the fader. Keyboard keys bind to pads and cues, not faders.
5. Optionally, add **Tap reactions** to a role (a Punch on HIT, a Swell on FLASH…), and **Add Morph to** the vibe to give fader 2 something to do in it.
6. On the **Perform** tab, click **Stream** in the toolbar and play:
   - **HIT** and **ACCENT**: a tap plays a cue that decays after a beat or two; hold to keep it until you let go.
   - **FLASH**: hold for the big moment.
   - **VIBE**: tap for the next vibe; hold 0.6 s to lock it.
   - **BLACKOUT**: dark until you press it again.
   - **Intensity** is the master brightness; **Morph** is whatever the vibe wires to it.
   - Turn on **AUTO** to change vibe by itself at the next phrase gap after 45 s.
7. Before the doors open, turn on **SHOW LOCK** in the Perform header: until you unlock it, nothing moves, clears or undoes the show by accident, and the pads, BLACKOUT, Stop and Play still work.

If you use VS Code with Calva, starting a REPL should automatically launch the app and give you a REPL in the `user.clj` namespace.

## Development Workflow

### Start a REPL for Interactive Development

The aliases are composable - combine them as needed:

```bash
# Basic development (Windows)
clj -M:dev:jfr:laser-show:win

# Development (macOS)
clj -M:dev:jfr:laser-show:mac

# Development (Linux)
clj -M:dev:jfr:laser-show:linux

# REPL-only (no app, just nREPL server - needs platform)
clj -M:win:repl
```

**What each alias provides:**
- **`:win`** / **`:mac`** / **`:mac-arm`** / **`:linux`** - Platform-specific JavaFX natives (required)
- **`:laser-show`** - JVM opts (ZGC, native-access) + main entry point
- **`:dev`** - Dev mode flag, async-profiler support, extra dev dependencies
- **`:jfr`** - Java Flight Recorder continuous recording (5min rolling buffer)

### VS Code with Calva

1. Ctrl+Shift+P → "Calva: Start a Project REPL and Connect (Jack-In)"
2. With this repo's configuration, Calva should automatically choose "deps.edn" and select the right aliases

### Test Laser Show in REPL

```clojure
;; Load and start the application
(start)
```

### Profiling

For performance analysis, see [docs/PROFILING.md](docs/PROFILING.md).

**Quick JFR profiling:**
1. Start with `:jfr` alias: `clj -M:dev:laser-show:jfr:win` (use your platform alias)
2. Open JDK Mission Control and connect to the running JVM
3. The "continuous" recording is already running with 5-minute history
4. Dump recordings from REPL:
   ```clojure
   (require '[laser-show.profiling.jfr-profiler :as jfr])
   (jfr/dump-recording "my-recording.jfr")
   ```

### Running Tests

```bash
# Unit tests (always add your platform alias)
clj -M:win:test

# One namespace
clj -M:win:test -n laser-show.events.grid-handlers-test
```

See the Testing section of [README.md](README.md) for UI, integration and timing tests.

### Building Uber JARs

```bash
# Build platform-specific JAR (pass platform as argument)
clj -T:build uber :platform :win      # Windows JAR
clj -T:build uber :platform :mac      # macOS Intel JAR
clj -T:build uber :platform :mac-arm  # macOS Apple Silicon JAR
clj -T:build uber :platform :linux    # Linux JAR
```

The JAR will be created at `target/laser-show-<version>-standalone.jar`, where
`<version>` comes from the `VERSION` file (also used for installer versions).

## Troubleshooting
btw, i've mostly tested on Windows, if you're not on it maybe it'll cause issues

### Windows 10 - VC++ Redistributable
If you are on Windows 10 and experience issues launching the application, you may need to install the latest [Microsoft Visual C++ Redistributable](https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist?view=msvc-170).

### "clj: command not found"
- Clojure CLI tools are not installed
- Install from: https://clojure.org/guides/install_clojure

### "Unable to resolve symbol"
- Dependencies not downloaded
- Run: `clj -P` to download all dependencies


## Support

This is an independent project. For issues or questions:
1. Post something in the Discussion
2. Test in a REPL for interactive debugging
3. Once you have repl commands to reproduce the issue then feel free to post an issue on github
   - i would also gladly accept a PR
