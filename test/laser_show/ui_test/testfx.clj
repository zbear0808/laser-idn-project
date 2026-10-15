(ns laser-show.ui-test.testfx
  "Small TestFX DSL for cljfx UI tests.

   The helpers deliberately query the JavaFX scene graph instead of caching
   nodes, so every assertion observes the current cljfx render."
  (:require [clojure.test :refer [is]])
  (:import [javafx.application Platform]
           [javafx.scene Node]
           [javafx.scene.control Labeled TextInputControl]
           [javafx.scene SnapshotParameters]
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

(defn press!
  "Press a JavaFX key through TestFX."
  [^FxRobot robot-instance ^KeyCode key]
  (.push robot-instance (into-array KeyCode [key]))
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
