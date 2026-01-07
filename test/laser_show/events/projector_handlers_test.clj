(ns laser-show.events.projector-handlers-test
  "Tests for complex projector handler logic.
   
   Only tests non-trivial handlers with complex logic:
   - Adding multiple services from discovered devices
   - Path-based selection with Ctrl/Shift modifiers"
  (:require
   [clojure.test :refer [deftest is testing]]
   [laser-show.events.handlers.projector :as projectors]))


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

(def sample-state
  {:projectors {:items {}
                :active-projector nil
                :broadcast-address "255.255.255.255"}
   :chains {:projector-effects {}}
   :ui {:projector-effect-ui-state {}}
   :project {:dirty? false}})


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
      
      ;; Each projector should have default effects chain
      (doseq [[projector-id effect-data] effects]
        (is (vector? (:items effect-data)))
        ;; Should have RGB curves and corner pin
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
        (is (= "Output A" (:service-name projector))))))
  
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


;; Path-Based Selection Tests


(def sample-effects
  [{:id (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
    :effect-id :rgb-curves
    :enabled? true}
   {:id (java.util.UUID/fromString "00000000-0000-0000-0000-000000000002")
    :effect-id :corner-pin
    :enabled? true}
   {:id (java.util.UUID/fromString "00000000-0000-0000-0000-000000000003")
    :effect-id :intensity
    :enabled? true}])

(def state-with-projector
  (-> sample-state
      (assoc-in [:projectors :items :proj-1] {:name "Test Projector"
                                               :host "192.168.1.100"
                                               :enabled? true})
      (assoc-in [:chains :projector-effects :proj-1 :items] sample-effects)))


(deftest handle-projectors-select-effect-at-path-test
  (testing "Single click replaces selection"
    (let [event {:event/type :projectors/select-effect
                 :projector-id :proj-1
                 :path [1]
                 :ctrl? false
                 :shift? false
                 :state state-with-projector}
          result (projectors/handle event)
          selected (get-in result [:state :ui :projector-effect-ui-state :proj-1 :selected-paths])]
      (is (= #{[1]} selected))))
  
  (testing "Ctrl+click toggles selection on"
    (let [state-with-selection (assoc-in state-with-projector
                                         [:ui :projector-effect-ui-state :proj-1 :selected-paths]
                                         #{[0]})
          event {:event/type :projectors/select-effect
                 :projector-id :proj-1
                 :path [1]
                 :ctrl? true
                 :shift? false
                 :state state-with-selection}
          result (projectors/handle event)
          selected (get-in result [:state :ui :projector-effect-ui-state :proj-1 :selected-paths])]
      ;; Should have both [0] and [1]
      (is (= #{[0] [1]} selected))))
  
  (testing "Ctrl+click toggles selection off"
    (let [state-with-selection (assoc-in state-with-projector
                                         [:ui :projector-effect-ui-state :proj-1 :selected-paths]
                                         #{[0] [1]})
          event {:event/type :projectors/select-effect
                 :projector-id :proj-1
                 :path [1]
                 :ctrl? true
                 :shift? false
                 :state state-with-selection}
          result (projectors/handle event)
          selected (get-in result [:state :ui :projector-effect-ui-state :proj-1 :selected-paths])]
      ;; Should have only [0] (toggled [1] off)
      (is (= #{[0]} selected))))
  
  (testing "Shift+click range selects between anchor and target"
    (let [state-with-anchor (-> state-with-projector
                                (assoc-in [:ui :projector-effect-ui-state :proj-1 :selected-paths] #{[0]})
                                (assoc-in [:ui :projector-effect-ui-state :proj-1 :last-selected-path] [0]))
          event {:event/type :projectors/select-effect
                 :projector-id :proj-1
                 :path [2]
                 :ctrl? false
                 :shift? true
                 :state state-with-anchor}
          result (projectors/handle event)
          selected (get-in result [:state :ui :projector-effect-ui-state :proj-1 :selected-paths])]
      ;; Should select [0], [1], [2]
      (is (= #{[0] [1] [2]} selected))))
  
  (testing "Shift+click does not update anchor"
    (let [state-with-anchor (-> state-with-projector
                                (assoc-in [:ui :projector-effect-ui-state :proj-1 :selected-paths] #{[0]})
                                (assoc-in [:ui :projector-effect-ui-state :proj-1 :last-selected-path] [0]))
          event {:event/type :projectors/select-effect
                 :projector-id :proj-1
                 :path [2]
                 :ctrl? false
                 :shift? true
                 :state state-with-anchor}
          result (projectors/handle event)
          anchor (get-in result [:state :ui :projector-effect-ui-state :proj-1 :last-selected-path])]
      ;; Anchor should still be [0]
      (is (= [0] anchor)))))


(deftest handle-projectors-remove-projector-test
  (testing "Removing active projector selects another"
    (let [state-multi-proj (-> state-with-projector
                               (assoc-in [:projectors :items :proj-2] {:name "Projector 2"})
                               (assoc-in [:projectors :active-projector] :proj-1))
          event {:event/type :projectors/remove-projector
                 :projector-id :proj-1
                 :state state-multi-proj}
          result (projectors/handle event)
          items (get-in result [:state :projectors :items])
          active (get-in result [:state :projectors :active-projector])]
      ;; proj-1 should be removed
      (is (not (contains? items :proj-1)))
      ;; Should auto-select proj-2
      (is (= :proj-2 active))))
  
  (testing "Removing non-active projector keeps active unchanged"
    (let [state-multi-proj (-> state-with-projector
                               (assoc-in [:projectors :items :proj-2] {:name "Projector 2"})
                               (assoc-in [:projectors :active-projector] :proj-1))
          event {:event/type :projectors/remove-projector
                 :projector-id :proj-2
                 :state state-multi-proj}
          result (projectors/handle event)
          active (get-in result [:state :projectors :active-projector])]
      ;; Active should still be proj-1
      (is (= :proj-1 active)))))
