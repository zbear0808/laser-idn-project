(ns laser-show.events.modulator-handlers-test
  "Tests for modulator event handlers.
   
   Tests the modulator toggle, type selection, and sub-parameter update
   handlers. A field edit names its effect by its instance and changes that
   field only, of the parameter as it is stored when the edit is handled, so
   two edits from one render both stay; one for an effect that is gone, or
   a parameter of another kind or mode, does nothing (reactivity plan 07).
   The toggle names its effect by its instance too (follow-up B6). And a
   modulator that is off keeps only a number typed into its parameter's
   field, through Save and Open."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.effects.intensity] ; :intensity's :amount is a :float
            [laser-show.animation.effects.zone] ; :zone-pattern's :style is a modulatable :choice
            [laser-show.animation.modulator-registry :as reg]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.modulator :as modulator]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.serialization :as serialization])
  (:import [java.io File]
           [javafx.event ActionEvent]))


;; Test Fixtures


(def sample-param-spec
  "A typical numeric parameter spec."
  {:min 0.0 :max 2.0 :default 1.0 :type :float :label "Test Param"})

(def sample-modulator-config
  "A sample sine modulator config."
  {:type :sine :min 0.5 :max 1.5 :period 1.0 :phase 0.0})

(def ^:private effect-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

(def ^:private field-base
  "What a field edit names: the effect in cell [0 0] by its instance, and
   its parameter."
  {:domain :effect-chains :entity-key [0 0] :effect-instance-id effect-id :param-key :test-param})

(defn- with-param
  "Cell [0 0] whose one effect (effect-id) has value as its :test-param."
  [value]
  {:chains {:effect-chains {[0 0] {:items [{:id effect-id :effect-id :test :params {:test-param value}}]}}}})

(defn- stored
  "Cell [0 0]'s :test-param in state."
  [state]
  (get-in state [:chains :effect-chains [0 0] :items 0 :params :test-param]))

(defn- handled
  "state after each event in turn through modulator/handle, as the event
   thread handles them: each given the state the one before left."
  [state events]
  (reduce (fn [s event] (or (:state (modulator/handle (assoc event :state s))) s)) state events))

(defn- field-edit
  "The field edit of type with more, as its control sends it."
  [type & {:as more}]
  (merge field-base {:event/type type} more))

(defn- edited
  "What :test-param becomes after event on value, or ::nothing when the
   handler changes nothing ({})."
  [value event]
  (let [result (modulator/handle (assoc event :state (with-param value)))]
    (if (= {} result) ::nothing (stored (:state result)))))


;; Toggle Tests


(defn- toggle-effect
  "effect, the one in cell [0 0], after its ∿ for :test-param is clicked to
   enabled? (the toggle names it by its instance: effect-id)."
  [effect enabled?]
  (let [state {:chains {:effect-chains {[0 0] {:items [(assoc effect :id effect-id)]}}}}
        result (modulator/handle (assoc field-base :event/type :modulator/toggle :state state
                                        :param-spec sample-param-spec :fx/event enabled?))]
    (dissoc (get-in (or (:state result) state) [:chains :effect-chains [0 0] :items 0]) :id)))

(deftest test-toggle-static-to-modulated
  (let [effect (toggle-effect {:params {:test-param 1.0}} true)]
    (is (= {:type :sine :min 0.0 :max 2.0 :active? true}
           (select-keys (get-in effect [:params :test-param]) [:type :min :max :active?]))
        "a new modulator uses the parameter's bounds")
    (is (not (contains? effect :keyframe-modulator)))))

(deftest test-toggle-modulated-to-static
  (doseq [[value expected-static] [[sample-modulator-config 1.0]
                                   [(assoc sample-modulator-config :value 0.75) 0.75]]]
    (let [off (toggle-effect {:params {:test-param value}} false)]
      (is (= (assoc value :active? false :value expected-static) (get-in off [:params :test-param]))
          "use the saved static value, or the midpoint on first deactivation")
      (is (= (assoc value :active? true :value expected-static)
             (get-in (toggle-effect off true) [:params :test-param]))))))


;; Set Type Tests


(deftest test-set-modulator-type-from-keyword
  (testing "Setting type from keyword creates correct modulator"
    (is (= :triangle (:type (edited sample-modulator-config
                                    (field-edit :modulator/set-type :param-spec sample-param-spec :fx/event :triangle)))))))

(deftest test-set-modulator-type-from-combo-box-item
  (testing "the dropdown sends the selected type's id"
    (is (= :square (:type (edited sample-modulator-config
                                  (field-edit :modulator/set-type :param-spec sample-param-spec :mod-type :square))))))
  (testing "Setting type from combo-box item map extracts :id"
    (is (= :square (:type (edited sample-modulator-config
                                  (field-edit :modulator/set-type :param-spec sample-param-spec
                                              :fx/event {:id :square :name "Square" :icon "▭"})))))))

(deftest test-set-type-preserves-custom-min-max
  (testing "Changing type preserves customized min/max from current value"
    (let [new-value (edited {:type :sine :min 0.2 :max 0.8 :period 1.0}
                            (field-edit :modulator/set-type :param-spec sample-param-spec :fx/event :triangle))]
      (is (= :triangle (:type new-value)))
      ;; Custom min/max should be preserved
      (is (= 0.2 (:min new-value)))
      (is (= 0.8 (:max new-value))))))

(deftest test-set-type-no-op-when-no-type
  (testing "Set type changes nothing without a valid type"
    (doseq [payload [{:fx/event nil} {:mod-type :no-such-type} {:mod-type :unified-keyframe-param}]]
      (is (= ::nothing (edited sample-modulator-config
                               (merge (field-edit :modulator/set-type :param-spec sample-param-spec) payload)))
          (pr-str payload))))
  (testing "calibration's dropdown has no type that follows a press"
    (let [state {:chains {:projector-effects {:p1 {:items [{:id effect-id :effect-id :test
                                                            :params {:test-param sample-modulator-config}}]}}}}]
      (is (= {} (modulator/handle (assoc (field-edit :modulator/set-type :param-spec sample-param-spec :mod-type :envelope)
                                         :domain :projector-effects :entity-key :p1 :state state))))
      (is (= :triangle (get-in (modulator/handle (assoc (field-edit :modulator/set-type :param-spec sample-param-spec :mod-type :triangle)
                                                        :domain :projector-effects :entity-key :p1 :state state))
                               [:state :chains :projector-effects :p1 :items 0 :params :test-param :type]))))))


;; Update Mod Param Tests


(deftest test-update-mod-param
  (testing "Updating modulator sub-parameter"
    (let [new-value (edited sample-modulator-config
                            (field-edit :modulator/update-param :mod-param-key :period :fx/event 2.0))]
      (is (= :sine (:type new-value)))
      (is (= 2.0 (:period new-value)))
      ;; Other values should be preserved
      (is (= 0.5 (:min new-value)))
      (is (= 1.5 (:max new-value))))))

(deftest test-update-mod-param-from-value-key
  (testing "Update uses :value key when :fx/event not present"
    (is (= 0.5 (:phase (edited sample-modulator-config
                               (field-edit :modulator/update-param :mod-param-key :phase :value 0.5)))))))

(deftest test-update-mod-param-on-non-modulator
  (testing "Update changes nothing when the stored value is not a modulator"
    (is (= ::nothing (edited 1.0 (field-edit :modulator/update-param :mod-param-key :period :fx/event 2.0))))))

(deftest test-update-mod-param-typed-text-is-a-finite-number
  (let [typed (fn [text]
                (edited sample-modulator-config
                        (field-edit :modulator/update-param :mod-param-key :period :mod-text-field? true
                                    :mod-param-min 0.1 :mod-param-max 16.0 :text text)))]
    (is (= 2.5 (:period (typed "2.5"))))
    (is (= 16.0 (:period (typed "99"))) "clamped")
    (doseq [text ["abc" "NaN" "Infinity" nil]]
      (is (= ::nothing (typed text)) (str (pr-str text) " changes nothing")))
    (is (= ::nothing (edited sample-modulator-config
                             (field-edit :modulator/update-param :mod-param-key :period :mod-text-field? true
                                         :fx/event (ActionEvent.))))
        "nor does the ActionEvent of a field that didn't read its text")))


;; The fields of what is stored (reactivity plan 07)


(deftest field-patches-use-stored-config-test
  (let [triangle {:type :triangle :period 7.0 :min 0.2 :max 0.8}
        state (with-param triangle)
        field (fn [k v] (field-edit :modulator/update-param :mod-param-key k :fx/event v))
        ;; Min and Max from one render: what each control sends
        min-03 (field :min 0.3)
        max-09 (field :max 0.9)
        square (field-edit :modulator/set-type :param-spec sample-param-spec :mod-type :square)]
    (testing "Min and Max from one render both stay, in either order"
      (doseq [order [[min-03 max-09] [max-09 min-03]]]
        (is (= (with-param (assoc triangle :min 0.3 :max 0.9)) (handled state order)))))
    (testing "two edits of the same field: the last one's value"
      (is (= (with-param (assoc triangle :min 0.4)) (handled state [min-03 (field :min 0.4)]))))
    (testing "the value a field has already changes nothing"
      (is (= ::nothing (edited triangle (field :min 0.2))))
      (is (= ::nothing (edited triangle (field :period 7))) "7 is the 7.0 stored"))
    (testing "a field the type defines that the stored config leaves out"
      (is (= (assoc triangle :phase 0.25) (edited triangle (field :phase 0.25)))))
    (testing "every sibling stays, a remembered static value too; Min and Max aren't sorted"
      (is (= (assoc triangle :min 0.3 :value 0.6) (edited (assoc triangle :value 0.6) min-03)))
      (is (= (assoc triangle :max 0.1) (edited triangle (field :max 0.1))) "Max below Min, as an Envelope's dip"))
    (testing "Max, then Square from the same render: the Square's defaults with the stored Min and Max"
      (is (= (with-param {:type :square :active? true :min 0.2 :max 0.9 :period 1.0 :time-unit :beats :loop-mode :loop
                          :once-periods 1.0 :duty-cycle 0.5 :phase 0.0 :restart-on :own})
             (handled state [max-09 square]))))
    (testing "the type it has already: nothing, so its fields stay"
      (is (= ::nothing (edited triangle (field-edit :modulator/set-type :param-spec sample-param-spec :mod-type :triangle)))))
    (testing "a field of the type before a type change does nothing after it"
      (let [square-config {:type :square :min 0.2 :max 0.8 :period 7.0 :duty-cycle 0.5}
            rendered-duty (field :duty-cycle 0.25)
            to-triangle (field-edit :modulator/set-type :param-spec sample-param-spec :mod-type :triangle)
            after (handled (with-param square-config) [to-triangle rendered-duty])]
        (is (= :triangle (:type (stored after))))
        (is (not (contains? (stored after) :duty-cycle)))
        (is (= ::nothing (edited (stored after) rendered-duty)))))
    (testing "a value that doesn't fit its field does nothing"
      (doseq [[k v] [[:min "0.3"] [:min ##NaN] [:min nil] [:time-unit :fortnights] [:time-unit 1.0]
                     [:loop-mode (ActionEvent.)]]]
        (is (= ::nothing (edited triangle (field k v))) (pr-str [k v]))))
    (testing "a checkbox's false is a value"
      (let [envelope {:type :envelope :source :hit :min 0.0 :max 1.0 :attack 0.0 :release 1.0 :curve :exp :hold? true}]
        (is (= (assoc envelope :hold? false) (edited envelope (field :hold? false))))
        (is (= ::nothing (edited (assoc envelope :hold? false) (field :hold? false))))
        (is (= envelope (edited (assoc envelope :hold? false) (field :hold? true))))))
    (testing "an empty palette is a value"
      (let [step {:type :step :source :hit :min 0.0 :max 1.0 :steps 4 :values [200.0 215.0]}]
        (is (= (assoc step :values []) (edited step (field-edit :modulator/update-param :mod-param-key :values
                                                                :text-field? true :text ""))))))
    (testing "a modulator that is off: only its static value"
      (let [off {:type :triangle :active? false :min 0.3 :max 0.9 :period 7.0 :value 0.4}
            static (field-edit :modulator/update-static-value :fx/event 0.6)]
        (is (= (with-param (assoc off :value 0.6)) (handled (with-param off) [static]))
            "a static slider rendered while Min was 0.2: Min stays 0.3")
        (is (= ::nothing (edited off max-09)) "an active control's Max, from before it was switched off")
        (is (= ::nothing (edited off square)) "nor its type")
        (is (= ::nothing (edited (assoc off :active? true) static)) "switched on again since")
        (is (= ::nothing (edited (dissoc off :active?) static)) "a config without :active? is on")
        (is (= ::nothing (edited 0.5 static)) "a number now: its own route")
        (is (= ::nothing (edited (assoc off :value 0.6) static)) "the value it has")))
    (testing "while the effect's keyframes are on, nothing: they set its parameters"
      (let [keyframed #(assoc-in (with-param %) [:chains :effect-chains [0 0] :items 0 :keyframe-modulator]
                                 {:enabled? true :keyframes []})
            off (assoc triangle :active? false :value 0.5)]
        (doseq [[value event] [[triangle min-03] [triangle square]
                               [off (field-edit :modulator/update-static-value :fx/event 0.6)]]]
          (is (= {} (modulator/handle (assoc event :state (keyframed value)))) (str (:event/type event))))))))

(defn- intensity
  "An Intensity whose Amount follows a Triangle from min to 0.8."
  [id min]
  {:id id :effect-id :intensity :enabled? true :params {:amount {:type :triangle :period 7.0 :min min :max 0.8}}})

(deftest field-patch-follows-instance-or-does-nothing-test
  (let [a #uuid "aaaaaaaa-0000-0000-0000-000000000001"
        b #uuid "bbbbbbbb-0000-0000-0000-000000000002"
        p #uuid "cccccccc-0000-0000-0000-000000000003"
        other #uuid "dddddddd-0000-0000-0000-000000000004"
        fx-a (intensity a 0.2)
        fx-b (intensity b 0.1)
        ;; A's Min, from a render where A was first in its chain (and its owner P first in the cell)
        patch (fn [domain entity-key & {:as more}]
                (merge {:event/type :modulator/update-param :domain domain :entity-key entity-key
                        :effect-instance-id a :param-key :amount :mod-param-key :min :fx/event 0.3}
                       more))
        min-of #(get-in %1 (conj %2 :params :amount :min))
        changes-only (fn [state event leaf]
                       (let [{after :state} (modulator/handle (assoc event :state state))]
                         (is (= 0.3 (min-of after leaf)) (str "A, now at " leaf))
                         (is (= (assoc-in state (conj leaf :params :amount :min) 0.3) after) "nothing else")
                         after))]
    (testing "an effects cell's chain"
      (let [cell [:chains :effect-chains [0 0] :items]
            event (patch :effect-chains [0 0])]
        (changes-only (assoc-in {} cell [fx-b fx-a]) event (conj cell 1))
        (changes-only (assoc-in {} cell [{:type :group :id other :items [fx-b fx-a]}]) event (conj cell 0 :items 1))
        (is (= {} (modulator/handle (assoc event :state (assoc-in {} cell [fx-b]))))
            "A gone, B in its place: nothing")
        (is (= {} (modulator/handle (assoc event :state (assoc-in {} [:chains :effect-chains [1 0] :items] [fx-a]))))
            "never another cell's")))
    (testing "an effect an owner has: the owner, then the effect among its own"
      (doseq [[what domain root owner] [["a cue item" :cue-chains [:chains :cue-chains [0 0] :items]
                                         {:id p :type :preset :preset-id :circle}]
                                        ["a timeline clip" :timeline-items [:chains :cue-chains [0 0] :items]
                                         {:id p :type :preset :preset-id :circle :track-id :t1}]]]
        (testing what
          (let [event (patch domain [0 0] :owner-id p)
                ;; rendered P at [0] with [A B]; now P is at [1], with [B A], behind
                ;; an owner with an effect of A's id (a copy that kept it)
                moved (assoc-in {} root [{:id other :type :preset :preset-id :circle :effects [(intensity a 0.5)]}
                                         (assoc owner :effects [fx-b fx-a])])
                after (changes-only moved event (conj root 1 :effects 1))]
            (is (= 0.5 (min-of after (conj root 0 :effects 0))) "the effect of A's id under another owner isn't it")
            (changes-only (assoc-in {} root [(assoc owner :effects [{:type :group :id other :items [fx-b fx-a]}])])
                          event (conj root 0 :effects 0 :items 1))
            (is (= {} (modulator/handle (assoc event :state (assoc-in {} root [(assoc owner :effects [fx-b])]))))
                "A gone from P, B in its place")
            (is (= {} (modulator/handle (assoc event :state (assoc-in {} root [{:id other :type :preset :effects [fx-a]}]))))
                "P gone")
            (is (= {} (modulator/handle (assoc (dissoc event :owner-id) :state moved))) "no owner named")))))
    (testing "a track inside a folder, moved with its effects"
      (let [root [:chains :cue-chains [0 0] :tracks]
            event (patch :timeline-tracks [0 0] :owner-id p)
            tracks [{:id other :type :group :name "Folder" :items [{:id :t1 :type :track :name "T1"}
                                                                    {:id p :type :track :name "T2" :effects [fx-b fx-a]}]}]]
        (changes-only (assoc-in {} root tracks) event (conj root 0 :items 1 :effects 1))
        (testing "and the folder's own effects"
          (changes-only (assoc-in {} root [{:id p :type :group :name "Folder" :effects [fx-a] :items []}])
                        event (conj root 0 :effects 0)))))
    (testing "the parameter gone, or not a modulator now: nothing"
      (let [cell [:chains :effect-chains [0 0] :items]
            event (patch :effect-chains [0 0])]
        (doseq [params [{} {:amount 0.5} {:amount nil}]]
          (is (= {} (modulator/handle (assoc event :state (assoc-in {} cell [(assoc fx-a :params params)]))))
              (pr-str params)))))
    (testing "an address that names no instance, or no supported chain: nothing"
      (let [state (assoc-in {} [:chains :effect-chains [0 0] :items] [fx-a])]
        (doseq [event [(dissoc (patch :effect-chains [0 0]) :effect-instance-id)
                       (patch :effect-chains [0 0] :effect-instance-id nil)
                       (patch :effect-chains nil)
                       (patch :no-such-domain [0 0])
                       (dissoc (patch :effect-chains [0 0]) :domain)]]
          (is (= {} (modulator/handle (assoc event :state state))) (pr-str (select-keys event [:domain :entity-key :effect-instance-id]))))))))


;; The static value of a modulator that is off


(deftest update-static-value-commits-only-a-number-test
  (let [inactive (assoc sample-modulator-config :active? false :value 0.7)
        commit (fn [event]
                 (modulator/handle (merge field-base
                                          {:event/type :modulator/update-static-value :state (with-param inactive)}
                                          event)))
        static #(:value (stored (:state %)))
        typed {:min 0.0 :max 2.0 :is-int? false}]
    (testing "a field's typed text (read on the FX thread): a number in the config, which keeps its settings"
      (is (= (with-param (assoc inactive :value 0.42)) (:state (commit (assoc typed :text "0.42")))))
      (is (= 2.0 (static (commit (assoc typed :text "5")))) "clamped to the parameter's max")
      (is (= 0.0 (static (commit (assoc typed :text "-5")))) "and min")
      (is (= 2 (static (commit (assoc typed :text "1.6" :is-int? true)))) "a whole number for an :int"))
    (testing "text that isn't a finite number commits nothing"
      (doseq [text ["abc" "" "NaN" "Infinity" nil]]
        (is (= {} (commit (assoc typed :text text))) (pr-str text))))
    (testing "the slider's number still commits"
      (is (= 1.5 (static (commit {:fx/event 1.5}))))
      (is (= 0.0 (static (commit {:fx/event 0.0})))))
    (testing "an ActionEvent or another non-number commits nothing"
      (doseq [payload [(ActionEvent.) ##NaN ##-Inf "0.4" :alternate nil]]
        (is (= {} (commit {:fx/event payload})) (pr-str payload))))
    (testing "a modulatable choice's combo box: one of its choices"
      (let [style {:type :sine :active? false :min 0.0 :max 6.0 :period 1.0 :value 2.0}
            state {:chains {:effect-chains {[0 0] {:items [{:id effect-id :effect-id :zone-pattern :params {:style style}}]}}}}
            choose #(commit {:state state :param-key :style :fx/event %})]
        (is (= :chase (get-in (choose :chase) [:state :chains :effect-chains [0 0] :items 0 :params :style :value])))
        (is (= {} (choose :no-such-style)))
        (is (= {} (commit {:state (assoc-in state [:chains :effect-chains [0 0] :items 0 :effect-id] :test)
                           :param-key :style :fx/event :chase}))
            "not without its effect's definition, which lists the choices")))))

(defn- run
  "Run event through the event pipeline from state, then each :dispatch it
   returns, inline as the app does; the state after."
  [state event]
  (let [effects (events/handle-event-pipeline (assoc event :state state))
        state (or (:state effects) state)]
    (if-let [next-event (:dispatch effects)]
      (run state next-event)
      state)))

(deftest typed-numbers-save-and-reopen-test
  (let [amount {:type :sine :active? false :value 0.2 :min 0.0 :max 1.0 :period 1.0}
        intensity {:effect-id :intensity :enabled? true}
        amount-id #uuid "55555555-5555-5555-5555-555555555555"
        before (-> (domains/build-initial-state)
                   (assoc-in [:chains :cue-chains [0 0]] {:name "Example" :items [{:type :preset :preset-id :circle}]})
                   (assoc-in [:chains :effect-chains [0 0] :items]
                             [(assoc intensity :id amount-id :params {:amount amount})])
                   (assoc-in [:chains :effect-chains [1 0] :items]
                             [(assoc intensity :id #uuid "66666666-6666-6666-6666-666666666666" :params {:amount 1.0}
                                     :keyframe-modulator {:enabled? true :selected-keyframe 1
                                                          :keyframes [{:position 0.0 :params {:amount 0.3}}
                                                                      {:position 1.0 :params {:amount 0.9}}]})])
                   history/mark-clean)
        field {:domain :effect-chains :param-key :amount :min 0.0 :max 3.0 :is-int? false}
        static (assoc field :event/type :modulator/update-static-value :entity-key [0 0] :effect-instance-id amount-id)
        keyframe (assoc field :event/type :keyframe/update-param :entity-key [1 0] :effect-path [0] :keyframe-idx 1)
        after (-> before
                  (run (assoc static :text "0.42"))
                  (run (assoc keyframe :text "0.66"))
                  ;; What the fields sent before their text was read on the FX thread
                  (run (assoc static :fx/event (ActionEvent.)))
                  (run (assoc keyframe :fx/event (ActionEvent.))))
        file (File/createTempFile "typed-numbers" ".zip")
        path (.getPath file)]
    (try
      (is (= 2 (count (get-in after [:history :undo-stack]))) "one undo step for each typed number")
      (is (true? (serialization/save-to-zip! path (persistent/project-files after))))
      (let [entries (serialization/load-from-zip path)
            content (get entries (:content persistent/zip-filenames))
            reopened (persistent/apply-loaded-project before entries path)
            amount-in #(get-in % [:chains :effect-chains [0 0] :items 0 :params :amount])
            frames-in #(mapv (comp :amount :params) (get-in % [:chains :effect-chains [1 0] :items 0 :keyframe-modulator :keyframes]))]
        (is (map? content) "content.edn reads back")
        (is (= (assoc amount :value 0.42) (amount-in content)))
        (is (= [0.3 0.66] (frames-in content)))
        (is (= (assoc amount :value 0.42) (amount-in reopened)))
        (is (= [0.3 0.66] (frames-in reopened)))
        (is (= ["Example" 1] ((juxt :name (comp count :items)) (get-in reopened [:chains :cue-chains [0 0]])))
            "the unrelated cue reopens too")
        (is (false? (get-in reopened [:project :dirty?]))))
      (finally
        (.delete file)))))

(deftest field-patches-save-and-reopen-test
  ;; Reactivity plan 00's hand-off 01 inputs -> 07 field patches -> 02
  ;; persistence, with today's reader (02's stricter one checks the same
  ;; file once it lands): a typed 0.42, Min and Max of an active modulator
  ;; from one render, Undo and Redo, then Save and Open
  (let [off-id #uuid "77777777-0000-0000-0000-000000000001"
        wave-id #uuid "77777777-0000-0000-0000-000000000002"
        off {:type :sine :active? false :value 0.2 :min 0.0 :max 1.0 :period 1.0}
        triangle {:type :triangle :active? true :period 7.0 :min 0.2 :max 0.8}
        intensity {:effect-id :intensity :enabled? true}
        before (-> (domains/build-initial-state)
                   (assoc-in [:chains :cue-chains [0 0]] {:name "Example" :items [{:type :preset :preset-id :circle}]})
                   (assoc-in [:chains :effect-chains [0 0] :items] [(assoc intensity :id off-id :params {:amount off})])
                   (assoc-in [:chains :effect-chains [1 0] :items] [(assoc intensity :id wave-id :params {:amount triangle})])
                   history/mark-clean)
        typed {:event/type :modulator/update-static-value :domain :effect-chains :entity-key [0 0]
               :effect-instance-id off-id :param-key :amount :min 0.0 :max 3.0 :is-int? false :text "0.42"}
        ;; What [1 0]'s Min and Max sliders send, from one render
        field (fn [k v] {:event/type :modulator/update-param :domain :effect-chains :entity-key [1 0]
                         :effect-instance-id wave-id :param-key :amount :mod-param-key k :fx/event v})
        off-in #(get-in % [:chains :effect-chains [0 0] :items 0 :params :amount])
        wave-in #(get-in % [:chains :effect-chains [1 0] :items 0 :params :amount])
        edited (-> before (run typed) (run (field :min 0.3)) (run (field :max 0.9)))
        undone (run edited {:event/type :edit/undo})
        redone (run undone {:event/type :edit/redo})
        file (File/createTempFile "field-patches" ".zip")
        path (.getPath file)]
    (try
      (is (= [(assoc off :value 0.42) (assoc triangle :min 0.3 :max 0.9)] ((juxt off-in wave-in) edited)))
      (is (= 3 (count (get-in edited [:history :undo-stack]))) "three edits, three undo steps")
      (is (= (assoc triangle :min 0.3) (wave-in undone)) "Undo takes back Max; Min stays")
      (is (= (assoc off :value 0.42) (off-in undone)))
      (is (= (assoc triangle :min 0.3 :max 0.9) (wave-in redone)) "Redo puts Max back")
      (is (true? (get-in redone [:project :dirty?])))
      (is (true? (serialization/save-to-zip! path (persistent/project-files redone))))
      (let [entries (serialization/load-from-zip path)
            content (get entries (:content persistent/zip-filenames))
            reopened (persistent/apply-loaded-project before entries path)]
        (is (map? content) "content.edn reads back")
        (is (= (assoc off :value 0.42) (off-in reopened)) "the typed number")
        (is (= (assoc triangle :min 0.3 :max 0.9) (wave-in reopened)) "both field edits")
        (is (= ["Example" 1] ((juxt :name (comp count :items)) (get-in reopened [:chains :cue-chains [0 0]])))
            "the unrelated cue reopens too")
        (is (false? (get-in reopened [:project :dirty?])) "the show opens clean")
        (is (= [[] []] ((juxt :undo-stack :redo-stack) (:history reopened))) "with fresh history"))
      (finally
        (.delete file)))))


;; Keyframe Mutual Exclusion Tests


(deftest test-toggle-on-disables-active-keyframes
  (let [owner-id #uuid "cccccccc-0000-0000-0000-00000000000c"
        group-id #uuid "dddddddd-0000-0000-0000-00000000000d"]
    (doseq [[domain entity-key owned?] [[:effect-chains [0 0] false]
                                        [:cue-chains [2 1] true]
                                        [:projector-effects :proj-1 false]]]
      (testing (str "a nested effect in " domain)
        (let [effect {:id effect-id :effect-id :test
                      :params {:test-param (assoc sample-modulator-config :active? false) :other 0.4}
                      :keyframe-modulator {:enabled? true :keyframes [{:position 0.0 :params {:test-param 0.5}}]}}
              group {:type :group :id group-id :items [effect]}
              items-path [:chains domain entity-key :items]
              ;; a cue's effects are its items' own
              [items effect-path] (if owned?
                                    [[{:id owner-id :type :preset :preset-id :circle :effects [group]}] [0 :effects 0 :items 0]]
                                    [[group] [0 :items 0]])
              state (assoc-in {} items-path items)
              result (modulator/handle (cond-> (assoc field-base :event/type :modulator/toggle :state state
                                                      :domain domain :entity-key entity-key
                                                      :param-spec sample-param-spec :fx/event true)
                                         owned? (assoc :owner-id owner-id)))]
          (is (= (-> effect (assoc-in [:keyframe-modulator :enabled?] false)
                            (assoc-in [:params :test-param :active?] true))
                 (get-in (:state result) (into items-path effect-path)))))))))

(deftest test-toggle-off-leaves-keyframes-alone
  (let [effect {:params {:test-param 0.5} :keyframe-modulator {:enabled? true}}]
    (is (= effect (toggle-effect effect false)))))

(deftest toggle-follows-instance-or-does-nothing-test
  ;; Follow-up B6: the ∿ toggle names its effect by its instance, as the
  ;; modulator's field edits do (reactivity plan 07): queued behind a
  ;; reorder, it turns on the effect it was clicked for, where that effect
  ;; is now, or does nothing once it is gone
  (let [a #uuid "aaaaaaaa-0000-0000-0000-0000000000b6"
        b #uuid "bbbbbbbb-0000-0000-0000-0000000000b6"
        p #uuid "cccccccc-0000-0000-0000-0000000000b6"
        other #uuid "dddddddd-0000-0000-0000-0000000000b6"
        fx (fn [id] {:id id :effect-id :intensity :enabled? true :params {:amount 1.0}})
        ;; A's ∿, from a render where A was first in its chain (and P first in the cue)
        toggle (fn [domain entity-key & {:as more}]
                 (merge {:event/type :modulator/toggle :domain domain :entity-key entity-key
                         :effect-instance-id a :param-key :amount :param-spec {:min 0.0 :max 3.0} :fx/event true}
                        more))
        modulating? #(reg/active-modulator? (get-in %1 (conj %2 :params :amount)))
        turns-on-only (fn [state event leaf]
                        (let [{after :state} (modulator/handle (assoc event :state state))]
                          (is (modulating? after leaf) (str "A, now at " leaf))
                          (is (= state (assoc-in after (conj leaf :params :amount) 1.0)) "nothing else")))]
    (testing "an effects cell's chain, B now where A was rendered"
      (let [cell [:chains :effect-chains [0 0] :items]
            event (toggle :effect-chains [0 0])]
        (turns-on-only (assoc-in {} cell [(fx b) (fx a)]) event (conj cell 1))
        (turns-on-only (assoc-in {} cell [{:type :group :id other :items [(fx b) (fx a)]}]) event (conj cell 0 :items 1))
        (is (= {} (modulator/handle (assoc event :state (assoc-in {} cell [(fx b)]))))
            "A gone, B in its place: nothing")
        (is (= {} (modulator/handle (assoc event :state (assoc-in {} [:chains :effect-chains [1 0] :items] [(fx a)]))))
            "never another cell's")
        (is (= {} (modulator/handle (assoc (dissoc event :effect-instance-id) :effect-path [0]
                                           :state (assoc-in {} cell [(fx a)]))))
            "nor a place: a toggle that names no instance does nothing")))
    (testing "a cue item's effect: the owner, then the effect among its own"
      (let [root [:chains :cue-chains [0 0] :items]
            event (toggle :cue-chains [0 0] :owner-id p)
            moved (assoc-in {} root [{:id other :type :preset :preset-id :circle :effects [(fx b)]}
                                     {:id p :type :preset :preset-id :circle :effects [(fx b) (fx a)]}])]
        (turns-on-only moved event (conj root 1 :effects 1))
        (is (= {} (modulator/handle (assoc event :state (assoc-in {} root [{:id p :type :preset :effects [(fx b)]}]))))
            "A gone from P")
        (is (= {} (modulator/handle (assoc (dissoc event :owner-id) :state moved))) "no owner named")))
    (testing "turning off what is off, or a number off: no change, nothing"
      (let [cell [:chains :effect-chains [0 0] :items]]
        (is (= {} (modulator/handle (assoc (toggle :effect-chains [0 0] :fx/event false)
                                           :state (assoc-in {} cell [(fx a)])))))))))


;; Unknown Event Type Test


(deftest test-unknown-event-type
  (testing "Unknown event type returns empty map"
    (let [event {:event/type :modulator/unknown-type}
          result (modulator/handle event)]
      (is (= {} result)))))


;; Different Modulator Types Tests


(deftest test-build-square-modulator
  (testing "Square modulator has duty-cycle parameter"
    (let [new-value (edited sample-modulator-config
                            (field-edit :modulator/set-type :param-spec sample-param-spec :fx/event :square))]
      (is (= :square (:type new-value)))
      (is (contains? new-value :duty-cycle))
      (is (= 0.5 (:duty-cycle new-value))))))

(deftest test-build-random-modulator
  (testing "Random modulator has period and time-unit parameters"
    (let [new-value (edited sample-modulator-config
                            (field-edit :modulator/set-type :param-spec sample-param-spec :fx/event :random))]
      (is (= :random (:type new-value)))
      (is (contains? new-value :period))
      (is (contains? new-value :time-unit)))))


;; The Step's palette (plans/2026-11-18-vibe-mode/04 §4)

(deftest number-list-param-parses-the-palette-test
  (let [step {:type :step :source :hit :min 0.0 :max 1.0 :steps 4 :values [1.0]}
        typed (fn [text]
                (:values (edited step (field-edit :modulator/update-param :mod-param-key :values
                                                  :text-field? true :text text))))]
    (is (= [200.0 215.0 320.0] (typed "200, 215 320")))
    (is (= [200.0 320.0] (typed "200 abc 320,,")) "what doesn't parse is left out")
    (is (= [-12.5 3.0] (typed " -12.5 ,3 ")))
    (is (= [] (typed "")) "empty clears the palette")))


;; Whole numbers in an :int field (follow-up B16b)

(deftest int-fields-store-whole-numbers-test
  ;; An :int field (the Step's Steps, a MIDI CC's channel and number) stores
  ;; a whole number, from its slider and from what is typed, as an :int
  ;; parameter does (h/parse-number-text); a :float field keeps its decimals
  (let [step {:type :step :source :hit :min 0.0 :max 1.0 :steps 4 :values []}
        midi {:type :midi :channel 1 :cc 1 :min 0.0 :max 1.0}
        slid (fn [config k v] (edited config (field-edit :modulator/update-param :mod-param-key k :fx/event v)))
        typed (fn [config k text lo hi]
                (edited config (field-edit :modulator/update-param :mod-param-key k :mod-text-field? true
                                           :mod-param-min lo :mod-param-max hi :text text)))]
    (testing "a slider's value, rounded"
      (is (= (assoc step :steps 6) (slid step :steps 5.6)))
      (is (= (assoc step :steps 5) (slid step :steps 5.4)))
      (is (integer? (:steps (slid step :steps 5.6))) "a whole number, not 6.0")
      (is (= ::nothing (slid (assoc step :steps 5) :steps 5.3)) "the 5 it has already: nothing")
      (is (= (assoc midi :cc 64) (slid midi :cc 63.7))))
    (testing "what is typed, rounded and clamped"
      (is (= (assoc step :steps 7) (typed step :steps "6.6" 2 16)))
      (is (= (assoc step :steps 16) (typed step :steps "99" 2 16)))
      (is (= (assoc midi :channel 4) (typed midi :channel "3.5" 1 16)))
      (is (integer? (:channel (typed midi :channel "3.5" 1 16))))
      (is (= ::nothing (typed step :steps "4.2" 2 16)) "4, the 4 it has"))
    (testing "a :float field keeps its decimals"
      (is (= 0.3 (:glide (slid step :glide 0.3))))
      (is (= 0.35 (:glide (typed step :glide "0.35" 0.0 4.0)))))))
