(ns laser-show.routing.zone-effects
  "The zone routing debug logging (Help's toggle): each log point logs once
   per playback session (frame-service's). Where each item goes is
   routing.targets' since zones plan Z4: the Zone Selector and
   :zone-group-id readers that were here went with it."
  (:require [clojure.tools.logging :as log]))


;; Debug Logging (one-shot per playback session to avoid per-frame spam)
;;
;; Each log point has its own key, so multiple log points can each fire once.

(def ^:private debug-enabled? (atom false))
(def ^:private !debug-logged-keys (atom #{}))  ;; Set of keys that have already logged

(defn enable-routing-debug! [] (reset! debug-enabled? true))
(defn disable-routing-debug! [] (reset! debug-enabled? false))

(defn reset-zone-debug!
  "Call this when starting playback to enable one debug log cycle.
   Clears all logged keys so each log point can fire once."
  []
  (reset! !debug-logged-keys #{}))

(defn routing-debug?
  "True when routing debug logging is enabled."
  []
  @debug-enabled?)

(defn log-once*
  "Function behind log-once. msg-fn is only called if the key hasn't logged yet."
  [log-key msg-fn]
  (when (and @debug-enabled?
             (not (contains? @!debug-logged-keys log-key)))
    (swap! !debug-logged-keys conj log-key)
    (log/info (msg-fn))))

(defmacro log-once
  "Log a message only once per playback session for the given key.
   Each unique key can log once until reset-zone-debug! is called.
   Neither log-key nor msg is evaluated unless routing debug is enabled,
   so callers on the hot path can build messages with format freely.
   Public so it can be used from frame-service for consistent debug logging."
  [log-key msg]
  `(when (routing-debug?)
     (log-once* ~log-key (fn [] ~msg))))
