(ns laser-show.events.effect-params-test
  "Tests for effect-params handlers.
   
   Note: toggle-zone-group was removed as part of zone routing simplification.
   The old zone-reroute effect with :target-zone-groups has been replaced by
   the new zone-selector effect with keyframeable zones parameter."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.events.handlers.effect-params :as ep]))

;; Add tests for remaining effect-params functions as needed
