(ns laser-show.events.chain-params-handlers-test
  "Tests that boolean parameter edits keep `false` instead of turning it into
   nil, that a numeric field's typed text commits only a number, that a
   colour edit takes only a plain colour (follow-up B4), for the effect it
   names by its instance (N2), and that an :int parameter stores a whole
   number (N1)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [laser-show.animation.effects.intensity] ; :intensity's :amount is a :float
   [laser-show.animation.effects.points] ; :decimate's :kpps and :fps are :int
   [laser-show.events.handlers.chain.helpers :as chain-helpers]
   [laser-show.events.handlers.chain.params :as params]
   [laser-show.events.handlers.keyframe :as keyframe]
   [laser-show.events.helpers :as h])
  (:import [javafx.event ActionEvent]))

(def config (chain-helpers/chain-config :effect-chains [0 0]))

(def state
  {:chains {:effect-chains {[0 0] {:items [{:effect-id :test
                                            :params {:invert? true :amount 0.5}
                                            :keyframe-modulator {:enabled? true
                                                                 :normalize? true
                                                                 :keyframes []}}]}}}})

(deftest first-some-test
  (is (false? (h/first-some false nil)))
  (is (false? (h/first-some nil false)))
  (is (= 1 (h/first-some nil 1 2)))
  (is (nil? (h/first-some nil nil))))

(deftest parse-number-text-test
  (is (= 0.42 (h/parse-number-text "0.42" 0.0 1.0 false)))
  (is (= 1000.0 (h/parse-number-text " 1e3 " nil nil false)) "spaces and exponents, as Double/parseDouble reads them")
  (is (= 1.0 (h/parse-number-text "7" 0.0 1.0 false)) "clamped to max")
  (is (= -2.0 (h/parse-number-text "-5" -2.0 2.0 false)) "and to min")
  (testing "a whole number for an :int parameter"
    (is (= 3 (h/parse-number-text "2.6" 1 8 true)))
    (is (instance? Long (h/parse-number-text "2.6" 1 8 true)))
    (is (= 8 (h/parse-number-text "12" 1 8 true))))
  (testing "nothing for text that isn't a finite number, or isn't text"
    (doseq [text ["abc" "" "  " "NaN" "Infinity" "-Infinity" "0,42" nil (ActionEvent.)]]
      (is (nil? (h/parse-number-text text 0.0 1.0 false)) (pr-str text)))))

(deftest update-param-from-text-test
  (let [commit (fn [event]
                 (get-in (params/handle-update-param-from-text
                          state config (merge {:effect-path [0] :param-key :amount :min 0.0 :max 1.0 :is-int? false} event))
                         [:chains :effect-chains [0 0] :items 0 :params :amount]))]
    (is (= 0.25 (commit {:text "0.25"})) "the field's text, read on the FX thread")
    (is (= 1.0 (commit {:text "4"})) "clamped")
    (doseq [text ["abc" "" "NaN" "Infinity"]]
      (is (= 0.5 (commit {:text text})) (str (pr-str text) " changes nothing")))
    (is (= 0.5 (commit {:fx/event (ActionEvent.)})) "an ActionEvent without its text changes nothing")))

(deftest update-color-param-takes-a-plain-colour-test
  ;; Follow-up B4: the colour picker reads its colour on the FX thread and
  ;; sends it as :color [red green blue]; the handler never reads a node
  (let [id #uuid "ffffffff-0000-0000-0000-0000000000b4"
        cell {:chains {:effect-chains {[0 0] {:items [{:id id :effect-id :set-color :params {:red 1.0 :green 1.0 :blue 1.0}}]}}}}
        rgb #(select-keys (get-in % [:chains :effect-chains [0 0] :items 0 :params]) [:red :green :blue])
        commit #(params/handle-update-color-param cell config (merge {:domain :effect-chains :entity-key [0 0] :effect-instance-id id} %))]
    (is (= {:red 0.2 :green 0.4 :blue 0.6} (rgb (commit {:color [0.2 0.4 0.6]}))))
    (is (= {:red 1.0 :green 0.0 :blue 0.5} (rgb (commit {:color [3 -1.0 0.5]}))) "clamped to 0.0-1.0")
    (testing "anything else changes nothing"
      (doseq [payload [{:fx/event (ActionEvent.)} {:color nil} {:color [0.1 0.2]} {:color [0.1 ##NaN 0.3]}
                       {:color "#ff0000"} {:color {:red 0.1 :green 0.2 :blue 0.3}} {}]]
        (is (identical? cell (commit payload)) (pr-str payload))))
    (testing "no such effect: nothing, not a new item"
      (is (identical? cell (commit {:effect-instance-id #uuid "ffffffff-0000-0000-0000-0000000000b5" :color [0.1 0.2 0.3]})))
      (is (identical? cell (commit {:effect-instance-id nil :color [0.1 0.2 0.3]}))))))

(deftest update-color-param-follows-the-instance-test
  ;; Follow-up N2: an effect's colour edit names its effect by its instance
  ;; (and owner), like the modulator's (reactivity plan 07, B6): queued
  ;; behind a reorder, it lands on that effect where it is now, or nowhere
  ;; once it is gone. One that names no instance does nothing: a cue item's
  ;; own colour is the preset editor's, :cue-chain/update-preset-color (N10)
  (let [a #uuid "aaaaaaaa-0000-0000-0000-0000000000c2"
        b #uuid "bbbbbbbb-0000-0000-0000-0000000000c2"
        p #uuid "cccccccc-0000-0000-0000-0000000000c2"
        other #uuid "dddddddd-0000-0000-0000-0000000000c2"
        white {:red 1.0 :green 1.0 :blue 1.0}
        set-color (fn [id] {:id id :effect-id :set-color :enabled? true :params white})
        ;; A's pick, rendered while A was first (and its owner P first)
        pick (fn [domain & {:as more}]
               (merge {:domain domain :entity-key [0 0] :effect-instance-id a :effect-path [0] :color [0.0 0.5 1.0]}
                      more))
        commit (fn [state event]
                 (params/handle-update-color-param state (chain-helpers/chain-config (:domain event) [0 0]) event))
        colour-of #(select-keys (get-in %1 (conj %2 :params)) [:red :green :blue])
        lands-on (fn [state event leaf]
                   (let [after (commit state event)]
                     (is (= {:red 0.0 :green 0.5 :blue 1.0} (colour-of after leaf)) (str "A, now at " leaf))
                     (is (= state (update-in after (conj leaf :params) merge white)) "nothing else")))]
    (testing "an effects cell's chain, B now where A was rendered"
      (let [cell [:chains :effect-chains [0 0] :items]
            event (pick :effect-chains)]
        (lands-on (assoc-in {} cell [(set-color b) (set-color a)]) event (conj cell 1))
        (lands-on (assoc-in {} cell [{:type :group :id other :items [(set-color b) (set-color a)]}]) event (conj cell 0 :items 1))
        (let [b-only (assoc-in {} cell [(set-color b)])]
          (is (identical? b-only (commit b-only event)) "A gone, B in its place: nothing"))
        (let [unnamed (assoc-in {} cell [(set-color a)])]
          (is (identical? unnamed (commit unnamed (assoc event :effect-instance-id nil))) "an instance it can't find: nothing"))))
    (testing "a cue item's effect: the owner, then the effect among its own"
      (let [root [:chains :cue-chains [0 0] :items]
            event (pick :cue-chains :owner-id p)
            moved (assoc-in {} root [{:id other :type :preset :preset-id :circle :effects [(set-color b)]}
                                     {:id p :type :preset :preset-id :circle :effects [(set-color b) (set-color a)]}])]
        (lands-on moved event (conj root 1 :effects 1))
        (is (identical? moved (commit moved (dissoc event :owner-id))) "no owner named: nothing")))
    (testing "one that names no instance, only a place: nothing"
      (let [root [:chains :cue-chains [0 0] :items]
            items (assoc-in {} root [{:id p :type :preset :preset-id :circle :params (assoc white :radius 0.5)}])]
        (is (identical? items (commit items {:domain :cue-chains :entity-key [0 0] :effect-path [0] :color [0.0 0.5 1.0]})))))))

(deftest update-param-rounds-an-int-param-test
  ;; Follow-up N1: a number for an :int parameter (Decimate's scan rate and
  ;; target fps, a preset's point count) is stored as a whole number, a long,
  ;; as 01's typed text is (h/parse-number-text): a slider's mid-drag 25.6 is
  ;; 26. A :float parameter keeps its decimals, and what isn't a number is
  ;; stored as before
  (let [cell (assoc-in {} [:chains :effect-chains [0 0] :items]
                       [{:id #uuid "eeeeeeee-0000-0000-0000-0000000000e1" :effect-id :decimate :enabled? true :params {:kpps 25 :fps 30}}
                        {:id #uuid "eeeeeeee-0000-0000-0000-0000000000e2" :effect-id :intensity :enabled? true :params {:amount 1.0}}])
        param #(get-in %1 [:chains :effect-chains [0 0] :items %2 :params %3])
        commit (fn [event] (params/handle-update-param cell config (merge {:effect-path [0]} event)))]
    (testing "an effect's :int parameter"
      (is (= 26 (param (commit {:param-key :kpps :fx/event 25.6}) 0 :kpps)))
      (is (= 25 (param (commit {:param-key :kpps :fx/event 25.4}) 0 :kpps)))
      (is (instance? Long (param (commit {:param-key :kpps :fx/event 25.6}) 0 :kpps)) "a long, not 26.0")
      (is (= 30 (param (commit {:param-key :fps :value 30.25}) 0 :fps)) "from :value too")
      (is (= 40 (param (commit {:param-key :fps :fx/event 40}) 0 :fps)) "a whole number as it is"))
    (testing "a :float parameter keeps its decimals"
      (is (= 0.25 (param (commit {:effect-path [1] :param-key :amount :fx/event 0.25}) 1 :amount))))
    (testing "what isn't a finite number is stored as before"
      (is (= {:type :sine :min 1.0 :max 2.0} (param (commit {:param-key :kpps :fx/event {:type :sine :min 1.0 :max 2.0}}) 0 :kpps)))
      (is (false? (param (commit {:param-key :kpps :fx/event false}) 0 :kpps))))
    (testing "a preset's :int parameter, in a cue chain"
      (let [cue (assoc-in {} [:chains :cue-chains [0 0] :items]
                          [{:id #uuid "eeeeeeee-0000-0000-0000-0000000000e3" :type :preset :preset-id :star
                            :params {:spikes 5 :outer-radius 0.5}}])
            cue-config (chain-helpers/chain-config :cue-chains [0 0])
            edited #(params/handle-update-param cue cue-config {:effect-path [0] :param-key %1 :fx/event %2})]
        (is (= 7 (get-in (edited :spikes 6.7) [:chains :cue-chains [0 0] :items 0 :params :spikes])))
        (is (= 0.45 (get-in (edited :outer-radius 0.45) [:chains :cue-chains [0 0] :items 0 :params :outer-radius]))
            "its :float parameter keeps its decimals")))))

(deftest update-param-unchecked-checkbox-test
  (testing "checkbox :fx/event false is stored as false"
    (let [result (params/handle-update-param state config {:effect-path [0]
                                                           :param-key :invert?
                                                           :fx/event false})]
      (is (false? (get-in result [:chains :effect-chains [0 0] :items 0 :params :invert?])))))
  (testing ":value false (no :fx/event) is stored as false"
    (let [result (params/handle-update-param state config {:effect-path [0]
                                                           :param-key :invert?
                                                           :value false})]
      (is (false? (get-in result [:chains :effect-chains [0 0] :items 0 :params :invert?])))))
  (testing ":fx/event still wins over :value"
    (let [result (params/handle-update-param state config {:effect-path [0]
                                                           :param-key :amount
                                                           :fx/event 0.9
                                                           :value 0.1})]
      (is (= 0.9 (get-in result [:chains :effect-chains [0 0] :items 0 :params :amount]))))))

(deftest keyframe-set-normalize-false-test
  (let [result (keyframe/handle {:event/type :keyframe/set-normalize
                                 :domain :effect-chains
                                 :entity-key [0 0]
                                 :effect-path [0]
                                 :normalize? false
                                 :state state})]
    (is (false? (get-in result [:state :chains :effect-chains [0 0] :items 0
                                :keyframe-modulator :normalize?])))))

(def ^:private projector-config (chain-helpers/chain-config :projector-effects :p1))

(def ^:private projector-state
  {:chains {:projector-effects {:p1 {:items [{:effect-id :rgb-curves :params {}}
                                             {:effect-id :axis-transform :params {:mirror-x? false}}
                                             {:type :group :items [{:effect-id :rgb-curves :params {}}
                                                                   {:effect-id :axis-transform :params {}}]}]}}}})

(deftest projector-effect-bool-param-test
  (let [result (params/handle-update-param projector-state projector-config
                                           {:effect-path [1] :param-key :mirror-x? :fx/event true})]
    (is (true? (get-in result [:chains :projector-effects :p1 :items 1 :params :mirror-x?])))
    (is (false? (get-in (params/handle-update-param result projector-config
                                                    {:effect-path [1] :param-key :mirror-x? :fx/event false})
                        [:chains :projector-effects :p1 :items 1 :params :mirror-x?])))))

(deftest nested-projector-effect-param-test
  (let [result (params/handle-update-param projector-state projector-config
                                           {:effect-path [2 :items 1] :param-key :swap-axes? :fx/event true})]
    (is (true? (get-in result [:chains :projector-effects :p1 :items 2 :items 1 :params :swap-axes?])))
    (is (nil? (get-in result [:chains :projector-effects :p1 :items 2 :params :swap-axes?]))
        "the group itself is untouched")))

