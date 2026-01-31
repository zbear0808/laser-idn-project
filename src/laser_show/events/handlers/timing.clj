(ns laser-show.events.handlers.timing
  "Event handlers for timing, BPM, and transport control.
   
   Handles:
   - BPM settings and tap tempo
   - Transport controls (play/stop/retrigger)"
  (:require [laser-show.events.helpers :as h]))


;; Timing Events


(defn- handle-timing-set-bpm
  "Set the BPM."
  [{:keys [bpm state]}]
  {:state (assoc-in state [:timing :bpm] (double bpm))})

(defn- handle-timing-tap-tempo
  "Record a tap for tap-tempo calculation.
   Clears old taps if the last tap was more than 2 seconds ago,
   preventing stale tap data from affecting BPM calculation."
  [{:keys [state] :as event}]
  (let [now (h/current-time-ms event)
        old-tap-times (get-in state [:timing :tap-times] [])
        last-tap (peek old-tap-times)
        ;; Clear tap times if last tap was more than 2000ms ago (30 BPM threshold)
        new-tap-times (if (or (nil? last-tap)
                              (> (- now last-tap) 2000))
                        [now]
                        (conj old-tap-times now))]
    {:state (assoc-in state [:timing :tap-times] new-tap-times)
     :timing/calculate-bpm true}))



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
    
    :transport/play (handle-transport-play event)
    :transport/stop (handle-transport-stop event)
    :transport/retrigger (handle-transport-retrigger event)
    
    ;; Unknown event in this domain
    {}))
