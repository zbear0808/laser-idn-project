(ns laser-show.events.handlers.triggers-test
  (:require [clojure.test :refer [deftest is]]
            [laser-show.events.handlers.triggers :as triggers]))

(deftest legacy-cue-trigger-dispatches-grid-event
  (let [input {:type :trigger :source :keyboard :id :space :timestamp 1000}
        state {:config {:input {:trigger-map
                                {{:source :keyboard :id :space}
                                 {:type :trigger-cue :target [2 1]}}}}
               :trigger-runtime {}}
        result (triggers/handle {:event/type :trigger/input
                                 :state state
                                 :input-event input
                                 :time 1000})]
    (is (= :grid/trigger-cell (:event/type (:dispatch result))))
    (is (= 2 (:col (:dispatch result))))
    (is (= 1 (:row (:dispatch result))))
    (is (= #{{:source :keyboard :id :space}}
           (get-in result [:state :trigger-runtime :held-inputs])))))

(deftest trigger-release-removes-held-input
  (let [input {:type :trigger-release :source :keyboard :id :space :timestamp 1100}
        state {:config {:input {:trigger-map {}}}
               :trigger-runtime {:held-inputs #{{:source :keyboard :id :space}}}}
        result (triggers/handle {:event/type :trigger/input
                                 :state state
                                 :input-event input
                                 :time 1100})]
    (is (empty? (get-in result [:state :trigger-runtime :held-inputs])))))

(deftest trigger-group-lock-and-expiry
  (let [state {:triggers {:settings {:group-duration-secs 1}}
               :trigger-runtime {:active-groups
                                 {:rhythm {:state :active :entered-at 0}}}}
        armed (:state (triggers/handle {:event/type :trigger/tick
                                        :state state
                                        :time 1000}))
        locked (:state (triggers/handle {:event/type :trigger-group/toggle-lock
                                         :state armed
                                         :group-id :rhythm}))]
    (is (= :armed (get-in armed [:trigger-runtime :active-groups :rhythm :state])))
    (is (= :locked (get-in locked [:trigger-runtime :active-groups :rhythm :state])))))
