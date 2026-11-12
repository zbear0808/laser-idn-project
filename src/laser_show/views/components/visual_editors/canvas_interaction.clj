(ns laser-show.views.components.visual-editors.canvas-interaction
  "Shared canvas interaction infrastructure for visual editor canvases.

   Provides `interactive-canvas` — wraps common mouse/keyboard handling,
   drag state management, and render orchestration.

   1. Canvas node is reused across renders, using :draw prop for declarative redraws.
   2. Does NOT maintain internal value state — renders whatever :value is in props
      (or the :preview-value of a drag in progress).
   3. Interaction state (drag, hover, preview, open undo transaction) is
      view-local: an atom in the Canvas userData, read and written
      synchronously on the FX thread. It never goes through the event queue,
      so the next mouse event always sees the previous one's state.
   4. Mouse handlers dispatch the callbacks' events and call render!
      immediately with the new interaction state."
  (:require [cljfx.api :as fx]
            [laser-show.events.core :as events])
  (:import [javafx.scene.canvas Canvas]
           [javafx.scene.input KeyEvent KeyCode MouseEvent ScrollEvent]))

(defn- dispatch-result!
  "Dispatch event maps from a callback result.
   Supports single map or vector of maps.
   Tags each event with :interaction/type when provided."
  ([dispatch-value] (dispatch-result! dispatch-value nil))
  ([dispatch-value interaction-type]
   (when dispatch-value
     (let [tag-fn (if interaction-type
                    #(assoc % :interaction/type interaction-type)
                    identity)]
       (if (sequential? dispatch-value)
         (run! (comp events/dispatch! tag-fn) dispatch-value)
         (events/dispatch! (tag-fn dispatch-value)))))))

(defn- interaction-atom
  "The canvas's interaction atom (set up in :on-created), or nil."
  [^Canvas canvas]
  (:interaction (.getUserData canvas)))

(defn- get-drag-state
  "Current interaction state of a canvas."
  [^Canvas canvas]
  (some-> (interaction-atom canvas) deref))

(defn- extract-event-info
  "Extracts common information from a JavaFX canvas interaction event into a hashmap."
  [^javafx.event.Event e]
  (let [canvas ^Canvas (.getSource e)
        canvas-id (:canvas-id (.getUserData canvas))
        drag-state (or (get-drag-state canvas) {})]
    (cond-> {:e e
             :canvas canvas
             :canvas-id canvas-id
             :drag-state drag-state}
      (instance? MouseEvent e)
      (assoc :x (.getX ^MouseEvent e)
             :y (.getY ^MouseEvent e)
             :button (.getButton ^MouseEvent e)
             :modifiers {:shift? (.isShiftDown ^MouseEvent e)
                         :shortcut? (.isShortcutDown ^MouseEvent e)
                         :alt? (.isAltDown ^MouseEvent e)})

      (instance? KeyEvent e)
      (assoc :key-code (.getCode ^KeyEvent e)
             :shift? (.isShiftDown ^KeyEvent e)
             :modifiers {:shift? (.isShiftDown ^KeyEvent e)
                         :shortcut? (.isShortcutDown ^KeyEvent e)
                         :alt? (.isAltDown ^KeyEvent e)})

      (instance? ScrollEvent e)
      (assoc :x (.getX ^ScrollEvent e)
             :y (.getY ^ScrollEvent e)
             :delta-x (.getDeltaX ^ScrollEvent e)
             :delta-y (.getDeltaY ^ScrollEvent e)
             :alt? (.isAltDown ^ScrollEvent e)
             :control? (or (.isControlDown ^ScrollEvent e)
                           (.isShortcutDown ^ScrollEvent e))
             :shift? (.isShiftDown ^ScrollEvent e)))))

(defn- update-drag-state!
  "Merge updates into the canvas's interaction state (FX thread, synchronous)."
  [^Canvas canvas updates]
  (when-let [a (interaction-atom canvas)]
    (swap! a merge updates)))

(defn- draw-interactive-canvas!
  "Called on every render cycle via cljfx Canvas :draw prop. Triggers render! callback."
  [^Canvas canvas props]
  (let [{:keys [render! value]} props
        drag-state (get-drag-state canvas)]
    (when render!
      (let [render-value (or (:preview-value drag-state) value)]
        (render! canvas render-value drag-state)))))

(defn interactive-canvas
  "Creates an interactive canvas with standardized mouse/keyboard handling.
   Interaction state lives in a view-local atom (see the ns docstring).

   Required opts:
     :width            - canvas width in pixels
     :height           - canvas height in pixels
     :value            - CURRENT value to render (passed from props)
     :render!          - (fn [^Canvas canvas value drag-info]) draws the canvas

   Mouse interaction opts (all receive 'value' from props). The drag-info
   passed to on-press, on-drag, on-release and on-key also carries
   :modifiers {:shift? :shortcut? :alt?} for the current event.
     :on-press         - (fn [mx my button value drag-info])
                         Returns map with keys:
                           :drag-start  - truthy to begin drag behavior
                           :drag-id     - id of what is being dragged
                           :dispatch    - event map or vector of event maps
                           :drag-updates - map of extra keys to merge into drag-info
                           :preview-value - immediate local value to render
                           :transaction-label - undo label for the drag
     :on-drag          - (fn [mx my value drag-info])
                         Returns map with keys:
                           :drag-id     - updated drag id
                           :dispatch    - event map or vector of event maps
                           :drag-updates - extra keys to merge into drag-info
                           :preview-value - immediate local value to render during drag
     :on-release       - (fn [value drag-info])
                         Returns map with :dispatch
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
                           :cancel-drag? - end the drag without committing:
                                           clears the preview and aborts the
                                           undo transaction, if one is open

   Optional:
     :on-scroll              - (fn [{:keys [delta-x delta-y x y alt? control? shift? value drag-state]}])
                               Returns map with :dispatch, optional :consumed? boolean
     :initial-drag-state     - extra keys for initial drag state map
     :cursor                 - default cursor CSS value (default \"crosshair\")
     :focus-traversable?     - enable focus traversal
     :managed                - JavaFX managed flag (default true); false keeps
                               the canvas out of its parent's layout     :history-transaction?   - wrap each drag in an undo transaction
                               (:history/begin-transaction on press, commit
                               on release). Default true. Pass false when the
                               canvas commits one edit on release itself."
  [{:keys [width height value render!
           on-press on-drag on-release on-hover on-key on-exit on-scroll
           initial-drag-state history-transaction?
           cursor focus-traversable? id style-class managed]
    :or {cursor "crosshair"
         value nil
         history-transaction? true}
    :as props}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [^Canvas canvas]
                 (let [canvas-id (keyword (gensym "canvas-"))]
                   (.setUserData canvas {:canvas-id canvas-id
                                         :interaction (atom (merge {:dragging?     false
                                                                    :hover-id      nil
                                                                    :mouse-over?   false
                                                                    :drag-id       nil
                                                                    :preview-value nil
                                                                    :transaction-open? false}
                                                                   initial-drag-state))})
                   (draw-interactive-canvas! canvas props)))
   :desc (cond->
          {:fx/type :canvas
          :id id
          :managed (if (some? managed) managed true)
          :width width
          :height height
          :style (str "-fx-cursor: " cursor ";")
          :focus-traversable (let [has-keyboard? (some? on-key)]
                               (if (some? focus-traversable?)
                                 focus-traversable?
                                 has-keyboard?))
          :draw (fn [^Canvas canvas]
                  (draw-interactive-canvas! canvas props))

          :on-mouse-pressed
          (fn [e]
            (let [{:keys [canvas canvas-id drag-state x y button modifiers]} (extract-event-info e)]
              (when (.isFocusTraversable canvas)
                (.requestFocus canvas)
                ;; Don't let the press bubble to an enclosing ScrollPane,
                ;; which would take the keyboard focus back from the canvas
                (.consume ^MouseEvent e))
              (when-let [result (and on-press (on-press x y button value (assoc drag-state :modifiers modifiers)))]
                (let [open-transaction? (and (:drag-start result)
                                             history-transaction?
                                             (not (:transaction-open? drag-state)))
                      new-drag (cond-> drag-state
                                 (:drag-start result) (assoc :dragging? true :drag-id (:drag-id result))
                                 (contains? result :preview-value) (assoc :preview-value (:preview-value result))
                                 (:drag-updates result) (merge (:drag-updates result))
                                 open-transaction? (assoc :transaction-open? true))]
                  (when open-transaction?
                    (events/dispatch! {:event/type :history/begin-transaction
                                       :label (or (:transaction-label result) "Canvas Edit")
                                       :source-id [:canvas canvas-id]}))
                  (update-drag-state! canvas new-drag)
                  (dispatch-result! (:dispatch result) :start)
                  (render! canvas (or (:preview-value new-drag) value) new-drag)))))

          :on-mouse-dragged
          (fn [e]
            (let [{:keys [canvas drag-state x y modifiers]} (extract-event-info e)]
              (when-let [result (and on-drag
                                     (:dragging? drag-state)
                                     (on-drag x y value (assoc drag-state :modifiers modifiers)))]
                (let [new-drag (cond-> drag-state
                                 (contains? result :drag-id) (assoc :drag-id (:drag-id result))
                                 (:preview-value result) (assoc :preview-value (:preview-value result))
                                 (:drag-updates result) (merge (:drag-updates result)))]
                  (update-drag-state! canvas new-drag)
                  (dispatch-result! (:dispatch result) :drag)
                  (render! canvas (or (:preview-value new-drag) value) new-drag)))))

          :on-mouse-released
          (fn [e]
            (let [{:keys [canvas canvas-id drag-state modifiers]} (extract-event-info e)
                  result (when (and on-release (:dragging? drag-state))
                           (on-release value (assoc drag-state :modifiers modifiers)))
                  new-drag (assoc drag-state
                                  :dragging? false
                                  :drag-id nil
                                  :preview-value nil
                                  :transaction-open? false)]
              ;; Release always closes a transaction this canvas opened, even
              ;; after a click with no drag (TL-13)
              (update-drag-state! canvas new-drag)
              (dispatch-result! (:dispatch result) :stop)
              (when (:transaction-open? drag-state)
                (events/dispatch! {:event/type :history/commit-transaction
                                   :source-id [:canvas canvas-id]}))
              (render! canvas value new-drag)))

          :on-mouse-moved
          (fn [e]
            (let [{:keys [canvas drag-state x y]} (extract-event-info e)]
              (when-let [result (and on-hover (on-hover x y value drag-state))]
                (let [new-hover (:hover-id result)
                      old-hover (:hover-id drag-state)]
                  (when (not= new-hover old-hover)
                    (let [new-drag (assoc drag-state :hover-id new-hover)]
                      (update-drag-state! canvas new-drag)
                      (render! canvas (or (:preview-value new-drag) value) new-drag)))
                  (when-let [c (:cursor result)]
                    (.setStyle canvas (str "-fx-cursor: " c ";")))))))

          :on-mouse-entered
          (fn [e]
            (let [{:keys [canvas drag-state]} (extract-event-info e)
                  new-drag (assoc drag-state :mouse-over? true)]
              (update-drag-state! canvas new-drag)))

          :on-mouse-exited
          (fn [e]
            (let [{:keys [canvas drag-state]} (extract-event-info e)
                  ;; Keep a drag's preview: the mouse may leave mid-drag
                  new-drag (cond-> (assoc drag-state :hover-id nil :mouse-over? false)
                             (not (:dragging? drag-state)) (assoc :preview-value nil))]
              (update-drag-state! canvas new-drag)
              (when on-exit (on-exit value new-drag))
              (render! canvas value new-drag)))

          :on-key-pressed
          (fn [e]
            (let [{:keys [canvas canvas-id drag-state key-code shift? modifiers]} (extract-event-info e)]
              (when-let [result (and on-key (on-key key-code shift? value (assoc drag-state :modifiers modifiers)))]
                (let [cancel? (:cancel-drag? result)
                      new-drag (cond-> drag-state
                                 (:drag-updates result) (merge (:drag-updates result))
                                 cancel? (assoc :dragging? false :drag-id nil
                                                :preview-value nil :transaction-open? false))]
                  (when (or (:drag-updates result) cancel?)
                    (update-drag-state! canvas new-drag))
                  (when (and cancel? (:transaction-open? drag-state))
                    (events/dispatch! {:event/type :history/abort-transaction
                                       :source-id [:canvas canvas-id]}))
                  (dispatch-result! (:dispatch result))
                  (render! canvas (or (:preview-value new-drag) value) new-drag)
                  (when (:consumed? result)
                    (.consume e)))))

          :on-scroll
          (fn [e]
            (let [info (extract-event-info e)
                  scroll-params (assoc info :value value)]
              (when-let [result (and on-scroll
                                     (try
                                       (on-scroll scroll-params)
                                       (catch clojure.lang.ArityException _
                                         (let [{:keys [delta-x delta-y drag-state]} info]
                                           (on-scroll delta-x delta-y value drag-state)))))]
                (dispatch-result! (:dispatch result))
                (when (:consumed? result)
                  (.consume e))))))}
          ;; cljfx can't set a nil style-class list
          style-class (assoc :style-class style-class))})