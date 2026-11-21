(ns laser-show.services.frame-service-test
  "Tests for frame service, particularly zone filtering behavior.
   
   These tests verify:
   - Preview zone filtering works correctly for UI preview
   - What the preview shows for a tick's rendered cues (preview-data)
   - Per-item zone routing generates separate frames per zone"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.animation.cue-timing :as ct]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.fit-guard :as fit-guard]
            [laser-show.backend.output-errors :as oe]
            [laser-show.routing.compose :as compose]
            [laser-show.routing.rig :as rig]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.routing.zone-remap :as zone-remap]
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
  (testing "Item without a target of its own inherits its track's"
    (let [test-cue-chain {:tracks [{:id #uuid "10000000-0000-0000-0000-000000000000"
                                    :name "Track 1"
                                    :target :all}]
                          :items [{:type :preset
                                   :preset-id :circle
                                   :track-id #uuid "10000000-0000-0000-0000-000000000000"
                                   :enabled? true
                                   :effects []}]}
          result (frame-service/generate-frames-by-zone
                  test-cue-chain test-timing-ctx)]

      (is (= 1 (count result)) "Should have exactly 1 zone")
      (is (contains? result :all) "Item without a target of its own should route to :all")
      (is (nil? (:left result)) "Should not have :left zone")
      (is (nil? (:right result)) "Should not have :right zone"))))

(deftest test-generate-frames-by-zone-empty-zones-excluded
  (testing "Empty zones do not appear in result map"
    (let [test-cue-chain {:target :left
                          :tracks [{:id #uuid "10000000-0000-0000-0000-000000000000"
                                    :name "Track 1"
                                    :target :left}]
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
  {:target zone
   :tracks [{:id track-id :name "Track" :target zone}]
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
          ref-frames (:ref-frames (frame-service/preview-data [cue]))]
      (is (= #{:all} (set (keys ref-frames))))
      (is (= (get-in cue [:zone-frames :all]) (get ref-frames :all))))))

(deftest test-zone-frames-multiple-cues-same-zone
  (testing "Cues to the same zone are joined there with a blanking jump, as the engine joins them"
    (let [cues (rendered [5 0] (chain-to :all :circle track-1)
                         [6 0] (chain-to :all :square track-2))
          [a b] (map #(get-in % [:zone-frames :all]) cues)
          zone (get-in (frame-service/preview-data cues) [:ref-frames :all])]
      (is (= (frame-service/concatenate-frames [a b]) zone))
      (is (= (+ (count a) 2 (count b)) (count zone)) "two blanking points between the cues"))))

(deftest test-zone-frames-empty-when-no-active-cues
  (testing "No cues, or cues that rendered nothing: nil"
    (is (nil? (frame-service/preview-data [])))
    (is (nil? (frame-service/preview-data [{:cell [0 0] :zone-frames {}}])))))

(deftest test-zone-frames-zones-match-cue-destinations
  (testing "zone-frames keys match union of all cue destination zones"
    (let [{:keys [ref-frames cue-destinations]} (frame-service/preview-data (two-cues))]
      (is (= (set (keys ref-frames)) (into #{} cat (vals cue-destinations)))))))


(defn- render-with-tracks [tracks]
  (frame-service/generate-frames-by-zone
   {:editor-type :timeline
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
      (let [cue {:items [{:id :a :type :preset :preset-id :circle :enabled? true :effects [] :target :auto/left}
                         {:id :b :type :preset :preset-id :circle :enabled? true :effects [] :target :auto/right}]}
            [frames calls] (counting-chains #(frame-service/generate-frames-by-zone
                                              cue test-timing-ctx {:global-effects grid-scale :global-ctx grid-ctx}))]
        (is (= #{:auto/left :auto/right} (set (keys frames))))
        (is (= 2 calls))
        (is (not (identical? (:auto/left frames) (:auto/right frames))))))))

(deftest frame-keys-are-normal-targets-test
  (let [item (fn [id zone] {:id id :type :preset :preset-id :square :enabled? true :effects [] :target zone})
        render #(frame-service/generate-frames-by-zone {:items %} test-timing-ctx)]
    (is (= #{[:auto/left :auto/right]} (set (keys (render [(item :a [:auto/right :auto/left])]))))
        "a vector target: its refs sorted, so a key")
    (is (= #{:graphics} (set (keys (render [(item :a [:graphics :graphics])])))) "one ref left: that ref")
    (let [frames (render [(item :a [:auto/right :auto/left]) (item :b [:auto/left :auto/right])])
          one (render [(item :a [:auto/left :auto/right])])]
      (is (= [[:auto/left :auto/right]] (keys frames)) "the same refs in another order: one key")
      (is (= (frame-service/concatenate-frames [(val (first one)) (val (first one))]) (val (first frames)))
          "and their frames joined, as two items on one zone are"))
    (is (= #{[:auto/left :zone/a]} (set (keys (render [(item :a [:zone/a :auto/left])])))))
    (is (= #{:all} (set (keys (render [(item :a nil)])))) "a keyword, or the fallback, as before")
    (is (= #{:zone/b} (set (keys (frame-service/generate-frames-by-zone {:target [:zone/b] :items [(item :a nil)]} test-timing-ctx))))
        "the cue's target, in normal form too")))

(deftest union-target-is-one-key-test
  (let [item (fn [id t & {:as more}] (merge {:id id :type :preset :preset-id :square :enabled? true :effects [] :target t} more))
        render #(frame-service/generate-frames-by-zone % test-timing-ctx)
        alone (render {:items [(item :a [:zone/b :zone/a])]})]
    (is (= [[:zone/a :zone/b]] (keys alone)) "zones A and B: one key, the union")
    (testing "two items on the same union share it, joined"
      (let [frames (render {:items [(item :a [:zone/b :zone/a]) (item :b [:zone/a :zone/b])]})]
        (is (= [[:zone/a :zone/b]] (keys frames)))
        (is (= (frame-service/concatenate-frames [(val (first alone)) (val (first alone))]) (val (first frames))))))
    (testing "a group's union reaches its children, and one of them can still go elsewhere"
      (let [frames (render {:items [{:id :g :type :group :enabled? true :target [:zone/a :graphics]
                                     :items [(item :c nil) (item :d :zone/c)]}]})]
        (is (= #{[:graphics :zone/a] :zone/c} (set (keys frames))))))
    (testing "the cue's union is every inheriting item's key"
      (is (= [[:zone/a :zone/c]] (keys (render {:target [:zone/c :zone/a] :items [(item :a nil) (item :b nil)]})))))))

(deftest column-and-show-defaults-reach-items-test
  (let [item (fn [id t] {:id id :type :preset :preset-id :square :enabled? true :effects [] :target t})
        cue {:items [(item :a nil) (item :b :zone/c)
                     {:id :g :type :group :enabled? true :items [(item :c nil)]}]}
        keys-of (fn [cue ctx] (set (keys (frame-service/generate-frames-by-zone cue test-timing-ctx ctx))))]
    (is (= #{:zone/a :zone/c} (keys-of cue {:column-target :zone/a :show-target :zone/b}))
        "what inherits takes its column's default, through a group too; an item's own stays")
    (is (= #{:zone/b :zone/c} (keys-of cue {:show-target :zone/b})) "no column default: the show's")
    (is (= #{:all :zone/c} (keys-of cue {})) "neither: All")
    (is (= #{:graphics :zone/c} (keys-of (assoc cue :target :graphics) {:column-target :zone/a :show-target :zone/b}))
        "the cue's own beats both")
    (testing "the tick's render context carries them, read once from app state"
      (let [ctx (frame-service/render-context (-> (domains/build-initial-state)
                                                  (assoc-in [:grid :column-targets] {2 :zone/a})
                                                  (assoc-in [:config :default-target] :zone/b))
                                              {} {:effective-beats 0.0})]
        (is (= {2 :zone/a} (:column-targets ctx)))
        (is (= :zone/b (:show-target ctx)))))))

(deftest frame-keys-ignore-the-map-test
  ;; zones plan Z4, invariant 2: frame keys name zones, never lanes, so a
  ;; map switch re-routes the same frames (the routing resolves the zones)
  (let [item (fn [id t] {:id id :type :preset :preset-id :square :enabled? true :effects [] :target t})
        cue {:items [(item :a :zone/a) (item :b [:zone/b :graphics]) (item :c nil)]}
        render (fn [active]
                 (let [ctx (frame-service/render-context (-> (domains/build-initial-state)
                                                             (assoc-in [:grid :column-targets] {0 :zone/c})
                                                             (assoc-in [:zone-map :active] active))
                                                         {} {:effective-beats 0.0})]
                   (frame-service/generate-frames-by-zone cue test-timing-ctx
                                                          (assoc ctx :column-target (get-in ctx [:column-targets 0])))))]
    (is (= #{:zone/a [:graphics :zone/b] :zone/c} (set (keys (render :map/left-right)))) "the zones, not their lanes")
    (is (= (render :map/left-right) (render :map/odd-even) (render :map/unison)) "the same keys and frames under every map")))

(deftest item-points-only-when-asked-test
  (let [cue {:items [{:id :ring :type :preset :preset-id :circle :enabled? true :effects [] :params {:num-points 99}}
                     {:id :box :type :preset :preset-id :square :enabled? true :effects [] :target :auto/left}
                     {:id :grp :type :group :enabled? true :target :auto/right
                      :items [{:id :inner :type :preset :preset-id :circle :enabled? true :effects []}]}
                     {:id :off :type :preset :preset-id :circle :enabled? false :effects []}]}
        asked (frame-service/generate-frames-by-zone cue test-timing-ctx {:item-points? true})
        plain (frame-service/generate-frames-by-zone cue test-timing-ctx {})]
    (is (= asked plain) "the same frames")
    (is (nil? (meta plain)) "not asked: no counts (only an open editor needs them)")
    (is (= [[:ring {:all 100}] [:box {:auto/left (count (:auto/left plain))}] [:grp {:auto/right (count (:auto/right plain))}]]
           (:item-points (meta asked)))
        "each top-level item's points by key, in order: the circle's 99 and its dark first point; none for one that is off")))


;; The preview of a tick (zones plan Z3): what is sent to each group, and
;; what each laser draws

(deftest preview-ref-frames-follow-ref-membership-test
  (let [a (square 0.0) b (square 0.4) c (square -0.4)
        cues [{:cell [0 0] :zone-frames {[:auto/left :graphics] b :auto/left a}}
              {:cell [1 0] :zone-frames {:auto/left c}}]
        {:keys [ref-frames]} (frame-service/preview-data cues nil)]
    (is (= (frame-service/concatenate-frames [a b c]) (:auto/left ref-frames))
        "every key that names Left, in key order (the single key first), then the next cue's")
    (is (= b (:graphics ref-frames)) "a key that names two refs counts for each")
    (is (= #{:auto/left :graphics} (set (keys ref-frames))))
    (testing "with keyword keys, today's zone frames"
      (let [cues (two-cues)]
        (is (= {:all (get-in cues [0 :zone-frames :all]) :left (get-in cues [1 :zone-frames :left])}
               (:ref-frames (frame-service/preview-data cues))))))))

(deftest preview-zones-count-for-their-lane-test
  (testing "zones plan Z4: a group's cell shows what its zones send it, through the map in use"
    (let [state {:projectors {:p1 {:name "Laser A" :host "10.0.0.1" :zone-groups []}
                              :p2 {:name "Laser B" :host "10.0.0.2" :zone-groups []}}}
          a (square 0.0) b (square 0.4) c (square -0.4)
          cues [{:cell [0 0] :zone-frames {:zone/a a :zone/b b :auto/left c [:zone/a :zone/c] b}}]
          ref-frames #(:ref-frames (frame-service/preview-data cues (rig/routing (rig/routing-inputs state %))))
          lr (ref-frames :map/left-right)]
      (is (= (frame-service/concatenate-frames [a c b]) (:auto/left lr))
          "Left: zone A's, its own, then the union of A and C's (once, both are Left), in key order")
      (is (= b (:auto/right lr)) "Right: zone B's")
      (is (= (frame-service/concatenate-frames [a b]) (:zone/a lr)) "a zone's own cell: what is drawn into it, a union naming it too")
      (is (= (frame-service/concatenate-frames [a b b]) (:all (ref-frames :map/unison))) "Unison: every zone is All")
      (is (= c (:auto/left (ref-frames :map/odd-even))) "Odd / Even: only Left's own key, its zones go to Odd and Even")
      (testing "without routing, a zone counts for itself only"
        (is (= c (:auto/left (:ref-frames (frame-service/preview-data cues)))))))))

(deftest preview-projector-frames-are-what-the-engine-composes-test
  (let [state {:projectors {:p1 {:name "Laser A" :host "10.0.0.1" :zone-groups []}
                            :p2 {:name "Laser B" :host "10.0.0.2" :zone-groups [] :enabled? false}}}
        routing (rig/routing (rig/routing-inputs state nil))
        a (square 0.0)
        heavy (vec (for [i (range 3000)] [(* 0.5 (Math/cos (* i 0.002))) (* 0.5 (Math/sin (* i 0.002))) 1.0 1.0 1.0]))
        cues [{:cell [0 0] :zone-frames {:auto/left a :auto/right heavy}}]
        {:keys [projector-frames]} (frame-service/preview-data cues routing)
        frame (get projector-frames :p1)]
    (is (= #{:p1} (set (keys projector-frames))) "each laser the routing has: not one that is off")
    (is (not (realized? frame)) "composed only when a cell shows it")
    (is (= (fit-guard/guard (:frame (compose/projector-frame cues routing :p1)) 25000 fit-guard/defaults) @frame)
        "the engine's composition and fit guard: its halves, fitted, then thinned")
    (is (= 1804 (count (:frame @frame))) "4 + 2 + 3,000 points, cut to 60%")
    (testing "a laser nothing reaches: nil"
      (is (nil? @(get (:projector-frames (frame-service/preview-data [{:cell [0 0] :zone-frames {:graphics a}}] routing))
                     :p1))))
    (testing "without routing: none"
      (is (= {} (:projector-frames (frame-service/preview-data cues)))))))


;; Zones plan Z5: the zone stage in the render (07 §4.4, §8.7, §8.9)

(defn- pattern-fx
  "A zone pattern effect instance: its preset for style, with more params."
  [id style where & {:as more}]
  (-> (zone-patterns/preset-instance style where)
      (assoc :id id)
      (update :params merge more)))

(defn- circle-cue
  "A cue drawing one circle into its own target (nil: All), with patterns."
  [& patterns]
  {:items [{:id :c :type :preset :preset-id :circle :enabled? true :effects []}]
   :zone-patterns (vec patterns)})

(defn- at-beats
  "A cue's timing context at beats, 120 BPM."
  [beats]
  {:accumulated-beats beats :effective-beats beats :phase-offset 0.0 :accumulated-ms (* 500.0 beats)
   :time-ms (* 500.0 beats) :bpm 120.0})

(def ^:private every-ref-ctx
  "The stage's rig context: every ref known, three lasers."
  {:known? (constantly true) :lasers [:p1 :p2 :p3]})

(defn- render-with
  "The cue's frames at cue beats and global beats, the effects grid's zone
   patterns (cells [{:cell :effect}]) and point chain."
  [cue cue-beats global-beats & {:keys [cells global-effects zone-ctx pulses]}]
  (frame-service/generate-frames-by-zone
   cue (cond-> (at-beats cue-beats) pulses (assoc :pulses pulses))
   {:zone-effects cells
    :global-effects global-effects
    :global-ctx (cond-> (at-beats global-beats) pulses (assoc :pulses pulses))
    :zone-ctx (or zone-ctx every-ref-ctx)}))

(defn- the-circle []
  (:all (frame-service/generate-frames-by-zone (circle-cue) (at-beats 0.0) {})))

(deftest cue-pattern-runs-before-grid-patterns-test
  (testing "07 §8.7: the cue's Alternate takes the All picture to A or B, then the cell's Swap trades A and B"
    (let [P (the-circle)
          cue (circle-cue (pattern-fx :cue-p :alternate :cue))
          cells [{:cell [0 1] :effect (pattern-fx :cell-p :swap :cell)}]]
      (is (= {:zone/a P} (render-with cue 0.0 0.0)) "the cue's alone, step 0")
      (is (= {:zone/b P} (render-with cue 0.0 1.0 :cells cells)) "cue step 0, cell step 1: A, then traded to B")
      (is (= {:zone/a P} (render-with cue 1.0 1.0 :cells cells)) "cue step 1, cell step 1: B, then traded to A")
      (is (= {:zone/a P} (render-with cue 0.0 2.0 :cells cells)) "the cell's even step changes nothing"))))

(deftest grid-patterns-run-in-reading-order-test
  (testing "probe v24's twelve cells, put in out of order: the stage reads them row by row, left to right"
    (let [order [13 10 12 2 1 11 20 21 23 22 3 0]
          chains (reduce (fn [m n] (assoc m [(mod n 10) (quot n 10)]
                                          {:active? true :items [(pattern-fx (keyword (str "p" n)) :shift :cell)]}))
                         {} order)
          cells (frame-service/get-active-zone-effects {:chains {:effect-chains chains}})]
      (is (= [[0 0] [1 0] [2 0] [3 0] [0 1] [1 1] [2 1] [3 1] [0 2] [1 2] [2 2] [3 2]] (mapv :cell cells)))
      (is (= :p0 (get-in cells [0 :effect :id])))))
  (testing "a Swap in row 0 runs before a Shift in row 1, whichever was put in first"
    (let [X (the-circle)
          swap {:active? true :items [(pattern-fx :s :swap :cell)]}
          shift {:active? true :items [(pattern-fx :t :shift :cell :zones [:zone/a :zone/b :zone/c])]}
          state {:chains {:effect-chains (array-map [0 1] shift [1 0] swap)}}
          cells (frame-service/get-active-zone-effects state)
          cue {:target :zone/a :items [{:id :c :type :preset :preset-id :circle :enabled? true :effects []}]}]
      (is (= [[1 0] [0 1]] (mapv :cell cells)))
      (is (= {:zone/c X} (render-with cue 0.0 1.0 :cells cells)) "A swapped to B, then shifted on to C")))
  (testing "only enabled patterns of cells that are on, in chain order, through groups; no Map cycle, no point effect"
    (let [state {:chains {:effect-chains {[0 0] {:active? true :items [{:type :group :enabled? true
                                                                        :items [(pattern-fx :a :chase :cell)
                                                                                (assoc (pattern-fx :b :chase :cell) :enabled? false)]}
                                                                       (zone-patterns/cycle-instance)
                                                                       {:id :sc :effect-id :scale :enabled? true :params {}}
                                                                       (pattern-fx :c :swap :cell)]}
                                          [1 0] {:active? false :items [(pattern-fx :d :swap :cell)]}}}}]
      (is (= [:a :c] (mapv (comp :id :effect) (frame-service/get-active-zone-effects state)))))))

(deftest stage-runs-before-point-effects-test
  (let [P (the-circle)
        half {:effects [{:id :half :effect-id :scale :enabled? true :params {:x-scale 0.5 :y-scale 1.0}}]}
        cue (circle-cue (pattern-fx :p :chase :cue :zones [:zone/a :zone/b]))
        out (render-with cue 1.0 1.0 :global-effects half)]
    (is (= [:zone/b] (keys out)) "moved first")
    (is (= (effects/apply-effect-chain P half (at-beats 1.0)) (:zone/b out)) "then the point effect on the moved picture"))
  (testing "the point chain leaves the zone-stage effects out"
    (let [state {:chains {:effect-chains {[0 0] {:active? true :items [(pattern-fx :p :chase :cell) (zone-patterns/cycle-instance)
                                                                       {:id :sc :effect-id :scale :enabled? true :params {}}]}}}}]
      (is (= [:sc] (mapv :id (:effects (frame-service/get-active-global-effects state)))))
      (is (nil? (frame-service/get-active-global-effects {:chains {:effect-chains {[0 0] {:active? true :items [(pattern-fx :p :chase :cell)]}}}}))
          "a cell of patterns only: no point chain at all")
      (is (= [:p] (mapv (comp :id :effect) (:zone-effects (frame-service/render-context state {} {:effective-beats 0.0}))))
          "the render context carries them apart"))))

(deftest copy-gets-point-effects-once-test
  (let [half {:effects [{:id :half :effect-id :scale :enabled? true :params {:x-scale 0.5 :y-scale 1.0}}]}
        cue (circle-cue (pattern-fx :p :alternate :cue :zones [:zone/a :zone/b :zone/c :zone/d] :source :picture))
        [out calls] (counting-chains #(render-with cue 0.0 0.0 :global-effects half))]
    (is (= [[:zone/a :zone/c]] (keys out)) "A and C lit: one frame under both (S-1)")
    (is (= 1 calls) "one picture, one run of the point chain")))

(deftest throwing-pattern-leaves-the-frames-test
  (let [P (the-circle)
        broken (pattern-fx :broken :chase :cue :zones :zone/a)
        good (pattern-fx :good :chase :cue :zones [:zone/a :zone/b])]
    (oe/clear!)
    (is (= {:zone/b P} (render-with (circle-cue broken good) 1.0 1.0)) "the broken one is skipped, the next one runs")
    (is (some? (get (oe/snapshot) [:zone-patterns :cue :broken])) "reported under its id")
    (is (= {:all P} (render-with (circle-cue broken) 1.0 1.0)) "alone: the frames as they were")
    (testing "a cell's too"
      (is (= {:all P} (render-with (circle-cue) 1.0 1.0 :cells [{:cell [2 1] :effect (assoc broken :id :cell-broken)}])))
      (is (some? (get (oe/snapshot) [:zone-patterns :effects :cell-broken]))))
    (oe/clear!)))

(deftest timeline-cue-pattern-loops-with-the-cue-test
  (let [cue (assoc (timeline-cue {:items [(clip-at 0.0)]})
                   :loop {:enabled? true :start 0.0 :duration 4.0}
                   :zone-patterns [(pattern-fx :p :chase :cue :zones [:zone/a :zone/b :zone/c :zone/d])])
        zone (fn [beats] (first (keys (render-with cue beats beats))))]
    (is (= [:zone/a :zone/b :zone/c :zone/d] (mapv zone [0.0 1.0 2.0 3.0])) "a zone a beat")
    (is (= [:zone/a :zone/b :zone/c] (mapv zone [4.0 5.0 6.0])) "the loop starts it again with the cue")))

(deftest cue-and-global-clock-test
  (let [P (the-circle)
        on (fn [clock] (render-with (circle-cue (pattern-fx :p :alternate :cue :clock clock)) 1.0 6.0))]
    (is (= {:zone/b P} (on :cue)) "the cue's start: beat 1 is step 1 (Z5-Q6 A)")
    (is (= {:zone/a P} (on :global)) "the show's beats: beat 6 is step 6")))

(deftest pattern-steps-on-a-press-test
  (let [P (the-circle)
        cue (circle-cue (pattern-fx :p :chase :cue :zones [:zone/a :zone/b :zone/c] :step-on :hit))
        press (fn [n] {:hit {:count n :beats 0.25 :ms 125.0 :held? false :since-release 0.25 :recent [0.25]}})]
    (is (= {:zone/a P} (render-with cue 7.3 7.3)) "no press: step 0, whatever the beat")
    (is (= {:zone/b P} (render-with cue 7.3 7.3 :pulses (press 1))) "one HIT: one step")
    (is (= {:zone/a P} (render-with cue 7.3 7.3 :pulses (press 3))) "three: round to A again")
    (testing "a cell's pattern sees the same presses"
      (is (= {:zone/c P} (render-with (circle-cue) 7.3 7.3 :pulses (press 2)
                                      :cells [{:cell [0 0] :effect (pattern-fx :q :chase :cell :zones [:zone/a :zone/b :zone/c] :step-on :hit)}]))))
    (testing "the steps the patterns are at go with the frames"
      (is (= {:p 1} (:zone-steps (meta (render-with cue 7.3 7.3 :pulses (press 1)))))))))

(deftest no-pattern-renders-as-before-test
  (let [cue (circle-cue)
        plain (frame-service/generate-frames-by-zone (dissoc cue :zone-patterns) (at-beats 1.0) {})
        stage-calls (atom 0)
        run-pattern zone-remap/run-pattern]
    (with-redefs [zone-remap/run-pattern (fn [& args] (swap! stage-calls inc) (apply run-pattern args))]
      (is (= plain (render-with cue 1.0 1.0)) "no pattern on the cue, none in a cell")
      (is (= plain (render-with (circle-cue (assoc (pattern-fx :p :alternate :cue) :enabled? false)) 1.0 1.0))
          "a disabled pattern is none")
      (is (zero? @stage-calls) "the stage doesn't even run")
      (is (nil? (meta (render-with cue 1.0 1.0))) "and nothing new rides on the frames"))
    (is (= plain (render-with cue 1.0 1.0 :zone-ctx nil)) "no zone context: the same")))

(deftest item-points-follow-the-stage-test
  (let [cue (assoc (circle-cue (pattern-fx :p :chase :cue :zones [:zone/a :zone/b])) :items
                   [{:id :ring :type :preset :preset-id :circle :enabled? true :effects []}])
        out (frame-service/generate-frames-by-zone cue (at-beats 1.0) {:item-points? true :zone-ctx every-ref-ctx})]
    (is (= [[:ring {:zone/b (count (:zone/b out))}]] (:item-points (meta out)))
        "an editor's \"Most points\" finds the item where the pattern put it")))
