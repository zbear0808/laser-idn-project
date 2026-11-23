(ns laser-show.views.components.trigger-bank-test
  (:require [clojure.test :refer [deftest is]]
            [laser-show.views.components.trigger-bank :as trigger-bank]))

(deftest binding-label-test
  (is (= "MIDI Ch 1 Note 36" (trigger-bank/binding-label {:source :midi :channel 0 :note 36})))
  (is (= "MIDI Ch 1 Note 36" (trigger-bank/binding-label {:source :midi :note 36})) "no channel: the first")
  (is (= "OSC /pad/1" (trigger-bank/binding-label {:source :osc :id "/pad/1"})))
  (is (= "Key Space" (trigger-bank/binding-label {:source :keyboard :id :space})))
  (is (= "Key 1" (trigger-bank/binding-label {:source :keyboard :id :digit1})))
  (is (= "{:source :gamepad, :id :a}" (trigger-bank/binding-label {:source :gamepad :id :a}))
      "any other source: the key itself"))

(deftest fader-label-test
  (is (= "MIDI Ch 1 CC 7" (trigger-bank/fader-label [:midi 0 7])))
  (is (= "MIDI Ch 16 CC 1" (trigger-bank/fader-label [:midi 15 1])))
  (is (= "OSC /fader/1" (trigger-bank/fader-label [:osc "/fader/1"])))
  (is (= "OSC CC 5" (trigger-bank/fader-label [:osc 0 5])) "a mapped OSC address's control")
  (is (= "Gamepad 1 trigger left" (trigger-bank/fader-label [:gamepad 0 :trigger-left])))
  (is (= "[:keyboard 0 :k]" (trigger-bank/fader-label [:keyboard 0 :k])) "any other: the key itself"))
