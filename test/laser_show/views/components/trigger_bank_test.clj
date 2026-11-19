(ns laser-show.views.components.trigger-bank-test
  (:require [clojure.test :refer [deftest is]]
            [laser-show.views.components.trigger-bank :as trigger-bank]))

(deftest binding-label-test
  (is (= "MIDI Ch 1 Note 36" (trigger-bank/binding-label {:source :midi :channel 0 :note 36})))
  (is (= "MIDI Ch 1 Note 36" (trigger-bank/binding-label {:source :midi :note 36})) "no channel: the first")
  (is (= "OSC /pad/1" (trigger-bank/binding-label {:source :osc :id "/pad/1"})))
  (is (= "Key :space" (trigger-bank/binding-label {:source :keyboard :id :space})))
  (is (= "{:source :gamepad, :id :a}" (trigger-bank/binding-label {:source :gamepad :id :a}))
      "any other source: the key itself"))
