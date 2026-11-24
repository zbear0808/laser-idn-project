(ns laser-show.ui-test.testfx
  "Small TestFX DSL for cljfx UI tests.

   The helpers deliberately query the JavaFX scene graph instead of caching
   nodes, so every assertion observes the current cljfx render."
  (:require [clojure.test :refer [is]])
  (:import [javafx.application Platform]
           [javafx.scene Node]
           [javafx.scene.control CheckBox ComboBox Control Labeled ScrollPane TextInputControl ToggleButton]
           [javafx.scene SnapshotParameters]
           [javafx.scene.image PixelFormat]
           [javafx.event ActionEvent Event]
           [javafx.scene.text Text]
           [javafx.stage Stage Window]
           [javafx.embed.swing SwingFXUtils]
           [javafx.scene.input DragEvent KeyCode MouseButton MouseEvent TransferMode]
           [javafx.util Pair]
           [com.sun.javafx.scene.input DragboardHelper]
           [com.sun.javafx.tk TKClipboard]
           [javax.imageio ImageIO]
           [java.io File]
           [javafx.geometry Point2D Pos]
           [org.testfx.api FxRobot FxRobotInterface FxToolkit]
           [org.testfx.robot Motion]
           [org.testfx.util WaitForAsyncUtils]))

(def ^:private default-timeout-ms 3000)

(defn init-toolkit!
  "Initialize TestFX's primary stage once per test namespace."
  []
  (FxToolkit/registerPrimaryStage))

(defn cleanup-toolkit!
  "Close all TestFX-managed stages."
  []
  (FxToolkit/cleanupStages))

(defn robot
  "Create a fresh robot for an individual test."
  []
  (FxRobot.))

(defn sync-fx!
  "Flush pending JavaFX and cljfx render work."
  []
  (WaitForAsyncUtils/waitForFxEvents))

(defn- run-on-fx-thread!
  [f]
  (if (Platform/isFxApplicationThread)
    (f)
    (let [result (promise)]
      (Platform/runLater
       (fn []
         (try
           (deliver result {:value (f)})
           (catch Throwable error
             (deliver result {:error error})))))
      (let [{:keys [value error]} (deref result 10000
                                          {:error (ex-info "Timed out waiting for JavaFX" {})})]
        (if error
          (throw error)
          value)))))

(defn query
  "Find the first visible node matching a CSS selector across active windows."
  ^Node [^String selector]
  (sync-fx!)
  (run-on-fx-thread! #(some-> (some (fn [^Window window]
                                      (when (.isShowing window)
                                        (.getScene window)))
                                    (reverse (vec (Window/getWindows))))
                              .getRoot
                              (.lookup selector))))

(defn query-all
  "Find all nodes matching a CSS selector across active windows."
  [^String selector]
  (sync-fx!)
  (run-on-fx-thread!
   #(vec
     (mapcat (fn [^Window window]
               (if (.isShowing window)
                 (if-let [scene (.getScene window)]
                   (seq (.lookupAll (.getRoot scene) selector))
                   [])
                 []))
             (Window/getWindows)))))

(defn window-titles
  "Return titles for currently showing JavaFX windows."
  []
  (run-on-fx-thread!
   #(mapv (fn [^Window window]
            (when (.isShowing window)
              (when (instance? javafx.stage.Stage window)
                (.getTitle ^javafx.stage.Stage window))))
          (Window/getWindows))))

(defn visible?
  [selector]
  (boolean (some #(and (.isVisible ^Node %)
                       (.isManaged ^Node %))
                 (query-all selector))))

(defn enabled?
  [selector]
  (boolean (some #(and (instance? Control %)
                       (not (.isDisabled ^Control %)))
                 (query-all selector))))

(defn has-style-class?
  [selector style-class]
  (boolean (some #(contains? (set (.getStyleClass ^Node %)) style-class)
                 (query-all selector))))

(defn selected?
  [selector]
  (boolean
   (some (fn [node]
           (or (and (instance? ToggleButton node)
                    (.isSelected ^ToggleButton node))
               (and (instance? CheckBox node)
                    (.isSelected ^CheckBox node))
               (and (instance? ComboBox node)
                    (some? (.getValue ^ComboBox node)))))
         (query-all selector))))

(declare node-text)

(defn attribute
  "Return a common JavaFX node attribute."
  [selector key]
  (when-let [^Node node (query selector)]
    (case key
      :id (.getId node)
      :visible (.isVisible node)
      :managed (.isManaged node)
      :disabled (and (instance? Control node) (.isDisabled ^Control node))
      :text (node-text selector)
      :style-class (vec (.getStyleClass node))
      nil)))

(defn children
  "Return direct children of the first matching parent node."
  [selector]
  (when-let [^javafx.scene.Parent parent
             (some #(when (instance? javafx.scene.Parent %) %)
                   (query-all selector))]
    (vec (.getChildrenUnmodifiable parent))))

(defn- active-root
  []
  (run-on-fx-thread! #(some-> (some (fn [^Window window]
                                      (when (.isShowing window)
                                        (.getScene window)))
                                    (reverse (vec (Window/getWindows))))
                              .getRoot)))

(defn- focus-owner-desc
  "Short description of each showing window's focus owner, for diagnostics."
  []
  (try
    (run-on-fx-thread!
     #(vec (for [^Window window (Window/getWindows)
                 :when (.isShowing window)
                 :let [owner (some-> window .getScene .getFocusOwner)]]
             (when owner
               (str (.getSimpleName (class owner))
                    (when-let [id (.getId ^Node owner)] (str "#" id)))))))
    (catch Throwable _ nil)))

(defn wait-for!
  "Poll pred until it returns truthy, flushing JavaFX events between attempts."
  ([pred] (wait-for! default-timeout-ms pred))
  ([timeout-ms pred]
   (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
     (loop []
       (sync-fx!)
       (if (pred)
         true
         (if (< (System/nanoTime) deadline)
           (do (Thread/sleep 50) (recur))
           (throw (ex-info "Timed out waiting for UI condition"
                           {:timeout-ms timeout-ms
                            :windows (window-titles)
                            :focus-owner (focus-owner-desc)}))))))))

(defn scroll-into-view!
  "Scroll each ScrollPane around the node found by selector so that the
   node's top is in view, as a user scrolls to it: a robot can't click a
   node outside the scene's visible bounds."
  [^String selector]
  (wait-for! #(some? (query selector)))
  (run-on-fx-thread!
   (fn []
     (let [^Node node (query selector)]
       (loop [^Node parent (.getParent node)]
         (when parent
           (when (instance? ScrollPane parent)
             (let [^ScrollPane pane parent
                   ^Node content (.getContent pane)
                   content-bounds (.getLayoutBounds content)
                   node-bounds (.sceneToLocal content (.localToScene node (.getBoundsInLocal node)))
                   viewport (.getViewportBounds pane)
                   spare-h (- (.getHeight content-bounds) (.getHeight viewport))
                   spare-w (- (.getWidth content-bounds) (.getWidth viewport))
                   at (fn [offset spare lo hi]
                        (+ lo (* (- hi lo) (max 0.0 (min 1.0 (/ offset spare))))))]
               (when (pos? spare-h)
                 (.setVvalue pane (at (- (.getMinY node-bounds) (.getMinY content-bounds) 8.0) spare-h
                                      (.getVmin pane) (.getVmax pane))))
               (when (pos? spare-w)
                 (.setHvalue pane (at (- (.getMinX node-bounds) (.getMinX content-bounds) 8.0) spare-w
                                      (.getHmin pane) (.getHmax pane))))
               (.layout pane)))
           (recur (.getParent parent)))))))
  (sync-fx!))

(defn click!
  "Click a node found by CSS selector using TestFX's mouse robot.
   The node is resolved with query (topmost window first) and clicked by
   reference, so nodes in secondary stages are hit too."
  [^FxRobot robot-instance ^String selector]
  (wait-for! #(some? (query selector)))
  (.clickOn ^FxRobotInterface robot-instance (query selector)
            (into-array MouseButton [MouseButton/PRIMARY]))
  (sync-fx!)
  robot-instance)

(defn click-node!
  "Click a specific node returned by query-all."
  [^FxRobot robot-instance ^Node node]
  (.clickOn ^FxRobotInterface robot-instance node
            (into-array MouseButton [MouseButton/PRIMARY]))
  (sync-fx!)
  robot-instance)

(defn right-click-node!
  "Secondary-button click on a specific node returned by query-all."
  [^FxRobot robot-instance ^Node node]
  (.clickOn ^FxRobotInterface robot-instance node
            (into-array MouseButton [MouseButton/SECONDARY]))
  (sync-fx!)
  robot-instance)

(defn focus!
  "Bring the node's window to the front and give the node keyboard focus."
  [^FxRobot robot-instance ^String selector]
  (wait-for! #(some? (query selector)))
  (let [^Node node (query selector)]
    (.interact robot-instance
               (reify Runnable
                 (run [_]
                   (let [window (.getWindow (.getScene node))]
                     (when (instance? javafx.stage.Stage window)
                       (.toFront ^javafx.stage.Stage window))
                     (.requestFocus window)
                     (.requestFocus node))))))
  ;; Scene focus owner, not Node.isFocused: the latter also needs OS window
  ;; focus, which a terminal-launched JVM on macOS often doesn't get.
  (wait-for! #(run-on-fx-thread!
               (fn []
                 (let [^Node n (query selector)]
                   (identical? n (some-> n .getScene .getFocusOwner))))))
  robot-instance)

(defn headless?
  "True when running on JavaFX's built-in headless glass platform."
  []
  (= "headless" (System/getProperty "glass.platform")))

(defn- mac? []
  (.contains (.toLowerCase (System/getProperty "os.name" "")) "mac"))

(defn shortcut-key-code
  "The platform shortcut key: Cmd on macOS, Ctrl elsewhere."
  ^KeyCode []
  (if (mac?) KeyCode/COMMAND KeyCode/CONTROL))

(defn- resolve-modifier ^KeyCode [^KeyCode modifier]
  (if (= modifier KeyCode/SHORTCUT) (shortcut-key-code) modifier))

(defn- fire-modified-click!
  "Fire a synthetic primary click on node with modifier flags set.
   JavaFX's HeadlessRobot drops held keys from mouse events, so headless
   modifier clicks are delivered this way instead."
  [^Node node ^KeyCode modifier]
  (let [shift? (= modifier KeyCode/SHIFT)
        ctrl? (= modifier KeyCode/CONTROL)
        alt? (= modifier KeyCode/ALT)
        meta? (contains? #{KeyCode/META KeyCode/COMMAND} modifier)]
    (run-on-fx-thread!
     (fn []
       (let [bounds (.localToScreen node (.getBoundsInLocal node))
             local-x (/ (.getWidth (.getBoundsInLocal node)) 2)
             local-y (/ (.getHeight (.getBoundsInLocal node)) 2)
             screen-x (+ (.getMinX bounds) local-x)
             screen-y (+ (.getMinY bounds) local-y)]
         (doseq [[event-type click-count] [[MouseEvent/MOUSE_PRESSED 1]
                                           [MouseEvent/MOUSE_RELEASED 1]
                                           [MouseEvent/MOUSE_CLICKED 1]]]
           (Event/fireEvent
            node
            (MouseEvent. event-type local-x local-y screen-x screen-y
                         MouseButton/PRIMARY click-count
                         shift? ctrl? alt? meta?
                         (= event-type MouseEvent/MOUSE_PRESSED) false false
                         false false true nil))))))))

(defn modifier-click-node!
  "Click a specific node while holding a keyboard modifier. Pass
   KeyCode/SHORTCUT for the platform shortcut key (Cmd on macOS, Ctrl
   elsewhere)."
  [^FxRobot robot-instance ^Node node ^KeyCode modifier]
  (let [modifier (resolve-modifier modifier)]
    (if (headless?)
      (fire-modified-click! node modifier)
      (do
        (.press ^FxRobotInterface robot-instance
                (into-array KeyCode [modifier]))
        (.clickOn ^FxRobotInterface robot-instance node
                  (into-array MouseButton [MouseButton/PRIMARY]))
        (.release ^FxRobotInterface robot-instance
                  (into-array KeyCode [modifier])))))
  (sync-fx!)
  robot-instance)

(defn modifier-click!
  "Click a selector while holding a keyboard modifier."
  [^FxRobot robot-instance ^String selector ^KeyCode modifier]
  (wait-for! #(some? (query selector)))
  (modifier-click-node! robot-instance (query selector) modifier))

(defn- fire-mouse!
  "Fire a primary-button mouse event of event-type at the centre of node, as
   a real mouse sends it: not synthesized, not a popup trigger."
  [^Node node event-type]
  (run-on-fx-thread!
   (fn []
     (let [bounds (.localToScreen node (.getBoundsInLocal node))
           local-x (/ (.getWidth (.getBoundsInLocal node)) 2)
           local-y (/ (.getHeight (.getBoundsInLocal node)) 2)]
       (Event/fireEvent
        node
        (MouseEvent. event-type local-x local-y (+ (.getMinX bounds) local-x) (+ (.getMinY bounds) local-y)
                     MouseButton/PRIMARY 1
                     false false false false
                     (= event-type MouseEvent/MOUSE_PRESSED) false false
                     false false true nil))))))

(defn press-node!
  "Press the primary mouse button on the node found by selector and keep it
   down (release-node! lets go). Headless, the press is fired at the node as a
   real mouse's: the headless robot marks every mouse event synthesized, as
   JavaFX marks the ones it makes from a touch, and a popup trigger, so a
   control that takes touches as touch events (the Perform tab's pads) leaves
   the robot's out."
  [^FxRobot robot-instance ^String selector]
  (wait-for! #(some? (query selector)))
  (let [node (query selector)]
    (if (headless?)
      (fire-mouse! node MouseEvent/MOUSE_PRESSED)
      (do (.moveTo ^FxRobotInterface robot-instance node)
          (.press robot-instance (into-array MouseButton [MouseButton/PRIMARY])))))
  (sync-fx!)
  robot-instance)

(defn release-node!
  "Release the primary mouse button pressed on the node found by selector
   (press-node!)."
  [^FxRobot robot-instance ^String selector]
  (if (headless?)
    (let [node (query selector)]
      (fire-mouse! node MouseEvent/MOUSE_RELEASED)
      (fire-mouse! node MouseEvent/MOUSE_CLICKED))
    (.release robot-instance (into-array MouseButton [MouseButton/PRIMARY])))
  (sync-fx!)
  robot-instance)

(defn mouse-click!
  "A primary-button click on the node found by selector, through press-node!
   and release-node!."
  [robot-instance selector]
  (press-node! robot-instance selector)
  (release-node! robot-instance selector))

(defn drag-node!
  "Drag one queried node onto another node using TestFX mouse movement."
  [^FxRobot robot-instance ^Node source ^Node target]
  (.moveTo ^FxRobotInterface robot-instance source)
  (.drag ^FxRobotInterface robot-instance
         (into-array MouseButton [MouseButton/PRIMARY]))
  (.dropTo ^FxRobotInterface robot-instance target)
  (sync-fx!)
  robot-instance)

(defn drag-node-to!
  "Real drag-and-drop with the mouse robot: press on the centre of source,
   move to node-local (x, y) of target (from its top-left) and release.
   Doesn't work headless: the headless glass platform can't run a native
   drag-and-drop (see fire-drop!)."
  [^FxRobot robot-instance ^Node source ^Node target x y]
  (.moveTo robot-instance source)
  (.press robot-instance (into-array MouseButton [MouseButton/PRIMARY]))
  (.moveBy robot-instance 12.0 0.0)
  (.moveTo robot-instance target Pos/TOP_LEFT (Point2D. (double x) (double y)) Motion/DIRECT)
  (sync-fx!)
  (.release robot-instance (into-array MouseButton [MouseButton/PRIMARY]))
  (sync-fx!)
  robot-instance)

(defn test-dragboard
  "A Dragboard holding `content` (a ClipboardContent) that offers the
   transfer `modes`, for synthetic drag events. An entry with a nil value
   announces its format and hides its content, as a platform may during a
   drag. With :put? false its setContent fails (returns false), as a
   platform's that can't take the content. FX thread only."
  [content modes & {:keys [put?] :or {put? true}}]
  (let [store (atom (into {} content))]
    (DragboardHelper/createDragboard
     (reify TKClipboard
       (getContentTypes [_] (set (keys @store)))
       (putContent [_ pairs]
         (when put?
           (doseq [^Pair p pairs] (swap! store assoc (.getKey p) (.getValue p))))
         (boolean put?))
       (getContent [_ fmt] (get @store fmt))
       (hasContent [_ fmt] (contains? @store fmt))
       (getTransferModes [_] (set modes))
       (setDragView [_ _])
       (setDragViewOffsetX [_ _])
       (setDragViewOffsetY [_ _])
       (getDragView [_] nil)
       (getDragViewOffsetX [_] 0.0)
       (getDragViewOffsetY [_] 0.0)))))

(defn fire-drop!
  "Drag `content` (a ClipboardContent) over node-local (x, y) of `node` and,
   when the node accepts and :drop? isn't false, drop it there. Fires
   synthetic DragEvents (DRAG_ENTERED, DRAG_OVER, DRAG_DROPPED, and
   DRAG_EXITED unless :exit? is false), because the headless glass platform
   can't run a real drag-and-drop: its DnD clipboard throws
   UnsupportedOperationException. The drag source isn't involved: the
   events' gesture source is :gesture-source (default nil, a drag from
   outside the app), and nothing ends its drag (fire-drag-done!). Each
   call drags a board of its own. :mode is the one transfer mode the drag
   offers (default COPY, as bank drags do). Returns {:accepted? :completed?}."
  [^Node node content x y & {:keys [drop? exit? mode gesture-source]
                             :or {drop? true exit? true mode TransferMode/COPY}}]
  (let [result (run-on-fx-thread!
                (fn []
                  (let [db (test-dragboard content [mode])
                        scene-p (.localToScene node (double x) (double y))
                        screen-p (.localToScreen node (double x) (double y))
                        event (fn [type mode]
                                (DragEvent. type db (.getX scene-p) (.getY scene-p)
                                            (.getX screen-p) (.getY screen-p)
                                            mode gesture-source node nil))
                        _ (Event/fireEvent node (event DragEvent/DRAG_ENTERED mode))
                        over (event DragEvent/DRAG_OVER mode)
                        _ (Event/fireEvent node over)
                        accepted? (.isAccepted over)
                        dropped (when (and accepted? drop?)
                                  (let [d (event DragEvent/DRAG_DROPPED (.getAcceptedTransferMode over))]
                                    (Event/fireEvent node d)
                                    d))]
                    (when exit?
                      (Event/fireEvent node (event DragEvent/DRAG_EXITED mode)))
                    {:accepted? accepted?
                     :completed? (boolean (some-> ^DragEvent dropped .isDropCompleted))})))]
    (sync-fx!)
    result))

(defn fire-drag-detected!
  "A drag gesture detected at the centre of node, as the mouse starts one:
   the node's drag-detected handlers run (a drag source starts its drag)."
  [^Node node]
  (run-on-fx-thread!
   #(let [b (.getBoundsInLocal node)
          x (/ (+ (.getMinX b) (.getMaxX b)) 2)
          y (/ (+ (.getMinY b) (.getMaxY b)) 2)
          scene (.localToScene node (double x) (double y))
          screen (.localToScreen node (double x) (double y))]
      (Event/fireEvent node (MouseEvent. MouseEvent/DRAG_DETECTED (.getX scene) (.getY scene)
                                         (.getX screen) (.getY screen) MouseButton/PRIMARY 1
                                         false false false false true false false false false false nil))))
  (sync-fx!))

(defn fire-drag-done!
  "The end of the drag node started, as the platform reports it to the
   source: DRAG_DONE with transfer `mode`, what the target accepted, or nil
   for a drag cancelled (Escape) or released where nothing took it."
  [^Node node mode]
  (run-on-fx-thread!
   #(Event/fireEvent node (DragEvent. DragEvent/DRAG_DONE (test-dragboard {} (if mode [mode] []))
                                      0.0 0.0 0.0 0.0 mode node nil nil)))
  (sync-fx!))

(defn drag-by!
  "Press the primary button at (x, y) inside the node found by selector
   (node-local coordinates from its top-left), move by dx/dy in steps, and
   release. With :hold? true, returns before releasing (call release!)."
  ([robot-instance selector x y dx dy] (drag-by! robot-instance selector x y dx dy nil))
  ([^FxRobot robot-instance ^String selector x y dx dy {:keys [hold? steps] :or {steps 4}}]
   (wait-for! #(some? (query selector)))
   (let [^Node node (query selector)]
     (.moveTo robot-instance node Pos/TOP_LEFT (Point2D. (double x) (double y)) Motion/DIRECT)
     (.press robot-instance (into-array MouseButton [MouseButton/PRIMARY]))
     (sync-fx!)
     (dotimes [_ steps]
       (.moveBy robot-instance (/ (double dx) steps) (/ (double dy) steps))
       (sync-fx!))
     (when-not hold?
       (.release robot-instance (into-array MouseButton [MouseButton/PRIMARY]))
       (sync-fx!)))
   robot-instance))

(defn fire-drag!
  "Drag node by dx, dy from its centre with mouse events fired at it, the
   primary button down: pressed, dragged in steps and released; with :hold?
   true it returns before the release, as a fn to call. Headless, a robot
   drag (drag-by!) leaves the node it pressed once the mouse does: the glass
   robot's drag events don't carry the button, so the events after that,
   the release too, go to the nodes under the mouse. A small handle, such as
   a split pane's divider, needs this instead."
  [^Node node dx dy & {:keys [hold? steps] :or {steps 4}}]
  (let [[x0 y0] (run-on-fx-thread! #(let [b (.localToScene node (.getBoundsInLocal node))]
                                      [(/ (+ (.getMinX b) (.getMaxX b)) 2)
                                       (/ (+ (.getMinY b) (.getMaxY b)) 2)]))
        fire! (fn [event-type x y]
                (run-on-fx-thread!
                 #(let [screen (.localToScreen node (.sceneToLocal node (double x) (double y)))]
                    (Event/fireEvent
                     node
                     (MouseEvent. event-type (double x) (double y) (.getX screen) (.getY screen)
                                  MouseButton/PRIMARY 1
                                  false false false false
                                  (not= event-type MouseEvent/MOUSE_RELEASED) false false
                                  false false false nil))))
                (sync-fx!))
        release! #(fire! MouseEvent/MOUSE_RELEASED (+ x0 dx) (+ y0 dy))]
    (fire! MouseEvent/MOUSE_PRESSED x0 y0)
    (doseq [i (range 1 (inc steps))]
      (fire! MouseEvent/MOUSE_DRAGGED (+ x0 (* dx (/ i steps))) (+ y0 (* dy (/ i steps)))))
    (if hold? release! (release!))))

(defn release!
  "Release the primary mouse button (ends a drag-by! with :hold? true)."
  [^FxRobot robot-instance]
  (.release robot-instance (into-array MouseButton [MouseButton/PRIMARY]))
  (sync-fx!)
  robot-instance)

(defn fill!
  "Replace the text of a TextInputControl found by CSS selector. Sets the
   text on the FX thread, so it doesn't depend on OS window focus. Use type!
   when the test needs real per-character key events."
  [^FxRobot robot-instance ^String selector ^String text]
  (wait-for! #(some? (query selector)))
  (let [^TextInputControl node (query selector)]
    (.interact robot-instance
               (reify Runnable
                 (run [_]
                   (.requestFocus node)
                   (.setText node text)
                   (.positionCaret node (count text)))))
    (sync-fx!)
    robot-instance))

(defn type!
  "Clear a TextInputControl, then type text with real key events. Needs a
   focused window (always true headless; not always on macOS visible runs)."
  [^FxRobot robot-instance ^String selector ^String text]
  (wait-for! #(some? (query selector)))
  (let [^TextInputControl node (query selector)]
    (.interact robot-instance
               (reify Runnable
                 (run [_]
                   (.requestFocus node)
                   (.clear node))))
    (.clickOn ^FxRobotInterface robot-instance node
              (into-array MouseButton [MouseButton/PRIMARY]))
    (.write robot-instance text)
    (sync-fx!)
    robot-instance))

(defn select!
  "Select a ComboBox item by its displayed value."
  [^FxRobot robot-instance selector item]
  (wait-for! #(some? (query selector)))
  (.interact robot-instance
             (reify Runnable
               (run [_]
                 (let [^ComboBox combo (query selector)
                       value (some #(when (or (= item (str %))
                                              (= item (:name %)))
                                      %)
                                   (.getItems combo))]
                   (when-not value
                     (throw (ex-info "ComboBox item not found"
                                     {:selector selector :item item})))
                   (.select (.getSelectionModel combo) value)))))
  (sync-fx!)
  robot-instance)

(defn check!
  [^FxRobot robot-instance selector]
  (.interact robot-instance
             (reify Runnable
               (run [_]
                 (.setSelected ^CheckBox (query selector) true))))
  (sync-fx!)
  robot-instance)

(defn uncheck!
  [^FxRobot robot-instance selector]
  (.interact robot-instance
             (reify Runnable
               (run [_]
                 (.setSelected ^CheckBox (query selector) false))))
  (sync-fx!)
  robot-instance)

(defn slide!
  [^FxRobot robot-instance selector value]
  (.interact robot-instance
             (reify Runnable
               (run [_]
                 (.setValue ^javafx.scene.control.Slider (query selector)
                            (double value)))))
  (sync-fx!)
  robot-instance)

(defn hover!
  [^FxRobot robot-instance selector]
  (wait-for! #(some? (query selector)))
  (.moveTo ^FxRobotInterface robot-instance (query selector))
  (sync-fx!)
  robot-instance)

(defn press!
  "Press a JavaFX key through TestFX."
  [^FxRobot robot-instance ^KeyCode key]
  (.push robot-instance (into-array KeyCode [key]))
  (sync-fx!)
  robot-instance)

(defn submit!
  "Fire the action event for a text input, equivalent to pressing Enter."
  [^FxRobot robot-instance selector]
  (.interact robot-instance
             (reify Runnable
               (run [_]
                 (Event/fireEvent (query selector) (ActionEvent.)))))
  (sync-fx!)
  robot-instance)

(defn node-text
  "Return text for standard JavaFX labels and text inputs."
  [^String selector]
  (let [^Node node (query selector)]
    (cond
      (instance? Labeled node) (.getText ^Labeled node)
      (instance? TextInputControl node) (.getText ^TextInputControl node)
      :else nil)))

(defn assert-text!
  "Auto-wait for a node's text and assert the final value."
  ([selector expected]
   (assert-text! default-timeout-ms selector expected))
  ([timeout-ms selector expected]
   (wait-for! timeout-ms #(= expected (node-text selector)))
   (is (= expected (node-text selector)))))

(defn assert-visible! [selector]
  (wait-for! #(visible? selector))
  (is (visible? selector)))

(defn assert-enabled! [selector]
  (wait-for! #(enabled? selector))
  (is (enabled? selector)))

(defn assert-disabled! [selector]
  (wait-for! #(not (enabled? selector)))
  (is (not (enabled? selector))))

(defn assert-selected! [selector]
  (wait-for! #(selected? selector))
  (is (selected? selector)))

(defn assert-count! [selector expected]
  (wait-for! #(= expected (count (query-all selector))))
  (is (= expected (count (query-all selector)))))

(defn assert-no-node! [selector]
  (is (empty? (query-all selector))))

(defn screenshot!
  "Capture a node matching selector, or the first active scene root, to PNG."
  [selector file-path]
  (let [^Node node (or (and selector (query selector))
                       (active-root))
        file (File. file-path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (run-on-fx-thread!
     (fn []
       (let [image (.snapshot node (SnapshotParameters.) nil)]
         (ImageIO/write (SwingFXUtils/fromFXImage image nil) "png" file))))
    file-path))

(defn node-pixels
  "The pixels of the first node matching selector, from a snapshot on the FX
   thread: a vector of ARGB ints, row by row."
  [selector]
  (let [^Node node (query selector)]
    (run-on-fx-thread!
     (fn []
       (let [image (.snapshot node (SnapshotParameters.) nil)
             w (int (.getWidth image))
             h (int (.getHeight image))
             argb (int-array (* w h))]
         (.getPixels (.getPixelReader image) 0 0 w h (PixelFormat/getIntArgbInstance) argb 0 w)
         (vec argb))))))

(defn on-fx-thread
  "(f) on the FX thread, waiting for its value."
  [f]
  (run-on-fx-thread! f))

(defn main-window
  "The main window: the one that shows #main-layout."
  ^Stage []
  (some-> ^Node (first (query-all "#main-layout")) .getScene .getWindow))

(defn set-size!
  "Give a window this size on the FX thread, as the OS does when the user
   resizes it, at the screen's top-left corner. The layout follows later:
   wait for it with wait-for!. Headless, the screen is 1000×1000 and a
   window that reaches past its last row can't be drawn (the headless
   window's blit throws), so keep (+ y height) under 1000 there."
  [^Window window width height]
  (run-on-fx-thread! #(doto window
                        (.setX 0.0)
                        (.setY 0.0)
                        (.setWidth (double width))
                        (.setHeight (double height))))
  (sync-fx!))

(defn scene-bounds
  "A node's bounds in its scene: [min-x min-y max-x max-y]."
  [^Node node]
  (run-on-fx-thread! #(let [b (.localToScene node (.getBoundsInLocal node))]
                        [(.getMinX b) (.getMinY b) (.getMaxX b) (.getMaxY b)])))

(defn shown-text
  "The text a Labeled shows: all of its text, or the part that fits and an
   ellipsis when it is too narrow (its skin's text node)."
  [^Labeled node]
  (run-on-fx-thread! #(some-> ^Text (.lookup node ".text") .getText)))
