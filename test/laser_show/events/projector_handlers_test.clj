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
   [laser-show.state.history :as history]
   [clojure.string :as str]
   [laser-show.events.core :as events]
   [laser-show.events.handlers.projector :as projectors]
   [laser-show.events.safety-guard :as guard]
   [laser-show.routing.rig :as rig]
   [laser-show.state.domains :refer [build-initial-state]]))


;; Test Data Fixtures




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



(deftest handle-projectors-toggle-zone-group-test
  (testing "Adds zone group when not present"
    (let [event {:event/type :projectors/toggle-zone-group
                 :projector-id test-projector-id
                 :zone-group-id :graphics
                 :state (assoc-in state-with-projector [:projectors test-projector-id :zone-groups] [])}
          result (projectors/handle event)
          zone-groups (get-in result [:state :projectors test-projector-id :zone-groups])]
      (is (= [:graphics] zone-groups))))
  
  (testing "Removes zone group when present"
    (let [state-with-groups (assoc-in state-with-projector
                                      [:projectors test-projector-id :zone-groups]
                                      [:graphics :crowd :group-balcony])
          event {:event/type :projectors/toggle-zone-group
                 :projector-id test-projector-id
                 :zone-group-id :crowd
                 :state state-with-groups}
          result (projectors/handle event)
          zone-groups (get-in result [:state :projectors test-projector-id :zone-groups])]
      (is (= [:graphics :group-balcony] zone-groups)))))

(deftest toggle-refuses-derived-groups-test
  (let [vp-state (assoc-in state-with-projector [:virtual-projectors :vp1] {:name "VP" :parent-projector-id test-projector-id
                                                                             :zone-groups []})]
    (doseq [ref [:all :auto/left :auto/even :auto.laser/proj-1]]
      (testing (str ref)
        (is (identical? vp-state (:state (projectors/handle {:event/type :projectors/toggle-zone-group
                                                             :projector-id test-projector-id :zone-group-id ref
                                                             :state vp-state})))
            "All and the automatic groups are never stored")
        (is (identical? vp-state (:state (projectors/handle {:event/type :projectors/vp-toggle-zone-group
                                                             :vp-id :vp1 :zone-group-id ref :state vp-state})))
            "nor in a virtual projector's list")))))

(deftest new-projectors-are-in-all-without-listing-it-test
  (let [added (first (vals (get-in (projectors/handle {:event/type :projectors/add-manual :name "Manual" :host "10.0.0.5"
                                                       :state sample-state})
                                   [:state :projectors])))
        found (get-in (projectors/handle {:event/type :projectors/scan-complete
                                          :devices [{:address "10.0.0.6" :unit-id "AB" :port 7255 :services [{:service-id 1}]}]
                                          :state sample-state})
                      [:state :projectors :projector-10-0-0-6-1])]
    (doseq [p [added found]]
      (is (= {:zone-groups [] :exclude-from-all? false} (select-keys p [:zone-groups :exclude-from-all?]))))
    (is (= :all (first (rig/refs-for {:projectors {:p added}} :p))) "and it plays All")))




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
      (is (history/derive-dirty (:state event) (:state result)))))
  
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

(deftest add-manual-closes-and-clears-its-dialog-test
  (let [open (assoc-in sample-state [:ui :dialogs :add-projector-manual]
                       {:open? true :name "Stage left" :host "10.0.0.5" :port "7300"})
        result (projectors/handle {:event/type :projectors/add-manual
                                   :name "Stage left" :host "10.0.0.5" :port 7300
                                   :state open})]
    (is (= 1 (count (get-in result [:state :projectors]))))
    (is (= {:open? false} (get-in result [:state :ui :dialogs :add-projector-manual]))
        "closed, so a second click can't add it again, and blank when it opens next")))

(deftest failed-scan-shows-a-notice-test
  (let [scanning (assoc-in sample-state [:projector-ui :scanning?] true)
        result (projectors/handle {:event/type :projectors/scan-failed
                                   :error "Network is unreachable"
                                   :state scanning})
        {:keys [id] :as notice} (get-in result [:state :ui :notice])]
    (is (false? (get-in result [:state :projector-ui :scanning?])) "Scan Network is back")
    (is (= {:text "Could not scan the network: Network is unreachable" :level :error}
           (dissoc notice :id)))
    (is (= {:event {:event/type :ui/clear-notice :id id} :delay-ms 6000}
           (:dispatch-later result))
        "it goes after 6 s, as an open or save failure's")
    (testing "without a reason"
      (is (= "Could not scan the network"
             (get-in (projectors/handle {:event/type :projectors/scan-failed :error nil :state scanning})
                     [:state :ui :notice :text]))))))

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

(deftest handle-projectors-update-settings-ignores-nil-host-test
  (testing "A nil host is ignored; the other updates still apply"
    (let [event {:event/type :projectors/update-settings
                 :projector-id test-projector-id
                 :updates {:host nil :name "Renamed"}
                 :state state-with-projector}
          projector (get-in (projectors/handle event) [:state :projectors test-projector-id])]
      (is (= "192.168.1.100" (:host projector)) "Host is unchanged")
      (is (= "Renamed" (:name projector))))))

(deftest handle-projectors-update-settings-ignores-blank-host-test
  (testing "A blank host is ignored instead of throwing"
    (doseq [blank ["" "   "]]
      (let [event {:event/type :projectors/update-settings
                   :projector-id test-projector-id
                   :updates {:host blank}
                   :state state-with-projector}]
        (is (= "192.168.1.100"
               (get-in (projectors/handle event) [:state :projectors test-projector-id :host])))))))

(deftest handle-projectors-update-settings-validates-port-test
  (doseq [[port expected] [[7300 7300] [1 1] [65535 65535]
                           [0 7255] [65536 7255] [-1 7255] [nil 7255] ["7300" 7255] [72.5 7255]]]
    (testing (pr-str port)
      (let [event {:event/type :projectors/update-settings
                   :projector-id test-projector-id
                   :updates {:port port}
                   :state (assoc-in state-with-projector [:projectors test-projector-id :port] 7255)}]
        (is (= expected
               (get-in (projectors/handle event) [:state :projectors test-projector-id :port])))))))

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

(deftest scan-rate-is-clamped-test
  (doseq [[rate expected] [[25000 25000] [40000 40000] [500 1000] [200000 100000]
                           [27500.4 27500]
                           [nil 30000] ["25000" 30000] [##NaN 30000]]]
    (testing (pr-str rate)
      (let [event {:event/type :projectors/update-settings
                   :projector-id test-projector-id
                   :updates {:scan-rate rate :name "Renamed"}
                   :state (assoc-in state-with-projector [:projectors test-projector-id :scan-rate] 30000)}
            projector (get-in (projectors/handle event) [:state :projectors test-projector-id])]
        (is (= expected (:scan-rate projector)) "clamped to 1 000-100 000; a non-number is dropped")
        (is (= "Renamed" (:name projector)) "the other updates still apply")))))

(deftest new-projectors-default-to-25k-test
  (let [event {:event/type :projectors/add-manual :name "Manual" :host "10.0.0.5" :state sample-state}]
    (is (= [25000] (map :scan-rate (vals (get-in (projectors/handle event) [:state :projectors])))))))


;; Engine Refresh on Projector Enable/Disable Tests
;;
;; When streaming is running and a projector's enabled state changes, the
;; event pipeline's output-sync middleware adds :multi-engine/refresh with the
;; new projector snapshot (handlers/output_sync.clj); the handlers themselves
;; no longer ask for it. These run the handlers through that pipeline.
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

(defn- through-pipeline [event]
  (events/handle-event-pipeline event))


(deftest handle-projectors-toggle-enabled-triggers-refresh-when-streaming-test
  (testing "Toggle enabled requests a refresh when streaming is running"
    (let [event {:event/type :projectors/toggle-enabled
                 :projector-id test-projector-id
                 :state state-with-streaming-running}
          result (through-pipeline event)]
      (is (= (get-in result [:state :projectors])
             (get-in result [:multi-engine/refresh :projectors]))
          "Should request a refresh with the new projectors when streaming is running")
      (is (true? (get-in result [:state :projectors test-projector-id :enabled?]))
          "Should toggle the enabled state")))
  
  (testing "Toggle enabled does NOT request a refresh when streaming is stopped"
    (let [event {:event/type :projectors/toggle-enabled
                 :projector-id test-projector-id
                 :state state-with-streaming-stopped}
          result (through-pipeline event)]
      (is (nil? (:multi-engine/refresh result))
          "Should NOT request a refresh when streaming is stopped")
      (is (true? (get-in result [:state :projectors test-projector-id :enabled?]))
          "Should still toggle the enabled state"))))


(deftest handle-projectors-set-service-enabled-triggers-refresh-when-streaming-test
  (let [host "192.168.1.100"
        service-id 0
        ;; Create a projector with the deterministic ID that set-service-enabled expects
        proj-id (keyword "projector-192-168-1-100-0")
        state-with-proj (-> base-state
                            (assoc-in [:projectors proj-id]
                                      {:name "Test"
                                       :host host
                                       :enabled? false
                                       :zone-groups [:all]})
                            (assoc-in [:backend :streaming :running?] true))]
    
    (testing "Set service enabled requests a refresh when streaming is running"
      (let [event {:event/type :projectors/set-service-enabled
                   :host host
                   :service-id service-id
                   :enabled? true
                   :state state-with-proj}
            result (through-pipeline event)]
        (is (contains? (get-in result [:multi-engine/refresh :projectors]) proj-id)
            "Should request a refresh when streaming is running")
        (is (true? (get-in result [:state :projectors proj-id :enabled?]))
            "Should set the enabled state")))
    
    (testing "Set service enabled does NOT request a refresh when streaming is stopped"
      (let [state-stopped (assoc-in state-with-proj [:backend :streaming :running?] false)
            event {:event/type :projectors/set-service-enabled
                   :host host
                   :service-id service-id
                   :enabled? true
                   :state state-stopped}
            result (through-pipeline event)]
        (is (nil? (:multi-engine/refresh result))
            "Should NOT request a refresh when streaming is stopped")))))


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
    
    (testing "Enable all by IP requests a refresh when streaming is running"
      (let [event {:event/type :projectors/enable-all-by-ip
                   :address host
                   :state state-with-projs}
            result (through-pipeline event)]
        (is (= #{proj-id-1 proj-id-2} (set (keys (get-in result [:multi-engine/refresh :projectors]))))
            "Should request a refresh when streaming is running")
        (is (true? (get-in result [:state :projectors proj-id-1 :enabled?]))
            "Should enable first projector")
        (is (true? (get-in result [:state :projectors proj-id-2 :enabled?]))
            "Should enable second projector")))
    
    (testing "Enable all by IP does NOT request a refresh when streaming is stopped"
      (let [state-stopped (assoc-in state-with-projs [:backend :streaming :running?] false)
            event {:event/type :projectors/enable-all-by-ip
                   :address host
                   :state state-stopped}
            result (through-pipeline event)]
        (is (nil? (:multi-engine/refresh result))
            "Should NOT request a refresh when streaming is stopped")))))


(deftest handle-projectors-enable-all-by-ip-no-refresh-when-no-matching-projectors-test
  (testing "Enable all by IP requests no refresh when no projectors match"
    (let [event {:event/type :projectors/enable-all-by-ip
                 :address "10.0.0.1"  ;; No projectors with this IP
                 :state state-with-streaming-running}
          result (through-pipeline event)]
      (is (nil? (:multi-engine/refresh result))
          "Should not request a refresh when no projectors match"))))


;; Stable identity: a scan finds a laser by its unit id and output (04 §4)


(def ^:private unit-l "0A 1B 2C 3D 4E 5F 60 71 82 93 A4 B5 C6 D7 E8 F9")
(def ^:private unit-r "1F 2E 3D 4C 5B 6A 79 88 97 A6 B5 C4 D3 E2 F1 00")

(defn- scanned
  "A device as the scan effect reports it, with these outputs."
  [address unit-id & service-ids]
  {:address address :host-name "DAC" :unit-id unit-id :port 7255 :status {} :protocol-version {:major 1 :minor 0}
   :services (mapv (fn [s] {:service-id s :name (str "Head " s)}) service-ids) :relays []})

(def ^:private placed-rig
  "Laser L and Laser R, found by a scan and set up: named, calibrated, in a
   group and placed, R first."
  (-> base-state
      (assoc-in [:projectors :projector-10-0-0-5-1]
                {:name "Laser L" :host "10.0.0.5" :port 7255 :service-id 1 :unit-id unit-l
                 :stable-id [:unit unit-l 1] :enabled? true :zone-groups [:all :graphics] :tags #{}})
      (assoc-in [:projectors :projector-10-0-0-9-1]
                {:name "Laser R" :host "10.0.0.9" :port 7255 :service-id 1 :unit-id unit-r
                 :stable-id [:unit unit-r 1] :enabled? true :zone-groups [:all] :tags #{}})
      (assoc-in [:chains :projector-effects :projector-10-0-0-5-1 :items]
                [{:id #uuid "00000000-0000-0000-0000-0000000000c1" :effect-id :rgb-curves :enabled? true
                  :params {:r-curve-points [[0.0 0.2] [1.0 1.0]]}}])
      (assoc-in [:rig :order] [:projector-10-0-0-9-1 :projector-10-0-0-5-1])))

(defn- scan [state & devices]
  (projectors/handle {:event/type :projectors/scan-complete :devices (vec devices) :state state}))

(deftest scan-moves-a-known-unit-to-its-new-ip-test
  (let [result (scan placed-rig (scanned "10.0.0.57" unit-l 1) (scanned "10.0.0.9" unit-r 1))
        state (:state result)]
    (is (= (assoc (get-in placed-rig [:projectors :projector-10-0-0-5-1]) :host "10.0.0.57")
           (get-in state [:projectors :projector-10-0-0-5-1]))
        "the same projector: its name, groups and tags kept, only the address changed")
    (is (= (get-in placed-rig [:chains :projector-effects]) (get-in state [:chains :projector-effects]))
        "its calibration kept")
    (is (= [:projector-10-0-0-9-1 :projector-10-0-0-5-1] (get-in state [:rig :order])) "and its place")
    (is (= #{:projector-10-0-0-5-1 :projector-10-0-0-9-1} (set (keys (:projectors state))))
        "not a new, disabled projector")
    (is (= 7300 (get-in (scan placed-rig (assoc (scanned "10.0.0.57" unit-l 1) :port 7300))
                        [:state :projectors :projector-10-0-0-5-1 :port]))
        "its port moves with it")))

(deftest scan-move-is-not-undone-and-not-unsaved-test
  (let [pipeline #(:state (events/handle-event-pipeline (assoc %2 :state %1)))
        clean (history/mark-clean placed-rig)
        renamed (pipeline clean {:event/type :projectors/update-settings :projector-id :projector-10-0-0-9-1
                                 :updates {:name "Stage R"}})
        scanned-state (pipeline renamed {:event/type :projectors/scan-complete
                                         :devices [(scanned "10.0.0.57" unit-l 1)]})
        undone (pipeline scanned-state {:event/type :history/undo})]
    (is (= "10.0.0.57" (get-in scanned-state [:projectors :projector-10-0-0-5-1 :host])))
    (is (= 1 (count (get-in scanned-state [:history :undo-stack]))) "the scan is no undo step")
    (is (= "Laser R" (get-in undone [:projectors :projector-10-0-0-9-1 :name])) "Undo takes back the rename")
    (is (= "10.0.0.57" (get-in undone [:projectors :projector-10-0-0-5-1 :host])) "and leaves the move")
    (is (false? (get-in undone [:project :dirty?])) "which isn't an unsaved change")
    (is (false? (get-in (pipeline clean {:event/type :projectors/scan-complete
                                         :devices [(scanned "10.0.0.57" unit-l 1)]})
                        [:project :dirty?]))
        "a scan never makes a saved show unsaved")))

(deftest scan-gives-a-new-unit-a-free-id-test
  (let [moved-away (assoc-in placed-rig [:projectors :projector-10-0-0-5-1 :host] "10.0.0.57")
        state (:state (scan moved-away (scanned "10.0.0.5" "99 88 77 66" 1)))
        new-one (get-in state [:projectors :projector-10-0-0-5-1-2])]
    (is (= {:name "Head 1" :host "10.0.0.5" :service-id 1 :enabled? false :stable-id [:unit "99 88 77 66" 1]}
           (select-keys new-one [:name :host :service-id :enabled? :stable-id]))
        "a different unit at Laser L's old address is a new laser, off")
    (is (= "Laser L" (get-in state [:projectors :projector-10-0-0-5-1 :name])) "and never takes Laser L's id")
    (is (= [:rgb-curves :corner-pin] (mapv :effect-id (get-in state [:chains :projector-effects :projector-10-0-0-5-1-2 :items])))
        "with the default calibration")))

(deftest scan-shows-a-notice-for-moves-test
  (let [result (scan placed-rig (scanned "10.0.0.57" unit-l 1))]
    (is (= {:text "Laser L moved to 10.0.0.57 (same unit)." :level :info} (dissoc (get-in result [:state :ui :notice]) :id)))
    (is (= 6000 (get-in result [:dispatch-later :delay-ms])) "it goes after 6 s"))
  (is (= "2 lasers have new addresses: Laser L, Laser R."
         (get-in (scan placed-rig (scanned "10.0.0.57" unit-l 1) (scanned "10.0.0.91" unit-r 1)) [:state :ui :notice :text])))
  (is (nil? (get-in (scan placed-rig (scanned "10.0.0.5" unit-l 1)) [:state :ui :notice])) "nothing moved: no notice")
  (is (= "Two devices report the same unit id (10.0.0.5, 10.0.0.6): matched by IP."
         (get-in (scan base-state (scanned "10.0.0.6" "unit" 1) (scanned "10.0.0.5" "unit" 1)) [:state :ui :notice :text]))))

(deftest set-service-enabled-finds-a-moved-laser-test
  (let [moved (:state (scan (assoc-in placed-rig [:projectors :projector-10-0-0-5-1 :enabled?] false)
                            (scanned "10.0.0.57" unit-l 1)))
        result (projectors/handle {:event/type :projectors/set-service-enabled
                                   :host "10.0.0.57" :service-id 1 :enabled? true :state moved})]
    (is (true? (get-in result [:state :projectors :projector-10-0-0-5-1 :enabled?])))
    (is (= moved (:state (projectors/handle {:event/type :projectors/set-service-enabled
                                             :host "10.0.0.5" :service-id 1 :enabled? true :state moved})))
        "nothing is at its old address any more")))

(deftest host-edit-moves-a-host-identity-test
  (let [state (assoc-in placed-rig [:projectors :hand] {:name "Hand" :host "10.0.0.20" :stable-id [:host "10.0.0.20" 0]
                                                         :enabled? true})
        edit (fn [id host] (get-in (projectors/handle {:event/type :projectors/update-settings :projector-id id
                                                       :updates {:host host} :state state})
                                   [:state :projectors id :stable-id]))]
    (is (= [:host "10.0.0.21" 0] (edit :hand "10.0.0.21")) "an identity by address follows a new address")
    (is (= [:unit unit-l 1] (edit :projector-10-0-0-5-1 "10.0.0.21")) "a unit's doesn't")))

(deftest new-projectors-have-a-stable-id-test
  (is (= [[:host "10.0.0.5" 0]]
         (map :stable-id (vals (get-in (projectors/handle {:event/type :projectors/add-manual :name "Manual"
                                                           :host "10.0.0.5" :state sample-state})
                                       [:state :projectors])))))
  (is (= {:projector-10-0-0-5-1 [:unit unit-l 1] :projector-10-0-0-5-2 [:unit unit-l 2]}
         (update-vals (get-in (scan base-state (scanned "10.0.0.5" unit-l 1 2)) [:state :projectors]) :stable-id))))


;; Removing a projector


(def ^:private removable-state
  (-> base-state
      (assoc-in [:projectors :p1] {:name "Laser L" :host "10.0.0.5" :port 7255 :service-id 1
                                   :enabled? true :zone-groups [:all]})
      (assoc-in [:projectors :p2] {:name "Laser R" :host "10.0.0.6" :port 7255 :service-id 1
                                   :enabled? true :zone-groups [:all]})
      (assoc-in [:chains :projector-effects :p1 :items] [{:id (random-uuid) :effect-id :rgb-curves :params {}}])
      (assoc-in [:chains :projector-effects :p2 :items] [])
      (assoc-in [:ui :projector-effect-ui-state :p1] {:active-curve-channel :r})
      (assoc-in [:list-ui [:projector-effects :p1]] {:selected-ids #{}})
      (assoc-in [:virtual-projectors :vp1] {:name "VP" :parent-projector-id :p1 :zone-groups [:all]})
      (assoc-in [:virtual-projectors :vp2] {:name "VP2" :parent-projector-id :p2 :zone-groups [:all]})
      (assoc-in [:projector-ui :active-projector] :p1)
      (assoc-in [:projector-ui :calibrating-projector-id] :p1)
      (assoc-in [:projector-ui :discovered-devices]
                [{:address "10.0.0.5" :services [{:service-id 1 :name "A"} {:service-id 2 :name "B"}]}
                 {:address "10.0.0.6" :services [{:service-id 1 :name "C"}]}])))

(deftest request-remove-projector-asks-first-test
  (let [dialog (get-in (projectors/handle {:event/type :projectors/request-remove :projector-id :p1
                                           :state removable-state})
                       [:state :ui :dialogs :confirm])]
    (is (:open? dialog))
    (is (= "Remove Laser L?" (:title dialog)))
    (is (= "Its calibration goes too. If it's still on the network, the next scan finds it again, turned off."
           (:message dialog)))
    (is (= {:event/type :projectors/remove :projector-id :p1} (:confirm-event dialog)))))

(deftest remove-projector-cleans-up-test
  (let [state (:state (projectors/handle {:event/type :projectors/remove :projector-id :p1
                                          :state removable-state}))]
    (is (nil? (get-in state [:projectors :p1])))
    (is (some? (get-in state [:projectors :p2])) "the other projector stays")
    (is (not (contains? (get-in state [:chains :projector-effects]) :p1)))
    (is (not (contains? (get-in state [:ui :projector-effect-ui-state]) :p1)))
    (is (not (contains? (:list-ui state) [:projector-effects :p1])))
    (is (= #{:vp2} (set (keys (:virtual-projectors state)))) "its virtual projectors go with it")
    (is (nil? (get-in state [:projector-ui :active-projector])))
    (is (nil? (get-in state [:projector-ui :calibrating-projector-id])))
    (is (= [{:address "10.0.0.5" :services [{:service-id 2 :name "B"}]}
            {:address "10.0.0.6" :services [{:service-id 1 :name "C"}]}]
           (get-in state [:projector-ui :discovered-devices]))
        "its output leaves the Discover list")))

(deftest remove-unknown-projector-changes-nothing-test
  (is (identical? removable-state
                  (:state (projectors/handle {:event/type :projectors/remove :projector-id :nope
                                              :state removable-state})))))

(deftest remove-takes-the-laser-out-of-the-order-test
  (let [placed (assoc-in removable-state [:rig :order] [:p2 :p1])]
    (is (= [:p2] (get-in (projectors/handle {:event/type :projectors/remove :projector-id :p1 :state placed})
                         [:state :rig :order]))
        "so a scan that finds it again puts it after the placed ones")
    (testing "one undo step, which puts it back in its place"
      (let [removed (:state (events/handle-event-pipeline {:event/type :projectors/remove :projector-id :p1
                                                           :state placed}))
            undone (:state (events/handle-event-pipeline {:event/type :history/undo :state removed}))]
        (is (= [:p2] (get-in removed [:rig :order])))
        (is (= [:p2 :p1] (get-in undone [:rig :order])))
        (is (some? (get-in undone [:projectors :p1])))))))

(deftest remove-projector-stops-its-engine-test
  (let [result (events/handle-event-pipeline {:event/type :projectors/remove :projector-id :p1
                                              :state (assoc-in removable-state [:backend :streaming :running?] true)})]
    (is (= #{:p2} (set (keys (get-in result [:multi-engine/refresh :projectors]))))
        "while streaming, the engines are refreshed without it")
    (is (= "Remove Projector" (get-in result [:state :history :undo-stack 0 :description]))
        "one undo step")))


;; Identify (zones plan Z1)


(def ^:private outputting-state
  "Streaming on, projector :p1 enabled with a host (so the engines are built for it)."
  (-> base-state
      (assoc-in [:projectors :p1] {:name "Laser L" :host "10.0.0.5" :port 7255 :service-id 1 :enabled? true
                                   :zone-groups [:all]})
      (assoc-in [:projectors :p2] {:name "Laser R" :host "10.0.0.6" :port 7255 :service-id 1 :enabled? false
                                   :zone-groups [:all]})
      (assoc-in [:backend :streaming :running?] true)))

(deftest outputting-is-streaming-and-wanted-test
  (is (guard/outputting? outputting-state :p1))
  (is (not (guard/outputting? outputting-state :p2)) "turned off")
  (is (not (guard/outputting? (assoc-in outputting-state [:backend :streaming :running?] false) :p1))
      "streaming is off")
  (is (not (guard/outputting? outputting-state :nope))))

(deftest identify-sets-its-end-time-test
  (let [result (projectors/handle {:event/type :projectors/identify :projector-id :p1 :time 1000
                                   :state outputting-state})]
    (is (= 4000 (get-in result [:state :projector-ui :identify :p1])))
    (is (= {:event {:event/type :projectors/identify-ended :projector-id :p1 :until 4000} :delay-ms 3000}
           (:dispatch-later result)))
    (testing "pressing it again restarts the 3 seconds"
      (is (= 6000 (get-in (projectors/handle {:event/type :projectors/identify :projector-id :p1 :time 3000
                                              :state (:state result)})
                          [:state :projector-ui :identify :p1]))))))

(deftest identify-ended-clears-only-its-own-test
  (let [s (assoc-in outputting-state [:projector-ui :identify :p1] 6000)]
    (is (= 6000 (get-in (projectors/handle {:event/type :projectors/identify-ended :projector-id :p1 :until 4000
                                            :state s})
                        [:state :projector-ui :identify :p1]))
        "an older flash's end leaves a newer flash alone")
    (is (not (contains? (get-in (projectors/handle {:event/type :projectors/identify-ended :projector-id :p1
                                                    :until 6000 :state s})
                                [:state :projector-ui :identify])
                        :p1)))))

(deftest identify-needs-an-outputting-laser-test
  (doseq [[label s id] [["turned off" outputting-state :p2]
                        ["not streaming" (assoc-in outputting-state [:backend :streaming :running?] false) :p1]
                        ["unknown" outputting-state :nope]]]
    (let [result (projectors/handle {:event/type :projectors/identify :projector-id id :time 1000 :state s})]
      (is (identical? s (:state result)) label)
      (is (nil? (:dispatch-later result)) label))))

(deftest identify-asks-while-a-cue-plays-test
  (let [playing (-> outputting-state
                    (assoc-in [:playback :playing?] true)
                    (assoc-in [:playback :active-cues] {[0 0] {:run 1}}))
        asked (projectors/handle {:event/type :projectors/identify :projector-id :p1 :time 1000 :state playing})]
    (is (nil? (get-in asked [:state :projector-ui :identify :p1])) "nothing flashes yet")
    (is (= "Identify Laser L?" (get-in asked [:state :ui :dialogs :confirm :title])))
    (is (= "It shows the test pattern for 3 seconds instead of the show."
           (get-in asked [:state :ui :dialogs :confirm :message])))
    (is (= {:event/type :projectors/identify :projector-id :p1 :confirmed? true}
           (get-in asked [:state :ui :dialogs :confirm :confirm-event]))
        "the parked event carries no state or time")
    (is (= 4000 (get-in (projectors/handle {:event/type :projectors/identify :projector-id :p1 :time 1000
                                            :confirmed? true :state playing})
                        [:state :projector-ui :identify :p1]))
        "confirmed, it flashes")))


;; Safety zone edits (zones plan Z1, D-28)


(def ^:private small {:limit [-0.5 -0.5 0.5 0.5] :blocked []})

(defn- with-zone [state id zone] (assoc-in state [:projectors id :safety] zone))

(defn- set-safety [state id zone & {:as more}]
  (projectors/handle (merge {:event/type :projectors/set-safety :projector-id id :safety zone :state state} more)))

(deftest set-safety-normalizes-test
  (let [result (set-safety base-state :p0 {:limit [0.5 0.5 -0.5 -0.5]})]
    (is (identical? base-state (:state result)) "a missing projector changes nothing"))
  (let [s (assoc-in base-state [:projectors :p1] {:name "Laser L"})
        stored (get-in (set-safety s :p1 {:limit [0.5 0.5 -0.5 -0.5] :blocked [[0 0 1/2 1/4]]})
                       [:state :projectors :p1 :safety])]
    (is (= {:limit [-0.5 -0.5 0.5 0.5] :blocked [[0.0 0.0 0.5 0.25]]} stored)))
  (testing "a zone normalize can't read is refused"
    (let [s (assoc-in base-state [:projectors :p1] {:name "Laser L"})]
      (is (identical? s (:state (set-safety s :p1 {:limit [0 0]})))))))

(deftest shrinking-a-live-zone-applies-at-once-test
  (let [live (with-zone outputting-state :p1 small)
        result (set-safety live :p1 {:limit [-0.4 -0.5 0.5 0.5]})]
    (is (= [-0.4 -0.5 0.5 0.5] (get-in result [:state :projectors :p1 :safety :limit])))
    (is (not (get-in result [:state :ui :dialogs :confirm :open?])))))

(deftest growing-a-live-zone-asks-first-test
  (let [live (with-zone outputting-state :p1 small)
        result (set-safety live :p1 {:limit [-0.6 -0.5 0.5 0.5]})]
    (is (= small (get-in result [:state :projectors :p1 :safety])) "nothing changes yet")
    (is (= "Allow light in a larger area?" (get-in result [:state :ui :dialogs :confirm :title])))
    (is (= "Laser L is on. Light can reach places it couldn't before."
           (get-in result [:state :ui :dialogs :confirm :message])))
    (is (= "Allow" (get-in result [:state :ui :dialogs :confirm :confirm-label])))
    (testing "while the question is open, another widening is dropped"
      (let [again (set-safety (:state result) :p1 {:limit [-0.9 -0.9 0.9 0.9]})]
        (is (= (get-in result [:state :ui :dialogs :confirm]) (get-in again [:state :ui :dialogs :confirm])))
        (is (= small (get-in again [:state :projectors :p1 :safety])))))))

(deftest parked-event-carries-no-state-test
  (let [live (with-zone outputting-state :p1 small)
        parked (get-in (set-safety live :p1 {:limit [-0.6 -0.5 0.5 0.5]} :time 123 :fx/event "x")
                       [:state :ui :dialogs :confirm :confirm-event])]
    (is (= {:event/type :projectors/set-safety :projector-id :p1 :safety {:limit [-0.6 -0.5 0.5 0.5]}
            :confirmed? true}
           parked))))

(deftest confirmed-growth-applies-test
  (let [live (with-zone outputting-state :p1 small)]
    (is (= [-0.6 -0.5 0.5 0.5]
           (get-in (set-safety live :p1 {:limit [-0.6 -0.5 0.5 0.5]} :confirmed? true)
                   [:state :projectors :p1 :safety :limit])))))

(deftest growing-an-off-laser-applies-at-once-test
  (let [off (with-zone (assoc-in outputting-state [:backend :streaming :running?] false) :p1 small)]
    (is (= [-1.0 -1.0 1.0 1.0] (get-in (set-safety off :p1 {:limit [-1 -1 1 1]})
                                       [:state :projectors :p1 :safety :limit])))))

(deftest add-blocked-area-test
  (let [live (with-zone outputting-state :p1 {:limit [-0.8 -0.6 0.8 1.0]})
        added (:state (projectors/handle {:event/type :projectors/add-blocked-area :projector-id :p1 :state live}))]
    (is (= [[-0.2 0.1 0.2 0.3]] (mapv (fn [r] (mapv #(/ (Math/round (* % 1e9)) 1e9) r))
                                      (get-in added [:projectors :p1 :safety :blocked])))
        "0.4 by 0.2 in the middle of the limit, at once: it only shrinks the area")
    (testing "at 8 it does nothing"
      (let [full (with-zone outputting-state :p1 {:blocked (vec (repeat 8 [0 0 0.1 0.1]))})]
        (is (identical? full (:state (projectors/handle {:event/type :projectors/add-blocked-area
                                                         :projector-id :p1 :state full}))))))))

(deftest removing-a-blocked-area-asks-when-live-test
  (let [zone {:limit [-0.9 -0.9 0.9 0.9] :blocked [[-0.2 -0.2 0.2 0.2] [0.5 0.5 0.6 0.6]]}
        remove (fn [s] (projectors/handle {:event/type :projectors/remove-blocked-area :projector-id :p1 :index 1 :state s}))
        live (remove (with-zone outputting-state :p1 zone))]
    (is (= zone (get-in live [:state :projectors :p1 :safety])))
    (is (= "Laser L is on. Light can reach Blocked 2 again." (get-in live [:state :ui :dialogs :confirm :message])))
    (is (= {:event/type :projectors/remove-blocked-area :projector-id :p1 :index 1 :confirmed? true}
           (get-in live [:state :ui :dialogs :confirm :confirm-event])))
    (testing "off, it applies at once"
      (is (= [[-0.2 -0.2 0.2 0.2]]
             (get-in (remove (with-zone (assoc-in outputting-state [:backend :streaming :running?] false) :p1 zone))
                     [:state :projectors :p1 :safety :blocked]))))))

(deftest copy-and-mirror-safety-test
  (let [zone {:limit [-0.7 -0.9 0.8 0.95] :blocked [[-0.9 -1.0 -0.5 -0.4]]}
        s (-> outputting-state
              (with-zone :p1 zone)
              (assoc-in [:projectors :p3] {:name "Laser C" :host "10.0.0.7" :port 7255 :enabled? true})
              (assoc-in [:backend :streaming :running?] false))
        copied (:state (projectors/handle {:event/type :projectors/copy-safety :from :p1 :to [:p2 :p3] :state s}))
        mirrored (:state (projectors/handle {:event/type :projectors/copy-safety :from :p1 :to [:p2] :mirror? true :state s}))]
    (is (= zone (get-in copied [:projectors :p2 :safety])))
    (is (= zone (get-in copied [:projectors :p3 :safety])))
    (is (= {:limit [-0.8 -0.9 0.7 0.95] :blocked [[0.5 -1.0 0.9 -0.4]]} (get-in mirrored [:projectors :p2 :safety])))
    (testing "to live lasers it would widen, one question for all of them"
      (let [live (-> s
                     (assoc-in [:backend :streaming :running?] true)
                     (assoc-in [:projectors :p2 :enabled?] true)
                     (with-zone :p2 small)
                     (with-zone :p3 small))
            asked (projectors/handle {:event/type :projectors/copy-safety :from :p1 :to [:p2 :p3] :state live})]
        (is (= "Allow light in a larger area on Laser R and Laser C?"
               (get-in asked [:state :ui :dialogs :confirm :title])))
        (is (= small (get-in asked [:state :projectors :p2 :safety])))
        (is (= zone (get-in (projectors/handle (assoc (get-in asked [:state :ui :dialogs :confirm :confirm-event])
                                                      :state live))
                            [:state :projectors :p3 :safety])))))))

(deftest safety-events-on-a-missing-projector-change-nothing-test
  (doseq [event [{:event/type :projectors/set-safety :projector-id :nope :safety small}
                 {:event/type :projectors/add-blocked-area :projector-id :nope}
                 {:event/type :projectors/remove-blocked-area :projector-id :nope :index 0}
                 {:event/type :projectors/copy-safety :from :nope :to [:p1]}]]
    (is (identical? outputting-state (:state (projectors/handle (assoc event :state outputting-state))))
        (str (:event/type event)))))

