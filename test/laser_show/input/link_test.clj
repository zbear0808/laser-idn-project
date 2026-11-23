(ns laser-show.input.link-test
  "Tests for Ableton Link synchronization service."
  (:require [beat-carabiner.core :as bc]
            [clojure.test :refer [deftest is testing]]
            [laser-show.events.handlers.timing :as timing]
            [laser-show.input.link :as link]))


;; State Management Tests


(deftest test-initial-state
  (testing "Initial Link state structure"
    (is (= {:carabiner-connected? false
            :link-enabled? false
            :sync-enabled? false
            :link-bpm nil
            :link-peers 0}
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
  (testing "Connection status query (requires both carabiner-connected? and link-enabled?)"
    (is (false? (link/connected? link/initial-state)))
    ;; connected? requires BOTH carabiner-connected? AND link-enabled? to be true
    (is (false? (link/connected? (assoc link/initial-state :carabiner-connected? true))))
    (is (false? (link/connected? (assoc link/initial-state :link-enabled? true))))
    (is (true? (link/connected? (assoc link/initial-state 
                                       :carabiner-connected? true 
                                       :link-enabled? true))))))

(deftest test-carabiner-connected-query
  (testing "Carabiner connection status query"
    (is (false? (link/carabiner-connected? link/initial-state)))
    (is (true? (link/carabiner-connected? (assoc link/initial-state :carabiner-connected? true))))))

(deftest test-link-enabled-query
  (testing "Link enabled status query"
    (is (false? (link/link-enabled? link/initial-state)))
    (is (true? (link/link-enabled? (assoc link/initial-state :link-enabled? true))))))


;; BPM Change Threshold Tests


(deftest test-bpm-changed
  (testing "BPM change detection with threshold"
    (let [bpm-changed? @#'link/bpm-changed?]
      ;; Nil old BPM should trigger change
      (is (true? (bpm-changed? nil 120.0)))
      (doseq [invalid [nil 0 -1 "128" ##NaN ##Inf]]
        (is (false? (bpm-changed? 120.0 invalid))))
      
      ;; Small change below threshold should not trigger
      (is (false? (bpm-changed? 120.0 120.005)))
      
      ;; Change above threshold should trigger
      (is (true? (bpm-changed? 120.0 120.02)))
      (is (true? (bpm-changed? 120.0 119.98)))
      
      ;; Exactly at threshold should trigger
      (is (true? (bpm-changed? 120.0 120.01))))))


;; Status Listener Tests
;; Note: The status listener expects :link-bpm key in the status map (from beat-carabiner)


(def ^:private following
  {:timing {:bpm 128.0}
   :backend {:link (assoc link/initial-state :carabiner-connected? true :link-enabled? true
                          :sync-enabled? true :link-bpm 128.0 :link-peers 1)}})

(defn- apply-event [s e] (:state (timing/handle (assoc e :state s))))

(deftest stable-status-is-quiet-but-corrects-app-tempo-drift-test
  (let [s (atom following)
        sent (atom [])
        listener (link/create-status-listener #(do (swap! sent conj %) (swap! s apply-event %)) #(deref s))
        status {:link-bpm 128.0 :link-peers 1 :sync-mode :off}]
    (listener status)
    (listener (assoc status :link-bpm 128.005))
    (is (empty? @sent) "steady tempo and sub-threshold jitter generate no events")
    (listener (assoc status :link-peers 2))
    (is (= [{:event/type :timing/link-peers-changed :peers 2}] @sent))
    (reset! sent [])
    (swap! s assoc-in [:timing :bpm] 120.0)
    (dotimes [_ 3] (listener (assoc status :link-peers 2)))
    (is (= 128.0 (get-in @s [:timing :bpm])) "compare the report with the app, not just the display cache")
    (is (= 1 (count @sent)) "one correction, then silence")))

(deftest queued-status-respects-the-current-sync-controls-test
  (doseq [flag [:link-enabled? :sync-enabled?]]
    (let [queued (atom [])
          listener (link/create-status-listener #(swap! queued conj %) (constantly following))]
      (listener {:link-bpm 132.0 :link-peers 1})
      (let [off (assoc-in following [:backend :link flag] false)
            result (reduce apply-event off @queued)]
        (is (= 132.0 (get-in result [:backend :link :link-bpm])) "still display the peer tempo")
        (is (= 128.0 (get-in result [:timing :bpm])) (str flag " off before processing"))))))

(deftest consecutive-reports-can-return-to-the-last-applied-values-test
  (let [queued (atom [])
        listener (link/create-status-listener #(swap! queued conj %) (constantly following))]
    ;; The event thread has not applied either report yet.
    (dotimes [_ 3] (listener {:link-bpm 135.0 :link-peers 2}))
    (is (= 2 (count @queued)) "only one BPM and one peer event, even with the event thread behind")
    (listener {:link-bpm 128.0 :link-peers 1})
    (is (= following (reduce apply-event following @queued)))))

(deftest connection-picks-up-a-status-that-already-arrived-test
  (let [sent (atom [])]
    (with-redefs [bc/connect (constantly true)
                  bc/add-status-listener (constantly nil)
                  bc/state (constantly {:link-bpm 140.0 :link-peers 0})]
      (link/connect-carabiner! link/initial-state #(swap! sent conj %) (constantly {:backend {:link link/initial-state}}))
      (is (= [{:event/type :timing/link-bpm-changed :bpm 140.0}] @sent)))))

(deftest failed-connection-registers-no-listener-test
  (with-redefs [bc/connect (constantly false)
                bc/add-status-listener (fn [_] (throw (AssertionError. "no connection")))]
    (is (= link/initial-state (link/connect-carabiner! link/initial-state identity (constantly {}))))))


;; Initialization Tests


(deftest test-init-no-auto-connect
  (testing "Init without auto-connect"
    (let [state (link/init link/initial-state false nil nil)]
      (is (false? (:carabiner-connected? state))))))

;; Note: Testing actual beat-carabiner connection requires mocking or integration tests
;; These tests cover the pure state management functions
