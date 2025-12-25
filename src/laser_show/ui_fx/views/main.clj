(ns laser-show.ui-fx.views.main
  "Main window layout for the cljfx UI.
   Uses JavaFX 26's StageStyle.EXTENDED and HeaderBar for menu in title bar.
   
   This is the root component - it receives fx/context and uses subscriptions
   to get state, then passes props down to child components."
  (:require [cljfx.api :as fx]
            [laser-show.ui-fx.styles :as styles]
            [laser-show.ui-fx.subs :as subs]
            [laser-show.ui-fx.views.toolbar :as toolbar]
            [laser-show.ui-fx.views.grid :as grid]
            [laser-show.ui-fx.views.preview :as preview]
            [laser-show.ui-fx.views.preset-palette :as preset-palette]
            [laser-show.ui-fx.views.effects-grid :as effects-grid]
            [laser-show.ui-fx.platform-theme :as platform-theme]
            [laser-show.ui-fx.components.menu-bar :as menu-bar-component])
  (:import [javafx.stage Stage]
           [javafx.scene.layout HeaderBar]
           [javafx.scene.paint Color]))

;; ============================================================================
;; Left Panel (Grid + Effects)
;; ============================================================================

(defn left-panel
  "Left side panel containing cue grid and effects grid.
   
   Props:
   - :fx/context - cljfx context (passed automatically)
   - :grid-cols - Cue grid columns
   - :grid-rows - Cue grid rows
   - :effects-cols - Effects grid columns
   - :effects-rows - Effects grid rows"
  [{:keys [grid-cols grid-rows effects-cols effects-rows]
    :or {grid-cols 8 grid-rows 4
         effects-cols 5 effects-rows 2}}]
  {:fx/type :v-box
   :style (str "-fx-background-color: " (:background styles/colors) ";")
   :children [{:fx/type grid/grid-panel
               :cols grid-cols
               :rows grid-rows
               :v-box/vgrow :always}
              {:fx/type effects-grid/effects-grid-panel
               :cols effects-cols
               :rows effects-rows}]})

;; ============================================================================
;; Right Panel (Preview + Presets)
;; ============================================================================

(defn right-panel
  "Right side panel containing preview and preset palette.
   
   Props:
   - :fx/context - cljfx context (passed automatically)
   - :preview-width - Preview panel width
   - :preview-height - Preview panel height"
  [{:keys [preview-width preview-height]
    :or {preview-width 350 preview-height 350}}]
  {:fx/type :v-box
   :style (str "-fx-background-color: " (:surface styles/colors) ";")
   :spacing 8
   :pref-width (+ preview-width 16)
   :children [{:fx/type preview/preview-panel-titled
               :title "Preview"
               :width preview-width
               :height preview-height}
              {:fx/type preset-palette/preset-palette-panel
               :title "Presets"
               :v-box/vgrow :always}]})

;; ============================================================================
;; Main Content
;; ============================================================================

(defn main-content
  "Main content area with split pane.
   
   Props: All from left-panel and right-panel"
  [{:keys [grid-cols grid-rows effects-cols effects-rows
           preview-width preview-height]}]
  {:fx/type :split-pane
   :style (str "-fx-background-color: " (:background styles/colors) ";")
   :divider-positions [0.65]
   :items [{:fx/type left-panel
            :grid-cols grid-cols
            :grid-rows grid-rows
            :effects-cols effects-cols
            :effects-rows effects-rows}
           {:fx/type right-panel
            :preview-width preview-width
            :preview-height preview-height}]})

;; ============================================================================
;; Platform Theme Hook
;; ============================================================================

(defonce ^:private theme-configured? (atom false))

(defn- configure-theme-once!
  "Configure platform theme settings once at startup."
  [^Stage _stage]
  (when-not @theme-configured?
    (reset! theme-configured? true)
    (let [info (platform-theme/theme-info)]
      (println "Theme info:" info))
    (platform-theme/configure-platform!)))

;; ============================================================================
;; HeaderBar Setup
;; ============================================================================

(defn- setup-header-bar!
  "Set up the HeaderBar in the given VBox.
   Creates the HeaderBar with menu bar and app icon, then inserts it at the top.
   
   Args:
   - top-vbox: The VBox that will contain the HeaderBar"
  [^javafx.scene.layout.VBox top-vbox]
  (let [header-bar (HeaderBar.)
        leading-box (menu-bar-component/create-header-leading-box)]
    (.setStyle header-bar (str "-fx-background-color: " (:surface styles/colors) ";"))
    (.setLeading header-bar leading-box)
    (-> top-vbox .getChildren (.add 0 header-bar))))

;; ============================================================================
;; Root Component
;; ============================================================================

(defn root
  "Root component using JavaFX 26 EXTENDED stage style.
   Places MenuBar in the title bar area using HeaderBar.
   
   Props:
   - :fx/context - cljfx context (passed automatically by renderer)"
  [{:keys [fx/context]}]
  ;; Subscribe to state via context
  (let [playing? (fx/sub-ctx context subs/playing?)
        bpm (fx/sub-ctx context subs/bpm)
        connected? (fx/sub-ctx context subs/connected?)
        target (fx/sub-ctx context subs/connection-target)
        project-dirty? (fx/sub-ctx context subs/project-dirty?)
        project-folder (fx/sub-ctx context subs/project-folder)
        title (str "Laser Show" (when project-dirty? " •"))]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [^Stage stage]
                   (configure-theme-once! stage))
     :desc {:fx/type :stage
            :title title
            :style :extended
            :showing true
            :width 1100
            :height 700
            :min-width 900
            :min-height 600
            :scene {:fx/type :scene
                    :fill (Color/web (:background styles/colors))
                    :stylesheets [(str "data:text/css," (java.net.URLEncoder/encode styles/root-css "UTF-8"))]
                    :root {:fx/type fx/ext-on-instance-lifecycle
                           :on-created (fn [^javafx.scene.layout.BorderPane bp]
                                         (when-let [top-vbox (.getTop bp)]
                                           (when (instance? javafx.scene.layout.VBox top-vbox)
                                             (setup-header-bar! top-vbox))))
                           :desc {:fx/type :border-pane
                                  :style (str "-fx-background-color: " (:background styles/colors) ";")
                                  
                                  :top {:fx/type :v-box
                                        :children [{:fx/type toolbar/toolbar
                                                    :playing? playing?
                                                    :bpm bpm
                                                    :connected? connected?
                                                    :target target}]}
                                  
                                  :center {:fx/type main-content
                                           :grid-cols 8
                                           :grid-rows 4
                                           :effects-cols 5
                                           :effects-rows 2
                                           :preview-width 350
                                           :preview-height 350}
                                  
                                  :bottom {:fx/type toolbar/status-bar
                                           :connected? connected?
                                           :target target
                                           :project-dirty? project-dirty?
                                           :project-folder project-folder}}}}}}))
