(ns laser-show.events.handlers.timing
  "Event handlers for timing, BPM, and transport control.
   
   Handles:
   - BPM settings and tap tempo
   - Transport controls (play/stop/retrigger)
   - External BPM sync (Ableton Link via beat-carabiner)
   
   Link Architecture:
   - Carabiner daemon auto-connects on app startup
   - Button toggles Link sync mode (:passive/:off)
   - UI shows both Carabiner and Link status"
  (:require [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.events.helpers :as h]
            [laser-show.input.link :as link]
            [laser-show.routing.zone-effects :as ze]
            [clojure.tools.logging :as log]))


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
  ;; Reset zone debug logging so we get one debug cycle on playback start
  (ze/reset-zone-debug!)
  {:state (assoc-in state [:playback :playing?] true)})

(defn- reset-all-active-cues
  "Reset timing accumulators for all active cues."
  [active-cues]
  (reduce-kv (fn [acc k v]
               (assoc acc k (cue-timing/reset-cue-timing v)))
             {}
             active-cues))

(defn- handle-transport-stop
  "Stop playback and reset timing accumulators."
  [{:keys [state]}]
  {:state (-> state
              (assoc-in [:playback :playing?] false)
              (assoc-in [:playback :active-cue] nil)
              ;; Reset global clock
              (update-in [:timing :global-clock] cue-timing/reset-global-clock)
              ;; Reset all active cue timings
              (update-in [:playback :active-cues] reset-all-active-cues))})

(defn- handle-transport-retrigger
  "Retrigger the current animation and reset timing accumulators."
  [{:keys [state]}]
  {:state (-> state
              ;; Reset global clock
              (update-in [:timing :global-clock] cue-timing/reset-global-clock)
              ;; Reset all active cue timings
              (update-in [:playback :active-cues] reset-all-active-cues))})


;; Link Events


(defn- handle-timing-toggle-link-sync
  "Toggle Ableton Link BPM sync on/off."
  [{:keys [state]}]
  (let [current-sync (get-in state [:backend :link :sync-enabled?] false)
        link-state (get-in state [:backend :link])
        new-link-state (if current-sync
                         (link/disable-sync link-state)
                         (link/enable-sync link-state))]
    {:state (assoc-in state [:backend :link] new-link-state)}))

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
  "Toggle Link sync mode on/off.
   Carabiner must be connected first (auto on startup).
   This enables/disables Link by setting sync mode to :passive or :off."
  [{:keys [state]}]
  (let [link-enabled? (get-in state [:backend :link :link-enabled?] false)
        link-state (get-in state [:backend :link])
        new-link-state (if link-enabled?
                         (link/disable-link! link-state)
                         (link/enable-link! link-state))]
    {:state (assoc-in state [:backend :link] new-link-state)}))

(defn- handle-timing-link-set-sync-enabled
  "Enable or disable BPM sync from Link to app.
   This controls whether received Link BPM updates the app BPM.
   Link must be enabled first."
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
  "Enable or disable auto-connect Carabiner on startup."
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

(defn- handle-timing-carabiner-connected
  "Update Carabiner connection status (internal event from link service)."
  [{:keys [state connected?]}]
  {:state (assoc-in state [:backend :link :carabiner-connected?] connected?)})

(defn- handle-timing-link-enabled
  "Update Link enabled status (internal event from link service)."
  [{:keys [state enabled?]}]
  {:state (assoc-in state [:backend :link :link-enabled?] enabled?)})


;; Global Trigger Mode


(defn- handle-timing-set-global-trigger-mode
  "Set the global trigger mode override.
   
   Global trigger mode options:
   - :default - Use per-cue trigger mode settings
   - :toggle - Override all cues to toggle mode (click ON/OFF)
   - :retrigger - Override all cues to retrigger mode (always restart)"
  [{:keys [fx/event state]}]
  (let [;; Extract :id from fx/event (combo-box selected value)
        mode (or (:mode event) (:id event) :default)]
    (log/info "Setting global trigger mode:" {:mode mode :fx-event event})
    {:state (assoc-in state [:config :cue :trigger-mode] mode)}))


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
    
    ;; Internal Link events
    :timing/carabiner-connected (handle-timing-carabiner-connected event)
    :timing/link-enabled (handle-timing-link-enabled event)
    
    ;; Global trigger mode
    :timing/set-global-trigger-mode (handle-timing-set-global-trigger-mode event)
    
    ;; Unknown event in this domain
    {}))
