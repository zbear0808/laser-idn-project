(ns laser-show.events.handlers.timing
  "Event handlers for timing, BPM, and transport control.
   
   Handles:
   - BPM settings and tap tempo
   - Quantization modes
   - Transport controls (play/stop/retrigger)"
  (:require [laser-show.events.helpers :as h]))


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
  "Stop playback."
  [{:keys [state]}]
  {:state (-> state
              (assoc-in [:playback :playing?] false)
              (assoc-in [:playback :active-cell] nil))})

(defn- handle-transport-retrigger
  "Retrigger the current animation."
  [{:keys [state] :as event}]
  (let [now (h/current-time-ms event)]
    {:state (assoc-in state [:playback :trigger-time] now)}))


;; Public API


(defn handle
  "Dispatch timing and transport events to their handlers.
   
   Accepts events with :event/type in the :timing/* or :transport/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    :timing/set-bpm (handle-timing-set-bpm event)
    :timing/tap-tempo (handle-timing-tap-tempo event)
    :timing/clear-taps (handle-timing-clear-taps event)
    :timing/set-quantization (handle-timing-set-quantization event)
    
    :transport/play (handle-transport-play event)
    :transport/stop (handle-transport-stop event)
    :transport/retrigger (handle-transport-retrigger event)
    
    ;; Unknown event in this domain
    {}))
