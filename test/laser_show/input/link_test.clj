(ns laser-show.input.link-test
  "Tests for Ableton Link synchronization service."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.input.link :as link]))


;; State Management Tests


(deftest test-initial-state
  (testing "Initial Link state structure"
    (is (= {:connected? false
            :sync-enabled? false
            :link-bpm nil}
           link/initial-state))))

(deftest test-enable-disable-sync
  (testing "Enabling BPM sync"
    (let [state link/initial-state
          enabled (link/enable-sync state)]
      (is (true? (:sync-enabled? enabled)))))
  
  (testing "Disabling BPM sync"
    (let [state (link/enable-sync link/initial-state)
          disabled (link/disable-sync state)]
      (is (false? (:sync-enabled? disabled))))))

(deftest test-sync-enabled-query
  (testing "Sync enabled query"
    (is (false? (link/sync-enabled? link/initial-state)))
    (is (true? (link/sync-enabled? (link/enable-sync link/initial-state))))))

(deftest test-connected-query
  (testing "Connection status query"
    (is (false? (link/connected? link/initial-state)))
    (is (true? (link/connected? (assoc link/initial-state :connected? true))))))


;; BPM Change Threshold Tests


(deftest test-bpm-changed
  (testing "BPM change detection with threshold"
    (let [bpm-changed? @#'link/bpm-changed?]
      ;; Nil old BPM should trigger change
      (is (true? (bpm-changed? nil 120.0)))
      
      ;; Small change below threshold should not trigger
      (is (false? (bpm-changed? 120.0 120.005)))
      
      ;; Change above threshold should trigger
      (is (true? (bpm-changed? 120.0 120.02)))
      (is (true? (bpm-changed? 120.0 119.98)))
      
      ;; Exactly at threshold should trigger
      (is (true? (bpm-changed? 120.0 120.01))))))


;; Status Listener Tests


(deftest test-status-listener-creation
  (testing "Status listener dispatches events correctly"
    (let [dispatched-events (atom [])
          dispatch-fn (fn [event] (swap! dispatched-events conj event))
          get-state-fn (fn [] {:sync-enabled? true :link-bpm nil})
          listener (link/create-status-listener dispatch-fn get-state-fn)
          status {:bpm 125.0 :beat-at-time 2.5}]
      
      ;; Call listener with status
      (listener status)
      
      ;; Should dispatch link-bpm-changed
      (is (= 1 (count (filter #(= :timing/link-bpm-changed (:event/type %)) @dispatched-events))))
      
      ;; Should dispatch set-bpm when sync is enabled
      (is (= 1 (count (filter #(= :timing/set-bpm (:event/type %)) @dispatched-events)))))))

(deftest test-status-listener-no-sync
  (testing "Status listener does not dispatch set-bpm when sync disabled"
    (let [dispatched-events (atom [])
          dispatch-fn (fn [event] (swap! dispatched-events conj event))
          get-state-fn (fn [] {:sync-enabled? false :link-bpm nil})
          listener (link/create-status-listener dispatch-fn get-state-fn)
          status {:bpm 125.0 :beat-at-time 2.5}]
      
      ;; Call listener with status
      (listener status)
      
      ;; Should dispatch link-bpm-changed
      (is (= 1 (count (filter #(= :timing/link-bpm-changed (:event/type %)) @dispatched-events))))
      
      ;; Should NOT dispatch set-bpm when sync is disabled
      (is (= 0 (count (filter #(= :timing/set-bpm (:event/type %)) @dispatched-events)))))))

(deftest test-status-listener-threshold
  (testing "Status listener respects BPM change threshold"
    (let [dispatched-events (atom [])
          dispatch-fn (fn [event] (swap! dispatched-events conj event))
          get-state-fn (fn [] {:sync-enabled? true :link-bpm 120.0})
          listener (link/create-status-listener dispatch-fn get-state-fn)
          ;; Small change below threshold
          status {:bpm 120.005 :beat-at-time 2.5}]
      
      ;; Call listener with status
      (listener status)
      
      ;; Should dispatch link-bpm-changed
      (is (= 1 (count (filter #(= :timing/link-bpm-changed (:event/type %)) @dispatched-events))))
      
      ;; Should NOT dispatch set-bpm due to threshold
      (is (= 0 (count (filter #(= :timing/set-bpm (:event/type %)) @dispatched-events)))))))


;; Initialization Tests


(deftest test-init-no-auto-connect
  (testing "Init without auto-connect"
    (let [state (link/init link/initial-state false nil nil)]
      (is (false? (:connected? state))))))

;; Note: Testing actual beat-carabiner connection requires mocking or integration tests
;; These tests cover the pure state management functions
