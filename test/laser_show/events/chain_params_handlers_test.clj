(ns laser-show.events.chain-params-handlers-test
  "Tests that boolean parameter edits keep `false` instead of turning it into nil."
  (:require
   [clojure.test :refer [deftest is testing]]
   [laser-show.events.handlers.chain.helpers :as chain-helpers]
   [laser-show.events.handlers.chain.params :as params]
   [laser-show.events.handlers.keyframe :as keyframe]
   [laser-show.events.helpers :as h]))

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
