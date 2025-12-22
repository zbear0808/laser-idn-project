# Laser Show & IDN-Hello Project

A Clojure project for controlling laser shows with multiple projectors using the ILDA Digital Network protocols. Features a launchpad-style interface, multi-input support (keyboard, MIDI, OSC), and a sophisticated zone-based routing system.

Contains two modules:
1. **Laser Show** - Live laser show control application
2. **IDN-Hello** - ILDA Digital Network Hello Protocol implementation

For detailed architecture information, see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Quick Start

### Prerequisites

- Java 11 or higher
- Clojure CLI tools (https://clojure.org/guides/install_clojure)

### Running Laser Show

Launch the laser show application with GUI:

```bash
clj -M:laser-show
```

Features:
- 8x4 launchpad-style grid interface
- Real-time animation preview
- Multiple zones and projectors support
- MIDI, OSC, and keyboard input
- Effect chains and modulation
- IDN protocol integration for laser hardware

### Running IDN-Hello

Run IDN-Hello protocol examples:

```bash
clj -M:idn-hello
```

The IDN-Hello module provides network device discovery, ping/pong communication, service enumeration, and streaming commands following the ILDA Digital Network Hello Protocol specification.

## Development

### Start a REPL

For interactive development:

```bash
clj -M:repl
```

Connect your editor (VS Code with Calva, Emacs with CIDER, etc.) to the nREPL server shown in the console output.

### REPL Examples

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
```

## Animation System

The laser show uses a frame-based animation system with the following core types:

- **LaserPoint**: Individual point with X/Y coordinates (-32768 to 32767) and RGB color (0-255 each)
- **LaserFrame**: Collection of points representing a single frame
- **Animation**: Protocol for generating frames over time

### Available Presets

- **Geometric**: Circle, Square, Triangle, Star, Spiral
- **Beams**: Beam Fan (sweeping laser beams)
- **Waves**: Sine wave patterns
- **Abstract**: Rainbow Circle (color-cycling effects)

See [`src/laser_show/animation/presets.clj`](src/laser_show/animation/presets.clj) for preset definitions and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for animation system details.

## IDN Protocol Support

### IDN-Hello Protocol

Implements ILDA Digital Network Hello Protocol (Draft 2022-03-27):

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

**Default Port**: UDP 7255

### IDN-Stream Protocol

Frame streaming with X/Y coordinates (16-bit) and RGB color (8-bit each). See [`src/laser_show/backend/idn_stream.clj`](src/laser_show/backend/idn_stream.clj) and [`src/laser_show/backend/streaming_engine.clj`](src/laser_show/backend/streaming_engine.clj).

## Input System

All input sources produce unified events that route to handlers:

### Keyboard

Default launchpad-style key mappings:

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

### MIDI

```clojure
;; List available MIDI devices
(require '[laser-show.input.midi :as midi])
(midi/list-device-names)

;; Connect to a MIDI device
(midi/connect-device! "Launchpad")

;; Or auto-connect to first available device
(midi/auto-connect!)
```

### OSC

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

See [`src/laser_show/input/`](src/laser_show/input/) for implementation details.

## Testing

Run all tests:

```bash
clj -M:test
```

**Test Suite: 49 tests, 182 assertions**

Covers input system (events, routing), IDN protocol implementation, packet logging, and backend systems. See [`test/`](test/) directory for test files.

## Current Work

Features currently in development:

| Feature | Status | Location |
|---------|--------|----------|
| IDN-Stream Protocol | Core packet construction complete | [`backend/idn_stream.clj`](src/laser_show/backend/idn_stream.clj) |
| Packet Logging | Functional, UI integrated | [`backend/packet_logger.clj`](src/laser_show/backend/packet_logger.clj) |
| Cue System | Basic structure defined | [`backend/cues.clj`](src/laser_show/backend/cues.clj) |
| Multi-Zone Routing | Implemented | [`backend/zone_router.clj`](src/laser_show/backend/zone_router.clj) |
| Effects System | Functional | [`animation/effects/`](src/laser_show/animation/effects/) |

## Non-Goals

This project intentionally does not include the following features:

### High-Quality / Realistic Visualization

Real-time, photorealistic laser visualization is computationally expensive and would be better suited as a standalone application. The current preview is optimized for performance and layout verification, not rendering quality. Building high-quality real-time visualization would add significant complexity without benefiting the core use case of controlling live shows.

### FFT / Live Audio Analysis

Live audio analysis (FFT) is typically noisy and unreliable for direct show control. The signal requires heavy smoothing and filtering to be usable, which is better handled by specialized tools. 

**Instead**: Use external applications (Python scripts, TouchDesigner, Max/MSP, etc.) to analyze audio and send pre-processed OSC or MIDI curves to this application. This separation of concerns allows you to use the best tool for audio analysis while keeping this application focused on laser control.

### Live Audio Playback

This application does not include built-in audio playback, timecode syncing, tempo adjustment, or multi-format audio decoding. These features add substantial complexity that existing tools already handle well:

- Playing audio with sample-accurate timing
- Syncing with frame-accurate timecode
- Dynamic BPM changes and time-stretching
- Decoding various audio formats
- Audio routing and mixing

**Instead**: Use your preferred audio application (TouchDesigner, Ableton Live, QLab, DAWs, etc.) to handle audio playback and send timecode (MIDI timecode or LTC) to this application. This app will sync to the incoming timecode and/or MIDI messages.

## Future Possibilities

The following feature is planned for future development but is not currently being worked on due to its significant complexity:

### Timeline System

A comprehensive timeline system for fully pre-recorded shows:

- **Timecode Integration**: Full support for MIDI timecode (MTC) and LTC timecode
- **Seeking/Scrubbing**: Navigate within the timeline, jump to specific cues
- **Pre-compilation**: Option to pre-render all IDN frames for maximum stability during playback
- **Live Recording**: Record MIDI/OSC inputs to create and edit cues in real-time
- **Beat Quantization**: Snap cue triggers to beat boundaries when recording to timeline
- **Timeline Editing**: Full editing capabilities for cue timing, transitions, and effects

This is a major architectural addition that requires:
- Comprehensive timeline data structures
- Timeline playback engine separate from live performance mode
- Timeline UI with scrubbing and editing
- Persistent timeline storage format
- Pre-compilation pipeline with caching
- Frame interpolation and transition system

**Status**: Not planned for the near term. The current focus is on perfecting live performance capabilities.

## Troubleshooting

### Laser Show won't start
- Ensure Java 11+ is installed: `java -version`
- Download dependencies: `clj -P -M:laser-show`

### IDN-Hello can't discover devices
- Verify network connectivity to laser hardware
- Check firewall settings for UDP port 7255
- Ensure broadcast address is correct for your network

### REPL connection issues
- Verify nREPL is running: `clj -M:repl`
- Check the port number in the console output
- Ensure your editor is configured for nREPL connection

For more help, see [`QUICKSTART.md`](QUICKSTART.md) or [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## License

Eclipse Public License 2.0 (for Clojure code compatibility)

## Contributing

This is an independent project. Feel free to fork and modify for your needs.
