(ns laser-show.events.middleware-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [laser-show.events.middleware :as mw]))


;; Test Fixtures


(defn reset-middleware-state [f]
  (mw/clear-event-history!)
  (mw/clear-event-log!)
  (mw/clear-snapshots!)
  (f)
  (mw/clear-event-history!)
  (mw/clear-event-log!)
  (mw/clear-snapshots!))

(use-fixtures :each reset-middleware-state)


;; Event Validation Tests


(deftest validate-event-test
  (testing "Valid events pass validation"
    (is (= {:valid? true :errors []} 
           (mw/validate-event [:grid/select-cell 0 0])))
    (is (= {:valid? true :errors []} 
           (mw/validate-event [:grid/trigger-cell 1 2])))
    (is (= {:valid? true :errors []} 
           (mw/validate-event [:grid/stop-active])))
    (is (= {:valid? true :errors []} 
           (mw/validate-event [:timing/set-bpm 120.0]))))
  
  (testing "Wrong argument count fails validation"
    (let [result (mw/validate-event [:grid/select-cell 0])]
      (is (false? (:valid? result)))
      (is (seq (:errors result)))))
  
  (testing "Invalid argument types fail validation"
    (let [result (mw/validate-event [:grid/select-cell "a" "b"])]
      (is (false? (:valid? result)))))
  
  (testing "Unknown events pass validation (extensibility)"
    (is (= {:valid? true :errors []} 
           (mw/validate-event [:custom/unknown-event 1 2 3])))))


;; Event History Tests


(deftest event-history-test
  (testing "Initial state"
    (is (= [] (mw/get-event-history)))
    (is (= 0 (mw/get-history-position)))
    (is (false? (mw/can-undo?)))
    (is (false? (mw/can-redo?))))
  
  (testing "History tracking via wrap-history"
    (let [calls (atom [])
          handler (fn [event] (swap! calls conj event) event)
          wrapped (mw/wrap-history handler)]
      ;; Dispatch some events
      (wrapped [:grid/select-cell 0 0])
      (wrapped [:grid/trigger-cell 1 1])
      
      ;; Check history
      (is (= 2 (count (mw/get-event-history))))
      (is (= 2 (mw/get-history-position)))
      (is (mw/can-undo?))
      (is (false? (mw/can-redo?)))))
  
  (testing "Excluded events not in history"
    (let [handler (fn [event] event)
          wrapped (mw/wrap-history handler {:exclude #{:tick}})]
      (mw/clear-event-history!)
      (wrapped [:tick])
      (wrapped [:tick])
      (is (= 0 (count (mw/get-event-history)))))))

(deftest get-undo-redo-event-test
  (testing "Get undo/redo events"
    (let [handler (fn [event] event)
          wrapped (mw/wrap-history handler)]
      (wrapped [:grid/select-cell 0 0])
      (wrapped [:grid/select-cell 1 1])
      
      ;; Last event should be undoable
      (let [undo-event (mw/get-undo-event)]
        (is (some? undo-event))
        (is (= [:grid/select-cell 1 1] (:event undo-event))))
      
      ;; Nothing to redo yet
      (is (nil? (mw/get-redo-event))))))


;; Event Log Tests


(deftest event-log-test
  (testing "Event logging"
    (let [handler (fn [event] event)
          wrapped (mw/wrap-logging handler)]
      (mw/enable-event-logging! true)
      (wrapped [:grid/select-cell 0 0])
      (wrapped [:grid/trigger-cell 1 1])
      
      (let [log (mw/get-event-log)]
        (is (= 2 (count log)))
        (is (every? #(contains? % :event) log))
        (is (every? #(contains? % :timestamp) log))
        (is (every? #(contains? % :duration-ms) log)))))
  
  (testing "Logging can be disabled"
    (mw/clear-event-log!)
    (mw/enable-event-logging! false)
    (let [handler (fn [event] event)
          wrapped (mw/wrap-logging handler)]
      (wrapped [:grid/select-cell 0 0])
      (is (= 0 (count (mw/get-event-log)))))
    (mw/enable-event-logging! true)))


;; Middleware Wrapper Tests


(deftest wrap-validation-test
  (testing "Valid events pass through"
    (let [calls (atom [])
          handler (fn [event] (swap! calls conj event) :handled)
          wrapped (mw/wrap-validation handler)]
      (is (= :handled (wrapped [:grid/select-cell 0 0])))
      (is (= 1 (count @calls)))))
  
  (testing "Invalid events are blocked"
    (let [calls (atom [])
          handler (fn [event] (swap! calls conj event) :handled)
          wrapped (mw/wrap-validation handler)]
      (is (nil? (wrapped [:grid/select-cell "invalid"])))
      (is (= 0 (count @calls))))))

(deftest wrap-error-handling-test
  (testing "Exceptions are caught"
    (let [handler (fn [_] (throw (Exception. "Test error")))
          wrapped (mw/wrap-error-handling handler)]
      (is (nil? (wrapped [:test/event]))))))

(deftest wrap-timing-test
  (testing "Timing middleware passes through events"
    (let [calls (atom [])
          handler (fn [event] (swap! calls conj event) :handled)
          wrapped (mw/wrap-timing handler)]
      (is (= :handled (wrapped [:test/event])))
      (is (= 1 (count @calls))))))

(deftest wrap-console-logging-test
  (testing "Excluded events not printed"
    (let [calls (atom [])
          handler (fn [event] (swap! calls conj event) :handled)
          wrapped (mw/wrap-console-logging handler {:exclude #{:tick}})]
      ;; This should work without printing
      (is (= :handled (wrapped [:tick])))
      (is (= 1 (count @calls))))))


;; Middleware Composition Tests


(deftest compose-middleware-test
  (testing "Middleware composes correctly"
    (let [order (atom [])
          mw1 (fn [h] (fn [e] (swap! order conj :mw1-before) (h e) (swap! order conj :mw1-after)))
          mw2 (fn [h] (fn [e] (swap! order conj :mw2-before) (h e) (swap! order conj :mw2-after)))
          handler (fn [e] (swap! order conj :handler))
          composed (mw/compose-middleware handler mw1 mw2)]
      (composed [:test])
      ;; mw1 wraps mw2, so mw1 runs first (outermost)
      (is (= [:mw2-before :mw1-before :handler :mw1-after :mw2-after] @order)))))

(deftest create-dispatcher-test
  (testing "Create dispatcher with all middleware"
    (let [calls (atom [])
          handler (fn [event] (swap! calls conj event) :handled)
          dispatcher (mw/create-dispatcher handler)]
      (is (= :handled (dispatcher [:grid/select-cell 0 0])))
      (is (= 1 (count @calls)))))
  
  (testing "Invalid events blocked by dispatcher"
    (let [calls (atom [])
          handler (fn [event] (swap! calls conj event) :handled)
          dispatcher (mw/create-dispatcher handler {:validation true})]
      (is (nil? (dispatcher [:grid/select-cell "bad"])))
      (is (= 0 (count @calls))))))


;; Undo/Redo Snapshot Tests


(deftest snapshot-state-test
  (testing "Taking snapshots"
    (mw/snapshot-state! (fn [] {:value 1}))
    (mw/snapshot-state! (fn [] {:value 2}))
    (mw/snapshot-state! (fn [] {:value 3}))
    
    (is (= 3 (mw/get-snapshot-count)))))

(deftest undo-redo-test
  (testing "Undo and redo functionality"
    (let [restored-values (atom [])]
      ;; Take some snapshots
      (mw/snapshot-state! (fn [] {:value 1}))
      (mw/snapshot-state! (fn [] {:value 2}))
      (mw/snapshot-state! (fn [] {:value 3}))
      
      (testing "Initially can undo but not redo"
        (is (mw/can-undo-snapshot?))
        (is (false? (mw/can-redo-snapshot?))))
      
      (testing "Undo restores previous state"
        (let [restored (mw/undo! (fn [s] (swap! restored-values conj s)))]
          (is (= {:value 2} restored))
          (is (= [{:value 2}] @restored-values))))
      
      (testing "After undo, can redo"
        (is (mw/can-redo-snapshot?)))
      
      (testing "Redo restores next state"
        (reset! restored-values [])
        (let [restored (mw/redo! (fn [s] (swap! restored-values conj s)))]
          (is (= {:value 3} restored))
          (is (= [{:value 3}] @restored-values))))
      
      (testing "After redo, cannot redo again"
        (is (false? (mw/can-redo-snapshot?)))))))

(deftest undo-redo-without-callback-test
  (testing "Undo and redo without restore callback"
    (mw/snapshot-state! (fn [] {:a 1}))
    (mw/snapshot-state! (fn [] {:a 2}))
    
    ;; Should work even without callback
    (let [restored (mw/undo! nil)]
      (is (= {:a 1} restored)))))

(deftest snapshot-truncation-test
  (testing "New snapshots truncate future history"
    (mw/snapshot-state! (fn [] {:v 1}))
    (mw/snapshot-state! (fn [] {:v 2}))
    (mw/snapshot-state! (fn [] {:v 3}))
    
    ;; Undo twice
    (mw/undo! nil)
    (mw/undo! nil)
    
    ;; Now add new snapshot - should truncate v2 and v3
    (mw/snapshot-state! (fn [] {:v 4}))
    
    ;; Should only have v1 and v4 now
    (is (= 2 (mw/get-snapshot-count)))
    (is (false? (mw/can-redo-snapshot?)))))

(deftest move-history-position-test
  (testing "Move history position"
    (let [handler (fn [e] e)
          wrapped (mw/wrap-history handler)]
      (wrapped [:test/event1])
      (wrapped [:test/event2])
      
      (is (= 2 (mw/get-history-position)))
      
      (mw/move-history-position! :back)
      (is (= 1 (mw/get-history-position)))
      
      (mw/move-history-position! :forward)
      (is (= 2 (mw/get-history-position)))
      
      ;; Cannot go beyond bounds
      (mw/move-history-position! :forward)
      (is (= 2 (mw/get-history-position))))))
