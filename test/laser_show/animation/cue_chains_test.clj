(ns laser-show.animation.cue-chains-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.cue-chains :as cue-chains]))

(deftest timeline-cue?-test
  (testing "returns false for nil or non-map"
    (is (false? (cue-chains/timeline-cue? nil)))
    (is (false? (cue-chains/timeline-cue? [])))
    (is (false? (cue-chains/timeline-cue? "string"))))

  (testing "returns false for standard cue chain without timeline features"
    (is (false? (cue-chains/timeline-cue? {})))
    (is (false? (cue-chains/timeline-cue? {:items [{:preset-id :circle}]})))
    (is (false? (cue-chains/timeline-cue? {:editor-type :cue-chain
                                          :items [{:preset-id :circle}]}))))

  (testing "returns true when explicitly marked with :editor-type :timeline"
    (is (true? (cue-chains/timeline-cue? {:editor-type :timeline})))
    (is (true? (cue-chains/timeline-cue? {:editor-type :timeline :items []}))))

  (testing "returns true when :tracks is present"
    (is (true? (cue-chains/timeline-cue? {:tracks [{:id "t1" :type :track}]}))))

  (testing "returns true when items contain timeline keys"
    (is (true? (cue-chains/timeline-cue? {:items [{:preset-id :circle :timeline/start 1.0}]})))
    (is (true? (cue-chains/timeline-cue? {:items [{:preset-id :circle :timeline/duration 2.0}]})))
    (is (true? (cue-chains/timeline-cue? {:items [{:preset-id :circle :track-id "t1"}]}))))

  (testing "respects explicit :editor-type :cue-chain override"
    (is (false? (cue-chains/timeline-cue? {:editor-type :cue-chain
                                          :tracks [{:id "t1" :type :track}]})))))
