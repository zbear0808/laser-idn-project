(ns laser-show.routing.targets
  "Targets (zones plan Z4, plans/2026-11-19-zones/06 §4.1, spec 01 §1, §2):
   where content goes, and where that came from. Pure, and it requires
   nothing, so the rig, the zone maps, the timeline and the vibes can all
   read a target.

   A target is nil (inherit), one ref, or several refs (their union), in
   normal form (normalize-target): nil, a keyword, or a vector of 2 or more
   distinct keywords sorted by namespace and name. Frame keys are targets in
   normal form. A zone is :zone/a, :zone/b … :zone/z, :zone/aa …: a place a
   cue draws into, which the zone map in use sends to one of its lanes
   (zone-lane, routing.zone-maps).

   Inheriting (spec §2, resolve-item): an item's own :target, else its
   track's (walking up its folders, track-targets), else its parent group's
   resolution, else the cue's fallback (cue-fallback): the cue's :target,
   its grid column's default, the show's, All. Each resolution says where it
   came from, {:target t :from level :via id}, for the editors' \"from
   column 3\"."
  (:require [clojure.string :as str]))


;; The normal form (spec 01 §1)

(defn normalize-target
  "A target in normal form: nil stays nil and a keyword stays itself; a
   collection becomes its distinct keywords sorted by namespace and name (the
   refs with no namespace first): one keyword when one is left, nil when none
   is. Frame keys are targets in normal form, so two items with the same refs
   in another order share a key."
  [t]
  (if (or (sequential? t) (set? t))
    (let [refs (sort-by (juxt namespace name) (into [] (comp (filter keyword?) (distinct)) t))]
      (case (count refs)
        0 nil
        1 (first refs)
        (vec refs)))
    t))

(defn key-refs
  "The refs a frame key names: [k] for a keyword, k itself for a vector."
  [k]
  (cond
    (vector? k) k
    (nil? k) []
    :else [k]))

(defn target-refs
  "The refs target t names, in normal form's order: [] for nil."
  [t]
  (key-refs (normalize-target t)))


;; Zones

(defn zone-index
  "A zone ref's index: :zone/a 0 … :zone/z 25, :zone/aa 26 …; nil for any
   other ref. It orders frame keys, and picks a zone's lane."
  [ref]
  (when (and (keyword? ref) (= "zone" (namespace ref)))
    (let [letters (name ref)]
      (when (re-matches #"[a-z]{1,12}" letters)
        (dec (reduce (fn [acc c] (+ (* 26 acc) (- (int c) 96))) 0 letters))))))

(defn zone-ref?
  "Is ref a zone?"
  [ref]
  (some? (zone-index ref)))

(defn zone-ref
  "The zone of index i (zone-index's inverse): 0 :zone/a, 25 :zone/z, 26
   :zone/aa."
  [i]
  (loop [n (inc (long i))
         letters ()]
    (if (pos? n)
      (let [n (dec n)]
        (recur (quot n 26) (cons (char (+ 97 (rem n 26))) letters)))
      (keyword "zone" (apply str letters)))))

(defn zone-letter
  "A zone's letters, as the UI shows them: \"A\", \"AA\"; nil for a ref that
   isn't a zone."
  [zone]
  (when (zone-ref? zone)
    (str/upper-case (name zone))))

(defn next-zone
  "The first zone, from A, that isn't in used (zones, or anything)."
  [used]
  (let [used (set used)]
    (first (remove used (map zone-ref (range))))))

(defn has-zone?
  "Does target t name a zone?"
  [t]
  (boolean (some zone-ref? (target-refs t))))

(defn toggle
  "t with ref added, or taken out when t names it, in normal form: :zone/a
   and :zone/b give [:zone/a :zone/b]; taking out the last ref gives nil."
  [t ref]
  (let [refs (target-refs t)]
    (normalize-target (if (some #{ref} refs) (remove #{ref} refs) (conj refs ref)))))

(defn zone-lane
  "Zone's lane in zone map m ({:lanes [ref …] :overrides {zone ref}}, spec
   §4): its override, else lane i mod n of the lanes, so zones wrap over
   them. nil when m has no lanes or that lane is empty, and nil for a ref
   that isn't a zone: either reaches nothing. A zone never maps to a zone,
   so a lane that is one counts as empty."
  [m zone]
  (when-let [i (zone-index zone)]
    (let [lanes (:lanes m)
          n (count lanes)
          lane (get (:overrides m) zone (when (pos? n) (nth lanes (mod i n))))]
      (when (and (keyword? lane) (not (zone-ref? lane)))
        lane))))

(defn pin
  "t with each zone replaced by its lane in zone map m, in normal form, so
   the content stops following the map (§7.1's Pin). A zone whose lane is
   empty goes; nil when nothing is left."
  [m t]
  (normalize-target (keep #(if (zone-ref? %) (zone-lane m %) %) (target-refs t))))


;; Inheriting (spec §2)

(def ^:private default-resolution
  {:target :all :from :default})

(defn cue-fallback
  "Spec §2's levels 4-7, worked out once per cue render: {:target :from},
   the cue's own target (:from :cue), else the grid column's default
   (:column, with :via col when it is given), else the show's
   ([:config :default-target], :show), else :all (:default). Each in normal
   form; an empty one (nil, []) is unset."
  ([cue-target column-target show-target]
   (cue-fallback cue-target column-target show-target nil))
  ([cue-target column-target show-target col]
   (if-let [t (normalize-target cue-target)]
     {:target t :from :cue}
     (if-let [t (normalize-target column-target)]
       (cond-> {:target t :from :column} (some? col) (assoc :via col))
       (if-let [t (normalize-target show-target)]
         {:target t :from :show}
         default-resolution)))))

(defn resolve-item
  "Spec §2 for one item, group or clip: {:target t :from level :via id},
   its own target own (:from :item); else track's ({:target :via}, what
   track-targets gives its track: :from :track, :via the track or folder
   that set it); else parent, its parent group's resolution as a child sees
   it (inherited); else fallback (cue-fallback), All without one. :target is
   in normal form, never nil."
  [own track parent fallback]
  (if-let [t (normalize-target own)]
    {:target t :from :item}
    (if-let [t (normalize-target (:target track))]
      {:target t :from :track :via (:via track)}
      (or parent fallback default-resolution))))

(defn inherited
  "res, a group's resolution, as the group's children see it: the group's
   own target becomes {:from :group :via group-id}; one it inherited passes
   through, so a child says where it really came from (06 SQ-6)."
  [res group-id]
  (if (= :item (:from res))
    {:target (:target res) :from :group :via group-id}
    res))


;; Tracks

(defn- index-tracks
  "{:id->track :id->parent} of a tracks tree: every track and folder by id,
   and each one's folder: the one it is nested in (:items), or in old data
   its :parent-id or the last of its :path."
  [tracks]
  (letfn [(walk [index parent-id ts]
            (reduce (fn [index t]
                      (if-not (map? t)
                        index
                        (let [tid (:id t)
                              parent (or parent-id (:parent-id t) (last (:path t)))]
                          (cond-> index
                            (some? tid) (assoc-in [:id->track tid] t)
                            (and (some? tid) (some? parent)) (assoc-in [:id->parent tid] parent)
                            (seq (:items t)) (walk tid (:items t))))))
                    index
                    ts))]
    (walk {:id->track {} :id->parent {}} nil tracks)))

(defn- track-target
  "{:target :via} for track id: its own :target, else the nearest folder's
   above it; nil when none has one. A loop of folders stops."
  [{:keys [id->track id->parent]} id]
  (loop [curr id
         seen #{}]
    (when (and (some? curr) (not (contains? seen curr)))
      (when-let [t (get id->track curr)]
        (if-let [target (normalize-target (:target t))]
          {:target target :via curr}
          (recur (get id->parent curr) (conj seen curr)))))))

(defn- compute-track-targets [tracks]
  (let [index (index-tracks tracks)]
    (into {}
          (keep (fn [id] (when-let [r (track-target index id)] [id r])))
          (keys (:id->track index)))))

(def ^:private track-cache-size 8)

(defonce ^:private !track-cache
  ;; [[tracks result] …], newest first: the tracks trees seen last, by
  ;; identity. The show clock's thread and the UI's share it; a race only
  ;; works one out again.
  (atom []))

(defn track-targets
  "{track-id {:target :via}} for every track or folder whose own :target, or
   a folder's above it, is set; :via is the one that set it. A track's
   folders are the ones it is nested in, or in old data its :parent-id or
   the last of its :path. Worked out once per cue render (it replaces the
   walk every item made), and kept while tracks is identical?."
  [tracks]
  (if (empty? tracks)
    {}
    (if-let [[_ result] (some #(when (identical? tracks (first %)) %) @!track-cache)]
      result
      (let [result (compute-track-targets tracks)]
        (swap! !track-cache #(into [[tracks result]] (take (dec track-cache-size)) %))
        result))))


;; What every level of a cue resolves to

(defn- track-names
  "{id {:name :folder?}} of every track and folder of tracks."
  [tracks]
  (into {}
        (keep (fn [t] (when (and (map? t) (some? (:id t)))
                        [(:id t) {:name (:name t)
                                  :folder? (boolean (or (= :group (:type t)) (= :folder (:type t)) (contains? t :items)))}])))
        (tree-seq #(seq (:items %)) :items {:items tracks})))

(defn- named
  "res with :via-name (and :via-folder? for a track), the name of the group,
   track or folder in :via."
  [res group-names tracks-by-id]
  (case (:from res)
    :group (assoc res :via-name (get group-names (:via res)))
    :track (let [{:keys [name folder?]} (get tracks-by-id (:via res))]
             (assoc res :via-name name :via-folder? folder?))
    res))

(defn explain-cue
  "How each level of cue resolves, for the editors: {:cue (cue-fallback of
   the cue's own target, its column's col-target and the show's show-target)
   :items {id res} (every item, group and clip, at any depth: resolve-item)
   :tracks {id res} (a track's or folder's own target, :from :item; else its
   folder's, :from :track :via it; else the cue's) :item-inherits and
   :track-inherits {id res} (what each would inherit were its own target
   cleared: what its chip's Inherit says)}. A res from a group, track or
   folder also carries :via-name, and :via-folder? for a track."
  ([cue col-target show-target]
   (explain-cue cue col-target show-target nil))
  ([cue col-target show-target col]
   (let [fallback (cue-fallback (:target cue) col-target show-target col)
         tracks (:tracks cue)
         by-track (track-targets tracks)
         tracks-by-id (track-names tracks)
         walk (fn walk [acc parent nodes]
                (reduce (fn [acc node]
                          (if-not (map? node)
                            acc
                            (let [track (get by-track (:track-id node))
                                  res (resolve-item (:target node) track parent fallback)
                                  acc (cond-> acc
                                        (some? (:id node)) (-> (assoc-in [:items (:id node)] res)
                                                               (assoc-in [:inherits (:id node)] (resolve-item nil track parent fallback))
                                                               (assoc-in [:names (:id node)] (:name node))))]
                              (if (= :group (:type node))
                                (walk acc (inherited res (:id node)) (:items node))
                                acc))))
                        acc
                        nodes))
         {:keys [items inherits] group-names :names} (walk {:items {} :inherits {} :names {}} nil (:items cue))
         index (index-tracks tracks)
         track-res (fn [id r]
                     (cond
                       (and r (= id (:via r))) {:target (:target r) :from :item}
                       r (named {:target (:target r) :from :track :via (:via r)} group-names tracks-by-id)
                       :else fallback))]
     {:cue fallback
      :items (update-vals items #(named % group-names tracks-by-id))
      :item-inherits (update-vals inherits #(named % group-names tracks-by-id))
      :tracks (into {} (map (fn [id] [id (track-res id (get by-track id))])) (keys tracks-by-id))
      ;; A track without its own target takes the nearest folder's above it
      :track-inherits (into {}
                            (map (fn [id] [id (track-res id (track-target index (get-in index [:id->parent id])))]))
                            (keys tracks-by-id))})))

(defn cue-refs
  "Every ref cue's content can draw to: the resolution (spec §2) of each of
   its presets, at any depth, on or off and on muted tracks too, its column's
   default col-target and the show's show-target included. What \"used by\"
   and the audience rule count. A cue with no preset draws nowhere: #{}.
   (routing.refs/cue-refs is the refs a cue's own data names.)"
  [cue col-target show-target]
  (let [fallback (cue-fallback (:target cue) col-target show-target)
        by-track (track-targets (:tracks cue))]
    (letfn [(refs [parent nodes]
              (mapcat (fn [node]
                        (when (map? node)
                          (let [res (resolve-item (:target node) (get by-track (:track-id node)) parent fallback)]
                            (if (= :group (:type node))
                              (refs (inherited res (:id node)) (:items node))
                              (key-refs (:target res))))))
                      nodes))]
      (set (refs nil (:items cue))))))


;; Names (§7)

(defn names
  "What the text functions read: {:groups {ref group} (Z2's target-groups,
   by id: :name, :kind) :order [ref …] (target-groups' order) :labels {zone
   text} ([:zones :labels]) :places {laser-ref n} (each laser's place, from
   1, in target-groups' rig order)}."
  [target-groups labels]
  {:groups (into {} (map (juxt :id identity)) target-groups)
   :order (mapv :id target-groups)
   :labels (or labels {})
   :places (into {} (map-indexed (fn [i g] [(:id g) (inc i)])) (filter #(= :laser (:kind %)) target-groups))})

(defn- capitalized [s]
  (if (str/blank? s) s (str (str/upper-case (subs s 0 1)) (subs s 1))))

(defn ref-name
  "What ref is called: a zone by its letters and label (\"A\", \"A ·
   Beams\"), All, a group or laser by its name (\"Left\", \"Laser 2\"), one
   the show no longer has as \"Front (missing)\"."
  [names ref]
  (cond
    (zone-ref? ref) (let [letters (zone-letter ref)
                          label (get-in names [:labels ref])]
                      (if (str/blank? label) letters (str letters " · " (str/trim label))))
    (contains? (:groups names) ref) (get-in names [:groups ref :name])
    (= :all ref) "All"
    (keyword? ref) (str (capitalized (name ref)) " (missing)")
    :else (str ref)))

(defn lane-text
  "Where zone goes under zone-map, by name: its lane's, or \"nothing ⚠\"."
  [names zone-map zone]
  (if-let [lane (zone-lane zone-map zone)]
    (ref-name names lane)
    "nothing ⚠"))

(defn- text-order
  "The order a target's refs are said in: its zones by index, then the
   rest, as routing.compose orders frame keys."
  [refs]
  (sort-by (fn [r] (if-let [i (zone-index r)] [0 i ""] [1 0 (str r)])) refs))

(defn target-text
  "What target t says (§7.1): its refs by name (a zone of a union by its
   letters only), zones first, joined by \" + \"; when one is a zone, then
   \" → \" and where they go under zone-map (a zone's lane, \"nothing ⚠\" for
   an empty one; any other ref itself), each once: \"A · Beams → Left\",
   \"A + B → Left + Right\", \"Graphics\". nil for nil."
  [names zone-map t]
  (let [refs (text-order (target-refs t))]
    (when (seq refs)
      (let [union? (next refs)
            left (str/join " + " (map #(if (and union? (zone-ref? %)) (zone-letter %) (ref-name names %)) refs))]
        (if (some zone-ref? refs)
          (str left " → " (str/join " + " (distinct (map #(if (zone-ref? %) (lane-text names zone-map %) (ref-name names %)) refs))))
          left)))))

(defn from-text
  "Where a resolution came from, as the chips say it: \"from column 3\",
   \"from the cue\", \"from the show default\", \"from group Pair\", \"from
   folder Beat\", \"from track Kick\"; nil for a level's own target and for
   All with nothing set."
  [{:keys [from via via-name via-folder?]}]
  (case from
    :column (if (some? via) (str "from column " (inc (long via))) "from the column")
    :cue "from the cue"
    :show "from the show default"
    :group (str "from group " (or via-name "…"))
    :track (str "from " (if via-folder? "folder " "track ") (or via-name "…"))
    nil))


;; Grid badges (§8.5)

(def ^:private auto-short
  {:auto/left "L" :auto/right "R" :auto/center "Ctr" :auto/odd "Odd" :auto/even "Even"})

(defn short-name
  "A ref as a badge says it: Left \"L\", Right \"R\", Center \"Ctr\", \"Odd\",
   \"Even\", a laser by its place (\"#2\"), a zone by its letters, All
   \"All\", any other group by its name cut to 5 letters (\"Graph…\")."
  [names ref]
  (cond
    (zone-ref? ref) (zone-letter ref)
    (contains? auto-short ref) (auto-short ref)
    (= :all ref) "All"
    (contains? (:places names) ref) (str "#" (get-in names [:places ref]))
    :else (let [n (or (get-in names [:groups ref :name]) (when (keyword? ref) (name ref)) (str ref))]
            (if (> (count n) 5) (str (subs n 0 5) "…") n))))

(defn- zone-runs
  "Letters for sorted zone indices: three or more in a row as \"A–C\", the
   rest one by one."
  [indices]
  (->> indices
       (reduce (fn [runs i]
                 (let [run (peek runs)]
                   (if (and run (= i (inc (peek run))))
                     (conj (pop runs) (conj run i))
                     (conj runs [i]))))
               [])
       (mapcat (fn [run]
                 (if (<= 3 (count run))
                   [(str (zone-letter (zone-ref (first run))) "–" (zone-letter (zone-ref (peek run))))]
                   (map #(zone-letter (zone-ref %)) run))))))

(defn- grouped
  "targets (in normal form, without :all and nil) as [zone-singles (sorted
   indices) zone-unions (vectors naming a zone) pinned (the rest)], each
   sorted."
  [targets]
  (let [ts (sort-by (juxt (comp boolean vector?) str) (disj (set (map normalize-target targets)) nil :all))]
    [(sort (keep zone-index (remove vector? ts)))
     (filterv #(and (vector? %) (some zone-ref? %)) ts)
     (filterv #(not (or (zone-ref? %) (and (vector? %) (some zone-ref? %)))) ts)]))

(defn badge
  "A grid cell's badge (§8.5) for the targets its cue's enabled content
   resolves to: {:zones :pins}, each a text or nil, or nil when everything
   goes to All. Zones by letter (\"A B\"; three or more in a row \"A–D\"),
   a union \"A+B\"; the targets naming no zone are pinned, by short name,
   joined by \"/\" (\"L/R\")."
  [names targets]
  (let [[singles unions pinned] (grouped targets)
        zones (concat (zone-runs singles)
                      (map #(str/join "+" (map (fn [r] (short-name names r)) (text-order %))) unions))
        pins (map #(str/join "+" (map (fn [r] (short-name names r)) (key-refs %))) pinned)]
    (when (or (seq zones) (seq pins))
      {:zones (when (seq zones) (str/join " " zones))
       :pins (when (seq pins) (str/join "/" pins))})))

(defn badge-text
  "A badge as plain text: \"A B\", \"A pinned L/R\", \"pinned Graph…\"."
  [{:keys [zones pins]}]
  (str/join " " (remove nil? [zones (when pins (str "pinned " pins))])))

(defn badge-tooltip
  "The badge's line in its cell's tooltip (§8.5): each zone and where
   zone-map (named map-name) sends it, \"A → Left · B → Right (map: Left /
   Right)\", a union \"A + B → Left + Right\", a target naming no zone
   \"Graphics (pinned)\", or for those alone \"Pinned: ignores the zone
   map\". nil when there's no badge."
  [names zone-map map-name targets]
  (let [[singles unions pinned] (grouped targets)
        zone-parts (concat (map #(target-text names zone-map (zone-ref %)) singles)
                           (map #(target-text names zone-map %) unions))
        pin-parts (map #(str (target-text names zone-map %) " (pinned)") pinned)]
    (cond
      (seq zone-parts) (str (str/join " · " (concat zone-parts pin-parts))
                            (when map-name (str " (map: " map-name ")")))
      (seq pinned) "Pinned: ignores the zone map"
      :else nil)))
