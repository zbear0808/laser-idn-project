(ns laser-show.routing.refs
  "Every place a zone-group id can be stored, in one table: which cues name a
   group, and re-pointing or clearing every reference at once.

   The places (ref-places):
   - a cue's :target (zones plan Z4);
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
     cleared goes, so its zone wraps again.

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
  (:require [laser-show.routing.targets :as targets]))


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


;; Cues


(defn cue-refs
  "The set of refs cue's own data names in any place. A cue with no target
   of its own names :all, as the routing falls back to it when its column
   and the show set none. (What a cue can draw to, its column's and the
   show's defaults included, is routing.targets/cue-refs.)"
  [cue]
  (let [own (targets/target-refs (:target cue))]
    (into (if (seq own) (set own) #{:all})
          (remove nil?)
          (concat (mapcat node-refs (:items cue))
                  (mapcat node-refs (:tracks cue))))))

(defn cues-using
  "The sorted set of grid cells [col row] whose cue names ref, from
   cue-chains ([:chains :cue-chains]). Effect cells and the preview are not
   cues, so they never count."
  [cue-chains ref]
  (into (sorted-set)
        (keep (fn [[cell cue]]
                (when (and cue (contains? (cue-refs cue) ref)) cell)))
        cue-chains))

(defn- rewrite-cue [cue smap]
  (-> cue
      (rewrite-target-key :target smap)
      (update-same :items #(rewrite-nodes % smap))
      (update-same :tracks #(rewrite-nodes % smap))))


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
   changes. Later phases add their places here."
  [{:kind :cue-chains
    :replace (fn [state smap]
               (update-in-same state [:chains :cue-chains]
                               #(update-vals-same % (fn [cue] (when cue (rewrite-cue cue smap))))))}
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
   the preview filter :all. Membership lists are left alone. Returns state
   itself (identical?) when nothing names an old id."
  [state smap]
  (if (empty? smap)
    state
    (reduce (fn [s {:keys [replace]}] (replace s smap)) state ref-places)))
