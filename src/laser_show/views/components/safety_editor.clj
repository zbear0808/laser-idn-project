(ns laser-show.views.components.safety-editor
  "The safety zone editor in the projector panel (zones plan Z1, 03 §7): a
   canvas with the scan limit (green) and blocked areas (red) to drag, their
   numbers to type, and buttons to add, remove, copy, mirror and reset.

   The canvas shows the projector's output coordinates (-1..1, after its
   calibration), Y up. A drag dispatches :projectors/set-safety as it moves
   while that can't let light anywhere new (or the projector isn't
   outputting), inside one undo transaction; a drag that would widen a live
   projector's area shows a dashed preview and dispatches once on release,
   which asks. Escape puts back the zone from before the drag."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.animation.safety :as safety]
            [laser-show.events.safety-guard :as guard]
            [laser-show.subs :as subs]
            [laser-show.views.components.commit-field :as commit-field]
            [laser-show.views.components.visual-editors.canvas-interaction :as ci])
  (:import [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.input KeyCode]
           [javafx.scene.paint Color]))

(def ^:const canvas-size 260)
(def ^:private ^:const margin 10.0)
(def ^:private ^:const min-limit 0.05)
(def ^:private ^:const min-blocked 0.01)
(def ^:private ^:const grab-px 7.0)


;; Coordinates: model x, y in -1..1 (Y up) ↔ canvas px


(defn- span ^double [] (- (double canvas-size) (* 2.0 margin)))
(defn- ->px ^double [x] (+ margin (* (/ (+ (double x) 1.0) 2.0) (span))))
(defn- ->py ^double [y] (+ margin (* (/ (- 1.0 (double y)) 2.0) (span))))
(defn- <-px ^double [px] (- (* 2.0 (/ (- (double px) margin) (span))) 1.0))
(defn- <-py ^double [py] (- 1.0 (* 2.0 (/ (- (double py) margin) (span)))))
(defn- clamp1 ^double [v] (max -1.0 (min 1.0 (double v))))


;; Hit testing and dragging (pure)


(defn- near-edges
  "The edges of rect [x0 y0 x1 y1] within grab-px of canvas point (px, py):
   a subset of #{:x0 :x1 :y0 :y1}, only where the point is beside the edge."
  [[x0 y0 x1 y1] px py]
  (let [l (->px x0) r (->px x1) t (->py y1) b (->py y0)
        within-y? (<= (- t grab-px) py (+ b grab-px))
        within-x? (<= (- l grab-px) px (+ r grab-px))]
    (cond-> #{}
      (and within-y? (< (Math/abs (- px l)) grab-px)) (conj :x0)
      (and within-y? (< (Math/abs (- px r)) grab-px)) (conj :x1)
      (and within-x? (< (Math/abs (- py b)) grab-px)) (conj :y0)
      (and within-x? (< (Math/abs (- py t)) grab-px)) (conj :y1))))

(defn hit
  "What a press at canvas point (px, py) grabs in zone: [:blocked i edges],
   [:blocked i :move], [:limit edges], or nil. The last blocked area drawn is
   on top."
  [{:keys [limit blocked]} px py]
  (let [x (<-px px) y (<-py py)]
    (or (some (fn [i]
                (let [r (nth blocked i)
                      edges (near-edges r px py)]
                  (cond
                    (seq edges) [:blocked i edges]
                    (let [[x0 y0 x1 y1] r] (and (<= x0 x x1) (<= y0 y y1))) [:blocked i :move])))
              (reverse (range (count blocked))))
        (let [edges (near-edges limit px py)]
          (when (seq edges) [:limit edges])))))

(defn- move-edges
  "rect with each grabbed edge at (x, y), kept min-size wide and high."
  [[x0 y0 x1 y1] edges x y min-size]
  (let [x (clamp1 x) y (clamp1 y)]
    [(if (:x0 edges) (min x (- x1 min-size)) x0)
     (if (:y0 edges) (min y (- y1 min-size)) y0)
     (if (:x1 edges) (max x (+ x0 min-size)) x1)
     (if (:y1 edges) (max y (+ y0 min-size)) y1)]))

(defn- move-rect
  "rect moved by (dx, dy), kept inside -1..1."
  [[x0 y0 x1 y1] dx dy]
  (let [dx (max (- -1.0 x0) (min (- 1.0 x1) dx))
        dy (max (- -1.0 y0) (min (- 1.0 y1) dy))]
    [(+ x0 dx) (+ y0 dy) (+ x1 dx) (+ y1 dy)]))

(defn dragged
  "zone as a drag of grabbed from (sx, sy) to (x, y) leaves it (model
   coordinates)."
  [zone grabbed [sx sy] [x y]]
  (case (first grabbed)
    :limit (update zone :limit move-edges (second grabbed) x y min-limit)
    :blocked (let [[_ i what] grabbed]
               (update-in zone [:blocked i]
                          (fn [r]
                            (if (= :move what)
                              (move-rect r (- x sx) (- y sy))
                              (move-edges r what x y min-blocked)))))
    zone))


;; Drawing


(defn- stroke-rect! [^GraphicsContext gc [x0 y0 x1 y1]]
  (.strokeRect gc (->px x0) (->py y1) (- (->px x1) (->px x0)) (- (->py y0) (->py y1))))

(defn- fill-rect! [^GraphicsContext gc [x0 y0 x1 y1]]
  (.fillRect gc (->px x0) (->py y1) (- (->px x1) (->px x0)) (- (->py y0) (->py y1))))

(defn- hatch! [^GraphicsContext gc [x0 y0 x1 y1]]
  (let [l (->px x0) r (->px x1) t (->py y1) b (->py y0)]
    (.save gc)
    (.beginPath gc)
    (.rect gc l t (- r l) (- b t))
    (.clip gc)
    (doseq [k (range (- (- b t)) (- r l) 8.0)]
      (.strokeLine gc (+ l k) b (+ l k (- b t)) t))
    (.restore gc)))

(defn- render! [stored ^Canvas canvas zone drag]
  (let [gc (.getGraphicsContext2D canvas)
        size (double canvas-size)
        {:keys [limit blocked]} zone
        widening? (:widening? drag)]
    (.setFill gc (Color/web "#141414"))
    (.fillRect gc 0 0 size size)
    ;; the -1..1 frame and its middle
    (.setStroke gc (Color/web "#333333"))
    (.setLineWidth gc 1.0)
    (.setLineDashes gc nil)
    (stroke-rect! gc [-1 -1 1 1])
    (.strokeLine gc (->px 0) (->py -1) (->px 0) (->py 1))
    (.strokeLine gc (->px -1) (->py 0) (->px 1) (->py 0))
    ;; the limit
    (.setFill gc (Color/web "#4CAF5022"))
    (fill-rect! gc limit)
    (.setStroke gc (Color/web "#4CAF50"))
    (.setLineWidth gc 2.0)
    (when widening? (.setLineDashes gc (double-array [6.0 4.0])))
    (stroke-rect! gc limit)
    (.setLineDashes gc nil)
    ;; blocked areas
    (doseq [[i r] (map-indexed vector blocked)]
      (.setFill gc (Color/web "#E5393533"))
      (fill-rect! gc r)
      (.setStroke gc (Color/web "#E5393588"))
      (.setLineWidth gc 1.0)
      (hatch! gc r)
      (.setStroke gc (Color/web "#E53935"))
      (.setLineWidth gc (if (= i (second (:hover-id drag))) 2.5 1.5))
      (when widening? (.setLineDashes gc (double-array [6.0 4.0])))
      (stroke-rect! gc r)
      (.setLineDashes gc nil)
      (.setFill gc (Color/web "#E57373"))
      (.fillText gc (str (inc i)) (+ (->px (r 0)) 3.0) (- (->py (r 1)) 3.0)))
    ;; while a widening waits for its question, the stored zone, faintly
    (when widening?
      (.setStroke gc (Color/web "#9E9E9E"))
      (.setLineWidth gc 1.0)
      (stroke-rect! gc (:limit stored)))))


;; The section


(defn- fmt [v] (format "%.2f" (double v)))

(defn- parse-coord [text]
  (try (let [v (Double/parseDouble (str/trim (str/replace text "," ".")))]
         (when (<= -1.0 v 1.0) v))
       (catch NumberFormatException _ nil)))

(defn- coord-field
  "A number field for one coordinate of the zone at path (e.g. [:limit 0])."
  [{:keys [projector-id zone path id]}]
  {:fx/type commit-field/commit-field
   :id id
   :text (fmt (get-in zone path))
   :pref-width 56
   :on-commit (fn [typed]
                (when-let [v (parse-coord typed)]
                  {:event/type :projectors/set-safety
                   :projector-id projector-id
                   :safety (assoc-in zone path v)}))})

(defn- rect-row
  [{:keys [projector-id zone label base id-prefix key-suffix removable-index]}]
  (let [;; The key changes with the stored zone and the confirm dialog, so a
        ;; field whose text was refused or is waiting for a question is made
        ;; again with the stored value (cljfx keeps a field whose description
        ;; didn't change)
        field (fn [k idx]
                {:fx/type coord-field :fx/key [idx key-suffix]
                 :projector-id projector-id :zone zone
                 :path (conj base idx) :id (str id-prefix "-" k)})
        text (fn [t] {:fx/type :label :text t :min-width :use-pref-size :style-class ["label-secondary"]})]
    {:fx/type :h-box
     :spacing 4
     :alignment :center-left
     :children (cond-> [{:fx/type :label :text label :min-width 70 :style-class ["label-secondary"]}
                        (text "x") (field "x0" 0) (text "to") (field "x1" 2)
                        (text "y") (field "y0" 1) (text "to") (field "y1" 3)]
                 removable-index
                 (conj {:fx/type :button
                        :id (str "safety-remove-blocked-" removable-index)
                        :text "✕"
                        :style-class ["button-secondary"]
                        :tooltip {:fx/type :tooltip :text "Remove this blocked area"}
                        :on-action {:event/type :projectors/remove-blocked-area
                                    :projector-id projector-id
                                    :index removable-index}}))}))

(defn- copy-menu
  [{:keys [id text projector-id others mirror?]}]
  {:fx/type :menu-button
   :id id
   :text text
   :min-width :use-pref-size
   :style-class ["button-secondary"]
   :items (vec (concat
                (for [{other :id other-name :name} others]
                  {:fx/type :menu-item
                   :text (or other-name (name other))
                   :on-action {:event/type :projectors/copy-safety :from projector-id :to [other] :mirror? mirror?}})
                (when (< 1 (count others))
                  [{:fx/type :separator-menu-item}
                   {:fx/type :menu-item
                    :text "Every other projector"
                    :on-action {:event/type :projectors/copy-safety :from projector-id
                                :to (mapv :id others) :mirror? mirror?}}])))})

(defn safety-section
  "The SAFETY ZONE section for projector-id."
  [{:keys [fx/context projector-id]}]
  (let [projector (fx/sub-val context get-in [:projectors projector-id])
        stored (try (safety/normalize (:safety projector)) (catch clojure.lang.ExceptionInfo _ nil))
        zone (or stored safety/default-safety)
        streaming? (fx/sub-val context get-in [:backend :streaming :running?])
        live? (guard/outputting? {:backend {:streaming {:running? streaming?}}
                                  :projectors {projector-id projector}}
                                 projector-id)
        asking? (fx/sub-ctx context subs/dialog-open? :confirm)
        others (filterv #(not= projector-id (:id %)) (fx/sub-ctx context subs/projectors-list))
        full? (>= (count (:blocked zone)) 8)
        ;; A field waiting for a question, or refused, shows the stored value
        ;; again once the dialog closes
        key-suffix [asking? zone]]
    {:fx/type :v-box
     :id "safety-section"
     :spacing 6
     :children
     (filterv some?
              [{:fx/type :label :text "SAFETY ZONE"
                :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
               (when-not stored
                 {:fx/type :label :id "safety-unreadable"
                  :text "This safety zone can't be read, so the projector stays dark. Reset it to draw a new one."
                  :wrap-text true :style "-fx-text-fill: #E57373;"})
               {:fx/type ci/interactive-canvas
                :id "safety-canvas"
                :width canvas-size
                :height canvas-size
                :value zone
                :cursor "default"
                :focus-traversable? true
                :render! (partial render! zone)
                :on-hover (fn [x y value _]
                            (let [h (hit value x y)]
                              {:hover-id h
                               :cursor (cond
                                         (nil? h) "default"
                                         (= :move (peek h)) "move"
                                         :else "crosshair")}))
                :on-press (fn [x y _ value _]
                            (when-let [grabbed (and stored (hit value x y))]
                              {:drag-start true
                               :drag-id grabbed
                               :transaction-label "Change Safety Zone"
                               :drag-updates {:grabbed grabbed :start [(<-px x) (<-py y)] :from value}}))
                :on-drag (fn [x y _ {:keys [grabbed start from]}]
                           (let [new (dragged from grabbed start [(<-px x) (<-py y)])
                                 widening? (and live? (safety/widens? zone new))]
                             {:preview-value new
                              :drag-updates {:widening? widening? :last new}
                              :dispatch (when-not widening?
                                          {:event/type :projectors/set-safety
                                           :projector-id projector-id
                                           :safety new})}))
                :on-release (fn [_ {:keys [widening? last]}]
                              ;; A widening was only previewed: send it once, and it asks
                              (when (and widening? last)
                                {:dispatch {:event/type :projectors/set-safety
                                            :projector-id projector-id
                                            :safety last}}))
                :on-key (fn [^KeyCode key-code _ _ drag]
                          (when (and (= KeyCode/ESCAPE key-code) (:dragging? drag))
                            {:cancel-drag? true :consumed? true :drag-updates {:widening? false}}))}
               {:fx/type rect-row :projector-id projector-id :zone zone :label "Limit"
                :base [:limit] :id-prefix "safety-limit" :key-suffix key-suffix}
               {:fx/type :v-box
                :spacing 4
                :children (vec (for [i (range (count (:blocked zone)))]
                                 {:fx/type rect-row :fx/key [:blocked i]
                                  :projector-id projector-id :zone zone
                                  :label (str "Blocked " (inc i))
                                  :base [:blocked i] :id-prefix (str "safety-blocked-" i)
                                  :key-suffix key-suffix :removable-index i}))}
               {:fx/type :h-box
                :spacing 6
                :alignment :center-left
                :children (filterv some?
                                   [{:fx/type :button
                                     :id "safety-add-blocked-button"
                                     :text "+ Add blocked area"
                                     :min-width :use-pref-size
                                     :disable (or full? (nil? stored))
                                     :style-class ["button-secondary"]
                                     :on-action {:event/type :projectors/add-blocked-area
                                                 :projector-id projector-id}}
                                    (when full?
                                      {:fx/type :label :id "safety-full-note" :text "Up to 8 blocked areas"
                                       :style-class ["label-hint"]})
                                    {:fx/type :button
                                     :id "safety-reset-button"
                                     :text "Reset"
                                     :min-width :use-pref-size
                                     :style-class ["button-secondary"]
                                     :tooltip {:fx/type :tooltip :text "The whole range, nothing blocked"}
                                     :on-action {:event/type :projectors/set-safety
                                                 :projector-id projector-id
                                                 :safety safety/default-safety}}])}
               ;; Only with somewhere to copy to, and a zone that can be read
               (when (and stored (seq others))
                 {:fx/type :h-box
                  :spacing 6
                  :alignment :center-left
                  :children [{:fx/type copy-menu :id "safety-copy-menu" :text "Copy to…"
                              :projector-id projector-id :others others :mirror? false}
                             {:fx/type copy-menu :id "safety-mirror-menu" :text "Mirror to…"
                              :projector-id projector-id :others others :mirror? true}]})])}))
