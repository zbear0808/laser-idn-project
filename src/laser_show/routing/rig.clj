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
   Z2's engine draws whole lasers (refs-for); Z3 reads the regions.

   Identity (D-23): a projector is known by its :stable-id, [:unit \"0A 1B …\"
   output] when the device reports a usable unit id, else [:host address
   output]. The :projectors keys stay as they are (04 S-7)."
  (:require [clojure.string :as str]))


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


;; Display data for the derived refs

(def group-defs
  "Names and colours of All and the automatic groups. Each laser's own group
   is laser-color, named after its projector."
  {:all {:name "All" :color "#808080"}
   :auto/left {:name "Left" :color "#4A90D9"}
   :auto/right {:name "Right" :color "#D94A4A"}
   :auto/center {:name "Center" :color "#4AD94A"}
   :auto/odd {:name "Odd" :color "#F1C40F"}
   :auto/even {:name "Even" :color "#1ABC9C"}})

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
   sends no unit id)\"."
  [p]
  (let [[kind v s] (or (:stable-id p) (stable-id p))]
    (if (= :unit kind)
      (let [tokens (str/split v #" ")]
        (str "Unit " (if (> (count tokens) 4) (str (str/join " " (take 4 tokens)) " …") v)
             " · output " s))
      (str "IP " v " · output " s " (the device sends no unit id)"))))


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


;; The refs that reach a projector: the Z2 bridge to the engine

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
   the whole laser (Z2 draws whole). nil for an unknown or disabled
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
