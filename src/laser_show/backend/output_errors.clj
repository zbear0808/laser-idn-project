(ns laser-show.backend.output-errors
  "Errors on the output path (engine threads, the show clock, effect chains),
   logged without flooding the log.

   report! logs a WARN with the stack the first time a key fails, then at most
   once every 10 s per key: with the stack again if the message changed, else
   with how often it failed meanwhile. ok! clears a key and logs its recovery.

   Keys: [:engine id], [:provider projector-id], [:projector-effects
   projector-id], [:calibration projector-id] (a calibration effect was
   skipped: the message names it), [:item-effects item-id],
   [:global-effects], [:effect instance-id], [:render cell], [:show-clock]."
  (:require [clojure.tools.logging :as log]))

(def ^:const LOG_INTERVAL_MS 10000)

(defonce ^:private errors (atom {}))

(defn- now-ms [] (System/currentTimeMillis))

(defn- message-of
  "A failure's message: a Throwable's class and message, or a string as it is."
  [e]
  (if (string? e)
    e
    (str (.getName (class e)) ": " (.getMessage ^Throwable e))))

(defn- record
  "The key's next entry after a failure at now. :log says what this failure
   should log, if anything."
  [entry now message]
  (cond
    (nil? entry)
    {:message message :count 1 :first-ms now :last-ms now
     :logged-ms now :logged-count 1 :logged-message message :log :stack}

    (>= (- now (:logged-ms entry)) LOG_INTERVAL_MS)
    (assoc entry :message message :count (inc (:count entry)) :last-ms now
           :logged-ms now :logged-count (inc (:count entry)) :logged-message message
           :log (if (= message (:logged-message entry)) :count :stack)
           :since-log (- (inc (:count entry)) (:logged-count entry))
           :log-interval-ms (- now (:logged-ms entry)))

    :else
    (assoc entry :message message :count (inc (:count entry)) :last-ms now :log nil)))

(defn report!
  "Record a failure of key: a Throwable, or a message string. Logs at most
   once per 10 s per key."
  [key e]
  (let [now (now-ms)
        message (message-of e)
        entry (get (swap! errors update key record now message) key)]
    (case (:log entry)
      :stack (if (string? e)
               (log/warn (str "Output error " (pr-str key) ":") message)
               (log/warn e (str "Output error " (pr-str key) ":") message))
      :count (log/warn (format "Output error %s still failing: %d times in the last %d s (%s)"
                               (pr-str key) (:since-log entry)
                               (quot (:log-interval-ms entry) 1000) message))
      nil)))

(defn ok!
  "Key worked. If it was failing, clear it and log the recovery."
  [key]
  (when (contains? @errors key)
    (let [[old _] (swap-vals! errors dissoc key)]
      (when-let [entry (get old key)]
        (log/info (format "Output error %s recovered after %d %s"
                          (pr-str key) (:count entry)
                          (if (= 1 (:count entry)) "failure" "failures")))))))

(defn failing?
  "True if any key is failing."
  []
  (boolean (seq @errors)))

(defn snapshot
  "{key {:message :count :first-ms :last-ms :logged-ms}} of every failing key."
  []
  (update-vals @errors #(select-keys % [:message :count :first-ms :last-ms :logged-ms])))

(defn clear!
  "Forget every error without logging (for tests)."
  []
  (reset! errors {}))
