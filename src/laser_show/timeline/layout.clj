(ns laser-show.timeline.layout
  "Pure layout for the timeline editor: one source of row positions for the
   header column, the lanes and hit-testing, plus the viewport maths.
   
   Coordinates:
   - content y: 0 = top of the first row (rows stack with :row-gap between)
   - canvas y:  0 = top of the lane canvas; the ruler is the first
                (:ruler-h metrics) px, lanes start below it
   - beats map to canvas x through the viewport:
       view = {:zoom px-per-beat :scroll-beat b :scroll-y px :width px :height px}
     where :width/:height are the lane canvas size (ruler included)."
  (:require [laser-show.timeline.model :as model]
            [laser-show.timeline.timecode :as tc]))


(def metrics
  {:ruler-h 36
   :track-h 36
   :folder-h 26
   :row-gap 1
   :header-w 220
   :edge-grab-px 6
   :min-clip-px 6
   :loop-band-h 6
   :summary-h 8
   :clip-inset-y 3
   :indent-px 14})

(def zoom-bounds {:min 2.0 :max 400.0})


;; ============================================================
;; Rows
;; ============================================================


(defn track-colors
  "{track-id -> hex}: each leaf track's :color, else the palette colour for
   its position among the leaf tracks (depth-first)."
  [tracks]
  (let [leaves (filter model/leaf-track?
                       (tree-seq model/track-group? #(:items % []) {:type :group :items tracks}))]
    (into {}
          (map-indexed (fn [i t] [(:id t) (or (:color t) (model/palette-color i))]))
          leaves)))

(defn- sort-clips [clips]
  (vec (sort-by #(double (or (model/clip-start %) 0.0)) clips)))

(defn rows
  "Display rows for the tracks tree, top to bottom:
   [{:id :kind (:track | :folder) :depth :y :h :track :color :muted?
     :zone-id :zone-inherited? :clips :summary-clips}]
   :clips are the clips on a track row (by start). Folder rows carry every
   descendant clip as :summary-clips; a collapsed folder hides its children.
   :zone-id is the effective zone (nil = the cue's default zone)."
  ([tracks clips] (rows tracks clips metrics))
  ([tracks clips {:keys [track-h folder-h row-gap]}]
   (let [by-track (group-by :track-id clips)
         colors (track-colors tracks)
         muted (model/muted-track-ids tracks)
         descendant-clips (fn [folder]
                            (sort-clips
                             (mapcat #(get by-track (:id %))
                                     (tree-seq model/track-group? #(:items % []) folder))))]
     (loop [todo (map (fn [t] [t 0 nil]) tracks)
            y 0
            out []]
       (if-let [[track depth parent-zone] (first todo)]
         (let [folder? (model/track-group? track)
               h (if folder? folder-h track-h)
               zone (or (:zone-group-id track) parent-zone)
               row {:id (:id track)
                    :kind (if folder? :folder :track)
                    :depth depth
                    :y y
                    :h h
                    :track track
                    :color (get colors (:id track))
                    :muted? (contains? muted (:id track))
                    :zone-id zone
                    :zone-inherited? (nil? (:zone-group-id track))
                    :clips (if folder? [] (sort-clips (get by-track (:id track))))
                    :summary-clips (if folder? (descendant-clips track) [])}
               children (when (and folder? (not (:collapsed? track)))
                          (map (fn [c] [c (inc depth) zone]) (:items track [])))]
           (recur (concat children (rest todo)) (+ y h row-gap) (conj out row)))
         out)))))

(defn content-height
  "Height of all rows (content px)."
  [rows]
  (if-let [r (peek rows)] (+ (:y r) (:h r)) 0))

(defn content-beats
  "Scrollable length in beats: the last clip or loop end (at least 16), plus 16."
  [cue]
  (let [clip-end (reduce max 0.0 (keep model/clip-end (:items cue)))
        {:keys [enabled? start duration]} (:loop cue)
        loop-end (if enabled? (+ (double (or start 0.0)) (double (or duration 0.0))) 0.0)]
    (+ 16.0 (max 16.0 clip-end loop-end))))


;; ============================================================
;; Viewport
;; ============================================================


(defn beat->x [{:keys [zoom scroll-beat]} beat]
  (* (- (double beat) (double scroll-beat)) (double zoom)))

(defn x->beat [{:keys [zoom scroll-beat]} x]
  (+ (/ (double x) (double zoom)) (double scroll-beat)))

(defn visible-beats [{:keys [zoom width]}]
  (/ (double (max 0 width)) (double zoom)))

(defn visible-beat-range [view]
  (let [b (double (:scroll-beat view))]
    [b (+ b (visible-beats view))]))

(defn lanes-height
  "Height of the lane area (canvas height minus the ruler)."
  [{:keys [height]}]
  (max 0.0 (- (double (or height 0)) (:ruler-h metrics))))

(defn row-y
  "Canvas y of a row's top edge."
  [view row]
  (+ (:ruler-h metrics) (- (double (:y row)) (double (:scroll-y view 0)))))

(defn row-at
  "The row at canvas y (lanes only), with its index: [idx row] or nil.
   The 1 px gaps between rows belong to the row above."
  [rows view y]
  (let [cy (+ (- (double y) (:ruler-h metrics)) (double (:scroll-y view 0)))]
    (when (and (>= (double y) (:ruler-h metrics)) (>= cy 0.0))
      (first (keep-indexed (fn [i r]
                             (when (< cy (+ (:y r) (:h r) (:row-gap metrics)))
                               [i r]))
                           rows)))))

(defn- clamp [x lo hi] (-> (double x) (max (double lo)) (min (double hi))))

(defn clamp-view
  "Keep zoom inside zoom-bounds and the scroll inside the content."
  [view content-beats content-height]
  (let [view (update view :zoom clamp (:min zoom-bounds) (:max zoom-bounds))
        max-beat (max 0.0 (- (double content-beats) (visible-beats view)))
        max-y (max 0.0 (- (double content-height) (lanes-height view)))]
    (-> view
        (update :scroll-beat clamp 0.0 max-beat)
        (update :scroll-y #(clamp (or % 0.0) 0.0 max-y)))))

(defn zoom-at
  "Zoom by factor keeping the beat under focal-x where it is (scroll >= 0)."
  ([view focal-x factor] (zoom-at view focal-x factor zoom-bounds))
  ([view focal-x factor {:keys [min max]}]
   (let [focal-beat (x->beat view focal-x)
         zoom (clamp (* (double (:zoom view)) (double factor)) min max)]
     (assoc view
            :zoom zoom
            :scroll-beat (clojure.core/max 0.0 (- focal-beat (/ (double focal-x) zoom)))))))

(defn scroll-by
  "Scroll by dx px (horizontal) and dy px (vertical); never before 0."
  [view dx dy]
  (-> view
      (update :scroll-beat #(clojure.core/max 0.0 (+ (double %) (/ (double dx) (double (:zoom view))))))
      (update :scroll-y #(clojure.core/max 0.0 (+ (double (or % 0.0)) (double dy))))))

(defn fit
  "Zoom so the whole content fits the width, scrolled to the start."
  [view content-beats]
  (assoc view
         :zoom (clamp (/ (double (max 1 (:width view))) (double content-beats))
                      (:min zoom-bounds) (:max zoom-bounds))
         :scroll-beat 0.0))


;; ============================================================
;; Ruler and grid
;; ============================================================


(def beats-per-bar 4)

(defn- tick-levels
  "Spacing (beats) of each tick kind at this zoom, plus which kinds get labels."
  [zoom]
  (let [zoom (double zoom)]
    (cond
      (< zoom 6.0)  {:bar 16 :label #{:bar}}
      (< zoom 24.0) {:bar 4 :beat 1 :label #{:bar}}
      (< zoom 96.0) {:bar 4 :beat 1 :label #{:bar :beat}}
      :else         {:bar 4 :beat 1 :sub 0.25 :label #{:bar :beat}})))

(defn- bar-beat-label [beat]
  (let [b (long (Math/round (double beat)))
        bar (inc (quot b beats-per-bar))
        in-bar (inc (mod b beats-per-bar))]
    (if (= 1 in-bar) (str bar) (str bar "." in-bar))))

(def ^:private time-steps
  "Candidate label spacings in seconds for the time ruler."
  [1 2 5 10 15 30 60 120 300 600 1800 3600])

(defn- time-ticks
  "Time-mode ticks: labelled every `major` seconds (at least ~70 px apart),
   minor ticks between, frame ticks at >= 400 px per second.
   opts: {:bpm :tc-start :fps}. Labels are mm:ss (h:mm:ss past an hour)."
  [view {:keys [bpm tc-start fps]}]
  (let [bpm (double (or bpm 120.0))
        tc-start (double (or tc-start 0.0))
        px-per-s (* (double (:zoom view)) (/ bpm 60.0))
        major (or (some #(when (>= (* px-per-s %) 70.0) %) time-steps) (peek time-steps))
        minor (cond
                (>= px-per-s 400.0) (/ 1.0 (double (or fps 30)))
                (>= (* px-per-s major) 280.0) (/ major 10.0)
                :else (/ major 5.0))
        [b0 b1] (visible-beat-range view)
        s0 (+ tc-start (* b0 (/ 60.0 bpm)))
        s1 (+ tc-start (* b1 (/ 60.0 bpm)))
        first-i (long (Math/floor (/ s0 minor)))
        last-i (long (Math/ceil (/ s1 minor)))
        per-major (Math/round (/ (double major) minor))]
    (into []
          (keep (fn [i]
                  (let [secs (* i minor)
                        beat (* (- secs tc-start) (/ bpm 60.0))
                        on-major? (and (pos? per-major) (zero? (mod i per-major)))
                        on-second? (< (Math/abs (- secs (Math/rint secs))) 1e-6)]
                    (when (>= beat -1e-9)
                      {:beat beat
                       :x (beat->x view beat)
                       :kind (cond on-major? :bar on-second? :beat :else :sub)
                       :label (when on-major? (tc/format-clock (Math/rint secs)))}))))
          (range first-i (inc last-i)))))

(declare bar-ticks)

(defn ruler-ticks
  "Ticks in the visible range: [{:beat :x :kind (:bar | :beat | :sub) :label}].
   One table drives both the ruler and the grid. Bars are 1-based (4/4);
   beat labels read bar.beat (\"5.3\").
   With opts {:mode :time :bpm :tc-start :fps} the ticks follow show time
   instead (see time-ticks); :bar is then a labelled time tick."
  ([view] (ruler-ticks view nil))
  ([view {:keys [mode] :as opts}]
   (if (= mode :time)
     (time-ticks view opts)
     (bar-ticks view))))

(defn- bar-ticks
  [view]
  (let [{:keys [label] :as levels} (tick-levels (:zoom view))
        step (double (or (:sub levels) (:beat levels) (:bar levels)))
        [b0 b1] (visible-beat-range view)
        first-i (long (Math/floor (/ b0 step)))
        last-i (long (Math/ceil (/ b1 step)))]
    (into []
          (keep (fn [i]
                  (let [beat (* i step)
                        whole? (< (Math/abs (- beat (Math/rint beat))) 1e-9)
                        b (Math/rint beat)
                        kind (cond
                               (and whole? (zero? (mod (long b) (long (:bar levels))))) :bar
                               (and whole? (:beat levels)) :beat
                               (:sub levels) :sub)]
                    (when (and kind (>= beat 0.0))
                      {:beat beat
                       :x (beat->x view beat)
                       :kind kind
                       :label (when (contains? label kind)
                                (if (= kind :bar)
                                  (str (inc (quot (long b) beats-per-bar)))
                                  (bar-beat-label b)))}))))
          (range first-i (inc last-i)))))


;; ============================================================
;; Hit testing
;; ============================================================


(defn clip-rect
  "[x y w h] of a clip in a row on the canvas (inset so neighbours show a gap)."
  [view row clip]
  (let [{:keys [min-clip-px clip-inset-y summary-h]} metrics
        x (beat->x view (or (model/clip-start clip) 0.0))
        w (max min-clip-px (- (* (double (or (model/clip-length clip) 0.0)) (double (:zoom view))) 2.0))
        top (row-y view row)]
    (if (= :folder (:kind row))
      [(+ x 1.0) (+ top (/ (- (:h row) summary-h) 2.0)) w summary-h]
      [(+ x 1.0) (+ top clip-inset-y) w (- (:h row) (* 2 clip-inset-y))])))

(defn clip-draw-order
  "A row's clips in draw order: by start, selected clips last (on top)."
  [row selection]
  (let [clips (if (= :folder (:kind row)) (:summary-clips row) (:clips row))
        {sel true other false} (group-by #(contains? selection (:id %)) clips)]
    (into (vec other) sel)))

(defn loop-band-y
  "[y h] of the loop band (the bottom strip of the ruler)."
  []
  (let [{:keys [ruler-h loop-band-h]} metrics]
    [(- ruler-h loop-band-h) loop-band-h]))

(defn- edge-of [x left w]
  (let [grab (min (:edge-grab-px metrics) (/ w 3.0))]
    (cond
      (< x (+ left grab)) :left
      (> x (- (+ left w) grab)) :right
      :else :center)))

(defn end-beat
  "Where the end marker is drawn, or nil: the timeline's end when it doesn't
   loop (it stops there) and has one."
  [cue]
  (let [end (model/effective-length cue)]
    (when (and (not (get-in cue [:loop :enabled?])) (pos? (double end)))
      end)))

(defn hit-test
  "What's at canvas (x, y), topmost first:
   {:type :loop :edge e}           the loop band (only its drawn strip)
   {:type :end :beat b}            the end marker, within 4 px, in the ruler
   {:type :ruler :beat b}          the rest of the ruler
   {:type :clip :clip c :row-idx i :row r :edge e}
                                   e is :left/:right/:center; summary bars
                                   on folder rows are always :center
   {:type :lane :row-idx i :row r :beat b}
   {:type :empty :beat b}          below the last row"
  [rows cue view x y selection]
  (let [{:keys [ruler-h]} metrics
        [band-y band-h] (loop-band-y)
        beat (x->beat view x)]
    (if (< y ruler-h)
      (let [{:keys [start duration] :or {start 0.0 duration 0.0}} (:loop cue)
            lx (beat->x view start)
            lw (* (double duration) (double (:zoom view)))]
        (cond
          (and (:loop cue) (>= y band-y) (< y (+ band-y band-h)) (>= x lx) (<= x (+ lx lw)))
          {:type :loop :edge (edge-of x lx lw)}

          (when-let [e (end-beat cue)] (<= (Math/abs (- (double x) (beat->x view e))) 4.0))
          {:type :end :beat (end-beat cue)}

          :else
          {:type :ruler :beat beat}))
      (if-let [[idx row] (row-at rows view y)]
        (or (some (fn [clip]
                    (let [[cx cy cw ch] (clip-rect view row clip)]
                      (when (and (>= x cx) (<= x (+ cx cw)) (>= y cy) (<= y (+ cy ch)))
                        {:type :clip :clip clip :row-idx idx :row row
                         :edge (if (= :folder (:kind row)) :center (edge-of x cx cw))})))
                  (rseq (clip-draw-order row (or selection #{}))))
            {:type :lane :row-idx idx :row row :beat beat})
        {:type :empty :beat beat}))))
