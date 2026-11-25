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
    - Link and following its tempo are separate local controls
   - UI shows both Carabiner and Link status"
  (:require [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.animation.show-time :as show-time]
            [laser-show.events.helpers :as h]
            [laser-show.input.link :as link]
            [laser-show.routing.zone-effects :as ze]
            [laser-show.routing.zone-maps :as zone-maps]
            [laser-show.routing.zone-patterns :as zone-patterns]
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

(defn- commit-pending-style
  "state with a pattern pads' switch that waits landed now (zones plan Z5),
   for the same reason."
  [state]
  (if-let [pending (get-in state [:pattern-pads :pending])]
    (zone-patterns/commit-style state (:style pending))
    state))

(defn- handle-transport-stop
  "Stop playback and restart every clock (the global one and each cue's); a
   pending zone map switch, and a pattern pads' one, land now."
  [{:keys [state]}]
  {:state (-> state
              (assoc-in [:playback :playing?] false)
              (assoc-in [:playback :active-cue] nil)
              (request :restart)
              (commit-pending-switch)
              (commit-pending-style))})

(defn- handle-transport-retrigger
  "Retrigger the current animation: restart every clock; a pending zone map
   switch, and a pattern pads' one, land now."
  [{:keys [state]}]
  {:state (-> state
              (request :restart)
              (commit-pending-switch)
              (commit-pending-style))})


;; Link Events

(defn- follow-link-tempo [state bpm]
  ;; Check both switches on the event thread: a report queued while sync
  ;; was on must not change the app's tempo after the user turns it off.
  (if (link/should-follow-bpm? state bpm)
    (assoc-in state [:timing :bpm] (double bpm))
    state))

(defn- with-link-settings [state link-state]
  (follow-link-tempo (assoc-in state [:backend :link] link-state) (:link-bpm link-state)))

(defn- set-link-bpm-sync [state enabled?]
  (let [settings (get-in state [:backend :link])]
    (with-link-settings state (if enabled? (link/enable-sync settings) (link/disable-sync settings)))))

(defn- handle-timing-toggle-link-sync
  "Toggle Ableton Link BPM sync on/off."
  [{:keys [state]}]
  {:state (set-link-bpm-sync state (not (get-in state [:backend :link :sync-enabled?])))})

(defn- handle-timing-link-bpm-changed
  "Cache a reported tempo for display; follow it only while sync is active."
  [{:keys [state bpm]}]
  {:state (-> (cond-> state
                (link/bpm-changed? (get-in state [:backend :link :link-bpm]) bpm)
                (assoc-in [:backend :link :link-bpm] bpm))
              (follow-link-tempo bpm))})

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
   cue's decay) at the position :position-ids names. The cue stops only if
   its intent still names that run and the ids are this state's
   (cue-timing/position-ids): a cue started again since (a new run), moved,
   sent elsewhere by a seek or a restart (Stop, Retrigger), or whose
   timeline follows timecode now (the chase's to stop) is left as it is; so
   is an end without a run or without both ids. Removing the cue can stop
   the transport, never start it (cue-timing/deactivate-cue)."
  [{:keys [cell run position-ids state]}]
  (let [playback (:playback state)
        intent (get-in playback [:active-cues cell])]
    {:state (if (and (some? run)
                     (= run (:run intent))
                     (not (:chasing? intent))
                     (not (playback/chases-mtc? (get-in state [:chains :cue-chains cell])))
                     (= position-ids (cue-timing/position-ids playback cell)))
              (update state :playback cue-timing/deactivate-cue cell)
              state)}))

(def ^:private chase-report-keys
  "What a :show-clock/chase report says (show-time's chase reports)."
  [:cell :active? :document-id :source-version :expected-owner :chase-id :start])

(defn- handle-show-clock-chase
  "The show clock saw timecode take the cell over (:active? true) or let go
   of it (false), for the show, the cue version and the owner the report
   names (show-time's chase reports). One for another show (a New or an
   Open since, or none named) is dropped at once ({}). Otherwise the report
   goes on to :show-clock/apply-chase with what the clock chases at the
   cell now (:show-clock/read :chase-cell): timecode may have stopped, or
   come back, since it was sent."
  [{:keys [cell state] :as event}]
  (if (and (some? (:document-id event)) (h/same-document? state event))
    {:show-clock/read {:chase-cell cell
                       :on-read (assoc (select-keys event chase-report-keys) :event/type :show-clock/apply-chase)}}
    {}))

(defn- owner-kind
  "The kind of a playback/intent-owner: :none, :run, :chase or :other."
  [owner]
  (if (vector? owner) (first owner) owner))

(defn- install-chase
  "playback with timecode's marker for episode k at cell, in place of owner
   there. A marker rebound to a new episode leaves the focus and the
   transport as they are. Any other takes the focus, and the transport
   plays if it did, or if start, the episode's start authority, still
   allows it: timecode entered while the transport was free (:allowed?),
   with no Stop or Retrigger since (the same :restart id) and nothing held
   now. So an On that Stop overtook holds its cue, as Stop holds the others,
   until Play."
  [playback cell owner k start]
  (let [playback' (assoc-in playback [:active-cues cell] {:chasing? true :timeline? true :chase-id k})]
    (if (= :chase (owner-kind owner))
      playback'
      (assoc playback'
             :active-cell cell
             :playing? (or (true? (:playing? playback))
                           (and (true? (:allowed? start))
                                (contains? start :restart)
                                (= (:restart start) (get-in playback [:requests :restart :id]))
                                (empty? (:active-cues playback))))))))

(defn- handle-show-clock-apply-chase
  "A :show-clock/chase report with :clock {:chase-id}, the episode the show
   clock chases at the cell now (nil: none). It applies only while all it
   was decided for still holds, a missing field holding nothing: the same
   show (:document-id, never nil), the same version of the cell's cue
   (:source-version, nil for one gone or not following:
   playback/source-version) and the same owner there (:expected-owner,
   playback/intent-owner, :none for an empty cell); then
   - On (:active? true): the cue follows timecode and the clock is still on
     the report's episode (:chase-id): its marker goes in (install-chase),
     unless a grid press stopped the cue for that very episode
     ([:playback :chase-stopped], follow-up B7); another episode ends that
     stop;
   - Off (:active? false): the clock chases nothing there: a chase marker
     goes, its cue gone or not; a run only while its cue follows timecode.
     It stops as any cue stops (cue-timing/deactivate-cue): the last one
     stops the transport, others leave it as it is.
   Anything else changes nothing ({}): an On late for timecode that
   stopped, an Off late for timecode that came back, a report for a cue
   since replaced or an owner since gone, a duplicate."
  [{:keys [cell active? document-id source-version expected-owner chase-id start clock state] :as event}]
  (let [intent (get-in state [:playback :active-cues cell])
        owner (playback/intent-owner intent)
        version (playback/source-version state cell)
        observed (:chase-id clock)
        stopped (get-in state [:playback :chase-stopped])
        holds? (and (some? document-id)
                    (h/same-document? state event)
                    (contains? event :source-version)
                    (= source-version version)
                    (contains? event :expected-owner)
                    (= expected-owner owner)
                    (contains? clock :chase-id))]
    (cond
      (not holds?) {}

      (true? active?)
      (if (and (some? version) (some? chase-id) (= chase-id observed) (not= chase-id (get stopped cell)))
        {:state (cond-> (update state :playback install-chase cell owner chase-id start)
                  (contains? stopped cell) (update-in [:playback :chase-stopped] dissoc cell))}
        {})

      (and (false? active?)
           (nil? observed)
           (or (= :chase (owner-kind owner))
               (and (= :run (owner-kind owner)) (some? version))))
      {:state (update state :playback cue-timing/deactivate-cue cell)}

      :else {})))

(defn- handle-show-clock-zone-map-due
  "The show clock reached the beat a zone map switch waits for (zones plan
   Z4): it lands, if :id is still the pending one (another press may have
   replaced it since). The clock already routed by it from that beat."
  [{:keys [id state]}]
  {:state (if (and (some? id) (= id (get-in state [:zone-map :pending :id])))
            (zone-maps/commit state (get-in state [:zone-map :pending :map]))
            state)})

(defn- handle-show-clock-pattern-due
  "The show clock reached the beat a pattern pads' switch waits for (zones
   plan Z5): it lands, if :id is still the one waiting. The clock already
   played it from that beat."
  [{:keys [id state]}]
  {:state (if (and (some? id) (= id (get-in state [:pattern-pads :pending :id])))
            (zone-patterns/commit-style state (get-in state [:pattern-pads :pending :style]))
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
  "Enable or disable read-only Link. Re-enabling resumes BPM sync if selected."
  [{:keys [state]}]
  (let [link-enabled? (get-in state [:backend :link :link-enabled?] false)
        link-state (get-in state [:backend :link])
        new-link-state (if link-enabled?
                         (link/disable-link! link-state)
                         (link/enable-link! link-state))]
    {:state (with-link-settings state new-link-state)}))

(defn- handle-timing-link-set-sync-enabled
  "Set the BPM-sync preference from the Settings checkbox."
  [{:keys [state fx/event]}]
  {:state (set-link-bpm-sync state event)})

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
  "Update Carabiner connection status (app/connect-link!'s result at
   start-up), and the status listener when the event has one. The other
   link keys are kept."
  [{:keys [state connected? listener]}]
  {:state (update-in state [:backend :link]
                     #(cond-> (assoc % :carabiner-connected? connected?)
                        listener (assoc :listener listener)))})

(defn- handle-timing-link-enabled
  "Update Link enabled status (internal event from link service)."
  [{:keys [state enabled?]}]
  {:state (with-link-settings state (assoc (get-in state [:backend :link]) :link-enabled? enabled?))})


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
    :show-clock/apply-chase (handle-show-clock-apply-chase event)
    :show-clock/status (handle-show-clock-status event)
    :show-clock/zone-map-due (handle-show-clock-zone-map-due event)
    :show-clock/pattern-due (handle-show-clock-pattern-due event)
    
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
