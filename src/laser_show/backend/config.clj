(ns laser-show.backend.config
  "Configuration storage and persistence for the laser show application.
   Handles saving/loading configuration to EDN files."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; ============================================================================
;; Configuration Directory
;; ============================================================================

(def config-dir "config")
(def config-file "config/settings.edn")
(def grid-config-file "config/grid.edn")

(defn ensure-config-dir!
  "Ensure the config directory exists."
  []
  (let [dir (io/file config-dir)]
    (when-not (.exists dir)
      (.mkdirs dir))))

;; ============================================================================
;; Configuration State
;; ============================================================================

(defonce !config
  (atom {:grid {:cols 8
                :rows 4}
         :preview {:width 400
                   :height 400}
         :window {:width 1200
                  :height 800}
         :idn {:host nil
               :port 7255}
         :osc {:enabled false
               :port 8000}
         :midi {:enabled false
                :device nil}}))

;; ============================================================================
;; File I/O
;; ============================================================================

(defn save-edn!
  "Save data to an EDN file."
  [filepath data]
  (ensure-config-dir!)
  (spit filepath (pr-str data)))

(defn load-edn
  "Load data from an EDN file. Returns nil if file doesn't exist."
  [filepath]
  (let [file (io/file filepath)]
    (when (.exists file)
      (edn/read-string (slurp file)))))

;; ============================================================================
;; Configuration Persistence
;; ============================================================================

(defn save-config!
  "Save the current configuration to disk."
  []
  (save-edn! config-file @!config))

(defn load-config!
  "Load configuration from disk and update the config atom."
  []
  (when-let [loaded (load-edn config-file)]
    (reset! !config (merge @!config loaded))))

(defn get-config
  "Get a configuration value by path (vector of keys)."
  [path]
  (get-in @!config path))

(defn set-config!
  "Set a configuration value by path (vector of keys).
   Optionally saves to disk if persist? is true."
  ([path value]
   (set-config! path value false))
  ([path value persist?]
   (swap! !config assoc-in path value)
   (when persist?
     (save-config!))))

(defn update-config!
  "Update a configuration value by path using a function.
   Optionally saves to disk if persist? is true."
  ([path f]
   (update-config! path f false))
  ([path f persist?]
   (swap! !config update-in path f)
   (when persist?
     (save-config!))))

;; ============================================================================
;; Grid Configuration
;; ============================================================================

(defn get-grid-size
  "Get the current grid size as [cols rows]."
  []
  (let [{:keys [cols rows]} (get-config [:grid])]
    [cols rows]))

(defn set-grid-size!
  "Set the grid size."
  [cols rows]
  (set-config! [:grid] {:cols cols :rows rows}))

;; ============================================================================
;; Grid Cell Assignments
;; ============================================================================

(defonce !grid-assignments
  (atom {}))

(defn save-grid-assignments!
  "Save grid cell assignments to disk."
  []
  (save-edn! grid-config-file @!grid-assignments))

(defn load-grid-assignments!
  "Load grid cell assignments from disk."
  []
  (when-let [loaded (load-edn grid-config-file)]
    (reset! !grid-assignments loaded)))

(defn get-cell-assignment
  "Get the preset assigned to a cell [col row]."
  [[col row]]
  (get @!grid-assignments [col row]))

(defn set-cell-assignment!
  "Assign a preset to a cell [col row].
   Optionally saves to disk if persist? is true."
  ([[col row] preset-id]
   (set-cell-assignment! [col row] preset-id false))
  ([[col row] preset-id persist?]
   (if preset-id
     (swap! !grid-assignments assoc [col row] preset-id)
     (swap! !grid-assignments dissoc [col row]))
   (when persist?
     (save-grid-assignments!))))

(defn clear-cell-assignment!
  "Clear the preset assignment for a cell."
  [[col row]]
  (set-cell-assignment! [col row] nil))

(defn clear-all-assignments!
  "Clear all cell assignments."
  []
  (reset! !grid-assignments {}))

(defn get-all-assignments
  "Get all cell assignments as a map."
  []
  @!grid-assignments)

;; ============================================================================
;; IDN Configuration
;; ============================================================================

(defn get-idn-host
  "Get the configured IDN host address."
  []
  (get-config [:idn :host]))

(defn set-idn-host!
  "Set the IDN host address."
  [host]
  (set-config! [:idn :host] host))

(defn get-idn-port
  "Get the configured IDN port."
  []
  (get-config [:idn :port]))

(defn set-idn-port!
  "Set the IDN port."
  [port]
  (set-config! [:idn :port] port))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize configuration by loading from disk."
  []
  (ensure-config-dir!)
  (load-config!)
  (load-grid-assignments!))

(defn shutdown!
  "Save configuration before shutdown."
  []
  (save-config!)
  (save-grid-assignments!))
