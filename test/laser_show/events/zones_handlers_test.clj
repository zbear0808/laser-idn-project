(ns laser-show.events.zones-handlers-test
  "Tests for zone handlers.
   
   Tests zone selection, enable/disable, zone group assignment,
   and settings updates."
  (:require
   [clojure.test :refer [deftest is testing]]
   [laser-show.events.handlers.zones :as zones]
   [laser-show.state.domains :refer [build-initial-state]]))


;; Test Data Fixtures


(def test-zone-id-1 #uuid "11111111-1111-1111-1111-111111111111")
(def test-zone-id-2 #uuid "22222222-2222-2222-2222-222222222222")

(def base-state
  "Base state with sample zones."
  (-> (build-initial-state)
      (assoc-in [:zones :items test-zone-id-1]
                {:id test-zone-id-1
                 :name "Test Zone 1"
                 :projector-id :proj-1
                 :type :default
                 :enabled? true
                 :zone-groups [:all :left]})
      (assoc-in [:zones :items test-zone-id-2]
                {:id test-zone-id-2
                 :name "Test Zone 2"
                 :projector-id :proj-1
                 :type :graphics
                 :enabled? false
                 :zone-groups [:all]})))


;; Selection Tests


(deftest handle-zones-select-zone-test
  (testing "Selecting a zone sets selected-zone and clears zone group selection"
    (let [state-with-group (assoc-in base-state [:zone-groups :selected-group] :left)
          event {:event/type :zones/select-zone
                 :zone-id test-zone-id-1
                 :state state-with-group}
          result (zones/handle event)]
      (is (= test-zone-id-1 (get-in result [:state :zones :selected-zone])))
      (is (nil? (get-in result [:state :zone-groups :selected-group]))))))


;; Enable/Disable Tests


(deftest handle-zones-toggle-enabled-test
  (testing "Toggling enabled zone disables it"
    (let [event {:event/type :zones/toggle-enabled
                 :zone-id test-zone-id-1
                 :state base-state}
          result (zones/handle event)]
      (is (false? (get-in result [:state :zones :items test-zone-id-1 :enabled?])))
      (is (true? (get-in result [:state :project :dirty?])))))
  
  (testing "Toggling disabled zone enables it"
    (let [event {:event/type :zones/toggle-enabled
                 :zone-id test-zone-id-2
                 :state base-state}
          result (zones/handle event)]
      (is (true? (get-in result [:state :zones :items test-zone-id-2 :enabled?]))))))


;; Zone Group Assignment Tests


(deftest handle-zones-toggle-zone-group-test
  (testing "Toggling adds zone to group if not member"
    (let [event {:event/type :zones/toggle-zone-group
                 :zone-id test-zone-id-1
                 :group-id :right
                 :state base-state}
          result (zones/handle event)
          groups (get-in result [:state :zones :items test-zone-id-1 :zone-groups])]
      (is (some #{:right} groups))
      (is (= 3 (count groups)))  ; :all, :left, :right
      (is (true? (get-in result [:state :project :dirty?])))))
  
  (testing "Toggling removes zone from group if member"
    (let [event {:event/type :zones/toggle-zone-group
                 :zone-id test-zone-id-1
                 :group-id :left
                 :state base-state}
          result (zones/handle event)
          groups (get-in result [:state :zones :items test-zone-id-1 :zone-groups])]
      (is (not (some #{:left} groups)))
      (is (= 1 (count groups))))))  ; only :all remains


(deftest handle-zones-set-zone-groups-test
  (testing "Set replaces all zone groups"
    (let [event {:event/type :zones/set-zone-groups
                 :zone-id test-zone-id-1
                 :zone-groups [:center :right]
                 :state base-state}
          result (zones/handle event)
          groups (get-in result [:state :zones :items test-zone-id-1 :zone-groups])]
      (is (= [:center :right] groups))
      (is (true? (get-in result [:state :project :dirty?])))))
  
  (testing "Reorder alias uses same handler"
    (let [event {:event/type :zones/reorder-zone-groups
                 :zone-id test-zone-id-1
                 :zone-groups [:left :all]  ; reversed order
                 :state base-state}
          result (zones/handle event)
          groups (get-in result [:state :zones :items test-zone-id-1 :zone-groups])]
      (is (= [:left :all] groups)))))


;; Settings Tests


(deftest handle-zones-update-settings-test
  (testing "Updates multiple settings at once"
    (let [event {:event/type :zones/update-settings
                 :zone-id test-zone-id-1
                 :updates {:name "New Name" :enabled? false}
                 :state base-state}
          result (zones/handle event)
          zone (get-in result [:state :zones :items test-zone-id-1])]
      (is (= "New Name" (:name zone)))
      (is (false? (:enabled? zone)))
      (is (true? (get-in result [:state :project :dirty?]))))))


(deftest handle-zones-rename-test
  (testing "Renames zone"
    (let [event {:event/type :zones/rename
                 :zone-id test-zone-id-1
                 :name "Renamed Zone"
                 :state base-state}
          result (zones/handle event)]
      (is (= "Renamed Zone" (get-in result [:state :zones :items test-zone-id-1 :name]))))))


;; Unknown Event Test


(deftest handle-unknown-event-test
  (testing "Unknown event returns empty map"
    (let [event {:event/type :zones/unknown-event
                 :state base-state}
          result (zones/handle event)]
      (is (= {} result)))))
