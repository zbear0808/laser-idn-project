(ns laser-show.state.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.logging.test :as log-test]
            [laser-show.state.core :as state]))

(use-fixtures :each
  (fn [t]
    (state/init-state! {:timing {:bpm 120.0}})
    (try
      (t)
      (finally
        (state/guard-writes! nil)))))

(def ^:private writes
  {"swap-state!" #(state/swap-state! assoc-in [:timing :bpm] 99.0)
   "reset-state!" #(state/reset-state! {:timing {:bpm 99.0}})
   "reset-state-vals!" #(state/reset-state-vals! {:timing {:bpm 99.0}})
   "assoc-in-state!" #(state/assoc-in-state! [:timing :bpm] 99.0)
   "init-state!" #(state/init-state! {:timing {:bpm 99.0}})})

(defn- bpm [] (get-in (state/get-raw-state) [:timing :bpm]))

(deftest no-guard-writes-from-any-thread-test
  (doseq [[write-name write!] writes]
    (testing write-name
      (state/init-state! {:timing {:bpm 120.0}})
      @(future (write!))
      (is (= 99.0 (bpm))))))

(deftest strict-guard-refuses-a-write-off-the-event-thread-test
  (doseq [[write-name write!] writes]
    (testing write-name
      (state/guard-writes! nil)
      (state/init-state! {:timing {:bpm 120.0}})
      (state/guard-writes! {:allowed? (constantly false) :strict? true})
      (let [e (try (write!) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "the write throws")
        (is (re-find #"outside the event pipeline" (ex-message e)))
        (is (= (.getName (Thread/currentThread)) (:thread (ex-data e)))))
      (is (= 120.0 (bpm)) "the state is unchanged"))))

(deftest guard-allows-the-event-thread-test
  (let [event-thread (atom nil)
        allowed? #(= @event-thread (Thread/currentThread))]
    (state/guard-writes! {:allowed? allowed? :strict? true})
    (doseq [[write-name write!] writes]
      (testing write-name
        (state/guard-writes! nil)
        (state/init-state! {:timing {:bpm 120.0}})
        (state/guard-writes! {:allowed? allowed? :strict? true})
        @(future (reset! event-thread (Thread/currentThread))
                 (write!))
        (is (= 99.0 (bpm)))))))

(deftest lenient-guard-logs-and-writes-test
  (log-test/with-log
    (state/guard-writes! {:allowed? (constantly false) :strict? false})
    (state/swap-state! assoc-in [:timing :bpm] 99.0)
    (is (= 99.0 (bpm)) "the write lands")
    (is (= 1 (count (filter #(= :error (:level %)) (log-test/the-log))))
        "one ERROR")
    (is (log-test/logged? 'laser-show.state.core :error
                          clojure.lang.ExceptionInfo
                          #"outside the event pipeline"))))
