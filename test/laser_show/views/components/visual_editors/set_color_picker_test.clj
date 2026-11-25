(ns laser-show.views.components.visual-editors.set-color-picker-test
  "The Set Color effect's visual editor (custom-param-renderers/
   set-color-picker-visual-editor, follow-up N3): its swatch, its R/G/B
   labels and its picker show each channel's colour whatever the channel
   holds: a number, a modulator that is off (its static value) or on (the
   middle of its range, marked ∿), nothing (full), or a value that isn't a
   colour (full). It never throws while it renders."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.state.domains :as domains]
            [laser-show.views.components.visual-editors.custom-param-renderers :as custom-renderers]
            [laser-show.views.harness :as h])
  (:import [javafx.scene.control ColorPicker Label]
           [javafx.scene.layout Region]
           [javafx.scene.paint Color]))

(def ^:private event-template
  {:event/type :chain/update-color-param :domain :effect-chains :entity-key [0 0] :effect-path [0]})

(defn- desc [params]
  {:fx/type custom-renderers/set-color-picker-visual-editor
   :current-params params
   :event-template event-template})

(defn- rendered
  "What the editor shows for params, mounted on a context of its own:
   {:swatch [r g b] (0-255) :labels [\"R: …\" \"G: …\" \"B: …\"] :picker
   [r g b] (0.0-1.0)}."
  [params]
  (h/mounted (domains/build-initial-state) h/unbounded (desc params) []
             (fn [{:keys [root]}]
               (let [^Region swatch (h/node-by-id root "set-color-swatch")
                     [_ & rgb] (re-find #"rgb\((\d+),(\d+),(\d+)\)" (.getStyle swatch))
                     ^Color c (.getValue ^ColorPicker (h/node-by-id root "set-color-picker"))]
                 {:swatch (mapv parse-long rgb)
                  :labels (into [] (comp (filter #(instance? Label %))
                                         (map #(.getText ^Label %))
                                         (filter #(re-find #"^[RGB]: " %)))
                                (h/nodes root))
                  :picker [(.getRed c) (.getGreen c) (.getBlue c)]}))))

(def ^:private shapes
  "[what params swatch labels]: each shape a channel can hold, and what
   the editor shows for it."
  [["numbers" {:red 1.0 :green 0.5 :blue 0.0} [255 127 0] ["R: 100%" "G: 50%" "B: 0%"]]
   ["nothing there: full" {} [255 255 255] ["R: 100%" "G: 100%" "B: 100%"]]
   ["a modulator that is off: its static value"
    {:red {:type :sine :active? false :min 0.0 :max 1.0 :period 1.0 :value 0.25} :green 1.0 :blue 1.0}
    [63 255 255] ["R: 25%" "G: 100%" "B: 100%"]]
   ["one that is off with no static value yet: the middle of its range"
    {:red 1.0 :green {:type :triangle :active? false :min 0.2 :max 0.6 :period 1.0} :blue 1.0}
    [255 102 255] ["R: 100%" "G: 40%" "B: 100%"]]
   ["one that is on: the middle of its range, marked"
    {:red 1.0 :green 1.0 :blue {:type :sine :active? true :min 0.0 :max 0.5 :period 1.0 :value 0.9}}
    [255 255 63] ["R: 100%" "G: 100%" "B: 25% ∿"]]
   ["one saved before :active? existed, which is on"
    {:red {:type :sine :min 0.0 :max 1.0 :period 1.0} :green 0.0 :blue 0.0}
    [127 0 0] ["R: 50% ∿" "G: 0%" "B: 0%"]]
   ["out of range: clamped" {:red 2.0 :green -1.0 :blue 0.5} [255 0 127] ["R: 100%" "G: 0%" "B: 50%"]]
   ["not a colour: full" {:red "x" :green nil :blue ##NaN} [255 255 255] ["R: 100%" "G: 100%" "B: 100%"]]])

(deftest swatch-shows-each-channel-shape-test
  (testing "its description, without throwing"
    (doseq [[what params] shapes]
      (is (map? (custom-renderers/set-color-picker-visual-editor {:current-params params :event-template event-template}))
          what)))
  (doseq [[what params swatch labels] shapes]
    (testing (str "rendered, " what)
      (let [shown (rendered params)]
        (is (= swatch (:swatch shown)) "the swatch")
        (is (= labels (:labels shown)) "its labels")
        (is (= swatch (mapv #(int (* 255.0 %)) (:picker shown))) "the picker shows the same colour")))))
