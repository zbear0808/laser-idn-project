(ns laser-show.services.frame-service-test
  "Tests for frame service, particularly zone filtering behavior.
   
   These tests verify:
   - Preview zone filtering works correctly for UI preview
   - What the preview shows for a tick's rendered cues (preview-data)
   - Per-item zone routing generates separate frames per zone"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.animation.cue-timing :as ct]
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
