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
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.migration :as migration]
            [laser-show.state.rig-file :as rig-file]
            [laser-show.state.serialization :as ser]
            [laser-show.state.templates :as templates]
            [laser-show.timeline.model :as model])
  (:import [java.nio.file CopyOption Files Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))


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
            {:path [:grid] :owner :show :keys [:size :column-targets]}
            ;; The map in use: live, but the show reopens on it (zones plan Z4 §5, D-14)
            {:path [:zone-map] :owner :show :keys [:active]}]}
   
   :hardware
   {:paths [{:path [:projectors] :owner :rig}
            {:path [:virtual-projectors] :owner :rig}
            {:path [:zone-groups] :owner :show}
            {:path [:rig] :owner :rig :merge-defaults? true}]}
   
   :content
   {:paths [{:path [:chains :cue-chains] :owner :show}
            {:path [:chains :effect-chains] :owner :show}
            {:path [:chains :projector-effects] :owner :rig}
            {:path [:zone-maps] :owner :show}
            {:path [:zones] :owner :show :merge-defaults? true}]}})

(def ^:private member-names
  "The names of a project zip's members (zip-filenames)."
  (set (vals zip-filenames)))

(defn- format-error
  "The error for a member that isn't what a show's is: problem at path in it."
  [member path problem]
  (ex-info (str member (when (seq path) (str " at " path)) " " problem)
           {:member member :path path}))

(defn- grid-cell? [k]
  (and (vector? k) (= 2 (count k)) (every? integer? k)))

(defn- check-grid!
  "Throw unless the cells at path in content.edn's data are {[col row] cell}
   with map cells, whose :items, when there are any, are a sequence of maps."
  [member data path]
  (let [cells (get-in data path)]
    (when-not (map? cells)
      (throw (format-error member path "is not a map")))
    (doseq [[k cell] cells
            :let [at (conj path k)]]
      (when-not (grid-cell? k)
        (throw (format-error member at "is not a [col row] cell")))
      (when-not (map? cell)
        (throw (format-error member at "is not a map")))
      (let [items (:items cell)]
        (when-not (or (nil? items) (and (sequential? items) (every? map? items)))
          (throw (format-error member (conj at :items) "is not a sequence of maps")))))))

(defn checked-project-files
  "files ({filename data}, as a project zip is read) when a show is in it,
   else throws ex-info naming the member and the path in it (:member :path):
   - content.edn must be there: a map whose :chains holds :cue-chains and
     :effect-chains, each a map ({} for an empty grid) of [col row] integer
     cells, each a map whose :items, when there are any, are a sequence of
     maps. Its later paths ([:chains :projector-effects], :zone-maps,
     :zones) may be missing.
   - project-metadata.edn and hardware.edn may be missing (their paths then
     take their defaults, as an old show's do), but one that is there is a
     map. A damaged one isn't a missing one: it fails.
   - Every path persistent-state-mapping reads from a member is a map, or
     isn't there.
   Other members are not looked at. Nothing about the content beyond that
   (how many cues, which ids, what effects) is checked here."
  [files]
  (let [content (:content zip-filenames)]
    (when-not (map? files)
      (throw (format-error "the project" [] "is not a map of members")))
    (when-not (contains? files content)
      (throw (format-error content [] "is missing")))
    (doseq [[file-key member] zip-filenames
            :when (contains? files member)
            :let [data (get files member)]]
      (when-not (map? data)
        (throw (format-error member [] "is not a map")))
      (doseq [{:keys [path]} (get-in persistent-state-mapping [file-key :paths])
              :let [v (get-in data path)]]
        (when-not (or (nil? v) (map? v))
          (throw (format-error member path "is not a map")))))
    (when-not (map? (get-in files [content :chains]))
      (throw (format-error content [:chains] "is not a map")))
    (check-grid! content (get files content) [:chains :cue-chains])
    (check-grid! content (get files content) [:chains :effect-chains])
    files))


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

(defn- clear-keyboard-holds [runtime]
  ;; The old show's actions must not receive key-up in the new show. Other
  ;; input devices keep their session, as before.
  (let [keyboard? #(= :keyboard (:source %))]
    (-> runtime
        (assoc :keyboard-activations {})
        (update :held-inputs #(into #{} (remove keyboard?) %))
        (update :active-triggers #(into {} (remove (comp keyboard? :input val)) %)))))

(defn- reset-session
  "Reset state that refers to the previous document (playback, what vibe
   mode is doing, selection, list UI, dialogs, the status bar's notice, the
   Vibes tab's selection, a pending zone map switch and the Map cycle's
   pause, the pattern pads' style, undo history, project metadata).
   Backend/runtime state (streaming, Link, MIDI/OSC, discovered devices) is kept."
  [state initial]
  (-> state
      (assoc :playback (:playback initial))
      (update :trigger-runtime clear-keyboard-holds)
      (assoc :vibe-runtime (:vibe-runtime initial))
      (assoc :list-ui (:list-ui initial))
      (assoc :history (:history initial))
      (assoc :project (:project initial))
      (assoc :zone-group-ui (:zone-group-ui initial))
      (assoc-in [:ui :dialogs] (get-in initial [:ui :dialogs]))
      (assoc-in [:ui :notice] (get-in initial [:ui :notice]))
      (assoc-in [:ui :vibes] (get-in initial [:ui :vibes]))
      (assoc-in [:grid :selected-cell] nil)
      ;; A switch waiting for a beat of the show that was open, the Map
      ;; cycle's pause and the pattern pads' style (zones plan Z5): a show
      ;; opens As set
      (assoc-in [:zone-map :pending] nil)
      (assoc-in [:zone-map :cycle-paused?] false)
      (assoc :pattern-pads (:pattern-pads initial))
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
   
   zip-contents is the {filename data} map from read-project. It is checked
   first (checked-project-files): contents that aren't a show throw, and
   replace nothing.
   An old show is converted (state.migration: Z2's rows, its groups judged by
   its own hardware, then Z4's, its routing as :target) before the saved
   baseline is taken, so it isn't \"unsaved\" right after loading."
  [state zip-contents zip-path]
  (let [initial (domains/build-initial-state)
        zip-contents (checked-project-files zip-contents)
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


(defn read-project
  "Read a project zip: the {filename data} of its members (zip-filenames),
   each read strictly (serialization/load-from-zip :strict?) and then
   checked (checked-project-files). nil when the file is missing, can't be
   read, or holds no show this build can open; the reason is logged, once."
  [zip-path]
  (try
    (if-not (.exists (java.io.File. ^String zip-path))
      (do (log/warn "Project file does not exist:" zip-path)
          nil)
      (checked-project-files (ser/load-from-zip zip-path :strict? true :filenames member-names)))
    (catch Exception e
      (log/error "Could not read project" zip-path "-" (ex-message e))
      nil)))

(defn- staging-file
  "A new, empty file beside target, named after it (<name>.<uuid>.tmp):
   in the same folder, so on the same file system."
  ^Path [^Path target]
  (Files/createFile (.resolveSibling target (str (.getFileName target) "." (random-uuid) ".tmp"))
                    (make-array FileAttribute 0)))

(defn- publish-project!
  "Move the checked staged file onto target, replacing the file there, in
   one step: the one moment a save changes what target holds. Where the file
   system can't replace a file in one step this throws
   (AtomicMoveNotSupportedException), and target is left as it was: the
   last good show is never deleted or cut short to make room."
  [^Path staged ^Path target]
  (Files/move staged target (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE])))

(defn write-project!
  "Write a project-files map (see project-files) to zip-path, without ever
   leaving zip-path half written or unreadable:
   1. its folder is made when it isn't there, and the zip is written to a
      new file beside it (staging-file), every value printed in full;
   2. that file must read back (read-project, as Open reads it) as files,
      each of the three members equal to its value here: a value that
      doesn't print as EDN, a member left out or a writer that wrote
      something else fails here;
   3. it is moved onto zip-path in one step (publish-project!).
   Returns true when zip-path holds the new show. false when any step
   failed (logged), with zip-path as it was, or still absent. The staged
   file is removed either way."
  [zip-path files]
  (let [staged (volatile! nil)
        refuse (fn [why]
                 (log/error "Not saving" zip-path "-" why)
                 false)]
    (try
      (if-not (and (map? files) (every? #(contains? files %) member-names))
        (refuse (str "a save needs all of " (str/join ", " (sort member-names))))
        (let [target (.toAbsolutePath (.toPath (io/file zip-path)))]
          (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
          (vreset! staged (staging-file target))
          (cond
            (not (binding [*print-length* nil *print-level* nil *print-meta* false]
                   (ser/save-to-zip! (str @staged) files)))
            (refuse "the zip couldn't be written")

            (not= files (read-project (str @staged)))
            (refuse "what was written doesn't read back as the show")

            :else
            (do (publish-project! @staged target)
                true))))
      (catch Exception e
        (log/error "Could not save project" zip-path "-" (ex-message e))
        false)
      (finally
        (when-let [^Path p @staged]
          (try
            (Files/deleteIfExists p)
            (catch Exception e
              (log/error "Could not remove the temporary file" (str p) "-" (ex-message e)))))))))

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
