(ns laser-show.events.modulator-handlers-test
  "Tests for modulator event handlers.
   
   Tests the modulator toggle, type selection, and sub-parameter update
   handlers, and that a modulator that is off keeps only a number typed into
   its parameter's field, through Save and Open."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.effects.intensity] ; :intensity's :amount is a :float
            [laser-show.animation.effects.zone] ; :zone-pattern's :style is a modulatable :choice
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

(def sample-event-base
  "Base event data for modulator events."
  {:domain :effect-chains
   :entity-key [0 0]
   :effect-path [0]
   :param-key :test-param})


;; Toggle Tests


(defn- toggle-effect [effect enabled?]
  (let [state {:chains {:effect-chains {[0 0] {:items [effect]}}}}
        result (modulator/handle (assoc sample-event-base :event/type :modulator/toggle :state state
                                        :param-spec sample-param-spec :fx/event enabled?))]
    (get-in (:state result) [:chains :effect-chains [0 0] :items 0])))

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
    (let [event (merge sample-event-base
                       {:event/type :modulator/set-type
                        :param-spec sample-param-spec
                        :current-value sample-modulator-config
                        :fx/event :triangle})
          result (modulator/handle event)]
      (is (contains? result :dispatch))
      (let [new-value (get-in result [:dispatch :value])]
        (is (= :triangle (:type new-value)))))))

(deftest test-set-modulator-type-from-combo-box-item
  (testing "Setting type from combo-box item map extracts :id"
    (let [event (merge sample-event-base
                       {:event/type :modulator/set-type
                        :param-spec sample-param-spec
                        :current-value sample-modulator-config
                        :fx/event {:id :square :name "Square" :icon "▭"}})
          result (modulator/handle event)]
      (is (contains? result :dispatch))
      (let [new-value (get-in result [:dispatch :value])]
        (is (= :square (:type new-value)))))))

(deftest test-set-type-preserves-custom-min-max
  (testing "Changing type preserves customized min/max from current value"
    (let [custom-modulator {:type :sine :min 0.2 :max 0.8 :period 1.0}
          event (merge sample-event-base
                       {:event/type :modulator/set-type
                        :param-spec sample-param-spec
                        :current-value custom-modulator
                        :fx/event :triangle})
          result (modulator/handle event)
          new-value (get-in result [:dispatch :value])]
      (is (= :triangle (:type new-value)))
      ;; Custom min/max should be preserved
      (is (= 0.2 (:min new-value)))
      (is (= 0.8 (:max new-value))))))

(deftest test-set-type-no-op-when-no-type
  (testing "Set type returns empty when no valid type provided"
    (let [event (merge sample-event-base
                       {:event/type :modulator/set-type
                        :param-spec sample-param-spec
                        :current-value sample-modulator-config
                        :fx/event nil})
          result (modulator/handle event)]
      (is (= {} result)))))


;; Update Mod Param Tests


(deftest test-update-mod-param
  (testing "Updating modulator sub-parameter"
    (let [event (merge sample-event-base
                       {:event/type :modulator/update-param
                        :current-value sample-modulator-config
                        :mod-param-key :period
                        :fx/event 2.0})
          result (modulator/handle event)]
      (is (contains? result :dispatch))
      (let [new-value (get-in result [:dispatch :value])]
        (is (= :sine (:type new-value)))
        (is (= 2.0 (:period new-value)))
        ;; Other values should be preserved
        (is (= 0.5 (:min new-value)))
        (is (= 1.5 (:max new-value)))))))

(deftest test-update-mod-param-from-value-key
  (testing "Update uses :value key when :fx/event not present"
    (let [event (merge sample-event-base
                       {:event/type :modulator/update-param
                        :current-value sample-modulator-config
                        :mod-param-key :phase
                        :value 0.5})
          result (modulator/handle event)
          new-value (get-in result [:dispatch :value])]
      (is (= 0.5 (:phase new-value))))))

(deftest test-update-mod-param-on-non-modulator
  (testing "Update returns empty when current-value is not a modulator"
    (let [event (merge sample-event-base
                       {:event/type :modulator/update-param
                        :current-value 1.0  ; static value, not modulator
                        :mod-param-key :period
                        :fx/event 2.0})
          result (modulator/handle event)]
      (is (= {} result)))))

(deftest test-update-mod-param-typed-text-is-a-finite-number
  (let [typed (fn [text]
                (modulator/handle (merge sample-event-base
                                         {:event/type :modulator/update-param
                                          :current-value sample-modulator-config
                                          :mod-param-key :period
                                          :mod-text-field? true
                                          :mod-param-min 0.1 :mod-param-max 16.0
                                          :fx/event text})))]
    (is (= 2.5 (get-in (typed "2.5") [:dispatch :value :period])))
    (is (= 16.0 (get-in (typed "99") [:dispatch :value :period])) "clamped")
    (doseq [text ["abc" "NaN" "Infinity"]]
      (is (nil? (:dispatch (typed text))) (str (pr-str text) " changes nothing")))))


;; The static value of a modulator that is off


(deftest update-static-value-commits-only-a-number-test
  (let [inactive (assoc sample-modulator-config :active? false :value 0.7)
        commit (fn [event]
                 (modulator/handle (merge sample-event-base
                                          {:event/type :modulator/update-static-value :current-value inactive}
                                          event)))
        static #(get-in % [:dispatch :value :value])
        typed {:min 0.0 :max 2.0 :is-int? false}]
    (testing "a field's typed text (read on the FX thread): a number in the config, which keeps its settings"
      (is (= {:event/type :chain/update-param :domain :effect-chains :entity-key [0 0] :effect-path [0]
              :param-key :test-param :value (assoc inactive :value 0.42)}
             (:dispatch (commit (assoc typed :text "0.42")))))
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
            state {:chains {:effect-chains {[0 0] {:items [{:effect-id :zone-pattern :params {:style style}}]}}}}
            choose #(commit {:state state :param-key :style :current-value style :fx/event %})]
        (is (= :chase (static (choose :chase))))
        (is (= {} (choose :no-such-style)))
        (is (= {} (commit {:param-key :style :current-value style :fx/event :chase}))
            "not without its effect, whose definition lists the choices")))))

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
        before (-> (domains/build-initial-state)
                   (assoc-in [:chains :cue-chains [0 0]] {:name "Example" :items [{:type :preset :preset-id :circle}]})
                   (assoc-in [:chains :effect-chains [0 0] :items]
                             [(assoc intensity :id #uuid "55555555-5555-5555-5555-555555555555" :params {:amount amount})])
                   (assoc-in [:chains :effect-chains [1 0] :items]
                             [(assoc intensity :id #uuid "66666666-6666-6666-6666-666666666666" :params {:amount 1.0}
                                     :keyframe-modulator {:enabled? true :selected-keyframe 1
                                                          :keyframes [{:position 0.0 :params {:amount 0.3}}
                                                                      {:position 1.0 :params {:amount 0.9}}]})])
                   history/mark-clean)
        field {:domain :effect-chains :effect-path [0] :param-key :amount :min 0.0 :max 3.0 :is-int? false}
        static (assoc field :event/type :modulator/update-static-value :entity-key [0 0] :current-value amount)
        keyframe (assoc field :event/type :keyframe/update-param :entity-key [1 0] :keyframe-idx 1)
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


;; Keyframe Mutual Exclusion Tests


(deftest test-toggle-on-disables-active-keyframes
  (doseq [[domain entity-key] [[:effect-chains [0 0]]
                               [:cue-chains [2 1]]
                               [:projector-effects :proj-1]]]
    (testing (str "a nested effect in " domain)
      (let [effect {:effect-id :test
                    :params {:test-param (assoc sample-modulator-config :active? false) :other 0.4}
                    :keyframe-modulator {:enabled? true :keyframes [{:position 0.0 :params {:test-param 0.5}}]}}
            items-path [:chains domain entity-key :items]
            state (assoc-in {} items-path [{:type :group :items [effect]}])
            result (modulator/handle (assoc sample-event-base :event/type :modulator/toggle :state state
                                            :domain domain :entity-key entity-key :effect-path [0 :items 0]
                                            :param-spec sample-param-spec :fx/event true))]
        (is (= (-> effect (assoc-in [:keyframe-modulator :enabled?] false)
                          (assoc-in [:params :test-param :active?] true))
               (get-in (:state result) (into items-path [0 :items 0]))))))))

(deftest test-toggle-off-leaves-keyframes-alone
  (let [effect {:params {:test-param 0.5} :keyframe-modulator {:enabled? true}}]
    (is (= effect (toggle-effect effect false)))))


;; Unknown Event Type Test


(deftest test-unknown-event-type
  (testing "Unknown event type returns empty map"
    (let [event {:event/type :modulator/unknown-type}
          result (modulator/handle event)]
      (is (= {} result)))))


;; Different Modulator Types Tests


(deftest test-build-square-modulator
  (testing "Square modulator has duty-cycle parameter"
    (let [event (merge sample-event-base
                       {:event/type :modulator/set-type
                        :param-spec sample-param-spec
                        :current-value sample-modulator-config
                        :fx/event :square})
          result (modulator/handle event)
          new-value (get-in result [:dispatch :value])]
      (is (= :square (:type new-value)))
      (is (contains? new-value :duty-cycle))
      (is (= 0.5 (:duty-cycle new-value))))))

(deftest test-build-random-modulator
  (testing "Random modulator has period and time-unit parameters"
    (let [event (merge sample-event-base
                       {:event/type :modulator/set-type
                        :param-spec sample-param-spec
                        :current-value sample-modulator-config
                        :fx/event :random})
          result (modulator/handle event)
          new-value (get-in result [:dispatch :value])]
      (is (= :random (:type new-value)))
      (is (contains? new-value :period))
      (is (contains? new-value :time-unit)))))


;; The Step's palette (plans/2026-11-18-vibe-mode/04 §4)

(deftest number-list-param-parses-the-palette-test
  (let [step {:type :step :source :hit :min 0.0 :max 1.0 :steps 4 :values []}
        typed (fn [text]
                (get-in (modulator/handle (merge sample-event-base
                                                 {:event/type :modulator/update-param
                                                  :current-value step
                                                  :mod-param-key :values
                                                  :text-field? true
                                                  :text-field-type :number-list
                                                  :fx/event text}))
                        [:dispatch :value :values]))]
    (is (= [200.0 215.0 320.0] (typed "200, 215 320")))
    (is (= [200.0 320.0] (typed "200 abc 320,,")) "what doesn't parse is left out")
    (is (= [-12.5 3.0] (typed " -12.5 ,3 ")))
    (is (= [] (typed "")) "empty clears the palette")))
