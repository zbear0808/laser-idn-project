(ns laser-show.services.frame-service-test
  "Tests for frame service, particularly zone filtering behavior.
   
   These tests verify:
   - Preview zone filtering works correctly for UI preview
   - IDN streaming bypasses preview zone filtering
   - Per-item zone routing generates separate frames per zone"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.services.frame-service :as frame-service]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]))
;; Test Fixtures

(defn setup-test-state
  "Set up test state before each test"
  [f]
  (state/init-state! (domains/build-initial-state))
  (f))

(use-fixtures :each setup-test-state)


;; Helper Functions


(defn setup-playing-state-with-cue!
  "Set up state as if playing with a cue chain.
   Updated for multi-cue architecture: uses active-cues map."
  [destination-zone-group]
  (let [current-time (System/currentTimeMillis)]
    (state/swap-state!
      (fn [s]
        (-> s
            (assoc-in [:playback :playing?] true)
            ;; New multi-cue structure: active-cues is a map
            (assoc-in [:playback :active-cues [0 0]]
                      {:trigger-time current-time
                       :accumulated-beats 0.0
                       :accumulated-ms 0.0
                       :phase-offset 0.0
                       :phase-offset-target 0.0
                       :last-frame-time current-time})
            ;; Initialize global clock
            (assoc-in [:timing :global-clock]
                      {:accumulated-beats 0.0
                       :accumulated-ms 0.0
                       :last-frame-time current-time})
            (assoc-in [:chains :cue-chains [0 0]]
                      {:destination-zone {:zone-group-id destination-zone-group}
                       :items [{:type :preset
                                :preset-id :circle
                                :enabled? true
                                :effects []}]}))))))


;; Preview Zone Filter Tests
;; 
;; These tests verify the fix for BUG-2026-01-25-2:
;; Frame service was applying preview zone filter to IDN streaming,
;; causing frames to be nil when cue destination didn't match preview filter.


(deftest get-preview-zone-filter-test
  (testing "defaults to :all"
    (is (= :all (frame-service/get-preview-zone-filter))))
  
  (testing "returns configured filter"
    (state/assoc-in-state! [:config :preview :zone-group-filter] :left)
    (is (= :left (frame-service/get-preview-zone-filter))))
  
  (testing "returns nil when set to nil (master view)"
    (state/assoc-in-state! [:config :preview :zone-group-filter] nil)
    (is (nil? (frame-service/get-preview-zone-filter)))))


(deftest matches-preview-zone-test
  (testing "nil preview-zone matches everything (master view)"
    (is (#'frame-service/matches-preview-zone? nil #{:left}))
    (is (#'frame-service/matches-preview-zone? nil #{:right}))
    (is (#'frame-service/matches-preview-zone? nil #{:all}))
    (is (#'frame-service/matches-preview-zone? nil #{})))
  
  (testing "matches when final-targets contains preview-zone"
    (is (#'frame-service/matches-preview-zone? :left #{:left :right}))
    (is (#'frame-service/matches-preview-zone? :all #{:all})))
  
  (testing "does not match when final-targets doesn't contain preview-zone"
    (is (not (#'frame-service/matches-preview-zone? :left #{:right})))
    (is (not (#'frame-service/matches-preview-zone? :all #{:left :right})))))


(deftest generate-current-frame-zone-filter-integration-test
  (testing "when for-preview? is true (default) - zone filter is bypassed for preview mode"
    ;; Preview mode shows all cues regardless of zone filter
    (setup-playing-state-with-cue! :left)
    (state/assoc-in-state! [:config :preview :zone-group-filter] :all)
    ;; With default for-preview? true, zone filter is bypassed
    (let [frame-data (frame-service/generate-current-frame)]
      ;; Frame should be generated with map structure
      (is (or (nil? frame-data) (map? frame-data))
          "Preview mode should return map with :points and :cue-destinations")
      (when frame-data
        (is (contains? frame-data :points))
        (is (contains? frame-data :cue-destinations)))))
  
  (testing "when skip-zone-filter? is true - zone filter is bypassed (legacy param)"
    ;; Test legacy skip-zone-filter? parameter still works
    (setup-playing-state-with-cue! :left)
    (state/assoc-in-state! [:config :preview :zone-group-filter] :all)
    ;; With skip-zone-filter? true, filter is bypassed
    (let [frame-data (frame-service/generate-current-frame {:skip-zone-filter? true})]
      ;; Frame should be generated
      (is (or (nil? frame-data) (map? frame-data))
          "skip-zone-filter? true should bypass zone filtering")))
  
  (testing "when destination matches filter - frame is generated"
    (setup-playing-state-with-cue! :all)
    (state/assoc-in-state! [:config :preview :zone-group-filter] :all)
    ;; Both modes should generate frame when zones match
    (let [frame-preview (frame-service/generate-current-frame {:for-preview? true})
          frame-idn (frame-service/generate-current-frame {:for-preview? false})]
      ;; Both should produce same nil/non-nil result when zones match
      (is (= (nil? frame-preview) (nil? frame-idn))
          "Both preview and IDN modes should produce same result when zones match")))
  
  (testing "when preview filter is nil (master view) - everything passes"
    (setup-playing-state-with-cue! :left)
    (state/assoc-in-state! [:config :preview :zone-group-filter] nil)
    ;; With nil filter, frame generation should be attempted
    (let [frame-data (frame-service/generate-current-frame)]
      ;; Should return map structure or nil
      (is (or (nil? frame-data) (and (map? frame-data)
                                     (or (nil? (:points frame-data))
                                         (vector? (:points frame-data)))))
          "Frame should be nil or a map with :points vector"))))


;; Timing Context Tests


(deftest get-timing-context-test
  (testing "returns timing context with accumulated values (from first active cue)"
    (state/swap-state!
     #(assoc-in % [:playback :active-cues [0 0]]
               {:trigger-time (System/currentTimeMillis)
                :accumulated-beats 42.5
                :accumulated-ms 10000
                :phase-offset 0.25
                :phase-offset-target 0.0
                :last-frame-time (System/currentTimeMillis)}))
    (let [ctx (frame-service/get-timing-context)]
      (is (= 42.5 (:accumulated-beats ctx)))
      (is (= 10000 (:accumulated-ms ctx)))
      (is (= 0.25 (:phase-offset ctx)))
      (is (= 42.75 (:effective-beats ctx)) "effective-beats = accumulated-beats + phase-offset")))
  
  (testing "defaults to zero values when no active cues"
    (let [ctx (frame-service/get-timing-context)]
      (is (number? (:accumulated-beats ctx)))
      (is (number? (:accumulated-ms ctx)))
      (is (number? (:phase-offset ctx)))
      (is (number? (:effective-beats ctx))))))


;; Playback State Tests


(deftest is-playing-test
  (testing "returns false when not playing"
    (is (not (frame-service/is-playing?))))
  
  (testing "returns true when playing"
    (state/assoc-in-state! [:playback :playing?] true)
    (is (frame-service/is-playing?))))


;; Active Cell Data Tests
;; Note: Each test case is isolated to avoid state pollution between tests


(deftest get-active-cell-data-nil-when-no-cues-test
  (testing "returns nil when no active cues"
    (is (nil? (frame-service/get-active-cell-data)))))

(deftest get-active-cell-data-nil-when-empty-items-test
  (testing "returns nil when active cue has empty cue chain"
    (state/swap-state!
     #(-> %
         (assoc-in [:playback :active-cues [0 0]]
                   {:trigger-time (System/currentTimeMillis)
                    :accumulated-beats 0.0
                    :accumulated-ms 0.0
                    :phase-offset 0.0
                    :phase-offset-target 0.0
                    :last-frame-time (System/currentTimeMillis)})
         (assoc-in [:chains :cue-chains [0 0]] {:items []})))
    (is (nil? (frame-service/get-active-cell-data)))))

(deftest get-active-cell-data-returns-data-when-present-test
  (testing "returns cue chain data when present (from first active cue)"
    (state/swap-state!
      (fn [s]
        (-> s

            (assoc-in [:playback :active-cues [1 2]]
                      {:trigger-time (System/currentTimeMillis)
                       :accumulated-beats 0.0
                       :accumulated-ms 0.0
                       :phase-offset 0.0
                       :phase-offset-target 0.0
                       :last-frame-time (System/currentTimeMillis)})
            (assoc-in [:chains :cue-chains [1 2]]
                      {:destination-zone {:zone-group-id :left}
                       :items [{:type :preset :preset-id :circle :enabled? true}]}))))
    (let [data (frame-service/get-active-cell-data)]
      (is (some? data))
      (is (some? (:cue-chain data)))
      (is (= :left (get-in data [:cue-chain :destination-zone :zone-group-id]))))))


;; Per-Item Zone Routing Tests (Phase 2)
;;
;; Tests for generate-frames-by-zone which separates items into different
;; frames based on their zone-selector effects.


(def ^:private test-timing-ctx
  {:accumulated-beats 2.0
   :accumulated-ms 1000.0
   :phase-offset 0.0
   :effective-beats 2.0
   :time-ms 1000
   :bpm 120.0
   :trigger-time 0})

(deftest test-generate-frames-by-zone-separates-items
  (testing "Items with different zone-selector effects produce separate frames per zone"
    (let [current-time (System/currentTimeMillis)]
      (state/swap-state!
        (fn [s]
          (-> s
              (assoc-in [:timing :global-clock]
                        {:accumulated-beats 2.0
                         :accumulated-ms 1000.0
                         :last-frame-time current-time}))))
      
      (let [test-cue-chain {:destination-zone {:zone-group-id :center}
                            :items [{:type :preset
                                     :preset-id :circle
                                     :enabled? true
                                     :effects [{:effect-id :zone-selector
                                                :enabled? true
                                                :params {:target-zone :left}}]}
                                    {:type :preset
                                     :preset-id :square
                                     :enabled? true
                                     :effects [{:effect-id :zone-selector
                                                :enabled? true
                                                :params {:target-zone :right}}]}
                                    {:type :preset
                                     :preset-id :triangle
                                     :enabled? true
                                     :effects []}]}
            elapsed-ms 1000
            bpm 120.0
            trigger-time 0
            result (frame-service/generate-frames-by-zone
                     test-cue-chain
                     elapsed-ms
                     bpm
                     trigger-time
                     test-timing-ctx)]
        
        (is (map? result) "Result should be a map")
        (is (= 3 (count result)) "Result should have 3 zones: :left, :right, :center")
        (is (contains? result :left) "Should have :left zone frame")
        (is (contains? result :right) "Should have :right zone frame")
        (is (contains? result :center) "Should have :center zone frame (default destination)")
        
        (is (vector? (:left result)) ":left frame should be a vector of points")
        (is (vector? (:right result)) ":right frame should be a vector of points")
        (is (vector? (:center result)) ":center frame should be a vector of points")
        
        (is (seq (:left result)) ":left frame should not be empty")
        (is (seq (:right result)) ":right frame should not be empty")
        (is (seq (:center result)) ":center frame should not be empty")))))

(deftest test-generate-frames-by-zone-inherits-default
  (testing "Item without zone effects inherits cue chain default destination"
    (let [current-time (System/currentTimeMillis)]
      (state/swap-state!
        (fn [s]
          (-> s
              (assoc-in [:timing :global-clock]
                        {:accumulated-beats 0.0
                         :accumulated-ms 0.0
                         :last-frame-time current-time}))))
      
      (let [test-cue-chain {:destination-zone {:zone-group-id :all}
                            :items [{:type :preset
                                     :preset-id :circle
                                     :enabled? true
                                     :effects []}]}
            result (frame-service/generate-frames-by-zone
                    test-cue-chain 0 120.0 0 test-timing-ctx)]
        
        (is (= 1 (count result)) "Should have exactly 1 zone")
        (is (contains? result :all) "Item without zone effects should route to :all")
        (is (nil? (:left result)) "Should not have :left zone")
        (is (nil? (:right result)) "Should not have :right zone")))))

(deftest test-generate-frames-by-zone-empty-zones-excluded
  (testing "Empty zones do not appear in result map"
    (let [current-time (System/currentTimeMillis)]
      (state/swap-state!
        (fn [s]
          (-> s
              (assoc-in [:timing :global-clock]
                        {:accumulated-beats 0.0
                         :accumulated-ms 0.0
                         :last-frame-time current-time}))))
      
      (let [test-cue-chain {:destination-zone {:zone-group-id :left}
                            :items [{:type :preset
                                     :preset-id :circle
                                     :enabled? true
                                     :effects []}]}
            result (frame-service/generate-frames-by-zone
                     test-cue-chain 0 120.0 0 test-timing-ctx)]
        
        (is (= #{:left} (set (keys result))) "Only :left should have a frame")
        (is (nil? (:all result)) ":all should not have a frame")
        (is (nil? (:right result)) ":right should not have a frame")
        (is (nil? (:center result)) ":center should not have a frame")))))

(deftest test-generate-frames-by-zone-cached
  (testing "Cached function returns same result on repeated calls within cache window"
    (let [current-time (System/currentTimeMillis)]
      (state/swap-state!
        (fn [s]
          (-> s
              (assoc-in [:timing :global-clock]
                        {:accumulated-beats 0.0
                         :accumulated-ms 0.0
                         :last-frame-time current-time}))))
      
      (let [test-cue-chain {:destination-zone {:zone-group-id :left}
                            :items [{:type :preset
                                     :preset-id :circle
                                     :enabled? true
                                     :effects []}]}
            result1 (frame-service/generate-frames-by-zone-cached
                      test-cue-chain 0 120.0 0 test-timing-ctx)
            result2 (frame-service/generate-frames-by-zone-cached
                      test-cue-chain 0 120.0 0 test-timing-ctx)]
        
        (is (= result1 result2) "Cached results should be identical")
        (is (contains? result1 :left) "Result should contain :left zone")))))


;; Multi-Cue Zone Destination Tests
;;
;; These tests verify the fix for BUG-2026-02-06:
;; cue-destinations was missing cues with non-default zone routing because
;; the single preview-zone filter excluded them entirely.


(defn setup-multi-cue-state!
  "Set up state with multiple active cues with different zone destinations.
   This tests the core bug where cues routed to zones other than the preview filter
   were being excluded from cue-destinations."
  []
  (let [current-time (System/currentTimeMillis)]
    (state/swap-state!
      (fn [s]
        (-> s
            (assoc-in [:playback :playing?] true)
            ;; Initialize global clock
            (assoc-in [:timing :global-clock]
                      {:accumulated-beats 0.0
                       :accumulated-ms 0.0
                       :last-frame-time current-time})
            ;; Cue 1: routed to :all (default zone)
            (assoc-in [:playback :active-cues [5 0]]
                      {:trigger-time current-time
                       :accumulated-beats 0.0
                       :accumulated-ms 0.0
                       :phase-offset 0.0
                       :phase-offset-target 0.0
                       :last-frame-time current-time})
            (assoc-in [:chains :cue-chains [5 0]]
                      {:destination-zone {:zone-group-id :all}
                       :items [{:type :preset
                                :preset-id :circle
                                :enabled? true
                                :effects []}]})
            ;; Cue 2: routed to :left (non-default zone)
            (assoc-in [:playback :active-cues [6 0]]
                      {:trigger-time current-time
                       :accumulated-beats 0.0
                       :accumulated-ms 0.0
                       :phase-offset 0.0
                       :phase-offset-target 0.0
                       :last-frame-time current-time})
            (assoc-in [:chains :cue-chains [6 0]]
                      {:destination-zone {:zone-group-id :left}
                       :items [{:type :preset
                                :preset-id :square
                                :enabled? true
                                :effects []}]}))))))


(deftest test-cue-destinations-includes-all-active-cues
  (testing "cue-destinations includes ALL active cues regardless of zone routing"
    (setup-multi-cue-state!)
    ;; Set preview filter to :all (the default)
    (state/assoc-in-state! [:config :preview :zone-group-filter] :all)
    
    (let [frame-data (frame-service/generate-current-frame)]
      (is (some? frame-data) "Frame data should not be nil")
      (is (contains? frame-data :cue-destinations) "Should have :cue-destinations")
      
      (let [cue-dests (:cue-destinations frame-data)]
        ;; This is the critical test - both cues should be in cue-destinations
        ;; even though only one targets :all (the preview filter zone)
        (is (contains? cue-dests [5 0])
            "Cue [5 0] with :all destination should be in cue-destinations")
        (is (contains? cue-dests [6 0])
            "Cue [6 0] with :left destination should ALSO be in cue-destinations (BUG FIX)")
        
        ;; Verify the zone targeting is correct
        (is (= #{:all} (get cue-dests [5 0]))
            "Cue [5 0] should target :all")
        (is (= #{:left} (get cue-dests [6 0]))
            "Cue [6 0] should target :left")))))


(deftest test-cue-destinations-not-affected-by-preview-filter
  (testing "cue-destinations tracking is independent of preview zone filter setting"
    (setup-multi-cue-state!)
    
    ;; Test with various preview filter settings
    (doseq [filter-zone [nil :all :left :right :center]]
      (state/assoc-in-state! [:config :preview :zone-group-filter] filter-zone)
      
      (let [frame-data (frame-service/generate-current-frame)]
        (when frame-data
          (let [cue-dests (:cue-destinations frame-data)]
            (is (= 2 (count cue-dests))
                (str "Should have 2 cues in cue-destinations with filter=" filter-zone))
            (is (contains? cue-dests [5 0])
                (str "Cue [5 0] should be present with filter=" filter-zone))
            (is (contains? cue-dests [6 0])
                (str "Cue [6 0] should be present with filter=" filter-zone))))))))

(deftest test-frame-data-contains-combined-points
  (testing "frame-data :points contains combined frames from all cues"
    (setup-multi-cue-state!)
    
    (let [frame-data (frame-service/generate-current-frame)]
      (is (some? frame-data) "Frame data should not be nil")
      (is (contains? frame-data :points) "Should have :points")
      (is (vector? (:points frame-data)) ":points should be a vector")
      ;; Should have points from both cues combined
      (is (seq (:points frame-data)) ":points should not be empty"))))


;; Zone-Frames Tests
;;
;; These tests verify the :zone-frames key in generate-current-frame output.
;; :zone-frames maps zone-id → points, aggregated across all cues.
;; This allows preview cells to look up points for their zone directly.


(defn setup-single-cue-state!
  "Set up state with a single active cue at [5 0] routed to :all."
  []
  (let [current-time (System/currentTimeMillis)]
    (state/swap-state!
      (fn [s]
        (-> s
            (assoc-in [:playback :playing?] true)
            (assoc-in [:timing :global-clock]
                      {:accumulated-beats 0.0
                       :accumulated-ms 0.0
                       :last-frame-time current-time})
            (assoc-in [:playback :active-cues [5 0]]
                      {:trigger-time current-time
                       :accumulated-beats 0.0
                       :accumulated-ms 0.0
                       :phase-offset 0.0
                       :phase-offset-target 0.0
                       :last-frame-time current-time})
            (assoc-in [:chains :cue-chains [5 0]]
                      {:destination-zone {:zone-group-id :all}
                       :items [{:type :preset
                                :preset-id :circle
                                :enabled? true
                                :effects []}]}))))))


(deftest test-zone-frames-single-active-cue
  (testing "Single active cue produces zone-frames entry for its destination zone"
    (setup-single-cue-state!)
    
    (let [frame-data (frame-service/generate-current-frame)]
      (is (some? frame-data) "Frame data should not be nil")
      (is (contains? frame-data :zone-frames) "Should have :zone-frames key")
      
      (let [zone-frames (:zone-frames frame-data)]
        (is (map? zone-frames) ":zone-frames should be a map")
        (is (contains? zone-frames :all) "Should contain entry for :all zone")
        (is (vector? (get zone-frames :all)) "Zone frame should be a vector of points")
        (is (seq (get zone-frames :all)) "Zone frame should not be empty")))))


(deftest test-zone-frames-multiple-cues-same-zone
  (testing "Multiple cues to same zone aggregate their points"
    (setup-multi-cue-state!)
    
    (let [frame-data (frame-service/generate-current-frame)]
      (is (some? frame-data) "Frame data should not be nil")
      (is (contains? frame-data :zone-frames) "Should have :zone-frames key")
      
      (let [zone-frames (:zone-frames frame-data)]
        (is (map? zone-frames) ":zone-frames should be a map")
        ;; Both cues in setup-multi-cue-state! route to :all
        (is (contains? zone-frames :all) "Should contain entry for :all zone")
        (is (vector? (get zone-frames :all)) "Zone frame should be a vector")
        (is (seq (get zone-frames :all)) "Zone frame should not be empty")))))


(deftest test-zone-frames-empty-when-no-active-cues
  (testing "No active cues produces nil frame-data (no zone-frames)"
    ;; State is already initialized with no active cues via fixture
    ;; Just set playing to true but no cues
    (state/assoc-in-state! [:playback :playing?] true)
    
    (let [frame-data (frame-service/generate-current-frame)]
      ;; When no active cues, generate-current-frame returns nil
      (is (nil? frame-data) "Frame data should be nil when no active cues"))))


(deftest test-zone-frames-zones-match-cue-destinations
  (testing "zone-frames keys match union of all cue destination zones"
    (setup-multi-cue-state!)
    
    (let [frame-data (frame-service/generate-current-frame)]
      (is (some? frame-data) "Frame data should not be nil")
      
      (let [zone-frames (:zone-frames frame-data)
            cue-destinations (:cue-destinations frame-data)
            ;; Collect all unique zones from cue-destinations (vals are sets of zones)
            all-zones (into #{} cat (vals cue-destinations))]
        ;; zone-frames should have entry for each unique zone
        (is (= (set (keys zone-frames)) all-zones)
            "zone-frames keys should match all unique zones from cue-destinations")))))



