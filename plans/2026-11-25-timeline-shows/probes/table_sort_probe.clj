;; S0 probe 1 (plans/2026-11-25-timeline-shows/02-phases.md S0 step 1).
;; A cljfx :table-view whose order, sort arrow and selection come from app
;; state, mounted with fx/create-app (async agent events, as the app has
;; them), driven headless with TestFX's glass robot. Uses the app's real
;; commit-field and applied-control; events/dispatch! is re-pointed at this
;; probe's own app. Not app code.
;;
;; Run (macOS arm64; the :probe alias adds TestFX and the :test-ui JVM opts):
;;   clojure -Sdeps '{:aliases {:probe {:extra-deps {org.testfx/testfx-core {:mvn/version "4.0.18"}}
;;                                      :jvm-opts ["-Djavafx.enablePreview=true" "-Djavafx.suppressPreviewWarning=true"
;;                                                 "--enable-native-access=ALL-UNNAMED"
;;                                                 "--add-exports=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED"
;;                                                 "--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED"
;;                                                 "--add-opens=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED"]}}}' \
;;     -M:mac-arm:test-ui-headless:probe plans/2026-11-25-timeline-shows/probes/table_sort_probe.clj
;;
;; Prints one line per check: [PASS|FAIL|INFO] id — what was seen.

(ns table-sort-probe
  (:require [cljfx.api :as fx]
            [cljfx.ext.table-view :as fx.ext.table-view]
            [cljfx.lifecycle :as lifecycle]
            [cljfx.mutator :as mutator]
            [cljfx.prop :as prop]
            [clojure.core.cache :as cache]
            [clojure.string :as str]
            [laser-show.events.core :as events]
            [laser-show.views.components.applied-value :as av]
            [laser-show.views.components.commit-field :as cf])
  (:import [javafx.application Platform]
           [javafx.collections ListChangeListener]
           [javafx.scene Node Scene]
           [javafx.scene.control CheckBox ContextMenu TableCell TableColumn TableColumn$SortType TableView TextField]
           [javafx.scene.control.skin TableColumnHeader]
           [javafx.scene.input KeyCode KeyCodeCombination KeyCombination$Modifier MouseButton MouseEvent]
           [javafx.stage Window]
           [javafx.util Callback]
           [org.testfx.api FxRobot FxToolkit]
           [org.testfx.util WaitForAsyncUtils]))

;; ------------------------------------------------------------ harness

(def results (atom []))
(def log (atom []))          ; every event the probe app handled, in order
(def counters (atom {}))     ; FX-side counters: sort-policy calls, permutations, accelerators

(defn bump! [k] (swap! counters update k (fnil inc 0)))

(defn report! [status id msg]
  (swap! results conj [status id msg])
  (println (format "[%s] %s — %s" (name status) id msg))
  (flush))

(defn check! [id ok? msg] (report! (if ok? :PASS :FAIL) id msg) ok?)
(defn info! [id msg] (report! :INFO id msg))

(defn on-fx [f]
  (if (Platform/isFxApplicationThread)
    (f)
    (let [p (promise)]
      (Platform/runLater #(deliver p (try [(f) nil] (catch Throwable t [nil t]))))
      (let [[v t] (deref p 10000 [nil (ex-info "FX timeout" {})])]
        (if t (throw t) v)))))

(defn sync-fx! [] (WaitForAsyncUtils/waitForFxEvents))

(defn wait-for
  ([pred] (wait-for 5000 pred))
  ([ms pred]
   (let [deadline (+ (System/currentTimeMillis) ms)]
     (loop []
       (sync-fx!)
       (or (pred)
           (if (< (System/currentTimeMillis) deadline)
             (do (Thread/sleep 30) (recur))
             false))))))

(defn settle! [] (Thread/sleep 150) (sync-fx!) (Thread/sleep 50) (sync-fx!))

;; ------------------------------------------------------------ state

(def init-rows
  {"r1" {:id "r1" :title "One More Time" :artist "Daft Punk" :bpm 122.7 :tc? false}
   "r2" {:id "r2" :title "Around the World" :artist "Daft Punk" :bpm 121.0 :tc? false}
   "r3" {:id "r3" :title "Strobe" :artist "deadmau5" :bpm 128.0 :tc? false}
   "r4" {:id "r4" :title "Genesis" :artist "Justice" :bpm nil :tc? false}
   "r5" {:id "r5" :title "Glue" :artist "Bicep" :bpm 124.0 :tc? false}
   "r6" {:id "r6" :title "Untitled" :artist nil :bpm nil :tc? false}})

(def init-state
  {:rows init-rows :sort {:column :title :dir :asc} :selected #{} :query "" :mech :m1
   ;; :plain - cljfx's :items and with-selection-props' :selected-indices
   ;; :in-place - ext-with-rows (.set per changed row) and ext-with-selection (by id)
   :rows-mode :plain
   ;; :fields - every Artist cell holds a commit-field
   ;; :draft - a label; the one cell at :editing holds draft-field, whose text is :draft
   :edit-mode :fields
   :editing nil
   :draft nil})

(defn- sort-key [column r]
  (let [v (get r column)] (if (string? v) (str/lower-case v) v)))

(defn visible-rows
  "Filtered by :query (title or artist substring), sorted by :sort, empty
   values last, ties by title then id (01-spec §3.3, cut down)."
  [ctx]
  (let [rows (vals (fx/sub-val ctx :rows))
        q (str/lower-case (str/trim (fx/sub-val ctx :query)))
        {:keys [column dir]} (fx/sub-val ctx :sort)
        rows (if (str/blank? q)
               rows
               (filter #(some (fn [k] (some-> (get % k) str/lower-case (str/includes? q))) [:title :artist]) rows))
        tie (fn [a b] (compare [(str/lower-case (or (:title a) "")) (:id a)] [(str/lower-case (or (:title b) "")) (:id b)]))]
    (vec (sort (fn [a b]
                 (let [va (sort-key column a) vb (sort-key column b)]
                   (cond (and (nil? va) (nil? vb)) (tie a b)
                         (nil? va) 1
                         (nil? vb) -1
                         :else (let [c (if (= dir :desc) (compare vb va) (compare va vb))]
                                 (if (zero? c) (tie a b) c)))))
               rows))))

(defn selected-indices [ctx]
  (let [rows (fx/sub-ctx ctx visible-rows)
        sel (fx/sub-val ctx :selected)]
    (vec (keep-indexed (fn [i r] (when (sel (:id r)) i)) rows))))

;; ------------------------------------------------------------ events (pure, async)

(def query-delay-ms
  "Slows :set-query on the event thread, so a render can come back while the
   user is still typing (the app's events are async: events/core.clj)."
  80)

(defn handle [{:keys [fx/context event/type] :as e}]
  (swap! log conj (dissoc e :fx/context))
  (let [swap #(hash-map :context (fx/swap-context context %))]
    (case type
      ::set-sort (swap #(let [{:keys [column dir]} e
                              prev (:sort %)]
                          ;; JavaFX's third click clears the sort order: map it back to ascending
                          (assoc % :sort (if column {:column column :dir dir} {:column (:column prev) :dir :asc}))))
      ::select (swap #(assoc % :selected (set (:ids e))))
      ::set-artist (swap #(assoc-in % [:rows (:id e) :artist] (not-empty (str/trim (:value e)))))
      ::set-title (swap #(assoc-in % [:rows (:id e) :title] (:value e)))
      ::set-bpm (swap #(assoc-in % [:rows (:id e) :bpm] (:value e)))
      ::set-tc (if (= "r2" (:id e)) {} (swap #(assoc-in % [:rows (:id e) :tc?] (:value e))))
      ::set-query (do (Thread/sleep query-delay-ms) (swap #(assoc % :query (:text e))))
      ::set-mech (swap #(assoc % :mech (:mech e)))
      ::reset (swap (constantly (merge init-state (select-keys e [:mech :rows-mode :edit-mode]))))
      ::start-edit (swap #(assoc % :editing [(:id e) :artist] :draft (or (get-in % [:rows (:id e) :artist]) "")))
      ::draft (swap #(if (= (:editing e) (:editing %)) (assoc % :draft (:text e)) %))
      ::commit-draft (swap #(if (= (:editing e) (:editing %))
                              (-> % (assoc-in [:rows (first (:editing e)) :artist] (not-empty (str/trim (:draft %))))
                                  (assoc :editing nil :draft nil))
                              %))
      ::play {}
      {})))

(defonce app-ref (atom nil))
(defn dispatch! [e] ((:handler @app-ref) e))

;; ------------------------------------------------------------ view

(def accept-policy
  (reify Callback (call [_ _] (bump! :sort-policy-calls) true)))

(defn on-sort
  "Read JavaFX's sort order on the FX thread and send plain data."
  [^javafx.scene.control.SortEvent e]
  (let [^TableView t (.getSource e)
        ^TableColumn col (first (.getSortOrder t))]
    (bump! :on-sort-calls)
    (dispatch! {:event/type ::set-sort
                :column (some-> col .getId keyword)
                :dir (when col (if (= TableColumn$SortType/DESCENDING (.getSortType col)) :desc :asc))})))

(defn on-selected-items [items]
  (dispatch! {:event/type ::select :ids (mapv :id items)}))

(defn apply-sort!
  "M2's :applied-sort setter: the column with that id first in the sort
   order, with that direction."
  [^TableView t {:keys [column dir]}]
  (when-let [^TableColumn col (some #(when (= (name column) (.getId ^TableColumn %)) %) (.getColumns t))]
    (.setSortType col (if (= dir :desc) TableColumn$SortType/DESCENDING TableColumn$SortType/ASCENDING))
    (when-not (= [col] (vec (.getSortOrder t)))
      (.setAll (.getSortOrder t) ^java.util.Collection [col]))))

(def ext-with-sort
  (fx/make-ext-with-props
   {:applied-sort (prop/make (mutator/setter apply-sort!) lifecycle/scalar)}))

(defn apply-rows!
  "ext-with-rows' setter: the same ids in the same order → .set only the rows
   that changed (one replace change each); else setAll."
  [^TableView t rows]
  (let [items (.getItems t)]
    (if (and (= (count items) (count rows)) (every? true? (map #(= (:id %1) (:id %2)) items rows)))
      (dotimes [i (count rows)]
        (let [r (nth rows i)] (when-not (identical? r (.get items i)) (.set items i r))))
      (.setAll items ^java.util.Collection rows))))

(def ext-with-rows
  (fx/make-ext-with-props
   {:rows (prop/make (mutator/setter apply-rows!) lifecycle/scalar)}))

(defn apply-selection!
  "ext-with-selection's setter: select the rows whose ids are in ids, unless
   JavaFX shows exactly those already. Its value carries the rows too, so it
   runs again after every rows change."
  [^TableView t {:keys [ids]}]
  (let [sm (.getSelectionModel t)
        idx (vec (keep-indexed (fn [i r] (when (contains? ids (:id r)) i)) (.getItems t)))]
    (when-not (= (set idx) (set (.getSelectedIndices sm)))
      (.clearSelection sm)
      (when (seq idx) (.selectIndices sm (int (first idx)) (int-array (rest idx)))))))

(def ext-with-selection
  (fx/make-ext-with-props
   {:selection (prop/make (mutator/setter apply-selection!) lifecycle/scalar)}))

(defn apply-query-text!
  "ext-with-query-text's setter: show the query from state unless the field
   has the focus (its own text is newer), except an empty query (Escape, the
   Clear link), which always applies."
  [^TextField f text]
  (when (and (not= text (.getText f)) (or (not (.isFocused f)) (= "" text)))
    (.setText f text)
    (.end f)))

(def ext-with-query-text
  (fx/make-ext-with-props
   {:query-text (prop/make (mutator/setter apply-query-text!) lifecycle/scalar)}))

(defn describe-title [row]
  {:text (or (:title row) "Untitled")})

(defn describe-bpm [row]
  {:text (if-let [b (:bpm row)] (format "%.1f" (double b)) "—")})

(defn draft-field
  "The :draft edit mode's cell graphic: a context component, so typing
   (:draft) re-renders it without changing the table's items. A field made
   while its cell is being edited takes the focus, caret at the end: JavaFX
   rebuilds a table's cells on any items change."
  [{:keys [fx/context id text]}]
  (let [editing (fx/sub-val context :editing)]
    (if (= editing [id :artist])
      {:fx/type fx/ext-on-instance-lifecycle
       ;; TextFieldBehavior's constructor (javap: TextFieldBehavior.<init>)
       ;; selects all the text when the field already owns the focus as its
       ;; skin is made; the skin comes on a later pulse. So the field takes
       ;; the focus once it has its skin, caret at the end. (A runLater after
       ;; requestFocus, and a focus listener's runLater, each lost that race
       ;; 1 run in 2: the first key replaced the draft.)
       :on-created (fn [^TextField f]
                     (let [focus! #(Platform/runLater (fn [] (.requestFocus f) (.end f)))]
                       (if (.getSkin f)
                         (focus!)
                         (.addListener (.skinProperty f)
                                       (reify javafx.beans.value.ChangeListener
                                         (changed [this _ _ skin]
                                           (when skin
                                             (.removeListener (.skinProperty f) this)
                                             (focus!))))))))
       :desc {:fx/type :text-field
              :id (str "artist-" id)
              :pref-width 140
              :text (fx/sub-val context :draft)
              :on-text-changed (fn [t] (dispatch! {:event/type ::draft :editing [id :artist] :text t}))
              :on-action (fn [_] (dispatch! {:event/type ::commit-draft :editing [id :artist]}))}}
      {:fx/type :label :id (str "artist-label-" id) :text (or text "")})))

(defn describe-artist-draft [row]
  {:text nil
   :graphic {:fx/type draft-field :id (:id row) :text (:artist row)}})

(defn describe-artist [row]
  {:text nil
   :graphic {:fx/type cf/commit-field
             :id (str "artist-" (:id row))
             ;; commit-field passes :pref-width through; nil fails cljfx's size coercion
             :pref-width 140
             :text (or (:artist row) "")
             :on-commit (fn [typed] {:event/type ::set-artist :id (:id row) :value typed})}})

(defn describe-tc [row]
  {:text nil
   :graphic {:fx/type av/applied-control
             :property :selected
             :address [:row (:id row) :tc?]
             :value (:tc? row)
             :on-change (fn [v] {:event/type ::set-tc :id (:id row) :value v})
             :desc {:fx/type :check-box :id (str "tc-" (:id row))}}})

(defn describe-row [row]
  {:context-menu {:fx/type :context-menu
                  :items [{:fx/type :menu-item
                           :text (str "Play " (:title row))
                           :on-action {:event/type ::play :id (:id row) :via :menu}}]}
   :on-mouse-clicked (fn [^MouseEvent e]
                       (when (and (= MouseButton/PRIMARY (.getButton e)) (= 2 (.getClickCount e)))
                         (dispatch! {:event/type ::play :id (:id row) :via :double-click})))})

(defn column [k text {:keys [column dir]} mech describe]
  (cond-> {:fx/type :table-column
           :id (name k)
           :text text
           :pref-width 150
           :cell-value-factory identity
           :cell-factory {:fx/cell-type :table-cell :describe describe}}
    (= :m1 mech) (assoc :sort-type (if (and (= k column) (= :desc dir)) :descending :ascending))
    (= :tc k) (assoc :sortable false :pref-width 50)))

(defn table [{:keys [fx/context]}]
  (let [rows (fx/sub-ctx context visible-rows)
        sort (fx/sub-val context :sort)
        mech (fx/sub-val context :mech)
        cols {:tc (column :tc "TC" sort mech describe-tc)
              :title (column :title "Title" sort mech describe-title)
              :artist (column :artist "Artist" sort mech (if (= :draft (fx/sub-val context :edit-mode))
                                                           describe-artist-draft
                                                           describe-artist))
              :bpm (column :bpm "BPM" sort mech describe-bpm)}
        tv (cond-> {:fx/type :table-view
                    :id "probe-table"
                    :items rows
                    :columns (mapv (fn [k] {:fx/type fx/ext-get-ref :ref k}) [:tc :title :artist :bpm])
                    :sort-policy accept-policy
                    :on-sort on-sort
                    :fixed-cell-size 28
                    :row-factory {:fx/cell-type :table-row :describe describe-row}
                    :placeholder {:fx/type :label :id "probe-placeholder" :text "No rows match"}}
             (= :m1 mech) (assoc :sort-order [{:fx/type fx/ext-get-ref :ref (:column sort)}]))]
    (if (= :in-place (fx/sub-val context :rows-mode))
      {:fx/type fx/ext-let-refs
       :refs cols
       :desc {:fx/type fx.ext.table-view/with-selection-props
              :props {:selection-mode :multiple
                      :on-selected-items-changed on-selected-items}
              :desc {:fx/type ext-with-selection
                     :props {:selection {:ids (fx/sub-val context :selected) :rows rows}}
                     :desc {:fx/type ext-with-rows
                            :props {:rows rows}
                            :desc {:fx/type ext-with-sort :props {:applied-sort sort}
                                   :desc (dissoc tv :items :sort-order)}}}}}
      {:fx/type fx/ext-let-refs
       :refs cols
       :desc {:fx/type fx.ext.table-view/with-selection-props
              :props {:selection-mode :multiple
                      :selected-indices (fx/sub-ctx context selected-indices)
                      :on-selected-items-changed on-selected-items}
              :desc (if (= :m2 mech)
                      {:fx/type ext-with-sort :props {:applied-sort sort} :desc tv}
                      tv)}})))

(defn root [{:keys [fx/context]}]
  {:fx/type :stage
   :showing true
   :x 0 :y 0 :width 760 :height 520
   :title "table-sort-probe"
   :scene {:fx/type :scene
           :root {:fx/type :v-box
                  :id "probe-root"
                  :spacing 4
                  :children [{:fx/type :h-box
                              :spacing 4
                              :children [{:fx/type :text-field
                                          :id "search-bound"
                                          :pref-width 240
                                          :text (fx/sub-val context :query)
                                          :on-text-changed (fn [t] (dispatch! {:event/type ::set-query :text t}))}
                                         {:fx/type ext-with-query-text
                                          :props {:query-text (fx/sub-val context :query)}
                                          :desc {:fx/type :text-field
                                                 :id "search-guarded"
                                                 :pref-width 240
                                                 :on-text-changed (fn [t] (dispatch! {:event/type ::set-query :text t}))}}
                                         {:fx/type :button :id "probe-button" :text "Other"}]}
                             {:fx/type table :v-box/vgrow :always}]}}})

;; ------------------------------------------------------------ queries

(defn table-node ^TableView []
  (on-fx #(some (fn [^Window w] (when (.isShowing w) (some-> w .getScene .getRoot (.lookup "#probe-table"))))
                (Window/getWindows))))

(defn header-of ^TableColumnHeader [col-id]
  (on-fx #(some (fn [n] (when (and (instance? TableColumnHeader n)
                                   (= col-id (some-> ^TableColumnHeader n .getTableColumn .getId)))
                          n))
                (.lookupAll (table-node) ".column-header"))))

(defn arrow-state
  "What the column's header shows: its sort arrow's visibility and rotation,
   and the table's sort order and that column's sort type."
  [col-id]
  (on-fx #(let [h (header-of col-id)
                ^Node arrow (.lookup h ".arrow")
                t (table-node)]
            {:header-id (.getId h)
             :arrow-visible? (boolean (and arrow (.isVisible arrow)
                                           (some-> arrow .getParent .isVisible)))
             :arrow-rotate (some-> arrow .getRotate)
             :sort-order (mapv (fn [^TableColumn c] (.getId c)) (.getSortOrder t))
             :sort-type (some (fn [^TableColumn c] (when (= col-id (.getId c)) (str (.getSortType c)))) (.getColumns t))})))

(defn items-ids [] (on-fx #(mapv :id (.getItems (table-node)))))

(defn cell-of
  "The non-empty TableCell showing row id in column col-id."
  ^TableCell [col-id row-id]
  (on-fx #(some (fn [n] (when (and (instance? TableCell n)
                                   (not (.isEmpty ^TableCell n))
                                   (= col-id (some-> ^TableCell n .getTableColumn .getId))
                                   (= row-id (:id (.getItem ^TableCell n))))
                          n))
                (.lookupAll (table-node) ".table-cell"))))

(defn lookup [sel] (on-fx #(.lookup (.getRoot (.getScene (table-node))) sel)))
(defn focus-owner [] (on-fx #(.getFocusOwner (.getScene (table-node)))))
(defn st [] (fx/sub-val @(:*ctx @app-ref) identity))
(defn state-val [k] (get (fx/sub-val @(:*ctx @app-ref) identity) k))
(defn events-of [t] (filterv #(= t (:event/type %)) @log))

(defn fire-click!
  "PRESSED, RELEASED, CLICKED at n's centre, not synthesized, not a popup
   trigger, still since the press, with shift? held. The headless glass
   robot's clicks are popup triggers (ui_test/testfx.clj press-node!), which
   a TableColumnHeader ignores."
  ([n] (fire-click! n false))
  ([^Node n shift?]
   (on-fx #(let [b (.localToScreen n (.getBoundsInLocal n))
                 x (/ (.getWidth (.getBoundsInLocal n)) 2) y (/ (.getHeight (.getBoundsInLocal n)) 2)]
             (doseq [t [MouseEvent/MOUSE_PRESSED MouseEvent/MOUSE_RELEASED MouseEvent/MOUSE_CLICKED]]
               (javafx.event.Event/fireEvent
                n (MouseEvent. t x y (+ (.getMinX b) x) (+ (.getMinY b) y) MouseButton/PRIMARY 1
                               (boolean shift?) false false false (= t MouseEvent/MOUSE_PRESSED) false false
                               false false true nil)))))
   (sync-fx!)))

(defn click! [^FxRobot r ^Node n] (.clickOn r n (into-array MouseButton [MouseButton/PRIMARY])) (sync-fx!))
(defn right-click! [^FxRobot r ^Node n] (.clickOn r n (into-array MouseButton [MouseButton/SECONDARY])) (sync-fx!))
(defn double-click! [^FxRobot r ^Node n] (.doubleClickOn r n (into-array MouseButton [MouseButton/PRIMARY])) (sync-fx!))
(defn push! [^FxRobot r ^KeyCode k] (.push r (into-array KeyCode [k])) (sync-fx!))

(defn reset-app!
  ([mech] (reset-app! mech :plain))
  ([mech rows-mode]
   (dispatch! {:event/type ::reset :mech mech :rows-mode rows-mode})
   (wait-for #(and (= mech (state-val :mech)) (= rows-mode (state-val :rows-mode))))
   (settle!)))

;; ------------------------------------------------------------ checks

(defn sort-checks [^FxRobot r mech click-header!]
  (let [tag (name mech)]
    (reset-app! mech)
    (swap! counters assoc :sort-policy-calls 0 :on-sort-calls 0 :permutations 0)
    (info! (str tag "/0") (str "initial " (arrow-state "title") " items " (items-ids)))
    ;; (a) one click on Artist
    (click-header! (header-of "artist"))
    (check! (str tag "/a-state") (wait-for #(= {:column :artist :dir :asc} (state-val :sort)))
            (str "state :sort after 1st Artist click = " (state-val :sort)))
    (settle!)
    (let [a (arrow-state "artist")]
      (check! (str tag "/a-arrow") (and (:arrow-visible? a) (= ["artist"] (:sort-order a)))
              (str "artist header " a))
      (check! (str tag "/a-no-javafx-reorder") (zero? (:permutations @counters 0))
              (str "sort-policy calls " (:sort-policy-calls @counters) ", on-sort calls " (:on-sort-calls @counters)
                   ", item-list permutations by JavaFX " (:permutations @counters 0))))
    ;; (b) items replaced from state keep the arrow
    (check! (str tag "/b-order") (= ["r5" "r2" "r1" "r3" "r4" "r6"] (items-ids))
            (str "items after state re-sort " (items-ids) " (Bicep, Daft Punk×2 by title, deadmau5, Justice, nil)"))
    (dispatch! {:event/type ::set-title :id "r5" :value "Glue (edit)"})
    (wait-for #(= "Glue (edit)" (get-in (st) [:rows "r5" :title])))
    (settle!)
    (let [a (arrow-state "artist")]
      (check! (str tag "/b-arrow-after-items-change") (and (:arrow-visible? a) (= ["artist"] (:sort-order a)))
              (str "after an unrelated :items replacement " a)))
    ;; 2nd click: descending
    (click-header! (header-of "artist"))
    (check! (str tag "/a2-state") (wait-for #(= {:column :artist :dir :desc} (state-val :sort)))
            (str "state after 2nd click = " (state-val :sort)))
    (settle!)
    (let [a (arrow-state "artist")]
      (check! (str tag "/a2-arrow") (and (:arrow-visible? a) (= "DESCENDING" (:sort-type a)))
              (str "artist header " a " items " (items-ids))))
    ;; (c) 3rd click: JavaFX clears the sort order; state maps it to ascending
    (click-header! (header-of "artist"))
    (check! (str tag "/c-state") (wait-for #(= {:column :artist :dir :asc} (state-val :sort)))
            (str "state after 3rd click = " (state-val :sort)))
    (settle!)
    (let [a (arrow-state "artist")]
      (check! (str tag "/c-arrow-restored") (and (:arrow-visible? a) (= ["artist"] (:sort-order a)) (= "ASCENDING" (:sort-type a)))
              (str "artist header after 3rd click " a)))
    ;; another column
    (click-header! (header-of "bpm"))
    (wait-for #(= :bpm (:column (state-val :sort))))
    (settle!)
    (check! (str tag "/other-column") (and (:arrow-visible? (arrow-state "bpm")) (not (:arrow-visible? (arrow-state "artist"))))
            (str "bpm " (arrow-state "bpm") " artist " (arrow-state "artist") " items " (items-ids)))
    ;; sort changed from state alone (not a click): does the arrow follow?
    (dispatch! {:event/type ::set-sort :column :title :dir :desc})
    (wait-for #(= {:column :title :dir :desc} (state-val :sort)))
    (settle!)
    (let [a (arrow-state "title")]
      (check! (str tag "/state-driven") (and (:arrow-visible? a) (= ["title"] (:sort-order a)) (= "DESCENDING" (:sort-type a)))
              (str "title after a state-only sort change " a)))
    (check! (str tag "/never-reordered-by-javafx") (zero? (:permutations @counters 0))
            (str "JavaFX permutations of the item list over the whole run: " (:permutations @counters 0)))))

(defn edit-checks [^FxRobot r rows-mode]
  (reset-app! :m2 rows-mode)
  (dispatch! {:event/type ::set-sort :column :artist :dir :asc})
  (wait-for #(= :artist (:column (state-val :sort))))
  (settle!)
  (swap! log empty)
  (let [^TextField f (lookup "#artist-r3")]
    (click! r f)
    (check! "d/focus" (wait-for #(identical? f (focus-owner))) (str "focus owner after clicking #artist-r3: " (class (focus-owner))))
    (info! "d/click-selects-row?" (str "selection after clicking into the field: " (state-val :selected)
                                       " (JavaFX selected ids " (on-fx #(mapv :id (.getSelectedItems (.getSelectionModel (table-node))))) ")"))
    (on-fx #(.end f))
    (.write r "Z")
    (sync-fx!)
    ;; an unrelated row edit while typing: :items replaced, order unchanged
    (dispatch! {:event/type ::set-title :id "r5" :value "Glue (while typing)"})
    (wait-for #(= "Glue (while typing)" (get-in (st) [:rows "r5" :title])))
    (settle!)
    (info! "d/field-after-items-change" (str "old field still in a scene " (some? (on-fx #(.getScene f)))
                                             ", #artist-r3 is the same instance " (identical? f (lookup "#artist-r3"))))
    (check! "d/items-change-keeps-field" (and (identical? f (focus-owner)) (= "deadmau5Z" (on-fx #(.getText f)))
                                              (= 9 (on-fx #(.getCaretPosition f))))
            (str "after an items replacement: same field focused " (identical? f (focus-owner))
                 ", text " (pr-str (on-fx #(.getText f))) ", caret " (on-fx #(.getCaretPosition f))))
    ;; an edit elsewhere that moves rows above the one being edited
    (dispatch! {:event/type ::set-artist :id "r4" :value "Aaa"})
    (wait-for #(= "Aaa" (get-in (st) [:rows "r4" :artist])))
    (settle!)
    (let [owner (focus-owner)]
      (check! "d/reorder-while-typing" (and (identical? f owner) (= "deadmau5Z" (on-fx #(.getText f))))
              (str "after rows above moved: same field focused " (identical? f owner)
                   ", owner id " (some-> ^Node owner .getId) ", field text " (pr-str (on-fx #(.getText f)))
                   ", field's cell row now " (on-fx #(some-> f .getParent (as-> p (when (instance? TableCell p) (:id (.getItem ^TableCell p)))))))))
    (.write r "z")
    (sync-fx!)
    (push! r KeyCode/ENTER)
    (wait-for #(= "deadmau5Zz" (get-in (st) [:rows "r3" :artist])))
    (settle!)
    (let [commits (events-of ::set-artist)]
      (info! "d/after-enter" (str "items " (items-ids) ", focus owner " (some-> ^Node (focus-owner) .getId)
                                  ", its text " (pr-str (some-> ^TextField (focus-owner) .getText))))
      ;; blur the field: click the other button
      (click! r (lookup "#probe-button"))
      (settle!)
      (Thread/sleep 400) (sync-fx!)
      (let [commits (events-of ::set-artist)]
        (check! "d/commits-once" (= 1 (count (filter #(= "r3" (:id %)) commits)))
                (str "set-artist events for r3: " (filterv #(= "r3" (:id %)) commits)
                     "; all set-artist " (mapv (juxt :id :value) commits)))
        (check! "d/no-stray-commits" (= #{"r4" "r3"} (set (map :id commits)))
                (str "rows that got a commit: " (set (map :id commits))))))))

(defn draft-edit-checks [^FxRobot r]
  (dispatch! {:event/type ::reset :mech :m2 :rows-mode :in-place :edit-mode :draft})
  (wait-for #(= :draft (state-val :edit-mode)))
  (dispatch! {:event/type ::set-sort :column :artist :dir :asc})
  (wait-for #(= :artist (:column (state-val :sort))))
  (settle!)
  (swap! log empty)
  (dispatch! {:event/type ::start-edit :id "r3"})
  (check! "d2/starts" (wait-for #(= "artist-r3" (some-> ^Node (focus-owner) .getId)))
          (str "focus owner after :start-edit r3 " (some-> ^Node (focus-owner) .getId)))
  (.write r "Z")
  (wait-for #(= "deadmau5Z" (state-val :draft)))
  (let [f1 (focus-owner)]
    (dispatch! {:event/type ::set-title :id "r5" :value "Glue (while typing)"})
    (wait-for #(= "Glue (while typing)" (get-in (st) [:rows "r5" :title])))
    (settle!)
    (let [^TextField f2 (focus-owner)]
      (check! "d2/items-change-refocuses" (and (= "artist-r3" (some-> f2 .getId)) (= "deadmau5Z" (.getText f2))
                                               (= 9 (on-fx #(.getCaretPosition f2))))
              (str "after an items change: new instance " (not (identical? f1 f2)) ", focus " (some-> f2 .getId)
                   ", text " (pr-str (some-> f2 .getText)) ", caret " (on-fx #(some-> f2 .getCaretPosition))
                   ", drafts sent " (mapv :text (events-of ::draft)) ", state draft " (pr-str (state-val :draft))))))
  (dispatch! {:event/type ::set-artist :id "r4" :value "Aaa"})
  (wait-for #(= "Aaa" (get-in (st) [:rows "r4" :artist])))
  (settle!)
  (check! "d2/reorder-refocuses" (= "artist-r3" (some-> ^Node (focus-owner) .getId))
          (str "after rows above moved, focus " (some-> ^Node (focus-owner) .getId)))
  (.write r "z")
  (wait-for #(= "deadmau5Zz" (state-val :draft)))
  (push! r KeyCode/ENTER)
  (wait-for #(nil? (state-val :editing)))
  (settle!)
  (check! "d2/commits-once" (and (= "deadmau5Zz" (get-in (st) [:rows "r3" :artist])) (= 1 (count (events-of ::commit-draft)))
                                 (empty? (remove #(= "r4" (:id %)) (events-of ::set-artist))))
          (str "r3 artist " (pr-str (get-in (st) [:rows "r3" :artist])) ", commit events " (count (events-of ::commit-draft))
               ", stray set-artist " (count (remove #(= "r4" (:id %)) (events-of ::set-artist)))
               ", drafts sent " (mapv :text (events-of ::draft)) ", focus after " (some-> ^Node (focus-owner) .getId)
               ", items " (items-ids))))

(defn selection-checks [^FxRobot r rows-mode]
  (reset-app! :m2 rows-mode)
  (click! r (cell-of "title" "r2"))
  (check! "e/click-selects" (wait-for #(= #{"r2"} (state-val :selected))) (str "state :selected " (state-val :selected)))
  (dispatch! {:event/type ::set-sort :column :bpm :dir :desc})
  (wait-for #(= :bpm (:column (state-val :sort))))
  (settle!)
  (let [ids (on-fx #(mapv :id (.getSelectedItems (.getSelectionModel (table-node)))))]
    (check! "e/survives-resort" (= ["r2"] ids) (str "JavaFX selected after re-sort " ids ", items " (items-ids)
                                                    ", state " (state-val :selected))))
  ;; data change on another row, order unchanged: the :selected-indices desc doesn't change
  (dispatch! {:event/type ::set-title :id "r6" :value "Untitled 2"})
  (wait-for #(= "Untitled 2" (get-in (st) [:rows "r6" :title])))
  (settle!)
  (let [ids (on-fx #(mapv :id (.getSelectedItems (.getSelectionModel (table-node)))))]
    (check! "e/survives-unrelated-items-change" (= ["r2"] ids)
            (str "JavaFX selected after an unrelated row edit (same order) " ids ", state " (state-val :selected))))
  ;; the selected row's own data changes
  (dispatch! {:event/type ::set-bpm :id "r2" :value 121.5})
  (wait-for #(= 121.5 (get-in (st) [:rows "r2" :bpm])))
  (settle!)
  (let [ids (on-fx #(mapv :id (.getSelectedItems (.getSelectionModel (table-node)))))]
    (check! "e/survives-own-row-change" (= ["r2"] ids)
            (str "JavaFX selected after the selected row changed " ids ", state " (state-val :selected))))
  ;; filtered out and back
  (dispatch! {:event/type ::set-query :text "strobe"})
  (wait-for #(= "strobe" (state-val :query)))
  (settle!)
  (info! "e/filtered-out" (str "items " (items-ids) " state :selected " (state-val :selected)
                               " JavaFX selected " (on-fx #(mapv :id (.getSelectedItems (.getSelectionModel (table-node)))))))
  (dispatch! {:event/type ::set-query :text ""})
  (wait-for #(= "" (state-val :query)))
  (settle!)
  (check! "e/filtered-back" (= ["r2"] (on-fx #(mapv :id (.getSelectedItems (.getSelectionModel (table-node))))))
          (str "after clearing the filter: JavaFX selected " (on-fx #(mapv :id (.getSelectedItems (.getSelectionModel (table-node)))))
               " state " (state-val :selected)))
  ;; keyboard: Down moves the selection natively, and the listener reports it
  (on-fx #(.requestFocus (table-node)))
  (sync-fx!)
  (push! r KeyCode/DOWN)
  (wait-for #(not= #{"r2"} (state-val :selected)))
  (info! "e/down-key" (str "after Down: state :selected " (state-val :selected) " items " (items-ids)))
  ;; shift-click range (headless robot drops held modifiers: fire a synthetic shift click)
  (fire-click! (cell-of "title" "r5") true)
  (info! "e/shift-click-javafx" (str "JavaFX selected right after shift-click on r5: "
                                     (on-fx #(mapv :id (.getSelectedItems (.getSelectionModel (table-node)))))))
  (settle!)
  (info! "e/shift-click" (str "after shift-click on r5: state :selected " (state-val :selected) " items " (items-ids))))

(defn key-checks [^FxRobot r]
  (reset-app! :m2)
  (on-fx #(let [^Scene s (.getScene (table-node))]
            (doseq [[k kw] [[KeyCode/SPACE :acc-space] [KeyCode/ENTER :acc-enter] [KeyCode/DELETE :acc-delete]
                            [KeyCode/E :acc-e] [KeyCode/UP :acc-up]]]
              (.put (.getAccelerators s) (KeyCodeCombination. k (make-array KeyCombination$Modifier 0))
                    (fn [] (bump! kw))))))
  (click! r (cell-of "title" "r1"))
  (on-fx #(.requestFocus (table-node)))
  (sync-fx!)
  (doseq [k [KeyCode/SPACE KeyCode/ENTER KeyCode/DELETE KeyCode/E]] (push! r k))
  (settle!)
  (info! "f/accelerators-with-table-focus"
         (str "scene accelerators that fired while the table had focus: "
              (select-keys @counters [:acc-space :acc-enter :acc-delete :acc-e])
              " (missing = the TableView consumed the key)"))
  ;; and with a text field in a cell focused
  (swap! counters dissoc :acc-space :acc-enter :acc-delete :acc-e)
  (click! r (lookup "#artist-r1"))
  (on-fx #(.end ^TextField (lookup "#artist-r1")))
  (push! r KeyCode/SPACE)
  (push! r KeyCode/ESCAPE)
  (settle!)
  (info! "f/accelerators-in-cell-field" (str "fired while typing in a cell field: " (select-keys @counters [:acc-space])
                                             ", field text " (pr-str (on-fx #(.getText ^TextField (lookup "#artist-r1")))))))

(defn menu-and-click-checks [^FxRobot r]
  (reset-app! :m2)
  (swap! log empty)
  (right-click! r (cell-of "title" "r3"))
  (settle!)
  (let [menus (on-fx #(vec (for [w (Window/getWindows) :when (and (instance? ContextMenu w) (.isShowing ^Window w))]
                             (mapv (fn [i] (.getText ^javafx.scene.control.MenuItem i)) (.getItems ^ContextMenu w)))))]
    (check! "i/row-menu" (= [["Play Strobe"]] menus) (str "context menus showing after right-click on r3: " menus)))
  (on-fx #(doseq [w (Window/getWindows) :when (instance? ContextMenu w)] (.hide ^ContextMenu w)))
  (sync-fx!)
  (double-click! r (cell-of "title" "r2"))
  (settle!)
  (check! "j/double-click-label-cell" (some #(= ["r2" :double-click] ((juxt :id :via) %)) (events-of ::play))
          (str "play events after double-click on r2's title: " (events-of ::play)))
  (swap! log empty)
  (double-click! r (lookup "#artist-r2"))
  (settle!)
  (info! "j/double-click-field-cell" (str "play events after double-click on r2's artist field: " (events-of ::play)
                                          "; focus owner " (some-> ^Node (focus-owner) .getId)))
  (on-fx #(.requestFocus (table-node))))

(defn checkbox-checks [^FxRobot r]
  (reset-app! :m2)
  (click! r (lookup "#tc-r1"))
  (check! "h/tick-applies" (wait-for #(get-in (st) [:rows "r1" :tc?])) (str "r1 :tc? " (get-in (st) [:rows "r1" :tc?])))
  (click! r (lookup "#tc-r2"))
  (sync-fx!)
  (let [right-after (on-fx #(.isSelected ^CheckBox (lookup "#tc-r2")))]
    (Thread/sleep 600) (sync-fx!)
    (check! "h/refused-tick-snaps-back" (and right-after (not (on-fx #(.isSelected ^CheckBox (lookup "#tc-r2"))))
                                             (not (get-in (st) [:rows "r2" :tc?])))
            (str "r2 ticked right after the click " right-after ", 600 ms later " (on-fx #(.isSelected ^CheckBox (lookup "#tc-r2"))))))
  ;; re-sort: cells are reused for other rows; does each checkbox show its new row's value?
  (dispatch! {:event/type ::set-sort :column :title :dir :desc})
  (wait-for #(= :desc (:dir (state-val :sort))))
  (settle!)
  (let [shown (on-fx #(into {} (for [id (keys init-rows) :let [^CheckBox c (lookup (str "#tc-" id))] :when c] [id (.isSelected c)])))]
    (check! "h/cell-reuse" (= shown (into {} (for [[id row] (:rows (st))] [id (boolean (:tc? row))])))
            (str "checkboxes after re-sort " shown))))

(defn search-checks [^FxRobot r selector run]
  (reset-app! :m2)
  (swap! log empty)
  (let [^TextField f (lookup selector)
        typed "daft punk one more"]
    (click! r f)
    (.write r typed)
    (sync-fx!)
    (let [right-after {:text (on-fx #(.getText f)) :caret (on-fx #(.getCaretPosition f))}]
      (wait-for 8000 #(= typed (state-val :query)))
      (settle!)
      (let [after {:text (on-fx #(.getText f)) :caret (on-fx #(.getCaretPosition f)) :query (state-val :query)}
            sent (mapv :text (events-of ::set-query))]
        (check! (str "g/" (subs selector 1) "-" run) (and (= typed (:text after)) (= typed (:query after)) (= (count typed) (:caret after)))
                (str "typed " (pr-str typed) " with " query-delay-ms " ms per event: field right after typing " right-after
                     ", settled " after ", events " (count sent) ", out-of-order/stale texts sent: "
                     (vec (remove #(str/starts-with? typed %) sent))))
        (info! "g/filtered-placeholder" (str "items while query " (pr-str (state-val :query)) ": " (items-ids)
                                 ", placeholder visible " (on-fx #(some-> ^Node (lookup "#probe-placeholder") .isVisible))))))))

(defn slow-search-checks
  "Type one key every 40 ms while each :set-query takes 80 ms on the event
   thread: renders of older queries land between keystrokes."
  [^FxRobot r]
  (reset-app! :m2)
  (swap! log empty)
  (let [^TextField f (lookup "#search-bound")
        typed "daft punk one more"]
    (click! r f)
    (let [shrinks (atom [])
          l (reify javafx.beans.value.ChangeListener
              (changed [_ _ old new] (when (< (count new) (count old)) (swap! shrinks conj [old new]))))
          _ (on-fx #(.addListener (.textProperty f) l))
          t0 (System/nanoTime)]
      (doseq [c typed] (.write r (str c)) (Thread/sleep 40))
      (info! "g2/timing" (format "typing %d keys took %.0f ms; queries applied by then: %s; the field's text shrank (a stale render overwrote it) %d times %s"
                                 (count typed) (/ (- (System/nanoTime) t0) 1e6) (pr-str (state-val :query)) (count @shrinks) @shrinks))
      (on-fx #(.removeListener (.textProperty f) l)))
    (wait-for 10000 #(= typed (state-val :query)))
    (settle!)
    (let [sent (mapv :text (events-of ::set-query))]
      (check! "g2/interleaved-renders" (and (= typed (on-fx #(.getText f))) (= (count typed) (on-fx #(.getCaretPosition f))))
              (str "field " (pr-str (on-fx #(.getText f))) " caret " (on-fx #(.getCaretPosition f)) " state " (pr-str (state-val :query))
                   ", " (count sent) " events, texts that aren't a prefix of what was typed: "
                   (vec (remove #(str/starts-with? typed %) sent)))))))

(defn -main []
  (Platform/setImplicitExit false)
  (FxToolkit/registerPrimaryStage)
  (alter-var-root #'events/dispatch! (constantly (fn [e & _] (dispatch! e))))
  (let [*ctx (atom (fx/create-context init-state #(cache/lru-cache-factory % :threshold 4096)))
        app (fx/create-app *ctx :event-handler handle :desc-fn (fn [_] {:fx/type root}))]
    (reset! app-ref (assoc app :*ctx *ctx))
    (when-not (wait-for 10000 #(some? (table-node)))
      (println "table never showed") (System/exit 2))
    (on-fx #(.addListener (.getItems (table-node))
                          (reify ListChangeListener
                            (onChanged [_ c] (while (.next c) (when (.wasPermutated c) (bump! :permutations))))))))
  (info! "env" (str "glass.platform=" (System/getProperty "glass.platform") " javafx=" (System/getProperty "javafx.runtime.version")
                    " java=" (System/getProperty "java.version")))
  (let [r (FxRobot.)]
    (doseq [[n f] [["sort m1, robot click (a popup trigger headless)" #(sort-checks r :m1 (partial click! r))]
                   ["sort m1 (:sort-order via ext-get-ref + :sort-type)" #(sort-checks r :m1 fire-click!)]
                   ["sort m2 (:applied-sort ext prop)" #(sort-checks r :m2 fire-click!)]
                   ["edit, :items" #(edit-checks r :plain)]
                   ["edit, ext-with-rows" #(edit-checks r :in-place)]
                   ["edit, :draft mode (editing state in app state)" #(draft-edit-checks r)]
                   ["selection, :items + :selected-indices" #(selection-checks r :plain)]
                   ["selection, ext-with-rows + ext-with-selection" #(selection-checks r :in-place)]
                   ["keys" #(key-checks r)]
                   ["menu/double-click" #(menu-and-click-checks r)]
                   ["checkbox" #(checkbox-checks r)]
                   ["search, :text bound (run 1)" #(search-checks r "#search-bound" 1)]
                   ["search, :text bound (run 2)" #(search-checks r "#search-bound" 2)]
                   ["search, :text bound (run 3)" #(search-checks r "#search-bound" 3)]
                   ["search, guarded (run 1)" #(search-checks r "#search-guarded" 1)]
                   ["search, guarded (run 2)" #(search-checks r "#search-guarded" 2)]
                   ["search, guarded (run 3)" #(search-checks r "#search-guarded" 3)]
                   ["search, slow typing" #(slow-search-checks r)]]]
      (println "==" n)
      (try (f) (catch Throwable t (report! :FAIL n (str "threw " t)) (.printStackTrace t)))))
  (println "== summary" (frequencies (map first @results)))
  (flush)
  (System/exit 0))

(-main)
