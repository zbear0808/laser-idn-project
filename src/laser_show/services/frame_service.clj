(ns laser-show.services.frame-service
  "Frame service: renders cues into frames.
   
   This service:
   - Renders a cue chain into frames per destination zone (generate-frames-by-zone)
   - Applies effects from the effects grid and per-preset effects
   - Builds what the preview shows from a tick's rendered cues (preview-data)
   - Generates calibration frames for projector calibration mode
   
   The show clock (services/show_clock.clj) calls it once per playing cue per
   tick; the engines and the preview take their frames from that tick.
   
   Cue Chain Support:
   - Cue chains are sequential lists of presets
   - Each preset can have its own parameters and effect chain
   - Presets render one after another in time
   
   Zone Group Filtering:
   - Preview can be filtered by zone group to show only content routed to specific zones
   - Default filter is :all (shows content routed to :all zone group)
   - Filter nil means show all content regardless of routing (master view)
   
   Calibration Mode:
   - When a projector is in calibration mode, it receives a boundary box test pattern
   - The test pattern has corner pin applied for live preview during calibration
   - Only the calibrating projector receives the test pattern
   
   Performance Note:
   - All collection operations use eager evaluation (vec, mapv, filterv, keepv)
   - Lazy sequences avoided in frame generation hot paths"
  (:require [laser-show.state.core :as state]
            [laser-show.animation.presets :as presets]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.show-time :as show-time]
            [laser-show.animation.types :as t]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.backend.output-errors :as output-errors]
            [laser-show.common.util :as u]
            [laser-show.routing.zone-effects :as ze]
            [laser-show.input.store :as input-store]
            [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.timeline.model :as model]
            [laser-show.timeline.playback :as playback]
            [laser-show.animation.effects.shape]
            [laser-show.animation.effects.color]
            [laser-show.animation.effects.intensity]
            [laser-show.animation.effects.calibration]
            [laser-show.animation.effects.zone]))


;; Frame Generation


;; NOTE: All these functions use get-raw-state instead of get-state because
;; they are called from the show clock's thread, not the UI thread. Using
;; fx/sub-val from a background thread causes assertion errors.

(defn get-active-cell-data
  "DEPRECATED: Use show-time/active-runs for multi-cue support.
   
   Get the cell data for the currently active cell (first active cue).
   Returns a map with :cue-chain from the unified :chains domain.
   For backward compatibility, returns data for first active cue."
  []
  (let [s (state/get-raw-state)
        active-cues (get-in s [:playback :active-cues] {})]
    (when-let [first-cell (first (keys active-cues))]
      (let [[col row] first-cell
            cue-chain-data (get-in s [:chains :cue-chains [col row]])]
        (when (seq (:items cue-chain-data))
          {:cue-chain cue-chain-data})))))

(defn get-bpm
  "Get the current BPM."
  []
  (get-in (state/get-raw-state) [:timing :bpm] 120.0))

(defn is-playing?
  "Check if playback is active."
  []
  (get-in (state/get-raw-state) [:playback :playing?] false))

(defn get-preview-zone-filter
  "Get the current preview zone group filter from state.
   Returns:
   - nil - show all content regardless of destination (master view) - DEFAULT
   - :all, :left, :right, etc. - show only content routed to that zone group"
  []
  (get-in (state/get-raw-state) [:config :preview :zone-group-filter]))

(defn- matches-preview-zone?
  "Check if a cue's final target zones match the preview zone filter.
   
   Matching logic:
   - If preview-zone is nil: show all (master view) -> always true
   - If final-targets contains preview-zone: direct match -> true
   - Otherwise: no match -> false
   
   Note: Zone groups like :all are just names, not special. Content routed
   to :all only appears in preview when filter is :all or nil (master view)."
  [preview-zone final-targets]
  (or (nil? preview-zone)                    ;; nil = show all (master view)
      (contains? final-targets preview-zone))) ;; direct match only

(defn get-active-global-effects
  "Get all active effect instances from the effects grid.
   Returns an effect chain or nil if no active effects.
   
   Performance: Uses eager collection operations to avoid lazy evaluation overhead."
  []
  (let [s (state/get-raw-state)
        effect-chains (get-in s [:chains :effect-chains] {})]
    (when (seq effect-chains)
      ;; Use eager operations: into with transducer (comp filter mapcat)
      (let [active-effects (into []
                                 (comp (filter :active?)
                                       (mapcat :items))
                                 (vals effect-chains))]
        (when (seq active-effects)
          ;; Debug logging - uncomment to trace effect chain construction
          ;; (println "[DEBUG get-active-effects] Found" (count active-effects) "effects:"
          ;;          (mapv :effect-id active-effects))
          {:effects active-effects})))))

;; Cue Chain Rendering
;;
;; Cue chains render ALL enabled presets simultaneously, not sequentially.
;; This is achieved by:
;; 1. Rendering each preset/group recursively
;; 2. Applying effects at each level (preset effects, then group effects)
;; 3. Concatenating all point sequences with blanking points between them
;; 4. The galvo will draw all shapes rapidly, creating persistence of vision
;;
;; Timeline Support:
;; Items and effects may have :timeline/start and :timeline/duration (in beats).
;; If present, they only render when effective-beats is within [start, start+duration).
;; If absent, they default to "always on" (backward compatible).
;;
;; Effect Pipeline: Preset Effects → Group Effects → Concatenate → Grid Effects
;;
;; For IDN output, the combined points are sent as a single frame.
;; For preview, we display all shapes at once.

(defn- render-preset-instance
  "Render a single preset instance with its parameters.
   Generates a frame directly from the preset generator."
  [preset-instance _elapsed-ms]
  (let [{:keys [preset-id params]} preset-instance]
    (if (seq params)
      (presets/generate-frame-with-params preset-id params)
      (presets/generate-frame preset-id))))

(defn- window-beats
  "The beat position clip/effect windows are tested against. Defaults to
   :effective-beats; generate-frames-by-zone sets :window-beats so a looping
   cue's windows see the same loop region as its playhead (TL-25)."
  ^double [timing-ctx]
  (double (or (:window-beats timing-ctx) (:effective-beats timing-ctx 0.0))))

(defn- wrap-into-loop
  "Wrap beats into an enabled loop region [start, start + duration)."
  [beats {:keys [enabled? start duration]}]
  (let [beats (double beats)
        start (double (or start 0.0))
        duration (double (or duration 0.0))]
    (if (and enabled? (pos? duration) (>= beats (+ start duration)))
      (+ start (mod (- beats start) duration))
      beats)))

(defn- apply-item-effects
  "Apply an item's (preset or group) effect chain to a frame.
   Effects with :timeline/start and :timeline/duration are filtered
   so only those active at the current beat position are applied."
  [frame item elapsed-ms bpm trigger-time timing-ctx]
  (let [current-beats (window-beats timing-ctx)
        item-effects (->> (:effects item [])
                          (filterv #(model/active-at? % current-beats)))]
    (if (seq item-effects)
      (try
        (let [out (effects/apply-effect-chain frame {:effects item-effects} elapsed-ms bpm trigger-time timing-ctx)]
          (output-errors/ok! [:item-effects (:id item)])
          out)
        (catch Throwable e
          (output-errors/report! [:item-effects (:id item)] e)
          frame))
      frame)))

(defn- create-blanking-jump
  "Create blanking points to safely jump from one position to another.
   Returns a vector of blanking points that:
   1. Turn off laser at current position (source)
   2. Move to new position with laser off (destination)
   
   This prevents visible lines when galvos travel between shapes."
  [from-point to-point]
  (let [;; First: blanking point at source (turn off laser at current position)
        blank-at-source (when from-point
                          [(from-point t/X) (from-point t/Y) 0.0 0.0 0.0])
        ;; Second: blanking point at destination (move to new position with laser off)
        blank-at-dest (when to-point
                        [(to-point t/X) (to-point t/Y) 0.0 0.0 0.0])]
    (filterv some? [blank-at-source blank-at-dest])))

(defn concatenate-frames
  "Concatenate multiple frames with blanking points between them.
   Returns a frame (vector of points) or nil.
   
   The elapsed-ms parameter is kept for backwards compatibility but not used."
  [frames _elapsed-ms]
  (when (seq frames)
    (let [combined-points (reduce
                           (fn [acc frame]
                             (if (empty? frame)
                               acc
                               (if (empty? acc)
                                 ;; First frame - just add points
                                 (vec frame)
                                 ;; Subsequent frames - add blanking jump then points
                                 (let [last-point (peek acc)
                                       first-new-point (first frame)
                                       blanking (create-blanking-jump last-point first-new-point)]
                                   (-> acc
                                       (into blanking)
                                       (into frame))))))
                           []
                           frames)]
      (when (seq combined-points)
        combined-points))))

(defn- render-item-with-effects
  "Recursively render a cue chain item (preset or group) applying effects at each level.
   
   Timeline gating:
   - Checks :timeline/start and :timeline/duration on the item.
   - If the current beat position is outside the item's window, returns nil.
   - Items without timeline keys are always rendered (backward compatible).
   
   Effect Pipeline:
   - For presets: render → apply preset effects
   - For groups: render children → concatenate → apply group effects
   
   Returns a frame (vector of points) or nil if disabled/empty/outside timeline."
  [item elapsed-ms bpm trigger-time timing-ctx]
  (let [current-beats (window-beats timing-ctx)]
    (when (and (:enabled? item true)
               (model/active-at? item current-beats))
      (cond
        ;; Preset: render and apply its effects
        (= :preset (:type item))
        (when-let [frame (render-preset-instance item elapsed-ms)]
          (apply-item-effects frame item elapsed-ms bpm trigger-time timing-ctx))

        ;; Group: render children, concatenate, then apply group effects
        (chains/group? item)
        (let [;; Use eager u/keepv to avoid lazy evaluation in frame rendering hot path
              child-frames (u/keepv #(render-item-with-effects % elapsed-ms bpm trigger-time timing-ctx)
                                    (:items item []))
              concatenated (concatenate-frames child-frames elapsed-ms)]
          (when concatenated
            (apply-item-effects concatenated item elapsed-ms bpm trigger-time timing-ctx)))

        :else nil))))

(defn- generate-frame-from-cue-chain
  "Generate a frame from a cue chain using recursive item rendering.
   
   Renders ALL enabled presets/groups, applying effects at each level:
   1. Preset-level effects on each preset
   2. Group-level effects on concatenated group contents
   3. Final concatenation of all top-level items
   
   This creates the effect of all shapes being displayed at once via persistence of vision.
   
   Performance: Uses eager u/keepv to avoid lazy evaluation overhead in hot path."
  [cue-chain elapsed-ms bpm trigger-time timing-ctx]
  (let [items (:items cue-chain [])
        ;; Use eager u/keepv instead of lazy keep for frame rendering hot path
        rendered-frames (u/keepv #(render-item-with-effects % elapsed-ms bpm trigger-time timing-ctx) items)]
    (concatenate-frames rendered-frames elapsed-ms)))

(defn- render-item-tree-by-zone
  "Recursively render a cue chain item (preset or group) partitioned by destination zone.
   Applies effects at each level (preset effects on presets, group effects on group zone sub-frames).
   
   Args:
   - item: A preset or group map
   - parent-zone-id: Inherited zone group keyword from parent container (or nil)
   - elapsed-ms: Elapsed ms since cue trigger
   - bpm: Current BPM
   - trigger-time: Cue trigger timestamp
   - timing-ctx: Timing context map
   - tracks: Cue chain tracks vector (optional)
   - default-zone-id: Fallback zone group keyword
   
   Returns: Map of zone-group-id → LaserFrame (vector of points)"
  [item parent-zone-id elapsed-ms bpm trigger-time timing-ctx tracks default-zone-id]
  (let [current-beats (window-beats timing-ctx)]
    (if-not (and (:enabled? item true)
                 (model/active-at? item current-beats))
      {}
      (cond
        ;; Preset: render and apply preset effects, assign to resolved zone
        (= :preset (:type item))
        (let [zone-id (ze/resolve-item-zone-destination
                       item timing-ctx
                       {:tracks tracks
                        :default-zone-id default-zone-id
                        :inherited-zone parent-zone-id})]
          (if-let [frame (render-preset-instance item elapsed-ms)]
            (let [frame-with-fx (apply-item-effects frame item elapsed-ms bpm trigger-time timing-ctx)]
              (if (seq frame-with-fx)
                {zone-id frame-with-fx}
                {}))
            {}))

        ;; Group: resolve group's effective zone, recursively render children,
        ;; then apply group effects to each zone partition
        (chains/group? item)
        (let [group-zone (ze/resolve-item-zone-destination
                          item timing-ctx
                          {:tracks tracks
                           :default-zone-id default-zone-id
                           :inherited-zone parent-zone-id})
              children (:items item [])
              child-zone-maps (u/keepv
                               #(render-item-tree-by-zone
                                 % group-zone elapsed-ms bpm trigger-time timing-ctx tracks default-zone-id)
                               children)
              merged-child-frames (reduce
                                   (fn [acc child-map]
                                     (reduce-kv
                                      (fn [m zone-id frame]
                                        (update m zone-id
                                                (fn [existing]
                                                  (if existing
                                                    (concatenate-frames [existing frame] elapsed-ms)
                                                    frame))))
                                      acc
                                      child-map))
                                   {}
                                   child-zone-maps)]
          (reduce-kv
           (fn [acc zone-id sub-frame]
             (let [processed-frame (apply-item-effects sub-frame item elapsed-ms bpm trigger-time timing-ctx)]
               (if (seq processed-frame)
                 (assoc acc zone-id processed-frame)
                 acc)))
           {}
           merged-child-frames))

        :else {}))))

(defn- apply-global-effects-to-frame
  "Apply global effects from the effects grid to a frame using the show
   clock's global clock."
  [frame bpm]
  (let [effect-chain (get-active-global-effects)]
    (if effect-chain
      (let [global-clock (show-time/global-clock)
            input-values (input-store/get-all-continuous-values)
            global-timing-ctx (assoc (cue-timing/get-global-timing-context global-clock bpm)
                                     :input-values input-values)
            global-elapsed (long (or (:accumulated-ms global-clock) 0))]
        (try
          (let [out (effects/apply-effect-chain frame effect-chain global-elapsed bpm 0 global-timing-ctx)]
            (output-errors/ok! [:global-effects])
            out)
          (catch Throwable e
            (output-errors/report! [:global-effects] e)
            frame)))
      frame)))

(defn- merge-zone-maps
  "Merge {zone-id frame} maps, concatenating frames that share a zone."
  [zone-maps elapsed-ms]
  (reduce
   (fn [acc item-map]
     (reduce-kv
      (fn [m zone-id frame]
        (update m zone-id
                (fn [existing]
                  (if existing
                    (concatenate-frames [existing frame] elapsed-ms)
                    frame))))
      acc
      item-map))
   {}
   zone-maps))

(defn- render-track-tree
  "Timeline cues whose tracks or folders have effects: merge the clips'
   zone maps per track, apply each track's effects, then each folder's to the
   merged result of its tracks. Track effects run at timeline time (the
   cue's timing-ctx), not clip-local time."
  [tracks clip-maps-by-track elapsed-ms bpm trigger-time timing-ctx]
  (merge-zone-maps
   (mapv (fn [track]
           (let [inner (if (model/track-group? track)
                         (render-track-tree (:items track []) clip-maps-by-track
                                            elapsed-ms bpm trigger-time timing-ctx)
                         (merge-zone-maps (get clip-maps-by-track (:id track)) elapsed-ms))]
             (if (seq (:effects track))
               (reduce-kv (fn [acc zone-id frame]
                            (let [f (apply-item-effects frame track elapsed-ms bpm trigger-time timing-ctx)]
                              (if (seq f) (assoc acc zone-id f) acc)))
                          {} inner)
               inner)))
         tracks)
   elapsed-ms))

(defn generate-frames-by-zone
  "Generate frames organized by destination zone group.
   
   Recursively evaluates cue chain items and groups by resolved zone destination,
   applies preset- and group-level effects at each level, and applies global effects
   to each zone's frame.
   
   Args:
   - cue-chain: The cue chain data with :items, :destination-zone, and optional :tracks
   - elapsed-ms: Elapsed time since cue trigger in milliseconds
   - bpm: Current beats per minute
   - trigger-time: Timestamp when cue was triggered
   - timing-ctx: Timing context map for keyframe evaluation
   
   Returns:
   Map of zone-group-id → frame, where each frame is a vector of points.
   Only zones with rendered content are included.
   Empty map if no items render successfully."
  [cue-chain elapsed-ms bpm trigger-time timing-ctx]
  (ze/log-once :frame-service-generate
               (format "[zone-debug] generate-frames-by-zone: destination-zone=%s effective-beats=%.2f item-count=%d"
                       (pr-str (:destination-zone cue-chain))
                       (double (:effective-beats timing-ctx 0.0))
                       (count (:items cue-chain []))))
  (let [tracks (:tracks cue-chain)
        muted (model/muted-track-ids tracks)
        items (cond->> (:items cue-chain [])
                (seq muted) (filterv #(not (contains? muted (:track-id %)))))
        destination-id (get-in cue-chain [:destination-zone :zone-group-id] :all)
        timing-ctx (if-let [loop-config (:loop cue-chain)]
                     (assoc timing-ctx :window-beats
                            (wrap-into-loop (:effective-beats timing-ctx 0.0) loop-config))
                     timing-ctx)
        timeline? (cue-chains/timeline-cue? cue-chain)
        item-zone-maps (u/keepv
                        (fn [item]
                          (if timeline?
                            ;; Each clip runs on its own clock, from its start
                            (let [clip-ctx (playback/clip-timing-ctx timing-ctx item bpm)]
                              (render-item-tree-by-zone
                               item nil (playback/clip-elapsed-ms clip-ctx) bpm 0 clip-ctx tracks destination-id))
                            (render-item-tree-by-zone
                             item nil elapsed-ms bpm trigger-time timing-ctx tracks destination-id)))
                        items)
        combined-by-zone (if (and timeline? (model/track-effects? tracks))
                           (let [by-track (group-by :track-id (map (fn [item m] {:track-id (:track-id item) :m m})
                                                                    items item-zone-maps))
                                 by-track (update-vals by-track #(mapv :m %))
                                 leaf-ids (model/leaf-track-ids tracks)]
                             (merge-zone-maps
                              (into [(render-track-tree tracks by-track elapsed-ms bpm trigger-time timing-ctx)]
                                    ;; Clips without a known track: no track effects
                                    (mapcat val (remove #(contains? leaf-ids (key %)) by-track)))
                              elapsed-ms))
                           (merge-zone-maps item-zone-maps elapsed-ms))]
    (reduce-kv
     (fn [acc zone-id zone-frame]
       (if (seq zone-frame)
         (let [frame-with-effects (apply-global-effects-to-frame zone-frame bpm)]
           (assoc acc zone-id frame-with-effects))
         acc))
     {}
     combined-by-zone)))

(defn preview-data
  "What the preview shows for one tick, from its rendered cues
   [{:cell :zone-frames} …] in order (show-time's snapshot :cues):
   - :points: every cue's zones joined, blanked between them (the master view);
   - :zone-frames: each zone's frames joined across cues, blanked between them
     as an engine on that zone joins them (multi-engine/projector-frame);
   - :cue-destinations: {cell #{zone-id}} for each cue that rendered something.
   Nil when nothing rendered."
  [cues]
  (when-let [points (concatenate-frames (u/keepv #(concatenate-frames (vec (vals (:zone-frames %))) 0) cues) 0)]
    {:points points
     :zone-frames (reduce (fn [acc {:keys [zone-frames]}]
                            (reduce-kv (fn [m zone-id frame]
                                         (update m zone-id #(if % (concatenate-frames [% frame] 0) frame)))
                                       acc zone-frames))
                          {}
                          cues)
     :cue-destinations (into {}
                             (keep (fn [{:keys [cell zone-frames]}]
                                     (when (seq zone-frames)
                                       [cell (set (keys zone-frames))])))
                             cues)}))
