(ns laser-show.timeline.shows
  "The timeline show library and the key its shows play under. Pure.

   Shows live at [:chains :shows] as {show-id show}: show-id a uuid, a show a
   timeline cue map (laser-show.timeline.model) that holds no :id of its own
   (plans/2026-11-25-timeline-shows). A show plays under the playback key
   [:show id], beside the grid's [col row] cells, in [:playback
   :active-cues], [:playback :chase-sources :cells], [:playback
   :chase-stopped] and the show clock's runs, chase and positions.

   cue-at is the one place that says where a playback key's cue lives, and
   key-order the one order over playback keys: plain compare throws on a
   mix of the two shapes (it compares :show with a number)."
  (:require [clojure.string :as str]
            [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.timeline.model :as model]
            [laser-show.timeline.timecode :as tc]))


;; The playback key

(defn play-key
  "The playback key of show id: [:show id]."
  [id]
  [:show id])

(defn play-key?
  "Is k a show's playback key: exactly [:show uuid]?"
  [k]
  (and (vector? k) (= 2 (count k)) (= :show (nth k 0)) (uuid? (nth k 1))))

(defn grid-cell?
  "Is k a grid cell's playback key: [int int]?"
  [k]
  (and (vector? k) (= 2 (count k)) (int? (nth k 0)) (int? (nth k 1))))

(defn key-show-id
  "The show id of a show's playback key, else nil."
  [k]
  (when (play-key? k) (nth k 1)))

(defn show-path
  "Where show id lives in app state."
  [id]
  [:chains :shows id])

(defn cue-path
  "Where the cue a playback key plays lives: a show's under [:chains
   :shows], anything else under [:chains :cue-chains] (the grid)."
  [k]
  (if (play-key? k)
    (show-path (key-show-id k))
    [:chains :cue-chains k]))

(defn new-show
  "An empty show named name (\"Untitled\" when blank): a timeline with no
   clips, made as an empty grid cell's was (model/convert-to-timeline: a
   Default folder holding one track), normalized. No :id: its id is its key
   in the library."
  [{:keys [name]}]
  (assoc (model/convert-to-timeline nil {})
         :name (if (and (string? name) (not (str/blank? name))) (str/trim name) "Untitled")))

(defn cue-at
  "The cue the playback key k plays in state, or nil."
  [state k]
  (when (some? k)
    (get-in state (cue-path k))))

(defn- key-rank ^long [k]
  (cond (grid-cell? k) 0
        (play-key? k) 1
        :else 2))

(defn key-order
  "Comparator over playback keys: grid cells first, in compare's order as
   today; then show keys, by their id's string; anything else (nil, a key
   of another shape) last, by its printed form. Total: it never throws."
  [a b]
  (let [ra (key-rank a)
        rb (key-rank b)]
    (if (not= ra rb)
      (compare ra rb)
      (case ra
        0 (compare a b)
        1 (compare (str (key-show-id a)) (str (key-show-id b)))
        (compare (pr-str a) (pr-str b))))))


;; Shows that follow timecode

(defn- follows-mtc? [cue]
  (and (cue-chains/timeline-cue? cue) (= :mtc (:timeline/chase cue))))

(defn playable-shows
  "{(play-key id) show} for every show that follows MIDI timecode: the cues
   the show clock's chase walks (show-time/chase). Only followers, as
   playback/apply-chase visits every entry it is given. Until the grid
   holds no timelines (plans/2026-11-25-timeline-shows S2), grid cells whose
   cue follows timecode are merged in too, keyed by their cell."
  [state]
  (let [grid (reduce-kv (fn [acc cell cue] (if (follows-mtc? cue) (assoc acc cell cue) acc))
                        {}
                        (or (get-in state [:chains :cue-chains]) {}))]
    (reduce-kv (fn [acc id show] (if (follows-mtc? show) (assoc acc (play-key id) show) acc))
               grid
               (or (get-in state [:chains :shows]) {}))))


;; Timecode ranges (plans/2026-11-25-timeline-shows/07 §4.4)

(def ^:private tc-eps
  "Seconds two ranges may share and still not overlap: float slack."
  1e-6)

(defn tc-range
  "The timecode a show plays over when it follows MTC: {:start :end :fps},
   in plain seconds, [start, end). start is :timeline/tc-start (0.0 when
   missing), end start plus its length (model/effective-length) at its
   locked tempo. nil without a locked tempo, or when its length is 0. The
   :loop is ignored: a chased show never loops."
  [show]
  (let [bpm (:timeline/tempo-bpm show)
        len (model/effective-length show)
        start (or (:timeline/tc-start show) 0.0)]
    (when (and (number? bpm) (pos? bpm) (number? len) (pos? len) (number? start))
      (let [s (double start)]
        {:start s
         :end (+ s (tc/beats->seconds len bpm))
         :fps (tc/cue-fps show)}))))

(defn tc-overlap?
  "Do ranges a and b ({:start :end}, half-open seconds) overlap by more
   than 1 µs? Ends that touch don't. No frame snapping, no 24 h wrap."
  [a b]
  (and (< (double (:start a)) (- (double (:end b)) tc-eps))
       (< (double (:start b)) (- (double (:end a)) tc-eps))))

(defn- by-id-str
  "[id x] pairs in (str id) order."
  [pairs]
  (sort-by (comp str key) pairs))

(defn- enabled-ranges
  "{id range} for the shows that follow MTC and have a range."
  [shows]
  (reduce-kv (fn [acc id show]
               (if-let [r (and (follows-mtc? show) (tc-range show))] (assoc acc id r) acc))
             {}
             (or shows {})))

(defn- problem-against
  "Why show can't follow timecode beside others ([id range] pairs, in the
   order to check), or nil."
  [show others]
  (let [bpm (:timeline/tempo-bpm show)]
    (if-not (and (number? bpm) (pos? bpm))
      {:reason :no-bpm}
      (if-let [r (tc-range show)]
        (some (fn [[id2 r2]] (when (tc-overlap? r r2) {:reason :overlap :with id2 :range r2}))
              others)
        {:reason :empty}))))

(defn timecode-problem
  "Why show id can't follow timecode, checked against every other show that
   follows MTC, in (str id) order: {:reason :no-bpm} without a locked tempo,
   {:reason :empty} when its length is 0, {:reason :overlap :with id2
   :range (tc-range id2's show)} for the first it overlaps; nil when it
   can (or there is no show id). Whether id itself follows doesn't matter.
   TS-6 (plans/2026-11-25-timeline-shows/07 §4.4)."
  [shows id]
  (when-let [show (get shows id)]
    (problem-against show (by-id-str (dissoc (enabled-ranges shows) id)))))

(defn invalid-timecode-ids
  "The changed shows (ids in changed-ids) that follow MTC but break TS-6,
   as [[id problem] …], problem as timecode-problem's. Each is checked,
   in (str id) order, against a kept set: every unchanged show that follows,
   plus each changed one already checked that passed. So of two changed
   shows that overlap, only the later is reported. 07 §4.4, §7.3."
  [shows changed-ids]
  (let [ranges (enabled-ranges shows)
        changed (->> (set changed-ids)
                     (filter #(follows-mtc? (get shows %)))
                     (sort-by str))]
    (loop [kept (reduce dissoc ranges changed)
           [id & more :as ids] changed
           bad []]
      (if (empty? ids)
        bad
        (if-let [p (problem-against (get shows id) (by-id-str kept))]
          (recur kept more (conj bad [id p]))
          (recur (assoc kept id (get ranges id)) more bad))))))

(defn invariant-violations
  "Every breach of TS-6 among the shows that follow MTC, as [[id problem] …]
   (problem as timecode-problem's): no locked tempo, empty, or overlapping
   an earlier-starting one. [] when they all hold. For tests."
  [shows]
  (let [enabled (by-id-str (filter #(follows-mtc? (val %)) (or shows {})))
        unranged (keep (fn [[id show]] (when-let [p (problem-against show nil)] [id p])) enabled)
        ranged (sort-by (juxt (comp :start second) (comp str first))
                        (keep (fn [[id show]] (when-let [r (tc-range show)] [id r])) enabled))]
    (into (vec unranged)
          (second
           (reduce (fn [[latest bad] [id r :as entry]]
                     ;; latest: the [id range] seen so far that ends last
                     (let [[lid lr] latest]
                       [(if (or (nil? latest) (> (:end r) (:end lr))) entry latest)
                        (if (and latest (tc-overlap? r lr))
                          (conj bad [id {:reason :overlap :with lid :range lr}])
                          bad)]))
                   [nil []]
                   ranged)))))


;; The table (plans/2026-11-25-timeline-shows/01 §3, 06 §4)

(def ^:private fallback-bpm
  "The tempo an unlocked show is worked out at when no global BPM is given,
   as tc/show-seconds does."
  120.0)

(defn- locked-bpm
  "Show's locked tempo (:timeline/tempo-bpm) when it is a positive number."
  [show]
  (let [b (:timeline/tempo-bpm show)]
    (when (and (number? b) (pos? b)) (double b))))

(defn- usable-bpm [global-bpm]
  (if (and (number? global-bpm) (pos? global-bpm)) (double global-bpm) fallback-bpm))

(defn- length-beats [show]
  (let [len (model/effective-length show)]
    (if (number? len) (max 0.0 (double len)) 0.0)))

(defn duration-s
  "How long show plays: {:seconds :approx?}. Its length
   (model/effective-length) at its locked tempo, or at global-bpm with
   :approx? true while it has none. A show of length 0 is 0 s at any tempo,
   so not approximate."
  [show global-bpm]
  (let [len (length-beats show)
        locked (locked-bpm show)]
    {:seconds (tc/beats->seconds len (or locked (usable-bpm global-bpm)))
     :approx? (and (nil? locked) (pos? len))}))

(defn- text-value [v]
  (when (and (string? v) (not (str/blank? v))) v))

(defn- row [id show]
  (let [info (:show/info show)
        {:keys [seconds approx?]} (duration-s show nil)
        start (:timeline/tc-start show)]
    {:id id
     :title (text-value (:name show))
     :artist (text-value (:artist info))
     :album (text-value (:album info))
     :bpm (locked-bpm show)
     :key (text-value (:key info))
     :genre (text-value (:genre info))
     :length-beats (length-beats show)
     :duration-s (when-not approx? seconds)
     :approx? approx?
     :tc-start-s (if (number? start) (double start) 0.0)
     :tc-end-s (:end (tc-range show))
     :fps (tc/cue-fps show)
     :timecode? (= :mtc (:timeline/chase show))
     :problem nil}))

(defn rows
  "One row map per show in the library ({id show}), for the Shows table
   (01 §3.1). Blank text is nil. No global BPM: an unlocked show's
   :duration-s is nil (:approx? true), and whoever shows, filters or sorts
   it works it out from :length-beats at the BPM it has."
  [shows]
  (mapv (fn [[id show]] (row id show)) shows))

(defn- row-seconds
  "A row's duration in seconds: its own, or its length at global-bpm."
  [r global-bpm]
  (or (:duration-s r)
      (some-> (:length-beats r) (tc/beats->seconds (usable-bpm global-bpm)))))


;; Search (01 §3.4)

(def scopes
  "The search scopes, in the scope menu's order."
  [:all :name :artist :album :key :genre :bpm :duration])

(def ^:private text-columns [:title :artist :album :key :genre])

(defn- tokens
  "query trimmed, lower-cased and split on whitespace. In a number scope a
   range typed with spaces (\"120 - 128\", \"120 +\") is one token."
  [query scope]
  (let [q (str/lower-case (str/trim (str query)))
        q (if (#{:bpm :duration} scope)
            (-> q (str/replace #"\s*-\s*" "-") (str/replace #"\s+\+" "+"))
            q)]
    (remove str/blank? (str/split q #"\s+"))))

(defn- parse-number [s]
  (when (re-matches #"\d+(\.\d+)?|\.\d+" s) (Double/parseDouble s)))

(defn- parse-duration
  "\"m:ss\" or plain seconds, in seconds."
  [s]
  (if-let [[_ m ss] (re-matches #"(\d+):([0-5]\d)" s)]
    (+ (* 60.0 (Long/parseLong m)) (Long/parseLong ss))
    (parse-number s)))

(defn- range-pred
  "The test token sets on a number: n (within tol of it), a-b (between them,
   either order) or a+ (at least a); nil when it doesn't parse."
  [parse tol token]
  (cond
    (str/ends-with? token "+")
    (when-let [a (parse (subs token 0 (dec (count token))))]
      (fn [v] (>= v a)))

    (str/includes? token "-")
    (let [[a b] (str/split token #"-" 2)
          a (some-> a parse)
          b (some-> b parse)]
      (when (and a b)
        (let [lo (min a b) hi (max a b)]
          (fn [v] (<= lo v hi)))))

    :else
    (when-let [n (parse token)]
      (fn [v] (< (Math/abs (- (double v) n)) tol)))))

(defn- bpm-pred [token]
  (range-pred parse-number 0.5 token))

(defn- duration-pred
  "Durations compare by the whole seconds the Duration column shows (m:ss),
   so \"5:20\" finds a show of 320.4 s."
  [token]
  (if (re-find #"[-+]" token)
    (when-let [p (range-pred parse-duration 0.0 token)]
      (fn [s] (p (Math/floor s))))
    (when-let [n (parse-duration token)]
      (fn [s] (== n (Math/floor s))))))

(defn- text-pred [columns token]
  (fn [r] (some #(some-> (get r %) str/lower-case (str/includes? token)) columns)))

(defn- token-pred [scope token global-bpm]
  (case scope
    :bpm (if-let [p (bpm-pred token)]
           (fn [r] (some-> (:bpm r) p))
           (constantly false))
    :duration (if-let [p (duration-pred token)]
                (fn [r] (some-> (row-seconds r global-bpm) p))
                (constantly false))
    (text-pred (case scope
                 (:all nil) text-columns
                 :name [:title]
                 [scope])
               token)))

(defn filter-rows
  "The rows that match query in scope (01 §3.4): every whitespace token of
   it, case-insensitive. A text scope (:all, :name for the title, :artist,
   :album, :key, :genre) finds it inside that field, :all inside any of
   them. :bpm and :duration take n, a-b or a+ (BPM a number, duration m:ss
   or seconds); a row with no BPM never matches :bpm, and an unlocked row's
   duration is its length at global-bpm. A token that doesn't parse matches
   nothing (query-problem). An empty query keeps every row."
  [rows query scope global-bpm]
  (let [toks (tokens query scope)]
    (if (empty? toks)
      (vec rows)
      (let [preds (mapv #(token-pred scope % global-bpm) toks)]
        (filterv (fn [r] (every? #(% r) preds)) rows)))))

(defn query-problem
  "nil, or :unparsed when a token of query doesn't parse in a number scope
   (:bpm, :duration): the search field is marked invalid."
  [query scope]
  (when-let [parse (case scope :bpm bpm-pred :duration duration-pred nil)]
    (when (some #(nil? (parse %)) (tokens query scope))
      :unparsed)))


;; Sort (01 §3.3)

(def sort-columns
  "The row keys the table sorts by."
  #{:title :artist :album :bpm :key :genre :duration-s :tc-start-s :tc-end-s :timecode?})

(defn- sort-value
  "What row sorts on in column, or nil when it is empty."
  [column global-bpm r]
  (case column
    (:bpm :tc-start-s :tc-end-s) (some-> (get r column) double)
    :duration-s (row-seconds r global-bpm)
    :timecode? (if (:timecode? r) 0 1)
    (some-> (get r column) str/lower-case)))

(defn- compare-empty-last [a b]
  (cond (and (nil? a) (nil? b)) 0
        (nil? a) 1
        (nil? b) -1
        :else (compare a b)))

(defn sort-rows
  "rows in the table's order for {:keys [column dir]} (01 §3.3): text
   case-insensitive, numbers as numbers, TC ticked first ascending; empty
   values last whichever the direction; ties by title (ascending, untitled
   last), then the id's string, so the order is total. An unlocked row's
   duration is its length at global-bpm. An unknown column sorts by title."
  [rows {:keys [column dir]} global-bpm]
  (let [column (if (contains? sort-columns column) column :title)
        desc? (= :desc dir)
        decorated (mapv (fn [r]
                          [(sort-value column global-bpm r)
                           (some-> (:title r) str/lower-case)
                           (str (:id r))
                           r])
                        rows)
        cmp (fn [[va ta ia] [vb tb ib]]
              (let [c (cond (and (nil? va) (nil? vb)) 0
                            (nil? va) 1
                            (nil? vb) -1
                            desc? (compare vb va)
                            :else (compare va vb))]
                (if (zero? c)
                  (let [c (compare-empty-last ta tb)]
                    (if (zero? c) (compare ia ib) c))
                  c)))]
    (mapv peek (sort cmp decorated))))

(defn visible-ids
  "The ids of the shows in state's library the Shows table shows, in its
   order: filtered by [:ui :shows] :query and :scope, sorted by its :sort,
   unlocked durations at [:timing :bpm]. The order subs/visible-show-rows
   shows, for handlers (next, previous, selection)."
  [state]
  (let [{:keys [query scope] sort-spec :sort} (get-in state [:ui :shows])
        global-bpm (get-in state [:timing :bpm])
        shown (filter-rows (rows (get-in state [:chains :shows])) query scope global-bpm)]
    (mapv :id (sort-rows shown sort-spec global-bpm))))
