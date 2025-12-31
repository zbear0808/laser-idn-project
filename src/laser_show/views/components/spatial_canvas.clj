(ns laser-show.views.components.spatial-canvas
  "2D spatial canvas component for visual parameter editing.
   
   Features:
   - Draggable points with real-time coordinate updates
   - Draggable polygon areas - click and drag inside to move all points together
   - Grid background with coordinate axes
   - World-to-canvas coordinate transformations
   - Support for lines, polygons, and visual feedback
   - Smart cursor feedback (hand on points, move cursor inside polygon)
   
   Usage:
   {:fx/type spatial-canvas
    :fx/key [unique-id]  ; Important: add unique key to force re-creation on context change
    :width 300
    :height 300
    :bounds {:x-min -2.0 :x-max 2.0 :y-min -2.0 :y-max 2.0}
    :points [{:id :center :x 0.0 :y 0.0 :color \"#4CAF50\" :label \"Center\"}]
    :lines [{:from :tl :to :tr :color \"#4A6FA5\" :width 2}]
    :polygon {:points [:tl :tr :br :bl] :color \"#4A6FA520\"}
    :on-point-drag {:event/type :effects/update-spatial-params ...}
    :show-grid true
    :show-axes true}"
  (:require [cljfx.api :as fx]
            [laser-show.events.core :as events])
  (:import [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.input MouseEvent]
           [javafx.scene.paint Color]
           [javafx.scene.text Font FontWeight]))


;; Coordinate Transformations


(defn- world-to-canvas
  "Convert world coordinates to canvas pixel coordinates.
   World space: [x-min, x-max] x [y-min, y-max] with Y increasing upward
   Canvas space: [0, width] x [0, height] with Y increasing downward"
  [x y width height {:keys [x-min x-max y-min y-max]}]
  (let [canvas-x (* (/ (- x x-min) (- x-max x-min)) width)
        ;; Invert Y: world Y increases up, canvas Y increases down
        canvas-y (* (/ (- y-max y) (- y-max y-min)) height)]
    [canvas-x canvas-y]))

(defn- canvas-to-world
  "Convert canvas pixel coordinates to world coordinates."
  [canvas-x canvas-y width height {:keys [x-min x-max y-min y-max]}]
  (let [x (+ x-min (* (/ canvas-x width) (- x-max x-min)))
        ;; Invert Y
        y (- y-max (* (/ canvas-y height) (- y-max y-min)))]
    [x y]))

(defn- clamp-to-bounds
  "Clamp world coordinates to bounds."
  [x y {:keys [x-min x-max y-min y-max]}]
  [(max x-min (min x-max x))
   (max y-min (min y-max y))])


;; Drawing Functions


(defn- draw-grid
  "Draw grid lines on the canvas."
  [^GraphicsContext gc width height bounds]
  (.setStroke gc (Color/web "#303030"))
  (.setLineWidth gc 0.5)
  
  (let [{:keys [x-min x-max y-min y-max]} bounds
        grid-step 0.5
        x-range (- x-max x-min)
        y-range (- y-max y-min)]
    
    ;; Vertical grid lines
    (doseq [x (range x-min (+ x-max grid-step) grid-step)]
      (let [[cx _] (world-to-canvas x 0 width height bounds)]
        (.strokeLine gc cx 0 cx height)))
    
    ;; Horizontal grid lines
    (doseq [y (range y-min (+ y-max grid-step) grid-step)]
      (let [[_ cy] (world-to-canvas 0 y width height bounds)]
        (.strokeLine gc 0 cy width cy)))))

(defn- draw-axes
  "Draw X and Y axes at origin."
  [^GraphicsContext gc width height bounds]
  (let [[origin-x origin-y] (world-to-canvas 0 0 width height bounds)]
    ;; X axis
    (.setStroke gc (Color/web "#505050"))
    (.setLineWidth gc 1.5)
    (.strokeLine gc 0 origin-y width origin-y)
    
    ;; Y axis
    (.strokeLine gc origin-x 0 origin-x height)
    
    ;; Origin marker
    (.setFill gc (Color/web "#707070"))
    (.fillOval gc (- origin-x 3) (- origin-y 3) 6 6)))

(defn- draw-coordinate-labels
  "Draw coordinate labels at the edges."
  [^GraphicsContext gc width height bounds]
  (let [{:keys [x-min x-max y-min y-max]} bounds]
    (.setFill gc (Color/web "#808080"))
    (.setFont gc (Font/font "System" 9.0))
    
    ;; X axis labels
    (.fillText gc (format "%.1f" x-min) 5 (- height 5))
    (.fillText gc (format "%.1f" x-max) (- width 35) (- height 5))
    
    ;; Y axis labels
    (.fillText gc (format "%.1f" y-max) 5 12)
    (.fillText gc (format "%.1f" y-min) 5 (- height 20))))

(defn- draw-line
  "Draw a line between two points."
  [^GraphicsContext gc width height bounds {:keys [from to color line-width]} points-map]
  (when-let [from-point (get points-map from)]
    (when-let [to-point (get points-map to)]
      (let [[x1 y1] (world-to-canvas (:x from-point) (:y from-point) width height bounds)
            [x2 y2] (world-to-canvas (:x to-point) (:y to-point) width height bounds)]
        (.setStroke gc (Color/web (or color "#4A6FA5")))
        (.setLineWidth gc (or line-width 2))
        (.strokeLine gc x1 y1 x2 y2)))))

(defn- draw-polygon
  "Draw a filled polygon connecting points."
  [^GraphicsContext gc width height bounds {:keys [points color]} points-map]
  (when (seq points)
    (let [coords (for [point-id points
                       :let [point (get points-map point-id)]
                       :when point]
                   (world-to-canvas (:x point) (:y point) width height bounds))]
      (when (>= (count coords) 3)
        (let [xs (double-array (map first coords))
              ys (double-array (map second coords))]
          (.setFill gc (Color/web (or color "#4A6FA520")))
          (.fillPolygon gc xs ys (count coords)))))))

(defn- draw-point
  "Draw a draggable point with label."
  [^GraphicsContext gc x y radius color label hover?]
  (let [actual-radius (if hover? (+ radius 2) radius)]
    ;; Outer glow when hovering
    (when hover?
      (.setFill gc (Color/web color 0.3))
      (.fillOval gc (- x actual-radius 3) (- y actual-radius 3)
                 (* 2 (+ actual-radius 3)) (* 2 (+ actual-radius 3))))
    
    ;; Point circle
    (.setFill gc (Color/web color))
    (.fillOval gc (- x actual-radius) (- y actual-radius)
               (* 2 actual-radius) (* 2 actual-radius))
    
    ;; Border
    (.setStroke gc (Color/web "#FFFFFF" 0.8))
    (.setLineWidth gc 1.5)
    (.strokeOval gc (- x actual-radius) (- y actual-radius)
                 (* 2 actual-radius) (* 2 actual-radius))
    
    ;; Label
    (when label
      (.setFill gc (Color/web "#E0E0E0"))
      (.setFont gc (Font/font "System" FontWeight/BOLD 10.0))
      (.fillText gc label (+ x actual-radius 5) (- y actual-radius)))))

(defn- find-closest-point
  "Find the closest point to mouse coordinates within threshold."
  [mx my points width height bounds threshold]
  (when (seq points)
    (->> points
         (map (fn [{:keys [id x y]}]
                (let [[cx cy] (world-to-canvas x y width height bounds)
                      dist (Math/sqrt (+ (Math/pow (- mx cx) 2)
                                        (Math/pow (- my cy) 2)))]
                  {:id id :dist dist})))
         (filter #(< (:dist %) threshold))
         (sort-by :dist)
         first
         :id)))

(defn- point-in-polygon?
  "Check if a point (px, py) is inside a polygon using ray casting algorithm."
  [px py polygon-points]
  (when (>= (count polygon-points) 3)
    (let [n (count polygon-points)]
      (loop [i 0
             j (dec n)
             inside? false]
        (if (< i n)
          (let [[xi yi] (nth polygon-points i)
                [xj yj] (nth polygon-points j)
                intersect? (and (or (and (> yi py) (<= yj py))
                                   (and (> yj py) (<= yi py)))
                               (< px (+ xj (* (/ (- py yj) (- yi yj)) (- xi xj)))))]
            (recur (inc i) i (if intersect? (not inside?) inside?)))
          inside?)))))

(defn- check-polygon-hit
  "Check if mouse coordinates are inside the polygon."
  [mx my polygon points-map width height bounds]
  (when (and polygon (seq (:points polygon)))
    (let [polygon-point-ids (:points polygon)
          polygon-coords (for [point-id polygon-point-ids
                              :let [point (get points-map point-id)]
                              :when point]
                          (world-to-canvas (:x point) (:y point) width height bounds))]
      (when (>= (count polygon-coords) 3)
        (point-in-polygon? mx my polygon-coords)))))


;; Main Canvas Component


(defn spatial-canvas
  "2D spatial canvas for visual parameter editing.
   
   Props:
   - :width - Canvas width in pixels
   - :height - Canvas height in pixels
   - :bounds - World coordinate bounds {:x-min :x-max :y-min :y-max}
   - :points - Vector of points [{:id :x :y :color :label}]
   - :lines - Optional vector of lines [{:from :to :color :line-width}]
   - :polygon - Optional polygon {:points [...ids] :color}
   - :on-point-drag - Event map to dispatch when point is dragged
   - :show-grid - Show grid background (default true)
   - :show-axes - Show coordinate axes (default true)
   - :show-labels - Show coordinate labels (default true)"
  [{:keys [width height bounds points lines polygon on-point-drag
           show-grid show-axes show-labels]
    :or {width 300 height 300
         show-grid true show-axes true show-labels true}}]
  
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created
   (fn [^Canvas canvas]
     (let [gc (.getGraphicsContext2D canvas)
           drag-state (atom {:dragging? false
                            :point-id nil
                            :hover-id nil
                            :drag-type nil  ; :point or :polygon
                            :drag-start-world nil  ; [x y] world coords where drag started
                            :initial-points nil})  ; snapshot of points at drag start
           points-map (atom (into {} (map (fn [p] [(:id p) p]) points)))]
       
       ;; Render function
       (letfn [(render! []
                 (.clearRect gc 0 0 width height)
                 
                 ;; Dark background
                 (.setFill gc (Color/web "#1E1E1E"))
                 (.fillRect gc 0 0 width height)
                 
                 ;; Grid and axes
                 (when show-grid 
                   (draw-grid gc width height bounds))
                 (when show-axes 
                   (draw-axes gc width height bounds))
                 (when show-labels
                   (draw-coordinate-labels gc width height bounds))
                 
                 ;; Polygon fill (draw before lines)
                 (when polygon
                   (draw-polygon gc width height bounds polygon @points-map))
                 
                 ;; Lines
                 (doseq [line lines]
                   (draw-line gc width height bounds line @points-map))
                 
                 ;; Points
                 (doseq [{:keys [id x y color label]} (vals @points-map)]
                   (let [[cx cy] (world-to-canvas x y width height bounds)
                         hover? (= id (:hover-id @drag-state))]
                     (draw-point gc cx cy 6 color label hover?))))]
         
         ;; Mouse pressed - start drag if over a point or inside polygon
         (.setOnMousePressed
           canvas
           (reify javafx.event.EventHandler
             (handle [_ e]
               (let [mx (.getX e)
                     my (.getY e)
                     hit-point (find-closest-point mx my (vals @points-map)
                                                  width height bounds 10)
                     [wx wy] (canvas-to-world mx my width height bounds)]
                 (cond
                   ;; Priority 1: Hit a point directly
                   hit-point
                   (swap! drag-state assoc
                          :dragging? true
                          :point-id hit-point
                          :drag-type :point
                          :drag-start-world [wx wy]
                          :initial-points @points-map)
                   
                   ;; Priority 2: Hit inside polygon (if polygon exists)
                   (and polygon (check-polygon-hit mx my polygon @points-map width height bounds))
                   (swap! drag-state assoc
                          :dragging? true
                          :drag-type :polygon
                          :drag-start-world [wx wy]
                          :initial-points @points-map))
                 (render!)))))
         
         ;; Mouse dragged - update point or polygon position
         (.setOnMouseDragged
           canvas
           (reify javafx.event.EventHandler
             (handle [_ e]
               (when (:dragging? @drag-state)
                 (let [[wx wy] (canvas-to-world (.getX e) (.getY e)
                                                width height bounds)
                       drag-type (:drag-type @drag-state)]
                   
                   (case drag-type
                     ;; Single point drag
                     :point
                     (let [[clamped-x clamped-y] (clamp-to-bounds wx wy bounds)
                           point-id (:point-id @drag-state)]
                       ;; Update local point position for immediate feedback
                       (swap! points-map assoc-in [point-id :x] clamped-x)
                       (swap! points-map assoc-in [point-id :y] clamped-y)
                       (render!)
                       ;; Dispatch event for state update
                       (when on-point-drag
                         (events/dispatch! (assoc on-point-drag
                                                 :point-id point-id
                                                 :x clamped-x
                                                 :y clamped-y))))
                     
                     ;; Polygon drag - move all points together
                     :polygon
                     (let [[start-wx start-wy] (:drag-start-world @drag-state)
                           dx (- wx start-wx)
                           dy (- wy start-wy)
                           initial-points (:initial-points @drag-state)
                           polygon-point-ids (:points polygon)]
                       ;; Update all polygon points
                       (doseq [point-id polygon-point-ids]
                         (when-let [initial-point (get initial-points point-id)]
                           (let [new-x (+ (:x initial-point) dx)
                                 new-y (+ (:y initial-point) dy)
                                 [clamped-x clamped-y] (clamp-to-bounds new-x new-y bounds)]
                             ;; Update local point position
                             (swap! points-map assoc-in [point-id :x] clamped-x)
                             (swap! points-map assoc-in [point-id :y] clamped-y)
                             ;; Dispatch event for each point
                             (when on-point-drag
                               (events/dispatch! (assoc on-point-drag
                                                       :point-id point-id
                                                       :x clamped-x
                                                       :y clamped-y))))))
                       (render!))
                     
                     ;; Default: do nothing
                     nil))))))
         
         ;; Mouse released - end drag
         (.setOnMouseReleased
           canvas
           (reify javafx.event.EventHandler
             (handle [_ e]
               (swap! drag-state assoc
                      :dragging? false
                      :point-id nil
                      :drag-type nil
                      :drag-start-world nil
                      :initial-points nil)
               (render!))))
         
         ;; Mouse moved - update hover state and cursor
         (.setOnMouseMoved
           canvas
           (reify javafx.event.EventHandler
             (handle [_ e]
               (let [mx (.getX e)
                     my (.getY e)
                     hover-point (find-closest-point mx my (vals @points-map)
                                                    width height bounds 10)
                     current-hover (:hover-id @drag-state)
                     inside-polygon? (and polygon
                                         (not hover-point)
                                         (check-polygon-hit mx my polygon @points-map width height bounds))]
                 ;; Update hover state for visual feedback
                 (when (not= hover-point current-hover)
                   (swap! drag-state assoc :hover-id hover-point)
                   (render!))
                 ;; Update cursor style
                 (cond
                   hover-point (.setStyle canvas "-fx-cursor: hand;")
                   inside-polygon? (.setStyle canvas "-fx-cursor: move;")
                   :else (.setStyle canvas "-fx-cursor: hand;"))))))
         
         ;; Mouse exited - clear hover
         (.setOnMouseExited
           canvas
           (reify javafx.event.EventHandler
             (handle [_ e]
               (when (:hover-id @drag-state)
                 (swap! drag-state assoc :hover-id nil)
                 (render!)))))
         
         ;; Initial render
         (render!))))
   
   :desc {:fx/type :canvas
          :width width
          :height height
          :style "-fx-cursor: hand;"}})
