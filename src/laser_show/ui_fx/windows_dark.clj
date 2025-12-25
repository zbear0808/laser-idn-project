(ns laser-show.ui-fx.windows-dark
  "Windows-specific dark mode support for window title bars.
   Uses JNA to call Windows DWM API for dark title bar support."
  (:import [com.sun.jna Native Pointer Memory]
           [com.sun.jna.platform.win32 WinDef$HWND User32]
           [com.sun.jna.ptr IntByReference]
           [javafx.stage Stage Window]
           [com.sun.jna.win32 StdCallLibrary]))

;; ============================================================================
;; Windows DWM Constants
;; ============================================================================

;; DWMWA_USE_IMMERSIVE_DARK_MODE = 20 for Windows 10 build 18985+ and Windows 11
;; Attribute 19 was used in earlier Windows 10 builds
(def ^:private DWMWA_USE_IMMERSIVE_DARK_MODE 20)
(def ^:private DWMWA_USE_IMMERSIVE_DARK_MODE_OLD 19)

;; ============================================================================
;; JNA Dwmapi Interface
;; ============================================================================

(definterface Dwmapi
  (^int DwmSetWindowAttribute [^com.sun.jna.platform.win32.WinDef$HWND hwnd
                               ^int dwAttribute
                               ^com.sun.jna.Pointer pvAttribute
                               ^int cbAttribute]))

;; ============================================================================
;; Load Dwmapi Library
;; ============================================================================

(defonce ^:private dwmapi
  (try
    (Native/load "dwmapi" Dwmapi)
    (catch Exception e
      (println "Could not load dwmapi.dll:" (.getMessage e))
      nil)))

;; ============================================================================
;; Check if Windows
;; ============================================================================

(defn windows?
  "Check if running on Windows."
  []
  (when-let [os-name (System/getProperty "os.name")]
    (.contains (.toLowerCase os-name) "windows")))

;; ============================================================================
;; Get Window Handle from JavaFX Stage
;; ============================================================================

(defn- get-native-window-handle
  "Get the native Windows HWND from a JavaFX Stage.
   Uses reflection to access Glass window internals."
  [^Stage stage]
  (try
    ;; Method 1: Try to get via com.sun.glass.ui.Window
    (let [glass-window-class (Class/forName "com.sun.glass.ui.Window")
          get-windows-method (.getMethod glass-window-class "getWindows" (into-array Class []))
          windows (.invoke get-windows-method nil (object-array []))]
      (when (and windows (seq windows))
        ;; Get the first window's native handle
        (let [window (first windows)
              get-native-handle (.getMethod (class window) "getNativeHandle" (into-array Class []))
              handle (.invoke get-native-handle window (object-array []))]
          (when handle
            (WinDef$HWND. (Pointer. handle))))))
    (catch Exception e
      ;; Method 2: Try alternative approach via Window peer
      (try
        (let [scene (.getScene stage)]
          (when scene
            (let [window (.getWindow scene)]
              (when window
                ;; Access internal peer
                (let [peer-field (try
                                   (.getDeclaredField Window "peer")
                                   (catch NoSuchFieldException _
                                     (.getDeclaredField Window "impl_peer")))]
                  (.setAccessible peer-field true)
                  (let [peer (.get peer-field window)]
                    (when peer
                      ;; Get platform window from peer
                      (let [platform-window-field (.getDeclaredField (class peer) "platformWindow")]
                        (.setAccessible platform-window-field true)
                        (let [platform-window (.get platform-window-field peer)
                              get-native (.getMethod (class platform-window) "getNativeHandle" (into-array Class []))
                              handle (.invoke get-native platform-window (object-array []))]
                          (when handle
                            (WinDef$HWND. (Pointer. handle))))))))))))
        (catch Exception e2
          (println "Could not get window handle (fallback):" (.getMessage e2))
          nil)))))

;; ============================================================================
;; Apply Dark Title Bar
;; ============================================================================

(defn apply-dark-title-bar!
  "Apply dark mode to a JavaFX Stage's title bar on Windows.
   Works on Windows 10 (build 18985+) and Windows 11.
   Returns true if successful, false otherwise."
  [^Stage stage]
  (when (and dwmapi (windows?))
    (try
      ;; Wait for window to be fully realized
      (Thread/sleep 100)
      
      (let [hwnd (get-native-window-handle stage)]
        (if hwnd
          (let [;; Create pointer to BOOL TRUE value (4 bytes, value 1)
                value-ptr (doto (Memory. 4) (.setInt 0 1))
                ;; Try the newer attribute first (Windows 10 18985+ / Windows 11)
                result (.DwmSetWindowAttribute dwmapi hwnd
                                               DWMWA_USE_IMMERSIVE_DARK_MODE
                                               value-ptr 4)]
            (if (zero? result)
              (do
                (println "Applied dark title bar successfully (attribute 20)")
                true)
              ;; Try the older attribute (early Windows 10 builds)
              (let [result2 (.DwmSetWindowAttribute dwmapi hwnd
                                                    DWMWA_USE_IMMERSIVE_DARK_MODE_OLD
                                                    value-ptr 4)]
                (if (zero? result2)
                  (do
                    (println "Applied dark title bar (attribute 19 - legacy)")
                    true)
                  (do
                    (println "Failed to apply dark title bar. Results:" result result2)
                    false)))))
          (do
            (println "Could not obtain native window handle")
            false)))
      (catch Exception e
        (println "Exception applying dark title bar:" (.getMessage e))
        (.printStackTrace e)
        false))))

;; ============================================================================
;; Async Apply (for use in lifecycle hooks)
;; ============================================================================

(defn apply-dark-title-bar-async!
  "Apply dark title bar asynchronously after a delay.
   Use this in cljfx lifecycle hooks to ensure window is ready."
  [^Stage stage delay-ms]
  (future
    (Thread/sleep delay-ms)
    (javafx.application.Platform/runLater
      #(apply-dark-title-bar! stage))))

;; ============================================================================
;; Debug / REPL
;; ============================================================================

(comment
  ;; Check if on Windows
  (windows?)
  
  ;; Check if dwmapi loaded
  dwmapi
  
  ;; Apply to a stage (from REPL, after app is running)
  ;; (apply-dark-title-bar! @some-stage-atom)
  )
