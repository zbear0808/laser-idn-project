(ns laser-show.routing.zone-effects-test
  (:require [clojure.test :refer :all]
            [laser-show.routing.zone-effects :as ze]))


;; Zone Effect Identification Tests


(deftest zone-effect?-test
  (testing "identifies zone effects"
    (is (ze/zone-effect? {:effect-id :zone-reroute}))
    (is (ze/zone-effect? {:effect-id :zone-broadcast}))
    (is (ze/zone-effect? {:effect-id :zone-mirror})))
  
  (testing "rejects non-zone effects"
    (is (not (ze/zone-effect? {:effect-id :scale})))
    (is (not (ze/zone-effect? {:effect-id :color-cycle})))
    (is (not (ze/zone-effect? {:effect-id :rotate})))
    (is (not (ze/zone-effect? nil)))))


(deftest extract-zone-effects-test
  (testing "extracts only enabled zone effects"
    (let [effects [{:effect-id :scale :enabled? true}
                   {:effect-id :zone-reroute :enabled? true :params {:mode :replace}}
                   {:effect-id :zone-broadcast :enabled? false}
                   {:effect-id :color-cycle :enabled? true}]]
      (is (= [{:effect-id :zone-reroute :enabled? true :params {:mode :replace}}]
             (ze/extract-zone-effects effects)))))
  
  (testing "treats missing :enabled? as true"
    (let [effects [{:effect-id :zone-reroute :params {:mode :add}}
                   {:effect-id :zone-broadcast}]]
      (is (= 2 (count (ze/extract-zone-effects effects))))))
  
  (testing "returns empty vector for no zone effects"
    (let [effects [{:effect-id :scale :enabled? true}
                   {:effect-id :rotate :enabled? true}]]
      (is (= [] (ze/extract-zone-effects effects)))))
  
  (testing "preserves order of effects"
    (let [effects [{:effect-id :zone-reroute :params {:mode :replace}}
                   {:effect-id :zone-broadcast}
                   {:effect-id :zone-mirror :params {:source-group :left}}]]
      (is (= [:zone-reroute :zone-broadcast :zone-mirror]
             (mapv :effect-id (ze/extract-zone-effects effects)))))))


;; Replace Mode Tests


(deftest apply-replace-mode-test
  (testing "replace mode overrides target"
    (is (= #{:right}
           (ze/apply-replace-mode #{:left} {:target-zone-groups [:right]}))))
  
  (testing "replace with multiple targets"
    (is (= #{:left :center :right}
           (ze/apply-replace-mode #{:all} {:target-zone-groups [:left :center :right]}))))
  
  (testing "defaults to :all when no target specified"
    (is (= #{:all}
           (ze/apply-replace-mode #{:left} {})))))


;; Add Mode Tests


(deftest apply-add-mode-test
  (testing "add mode unions targets"
    (is (= #{:left :right}
           (ze/apply-add-mode #{:left} {:target-zone-groups [:right]}))))
  
  (testing "add preserves existing targets"
    (is (= #{:left :center :right}
           (ze/apply-add-mode #{:left :center} {:target-zone-groups [:right]}))))
  
  (testing "add with empty params does nothing"
    (is (= #{:left}
           (ze/apply-add-mode #{:left} {}))))
  
  (testing "add handles duplicate targets"
    (is (= #{:left :right}
           (ze/apply-add-mode #{:left} {:target-zone-groups [:left :right]})))))


;; Filter Mode Tests


(deftest apply-filter-mode-test
  (testing "filter mode intersects targets"
    (is (= #{:center}
           (ze/apply-filter-mode #{:left :center} {:target-zone-groups [:center :right]}))))
  
  (testing "filter with no overlap returns empty set"
    (is (= #{}
           (ze/apply-filter-mode #{:left} {:target-zone-groups [:right]}))))
  
  (testing "filter with full overlap preserves all"
    (is (= #{:left :center}
           (ze/apply-filter-mode #{:left :center} {:target-zone-groups [:left :center :right]})))))


;; Broadcast Effect Tests


(deftest apply-broadcast-test
  (testing "broadcast replaces with :all"
    (is (= #{:all}
           (ze/apply-broadcast #{:left} {}))))
  
  (testing "broadcast replaces any target"
    (is (= #{:all}
           (ze/apply-broadcast #{:left :right :center} {})))))


;; Mirror Effect Tests


(deftest apply-mirror-test
  (testing "mirror swaps left to right"
    (is (= #{:right}
           (ze/apply-mirror #{:left} {:source-group :left :include-original? false}))))
  
  (testing "mirror swaps right to left"
    (is (= #{:left}
           (ze/apply-mirror #{:right} {:source-group :right :include-original? false}))))
  
  (testing "mirror with include-original adds instead of replacing"
    (is (= #{:left :right}
           (ze/apply-mirror #{:left} {:source-group :left :include-original? true}))))
  
  (testing "mirror does nothing for non-matching source"
    (is (= #{:center}
           (ze/apply-mirror #{:center} {:source-group :left :include-original? false}))))
  
  (testing "mirror with unknown group passes through"
    (is (= #{:custom}
           (ze/apply-mirror #{:custom} {:source-group :custom :include-original? false})))))


;; Resolve Final Target Tests


(deftest resolve-final-target-test
  (testing "returns base destination when no effects"
    (is (= #{:left}
           (ze/resolve-final-target {:zone-group-id :left} []))))
  
  (testing "defaults to :all when no destination"
    (is (= #{:all}
           (ze/resolve-final-target nil []))))
  
  (testing "defaults to :all when empty destination"
    (is (= #{:all}
           (ze/resolve-final-target {} []))))
  
  (testing "processes single zone effect"
    (let [effects [{:effect-id :zone-reroute 
                    :enabled? true
                    :params {:mode :replace :target-zone-groups [:right]}}]]
      (is (= #{:right}
             (ze/resolve-final-target {:zone-group-id :left} effects)))))
  
  (testing "processes multiple zone effects in sequence"
    (let [effects [{:effect-id :zone-reroute 
                    :enabled? true
                    :params {:mode :replace :target-zone-groups [:left :right]}}
                   {:effect-id :zone-reroute 
                    :enabled? true
                    :params {:mode :filter :target-zone-groups [:left]}}]]
      ;; Start with :center, replace with [:left :right], filter to [:left]
      (is (= #{:left}
             (ze/resolve-final-target {:zone-group-id :center} effects)))))
  
  (testing "disabled effects are skipped"
    (let [effects [{:effect-id :zone-reroute 
                    :enabled? false
                    :params {:mode :replace :target-zone-groups [:right]}}]]
      (is (= #{:left}
             (ze/resolve-final-target {:zone-group-id :left} effects)))))
  
  (testing "ignores non-zone effects"
    (let [effects [{:effect-id :scale :enabled? true :params {:x 2}}
                   {:effect-id :zone-reroute :enabled? true :params {:mode :replace :target-zone-groups [:center]}}
                   {:effect-id :rotate :enabled? true :params {:angle 45}}]]
      (is (= #{:center}
             (ze/resolve-final-target {:zone-group-id :left} effects))))))


(deftest zone-broadcast-in-resolve-test
  (testing "broadcast replaces with :all"
    (is (= #{:all}
           (ze/resolve-final-target 
             {:zone-group-id :left}
             [{:effect-id :zone-broadcast :enabled? true}])))))


(deftest zone-mirror-in-resolve-test
  (testing "mirror swaps left to right in resolution"
    (is (= #{:right}
           (ze/resolve-final-target
             {:zone-group-id :left}
             [{:effect-id :zone-mirror 
               :enabled? true
               :params {:source-group :left :include-original? false}}]))))
  
  (testing "mirror with include-original in resolution"
    (is (= #{:left :right}
           (ze/resolve-final-target
             {:zone-group-id :left}
             [{:effect-id :zone-mirror 
               :enabled? true
               :params {:source-group :left :include-original? true}}])))))


;; Collect Effects From Cue Chain Tests


(deftest collect-effects-from-cue-chain-test
  (testing "collects effects from simple preset"
    (let [items [{:type :preset
                  :preset-id :circle
                  :enabled? true
                  :effects [{:effect-id :zone-reroute :params {:mode :replace}}]}]]
      (is (= [{:effect-id :zone-reroute :params {:mode :replace}}]
             (ze/collect-effects-from-cue-chain items)))))
  
  (testing "collects effects from multiple presets"
    (let [items [{:type :preset
                  :preset-id :circle
                  :enabled? true
                  :effects [{:effect-id :scale}]}
                 {:type :preset
                  :preset-id :square
                  :enabled? true
                  :effects [{:effect-id :zone-broadcast}]}]]
      (is (= [{:effect-id :scale} {:effect-id :zone-broadcast}]
             (ze/collect-effects-from-cue-chain items)))))
  
  (testing "skips disabled items"
    (let [items [{:type :preset
                  :preset-id :circle
                  :enabled? false
                  :effects [{:effect-id :zone-reroute}]}
                 {:type :preset
                  :preset-id :square
                  :enabled? true
                  :effects [{:effect-id :zone-broadcast}]}]]
      (is (= [{:effect-id :zone-broadcast}]
             (ze/collect-effects-from-cue-chain items)))))
  
  (testing "collects effects from nested groups"
    (let [items [{:type :group
                  :enabled? true
                  :effects [{:effect-id :scale}]
                  :items [{:type :preset
                           :preset-id :circle
                           :enabled? true
                           :effects [{:effect-id :zone-reroute}]}]}]]
      ;; Group effects + nested preset effects
      (is (= [{:effect-id :scale} {:effect-id :zone-reroute}]
             (ze/collect-effects-from-cue-chain items)))))
  
  (testing "handles empty effects"
    (let [items [{:type :preset
                  :preset-id :circle
                  :enabled? true
                  :effects []}]]
      (is (= []
             (ze/collect-effects-from-cue-chain items)))))
  
  (testing "handles items without :enabled? (defaults to true)"
    (let [items [{:type :preset
                  :preset-id :circle
                  :effects [{:effect-id :scale}]}]]
      (is (= [{:effect-id :scale}]
             (ze/collect-effects-from-cue-chain items))))))


;; Integration Test: Complete Routing Scenario


(deftest integration-routing-scenario-test
  (testing "complete routing scenario with destination zone and zone effects"
    (let [;; Cue chain with destination :left and a zone-broadcast effect
          cue-chain-items [{:type :preset
                           :preset-id :circle
                           :enabled? true
                           :effects [{:effect-id :zone-broadcast :enabled? true}]}]
          destination {:zone-group-id :left}
          collected-effects (ze/collect-effects-from-cue-chain cue-chain-items)
          final-target (ze/resolve-final-target destination collected-effects)]
      ;; Should route to :all because zone-broadcast overrides destination
      (is (= #{:all} final-target))))
  
  (testing "chained zone effects scenario"
    (let [cue-chain-items [{:type :preset
                           :preset-id :circle
                           :enabled? true
                           :effects [{:effect-id :zone-reroute 
                                     :enabled? true
                                     :params {:mode :replace :target-zone-groups [:left :center :right]}}
                                    {:effect-id :zone-reroute 
                                     :enabled? true
                                     :params {:mode :filter :target-zone-groups [:left :right]}}]}]
          destination {:zone-group-id :all}
          collected-effects (ze/collect-effects-from-cue-chain cue-chain-items)
          final-target (ze/resolve-final-target destination collected-effects)]
      ;; Start with :all, replace with [:left :center :right], filter to [:left :right]
      (is (= #{:left :right} final-target)))))
