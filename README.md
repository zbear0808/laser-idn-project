# Laser Show (i'm bad at naming things)

A Clojure project for controlling laser shows with multiple projectors using the ILDA Digital Network protocols. Features a launchpad-style interface, multi-input support (keyboard, MIDI, OSC)


<img width="2880" height="1800" alt="{DC4D3125-9F45-4BF8-9EB9-6AF6D73751A2}" src="https://github.com/user-attachments/assets/fe47f5a2-5038-4c44-8fd7-57c76548429f" />


## Table of Contents

- [Animation System](#animation-system)
- [IDN Protocol Support](#idn-protocol-support)
- [Input System](#input-system)
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
this should work regardless of your OS, but you'd need JDK23+ installed

## Animation System

The laser show uses a frame-based animation system with the following core types:

- **LaserPoint**: Individual point with X/Y coordinates [-1, 1] and RGB color [0, 1.0]
- **LaserFrame**: Vector of points representing a single frame

## IDN Protocol Support

Implements ILDA Digital Network Hello Protocol and IDN Stream Protocol:

**IDN Hello Default Port**: UDP 7255

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



**Status**: Not planned for the near term. The current focus is on live performance capabilities.



For more help, see [`QUICKSTART.md`](QUICKSTART.md).

## License

agplv3

## Contributing

This is an independent project. Feel free to fork and modify for your needs.
