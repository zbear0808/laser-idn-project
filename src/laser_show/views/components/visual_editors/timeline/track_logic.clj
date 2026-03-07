(ns laser-show.views.components.visual-editors.timeline.track-logic
  "Pure helper functions for Track-based Timeline layout.
   
   A Track is an explicit object within a CueChain that groups items
   for organizational and zone-routing purposes.
   
   Track structure:
   {:id         UUID
    :name       String   — user-visible label  (default: zone group name)
    :zone-group-id  Keyword  — which zone group this track routes to
    :color      String   — hex color (optional, derived from zone if nil)
    :visible?   Boolean  — whether hidden in UI (default true)
    :collapsed? Boolean  — whether sub-items are collapsed (default false)}
   
   Items reference their track via :track-id.
   Multiple tracks may point to the same :zone-group-id."
  (:require [laser-show.animation.chains :as chains]
            [laser-show.common.util :as u]))


;; ============================================================
;; Track Creation
;; ============================================================


(defn create-track
  "Create a new track with a zone-group assignment.
   
   Parameters:
   - opts map with:
     :zone-group-id — required, keyword like :all, :front, :back
     :name          — optional, defaults to zone-group-id name
     :color         — optional hex color string
   
   Returns: Track map with fresh UUID."
  [{:keys [zone-group-id name color]}]
  (u/assoc-some
   {:id (random-uuid)
    :type :track
    :zone-group-id (or zone-group-id :all)
    :name (or name (clojure.core/name (or zone-group-id :all)))
    :visible? true
    :collapsed? false}
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

(defn track-index
  "Return the flat index of a track by ID within a sequence of FLATTENED tracks, or nil if not found."
  [flat-tracks track-id]
  (first
   (keep-indexed (fn [i t] (when (= (:id t) track-id) i))
                 flat-tracks)))

(defn track-group?
  "Returns true if the track is a group/folder."
  [track]
  (boolean
   (or (= (:type track) :group)
       (contains? track :items))))

(defn flatten-visible-tracks
  "Takes a nested vector of tracks and returns a flat view of all tracks
   that should be visible on the canvas. 
   
   If a track-group is marked as :collapsed? true, its children are OUT of the view.
   Also propagates inherited :zone-group-id down from parents to children if 
   the children do not have their own explicit zone."
  [tracks]
  (letfn [(flatten-level [level-tracks parent-zone-id]
            (mapcat (fn [track]
                      (let [effective-zone (or (:zone-group-id track) parent-zone-id)
                            track-with-zone (assoc track :zone-group-id effective-zone)]
                        (if (track-group? track)
                          ;; It's a folder. We include the folder itself as a row, 
                          ;; then conditionally include its children
                          (into [track-with-zone]
                                (when-not (:collapsed? track)
                                  (flatten-level (:items track []) effective-zone)))
                          ;; Regular track
                          [track-with-zone])))
                    level-tracks))]
    (flatten-level tracks :all)))


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

(defn track-zone-id
  "Resolve a zone-group-id for an item by looking up its track.
   Falls back to default-zone when track is not found."
  [tracks item default-zone]
  (if-let [tid (:track-id item)]
    (if-let [track (find-track-by-id tracks tid)]
      (:zone-group-id track)
      default-zone)
    default-zone))


;; ============================================================
;; Track Mutation Helpers (pure)
;; ============================================================


(defn add-track
  "Append a new track to the tracks vector.
   Returns updated tracks."
  [tracks opts]
  (conj (vec tracks) (create-track opts)))

(defn remove-track
  "Remove a track by ID. Returns updated tracks vector.
   Does NOT handle orphaned items — caller must reassign."
  [tracks track-id]
  (into [] (remove #(= (:id %) track-id)) tracks))

(defn update-track
  "Update a track by ID with the given key-value pairs.
   Returns updated tracks vector."
  [tracks track-id updates]
  (mapv (fn [t]
          (if (= (:id t) track-id)
            (merge t updates)
            t))
        tracks))

(defn move-track
  "Move a track from one index to another.
   Returns reordered tracks vector."
  [tracks from-idx to-idx]
  (let [tracks (vec tracks)
        item (nth tracks from-idx)
        without (into (subvec tracks 0 from-idx)
                      (subvec tracks (inc from-idx)))
        before (subvec without 0 (min to-idx (count without)))
        after (subvec without (min to-idx (count without)))]
    (into [] cat [before [item] after])))


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
                                                                      :name (str name-str " 1")
                                                                      :color (:color zg)})
                                           folder (assoc (create-track {:zone-group-id zone-id
                                                                        :name name-str
                                                                        :color (:color zg)})
                                                         :type :group
                                                         :collapsed? false
                                                         :items [child-track])]
                                       [zone-id {:folder folder :child-track child-track}])))
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
