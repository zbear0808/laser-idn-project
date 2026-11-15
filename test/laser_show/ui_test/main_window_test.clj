(ns laser-show.ui-test.main-window-test
  "Main window: default layout, top-level tabs, typing into the toolbar and the
   unsaved-changes check when the window is closed."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.event Event]
           [javafx.stage Stage WindowEvent]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(deftest user-can-type-and-switch-tabs
  (let [robot (ui/robot)]
    (ui/fill! robot "#bpm-input" "140")
    (is (= "140" (ui/node-text "#bpm-input")))
    (is (some? (ui/query "#settings-tab")))
    (ui/click! robot "#settings-tab")))

(deftest application-starts-with-default-layout
  (is (ui/visible? "#main-layout"))
  (ui/assert-visible! "#play-btn")
  (ui/assert-visible! "#bpm-input")
  (ui/assert-visible! "#grid-tab")
  (ui/assert-visible! "#preview-panel")
  (ui/assert-visible! "#status-bar")
  (ui/assert-text! "#bpm-input" "120.0")
  (is (false? (get-in (state/get-raw-state) [:playback :playing?])))
  (ui/assert-no-node! "#stop-btn"))

(deftest top-level-tabs-update-content-and-preview
  (let [robot (ui/robot)]
    (doseq [[tab preview?] [["effects" true]
                            ["projectors" false]
                            ["zones" false]
                            ["settings" false]
                            ["grid" true]]]
      (ui/click! robot (str "#" tab "-tab"))
      (ui/wait-for! #(= (keyword tab)
                        (get-in (state/get-raw-state) [:ui :active-tab])))
      (is (= (keyword tab)
             (get-in (state/get-raw-state) [:ui :active-tab])))
      (ui/wait-for! #(= preview? (ui/visible? "#preview-panel")))
      (is (= preview? (ui/visible? "#preview-panel"))))))


;; Closing the window

(def ^:private saved-project "test-output/main-window-test.zip")

(defn- main-stage ^Stage []
  (some-> (first (ui/query-all "#main-layout")) .getScene .getWindow))

(defn- close-main-window!
  "Send the window's close request, as the OS close button does."
  []
  (.interact (ui/robot)
             (reify Runnable
               (run [_]
                 (let [stage (main-stage)]
                   (Event/fireEvent stage (WindowEvent. stage WindowEvent/WINDOW_CLOSE_REQUEST))))))
  (ui/sync-fx!))

(defn- confirm-open? []
  (true? (get-in (state/get-raw-state) [:ui :dialogs :unsaved-confirm :open?])))

(defn- make-project-dirty! []
  (events/dispatch! {:event/type :grid/clear-cell :col 0 :row 0})
  (ui/wait-for! #(true? (get-in (state/get-raw-state) [:project :dirty?]))))

(defn- with-exit-counter
  "Run f with the exit function replaced by a counter, so asking the app to exit
   doesn't end the test JVM."
  [f]
  (let [exits (atom 0)]
    (events/set-exit-fn! #(swap! exits inc))
    (try (f exits)
         (finally (events/set-exit-fn! nil)))))

(deftest closing-a-clean-project-exits-without-asking-test
  (with-exit-counter
    (fn [exits]
      (close-main-window!)
      (ui/wait-for! #(= 1 @exits))
      (is (not (confirm-open?)))
      (ui/assert-no-node! "#unsaved-confirm-dialog"))))

(deftest closing-a-dirty-project-asks-first-test
  (with-exit-counter
    (fn [exits]
      (make-project-dirty!)
      (close-main-window!)
      (ui/wait-for! #(ui/visible? "#unsaved-cancel"))
      (ui/assert-visible! "#unsaved-save")
      (ui/assert-visible! "#unsaved-discard")
      (is (zero? @exits))
      (is (.isShowing (main-stage)) "the window itself was not closed"))))

(deftest cancel-keeps-the-window-and-the-project-test
  (with-exit-counter
    (fn [exits]
      (make-project-dirty!)
      (close-main-window!)
      (ui/wait-for! #(ui/visible? "#unsaved-cancel"))
      (ui/click! (ui/robot) "#unsaved-cancel")
      (ui/wait-for! #(not (confirm-open?)))
      (ui/assert-no-node! "#unsaved-confirm-dialog")
      (is (zero? @exits))
      (is (.isShowing (main-stage)))
      (is (true? (get-in (state/get-raw-state) [:project :dirty?]))))))

(deftest discard-exits-test
  (with-exit-counter
    (fn [exits]
      (make-project-dirty!)
      (close-main-window!)
      (ui/wait-for! #(ui/visible? "#unsaved-discard"))
      (ui/click! (ui/robot) "#unsaved-discard")
      (ui/wait-for! #(= 1 @exits))
      (is (not (confirm-open?))))))

(deftest save-then-exit-test
  (let [file (io/file saved-project)]
    (io/make-parents file)
    (when (.exists file) (.delete file))
    (try
      (with-exit-counter
        (fn [exits]
          ;; a project that already has a file, so no native chooser is needed
          (state/swap-state! assoc-in [:project :current-file] saved-project)
          (make-project-dirty!)
          (close-main-window!)
          (ui/wait-for! #(ui/visible? "#unsaved-save"))
          (ui/click! (ui/robot) "#unsaved-save")
          (ui/wait-for! #(= 1 @exits))
          (is (.exists file) "the project was written before the app was asked to exit")
          (is (false? (get-in (state/get-raw-state) [:project :dirty?])))))
      (finally
        (when (.exists file) (.delete file))))))
