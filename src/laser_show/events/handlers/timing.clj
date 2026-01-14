(ns laser-show.events.handlers.timing
  "Event handlers for timing, BPM, and transport control.
   
   Handles:
   - BPM settings and tap tempo
   - Quantization modes
   - Transport controls (play/stop/retrigger)
   - Phase resync for beat accumulation"
  (:require [laser-show.events.helpers :as h]))


;; Phase Resync Helpers
;;
;; These functions handle smooth phase correction when tap tempo is used.
;; Instead of jumping to the new phase, we calculate the shortest path
;; to phase 0 and smoothly interpolate over time.


(defn- calculate-phase-adjustment
  "Calculate the phase adjustment to align downbeat with current moment.
   Returns adjustment to add to phase-offset-target.
   
   If current-phase is <= 0.5, slow down (negative adjustment) to reach 0.
   If current-phase is > 0.5, speed up (positive adjustment) to reach 1.0 (which wraps to 0)."
  [accumulated-beats phase-offset]
  (let [current-phase (mod (+ (or accumulated-beats 0.0)
                              (or phase-offset 0.0))
                           1.0)]
    (if (<= current-phase 0.5)
      (- current-phase)           ;; Slow down to reach 0
      (- 1.0 current-phase))))    ;; Speed up to reach 0 (via 1.0)


;; Timing Events


(defn- handle-timing-set-bpm
  "Set the BPM."
  [{:keys [bpm state]}]
  {:state (assoc-in state [:timing :bpm] (double bpm))})

(defn- handle-timing-tap-tempo
  "Record a tap for tap-tempo calculation."
  [{:keys [state] :as event}]
  (let [now (h/current-time-ms event)]
    {:state (update-in state [:timing :tap-times] conj now)
     :timing/calculate-bpm true}))

(defn- handle-timing-bpm-calculated
  "Handle BPM calculation completion from tap tempo.
   Triggers phase resync to align modulator animations with the tap."
  [{:keys [bpm state]}]
  (let [{:keys [accumulated-beats phase-offset phase-offset-target]} (:playback state)
        adjustment (calculate-phase-adjustment accumulated-beats phase-offset)
        new-target (+ (or phase-offset-target 0.0) adjustment)]
    {:state (-> state
                (assoc-in [:timing :bpm] (double bpm))
                (assoc-in [:playback :phase-offset-target] new-target))}))

(defn- handle-timing-resync-phase
  "Manually resync phase to align downbeat with current moment.
   Use this for manual 'tap to sync' functionality."
  [{:keys [state]}]
  (let [{:keys [accumulated-beats phase-offset phase-offset-target]} (:playback state)
        adjustment (calculate-phase-adjustment accumulated-beats phase-offset)
        new-target (+ (or phase-offset-target 0.0) adjustment)]
    {:state (assoc-in state [:playback :phase-offset-target] new-target)}))

(defn- handle-timing-clear-taps
  "Clear tap tempo timestamps."
  [{:keys [state]}]
  {:state (assoc-in state [:timing :tap-times] [])})

(defn- handle-timing-set-quantization
  "Set quantization mode."
  [{:keys [mode state]}]
  {:state (assoc-in state [:timing :quantization] mode)})


;; Transport Events


(defn- handle-transport-play
  "Start playback."
  [{:keys [state]}]
  {:state (assoc-in state [:playback :playing?] true)})

(defn- handle-transport-stop
  "Stop playback and reset timing accumulators."
  [{:keys [state]}]
  {:state (-> state
              (assoc-in [:playback :playing?] false)
              (assoc-in [:playback :active-cell] nil)
              ;; Reset timing accumulators
              (assoc-in [:playback :accumulated-beats] 0.0)
              (assoc-in [:playback :accumulated-ms] 0.0)
              (assoc-in [:playback :phase-offset] 0.0)
              (assoc-in [:playback :phase-offset-target] 0.0)
              (assoc-in [:playback :last-frame-time] 0))})

(defn- handle-transport-retrigger
  "Retrigger the current animation and reset timing accumulators."
  [{:keys [state] :as event}]
  (let [now (h/current-time-ms event)]
    {:state (-> state
                (assoc-in [:playback :trigger-time] now)
                ;; Reset timing accumulators for fresh start
                (assoc-in [:playback :accumulated-beats] 0.0)
                (assoc-in [:playback :accumulated-ms] 0.0)
                (assoc-in [:playback :phase-offset] 0.0)
                (assoc-in [:playback :phase-offset-target] 0.0)
                (assoc-in [:playback :last-frame-time] 0))}))


;; Public API


(defn handle
  "Dispatch timing and transport events to their handlers.
   
   Accepts events with :event/type in the :timing/* or :transport/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    :timing/set-bpm (handle-timing-set-bpm event)
    :timing/tap-tempo (handle-timing-tap-tempo event)
    :timing/bpm-calculated (handle-timing-bpm-calculated event)
    :timing/resync-phase (handle-timing-resync-phase event)
    :timing/clear-taps (handle-timing-clear-taps event)
    :timing/set-quantization (handle-timing-set-quantization event)
    
    :transport/play (handle-transport-play event)
    :transport/stop (handle-transport-stop event)
    :transport/retrigger (handle-transport-retrigger event)
    
    ;; Unknown event in this domain
    {}))
