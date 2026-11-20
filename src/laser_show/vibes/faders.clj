(ns laser-show.vibes.faders
  "The two faders (plans/2026-11-18-vibe-mode/05): 1 Intensity, a master
   dimmer, and 2 Morph, whatever the vibe that plays wires to it. A fader is
   a slot, :slider-1 or :slider-2: the control learned for it ([:config
   :input :macro-map], handlers/input.clj) writes its value to [:macro slot]
   in the input store too (the input mapper), and a Fader modulator
   ({:type :macro :slot}) on any parameter follows it.

   Here: the cells that wire them in one click, the Master (an effects-grid
   cell, at [0 3] in a new project, state.templates) and the Morphs (in a
   vibe's FX set), each neutral until its fader first moves; where they go,
   the first empty effect cell, as a tap reaction's (vibes.reactions); and
   whether a vibe wires a fader, for the Perform tab's meters."
  (:require [laser-show.events.helpers :as h]
            [laser-show.vibes.library :as library]
            [laser-show.vibes.reactions :as reactions]))

(def slots
  "The two faders: 1 Intensity, 2 Morph."
  [:slider-1 :slider-2])

(defn- fader
  "A Fader modulator: lo..hi as the fader on slot goes 0..1, at default
   (0..1) until it first moves."
  [slot lo hi default]
  {:type :macro :active? true :slot slot :min lo :max hi :default default})

(defn master-cell
  "The Master, with fresh ids: an effect cell, on, whose Intensity follows
   fader 1, full until the fader first moves."
  []
  {:name "Master" :active? true
   :items [(h/ensure-item-fields {:effect-id :intensity :enabled? true
                                  :params {:amount (fader :slider-1 0.0 1.0 1.0)}})]})

(def morph-templates
  "What fader 2 can morph, in menu order: {:kind :menu :name :effects}. Each
   is neutral until the fader first moves: Size at 1.0x, Spin at 0°, Colour
   at 0° of hue."
  (let [size (fader :slider-2 0.5 1.5 0.5)]
    [{:kind :size :menu "Size" :name "Morph: Size"
      :effects [{:effect-id :scale :params {:x-scale size :y-scale size :uniform? false}}]}
     {:kind :spin :menu "Spin" :name "Morph: Spin"
      :effects [{:effect-id :rotation :params {:angle (fader :slider-2 0.0 360.0 0.0)}}]}
     {:kind :colour :menu "Colour" :name "Morph: Colour"
      :effects [{:effect-id :hue-shift :params {:degrees (fader :slider-2 -180.0 180.0 0.5)}}]}]))

(def ^:private by-kind (into {} (map (juxt :kind identity)) morph-templates))

(defn morph-cell
  "An effect cell for the Morph kind, off, with fresh ids, named after the
   vibe (\"Groove · Morph: Size\", just the Morph's name without one). nil
   for no such kind."
  [kind vibe-name]
  (when-let [{:keys [name effects]} (by-kind kind)]
    {:name (if vibe-name (str vibe-name " · " name) name)
     :active? false
     :items (mapv #(h/ensure-item-fields (assoc % :enabled? true)) effects)}))

(defn- follows?
  "Is v a Fader modulator on slot that is on?"
  [slot v]
  (and (map? v) (= :macro (:type v)) (not= false (:active? v))
       (= slot (get v :slot (first slots)))))

(defn- wires?
  "Does an enabled effect in chain have a Fader on slot that is on?"
  [chain slot]
  (boolean (some #(some (partial follows? slot) (vals (:params %))) (reactions/effects-in chain))))

(defn master-at
  "The effect cell holding a Master, an Intensity whose amount follows fader
   1 (on or off on the effects grid), or nil."
  [state]
  (first (for [[cell chain] (get-in state [:chains :effect-chains])
               :when (some #(and (= :intensity (:effect-id %)) (follows? :slider-1 (get-in % [:params :amount])))
                           (reactions/effects-in chain))]
           cell)))

(defn add-master
  "state with a Master in the first empty effect cell (reactions/
   first-empty-effect-cell). Unchanged while there is one (master-at), so
   the fader never dims twice, or without a free cell."
  [state]
  (let [cell (reactions/first-empty-effect-cell state)]
    (if (and cell (nil? (master-at state)))
      (assoc-in state [:chains :effect-chains cell] (master-cell))
      state)))

(defn add-morph
  "state with the Morph kind made for the vibe: in the first empty effect
   cell, named after it, off, and in its FX set, which switches it on while
   the vibe plays (the handler reconciles what plays,
   engine/reconcile). Unchanged without the vibe, the kind or a free cell."
  [state vibe-id kind]
  (let [vibe (library/vibe state vibe-id)
        cell (reactions/first-empty-effect-cell state)
        made (when vibe (morph-cell kind (:name vibe)))]
    (if (and made cell)
      (-> state
          (assoc-in [:chains :effect-chains cell] made)
          (update-in [:config :vibes :library vibe-id :fx] (fnil conj []) cell))
      state)))

(defn slot-wired?
  "Does moving the fader on slot change what plays in vibe-id (nil: none)?
   A Fader on slot, on, in an enabled effect of its FX set or its flash
   effects, of an effects-grid cell that is on, or of a cue of its pools,
   flash or bed. A fader nothing follows says \"not wired\" (05 invariant
   6)."
  [state vibe-id slot]
  (let [vibe (library/vibe state vibe-id)
        effect-chains (get-in state [:chains :effect-chains])
        cue-chains (get-in state [:chains :cue-chains])]
    (boolean
     (some #(wires? % slot)
           (concat (keep #(get effect-chains %) (concat (:fx vibe) (get-in vibe [:flash :fx])))
                   (filter :active? (vals effect-chains))
                   (keep #(get cue-chains %) (concat (get-in vibe [:hit :cells]) (get-in vibe [:accent :cells])
                                                     (keep identity [(get-in vibe [:flash :cue]) (:bed vibe)]))))))))
