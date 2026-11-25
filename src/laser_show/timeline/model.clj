(ns laser-show.timeline.model
  "The timeline document: the single owner of the timeline invariants.
   
   A timeline cue is a cue chain (at [:chains :cue-chains [col row]]) with:
   - :items   clips; every top-level clip has :timeline/start (beats >= 0),
              :timeline/duration (>= min-clip-length) and a :track-id that
              is a leaf track. Children of a group clip may omit timing
              (= always on inside the parent). Windows are absolute beats.
   - :tracks  tree of {:type :track} and {:type :group :items [...]} folders
   - :loop    {:enabled? :start :duration} (optional)
   - :editor-type :timeline
   
   Track structure:
   {:id         UUID
    :name       String   — user-visible label
    :target     target   — where its clips go (zones plan Z4; optional:
                           it inherits from its folder, then the cue)
    :color      String   — hex colour (clip colour = track colour)
    :muted?     Boolean  — muted track/folder (live field, not undoable)
    :collapsed? Boolean  — folder collapsed in the editor (live field)
    :effects    [effect] — optional chain applied to everything on the track
                           (a folder's: to all its tracks), at timeline time}
   
   Pure: used by event handlers, views, frame-service and persistence.
   Must not require views, events or state (the pure list tree helpers in
   events.handlers.list.helpers are the one exception)."
  (:require [clojure.string]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.common.util :as u]
            [laser-show.events.handlers.list.helpers :as list-helpers]
            [laser-show.routing.targets :as targets]))

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
     :target — optional (routing.targets): where its clips go. Without it
               the track inherits from its folder, or the cue.
     :name   — optional, defaults to \"Track\"
     :color  — optional hex color string
   
   Returns: Track map with fresh UUID."
  [{:keys [target name color]}]
  (u/assoc-some
   {:id (random-uuid)
    :type :track
    :name (or name "Track")
    :collapsed? false}
   :target (targets/normalize-target target)
   :color color))

;; ============================================================
;; Track Queries
;; ============================================================

(defn find-track
  "Find a track by its ID recursively in a tracks vector.
   Returns the track map or nil."
  [tracks track-id]
  (when-let [path (chains/find-path-by-id tracks track-id)]
    (get-in (vec tracks) path)))

(defn track-effects?
  "Does any track or folder carry effects?"
  [tracks]
  (some? (some #(seq (:effects %))
               (tree-seq #(seq (:items %)) :items {:items tracks}))))

(defn track-group?
  "Returns true if the track is a group/folder."
  [track]
  (boolean
   (or (= (:type track) :group)
       (contains? track :items))))

(defn leaf-track?
  "Returns true if the track is a leaf track (not a group)."
  [track]
  (not (track-group? track)))

(defn first-leaf-track
  "Find the first track that is a leaf (not a group) by traversing
   the tracks tree recursively (depth-first). Returns the track or nil."
  [tracks]
  (some (fn [track]
          (if (track-group? track)
            (first-leaf-track (:items track []))
            track))
        tracks))

(defn insert-track
  "The leaf track new content goes to, given a candidate (e.g. the sidebar
   selection). Returns a leaf track ID.
   If candidate-id points to a leaf track, returns candidate-id.
   If candidate-id points to a group folder, returns the first leaf track within that folder.
   If candidate-id is nil or not found, returns the first leaf track in the tracks tree."
  [tracks candidate-id]
  (if-let [candidate (when candidate-id (find-track tracks candidate-id))]
    (if (leaf-track? candidate)
      candidate-id
      (or (:id (first-leaf-track (:items candidate [])))
          (:id (first-leaf-track tracks))))
    (:id (first-leaf-track tracks))))

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

(defn- descendant-ids [track]
  (into #{}
        (comp (drop 1) (map :id))
        (tree-seq track-group? #(:items % []) track)))

(defn flatten-visible-tracks
  "Takes a nested vector of tracks and returns a flat view of all tracks
   that should be visible on the canvas: every top-level track, plus the
   children of folders that aren't :collapsed?.
   
   Each row carries its effective :target (its folder's when it has none;
   nil at the top: the cue's). A collapsed folder's row also has
   :hidden-descendants, the ids of every track hidden under it."
  [tracks]
  (letfn [(flatten-level [level-tracks parent-target]
            (mapcat (fn [track]
                      (let [effective (or (targets/normalize-target (:target track)) parent-target)
                            row (if (some? effective) (assoc track :target effective) (dissoc track :target))]
                        (cond
                          (not (track-group? track))
                          [row]

                          (:collapsed? track false)
                          [(assoc row :hidden-descendants (descendant-ids track))]

                          :else
                          (into [row]
                                (flatten-level (:items track []) effective)))))
                    level-tracks))]
    (vec (flatten-level tracks nil))))

(defn items-by-track
  "Group items into a map of {track-id -> [items]} ordered by :timeline/start.
   Items without a :track-id are collected under ::unassigned."
  [items]
  (let [grouped (group-by #(or (:track-id %) ::unassigned) items)]
    (update-vals grouped
                 (fn [track-items]
                   (vec (sort-by #(:timeline/start % 0.0) track-items))))))

;; ============================================================
;; Clip Timing
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

(defn active-at?
  "Whether an item's timeline window contains beats: start <= beats < end.
   No timing keys = always on (plain cue chains, untimed group children);
   no length = on from start onward."
  [item beats]
  (let [start (clip-start item)
        length (clip-length item)]
    (if (and (nil? start) (nil? length))
      true
      (let [s (double (or start 0.0))]
        (and (>= (double beats) s)
             (or (nil? length)
                 (< (double beats) (+ s (double length)))))))))

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
;; Document: auto-initialization, normalization, conversion
;; ============================================================

(defn- target-name
  "A folder's name for target t: its refs' names (names {ref {:name}}, a
   zone by its letters), zones first, joined by \" + \"."
  [names t]
  (->> (targets/target-refs t)
       (sort-by (fn [r] (if-let [i (targets/zone-index r)] [0 i ""] [1 0 (str r)])))
       (map #(or (targets/zone-letter %) (get-in names [% :name]) (clojure.core/name %)))
       (clojure.string/join " + ")))

(defn auto-initialize-tracks
  "Generate default tracks for a CueChain that has no :tracks yet (zones
   plan Z4): a \"Default\" folder, with no target, for the items with none
   of their own, so they still follow the cue, its column and the show; then
   a folder per distinct item :target, in the order the items first use
   them. Each folder holds one track; each item goes on its target's track
   and keeps its own target. Default comes first, even with no item on it.
   
   Parameters:
   - cue-chain: The cue chain map (:items, :target)
   - names: {ref {:name}} for the folders' names (Z2's target-groups by id)
   
   Returns: Updated cue-chain with structured :tracks and items having :track-id."
  [cue-chain names]
  (let [items (:items cue-chain [])
        item-targets (mapv #(targets/normalize-target (:target %)) items)
        folder-targets (distinct (cons nil item-targets))
        folders (into {}
                      (map-indexed
                       (fn [i t]
                         (let [folder-name (if (nil? t) "Default" (target-name names t))
                               child (assoc (create-track {:name (str folder-name " 1")}) :color (palette-color i))
                               folder (assoc (create-track {:target t :name folder-name})
                                             :type :group
                                             :collapsed? false
                                             :items [child])]
                           [t folder])))
                      folder-targets)]
    (assoc cue-chain
           :tracks (mapv folders folder-targets)
           :items (mapv (fn [item t] (assoc item :track-id (get-in folders [t :items 0 :id])))
                        items item-targets))))

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
   the same span is enabled. Timeline cues are returned identical. names
   ({ref {:name}}) names the folders (auto-initialize-tracks)."
  [cue-chain names]
  (if (cue-chains/timeline-cue? cue-chain)
    cue-chain
    (let [cc (or cue-chain {:items []})
          cc (if (seq (:tracks cc)) cc (auto-initialize-tracks cc (or names {})))
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
;; Timeline settings
;; ============================================================
;;
;; Optional document keys on a timeline cue:
;; :timeline/length     beats; nil = the last clip end
;; :timeline/tempo-bpm  locked tempo; nil = follow the global BPM
;; :timeline/tc-start   timecode of beat 0, in seconds (default 0)
;; :timeline/tc-fps     24, 25, 29.97 (drop-frame) or 30 (default 30)
;; :timeline/chase      nil, or :mtc to follow incoming MIDI timecode


(defn content-end
  "End of the last clip in beats (0 for an empty cue)."
  [cue]
  (reduce max 0.0 (keep clip-end (:items cue))))

(defn effective-length
  "Where the timeline ends: :timeline/length when set, else the last clip end."
  [cue]
  (or (:timeline/length cue) (content-end cue)))


;; ============================================================
;; Edits as data: apply-op
;; ============================================================
;;
;; Every committed timeline edit is one {:op ...} map applied by apply-op, so
;; there's one undo entry per edit with a meaningful label (op-label), and the
;; edit rules live in one place. Ops on unknown ids leave the cue unchanged.


(defn- update-items-by-id
  "Apply f to each item (at any depth) whose :id is in ids."
  [items ids f]
  (mapv (fn [item]
          (let [item (if (contains? ids (:id item)) (f item) item)]
            (if (seq (:items item))
              (update item :items update-items-by-id ids f)
              item)))
        items))

(defn- remove-ids
  "Remove items (at any depth) whose :id is in ids."
  [items ids]
  (into []
        (comp (remove #(contains? ids (:id %)))
              (map #(if (seq (:items %)) (update % :items remove-ids ids) %)))
        items))

(defn- top-level-clips [cue ids]
  (filter #(contains? ids (:id %)) (:items cue [])))

(defn- update-track [cue track-id f]
  (if (find-track (:tracks cue []) track-id)
    (update cue :tracks update-items-by-id #{track-id} f)
    cue))

(defn- clips-span
  "[start end] covering the given clips (missing start = 0, missing length =
   0), or nil for no clips."
  [clips]
  (when (seq clips)
    (let [start #(double (or (clip-start %) 0.0))]
      [(apply min (map start clips))
       (apply max (map #(or (clip-end %) (start %)) clips))])))

(defn- move-clips [cue {:keys [clip-ids delta to-track anchor-id]} snap-opts]
  (let [ids (set clip-ids)
        clips (top-level-clips cue ids)]
    (if (empty? clips)
      cue
      (let [earliest (first (clips-span clips))
            ;; Snap where the anchor (the dragged clip; default the earliest)
            ;; lands, then move every clip by the same delta so their offsets
            ;; are kept. The earliest clip stays at >= 0.
            anchor-start (or (some #(when (= anchor-id (:id %)) (double (or (clip-start %) 0.0))) clips)
                             earliest)
            target (snap (+ anchor-start (double delta)) snap-opts)
            applied (max (- earliest) (- target anchor-start))
            leaf? (and to-track (contains? (leaf-track-ids (:tracks cue [])) to-track))]
        (update cue :items
                (fn [items]
                  (mapv (fn [item]
                          (if (contains? ids (:id item))
                            (cond-> (move-clip item applied nil)
                              leaf? (assoc :track-id to-track))
                            item))
                        items)))))))

(defn- duplicate-clips [cue {:keys [clip-ids]}]
  (let [clips (vec (top-level-clips cue (set clip-ids)))]
    (if-let [[s e] (clips-span clips)]
      (let [offset (- e s)
            copies (mapv #(move-clip % offset nil) (chains/deep-copy-items clips))]
        (update cue :items (fnil into []) copies))
      cue)))

(defn- delete-tracks [cue {:keys [track-ids]}]
  (let [tracks (:tracks cue [])
        roots (keep #(find-track tracks %) track-ids)
        removed (into #{} (mapcat #(cons (:id %) (descendant-ids %))) roots)
        removed-leaves (into #{} (filter #(contains? removed %)) (leaf-track-ids tracks))
        clip-ids (into #{} (comp (filter #(contains? removed-leaves (:track-id %))) (map :id))
                       (:items cue []))]
    (if (empty? removed)
      cue
      (-> cue
          (assoc :tracks (remove-ids tracks removed))
          (update :items #(remove-ids (or % []) clip-ids))))))

(defn- add-track [cue {:keys [track-id parent-id name folder? target]}]
  (let [tracks (:tracks cue [])
        leaf-count (count (leaf-track-ids tracks))
        base (create-track {:target target
                            :name (or name (if folder?
                                             (str "Folder " (inc (count tracks)))
                                             (str "Track " (inc (count tracks)))))
                            :color (when-not folder? (palette-color leaf-count))})
        track (cond-> (assoc base :id (or track-id (:id base)))
                folder? (assoc :type :group :items []))
        parent (when parent-id (find-track tracks parent-id))]
    (assoc cue :tracks
           (if (and parent (track-group? parent))
             (update-items-by-id tracks #{parent-id} #(update % :items (fnil conj []) track))
             (conj (vec tracks) track)))))

(defn- move-tracks [cue {:keys [track-ids target-id position]}]
  (let [tracks (:tracks cue [])]
    (if (every? #(find-track tracks %) track-ids)
      (assoc cue :tracks (list-helpers/move-items tracks (set track-ids) target-id position))
      cue)))

(defn- set-target
  "The item or track id (at any depth) with target, in normal form; none
   (nil, [], :inherit) removes it, so it inherits."
  [items id target]
  (let [t (when-not (= :inherit target) (targets/normalize-target target))]
    (update-items-by-id items #{id} #(if (some? t) (assoc % :target t) (dissoc % :target)))))

(defn- update-effects
  "Apply f to the :effects vector of the op's target: the clip :clip-id
   (any depth) or, without one, the track or folder :track-id."
  [cue {:keys [clip-id track-id]} f]
  (let [g #(update % :effects (fn [fx] (f (vec (or fx [])))))]
    (cond
      clip-id (update cue :items update-items-by-id #{clip-id} g)
      track-id (update-track cue track-id g)
      :else cue)))

(defn- move-effect
  "Move the top-level effect id to index `to` (clamped)."
  [effects id to]
  (if-let [i (first (keep-indexed #(when (= id (:id %2)) %1) effects))]
    (let [fx (effects i)
          rest (into (subvec effects 0 i) (subvec effects (inc i)))
          to (-> (long to) (max 0) (min (count rest)))]
      (into (conj (subvec rest 0 to) fx) (subvec rest to)))
    effects))

(defn- insert-effect
  "Insert fx at `index` (clamped); without an index, append."
  [effects index fx]
  (if (some? index)
    (let [i (-> (long index) (max 0) (min (count effects)))]
      (into (conj (subvec effects 0 i) fx) (subvec effects i)))
    (conj effects fx)))

(defn- edit-loop [cue f]
  (update cue :loop #(f (or % {:enabled? false :start 0.0 :duration default-clip-length}))))

(defn- apply-op*
  [cue {:keys [op] :as o} {snap-opts :snap}]
  (case op
    :move-clips (move-clips cue o snap-opts)
    :trim-start (update cue :items update-items-by-id #{(:clip-id o)} #(trim-start % (:to o) snap-opts))
    :trim-end (update cue :items update-items-by-id #{(:clip-id o)} #(trim-end % (:to o) snap-opts))
    :delete-clips (update cue :items #(remove-ids (or % []) (set (:clip-ids o))))
    :duplicate-clips (duplicate-clips cue o)
    :add-clip (let [track-id (insert-track (:tracks cue []) (:track-id o))
                    clip (-> (cue-chains/create-preset-instance (:preset-id o) {})
                             (assoc :id (or (:clip-id o) (random-uuid))
                                    :track-id track-id
                                    :timeline/start (max 0.0 (snap (or (:start o) 0.0) snap-opts))
                                    :timeline/duration (or (:length o) default-clip-length)))]
                (update cue :items (fnil conj []) clip))
    ;; Effect ops target a clip (:clip-id) or a track or folder (:track-id)
    :add-effect (update-effects cue o #(insert-effect % (:index o)
                                                      {:id (or (:effect-instance-id o) (random-uuid))
                                                       :effect-id (:effect-id o)
                                                       :params (or (:params o) {})
                                                       :enabled? true}))
    :remove-effect (update-effects cue o #(remove-ids % #{(:effect-instance-id o)}))
    :set-effect-enabled (update-effects cue o #(update-items-by-id % #{(:effect-instance-id o)}
                                                                 (fn [fx] (assoc fx :enabled? (boolean (:enabled? o))))))
    :move-effect (update-effects cue o #(move-effect % (:effect-instance-id o) (:to o)))
    :set-clip-track (if (contains? (leaf-track-ids (:tracks cue [])) (:track-id o))
                      (update cue :items update-items-by-id #{(:clip-id o)} #(assoc % :track-id (:track-id o)))
                      cue)
    :set-clip-target (update cue :items set-target (:id o) (:target o))
    :set-track-target (update cue :tracks set-target (:id o) (:target o))
    :add-track (add-track cue o)
    :add-folder (add-track cue (assoc o :folder? true))
    :delete-tracks (delete-tracks cue o)
    :move-tracks (move-tracks cue o)
    :rename-track (update-track cue (:track-id o) #(assoc % :name (:name o)))
    :set-track-color (update-track cue (:track-id o) #(assoc % :color (:color o)))
    :set-track-muted (update-track cue (:track-id o) #(if (:muted? o) (assoc % :muted? true) (dissoc % :muted?)))
    :set-collapsed (update-track cue (:track-id o) #(assoc % :collapsed? (boolean (:collapsed? o))))
    :set-loop (edit-loop cue #(merge % (select-keys o [:enabled? :start :duration])))
    :move-loop (edit-loop cue #(move-loop % (:delta o) snap-opts))
    :trim-loop-start (edit-loop cue #(trim-loop-start % (:to o) snap-opts))
    :trim-loop-end (edit-loop cue #(trim-loop-end % (:to o) snap-opts))
    :set-length (if-let [b (:beats o)]
                  (assoc cue :timeline/length (max min-clip-length (snap (double b) snap-opts)))
                  (dissoc cue :timeline/length))
    :set-tempo (if-let [bpm (:bpm o)]
                 (assoc cue :timeline/tempo-bpm (-> (double bpm) (max 20.0) (min 999.0)))
                 (dissoc cue :timeline/tempo-bpm))
    :set-tc-start (assoc cue :timeline/tc-start (max 0.0 (double (or (:seconds o) 0.0))))
    :set-tc-fps (if (some #(== % (double (:fps o 0))) [24 25 29.97 30])
                  (assoc cue :timeline/tc-fps (:fps o))
                  cue)
    :set-chase (if (= :mtc (:source o))
                 (assoc cue :timeline/chase :mtc)
                 (dissoc cue :timeline/chase))
    ;; The show's own target, already in normal form; none clears it
    :set-cue-target (if-let [t (:target o)] (assoc cue :target t) (dissoc cue :target))
    cue))

(def op-types
  "Every op apply-op knows."
  #{:move-clips :trim-start :trim-end :delete-clips :duplicate-clips :add-clip
    :add-effect :remove-effect :set-clip-track :set-clip-target :set-track-target :add-track
    :add-folder :delete-tracks :move-tracks :rename-track :set-track-color
    :set-track-muted :set-collapsed :set-loop :move-loop :trim-loop-start
    :trim-loop-end :set-length :set-tempo :set-tc-start :set-tc-fps
    :set-chase :set-effect-enabled :move-effect :set-cue-target})

(defn apply-op
  "Apply one edit op to a timeline cue chain. Returns the cue normalized
   (normalize-timeline-cue). Unknown ops and unknown ids leave it unchanged.
   opts: {:snap {:enabled? bool :value beats}}; an op with :snap? false
   ignores snapping (e.g. Shift held during a drag)."
  ([cue op] (apply-op cue op nil))
  ([cue op opts]
   (let [cue' (apply-op* cue op (cond-> opts (false? (:snap? op)) (dissoc :snap)))]
     ;; Ops that find nothing to change may still rebuild maps; return the
     ;; original so callers can use identical? to detect no-ops
     (if (or (identical? cue' cue) (= cue' cue))
       cue
       (normalize-timeline-cue cue')))))

(defn- n-clips [n] (if (= 1 n) "Clip" (str n " Clips")))
(defn- n-tracks [n] (if (= 1 n) "Track" (str n " Tracks")))

(defn op-label
  "Undo label for an op, e.g. \"Move Clip\", \"Move 3 Clips\", \"Delete Track\"."
  [{:keys [op clip-ids track-ids to-track delta] :as _op}]
  (case op
    :move-clips (if (and to-track (zero? (double (or delta 0.0))))
                  (str "Move " (n-clips (count clip-ids)) " to Track")
                  (str "Move " (n-clips (count clip-ids))))
    (:trim-start :trim-end) "Trim Clip"
    :delete-clips (str "Delete " (n-clips (count clip-ids)))
    :duplicate-clips (str "Duplicate " (n-clips (count clip-ids)))
    :add-clip "Add Clip"
    :add-effect "Add Effect"
    :remove-effect "Remove Effect"
    :set-effect-enabled "Toggle Effect"
    :move-effect "Move Effect"
    :set-clip-track "Move Clip to Track"
    :set-clip-target "Change Clip Target"
    :set-track-target "Change Track Target"
    :add-track "Add Track"
    :add-folder "Add Folder"
    :delete-tracks (str "Delete " (n-tracks (count track-ids)))
    :move-tracks (str "Move " (n-tracks (count track-ids)))
    :rename-track "Rename Track"
    :set-track-color "Change Track Colour"
    :set-track-muted "Mute Track"
    :set-collapsed "Collapse Folder"
    (:set-loop :move-loop :trim-loop-start :trim-loop-end) "Edit Loop"
    :set-length "Set Timeline Length"
    :set-tempo "Set Timeline Tempo"
    :set-chase "Set Follow Timecode"
    :set-cue-target "Change Show Target"
    (:set-tc-start :set-tc-fps) "Set Timecode"
    "Edit Timeline"))
