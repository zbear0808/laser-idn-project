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
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.serialization :as ser]
            [laser-show.state.templates :as templates]
            [laser-show.timeline.model :as model]))


(def default-projects-dir
  "Default directory for saving projects."
  (str (System/getProperty "user.home") "/LaserShowProjects"))


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
   - :merge-defaults? - when loading, merge the file data over the domain defaults
                        (for settings maps, so keys added after the file was saved
                        get their default value)
   
   When loading: the value at each :path is REPLACED by the file data. Paths
                 missing from the file are reset to their initial value.
   When saving: if :keys is nil, save full domain; if :keys is a vector, save (select-keys)"
  {:project-metadata
   {:paths [{:path [:config] :merge-defaults? true}
            {:path [:timing] :keys [:bpm]}
            {:path [:grid] :keys [:size]}]}
   
   :hardware
   {:paths [{:path [:projectors]}
            {:path [:virtual-projectors]}
            {:path [:zone-groups]}]}
   
   :content
   {:paths [{:path [:chains :cue-chains]}
            {:path [:chains :effect-chains]}
            {:path [:chains :projector-effects]}]}})


;; Pure Document Functions
;;
;; These work on a state map passed in, so event handlers can stay pure and
;; return the new state from their :state effect.


(def ^:private all-path-specs
  (vec (mapcat :paths (vals persistent-state-mapping))))

(defn- collect-data-for-paths
  "Collect data from state for multiple paths into a single map.
   
   Parameters:
   - state: Application state map
   - path-specs: Vector of {:path [:domain :field] :keys nil-or-vector}
   
   Returns: Map with data organized by top-level domain keys"
  [state path-specs]
  (reduce
    (fn [acc {:keys [path keys]}]
      (if path
        (let [domain-data (get-in state path)
              data (if keys
                     (select-keys domain-data keys)
                     domain-data)]
          (assoc-in acc path data))
        acc))
    {}
    path-specs))

(defn project-files
  "Build the {filename data} map written into the project zip from state."
  [state]
  (into {}
        (keep (fn [[file-key filename]]
                (when-let [{:keys [paths]} (get persistent-state-mapping file-key)]
                  [filename (collect-data-for-paths state paths)])))
        zip-filenames))

(defn mark-saved
  "Record that the project was saved to zip-path at time now.
   The current document becomes the saved baseline (clean)."
  [state zip-path now]
  (-> state
      history/mark-clean
      (assoc-in [:project :current-file] zip-path)
      (assoc-in [:project :last-saved] now)))

(defn- replace-at-path
  "Replace the value at one path spec with file data, falling back to defaults."
  [state initial file-data {:keys [path keys merge-defaults?]}]
  (let [loaded (get-in file-data path)
        default (get-in initial path)]
    (if keys
      (reduce (fn [s k]
                (assoc-in s (conj path k)
                          (if (contains? loaded k) (get loaded k) (get default k))))
              state
              keys)
      (assoc-in state path
                (cond
                  (nil? loaded) default
                  merge-defaults? (merge default loaded)
                  :else loaded)))))

(defn- replace-document
  "Replace every persisted path in state with file-data (a map shaped like state).
   Paths missing from file-data are reset to their initial value."
  [state initial file-data]
  (reduce #(replace-at-path %1 initial file-data %2) state all-path-specs))

(defn- reset-session
  "Reset state that refers to the previous document (playback, selection,
   list UI, dialogs, undo history, project metadata).
   Backend/runtime state (streaming, Link, MIDI/OSC, discovered devices) is kept."
  [state initial]
  (-> state
      (assoc :playback (:playback initial))
      (assoc :list-ui (:list-ui initial))
      (assoc :history (:history initial))
      (assoc :project (:project initial))
      (assoc :zone-group-ui (:zone-group-ui initial))
      (assoc-in [:ui :dialogs] (get-in initial [:ui :dialogs]))
      (assoc-in [:grid :selected-cell] nil)
      (update :projector-ui merge (select-keys (:projector-ui initial)
                                               [:active-projector :active-virtual-projector]))
      (cond-> (contains? state :effects) (assoc-in [:effects :selected-cell] nil))))

(defn zip-contents->file-data
  "Merge the per-file maps of a loaded zip into one state-shaped map."
  [zip-contents]
  (reduce (fn [acc [_file-key filename]]
            (merge-with merge acc (get zip-contents filename)))
          {}
          zip-filenames))

(defn apply-loaded-project
  "Return state with the document replaced by the loaded zip contents.
   
   zip-contents is the {filename data} map from serialization/load-from-zip."
  [state zip-contents zip-path]
  (let [initial (domains/build-initial-state)]
    (-> state
        (replace-document initial (zip-contents->file-data zip-contents))
        (reset-session initial)
        ;; Before mark-clean, so an old project isn't "unsaved" right after loading
        (update-in [:chains :cue-chains] model/normalize-timeline-cues)
        history/mark-clean
        (assoc-in [:project :current-file] zip-path))))

(defn new-project-state
  "Return state with a fresh starter document. Runtime/backend state is kept."
  [state]
  (let [initial (domains/build-initial-state)]
    (-> state
        (replace-document initial {})
        (templates/apply-starter-cue-chains)
        (reset-session initial)
        history/mark-clean)))


;; I/O Functions


(defn write-project!
  "Write a project-files map (see project-files) to zip-path.
   Returns true on success, false on failure."
  [zip-path files]
  (try
    (boolean (ser/save-to-zip! zip-path files))
    (catch Exception e
      (log/error "Error saving project:" (.getMessage e))
      false)))

(defn read-project
  "Read a project zip. Returns the {filename data} map, or nil if the file is
   missing or unreadable."
  [zip-path]
  (try
    (if-not (.exists (java.io.File. ^String zip-path))
      (do (log/warn "Project file does not exist:" zip-path)
          nil)
      (ser/load-from-zip zip-path))
    (catch Exception e
      (log/error "Error loading project:" (.getMessage e))
      nil)))


;; Project Operations (REPL / tests - event handlers use the pure functions above)

(defn save-project!
  "Save the current state to a zip file and mark the project clean.
   
   Returns: true on success, false on failure"
  [zip-path]
  (if (write-project! zip-path (project-files (state/get-raw-state)))
    (do (state/swap-state! mark-saved zip-path (System/currentTimeMillis))
        true)
    false))

(defn load-project!
  "Load a zip file, replacing the current document.
   
   Returns: true if successful, false if file doesn't exist or load fails"
  [zip-path]
  (if-let [contents (read-project zip-path)]
    (do (state/swap-state! apply-loaded-project contents zip-path)
        true)
    false))

(defn new-project!
  "Replace the current document with a fresh starter project.
   
   Returns: true"
  []
  (state/swap-state! new-project-state)
  true)

(defn get-default-project-filename
  "Generate a default project filename with timestamp.
   
   Returns: Just the filename (not full path)
   
   Example:
   (get-default-project-filename)
   => \"project-2026-01-28-014509.zip\""
  []
  (let [timestamp (.format (java.text.SimpleDateFormat. "yyyy-MM-dd-HHmmss")
                           (java.util.Date.))]
    (str "project-" timestamp ".zip")))
