(ns laser-show.services.frame-service
  "Frame service: renders cues into frames.
   
   This service:
   - Renders a cue chain into frames per destination zone (generate-frames-by-zone)
   - Applies effects from the effects grid and per-preset effects
   - Builds what the preview shows from a tick's rendered cues (preview-data)
   - Generates calibration frames for projector calibration mode
   
   The show clock (services/show_clock.clj) calls it once per playing cue per
   tick, with the cue's timing context and the tick's render context
   (render-context), read once per tick; the engines and the preview take
   their frames from that tick.
   
   Cue Chain Support:
   - Cue chains are sequential lists of presets
   - Each preset can have its own parameters and effect chain
   - Presets render one after another in time
   
   Zone Group Filtering:
   - Preview can be filtered by zone group to show only content routed to specific zones
   - Default filter is :all (shows content routed to :all zone group)
   - Filter nil means show all content regardless of routing (master view)
   
   Calibration Mode:
   - The test pattern (animation/test_pattern.clj) is made by the engine
     (backend/multi_engine.clj projector-frame), not here: only the calibrating
     or identified projector shows it, its grid through the corner pin and its
     safety outline on top
   
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
            [laser-show.vibes.faders :as faders]
            [laser-show.animation.effects.shape]
            [laser-show.animation.effects.color]
            [laser-show.animation.effects.intensity]
            [laser-show.animation.effects.calibration]
            [laser-show.animation.effects.zone]))


;; Frame Generation


;; NOTE: All these functions use get-raw-state instead of get-state because
;; they are called from the show clock's thread, not the UI thread. Using
;; fx/sub-val from a background thread causes assertion errors.

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
  "The active effects of the effects grid in state, as one effect chain, or
   nil if there are none.
   
   Performance: Uses eager collection operations to avoid lazy evaluation overhead."
  [state]
  (let [effect-chains (get-in state [:chains :effect-chains] {})]
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

(defn render-context
  "What every cue's render in one tick shares. The show clock makes it once
   per tick, from the tick's app state, input values, global clock and
   presses (show-time/pulse-view, nil without):
   - :input-values: the input store's values, and each fader's at [:macro
     slot]: the value of the control learned for it now ([:config :input
     :macro-map], vibes.faders/with-faders), never a stored copy;
   - :pulses: the presses, which the clock puts on each cue's timing context;
   - :global-effects: the effects grid's active chain in state, or nil;
   - :global-ctx: the global clock's timing context, with the input values
     and the presses, at the global BPM. Each cue's render puts its own tempo
     on it, so a timeline with a locked tempo runs the global effects at that
     tempo."
  ([state input-values global-clock]
   (render-context state input-values global-clock nil))
  ([state input-values global-clock pulses]
   (let [input-values (faders/with-faders input-values (get-in state [:config :input :macro-map]))]
     {:input-values input-values
      :pulses pulses
      :global-effects (get-active-global-effects state)
      :global-ctx (assoc (cue-timing/get-global-timing-context global-clock (get-in state [:timing :bpm]))
                         :input-values input-values
                         :pulses pulses)})))

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
  [preset-instance]
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
  "Apply an item's (preset, group or track) effect chain to a frame, at
   timing-ctx. Effects with :timeline/start and :timeline/duration are
   filtered so only those active at the current beat position are applied."
  [frame item timing-ctx]
  (let [current-beats (window-beats timing-ctx)
        item-effects (->> (:effects item [])
                          (filterv #(model/active-at? % current-beats)))]
    (if (seq item-effects)
      (try
        (let [out (effects/apply-effect-chain frame {:effects item-effects} timing-ctx)]
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
   Returns a frame (vector of points) or nil."
  [frames]
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

(defn- render-item-tree-by-zone
  "Recursively render a cue chain item (preset or group) partitioned by destination zone.
   Applies effects at each level (preset effects on presets, group effects on group zone sub-frames).
   
   Args:
   - item: A preset or group map
   - parent-zone-id: Inherited zone group keyword from parent container (or nil)
   - timing-ctx: The cue's (or the clip's) timing context
   - tracks: Cue chain tracks vector (optional)
   - default-zone-id: Fallback zone group keyword
   
   Returns: Map of zone-group-id → LaserFrame (vector of points)"
  [item parent-zone-id timing-ctx tracks default-zone-id]
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
          (if-let [frame (render-preset-instance item)]
            (let [frame-with-fx (apply-item-effects frame item timing-ctx)]
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
                                 % group-zone timing-ctx tracks default-zone-id)
                               children)
              merged-child-frames (reduce
                                   (fn [acc child-map]
                                     (reduce-kv
                                      (fn [m zone-id frame]
                                        (update m zone-id
                                                (fn [existing]
                                                  (if existing
                                                    (concatenate-frames [existing frame])
                                                    frame))))
                                      acc
                                      child-map))
                                   {}
                                   child-zone-maps)]
          (reduce-kv
           (fn [acc zone-id sub-frame]
             (let [processed-frame (apply-item-effects sub-frame item timing-ctx)]
               (if (seq processed-frame)
                 (assoc acc zone-id processed-frame)
                 acc)))
           {}
           merged-child-frames))

        :else {}))))

(defn- apply-global-effects-to-frame
  "Apply the effects grid's active chain (global-effects, or nil) to a frame,
   at global-ctx: the show clock's global clock at the cue's tempo."
  [frame global-effects global-ctx]
  (if global-effects
    (try
      (let [out (effects/apply-effect-chain frame global-effects global-ctx)]
        (output-errors/ok! [:global-effects])
        out)
      (catch Throwable e
        (output-errors/report! [:global-effects] e)
        frame))
    frame))

(defn- merge-zone-maps
  "Merge {zone-id frame} maps, concatenating frames that share a zone."
  [zone-maps]
  (reduce
   (fn [acc item-map]
     (reduce-kv
      (fn [m zone-id frame]
        (update m zone-id
                (fn [existing]
                  (if existing
                    (concatenate-frames [existing frame])
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
  [tracks clip-maps-by-track timing-ctx]
  (merge-zone-maps
   (mapv (fn [track]
           (let [inner (if (model/track-group? track)
                         (render-track-tree (:items track []) clip-maps-by-track timing-ctx)
                         (merge-zone-maps (get clip-maps-by-track (:id track))))]
             (if (seq (:effects track))
               (reduce-kv (fn [acc zone-id frame]
                            (let [f (apply-item-effects frame track timing-ctx)]
                              (if (seq f) (assoc acc zone-id f) acc)))
                          {} inner)
               inner)))
         tracks)))

(defn generate-frames-by-zone
  "Generate frames organized by destination zone group.
   
   Recursively evaluates cue chain items and groups by resolved zone destination,
   applies preset- and group-level effects at each level, and applies global effects
   to each zone's frame.
   
   Args:
   - cue-chain: The cue chain data with :items, :destination-zone, and optional :tracks
   - timing-ctx: The cue's timing context (cue-timing/get-cue-timing-context),
     which every modulator, keyframe and clip window reads. Its :bpm is the
     cue's tempo.
   - render-ctx: What the tick's cues share (render-context). Without it, it
     is made from app state, the input store and the show clock as they are.
   
   Returns:
   Map of zone-group-id → frame, where each frame is a vector of points.
   Only zones with rendered content are included.
   Empty map if no items render successfully."
  ([cue-chain timing-ctx]
   (generate-frames-by-zone cue-chain timing-ctx
                            (render-context (state/get-raw-state)
                                            (input-store/get-all-continuous-values)
                                            (show-time/global-clock))))
  ([cue-chain timing-ctx {:keys [global-effects global-ctx]}]
   (ze/log-once :frame-service-generate
                (format "[zone-debug] generate-frames-by-zone: destination-zone=%s effective-beats=%.2f item-count=%d"
                        (pr-str (:destination-zone cue-chain))
                        (double (:effective-beats timing-ctx 0.0))
                        (count (:items cue-chain []))))
   (let [bpm (:bpm timing-ctx)
         tracks (:tracks cue-chain)
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
                           (render-item-tree-by-zone
                            item nil
                            ;; Each clip runs on its own clock, from its start
                            (if timeline? (playback/clip-timing-ctx timing-ctx item bpm) timing-ctx)
                            tracks destination-id))
                         items)
         combined-by-zone (if (and timeline? (model/track-effects? tracks))
                            (let [by-track (group-by :track-id (map (fn [item m] {:track-id (:track-id item) :m m})
                                                                     items item-zone-maps))
                                  by-track (update-vals by-track #(mapv :m %))
                                  leaf-ids (model/leaf-track-ids tracks)]
                              (merge-zone-maps
                               (into [(render-track-tree tracks by-track timing-ctx)]
                                     ;; Clips without a known track: no track effects
                                     (mapcat val (remove #(contains? leaf-ids (key %)) by-track)))))
                            (merge-zone-maps item-zone-maps))
         ;; The global effects run at the cue's tempo
         global-ctx (when global-effects (assoc global-ctx :bpm bpm))]
     (reduce-kv
      (fn [acc zone-id zone-frame]
        (if (seq zone-frame)
          (assoc acc zone-id (apply-global-effects-to-frame zone-frame global-effects global-ctx))
          acc))
      {}
      combined-by-zone))))

(defn preview-data
  "What the preview shows for one tick, from its rendered cues
   [{:cell :zone-frames} …] in order (show-time's snapshot :cues):
   - :points: every cue's zones joined, blanked between them (the master view);
   - :zone-frames: each zone's frames joined across cues, blanked between them
     as an engine on that zone joins them (multi-engine/projector-frame);
   - :cue-destinations: {cell #{zone-id}} for each cue that rendered something.
   Nil when nothing rendered."
  [cues]
  (when-let [points (concatenate-frames (u/keepv #(concatenate-frames (vec (vals (:zone-frames %)))) cues))]
    {:points points
     :zone-frames (reduce (fn [acc {:keys [zone-frames]}]
                            (reduce-kv (fn [m zone-id frame]
                                         (update m zone-id #(if % (concatenate-frames [% frame]) frame)))
                                       acc zone-frames))
                          {}
                          cues)
     :cue-destinations (into {}
                             (keep (fn [{:keys [cell zone-frames]}]
                                     (when (seq zone-frames)
                                       [cell (set (keys zone-frames))])))
                             cues)}))
