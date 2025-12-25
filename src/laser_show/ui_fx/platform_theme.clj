(ns laser-show.ui-fx.platform-theme
  "Cross-platform theme support using JavaFX 22+ Platform.Preferences.
   
   This module provides a clean, cross-platform way to:
   - Detect system color scheme (light/dark)
   - Request dark window decorations
   - React to system theme changes"
  (:import [javafx.application Platform]
           [javafx.stage StageStyle]))

;; ============================================================================
;; JavaFX 22+ Platform.Preferences
;; ============================================================================

(defn get-preferences
  "Get the Platform.Preferences instance.
   Available in JavaFX 22+."
  []
  (try
    (Platform/getPreferences)
    (catch Exception _e
      (println "Platform.Preferences not available (requires JavaFX 22+)")
      nil)))

(defn get-color-scheme
  "Get the system color scheme preference.
   Returns :dark, :light, or :unknown.
   
   This uses JavaFX 22+ Platform.Preferences.colorScheme."
  []
  (if-let [prefs (get-preferences)]
    (try
      (let [scheme (.getColorScheme prefs)]
        (cond
          (= scheme javafx.application.ColorScheme/DARK) :dark
          (= scheme javafx.application.ColorScheme/LIGHT) :light
          :else :unknown))
      (catch Exception _e
        :unknown))
    :unknown))

(defn system-dark-mode?
  "Check if the system is in dark mode.
   Uses JavaFX 22+ Platform.Preferences."
  []
  (= (get-color-scheme) :dark))

(defn add-color-scheme-listener!
  "Add a listener for color scheme changes.
   The callback receives :dark or :light when the system theme changes.
   
   Returns a function to remove the listener."
  [callback]
  (if-let [prefs (get-preferences)]
    (try
      (let [listener (reify javafx.beans.value.ChangeListener
                       (changed [_ _ _ new-scheme]
                         (callback (cond
                                     (= new-scheme javafx.application.ColorScheme/DARK) :dark
                                     (= new-scheme javafx.application.ColorScheme/LIGHT) :light
                                     :else :unknown))))]
        (-> prefs
            (.colorSchemeProperty)
            (.addListener listener))
        ;; Return a function to remove the listener
        (fn []
          (-> prefs
              (.colorSchemeProperty)
              (.removeListener listener))))
      (catch Exception _e
        (fn []))) ; Return no-op remover
    (fn []))) ; Return no-op remover

;; ============================================================================
;; Stage Style Recommendations
;; ============================================================================

(defn recommended-stage-style
  "Get the recommended StageStyle for current platform.
   
   On macOS: UNIFIED gives a native look with merged title bar
   On Windows/Linux: DECORATED is standard
   
   Note: UNDECORATED removes the title bar entirely (for custom title bars)"
  []
  (let [os-name (System/getProperty "os.name" "")]
    (if (.contains (.toLowerCase os-name) "mac")
      StageStyle/UNIFIED
      StageStyle/DECORATED)))

;; ============================================================================
;; System Properties for Dark Mode (Pre-init)
;; ============================================================================

(defn set-dark-mode-hint!
  "Set system property to hint dark mode preference to JavaFX.
   MUST be called before JavaFX toolkit initialization.
   
   On Windows 10/11, this tells JavaFX to use dark window decorations
   if available. Note: The effectiveness depends on JavaFX version
   and Windows build."
  [dark?]
  ;; Various properties that different JavaFX versions/platforms may respect
  (System/setProperty "javafx.scene.paint.Color.DARK_MODE" (str dark?))
  (System/setProperty "sun.java2d.uiScale.enabled" "true")
  
  ;; For Windows 10/11 dark title bar
  ;; The specific property name may vary by JavaFX version
  (when dark?
    (System/setProperty "prism.useNativeICC" "false")
    (System/setProperty "glass.gtk.uiScale" "1.0")))

;; ============================================================================
;; Startup Configuration
;; ============================================================================

(defn configure-platform!
  "Configure platform settings for optimal dark mode support.
   Should be called early, before JavaFX initialization if possible."
  []
  ;; Enable implicit exit to be off (allows REPL usage)
  (try
    (Platform/setImplicitExit false)
    (catch Exception _e nil))
  
  ;; Set general rendering hints
  (System/setProperty "prism.lcdtext" "false")
  (System/setProperty "prism.text" "t2k"))

;; ============================================================================
;; Theme Info
;; ============================================================================

(defn theme-info
  "Get a map of current theme information."
  []
  {:color-scheme (get-color-scheme)
   :system-dark? (system-dark-mode?)
   :os (System/getProperty "os.name")
   :java-version (System/getProperty "java.version")
   :javafx-version (try
                     (System/getProperty "javafx.runtime.version")
                     (catch Exception _ "unknown"))})

;; ============================================================================
;; Debug / REPL
;; ============================================================================

(comment
  ;; Check current theme info
  (theme-info)
  
  ;; Check if system is in dark mode
  (system-dark-mode?)
  
  ;; Get color scheme
  (get-color-scheme)
  
  ;; Listen for theme changes (call from JavaFX thread)
  (def remove-listener 
    (add-color-scheme-listener! 
     (fn [scheme] (println "Theme changed to:" scheme))))
  
  ;; Stop listening
  (remove-listener)
  )
