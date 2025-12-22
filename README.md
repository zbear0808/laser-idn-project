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
│   │   │   ├── presets.clj
│   │   │   └── colors.clj
│   │   ├── backend/      # Backend systems
│   │   │   ├── config.clj
│   │   │   ├── cues.clj
│   │   │   ├── packet_logger.clj
│   │   │   └── idn_stream.clj
│   │   ├── input/        # Input system (MIDI, OSC, Keyboard)
│   │   │   ├── events.clj
│   │   │   ├── router.clj
│   │   │   ├── keyboard.clj
│   │   │   ├── midi.clj
│   │   │   └── osc.clj
│   │   └── ui/           # User interface
│   │       ├── grid.clj
│   │       ├── preview.clj
│   │       ├── layout.clj
│   │       └── colors.clj
│   └── idn_hello/        # IDN-Hello protocol
│       └── core.clj      # Protocol implementation
├── test/                 # Test suite
│   ├── laser_show/
│   │   ├── input/
│   │   │   ├── events_test.clj
│   │   │   └── router_test.clj
│   │   └── backend/
│   │       └── packet_logger_test.clj
│   └── idn_hello/
│       └── core_test.clj
└── README.md
```

## Dependencies

- **Clojure 1.12.1** - Core language
- **Seesaw 1.5.0** - Swing UI wrapper (for Laser Show)
- **FlatLaf 3.6.1** - Modern look and feel (for Laser Show)
- **overtone/midi-clj 0.5.0** - MIDI input support
- **overtone/osc-clj 0.9.0** - OSC input support
- **cognitect-labs/test-runner** - Test framework

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

---

## Testing

Run all tests:

```bash
clj -M:test
```

**Test Suite Summary: 49 tests, 182 assertions**

| Module | Description |
|--------|-------------|
| `idn-hello.core-test` | Protocol constants, packet headers, parsing, sockets |
| `laser-show.input.events-test` | Event creation, predicates, matching, value scaling |
| `laser-show.input.router-test` | Handler registration, dispatch, pattern matching |
| `laser-show.backend.packet-logger-test` | Command names, packet formatting, file operations |

---

## Input System

The laser show supports multiple input sources that all produce unified events:

### Keyboard (Default Mappings)

| Row | Keys | Notes |
|-----|------|-------|
| 0 | 1 2 3 4 5 6 7 8 | Grid cells 0-7 |
| 1 | Q W E R T Y U I | Grid cells 8-15 |
| 2 | A S D F G H J K | Grid cells 16-23 |
| 3 | Z X C V B N M , | Grid cells 24-31 |

**Transport:** Space (play/pause), Escape (stop), F1-F8 (presets)

### MIDI

```clojure
(require '[laser-show.input.midi :as midi])
(midi/list-device-names)      ;; List available devices
(midi/connect-device! "name") ;; Connect to a device
(midi/auto-connect!)          ;; Auto-connect first device
```

### OSC

```clojure
(require '[laser-show.input.osc :as osc])
(osc/start-server! 9000)  ;; Start OSC server
(osc/stop-server!)        ;; Stop server
```

---

## Work In Progress (WIP)

| Feature | Location | Status |
|---------|----------|--------|
| IDN-Stream Protocol | `backend/idn_stream.clj` | Core packet construction done |
| Packet Logging | `backend/packet_logger.clj` | Functional, UI integrated |
| Cue System | `backend/cues.clj` | Basic structure defined |

---

## Planned Features

### High Priority
- IDN-Stream output to laser hardware
- Save/Load projects
- MIDI output (LED feedback)

### Medium Priority
- Effects system (blur, color shift, scaling)
- BPM/tempo sync
- Audio reactivity
- Multi-output support

### Future
- DMX integration
- Show recording/playback
- Web UI
- Plugin system

## Testing

Run all tests with:

```bash
clj -M:test
```

### Test Coverage

| Module | Tests | Assertions | Description |
|--------|-------|------------|-------------|
| `idn-hello.core-test` | 9 | 30+ | IDN-Hello protocol constants, packet headers, parsing, sockets |
| `laser-show.input.events-test` | 12 | 50+ | Event creation, predicates, matching, value scaling |
| `laser-show.input.router-test` | 15 | 40+ | Handler registration, dispatch, pattern matching, logging |
| `laser-show.backend.packet-logger-test` | 13 | 30+ | Command names, packet formatting, file operations |

**Total: 49 tests, 182 assertions**

## Input System

The laser show supports multiple input sources that all produce unified events:

### Keyboard Input

Default key mappings (launchpad-style layout):

| Row | Keys |
|-----|------|
| 0 | `1` `2` `3` `4` `5` `6` `7` `8` |
| 1 | `Q` `W` `E` `R` `T` `Y` `U` `I` |
| 2 | `A` `S` `D` `F` `G` `H` `J` `K` |
| 3 | `Z` `X` `C` `V` `B` `N` `M` `,` |

**Transport Controls:**
- `Space` - Play/Pause toggle
- `Escape` - Stop
- `F1-F8` - Quick preset access

### MIDI Input

```clojure
;; List available MIDI devices
(require '[laser-show.input.midi :as midi])
(midi/list-device-names)

;; Connect to a MIDI device
(midi/connect-device! "Launchpad")

;; Or auto-connect to first available device
(midi/auto-connect!)

;; MIDI Learn - returns the next MIDI event info
(def learn-result (midi/start-learn!))
@learn-result  ;; Wait for user to move a control
```

### OSC Input

```clojure
;; Start OSC server on port 9000
(require '[laser-show.input.osc :as osc])
(osc/start-server! 9000)

;; Default mappings work with TouchOSC:
;; /fader1 - /fader8  -> Control changes
;; /grid/1/1 - /grid/2/8 -> Grid triggers
;; /play, /stop -> Transport

;; Stop OSC server
(osc/stop-server!)
```

---

## Work In Progress (WIP)

These features are partially implemented:

### IDN-Stream Protocol
- **Status**: Core packet construction implemented
- **Location**: `src/laser_show/backend/idn_stream.clj`
- **TODO**: 
  - Complete frame data encoding
  - Implement streaming state machine
  - Add connection management

### Packet Logging
- **Status**: Functional, integrated with UI
- **Location**: `src/laser_show/backend/packet_logger.clj`
- **TODO**: 
  - Add packet parsing/display mode
  - Add timestamp filtering
  - Add export to PCAP format

### Cue System
- **Status**: Basic structure defined
- **Location**: `src/laser_show/backend/cues.clj`
- **TODO**:
  - Implement cue triggering
  - Add cue sequencing
  - Add BPM sync

---

## To Be Started

These features are planned but not yet implemented:

### High Priority
- [ ] **IDN-Stream Output** - Actually send animation frames to laser hardware
- [ ] **Save/Load Projects** - Persist grid configurations to files
- [ ] **MIDI Output** - Feedback to MIDI controllers (LED states)

### Medium Priority
- [ ] **Effects System** - Add real-time effects (blur, color shift, scaling)
- [ ] **Tempo Sync** - BPM-based animation timing
- [ ] **Audio Reactivity** - FFT analysis for audio-reactive animations
- [ ] **Multi-Output** - Support multiple laser outputs/zones

### Low Priority / Future
- [ ] **DMX Integration** - Control traditional lighting fixtures
- [ ] **Show Recording** - Record and playback complete shows
- [ ] **Web UI** - Browser-based control interface
- [ ] **Plugin System** - Load custom animation generators
