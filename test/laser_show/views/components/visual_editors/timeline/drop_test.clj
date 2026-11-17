(ns laser-show.views.components.visual-editors.timeline.drop-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.timeline.layout :as layout]
            [laser-show.views.components.visual-editors.timeline.drop :as drop]))

;; Same geometry as gesture_test: zoom 50 px/beat, no scroll, ruler 36 px.
;; Folder :f row y 36-62 (summary bar 45-53), :t1 (in :f) y 63-99 with clip :a
;; [2, 6) at x 101-299, :t2 y 100-136.

(def ^:private clip-a
  {:id :a :type :preset :preset-id :circle :track-id :t1 :timeline/start 2.0 :timeline/duration 4.0
   :effects [{:id :e0 :effect-id :scale :params {} :enabled? true}
             {:id :e1 :effect-id :rotation :params {} :enabled? true}]})

(def ^:private cue
  {:editor-type :timeline
   :tracks [{:id :f :type :group :items [{:id :t1 :type :track}]}
            {:id :t2 :type :track}]
   :items [clip-a]})

(def ^:private rows (layout/rows (:tracks cue) (:items cue)))

(def ^:private view {:zoom 50.0 :scroll-beat 0.0 :scroll-y 0.0 :width 1000 :height 400})

(def ^:private opts {:col 0 :row 0 :tracks (:tracks cue) :snap {:enabled? true :value 0.25}})

(def ^:private square {:laser-show/drag :bank-item :kind :preset :id :square :category :geometric})
(def ^:private scale {:laser-show/drag :bank-item :kind :effect :id :scale :category :shape})
(def ^:private zone-fx {:laser-show/drag :bank-item :kind :effect :id :zone-reroute :category :zone})

(defn- hit
  "layout/hit-test at (x, y) plus the pointer's beat, as the editor builds it."
  [x y]
  (assoc (layout/hit-test rows cue view x y #{}) :beat (layout/x->beat view x)))

(defn- drop-at [payload x y] (drop/drop-target payload (hit x y) opts))

(def ^:private add-clip {:event/type :timeline/add-preset-to-track :col 0 :row 0 :item-id :square})
(def ^:private add-fx {:event/type :timeline/add-effect-to-item :col 0 :row 0 :item-id :scale})

(deftest preset-on-lane-snaps-start-test
  (is (= {:event (assoc add-clip :track-id :t2 :start 8.25)
          :preview {:ghost {:row-id :t2 :start 8.25 :length 4.0}}}
         (drop-at square 413 118)))
  (testing "never before 0, and unsnapped when snap is off"
    (is (= 0.0 (get-in (drop/drop-target square (assoc (hit 5 118) :beat -0.4) opts) [:event :start])))
    (is (= 8.26 (get-in (drop/drop-target square (hit 413 118) (assoc opts :snap {:enabled? false :value 0.25}))
                        [:event :start])))
    (is (= 8.26 (get-in (drop/drop-target square (hit 413 118) (dissoc opts :snap)) [:event :start])))))

(deftest preset-on-folder-row-targets-folder-test
  (testing "the event names the folder; the ghost is on the leaf the handler will pick"
    (is (= {:event (assoc add-clip :track-id :f :start 3.0)
            :preview {:ghost {:row-id :t1 :start 3.0 :length 4.0}}}
           (drop-at square 150 49)))
    (is (= :f (get-in (drop-at square 600 40) [:event :track-id])) "the empty part of the folder row"))
  (testing "no leaf track anywhere: nothing to add to"
    (let [tracks [{:id :g :type :group :items []}]
          r (first (layout/rows tracks []))]
      (is (nil? (drop/drop-target square {:type :lane :row r :row-idx 0 :beat 1.0} (assoc opts :tracks tracks)))))))

(deftest preset-on-clip-adds-overlapping-clip-test
  (is (= {:event (assoc add-clip :track-id :t1 :start 3.0)
          :preview {:ghost {:row-id :t1 :start 3.0 :length 4.0}}}
         (drop-at square 150 81))))

(deftest preset-on-header-uses-cursor-test
  (is (= {:event (assoc add-clip :track-id :t2)}
         (drop/drop-target square {:type :header :track-id :t2} opts)))
  (is (nil? (drop/drop-target square {:type :header} opts))))

(deftest effect-on-clip-targets-clip-test
  (is (= {:event (assoc add-fx :target-item-id :a :select-target? true)
          :preview {:highlight-clip :a}}
         (drop-at scale 150 81))))

(deftest effect-on-lane-header-and-summary-targets-track-test
  (is (= {:event (assoc add-fx :target-track-id :t1 :select-target? true)
          :preview {:highlight-row :t1}}
         (drop-at scale 400 81))
      "empty part of :t1")
  (is (= {:event (assoc add-fx :target-track-id :t2 :select-target? true)
          :preview {:highlight-row :t2}}
         (drop/drop-target scale {:type :header :track-id :t2} opts)))
  (is (= {:event (assoc add-fx :target-track-id :f :select-target? true)
          :preview {:highlight-row :f}}
         (drop-at scale 150 49))
      "the summary bar on folder :f"))

(deftest zone-effect-only-on-clips-test
  (is (= (assoc add-fx :item-id :zone-reroute :target-item-id :a :select-target? true)
         (:event (drop-at zone-fx 150 81))))
  (is (= :a (get-in (drop-at (assoc zone-fx :category #{:zone :shape}) 150 81) [:event :target-item-id]))
      "a category set containing :zone")
  (doseq [[what h] [["lane" (hit 400 81)]
                    ["summary bar" (hit 150 49)]
                    ["header" {:type :header :track-id :t2}]
                    ["track chain" {:type :chain :index 0 :target {:kind :track :entity {:id :t2}}}]]]
    (is (nil? (drop/drop-target zone-fx h opts)) what))
  (is (= 1 (get-in (drop/drop-target zone-fx {:type :chain :index 1 :target {:kind :clip :entity clip-a}} opts)
                   [:event :index]))
      "the chain of a clip is that clip"))

(deftest rejected-hits-and-payloads-test
  (testing "ruler, loop, end marker, below the rows"
    (doseq [[what h] [["ruler" (hit 150 20)]
                      ["loop" {:type :loop :edge :center}]
                      ["end" {:type :end :beat 16.0}]
                      ["empty" (hit 150 200)]
                      ["nil" nil]
                      ["odd" {:type :clip}]
                      ["odd lane" {:type :lane :beat 1.0}]
                      ["not a map" [:lane]]]
            p [square scale]]
      (is (nil? (drop/drop-target p h opts)) (str what " " (:kind p))))
    (is (nil? (drop/drop-target square {:type :lane :row {:id :t2} :beat "x"} opts)) "a preset needs a beat"))
  (testing "foreign or malformed payloads"
    (doseq [p [nil {} "square" {:kind :preset} {:kind :preset :id "square"} {:kind :clip :id :a}
               {:kind :effect :id :scale :category "shape"} {:kind :effect :id :scale :category #{"shape"}}]]
      (is (nil? (drop/drop-target p (hit 413 118) opts)) (pr-str p))))
  (testing "odd opts don't throw"
    (is (nil? (drop/drop-target square (hit 150 49) {:tracks :nonsense})))))

(deftest chain-insert-index-test
  (let [spans [[0 320] [326 320]]]
    (is (= 0 (drop/chain-insert-index spans 100)))
    (is (= 1 (drop/chain-insert-index spans 400)))
    (is (= 2 (drop/chain-insert-index spans 700)))
    (is (= 0 (drop/chain-insert-index [] 50)) "no cards")))

(deftest chain-drop-needs-single-target-test
  (let [on-chain (fn [target i] (drop/drop-target scale {:type :chain :target target :index i} opts))]
    (is (= {:event (assoc add-fx :target-item-id :a :index 1)}
           (on-chain {:kind :clip :entity clip-a} (drop/chain-insert-index [[0 320] [326 320]] 400))))
    (is (= {:event (assoc add-fx :target-track-id :f :index 0)}
           (on-chain {:kind :track :entity {:id :f :type :group}} 0)))
    (is (nil? (on-chain {:kind :clips :ids #{:a :b}} 0)))
    (is (nil? (on-chain {:kind :timeline} 0)))
    (is (nil? (on-chain {:kind :clip :entity clip-a} nil)) "needs an index")
    (is (nil? (drop/drop-target square {:type :chain :target {:kind :clip :entity clip-a} :index 0} opts))
        "presets don't go in the chain")))
