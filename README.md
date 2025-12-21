# Laser Show & IDN-Hello Project

A Clojure project containing two independent modules:
1. **Laser Show** - A launchpad-style interface for controlling laser animations
2. **IDN-Hello** - Implementation of the ILDA Digital Network Hello Protocol

## Prerequisites

- Java 11 or higher
- Clojure CLI tools (https://clojure.org/guides/install_clojure)

## Project Structure

```
laser-idn-project/
├── deps.edn              # Project dependencies and aliases
├── src/
│   ├── laser_show/       # Laser show application
│   │   ├── core.clj      # Main entry point
│   │   ├── animation/    # Animation system
│   │   │   ├── types.clj
│   │   │   ├── generators.clj
│   │   │   └── presets.clj
│   │   └── ui/           # User interface
│   │       ├── grid.clj
│   │       └── preview.clj
│   └── idn_hello/        # IDN-Hello protocol
│       └── core.clj      # Protocol implementation
└── README.md
```

## Dependencies

- **Clojure 1.12.1** - Core language
- **Seesaw 1.5.0** - Swing UI wrapper (for Laser Show)
- **FlatLaf 3.6.1** - Modern look and feel (for Laser Show)

## Running the Applications

### Laser Show

Launch the laser show application with a launchpad-style grid interface:

```bash
clj -M:laser-show
```

Features:
- 8x4 grid of animation cells (launchpad-style)
- Real-time animation preview
- Preset palette with various geometric shapes and effects
- IDN protocol integration for laser hardware control
- Click cells to trigger animations
- Right-click to clear cells

### IDN-Hello

Run the IDN-Hello protocol examples:

```bash
clj -M:idn-hello
```

The IDN-Hello module provides:
- Network device discovery
- Ping/pong communication
- Service map requests
- Realtime streaming commands
- Full ILDA Digital Network Hello Protocol implementation

## Development

### Start a REPL

For interactive development with nREPL and CIDER support:

```bash
clj -M:repl
```

Then connect your editor (VS Code with Calva, Emacs with CIDER, etc.) to the nREPL server.

### REPL Usage Examples

#### Laser Show

```clojure
;; Start the application
(require '[laser-show.core :as laser])
(laser/start!)

;; Create and test animations
(require '[laser-show.animation.presets :as presets])
(def anim (presets/create-animation-from-preset :spinning-square))

;; Get a frame at time 1000ms
(require '[laser-show.animation.types :as t])
(t/get-frame anim 1000)
```

#### IDN-Hello

```clojure
;; Discover devices on network
(require '[idn-hello.core :as idn])
(idn/discover-devices "192.168.1.255")

;; Ping a specific device
(idn/ping-device "192.168.1.100")

;; Send realtime streaming messages
(let [socket (idn/create-udp-socket)]
  (try
    (dotimes [i 10]
      (idn/send-rt-channel-message socket "192.168.1.100" i 0 nil)
      (Thread/sleep 100))
    (idn/send-rt-close socket "192.168.1.100" 10 0)
    (finally
      (.close socket))))
```

## Laser Show Animation System

The laser show uses a frame-based animation system:

- **LaserPoint**: Individual point with X/Y coordinates (-32768 to 32767) and RGB color
- **LaserFrame**: Collection of points representing a single frame
- **Animation**: Protocol for generating frames over time

### Available Presets

- **Geometric**: Circle, Square, Triangle, Star, Spiral
- **Beams**: Beam Fan (sweeping laser beams)
- **Waves**: Sine wave patterns
- **Abstract**: Rainbow Circle (color-cycling effects)

### Creating Custom Animations

```clojure
(require '[laser-show.animation.types :as t])
(require '[laser-show.animation.generators :as gen])

;; Define a custom animation generator
(defn my-animation []
  (fn [time-ms params]
    (let [points (gen/generate-circle :radius 0.5 :color [255 0 0])]
      (t/make-frame points))))

;; Create animation instance
(def my-anim (t/make-animation "My Animation" (my-animation) {} nil))
```

## IDN-Hello Protocol

The IDN-Hello implementation follows the ILDA Digital Network Hello Protocol specification (Draft 2022-03-27).

### Protocol Commands

**Management Commands:**
- `CMD_PING_REQUEST` / `CMD_PING_RESPONSE` - Network connectivity testing
- `CMD_CLIENT_GROUP_REQUEST` / `CMD_CLIENT_GROUP_RESPONSE` - Client group management

**Discovery Commands:**
- `CMD_SCAN_REQUEST` / `CMD_SCAN_RESPONSE` - Device discovery
- `CMD_SERVICE_MAP_REQUEST` / `CMD_SERVICE_MAP_RESPONSE` - Service enumeration

**Realtime Streaming:**
- `CMD_RT_CHANNEL_MESSAGE` - Send frame data
- `CMD_RT_CHANNEL_MESSAGE_ACK` - Send with acknowledgement
- `CMD_RT_CLOSE` - Graceful connection close
- `CMD_RT_ABORT` - Immediate connection abort

### Network Configuration

Default IDN-Hello port: **7255 UDP**

## License

This project is independent from the Beat Link Trigger project and uses:
- Eclipse Public License 2.0 (for Clojure code compatibility)

## Contributing

This is an independent project. Feel free to fork and modify for your needs.

## Troubleshooting

### Laser Show won't start
- Ensure Java 11+ is installed
- Check that all dependencies are downloaded: `clj -P -M:laser-show`

### IDN-Hello can't discover devices
- Verify network connectivity
- Check firewall settings (UDP port 7255)
- Ensure broadcast address is correct for your network

### REPL connection issues
- Verify nREPL is running: `clj -M:repl`
- Check the port number in the console output
- Ensure your editor is configured for nREPL connection
