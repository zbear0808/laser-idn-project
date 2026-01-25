(ns laser-show.views.components.visual-editors.keyframe-timeline
  "Canvas component for keyframe modulator timeline.
   
   Features:
   - Horizontal timeline bar showing 0% to 100% of period
   - Draggable keyframe markers (diamond shapes)
   - Click empty space to add new keyframe
   - Right-click to delete keyframe (except if last one)
   - Selected keyframe is highlighted
   - Current playback position indicator (vertical line)
   - Hover states with position display
   
   Visual design:
   ┌────────────────────────────────────────────────────────────────┐
   │  0%                    50%                               100%  │
   │  ├─────────────────────┼─────────────────────────────────┤    │
   │  ◆                     ◇                                 ◆    │
   │  │                     │                                 │    │
   │  ▼ - selected                                                  │
   │                           ↑ playback indicator                 │
   └────────────────────────────────────────────────────────────────┘
   
   Usage:
   {:fx/type keyframe-timeline
    :width 400
    :height 60
    :keyframes [{:position 0.0 :params {...}} ...]
    :selected-idx 0
    :current-phase 0.35
    :on-select {:event/type :keyframe/select ...}
    :on-add {:event/type :keyframe/add ...}
    :on-move {:event/type :keyframe/move ...}
    :on-delete {:event/type :keyframe/delete ...}}"
  (:require [cljfx.api :as fx]
            [laser-show.events.core :as events]
            [laser-show.css.theme :as theme])
  (:import [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.input MouseButton MouseEvent]
           [javafx.scene.paint Color]
           [javafx.scene.text Font FontWeight TextAlignment]))


;; Constants


(def ^:private marker-size 10)
(def ^:private marker-half (/ marker-size 2))
(def ^:private timeline-height 20)
(def ^:private padding-left 30)
(def ^:private padding-right 30)
(def ^:private padding-top 25)
(def ^:private playhead-color "#FFD700")
(def ^:private selected-color "#00BFFF")
(def ^:private normal-color "#AAAAAA")
(def ^:private hover-color "#FFFFFF")
(def ^:private timeline-bg-color "#2A2A2A")
(def ^:private timeline-border-color "#444444")


;; Coordinate Transformations


(defn- position-to-x
  "Convert timeline position (0.0-1.0) to canvas X coordinate."
  ^double [^double position ^double width]
  (let [usable-width (- width padding-left padding-right)]
    (+ padding-left (* position usable-width))))

(defn- x-to-position
  "Convert canvas X coordinate to timeline position (0.0-1.0)."
  ^double [^double x ^double width]
  (let [usable-width (- width padding-left padding-right)
        clamped-x (max padding-left (min (- width padding-right) x))]
    (/ (- clamped-x padding-left) usable-width)))


;; Drawing Functions


(defn- draw-background
  "Draw the canvas background."
  [^GraphicsContext gc ^double width ^double height]
  (.setFill gc (Color/web (:bg-base theme/base-colors)))
  (.fillRect gc 0 0 width height))

(defn- draw-timeline-bar
  "Draw the timeline bar background and border."
  [^GraphicsContext gc ^double width ^double height]
  (let [bar-y (+ padding-top 5)
        bar-height 8
        bar-x padding-left
        bar-width (- width padding-left padding-right)]
    ;; Background
    (.setFill gc (Color/web timeline-bg-color))
    (.fillRoundRect gc bar-x bar-y bar-width bar-height 4 4)
    ;; Border
    (.setStroke gc (Color/web timeline-border-color))
    (.setLineWidth gc 1.0)
    (.strokeRoundRect gc bar-x bar-y bar-width bar-height 4 4)))

(defn- draw-tick-marks
  "Draw tick marks and percentage labels at 0%, 25%, 50%, 75%, 100%."
  [^GraphicsContext gc ^double width ^double height]
  (let [bar-y (+ padding-top 5)
        tick-positions [0.0 0.25 0.5 0.75 1.0]
        labels ["0%" "25%" "50%" "75%" "100%"]]
    (.setStroke gc (Color/web "#555555"))
    (.setLineWidth gc 1.0)
    (.setFill gc (Color/web "#888888"))
    (.setFont gc (Font/font "System" 9.0))
    (.setTextAlign gc TextAlignment/CENTER)
    
    (doseq [[pos label] (map vector tick-positions labels)]
      (let [x (position-to-x pos width)]
        ;; Tick mark
        (.strokeLine gc x (- bar-y 2) x (+ bar-y 10))
        ;; Label above
        (.fillText gc label x (- bar-y 6))))))

(defn- draw-playhead
  "Draw the current playback position indicator."
  [^GraphicsContext gc ^double width ^double height current-phase]
  (when current-phase
    (let [x (position-to-x current-phase width)
          bar-y (+ padding-top 5)
          top-y (- bar-y 4)
          bottom-y (+ bar-y 16)]
      (.setStroke gc (Color/web playhead-color))
      (.setLineWidth gc 2.0)
      (.strokeLine gc x top-y x bottom-y)
      ;; Small triangle at top
      (.setFill gc (Color/web playhead-color))
      (.fillPolygon gc
                    (double-array [(- x 4) x (+ x 4)])
                    (double-array [top-y (+ top-y 5) top-y])
                    3))))

(defn- draw-keyframe-marker
  "Draw a single keyframe marker (diamond shape)."
  [^GraphicsContext gc x y selected? hover?]
  (let [x (double x)
        y (double y)
        color (cond
                selected? selected-color
                hover? hover-color
                :else normal-color)
        size (if (or selected? hover?) (+ marker-half 2) marker-half)]
    ;; Diamond shape
    (.setFill gc (Color/web color))
    (.fillPolygon gc
                  (double-array [(- x size) x (+ x size) x])
                  (double-array [y (- y size) y (+ y size)])
                  4)
    ;; Border
    (.setStroke gc (Color/web "#FFFFFF" 0.8))
    (.setLineWidth gc (if selected? 2.0 1.0))
    (.strokePolygon gc
                    (double-array [(- x size) x (+ x size) x])
                    (double-array [y (- y size) y (+ y size)])
                    4)
    ;; Selection indicator (small arrow below)
    (when selected?
      (.setFill gc (Color/web selected-color))
      (.fillPolygon gc
                    (double-array [(- x 4) x (+ x 4)])
                    (double-array [(+ y size 4) (+ y size 10) (+ y size 4)])
                    3))))

(defn- draw-keyframe-markers
  "Draw all keyframe markers."
  [^GraphicsContext gc width height keyframes selected-idx hover-idx]
  (let [width (double width)
        height (double height)
        marker-y (+ padding-top 9)]  ;; Center on timeline bar
    (doseq [[idx kf] (map-indexed vector keyframes)]
      (let [x (position-to-x (:position kf) width)
            selected? (= idx selected-idx)
            hover? (= idx hover-idx)]
        (draw-keyframe-marker gc x marker-y selected? hover?)))))

(defn- draw-position-tooltip
  "Draw position tooltip near hover point."
  [^GraphicsContext gc width height keyframes hover-idx]
  (let [width (double width)]
    (when hover-idx
      (when-let [kf (nth keyframes hover-idx nil)]
        (let [x (position-to-x (:position kf) width)
              y (+ padding-top 9)
              text (format "%.0f%%" (* 100 (:position kf)))
              tx (+ x 12)
              ty (- y 8)]
          (.setFill gc (Color/web "#000000" 0.9))
          (.fillRoundRect gc (- tx 4) (- ty 10) 40 14 3 3)
          (.setFill gc (Color/web "#FFFFFF"))
          (.setFont gc (Font/font "Consolas" FontWeight/NORMAL 10.0))
          (.setTextAlign gc TextAlignment/LEFT)
          (.fillText gc text tx ty))))))


;; Hit Testing


(defn- find-closest-keyframe
  "Find the closest keyframe marker to mouse coordinates within threshold."
  [mx my keyframes width height threshold]
  (let [marker-y (+ padding-top 9)]
    (when (and (seq keyframes)
               (< (Math/abs (- my marker-y)) threshold))
      (let [results
            (for [[idx kf] (map-indexed vector keyframes)]
              (let [kf-x (position-to-x (:position kf) width)
                    dist (Math/abs (- mx kf-x))]
                {:idx idx :dist dist}))]
        (->> results
             (filter #(< (:dist %) threshold))
             (sort-by :dist)
             first
             :idx)))))


;; Main Canvas Component


(defn keyframe-timeline
  "Canvas component for keyframe modulator timeline.
   
   Props:
   - :width - Canvas width in pixels (default 400)
   - :height - Canvas height in pixels (default 60)
   - :keyframes - Vector of {:position :params} maps
   - :selected-idx - Index of currently selected keyframe
   - :current-phase - Current playback position (0.0-1.0) for preview
   - :on-select - Event template for selecting keyframe (receives :keyframe-idx)
   - :on-add - Event template for adding keyframe (receives :position)
   - :on-move - Event template for moving keyframe (receives :keyframe-idx :new-position)
   - :on-delete - Event template for deleting keyframe (receives :keyframe-idx)"
  [{:keys [width height keyframes selected-idx current-phase
           on-select on-add on-move on-delete]
    :or {width 400 height 60}}]
  
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created
   (fn [^Canvas canvas]
     (let [gc (.getGraphicsContext2D canvas)
           ;; Internal state for interaction
           drag-state (atom {:dragging? false
                             :keyframe-idx nil
                             :hover-idx nil})
           keyframes-atom (atom (or keyframes []))
           selected-atom (atom selected-idx)
           phase-atom (atom current-phase)]
       
       ;; Render function
       (letfn [(render! []
                 (let [kfs @keyframes-atom
                       sel-idx @selected-atom
                       phase @phase-atom
                       hover-idx (:hover-idx @drag-state)]
                   (draw-background gc width height)
                   (draw-timeline-bar gc width height)
                   (draw-tick-marks gc width height)
                   (draw-keyframe-markers gc width height kfs sel-idx hover-idx)
                   (draw-playhead gc width height phase)
                   (draw-position-tooltip gc width height kfs hover-idx)))]
         
         ;; Mouse pressed - start drag, select, or add keyframe
         (.setOnMousePressed
          canvas
          (reify javafx.event.EventHandler
            (handle [_ e]
              (let [mx (.getX e)
                    my (.getY e)
                    button (.getButton e)
                    kfs @keyframes-atom
                    hit-idx (find-closest-keyframe mx my kfs width height 15)]
                (cond
                  ;; Right-click on keyframe - delete it (if more than 1)
                  (and (= button MouseButton/SECONDARY) hit-idx)
                  (when (and (> (count kfs) 1) on-delete)
                    (events/dispatch! (assoc on-delete :keyframe-idx hit-idx))
                    (let [new-kfs (vec (concat (subvec kfs 0 hit-idx)
                                               (subvec kfs (inc hit-idx))))]
                      (reset! keyframes-atom new-kfs)
                      ;; Adjust selected if needed
                      (when (>= @selected-atom (count new-kfs))
                        (reset! selected-atom (dec (count new-kfs))))
                      (render!)))
                  
                  ;; Left-click on keyframe - select and start drag
                  (and (= button MouseButton/PRIMARY) hit-idx)
                  (do
                    (reset! selected-atom hit-idx)
                    (swap! drag-state assoc
                           :dragging? true
                           :keyframe-idx hit-idx)
                    (when on-select
                      (events/dispatch! (assoc on-select :keyframe-idx hit-idx)))
                    (render!))
                  
                  ;; Left-click on empty space - add new keyframe
                  (and (= button MouseButton/PRIMARY) (nil? hit-idx))
                  (let [position (x-to-position mx width)
                        clamped-pos (max 0.0 (min 1.0 position))]
                    (when on-add
                      (events/dispatch! (assoc on-add :position clamped-pos))
                      ;; Add to local state for immediate feedback
                      (let [new-kf {:position clamped-pos :params {}}
                            new-kfs (vec (sort-by :position (conj @keyframes-atom new-kf)))
                            new-idx (.indexOf new-kfs new-kf)]
                        (reset! keyframes-atom new-kfs)
                        (reset! selected-atom new-idx)
                        (render!)))))))))
         
         ;; Mouse dragged - update keyframe position
         (.setOnMouseDragged
          canvas
          (reify javafx.event.EventHandler
            (handle [_ e]
              (when (:dragging? @drag-state)
                (let [keyframe-idx (:keyframe-idx @drag-state)
                      position (x-to-position (.getX e) width)
                      clamped-pos (max 0.0 (min 1.0 position))]
                  ;; Update local state for immediate feedback
                  (swap! keyframes-atom assoc-in [keyframe-idx :position] clamped-pos)
                  ;; Re-sort keyframes and track new index
                  (let [kfs @keyframes-atom
                        updated-kf (nth kfs keyframe-idx)
                        sorted-kfs (vec (sort-by :position kfs))
                        new-idx (.indexOf sorted-kfs updated-kf)]
                    (reset! keyframes-atom sorted-kfs)
                    (swap! drag-state assoc :keyframe-idx new-idx)
                    (reset! selected-atom new-idx))
                  (render!)
                  ;; Dispatch event for state update
                  (when on-move
                    (events/dispatch! (assoc on-move
                                             :keyframe-idx (:keyframe-idx @drag-state)
                                             :new-position clamped-pos))))))))
         
         ;; Mouse released - end drag
         (.setOnMouseReleased
          canvas
          (reify javafx.event.EventHandler
            (handle [_ e]
              (swap! drag-state assoc
                     :dragging? false
                     :keyframe-idx nil)
              (render!))))
         
         ;; Mouse moved - update hover state
         (.setOnMouseMoved
          canvas
          (reify javafx.event.EventHandler
            (handle [_ e]
              (let [mx (.getX e)
                    my (.getY e)
                    kfs @keyframes-atom
                    hover-idx (find-closest-keyframe mx my kfs width height 15)
                    current-hover (:hover-idx @drag-state)]
                (when (not= hover-idx current-hover)
                  (swap! drag-state assoc :hover-idx hover-idx)
                  (render!))
                ;; Update cursor
                (if hover-idx
                  (.setStyle canvas "-fx-cursor: hand;")
                  (.setStyle canvas "-fx-cursor: crosshair;"))))))
         
         ;; Mouse exited - clear hover
         (.setOnMouseExited
          canvas
          (reify javafx.event.EventHandler
            (handle [_ e]
              (when (:hover-idx @drag-state)
                (swap! drag-state assoc :hover-idx nil)
                (render!)))))
         
         ;; Initial render
         (render!))))
   
   :desc {:fx/type :canvas
          :width width
          :height height
          :style "-fx-cursor: crosshair;"}})
