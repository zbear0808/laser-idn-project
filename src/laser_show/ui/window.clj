(ns laser-show.ui.window
  "Window lifecycle management for the Laser Show application.
   Refactored to use Uni-directional Data Flow with state atoms."
(:require
   [clojure.java.io :as io]
   [laser-show.animation.effects.color]
   [laser-show.animation.effects.intensity]
   [laser-show.animation.effects.shape]
   [laser-show.animation.presets :as presets]
   [laser-show.animation.time :as anim-time]
   [laser-show.backend.frame-provider :as frame-provider]
   [laser-show.events.dispatch :as events]
   [laser-show.state.atoms :as state]
   [laser-show.ui.effect-chain-editor :as effect-chain-editor]
   [laser-show.ui.effects-grid :as effects-grid]
   [laser-show.ui.grid :as grid]
   [laser-show.ui.preview :as preview]
   [laser-show.ui.project-actions :as project]
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
        
        ;; Effects Grid with simplified callbacks - uses new effect-chain-editor
        ;; The new editor writes directly to !effects atom, so no callbacks needed
        effects-grid-comp (effects-grid/create-effects-grid-panel
                           :on-effects-change (fn [active-effects] 
                                                (println "Active effects:" (count active-effects)))
                           ;; Both new and edit use the same editor - it handles both cases
                           :on-new-effect (fn [cell-key]
                                            (effect-chain-editor/show-effect-chain-editor!
                                              (state/get-main-frame)
                                              (first cell-key)
                                              (second cell-key)))
                           :on-edit-effect (fn [cell-key _effect-data]
                                             ;; No need to pass effect-data - editor reads from atom
                                             (effect-chain-editor/show-effect-chain-editor!
                                               (state/get-main-frame)
                                               (first cell-key)
                                               (second cell-key))))
        
        ;; Store reference for callbacks
        _ (reset! !effects-grid-ref effects-grid-comp)
        
        status-bar (toolbar/create-status-bar
                     ;; Connect
                     (fn [target]
                       (println "Connect logic needs migration to events fully.")
                       ;; For now, just fire an event - using frame provider WITH effects
                       (events/dispatch! [:idn/connect target
                                          (frame-provider/create-frame-provider-with-effects
                                            (fn [frame time-ms bpm]
                                              (if-let [effects-grid @!effects-grid-ref]
                                                ((:apply-to-frame effects-grid) frame time-ms bpm)
                                                frame)))]))
                     ;; Log
                     (fn [enabled]
                       (println "Logging toggle pending migration.")))
        
        toolbar-comp (toolbar/create-toolbar
                       ;; Play
                       (fn [] (events/dispatch! [:transport/play-pause]))
                       ;; Stop
                       (fn [] (events/dispatch! [:transport/stop])))
        
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
                   (fn [] (project/handle-new-project! frame))      ; File > New
                   (fn [] (project/handle-open-project! frame))     ; File > Open
                   (fn [] (project/handle-save-project! frame))     ; File > Save
                   (fn [] (project/handle-save-as-project! frame))  ; File > Save As
                   (fn [] (ss/alert frame "Laser Show - IDN Controller\n\nA laser show control application using IDN protocol.")))  ; Help > About
        _ (.setJMenuBar frame menu-bar)
        
        ;; Set up dirty tracking for project management
        _ (project/setup-dirty-tracking!)
        
        ;; Helper to convert runtime state to legacy grid format
        make-legacy-state (fn []
                            {:playing? (state/playing?)
                             :grid {:cells (state/get-grid-cells)
                                    :active-cell (state/get-active-cell)
                                    :selected-cell (state/get-selected-cell)
                                    :size (state/get-grid-size)}})]
    
    (.addWindowListener frame
      (proxy [WindowAdapter] []
        (windowClosed [_e]
          (clean-up!)
          (state/set-main-frame! nil))))
    
    ;; --- Wire Up State Watchers ---
    ;; Watch !playback for trigger-time and active-cell changes (KEY for retriggering)
    
    (add-watch state/!playback :preview-update
      (fn [_key _ref old-playback new-playback]
        (ss/invoke-later
          (let [old-trigger (:trigger-time old-playback)
                new-trigger (:trigger-time new-playback)
                old-cell (:active-cell old-playback)
                new-cell (:active-cell new-playback)
                playing? (:playing? new-playback)]
            (cond
              ;; Stop preview if not playing
              (not playing?)
              ((:stop! preview-comp))
              
              ;; Retrigger if trigger-time changed OR active-cell changed
              (or (not= old-trigger new-trigger)
                  (not= old-cell new-cell))
              (when new-cell
                (let [cell (state/get-cell (first new-cell) (second new-cell))
                      preset-id (:preset-id cell)]
                  (when preset-id
                    (let [anim (presets/create-animation-from-preset preset-id)]
                      ((:set-animation! preview-comp) anim)))))
              
              ;; Default: do nothing if no change
              :else nil)))))
    
    ;; Watch !grid for grid UI updates
    (add-watch state/!grid :grid-update
      (fn [_key _ref _old-grid _new-grid]
        (ss/invoke-later
          ((:update-view! grid-comp) (make-legacy-state)))))
    
    ;; Watch !playback for grid active-cell highlight
    (add-watch state/!playback :grid-active-update
      (fn [_key _ref _old-playback _new-playback]
        (ss/invoke-later
          ((:update-view! grid-comp) (make-legacy-state)))))
    
    ;; Watch !idn for status bar updates
    (add-watch state/!idn :status-bar-update
      (fn [_key _ref _old-idn new-idn]
        (ss/invoke-later
          ((:set-connection! status-bar) (:connected? new-idn) (:target new-idn)))))
    
    ;; Initial Render
    ((:update-view! grid-comp) (make-legacy-state))
    
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
      (if is-macos?
        (FlatMacDarkLaf/setup)
        (FlatDarkLaf/setup))
      
      (if-let [frame (state/get-main-frame)]
        (do (.toFront frame) frame)
        (let [frame (create-main-window-internal)]
          (ss/show! frame)
          (state/set-main-frame! frame)
          frame)))))

(defn close-window! []
  (when-let [frame (state/get-main-frame)]
    (.dispose frame)))

(defn window-open? []
  (some? (state/get-main-frame)))

(defn get-frame []
  (state/get-main-frame))
