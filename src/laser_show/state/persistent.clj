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
  "Map of config type to file path.
   
   Consolidated from 9 files to 4 files:
   - project-metadata.edn: Settings, grid config, BPM
   - hardware.edn: Projectors, zones, zone-groups
   - content.edn: All chains (cue-chains, effect-chains, projector-effects, zone-effects)
   - mappings.edn: Input router handlers (future)"
  {:project-metadata "config/project-metadata.edn"
   :hardware        "config/hardware.edn"
   :content         "config/content.edn"})


;; Persistence Mapping

;; Defines what state is persistent. Maps file keys to state paths.

(def persistent-state-mapping
  "Defines the mapping between file keys and state paths.
   
   Format: {:file-key {:paths [{:path [:domain :field] :keys nil-or-vector}]}}
   
   - :paths - Vector of path specs to save/load for this file
   - :path - Path into the unified state map
   - :keys - nil means save whole domain, vector means select-keys for partial save
   
   When loading: data from file is merged into the state at each :path
   When saving: if :keys is nil, save full domain; if :keys is a vector, save (select-keys)"
  {:project-metadata
   {:paths [{:path [:config] :keys nil}
            {:path [:timing] :keys [:bpm]}
            {:path [:grid] :keys [:size]}]}
   
   :hardware
   {:paths [{:path [:projectors :items] :keys nil}
            {:path [:zones :items] :keys nil}
            {:path [:zone-groups :items] :keys nil}]}
   
   :content
   {:paths [{:path [:chains :cue-chains] :keys nil}
            {:path [:chains :effect-chains] :keys nil}
            {:path [:chains :projector-effects] :keys nil}
            {:path [:chains :zone-effects] :keys nil}]}})


;; Load Functions


(defn- load-and-merge-paths!
  "Load data from file and merge into state at multiple paths.
   
   Parameters:
   - filepath: Path to file to load
   - path-specs: Vector of {:path [:domain :field] :keys nil-or-vector}
   
   Returns: true if loaded successfully, false otherwise"
  [filepath path-specs]
  (if-let [file-data (ser/load-from-file filepath)]
    (do
      (doseq [{:keys [path keys]} path-specs]
        (when path
          (let [;; Extract data for this path from file
                ;; For nested paths like [:chains :cue-chains], get-in from file
                data-from-file (get-in file-data path)
                ;; Apply key filtering if specified
                data-to-merge (if (and keys data-from-file)
                                (select-keys data-from-file keys)
                                data-from-file)]
            (when data-to-merge
              (state/swap-state! (fn [state]
                                   (update-in state path merge data-to-merge)))))))
      true)
    false))

(defn load-single!
  "Load a single config file and merge into state.
   Returns true if loaded successfully, false otherwise."
  [file-key]
  (let [filepath (get config-files file-key)
        {:keys [paths]} (get persistent-state-mapping file-key)]
    (when (and filepath paths)
      (load-and-merge-paths! filepath paths))))

(defn load-from-disk!
  "Load all persistent state from disk. Called once at app startup.
   Loads each config file and merges into the state."
  []
  (doseq [file-key (keys persistent-state-mapping)]
    (load-single! file-key)))


;; Save Functions


(defn- collect-data-for-paths
  "Collect data from state for multiple paths into a single map.
   
   Parameters:
   - path-specs: Vector of {:path [:domain :field] :keys nil-or-vector}
   
   Returns: Map with data organized by top-level domain keys"
  [path-specs]
  (reduce
    (fn [acc {:keys [path keys]}]
      (if path
        (let [domain-key (first path)
              domain-data (state/get-in-state path)
              data (if keys
                     (select-keys domain-data keys)
                     domain-data)]
          (if (= 1 (count path))
            ;; Top-level domain - merge directly
            (assoc acc domain-key data)
            ;; Nested path - need to preserve structure
            (assoc-in acc path data)))
        acc))
    {}
    path-specs))

(defn save-single!
  "Save a single config file from state.
   Returns true if saved successfully, false otherwise."
  [file-key]
  (let [filepath (get config-files file-key)
        {:keys [paths]} (get persistent-state-mapping file-key)]
    (when (and filepath paths)
      (let [data (collect-data-for-paths paths)]
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
   => {:project-metadata \"/path/to/project/project-metadata.edn\", ...}"
  [project-folder]
  {:project-metadata (str project-folder "/project-metadata.edn")
   :hardware        (str project-folder "/hardware.edn")
   :content         (str project-folder "/content.edn")})

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
        (when-let [{:keys [paths]} (get persistent-state-mapping file-key)]
          (let [data (collect-data-for-paths paths)]
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
            (when-let [{:keys [paths]} (get persistent-state-mapping file-key)]
              (load-and-merge-paths! filepath paths)))
          
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
