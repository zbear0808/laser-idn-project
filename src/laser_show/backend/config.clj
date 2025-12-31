(ns laser-show.backend.config
  "Configuration access layer for backend services.
   Uses the queries namespace for thread-safe state access."
  (:require [laser-show.state.queries :as queries]))

;; ============================================================================
;; Configuration Access
;; ============================================================================

(defn get-config-value
  "Get a configuration value at the given path."
  [path]
  (get-in (queries/config) path))

(defn get-config
  "Get the full config map."
  []
  (queries/config))

;; ============================================================================
;; Grid Configuration
;; ============================================================================

(defn get-grid-dimensions
  "Get grid dimensions as [cols rows]."
  []
  (let [{:keys [cols rows]} (queries/grid-config)]
    [cols rows]))

(defn get-grid-config
  "Get grid configuration {:cols :rows}."
  []
  (queries/grid-config))

;; ============================================================================
;; Cell Access
;; ============================================================================

(defn get-cell
  "Get a cell at a grid position."
  [[col row]]
  (queries/cell col row))

(defn get-cell-at
  "Get a cell at a grid position (alternative API)."
  [col row]
  (queries/cell col row))

(defn get-all-cells
  "Get all grid cells."
  []
  (queries/grid-cells))

;; ============================================================================
;; IDN Configuration
;; ============================================================================

(defn get-idn-host
  "Get the IDN target host."
  []
  (get-in (queries/config) [:idn :host]))

(defn get-idn-port
  "Get the IDN target port."
  []
  (get-in (queries/config) [:idn :port]))

;; ============================================================================
;; Window Configuration
;; ============================================================================

(defn get-window-config
  "Get window configuration {:width :height}."
  []
  (queries/window-config))

;; ============================================================================
;; Preview Configuration
;; ============================================================================

(defn get-preview-config
  "Get preview panel configuration {:width :height}."
  []
  (queries/preview-config))

;; ============================================================================
;; Time Utilities
;; ============================================================================

(defn get-current-time-ms
  "Get the current time in milliseconds."
  []
  (System/currentTimeMillis))
