(ns laser-show.views.components.visual-editors.timeline-editor
  "Timeline editor: toolbar, track header column, lane canvas with a
   playhead overlay, scroll bars, inspector and preset drawer.
   
   Layout comes from laser-show.timeline.layout: the header column and the
   lane canvas draw the same rows, so they always line up. The scrollable
   area is a virtual viewport ({:zoom :scroll-beat :scroll-y :width :height})
   kept in view-local state (fx/ext-state): scroll bars, the mouse wheel and
   size changes update it directly on the FX thread, never through app
   events, so nothing can echo a stale value back. Drawing cost depends on the
   visible range, not the length of the show.
   
   Gestures (timeline.gesture) preview locally and commit one
   :timeline/apply-op on release."
  (:require [cljfx.api :as fx]
            [clj-font-awesome.core :as fa]
            [laser-show.animation.presets :as presets]
            [laser-show.animation.show-time :as show-time]
            [laser-show.css.canvas :as cc]
            [laser-show.events.core :as events]
            [laser-show.subs :as subs]
            [laser-show.timeline.layout :as layout]
            [laser-show.timeline.model :as model]
            [laser-show.timeline.playback :as playback]
            [laser-show.timeline.timecode :as tc]
            [laser-show.views.components.clock-view :as clock-view]
            [laser-show.views.components.drag-drop-cell :as dd]
            [laser-show.views.components.visual-editors.canvas-interaction :as ci]
            [laser-show.views.components.visual-editors.timeline.browser :as browser]
            [laser-show.views.components.visual-editors.timeline.device-chain :as device-chain]
            [laser-show.views.components.visual-editors.timeline.drop :as drop]
            [laser-show.views.components.visual-editors.timeline.gesture :as gesture]
            [laser-show.views.components.visual-editors.timeline.header :as header]
            [laser-show.views.components.visual-editors.timeline.inspector :as inspector])
  (:import [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.control Label]
           [javafx.scene.input Dragboard KeyCode MouseButton MouseEvent ScrollEvent]
           [javafx.scene.layout HBox Region]
           [javafx.scene.paint Color]
           [javafx.scene.text TextAlignment]))


(def ^:private m layout/metrics)

(def ^:private initial-view
  {:zoom 50.0 :scroll-beat 0.0 :scroll-y 0.0 :width 0.0 :height 0.0})


;; ============================================================
;; Drawing helpers
;; ============================================================


(defn- crisp
  "Pixel-snap a coordinate so 1 px lines are sharp."
  ^double [x]
  (+ (Math/floor (double x)) 0.5))

(defn- clip-label [clip]
  (or (:name clip)
      (:name (presets/presets-by-id (:preset-id clip)))
      (some-> (:preset-id clip) name)
      ""))

(defn- bar-beat-sub
  "Musical position readout \"bar.beat.sixteenth\" (1-based, 4/4)."
  [beats]
  (let [sixteenths (long (Math/floor (* 4.0 (double (max 0.0 beats)))))
        bar (inc (quot sixteenths 16))
        beat (inc (quot (mod sixteenths 16) 4))
        sub (inc (mod sixteenths 4))]
    (str bar "." beat "." sub)))


;; ============================================================
;; Lane canvas
;; ============================================================


(defn- draw-lanes! [^GraphicsContext gc {:keys [rows view selected-track-id insert-track-id cue]} width height]
  (let [ruler-h (:ruler-h m)
        {:keys [enabled? start duration]} (:loop cue)]
    (.setFill gc cc/tl-bg)
    (.fillRect gc 0 ruler-h width (- height ruler-h))
    (doseq [[i r] (map-indexed vector rows)
            :let [y (layout/row-y view r)]
            :when (and (< y height) (> (+ y (:h r)) ruler-h))]
      (.setFill gc (cond
                     (= :folder (:kind r)) cc/tl-lane-folder
                     (odd? i) cc/tl-lane-alt
                     :else cc/tl-lane))
      (.fillRect gc 0 y width (:h r))
      (when (= selected-track-id (:id r))
        (.setFill gc cc/tl-lane-selected)
        (.fillRect gc 0 y width (:h r)))
      (when (= insert-track-id (:id r))
        (.setFill gc (cc/color (or (:color r) "#5BA5F5")))
        (.fillRect gc 0 y 3 (:h r))))
    (when (and enabled? duration)
      (let [x0 (max 0.0 (layout/beat->x view start))
            x1 (min (double width) (layout/beat->x view (+ start duration)))]
        (when (> x1 x0)
          (.setFill gc cc/tl-loop-tint)
          (.fillRect gc x0 ruler-h (- x1 x0) (- height ruler-h)))))))

(defn- draw-grid! [^GraphicsContext gc ticks width height]
  (let [ruler-h (:ruler-h m)
        sub-wide? (let [subs (filter #(= :sub (:kind %)) ticks)]
                    (or (< (count subs) 2)
                        (>= (- (double (:x (second subs))) (double (:x (first subs)))) 12.0)))]
    (.setLineWidth gc 1.0)
    (doseq [{:keys [x kind]} ticks
            :when (and (<= 0 x width) (or (not= kind :sub) sub-wide?))]
      (.setStroke gc (case kind :bar cc/tl-grid-bar :beat cc/tl-grid-beat cc/tl-grid-sub))
      (.strokeLine gc (crisp x) ruler-h (crisp x) height))))

(defn- draw-hatch!
  "45° hatch over [x y w h] (overlapping clip spans)."
  [^GraphicsContext gc x y w h]
  (.save gc)
  (.beginPath gc)
  (.rect gc x y w h)
  (.clip gc)
  (.setStroke gc cc/tl-hatch)
  (.setLineWidth gc 1.0)
  (loop [i (- (double x) (double h))]
    (when (< i (+ x w))
      (.strokeLine gc i (+ y h) (+ i h) y)
      (recur (+ i 6.0))))
  (.restore gc))

(defn- draw-clip!
  [^GraphicsContext gc view r clip {:keys [selected? hover? dimmed? color]}]
  (let [[x y w h] (layout/clip-rect view r clip)
        ^Color base (cc/color (or color "#808080"))
        body (cond-> (cc/with-alpha base (if dimmed? 0.30 0.75))
               selected? (.brighter))
        top (if dimmed? (cc/with-alpha base 0.45) base)]
    (.setFill gc body)
    (.fillRect gc x y w h)
    (.setFill gc top)
    (.fillRect gc x y w 2)
    (when (> w 24)
      (.save gc)
      (.beginPath gc)
      (.rect gc (+ x 4) y (- w 8) h)
      (.clip gc)
      (.setFont gc cc/tl-font-clip)
      (.setTextAlign gc TextAlignment/LEFT)
      (.setFill gc (cond
                     dimmed? cc/tl-text-muted
                     (< (cc/luminance body) 0.5) cc/tl-text-light
                     :else cc/tl-text-dark))
      (.fillText gc (clip-label clip) (+ x 6) (+ y (/ h 2.0) 4))
      (when-let [n (not-empty (:effects clip))]
        (.setFont gc cc/tl-font-small)
        (.setTextAlign gc TextAlignment/RIGHT)
        (.fillText gc (str "fx " (count n)) (+ x w -6) (+ y (/ h 2.0) 4)))
      (.restore gc))
    (cond
      selected? (do (.setStroke gc cc/tl-selection)
                    (.setLineWidth gc 2.0)
                    (.strokeRect gc (+ x 1) (+ y 1) (- w 2) (- h 2)))
      hover? (do (.setStroke gc cc/tl-hover)
                 (.setLineWidth gc 1.0)
                 (.strokeRect gc (crisp x) (crisp y) (dec w) (dec h))))))

(defn- draw-overlaps!
  "Hatch spans where clips on the same track row overlap (they all play)."
  [^GraphicsContext gc view r clips]
  (let [spans (->> clips
                   (keep (fn [c] (when-let [e (model/clip-end c)] [(double (model/clip-start c)) e])))
                   (sort-by first)
                   vec)
        [_ y _ h] (when (seq clips) (layout/clip-rect view r (first clips)))]
    (doseq [i (range (count spans))
            j (range (inc i) (count spans))
            :let [[s1 e1] (spans i) [s2 e2] (spans j)
                  s (max s1 s2) e (min e1 e2)]
            :when (< s e)]
      (let [x0 (layout/beat->x view s) x1 (layout/beat->x view e)]
        (draw-hatch! gc x0 y (- x1 x0) h)))))

(defn- draw-clips! [^GraphicsContext gc {:keys [rows view selection muted-track-ids colors hover-id]} width height]
  (let [[b0 b1] (layout/visible-beat-range view)
        visible? (fn [c] (and (< (double (or (model/clip-start c) 0.0)) b1)
                              (> (double (or (model/clip-end c) 0.0)) b0)))]
    (.save gc)
    (.beginPath gc)
    (.rect gc 0 (:ruler-h m) width (- height (:ruler-h m)))
    (.clip gc)
    (doseq [r rows
            :let [y (layout/row-y view r)]
            :when (and (< y height) (> (+ y (:h r)) (:ruler-h m)))]
      (if (= :folder (:kind r))
        ;; Summary bars: every descendant clip in its track's colour
        (doseq [c (:summary-clips r) :when (visible? c)
                :let [[x cy w h] (layout/clip-rect view r c)]]
          (.setFill gc (cc/color (get colors (:track-id c) "#808080") 0.45))
          (.fillRect gc x cy w h))
        (let [clips (layout/clip-draw-order r selection)]
          (doseq [c clips :when (visible? c)]
            (draw-clip! gc view r c {:selected? (contains? selection (:id c))
                                     :hover? (= hover-id (:id c))
                                     :dimmed? (or (false? (:enabled? c))
                                                  (contains? muted-track-ids (:track-id c)))
                                     :color (:color r)}))
          (draw-overlaps! gc view r (filter visible? (:clips r))))))
    (.restore gc)))

(defn- draw-ruler! [^GraphicsContext gc {:keys [view cue]} ticks width]
  (let [ruler-h (:ruler-h m)
        [band-y band-h] (layout/loop-band-y)]
    (.setFill gc cc/tl-ruler-bg)
    (.fillRect gc 0 0 width ruler-h)
    (.setLineWidth gc 1.0)
    (doseq [{:keys [x kind label]} ticks :when (<= -40 x width)]
      (.setStroke gc (case kind :bar cc/tl-grid-bar :beat cc/tl-grid-beat cc/tl-grid-sub))
      (let [tick (case kind :bar (- ruler-h 4) :beat 8 4)]
        (.strokeLine gc (crisp x) (- band-y tick) (crisp x) band-y))
      (when label
        (.setTextAlign gc TextAlignment/LEFT)
        (if (= kind :bar)
          (do (.setFont gc cc/tl-font-ruler) (.setFill gc cc/tl-ruler-text) (.fillText gc label (+ x 3) 13))
          (do (.setFont gc cc/tl-font-small) (.setFill gc cc/tl-ruler-text-dim) (.fillText gc label (+ x 3) 25)))))
    (when-let [{:keys [enabled? start duration]} (:loop cue)]
      (let [x (layout/beat->x view start)
            w (* (double duration) (double (:zoom view)))]
        (.setFill gc (if enabled? cc/tl-loop-on cc/tl-loop-off))
        (.fillRect gc x band-y w band-h)
        ;; End grips
        (.setFill gc (if enabled? cc/tl-selection cc/tl-ruler-text-dim))
        (.fillRect gc x band-y 2 band-h)
        (.fillRect gc (- (+ x w) 2) band-y 2 band-h)))
    (.setStroke gc cc/tl-grid-beat)
    (.strokeLine gc 0 (- ruler-h 0.5) width (- ruler-h 0.5))))

(defn- draw-past-end!
  "The timeline stops at its end: shade what's after it and draw the
   (draggable) end marker."
  [^GraphicsContext gc {:keys [view cue]} width height]
  (when-let [end (layout/end-beat cue)]
    (let [x (layout/beat->x view end)]
      (when (< x width)
        (.setFill gc cc/tl-past-end)
        (.fillRect gc (max 0.0 x) 0 (- width (max 0.0 x)) height)
        (when (>= x 0)
          (.setStroke gc cc/tl-end-marker)
          (.setLineWidth gc 1.0)
          (.strokeLine gc (crisp x) 0 (crisp x) height)
          (.setFill gc cc/tl-end-marker)
          (.fillRect gc (- (crisp x) 3) 4 6 14))))))

(defn- draw-empty-state! [^GraphicsContext gc {:keys [rows view cue cursor-beat]} width height]
  (.setFont gc cc/tl-font-hint)
  (.setFill gc cc/tl-ghost-text)
  (cond
    (empty? rows)
    (do (.setTextAlign gc TextAlignment/CENTER)
        (.fillText gc "Add a track to get started" (/ width 2.0) (/ (+ height (:ruler-h m)) 2.0)))

    (empty? (:items cue))
    (let [r (or (first (filter #(= :track (:kind %)) rows)) (first rows))]
      (.setTextAlign gc TextAlignment/LEFT)
      (.fillText gc (str "Click or drag a preset from the browser to add it (cursor at bar "
                         (bar-beat-sub (or cursor-beat 0.0)) ")")
                 12 (+ (layout/row-y view r) (/ (:h r) 2.0) 4)))))

(defn- draw-drop-preview!
  "What a drag from the browser would do here: a ghost clip with a snap
   guide (preset), or the clip or lane it would land on (effect)."
  [^GraphicsContext gc {:keys [rows view]} preview width height]
  (when preview
    (let [ruler-h (:ruler-h m)
          row-by-id (fn [id] (some #(when (= id (:id %)) %) rows))]
      (.save gc)
      (.beginPath gc)
      (.rect gc 0 ruler-h width (- height ruler-h))
      (.clip gc)
      (when-let [r (row-by-id (:highlight-row preview))]
        (.setFill gc cc/tl-drop-lane)
        (.fillRect gc 0 (layout/row-y view r) width (:h r)))
      (when-let [id (:highlight-clip preview)]
        (doseq [r rows
                c (:clips r)
                :when (= id (:id c))
                :let [[x y w h] (layout/clip-rect view r c)]]
          (.setStroke gc cc/tl-drop-target)
          (.setLineWidth gc 2.0)
          (.strokeRect gc (+ x 1) (+ y 1) (- w 2) (- h 2))))
      (when-let [{:keys [row-id start length]} (:ghost preview)]
        (let [x (crisp (layout/beat->x view start))]
          (.setStroke gc cc/tl-snap-guide)
          (.setLineWidth gc 1.0)
          (.strokeLine gc x ruler-h x height)
          (when-let [r (row-by-id row-id)]
            (let [[cx cy cw ch] (layout/clip-rect view r {:timeline/start start :timeline/duration length})
                  hex (or (:color r) "#808080")]
              (.setFill gc (cc/color hex 0.35))
              (.fillRect gc cx cy cw ch)
              (.setStroke gc (cc/color hex))
              (.setLineDashes gc (double-array [4.0 3.0]))
              (.strokeRect gc (crisp cx) (crisp cy) (dec cw) (dec ch))))))
      (.restore gc))))

(defn- render-lanes!
  "Lane canvas: lanes, loop tint, grid, clips (or the drag preview), drop
   preview, ruler, loop band, marquee. The playhead and cursor are on the
   overlay canvas."
  [^Canvas canvas value drag-info]
  (let [gc (.getGraphicsContext2D canvas)
        width (.getWidth canvas)
        height (.getHeight canvas)
        value (assoc value :hover-id (:hover-id drag-info))
        ticks (layout/ruler-ticks (:view value) (:ruler-opts value))]
    (draw-lanes! gc value width height)
    (draw-grid! gc ticks width height)
    (draw-clips! gc value width height)
    (draw-drop-preview! gc value (:drop-preview drag-info) width height)
    (draw-past-end! gc value width height)
    (draw-empty-state! gc value width height)
    (draw-ruler! gc value ticks width)
    (when-let [b (:scrub-beat value)]
      (let [x (crisp (layout/beat->x (:view value) b))]
        (.setStroke gc cc/tl-cursor)
        (.setLineWidth gc 1.0)
        (.strokeLine gc x 0 x height)))
    (when-let [[x y w h] (:marquee value)]
      (.setFill gc cc/tl-marquee-fill)
      (.fillRect gc x y w h)
      (.setStroke gc cc/tl-marquee-stroke)
      (.setLineWidth gc 1.0)
      (.strokeRect gc (crisp x) (crisp y) w h))))


;; ============================================================
;; Gestures
;; ============================================================


(defn- gesture-view [value]
  (assoc (:view value) :rows (:rows value) :selection (:selection value)))

(defn- preview-value
  "The canvas value with the gesture's edit applied, for rendering mid-drag."
  [value g]
  (let [marquee (gesture/marquee-rect g)]
    (cond
      marquee (assoc value :marquee marquee
                     :selection (cond-> (set (:marquee-ids g))
                                  (:additive? g) (into (:selection value))))
      (= :scrub (:kind g)) (assoc value :scrub-beat (:beat g))
      (and (:moved? g) (:op g))
      (let [cue (gesture/preview (:cue value) g {:snap (:snap value)})]
        (assoc value
               :cue cue
               :rows (layout/rows (:tracks cue) (:items cue))
               :selection (into (:selection value) (:clip-ids g))))
      :else value)))

(defn- hit [value x y]
  (layout/hit-test (:rows value) (:cue value) (:view value) x y (:selection value)))

(defn- on-press
  "Start a gesture, or (ruler) move the cursor. Clicking a clip selects it
   (Shift adds, Ctrl/Cmd toggles); pressing an empty lane clears the
   selection (unless a modifier is held) and selects that track."
  [mx my button value {:keys [modifiers]}]
  (when (= button MouseButton/PRIMARY)
    (let [{:keys [col row view]} value
          h (hit value mx my)
          pointer (assoc modifiers :x mx :y my)
          g (gesture/start h pointer (:cue value) (gesture-view value))
          beat (max 0.0 (layout/x->beat view mx))
          select-track (when-let [r (:row h)]
                         {:event/type :timeline/select-track :col col :row row :track-id (:id r)})
          events (case (:type h)
                   :clip (cond-> []
                           (:press-select g) (conj (assoc (:press-select g) :event/type :timeline/select-items :col col :row row))
                           select-track (conj select-track))
                   (:loop :end) []
                   :ruler [{:event/type :timeline/seek :col col :row row :beat beat}]
                   (cond-> []
                     (not (or (:shift? modifiers) (:shortcut? modifiers)))
                     (conj {:event/type :timeline/clear-selection :col col :row row})
                     true (conj {:event/type :timeline/set-cursor-beat :col col :row row :beat beat})
                     select-track (conj select-track)))]
      (cond-> {:dispatch events}
        g (assoc :drag-start true
                 :drag-id (or (:clip-id g) (:kind g))
                 :drag-updates {:gesture g})))))

(defn- on-drag [mx my value {:keys [gesture modifiers]}]
  (when gesture
    (let [g (gesture/update gesture (assoc modifiers :x mx :y my) (:cue value) (gesture-view value))]
      {:drag-updates {:gesture g}
       :preview-value (preview-value value g)})))

(defn- on-release [value {:keys [gesture]}]
  (let [{:keys [col row]} value
        {:keys [op select select-track seek]} (gesture/finish gesture)]
    {:dispatch (cond-> []
                 seek (conj {:event/type :timeline/seek :col col :row row :beat seek})
                 op (conj {:event/type :timeline/apply-op :col col :row row :op op})
                 select (conj (assoc select :event/type :timeline/select-items :col col :row row))
                 select-track (conj {:event/type :timeline/select-track :col col :row row :track-id select-track}))}))

(defn- on-hover [mx my value _]
  (let [h (hit value mx my)]
    {:hover-id (when (= :clip (:type h)) (get-in h [:clip :id]))
     :cursor (case (:type h)
               (:clip :loop) (case (:edge h) :left "w-resize" :right "e-resize" "move")
               :end "e-resize"
               :ruler "hand"
               "default")}))

(defn- on-key
  "Space plays/pauses. Escape cancels a drag (or clears the selection), Delete/Backspace delete
   the selection, arrows nudge (Shift = 1/4 beat), Ctrl/Cmd+D duplicates,
   Ctrl/Cmd+A selects every clip."
  [^KeyCode key-code shift? value {:keys [dragging? modifiers]}]
  (let [{:keys [col row selection cue]} value
        shortcut? (:shortcut? modifiers)]
    (cond
      (= key-code KeyCode/SPACE)
      {:dispatch {:event/type :timeline/toggle-play :col col :row row} :consumed? true}

      (= key-code KeyCode/ESCAPE)
      (if dragging?
        {:cancel-drag? true :consumed? true}
        {:dispatch {:event/type :timeline/clear-selection :col col :row row} :consumed? true})

      (and shortcut? (= key-code KeyCode/D))
      (when (seq selection)
        {:dispatch {:event/type :timeline/apply-op :col col :row row
                    :op {:op :duplicate-clips :clip-ids selection}}
         :consumed? true})

      (and shortcut? (= key-code KeyCode/A))
      {:dispatch {:event/type :timeline/select-items :col col :row row
                  :ids (mapv :id (:items cue)) :mode :replace}
       :consumed? true}

      (or (= key-code KeyCode/DELETE) (= key-code KeyCode/BACK_SPACE))
      {:dispatch {:event/type :timeline/delete-selection :col col :row row} :consumed? true}

      (= key-code KeyCode/LEFT)
      {:dispatch {:event/type :timeline/nudge-selection :col col :row row
                  :delta-beats (if shift? -0.25 -1.0)}
       :consumed? true}

      (= key-code KeyCode/RIGHT)
      {:dispatch {:event/type :timeline/nudge-selection :col col :row row
                  :delta-beats (if shift? 0.25 1.0)}
       :consumed? true}

      :else nil)))


;; ============================================================
;; Drops from the browser
;; ============================================================


(defn- drop-at
  "The browser drop at canvas (x, y): {:event :preview} or nil."
  [^Dragboard db value x y]
  (when-let [payload (dd/read-payload db :bank-item drop/valid-payload?)]
    (drop/drop-target payload
                      (assoc (hit value x y) :beat (layout/x->beat (:view value) x))
                      {:col (:col value) :row (:row value)
                       :tracks (get-in value [:cue :tracks] [])
                       :snap (:snap value)})))

;; DnD takes over the mouse, so the hover state is cleared too (R1)
(defn- on-drop-over [x y db value _]
  (let [t (drop-at db value x y)]
    {:accept? (some? t)
     :drag-updates {:drop-preview (:preview t) :hover-id nil}}))

(defn- on-drop-exit [_ _]
  {:drag-updates {:drop-preview nil :hover-id nil}})

(defn- on-drop [x y db value _]
  {:dispatch (:event (drop-at db value x y))
   :drag-updates {:drop-preview nil :hover-id nil}})


;; ============================================================
;; Overlay canvas (playhead + cursor), follow playhead
;; ============================================================


(defn- draw-overlay! [^Canvas canvas {:keys [view cursor-beat playhead]}]
  (let [gc (.getGraphicsContext2D canvas)
        width (.getWidth canvas)
        height (.getHeight canvas)
        ruler-h (:ruler-h m)]
    (.clearRect gc 0 0 width height)
    (let [x (crisp (layout/beat->x view (or cursor-beat 0.0)))]
      (when (<= 0 x width)
        (.save gc)
        (.setStroke gc cc/tl-cursor)
        (.setLineWidth gc 1.0)
        (.setLineDashes gc (double-array [4.0 3.0]))
        (.strokeLine gc x ruler-h x height)
        (.restore gc)
        (.setStroke gc cc/tl-cursor)
        (.strokePolygon gc (double-array [(- x 4) x (+ x 4)]) (double-array [20 28 20]) 3)))
    (when playhead
      (let [x (layout/beat->x view playhead)]
        (when (<= 0 x width)
          (.setStroke gc cc/tl-playhead)
          (.setLineWidth gc 2.0)
          (.strokeLine gc x (- ruler-h 10) x height)
          (.setFill gc cc/tl-playhead)
          (.fillPolygon gc (double-array [(- x 5) (+ x 5) x])
                        (double-array [(- ruler-h 10) (- ruler-h 10) ruler-h]) 3))))))

(defn- follow!
  "When following and the playhead has left the visible page, scroll so the
   page it entered starts with the playhead at 10 % of the width. Each scroll
   is asked for once: ticks that come before the re-render see the old view
   and would ask again."
  [^Canvas canvas {:keys [view follow? swap-state]} playhead]
  (let [vis (layout/visible-beats view)]
    (when (and follow? playhead (pos? vis))
      (let [page (long (Math/floor (/ (- playhead (:scroll-beat view)) vis)))]
        (when-not (zero? page)
          (let [scroll (max 0.0 (double (- (+ (:scroll-beat view) (* page vis)) (* 0.1 vis))))
                asked [(:scroll-beat view) scroll]
                props (.getProperties canvas)]
            (when (not= asked (.get props ::asked-scroll))
              (.put props ::asked-scroll asked)
              (swap-state assoc-in [:view :scroll-beat] scroll))))))))

(defn- redraw-overlay!
  "The overlay from the show clock's tick (clock-view, on the FX thread): the
   cursor, the cue's playhead, and a page flip while following. It draws only
   when the playhead or the view moved, so a stopped or paused cue leaves the
   canvas alone."
  [^Canvas canvas snapshot {:keys [col row view cursor-beat] :as data}]
  (let [playhead (show-time/cue-beats snapshot [col row])
        drawn [playhead view cursor-beat]
        props (.getProperties canvas)]
    (when (not= drawn (.get props ::drawn))
      (.put props ::drawn drawn)
      (draw-overlay! canvas {:view view :cursor-beat cursor-beat :playhead playhead}))
    (follow! canvas data playhead)))

(defn- overlay-canvas
  "Mouse-transparent canvas over the lanes. It draws the show clock's newest
   tick itself, so playback redraws two lines, not the timeline."
  [{:keys [col row view cursor-beat follow? swap-state]}]
  {:fx/type clock-view/ext-clock-redraw
   :redraw! #'redraw-overlay!
   :desc {:fx/type :canvas
          :id "timeline-overlay"
          :managed false
          :mouse-transparent true
          :width (:width view)
          :height (:height view)
          :user-data {:col col :row row :view view :cursor-beat cursor-beat
                      :follow? follow? :swap-state swap-state}}})


;; ============================================================
;; Toolbar
;; ============================================================


(def ^:private bar-snap-options
  [["1 bar" 4.0] ["1/2" 2.0] ["1/4" 1.0] ["1/8" 0.5] ["1/16" 0.25] ["1/32" 0.125] ["1/64" 0.0625]])

(defn- snap-options
  "[label beats] snap choices: musical in bar mode, time in time mode
   (converted to beats at the cue's tempo)."
  [{:keys [mode bpm fps]}]
  (if (= mode :time)
    (mapv (fn [[label secs]] [label (tc/seconds->beats secs bpm)])
          [["1 frame" (/ 1.0 (double (or fps 30)))] ["1/4 s" 0.25] ["1 s" 1.0] ["5 s" 5.0]])
    bar-snap-options))

(defn- snap-name [options value]
  (or (some (fn [[label v]] (when (< (Math/abs (- (double v) (double value))) 1e-9) label)) options)
      (format "%.3g" (double value))))

(defn- readout-texts
  "[bar.beat.sub timecode] at beat pos of cue. ≈ marks a timecode estimated
   at the current global BPM (the tempo isn't locked)."
  [cue pos global-bpm]
  (let [{:keys [seconds approx?]} (tc/show-seconds cue pos global-bpm)]
    [(bar-beat-sub pos)
     (str (when approx? "≈") (tc/format-tc (tc/seconds->tc seconds (tc/cue-fps cue))))]))

(defn- redraw-readout!
  "The readout's labels from the show clock's tick (clock-view): the
   playhead's position while the tick has one for the cue, else the
   cursor's, which is also what cljfx renders."
  [^HBox box snapshot {:keys [col row cue cursor-beat global-bpm]}]
  (let [pos (or (show-time/cue-beats snapshot [col row]) cursor-beat 0.0)
        [bbs timecode] (readout-texts cue pos global-bpm)
        children (.getChildren box)
        ^Label bbs-label (.get children 0)
        ^Label tc-label (.get children 1)]
    (when (not= bbs (.getText bbs-label))
      (.setText bbs-label bbs))
    (when (not= timecode (.getText tc-label))
      (.setText tc-label timecode))))

(defn- readout
  "Position readout: bar.beat.sub and show timecode, from the playhead while
   playing, else the cursor. It follows the show clock's newest tick itself,
   so playback changes two labels, not the toolbar."
  [{:keys [col row cue cursor-beat global-bpm]}]
  (let [[bbs timecode] (readout-texts cue (or cursor-beat 0.0) global-bpm)
        approx? (nil? (:timeline/tempo-bpm cue))]
    {:fx/type clock-view/ext-clock-redraw
     :redraw! #'redraw-readout!
     :desc {:fx/type :h-box
            :alignment :center-left
            :spacing 6
            :user-data {:col col :row row :cue cue :cursor-beat cursor-beat :global-bpm global-bpm}
            :children [{:fx/type :label
                        :id "timeline-readout"
                        :style-class "tl-readout"
                        :text bbs}
                       {:fx/type :label
                        :id "timeline-readout-tc"
                        :style-class (cond-> ["tl-readout" "tl-readout-tc"] approx? (conj "tl-readout-approx"))
                        :text timecode}]}}))

(defn- icon-button [{:keys [id icon tooltip on-action disable]}]
  (cond-> {:fx/type :button
           :style-class ["button" "tl-icon-button"]
           :focus-traversable false
           :graphic {:fx/type fa/icon :name icon :size 12}
           :tooltip {:fx/type :tooltip :text tooltip}
           :on-action on-action}
    id (assoc :id id)
    disable (assoc :disable true)))

(defn- toggle [{:keys [id icon tooltip selected on-action]}]
  {:fx/type :toggle-button
   :id id
   :style-class ["toggle-button" "tl-toggle"]
   :focus-traversable false
   :selected (boolean selected)
   :graphic {:fx/type fa/icon :name icon :size 12 :style-class "tl-toggle-icon"}
   :tooltip {:fx/type :tooltip :text (str tooltip (if selected " (on)" " (off)"))}
   :on-action on-action})

(defn- separator [] {:fx/type :region :style-class "tl-toolbar-sep"})

(defn- mtc-chip
  "Incoming MIDI timecode, for a timeline that follows it: green while
   running, amber while freewheeling, grey when stopped. Subscribes to the
   status itself so only this label updates as timecode runs."
  [{:keys [fx/context]}]
  (let [{:keys [state text]} (fx/sub-ctx context subs/mtc-status)
        state (or state :stopped)]
    {:fx/type :label
     :id "timeline-mtc-chip"
     :style-class ["label" "tl-mtc-chip" (str "tl-mtc-" (name state))]
     :text (str "MTC " (if (= :stopped state) "—" text))
     :tooltip {:fx/type :tooltip
               :text (case state
                       :running "Following MIDI timecode"
                       :freewheel "MIDI timecode dropped out: carrying on at the last speed"
                       "No MIDI timecode coming in")}}))

(defn- toolbar
  [{:keys [col row title view snap loop-enabled? follow? inspector-open? drawer-open?
           cursor-beat zoom-view! fit! swap-state cue global-bpm transport ruler-opts]}]
  (let [log2 #(/ (Math/log (double %)) (Math/log 2.0))
        options (snap-options ruler-opts)
        locked (:timeline/tempo-bpm cue)
        playing? (= :playing transport)
        chasing? (playback/chases-mtc? cue)
        chase-tip "Following MIDI timecode"]
    {:fx/type :h-box
     :style-class "tl-toolbar"
     :children
     (filterv some?
              [title
               (when title (separator))
               (icon-button {:id "timeline-to-start" :icon :backward-step
                             :tooltip (if chasing? chase-tip "Back to start")
                             :disable chasing?
                             :on-action {:event/type :timeline/seek :col col :row row :beat 0.0}})
               (icon-button {:id "timeline-play" :icon (if playing? :pause :play)
                             :tooltip (cond chasing? chase-tip
                                            playing? "Pause (Space)"
                                            :else "Play from the cursor (Space)")
                             :disable chasing?
                             :on-action {:event/type :timeline/toggle-play :col col :row row}})
               (icon-button {:id "timeline-stop" :icon :stop :tooltip (if chasing? chase-tip "Stop")
                             :disable (or chasing? (= :stopped transport))
                             :on-action {:event/type :timeline/stop :col col :row row}})
               {:fx/type readout :col col :row row :cue cue :cursor-beat cursor-beat :global-bpm global-bpm}
               (when chasing? {:fx/type mtc-chip})
               (separator)
               {:fx/type :label
                :id "timeline-tempo"
                :style-class (cond-> ["tl-readout" "tl-tempo"] (not locked) (conj "tl-readout-approx"))
                :text (format "%.1f BPM" (double (or locked global-bpm 120.0)))}
               {:fx/type :toggle-button
                :id "timeline-tempo-lock"
                :style-class ["toggle-button" "tl-toggle"]
                :focus-traversable false
                :selected (some? locked)
                :graphic {:fx/type fa/icon :name (if locked :lock :lock-open) :size 12}
                :tooltip {:fx/type :tooltip
                          :text (if locked
                                  "Tempo locked: tap tempo and Link don't change this timeline. Click to follow the global BPM."
                                  "Follows the global BPM. Click to lock this timeline at the current tempo.")}
                :on-action {:event/type :timeline/apply-op :col col :row row
                            :op {:op :set-tempo :bpm (when-not locked global-bpm)}}}
               {:fx/type :h-box
                :children [{:fx/type :toggle-button
                            :id "timeline-ruler-bars"
                            :text "Bars"
                            :style-class ["toggle-button" "tl-toggle" "tl-segment"]
                            :focus-traversable false
                            :selected (not= :time (:mode ruler-opts))
                            :on-action {:event/type :timeline/set-ruler-mode :mode :bars}}
                           {:fx/type :toggle-button
                            :id "timeline-ruler-time"
                            :text "Time"
                            :style-class ["toggle-button" "tl-toggle" "tl-segment"]
                            :focus-traversable false
                            :selected (= :time (:mode ruler-opts))
                            :on-action {:event/type :timeline/set-ruler-mode :mode :time}}]}
               (separator)
               {:fx/type :region :h-box/hgrow :always}
               {:fx/type :button
                :id "timeline-add-track"
                :text "+ Track"
                :style-class ["button" "tl-icon-button"]
                :focus-traversable false
                :on-action {:event/type :timeline/add-track :col col :row row}}
               {:fx/type :menu-button
                :id "timeline-add-menu"
                :style-class ["menu-button" "tl-icon-button"]
                :focus-traversable false
                :tooltip {:fx/type :tooltip :text "Add a folder"}
                :items [{:fx/type :menu-item
                         :id "timeline-add-folder"
                         :text "Folder"
                         :on-action {:event/type :timeline/add-folder :col col :row row}}]}
               (separator)
               (toggle {:id "timeline-snap-toggle" :icon :magnet :tooltip "Snap to grid"
                        :selected (:enabled? snap)
                        :on-action {:event/type :timeline/set-snap :toggle? true}})
               {:fx/type :combo-box
                :id "timeline-snap-value"
                :style-class ["combo-box" "tl-snap-combo"]
                :focus-traversable false
                :items (mapv first options)
                :value (snap-name options (:value snap))
                :on-value-changed (fn [label]
                                    (when-let [v (some (fn [[l v]] (when (= l label) v)) options)]
                                      (when (not= v (:value snap))
                                        (events/dispatch! {:event/type :timeline/set-snap :value v}))))}
               (toggle {:id "timeline-loop-toggle" :icon :repeat :tooltip "Loop"
                        :selected loop-enabled?
                        :on-action {:event/type :timeline/toggle-loop :col col :row row}})
               (toggle {:id "timeline-follow-toggle" :icon :angles-right :tooltip "Follow playhead"
                        :selected follow?
                        :on-action (fn [_] (swap-state update :follow? not))})
               (separator)
               (icon-button {:id "timeline-zoom-out" :icon :minus :tooltip "Zoom out"
                             :on-action (fn [_] (zoom-view! layout/zoom-at (/ (:width view) 2.0) (/ 1.0 1.25)))})
               {:fx/type :slider
                :id "timeline-zoom-slider"
                :style-class ["slider" "tl-zoom-slider"]
                :focus-traversable false
                :min (log2 (:min layout/zoom-bounds))
                :max (log2 (:max layout/zoom-bounds))
                :value (log2 (:zoom view))
                :on-value-changed (fn [v]
                                    (let [z (Math/pow 2.0 (double v))]
                                      (when (> (Math/abs (- z (double (:zoom view)))) 1e-6)
                                        (zoom-view! layout/zoom-at (/ (:width view) 2.0) (/ z (double (:zoom view)))))))}
               (icon-button {:id "timeline-zoom-in" :icon :plus :tooltip "Zoom in"
                             :on-action (fn [_] (zoom-view! layout/zoom-at (/ (:width view) 2.0) 1.25))})
               (icon-button {:id "timeline-zoom-fit" :icon :arrows-left-right :tooltip "Fit the whole timeline"
                             :on-action (fn [_] (fit!))})
               (separator)
               (toggle {:id "timeline-add-content" :icon :layer-group :tooltip "Browser (presets and effects)"
                        :selected drawer-open?
                        :on-action {:event/type :timeline/toggle-add-panel}})
               (toggle {:id "timeline-inspector-toggle" :icon :sliders :tooltip "Detail panel (properties and effects)"
                        :selected inspector-open?
                        :on-action {:event/type :timeline/toggle-inspector}})])}))


;; ============================================================
;; Detail panel (bottom): properties + effects
;; ============================================================


(defonce ^:private detail-drag
  ;; [screen-y height lanes-height] at the press; outside render so
  ;; re-renders keep it
  (atom nil))

(defn- detail-panel
  "Bottom panel, Ableton-style: the selection's properties on the left and
   its effects as a row of cards. The strip on top drags to resize it: the
   drag previews in local state, and the release commits the height as an
   editor preference (one :timeline/set-detail-height per drag)."
  [{:keys [height lanes-height swap-state inspector-props target col row]}]
  (let [drag detail-drag
        drag-height (fn [^MouseEvent e]
                      (when-let [[y0 h0 lanes-h] @drag]
                        (layout/detail-drag-height h0 y0 (.getScreenY e) lanes-h)))]
    {:fx/type :v-box
     :id "timeline-detail"
     :style-class "tl-detail"
     :min-height height :pref-height height :max-height height
     :children
     [{:fx/type :region
       :id "timeline-detail-resize"
       :style-class "tl-detail-resize"
       :cursor :n-resize
       :on-mouse-pressed (fn [^MouseEvent e]
                           (reset! drag [(.getScreenY e) (double height) (double (or lanes-height 0.0))]))
       :on-mouse-dragged (fn [e]
                           (when-let [h (drag-height e)]
                             (swap-state assoc :detail-h h)))
       ;; The preview stays after the release: clearing it would show the old
       ;; height until the event lands. It's dropped on reopen or cell switch.
       :on-mouse-released (fn [e]
                            (when-let [h (drag-height e)]
                              (swap-state assoc :detail-h h)
                              (when (not= h (second @drag))
                                (events/dispatch! {:event/type :timeline/set-detail-height
                                                   :height h})))
                            (reset! drag nil))}
      {:fx/type :h-box
       :v-box/vgrow :always
       :children [(assoc inspector-props :fx/type inspector/inspector :target target)
                  {:fx/type device-chain/device-chain :h-box/hgrow :always
                   :col col :row row :target target}]}]}))


;; ============================================================
;; Editor
;; ============================================================


(defn- on-wheel
  "Mouse wheel over the lanes or headers: scroll vertically; Shift or a
   horizontal trackpad swipe scrolls in time; Ctrl/Cmd zooms at the pointer."
  [zoom-view! scroll-view! ^ScrollEvent e]
  (let [dx (.getDeltaX e) dy (.getDeltaY e)]
    (cond
      (or (.isShortcutDown e) (.isControlDown e))
      (zoom-view! layout/zoom-at (.getX e) (if (pos? dy) 1.15 (/ 1.0 1.15)))

      (or (.isShiftDown e) (> (Math/abs dx) (Math/abs dy)))
      (scroll-view! layout/scroll-by (- (if (zero? dx) dy dx)) 0)

      :else
      (scroll-view! layout/scroll-by 0 (- dy)))
    (.consume e)))

(defn- editor-body
  [{:keys [fx/context state swap-state col row cue timeline-ui timeline-prefs title]}]
  (let [{:keys [selection cursor-beat] :or {selection #{} cursor-beat 0.0}} timeline-ui
        snap (merge {:enabled? true :value 0.25} (:snap timeline-prefs))
        {drawer-open? :open? preset-tab :preset-tab effect-tab :effect-tab} (:add-panel timeline-prefs)
        inspector-open? (:inspector-open? timeline-prefs true)
        tracks (:tracks cue [])
        ;; Where each track and clip goes, and what each would inherit
        explain (fx/sub-ctx context subs/cue-target-explain col row)
        global-bpm (fx/sub-ctx context subs/bpm)
        transport (fx/sub-ctx context subs/timeline-transport col row)
        ruler-opts {:mode (:ruler-mode timeline-prefs :bars)
                    :bpm (playback/cue-bpm cue global-bpm)
                    :tc-start (:timeline/tc-start cue 0.0)
                    :fps (tc/cue-fps cue)}
        rows (layout/rows tracks (:items cue))
        content-beats (layout/content-beats cue)
        content-height (layout/content-height rows)
        view (layout/clamp-view (:view state) content-beats content-height)
        follow? (:follow? state true)
        zoom-view! (fn [f & args]
                     (swap-state update :view #(layout/clamp-view (apply f % args) content-beats content-height)))
        ;; Scrolling by hand stops following the playhead
        scroll-view! (fn [f & args]
                       (swap-state (fn [s]
                                     (-> s
                                         (update :view #(layout/clamp-view (apply f % args) content-beats content-height))
                                         (assoc :follow? false)))))
        fit! #(swap-state update :view (fn [v] (layout/clamp-view (layout/fit v content-beats) content-beats content-height)))
        wheel (partial on-wheel zoom-view! scroll-view!)
        tracks-ui (fx/sub-ctx context subs/list-ui-state [:timeline-tracks col row])
        selected-track-id (let [id (first (:selected-ids tracks-ui))]
                            (when (and id (model/find-track tracks id)) id))
        insert-track-id (model/insert-track tracks selected-track-id)
        lanes-h (layout/lanes-height view)
        visible (layout/visible-beats view)
        target (inspector/selection-target cue selection selected-track-id)]
    (cond->
     {:fx/type :border-pane
     :style-class "tl-root"
     :top {:fx/type toolbar
           :col col :row row :title title :view view :snap snap
           :loop-enabled? (boolean (get-in cue [:loop :enabled?]))
           :follow? follow? :inspector-open? inspector-open? :drawer-open? drawer-open?
           :cursor-beat cursor-beat :zoom-view! zoom-view! :fit! fit! :swap-state swap-state
           :cue cue :global-bpm global-bpm :transport transport :ruler-opts ruler-opts}
     :center
     {:fx/type :border-pane
      :center
      {:fx/type :h-box
       :children
       [{:fx/type header/header-column
         :rows rows :view view :col col :row row :tracks tracks
         :selected-track-id selected-track-id
         :explain explain
         :renaming-id (:renaming-id state) :swap-state swap-state
         :on-scroll wheel}
        {:fx/type fx/ext-on-instance-lifecycle
         :h-box/hgrow :always
         :on-created (fn [^Region pane]
                       (let [sync! (fn [] (swap-state update :view assoc
                                                      :width (.getWidth pane) :height (.getHeight pane)))]
                         (.addListener (.widthProperty pane) (reify javafx.beans.value.ChangeListener
                                                               (changed [_ _ _ _] (sync!))))
                         (.addListener (.heightProperty pane) (reify javafx.beans.value.ChangeListener
                                                                (changed [_ _ _ _] (sync!))))))
         :desc
         {:fx/type :pane
          :id "timeline-lanes"
          :min-width 0 :min-height 0 :pref-width 400 :pref-height 200
          :on-scroll wheel
          :children
          [{:fx/type ci/interactive-canvas
            :id "timeline-canvas"
            :style-class ["timeline-canvas"]
            :managed false
            :width (:width view)
            :height (:height view)
            :cursor "default"
            :history-transaction? false
            :value {:col col :row row
                    :cue cue
                    :rows rows
                    :view view
                    :colors (layout/track-colors tracks)
                    :selection (or selection #{})
                    :selected-track-id selected-track-id
                    :insert-track-id insert-track-id
                    :muted-track-ids (model/muted-track-ids tracks)
                    :cursor-beat cursor-beat
                    :ruler-opts ruler-opts
                    :snap snap}
            :render! render-lanes!
            :on-press on-press
            :on-drag on-drag
            :on-release on-release
            :on-hover on-hover
            :claimed-keys #{:space :escape :delete :back_space :left :right}
            :on-key on-key
            :on-drag-over on-drop-over
            :on-drag-exited on-drop-exit
            :on-drag-dropped on-drop}
           {:fx/type overlay-canvas
            :col col :row row :view view :cursor-beat cursor-beat
            :follow? follow? :swap-state swap-state}]}}
        {:fx/type :scroll-bar
         :id "timeline-vscroll"
         :style-class ["scroll-bar" "tl-scroll-bar"]
         :orientation :vertical
         :min 0.0
         :max (max 0.0 (- content-height lanes-h))
         :visible-amount (max 1.0 lanes-h)
         :unit-increment 36.0
         :block-increment (max 1.0 lanes-h)
         :value (:scroll-y view)
         :on-value-changed (fn [v]
                             (when (> (Math/abs (- (double v) (double (:scroll-y view)))) 1e-6)
                               (scroll-view! assoc :scroll-y (double v))))}]}
      :bottom
      {:fx/type :h-box
       :children [{:fx/type :region :min-width (:header-w m) :pref-width (:header-w m)}
                  {:fx/type :scroll-bar
                   :id "timeline-hscroll"
                   :style-class ["scroll-bar" "tl-scroll-bar"]
                   :h-box/hgrow :always
                   :max-width Double/MAX_VALUE
                   :orientation :horizontal
                   :min 0.0
                   :max (max 0.0 (- content-beats visible))
                   :visible-amount (max 0.01 visible)
                   :unit-increment 1.0
                   :block-increment (max 1.0 visible)
                   :value (:scroll-beat view)
                   :on-value-changed (fn [v]
                                       (when (> (Math/abs (- (double v) (double (:scroll-beat view)))) 1e-6)
                                         (scroll-view! assoc :scroll-beat (double v))))}]}}
     }
      ;; cljfx needs these props absent, not nil
      drawer-open?
      (assoc :right {:fx/type browser/browser
                     :col col :row row :preset-tab preset-tab :effect-tab effect-tab
                     :cursor-label (bar-beat-sub (or cursor-beat 0.0))
                     :insert-track-id insert-track-id
                     :insert-track-name (:name (model/find-track tracks insert-track-id))
                     :target target})
      inspector-open?
      (assoc :bottom {:fx/type detail-panel
                      :col col :row row :target target
                      ;; A drag in progress (or just released) wins over the pref
                      :height (or (:detail-h state)
                                  (:detail-height timeline-prefs)
                                  layout/default-detail-height)
                      :lanes-height (:height view)
                      :swap-state swap-state
                      :inspector-props {:col col :row row :cue cue :tracks tracks
                                        :explain explain :global-bpm global-bpm}}))))

(defn timeline-editor
  "Main timeline editor component.
   
   Props:
   - :col, :row             — grid cell of the cue
   - :cue                   — the cue chain ({:items :tracks :loop ...})
   - :timeline-ui           — this cue's view state (subs/timeline-ui)
   - :timeline-prefs        — editor preferences (subs/timeline-prefs)
   - :title                 — optional node shown at the left of the toolbar
   
   Local state (viewport, follow, renaming) resets when :cell changes, since
   it's part of :initial-state; the measured lane size carries over. The
   body gets the props without the editor's context: cljfx gives it its
   own."
  [{:keys [col row] :as props}]
  {:fx/type fx/ext-state
   ;; :cell is never read: it's here so a new cell resets the local state
   :initial-state {:cell [col row] :view initial-view :follow? true :renaming-id nil}
   ;; The lanes pane keeps its size across the reset, so its size listeners
   ;; won't fire again: keep the measured size, or the canvas stays 0x0
   :reset (fn [old new]
            (update new :view merge (select-keys (:view old) [:width :height])))
   :desc (assoc (dissoc props :fx/context) :fx/type editor-body)})
