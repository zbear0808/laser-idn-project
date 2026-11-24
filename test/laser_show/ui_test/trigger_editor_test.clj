(ns laser-show.ui-test.trigger-editor-test
  "Cell trigger editor dialog: opening and closing, learning an input and
   setting its behaviour."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.input :as input]
            [laser-show.input.learn :as learn]
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

(defn- height-wanted-and-had
  "The trigger editor's content: [its preferred height at its scene's width,
   its scene's height]."
  []
  (let [^Region root (ui/query "#trigger-editor-dialog")]
    (ui/on-fx-thread
     #(let [scene (.getScene root)]
        [(.prefHeight root (.getWidth scene)) (.getHeight scene)]))))

(defn- fits-window? []
  (let [[wanted had] (height-wanted-and-had)]
    (<= wanted (+ had 0.5))))

(defn- wait-fits? []
  (try (ui/wait-for! fits-window?) (catch Exception _ false)))

(deftest trigger-editor-sections-are-spaced-test
  (events/write-state! assoc-in [:config :input :trigger-map {:source :keyboard :id :space}]
                       {:type :trigger-cue :target [0 0] :behavior :follow})
  (open-trigger-editor! 0 0 :cue)
  (ui/assert-visible! "#trigger-editor-done")
  (ui/assert-text! "#trigger-mapped-list .trigger-item-label" "Key Space")
  (ui/assert-text! "#trigger-mapped-list .label-secondary" "Overrides built-in shortcut: Play/Stop")
  (let [^VBox dialog (first (ui/query-all "#trigger-editor-dialog"))
        sections (ui/on-fx-thread #(vec (.getChildren dialog)))
        gaps (fn [] (map (fn [above below] (- (second (ui/scene-bounds below)) (nth (ui/scene-bounds above) 3)))
                         sections (rest sections)))]
    (is (= 3 (count sections)) "the header, the trigger bank and the footer")
    (is (= 12.0 (ui/on-fx-thread #(do (.applyCss dialog) (.getSpacing dialog)))))
    (ui/wait-for! #(every? (fn [gap] (<= 11.5 gap)) (gaps)))
    (is (every? #(<= 11.5 % 12.5) (gaps)) "12 px between them, not touching")
    (is (= "#121212" (fill-hex dialog)) "the dialog's background, as before")
    (testing "its window fits it, whatever the platform's fonts and title bar"
      ;; The scene is what the window has inside its title bar on every
      ;; platform (headless has none), so the content must get its
      ;; preferred height there. A window of a fixed height fits one
      ;; platform's fonts and title bar, not every one's: in a 434-px
      ;; window, with one binding, Windows' fonts wanted 408 px at 420
      ;; wide, more than a title bar leaves.
      (is (fits-window?) (str "with a binding: " (height-wanted-and-had)))
      (events/write-state! update-in [:config :input :trigger-map] assoc
                           {:source :osc :id :r} {:type :trigger-cue :target [0 0] :behavior :follow}
                           {:source :keyboard :id :e} {:type :trigger-cue :target [0 0] :behavior :follow})
      (ui/wait-for! #(= 3 (count (ui/query-all "#trigger-mapped-list .button-danger"))))
      (ui/assert-count! "#trigger-mapped-list .label-secondary" 1)
      (is (wait-fits?) (str "with three bindings: " (height-wanted-and-had)))
      (ui/on-fx-thread #(.setStyle dialog "-fx-font-size: 16px"))
      (is (wait-fits?) (str "in a taller font: " (height-wanted-and-had))))))

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
    (events/write-state! assoc-in [:config :input :trigger-map {:source :keyboard :id :q}]
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


;; One learn attempt, the behaviour shown (reactivity plan 24, case 4)

(defn- app-state [] (state/get-raw-state))
(defn- attempt [] (input/active-learn (app-state)))
(defn- undo-count [] (count (get-in (app-state) [:history :undo-stack])))

(defn- key-press! [id]
  (input-router/dispatch! {:type :trigger :source :keyboard :id id :state :pressed
                           :timestamp (System/currentTimeMillis)}))

(defn- with-held-captures
  "(f release!): while f runs, a learn attempt's waiter that has caught an
   input (input.learn/wait-for-capture) holds it, at most 5 s, until
   release! is called."
  [f]
  (let [gate (promise)
        wait learn/wait-for-capture]
    (with-redefs [learn/wait-for-capture (fn [g ms]
                                           (let [result (wait g ms)]
                                             (when (map? result)
                                               (deref gate 5000 nil))
                                             result))]
      (try
        (f #(deliver gate true))
        (finally
          (deliver gate true))))))

(deftest behavior-changed-while-waiting-is-the-binding-learned-test
  (let [robot (ui/robot)
        binding #(get-in (app-state) [:config :input :trigger-map {:source :keyboard :id %}])]
    (testing "While held chosen while the learn waits on Follow: the same attempt, and the binding is While held"
      (open-trigger-editor! 0 0 :cue)
      (ui/wait-for! #(= follow (behavior-shown)))
      (ui/click! robot "#trigger-learn-button")
      (ui/assert-visible! "#trigger-learning-panel")
      (ui/wait-for! #(= :follow (get-in (attempt) [:target-action :behavior])))
      (let [waiting (:learn-id (attempt))]
        (ui/wait-for! #(= waiting (input-router/learn-listener-id)))
        (ui/select! robot "#trigger-behavior-selector" while-held)
        (ui/wait-for! #(= :momentary (get-in (attempt) [:target-action :behavior])))
        (ui/wait-for! #(= while-held (behavior-shown)))
        (is (= waiting (:learn-id (attempt))) "the same attempt")
        (is (= waiting (input-router/learn-listener-id)) "with its listener")
        (ui/assert-visible! "#trigger-learning-panel")
        (is (zero? (undo-count)) "the choice alone on a cell with no binding: no undo step")
        (is (false? (get-in (app-state) [:project :dirty?])))
        (space-press!)
        (ui/wait-for! #(some? (binding :space)))
        (is (= {:type :trigger-cue :target [0 0] :behavior :momentary} (binding :space)))
        (ui/wait-for! #(nil? (attempt)))
        (is (= while-held (behavior-shown)) "shown as learned")
        (is (= 1 (undo-count)) "the binding: one step")))
    (testing "captured first, then While held: the existing binding changes, in a step of its own"
      (events/dispatch! {:event/type :ui/open-dialog :dialog-id :trigger-editor
                         :data {:col 1 :row 0 :cell-mode :cue :behavior :follow}})
      (ui/wait-for! #(= [1 0] ((juxt :col :row) (get-in (app-state) [:ui :dialogs :trigger-editor]))))
      (ui/wait-for! #(= follow (behavior-shown)))
      (ui/click! robot "#trigger-learn-button")
      (ui/wait-for! #(= {:type :trigger-cue :target [1 0] :behavior :follow} (:target-action (attempt))))
      (ui/wait-for! #(= (:learn-id (attempt)) (input-router/learn-listener-id)))
      (key-press! :b)
      (ui/wait-for! #(= {:type :trigger-cue :target [1 0] :behavior :follow} (binding :b)))
      (ui/wait-for! #(nil? (attempt)))
      (is (= follow (behavior-shown)))
      (is (= 2 (undo-count)))
      (ui/select! robot "#trigger-behavior-selector" while-held)
      (ui/wait-for! #(= :momentary (:behavior (binding :b))))
      (ui/wait-for! #(= while-held (behavior-shown)))
      (is (= 3 (undo-count)) "the choice changed a binding: a step of its own")
      (is (nil? (attempt))))
    (testing "the editor closed, or on another cell, while its learn waits: the held capture binds nothing"
      (doseq [[cell key-id away!] [[[2 0] :c #(ui/click! robot "#trigger-editor-done")]
                                   [[3 0] :d #(events/dispatch! {:event/type :ui/open-dialog :dialog-id :trigger-editor
                                                                 :data {:col 4 :row 0 :cell-mode :cue}})]]]
        (with-held-captures
          (fn [release!]
            (let [before (get-in (app-state) [:config :input])]
              (open-trigger-editor! (first cell) (second cell) :cue)
              (ui/wait-for! #(= [(first cell) (second cell)] ((juxt :col :row) (get-in (app-state) [:ui :dialogs :trigger-editor]))))
              (ui/click! robot "#trigger-learn-button")
              (ui/wait-for! #(= {:type :trigger-cue :target cell} (input/learning-target (app-state))))
              (ui/wait-for! #(= (:learn-id (attempt)) (input-router/learn-listener-id)))
              (let [held (:learn-id (attempt))]
                (key-press! key-id)
                (away!)
                (ui/wait-for! #(nil? (attempt)))
                (ui/wait-for! #(nil? (input-router/learn-listener-id)))
                (release!)
                (ui/wait-for! #(not (contains? (learn/waiting) held)))
                (is (events/flush!))
                (is (= before (get-in (app-state) [:config :input])) (str "neither cell changes, " cell))
                (is (nil? (binding key-id)))))))))))
