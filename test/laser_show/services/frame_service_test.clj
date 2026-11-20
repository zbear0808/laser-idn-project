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
            [laser-show.routing.compose :as compose]
            [laser-show.routing.rig :as rig]
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
