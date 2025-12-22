(ns laser-show.database.persistent
  "Persistent configuration state for the laser show application.
   This state is saved to disk and restored on startup."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; ============================================================================
;; Configuration State
;; ============================================================================

(defonce !config
  (atom {:grid {:cols 8 :rows 4}
         :window {:width 1200 :height 800}
         :preview {:width 400 :height 400}
         :idn {:host nil :port 7255}
         :osc {:enabled false :port 8000}
         :midi {:enabled false :device nil}}))

(defonce !grid-assignments
  (atom {}))  ; {[col row] preset-id}

(defonce !projectors
  (atom {}))  ; {projector-id projector-config}

(defonce !zones
  (atom {}))  ; {zone-id zone-config}

(defonce !zone-groups
  (atom {}))  ; {group-id group-config}

(defonce !cues
  (atom {}))  ; {cue-id cue-definition}

(defonce !cue-lists
  (atom {}))  ; {list-id cue-list}

(defonce !effect-registry
  (atom {}))  ; {effect-id effect-definition}

;; ============================================================================
;; File Paths
;; ============================================================================

(def config-dir "config")

(def config-files
  {:settings      "config/settings.edn"
   :grid          "config/grid.edn"
   :projectors    "config/projectors.edn"
   :zones         "config/zones.edn"
   :zone-groups   "config/zone-groups.edn"
   :cues          "config/cues.edn"
   :cue-lists     "config/cue-lists.edn"
   :effects       "config/effects.edn"})

;; ============================================================================
;; Low-level Persistence Functions
;; ============================================================================

(defn ensure-config-dir!
  "Ensure the config directory exists"
  []
  (let [dir (io/file config-dir)]
    (when-not (.exists dir)
      (.mkdirs dir))))

(defn save-edn!
  "Save data to an EDN file"
  [filepath data]
  (ensure-config-dir!)
  (spit filepath (pr-str data)))

(defn load-edn
  "Load data from an EDN file, returns nil if file doesn't exist"
  [filepath]
  (let [file (io/file filepath)]
    (when (.exists file)
      (try
        (edn/read-string (slurp file))
        (catch Exception e
          (println "Error loading" filepath ":" (.getMessage e))
          nil)))))

;; ============================================================================
;; Save Functions - Individual State Atoms
;; ============================================================================

(defn save-config!
  "Save application settings to disk"
  []
  (save-edn! (:settings config-files) @!config))

(defn save-grid-assignments!
  "Save grid assignments to disk"
  []
  (save-edn! (:grid config-files) @!grid-assignments))

(defn save-projectors!
  "Save projector configurations to disk"
  []
  (save-edn! (:projectors config-files) @!projectors))

(defn save-zones!
  "Save zone definitions to disk"
  []
  (save-edn! (:zones config-files) @!zones))

(defn save-zone-groups!
  "Save zone group definitions to disk"
  []
  (save-edn! (:zone-groups config-files) @!zone-groups))

(defn save-cues!
  "Save cue library to disk"
  []
  (save-edn! (:cues config-files) @!cues))

(defn save-cue-lists!
  "Save cue lists to disk"
  []
  (save-edn! (:cue-lists config-files) @!cue-lists))

(defn save-effects!
  "Save effect registry to disk"
  []
  (save-edn! (:effects config-files) @!effect-registry))

(defn save-all!
  "Save all persistent state to disk"
  []
  (save-config!)
  (save-grid-assignments!)
  (save-projectors!)
  (save-zones!)
  (save-zone-groups!)
  (save-cues!)
  (save-cue-lists!)
  (save-effects!))

;; ============================================================================
;; Load Functions - Individual State Atoms
;; ============================================================================

(defn load-config!
  "Load application settings from disk"
  []
  (when-let [loaded (load-edn (:settings config-files))]
    (reset! !config (merge @!config loaded))))

(defn load-grid-assignments!
  "Load grid assignments from disk"
  []
  (when-let [loaded (load-edn (:grid config-files))]
    (reset! !grid-assignments loaded)))

(defn load-projectors!
  "Load projector configurations from disk"
  []
  (when-let [loaded (load-edn (:projectors config-files))]
    (reset! !projectors loaded)))

(defn load-zones!
  "Load zone definitions from disk"
  []
  (when-let [loaded (load-edn (:zones config-files))]
    (reset! !zones loaded)))

(defn load-zone-groups!
  "Load zone group definitions from disk"
  []
  (when-let [loaded (load-edn (:zone-groups config-files))]
    (reset! !zone-groups loaded)))

(defn load-cues!
  "Load cue library from disk"
  []
  (when-let [loaded (load-edn (:cues config-files))]
    (reset! !cues loaded)))

(defn load-cue-lists!
  "Load cue lists from disk"
  []
  (when-let [loaded (load-edn (:cue-lists config-files))]
    (reset! !cue-lists loaded)))

(defn load-effects!
  "Load effect registry from disk"
  []
  (when-let [loaded (load-edn (:effects config-files))]
    (reset! !effect-registry loaded)))

(defn load-all!
  "Load all persistent state from disk"
  []
  (load-config!)
  (load-grid-assignments!)
  (load-projectors!)
  (load-zones!)
  (load-zone-groups!)
  (load-cues!)
  (load-cue-lists!)
  (load-effects!))

;; ============================================================================
;; Accessor Functions - Config
;; ============================================================================

(defn get-config []
  "Get entire config map"
  @!config)

(defn get-grid-config []
  "Get grid configuration"
  (:grid @!config))

(defn get-window-config []
  "Get window configuration"
  (:window @!config))

(defn get-idn-config []
  "Get IDN configuration"
  (:idn @!config))

(defn update-config! [path value]
  "Update a config value at the given path"
  (swap! !config assoc-in path value)
  (save-config!))

;; ============================================================================
;; Accessor Functions - Grid Assignments
;; ============================================================================

(defn get-grid-assignments []
  "Get all grid assignments"
  @!grid-assignments)

(defn get-assignment [col row]
  "Get preset assignment for a specific cell"
  (get @!grid-assignments [col row]))

(defn set-assignment! [col row preset-id]
  "Set preset assignment for a specific cell"
  (swap! !grid-assignments assoc [col row] preset-id)
  (save-grid-assignments!))

(defn clear-assignment! [col row]
  "Clear preset assignment for a specific cell"
  (swap! !grid-assignments dissoc [col row])
  (save-grid-assignments!))

;; ============================================================================
;; Accessor Functions - Projectors
;; ============================================================================

(defn get-projectors []
  "Get all projectors"
  @!projectors)

(defn get-projector [projector-id]
  "Get a specific projector"
  (get @!projectors projector-id))

(defn add-projector! [projector-id config]
  "Add or update a projector"
  (swap! !projectors assoc projector-id config)
  (save-projectors!))

(defn remove-projector! [projector-id]
  "Remove a projector"
  (swap! !projectors dissoc projector-id)
  (save-projectors!))

;; ============================================================================
;; Accessor Functions - Zones
;; ============================================================================

(defn get-zones []
  "Get all zones"
  @!zones)

(defn get-zone [zone-id]
  "Get a specific zone"
  (get @!zones zone-id))

(defn add-zone! [zone-id config]
  "Add or update a zone"
  (swap! !zones assoc zone-id config)
  (save-zones!))

(defn remove-zone! [zone-id]
  "Remove a zone"
  (swap! !zones dissoc zone-id)
  (save-zones!))

;; ============================================================================
;; Accessor Functions - Zone Groups
;; ============================================================================

(defn get-zone-groups []
  "Get all zone groups"
  @!zone-groups)

(defn get-zone-group [group-id]
  "Get a specific zone group"
  (get @!zone-groups group-id))

(defn add-zone-group! [group-id config]
  "Add or update a zone group"
  (swap! !zone-groups assoc group-id config)
  (save-zone-groups!))

(defn remove-zone-group! [group-id]
  "Remove a zone group"
  (swap! !zone-groups dissoc group-id)
  (save-zone-groups!))

;; ============================================================================
;; Accessor Functions - Cues
;; ============================================================================

(defn get-cues []
  "Get all cues"
  @!cues)

(defn get-cue [cue-id]
  "Get a specific cue"
  (get @!cues cue-id))

(defn add-cue! [cue-id definition]
  "Add or update a cue"
  (swap! !cues assoc cue-id definition)
  (save-cues!))

(defn remove-cue! [cue-id]
  "Remove a cue"
  (swap! !cues dissoc cue-id)
  (save-cues!))

;; ============================================================================
;; Accessor Functions - Cue Lists
;; ============================================================================

(defn get-cue-lists []
  "Get all cue lists"
  @!cue-lists)

(defn get-cue-list [list-id]
  "Get a specific cue list"
  (get @!cue-lists list-id))

(defn add-cue-list! [list-id cue-list]
  "Add or update a cue list"
  (swap! !cue-lists assoc list-id cue-list)
  (save-cue-lists!))

(defn remove-cue-list! [list-id]
  "Remove a cue list"
  (swap! !cue-lists dissoc list-id)
  (save-cue-lists!))

;; ============================================================================
;; Auto-save Support
;; ============================================================================

(defn enable-auto-save!
  "Add watchers to automatically save state on changes"
  []
  (add-watch !config ::auto-save
             (fn [_ _ _ _] (save-config!)))
  (add-watch !grid-assignments ::auto-save
             (fn [_ _ _ _] (save-grid-assignments!)))
  (add-watch !projectors ::auto-save
             (fn [_ _ _ _] (save-projectors!)))
  (add-watch !zones ::auto-save
             (fn [_ _ _ _] (save-zones!)))
  (add-watch !zone-groups ::auto-save
             (fn [_ _ _ _] (save-zone-groups!)))
  (add-watch !cues ::auto-save
             (fn [_ _ _ _] (save-cues!)))
  (add-watch !cue-lists ::auto-save
             (fn [_ _ _ _] (save-cue-lists!)))
  (add-watch !effect-registry ::auto-save
             (fn [_ _ _ _] (save-effects!))))

(defn disable-auto-save!
  "Remove auto-save watchers"
  []
  (remove-watch !config ::auto-save)
  (remove-watch !grid-assignments ::auto-save)
  (remove-watch !projectors ::auto-save)
  (remove-watch !zones ::auto-save)
  (remove-watch !zone-groups ::auto-save)
  (remove-watch !cues ::auto-save)
  (remove-watch !cue-lists ::auto-save)
  (remove-watch !effect-registry ::auto-save))
