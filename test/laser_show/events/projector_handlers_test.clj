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
   [clojure.string :as str]
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


;; Test Fixtures
;;
;; Corner-pin is stored as an effect in the projector's effect chain.
;; Updates to corner-pin params go through the standard :chain/update-spatial-params handler.


(def test-projector-id :proj-1)

(def state-with-projector
  "Test state with a projector that has RGB curves and corner-pin effects."
  (-> sample-state
      (assoc-in [:projectors test-projector-id]
                {:name "Test Projector"
                 :host "192.168.1.100"
                 :enabled? true
                 :zone-groups [:all]
                 :tags #{}})
      (assoc-in [:chains :projector-effects test-projector-id :items]
                [{:id (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
                  :effect-id :rgb-curves
                  :enabled? true
                  :params {:r-curve-points [[0.0 0.0] [1.0 1.0]]
                           :g-curve-points [[0.0 0.0] [1.0 1.0]]
                           :b-curve-points [[0.0 0.0] [1.0 1.0]]}}
                 {:id (java.util.UUID/fromString "00000000-0000-0000-0000-000000000002")
                  :effect-id :corner-pin
                  :enabled? true
                  :params {:tl-x -1.0 :tl-y 1.0
                           :tr-x 1.0 :tr-y 1.0
                           :bl-x -1.0 :bl-y -1.0
                           :br-x 1.0 :br-y -1.0}}])))


;; NOTE: Corner-pin updates now go through :chain/update-spatial-params and :chain/reset-params
;; instead of the special :projectors/update-corner-pin and :projectors/reset-corner-pin handlers.
;; Tests for that functionality should be in the chain handlers tests.


;; Zone Group Assignment Tests


(deftest handle-projectors-toggle-zone-group-test
  (testing "Adds zone group when not present"
    (let [event {:event/type :projectors/toggle-zone-group
                 :projector-id test-projector-id
                 :zone-group-id :left
                 :state state-with-projector}
          result (projectors/handle event)
          zone-groups (get-in result [:state :projectors test-projector-id :zone-groups])]
      (is (some #{:left} zone-groups))
      (is (some #{:all} zone-groups))))
  
  (testing "Removes zone group when present"
    (let [state-with-groups (assoc-in state-with-projector
                                      [:projectors test-projector-id :zone-groups]
                                      [:all :left :right])
          event {:event/type :projectors/toggle-zone-group
                 :projector-id test-projector-id
                 :zone-group-id :left
                 :state state-with-groups}
          result (projectors/handle event)
          zone-groups (get-in result [:state :projectors test-projector-id :zone-groups])]
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
          zone-groups (get-in result [:state :projectors test-projector-id :zone-groups])]
      (is (= [:left :right] zone-groups)))))


;; Virtual Projector Tests


(def vp-id #uuid "11111111-1111-1111-1111-111111111111")


(deftest handle-projectors-add-virtual-projector-test
  (testing "Creates virtual projector with custom corner-pin inheriting parent's color curves"
    (let [event {:event/type :projectors/add-virtual-projector
                 :parent-projector-id test-projector-id
                 :name "Graphics Zone"
                 :state state-with-projector}
          result (projectors/handle event)
          vps (get-in result [:state :virtual-projectors])
          new-vp (first (vals vps))]
      (is (= 1 (count vps)))
      (is (= "Graphics Zone" (:name new-vp)))
      (is (= test-projector-id (:parent-projector-id new-vp)))
      (is (contains? new-vp :corner-pin))
      (is (vector? (:zone-groups new-vp))))))


(deftest handle-vp-update-corner-pin-test
  (testing "Updates virtual projector corner-pin"
    (let [state-with-vp (assoc-in state-with-projector
                                  [:virtual-projectors vp-id]
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
          corner-pin (get-in result [:state :virtual-projectors vp-id :corner-pin])]
      (is (= 0.7 (:br-x corner-pin)))
      (is (= -0.8 (:br-y corner-pin))))))


(deftest handle-vp-toggle-zone-group-test
  (testing "Toggles zone group on virtual projector"
    (let [state-with-vp (assoc-in state-with-projector
                                  [:virtual-projectors vp-id]
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
          zone-groups (get-in result [:state :virtual-projectors vp-id :zone-groups])]
      (is (some #{:graphics} zone-groups)))))


(deftest handle-projectors-remove-virtual-projector-test
  (testing "Removes virtual projector"
    (let [state-with-vp (assoc-in state-with-projector
                                  [:virtual-projectors vp-id]
                                  {:name "Test VP"
                                   :parent-projector-id test-projector-id})
          event {:event/type :projectors/remove-virtual-projector
                 :vp-id vp-id
                 :state state-with-vp}
          result (projectors/handle event)]
      (is (empty? (get-in result [:state :virtual-projectors]))))))


(deftest handle-projectors-remove-projector-test
  (testing "Removing active projector selects another"
    (let [state-multi-proj (-> state-with-projector
                               (assoc-in [:projectors :proj-2] {:name "Projector 2"
                                                                 :zone-groups [:all]
                                                                 :tags #{}})
                               (assoc-in [:projector-ui :active-projector] test-projector-id))
          event {:event/type :projectors/remove-projector
                 :projector-id test-projector-id
                 :state state-multi-proj}
          result (projectors/handle event)
          items (get-in result [:state :projectors])
          active (get-in result [:state :projector-ui :active-projector])]
      ;; proj-1 should be removed
      (is (not (contains? items test-projector-id)))
      ;; Should auto-select proj-2
      (is (= :proj-2 active))))
  
  (testing "Removing non-active projector keeps active unchanged"
    (let [state-multi-proj (-> state-with-projector
                               (assoc-in [:projectors :proj-2] {:name "Projector 2"
                                                                 :zone-groups [:all]
                                                                 :tags #{}})
                               (assoc-in [:projector-ui :active-projector] test-projector-id))
          event {:event/type :projectors/remove-projector
                 :projector-id :proj-2
                 :state state-multi-proj}
          result (projectors/handle event)
          active (get-in result [:state :projector-ui :active-projector])]
      ;; Active should still be proj-1
      (is (= test-projector-id active))))
  
  (testing "Removing projector also removes its virtual projectors"
    (let [state-with-vp (-> state-with-projector
                            (assoc-in [:virtual-projectors vp-id]
                                      {:name "Test VP"
                                       :parent-projector-id test-projector-id
                                       :zone-groups [:all]}))
          event {:event/type :projectors/remove-projector
                 :projector-id test-projector-id
                 :state state-with-vp}
          result (projectors/handle event)
          vps (get-in result [:state :virtual-projectors])]
      ;; Virtual projector should be removed since parent is gone
      (is (nil? (get vps vp-id))))))


;; Effect Chain Addition Tests


(deftest handle-projectors-add-effect-test
  (testing "Adds calibration effect to projector's chain"
    (let [;; Add axis-transform effect
          event {:event/type :projectors/add-effect
                 :projector-id test-projector-id
                 :effect {:effect-id :axis-transform
                          :params {:mirror-x? false
                                   :mirror-y? true
                                   :swap-axes? false}}
                 :state state-with-projector}
          result (projectors/handle event)
          effects (get-in result [:state :chains :projector-effects test-projector-id :items])]
      ;; Should have added the effect (original had 2 effects: rgb-curves + corner-pin)
      (is (= 3 (count effects)))
      
      ;; New effect should be last
      (let [new-effect (last effects)]
        (is (= :axis-transform (:effect-id new-effect)))
        (is (= false (get-in new-effect [:params :mirror-x?])))
        (is (= true (get-in new-effect [:params :mirror-y?])))
        (is (= false (get-in new-effect [:params :swap-axes?])))
        ;; Effect should have unique ID assigned
        (is (uuid? (:id new-effect)))
        ;; Effect should be enabled by default
        (is (true? (:enabled? new-effect))))
      
      ;; Project should be marked dirty
      (is (true? (get-in result [:state :project :dirty?])))))
  
  (testing "Adds corner-pin calibration effect"
    (let [event {:event/type :projectors/add-effect
                 :projector-id test-projector-id
                 :effect {:effect-id :corner-pin
                          :params {:tl-x -0.9 :tl-y 0.9
                                   :tr-x 0.9 :tr-y 0.9
                                   :bl-x -0.9 :bl-y -0.9
                                   :br-x 0.9 :br-y -0.9}}
                 :state state-with-projector}
          result (projectors/handle event)
          effects (get-in result [:state :chains :projector-effects test-projector-id :items])
          new-effect (last effects)]
      (is (= :corner-pin (:effect-id new-effect)))
      (is (= -0.9 (get-in new-effect [:params :tl-x]))))))


;; Projector Configuration Validation Tests


(deftest handle-projectors-add-manual-with-valid-host-test
  (testing "Manual projector with valid host is created successfully"
    (let [event {:event/type :projectors/add-manual
                 :name "Test Projector"
                 :host "192.168.1.100"
                 :port 7255
                 :state sample-state}
          result (projectors/handle event)
          projectors (get-in result [:state :projectors])]
      (is (= 1 (count projectors)) "Should create one projector")
      (let [projector (first (vals projectors))]
        (is (= "Test Projector" (:name projector)) "Should have correct name")
        (is (= "192.168.1.100" (:host projector)) "Should have valid host")
        (is (true? (:enabled? projector)) "Manual projectors should be enabled by default")))))

(deftest handle-projectors-add-manual-with-nil-host-throws-test
  (testing "Manual projector with nil host throws exception"
    (let [event {:event/type :projectors/add-manual
                 :name "Invalid Projector"
                 :host nil
                 :port 7255
                 :state sample-state}]
      (is (thrown? AssertionError (projectors/handle event))
          "Should throw AssertionError for nil host"))))

(deftest handle-projectors-add-manual-with-blank-host-throws-test
  (testing "Manual projector with blank host throws exception"
    (let [event {:event/type :projectors/add-manual
                 :name "Invalid Projector"
                 :host ""
                 :port 7255
                 :state sample-state}]
      (is (thrown? AssertionError (projectors/handle event))
          "Should throw AssertionError for blank host"))))

(deftest handle-projectors-add-manual-with-whitespace-host-throws-test
  (testing "Manual projector with whitespace-only host throws exception"
    (let [event {:event/type :projectors/add-manual
                 :name "Invalid Projector"
                 :host "   "
                 :port 7255
                 :state sample-state}]
      (is (thrown? AssertionError (projectors/handle event))
          "Should throw AssertionError for whitespace-only host"))))

(deftest handle-scan-complete-skips-invalid-devices-test
  (testing "Auto-discovered devices with invalid addresses are created but validation happens at streaming time"
    ;; Note: Auto-discovery currently doesn't validate hosts at creation time,
    ;; validation happens when starting streaming engines
    (let [invalid-device {:address nil  ;; Invalid address
                          :host-name "BadDevice"
                          :port 7255
                          :unit-id "XYZ"
                          :services [{:service-id 0 :name "Output" :flags {}}]}
          event {:event/type :projectors/scan-complete
                 :devices [invalid-device]
                 :state sample-state}
          ;; This should throw when trying to create the projector config
          result (try
                   (projectors/handle event)
                   (catch AssertionError _e
                     ;; Expected - validation at config creation
                     {:state sample-state}))]
      ;; If it throws, projector won't be created
      (is (or (empty? (get-in result [:state :projectors]))
              ;; Or if exception was caught, state is unchanged
              (= sample-state (:state result)))
          "Should not create projector with invalid host"))))

(deftest handle-projectors-update-settings-with-valid-host-test
  (testing "Updating host to valid value succeeds"
    (let [event {:event/type :projectors/update-settings
                 :projector-id test-projector-id
                 :updates {:host "192.168.1.200"}
                 :state state-with-projector}
          result (projectors/handle event)
          updated-host (get-in result [:state :projectors test-projector-id :host])]
      (is (= "192.168.1.200" updated-host) "Should update to new valid host"))))

(deftest handle-projectors-update-settings-with-nil-host-throws-test
  (testing "Updating host to nil throws exception"
    (let [event {:event/type :projectors/update-settings
                 :projector-id test-projector-id
                 :updates {:host nil}
                 :state state-with-projector}]
      (is (thrown? clojure.lang.ExceptionInfo (projectors/handle event))
          "Should throw ExceptionInfo for nil host"))))

(deftest handle-projectors-update-settings-with-blank-host-throws-test
  (testing "Updating host to blank string throws exception"
    (let [event {:event/type :projectors/update-settings
                 :projector-id test-projector-id
                 :updates {:host ""}
                 :state state-with-projector}]
      (is (thrown? clojure.lang.ExceptionInfo (projectors/handle event))
          "Should throw ExceptionInfo for blank host"))))

(deftest handle-projectors-update-settings-without-host-succeeds-test
  (testing "Updating other settings without touching host succeeds"
    (let [event {:event/type :projectors/update-settings
                 :projector-id test-projector-id
                 :updates {:name "Updated Name" :port 7256}
                 :state state-with-projector}
          result (projectors/handle event)
          updated-name (get-in result [:state :projectors test-projector-id :name])
          updated-port (get-in result [:state :projectors test-projector-id :port])]
      (is (= "Updated Name" updated-name) "Should update name")
      (is (= 7256 updated-port) "Should update port"))))


;; Engine Refresh on Projector Enable/Disable Tests
;;
;; These tests verify that when streaming is running and projector enabled state
;; changes, the :multi-engine/refresh effect is returned to trigger engine recreation.
;; This fixes the bug where starting streaming before enabling projectors would
;; result in zero engines being created, and later enabling projectors would not
;; create new engines.


(def state-with-streaming-running
  "State with streaming active and a disabled projector."
  (-> state-with-projector
      (assoc-in [:projectors test-projector-id :enabled?] false)
      (assoc-in [:backend :streaming :running?] true)))

(def state-with-streaming-stopped
  "State with streaming stopped and a disabled projector."
  (-> state-with-projector
      (assoc-in [:projectors test-projector-id :enabled?] false)
      (assoc-in [:backend :streaming :running?] false)))


(deftest handle-projectors-toggle-enabled-triggers-refresh-when-streaming-test
  (testing "Toggle enabled returns :multi-engine/refresh when streaming is running"
    (let [event {:event/type :projectors/toggle-enabled
                 :projector-id test-projector-id
                 :state state-with-streaming-running}
          result (projectors/handle event)]
      (is (true? (:multi-engine/refresh result))
          "Should return :multi-engine/refresh effect when streaming is running")
      (is (true? (get-in result [:state :projectors test-projector-id :enabled?]))
          "Should toggle the enabled state")))
  
  (testing "Toggle enabled does NOT return :multi-engine/refresh when streaming is stopped"
    (let [event {:event/type :projectors/toggle-enabled
                 :projector-id test-projector-id
                 :state state-with-streaming-stopped}
          result (projectors/handle event)]
      (is (nil? (:multi-engine/refresh result))
          "Should NOT return :multi-engine/refresh effect when streaming is stopped")
      (is (true? (get-in result [:state :projectors test-projector-id :enabled?]))
          "Should still toggle the enabled state"))))


(deftest handle-projectors-set-service-enabled-triggers-refresh-when-streaming-test
  (let [host "192.168.1.100"
        service-id 0
        ;; Create a projector with the deterministic ID that set-service-enabled expects
        proj-id (keyword (str "projector-192-168-1-100-0"))
        state-with-proj (-> base-state
                            (assoc-in [:projectors proj-id]
                                      {:name "Test"
                                       :host host
                                       :enabled? false
                                       :zone-groups [:all]})
                            (assoc-in [:backend :streaming :running?] true))]
    
    (testing "Set service enabled returns :multi-engine/refresh when streaming is running"
      (let [event {:event/type :projectors/set-service-enabled
                   :host host
                   :service-id service-id
                   :enabled? true
                   :state state-with-proj}
            result (projectors/handle event)]
        (is (true? (:multi-engine/refresh result))
            "Should return :multi-engine/refresh effect when streaming is running")
        (is (true? (get-in result [:state :projectors proj-id :enabled?]))
            "Should set the enabled state")))
    
    (testing "Set service enabled does NOT return :multi-engine/refresh when streaming is stopped"
      (let [state-stopped (assoc-in state-with-proj [:backend :streaming :running?] false)
            event {:event/type :projectors/set-service-enabled
                   :host host
                   :service-id service-id
                   :enabled? true
                   :state state-stopped}
            result (projectors/handle event)]
        (is (nil? (:multi-engine/refresh result))
            "Should NOT return :multi-engine/refresh effect when streaming is stopped")))))


(deftest handle-projectors-enable-all-by-ip-triggers-refresh-when-streaming-test
  (let [host "192.168.1.100"
        proj-id-1 (keyword (str "projector-" (str/replace host "." "-") "-0"))
        proj-id-2 (keyword (str "projector-" (str/replace host "." "-") "-1"))
        state-with-projs (-> base-state
                             (assoc-in [:projectors proj-id-1]
                                       {:name "Output A"
                                        :host host
                                        :enabled? false
                                        :zone-groups [:all]})
                             (assoc-in [:projectors proj-id-2]
                                       {:name "Output B"
                                        :host host
                                        :enabled? false
                                        :zone-groups [:all]})
                             (assoc-in [:backend :streaming :running?] true))]
    
    (testing "Enable all by IP returns :multi-engine/refresh when streaming is running"
      (let [event {:event/type :projectors/enable-all-by-ip
                   :address host
                   :state state-with-projs}
            result (projectors/handle event)]
        (is (true? (:multi-engine/refresh result))
            "Should return :multi-engine/refresh effect when streaming is running")
        (is (true? (get-in result [:state :projectors proj-id-1 :enabled?]))
            "Should enable first projector")
        (is (true? (get-in result [:state :projectors proj-id-2 :enabled?]))
            "Should enable second projector")))
    
    (testing "Enable all by IP does NOT return :multi-engine/refresh when streaming is stopped"
      (let [state-stopped (assoc-in state-with-projs [:backend :streaming :running?] false)
            event {:event/type :projectors/enable-all-by-ip
                   :address host
                   :state state-stopped}
            result (projectors/handle event)]
        (is (nil? (:multi-engine/refresh result))
            "Should NOT return :multi-engine/refresh effect when streaming is stopped")))))


(deftest handle-projectors-enable-all-by-ip-no-refresh-when-no-matching-projectors-test
  (testing "Enable all by IP returns no refresh when no projectors match"
    (let [event {:event/type :projectors/enable-all-by-ip
                 :address "10.0.0.1"  ;; No projectors with this IP
                 :state state-with-streaming-running}
          result (projectors/handle event)]
      (is (nil? (:multi-engine/refresh result))
          "Should not return refresh when no projectors match"))))
