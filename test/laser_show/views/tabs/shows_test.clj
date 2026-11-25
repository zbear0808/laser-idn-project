(ns laser-show.views.tabs.shows-test
  (:require [clojure.test :refer [deftest is]]
            [laser-show.views.tabs.shows :as shows]))

(deftest migration-text-test
  (is (= "Moved 2 timeline cues from the grid into Shows. Timecode turned off for 'Timeline A2' (it overlapped). Saving writes the new layout; older versions won't see these shows."
         (shows/migration-text {:moved 2 :timecode-off ["Timeline A2"]}))
      "01 §6's example")
  (is (= "Moved 1 timeline cue from the grid into Shows. Saving writes the new layout; older versions won't see these shows."
         (shows/migration-text {:moved 1 :timecode-off []})))
  (is (= "Moved 3 timeline cues from the grid into Shows. Timecode turned off for 'A', 'B' and 'C' (they overlapped). Saving writes the new layout; older versions won't see these shows."
         (shows/migration-text {:moved 3 :timecode-off ["A" "B" "C"]}))))
