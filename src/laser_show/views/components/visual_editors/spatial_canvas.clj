(ns laser-show.views.components.visual-editors.spatial-canvas
  "2D spatial canvas component for visual parameter editing.
   
   Features:
   - Draggable points with real-time coordinate updates
   - Draggable polygon areas - click and drag inside to move all points together
   - Keyboard arrow keys for granular position adjustment
   - Grid background with coordinate axes
   - World-to-canvas coordinate transformations
   - Support for lines, polygons, and visual feedback
   - Smart cursor feedback (hand on points, move cursor inside polygon)
   
   Refactored to Stateless Interactive Canvas."
  (:require [laser-show.common.util :as u]
            [laser-show.views.components.visual-editors.canvas-interaction :as ci])
  (:import [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.input MouseButton KeyEvent KeyCode]
           [javafx.scene.paint Color]
           [javafx.scene.text Font FontWeight]))


;; Coordinate Transformations


(defn- world-to-canvas
  "Convert world coordinates to canvas pixel coordinates.
   World space: [x-min, x-max] x [y-min, y-max] with Y increasing upward
   Canvas space: [0, width] x [0, height] with Y increasing downward"
  [x y width height {:keys [x-min x-max y-min y-max]}]
  (let [canvas-x (* (/ (- x x-min) (- x-max x-min)) width)
        canvas-y (* (/ (- y-max y) (- y-max y-min)) height)]
    [canvas-x canvas-y]))

(defn- canvas-to-world
  "Convert canvas pixel coordinates to world coordinates."
  [canvas-x canvas-y width height {:keys [x-min x-max y-min y-max]}]
  (let [x (+ x-min (* (/ canvas-x width) (- x-max x-min)))
        y (- y-max (* (/ canvas-y height) (- y-max y-min)))]
    [x y]))

(defn- clamp-to-bounds
  "Clamp world coordinates to bounds."
  [x y {:keys [x-min x-max y-min y-max]}]
  [(u/clamp x x-min x-max)
   (u/clamp y y-min y-max)])


;; Drawing Functions


(defn- draw-grid
  "Draw grid lines on the canvas."
  [^GraphicsContext gc width height bounds]
  (.setStroke gc (Color/web "#303030"))
  (.setLineWidth gc 0.5)
  (let [{:keys [x-min x-max y-min y-max]} bounds
        grid-step 0.5]
    (doseq [x (range x-min (+ x-max grid-step) grid-step)]
      (let [[cx _] (world-to-canvas x 0 width height bounds)]
        (.strokeLine gc cx 0 cx height)))
    (doseq [y (range y-min (+ y-max grid-step) grid-step)]
      (let [[_ cy] (world-to-canvas 0 y width height bounds)]
        (.strokeLine gc 0 cy width cy)))))

(defn- draw-axes
  "Draw X and Y axes at origin."
  [^GraphicsContext gc width height bounds]
  (let [[origin-x origin-y] (world-to-canvas 0 0 width height bounds)]
    (.setStroke gc (Color/web "#505050"))
    (.setLineWidth gc 1.5)
    (.strokeLine gc 0 origin-y width origin-y)
    (.strokeLine gc origin-x 0 origin-x height)
    (.setFill gc (Color/web "#707070"))
    (.fillOval gc (- origin-x 3) (- origin-y 3) 6 6)))

(defn- draw-coordinate-labels
  "Draw coordinate labels at the edges."
  [^GraphicsContext gc width height bounds]
  (let [{:keys [x-min x-max y-min y-max]} bounds]
    (.setFill gc (Color/web "#808080"))
    (.setFont gc (Font/font "System" 9.0))
    (.fillText gc (format "%.1f" x-min) 5 (- height 5))
    (.fillText gc (format "%.1f" x-max) (- width 35) (- height 5))
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
  [^GraphicsContext gc x y radius color label hover? keyboard-selected?]
  (let [actual-radius (if hover? (+ radius 2) radius)]
    (when (or hover? keyboard-selected?)
      (.setFill gc (Color/web color 0.3))
      (.fillOval gc (- x actual-radius 3) (- y actual-radius 3)
                 (* 2 (+ actual-radius 3)) (* 2 (+ actual-radius 3))))
    (.setFill gc (Color/web color))
    (.fillOval gc (- x actual-radius) (- y actual-radius)
               (* 2 actual-radius) (* 2 actual-radius))
    (if keyboard-selected?
      (do
        (.setStroke gc (Color/web "#FFFF00" 0.9))
        (.setLineWidth gc 2.5))
      (do
        (.setStroke gc (Color/web "#FFFFFF" 0.8))
        (.setLineWidth gc 1.5)))
    (.strokeOval gc (- x actual-radius) (- y actual-radius)
                 (* 2 actual-radius) (* 2 actual-radius))
    (when label
      (.setFill gc (Color/web "#E0E0E0"))
      (.setFont gc (Font/font "System" FontWeight/BOLD 10.0))
      (.fillText gc label (+ x actual-radius 5) (- y actual-radius)))))


;; Hit Testing


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


;; Local Preview Helpers

(defn- update-point-in-list
  "Update a single point's coordinates in the points list."
  [points point-id x y]
  (mapv (fn [p]
          (if (= (:id p) point-id)
            (assoc p :x x :y y)
            p))
        points))

(defn- update-points-in-list
  "Update multiple points in the points list.
   updates-map: {point-id {:x ... :y ...}}"
  [points updates-map]
  (mapv (fn [p]
          (if-let [updates (get updates-map (:id p))]
            (merge p updates)
            p))
        points))


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
   - :on-reset - Event map to dispatch when right-click resets values
   - :show-grid - Show grid background (default true)
   - :show-axes - Show coordinate axes (default true)
   - :show-labels - Show coordinate labels (default true)"
  [{:keys [width height bounds points lines polygon on-point-drag on-reset
           show-grid show-axes show-labels]
    :or {width 300 height 300
         show-grid true show-axes true show-labels true}}]

  (ci/interactive-canvas
   {:width width
    :height height
    :value points
    :cursor "crosshair"

    :initial-drag-state {:keyboard-selected-id (some-> points first :id)}

    :render!
    (fn [^Canvas canvas points drag-info]
      (let [gc (.getGraphicsContext2D canvas)
            points-map (u/map-into :id points)]
        (.clearRect gc 0 0 width height)
        (.setFill gc Color/BLACK)
        (.fillRect gc 0 0 width height)
        (when show-grid
          (draw-grid gc width height bounds))
        (when show-axes
          (draw-axes gc width height bounds))
        (when show-labels
          (draw-coordinate-labels gc width height bounds))
        (when polygon
          (draw-polygon gc width height bounds polygon points-map))
        (doseq [line lines]
          (draw-line gc width height bounds line points-map))
        (doseq [{:keys [id x y color label]} (vals points-map)]
          (let [[cx cy] (world-to-canvas x y width height bounds)
                hover? (= id (:hover-id drag-info))
                keyboard-selected? (= id (:keyboard-selected-id drag-info))]
            (draw-point gc cx cy 6 color label hover? keyboard-selected?)))))

    :on-press
    (fn [mx my button points drag-info]
      (let [points-map (u/map-into :id points)
            hit-point-id (find-closest-point mx my (vals points-map) width height bounds 10)
            [wx wy] (canvas-to-world mx my width height bounds)]
        (if (= button MouseButton/SECONDARY)
          ;; Right-click: dispatch reset
          (when on-reset {:dispatch on-reset})

          ;; Left-click: drag logic
          (cond
            hit-point-id
            {:drag-start true
             :drag-id hit-point-id
             :drag-updates {:drag-type :point
                            :drag-start-world [wx wy]
                            :initial-points points-map
                            :keyboard-selected-id hit-point-id}}

            (and polygon (check-polygon-hit mx my polygon points-map width height bounds))
            {:drag-start true
             :drag-id :polygon
             :drag-updates {:drag-type :polygon
                            :drag-start-world [wx wy]
                            :initial-points points-map}}

            :else nil))))

    :on-drag
    (fn [mx my points drag-info]
      (let [[wx wy] (canvas-to-world mx my width height bounds)
            drag-type (:drag-type drag-info)]
        (case drag-type
          :point
          (let [[clamped-x clamped-y] (clamp-to-bounds wx wy bounds)
                point-id (:drag-id drag-info)]
            (when on-point-drag
              {:dispatch (assoc on-point-drag
                                :point-id point-id
                                :x (double clamped-x)
                                :y (double clamped-y))
               ;; RETURN PREVIEW VALUE
               :preview-value (update-point-in-list points point-id clamped-x clamped-y)}))

          :polygon
          (let [[start-wx start-wy] (:drag-start-world drag-info)
                dx (- wx start-wx)
                dy (- wy start-wy)
                initial-points (:initial-points drag-info)
                polygon-point-ids (:points polygon)

                ;; Calculate new positions for all points in polygon
                updates-map (into {}
                                  (for [point-id polygon-point-ids
                                        :let [initial-point (get initial-points point-id)]
                                        :when initial-point]
                                    (let [new-x (+ (:x initial-point) dx)
                                          new-y (+ (:y initial-point) dy)
                                          [clamped-x clamped-y] (clamp-to-bounds new-x new-y bounds)]
                                      [point-id {:x clamped-x :y clamped-y}])))

                events (for [[point-id {:keys [x y]}] updates-map]
                         (assoc on-point-drag
                                :point-id point-id
                                :x (double x)
                                :y (double y)))]
            (when (seq events)
              {:dispatch (vec events)
               ;; RETURN PREVIEW VALUE
               :preview-value (update-points-in-list points updates-map)}))

          nil)))

    :on-hover
    (fn [mx my points drag-info]
      (let [points-map (u/map-into :id points)
            hover-point-id (find-closest-point mx my (vals points-map) width height bounds 10)
            inside-polygon? (and polygon
                                 (not hover-point-id)
                                 (check-polygon-hit mx my polygon points-map width height bounds))]
        {:hover-id hover-point-id
         :cursor (cond
                   hover-point-id "hand"
                   inside-polygon? "move"
                   :else "crosshair")}))

    :on-key
    (fn [^KeyCode code shift? points drag-info]
      (let [fine-step 0.005
            coarse-step 0.02
            step (if shift? coarse-step fine-step)
            selected-id (:keyboard-selected-id drag-info)
            points-map (u/map-into :id points)]

        (cond
          ;; TAB: Cycle selection
          (= code KeyCode/TAB)
          (let [point-ids (mapv :id points)
                current-idx (.indexOf point-ids selected-id)
                next-idx (if shift?
                           (mod (dec current-idx) (count point-ids))
                           (mod (inc current-idx) (count point-ids)))
                next-id (nth point-ids next-idx)]
            {:consumed? true
             :drag-updates {:keyboard-selected-id next-id}})

          ;; ARROWS: Move selected point
          (and selected-id (#{KeyCode/LEFT KeyCode/RIGHT KeyCode/UP KeyCode/DOWN} code))
          (let [dx (case code
                     KeyCode/LEFT (- step)
                     KeyCode/RIGHT step
                     0.0)
                dy (case code
                     KeyCode/UP step
                     KeyCode/DOWN (- step)
                     0.0)]
            (when-let [point (get points-map selected-id)]
              (let [new-x (+ (:x point) dx)
                    new-y (+ (:y point) dy)
                    [clamped-x clamped-y] (clamp-to-bounds new-x new-y bounds)]
                (when on-point-drag
                  {:dispatch (assoc on-point-drag
                                    :point-id selected-id
                                    :x (double clamped-x)
                                    :y (double clamped-y))
                   :consumed? true
                   ;; RETURN PREVIEW VALUE
                   :preview-value (update-point-in-list points selected-id clamped-x clamped-y)})))))))}))
