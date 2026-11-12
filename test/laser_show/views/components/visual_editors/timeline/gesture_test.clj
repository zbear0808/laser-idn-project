(ns laser-show.views.components.visual-editors.timeline.gesture-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.timeline.layout :as layout]
            [laser-show.timeline.model :as model]
            [laser-show.views.components.visual-editors.timeline.gesture :as gesture]))

;; Zoom 50 px/beat, no scroll. Ruler 36 px. Row 0 = folder :f (canvas y
;; 36-62), row 1 = :t1 (63-99) holding clip :a at beat 2 for 4 beats (x 101-299),
;; row 2 = :t2 (100-136).

(def ^:private clip-a
  {:id :a :type :preset :preset-id :circle :track-id :t1 :timeline/start 2.0 :timeline/duration 4.0})

(def ^:private cue
  {:editor-type :timeline
   :tracks [{:id :f :type :group :items [{:id :t1 :type :track}]}
            {:id :t2 :type :track}]
   :items [clip-a]})

(def ^:private rows (layout/rows (:tracks cue) (:items cue)))

(def ^:private view
  {:zoom 50.0 :scroll-beat 0.0 :scroll-y 0.0 :width 1000 :height 400 :rows rows :selection #{}})

(def ^:private snap {:snap {:enabled? true :value 0.25}})

(def ^:private folder-y 49)
(def ^:private row1-y 81)
(def ^:private row2-y 118)

(defn- hit [v x y] (layout/hit-test rows cue v x y (:selection v)))

(defn- drag-in
  "Press at (x0, y0) in viewport v, move through the points, return the gesture."
  [v [x0 y0] & points]
  (reduce (fn [g [x y & {:as mods}]]
            (gesture/update g (merge {:x x :y y} mods) cue v))
          (gesture/start (hit v x0 y0) {:x x0 :y y0} cue v)
          points))

(defn- drag [from & points] (apply drag-in view from points))

(deftest gesture-examples-test
  (testing "press and release without moving: selection only"
    (let [g (drag [150 row1-y])]
      (is (= {:ids [:a] :mode :replace} (:press-select g)))
      (is (nil? (gesture/finish g)))))
  (testing "move right by 75 px = 1.5 beats"
    (is (= {:op :move-clips :clip-ids #{:a} :anchor-id :a :delta 1.5 :snap? true}
           (:op (gesture/finish (drag [150 row1-y] [225 row1-y]))))))
  (testing "left edge to x=42: trim start to 0.78, which snaps to 0.75"
    (let [{:keys [op]} (gesture/finish (drag [103 row1-y] [42 row1-y]))]
      (is (= :trim-start (:op op)))
      (is (< (Math/abs (- 0.78 (:to op))) 1e-9))
      (is (= 0.75 (:timeline/start (first (:items (model/apply-op cue op snap))))))))
  (testing "drag down into row 2 moves the clip to track :t2"
    (let [{:keys [op select-track]} (gesture/finish (drag [150 row1-y] [150 row2-y]))]
      (is (= :t2 (:to-track op)))
      (is (= 0.0 (:delta op)))
      (is (= :t2 select-track))))
  (testing "dragging onto the folder row keeps the track"
    (is (nil? (:to-track (:op (gesture/finish (drag [150 row1-y] [150 folder-y])))))))
  (testing "cancel commits nothing"
    (is (nil? (gesture/finish (gesture/cancel (drag [150 row1-y] [225 row1-y])))))))

(deftest scrolled-view-test
  (testing "moves are the same under a scrolled viewport"
    (let [v (assoc view :scroll-beat 1.0)]
      (is (= 1.5 (:delta (:op (gesture/finish (drag-in v [100 row1-y] [175 row1-y])))))))))

(deftest shift-drag-is-unsnapped-test
  (let [{:keys [op]} (gesture/finish (drag [150 row1-y] [165 row1-y :shift? true]))
        moved (first (:items (model/apply-op cue op snap)))]
    (is (false? (:snap? op)))
    (is (< (Math/abs (- 2.3 (:timeline/start moved))) 1e-9))))

(deftest preview-equals-commit-test
  (let [cue (assoc cue :loop {:enabled? true :start 0.0 :duration 8.0})
        loop-hit (layout/hit-test rows cue view 150 33 #{})]
    (is (= :loop (:type loop-hit)))
    (doseq [[h from to] [[(hit view 150 row1-y) [150 row1-y] [233 row2-y]]
                         [(hit view 103 row1-y) [103 row1-y] [173 row1-y]]
                         [(hit view 297 row1-y) [297 row1-y] [350 row1-y]]
                         [loop-hit [150 33] [230 33]]]]
      (let [g (gesture/update (gesture/start h {:x (first from) :y (second from)} cue view)
                              {:x (first to) :y (second to)} cue view)]
        (is (= (gesture/preview cue g snap)
               (model/apply-op cue (:op (gesture/finish g)) snap))
            (pr-str (:type h) (:edge h)))))))

(deftest click-without-move-is-selection-only-test
  (testing "a 2 px wobble is still a click"
    (let [g (drag [150 row1-y] [152 row1-y])]
      (is (not (:moved? g)))
      (is (nil? (:op (gesture/finish g))))
      (is (identical? cue (gesture/preview cue g snap)))))
  (testing "clicking an already-selected clip collapses a multi-selection on release"
    (let [v (assoc view :selection #{:a :b})
          g (gesture/start (hit v 150 row1-y) {:x 150 :y row1-y} cue v)]
      (is (nil? (:press-select g)) "the press keeps the selection so both can be dragged")
      (is (= #{:a :b} (:clip-ids g)))
      (is (= {:select {:ids [:a] :mode :replace}} (gesture/finish g)))))
  (testing "Ctrl/Cmd-click toggles, Shift-click adds"
    (is (= :toggle (:mode (:press-select (gesture/start (hit view 150 row1-y) {:x 150 :y row1-y :shortcut? true} cue view)))))
    (is (= :add (:mode (:press-select (gesture/start (hit view 150 row1-y) {:x 150 :y row1-y :shift? true} cue view)))))))

(deftest ruler-press-starts-a-scrub-test
  (is (= :scrub (:kind (gesture/start (hit view 150 10) {:x 150 :y 10} cue view)))))

(deftest marquee-selects-intersecting-clips-test
  (let [g (drag [50 40] [160 row2-y])]
    (is (= {:select {:ids [:a] :mode :replace}} (gesture/finish g)))
    (is (= [50 40 110 (- row2-y 40)] (mapv long (gesture/marquee-rect g)))))
  (testing "a marquee that misses every clip selects nothing"
    (is (= {:select {:ids [] :mode :replace}} (gesture/finish (drag [10 row2-y] [60 row2-y]))))))

(deftest scrub-and-end-marker-test
  (testing "a ruler press is a scrub; release seeks to where it ended"
    (let [g (drag [100 10] [175 12])]
      (is (= :scrub (:kind g)))
      (is (= {:seek 3.5} (gesture/finish g)))
      (is (= {:seek 2.0} (gesture/finish (drag [100 10]))) "a click seeks too")))
  (testing "dragging the end marker sets the length"
    (let [v view
          g (gesture/update (gesture/start {:type :end :beat 6.0} {:x 300 :y 10} cue v) {:x 400 :y 10} cue v)]
      (is (= {:op :set-length :beats 8.0 :snap? true} (:op (gesture/finish g)))))))
