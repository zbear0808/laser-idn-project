(ns laser-show.events.zone-groups-handlers-test
  "Tests for zone group handlers.
   
   SIMPLIFIED ARCHITECTURE (v2):
   Tests zone group CRUD operations, selection, and editor dialog.
   Zone groups now reference projectors instead of zones."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [laser-show.events.handlers.zone-groups :as zone-groups]
   [laser-show.state.domains :refer [build-initial-state]]))


;; Test Data Fixtures


(def test-projector-id :proj-1)

(def base-state
  "Base state with default zone groups and a test projector."
  (-> (build-initial-state)
      (assoc-in [:projectors :items test-projector-id]
                {:name "Test Projector"
                 :host "192.168.1.10"
                 :port 7255
                 :enabled? true
                 :zone-groups [:all :left]
                 :tags #{}
                 :corner-pin {:tl-x -1.0 :tl-y 1.0
                              :tr-x 1.0 :tr-y 1.0
                              :bl-x -1.0 :bl-y -1.0
                              :br-x 1.0 :br-y -1.0}})))


;; Selection Tests


(deftest handle-zone-groups-select-test
  (testing "Selecting a zone group sets selected-group and clears projector selection"
    (let [state-with-projector (assoc-in base-state [:projectors :active-projector] test-projector-id)
          event {:event/type :zone-groups/select
                 :group-id :left
                 :state state-with-projector}
          result (zone-groups/handle event)]
      (is (= :left (get-in result [:state :zone-group-ui :selected-group])))
      (is (nil? (get-in result [:state :projectors :active-projector])))
      (is (nil? (get-in result [:state :projectors :active-virtual-projector]))))))


;; Add/Create Tests


(deftest handle-zone-groups-add-test
  (testing "Add opens zone group editor dialog in create mode"
    (let [event {:event/type :zone-groups/add
                 :state base-state}
          result (zone-groups/handle event)]
      (is (true? (get-in result [:state :ui :dialogs :zone-group-editor :open?])))
      ;; FLATTENED: Dialog fields live alongside :open?, not under :data
      (is (false? (get-in result [:state :ui :dialogs :zone-group-editor :editing?])))
      (is (nil? (get-in result [:state :ui :dialogs :zone-group-editor :group-id])))
      (is (= "" (get-in result [:state :ui :dialogs :zone-group-editor :name]))))))


(deftest handle-zone-groups-create-new-test
  (testing "Create new adds zone group and closes dialog"
    (let [state-with-dialog (assoc-in base-state [:ui :dialogs :zone-group-editor :open?] true)
          event {:event/type :zone-groups/create-new
                 :name "My New Group"
                 :description "Test description"
                 :color "#FF5500"
                 :state state-with-dialog}
          result (zone-groups/handle event)
          groups (get-in result [:state :zone-groups])
          new-group (first (filter #(= "My New Group" (:name (second %))) groups))]
      ;; Should have created a new group
      (is (some? new-group))
      (is (= "My New Group" (:name (second new-group))))
      (is (= "Test description" (:description (second new-group))))
      (is (= "#FF5500" (:color (second new-group))))
      ;; Dialog should be closed
      (is (false? (get-in result [:state :ui :dialogs :zone-group-editor :open?])))
      ;; New group should be selected
      (is (= (first new-group) (get-in result [:state :zone-group-ui :selected-group])))
      ;; Project should be dirty
      (is (true? (get-in result [:state :project :dirty?])))))
  
  (testing "Create with defaults works"
    (let [event {:event/type :zone-groups/create-new
                 :name "Minimal Group"
                 :state base-state}
          result (zone-groups/handle event)
          groups (get-in result [:state :zone-groups])
          new-group (first (filter #(= "Minimal Group" (:name (second %))) groups))]
      (is (some? new-group))
      (is (= "" (:description (second new-group))))
      (is (= "#808080" (:color (second new-group)))))))


;; Remove Tests


(deftest handle-zone-groups-remove-test
  (testing "Remove deletes group and clears from projectors and virtual projectors"
    ;; First create a custom group and assign it to a projector
    (let [custom-group-id :custom-group
          vp-id #uuid "22222222-2222-2222-2222-222222222222"
          state-with-custom (-> base-state
                                (assoc-in [:zone-groups custom-group-id]
                                          {:id custom-group-id
                                           :name "Custom"
                                           :description ""
                                           :color "#FF0000"})
                                (assoc-in [:projectors :items test-projector-id :zone-groups]
                                          [:all :left custom-group-id])
                                (assoc-in [:projectors :virtual-projectors vp-id]
                                          {:name "Test VP"
                                           :parent-projector-id test-projector-id
                                           :zone-groups [:all custom-group-id]
                                           :enabled? true})
                                (assoc-in [:zone-group-ui :selected-group] custom-group-id))
          event {:event/type :zone-groups/remove
                 :group-id custom-group-id
                 :state state-with-custom}
          result (zone-groups/handle event)]
      ;; Group should be removed
      (is (nil? (get-in result [:state :zone-groups custom-group-id])))
      ;; Projector should no longer reference the group
      (is (not (some #{custom-group-id}
                     (get-in result [:state :projectors :items test-projector-id :zone-groups]))))
      ;; Virtual projector should no longer reference the group
      (is (not (some #{custom-group-id}
                     (get-in result [:state :projectors :virtual-projectors vp-id :zone-groups]))))
      ;; Selection should be cleared
      (is (nil? (get-in result [:state :zone-group-ui :selected-group])))
      ;; Project should be dirty
      (is (true? (get-in result [:state :project :dirty?]))))))


;; Update Tests


(deftest handle-zone-groups-update-test
  (testing "Update modifies group properties"
    (let [event {:event/type :zone-groups/update
                 :group-id :left
                 :updates {:name "Left Side" :color "#0000FF"}
                 :state base-state}
          result (zone-groups/handle event)
          group (get-in result [:state :zone-groups :left])]
      (is (= "Left Side" (:name group)))
      (is (= "#0000FF" (:color group)))
      (is (true? (get-in result [:state :project :dirty?]))))))


;; Duplicate Tests


(deftest handle-zone-groups-duplicate-test
  (testing "Duplicate creates copy with modified name"
    (let [event {:event/type :zone-groups/duplicate
                 :group-id :left
                 :state base-state}
          result (zone-groups/handle event)
          groups (get-in result [:state :zone-groups])
          ;; Find the duplicate (has " (copy)" suffix)
          duplicate (first (filter #(str/includes? (:name (second %)) "(copy)")
                                   groups))]
      (is (some? duplicate))
      ;; Should be selected
      (is (= (first duplicate) (get-in result [:state :zone-group-ui :selected-group])))
      (is (true? (get-in result [:state :project :dirty?]))))))


;; Edit Dialog Tests


(deftest handle-zone-groups-edit-test
  (testing "Edit opens dialog with existing group data"
    (let [event {:event/type :zone-groups/edit
                 :group-id :left
                 :state base-state}
          result (zone-groups/handle event)
          ;; FLATTENED: Dialog fields live alongside :open?, not under :data
          dialog (get-in result [:state :ui :dialogs :zone-group-editor])]
      (is (true? (:open? dialog)))
      (is (true? (:editing? dialog)))
      (is (= :left (:group-id dialog)))
      (is (= "Left" (:name dialog))))))


(deftest handle-zone-groups-save-edit-test
  (testing "Save edit updates group and closes dialog"
    ;; FLATTENED: Dialog fields live alongside :open?, not under :data
    (let [state-with-dialog (-> base-state
                                (assoc-in [:ui :dialogs :zone-group-editor]
                                          {:open? true
                                           :editing? true
                                           :group-id :left
                                           :name "Left Updated"
                                           :description "New desc"
                                           :color "#00FF00"}))
          event {:event/type :zone-groups/save-edit
                 :group-id :left
                 :name "Left Updated"
                 :description "New desc"
                 :color "#00FF00"
                 :state state-with-dialog}
          result (zone-groups/handle event)
          group (get-in result [:state :zone-groups :left])]
      (is (= "Left Updated" (:name group)))
      (is (= "New desc" (:description group)))
      (is (= "#00FF00" (:color group)))
      (is (false? (get-in result [:state :ui :dialogs :zone-group-editor :open?]))))))


(deftest handle-zone-groups-set-editor-color-test
  (testing "Set editor color updates dialog data"
    ;; FLATTENED: Dialog fields live alongside :open?, not under :data
    (let [state-with-dialog (assoc-in base-state
                                      [:ui :dialogs :zone-group-editor :color]
                                      "#808080")
          event {:event/type :zone-groups/set-editor-color
                 :color "#FF5500"
                 :state state-with-dialog}
          result (zone-groups/handle event)]
      (is (= "#FF5500" (get-in result [:state :ui :dialogs :zone-group-editor :color]))))))


;; Unknown Event Test


(deftest handle-unknown-event-test
  (testing "Unknown event returns empty map"
    (let [event {:event/type :zone-groups/unknown-event
                 :state base-state}
          result (zone-groups/handle event)]
      (is (= {} result)))))
