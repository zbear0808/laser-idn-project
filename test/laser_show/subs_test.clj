(ns laser-show.subs-test
  "Subscription tests run against a real cljfx context (no FX toolkit needed)."
  (:require
   [cljfx.api :as fx]
   [clojure.test :refer [deftest is testing]]
   [laser-show.events.handlers.timing :as timing]
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
            :effect-ids [:scale :rotate]
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

(def ^:private playing-state
  "Three cues playing, started in no particular order: [0 1] (B1), [1 0] (A2)
   and [0 0] (A1)."
  (-> base-state
      (assoc-in [:chains :cue-chains [0 1]] {:items [{:type :preset :preset-id :star}]})
      (assoc-in [:playback :active-cues] {[0 1] {:run #uuid "00000000-0000-0000-0000-000000000003"}
                                          [1 0] {:run #uuid "00000000-0000-0000-0000-000000000002"}
                                          [0 0] {:run #uuid "00000000-0000-0000-0000-000000000001"}})
      (assoc-in [:playback :playing?] true)))

(defn- after [state & event-types]
  (reduce (fn [s t] (:state (timing/handle {:event/type t :state s}))) state event-types))

(deftest output-held-sub-test
  (let [held? #(fx/sub-ctx (context %) subs/output-held?)
        held #(fx/sub-ctx (context %) subs/held-cue-count)]
    (testing "while cues play, nothing is held"
      (is (false? (held? playing-state)))
      (is (zero? (held playing-state))))
    (testing "Stop is the blackout: it leaves the cues in play, and holds them"
      (is (true? (held? (after playing-state :transport/stop))))
      (is (= 3 (held (after playing-state :transport/stop)))))
    (testing "Play resumes them"
      (is (false? (held? (after playing-state :transport/stop :transport/play))))
      (is (zero? (held (after playing-state :transport/stop :transport/play)))))
    (testing "with no cue left there is nothing to hold, playing or not"
      (let [none (assoc-in playing-state [:playback :active-cues] {})]
        (is (false? (held? none)))
        (is (false? (held? (after none :transport/stop))))
        (is (zero? (held (after none :transport/stop))))))))

(deftest playing-cues-sub-test
  (let [ctx (context playing-state)
        cues (fx/sub-ctx ctx subs/playing-cues)]
    (testing "row by row, as the grid reads (A1, A2, then B1), with what each cell is named from"
      (is (= [{:col 0 :row 0 :name "Intro" :first-preset-id :circle :preset-count 3 :timeline? false}
              {:col 1 :row 0 :name nil :first-preset-id :line :preset-count 1 :timeline? false}
              {:col 0 :row 1 :name nil :first-preset-id :star :preset-count 1 :timeline? false}]
             cues)))
    (testing "unchanged by what changes inside the cues' intents (a pause, a seek) and the show clock's status"
      (let [ctx' (-> ctx
                     (fx/swap-context assoc-in [:playback :active-cues [0 0] :paused?] true)
                     (status-write))]
        (is (identical? cues (fx/sub-ctx ctx' subs/playing-cues)))))
    (testing "a cue that stops leaves the list"
      (is (= [[0 0] [0 1]]
             (map (juxt :col :row)
                  (fx/sub-ctx (fx/swap-context ctx update-in [:playback :active-cues] dissoc [1 0])
                              subs/playing-cues)))))
    (testing "nothing plays while Stop holds them"
      (is (= [] (fx/sub-ctx (context (after playing-state :transport/stop)) subs/playing-cues))))))

(deftest connection-status-says-why-nothing-streams-test
  (let [status-at (fn [running? status]
                    (select-keys (fx/sub-ctx (context (-> base-state
                                                          (assoc-in [:backend :streaming :running?] running?)
                                                          (assoc-in [:backend :streaming :status] status)))
                                             subs/connection-status)
                                 [:state :status-text]))]
    (is (= {:state :idle :status-text "No projectors enabled"}
           (status-at true {:engines 0 :wanted 0 :pending? false :projectors {}}))
        "streaming is on, with nothing to stream")
    (is (= {:state :streaming :status-text "Streaming to 1 of 1"}
           (status-at true {:engines 1 :wanted 1 :pending? false
                            :projectors {:a {:state :streaming :fps 59.8}}})))
    (is (= {:state :stopped :status-text "Not streaming"} (status-at false nil)))))
