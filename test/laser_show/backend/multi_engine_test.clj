(ns laser-show.backend.multi-engine-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [laser-show.animation.cue-timing :as ct]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.calibration]
            [laser-show.animation.fit-guard :as fit-guard]
            [laser-show.animation.show-time :as show-time]
            [laser-show.backend.multi-engine :as me]
            [laser-show.backend.output-errors :as output-errors]
            [laser-show.animation.safety :as safety]
            [laser-show.events.core :as events]
            [laser-show.routing.compose :as compose]
            [laser-show.routing.rig :as rig]
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
  "A snapshot as the show clock publishes it, its routing and what the
   engines take from app state (multi-engine/engine-inputs, at wall-ms, now
   by default) made from app state as it is now (zones plan Z3, state
   integrity 01)."
  [cues & {:keys [at-ns playing? projector-ctx wall-ms] :or {playing? true}}]
  (let [s (state/get-raw-state)]
    {:tick 1 :at-ns (or at-ns (System/nanoTime)) :playing? playing? :cues cues
     :projector-ctx projector-ctx :preview (delay nil)
     :routing (rig/routing (rig/routing-inputs s nil))
     :engine-inputs (me/engine-inputs s (or wall-ms (System/currentTimeMillis)))}))


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
      (is (= "192.168.1.100" (get-in engine [:driver-config :host])) "Engine should have correct host"))))

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
      (is (= 7 (get-in engine [:driver-config :service-id])) "Engine should have service-id 7")
      (is (= 7 (get-in engine [:driver-config :channel-id])) "Engine should have channel-id equal to service-id"))))

(deftest engine-default-channel-id-when-no-service-id-test
  (testing "Engine uses 0 for both channel-id and service-id when not specified"
    (let [projector {:name "Test Projector"
                     :host "192.168.1.100"
                     :port 7255
                     :enabled? true
                     :zone-groups [:all]}
          engine (me/create-engine-for-projector :test-proj projector)]
      (is (some? engine) "Engine should be created")
      (is (= 0 (get-in engine [:driver-config :service-id])) "Engine should default to service-id 0")
      (is (= 0 (get-in engine [:driver-config :channel-id])) "Engine should default to channel-id 0"))))

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
          channel-ids (set (map (comp :channel-id :driver-config) (vals engines)))]
      (is (= 3 (count engines)) "Should create 3 engines")
      (is (= #{1 2 10} channel-ids) "Each engine should have unique channel-id from service-id"))))


;; Routing (zones plan Z3): each engine composes its laser's frame from the
;; snapshot's routing (routing.compose), never from app state


(deftest engine-routes-from-the-snapshot-not-app-state-test
  (let [graphics [[0.0 0.5 0.0 1.0 0.0] [0.5 0.5 0.0 1.0 0.0]]
        cues [{:cell [0 0] :zone-frames {:graphics graphics}}]]
    (state/assoc-in-state! [:projectors :p] {:host "10.0.0.5" :enabled? true :zone-groups [:graphics]})
    (let [snap (snapshot cues)]
      (state/assoc-in-state! [:projectors :p :zone-groups] [])
      (is (= graphics (#'me/projector-frame :p snap)) "the tick's routing, not an edit made since")
      (is (nil? (#'me/projector-frame :p (snapshot cues))) "the next tick's follows the edit")
      (testing "no engine reads the groups from app state"
        (with-redefs [rig/refs-for (fn [& _] (throw (ex-info "test: read live" {})))
                      rig/routing (fn [& _] (throw (ex-info "test: worked out by the engine" {})))]
          (is (= graphics (#'me/projector-frame :p snap)))))
      (testing "a snapshot with no routing reaches no laser"
        (is (nil? (#'me/projector-frame :p (dissoc snap :routing))))))))

(deftest projector-draws-all-without-listing-it-test
  (let [all-frame [[0.0 0.0 1.0 1.0 1.0] [0.5 0.0 1.0 1.0 1.0]]]
    (state/assoc-in-state! [:projectors :p] {:host "10.0.0.5" :enabled? true :zone-groups []
                                             :exclude-from-all? false})
    (is (= all-frame (#'me/projector-frame :p (snapshot [{:cell [0 0] :zone-frames {:all all-frame}}])))
        "every enabled projector plays All, with nothing ticked (D-20)")))

(deftest excluded-projector-skips-all-test
  (let [all-frame [[0.0 0.0 1.0 1.0 1.0] [0.5 0.0 1.0 1.0 1.0]]
        graphics [[0.0 0.5 0.0 1.0 0.0] [0.5 0.5 0.0 1.0 0.0]]
        frame #(#'me/projector-frame :p (snapshot [{:cell [0 0] :zone-frames %}]))]
    (state/assoc-in-state! [:projectors :p] {:host "10.0.0.5" :enabled? true :zone-groups [:graphics]
                                             :exclude-from-all? true})
    (is (nil? (frame {:all all-frame})) "Exclude from All: All's content isn't drawn")
    (is (nil? (frame {:auto/left all-frame :auto/odd all-frame})) "nor Left's or Odd's (D-33)")
    (is (= graphics (frame {:all all-frame :graphics graphics})) "its own groups still are")
    (is (= graphics (frame {:auto.laser/p graphics})) "and the group of its own")))

(deftest reorder-reroutes-left-test
  (let [left-frame [[-0.5 0.0 1.0 1.0 1.0] [-0.4 0.0 1.0 1.0 1.0]]
        cues [{:cell [0 0] :zone-frames {:auto/left left-frame}}]]
    (state/swap-state! assoc :projectors {:a {:name "A" :host "10.0.0.5" :enabled? true :zone-groups []}
                                          :b {:name "B" :host "10.0.0.6" :enabled? true :zone-groups []}})
    (is (= left-frame (#'me/projector-frame :a (snapshot cues))) "by IP, A is on the left")
    (is (nil? (#'me/projector-frame :b (snapshot cues))))
    (state/assoc-in-state! [:rig :order] [:b :a])
    (is (nil? (#'me/projector-frame :a (snapshot cues))) "moved: the next frame follows, no restart")
    (is (= left-frame (#'me/projector-frame :b (snapshot cues))))))

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
      
      (testing ":left and :center content IS included, in the spec's key order: Center before Left (S-4)"
        (is (= [0.5 0.5 0.0 1.0 0.0] (first combined)))
        (is (= [0.1 0.0 1.0 0.0 0.0] (last combined)))
        (is (= (fs/concatenate-frames [(:center zone-frames) (:left zone-frames)]) combined)))
      
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
   rendered with generate-frames-by-zone at its own timing, composed for the
   projector (routing.compose, on the routing app state gives now), then the
   projector effects at the first cue's timing and the global BPM."
  [projector-id]
  (let [s (state/get-raw-state)
        bpm (get-in s [:timing :bpm])
        runs (show-time/active-runs s)
        cues (mapv (fn [{:keys [cell cue-chain cue-timing]}]
                     (let [cue-bpm (playback/cue-bpm cue-chain bpm)
                           ctx (assoc (ct/get-cue-timing-context cue-timing cue-bpm) :input-values {})]
                       {:cell cell :zone-frames (fs/generate-frames-by-zone cue-chain ctx)}))
                   runs)
        combined (:frame (compose/projector-frame cues (rig/routing (rig/routing-inputs s nil)) projector-id))
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
                            (assoc-in [:chains :cue-chains [1 0]] {:target :left
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

(deftest one-laser-draws-left-and-right-halves-test
  (testing "spec §6.2 b through the clock: one laser, a circle to Left and a wave to Right, each in its half"
    (state/swap-state! #(-> %
                            (assoc-in [:chains :cue-chains [0 0]]
                                      {:destination-zone {:zone-group-id :all}
                                       :items [(assoc (preset :circle) :target :auto/left)
                                               (assoc (preset :wave) :target :auto/right)]})
                            (assoc :projectors {:p {:host "10.0.0.5" :enabled? true :zone-groups []}})))
    (events/event-handler {:event/type :grid/trigger-cell :col 0 :row 0})
    (let [s (show-clock/tick! (System/nanoTime))
          {:auto/keys [left right]} (get-in s [:cues 0 :zone-frames])
          {:keys [L R]} (get-in s [:routing :outputs :p :regions])
          out (#'me/projector-frame :p s)]
      (is (and (seq left) (seq right)))
      (is (= (fs/concatenate-frames [(compose/fit-region left L) (compose/fit-region right R)]) out)
          "the circle fitted in the left half, a blanking jump, the wave in the right")
      (is (= (+ (count left) 2 (count right)) (count out)))
      (is (every? (fn [[x]] (<= -1.0 x 0.5)) (compose/fit-region left L)) "Left is the left 75% of the width")
      (is (every? (fn [[x]] (<= -0.5 x 1.0)) (compose/fit-region right R)) "Right the right 75%"))))

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
          (let [out (apply-projector-effects frame :proj-a
                                             [{:id (random-uuid) :effect-id failing :enabled? true :params {}} curves]
                                             {:time-ms 0 :bpm 120.0})]
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
      (let [out (apply-projector-effects frame :proj-a [fails curves] {:time-ms 0 :bpm 120.0})]
        (is (every? #(< 0.49 (% 2) 0.51) out) "the frame is the same as before: the others still apply"))
      (is (= "Test: corner pin fails skipped" (warning)) "the skip is named under [:calibration id]")
      (testing "it clears when the effect works again"
        (apply-projector-effects frame :proj-a [curves] {:time-ms 0 :bpm 120.0})
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
        frame-at (fn [wall-ms] (#'me/projector-frame :p (snapshot cues :wall-ms wall-ms)))]
    (state/swap-state! #(assoc-in % [:projector-ui :identify :p] 10000))
    (is (< 300 (count (frame-at 7000))) "lit: the pattern instead of the show")
    (is (= [] (frame-at 7300)) "dark for the next 250 ms")
    (is (< 300 (count (frame-at 7500))) "lit again")
    (is (= lit-line (frame-at 10000)) "at its end time the show comes back, even before it is cleared")))



;; The fit guard (zones plan Z3): after composition, before the projector
;; effects; its record rides on the frame

(def ^:private heavy
  "3,000 lit points: at 25,000 pts/s, 8 fps."
  (vec (for [i (range 3000)]
         (let [a (/ (* 2.0 Math/PI i) 3000)] [(* 0.5 (Math/cos a)) (* 0.5 (Math/sin a)) 1.0 1.0 1.0]))))

(def ^:private identity-curves
  {:id (random-uuid) :effect-id :rgb-curves :enabled? true
   :params {:r-curve-points [[0.0 0.0] [1.0 1.0]] :g-curve-points [[0.0 0.0] [1.0 1.0]] :b-curve-points [[0.0 0.0] [1.0 1.0]]}})

(deftest fit-guard-runs-before-projector-effects-test
  (state/swap-state! #(-> %
                          (assoc-in [:projectors :p] {:host "10.0.0.5" :enabled? true :zone-groups [] :scan-rate 25000})
                          (assoc-in [:chains :projector-effects :p :items] [identity-curves])))
  (let [seen (atom [])
        apply-chain effects/apply-effect-chain]
    (with-redefs [effects/apply-effect-chain (fn [frame chain ctx] (swap! seen conj (count frame)) (apply-chain frame chain ctx))]
      (let [out (#'me/projector-frame :p (snapshot [{:cell [0 0] :zone-frames {:all heavy}}]))]
        (is (= [1800] @seen) "the calibration gets the thinned frame: 60% of 3,000")
        (is (= 1800 (count out)))
        (is (= [3000 1800] ((juxt :points-in :points) (:fit (meta out)))))
        (is (not (contains? (:fit (meta out)) :effect-points))
            "a chain that keeps the count: the load meter counts what the laser is asked for (Q-Z3-4 A)")))))

(deftest frame-carries-its-fit-record-test
  (state/assoc-in-state! [:projectors :p] {:host "10.0.0.5" :enabled? true :zone-groups []})
  (let [a [[-0.5 0.0 1.0 1.0 1.0] [0.5 0.0 1.0 1.0 1.0]]
        b [[-0.5 0.2 0.0 1.0 0.0] [0.5 0.2 0.0 1.0 0.0]]
        out (#'me/projector-frame :p (snapshot [{:cell [0 0] :zone-frames {:auto/left a :auto/right b}}]))
        {:keys [fit tick]} (meta out)]
    (is (= 1 tick) "still the tick it shows")
    (is (= {:points-in 6 :points 6 :budget 1250 :min-fps 20 :scan-rate 25000 :reduced-pct 0.0 :dim-pct 0.0 :regions 2}
           (dissoc fit :est-fps :guard-fps))
        "one laser, Left and Right: its two halves, 6 points, nothing cut")
    (is (= (/ 25000.0 6) (:est-fps fit) (:guard-fps fit))))
  (testing "a heavy frame: what the guard cut"
    (let [{:keys [fit]} (meta (#'me/projector-frame :p (snapshot [{:cell [0 0] :zone-frames {:all heavy}}])))]
      (is (= [3000 1800 0] ((juxt :points-in :points :regions) fit)))
      (is (< 39.9 (:reduced-pct fit) 40.1))))
  (testing "a safe area too small to split says so"
    (state/assoc-in-state! [:projectors :p :safety] {:limit [0.0 -1.0 0.04 1.0]})
    (is (true? (:no-room? (:fit (meta (#'me/projector-frame :p (snapshot [{:cell [0 0] :zone-frames {:auto/left heavy}}])))))))))

(deftest test-pattern-frame-carries-no-load-test
  (state/swap-state! #(-> %
                          (assoc-in [:projectors :p] {:zone-groups [:all]})
                          (assoc-in [:projector-ui :calibrating-projector-id] :p)))
  (let [out (#'me/projector-frame :p (snapshot [{:cell [0 0] :zone-frames {:all heavy}}]))]
    (is (< 300 (count out)) "the pattern, not the 3,000 points")
    (is (= 1 (:tick (meta out))))
    (is (not (contains? (meta out) :fit)) "it skips composition and the guard: no load while it shows (S-13)")))

(deftest provider-smooths-and-resets-fit-test
  ;; What the card's thumbnail shows is the driver's last return, not this
  ;; (output-thumbnail-test)
  (state/assoc-in-state! [:projectors :p] {:host "10.0.0.5" :enabled? true :zone-groups []})
  (let [provider (#'me/create-projector-frame-provider :p)
        cues [{:cell [0 0] :zone-frames {:all heavy}}]
        fit #(:fit (meta %))]
    (show-time/publish! (snapshot cues))
    (let [frame (provider)]
      (is (= 1800 (count frame)) "what it produced: after the guard, calibration and safety")
      (is (true? (:low? (fit frame))) "the record is smoothed (fit-guard/smooth): 13.9 fps is low")
      (is (= 1 (:samples (fit frame)))))
    (testing "averaged over the frames it makes"
      (show-time/publish! (snapshot cues))
      (is (= 2 (:samples (fit (provider))))))
    (testing "nothing playing: nil, and the average starts again"
      (show-time/publish! (snapshot [] :playing? false))
      (is (nil? (provider)))
      (show-time/publish! (snapshot cues))
      (is (= 1 (:samples (fit (provider))))))
    (testing "a stale tick: nil, reported, and the average starts again"
      (show-time/publish! (snapshot cues :at-ns (- (System/nanoTime) (* (inc show-time/STALE_MS) 1000000))))
      (is (nil? (provider)))
      (is (= "Show clock stalled" (get-in (output-errors/snapshot) [[:provider :p] :message])))
      (show-time/publish! (snapshot cues))
      (is (= 1 (:samples (fit (provider))))))))


;; Decimate in a laser's calibration chain (zones plan 12): the record counts
;; what it left

(def ^:private decimate
  {:id (random-uuid) :effect-id :decimate :enabled? true :params {:kpps 25 :fps 30}})

(deftest decimate-in-a-calibration-chain-counts-on-the-card-test
  (state/swap-state! #(-> %
                          (assoc-in [:projectors :p] {:host "10.0.0.5" :enabled? true :zone-groups [] :scan-rate 25000})
                          (assoc-in [:chains :projector-effects :p :items] [decimate])))
  (let [cues [{:cell [0 0] :zone-frames {:all heavy}}]
        out (#'me/projector-frame :p (snapshot cues))
        fit (:fit (meta out))]
    (is (= 833 (count out)) "the guard's 1,800 points, then Decimate's 833")
    (is (= [3000 833] ((juxt :points-in :points) fit)) "the record counts what Decimate left")
    (is (= 833 (:effect-points fit)) "and so does the load meter: 833 of its 1,250 (Q-D-1 B)")
    (is (<= 30.0 (:guard-fps fit)) "25,000 / 833: 30 fps")
    (is (< 72.2 (:reduced-pct fit) 72.3) "2,167 of the 3,000 points cut")
    (is (= [1250 20 0.0 (/ 25000.0 3000)] ((juxt :budget :min-fps :dim-pct :est-fps) fit)) "the rest is the guard's")
    (testing "so the laser isn't low"
      (let [provider (#'me/create-projector-frame-provider :p)]
        (show-time/publish! (snapshot cues))
        (let [frame (provider)]
          (is (= 833 (count frame) (:points (:fit (meta frame))) (:effect-points (:fit (meta frame)))))
          (is (false? (:low? (:fit (meta frame))))))
        (testing "and a dark frame (a Strobe's dark half), which Decimate can't thin, leaves its meter as it was (S-14)"
          (show-time/publish! (snapshot [{:cell [0 0] :zone-frames {:all (mapv #(assoc % 2 0.0 3 0.0 4 0.0) heavy)}}]))
          (let [fit (:fit (meta (provider)))]
            (is (true? (:dark? fit)))
            (is (= [3000 833] ((juxt :points :effect-points) fit)) "it sends the 3,000 dark points; the meter reads 833")))))
    (testing "the safety stage's crossing points aren't counted: the record says what reaches it"
      (state/assoc-in-state! [:projectors :p :safety] {:limit [-0.4 -1.0 0.4 1.0]})
      (let [out (#'me/projector-frame :p (snapshot cues))]
        (is (= (+ 833 4) (count out)) "a point more where the circle crosses the limit, four times")
        (is (= 833 (:points (:fit (meta out)))))))))

(defn- lit-circle
  "n lit points round a circle."
  [n]
  (vec (for [i (range n)]
         (let [a (/ (* 2.0 Math/PI i) n)] [(* 0.5 (Math/cos a)) (* 0.5 (Math/sin a)) 1.0 1.0 1.0]))))

(deftest decimate-that-changes-nothing-still-meters-what-the-laser-draws-test
  (testing "Q-D-3 B: Decimate at 15 fps (1,666 points) on a laser at 25 kpps with a minimum of 20"
    (state/swap-state! #(-> %
                            (assoc-in [:projectors :p] {:host "10.0.0.5" :enabled? true :zone-groups [] :scan-rate 25000})
                            (assoc-in [:chains :projector-effects :p :items] [(assoc-in decimate [:params :fps] 15)])))
    (let [fit-of (fn [n] (:fit (meta (#'me/projector-frame :p (snapshot [{:cell [0 0] :zone-frames {:all (lit-circle n)}}])))))]
      (is (= [2700 1620 1620] ((juxt :points-in :points :effect-points) (fit-of 2700)))
          "the guard leaves 1,620 of 2,700, under Decimate's budget, so it cuts none: the meter counts the 1,620, 130%, not 216%")
      (is (= [2800 1666 1666] ((juxt :points-in :points :effect-points) (fit-of 2800)))
          "2,800: Decimate cuts the guard's 1,680 to 1,666, 133%: a heavier cue no longer reads lower")
      (is (= [500 500] ((juxt :points :effect-points) (fit-of 500))) "under every budget: what it draws is what it is asked for")
      (testing "a disabled Decimate, or none: what the laser is asked for, as before (Q-Z3-4 A)"
        (state/assoc-in-state! [:chains :projector-effects :p :items] [(assoc decimate :enabled? false)])
        (is (= [2700 1620] ((juxt :points-in :points) (fit-of 2700))))
        (is (not (contains? (fit-of 2700) :effect-points)))
        (state/assoc-in-state! [:chains :projector-effects :p :items] [identity-curves])
        (is (not (contains? (fit-of 2700) :effect-points)))))))


;; Every frame from one tick (plans/2026-11-20-state-integrity/01): the
;; snapshot carries what the engines took from app state, and no engine reads
;; it

(deftest engine-inputs-are-the-ticks-own-maps-test
  (let [state {:projectors {:p1 {:host "10.0.0.5" :enabled? true
                                 :safety {:limit [-0.9 -0.8 0.9 0.95] :blocked [[-0.2 -1.0 0.2 -0.4]]}}
                            :p2 {:driver :lsps :driver-config {:host "10.0.0.6" :port 7266} :enabled? true}}
               :chains {:projector-effects {:p1 {:items [identity-curves double-size-pin decimate]}
                                            :p2 {:items [identity-curves double-size-pin]}}}
               :projector-ui {:calibrating-projector-id :p2 :calibration-brightness 0.1 :identify {:p1 1763686500000}}
               :vibe-runtime {:blackout? false}}
        inputs (me/engine-inputs state 1763686498000)]
    (is (= {:projectors (:projectors state)
            :projector-effects (get-in state [:chains :projector-effects])
            :calibrating :p2
            :brightness 0.1
            :identify {:p1 1763686500000}
            :blackout? false
            :wall-ms 1763686498000}
           inputs))
    (testing "the state's own maps, not copies"
      (is (identical? (:projectors state) (:projectors inputs)))
      (is (identical? (get-in state [:chains :projector-effects]) (:projector-effects inputs)))
      (is (identical? (:projector-effects (rig/routing-inputs state nil)) (:projector-effects inputs))
          "the chains the routing's Decimate budgets come from"))
    (testing "a state with no test pattern, Identify or BLACKOUT"
      (is (= {:calibrating nil :brightness 0.1 :identify nil :blackout? false}
             (select-keys (me/engine-inputs {:projectors {}} 5) [:calibrating :brightness :identify :blackout?]))))
    (testing "the calibration brightness and BLACKOUT as set"
      (is (= [0.4 true] ((juxt :brightness :blackout?)
                         (me/engine-inputs {:projector-ui {:calibration-brightness 0.4} :vibe-runtime {:blackout? :yes}} 5)))))))

(def ^:private line-to-08
  "A lit line from the middle to x = 0.8."
  [[0.0 0.0 0.0 0.0 0.0] [0.8 0.0 1.0 1.0 1.0]])

(defn- pin
  "A corner pin with its corners at ±s: it scales every point by s."
  [s]
  {:id #uuid "00000000-0000-0000-0000-0000000000a1" :effect-id :corner-pin :enabled? true
   :params {:tl-x (- s) :tl-y s :tr-x s :tr-y s :bl-x (- s) :bl-y (- s) :br-x s :br-y (- s)}})

(defn- box [h] {:limit [(- h) (- h) h h]})

(defn- reach
  "How far right the frame's light goes: the largest x of a lit point, 0 for none."
  [frame]
  (reduce max 0.0 (map #(double (first %)) (lit-points frame))))

(defn- near? [a b] (< (Math/abs (- (double a) (double b))) 1e-6))

(defn- laser [host] {:host host :enabled? true :zone-groups [] :exclude-from-all? false})

(deftest calibration-edit-waits-for-the-next-tick-test
  (state/swap-state! #(-> %
                          (assoc-in [:chains :cue-chains [0 0]] {:destination-zone {:zone-group-id :all}
                                                                  :items [(preset :circle)]})
                          (assoc-in [:projectors :p] (laser "10.0.0.5"))
                          (assoc-in [:chains :projector-effects :p :items] [(pin 1.0)])))
  (events/event-handler {:event/type :grid/trigger-cell :col 0 :row 0})
  (with-redefs [events/dispatch! (fn [_])]
    (show-clock/tick! 0)
    (let [s (show-clock/tick! 16666667)
          before (#'me/projector-frame :p s)]
      (state/assoc-in-state! [:chains :projector-effects :p :items] [(pin 0.5)])
      (let [after (#'me/projector-frame :p s)]
        (is (near? 0.5 (reach before)) "the circle of radius 0.5 through a pin of 1.0")
        (is (= before after) "the tick's frame keeps the tick's pin")
        (is (near? 0.25 (reach (#'me/projector-frame :p (show-clock/tick! 33333334))))
            "the next tick's has the edit")))))

(deftest decimate-edit-waits-for-the-next-tick-test
  (state/assoc-in-state! [:projectors :p] (assoc (laser "10.0.0.5") :scan-rate 25000))
  (let [cues [{:cell [0 0] :zone-frames {:all heavy}}]
        counts (fn [frame] [(count frame) (:points (:fit (meta frame)))])
        snap (snapshot cues)
        before (#'me/projector-frame :p snap)
        provider (#'me/create-projector-frame-provider :p)]
    (show-time/publish! snap)
    (state/assoc-in-state! [:chains :projector-effects :p :items] [decimate])
    (let [after (#'me/projector-frame :p snap)]
      (is (= [1800 1800] (counts before)))
      (is (= before after) "Decimate added after the tick: the tick's frame keeps the guard's 1,800 points")
      (is (= [1800 1800] (counts after)) "and its record, which the card and LOW FPS read, says so")
      (is (= [1800 1800] (counts (provider))) "so does the provider's smoothed record"))
    (let [next-snap (snapshot cues)]
      (is (= [833 833] (counts (#'me/projector-frame :p next-snap))) "the next tick's frame is Decimate's")
      (show-time/publish! next-snap)
      (is (= [833 833] (counts (provider)))))))

(deftest decimate-removal-waits-for-the-next-tick-test
  (state/swap-state! #(-> %
                          (assoc-in [:projectors :p] (assoc (laser "10.0.0.5") :scan-rate 25000))
                          (assoc-in [:chains :projector-effects :p :items] [decimate])))
  (let [cues [{:cell [0 0] :zone-frames {:all heavy}}]
        counts (fn [frame] (let [fit (:fit (meta frame))] [(count frame) (:points fit) (:effect-points fit)]))
        snap (snapshot cues)]
    (is (= 833 (get-in snap [:routing :outputs :p :decimate-budget])) "the tick's routing meters by Decimate's budget")
    (state/assoc-in-state! [:chains :projector-effects :p :items] [])
    (is (= [833 833 833] (counts (#'me/projector-frame :p snap)))
        "Decimate removed after the tick: the tick's frame is still its chain's, metered by its rule")
    (is (= [1800 1800 nil] (counts (#'me/projector-frame :p (snapshot cues))))
        "the next tick's: no Decimate, and the meter counts what the laser is asked for")))

(defn- circle-points
  "n lit points on a circle of radius r around the middle."
  [n r]
  (mapv (fn [i] (let [a (/ (* 2.0 Math/PI i) n)] [(* r (Math/cos a)) (* r (Math/sin a)) 0.0 1.0 0.0]))
        (range n)))

(deftest safety-edit-waits-for-the-next-tick-test
  (state/swap-state! #(-> %
                          (assoc-in [:projectors :p] (laser "10.0.0.5"))
                          (assoc-in [:projectors :p :safety] (box 0.9))))
  (let [cues [{:cell [0 0] :zone-frames {:auto/left (circle-points 64 0.9)}}]
        snap (snapshot cues)
        left (fn [s] (get-in s [:routing :outputs :p :regions :L]))]
    (is (every? true? (map near? [-0.225 0.0 0.675] (left snap))) "the tick fits Left to the ±0.9 box")
    (state/assoc-in-state! [:projectors :p :safety] (box 0.3))
    (let [out (#'me/projector-frame :p snap)]
      (is (= 64 (count (lit-points out))) "narrowed after the tick: the tick's frame is clipped to the box it was fitted to")
      (is (inside-limit? [-0.9 -0.9 0.9 0.9] out)))
    (let [next-snap (snapshot cues)
          out (#'me/projector-frame :p next-snap)]
      (is (every? true? (map near? [-0.075 0.0 0.225] (left next-snap))) "the next tick fits Left to the ±0.3 box")
      (is (= 64 (count (lit-points out))))
      (is (inside-limit? [-0.3 -0.3 0.3 0.3] out) "and clips to it"))))

(deftest blackout-and-test-pattern-wait-for-the-next-tick-test
  (state/assoc-in-state! [:projectors :p] (laser "10.0.0.5"))
  (testing "BLACKOUT pressed after the tick, the test pattern on"
    (state/assoc-in-state! [:projector-ui :calibrating-projector-id] :p)
    (let [snap (snapshot [] :playing? false)]
      (state/assoc-in-state! [:vibe-runtime :blackout?] true)
      (is (= 537 (count (#'me/projector-frame :p snap))) "the tick's frame is the pattern")
      (is (= [] (#'me/projector-frame :p (snapshot [] :playing? false))) "the next tick's is dark"))
    (state/swap-state! #(-> % (assoc-in [:vibe-runtime :blackout?] false)
                            (assoc-in [:projector-ui :calibrating-projector-id] nil))))
  (testing "the test pattern turned on after the tick"
    (let [cues [{:cell [0 0] :zone-frames {:all line-to-08}}]
          snap (snapshot cues)]
      (state/assoc-in-state! [:projector-ui :calibrating-projector-id] :p)
      (is (= line-to-08 (#'me/projector-frame :p snap)) "the tick's frame is the cue")
      (is (= 537 (count (#'me/projector-frame :p (snapshot cues)))) "the next tick's the pattern"))
    (state/assoc-in-state! [:projector-ui :calibrating-projector-id] nil))
  (testing "Identify pressed after the tick"
    (let [cues [{:cell [0 0] :zone-frames {:all line-to-08}}]
          snap (snapshot cues :wall-ms 7000)]
      (state/assoc-in-state! [:projector-ui :identify :p] 10000)
      (is (= line-to-08 (#'me/projector-frame :p snap)) "the tick's frame is the cue")
      (is (< 300 (count (#'me/projector-frame :p (snapshot cues :wall-ms 7000)))) "the next tick's flashes"))))

(deftest edit-while-a-frame-is-made-leaves-it-alone-test
  (state/swap-state! #(-> %
                          (assoc-in [:projectors :p] (assoc (laser "10.0.0.5") :safety (box 0.9)))
                          (assoc-in [:chains :projector-effects :p :items] [(pin 1.0)])))
  (let [cues [{:cell [0 0] :zone-frames {:all line-to-08}}]
        snap (snapshot cues)
        guard fit-guard/guard
        edit! #(state/swap-state! (fn [s] (-> s (assoc-in [:projectors :p :safety] (box 0.3))
                                              (assoc-in [:chains :projector-effects :p :items] [(pin 0.5)]))))
        out (with-redefs [fit-guard/guard (fn [& args] (edit!) (apply guard args))]
              (#'me/projector-frame :p snap))]
    (is (near? 0.8 (reach out)) "the pin and the limit edited while the frame is composed: the tick's pin, the tick's limit")
    (is (near? 0.3 (reach (#'me/projector-frame :p (snapshot cues)))) "the next tick's: both new")))

(deftest lasers-of-one-tick-share-its-state-test
  (state/swap-state! #(-> %
                          (assoc :projectors {:a (laser "10.0.0.5") :b (laser "10.0.0.6")})
                          (assoc-in [:chains :projector-effects :a :items] [(pin 1.0)])
                          (assoc-in [:chains :projector-effects :b :items] [(pin 1.0)])))
  (let [snap (snapshot [{:cell [0 0] :zone-frames {:all line-to-08}}])
        a (#'me/projector-frame :a snap)]
    (state/swap-state! #(-> % (assoc-in [:chains :projector-effects :a :items] [(pin 0.5)])
                            (assoc-in [:chains :projector-effects :b :items] [(pin 0.5)])))
    (let [b (#'me/projector-frame :b snap)]
      (is (near? 0.8 (reach a)))
      (is (near? 0.8 (reach b)) "both pins edited between the two lasers' frames: both draw the tick's"))))

(deftest engine-reads-no-app-state-test
  (let [cues [{:cell [0 0] :zone-frames {:all line-to-08}}]
        frame-of (fn [snap] (try (#'me/projector-frame :p snap) (catch Throwable e (ex-message e))))
        cases (atom [])
        case! (fn [label snap] (swap! cases conj [label snap (frame-of snap)]))]
    (state/swap-state! #(-> %
                            (assoc-in [:projectors :p] (assoc (laser "10.0.0.5") :safety {:limit [-0.5 -0.5 0.5 0.5]
                                                                                       :blocked [[-0.1 -0.1 0.1 0.1]]}))
                            (assoc-in [:chains :projector-effects :p :items] [identity-curves (pin 0.5)])))
    (case! "a show frame with calibration and a zone" (snapshot cues))
    (state/assoc-in-state! [:projector-ui :calibrating-projector-id] :p)
    (case! "the test pattern" (snapshot [] :playing? false))
    (state/swap-state! #(-> % (assoc-in [:projector-ui :calibrating-projector-id] nil)
                            (assoc-in [:projector-ui :identify :p] 10000)))
    (case! "Identify, lit" (snapshot cues :wall-ms 7000))
    (case! "Identify, dark" (snapshot cues :wall-ms 7300))
    (state/swap-state! #(-> % (update :projector-ui dissoc :identify)
                            (assoc-in [:projector-ui :calibrating-projector-id] :p)
                            (assoc-in [:vibe-runtime :blackout?] true)))
    (case! "BLACKOUT on the pattern" (snapshot [] :playing? false))
    (state/swap-state! #(-> % (assoc-in [:projector-ui :calibrating-projector-id] nil)
                            (assoc-in [:vibe-runtime :blackout?] false)))
    (case! "nothing playing" (snapshot [] :playing? false))
    (state/assoc-in-state! [:projectors :p :safety] {:limit [0.0 0.0]})
    (case! "an unreadable zone" (snapshot cues))
    (let [[_ _ show] (first @cases)]
      (is (near? 0.4 (reach show)) "the show frame is pinned (0.8 to 0.4) inside its zone"))
    (is (< 300 (count (nth (nth @cases 1) 2))) "the test pattern")
    (is (nil? (nth (nth @cases 5) 2)) "nothing playing")
    (output-errors/clear!)
    (with-redefs [state/get-raw-state (fn [] (throw (ex-info "test: an engine read app state" {})))]
      (doseq [[label snap expected] @cases]
        (testing label
          (is (= expected (frame-of snap))))))
    (is (some? (get (output-errors/snapshot) [:safety :p])) "the unreadable zone is reported")))

(deftest laser-the-tick-doesnt-know-draws-nothing-test
  (state/swap-state! #(-> %
                          (assoc-in [:projectors :p] (laser "10.0.0.5"))
                          (assoc-in [:projector-ui :calibrating-projector-id] :p)))
  (let [snap (snapshot [{:cell [0 0] :zone-frames {:all line-to-08}}])]
    (is (= 537 (count (#'me/projector-frame :p snap))) "a laser the tick knows shows its test pattern")
    (output-errors/clear!)
    (is (nil? (#'me/projector-frame :p (update-in snap [:engine-inputs :projectors] dissoc :p)))
        "one missing from the tick's [:projectors] draws nothing, the test pattern included")
    (is (nil? (#'me/projector-frame :p (dissoc snap :engine-inputs))) "and so does a snapshot without :engine-inputs")
    (is (empty? (output-errors/snapshot)) "with no error: not a full-range fallback")))

(deftest same-tick-same-frame-test
  (state/swap-state! #(-> %
                          (assoc-in [:projectors :p] (laser "10.0.0.5"))
                          (assoc-in [:chains :projector-effects :p :items] [(pin 1.0)])))
  (let [provider (#'me/create-projector-frame-provider :p)]
    (show-time/publish! (snapshot [{:cell [0 0] :zone-frames {:all line-to-08}}]))
    (let [first-frame (provider)]
      (state/swap-state! #(-> % (assoc-in [:chains :projector-effects :p :items] [(pin 0.5)])
                              (assoc-in [:projector-ui :identify :p] (+ (System/currentTimeMillis) 3000))))
      (let [resent (provider)]
        (is (near? 0.8 (reach first-frame)))
        (is (= first-frame resent) "a keepalive resend of the same tick, with the pin edited and Identify started since")))))
