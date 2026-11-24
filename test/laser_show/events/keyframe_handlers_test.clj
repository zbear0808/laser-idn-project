(ns laser-show.events.keyframe-handlers-test
  "Unit tests for keyframe modulator event handlers.
   
   Tests the keyframe modulator operations for effect chains.
   These handlers follow the re-frame pattern: pure functions that receive
   state as part of the event map and return effect maps with :state key."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.effects.intensity] ; :intensity's :amount is a :float
            [laser-show.events.core :as events]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.events.handlers.keyframe :as keyframe]
            [laser-show.events.handlers.chain :as chain]
            [laser-show.common.util :as u])
  (:import [javafx.event ActionEvent]))


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
  ;; FLATTENED: Dialog fields live alongside :open?, not under :data
  {:chains {:effect-chains {[0 0] {:items effects}}}
   :ui {:dialogs {:effect-chain-editor {:open? false}}}
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
      (is (history/derive-dirty state result) "Project should be marked dirty"))))


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
          new-kf (u/seek #(= 0.75 (:position %)) (:keyframes kf-mod))]
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


(def keyframed-intensity
  "An Intensity whose keyframes set its :amount (a :float, 0 to 3), and a
   :look its definition doesn't have."
  {:id #uuid "44444444-4444-4444-4444-444444444444"
   :effect-id :intensity
   :enabled? true
   :params {:amount 1.0}
   :keyframe-modulator {:enabled? true
                        :selected-keyframe 1
                        :keyframes [{:position 0.0 :params {:amount 0.3 :look :soft}}
                                    {:position 1.0 :params {:amount 0.9 :look :soft}}]}})

(deftest update-param-commits-only-numbers-to-numeric-params-test
  (let [state (make-test-state [keyframed-intensity sample-effect-with-keyframes])
        edit (fn [event]
               (keyframe/handle (merge {:event/type :keyframe/update-param :domain :effect-chains :entity-key [0 0]
                                        :effect-path [0] :keyframe-idx 1 :param-key :amount :state state}
                                       event)))
        frames (fn [result effect-idx]
                 (mapv :params (get-in result [:state :chains :effect-chains [0 0] :items effect-idx :keyframe-modulator :keyframes])))
        amounts #(mapv :amount (frames % 0))
        typed {:min 0.0 :max 3.0 :is-int? false}]
    (testing "a field's typed text (read on the FX thread): a number in the addressed keyframe, clamped"
      (is (= [0.3 0.42] (amounts (edit (assoc typed :text "0.42")))))
      (is (= [0.3 3.0] (amounts (edit (assoc typed :text "12")))))
      (is (= [0.3 0.0] (amounts (edit (assoc typed :text "-1")))))
      (is (= [0.3 2] (amounts (edit (assoc typed :text "1.7" :is-int? true)))) "a whole number for an :int")
      (is (= 1.0 (get-in (edit (assoc typed :text "0.42")) [:state :chains :effect-chains [0 0] :items 0 :params :amount]))
          "the effect's own value stays"))
    (testing "text that isn't a finite number commits nothing"
      (doseq [text ["abc" "" "NaN" "Infinity" nil]]
        (is (= {} (edit (assoc typed :text text))) (pr-str text))))
    (testing "a slider's number still commits, zero too"
      (is (= [0.3 0.6] (amounts (edit {:fx/event 0.6}))))
      (is (= [0.3 0.0] (amounts (edit {:value 0.0})))))
    (testing "an ActionEvent or another non-number never lands in a numeric parameter"
      (doseq [payload [(ActionEvent.) ##NaN ##Inf "0.4" :fast nil]]
        (is (= {} (edit {:fx/event payload})) (str ":fx/event " (pr-str payload)))
        (is (= {} (edit {:value payload})) (str ":value " (pr-str payload)))))
    (testing "a parameter its definition doesn't have keeps the kind of value its keyframe holds"
      (is (= [:soft :hard] (mapv :look (frames (edit {:param-key :look :fx/event :hard}) 0))))
      (is (= {} (edit {:param-key :look :fx/event (ActionEvent.)})) "but never an ActionEvent")
      (is (= [0.8 1.5 0.5] (mapv :x (frames (edit {:effect-path [1] :keyframe-idx 0 :param-key :x :value 0.8}) 1)))
          "a number where the keyframe holds one")
      (is (= {} (edit {:effect-path [1] :keyframe-idx 0 :param-key :x :value :fast}))))))


;; The selected keyframe's Value and Interp controls (reactivity plan 06)


(def ^:private panel-frames
  "Three keyframes, each its own amount, zone and mode."
  [{:position 0.0 :params {:amount 0.2 :target-zone :auto/right} :interpolation :linear}
   {:position 0.5 :params {:amount 0.5 :target-zone :all} :interpolation :step}
   {:position 1.0 :params {:amount 0.9 :target-zone :auto/odd} :interpolation :exp-decay}])

(def ^:private panel-effect
  {:id #uuid "88888888-8888-8888-8888-888888888888" :effect-id :intensity :enabled? true :params {:amount 1.0}
   :keyframe-modulator {:enabled? true :driver :time :period 4.0 :selected-keyframe 1 :keyframes panel-frames}})

(def ^:private panel-targets
  "The same keyframed effect directly in Effects cell [0 0], and among a cue
   item's effects in cue [2 1]: [address, path of its :keyframes]."
  [[{:domain :effect-chains :entity-key [0 0] :effect-path [0]}
    [:chains :effect-chains [0 0] :items 0 :keyframe-modulator :keyframes]]
   [{:domain :cue-chains :entity-key [2 1] :effect-path [0 :effects 0]}
    [:chains :cue-chains [2 1] :items 0 :effects 0 :keyframe-modulator :keyframes]]])

(defn- panel-state
  "The initial state with both of panel-targets' effects, marked clean."
  []
  (-> (domains/build-initial-state)
      (assoc-in [:chains :effect-chains [0 0]] {:active? false :items [panel-effect]})
      (assoc-in [:chains :cue-chains [2 1]]
                {:name "Cue" :items [{:id #uuid "99999999-9999-9999-9999-999999999999" :type :preset
                                      :preset-id :circle :enabled? true :params {} :effects [panel-effect]}]})
      history/mark-clean))

(defn- pipeline
  "Run event (already as the control delivers it: cljfx has added :fx/event)
   through the event pipeline from state, effects unexecuted: [effects
   state-after]."
  [state event]
  (let [effects (events/handle-event-pipeline (assoc event :state state))]
    [effects (or (:state effects) state)]))

(deftest keyframe-control-payloads-update-only-the-addressed-leaf-test
  (let [zone {:event/type :keyframe/set-zone-value :keyframe-idx 1 :param-key :target-zone
              :fx/event {:id :auto/left :name "Left" :kind :auto}}
        value {:event/type :keyframe/update-param :keyframe-idx 1 :param-key :amount :value :fx/event :fx/event 0.6}
        interp {:event/type :keyframe/set-interpolation :keyframe-idx 1 :interpolation :fx/event :fx/event :exp-grow}]
    (doseq [[address frames-path] panel-targets
            :let [before (panel-state)
                  undo-depth #(count (get-in % [:history :undo-stack]))
                  edit (fn [state event] (pipeline state (merge event address)))]]
      (testing (str "the effect in " (:domain address))
        (testing "an accepted edit: one leaf, one undo step"
          (doseq [[event leaf expected] [[zone [:params :target-zone] :auto/left]
                                         [(-> zone (dissoc :fx/event) (assoc :value :auto/center)) [:params :target-zone] :auto/center]
                                         [(assoc zone :fx/event :auto/even) [:params :target-zone] :auto/even]
                                         [value [:params :amount] 0.6]
                                         [(assoc value :fx/event 0.0) [:params :amount] 0.0]
                                         [interp [:interpolation] :exp-grow]]
                  :let [[effects after] (edit before event)]]
            (is (= (assoc-in (:chains before) (into (vec (rest frames-path)) (cons 1 leaf)) expected)
                   (:chains after))
                (pr-str event))
            (is (= 1 (undo-depth after)) (pr-str event))
            (is (true? (get-in after [:project :dirty?])) (pr-str event))
            (is (nil? (:dispatch effects)) "no follow-up event")
            (is (= (:chains before) (:chains (second (pipeline after {:event/type :edit/undo}))))
                (str "Undo takes back " (pr-str event)))))
        (testing "each of the four modes"
          (let [states (reductions (fn [state mode] (second (edit state (assoc interp :fx/event mode))))
                                   before [:linear :exp-decay :exp-grow :step])]
            (is (= [:step :linear :exp-decay :exp-grow :step]
                   (map #(get-in % (conj frames-path 1 :interpolation)) states)))
            (is (= [0 1 2 3 4] (map undo-depth states)))
            (is (= (mapv :params panel-frames) (mapv :params (get-in (last states) frames-path))) "params stay")))
        (testing "nothing changes, and Undo and Redo stay, for"
          (let [[_ with-redo] (edit before zone)
                [_ with-redo] (pipeline with-redo {:event/type :edit/undo})
                frame-1 {:keyframe-idx 1}]
            (is (= [0 1] [(undo-depth with-redo) (count (get-in with-redo [:history :redo-stack]))]))
            (doseq [[what event] (concat
                                  [["no zone" (dissoc zone :fx/event)]
                                   ["a zone option without an :id" (assoc zone :fx/event {:name "Left"})]
                                   ["a zone that isn't a keyword" (assoc zone :fx/event "Left")]
                                   ["the zone it has" (assoc zone :fx/event {:id :all :name "All"})]
                                   ["an unknown mode" (assoc interp :fx/event :unknown)]
                                   ["no mode" (assoc interp :fx/event nil)]
                                   ["a mode that isn't a keyword" (assoc interp :fx/event "linear")]
                                   ["the mode it has" (assoc interp :fx/event :step)]
                                   ["the amount it has" (assoc value :fx/event 0.5)]
                                   ["no amount" (assoc value :fx/event nil)]]
                                  (for [event [zone value interp]
                                        [what index] [["index -1" (assoc frame-1 :keyframe-idx -1)]
                                                      ["no index" (dissoc frame-1 :keyframe-idx)]
                                                      ["index 3 of three frames" (assoc frame-1 :keyframe-idx 3)]
                                                      ["index 1.0" (assoc frame-1 :keyframe-idx 1.0)]]]
                                    [(str what ", " (name (:event/type event)))
                                     (merge (dissoc event :keyframe-idx) index)])
                                  (for [event [zone value interp]
                                        [what target] [["no effect there" {:effect-path [5]}]
                                                       ["no such cell" {:entity-key [7 7]}]]]
                                    [(str what ", " (name (:event/type event))) (merge event target)]))
                    :let [[effects after] (pipeline with-redo (merge address event))]]
              (testing what
                (is (nil? (:state effects)) "no commit")
                (is (identical? with-redo after))))))))))


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
  
  (testing "Unknown event type returns empty map"
    (let [event {:event/type :keyframe/unknown-type
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :state {}}
          result (keyframe/handle event)]
      (is (= {} result)))))


;; Set Interpolation Tests


(deftest handle-set-interpolation-test
  (testing "Sets interpolation mode on keyframe"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-set-interpolation state sample-config [0] 0 :exp-decay)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])
          first-kf (first (:keyframes kf-mod))]
      (is (= :exp-decay (:interpolation first-kf)))))
  
  (testing "Invalid keyframe index returns unchanged state"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-set-interpolation state sample-config [0] 10 :exp-decay)]
      (is (= state result))))
  
  (testing "Marks project dirty"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-set-interpolation state sample-config [0] 0 :step)]
      (is (history/derive-dirty state result)))))


(deftest handle-add-keyframe-with-interpolation-test
  (testing "New keyframes have default :interpolation :linear"
    (let [state (make-test-state [sample-effect-with-keyframes])
          result (keyframe/handle-add-keyframe state sample-config [0] 0.25 nil)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])
          new-kf (u/seek #(= 0.25 (:position %)) (:keyframes kf-mod))]
      (is (= :linear (:interpolation new-kf))))))


(deftest create-default-keyframes-with-interpolation-test
  (testing "Default keyframes have :interpolation :linear"
    (let [state (make-test-state [sample-effect])
          result (keyframe/handle-toggle-enabled state sample-config [0] true)
          kf-mod (get-in result [:chains :effect-chains [0 0] :items 0 :keyframe-modulator])
          keyframes (:keyframes kf-mod)]
      (is (every? #(= :linear (:interpolation %)) keyframes)))))


(deftest handle-dispatch-set-interpolation-test
  (testing "Dispatches :keyframe/set-interpolation"
    (let [state (make-test-state [sample-effect-with-keyframes])
          event {:event/type :keyframe/set-interpolation
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :keyframe-idx 1
                 :interpolation :exp-grow
                 :state state}
          result (keyframe/handle event)
          kf-mod (get-in result [:state :chains :effect-chains [0 0] :items 0 :keyframe-modulator])
          second-kf (nth (:keyframes kf-mod) 1)]
      (is (= :exp-grow (:interpolation second-kf))))))


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


