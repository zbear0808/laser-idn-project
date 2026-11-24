(ns laser-show.ui-test.modulator-editor-test
  "The modulator editor (views/components/modulator_param_control.clj): the
   types that follow a press (Envelope, Step, Energy) in its dropdown, what
   a press source and Restart on are called in it, the Step's palette, and
   none of that for calibration, which never sees a press
   (plans/2026-11-18-vibe-mode/04 §4, invariant 4). And the Fader, which
   follows fader 1 or 2 (05). And the sliders' drags, each one press, one
   undo step (reactivity plan 16)."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.components.modulator-param-control :as mod-param]
            [laser-show.views.components.parameter-controls :as param-controls])
  (:import [javafx.event Event]
           [javafx.scene Node Scene]
           [javafx.scene.control CheckBox ComboBox Slider ToggleButton]
           [javafx.scene.input KeyCode KeyEvent MouseButton MouseEvent]
           [javafx.stage Stage]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- amount
  "Effects cell [0 0]'s Intensity amount, as app state has it."
  []
  (get-in (state/get-raw-state) [:chains :effect-chains [0 0] :items 0 :params :amount]))

(defn- open-amount-editor!
  "Effects cell [0 0] with one Intensity effect whose amount is modulator,
   in the effect chain editor (one effect: it is selected as it opens)."
  [modulator]
  (events/write-state! assoc-in [:chains :effect-chains [0 0]]
                       {:active? false
                        :items [{:id (random-uuid) :effect-id :intensity :enabled? true :params {:amount modulator}}]})
  (events/dispatch! {:event/type :effect-chain/open-editor :col 0 :row 0})
  (ui/wait-for! #(= (:type modulator) (:id (some-> ^ComboBox (first (ui/query-all "#modulator-type-amount")) .getValue)))))

(defn- node
  "The first node matching selector in any window (ui/query looks in the
   top one, a combo's popup while it shows)."
  ^Node [selector]
  (first (ui/query-all selector)))

(defn- items [selector]
  (let [^ComboBox combo (node selector)]
    (ui/on-fx-thread #(vec (.getItems combo)))))

(defn- shown
  "What a combo box shows for its value."
  [selector]
  (let [^ComboBox combo (node selector)]
    (ui/on-fx-thread #(some-> combo .getButtonCell .getText))))

(defn- choose!
  "Select value in the combo box selector, as a click on it does."
  [selector value]
  (let [^ComboBox combo (node selector)]
    (ui/on-fx-thread #(.select (.getSelectionModel combo) value)))
  (ui/sync-fx!))

(def ^:private pulse-types #{:envelope :step :energy})


(deftest immediate-undo-of-modulate-restores-keyframe-editor-test
  (let [config {:type :triangle :active? true :period 7.0 :min 0.2 :max 0.8 :value 0.6}
        enable-keyframes ".keyframe-panel-header .check-box"]
    (open-amount-editor! config)
    (ui/click! (ui/robot) ".modulator-toggle-active")
    (ui/wait-for! #(and (= (assoc config :active? false) (amount)) (some? (node ".modulator-toggle"))))
    ;; Queue both real controls before the editor can re-render and hide Modulate.
    (ui/on-fx-thread
     #(let [keyframes (node enable-keyframes)
            modulate (node ".modulator-toggle")]
        (.fire ^CheckBox keyframes)
        (.fire ^ToggleButton modulate)
        (events/dispatch! {:event/type :edit/undo})))
    (is (ui/wait-for! #(and (ui/selected? enable-keyframes) (nil? (node "#modulator-type-amount"))
                            (= (assoc config :active? false) (amount))))
        "Undo returns the editor to keyframes")
    (events/dispatch! {:event/type :edit/redo})
    (is (ui/wait-for! #(and (not (ui/selected? enable-keyframes)) (ui/selected? ".modulator-toggle-active")
                            (= "△ Triangle" (shown "#modulator-type-amount")) (= config (amount))))
        "Redo restores the stored modulator and its controls")))

(deftest pulse-types-are-in-the-dropdown-test
  (open-amount-editor! {:type :sine :active? true :min 0.0 :max 1.0 :period 1.0})
  (let [types (items "#modulator-type-amount")]
    (is (every? (set (map :id types)) [:envelope :step :energy :sine :decay :midi]))
    (is (= pulse-types (set (map :id (filter #(= :pulse (:category %)) types)))))
    (choose! "#modulator-type-amount" (first (filter #(= :envelope (:id %)) types))))
  (ui/wait-for! #(= :envelope (:type (amount))))
  (is (= :hit (:source (amount))) "an Envelope follows HIT until told otherwise")
  (ui/wait-for! #(= "HIT" (shown "#modulator-amount-source")))
  (ui/wait-for! #(= "⋀ Envelope" (shown "#modulator-type-amount"))))

(deftest source-choice-shows-its-labels-test
  (open-amount-editor! {:type :envelope :active? true :source :hit :min 1.0 :max 1.25
                        :attack 0.0 :release 0.5 :curve :exp :hold? false})
  (ui/wait-for! #(= "HIT" (shown "#modulator-amount-source")))
  (is (= [:hit :accent :flash :vibe :any :cue :zone-step] (items "#modulator-amount-source"))
      "zones plan Z5's Zone step key is one more source")
  (choose! "#modulator-amount-source" :vibe)
  (ui/wait-for! #(= :vibe (:source (amount))))
  (ui/wait-for! #(= "Vibe change" (shown "#modulator-amount-source")))
  (choose! "#modulator-amount-source" :any)
  (ui/wait-for! #(= "HIT, ACCENT or FLASH" (shown "#modulator-amount-source")))
  (testing "a wave's Restart on, in the same words"
    (open-amount-editor! {:type :sine :active? true :min 0.0 :max 1.0 :period 1.0})
    (ui/wait-for! #(= "Its cue's start" (shown "#modulator-amount-restart-on")))
    (choose! "#modulator-amount-restart-on" :cue)
    (ui/wait-for! #(= :cue (:restart-on (amount))))
    (ui/wait-for! #(= "Any cue start" (shown "#modulator-amount-restart-on")))))

(deftest step-palette-field-test
  (let [robot (ui/robot)]
    (open-amount-editor! {:type :step :active? true :source :hit :min 0.0 :max 1.0 :steps 4 :values []})
    (ui/wait-for! #(some? (node "#modulator-amount-values")))
    (ui/fill! robot "#modulator-amount-values" "0.2, 0.5 1 x")
    (ui/submit! robot "#modulator-amount-values")
    (ui/wait-for! #(= [0.2 0.5 1.0] (:values (amount))))
    (ui/wait-for! #(= "0.2 0.5 1" (ui/on-fx-thread (fn [] (.getText ^javafx.scene.control.TextField (node "#modulator-amount-values"))))))
    (ui/fill! robot "#modulator-amount-values" "")
    (ui/submit! robot "#modulator-amount-values")
    (ui/wait-for! #(= [] (:values (amount))))))

(defn- mount!
  "The modulator control for an Intensity amount, as an editor of domain
   would show it, in a window of its own; returns the window."
  ^Stage [domain]
  (let [stage (ui/on-fx-thread
               #(let [component (fx/create-component
                                 {:fx/type mod-param/modulator-param-control
                                  :param-key :amount
                                  :param-spec {:key :amount :label "Amount" :type :float :min 0.0 :max 3.0 :default 1.0}
                                  :current-value {:type :sine :active? true :min 0.0 :max 1.0 :period 1.0}
                                  :on-change-event {:event/type :chain/update-param :domain domain :entity-key :p1 :effect-path [0]}
                                  :on-text-event {:event/type :chain/update-param-from-text :domain domain :entity-key :p1 :effect-path [0]}
                                  :modulator-event-base {:domain domain :entity-key :p1 :effect-path [0]}}
                                 {:fx.opt/map-event-handler (fn [_])})]
                  (doto (Stage.)
                    (.setScene (Scene. (fx/instance component) 500 400))
                    (.show))))]
    (ui/wait-for! #(some? (node "#modulator-type-amount")))
    stage))

(deftest calibration-editor-hides-pulse-types-test
  (testing "the projector tab's calibration editor shows no modulators today (param-controls-list), so the
            control is mounted as its editor would show it: domain :projector-effects"
    (let [stage (mount! :projector-effects)]
      (try
        (let [types (set (map :id (items "#modulator-type-amount")))]
          (is (seq types))
          (is (not-any? pulse-types types) "no Envelope, Step or Energy")
          (is (contains? types :sine))
          (is (nil? (node "#modulator-amount-restart-on")) "and no Restart on: calibration never sees a press")
          (is (some? (node "#modulator-amount-period"))))
        (finally (ui/on-fx-thread #(.close stage)))))
    (testing "the same control in an effect chain's editor offers them"
      (let [stage (mount! :effect-chains)]
        (try
          (is (every? (set (map :id (items "#modulator-type-amount"))) pulse-types))
          (is (some? (node "#modulator-amount-restart-on")))
          (finally (ui/on-fx-thread #(.close stage))))))))

(deftest keyframe-restart-on-combo-test
  (testing "a time-driven keyframe animation restarts on a press, chosen in its settings row"
      (events/write-state! assoc-in [:chains :effect-chains [0 0]]
                         {:active? false
                          :items [{:id (random-uuid) :effect-id :intensity :enabled? true :params {:amount 1.0}
                                   :keyframe-modulator {:enabled? true :driver :time :period 2.0 :time-unit :beats
                                                        :loop-mode :once :selected-keyframe 0
                                                        :keyframes [{:position 0.0 :params {:amount 0.0}}
                                                                    {:position 1.0 :params {:amount 1.0}}]}}]})
    (events/dispatch! {:event/type :effect-chain/open-editor :col 0 :row 0})
    (ui/wait-for! #(= "Its cue's start" (shown "#keyframe-restart-on")))
    (choose! "#keyframe-restart-on" :accent)
    (ui/wait-for! #(= :accent (get-in (state/get-raw-state) [:chains :effect-chains [0 0] :items 0 :keyframe-modulator :restart-on])))
    (ui/wait-for! #(= "ACCENT" (shown "#keyframe-restart-on")))))

(deftest fader-type-follows-a-slot-test
  (testing "the Fader (plans/2026-11-18-vibe-mode/05): on any parameter, it follows fader 1 until told otherwise"
    (open-amount-editor! {:type :sine :active? true :min 0.0 :max 1.0 :period 1.0})
    (choose! "#modulator-type-amount" (first (filter #(= :macro (:id %)) (items "#modulator-type-amount"))))
    (ui/wait-for! #(= :macro (:type (amount))))
    (is (= {:slot :slider-1 :default 0.0 :min 0.0 :max 1.0} (select-keys (amount) [:slot :default :min :max])))
    (ui/wait-for! #(= "Fader" (shown "#modulator-type-amount")))
    (ui/wait-for! #(= "1: Intensity" (shown "#modulator-amount-slot")))
    (is (= [:slider-1 :slider-2] (items "#modulator-amount-slot")))
    (choose! "#modulator-amount-slot" :slider-2)
    (ui/wait-for! #(= :slider-2 (:slot (amount))))
    (ui/wait-for! #(= "2: Morph" (shown "#modulator-amount-slot")))))


;; A slider's drag is one press (reactivity plan 16)

(defn- mount-desc!
  "desc, in a window of its own (its map events go nowhere: the sliders send
   theirs through events/dispatch!, to the app). Returns [the window, a fn
   that renders it again with a new desc, as the app's state would]."
  [desc]
  (ui/on-fx-thread
   #(let [opts {:fx.opt/map-event-handler (fn [_])}
          component (atom (fx/create-component desc opts))
          stage (doto (Stage.)
                  (.setScene (Scene. (fx/instance @component) 500 300))
                  (.show))]
      [stage (fn [desc] (ui/on-fx-thread (fn [] (swap! component fx/advance-component desc opts))))])))

(defn- record-dispatches!
  "(f) with every event sent through events/dispatch! recorded in sent too,
   still dispatched."
  [sent f]
  (let [dispatch events/dispatch!]
    (with-redefs [events/dispatch! (fn [e] (swap! sent conj e) (dispatch e))]
      (f))))

(defn- sliders-in [^Stage stage]
  (ui/on-fx-thread #(vec (.lookupAll (.getRoot (.getScene stage)) ".slider"))))

(defn- fire-track!
  "One primary-button mouse event on slider's track at fraction of its width,
   as a real mouse sends it (headless, a robot drag loses its button)."
  [^Slider slider event-type fraction]
  (ui/on-fx-thread
   #(let [^Node track (.lookup slider ".track")
          b (.getLayoutBounds track)
          x (* fraction (.getWidth b))
          y (/ (.getHeight b) 2.0)
          scene (.localToScene track x y)
          screen (.localToScreen track x y)]
      (Event/fireEvent track (MouseEvent. event-type (.getX scene) (.getY scene) (.getX screen) (.getY screen)
                                          MouseButton/PRIMARY 1 false false false false
                                          (not= event-type MouseEvent/MOUSE_RELEASED) false false
                                          false false false nil))))
  (ui/sync-fx!))

(defn- drag-track! [slider fractions]
  (fire-track! slider MouseEvent/MOUSE_PRESSED (first fractions))
  (doseq [f (rest fractions)] (fire-track! slider MouseEvent/MOUSE_DRAGGED f)))

(defn- undo-labels [] (mapv :description (get-in (state/get-raw-state) [:history :undo-stack])))

(defn- settled? [n] (and (nil? (get-in (state/get-raw-state) [:history :transaction])) (= n (count (undo-labels)))))

(defn- open-press [] (get-in (state/get-raw-state) [:history :transaction :gesture-id]))

(deftest continuous-sliders-own-their-value-events-test
  (let [spec {:key :amount :label "Amount" :type :float :min 0.0 :max 3.0 :default 1.0}
        amount-of (fn [cell] (get-in (state/get-raw-state) [:chains :effect-chains cell :items 0 :params :amount]))
        slider (fn [cell & {:keys [param-key value] :or {param-key :amount value 1.0}}]
                 {:fx/type param-controls/param-slider
                  :param-key param-key :param-spec (assoc spec :key param-key :label (str/capitalize (name param-key)))
                  :current-value value
                  :on-change-event {:event/type :chain/update-param :domain :effect-chains :entity-key cell :effect-path [0]}
                  :on-text-event {:event/type :chain/update-param-from-text :domain :effect-chains :entity-key cell :effect-path [0]}})
        two (fn [& a-opts] {:fx/type :v-box :children [(apply slider [0 0] a-opts) (slider [1 0])]})]
    (events/write-state! #(-> %
                              (assoc-in [:chains :effect-chains [0 0]] {:active? false :items [{:id (random-uuid) :effect-id :intensity :enabled? true :params {:amount 1.0}}]})
                              (assoc-in [:chains :effect-chains [1 0]] {:active? false :items [{:id (random-uuid) :effect-id :intensity :enabled? true :params {:amount 1.0}}]})))
    (testing "two sliders with the same parameter key, on two cells"
      (let [[stage render!] (mount-desc! (two))]
        (try
          (let [[a b] (sliders-in stage)]
            (testing "a press, its drag and its release: one step, from its first value to its last"
              (drag-track! a [0.2 0.4 0.6])
              (fire-track! a MouseEvent/MOUSE_RELEASED 0.6)
              (ui/wait-for! #(settled? 1))
              (is (< 1.5 (amount-of [0 0]) 2.1) "the last value: 0.6 of 0 to 3")
              (is (= ["Adjust Amount"] (undo-labels))))
            (testing "a key after the release is a step of its own"
              (ui/on-fx-thread #(Event/fireEvent a (KeyEvent. KeyEvent/KEY_PRESSED "" "" KeyCode/LEFT false false false false)))
              (ui/wait-for! #(settled? 2))
              (is (= ["Adjust Amount" "Update param"] (undo-labels))))
            (testing "a press never released, then a press on the other slider: each its own step"
              (drag-track! a [0.9 0.8])
              (ui/wait-for! #(some? (get-in (state/get-raw-state) [:history :transaction])))
              (drag-track! b [0.1 0.3])
              (fire-track! b MouseEvent/MOUSE_RELEASED 0.3)
              (ui/wait-for! #(settled? 4))
              (is (= ["Adjust Amount" "Update param" "Adjust Amount" "Adjust Amount"] (undo-labels)))
              (is (< 2.2 (amount-of [0 0])) "the first slider's last value stays")
              (is (< 0.6 (amount-of [1 0]) 1.0) "the second's own"))
            (testing "the first slider's release, late, ends nothing"
              (fire-track! a MouseEvent/MOUSE_RELEASED 0.8)
              (events/flush!)
              (is (settled? 4)))
            (testing "Undo takes the presses back one at a time"
              (events/dispatch! {:event/type :edit/undo})
              (ui/wait-for! #(= 1.0 (amount-of [1 0])))
              (is (< 2.2 (amount-of [0 0]))))
            (testing "the model's value coming back mid-press is no edit; the press's own last value stays"
              (let [sent (atom [])]
                (record-dispatches!
                 sent
                 #(do
                    (drag-track! a [0.5])
                    (ui/wait-for! (fn [] (some? (open-press))))
                    (reset! sent [])
                    (render! (two :value 2.9))
                    (ui/wait-for! (fn [] (ui/on-fx-thread (fn [] (= 2.9 (.getValue ^Slider a))))))
                    (is (= [] @sent) "the slider showed it, and sent nothing")
                    (fire-track! a MouseEvent/MOUSE_DRAGGED 0.4)
                    (fire-track! a MouseEvent/MOUSE_RELEASED 0.4)))
                (ui/wait-for! #(settled? 4))
                (is (< 0.8 (amount-of [0 0]) 1.5) "0.4 of 0 to 3, not the 2.9 that came back")))
            (testing "rendered for another control mid-press: the press ends, the rest of its drag sends nothing"
              (let [sent (atom [])]
                (record-dispatches!
                 sent
                 #(do
                    (drag-track! a [0.1])
                    (ui/wait-for! (fn [] (some? (open-press))))
                    (reset! sent [])
                    (render! (two :param-key :size :value 2.9))
                    (fire-track! a MouseEvent/MOUSE_DRAGGED 0.7)
                    (fire-track! a MouseEvent/MOUSE_RELEASED 0.7)))
                (events/flush!)
                (is (= [:history/commit-transaction] (mapv :event/type @sent)) "its end, and nothing more")
                (ui/wait-for! #(settled? 5))
                (is (= "Adjust Amount" (peek (undo-labels))))
                (is (< 0.1 (amount-of [0 0]) 0.6) "what it sent before stays")
                (is (nil? (get-in (state/get-raw-state) [:chains :effect-chains [0 0] :items 0 :params :size]))
                    "the control it stands for now got nothing"))))
          (finally (ui/on-fx-thread #(.close stage))))))
    (testing "a modulator's setting: its writer, :chain/update-param, is the press's too"
      (let [config {:type :sine :active? true :min 0.0 :max 1.0 :period 1.0}
            _ (events/write-state! assoc-in [:chains :effect-chains [0 0] :items 0 :params :amount] config)
            before (count (undo-labels))
            [stage _] (mount-desc! {:fx/type mod-param/modulator-param-control
                                    :param-key :amount :param-spec spec :current-value config
                                    :on-change-event {:event/type :chain/update-param :domain :effect-chains :entity-key [0 0] :effect-path [0]}
                                    :on-text-event {:event/type :chain/update-param-from-text :domain :effect-chains :entity-key [0 0] :effect-path [0]}
                                    :modulator-event-base {:domain :effect-chains :entity-key [0 0] :effect-path [0]}})]
        (try
          (let [period (ui/on-fx-thread #(.lookup (.getRoot (.getScene stage)) "#modulator-amount-period"))]
            (drag-track! period [0.2 0.5 0.7])
            (fire-track! period MouseEvent/MOUSE_RELEASED 0.7)
            (ui/wait-for! #(settled? (inc before)))
            (is (not= 1.0 (:period (amount-of [0 0]))) "the period moved")
            (is (= "Adjust Period" (peek (undo-labels))) "one step for the whole drag"))
          (finally (ui/on-fx-thread #(.close stage))))))))
