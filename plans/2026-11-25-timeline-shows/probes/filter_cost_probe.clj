;; S0 probe 2 (plans/2026-11-25-timeline-shows/02-phases.md S0 step 2).
;; A self-contained sketch of shows/rows, filter-rows and sort-rows as
;; 01-spec.md §3.1-§3.4 specify them, over 1000 generated shows (plain maps,
;; no app code), timed with System/nanoTime. criterium isn't in deps.edn, so
;; this does its own warm-up and reports the median and p95 of many runs.
;;
;; Run (no GUI, no aliases needed):
;;   clojure -M plans/2026-11-25-timeline-shows/probes/filter_cost_probe.clj
;;
;; Not app code: S3 writes the real laser-show.timeline.shows.

(ns filter-cost-probe
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------- data

(def artists ["Daft Punk" "deadmau5" "Justice" "Moderat" "Bicep" "Floating Points"
              "Four Tet" "Jon Hopkins" "Kiasmos" "Ross From Friends" "Bonobo" "Caribou"
              nil])
(def albums ["Discovery" "Homework" "For Lack of a Better Name" "Cross" "Isles"
             "Crush" "Sixteen Oceans" "Singularity" nil])
(def genres ["House" "Techno" "Prog" "Electro" "Ambient" "Breaks" nil])
(def keys* ["Am" "F#m" "Bm" "C" "G" "Dm" "E" nil])
(def words ["One" "More" "Time" "Around" "the" "World" "Strobe" "Glue" "Ghosts"
            "Open" "Eye" "Kerala" "Atlas" "Cirrus" "Emerald" "Rush" "Night" "Day"])

(defn gen-show [^java.util.Random rnd]
  (let [pick (fn [v] (nth v (.nextInt rnd (count v))))
        locked? (< (.nextDouble rnd) 0.85)]
    {:name (when (< (.nextDouble rnd) 0.97)
             (str/join " " (repeatedly (inc (.nextInt rnd 3)) #(pick words))))
     :show/info (into {} (remove (comp nil? val))
                      {:artist (pick artists) :album (pick albums)
                       :key (pick keys*) :genre (pick genres)})
     :editor-type :timeline
     :timeline/tempo-bpm (when locked? (+ 90.0 (* 0.1 (.nextInt rnd 600))))
     :timeline/tc-start (* 1.0 (.nextInt rnd 20000))
     :timeline/tc-fps 25
     :timeline/chase (when (< (.nextDouble rnd) 0.2) :mtc)
     ;; effective-length stand-in: the real one walks :items (model/effective-length)
     :timeline/length (* 4.0 (+ 16 (.nextInt rnd 200)))}))

(defn gen-shows [n seed]
  (let [rnd (java.util.Random. seed)]
    (into {} (repeatedly n (fn [] [(java.util.UUID. (.nextLong rnd) (.nextLong rnd)) (gen-show rnd)])))))

;; ---------------------------------------------------------------- rows (§3.1, §3.2)

(defn duration-s [show global-bpm]
  (let [bpm (:timeline/tempo-bpm show)
        len (or (:timeline/length show) 0.0)]
    {:seconds (* len (/ 60.0 (or bpm global-bpm))) :approx? (nil? bpm)}))

(defn row [id show global-bpm]
  (let [{:keys [seconds approx?]} (duration-s show global-bpm)
        info (:show/info show)
        start (:timeline/tc-start show 0.0)]
    {:id id :title (:name show) :artist (:artist info) :album (:album info)
     :bpm (:timeline/tempo-bpm show) :key (:key info) :genre (:genre info)
     :duration-s seconds :approx? approx?
     :tc-start-s start :tc-end-s (when-not approx? (+ start seconds)) :fps (:timeline/tc-fps show)
     :timecode? (= :mtc (:timeline/chase show)) :problem nil}))

(defn rows [shows global-bpm]
  (mapv (fn [[id show]] (row id show global-bpm)) shows))

;; ---------------------------------------------------------------- filter (§3.4)

(def text-cols [:title :artist :album :key :genre])

(defn- parse-num [s]
  (when (re-matches #"\d+(\.\d+)?" s) (Double/parseDouble s)))

(defn- parse-dur [s]
  (if-let [[_ m ss] (re-matches #"(\d+):([0-5]\d)" s)]
    (+ (* 60.0 (Long/parseLong m)) (Long/parseLong ss))
    (parse-num s)))

(defn- range-pred
  "n, a-b or a+ → (fn [v]) or nil when the token doesn't parse."
  [parse tol tok]
  (cond
    (str/ends-with? tok "+") (when-let [a (parse (subs tok 0 (dec (count tok))))] #(>= % a))
    (str/includes? tok "-") (let [[a b] (str/split tok #"-" 2)
                                  a (parse a) b (parse b)]
                              (when (and a b) #(<= a % b)))
    :else (when-let [n (parse tok)] #(< (Math/abs (double (- % n))) tol))))

(defn- token-pred [scope tok]
  (case scope
    :all (fn [r] (some #(some-> (get r %) str/lower-case (str/includes? tok)) text-cols))
    :bpm (if-let [p (range-pred parse-num 0.5 tok)] (fn [r] (some-> (:bpm r) p)) (constantly false))
    :duration (if-let [p (range-pred parse-dur 0.5 tok)] (fn [r] (p (:duration-s r))) (constantly false))
    (let [col (if (= scope :name) :title scope)]
      (fn [r] (some-> (get r col) str/lower-case (str/includes? tok))))))

(defn filter-rows [rows query scope]
  (let [toks (remove str/blank? (str/split (str/lower-case (str/trim (or query ""))) #"\s+"))]
    (if (empty? toks)
      rows
      (let [preds (mapv #(token-pred scope %) toks)]
        (filterv (fn [r] (every? #(% r) preds)) rows)))))

;; ---------------------------------------------------------------- sort (§3.3)

(def number-cols #{:bpm :duration-s :tc-start-s :tc-end-s})

(defn- sort-val [col r]
  (let [v (get r col)]
    (cond (= col :timecode?) (when v 0) ; ticked first; unticked counts as empty
          (number-cols col) v
          :else (some-> v str/lower-case))))

(defn sort-rows [rows {:keys [column dir]}]
  (let [cmp-dir (if (= dir :desc) #(compare %2 %1) compare)
        tie (fn [a b] (let [c (compare (some-> (:title a) str/lower-case) (some-> (:title b) str/lower-case))]
                        (if (zero? c) (compare (str (:id a)) (str (:id b))) c)))]
    (vec (sort (fn [a b]
                 (let [va (sort-val column a) vb (sort-val column b)]
                   (cond (and (nil? va) (nil? vb)) (tie a b)
                         (nil? va) 1
                         (nil? vb) -1
                         :else (let [c (cmp-dir va vb)] (if (zero? c) (tie a b) c)))))
               rows))))

;; ---------------------------------------------------------------- timing

(defn- time-ns [f]
  (let [t0 (System/nanoTime)] (f) (- (System/nanoTime) t0)))

(defn bench [label f]
  (dotimes [_ 300] (f)) ; warm-up (JIT)
  (let [samples (vec (sort (repeatedly 500 #(time-ns f))))
        ms #(/ (double %) 1e6)]
    (printf "%-52s median %.3f ms  p95 %.3f ms  min %.3f ms  (rows out: %d)%n"
            label (ms (nth samples 250)) (ms (nth samples 475)) (ms (first samples)) (count (f)))
    (flush)))

;; Examples from 01-spec §3.3/§3.4 as quick self-checks
(defn self-check []
  (let [a {:id "a" :title "b" :artist "Zed" :bpm 120.0}
        b {:id "b" :title "a" :artist nil :bpm 128.0}
        c {:id "c" :title "c" :artist "adam" :bpm nil}
        ids #(mapv :id %)]
    (assert (= ["c" "a" "b"] (ids (sort-rows [a b c] {:column :artist :dir :asc}))))
    (assert (= ["a" "c" "b"] (ids (sort-rows [a b c] {:column :artist :dir :desc}))))
    (assert (= ["b" "a" "c"] (ids (sort-rows [a b c] {:column :bpm :dir :desc}))))
    (assert (= ["b"] (ids (filter-rows [a b c] "125-130" :bpm))))
    (assert (= [] (ids (filter-rows [{:id "x" :duration-s 200.0}] "5:" :duration))))
    (assert (= ["x"] (ids (filter-rows [{:id "x" :duration-s 200.0}] "3:00-4:30" :duration))))
    (println "self-check: 01-spec §3.3/§3.4 examples pass")))

(defn -main []
  (self-check)
  (println "JVM" (System/getProperty "java.version") "| Clojure" (clojure-version)
           "|" (.availableProcessors (Runtime/getRuntime)) "cpus")
  (doseq [n [1000 5000]]
    (let [shows (gen-shows n 42)
          rs (rows shows 120.0)]
      (println (str "--- " n " shows"))
      (bench "rows only" #(rows shows 120.0))
      (bench "full: \"\" :all, sort :title asc" #(-> (rows shows 120.0) (filter-rows "" :all) (sort-rows {:column :title :dir :asc})))
      (bench "full: \"da\" :all, sort :artist asc" #(-> (rows shows 120.0) (filter-rows "da" :all) (sort-rows {:column :artist :dir :asc})))
      (bench "full: \"120-128\" :bpm, sort :title asc" #(-> (rows shows 120.0) (filter-rows "120-128" :bpm) (sort-rows {:column :title :dir :asc})))
      (bench "filter+sort only (rows memoized): \"\"" #(-> rs (filter-rows "" :all) (sort-rows {:column :title :dir :asc})))
      (bench "filter+sort only (rows memoized): \"da\" artist" #(-> rs (filter-rows "da" :all) (sort-rows {:column :artist :dir :asc})))
      (bench "filter+sort only (rows memoized): \"daft one\"" #(-> rs (filter-rows "daft one" :all) (sort-rows {:column :title :dir :asc})))))
  (shutdown-agents))

(-main)
