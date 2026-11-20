(ns laser-show.events.handlers.timing
  "Event handlers for timing, BPM, and transport control.
   
   Handles:
   - BPM settings and tap tempo
   - Transport controls (play/stop/retrigger)
   - External BPM sync (Ableton Link via beat-carabiner)
   - What the show clock reports back (:show-clock/*)
   
   Show time itself is the show clock's (laser-show.animation.show-time): these
   handlers write intents and requests to [:playback], which it applies at its
   next tick.
   
   Link Architecture:
   - Carabiner daemon auto-connects on app startup
   - Button toggles Link sync mode (:passive/:off)
   - UI shows both Carabiner and Link status"
  (:require [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.animation.show-time :as show-time]
            [laser-show.events.helpers :as h]
            [laser-show.input.link :as link]
            [laser-show.routing.zone-effects :as ze]
            [laser-show.routing.zone-maps :as zone-maps]
            [laser-show.timeline.playback :as playback]
            [laser-show.triggers.tap-tempo :as tap]
            [clojure.tools.logging :as log]))


(defn- request
  "state asking the show clock for kind (:restart, :align or :shift) once."
  ([state kind] (request state kind {}))
  ([state kind extra]
   (assoc-in state [:playback :requests kind] (assoc extra :id (random-uuid)))))


;; Timing Events


(defn- handle-timing-set-bpm
  "Set the BPM."
  [{:keys [bpm state]}]
  {:state (assoc-in state [:timing :bpm] (double bpm))})

(defn- handle-timing-tap-tempo
  "A tap of the tempo (the TAP button, a tap-tempo key, HIT), at the input's
   own time when it has one (:input-time), else when it is handled. The taps
   are the one algorithm's, triggers.tap-tempo, in [:timing :tap]: once the
   series has an interval the BPM is its tempo, in the same state, and every
   tap counted asks the show clock to align the beat phase to the nearest
   whole beat. A bounce changes nothing."
  [{:keys [state] :as event}]
  (let [now (or (:input-time event) (h/current-time-ms event))
        taps (get-in state [:timing :tap] (tap/make))
        taps' (tap/tap taps now)]
    {:state (if (identical? taps taps')
              state
              (cond-> (-> state
                          (assoc-in [:timing :tap] taps')
                          (request :align))
                (and (next (:taps taps')) (:bpm taps')) (assoc-in [:timing :bpm] (:bpm taps'))))}))



;; Transport Events


(defn- handle-transport-play
  "Start playback."
  [{:keys [state]}]
  ;; Reset zone debug logging so we get one debug cycle on playback start
  (ze/reset-zone-debug!)
  {:state (assoc-in state [:playback :playing?] true)})

(defn- commit-pending-switch
  "state with a pending zone map switch landed now (zones plan Z4, 06
   SQ-2): a restart puts the global clock back to beat 0, and the beat it
   waits for belongs to the old count."
  [state]
  (if-let [pending (get-in state [:zone-map :pending])]
    (zone-maps/commit state (:map pending))
    state))

(defn- handle-transport-stop
  "Stop playback and restart every clock (the global one and each cue's); a
   pending zone map switch lands now."
  [{:keys [state]}]
  {:state (-> state
              (assoc-in [:playback :playing?] false)
              (assoc-in [:playback :active-cue] nil)
              (request :restart)
              (commit-pending-switch))})

(defn- handle-transport-retrigger
  "Retrigger the current animation: restart every clock; a pending zone map
   switch lands now."
  [{:keys [state]}]
  {:state (-> state
              (request :restart)
              (commit-pending-switch))})


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
   Uses phase-offset-target mechanism for smooth correction.
   The Link query runs in the :link/sync-to-downbeat effect, which reads the
   show clock's beats and dispatches :timing/set-phase-offset-target."
  [_event]
  {:link/sync-to-downbeat {}})

(defn- handle-timing-set-phase-offset-target
  "Ask the show clock to move the phase targets by :offset (the global clock
   and every cue but timelines). Used by external sync."
  [{:keys [state offset]}]
  {:state (request state :shift {:offset (double (or offset 0.0))})})


;; What the show clock reports


(defn- handle-show-clock-cue-ended
  "The show clock saw the run at cell end (a timeline at its end, a vibe
   cue's decay): stop the cue, unless it was started again since (a new run)."
  [{:keys [cell run state]}]
  {:state (if (= run (get-in state [:playback :active-cues cell :run]))
            (update state :playback cue-timing/deactivate-cue cell)
            state)})

(defn- handle-show-clock-chase
  "The show clock started (:active? true) or stopped following timecode for
   the chasing timeline at cell."
  [{:keys [cell active? state]}]
  (let [intent (get-in state [:playback :active-cues cell])
        chases? (playback/chases-mtc? (get-in state [:chains :cue-chains cell]))]
    {:state (cond
              (and active? chases? (not (:chasing? intent)))
              (-> state
                  (assoc-in [:playback :active-cues cell] {:chasing? true :timeline? true})
                  (update :playback assoc :playing? true :active-cell cell))

              (and (not active?) intent (or chases? (:chasing? intent)))
              (update state :playback cue-timing/deactivate-cue cell)

              :else state)}))

(defn- handle-show-clock-zone-map-due
  "The show clock reached the beat a zone map switch waits for (zones plan
   Z4): it lands, if :id is still the pending one (another press may have
   replaced it since). The clock already routed by it from that beat."
  [{:keys [id state]}]
  {:state (if (and (some? id) (= id (get-in state [:zone-map :pending :id])))
            (zone-maps/commit state (get-in state [:zone-map :pending :map]))
            state)})

(defn- handle-show-clock-status
  "What views show a few times a second, from the show clock: the beat in the
   bar, the frame stats and the MTC text (show-time/status-paths)."
  [{:keys [status state]}]
  {:state (reduce-kv (fn [s k path] (assoc-in s path (get status k)))
                     state
                     show-time/status-paths)})


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
   
   Accepts events with :event/type in the :timing/*, :transport/* or
   :show-clock/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    :timing/set-bpm (handle-timing-set-bpm event)
    :timing/tap-tempo (handle-timing-tap-tempo event)
    
    :transport/play (handle-transport-play event)
    :transport/stop (handle-transport-stop event)
    :transport/retrigger (handle-transport-retrigger event)
    
    ;; Reports from the show clock
    :show-clock/cue-ended (handle-show-clock-cue-ended event)
    :show-clock/chase (handle-show-clock-chase event)
    :show-clock/status (handle-show-clock-status event)
    :show-clock/zone-map-due (handle-show-clock-zone-map-due event)
    
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
