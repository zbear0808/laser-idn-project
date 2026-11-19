(ns laser-show.ui-test.menu-bar-test
  "Menu bar in the title bar."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.event Event]
           [javafx.scene Node]
           [javafx.scene.control ContextMenu MenuButton MenuItem]
           [javafx.scene.input KeyCode KeyEvent]
           [javafx.stage Window]))

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
    (state/swap-state! assoc-in [:project :current-file] "test-output/shows/friday.zip")
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
