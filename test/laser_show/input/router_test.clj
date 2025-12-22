(ns laser-show.input.router-test
  "Unit tests for the event router."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [laser-show.input.events :as events]
            [laser-show.input.router :as router]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(defn reset-router-fixture
  "Resets router state before each test."
  [f]
  (router/clear-handlers!)
  (router/clear-event-log!)
  (router/enable!)
  (router/disable-logging!)
  (f)
  (router/clear-handlers!))

(use-fixtures :each reset-router-fixture)

;; ============================================================================
;; Handler Registration Tests
;; ============================================================================

(deftest register-handler-test
  (testing "Registers handler with pattern"
    (let [received (atom nil)
          handler-id (router/register-handler! :test-handler 
                                               {:type :note-on}
                                               #(reset! received %))]
      (is (= :test-handler handler-id))
      (is (= 1 (router/handler-count))))))

(deftest register-global-handler-test
  (testing "Registers global handler"
    (let [received (atom nil)
          handler-id (router/register-global-handler! :global-test #(reset! received %))]
      (is (= :global-test handler-id))
      (is (= 1 (router/handler-count))))))

(deftest unregister-handler-test
  (testing "Removes registered handler"
    (router/register-handler! :test {:type :note-on} identity)
    (is (= 1 (router/handler-count)))
    (router/unregister-handler! :test)
    (is (= 0 (router/handler-count)))))

(deftest clear-handlers-test
  (testing "Removes all handlers"
    (router/register-handler! :test1 {:type :note-on} identity)
    (router/register-handler! :test2 {:type :note-off} identity)
    (router/register-global-handler! :global identity)
    (is (= 3 (router/handler-count)))
    (router/clear-handlers!)
    (is (= 0 (router/handler-count)))))

;; ============================================================================
;; Event Dispatch Tests
;; ============================================================================

(deftest dispatch-basic-test
  (testing "Dispatches event to matching handler"
    (let [received (atom nil)]
      (router/register-handler! :test 
                               {:type :note-on}
                               #(reset! received %))
      (let [event (events/note-on :midi 0 60 1.0)]
        (router/dispatch! event)
        (is (= event @received))))))

(deftest dispatch-no-match-test
  (testing "Does not dispatch to non-matching handler"
    (let [received (atom nil)]
      (router/register-handler! :test 
                               {:type :note-off}
                               #(reset! received %))
      (router/dispatch! (events/note-on :midi 0 60 1.0))
      (is (nil? @received)))))

(deftest dispatch-pattern-matching-test
  (testing "Pattern matching with multiple criteria"
    (let [received (atom nil)]
      (router/register-handler! :test 
                               {:type :note-on :channel 0 :note 60}
                               #(reset! received %))
      ;; Should match
      (router/dispatch! (events/note-on :midi 0 60 1.0))
      (is (some? @received))
      
      ;; Reset and test non-match (different note)
      (reset! received nil)
      (router/dispatch! (events/note-on :midi 0 61 1.0))
      (is (nil? @received)))))

(deftest dispatch-global-handler-test
  (testing "Global handler receives all events"
    (let [received (atom [])]
      (router/register-global-handler! :global #(swap! received conj %))
      (router/dispatch! (events/note-on :midi 0 60 1.0))
      (router/dispatch! (events/control-change :osc 0 1 0.5))
      (router/dispatch! (events/trigger :keyboard :play :pressed))
      (is (= 3 (count @received))))))

(deftest dispatch-multiple-handlers-test
  (testing "Multiple handlers can receive same event"
    (let [count1 (atom 0)
          count2 (atom 0)]
      (router/register-handler! :handler1 {:type :note-on} (fn [_] (swap! count1 inc)))
      (router/register-handler! :handler2 {:type :note-on} (fn [_] (swap! count2 inc)))
      (router/dispatch! (events/note-on :midi 0 60 1.0))
      (is (= 1 @count1))
      (is (= 1 @count2)))))

(deftest dispatch-many-test
  (testing "Dispatches multiple events in sequence"
    (let [received (atom [])]
      (router/register-global-handler! :test #(swap! received conj %))
      (router/dispatch-many! [(events/note-on :midi 0 60 1.0)
                              (events/note-on :midi 0 61 1.0)
                              (events/note-on :midi 0 62 1.0)])
      (is (= 3 (count @received))))))

;; ============================================================================
;; Router Control Tests
;; ============================================================================

(deftest enable-disable-test
  (testing "Disabled router does not dispatch"
    (let [received (atom nil)]
      (router/register-handler! :test {} #(reset! received %))
      (router/disable!)
      (router/dispatch! (events/note-on :midi 0 60 1.0))
      (is (nil? @received))
      
      ;; Re-enable and test
      (router/enable!)
      (router/dispatch! (events/note-on :midi 0 60 1.0))
      (is (some? @received)))))

;; ============================================================================
;; Event Logging Tests
;; ============================================================================

(deftest event-logging-test
  (testing "Logs events when enabled"
    (router/enable-logging!)
    (router/dispatch! (events/note-on :midi 0 60 1.0))
    (router/dispatch! (events/note-on :midi 0 61 1.0))
    (let [log (router/get-event-log)]
      (is (= 2 (count log))))))

(deftest event-log-disabled-test
  (testing "Does not log when disabled"
    (router/disable-logging!)
    (router/dispatch! (events/note-on :midi 0 60 1.0))
    (is (empty? (router/get-event-log)))))

(deftest clear-event-log-test
  (testing "Clears event log"
    (router/enable-logging!)
    (router/dispatch! (events/note-on :midi 0 60 1.0))
    (is (= 1 (count (router/get-event-log))))
    (router/clear-event-log!)
    (is (empty? (router/get-event-log)))))

;; ============================================================================
;; Convenience Function Tests
;; ============================================================================

(deftest on-note-test
  (testing "on-note! registers note handler"
    (let [received (atom nil)]
      (router/on-note! :test 0 60 #(reset! received %))
      (router/dispatch! (events/note-on :midi 0 60 1.0))
      (is (some? @received))
      (is (= 60 (:note @received))))))

(deftest on-control-test
  (testing "on-control! registers control change handler"
    (let [received (atom nil)]
      (router/on-control! :test 0 1 #(reset! received %))
      (router/dispatch! (events/control-change :midi 0 1 0.5))
      (is (some? @received))
      (is (= 1 (:control @received))))))

(deftest on-trigger-test
  (testing "on-trigger! registers trigger handler"
    (let [received (atom nil)]
      (router/on-trigger! :test :play #(reset! received %))
      (router/dispatch! (events/trigger :keyboard :play :pressed))
      (is (some? @received))
      (is (= :play (:id @received))))))

(deftest on-any-test
  (testing "on-any! registers handler for all events from source"
    (let [received (atom [])]
      (router/on-any! :test :midi #(swap! received conj %))
      (router/dispatch! (events/note-on :midi 0 60 1.0))
      (router/dispatch! (events/control-change :midi 0 1 0.5))
      (router/dispatch! (events/trigger :keyboard :play :pressed))
      (is (= 2 (count @received))))))

;; ============================================================================
;; Handler Info Tests
;; ============================================================================

(deftest list-handlers-test
  (testing "Lists registered handlers"
    (router/register-handler! :handler1 {:type :note-on} identity)
    (router/register-handler! :handler2 {:type :note-off} identity)
    (router/register-global-handler! :global identity)
    (let [info (router/list-handlers)]
      (is (= {:type :note-on} (get-in info [:handlers :handler1])))
      (is (= {:type :note-off} (get-in info [:handlers :handler2])))
      (is (some #{:global} (:global-handlers info))))))

;; ============================================================================
;; Error Handling Tests
;; ============================================================================

(deftest handler-error-test
  (testing "Handler errors don't stop dispatch to other handlers"
    (let [received (atom nil)]
      (router/register-handler! :error-handler {} (fn [_] (throw (Exception. "Test error"))))
      (router/register-handler! :good-handler {} #(reset! received %))
      (router/dispatch! (events/note-on :midi 0 60 1.0))
      (is (some? @received)))))
