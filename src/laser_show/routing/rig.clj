(ns laser-show.routing.rig
  "The rig as data: the projectors from left to right, and the groups that
   come from that order (plans/2026-11-19-zones/01 §3, 04 §4). Pure.

   Refs (spec 01 §1):
   - :all, every enabled projector that isn't set to :exclude-from-all?;
   - :auto/left :auto/right :auto/center :auto/odd :auto/even, folded from E,
     the enabled projectors in rig order without the excluded ones (D-33);
   - :auto.laser/<id>, one projector, for every enabled one, excluded or not;
   - a custom group id (:graphics, :crowd, ...), the projectors that list it
     in their :zone-groups.
   All and the automatic groups are derived: never saved, never listed in a
   projector's :zone-groups.

   An entry of a group is [projector-id region side]: region nil (the whole
   laser), :L or :R (a half); side nil, or :left/:right for a :both middle.
   The engines compose from them (routing/compose.clj), halves and all.

   Identity (D-23): a projector is known by its :stable-id, [:unit \"0A 1B …\"
   output] when the device reports a usable unit id, else [:host address
   output]. The :projectors keys stay as they are (04 S-7).

   The routing (05 §4, spec 01 §5): routing works out, from the rig, the
   projectors and the groups, every ref's entries and each laser's scan
   rate, fit settings and the boxes its halves fit into. The show clock
   keeps it (cached-routing) and puts it in each tick's snapshot; everything
   reads a ref through resolve-ref."
  (:require [clojure.string :as str]
            [laser-show.animation.fit-guard :as fit-guard]
            [laser-show.animation.safety :as safety]
            [laser-show.idn.stream :as stream]))


;; Enabled, excluded, the stored groups

(defn enabled?
  "The engines' rule: a missing :enabled? counts as on."
  [p]
  (boolean (:enabled? p true)))

(defn- excluded? [p]
  (true? (:exclude-from-all? p)))

(defn laser-ref
  "The ref of one projector's own group: :auto.laser/<id>."
  [id]
  (keyword "auto.laser" (name id)))

(defn laser-of
  "The projector id of a laser ref, or nil."
  [ref]
  (when (and (keyword? ref) (= "auto.laser" (namespace ref)))
    (keyword (name ref))))

(defn derived-ref?
  "Is ref All or an automatic group (never stored)?"
  [ref]
  (boolean (and (keyword? ref)
                (or (= :all ref) (contains? #{"auto" "auto.laser"} (namespace ref))))))

(defn custom-refs
  "The custom groups an output (projector or virtual projector) lists, in its
   order, each once: never All or an automatic group, which old or
   hand-edited data may still list."
  [output]
  (into [] (comp (filter keyword?) (remove derived-ref?) (distinct)) (:zone-groups output)))

(defn stored-refs
  "The refs an output reaches by its own settings: :all unless it is
   excluded, then its custom groups."
  [output]
  (cond->> (custom-refs output)
    (not (excluded? output)) (into [:all])))


;; Targets and frame keys (spec 01 §1)

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

(defn zone-index
  "A zone ref's index: :zone/a 0 … :zone/z 25, :zone/aa 26 …; nil for any
   other ref. It orders frame keys, and picks a zone's lane in Z4."
  [ref]
  (when (and (keyword? ref) (= "zone" (namespace ref)))
    (let [letters (name ref)]
      (when (re-matches #"[a-z]{1,12}" letters)
        (dec (reduce (fn [acc c] (+ (* 26 acc) (- (int c) 96))) 0 letters))))))


;; Display data for the derived refs

(def group-defs
  "Names, colours and descriptions of All and the automatic groups. Each
   laser's own group is laser-color, named after its projector."
  {:all {:name "All" :color "#808080" :description "Every laser that's on, except those set to Exclude from All."}
   :auto/left {:name "Left" :color "#4A90D9" :description "The left half of the rig."}
   :auto/right {:name "Right" :color "#D94A4A" :description "The right half of the rig."}
   :auto/center {:name "Center" :color "#4AD94A" :description "The middle laser, or the middle two."}
   :auto/odd {:name "Odd" :color "#F1C40F" :description "Every other laser from the left: 1st, 3rd, 5th…"}
   :auto/even {:name "Even" :color "#1ABC9C" :description "Every other laser from the left: 2nd, 4th…"}})

(def laser-color "#4A6FA5")

(def auto-refs
  "The automatic groups folded from the order, in the order they are listed
   and joined."
  [:auto/left :auto/right :auto/center :auto/odd :auto/even])

(defn projector-name
  "A projector's name, or its id when it has none."
  [state id]
  (let [n (get-in state [:projectors id :name])]
    (if (and (string? n) (not (str/blank? n))) n (name id))))

(defn ref-name
  "What the pickers call ref: \"All\", \"Left\" …, a laser's own group by
   its projector's name, a custom group by its name (or its id)."
  [state ref]
  (cond
    (contains? group-defs ref) (get-in group-defs [ref :name])
    (laser-of ref) (projector-name state (laser-of ref))
    :else (let [n (get-in state [:zone-groups ref :name])]
            (cond
              (and (string? n) (not (str/blank? n))) n
              (keyword? ref) (name ref)
              :else (str ref)))))

(defn known-ref?
  "Is ref something a cue can be sent to: All, an automatic group, the
   group of a projector that exists, or a stored group?"
  [state ref]
  (boolean (or (contains? group-defs ref)
               (when-let [id (laser-of ref)] (contains? (:projectors state) id))
               (contains? (:zone-groups state) ref))))


;; Stable identity

(defn- usable-unit-id
  "The unit id as upper-case bytes with single spaces, or nil when it can't
   tell devices apart: nil, blank, or every byte 00 (or every byte FF)."
  [unit-id]
  (when (string? unit-id)
    (let [tokens (remove str/blank? (str/split (str/upper-case unit-id) #"\s+"))]
      (when (and (seq tokens) (not-every? #{"00"} tokens) (not-every? #{"FF"} tokens))
        (str/join " " tokens)))))

(defn- output-number [service-id]
  (if (integer? service-id) service-id 0))

(defn stable-id
  "[:unit \"0A 1B …\" s] when the unit id is usable, else [:host host s],
   where s is the service id (0 without one)."
  [{:keys [unit-id service-id host]}]
  (let [s (output-number service-id)]
    (if-let [u (usable-unit-id unit-id)]
      [:unit u s]
      [:host host s])))

(defn identity-label
  "\"Unit 0A 1B 2C 3D … · output 1\", or \"IP 10.0.0.5 · output 1 (the device
   sends no unit id)\"; why (\", so a new address …\") is added inside the
   parentheses."
  ([p] (identity-label p nil))
  ([p why]
   (let [[kind v s] (or (:stable-id p) (stable-id p))]
     (if (= :unit kind)
       (let [tokens (str/split v #" ")]
         (str "Unit " (if (> (count tokens) 4) (str (str/join " " (take 4 tokens)) " …") v)
              " · output " s))
       (str "IP " v " · output " s " (the device sends no unit id" why ")")))))


;; The order

(defn- ipv4
  "The four numbers of an IPv4 address, or nil."
  [host]
  (when (string? host)
    (when-let [[_ & parts] (re-matches #"(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})" (str/trim host))]
      (let [nums (mapv parse-long parts)]
        (when (every? #(<= 0 % 255) nums) nums)))))

(defn- id-text [id]
  (if (keyword? id) (str (symbol id)) (str id)))

(defn placement-key
  "Where a projector goes when it has no place yet: IPv4 hosts first, as
   four numbers, then other hosts by text; then name, service id and id."
  [id p]
  (let [ip (ipv4 (:host p))
        n (:name p)]
    [(if ip 0 1)
     (or ip [0 0 0 0])
     (if ip "" (str/lower-case (str (:host p))))
     (if (string? n) (str/lower-case n) "")
     (output-number (:service-id p))
     (id-text id)]))

(defn effective-order
  "The projectors from left to right: the stored order (ids that still
   exist, each once), then every other projector by placement-key. So an old
   show is ordered by IP, then name, and a newly found laser lands after the
   placed ones."
  [stored projectors]
  (let [projectors (or projectors {})
        placed (into [] (comp (filter #(contains? projectors %)) (distinct)) stored)
        placed? (set placed)]
    (into placed
          (comp (remove (comp placed? key))
                (map key))
          (sort-by (fn [[id p]] (placement-key id p)) projectors))))


;; The rig's inputs and the groups folded from them

(def middle-policies #{:split :both :left :right :none})

(def default-split-width 0.75)

(defn middle-policy
  "[:rig :middle], or :split for anything that isn't a policy."
  [rig]
  (let [m (:middle rig)]
    (if (contains? middle-policies m) m :split)))

(defn split-width
  "[:rig :split-width] clamped to 0.5-1.0, or 0.75 when it isn't a number."
  [rig]
  (let [w (:split-width rig)]
    (if (and (number? w) (not (Double/isNaN (double w))))
      (max 0.5 (min 1.0 (double w)))
      default-split-width)))

(defn rig-inputs
  "What the groups are folded from: {:order (effective-order) :enabled #{id}
   :excluded #{id} :middle :split-width :member-of {id [custom group]}
   :groups #{custom group defined in [:zone-groups]}}."
  [state]
  (let [projectors (or (:projectors state) {})
        rig (:rig state)
        order (effective-order (:order rig) projectors)]
    {:order order
     :enabled (into #{} (filter #(enabled? (get projectors %))) order)
     :excluded (into #{} (filter #(excluded? (get projectors %))) order)
     :middle (middle-policy rig)
     :split-width (split-width rig)
     :member-of (into {} (map (fn [id] [id (custom-refs (get projectors id))])) order)
     :groups (into #{} (remove derived-ref?) (keys (:zone-groups state)))}))

(defn e-order
  "E: the enabled projectors that aren't excluded, in rig order."
  [{:keys [order enabled excluded]}]
  (filterv #(and (contains? enabled %) (not (contains? excluded %))) order))

(defn- whole [id] [id nil nil])

(defn- fold
  "Spec 01 §3: Left, Right, Center, Odd and Even of E."
  [e middle]
  (let [n (count e)
        odd (into [] (comp (take-nth 2) (map whole)) e)
        even (into [] (comp (drop 1) (take-nth 2) (map whole)) e)]
    (cond
      (zero? n) {:auto/left [] :auto/right [] :auto/center [] :auto/odd [] :auto/even []}

      (= 1 n) (let [p (first e)]
                {:auto/left [[p :L nil]] :auto/right [[p :R nil]] :auto/center [(whole p)]
                 :auto/odd [[p :L nil]] :auto/even [[p :R nil]]})

      (even? n) (let [h (quot n 2)]
                  {:auto/left (mapv whole (subvec e 0 h))
                   :auto/right (mapv whole (subvec e h))
                   :auto/center [(whole (e (dec h))) (whole (e h))]
                   :auto/odd odd
                   :auto/even even})

      :else (let [m (quot n 2)
                  pm (e m)
                  [in-left in-right] (case middle
                                       :both [[[pm nil :left]] [[pm nil :right]]]
                                       :left [[(whole pm)] []]
                                       :right [[] [(whole pm)]]
                                       :none [[] []]
                                       [[[pm :L nil]] [[pm :R nil]]])]
              {:auto/left (into (mapv whole (subvec e 0 m)) in-left)
               :auto/right (into in-right (map whole) (subvec e (inc m)))
               :auto/center [(whole pm)]
               :auto/odd odd
               :auto/even even}))))

(defn auto-groups
  "{ref [[projector-id region side] …]}: the five folded groups (spec 01 §3:
   halves for one laser and for the :split middle, the side tag for :both;
   empty with no laser in E), and :auto.laser/<id> for every enabled laser,
   excluded or not."
  [inputs]
  (into (fold (e-order inputs) (:middle inputs))
        (comp (filter (:enabled inputs))
              (map (fn [id] [(laser-ref id) [(whole id)]])))
        (:order inputs)))

(defn ref-entries
  "Every ref the rig knows, with its entries: auto-groups, :all (E, whole),
   and each custom group (its enabled members, whole, in rig order). What
   Z3's resolver puts under :refs."
  [inputs]
  (let [{:keys [order enabled member-of groups]} inputs
        members (fn [g] (into [] (comp (filter enabled)
                                       (filter #(some #{g} (get member-of %)))
                                       (map whole))
                              order))
        custom (into (set groups) (mapcat val) member-of)]
    (-> (auto-groups inputs)
        (assoc :all (mapv whole (e-order inputs)))
        (into (map (fn [g] [g (members g)])) custom))))


;; The routing each tick carries (zones plan Z3, spec 01 §5)

(def ^:private min-box
  "A safe box narrower or lower than this has no halves (05 S-9)."
  0.05)

(defn region-fits
  "Spec 01 §7: where each half of a laser draws, from the bounding box of its
   safety limit [x0 y0 x1 y1] and the split width w: {:L [cx cy s] :R [cx cy
   s]}, a point (x, y) of a half's picture going to (cx + s·x, cy + s·y). :L
   spans the box's left w of the width and :R its right w; s is half of the
   smaller of a half's width and the box's height, so shapes keep their
   aspect. nil for a box under 0.05 wide or high (S-9)."
  [limit w]
  (when (and (sequential? limit) (= 4 (count limit)) (every? number? limit))
    (let [[a b c d] (map double limit)
          x0 (min a c) x1 (max a c) y0 (min b d) y1 (max b d)
          width (- x1 x0)
          height (- y1 y0)]
      (when (and (>= width min-box) (>= height min-box))
        (let [half (* (double w) width)
              s (/ (min half height) 2.0)
              cy (/ (+ y0 y1) 2.0)]
          {:L [(+ x0 (/ half 2.0)) cy s]
           :R [(- x1 (/ half 2.0)) cy s]})))))

(def ^:private routing-keys [:rig :projectors :zone-groups :zone-maps :map])

(defn routing-inputs
  "What the routing is worked out from, slices of one tick's app state:
   {:rig :projectors :zone-groups :zone-maps (nil until Z4) :map (the
   effective map id, nil until Z4)}. Their identity is cached-routing's key."
  [state map-id]
  {:rig (:rig state)
   :projectors (:projectors state)
   :zone-groups (:zone-groups state)
   :zone-maps (:zone-maps state)
   :map map-id})

(defn- halves
  "region-fits of a projector's safety limit as safety/normalize reads it,
   or nil when it can't be read: that laser draws no halves, and the safety
   stage sends it dark (D-29)."
  [p w]
  (try
    (region-fits (:limit (safety/normalize (:safety p))) w)
    (catch Exception _ nil)))

(defn routing
  "The routing (spec 01 §5): {:map map-id :refs {ref [[projector-id region
   side] …]} :outputs {projector-id {:index :scan-rate :fit :regions}}}.
   :refs is ref-entries: every ref the rig knows but the zones. :outputs has
   every enabled laser, excluded ones too: :index its place in E (nil when
   excluded from All), :scan-rate clamped as its engine clamps it, :fit its
   fit-guard/settings, :regions the region-fits of its safety limit. Pure."
  [{:keys [rig projectors zone-groups] map-id :map}]
  (let [inputs (rig-inputs {:rig rig :projectors projectors :zone-groups zone-groups})
        index (zipmap (e-order inputs) (range))
        w (:split-width inputs)]
    {:map map-id
     :refs (ref-entries inputs)
     :outputs (into {}
                    (keep (fn [id]
                            (when (contains? (:enabled inputs) id)
                              (let [p (get projectors id)]
                                [id {:index (get index id)
                                     :scan-rate (or (stream/clamp-scan-rate (:scan-rate p)) stream/DEFAULT_SCAN_RATE)
                                     :fit (fit-guard/settings (:fit p))
                                     :regions (halves p w)}]))))
                    (:order inputs))}))

(defn resolve-ref
  "ref's entries in routing, [] for a ref it doesn't know: the only way to
   read them. Z4 resolves a zone here, through the effective map's lanes."
  [routing ref]
  (get (:refs routing) ref []))

(defn cached-routing
  "[cache routing] for inputs (routing-inputs): the cached routing while
   every input is identical? to the cached one (every tick without an edit),
   otherwise a new one, which the new cache keeps."
  [cache inputs]
  (if (and cache (every? #(identical? (get inputs %) (get-in cache [:inputs %])) routing-keys))
    [cache (:routing cache)]
    (let [r (routing inputs)]
      [{:inputs inputs :routing r} r])))


;; The refs that reach a projector, for the vibes' audience rule (refs-reaching):
;; no engine reads them, since the engines compose from the tick's routing

(defn- compute-refs
  "{projector-id [ref …]} of every enabled projector, in join order."
  [state]
  (let [inputs (rig-inputs state)
        groups (auto-groups inputs)
        in? (fn [ref id] (some #(= id (first %)) (get groups ref)))]
    (into {}
          (keep (fn [id]
                  (when (contains? (:enabled inputs) id)
                    [id (-> (if (contains? (:excluded inputs) id) [] [:all])
                            (into (get (:member-of inputs) id))
                            (into (filter #(in? % id)) auto-refs)
                            (conj (laser-ref id)))])))
          (:order inputs))))

(defonce ^:private !refs
  ;; {:projectors :rig :refs}: the last computed, for the identity of
  ;; [:projectors] and [:rig]. The engine threads and the UI share it; a race
  ;; only recomputes.
  (atom nil))

(defn refs-for
  "The refs that reach projector id, in join order: :all unless it is
   excluded; its stored custom groups, in their order; the automatic groups
   it is in (Left, Right, Center, Odd, Even, then its own). A half counts as
   the whole laser. nil for an unknown or disabled
   projector. The same value (identical?) while [:projectors] and [:rig]
   are."
  [state id]
  (let [projectors (:projectors state)
        rig (:rig state)
        cached @!refs]
    (if (and cached (identical? projectors (:projectors cached)) (identical? rig (:rig cached)))
      (get (:refs cached) id)
      (let [refs (compute-refs state)]
        (reset! !refs {:projectors projectors :rig rig :refs refs})
        (get refs id)))))

(defn reaching
  "The refs that reach projector id: refs-for when it is enabled; when it is
   off, :all unless excluded, its custom groups and its own (today's
   audience rule counts lasers that are off too)."
  [state id]
  (when-let [p (get-in state [:projectors id])]
    (if (enabled? p)
      (refs-for state id)
      (conj (stored-refs p) (laser-ref id)))))

(defn refs-reaching
  "Every ref that reaches a projector (pred projector) accepts."
  [state pred]
  (into #{}
        (mapcat (fn [[id p]] (when (pred p) (reaching state id))))
        (:projectors state)))


;; What the pickers list

(defn target-groups
  "Everything a cue can be sent to, in the pickers' order: All; Left, Right,
   Center, Odd, Even; each laser's own group, in rig order (\"(off)\" while
   it is off: it reaches nothing then); then your groups by name. Each is
   {:id :name :color :description :kind}, :kind one of :all :auto :laser
   :custom; a laser's also has :projector-id. state needs :projectors,
   :rig and :zone-groups."
  [state]
  (let [projectors (or (:projectors state) {})
        derived (for [ref (cons :all auto-refs)]
                  (assoc (group-defs ref) :id ref :kind (if (= :all ref) :all :auto)))
        lasers (for [id (effective-order (get-in state [:rig :order]) projectors)
                     :let [n (projector-name state id)]]
                 {:id (laser-ref id)
                  :name (cond-> n (not (enabled? (get projectors id))) (str " (off)"))
                  :color laser-color
                  :description (str "Just " n ".")
                  :kind :laser
                  :projector-id id})
        custom (->> (:zone-groups state)
                    (remove (comp derived-ref? key))
                    (map (fn [[id g]] (assoc g :id id :kind :custom)))
                    (sort-by (fn [g] [(str/lower-case (str (:name g))) (name (:id g))])))]
    (vec (concat derived lasers custom))))


;; How each laser is in a group (the rows under the cards, and the Zone
;; Groups tab)

(defn- entry-mark [[_ region]]
  (case region :L :left-half :R :right-half :whole))

(defn ref-marks
  "{projector-id mark} for ref (or :each-laser, every laser's own group at
   once) over the rig's projectors (inputs, and groups: their auto-groups),
   as zone-chips/member-mark shows them: :whole :left-half :right-half for
   All and the automatic groups (:not-in when not in one, :excluded for a
   laser excluded from All, :off for one that is off); :member :not-member
   for your groups, whatever the laser's state."
  [inputs groups ref]
  (let [{:keys [order enabled excluded member-of]} inputs
        lacking (fn [id] (cond (not (contains? enabled id)) :off
                               (contains? excluded id) :excluded))
        auto? (set auto-refs)]
    (into {}
          (map (fn [id]
                 [id (cond
                       (= :all ref) (or (lacking id) :whole)
                       (auto? ref) (or (lacking id)
                                       (if-let [entry (some #(when (= id (first %)) %) (get groups ref))]
                                         (entry-mark entry)
                                         :not-in))
                       (= :each-laser ref) (if (contains? enabled id) :whole :off)
                       (laser-of ref) (cond (not= id (laser-of ref)) :not-in
                                            (contains? enabled id) :whole
                                            :else :off)
                       :else (if (some #{ref} (get member-of id)) :member :not-member))]))
          order)))

(defn matrix
  "The rows under the rig strip's cards: {:columns [projector-id …] (rig
   order) :rows [{:id :name :color :kind :cells {projector-id mark}}]}, the
   rows All, Left, Right, Center, Odd, Even, Each laser (:each-laser), then
   your groups by name. state needs :projectors, :rig and :zone-groups."
  [state]
  (let [inputs (rig-inputs state)
        groups (auto-groups inputs)
        rows (concat (for [ref (cons :all auto-refs)]
                       (assoc (group-defs ref) :id ref :kind (if (= :all ref) :all :auto)))
                     [{:id :each-laser :name "Each laser" :color laser-color :kind :laser}]
                     (filter #(= :custom (:kind %)) (target-groups state)))]
    {:columns (:order inputs)
     :rows (mapv (fn [row]
                   (assoc (select-keys row [:id :name :color :kind])
                          :cells (ref-marks inputs groups (:id row))))
                 rows)}))


;; The scan's matching (04 §4)

(defn projector-id
  "A new projector's id, from its host and service id:
   :projector-10-0-0-5-1."
  [host service-id]
  (keyword (str "projector-" (str/replace (str host) "." "-") "-" (output-number service-id))))

(defn unique-projector-id
  "projector-id, or with -2, -3 … while taken (a map or set of ids)."
  [taken host service-id]
  (let [base (projector-id host service-id)]
    (->> (cons base (map #(keyword (str (name base) "-" %)) (iterate inc 2)))
         (remove #(contains? taken %))
         first)))

(defn- host-id [p]
  [:host (:host p) (output-number (:service-id p))])

(defn settled-ids
  "{id stable-id} of the saved projectors: each its :stable-id, or the one
   stable-id gives. When two share one (an old show made one per IP), the
   enabled one keeps it (otherwise the smaller id) and the others fall back
   to [:host address s]."
  [projectors]
  (let [own (into {} (map (fn [[id p]] [id (or (:stable-id p) (stable-id p))])) projectors)]
    (reduce (fn [acc [_ ids]]
              (if (< 1 (count ids))
                (let [keeper (first (sort-by (fn [id] [(if (enabled? (get projectors id)) 0 1) (id-text id)]) ids))]
                  (reduce #(if (= keeper %2) %1 (assoc %1 %2 (host-id (get projectors %2)))) acc ids))
                acc))
            own
            (group-by own (keys own)))))

(defn- address-key [address]
  (let [ip (ipv4 address)]
    [(if ip 0 1) (or ip [0 0 0 0]) (str address)]))

(defn- count-word [n]
  (if (= 2 n) "Two" (str n)))

(defn- move-notice [projectors moved moves]
  (case (count moved)
    0 nil
    1 (let [id (first moved)]
        (str (projector-name {:projectors projectors} id) " moved to " (get-in moves [id :host]) " (same unit)."))
    (str (count moved) " lasers have new addresses: "
         (str/join ", " (map #(projector-name {:projectors projectors} %) moved)) ".")))

(defn match-discovered
  "Match a scan's devices to the saved projectors by stable id:
   1. each output gets a stable id; devices of one scan that report the same
      usable unit id are matched by IP this time, with a notice;
   2. a projector with that stable id moves when its host differs (host and
      port, nothing else);
   3. otherwise a projector whose stable id is [:host address s] upgrades to
      the unit identity (a laser added by hand, then found by a scan); one
      added by hand with no service id matches a device with one output or
      none;
   4. otherwise the output is new, with a free id (unique-projector-id);
   5. two saved projectors with one stable id: see settled-ids.
   Returns {:moves {id {:host :port}} :upgrades {id stable-id} (every
   identity to write: step 3's, and saved projectors whose settled id isn't
   stored yet) :new [{:id :device :service :stable-id}] :notices [text]}."
  [projectors devices]
  (let [projectors (or projectors {})
        devices (into [] (comp (filter #(and (string? (:address %)) (not (str/blank? (:address %)))))
                               (distinct))
                      devices)
        shared (->> (group-by #(usable-unit-id (:unit-id %)) devices)
                    (filter (fn [[u ds]] (and u (< 1 (count (distinct (map :address ds)))))))
                    (map (fn [[_ ds]] (sort-by address-key (distinct (map :address ds)))))
                    (sort-by (comp address-key first)))
        shared? (set (apply concat shared))
        settled (settled-ids projectors)
        by-sid (reduce (fn [m [id sid]] (if (contains? m sid) m (assoc m sid id)))
                       {}
                       (sort-by (comp id-text key) settled))
        outputs (for [d devices s (or (seq (:services d)) [nil])] [d s])
        result (reduce
                (fn [{:keys [claimed] :as acc} [d s]]
                  (let [address (:address d)
                        n (output-number (:service-id s))
                        sid (if (shared? address)
                              [:host address n]
                              (stable-id {:unit-id (:unit-id d) :service-id n :host address}))
                        free (fn [id] (when (and id (not (contains? claimed id))) id))
                        hand-added (free (let [id (get by-sid [:host address 0])]
                                           (when (and id (<= (count (:services d)) 1)
                                                      (nil? (get-in projectors [id :service-id])))
                                             id)))]
                    (if-let [id (free (get by-sid sid))]
                      (cond-> (update acc :claimed conj id)
                        (not= address (get-in projectors [id :host]))
                        (-> (assoc-in [:moves id] {:host address :port (or (:port d) 7255)})
                            (update :moved conj id)))
                      (if-let [id (or (free (get by-sid [:host address n])) hand-added)]
                        (-> acc
                            (update :claimed conj id)
                            (assoc-in [:upgrades id] sid))
                        (let [id (unique-projector-id (:taken acc) address n)]
                          (-> acc
                              (update :taken conj id)
                              (update :new conj {:id id :device d :service s :stable-id sid})))))))
                {:moves {} :upgrades {} :new [] :moved [] :claimed #{} :taken (set (keys projectors))}
                outputs)
        unstored (into {} (remove (fn [[id sid]] (= sid (get-in projectors [id :stable-id])))) settled)]
    {:moves (:moves result)
     :upgrades (merge unstored (:upgrades result))
     :new (:new result)
     :notices (into (mapv #(str (count-word (count %)) " devices report the same unit id ("
                                (str/join ", " %) "): matched by IP.")
                          shared)
                    (keep identity [(move-notice projectors (:moved result) (:moves result))]))}))
