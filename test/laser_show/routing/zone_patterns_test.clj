(ns laser-show.routing.zone-patterns-test
  "The zone patterns' model (zones plan Z5, 07 §4.2, §8.2, §8.6, §8.8): the
   presets, what a pattern says of itself, the Map cycle's pick, who wins
   between cycles, how far a pattern reaches, its warnings, and the pattern
   pads' live style and its timing."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.pulses :as pulses]
            [laser-show.routing.targets :as targets]
            [laser-show.routing.zone-patterns :as zp]))

(def ^:private abcd [:zone/a :zone/b :zone/c :zone/d])

(def ^:private names
  (targets/names [{:id :all :name "All" :kind :all}
                  {:id :auto/left :name "Left" :kind :auto} {:id :auto/right :name "Right" :kind :auto}
                  {:id :auto.laser/p1 :name "Laser 1" :kind :laser} {:id :auto.laser/p2 :name "Laser 2" :kind :laser}
                  {:id :graphics :name "Graphics" :kind :custom}]
                 {:zone/a "Beams"}))

(defn- lasers
  "n enabled projectors :p1 … :pn, in that order."
  [n]
  (into {} (for [k (range 1 (inc n))]
             [(keyword (str "p" k)) {:name (str "Laser " k) :host (str "10.0.0." k) :enabled? true :zone-groups []}])))


;; Presets (07 §8.2)

(deftest presets-are-valid-test
  (let [params-by-key (into {} (map (juxt :key identity)) zp/pattern-parameters)]
    (is (= [:alternate :chase :ping-pong :random :shift :swap] zp/styles))
    (doseq [style zp/styles
            where [:cue :cell]]
      (testing (str style " " where)
        (let [{:keys [effect-id enabled? params]} (zp/preset-instance style where)]
          (is (= :zone-pattern effect-id))
          (is (true? enabled?))
          (is (= (set (keys params-by-key)) (set (keys params))) "every parameter has a value")
          (is (= style (:style params)))
          (is (= (if (= :cue where) :cue :global) (:clock params)) "a cue's counts from its start (Z5-Q6 A), a cell's the show's beats")
          (doseq [[k v] params
                  :let [{:keys [type choices min max]} (params-by-key k)]]
            (case type
              :choice (is (some #{v} choices) (str k " is one of its choices"))
              (:float :int) (is (<= min v max) (str k " is in range"))
              :bool (is (boolean? v) (str k))
              :ref-list (is (and (vector? v) (every? keyword? v)) (str k))
              :ref (is (keyword? v) (str k))
              (is false (str k " has no type")))))))
    (is (= [[:zone/a :zone/b] [:lane/lasers] [:lane/lasers] [:lane/lasers] abcd [:zone/a :zone/b]]
           (mapv #(get-in (zp/preset-instance % :cue) [:params :zones]) zp/styles))
        "the default lanes")
    (testing "every choice a modulator may drive says so, and has a label"
      (is (= #{:style :source :path} (set (keep #(when (:modulatable? %) (:key %)) zp/pattern-parameters))))
      (doseq [p zp/pattern-parameters :when (= :choice (:type p))]
        (is (every? #(string? (get (:labels p) %)) (:choices p)) (str (:key p) " names each choice"))))
    (testing "Step on: the beat or any pulse source"
      (is (= (into [:clock] pulses/sources) zp/step-sources))
      (is (= zp/step-sources (:choices (params-by-key :step-on))))
      (is (= (into [:own] pulses/sources) (:choices (params-by-key :restart-on))))
      (is (= "The beat" (get-in params-by-key [:step-on :labels :clock])))
      (is (= "HIT" (get-in params-by-key [:step-on :labels :hit])))
      (is (= "No" (get-in params-by-key [:restart-on :labels :own])) "a pattern's Restart on says No, not Its cue's start"))
    (testing "the Map cycle's"
      (is (= {:maps [:map/left-right :map/odd-even] :rate 0.25 :offset 0.0 :path :loop :step-on :clock}
             (:params (zp/cycle-instance))))
      (is (= :zone-map-cycle (:effect-id (zp/cycle-instance)))))))

(deftest describe-test
  (is (= "Alternate · A ⇄ B · 1 a beat" (zp/describe {:style :alternate :zones [:zone/a :zone/b] :rate 1.0} names)))
  (is (= "Alternate · A C ⇄ B D · 1 a beat" (zp/describe {:style :alternate :zones abcd :rate 1.0} names)))
  (is (= "Chase · every laser · 2 a beat" (zp/describe {:style :chase :zones [:lane/lasers] :rate 2.0} names)))
  (is (= "Shift · A → B → C → D · 1 every 2 beats" (zp/describe {:style :shift :zones abcd :rate 0.5} names)))
  (is (= "Swap · A ⇄ B · on each HIT" (zp/describe {:style :swap :zones [:zone/a :zone/b] :step-on :hit :rate 1.0} names)))
  (is (= "Random · every laser → Graphics · 2 on each Zone step" (zp/describe {:style :random :zones [:lane/lasers :graphics] :step-on :zone-step :rate 2.0} names)))
  (is (= "Ping-pong · Left → Right · 1 a beat, backwards" (zp/describe {:style :ping-pong :zones [:auto/left :auto/right] :rate -1.0} names)))
  (is (= "Chase · no zones · still" (zp/describe {:style :chase :zones [] :rate 0.0} names)))
  (is (= "Chase · A → Front (missing) · 1 a beat" (zp/describe {:style :chase :zones [:zone/a :front]} names)) "a group that's gone says so")
  (testing "a Map cycle (07 §7.4)"
    (is (= "Map cycle: Left / Right, Odd / Even, a bar each"
           (zp/describe-cycle {:maps [:map/left-right :map/odd-even] :rate 0.25} {})))
    (is (= "Map cycle: Unison, Sweep, a beat each"
           (zp/describe-cycle {:maps [:map/unison :map/custom-1] :rate 1.0} {:zone-maps {:map/custom-1 {:name "Sweep" :lanes [:all]}}})))
    (is (= "Map cycle: Left / Right, Odd / Even, 2 bars each" (zp/describe-cycle {:maps [:map/left-right :map/odd-even] :rate 0.125} {})))
    (is (= "Map cycle: Left / Right, Odd / Even, one on each HIT"
           (zp/describe-cycle {:maps [:map/left-right :map/odd-even] :rate 1.0 :step-on :hit} {})))
    (is (= "Map cycle: no maps" (zp/describe-cycle {:maps [:map/gone]} {})))))


;; The Map cycle (07 §8.6)

(def ^:private two-maps {:maps [:map/left-right :map/odd-even] :rate 0.25 :offset 0.0 :path :loop :step-on :clock})

(def ^:private known-maps [:map/left-right :map/odd-even :map/unison :map/custom-1])

(deftest cycle-pick-test
  (testing "a bar each, on the show's beats"
    (is (= [:map/left-right :map/left-right :map/odd-even :map/odd-even :map/left-right]
           (mapv #(zp/cycle-pick two-maps nil % known-maps) [0.0 3.99 4.0 7.99 8.0]))))
  (testing "an unknown map is dropped; none left, no pick"
    (is (= :map/odd-even (zp/cycle-pick (assoc two-maps :maps [:map/gone :map/odd-even]) nil 4.0 known-maps)))
    (is (nil? (zp/cycle-pick (assoc two-maps :maps [:map/gone]) nil 0.0 known-maps)))
    (is (nil? (zp/cycle-pick (assoc two-maps :maps []) nil 0.0 known-maps))))
  (testing "the offset and the path"
    (is (= :map/odd-even (zp/cycle-pick (assoc two-maps :offset 1.0) nil 0.0 known-maps)))
    (is (= [:map/left-right :map/odd-even :map/unison :map/odd-even :map/left-right]
           (mapv #(zp/cycle-pick {:maps [:map/left-right :map/odd-even :map/unison] :rate 1.0 :path :bounce} nil % known-maps)
                 [0.0 1.0 2.0 3.0 4.0]))))
  (testing "a step on each press of a source, from its count"
    (let [view (fn [n] {:hit {:count n :beats 0.5}})]
      (is (= [:map/left-right :map/odd-even :map/left-right]
             (mapv #(zp/cycle-pick (assoc two-maps :step-on :hit :rate 1.0) (when (pos? %) (view %)) 99.0 known-maps) [0 1 2])))))
  (testing "a bad rate or offset (a modulator not resolved) counts as the default"
    (is (= :map/left-right (zp/cycle-pick (assoc two-maps :rate {:type :sine} :offset nil) nil 2.0 known-maps)))))

(defn- fx-cell [active? & effects]
  {:active? active? :items (vec effects)})

(defn- cycle-fx [maps & {:as more}]
  (merge {:effect-id :zone-map-cycle :enabled? true :params (assoc two-maps :maps maps)} more))

(deftest last-cycle-in-reading-order-wins-test
  (let [state {:chains {:effect-chains {[2 0] (fx-cell true (cycle-fx [:map/unison]))
                                        [0 1] (fx-cell true (cycle-fx [:map/odd-even]))
                                        [1 1] (fx-cell false (cycle-fx [:map/custom-1]))
                                        [0 3] (fx-cell true {:effect-id :intensity :enabled? true :params {}})}}}]
    (is (= {:cell [0 1] :params (assoc two-maps :maps [:map/odd-even])}
           (select-keys (zp/active-cycle state) [:cell :params]))
        "row by row, left to right: [2 0] is read before [0 1], so [0 1] wins; an off cell doesn't count")
    (is (= :map/odd-even (get-in (zp/active-cycle state) [:effect :params :maps 0])) "with its effect, for its keyframes")
    (is (= [[2 0] [0 1]] (mapv :cell (zp/active-cycles state))) "every one that runs, in reading order")
    (testing "in one cell, the later in its chain"
      (is (= [:map/unison] (get-in (zp/active-cycle {:chains {:effect-chains {[0 0] (fx-cell true (cycle-fx [:map/odd-even]) (cycle-fx [:map/unison]))}}})
                                   [:params :maps]))))
    (testing "a disabled one, or one in a disabled group, doesn't run"
      (is (nil? (zp/active-cycle {:chains {:effect-chains {[0 0] (fx-cell true (cycle-fx [:map/unison] :enabled? false))}}})))
      (is (nil? (zp/active-cycle {:chains {:effect-chains {[0 0] (fx-cell true {:type :group :enabled? false :items [(cycle-fx [:map/unison])]})}}})))
      (is (= [:map/unison] (get-in (zp/active-cycle {:chains {:effect-chains {[0 0] (fx-cell true {:type :group :items [(cycle-fx [:map/unison])]})}}})
                                   [:params :maps]))
          "an enabled group's does"))
    (is (nil? (zp/active-cycle {})) "none")))


;; Reach (07 §8.8)

(deftest reach-is-the-whole-lane-test
  (let [state {:projectors (assoc (lasers 4) :p5 {:name "Crowd" :host "10.0.0.5" :enabled? true :zone-groups [] :tags #{:crowd-scanning}})
               :zone-groups {:graphics {:name "Graphics"}}}]
    (is (= [:auto.laser/p1 :auto.laser/p2 :auto.laser/p3 :auto.laser/p4 :auto.laser/p5]
           (zp/reach state {:zones [:lane/lasers]}))
        "Every laser: each one in All, in rig order")
    (is (= [:auto.laser/p1 :auto.laser/p2 :auto.laser/p3 :auto.laser/p4]
           (zp/reach (assoc-in state [:projectors :p5 :exclude-from-all?] true) {:zones [:lane/lasers]}))
        "one excluded from All isn't one of every laser (Q-Z2-1 B)")
    (is (= [:auto.laser/p1 :auto.laser/p2 :auto.laser/p3 :auto.laser/p4 :auto.laser/p5]
           (zp/reach (assoc-in state [:projectors :p2 :enabled?] false) {:zones [:lane/lasers]}))
        "one that is off still counts: it can be turned on mid-show")
    (is (= [:zone/a :zone/b] (zp/reach state {:style :swap :zones [:zone/a :zone/b] :from :crowd}))
        "the whole lane, whatever the style; :from doesn't count")
    (is (= [:auto/left :graphics] (zp/reach state {:zones [:auto/left :front :graphics :auto/left]})) "unknown and repeated refs go")
    (is (= [:auto/left :auto/right] (zp/reach {:projectors (lasers 1)} {:zones [:lane/lasers]})) "one laser: its halves")))


;; Warnings (07 §8.8, as the pattern window says them)

(deftest warnings-test
  (let [state {:projectors (lasers 2)
               :zone-groups {:graphics {:name "Graphics"}}
               :zone-map {:active :map/left-right}
               :config {:input {:trigger-map {}}}}
        texts (fn [s params] (mapv (juxt :level :text) (zp/warnings s params names)))]
    (is (= [] (texts state {:style :alternate :zones [:zone/a :zone/b]})) "nothing to say")
    (is (= [[:error "This pattern needs two zones (it has one: A · Beams)"]] (texts state {:style :alternate :zones [:zone/a]})))
    (is (= [[:error "This pattern needs two zones (it has none)"]] (texts state {:style :chase :zones []})))
    (is (= [[:warn "This pattern lists Front, which no longer exists"]] (texts state {:style :chase :zones [:zone/a :zone/b :front]})))
    (is (= [[:warn "This pattern: Graphics has no laser, so the picture vanishes on that step"]]
           (texts state {:style :chase :zones [:zone/a :graphics]}))
        "a group no laser is in")
    (is (= [[:info "This pattern doesn't move under Unison"]]
           (texts (assoc-in state [:zone-map :active] :map/unison) {:style :alternate :zones [:zone/a :zone/b]})))
    (is (= [] (texts (assoc-in state [:zone-map :active] :map/unison) {:style :chase :zones [:lane/lasers]}))
        "every laser doesn't use zones: the map doesn't matter")
    (is (= [[:info "This pattern steps on the Zone step key, which has no key yet"]]
           (texts state {:style :chase :zones [:zone/a :zone/b] :step-on :zone-step})))
    (is (= [] (texts (assoc-in state [:config :input :trigger-map {:source :midi :channel 0 :note 41}] {:type :zone-step})
                     {:style :chase :zones [:zone/a :zone/b] :step-on :zone-step}))
        "learned: nothing to say")
    (is (= ["B3 (Zone pattern) needs two zones (it has none)"]
           (mapv :text (zp/warnings state {:style :shift :zones []} names "B3 (Zone pattern)")))
        "the lint names its own subject")))


;; The pattern pads (07 §7.3, spec §9)

(defn- pads-state [& {:as more}]
  (merge {:pattern-pads {:style nil :pending nil} :timing {:bpm 120.0} :playback {:playing? true}} more))

(defn- clock [beats & {:as more}] (merge {:beats beats :running? true :now-ms 0 :id :p1} more))

(deftest schedule-style-test
  (testing "within 50 ms of a beat: now"
    (is (= {:style :chase :pending nil} (:pattern-pads (zp/schedule-style (pads-state) :chase false (clock 16.04))))))
  (testing "otherwise the next beat"
    (is (= {:style nil :pending {:id :p1 :style :chase :at-beat 17.0 :bar? false}}
           (:pattern-pads (zp/schedule-style (pads-state) :chase false (clock 16.5))))))
  (testing "the next bar with Shift"
    (is (= {:id :p1 :style :off :at-beat 20.0 :bar? true}
           (get-in (zp/schedule-style (pads-state) :off true (clock 17.0)) [:pattern-pads :pending]))))
  (let [waiting (zp/schedule-style (pads-state) :chase false (clock 16.5))]
    (testing "a new press replaces the waiting one"
      (is (= :swap (get-in (zp/schedule-style waiting :swap false (clock 16.6)) [:pattern-pads :pending :style]))))
    (testing "pressing the active pad cancels it"
      (is (= {:style nil :pending nil} (:pattern-pads (zp/schedule-style waiting nil false (clock 16.6)))) "As set is nil"))
    (testing "one already due lands first"
      (is (= {:style :chase :pending nil} (:pattern-pads (zp/schedule-style waiting :chase false (clock 17.2)))))))
  (testing "no clock, the transport stopped, no tempo: at once"
    (is (= :chase (get-in (zp/schedule-style (pads-state) :chase false (clock 16.5 :running? false)) [:pattern-pads :style])))
    (is (= :chase (get-in (zp/schedule-style (pads-state :playback {:playing? false}) :chase false (clock 16.5)) [:pattern-pads :style])))
    (is (= :chase (get-in (zp/schedule-style (pads-state :timing {:bpm 0.0}) :chase false (clock 16.5)) [:pattern-pads :style]))))
  (testing "a pad's id is its style; As set's is nil"
    (is (= [:as-set :off :alternate :chase :ping-pong :random :shift :swap] zp/pad-ids))
    (is (= [nil :off :chase] (mapv zp/pad-style [:as-set :off :chase])))
    (is (= [:as-set :off :chase] (mapv zp/style-pad [nil :off :chase])))))

(deftest live-style-is-pending-once-due-test
  (let [s (pads-state :pattern-pads {:style :chase :pending {:id :p1 :style :off :at-beat 33.0 :bar? false}})]
    (is (= :chase (zp/live-style s 32.9)) "not yet")
    (is (= :off (zp/live-style s 33.0)) "from its beat")
    (is (= :off (zp/live-style s (- 33.0 1e-12))) "a hair before counts")
    (is (= :off (zp/live-style s 40.0)) "until it is committed")
    (is (nil? (zp/live-style (pads-state) 3.0)) "As set")
    (is (nil? (zp/live-style {} 3.0)) "nothing set")
    (testing "a commit lands it"
      (is (= {:style :off :pending nil} (:pattern-pads (zp/commit-style s :off)))))))
