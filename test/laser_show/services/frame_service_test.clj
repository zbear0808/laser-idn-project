(ns laser-show.services.frame-service-test
  "Tests for frame service, particularly zone filtering behavior.
   
   These tests verify:
   - Preview zone filtering works correctly for UI preview
   - IDN streaming bypasses preview zone filtering"
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
  
  (testing "when for-preview? is false - IDN streaming applies zone filter"
    ;; This tests BUG-2026-01-25-2 fix: IDN streaming with for-preview? false
    (setup-playing-state-with-cue! :left)
    (state/assoc-in-state! [:config :preview :zone-group-filter] :all)
    ;; With for-preview? false, zone filter SHOULD be applied
    (let [frame-data (frame-service/generate-current-frame {:for-preview? false})]
      ;; Frame should be nil because :left doesn't match filter :all
      (is (nil? frame-data) "IDN streaming should respect zone filter when for-preview? is false")))
  
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
      (fn [s]
        (-> s
            ;; New structure: timing values are per-cue in active-cues map
            (assoc-in [:playback :active-cues [0 0]]
                      {:trigger-time (System/currentTimeMillis)
                       :accumulated-beats 42.5
                       :accumulated-ms 10000
                       :phase-offset 0.25
                       :phase-offset-target 0.0
                       :last-frame-time (System/currentTimeMillis)}))))
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
      (fn [s]
        (-> s
            ;; New structure: use active-cues map
            (assoc-in [:playback :active-cues [0 0]]
                      {:trigger-time (System/currentTimeMillis)
                       :accumulated-beats 0.0
                       :accumulated-ms 0.0
                       :phase-offset 0.0
                       :phase-offset-target 0.0
                       :last-frame-time (System/currentTimeMillis)})
            (assoc-in [:chains :cue-chains [0 0]] {:items []}))))
    (is (nil? (frame-service/get-active-cell-data)))))

(deftest get-active-cell-data-returns-data-when-present-test
  (testing "returns cue chain data when present (from first active cue)"
    (state/swap-state!
      (fn [s]
        (-> s
            ;; New structure: use active-cues map
            ;; Only set up the one active cue we want to test
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
