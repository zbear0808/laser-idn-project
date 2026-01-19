(ns laser-show.events.projector-handlers-test
  "Tests for complex projector handler logic.
   
   SIMPLIFIED ARCHITECTURE (v2):
   Tests projector operations including:
   - Adding projectors from discovered devices
   - Corner-pin configuration (now directly on projectors)
   - Zone group assignments
   - Virtual projector management
   - Path-based selection with Ctrl/Shift modifiers"
  (:require
   [clojure.test :refer [deftest is testing]]
   [laser-show.events.handlers.projector :as projectors]
   [laser-show.state.domains :refer [build-initial-state]]))


;; Test Data Fixtures


(def sample-discovered-device
  {:address "192.168.1.100"
   :host-name "LaserDAC-1"
   :port 7255
   :unit-id "ABC123"
   :services [{:service-id 0
               :name "Output A"
               :flags {:default-service true}}
              {:service-id 1
               :name "Output B"
               :flags {}}
              {:service-id 2
               :name "Output C"
               :flags {}}]})

(def base-state
  "Base state using actual app initial state structure."
  (build-initial-state))

(def sample-state
  "Empty projector state for testing device addition."
  base-state)


;; Multi-Service Addition Tests


(deftest handle-projectors-add-all-services-test
  (testing "Adds all services from a multi-output device"
    (let [event {:event/type :projectors/add-all-services
                 :device sample-discovered-device
                 :state sample-state}
          result (projectors/handle event)
          projectors (get-in result [:state :projectors :items])
          effects (get-in result [:state :chains :projector-effects])]
      ;; Should create 3 projectors (one per service)
      (is (= 3 (count projectors)))
      (is (= 3 (count effects)))
      
      ;; Each projector should have correct service-id
      (let [projector-ids (keys projectors)
            service-ids (set (map #(get-in projectors [% :service-id]) projector-ids))]
        (is (= #{0 1 2} service-ids)))
      
      ;; Each projector should have zone-groups and corner-pin directly
      (doseq [[_proj-id proj-data] projectors]
        (is (vector? (:zone-groups proj-data)))
        (is (contains? proj-data :corner-pin))
        (is (set? (:tags proj-data))))
      
      ;; Each projector should have default effects chain (color calibration + corner-pin)
      (doseq [[_projector-id effect-data] effects]
        (is (vector? (:items effect-data)))
        ;; Should have RGB curves and corner-pin effects
        (is (= 2 (count (:items effect-data))))
        (is (= :rgb-curves (get-in effect-data [:items 0 :effect-id])))
        (is (= :corner-pin (get-in effect-data [:items 1 :effect-id]))))
      
      ;; First projector should be selected
      (is (some? (get-in result [:state :projectors :active-projector])))
      
      ;; Project should be marked dirty
      (is (true? (get-in result [:state :project :dirty?])))))
  
  (testing "Each projector gets unique name from service"
    (let [event {:event/type :projectors/add-all-services
                 :device sample-discovered-device
                 :state sample-state}
          result (projectors/handle event)
          projectors (get-in result [:state :projectors :items])
          names (set (map #(:name (second %)) projectors))]
      ;; Names should include service names
      (is (contains? names "Output A"))
      (is (contains? names "Output B"))
      (is (contains? names "Output C")))))


(deftest handle-projectors-add-device-test
  (testing "Adds default service when device has multiple services"
    (let [event {:event/type :projectors/add-device
                 :device sample-discovered-device
                 :state sample-state}
          result (projectors/handle event)
          projectors (get-in result [:state :projectors :items])]
      ;; Should add only 1 projector (the default service)
      (is (= 1 (count projectors)))
      
      ;; Should use default service (service-id 0)
      (let [projector (first (vals projectors))]
        (is (= 0 (:service-id projector)))
        (is (= "Output A" (:service-name projector)))
        ;; New architecture: projector has zone-groups and corner-pin directly
        (is (vector? (:zone-groups projector)))
        (is (contains? projector :corner-pin)))))
  
  (testing "Uses first service if no default marked"
    (let [device-no-default (update sample-discovered-device :services
                                   (fn [svcs]
                                     (mapv #(assoc % :flags {}) svcs)))
          event {:event/type :projectors/add-device
                 :device device-no-default
                 :state sample-state}
          result (projectors/handle event)
          projector (first (vals (get-in result [:state :projectors :items])))]
      ;; Should use first service
      (is (= 0 (:service-id projector))))))


(deftest handle-projectors-add-service-test
  (testing "Adds specific service from multi-output device"
    (let [service-b (second (:services sample-discovered-device))
          event {:event/type :projectors/add-service
                 :device sample-discovered-device
                 :service service-b
                 :state sample-state}
          result (projectors/handle event)
          projector (first (vals (get-in result [:state :projectors :items])))]
      ;; Should add service B specifically
      (is (= 1 (:service-id projector)))
      (is (= "Output B" (:service-name projector))))))


;; Corner-Pin Tests (new in v2 architecture)


(def test-projector-id :proj-1)

(def state-with-projector
  (-> sample-state
      (assoc-in [:projectors :items test-projector-id]
                {:name "Test Projector"
                 :host "192.168.1.100"
                 :enabled? true
                 :zone-groups [:all]
                 :tags #{}
                 :corner-pin {:tl-x -1.0 :tl-y 1.0
                              :tr-x 1.0 :tr-y 1.0
                              :bl-x -1.0 :bl-y -1.0
                              :br-x 1.0 :br-y -1.0}})
      (assoc-in [:chains :projector-effects test-projector-id :items]
                [{:id (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
                  :effect-id :rgb-curves
                  :enabled? true}])))


(deftest handle-projectors-update-corner-pin-test
  (testing "Updates corner-pin coordinates on projector"
    (let [event {:event/type :projectors/update-corner-pin
                 :projector-id test-projector-id
                 :point-id :tl
                 :x -0.8
                 :y 0.9
                 :state state-with-projector}
          result (projectors/handle event)
          corner-pin (get-in result [:state :projectors :items test-projector-id :corner-pin])]
      (is (= -0.8 (:tl-x corner-pin)))
      (is (= 0.9 (:tl-y corner-pin)))
      ;; Other corners unchanged
      (is (= 1.0 (:tr-x corner-pin)))
      (is (true? (get-in result [:state :project :dirty?]))))))


(deftest handle-projectors-reset-corner-pin-test
  (testing "Resets corner-pin to default rectangle"
    (let [modified-state (assoc-in state-with-projector
                                   [:projectors :items test-projector-id :corner-pin]
                                   {:tl-x -0.5 :tl-y 0.5
                                    :tr-x 0.8 :tr-y 0.9
                                    :bl-x -0.3 :bl-y -0.7
                                    :br-x 0.6 :br-y -0.8})
          event {:event/type :projectors/reset-corner-pin
                 :projector-id test-projector-id
                 :state modified-state}
          result (projectors/handle event)
          corner-pin (get-in result [:state :projectors :items test-projector-id :corner-pin])]
      (is (= -1.0 (:tl-x corner-pin)))
      (is (= 1.0 (:tl-y corner-pin)))
      (is (= 1.0 (:tr-x corner-pin)))
      (is (= 1.0 (:tr-y corner-pin)))
      (is (= -1.0 (:bl-x corner-pin)))
      (is (= -1.0 (:bl-y corner-pin)))
      (is (= 1.0 (:br-x corner-pin)))
      (is (= -1.0 (:br-y corner-pin))))))


;; Zone Group Assignment Tests


(deftest handle-projectors-toggle-zone-group-test
  (testing "Adds zone group when not present"
    (let [event {:event/type :projectors/toggle-zone-group
                 :projector-id test-projector-id
                 :zone-group-id :left
                 :state state-with-projector}
          result (projectors/handle event)
          zone-groups (get-in result [:state :projectors :items test-projector-id :zone-groups])]
      (is (some #{:left} zone-groups))
      (is (some #{:all} zone-groups))))
  
  (testing "Removes zone group when present"
    (let [state-with-groups (assoc-in state-with-projector
                                      [:projectors :items test-projector-id :zone-groups]
                                      [:all :left :right])
          event {:event/type :projectors/toggle-zone-group
                 :projector-id test-projector-id
                 :zone-group-id :left
                 :state state-with-groups}
          result (projectors/handle event)
          zone-groups (get-in result [:state :projectors :items test-projector-id :zone-groups])]
      (is (not (some #{:left} zone-groups)))
      (is (some #{:all} zone-groups))
      (is (some #{:right} zone-groups)))))


(deftest handle-projectors-set-zone-groups-test
  (testing "Sets zone groups directly"
    (let [event {:event/type :projectors/set-zone-groups
                 :projector-id test-projector-id
                 :zone-groups [:left :right]
                 :state state-with-projector}
          result (projectors/handle event)
          zone-groups (get-in result [:state :projectors :items test-projector-id :zone-groups])]
      (is (= [:left :right] zone-groups)))))


;; Tag Tests


(deftest handle-projectors-add-tag-test
  (testing "Adding graphics tag auto-assigns to graphics zone group"
    (let [state-with-graphics-group (assoc-in state-with-projector
                                              [:zone-groups :items :graphics]
                                              {:id :graphics :name "Graphics"})
          event {:event/type :projectors/add-tag
                 :projector-id test-projector-id
                 :tag :graphics
                 :state state-with-graphics-group}
          result (projectors/handle event)
          tags (get-in result [:state :projectors :items test-projector-id :tags])
          zone-groups (get-in result [:state :projectors :items test-projector-id :zone-groups])]
      (is (contains? tags :graphics))
      (is (some #{:graphics} zone-groups)))))


(deftest handle-projectors-remove-tag-test
  (testing "Removes tag from projector"
    (let [state-with-tag (assoc-in state-with-projector
                                   [:projectors :items test-projector-id :tags]
                                   #{:graphics})
          event {:event/type :projectors/remove-tag
                 :projector-id test-projector-id
                 :tag :graphics
                 :state state-with-tag}
          result (projectors/handle event)
          tags (get-in result [:state :projectors :items test-projector-id :tags])]
      (is (not (contains? tags :graphics))))))


;; Virtual Projector Tests


(def vp-id #uuid "11111111-1111-1111-1111-111111111111")


(deftest handle-projectors-add-virtual-projector-test
  (testing "Creates virtual projector with custom corner-pin inheriting parent's color curves"
    (let [event {:event/type :projectors/add-virtual-projector
                 :parent-projector-id test-projector-id
                 :name "Graphics Zone"
                 :state state-with-projector}
          result (projectors/handle event)
          vps (get-in result [:state :projectors :virtual-projectors])
          new-vp (first (vals vps))]
      (is (= 1 (count vps)))
      (is (= "Graphics Zone" (:name new-vp)))
      (is (= test-projector-id (:parent-projector-id new-vp)))
      (is (contains? new-vp :corner-pin))
      (is (vector? (:zone-groups new-vp))))))


(deftest handle-vp-update-corner-pin-test
  (testing "Updates virtual projector corner-pin"
    (let [state-with-vp (assoc-in state-with-projector
                                  [:projectors :virtual-projectors vp-id]
                                  {:name "Test VP"
                                   :parent-projector-id test-projector-id
                                   :zone-groups [:all]
                                   :enabled? true
                                   :corner-pin {:tl-x -1.0 :tl-y 1.0
                                                :tr-x 1.0 :tr-y 1.0
                                                :bl-x -1.0 :bl-y -1.0
                                                :br-x 1.0 :br-y -1.0}})
          event {:event/type :projectors/vp-update-corner-pin
                 :vp-id vp-id
                 :point-id :br
                 :x 0.7
                 :y -0.8
                 :state state-with-vp}
          result (projectors/handle event)
          corner-pin (get-in result [:state :projectors :virtual-projectors vp-id :corner-pin])]
      (is (= 0.7 (:br-x corner-pin)))
      (is (= -0.8 (:br-y corner-pin))))))


(deftest handle-vp-toggle-zone-group-test
  (testing "Toggles zone group on virtual projector"
    (let [state-with-vp (assoc-in state-with-projector
                                  [:projectors :virtual-projectors vp-id]
                                  {:name "Test VP"
                                   :parent-projector-id test-projector-id
                                   :zone-groups [:all]
                                   :enabled? true
                                   :corner-pin {:tl-x -1.0 :tl-y 1.0
                                                :tr-x 1.0 :tr-y 1.0
                                                :bl-x -1.0 :bl-y -1.0
                                                :br-x 1.0 :br-y -1.0}})
          event {:event/type :projectors/vp-toggle-zone-group
                 :vp-id vp-id
                 :zone-group-id :graphics
                 :state state-with-vp}
          result (projectors/handle event)
          zone-groups (get-in result [:state :projectors :virtual-projectors vp-id :zone-groups])]
      (is (some #{:graphics} zone-groups)))))


(deftest handle-projectors-remove-virtual-projector-test
  (testing "Removes virtual projector"
    (let [state-with-vp (assoc-in state-with-projector
                                  [:projectors :virtual-projectors vp-id]
                                  {:name "Test VP"
                                   :parent-projector-id test-projector-id})
          event {:event/type :projectors/remove-virtual-projector
                 :vp-id vp-id
                 :state state-with-vp}
          result (projectors/handle event)]
      (is (empty? (get-in result [:state :projectors :virtual-projectors]))))))


;; Path-Based Selection Tests


(def sample-effects
  [{:id (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
    :effect-id :rgb-curves
    :enabled? true}
   {:id (java.util.UUID/fromString "00000000-0000-0000-0000-000000000002")
    :effect-id :intensity
    :enabled? true}
   {:id (java.util.UUID/fromString "00000000-0000-0000-0000-000000000003")
    :effect-id :color-wash
    :enabled? true}])

(def state-with-effects
  (assoc-in state-with-projector [:chains :projector-effects test-projector-id :items] sample-effects))


(deftest handle-projectors-select-effect-at-path-test
  (testing "Single click replaces selection"
    (let [event {:event/type :projectors/select-effect
                 :projector-id test-projector-id
                 :path [1]
                 :ctrl? false
                 :shift? false
                 :state state-with-effects}
          result (projectors/handle event)
          selected (get-in result [:state :ui :projector-effect-ui-state test-projector-id :selected-paths])]
      (is (= #{[1]} selected))))
  
  (testing "Ctrl+click toggles selection on"
    (let [state-with-selection (assoc-in state-with-effects
                                         [:ui :projector-effect-ui-state test-projector-id :selected-paths]
                                         #{[0]})
          event {:event/type :projectors/select-effect
                 :projector-id test-projector-id
                 :path [1]
                 :ctrl? true
                 :shift? false
                 :state state-with-selection}
          result (projectors/handle event)
          selected (get-in result [:state :ui :projector-effect-ui-state test-projector-id :selected-paths])]
      ;; Should have both [0] and [1]
      (is (= #{[0] [1]} selected))))
  
  (testing "Ctrl+click toggles selection off"
    (let [state-with-selection (assoc-in state-with-effects
                                         [:ui :projector-effect-ui-state test-projector-id :selected-paths]
                                         #{[0] [1]})
          event {:event/type :projectors/select-effect
                 :projector-id test-projector-id
                 :path [1]
                 :ctrl? true
                 :shift? false
                 :state state-with-selection}
          result (projectors/handle event)
          selected (get-in result [:state :ui :projector-effect-ui-state test-projector-id :selected-paths])]
      ;; Should have only [0] (toggled [1] off)
      (is (= #{[0]} selected))))
  
  (testing "Shift+click range selects between anchor and target"
    (let [state-with-anchor (-> state-with-effects
                                (assoc-in [:ui :projector-effect-ui-state test-projector-id :selected-paths] #{[0]})
                                (assoc-in [:ui :projector-effect-ui-state test-projector-id :last-selected-path] [0]))
          event {:event/type :projectors/select-effect
                 :projector-id test-projector-id
                 :path [2]
                 :ctrl? false
                 :shift? true
                 :state state-with-anchor}
          result (projectors/handle event)
          selected (get-in result [:state :ui :projector-effect-ui-state test-projector-id :selected-paths])]
      ;; Should select [0], [1], [2]
      (is (= #{[0] [1] [2]} selected))))
  
  (testing "Shift+click does not update anchor"
    (let [state-with-anchor (-> state-with-effects
                                (assoc-in [:ui :projector-effect-ui-state test-projector-id :selected-paths] #{[0]})
                                (assoc-in [:ui :projector-effect-ui-state test-projector-id :last-selected-path] [0]))
          event {:event/type :projectors/select-effect
                 :projector-id test-projector-id
                 :path [2]
                 :ctrl? false
                 :shift? true
                 :state state-with-anchor}
          result (projectors/handle event)
          anchor (get-in result [:state :ui :projector-effect-ui-state test-projector-id :last-selected-path])]
      ;; Anchor should still be [0]
      (is (= [0] anchor)))))


(deftest handle-projectors-remove-projector-test
  (testing "Removing active projector selects another"
    (let [state-multi-proj (-> state-with-projector
                               (assoc-in [:projectors :items :proj-2] {:name "Projector 2"
                                                                        :zone-groups [:all]
                                                                        :tags #{}})
                               (assoc-in [:projectors :active-projector] test-projector-id))
          event {:event/type :projectors/remove-projector
                 :projector-id test-projector-id
                 :state state-multi-proj}
          result (projectors/handle event)
          items (get-in result [:state :projectors :items])
          active (get-in result [:state :projectors :active-projector])]
      ;; proj-1 should be removed
      (is (not (contains? items test-projector-id)))
      ;; Should auto-select proj-2
      (is (= :proj-2 active))))
  
  (testing "Removing non-active projector keeps active unchanged"
    (let [state-multi-proj (-> state-with-projector
                               (assoc-in [:projectors :items :proj-2] {:name "Projector 2"
                                                                        :zone-groups [:all]
                                                                        :tags #{}})
                               (assoc-in [:projectors :active-projector] test-projector-id))
          event {:event/type :projectors/remove-projector
                 :projector-id :proj-2
                 :state state-multi-proj}
          result (projectors/handle event)
          active (get-in result [:state :projectors :active-projector])]
      ;; Active should still be proj-1
      (is (= test-projector-id active))))
  
  (testing "Removing projector also removes its virtual projectors"
    (let [state-with-vp (-> state-with-projector
                            (assoc-in [:projectors :virtual-projectors vp-id]
                                      {:name "Test VP"
                                       :parent-projector-id test-projector-id
                                       :zone-groups [:all]}))
          event {:event/type :projectors/remove-projector
                 :projector-id test-projector-id
                 :state state-with-vp}
          result (projectors/handle event)
          vps (get-in result [:state :projectors :virtual-projectors])]
      ;; Virtual projector should be removed since parent is gone
      (is (nil? (get vps vp-id))))))
