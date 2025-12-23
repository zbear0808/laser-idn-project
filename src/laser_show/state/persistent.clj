(ns laser-show.state.persistent
  "Persistent configuration state for the laser show application.
   This state is saved to disk and restored on startup."
  (:require [laser-show.state.serialization :as ser]
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
;; Save State Atoms to Disk
;; ============================================================================

(defn save-config!

  []
  (ser/save-to-file! (:settings config-files) @!config))

(defn save-grid-assignments!

  []
  (ser/save-to-file! (:grid config-files) @!grid-assignments))

(defn save-projectors!

  []
  (ser/save-to-file! (:projectors config-files) @!projectors))

(defn save-zones!

  []
  (ser/save-to-file! (:zones config-files) @!zones))

(defn save-zone-groups!

  []
  (ser/save-to-file! (:zone-groups config-files) @!zone-groups))

(defn save-cues!

  []
  (ser/save-to-file! (:cues config-files) @!cues))

(defn save-cue-lists!

  []
  (ser/save-to-file! (:cue-lists config-files) @!cue-lists))

(defn save-effects!

  []
  (ser/save-to-file! (:effects config-files) @!effect-registry))

(defn save-all!

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
  
  []
  (when-let [loaded (ser/load-from-file (:settings config-files))]
    (reset! !config (merge @!config loaded))))

(defn load-grid-assignments!
  
  []
  (when-let [loaded (ser/load-from-file (:grid config-files))]
    (reset! !grid-assignments loaded)))

(defn load-projectors!
  
  []
  (when-let [loaded (ser/load-from-file (:projectors config-files))]
    (reset! !projectors loaded)))

(defn load-zones!
  
  []
  (when-let [loaded (ser/load-from-file (:zones config-files))]
    (reset! !zones loaded)))

(defn load-zone-groups!
  
  []
  (when-let [loaded (ser/load-from-file (:zone-groups config-files))]
    (reset! !zone-groups loaded)))

(defn load-cues!
  
  []
  (when-let [loaded (ser/load-from-file (:cues config-files))]
    (reset! !cues loaded)))

(defn load-cue-lists!
  
  []
  (when-let [loaded (ser/load-from-file (:cue-lists config-files))]
    (reset! !cue-lists loaded)))

(defn load-effects!
  
  []
  (when-let [loaded (ser/load-from-file (:effects config-files))]
    (reset! !effect-registry loaded)))

(defn load-all!
  
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

  @!config)

(defn get-grid-config []

  (:grid @!config))

(defn get-window-config []

  (:window @!config))

(defn get-idn-config []

  (:idn @!config))

(defn update-config! [path value]
  (swap! !config assoc-in path value)
  (save-config!))

;; ============================================================================
;; Accessor Functions - Grid Assignments
;; ============================================================================

(defn get-grid-assignments []

  @!grid-assignments)

(defn get-assignment [col row]

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

  @!projectors)

(defn get-projector [projector-id]

  (get @!projectors projector-id))

(defn add-projector! [projector-id config]

  (swap! !projectors assoc projector-id config)
  (save-projectors!))

(defn remove-projector! [projector-id]

  (swap! !projectors dissoc projector-id)
  (save-projectors!))

;; ============================================================================
;; Accessor Functions - Zones
;; ============================================================================

(defn get-zones []

  @!zones)

(defn get-zone [zone-id]

  (get @!zones zone-id))

(defn add-zone! [zone-id config]

  (swap! !zones assoc zone-id config)
  (save-zones!))

(defn remove-zone! [zone-id]

  (swap! !zones dissoc zone-id)
  (save-zones!))

;; ============================================================================
;; Accessor Functions - Zone Groups
;; ============================================================================

(defn get-zone-groups []

  @!zone-groups)

(defn get-zone-group [group-id]

  (get @!zone-groups group-id))

(defn add-zone-group! [group-id config]

  (swap! !zone-groups assoc group-id config)
  (save-zone-groups!))

(defn remove-zone-group! [group-id]

  (swap! !zone-groups dissoc group-id)
  (save-zone-groups!))

;; ============================================================================
;; Accessor Functions - Cues
;; ============================================================================

(defn get-cues []

  @!cues)

(defn get-cue [cue-id]

  (get @!cues cue-id))

(defn add-cue! [cue-id definition]

  (swap! !cues assoc cue-id definition)
  (save-cues!))

(defn remove-cue! [cue-id]

  (swap! !cues dissoc cue-id)
  (save-cues!))

;; ============================================================================
;; Accessor Functions - Cue Lists
;; ============================================================================

(defn get-cue-lists []

  @!cue-lists)

(defn get-cue-list [list-id]

  (get @!cue-lists list-id))

(defn add-cue-list! [list-id cue-list]

  (swap! !cue-lists assoc list-id cue-list)
  (save-cue-lists!))

(defn remove-cue-list! [list-id]

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

  []
  (remove-watch !config ::auto-save)
  (remove-watch !grid-assignments ::auto-save)
  (remove-watch !projectors ::auto-save)
  (remove-watch !zones ::auto-save)
  (remove-watch !zone-groups ::auto-save)
  (remove-watch !cues ::auto-save)
  (remove-watch !cue-lists ::auto-save)
  (remove-watch !effect-registry ::auto-save))
