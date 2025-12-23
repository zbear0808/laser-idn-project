(ns laser-show.backend.config
  "Configuration API that delegates to the centralized state layer.
   This namespace provides convenient APIs for common configuration access."
  (:require [laser-show.state.atoms :as state]
            [laser-show.state.persistent :as persist]))

;; ============================================================================
;; Configuration Access
;; ============================================================================

(defn get-config
  "Get a configuration value by path (vector of keys)."
  [path]
  (get-in (state/get-config) path))

(defn set-config!
  "Set a configuration value by path (vector of keys)."
  [path value]
  (state/update-config! path value))

(defn update-config!
  "Update a configuration value by path using a function."
  [path f]
  (let [current-val (get-config path)
        new-val (f current-val)]
    (set-config! path new-val)))

;; ============================================================================
;; Grid Configuration
;; ============================================================================

(defn get-grid-size
  "Get the current grid size as [cols rows]."
  []
  (let [{:keys [cols rows]} (state/get-grid-config)]
    [cols rows]))

(defn set-grid-size!
  "Set the grid size."
  [cols rows]
  (state/update-config! [:grid] {:cols cols :rows rows}))

;; ============================================================================
;; Grid Cell Assignments
;; ============================================================================

(defn get-cell-assignment
  "Get the preset assigned to a cell [col row].
   Returns the cell data map or nil."
  [[col row]]
  (state/get-cell col row))

(defn set-cell-assignment!
  "Assign a preset to a cell [col row]."
  [[col row] preset-id]
  (if preset-id
    (state/set-cell-preset! col row preset-id)
    (state/clear-cell! col row)))

(defn clear-cell-assignment!
  "Clear the preset assignment for a cell."
  [[col row]]
  (state/clear-cell! col row))

(defn clear-all-assignments!
  "Clear all cell assignments."
  []
  (swap! state/!grid assoc :cells {}))

(defn get-all-assignments
  "Get all cell assignments as a map."
  []
  (state/get-grid-cells))

;; ============================================================================
;; IDN Configuration
;; ============================================================================

(defn get-idn-host
  "Get the configured IDN host address."
  []
  (get-in (state/get-config) [:idn :host]))

(defn set-idn-host!
  "Set the IDN host address."
  [host]
  (state/update-config! [:idn :host] host))

(defn get-idn-port
  "Get the configured IDN port."
  []
  (get-in (state/get-config) [:idn :port]))

(defn set-idn-port!
  "Set the IDN port."
  [port]
  (state/update-config! [:idn :port] port))

;; ============================================================================
;; Persistence Functions
;; ============================================================================

(defn save-config!
  "Save the current configuration to disk."
  []
  (persist/save-single! :settings))

(defn load-config!
  "Load configuration from disk."
  []
  (persist/load-single! :settings))

(defn save-grid!
  "Save grid cell assignments to disk."
  []
  (persist/save-single! :grid))

(defn load-grid!
  "Load grid cell assignments from disk."
  []
  (persist/load-single! :grid))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize configuration by loading from disk."
  []
  (persist/load-from-disk!))

(defn shutdown!
  "Save configuration before shutdown."
  []
  (persist/save-to-disk!))
