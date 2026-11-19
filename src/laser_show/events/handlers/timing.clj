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
            [laser-show.timeline.playback :as playback]
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

(def ^:private tap-history
  "Taps kept: all that the :timing/calculate-bpm effect reads
   (events/core.clj, the last 8). HIT taps on every press, so without a cap
   the history grows for as long as the presses come under 2 s apart."
  8)

(defn- handle-timing-tap-tempo
  "Record a tap for tap-tempo calculation and smoothly align beat phase.
   Clears old taps if the last tap was more than 2 seconds ago,
   preventing stale tap data from affecting BPM calculation, and keeps the
   last tap-history taps."
  [{:keys [state] :as event}]
  (let [now (h/current-time-ms event)
        old-tap-times (get-in state [:timing :tap-times] [])
        last-tap (peek old-tap-times)
        ;; Clear tap times if last tap was more than 2000ms ago (30 BPM threshold)
        new-tap-times (if (or (nil? last-tap)
                              (> (- now last-tap) 2000))
                        [now]
                        (let [taps (conj old-tap-times now)]
                          ;; A fresh vector: a subvec would hold the old one
                          (cond-> taps (> (count taps) tap-history) (->> (take-last tap-history) vec))))]
    {:state (-> state
                (assoc-in [:timing :tap-times] new-tap-times)
                (request :align))
     :timing/calculate-bpm true}))



;; Transport Events


(defn- handle-transport-play
  "Start playback."
  [{:keys [state]}]
  ;; Reset zone debug logging so we get one debug cycle on playback start
  (ze/reset-zone-debug!)
  {:state (assoc-in state [:playback :playing?] true)})

(defn- handle-transport-stop
  "Stop playback and restart every clock (the global one and each cue's)."
  [{:keys [state]}]
  {:state (-> state
              (assoc-in [:playback :playing?] false)
              (assoc-in [:playback :active-cue] nil)
              (request :restart))})

(defn- handle-transport-retrigger
  "Retrigger the current animation: restart every clock."
  [{:keys [state]}]
  {:state (request state :restart)})


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
