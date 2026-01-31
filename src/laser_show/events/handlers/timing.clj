(ns laser-show.events.handlers.timing
  "Event handlers for timing, BPM, and transport control.
   
   Handles:
   - BPM settings and tap tempo
   - Transport controls (play/stop/retrigger)
   - External BPM sync (Ableton Link)"
  (:require [laser-show.events.helpers :as h]
            [laser-show.input.link :as link]
            [laser-show.state.core :as state]))


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


;; Link Events


(defn- handle-timing-toggle-link-sync
  "Toggle Ableton Link BPM sync on/off."
  [{:keys [state dispatch-fn]}]
  (let [current-sync (get-in state [:backend :link :sync-enabled?] false)
        link-state (get-in state [:backend :link])
        new-link-state (if current-sync
                         (link/disable-sync link-state)
                         (link/enable-sync link-state))]
    {:state (assoc-in state [:backend :link] new-link-state)}))

(defn- handle-timing-link-connected
  "Update Link connection status."
  [{:keys [state connected?]}]
  {:state (assoc-in state [:backend :link :connected?] connected?)})

(defn- handle-timing-link-bpm-changed
  "Receive BPM update from Link network."
  [{:keys [state bpm]}]
  {:state (assoc-in state [:backend :link :link-bpm] bpm)})

(defn- handle-timing-sync-to-downbeat
  "Synchronize playback to Link's next downbeat.
   Uses phase-offset-target mechanism for smooth correction."
  [{:keys [state dispatch-fn]}]
  (let [accumulated-beats (get-in state [:playback :accumulated-beats] 0.0)]
    (link/sync-to-downbeat! dispatch-fn accumulated-beats)
    {:state state}))

(defn- handle-timing-set-phase-offset-target
  "Set the phase offset target for beat alignment.
   Used by external sync and tap-tempo resync."
  [{:keys [state offset]}]
  {:state (assoc-in state [:playback :phase-offset-target] offset)})


;; Link Settings Events


(defn- handle-timing-link-toggle
  "Toggle Link connection on/off."
  [{:keys [state dispatch-fn]}]
  (let [connected? (get-in state [:backend :link :connected?] false)
        link-state (get-in state [:backend :link])
        get-state-fn #(get-in (state/get-raw-state) [:backend :link])]
    (if connected?
      ;; Disconnect
      (let [new-link-state (link/stop-link! link-state)]
        {:state (assoc-in state [:backend :link] new-link-state)})
      ;; Connect
      (let [new-link-state (link/start-link! link-state dispatch-fn get-state-fn)]
        {:state (assoc-in state [:backend :link] new-link-state)}))))

(defn- handle-timing-link-set-sync-enabled
  "Enable or disable BPM sync from Link."
  [{:keys [state fx/event]}]
  (let [enabled? (boolean event)
        link-state (get-in state [:backend :link])
        new-link-state (if enabled?
                         (link/enable-sync link-state)
                         (link/disable-sync link-state))]
    {:state (assoc-in state [:backend :link] new-link-state)}))

(defn- handle-timing-link-set-beat-sync
  "Enable or disable downbeat alignment sync."
  [{:keys [state fx/event]}]
  {:state (assoc-in state [:backend :link :beat-sync?] (boolean event))})

(defn- handle-timing-link-set-auto-connect
  "Enable or disable auto-connect on startup."
  [{:keys [state fx/event]}]
  {:state (assoc-in state [:backend :link :auto-connect?] (boolean event))})

(defn- handle-timing-link-set-latency
  "Set latency compensation in milliseconds."
  [{:keys [state fx/event]}]
  {:state (assoc-in state [:backend :link :latency-ms] (int (or event 0)))})

(defn- handle-timing-link-peers-changed
  "Update the number of Link peers."
  [{:keys [state peers]}]
  {:state (assoc-in state [:backend :link :link-peers] (or peers 0))})


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
    
    ;; Link sync events
    :timing/toggle-link-sync (handle-timing-toggle-link-sync event)
    :timing/link-connected (handle-timing-link-connected event)
    :timing/link-bpm-changed (handle-timing-link-bpm-changed event)
    :timing/sync-to-downbeat (handle-timing-sync-to-downbeat event)
    :timing/set-phase-offset-target (handle-timing-set-phase-offset-target event)
    
    ;; Link settings events (from Settings tab)
    :timing/link-toggle (handle-timing-link-toggle event)
    :timing/link-set-sync-enabled (handle-timing-link-set-sync-enabled event)
    :timing/link-set-beat-sync (handle-timing-link-set-beat-sync event)
    :timing/link-set-auto-connect (handle-timing-link-set-auto-connect event)
    :timing/link-set-latency (handle-timing-link-set-latency event)
    :timing/link-peers-changed (handle-timing-link-peers-changed event)
    
    ;; Unknown event in this domain
    {}))
