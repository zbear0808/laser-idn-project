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


(def default-projects-dir
  "Default directory for saving projects."
  (str (System/getProperty "user.home") "/LaserShowProjects"))

(def config-files
  "Map of config type to file path.
   
   - project-metadata.edn: Settings, grid config, BPM
   - hardware.edn: Projectors, zone-groups, virtual-projectors
   - content.edn: All chains (cue-chains, effect-chains, projector-effects)
   - mappings.edn: Input router handlers (future)"
  {:project-metadata "config/project-metadata.edn"
   :hardware        "config/hardware.edn"
   :content         "config/content.edn"})

(def zip-filenames
  "Filenames used inside the project zip file."
  {:project-metadata "project-metadata.edn"
   :hardware        "hardware.edn"
   :content         "content.edn"})



(def persistent-state-mapping
  "Defines the mapping between file keys and state paths.
   
   Format: {:file-key {:paths [{:path [:domain :field]-or-vector}]}}
   
   - :paths - Vector of path specs to save/load for this file
   - :path - Path into the unified state map
   - :keys - nil means save whole domain, vector means select-keys for partial save
   
   When loading: data from file is merged into the state at each :path
   When saving: if :keys is nil, save full domain; if :keys is a vector, save (select-keys)"
  {:project-metadata
   {:paths [{:path [:config]}
            {:path [:timing] :keys [:bpm]}
            {:path [:grid] :keys [:size]}]}
   
   :hardware
   {:paths [{:path [:projectors :items]}
            {:path [:projectors :virtual-projectors]}
            {:path [:zone-groups :items]}]}
   
   :content
   {:paths [{:path [:chains :cue-chains]}
            {:path [:chains :effect-chains]}
            {:path [:chains :projector-effects]}]}})


;; Load Functions


(defn- merge-data-at-paths!
  "Merge file data into state at multiple paths.
   
   Parameters:
   - file-data: Map of data loaded from file
   - path-specs: Vector of {:path [:domain :field] :keys nil-or-vector}"
  [file-data path-specs]
  (doseq [{:keys [path keys]} path-specs
          :when path
          :let [data-from-file (get-in file-data path)
                data-to-merge (if (and keys data-from-file)
                                (select-keys data-from-file keys)
                                data-from-file)]
          :when data-to-merge]
    (state/swap-state! #(update-in % path merge data-to-merge))))

(defn- load-and-merge-paths!
  "Load data from file and merge into state at multiple paths.
   
   Parameters:
   - filepath: Path to file to load
   - path-specs: Vector of {:path [:domain :field]-or-vector}
   
   Returns: true if loaded successfully, false otherwise"
  [filepath path-specs]
  (if-let [file-data (ser/load-from-file filepath)]
    (do (merge-data-at-paths! file-data path-specs)
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




;; Project Save/Load Helpers

(defn- collect-files-for-zip
  "Collect data for all files to be saved in zip."
  []
  (into {}
        (keep (fn [[file-key filename]]
                (when-let [{:keys [paths]} (get persistent-state-mapping file-key)]
                  [filename (collect-data-for-paths paths)])))
        zip-filenames))

(defn- update-project-state-after-save!
  "Update project state after successful save/load."
  [zip-path]
  (state/swap-state! #(-> %
                          (assoc-in [:project :current-file] zip-path)
                          (assoc-in [:project :dirty?] false)
                          (assoc-in [:project :last-saved] (System/currentTimeMillis)))))

(defn- load-zip-contents!
  "Load zip contents and merge into state.
   Returns true if successful, nil otherwise."
  [zip-path]
  (when-let [zip-contents (ser/load-from-zip zip-path)]
    (doseq [[file-key filename] zip-filenames
            :let [file-data (get zip-contents filename)
                  {:keys [paths]} (get persistent-state-mapping file-key)]
            :when (and file-data paths)]
      (merge-data-at-paths! file-data paths))
    true))


;; Project Operations

(defn save-project!
  "Save all state to a zip file.
   Creates parent directories if they don't exist.
   Updates project state with file path and marks as clean.
   
   Parameters:
   - zip-path - Path to the zip file (should end with .zip)
   
   Returns: true on success, false on failure
   
   Example:
   (save-project! \"/path/to/my-project.zip\")"
  [zip-path]
  (try
    (when (ser/save-to-zip! zip-path (collect-files-for-zip))
      (update-project-state-after-save! zip-path)
      true)
    (catch Exception e
      (log/error "Error saving project:" (.getMessage e))
      false)))

(defn load-project!
  "Load all state from a zip file.
   Merges loaded data into existing state.
   Updates project state with file path.
   
   Parameters:
   - zip-path - Path to the zip file to load
   
   Returns: true if successful, false if file doesn't exist or load fails
   
   Example:
   (load-project! \"/path/to/my-project.zip\")"
  [zip-path]
  (try
    (if-not (.exists (java.io.File. zip-path))
      (do (log/warn "Project file does not exist:" zip-path)
          false)
      (when (load-zip-contents! zip-path)
        (update-project-state-after-save! zip-path)
        true))
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

(defn ensure-projects-dir!
  "Ensure the default projects directory exists.
   Creates it if it doesn't exist.
   
   Returns: true if directory exists or was created successfully"
  []
  (try
    (let [dir (java.io.File. default-projects-dir)]
      (when-not (.exists dir)
        (.mkdirs dir))
      (.exists dir))
    (catch Exception e
      (log/error "Error creating projects directory:" (.getMessage e))
      false)))

(defn get-default-project-path
  "Generate a default project file path with timestamp.
   
   Returns: Full path to a new project file in the default projects directory
   
   Example:
   (get-default-project-path)
   => \"/home/user/LaserShowProjects/project-2026-01-15-182030.zip\""
  []
  (ensure-projects-dir!)
  (let [timestamp (.format (java.text.SimpleDateFormat. "yyyy-MM-dd-HHmmss")
                           (java.util.Date.))]
    (str default-projects-dir "/project-" timestamp ".zip")))
