(ns laser-show.events.handlers.grid
  "Event handlers for grid cell operations.
   
   Handles:
   - Cell triggering and selection (with toggle/retrigger modes)
   - Cell content management (clear, move)
   - Clipboard operations (copy, paste)"
  (:require [laser-show.events.helpers :as h]
            [laser-show.animation.chains :as chains]
            [laser-show.state.migration :as migration]
            [laser-show.routing.targets :as targets]
            [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.timeline.playback :as playback]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.vibes.refs :as refs]
            [clojure.tools.logging :as log]))


(defn- resolve-trigger-mode
  "Resolve the effective trigger mode for a cue.
   Global setting overrides per-cue if not :default.
   
   Returns :toggle or :retrigger"
  [state col row]
  (let [global-mode (get-in state [:config :cue :trigger-mode] :default)
        cue-mode (get-in state [:chains :cue-chains [col row] :trigger-mode] :toggle)
        resolved-mode (if (= global-mode :default) cue-mode global-mode)]
    (log/info "Trigger mode resolution:"
              {:col col :row row
               :global-mode global-mode
               :cue-mode cue-mode
               :resolved-mode resolved-mode})
    resolved-mode))

(defn start-cue
  "Start the cue at [col row]: its intent in active-cues, as a new run, which
   the show clock starts at its next tick, and a :cue pulse for the tap
   reactions that follow any cue start. Timeline cues start from the top
   (live use); the timeline editor's Play starts from its cursor. A timeline
   following MIDI timecode isn't started: the timecode drives it. opts:
   :decay-beats and :hold? (cue-timing/activate-cue), :run (the run's id,
   a new random one without)."
  ([state col row] (start-cue state col row {}))
  ([state col row {:keys [decay-beats hold? run]}]
   (let [cue (get-in state [:chains :cue-chains [col row]])]
     (if (playback/chases-mtc? cue)
       state
       (-> state
           (update :playback cue-timing/activate-cue [col row] (or run (random-uuid))
                   {:timeline? (cue-chains/timeline-cue? cue) :start-beat 0.0
                    :decay-beats decay-beats :hold? hold?})
           (update-in [:playback :pulses :cue :id] (fnil inc 0)))))))

(defn- untrigger-cue
  "Remove a cue from active-cues. Sets playing? to false if no cues remain."
  [state col row]
  (update state :playback cue-timing/deactivate-cue [col row]))

(defn- cue-content?
  "Does [col row] hold a cue now, by the rule its cell shows
   (cue-chains/grid-content): a timeline, or a preset? Only then can a
   press start it."
  [state col row]
  (:has-content? (cue-chains/grid-content (get-in state [:chains :cue-chains [col row]]))))

(defn- stop-chase
  "state with the cue at cell, which timecode drives as the show clock's
   episode k (its marker), stopped as any cue stops (untrigger-cue: the
   last one stops the transport, another leaves it as it is), for that
   episode only ([:playback :chase-stopped]): the clock doesn't take it
   over again while k lasts, and a new episode plays (follow-up B7)."
  [state [col row :as cell] k]
  (cond-> (untrigger-cue state col row)
    (some? k) (assoc-in [:playback :chase-stopped cell] k)))

(defn- chase-stopped?
  "Is the cue at cell one that follows timecode and that a press stopped
   for an episode (stop-chase)?"
  [state cell]
  (and (contains? (get-in state [:playback :chase-stopped]) cell)
       (playback/chases-mtc? (get-in state [:chains :cue-chains cell]))))

(defn- follow-again
  "state with the cue at cell following timecode again: its stop let go
   of, so the show clock takes it over at its next tick while timecode runs
   inside it."
  [state cell]
  (update-in state [:playback :chase-stopped] dissoc cell))

(defn- handle-grid-trigger-cell
  "Press the cell at [col row]: a single primary click on it, or a bound
   input's press that isn't momentary. The press names the cell, not what
   the cell showed: it selects the cell, then what the cell holds as the
   press is handled (cue-content?) decides. A cue there is started or
   stopped by its trigger mode (resolve-trigger-mode): :toggle stops it
   while in play and starts it otherwise, :retrigger starts it as a new run.
   A cue that follows timecode toggles too: one the show clock chases stops
   until timecode enters it again (stop-chase), and one stopped so follows
   again (follow-again). Without a cue there, playback stays as it is."
  [{:keys [col row state]}]
  (let [state-with-selected (assoc-in state [:grid :selected-cell] [col row])]
    (if (cue-content? state col row)
      (let [trigger-mode (resolve-trigger-mode state-with-selected col row)
            intent (get-in state-with-selected [:playback :active-cues [col row]])
            cue-active? (contains? (get-in state-with-selected [:playback :active-cues]) [col row])]
        (log/info "Grid cell pressed:"
                  {:col col :row row
                   :trigger-mode trigger-mode
                   :cue-active? cue-active?})
        (case trigger-mode
          :toggle (cond
                    (:chasing? intent) {:state (stop-chase state-with-selected [col row] (:chase-id intent))}
                    cue-active? {:state (untrigger-cue state-with-selected col row)}
                    (chase-stopped? state [col row]) {:state (follow-again state-with-selected [col row])}
                    :else {:state (start-cue state-with-selected col row)})
          ;; :retrigger or any other value - always trigger/restart
          {:state (start-cue state-with-selected col row)}))
      ;; Pressing an empty cell just selects it
      {:state state-with-selected})))

(defn- handle-grid-activate-cell
  "Start a cue regardless of trigger mode (momentary press), selecting its
   cell, when the cell holds one now (cue-content?). An empty cell is left
   alone: not selected, nothing started."
  [{:keys [col row state]}]
  (if (cue-content? state col row)
    {:state (start-cue (assoc-in state [:grid :selected-cell] [col row]) col row)}
    {:state state}))

(defn- handle-grid-release-cell
  "Stop a cue if it is playing (momentary release). No-op otherwise."
  [{:keys [col row state]}]
  (if (contains? (get-in state [:playback :active-cues]) [col row])
    {:state (untrigger-cue state col row)}
    {:state state}))

(defn- handle-grid-select-cell
  "Select a cell in the grid without triggering playback."
  [{:keys [col row state]}]
  {:state (assoc-in state [:grid :selected-cell] [col row])})

(defn- handle-grid-move-cell
  "Move a cell's cue chain from one position to another, preserving any active
   cue state. Trigger-map bindings and vibes that named it follow."
  [{:keys [from-col from-row to-col to-row state]}]
  (let [cue-chain-data (get-in state [:chains :cue-chains [from-col from-row]])]
    (if cue-chain-data
      (let [active? (contains? (get-in state [:playback :active-cues]) [from-col from-row])
            state* (-> state
                       (update-in [:chains :cue-chains] dissoc [from-col from-row])
                       (assoc-in [:chains :cue-chains [to-col to-row]] cue-chain-data)
                       (update-in [:config :input :trigger-map]
                                  refs/move-trigger-targets [from-col from-row] [to-col to-row] :trigger-cue)
                       (refs/move-cue [from-col from-row] [to-col to-row])
                       ;; Move active cell if it was the source
                       (cond-> (= (get-in state [:playback :active-cell]) [from-col from-row])
                         (assoc-in [:playback :active-cell] [to-col to-row]))
                       ;; Move active cue entry if present
                       (cond-> active?
                         (-> (update-in [:playback :active-cues] dissoc [from-col from-row])
                             (assoc-in [:playback :active-cues [to-col to-row]]
                               (get-in state [:playback :active-cues [from-col from-row]])))))]
        {:state state*})
      {:state state})))

(defn- handle-grid-copy-cell
  "Copy a cell's cue chain to the clipboard, and its EDN to the OS
   clipboard (:clipboard/write)."
  [{:keys [col row state] :as event}]
  (h/handle-copy-to-clipboard state
                              [:chains :cue-chains [col row]]
                              :cue-chain
                              (h/current-time-ms event)))

(defn- handle-grid-paste-cell
  "Paste the clipboard's cue chain to a cell (h/with-clipboard: the OS
   clipboard first while nothing is copied in this app). A cue copied from an
   older build is converted as an old show is when it opens
   (state.migration/cue-z4), so it routes the same, and everything in it
   gets a new id (chains/fresh-ids), so two cells never share one."
  [{:keys [col row] :as event}]
  (h/handle-paste-from-clipboard event
                                 [:chains :cue-chains [col row]]
                                 :cue-chain
                                 (comp chains/fresh-ids migration/cue-z4)))

(defn- handle-grid-clear-cell
  "Clear a cell's cue chain, and the triggers and vibes that named it. A cue
   playing there stops as any cue stops (cue-timing/deactivate-cue): the
   last one stops the transport, another leaves it as it is."
  [{:keys [col row state]}]
  {:state (-> state
              (update-in [:chains :cue-chains] dissoc [col row])
              (update-in [:config :input :trigger-map] refs/remove-trigger-targets [col row] :trigger-cue)
              (refs/remove-cue [col row])
              (cond-> (contains? (get-in state [:playback :active-cues]) [col row])
                (update :playback cue-timing/deactivate-cue [col row])))})

(defn- handle-grid-set-column-target
  "Set or clear (nil) column col's default target (zones plan Z4), in normal
   form: what its cues draw to when they set none of their own. Without a
   column, nothing changes."
  [{:keys [col state] :as event}]
  (if (integer? col)
    (let [chosen (if (contains? event :target) (:target event) (:id (:fx/event event)))
          t (when-not (= :inherit chosen) (targets/normalize-target chosen))]
      {:state (update-in state [:grid :column-targets] #(if (some? t) (assoc (or % {}) col t) (dissoc (or % {}) col)))})
    {:state state}))

(defn- handle-grid-open-cell-editor
  "Open the appropriate editor for a cell based on its cue configuration.
   Dispatches :timeline-cue-editor/open for timeline cues,
   or :cue-chain/open-editor for standard cues."
  [{:keys [col row state]}]
  (let [cue-chain (get-in state [:chains :cue-chains [col row]])]
    {:dispatch (if (cue-chains/timeline-cue? cue-chain)
                 {:event/type :timeline-cue-editor/open :col col :row row}
                 {:event/type :cue-chain/open-editor :col col :row row})}))


;; Public API


(defn handle
  "Dispatch grid events to their handlers.
   
   Accepts events with :event/type in the :grid/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    :grid/trigger-cell (handle-grid-trigger-cell event)
    :grid/activate-cell (handle-grid-activate-cell event)
    :grid/release-cell (handle-grid-release-cell event)
    ;; A click's old name, the same press: the :has-content? it carried is ignored
    :grid/cell-clicked (handle-grid-trigger-cell event)
    :grid/select-cell (handle-grid-select-cell event)
    :grid/open-cell-editor (handle-grid-open-cell-editor event)
    :grid/move-cell (handle-grid-move-cell event)
    :grid/copy-cell (handle-grid-copy-cell event)
    :grid/paste-cell (handle-grid-paste-cell event)
    :grid/clear-cell (handle-grid-clear-cell event)
    :grid/set-column-target (handle-grid-set-column-target event)
    
    ;; Unknown event in this domain
    {}))
