(ns laser-show.views.components.zone-pattern-editor-test
  "What the pattern editor says (zones plan Z5, 07 §7.1, §7.2): the Pattern
   chip's text and tooltip, the rows each style shows, a cell's tooltip, and
   how a pattern folds onto this rig."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.routing.targets :as targets]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.views.components.zone-pattern-editor :as zpe]))

(def ^:private names
  (targets/names [{:id :all :name "All" :kind :all} {:id :auto/left :name "Left" :kind :auto}
                  {:id :graphics :name "Graphics" :kind :custom}]
                 {}))

(defn- preset [style where & {:as more}]
  (update (zone-patterns/preset-instance style where) :params merge more))

(deftest chip-text-test
  (is (= "None" (zpe/chip-text nil names)) "07 §7.3: Pattern: None")
  (is (= "Alternate · A ⇄ B" (zpe/chip-text (preset :alternate :cue) names)))
  (is (= "Chase · every laser" (zpe/chip-text (preset :chase :cue) names)))
  (is (= "Shift · A → B → C → D" (zpe/chip-text (preset :shift :cue) names)))
  (is (= "None" (zpe/chip-text (assoc (preset :swap :cue) :enabled? false) names)) "a disabled one is none")
  (testing "its tooltip says what it does, in the doc's words"
    (is (= "Zone pattern: Alternate. Moves the All picture A ⇄ B, 1 step a beat, from the cue's start."
           (zpe/chip-tooltip (preset :alternate :cue) names)))
    (is (= "Zone pattern: Chase. Takes turns with what's in the zones across every laser, 2 steps a beat, on the show's beats."
           (zpe/chip-tooltip (preset :chase :cue :source :zones :rate 2.0 :clock :global) names)))
    (is (= "Zone pattern: Swap. Trades what's in A ⇄ B, 1 step on each HIT."
           (zpe/chip-tooltip (preset :swap :cue :step-on :hit) names)))
    (is (= "Zone pattern: Shift. Moves what's in the zones along A → B → C → D, 1 step every 2 beats, from the cue's start, starting again at each FLASH."
           (zpe/chip-tooltip (preset :shift :cue :rate 0.5 :restart-on :flash) names)))
    (is (= "No zone pattern: the cue's pictures stay in the zones they're drawn into. Pick a style to move them on the beat or on a press."
           (zpe/chip-tooltip nil names))))
  (testing "a cell's tooltip (07 §7.2)"
    (is (= "Zone pattern: Chase across every laser, 2 steps a beat (show beats)"
           (zpe/cell-tooltip (:params (preset :chase :cell :rate 2.0)) names)))
    (is (= "Zone pattern: Alternate A ⇄ B, 1 step a beat (each cue from its start)"
           (zpe/cell-tooltip (:params (preset :alternate :cue)) names)))
    (is (= "Zone pattern: Swap A ⇄ B, 1 step on each ACCENT" (zpe/cell-tooltip (:params (preset :swap :cell :step-on :accent)) names)))))

(deftest rows-per-style-test
  (let [base [:style :follow-pads? :zones]]
    (is (= (into base [:source :rate :step-on :clock :restart-on :offset]) (zpe/rows :alternate :clock))
        "Alternate: Moves; Count from and Restart on while Step on is the beat")
    (is (= (into base [:source :rate :step-on :clock :restart-on :offset :path :width]) (zpe/rows :chase :clock)) "Chase: Path and Width")
    (is (= (into base [:source :rate :step-on :clock :restart-on :offset :width]) (zpe/rows :ping-pong :clock)) "Ping-pong: Width")
    (is (= (into base [:source :rate :step-on :clock :restart-on :offset :width :seed]) (zpe/rows :random :clock)) "Random: Width and Seed")
    (is (= (into base [:rate :step-on :clock :restart-on :offset]) (zpe/rows :shift :clock)) "Shift moves what's in the zones: no Moves")
    (is (= (into base [:rate :step-on :offset]) (zpe/rows :swap :hit)) "on a press: no Count from, no Restart on")
    (is (= :pair (zpe/lane-mode :swap)) "Swap's zones are two pickers")
    (is (= :list (zpe/lane-mode :chase))))
  (testing "a Map cycle's"
    (is (= [:maps :rate :path :step-on :offset] (zpe/cycle-rows)))))

(defn- lasers [n]
  (into {} (for [k (range 1 (inc n))]
             [(keyword (str "p" k)) {:name (str "Laser " k) :host (str "10.0.0." k) :enabled? true :zone-groups []}])))

(deftest fold-text-test
  (let [state (fn [n & {:as more}] (merge {:projectors (lasers n) :zone-map {:active :map/left-right}} more))
        ab (:params (preset :alternate :cue))]
    (is (= "This rig (1 laser, Left / Right): A is its left half, B its right half." (zpe/fold-text (state 1) ab names)))
    (is (= "This rig (3 lasers, Left / Right): A is Laser 1 and the left half of Laser 2; B the right half of Laser 2 and Laser 3."
           (zpe/fold-text (state 3) ab names)))
    (is (= "This rig (2 lasers, Odd / Even): A is Laser 1; B Laser 2." (zpe/fold-text (state 2 :zone-map {:active :map/odd-even}) ab names)))
    (is (= "Under Unison every zone is All, so this pattern doesn't move." (zpe/fold-text (state 3 :zone-map {:active :map/unison}) ab names)))
    (is (= "This rig (3 lasers): Laser 1, Laser 2 and Laser 3, left to right." (zpe/fold-text (state 3) (:params (preset :chase :cue)) names)))
    (is (= "This rig (1 laser): its left half, then its right half." (zpe/fold-text (state 1) (:params (preset :chase :cue)) names)))
    (is (= "No laser is on, so this pattern has nowhere to send anything." (zpe/fold-text (state 0) ab names)))
    (is (= "This rig (2 lasers, Left / Right): A is Laser 1; Graphics nothing."
           (zpe/fold-text (state 2 :zone-groups {:graphics {:name "Graphics"}}) (assoc ab :zones [:zone/a :graphics]) names))
        "a place that reaches no laser says so")))
