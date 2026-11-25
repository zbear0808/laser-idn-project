(ns laser-show.ui-test.canvas-interaction-test
  "The shared interactive canvas (views/components/visual_editors/
   canvas_interaction.clj): a canvas given :on-scroll gets the scroll
   wheel, its consumer's :dispatch is sent and :consumed? stops the event
   there (follow-up B15: the handler was never installed); its keys still
   reach :on-key. A canvas without :on-scroll lets the wheel go on to its
   parent, as the timeline's lanes pane takes it."
  (:require [cljfx.api :as fx]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.components.visual-editors.canvas-interaction :as ci])
  (:import [javafx.event Event EventHandler]
           [javafx.scene Node Scene]
           [javafx.scene.canvas Canvas]
           [javafx.scene.input KeyCode KeyEvent ScrollEvent ScrollEvent$HorizontalTextScrollUnits
            ScrollEvent$VerticalTextScrollUnits]
           [javafx.scene.layout StackPane]
           [javafx.stage Stage]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- wheel
  "A turn of the scroll wheel by dy at scene point x, y."
  ^ScrollEvent [x y dy]
  (ScrollEvent. ScrollEvent/SCROLL x y x y false false false false false false 0.0 dy 0.0 dy
                ScrollEvent$HorizontalTextScrollUnits/NONE 0.0 ScrollEvent$VerticalTextScrollUnits/NONE 0.0 0 nil))

(defn- with-canvas
  "(f canvas) with desc's canvas mounted in a pane of its own window, which
   counts the scroll events that reach it in passed-up; then it goes."
  [desc passed-up f]
  (let [component (ui/on-fx-thread #(fx/create-component desc))
        ^Canvas canvas (fx/instance component)
        pane (ui/on-fx-thread #(doto (StackPane. (into-array Node [canvas]))
                                 (.addEventHandler ScrollEvent/SCROLL
                                                   (reify EventHandler (handle [_ _] (swap! passed-up inc))))))
        stage (ui/on-fx-thread #(doto (Stage.) (.setScene (Scene. pane 300 200)) (.show)))]
    (try
      (f canvas)
      (finally
        (ui/on-fx-thread #(do (.close ^Stage stage)
                              (fx/delete-component component)))))))

(def ^:private canvas-desc
  {:fx/type ci/interactive-canvas
   :id "scroll-canvas" :width 200 :height 100 :value 3
   :render! (fn [_canvas _value _drag])})

(deftest scroll-reaches-the-canvas-consumer-test
  (let [sent (atom [])
        scrolls (atom [])
        passed-up (atom 0)]
    ;; The app gets nothing: what the canvas sends is all there is
    (with-redefs [events/dispatch! (fn [event] (swap! sent conj event))]
      (testing "a canvas with a scroll consumer"
        (with-canvas
          (assoc canvas-desc
                 :on-scroll (fn [{:keys [delta-y value x y]}]
                              (swap! scrolls conj {:delta-y delta-y :value value :at? (and (number? x) (number? y))})
                              {:dispatch {:event/type :test/zoom :by delta-y} :consumed? true})
                 :on-key (fn [code _shift? value _drag]
                           {:dispatch {:event/type :test/key :code code :value value}}))
          passed-up
          (fn [^Canvas canvas]
            (testing ": a wheel turn over it reaches its consumer, with the canvas's value"
              (ui/on-fx-thread #(Event/fireEvent canvas (wheel 150.0 100.0 -40.0)))
              (is (= [{:delta-y -40.0 :value 3 :at? true}] @scrolls))
              (is (= [{:event/type :test/zoom :by -40.0}] @sent) "its :dispatch, sent")
              (is (zero? @passed-up) "consumed: it goes no further"))
            (testing ": its keys still reach :on-key"
              (reset! sent [])
              (ui/on-fx-thread #(Event/fireEvent canvas (KeyEvent. KeyEvent/KEY_PRESSED "" "" KeyCode/LEFT false false false false)))
              (is (= [{:event/type :test/key :code KeyCode/LEFT :value 3}] @sent))))))
      (testing "a canvas without one lets the wheel go on to its parent"
        (reset! sent [])
        (reset! passed-up 0)
        (with-canvas canvas-desc passed-up
          (fn [^Canvas canvas]
            (ui/on-fx-thread #(Event/fireEvent canvas (wheel 150.0 100.0 -40.0)))
            (is (= 1 @passed-up))
            (is (= [] @sent))))))))
