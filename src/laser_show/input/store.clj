(ns laser-show.input.store
  "Fast lock-free store for continuous input values (MIDI CC, OSC floats, etc.).
   Decoupled from the cljfx root UI state atom to eliminate CAS retries,
   GC churn, and UI re-renders on high-frequency controller input."
  (:require [laser-show.common.util :as u]))

;; State holds map of {source-key -> normalized-double-value}
(defonce ^:private !continuous-values
  (atom {}))

(defn set-continuous-value!
  "Sets a continuous input value in the fast store.
   source-key can be [:midi channel control], [:osc path], [:osc channel control], etc.
   value is normalized 0.0-1.0."
  [source-key value]
  (swap! !continuous-values assoc source-key (double value)))

(defn get-continuous-value
  "Gets a continuous input value for the given source-key.
   Returns default-val (default 0.0) if not found."
  ([source-key]
   (get-continuous-value source-key 0.0))
  ([source-key default-val]
   (get @!continuous-values source-key default-val)))

(defn get-all-continuous-values
  "Returns a snapshot map of all current continuous input values.
   Fast O(1) atom deref, safe for high-frequency frame rendering loops."
  []
  @!continuous-values)

(defn clear-continuous-values!
  "Clears all continuous input values. Useful for resets and testing."
  []
  (reset! !continuous-values {}))
