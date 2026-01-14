(ns laser-show.events.zone-groups-handlers-test
  "Tests for zone group handlers.
   
   Tests zone group CRUD operations, selection, and editor dialog."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [laser-show.events.handlers.zone-groups :as zone-groups]
   [laser-show.state.domains :refer [build-initial-state]]))


;; Test Data Fixtures


(def test-zone-id-1 #uuid "11111111-1111-1111-1111-111111111111")

(def base-state
  "Base state with default zone groups and a test zone."
  (-> (build-initial-state)
      (assoc-in [:zones :items test-zone-id-1]
                {:id test-zone-id-1
                 :name "Test Zone"
                 :projector-id :proj-1
                 :type :default
                 :enabled? true
                 :zone-groups [:all :left]})))


;; Selection Tests


(deftest handle-zone-groups-select-test
  (testing "Selecting a zone group sets selected-group and clears zone selection"
    (let [state-with-zone (assoc-in base-state [:zones :selected-zone] test-zone-id-1)
          event {:event/type :zone-groups/select
                 :group-id :left
                 :state state-with-zone}
          result (zone-groups/handle event)]
      (is (= :left (get-in result [:state :zone-groups :selected-group])))
      (is (nil? (get-in result [:state :zones :selected-zone]))))))


;; Add/Create Tests


(deftest handle-zone-groups-add-test
  (testing "Add opens zone group editor dialog in create mode"
    (let [event {:event/type :zone-groups/add
                 :state base-state}
          result (zone-groups/handle event)]
      (is (true? (get-in result [:state :ui :dialogs :zone-group-editor :open?])))
      (is (false? (get-in result [:state :ui :dialogs :zone-group-editor :data :editing?])))
      (is (nil? (get-in result [:state :ui :dialogs :zone-group-editor :data :group-id])))
      (is (= "" (get-in result [:state :ui :dialogs :zone-group-editor :data :name]))))))


(deftest handle-zone-groups-create-new-test
  (testing "Create new adds zone group and closes dialog"
    (let [state-with-dialog (assoc-in base-state [:ui :dialogs :zone-group-editor :open?] true)
          event {:event/type :zone-groups/create-new
                 :name "My New Group"
                 :description "Test description"
                 :color "#FF5500"
                 :state state-with-dialog}
          result (zone-groups/handle event)
          groups (get-in result [:state :zone-groups :items])
          new-group (first (filter #(= "My New Group" (:name (second %))) groups))]
      ;; Should have created a new group
      (is (some? new-group))
      (is (= "My New Group" (:name (second new-group))))
      (is (= "Test description" (:description (second new-group))))
      (is (= "#FF5500" (:color (second new-group))))
      ;; Dialog should be closed
      (is (false? (get-in result [:state :ui :dialogs :zone-group-editor :open?])))
      ;; New group should be selected
      (is (= (first new-group) (get-in result [:state :zone-groups :selected-group])))
      ;; Project should be dirty
      (is (true? (get-in result [:state :project :dirty?])))))
  
  (testing "Create with defaults works"
    (let [event {:event/type :zone-groups/create-new
                 :name "Minimal Group"
                 :state base-state}
          result (zone-groups/handle event)
          groups (get-in result [:state :zone-groups :items])
          new-group (first (filter #(= "Minimal Group" (:name (second %))) groups))]
      (is (some? new-group))
      (is (= "" (:description (second new-group))))
      (is (= "#808080" (:color (second new-group)))))))


;; Remove Tests


(deftest handle-zone-groups-remove-test
  (testing "Remove deletes group and clears from zones"
    ;; First create a custom group
    (let [custom-group-id :custom-group
          state-with-custom (-> base-state
                                (assoc-in [:zone-groups :items custom-group-id]
                                          {:id custom-group-id
                                           :name "Custom"
                                           :description ""
                                           :color "#FF0000"})
                                (assoc-in [:zones :items test-zone-id-1 :zone-groups]
                                          [:all :left custom-group-id])
                                (assoc-in [:zone-groups :selected-group] custom-group-id))
          event {:event/type :zone-groups/remove
                 :group-id custom-group-id
                 :state state-with-custom}
          result (zone-groups/handle event)]
      ;; Group should be removed
      (is (nil? (get-in result [:state :zone-groups :items custom-group-id])))
      ;; Zone should no longer reference the group
      (is (not (some #{custom-group-id}
                     (get-in result [:state :zones :items test-zone-id-1 :zone-groups]))))
      ;; Selection should be cleared
      (is (nil? (get-in result [:state :zone-groups :selected-group])))
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
          group (get-in result [:state :zone-groups :items :left])]
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
          groups (get-in result [:state :zone-groups :items])
          ;; Find the duplicate (has " (copy)" suffix)
          duplicate (first (filter #(str/includes? (:name (second %)) "(copy)")
                                   groups))]
      (is (some? duplicate))
      ;; Should be selected
      (is (= (first duplicate) (get-in result [:state :zone-groups :selected-group])))
      (is (true? (get-in result [:state :project :dirty?]))))))


;; Edit Dialog Tests


(deftest handle-zone-groups-edit-test
  (testing "Edit opens dialog with existing group data"
    (let [event {:event/type :zone-groups/edit
                 :group-id :left
                 :state base-state}
          result (zone-groups/handle event)
          dialog-data (get-in result [:state :ui :dialogs :zone-group-editor :data])]
      (is (true? (get-in result [:state :ui :dialogs :zone-group-editor :open?])))
      (is (true? (:editing? dialog-data)))
      (is (= :left (:group-id dialog-data)))
      (is (= "Left" (:name dialog-data))))))


(deftest handle-zone-groups-save-edit-test
  (testing "Save edit updates group and closes dialog"
    (let [state-with-dialog (-> base-state
                                (assoc-in [:ui :dialogs :zone-group-editor :open?] true)
                                (assoc-in [:ui :dialogs :zone-group-editor :data]
                                          {:editing? true
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
          group (get-in result [:state :zone-groups :items :left])]
      (is (= "Left Updated" (:name group)))
      (is (= "New desc" (:description group)))
      (is (= "#00FF00" (:color group)))
      (is (false? (get-in result [:state :ui :dialogs :zone-group-editor :open?]))))))


(deftest handle-zone-groups-set-editor-color-test
  (testing "Set editor color updates dialog data"
    (let [state-with-dialog (assoc-in base-state
                                      [:ui :dialogs :zone-group-editor :data :color]
                                      "#808080")
          event {:event/type :zone-groups/set-editor-color
                 :color "#FF5500"
                 :state state-with-dialog}
          result (zone-groups/handle event)]
      (is (= "#FF5500" (get-in result [:state :ui :dialogs :zone-group-editor :data :color]))))))


;; Unknown Event Test


(deftest handle-unknown-event-test
  (testing "Unknown event returns empty map"
    (let [event {:event/type :zone-groups/unknown-event
                 :state base-state}
          result (zone-groups/handle event)]
      (is (= {} result)))))
