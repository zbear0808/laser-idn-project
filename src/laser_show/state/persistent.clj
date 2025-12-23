(ns laser-show.state.persistent
  "Persistence layer for application state.
   
   This namespace is responsible for:
   - Defining WHICH state is persistent (the mapping lives here, not in atoms.clj)
   - Loading persistent state from disk on app startup
   - Saving persistent state to disk on user request
   
   The atoms.clj namespace is agnostic about persistence - it just holds state.
   This namespace decides what gets saved and loaded."
  (:require [laser-show.state.atoms :as state]
            [laser-show.state.serialization :as ser]))

;; ============================================================================
;; File Paths
;; ============================================================================

(def config-dir "config")

(def config-files
  "Map of config type to file path."
  {:settings    "config/settings.edn"
   :grid        "config/grid.edn"
   :projectors  "config/projectors.edn"
   :zones       "config/zones.edn"
   :zone-groups "config/zone-groups.edn"
   :cues        "config/cues.edn"
   :cue-lists   "config/cue-lists.edn"
   :effects     "config/effects.edn"})

;; ============================================================================
;; Persistence Mapping
;; ============================================================================
;; Defines what state is persistent. The atoms.clj namespace doesn't care
;; about persistence - this mapping is the single source of truth for what
;; gets saved/loaded.

(def persistent-state-mapping
  "Defines the mapping between file keys, atoms, and what keys to persist.
   
   Format: {:file-key {:atom atom-ref
                       :keys nil-or-vector}}
   
   - :atom - Reference to the atom in state namespace
   - :keys - nil means save whole atom, vector means select-keys for partial save
   
   When loading: data from file is merged into the atom
   When saving: if :keys is nil, save @atom; if :keys is a vector, save (select-keys @atom keys)"
  {:settings    {:atom state/!config
                 :keys nil}
   :grid        {:atom state/!grid
                 :keys [:cells]}  ; Only persist cells, not selected-cell or size
   :projectors  {:atom state/!projectors
                 :keys nil}
   :zones       {:atom state/!zones
                 :keys nil}
   :zone-groups {:atom state/!zone-groups
                 :keys nil}
   :cues        {:atom state/!cues
                 :keys nil}
   :cue-lists   {:atom state/!cue-lists
                 :keys nil}
   :effects     {:atom state/!effect-registry
                 :keys nil}})

;; ============================================================================
;; Load Functions
;; ============================================================================

(defn load-single!
  "Load a single config file and merge into its atom.
   Returns true if loaded successfully, false otherwise."
  [file-key]
  (let [filepath (get config-files file-key)
        {:keys [atom keys]} (get persistent-state-mapping file-key)]
    (when (and filepath atom)
      (if-let [data (ser/load-from-file filepath)]
        (do
          (if keys
            (swap! atom merge (select-keys data keys))
            (swap! atom merge data))
          true)
        false))))

(defn load-from-disk!
  "Load all persistent state from disk. Called once at app startup.
   Loads each config file and merges into the corresponding atom."
  []
  (doseq [file-key (keys persistent-state-mapping)]
    (load-single! file-key)))

;; ============================================================================
;; Save Functions
;; ============================================================================

(defn save-single!
  "Save a single config file from its atom.
   Returns true if saved successfully, false otherwise."
  [file-key]
  (let [filepath (get config-files file-key)
        {:keys [atom keys]} (get persistent-state-mapping file-key)]
    (when (and filepath atom)
      (let [data (if keys
                   (select-keys @atom keys)
                   @atom)]
        (ser/save-to-file! filepath data)))))

(defn save-to-disk!
  "Save all persistent state to disk. Called when user saves."
  []
  (doseq [file-key (keys persistent-state-mapping)]
    (save-single! file-key)))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn get-config-path
  "Get the file path for a config type."
  [file-key]
  (get config-files file-key))

(defn persistent-keys
  "Get a list of all persistent state keys."
  []
  (keys persistent-state-mapping))
