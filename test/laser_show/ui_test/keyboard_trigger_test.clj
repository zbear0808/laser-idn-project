(ns laser-show.ui-test.keyboard-trigger-test
  "Keyboard triggers through the mounted app: JavaFX focus and accelerators,
   the input router and mapper, and the actions the musician actually plays."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.input-mapper :as input-mapper]
            [laser-show.input.router :as router]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene.input KeyCode]
           [org.testfx.api FxRobot]))

(def ^:dynamic ^:private *key-edges* nil)

(defn- with-input-mapper [test]
  ;; start-test! mounts the real UI without hardware or the input mapper.
  ;; Observe the router alongside the mapper: silence (and exactly one edge)
  ;; can't always be inferred from an action's final state.
  (let [edges (atom [])]
    (input-mapper/set-dispatch-fn! events/dispatch!)
    (input-mapper/start-mapper!)
    (router/on-any! ::key-edges :keyboard #(swap! edges conj [(:id %) (:state %)]))
    (try
      (binding [*key-edges* edges] (test))
      (finally
        (router/unregister-handler! ::key-edges)
        (input-mapper/stop-mapper!)
        (input-mapper/set-dispatch-fn! nil)))))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app with-input-mapper)

(defn- at [& path] (get-in (state/get-raw-state) path))
(defn- cue [cell] (at :playback :active-cues cell))
(defn- playing? [] (true? (at :playback :playing?)))
(defn- binding-path [id] [:config :input :trigger-map {:source :keyboard :id id}])
(defn- bind! [id action] (events/write-state! assoc-in (binding-path id) action))
(defn- main-robot! [] (ui/focus! (ui/robot) "#main-layout"))

(defn- while-held!
  "Run f between the robot's physical key down and key up, even on failure."
  [^FxRobot robot code f]
  (let [codes (into-array KeyCode [code])]
    (.press robot codes)
    (try
      (ui/sync-fx!)
      (f)
      (finally
        (.release robot codes)
        (ui/sync-fx!)))))

(deftest bound-key-triggers-its-cue-test
  (bind! :digit1 {:type :trigger-cue :target [0 0]})
  (ui/press! (main-robot!) KeyCode/DIGIT1)
  (ui/wait-for! #(some? (cue [0 0])))
  (is (= [[:digit1 :pressed] [:digit1 :released]] @*key-edges*)))

(deftest momentary-binding-releases-on-key-up-test
  (bind! :h {:type :trigger-cue :target [0 0] :behavior :momentary})
  (while-held! (main-robot!) KeyCode/H
               #(ui/wait-for! (fn [] (some? (cue [0 0])))))
  (ui/wait-for! #(nil? (cue [0 0])))
  (is (= [[:h :pressed] [:h :released]] @*key-edges*)))

(deftest bound-key-holds-and-releases-a-vibe-role-test
  (bind! :j {:type :vibe :role :hit})
  (while-held! (main-robot!) KeyCode/J
               (fn []
                 (ui/wait-for! #(= :input (at :vibe-runtime :held :hit :src)))
                 (is (true? (:hold? (cue (at :vibe-runtime :voices :hit :cell)))))))
  (ui/wait-for! #(nil? (at :vibe-runtime :held :hit)))
  (is (= [[:j :pressed] [:j :released]] @*key-edges*)))

(deftest unbound-key-passes-through-test
  (let [robot (main-robot!)]
    (ui/press! robot KeyCode/SPACE)
    (ui/wait-for! playing?)
    (ui/press! robot KeyCode/SPACE)
    (ui/wait-for! #(not (playing?)))
    (is (empty? @*key-edges*) "unbound Space reaches Play/Stop without entering the router")))

(deftest bound-space-overrides-play-stop-test
  ;; A cue would itself start the transport. A map pad lets us see both
  ;; effects independently: its map changes, but the transport stays stopped.
  (bind! :space {:type :zone-map :target :map/odd-even})
  (events/write-state! assoc-in [:chains :cue-chains [0 0] :items 0 :target] :zone/a)
  (ui/assert-text! "#zone-map-chip-left-right" "● Left / Right")
  (ui/press! (main-robot!) KeyCode/SPACE)
  (ui/wait-for! #(= :map/odd-even (at :zone-map :active)))
  (ui/assert-text! "#zone-map-chip-odd-even" "● Odd / Even")
  (is (events/flush!))
  (is (not (playing?)) "the same Space did not run Play"))

(deftest text-field-focus-blocks-triggers-test
  (bind! :digit1 {:type :trigger-cue :target [0 0]})
  (let [robot (ui/robot)]
    (ui/focus! robot "#bpm-input")
    (ui/fill! robot "#bpm-input" "12")
    (ui/press! robot KeyCode/DIGIT1)
    (ui/assert-text! "#bpm-input" "121")
    (is (events/flush!))
    (is (nil? (cue [0 0])))
    (is (empty? @*key-edges*))))

(deftest escape-always-stops-test
  ;; Even a project with a stale Escape binding can't take over the panic key.
  (bind! :escape {:type :trigger-cue :target [0 0]})
  (let [robot (ui/robot)]
    (doseq [selector ["#main-layout" "#bpm-input"]]
      (testing selector
        (events/dispatch! {:event/type :transport/play})
        (ui/wait-for! playing?)
        (ui/focus! robot selector)
        (ui/press! robot KeyCode/ESCAPE)
        (ui/wait-for! #(not (playing?)))
        (is (nil? (cue [0 0])))
        (is (empty? @*key-edges*))))))
