(ns laser-show.views.tabs.zones-test
  (:require [clojure.test :refer [deftest is]]
            [laser-show.views.tabs.zones :as zones]))

(deftest cue-usage-text-test
  (is (= "Used by no cues" (zones/cue-usage-text 0)))
  (is (= "Used by 1 cue" (zones/cue-usage-text 1)))
  (is (= "Used by 3 cues" (zones/cue-usage-text 3))))
