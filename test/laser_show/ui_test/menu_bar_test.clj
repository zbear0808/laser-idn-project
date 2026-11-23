(ns laser-show.ui-test.menu-bar-test
  "Menu bar in the title bar."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.event Event]
           [javafx.scene Node]
           [javafx.scene.control CheckMenuItem ContextMenu MenuButton MenuItem]
           [javafx.scene.input KeyCode KeyEvent]
           [javafx.stage Stage Window]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(deftest menu-bar-opens-a-menu
  (let [robot (ui/robot)]
    (ui/click! robot "#transport-menu")
    (ui/wait-for! #(some? (ui/query ".menu-item")))
    (is (some? (ui/query ".menu-item")))
    (ui/press! robot javafx.scene.input.KeyCode/ESCAPE)))

(defn- file-item
  "The File menu's item with this text."
  ^MenuItem [text]
  (let [^MenuButton menu (first (ui/query-all "#file-menu"))]
    (ui/on-fx-thread #(some (fn [^MenuItem item] (when (= text (.getText item)) item))
                            (.getItems menu)))))

(deftest save-as-is-always-enabled-test
  (is (nil? (get-in (state/get-raw-state) [:project :current-file])) "a new project, never saved")
  (is (false? (ui/on-fx-thread #(.isDisable (file-item "Save As...")))) "it can be saved under a name")
  (testing "and once it has a file"
    (events/write-state! assoc-in [:project :current-file] "test-output/shows/friday.zip")
    (ui/sync-fx!)
    (is (false? (ui/on-fx-thread #(.isDisable (file-item "Save As...")))))))

(defn- transport-items
  "The texts of the Transport menu's items that show."
  []
  (let [^MenuButton menu (first (ui/query-all "#transport-menu"))]
    (ui/on-fx-thread #(into []
                            (comp (filter (fn [^MenuItem item] (.isVisible item)))
                                  (keep (fn [^MenuItem item] (.getText item))))
                            (.getItems menu)))))

(defn- press-in-main-window!
  "A key press that reaches the main window's scene with no control taking
   it, as when nothing focused uses the key."
  [^KeyCode code]
  (let [^Node root (first (ui/query-all "#main-layout"))]
    (ui/on-fx-thread #(Event/fireEvent root (KeyEvent. KeyEvent/KEY_PRESSED "" "" code false false false false)))
    (ui/sync-fx!)))

(deftest transport-menu-has-one-play-stop-item-test
  (let [robot (ui/robot)
        playing? #(get-in (state/get-raw-state) [:playback :playing?])]
    (is (= ["Play" "Retrigger"] (transport-items)) "one item plays or stops, not Pause and Stop")
    (testing "the open menu shows those two"
      (ui/click! robot "#transport-menu")
      (ui/wait-for! #(some? (ui/query ".menu-item")))
      (is (= 2 (count (filter #(.isVisible ^Node %) (ui/query-all ".menu-item")))))
      (ui/on-fx-thread #(doseq [window (vec (Window/getWindows))]
                          (when (instance? ContextMenu window)
                            (.hide ^ContextMenu window))))
      (ui/wait-for! #(nil? (ui/query ".menu-item"))))
    (ui/click! robot "#grid-cell-0-0")
    (ui/wait-for! #(true? (playing?)))
    (ui/wait-for! #(= ["Stop (blackout)" "Retrigger"] (transport-items)))
    (is (= ["Stop (blackout)" "Retrigger"] (transport-items)))
    (testing "Escape still stops, as the Stop item's shortcut did"
      (press-in-main-window! KeyCode/ESCAPE)
      (ui/wait-for! #(false? (playing?)))
      (is (some? (get-in (state/get-raw-state) [:playback :active-cues [0 0]])) "the blackout: the cue is held")
      (ui/wait-for! #(= ["Play" "Retrigger"] (transport-items))))))


;; The menu stubs (dialog shell plan 02 §9): the View menu works, the
;; unimplemented items are gone

(defn- menu-items
  "The texts of the items in the menu with this id (separators left out)."
  [menu-id]
  (let [^MenuButton menu (first (ui/query-all menu-id))]
    (ui/on-fx-thread #(into [] (keep (fn [^MenuItem item] (.getText item))) (.getItems menu)))))

(defn- view-item
  "The View menu's item with this text."
  ^CheckMenuItem [text]
  (let [^MenuButton menu (first (ui/query-all "#view-menu"))]
    (ui/on-fx-thread #(some (fn [^MenuItem item] (when (= text (.getText item)) item))
                            (.getItems menu)))))

(defn- pick-view-item!
  "Choose the View menu's item, as a click on it does."
  [text]
  (let [item (view-item text)]
    (ui/on-fx-thread #(.fire item))
    (ui/sync-fx!)))

(defn- press-shortcut-in-main-window!
  "Shortcut+code (Cmd on macOS, Ctrl elsewhere) reaching the main window's
   scene, where the menus' accelerators are."
  [^KeyCode code]
  (let [^Node root (first (ui/query-all "#main-layout"))
        mac? (= KeyCode/COMMAND (ui/shortcut-key-code))]
    (ui/on-fx-thread #(Event/fireEvent root (KeyEvent. KeyEvent/KEY_PRESSED "" "" code false (not mac?) false mac?)))
    (ui/sync-fx!)))

(deftest unimplemented-menu-items-are-gone-test
  (is (not-any? #{"Export..."} (menu-items "#file-menu")) "no exporter yet")
  (is (= ["New Project" "Open..." "Save" "Save As..." "Exit"] (menu-items "#file-menu")))
  (is (not-any? #{"Check for Updates"} (menu-items "#help-menu")) "releases carry no binaries yet")
  (is (some #{"About"} (menu-items "#help-menu")) "About stays")
  (is (= ["Show Preview" "Fullscreen"] (menu-items "#view-menu"))))

(deftest show-preview-hides-and-restores-the-preview-test
  (let [visible? #(get-in (state/get-raw-state) [:ui :preview-visible?] true)]
    (ui/assert-visible! "#preview-panel")
    (is (true? (ui/on-fx-thread #(.isSelected (view-item "Show Preview")))) "checked while it shows")
    (testing "View > Show Preview hides it, and the tab gets the window"
      (pick-view-item! "Show Preview")
      (ui/wait-for! #(false? (visible?)))
      (ui/wait-for! #(nil? (ui/query "#preview-panel")))
      (is (nil? (ui/query "#main-split")))
      (ui/assert-visible! "#grid-cell-0-0")
      (is (false? (ui/on-fx-thread #(.isSelected (view-item "Show Preview"))))))
    (testing "Shortcut+P brings it back"
      (press-shortcut-in-main-window! KeyCode/P)
      (ui/wait-for! #(true? (visible?)))
      (ui/wait-for! #(some? (ui/query "#preview-panel")))
      (ui/assert-visible! "#main-split")
      (is (true? (ui/on-fx-thread #(.isSelected (view-item "Show Preview"))))))
    (testing "the tabs that never show it still don't, with it on"
      (events/dispatch! {:event/type :ui/set-active-tab :tab-id :settings})
      (ui/wait-for! #(nil? (ui/query "#preview-panel")))
      (testing "and off there, it stays off on the grid"
        (press-shortcut-in-main-window! KeyCode/P)
        (ui/wait-for! #(false? (visible?)))
        (events/dispatch! {:event/type :ui/set-active-tab :tab-id :grid})
        (ui/wait-for! #(some? (ui/query "#grid-cell-0-0")))
        (is (nil? (ui/query "#preview-panel")))
        (pick-view-item! "Show Preview")
        (ui/wait-for! #(some? (ui/query "#preview-panel")))))))

(deftest fullscreen-follows-the-main-window-test
  (let [flag #(get-in (state/get-raw-state) [:ui :fullscreen?])
        main-stage #(ui/main-window)
        stage-full? #(ui/on-fx-thread (fn [] (.isFullScreen ^Stage (main-stage))))]
    (is (identical? (main-stage) (ui/on-fx-thread events/main-stage)) "the effect finds the app's window, not TestFX's")
    (is (not (stage-full?)))
    (try
      (testing "F11 puts the main window in fullscreen"
        (press-in-main-window! KeyCode/F11)
        (ui/wait-for! #(and (true? (flag)) (stage-full?)))
        (is (true? (ui/on-fx-thread #(.isSelected (view-item "Fullscreen"))))))
      (testing "Escape is the blackout here: it stops the show and doesn't leave fullscreen"
        (ui/click! (ui/robot) "#grid-cell-0-0")
        (ui/wait-for! #(true? (get-in (state/get-raw-state) [:playback :playing?])))
        ;; A real key press: JavaFX's own Escape-leaves-fullscreen sits
        ;; below the scene, where a fired KeyEvent doesn't go
        (ui/press! (ui/robot) KeyCode/ESCAPE)
        (ui/wait-for! #(false? (get-in (state/get-raw-state) [:playback :playing?])))
        (events/flush!)
        (ui/sync-fx!)
        (is (stage-full?))
        (is (true? (flag))))
      (testing "View > Fullscreen leaves it"
        (pick-view-item! "Fullscreen")
        (ui/wait-for! #(and (false? (flag)) (not (stage-full?))))
        (is (false? (ui/on-fx-thread #(.isSelected (view-item "Fullscreen"))))))
      (testing "the OS's own control: the flag follows the window both ways"
        (ui/on-fx-thread #(.setFullScreen ^Stage (main-stage) true))
        (ui/wait-for! #(true? (flag)))
        (ui/on-fx-thread #(.setFullScreen ^Stage (main-stage) false))
        (ui/wait-for! #(false? (flag)))
        (is (false? (ui/on-fx-thread #(.isSelected (view-item "Fullscreen"))))))
      (finally
        (ui/on-fx-thread #(some-> ^Stage (main-stage) (.setFullScreen false)))))))
