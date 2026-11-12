(ns laser-show.views.components.visual-editors.timeline.gesture
  "Pure gesture state machine for the timeline canvas, so drag logic can be
   tested without JavaFX.
   
   A gesture starts on mouse press over a clip, a clip edge, the loop brace or
   an empty lane, follows the pointer, and on release yields at most one edit
   op (see laser-show.timeline.model/apply-op) and/or a selection change.
   Nothing is dispatched while dragging: the canvas renders (preview cue
   gesture) instead.
   
   Pointer maps: {:x :y :shift? :shortcut?} in canvas pixels.
   View maps:    a layout viewport ({:zoom :scroll-beat :scroll-y ...}) plus
                 :rows (layout/rows) and :selection.
   Hits:         layout/hit-test results."
  (:refer-clojure :exclude [update])
  (:require [laser-show.timeline.layout :as layout]
            [laser-show.timeline.model :as model]))


(def drag-threshold-px
  "Movement below this is a click, not a drag."
  3.0)


(defn- row-at
  "[idx row] at canvas y, or nil."
  [view y]
  (layout/row-at (:rows view) view y))

(defn- moved? [{:keys [origin]} {:keys [x y]}]
  (>= (Math/hypot (- (double x) (double (:x origin)))
                  (- (double y) (double (:y origin))))
      drag-threshold-px))


(defn start
  "Begin a gesture for a press. hit is the layout/hit-test result.
   Returns a gesture, or nil when the press doesn't start one.
   
   Pressing a clip that's already selected (no modifiers) keeps the selection
   so several clips can be dragged; the click collapses it on release.
   Otherwise :press-select is the selection to apply right away."
  [hit {:keys [shift? shortcut?] :as pointer} cue view]
  (let [base {:origin (select-keys pointer [:x :y]) :pointer pointer}
        selection (or (:selection view) #{})]
    (case (:type hit)
      :clip
      (let [{:keys [clip edge row-idx]} hit
            item clip
            id (:id item)
            selected? (contains? selection id)]
        (merge base
               {:clip-id id
                :origin-row row-idx
                :press-select (cond
                                shortcut? {:ids [id] :mode :toggle}
                                shift? {:ids [id] :mode :add}
                                selected? nil
                                :else {:ids [id] :mode :replace})
                :collapse-on-click? (and selected? (not shift?) (not shortcut?))}
               (case edge
                 :left {:kind :trim-start :original (model/clip-start item)}
                 :right {:kind :trim-end :original (model/clip-end item)}
                 ;; Drag the clips selected after this press, always
                 ;; including the pressed one
                 {:kind :move
                  :clip-ids (if (or selected? shift? shortcut?)
                              (conj selection id)
                              #{id})})))

      :loop
      (let [{:keys [start duration] :or {start 0.0 duration model/default-clip-length}} (:loop cue)]
        (merge base
               (case (:edge hit)
                 :left {:kind :loop-trim-start :original start}
                 :right {:kind :loop-trim-end :original (+ start duration)}
                 {:kind :loop-move :original start})))

      (:lane :empty)
      (assoc base :kind :marquee :additive? (boolean (or shift? shortcut?)))

      ;; Scrubbing the ruler moves the cursor (and a playing timeline)
      :ruler
      (assoc base :kind :scrub :beat (max 0.0 (double (:beat hit))))

      ;; Dragging the end marker sets an explicit length
      :end
      (assoc base :kind :end :original (:beat hit))

      nil)))


(defn- marquee-ids
  "Clips (top-level, on the rows between the two y's) whose span intersects
   the beats between the two x's."
  [{:keys [origin]} {:keys [x y]} view]
  (let [[b0 b1] (sort [(layout/x->beat view (:x origin)) (layout/x->beat view x)])
        [y0 y1] (sort [(double (:y origin)) (double y)])
        rows (:rows view)
        first-row (or (first (row-at view (max y0 (:ruler-h layout/metrics)))) 0)
        last-row (or (first (row-at view y1)) (dec (count rows)))]
    (into #{}
          (comp (mapcat #(:clips (nth rows % nil)))
                (filter (fn [c]
                          (let [s (double (or (model/clip-start c) 0.0))
                                e (double (or (model/clip-end c) s))]
                            (and (< s b1) (> e b0)))))
                (map :id))
          (range first-row (inc last-row)))))


(defn update
  "Follow the pointer. Sets :moved? once past the drag threshold and :op to
   the edit releasing now would commit. Shift held = no snapping."
  [gesture {:keys [x shift?] :as pointer} _cue view]
  (when gesture
    (if (= :scrub (:kind gesture))
      (assoc gesture :pointer pointer :moved? true
             :beat (max 0.0 (layout/x->beat view x)))
    (let [moved (or (:moved? gesture) (moved? gesture pointer))
          delta (/ (- (double x) (double (get-in gesture [:origin :x]))) (double (:zoom view)))
          snap? (not shift?)
          g (assoc gesture :pointer pointer :moved? moved)]
      (if-not moved
        g
        (case (:kind g)
          :move
          (let [[row-idx row] (row-at view (:y pointer))
                to-track (when (and (= 1 (count (:clip-ids g)))
                                    (some? row-idx)
                                    (not= row-idx (:origin-row g))
                                    (= :track (:kind row)))
                           (:id row))]
            (assoc g :op (cond-> {:op :move-clips :clip-ids (:clip-ids g) :anchor-id (:clip-id g)
                                  :delta delta :snap? snap?}
                           to-track (assoc :to-track to-track))))
          :trim-start (assoc g :op {:op :trim-start :clip-id (:clip-id g) :to (+ (:original g) delta) :snap? snap?})
          :trim-end (assoc g :op {:op :trim-end :clip-id (:clip-id g) :to (+ (:original g) delta) :snap? snap?})
          :loop-move (assoc g :op {:op :move-loop :delta delta :snap? snap?})
          :loop-trim-start (assoc g :op {:op :trim-loop-start :to (+ (:original g) delta) :snap? snap?})
          :loop-trim-end (assoc g :op {:op :trim-loop-end :to (+ (:original g) delta) :snap? snap?})
          :end (assoc g :op {:op :set-length :beats (max model/min-clip-length (+ (:original g) delta)) :snap? snap?})
          :marquee (assoc g :marquee-ids (marquee-ids g pointer view))
          g))))))


(defn finish
  "End the gesture on release. Returns {:op op} for an edit, {:select {...}}
   for a selection change, {:seek beat} for a scrub, or nil for nothing to do."
  [gesture]
  (when gesture
    (cond
      (= :scrub (:kind gesture))
      {:seek (:beat gesture)}

      (and (:moved? gesture) (= :marquee (:kind gesture)))
      {:select {:ids (vec (:marquee-ids gesture))
                :mode (if (:additive? gesture) :add :replace)}}

      (and (:moved? gesture) (:op gesture))
      (cond-> {:op (:op gesture)}
        (:to-track (:op gesture)) (assoc :select-track (:to-track (:op gesture))))

      (:collapse-on-click? gesture)
      {:select {:ids [(:clip-id gesture)] :mode :replace}}

      :else nil)))


(defn cancel
  "Abandon the gesture (Escape). Nothing is committed."
  [_gesture]
  nil)


(defn preview
  "The cue as it would be if the gesture were released now."
  [cue gesture opts]
  (if-let [op (and (:moved? gesture) (:op gesture))]
    (model/apply-op cue op opts)
    cue))


(defn marquee-rect
  "[x y w h] of the marquee being dragged, or nil."
  [gesture]
  (when (and (= :marquee (:kind gesture)) (:moved? gesture))
    (let [{x0 :x y0 :y} (:origin gesture)
          {x1 :x y1 :y} (:pointer gesture)]
      [(min x0 x1) (min y0 y1) (Math/abs (double (- x1 x0))) (Math/abs (double (- y1 y0)))])))
