(ns laser-show.state.rig-file
  "The rig as a document of its own (D-30, plans/2026-11-19-zones/09): the
   projectors, their order, safety zones, calibration and group membership
   belong to the machine and the venue, the show is the art. Pure: the file
   itself is state.rig-store's.

   The rig file holds the rig paths (rig-paths) and a copy of the groups the
   rig's lasers are in (group-copy), so New Project can bring them along and
   a show that doesn't define them can be told so."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [laser-show.animation.safety :as safety]
            [laser-show.routing.refs :as refs]
            [laser-show.routing.rig :as rig]
            [laser-show.state.domains :as domains]
            [laser-show.state.migration :as migration]))


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
   ([:rig-file :group-copy]), or the fallback definitions given, with the
   show's current definitions over it, kept to the groups some projector or
   virtual projector lists. Membership is the rig's and definitions are the
   show's (spec 01 §12): the copy lets New Project bring the groups back and
   the report name them. An undo step's fallback is the definitions its
   snapshot kept (state.history/restore-snapshot)."
  ([state] (group-copy state (get-in state [:rig-file :group-copy])))
  ([state fallback]
   (select-keys (merge fallback (:zone-groups state)) (listed-groups state))))

(defn with-group-copy
  "state with its copy ([:rig-file :group-copy]) brought to group-copy of
   it, from fallback (the copy itself when not given): the definitions
   this window remembers, written or not. The copy keeps its map when it is
   equal."
  ([state] (with-group-copy state (get-in state [:rig-file :group-copy])))
  ([state fallback]
   (let [copy (group-copy state fallback)]
     (if (= copy (get-in state [:rig-file :group-copy] {}))
       state
       (assoc-in state [:rig-file :group-copy] copy)))))

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
   lasers are in (the group copy, when the show's groups or the copy itself
   changed)?"
  [old new]
  (boolean
   (or (some #(differs? (get-in old %) (get-in new %)) rig-paths)
       (and (or (not (identical? (:zone-groups old) (:zone-groups new)))
                (not (identical? (get-in old [:rig-file :group-copy]) (get-in new [:rig-file :group-copy]))))
            (not= (group-copy old) (group-copy new))))))

;; Matching a show's lasers onto this rig

(defn- free-id
  "A free id for a show's laser p (id) whose id is taken here (taken): for
   IDN, rig/unique-projector-id from its host and output; for another
   driver (it has no host), its own id with -2, -3 … (output drivers D2), so
   the result doesn't depend on when it is worked out."
  [taken id p]
  (if (= :idn (:driver p :idn))
    (rig/unique-projector-id taken (:host p) (:service-id p))
    (->> (map #(keyword (str (name id) "-" %)) (iterate inc 2))
         (remove #(contains? taken %))
         first)))

(defn pin-map
  "{show-id rig-id} for each projector in a show's hardware (its
   hardware.edn): the id of the projector here with the same stable id (a
   show from before Z2 is known by its :unit-id, as a scan knows it,
   rig/settled-ids). One whose unit isn't here keeps its id while no
   projector here has it, else gets a free one (free-id), so its pins reach
   nothing here rather than another laser."
  [state hardware]
  (let [show (or (:projectors hardware) {})
        here (or (:projectors state) {})
        here-by-sid (into {} (map (fn [[id sid]] [sid id])) (rig/settled-ids here))
        matched (into {} (keep (fn [[id sid]] (when-let [r (get here-by-sid sid)] [id r]))) (rig/settled-ids show))]
    (reduce (fn [m [id p]]
              (cond
                (contains? m id) m
                (not (contains? here id)) (assoc m id id)
                :else (assoc m id (free-id (into (set (keys here)) (concat (keys show) (vals m))) id p))))
            matched
            (sort-by (comp str key) show))))

(defn rename-pins
  "state with every pin to one of a show's lasers (:auto.laser/<show id>)
   naming that laser's id here (pin-map), wherever it is stored
   (routing.refs/replace-refs: the targets of cues, items, tracks and clips,
   the preview's cells, the defaults, the zone maps, and the places,
   sources and steps of the zone patterns in cues and effect cells, their
   keyframes' too; an old show's Zone Selectors were converted on load), so
   a show's pins follow the units, not the addresses (S-ZR-5). hardware is
   the show's hardware.edn. state comes back identical? when nothing is
   renamed."
  [state hardware]
  (refs/replace-refs state (into {}
                                 (keep (fn [[s r]] (when (not= s r) [(rig/laser-ref s) (rig/laser-ref r)])))
                                 (pin-map state hardware))))

(defn- rename-keys-in
  "m with each key in renames ({old new}) at its new key; m when it isn't a
   map."
  [m renames]
  (if (map? m)
    (reduce-kv (fn [m old new] (if (contains? m old) (-> m (dissoc old) (assoc new (get m old))) m)) m renames)
    m))

(defn- renamed-projector
  "p with a stable id that names its old id ([:manual driver id], output
   drivers 01 §5.3) naming the new one."
  [p renames]
  (let [[kind driver id :as sid] (:stable-id p)]
    (if (and (= :manual kind) (contains? renames id))
      (assoc p :stable-id [kind driver (get renames id)])
      p)))

(defn rename-projectors
  "state (the app state, or a document as undo keeps it) with each projector
   in renames ({old-id new-id}) at its new id: its settings, calibration,
   place in the order and virtual projectors, and every pin to it
   (:auto.laser/<id>), wherever routing.refs/replace-refs finds one, as
   rename-pins re-points a show's. A rename whose old id isn't there, or
   whose new id is taken, is left out. Nothing else changes: state comes
   back identical? with nothing to rename."
  [state renames]
  (let [projectors (:projectors state)
        renames (into {} (filter (fn [[old new]] (and (contains? projectors old) (not (contains? projectors new))))) renames)]
    (if (empty? renames)
      state
      (-> state
          (assoc :projectors (update-vals (rename-keys-in projectors renames) #(renamed-projector % renames)))
          (cond-> (map? (get-in state [:chains :projector-effects]))
            (update-in [:chains :projector-effects] rename-keys-in renames))
          (cond-> (sequential? (get-in state [:rig :order]))
            (update-in [:rig :order] #(mapv (fn [id] (get renames id id)) %)))
          (cond-> (map? (:virtual-projectors state))
            (update :virtual-projectors update-vals
                    (fn [vp] (cond-> vp (contains? renames (:parent-projector-id vp)) (update :parent-projector-id renames)))))
          (refs/replace-refs (into {} (map (fn [[old new]] [(rig/laser-ref old) (rig/laser-ref new)])) renames))))))

;; Setting the rig up from a show (09 §7)

(defn set-up?
  "Is the rig set up: is some laser turned on (rig/enabled?)?"
  [state]
  (boolean (some rig/enabled? (vals (:projectors state)))))

(defn- show-lasers
  "The ids of the lasers a show was made with (the projectors on in its
   hardware), in its order."
  [hardware]
  (let [projectors (or (:projectors hardware) {})]
    (filterv #(rig/enabled? (get projectors %))
             (rig/effective-order (get-in hardware [:rig :order]) projectors))))

(defn- readable-zone
  "A show's safety zone through safety/normalize, or ::unreadable."
  [zone]
  (try (safety/normalize zone)
       (catch Exception _ ::unreadable)))

(defn- incoming-safety
  "A show laser's zone as it comes onto this rig (S-ZR-8): what
   safety/normalize gives, so it never grows on the way in; one it can't
   read as it is, so the stage fails closed and the laser stays dark until
   its zone is set. A laser with no zone keeps none."
  [p]
  (if (contains? p :safety)
    (let [zone (readable-zone (:safety p))]
      (if (= ::unreadable zone) p (assoc p :safety zone)))
    p))

(def ^:private identity-keys
  "What a laser already on this rig keeps when a show's setup comes onto it:
   where and what it is here, its output driver and that driver's settings
   among them (output drivers D2: connection data, like :host and :port)."
  [:host :port :service-id :service-name :unit-id :stable-id :driver :driver-config])

(defn- matched-by-ip
  "The show's lasers (ids) matched to one here by a [:host …] identity only."
  [state hardware]
  (let [here (set (vals (rig/settled-ids (:projectors state))))
        sids (rig/settled-ids (:projectors hardware))]
    (filterv #(let [[kind :as sid] (get sids %)] (and (= :host kind) (contains? here sid)))
             (show-lasers hardware))))

(defn- unreadable-zones
  "The show's lasers (ids) whose safety zone safety/normalize can't read."
  [hardware]
  (filterv #(let [p (get-in hardware [:projectors %])]
              (and (contains? p :safety) (= ::unreadable (readable-zone (:safety p)))))
           (show-lasers hardware)))

(defn set-up-question
  "What the set-up question says of a show's hardware on this rig:
   {:lasers [name …] (in the show's order) :matched-by-ip [{:name :host}]
   :unreadable [name …]}, or nil when the show was made with no laser on."
  [state hardware]
  (when-let [ids (seq (show-lasers hardware))]
    (let [name-of #(rig/projector-name hardware %)]
      {:lasers (mapv name-of ids)
       :matched-by-ip (mapv (fn [id] {:name (name-of id) :host (get-in hardware [:projectors id :host])})
                            (matched-by-ip state hardware))
       :unreadable (mapv name-of (unreadable-zones hardware))})))

(defn- renamed-since
  "{old-pin new-pin} for the show's lasers whose id here (pins, pin-map's
   now) isn't the one their pins got when the show was opened (pins-then),
   as when a scan found one of them in between."
  [pins-then pins]
  (into {}
        (keep (fn [[s r]]
                (let [then (get pins-then s r)]
                  (when (not= then r) [(rig/laser-ref then) (rig/laser-ref r)]))))
        pins))

(defn- bring-lasers
  "state with the lasers ids of a show's hardware (converted) and chains on
   this rig, each at its id here (pins): one already here keeps its
   identity-keys (one it doesn't have, such as an IDN laser's :driver, it
   doesn't take from the show either) and takes everything else from the
   show; one that isn't is added. Zones come in as incoming-safety gives them; calibration and the
   virtual projectors on these lasers come too."
  [state hardware chains ids pins]
  (let [brought (set ids)]
    (as-> state s
      (reduce (fn [s id]
                (let [r (get pins id id)
                      show-p (incoming-safety (get-in hardware [:projectors id]))
                      here (get-in s [:projectors r])]
                  (cond-> (assoc-in s [:projectors r] (if here
                                                        (merge (apply dissoc show-p identity-keys) (select-keys here identity-keys))
                                                        show-p))
                    (get chains id) (assoc-in [:chains :projector-effects r] (get chains id)))))
              s
              ids)
      (reduce-kv (fn [s vp-id vp]
                   (if (contains? brought (:parent-projector-id vp))
                     (assoc-in s [:virtual-projectors vp-id] (update vp :parent-projector-id #(get pins % %)))
                     s))
                 s
                 (or (:virtual-projectors hardware) {})))))

(defn set-up-from-show
  "state with a show's rig as this rig (the set-up question's Set up rig,
   09 §7): the lasers the show was made with (its hardware converted first
   when it is from before Z2) brought on (bring-lasers), at the ids pin-map
   gives now, and the show's order, middle laser and split width. A pin
   still naming the id a laser had here when the show was opened (pins-then)
   follows it to its id now. chains is the show's [:chains :projector-effects]."
  [state hardware chains pins-then]
  (let [hardware (migration/zones-z2-rig hardware)
        pins (pin-map state hardware)
        ids (show-lasers hardware)
        rig-of-show (:rig hardware)]
    (-> state
        (bring-lasers hardware chains ids pins)
        (assoc-in [:rig :order] (mapv #(get pins % %) ids))
        (update :rig merge (select-keys rig-of-show [:middle :split-width]))
        (refs/replace-refs (renamed-since pins-then pins)))))

;; The report (09 §7, §8)

(defn- name-key
  "A group's name to compare by: no case, no spaces around it; nil when blank."
  [n]
  (let [k (str/lower-case (str/trim (str n)))]
    (when-not (str/blank? k) k)))

(defn- listing
  "The rig's projectors (ids) that list group."
  [state group]
  (into [] (comp (filter #(some #{group} (rig/custom-refs (val %)))) (map key)) (:projectors state)))

(defn- ref-owners
  "Who stores each ref in the show: its cues, effect cells and shows
   (routing.refs/chain-ref-usage)."
  [state]
  (refs/chain-ref-usage (get-in state [:chains :cue-chains]) (get-in state [:chains :effect-chains])
                        (get-in state [:chains :shows])))

(defn- owner-counts
  "A report row's counts of the owners of ref in usage (ref-owners): how
   many cues, 0 too, and how many effect cells and shows, each only when
   there are some."
  [usage ref]
  (let [{:keys [cues effect-cells shows]} (get usage ref)]
    (cond-> {:cues (count cues)}
      (seq effect-cells) (assoc :effect-cells (count effect-cells))
      (seq shows) (assoc :shows (count shows)))))

(defn- by-name [k]
  (fn [row] [(or (name-key (:name row)) "") (str (get row k))]))

(defn- groups-without-lasers
  "The show's groups its cues, effect cells or shows name (usage, ref-owners) that
   no projector or virtual projector here is in, with this rig's group of
   the same name, if there is one."
  [state usage]
  (let [listed (listed-groups state)
        rig-groups (sort-by (comp str key) (group-copy state))]
    (->> (:zone-groups state)
         (remove (comp rig/derived-ref? key))
         (filter (fn [[id _]] (and (contains? usage id) (not (contains? listed id)))))
         (map (fn [[id g]]
                (let [k (name-key (:name g))
                      same (when k (some (fn [[rid rg]] (when (and (not= rid id) (= k (name-key (:name rg)))) [rid rg])) rig-groups))]
                  (cond-> (merge {:group-id id :name (:name g)} (owner-counts usage id))
                    same (assoc :same-name {:group-id (first same) :name (:name (second same))
                                            :lasers (count (listing state (first same)))})))))
         (sort-by (by-name :group-id))
         vec)))

(defn- missing-pins
  "The lasers the show's cues, effect cells or shows are pinned to (usage,
   ref-owners) that aren't on this rig, named as in the show (hardware),
   with how many of each."
  [state hardware usage]
  (let [show-id (into {} (map (fn [[s r]] [r s])) (pin-map state hardware))]
    (->> (keys usage)
         (keep (fn [ref]
                 (when-let [id (rig/laser-of ref)]
                   (when-not (contains? (:projectors state) id)
                     (merge {:ref ref :name (rig/projector-name hardware (get show-id id id))} (owner-counts usage ref))))))
         (sort-by (by-name :ref))
         vec)))

(defn- strip-ids
  "Calibration items as compared: without their ids and collapsed flags."
  [items]
  (walk/postwalk #(if (map? %) (dissoc % :id :collapsed?) %) (vec items)))

(defn- setup-differs
  "The lasers here whose safety zone or calibration the show's differs
   from: {:projector-id :name :what #{:safety :calibration}}, and
   :unreadable? when the show's zone can't be read."
  [state hardware chains]
  (let [pins (pin-map state hardware)]
    (into []
          (keep (fn [s]
                  (let [r (get pins s)]
                    (when (contains? (:projectors state) r)
                      (let [show-zone (readable-zone (get-in hardware [:projectors s :safety]))
                            what (cond-> #{}
                                   (not= show-zone (readable-zone (get-in state [:projectors r :safety]))) (conj :safety)
                                   (not= (strip-ids (get-in chains [s :items]))
                                         (strip-ids (get-in state [:chains :projector-effects r :items])))
                                   (conj :calibration))]
                        (when (seq what)
                          (cond-> {:projector-id r :name (rig/projector-name state r) :what what}
                            (= ::unreadable show-zone) (assoc :unreadable? true))))))))
          (show-lasers hardware))))

(defn match-report
  "What doesn't fit when a show (its hardware.edn and calibration chains) is
   open on this rig (09 §8): {:file :show-lasers :rig-lasers (the lasers
   on) :groups-without-lasers [{:group-id :name :cues :effect-cells :shows
   :same-name {:group-id :name :lasers}}]
   :missing-pins [{:ref :name :cues :effect-cells :shows}] :rig-groups-not-in-show
   [{:group-id :name :lasers}] :setup-differs [{:projector-id :name :what
   :unreadable?}] :matched-by-ip [{:name :host}] :new-lasers [show id …]}.
   The first two are its ⚠ rows (warning-count), one per ref, counting the
   cues, the effect cells and the shows that store it (:effect-cells and
   :shows only when there are some); state's [:chains :effect-chains] need
   only the cells' :items."
  [state hardware chains]
  (let [pins (pin-map state hardware)
        usage (ref-owners state)
        without (groups-without-lasers state usage)
        offered (into #{} (keep #(get-in % [:same-name :group-id])) without)
        file (get-in state [:project :current-file])]
    {:file (when file (last (str/split (str file) #"[/\\]")))
     :show-lasers (count (show-lasers hardware))
     :rig-lasers (count (filter rig/enabled? (vals (:projectors state))))
     :groups-without-lasers without
     :missing-pins (missing-pins state hardware usage)
     :rig-groups-not-in-show (->> (group-copy state)
                                  (remove (fn [[id _]] (or (contains? (:zone-groups state) id) (contains? offered id))))
                                  (map (fn [[id g]] {:group-id id :name (:name g) :lasers (count (listing state id))}))
                                  (sort-by (by-name :group-id))
                                  vec)
     :setup-differs (setup-differs state hardware chains)
     :matched-by-ip (mapv (fn [id] {:name (rig/projector-name hardware id) :host (get-in hardware [:projectors id :host])})
                          (matched-by-ip state hardware))
     :new-lasers (filterv #(not (contains? (:projectors state) (get pins %))) (show-lasers hardware))}))

(defn warning-count
  "How many ⚠ rows a report has: a group with no laser here, a pinned laser
   that isn't here. The report opens after an Open only with one."
  [report]
  (+ (count (:groups-without-lasers report)) (count (:missing-pins report))))


;; The report's fixes, each a pure state → state for one button

(defn import-new-lasers
  "state with the show's lasers that aren't on this rig brought on
   (bring-lasers: their setup, calibration and virtual projectors; zones as
   for the set-up), at the ids their pins have. Never changes a laser
   already here, nor the order: the new ones follow, by IP."
  [state hardware chains pins-then]
  (let [hardware (migration/zones-z2-rig hardware)
        pins (pin-map state hardware)
        ids (filterv #(not (contains? (:projectors state) (get pins %))) (show-lasers hardware))]
    (-> state
        (bring-lasers hardware chains ids pins)
        (refs/replace-refs (renamed-since pins-then pins)))))

(defn use-show-setup
  "state with the show's safety zone (through safety/normalize) and
   calibration for projector-id, a laser here (Use the show's…): nothing
   else of it changes. nil when the show has no laser with its stable id,
   or the show's zone can't be read (refused, as every safety edit refuses
   a zone it can't read). A show's laser with no zone or calibration gives
   it none."
  [state hardware chains projector-id]
  (when-let [s (and (contains? (:projectors state) projector-id)
                    (some (fn [[s r]] (when (= r projector-id) s)) (pin-map state hardware)))]
    (let [p (get-in hardware [:projectors s])
          zone (when (contains? p :safety) (readable-zone (:safety p)))
          chain (get chains s)]
      (when-not (= ::unreadable zone)
        (cond-> state
          zone (assoc-in [:projectors projector-id :safety] zone)
          (nil? zone) (update-in [:projectors projector-id] dissoc :safety)
          chain (assoc-in [:chains :projector-effects projector-id] chain)
          (nil? chain) (update-in [:chains :projector-effects] dissoc projector-id))))))

(defn adopt-group
  "state with the show's group group-id on the lasers in this rig's group
   from (Use this rig's \"Graphics\"): each projector and virtual projector
   that lists from lists group-id too."
  [state group-id from]
  (if (or (nil? group-id) (nil? from) (rig/derived-ref? group-id) (rig/derived-ref? from))
    state
    (let [adopt (fn [outputs]
                  (reduce-kv (fn [m id o]
                               (if (and (some #{from} (:zone-groups o)) (not (some #{group-id} (:zone-groups o))))
                                 (assoc m id (update o :zone-groups #(conj (vec %) group-id)))
                                 m))
                             outputs
                             outputs))]
      (cond-> (update state :projectors adopt)
        (contains? state :virtual-projectors) (update :virtual-projectors adopt)))))

(defn add-groups-to-show
  "state with this rig's groups group-ids (their copy's definitions) in the
   show (Add them to this show)."
  [state group-ids]
  (let [copy (group-copy state)]
    (reduce (fn [s id]
              (if (and (contains? copy id) (not (contains? (:zone-groups s) id)))
                (assoc-in s [:zone-groups id] (get copy id))
                s))
            state
            group-ids)))

(defn repin
  "state with every reference to from, a laser that isn't here, pinned to
   to instead: one of this rig's lasers (Pin them to…), or :all (Send them
   to All). Each stored site: a cue's or an item's target, a zone pattern's
   places, source and steps, in cues and effect cells, keyframes too
   (routing.refs/replace-refs)."
  [state from to]
  (if (and (rig/laser-of from)
           (not= from to)
           (or (= :all to) (and (rig/laser-of to) (rig/known-ref? state to))))
    (refs/replace-refs state {from to})
    state))

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
