(ns laser-show.state.history-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.state.history :as history]))

(def sample-state
  {:config {:grid {:cols 8 :rows 4}}
   :timing {:bpm 120.0
            :global-clock {:accumulated-beats 4.0}}
   :playback {:playing? true
              :active-cues {[0 0] {:trigger-time 100}}}
   :grid {:size [8 4]
          :selected-cell [0 0]}
   :ui {:active-tab :grid
        :preview {:frame {:points []}}}
   :backend {:idn {:connected? true}}
   :projectors {:proj-1 {:name "Main" :enabled? true}}
   :virtual-projectors {}
   :zone-groups {:all {:id :all :name "All"}}
   :chains {:cue-chains {[0 0] {:items [{:id #uuid "11111111-1111-1111-1111-111111111111"
                                         :type :preset
                                         :params {:size 1.0}}]}}
            :effect-chains {}
            :projector-effects {}}
   :history {:undo-stack []
             :redo-stack []
             :transaction nil}})

(deftest extract-document-state-test
  (testing "extracts only document fields and ignores runtime data"
    (let [doc (history/extract-document-state sample-state)]
      (is (= {:cols 8 :rows 4} (get-in doc [:config :grid])))
      (is (= 120.0 (get-in doc [:timing :bpm])))
      (is (= [8 4] (get-in doc [:grid :size])))
      (is (= "Main" (get-in doc [:projectors :proj-1 :name])))
      (is (= 1 (count (get-in doc [:chains :cue-chains [0 0] :items]))))
      ;; Runtime fields must NOT be in document state
      (is (nil? (:playback doc)))
      (is (nil? (:backend doc)))
      (is (nil? (:ui doc)))
      (is (nil? (get-in doc [:timing :global-clock]))))))

(deftest apply-document-state-test
  (testing "restores document fields without clobbering runtime state"
    (let [modified-doc (-> (history/extract-document-state sample-state)
                           (assoc-in [:timing :bpm] 145.0)
                           (assoc-in [:chains :cue-chains [0 0] :items] []))
          restored-state (history/apply-document-state sample-state modified-doc)]
      (is (= 145.0 (get-in restored-state [:timing :bpm])))
      (is (= [] (get-in restored-state [:chains :cue-chains [0 0] :items])))
      ;; Runtime state remains intact
      (is (= true (get-in restored-state [:playback :playing?])))
      (is (= true (get-in restored-state [:backend :idn :connected?])))
      (is (= 4.0 (get-in restored-state [:timing :global-clock :accumulated-beats])))
      (is (= true (get-in restored-state [:project :dirty?]))))))

(deftest push-and-pop-undo-test
  (testing "pushing snapshot clears redo stack and enables undo"
    (let [h0 {:undo-stack [] :redo-stack [{:description "Old Redo"}] :transaction nil}
          s1 (history/make-snapshot {:state sample-state :description "Change BPM"})
          h1 (history/push-undo-snapshot h0 s1)]
      (is (true? (history/can-undo? h1)))
      (is (false? (history/can-redo? h1)))
      (is (= "Change BPM" (history/undo-label h1)))
      (is (= 1 (count (:undo-stack h1))))))

  (testing "pop-undo pops from undo, pushes onto redo, and returns snapshot"
    (let [s1 (history/make-snapshot {:state sample-state :description "Change BPM"})
          h1 (history/push-undo-snapshot {:undo-stack [] :redo-stack []} s1)
          state-after-edit (assoc-in sample-state [:timing :bpm] 140.0)
          res (history/pop-undo h1 state-after-edit {:active-tab :grid})]
      (is (some? res))
      (let [{:keys [history snapshot]} res]
        (is (= 0 (count (:undo-stack history))))
        (is (= 1 (count (:redo-stack history))))
        (is (false? (history/can-undo? history)))
        (is (true? (history/can-redo? history)))
        (is (= "Change BPM" (:description snapshot)))
        (is (= 120.0 (get-in snapshot [:document-state :timing :bpm])))))))

(deftest transaction-lifecycle-test
  (testing "transaction captures baseline on begin, and commits on mouse-up only if changed"
    (let [h0 {:undo-stack [] :redo-stack [] :transaction nil}
          ;; Mouse down on slider
          h1 (history/begin-transaction h0 sample-state {:label "Drag Slider" :source-id [:slider :bpm]})]
      (is (some? (:transaction h1)))
      (is (= "Drag Slider" (get-in h1 [:transaction :label])))
      ;; If mouse released without any state change -> discarded cleanly
      (let [h-no-change (history/commit-transaction h1 sample-state)]
        (is (nil? (:transaction h-no-change)))
        (is (false? (history/can-undo? h-no-change))))
      ;; If state changed during drag -> committed to undo stack
      (let [state-changed (assoc-in sample-state [:timing :bpm] 150.0)
            h-committed (history/commit-transaction h1 state-changed)]
        (is (nil? (:transaction h-committed)))
        (is (true? (history/can-undo? h-committed)))
        (is (= "Drag Slider" (history/undo-label h-committed)))
        (is (= 120.0 (get-in (peek (:undo-stack h-committed)) [:document-state :timing :bpm]))))))

  (testing "abort-transaction returns baseline and clears transaction"
    (let [h0 {:undo-stack [] :redo-stack [] :transaction nil}
          h1 (history/begin-transaction h0 sample-state {:label "Drag Slider" :source-id [:slider :bpm]})
          {:keys [history baseline]} (history/abort-transaction h1)]
      (is (nil? (:transaction history)))
      (is (some? baseline))
      (is (= 120.0 (get-in baseline [:document-state :timing :bpm]))))))

