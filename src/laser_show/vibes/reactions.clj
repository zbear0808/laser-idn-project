(ns laser-show.vibes.reactions
  "Tap reactions: effect cells that move with the presses
   (plans/2026-11-18-vibe-mode/04-phase4-tap-reactions.md §7). A reaction is
   an ordinary effect cell whose modulators follow a role's press (the
   :pulse types, or Restart on), made in one click from a template: in a
   vibe's FX set (add-to-vibe, :vibe/add-reaction) or straight onto the
   effects grid (:effects/new-reaction).

   The zone reactions (vibe Phase 8b, plans/2026-11-19-zones/11 §12) are
   zone patterns (routing.zone-patterns) that step on the press itself: Zone
   hop and Zone chase move the All picture across every laser, Zone swap
   and Zone shift trade what is drawn into zones A and B (A to D). A new
   one's places keep clear of the audience (lane), and one the rig has too
   few places for isn't made (zone-hint).

   Also which cells react to which role, for the Perform pads, the Vibes
   tab and its lint (listens-to, reactions; laser-show.vibes.library's lint
   reads them, so this namespace reads the vibes without it)."
  (:require [clojure.string :as str]
            [laser-show.animation.modulator-registry :as reg]
            [laser-show.animation.modulators]
            [laser-show.events.helpers :as h]
            [laser-show.routing.rig :as rig]
            [laser-show.routing.targets :as targets]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.routing.zone-remap :as zone-remap]))


;; The templates

(defn- envelope [source lo hi & {:keys [attack release curve hold?]
                                 :or {attack 0.0 release 1.0 curve :exp hold? false}}]
  {:type :envelope :active? true :source source :min lo :max hi
   :attack attack :release release :curve curve :hold? hold?})

(defn- step [source lo hi steps & {:as opts}]
  (merge {:type :step :active? true :source source :min lo :max hi :steps steps
          :order :up :wrap? false :glide 0.0 :values []}
         opts))

(defn- scale [x y] {:x-scale x :y-scale y :uniform? false})

(defn- zone-pattern
  "A zone reaction's effect: Z5's preset of style for an effects cell
   (zone-patterns/preset-instance: its lane, the show's beats, following the
   pattern pads), stepping as press says (:step-on, :restart-on)."
  [style press]
  (update (zone-patterns/preset-instance style :cell) :params merge press))

(def templates
  "The one-click reactions, in menu order, grouped by the role they follow:
   {:kind :menu (its menu text) :name (the cell's) :role :effects}, and
   :into :flash-fx for those that belong in the flash FX, on only while
   FLASH is held. Each is identity at rest but Pump, whose menu says its rest
   level, and Zone hop, which puts the All picture on its first laser until
   the first HIT (the zone reactions: zone-reaction?)."
  (let [punch (envelope :hit 1.0 1.25 :release 0.5)
        swell (envelope :flash 1.0 1.4 :attack 0.5 :release 1.0 :curve :linear :hold? true)
        energy {:type :energy :active? true :source :any :min 1.0 :max 1.4 :rise 0.2 :fall 0.25}]
    [{:kind :punch-size :role :hit :menu "Punch: size" :name "Punch size"
      :effects [{:effect-id :scale :params (scale punch punch)}]}
     {:kind :pump :role :hit :menu "Pump: brightness (35 % between hits)" :name "Pump"
      :effects [{:effect-id :intensity :params {:amount (envelope :hit 0.35 1.0 :release 0.75)}}]}
     {:kind :step-colour :role :hit :menu "Step: colour" :name "Step colour"
      :effects [{:effect-id :hue-shift :params {:degrees (step :hit 0.0 180.0 4)}}]}
     {:kind :step-spin :role :hit :menu "Step: spin" :name "Step spin"
      :effects [{:effect-id :rotation :params {:angle (step :hit 0.0 360.0 8 :wrap? true :glide 0.25)}}]}
     {:kind :zone-hop :role :hit :menu "Zone hop: next zone (one zone at a time)" :name "Zone hop"
      :effects [(zone-pattern :chase {:step-on :hit})]}
     {:kind :flip :role :accent :menu "Flip" :name "Flip"
      :effects [{:effect-id :scale :params (scale (step :accent -1.0 1.0 2 :order :down) 1.0)}]}
     {:kind :scatter :role :accent :menu "Scatter: position" :name "Scatter"
      :effects [{:effect-id :translate :params {:x (step :accent -0.4 0.4 9 :order :scatter) :y 0.0}}]}
     {:kind :zone-swap :role :accent :menu "Zone swap" :name "Zone swap"
      :effects [(zone-pattern :swap {:step-on :accent})]}
     {:kind :zone-shift :role :accent :menu "Zone shift: next zone" :name "Zone shift"
      :effects [(zone-pattern :shift {:step-on :accent})]}
     {:kind :swell :role :flash :menu "Swell while held" :name "Swell"
      :effects [{:effect-id :scale :params (scale swell swell)}]}
     {:kind :strobe :role :flash :menu "Strobe while held" :name "Strobe" :into :flash-fx
      :effects [{:effect-id :intensity
                 :params {:amount {:type :square :active? true :min 0.0 :max 1.0 :period 0.125
                                   :time-unit :beats :loop-mode :loop :once-periods 1.0
                                   :duty-cycle 0.5 :phase 0.0 :restart-on :flash}}}]}
     {:kind :zone-chase :role :flash :menu "Zone chase while held" :name "Zone chase" :into :flash-fx
      :effects [(zone-pattern :chase {:restart-on :flash})]}
     {:kind :fade-in :role :vibe :menu "Fade in" :name "Fade in"
      :effects [{:effect-id :intensity :params {:amount (envelope :vibe 1.0 0.0 :release 1.0 :curve :linear)}}]}
     {:kind :energy-size :role :any :menu "Energy: size" :name "Energy size"
      :effects [{:effect-id :scale :params (scale energy energy)}]}]))

(def ^:private by-kind (into {} (map (juxt :kind identity)) templates))

(defn template
  "The template of kind, or nil."
  [kind]
  (get by-kind kind))

(defn zone-reaction?
  "Does template t hold a zone pattern: is it a zone reaction (vibe Phase
   8b)?"
  [t]
  (boolean (some #(= :zone-pattern (:effect-id %)) (:effects t))))


;; A zone reaction's places (plans/2026-11-19-zones/11 §12.3, §12.4)

(defn- template-zones
  "The places of a zone reaction's template t (its zone pattern's :zones),
   or nil for a template that isn't one."
  [t]
  (some #(when (= :zone-pattern (:effect-id %)) (get-in % [:params :zones])) (:effects t)))

(defn lane
  "The places a new zone reaction of kind moves pictures between on state's
   rig, nil for a kind that isn't one: its template's. Every laser
   (:lane/lasers, zones plan Z5-Q4 A) stays, unless a laser in All, on or
   off, can reach the audience (its own group is one of groups:
   vibes.library/audience-groups, which this namespace can't read); then it
   is the lasers that are on and in All and can't, left to right, each by
   its own group, so a new reaction never sweeps the crowd. Zones stay: the
   audience rule decides for them."
  [state groups kind]
  (when-let [zones (template-zones (template kind))]
    (let [inputs (rig/rig-inputs state)
          audience? #(contains? groups (rig/laser-ref %))]
      (if (and (some #{:lane/lasers} zones)
               (some audience? (remove (:excluded inputs) (:order inputs))))
        (into []
              (mapcat #(if (= :lane/lasers %) (map rig/laser-ref (remove audience? (rig/e-order inputs))) [%]))
              zones)
        zones))))

(defn places
  "The places zones (a zone pattern's lane) reach on state's rig now, as the
   zone stage reads them (zone-remap/lane): Every laser as the lasers that
   are on and in All, in rig order (one alone as its two halves), a zone as
   itself, a group or a laser the rig knows; the rest left out. Fewer than
   two is Z5's \"needs two zones\" (zone-patterns/warnings)."
  [state zones]
  (zone-remap/lane zones #(or (targets/zone-ref? %) (rig/known-ref? state %)) (rig/e-order (rig/rig-inputs state))))

(defn zone-hint
  "Why the Tap reactions menus grey the reaction kind, or nil: a zone
   reaction whose places (lane) on state's rig are fewer than two. Every
   laser has none without a laser on and in All, and the lasers that can't
   reach the audience may be too few. groups: as lane's."
  [state groups kind]
  (when-let [l (lane state groups kind)]
    (when (< (count (places state l)) 2)
      (if (some #{:lane/lasers} l)
        "needs a laser that's on and in All (Projectors tab)"
        "needs two lasers in All that don't scan the audience (Projectors tab)"))))


;; Making one

(defn reaction-cell
  "An effect cell for the reaction kind, off, named after the vibe
   (\"Groove · Punch size\", just the reaction's name without one); its
   effects have no :id yet (h/ensure-item-fields). A zone reaction's pattern
   takes zones as its places (lane), its template's without. nil for no
   such kind."
  ([kind vibe-name] (reaction-cell kind vibe-name nil))
  ([kind vibe-name zones]
   (when-let [{:keys [name effects]} (template kind)]
     {:name (if vibe-name (str vibe-name " · " name) name)
      :active? false
      :items (mapv #(cond-> (assoc % :enabled? true)
                      (and zones (= :zone-pattern (:effect-id %))) (assoc-in [:params :zones] zones))
                   effects)})))

(defn first-empty-effect-cell
  "Where a new reaction goes: the first empty effect cell from the bottom
   row up, each row left to right, which keeps them out of the way of the
   cells made by hand (and after Phase 5's Master, at [0 3]). nil when the
   grid is full."
  [state]
  (let [[cols rows] (or (get-in state [:grid :size]) [8 4])
        chains (get-in state [:chains :effect-chains])]
    (first (for [row (range (dec rows) -1 -1)
                 col (range cols)
                 :when (nil? (get chains [col row]))]
             [col row]))))

(defn add-to-vibe
  "state with the reaction kind made for the vibe: in the first empty effect
   cell, named after it, off, and last in its FX set (the Strobe and the
   Zone chase in its flash FX). A zone reaction takes lane's places (groups:
   the audience groups, vibes.library/audience-groups; #{} without), and
   isn't made while they are too few (zone-hint). :vibe/add-reaction then
   switches it on as every edit of the vibes does a cell new in the FX set
   of the vibe that plays (engine/reconcile). Unchanged without the vibe,
   the kind or a free cell."
  ([state vibe-id kind] (add-to-vibe state vibe-id kind #{}))
  ([state vibe-id kind groups]
   (let [vibe (get-in state [:config :vibes :library vibe-id])
         cell (first-empty-effect-cell state)
         made (when (and vibe (nil? (zone-hint state groups kind)))
                (reaction-cell kind (:name vibe) (lane state groups kind)))]
     (if (and made cell)
       (-> state
           (assoc-in [:chains :effect-chains cell] (update made :items #(mapv h/ensure-item-fields %)))
           (update-in [:config :vibes :library vibe-id]
                      #(if (= :flash-fx (:into (template kind)))
                         (update-in % [:flash :fx] (fnil conj []) cell)
                         (update % :fx (fnil conj []) cell))))
       state))))


;; What reacts to what

(defn effects-in
  "Every enabled effect in x (a chain, an item, a group, a track, or a
   vector of them), through the enabled ones (also vibes.faders')."
  [x]
  (cond
    (map? x) (when (not= false (:enabled? x))
               (if (:effect-id x)
                 [x]
                 (mapcat effects-in [(:items x) (:effects x) (:tracks x) (:zone-patterns x)])))
    (sequential? x) (mapcat effects-in x)
    :else nil))

(defn- default-source [mod-type]
  (some #(when (= :source (:key %)) (:default %)) (reg/get-params mod-type)))

(defn- modulator-source
  "The source a modulator config follows: a :pulse type's :source, else its
   :restart-on; nil for :own, none, or a modulator that's off."
  [v]
  (when (and (map? v) (keyword? (:type v)) (not= false (:active? v)))
    (let [source (if (= :pulse (:category (reg/get-modulator (:type v))))
                   (or (:source v) (default-source (:type v)))
                   (:restart-on v))]
      (when (and source (not= :own source))
        source))))

(defn- stage-sources
  "What a zone pattern or a Map cycle steps on (zones plan Z5, 07 S-10): its
   :step-on when it is a press's source, else (on the beat) its :restart-on
   when it names one; a plain parameter, not a modulator."
  [effect-id {:keys [step-on restart-on]}]
  (when (contains? zone-patterns/zone-stage-ids effect-id)
    (cond
      (and (keyword? step-on) (not= :clock step-on)) [step-on]
      (and (keyword? restart-on) (not= :own restart-on)) [restart-on])))

(defn- effect-sources [{:keys [effect-id params keyframe-modulator]}]
  (let [{:keys [enabled? restart-on]} keyframe-modulator]
    (cond-> (into (set (stage-sources effect-id params)) (keep modulator-source) (vals params))
      (and enabled? restart-on (not= :own restart-on)) (conj restart-on))))

(defn listens-to
  "The sources any enabled effect in chain follows: a :pulse type's :source,
   a modulator's or a time-driven keyframe animation's :restart-on, a zone
   pattern's or Map cycle's Step on or Restart on (#{} for none). A chain
   is an effect cell, a cue chain (its own zone patterns too), or anything
   holding effects."
  [chain]
  (into #{} (mapcat effect-sources) (effects-in chain)))

(def roles-of
  "The roles whose press a source follows: :any is all three, a cue start
   (:cue) is no role's."
  {:hit [:hit] :accent [:accent] :flash [:flash] :vibe [:vibe] :any [:hit :accent :flash]})

(defn- words [k] (str/replace (name k) "-" " "))

(defn- display-name
  "What a reaction is called under its vibe: the cell's name without the
   vibe's own (\"Groove · Punch size\" is \"Punch size\" under Groove), else
   its first effect's, or for a cue cell its first preset's."
  [grid chain vibe-name]
  (let [n (:name chain)
        prefix (str vibe-name " · ")]
    (cond
      (and (seq n) vibe-name (str/starts-with? n prefix)) (subs n (count prefix))
      (seq n) n
      (= :cues grid) (or (some-> (first (:items chain)) :preset-id words) "")
      :else (or (some-> (first (effects-in chain)) :effect-id words) ""))))

(defn reactions
  "The cells that react to each role while vibe-id plays: {role [{:grid
   :effects|:cues :cell :name}]}, roles with none left out. In order: its FX
   set, its flash FX, the effects grid's cells that are on (but with :own?
   true, the vibe's own cells only, as the Vibes tab and its lint show
   them), then the cues of its pools, flash and bed, each cell once per
   role. {} for no such vibe."
  [state vibe-id & {:keys [own?]}]
  (if-let [vibe (get-in state [:config :vibes :library vibe-id])]
    (let [effect-chains (get-in state [:chains :effect-chains])
          cue-chains (get-in state [:chains :cue-chains])
          on-grid (when-not own?
                    (sort-by (fn [[col row]] [row col]) (keep (fn [[cell c]] (when (:active? c) cell)) effect-chains)))
          cells (distinct (concat (for [cell (concat (:fx vibe) (get-in vibe [:flash :fx]) on-grid)] [:effects cell])
                                  (for [cell (concat (get-in vibe [:hit :cells]) (get-in vibe [:accent :cells])
                                                     [(get-in vibe [:flash :cue]) (:bed vibe)])
                                        :when cell]
                                    [:cues cell])))]
      (reduce (fn [acc [grid cell]]
                (let [chain (get (if (= :effects grid) effect-chains cue-chains) cell)
                      entry {:grid grid :cell cell :name (display-name grid chain (:name vibe))}]
                  (reduce (fn [acc role] (update acc role (fnil conj []) entry))
                          acc
                          (distinct (mapcat roles-of (listens-to chain))))))
              {}
              cells))
    {}))
