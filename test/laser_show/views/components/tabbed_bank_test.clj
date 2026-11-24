(ns laser-show.views.components.tabbed-bank-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.views.components.tabbed-bank :as tabbed-bank]))

(def ^:private item-button @#'tabbed-bank/item-button)

(def ^:private square {:id :square :name "Square" :category :geometric})

(def ^:private props
  {:item square :item-name-key :name :item-id-key :id
   :event-template {:event/type :timeline/add-preset-to-track :col 0 :row 0 :track-id :t1}
   :style-class "bank-item-btn"})

(deftest bank-click-event-unchanged-test
  (testing "without :drag-kind the button is exactly what it was: click only"
    (is (= {:fx/type :button
            :text "Square"
            :style-class "bank-item-btn"
            :on-action {:event/type :timeline/add-preset-to-track :col 0 :row 0 :track-id :t1
                        :item-id :square}}
           (item-button props)
           (item-button (assoc props :drag-kind nil)))))
  (testing "the click carries the item's id, not its definition: the handler reads that (reactivity plan 17)"
    (is (not (contains? (:on-action (item-button props)) :item))))
  (testing "with :drag-kind the click event is the same and the button is a drag source"
    (let [b (item-button (assoc props :drag-kind :preset))]
      (is (= (:on-action (item-button props)) (:on-action b)))
      (is (fn? (:on-drag-detected b)))
      (is (fn? (:on-drag-done b))))))
