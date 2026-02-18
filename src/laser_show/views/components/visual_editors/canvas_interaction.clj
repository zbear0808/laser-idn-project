(ns laser-show.views.components.visual-editors.canvas-interaction
  "Shared canvas interaction infrastructure for visual editor canvases.

   Provides `interactive-canvas` — wraps common mouse/keyboard handling,
   drag state management, and render orchestration.

   GLOBAL STATE VERSION:
   1. Canvas node is reused across renders (via ext-on-instance-lifecycle).
   2. Does NOT maintain internal value state — renders whatever :value is in props.
   3. Interaction state (drag, hover) stored in global state at
      [:ui :canvas-drag <canvas-id>], not in a local atom.
   4. Mouse handlers dispatch canvas events AND call render! immediately
      with computed state for responsive feedback."
  (:require [cljfx.api :as fx]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state])
  (:import [javafx.scene.canvas Canvas]
           [javafx.scene.input KeyEvent KeyCode]))

(defn- dispatch-result!
  "Dispatch event maps from a callback result.
   Supports single map or vector of maps."
  [dispatch-value]
  (when dispatch-value
    (if (sequential? dispatch-value)
      (run! events/dispatch! dispatch-value)
      (events/dispatch! dispatch-value))))

(defn- get-canvas-id
  "Get the canvas-id from a JavaFX event's source UserData."
  [^javafx.event.Event e]
  (:canvas-id (.getUserData (.getSource e))))

(defn- get-drag-state
  "Read the current drag state for a canvas from global state."
  [canvas-id]
  (get-in (state/get-raw-state) [:ui :canvas-drag canvas-id]))

(defn- update-drag-state!
  "Dispatch a canvas drag state update to global state."
  [canvas-id updates]
  (events/dispatch! {:event/type :canvas/update-drag
                     :canvas-id canvas-id
                     :updates updates}))

(defn- update-interactive-canvas!
  "Called on every render cycle. Triggers render! callback."
  [^Canvas canvas props]
  (let [{:keys [render! value cursor focus-traversable? on-key]} props
        canvas-id (:canvas-id (.getUserData canvas))
        drag-state (get-drag-state canvas-id)
        has-keyboard? (some? on-key)
        focus? (if (some? focus-traversable?) focus-traversable? has-keyboard?)]
    (when focus?
      (.setFocusTraversable canvas true))
    (when cursor
      (.setStyle canvas (str "-fx-cursor: " cursor ";")))
    (when render!
      (let [render-value (or (:preview-value drag-state) value)]
        (render! canvas render-value drag-state)))))

(defn interactive-canvas
  "Creates an interactive canvas with standardized mouse/keyboard handling.
   GLOBAL STATE VERSION: Interaction state stored in [:ui :canvas-drag].

   Required opts:
     :width            - canvas width in pixels
     :height           - canvas height in pixels
     :value            - CURRENT value to render (passed from props)
     :render!          - (fn [^Canvas canvas value drag-info]) draws the canvas

   Mouse interaction opts (all receive 'value' from props):
     :on-press         - (fn [mx my button value drag-info])
                         Returns map with keys:
                           :drag-start  - truthy to begin drag behavior
                           :drag-id     - id of what is being dragged
                           :dispatch    - event map or vector of event maps
                           :drag-updates - map of extra keys to merge into drag-info
     :on-drag          - (fn [mx my value drag-info])
                         Returns map with keys:
                           :drag-id     - updated drag id
                           :dispatch    - event map or vector of event maps
                           :drag-updates - extra keys to merge into drag-info
                           :preview-value - immediate local value to render during drag
     :on-release       - (fn [value drag-info])
     :on-hover         - (fn [mx my value drag-info])
                         Returns map with keys:
                           :hover-id    - id of hovered element
                           :cursor      - cursor CSS string
     :on-exit          - (fn [value drag-info])

   Keyboard interaction opts:
     :on-key           - (fn [^KeyCode key-code shift? value drag-info])
                         Returns map with keys:
                           :dispatch    - event map
                           :consumed?   - whether to consume event
                           :drag-updates - extra keys merge

   Optional:
     :initial-drag-state     - extra keys for initial drag state map
     :cursor                 - default cursor CSS value (default \"crosshair\")
     :focus-traversable?     - enable focus traversal"
  [{:keys [width height value render!
           on-press on-drag on-release on-hover on-key on-exit
           initial-drag-state
           cursor focus-traversable?]
    :or {cursor "crosshair"
         value nil}
    :as props}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [^Canvas canvas]
                 (let [canvas-id (keyword (gensym "canvas-"))]
                   (.setUserData canvas {:canvas-id canvas-id})
                   (update-drag-state! canvas-id
                                       (merge {:dragging?     false
                                               :hover-id      nil
                                               :mouse-over?   false
                                               :drag-id       nil
                                               :preview-value nil}
                                              initial-drag-state))
                   (update-interactive-canvas! canvas props)))
   :on-advanced (fn [^Canvas canvas]
                  (update-interactive-canvas! canvas props))
   :on-deleted (fn [^Canvas canvas]
                 (let [canvas-id (:canvas-id (.getUserData canvas))]
                   (events/dispatch! {:event/type :canvas/clear-drag
                                      :canvas-id canvas-id})))
   :desc {:fx/type :canvas
          :width width
          :height height
          :style (str "-fx-cursor: " cursor ";")
          :focus-traversable (let [has-keyboard? (some? on-key)]
                               (if (some? focus-traversable?)
                                 focus-traversable?
                                 has-keyboard?))

          :on-mouse-pressed
          (fn [e]
            (when on-press
              (let [canvas-id (get-canvas-id e)
                    drag-state (get-drag-state canvas-id)
                    result (on-press (.getX e) (.getY e) (.getButton e)
                                     value drag-state)]
                (when result
                  (let [new-drag (cond-> (or drag-state {})
                                   (:drag-start result)
                                   (assoc :dragging? true
                                          :drag-id (:drag-id result))
                                   (:drag-updates result)
                                   (merge (:drag-updates result)))]
                    (update-drag-state! canvas-id new-drag)
                    (dispatch-result! (:dispatch result))
                    (render! (.getSource e)
                             (or (:preview-value new-drag) value)
                             new-drag))))))

          :on-mouse-dragged
          (fn [e]
            (when on-drag
              (let [canvas-id (get-canvas-id e)
                    drag-state (get-drag-state canvas-id)]
                (when (:dragging? drag-state)
                  (let [result (on-drag (.getX e) (.getY e)
                                        value drag-state)]
                    (when result
                      (let [new-drag (cond-> drag-state
                                       (contains? result :drag-id)
                                       (assoc :drag-id (:drag-id result))
                                       (:preview-value result)
                                       (assoc :preview-value (:preview-value result))
                                       (:drag-updates result)
                                       (merge (:drag-updates result)))]
                        (update-drag-state! canvas-id new-drag)
                        (dispatch-result! (:dispatch result))
                        (render! (.getSource e)
                                 (or (:preview-value new-drag) value)
                                 new-drag))))))))

          :on-mouse-released
          (fn [e]
            (let [canvas-id (get-canvas-id e)
                  drag-state (get-drag-state canvas-id)]
              (when on-release
                (let [result (on-release value drag-state)]
                  (dispatch-result! (:dispatch result))))
              (let [new-drag (assoc (or drag-state {})
                                    :dragging? false
                                    :drag-id nil
                                    :preview-value nil)]
                (update-drag-state! canvas-id new-drag)
                (render! (.getSource e) value new-drag))))

          :on-mouse-moved
          (fn [e]
            (let [canvas-id (get-canvas-id e)
                  canvas (.getSource e)
                  drag-state (get-drag-state canvas-id)]
              (when on-hover
                (let [result (on-hover (.getX e) (.getY e)
                                       value drag-state)
                      new-hover (:hover-id result)
                      old-hover (:hover-id drag-state)]
                  (when (not= new-hover old-hover)
                    (let [new-drag (assoc (or drag-state {}) :hover-id new-hover)]
                      (update-drag-state! canvas-id new-drag)
                      (render! canvas
                               (or (:preview-value new-drag) value)
                               new-drag)))
                  (when-let [c (:cursor result)]
                    (.setStyle canvas (str "-fx-cursor: " c ";")))))))

          :on-mouse-entered
          (fn [e]
            (let [canvas-id (get-canvas-id e)
                  drag-state (get-drag-state canvas-id)
                  new-drag (assoc (or drag-state {}) :mouse-over? true)]
              (update-drag-state! canvas-id new-drag)))

          :on-mouse-exited
          (fn [e]
            (let [canvas-id (get-canvas-id e)
                  canvas (.getSource e)
                  drag-state (get-drag-state canvas-id)
                  new-drag (assoc (or drag-state {})
                                  :hover-id nil
                                  :mouse-over? false
                                  :preview-value nil)]
              (update-drag-state! canvas-id new-drag)
              (when on-exit
                (on-exit value new-drag))
              (render! canvas value new-drag)))

          :on-key-pressed
          (fn [e]
            (when on-key
              (let [^KeyEvent ke e
                    canvas (.getSource ke)
                    canvas-id (:canvas-id (.getUserData canvas))
                    drag-state (get-drag-state canvas-id)
                    result (on-key (.getCode ke)
                                   (.isShiftDown ke)
                                   value
                                   drag-state)]
                (when result
                  (let [new-drag (if-let [du (:drag-updates result)]
                                   (merge (or drag-state {}) du)
                                   drag-state)]
                    (when (:drag-updates result)
                      (update-drag-state! canvas-id new-drag))
                    (dispatch-result! (:dispatch result))
                    (render! canvas
                             (or (:preview-value new-drag) value)
                             new-drag)
                    (when (:consumed? result)
                      (.consume ke)))))))}})