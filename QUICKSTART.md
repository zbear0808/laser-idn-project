# Quick Start Guide

## Installation

1. **Install Java** (if not already installed)
   - Download from https://adoptium.net/
   - Minimum version: JDK 25

2. **Install Clojure CLI tools**
   - Windows: https://clojure.org/guides/install_clojure#_windows
   - Or use: `scoop install clojure`


## Running the Applications

### Platform Selection

JavaFX requires platform-specific native libraries. Add your platform alias:
- **`:win`** - Windows
- **`:mac`** - macOS
- **`:linux`** - Linux

### Option 1: Laser Show (GUI Application)

```bash
# Windows
clj -M:win:laser-show

# macOS
clj -M:mac:laser-show

# Linux
clj -M:linux:laser-show
```

This launches a graphical interface.

**Usage:**
1. Click a preset from the palette (right side)
2. Click a grid cell to assign the preset
3. Click the cell again to play the animation
4. Right-click a cell to clear it
5. Use "Connect IDN" to connect to laser hardware

If you use VS Code with Calva, starting a REPL should automatically launch the app and give you a REPL in the `user.clj` namespace.

## Development Workflow

### Start a REPL for Interactive Development

The aliases are composable - combine them as needed:

```bash
# Basic development (Windows)
clj -M:dev:laser-show:win

# Development (macOS)
clj -M:dev:laser-show:mac

# Development (Linux)
clj -M:dev:laser-show:linux

# Development with JFR profiling enabled
clj -M:dev:laser-show:win:jfr

# REPL-only (no app, just nREPL server - needs platform)
clj -M:win:repl
```

**What each alias provides:**
- **`:win`** / **`:mac`** / **`:linux`** - Platform-specific JavaFX natives (required)
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
1. Start with `:jfr` alias: `clj -M:dev:laser-show:jfr`
2. Open JDK Mission Control and connect to the running JVM
3. The "continuous" recording is already running with 5-minute history
4. Dump recordings from REPL:
   ```clojure
   (require '[laser-show.profiling.jfr-profiler :as jfr])
   (jfr/dump-recording "my-recording.jfr")
   ```

### Building Uber JARs

```bash
# Build platform-specific JAR (pass platform as argument)
clj -T:build uber :platform :win      # Windows JAR
clj -T:build uber :platform :mac      # macOS JAR
clj -T:build uber :platform :linux    # Linux JAR
```

The JAR will be created at `target/laser-show-0.1.0-standalone.jar`

## Troubleshooting
btw, i've mostly tested on Windows, if you're not on it maybe it'll cause issues

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
