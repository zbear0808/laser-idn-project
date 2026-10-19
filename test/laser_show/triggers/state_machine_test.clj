(ns laser-show.triggers.state-machine-test
  (:require [clojure.test :refer [deftest is]]
            [laser-show.triggers.state-machine :as machine]))

(deftest transitions-and-emits
  (let [m (machine/make :idle {:idle [{:match {:type :go} :to :active :emit :started}]
                               :active [{:match {:type :stop} :to :idle :emit :stopped}]})
        [effect m] (machine/transition m {:type :go})
        [effect2 m] (machine/transition m {:type :stop})]
    (is (= :started effect))
    (is (= :stopped effect2))
    (is (= :idle (:state m)))
    (is (= 2 (count (:history m))))))
