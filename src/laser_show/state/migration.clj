(ns laser-show.state.migration
  "Converting old shows on load (plans/2026-11-19-zones/01 §11, 04 §4).
   Pure, silent and safe to run twice: each row looks at the data, not at a
   flag. apply-loaded-project runs it before the saved baseline is taken, so
   a converted show isn't unsaved.

   Z2's rows run when the loaded hardware.edn has no :rig (every file Z2
   saves has one). They are two functions, so the rig file (ZR) can run them
   apart: rig-rows (the projectors) and show-rows (the groups). Z4 adds its
   rows here."
  (:require [laser-show.routing.refs :as refs]
            [laser-show.routing.rig :as rig]))


;; Rig rows: the projectors and virtual projectors

(defn- all-membership
  "All is implicit (D-20): an output without :exclude-from-all? is excluded
   when it didn't list :all, and :all leaves its list."
  [output]
  (let [groups (:zone-groups output)
        all? (boolean (some #{:all} groups))]
    (cond-> output
      (not (contains? output :exclude-from-all?)) (assoc :exclude-from-all? (not all?))
      all? (assoc :zone-groups (into [] (remove #{:all}) groups)))))

(defn- with-stable-ids
  "Each projector without :stable-id gets one; two with one unit id are
   settled as a scan settles them (rig/settled-ids)."
  [projectors]
  (let [settled (rig/settled-ids projectors)]
    (reduce-kv (fn [ps id p]
                 (if (contains? p :stable-id) ps (assoc-in ps [id :stable-id] (get settled id))))
               projectors
               projectors)))

(defn rig-rows
  "hardware (a map with :projectors and :virtual-projectors) with every
   output's All membership converted and every projector given a stable id.
   No order is written: rig/effective-order reads IP, then name (04 S-11)."
  [hardware]
  (cond-> hardware
    (:projectors hardware) (update :projectors #(with-stable-ids (update-vals % all-membership)))
    (:virtual-projectors hardware) (update :virtual-projectors #(update-vals % all-membership))))


;; Show rows: the group definitions and what names them

(def ^:private built-ins
  "The built-in groups that became automatic ones, and their names."
  [[:left :auto/left "Left"] [:right :auto/right "Right"] [:center :auto/center "Center"]])

(defn show-rows
  "state with the built-in :left, :right and :center converted: one no
   projector or virtual projector of the loaded hardware lists (on or off)
   goes, and every reference to it (routing.refs) names the automatic group
   instead; one that is listed stays, as \"Left (manual)\" while it is still
   called \"Left\". A built-in deleted before is left alone, and so is what
   names it (04 S-12). All's definition goes: All is built in (04 S-9)."
  [state hardware]
  (let [outputs (concat (vals (:projectors hardware)) (vals (:virtual-projectors hardware)))
        listed? (fn [group] (some #(some #{group} (:zone-groups %)) outputs))]
    (reduce (fn [s [old auto label]]
              (cond
                (not (contains? (:zone-groups s) old)) s
                (listed? old) (cond-> s
                                (= label (get-in s [:zone-groups old :name]))
                                (assoc-in [:zone-groups old :name] (str label " (manual)")))
                :else (-> s
                          (update :zone-groups dissoc old)
                          (refs/replace-refs {old auto}))))
            (cond-> state
              (contains? (:zone-groups state) :all) (update :zone-groups dissoc :all))
            built-ins)))

(defn zones-z2
  "Z2's rows (04 §4), when hardware (the loaded hardware.edn) has no :rig:
   show-rows, then rig-rows over state's projectors and virtual projectors.
   state comes back identical when the file has a :rig."
  [state hardware]
  (if (contains? hardware :rig)
    state
    (merge (show-rows state hardware)
           (rig-rows (select-keys state [:projectors :virtual-projectors])))))
