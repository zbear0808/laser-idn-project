# Quick Start Guide

## Installation

1. **Install Java** (if not already installed)
   - Download from https://adoptium.net/
   - Minimum version: idk, but I set it up to build using JDK 25

2. **Install Clojure CLI tools**
   - Windows: https://clojure.org/guides/install_clojure#_windows
   - Or use: `scoop install clojure`


## Running the Applications

### Option 1: Laser Show (GUI Application)

```bash
clj -M:laser-show
```

This launches a graphical interface with:
- 8x4 grid of animation cells
- Real-time preview window
- Preset palette on the right

**Usage:**
1. Click a preset from the palette (right side)
2. Click a grid cell to assign the preset
3. Click the cell again to play the animation
4. Right-click a cell to clear it
5. Use "Connect IDN" to connect to laser hardware

If you use VS Code with Calva starting a repl should automatically launch the app and give you a repl in the `user.clj` namespace 

## Development Workflow

### Start a REPL for Interactive Development

```bash
clj -M:repl
```

The REPL will start and show a port number. Connect your editor:

- **VS Code with Calva**: 
  1. Ctrl+Shift+P → "Calva: Start a Project REPL and Connect (Jack-In)"
  With this repo's configuration calva should automatically choose "deps.edn" and select the right port


### Test Laser Show in REPL

```clojure
;; Load and start the application
(require '[laser-show.app :as app])
(app/start!)

```


## Troubleshooting
btw, i've only tested on Windows, if you're not on it maybe it'll cause issues

### "clj: command not found"
- Clojure CLI tools are not installed
- Install from: https://clojure.org/guides/install_clojure

### "Unable to resolve symbol"
- Dependencies not downloaded
- Run: `clj -P` to download all dependencies

### Laser Show window doesn't appear
- Check Java version: `java -version` (should be 25+ briefly tested 21, but not 100% sure it'll still work)
- Try running with: `clj -M:laser-show` from project directory

### IDN-Hello can't find devices
- Check your network broadcast address
- Verify UDP port 7255 is not blocked by firewall
- Ensure you're on the same network as the devices

## Next Steps

- Read the full [README.md](README.md) for detailed documentation
- Explore the source code in `src/laser_show/`
- Integrate with your laser hardware using the IDN protocol


## Support

This is an independent project. For issues or questions:
1. Post something in the Discussion
2. Test in a REPL for interactive debugging
3. Once you have repl commands to reproduce the issue then feel free to post an issue on github
   - i would also gladly accept a PR
