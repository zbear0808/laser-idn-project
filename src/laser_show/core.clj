(ns laser-show.core
  "Main entry point for the Laser Show application.
   Creates the main window with grid, preview, and controls."
  (:require [seesaw.core :as ss]
            [seesaw.mig :as mig]
            [seesaw.border :as border]
            [seesaw.color :as sc]
            [laser-show.animation.types :as t]
            [laser-show.animation.presets :as presets]
            [laser-show.ui.grid :as grid]
            [laser-show.ui.preview :as preview])
  (:import [java.awt Color Dimension Font]
           [javax.swing UIManager JFrame]
           [com.formdev.flatlaf FlatDarkLaf])
  (:gen-class))

;; ============================================================================
;; Application State
;; ============================================================================

(defonce app-state
  (atom {:main-frame nil
         :grid nil
         :preview nil
         :idn-connected false
         :idn-target nil
         :playing false
         :current-animation nil}))

;; ============================================================================
;; Status Bar
;; ============================================================================

(defn- create-status-bar
  "Create the status bar showing connection status and FPS."
  []
  (let [connection-label (ss/label :text "IDN: Disconnected"
                                   :foreground (Color. 255 100 100)
                                   :font (Font. "SansSerif" Font/PLAIN 11))
        fps-label (ss/label :text "FPS: --"
                            :foreground Color/WHITE
                            :font (Font. "SansSerif" Font/PLAIN 11))
        points-label (ss/label :text "Points: --"
                               :foreground Color/WHITE
                               :font (Font. "SansSerif" Font/PLAIN 11))]
    {:panel (mig/mig-panel
             :constraints ["insets 5 10 5 10", "[grow][][]", ""]
             :items [[connection-label "growx"]
                     [fps-label ""]
                     [points-label ""]]
             :background (Color. 35 35 35))
     :set-connection! (fn [connected target]
                        (if connected
                          (do
                            (ss/config! connection-label :text (str "IDN: " target))
                            (ss/config! connection-label :foreground (Color. 100 255 100)))
                          (do
                            (ss/config! connection-label :text "IDN: Disconnected")
                            (ss/config! connection-label :foreground (Color. 255 100 100)))))
     :set-fps! (fn [fps]
                 (ss/config! fps-label :text (format "FPS: %.1f" (float fps))))
     :set-points! (fn [points]
                    (ss/config! points-label :text (str "Points: " points)))}))

;; ============================================================================
;; Toolbar
;; ============================================================================

(defn- create-toolbar
  "Create the main toolbar with playback and connection controls."
  [on-play on-stop on-connect]
  (let [play-btn (ss/button :text "▶ Play"
                            :font (Font. "SansSerif" Font/BOLD 12))
        stop-btn (ss/button :text "■ Stop"
                            :font (Font. "SansSerif" Font/BOLD 12))
        connect-btn (ss/button :text "🔌 Connect IDN"
                               :font (Font. "SansSerif" Font/PLAIN 11))
        target-field (ss/text :text "192.168.1.100"
                              :columns 12
                              :font (Font. "Monospaced" Font/PLAIN 11))]
    
    (ss/listen play-btn :action (fn [_] (on-play)))
    (ss/listen stop-btn :action (fn [_] (on-stop)))
    (ss/listen connect-btn :action (fn [_] (on-connect (ss/text target-field))))
    
    {:panel (mig/mig-panel
             :constraints ["insets 5 10 5 10", "[][][][grow][]", ""]
             :items [[play-btn ""]
                     [stop-btn ""]
                     [(ss/label :text "   ") ""]
                     [(ss/label :text "") "growx"]
                     [(ss/label :text "Target:") ""]
                     [target-field ""]
                     [connect-btn ""]]
             :background (Color. 45 45 45))
     :set-playing! (fn [playing]
                     (ss/config! play-btn :enabled? (not playing))
                     (ss/config! stop-btn :enabled? playing))
     :get-target (fn [] (ss/text target-field))}))

;; ============================================================================
;; Menu Bar
;; ============================================================================

(defn- create-menu-bar
  "Create the application menu bar."
  [on-new on-open on-save on-about]
  (ss/menubar
   :items [(ss/menu :text "File"
                    :items [(ss/action :name "New Grid" :handler (fn [_] (on-new)))
                            (ss/action :name "Open..." :handler (fn [_] (on-open)))
                            (ss/action :name "Save..." :handler (fn [_] (on-save)))
                            :separator
                            (ss/action :name "Exit" :handler (fn [_] (System/exit 0)))])
           (ss/menu :text "View"
                    :items [(ss/action :name "Reset Layout" :handler (fn [_] nil))])
           (ss/menu :text "Help"
                    :items [(ss/action :name "About Laser Show" :handler (fn [_] (on-about)))])]))

;; ============================================================================
;; Main Window
;; ============================================================================

(defn create-main-window
  "Create and configure the main application window."
  []
  (let [;; Create preview panel
        preview-component (preview/create-preview-panel :width 350 :height 350)
        
        ;; Track selected preset for assignment
        selected-preset-atom (atom nil)
        
        ;; Mutable reference for grid component (needed for forward reference)
        grid-ref (atom nil)
        
        ;; Create grid panel with handlers
        grid-component (grid/create-grid-panel
                        :cols 8
                        :rows 4
                        :on-cell-click (fn [[col row] cell-state]
                                         (println "Cell clicked:" [col row])
                                         ;; If a preset is selected from palette, assign it
                                         (when-let [preset-id @selected-preset-atom]
                                           (when-let [gc @grid-ref]
                                             ((:set-cell-preset! gc) col row preset-id))
                                           (reset! selected-preset-atom nil))
                                         ;; If cell has animation, play it
                                         (when-let [anim (:animation cell-state)]
                                           ((:set-animation! preview-component) anim)
                                           (when-let [gc @grid-ref]
                                             ((:set-active-cell! gc) col row))
                                           (swap! app-state assoc 
                                                  :playing true 
                                                  :current-animation anim)))
                        :on-cell-right-click (fn [[col row] cell-state]
                                               (println "Cell right-clicked:" [col row])
                                               ;; Clear the cell
                                               (when-let [gc @grid-ref]
                                                 ((:set-cell-preset! gc) col row nil))))
        
        ;; Store grid component in ref for use in callbacks
        _ (reset! grid-ref grid-component)
        
        ;; Create preset palette
        preset-palette (grid/create-preset-palette
                        (fn [preset-id]
                          (println "Preset selected:" preset-id)
                          (reset! selected-preset-atom preset-id)
                          ;; If a cell is selected, assign directly
                          (when-let [gc @grid-ref]
                            (when-let [[col row] ((:get-selected-cell gc))]
                              ((:set-cell-preset! gc) col row preset-id)
                              (reset! selected-preset-atom nil)))))
        
        ;; Create status bar
        status-bar (create-status-bar)
        
        ;; Create toolbar
        toolbar (create-toolbar
                 ;; On Play
                 (fn []
                   (when-let [anim (:current-animation @app-state)]
                     ((:set-animation! preview-component) anim)
                     (swap! app-state assoc :playing true)))
                 ;; On Stop
                 (fn []
                   ((:stop! preview-component))
                   ((:set-active-cell! grid-component) nil nil)
                   (swap! app-state assoc :playing false :current-animation nil))
                 ;; On Connect
                 (fn [target]
                   (println "Connecting to IDN target:" target)
                   (swap! app-state assoc :idn-target target :idn-connected true)
                   ((:set-connection! status-bar) true target)))
        
        ;; Right panel with preview and palette
        right-panel (ss/border-panel
                     :north (ss/border-panel
                             :center (:panel preview-component)
                             :border (border/line-border :color (Color. 60 60 60) :thickness 1))
                     :center preset-palette
                     :vgap 10
                     :background (Color. 35 35 35))
        
        ;; Main content panel
        content-panel (ss/border-panel
                       :center (:panel grid-component)
                       :east right-panel
                       :south (:panel status-bar)
                       :north (:panel toolbar)
                       :background (Color. 30 30 30))
        
        ;; Create main frame
        frame (ss/frame
               :title "Laser Show - IDN Controller"
               :content content-panel
               :minimum-size [900 :by 600]
               :size [1100 :by 700]
               :on-close :exit)]
    
    ;; Set up menu bar
    (ss/config! frame :menubar 
                (create-menu-bar
                 ;; New
                 (fn [] ((:clear-all! grid-component)))
                 ;; Open
                 (fn [] (println "Open not implemented yet"))
                 ;; Save
                 (fn [] (println "Save not implemented yet"))
                 ;; About
                 (fn [] 
                   (ss/alert frame 
                             "Laser Show - IDN Controller\n\nA launchpad-style interface for laser animations.\n\nVersion 0.1.0"))))
    
    ;; Store references
    (swap! app-state assoc
           :main-frame frame
           :grid grid-component
           :preview preview-component)
    
    ;; Set up some demo presets
    ((:set-cell-preset! grid-component) 0 0 :circle)
    ((:set-cell-preset! grid-component) 1 0 :spinning-square)
    ((:set-cell-preset! grid-component) 2 0 :triangle)
    ((:set-cell-preset! grid-component) 3 0 :star)
    ((:set-cell-preset! grid-component) 4 0 :spiral)
    ((:set-cell-preset! grid-component) 5 0 :wave)
    ((:set-cell-preset! grid-component) 6 0 :beam-fan)
    ((:set-cell-preset! grid-component) 7 0 :rainbow-circle)
    
    frame))

;; ============================================================================
;; Application Entry Point
;; ============================================================================

(defn start!
  "Start the Laser Show application."
  []
  (ss/invoke-later
   (try
     ;; Set up FlatLaf dark theme
     (FlatDarkLaf/setup)
     (catch Exception e
       (println "Could not set FlatLaf theme:" (.getMessage e))))
   
   (let [frame (create-main-window)]
     (ss/show! frame))))

(defn -main
  "Main entry point."
  [& args]
  (start!))

;; For REPL development
(comment
  (start!)
  
  ;; Test animation
  (let [anim (presets/create-animation-from-preset :spinning-square)]
    ((:set-animation! (:preview @app-state)) anim))
  
  ;; Stop animation
  ((:stop! (:preview @app-state)))
  )
