(ns laser-show.events.keyframe-handlers-test
  "Unit tests for keyframe modulator event handlers.
   
   Tests the keyframe modulator operations for effect chains.
   These handlers follow the re-frame pattern: pure functions that receive
   state as part of the event map and return effect maps with :state key."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.handlers.keyframe :as keyframe]
            [laser-show.events.handlers.chain :as chain]))


;; Test Fixtures


(def sample-effect
  "A typical effect instance with params."
  {:id #uuid "11111111-1111-1111-1111-111111111111"
   :effect-id :translate
   :enabled? true
   :params {:x 0.5 :y 0.3 :z 0.0}})

(def sample-effect-with-keyframes
  "An effect with an existing keyframe modulator."
  {:id #uuid "22222222-2222-2222-2222-222222222222"
   :effect-id :scale
   :enabled? true
   :params {:x 1.0 :y 1.0}
   :keyframe-modulator {:enabled? true
                        :period 4.0
                        :time-unit :beats
                        :loop-mode :loop
                        :selected-keyframe 0
                        :keyframes [{:position 0.0 :params {:x 0.5 :y 0.5}}
                                    {:position 0.5 :params {:x 1.5 :y 1.5}}
                                    {:position 1.0 :params {:x 0.5 :y 0.5}}]}})

(defn make-test-state
  "Create a test state with effects at [0 0] cell."
  [effects]
  {:chains {:effect-chains {[0 0] {:items effects}}}
   :ui {:dialogs {:effect-chain-editor {:data {}}}}
   :project {:dirty? false}})

(def sample-config
  "Chain config for effect-chains at [0 0]."
  (chain/chain-config :effect-chains [0 0]))


;; Helper function tests


(deftest clamp-position-test
  (testing "Clamps position to valid range"
    ;; Access private function via var
    (let [clamp-position #'keyframe/clamp-position]
      (is (= 0.5 (clamp-position 0.5)) "Value in range stays unchanged")
      (is (= 0.0 (clamp-position -0.5)) "Negative value clamps to 0.0")
      (is (= 1.0 (clamp-position 1.5)) "Value > 1.0 clamps to 1.0")
      (is (= 0.0 (clamp-position 0.0)) "Edge case: exactly 0.0")
      (is (= 1.0 (clamp-position 1.0)) "Edge case: exactly 1.0"))))


;; Toggle Enabled Tests


(deftest handle-toggle-enabled-test
  (testing "Enabling creates new keyframe modulator when none exists"
    (let [state (make-test-state [sample-effect])
          result (keyframe/handle-toggle-enabled state sample-config [0] true)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      (is (some? kf-mod) "Keyframe modulator should be created")
      (is (true? (:enabled? kf-mod)) "Should be enabled")
      (is (= 4.0 (:period kf-mod)) "Should have default period")
      (is (= :beats (:time-unit kf-mod)) "Should have default time-unit")
      (is (= :loop (:loop-mode kf-mod)) "Should have default loop-mode")
      (is (= 2 (count (:keyframes kf-mod))) "Should have 2 default keyframes")
      (is (= 0.0 (-> kf-mod :keyframes first :position)) "First keyframe at 0.0")
      (is (= 1.0 (-> kf-mod :keyframes second :position)) "Second keyframe at 1.0")
      (is (= {:x 0.5 :y 0.3 :z 0.0} (-> kf-mod :keyframes first :params)) 
          "Keyframes should use effect's current params")))
  
  (testing "Enabling existing modulator just sets enabled? flag"
    (let [state (make-test-state [sample-effect-with-keyframes])
          ;; First disable it
          disabled-state (assoc-in state 
                                   [:chains :effect-chains [0 0] :items 0 :keyframe-modulator :enabled?] 
                                   false)
          result (keyframe/handle-toggle-enabled disabled-state sample-config [0] true)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      (is (true? (:enabled? kf-mod)) "Should be enabled")
      (is (= 3 (count (:keyframes kf-mod))) "Should preserve existing keyframes")))
  
  (testing "Disabling sets enabled? to false"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-toggle-enabled state sample-config [0] false)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      (is (false? (:enabled? kf-mod)) "Should be disabled")
      (is (= 3 (count (:keyframes kf-mod))) "Should preserve keyframes")))
  
  (testing "Marks project dirty"
    (let [state (make-test-state [sample-effect])
          result (keyframe/handle-toggle-enabled state sample-config [0] true)]
      (is (true? (get-in result [:project :dirty?])) "Project should be marked dirty"))))


;; Initialize Tests


(deftest handle-initialize-test
  (testing "Initializes keyframe modulator with defaults"
    (let [state (make-test-state [sample-effect])
          result (keyframe/handle-initialize state sample-config [0])
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      (is (true? (:enabled? kf-mod)))
      (is (= 4.0 (:period kf-mod)))
      (is (= :beats (:time-unit kf-mod)))
      (is (= :loop (:loop-mode kf-mod)))
      (is (= 0 (:selected-keyframe kf-mod)))
      (is (= 2 (count (:keyframes kf-mod)))))))


;; Update Settings Tests


(deftest handle-update-settings-test
  (testing "Updates multiple settings at once"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-update-settings state sample-config [0] 
                                                  {:period 8.0 :time-unit :ms :loop-mode :ping-pong})
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      (is (= 8.0 (:period kf-mod)))
      (is (= :ms (:time-unit kf-mod)))
      (is (= :ping-pong (:loop-mode kf-mod)))
      (is (true? (:enabled? kf-mod)) "Other fields should be preserved"))))


(deftest handle-update-setting-test
  (testing "Updates a single setting"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-update-setting state sample-config [0] :period 16.0)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      (is (= 16.0 (:period kf-mod)))
      (is (= :beats (:time-unit kf-mod)) "Other settings should be preserved"))))


;; Select Keyframe Tests


(deftest handle-select-keyframe-test
  (testing "Selects a keyframe by index"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-select-keyframe state sample-config [0] 2)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      (is (= 2 (:selected-keyframe kf-mod)))))
  
  (testing "Does not mark dirty (selection is UI state)"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-select-keyframe state sample-config [0] 1)]
      ;; Select doesn't call mark-dirty
      (is (false? (get-in result [:project :dirty?]))))))


;; Add Keyframe Tests


(deftest handle-add-keyframe-test
  (testing "Adds keyframe at specified position"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-add-keyframe state sample-config [0] 0.25 nil)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])
          keyframes (:keyframes kf-mod)]
      (is (= 4 (count keyframes)) "Should have 4 keyframes now")
      ;; Should be sorted by position
      (is (= [0.0 0.25 0.5 1.0] (mapv :position keyframes)))))
  
  (testing "Adds keyframe with provided params"
    (let [state (make-test-state [sample-effect-with-keyframes])
          custom-params {:x 2.0 :y 3.0}
          result (keyframe/handle-add-keyframe state sample-config [0] 0.75 custom-params)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])
          new-kf (first (filter #(= 0.75 (:position %)) (:keyframes kf-mod)))]
      (is (= custom-params (:params new-kf)))))
  
  (testing "Clamps position to valid range"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-add-keyframe state sample-config [0] 1.5 nil)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])
          positions (mapv :position (:keyframes kf-mod))]
      (is (every? #(<= 0.0 % 1.0) positions) "All positions should be in valid range")))
  
  (testing "Selects newly added keyframe"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-add-keyframe state sample-config [0] 0.25 nil)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      ;; New keyframe at 0.25 should be at index 1 after sorting
      (is (= 1 (:selected-keyframe kf-mod))))))


;; Move Keyframe Tests


(deftest handle-move-keyframe-test
  (testing "Moves keyframe to new position"
    (let [state (make-test-state [sample-effect-with-keyframes])
          ;; Move the middle keyframe (index 1, position 0.5) to position 0.75
          result (keyframe/handle-move-keyframe state sample-config [0] 1 0.75)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])
          positions (mapv :position (:keyframes kf-mod))]
      (is (= [0.0 0.75 1.0] positions))))
  
  (testing "Re-sorts keyframes after move"
    (let [state (make-test-state [sample-effect-with-keyframes])
          ;; Move the first keyframe (index 0, position 0.0) to position 0.9
          result (keyframe/handle-move-keyframe state sample-config [0] 0 0.9)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])
          positions (mapv :position (:keyframes kf-mod))]
      ;; After moving 0.0 to 0.9, order should be [0.5, 0.9, 1.0]
      (is (= [0.5 0.9 1.0] positions))))
  
  (testing "Updates selected-keyframe to track moved keyframe"
    (let [state (make-test-state [sample-effect-with-keyframes])
          ;; Move the first keyframe (index 0) to position 0.9
          ;; After sorting, it will be at index 1
          result (keyframe/handle-move-keyframe state sample-config [0] 0 0.9)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      (is (= 1 (:selected-keyframe kf-mod)))))
  
  (testing "Clamps position to valid range"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-move-keyframe state sample-config [0] 1 -0.5)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])
          positions (mapv :position (:keyframes kf-mod))]
      (is (every? #(<= 0.0 % 1.0) positions))))
  
  (testing "Returns state unchanged for invalid keyframe index"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-move-keyframe state sample-config [0] 10 0.5)]
      (is (= state result)))))


;; Delete Keyframe Tests


(deftest handle-delete-keyframe-test
  (testing "Deletes keyframe at index"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-delete-keyframe state sample-config [0] 1)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])
          keyframes (:keyframes kf-mod)]
      (is (= 2 (count keyframes)))
      (is (= [0.0 1.0] (mapv :position keyframes)))))
  
  (testing "Cannot delete if only one keyframe remains"
    (let [;; Create state with only one keyframe
          one-kf-effect (assoc-in sample-effect-with-keyframes 
                                  [:keyframe-modulator :keyframes]
                                  [{:position 0.5 :params {:x 1.0 :y 1.0}}])
          state (make-test-state [one-kf-effect])
          result (keyframe/handle-delete-keyframe state sample-config [0] 0)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      (is (= 1 (count (:keyframes kf-mod))) "Should still have 1 keyframe")))
  
  (testing "Adjusts selected-keyframe when deleting before selection"
    (let [;; Select the last keyframe (index 2)
          state (-> (make-test-state [sample-effect-with-keyframes])
                    (assoc-in [:chains :effect-chains [0 0] :items 0 :keyframe-modulator :selected-keyframe] 2))
          result (keyframe/handle-delete-keyframe state sample-config [0] 0)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      ;; After deleting index 0, the previously-index-2 item is now at index 1
      (is (= 1 (:selected-keyframe kf-mod)))))
  
  (testing "Adjusts selected-keyframe when deleting at end"
    (let [;; Select the last keyframe (index 2)
          state (-> (make-test-state [sample-effect-with-keyframes])
                    (assoc-in [:chains :effect-chains [0 0] :items 0 :keyframe-modulator :selected-keyframe] 2))
          result (keyframe/handle-delete-keyframe state sample-config [0] 2)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      (is (= 1 (:selected-keyframe kf-mod)) "Should select last remaining keyframe")))
  
  (testing "Returns state unchanged for invalid index"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-delete-keyframe state sample-config [0] -1)]
      (is (= state result)))))


;; Update Keyframe Param Tests


(deftest handle-update-keyframe-param-test
  (testing "Updates a single parameter in keyframe"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-update-keyframe-param state sample-config [0] 0 :x 0.8)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])
          first-kf (first (:keyframes kf-mod))]
      (is (= 0.8 (get-in first-kf [:params :x])))
      (is (= 0.5 (get-in first-kf [:params :y])) "Other params should be preserved")))
  
  (testing "Returns state unchanged for invalid index"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-update-keyframe-param state sample-config [0] 10 :x 0.8)]
      (is (= state result)))))


;; Update Keyframe Params Tests


(deftest handle-update-keyframe-params-test
  (testing "Merges params map into keyframe"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-update-keyframe-params state sample-config [0] 0 {:x 2.0 :z 1.0})
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])
          first-kf (first (:keyframes kf-mod))]
      (is (= 2.0 (get-in first-kf [:params :x])))
      (is (= 0.5 (get-in first-kf [:params :y])) "Existing params should be preserved")
      (is (= 1.0 (get-in first-kf [:params :z])) "New params should be added"))))


;; Copy Effect Params Tests


(deftest handle-copy-effect-params-to-keyframe-test
  (testing "Copies effect's base params to keyframe"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-copy-effect-params-to-keyframe state sample-config [0] 1)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])
          target-kf (nth (:keyframes kf-mod) 1)]
      ;; Effect's params are {:x 1.0 :y 1.0}
      (is (= {:x 1.0 :y 1.0} (:params target-kf))))))


;; Main Handle Function Tests


(deftest handle-dispatch-test
  (testing "Dispatches :keyframe/toggle-enabled"
    (let [state (make-test-state [sample-effect])
          event {:event/type :keyframe/toggle-enabled
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :enabled? true
                 :state state}
          result (keyframe/handle event)]
      (is (some? (get-in result [:state :chains :effect-chains [0 0] :items 0 :keyframe-modulator])))))
  
  (testing "Dispatches :keyframe/initialize"
    (let [state (make-test-state [sample-effect])
          event {:event/type :keyframe/initialize
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :state state}
          result (keyframe/handle event)]
      (is (some? (get-in result [:state :chains :effect-chains [0 0] :items 0 :keyframe-modulator])))))
  
  (testing "Dispatches :keyframe/update-settings"
    (let [state (make-test-state [sample-effect-with-keyframes])
          event {:event/type :keyframe/update-settings
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :period 16.0
                 :time-unit :ms
                 :state state}
          result (keyframe/handle event)
          kf-mod (get-in result [:state :chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      (is (= 16.0 (:period kf-mod)))
      (is (= :ms (:time-unit kf-mod)))))
  
  (testing "Dispatches :keyframe/update-setting"
    (let [state (make-test-state [sample-effect-with-keyframes])
          event {:event/type :keyframe/update-setting
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :setting-key :period
                 :fx/event 32.0
                 :state state}
          result (keyframe/handle event)
          kf-mod (get-in result [:state :chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      (is (= 32.0 (:period kf-mod)))))
  
  (testing "Dispatches :keyframe/select"
    (let [state (make-test-state [sample-effect-with-keyframes])
          event {:event/type :keyframe/select
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :keyframe-idx 2
                 :state state}
          result (keyframe/handle event)
          kf-mod (get-in result [:state :chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      (is (= 2 (:selected-keyframe kf-mod)))))
  
  (testing "Dispatches :keyframe/add"
    (let [state (make-test-state [sample-effect-with-keyframes])
          event {:event/type :keyframe/add
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :position 0.25
                 :state state}
          result (keyframe/handle event)
          kf-mod (get-in result [:state :chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      (is (= 4 (count (:keyframes kf-mod))))))
  
  (testing "Dispatches :keyframe/move"
    (let [state (make-test-state [sample-effect-with-keyframes])
          event {:event/type :keyframe/move
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :keyframe-idx 1
                 :new-position 0.75
                 :state state}
          result (keyframe/handle event)
          kf-mod (get-in result [:state :chains :effect-chains [0 0] :items 0 :keyframe-modulator])
          positions (mapv :position (:keyframes kf-mod))]
      (is (= [0.0 0.75 1.0] positions))))
  
  (testing "Dispatches :keyframe/delete"
    (let [state (make-test-state [sample-effect-with-keyframes])
          event {:event/type :keyframe/delete
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :keyframe-idx 1
                 :state state}
          result (keyframe/handle event)
          kf-mod (get-in result [:state :chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      (is (= 2 (count (:keyframes kf-mod))))))
  
  (testing "Dispatches :keyframe/update-param"
    (let [state (make-test-state [sample-effect-with-keyframes])
          event {:event/type :keyframe/update-param
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :keyframe-idx 0
                 :param-key :x
                 :value 0.9
                 :state state}
          result (keyframe/handle event)
          kf-mod (get-in result [:state :chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      (is (= 0.9 (get-in kf-mod [:keyframes 0 :params :x])))))
  
  (testing "Dispatches :keyframe/update-params"
    (let [state (make-test-state [sample-effect-with-keyframes])
          event {:event/type :keyframe/update-params
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :keyframe-idx 0
                 :params {:x 2.0 :y 2.0}
                 :state state}
          result (keyframe/handle event)
          kf-mod (get-in result [:state :chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      (is (= 2.0 (get-in kf-mod [:keyframes 0 :params :x])))
      (is (= 2.0 (get-in kf-mod [:keyframes 0 :params :y])))))
  
  (testing "Dispatches :keyframe/copy-effect-params"
    (let [state (make-test-state [sample-effect-with-keyframes])
          event {:event/type :keyframe/copy-effect-params
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :keyframe-idx 0
                 :state state}
          result (keyframe/handle event)
          kf-mod (get-in result [:state :chains :effect-chains [0 0] :items 0 :keyframe-modulator])]
      ;; Effect base params are {:x 1.0 :y 1.0}
      (is (= {:x 1.0 :y 1.0} (get-in kf-mod [:keyframes 0 :params])))))
  
  (testing "Unknown event type returns empty map"
    (let [event {:event/type :keyframe/unknown-type
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :state {}}
          result (keyframe/handle event)]
      (is (= {} result)))))


;; Nested Effect Path Tests


(deftest nested-effect-path-test
  (testing "Works with nested effect paths in groups"
    (let [;; Create a group containing an effect
          grouped-effect {:type :group
                          :id #uuid "33333333-3333-3333-3333-333333333333"
                          :name "Test Group"
                          :enabled? true
                          :items [sample-effect]}
          state (make-test-state [grouped-effect])
          ;; Effect path is [0 :items 0] for first item in first group
          result (keyframe/handle-toggle-enabled state sample-config [0 :items 0] true)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :items 0 :keyframe-modulator])]
      (is (some? kf-mod) "Should create keyframe modulator in nested effect")
      (is (true? (:enabled? kf-mod))))))
