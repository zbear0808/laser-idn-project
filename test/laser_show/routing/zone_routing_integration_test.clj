(ns laser-show.routing.zone-routing-integration-test
  "Integration tests for zone-selector effect routing.
   
   Tests the simplified zone routing system where:
   - A single zone-selector effect determines routing destination (static)
   - Groups with zone-selector route all children to the group's destination
   
   Key Functions Under Test:
   - laser-show.animation.effects.zone/evaluate-zone
   - laser-show.routing.zone-effects/resolve-item-zone-destination
   - laser-show.routing.zone-effects/group-items-by-zone
   - laser-show.services.frame-service/generate-frames-by-zone"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [laser-show.animation.effects.zone :as zone]
   [laser-show.routing.zone-effects :as ze]
   [laser-show.services.frame-service :as fs]
   [laser-show.backend.multi-engine :as me]
   [laser-show.state.core :as state]
   [laser-show.state.domains :as domains]
   [laser-show.animation.types :as t]))


;; ============================================================================
;; Test Constants and Fixtures
;; ============================================================================


(def test-zone-groups
  "Set of all available zone groups for testing."
  #{:left :center :right :all})

;; ============================================================================
;; Test Cue Chain Fixtures
;; ============================================================================


(def test-cue-chain-static-zone-selector
  "Cue chain with static zone-selector (no keyframes)."
  {:id "cue-chain-static"
   :name "Static Zone Selector Test"
   :destination-zone {:zone-group-id :all}
   :items [{:id "preset-1"
            :type :preset
            :name "Circle routed to left"
            :enabled? true
            :preset-id :circle
            :params {:radius 0.5 :num-points 32 :red 1.0 :green 0.0 :blue 0.0}
            :effects [{:effect-id :zone-selector
                       :enabled? true
                       :params {:target-zone :left}}]}]})

(def test-cue-chain-no-zone-effect
  "Cue chain with no zone-selector effect (uses default destination)."
  {:id "cue-chain-no-effect"
   :name "No Zone Effect Test"
   :destination-zone {:zone-group-id :center}
   :items [{:id "preset-default"
            :type :preset
            :name "Circle using default zone"
            :enabled? true
            :preset-id :circle
            :params {:radius 0.4 :num-points 24 :red 0.0 :green 0.0 :blue 1.0}
            :effects []}]})

(def test-cue-chain-multiple-items
  "Cue chain with multiple items routing to different zones."
  {:id "cue-chain-multi"
   :name "Multiple Items Test"
   :destination-zone {:zone-group-id :all}
   :items [{:id "preset-left"
            :type :preset
            :name "Circle for left"
            :enabled? true
            :preset-id :circle
            :params {:radius 0.3 :num-points 20 :red 1.0 :green 0.0 :blue 0.0}
            :effects [{:effect-id :zone-selector
                       :enabled? true
                       :params {:target-zone :left}}]}
           {:id "preset-right"
            :type :preset
            :name "Square for right"
            :enabled? true
            :preset-id :square
            :params {:size 0.3 :num-points 12 :red 0.0 :green 1.0 :blue 0.0}
            :effects [{:effect-id :zone-selector
                       :enabled? true
                       :params {:target-zone :right}}]}
           {:id "preset-center"
            :type :preset
            :name "Triangle for center"
            :enabled? true
            :preset-id :triangle
            :params {:size 0.3 :num-points 3 :red 0.0 :green 0.0 :blue 1.0}
            :effects [{:effect-id :zone-selector
                       :enabled? true
                       :params {:target-zone :center}}]}]})

(def test-cue-chain-group-with-zone-selector
  "Cue chain with a group that has zone-selector - children's effects are ignored."
  {:id "cue-chain-group"
   :name "Group Zone Selector Test"
   :destination-zone {:zone-group-id :all}
   :items [{:id "group-1"
            :type :group
            :name "Combined Shapes"
            :enabled? true
            :effects [{:effect-id :zone-selector
                       :enabled? true
                       :params {:target-zone :center}}]
            :items [;; Child 1: has its own zone effect (should be IGNORED)
                    {:id "child-1"
                     :type :preset
                     :name "Circle trying to go to :right"
                     :enabled? true
                     :preset-id :circle
                     :params {:radius 0.3 :num-points 24 :red 1.0 :green 1.0 :blue 0.0}
                     :effects [{:effect-id :zone-selector
                                :enabled? true
                                :params {:target-zone :right}}]}
                    ;; Child 2: no zone effect
                    {:id "child-2"
                     :type :preset
                     :name "Square"
                     :enabled? true
                     :preset-id :square
                     :params {:size 0.3 :num-points 12 :red 0.0 :green 1.0 :blue 1.0}
                     :effects []}]}]})


;; ============================================================================
;; Test Fixture Setup
;; ============================================================================


(defn setup-test-state
  "Set up minimal test state before each test."
  [f]
  (state/init-state! (domains/build-initial-state))
  ;; Set up minimal zone groups
  (state/assoc-in-state! [:zone-groups] {:left {:id :left :name "Left"}
                                          :center {:id :center :name "Center"}
                                          :right {:id :right :name "Right"}
                                          :all {:id :all :name "All"}})
  (f))

(use-fixtures :each setup-test-state)


;; Helper function to create timing context
(defn make-timing-ctx
  "Create a timing context for a given beat position."
  [effective-beats]
  {:accumulated-beats effective-beats
   :accumulated-ms (* effective-beats 500.0)  ;; At 120 BPM, 1 beat = 500ms
   :phase-offset 0.0
   :effective-beats effective-beats
   :time-ms (long (* effective-beats 500.0))
   :bpm 120.0
   :trigger-time 0})


;; ============================================================================
;; Unit Tests: evaluate-zone
;; ============================================================================


(deftest evaluate-zone-test
  (testing "Simple keyword target returns that keyword"
    (is (= :all (zone/evaluate-zone {:target-zone :all})))
    (is (= :left (zone/evaluate-zone {:target-zone :left})))
    (is (= :right (zone/evaluate-zone {:target-zone :right}))))

  (testing "Missing target-zone defaults to :all"
    (is (= :all (zone/evaluate-zone {})))))


;; ============================================================================
;; Unit Tests: Zone Effect Identification
;; ============================================================================


(deftest zone-effect-identification-test
  (testing "zone-effect? correctly identifies zone-selector"
    (is (true? (ze/zone-effect? {:effect-id :zone-selector})))
    (is (false? (ze/zone-effect? {:effect-id :color-shift})))
    (is (false? (ze/zone-effect? {:effect-id :scale})))
    (is (false? (ze/zone-effect? nil)))))


(deftest extract-zone-effects-test
  (testing "extract-zone-effects filters to enabled zone effects only"
    ;; No effects
    (is (= [] (ze/extract-zone-effects [])))
    
    ;; Only zone-selector, enabled
    (is (= [{:effect-id :zone-selector :enabled? true :params {}}]
           (ze/extract-zone-effects [{:effect-id :zone-selector 
                                      :enabled? true 
                                      :params {}}])))
    
    ;; Zone-selector disabled
    (is (= [] (ze/extract-zone-effects [{:effect-id :zone-selector 
                                         :enabled? false 
                                         :params {}}])))
    
    ;; Mixed effects - only returns zone effects
    (is (= [{:effect-id :zone-selector :enabled? true :params {:target-zone :left}}]
           (ze/extract-zone-effects [{:effect-id :scale :enabled? true :params {}}
                                     {:effect-id :zone-selector 
                                      :enabled? true 
                                      :params {:target-zone :left}}
                                     {:effect-id :color-shift :enabled? true :params {}}])))))


;; ============================================================================
;; Unit Tests: resolve-item-zone-destination
;; ============================================================================


(deftest resolve-item-zone-destination-with-zone-selector-test
  (testing "Item with zone-selector returns evaluated zone"
    (let [item {:id "test-item"
                :effects [{:effect-id :zone-selector
                           :enabled? true
                           :params {:target-zone :left}}]}
          cue-chain-dest {:zone-group-id :all}
          timing-ctx (make-timing-ctx 0.0)]
      (is (= :left (ze/resolve-item-zone-destination item cue-chain-dest timing-ctx))))))


(deftest resolve-item-zone-destination-no-zone-effect-test
  (testing "Item without zone-selector returns default destination"
    (let [item {:id "test-item"
                :effects [{:effect-id :scale :enabled? true :params {}}]}
          cue-chain-dest {:zone-group-id :center}
          timing-ctx (make-timing-ctx 0.0)]
      (is (= :center (ze/resolve-item-zone-destination item cue-chain-dest timing-ctx))))))


(deftest resolve-item-zone-destination-disabled-effect-test
  (testing "Disabled zone-selector is ignored, uses default"
    (let [item {:id "test-item"
                :effects [{:effect-id :zone-selector
                           :enabled? false
                           :params {:target-zone :right}}]}
          cue-chain-dest {:zone-group-id :left}
          timing-ctx (make-timing-ctx 0.0)]
      (is (= :left (ze/resolve-item-zone-destination item cue-chain-dest timing-ctx))))))


;; ============================================================================
;; Unit Tests: group-items-by-zone
;; ============================================================================


(deftest group-items-by-zone-single-item-test
  (testing "Single item is grouped to its resolved zone"
    (let [items [{:id "item-1"
                  :enabled? true
                  :effects [{:effect-id :zone-selector
                             :enabled? true
                             :params {:target-zone :left}}]}]
          cue-chain-dest {:zone-group-id :all}
          timing-ctx (make-timing-ctx 0.0)
          result (ze/group-items-by-zone items cue-chain-dest timing-ctx)]
      
      (is (= #{:left} (set (keys result))))
      (is (= 1 (count (:left result))))
      (is (= "item-1" (:id (first (:left result))))))))


(deftest group-items-by-zone-multiple-items-test
  (testing "Multiple items with different zones are grouped correctly"
    (let [items [{:id "item-left"
                  :enabled? true
                  :effects [{:effect-id :zone-selector
                             :enabled? true
                             :params {:target-zone :left}}]}
                 {:id "item-right"
                  :enabled? true
                  :effects [{:effect-id :zone-selector
                             :enabled? true
                             :params {:target-zone :right}}]}
                 {:id "item-center"
                  :enabled? true
                  :effects [{:effect-id :zone-selector
                             :enabled? true
                             :params {:target-zone :center}}]}]
          cue-chain-dest {:zone-group-id :all}
          timing-ctx (make-timing-ctx 0.0)
          result (ze/group-items-by-zone items cue-chain-dest timing-ctx)]
      
      (is (= #{:left :right :center} (set (keys result))))
      (is (= 1 (count (:left result))))
      (is (= 1 (count (:right result))))
      (is (= 1 (count (:center result)))))))


(deftest group-items-by-zone-disabled-items-excluded-test
  (testing "Disabled items are excluded from grouping"
    (let [items [{:id "item-enabled"
                  :enabled? true
                  :effects [{:effect-id :zone-selector
                             :enabled? true
                             :params {:target-zone :left}}]}
                 {:id "item-disabled"
                  :enabled? false
                  :effects [{:effect-id :zone-selector
                             :enabled? true
                             :params {:target-zone :right}}]}]
          cue-chain-dest {:zone-group-id :all}
          timing-ctx (make-timing-ctx 0.0)
          result (ze/group-items-by-zone items cue-chain-dest timing-ctx)]
      
      (is (= #{:left} (set (keys result))))
      (is (nil? (:right result))))))


(deftest group-items-by-zone-items-without-zone-effect-use-default-test
  (testing "Items without zone-selector use cue chain default"
    (let [items [{:id "item-no-effect"
                  :enabled? true
                  :effects []}]
          cue-chain-dest {:zone-group-id :center}
          timing-ctx (make-timing-ctx 0.0)
          result (ze/group-items-by-zone items cue-chain-dest timing-ctx)]
      
      (is (= #{:center} (set (keys result))))
      (is (= 1 (count (:center result)))))))


;; ============================================================================
;; Integration Tests: Frame Generation
;; ============================================================================


(deftest zone-selector-static-frame-routing-test
  (testing "Static zone-selector routes frames to specified zone"
    (let [frames-by-zone (fs/generate-frames-by-zone
                           test-cue-chain-static-zone-selector
                           0 120.0 0
                           (make-timing-ctx 0.0))]
      
      ;; Should have frames for :left only
      (is (contains? frames-by-zone :left)
          "Should have frame for :left zone")
      (is (pos? (count (:left frames-by-zone)))
          ":left frame should contain points")
      
      ;; Other zones should be empty
      (is (or (not (contains? frames-by-zone :right))
              (nil? (:right frames-by-zone)))
          ":right should be empty")
      (is (or (not (contains? frames-by-zone :center))
              (nil? (:center frames-by-zone)))
          ":center should be empty"))))


(deftest no-zone-effect-uses-default-test
  (testing "Cue chain without zone-selector uses default destination"
    (let [frames-by-zone (fs/generate-frames-by-zone
                           test-cue-chain-no-zone-effect
                           0 120.0 0
                           (make-timing-ctx 0.0))]
      
      ;; Should route to :center (the cue chain default)
      (is (contains? frames-by-zone :center)
          "Should have frame for :center (default destination)")
      (is (pos? (count (:center frames-by-zone)))
          ":center should have points")
      
      ;; Other zones should be empty
      (is (or (not (contains? frames-by-zone :left))
              (nil? (:left frames-by-zone)))
          ":left should be empty"))))


(deftest multiple-items-different-zones-test
  (testing "Multiple items with different zone-selectors route correctly"
    (let [frames-by-zone (fs/generate-frames-by-zone
                           test-cue-chain-multiple-items
                           0 120.0 0
                           (make-timing-ctx 0.0))]
      
      ;; Should have frames for :left, :right, and :center
      (is (contains? frames-by-zone :left))
      (is (contains? frames-by-zone :right))
      (is (contains? frames-by-zone :center))
      
      ;; Each zone should have content
      (is (pos? (count (:left frames-by-zone))))
      (is (pos? (count (:right frames-by-zone))))
      (is (pos? (count (:center frames-by-zone))))
      
      ;; Frame sizes should differ (circle 20 pts, square 12 pts, triangle 3 pts)
      (is (not= (count (:left frames-by-zone))
                (count (:right frames-by-zone)))
          "Different shapes should have different point counts"))))


;; ============================================================================
;; Group Behavior Tests
;; ============================================================================


(deftest group-zone-selector-overrides-children-test
  (testing "Group's zone-selector routes all children to group's destination"
    (let [frames-by-zone (fs/generate-frames-by-zone
                           test-cue-chain-group-with-zone-selector
                           0 120.0 0
                           (make-timing-ctx 0.0))]
      
      ;; All content should go to :center (group's destination)
      (is (contains? frames-by-zone :center)
          "Should have frame for :center (group's destination)")
      
      ;; Combined frame should have content from both children
      (is (pos? (count (:center frames-by-zone)))
          ":center should have combined content")
      
      ;; The combined frame should have more points than either child alone
      ;; Circle has 24 points, Square has 12 points, plus blanking
      (is (> (count (:center frames-by-zone)) 24)
          ":center should have combined content (> 24 points)")
      
      ;; :right should NOT have any content (child's zone effect ignored)
      (is (or (not (contains? frames-by-zone :right))
              (nil? (:right frames-by-zone)))
          ":right should be empty - child's zone effect was ignored")
      
      ;; :all should NOT have content (default was overridden)
      (is (or (not (contains? frames-by-zone :all))
              (nil? (:all frames-by-zone)))
          ":all should be empty - default was overridden"))))


(deftest group-items-by-zone-sealed-unit-behavior-test
  (testing "Group with zone-selector acts as sealed unit - children's effects ignored"
    (let [group-item {:id "group-1"
                      :type :group
                      :enabled? true
                      :effects [{:effect-id :zone-selector
                                 :enabled? true
                                 :params {:target-zone :center}}]
                      :items [;; Child 1: wants :right (should be ignored)
                              {:id "child-1"
                               :type :preset
                               :enabled? true
                               :preset-id :circle
                               :params {}
                               :effects [{:effect-id :zone-selector
                                          :enabled? true
                                          :params {:target-zone :right}}]}
                              ;; Child 2: no zone effect
                              {:id "child-2"
                               :type :preset
                               :enabled? true
                               :preset-id :square
                               :params {}
                               :effects []}]}
          cue-chain-dest {:zone-group-id :all}
          timing-ctx (make-timing-ctx 0.0)
          result (ze/group-items-by-zone [group-item] cue-chain-dest timing-ctx)]
      
      ;; All content should go to :center (group's destination)
      (is (= #{:center} (set (keys result)))
          "All content should route to :center only")
      
      ;; The group should be in :center
      (is (= 1 (count (:center result)))
          "Should have exactly one item (the group) in :center")
      
      ;; Verify the group is in :center
      (is (= "group-1" (:id (first (:center result))))
          "The group should be routed to :center")
      
      ;; Other zones should be empty
      (is (nil? (:right result))
          ":right should be empty - child's effect was ignored")
      (is (nil? (:all result))
          ":all should be empty - default was overridden"))))


;; ============================================================================
;; Projector Integration Tests
;; ============================================================================


(deftest projector-receives-zone-frames-test
  (testing "Projector frame extraction works with zone-based frames"
    (let [frames-by-zone (fs/generate-frames-by-zone
                           test-cue-chain-multiple-items
                           0 120.0 0
                           (make-timing-ctx 0.0))
          left-frame (:left frames-by-zone)
          right-frame (:right frames-by-zone)]
      
      ;; Both zones should have content
      (is (some? left-frame))
      (is (pos? (count left-frame)))
      (is (some? right-frame))
      (is (pos? (count right-frame))))))


(deftest projector-multi-zone-concatenation-test
  (testing "Projector in multiple zones receives concatenated frames"
    (let [frames-by-zone (fs/generate-frames-by-zone
                           test-cue-chain-multiple-items
                           0 120.0 0
                           (make-timing-ctx 0.0))
          left-frame (:left frames-by-zone)
          center-frame (:center frames-by-zone)
          
          ;; Simulate projector frame extraction for projector in [:left :center]
          projector-zone-groups [:left :center]
          projector-frames (#'me/extract-frames-for-zones frames-by-zone projector-zone-groups)
          combined-frame (#'me/combine-zone-frames projector-frames)]
      
      ;; Both zones should have content
      (is (some? left-frame))
      (is (pos? (count left-frame)))
      (is (some? center-frame))
      (is (pos? (count center-frame)))
      
      ;; Combined frame should exist and be larger than individuals
      (is (some? combined-frame))
      (is (> (count combined-frame) (count left-frame)))
      (is (> (count combined-frame) (count center-frame)))
      
      ;; Combined should be at least sum of both (may have blanking)
      (is (>= (count combined-frame)
              (+ (count left-frame) (count center-frame)))))))


;; ============================================================================
;; Edge Case Tests
;; ============================================================================


(deftest disabled-zone-selector-uses-default-test
  (testing "Disabled zone-selector effect causes item to use default destination"
    (let [cue-chain {:id "cue-chain-disabled-effect"
                     :destination-zone {:zone-group-id :left}
                     :items [{:id "preset-1"
                              :type :preset
                              :enabled? true
                              :preset-id :circle
                              :params {:radius 0.5 :num-points 20 :red 1.0 :green 0.0 :blue 0.0}
                              :effects [{:effect-id :zone-selector
                                         :enabled? false  ;; DISABLED
                                         :params {:target-zone :right}}]}]}
          frames-by-zone (fs/generate-frames-by-zone
                           cue-chain
                           0 120.0 0
                           (make-timing-ctx 0.0))]
      
      ;; Should route to :left (default) since zone effect is disabled
      (is (contains? frames-by-zone :left)
          "Should have frame for :left (default)")
      (is (pos? (count (:left frames-by-zone))))
      
      ;; :right should NOT have content
      (is (or (not (contains? frames-by-zone :right))
              (nil? (:right frames-by-zone)))
          ":right should be empty"))))


(deftest disabled-preset-excluded-test
  (testing "Disabled presets are excluded from frame generation"
    (let [cue-chain {:id "cue-chain-disabled-preset"
                     :destination-zone {:zone-group-id :left}
                     :items [{:id "preset-enabled"
                              :type :preset
                              :enabled? true
                              :preset-id :circle
                              :params {:radius 0.5 :num-points 20 :red 1.0 :green 0.0 :blue 0.0}
                              :effects []}
                             {:id "preset-disabled"
                              :type :preset
                              :enabled? false  ;; DISABLED
                              :preset-id :square
                              :params {:size 0.5 :num-points 16}
                              :effects [{:effect-id :zone-selector
                                         :enabled? true
                                         :params {:target-zone :right}}]}]}
          frames-by-zone (fs/generate-frames-by-zone
                           cue-chain
                           0 120.0 0
                           (make-timing-ctx 0.0))]
      
      ;; Only enabled preset should generate frames
      (is (contains? frames-by-zone :left))
      (is (pos? (count (:left frames-by-zone))))
      
      ;; :right should NOT have content (disabled preset excluded)
      (is (or (not (contains? frames-by-zone :right))
              (nil? (:right frames-by-zone)))
          ":right should be empty - disabled preset excluded"))))


(deftest empty-items-produces-no-frames-test
  (testing "Cue chain with empty items produces no frames"
    (let [cue-chain {:id "empty-cue-chain"
                     :destination-zone {:zone-group-id :all}
                     :items []}
          frames-by-zone (fs/generate-frames-by-zone
                           cue-chain
                           0 120.0 0
                           (make-timing-ctx 0.0))]
      
      ;; Should be empty or have no content in any zone
      (is (or (empty? frames-by-zone)
              (every? #(or (nil? %) (empty? %)) (vals frames-by-zone)))
          "Empty cue chain should produce no frames"))))


(deftest nil-destination-defaults-to-all-test
  (testing "Nil destination zone defaults to :all"
    (let [item {:id "test-item"
                :enabled? true
                :effects []}  ;; No zone-selector
          cue-chain-dest {:zone-group-id nil}  ;; Nil destination
          timing-ctx (make-timing-ctx 0.0)
          result (ze/resolve-item-zone-destination item cue-chain-dest timing-ctx)]
      
      (is (= :all result)
          "Nil destination should default to :all"))))
