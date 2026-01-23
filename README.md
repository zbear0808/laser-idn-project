# Laser Show & IDN-Hello Project

A Clojure project for controlling laser shows with multiple projectors using the ILDA Digital Network protocols. Features a launchpad-style interface, multi-input support (keyboard, MIDI, OSC), and a sophisticated zone-based routing system.

Contains two modules:
1. **Laser Show** - Live laser show control application
2. **IDN-Hello** - ILDA Digital Network Hello Protocol implementation


## Table of Contents

- [Quick Start](#quick-start)
  - [Prerequisites](#prerequisites)
  - [Running Laser Show](#running-laser-show)
  - [Running IDN-Hello](#running-idn-hello)
- [Development](#development)
- [Animation System](#animation-system)
- [IDN Protocol Support](#idn-protocol-support)
- [Input System](#input-system)
- [Testing](#testing)
- [Current Work](#current-work)
- [Non-Goals](#non-goals)
- [Future Possibilities](#future-possibilities)
- [Troubleshooting](#troubleshooting)
- [License](#license)
- [Contributing](#contributing)


### Installation Steps

1. **Download** the appropriate file for your system from the [releases page](https://github.com/zbear0808/laser-idn-project/releases)


### Troubleshooting Downloads
I haven't tested mac or linux builds, they're just generated from my github action

→ Try the universal JAR: `java -jar laser-show-X.X.X-standalone.jar`
this should work regardless of your OS, but you'd need JDK25 installed

## Animation System

The laser show uses a frame-based animation system with the following core types:

- **LaserPoint**: Individual point with X/Y coordinates (-32768 to 32767) and RGB color (0-255 each)
- **LaserFrame**: Collection of points representing a single frame
- **Animation**: Protocol for generating frames over time

## IDN Protocol Support

### IDN-Hello Protocol

Implements ILDA Digital Network Hello Protocol (Draft 2022-03-27):

**Default Port**: UDP 7255

### IDN-Stream Protocol

Frame streaming with X/Y coordinates (16-bit) and RGB color (16-bit each). See [`src/laser_show/backend/idn_stream.clj`](src/laser_show/backend/idn_stream.clj) and [`src/laser_show/backend/streaming_engine.clj`](src/laser_show/backend/streaming_engine.clj).

## Input System

All input sources produce unified events that route to handlers:
we have keyboard, midi, and osc
See [`src/laser_show/input/`](src/laser_show/input/) for implementation details.

## Testing

Run all tests:

```bash
clj -M:test
```

**Test Suite is pretty bad**

Decent coverage of IDN protocol implementation, packet logging

bad coverage of everything else. a lot of ai generated tests that aren't actually checking anything useful, i need to clean those up.
 See [`test/`](test/) directory for test files.

## Current Work

todo, update


## Non-Goals

This project intentionally does not include the following features:

### High-Quality / Realistic Visualization

Real-time, photorealistic laser visualization is computationally expensive and would be better suited as a standalone application. The current preview is optimized for performance and layout verification, not rendering quality. Building high-quality real-time visualization would add significant complexity without benefiting the core use case of controlling live shows.

**If you need visualization**: Check out the [IDN Tools project](https://gitlab.com/laser_light_lab_uni_bonn/idn-npp/idn-tools) which includes visualization tools for the IDN protocol.

### FFT / Live Audio Analysis

Live audio analysis (FFT) is typically noisy and unreliable for direct show control. The signal requires heavy smoothing and filtering to be usable, which is better handled by specialized tools. 

**Instead**: Use external applications (Python scripts, TouchDesigner, Max/MSP, etc.) to analyze audio and send pre-processed OSC or MIDI cc to this application. This separation of concerns allows you to use the best tool for audio analysis while keeping this application focused on laser control.

### Live Audio Playback

 This is because features that i want to implement down the road like timecode syncing and tempo adjustment become MUCH harder when you also have to deal with an audio stream, and that's not even considering various formats' audio decoding. 

**Instead**: Use existing tools that can already play various audio formats with tempo adjustment (TouchDesigner, Max for Ableton Live) to handle audio playback and send timecode (MIDI timecode or LTC) to this application. This app will sync to the incoming timecode and/or MIDI messages, (note we don't have a timeline feature yet, but it's planned)

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
- Ensure Java 24+ is installed: `java -version` (JDK 24 recommended for VisualVM compatibility)
- Download dependencies: `clj -P -M:laser-show`

### IDN-Hello can't discover devices
- Verify network connectivity to laser hardware
- Check firewall settings for UDP port 7255
- Ensure broadcast address is correct for your network

### REPL connection issues
- Verify nREPL is running: `clj -M:repl`
- Check the port number in the console output
- Ensure your editor is configured for nREPL connection

For more help, see [`QUICKSTART.md`](QUICKSTART.md).

## License

idk yet, but for now Pangolin is not allowed to use any of my code bc they're meanies


## Contributing

This is an independent project. Feel free to fork and modify for your needs.
