(ns laser-show.input.store-test
  "Unit tests for the fast continuous input store."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [laser-show.input.store :as store]
            [laser-show.input.router :as router]))

(defn reset-store-fixture [f]
  (store/clear-continuous-values!)
  (f)
  (store/clear-continuous-values!))

(use-fixtures :each reset-store-fixture)

(deftest store-set-and-get-test
  (testing "Sets and retrieves continuous values with default fallback"
    (is (= 0.0 (store/get-continuous-value [:midi 0 1])))
    (is (= 10.0 (store/get-continuous-value [:midi 0 1] 10.0)))
    (store/set-continuous-value! [:midi 0 1] 0.75)
    (is (= 0.75 (store/get-continuous-value [:midi 0 1])))
    (is (= 0.75 (store/get-continuous-value [:midi 0 1] 0.0)))))

(deftest store-get-all-snapshot-test
  (testing "get-all-continuous-values returns immutable snapshot of current values"
    (store/set-continuous-value! [:midi 0 1] 0.25)
    (store/set-continuous-value! [:osc "/fader1"] 0.8)
    (let [snapshot (store/get-all-continuous-values)]
      (is (= {[:midi 0 1] 0.25
              [:osc "/fader1"] 0.8}
             snapshot))
      ;; Updating after snapshot doesn't mutate existing snapshot map
      (store/set-continuous-value! [:midi 0 1] 1.0)
      (is (= 0.25 (get snapshot [:midi 0 1])))
      (is (= 1.0 (store/get-continuous-value [:midi 0 1]))))))

(deftest router-store-integration-test
  (testing "router delegates continuous value operations to store"
    (router/set-continuous-value! [:osc "/fader2"] 0.5)
    (is (= 0.5 (router/get-continuous-value [:osc "/fader2"])))
    (is (= 0.5 (store/get-continuous-value [:osc "/fader2"])))
    (is (= 0.5 (get (router/get-all-continuous-values) [:osc "/fader2"])))))
