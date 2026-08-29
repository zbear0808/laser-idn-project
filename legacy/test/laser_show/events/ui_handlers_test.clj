(ns laser-show.events.ui-handlers-test
  "Tests for UI event handlers, particularly dialog data updates."
  (:require
   [clojure.test :refer [deftest is testing]]
   [laser-show.events.handlers.ui :as ui]
   [laser-show.state.domains :refer [build-initial-state]]))


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
