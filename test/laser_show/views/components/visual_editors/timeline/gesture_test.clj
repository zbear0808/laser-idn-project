(ns laser-show.views.components.visual-editors.timeline.gesture-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.timeline.model :as model]
            [laser-show.views.components.visual-editors.timeline.gesture :as gesture]))

;; Zoom 50 px/beat, ruler 24 px, rows 32 px. Row 0 = folder :f, row 1 = :t1
;; (clip :a at beat 2, length 4), row 2 = :t2.

(def ^:private clip-a
  {:id :a :type :preset :preset-id :circle :track-id :t1 :timeline/start 2.0 :timeline/duration 4.0})

(def ^:private cue
  {:editor-type :timeline
   :tracks [{:id :f :type :group :items [{:id :t1 :type :track}]}
            {:id :t2 :type :track}]
   :items [clip-a]})

(def ^:private rows
  [{:id :f :type :group :track {:id :f :type :group :items []} :items []}
   {:id :t1 :type :track :track {:id :t1 :type :track} :items [clip-a]}
   {:id :t2 :type :track :track {:id :t2 :type :track} :items []}])

(def ^:private view {:zoom-x 50.0 :rows rows :selection #{} :ruler-height 24 :track-height 32})
(def ^:private snap {:snap {:enabled? true :value 0.25}})

(def ^:private row1-y (+ 24 32 16))
(def ^:private row2-y (+ 24 64 16))

(defn- clip-hit [edge] {:type :clip :item clip-a :edge edge :track-idx 1})

(defn- drag
  "Press with hit at (x0, y0), move through the points, return the gesture."
  [hit [x0 y0] & points]
  (reduce (fn [g [x y & {:as mods}]]
            (gesture/update g (merge {:x x :y y} mods) cue view))
          (gesture/start hit {:x x0 :y y0} cue view)
          points))

(deftest gesture-examples-test
  (testing "press and release without moving: selection only"
    (let [g (drag (clip-hit :center) [125 row1-y])]
      (is (= {:ids [:a] :mode :replace} (:press-select g)))
      (is (nil? (gesture/finish g)))))
  (testing "move right by 75 px = 1.5 beats"
    (is (= {:op :move-clips :clip-ids #{:a} :anchor-id :a :delta 1.5 :snap? true}
           (:op (gesture/finish (drag (clip-hit :center) [125 row1-y] [200 row1-y]))))))
  (testing "left edge to x=40: trim start to 0.75 (original 2.0 - 1.2 beats, snapped by apply-op)"
    (let [{:keys [op]} (gesture/finish (drag (clip-hit :left) [101 row1-y] [40 row1-y]))]
      (is (= :trim-start (:op op)))
      (is (< (Math/abs (- 0.78 (:to op))) 1e-9))
      (is (= 0.75 (:timeline/start (first (:items (model/apply-op cue op snap))))))))
  (testing "drag down into row 2 moves the clip to track :t2"
    (let [{:keys [op select-track]} (gesture/finish (drag (clip-hit :center) [125 row1-y] [125 row2-y]))]
      (is (= :t2 (:to-track op)))
      (is (= 0.0 (:delta op)))
      (is (= :t2 select-track))))
  (testing "dragging onto the folder row keeps the track"
    (is (nil? (:to-track (:op (gesture/finish (drag (clip-hit :center) [125 row1-y] [125 (+ 24 16)])))))))
  (testing "cancel commits nothing"
    (is (nil? (gesture/finish (gesture/cancel (drag (clip-hit :center) [125 row1-y] [200 row1-y])))))))

(deftest shift-drag-is-unsnapped-test
  (let [{:keys [op]} (gesture/finish (drag (clip-hit :center) [125 row1-y] [140 row1-y :shift? true]))
        moved (first (:items (model/apply-op cue op snap)))]
    (is (false? (:snap? op)))
    (is (< (Math/abs (- 2.3 (:timeline/start moved))) 1e-9))))

(deftest preview-equals-commit-test
  (doseq [[hit from to] [[(clip-hit :center) [125 row1-y] [233 row2-y]]
                         [(clip-hit :left) [101 row1-y] [173 row1-y]]
                         [(clip-hit :right) [399 row1-y] [450 row1-y]]
                         [{:type :loop-brace :edge :center} [10 18] [90 18]]]]
    (let [g (drag hit from to)]
      (is (= (gesture/preview cue g snap)
             (model/apply-op cue (:op (gesture/finish g)) snap))
          (pr-str hit)))))

(deftest click-without-move-is-selection-only-test
  (testing "a 2 px wobble is still a click"
    (let [g (drag (clip-hit :center) [125 row1-y] [127 row1-y])]
      (is (not (:moved? g)))
      (is (nil? (:op (gesture/finish g))))
      (is (identical? cue (gesture/preview cue g snap)))))
  (testing "clicking an already-selected clip collapses a multi-selection on release"
    (let [v (assoc view :selection #{:a :b})
          g (gesture/start (clip-hit :center) {:x 125 :y row1-y} cue v)]
      (is (nil? (:press-select g)) "the press keeps the selection so both can be dragged")
      (is (= #{:a :b} (:clip-ids g)))
      (is (= {:select {:ids [:a] :mode :replace}} (gesture/finish g)))))
  (testing "Ctrl/Cmd-click toggles, Shift-click adds"
    (is (= :toggle (:mode (:press-select (gesture/start (clip-hit :center) {:x 125 :y row1-y :shortcut? true} cue view)))))
    (is (= :add (:mode (:press-select (gesture/start (clip-hit :center) {:x 125 :y row1-y :shift? true} cue view)))))))

(deftest marquee-selects-intersecting-clips-test
  (let [g (drag nil [50 (+ 24 2)] [160 row2-y])]
    (is (= {:select {:ids [:a] :mode :replace}} (gesture/finish g)))
    (is (= [50 26 110 (- row2-y 26)] (mapv long (gesture/marquee-rect g)))))
  (testing "a marquee that misses every clip selects nothing"
    (is (= {:select {:ids [] :mode :replace}} (gesture/finish (drag nil [10 row2-y] [60 row2-y]))))))
