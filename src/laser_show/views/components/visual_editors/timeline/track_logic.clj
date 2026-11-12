(ns laser-show.views.components.visual-editors.timeline.track-logic
  "Pure helper functions for Track-based Timeline layout.
   
   A Track is an explicit object within a CueChain that groups items
   for organizational and zone-routing purposes.
   
   Track structure:
   {:id         UUID
    :name       String   — user-visible label  (default: zone group name)
    :zone-group-id  Keyword  — zone group override (optional; inherits from
                               the parent folder, then the chain default)
    :color      String   — hex color (optional, derived from zone if nil)
    :muted?     Boolean  — muted track/folder (optional)
    :collapsed? Boolean  — whether sub-items are collapsed (default false)}
   
   Items reference their track via :track-id.
   Multiple tracks may point to the same :zone-group-id."
  (:require [laser-show.animation.chains :as chains]
            [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.common.util :as u]))


;; ============================================================
;; Track Creation
;; ============================================================


(def track-palette
  "Track colours, assigned round-robin to new tracks (clip colour = track colour)."
  ["#4A90D9" "#E67E22" "#9B59B6" "#2EAD8F" "#D9B84A" "#D9577A" "#6C8CD5" "#7CB342"])

(defn palette-color
  "The palette colour for the n-th track."
  [n]
  (nth track-palette (mod (long n) (count track-palette))))

(defn create-track
  "Create a new track.
   
   Parameters:
   - opts map with:
     :zone-group-id — optional keyword like :all, :front, :back. Without it
                      the track inherits from its folder, or the chain default.
     :name          — optional, defaults to the zone name or \"Track\"
     :color         — optional hex color string
   
   Returns: Track map with fresh UUID."
  [{:keys [zone-group-id name color]}]
  (u/assoc-some
   {:id (random-uuid)
    :type :track
    :name (or name (some-> zone-group-id clojure.core/name) "Track")
    :collapsed? false}
   :zone-group-id zone-group-id
   :color color))


;; ============================================================
;; Track Queries
;; ============================================================


(defn find-track-by-id
  "Find a track by its ID recursively in a tracks vector.
   Returns the track map or nil."
  [tracks track-id]
  (when-let [path (chains/find-path-by-id tracks track-id)]
    (get-in (vec tracks) path)))

(defn track-group?
  "Returns true if the track is a group/folder."
  [track]
  (boolean
   (or (= (:type track) :group)
       (contains? track :items))))

(defn- descendant-ids [track]
  (into #{}
        (comp (drop 1) (map :id))
        (tree-seq track-group? #(:items % []) track)))

(defn flatten-visible-tracks
  "Takes a nested vector of tracks and returns a flat view of all tracks
   that should be visible on the canvas: every top-level track, plus the
   children of folders that aren't :collapsed?.
   
   Each row carries its effective :zone-group-id (inherited from its parent
   folder when it has none). A collapsed folder's row also has
   :hidden-descendants, the ids of every track hidden under it."
  [tracks]
  (letfn [(flatten-level [level-tracks parent-zone-id]
            (mapcat (fn [track]
                      (let [effective-zone (or (:zone-group-id track) parent-zone-id)
                            track-with-zone (assoc track :zone-group-id effective-zone)]
                        (cond
                          (not (track-group? track))
                          [track-with-zone]

                          (:collapsed? track false)
                          [(assoc track-with-zone :hidden-descendants (descendant-ids track))]

                          :else
                          (into [track-with-zone]
                                (flatten-level (:items track []) effective-zone)))))
                    level-tracks))]
    (vec (flatten-level tracks :all))))


;; ============================================================
;; Items-to-Tracks Grouping
;; ============================================================


(defn items-by-track
  "Group items into a map of {track-id -> [items]} ordered by :timeline/start.
   Items without a :track-id are collected under ::unassigned."
  [items]
  (let [grouped (group-by #(or (:track-id %) ::unassigned) items)]
    (update-vals grouped
                 (fn [track-items]
                   (vec (sort-by #(:timeline/start % 0.0) track-items))))))

;; ============================================================
;; Auto-initialization (migration from legacy chains)
;; ============================================================

(defn first-leaf-track
  "Find the first track that is a leaf (not a group) by traversing
   the tracks tree recursively (depth-first). Returns the track or nil."
  [tracks]
  (some (fn [track]
          (if (track-group? track)
            (first-leaf-track (:items track []))
            track))
        tracks))

(defn leaf-track?
  "Returns true if the track is a leaf track (not a group)."
  [track]
  (not (track-group? track)))

(defn ensure-leaf-track
  "Given a tracks hierarchy and a candidate track-id, returns a leaf track ID.
   If candidate-id points to a leaf track, returns candidate-id.
   If candidate-id points to a group folder, returns the first leaf track within that folder.
   If candidate-id is nil or not found, returns the first leaf track in the tracks tree."
  [tracks candidate-id]
  (if-let [candidate (when candidate-id (find-track-by-id tracks candidate-id))]
    (if (leaf-track? candidate)
      candidate-id
      (or (:id (first-leaf-track (:items candidate [])))
          (:id (first-leaf-track tracks))))
    (:id (first-leaf-track tracks))))

(defn droppable-track?
  "Returns true if a canvas track row or track definition is a valid drop target
   for clips and track selection (i.e. is an actual leaf track, not a group folder
   or synthetic unassigned row)."
  [track-or-row]
  (boolean
   (and (some? track-or-row)
        (= :track (:type track-or-row :track))
        (not= ::unassigned (:id track-or-row))
        (some? (:id track-or-row))
        (if-let [t (:track track-or-row)]
          (leaf-track? t)
          (not= :group (:type track-or-row))))))

(defn resolve-active-track
  "Given a tracks hierarchy and an optional candidate track ID (such as from
   sidebar selection), returns the resolved leaf track UUID for content addition.
   
   If selected-track-id points to a leaf track, returns that ID.
   If selected-track-id points to a group folder, returns the first leaf track within it.
   If selected-track-id is nil, empty, or not found in track-defs, falls back to
   the first leaf track in the tracks hierarchy."
  [{:keys [track-defs selected-track-id]}]
  (ensure-leaf-track (or track-defs []) selected-track-id))

(defn track-at-y
  "Resolve the track row and index at a canvas vertical coordinate y.
   
   Parameters map:
   - :y            - Canvas vertical coordinate (Number)
   - :ruler-height - Top ruler height in pixels (Number, default 24)
   - :track-height - Height per track lane in pixels (Number, default 32)
   - :track-rows   - Vector of canvas track display rows
   
   Returns {:track-idx int :track row} or nil if y is in the ruler or out of bounds."
  [{:keys [y ruler-height track-height track-rows]
    :or {ruler-height 24 track-height 32}}]
  (when (and (some? y) (>= y ruler-height))
    (let [idx (int (/ (- y ruler-height) track-height))]
      (when (and (>= idx 0) (< idx (count track-rows)))
        {:track-idx idx
         :track (nth track-rows idx)}))))


(defn auto-initialize-tracks
  "Generate default tracks for a CueChain that has no :tracks yet.
   
   Strategy:
   1. Collect unique zone-group-ids from items' :zone-selector effects.
   2. Fall back to the chain's :destination-zone.
   3. For each unique zone-group-id, create a group folder.
   4. Inside each group folder, create a single default track.
   5. Assign each item a :track-id matching the default track for its zone.
   
   Parameters:
   - cue-chain: The cue chain map (:items, :destination-zone)
   - zone-groups: Map of zone-group-id -> zone config (for names/colors)
   
   Returns: Updated cue-chain with structured :tracks and items having :track-id."
  [cue-chain zone-groups]
  (let [default-zone (get-in cue-chain [:destination-zone :zone-group-id] :all)
        items (:items cue-chain [])

        ;; Collect per-item zone destinations
        item-zones (mapv (fn [item]
                           (let [zone-effect (u/seek #(= :zone-selector (:effect-id %))
                                                     (:effects item []))]
                             (if zone-effect
                               (get-in zone-effect [:params :target-zone] default-zone)
                               default-zone)))
                         items)

        ;; Unique zones encountered (always include the default zone)
        unique-zones (distinct (cons default-zone item-zones))

        ;; Create the folder structure and track mapping
        ;; Result: {zone-id {:folder-track {...} :child-track {...}}}
        zone-structures (into {}
                              (map (fn [zone-id]
                                     (let [zg (get zone-groups zone-id)
                                           name-str (or (:name zg) (clojure.core/name zone-id))
                                           child-track (create-track {:zone-group-id zone-id
                                                                      :name (str name-str " 1")})
                                           folder (assoc (create-track {:zone-group-id zone-id
                                                                        :name name-str})
                                                         :type :group
                                                         :collapsed? false
                                                         :items [child-track])]
                                       [zone-id {:folder folder :child-track child-track}])))
                              unique-zones)

        ;; Each folder's track gets the next palette colour
        zone-structures (into {}
                              (map-indexed (fn [i zone-id]
                                             (let [color (palette-color i)]
                                               [zone-id (-> (get zone-structures zone-id)
                                                            (assoc-in [:child-track :color] color)
                                                            (assoc-in [:folder :items 0 :color] color))])))
                              unique-zones)

        ;; The new tracks tree is just the folders
        tracks-vec (mapv #(get-in zone-structures [% :folder]) unique-zones)

        ;; Mapping from zone to the ID of the child track inside that zone's folder
        zone->track-id (update-vals zone-structures #(-> % :child-track :id))

        updated-items (mapv (fn [item zone-id]
                              (assoc item :track-id (get zone->track-id zone-id)))
                            items item-zones)]
    (assoc cue-chain
           :tracks tracks-vec
           :items updated-items)))


;; ============================================================
;; Clip Timing (pure)
;; ============================================================


(def default-clip-length 4.0)
(def conversion-length 16.0)
(def min-clip-length 0.0625) ;; 1/16 beat

(defn clip-start
  "Clip start in beats, or nil when the item has no timing."
  [item]
  (:timeline/start item))

(defn clip-length
  "Clip length in beats, or nil when the item has no timing."
  [item]
  (:timeline/duration item))

(defn clip-end
  "Clip end in beats, or nil when start or length is missing."
  [item]
  (when-let [s (clip-start item)]
    (when-let [l (clip-length item)]
      (+ (double s) (double l)))))

(defn snap
  "Snap beats to the nearest multiple of :value when :enabled?."
  [beats {:keys [enabled? value]}]
  (if (and enabled? value (pos? (double value)))
    (* (Math/round (/ (double beats) (double value))) (double value))
    (double beats)))

(defn- shift-nested-starts
  "Shift :timeline/start of every nested child (under :items) by delta."
  [item delta]
  (if (seq (:items item))
    (update item :items
            (fn [children]
              (mapv (fn [child]
                      (cond-> (shift-nested-starts child delta)
                        (number? (:timeline/start child))
                        (update :timeline/start #(+ (double %) delta))))
                    children)))
    item))

(defn move-clip
  "Move a clip by delta beats, keeping its length. The new start is snapped
   and clamped to >= 0; nested children shift by the delta actually applied."
  [item delta snap-opts]
  (let [start (double (or (clip-start item) 0.0))
        new-start (max 0.0 (snap (+ start (double delta)) snap-opts))
        applied (- new-start start)]
    (-> item
        (assoc :timeline/start new-start)
        (shift-nested-starts applied))))

(defn trim-start
  "Move the clip's left edge to new-start, keeping its end fixed.
   Start is snapped, then clamped to [0, end - min-clip-length]."
  [item new-start snap-opts]
  (let [end (or (clip-end item) (+ (double (or (clip-start item) 0.0)) default-clip-length))
        s (-> (snap new-start snap-opts) (max 0.0) (min (- end min-clip-length)))]
    (assoc item :timeline/start s :timeline/duration (- end s))))

(defn trim-end
  "Move the clip's right edge to new-end, keeping its start fixed.
   Length is at least min-clip-length."
  [item new-end snap-opts]
  (let [start (double (or (clip-start item) 0.0))
        e (max (+ start min-clip-length) (snap new-end snap-opts))]
    (assoc item :timeline/start start :timeline/duration (- e start))))

(defn- loop->clip [{:keys [start duration]}]
  {:timeline/start (double (or start 0.0)) :timeline/duration (double (or duration default-clip-length))})

(defn- clip->loop [loop-config clip]
  (assoc loop-config :start (:timeline/start clip) :duration (:timeline/duration clip)))

(defn move-loop
  "move-clip for a loop region {:start :duration}."
  [loop-config delta snap-opts]
  (clip->loop loop-config (move-clip (loop->clip loop-config) delta snap-opts)))

(defn trim-loop-start
  "trim-start for a loop region {:start :duration}."
  [loop-config new-start snap-opts]
  (clip->loop loop-config (trim-start (loop->clip loop-config) new-start snap-opts)))

(defn trim-loop-end
  "trim-end for a loop region {:start :duration}."
  [loop-config new-end snap-opts]
  (clip->loop loop-config (trim-end (loop->clip loop-config) new-end snap-opts)))


;; ============================================================
;; Track Tree Queries & Timeline Normalization (pure)
;; ============================================================


(defn leaf-track-ids
  "Set of ids of every leaf (non-folder) track in the tracks tree."
  [tracks]
  (into #{}
        (comp (filter leaf-track?) (map :id))
        (tree-seq (fn [t] (and (map? t) (track-group? t))) #(:items % []) {:type :group :items tracks})))

(defn parent-track
  "The folder directly containing track-id, or nil for top-level/unknown tracks."
  [tracks track-id]
  (when-let [path (chains/find-path-by-id tracks track-id)]
    (when (> (count path) 1)
      (get-in (vec tracks) (vec (drop-last 2 path))))))

(defn muted-track-ids
  "Ids of tracks with :muted? true, plus every descendant of a muted folder."
  [tracks]
  (letfn [(walk [acc level muted-parent?]
            (reduce (fn [acc t]
                      (let [muted? (or muted-parent? (true? (:muted? t)))
                            acc (cond-> acc muted? (conj (:id t)))]
                        (if (track-group? t)
                          (walk acc (:items t []) muted?)
                          acc)))
                    acc level))]
    (walk #{} tracks false)))

(defn- mapv-identical
  "Like mapv, but returns coll itself when f returns every element unchanged."
  [f coll]
  (let [out (mapv f coll)]
    (if (and (vector? coll) (every? true? (map identical? coll out)))
      coll
      out)))

(defn- migrate-track-enabled
  "D10: the old track checkbox wrote :enabled?; mute is now :muted?."
  [tracks]
  (mapv-identical
   (fn [t]
     (let [t' (if (contains? t :enabled?)
                (cond-> (dissoc t :enabled?)
                  (false? (:enabled? t)) (assoc :muted? true))
                t)]
       (if (and (track-group? t') (seq (:items t')))
         (let [children (migrate-track-enabled (:items t'))]
           (if (identical? children (:items t')) t' (assoc t' :items children)))
         t')))
   tracks))

(defn- timed-children-span
  "[start length] covering the group's timed children, or nil."
  [group]
  (let [timed (filter #(and (number? (clip-start %)) (number? (clip-length %))) (:items group []))]
    (when (seq timed)
      (let [s (apply min (map #(double (clip-start %)) timed))
            e (apply max (map #(double (clip-end %)) timed))]
        [s (max min-clip-length (- e s))]))))

(defn- normalize-item
  [item leaf-ids default-track-id]
  (let [start (clip-start item)
        length (clip-length item)
        [span-start span-length] (when (and (nil? start) (nil? length) (chains/group? item))
                                   (timed-children-span item))
        valid-start? (and (number? start) (>= (double start) 0.0))
        valid-length? (and (number? length) (>= (double length) min-clip-length))]
    (cond-> item
      (not valid-start?) (assoc :timeline/start (if (number? start)
                                                  0.0
                                                  (double (or span-start 0.0))))
      (not valid-length?) (assoc :timeline/duration (if (number? length)
                                                      min-clip-length
                                                      (double (or span-length default-clip-length))))
      (and default-track-id (not (contains? leaf-ids (:track-id item))))
      (assoc :track-id default-track-id))))

(defn normalize-timeline-cue
  "Enforce the timeline invariants on a timeline cue chain. Every top-level
   item gets a numeric start >= 0, a length >= min-clip-length and a :track-id
   that is a leaf track; existing valid timing is never changed. Legacy track
   :enabled? is migrated to :muted?. Non-timeline cues and cues that already
   satisfy the invariants are returned identical.
   
   opts :new-track-id fixes the id of the track created when items exist but
   there's no leaf track (default: a random uuid)."
  ([cue-chain] (normalize-timeline-cue cue-chain nil))
  ([cue-chain {:keys [new-track-id]}]
   (if-not (cue-chains/timeline-cue? cue-chain)
     cue-chain
     (let [items (:items cue-chain [])
           tracks0 (:tracks cue-chain [])
           tracks1 (migrate-track-enabled tracks0)
           tracks2 (if (or (empty? items) (first-leaf-track tracks1))
                     tracks1
                     (conj (vec tracks1)
                           (assoc (create-track {:name "Track 1"})
                                  :id (or new-track-id (random-uuid)))))
           leaf-ids (leaf-track-ids tracks2)
           default-id (:id (first-leaf-track tracks2))
           items' (mapv-identical #(normalize-item % leaf-ids default-id) items)]
       (cond-> cue-chain
         (not (identical? tracks0 tracks2)) (assoc :tracks tracks2)
         (not (identical? items items')) (assoc :items items'))))))

(defn normalize-timeline-cues
  "normalize-timeline-cue over a {cell -> cue-chain} map.
   Only entries not identical? to old-cue-chains are checked (all, when it's nil).
   Returns new-cue-chains itself when nothing changes."
  ([new-cue-chains] (normalize-timeline-cues nil new-cue-chains))
  ([old-cue-chains new-cue-chains]
   (if-not (map? new-cue-chains)
     new-cue-chains
     (reduce-kv (fn [acc k cc]
                  (if (identical? cc (get old-cue-chains k ::none))
                    acc
                    (let [cc' (normalize-timeline-cue cc)]
                      (if (identical? cc cc') acc (assoc acc k cc')))))
                new-cue-chains
                new-cue-chains))))

(defn convert-to-timeline
  "Turn a plain cue chain (or nil for an empty cell) into a timeline cue
   without changing what it renders: each untimed item becomes a clip over
   [0, conversion-length), and unless the cue already has a :loop, a loop over
   the same span is enabled. Timeline cues are returned identical."
  [cue-chain zone-groups]
  (if (cue-chains/timeline-cue? cue-chain)
    cue-chain
    (let [cc (or cue-chain {:items []})
          cc (if (seq (:tracks cc)) cc (auto-initialize-tracks cc (or zone-groups {})))
          items (mapv (fn [item]
                        (cond-> item
                          (nil? (clip-start item)) (assoc :timeline/start 0.0)
                          (nil? (clip-length item)) (assoc :timeline/duration conversion-length)))
                      (:items cc []))]
      (normalize-timeline-cue
       (cond-> (assoc cc :items items :editor-type :timeline)
         (and (seq items) (not (contains? cc :loop)))
         (assoc :loop {:enabled? true :start 0.0 :duration conversion-length}))))))


;; ============================================================
;; Timeline Geometry & Scrolling Math
;; ============================================================


(defn calculate-canvas-width
  "Calculate canvas width based on maximum item reach, loop boundaries, and zoom level.
   Clamped between 800.0 and 8192.0 to stay within GPU hardware texture bounds."
  [{:keys [items loop-config zoom-x]}]
  (let [max-item-end (reduce (fn [m item]
                               (max m (+ (double (:timeline/start item 0.0))
                                         (double (:timeline/duration item 4.0)))))
                             0.0
                             items)
        loop-end (if (:enabled? loop-config true)
                   (+ (double (:start loop-config 0.0))
                      (double (:duration loop-config 4.0)))
                   0.0)
        total-beats (max 32.0 (+ (max max-item-end loop-end) 8.0))
        computed-width (* total-beats (double (or zoom-x 50.0)))]
    (-> computed-width
        (max 800.0)
        (min 8192.0))))


(defn calculate-horizontal-scroll
  "Calculate new normalized horizontal scroll position (0.0 to 1.0)
   given a pixel delta and viewport/content dimensions."
  [{:keys [current-hvalue delta-px content-width viewport-width]
    :or {current-hvalue 0.0 delta-px 0.0}}]
  (let [scrollable-width (- (double (or content-width 0.0))
                            (double (or viewport-width 0.0)))]
    (if (pos? scrollable-width)
      (let [delta-h (/ (double delta-px) scrollable-width)
            new-h (+ (double current-hvalue) delta-h)]
        (-> new-h (max 0.0) (min 1.0)))
      0.0)))


(defn calculate-zoom-at-cursor
  "Calculate new zoom-x and new hvalue so that the focal-beat remains at
   viewport-x in the visible ScrollPane viewport.
   
   Supports both:
   - Modern pinning: :focal-beat and :viewport-x (immune to stale canvas rendering).
   - Canvas-x pinning fallback: :cursor-x and :current-hvalue.
   
   Returns {:new-zoom Double :new-hvalue Double}."
  [{:keys [current-zoom zoom-factor focal-beat viewport-x cursor-x current-hvalue
           content-width viewport-width new-content-width min-zoom max-zoom]
    :or {current-hvalue 0.0 min-zoom 10.0 max-zoom 200.0}}]
  (let [cur-zoom (double (or current-zoom 50.0))
        new-zoom (-> (* cur-zoom (double zoom-factor))
                     (max (double min-zoom))
                     (min (double max-zoom)))]
    (if (== new-zoom cur-zoom)
      {:new-zoom cur-zoom
       :new-hvalue (double current-hvalue)}
      (let [vp-w (double (or viewport-width 0.0))
            scrollable-old (max 0.0 (- (double (or content-width 0.0)) vp-w))
            scroll-px-old (* (double current-hvalue) scrollable-old)
            beat (double (or focal-beat
                             (if (and (number? cursor-x) (pos? cur-zoom))
                               (/ (double cursor-x) cur-zoom)
                               0.0)))
            vp-x (double (or viewport-x
                             (if (number? cursor-x)
                               (- (double cursor-x) scroll-px-old)
                               (/ vp-w 2.0))))
            new-canvas-x (* beat new-zoom)
            target-scroll-px (- new-canvas-x vp-x)
            new-scrollable (max 0.0 (- (double (or new-content-width 0.0)) vp-w))
            new-hvalue (if (pos? new-scrollable)
                         (-> (/ target-scroll-px new-scrollable)
                             (max 0.0)
                             (min 1.0))
                         0.0)]
        {:new-zoom new-zoom
         :new-hvalue new-hvalue}))))

