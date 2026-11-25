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
