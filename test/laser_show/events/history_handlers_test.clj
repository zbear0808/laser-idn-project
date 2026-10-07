(ns laser-show.events.history-handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.events.handlers :as handlers]
            [laser-show.events.handlers.history :as history-handlers]
            [laser-show.events.core :as events]))

(defn test-state []
  (-> (domains/build-initial-state)
      (assoc-in [:grid :size] [8 4])
      (assoc-in [:timing :bpm] 120.0)
      (assoc-in [:chains :cue-chains [0 0] :items]
                [{:id #uuid "11111111-1111-1111-1111-111111111111"
                  :type :preset
                  :preset-id :test-preset
                  :enabled? true}])
      (assoc :history {:undo-stack []
                       :redo-stack []
                       :transaction nil})))

(deftest discrete-event-automatic-history-test
  (testing "discrete event automatically captures pre-action snapshot on undo stack"
    (let [pipeline events/handle-event-pipeline
          s0 (test-state)
          ;; Clear cell [0 0]
          clear-res (pipeline {:event/type :grid/clear-cell
                               :col 0 :row 0
                               :state s0})
          s1 (:state clear-res)
          h1 (:history s1)]
      (is (nil? (get-in s1 [:chains :cue-chains [0 0]])))
      (is (true? (history/can-undo? h1)))
      (is (false? (history/can-redo? h1)))
      (is (= "Clear Cell" (history/undo-label h1)))

      ;; Now perform Undo
      (let [undo-res (pipeline {:event/type :edit/undo :state s1})
            s2 (:state undo-res)
            h2 (:history s2)]
        (is (= 1 (count (get-in s2 [:chains :cue-chains [0 0] :items]))))
        (is (false? (history/can-undo? h2)))
        (is (true? (history/can-redo? h2)))
        (is (= "Clear Cell" (history/redo-label h2)))

        ;; Now perform Redo
        (let [redo-res (pipeline {:event/type :edit/redo :state s2})
              s3 (:state redo-res)
              h3 (:history s3)]
          (is (nil? (get-in s3 [:chains :cue-chains [0 0]])))
          (is (true? (history/can-undo? h3)))
          (is (false? (history/can-redo? h3))))))))

(deftest continuous-transaction-lifecycle-test
  (testing "continuous mouse-down and mouse-up groups multiple intermediate changes into single undo step"
    (let [pipeline events/handle-event-pipeline
          s0 (test-state)
          ;; Mouse down on slider
          tx-begin (pipeline {:event/type :history/begin-transaction
                              :label "Adjust BPM"
                              :source-id [:slider :bpm]
                              :state s0})
          s1 (:state tx-begin)]
      (is (some? (get-in s1 [:history :transaction])))
      (is (false? (history/can-undo? (:history s1))))

      ;; Intermediate slider drag frames
      (let [drag-1 (pipeline {:event/type :timing/set-bpm :bpm 130.0 :state s1})
            s2 (:state drag-1)]
        (is (= 130.0 (get-in s2 [:timing :bpm])))
        ;; Still 0 items on undo stack because transaction is active
        (is (= 0 (count (get-in s2 [:history :undo-stack]))))

        (let [drag-2 (pipeline {:event/type :timing/set-bpm :bpm 140.0 :state s2})
              s3 (:state drag-2)]
          (is (= 140.0 (get-in s3 [:timing :bpm])))
          (is (= 0 (count (get-in s3 [:history :undo-stack]))))

          ;; Mouse up on slider (commit)
          (let [tx-commit (pipeline {:event/type :history/commit-transaction
                                     :source-id [:slider :bpm]
                                     :state s3})
                s4 (:state tx-commit)
                h4 (:history s4)]
            (is (nil? (get-in s4 [:history :transaction])))
            (is (true? (history/can-undo? h4)))
            (is (= 1 (count (:undo-stack h4))))
            (is (= "Adjust BPM" (history/undo-label h4)))
            (is (= 140.0 (get-in s4 [:timing :bpm])))

            ;; Undo restores to original 120.0 before drag began
            (let [undo-res (pipeline {:event/type :edit/undo :state s4})
                  s5 (:state undo-res)]
              (is (= 120.0 (get-in s5 [:timing :bpm])))
              (is (true? (history/can-redo? (:history s5)))))))))))

(deftest continuous-transaction-abort-test
  (testing "abort-transaction reverts document back to baseline snapshot"
    (let [pipeline events/handle-event-pipeline
          s0 (test-state)
          ;; Mouse down
          tx-begin (pipeline {:event/type :history/begin-transaction
                              :label "Adjust BPM"
                              :source-id [:slider :bpm]
                              :state s0})
          s1 (:state tx-begin)
          ;; Drag
          drag-1 (pipeline {:event/type :timing/set-bpm :bpm 160.0 :state s1})
          s2 (:state drag-1)]
      (is (= 160.0 (get-in s2 [:timing :bpm])))

      ;; User hits Escape -> abort transaction
      (let [tx-abort (pipeline {:event/type :history/abort-transaction :state s2})
            s3 (:state tx-abort)]
        (is (nil? (get-in s3 [:history :transaction])))
        ;; Restored back to 120.0
        (is (= 120.0 (get-in s3 [:timing :bpm])))
        ;; Undo stack is empty
        (is (false? (history/can-undo? (:history s3))))))))

(deftest runtime-events-do-not-trigger-history-test
  (testing "runtime events such as selection do not push undo snapshots"
    (let [pipeline events/handle-event-pipeline
          s0 (test-state)
          sel-res (pipeline {:event/type :grid/select-cell :col 1 :row 1 :state s0})
          s1 (:state sel-res)]
      (is (= [1 1] (get-in s1 [:grid :selected-cell])))
      (is (false? (history/can-undo? (:history s1))))
      (is (= 0 (count (get-in s1 [:history :undo-stack])))))))

