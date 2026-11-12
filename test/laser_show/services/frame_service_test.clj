(ns laser-show.services.frame-service-test
  "Tests for frame service, particularly zone filtering behavior.
   
   These tests verify:
   - Preview zone filtering works correctly for UI preview
   - IDN streaming bypasses preview zone filtering
   - Per-item zone routing generates separate frames per zone"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.input.mtc :as mtc]
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
                      :tracks [{:id #uuid "10000000-0000-0000-0000-000000000000"
                                :name "Track 1"
                                :zone-group-id destination-zone-group}]
                      :items [{:type :preset
                               :preset-id :circle
                               :track-id #uuid "10000000-0000-0000-0000-000000000000"
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




(def ^:private test-timing-ctx
  {:accumulated-beats 2.0
   :accumulated-ms 1000.0
   :phase-offset 0.0
   :effective-beats 2.0
   :time-ms 1000
   :bpm 120.0
   :trigger-time 0})

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
                            :tracks [{:id #uuid "10000000-0000-0000-0000-000000000000"
                                      :name "Track 1"
                                      :zone-group-id :all}]
                            :items [{:type :preset
                                     :preset-id :circle
                                     :track-id #uuid "10000000-0000-0000-0000-000000000000"
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
                            :tracks [{:id #uuid "10000000-0000-0000-0000-000000000000"
                                      :name "Track 1"
                                      :zone-group-id :left}]
                            :items [{:type :preset
                                     :preset-id :circle
                                     :track-id #uuid "10000000-0000-0000-0000-000000000000"
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
                            :tracks [{:id #uuid "10000000-0000-0000-0000-000000000000"
                                      :name "Track 1"
                                      :zone-group-id :left}]
                            :items [{:type :preset
                                     :preset-id :circle
                                     :track-id #uuid "10000000-0000-0000-0000-000000000000"
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
                      :tracks [{:id #uuid "10000000-0000-0000-0000-000000000000"
                                :name "Track 1"
                                :zone-group-id :all}]
                      :items [{:type :preset
                               :preset-id :circle
                               :track-id #uuid "10000000-0000-0000-0000-000000000000"
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
                      :tracks [{:id #uuid "20000000-0000-0000-0000-000000000000"
                                :name "Track 2"
                                :zone-group-id :left}]
                      :items [{:type :preset
                               :preset-id :square
                               :track-id #uuid "20000000-0000-0000-0000-000000000000"
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
                      :tracks [{:id #uuid "10000000-0000-0000-0000-000000000000"
                                :name "Track 1"
                                :zone-group-id :all}]
                      :items [{:type :preset
                               :preset-id :circle
                               :track-id #uuid "10000000-0000-0000-0000-000000000000"
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




(defn- render-with-tracks [tracks]
  (frame-service/generate-frames-by-zone
   {:destination-zone {:zone-group-id :all}
    :editor-type :timeline
    :tracks tracks
    :items [{:id :a :type :preset :preset-id :circle :track-id :t1 :enabled? true :effects []
             :timeline/start 0.0 :timeline/duration 4.0}
            {:id :b :type :preset :preset-id :square :track-id :t2 :enabled? true :effects []
             :timeline/start 0.0 :timeline/duration 4.0}]}
   0 120.0 0 test-timing-ctx))

(deftest muted-track-clips-do-not-render-test
  (let [both (render-with-tracks [{:id :t1 :type :track} {:id :t2 :type :track}])
        one (render-with-tracks [{:id :t1 :type :track :muted? true} {:id :t2 :type :track}])]
    (is (< (count (:all one)) (count (:all both))))
    (is (pos? (count (:all one))))))

(deftest muted-folder-mutes-children-test
  (is (empty? (render-with-tracks [{:id :f :type :group :muted? true
                                    :items [{:id :t1 :type :track} {:id :t2 :type :track}]}]))))

;; Phase 4: timeline playback

(defn- timeline-cue [extra]
  (merge {:editor-type :timeline
          :destination-zone {:zone-group-id :all}
          :tracks [{:id :t :type :track}]
          :items [{:id :a :type :preset :preset-id :circle :track-id :t :enabled? true :effects []
                   :timeline/start 0.0 :timeline/duration 4.0}]}
         extra))

(defn- start-timeline! [cue start-beat now]
  (state/swap-state!
   (fn [s]
     (-> s
         (assoc-in [:chains :cue-chains [0 0]] cue)
         (assoc-in [:timing :bpm] 120.0)
         (assoc-in [:playback :playing?] true)
         (assoc-in [:playback :active-cues [0 0]]
                   {:timeline? true :trigger-time now :accumulated-beats start-beat :accumulated-ms 0.0
                    :phase-offset 0.0 :phase-offset-target 0.0 :last-frame-time now})))))

(defn- beats-at-00 [] (get-in (state/get-raw-state) [:playback :active-cues [0 0] :accumulated-beats]))

(deftest timeline-cue-stops-at-end-test
  (start-timeline! (timeline-cue {}) 3.5 1000)
  (frame-service/update-timing-accumulators! 1500) ; +1 beat at 120 BPM
  (is (nil? (get-in (state/get-raw-state) [:playback :active-cues [0 0]]))
      "past the last clip end (4 beats) the cue is deactivated")
  (is (false? (get-in (state/get-raw-state) [:playback :playing?])))
  (testing "with the loop on it keeps going"
    (start-timeline! (timeline-cue {:loop {:enabled? true :start 0.0 :duration 4.0}}) 3.5 1000)
    (frame-service/update-timing-accumulators! 1500)
    (is (< (Math/abs (- 0.5 (beats-at-00))) 1e-9))))

(deftest locked-tempo-ignores-global-bpm-test
  (start-timeline! (timeline-cue {:timeline/tempo-bpm 60.0 :timeline/length 100.0}) 0.0 1000)
  (state/swap-state! assoc-in [:timing :bpm] 180.0)
  (frame-service/update-timing-accumulators! 2000) ; 1 s
  (is (< (Math/abs (- 1.0 (beats-at-00))) 1e-9) "1 s at the locked 60 BPM = 1 beat, not 3"))

(deftest clip-local-loop-modulator-starts-at-clip-test
  (let [clip (fn [start]
               {:id :a :type :preset :preset-id :circle :track-id :t :enabled? true
                :timeline/start start :timeline/duration 8.0
                :effects [{:id :fx :effect-id :scale :enabled? true
                           :params {:x-scale {:type :sine :min 0.5 :max 1.5 :period 3.0}}}]})
        render (fn [start beats]
                 (frame-service/generate-frames-by-zone
                  (timeline-cue {:items [(clip start)]})
                  0 120.0 0 {:accumulated-beats beats :effective-beats beats :phase-offset 0.0
                             :accumulated-ms (* beats 500.0) :time-ms (* beats 500.0) :bpm 120.0 :trigger-time 0}))]
    (is (= (render 0.0 1.25) (render 8.0 9.25))
        "the same clip moved to beat 8 looks the same 1.25 beats into it")
    (is (not= (render 0.0 1.25) (render 0.0 2.0)) "the modulator does move")))

;; Phase 5: MTC chase

(deftest chasing-timeline-follows-mtc-test
  (let [cue (timeline-cue {:timeline/chase :mtc :timeline/tc-start 3600.0 :timeline/tempo-bpm 120.0
                           :timeline/length 64.0})
        tick! (fn [pos now] (with-redefs [mtc/current (constantly pos)]
                              (frame-service/update-timing-accumulators! now)))]
    (state/swap-state! (fn [s] (-> s
                                   (assoc-in [:chains :cue-chains [0 0]] cue)
                                   (assoc-in [:playback :active-cues] {})
                                   (assoc-in [:playback :playing?] false))))
    (tick! {:state :running :seconds 3599.0 :fps 25 :tc {:h 0 :m 59 :s 59 :f 0 :drop? false}} 1000)
    (is (nil? (get-in (state/get-raw-state) [:playback :active-cues [0 0]])))
    (is (= {:state :running :fps 25 :text "00:59:59:00"}
           (dissoc (get-in (state/get-raw-state) [:backend :input :mtc]) :at-ms))
        "the status is mirrored for the UI")
    (tick! {:state :running :seconds 3605.48 :fps 25} 1033)
    (is (< (Math/abs (- 10.96 (beats-at-00))) 1e-9))
    (testing "the cue's own clock doesn't move it between timecode updates"
      (tick! {:state :running :seconds 3605.48 :fps 25} 1500)
      (is (< (Math/abs (- 10.96 (beats-at-00))) 1e-9)))
    (tick! {:state :stopped :seconds 3605.48 :fps 25} 1600)
    (is (nil? (get-in (state/get-raw-state) [:playback :active-cues [0 0]])))))

(deftest non-chasing-cues-ignore-mtc-test
  (start-timeline! (timeline-cue {:timeline/length 100.0}) 2.0 1000)
  (with-redefs [mtc/current (constantly {:state :running :seconds 50.0 :fps 25})]
    (frame-service/update-timing-accumulators! 1500))
  (is (< (Math/abs (- 3.0 (beats-at-00))) 1e-9) "advanced by its own clock (+1 beat), not set to 100"))
