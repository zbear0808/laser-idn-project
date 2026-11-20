(ns laser-show.services.frame-service-test
  "Tests for frame service, particularly zone filtering behavior.
   
   These tests verify:
   - Preview zone filtering works correctly for UI preview
   - What the preview shows for a tick's rendered cues (preview-data)
   - Per-item zone routing generates separate frames per zone"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.animation.cue-timing :as ct]
            [laser-show.animation.effects :as effects]
            [laser-show.routing.zone-effects :as ze]
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
                  test-cue-chain test-timing-ctx)]

      (is (= 1 (count result)) "Should have exactly 1 zone")
      (is (contains? result :all) "Item without zone effects should route to :all")
      (is (nil? (:left result)) "Should not have :left zone")
      (is (nil? (:right result)) "Should not have :right zone"))))

(deftest test-generate-frames-by-zone-empty-zones-excluded
  (testing "Empty zones do not appear in result map"
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
                  test-cue-chain test-timing-ctx)]

      (is (= #{:left} (set (keys result))) "Only :left should have a frame")
      (is (nil? (:all result)) ":all should not have a frame")
      (is (nil? (:right result)) ":right should not have a frame")
      (is (nil? (:center result)) ":center should not have a frame"))))

;; The preview from a tick's rendered cues (preview-data)
;;
;; BUG-2026-02-06: cue-destinations was missing cues with non-default zone
;; routing because the single preview-zone filter excluded them entirely.


(def ^:private track-1 #uuid "10000000-0000-0000-0000-000000000000")
(def ^:private track-2 #uuid "20000000-0000-0000-0000-000000000000")

(defn- chain-to [zone preset-id track-id]
  {:destination-zone {:zone-group-id zone}
   :tracks [{:id track-id :name "Track" :zone-group-id zone}]
   :items [{:type :preset :preset-id preset-id :track-id track-id :enabled? true :effects []}]})

(defn- rendered
  "[{:cell :zone-frames}] for cells and their chains, rendered at beat 2 as
   the show clock renders a tick."
  [& cells-and-chains]
  (mapv (fn [[cell chain]]
          {:cell cell
           :zone-frames (frame-service/generate-frames-by-zone chain test-timing-ctx)})
        (partition 2 cells-and-chains)))

(defn- two-cues []
  (rendered [5 0] (chain-to :all :circle track-1)
            [6 0] (chain-to :left :square track-2)))


(deftest test-cue-destinations-includes-all-active-cues
  (testing "cue-destinations includes ALL active cues regardless of zone routing"
    (let [cue-dests (:cue-destinations (frame-service/preview-data (two-cues)))]
      (is (= {[5 0] #{:all} [6 0] #{:left}} cue-dests)
          "the cue routed to :left is there too (the bug fix), each with its zones"))))

(deftest test-cue-destinations-not-affected-by-preview-filter
  (testing "cue-destinations tracking is independent of preview zone filter setting"
    (doseq [filter-zone [nil :all :left :right :center]]
      (state/assoc-in-state! [:config :preview :zone-group-filter] filter-zone)
      (is (= #{[5 0] [6 0]} (set (keys (:cue-destinations (frame-service/preview-data (two-cues))))))
          (str "with filter=" filter-zone)))))

(deftest test-frame-data-contains-combined-points
  (testing ":points joins every cue's zones, blanked between them"
    (let [cues (two-cues)
          {:keys [points]} (frame-service/preview-data cues)
          [a b] (map #(frame-service/concatenate-frames (vec (vals (:zone-frames %)))) cues)]
      (is (vector? points))
      (is (= (frame-service/concatenate-frames [a b]) points)))))


;; Zone-frames: zone-id -> points across all cues, so a preview cell for a
;; zone shows what an engine on that zone gets


(deftest test-zone-frames-single-active-cue
  (testing "Single active cue produces zone-frames entry for its destination zone"
    (let [[cue] (rendered [5 0] (chain-to :all :circle track-1))
          zone-frames (:zone-frames (frame-service/preview-data [cue]))]
      (is (= #{:all} (set (keys zone-frames))))
      (is (= (get-in cue [:zone-frames :all]) (get zone-frames :all))))))

(deftest test-zone-frames-multiple-cues-same-zone
  (testing "Cues to the same zone are joined there with a blanking jump, as the engine joins them"
    (let [cues (rendered [5 0] (chain-to :all :circle track-1)
                         [6 0] (chain-to :all :square track-2))
          [a b] (map #(get-in % [:zone-frames :all]) cues)
          zone (get-in (frame-service/preview-data cues) [:zone-frames :all])]
      (is (= (frame-service/concatenate-frames [a b]) zone))
      (is (= (+ (count a) 2 (count b)) (count zone)) "two blanking points between the cues"))))

(deftest test-zone-frames-empty-when-no-active-cues
  (testing "No cues, or cues that rendered nothing: nil"
    (is (nil? (frame-service/preview-data [])))
    (is (nil? (frame-service/preview-data [{:cell [0 0] :zone-frames {}}])))))

(deftest test-zone-frames-zones-match-cue-destinations
  (testing "zone-frames keys match union of all cue destination zones"
    (let [{:keys [zone-frames cue-destinations]} (frame-service/preview-data (two-cues))]
      (is (= (set (keys zone-frames)) (into #{} cat (vals cue-destinations)))))))


(defn- render-with-tracks [tracks]
  (frame-service/generate-frames-by-zone
   {:destination-zone {:zone-group-id :all}
    :editor-type :timeline
    :tracks tracks
    :items [{:id :a :type :preset :preset-id :circle :track-id :t1 :enabled? true :effects []
             :timeline/start 0.0 :timeline/duration 4.0}
            {:id :b :type :preset :preset-id :square :track-id :t2 :enabled? true :effects []
             :timeline/start 0.0 :timeline/duration 4.0}]}
   test-timing-ctx))

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

(deftest clip-local-loop-modulator-starts-at-clip-test
  (let [clip (fn [start]
               {:id :a :type :preset :preset-id :circle :track-id :t :enabled? true
                :timeline/start start :timeline/duration 8.0
                :effects [{:id :fx :effect-id :scale :enabled? true
                           :params {:x-scale {:type :sine :min 0.5 :max 1.5 :period 3.0}}}]})
        render (fn [start beats]
                 (frame-service/generate-frames-by-zone
                  (timeline-cue {:items [(clip start)]})
                  {:accumulated-beats beats :effective-beats beats :phase-offset 0.0
                             :accumulated-ms (* beats 500.0) :time-ms (* beats 500.0) :bpm 120.0 :trigger-time 0}))]
    (is (= (render 0.0 1.25) (render 8.0 9.25))
        "the same clip moved to beat 8 looks the same 1.25 beats into it")
    (is (not= (render 0.0 1.25) (render 0.0 2.0)) "the modulator does move")))

(deftest timeline-clip-effects-see-pulses-test
  (testing "a clip's own clock keeps the presses (probe v13: playback/clip-timing-ctx keeps the key)"
    (let [punch {:type :envelope :source :hit :min 1.0 :max 1.5 :attack 0.0 :release 1.0 :curve :linear :hold? false}
          cue (timeline-cue {:items [{:id :a :type :preset :preset-id :circle :track-id :t :enabled? true
                                      :timeline/start 8.0 :timeline/duration 8.0
                                      :effects [{:id :fx :effect-id :scale :enabled? true
                                                 :params {:x-scale punch :y-scale 1.0}}]}]})
          width (fn [pulses]
                  (let [frame (:all (frame-service/generate-frames-by-zone
                                     cue (cond-> {:accumulated-beats 9.0 :effective-beats 9.0 :phase-offset 0.0
                                                  :accumulated-ms 4500.0 :time-ms 4500.0 :bpm 120.0}
                                           pulses (assoc :pulses pulses))))
                        xs (map first (remove (fn [[_ _ r g b]] (= 0.0 r g b)) frame))]
                    (- (apply max xs) (apply min xs))))
          hit (fn [beats] {:hit {:count 1 :beats beats :ms (* 500.0 beats) :held? false :since-release beats :recent [beats]}})
          rest-width (width nil)]
      (is (< (Math/abs (- (* 1.5 rest-width) (width (hit 0.0)))) 1e-9) "a press now: the peak")
      (is (< (Math/abs (- (* 1.25 rest-width) (width (hit 0.5)))) 1e-9) "half a beat on: half way back")
      (is (< (Math/abs (- rest-width (width (hit 2.0)))) 1e-9) "at rest again"))))

;; Track and folder effects

(defn- render-at [cue beats]
  (frame-service/generate-frames-by-zone
   cue {:accumulated-beats beats :effective-beats beats :phase-offset 0.0
                  :accumulated-ms (* beats 500.0) :time-ms (* beats 500.0) :bpm 120.0 :trigger-time 0}))

(def ^:private scale-fx {:id :fx :effect-id :scale :enabled? true :params {:x-scale 0.5}})

(defn- clip-at [start & [effects]]
  {:id :a :type :preset :preset-id :circle :track-id :t :enabled? true
   :timeline/start start :timeline/duration 16.0 :effects (or effects [])})

(deftest track-effect-applies-to-its-clips-test
  (let [on-clip (timeline-cue {:items [(clip-at 0.0 [scale-fx])]})
        on-track (timeline-cue {:tracks [{:id :t :type :track :effects [scale-fx]}]
                                :items [(clip-at 0.0)]})
        in-folder (timeline-cue {:tracks [{:id :f :type :group :effects [scale-fx]
                                           :items [{:id :t :type :track}]}]
                                 :items [(clip-at 0.0)]})
        plain (timeline-cue {:items [(clip-at 0.0)]})]
    (is (not= (render-at plain 1.0) (render-at on-clip 1.0)) "the effect does something")
    (is (= (render-at on-clip 1.0) (render-at on-track 1.0)))
    (is (= (render-at on-clip 1.0) (render-at in-folder 1.0)) "folder effects apply to their tracks")
    (is (= (render-at plain 1.0)
           (render-at (assoc-in on-track [:tracks 0 :effects 0 :enabled?] false) 1.0))
        "a disabled track effect does nothing")))

(deftest track-effect-uses-timeline-time-test
  (let [sine {:id :fx :effect-id :scale :enabled? true
              :params {:x-scale {:type :sine :min 0.5 :max 1.5 :period 3.0}}}
        on-track (timeline-cue {:tracks [{:id :t :type :track :effects [sine]}]
                                :items [(clip-at 8.0)]})
        clip-from-0 (timeline-cue {:items [(clip-at 0.0 [sine])]})]
    (is (= (render-at clip-from-0 9.25) (render-at on-track 9.25))
        "a track modulator is at timeline beat 9.25, not 1.25 into the clip")))

(deftest once-mode-on-a-track-effect-follows-the-playhead-test
  (let [saw {:id :fx :effect-id :scale :enabled? true
             :params {:x-scale {:type :sawtooth :min 0.0 :max 1.0 :period 4.0 :loop-mode :once}}}
        cue (timeline-cue {:tracks [{:id :t :type :track :effects [saw]}]
                           :items [(clip-at 0.0)]})
        t0 (System/currentTimeMillis)
        ;; Started and advanced the way the app does it, so the context is the app's
        width-at (fn [ms]
                   (let [t (reduce (fn [t now] (ct/update-cue-timing t now 120.0 1.0 {:enabled? false} {:length 64.0}))
                                   (ct/start-timing t0 0.0 {:timeline? true :start-beat 0.0})
                                   (concat (range t0 (+ t0 ms) 33) [(+ t0 ms)]))
                         frame (get (frame-service/generate-frames-by-zone
                                     cue (ct/get-cue-timing-context t 120.0))
                                    :all)
                         xs (map first (remove #(every? zero? (subvec % 2 5)) frame))]
                     (- (apply max xs) (apply min xs))))]
    (is (< (Math/abs (- 1.0 (width-at 0))) 0.01) "at the start the sawtooth is at 1")
    (is (< (Math/abs (- 0.5 (width-at 1000))) 0.01) "2 beats into 4: half")
    (is (< (width-at 3000) 0.01) "done: held at 0")))


;; Zones plan Z3: the effects grid runs once per distinct picture (spec 01
;; §6.1), and frame keys are targets in normal form (§1)

(def ^:private grid-scale
  "An effects-grid chain: half as wide."
  {:effects [{:id :grid-scale :effect-id :scale :enabled? true :params {:x-scale 0.5 :y-scale 1.0}}]})

(def ^:private grid-ctx
  {:accumulated-beats 2.0 :accumulated-ms 1000.0 :phase-offset 0.0 :effective-beats 2.0 :time-ms 1000 :bpm 120.0})

(defn- square [x] [[x 0.0 1.0 1.0 1.0] [(+ x 0.2) 0.0 1.0 1.0 1.0] [(+ x 0.2) 0.2 1.0 1.0 1.0] [x 0.2 1.0 1.0 1.0]])

(defn- counting-chains
  "(f) with effects/apply-effect-chain counted; [result calls]."
  [f]
  (let [calls (atom 0)
        apply-chain effects/apply-effect-chain]
    (with-redefs [effects/apply-effect-chain (fn [& args] (swap! calls inc) (apply apply-chain args))]
      [(f) @calls])))

(deftest global-effects-run-once-per-distinct-frame-test
  (let [shared (square 0.0)
        other (square 0.4)
        [out calls] (counting-chains #(frame-service/apply-global-effects-once
                                       {:auto/left shared :auto/right shared :all other} grid-scale grid-ctx))]
    (is (= 2 calls) "two pictures, two runs of the chain, though three keys")
    (is (identical? (:auto/left out) (:auto/right out)) "both keys hold the one result object")
    (is (= (effects/apply-effect-chain shared grid-scale grid-ctx) (:auto/left out)) "what the chain makes of it")
    (is (not (identical? (:auto/left out) (:all out))))
    (testing "no chain: every frame as it was, the same objects"
      (let [out (frame-service/apply-global-effects-once {:auto/left shared :auto/right shared} nil grid-ctx)]
        (is (identical? shared (:auto/left out)))
        (is (identical? shared (:auto/right out)))))
    (testing "an empty frame is left out, as before"
      (is (= #{:all} (set (keys (frame-service/apply-global-effects-once {:all other :auto/left []} grid-scale grid-ctx))))))))

(deftest distinct-frames-are-not-shared-test
  (let [a (square 0.0)
        b (into [] (square 0.0))
        [out calls] (counting-chains #(frame-service/apply-global-effects-once {:auto/left a :auto/right b} grid-scale grid-ctx))]
    (is (= a b))
    (is (= 2 calls) "equal frames that aren't one object are two pictures: no value hashing")
    (is (not (identical? (:auto/left out) (:auto/right out))))
    (testing "through the render: two items, two pictures, each through the chain once"
      (let [cue {:destination-zone {:zone-group-id :all}
                 :items [{:id :a :type :preset :preset-id :circle :enabled? true :effects [] :zone-group-id :auto/left}
                         {:id :b :type :preset :preset-id :circle :enabled? true :effects [] :zone-group-id :auto/right}]}
            [frames calls] (counting-chains #(frame-service/generate-frames-by-zone
                                              cue test-timing-ctx {:global-effects grid-scale :global-ctx grid-ctx}))]
        (is (= #{:auto/left :auto/right} (set (keys frames))))
        (is (= 2 calls))
        (is (not (identical? (:auto/left frames) (:auto/right frames))))))))

(deftest frame-keys-are-normal-targets-test
  (let [item (fn [id zone] {:id id :type :preset :preset-id :square :enabled? true :effects [] :zone-group-id zone})
        render #(frame-service/generate-frames-by-zone {:destination-zone {:zone-group-id :all} :items %} test-timing-ctx)]
    (is (= #{[:auto/left :auto/right]} (set (keys (render [(item :a [:auto/right :auto/left])]))))
        "a vector target: its refs sorted, so a key")
    (is (= #{:graphics} (set (keys (render [(item :a [:graphics :graphics])])))) "one ref left: that ref")
    (let [frames (render [(item :a [:auto/right :auto/left]) (item :b [:auto/left :auto/right])])
          one (render [(item :a [:auto/left :auto/right])])]
      (is (= [[:auto/left :auto/right]] (keys frames)) "the same refs in another order: one key")
      (is (= (frame-service/concatenate-frames [(val (first one)) (val (first one))]) (val (first frames)))
          "and their frames joined, as two items on one zone are"))
    (is (= [:auto/left :zone/a] (ze/resolve-item-zone-destination {:zone-group-id [:zone/a :auto/left]} {} {})))
    (is (= :all (ze/resolve-item-zone-destination {} {} {})) "a keyword, or the fallback, as before")))
