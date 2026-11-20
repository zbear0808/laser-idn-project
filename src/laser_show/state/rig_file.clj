(ns laser-show.state.rig-file
  "The rig as a document of its own (D-30, plans/2026-11-19-zones/09): the
   projectors, their order, safety zones, calibration and group membership
   belong to the machine and the venue, the show is the art. Pure: the file
   itself is state.rig-store's.

   The rig file holds the rig paths (rig-paths) and a copy of the groups the
   rig's lasers are in (group-copy), so New Project can bring them along and
   a show that doesn't define them can be told so."
  (:require [laser-show.routing.rig :as rig]
            [laser-show.state.domains :as domains]))


;; The document

(def version
  "The rig file's format. A file with a higher :rig-file/version comes from
   a newer build: it is used, but never written (rig-store/read!)."
  1)

(def rig-paths
  "The rig's paths in state: what the rig file holds. A show's zip still
   carries a copy of them (Q-ZR-2). state.persistent's mapping marks the same
   paths :owner :rig (rig-file-test checks they agree)."
  [[:projectors] [:virtual-projectors] [:rig] [:chains :projector-effects]])

(def ^:private merged-paths
  "Settings maps among rig-paths: a file's value is merged over the
   defaults, so a key added after it was written gets its default (as
   persistent's :merge-defaults?)."
  #{[:rig]})

(defn- listed-groups
  "The custom groups the rig's projectors and virtual projectors list (never
   All or an automatic group)."
  [state]
  (into #{}
        (mapcat rig/custom-refs)
        (concat (vals (:projectors state)) (vals (:virtual-projectors state)))))

(defn group-copy
  "The rig's copy of the groups its lasers are in: the previous copy
   ([:rig-file :group-copy]) with the show's current definitions over it,
   kept to the groups some projector or virtual projector lists. Membership
   is the rig's and definitions are the show's (spec 01 §12): the copy lets
   New Project bring the groups back and the report name them."
  [state]
  (select-keys (merge (get-in state [:rig-file :group-copy]) (:zone-groups state))
               (listed-groups state)))

(defn rig-document
  "The rig file's contents for state (09 §8): {:rig-file/version :projectors
   :virtual-projectors :rig :chains {:projector-effects} :zone-groups (the
   group copy)}."
  [state]
  (reduce (fn [doc path] (assoc-in doc path (get-in state path)))
          {:rig-file/version version
           :zone-groups (group-copy state)}
          rig-paths))

(defn- differs? [a b]
  (and (not (identical? a b)) (not= a b)))

(defn rig-changed?
  "Did the rig the file holds change from old to new: a rig path (an equal
   value rebuilt doesn't count), or the definition of a group the rig's
   lasers are in (the group copy)?"
  [old new]
  (boolean
   (or (some #(differs? (get-in old %) (get-in new %)) rig-paths)
       (and (not (identical? (:zone-groups old) (:zone-groups new)))
            (not= (group-copy old) (group-copy new))))))

(defn- map-or-nil? [v]
  (or (nil? v) (map? v)))

(defn valid-rig-document?
  "Is doc shaped like a rig file: a map with a whole :rig-file/version of 1
   or more, maps (or nothing) at the rig's paths and :zone-groups, and a map
   for each projector?"
  [doc]
  (and (map? doc)
       (pos-int? (:rig-file/version doc))
       (every? #(map-or-nil? (get-in doc %)) (into [[:chains] [:zone-groups]] rig-paths))
       (every? map? (vals (:projectors doc)))))

(defn newer?
  "Does doc come from a newer build than this one?"
  [doc]
  (and (pos-int? (:rig-file/version doc)) (> (:rig-file/version doc) version)))

(defn apply-rig-document
  "state with its rig paths replaced by doc's (a rig file): a path the file
   doesn't have gets its initial value, and [:rig]'s keys the file doesn't
   have get their defaults. The file's group copy goes to
   [:rig-file :group-copy]. The show's paths are left as they are."
  [state doc]
  (let [initial (domains/build-initial-state)]
    (-> (reduce (fn [s path]
                  (let [v (get-in doc path)
                        default (get-in initial path)]
                    (assoc-in s path (cond
                                       (nil? v) default
                                       (contains? merged-paths path) (merge default v)
                                       :else v))))
                state
                rig-paths)
        (assoc-in [:rig-file :group-copy] (or (:zone-groups doc) {})))))
