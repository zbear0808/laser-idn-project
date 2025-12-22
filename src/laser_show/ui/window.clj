(ns laser-show.ui.window
  "Window lifecycle management for the Laser Show application.
   
   This namespace manages the main application window and its state.
   It provides functions to show, close, and check window status
   that work well with REPL-driven development."
  (:require [seesaw.core :as ss]
            [seesaw.border :as border]
            [laser-show.animation.types :as t]
            [laser-show.animation.time :as anim-time]
            [laser-show.animation.effects.shape]
            [laser-show.animation.effects.color]
            [laser-show.animation.effects.intensity]
            [laser-show.ui.grid :as grid]
            [laser-show.ui.effects-grid :as effects-grid]
            [laser-show.ui.effect-dialogs :as effect-dialogs]
            [laser-show.ui.preview :as preview]
            [laser-show.ui.toolbar :as toolbar]
            [laser-show.ui.layout :as layout]
            [laser-show.backend.packet-logger :as plog]
            [laser-show.backend.streaming-engine :as streaming]
            [laser-show.state.clipboard :as clipboard])
  (:import [java.awt Color Font]
           [java.awt.event WindowAdapter]
           [com.formdev.flatlaf FlatDarkLaf]))

;; ============================================================================
;; Application State
;; ============================================================================

(defonce app-state
  (atom {:main-frame nil
         :grid nil
         :effects-grid nil
         :preview nil
         :idn-connected false
         :idn-target nil
         :playing false
         :current-animation nil
         :animation-start-time 0
         :streaming-engine nil
         :packet-logging-enabled false
         :packet-log-file nil
         :packet-log-path "idn-packets.log"
         :status-bar nil
         :toolbar nil}))

;; ============================================================================
;; Window State Queries
;; ============================================================================

(defn window-open?
  "Returns true if the main window exists and is displayable."
  []
  (when-let [frame (:main-frame @app-state)]
    (.isDisplayable frame)))

(defn get-frame
  "Returns the main frame, or nil if not open."
  []
  (:main-frame @app-state))

;; ============================================================================
;; Frame Provider for Streaming
;; ============================================================================

(defn create-frame-provider
  "Create a function that provides the current animation frame for streaming.
   The frame provider reads from app-state and generates frames based on
   the current animation and elapsed time.
   
   Also applies any active effects from the effects grid."
  []
  (fn []
    (when-let [anim (:current-animation @app-state)]
      (let [start-time (:animation-start-time @app-state)
            elapsed (- (System/currentTimeMillis) start-time)
            bpm (anim-time/get-global-bpm)
            base-frame (t/get-frame anim elapsed)]
        (if-let [eg (:effects-grid @app-state)]
          ((:apply-to-frame eg) base-frame elapsed bpm)
          base-frame)))))

;; ============================================================================
;; IDN Packet Logging Helper
;; ============================================================================

(defn get-packet-log-callback
  "Returns a logging callback function if packet logging is enabled, nil otherwise."
  []
  (when (:packet-logging-enabled @app-state)
    (when-let [writer (:packet-log-file @app-state)]
      (plog/create-log-callback writer))))

;; ============================================================================
;; Window Cleanup Callback
;; ============================================================================

(defonce ^:private on-window-close-callbacks (atom []))

(defn add-on-close-callback!
  "Register a callback to be called when the window is closed.
   Useful for cleanup operations like shutting down input systems."
  [callback]
  (swap! on-window-close-callbacks conj callback))

(defn clear-on-close-callbacks!
  "Clear all registered on-close callbacks."
  []
  (reset! on-window-close-callbacks []))

(defn- run-close-callbacks!
  "Run all registered close callbacks."
  []
  (doseq [cb @on-window-close-callbacks]
    (try
      (cb)
      (catch Exception e
        (println "Error in close callback:" (.getMessage e))))))

;; ============================================================================
;; Main Window Creation
;; ============================================================================

(defn- create-main-window-internal
  "Create and configure the main application window.
   This is an internal function - use show-window! instead."
  []
  (let [effects-grid-ref (atom nil)
        
        frame-processor (fn [frame elapsed-ms]
                          (let [bpm (anim-time/get-global-bpm)]
                            (if-let [eg @effects-grid-ref]
                              ((:apply-to-frame eg) frame elapsed-ms bpm)
                              frame)))
        
        preview-component (preview/create-preview-panel 
                           :width 350 
                           :height 350
                           :frame-processor frame-processor)
        
        selected-preset-atom (atom nil)
        grid-ref (atom nil)
        
        grid-component (grid/create-grid-panel
                        :on-cell-click (fn [[col row] cell-state]
                                         (println "Cell clicked:" [col row])
                                         (when-let [preset-id @selected-preset-atom]
                                           (when-let [gc @grid-ref]
                                             ((:set-cell-preset! gc) col row preset-id))
                                           (reset! selected-preset-atom nil))
                                         (when-let [anim (:animation cell-state)]
                                           ((:set-animation! preview-component) anim)
                                           (when-let [gc @grid-ref]
                                             ((:set-active-cell! gc) col row))
                                           (swap! app-state assoc 
                                                  :playing true 
                                                  :current-animation anim
                                                  :animation-start-time (System/currentTimeMillis))))
                        :on-cell-right-click (fn [[col row] cell-state]
                                               (println "Cell right-clicked:" [col row]))
                        :on-copy (fn [[col row] cell-state]
                                   (when-let [preset-id (:preset-id cell-state)]
                                     (clipboard/copy-cell-assignment! preset-id)
                                     (println "Copied preset:" preset-id)))
                        :on-paste (fn [[col row]]
                                    (when-let [preset-id (clipboard/paste-cell-assignment)]
                                      (when-let [gc @grid-ref]
                                        ((:set-cell-preset! gc) col row preset-id)
                                        (println "Pasted preset:" preset-id "to" [col row]))))
                        :on-clear (fn [[col row]]
                                    (when-let [gc @grid-ref]
                                      ((:set-cell-preset! gc) col row nil)
                                      (println "Cleared cell:" [col row]))))
        
        _ (reset! grid-ref grid-component)
        
        preset-palette (grid/create-preset-palette
                        (fn [preset-id]
                          (println "Preset selected:" preset-id)
                          (reset! selected-preset-atom preset-id)
                          (when-let [gc @grid-ref]
                            (when-let [[col row] ((:get-selected-cell gc))]
                              ((:set-cell-preset! gc) col row preset-id)
                              (reset! selected-preset-atom nil)))))
        
        main-frame-ref (atom nil)
        
        effects-grid-component (effects-grid/create-effects-grid-panel
                                :on-effects-change (fn [active-effects]
                                                     (println "Active effects:" (count active-effects)))
                                :on-new-effect (fn [cell-key]
                                                 (println "New effect for:" cell-key)
                                                 (let [col (first cell-key)
                                                       row (second cell-key)
                                                       has-effect-atom (atom false)]
                                                   (effect-dialogs/show-effect-dialog! 
                                                    @main-frame-ref nil
                                                    (fn [effect-data]
                                                      (when (and effect-data @effects-grid-ref)
                                                        ((:set-cell-effect! @effects-grid-ref) col row effect-data)
                                                        (println "Created effect:" (:effect-id effect-data))))
                                                    :on-effect-change (fn [effect-data]
                                                                        (when @effects-grid-ref
                                                                          (reset! has-effect-atom true)
                                                                          ((:set-cell-effect! @effects-grid-ref) col row effect-data)))
                                                    :on-cancel (fn []
                                                                 (when (and @has-effect-atom @effects-grid-ref)
                                                                   ((:set-cell-effect! @effects-grid-ref) col row nil)
                                                                   (println "Cancelled, cleared effect"))))))
                                :on-edit-effect (fn [cell-key cell-state]
                                                  (println "Edit effect for:" cell-key)
                                                  (let [col (first cell-key)
                                                        row (second cell-key)
                                                        existing-effect (:data cell-state)
                                                        original-effect existing-effect]
                                                    (effect-dialogs/show-effect-dialog!
                                                     @main-frame-ref existing-effect
                                                     (fn [effect-data]
                                                       (when (and effect-data @effects-grid-ref)
                                                         ((:set-cell-effect! @effects-grid-ref) col row effect-data)
                                                         (println "Updated effect:" (:effect-id effect-data))))
                                                     :on-effect-change (fn [effect-data]
                                                                         (when @effects-grid-ref
                                                                           ((:set-cell-effect! @effects-grid-ref) col row effect-data)))
                                                     :on-cancel (fn []
                                                                  (when @effects-grid-ref
                                                                    ((:set-cell-effect! @effects-grid-ref) col row original-effect)
                                                                    (println "Cancelled, restored original effect")))))))
        _ (reset! effects-grid-ref effects-grid-component)
        
        status-bar (toolbar/create-status-bar)
        toolbar-ref (atom nil)
        
        toolbar-component (toolbar/create-toolbar
                           ;; On Play
                           (fn []
                             (when-let [anim (:current-animation @app-state)]
                               ((:set-animation! preview-component) anim)
                               (swap! app-state assoc 
                                      :playing true
                                      :animation-start-time (System/currentTimeMillis))))
                           ;; On Stop
                           (fn []
                             ((:stop! preview-component))
                             ((:set-active-cell! grid-component) nil nil)
                             (swap! app-state assoc :playing false :current-animation nil))
                           ;; On Connect
                           (fn [target]
                             (if (:idn-connected @app-state)
                               (do
                                 (println "Disconnecting from IDN target")
                                 (when-let [engine (:streaming-engine @app-state)]
                                   (streaming/stop! engine))
                                 (swap! app-state assoc 
                                        :idn-connected false 
                                        :idn-target nil
                                        :streaming-engine nil)
                                 ((:set-connection! status-bar) false nil))
                               (do
                                 (println "Connecting to IDN target:" target)
                                 (try
                                   (let [frame-provider (create-frame-provider)
                                         log-callback (get-packet-log-callback)
                                         engine (streaming/create-engine target frame-provider
                                                                         :log-callback log-callback)]
                                     (streaming/start! engine)
                                     (swap! app-state assoc 
                                            :idn-target target 
                                            :idn-connected true
                                            :streaming-engine engine)
                                     ((:set-connection! status-bar) true target))
                                   (catch Exception e
                                     (println "Connection failed:" (.getMessage e))
                                     (ss/alert "Connection failed: " (.getMessage e)))))))
                           ;; On Log Toggle
                           (fn [enabled]
                             (if enabled
                               (let [log-path (:packet-log-path @app-state)
                                     writer (plog/start-logging! log-path)]
                                 (if writer
                                   (do
                                     (swap! app-state assoc
                                            :packet-logging-enabled true
                                            :packet-log-file writer)
                                     (when-let [engine (:streaming-engine @app-state)]
                                       (streaming/set-log-callback! engine (plog/create-log-callback writer))))
                                   (when-let [tb @toolbar-ref]
                                     (ss/value! (:log-checkbox tb) false))))
                               (do
                                 (when-let [writer (:packet-log-file @app-state)]
                                   (plog/stop-logging! writer))
                                 (when-let [engine (:streaming-engine @app-state)]
                                   (streaming/set-log-callback! engine nil))
                                 (swap! app-state assoc
                                        :packet-logging-enabled false
                                        :packet-log-file nil)))))
        
        _ (reset! toolbar-ref toolbar-component)
        
        right-panel (ss/border-panel
                     :north (ss/border-panel
                             :center (:panel preview-component)
                             :border (border/line-border :color (Color. 60 60 60) :thickness 1))
                     :center preset-palette
                     :vgap 10
                     :background (Color. 35 35 35))
        
        effects-label (ss/label :text "Effects"
                                :font (Font. "SansSerif" Font/BOLD 12)
                                :foreground (Color. 180 180 180))
        
        effects-section (ss/border-panel
                         :north (ss/horizontal-panel
                                 :items [effects-label]
                                 :background (Color. 40 40 40)
                                 :border (border/empty-border :left 5 :top 3 :bottom 3))
                         :center (:panel effects-grid-component)
                         :background (Color. 30 30 30))
        
        left-panel (ss/border-panel
                    :center (:panel grid-component)
                    :south effects-section
                    :vgap 5
                    :background (Color. 30 30 30))
        
        left-scrollable (ss/scrollable left-panel
                                       :border nil
                                       :background (Color. 30 30 30))
        
        content-panel (ss/border-panel
                       :center left-scrollable
                       :east right-panel
                       :south (:panel status-bar)
                       :north (:panel toolbar-component)
                       :background (Color. 30 30 30))
        
        ;; Create frame with :dispose instead of :exit
        frame (ss/frame
               :title "Laser Show - IDN Controller"
               :content content-panel
               :minimum-size [900 :by 600]
               :size [1100 :by 700]
               :on-close :dispose)]
    
    ;; Add window listener to clean up state when window is closed
    (.addWindowListener frame
      (proxy [WindowAdapter] []
        (windowClosed [_e]
          (println "Window closed, cleaning up...")
          (run-close-callbacks!)
          (swap! app-state assoc 
                 :main-frame nil
                 :grid nil
                 :effects-grid nil
                 :preview nil
                 :status-bar nil
                 :toolbar nil))))
    
    ;; Set up menu bar
    (ss/config! frame :menubar 
                (toolbar/create-menu-bar
                 frame
                 (:clear-all! grid-component)
                 (fn [] (println "Open not implemented yet"))
                 (fn [] (println "Save not implemented yet"))
                 (fn [] 
                   (ss/alert frame 
                             "Laser Show - IDN Controller\n\nA launchpad-style interface for laser animations.\n\nVersion 0.1.0"))))
    
    (reset! main-frame-ref frame)
    
    ;; Store references
    (swap! app-state assoc
           :main-frame frame
           :grid grid-component
           :effects-grid effects-grid-component
           :preview preview-component
           :status-bar status-bar
           :toolbar toolbar-component)
    
    ;; Set up demo presets
    ((:set-cell-preset! grid-component) 0 0 :circle)
    ((:set-cell-preset! grid-component) 1 0 :spinning-square)
    ((:set-cell-preset! grid-component) 2 0 :triangle)
    ((:set-cell-preset! grid-component) 3 0 :star)
    ((:set-cell-preset! grid-component) 4 0 :spiral)
    ((:set-cell-preset! grid-component) 0 1 :wave)
    ((:set-cell-preset! grid-component) 1 1 :beam-fan)
    ((:set-cell-preset! grid-component) 2 1 :rainbow-circle)
    
    frame))

;; ============================================================================
;; Public Window Lifecycle Functions
;; ============================================================================

(defn show-window!
  "Show the main window. If a window already exists and is displayable,
   brings it to front. Otherwise creates a new window.
   
   Returns the frame."
  []
  (ss/invoke-later
    (try
      (FlatDarkLaf/setup)
      (catch Exception e
        (println "Could not set FlatLaf theme:" (.getMessage e))))
    
    (if (window-open?)
      ;; Window exists, bring to front
      (let [frame (:main-frame @app-state)]
        (println "Window already open, bringing to front")
        (.toFront frame)
        (.requestFocus frame)
        frame)
      ;; Create new window
      (let [frame (create-main-window-internal)]
        (println "Created new window")
        (ss/show! frame)
        frame))))

(defn close-window!
  "Close the main window and clean up resources.
   This disposes the window but does not exit the JVM."
  []
  (println "Closing window...")
  ;; Stop streaming if connected
  (when-let [engine (:streaming-engine @app-state)]
    (try
      (streaming/stop! engine)
      (catch Exception _e nil)))
  
  ;; Stop packet logging if enabled
  (when-let [writer (:packet-log-file @app-state)]
    (try
      (plog/stop-logging! writer)
      (catch Exception _e nil)))
  
  ;; Dispose the frame (this triggers windowClosed which cleans up state)
  (when-let [frame (:main-frame @app-state)]
    (ss/invoke-later
      (try
        (.dispose frame)
        (println "✓ Window closed")
        (catch Exception e
          (println "Note: Window may already be closed:" (.getMessage e))))))
  
  ;; Clear streaming/logging state
  (swap! app-state assoc
         :idn-connected false
         :idn-target nil
         :streaming-engine nil
         :packet-logging-enabled false
         :packet-log-file nil
         :playing false
         :current-animation nil))

(defn bring-to-front!
  "Bring the main window to front if it exists."
  []
  (when-let [frame (:main-frame @app-state)]
    (ss/invoke-later
      (.toFront frame)
      (.requestFocus frame))))
