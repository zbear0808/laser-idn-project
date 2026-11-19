(ns laser-show.ui-test.trigger-editor-test
  "Cell trigger editor dialog: opening and closing, learning an input and
   setting its behaviour."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.input.router :as input-router]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene.control ComboBox Labeled]
           [javafx.scene.control.skin ComboBoxListViewSkin]
           [javafx.scene.layout BackgroundFill Region VBox]
           [javafx.scene.paint Color]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(def ^:private follow "Follow the cue's mode (toggle or retrigger)")
(def ^:private while-held "While held (momentary)")

(defn- open-trigger-editor! [col row cell-mode]
  (events/dispatch! {:event/type :ui/open-dialog
                     :dialog-id :trigger-editor
                     :data {:col col :row row :cell-mode cell-mode}})
  (ui/wait-for! #(true? (get-in (state/get-raw-state)
                                [:ui :dialogs :trigger-editor :open?])))
  (ui/wait-for! #(some (fn [title]
                         (and title (.startsWith ^String title "Triggers")))
                       (ui/window-titles))))

(defn- behavior-shown
  "The text the behaviour selector shows (on its button)."
  []
  (let [^ComboBox combo (ui/query "#trigger-behavior-selector")]
    (ui/on-fx-thread #(some-> ^ComboBoxListViewSkin (.getSkin combo) ^Labeled (.getDisplayNode) .getText))))

(defn- behavior-choices
  "The names of the behaviour selector's choices."
  []
  (let [^ComboBox combo (ui/query "#trigger-behavior-selector")]
    (ui/on-fx-thread #(mapv :name (.getItems combo)))))

(defn- space-press! []
  (input-router/dispatch! {:type :trigger
                           :source :keyboard
                           :id :space
                           :state :pressed
                           :timestamp (System/currentTimeMillis)}))

(deftest cell-trigger-editor-opens-and-closes-modal
  ;; Open the modal through the same event used by production controls.
  (events/dispatch! {:event/type :ui/open-dialog
                     :dialog-id :trigger-editor
                     :data {:col 0 :row 0 :cell-mode :cue}})
  (ui/wait-for! #(true? (get-in (state/get-raw-state)
                                [:ui :dialogs :trigger-editor :open?])))
  (ui/wait-for! #(some (fn [title]
                         (and title (.startsWith ^String title "Triggers")))
                       (ui/window-titles)))
  (ui/assert-visible! "#trigger-editor-dialog")
  (ui/assert-visible! "#trigger-editor-done")
  (ui/assert-text! "#trigger-editor-done" "Close")
  (is (some (fn [title]
              (and title (.startsWith ^String title "Triggers")))
            (ui/window-titles)))
  (is (some #{"Triggers — Cue Cell A1"} (ui/window-titles)) "the cell named as everywhere else")
  (ui/click! (ui/robot) "#trigger-editor-done")
  (ui/wait-for! #(false? (get-in (state/get-raw-state)
                                 [:ui :dialogs :trigger-editor :open?])))
  (ui/assert-no-node! "#trigger-editor-dialog"))

(defn- fill-hex
  "A region's background colour as #RRGGBB, with its CSS applied."
  [^Region region]
  (ui/on-fx-thread
   #(do (.applyCss region)
        (when-let [^Color c (some-> region .getBackground .getFills ^BackgroundFill first .getFill)]
          (let [byte (fn [v] (Math/round (* 255.0 (double v))))]
            (format "#%02X%02X%02X" (byte (.getRed c)) (byte (.getGreen c)) (byte (.getBlue c))))))))

(deftest trigger-editor-sections-are-spaced-test
  (state/swap-state! assoc-in [:config :input :trigger-map {:source :keyboard :id :q}]
                     {:type :trigger-cue :target [0 0] :behavior :follow})
  (open-trigger-editor! 0 0 :cue)
  (ui/assert-visible! "#trigger-editor-done")
  (let [^VBox dialog (first (ui/query-all "#trigger-editor-dialog"))
        sections (ui/on-fx-thread #(vec (.getChildren dialog)))
        gaps (fn [] (map (fn [above below] (- (second (ui/scene-bounds below)) (nth (ui/scene-bounds above) 3)))
                         sections (rest sections)))]
    (is (= 3 (count sections)) "the header, the trigger bank and the footer")
    (is (= 12.0 (ui/on-fx-thread #(do (.applyCss dialog) (.getSpacing dialog)))))
    (ui/wait-for! #(every? (fn [gap] (<= 11.5 gap)) (gaps)))
    (is (every? #(<= 11.5 % 12.5) (gaps)) "12 px between them, not touching")
    (is (= "#121212" (fill-hex dialog)) "the dialog's background, as before")
    (testing "with a binding, it still fits the window under a macOS title bar (28 px)"
      (let [window-height (ui/on-fx-thread #(.getHeight (.getWindow (.getScene dialog))))]
        (is (<= (ui/on-fx-thread #(.prefHeight dialog 420.0)) (- window-height 28)))))))

(deftest trigger-editor-learns-input-and-configures-behavior
  (let [robot (ui/robot)
        target {:type :trigger-cue :target [0 0]}]
    (events/dispatch! {:event/type :ui/open-dialog
                       :dialog-id :trigger-editor
                       :data {:col 0 :row 0 :cell-mode :cue}})
    (ui/wait-for! #(true? (get-in (state/get-raw-state)
                                  [:ui :dialogs :trigger-editor :open?])))
    (ui/wait-for! #(some (fn [title]
                           (and title (.startsWith ^String title "Triggers")))
                         (ui/window-titles)))
    (ui/assert-visible! "#trigger-learn-button")
    (ui/click! robot "#trigger-learn-button")
    (ui/assert-visible! "#trigger-learning-panel")
    (input-router/dispatch! {:type :trigger
                              :source :keyboard
                              :id :space
                              :state :pressed
                              :timestamp (System/currentTimeMillis)})
    (ui/wait-for! #(some? (get-in (state/get-raw-state)
                                  [:config :input :trigger-map
                                   {:source :keyboard :id :space}])))
    (is (= target
           (select-keys (get-in (state/get-raw-state)
                                [:config :input :trigger-map
                                 {:source :keyboard :id :space}])
                        [:type :target])))
    (ui/assert-visible! "#trigger-behavior-selector")
    (ui/select! robot "#trigger-behavior-selector" while-held)
    (ui/wait-for! #(= :momentary
                      (get-in (state/get-raw-state)
                              [:config :input :trigger-map
                               {:source :keyboard :id :space} :behavior])))
    (let [remove-button (first (ui/query-all "#trigger-mapped-list .button-danger"))]
      (ui/click-node! robot remove-button))
    (ui/wait-for! #(nil? (get-in (state/get-raw-state)
                                 [:config :input :trigger-map
                                  {:source :keyboard :id :space}])))
    (is (nil? (get-in (state/get-raw-state)
                      [:config :input :trigger-map
                       {:source :keyboard :id :space}])))))

(deftest behavior-shows-plain-names-test
  (open-trigger-editor! 0 0 :cue)
  (ui/assert-visible! "#trigger-behavior-selector")
  (is (= [follow while-held] (behavior-choices)))
  (ui/wait-for! #(= follow (behavior-shown)))
  (ui/assert-visible! "#trigger-behavior-description")
  (ui/assert-no-node! "#trigger-decay-beats")
  (testing "a binding with an old behaviour shows as following the cue, as it plays"
    (state/swap-state! assoc-in [:config :input :trigger-map {:source :keyboard :id :q}]
                       {:type :trigger-cue :target [0 0] :behavior :quantized-decay})
    (ui/assert-visible! "#trigger-mapped-list")
    (is (= follow (behavior-shown))))
  (testing "an effect cell has no cue mode to follow: a press toggles it"
    (open-trigger-editor! 0 0 :effects)
    (ui/wait-for! #(= ["Toggle on each press" while-held] (behavior-choices)))
    (ui/wait-for! #(= "Toggle on each press" (behavior-shown)))
    (is (= "Toggle on each press" (behavior-shown)))))

(deftest behavior-chosen-before-learning-sticks-test
  (let [robot (ui/robot)
        space {:source :keyboard :id :space}
        binding #(get-in (state/get-raw-state) [:config :input :trigger-map space])]
    (open-trigger-editor! 0 0 :cue)
    (ui/select! robot "#trigger-behavior-selector" while-held)
    (ui/wait-for! #(= :momentary (get-in (state/get-raw-state) [:ui :dialogs :trigger-editor :behavior])))
    (ui/wait-for! #(= while-held (behavior-shown)))
    (ui/click! robot "#trigger-learn-button")
    (ui/assert-visible! "#trigger-learning-panel")
    (space-press!)
    (ui/wait-for! #(some? (binding)))
    (is (= {:type :trigger-cue :target [0 0] :behavior :momentary} (binding)))
    (ui/wait-for! #(= while-held (behavior-shown)))
    (is (= while-held (behavior-shown)))))
