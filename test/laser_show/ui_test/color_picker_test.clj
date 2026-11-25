(ns laser-show.ui-test.color-picker-test
  "The colour pickers (views/components/parameter_controls.clj color-picker,
   follow-up B4): a pick sends its colour as plain data, :color [red green
   blue], read from the picker on the FX thread, never the picker or its
   ActionEvent. The Set Color effect's picker stores it, in the selected
   keyframe while keyframes are on, as one undo step. After a pick that
   changed nothing it shows the colour that applies again; a colour it
   shows while its palette or custom colour dialog is open is a preview,
   sent only by its action and not put back before it closes."
  (:require [cljfx.api :as fx]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.walk :as walk]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.chain.params :as params]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.components.applied-value :as applied-value]
            [laser-show.views.components.parameter-controls :as param-controls])
  (:import [javafx.event ActionEvent]
           [javafx.scene Node Scene]
           [javafx.scene.control ColorPicker]
           [javafx.scene.layout StackPane]
           [javafx.scene.paint Color]
           [javafx.stage Stage]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- pick!
  "What a click on a square of the picker's palette does
   (javafx.scene.control.skin.ColorPalette): set its value, then fire its
   action. FX thread."
  [^ColorPicker picker ^Color color]
  (.setValue picker color)
  (.fireEvent picker (ActionEvent.)))

(defn- shown
  "The colour picker shows now."
  ^Color [^ColorPicker picker]
  (ui/on-fx-thread #(.getValue picker)))

(defn- java-objects
  "The JavaFX objects anywhere in x: none in plain data."
  [x]
  (let [found (atom [])]
    (walk/postwalk #(do (when (and (some? %) (.startsWith (.getName (class %)) "javafx.")) (swap! found conj %)) %) x)
    @found))

(def ^:private white {:red 1.0 :green 1.0 :blue 1.0})


;; The picker on its own: nothing applies what it sends

(deftest picker-sends-a-plain-colour-and-shows-what-applies-test
  (let [sent (atom [])
        attached (applied-value/attached-count)
        template {:event/type :test/set-color :domain :effect-chains :entity-key [0 0] :effect-path [0]}
        desc {:fx/type param-controls/color-picker :id "test-picker" :current-params white :event template}]
    ;; The app gets nothing: whatever the picker sends is the only edit
    (with-redefs [events/dispatch! (fn [event] (swap! sent conj event))]
      (let [component (ui/on-fx-thread #(fx/create-component desc))
            ^ColorPicker picker (fx/instance component)
            stage (ui/on-fx-thread #(doto (Stage.)
                                      (.setScene (Scene. (StackPane. (into-array Node [picker])) 300 200))
                                      (.show)))]
        (try
          (is (= Color/WHITE (shown picker)) "the colour that applies")
          (is (= (inc attached) (applied-value/attached-count)))
          (testing "a pick: its colour, once, as plain data; then, as nothing applied it, the colour that applies"
            (ui/on-fx-thread #(pick! picker Color/RED))
            (is (= [(assoc template :color [1.0 0.0 0.0])] @sent))
            (is (empty? (java-objects @sent)))
            (is (ui/wait-for! #(= Color/WHITE (shown picker)))))
          (testing "a colour shown while its popup is open is a preview: not sent, and kept until it closes"
            (reset! sent [])
            (ui/on-fx-thread #(do (.show picker) (.setValue picker Color/BLUE)))
            (Thread/sleep (+ applied-value/settle-ms 200))
            (is (= Color/BLUE (shown picker)) "not put back while it is open")
            (is (= [] @sent))
            ;; Use in the custom colour dialog: the action, then the popup closes
            (ui/on-fx-thread #(do (.fireEvent picker (ActionEvent.)) (.hide picker)))
            (is (= [(assoc template :color [0.0 0.0 1.0])] @sent) "its colour, once")
            (is (ui/wait-for! #(= Color/WHITE (shown picker))) "then, closed, the colour that applies"))
          (finally
            (ui/on-fx-thread #(do (.close ^Stage stage)
                                  (fx/delete-component component)))))
        (is (= attached (applied-value/attached-count)) "its listener and timer went with it")))))


;; The Set Color effect's picker, in the effect chain editor

(defn- picker
  "The Set Color effect's colour picker, in the open editor."
  ^ColorPicker []
  (first (ui/query-all "#set-color-picker")))

(defn- effect [] (get-in (state/get-raw-state) [:chains :effect-chains [0 0] :items 0]))
(defn- stored [] (select-keys (:params (effect)) [:red :green :blue]))
(defn- undo-depth [] (count (get-in (state/get-raw-state) [:history :undo-stack])))

(defn- open-set-color!
  "Effects cell [0 0] with fx, a Set Color, its one effect, in the effect
   chain editor (selected as it opens), once its picker shows."
  [fx]
  (events/write-state! assoc-in [:chains :effect-chains [0 0]] {:active? false :items [fx]})
  (events/dispatch! {:event/type :effect-chain/open-editor :col 0 :row 0})
  (ui/wait-for! #(some? (picker))))

(deftest set-color-picker-stores-a-plain-colour-test
  (open-set-color! {:id (random-uuid) :effect-id :set-color :enabled? true :params white})
  (is (= Color/WHITE (shown (picker))))
  (let [sent (atom [])
        dispatch events/dispatch!
        before (undo-depth)]
    (with-redefs [events/dispatch! (fn [e] (swap! sent conj e) (dispatch e))]
      (ui/on-fx-thread #(pick! (picker) Color/RED))
      (is (ui/wait-for! #(and (= {:red 1.0 :green 0.0 :blue 0.0} (stored)) (= Color/RED (shown (picker)))))
          "the colour, stored and shown"))
    (let [edits (filter :color @sent)]
      (is (= [[1.0 0.0 0.0]] (map :color edits)) "one edit, the colour as plain data")
      (is (empty? (java-objects edits)) "no JavaFX object in it"))
    (is (= (inc before) (undo-depth)) "one undo step")
    (testing "Undo shows the colour before"
      (events/dispatch! {:event/type :edit/undo})
      (is (ui/wait-for! #(and (= white (stored)) (= Color/WHITE (shown (picker)))))))))

(deftest set-color-picker-edits-the-selected-keyframe-test
  (let [frames #(mapv (fn [kf] (select-keys (:params kf) [:red :green :blue])) (get-in (effect) [:keyframe-modulator :keyframes]))]
    (open-set-color! {:id (random-uuid) :effect-id :set-color :enabled? true :params white
                      :keyframe-modulator {:enabled? true :driver :time :period 4.0 :time-unit :beats :loop-mode :loop
                                           :selected-keyframe 1
                                           :keyframes [{:position 0.0 :params white} {:position 1.0 :params white}]}})
    (ui/on-fx-thread #(pick! (picker) Color/LIME))
    (is (ui/wait-for! #(and (= [white {:red 0.0 :green 1.0 :blue 0.0}] (frames)) (= Color/LIME (shown (picker)))))
        "the selected keyframe's colour")
    (is (= white (stored)) "the effect's own colour stays")))

(deftest unapplied-pick-shows-the-colour-that-applies-test
  ;; No colour picker is one the show lock refuses (a zone pattern has no
  ;; colour): a colour edit that changes nothing stands in for a refusal
  (open-set-color! {:id (random-uuid) :effect-id :set-color :enabled? true :params white})
  (with-redefs [params/handle-update-color-param (fn [state _config _event] state)]
    (ui/on-fx-thread #(pick! (picker) Color/BLUE))
    (events/flush!)
    (is (ui/wait-for! #(= Color/WHITE (shown (picker)))) "the colour that applies again")
    (is (= white (stored)))))
