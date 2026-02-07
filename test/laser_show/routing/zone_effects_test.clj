(ns laser-show.routing.zone-effects-test
  "Unit tests for zone-effects module.
   
   Tests the new zone-selector effect system:
   - Zone effect identification  
   - Zone destination resolution with timing-ctx
   - Item grouping by resolved zone"
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.routing.zone-effects :as zone-effects]
            [laser-show.animation.effects.zone :as zone]))


;; Test Fixtures

(def default-destination {:zone-group-id :left})

(defn make-timing-ctx
  "Create a timing context for testing."
  [effective-beats]
  {:effective-beats effective-beats
   :bpm 120.0
   :time-ms (long (* effective-beats 500.0))})


;; Test Items

(def item-no-effects
  "Preset with no effects - uses cue chain default"
  {:type :preset
   :id (random-uuid)
   :preset-id :circle
   :effects []
   :enabled? true})

(def item-with-zone-selector
  "Preset with zone-selector targeting :right"
  {:type :preset
   :id (random-uuid)
   :preset-id :circle
   :effects [{:effect-id :zone-selector
              :enabled? true
              :params {:target-zone :right}}]
   :enabled? true})

(def item-with-keyframed-zone-selector
  "Preset with keyframed zone-selector"
  {:type :preset
   :id (random-uuid)
   :preset-id :circle
   :effects [{:effect-id :zone-selector
              :enabled? true
              :params {:target-zone {:value :all
                                     :keyframes [{:beat 0.0 :value :left}
                                                 {:beat 2.0 :value :right}
                                                 {:beat 4.0 :value :center}]}}}]
   :enabled? true})

(def group-with-zone-selector
  "Group with zone-selector - children's zone effects should be ignored"
  {:type :group
   :id (random-uuid)
   :name "Test Group"
   :effects [{:effect-id :zone-selector
              :enabled? true
              :params {:target-zone :center}}]
   :items [{:type :preset
            :id (random-uuid)
            :preset-id :circle
            :effects [{:effect-id :zone-selector
                       :enabled? true
                       :params {:target-zone :left}}]  ;; Should be ignored
            :enabled? true}]
   :enabled? true})

(def item-disabled
  "Disabled preset - should not be included in grouping"
  {:type :preset
   :id (random-uuid)
   :preset-id :circle
   :effects [{:effect-id :zone-selector
              :enabled? true
              :params {:target-zone :right}}]
   :enabled? false})


;; zone-effect? tests

(deftest zone-effect-identification-test
  (testing "zone-effect? identifies zone-selector"
    (is (true? (zone-effects/zone-effect? {:effect-id :zone-selector}))))
  
  (testing "zone-effect? returns false for non-zone effects"
    (is (false? (zone-effects/zone-effect? {:effect-id :scale})))
    (is (false? (zone-effects/zone-effect? {:effect-id :translate})))
    (is (false? (zone-effects/zone-effect? {:effect-id :color-shift})))
    (is (false? (zone-effects/zone-effect? nil)))))


;; extract-zone-effects tests

(deftest extract-zone-effects-test
  (testing "Empty effects returns empty vector"
    (is (= [] (zone-effects/extract-zone-effects []))))
  
  (testing "Returns only enabled zone-selector effects"
    (is (= [{:effect-id :zone-selector :enabled? true :params {}}]
           (zone-effects/extract-zone-effects 
             [{:effect-id :zone-selector :enabled? true :params {}}]))))
  
  (testing "Filters out disabled zone-selector"
    (is (= []
           (zone-effects/extract-zone-effects
             [{:effect-id :zone-selector :enabled? false :params {}}]))))
  
  (testing "Filters out non-zone effects"
    (is (= [{:effect-id :zone-selector :enabled? true :params {:target-zone :left}}]
           (zone-effects/extract-zone-effects
             [{:effect-id :scale :enabled? true :params {}}
              {:effect-id :zone-selector :enabled? true :params {:target-zone :left}}
              {:effect-id :translate :enabled? true :params {}}])))))


;; evaluate-zone-at-beat tests

(deftest evaluate-zone-at-beat-test
  (testing "Simple keyword target returns that keyword"
    (is (= :left (zone/evaluate-zone-at-beat {:target-zone :left} 0.0)))
    (is (= :right (zone/evaluate-zone-at-beat {:target-zone :right} 5.0))))
  
  (testing "Empty keyframes returns base value"
    (is (= :all (zone/evaluate-zone-at-beat 
                  {:target-zone {:value :all :keyframes []}} 
                  0.0))))
  
  (testing "Nil keyframes returns base value"
    (is (= :center (zone/evaluate-zone-at-beat
                     {:target-zone {:value :center :keyframes nil}}
                     2.5))))
  
  (testing "Step interpolation with keyframes"
    (let [params {:target-zone {:value :all
                                :keyframes [{:beat 0.0 :value :left}
                                            {:beat 2.0 :value :right}
                                            {:beat 4.0 :value :center}]}}]
      ;; At beat 0 - exactly at first keyframe
      (is (= :left (zone/evaluate-zone-at-beat params 0.0)))
      ;; Between first and second keyframes
      (is (= :left (zone/evaluate-zone-at-beat params 1.0)))
      (is (= :left (zone/evaluate-zone-at-beat params 1.999)))
      ;; At beat 2 - exactly at second keyframe
      (is (= :right (zone/evaluate-zone-at-beat params 2.0)))
      ;; Between second and third keyframes
      (is (= :right (zone/evaluate-zone-at-beat params 3.0)))
      ;; At beat 4 - exactly at third keyframe  
      (is (= :center (zone/evaluate-zone-at-beat params 4.0)))
      ;; After all keyframes
      (is (= :center (zone/evaluate-zone-at-beat params 10.0)))))
  
  (testing "Beat before first keyframe returns base value"
    (let [params {:target-zone {:value :all
                                :keyframes [{:beat 1.0 :value :left}]}}]
      (is (= :all (zone/evaluate-zone-at-beat params 0.0)))
      (is (= :all (zone/evaluate-zone-at-beat params 0.5))))))


;; resolve-item-zone-destination tests

(deftest resolve-item-zone-destination-test
  (testing "Item without zone effects uses cue chain default"
    (is (= :left
           (zone-effects/resolve-item-zone-destination
             item-no-effects
             default-destination
             (make-timing-ctx 0.0)))))
  
  (testing "Item with zone-selector uses target zone"
    (is (= :right
           (zone-effects/resolve-item-zone-destination
             item-with-zone-selector
             default-destination
             (make-timing-ctx 0.0)))))
  
  (testing "Group's zone-selector applies, children's effects ignored at group level"
    (is (= :center
           (zone-effects/resolve-item-zone-destination
             group-with-zone-selector
             default-destination
             (make-timing-ctx 0.0)))))
  
  (testing "Nil destination defaults to :all"
    (is (= :all
           (zone-effects/resolve-item-zone-destination
             item-no-effects
             {}
             (make-timing-ctx 0.0)))))
  
  (testing "Disabled zone effect is ignored"
    (let [item-disabled-effect {:type :preset
                                :id (random-uuid)
                                :effects [{:effect-id :zone-selector
                                           :enabled? false
                                           :params {:target-zone :right}}]}]
      (is (= :left
             (zone-effects/resolve-item-zone-destination
               item-disabled-effect
               default-destination
               (make-timing-ctx 0.0))))))
  
  (testing "Keyframed zone-selector evaluates at current beat"
    ;; At beat 0 → :left
    (is (= :left
           (zone-effects/resolve-item-zone-destination
             item-with-keyframed-zone-selector
             default-destination
             (make-timing-ctx 0.0))))
    ;; At beat 2.0 → :right
    (is (= :right
           (zone-effects/resolve-item-zone-destination
             item-with-keyframed-zone-selector
             default-destination
             (make-timing-ctx 2.0))))
    ;; At beat 4.0 → :center
    (is (= :center
           (zone-effects/resolve-item-zone-destination
             item-with-keyframed-zone-selector
             default-destination
             (make-timing-ctx 4.0))))))


;; group-items-by-zone tests

(deftest group-items-by-zone-test
  (testing "Groups items by their resolved zone destination"
    (let [items [item-no-effects item-with-zone-selector]
          timing-ctx (make-timing-ctx 0.0)
          result (zone-effects/group-items-by-zone items default-destination timing-ctx)]
      ;; item-no-effects → :left (default)
      ;; item-with-zone-selector → :right
      (is (= 1 (count (:left result))))
      (is (= 1 (count (:right result))))
      (is (= item-no-effects (first (:left result))))
      (is (= item-with-zone-selector (first (:right result))))))
  
  (testing "Item routes to single zone (no multi-zone in new system)"
    (let [items [item-with-zone-selector]
          timing-ctx (make-timing-ctx 0.0)
          result (zone-effects/group-items-by-zone items default-destination timing-ctx)]
      ;; New system: each item routes to exactly ONE zone
      (is (= 1 (count (keys result))))
      (is (= 1 (count (:right result))))
      (is (= item-with-zone-selector (first (:right result))))))
  
  (testing "Disabled items are skipped"
    (let [items [item-disabled item-no-effects]
          timing-ctx (make-timing-ctx 0.0)
          result (zone-effects/group-items-by-zone items default-destination timing-ctx)]
      (is (= 1 (count (:left result))))
      (is (= item-no-effects (first (:left result))))
      (is (nil? (:right result)))))
  
  (testing "Empty items returns empty map"
    (is (= {} (zone-effects/group-items-by-zone [] default-destination (make-timing-ctx 0.0)))))
  
  (testing "Group with zone-selector routes to single zone"
    (let [items [group-with-zone-selector]
          timing-ctx (make-timing-ctx 0.0)
          result (zone-effects/group-items-by-zone items default-destination timing-ctx)]
      ;; Group routes to :center
      (is (= 1 (count (keys result))))
      (is (= 1 (count (:center result))))
      (is (= group-with-zone-selector (first (:center result))))))
  
  (testing "Keyframed zone-selector groups change with beat position"
    (let [items [item-with-keyframed-zone-selector]]
      ;; At beat 0 → :left
      (let [result (zone-effects/group-items-by-zone items default-destination (make-timing-ctx 0.0))]
        (is (= #{:left} (set (keys result)))))
      ;; At beat 2 → :right
      (let [result (zone-effects/group-items-by-zone items default-destination (make-timing-ctx 2.0))]
        (is (= #{:right} (set (keys result)))))
      ;; At beat 4 → :center
      (let [result (zone-effects/group-items-by-zone items default-destination (make-timing-ctx 4.0))]
        (is (= #{:center} (set (keys result))))))))
