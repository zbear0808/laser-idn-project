(ns laser-show.events.chain-params-handlers-test
  "Tests that boolean parameter edits keep `false` instead of turning it into
   nil, that a numeric field's typed text commits only a number, and that a
   colour edit takes only a plain colour (follow-up B4)."
  (:require
   [clojure.test :refer [deftest is testing]]
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
  (let [cell {:chains {:effect-chains {[0 0] {:items [{:effect-id :set-color :params {:red 1.0 :green 1.0 :blue 1.0}}]}}}}
        rgb #(select-keys (get-in % [:chains :effect-chains [0 0] :items 0 :params]) [:red :green :blue])
        commit #(params/handle-update-color-param cell config (merge {:effect-path [0]} %))]
    (is (= {:red 0.2 :green 0.4 :blue 0.6} (rgb (commit {:color [0.2 0.4 0.6]}))))
    (is (= {:red 1.0 :green 0.0 :blue 0.5} (rgb (commit {:color [3 -1.0 0.5]}))) "clamped to 0.0-1.0")
    (testing "anything else changes nothing"
      (doseq [payload [{:fx/event (ActionEvent.)} {:color nil} {:color [0.1 0.2]} {:color [0.1 ##NaN 0.3]}
                       {:color "#ff0000"} {:color {:red 0.1 :green 0.2 :blue 0.3}} {}]]
        (is (identical? cell (commit payload)) (pr-str payload))))
    (testing "no effect at its path: nothing, not a new item"
      (is (identical? cell (commit {:effect-path [1] :color [0.1 0.2 0.3]})))
      (is (identical? cell (commit {:effect-path nil :color [0.1 0.2 0.3]}))))))

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

