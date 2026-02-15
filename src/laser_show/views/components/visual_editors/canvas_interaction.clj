(ns laser-show.views.components.visual-editors.canvas-interaction
  "Shared canvas interaction infrastructure for visual editor canvases.

   Provides `interactive-canvas` — a single function that wraps the common
   boilerplate pattern of mouse handlers, keyboard scene filters, drag state
   management, and render cycle orchestration used across all canvas-based
   visual editors.

   Refactored to be STATELESS and STABLE:
   1. The Canvas node is reused across renders (via user data + manual update).
   2. It does NOT maintain its own 'value' state atom. It renders whatever
      `value` is passed in via props.
   3. Interaction callbacks (on-drag, etc.) are responsible for calculating
      new values and dispatching events to update the global state.
   4. The component waits for the new props to arrive to re-render the new state."
  (:require [cljfx.api :as fx]
            [laser-show.events.core :as events])
  (:import [javafx.scene.canvas Canvas]
           [javafx.scene.input MouseButton KeyEvent KeyCode]
           [javafx.event EventHandler]))

(defn- dispatch-result!
  "Dispatch event maps from a callback result.
   Supports single map or vector of maps."
  [dispatch-value]
  (when dispatch-value
    (if (sequential? dispatch-value)
      (run! events/dispatch! dispatch-value)
      (events/dispatch! dispatch-value))))
(defn- update-interactive-canvas!
  "Called on every render. Updates props in UserData and triggers render."
  [^Canvas canvas props]
  (when-let [user-data (.getUserData canvas)]
    (let [{:keys [props-atom drag-atom]} user-data
          {:keys [render! value cursor focus-traversable? on-key]} props

          has-keyboard? (some? on-key)
          focus? (if (some? focus-traversable?) focus-traversable? has-keyboard?)]

      ;; Update the props atom so handlers see the new callbacks/values
      (reset! props-atom props)

      ;; Handle focus/style updates
      (when focus?
        (.setFocusTraversable canvas true))
      (when cursor
        (.setStyle canvas (str "-fx-cursor: " cursor ";")))

      ;; Trigger render with new props
      (when render!
        (render! canvas value @drag-atom)))))

(defn- setup-interactive-canvas!
  "One-time setup for the canvas. Initializes UserData and attaches constant listeners."
  [^Canvas canvas]
  ;; Initialize UserData with atoms that will hold the LATEST props and interaction state
  (let [props-atom (atom {})
        drag-atom  (atom {:dragging?   false
                          :hover-id    nil
                          :mouse-over? false
                          :drag-id     nil})
        scene-filter-atom (atom nil)

        ;; Helper to get current props
        get-props (fn [] @props-atom)

        ;; Helper to run render! with current value and drag state
        do-render! (fn []
                     (let [{:keys [render! value]} @props-atom]
                       (when render!
                         (render! canvas value @drag-atom))))]

    (.setUserData canvas {:props-atom props-atom
                          :drag-atom drag-atom
                          :scene-filter-atom scene-filter-atom})

    ;; --- Persistent Event Handlers ---
    ;; These are attached ONCE. They look up the *latest* callbacks from props-atom.

    (.setOnMousePressed
     canvas
     (reify EventHandler
       (handle [_ e]
         (let [{:keys [on-press value]} (get-props)]
           (when on-press
             (let [result (on-press (.getX e) (.getY e) (.getButton e)
                                    value @drag-atom)]
               (when result
                 (when (:drag-start result)
                   (swap! drag-atom assoc
                          :dragging? true
                          :drag-id (:drag-id result)))
                 (when-let [du (:drag-updates result)]
                   (swap! drag-atom merge du))
                 (dispatch-result! (:dispatch result))
                 ;; Re-render immediately to reflect drag state changes (e.g. highlight)
                 (do-render!))))))))

    (.setOnMouseDragged
     canvas
     (reify EventHandler
       (handle [_ e]
         (let [{:keys [on-drag value]} (get-props)]
           (when (and on-drag (:dragging? @drag-atom))
             (let [result (on-drag (.getX e) (.getY e)
                                   value @drag-atom)]
               (when result
                 (when (contains? result :drag-id)
                   (swap! drag-atom assoc :drag-id (:drag-id result)))
                 (when-let [du (:drag-updates result)]
                   (swap! drag-atom merge du))
                 (dispatch-result! (:dispatch result))
                 ;; Note: We re-render to reflect drag-state, but the VALUE 
                 ;; usually hasn't changed yet (waiting for props update via event loop).
                 (do-render!))))))))

    (.setOnMouseReleased
     canvas
     (reify EventHandler
       (handle [_ _e]
         (let [{:keys [on-release value]} (get-props)]
           (when on-release
             (let [result (on-release value @drag-atom)]
               ;; Side effects from release?
               (dispatch-result! (:dispatch result))))
           (swap! drag-atom assoc
                  :dragging? false
                  :drag-id nil)
           (do-render!)))))

    (.setOnMouseMoved
     canvas
     (reify EventHandler
       (handle [_ e]
         (let [{:keys [on-hover value]} (get-props)]
           (when on-hover
             (let [result    (on-hover (.getX e) (.getY e)
                                       value @drag-atom)
                   new-hover (:hover-id result)
                   old-hover (:hover-id @drag-atom)]
               (when (not= new-hover old-hover)
                 (swap! drag-atom assoc :hover-id new-hover)
                 (do-render!))
               (when-let [c (:cursor result)]
                 (.setStyle canvas (str "-fx-cursor: " c ";"))))))))

     (.setOnMouseEntered
      canvas
      (reify EventHandler
        (handle [_ _e]
          (swap! drag-atom assoc :mouse-over? true)
          (let [{:keys [on-key]} (get-props)]
            (when on-key
              (when-let [scene (.getScene canvas)]
                (when-not @scene-filter-atom
                  (let [filter (reify EventHandler
                                 (handle [_ e]
                                   (when (and (instance? KeyEvent e)
                                              (:mouse-over? @drag-atom))
                                     (let [^KeyEvent ke e
                                           {:keys [on-key value]} (get-props)
                                           result (when on-key
                                                    (on-key (.getCode ke)
                                                            (.isShiftDown ke)
                                                            value
                                                            @drag-atom))]
                                       (when result
                                         (when-let [du (:drag-updates result)]
                                           (swap! drag-atom merge du))
                                         (dispatch-result! (:dispatch result))
                                         (do-render!)
                                         (when (:consumed? result)
                                           (.consume ke)))))))]
                    (reset! scene-filter-atom filter)
                    (.addEventFilter scene KeyEvent/KEY_PRESSED filter)))))))))

     (.setOnMouseExited
      canvas
      (reify EventHandler
        (handle [_ _e]
          (let [{:keys [on-exit value]} (get-props)
                had-hover? (some? (:hover-id @drag-atom))]
            (swap! drag-atom assoc :hover-id nil :mouse-over? false)
            (when on-exit
              (on-exit value @drag-atom))
            (do-render!)))))))

  

  
)

(defn interactive-canvas
  "Creates an interactive canvas with standardized mouse/keyboard handling.
   STATELESS VERSION: Does not maintain internal value state.

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
                           :dispatch    - event map or vector of event maps (to update global state)
                           :drag-updates - map of extra keys to merge into drag-info
     :on-drag          - (fn [mx my value drag-info])
                         Returns map with keys:
                           :drag-id     - updated drag id
                           :dispatch    - event map or vector of event maps
                           :drag-updates - extra keys to merge into drag-info
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
                 (setup-interactive-canvas! canvas)
                 ;; Initial update
                 (update-interactive-canvas! canvas props))
   :on-advanced (fn [^Canvas canvas]
                  (update-interactive-canvas! canvas props))
   :desc {:fx/type :canvas
          :width width
          :height height
          :style (str "-fx-cursor: " cursor ";")}})