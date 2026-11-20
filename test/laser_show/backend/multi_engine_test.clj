(ns laser-show.backend.multi-engine-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [laser-show.animation.cue-timing :as ct]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.calibration]
            [laser-show.animation.show-time :as show-time]
            [laser-show.backend.multi-engine :as me]
            [laser-show.backend.output-errors :as output-errors]
            [laser-show.animation.safety :as safety]
            [laser-show.events.core :as events]
            [laser-show.services.frame-service :as fs]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.timeline.playback :as playback]))


;; Test Fixtures

(defn setup-test-state
  "Set up test state before each test"
  [f]
  (state/init-state! (domains/build-initial-state))
  (show-time/reset-runtime!)
  (output-errors/clear!)
  (f)
  (show-time/reset-runtime!))

(use-fixtures :each setup-test-state)

(defn- snapshot
  "A snapshot as the show clock publishes it."
  [cues & {:keys [at-ns playing? projector-ctx] :or {playing? true}}]
  {:tick 1 :at-ns (or at-ns (System/nanoTime)) :playing? playing? :cues cues
   :projector-ctx projector-ctx :preview (delay nil)})


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

(deftest engine-uses-the-projector-scan-rate-test
  (let [scan-rate #(:scan-rate (me/create-engine-for-projector :test-proj (merge {:host "192.168.1.100"} %)))]
    (is (= 40000 (scan-rate {:scan-rate 40000})))
    (is (= 25000 (scan-rate {})) "a projector without one gets the default")
    (is (= 1000 (scan-rate {:scan-rate 10})) "clamped to the range")))

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
  (testing "Projector receives only frames for its configured zone groups, from the tick's snapshot"
    (let [zone-frames {:left   [[0.0 0.0 1.0 0.0 0.0]
                                [0.1 0.0 1.0 0.0 0.0]]
                       :center [[0.5 0.5 0.0 1.0 0.0]
                                [0.6 0.5 0.0 1.0 0.0]]
                       :right  [[1.0 0.0 0.0 0.0 1.0]
                                [1.0 0.1 0.0 0.0 1.0]]}
          _ (state/assoc-in-state! [:projectors :proj] {:zone-groups [:left :center]})
          combined (#'me/projector-frame :proj (snapshot [{:cell [0 0] :zone-frames zone-frames}]))]
      
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
          (is (some has-blank? combined) "Should have at least one blanking point")))

      (testing "the frame says which tick it shows"
        (is (= {:tick 1} (select-keys (meta combined) [:tick])))
        (is (number? (:at-ns (meta combined)))))

      (testing "nothing for the projector's zones: nil"
        (state/assoc-in-state! [:projectors :proj] {:zone-groups [:other]})
        (is (nil? (#'me/projector-frame :proj (snapshot [{:cell [0 0] :zone-frames zone-frames}]))))))))


;; Engine frames from the show clock's tick (show clock 02 §7)

(def ^:private track-id #uuid "10000000-0000-0000-0000-000000000000")

(defn- preset [preset-id & effects]
  {:id (random-uuid) :type :preset :preset-id preset-id :enabled? true
   :effects (vec effects)})

(def ^:private spin {:id (random-uuid) :effect-id :rotation :enabled? true
                     :params {:angle {:type :sine :min 0.0 :max 360.0 :period 1.0}}})

(defn- direct-frame
  "What the projector would send for the cues as they are now: each cue
   rendered with generate-frames-by-zone at its own timing, its projector's
   zones taken and joined, the cues joined, then the projector effects at the
   first cue's timing and the global BPM."
  [projector-id]
  (let [s (state/get-raw-state)
        bpm (get-in s [:timing :bpm])
        zones (get-in s [:projectors projector-id :zone-groups])
        runs (show-time/active-runs s)
        per-cue (keep (fn [{:keys [cue-chain cue-timing]}]
                        (let [cue-bpm (playback/cue-bpm cue-chain bpm)
                              ctx (assoc (ct/get-cue-timing-context cue-timing cue-bpm) :input-values {})
                              by-zone (fs/generate-frames-by-zone cue-chain ctx)]
                          (#'me/combine-zone-frames (#'me/extract-frames-for-zones by-zone zones))))
                      runs)
        combined (#'me/combine-zone-frames (vec per-cue))
        ctx (assoc (ct/get-cue-timing-context (:cue-timing (first runs)) bpm) :input-values {})
        projector-effects (get-in s [:chains :projector-effects projector-id :items])]
    (when combined
      (if (seq projector-effects)
        (effects/apply-effect-chain combined {:effects projector-effects} ctx)
        combined))))

(defn- tick-and-compare
  "Two ticks 20 ms apart, then the engine's frame from the snapshot and the direct render."
  [projector-id]
  (show-clock/tick! (System/nanoTime))
  (java.util.concurrent.locks.LockSupport/parkNanos 20000000)
  (show-clock/tick! (System/nanoTime))
  [(#'me/projector-frame projector-id (show-time/latest)) (direct-frame projector-id)])

(deftest engine-frame-equals-direct-render-test
  (testing "one cue"
    (state/swap-state! #(-> %
                            (assoc-in [:chains :cue-chains [0 0]] {:destination-zone {:zone-group-id :all}
                                                                    :items [(preset :circle spin)]})
                            (assoc-in [:projectors :p] {:zone-groups [:all]})))
    (events/event-handler {:event/type :grid/trigger-cell :col 0 :row 0})
    (let [[engine direct] (tick-and-compare :p)]
      (is (seq engine))
      (is (= direct engine))))
  (testing "two cues on two zones"
    (state/swap-state! #(-> %
                            (assoc-in [:chains :cue-chains [1 0]] {:destination-zone {:zone-group-id :left}
                                                                    :items [(preset :square spin)]})
                            (assoc-in [:projectors :p] {:zone-groups [:left :all]})))
    (events/event-handler {:event/type :grid/trigger-cell :col 1 :row 0})
    (let [[engine direct] (tick-and-compare :p)]
      (is (= 2 (count (show-time/active-runs (state/get-raw-state)))))
      (is (= direct engine))))
  (testing "a timeline with a track effect, to a projector with an RGB curve"
    (show-time/reset-runtime!)
    (state/swap-state! #(-> %
                            (assoc-in [:playback :active-cues] {})
                            (assoc-in [:chains :cue-chains [2 0]]
                                      {:editor-type :timeline
                                       :destination-zone {:zone-group-id :all}
                                       :tracks [{:id track-id :type :track
                                                 :effects [{:id (random-uuid) :effect-id :scale :enabled? true
                                                            :params {:x-scale {:type :sine :min 0.5 :max 1.5 :period 2.0}}}]}]
                                       :items [(assoc (preset :circle) :track-id track-id
                                                      :timeline/start 0.0 :timeline/duration 16.0)]})
                            (assoc-in [:chains :projector-effects :p :items]
                                      [{:id (random-uuid) :effect-id :rgb-curves :enabled? true
                                        :params {:r-curve-points [[0.0 0.2] [1.0 1.0]]
                                                 :g-curve-points [[0.0 0.0] [1.0 0.5]]
                                                 :b-curve-points [[0.0 0.0] [1.0 1.0]]}}])
                            (assoc-in [:projectors :p] {:zone-groups [:all]})))
    (events/event-handler {:event/type :timeline/play :col 2 :row 0 :from 1.5})
    (let [[engine direct] (tick-and-compare :p)]
      (is (seq engine))
      (is (= direct engine)))))

(deftest stale-snapshot-sends-an-empty-frame-test
  (let [provider (#'me/create-projector-frame-provider :p)
        cues [{:cell [0 0] :zone-frames {:all [[0.0 0.0 1.0 1.0 1.0] [0.5 0.0 1.0 1.0 1.0]]}}]]
    (state/assoc-in-state! [:projectors :p] {:zone-groups [:all]})
    (show-time/publish! (snapshot cues))
    (is (seq (provider)) "a fresh tick is sent")
    (show-time/publish! (snapshot cues :at-ns (- (System/nanoTime) (* (inc show-time/STALE_MS) 1000000))))
    (is (nil? (provider)) "no frame from a tick older than STALE_MS: an empty frame goes out")
    (is (str/includes? (str (get-in (output-errors/snapshot) [[:provider :p] :message])) "Show clock stalled")
        "the projector's status says why")
    (show-time/publish! (snapshot cues))
    (is (seq (provider)) "ticks come back")
    (is (nil? (get (output-errors/snapshot) [:provider :p])) "and the error clears")))

(deftest no-snapshot-yet-reports-nothing-test
  (state/assoc-in-state! [:projectors :p] {:zone-groups [:all]})
  (is (nil? (show-time/latest)))
  (is (nil? ((#'me/create-projector-frame-provider :p))) "no clock has ticked (start-test!): an empty frame")
  (is (empty? (output-errors/snapshot)) "and no error"))


;; Frame Provider Tests


(deftest idle-frame-provider-returns-nil-without-exceptions-test
  (let [projector {:name "Test Projector"
                   :host "192.168.1.100"
                   :port 7255
                   :enabled? true
                   :zone-groups [:all]}
        provider (:frame-provider (me/create-engine-for-projector :test-proj projector))
        ex-count (atom 0)
        orig-ex-info ex-info]
    (with-redefs [ex-info (fn [& args] (swap! ex-count inc) (apply orig-ex-info args))]
      (testing "No tick yet"
        (is (nil? (provider))))
      (testing "Not playing"
        (show-time/publish! (snapshot [] :playing? false))
        (is (nil? (provider))))
      (testing "Playing with no active cues"
        (show-time/publish! (snapshot []))
        (is (nil? (provider)))))
    (is (zero? @ex-count) "Idle frames should not build exceptions")))


;; Projector effects (output runtime Phase 2, O2)

(def ^:private failing-calibration-effects
  [{:id :test-calibration-fails-per-point :name "Test: corner pin fails" :category :calibration
    :timing :static :parameters []
    :apply-transducer (fn [_ _ _ _] (map (fn [_] (throw (ex-info "test: corner pin failed" {})))))}
   {:id :test-calibration-fails-to-build :name "Test: bad calibration" :category :calibration
    :timing :static :parameters []
    :apply-transducer (fn [_ _ _ _] (throw (ex-info "test: bad calibration params" {})))}])

(deftest failing-calibration-effect-skips-only-itself-test
  (run! effects/register-effect! failing-calibration-effects)
  (try
    (let [frame [[0.9 0.9 0.0 1.0 1.0] [-0.9 -0.9 0.0 1.0 1.0]]
          curves {:id (random-uuid) :effect-id :rgb-curves :enabled? true
                  :params {:r-curve-points [[0.0 0.5] [1.0 1.0]]
                           :g-curve-points [[0.0 0.0] [1.0 1.0]]
                           :b-curve-points [[0.0 0.0] [1.0 1.0]]}}
          apply-projector-effects #'me/apply-projector-effects]
      (doseq [failing (map :id failing-calibration-effects)]
        (testing (name failing)
          (state/assoc-in-state! [:chains :projector-effects :proj-a :items]
                                 [{:id (random-uuid) :effect-id failing :enabled? true :params {}}
                                  curves])
          (let [out (apply-projector-effects frame :proj-a {:time-ms 0 :bpm 120.0})]
            (is (= [[0.9 0.9] [-0.9 -0.9]] (mapv #(subvec % 0 2) out)) "positions unchanged")
            (is (every? #(< 0.49 (% 2) 0.51) out) "rgb-curves still raises red to 0.5")))))
    (finally
      (swap! effects/!effect-registry #(apply dissoc % (map :id failing-calibration-effects))))))

(deftest skipped-calibration-effect-is-reported-test
  (run! effects/register-effect! failing-calibration-effects)
  (output-errors/clear!)
  (try
    (let [frame [[0.9 0.9 0.0 1.0 1.0] [-0.9 -0.9 0.0 1.0 1.0]]
          fails {:id #uuid "00000000-0000-0000-0000-0000000000c1" :effect-id :test-calibration-fails-per-point :enabled? true :params {}}
          curves {:id (random-uuid) :effect-id :rgb-curves :enabled? true
                  :params {:r-curve-points [[0.0 0.5] [1.0 1.0]]
                           :g-curve-points [[0.0 0.0] [1.0 1.0]]
                           :b-curve-points [[0.0 0.0] [1.0 1.0]]}}
          apply-projector-effects #'me/apply-projector-effects
          warning #(get-in (output-errors/snapshot) [[:calibration :proj-a] :message])]
      (state/assoc-in-state! [:chains :projector-effects :proj-a :items] [fails curves])
      (let [out (apply-projector-effects frame :proj-a {:time-ms 0 :bpm 120.0})]
        (is (every? #(< 0.49 (% 2) 0.51) out) "the frame is the same as before: the others still apply"))
      (is (= "Test: corner pin fails skipped" (warning)) "the skip is named under [:calibration id]")
      (testing "it clears when the effect works again"
        (state/assoc-in-state! [:chains :projector-effects :proj-a :items] [curves])
        (apply-projector-effects frame :proj-a {:time-ms 0 :bpm 120.0})
        (is (nil? (warning)))))
    (finally
      (output-errors/clear!)
      (swap! effects/!effect-registry #(apply dissoc % (map :id failing-calibration-effects))))))


;; Safety zones (zones plan Z1): the last stage of every frame


(def ^:private lit-line
  "A lit line from the middle to x = 0.4."
  [[0.0 0.0 0.0 0.0 0.0] [0.4 0.0 1.0 1.0 1.0]])

(def ^:private double-size-pin
  {:id (random-uuid) :effect-id :corner-pin :enabled? true
   :params {:tl-x -2.0 :tl-y 2.0 :tr-x 2.0 :tr-y 2.0 :bl-x -2.0 :bl-y -2.0 :br-x 2.0 :br-y -2.0}})

(defn- inside-limit? [[x0 y0 x1 y1] frame]
  (every? (fn [[x y]] (and (<= (- x0 1e-9) x (+ x1 1e-9)) (<= (- y0 1e-9) y (+ y1 1e-9)))) frame))

(deftest safety-runs-after-projector-effects-test
  (state/swap-state! #(-> %
                          (assoc-in [:projectors :p] {:zone-groups [:all] :safety {:limit [-0.5 -0.5 0.5 0.5]}})
                          (assoc-in [:chains :projector-effects :p :items] [double-size-pin])))
  (let [out (#'me/projector-frame :p (snapshot [{:cell [0 0] :zone-frames {:all lit-line}}]))]
    (is (seq out))
    (is (inside-limit? [-0.5 -0.5 0.5 0.5] out) "the corner pin pushed the line to 0.8; safety cut it at 0.5")
    (is (some #(and (< (Math/abs (- 0.5 (% 0))) 1e-9) (pos? (% 2))) out) "lit up to the limit")
    (is (= {:tick 1} (select-keys (meta out) [:tick])) "the frame still carries its tick")))

(deftest no-safety-zone-sends-the-same-frame-test
  (state/swap-state! #(assoc-in % [:projectors :p] {:zone-groups [:all]}))
  (let [cues [{:cell [0 0] :zone-frames {:all lit-line}}]]
    (is (= lit-line (#'me/projector-frame :p (snapshot cues))))))

(deftest failing-safety-sends-an-empty-frame-test
  (let [original safety/apply-safety]
    (state/swap-state! #(-> %
                            (assoc-in [:projectors :p] {:zone-groups [:all] :safety {:limit [-0.5 -0.5 0.5 0.5]}})
                            (assoc-in [:projectors :q] {:zone-groups [:all]})))
    (with-redefs [safety/apply-safety (fn [s frame]
                                        (if (= [-0.5 -0.5 0.5 0.5] (:limit (safety/normalize s)))
                                          (throw (ex-info "test: safety failed" {}))
                                          (original s frame)))]
      (let [snap (snapshot [{:cell [0 0] :zone-frames {:all lit-line}}])
            out (#'me/projector-frame :p snap)]
        (is (= [] out) "the projector sends a void frame")
        (is (= {:tick 1} (select-keys (meta out) [:tick])) "and counts the tick")
        (is (re-find #"test: safety failed" (get-in (output-errors/snapshot) [[:safety :p] :message])))
        (is (= lit-line (#'me/projector-frame :q snap)) "the other projector still draws")))))

(deftest unreadable-safety-fails-closed-test
  (state/swap-state! #(assoc-in % [:projectors :p] {:zone-groups [:all] :safety {:limit [0.0 0.0]}}))
  (is (= [] (#'me/projector-frame :p (snapshot [{:cell [0 0] :zone-frames {:all lit-line}}]))))
  (is (some? (get (output-errors/snapshot) [:safety :p])))
  (testing "and it clears once the zone can be read"
    (state/swap-state! #(assoc-in % [:projectors :p :safety] {:limit [-0.9 -0.9 0.9 0.9]}))
    (is (seq (#'me/projector-frame :p (snapshot [{:cell [0 0] :zone-frames {:all lit-line}}]))))
    (is (nil? (get (output-errors/snapshot) [:safety :p])))))

(deftest failing-calibration-is-still-clipped-test
  (run! effects/register-effect! failing-calibration-effects)
  (try
    (state/swap-state! #(-> %
                            (assoc-in [:projectors :p] {:zone-groups [:all] :safety {:limit [-0.3 -0.3 0.3 0.3]}})
                            (assoc-in [:chains :projector-effects :p :items]
                                      [{:id (random-uuid) :effect-id :test-calibration-fails-per-point :enabled? true :params {}}])))
    (let [out (#'me/projector-frame :p (snapshot [{:cell [0 0] :zone-frames {:all lit-line}}]))]
      (is (seq out))
      (is (inside-limit? [-0.3 -0.3 0.3 0.3] out) "a skipped calibration effect can't move light past the limit"))
    (finally
      (swap! effects/!effect-registry #(apply dissoc % (map :id failing-calibration-effects))))))


;; The test pattern (zones plan Z1)


(defn- lit-points [frame] (remove #(and (< (% 2) 1e-6) (< (% 3) 1e-6) (< (% 4) 1e-6)) frame))

(deftest test-pattern-shows-with-nothing-playing-test
  (state/swap-state! #(-> %
                          (assoc-in [:projectors :p] {:zone-groups [:all] :safety {:limit [-0.8 -0.8 0.8 0.8]}})
                          (assoc-in [:projector-ui :calibrating-projector-id] :p)
                          (assoc-in [:projector-ui :calibration-brightness] 0.2)))
  (let [out (#'me/projector-frame :p (snapshot [] :playing? false))]
    (is (seq (lit-points out)) "the pattern shows, though nothing plays")
    (is (inside-limit? [-0.8 -0.8 0.8 0.8] out) "through the safety zone")
    (is (every? #(= [0.2 0.2 0.2] (subvec % 2 5)) (lit-points out)) "at the calibration brightness")
    (is (= {:tick 1} (select-keys (meta out) [:tick]))))
  (testing "only the calibrating projector shows it"
    (state/swap-state! #(assoc-in % [:projectors :q] {:zone-groups [:all]}))
    (is (nil? (#'me/projector-frame :q (snapshot [] :playing? false))))))

(deftest test-pattern-replaces-the-show-test
  (state/swap-state! #(-> %
                          (assoc-in [:projectors :p] {:zone-groups [:all]})
                          (assoc-in [:projector-ui :calibrating-projector-id] :p)))
  (let [out (#'me/projector-frame :p (snapshot [{:cell [0 0] :zone-frames {:all lit-line}}]))]
    (is (not= lit-line out))
    (is (< 300 (count out)) "the pattern, not the cue")))

(deftest master-doesnt-dim-the-test-pattern-test
  (state/swap-state! #(-> %
                          (assoc-in [:projectors :p] {:zone-groups [:all]})
                          (assoc-in [:projector-ui :calibrating-projector-id] :p)
                          (assoc-in [:projector-ui :calibration-brightness] 0.25)
                          (assoc-in [:chains :effect-chains [0 3]]
                                    {:name "Master" :active? true
                                     :items [{:id (random-uuid) :effect-id :intensity :enabled? true
                                              :params {:amount 0.0}}]})))
  (let [out (#'me/projector-frame :p (snapshot [] :playing? false))]
    (is (seq (lit-points out)))
    (is (every? #(= [0.25 0.25 0.25] (subvec % 2 5)) (lit-points out))
        "the pattern skips the render, so a Master at 0 can't hide it")))

(deftest blackout-hides-the-test-pattern-test
  (state/swap-state! #(-> %
                          (assoc-in [:projectors :p] {:zone-groups [:all]})
                          (assoc-in [:projector-ui :calibrating-projector-id] :p)
                          (assoc-in [:vibe-runtime :blackout?] true)))
  (let [out (#'me/projector-frame :p (snapshot [] :playing? false))]
    (is (= [] out))
    (is (= {:tick 1} (select-keys (meta out) [:tick])))))

(deftest blackout-leaves-a-grid-cue-alone-test
  (state/swap-state! #(-> %
                          (assoc-in [:projectors :p] {:zone-groups [:all]})
                          (assoc-in [:vibe-runtime :blackout?] true)))
  (is (= lit-line (#'me/projector-frame :p (snapshot [{:cell [0 0] :zone-frames {:all lit-line}}])))
      "BLACKOUT changes only the test pattern; a grid cue started by hand plays as today"))

(deftest identify-flashes-then-stops-test
  (state/swap-state! #(assoc-in % [:projectors :p] {:zone-groups [:all]}))
  (let [cues [{:cell [0 0] :zone-frames {:all lit-line}}]
        frame-at (fn [now-ms]
                   (with-redefs [me/now-ms (constantly now-ms)]
                     (#'me/projector-frame :p (snapshot cues))))]
    (state/swap-state! #(assoc-in % [:projector-ui :identify :p] 10000))
    (is (< 300 (count (frame-at 7000))) "lit: the pattern instead of the show")
    (is (= [] (frame-at 7300)) "dark for the next 250 ms")
    (is (< 300 (count (frame-at 7500))) "lit again")
    (is (= lit-line (frame-at 10000)) "at its end time the show comes back, even before it is cleared")))

