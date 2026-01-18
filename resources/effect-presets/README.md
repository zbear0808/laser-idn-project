# Effect Presets

This directory contains pre-configured effect chains that can be loaded and applied to cues in the laser show application.

## Available Presets

### Color Effects

#### rainbow-by-points.edn
Creates a rainbow gradient across points using point index modulation. Each point in the shape gets a different hue based on its position in the point sequence.

**Effect:** `set-hue` with `point-index` modulator  
**Use case:** Static rainbow gradients on shapes

#### rainbow-time-oscillation.edn
Animated rainbow that cycles through all hues over time. The entire shape changes color in sync.

**Effect:** `hue-shift` with `sawtooth` modulator  
**Period:** 4 beats  
**Use case:** Animated color cycling

#### desaturated-oscillator.edn
Oscillates saturation from grayscale (0.0) to full color (1.0) over time.

**Effect:** `saturation` with `sine` modulator  
**Period:** 2 beats  
**Use case:** Breathing color effect, fade between grayscale and color

### Intensity Effects

#### strobe.edn
Fast strobe effect that flashes 8 times per beat with a 10% duty cycle.

**Effect:** `intensity` with `square` modulator  
**Period:** 0.125 beats (8x per beat)  
**Duty cycle:** 10%  
**Use case:** High-energy strobe effects

### Shape Effects

#### x-position-oscillator.edn
Oscillates horizontal position left and right.

**Effect:** `offset` with `sine` modulator on x-axis  
**Range:** -0.3 to 0.3  
**Period:** 1 beat  
**Use case:** Horizontal movement, side-to-side motion

#### y-position-oscillator.edn
Oscillates vertical position up and down.

**Effect:** `offset` with `sine` modulator on y-axis  
**Range:** -0.3 to 0.3  
**Period:** 1 beat  
**Use case:** Vertical movement, bouncing motion

#### shrink-grow-oscillator.edn
Oscillates scale to create a breathing/pulsing effect. Both X and Y scale together.

**Effect:** `scale` with `sine` modulator on both axes  
**Range:** 0.7 to 1.3  
**Period:** 2 beats  
**Use case:** Breathing effect, pulsing shapes

## File Format

Each preset is an EDN file with the following structure:

```clojure
{:name "Display Name"
 :description "Description of what the effect does"
 :category :color/:intensity/:shape
 :effects [{:effect-id :effect-name
            :enabled true
            :params {:param-name value-or-modulator}}]}
```

### Modulator Format

Modulators are specified as maps with a `:type` key:

```clojure
{:type :sine
 :min 0.0
 :max 1.0
 :period 2.0
 :phase 0.0
 :loop-mode :loop
 :time-unit :beats}
```

**Common modulator types:**
- `:sine` - Smooth sine wave oscillation
- `:triangle` - Linear ramp up and down
- `:sawtooth` - Linear ramp with instant reset
- `:square` - On/off switching with duty cycle
- `:point-index` - Per-point modulation based on point position

**Time units:**
- `:beats` - Synchronized to BPM (default)
- `:seconds` - Real-time, independent of BPM

## Usage

These presets can be loaded through the application's effect loading system. They define complete effect chains that can be applied to any cue.

## Creating Custom Presets

To create your own presets:

1. Create a new `.edn` file in this directory
2. Follow the format shown above
3. Use the effect IDs from the registered effects system
4. Configure parameters with either static values or modulator configs
5. Test the preset in the application

Refer to `src/laser_show/animation/effects.clj` for available effect IDs and their parameters.
