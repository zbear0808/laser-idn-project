(ns laser-show.ui.window
  "Window lifecycle management for the Laser Show application.
   Refactored to use Uni-directional Data Flow."
  (:require
   [clojure.java.io :as io]
   [laser-show.animation.effects.color]
   [laser-show.animation.effects.intensity]
   [laser-show.animation.effects.shape]
   [laser-show.animation.time :as anim-time]
   [laser-show.animation.types :as t]
   [laser-show.app-events :as events]
   [laser-show.app-db :as state]
   [laser-show.ui.effect-dialogs :as effect-dialogs]
   [laser-show.ui.effects-grid :as effects-grid]
   [laser-show.ui.grid :as grid]
   [laser-show.ui.preview :as preview]
   [laser-show.ui.toolbar :as toolbar]
   [seesaw.border :as border]
   [seesaw.core :as ss])
  (:import
   [com.formdev.flatlaf FlatDarkLaf]
   [com.formdev.flatlaf.themes FlatMacDarkLaf]
   [java.awt Color]
   [java.awt.event WindowAdapter]
   [javax.imageio ImageIO]))

;; ============================================================================
;; State References
;; ============================================================================

;; Backward compatibility for core/app-state if needed, though core should change
(def app-state state/app-state)

;; ============================================================================
;; Frame Provider for Streaming
;; ============================================================================

(defn create-frame-provider
  "Create a function that provides the current animation frame for streaming.
   Reads from global app-state."
  []
  (fn []
    (let [current-state @state/app-state
          anim (:current-animation current-state)]
      (when anim
        (let [start-time (:animation-start-time current-state)
              elapsed (- (System/currentTimeMillis) start-time)
              bpm (anim-time/get-global-bpm)
              base-frame (t/get-frame anim elapsed)]
          ;; Effect grid still needs legacy integration or refactor
          ;; For now, use the active effects from state if migrated, 
          ;; or we might have broken effect grid momentarily.
          ;; Assuming effects are applied elsewhere or we skip effects for this phase.
          base-frame)))))

;; ============================================================================
;; Window Cleanup
;; ============================================================================

(defonce ^:private on-window-close-callbacks (atom []))

(defn add-on-close-callback! [callback]
  (swap! on-window-close-callbacks conj callback))

(defn clean-up! []
  (doseq [cb @on-window-close-callbacks]
    (try (cb) (catch Exception e (println "Error in close callback:" (.getMessage e)))))
  (reset! on-window-close-callbacks []))

;; ============================================================================
;; Main Window Creation
;; ============================================================================

(defn- create-main-window-internal []
  (let [;; --- Components ---
        
        ;; Atom for forward reference to effects grid
        !effects-grid-ref (atom nil)
        
        ;; Create preview with frame processor that applies effects
        preview-comp (preview/create-preview-panel 
                       :width 350 :height 350
                       :frame-processor (fn [frame _time-ms]
                                          (if-let [effects-grid @!effects-grid-ref]
                                            ((:apply-to-frame effects-grid) frame (System/currentTimeMillis) (anim-time/get-global-bpm))
                                            frame)))
        
        ;; Passing dispatch! to grid is key
        grid-comp (grid/create-grid-panel events/dispatch!)
        
        preset-palette (grid/create-preset-palette events/dispatch!)
        
        ;; Effects Grid with proper callbacks including live preview
        effects-grid-comp (effects-grid/create-effects-grid-panel
                           :on-effects-change (fn [active-effects] 
                                                (println "Active effects:" (count active-effects)))
                           :on-new-effect (fn [cell-key]
                                            (let [original-data nil]
                                              (effect-dialogs/show-effect-dialog!
                                                (get-in @state/app-state [:ui :main-frame])
                                                nil
                                                (fn [effect-data]
                                                  (when (and effect-data @!effects-grid-ref)
                                                    ((:set-cell-effect! @!effects-grid-ref) 
                                                     (first cell-key) 
                                                     (second cell-key) 
                                                     effect-data)))
                                                :on-effect-change (fn [effect-data]
                                                                    ;; Live preview - temporarily apply effect
                                                                    (when @!effects-grid-ref
                                                                      ((:set-cell-effect! @!effects-grid-ref)
                                                                       (first cell-key)
                                                                       (second cell-key)
                                                                       effect-data)))
                                                :on-cancel (fn []
                                                             ;; Revert on cancel
                                                             (when @!effects-grid-ref
                                                               ((:clear-cell! @!effects-grid-ref)
                                                                (first cell-key)
                                                                (second cell-key)))))))
                           :on-edit-effect (fn [cell-key cell-state]
                                             (let [original-data (:data cell-state)]
                                               (effect-dialogs/show-effect-dialog!
                                                 (get-in @state/app-state [:ui :main-frame])
                                                 original-data
                                                 (fn [effect-data]
                                                   (when (and effect-data @!effects-grid-ref)
                                                     ((:set-cell-effect! @!effects-grid-ref)
                                                      (first cell-key)
                                                      (second cell-key)
                                                      effect-data)))
                                                 :on-effect-change (fn [effect-data]
                                                                     ;; Live preview - temporarily apply effect
                                                                     (when @!effects-grid-ref
                                                                       ((:set-cell-effect! @!effects-grid-ref)
                                                                        (first cell-key)
                                                                        (second cell-key)
                                                                        effect-data)))
                                                 :on-cancel (fn []
                                                              ;; Revert on cancel
                                                              (when (and @!effects-grid-ref original-data)
                                                                ((:set-cell-effect! @!effects-grid-ref)
                                                                 (first cell-key)
                                                                 (second cell-key)
                                                                 original-data))
                                                              (when (and @!effects-grid-ref (nil? original-data))
                                                                ((:clear-cell! @!effects-grid-ref)
                                                                 (first cell-key)
                                                                 (second cell-key))))))))
        
        ;; Store reference for callbacks
        _ (reset! !effects-grid-ref effects-grid-comp)
        
        status-bar (toolbar/create-status-bar)
        
        toolbar-comp (toolbar/create-toolbar
                       ;; Play
                       (fn [] (events/dispatch! [:transport/play-pause]))
                       ;; Stop
                       (fn [] (events/dispatch! [:transport/stop]))
                       ;; Connect (Complex logic kept here for now for simplicity, could move to events)
                       (fn [target]
                         (println "Connect logic needs migration to events fully.")
                         ;; For now, just fire an event
                         (events/dispatch! [:idn/connect target (create-frame-provider)]))
                       ;; Log
                       (fn [enabled]
                         (println "Logging toggle pending migration.")))
        
        ;; --- Layout ---
        
        right-panel (ss/border-panel
                     :north (ss/border-panel
                             :center (:panel preview-comp)
                             :border (border/line-border :color (Color. 60 60 60) :thickness 1))
                     :center preset-palette
                     :vgap 10
                     :background (Color. 35 35 35))
        
        left-panel (ss/border-panel
                    :center (:panel grid-comp)
                    :south (:panel effects-grid-comp)
                    :vgap 5
                    :background (Color. 30 30 30))
        
        content-panel (ss/border-panel
                       :center (ss/scrollable left-panel :border nil :background (Color. 30 30 30))
                       :east right-panel
                       :south (:panel status-bar)
                       :north (:panel toolbar-comp)
                       :background (Color. 30 30 30))
        
        frame (ss/frame
               :title "Laser Show - IDN Controller"
               :content content-panel
               :minimum-size [900 :by 600]
               :size [1100 :by 700]
               :icon (try
                       (ImageIO/read (io/resource "laser-warning-square.png"))
                       (catch Exception e
                         (println "Could not load icon:" (.getMessage e))
                         nil))
               :on-close :dispose)
        
        ;; Create and add menu bar after frame exists (needed for dialog parent)
        menu-bar (toolbar/create-menu-bar
                   frame
                   (fn [] (println "File > New - not yet implemented"))
                   (fn [] (println "File > Open - not yet implemented"))
                   (fn [] (println "File > Save - not yet implemented"))
                   (fn [] (ss/alert frame "Laser Show - IDN Controller\n\nA laser show control application using IDN protocol.")))
        _ (.setJMenuBar frame menu-bar)]
    
    (.addWindowListener frame
      (proxy [WindowAdapter] []
        (windowClosed [_e]
          (clean-up!)
          (swap! state/app-state assoc-in [:ui :main-frame] nil))))
    
    ;; --- Wire Up State Watcher ---
    
    (add-watch state/app-state :ui-update
      (fn [_key _ref _old-state new-state]
        (ss/invoke-later
          ;; Update Grid
          ((:update-view! grid-comp) new-state)
          
          ;; Update Preview
          (if-let [anim (:current-animation new-state)]
             ((:set-animation! preview-comp) anim)
             ((:stop! preview-comp)))
          
          ;; Update Status Bar
          (let [{:keys [connected? target]} (:idn new-state)]
            ((:set-connection! status-bar) connected? target)))))
    
    ;; Initial Render
    ((:update-view! grid-comp) @state/app-state)
    
    frame))

;; ============================================================================
;; Public API
;; ============================================================================

(defn show-window! []
  (ss/invoke-later
    (let [is-macos? (= "Mac OS X" (System/getProperty "os.name"))]
      ;; Configure font rendering for macOS BEFORE FlatLaf setup
      ;; This fixes missing/broken characters on macOS, especially Apple Silicon
      (when is-macos?
        ;; Enable LCD text rendering on macOS
        (System/setProperty "awt.useSystemAAFontSettings" "lcd")
        (System/setProperty "swing.aatext" "true")
        ;; Use Quartz rendering on macOS for better font display
        (System/setProperty "apple.awt.graphics.UseQuartz" "true")
        ;; Better sub-pixel rendering
        (System/setProperty "apple.laf.useScreenMenuBar" "true")
        ;; Ensure proper font smoothing
        (System/setProperty "apple.awt.textantialiasing" "on"))
      
      ;; Setup FlatLaf - use macOS-specific theme on macOS for native look
      (FlatMacDarkLaf/setup)
      #_(if is-macos?
        (FlatMacDarkLaf/setup)
        (FlatDarkLaf/setup))
      
      (if-let [frame (get-in @app-state [:ui :main-frame])]
        (do (.toFront frame) frame)
        (let [frame (create-main-window-internal)]
          (ss/show! frame)
          (swap! state/app-state assoc-in [:ui :main-frame] frame)
          frame)))))

(defn close-window! []
  (when-let [frame (get-in @app-state [:ui :main-frame])]
    (.dispose frame)))

(defn window-open? []
  (some? (get-in @app-state [:ui :main-frame])))

(defn get-frame []
  (get-in @app-state [:ui :main-frame]))
