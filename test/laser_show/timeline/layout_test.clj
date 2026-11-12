(ns laser-show.timeline.layout-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.timeline.layout :as layout]))

(def ^:private tracks
  [{:id :f :type :group :items [{:id :t1 :type :track} {:id :t2 :type :track}]}
   {:id :t3 :type :track}])

(def ^:private clips
  [{:id :a :track-id :t1 :timeline/start 0.0 :timeline/duration 4.0}
   {:id :b :track-id :t2 :timeline/start 2.0 :timeline/duration 2.0}
   {:id :c :track-id :t3 :timeline/start 8.0 :timeline/duration 4.0}])

(defn- shape [rows] (mapv (juxt :id :kind :y :h) rows))

(deftest rows-examples-test
  (testing "expanded (plan §7.1)"
    (is (= [[:f :folder 0 26] [:t1 :track 27 36] [:t2 :track 64 36] [:t3 :track 101 36]]
           (shape (layout/rows tracks clips)))))
  (testing "f collapsed"
    (is (= [[:f :folder 0 26] [:t3 :track 27 36]]
           (shape (layout/rows (assoc-in tracks [0 :collapsed?] true) clips)))))
  (testing "depth, clips and inherited zones"
    (let [rows (layout/rows (assoc-in tracks [0 :zone-group-id] :front) clips)
          by-id (into {} (map (juxt :id identity)) rows)]
      (is (= [0 1 1 0] (mapv :depth rows)))
      (is (= [:a] (mapv :id (get-in by-id [:t1 :clips]))))
      (is (= [:front true] ((juxt :zone-id :zone-inherited?) (by-id :t1))))
      (is (= [:front false] ((juxt :zone-id :zone-inherited?) (by-id :f))))
      (is (nil? (:zone-id (by-id :t3))) "top level, no zone: the cue default"))))

(deftest collapsed-folder-summarizes-children-test
  (let [rows (layout/rows (assoc-in tracks [0 :collapsed?] true) clips)]
    (is (= [:a :b] (mapv :id (:summary-clips (first rows)))))
    (is (= [] (:clips (first rows)))))
  (testing "expanded folders summarize too"
    (is (= [:a :b] (mapv :id (:summary-clips (first (layout/rows tracks clips))))))))

(deftest track-colors-test
  (let [colors (layout/track-colors [{:id :f :type :group :items [{:id :t1 :type :track}
                                                                 {:id :t2 :type :track :color "#123456"}]}
                                     {:id :t3 :type :track}])]
    (is (= {:t1 "#4A90D9" :t2 "#123456" :t3 "#9B59B6"} colors))))

(def ^:private view {:zoom 50.0 :scroll-beat 10.0 :scroll-y 0.0 :width 1000 :height 400})

(deftest zoom-at-keeps-focal-beat-test
  (testing "plan §7.2"
    (let [v (layout/zoom-at view 500 2.0)]
      (is (= 100.0 (:zoom v)))
      (is (= 15.0 (:scroll-beat v)))
      (is (== 20.0 (layout/x->beat v 500)))))
  (testing "clamped to the max zoom"
    (is (= 400.0 (:zoom (layout/zoom-at view 500 1000.0)))))
  (testing "never scrolls before beat 0"
    (is (= 0.0 (:scroll-beat (layout/zoom-at (assoc view :scroll-beat 0.0) 500 0.5))))))

(deftest scroll-clamp-test
  (is (= 0.0 (:scroll-beat (layout/scroll-by (assoc view :scroll-beat 2.0) -300 0))))
  (is (= [0.0 500.0] (layout/visible-beat-range (assoc view :zoom 2.0 :scroll-beat 0.0)))
      "long shows fit; there's no width cap")
  (testing "clamp-view keeps the scroll inside the content"
    (let [v (layout/clamp-view (assoc view :scroll-beat 90.0 :scroll-y 500.0) 64.0 200.0)]
      (is (= 44.0 (:scroll-beat v)) "64 beats of content, 20 visible")
      (is (= 0.0 (:scroll-y v)) "content shorter than the lanes"))))

(deftest beat-x-roundtrip-test
  (let [rnd (java.util.Random. 3)]
    (dotimes [_ 200]
      (let [v {:zoom (+ 2.0 (* 398.0 (.nextDouble rnd))) :scroll-beat (* 100 (.nextDouble rnd)) :width 800}
            beat (* 500 (.nextDouble rnd))]
        (is (< (Math/abs (- beat (layout/x->beat v (layout/beat->x v beat)))) 1e-9))))))

(deftest ruler-ticks-levels-test
  (let [kinds (fn [zoom] (set (map :kind (layout/ruler-ticks {:zoom zoom :scroll-beat 0.0 :width 800}))))]
    (is (= #{:bar} (kinds 3.0)))
    (is (= #{:bar :beat} (kinds 12.0)))
    (is (= #{:bar :beat} (kinds 50.0)))
    (is (= #{:bar :beat :sub} (kinds 120.0))))
  (testing "labels: bars at 1-based bar numbers, beats as bar.beat"
    (let [ticks (layout/ruler-ticks {:zoom 50.0 :scroll-beat 0.0 :width 400})]
      (is (= ["1" "1.2" "1.3" "1.4" "2" "2.2"] (take 6 (keep :label ticks))))
      (is (= [0.0 50.0] (take 2 (map :x ticks))))))
  (testing "below 24 px/beat only bars are labelled"
    (is (every? #(= :bar (:kind %)) (filter :label (layout/ruler-ticks {:zoom 12.0 :scroll-beat 0.0 :width 800}))))))

(def ^:private rows (layout/rows tracks clips))
(def ^:private v0 {:zoom 50.0 :scroll-beat 0.0 :scroll-y 0.0 :width 1000 :height 400})

(deftest hit-test-topmost-first-test
  (let [overlap (layout/rows [{:id :t :type :track}]
                             [{:id :x :track-id :t :timeline/start 0.0 :timeline/duration 4.0}
                              {:id :y :track-id :t :timeline/start 2.0 :timeline/duration 4.0}])
        y (+ 36 18)]
    (is (= :y (get-in (layout/hit-test overlap {} v0 150 y #{}) [:clip :id])) "later start is on top")
    (is (= :x (get-in (layout/hit-test overlap {} v0 150 y #{:x}) [:clip :id])) "selected is on top"))
  (testing "edges"
    (is (= :left (:edge (layout/hit-test rows {} v0 3 (+ 36 27 18) #{}))))
    (is (= :right (:edge (layout/hit-test rows {} v0 196 (+ 36 27 18) #{}))))
    (is (= :center (:edge (layout/hit-test rows {} v0 100 (+ 36 27 18) #{})))))
  (testing "lanes, the ruler and below the rows"
    (is (= :lane (:type (layout/hit-test rows {} v0 600 (+ 36 27 18) #{}))))
    (is (= :ruler (:type (layout/hit-test rows {} v0 600 10 #{}))))
    (is (= :empty (:type (layout/hit-test rows {} v0 600 390 #{}))))))

(deftest hit-test-loop-band-only-test
  (let [cue {:loop {:enabled? true :start 0.0 :duration 8.0}}]
    (is (= {:type :loop :edge :center} (layout/hit-test rows cue v0 200 33 #{})) "inside the 6 px band")
    (is (= :ruler (:type (layout/hit-test rows cue v0 200 12 #{}))) "above the band is ruler")
    (is (= :ruler (:type (layout/hit-test rows {} v0 200 33 #{}))) "no loop, no band")))

(deftest scrolled-rows-test
  (let [v (assoc v0 :scroll-y 27.0)]
    (is (= :t1 (:id (second (layout/row-at rows v (+ 36 5))))) "t1 is now at the top of the lanes")
    (is (= 36.0 (layout/row-y v (second rows))))))

(deftest ruler-time-mode-test
  (let [v {:zoom 50.0 :scroll-beat 0.0 :width 800}
        ticks (layout/ruler-ticks v {:mode :time :bpm 120.0 :tc-start 0.0 :fps 25})
        labelled (filter :label ticks)]
    (testing "120 BPM, 50 px/beat = 100 px/s: a label every second"
      (is (= ["00:00" "00:01" "00:02"] (take 3 (map :label labelled))))
      (is (= [0.0 2.0 4.0] (take 3 (map :beat labelled))) "1 s = 2 beats"))
    (testing "tc-start shifts the labels"
      (is (= "1:00:00" (:label (first (filter :label (layout/ruler-ticks v {:mode :time :bpm 120.0 :tc-start 3600.0}))))))))
  (testing "frame ticks at >= 400 px/s"
    (let [ticks (layout/ruler-ticks {:zoom 250.0 :scroll-beat 0.0 :width 400} {:mode :time :bpm 120.0 :fps 25})]
      (is (some #(= :sub (:kind %)) ticks))
      (is (< (Math/abs (- 0.08 (- (:beat (second ticks)) (:beat (first ticks))))) 1e-9) "1/25 s = 0.08 beats"))))

(deftest hit-test-end-marker-test
  (let [cue {:items [{:timeline/start 0.0 :timeline/duration 8.0}]}]
    (is (= {:type :end :beat 8.0} (layout/hit-test rows cue v0 401 12 #{})))
    (is (= :ruler (:type (layout/hit-test rows cue v0 380 12 #{}))))
    (is (= :ruler (:type (layout/hit-test rows (assoc cue :loop {:enabled? true :start 0.0 :duration 4.0}) v0 401 12 #{})))
        "a looping timeline has no end marker")))
