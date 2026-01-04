(ns laser-show.state.persistent
  "Persistence layer for application state.
   
   This namespace is responsible for:
   - Defining WHICH state is persistent (the mapping lives here)
   - Loading persistent state from disk on app startup
   - Saving persistent state to disk on user request
   
   The state.core namespace is agnostic about persistence - it just holds state.
   This namespace decides what gets saved and loaded."
  (:require [clojure.tools.logging :as log]
            [laser-show.state.core :as state]
            [laser-show.state.serialization :as ser]))


;; File Paths


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


;; Persistence Mapping

;; Defines what state is persistent. Maps file keys to state paths.

(def persistent-state-mapping
  "Defines the mapping between file keys and state paths.
   
   Format: {:file-key {:path [:domain :field] or [:domain]
                       :keys nil-or-vector}}
   
   - :path - Path into the unified state map
   - :keys - nil means save whole domain, vector means select-keys for partial save
   
   When loading: data from file is merged into the state at :path
   When saving: if :keys is nil, save full domain; if :keys is a vector, save (select-keys)"
  {:settings     {:path [:config]
                  :keys nil}
   :grid         {:path [:grid]
                  :keys [:cells]}  ; Only persist cells, not selected-cell or size
   :projectors   {:path [:projectors :items]
                  :keys nil}
   :zones        {:path [:zones :items]
                  :keys nil}
   :zone-groups  {:path [:zone-groups :items]
                  :keys nil}
   :cues         {:path [:cues :items]
                  :keys nil}
   :cue-lists    {:path [:cue-lists :items]
                  :keys nil}
   :effects      {:path [:effect-registry :items]
                  :keys nil}
   :effects-grid {:path [:effects]
                  :keys [:cells]}}) ; Persist effect grid cell assignments


;; Load Functions


(defn load-single!
  "Load a single config file and merge into state.
   Returns true if loaded successfully, false otherwise."
  [file-key]
  (let [filepath (get config-files file-key)
        {:keys [path keys]} (get persistent-state-mapping file-key)]
    (when (and filepath path)
      (if-let [data (ser/load-from-file filepath)]
        (do
          (let [data-to-merge (if keys (select-keys data keys) data)]
            (state/swap-state! (fn [state]
                                 (update-in state path merge data-to-merge))))
          true)
        false))))

(defn load-from-disk!
  "Load all persistent state from disk. Called once at app startup.
   Loads each config file and merges into the state."
  []
  (doseq [file-key (keys persistent-state-mapping)]
    (load-single! file-key)))


;; Save Functions


(defn save-single!
  "Save a single config file from state.
   Returns true if saved successfully, false otherwise."
  [file-key]
  (let [filepath (get config-files file-key)
        {:keys [path keys]} (get persistent-state-mapping file-key)]
    (when (and filepath path)
      (let [domain-data (state/get-in-state path)
            data (if keys
                   (select-keys domain-data keys)
                   domain-data)]
        (ser/save-to-file! filepath data)))))

(defn save-to-disk!
  "Save all persistent state to disk. Called when user saves."
  []
  (doseq [file-key (keys persistent-state-mapping)]
    (save-single! file-key)))



;; Project-Based Persistence Functions


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
   Updates project state with folder and marks as clean.
   
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
          (let [{:keys [path keys]} mapping
                domain-data (state/get-in-state path)
                data (if keys
                       (select-keys domain-data keys)
                       domain-data)]
            (ser/save-to-file! filepath data))))
      
      ;; Update project state
      (state/assoc-in-state! [:project :current-folder] project-folder)
      (state/swap-state! #(-> %
                              (assoc-in [:project :dirty?] false)
                              (assoc-in [:project :last-saved] (System/currentTimeMillis))))
      true)
    (catch Exception e
      (log/error "Error saving project:" (.getMessage e))
      false)))

(defn load-project!
  "Load all state from specified project folder.
   Merges loaded data into existing state.
   Updates project state with folder.
   
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
              (let [{:keys [path keys]} mapping]
                (when-let [data (ser/load-from-file filepath :if-not-found nil)]
                  (let [data-to-merge (if keys (select-keys data keys) data)]
                    (state/swap-state! (fn [state]
                                         (update-in state path merge data-to-merge))))))))
          
          ;; Update project state
          (state/assoc-in-state! [:project :current-folder] project-folder)
          (state/swap-state! #(-> %
                                  (assoc-in [:project :dirty?] false)
                                  (assoc-in [:project :last-saved] (System/currentTimeMillis))))
          true)
        (do
          (log/warn "Project folder does not exist:" project-folder)
          false)))
    (catch Exception e
      (log/error "Error loading project:" (.getMessage e))
      false)))

(defn new-project!
  "Reset all state to initial values.
   Clears current project folder and marks as clean.
   This creates a fresh blank project.
   
   Returns: true (always succeeds)
   
   Example:
   (new-project!)"
  []
  (try
    ;; Reset all state to initial values from registered domains
    (let [domains (state/get-registered-domains)]
      (state/reset-state! (into {}
                                (map (fn [[k v]] [k (:initial v)]))
                                domains)))
    true
    (catch Exception e
      (log/error "Error creating new project:" (.getMessage e))
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
