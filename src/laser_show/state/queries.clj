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

;; Beat Accumulation Queries

(defn accumulated-beats []
  (ex/accumulated-beats (raw-state)))

(defn accumulated-ms []
  (ex/accumulated-ms (raw-state)))

(defn last-frame-time []
  (ex/last-frame-time (raw-state)))

(defn phase-offset []
  (ex/phase-offset (raw-state)))

(defn phase-offset-target []
  (ex/phase-offset-target (raw-state)))

(defn resync-rate []
  (ex/resync-rate (raw-state)))

(defn effective-beats []
  (ex/effective-beats (raw-state)))

(defn trigger-time []
  (ex/trigger-time (raw-state)))


;; Zone Groups Queries


(defn zone-groups-items []
  (ex/zone-groups-items (raw-state)))

(defn zone-group [zone-group-id]
  (ex/zone-group (raw-state) zone-group-id))


;; Projector Queries


(defn projectors-items []
  (ex/projectors-items (raw-state)))

(defn projector [projector-id]
  (ex/projector (raw-state) projector-id))

(defn projector-zone-groups [projector-id]
  (ex/projector-zone-groups (raw-state) projector-id))

(defn projector-corner-pin [projector-id]
  (ex/projector-corner-pin (raw-state) projector-id))

(defn projector-tags [projector-id]
  (ex/projector-tags (raw-state) projector-id))

(defn enabled-projectors []
  (ex/enabled-projectors (raw-state)))

(defn projectors-by-zone-group [zone-group-id]
  (ex/projectors-by-zone-group (raw-state) zone-group-id))

(defn projectors-by-tag [tag]
  (ex/projectors-by-tag (raw-state) tag))


;; Virtual Projector Queries


(defn virtual-projectors []
  (ex/virtual-projectors (raw-state)))

(defn virtual-projector [vp-id]
  (ex/virtual-projector (raw-state) vp-id))

(defn virtual-projectors-for-projector [parent-projector-id]
  (ex/virtual-projectors-for-projector (raw-state) parent-projector-id))

(defn enabled-virtual-projectors []
  (ex/enabled-virtual-projectors (raw-state)))

(defn virtual-projectors-by-zone-group [zone-group-id]
  (ex/virtual-projectors-by-zone-group (raw-state) zone-group-id))

(defn virtual-projector-corner-pin [vp-id]
  (ex/virtual-projector-corner-pin (raw-state) vp-id))

(defn virtual-projector-parent [vp-id]
  (ex/virtual-projector-parent (raw-state) vp-id))


;; Routing Queries


(defn all-outputs-for-zone-group [zone-group-id]
  (ex/all-outputs-for-zone-group (raw-state) zone-group-id))

