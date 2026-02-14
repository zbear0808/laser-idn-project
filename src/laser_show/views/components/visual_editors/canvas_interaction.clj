(ns laser-show.views.components.visual-editors.canvas-interaction
  "Shared canvas interaction infrastructure for visual editor canvases.

   Provides `interactive-canvas` — a single function that wraps the common
   boilerplate pattern of mouse handlers, keyboard scene filters, drag state
   management, and render cycle orchestration used across all canvas-based
   visual editors.

   Each canvas provides callback functions for its specific behavior; this
   module handles the wiring into JavaFX event machinery.

   Callbacks are pure-ish functions: data in, data out. The utility handles
   all side effects — atom updates, event dispatch, and render calls."
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

(defn interactive-canvas
  "Creates an interactive canvas with standardized mouse/keyboard handling.

   Required opts:
     :width            - canvas width in pixels
     :height           - canvas height in pixels
     :render!          - (fn [^Canvas canvas state drag-info]) draws the canvas

   Mouse interaction opts:
     :on-press         - (fn [mx my button state drag-info])
                         Returns map with keys:
                           :state       - new state value (or nil to keep current)
                           :drag-start  - truthy to begin drag; if map, merged into drag-info
                           :drag-id     - id of what is being dragged
                           :dispatch    - event map or vector of event maps
                           :drag-updates - map of extra keys to merge into drag-info
     :on-drag          - (fn [mx my state drag-info])
                         Returns map with keys:
                           :state       - new state value
                           :drag-id     - updated drag id (for re-indexing)
                           :dispatch    - event map or vector of event maps
                           :drag-updates - extra keys to merge into drag-info
     :on-release       - (fn [state drag-info])
                         Returns map with keys:
                           :state       - new state value (or nil)
     :on-hover         - (fn [mx my state drag-info])
                         Returns map with keys:
                           :hover-id    - id of hovered element (or nil)
                           :cursor      - cursor CSS string
     :on-exit          - (fn [state drag-info])
                         Returns map with keys:
                           :state       - new state value (or nil)

   Keyboard interaction opts:
     :on-key           - (fn [^KeyCode key-code shift? state drag-info])
                         Returns map with keys:
                           :state       - new state value
                           :dispatch    - event map or vector of event maps
                           :consumed?   - whether to consume the key event
                           :drag-updates - extra keys to merge into drag-info

   Optional:
     :initial-state          - initial value for canvas-specific state atom
     :initial-drag-state     - extra keys for initial drag state map
     :cursor                 - default cursor CSS value (default \"crosshair\")
     :focus-traversable?     - enable focus traversal (default true when :on-key provided)"
  [{:keys [width height render!
           on-press on-drag on-release on-hover on-key on-exit
           initial-state initial-drag-state
           cursor focus-traversable?]
    :or {cursor "crosshair"}}]
  (let [has-keyboard? (some? on-key)
        focus? (if (some? focus-traversable?) focus-traversable? has-keyboard?)]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created
     (fn [^Canvas canvas]
       (let [state-atom (atom initial-state)
             drag-atom  (atom (merge {:dragging?   false
                                      :hover-id    nil
                                      :mouse-over? false
                                      :drag-id     nil}
                                     initial-drag-state))
             scene-filter-atom (atom nil)

             do-render! (fn []
                          (render! canvas @state-atom @drag-atom))]

         ;; --- Mouse Pressed ---
         (.setOnMousePressed
          canvas
          (reify EventHandler
            (handle [_ e]
              (when on-press
                (let [result (on-press (.getX e) (.getY e) (.getButton e)
                                       @state-atom @drag-atom)]
                  (when result
                    (when (contains? result :state)
                      (reset! state-atom (:state result)))
                    (when (:drag-start result)
                      (swap! drag-atom assoc
                             :dragging? true
                             :drag-id (:drag-id result)))
                    (when-let [du (:drag-updates result)]
                      (swap! drag-atom merge du))
                    (dispatch-result! (:dispatch result))
                    (do-render!)))))))

         ;; --- Mouse Dragged ---
         (.setOnMouseDragged
          canvas
          (reify EventHandler
            (handle [_ e]
              (when (and on-drag (:dragging? @drag-atom))
                (let [result (on-drag (.getX e) (.getY e)
                                      @state-atom @drag-atom)]
                  (when result
                    (when (contains? result :state)
                      (reset! state-atom (:state result)))
                    (when (contains? result :drag-id)
                      (swap! drag-atom assoc :drag-id (:drag-id result)))
                    (when-let [du (:drag-updates result)]
                      (swap! drag-atom merge du))
                    (dispatch-result! (:dispatch result))
                    (do-render!)))))))

         ;; --- Mouse Released ---
         (.setOnMouseReleased
          canvas
          (reify EventHandler
            (handle [_ _e]
              (when on-release
                (let [result (on-release @state-atom @drag-atom)]
                  (when (and result (contains? result :state))
                    (reset! state-atom (:state result)))))
              (swap! drag-atom assoc
                     :dragging? false
                     :drag-id nil)
              (do-render!))))

         ;; --- Mouse Moved (hover) ---
         (when on-hover
           (.setOnMouseMoved
            canvas
            (reify EventHandler
              (handle [_ e]
                (let [result    (on-hover (.getX e) (.getY e)
                                          @state-atom @drag-atom)
                      new-hover (:hover-id result)
                      old-hover (:hover-id @drag-atom)]
                  (when (not= new-hover old-hover)
                    (swap! drag-atom assoc :hover-id new-hover)
                    (do-render!))
                  (when-let [c (:cursor result)]
                    (.setStyle canvas (str "-fx-cursor: " c ";"))))))))

         ;; --- Mouse Entered ---
         (.setOnMouseEntered
          canvas
          (reify EventHandler
            (handle [_ _e]
              (swap! drag-atom assoc :mouse-over? true)
              (when has-keyboard?
                (when-let [scene (.getScene canvas)]
                  (when-not @scene-filter-atom
                    (let [filter (reify EventHandler
                                  (handle [_ e]
                                    (when (and (instance? KeyEvent e)
                                              (:mouse-over? @drag-atom))
                                      (let [^KeyEvent ke e
                                            result (on-key (.getCode ke)
                                                           (.isShiftDown ke)
                                                           @state-atom
                                                           @drag-atom)]
                                        (when result
                                          (when (contains? result :state)
                                            (reset! state-atom (:state result)))
                                          (when-let [du (:drag-updates result)]
                                            (swap! drag-atom merge du))
                                          (dispatch-result! (:dispatch result))
                                          (do-render!)
                                          (when (:consumed? result)
                                            (.consume ke)))))))]
                      (reset! scene-filter-atom filter)
                      (.addEventFilter scene KeyEvent/KEY_PRESSED filter))))))))

         ;; --- Mouse Exited ---
         (.setOnMouseExited
          canvas
          (reify EventHandler
            (handle [_ _e]
              (let [had-hover? (some? (:hover-id @drag-atom))]
                (swap! drag-atom assoc :hover-id nil :mouse-over? false)
                (when on-exit
                  (let [result (on-exit @state-atom @drag-atom)]
                    (when (and result (contains? result :state))
                      (reset! state-atom (:state result)))))
                (do-render!)))))

         ;; --- Initial Setup ---
         (do-render!)
         (when focus?
           (.setFocusTraversable canvas true))
         (.setStyle canvas (str "-fx-cursor: " cursor ";"))))

     :desc {:fx/type :canvas
            :width width
            :height height
            :style (str "-fx-cursor: " cursor ";")}}))
