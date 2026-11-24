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
   [laser-show.animation.fit-guard :as fit-guard]
   [laser-show.output.driver :as driver]
   [laser-show.routing.refs :as refs]
   [laser-show.routing.rig :as rig]
   [laser-show.vibes.library :as library]
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

(deftest set-tag-keeps-a-set-test
  (let [tag (fn [state t on?] (:state (projectors/handle {:event/type :projectors/set-tag :projector-id test-projector-id
                                                          :tag t :on? on? :state state})))
        tags #(get-in % [:projectors test-projector-id :tags])
        old (assoc-in state-with-projector [:projectors test-projector-id :tags] [:graphics])
        both (tag old :crowd-scanning true)]
    (is (= #{:graphics :crowd-scanning} (tags both)))
    (is (set? (tags both)) "an old file's vector is kept as a set")
    (is (= #{:crowd-scanning} (tags (tag both :graphics false))))
    (is (identical? both (tag both :graphics true)) "it sets, never toggles")
    (is (identical? both (tag both :graphics "yes")))
    (is (identical? both (:state (projectors/handle {:event/type :projectors/set-tag :projector-id :nope
                                                     :tag :graphics :on? true :state both}))))
    (testing "the vibes read it"
      (is (contains? (library/audience-groups (tag state-with-projector :crowd-scanning true)) :all)
          "a scanner in All makes every All cue an audience cue"))))

(deftest set-exclude-from-all-test
  (let [excluded (fn [state on?] (:state (projectors/handle {:event/type :projectors/set-exclude-from-all
                                                             :projector-id test-projector-id :exclude? on?
                                                             :state state})))
        out (excluded state-with-projector true)]
    (is (true? (get-in out [:projectors test-projector-id :exclude-from-all?])))
    (is (= [:auto.laser/proj-1]
           (rig/refs-for (excluded (assoc-in state-with-projector [:projectors test-projector-id :zone-groups] []) true)
                         test-projector-id))
        "out of All and the automatic groups: only its own group reaches it")
    (is (false? (get-in (excluded out false) [:projectors test-projector-id :exclude-from-all?])) "and back in")
    (is (identical? out (excluded out true)) "it sets, never toggles")
    (is (identical? out (excluded out "yes")))
    (is (identical? state-with-projector
                    (:state (projectors/handle {:event/type :projectors/set-exclude-from-all :projector-id :nope :exclude? true
                                                :state state-with-projector}))))))

(deftest set-fit-clamps-and-is-undoable-test
  (let [set-fit (fn [state k v] (:state (projectors/handle {:event/type :projectors/set-fit :projector-id test-projector-id
                                                            :key k :value v :state state})))
        fit #(get-in % [:projectors test-projector-id :fit])
        out (set-fit state-with-projector :min-fps 30)]
    (is (= (assoc fit-guard/defaults :min-fps 30) (fit out)) "stored whole, the defaults for the rest")
    (testing "clamped to its range"
      (is (= [60 5] [(:min-fps (fit (set-fit out :min-fps 100))) (:min-fps (fit (set-fit out :min-fps 1)))]))
      (is (= 0.3 (:point-floor (fit (set-fit out :point-floor 0.1)))))
      (is (= 1.0 (:dim-floor (fit (set-fit out :dim-floor 3))))))
    (is (true? (:dim? (fit (set-fit out :dim? true)))))
    (is (false? (:reduce-points? (fit (set-fit out :reduce-points? false)))))
    (testing "a value of the wrong type, another key, an unknown projector or the value it has: nothing changes"
      (is (identical? out (set-fit out :min-fps "fast")))
      (is (identical? out (set-fit out :min-fps ##NaN)))
      (is (identical? out (set-fit out :dim? "yes")))
      (is (identical? out (set-fit out :colour 1)))
      (is (identical? out (set-fit out :min-fps 30)))
      (is (identical? out (:state (projectors/handle {:event/type :projectors/set-fit :projector-id :nope
                                                      :key :min-fps :value 25 :state out})))))
    (testing "one undo step, named, saved as the projector's other settings are, and no engine restarts"
      (let [pipeline #(events/handle-event-pipeline (assoc %2 :state %1))
            start (-> state-with-projector history/mark-clean (assoc-in [:backend :streaming :running?] true))
            result (pipeline start {:event/type :projectors/set-fit :projector-id test-projector-id :key :min-fps :value 25})
            edited (:state result)
            tagged (:state (pipeline start {:event/type :projectors/set-tag :projector-id test-projector-id
                                            :tag :crowd-scanning :on? true}))]
        (is (= 25 (:min-fps (fit edited))))
        (is (= "Change Fit Guard" (history/undo-label (:history edited))))
        (is (some? (get-in tagged [:projectors test-projector-id :tags])))
        (is (= (boolean (get-in tagged [:project :dirty?])) (boolean (get-in edited [:project :dirty?])))
            "the show unsaved or not as a tag edit leaves it: :fit is saved with the projector (zones ZR saves the projectors with the rig)")
        (is (nil? (:multi-engine/refresh result)) "fit is no part of an engine's fingerprint")
        (is (nil? (fit (:state (pipeline edited {:event/type :history/undo})))))))))

(deftest set-enabled-turns-an-output-on-or-off-test
  (let [off (assoc-in state-with-projector [:projectors test-projector-id :enabled?] false)
        set-enabled (fn [state id on?] (:state (projectors/handle {:event/type :projectors/set-enabled :projector-id id
                                                                   :enabled? on? :state state})))]
    (is (true? (get-in (set-enabled off test-projector-id true) [:projectors test-projector-id :enabled?])))
    (is (false? (get-in (set-enabled state-with-projector test-projector-id false) [:projectors test-projector-id :enabled?])))
    (is (identical? off (set-enabled off test-projector-id false)) "it sets, never toggles: off stays off")
    (is (identical? off (set-enabled off :nope true)) "an unknown projector")
    (is (identical? off (set-enabled off test-projector-id "yes")) "only true or false")
    (testing "while streaming, the engines follow"
      (is (contains? (get-in (events/handle-event-pipeline {:event/type :projectors/set-enabled :projector-id test-projector-id
                                                            :enabled? true :state (assoc-in off [:backend :streaming :running?] true)})
                             [:multi-engine/refresh :projectors])
                     test-projector-id)))))

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
      
      ;; Calibration is the rig's: undoable, saved by the rig file, not an unsaved show change (zones plan ZR)
      (is (history/undoable-changed? (history/extract-document-state (:state event))
                                     (history/extract-document-state (:state result))))
      (is (false? (history/derive-dirty (:state event) (:state result))))))
  
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
    (is (false? (get-in result [:state :projector-ui :scanning?])) "Scan is back")
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

(deftest remove-retargets-the-lasers-pins-test
  (let [pinned (-> removable-state
                   (assoc-in [:chains :cue-chains [0 0]] {:name "Intro" :target :auto.laser/p1
                                                          :items []})
                   (assoc-in [:chains :cue-chains [1 0]] {:name "Drop" :items [{:id "i" :type :preset :preset-id :circle
                                                                               :target :auto.laser/p1}]})
                   (assoc-in [:chains :cue-chains [2 0]] {:name "Other" :items [{:id "j" :type :preset :preset-id :circle
                                                                                :target :auto.laser/p2}]}))
        removed (fn [& {:as opts}] (:state (projectors/handle (merge {:event/type :projectors/remove :projector-id :p1
                                                                     :state pinned}
                                                                    opts))))]
    (testing "asked first: how many cues are pinned to it, and where they go (All by default)"
      (let [dialog (get-in (projectors/handle {:event/type :projectors/request-remove :projector-id :p1 :state pinned})
                           [:state :ui :dialogs :confirm])
            options (mapv :value (get-in dialog [:choice :options]))]
        (is (= (str "Its calibration goes too. If it's still on the network, the next scan finds it again, turned off. "
                    "2 cues are pinned to it.")
               (:message dialog)))
        (is (= "A1 Intro · A2 Drop" (:details dialog)))
        (is (= {:key :retarget-to :label "Send its cues to:"} (select-keys (:choice dialog) [:key :label])))
        (is (= :all (:choice-value dialog)))
        (is (= [:all :auto/left] (take 2 options)))
        (is (some #{:auto.laser/p2} options) "another laser can take them")
        (is (not (some #{:auto.laser/p1} options)) "never the laser that goes")
        (is (= :inherit (peek options)))
        (is (= {:event/type :projectors/remove :projector-id :p1} (:confirm-event dialog)))))
    (testing "removed, its cues go where you chose"
      (let [state (removed :retarget-to :auto/left)]
        (is (= :auto/left (get-in state [:chains :cue-chains [0 0] :target])))
        (is (= :auto/left (get-in state [:chains :cue-chains [1 0] :items 0 :target])))
        (is (= :auto.laser/p2 (get-in state [:chains :cue-chains [2 0] :items 0 :target])) "another laser's stay")
        (is (empty? (refs/cues-using (get-in state [:chains :cue-chains]) :auto.laser/p1))
            "and nothing names it any more (invariant 12)")
        (is (= "Removed Laser L · 2 cues now go to Left" (get-in state [:ui :notice :text])))))
    (testing "with no choice made, to All; Nothing lets them inherit"
      (is (= :all (get-in (removed) [:chains :cue-chains [0 0] :target])))
      (is (= :all (get-in (removed :retarget-to :auto.laser/p1) [:chains :cue-chains [1 0] :items 0 :target]))
          "not to itself")
      (is (not (contains? (get-in (removed :retarget-to :inherit) [:chains :cue-chains [1 0] :items 0]) :target))))
    (testing "a laser no cue is pinned to asks as before, and removes as before"
      (is (nil? (get-in (projectors/handle {:event/type :projectors/request-remove :projector-id :p1 :state removable-state})
                        [:state :ui :dialogs :confirm :choice])))
      (is (nil? (get-in (projectors/handle {:event/type :projectors/remove :projector-id :p1 :state removable-state})
                        [:state :ui :notice]))))))

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
                 {:event/type :projectors/copy-safety :from :nope :to [:p1]}
                 {:event/type :projectors/set-safety-coordinate :projector-id :nope :path [:limit 0] :value -0.4}
                 {:event/type :projectors/reset-safety :projector-id :nope}]]
    (is (identical? outputting-state (:state (projectors/handle (assoc event :state outputting-state))))
        (str (:event/type event)))))

(deftest a-drag-leaves-a-zone-that-cant-be-read-test
  ;; plans/2026-11-23-cljfx-reactivity/08 §4: Reset is its own event now, so
  ;; a whole zone from the canvas never replaces one that can't be read
  (let [s (with-zone (assoc-in outputting-state [:backend :streaming :running?] false) :p1 {:limit [0 0]})]
    (is (identical? s (:state (set-safety s :p1 small))))))


;; One coordinate of a zone (plans/2026-11-23-cljfx-reactivity/08)


(def ^:private two-rows
  {:limit [-0.5 -0.5 0.5 0.5] :blocked [[-0.2 -0.2 0.2 0.2] [0.3 0.3 0.4 0.4]]})

(defn- coord
  "A coordinate field's edit of :p1's zone (more: :row-version, …)."
  [path value & {:as more}]
  (merge {:event/type :projectors/set-safety-coordinate :projector-id :p1 :path path :value value} more))

(defn- handle-all
  "state after each event, through the handler alone."
  [state events]
  (reduce #(:state (projectors/handle (assoc %2 :state %1))) state events))

(defn- run-pipeline
  "state after event and what it dispatches inline, through the real
   pipeline, as the agent runs them."
  [state event]
  (let [effects (events/handle-event-pipeline (assoc event :state state :time 1000))
        s (or (:state effects) state)]
    (if-let [next-event (:dispatch effects)]
      (run-pipeline s next-event)
      s)))

(deftest coordinate-intent-validates-current-zone-test
  (let [off (assoc-in outputting-state [:backend :streaming :running?] false)
        zone-of #(get-in % [:projectors :p1 :safety])]
    (testing "two fields from one render: each is set in the zone stored then, in either order"
      (doseq [[label s0 events expected]
              [["the limit's x0 and y1" (with-zone off :p1 small)
                [(coord [:limit 0] -0.4) (coord [:limit 3] 0.3)]
                {:limit [-0.4 -0.5 0.5 0.3] :blocked []}]
               ["the limit and a blocked area" (with-zone off :p1 two-rows)
                [(coord [:limit 1] -0.4) (coord [:blocked 1 2] 0.45 :row-version 0)]
                {:limit [-0.5 -0.4 0.5 0.5] :blocked [[-0.2 -0.2 0.2 0.2] [0.3 0.3 0.45 0.4]]}]
               ["two fields of one row" (with-zone off :p1 two-rows)
                [(coord [:blocked 0 0] -0.3 :row-version 0) (coord [:blocked 0 3] 0.25 :row-version 0)]
                {:limit [-0.5 -0.5 0.5 0.5] :blocked [[-0.3 -0.2 0.2 0.25] [0.3 0.3 0.4 0.4]]}]]
              order [identity reverse]]
        (let [s (handle-all s0 (order events))]
          (is (= expected (zone-of s)) label)
          (is (= (get-in s0 [:projectors :p2]) (get-in s [:projectors :p2])) (str label ": the other projector stays")))))
    (testing "the same field twice: the later one"
      (is (= [-0.3 -0.5 0.5 0.5]
             (:limit (zone-of (handle-all (with-zone off :p1 small) [(coord [:limit 0] -0.4) (coord [:limit 0] -0.3)]))))))
    (testing "the value it has already: nothing happens"
      (let [s (with-zone off :p1 small)]
        (is (identical? s (:state (projectors/handle (assoc (coord [:limit 0] -0.5) :state s))))))
      (testing "and a zone that is the default without being written isn't written"
        (let [s (update-in off [:projectors :p1] dissoc :safety)]
          (is (identical? s (:state (projectors/handle (assoc (coord [:limit 2] 1.0) :state s))))))))
    (testing "a zone left as the default can be edited"
      (is (= {:limit [-0.5 -1.0 1.0 1.0] :blocked []}
             (zone-of (handle-all (update-in off [:projectors :p1] dissoc :safety) [(coord [:limit 0] -0.5)])))))
    (testing "refused, the zone and everything else as it was"
      (let [s (with-zone off :p1 two-rows)]
        (doseq [[label event]
                [["no vector" (coord '(:limit 0) -0.4)]
                 ["a keyword" (coord :limit -0.4)]
                 ["no path" (coord nil -0.4)]
                 ["too short" (coord [:limit] -0.4)]
                 ["one key too many" (coord [:limit 0 0] -0.4)]
                 ["a blocked area's, too short" (coord [:blocked 0] -0.4 :row-version 0)]
                 ["a blocked area's, too long" (coord [:blocked 0 0 0] -0.4 :row-version 0)]
                 ["anywhere else in the state" (coord [:projectors :p2 :safety] -0.4)]
                 ["another key of the zone" (coord [:limits 0] -0.4)]
                 ["a fifth corner" (coord [:limit 4] -0.4)]
                 ["a negative corner" (coord [:limit -1] -0.4)]
                 ["a corner that isn't an integer" (coord [:limit 0.0] -0.4)]
                 ["a corner as text" (coord [:limit "0"] -0.4)]
                 ["the row after the last" (coord [:blocked 2 0] -0.4 :row-version 0)]
                 ["a negative row" (coord [:blocked -1 0] -0.4 :row-version 0)]
                 ["a row that isn't an integer" (coord [:blocked 0.0 0] -0.3 :row-version 0)]
                 ["a blocked area's fifth corner" (coord [:blocked 0 4] -0.3 :row-version 0)]
                 ["another row layout" (coord [:blocked 0 0] -0.3 :row-version 7)]
                 ["no row layout" (coord [:blocked 0 0] -0.3)]
                 ["text" (coord [:limit 0] "-0.4")]
                 ["nil" (coord [:limit 0] nil)]
                 ["NaN" (coord [:limit 0] ##NaN)]
                 ["infinity" (coord [:limit 0] ##Inf)]
                 ["minus infinity" (coord [:limit 0] ##-Inf)]
                 ["past 1" (coord [:limit 2] 1.5)]
                 ["past -1" (coord [:limit 0] -1.01)]
                 ["x0 past x1: the edges would swap" (coord [:limit 0] 0.6)]
                 ["a blocked area's y1 under its y0" (coord [:blocked 0 3] -0.3 :row-version 0)]
                 ["a limit with no width: it can't be read" (coord [:limit 0] 0.5)]
                 ["a limit with no height: it can't be read" (coord [:limit 3] -0.5)]
                 ["a projector that isn't there" (assoc (coord [:limit 0] -0.4) :projector-id :nope)]]]
          (is (identical? s (:state (projectors/handle (assoc event :state s)))) label)))
      (testing "a zone that can't be read: only Reset replaces it"
        (let [s (with-zone off :p1 {:limit [0 0]})]
          (is (identical? s (:state (projectors/handle (assoc (coord [:limit 3] 0.3) :state s)))))
          (is (= {:limit [-1.0 -1.0 1.0 1.0] :blocked []}
                 (zone-of (handle-all s [{:event/type :projectors/reset-safety :projector-id :p1}])))))))
    (testing "a ninth blocked area from an older rig stays, edited or not"
      (let [nine {:limit [-1.0 -1.0 1.0 1.0] :blocked (vec (for [i (range 9)] [(* 0.1 i) 0.0 (+ 0.05 (* 0.1 i)) 0.05]))}
            s (handle-all (with-zone off :p1 nine) [(coord [:blocked 8 3] 0.1 :row-version 0) (coord [:limit 0] -0.9)])]
        (is (= 9 (count (:blocked (zone-of s)))))
        (is (= [0.8 0.0 0.85 0.1] (mapv #(/ (Math/round (* % 1e9)) 1e9) (get-in (zone-of s) [:blocked 8]))))
        (is (= (subvec (:blocked nine) 0 8) (subvec (:blocked (zone-of s)) 0 8)))))
    (testing "through the pipeline: an old row's edit never lands on another row"
      (let [s0 (with-zone off :p1 two-rows)
            v0 (guard/rows-version s0 :p1)
            old-edits [(coord [:blocked 0 0] -0.3 :row-version v0) (coord [:blocked 0 3] 0.25 :row-version v0)]
            old-edit (first old-edits)
            old-second-row (coord [:blocked 1 0] 0.35 :row-version v0)
            untouched? (fn [s label]
                         (doseq [event old-edits]
                           (is (= s (run-pipeline s event)) (str label ": the old edit changes nothing")))
                         (is (= s (run-pipeline s old-second-row)) (str label ": no row is made")))]
        (testing "after Blocked 1 is removed (Blocked 2 is first now)"
          (let [removed (run-pipeline s0 {:event/type :projectors/remove-blocked-area :projector-id :p1 :index 0})]
            (is (= [[0.3 0.3 0.4 0.4]] (:blocked (zone-of removed))))
            (untouched? removed "removed")
            (is (= [[0.25 0.3 0.4 0.4]]
                   (:blocked (zone-of (run-pipeline removed (coord [:blocked 0 0] 0.25 :row-version (guard/rows-version removed :p1))))))
                "an edit from the editor as it is now lands")))
        (testing "after a whole zone with as many rows"
          (untouched? (run-pipeline s0 {:event/type :projectors/set-safety :projector-id :p1
                                        :safety (update two-rows :blocked (comp vec reverse))})
                      "replaced"))
        (testing "after Undo puts back the rows"
          (let [edited (run-pipeline s0 (coord [:blocked 1 2] 0.45 :row-version v0))
                undone (run-pipeline edited {:event/type :edit/undo})]
            (is (= v0 (guard/rows-version edited :p1)) "an edit of one number keeps the rows")
            (is (= two-rows (zone-of undone)))
            (untouched? undone "undone")))
        (testing "a change to the limit only keeps the rows' layout"
          (let [limited (run-pipeline s0 {:event/type :projectors/set-safety :projector-id :p1
                                          :safety (assoc two-rows :limit [-0.6 -0.5 0.5 0.5])})]
            (is (= v0 (guard/rows-version limited :p1)))
            (is (not= (guard/geometry-version s0 :p1) (guard/geometry-version limited :p1)))
            (is (= [[-0.3 -0.2 0.2 0.2] [0.3 0.3 0.4 0.4]] (:blocked (zone-of (run-pipeline limited old-edit))))
                "an edit from before still lands on its row")))
        (testing "a projector removed loses its versions; put back, it gets new ones"
          (let [stamped (run-pipeline s0 (coord [:limit 0] -0.4))
                gone (run-pipeline stamped {:event/type :projectors/remove :projector-id :p1})
                back (run-pipeline gone {:event/type :edit/undo})]
            (is (pos? (guard/geometry-version stamped :p1)))
            (is (not (contains? (get-in gone [:projector-ui :safety-versions :by-id]) :p1)))
            (is (< (guard/geometry-version stamped :p1) (guard/geometry-version back :p1)))
            (is (not= v0 (guard/rows-version back :p1)))
            (untouched? back "put back")))
        (testing "a projector removed: nothing makes it again"
          (let [gone (run-pipeline s0 {:event/type :projectors/remove :projector-id :p1})]
            (doseq [event [old-edit (coord [:limit 0] -0.4) {:event/type :projectors/reset-safety :projector-id :p1}]]
              (is (not (contains? (:projectors (run-pipeline gone event)) :p1)) (pr-str (:path event))))))))))



;; Projectors with another output driver (plans/2026-11-19-output-drivers,
;; D2: 01 §5.1, §5.3, §5.4, OD-11)


(def ^:private fields-descriptor
  "A driver with every field type, for the coercion table (no registry
   needed: handlers only read the descriptors in state)."
  {:id :test/fields :name "Test fields" :transport :other
   :fields [{:key :host :label "Host" :type :host :required? true}
            {:key :port :label "Port" :type :int :min 1 :max 65535 :default 7266}
            {:key :mode :label "Mode" :type :choice :options [[:a "A"] [:b "B"]] :default :a}
            {:key :fast? :label "Fast" :type :boolean}]
   :capabilities {:scan-rate {:min 5000 :max 30000 :default 30000}}
   :discover? false :addon nil})

(def ^:private driver-state
  "The built-in drivers' descriptors in state, as :addons/loaded writes them,
   and the test driver's."
  (assoc-in base-state [:backend :output :drivers]
            (assoc (driver/descriptors) :test/fields fields-descriptor)))

(defn- add-manual [state event]
  (projectors/handle (assoc event :event/type :projectors/add-manual :state state)))

(defn- only-projector [result]
  (let [[[id p] & more] (seq (get-in result [:state :projectors]))]
    (assert (nil? more))
    [id p]))

(deftest add-manual-lsps-projector-test
  (let [open (assoc-in driver-state [:ui :dialogs :add-projector-manual] {:open? true :driver :lsps})
        result (add-manual open {:driver :lsps :name "Bridge" :driver-config {:host "127.0.0.1" :port "7266"}})
        [id p] (only-projector result)]
    (is (= {:name "Bridge" :driver :lsps
            :driver-config {:host "127.0.0.1" :port 7266 :max-packet-bytes 1400}
            :stable-id [:manual :lsps id]
            :zone-groups [] :exclude-from-all? false :tags #{} :enabled? true :scan-rate 25000}
           p)
        "01 §5.1's shape: no :host, :port or :output-config, the field defaults filled in")
    (is (= id (get-in result [:state :projector-ui :active-projector])) "selected")
    (is (= 2 (count (get-in result [:state :chains :projector-effects id :items]))) "with the default calibration")
    (is (= {:open? false} (get-in result [:state :ui :dialogs :add-projector-manual])) "the dialog closed and cleared")
    (testing "without a name, it is its driver's short name and the first number no projector's name has"
      (is (= "LSPS 1"
             (:name (second (only-projector (add-manual driver-state {:driver :lsps :driver-config {:host " 10.0.0.9 " :port 7300}}))))))
      (is (= "LSPS 1"
             (:name (second (only-projector (add-manual driver-state {:driver :lsps :name "  " :driver-config {:host "10.0.0.9"}}))))))
      (let [taken (assoc driver-state :projectors {:a {:name "LSPS 1"} :b {:name "LSPS 3"} :c {:name "IDN 2"}})
            new-name (fn [state event]
                       (let [before (set (keys (:projectors state)))]
                         (some (fn [[id p]] (when-not (before id) (:name p)))
                               (get-in (add-manual state event) [:state :projectors]))))]
        (is (= "LSPS 2" (new-name taken {:driver :lsps :driver-config {:host "h"}})) "unique among the projectors")
        (is (= "Test fields 1" (new-name taken {:driver :test/fields :driver-config {:host "h"}}))
            "a driver without a :short-name: its name")))
    (testing "fields that don't coerce, or a driver that isn't installed, add nothing"
      (doseq [[label event] [["no host" {:driver :lsps :driver-config {:port "7266"}}]
                             ["a port out of range" {:driver :lsps :driver-config {:host "h" :port "99999"}}]
                             ["a packet size that isn't a number" {:driver :lsps :driver-config {:host "h" :max-packet-bytes "big"}}]
                             ["not installed" {:driver :acme/nova :driver-config {:serial "NV-00123"}}]]]
        (is (identical? driver-state (:state (add-manual driver-state event))) label)))
    (testing "it starts at the driver's default scan rate, in its range"
      (is (= 30000 (:scan-rate (second (only-projector (add-manual driver-state {:driver :test/fields :driver-config {:host "h"}})))))))))

(deftest add-manual-idn-unchanged-test
  (doseq [event [{:name "Stage left" :host "10.0.0.5" :port 7300}
                 {:name "Stage left" :host "10.0.0.5" :port 7300 :driver :idn}]]
    (testing (pr-str event)
      (is (= {:name "Stage left" :host "10.0.0.5" :port 7300 :service-id nil :service-name nil :unit-id nil
              :stable-id [:host "10.0.0.5" 0] :zone-groups [] :exclude-from-all? false :tags #{}
              :enabled? true :scan-rate 25000 :output-config {:color-bit-depth 8 :xy-bit-depth 16}}
             (second (only-projector (add-manual driver-state event))))
          "today's shape exactly: no :driver, no :driver-config")))
  (is (thrown? AssertionError (add-manual driver-state {:driver :idn :host " "})) "and still a host"))

(defn- with-lsps
  "driver-state with an LSPS projector :b at 127.0.0.1:7266, and an IDN one :a."
  []
  (-> driver-state
      (assoc-in [:projectors :b] {:name "Bridge" :driver :lsps
                                  :driver-config {:host "127.0.0.1" :port 7266 :max-packet-bytes 1400}
                                  :stable-id [:manual :lsps :b] :zone-groups [] :exclude-from-all? false
                                  :tags #{} :enabled? true :scan-rate 25000})
      (assoc-in [:projectors :t] {:name "Fields" :driver :test/fields :driver-config {:host "h"}
                                  :stable-id [:manual :test/fields :t] :enabled? true :scan-rate 30000})
      (assoc-in [:projectors :a] {:name "IDN" :host "10.0.0.5" :port 7255 :enabled? true})))

(defn- edit-config [state id k v]
  (:state (projectors/handle {:event/type :projectors/update-driver-config :projector-id id :key k :value v :state state})))

(deftest update-driver-config-validates-test
  (let [s (with-lsps)
        config #(get-in % [:projectors %2 :driver-config])]
    (testing "01 §5.4's table: only an {:ok v} applies"
      (doseq [[id k input expected] [[:b :port "7300" 7300]
                                     [:b :port 7301 7301]
                                     [:b :port "99999" 7266]
                                     [:b :port "seven" 7266]
                                     [:b :host "  " "127.0.0.1"]
                                     [:b :host " 10.0.0.9 " "10.0.0.9"]
                                     [:t :mode :b :b]
                                     [:t :mode "b" "b-is-not-a-value"]
                                     [:t :mode :c nil]
                                     [:t :fast? true true]
                                     [:t :fast? "maybe" nil]]]
        (testing (pr-str [id k input])
          (let [after (edit-config s id k input)]
            (if (= "b-is-not-a-value" expected)
              (is (identical? s after) "a choice is one of its values")
              (if (= (get-in s [:projectors id :driver-config k]) expected)
                (is (identical? s after) "unchanged")
                (is (= expected (get (config after id) k)))))))))
    (testing "an unset optional field goes, so its default applies"
      (is (= {:host "h"} (config (edit-config (edit-config s :t :port "7400") :t :port "") :t))))
    (testing "a key no field has, an IDN projector, an unknown one, a driver that isn't installed: nothing"
      (is (identical? s (edit-config s :b :colour "red")))
      (is (identical? s (edit-config s :a :port "7300")))
      (is (identical? s (edit-config s :nope :port "7300")))
      (let [missing (assoc-in s [:projectors :b :driver] :acme/nova)]
        (is (identical? missing (edit-config missing :b :port "7300")) "its settings are kept as they are (OD-10)")))))

(deftest update-driver-config-is-undoable-test
  (let [pipeline #(events/handle-event-pipeline (assoc %2 :state %1))
        start (-> (with-lsps) history/mark-clean (assoc-in [:backend :streaming :running?] true))
        result (pipeline start {:event/type :projectors/update-driver-config :projector-id :b :key :port :value "7300"})
        edited (:state result)]
    (is (= 7300 (get-in edited [:projectors :b :driver-config :port])))
    (is (= "Change Output Settings" (history/undo-label (:history edited))))
    (is (= (get-in edited [:projectors :b]) (get-in result [:multi-engine/refresh :projectors :b]))
        "while streaming, its engine restarts with the new config")
    (let [undone (pipeline edited {:event/type :history/undo})]
      (is (= 7266 (get-in undone [:state :projectors :b :driver-config :port])) "Undo puts it back")
      (is (= 7266 (get-in undone [:multi-engine/refresh :projectors :b :driver-config :port]))
          "and the engine follows"))
    (testing "an invalid value is no undo step"
      (is (nil? (history/undo-label (:history (:state (pipeline start {:event/type :projectors/update-driver-config
                                                                        :projector-id :b :key :port :value "0"})))))))))

(deftest driver-never-changes-after-creation-test
  (let [s (with-lsps)
        update-settings #(:state (projectors/handle {:event/type :projectors/update-settings :projector-id %2
                                                     :updates %3 :state %1}))]
    (is (nil? (get-in (update-settings s :a {:driver :lsps :name "Renamed"}) [:projectors :a :driver]))
        "an IDN projector stays IDN")
    (is (= "Renamed" (get-in (update-settings s :a {:driver :lsps :name "Renamed"}) [:projectors :a :name]))
        "the other updates still apply")
    (is (= :lsps (get-in (update-settings s :b {:driver :acme/nova}) [:projectors :b :driver])))
    (is (= (get-in s [:projectors :b :driver-config])
           (get-in (update-settings s :b {:driver-config {:port 1}}) [:projectors :b :driver-config]))
        "its settings change only through update-driver-config, which checks them")
    (is (identical? s (edit-config s :b :driver :idn)) "update-driver-config has no :driver field")
    (is (identical? s (edit-config s :a :driver :lsps)))))

(deftest manual-driver-projector-stable-id-test
  (let [first-added (:state (add-manual driver-state {:driver :lsps :name "A" :driver-config {:host "127.0.0.1"}}))
        _ (Thread/sleep 2)
        both (:state (add-manual first-added {:driver :lsps :name "B" :driver-config {:host "127.0.0.1"}}))
        sids (into {} (map (fn [[id p]] [id (:stable-id p)])) (:projectors both))]
    (is (= 2 (count sids)))
    (is (every? (fn [[id sid]] (= [:manual :lsps id] sid)) sids) "each its own id: never shared, never a host")
    (is (= sids (rig/settled-ids (:projectors both))))
    (testing "a scan never moves, upgrades or replaces them"
      (let [scanned-state (:state (scan both (scanned "127.0.0.1" nil) (scanned "10.0.0.5" unit-l 1)))]
        (is (= (:projectors both) (select-keys (:projectors scanned-state) (keys sids))))
        (is (= 4 (count (:projectors scanned-state))) "the IDN devices are new projectors of their own, even the one on the bridge's host")))
    (testing "two that share one (a copied file) are settled apart by their own ids, not to [:host nil 0]"
      (let [copied (assoc-in (:projectors both) [(first (keys sids)) :stable-id] (val (second (seq sids))))]
        (is (= sids (rig/settled-ids copied)))))
    (is (= "Added by hand: a scan doesn't look for it" (rig/identity-label (val (first (:projectors both))))))))

(deftest scan-rate-clamps-to-driver-capabilities-test
  (let [s (with-lsps)
        rate (fn [id r] (get-in (projectors/handle {:event/type :projectors/update-settings :projector-id id
                                                    :updates {:scan-rate r} :state s})
                                [:state :projectors id :scan-rate]))]
    (is (= [5000 30000 20000] [(rate :t 2000) (rate :t 40000) (rate :t 20000)]) "into the driver's 5 000–30 000")
    (is (= [1000 100000] [(rate :b 500) (rate :b 200000)]) "LSPS: 1 000–100 000")
    (is (= [1000 100000] [(rate :a 500) (rate :a 200000)]) "IDN as before")
    (is (= 30000 (rate :t "fast")) "a non-number is dropped")
    (testing "a driver that isn't installed: only the core's range"
      (is (= 40000 (get-in (projectors/handle {:event/type :projectors/update-settings :projector-id :t
                                               :updates {:scan-rate 40000}
                                               :state (update-in s [:backend :output :drivers] dissoc :test/fields)})
                           [:state :projectors :t :scan-rate]))))))

(deftest live-lsps-projector-asks-before-a-larger-zone-test
  (let [live (-> (with-lsps)
                 (assoc-in [:projectors :b :safety] small)
                 (assoc-in [:backend :streaming :running?] true))
        result (projectors/handle {:event/type :projectors/set-safety :projector-id :b
                                   :safety {:limit [-1 -1 1 1]} :state live})]
    (is (guard/outputting? live :b) "01 §5.5: wanted without a host")
    (is (= small (get-in result [:state :projectors :b :safety])) "not applied yet")
    (is (true? (get-in result [:state :ui :dialogs :confirm :open?])) "Z1 asks first")))


;; Scan finds every driver's devices (output drivers D4, 01 §5.3)


(def ^:private nova-descriptor
  {:id :acme/nova :name "Acme Nova (test)" :short-name "Nova" :transport :usb
   :fields [{:key :serial :label "Serial" :type :string :required? true :summary? true}
            {:key :brightness :label "Brightness" :type :int :default 50}]
   :capabilities {:scan-rate {:min 1000 :max 30000 :default 30000}}
   :discover? true :addon nil})

(def ^:private zeta-descriptor
  {:id :zeta/beam :name "Zeta beam (test)" :transport :network
   :fields [{:key :host :label "Host" :type :host} {:key :port :label "Port" :type :int :default 7300}]
   :discover? true :addon nil})

(def ^:private discovery-state
  (assoc-in base-state [:backend :output :drivers]
            (assoc (driver/descriptors) :acme/nova nova-descriptor :zeta/beam zeta-descriptor)))

(defn- nova
  "A device as the scan reports one of :acme/nova's, with these outputs."
  [key & outputs]
  {:driver :acme/nova :device-key key :name (str "Nova " key)
   :outputs (mapv (fn [o] {:output-id o :name (str "Output " o) :driver-config {:serial key :output o}}) outputs)})

(defn- zeta [key host]
  {:driver :zeta/beam :device-key key :outputs [{:output-id 0 :driver-config {:host host}}]})

(defn- scan-d
  ([state devices] (scan-d state devices []))
  ([state devices warnings]
   (projectors/handle {:event/type :projectors/scan-complete :devices (vec devices) :warnings warnings :state state})))

(def ^:private nova-id :projector-acme-nova-NV-00123-0)

(deftest scan-creates-driver-projectors-test
  (let [result (scan-d discovery-state [(nova "NV-00123" 0) (zeta "0x1A2B" "10.0.0.9") (nova "10.0.0.9" 2)])
        ps (get-in result [:state :projectors])]
    (is (= #{:projector-acme-nova-NV-00123-0 :projector-zeta-beam-0x1A2B-0 :projector-acme-nova-10-0-0-9-2}
           (set (keys ps)))
        "01 §5.3's ids")
    (is (= {:name "Nova NV-00123" :driver :acme/nova
            :driver-config {:serial "NV-00123" :output 0}
            :stable-id [:device :acme/nova "NV-00123" 0]
            :zone-groups [] :exclude-from-all? false :tags #{} :enabled? false :scan-rate 30000}
           (get ps nova-id))
        "01 §5.1's shape: off, the output's own config, its device identity, no :host")
    (is (= {:name "0x1A2B" :driver-config {:host "10.0.0.9"} :scan-rate 25000}
           (select-keys (get ps :projector-zeta-beam-0x1A2B-0) [:name :driver-config :scan-rate]))
        "named after its key without a name; the core's scan rate without :capabilities")
    (is (not-any? #(contains? % :host) (vals ps)))
    (is (= 2 (count (get-in result [:state :chains :projector-effects nova-id :items]))) "the default calibration")
    (is (= [(nova "NV-00123" 0) (zeta "0x1A2B" "10.0.0.9") (nova "10.0.0.9" 2)]
           (get-in result [:state :projector-ui :discovered-devices])))
    (is (some? (:history/backfill result)))
    (testing "a device with more outputs: one projector each, named after both"
      (is (= {:projector-acme-nova-NV-7-0 "Nova NV-7 · Output 0" :projector-acme-nova-NV-7-1 "Nova NV-7 · Output 1"}
             (update-vals (get-in (scan-d discovery-state [(nova "NV-7" 0 1)]) [:state :projectors]) :name))))
    (testing "unique: a taken id gets -2, -3 …"
      (let [taken (assoc-in discovery-state [:projectors nova-id]
                            {:name "By hand" :driver :acme/nova :driver-config {:serial "NV-00123"}
                             :stable-id [:manual :acme/nova nova-id] :enabled? true})
            ps (get-in (scan-d taken [(nova "NV-00123" 0) (nova "NV.00123" 0)]) [:state :projectors])]
        (is (= #{nova-id :projector-acme-nova-NV-00123-0-2 :projector-acme-nova-NV-00123-0-3} (set (keys ps))))
        (is (= "By hand" (get-in ps [nova-id :name])) "the one added by hand is never taken for it")))
    (testing "not undone, not an unsaved change"
      (let [clean (history/mark-clean discovery-state)
            after (:state (events/handle-event-pipeline {:event/type :projectors/scan-complete
                                                         :devices [(nova "NV-00123" 0)] :warnings [] :state clean}))]
        (is (contains? (:projectors after) nova-id))
        (is (= (get-in after [:projectors nova-id]) (get-in after [:project :saved-document :projectors nova-id]))
            "new outputs are backfilled into the saved baseline")
        (is (empty? (get-in after [:history :undo-stack])))
        (is (false? (get-in after [:project :dirty?])))))))

(defn- set-up-nova
  "discovery-state after a scan found Nova NV-00123, then named, turned on,
   put in Graphics, calibrated, placed and its scan rate set."
  []
  (-> (:state (scan-d discovery-state [(nova "NV-00123" 0) (zeta "0x1A2B" "10.0.0.9")]))
      (assoc-in [:projectors nova-id :name] "Stage left")
      (assoc-in [:projectors nova-id :enabled?] true)
      (assoc-in [:projectors nova-id :zone-groups] [:graphics])
      (assoc-in [:projectors nova-id :scan-rate] 21000)
      (assoc-in [:projectors :projector-zeta-beam-0x1A2B-0 :name] "Stage right")
      (assoc-in [:chains :projector-effects nova-id :items]
                [{:id #uuid "00000000-0000-0000-0000-0000000000d1" :effect-id :rgb-curves :enabled? true
                  :params {:r-curve-points [[0.0 0.3] [1.0 1.0]]}}])
      (assoc-in [:rig :order] [:projector-zeta-beam-0x1A2B-0 nova-id])))

(deftest rescan-keeps-driver-projector-test
  (let [s (set-up-nova)
        result (scan-d s [(nova "NV-00123" 0) (zeta "0x1A2B" "10.0.0.9")])
        after (:state result)]
    (is (= (:projectors s) (:projectors after)) "the same projectors: names, groups, on, settings")
    (is (= (get-in s [:chains :projector-effects]) (get-in after [:chains :projector-effects])) "calibration kept")
    (is (= (get-in s [:rig :order]) (get-in after [:rig :order])) "and the places")
    (is (nil? (:history/backfill result)) "nothing to write")
    (is (nil? (get-in after [:ui :notice])))))

(deftest moved-driver-device-is-matched-test
  (let [s (assoc-in (set-up-nova) [:projectors :projector-zeta-beam-0x1A2B-0 :driver-config :port] 7400)
        result (scan-d s [(nova "NV-00123" 0) (zeta "0x1A2B" "10.0.0.20")])
        after (:state result)
        zeta-id :projector-zeta-beam-0x1A2B-0]
    (is (= (assoc (get-in s [:projectors zeta-id]) :driver-config {:host "10.0.0.20"})
           (get-in after [:projectors zeta-id]))
        "the new config replaces the old one; everything outside :driver-config stays")
    (is (= (dissoc (:projectors s) zeta-id) (dissoc (:projectors after) zeta-id)) "the others as they were")
    (is (= 2 (count (:projectors after))) "no new projector")
    (is (= {:text "Stage right moved (same device)." :level :info} (select-keys (get-in after [:ui :notice]) [:text :level])))
    (testing "a scan's move is no undo step and leaves the show saved"
      (let [pipeline #(:state (events/handle-event-pipeline (assoc %2 :state %1)))
            clean (history/mark-clean s)
            renamed (pipeline clean {:event/type :projectors/update-settings :projector-id nova-id :updates {:name "SL"}})
            moved (pipeline renamed {:event/type :projectors/scan-complete :devices [(zeta "0x1A2B" "10.0.0.20")] :warnings []})
            undone (pipeline moved {:event/type :history/undo})]
        (is (= "10.0.0.20" (get-in undone [:projectors zeta-id :driver-config :host])) "Undo leaves the move")
        (is (= {:host "10.0.0.20"} (get-in undone [:project :saved-document :projectors zeta-id :driver-config]))
            "the baseline moved with it")
        (is (= "Stage left" (get-in undone [:projectors nova-id :name])) "and takes back the rename")
        (is (false? (get-in undone [:project :dirty?])))))))

(deftest scan-warnings-show-a-notice-test
  (let [scanning (assoc-in discovery-state [:projector-ui :scanning?] true)
        result (scan-d scanning [(zeta "0x1A2B" "10.0.0.9")] ["Acme Nova: libnova not found" "Zeta: no answer within 3 s"])
        {:keys [id] :as notice} (get-in result [:state :ui :notice])]
    (is (= {:text "Acme Nova: libnova not found; Zeta: no answer within 3 s" :level :warning} (dissoc notice :id))
        "one :warning notice")
    (is (= {:event {:event/type :ui/clear-notice :id id} :delay-ms 6000} (:dispatch-later result)))
    (is (false? (get-in result [:state :projector-ui :scanning?])))
    (is (contains? (get-in result [:state :projectors]) :projector-zeta-beam-0x1A2B-0) "what was found is still added")
    (testing "with a move's notice: both, as a warning"
      (is (= {:text "Stage right moved (same device). Acme Nova: libnova not found" :level :warning}
             (select-keys (get-in (scan-d (set-up-nova) [(zeta "0x1A2B" "10.0.0.20")] ["Acme Nova: libnova not found"])
                                  [:state :ui :notice])
                          [:text :level]))))
    (testing "no warnings: no notice"
      (is (nil? (get-in (scan-d scanning [(zeta "0x1A2B" "10.0.0.9")] []) [:state :ui :notice])))
      (is (nil? (get-in (projectors/handle {:event/type :projectors/scan-complete :devices [] :state scanning})
                        [:state :ui :notice]))
          "nor without :warnings"))))

(def ^:private two-novas
  "Nova NV-1's two outputs, Nova NV-2's one and an IDN laser, all off."
  (assoc discovery-state :projectors
         {:n1a {:name "1A" :driver :acme/nova :driver-config {} :stable-id [:device :acme/nova "NV-1" 0] :enabled? false}
          :n1b {:name "1B" :driver :acme/nova :driver-config {} :stable-id [:device :acme/nova "NV-1" 1] :enabled? false}
          :n2 {:name "2" :driver :acme/nova :driver-config {} :stable-id [:device :acme/nova "NV-2" 0] :enabled? false}
          :z1 {:name "Z" :driver :zeta/beam :driver-config {} :stable-id [:device :zeta/beam "NV-1" 0] :enabled? false}
          :idn {:name "IDN" :host "10.0.0.5" :service-id 1 :stable-id [:host "10.0.0.5" 1] :enabled? false}}))

(deftest enable-device-test
  (let [enable (fn [state event] (projectors/handle (assoc event :event/type :projectors/enable-device :state state)))
        on (fn [state] (into #{} (keep (fn [[id p]] (when (:enabled? p) id))) (:projectors state)))
        turned-on (:state (enable two-novas {:driver :acme/nova :device-key "NV-1" :enabled? true}))]
    (is (= #{:n1a :n1b} (on turned-on)) "every output of that device, and only those")
    (is (= #{} (on (:state (enable turned-on {:driver :acme/nova :device-key "NV-1" :enabled? false})))) "and off again")
    (testing "IDN's older enable events cannot turn a hostless driver output on"
      (doseq [e [{:event/type :projectors/enable-all-by-ip :address nil}
                 {:event/type :projectors/set-service-enabled :host nil :service-id 0 :enabled? true}]]
        (is (empty? (on (:state (projectors/handle (assoc e :state two-novas))))))))
    (doseq [event [{:driver :acme/nova :device-key "NV-9" :enabled? true}
                   {:driver :acme/nova :device-key "NV-1" :enabled? "yes"}
                   {:driver :acme/nova :device-key "NV-1"}
                   {:driver :idn :device-key "10.0.0.5" :enabled? true}]]
      (is (identical? two-novas (:state (enable two-novas event))) (str "nothing: " (pr-str event))))
    (testing "through the pipeline: one undo step, and the engines follow while streaming"
      (let [live (assoc-in two-novas [:backend :streaming :running?] true)
            result (events/handle-event-pipeline {:event/type :projectors/enable-device :driver :acme/nova
                                                  :device-key "NV-1" :enabled? true :state live})]
        (is (= (get-in result [:state :projectors]) (get-in result [:multi-engine/refresh :projectors]))
            "a refresh, with the new projectors")
        (is (true? (get-in result [:multi-engine/refresh :projectors :n1b :enabled?])))
        (is (= "Turn Device On or Off" (history/undo-label (get-in result [:state :history]))))))))

(deftest remove-driver-projector-leaves-the-device-list-test
  (let [s (:state (scan-d discovery-state [(nova "NV-7" 0 1) (zeta "0x1A2B" "10.0.0.9")]))
        after (:state (projectors/handle {:event/type :projectors/remove :projector-id :projector-acme-nova-NV-7-0 :state s}))]
    (is (= [(update (nova "NV-7" 0 1) :outputs #(vec (rest %))) (zeta "0x1A2B" "10.0.0.9")]
           (get-in after [:projector-ui :discovered-devices]))
        "its output leaves the scan's list; the device's other output and other devices stay")))
