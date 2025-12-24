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
  {:settings     "config/settings.edn"
   :grid         "config/grid.edn"
   :projectors   "config/projectors.edn"
   :zones        "config/zones.edn"
   :zone-groups  "config/zone-groups.edn"
   :cues         "config/cues.edn"
   :cue-lists    "config/cue-lists.edn"
   :effects      "config/effects.edn"
   :effects-grid "config/effects-grid.edn"})

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
  {:settings     {:atom state/!config
                  :keys nil}
   :grid         {:atom state/!grid
                  :keys [:cells]}  ; Only persist cells, not selected-cell or size
   :projectors   {:atom state/!projectors
                  :keys nil}
   :zones        {:atom state/!zones
                  :keys nil}
   :zone-groups  {:atom state/!zone-groups
                  :keys nil}
   :cues         {:atom state/!cues
                  :keys nil}
   :cue-lists    {:atom state/!cue-lists
                  :keys nil}
   :effects      {:atom state/!effect-registry
                  :keys nil}
   :effects-grid {:atom state/!effects
                  :keys [:cells]}}) ; Persist effect grid cell assignments

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

;; ============================================================================
;; Project-Based Persistence Functions
;; ============================================================================

(defn get-project-file-paths
  "Get all file paths for a project folder.
   
   Parameters:
   - project-folder - Base folder path for the project
   
   Returns: Map of file-key -> full-path
   
   Example:
   (get-project-file-paths \"/path/to/project\")
   => {:settings \"/path/to/project/settings.edn\", ...}"
  [project-folder]
  {:settings     (str project-folder "/settings.edn")
   :grid         (str project-folder "/grid.edn")
   :projectors   (str project-folder "/projectors.edn")
   :zones        (str project-folder "/zones.edn")
   :zone-groups  (str project-folder "/zone-groups.edn")
   :cues         (str project-folder "/cues.edn")
   :cue-lists    (str project-folder "/cue-lists.edn")
   :effects      (str project-folder "/effects.edn")
   :effects-grid (str project-folder "/effects-grid.edn")})

(defn save-project!
  "Save all state to specified project folder.
   Creates folder if it doesn't exist.
   Updates project state atom with folder and marks as clean.
   
   Parameters:
   - project-folder - Folder path where project should be saved
   
   Returns: true on success, false on failure
   
   Example:
   (save-project! \"/path/to/my-project\")"
  [project-folder]
  (try
    (let [file-paths (get-project-file-paths project-folder)]
      ;; Save each file
      (doseq [[file-key filepath] file-paths]
        (when-let [mapping (get persistent-state-mapping file-key)]
          (let [{:keys [atom keys]} mapping
                data (if keys
                       (select-keys @atom keys)
                       @atom)]
            (ser/save-to-file! filepath data))))
      
      ;; Update project state
      (state/set-project-folder! project-folder)
      (state/mark-project-clean!)
      true)
    (catch Exception e
      (println "Error saving project:" (.getMessage e))
      false)))

(defn load-project!
  "Load all state from specified project folder.
   Merges loaded data into existing state atoms.
   Updates project state atom with folder.
   
   Parameters:
   - project-folder - Folder path to load project from
   
   Returns: true if successful, false if folder doesn't exist or load fails
   
   Example:
   (load-project! \"/path/to/my-project\")"
  [project-folder]
  (try
    (let [folder (java.io.File. project-folder)]
      (if (.exists folder)
        (let [file-paths (get-project-file-paths project-folder)]
          ;; Load each file
          (doseq [[file-key filepath] file-paths]
            (when-let [mapping (get persistent-state-mapping file-key)]
              (let [{:keys [atom keys]} mapping]
                (when-let [data (ser/load-from-file filepath :if-not-found nil)]
                  (if keys
                    (swap! atom merge (select-keys data keys))
                    (swap! atom merge data))))))
          
          ;; Update project state
          (state/set-project-folder! project-folder)
          (state/mark-project-clean!)
          true)
        (do
          (println "Project folder does not exist:" project-folder)
          false)))
    (catch Exception e
      (println "Error loading project:" (.getMessage e))
      false)))

(defn new-project!
  "Reset all state atoms to initial values.
   Clears current project folder and marks as clean.
   This creates a fresh blank project.
   
   Returns: true (always succeeds)
   
   Example:
   (new-project!)"
  []
  (try
    ;; Reset all persistent state atoms
    (state/reset-grid!)
    (state/reset-config!)
    (state/reset-projectors!)
    (state/reset-zones!)
    (state/reset-zone-groups!)
    (state/reset-cues!)
    (state/reset-cue-lists!)
    (state/reset-effect-registry!)
    (state/reset-effects!)
    
    ;; Clear project state
    (state/reset-project!)
    true
    (catch Exception e
      (println "Error creating new project:" (.getMessage e))
      false)))

(defn folder-has-project-files?
  "Check if a folder contains any project files.
   Useful for warning about overwriting existing projects.
   
   Parameters:
   - project-folder - Folder path to check
   
   Returns: true if folder contains .edn files, false otherwise
   
   Example:
   (folder-has-project-files? \"/path/to/folder\")"
  [project-folder]
  (try
    (let [folder (java.io.File. project-folder)]
      (if (.exists folder)
        (let [edn-files (filter #(.endsWith (.getName %) ".edn")
                                (.listFiles folder))]
          (seq edn-files))
        false))
    (catch Exception e
      false)))
