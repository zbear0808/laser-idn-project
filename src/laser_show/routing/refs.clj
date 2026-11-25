(ns laser-show.routing.refs
  "Every place a zone-group id can be stored, in one table: which cues name a
   group, and re-pointing or clearing every reference at once.

   The places (ref-places):
   - a cue's :target (zones plan Z4), and a show's ([:chains :shows], a
     timeline cue map, rewritten as a cue);
   - the :target of every item and group, recursively (cue chain items,
     timeline clips and group clips), and of every timeline track and folder,
     a ref inside a union too;
   - the preview's [:config :preview :grid-cells i :target] and
     [:config :preview :zone-group-filter];
   - the grid's column defaults, [:grid :column-targets col], and the
     show's, [:config :default-target] (Z4);
   - every zone map's lanes and overrides, [:zone-maps id :lanes i] and
     [:zone-maps id :overrides zone] (Z4). A lane cleared is left empty, so
     the zones after it keep their lanes (SQ-13, spec R5); an override
     cleared goes, so its zone wraps again;
   - the zone patterns' own fields (Z5, Z6), on a cue (:zone-patterns) and
     in an effect cell (:items, through its groups): each stored parameter
     map's places (:zones), source (:from) and lit steps (:steps), the
     keyframes' too, on or off (pattern-ref-sites).

   Membership lists (a projector's or virtual projector's :zone-groups) are
   not places: they say which lasers are in a group, not where content goes.
   Nothing here touches them.

   Z0's keys (a cue's [:destination-zone :zone-group-id], an item's,
   group's, clip's or track's :zone-group-id, a Zone Selector's
   :target-zone, a preview cell's :zone-group-id) are no places since zones
   plan Z5 (Z4-Q7 A): loading and pasting turn them into targets
   (state.migration), and an old show's Z2 rows re-point them there first.

   Rewrites keep the identity of everything they don't change, so a state
   where nothing names the old id comes back identical?."
  (:require [laser-show.animation.chains :as chains]
            [laser-show.routing.targets :as targets]))


;; Identity-keeping updates


(defn- mapv-same
  "mapv f over v, but v itself when f changes no element."
  [f v]
  (if (empty? v)
    v
    (let [v' (mapv f v)]
      (if (every? true? (map identical? v v')) v v'))))

(defn- update-same
  "update m k f when m has k, keeping m when f returns the identical value."
  [m k f]
  (if (contains? m k)
    (let [old (get m k)
          new (f old)]
      (if (identical? old new) m (assoc m k new)))
    m))


;; One reference


(defn- replaced
  "[found? new] for id in smap ({old new})."
  [smap id]
  (if (and (some? id) (contains? smap id))
    [true (get smap id)]
    [false id]))

(defn- rewrite-value
  "A value that can't be absent (the preview filter): re-pointed, or
   else-value when its new value is nil."
  [id smap else-value]
  (let [[found? new] (replaced smap id)]
    (cond
      (not found?) id
      (nil? new) else-value
      :else new)))


;; A target (zones plan Z4): one ref, or a union


(defn- rewrite-target
  "Target t with each ref smap names re-pointed, a ref cleared (nil) taken
   out, in normal form again: [:graphics :zone/a] cleared of :graphics is
   :zone/a, and of every ref nil. t itself when it names nothing in smap."
  [t smap]
  (let [refs (targets/target-refs t)]
    (if (some #(and (some? %) (contains? smap %)) refs)
      (targets/normalize-target (keep #(if (contains? smap %) (get smap %) %) refs))
      t)))

(defn- rewrite-target-key
  "m's target at k re-pointed (rewrite-target), the key removed when no ref
   is left, so it inherits."
  [m k smap]
  (if (contains? m k)
    (let [old (get m k)
          new (rewrite-target old smap)]
      (cond
        (identical? old new) m
        (nil? new) (dissoc m k)
        :else (assoc m k new)))
    m))


;; Items, groups, clips, tracks and folders


(declare rewrite-nodes)

(defn- rewrite-node
  "An item, group, clip, track or folder: its :target and its children."
  [node smap]
  (-> node
      (rewrite-target-key :target smap)
      (update-same :items #(rewrite-nodes % smap))))

(defn- rewrite-nodes [nodes smap]
  (mapv-same #(rewrite-node % smap) nodes))

(defn- node-refs [node]
  (concat (targets/target-refs (:target node))
          (mapcat node-refs (:items node))))


;; Zone patterns (zones plans Z5, Z6): the refs their own fields store

(def ^:private pattern-tokens
  "What a pattern's fields hold that is no ref: Every laser in a lane,
   Everything the cue draws as a source."
  #{:lane/lasers :any})

(defn- pattern-ref? [x]
  (and (keyword? x) (not (contains? pattern-tokens x))))

(defn- pattern-params
  "[[path params] …]: every stored parameter map of the zone patterns in
   items (a chain root: a cue's :zone-patterns, an effect cell's :items),
   path from the root: each pattern's :params and each of its keyframes',
   through chain groups, on or off. Nothing else is looked into: not a
   point effect, a Map cycle, a value that is a map."
  [items]
  (letfn [(walk [prefix items]
            (when (sequential? items)
              (mapcat (fn [i item]
                        (let [at (conj prefix i)]
                          (cond
                            (not (map? item)) nil
                            (chains/group? item) (walk (conj at :items) (:items item))
                            (= :zone-pattern (:effect-id item))
                            (concat
                             (when (map? (:params item)) [[(conj at :params) (:params item)]])
                             (let [kfs (get-in item [:keyframe-modulator :keyframes])]
                               (when (sequential? kfs)
                                 (keep-indexed (fn [k kf]
                                                 (when (and (map? kf) (map? (:params kf)))
                                                   [(into at [:keyframe-modulator :keyframes k :params]) (:params kf)]))
                                               kfs))))
                            :else nil)))
                      (range) items)))]
    (vec (walk [] items))))

(defn- param-sites
  "The refs one parameter map stores, paths from it: each direct ref of
   :zones (:lane), :from (:source), each direct ref of a step (:lit)."
  [{:keys [zones from steps]}]
  (concat
   (when (sequential? zones)
     (keep-indexed (fn [i r] (when (pattern-ref? r) {:path [:zones i] :role :lane :ref r})) zones))
   (when (pattern-ref? from)
     [{:path [:from] :role :source :ref from}])
   (when (sequential? steps)
     (apply concat (keep-indexed (fn [s step]
                                   (when (sequential? step)
                                     (keep-indexed (fn [j r] (when (pattern-ref? r) {:path [:steps s j] :role :lit :ref r})) step)))
                                 steps)))))

(defn- pattern-ref-sites
  "Every ref the zone patterns in items (a chain root) store, and where:
   [{:path (from the root) :role :lane|:source|:lit :ref} …]. What a pattern
   names, not where it sends pictures (zone-patterns/reach): a source, an
   unknown ref, a pattern or keyframe that is off count."
  [items]
  (into []
        (mapcat (fn [[path params]] (map #(update % :path (partial into path)) (param-sites params))))
        (pattern-params items)))

(defn- pattern-refs
  "The set of refs the zone patterns in items (a chain root) store."
  [items]
  (into #{} (map :ref) (pattern-ref-sites items)))

(defn- re-pointed
  "ref under replacements: itself when they don't name it (or it is no
   ref), ::cleared for nil."
  [replacements ref]
  (if (and (pattern-ref? ref) (contains? replacements ref))
    (let [new (get replacements ref)]
      (if (nil? new) ::cleared new))
    ref))

(defn- moves? [replacements ref]
  (and (pattern-ref? ref) (contains? replacements ref) (not= ref (get replacements ref))))

(defn- in-form
  "xs as a vector, or as a list when coll is one."
  [coll xs]
  (if (vector? coll) (vec xs) (apply list xs)))

(defn- same-or
  "coll when xs holds the same elements in the same order, else xs in
   coll's form."
  [coll xs]
  (if (and (= (count coll) (count xs)) (every? true? (map identical? coll xs)))
    coll
    (in-form coll xs)))

(defn- each-ref-once
  "xs with each ref once, the first kept; anything else where it is."
  [xs]
  (let [seen (volatile! #{})]
    (filterv #(or (not (pattern-ref? %))
                  (when-not (contains? @seen %) (vswap! seen conj %)))
             xs)))

(defn- re-pointed-seq
  "A lane or a step with its refs re-pointed, one lookup per ref, a cleared
   one out, then each ref once; xs itself when replacements move none of
   its refs."
  [xs replacements]
  (if (some #(moves? replacements %) xs)
    (same-or xs (each-ref-once (remove #{::cleared} (map #(re-pointed replacements %) xs))))
    xs))

(defn- rewrite-params
  "One parameter map with its refs re-pointed (map-pattern-refs). A lane
   that moves puts every step's lights in its new order; a map without a
   lane (a sparse keyframe's) keeps its steps' order. params itself when
   nothing moves."
  [params replacements]
  (let [{:keys [zones steps from]} params
        zones' (if (sequential? zones) (re-pointed-seq zones replacements) zones)
        rank (when-not (identical? zones zones')
               (zipmap (filter pattern-ref? zones') (range)))
        step' (fn [step]
                (if (sequential? step)
                  (let [s (re-pointed-seq step replacements)]
                    (if rank (same-or step (sort-by #(get rank % Long/MAX_VALUE) s)) s))
                  step))
        steps' (if (sequential? steps) (same-or steps (map step' steps)) steps)
        from' (re-pointed replacements from)]
    (cond-> params
      (not (identical? zones zones')) (assoc :zones zones')
      (not (identical? steps steps')) (assoc :steps steps')
      (= ::cleared from') (dissoc :from)
      (not (or (= ::cleared from') (identical? from from'))) (assoc :from from'))))

(defn- assoc-at
  "coll with v at k: a map's key, or a vector's or a list's index (a list
   stays one)."
  [coll k v]
  (if (or (map? coll) (vector? coll))
    (assoc coll k v)
    (apply list (assoc (vec coll) k v))))

(defn- assoc-path [coll [k & ks] v]
  (if ks
    (assoc-at coll k (assoc-path (if (or (map? coll) (vector? coll)) (get coll k) (nth coll k)) ks v))
    (assoc-at coll k v)))

(defn- map-pattern-refs
  "items (a chain root) with the refs its zone patterns store re-pointed by
   replacements {old new}, at pattern-ref-sites' sites and nowhere else:
   an absent key keeps a ref, nil clears it, one lookup per original ref (a
   swap swaps). A lane keeps its order and each ref once, a merged one at
   the first place it had; its steps keep their length and rests, each one
   its lights once, in the lane's new order; a cleared ref leaves them, and
   the last place cleared leaves :zones []. A step without a lane in its
   map (a sparse keyframe's) is re-pointed in place. A cleared :from goes
   (the default, All). Vectors stay vectors and lists lists; items itself
   when nothing moves."
  [items replacements]
  (if (empty? replacements)
    items
    (reduce (fn [items [path params]]
              (let [params' (rewrite-params params replacements)]
                (if (identical? params params') items (assoc-path items path params'))))
            items
            (pattern-params items))))


;; Cues


(defn cue-refs
  "The set of refs cue's own data names in any place, its zone patterns'
   too. A cue with no target of its own names :all, as the routing falls
   back to it when its column and the show set none. (What a cue can draw
   to, its column's and the show's defaults included, is
   routing.targets/cue-refs.)"
  [cue]
  (let [own (targets/target-refs (:target cue))]
    (into (if (seq own) (set own) #{:all})
          (remove nil?)
          (concat (mapcat node-refs (:items cue))
                  (mapcat node-refs (:tracks cue))
                  (pattern-refs (:zone-patterns cue))))))

(defn cues-using
  "The sorted set of grid cells [col row] whose cue names ref, from
   cue-chains ([:chains :cue-chains]). Effect cells and the preview are not
   cues, so they never count."
  [cue-chains ref]
  (into (sorted-set)
        (keep (fn [[cell cue]]
                (when (and cue (contains? (cue-refs cue) ref)) cell)))
        cue-chains))

(defn effect-cells-using
  "The sorted set of effect cells [col row] whose zone patterns store ref,
   from effect-chains ([:chains :effect-chains]): cues-using's effects grid."
  [effect-chains ref]
  (into (sorted-set)
        (keep (fn [[cell chain]]
                (when (and (map? chain) (contains? (pattern-refs (:items chain)) ref)) cell)))
        effect-chains))

(defn- ref-usage
  "chain-ref-usage, each entry starting as owners."
  [owners cue-chains effect-chains shows]
  (let [add (fn [usage k cell refs]
              (reduce (fn [usage ref] (update usage ref #(update (or % owners) k conj cell))) usage refs))
        cue-owners (fn [k]
                     (fn [usage id cue] (if (map? cue) (add usage k id (cue-refs cue)) usage)))
        usage (reduce-kv (cue-owners :cues) {} (or cue-chains {}))
        usage (reduce-kv (cue-owners :shows) usage (or shows {}))]
    (reduce-kv (fn [usage cell chain] (if (map? chain) (add usage :effect-cells cell (pattern-refs (:items chain))) usage))
               usage
               (or effect-chains {}))))

(defn shows-using
  "The sorted set of show ids whose show names ref, from shows ([:chains
   :shows] {id show}): cues-using's show library."
  [shows ref]
  (into (sorted-set)
        (keep (fn [[id show]]
                (when (and (map? show) (contains? (cue-refs show) ref)) id)))
        shows))

(defn chain-ref-usage
  "Who stores each ref in the show's two grids: {ref {:cues #{[col row] …}
   :effect-cells #{[col row] …}}}, sorted sets, both there for every ref
   named; a cue's refs are cue-refs' (its implicit All too), an effect
   cell's its zone patterns'. Every literal reference, whether or not
   anything draws there now: the zone catalogue and the rig report read it.
   Given shows ([:chains :shows]) too, each entry has :shows #{id …} as
   well, a show's refs as a cue's."
  ([cue-chains effect-chains]
   (ref-usage {:cues (sorted-set) :effect-cells (sorted-set)} cue-chains effect-chains nil))
  ([cue-chains effect-chains shows]
   (ref-usage {:cues (sorted-set) :effect-cells (sorted-set) :shows (sorted-set)} cue-chains effect-chains shows)))

(defn- rewrite-cue [cue smap]
  (-> cue
      (rewrite-target-key :target smap)
      (update-same :items #(rewrite-nodes % smap))
      (update-same :tracks #(rewrite-nodes % smap))
      (update-same :zone-patterns #(map-pattern-refs % smap))))


;; The places


(defn- update-vals-same
  "m with f applied to each value, m itself when nothing changes."
  [m f]
  (reduce-kv (fn [acc k v]
               (let [v' (f v)]
                 (if (identical? v v') acc (assoc acc k v'))))
             m m))

(defn- update-in-same [m path f]
  (if (seq path)
    (update-same m (first path) #(update-in-same % (rest path) f))
    (f m)))

(def ref-places
  "Every place a zone-group id is stored: {:kind :replace}, where :replace
   is (fn [state smap] state') and keeps state identical? when nothing
   changes. Later phases add their places here. A cue's zone patterns are
   the cue's (:cue-chains), an effect cell's its cell's (:effect-chains);
   a show (a timeline cue map, [:chains :shows]) is rewritten as a cue."
  [{:kind :cue-chains
    :replace (fn [state smap]
               (update-in-same state [:chains :cue-chains]
                               #(update-vals-same % (fn [cue] (when cue (rewrite-cue cue smap))))))}
   {:kind :shows
    :replace (fn [state smap]
               (update-in-same state [:chains :shows]
                               #(update-vals-same % (fn [show] (when show (rewrite-cue show smap))))))}
   {:kind :effect-chains
    :replace (fn [state smap]
               (update-in-same state [:chains :effect-chains]
                               #(update-vals-same % (fn [cell] (if (map? cell) (update-same cell :items (fn [items] (map-pattern-refs items smap))) cell)))))}
   {:kind :preview-cells
    :replace (fn [state smap]
               (update-in-same state [:config :preview :grid-cells]
                               (fn [cells]
                                 (mapv-same (fn [cell]
                                              (let [[found? new] (replaced smap (get cell :target))]
                                                (if (and found? (contains? cell :target)) (assoc cell :target new) cell)))
                                            cells))))}
   {:kind :preview-filter
    :replace (fn [state smap]
               (update-in-same state [:config :preview :zone-group-filter]
                               #(rewrite-value % smap :all)))}
   {:kind :column-targets
    :replace (fn [state smap]
               (update-in-same state [:grid]
                               #(if (map? %) (update-same % :column-targets (fn [cols] (reduce-kv (fn [acc col _] (rewrite-target-key acc col smap)) cols cols))) %)))}
   {:kind :default-target
    :replace (fn [state smap]
               (update-in-same state [:config]
                               #(if (map? %) (update-same % :default-target (fn [t] (rewrite-target t smap))) %)))}
   {:kind :zone-maps
    :replace (fn [state smap]
               (update-same state :zone-maps
                            #(update-vals-same % (fn [m]
                                                   (if (map? m)
                                                     (-> m
                                                         (update-same :lanes (fn [lanes] (mapv-same (fn [lane] (rewrite-target lane smap)) lanes)))
                                                         (update-same :overrides (fn [o] (reduce-kv (fn [acc zone _] (rewrite-target-key acc zone smap)) o o))))
                                                     m)))))}])

(defn replace-refs
  "Re-point every reference to a zone group in state, by smap {old new}.
   A nil new clears it: the ref leaves a target (a union keeps its other
   refs; with none left the key goes and the level inherits), a column
   default goes, the show default is none (All), a map lane is left empty
   and an override goes; a preview cell shows the master view (nil) and
   the preview filter :all; a zone pattern drops it from its places and
   steps, and a source of it goes (map-pattern-refs). Membership lists are
   left alone. Returns state itself (identical?) when nothing names an old
   id."
  [state smap]
  (if (empty? smap)
    state
    (reduce (fn [s {:keys [replace]}] (replace s smap)) state ref-places)))
