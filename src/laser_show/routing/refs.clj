(ns laser-show.routing.refs
  "Every place a zone-group id can be stored, in one table: which cues name a
   group, and re-pointing or clearing every reference at once.

   The places (ref-places):
   - a cue's [:destination-zone :zone-group-id];
   - the :zone-group-id of every item and group, recursively (cue chain items,
     timeline clips and group clips), and of every timeline track and folder;
   - every Zone Selector's [:params :target-zone] and its keyframes'
     [:keyframe-modulator :keyframes i :params :target-zone], on or off, in the
     :effects of items, groups, clips, tracks and folders, inside effect
     groups too, in cue chains and effect chains;
   - the preview's [:config :preview :grid-cells i :target] (an old cell's
     :zone-group-id) and [:config :preview :zone-group-filter].

   Membership lists (a projector's or virtual projector's :zone-groups) are
   not places: they say which lasers are in a group, not where content goes.
   Nothing here touches them.

   Rewrites keep the identity of everything they don't change, so a state
   where nothing names the old id comes back identical?."
  (:require [laser-show.animation.chains :as chains]))


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

(defn- rewrite-inheriting
  "m's k (an item's, group's or track's :zone-group-id): re-pointed, or
   removed when its new value is nil, so it inherits."
  [m k smap]
  (let [[found? new] (replaced smap (get m k))]
    (cond
      (not found?) m
      (nil? new) (dissoc m k)
      :else (assoc m k new))))

(defn- rewrite-value
  "A value that can't be absent (a Zone Selector's zone, the preview filter):
   re-pointed, or else-value when its new value is nil."
  [id smap else-value]
  (let [[found? new] (replaced smap id)]
    (cond
      (not found?) id
      (nil? new) else-value
      :else new)))


;; Effects


(defn- zone-selector? [fx]
  (= :zone-selector (:effect-id fx)))

(declare rewrite-effects)

(defn- rewrite-effect
  [fx smap]
  (cond
    (chains/group? fx) (update-same fx :items #(rewrite-effects % smap))

    (zone-selector? fx)
    (-> fx
        (update-same :params #(update-same % :target-zone (fn [z] (rewrite-value z smap :all))))
        (update-same :keyframe-modulator
                     (fn [km]
                       (update-same km :keyframes
                                    (fn [kfs]
                                      (mapv-same (fn [kf]
                                                   (update-same kf :params
                                                                #(update-same % :target-zone
                                                                              (fn [z] (rewrite-value z smap :all)))))
                                                 kfs))))))

    :else fx))

(defn- rewrite-effects [effects smap]
  (mapv-same #(rewrite-effect % smap) effects))

(defn- effect-refs
  "The zones fx names: a Zone Selector's zone and its keyframes' zones,
   inside effect groups too."
  [fx]
  (cond
    (chains/group? fx) (mapcat effect-refs (:items fx))
    (zone-selector? fx) (cons (get-in fx [:params :target-zone])
                              (map #(get-in % [:params :target-zone])
                                   (get-in fx [:keyframe-modulator :keyframes])))
    :else nil))


;; Items, groups, clips, tracks and folders


(declare rewrite-nodes)

(defn- rewrite-node
  "An item, group, clip, track or folder: its :zone-group-id, its effects and
   its children."
  [node smap]
  (-> node
      (rewrite-inheriting :zone-group-id smap)
      (update-same :effects #(rewrite-effects % smap))
      (update-same :items #(rewrite-nodes % smap))))

(defn- rewrite-nodes [nodes smap]
  (mapv-same #(rewrite-node % smap) nodes))

(defn- node-refs [node]
  (concat (when-let [z (:zone-group-id node)] [z])
          (mapcat effect-refs (:effects node))
          (mapcat node-refs (:items node))))


;; Cues


(defn cue-refs
  "The set of zone-group ids cue names in any place. A cue with no
   destination names :all, as the routing falls back to it
   (services/frame_service.clj generate-frames-by-zone)."
  [cue]
  (into #{(or (get-in cue [:destination-zone :zone-group-id]) :all)}
        (remove nil?)
        (concat (mapcat node-refs (:items cue))
                (mapcat node-refs (:tracks cue)))))

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
  (let [[found? new] (replaced smap (get-in cue [:destination-zone :zone-group-id]))]
    (-> (cond
          (not found?) cue
          (nil? new) (dissoc cue :destination-zone)
          :else (assoc-in cue [:destination-zone :zone-group-id] new))
        (update-same :items #(rewrite-nodes % smap))
        (update-same :tracks #(rewrite-nodes % smap)))))


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
   {:kind :effect-chains
    :replace (fn [state smap]
               (update-in-same state [:chains :effect-chains]
                               #(update-vals-same % (fn [chain] (update-same chain :items (fn [fx] (rewrite-effects fx smap)))))))}
   {:kind :preview-cells
    :replace (fn [state smap]
               (update-in-same state [:config :preview :grid-cells]
                               (fn [cells]
                                 (mapv-same (fn [cell]
                                              ;; :target since zones Z3, :zone-group-id before
                                              (let [k (if (contains? cell :target) :target :zone-group-id)
                                                    [found? new] (replaced smap (get cell k))]
                                                (if found? (assoc cell k new) cell)))
                                            cells))))}
   {:kind :preview-filter
    :replace (fn [state smap]
               (update-in-same state [:config :preview :zone-group-filter]
                               #(rewrite-value % smap :all)))}])

(defn replace-refs
  "Re-point every reference to a zone group in state, by smap {old new}.
   A nil new clears it: an item's, group's, clip's or track's :zone-group-id
   is removed (it inherits), a cue's :destination-zone is removed (it falls
   back to :all), a Zone Selector says :all, a preview cell shows the master
   view (nil) and the preview filter :all. Membership lists are left alone.
   Returns state itself (identical?) when nothing names an old id."
  [state smap]
  (if (empty? smap)
    state
    (reduce (fn [s {:keys [replace]}] (replace s smap)) state ref-places)))
