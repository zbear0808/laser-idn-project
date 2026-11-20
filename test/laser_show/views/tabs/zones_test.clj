(ns laser-show.views.tabs.zones-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.views.tabs.zones :as zones]))

(deftest cue-usage-text-test
  (is (= "Used by no cues" (zones/cue-usage-text 0)))
  (is (= "Used by 1 cue" (zones/cue-usage-text 1)))
  (is (= "Used by 3 cues" (zones/cue-usage-text 3))))

(deftest group-explanation-test
  (let [explain (fn [group & {:keys [n middle middle-name] :or {n 3 middle :split middle-name "Laser C"}}]
                  (zones/group-explanation group {:n n :middle middle :split-width 0.75} middle-name))
        left {:id :auto/left :kind :auto :name "Left"}
        right {:id :auto/right :kind :auto :name "Right"}]
    (is (= "Every laser that's on, except those set to Exclude from All." (explain {:id :all :kind :all :name "All"})))
    (testing "Left and Right: the halves of the rig, and how the middle laser joins them"
      (is (= "The left half of the rig. The middle laser, Laser C, is split: its left half is in Left." (explain left)))
      (is (= "The right half of the rig. The middle laser, Laser C, is split: its right half is in Right." (explain right)))
      (is (= "The left half of the rig. The middle laser, Laser C, is in both, whole." (explain left :middle :both)))
      (is (= "The left half of the rig. The middle laser, Laser C, is in Left only." (explain left :middle :left)))
      (is (= "The right half of the rig. The middle laser, Laser C, is in Left only." (explain right :middle :left)))
      (is (= "The left half of the rig. The middle laser, Laser C, is in neither." (explain left :middle :none)))
      (is (= "The left half of the rig." (explain left :n 4)) "an even count has no middle laser")
      (is (= "With one laser, Left is its left 75%." (explain left :n 1)))
      (is (= "With one laser, Right is its right 75%." (explain right :n 1))))
    (is (= "The middle laser, or the middle two." (explain {:id :auto/center :kind :auto})))
    (is (= "Every other laser from the left: 1st, 3rd, 5th…" (explain {:id :auto/odd :kind :auto})))
    (is (= "Every other laser from the left: 2nd, 4th…" (explain {:id :auto/even :kind :auto})))
    (is (= "With one laser, Odd is its left half and Even its right half." (explain {:id :auto/even :kind :auto} :n 1)))
    (is (= "Just Laser C." (explain {:id :auto.laser/c :kind :laser :name "Laser C (off)" :description "Just Laser C."})))
    (is (= "Click a laser to add it to Graphics or take it out. The boxes under the cards on the Projectors tab are the same ones."
           (explain {:id :graphics :kind :custom :name "Graphics"})))))

(deftest usage-text-test
  (is (= "Used by no cues" (zones/usage-text {:direct 0 :through 0 :zones []})))
  (is (= "Used by 3 cues" (zones/usage-text {:direct 3 :through 0 :zones []})))
  (is (= "Used by 1 cue" (zones/usage-text {:direct 1 :through 0 :zones []})))
  (is (= "Used by 3 cues · 9 more through Zone A" (zones/usage-text {:direct 3 :through 9 :zones [:zone/a]})))
  (is (= "Used by 3 cues · 11 more through Zones A, C" (zones/usage-text {:direct 3 :through 11 :zones [:zone/a :zone/c]})))
  (is (= "Used by 9 cues through Zone A" (zones/usage-text {:direct 0 :through 9 :zones [:zone/a]})))
  (is (= "Used by 1 cue through Zone B" (zones/usage-text {:direct 0 :through 1 :zones [:zone/b]}))))
