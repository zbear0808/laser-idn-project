(ns laser-show.backend.config
  "Configuration API that delegates to the centralized database layer.
   This namespace provides backward-compatible APIs that now use database/persistent."
  (:require [laser-show.database.persistent :as persist]))

;; ============================================================================
;; Configuration Access (delegates to database/persistent)
;; ============================================================================

(defn get-config
  "Get a configuration value by path (vector of keys)."
  [path]
  (get-in (persist/get-config) path))

(defn set-config!
  "Set a configuration value by path (vector of keys).
   Always persists to disk."
  ([path value]
   (persist/update-config! path value)))

(defn update-config!
  "Update a configuration value by path using a function.
   Always persists to disk."
  ([path f]
   (let [current-val (get-config path)
         new-val (f current-val)]
     (set-config! path new-val))))

;; ============================================================================
;; Grid Configuration
;; ============================================================================

(defn get-grid-size
  "Get the current grid size as [cols rows]."
  []
  (let [{:keys [cols rows]} (persist/get-grid-config)]
    [cols rows]))

(defn set-grid-size!
  "Set the grid size."
  [cols rows]
  (persist/update-config! [:grid] {:cols cols :rows rows}))

;; ============================================================================
;; Grid Cell Assignments
;; ============================================================================

(defn get-cell-assignment
  "Get the preset assigned to a cell [col row]."
  [[col row]]
  (persist/get-assignment col row))

(defn set-cell-assignment!
  "Assign a preset to a cell [col row].
   Always persists to disk."
  ([[col row] preset-id]
   (if preset-id
     (persist/set-assignment! col row preset-id)
     (persist/clear-assignment! col row))))

(defn clear-cell-assignment!
  "Clear the preset assignment for a cell."
  [[col row]]
  (persist/clear-assignment! col row))

(defn clear-all-assignments!
  "Clear all cell assignments."
  []
  (reset! persist/!grid-assignments {})
  (persist/save-grid-assignments!))

(defn get-all-assignments
  "Get all cell assignments as a map."
  []
  (persist/get-grid-assignments))

;; ============================================================================
;; IDN Configuration
;; ============================================================================

(defn get-idn-host
  "Get the configured IDN host address."
  []
  (get-in (persist/get-config) [:idn :host]))

(defn set-idn-host!
  "Set the IDN host address."
  [host]
  (persist/update-config! [:idn :host] host))

(defn get-idn-port
  "Get the configured IDN port."
  []
  (get-in (persist/get-config) [:idn :port]))

(defn set-idn-port!
  "Set the IDN port."
  [port]
  (persist/update-config! [:idn :port] port))

;; ============================================================================
;; Persistence Functions (delegate to database/persistent)
;; ============================================================================

(defn save-config!
  "Save the current configuration to disk."
  []
  (persist/save-config!))

(defn load-config!
  "Load configuration from disk."
  []
  (persist/load-config!))

(defn save-grid-assignments!
  "Save grid cell assignments to disk."
  []
  (persist/save-grid-assignments!))

(defn load-grid-assignments!
  "Load grid cell assignments from disk."
  []
  (persist/load-grid-assignments!))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize configuration by loading from disk."
  []
  (persist/load-all!))

(defn shutdown!
  "Save configuration before shutdown."
  []
  (persist/save-all!))
