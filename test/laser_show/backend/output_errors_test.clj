(ns laser-show.backend.output-errors-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.logging.test :as log-test]
            [laser-show.backend.output-errors :as oe]))

(use-fixtures :each (fn [f] (oe/clear!) (f) (oe/clear!)))

(defn- entries []
  (filterv #(= "laser-show.backend.output-errors" (str (:logger-ns %))) (log-test/the-log)))

(deftest message-says-what-a-driver-said-test
  (testing "an ex-info's message is written for people (a driver's \"E-stop active\"): as it is, so a card has room for it"
    (is (= "E-stop active" (oe/message (ex-info "E-stop active" {:reopen? true}))))
    (is (= "clojure.lang.ExceptionInfo" (oe/message (ex-info "" {}))) "with nothing to say, its class"))
  (testing "any other throwable keeps its class, which says what failed"
    (is (= "java.net.SocketException: Network is unreachable"
           (oe/message (java.net.SocketException. "Network is unreachable"))))
    (is (= "java.lang.NullPointerException" (oe/message (NullPointerException.)))))
  (is (= "No host" (oe/message "No host")) "a string as it is"))

(deftest logs-once-then-throttled-test
  (let [now (atom 1000)
        e (ex-info "corner pin failed" {})]
    (with-redefs [oe/now-ms (fn [] @now)]
      (log-test/with-log
        (testing "the first failure logs a WARN with the stack"
          (oe/report! [:effect :a] e)
          (is (= 1 (count (entries))))
          (is (= :warn (:level (first (entries)))))
          (is (identical? e (:throwable (first (entries))))))
        (testing "repeats within 10 s only count"
          (dotimes [i 599]
            (reset! now (+ 1000 (* 16 (inc i))))
            (oe/report! [:effect :a] e))
          (is (= 1 (count (entries))))
          (is (= 600 (get-in (oe/snapshot) [[:effect :a] :count]))))
        (testing "after 10 s one WARN says how often it failed meanwhile"
          (reset! now 11000)
          (oe/report! [:effect :a] e)
          (is (= 2 (count (entries))))
          (let [{:keys [level message throwable]} (second (entries))]
            (is (= :warn level))
            (is (nil? throwable))
            (is (str/includes? message "still failing: 600 times in the last 10 s"))))
        (testing "a new message for the same key waits for the next slot, then logs its stack"
          (let [other (ex-info "bad params" {})]
            (reset! now 12000)
            (oe/report! [:effect :a] other)
            (is (= 2 (count (entries))))
            (reset! now 21000)
            (oe/report! [:effect :a] other)
            (is (= 3 (count (entries))))
            (is (identical? other (:throwable (nth (entries) 2))))))
        (testing "another key logs on its own"
          (oe/report! [:preview] (AssertionError. "boom"))
          (is (= 4 (count (entries)))))))))

(deftest ok-logs-recovery-once-test
  (log-test/with-log
    (let [e (ex-info "provider failed" {})]
      (dotimes [_ 5] (oe/report! [:provider :a] e))
      (is (= 1 (count (entries))))
      (oe/ok! [:provider :a])
      (is (= 2 (count (entries))))
      (let [{:keys [level message]} (second (entries))]
        (is (= :info level))
        (is (str/includes? message "recovered after 5 failures")))
      (is (not (contains? (oe/snapshot) [:provider :a])))
      (oe/ok! [:provider :a])
      (oe/ok! [:never-failed])
      (is (= 2 (count (entries))) "ok! of a key that isn't failing logs nothing")
      (testing "failing again after recovery logs the stack again"
        (oe/report! [:provider :a] e)
        (is (= 3 (count (entries))))
        (is (identical? e (:throwable (nth (entries) 2))))))))

(deftest forget-clears-a-key-without-a-recovery-test
  ;; Follow-up B10: a streaming engine's next run takes [:engine id] over
  ;; from the run before, whose failure isn't a recovery
  (log-test/with-log
    (let [e (ex-info "send failed" {})]
      (oe/report! [:engine :p] e)
      (oe/report! [:engine :q] e)
      (is (= 2 (count (entries))))
      (oe/forget! [:engine :p])
      (is (= #{[:engine :q]} (set (keys (oe/snapshot)))) "only that key goes")
      (is (= 2 (count (entries))) "and nothing is logged")
      (oe/forget! [:never-failed])
      (is (= #{[:engine :q]} (set (keys (oe/snapshot)))))
      (testing "the key's next failure logs its stack, as a first one"
        (oe/report! [:engine :p] e)
        (is (= 3 (count (entries))))
        (is (identical? e (:throwable (nth (entries) 2))))))))
