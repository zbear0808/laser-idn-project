(ns laser-show.subs-test
  "Subscription tests run against a real cljfx context (no FX toolkit needed)."
  (:require
   [cljfx.api :as fx]
   [clojure.test :refer [deftest is testing]]
   [laser-show.state.core :as state]
   [laser-show.state.domains :as domains]
   [laser-show.subs :as subs]))

(def cue-chain {:name "Intro"
                :items [{:type :group
                         :items [{:type :preset :preset-id :circle}
                                 {:type :preset :preset-id :star}]}
                        {:type :preset :preset-id :wave}]})

(def base-state
  (-> (domains/build-initial-state)
      (assoc-in [:chains :cue-chains] {[0 0] cue-chain
                                       [1 0] {:items [{:type :preset :preset-id :line}]}})
      (assoc-in [:chains :effect-chains] {[0 0] {:name "FX"
                                                 :active? true
                                                 :items [{:type :effect :effect-id :scale}
                                                         {:type :effect :effect-id :rotate}]}})
      (assoc-in [:playback :active-cues] {[0 0] {:run #uuid "00000000-0000-0000-0000-000000000001"}})
      (assoc-in [:grid :selected-cell] [0 0])))

(defn- context [s]
  (fx/create-context s state/cache-factory))

(defn- status-write
  "What a playing show changes in app state: the show clock's status (the beat
   square, the stats)."
  [ctx]
  (fx/swap-context ctx #(-> %
                            (update-in [:timing :beat-index] (fn [b] (mod (inc (or b 0)) 4)))
                            (assoc-in [:backend :streaming :frame-stats] {:avg-latency-us 123}))))

(deftest cell-display-data-output-test
  (let [ctx (context base-state)]
    (is (= {:col 0 :row 0
            :name "Intro"
            :cue-chain cue-chain
            :preset-count 3
            :first-preset-id :circle
            :active? true
            :selected? true
            :timeline? false
            :has-content? true}
           (fx/sub-ctx ctx subs/cell-display-data 0 0)))
    (testing "empty cell"
      (is (= {:col 3 :row 2
              :name nil
              :cue-chain {:items []}
              :preset-count 0
              :first-preset-id nil
              :active? false
              :selected? false
              :timeline? false
              :has-content? false}
             (fx/sub-ctx ctx subs/cell-display-data 3 2))))))

(deftest cell-display-data-ignores-unrelated-changes-test
  (let [ctx (context base-state)
        before (fx/sub-ctx ctx subs/cell-display-data 0 0)]
    (testing "the show clock's status while a show plays"
      (let [ctx' (status-write ctx)]
        (is (identical? before (fx/sub-ctx ctx' subs/cell-display-data 0 0)))))
    (testing "another cell starts playing"
      (let [ctx' (fx/swap-context ctx assoc-in [:playback :active-cues [1 0]] {:run (random-uuid)})]
        (is (identical? before (fx/sub-ctx ctx' subs/cell-display-data 0 0)))))
    (testing "edits to another cell's chain"
      (let [ctx' (fx/swap-context ctx assoc-in [:chains :cue-chains [1 0] :name] "Other")]
        (is (identical? before (fx/sub-ctx ctx' subs/cell-display-data 0 0)))))
    (testing "this cell stops playing"
      (let [ctx' (fx/swap-context ctx update-in [:playback :active-cues] dissoc [0 0])]
        (is (false? (:active? (fx/sub-ctx ctx' subs/cell-display-data 0 0))))))
    (testing "this cell's chain changes"
      (let [ctx' (fx/swap-context ctx assoc-in [:chains :cue-chains [0 0] :name] "Renamed")]
        (is (= "Renamed" (:name (fx/sub-ctx ctx' subs/cell-display-data 0 0))))))))

(deftest effect-cell-display-data-test
  (let [ctx (context base-state)
        before (fx/sub-ctx ctx subs/effect-cell-display-data 0 0)]
    (is (= {:col 0 :row 0
            :name "FX"
            :effect-count 2
            :first-effect-id :scale
            :active? true
            :selected? false
            :has-effects? true
            :display-text "scale +1"}
           before))
    (testing "unaffected by cue-chain edits and the show clock's status"
      (let [ctx' (-> ctx
                     (fx/swap-context assoc-in [:chains :cue-chains [0 0] :name] "Renamed")
                     (status-write))]
        (is (identical? before (fx/sub-ctx ctx' subs/effect-cell-display-data 0 0)))))))

(deftest beat-index-sub-test
  (let [ctx (context base-state)]
    (is (= 0 (fx/sub-ctx ctx subs/beat-index)) "the downbeat before any status")
    (testing "the status moves it"
      (is (= 3 (fx/sub-ctx (fx/swap-context ctx assoc-in [:timing :beat-index] 3) subs/beat-index))))
    (testing "the stats alone don't"
      (let [ctx' (fx/swap-context ctx assoc-in [:backend :streaming :frame-stats] {:avg-latency-us 10})]
        (is (= 0 (fx/sub-ctx ctx' subs/beat-index)))))))

(deftest timeline-dialog-subs-ignore-playback-and-other-cells-test
  (let [ctx (context base-state)
        chain (fx/sub-ctx ctx subs/cue-cell-chain 0 0)
        ui (fx/sub-ctx ctx subs/timeline-ui 0 0)]
    (testing "the show clock's status doesn't invalidate the dialog's subs"
      (let [ctx' (status-write ctx)]
        (is (identical? chain (fx/sub-ctx ctx' subs/cue-cell-chain 0 0)))
        (is (identical? ui (fx/sub-ctx ctx' subs/timeline-ui 0 0)))))
    (testing "another cell's edits and view don't either"
      (let [ctx' (-> ctx
                     (fx/swap-context assoc-in [:chains :cue-chains [1 0] :name] "Other")
                     (fx/swap-context assoc-in [:ui :timeline-editor [1 0] :selection] #{:x}))]
        (is (identical? chain (fx/sub-ctx ctx' subs/cue-cell-chain 0 0)))
        (is (identical? ui (fx/sub-ctx ctx' subs/timeline-ui 0 0)))))))
