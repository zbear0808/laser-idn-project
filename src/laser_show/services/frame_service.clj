(ns laser-show.services.frame-service
  "Frame service: renders cues into frames.
   
   This service:
   - Renders a cue chain into frames by target (generate-frames-by-zone)
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
            [laser-show.animation.fit-guard :as fit-guard]
            [laser-show.animation.frames :as frames]
            [laser-show.animation.show-time :as show-time]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.backend.output-errors :as output-errors]
            [laser-show.common.util :as u]
            [laser-show.routing.compose :as compose]
            [laser-show.routing.rig :as rig]
            [laser-show.routing.targets :as targets]
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
            [laser-show.animation.effects.points]
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
   - :column-targets and :show-target: the grid columns' default targets and
     the show's (zones plan Z4); each cue's render takes its column's
     (:column-target, show-clock's render-cue);
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
      :column-targets (get-in state [:grid :column-targets])
      :show-target (get-in state [:config :default-target])
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

(def ^{:arglists '([frames])
       :doc "Concatenate multiple frames with blanking points between them.
   Returns a frame (vector of points) or nil. (animation.frames, where the
   routing can reach it.)"}
  concatenate-frames
  frames/concatenate-frames)

(defn- render-item-tree-by-zone
  "Render a cue chain item (preset or group) partitioned by target (zones
   plan Z4): {target frame}, the keys targets in normal form. Applies
   effects at each level (preset effects on presets, group effects on each
   of a group's targets' frames).
   
   Args:
   - item: A preset or group map
   - parent: the resolution of the group it is in, as its children see it
     (routing.targets/inherited), or nil at the top
   - timing-ctx: The cue's (or the clip's) timing context
   - by-track: the cue's track targets (routing.targets/track-targets)
   - fallback: the cue's fallback (routing.targets/cue-fallback)"
  [item parent timing-ctx by-track fallback]
  (let [current-beats (window-beats timing-ctx)]
    (if-not (and (:enabled? item true)
                 (model/active-at? item current-beats))
      {}
      (cond
        ;; Preset: render and apply preset effects, under its target
        (= :preset (:type item))
        (let [target (:target (targets/resolve-item (:target item) (get by-track (:track-id item)) parent fallback))]
          (ze/log-once [:resolve-item (:id item)]
                       (format "[zone-debug] resolve-item: id=%s target=%s" (:id item) target))
          (if-let [frame (render-preset-instance item)]
            (let [frame-with-fx (apply-item-effects frame item timing-ctx)]
              (if (seq frame-with-fx)
                {target frame-with-fx}
                {}))
            {}))

        ;; Group: its resolution reaches its children, which are rendered,
        ;; then the group's effects run on each target's frame
        (chains/group? item)
        (let [res (targets/resolve-item (:target item) (get by-track (:track-id item)) parent fallback)
              inherited (targets/inherited res (:id item))
              children (:items item [])
              child-zone-maps (u/keepv
                               #(render-item-tree-by-zone % inherited timing-ctx by-track fallback)
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

(defn apply-global-effects-once
  "The effects grid's chain (global-effects, or nil) on each frame of
   frames-by-key ({key frame}, one cue's), at global-ctx, once per distinct
   frame object (spec 01 §6.1): keys that hold one object get one result
   object, so a picture placed under two keys stays one picture and a laser
   draws it once (routing.compose). There is no value hashing: equal frames
   that aren't identical? are two pictures. Empty frames are left out."
  [frames-by-key global-effects global-ctx]
  (let [done (java.util.IdentityHashMap.)]
    (reduce-kv
     (fn [acc zone-id zone-frame]
       (if (seq zone-frame)
         (assoc acc zone-id (if (.containsKey done zone-frame)
                              (.get done zone-frame)
                              (let [out (apply-global-effects-to-frame zone-frame global-effects global-ctx)]
                                (.put done zone-frame out)
                                out)))
         acc))
     {}
     frames-by-key)))

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
  "Generate a cue's frames by target (zones plan Z4).
   
   Each item goes where it resolves to (routing.targets/resolve-item, spec 01
   §2): its own :target, its track's (walking up its folders), its group's,
   else the cue's fallback: the cue's :target, its grid column's default,
   the show's, All. Preset- and group-level effects run at each level, then
   the global effects on each frame. The keys never depend on the zone map
   (06 §8.8 invariant 2): a map switch re-routes these same frames.
   
   Args:
   - cue-chain: The cue chain data with :items, :target, and optional :tracks
   - timing-ctx: The cue's timing context (cue-timing/get-cue-timing-context),
     which every modulator, keyframe and clip window reads. Its :bpm is the
     cue's tempo.
   - render-ctx: What the tick's cues share (render-context). Without it, it
     is made from app state, the input store and the show clock as they are.
     :column-target and :show-target are the cue's column's default and the
     show's. With :item-points? true (the show clock sets it for a cue whose
     editor is open), the result's metadata has :item-points [[item-id {key
     points}] …]: each top-level item's (or clip's) points by key, before
     track and global effects, for the cue warnings (routing.compose).
   
   Returns:
   Map of target → frame, where each frame is a vector of points.
   Only targets with rendered content are included.
   Empty map if no items render successfully."
  ([cue-chain timing-ctx]
   (generate-frames-by-zone cue-chain timing-ctx
                            (render-context (state/get-raw-state)
                                            (input-store/get-all-continuous-values)
                                            (show-time/global-clock))))
  ([cue-chain timing-ctx {:keys [global-effects global-ctx item-points? column-target show-target]}]
   (ze/log-once :frame-service-generate
                (format "[zone-debug] generate-frames-by-zone: target=%s effective-beats=%.2f item-count=%d"
                        (pr-str (:target cue-chain))
                        (double (:effective-beats timing-ctx 0.0))
                        (count (:items cue-chain []))))
   (let [bpm (:bpm timing-ctx)
         tracks (:tracks cue-chain)
         muted (model/muted-track-ids tracks)
         items (cond->> (:items cue-chain [])
                 (seq muted) (filterv #(not (contains? muted (:track-id %)))))
         fallback (targets/cue-fallback (:target cue-chain) column-target show-target)
         by-track (targets/track-targets tracks)
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
                            by-track fallback))
                         items)
         combined-by-zone (if (and timeline? (model/track-effects? tracks))
                            (let [clips-by-track (update-vals (group-by :track-id (map (fn [item m] {:track-id (:track-id item) :m m})
                                                                                         items item-zone-maps))
                                                              #(mapv :m %))
                                  leaf-ids (model/leaf-track-ids tracks)]
                              (merge-zone-maps
                               (into [(render-track-tree tracks clips-by-track timing-ctx)]
                                     ;; Clips without a known track: no track effects
                                     (mapcat val (remove #(contains? leaf-ids (key %)) clips-by-track)))))
                            (merge-zone-maps item-zone-maps))
         ;; The global effects run at the cue's tempo
         global-ctx (when global-effects (assoc global-ctx :bpm bpm))]
     (cond-> (apply-global-effects-once combined-by-zone global-effects global-ctx)
       item-points? (with-meta {:item-points (into []
                                                   (keep (fn [[item by-zone]]
                                                           (when (seq by-zone)
                                                             [(:id item) (update-vals by-zone count)])))
                                                   (map vector items item-zone-maps))})))))

(defn- ref-frames
  "{ref frame} for every ref the tick's keys name: the frames of each key
   that names it, joined in key order (routing.compose/key-order), then
   across the cues. A key with two refs counts for each."
  [cues]
  (reduce (fn [acc {:keys [zone-frames]}]
            (let [by-ref (reduce (fn [m k]
                                   (reduce (fn [m ref] (update m ref (fnil conj []) (get zone-frames k)))
                                           m
                                           (rig/key-refs k)))
                                 {}
                                 (sort-by compose/key-order (keys zone-frames)))]
              (reduce-kv (fn [acc ref frames]
                           (let [frame (concatenate-frames frames)]
                             (update acc ref #(if % (concatenate-frames [% frame]) frame))))
                         acc
                         by-ref)))
          {}
          cues))

(defn preview-data
  "What the preview shows for one tick, from its rendered cues
   [{:cell :zone-frames} …] in order (show-time's snapshot :cues) and its
   routing (nil: no laser cells):
   - :points: every cue's zones joined, blanked between them (the master view);
   - :ref-frames: {ref frame}, what is sent to each ref the tick's keys name
     (a group cell);
   - :projector-frames: {projector-id (delay {:frame :fit})}, for each laser
     of the routing, what it draws before its calibration: the composition
     and fit guard its engine runs (routing.compose, animation.fit-guard),
     worked out only when a cell shows it (a laser's cell);
   - :cue-destinations: {cell #{zone-id}} for each cue that rendered something.
   Nil when nothing rendered."
  ([cues] (preview-data cues nil))
  ([cues routing]
   (when-let [points (concatenate-frames (u/keepv #(concatenate-frames (vec (vals (:zone-frames %)))) cues))]
     {:points points
      :ref-frames (ref-frames cues)
      :projector-frames (into {}
                              (map (fn [[pid {:keys [scan-rate fit]}]]
                                     [pid (delay (when-let [{:keys [frame]} (compose/projector-frame cues routing pid)]
                                                   (fit-guard/guard frame scan-rate fit)))]))
                              (:outputs routing))
      :cue-destinations (into {}
                              (keep (fn [{:keys [cell zone-frames]}]
                                      (when (seq zone-frames)
                                        [cell (set (keys zone-frames))])))
                              cues)})))
