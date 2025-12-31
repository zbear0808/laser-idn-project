(ns laser-show.state.queries
  "Thread-safe state queries for backend/services.
   
   These functions use get-raw-state() which is safe to call from background
   threads without triggering fx/sub-val assertions. They are NOT memoized
   and should NOT be used in UI components - use laser-show.subs for that.
   
   All query functions delegate to extractors.clj for the actual data access,
   providing thread-safe wrappers around the shared extraction logic.
   
   Usage:
   - Backend services: Use these query functions
   - UI components: Use laser-show.subs with fx/sub-ctx
   - Event handlers: Can use either (if in co-effects) or queries (if async)"
  (:require [laser-show.state.core :as state]
            [laser-show.state.extractors :as ex]))


(defn- raw-state []
  (state/get-raw-state))

(defn bpm []
  (ex/bpm (raw-state)))

(defn tap-times []
  (ex/tap-times (raw-state)))

(defn clipboard []
  (ex/clipboard (raw-state)))

