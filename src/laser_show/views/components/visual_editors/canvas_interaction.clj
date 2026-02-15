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
   3. Event handlers are now attached via Cljfx props, ensuring they always closed over fresh props.
   4. Interaction state (dragging?) is kept in a persistent atom in UserData."
  (:require [cljfx.api :as fx]
            [laser-show.events.core :as events]
            [clojure.tools.logging :as log])
  (:import [javafx.scene.canvas Canvas]
           [javafx.scene.input MouseButton KeyEvent KeyCode]
           [javafx.scene.text Font FontWeight]
           [javafx.event EventHandler]))

(defn- dispatch-result!
  "Dispatch event maps from a callback result.
   Supports single map or vector of maps."
  [dispatch-value]
  (when dispatch-value
    (if (sequential? dispatch-value)
      (run! events/dispatch! dispatch-value)
      (events/dispatch! dispatch-value))))

(defn- get-drag-atom
  [^javafx.event.Event e]
  (let [source (.getSource e)
        user-data (.getUserData source)]
    (:drag-atom user-data)))

(defn- update-interactive-canvas!
  "Called on every render. Triggers render! callback."
  [^Canvas canvas props drag-atom]
  (let [{:keys [render! value cursor focus-traversable? on-key]} props

        has-keyboard? (some? on-key)
        focus? (if (some? focus-traversable?) focus-traversable? has-keyboard?)]

    ;; Handle focus/style updates
    (when focus?
      (.setFocusTraversable canvas true))
    (when cursor
      (.setStyle canvas (str "-fx-cursor: " cursor ";")))

    ;; Trigger render with new props and current drag state
    (when render!
      (let [drag-state @drag-atom
            ;; PREFER PREVIEW VALUE IF EXISTS
            render-value (or (:preview-value drag-state) value)]
        (render! canvas render-value drag-state)))))

(defn- setup-interactive-canvas!
  "One-time setup for the canvas. Initializes UserData."
  [^Canvas canvas]
  ;; Initialize UserData with atoms that will hold constant interaction state
  (let [drag-atom  (atom {:dragging?   false
                          :hover-id    nil
                          :mouse-over? false
                          :drag-id     nil
                          :preview-value nil})
        scene-filter-atom (atom nil)]

    (.setUserData canvas {:drag-atom drag-atom
                          :scene-filter-atom scene-filter-atom})))

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
                           :preview-value - (OPTIONAL) immediate local value to render during drag
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
                 (let [drag-atom (:drag-atom (.getUserData canvas))]
                   (update-interactive-canvas! canvas props drag-atom)))
   :on-advanced (fn [^Canvas canvas]
                  (log/info "DEBUG: on-advanced called")
                  (let [drag-atom (:drag-atom (.getUserData canvas))]
                    (update-interactive-canvas! canvas props drag-atom)))
   :desc {:fx/type :canvas
          :width width
          :height height
          ;; Force update by changing accessible-help. This avoids colliding with UserData.
          :accessible-help (str (java.util.UUID/randomUUID))
          :style (str "-fx-cursor: " cursor ";")

          :on-mouse-pressed
          (fn [e]
            (let [drag-atom (get-drag-atom e)]
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
                    ;; Re-render immediately
                    (render! (.getSource e)
                             (or (:preview-value @drag-atom) value)
                             @drag-atom))))))

          :on-mouse-dragged
          (fn [e]
            (let [drag-atom (get-drag-atom e)]
              (when (and on-drag (:dragging? @drag-atom))
                (let [result (on-drag (.getX e) (.getY e)
                                      value @drag-atom)]
                  (when result
                    (when (contains? result :drag-id)
                      (swap! drag-atom assoc :drag-id (:drag-id result)))

                    ;; Handle Preview Value
                    (if-let [pv (:preview-value result)]
                      (swap! drag-atom assoc :preview-value pv)
                      nil)

                    (when-let [du (:drag-updates result)]
                      (swap! drag-atom merge du))
                    (dispatch-result! (:dispatch result))

                    (render! (.getSource e)
                             (or (:preview-value @drag-atom) value)
                             @drag-atom))))))

          :on-mouse-released
          (fn [e]
            (let [drag-atom (get-drag-atom e)]
              (when on-release
                (let [result (on-release value @drag-atom)]
                  (dispatch-result! (:dispatch result))))
              (swap! drag-atom assoc
                     :dragging? false
                     :drag-id nil
                     :preview-value nil)
              (render! (.getSource e) value @drag-atom)))

          :on-mouse-moved
          (fn [e]
            (let [drag-atom (get-drag-atom e)
                  canvas (.getSource e)]
              (when on-hover
                (let [result    (on-hover (.getX e) (.getY e)
                                          value @drag-atom)
                      new-hover (:hover-id result)
                      old-hover (:hover-id @drag-atom)]
                  (when (not= new-hover old-hover)
                    (swap! drag-atom assoc :hover-id new-hover)
                    (render! canvas
                             (or (:preview-value @drag-atom) value)
                             @drag-atom))
                  (when-let [c (:cursor result)]
                    (.setStyle canvas (str "-fx-cursor: " c ";")))))))

          :on-mouse-entered
          (fn [e]
            (let [drag-atom (get-drag-atom e)
                  canvas (.getSource e)
                  user-data (.getUserData canvas)
                  scene-filter-atom (:scene-filter-atom user-data)]
              (swap! drag-atom assoc :mouse-over? true)
              (when on-key
                (when-let [scene (.getScene canvas)]
                  (when-not @scene-filter-atom
                    (let [filter (reify EventHandler
                                   (handle [_ e]
                                     (when (and (instance? KeyEvent e)
                                                (:mouse-over? @drag-atom))
                                       (let [^KeyEvent ke e
                                             ;; Use closed-over ON-KEY and VALUE
                                             result (when on-key
                                                      (on-key (.getCode ke)
                                                              (.isShiftDown ke)
                                                              value
                                                              @drag-atom))]
                                         (when result
                                           (when-let [du (:drag-updates result)]
                                             (swap! drag-atom merge du))
                                           (dispatch-result! (:dispatch result))
                                           (render! canvas
                                                    (or (:preview-value @drag-atom) value)
                                                    @drag-atom)
                                           (when (:consumed? result)
                                             (.consume ke)))))))]
                      (reset! scene-filter-atom filter)
                      (.addEventFilter scene KeyEvent/KEY_PRESSED filter)))))))

          :on-mouse-exited
          (fn [e]
            (let [drag-atom (get-drag-atom e)
                  canvas (.getSource e)
                  user-data (.getUserData canvas)
                  scene-filter-atom (:scene-filter-atom user-data)]
              (swap! drag-atom assoc
                     :hover-id nil
                     :mouse-over? false
                     :preview-value nil)

              ;; Remove key filter to prevent stale closures and leaks
              (when-let [filter @scene-filter-atom]
                (when-let [scene (.getScene canvas)]
                  (.removeEventFilter scene KeyEvent/KEY_PRESSED filter))
                (reset! scene-filter-atom nil))

              (when on-exit
                (on-exit value @drag-atom))
              (render! canvas value @drag-atom)))}})