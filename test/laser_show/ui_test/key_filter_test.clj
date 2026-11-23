(ns laser-show.ui-test.key-filter-test
  "A scene filter gets the key before the menu accelerator (keyboard Phase 5, step 0)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.event EventHandler]
           [javafx.scene Scene]
           [javafx.scene.input KeyCode KeyEvent]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(deftest consuming-scene-filter-precedes-menu-accelerator-test
  (let [robot (ui/robot)
        ^Scene scene (ui/on-fx-thread #(.getScene (ui/main-window)))
        saw-space? (promise)
        filter (reify EventHandler
                 (handle [_ e]
                   (when (= KeyCode/SPACE (.getCode ^KeyEvent e))
                     (.consume ^KeyEvent e)
                     (deliver saw-space? true))))
        playing? #(get-in (state/get-raw-state) [:playback :playing?])]
    (ui/focus! robot "#main-layout")
    (ui/on-fx-thread #(.addEventFilter scene KeyEvent/KEY_PRESSED filter))
    (try
      (ui/press! robot KeyCode/SPACE)
      (is (true? (deref saw-space? 2000 false)))
      (events/flush!)
      (is (not (playing?)) "the scene consumed Space before the Transport menu could play")
      (finally (ui/on-fx-thread #(.removeEventFilter scene KeyEvent/KEY_PRESSED filter))))
    (ui/press! robot KeyCode/SPACE)
    (ui/wait-for! playing?)))
