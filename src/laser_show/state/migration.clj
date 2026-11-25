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
   routed becomes a :target, and the Zone Selector goes.

   timeline-shows runs after Z4's rows: an old show's timeline cues leave
   the grid for the show library, [:chains :shows]
   (plans/2026-11-25-timeline-shows/04 §4.3), and what named their cells
   follows them or lets go."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.common.util :as u]
            [laser-show.routing.refs :as refs]
            [laser-show.routing.rig :as rig]
            [laser-show.routing.targets :as targets]
            [laser-show.timeline.shows :as shows]
            [laser-show.vibes.refs :as vibe-refs]))


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

(defn- zone-selector? [fx]
  (= :zone-selector (:effect-id fx)))

(defn- update-vals-same
  "m with f on each value, m itself when nothing changes."
  [m f]
  (if (map? m)
    (reduce-kv (fn [acc k v]
                 (let [v' (f v)]
                   (if (identical? v v') acc (assoc acc k v'))))
               m m)
    m))


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

(defn- old-ref
  "ref re-pointed by smap {old new}."
  [smap ref]
  (if (and (some? ref) (contains? smap ref)) (get smap ref) ref))

(defn- old-effect
  "A Zone Selector's :target-zone, and its keyframes', re-pointed; inside
   effect groups too."
  [fx smap]
  (cond
    (chains/group? fx) (update-same fx :items (fn [fxs] (mapv-same #(old-effect % smap) fxs)))
    (zone-selector? fx)
    (-> fx
        (update-same :params (fn [p] (update-same p :target-zone #(old-ref smap %))))
        (update-same :keyframe-modulator
                     (fn [km]
                       (update-same km :keyframes
                                    (fn [kfs]
                                      (mapv-same (fn [kf] (update-same kf :params (fn [p] (update-same p :target-zone #(old-ref smap %)))))
                                                 kfs))))))
    :else fx))

(defn- old-node
  "An item, group, clip, track or folder: its :zone-group-id, its effects'
   and its children's re-pointed."
  [node smap]
  (if (map? node)
    (-> node
        (update-same :zone-group-id #(old-ref smap %))
        (update-same :effects (fn [fxs] (mapv-same #(old-effect % smap) fxs)))
        (update-same :items (fn [nodes] (mapv-same #(old-node % smap) nodes))))
    node))

(defn- old-keys
  "state with the groups smap names re-pointed where shows before zones
   plan Z4 named them, which routing.refs no longer reads (Z4-Q7 A): a cue's
   [:destination-zone :zone-group-id], an item's, group's, clip's, track's or
   folder's :zone-group-id, a Zone Selector's :target-zone (in cues and in
   the effects grid), a preview cell's :zone-group-id. Z4's rows then make
   them targets. The same state when nothing names them."
  [state smap]
  (let [cue (fn [c]
              (if (map? c)
                (-> c
                    (update-same :destination-zone (fn [d] (update-same d :zone-group-id #(old-ref smap %))))
                    (update-same :items (fn [nodes] (mapv-same #(old-node % smap) nodes)))
                    (update-same :tracks (fn [nodes] (mapv-same #(old-node % smap) nodes))))
                c))
        cell (fn [c] (if (map? c) (update-same c :items (fn [fxs] (mapv-same #(old-effect % smap) fxs))) c))]
    (-> state
        (update-same :chains (fn [chains]
                               (-> chains
                                   (update-same :cue-chains #(update-vals-same % cue))
                                   (update-same :effect-chains #(update-vals-same % cell)))))
        (update-same :config (fn [config]
                               (update-same config :preview
                                            (fn [preview]
                                              (update-same preview :grid-cells
                                                           (fn [cells]
                                                             (if (vector? cells)
                                                               (mapv-same #(if (map? %) (update-same % :zone-group-id (fn [z] (old-ref smap z))) %) cells)
                                                               cells))))))))))

(def ^:private built-ins
  "The built-in groups that became automatic ones, and their names."
  [[:left :auto/left "Left"] [:right :auto/right "Right"] [:center :auto/center "Center"]])

(defn show-rows
  "state with the built-in :left, :right and :center converted: one no
   projector or virtual projector of the loaded hardware lists (on or off)
   goes, and every reference to it (routing.refs, and an old show's own
   keys, old-keys) names the automatic group instead; one that is listed
   stays, as \"Left (manual)\" while it is still called \"Left\". A built-in
   deleted before is left alone, and so is what names it (04 S-12). All's
   definition goes: All is built in (04 S-9)."
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
                          (old-keys {old auto})
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


;; Timeline shows leave the grid (plans/2026-11-25-timeline-shows/04 §4.3)

(def ^:private show-trigger-types
  "A moved cell's bindings, by type, and the type each becomes: a pad that
   played the cell toggles its show, as a press did."
  {:trigger-cue :toggle-show :start-timeline :start-timeline :stop-timeline :stop-timeline})

(defn- timeline-at?
  "Is the cue at cell a timeline that moves: a grid cell's, and
   timeline-cue? (explicit or inferred)? false, logged, for a cue
   timeline-cue? can't read: it stays where it is."
  [cell cue]
  (and (shows/grid-cell? cell)
       (try (cue-chains/timeline-cue? cue)
            (catch Exception e
              (log/warn "Timeline shows: left the cue at" (pr-str cell) "in the grid, it can't be read:" (ex-message e))
              false))))

(defn- follows-mtc? [show]
  (= :mtc (:timeline/chase show)))

(defn- cell-show
  "The show the timeline cue at cell [c r] becomes: :editor-type :timeline,
   no :id or :trigger-mode, its column's default target when it has none of
   its own, \"Timeline A3\" when it has no name, and the file's BPM as its
   tempo when it follows timecode without one."
  [state [c r] cue]
  (let [column-target (get-in state [:grid :column-targets c])
        bpm (get-in state [:timing :bpm])
        name (:name cue)]
    (cond-> (-> cue
                (assoc :editor-type :timeline)
                (dissoc :id :trigger-mode))
      (and (nil? (targets/normalize-target (:target cue)))
           (some? (targets/normalize-target column-target)))
      (assoc :target column-target)

      (not (and (string? name) (not (str/blank? name))))
      (assoc :name (str "Timeline " (u/cell-label c r)))

      (and (follows-mtc? cue) (nil? (:timeline/tempo-bpm cue)) (number? bpm) (pos? bpm))
      (assoc :timeline/tempo-bpm (double bpm)))))

(defn- fresh-id
  "An id from id-fn that names no show in taken, or nil after 100 tries."
  [id-fn taken]
  (loop [n 0]
    (when (< n 100)
      (let [id (id-fn)]
        (if (or (nil? id) (contains? taken id)) (recur (inc n)) id)))))

(defn- timeline-moves
  "[{:cell :id :show}] for the timeline cells of cues, in reading order
   ([row col]). A cell whose show can't be built stays, logged."
  [state cues library id-fn]
  (let [cells (->> cues
                   (keep (fn [[cell cue]] (when (timeline-at? cell cue) cell)))
                   (sort-by (fn [[c r]] [r c])))]
    (:moves
     (reduce (fn [{:keys [taken] :as acc} cell]
               (if-let [m (try
                            (let [show (cell-show state cell (get cues cell))]
                              (when-let [id (fresh-id id-fn taken)]
                                {:cell cell :id id :show show}))
                            (catch Exception e
                              (log/warn "Timeline shows: left the cue at" (pr-str cell) "in the grid, it can't be moved:" (ex-message e))
                              nil))]
                 (-> acc (update :moves conj m) (update :taken conj (:id m)))
                 acc))
             {:moves [] :taken (set (keys library))}
             cells))))

(defn- safe-range
  "shows/tc-range, nil for a show it can't read."
  [show]
  (try (shows/tc-range show) (catch Exception _ nil)))

(defn- check-timecode
  "moves with :timeline/chase gone from each follower that is empty or
   overlaps an enabled show before it: the library's own, in (str id)
   order, then the moved ones that kept it, in order. And :timecode-off,
   the names of those that lost it."
  [library moves]
  (let [enabled (->> library
                     (filter (fn [[_ show]] (and (map? show) (follows-mtc? show))))
                     (sort-by (comp str key))
                     (keep (comp safe-range val))
                     vec)]
    (reduce (fn [{:keys [enabled] :as acc} {:keys [show] :as m}]
              (if (follows-mtc? show)
                (let [r (safe-range show)]
                  (if (and r (not-any? #(shows/tc-overlap? r %) enabled))
                    (-> acc (update :moves conj m) (update :enabled conj r))
                    (-> acc
                        (update :moves conj (update m :show dissoc :timeline/chase))
                        (update :timecode-off conj (:name show)))))
                (update acc :moves conj m)))
            {:moves [] :enabled enabled :timecode-off []}
            moves)))

(defn- rehomed-count
  "How many bindings of trigger-map rehome-trigger-targets moves off cell."
  [trigger-map cell]
  (if (map? trigger-map)
    (count (filter (fn [v] (and (map? v) (= cell (:target v)) (contains? show-trigger-types (:type v))))
                   (vals trigger-map)))
    0))

(defn- move-cell
  "state with the move's show in the library, its cell gone from the grid,
   its bindings re-pointed at the show and the vibes letting go of the cell;
   the counts in :triggers-rewritten and :vibe-refs-removed of tally."
  [[state tally] {:keys [cell id show]}]
  (let [tm (get-in state [:config :input :trigger-map])
        rewritten (rehomed-count tm cell)
        vibe-refs (vibe-refs/cue-ref-count state cell)]
    [(cond-> (-> state
                 (assoc-in [:chains :shows id] show)
                 (update-in [:chains :cue-chains] dissoc cell))
       (pos? rewritten)
       (assoc-in [:config :input :trigger-map]
                 (vibe-refs/rehome-trigger-targets tm cell (shows/play-key id) show-trigger-types))

       (pos? vibe-refs)
       (vibe-refs/remove-cue cell))
     (-> tally
         (update :triggers-rewritten + rewritten)
         (update :vibe-refs-removed + vibe-refs))]))

(defn timeline-shows
  "state with every timeline cue of the grid moved into the show library
   (04 §4.3), the cells in reading order, each under a new id (id-fn,
   random-uuid by default):
   - the show keeps the cue, as :editor-type :timeline, with no :id or
     :trigger-mode, its column's default target when it has no :target, a
     name (\"Timeline A3\") when it has none, and the file's BPM ([:timing
     :bpm], read only) as its tempo when it follows timecode without one;
   - a follower that is empty, or overlaps an enabled show (the library's
     first, then the moved ones before it), stops following;
   - its :trigger-cue bindings toggle the show, its :start-timeline and
     :stop-timeline ones name it ([:show id]), :behavior kept; no vibe
     names its cell;
   - [:ui :shows :migrated] says what moved.
   state itself (identical?) when no cue is a timeline, so it is safe to
   run twice. It never throws: Save runs it in its dry Open. A cue it can't
   read stays where it is, and is logged."
  ([state] (timeline-shows state nil))
  ([state {:keys [id-fn] :or {id-fn random-uuid}}]
   (try
     (let [cues (get-in state [:chains :cue-chains])
           library (get-in state [:chains :shows])]
       (if-not (and (map? cues) (or (nil? library) (map? library)))
         state
         (let [found (timeline-moves state cues library id-fn)]
           (if (empty? found)
             state
             (let [{:keys [moves timecode-off]} (check-timecode library found)
                   [state' tally] (reduce move-cell
                                          [state {:triggers-rewritten 0 :vibe-refs-removed 0}]
                                          moves)]
               (assoc-in state' [:ui :shows :migrated]
                         {:moved (count moves)
                          :timecode-off timecode-off
                          :vibe-refs-removed (:vibe-refs-removed tally)
                          :triggers-rewritten (:triggers-rewritten tally)}))))))
     (catch Exception e
       (log/error e "Timeline shows: the migration failed; the grid is left as it was")
       state))))
