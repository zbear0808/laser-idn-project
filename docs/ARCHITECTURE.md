# Laser IDN Project Architecture

## Overview

The Laser IDN Project is a Clojure-based laser show control system that implements the ILDA Digital Network protocols (IDN-Hello and IDN-Stream) for controlling multiple laser projectors. The system provides a launchpad-style interface for triggering animations and effects across multiple zones and projectors.

## System Architecture

### High-Level Components

```
┌─────────────────────────────────────────────────────────────┐
│                     User Interface (Swing/Seesaw)            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  Grid View   │  │   Preview    │  │  Config UIs  │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                             │
┌─────────────────────────────────────────────────────────────┐
│                      Input System                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Keyboard   │  │     MIDI     │  │     OSC      │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                             │
                    Unified Event Stream
                             │
┌─────────────────────────────────────────────────────────────┐
│                    Animation System                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Presets    │  │  Generators  │  │   Effects    │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                             │
                        LaserFrame
                             │
┌─────────────────────────────────────────────────────────────┐
│                     Cue System                               │
│          (Manages what animation plays where)                │
└─────────────────────────────────────────────────────────────┘
                             │
                      Target Specification
                             │
┌─────────────────────────────────────────────────────────────┐
│                    Zone Router                               │
│        (Maps animations to physical projectors)              │
└─────────────────────────────────────────────────────────────┘
                             │
                  ┌───────────┴────────────┐
                  │                        │
┌─────────────────────────┐  ┌─────────────────────────┐
│   Projector 1 Stream    │  │   Projector 2 Stream    │
│   (IDN-Hello/Stream)    │  │   (IDN-Hello/Stream)    │
└─────────────────────────┘  └─────────────────────────┘
```

## Core Concepts

### 1. Presets and Animations

**Preset**: A template definition that includes:
- Generator function
- Default parameters
- Parameter specifications (type, range, etc.)
- Metadata (name, category)

**Animation**: A runtime instance created from a preset with specific parameter values. Implements the `IAnimation` protocol to generate `LaserFrame` objects over time.

**Relationship**: `Preset` → (instantiate with params) → `Animation`

Located in: [`src/laser_show/animation/`](../src/laser_show/animation/)

### 2. Frames and Points

**LaserPoint**: A single point in laser space
- X, Y coordinates (16-bit signed: -32768 to 32767)
- RGB color (8-bit each: 0-255)
- Blanking indicated by R=G=B=0

**LaserFrame**: A collection of `LaserPoint` objects representing one frame
- Vector of points
- Timestamp
- Metadata

Located in: [`src/laser_show/animation/types.clj`](../src/laser_show/animation/types.clj)

### 3. Effects System

**Effect**: A transformation applied to a `LaserFrame`
- Categories: `:shape`, `:color`, `:intensity`, `:calibration`
- Timing modes: `:static`, `:bpm`, `:seconds`
- Can be modulated (parameters change over time)

**Effect Chain**: Ordered list of effects applied sequentially

**Effect Application Order** (critical for understanding behavior):
1. **Cue Effects** - Applied to base animation frame
2. **Zone Group Effects** - Applied during routing
3. **Zone Effects** - Applied during routing
4. **Projector Effects** - Applied after routing (typically calibration)

Located in: [`src/laser_show/animation/effects/`](../src/laser_show/animation/effects/)

### 4. The Cue → Zone → Projector Flow

This is the core routing architecture of the system:

```
┌──────────┐
│   Cue    │  Stores: animation + target + effect chain
└────┬─────┘
     │
     │ 1. Get animation frame
     │ 2. Apply cue effects
     │
     ▼
┌──────────────────┐
│     Target       │  Specifies where to route:
│  Specification   │  - Single zone: {:type :zone :zone-id :zone-1}
└────┬─────────────┘  - Zone group: {:type :zone-group :group-id :left-side}
     │                - Multiple zones: {:type :zones :zone-ids #{...}}
     │
     ▼
┌──────────────────┐
│   Zone Router    │  Resolves target → zones
│                  │  Groups zones by projector
│                  │  Resolves priority conflicts
└────┬─────────────┘
     │
     │ For each winning zone:
     │ 3. Apply zone group effects
     │ 4. Apply zone effects
     │ 5. Apply zone transformations
     │
     ▼
┌──────────────────┐
│   Projector      │  Physical laser projector
│                  │  Has network address (IP:port)
│                  │  Has IDN channel ID
└────┬─────────────┘
     │
     │ 6. Apply projector effects (calibration)
     │ 7. Convert to IDN-Stream packets
     │ 8. Send via UDP
     │
     ▼
  Laser Hardware
```

#### Detailed Flow

**Step 1: Cue System**
- Cues store the "what" (animation) and "where" (target)
- Active cue determines current animation and routing
- Located in: [`src/laser_show/backend/cues.clj`](../src/laser_show/backend/cues.clj)

**Step 2: Target Resolution**
- Target specification is resolved to a set of zone IDs
- Zone groups expand to their constituent zones
- Only enabled zones are considered
- Located in: [`src/laser_show/backend/zone_router.clj`](../src/laser_show/backend/zone_router.clj)

**Step 3: Zone Mapping**
- Each zone maps to exactly one projector
- Multiple zones can map to the same projector
- Priority system resolves conflicts (highest priority wins)
- Located in: [`src/laser_show/backend/zones.clj`](../src/laser_show/backend/zones.clj)

**Step 4: Frame Transformation**
- Zone defines geometric transformations:
  - Viewport clipping
  - Scale (X/Y independent)
  - Offset (translation)
  - Rotation
  - Blocked regions (safety masking)
- Transformations map the normalized frame space to projector space
- Located in: [`src/laser_show/backend/zone_transform.clj`](../src/laser_show/backend/zone_transform.clj)

**Step 5: Projector Streaming**
- Each projector has a dedicated streaming engine
- Multi-projector coordinator manages all streams
- Frames converted to IDN-Stream protocol packets
- Sent via UDP to projector network address
- Located in: [`src/laser_show/backend/multi_projector_stream.clj`](../src/laser_show/backend/multi_projector_stream.clj)

### 5. Zone System Details

**Zone**: A logical projection space
- Has a unique ID (keyword)
- Maps to one projector
- Defines transformations for mapping content
- Has safety tags (`:safe`, `:crowd-scanning`, etc.)
- Has blocked regions for safety
- Can have an effect chain

**Zone Group**: Collection of zones
- Allows targeting multiple zones as a unit
- Examples: "left-side", "all-zones", "upper-tier"
- Useful for spatial or functional grouping
- Can have an effect chain applied to all members

**Why Zones?**
- Decouple content (animations) from hardware (projectors)
- Support multiple virtual spaces per projector
- Enable geometric corrections per space
- Provide safety boundaries
- Allow flexible reconfiguration without changing content

### 6. Protocol Stack

**IDN-Hello Protocol** (Discovery & Management)
- Device discovery (scan)
- Ping/pong communication
- Service enumeration
- Connection management
- Port: UDP 7255
- Located in: [`src/idn_hello/core.clj`](../src/idn_hello/core.clj)

**IDN-Stream Protocol** (Frame Streaming)
- Frame data encoding
- Channel management
- Configuration messages
- Sample format: X(16bit), Y(16bit), R(8bit), G(8bit), B(8bit)
- Located in: [`src/laser_show/backend/idn_stream.clj`](../src/laser_show/backend/idn_stream.clj)

**Streaming Engine**
- Manages continuous frame streaming
- Resends channel configuration every 200ms (per spec)
- Handles timing and FPS control
- Manages sequence numbers
- Located in: [`src/laser_show/backend/streaming_engine.clj`](../src/laser_show/backend/streaming_engine.clj)

### 7. Input System

**Unified Event Model**
- All inputs (keyboard, MIDI, OSC) produce standardized events
- Event types: `:note-on`, `:note-off`, `:control-change`, `:trigger`
- Events routed to handlers via pattern matching
- Located in: [`src/laser_show/input/`](../src/laser_show/input/)

**Input Flow**:
```
Keyboard/MIDI/OSC → Unified Events → Event Router → Handlers → Action
```

### 8. State Management

**Current Approach**: Distributed state atoms
- Each subsystem manages its own state
- `!projectors`, `!zones`, `!zone-groups`, `!cues` (registries)
- `app-state` (UI and runtime state)
- `!multi-engine-state` (streaming state)

**State Atoms Naming Convention**: 
- Prefix with `!` for mutable atoms
- Example: `!projectors`, `!zones`, `!cues`

## Data Flow Examples

### Example 1: Triggering a Simple Animation

```
1. User clicks grid cell [2,3]
2. Grid lookup → finds preset-id :spinning-square
3. Create animation from preset
4. Set as current-animation in app-state
5. Preview panel starts rendering animation
6. If streaming active:
   a. Frame provider gets current frame
   b. No target specified → default to :zone-1
   c. Zone-1 maps to :projector-1
   d. Frame transformed for zone-1
   e. Projector effects applied
   f. Converted to IDN packets
   g. Sent to projector-1's IP address
```

### Example 2: Complex Multi-Projector Cue

```
1. Cue triggered: {:id :cue-1 
                    :preset-id :rainbow-circle
                    :target {:type :zone-group :group-id :all-zones}
                    :effect-chain {...}}
2. Get animation frame from :rainbow-circle preset
3. Apply cue effect chain
4. Resolve :all-zones group → #{:zone-1 :zone-2 :zone-3}
5. Group by projector:
   - :projector-1 ← [:zone-1 :zone-2]
   - :projector-2 ← [:zone-3]
6. Resolve priority (if conflicts)
7. For each zone:
   a. Apply zone group effects
   b. Apply zone effects  
   c. Apply zone transformations
8. For each projector:
   a. Apply projector calibration effects
   b. Convert to IDN packets
   c. Stream to hardware
```

## Directory Structure

```
src/
├── idn_hello/              # IDN-Hello protocol implementation
│   └── core.clj
├── laser_show/
│   ├── core.clj            # Application entry point
│   ├── animation/          # Animation generation and effects
│   │   ├── types.clj       # Core data structures (Point, Frame, Animation)
│   │   ├── generators.clj  # Shape generation functions
│   │   ├── presets.clj     # Preset definitions
│   │   ├── colors.clj      # Color utilities
│   │   ├── time.clj        # BPM and timing utilities
│   │   ├── modulation.clj  # Parameter modulation system
│   │   ├── effects.clj     # Effect system core
│   │   └── effects/        # Effect implementations
│   │       ├── common.clj  # Shared utilities
│   │       ├── shape.clj   # Geometric transformations
│   │       ├── color.clj   # Color effects
│   │       ├── intensity.clj # Brightness/dimming effects
│   │       └── calibration.clj # Hardware calibration
│   ├── backend/            # State management and streaming
│   │   ├── config.clj      # Configuration persistence
│   │   ├── cues.clj        # Cue system
│   │   ├── projectors.clj  # Projector registry
│   │   ├── zones.clj       # Zone registry
│   │   ├── zone_groups.clj # Zone group registry
│   │   ├── zone_router.clj # Zone routing logic
│   │   ├── zone_transform.clj # Zone transformations
│   │   ├── idn_stream.clj  # IDN-Stream protocol
│   │   ├── streaming_engine.clj # Single projector streaming
│   │   ├── multi_projector_stream.clj # Multi-projector coordinator
│   │   └── packet_logger.clj # Debug logging
│   ├── input/              # Input handling
│   │   ├── events.clj      # Unified event system
│   │   ├── router.clj      # Event routing
│   │   ├── keyboard.clj    # Keyboard input
│   │   ├── midi.clj        # MIDI input
│   │   └── osc.clj         # OSC input
│   ├── state/              # State management
│   │   └── clipboard.clj   # Copy/paste support
│   └── ui/                 # User interface
│       ├── colors.clj      # UI color scheme
│       ├── grid.clj        # Launchpad grid
│       ├── preview.clj     # Animation preview
│       ├── layout.clj      # Layout management
│       ├── drag_drop.clj   # Drag & drop
│       ├── projector_config.clj # Projector configuration UI
│       ├── zone_config.clj # Zone configuration UI
│       └── zone_group_config.clj # Zone group configuration UI
test/                       # Test suite
plans/                      # Design documents and plans
docs/                       # Documentation (this file)
```

## Key Design Patterns

### 1. Protocol-Based Polymorphism
- `IAnimation` protocol for animation implementations
- Effects use function-based approach with registration

### 2. Atom-Based State
- Each registry is an atom: `!projectors`, `!zones`, etc.
- Consistent naming with `!` prefix for mutable state
- Pure functions for transformations, atoms for coordination

### 3. Function Composition
- Effects are composable transformations
- Frame transformation pipeline
- Event handler chains

### 4. Registry Pattern
- Common pattern for projectors, zones, zone groups, cues
- CRUD operations: register, get, update, remove, list
- Persistence to EDN files

## Configuration and Persistence

All configuration is stored in `config/` directory as EDN files:
- `config/settings.edn` - Application settings
- `config/grid.edn` - Grid cell assignments
- `config/projectors.edn` - Projector configurations
- `config/zones.edn` - Zone definitions
- `config/zone-groups.edn` - Zone group definitions
- `config/cues.edn` - Cue definitions
- `config/cue-lists.edn` - Cue sequence lists

## Threading and Concurrency

**Main Thread**: UI event handling (Swing EDT)

**Streaming Threads**: One per projector
- Managed by streaming engine
- Run at configured FPS (default 30)
- Independent timing and sequence numbers

**Input Threads**: 
- MIDI input handling
- OSC server (if enabled)

**Coordination**: Atoms provide thread-safe state updates

## Performance Considerations

**Frame Generation**: 
- Should complete well under 33ms (for 30 FPS)
- Effects applied sequentially in chain
- Transformations are pure functions (no allocation overhead)

**Network Streaming**:
- UDP (fire and forget, no TCP overhead)
- Configuration resent every 200ms per spec
- Max ~150 points per packet (IDN-Stream limitation)

**UI Updates**:
- Preview runs independently of streaming
- Grid updates on-demand only

## Extension Points

### Adding New Effects
1. Create effect in appropriate `effects/*.clj` file
2. Define parameters with types and ranges
3. Implement `apply-fn` that transforms frames
4. Register with `fx/register-effect!`
5. Optionally support modulation for parameters

### Adding New Presets
1. Create generator function in `generators.clj` (if needed)
2. Define preset in `presets.clj` with parameters
3. Add to `all-presets` vector
4. Preset automatically appears in UI palette

### Adding New Input Sources
1. Create namespace in `input/`
2. Convert source events to unified event format
3. Publish events to event router
4. Follow existing MIDI/OSC patterns

## Safety Features

**Blocked Regions**: Zones can define areas where laser should be blanked
- Rectangular regions
- Circular regions
- Points in blocked regions automatically blanked (R=G=B=0)

**Zone Tags**: 
- `:safe` - General use areas
- `:crowd-scanning` - Special handling required
- `:restricted` - Requires authorization

**Validation**: 
- Target validation before routing
- Zone validation (missing zones detected)
- Frame safety checks

## Future Architecture Considerations

**State Centralization**: Consider moving to single state atom or database
**Undo/Redo**: Would benefit from centralized state with history
**Network Discovery**: Auto-discovery of projectors on network
**Failover**: Redundant projector support
**Timecode Sync**: SMPTE/LTC synchronization for shows

## References

- [IDN-Hello Protocol Spec](https://www.ilda.com/)
- [IDN-Stream Protocol Spec](https://www.ilda.com/)
- [Project README](../README.md)
- [Cleanup Document](../plans/codebase-cleanup-2025-12-22-00-13.md)
