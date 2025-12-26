(ns laser-show.backend.cues
  "Cue storage and management for the laser show application.
   Handles cue triggering, sequencing, and persistence.
   
   Cues now support zone targeting:
   - :target {:type :zone :zone-id :zone-1} - Single zone
   - :target {:type :zone-group :group-id :left-side} - Zone group
   - :target {:type :zones :zone-ids #{:zone-1 :zone-2}} - Multiple zones
   
   Cues also support effect chains:
   - :effect-chain {:effects [{:effect-id :hue-shift :enabled true :params {...}} ...]}
   
   And can store animation objects directly:
   - :animation - The actual animation object (not persisted, created from preset-id)"
  (:require [laser-show.state.atoms :as state]
            [laser-show.state.persistent :as persist]
            [laser-show.backend.zone-router :as router]
            [laser-show.animation.effects :as effects]))

;; ============================================================================
;; Cue Definition
;; ============================================================================

(defn make-cue
  "Create a cue definition.
   - id: unique identifier for the cue
   - name: display name
   - preset-id: the animation preset to play
   - params: optional parameter overrides
   - target: zone target specification (default: zone-1)
   - duration: optional duration in ms (nil for infinite)
   
   Target can be:
   - {:type :zone :zone-id :zone-1} - Single zone
   - {:type :zone-group :group-id :left-side} - Zone group
   - {:type :zones :zone-ids #{:zone-1 :zone-2}} - Multiple zones
   - nil - Uses default zone"
  ([id name preset-id]
   (make-cue id name preset-id {} router/default-target nil))
  ([id name preset-id params]
   (make-cue id name preset-id params router/default-target nil))
  ([id name preset-id params target]
   (make-cue id name preset-id params target nil))
  ([id name preset-id params target duration]
   {:id id
    :name name
    :preset-id preset-id
    :params params
    :target target
    :duration duration
    :created-at (System/currentTimeMillis)}))

(defn make-cue-with-zone
  "Create a cue targeting a specific zone."
  [id name preset-id zone-id & {:keys [params duration]}]
  (make-cue id name preset-id params (router/make-zone-target zone-id) duration))

(defn make-cue-with-group
  "Create a cue targeting a zone group."
  [id name preset-id group-id & {:keys [params duration]}]
  (make-cue id name preset-id params (router/make-group-target group-id) duration))

(defn make-cue-with-zones
  "Create a cue targeting multiple specific zones."
  [id name preset-id zone-ids & {:keys [params duration]}]
  (make-cue id name preset-id params (router/make-zones-target zone-ids) duration))

;; ============================================================================
;; Cue CRUD Operations
;; ============================================================================

(defn add-cue!
  "Add a cue to the cue library."
  [cue]
  (state/add-cue! (:id cue) cue))

(defn get-cue
  "Get a cue by ID."
  [cue-id]
  (state/get-cue cue-id))

(defn update-cue!
  "Update a cue's properties."
  [cue-id updates]
  (when (get-cue cue-id)
    (let [updated (merge (get-cue cue-id) updates)]
      (state/add-cue! cue-id updated)
      updated)))

(defn remove-cue!
  "Remove a cue from the library."
  [cue-id]
  (state/remove-cue! cue-id))

(defn list-cues
  "Get all cues as a sequence."
  []
  (vals (state/get-cues)))

;; ============================================================================
;; Cue Triggering
;; ============================================================================

(defn trigger-cue!
  "Trigger a cue to start playing.
   Records trigger time for time-based modulators with 'once' mode.
   Returns the cue if found, nil otherwise."
  [cue-id]
  (when-let [cue (get-cue cue-id)]
    (let [trigger-time (System/currentTimeMillis)
          cue-with-trigger (assoc cue :trigger-time trigger-time)]
      (swap! state/!playback assoc :active-cue cue-with-trigger)
      cue-with-trigger)))

(defn stop-cue!
  "Stop the currently playing cue."
  []
  (swap! state/!playback assoc :active-cue nil))

(defn get-active-cue
  "Get the currently active cue."
  []
  (:active-cue @state/!playback))

(defn cue-active?
  "Check if a specific cue is currently active."
  [cue-id]
  (= cue-id (:id (:active-cue @state/!playback))))

;; ============================================================================
;; Cue Queue
;; ============================================================================

(defn queue-cue!
  "Add a cue to the queue."
  [cue-id]
  (when (get-cue cue-id)
    (swap! state/!playback update :cue-queue conj cue-id)))

(defn dequeue-cue!
  "Remove and return the next cue from the queue."
  []
  (let [queue (:cue-queue @state/!playback)]
    (when (seq queue)
      (swap! state/!playback update :cue-queue rest)
      (first queue))))

(defn clear-queue!
  "Clear the cue queue."
  []
  (swap! state/!playback assoc :cue-queue []))

(defn get-queue
  "Get the current cue queue."
  []
  (:cue-queue @state/!playback))

(defn advance-queue!
  "Stop current cue and trigger the next one in queue."
  []
  (stop-cue!)
  (when-let [next-cue-id (dequeue-cue!)]
    (trigger-cue! next-cue-id)))

;; ============================================================================
;; Cue Lists (sequences of cues)
;; ============================================================================

(defn make-cue-list
  "Create a cue list (sequence of cues).
   - id: unique identifier
   - name: display name
   - cue-ids: ordered vector of cue IDs"
  [id name cue-ids]
  {:id id
   :name name
   :cue-ids (vec cue-ids)
   :created-at (System/currentTimeMillis)})

(defn add-cue-list!
  "Add a cue list."
  [cue-list]
  (state/add-cue-list! (:id cue-list) cue-list))

(defn get-cue-list
  "Get a cue list by ID."
  [list-id]
  (state/get-cue-list list-id))

(defn update-cue-list!
  "Update a cue list's properties."
  [list-id updates]
  (when (get-cue-list list-id)
    (let [updated (merge (get-cue-list list-id) updates)]
      (state/add-cue-list! list-id updated)
      updated)))

(defn remove-cue-list!
  "Remove a cue list."
  [list-id]
  (state/remove-cue-list! list-id))

(defn list-cue-lists
  "Get all cue lists as a sequence."
  []
  (vals (state/get-cue-lists)))

(defn load-cue-list-to-queue!
  "Load all cues from a cue list into the queue."
  [list-id]
  (when-let [cue-list (get-cue-list list-id)]
    (clear-queue!)
    (doseq [cue-id (:cue-ids cue-list)]
      (queue-cue! cue-id))))

;; ============================================================================
;; Persistence
;; ============================================================================

(defn save-cues!
  "Save all cues to disk."
  []
  (persist/save-single! :cues))

(defn load-cues!
  "Load cues from disk."
  []
  (persist/load-single! :cues))

(defn save-cue-lists!
  "Save all cue lists to disk."
  []
  (persist/save-single! :cue-lists))

(defn load-cue-lists!
  "Load cue lists from disk."
  []
  (persist/load-single! :cue-lists))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize cue system by loading from disk."
  []
  (load-cues!)
  (load-cue-lists!))

(defn shutdown!
  "Save cues before shutdown."
  []
  (save-cues!)
  (save-cue-lists!))

;; ============================================================================
;; Effect Chain Management
;; ============================================================================

(defn set-cue-effect-chain!
  "Set the effect chain for a cue."
  [cue-id effect-chain]
  (update-cue! cue-id {:effect-chain effect-chain}))

(defn get-cue-effect-chain
  "Get the effect chain for a cue."
  [cue-id]
  (:effect-chain (get-cue cue-id)))

(defn add-effect-to-cue!
  "Add an effect to a cue's effect chain."
  [cue-id effect-instance]
  (let [cue (get-cue cue-id)
        current-chain (or (:effect-chain cue) (effects/empty-effect-chain))
        new-chain (effects/add-effect-to-chain current-chain effect-instance)]
    (update-cue! cue-id {:effect-chain new-chain})))

(defn remove-effect-from-cue!
  "Remove an effect from a cue's effect chain by index."
  [cue-id effect-index]
  (when-let [cue (get-cue cue-id)]
    (when-let [chain (:effect-chain cue)]
      (let [new-chain (effects/remove-effect-at chain effect-index)]
        (update-cue! cue-id {:effect-chain new-chain})))))

(defn update-cue-effect!
  "Update an effect in a cue's effect chain."
  [cue-id effect-index updates]
  (when-let [cue (get-cue cue-id)]
    (when-let [chain (:effect-chain cue)]
      (let [new-chain (effects/update-effect-at chain effect-index updates)]
        (update-cue! cue-id {:effect-chain new-chain})))))

(defn enable-cue-effect!
  "Enable an effect in a cue's effect chain."
  [cue-id effect-index]
  (when-let [cue (get-cue cue-id)]
    (when-let [chain (:effect-chain cue)]
      (let [new-chain (effects/enable-effect-at chain effect-index)]
        (update-cue! cue-id {:effect-chain new-chain})))))

(defn disable-cue-effect!
  "Disable an effect in a cue's effect chain."
  [cue-id effect-index]
  (when-let [cue (get-cue cue-id)]
    (when-let [chain (:effect-chain cue)]
      (let [new-chain (effects/disable-effect-at chain effect-index)]
        (update-cue! cue-id {:effect-chain new-chain})))))

;; ============================================================================
;; Animation Management
;; ============================================================================

(defn set-cue-animation!
  "Set the animation object for a cue.
   Note: Animation objects are not persisted, only kept in memory."
  [cue-id animation]
  (update-cue! cue-id {:animation animation}))

(defn get-cue-animation
  "Get the animation object for a cue."
  [cue-id]
  (:animation (get-cue cue-id)))

(defn get-active-cue-animation
  "Get the animation from the currently active cue."
  []
  (:animation (:active-cue @state/!playback)))

(defn get-active-cue-effect-chain
  "Get the effect chain from the currently active cue."
  []
  (:effect-chain (:active-cue @state/!playback)))

;; ============================================================================
;; Quick Cue Creation from Grid
;; ============================================================================

(defn create-cue-from-cell!
  "Create a cue from a grid cell assignment.
   Uses the cell position as part of the cue ID."
  [col row preset-id & {:keys [params duration]
                        :or {name nil
                             params {}}}]
  (let [cue-id (keyword (str "cell-" col "-" row))
        cue-name (or name (str "Cell " col "," row))
        cue (make-cue cue-id cue-name preset-id params router/default-target duration)]
    (add-cue! cue)
    cue))

;; ============================================================================
;; Cue with Effects Creation Helpers
;; ============================================================================

(defn make-cue-with-effects
  "Create a cue with an effect chain.
   effects should be a vector of effect instances."
  [id name preset-id effects & {:keys [params target duration]
                                 :or {params {}}}]
  (let [base-cue (make-cue id name preset-id params target duration)]
    (assoc base-cue :effect-chain {:effects (vec effects)})))

(defn create-effect-instance
  "Create an effect instance for use in cues.
   Convenience wrapper for fx/make-effect-instance."
  [effect-id & {:keys [enabled params] :or {enabled true params {}}}]
  (effects/make-effect-instance effect-id :enabled enabled :params params))

;; ============================================================================
;; Frame Generation with Effects
;; ============================================================================

(defn get-cue-frame
  "Get a frame from a cue's animation at the given time.
   Returns nil if cue has no animation."
  [cue-id time-ms]
  (when-let [cue (get-cue cue-id)]
    (when-let [anim (:animation cue)]
      (if (fn? anim)
        (anim time-ms)
        (when-let [get-frame-fn (:get-frame anim)]
          (get-frame-fn time-ms))))))

(defn get-cue-frame-with-effects
  "Get a frame from a cue's animation with effects applied.
   
   Parameters:
   - cue-id: The cue ID
   - time-ms: Current time in milliseconds
   - bpm: Current BPM for beat-synced effects
   
   Returns the frame with cue effects applied, or nil if no animation."
  [cue-id time-ms bpm]
  (when-let [cue (get-cue cue-id)]
    (when-let [anim (:animation cue)]
      (let [frame (cond
                    (fn? anim) (anim time-ms)
                    (map? anim) (when-let [get-frame-fn (:get-frame anim)]
                                  (get-frame-fn time-ms))
                    :else nil)
            trigger-time (:trigger-time cue)]
        (if-let [chain (:effect-chain cue)]
          (effects/apply-effect-chain frame chain time-ms bpm trigger-time)
          frame)))))

(defn get-active-cue-frame-with-effects
  "Get the current frame from the active cue with effects applied.
   
   Parameters:
   - time-ms: Current time in milliseconds
   - bpm: Current BPM for beat-synced effects
   
   Returns the frame with effects applied, or nil if no active cue."
  [time-ms bpm]
  (when-let [active-cue (:active-cue @state/!playback)]
    (when-let [anim (:animation active-cue)]
      (let [frame (cond
                    (fn? anim) (anim time-ms)
                    (map? anim) (when-let [get-frame-fn (:get-frame anim)]
                                  (get-frame-fn time-ms))
                    :else nil)
            trigger-time (:trigger-time active-cue)]
        (if-let [chain (:effect-chain active-cue)]
          (effects/apply-effect-chain frame chain time-ms bpm trigger-time)
          frame)))))
