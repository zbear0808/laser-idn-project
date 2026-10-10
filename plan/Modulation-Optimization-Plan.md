* [ ]

# Low-Latency MIDI & OSC Modulation Architecture Plan

## 1. Analysis: Why Point Data is Already Efficient

* [ ] The previous suggestion to introduce a mutable Java record for per-point modulation context was fundamentally incorrect for the laser-show engine:


1. **Laser Points Are Already Compact Primitive Vectors**:

   - In `laser-show.animation.effects`, points are 5-element vectors `[x y r g b]`.
   - Effect chains compose transducers via `(comp ...)` and execute in a single eager pass `(into [] transducer points)` without intermediate sequences or map allocations.
2. **MIDI & OSC Modulation Is Not Per-Point**:

   - In `laser-show.animation.effects/resolve-param-fn`, modulators are classified as either per-point (spatial drivers) or global (time, LFO, MIDI, OSC).
   - Global modulators (including MIDI CC and OSC controls) are resolved **once per frame/effect** and wrapped in a constant function for the point transducer.
   - There is **zero per-point map allocation** for MIDI or OSC inputs.
3. **Spatial Per-Point Modulators Are Already Compiled**:

   - Modulators that do vary across points (`:pos-x`, `:pos-y`, `:radial`, `:point-index`) use pre-compiled math closures via `compile-per-point-modulator`. They take primitive numbers directly and do not allocate maps during rendering.
4. **Conclusion**:

   - No mutable records or POJOs are needed. The point data pipeline is already fast and allocation-free.
   - The real issues causing latency or broken behavior live entirely in input normalization, routing keys, and root atom contention.

---

## 2. Root Causes of Current MIDI/OSC Issues

### A. Double-Normalization Bug in MIDI CC

- `laser-show.input.midi/midi-msg->event` normalizes incoming raw values (0–127) to 0.0–1.0 via `events/midi-to-normalized`.
- `laser-show.animation.modulator-evaluators/eval-midi` divides by 127.0 a second time (`(/ cc-val 127.0)`).
- **Impact**: Full knob travel (127) only modulates by 1/127 (~0.0078), making MIDI modulation appear broken or virtually imperceptible.

### B. Broken OSC Routing Keys

- `laser-show.animation.modulator-evaluators/eval-osc` expects input values stored at `[:osc path]` (e.g. `[:osc "/fader1"]`).
- `laser-show.events.handlers.input-mapper/process-input-event!` hardcodes updates to `[:midi channel control]` regardless of whether the incoming event was MIDI or OSC, dropping the OSC path.
- **Impact**: OSC modulators never receive incoming values and always evaluate to minimum or default.

### C. Root State Atom Contention on High-Frequency Continuous Input

- When a user twists a MIDI encoder or moves an OSC fader, events arrive at 50–200 Hz.
- Currently, `input-mapper/update-input-value!` updates `[:backend :input :values]` by calling `state/assoc-in-state!`.
- `state/assoc-in-state!` calls `state/swap-state!`, which recreates the entire cljfx root context on every swap.
- **Impact**: High-rate continuous controller streams cause CAS retries, GC churn across persistent state trees, and UI stutter.

---

## 3. Architecture & Structural Changes

### A. State Management: Fast Dedicated Input Registry

Continuous input values do not belong inside the root cljfx UI state atom. They need a dedicated, lock-free continuous store.

- **New/Refactored Location**:
  - `laser-show.input.router` or a dedicated input store namespace `laser-show.input.store`.
- **State Representation**:
  - A dedicated atom or concurrent map holding `{source-key -> normalized-double-value}`.
  - Keys:
    - MIDI: `[:midi channel cc-number]` -> float in `0.0..1.0`
    - OSC: `[:osc address-path]` -> float in `0.0..1.0` and `[:osc channel control-number]` -> float in `0.0..1.0`
    - Generic/Gamepad: `[:input source-key]` -> float in `0.0..1.0`
- **Functions to update & query**:
  - `laser-show.input.router/set-continuous-value!`: parameters `[source-key value]`
  - `laser-show.input.router/get-continuous-value`: parameters `[source-key default-value]`
  - `laser-show.input.router/get-all-continuous-values`: no parameters, returns snapshot map for frame context.

### B. Event Handling: Input Mapper Dispatch

- **Namespace to update**: `laser-show.events.handlers.input-mapper`
- **Function to update**: `process-input-event!`
  - Extract `:source`, `:channel`, `:control`, `:path`, and `:value` from the event.
  - Route continuous events to `set-continuous-value!` with their correct source keys:
    - If `:source` is `:midi`, key is `[:midi channel control]`.
    - If `:source` is `:osc`, update both `[:osc path]` (if present) and `[:osc channel control]`.
  - Discrete trigger events (pads, buttons, transport) remain routed through the trigger map to UI/grid actions.

### C. Modulator Evaluation

- **Namespace to update**: `laser-show.animation.modulator-evaluators`
- **Functions to update**:
  - `eval-midi`:
    - Parameters: `[config context]`
    - Remove the redundant `(/ cc-val 127.0)` division since values in `input-values` are already normalized to `[0.0, 1.0]`.
  - `eval-osc`:
    - Parameters: `[config context]`
    - Read from `[:osc path]` in `input-values` (with fallback to `[:osc 0 control]` if path is not specified).
- **Namespace to update**: `laser-show.backend.multi-engine` and `laser-show.services.frame-service`
  - When building `timing-ctx` and `context` for rendering, read `input-values` directly from the fast input store rather than pulling from the cljfx root state atom `[:backend :input :values]`.

### D. UI Modulator Parameter Component

- **Namespace to inspect/reuse**: `laser-show.views.components.modulator-param-control`
  - Already supports selecting modulator types (`:midi`, `:osc`, `:sine`, etc.).
  - Parameters for MIDI: `:channel`, `:cc`, `:min`, `:max`.
  - Parameters for OSC: `:path`, `:min`, `:max`.
  - Ensure the UI correctly preserves and saves `:path` when configuring OSC modulators.

---

## 4. Pure-Function Unit Test Plan

To ensure robustness, all data conversion, normalization, and evaluation functions must be covered by pure-function unit tests with zero mocking and zero side effects.

### Test Namespace: `test/laser_show/animation/modulation_test.clj` and `test/laser_show/input/events_test.clj`

#### 1. Input Event Parsing & Normalization Tests

- **Target**: `laser-show.input.midi/midi-msg->event`
  - Parameters tested: raw MIDI maps with status bytes 176–191 (CC), data1 (controller), data2 (0, 64, 127).
  - Assertions:
    - Return value has `:type :control-change` and `:source :midi`.
    - Value is normalized to `0.0` for 0, `0.5039...` for 64, and `1.0` for 127.
- **Target**: `laser-show.input.osc/osc-msg->events`
  - Parameters tested: OSC messages with path `/fader1`, args `[0.0]`, `[0.5]`, `[1.0]`.
  - Assertions:
    - Address mappings produce correct `:control-change` events.
    - Unmapped float messages produce valid continuous events with the address preserved.

#### 2. Modulator Evaluator Tests

- **Target**: `laser-show.animation.modulator-evaluators/eval-midi`
  - Parameters tested:
    - Config: `{:type :midi :channel 0 :cc 1 :min 10.0 :max 50.0}`
    - Context with `input-values`: `{:input-values {[:midi 0 1] 0.0}}` -> asserts output `10.0`.
    - Context with `input-values`: `{:input-values {[:midi 0 1] 0.5}}` -> asserts output `30.0`.
    - Context with `input-values`: `{:input-values {[:midi 0 1] 1.0}}` -> asserts output `50.0`.
    - Missing input in context -> asserts fallback to `10.0` (`:min`).
- **Target**: `laser-show.animation.modulator-evaluators/eval-osc`
  - Parameters tested:
    - Config: `{:type :osc :path "/fader1" :min -1.0 :max 1.0}`
    - Context with `input-values`: `{:input-values {[:osc "/fader1"] 0.0}}` -> asserts output `-1.0`.
    - Context with `input-values`: `{:input-values {[:osc "/fader1"] 0.5}}` -> asserts output `0.0`.
    - Context with `input-values`: `{:input-values {[:osc "/fader1"] 1.0}}` -> asserts output `1.0`.

#### 3. Parameter Resolution & Effect Binding Tests

- **Target**: `laser-show.animation.modulation/resolve-param`
  - Parameters tested: static numeric values, active MIDI/OSC modulator configs, inactive modulator configs (`:active? false`).
  - Assertions:
    - Static numbers return directly.
    - Inactive modulators return the static `:value` or midpoint without evaluation.
    - Active modulators resolve correctly according to context `input-values`.
- **Target**: `laser-show.animation.effects/resolve-param-fn`
  - Parameters tested: `params` containing a MIDI modulator, `ctx` with `timing-ctx`.
  - Assertions:
    - Returns a constant function `(fn [x y idx] resolved-val)`.
    - Evaluates the modulator once, rather than evaluating inside the point loop.

---

## 5. Architectural Checklist Before Implementation

- [X] Verify point data structure: 5-element vector, transducer pipeline (no changes needed).
- [X] Decouple continuous inputs from root cljfx `!state` atom to a fast concurrent input registry.
- [X] Fix double normalization in `eval-midi`.
- [X] Fix OSC path key routing in `input-mapper` and `eval-osc`.
- [X] Read `input-values` for frame rendering directly from the fast input store.
- [X] Add comprehensive pure-function unit tests covering input parsing, normalization, and evaluation.
