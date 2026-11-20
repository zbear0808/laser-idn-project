(ns laser-show.events.ui-handlers-test
  "Tests for UI event handlers, particularly dialog data updates."
  (:require
   [cljfx.api :as fx]
   [clojure.test :refer [deftest is testing]]
   [laser-show.events.core :as events]
   [laser-show.events.handlers.ui :as ui]
   [laser-show.events.helpers :as h]
   [laser-show.state.core :as state]
   [laser-show.state.domains :refer [build-initial-state]]
   [laser-show.state.history :as history]
   [laser-show.state.persistent :as persistent]
   [laser-show.subs :as subs]))


;; Test Fixtures


(def base-state
  "Base state using actual app initial state structure."
  (build-initial-state))

(def state-with-dialog-open
  "State with effect-chain-editor dialog open."
  (-> base-state
      (assoc-in [:ui :dialogs :effect-chain-editor :open?] true)
      (assoc-in [:ui :dialogs :effect-chain-editor :col] 0)
      (assoc-in [:ui :dialogs :effect-chain-editor :row] 0)))


;; handle-ui-update-dialog-data Tests
;; These test the fix for the bug where extra keys (like :editing-name?) were ignored


(deftest handle-update-dialog-data-with-updates-key
  (testing "Updates via :updates key are merged into dialog state"
    (let [event {:event/type :ui/update-dialog-data
                 :dialog-id :effect-chain-editor
                 :updates {:editing-name? true :custom-value 42}
                 :state state-with-dialog-open}
          result (ui/handle event)]
      (is (true? (get-in result [:state :ui :dialogs :effect-chain-editor :editing-name?])))
      (is (= 42 (get-in result [:state :ui :dialogs :effect-chain-editor :custom-value])))
      ;; Original dialog values should be preserved
      (is (= 0 (get-in result [:state :ui :dialogs :effect-chain-editor :col])))
      (is (= 0 (get-in result [:state :ui :dialogs :effect-chain-editor :row]))))))

(deftest handle-update-dialog-data-with-tab-id
  (testing "Tab ID sets :active-bank-tab"
    (let [event {:event/type :ui/update-dialog-data
                 :dialog-id :effect-chain-editor
                 :tab-id :color
                 :state state-with-dialog-open}
          result (ui/handle event)]
      (is (= :color (get-in result [:state :ui :dialogs :effect-chain-editor :active-bank-tab]))))))

(deftest handle-update-dialog-data-with-extra-keys
  (testing "Extra keys at top level are merged into dialog state (bug fix)"
    ;; This tests the specific bug that was fixed - callers can pass keys
    ;; directly at the top level without wrapping in :updates
    (let [event {:event/type :ui/update-dialog-data
                 :dialog-id :effect-chain-editor
                 :editing-name? true
                 :state state-with-dialog-open}
          result (ui/handle event)]
      (is (true? (get-in result [:state :ui :dialogs :effect-chain-editor :editing-name?])))
      ;; Original dialog values should be preserved
      (is (= 0 (get-in result [:state :ui :dialogs :effect-chain-editor :col]))))))

(deftest handle-update-dialog-data-multiple-extra-keys
  (testing "Multiple extra keys are all merged"
    (let [event {:event/type :ui/update-dialog-data
                 :dialog-id :effect-chain-editor
                 :editing-name? true
                 :selected-item-id "item-123"
                 :custom-mode :advanced
                 :state state-with-dialog-open}
          result (ui/handle event)]
      (is (true? (get-in result [:state :ui :dialogs :effect-chain-editor :editing-name?])))
      (is (= "item-123" (get-in result [:state :ui :dialogs :effect-chain-editor :selected-item-id])))
      (is (= :advanced (get-in result [:state :ui :dialogs :effect-chain-editor :custom-mode]))))))

(deftest handle-update-dialog-data-empty-event
  (testing "Event with no updates does nothing"
    (let [event {:event/type :ui/update-dialog-data
                 :dialog-id :effect-chain-editor
                 :state state-with-dialog-open}
          result (ui/handle event)]
      ;; Original state should be unchanged
      (is (= 0 (get-in result [:state :ui :dialogs :effect-chain-editor :col])))
      (is (= 0 (get-in result [:state :ui :dialogs :effect-chain-editor :row]))))))

(deftest handle-update-dialog-data-reserved-keys-not-merged
  (testing "Reserved keys like :state and :fx/event are not merged into dialog"
    (let [event {:event/type :ui/update-dialog-data
                 :dialog-id :effect-chain-editor
                 :editing-name? true
                 :fx/event {:some :event-data}
                 :state state-with-dialog-open}
          result (ui/handle event)]
      ;; :editing-name? should be merged
      (is (true? (get-in result [:state :ui :dialogs :effect-chain-editor :editing-name?])))
      ;; :fx/event should NOT be merged
      (is (nil? (get-in result [:state :ui :dialogs :effect-chain-editor :fx/event]))))))


;; handle-ui-open-dialog Tests


(deftest handle-open-dialog-basic
  (testing "Opening a dialog sets :open? to true"
    (let [event {:event/type :ui/open-dialog
                 :dialog-id :effect-chain-editor
                 :data {:col 1 :row 2}
                 :state base-state}
          result (ui/handle event)]
      (is (true? (get-in result [:state :ui :dialogs :effect-chain-editor :open?])))
      (is (= 1 (get-in result [:state :ui :dialogs :effect-chain-editor :col])))
      (is (= 2 (get-in result [:state :ui :dialogs :effect-chain-editor :row]))))))


;; handle-ui-close-dialog Tests


(deftest handle-close-dialog-basic
  (testing "Closing a dialog sets :open? to false"
    (let [event {:event/type :ui/close-dialog
                 :dialog-id :effect-chain-editor
                 :state state-with-dialog-open}
          result (ui/handle event)]
      (is (false? (get-in result [:state :ui :dialogs :effect-chain-editor :open?])))
      ;; Other dialog data should be preserved
      (is (= 0 (get-in result [:state :ui :dialogs :effect-chain-editor :col]))))))


;; handle-ui-set-active-tab Tests


(deftest handle-set-active-tab
  (testing "Setting active tab updates UI state"
    (let [event {:event/type :ui/set-active-tab
                 :tab-id :effects
                 :state base-state}
          result (ui/handle event)]
      (is (= :effects (get-in result [:state :ui :active-tab]))))))


;; Notices


(defn- clear [state id]
  (ui/handle {:event/type :ui/clear-notice :id id :state state}))

(deftest notice-goes-after-6-s-or-on-a-click-test
  (let [{:keys [state dispatch-later]} (h/show-notice base-state "Could not open a.zip" :error)
        {:keys [id]} (get-in state [:ui :notice])]
    (is (= {:id id :text "Could not open a.zip" :level :error} (get-in state [:ui :notice])))
    (is (= {:event {:event/type :ui/clear-notice :id id} :delay-ms 6000} dispatch-later))
    (testing "its timer (or a click on it) clears it"
      (is (nil? (get-in (clear state id) [:state :ui :notice]))))
    (testing "once it is gone, nothing is left to clear"
      (is (= {} (clear (:state (clear state id)) id))))))

(deftest earlier-notice-timer-keeps-a-later-notice-test
  (let [first-notice (h/show-notice base-state "Could not open a.zip" :error)
        first-id (get-in first-notice [:state :ui :notice :id])
        second-notice (h/show-notice (:state first-notice) "Could not save b.zip" :error)
        second-id (get-in second-notice [:state :ui :notice :id])]
    (is (not= first-id second-id) "each notice has its own id")
    (testing "the first notice's timer, 6 s after it, leaves the second on screen"
      (is (= {} (clear (:state second-notice) first-id))))
    (testing "the second's own timer clears it"
      (is (nil? (get-in (clear (:state second-notice) second-id) [:state :ui :notice]))))
    (testing "even after a click took the first away before the second came"
      (let [clicked (:state (clear (:state first-notice) first-id))
            later (h/show-notice clicked "Could not save b.zip" :error)]
        (is (= {} (clear (:state later) first-id)))))))


;; The divider between a tab and the preview

(defn- grid-split [state]
  (subs/grid-split (fx/create-context state state/cache-factory)))

(deftest grid-split-is-a-saved-preference-test
  (let [clean (history/mark-clean base-state)
        moved (:state (events/handle-event-pipeline {:event/type :ui/set-grid-split
                                                     :position 0.42
                                                     :state clean}))]
    (testing "a project that never had the divider moved shows it at 0.65"
      (is (= 0.65 (grid-split clean))))
    (testing "where the user left it is remembered"
      (is (= 0.42 (get-in moved [:config :window :grid-split])))
      (is (= 0.42 (grid-split moved))))
    (testing "a preference: saved with the project, so a change to save, but nothing to undo"
      (is (true? (get-in moved [:project :dirty?])))
      (is (empty? (get-in moved [:history :undo-stack])))
      (is (= 0.42 (get-in (persistent/project-files moved)
                          ["project-metadata.edn" :config :window :grid-split]))))))


;; The shared confirm dialog


(deftest ui-confirm-continues-with-its-event-test
  (let [asking (update-in base-state [:ui :dialogs :confirm] merge
                          {:open? true
                           :title "Delete \"Balcony\"?"
                           :confirm-event {:event/type :zone-groups/remove :group-id :group-balcony}
                           :choice {:key :retarget-to :label "Send its cues to:"
                                    :options [{:value :all :text "All"} {:value :left :text "Left"}]}
                           :choice-value :left})
        result (ui/handle {:event/type :ui/confirm :state asking})]
    (is (false? (get-in result [:state :ui :dialogs :confirm :open?])) "it closes")
    (is (= {:event/type :zone-groups/remove :group-id :group-balcony :retarget-to :left}
           (:dispatch result))
        "and continues with its event and the choice")
    (testing "without a choice, the event as it is"
      (is (= {:event/type :projectors/remove :projector-id :p1}
             (:dispatch (ui/handle {:event/type :ui/confirm
                                    :state (update-in base-state [:ui :dialogs :confirm] merge
                                                      {:open? true
                                                       :confirm-event {:event/type :projectors/remove :projector-id :p1}})})))))
    (testing "a closed dialog continues with nothing"
      (is (nil? (:dispatch (ui/handle {:event/type :ui/confirm :state base-state})))))))


;; The LOW FPS chip opens a laser's card (zones plan Z3)

(deftest reveal-projector-opens-its-card-test
  (let [state (-> base-state
                  (assoc-in [:projectors :p1] {:name "Laser R" :host "10.0.0.1"})
                  (assoc-in [:ui :active-tab] :grid)
                  (assoc-in [:projector-ui :active-virtual-projector] :vp-1))
        out (:state (ui/handle {:event/type :ui/reveal-projector :projector-id :p1 :state state}))]
    (is (= :projectors (get-in out [:ui :active-tab])) "the Projectors tab")
    (is (= :p1 (get-in out [:projector-ui :active-projector])) "with its card selected")
    (is (nil? (get-in out [:projector-ui :active-virtual-projector])))
    (testing "a laser that is gone: the tab, nothing selected anew"
      (let [out (:state (ui/handle {:event/type :ui/reveal-projector :projector-id :gone :state state}))]
        (is (= :projectors (get-in out [:ui :active-tab])))
        (is (nil? (get-in out [:projector-ui :active-projector])))))
    (is (not= {} (ui/handle {:event/type :ui/reveal-projector :state base-state})) "no id: still a handler's answer")))


;; A preview cell names a target (zones plan Z3)

(deftest set-cell-zone-writes-a-target-test
  (let [state (-> base-state
                  (assoc-in [:config :preview :grid-cells] [{:target nil} {:zone-group-id :graphics}])
                  (assoc-in [:ui :preview-zone-selector-open] 1))
        out (:state (ui/handle {:event/type :preview/set-cell-zone :cell-index 1 :target :auto.laser/p1 :state state}))]
    (is (= {:target :auto.laser/p1} (get-in out [:config :preview :grid-cells 1])) "the old key goes")
    (is (nil? (get-in out [:ui :preview-zone-selector-open])) "and the picker closes")
    (is (= {:target :all}
           (get-in (ui/handle {:event/type :preview/set-cell-zone :cell-index 0 :zone-group-id :all :state state})
                   [:state :config :preview :grid-cells 0]))
        ":zone-group-id still works")
    (is (= {:target nil}
           (get-in (ui/handle {:event/type :preview/set-cell-zone :cell-index 1 :target nil :state state})
                   [:state :config :preview :grid-cells 1]))
        "the master view")
    (testing "a bigger layout fills its new cells with targets"
      (let [cells (get-in (ui/handle {:event/type :preview/set-grid-layout :fx/event "3x2" :state state})
                          [:state :config :preview :grid-cells])]
        (is (= 6 (count cells)))
        (is (= [{:target nil} {:zone-group-id :graphics}] (subvec cells 0 2)) "the cells it had stay as they were")
        (is (every? #(contains? % :target) (subvec cells 2)))))))

(deftest old-preview-cell-reads-as-a-target-test
  (let [ctx (fx/create-context (assoc-in base-state [:config :preview :grid-cells]
                                         [{:zone-group-id nil} {:zone-group-id :graphics} {:target :auto/left}
                                          {:target :auto.laser/p1 :zone-group-id :graphics}])
                               state/cache-factory)]
    (is (= [{:target nil} {:target :graphics} {:target :auto/left} {:target :auto.laser/p1}]
           (mapv #(fx/sub-ctx ctx subs/preview-cell-config %) (range 4)))
        "{:zone-group-id x} reads as {:target x}; a cell with both is its target")
    (is (= {:target nil} (fx/sub-ctx ctx subs/preview-cell-config 9)) "a cell past the end: the master view")))
