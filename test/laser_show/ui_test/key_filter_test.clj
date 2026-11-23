(ns laser-show.ui-test.key-filter-test
  "A scene filter gets the key before the menu accelerator (keyboard Phase 5, step 0)."
  (:require [cljfx.api :as fx]
            [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.input.router :as router]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.key-triggers :as key-triggers])
  (:import [javafx.event Event EventHandler]
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

(deftest disposing-or-replacing-a-scene-removes-its-filter-test
  ;; Use nodes still reachable by the test after cljfx releases them. A
  ;; leaked listener would route the final press or release the key twice.
  (events/write-state! assoc-in [:config :input :trigger-map {:source :keyboard :id :j}]
                       {:type :trigger-cue :target [0 0] :behavior :momentary})
  (let [heard (atom [])
        desc (fn [k] (key-triggers/wrap-scene {:fx/type fx/ext-recreate-on-key-changed :key k
                                               :desc {:fx/type :scene :root {:fx/type :pane}}} #{}))
        fire! (fn [^Scene scene type]
                (Event/fireEvent scene (KeyEvent. type "" "" KeyCode/J false false false false)))]
    (router/register-global-handler! ::record
                                     #(when (= :keyboard (:source %)) (swap! heard conj (:state %))))
    (try
      (ui/on-fx-thread
       (fn []
         (let [component (fx/create-component (desc :one))
               old (fx/instance component)]
           (fire! old KeyEvent/KEY_PRESSED)
           (let [updated (fx/advance-component component (desc :two))
                 new (fx/instance updated)]
             (try
               (is (not (identical? old new)) "the keyed scene was replaced")
               (fire! old KeyEvent/KEY_PRESSED)
               (fire! new KeyEvent/KEY_PRESSED)
               (finally (fx/delete-component updated)))
             (fire! new KeyEvent/KEY_PRESSED)))))
      (is (= [:pressed :released :pressed :released] @heard))
      (finally (router/unregister-handler! ::record)))))
