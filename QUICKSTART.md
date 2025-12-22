# Quick Start Guide

## Installation

1. **Install Java** (if not already installed)
   - Download from https://adoptium.net/
   - Minimum version: Java 11

2. **Install Clojure CLI tools**
   - Windows: https://clojure.org/guides/install_clojure#_windows
   - Or use: `scoop install clojure`

3. **Clone or download this project**
   ```bash
   cd C:\Users\YourName\Documents\GitHub
   git clone <your-repo-url> laser-idn-project
   cd laser-idn-project
   ```

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

### Option 2: IDN-Hello (Command Line)

```bash
clj -M:idn-hello
```

This runs the IDN-Hello protocol demonstration. The output shows available functions.

**To actually use IDN-Hello functions, start a REPL:**

```bash
clj -M:repl
```

Then in the REPL:

```clojure
;; Discover devices on your network
(require '[idn-hello.core :as idn])
(idn/discover-devices "192.168.1.255")  ; Use your network's broadcast address

;; Ping a specific device
(idn/ping-device "192.168.1.100")  ; Replace with actual device IP
```

## Development Workflow

### Start a REPL for Interactive Development

```bash
clj -M:repl
```

The REPL will start and show a port number. Connect your editor:

- **VS Code with Calva**: 
  1. Ctrl+Shift+P → "Calva: Connect to a Running REPL Server"
  2. Select "deps.edn"
  3. Enter the port number shown in terminal

- **Emacs with CIDER**:
  1. M-x cider-connect
  2. Enter localhost and the port number

### Test Laser Show in REPL

```clojure
;; Load and start the application
(require '[laser-show.core :as laser])
(laser/start!)

;; Test individual animations
(require '[laser-show.animation.presets :as presets])
(require '[laser-show.animation.types :as t])

(def my-anim (presets/create-animation-from-preset :spinning-square))
(t/get-frame my-anim 1000)  ; Get frame at 1 second

;; Access the running application state
@laser/app-state
```

### Test IDN-Hello in REPL

```clojure
;; Load the IDN-Hello module
(require '[idn-hello.core :as idn])

;; Create a UDP socket
(def socket (idn/create-udp-socket))

;; Send a ping
(idn/send-ping socket "192.168.1.100" 1)

;; Receive response (with 5 second timeout)
(idn/receive-packet socket 1024 5000)

;; Close socket when done
(.close socket)
```

## Troubleshooting

### "clj: command not found"
- Clojure CLI tools are not installed
- Install from: https://clojure.org/guides/install_clojure

### "Unable to resolve symbol"
- Dependencies not downloaded
- Run: `clj -P` to download all dependencies

### Laser Show window doesn't appear
- Check Java version: `java -version` (should be 11+)
- Try running with: `clj -M:laser-show` from project directory

### IDN-Hello can't find devices
- Check your network broadcast address
- Verify UDP port 7255 is not blocked by firewall
- Ensure you're on the same network as the devices

## Next Steps

- Read the full [README.md](README.md) for detailed documentation
- Explore the source code in `src/laser_show/` and `src/idn_hello/`
- Create custom animations by modifying `src/laser_show/animation/presets.clj`
- Integrate with your laser hardware using the IDN protocol

## Project Structure

```
laser-idn-project/
├── deps.edn              # Dependencies and run configurations
├── README.md             # Full documentation
├── QUICKSTART.md         # This file
├── .gitignore           # Git ignore rules
└── src/
    ├── laser_show/       # Laser show GUI application
    │   ├── core.clj
    │   ├── animation/
    │   └── ui/
    └── idn_hello/        # IDN protocol implementation
        └── core.clj
```

## Support

This is an independent project. For issues or questions:
1. Check the README.md for detailed documentation
2. Review the source code comments
3. Test in a REPL for interactive debugging
