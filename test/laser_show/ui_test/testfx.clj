(ns laser-show.ui-test.testfx
  "Small TestFX DSL for cljfx UI tests.

   The helpers deliberately query the JavaFX scene graph instead of caching
   nodes, so every assertion observes the current cljfx render."
  (:require [clojure.test :refer [is]])
  (:import [javafx.application Platform]
           [javafx.scene Node]
           [javafx.scene.control CheckBox ComboBox Control Labeled TextInputControl ToggleButton]
           [javafx.scene SnapshotParameters]
           [javafx.event ActionEvent Event]
           [javafx.stage Window]
           [javafx.embed.swing SwingFXUtils]
           [javafx.scene.input KeyCode MouseButton]
           [javax.imageio ImageIO]
           [java.io File]
           [org.testfx.api FxRobot FxRobotInterface FxToolkit]
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
                           {:timeout-ms timeout-ms}))))))))

(defn click!
  "Click a node found by CSS selector using TestFX's mouse robot."
  [^FxRobot robot-instance ^String selector]
  (wait-for! #(some? (query selector)))
  (.clickOn ^FxRobotInterface robot-instance selector
            (into-array MouseButton [MouseButton/PRIMARY]))
  (sync-fx!)
  robot-instance)

(defn fill!
  "Clear and type text into a TextInputControl found by CSS selector."
  [^FxRobot robot-instance ^String selector ^String text]
  (wait-for! #(some? (query selector)))
  (let [^TextInputControl node (query selector)]
    (.interact robot-instance
               (reify Runnable
                 (run [_]
                   (.requestFocus node)
                   (.clear node))))
    (.clickOn ^FxRobotInterface robot-instance selector
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
  (.moveTo ^FxRobotInterface robot-instance selector)
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
