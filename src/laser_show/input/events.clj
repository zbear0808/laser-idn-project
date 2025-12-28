(ns laser-show.input.events
  "Unified input event system for MIDI, OSC, and Keyboard inputs.
   All input sources produce standardized events that can be routed
   to handlers throughout the application.")

(def event-types
  "All supported event types in the system."
  #{:control-change   ; Continuous controller (knob, fader, etc.)
    :note-on          ; Note pressed (pad, key)
    :note-off         ; Note released
    :trigger          ; Generic trigger (button press, key press)
    :trigger-release  ; Generic trigger release
    :program-change   ; Program/preset change
    :pitch-bend})     ; Pitch bend wheel

(defn control-change
  "Creates a control change event (knob/fader movement).
   - source: Input source identifier (:midi, :osc, :keyboard)
   - channel: Channel number (0-15 for MIDI, arbitrary for others)
   - control: Control number or identifier
   - value: Normalized value 0.0-1.0"
  [source channel control value]
  {:type :control-change
   :source source
   :channel channel
   :control control
   :value (max 0.0 (min 1.0 (double value)))
   :timestamp (System/currentTimeMillis)})

(defn note-on
  "Creates a note-on event (pad/key press).
   - source: Input source identifier
   - channel: Channel number
   - note: Note number (0-127 for MIDI)
   - velocity: Normalized velocity 0.0-1.0"
  [source channel note velocity]
  {:type :note-on
   :source source
   :channel channel
   :note note
   :velocity (max 0.0 (min 1.0 (double velocity)))
   :timestamp (System/currentTimeMillis)})

(defn note-off
  "Creates a note-off event (pad/key release).
   - source: Input source identifier
   - channel: Channel number
   - note: Note number"
  [source channel note]
  {:type :note-off
   :source source
   :channel channel
   :note note
   :velocity 0.0
   :timestamp (System/currentTimeMillis)})

(defn trigger
  "Creates a trigger event (button/key press).
   - source: Input source identifier
   - id: Trigger identifier (keyword like :play, :stop, :cue-1)
   - state: :pressed or :released"
  [source id state]
  {:type (if (= state :released) :trigger-release :trigger)
   :source source
   :id id
   :state state
   :timestamp (System/currentTimeMillis)})

(defn program-change
  "Creates a program change event.
   - source: Input source identifier
   - channel: Channel number
   - program: Program number"
  [source channel program]
  {:type :program-change
   :source source
   :channel channel
   :program program
   :timestamp (System/currentTimeMillis)})

;; ============================================================================
;; Event Predicates
;; ============================================================================

(defn control-change?
  "Returns true if event is a control change."
  [event]
  (= (:type event) :control-change))

(defn note-on?
  "Returns true if event is a note-on."
  [event]
  (= (:type event) :note-on))

(defn note-off?
  "Returns true if event is a note-off."
  [event]
  (= (:type event) :note-off))

(defn trigger?
  "Returns true if event is a trigger (pressed)."
  [event]
  (= (:type event) :trigger))

(defn trigger-release?
  "Returns true if event is a trigger release."
  [event]
  (= (:type event) :trigger-release))

(defn from-source?
  "Returns true if event is from the specified source."
  [event source]
  (= (:source event) source))

(defn midi-event?
  "Returns true if event is from MIDI."
  [event]
  (from-source? event :midi))

(defn osc-event?
  "Returns true if event is from OSC."
  [event]
  (from-source? event :osc))

(defn keyboard-event?
  "Returns true if event is from keyboard."
  [event]
  (from-source? event :keyboard))

;; ============================================================================
;; Event Matching
;; ============================================================================

(defn matches?
  "Returns true if event matches the given pattern.
   Pattern is a map where each key-value pair must match the event.
   Example: (matches? event {:type :note-on :channel 0})"
  [event pattern]
  (every? (fn [[k v]]
            (= (get event k) v))
          pattern))

(defn event-id
  "Returns a unique identifier for this event type (not instance).
   Useful for creating mappings.
   Example: [:midi :control-change 0 1] for MIDI CC 1 on channel 0"
  [event]
  (case (:type event)
    :control-change [(:source event) :control-change (:channel event) (:control event)]
    :note-on [(:source event) :note (:channel event) (:note event)]
    :note-off [(:source event) :note (:channel event) (:note event)]
    :trigger [(:source event) :trigger (:id event)]
    :trigger-release [(:source event) :trigger (:id event)]
    :program-change [(:source event) :program-change (:channel event)]
    [(:source event) (:type event)]))

;; ============================================================================
;; Value Scaling Utilities
;; ============================================================================

(defn midi-to-normalized
  "Converts MIDI value (0-127) to normalized (0.0-1.0)."
  [midi-value]
  (/ (double midi-value) 127.0))

(defn normalized-to-midi
  "Converts normalized (0.0-1.0) to MIDI value (0-127)."
  [normalized-value]
  (int (* normalized-value 127)))

(defn scale-value
  "Scales a normalized value (0.0-1.0) to a target range."
  [normalized-value min-val max-val]
  (+ min-val (* normalized-value (- max-val min-val))))

(defn unscale-value
  "Converts a value from a range to normalized (0.0-1.0)."
  [value min-val max-val]
  (/ (- value min-val) (- max-val min-val)))
