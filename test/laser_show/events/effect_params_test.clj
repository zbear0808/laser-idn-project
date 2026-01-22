(ns laser-show.events.effect-params-test
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.events.handlers.effect-params :as ep]))

(deftest toggle-zone-group-test
  (testing "adds group when not present"
    (let [state {:chains {:effect-chains {[0 0] {:items [{:params {:target-zone-groups [:left]}}]}}}}
          params-path [:chains :effect-chains [0 0] :items 0 :params]
          result (ep/toggle-zone-group state params-path :right)]
      (is (= #{:left :right}
             (set (get-in result (conj params-path :target-zone-groups)))))))
  
  (testing "removes group when present"
    (let [state {:chains {:effect-chains {[0 0] {:items [{:params {:target-zone-groups [:left :right]}}]}}}}
          params-path [:chains :effect-chains [0 0] :items 0 :params]
          result (ep/toggle-zone-group state params-path :right)]
      (is (= [:left]
             (get-in result (conj params-path :target-zone-groups))))))
  
  (testing "returns [:all] when removing last group"
    (let [state {:chains {:effect-chains {[0 0] {:items [{:params {:target-zone-groups [:left]}}]}}}}
          params-path [:chains :effect-chains [0 0] :items 0 :params]
          result (ep/toggle-zone-group state params-path :left)]
      (is (= [:all]
             (get-in result (conj params-path :target-zone-groups))))))
  
  (testing "handles :all as initial state"
    (let [state {:chains {:effect-chains {[0 0] {:items [{:params {:target-zone-groups [:all]}}]}}}}
          params-path [:chains :effect-chains [0 0] :items 0 :params]
          result (ep/toggle-zone-group state params-path :left)]
      (is (= #{:all :left}
             (set (get-in result (conj params-path :target-zone-groups)))))))
  
  (testing "handles missing :target-zone-groups (defaults to [:all])"
    (let [state {:chains {:effect-chains {[0 0] {:items [{:params {}}]}}}}
          params-path [:chains :effect-chains [0 0] :items 0 :params]
          result (ep/toggle-zone-group state params-path :left)]
      (is (= #{:all :left}
             (set (get-in result (conj params-path :target-zone-groups))))))))
