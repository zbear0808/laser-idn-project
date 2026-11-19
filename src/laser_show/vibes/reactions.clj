(ns laser-show.vibes.reactions
  "Tap reactions: effect cells that move with the presses
   (plans/2026-11-18-vibe-mode/04-phase4-tap-reactions.md §7). A reaction is
   an ordinary effect cell whose modulators follow a role's press (the
   :pulse types, or Restart on), made in one click from a template: in a
   vibe's FX set (add-to-vibe, :vibe/add-reaction) or straight onto the
   effects grid (:effects/new-reaction).

   Also which cells react to which role, for the Perform pads and the Vibes
   tab (listens-to, reactions). They live here rather than in
   laser-show.vibes.library, which Phase 3 rewrites."
  (:require [clojure.string :as str]
            [laser-show.animation.modulator-registry :as reg]
            [laser-show.animation.modulators]
            [laser-show.events.helpers :as h]
            [laser-show.vibes.library :as library]))


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

(def templates
  "The one-click reactions, in menu order, grouped by the role they follow:
   {:kind :menu (its menu text) :name (the cell's) :role :effects}, and
   :into :flash-fx for the one that belongs in the flash FX, on only while
   FLASH is held. Each is identity at rest but Pump, whose menu says its rest
   level."
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
     {:kind :flip :role :accent :menu "Flip" :name "Flip"
      :effects [{:effect-id :scale :params (scale (step :accent -1.0 1.0 2 :order :down) 1.0)}]}
     {:kind :scatter :role :accent :menu "Scatter: position" :name "Scatter"
      :effects [{:effect-id :translate :params {:x (step :accent -0.4 0.4 9 :order :scatter) :y 0.0}}]}
     {:kind :swell :role :flash :menu "Swell while held" :name "Swell"
      :effects [{:effect-id :scale :params (scale swell swell)}]}
     {:kind :strobe :role :flash :menu "Strobe while held" :name "Strobe" :into :flash-fx
      :effects [{:effect-id :intensity
                 :params {:amount {:type :square :active? true :min 0.0 :max 1.0 :period 0.125
                                   :time-unit :beats :loop-mode :loop :once-periods 1.0
                                   :duty-cycle 0.5 :phase 0.0 :restart-on :flash}}}]}
     {:kind :fade-in :role :vibe :menu "Fade in" :name "Fade in"
      :effects [{:effect-id :intensity :params {:amount (envelope :vibe 1.0 0.0 :release 1.0 :curve :linear)}}]}
     {:kind :energy-size :role :any :menu "Energy: size" :name "Energy size"
      :effects [{:effect-id :scale :params (scale energy energy)}]}]))

(def ^:private by-kind (into {} (map (juxt :kind identity)) templates))

(defn template
  "The template of kind, or nil."
  [kind]
  (get by-kind kind))

(defn reaction-cell
  "An effect cell for the reaction kind, off, named after the vibe
   (\"Groove · Punch size\", just the reaction's name without one); its
   effects have no :id yet (h/ensure-item-fields). nil for no such kind."
  [kind vibe-name]
  (when-let [{:keys [name effects]} (template kind)]
    {:name (if vibe-name (str vibe-name " · " name) name)
     :active? false
     :items (mapv #(assoc % :enabled? true) effects)}))

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
   cell, named after it, in its FX set (the Strobe in its flash FX), and on
   now if the vibe is playing and blackout is off, as entering it would have
   switched it on (recorded in [:vibe-runtime :fx-on], so leaving the vibe
   switches it off). Unchanged without the vibe, the kind or a free cell."
  [state vibe-id kind]
  (let [vibe (library/vibe state vibe-id)
        cell (first-empty-effect-cell state)
        made (when vibe (reaction-cell kind (:name vibe)))]
    (if (and made cell)
      (let [flash? (= :flash-fx (:into (template kind)))
            on? (and (not flash?)
                     (= vibe-id (get-in state [:vibe-runtime :current]))
                     (not (get-in state [:vibe-runtime :blackout?])))]
        (cond-> (-> state
                    (assoc-in [:chains :effect-chains cell]
                              (-> made (update :items #(mapv h/ensure-item-fields %)) (assoc :active? on?)))
                    (update-in [:config :vibes :library vibe-id]
                               #(if flash?
                                  (update-in % [:flash :fx] (fnil conj []) cell)
                                  (update % :fx (fnil conj []) cell))))
          on? (update-in [:vibe-runtime :fx-on] (fnil conj []) cell)))
      state)))


;; What reacts to what

(defn- effects-in
  "Every enabled effect in x (a chain, an item, a group, a track, or a
   vector of them), through the enabled ones."
  [x]
  (cond
    (map? x) (when (not= false (:enabled? x))
               (if (:effect-id x)
                 [x]
                 (mapcat effects-in [(:items x) (:effects x) (:tracks x)])))
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

(defn- effect-sources [{:keys [params keyframe-modulator]}]
  (let [{:keys [enabled? restart-on]} keyframe-modulator]
    (cond-> (into #{} (keep modulator-source) (vals params))
      (and enabled? restart-on (not= :own restart-on)) (conj restart-on))))

(defn listens-to
  "The sources any enabled effect in chain follows: a :pulse type's :source,
   a modulator's or a time-driven keyframe animation's :restart-on (#{} for
   none). A chain is an effect cell, a cue chain, or anything holding
   effects."
  [chain]
  (into #{} (mapcat effect-sources) (effects-in chain)))

(def ^:private roles-of
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
   set, its flash FX, the effects grid's cells that are on, then the cues of
   its pools, flash and bed, each cell once per role. {} for no such vibe."
  [state vibe-id]
  (if-let [vibe (library/vibe state vibe-id)]
    (let [effect-chains (get-in state [:chains :effect-chains])
          cue-chains (get-in state [:chains :cue-chains])
          on-grid (sort-by (fn [[col row]] [row col]) (keep (fn [[cell c]] (when (:active? c) cell)) effect-chains))
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
