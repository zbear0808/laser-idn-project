(ns laser-show.ui-test.driver
  "UI test driver providing JavaFX interop wrappers for finding nodes,
   triggering native events, and capturing screenshots.
   Handles multi-window traversal (modals, dialogs) via Window/getWindows."
  (:import [javafx.application Platform]
           [javafx.embed.swing SwingFXUtils]
           [javafx.event Event]
           [javafx.scene Node SnapshotParameters]
           [javafx.scene.input MouseButton MouseEvent]
           [javafx.stage Window]
           [javax.imageio ImageIO]
           [java.io File]))

(def ^:private default-wait-ms 1000)

;; ---------------------------------------------------------------------------
;; JavaFX Thread Utilities
;; ---------------------------------------------------------------------------

(defn- run-on-fx-thread!
  "Runs f on the JavaFX Application Thread and blocks until complete.
   Returns the result of f, or re-throws any exception from f."
  [f]
  (if (Platform/isFxApplicationThread)
    (f)
    (let [result (promise)]
      (Platform/runLater
        (fn []
          (try
            (deliver result {:value (f)})
            (catch Throwable t
              (deliver result {:error t})))))
      (let [{:keys [value error]} (deref result 10000 {:error (ex-info "FX thread timeout" {})})]
        (if error
          (throw error)
          value)))))

;; ---------------------------------------------------------------------------
;; Window & Node Lookup (supports modals)
;; ---------------------------------------------------------------------------

(defn- get-active-windows
  "Returns all currently showing JavaFX windows."
  []
  (filterv #(.isShowing ^Window %) (Window/getWindows)))

(defn- get-focused-window
  "Returns the currently focused window, or the last showing window as fallback."
  []
  (let [windows (get-active-windows)]
    (or (some #(when (.isFocused ^Window %) %) windows)
        (last windows))))

(defn- find-node*
  "Internal: finds a JavaFX Node by CSS selector across all active windows.
   Searches the focused/topmost window first to correctly find modal content.
   Must be called on the FX Application Thread."
  [selector]
  (let [focused (get-focused-window)
        windows (get-active-windows)
        ;; Search focused window first, then remaining windows
        search-order (into [focused] (filterv #(not= % focused) windows))]
    (some (fn [^Window w]
            (when-let [scene (.getScene w)]
              (when-let [root (.getRoot scene)]
                (.lookup root selector))))
          search-order)))

(defn find-node
  "Finds a JavaFX Node by CSS selector string across all active windows.
   Searches the focused/topmost window first (to find modal content),
   then falls back through other windows."
  [selector]
  (run-on-fx-thread! #(find-node* selector)))

;; ---------------------------------------------------------------------------
;; Event Simulation
;; ---------------------------------------------------------------------------

(defn- make-mouse-event
  "Creates a synthetic MouseEvent at the given local coordinates."
  [event-type x y]
  (MouseEvent. event-type
               x y x y
               MouseButton/PRIMARY 1
               false false false false
               true false false false
               false true nil))

(defn click!
  "Simulates a full mouse click (press + release + click) on the node
   matching the given CSS selector."
  [selector]
  (run-on-fx-thread!
    (fn []
      (if-let [^Node node (find-node* selector)]
        (let [bounds (.getBoundsInLocal node)
              x      (/ (.getWidth bounds) 2.0)
              y      (/ (.getHeight bounds) 2.0)]
          (Event/fireEvent node (make-mouse-event MouseEvent/MOUSE_PRESSED x y))
          (Event/fireEvent node (make-mouse-event MouseEvent/MOUSE_RELEASED x y))
          (Event/fireEvent node (make-mouse-event MouseEvent/MOUSE_CLICKED x y))
          true)
        (throw (ex-info (str "Node not found: " selector) {:selector selector}))))))

(defn slide!
  "Sets a slider's value for the node matching the given CSS selector."
  [selector value]
  (run-on-fx-thread!
    (fn []
      (if-let [^javafx.scene.control.Slider node (find-node* selector)]
        (do (.setValue node (double value)) true)
        (throw (ex-info (str "Slider not found: " selector) {:selector selector}))))))

(defn type!
  "Sets the text content of the text input matching the given CSS selector."
  [selector text]
  (run-on-fx-thread!
    (fn []
      (if-let [^javafx.scene.control.TextInputControl node (find-node* selector)]
        (do (.setText node text) true)
        (throw (ex-info (str "Text input not found: " selector) {:selector selector}))))))

;; ---------------------------------------------------------------------------
;; Wait
;; ---------------------------------------------------------------------------

(defn wait!
  "Pauses execution for the default wait period (1 second) to allow
   UI rendering and OS-level window management to complete."
  ([] (wait! default-wait-ms))
  ([ms] (Thread/sleep ms)))

;; ---------------------------------------------------------------------------
;; Screen Capture
;; ---------------------------------------------------------------------------

(defn capture-node!
  "Captures a screenshot of the node matching the given CSS selector
   and writes it to file-path. Supports .png and .jpg formats.
   If selector is nil, captures the root of the currently focused window.
   Returns the file-path on success."
  [selector file-path]
  (run-on-fx-thread!
    (fn []
      (let [^Node node (if selector
                         (or (find-node* selector)
                             (throw (ex-info (str "Node not found: " selector)
                                            {:selector selector})))
                         (some-> (get-focused-window) .getScene .getRoot))
            _          (when-not node
                         (throw (ex-info "No active window found for capture" {})))
            snapshot    (.snapshot node (SnapshotParameters.) nil)
            buffered    (SwingFXUtils/fromFXImage snapshot nil)
            file        (File. ^String file-path)
            format      (if (.endsWith file-path ".jpg") "jpg" "png")]
        (.mkdirs (.getParentFile file))
        (ImageIO/write buffered format file)
        file-path))))
