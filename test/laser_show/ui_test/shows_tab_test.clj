(ns laser-show.ui-test.shows-tab-test
  "The Shows tab (plans/2026-11-25-timeline-shows 01 §7, 06 §8): the table of
   shows, sorted by its headers, filtered by its search, edited in its
   cells, played and opened from its rows, and its keys. The preview stays
   beside it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.shows :as shows-handlers]
            [laser-show.state.core :as state]
            [laser-show.timeline.shows :as shows]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene Node]
           [javafx.scene.control ContextMenu MenuItem TableColumn TableView TextField]
           [javafx.scene.input KeyCode]
           [javafx.stage Window]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(def ^:private zebra #uuid "00000000-0000-0000-0000-0000000000a1")
(def ^:private alpha #uuid "00000000-0000-0000-0000-0000000000a2")
(def ^:private untitled #uuid "00000000-0000-0000-0000-0000000000a3")

(def ^:private one-more #uuid "00000000-0000-0000-0000-0000000000b1")
(def ^:private around #uuid "00000000-0000-0000-0000-0000000000b2")
(def ^:private strobe #uuid "00000000-0000-0000-0000-0000000000b3")
(def ^:private genesis #uuid "00000000-0000-0000-0000-0000000000b4")

(defn- show
  ([title] (show title nil nil))
  ([title artist bpm]
   (cond-> {:editor-type :timeline :tracks [] :items []}
     title (assoc :name title)
     artist (assoc :show/info {:artist artist})
     bpm (assoc :timeline/tempo-bpm bpm))))

(def ^:private four-shows
  {one-more (show "One More Time" "Daft Punk" 122.7)
   around (show "Around the World" "Daft Punk" 121.0)
   strobe (show "Strobe" "deadmau5" 128.0)
   genesis (show "Genesis" "Justice" nil)})

(defn- st [] (state/get-raw-state))

(defn- table ^TableView [] (ui/query "#shows-table"))

(defn- item-ids [] (ui/on-fx-thread #(mapv :id (.getItems (table)))))

(defn- focus-owner-id []
  (ui/on-fx-thread #(some-> ^Node (.getFocusOwner (.getScene (table))) .getId)))

(defn- open-shows! [robot]
  (ui/click! robot "#shows-tab")
  (ui/wait-for! #(= :shows (get-in (st) [:ui :active-tab])))
  (ui/assert-visible! "#shows-tab-root"))

(defn- with-four-shows! [robot]
  (open-shows! robot)
  (events/write-state! assoc-in [:chains :shows] four-shows)
  (ui/wait-for! #(= 4 (count (item-ids)))))

(defn- cell [column id] (str "#shows-cell-" (name column) "-" id))

(defn- recording
  "pipeline, recording each :shows/* event the app handles into sent, then
   handling it as before."
  [pipeline sent]
  (fn [event]
    (when (= "shows" (some-> (:event/type event) namespace))
      (swap! sent conj (select-keys event [:event/type :show-id :from-row])))
    (pipeline event)))

(defn- menu-item!
  "Fire the showing context menu's item with text."
  [text]
  (ui/wait-for! #(ui/on-fx-thread (fn [] (some (fn [w] (and (instance? ContextMenu w) (.isShowing ^Window w)))
                                               (Window/getWindows)))))
  (ui/on-fx-thread
   (fn []
     (let [menu (some #(when (and (instance? ContextMenu %) (.isShowing ^Window %)) %) (Window/getWindows))
           item (some #(when (= text (.getText ^MenuItem %)) %) (.getItems ^ContextMenu menu))]
       (.hide ^ContextMenu menu)
       (.fire ^MenuItem item))))
  (ui/sync-fx!))

(defn- undo-labels [] (mapv :description (get-in (st) [:history :undo-stack])))

(defn- top-undo [] (:description (peek (get-in (st) [:history :undo-stack]))))

(deftest bare-list-opens-and-plays-test
  (let [robot (ui/robot)
        sent (atom [])
        pipeline events/ordered-pipeline]
    (open-shows! robot)
    (is (ui/visible? "#preview-panel") "the preview stays, as on Grid")
    (testing "an empty library"
      (ui/assert-text! "#shows-empty" "No shows yet. + New Show, or New Show from Cue in the cue editor.")
      (ui/assert-text! "#shows-count" "0 shows"))
    (events/write-state! assoc-in [:chains :shows] {zebra (show "Zebra") alpha (show "alpha") untitled (show nil)})
    (testing "a row per show, by title, untitled last"
      (ui/wait-for! #(= 3 (count (item-ids))))
      (is (= [alpha zebra untitled] (item-ids)))
      (is (= ["alpha" "Zebra" "Untitled"] (mapv #(ui/node-text (cell :title %)) [alpha zebra untitled])))
      (is (ui/has-style-class? (cell :title untitled) "shows-cell-muted") "Untitled, muted")
      (is (not (ui/has-style-class? (cell :title alpha) "shows-cell-muted")))
      (ui/assert-text! "#shows-count" "3 shows")
      (is (empty? (ui/query-all "#shows-empty"))))
    (testing "a double-click plays, + New Show and the row menu's Open in Editor send their events"
      (with-redefs [events/ordered-pipeline (recording pipeline sent)]
        (.doubleClickOn robot (ui/query (cell :title zebra)) (into-array javafx.scene.input.MouseButton [javafx.scene.input.MouseButton/PRIMARY]))
        (ui/wait-for! #(some #{{:event/type :shows/play :show-id zebra}} @sent))
        (ui/click! robot "#shows-new")
        (ui/wait-for! #(some #{{:event/type :shows/create}} @sent))
        (ui/wait-for! #(= 4 (count (get-in (st) [:chains :shows]))))
        (is (= :title (get-in (st) [:ui :shows :editing :column])) "+ New Show edits its title")
        (ui/wait-for! #(= "shows-edit-field" (focus-owner-id)))
        (ui/press! robot KeyCode/ESCAPE)
        (ui/wait-for! #(nil? (get-in (st) [:ui :shows :editing])))
        (ui/right-click-node! robot (ui/query (cell :title alpha)))
        (menu-item! "Open in Editor")
        (ui/wait-for! #(some #{{:event/type :shows/open-editor :from-row alpha}} @sent))
        (ui/wait-for! #(= alpha (get-in (st) [:ui :dialogs :timeline-cue-editor :show-id])))))))

(defn- header-state [col-id]
  (ui/on-fx-thread
   #(let [t (table)]
      {:sort-order (mapv (fn [^TableColumn c] (.getId c)) (.getSortOrder t))
       :sort-type (some (fn [^TableColumn c] (when (= col-id (.getId c)) (str (.getSortType c)))) (.getColumns t))})))

(deftest header-click-sorts-and-reverses-test
  (let [robot (ui/robot)]
    (with-four-shows! robot)
    (is (= [around genesis one-more strobe] (item-ids)) "by title")
    (doseq [[n sort-state type order] [[1 {:column :artist :dir :asc} "ASCENDING" [around one-more strobe genesis]]
                                       [2 {:column :artist :dir :desc} "DESCENDING" [genesis strobe around one-more]]
                                       [3 {:column :artist :dir :asc} "ASCENDING" [around one-more strobe genesis]]]]
      (testing (str "click " n)
        (ui/mouse-click! robot "#shows-col-artist")
        (ui/wait-for! #(= sort-state (get-in (st) [:ui :shows :sort])))
        (ui/wait-for! #(= order (item-ids)))
        (ui/wait-for! #(= {:sort-order ["shows-col-artist"] :sort-type type} (header-state "shows-col-artist")))
        (is (= {:sort-order ["shows-col-artist"] :sort-type type} (header-state "shows-col-artist")))
        (is (= order (item-ids)))))))

(deftest typing-filters-rows-test
  (let [robot (ui/robot)]
    (with-four-shows! robot)
    (ui/type! robot "#shows-search" "daft")
    (ui/assert-text! "#shows-count" "2 of 4 shows")
    (is (= [around one-more] (item-ids)))
    (ui/click! robot "#shows-search-clear")
    (ui/assert-text! "#shows-count" "4 shows")
    (is (= "" (ui/node-text "#shows-search")))
    (testing "no match: the placeholder and its Clear search"
      (ui/type! robot "#shows-search" "zzz")
      (ui/assert-text! "#shows-no-match-text" "No shows match 'zzz'")
      (ui/click! robot "#shows-clear-search")
      (ui/wait-for! #(= 4 (count (item-ids)))))))

(deftest search-keeps-typing-under-load-test
  (let [robot (ui/robot)
        typed "daft punk one more"
        set-query @#'shows-handlers/handle-set-query]
    (with-four-shows! robot)
    (with-redefs [shows-handlers/handle-set-query (fn [event] (Thread/sleep 80) (set-query event))]
      (ui/type! robot "#shows-search" typed)
      (ui/wait-for! 10000 #(= typed (get-in (st) [:ui :shows :query])))
      (ui/sync-fx!))
    (let [^TextField f (ui/query "#shows-search")]
      (is (= typed (ui/on-fx-thread #(.getText f))))
      (is (= (count typed) (ui/on-fx-thread #(.getCaretPosition f))))
      (is (= typed (get-in (st) [:ui :shows :query]))))))

(deftest scope-bpm-range-test
  (let [robot (ui/robot)]
    (with-four-shows! robot)
    (ui/select! robot "#shows-scope" ":bpm")
    (ui/wait-for! #(= :bpm (get-in (st) [:ui :shows :scope])))
    (ui/type! robot "#shows-search" "120-128")
    (ui/assert-text! "#shows-count" "3 of 4 shows")
    (is (not (ui/has-style-class? "#shows-search" "text-field-invalid")))
    (ui/select! robot "#shows-scope" ":duration")
    (ui/wait-for! #(= :duration (get-in (st) [:ui :shows :scope])))
    (ui/type! robot "#shows-search" "5:")
    (ui/wait-for! #(ui/has-style-class? "#shows-search" "text-field-invalid"))
    (is (ui/has-style-class? "#shows-search" "text-field-invalid"))
    (ui/assert-text! "#shows-count" "0 of 4 shows")))

(deftest inline-edit-commits-once-test
  (let [robot (ui/robot)]
    (with-four-shows! robot)
    (ui/click-node! robot (ui/query (cell :artist strobe)))
    (ui/wait-for! #(= #{strobe} (get-in (st) [:ui :shows :selected])))
    (Thread/sleep 700)
    (ui/click-node! robot (ui/query (cell :artist strobe)))
    (ui/wait-for! #(= "shows-edit-field" (focus-owner-id)))
    (is (= {:show-id strobe :column :artist :draft "deadmau5" :invalid? false} (get-in (st) [:ui :shows :editing])))
    (let [before (count (undo-labels))]
      (.write robot " Live")
      (ui/wait-for! #(= "deadmau5 Live" (get-in (st) [:ui :shows :editing :draft])))
      (ui/press! robot KeyCode/ENTER)
      (ui/wait-for! #(nil? (get-in (st) [:ui :shows :editing])))
      (ui/sync-fx!)
      (Thread/sleep 300)
      (ui/sync-fx!)
      (is (= "deadmau5 Live" (get-in (st) [:chains :shows strobe :show/info :artist])))
      (is (= (inc before) (count (undo-labels))) "one undo step")
      (is (= "Edit Show Info" (top-undo)))
      (is (= #{strobe} (get-in (st) [:ui :shows :selected])))
      (is (= [strobe] (ui/on-fx-thread #(mapv :id (.getSelectedItems (.getSelectionModel (table)))))) "still selected")
      (is (= "shows-table" (focus-owner-id)) "the table has the focus, so the keys go on working"))))

(deftest edit-survives-row-change-test
  (let [robot (ui/robot)]
    (with-four-shows! robot)
    (events/dispatch! {:event/type :shows/start-edit :show-id strobe :column :artist})
    (ui/wait-for! #(= "shows-edit-field" (focus-owner-id)))
    (.write robot "Z")
    (ui/wait-for! #(= "deadmau5Z" (get-in (st) [:ui :shows :editing :draft])))
    (events/write-state! assoc-in [:chains :shows genesis :name] "Genesis II")
    (ui/wait-for! #(= "Genesis II" (ui/node-text (cell :title genesis))))
    (ui/wait-for! #(= "shows-edit-field" (focus-owner-id)))
    (let [^TextField f (ui/query "#shows-edit-field")]
      (is (= "deadmau5Z" (ui/on-fx-thread #(.getText f))))
      (is (= 9 (ui/on-fx-thread #(.getCaretPosition f)))))
    (.write robot "z")
    (ui/wait-for! #(= "deadmau5Zz" (get-in (st) [:ui :shows :editing :draft])))
    (let [before (count (undo-labels))]
      (ui/press! robot KeyCode/ENTER)
      (ui/wait-for! #(nil? (get-in (st) [:ui :shows :editing])))
      (Thread/sleep 300)
      (ui/sync-fx!)
      (is (= "deadmau5Zz" (get-in (st) [:chains :shows strobe :show/info :artist])))
      (is (= (inc before) (count (undo-labels))) "committed once"))))

(deftest escape-cancels-edit-test
  (let [robot (ui/robot)]
    (with-four-shows! robot)
    (events/dispatch! {:event/type :transport/play})
    (ui/wait-for! #(true? (get-in (st) [:playback :playing?])))
    (ui/click-node! robot (ui/query (cell :title strobe)))
    (ui/wait-for! #(= #{strobe} (get-in (st) [:ui :shows :selected])))
    (ui/focus! robot "#shows-table")
    (ui/press! robot KeyCode/F2)
    (ui/wait-for! #(= "shows-edit-field" (focus-owner-id)))
    (is (= :title (get-in (st) [:ui :shows :editing :column])))
    (.write robot "X")
    (ui/wait-for! #(= "StrobeX" (get-in (st) [:ui :shows :editing :draft])))
    (ui/press! robot KeyCode/ESCAPE)
    (ui/wait-for! #(nil? (get-in (st) [:ui :shows :editing])))
    (Thread/sleep 300)
    (ui/sync-fx!)
    (is (= "Strobe" (get-in (st) [:chains :shows strobe :name])) "unchanged")
    (is (true? (get-in (st) [:playback :playing?])) "Escape didn't stop the transport")
    (is (= "shows-table" (focus-owner-id)))))

(deftest keys-in-table-test ; also 07 §8's space-in-table-toggles-show-not-transport-test
  (let [robot (ui/robot)
        grid-before (get-in (st) [:chains :cue-chains])]
    (with-four-shows! robot)
    (ui/click-node! robot (ui/query (cell :title one-more)))
    (ui/wait-for! #(= #{one-more} (get-in (st) [:ui :shows :selected])))
    (ui/focus! robot "#shows-table")
    (testing "Delete asks to delete the show, and clears no cell"
      (ui/press! robot KeyCode/DELETE)
      (ui/assert-visible! "#confirm-dialog")
      (ui/assert-text! "#confirm-title" "Delete \"One More Time\"?")
      (is (= grid-before (get-in (st) [:chains :cue-chains])))
      (ui/click! robot "#confirm-cancel")
      (ui/wait-for! #(not (get-in (st) [:ui :dialogs :confirm :open?])))
      (is (= 4 (count (get-in (st) [:chains :shows])))))
    (ui/focus! robot "#shows-table")
    (testing "Enter plays the selected show"
      (ui/press! robot KeyCode/ENTER)
      (ui/wait-for! #(some? (get-in (st) [:playback :active-cues (shows/play-key one-more)])))
      (ui/wait-for! #(= "▶" (ui/node-text (str "#shows-status-" one-more)))))
    (testing "Space pauses and resumes the show, not the transport (07 §4.9)"
      (ui/wait-for! #(true? (get-in (st) [:playback :playing?])))
      (ui/press! robot KeyCode/SPACE)
      (ui/wait-for! #(true? (get-in (st) [:playback :active-cues (shows/play-key one-more) :paused?])))
      (is (true? (get-in (st) [:playback :playing?])) "the transport keeps running")
      (ui/press! robot KeyCode/SPACE)
      (ui/wait-for! #(not (get-in (st) [:playback :active-cues (shows/play-key one-more) :paused?])))
      (is (true? (get-in (st) [:playback :playing?]))))
    (testing "⌘F goes to the search"
      (.press robot (into-array KeyCode [(ui/shortcut-key-code)]))
      (.press robot (into-array KeyCode [KeyCode/F]))
      (.release robot (into-array KeyCode [KeyCode/F]))
      (.release robot (into-array KeyCode [(ui/shortcut-key-code)]))
      (ui/wait-for! #(= "shows-search" (focus-owner-id))))))

(deftest row-menu-acts-on-selection-test
  (let [robot (ui/robot)]
    (with-four-shows! robot)
    (ui/on-fx-thread #(.selectIndices (.getSelectionModel (table)) 0 (int-array [1])))
    (ui/wait-for! #(= #{around genesis} (get-in (st) [:ui :shows :selected])))
    (ui/right-click-node! robot (ui/query (cell :title around)))
    (menu-item! "Duplicate")
    (ui/wait-for! #(= 6 (count (get-in (st) [:chains :shows]))))
    (is (= #{"Around the World (copy)" "Genesis (copy)"}
           (set (keep #(get-in (st) [:chains :shows % :name])
                      (get-in (st) [:ui :shows :selected])))))
    (is (= "Duplicate 2 Shows" (top-undo)))
    (testing "on a row outside the selection: that row"
      (ui/right-click-node! robot (ui/query (cell :title strobe)))
      (menu-item! "Duplicate")
      (ui/wait-for! #(= 7 (count (get-in (st) [:chains :shows])))))))

(deftest delete-asks-then-removes-test
  (let [robot (ui/robot)]
    (with-four-shows! robot)
    (ui/right-click-node! robot (ui/query (cell :title strobe)))
    (menu-item! "Delete…")
    (ui/assert-visible! "#confirm-dialog")
    (ui/assert-text! "#confirm-title" "Delete \"Strobe\"?")
    (is (= 4 (count (get-in (st) [:chains :shows]))) "nothing gone before yes")
    (ui/click! robot "#confirm-ok")
    (ui/wait-for! #(= 3 (count (item-ids))))
    (is (nil? (get-in (st) [:chains :shows strobe])))
    (ui/assert-text! "#shows-count" "3 shows")
    (is (= "Delete Show" (top-undo)))
    (events/dispatch! {:event/type :edit/undo})
    (ui/wait-for! #(= 4 (count (item-ids))))
    (is (some? (get-in (st) [:chains :shows strobe])) "undo brings it back")))

(def ^:private tc-a #uuid "00000000-0000-0000-0000-0000000000c1")
(def ^:private tc-b #uuid "00000000-0000-0000-0000-0000000000c2")
(def ^:private tc-c #uuid "00000000-0000-0000-0000-0000000000c3")

(defn- tc-show
  "Show title, 120 BPM locked (2 beats a second), 25 fps, one clip of beats
   beats, beat 0 at tc-start seconds."
  [title tc-start beats]
  (let [track (random-uuid)]
    {:name title
     :editor-type :timeline
     :timeline/tempo-bpm 120.0
     :timeline/tc-fps 25
     :timeline/tc-start tc-start
     :tracks [{:id track :type :track :name "Track 1" :collapsed? false}]
     :items [{:id (random-uuid) :type :preset :preset-id :circle :enabled? true :effects []
              :track-id track :timeline/start 0.0 :timeline/duration beats}]}))

(defn- chase-of [id] (get-in (st) [:chains :shows id :timeline/chase]))

(defn- tooltip-text [selector]
  (ui/on-fx-thread #(some-> ^javafx.scene.control.Control (ui/query selector) .getTooltip .getText)))

(deftest refused-tick-snaps-back-with-notice-test
  (let [robot (ui/robot)
        tc-box #(str "#shows-tc-" %)]
    (open-shows! robot)
    ;; A on over [0, 300); B would be [299, 600); C [600, 900)
    (events/write-state! assoc-in [:chains :shows] {tc-a (assoc (tc-show "A" 0.0 600.0) :timeline/chase :mtc)
                                                     tc-b (tc-show "B" 299.0 602.0)
                                                     tc-c (tc-show "C" 600.0 600.0)})
    (ui/wait-for! #(= 3 (count (item-ids))))
    (ui/wait-for! #(ui/selected? (tc-box tc-a)))
    (testing "a refused tick: the notice, and the box unticks itself"
      (let [before (count (undo-labels))]
        (ui/click! robot (tc-box tc-b))
        (ui/wait-for! #(some-> (get-in (st) [:ui :notice :text]) (.contains "overlaps")))
        (ui/wait-for! #(some-> (ui/node-text "#status-notice-text") (.contains "overlaps")))
        (Thread/sleep 400)
        (ui/wait-for! #(not (ui/selected? (tc-box tc-b))))
        (is (not (ui/selected? (tc-box tc-b))))
        (is (= "'B' overlaps 'A' (00:00:00:00–00:05:00:00)" (get-in (st) [:ui :notice :text])))
        (is (nil? (chase-of tc-b)))
        (is (= before (count (undo-labels))) "no undo step")))
    (testing "a tick that applies stays, one step"
      (ui/click! robot (tc-box tc-c))
      (ui/wait-for! #(= :mtc (chase-of tc-c)))
      (Thread/sleep 400)
      (ui/sync-fx!)
      (is (ui/selected? (tc-box tc-c)))
      (is (= "Enable Timecode" (top-undo))))
    (testing "the row menu's Disable Timecode acts on the selection"
      (ui/on-fx-thread #(.selectIndices (.getSelectionModel (table)) 0 (int-array [2])))
      (ui/wait-for! #(= #{tc-a tc-c} (get-in (st) [:ui :shows :selected])))
      (ui/right-click-node! robot (ui/query (cell :title tc-c)))
      (menu-item! "Disable Timecode")
      (ui/wait-for! #(and (nil? (chase-of tc-a)) (nil? (chase-of tc-c))))
      (is (= "Disable Timecode" (top-undo)))
      (ui/wait-for! #(not (ui/selected? (tc-box tc-a)))))
    (testing "and Enable Timecode, on a row outside the selection: that row"
      (ui/right-click-node! robot (ui/query (cell :title tc-b)))
      (menu-item! "Enable Timecode")
      (ui/wait-for! #(= :mtc (chase-of tc-b)))
      (is (nil? (chase-of tc-a)))
      (ui/wait-for! #(ui/selected? (tc-box tc-b))))))

(deftest tc-problem-tooltip-test
  (let [robot (ui/robot)
        warn #(str "#shows-tc-problem-" %)]
    (open-shows! robot)
    (events/write-state! assoc-in [:chains :shows] {genesis (show "Genesis" "Justice" nil)
                                                     strobe (show "Strobe" "deadmau5" 128.0)
                                                     tc-a (assoc (tc-show "A" 0.0 600.0) :timeline/chase :mtc)
                                                     tc-b (tc-show "B" 299.0 602.0)
                                                     tc-c (tc-show "C" 600.0 600.0)})
    (ui/wait-for! #(= 5 (count (item-ids))))
    (testing "an unlocked show's ⚠ says it needs a BPM"
      (ui/wait-for! #(ui/visible? (warn genesis)))
      (is (= "⚠" (ui/node-text (warn genesis))))
      (is (= "needs a BPM" (tooltip-text (warn genesis)))))
    (testing "an empty show's, and one that would overlap"
      (is (= "empty" (tooltip-text (warn strobe))))
      (is (= "would overlap 'A' (00:00:00:00–00:05:00:00)" (tooltip-text (warn tc-b)))))
    (testing "no ⚠ on a show that follows, or one that could"
      (is (empty? (ui/query-all (warn tc-a))))
      (is (empty? (ui/query-all (warn tc-c)))))
    (testing "locking the tempo takes the reason away"
      (events/write-state! assoc-in [:chains :shows genesis :timeline/tempo-bpm] 120.0)
      (ui/wait-for! #(= "empty" (tooltip-text (warn genesis)))))))
