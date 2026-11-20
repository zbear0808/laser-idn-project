(ns laser-show.views.components.load-warning-test
  "What the cards, the toolbar chip and the cue warnings say of a laser's
   load (plans/2026-11-19-zones/05 §7)."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.views.components.load-warning :as lw]))

(defn- load [& {:as m}]
  (merge {:points-in 3000 :points 1800 :budget 1250 :min-fps 20 :scan-rate 25000
          :est-fps 8 :guard-fps 14 :reduced-pct 40 :dim-pct 0 :regions 0}
         m))

(deftest low-fps-text-test
  (let [r {:id :p3 :name "Laser R" :fps 14 :min-fps 20 :reduced-pct 40}
        l {:id :p1 :name "Laser L" :fps 16 :min-fps 20 :reduced-pct 0}
        c {:id :p2 :name "Laser C" :fps 18 :min-fps 25 :reduced-pct 12}]
    (is (= "LOW FPS · Laser R 14 fps" (lw/low-fps-text [r])) "the lowest")
    (is (= "LOW FPS · Laser R 14 fps +2" (lw/low-fps-text [r l c])) "+1 for each other low laser")
    (is (nil? (lw/low-fps-text [])) "none low: no chip")
    (is (= "Laser R: 14 fps (minimum 20) · −40% pts\nLaser L: 16 fps (minimum 20)\nLaser C: 18 fps (minimum 25) · −12% pts\nClick to see it."
           (lw/low-fps-tooltip [r l c]))
        "one line per laser, its cut when it has one")))

(deftest load-note-text-test
  (is (= "" (lw/load-note-text {:load (load :reduced-pct 0 :points 3000) :low? false})) "nothing to say")
  (is (= "split · 2 regions" (lw/load-note-text {:load (load :regions 2 :reduced-pct 0) :low? false})))
  (is (= "split · 1 region" (lw/load-note-text {:load (load :regions 1 :reduced-pct 0) :low? false})))
  (is (= "min 20 fps · −40% pts" (lw/load-note-text {:load (load) :low? true})) "low: it starts with the minimum")
  (is (= "−28% pts" (lw/load-note-text {:load (load :reduced-pct 28) :low? false}))
      "thinned back up to its minimum: the cut, and no chip (Q-Z3-5 A)")
  (is (= "min 20 fps · split · 2 regions · −40% pts · −31% bright"
         (lw/load-note-text {:load (load :regions 2 :dim-pct 31) :low? true})))
  (is (= "Safe area too small to split" (lw/load-note-text {:load (load :no-room? true :reduced-pct 0) :low? false})) "S-9")
  (is (nil? (lw/load-note-text nil)) "not streaming"))

(deftest load-meter-test
  (testing "100% is the points that still reach the minimum: its budget (Q-Z3-4 A)"
    (is (= 240 (lw/load-percent (load))))
    (is (= 31 (lw/load-percent (load :points-in 390))))
    (is (nil? (lw/load-percent (load :budget 0)))))
  (is (= [:ok :near :near :over] (mapv lw/meter-level [79 80 100 101])) "green under 80%, amber to 100%, red past it")
  (is (= "3,000 points a frame. 1,250 fit 20 fps at 25,000 pts/s" (lw/meter-tooltip (load))))
  (is (= "1,234,560" (lw/grouped 1234560)) "thousands, whatever the machine's locale"))

(deftest cue-warning-text-test
  (let [w {:laser "Laser C" :projector :p2 :points 2010 :fps 12 :min-fps 20 :scan-rate 25000}]
    (is (= "Too heavy for Laser C: about 12 fps (minimum 20)" (lw/cue-warning-text w)) "the cell's tooltip")
    (is (= "⚠ ~2,010 points: about 12 fps on Laser C (25 kpps). Simplify it, or lower Laser C's minimum."
           (lw/cue-line-text w))
        "the editor's line, until the item counts arrive")
    (is (= "⚠ ~2,010 points: about 12 fps on Laser C (25 kpps). Most points: Circle (2,000). Simplify it, or lower Laser C's minimum."
           (lw/cue-line-text (assoc w :item {:id :x :points 2000} :item-name "Circle"))))
    (is (= "⚠ ~2,010 points: about 12 fps on Laser C (27.5 kpps). Simplify it, or lower Laser C's minimum."
           (lw/cue-line-text (assoc w :scan-rate 27500))))
    (is (nil? (lw/cue-warning-text nil)))
    (is (nil? (lw/cue-line-text nil)))))
