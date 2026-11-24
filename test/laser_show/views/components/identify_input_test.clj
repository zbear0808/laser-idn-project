(ns laser-show.views.components.identify-input-test
  (:require [clojure.test :refer [deftest is]]
            [laser-show.views.components.identify-input :as identify-input]))

(deftest input-label-test
  (is (= "MIDI Ch 2 Note 60" (identify-input/input-label {:type :note-on :source :midi :channel 1 :note 60}))
      "channels as the user counts them, from 1")
  (is (= "MIDI Ch 1 Note 60 off" (identify-input/input-label {:type :note-off :source :midi :channel 0 :note 60})))
  (is (= "MIDI Ch 16 CC 7" (identify-input/input-label {:type :control-change :source :midi :channel 15 :control 7})))
  (is (= "MIDI Ch 1 Program 3" (identify-input/input-label {:type :program-change :source :midi :channel 0 :program 3})))
  (is (= "OSC /1/push1" (identify-input/input-label {:type :trigger :source :osc :id "/1/push1" :path "/1/push1"})))
  (is (= "OSC /fader1" (identify-input/input-label {:type :control-change :source :osc :channel 0 :control 1 :path "/fader1"}))
      "a mapped address by its path, not its control"))

(deftest result-text-test
  (is (= "Detected MIDI Ch 2 Note 60"
         (identify-input/result-text {:status :captured :input-event {:type :note-on :source :midi :channel 1 :note 60}})))
  (is (= "No input received." (identify-input/result-text {:status :timeout})))
  (is (= "Could not identify the input." (identify-input/result-text {:status :failed})))
  (is (nil? (identify-input/result-text nil)) "ready: nothing to say"))
