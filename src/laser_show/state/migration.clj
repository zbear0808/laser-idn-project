(ns laser-show.state.migration
  "Converting old shows on load (plans/2026-11-19-zones/01 §11, 04 §4).
   Pure, silent and safe to run twice: each row looks at the data, not at a
   flag. apply-loaded-project runs it before the saved baseline is taken, so
   a converted show isn't unsaved.

   Z2's rows run when the loaded hardware.edn has no :rig (every file Z2
   saves has one). They are two functions, so the rig file (ZR) runs them
   apart: show-rows (the groups) on every Open of an old show, judged by
   its own hardware, and rig-rows (the projectors) only on a show's
   hardware, before its lasers are set up or imported (zones-z2-show,
   zones-z2-rig). Z4 adds its rows here."
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

(defn old-hardware?
  "Is hardware (a hardware.edn) from before Z2: no :rig (every file Z2 saves
   has one)?"
  [hardware]
  (not (contains? hardware :rig)))

(defn zones-z2
  "Z2's rows (04 §4), when hardware (the loaded hardware.edn) has no :rig:
   show-rows, then rig-rows over state's projectors and virtual projectors.
   state comes back identical when the file has a :rig. From ZR on a show's
   load runs only the show half (zones-z2-show): the rig isn't the show's."
  [state hardware]
  (if (contains? hardware :rig)
    state
    (merge (show-rows state hardware)
           (rig-rows (select-keys state [:projectors :virtual-projectors])))))

(defn zones-z2-show
  "Z2's show rows on a show just opened (zones plan ZR), when its hardware
   is from before Z2: the built-in groups judged by the show's own hardware
   (09 S-ZR-7), not by this rig's."
  [state hardware]
  (if (old-hardware? hardware)
    (show-rows state hardware)
    state))

(defn zones-z2-rig
  "Z2's rig rows on a show's own hardware (zones plan ZR), when it is from
   before Z2: just before its lasers are set up on this rig or imported."
  [hardware]
  (if (old-hardware? hardware)
    (rig-rows hardware)
    hardware))
