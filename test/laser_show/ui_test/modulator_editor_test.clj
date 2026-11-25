(ns laser-show.ui-test.modulator-editor-test
  "The modulator editor (views/components/modulator_param_control.clj): the
   types that follow a press (Envelope, Step, Energy) in its dropdown, what
   a press source and Restart on are called in it, the Step's palette, and
   none of that for calibration, which never sees a press
   (plans/2026-11-18-vibe-mode/04 §4, invariant 4). And the Fader, which
   follows fader 1 or 2 (05). And a number typed into a parameter's field
   with Enter, while its modulator is off or keyframes set it: a number is
   stored, other text is put back. And the selected keyframe's Value and
   Interp controls: each change edits that keyframe once, and Undo and Redo
   show in them. And a modulator's fields: two edits from one render both
   stay, and what is typed into one is stored in it alone (reactivity plan
   07). And the sliders' drags, each one press, one undo step (reactivity
   plan 16). And ∿, queued behind a reorder, turns on the effect it was
   clicked for (follow-up B6)."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.animation.modulator-registry :as reg]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.keyframe :as keyframe]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.state.core :as state]
            [laser-show.state.history :as history]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.components.modulator-param-control :as mod-param]
            [laser-show.views.components.parameter-controls :as param-controls])
  (:import [javafx.event Event]
           [javafx.scene Node Scene]
           [javafx.scene.control CheckBox ComboBox Label Slider Spinner TextField ToggleButton]
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

(deftest modulate-queued-behind-a-reorder-turns-on-its-own-effect-test
  ;; Follow-up B6: the ∿ toggle names its effect by its instance. Clicked for
  ;; A, and queued behind a reorder that puts B in A's place, it turns on
  ;; A's modulation where A is now, and leaves B as it was
  (let [a (random-uuid)
        b (random-uuid)
        intensity (fn [id] {:id id :effect-id :intensity :enabled? true :params {:amount 1.0}})
        cell-items #(get-in (state/get-raw-state) [:chains :effect-chains [0 0] :items])
        amount-of (fn [id] (some #(when (= id (:id %)) (get-in % [:params :amount])) (cell-items)))]
    (events/write-state! #(-> %
                              (assoc-in [:chains :effect-chains [0 0]] {:active? false :items [(intensity a) (intensity b)]})
                              (assoc-in [:list-ui [:effect-chain 0 0]] {:selected-ids #{a} :last-selected-id a})))
    (events/dispatch! {:event/type :effect-chain/open-editor :col 0 :row 0})
    (ui/wait-for! #(some? (node "#modulate-amount")))
    ;; The reorder the chain list sends, then a click on A's ∿, in one FX
    ;; turn: the click's event is the one rendered while A was first
    (ui/on-fx-thread #(do (events/dispatch! {:event/type :chain/set-items :domain :effect-chains :entity-key [0 0]
                                             :items [(intensity b) (intensity a)]})
                          (.fire ^ToggleButton (node "#modulate-amount"))))
    (is (ui/wait-for! #(reg/active-modulator? (amount-of a))) "A's Amount is modulated, A now second")
    (is (= [b a] (mapv :id (cell-items))))
    (is (= 1.0 (amount-of b)) "B's, in A's old place, stays")
    (is (ui/wait-for! #(ui/selected? "#modulate-amount")) "and A's editor shows it on")))

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


;; A number typed into a parameter's field (Enter)


(def ^:private amount-field "#param-amount-field")
(def ^:private amount-slider "#param-amount-slider")

(defn- open-effect-editor!
  "Effects cell [0 0] with effect as its one effect, in the effect chain
   editor (it is selected as it opens), once the Amount field shows."
  [effect]
  (events/write-state! assoc-in [:chains :effect-chains [0 0]] {:active? false :items [effect]})
  (events/dispatch! {:event/type :effect-chain/open-editor :col 0 :row 0})
  (ui/wait-for! #(some? (node amount-field))))

(defn- field-text
  "What the Amount field shows now."
  []
  (let [^TextField field (node amount-field)]
    (ui/on-fx-thread #(.getText field))))

(defn- enter!
  "Type text into the Amount field and press Enter."
  [robot text]
  (ui/fill! robot amount-field text)
  (ui/submit! robot amount-field))

(defn- refuses-bad-text!
  "Enter each text that isn't a number: the field shows shown again, and
   (stored) is still what it was."
  [robot shown stored]
  (let [before (stored)]
    (doseq [text ["abc" "" "NaN" "Infinity"]]
      (enter! robot text)
      (is (ui/wait-for! #(= shown (field-text))) (str (pr-str text) " is put back"))
      (events/flush!)
      (is (= before (stored)) (str (pr-str text) " changes nothing")))))

(deftest inactive-modulator-field-stores-a-typed-number-test
  (let [robot (ui/robot)
        config {:type :sine :active? false :value 0.2 :min 0.0 :max 1.0 :period 1.0}]
    (open-effect-editor! {:id (random-uuid) :effect-id :intensity :enabled? true :params {:amount config}})
    (is (= "0.20" (field-text)))
    (enter! robot "0.42")
    (is (ui/wait-for! #(and (= (assoc config :value 0.42) (amount)) (= "0.42" (field-text))))
        "Enter stores the number in the modulator that is off, which keeps its settings")
    (enter! robot "9")
    (is (ui/wait-for! #(and (= (assoc config :value 3.0) (amount)) (= "3.00" (field-text))))
        "clamped to Amount's Max")
    (enter! robot "12")
    (is (ui/wait-for! #(= "3.00" (field-text))) "the Max again: no change to show, so the field shows it again")
    (refuses-bad-text! robot "3.00" amount)
    (testing "one Undo takes back one typed number"
      (events/dispatch! {:event/type :edit/undo})
      (is (ui/wait-for! #(and (= (assoc config :value 0.42) (amount)) (= "0.42" (field-text))))))
    (testing "the slider still sets it"
      (ui/slide! robot amount-slider 0.5)
      (is (ui/wait-for! #(and (= (assoc config :value 0.5) (amount)) (= "0.50" (field-text))))))))

(deftest keyframe-field-stores-a-typed-number-test
  (let [robot (ui/robot)
        effect (fn [] (get-in (state/get-raw-state) [:chains :effect-chains [0 0] :items 0]))
        frames (fn [] (mapv (comp :amount :params) (get-in (effect) [:keyframe-modulator :keyframes])))]
    (open-effect-editor! {:id (random-uuid) :effect-id :intensity :enabled? true :params {:amount 1.0}
                          :keyframe-modulator {:enabled? true :driver :time :period 4.0 :time-unit :beats
                                               :loop-mode :loop :selected-keyframe 1
                                               :keyframes [{:position 0.0 :params {:amount 0.3}}
                                                           {:position 1.0 :params {:amount 0.9}}]}})
    (is (= "0.90" (field-text)) "the selected keyframe's Amount")
    (enter! robot "0.42")
    (is (ui/wait-for! #(and (= [0.3 0.42] (frames)) (= "0.42" (field-text))))
        "Enter stores the number in the selected keyframe")
    (is (= 1.0 (amount)) "the effect's own Amount stays")
    (enter! robot "-2")
    (is (ui/wait-for! #(and (= [0.3 0.0] (frames)) (= "0.00" (field-text)))) "clamped to Amount's Min")
    (refuses-bad-text! robot "0.00" frames)
    (testing "the slider still sets it"
      (ui/slide! robot amount-slider 1.5)
      (is (ui/wait-for! #(and (= [0.3 1.5] (frames)) (= "1.50" (field-text))))))))


;; The selected keyframe's Value and Interp (reactivity plan 06)


(def ^:private kf-actions
  "The keyframe panel's actions row: the selected keyframe's Value and
   Interp, apart from Period, the driver and the Zone Selector's own zone."
  ".keyframe-panel-actions")

(defn- in-actions [selector] (str kf-actions " " selector))

(defn- open-keyframed!
  "Effects cell [0 0] with effect as its one effect, saved and with no
   history yet, in the effect chain editor once its keyframe actions row
   shows."
  [effect]
  (events/write-state! #(-> %
                            (assoc-in [:chains :effect-chains [0 0]] {:active? false :items [effect]})
                            (update :history history/clear-history)
                            history/mark-clean))
  (events/dispatch! {:event/type :effect-chain/open-editor :col 0 :row 0})
  (ui/wait-for! #(some? (node kf-actions))))

(defn- cell-effect [] (get-in (state/get-raw-state) [:chains :effect-chains [0 0] :items 0]))
(defn- frames [] (get-in (cell-effect) [:keyframe-modulator :keyframes]))
(defn- undo-depth [] (count (get-in (state/get-raw-state) [:history :undo-stack])))
(defn- dirty? [] (get-in (state/get-raw-state) [:project :dirty?]))

(defn- selection-text
  "What the actions row says is selected."
  []
  (let [^Label label (node (in-actions ".label-secondary"))]
    (ui/on-fx-thread #(.getText label))))

(defn- spinner-value [selector]
  (let [^Spinner spinner (node selector)]
    (ui/on-fx-thread #(.getValue spinner))))

(defn- text-of [selector]
  (let [^TextField field (node selector)]
    (ui/on-fx-thread #(.getText field))))

(defn- slider-value [selector]
  (let [^Slider slider (node selector)]
    (ui/on-fx-thread #(.getValue slider))))

(defn- with-keyframe-edits
  "(f edits) while keyframe/handle also records each Value, zone or Interp
   edit it is given, without its co-effects, into the atom edits."
  [f]
  (let [handle keyframe/handle
        edits (atom [])]
    (with-redefs [keyframe/handle (fn [event]
                                    (when (#{:keyframe/update-param :keyframe/set-zone-value :keyframe/set-interpolation}
                                           (:event/type event))
                                      (swap! edits conj (dissoc event :state :time)))
                                    (handle event))]
      (f edits))))

(defn- address
  "Where a recorded edit is aimed: Effects cell [0 0]'s effect, a keyframe."
  [edit]
  (select-keys edit [:event/type :domain :entity-key :effect-path :keyframe-idx :param-key]))

(deftest keyframe-zone-value-commits-id-once-test
  (with-keyframe-edits
    (fn [edits]
      (let [zone (in-actions "#keyframe-zone-value")
            zones #(mapv (comp :target-zone :params) (frames))]
        (open-keyframed! {:id (random-uuid) :effect-id :zone-selector :enabled? true :params {:target-zone :all}
                          :keyframe-modulator {:enabled? true :driver :time :period 4.0 :selected-keyframe 1
                                               :keyframes [{:position 0.0 :params {:target-zone :auto/right} :interpolation :step}
                                                           {:position 0.5 :params {:target-zone :all} :interpolation :step}
                                                           {:position 1.0 :params {:target-zone :auto/odd} :interpolation :step}]}})
        (is (ui/wait-for! #(= "All" (shown zone))))
        (choose! zone (first (filter #(= :auto/left (:id %)) (items zone))))
        (is (ui/wait-for! #(and (= [:auto/right :auto/left :auto/odd] (zones)) (= "Left" (shown zone))))
            "Left's id, in the selected keyframe")
        (is (= :all (get-in (cell-effect) [:params :target-zone])) "the effect's own zone stays")
        (is (= [:step :step :step] (mapv :interpolation (frames))))
        (is (nil? (node (in-actions "#keyframe-interp"))) "a zone has no Interp")
        (is (= 1 (undo-depth)))
        (testing "Undo once, then Redo"
          (events/dispatch! {:event/type :edit/undo})
          (is (ui/wait-for! #(and (= [:auto/right :all :auto/odd] (zones)) (false? (dirty?)) (= "All" (shown zone)))))
          (events/dispatch! {:event/type :edit/redo})
          (is (ui/wait-for! #(and (= [:auto/right :auto/left :auto/odd] (zones)) (= "Left" (shown zone))
                                  (= "Selected: Keyframe 2 @ 50%" (selection-text))))))
        (events/flush!)
        (is (= [{:event/type :keyframe/set-zone-value :domain :effect-chains :entity-key [0 0] :effect-path [0]
                 :keyframe-idx 1 :param-key :target-zone}]
               (mapv address @edits))
            "one edit for the choice, none for mounting, Undo or Redo")
        (is (= :auto/left (:id (:fx/event (first @edits)))))))))

(deftest keyframe-value-spinner-follows-selected-frame-test
  (doseq [{:keys [what effect param before after field]}
          [{:what "a :float: Intensity's Amount"
            :effect {:id (random-uuid) :effect-id :intensity :enabled? true :params {:amount 1.0}
                     :keyframe-modulator {:enabled? true :driver :time :period 4.0 :time-unit :beats :loop-mode :loop
                                          :selected-keyframe 0
                                          :keyframes [{:position 0.0 :params {:amount 0.2} :interpolation :linear}
                                                      {:position 0.5 :params {:amount 0.5} :interpolation :linear}
                                                      {:position 1.0 :params {:amount 0.9} :interpolation :linear}]}}
            :param :amount :before 0.5 :after 0.6 :field "0.60"}
           {:what "an :int: Decimate's scan rate steps to 26, never 25.1"
            :effect {:id (random-uuid) :effect-id :decimate :enabled? true :params {:kpps 25 :fps 30}
                     :keyframe-modulator {:enabled? true :driver :time :period 4.0 :time-unit :beats :loop-mode :loop
                                          :selected-keyframe 0
                                          :keyframes [{:position 0.0 :params {:kpps 20 :fps 30} :interpolation :linear}
                                                      {:position 0.5 :params {:kpps 25 :fps 30} :interpolation :linear}
                                                      {:position 1.0 :params {:kpps 40 :fps 30} :interpolation :linear}]}}
            :param :kpps :before 25 :after 26 :field "26"}]]
    (testing what
      (with-keyframe-edits
        (fn [edits]
          (let [spinner (in-actions "#keyframe-value")
                values #(mapv (comp param :params) (frames))
                [first-value _ last-value] (mapv (comp param :params) (get-in effect [:keyframe-modulator :keyframes]))]
            (open-keyframed! effect)
            (is (ui/wait-for! #(= "Selected: Keyframe 1 @ 0%" (selection-text))))
            (testing "the middle keyframe, selected on the timeline"
              (ui/click! (ui/robot) ".keyframe-panel-timeline Canvas")
              (is (ui/wait-for! #(and (= "Selected: Keyframe 2 @ 50%" (selection-text)) (= before (spinner-value spinner)))))
              (events/flush!)
              (is (zero? (undo-depth)) "selecting adds no history"))
            (let [^Spinner s (node spinner)]
              (ui/on-fx-thread #(.increment s)))
            (is (ui/wait-for! #(and (= [first-value after last-value] (values)) (= after (spinner-value spinner))
                                    (= field (text-of (str "#param-" (name param) "-field")))))
                "one step: the keyframe, and the parameter's own field")
            (is (= (integer? after) (integer? (get-in (frames) [1 :params param]))) "an :int stays whole")
            (is (== after (slider-value (str "#param-" (name param) "-slider"))) "and its slider")
            (is (= (:params effect) (:params (cell-effect))) "the effect's own values stay")
            (is (= 4.0 (get-in (cell-effect) [:keyframe-modulator :period])) "and Period")
            (is (= 1 (undo-depth)))
            (testing "Undo once, then Redo"
              (events/dispatch! {:event/type :edit/undo})
              (is (ui/wait-for! #(and (= [first-value before last-value] (values)) (= before (spinner-value spinner))
                                      (false? (dirty?)))))
              (events/dispatch! {:event/type :edit/redo})
              (is (ui/wait-for! #(and (= [first-value after last-value] (values)) (= after (spinner-value spinner))
                                      (= "Selected: Keyframe 2 @ 50%" (selection-text))))))
            (events/flush!)
            (is (= [{:event/type :keyframe/update-param :domain :effect-chains :entity-key [0 0] :effect-path [0]
                     :keyframe-idx 1 :param-key param}]
                   (mapv address @edits))
                "one edit for the step, none for mounting, selecting, Undo or Redo")
            (is (= after (:value (first @edits))))))))))

(deftest keyframe-interp-choice-refreshes-on-undo-test
  (with-keyframe-edits
    (fn [edits]
      (let [interp (in-actions "#keyframe-interp")
            modes #(mapv :interpolation (frames))]
        (open-keyframed! {:id (random-uuid) :effect-id :intensity :enabled? true :params {:amount 1.0}
                          :keyframe-modulator {:enabled? true :driver :pos-x :edge-behavior :clamp :selected-keyframe 1
                                               :keyframes [{:position 0.0 :params {:amount 0.2} :interpolation :linear}
                                                           {:position 0.5 :params {:amount 0.5} :interpolation :linear}
                                                           {:position 1.0 :params {:amount 0.9} :interpolation :exp-decay}]}})
        (is (ui/wait-for! #(= "Linear" (shown interp))))
        (choose! interp :exp-grow)
        (is (ui/wait-for! #(and (= [:linear :exp-grow :exp-decay] (modes)) (= "Ease In" (shown interp))))
            "only the selected keyframe's mode")
        (is (= [0.2 0.5 0.9] (mapv (comp :amount :params) (frames))) "its values stay")
        (is (= 1 (undo-depth)))
        (testing "Undo once, then Redo"
          (events/dispatch! {:event/type :edit/undo})
          (is (ui/wait-for! #(and (= [:linear :linear :exp-decay] (modes)) (= "Linear" (shown interp)))))
          (events/dispatch! {:event/type :edit/redo})
          (is (ui/wait-for! #(and (= [:linear :exp-grow :exp-decay] (modes)) (= "Ease In" (shown interp))))))
        (events/flush!)
        (is (= [{:event/type :keyframe/set-interpolation :domain :effect-chains :entity-key [0 0] :effect-path [0]
                 :keyframe-idx 1}]
               (mapv #(dissoc (address %) :param-key) @edits))
            "one edit for the choice, none for mounting, Undo or Redo")
        (is (= :exp-grow (:interpolation (first @edits))))
        (testing "a Zone pattern's Style isn't a number: no Value spinner, but Interp"
          (let [pattern (zone-patterns/preset-instance :alternate :cell)]
            (open-keyframed! (assoc pattern :id (random-uuid)
                                    :keyframe-modulator {:enabled? true :driver :time :period 4.0 :selected-keyframe 1
                                                         :keyframes [{:position 0.0 :params (:params pattern)}
                                                                     {:position 0.5 :params (assoc (:params pattern) :style :alternate)}
                                                                     {:position 1.0 :params (assoc (:params pattern) :style :chase)}]}))
            (is (ui/wait-for! #(and (some? (node "#zone-pattern-visual"))
                                    (= "Selected: Keyframe 2 @ 50%" (selection-text)) (some? (node interp)))))
            (is (nil? (node (in-actions "#keyframe-value"))))
            (is (nil? (node (in-actions ".spinner"))))))))))

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

(defn- track-event!
  "One primary-button mouse event on slider's track at fraction of its width,
   as a real mouse sends it (headless, a robot drag loses its button). FX
   thread."
  [^Slider slider event-type fraction]
  (let [^Node track (.lookup slider ".track")
        b (.getLayoutBounds track)
        x (* fraction (.getWidth b))
        y (/ (.getHeight b) 2.0)
        scene (.localToScene track x y)
        screen (.localToScreen track x y)]
    (Event/fireEvent track (MouseEvent. event-type (.getX scene) (.getY scene) (.getX screen) (.getY screen)
                                        MouseButton/PRIMARY 1 false false false false
                                        (not= event-type MouseEvent/MOUSE_RELEASED) false false
                                        false false false nil))))

(defn- fire-track!
  "track-event!, from the test's thread."
  [slider event-type fraction]
  (ui/on-fx-thread #(track-event! slider event-type fraction))
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
    (testing "a modulator's field: each of its edits carries the press's id, so the drag is one step"
      (let [config {:type :sine :active? true :min 0.0 :max 1.0 :period 1.0}
            _ (events/write-state! assoc-in [:chains :effect-chains [0 0] :items 0 :params :amount] config)
            id (get-in (state/get-raw-state) [:chains :effect-chains [0 0] :items 0 :id])
            before (count (undo-labels))
            [stage _] (mount-desc! {:fx/type mod-param/modulator-param-control
                                    :param-key :amount :param-spec spec :current-value config
                                    :on-change-event {:event/type :chain/update-param :domain :effect-chains :entity-key [0 0] :effect-path [0]}
                                    :on-text-event {:event/type :chain/update-param-from-text :domain :effect-chains :entity-key [0 0] :effect-path [0]}
                                    :modulator-event-base {:domain :effect-chains :entity-key [0 0] :effect-path [0]
                                                           :effect-instance-id id}})]
        (try
          (let [period (ui/on-fx-thread #(.lookup (.getRoot (.getScene stage)) "#modulator-amount-period"))]
            (drag-track! period [0.2 0.5 0.7])
            (fire-track! period MouseEvent/MOUSE_RELEASED 0.7)
            (ui/wait-for! #(settled? (inc before)))
            (is (not= 1.0 (:period (amount-of [0 0]))) "the period moved")
            (is (= "Adjust Period" (peek (undo-labels))) "one step for the whole drag"))
          (finally (ui/on-fx-thread #(.close stage))))))))


;; Two edits to a modulator's fields from one render (reactivity plan 07)


(deftest min-and-max-from-one-render-both-stick-test
  (let [config {:type :triangle :active? true :period 7.0 :min 0.2 :max 0.8}
        lo "#modulator-amount-min"
        hi "#modulator-amount-max"]
    (open-amount-editor! config)
    (ui/wait-for! #(and (some? (node lo)) (some? (node hi))))
    ;; A click (press and release) on each real slider, Min a fifth of the way
    ;; along 0 to 3, Max half way, in one FX turn: both sent from one render,
    ;; before the editor can render again
    (let [min-slider (node lo)
          max-slider (node hi)]
      (ui/on-fx-thread #(doseq [[slider at] [[min-slider 0.2] [max-slider 0.5]]]
                          (track-event! slider MouseEvent/MOUSE_PRESSED at)
                          (track-event! slider MouseEvent/MOUSE_RELEASED at))))
    (is (ui/wait-for! #(let [{lo-value :min hi-value :max} (amount)]
                         (and (< 0.3 lo-value 1.0) (< 1.0 hi-value 2.2)
                              (== lo-value (slider-value lo))
                              (== hi-value (slider-value hi)))))
        "the stored Min and Max are both the new ones (neither back at 0.2 or 0.8), and so are the sliders")
    (is (= (merge config (select-keys (amount) [:min :max])) (amount)) "the rest of the modulator stays")
    (is (= ["Adjust Min" "Adjust Max"] (mapv :description (get-in (state/get-raw-state) [:history :undo-stack])))
        "each click its own press, its own step")))

(deftest modulator-fields-store-what-is-typed-test
  (let [robot (ui/robot)
        config {:type :triangle :active? true :period 7.0 :min 0.2 :max 0.8}
        field "#modulator-amount-min-field"]
    (open-amount-editor! config)
    (ui/wait-for! #(some? (node field)))
    (is (= "0.20" (text-of field)))
    (ui/fill! robot field "0.35")
    (ui/submit! robot field)
    (is (ui/wait-for! #(and (= (assoc config :min 0.35) (amount)) (= "0.35" (text-of field))
                            (== 0.35 (slider-value "#modulator-amount-min"))))
        "Enter stores the typed number, in Min only, and the slider shows it")
    (ui/fill! robot field "abc")
    (ui/submit! robot field)
    (is (ui/wait-for! #(= "0.35" (text-of field))) "text that isn't a number is put back")
    (events/flush!)
    (is (= (assoc config :min 0.35) (amount)) "and changes nothing")
    (testing "a text field: an OSC modulator's path"
      (open-amount-editor! {:type :osc :active? true :path "/control" :min 0.0 :max 1.0})
      (ui/fill! robot "#modulator-amount-path" "/fader/1")
      (ui/submit! robot "#modulator-amount-path")
      (is (ui/wait-for! #(and (= "/fader/1" (:path (amount))) (= "/fader/1" (text-of "#modulator-amount-path"))))))))
