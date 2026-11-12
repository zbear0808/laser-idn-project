(ns laser-show.timeline.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.timeline.model :as model]))

(deftest track-group?-test
  (testing "identifies groups correctly"
    (is (model/track-group? {:type :group}))
    (is (model/track-group? {:type :track :items []}))
    (is (not (model/track-group? {:type :track})))))

(deftest flatten-visible-tracks-test
  (testing "flattens and inherits zones; collapsed folders hide their children"
    (let [tracks [{:id "t1"
                   :type :track
                   :zone-group-id :z1}
                  {:id "g1"
                   :type :group
                   :zone-group-id :z2
                   :items [{:id "t2"
                            :type :track} ;; Should inherit :z2
                           {:id "t3"
                            :type :track
                            :zone-group-id :z3}]} ;; Should override to :z3
                  {:id "g2"
                   :type :group
                   :collapsed? true
                   :zone-group-id :z4
                   :items [{:id "t4"
                            :type :track}]}] ;; Children hidden, but group row remains
          result (model/flatten-visible-tracks tracks)]

      (is (= 5 (count result)) "t1, g1, t2, t3, g2")
      (is (= "t1" (:id (nth result 0))))
      (is (= "g1" (:id (nth result 1))))
      (is (= :z2 (:zone-group-id (nth result 1))))
      (is (= "t2" (:id (nth result 2))))
      (is (= :z2 (:zone-group-id (nth result 2))))
      (is (= "t3" (:id (nth result 3))))
      (is (= :z3 (:zone-group-id (nth result 3))))
      (is (= "g2" (:id (nth result 4))))
      (is (= :z4 (:zone-group-id (nth result 4)))))))

(deftest flatten-visible-tracks-hidden-descendants-test
  (let [tracks [{:id :f :type :group :collapsed? true
                 :items [{:id :t1 :type :track}
                         {:id :g :type :group :items [{:id :t2 :type :track}]}]}
                {:id :open :type :group :items [{:id :t3 :type :track}]}]
        rows (model/flatten-visible-tracks tracks)]
    (is (= [:f :open :t3] (mapv :id rows)))
    (is (= #{:t1 :g :t2} (:hidden-descendants (first rows))))
    (is (nil? (:hidden-descendants (second rows))))))

(deftest first-leaf-track-test
  (testing "finds the first non-group track at any depth"
    (let [tracks [{:id "g1"
                   :type :group
                   :items []} ;; Empty group, should skip
                  {:id "g2"
                   :type :group
                   :items [{:id "g3"
                            :type :group
                            :items [{:id "t1" :type :track}
                                    {:id "t2" :type :track}]}]}
                  {:id "t3" :type :track}]]
      (is (= "t1" (:id (model/first-leaf-track tracks))))
      (is (nil? (model/first-leaf-track [])))
      (is (nil? (model/first-leaf-track [{:id "g" :type :group :items []}]))))))

(deftest insert-track-test
  (testing "resolves candidate track or falls back to first leaf track"
    (let [tracks [{:id "g1"
                   :type :group
                   :items [{:id "t1" :type :track}
                           {:id "t2" :type :track}]}
                  {:id "t3" :type :track}]]
      (is (= "t1" (model/insert-track tracks "t1")) "Already leaf track")
      (is (= "t1" (model/insert-track tracks "g1")) "Folder resolves to child track")
      (is (= "t3" (model/insert-track tracks "t3")) "Other leaf track")
      (is (= "t1" (model/insert-track tracks nil)) "Nil resolves to first leaf track")
      (is (= "t1" (model/insert-track tracks "unknown")) "Unknown resolves to first leaf track"))))

(deftest auto-initialize-tracks-test
  (testing "creates zone-group folders with child tracks"
    (let [zone-groups {:all {:name "All" :color "#FFFFFF"}
                       :front {:name "Front" :color "#FF0000"}
                       :back {:name "Back" :color "#00FF00"}}
          cue-chain {:destination-zone {:zone-group-id :all}
                     :items [{:id "item1"
                              :effects []}
                             {:id "item2"
                              :effects [{:effect-id :zone-selector
                                         :params {:target-zone :front}}]}
                             {:id "item3"
                              :effects [{:effect-id :zone-selector
                                         :params {:target-zone :front}}]}]}
          result (model/auto-initialize-tracks cue-chain zone-groups)
          tracks (:tracks result)
          items (:items result)]

      ;; 1. Check folder structure
      (is (= 2 (count tracks)) "Should create 2 folders: :all (default) and :front")
      (let [all-folder (first (filter #(= :all (:zone-group-id %)) tracks))
            front-folder (first (filter #(= :front (:zone-group-id %)) tracks))]

        (is (= :group (:type all-folder)))
        (is (= "All" (:name all-folder)))
        (is (= 1 (count (:items all-folder))) "Should have one child track")
        (is (= :track (:type (first (:items all-folder)))))
        (is (= :all (:zone-group-id (first (:items all-folder)))))

        (is (= :group (:type front-folder)))
        (is (= 1 (count (:items front-folder))))
        (is (= :track (:type (first (:items front-folder)))))
        (is (= :front (:zone-group-id (first (:items front-folder))))))

      ;; 2. Check item routing to the child tracks
      (let [all-track-id (:id (first (:items (first (filter #(= :all (:zone-group-id %)) tracks)))))
            front-track-id (:id (first (:items (first (filter #(= :front (:zone-group-id %)) tracks)))))]

        (is (= all-track-id (:track-id (nth items 0))) "Item 1 goes to default zone child track")
        (is (= front-track-id (:track-id (nth items 1))) "Item 2 goes to front zone child track")
        (is (= front-track-id (:track-id (nth items 2))) "Item 3 goes to front zone child track")))))

(deftest insert-track-resolves-folders-and-fallbacks-test
  (testing "resolves leaf track or falls back according to hierarchy"
    (let [tracks [{:id "g1"
                   :type :group
                   :items [{:id "t1" :type :track}
                           {:id "t2" :type :track}]}
                  {:id "t3" :type :track}]]
      (is (= "t2" (model/insert-track tracks "t2")))
      (is (= "t1" (model/insert-track tracks "g1")))
      (is (= "t1" (model/insert-track tracks nil)))
      (is (= "t1" (model/insert-track tracks "deleted-id")))
      (is (nil? (model/insert-track [] "t1"))))))

(def ^:private snap-q {:enabled? true :value 0.25})

(def ^:private clip-1-4 {:id :c :timeline/start 1.0 :timeline/duration 4.0})

(defn- span [item] [(:timeline/start item) (:timeline/duration item)])

(deftest trim-start-keeps-end-test
  (testing "plan §7.3 examples"
    (is (= [0.0 5.0] (span (model/trim-start clip-1-4 -3.0 snap-q))) "clamped to 0")
    (is (= [4.9375 0.0625] (span (model/trim-start clip-1-4 9.0 snap-q))) "min length wins")
    (is (= [1.5 3.5] (span (model/trim-start clip-1-4 1.6 snap-q))) "snapped"))
  (testing "end never moves for in-range targets"
    (doseq [target (range -2.0 8.0 0.37)]
      (let [r (model/trim-start clip-1-4 target snap-q)]
        (is (== 5.0 (model/clip-end r)))
        (is (>= (:timeline/start r) 0.0))
        (is (>= (:timeline/duration r) model/min-clip-length))))))

(deftest trim-end-keeps-start-test
  (is (= [1.0 0.0625] (span (model/trim-end clip-1-4 0.5 snap-q))))
  (is (= [1.0 6.0] (span (model/trim-end clip-1-4 7.1 snap-q))))
  (doseq [target (range -2.0 10.0 0.41)]
    (let [r (model/trim-end clip-1-4 target snap-q)]
      (is (== 1.0 (:timeline/start r)))
      (is (>= (:timeline/duration r) model/min-clip-length)))))

(deftest move-clip-shifts-children-test
  (let [group (assoc clip-1-4 :items [{:id :child :timeline/start 1.0 :timeline/duration 2.0}
                                      {:id :untimed}])]
    (testing "+2.1 snaps to start 3.0, child shifted by the applied +2.0"
      (let [r (model/move-clip group 2.1 snap-q)]
        (is (= [3.0 4.0] (span r)))
        (is (== 3.0 (get-in r [:items 0 :timeline/start])))
        (is (not (contains? (get-in r [:items 1]) :timeline/start)) "untimed child stays untimed")))
    (testing "-5 clamps to 0, child shifted by the applied -1.0"
      (let [r (model/move-clip group -5.0 snap-q)]
        (is (= [0.0 4.0] (span r)))
        (is (== 0.0 (get-in r [:items 0 :timeline/start])))))))

(deftest loop-trims-test
  (let [lp {:enabled? true :start 1.0 :duration 4.0}]
    (is (= {:enabled? true :start 0.0 :duration 5.0} (model/trim-loop-start lp -3.0 snap-q)))
    (is (= {:enabled? true :start 1.0 :duration 6.0} (model/trim-loop-end lp 7.0 snap-q)))
    (is (= {:enabled? true :start 3.0 :duration 4.0} (model/move-loop lp 2.1 snap-q)))))

(def ^:private norm-tracks [{:id :f :type :group :items [{:id :t1 :type :track}]}])

(defn- norm-item [item]
  (first (:items (model/normalize-timeline-cue {:editor-type :timeline :tracks norm-tracks :items [item]}))))

(deftest normalize-timeline-cue-examples-test
  (testing "plan §7.1 table"
    (is (= {:id :a :type :preset :timeline/start 0.0 :timeline/duration 4.0 :track-id :t1}
           (norm-item {:id :a :type :preset})))
    (is (= {:id :a :type :preset :timeline/start 8.0 :timeline/duration 4.0 :track-id :t1}
           (norm-item {:id :a :type :preset :timeline/start 8.0 :track-id :t1})))
    (is (= :t1 (:track-id (norm-item {:id :a :track-id :gone :timeline/start 2.0 :timeline/duration 1.0})))
        "dangling track id goes to the first leaf")
    (let [g (norm-item {:id :g :type :group
                        :items [{:id :c1 :timeline/start 4.0 :timeline/duration 2.0}
                                {:id :c2 :timeline/start 8.0 :timeline/duration 4.0}]})]
      (is (= [4.0 8.0] [(:timeline/start g) (:timeline/duration g)]) "group spans its timed children")
      (is (= [{:id :c1 :timeline/start 4.0 :timeline/duration 2.0}
              {:id :c2 :timeline/start 8.0 :timeline/duration 4.0}]
             (:items g)) "children unchanged"))
    (let [g (norm-item {:id :g :type :group :items [{:id :c1}]})]
      (is (= [0.0 4.0] [(:timeline/start g) (:timeline/duration g)]))))
  (testing "a complete cue is returned identical"
    (let [cue {:editor-type :timeline :tracks norm-tracks
               :items [{:id :a :timeline/start 1.0 :timeline/duration 2.0 :track-id :t1}]}]
      (is (identical? cue (model/normalize-timeline-cue cue)))))
  (testing "no leaf track: one is appended and used"
    (let [cue (model/normalize-timeline-cue {:editor-type :timeline :tracks [] :items [{:id :a}]}
                                         {:new-track-id :new})]
      (is (= [{:id :new :type :track :name "Track 1" :collapsed? false}] (:tracks cue)))
      (is (= :new (get-in cue [:items 0 :track-id])))))
  (testing "non-timeline cues are untouched"
    (let [cue {:editor-type :cue-chain :items [{:id :a}]}]
      (is (identical? cue (model/normalize-timeline-cue cue))))))

(deftest normalize-migrates-disabled-tracks-to-muted-test
  (let [cue (model/normalize-timeline-cue
             {:editor-type :timeline
              :tracks [{:id :f :type :group :enabled? true
                        :items [{:id :t1 :type :track :enabled? false}]}]
              :items []})]
    (is (= [{:id :f :type :group :items [{:id :t1 :type :track :muted? true}]}]
           (:tracks cue)))))

(defn- random-cue [^java.util.Random rnd]
  (let [pick (fn [& xs] (nth xs (.nextInt rnd (count xs))))
        leaf-ids [:t1 :t2]
        item (fn [i]
               (cond-> {:id i :type (pick :preset :group)}
                 (.nextBoolean rnd) (assoc :timeline/start (pick -1.0 0.0 2.5 7.0))
                 (.nextBoolean rnd) (assoc :timeline/duration (pick 0.0 0.01 1.0 4.0))
                 (.nextBoolean rnd) (assoc :track-id (pick :t1 :t2 :f :gone nil))))]
    {:editor-type :timeline
     :tracks (pick [] [{:id :t1 :type :track}]
                   [{:id :f :type :group :enabled? (.nextBoolean rnd)
                     :items (mapv (fn [id] {:id id :type :track}) leaf-ids)}])
     :items (mapv item (range (.nextInt rnd 4)))}))

(deftest normalize-timeline-cue-idempotent-test
  (let [rnd (java.util.Random. 42)]
    (dotimes [_ 200]
      (let [cue (random-cue rnd)
            once (model/normalize-timeline-cue cue {:new-track-id :new})
            twice (model/normalize-timeline-cue once {:new-track-id :other})
            leaf-ids (model/leaf-track-ids (:tracks once))]
        (is (identical? once twice) (pr-str cue))
        (doseq [item (:items once)]
          (is (>= (:timeline/start item) 0.0))
          (is (>= (:timeline/duration item) model/min-clip-length))
          (is (contains? leaf-ids (:track-id item))))))))

(deftest convert-to-timeline-examples-test
  (testing "empty cell"
    (let [cue (model/convert-to-timeline nil {})]
      (is (= :timeline (:editor-type cue)))
      (is (= [] (:items cue)))
      (is (seq (:tracks cue)))
      (is (not (contains? cue :loop)))))
  (testing "plain cue"
    (let [cue (model/convert-to-timeline {:items [{:id :a :type :preset}]
                                       :destination-zone {:zone-group-id :all}} {})
          item (first (:items cue))]
      (is (= [0.0 16.0] [(:timeline/start item) (:timeline/duration item)]))
      (is (contains? (model/leaf-track-ids (:tracks cue)) (:track-id item)))
      (is (= {:enabled? true :start 0.0 :duration 16.0} (:loop cue)))))
  (testing "keeps an existing loop"
    (is (= {:enabled? false :start 0.0 :duration 8.0}
           (:loop (model/convert-to-timeline {:items [{:id :a}] :loop {:enabled? false :start 0.0 :duration 8.0}} {})))))
  (testing "timeline cues are returned identical"
    (let [cue {:editor-type :timeline :items []}]
      (is (identical? cue (model/convert-to-timeline cue {}))))))

(deftest muted-track-ids-includes-folder-descendants-test
  (is (= #{:f :t1 :g :t2 :t3}
         (model/muted-track-ids [{:id :f :type :group :muted? true
                               :items [{:id :t1 :type :track}
                                       {:id :g :type :group :items [{:id :t2} {:id :t3 :type :track}]}]}
                              {:id :t4 :type :track}])))
  (is (= #{:t4} (model/muted-track-ids [{:id :t4 :type :track :muted? true}]))))

(deftest parent-track-test
  (let [tracks [{:id :f :type :group :items [{:id :t1 :type :track}]} {:id :t2 :type :track}]]
    (is (= :f (:id (model/parent-track tracks :t1))))
    (is (nil? (model/parent-track tracks :t2)))
    (is (nil? (model/parent-track tracks :missing)))))

(defn- legacy-in-timeline-window?
  "frame_service/in-timeline-window? before Phase 2, copied verbatim."
  [item current-beats]
  (let [start (:timeline/start item)
        duration (:timeline/duration item)]
    (if (and (nil? start) (nil? duration))
      true
      (let [s (or start 0.0)]
        (and (>= current-beats s)
             (or (nil? duration)
                 (< current-beats (+ s duration))))))))

(deftest active-at?-matches-legacy-window-test
  (doseq [item [{} {:timeline/start 2.0} {:timeline/duration 3.0}
                {:timeline/start 2.0 :timeline/duration 3.0}
                {:timeline/start 0.0 :timeline/duration 0.0625}]
          beats [0.0 1.9 2.0 3.0 4.999 5.0 100.0]]
    (is (= (legacy-in-timeline-window? item beats) (model/active-at? item beats))
        (pr-str item beats))))

;; apply-op

(def ^:private q {:snap {:enabled? true :value 0.25}})

(defn- op-cue [& clips]
  {:editor-type :timeline
   :tracks [{:id :f :type :group :items [{:id :t1 :type :track}]}
            {:id :t2 :type :track}]
   :items (vec clips)})

(defn- clip [id start len track]
  {:id id :type :preset :preset-id :circle :timeline/start start :timeline/duration len :track-id track})

(defn- by-id [cue] (into {} (map (juxt :id identity)) (:items cue)))

(deftest apply-op-examples-test
  (testing "move: delta is clamped so the earliest clip stays at >= 0, same delta for all"
    (let [r (by-id (model/apply-op (op-cue (clip :a 1.0 4.0 :t1) (clip :b 6.0 2.0 :t1))
                                   {:op :move-clips :clip-ids #{:a :b} :delta -3.0} q))]
      (is (= 0.0 (get-in r [:a :timeline/start])))
      (is (= 5.0 (get-in r [:b :timeline/start])))))
  (testing "move to a folder is ignored"
    (let [c (op-cue (clip :a 1.0 4.0 :t1))]
      (is (identical? c (model/apply-op c {:op :move-clips :clip-ids #{:a} :delta 0.0 :to-track :f} q)))))
  (testing "move to a leaf changes the track"
    (is (= :t2 (get-in (by-id (model/apply-op (op-cue (clip :a 1.0 4.0 :t1))
                                               {:op :move-clips :clip-ids #{:a} :delta 0.0 :to-track :t2} q))
                       [:a :track-id]))))
  (testing "deleting a track deletes its clips"
    (let [r (model/apply-op (op-cue (clip :a 0.0 4.0 :t1) (clip :b 0.0 4.0 :t2))
                            {:op :delete-tracks :track-ids #{:t2}} q)]
      (is (= [:a] (mapv :id (:items r))))
      (is (nil? (model/find-track (:tracks r) :t2)))))
  (testing "duplicate places copies right after the span of the originals"
    (let [r (model/apply-op (op-cue (clip :a 0.0 4.0 :t1) (clip :b 4.0 2.0 :t1))
                            {:op :duplicate-clips :clip-ids #{:a :b}} q)
          copies (drop 2 (:items r))]
      (is (= 4 (count (:items r))))
      (is (= [6.0 10.0] (mapv :timeline/start copies)))
      (is (= [4.0 2.0] (mapv :timeline/duration copies)))
      (is (every? #(= :t1 (:track-id %)) copies))
      (is (not-any? #{:a :b} (map :id copies)) "fresh ids")))
  (testing "add-clip snaps the start and uses the first leaf"
    (let [r (model/apply-op (op-cue) {:op :add-clip :clip-id :new :preset-id :circle :start 3.1} q)
          c (first (:items r))]
      (is (= [:new 3.0 4.0 :t1] [(:id c) (:timeline/start c) (:timeline/duration c) (:track-id c)]))))
  (testing "unknown ids leave the cue unchanged"
    (let [c (op-cue (clip :a 1.0 4.0 :t1))]
      (doseq [op [{:op :move-clips :clip-ids #{:zz} :delta 1.0}
                  {:op :trim-start :clip-id :zz :to 0.0}
                  {:op :delete-tracks :track-ids #{:zz}}
                  {:op :rename-track :track-id :zz :name "x"}
                  {:op :no-such-op}]]
        (is (identical? c (model/apply-op c op q)) (pr-str op))))))

(deftest move-clips-keeps-relative-offsets-test
  (let [rnd (java.util.Random. 7)]
    (dotimes [_ 100]
      (let [starts (repeatedly 3 #(* 0.25 (.nextInt rnd 40)))
            c (apply op-cue (map-indexed (fn [i s] (clip i s 1.0 :t1)) starts))
            delta (- (* 0.5 (.nextInt rnd 40)) 10.0)
            r (model/apply-op c {:op :move-clips :clip-ids #{0 1 2} :delta delta} q)
            before (mapv :timeline/start (:items c))
            after (mapv :timeline/start (:items r))]
        (is (apply = (map - after before)) (pr-str before delta after))
        (is (every? #(>= % 0.0) after))))))

(deftest delete-tracks-deletes-clips-test
  (let [r (model/apply-op (op-cue (clip :a 0.0 4.0 :t1) (clip :b 0.0 4.0 :t2))
                          {:op :delete-tracks :track-ids #{:f}} q)]
    (is (= [:b] (mapv :id (:items r))) "deleting a folder deletes the clips of its tracks")
    (is (= [:t2] (mapv :id (:tracks r))))))

(defn- random-op [^java.util.Random rnd cue]
  (let [pick (fn [xs] (when (seq xs) (nth (vec xs) (.nextInt rnd (count xs)))))
        clip-ids (map :id (:items cue))
        track-ids (map :id (tree-seq #(= :group (:type %)) :items {:type :group :items (:tracks cue)}))
        beats #(- (* 0.25 (.nextInt rnd 80)) 4.0)]
    (case (.nextInt rnd 12)
      0 {:op :move-clips :clip-ids (set (take 2 (shuffle clip-ids))) :delta (beats) :to-track (pick track-ids)}
      1 {:op :trim-start :clip-id (pick clip-ids) :to (beats)}
      2 {:op :trim-end :clip-id (pick clip-ids) :to (beats)}
      3 {:op :delete-clips :clip-ids #{(pick clip-ids)}}
      4 {:op :duplicate-clips :clip-ids (set (take 2 clip-ids))}
      5 {:op :add-clip :preset-id :circle :track-id (pick track-ids) :start (beats)}
      6 {:op :add-track :parent-id (pick track-ids)}
      7 {:op :add-folder}
      8 {:op :delete-tracks :track-ids #{(pick (rest track-ids))}}
      9 {:op :set-clip-track :clip-id (pick clip-ids) :track-id (pick track-ids)}
      10 {:op :move-tracks :track-ids #{(pick (rest track-ids))} :target-id (pick (rest track-ids)) :position :into}
      11 {:op :trim-loop-end :to (beats)})))

(deftest apply-op-output-is-normalized-test
  (let [rnd (java.util.Random. 1234)]
    (loop [cue (op-cue (clip :a 0.0 4.0 :t1)) step 0]
      (when (< step 500)
        (let [op (random-op rnd cue)
              cue' (model/apply-op cue op q)
              leaves (model/leaf-track-ids (:tracks cue'))]
          (is (identical? cue' (model/normalize-timeline-cue cue')) (pr-str op))
          (doseq [c (:items cue')]
            (is (contains? leaves (:track-id c)) (pr-str op c)))
          (recur cue' (inc step)))))))

(deftest op-label-test
  (is (= "Move Clip" (model/op-label {:op :move-clips :clip-ids #{:a} :delta 1.0})))
  (is (= "Move 3 Clips" (model/op-label {:op :move-clips :clip-ids #{:a :b :c} :delta 1.0})))
  (is (= "Move Clip to Track" (model/op-label {:op :move-clips :clip-ids #{:a} :delta 0.0 :to-track :t})))
  (is (= "Trim Clip" (model/op-label {:op :trim-start})))
  (is (= "Delete Track" (model/op-label {:op :delete-tracks :track-ids #{:t}})))
  (is (= "Edit Loop" (model/op-label {:op :move-loop})))
  (doseq [op model/op-types]
    (is (string? (model/op-label {:op op})))))
