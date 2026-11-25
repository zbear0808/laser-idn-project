(ns laser-show.views.components.show-table
  "The Shows tab's table (plans/2026-11-25-timeline-shows 06 §4.1-§4.3), the
   app's first TableView: one row per show, in the order, filter and
   selection the app state holds.

   - Its rows are subs/visible-show-rows, already filtered and sorted. Its
     sort policy accepts without sorting, and a header click only reports
     the sort JavaFX shows (on-sort → :shows/set-sort).
   - Three extensions put the state back on the TableView, each a setter
     run in render (cljfx's own listeners are muted then), copied from the
     S0 probe (06 §1.3): ext-with-sort the arrow, by the state's value (the
     third click clears JavaFX's sort order, and :sort-order would never
     put it back); ext-with-rows the items, .set row by row when the order
     is the same; ext-with-selection the selection, by id, after every
     rows change (JavaFX drops a selected row whose item is replaced).
   - JavaFX owns the selection gestures; on-selected-rows reports them.
   - Only the cell being edited holds a text field (edit-field), its draft
     in app state ([:ui :shows :editing]): JavaFX rebuilds the cells on any
     items change, and a new field takes the focus back.
   - install-table-keys! owns the keys while the table has the focus and
     nobody types (Space, Enter, Delete, F2, ⌘E) and the slow second click
     that starts an edit."
  (:require [cljfx.api :as fx]
            [cljfx.ext.table-view :as fx.ext.table-view]
            [cljfx.lifecycle :as lifecycle]
            [cljfx.mutator :as mutator]
            [cljfx.prop :as prop]
            [clojure.string :as str]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.state.extractors :as ex]
            [laser-show.subs :as subs]
            [laser-show.timeline.timecode :as tc]
            [laser-show.views.keys :as keys])
  (:import [javafx.animation PauseTransition]
           [javafx.application Platform]
           [javafx.beans.value ChangeListener]
           [javafx.event EventHandler]
           [javafx.scene Node Parent]
           [javafx.scene.control SortEvent TableCell TableColumn TableColumn$SortType TableRow TableView
            TextField TextInputControl]
           [javafx.scene.input KeyCode KeyEvent MouseButton MouseEvent]
           [javafx.util Callback Duration]))


;; Columns and their sort keys


(def ^:private sort-keys
  "Each sortable column's key in [:ui :shows :sort] (a row key,
   timeline.shows/sort-columns)."
  {:tc :timecode? :title :title :artist :artist :album :album :bpm :bpm :key :key :genre :genre
   :duration :duration-s :tc-start :tc-start-s :tc-end :tc-end-s})

(def ^:private column-of-sort (into {} (map (fn [[c s]] [s c])) sort-keys))

(def ^:private editable-columns #{:title :artist :album :bpm :key :genre :tc-start})

(def ^:private column-prefix "shows-col-")

(defn column-id
  "The id of column k's TableColumn, which is also its header's:
   \"shows-col-artist\"."
  [k]
  (str column-prefix (name k)))

(defn- column-key [^TableColumn col]
  (let [id (some-> col .getId)]
    (when (and id (str/starts-with? id column-prefix))
      (keyword (subs id (count column-prefix))))))


;; The three extensions (06 §4.1, probe apply-sort!, apply-rows!, apply-selection!)


(defn- apply-sort!
  "Show the sort {:column :dir} (a sort key): that column alone in the sort
   order, with that direction."
  [^TableView table {:keys [column dir]}]
  (let [id (some-> (column-of-sort column) column-id)]
    (when-let [^TableColumn col (some #(when (= id (.getId ^TableColumn %)) %) (.getColumns table))]
      (.setSortType col (if (= :desc dir) TableColumn$SortType/DESCENDING TableColumn$SortType/ASCENDING))
      (when-not (= [col] (vec (.getSortOrder table)))
        (.setAll (.getSortOrder table) ^java.util.Collection [col])))))

(def ^:private ext-with-sort
  (fx/make-ext-with-props
   {:applied-sort (prop/make (mutator/setter apply-sort!) lifecycle/scalar)}))

(defn- apply-rows!
  "The table's items as rows: the same ids in the same order → .set only
   the rows that aren't identical; else setAll."
  [^TableView table rows]
  (let [items (.getItems table)]
    (if (and (= (count items) (count rows))
             (every? true? (map #(= (:id %1) (:id %2)) items rows)))
      (dotimes [i (count rows)]
        (let [r (nth rows i)]
          (when-not (identical? r (.get items i))
            (.set items i r))))
      (.setAll items ^java.util.Collection rows))))

(def ^:private ext-with-rows
  (fx/make-ext-with-props
   {:rows (prop/make (mutator/setter apply-rows!) lifecycle/scalar)}))

(defn- apply-selection!
  "Select the rows whose ids are in ids, unless JavaFX shows exactly those.
   The value carries the rows too, so it runs again after every rows
   change."
  [^TableView table {:keys [ids]}]
  (let [sm (.getSelectionModel table)
        idx (vec (keep-indexed (fn [i r] (when (contains? ids (:id r)) i)) (.getItems table)))]
    (when-not (= (set idx) (set (.getSelectedIndices sm)))
      (.clearSelection sm)
      (when (seq idx)
        (.selectIndices sm (int (first idx)) (int-array (rest idx)))))))

(def ^:private ext-with-selection
  (fx/make-ext-with-props
   {:selection (prop/make (mutator/setter apply-selection!) lifecycle/scalar)}))

(def ^:private accept-sort
  "The sort policy: accept, sort nothing. The rows come sorted."
  (reify Callback (call [_ _] true)))

(defn- on-sort
  "A header click: send the sort JavaFX now shows, as plain data. The third
   click on a column empties the sort order: :column nil."
  [^SortEvent e]
  (let [^TableView table (.getSource e)
        ^TableColumn col (first (.getSortOrder table))]
    (events/dispatch! {:event/type :shows/set-sort
                       :column (some-> col column-key sort-keys)
                       :dir (when col (if (= TableColumn$SortType/DESCENDING (.getSortType col)) :desc :asc))})))

(defn- on-selected-rows
  "JavaFX's selection after a gesture (click, ⌘-click, Shift-click, arrows)."
  [rows]
  (events/dispatch! {:event/type :shows/select :show-ids (into [] (keep :id) rows)}))


;; Cells


(defn- duration-text
  "\"5:20\", or \"1:02:05\" past an hour."
  [seconds]
  (let [s (long (Math/floor (double seconds)))
        h (quot s 3600) m (mod (quot s 60) 60) sec (mod s 60)]
    (if (pos? h)
      (format "%d:%02d:%02d" h m sec)
      (format "%d:%02d" (quot s 60) sec))))

(defn- cell-label [id column text style]
  {:fx/type :label
   :id (str "shows-cell-" (name column) "-" id)
   :text text
   :min-width 0
   :max-width Double/MAX_VALUE
   :style-class (if style ["label" style] ["label"])})

(defn- shown
  "[text style] column shows for row."
  [column row]
  (let [v (get row column)]
    (case column
      :title (if v [v nil] ["Untitled" "shows-cell-muted"])
      :bpm (if v [(format "%.1f" (double v)) nil] ["—" "shows-cell-muted"])
      :tc-start [(tc/format-tc (tc/seconds->tc (or (:tc-start-s row) 0.0) (or (:fps row) 30))) "shows-cell-mono"]
      :tc-end (if-let [e (:tc-end-s row)]
                [(tc/format-tc (tc/seconds->tc-ceil e (or (:fps row) 30))) "shows-cell-mono"]
                ["—" "shows-cell-muted"])
      [(or v "") nil])))

(defn- typed-text?
  "Is the field the scene's focus owner: is the user typing in it? (Not
   Node.isFocused, which also needs the window to have the OS focus.)"
  [^TextInputControl f]
  (identical? f (some-> f .getScene .getFocusOwner)))

(defn- apply-draft-text!
  "The edit field's text from the draft, unless the user is typing in it:
   its own text is newer than a render that lags the event queue (06 P4)."
  [^TextField f text]
  (when (and (not= text (.getText f)) (not (typed-text? f)))
    (.setText f text)
    (.end f)))

(def ^:private ext-with-draft-text
  (fx/make-ext-with-props
   {:draft-text (prop/make (mutator/setter apply-draft-text!) lifecycle/scalar)}))

(defn- enclosing-table ^TableView [^Node n]
  (loop [n n]
    (cond (nil? n) nil
          (instance? TableView n) n
          :else (recur (.getParent n)))))

(defn- focus-field!
  "Give the edit field the focus, the caret at the end (or all its text
   selected, for a new show's title), once it is still in a scene. Taking
   it from a field a cell rebuild removed makes the main window give the
   focus to its layout on the next runLater (views/root.clj
   take-focus-from-removed-owners!), so it asks once more after that."
  [^TextField f]
  (let [take! #(when (and (.getScene f) (not (.get (.getProperties f) ::done)))
                 (.requestFocus f)
                 (if (:select? (.getUserData f)) (.selectAll f) (.end f)))]
    (Platform/runLater
     (fn []
       (take!)
       (Platform/runLater
        #(when-not (or (typed-text? f)
                       (instance? TextInputControl (some-> f .getScene .getFocusOwner)))
           (take!)))))))

(defn- refocus-on-refusal!
  "A commit refused (the draft doesn't parse) leaves the field: take the
   focus back, which Enter gave the table."
  [^TextField f refused]
  (when (pos? (long (or refused 0)))
    (.remove (.getProperties f) ::done)
    (focus-field! f)))

(def ^:private ext-with-refusals
  (fx/make-ext-with-props
   {:refused (prop/make (mutator/setter refocus-on-refusal!) lifecycle/scalar)}))

(defn- finish-edit!
  "Leave the field with event: the table takes the focus first, so the
   keys go on working once the field goes, and the field's own focus loss
   commits nothing more."
  [^TextField f event]
  (.put (.getProperties f) ::done true)
  (some-> (enclosing-table f) .requestFocus)
  (events/dispatch! event))

(defn- edit-field-key! [^TextField f ^KeyEvent e]
  (let [{:keys [show-id column]} (.getUserData f)
        commit #(finish-edit! f {:event/type :shows/commit-edit :show-id show-id :column column :then %})]
    (condp = (.getCode e)
      KeyCode/ENTER (do (.consume e) (commit nil))
      KeyCode/TAB (do (.consume e) (commit (if (.isShiftDown e) :previous :next)))
      KeyCode/ESCAPE (do (.consume e) (finish-edit! f {:event/type :shows/cancel-edit}))
      nil)))

(defn- detaches
  "How many times the field has left its scene."
  ^long [^TextField f]
  (long (or (.get (.getProperties f) ::detaches) 0)))

(defn- edit-field-created!
  "The edit field's lifetime: the focus once its skin exists (a field
   focused before then has its text all selected when the skin comes, so
   the first key would replace the draft: probe d2), and again whenever a
   cell rebuild puts it back in its scene; its keys; and a commit when the
   focus goes elsewhere in its scene. A field a cell rebuild takes out
   commits nothing, as it loses the focus: it, or its replacement, takes
   the focus back. JavaFX gives the table the focus from a cell it
   rebuilds, so a focus going to the table commits nothing: a press in the
   table does (commit-on-press!)."
  [^TextField f]
  (if (.getSkin f)
    (focus-field! f)
    (.addListener (.skinProperty f)
                  (reify ChangeListener
                    (changed [this _ _ skin]
                      (when skin
                        (.removeListener (.skinProperty f) this)
                        (focus-field! f))))))
  (.addListener (.sceneProperty f)
                (reify ChangeListener
                  (changed [_ _ old scene]
                    (cond
                      (nil? scene) (.put (.getProperties f) ::detaches (inc (detaches f)))
                      (and (nil? old) (not (.get (.getProperties f) ::done))) (focus-field! f)))))
  (.addEventFilter f KeyEvent/KEY_PRESSED (reify EventHandler (handle [_ e] (edit-field-key! f e))))
  (.addListener (.focusedProperty f)
                (reify ChangeListener
                  (changed [_ _ _ focused?]
                    (when (and (not focused?) (.getScene f))
                      (let [n0 (detaches f)]
                        (Platform/runLater
                         #(let [scene (.getScene f)
                                owner (some-> scene .getFocusOwner)
                                {:keys [show-id column]} (.getUserData f)]
                            (when (and scene owner (not (identical? owner f))
                                       ;; the table takes the focus from a cell it
                                       ;; rebuilds; a press in it commits instead
                                       (not (identical? owner (enclosing-table f)))
                                       (= n0 (detaches f))
                                       (not (.get (.getProperties f) ::done)))
                              (.put (.getProperties f) ::done true)
                              (events/dispatch! {:event/type :shows/commit-edit
                                                 :show-id show-id :column column}))))))))))

(defn- edit-field
  "The one text field, in the cell being edited: its text is the draft
   (subs/show-edit-draft, read here only, so a keystroke runs this body and
   no other), red-bordered while it doesn't parse."
  [{:keys [fx/context show-id column]}]
  (let [{:keys [invalid? select? refused]} (fx/sub-ctx context subs/show-editing)]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created edit-field-created!
     :desc {:fx/type ext-with-refusals
            :props {:refused refused}
            :desc {:fx/type ext-with-draft-text
                   :props {:draft-text (fx/sub-ctx context subs/show-edit-draft)}
                   :desc {:fx/type :text-field
                          :id "shows-edit-field"
                          :style-class (cond-> ["text-field" "text-field-dark" "shows-edit-field"]
                                         invalid? (conj "text-field-invalid"))
                          :min-width 0
                          :max-width Double/MAX_VALUE
                          :user-data {:show-id show-id :column column :select? (boolean select?)}
                          :on-text-changed {:event/type :shows/edit-draft :show-id show-id :column column}}}}}))

(def ^:private no-row
  "A cell JavaFX shows without a row (between two items): nothing."
  {})

(defn- text-cell
  "A text column's cell: its label, or the edit field while it is the one
   being edited."
  [column row]
  (if (nil? (:id row))
    no-row
    {:text nil
     :graphic (if (= column (::editing row))
              {:fx/type edit-field :show-id (:id row) :column column}
                (let [[text style] (shown column row)]
                  (cell-label (:id row) column text style)))}))

(defn- approx-duration
  "An unlocked show's duration at the global BPM, \"≈2:08\": it subscribes
   to the BPM itself, so a tempo change runs these cells, not the rows."
  [{:keys [fx/context id beats]}]
  (let [bpm (fx/sub-ctx context subs/bpm)
        bpm (if (and (number? bpm) (pos? bpm)) bpm 120.0)]
    (cell-label id :duration (str "≈" (duration-text (tc/beats->seconds beats bpm))) "shows-cell-approx")))

(defn- duration-cell [row]
  (if (nil? (:id row))
    no-row
    {:text nil
   :graphic (if (:approx? row)
              {:fx/type approx-duration :id (:id row) :beats (:length-beats row)}
              (cell-label (:id row) :duration (duration-text (or (:duration-s row) 0.0)) nil))}))

(defn- status-cell
  "▶ while the show plays, TC while timecode drives it (subs/show-row-status,
   not the rows)."
  [{:keys [fx/context show-id]}]
  (case (get (fx/sub-ctx context subs/show-row-status) show-id)
    :manual {:fx/type :label :id (str "shows-status-" show-id) :text "▶" :style-class ["label" "shows-status"]}
    :timecode {:fx/type :label :id (str "shows-status-" show-id) :text "TC"
               :style-class ["label" "shows-tc-chip" "shows-tc-running"]}
    {:fx/type :region}))

(defn- status-cell-desc [row]
  (if (nil? (:id row)) no-row {:text nil :graphic {:fx/type status-cell :show-id (:id row)}}))

(defn- tc-cell-desc
  "Its timecode, shown: S4 makes it a control."
  [row]
  (if (nil? (:id row))
    no-row
    {:text nil
     :graphic {:fx/type :check-box
             :id (str "shows-tc-" (:id row))
             :selected (boolean (:timecode? row))
             :disable true
             :focus-traversable false}}))

(def ^:private describe-title (partial text-cell :title))
(def ^:private describe-artist (partial text-cell :artist))
(def ^:private describe-album (partial text-cell :album))
(def ^:private describe-bpm (partial text-cell :bpm))
(def ^:private describe-key (partial text-cell :key))
(def ^:private describe-genre (partial text-cell :genre))
(def ^:private describe-tc-start (partial text-cell :tc-start))
(def ^:private describe-tc-end (partial text-cell :tc-end))

(defn- column [k text width describe & {:as more}]
  (merge {:fx/type :table-column
          :id (column-id k)
          :text text
          :pref-width width
          :min-width 40
          :sortable (contains? sort-keys k)
          :cell-value-factory identity
          :cell-factory {:fx/cell-type :table-cell :describe describe}}
         more))

(def ^:private columns
  "The columns, in order: [ref description]."
  [[:status (column :status "▶" 28 status-cell-desc :min-width 28 :max-width 28 :resizable false)]
   [:tc (column :tc "TC" 48 tc-cell-desc :min-width 48 :max-width 48 :resizable false)]
   [:title (column :title "Title" 220 describe-title :min-width 120)]
   [:artist (column :artist "Artist" 160 describe-artist)]
   [:album (column :album "Album" 160 describe-album)]
   [:bpm (column :bpm "BPM" 64 describe-bpm)]
   [:key (column :key "Key" 56 describe-key)]
   [:genre (column :genre "Genre" 100 describe-genre)]
   [:duration (column :duration "Dur." 64 duration-cell)]
   [:tc-start (column :tc-start "TC start" 110 describe-tc-start)]
   [:tc-end (column :tc-end "TC end" 110 describe-tc-end)]])

(def column-descs
  "{ref description} of every column, for tests."
  (into {} columns))


;; Rows


(defn- picked-cell
  "The table cell under mouse event e, or nil."
  ^TableCell [^MouseEvent e]
  (loop [n (some-> e .getPickResult .getIntersectedNode)]
    (cond (nil? n) nil
          (instance? TableCell n) n
          (instance? TableRow n) nil
          :else (recur (.getParent ^Node n)))))

(defn- in-text-input? [^MouseEvent e]
  (loop [n (some-> e .getPickResult .getIntersectedNode)]
    (cond (nil? n) false
          (instance? TextInputControl n) true
          (instance? TableRow n) false
          :else (recur (.getParent ^Node n)))))

(defn- stop-slow-click! [^TableView table]
  (when table
    (when-let [^PauseTransition t (.remove (.getProperties table) ::slow-click)]
      (.stop t))))

(def ^:private slow-click-ms
  "How long after a click on the only selected row its cell starts
   editing, unless a double-click or a key comes first."
  500)

(defn- start-slow-click!
  [^TableView table id column]
  (let [t (PauseTransition. (Duration/millis slow-click-ms))]
    (.setOnFinished t (reify EventHandler
                        (handle [_ _]
                          (.remove (.getProperties table) ::slow-click)
                          (events/dispatch! {:event/type :shows/start-edit :show-id id :column column}))))
    (.put (.getProperties table) ::slow-click t)
    (.play t)))

(defn- row-clicked
  "A click on a row: a double-click plays it; a plain single click on a
   text cell of the row that was the only one selected before the press
   starts editing that cell, slowly (06 §4.2)."
  [^MouseEvent e]
  (let [^TableRow row (.getSource e)
        id (:id (.getItem row))
        table (.getTableView row)]
    (when (and id table (= MouseButton/PRIMARY (.getButton e)) (not (in-text-input? e)))
      (stop-slow-click! table)
      (case (long (.getClickCount e))
        2 (events/dispatch! {:event/type :shows/play :show-id id})
        1 (let [{:keys [row-id sole?]} (.get (.getProperties table) ::press)
                col (some-> (picked-cell e) .getTableColumn column-key)]
            (when (and sole? (= id row-id) (contains? editable-columns col)
                       (not (or (.isShiftDown e) (.isShortcutDown e) (.isAltDown e) (.isControlDown e))))
              (start-slow-click! table id col)))
        nil))))

(defn- row-desc
  "A row: its id, its menu (whose events say :from-row, as a row can't see
   the selection) and its clicks."
  [{:keys [id timecode?]}]
  (if (nil? id)
    {}
    {:id (str "shows-row-" id)
   :on-mouse-clicked row-clicked
   :context-menu {:fx/type :context-menu
                  :items [{:fx/type :menu-item :text "Play"
                           :on-action {:event/type :shows/play :from-row id}}
                          {:fx/type :menu-item :text "Open in Editor"
                           :on-action {:event/type :shows/open-editor :from-row id}}
                          {:fx/type :menu-item :text (if timecode? "Disable Timecode" "Enable Timecode")
                           ;; S4 turns timecode on and off
                           :disable true
                           :on-action {:event/type :shows/set-timecode :from-row id :enabled? (not timecode?)}}
                          {:fx/type :separator-menu-item}
                          {:fx/type :menu-item :text "Duplicate"
                           :on-action {:event/type :shows/duplicate :from-row id}}
                          {:fx/type :menu-item :text "Delete…"
                           :on-action {:event/type :shows/request-delete :from-row id}}]}}))


;; Keys and presses (06 §4.2)


(def ^:private claimed-keys
  "The bare keys the table acts on, or JavaFX does: a keyboard trigger on
   one of them doesn't fire while the table has the focus."
  #{:space :enter :delete :back_space :f2 :up :down})

(defn- table-key-event
  "The event key press e asks for while the table has the focus and nobody
   types, or nil. Space plays and stops as everywhere (the TableView would
   swallow it); Delete is the table's, not Edit > Clear Cell's."
  [^KeyEvent e]
  (let [code (.getCode e)
        shortcut? (.isShortcutDown e)
        plain? (not (or shortcut? (.isAltDown e) (.isControlDown e) (.isMetaDown e)))]
    (cond
      (and plain? (= KeyCode/SPACE code))
      {:event/type (if (ex/playing? (state/get-raw-state)) :transport/stop :transport/play)}

      (and plain? (= KeyCode/ENTER code)) {:event/type :shows/play :from-selection true}
      (and plain? (#{KeyCode/DELETE KeyCode/BACK_SPACE} code)) {:event/type :shows/request-delete :from-selection true}
      (and plain? (= KeyCode/F2 code)) {:event/type :shows/start-edit :from-selection true :column :title}
      (and shortcut? (= KeyCode/E code)) {:event/type :shows/open-editor :from-selection true}
      :else nil)))

(defn- pressed-row-id [^MouseEvent e]
  (loop [n (some-> e .getPickResult .getIntersectedNode)]
    (cond (nil? n) nil
          (instance? TableRow n) (:id (.getItem ^TableRow n))
          :else (recur (.getParent ^Node n)))))

(defn- commit-on-press!
  "A press in the table outside the edit field commits the edit (as the
   field's focus loss does when the focus goes out of the table)."
  [^TableView table ^MouseEvent e]
  (when-let [^TextField f (.lookup table "#shows-edit-field")]
    (when-not (or (in-text-input? e) (.get (.getProperties f) ::done))
      (let [{:keys [show-id column]} (.getUserData f)]
        (.put (.getProperties f) ::done true)
        (events/dispatch! {:event/type :shows/commit-edit :show-id show-id :column column})))))

(defn- install-table-keys!
  "The table's keys (claim-keys!, and a filter that acts while the focus is
   in the table and nobody types) and its press record for the slow click:
   which row was pressed, and whether it was the only one selected before
   the press (a filter runs before the behaviour selects)."
  [^TableView table]
  (keys/claim-keys! table claimed-keys)
  (.addEventFilter table KeyEvent/KEY_PRESSED
                   (reify EventHandler
                     (handle [_ e]
                       (stop-slow-click! table)
                       (when (keys/list-key-target? table e)
                         (when-let [event (table-key-event e)]
                           (.consume ^KeyEvent e)
                           (events/dispatch! event))))))
  (.addEventFilter table MouseEvent/MOUSE_PRESSED
                   (reify EventHandler
                     (handle [_ e]
                       (commit-on-press! table e)
                       (let [id (pressed-row-id e)
                             selected (.getSelectedItems (.getSelectionModel table))]
                         (.put (.getProperties table) ::press
                               {:row-id id
                                :sole? (and (some? id) (= 1 (count selected)) (= id (:id (first selected))))}))))))


;; The table


(defn- table-items
  "The table's items: the visible rows, the one being edited marked with
   the column (::editing). A subscription of its own, so a render for
   another reason (the selection) keeps the same row maps."
  [context]
  (let [rows (fx/sub-ctx context subs/visible-show-rows)
        {:keys [show-id column]} (fx/sub-ctx context subs/show-editing)]
    (if (some? show-id)
      (mapv #(if (= show-id (:id %)) (assoc % ::editing column) %) rows)
      rows)))

(defn- placeholder
  "What the table shows without rows: an empty library, or a search that
   matches nothing."
  [{:keys [fx/context]}]
  (let [{:keys [total]} (fx/sub-ctx context subs/show-table-status)
        {:keys [query]} (fx/sub-ctx context subs/show-table-ui)]
    (if (zero? total)
      {:fx/type :v-box
       :alignment :center
       :children [{:fx/type :label
                   :id "shows-empty"
                   :style-class ["label" "label-hint"]
                   :wrap-text true
                   :text "No shows yet. + New Show, or New Show from Cue in the cue editor."}]}
      {:fx/type :v-box
       :id "shows-no-match"
       :alignment :center
       :spacing 4
       :children [{:fx/type :label
                   :id "shows-no-match-text"
                   :style-class ["label" "label-hint"]
                   :text (str "No shows match '" (str/trim (str query)) "'")}
                  {:fx/type :hyperlink
                   :id "shows-clear-search"
                   :text "Clear search"
                   :on-action {:event/type :shows/set-query :text ""}}]})))

(defn show-table
  "The Shows table."
  [{:keys [fx/context]}]
  (let [items (fx/sub-ctx context table-items)
        selected (fx/sub-ctx context subs/show-selection)
        {sort-spec :sort} (fx/sub-ctx context subs/show-table-ui)]
    {:fx/type fx/ext-let-refs
     :refs column-descs
     :desc {:fx/type fx.ext.table-view/with-selection-props
            :props {:selection-mode :multiple
                    :on-selected-items-changed on-selected-rows}
            :desc {:fx/type ext-with-selection
                   :props {:selection {:ids selected :rows items}}
                   :desc {:fx/type ext-with-rows
                          :props {:rows items}
                          :desc {:fx/type ext-with-sort
                                 :props {:applied-sort sort-spec}
                                 :desc {:fx/type fx/ext-on-instance-lifecycle
                                        :on-created install-table-keys!
                                        :desc {:fx/type :table-view
                                               :id "shows-table"
                                               :style-class ["table-view" "shows-table"]
                                               :columns (mapv (fn [[k]] {:fx/type fx/ext-get-ref :ref k}) columns)
                                               :sort-policy accept-sort
                                               :on-sort on-sort
                                               :fixed-cell-size 28
                                               :column-resize-policy :constrained
                                               :row-factory {:fx/cell-type :table-row :describe row-desc}
                                               :placeholder {:fx/type placeholder}}}}}}}}))
