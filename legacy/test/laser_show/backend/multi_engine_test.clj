(ns laser-show.backend.multi-engine-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.backend.multi-engine :as me] 
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]))


;; Test Fixtures

(defn setup-test-state
  "Set up test state before each test"
  [f]
  (state/init-state! (domains/build-initial-state))
  (f))

(use-fixtures :each setup-test-state)


;; Validation Tests


(deftest create-engine-with-valid-host-test
  (testing "Creates engine when projector has valid host"
    (let [projector {:name "Test Projector"
                     :host "192.168.1.100"
                     :port 7255
                     :enabled? true
                     :zone-groups [:all]
                     :output-config {:color-bit-depth 8
                                    :xy-bit-depth 16}}
          engine (me/create-engine-for-projector :test-proj projector)]
      (is (some? engine) "Engine should be created with valid host")
      (is (= "192.168.1.100" (:target-host engine)) "Engine should have correct host"))))

(deftest create-engine-with-nil-host-test
  (testing "Returns nil when projector has nil host"
    (let [projector {:name "Test Projector"
                     :host nil
                     :port 7255
                     :enabled? true
                     :zone-groups [:all]}
          engine (me/create-engine-for-projector :test-proj projector)]
      (is (nil? engine) "Engine should not be created with nil host"))))

(deftest create-engine-with-blank-host-test
  (testing "Returns nil when projector has blank host"
    (let [projector {:name "Test Projector"
                     :host ""
                     :port 7255
                     :enabled? true
                     :zone-groups [:all]}
          engine (me/create-engine-for-projector :test-proj projector)]
      (is (nil? engine) "Engine should not be created with blank host"))))

(deftest create-engine-with-whitespace-host-test
  (testing "Returns nil when projector has whitespace-only host"
    (let [projector {:name "Test Projector"
                     :host "   "
                     :port 7255
                     :enabled? true
                     :zone-groups [:all]}
          engine (me/create-engine-for-projector :test-proj projector)]
      (is (nil? engine) "Engine should not be created with whitespace-only host"))))

(deftest create-engines-skips-invalid-hosts-test
  (testing "create-engines skips projectors with invalid hosts"
    ;; Set up state with mix of valid and invalid projectors
    (state/reset-state!
      {:projectors {:proj-1 {:name "Valid Projector"
                             :host "192.168.1.100"
                             :port 7255
                             :enabled? true
                             :zone-groups [:all]
                             :output-config {:color-bit-depth 8
                                           :xy-bit-depth 16}}
                    :proj-2 {:name "Invalid Projector (nil host)"
                             :host nil
                             :port 7255
                             :enabled? true
                             :zone-groups [:all]}
                    :proj-3 {:name "Invalid Projector (blank host)"
                             :host ""
                             :port 7255
                             :enabled? true
                             :zone-groups [:all]}
                    :proj-4 {:name "Disabled Projector"
                             :host "192.168.1.101"
                             :port 7255
                             :enabled? false
                             :zone-groups [:all]}}})
    
    (let [engines (me/create-engines)]
      (is (= 1 (count engines)) "Should only create engine for valid enabled projector")
      (is (contains? engines :proj-1) "Should include valid projector")
      (is (not (contains? engines :proj-2)) "Should skip projector with nil host")
      (is (not (contains? engines :proj-3)) "Should skip projector with blank host")
      (is (not (contains? engines :proj-4)) "Should skip disabled projector"))))


;; Channel ID Assignment Tests
;;
;; These tests verify that each streaming engine gets a unique channel ID
;; to prevent the service ID alternation bug (BUG-2026-01-25-1)


(deftest engine-uses-service-id-as-channel-id-test
  (testing "Engine uses service-id as channel-id to prevent channel collision"
    (let [projector {:name "Test Projector"
                     :host "192.168.1.100"
                     :port 7255
                     :enabled? true
                     :service-id 7
                     :zone-groups [:all]
                     :output-config {:color-bit-depth 8
                                    :xy-bit-depth 16}}
          engine (me/create-engine-for-projector :test-proj projector)]
      (is (some? engine) "Engine should be created")
      (is (= 7 (:service-id engine)) "Engine should have service-id 7")
      (is (= 7 (:channel-id engine)) "Engine should have channel-id equal to service-id"))))

(deftest engine-default-channel-id-when-no-service-id-test
  (testing "Engine uses 0 for both channel-id and service-id when not specified"
    (let [projector {:name "Test Projector"
                     :host "192.168.1.100"
                     :port 7255
                     :enabled? true
                     :zone-groups [:all]}
          engine (me/create-engine-for-projector :test-proj projector)]
      (is (some? engine) "Engine should be created")
      (is (= 0 (:service-id engine)) "Engine should default to service-id 0")
      (is (= 0 (:channel-id engine)) "Engine should default to channel-id 0"))))

(deftest multiple-engines-get-unique-channel-ids-test
  (testing "Multiple engines for same host get unique channel IDs based on service-id"
    ;; This tests the fix for the bug where all engines shared channel 0,
    ;; causing the IDN device to rapidly switch which service received data
    (state/reset-state!
      {:projectors {:proj-1 {:name "Projector 1"
                             :host "192.168.1.100"
                             :port 7255
                             :enabled? true
                             :service-id 1
                             :zone-groups [:all]}
                    :proj-2 {:name "Projector 2"
                             :host "192.168.1.100"  ;; Same host!
                             :port 7255
                             :enabled? true
                             :service-id 2
                             :zone-groups [:all]}
                    :proj-3 {:name "Projector 3"
                             :host "192.168.1.100"  ;; Same host!
                             :port 7255
                             :enabled? true
                             :service-id 10
                             :zone-groups [:left]}}})
    
    (let [engines (me/create-engines)
          channel-ids (set (map :channel-id (vals engines)))]
      (is (= 3 (count engines)) "Should create 3 engines")
      (is (= #{1 2 10} channel-ids) "Each engine should have unique channel-id from service-id"))))


;; Zone-Specific Frame Routing Tests (Phase 3)
;;
;; These tests verify that projectors receive only frames for their assigned zone groups,
;; properly concatenated with blanking points.


(deftest extract-frames-for-zones-test
  (testing "Extracts frames for specified zone groups from zone-frames map"
    (let [zone-frames {:left   [[0.0 0.0 1.0 0.0 0.0]
                                [0.1 0.0 1.0 0.0 0.0]]
                       :center [[0.5 0.5 0.0 1.0 0.0]
                                [0.6 0.5 0.0 1.0 0.0]]
                       :right  [[1.0 0.0 0.0 0.0 1.0]
                                [1.0 0.1 0.0 0.0 1.0]]}]
      
      (testing "Single zone extraction"
        (let [frames (#'me/extract-frames-for-zones zone-frames [:left])]
          (is (= 1 (count frames)))
          (is (= (:left zone-frames) (first frames)))))
      
      (testing "Multiple zone extraction preserves order"
        (let [frames (#'me/extract-frames-for-zones zone-frames [:left :center])]
          (is (= 2 (count frames)))
          (is (= (:left zone-frames) (first frames)))
          (is (= (:center zone-frames) (second frames)))))
      
      (testing "Missing zone returns nil in vector"
        (let [frames (#'me/extract-frames-for-zones zone-frames [:back])]
          (is (= 1 (count frames)))
          (is (nil? (first frames)))))
      
      (testing "Empty zone-group-ids returns empty vector"
        (let [frames (#'me/extract-frames-for-zones zone-frames [])]
          (is (empty? frames)))))))

(deftest combine-zone-frames-test
  (testing "Combines multiple frames with blanking points between them"
    (let [frame-left [[0.0 0.0 1.0 0.0 0.0]
                      [0.1 0.0 1.0 0.0 0.0]]
          frame-center [[0.5 0.5 0.0 1.0 0.0]
                        [0.6 0.5 0.0 1.0 0.0]]]
      
      (testing "Single frame returns unchanged"
        (let [result (#'me/combine-zone-frames [frame-left])]
          (is (= 2 (count result)))
          (is (= frame-left result))))
      
      (testing "Multiple frames concatenated with blanking"
        (let [result (#'me/combine-zone-frames [frame-left frame-center])]
          (is (> (count result) 4) "Should have blanking points between frames")
          (is (= [0.0 0.0 1.0 0.0 0.0] (first result)) "First point from :left")
          (is (= [0.6 0.5 0.0 1.0 0.0] (last result)) "Last point from :center")))
      
      (testing "Nil frames are filtered out"
        (let [result (#'me/combine-zone-frames [nil frame-left nil])]
          (is (= 2 (count result)))
          (is (= frame-left result))))
      
      (testing "All nil returns nil"
        (let [result (#'me/combine-zone-frames [nil nil])]
          (is (nil? result))))
      
      (testing "Empty vector returns nil"
        (let [result (#'me/combine-zone-frames [])]
          (is (nil? result)))))))

(deftest get-projector-zone-groups-test
  (testing "Gets zone groups from projector config"
    (state/reset-state!
      {:projectors {:proj-1 {:zone-groups [:left :center]}
                    :proj-2 {:zone-groups [:right]}
                    :proj-3 {}}})
    
    (let [raw-state (state/get-raw-state)]
      (testing "Returns configured zone groups"
        (is (= [:left :center] (#'me/get-projector-zone-groups raw-state :proj-1)))
        (is (= [:right] (#'me/get-projector-zone-groups raw-state :proj-2))))
      
      (testing "Returns nil when no zone groups configured"
        (is (nil? (#'me/get-projector-zone-groups raw-state :proj-3))))
      
      (testing "Returns nil for unknown projector"
        (is (nil? (#'me/get-projector-zone-groups raw-state :unknown)))))))

(deftest projector-receives-only-zone-specific-frames-test
  (testing "Projector receives only frames for its configured zone groups"
    (let [zone-frames {:left   [[0.0 0.0 1.0 0.0 0.0]
                                [0.1 0.0 1.0 0.0 0.0]]
                       :center [[0.5 0.5 0.0 1.0 0.0]
                                [0.6 0.5 0.0 1.0 0.0]]
                       :right  [[1.0 0.0 0.0 0.0 1.0]
                                [1.0 0.1 0.0 0.0 1.0]]}
          projector-zones [:left :center]
          projector-frames (#'me/extract-frames-for-zones zone-frames projector-zones)
          combined (#'me/combine-zone-frames projector-frames)] 
      
      (testing "Extracts correct number of zone frames"
        (is (= 2 (count projector-frames))))
      
      (testing "Combined frame contains content from both zones"
        (is (some? combined))
        (is (> (count combined) 4)))
      
      (testing ":right zone is NOT included"
        (let [right-first-point [1.0 0.0 0.0 0.0 1.0]
              right-last-point [1.0 0.1 0.0 0.0 1.0]]
          (is (not (some #{right-first-point} combined)))
          (is (not (some #{right-last-point} combined)))))
      
      (testing ":left and :center content IS included"
        (is (= [0.0 0.0 1.0 0.0 0.0] (first combined)))
        (is (= [0.6 0.5 0.0 1.0 0.0] (last combined))))
      
      (testing "Blanking points exist between zone frames"
        (let [has-blank? (fn [pt] (and (= 0.0 (nth pt 2))
                                       (= 0.0 (nth pt 3))
                                       (= 0.0 (nth pt 4))))]
          (is (some has-blank? combined) "Should have at least one blanking point"))))))
