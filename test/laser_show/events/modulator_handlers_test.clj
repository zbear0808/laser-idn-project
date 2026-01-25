(ns laser-show.events.modulator-handlers-test
  "Tests for modulator event handlers.
   
   Tests the modulator toggle, type selection, and sub-parameter update handlers."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.handlers.modulator :as modulator]))


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


(deftest test-toggle-static-to-modulated
  (testing "Toggling static value creates default sine modulator"
    (let [event (merge sample-event-base
                       {:event/type :modulator/toggle
                        :param-spec sample-param-spec
                        :current-value 1.0
                        :fx/event true})  ; User wants modulation ON
          result (modulator/handle event)]
      (is (contains? result :dispatch))
      (let [dispatch (:dispatch result)]
        (is (= :chain/update-param (:event/type dispatch)))
        (is (= :effect-chains (:domain dispatch)))
        (is (= [0 0] (:entity-key dispatch)))
        (is (= [0] (:effect-path dispatch)))
        (is (= :test-param (:param-key dispatch)))
        (let [new-value (:value dispatch)]
          (is (map? new-value))
          (is (= :sine (:type new-value)))
          (is (contains? new-value :min))
          (is (contains? new-value :max)))))))

(deftest test-toggle-modulated-to-static
  (testing "Toggling modulator preserves config with :active? false"
    (let [event (merge sample-event-base
                       {:event/type :modulator/toggle
                        :param-spec sample-param-spec
                        :current-value sample-modulator-config
                        :fx/event false})  ; User wants modulation OFF
          result (modulator/handle event)]
      (is (contains? result :dispatch))
      (let [dispatch (:dispatch result)
            new-value (:value dispatch)]
        ;; Should preserve modulator config with :active? false
        (is (map? new-value))
        (is (= false (:active? new-value)))
        ;; Should set :value to midpoint of 0.5 and 1.5 = 1.0
        (is (= 1.0 (:value new-value)))
        ;; Should preserve other modulator settings
        (is (= :sine (:type new-value)))
        (is (= 0.5 (:min new-value)))
        (is (= 1.5 (:max new-value)))))))

(deftest test-toggle-uses-param-spec-bounds
  (testing "New modulator uses param-spec bounds when reasonable"
    (let [spec {:min 0.0 :max 1.0 :default 0.5 :type :float}
          event (merge sample-event-base
                       {:event/type :modulator/toggle
                        :param-spec spec
                        :current-value 0.5
                        :fx/event true})  ; User wants modulation ON
          result (modulator/handle event)
          new-value (get-in result [:dispatch :value])]
      ;; When param-spec bounds are not -10/10, they should be used
      (is (= 0.0 (:min new-value)))
      (is (= 1.0 (:max new-value))))))


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
  (testing "Random modulator has period and period-unit parameters"
    (let [event (merge sample-event-base
                       {:event/type :modulator/set-type
                        :param-spec sample-param-spec
                        :current-value sample-modulator-config
                        :fx/event :random})
          result (modulator/handle event)
          new-value (get-in result [:dispatch :value])]
      (is (= :random (:type new-value)))
      (is (contains? new-value :period))
      (is (contains? new-value :period-unit)))))
