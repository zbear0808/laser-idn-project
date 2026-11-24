(ns laser-show.vibes.faders-test
  "laser-show.vibes.faders: the Master and Morph templates, where they go,
   and whether a vibe wires a fader (plans/2026-11-18-vibe-mode/05 §7)."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.modulator-registry :as reg]
            [laser-show.state.domains :as domains]
            [laser-show.state.templates :as templates]
            [laser-show.vibes.faders :as faders]))

(defn- starter []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-vibes)
      (templates/apply-starter-effects)))

(defn- fader [slot lo hi default]
  {:type :macro :active? true :slot slot :min lo :max hi :default default})

(deftest templates-are-valid-test
  (is (= [:slider-1 :slider-2] faders/slots))
  (is (= [{:effect-id :intensity :enabled? true :params {:amount (fader :slider-1 0.0 1.0 1.0)}}]
         (mapv #(dissoc % :id) (:items (faders/master-cell)))))
  (is (= {:name "Groove · Morph: Size" :active? false
          :items [{:effect-id :scale :enabled? true
                   :params {:x-scale (fader :slider-2 0.5 1.5 0.5) :y-scale (fader :slider-2 0.5 1.5 0.5) :uniform? false}}]}
         (update (faders/morph-cell :size "Groove") :items (partial mapv #(dissoc % :id)))))
  (is (= [:rotation {:angle (fader :slider-2 0.0 360.0 0.0)}]
         ((juxt :effect-id :params) (first (:items (faders/morph-cell :spin nil))))))
  (is (= [:hue-shift {:degrees (fader :slider-2 -180.0 180.0 0.5)}]
         ((juxt :effect-id :params) (first (:items (faders/morph-cell :colour nil))))))
  (is (= "Morph: Colour" (:name (faders/morph-cell :colour nil))))
  (is (nil? (faders/morph-cell :no-such-kind "Groove")))
  (testing "registered effects and modulators, values in their ranges"
    (doseq [{:keys [effect-id params]} (into (:items (faders/master-cell))
                                             (mapcat #(:items (faders/morph-cell (:kind %) nil)) faders/morph-templates))
            :let [effect (effects/get-effect effect-id)]
            [k v] params
            :when (map? v)
            :let [spec (first (filter #(= k (:key %)) (:parameters effect)))]]
      (is (some? spec) (str effect-id " has a param " k))
      (is (reg/valid-modulator-type? (:type v)))
      (doseq [bound [:min :max]]
        (is (<= (:min spec) (double (get v bound)) (:max spec)) (str effect-id " " k " " bound " within the effect's range")))
      (doseq [{pk :key lo :min hi :max ptype :type choices :choices} (reg/get-params (:type v))
              :let [pv (get v pk)]
              :when (and (some? pv) (not (#{:min :max} pk)))]
        (case ptype
          (:float :int) (is (<= lo (double pv) hi) (str effect-id " " pk " " pv " within " lo "-" hi))
          :choice (is (some #{pv} choices) (str effect-id " " pk " " pv " is a choice"))
          nil)))))

(deftest master-at-test
  (let [s (starter)]
    (is (= [0 3] (faders/master-at s)))
    (is (nil? (faders/master-at (update-in s [:chains :effect-chains] dissoc [0 3]))))
    (is (= [0 3] (faders/master-at (assoc-in s [:chains :effect-chains [0 3] :active?] false)))
        "switched off on the effects grid, it is still the Master")
    (is (nil? (faders/master-at (assoc-in s [:chains :effect-chains [0 3] :items 0 :params :amount :slot] :slider-2)))
        "an Intensity on fader 2 is no Master")
    (is (nil? (faders/master-at (assoc-in s [:chains :effect-chains [0 3] :items 0 :enabled?] false))))))

(deftest slot-wired-test
  (let [s (starter)
        wired? (fn [state vibe slot] (faders/slot-wired? state vibe slot))
        size-cell (faders/morph-cell :size "Shapes")]
    (testing "the Master, on, wires fader 1 in every vibe, and with none"
      (is (wired? s :vibe-1 :slider-1))
      (is (wired? s :vibe-2 :slider-1))
      (is (wired? s nil :slider-1)))
    (testing "invariant 6: fader 2 is wired by nothing until a vibe wires it"
      (is (not (wired? s :vibe-1 :slider-2)))
      (let [morphed (-> s
                        (assoc-in [:chains :effect-chains [1 3]] size-cell)
                        (assoc-in [:config :vibes :library :vibe-1 :fx] [[1 3]]))]
        (is (wired? morphed :vibe-1 :slider-2) "its FX set, off until the vibe plays")
        (is (not (wired? morphed :vibe-2 :slider-2)) "another vibe's")
        (is (wired? (assoc-in morphed [:chains :effect-chains [1 3] :items 0 :params :x-scale :active?] false)
                    :vibe-1 :slider-2)
            "one param's modulator off: the other still follows")
        (is (not (wired? (-> morphed
                             (assoc-in [:chains :effect-chains [1 3] :items 0 :params :x-scale :active?] false)
                             (assoc-in [:chains :effect-chains [1 3] :items 0 :params :y-scale :active?] false))
                         :vibe-1 :slider-2))
            "both off: nothing")
        (is (not (wired? (assoc-in morphed [:chains :effect-chains [1 3] :items 0 :enabled?] false) :vibe-1 :slider-2))
            "the effect disabled")))
    (testing "the flash's effects, a cue of a pool, and an effects-grid cell that is on wire it too"
      (is (wired? (-> s
                      (assoc-in [:chains :effect-chains [2 3]] size-cell)
                      (assoc-in [:config :vibes :library :vibe-2 :flash :fx] [[2 3]]))
                  :vibe-2 :slider-2))
      (is (wired? (assoc-in s [:chains :cue-chains [5 0] :items 0 :effects] (:items size-cell)) :vibe-2 :slider-2)
          "Lines' HIT cue [5 0]")
      (is (not (wired? (assoc-in s [:chains :cue-chains [5 0] :items 0 :effects] (:items size-cell)) :vibe-1 :slider-2))
          "not Shapes'")
      (is (wired? (assoc-in s [:chains :effect-chains [3 3]] (assoc size-cell :active? true)) :vibe-1 :slider-2))
      (is (not (wired? (assoc-in s [:chains :effect-chains [3 3]] size-cell) :vibe-1 :slider-2)) "off, in no vibe"))
    (testing "the Master switched off or deleted: fader 1 does nothing"
      (is (not (wired? (assoc-in s [:chains :effect-chains [0 3] :active?] false) :vibe-1 :slider-1)))
      (is (not (wired? (update-in s [:chains :effect-chains] dissoc [0 3]) :vibe-1 :slider-1))))))


;; The slots follow the bindings (05 §11): derived from the control learned
;; for each fader now, never kept

(deftest fader-values-follow-the-bindings-test
  (let [values {[:midi 0 7] 0.3 [:midi 0 9] 0.0 [:osc "/f"] 0.8}]
    (is (= {:slider-1 0.3} (faders/fader-values values {[:midi 0 7] :slider-1})))
    (is (= {:slider-1 0.3 :slider-2 0.0} (faders/fader-values values {[:midi 0 7] :slider-1 [:midi 0 9] :slider-2}))
        "a fader at 0 is a value")
    (is (= {} (faders/fader-values values {[:midi 0 5] :slider-1}))
        "learned on a control that hasn't moved: no value yet, so its Faders sit at their :default")
    (is (= {} (faders/fader-values values {})) "nothing learned")
    (is (= {} (faders/fader-values values nil)) "a project from before the faders")
    (testing "with-faders: the render's input values, each fader's at [:macro slot] and no other, whatever the store holds"
      (is (= (assoc values [:macro :slider-1] 0.3) (faders/with-faders values {[:midi 0 7] :slider-1})))
      (let [stale (assoc values [:macro :slider-1] 0.0 [:macro :slider-2] 0.0)]
        (is (= (assoc values [:macro :slider-2] 0.8) (faders/with-faders stale {[:osc "/f"] :slider-2})))
        (is (= values (faders/with-faders stale {})) "nothing learned: no slot")
        (is (= values (faders/with-faders stale {[:midi 0 5] :slider-1})) "learned on a control that hasn't moved"))
      (is (identical? values (faders/with-faders values {})) "nothing to derive: the same map, no garbage a tick"))))

(deftest tick-inputs-share-one-lookup-test
  (testing "reactivity plan 27 §7 cases 4 and 5: the meters' levels and the modulators' input values, together"
    (is (= {:faders {:slider-1 0.75}
            :input-values {[:gamepad 0 :trigger-left] 0.75 [:macro :slider-1] 0.75}}
           (faders/tick-inputs {[:gamepad 0 :trigger-left] 0.75} {[:gamepad 0 :trigger-left] :slider-1})))
    (is (= {:faders {} :input-values {[:gamepad 0 :trigger-left] 0.75}}
           (faders/tick-inputs {[:gamepad 0 :trigger-left] 0.75 [:macro :slider-1] 0.0} {}))
        "nothing learned: no level, and the store's stale slot gone")
    (is (= {:faders {:slider-1 0.0} :input-values {[:midi 0 7] 0.0 [:macro :slider-1] 0.0}}
           (faders/tick-inputs {[:midi 0 7] 0.0} {[:midi 0 7] :slider-1}))
        "a fader at 0 is a level")
    (let [values {[:midi 0 7] 0.3}]
      (is (identical? values (:input-values (faders/tick-inputs values nil))) "a project from before the faders: the same map")
      (is (= (faders/with-faders values {[:midi 0 7] :slider-1})
             (:input-values (faders/tick-inputs values {[:midi 0 7] :slider-1})))
          "with-faders is its :input-values"))))
