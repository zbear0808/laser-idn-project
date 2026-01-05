(ns laser-show.events.chain-handlers-test
  "Tests for generic chain handler helpers."
  (:require
   [clojure.test :refer [deftest is testing]]
   [laser-show.events.chain-handlers :as ch]))


;; Test Data Fixtures


(defn make-effect
  "Create a test effect with an ID."
  [id-suffix]
  {:id (java.util.UUID/fromString (str "00000000-0000-0000-0000-00000000000" id-suffix))
   :effect-id :scale
   :enabled? true
   :params {:x 1.0 :y 1.0}})

(defn make-group
  "Create a test group with items."
  [id-suffix name items]
  {:type :group
   :id (java.util.UUID/fromString (str "00000000-0000-0000-0000-00000000000" id-suffix))
   :name name
   :enabled? true
   :collapsed? false
   :items (vec items)})

(def effect-1 (make-effect "1"))
(def effect-2 (make-effect "2"))
(def effect-3 (make-effect "3"))
(def effect-4 (make-effect "4"))
(def effect-5 (make-effect "5"))

(def group-a (make-group "a" "Group A" [effect-2 effect-3]))
(def group-b (make-group "b" "Group B" [effect-4]))

;; Sample chain: [effect-1, group-a{effect-2, effect-3}, group-b{effect-4}, effect-5]
(def sample-chain [effect-1 group-a group-b effect-5])

;; Sample state with effects chain
(def sample-state
  {:effects {:cells {[0 0] {:effects sample-chain :active true}}}
   :ui {:dialogs {:effect-chain-editor {:data {:selected-paths #{}
                                                :last-selected-path nil
                                                :dragging-paths nil
                                                :renaming-path nil}}}}
   :project {:dirty? false}})

(def effects-config (ch/effects-chain-config 0 0))


;; Selection Tests


(deftest handle-select-item-test
  (testing "Single item selection"
    (let [result (ch/handle-select-item sample-state effects-config [0] false false)]
      (is (= #{[0]} (get-in result [:ui :dialogs :effect-chain-editor :data :selected-paths])))
      (is (= [0] (get-in result [:ui :dialogs :effect-chain-editor :data :last-selected-path])))))
  
  (testing "Ctrl+click adds to selection"
    (let [state-with-selection (assoc-in sample-state 
                                         [:ui :dialogs :effect-chain-editor :data :selected-paths] 
                                         #{[0]})
          result (ch/handle-select-item state-with-selection effects-config [1] true false)]
      (is (= #{[0] [1]} (get-in result [:ui :dialogs :effect-chain-editor :data :selected-paths])))))
  
  (testing "Ctrl+click toggles selection off"
    (let [state-with-selection (assoc-in sample-state 
                                         [:ui :dialogs :effect-chain-editor :data :selected-paths] 
                                         #{[0] [1]})
          result (ch/handle-select-item state-with-selection effects-config [0] true false)]
      (is (= #{[1]} (get-in result [:ui :dialogs :effect-chain-editor :data :selected-paths])))))
  
  (testing "Shift+click range selection"
    (let [state-with-anchor (-> sample-state
                                (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-paths] #{[0]})
                                (assoc-in [:ui :dialogs :effect-chain-editor :data :last-selected-path] [0]))
          result (ch/handle-select-item state-with-anchor effects-config [3] false true)]
      ;; Should select paths [0], [1], [1 :items 0], [1 :items 1], [2], [2 :items 0], [3]
      (is (contains? (get-in result [:ui :dialogs :effect-chain-editor :data :selected-paths]) [0]))
      (is (contains? (get-in result [:ui :dialogs :effect-chain-editor :data :selected-paths]) [3]))
      ;; Anchor should NOT be updated on shift+click
      (is (= [0] (get-in result [:ui :dialogs :effect-chain-editor :data :last-selected-path]))))))

(deftest handle-select-all-test
  (testing "Select all items including nested"
    (let [result (ch/handle-select-all sample-state effects-config)
          selected (get-in result [:ui :dialogs :effect-chain-editor :data :selected-paths])]
      ;; Should have paths for: effect-1[0], group-a[1], effect-2[1 :items 0], effect-3[1 :items 1], 
      ;;                        group-b[2], effect-4[2 :items 0], effect-5[3]
      (is (= 7 (count selected)))
      (is (contains? selected [0]))
      (is (contains? selected [1]))
      (is (contains? selected [1 :items 0]))
      (is (contains? selected [1 :items 1]))
      (is (contains? selected [2]))
      (is (contains? selected [2 :items 0]))
      (is (contains? selected [3])))))

(deftest handle-clear-selection-test
  (testing "Clear selection"
    (let [state-with-selection (-> sample-state
                                   (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-paths] #{[0] [1]})
                                   (assoc-in [:ui :dialogs :effect-chain-editor :data :last-selected-path] [0]))
          result (ch/handle-clear-selection state-with-selection effects-config)]
      (is (= #{} (get-in result [:ui :dialogs :effect-chain-editor :data :selected-paths])))
      (is (nil? (get-in result [:ui :dialogs :effect-chain-editor :data :last-selected-path]))))))


;; Copy/Paste Tests


(deftest handle-copy-selected-test
  (testing "Copy single item"
    (let [state-with-selection (assoc-in sample-state 
                                         [:ui :dialogs :effect-chain-editor :data :selected-paths] 
                                         #{[0]})
          {:keys [state items]} (ch/handle-copy-selected state-with-selection effects-config)]
      (is (= 1 (count items)))
      (is (= :scale (:effect-id (first items))))))
  
  (testing "Copy multiple items"
    (let [state-with-selection (assoc-in sample-state 
                                         [:ui :dialogs :effect-chain-editor :data :selected-paths] 
                                         #{[0] [3]})
          {:keys [state items]} (ch/handle-copy-selected state-with-selection effects-config)]
      (is (= 2 (count items)))))
  
  (testing "Copy group includes children"
    (let [state-with-selection (assoc-in sample-state 
                                         [:ui :dialogs :effect-chain-editor :data :selected-paths] 
                                         #{[1]})
          {:keys [state items]} (ch/handle-copy-selected state-with-selection effects-config)]
      (is (= 1 (count items)))
      (is (= :group (:type (first items))))
      (is (= 2 (count (:items (first items))))))))

(deftest handle-paste-items-test
  (testing "Paste regenerates IDs"
    (let [items-to-paste [(make-effect "6")]
          result (ch/handle-paste-items sample-state effects-config items-to-paste)
          pasted (last (get-in result [:effects :cells [0 0] :effects]))]
      (is (not= (:id (first items-to-paste)) (:id pasted)))))
  
  (testing "Paste inserts at end when no selection"
    (let [items-to-paste [(make-effect "7")]
          result (ch/handle-paste-items sample-state effects-config items-to-paste)
          effects (get-in result [:effects :cells [0 0] :effects])]
      (is (= 5 (count effects)))))
  
  (testing "Paste marks project dirty"
    (let [items-to-paste [(make-effect "8")]
          result (ch/handle-paste-items sample-state effects-config items-to-paste)]
      (is (true? (get-in result [:project :dirty?])))))
  
  (testing "Paste selects pasted items"
    (let [items-to-paste [(make-effect "9") (make-effect "c")]
          result (ch/handle-paste-items sample-state effects-config items-to-paste)
          selected (get-in result [:ui :dialogs :effect-chain-editor :data :selected-paths])]
      (is (= 2 (count selected)))
      (is (contains? selected [4]))
      (is (contains? selected [5])))))


;; Delete Tests


(deftest handle-delete-selected-test
  (testing "Delete single item"
    (let [state-with-selection (assoc-in sample-state 
                                         [:ui :dialogs :effect-chain-editor :data :selected-paths] 
                                         #{[0]})
          result (ch/handle-delete-selected state-with-selection effects-config)
          effects (get-in result [:effects :cells [0 0] :effects])]
      (is (= 3 (count effects)))
      (is (= :group (:type (first effects))))))
  
  (testing "Delete group removes children"
    (let [state-with-selection (assoc-in sample-state 
                                         [:ui :dialogs :effect-chain-editor :data :selected-paths] 
                                         #{[1]})
          result (ch/handle-delete-selected state-with-selection effects-config)
          effects (get-in result [:effects :cells [0 0] :effects])]
      (is (= 3 (count effects)))
      ;; group-a (containing effect-2, effect-3) is removed
      (is (not (some #(= "Group A" (:name %)) effects)))))
  
  (testing "Delete clears selection"
    (let [state-with-selection (assoc-in sample-state 
                                         [:ui :dialogs :effect-chain-editor :data :selected-paths] 
                                         #{[0]})
          result (ch/handle-delete-selected state-with-selection effects-config)]
      (is (= #{} (get-in result [:ui :dialogs :effect-chain-editor :data :selected-paths])))))
  
  (testing "Delete marks project dirty"
    (let [state-with-selection (assoc-in sample-state 
                                         [:ui :dialogs :effect-chain-editor :data :selected-paths] 
                                         #{[0]})
          result (ch/handle-delete-selected state-with-selection effects-config)]
      (is (true? (get-in result [:project :dirty?]))))))


;; Group Tests


(deftest handle-create-empty-group-test
  (testing "Creates empty group at end"
    (let [result (ch/handle-create-empty-group sample-state effects-config "Test Group")
          effects (get-in result [:effects :cells [0 0] :effects])
          new-group (last effects)]
      (is (= 5 (count effects)))
      (is (= :group (:type new-group)))
      (is (= "Test Group" (:name new-group)))
      (is (empty? (:items new-group)))))
  
  (testing "Default name is 'New Group'"
    (let [result (ch/handle-create-empty-group sample-state effects-config)
          new-group (last (get-in result [:effects :cells [0 0] :effects]))]
      (is (= "New Group" (:name new-group))))))

(deftest handle-group-selected-test
  (testing "Group multiple items"
    (let [state-with-selection (assoc-in sample-state 
                                         [:ui :dialogs :effect-chain-editor :data :selected-paths] 
                                         #{[0] [3]})
          result (ch/handle-group-selected state-with-selection effects-config "My Group")
          effects (get-in result [:effects :cells [0 0] :effects])]
      ;; Should have: new-group{effect-1, effect-5}, group-a, group-b
      ;; Total top-level items: 3
      (is (= 3 (count effects)))
      (is (= "My Group" (:name (first effects))))
      (is (= 2 (count (:items (first effects)))))))
  
  (testing "Group selects the new group"
    (let [state-with-selection (assoc-in sample-state 
                                         [:ui :dialogs :effect-chain-editor :data :selected-paths] 
                                         #{[0] [3]})
          result (ch/handle-group-selected state-with-selection effects-config "My Group")
          selected (get-in result [:ui :dialogs :effect-chain-editor :data :selected-paths])]
      (is (= 1 (count selected)))
      (is (contains? selected [0])))))

(deftest handle-ungroup-test
  (testing "Ungroup inserts children at group position"
    (let [state-with-selection (assoc-in sample-state 
                                         [:ui :dialogs :effect-chain-editor :data :selected-paths] 
                                         #{[1]})
          result (ch/handle-ungroup state-with-selection effects-config [1])
          effects (get-in result [:effects :cells [0 0] :effects])]
      ;; Should have: effect-1, effect-2, effect-3, group-b, effect-5
      (is (= 5 (count effects)))
      (is (= (:id effect-1) (:id (nth effects 0))))
      (is (= (:id effect-2) (:id (nth effects 1))))
      (is (= (:id effect-3) (:id (nth effects 2))))))
  
  (testing "Ungroup clears selection"
    (let [result (ch/handle-ungroup sample-state effects-config [1])]
      (is (= #{} (get-in result [:ui :dialogs :effect-chain-editor :data :selected-paths]))))))

(deftest handle-toggle-collapse-test
  (testing "Toggle collapse on group"
    (let [result (ch/handle-toggle-collapse sample-state effects-config [1])
          group (get-in result [:effects :cells [0 0] :effects 1])]
      (is (true? (:collapsed? group)))))
  
  (testing "Toggle collapse back"
    (let [state-collapsed (assoc-in sample-state [:effects :cells [0 0] :effects 1 :collapsed?] true)
          result (ch/handle-toggle-collapse state-collapsed effects-config [1])
          group (get-in result [:effects :cells [0 0] :effects 1])]
      (is (false? (:collapsed? group))))))


;; Rename Tests


(deftest handle-start-rename-test
  (testing "Sets renaming path"
    (let [result (ch/handle-start-rename sample-state effects-config [1])]
      (is (= [1] (get-in result [:ui :dialogs :effect-chain-editor :data :renaming-path]))))))

(deftest handle-cancel-rename-test
  (testing "Clears renaming path"
    (let [state-renaming (assoc-in sample-state 
                                   [:ui :dialogs :effect-chain-editor :data :renaming-path] 
                                   [1])
          result (ch/handle-cancel-rename state-renaming effects-config)]
      (is (nil? (get-in result [:ui :dialogs :effect-chain-editor :data :renaming-path]))))))

(deftest handle-rename-item-test
  (testing "Renames group"
    (let [result (ch/handle-rename-item sample-state effects-config [1] "Renamed Group")
          group (get-in result [:effects :cells [0 0] :effects 1])]
      (is (= "Renamed Group" (:name group)))))
  
  (testing "Clears renaming path after rename"
    (let [state-renaming (assoc-in sample-state 
                                   [:ui :dialogs :effect-chain-editor :data :renaming-path] 
                                   [1])
          result (ch/handle-rename-item state-renaming effects-config [1] "Renamed Group")]
      (is (nil? (get-in result [:ui :dialogs :effect-chain-editor :data :renaming-path])))))
  
  (testing "Marks project dirty"
    (let [result (ch/handle-rename-item sample-state effects-config [1] "Renamed Group")]
      (is (true? (get-in result [:project :dirty?]))))))


;; Drag-and-Drop Tests


(deftest handle-start-drag-test
  (testing "Drag selected item drags all selected"
    (let [state-with-selection (assoc-in sample-state 
                                         [:ui :dialogs :effect-chain-editor :data :selected-paths] 
                                         #{[0] [3]})
          result (ch/handle-start-drag state-with-selection effects-config [0])]
      (is (= #{[0] [3]} (get-in result [:ui :dialogs :effect-chain-editor :data :dragging-paths])))))
  
  (testing "Drag unselected item selects and drags just that item"
    (let [state-with-selection (assoc-in sample-state 
                                         [:ui :dialogs :effect-chain-editor :data :selected-paths] 
                                         #{[0]})
          result (ch/handle-start-drag state-with-selection effects-config [3])]
      (is (= #{[3]} (get-in result [:ui :dialogs :effect-chain-editor :data :dragging-paths])))
      (is (= #{[3]} (get-in result [:ui :dialogs :effect-chain-editor :data :selected-paths]))))))

(deftest handle-move-items-test
  (testing "Move item to new position"
    (let [state-dragging (-> sample-state
                             (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-paths] #{[0]})
                             (assoc-in [:ui :dialogs :effect-chain-editor :data :dragging-paths] #{[0]}))
          result (ch/handle-move-items state-dragging effects-config (:id effect-5) :before)
          effects (get-in result [:effects :cells [0 0] :effects])]
      ;; effect-1 should be moved before effect-5
      ;; Order should be: group-a, group-b, effect-1, effect-5
      (is (= 4 (count effects)))
      (is (= (:id effect-1) (:id (nth effects 2))))))
  
  (testing "Move clears dragging state"
    (let [state-dragging (-> sample-state
                             (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-paths] #{[0]})
                             (assoc-in [:ui :dialogs :effect-chain-editor :data :dragging-paths] #{[0]}))
          result (ch/handle-move-items state-dragging effects-config (:id effect-5) :before)]
      (is (nil? (get-in result [:ui :dialogs :effect-chain-editor :data :dragging-paths])))))
  
  (testing "Move into group"
    (let [state-dragging (-> sample-state
                             (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-paths] #{[0]})
                             (assoc-in [:ui :dialogs :effect-chain-editor :data :dragging-paths] #{[0]}))
          result (ch/handle-move-items state-dragging effects-config (:id group-b) :into)
          effects (get-in result [:effects :cells [0 0] :effects])
          group-b-new (nth effects 1)]
      ;; effect-1 should be inside group-b now
      (is (= 3 (count effects)))  ;; group-a, group-b, effect-5
      (is (= 2 (count (:items group-b-new)))))))

(deftest handle-clear-drag-state-test
  (testing "Clears dragging paths"
    (let [state-dragging (assoc-in sample-state 
                                   [:ui :dialogs :effect-chain-editor :data :dragging-paths] 
                                   #{[0] [1]})
          result (ch/handle-clear-drag-state state-dragging effects-config)]
      (is (nil? (get-in result [:ui :dialogs :effect-chain-editor :data :dragging-paths]))))))


;; Enabled State Tests


(deftest handle-set-item-enabled-test
  (testing "Set item enabled to false"
    (let [result (ch/handle-set-item-enabled sample-state effects-config [0] false)
          effect (get-in result [:effects :cells [0 0] :effects 0])]
      (is (false? (:enabled? effect)))))
  
  (testing "Set nested item enabled"
    (let [result (ch/handle-set-item-enabled sample-state effects-config [1 :items 0] false)
          effect (get-in result [:effects :cells [0 0] :effects 1 :items 0])]
      (is (false? (:enabled? effect)))))
  
  (testing "Marks project dirty"
    (let [result (ch/handle-set-item-enabled sample-state effects-config [0] false)]
      (is (true? (get-in result [:project :dirty?]))))))


;; Config Tests


(deftest effects-chain-config-test
  (testing "Creates correct config for effects"
    (let [config (ch/effects-chain-config 1 2)]
      (is (= [:effects :cells [1 2] :effects] (:items-path config)))
      (is (= [:ui :dialogs :effect-chain-editor :data] (:ui-path config)))
      (is (= :effects (:domain config))))))

(deftest projector-chain-config-test
  (testing "Creates correct config for projectors"
    (let [config (ch/projector-chain-config :proj-1)]
      (is (= [:projectors :items :proj-1 :effects] (:items-path config)))
      (is (= [:ui :projector-effect-ui-state :proj-1] (:ui-path config)))
      (is (= :projectors (:domain config))))))

(deftest cue-chain-config-test
  (testing "Creates correct config for cue chains"
    (let [config (ch/cue-chain-config 0 1)]
      (is (= [:grid :cells [0 1] :cue-chain :items] (:items-path config)))
      (is (= [:cue-chain-editor] (:ui-path config)))
      (is (= :cue-chain (:domain config))))))
