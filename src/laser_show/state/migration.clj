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
   zones-z2-rig).

   Z4's rows (zones-z4, cue-z4; 06 §8.3) run after them, so a reference to a
   removed built-in already names the automatic group: every way an old show
   routed becomes a :target, and the Zone Selector goes."
  (:require [laser-show.animation.chains :as chains]
            [laser-show.routing.refs :as refs]
            [laser-show.routing.rig :as rig]
            [laser-show.routing.targets :as targets]))


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


;; Z4's rows: targets (06 §8.3)

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

(defn- zone-selector? [fx]
  (= :zone-selector (:effect-id fx)))

(defn effects-z4
  "effects without a Zone Selector, inside effect groups too: routing reads
   no raw parameter (spec §2), and one left behind would do nothing. The
   same vector when there is none."
  [effects]
  (if (and (vector? effects) (some #(or (zone-selector? %) (chains/group? %)) effects))
    (let [kept (into [] (remove zone-selector?) effects)
          kept (mapv-same #(if (chains/group? %) (update-same % :items effects-z4) %) kept)]
      (if (and (= (count kept) (count effects)) (every? true? (map identical? kept effects)))
        effects
        kept))
    effects))

(defn- selector-target
  "The zone an item's Zone Selectors routed it to: the first one on, among
   its own effects (not inside an effect group), and its :target-zone, as
   routing read it (zone_effects.clj); nil when it has none."
  [effects]
  (when-let [fx (first (filter #(and (zone-selector? %) (chains/item-enabled? %)) effects))]
    (get-in fx [:params :target-zone])))

(defn- node-z4
  "An item, group or clip (track? false) or a track or folder (track? true)
   in Z4's form: its :zone-group-id is its :target, else (an item only) the
   zone its first Zone Selector on routed it to; the selectors go; its
   children too. A :target it has stays. The same map when it has nothing
   to convert."
  [node track?]
  (if-not (map? node)
    node
    (let [own (when (contains? node :zone-group-id) (:zone-group-id node))
          target (when-not (contains? node :target)
                   (targets/normalize-target
                    (if (some? own) own (when-not track? (selector-target (:effects node))))))]
      (cond-> node
        (contains? node :zone-group-id) (dissoc :zone-group-id)
        (some? target) (assoc :target target)
        true (update-same :effects effects-z4)
        true (update-same :items #(mapv-same (fn [child] (node-z4 child track?)) %))))))

(defn items-z4
  "Cue chain items (presets, groups, clips) in Z4's form: what a paste from
   an older build brings in."
  [items]
  (mapv-same #(node-z4 % false) items))

(defn cue-z4
  "A cue in Z4's form (06 §8.3): its [:destination-zone :zone-group-id] is
   its :target (All, or none, is no target: it inherits, down to All on an
   old show), every :zone-group-id is a :target, an item's first Zone
   Selector on gives it its target when it had none, and every Zone
   Selector goes. Looks at the data, not at a flag: a cue in Z4's form comes
   back identical?."
  [cue]
  (if-not (map? cue)
    cue
    (let [dest (targets/normalize-target (get-in cue [:destination-zone :zone-group-id]))
          target (when-not (or (contains? cue :target) (= :all dest)) dest)]
      (cond-> cue
        (contains? cue :destination-zone) (dissoc :destination-zone)
        (some? target) (assoc :target target)
        true (update-same :items items-z4)
        true (update-same :tracks #(mapv-same (fn [t] (node-z4 t true)) %))))))

(defn- cell-z4
  "A preview cell as {:target …}: an old {:zone-group-id x} is {:target x};
   one with both shows its :target, so it keeps that."
  [cell]
  (cond
    (not (map? cell)) cell
    (contains? cell :target) (if (contains? cell :zone-group-id) (dissoc cell :zone-group-id) cell)
    (contains? cell :zone-group-id) (-> cell (assoc :target (:zone-group-id cell)) (dissoc :zone-group-id))
    :else cell))

(defn- update-vals-same
  "m with f on each value, m itself when nothing changes."
  [m f]
  (if (map? m)
    (reduce-kv (fn [acc k v]
                 (let [v' (f v)]
                   (if (identical? v v') acc (assoc acc k v'))))
               m m)
    m))

(defn zones-z4
  "Z4's rows on a loaded show (06 §8.3): every cue in Z4's form (cue-z4),
   every Zone Selector out of the effects grid (one there never routed,
   probe v20), the preview's cells {:target …}. Run after Z2's rows, before
   the saved baseline is taken, so a converted show isn't unsaved. state
   comes back identical? when there is nothing to convert."
  [state]
  (-> state
      (update-same :chains
                   (fn [chains]
                     (-> chains
                         (update-same :cue-chains #(update-vals-same % cue-z4))
                         (update-same :effect-chains #(update-vals-same % (fn [cell] (if (map? cell) (update-same cell :items effects-z4) cell)))))))
      (update-same :config
                   (fn [config]
                     (update-same config :preview
                                  (fn [preview]
                                    (update-same preview :grid-cells #(if (vector? %) (mapv-same cell-z4 %) %))))))))
