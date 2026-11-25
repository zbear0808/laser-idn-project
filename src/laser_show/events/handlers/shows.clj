(ns laser-show.events.handlers.shows
  "Event handlers for the timeline show library (plans/2026-11-25-timeline-shows).

   Handles:
   - Library edits: a new show, a new show copied from a grid cue
   - Opening the timeline editor on a show
   - Playing by hand (:shows/play, :shows/stop: one show at a time, TS-9)
   - A trigger's toggle (:shows/toggle-run: a pad starts or stops its own
     show as a grid press does, TS-11)
   - The Shows table (S3): metadata, tempo and TC start edits, the one
     edit field, Duplicate, Delete, and the table's search, sort and
     selection; :from-row and :from-selection name shows as the table
     shows them (target-ids)

   State paths:
   - Shows: [:chains :shows show-id] (shows/show-path)
   - Their playback: [:playback :active-cues [:show show-id]] (shows/play-key)
   - Tab state: [:ui :shows] (:query, :scope, :sort, :selected, :editing,
     :manual)

   A handler that names no show, or a show that isn't there, returns
   {:state state}: nothing changes."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.events.helpers :as h]
            [laser-show.routing.rig :as rig]
            [laser-show.routing.targets :as targets]
            [laser-show.timeline.model :as model]
            [laser-show.timeline.playback :as playback]
            [laser-show.timeline.shows :as shows]
            [laser-show.timeline.timecode :as tc]))


;; Helpers


(defn- show-at [state id]
  (when (some? id)
    (get-in state (shows/show-path id))))

(defn- start-show
  "state with show k (a playback key) started from beat 0 as a new run,
   which the show clock starts at its next tick. Like any cue start it
   resumes a held transport (cue-timing/activate-cue)."
  [state k]
  (update state :playback cue-timing/activate-cue k (random-uuid) {:timeline? true :start-beat 0.0}))

(defn- stop-show [state k]
  (update state :playback cue-timing/deactivate-cue k))

(defn- stop-chase
  "state with show k, which timecode drives as the show clock's episode
   chase-id, stopped for that episode only ([:playback :chase-stopped]), as
   a grid press stops a chased cue (follow-up B7)."
  [state k chase-id]
  (cond-> (stop-show state k)
    (some? chase-id) (assoc-in [:playback :chase-stopped k] chase-id)))

(defn- chase-stopped?
  "Is show k one that follows timecode and that a toggle stopped for an
   episode (stop-chase)?"
  [state k]
  (and (contains? (get-in state [:playback :chase-stopped]) k)
       (playback/chases-mtc? (shows/cue-at state k))))

(defn- follow-again
  "state with show k following timecode again: the show clock takes it over
   at its next tick while timecode runs inside it."
  [state k]
  (update-in state [:playback :chase-stopped] dissoc k))


;; Library


(defn- handle-create
  "Add an empty show titled :title (\"Untitled\" without one) and select it."
  [{:keys [title state]}]
  (let [id (random-uuid)]
    {:state (-> state
                (assoc-in (shows/show-path id) (shows/new-show {:name title}))
                (assoc-in [:ui :shows :selected] #{id}))}))

(defn- open-editor
  "Effects that open the timeline editor on show id. A show that somehow
   skipped normalization is normalized here as a backfill: no undo step,
   not unsaved. A show with no clips opens with the browser, where its
   presets are. Opening it on another show than the one it showed lets go
   of the dialog's effect UI, which names that show's items."
  [state id]
  (let [path (shows/show-path id)
        show (get-in state path)
        new-track-id (random-uuid)
        normalize #(model/normalize-timeline-cue % {:new-track-id new-track-id})
        show' (normalize show)
        changed? (not (identical? show show'))
        another? (not= id (get-in state [:ui :dialogs :timeline-cue-editor :show-id]))]
    (cond-> {:state (cond-> state
                      changed? (assoc-in path show')
                      (empty? (:items show')) (assoc-in [:ui :timeline-prefs :add-panel :open?] true)
                      another? (update-in [:ui :dialogs :timeline-cue-editor] dissoc :effect-ui))
             :dispatch {:event/type :ui/open-dialog
                        :dialog-id :timeline-cue-editor
                        :data {:show-id id :editing-name? false}}}
      changed? (assoc :history/backfill
                      (fn [doc]
                        (if (get-in doc path)
                          (update-in doc path normalize)
                          doc))))))

(defn- handle-open-editor
  [{:keys [show-id state]}]
  (if (show-at state show-id)
    (open-editor state show-id)
    {:state state}))

(defn- cue->show
  "The show a copy of the grid cue at [col row] becomes: new ids throughout
   (chains/fresh-ids), converted without changing what it renders
   (model/convert-to-timeline), with timecode off and no :id or
   :trigger-mode. A cue with no target of its own takes its column's
   default, so the show draws where the cue did; a cue with no name is
   \"Untitled\"."
  [state col row cue]
  (let [names (into {} (map (juxt :id identity)) (rig/target-groups state))
        column-target (get-in state [:grid :column-targets col])
        show (-> (chains/fresh-ids cue)
                 (dissoc :id :trigger-mode :timeline/chase)
                 (model/convert-to-timeline names)
                 (assoc :editor-type :timeline)
                 model/normalize-timeline-cue)
        name (:name show)]
    (cond-> show
      (and (nil? (targets/normalize-target (:target show)))
           (some? (targets/normalize-target column-target)))
      (assoc :target column-target)

      (not (and (string? name) (not (str/blank? name))))
      (assoc :name "Untitled"))))

(defn- handle-new-from-cue
  "Copy the cue at [col row] into a new show, select it, show the Shows tab
   and open the editor on it. The cell stays as it is. One undo step."
  [{:keys [col row state]}]
  (if-not (and (int? col) (int? row))
    {:state state}
    (let [id (random-uuid)
          show (cue->show state col row (get-in state [:chains :cue-chains [col row]]))
          added (-> state
                    (assoc-in (shows/show-path id) show)
                    (assoc-in [:ui :shows :selected] #{id})
                    (assoc-in [:ui :active-tab] :shows))]
      (-> (open-editor added id)
          (dissoc :history/backfill)
          (assoc :history/description "New Show from Cue")))))


;; Playing


(defn- handle-toggle-run
  "A trigger's toggle of show :show-id, as a grid press on a timeline cell:
   - timecode drives it: stopped for this episode, until timecode enters
     it again (stop-chase);
   - so stopped: it follows timecode again (follow-again);
   - it plays: stopped;
   - it follows timecode but isn't driven: left alone, timecode starts it;
   - otherwise: started from beat 0, with the :cue pulse every cue start
     gives the tap reactions.
   [:ui :shows :manual] is left alone: a pad plays its own show."
  [{:keys [show-id state]}]
  (if-let [show (show-at state show-id)]
    (let [k (shows/play-key show-id)
          intent (get-in state [:playback :active-cues k])]
      {:state (cond
                (:chasing? intent) (stop-chase state k (:chase-id intent))
                (some? intent) (stop-show state k)
                (chase-stopped? state k) (follow-again state k)
                (playback/chases-mtc? show) state
                :else (-> (start-show state k)
                          (update-in [:playback :pulses :cue :id] (fnil inc 0))))})
    {:state state}))

(defn- handle-play
  "Play show :show-id by hand, from beat 0 (TS-9). A show that follows
   timecode is refused with a notice. The show played by hand before it
   ([:ui :shows :manual]) stops, unless timecode drives it; grid cues play
   on. :manual becomes this show."
  [{:keys [show-id state]}]
  (let [show (show-at state show-id)
        manual (get-in state [:ui :shows :manual])
        manual-k (when (some? manual) (shows/play-key manual))]
    (cond
      (nil? show)
      {:state state}

      (playback/chases-mtc? show)
      (h/show-notice state "Follows timecode. Untick TC to play it by hand." :info)

      :else
      {:state (cond-> state
                (and (some? manual)
                     (not= manual show-id)
                     (not (:chasing? (get-in state [:playback :active-cues manual-k]))))
                (stop-show manual-k)

                true (start-show (shows/play-key show-id))
                true (assoc-in [:ui :shows :manual] show-id))})))

(defn- handle-stop
  "Stop the show played by hand. [:ui :shows :manual] stays, so Play plays
   it again. A show timecode drives is left alone."
  [{:keys [state]}]
  (let [manual (get-in state [:ui :shows :manual])]
    (if (or (nil? manual) (playback/chases-mtc? (show-at state manual)))
      {:state state}
      {:state (stop-show state (shows/play-key manual))})))


;; The table (S3): which shows an event means, metadata edits, the one edit
;; field, Duplicate and Delete, and the table's own UI state
;; (plans/2026-11-25-timeline-shows 06 §4.6, §6)


(defn- selected-visible
  "The selected shows the table shows, in its order (shows/visible-ids): a
   selected show the search hides is left out."
  [state]
  (let [selected (get-in state [:ui :shows :selected] #{})]
    (filterv #(contains? selected %) (shows/visible-ids state))))

(defn- target-ids
  "The shows event means, in the table's order, each one in the library:
   :show-ids; :from-row id, the visible selection when id is in it, else
   just id (a row menu can't see the selection); :from-selection, the
   visible selection; else :show-id."
  [state {:keys [show-id show-ids from-row from-selection]}]
  (let [ids (cond
              (some? from-row) (let [sel (selected-visible state)]
                                 (if (some #{from-row} sel) sel [from-row]))
              from-selection (selected-visible state)
              (some? show-ids) (let [wanted (set show-ids)
                                     visible (shows/visible-ids state)]
                                 (into (filterv wanted visible) (remove (set visible)) (distinct show-ids)))
              (some? show-id) [show-id]
              :else [])]
    (filterv #(show-at state %) ids)))

(defn- resolve-show
  "event naming its first show (target-ids) as :show-id: Play and Open in
   Editor act on one show."
  [{:keys [state] :as event}]
  (if (or (contains? event :from-row) (contains? event :from-selection))
    (assoc event :show-id (first (target-ids state event)))
    event))

(def info-fields
  "The fields :shows/set-info sets: the title (:name) and :show/info's."
  #{:name :artist :album :key :genre})

(def edit-columns
  "The table's editable columns, in its order (Tab goes right, Shift-Tab
   left)."
  [:title :artist :album :bpm :key :genre :tc-start])

(defn- column-field [column] (if (= :title column) :name column))

(defn- show-name [show]
  (let [n (:name show)]
    (if (and (string? n) (not (str/blank? n))) n "Untitled")))

(defn- bpm-text [bpm]
  (if (and (number? bpm) (pos? bpm)) (format "%.1f" (double bpm)) ""))

(defn- tc-start-text [show]
  (tc/format-tc (tc/seconds->tc (or (:timeline/tc-start show) 0.0) (tc/cue-fps show))))

(defn- cell-text
  "What column of show reads in its edit field when the edit starts."
  [show column]
  (case column
    :title (or (:name show) "")
    :bpm (bpm-text (:timeline/tempo-bpm show))
    :tc-start (tc-start-text show)
    (or (get-in show [:show/info column]) "")))

(defn- with-info
  "show with field (info-fields) set to value trimmed; blank removes it."
  [show field value]
  (let [v (str/trim (str value))]
    (if (= :name field)
      (if (str/blank? v) (dissoc show :name) (assoc show :name v))
      (let [info (if (str/blank? v)
                   (dissoc (:show/info show) field)
                   (assoc (:show/info show) field v))]
        (if (empty? info) (dissoc show :show/info) (assoc show :show/info info))))))

(defn- parse-bpm [text]
  (let [t (str/trim (str text))]
    (cond
      (str/blank? t) {:bpm nil}
      (re-matches #"\d+(\.\d+)?|\.\d+" t) {:bpm (Double/parseDouble t)})))

(defn- with-bpm
  "{:show show'} with its locked tempo from text (blank unlocks it), or
   {:problem text} when it doesn't parse."
  [show text]
  (if-let [{:keys [bpm]} (parse-bpm text)]
    {:show (model/apply-op show {:op :set-tempo :bpm bpm})}
    {:problem (str "'" (show-name show) "': BPM must be a number, e.g. 128")}))

(defn- with-tc-start
  "{:show show'} starting at the timecode text (at its own frame rate), or
   {:problem text}."
  [show text]
  (let [fps (tc/cue-fps show)]
    (if-let [t (tc/parse-tc text fps)]
      {:show (model/apply-op show {:op :set-tc-start :seconds (tc/tc->seconds t fps)})}
      {:problem (str "'" (show-name show) "': TC start must be a timecode, e.g. 01:00:00:00")})))

(defn- edited
  "{:show show'} with column set from text, or {:problem text}."
  [show column text]
  (case column
    :bpm (with-bpm show text)
    :tc-start (with-tc-start show text)
    {:show (with-info show (column-field column) text)}))

(defn- edit-label [column]
  (case column
    :bpm (model/op-label {:op :set-tempo})
    :tc-start (model/op-label {:op :set-tc-start})
    "Edit Show Info"))

(defn- apply-edit
  "Effects for setting column of show id from text: the show changed, with
   the column's undo label; state as it was when nothing changes; a notice
   when text doesn't parse."
  [state id column text]
  (if-let [show (show-at state id)]
    (let [{show' :show problem :problem} (edited show column text)]
      (cond
        problem (h/show-notice state problem :warning)
        (= show show') {:state state}
        :else {:state (assoc-in state (shows/show-path id) show')
               :history/description (edit-label column)}))
    {:state state}))

(defn- handle-set-info
  [{:keys [show-id field value state]}]
  (if (contains? info-fields field)
    (apply-edit state show-id (if (= :name field) :title field) value)
    {:state state}))

(defn- handle-set-bpm
  [{:keys [show-id text state]}]
  (apply-edit state show-id :bpm text))

(defn- handle-set-tc-start
  [{:keys [show-id text state]}]
  (apply-edit state show-id :tc-start text))

(defn- editing [state] (get-in state [:ui :shows :editing]))

(defn- current-edit?
  "Is event about the cell being edited ([:show-id :column])?"
  [state {:keys [show-id column]}]
  (let [e (editing state)]
    (and (some? e) (= [show-id column] [(:show-id e) (:column e)]))))

(defn- start-editing
  "state editing column of show id, its draft the cell's text."
  [state id column]
  (assoc-in state [:ui :shows :editing]
            {:show-id id :column column :draft (cell-text (show-at state id) column) :invalid? false}))

(defn- commit-current
  "Effects that apply the edit in progress and end it; one that doesn't
   parse stays, marked invalid, with its notice. Nothing in progress: state."
  [state]
  (if-let [{:keys [show-id column draft]} (editing state)]
    (let [effects (apply-edit state show-id column draft)]
      (if (:dispatch-later effects)
        (update effects :state update-in [:ui :shows :editing] #(-> % (assoc :invalid? true) (update :refused (fnil inc 0))))
        (update effects :state assoc-in [:ui :shows :editing] nil)))
    {:state state}))

(defn- start-edit
  "Effects that start editing column of show id, after committing another
   cell's edit (which, refused, stays instead)."
  [state id column]
  (cond
    (not (and (show-at state id) (some #{column} edit-columns))) {:state state}
    (current-edit? state {:show-id id :column column}) {:state state}
    :else (let [effects (commit-current state)]
            (if (:dispatch-later effects)
              effects
              (update effects :state start-editing id column)))))

(defn- handle-start-edit
  [{:keys [column state] :as event}]
  (start-edit state (first (target-ids state event)) column))

(defn- handle-edit-draft
  [{:keys [text fx/event state] :as e}]
  (if (current-edit? state e)
    {:state (update-in state [:ui :shows :editing] #(-> % (assoc :draft (str (h/first-some text event "")) :invalid? false) (dissoc :select?)))}
    {:state state}))

(defn- next-column [column then]
  (let [i (.indexOf ^java.util.List edit-columns column)
        j (case then :next (inc i) :previous (dec i) nil)]
    (when (and j (< -1 j (count edit-columns)))
      (nth edit-columns j))))

(defn- handle-commit-edit
  "Apply the draft of the cell being edited (a stale commit, for another
   cell, does nothing), then with :then :next or :previous edit the next or
   previous column of the same show."
  [{:keys [show-id column then state] :as event}]
  (if-not (current-edit? state event)
    {:state state}
    (let [effects (commit-current state)
          next-col (next-column column then)]
      (if (or (:dispatch-later effects) (nil? next-col) (nil? (show-at (:state effects) show-id)))
        effects
        (update effects :state start-editing show-id next-col)))))

(defn- handle-cancel-edit
  [{:keys [state]}]
  {:state (assoc-in state [:ui :shows :editing] nil)})

(defn- handle-create-and-edit
  "+ New Show: a new show (handle-create), selected, its Title being edited.
   A search that would hide it is cleared, so its row shows."
  [event]
  (let [{:keys [state] :as effects} (handle-create event)
        id (first (get-in state [:ui :shows :selected]))
        shown (if (some #{id} (shows/visible-ids state)) state (assoc-in state [:ui :shows :query] ""))]
    (assoc effects :state (-> (start-editing shown id :title)
                              (assoc-in [:ui :shows :editing :select?] true)))))

(defn- n-shows [n] (if (= 1 n) "Show" (str n " Shows")))

(defn- duplicate-of
  "A copy of show: new ids throughout (chains/fresh-ids), timecode off, its
   title with \" (copy)\"."
  [show]
  (cond-> (dissoc (chains/fresh-ids show) :timeline/chase)
    (some? (:name show)) (update :name str " (copy)")))

(defn- handle-duplicate
  "Copy the shows the event means; the copies have timecode off and are
   selected. One undo step."
  [{:keys [state] :as event}]
  (let [ids (target-ids state event)]
    (if (empty? ids)
      {:state state}
      (let [copies (into {} (map (fn [id] [(random-uuid) (duplicate-of (show-at state id))])) ids)]
        {:state (-> (reduce-kv (fn [s id show] (assoc-in s (shows/show-path id) show)) state copies)
                    (assoc-in [:ui :shows :selected] (set (keys copies))))
         :history/description (str "Duplicate " (n-shows (count ids)))}))))

(defn- handle-request-delete
  "Ask before deleting the shows the event means (the confirm dialog), then
   :shows/delete them."
  [{:keys [state] :as event}]
  (let [ids (target-ids state event)
        n (count ids)]
    (if (zero? n)
      {:state state}
      {:state (assoc-in state [:ui :dialogs :confirm]
                        {:open? true
                         :title (if (= 1 n)
                                  (str "Delete \"" (show-name (show-at state (first ids))) "\"?")
                                  (str "Delete " n " shows?"))
                         :message (str (if (= 1 n) "Its timeline, its triggers and its editor go with it."
                                           "Their timelines, triggers and editors go with them.")
                                       " Undo brings " (if (= 1 n) "it" "them") " back.")
                         :confirm-label (str "Delete " (str/lower-case (n-shows n)))
                         :danger? true
                         :confirm-event {:event/type :shows/delete :show-ids ids}})})))

(defn- remove-show
  "state without show id and what it leaves behind: its playback (an intent,
   a chase stop), its editor's view state and window, the triggers that
   name it, and the tab's state about it. The show clock lets go of its run
   and the timeline middleware of its chase version."
  [state id]
  (let [k (shows/play-key id)
        targets-it? (fn [[_ v]] (= k (:target v)))]
    (cond-> (-> state
                (update-in [:chains :shows] dissoc id)
                (update-in [:playback :chase-stopped] dissoc k)
                (update-in [:ui :timeline-editor] dissoc id)
                (update :list-ui dissoc [:timeline-tracks id])
                (update-in [:ui :shows :selected] (fnil disj #{}) id))
      (contains? (get-in state [:playback :active-cues]) k)
      (update :playback cue-timing/deactivate-cue k)

      (some targets-it? (get-in state [:config :input :trigger-map]))
      (update-in [:config :input :trigger-map] #(into {} (remove targets-it?) %))

      (= id (get-in state [:ui :shows :editing :show-id]))
      (assoc-in [:ui :shows :editing] nil)

      (= id (get-in state [:ui :shows :manual]))
      (assoc-in [:ui :shows :manual] nil)

      (= id (get-in state [:ui :dialogs :timeline-cue-editor :show-id]))
      (assoc-in [:ui :dialogs :timeline-cue-editor :open?] false))))

(defn- handle-delete
  "Delete the shows :show-ids names (the confirm dialog's yes). One undo
   step."
  [{:keys [state] :as event}]
  (let [ids (target-ids state event)]
    (if (empty? ids)
      {:state state}
      {:state (reduce remove-show state ids)
       :history/description (str "Delete " (n-shows (count ids)))})))

(defn- handle-set-query
  [{:keys [text fx/event state]}]
  {:state (assoc-in state [:ui :shows :query] (str (h/first-some text event "")))})

(defn- handle-set-scope
  [{:keys [scope state]}]
  (if (some #{scope} shows/scopes)
    {:state (assoc-in state [:ui :shows :scope] scope)}
    {:state state}))

(defn- handle-set-sort
  "The table's sort, from its header: JavaFX's third click on a column
   clears its sort order (:column nil), which sorts by that column
   ascending again."
  [{:keys [column dir state]}]
  (cond
    (nil? column)
    {:state (assoc-in state [:ui :shows :sort]
                      {:column (get-in state [:ui :shows :sort :column] :title) :dir :asc})}

    (and (contains? shows/sort-columns column) (#{:asc :desc} dir))
    {:state (assoc-in state [:ui :shows :sort] {:column column :dir dir})}

    :else {:state state}))

(defn- handle-select
  "The table's selection, as JavaFX shows it after a gesture."
  [{:keys [show-ids state]}]
  {:state (assoc-in state [:ui :shows :selected] (set show-ids))})
;; Timecode (S4)


(defn- timecode-order
  "The show ids of ids in the library, in the order :shows/set-timecode
   takes them: the Shows table's (shows/visible-ids), then the ones it
   doesn't show, by (str id)."
  [state ids]
  (let [library (get-in state [:chains :shows])
        ids (into #{} (filter #(contains? library %)) ids)
        visible (filterv ids (shows/visible-ids state))]
    (into visible (sort-by str (remove (set visible) ids)))))

(defn- refusal-text
  "The notice for the shows :shows/set-timecode couldn't enable: refused
   [[id problem] …] (shows/timecode-problem's), library the shows checked
   against (01 §4.2)."
  [library refused]
  (let [name-of #(str "'" (shows/title (get library %)) "'")]
    (if (= 1 (count refused))
      (let [[id {:keys [reason with range]}] (first refused)]
        (str (name-of id)
             (case reason
               :no-bpm " needs a BPM to follow timecode"
               :empty " is empty"
               :overlap (str " overlaps " (name-of with) " (" (shows/tc-range-text range) ")"))))
      (str (count refused) " shows not enabled: "
           (str/join ", " (map (fn [[id {:keys [reason with]}]]
                                 (str (name-of id) " ("
                                      (case reason
                                        :no-bpm "needs a BPM"
                                        :empty "empty"
                                        :overlap (str "overlaps " (name-of with)))
                                      ")"))
                               refused))))))

(defn- enable-timecode
  "Turn timecode on for ids (timecode-order), each checked against the
   shows that follow already and the ones before it (TS-6). Those that pass
   go on in one edit, \"Enable Timecode\"; any refused are named in a
   notice. All refused: the notice only, so no undo step."
  [state ids]
  (let [[library on refused]
        (reduce (fn [[library on refused :as acc] id]
                  (let [show (get library id)]
                    (if (= :mtc (:timeline/chase show))
                      acc
                      (if-let [p (shows/timecode-problem library id)]
                        [library on (conj refused [id p])]
                        [(assoc library id (model/apply-op show {:op :set-chase :source :mtc}))
                         (conj on id)
                         refused]))))
                [(get-in state [:chains :shows]) [] []]
                ids)
        state' (assoc-in state [:chains :shows] library)]
    (cond
      (and (empty? on) (empty? refused)) {:state state}
      (empty? on) (h/show-notice state (refusal-text library refused) :warning)
      (empty? refused) {:state state' :history/description "Enable Timecode"}
      :else (assoc (h/show-notice state' (refusal-text library refused) :warning)
                   :history/description "Enable Timecode"))))

(defn- disable-timecode
  "Turn timecode off for ids: never refused. \"Disable Timecode\"."
  [state ids]
  (let [library (get-in state [:chains :shows])
        library' (reduce (fn [library id]
                           (if (= :mtc (get-in library [id :timeline/chase]))
                             (update library id model/apply-op {:op :set-chase :source nil})
                             library))
                         library
                         ids)]
    (if (identical? library library')
      {:state state}
      {:state (assoc-in state [:chains :shows] library') :history/description "Disable Timecode"})))

(defn- handle-set-timecode
  "The TC column's tick: timecode on (:enabled? true) or off for the shows
   :show-ids (or the one :show-id; :on? reads as :enabled?). On is checked
   against TS-6 (enable-timecode); a show already so, or missing, is left
   alone (07 §4.5)."
  [{:keys [show-ids show-id enabled? on? state] :as event}]
  (let [ids (timecode-order state (cond-> (set show-ids) (some? show-id) (conj show-id)))]
    (if (if (contains? event :enabled?) enabled? on?)
      (enable-timecode state ids)
      (disable-timecode state ids))))


;; Public API


(defn handle
  "Dispatch :shows/* events to their handlers."
  [{:keys [event/type] :as event}]
  (case type
    :shows/create (handle-create-and-edit event)
    :shows/new-from-cue (handle-new-from-cue event)
    :shows/open-editor (handle-open-editor (resolve-show event))
    :shows/toggle-run (handle-toggle-run event)
    :shows/play (handle-play (resolve-show event))
    :shows/stop (handle-stop event)
    :shows/set-info (handle-set-info event)
    :shows/set-bpm (handle-set-bpm event)
    :shows/set-tc-start (handle-set-tc-start event)
    :shows/start-edit (handle-start-edit event)
    :shows/edit-draft (handle-edit-draft event)
    :shows/commit-edit (handle-commit-edit event)
    :shows/cancel-edit (handle-cancel-edit event)
    :shows/duplicate (handle-duplicate event)
    :shows/request-delete (handle-request-delete event)
    :shows/delete (handle-delete event)
    :shows/set-query (handle-set-query event)
    :shows/set-scope (handle-set-scope event)
    :shows/set-sort (handle-set-sort event)
    :shows/select (handle-select event)
    :shows/set-timecode (handle-set-timecode event)
    (do
      (log/warn "Unknown shows event:" type)
      {})))
