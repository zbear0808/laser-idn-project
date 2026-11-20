(ns laser-show.state.persistent
  "Persistence layer for application state.
   
   This namespace is responsible for:
   - Defining WHICH state is persistent (the mapping lives here), and which
     of it is the show's and which this machine's rig's (:owner): Open and
     New replace the show only, and the rig has a file of its own
     (state.rig-file, state.rig-store), the zip keeping a copy of it
   - Loading persistent state from disk on app startup
   - Saving persistent state to disk on user request
   
   The state.core namespace is agnostic about persistence - it just holds state.
   This namespace decides what gets saved and loaded."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.migration :as migration]
            [laser-show.state.rig-file :as rig-file]
            [laser-show.state.serialization :as ser]
            [laser-show.state.templates :as templates]
            [laser-show.timeline.model :as model]))


(def default-projects-dir
  "Default directory for saving projects."
  (str (System/getProperty "user.home") "/LaserShowProjects"))

(defn default-rig-file
  "Where this machine's rig file lives (Q-ZR-1): the JVM property
   laser-show.rig-file when it is set, else rig.edn beside the shows in
   default-projects-dir. The test runner sets the property to a temp folder,
   so no test reaches the user's rig."
  []
  (let [property (System/getProperty "laser-show.rig-file")]
    (if (str/blank? property)
      (str default-projects-dir "/rig.edn")
      property)))


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
   - :owner - :show (the show: its cues, effects, groups and settings) or
              :rig (the machine's rig: the projectors, their order and
              calibration, D-30; the same paths state.rig-file/rig-paths
              names)
   - :keys - nil means save whole domain, vector means select-keys for partial save
   - :merge-defaults? - when loading, merge the file data over the domain defaults
                        (for settings maps, so keys added after the file was saved
                        get their default value)
   
   When loading: the value at each :path is REPLACED by the file data. Paths
                 missing from the file are reset to their initial value.
   When saving: if :keys is nil, save full domain; if :keys is a vector, save (select-keys)"
  {:project-metadata
   {:paths [{:path [:config] :owner :show :merge-defaults? true}
            {:path [:timing] :owner :show :keys [:bpm]}
            {:path [:grid] :owner :show :keys [:size]}]}
   
   :hardware
   {:paths [{:path [:projectors] :owner :rig}
            {:path [:virtual-projectors] :owner :rig}
            {:path [:zone-groups] :owner :show}
            {:path [:rig] :owner :rig :merge-defaults? true}]}
   
   :content
   {:paths [{:path [:chains :cue-chains] :owner :show}
            {:path [:chains :effect-chains] :owner :show}
            {:path [:chains :projector-effects] :owner :rig}]}})


;; Pure Document Functions
;;
;; These work on a state map passed in, so event handlers can stay pure and
;; return the new state from their :state effect.


(def ^:private all-path-specs
  (vec (mapcat :paths (vals persistent-state-mapping))))

(defn owned-paths
  "The saved paths owner (:show or :rig) owns."
  [owner]
  (into [] (comp (filter #(= owner (:owner %))) (map :path)) all-path-specs))

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

(defn- vibe-switches-off
  "state with the effect cells vibe mode switched on ([:vibe-runtime :fx-on],
   and the held flash's) as off. Those switches are the session's: after a
   load or New the vibe runtime starts empty, and nothing would switch them
   off again."
  [state]
  (let [{:keys [fx-on voices]} (:vibe-runtime state)]
    (reduce (fn [s cell]
              (cond-> s
                (get-in s [:chains :effect-chains cell])
                (assoc-in [:chains :effect-chains cell :active?] false)))
            state
            (concat fx-on (get-in voices [:flash :fx-on])))))

(defn project-files
  "Build the {filename data} map written into the project zip from state.
   Effect cells vibe mode switched on are written off (vibe-switches-off)."
  [state]
  (let [state (vibe-switches-off state)]
    (into {}
          (keep (fn [[file-key filename]]
                  (when-let [{:keys [paths]} (get persistent-state-mapping file-key)]
                    [filename (collect-data-for-paths state paths)])))
          zip-filenames)))

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
  "Replace every persisted path of owners (a set of :show and :rig) in state
   with file-data (a map shaped like state). Paths missing from file-data
   are reset to their initial value."
  [state initial file-data owners]
  (reduce #(replace-at-path %1 initial file-data %2)
          state
          (filter #(contains? owners (:owner %)) all-path-specs)))

(defn- reset-session
  "Reset state that refers to the previous document (playback, what vibe
   mode is doing, selection, list UI, dialogs, the status bar's notice, the
   Vibes tab's selection, undo history, project metadata).
   Backend/runtime state (streaming, Link, MIDI/OSC, discovered devices) is kept."
  [state initial]
  (-> state
      (assoc :playback (:playback initial))
      (assoc :vibe-runtime (:vibe-runtime initial))
      (assoc :list-ui (:list-ui initial))
      (assoc :history (:history initial))
      (assoc :project (:project initial))
      (assoc :zone-group-ui (:zone-group-ui initial))
      (assoc-in [:ui :dialogs] (get-in initial [:ui :dialogs]))
      (assoc-in [:ui :notice] (get-in initial [:ui :notice]))
      (assoc-in [:ui :vibes] (get-in initial [:ui :vibes]))
      (assoc-in [:grid :selected-cell] nil)
      ;; The test pattern and Identify belong to the show that was open
      (update :projector-ui merge (select-keys (:projector-ui initial)
                                               [:active-projector :active-virtual-projector
                                                :calibrating-projector-id :identify]))
      (cond-> (contains? state :effects) (assoc-in [:effects :selected-cell] nil))))

(defn zip-contents->file-data
  "Merge the per-file maps of a loaded zip into one state-shaped map."
  [zip-contents]
  (reduce (fn [acc [_file-key filename]]
            (merge-with merge acc (get zip-contents filename)))
          {}
          zip-filenames))

(defn apply-loaded-project
  "Return state with the show replaced by the loaded zip contents. This
   machine's rig (projectors, virtual projectors, [:rig], calibration) stays
   as it is (D-30): the zip's copy of it is only read, to match the show onto
   this rig. The show's pins follow their units to this rig's ids
   (rig-file/rename-pins).
   
   zip-contents is the {filename data} map from serialization/load-from-zip.
   An old show is converted (state.migration: Z2's rows, its groups judged by
   its own hardware, then Z4's, its routing as :target) before the saved
   baseline is taken, so it isn't \"unsaved\" right after loading."
  [state zip-contents zip-path]
  (let [initial (domains/build-initial-state)
        hardware (get zip-contents (:hardware zip-filenames))]
    (-> state
        (replace-document initial (zip-contents->file-data zip-contents) #{:show})
        (reset-session initial)
        ;; Before mark-clean, so an old project isn't "unsaved" right after loading
        (update-in [:chains :cue-chains] model/normalize-timeline-cues)
        (migration/zones-z2-show hardware)
        (migration/zones-z4)
        (rig-file/rename-pins hardware)
        history/mark-clean
        (assoc-in [:project :current-file] zip-path))))

(defn new-project-state
  "Return state with a fresh starter show on the same rig: the rig stays as
   it is, and the groups its lasers are in come into the new show
   (rig-file/group-copy). Runtime/backend state is kept."
  [state]
  (let [initial (domains/build-initial-state)
        rig-groups (rig-file/group-copy state)]
    (-> state
        (replace-document initial {} #{:show})
        (update :zone-groups merge rig-groups)
        (templates/apply-starter-cue-chains)
        (templates/apply-starter-vibes)
        (templates/apply-starter-effects)
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
