(ns laser-show.views.root
  "Root view component for the laser show application.
   
   This is the top-level component that:
   - Creates the main window (Stage)
   - Sets up the layout structure
   - Manages dialogs via fx/ext-let-refs
   - Applies theming via fx/ext-set-env
   
   Component Hierarchy:
   root-view
   ├── toolbar
   ├── tab-bar  
   ├── main-content (split-pane)
   │   ├── tab-content (grid/effects/projectors/settings)
   │   └── preview-panel
   ├── status-bar
   └── dialogs (via refs)"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.views.components.menu-bar :as menu-bar]
            [laser-show.views.toolbar :as toolbar]
            [laser-show.views.status-bar :as status-bar]
            [laser-show.views.tabs.grid :as grid-tab]
            [laser-show.views.tabs.effects :as effects-tab]
            [laser-show.views.components.preview :as preview]
            [laser-show.views.dialogs.effect-chain-editor :as effect-chain-editor]))

;; ============================================================================
;; Theme
;; ============================================================================

(def theme
  "Application theme colors and styles."
  {:colors {:background "#1E1E1E"
            :surface "#2D2D2D"
            :surface-light "#3D3D3D"
            :primary "#4CAF50"
            :primary-dark "#388E3C"
            :secondary "#2196F3"
            :accent "#FF9800"
            :text "#FFFFFF"
            :text-secondary "#B0B0B0"
            :error "#F44336"
            :cell-empty "#424242"
            :cell-content "#616161"
            :cell-active "#4CAF50"
            :cell-selected "#2196F3"}
   :spacing {:xs 4 :sm 8 :md 16 :lg 24 :xl 32}
   :fonts {:default "System"
           :mono "Consolas"}})

;; ============================================================================
;; Tab Content Router
;; ============================================================================

(defn tab-content
  "Render the appropriate tab content based on active tab."
  [{:keys [fx/context]}]
  (let [active-tab (fx/sub-ctx context subs/active-tab)]
    (case active-tab
      :grid {:fx/type grid-tab/grid-tab}
      :effects {:fx/type effects-tab/effects-tab}
      :projectors {:fx/type :v-box
                   :padding 20
                   :children [{:fx/type :label
                               :text "Projectors Configuration"
                               :style "-fx-font-size: 18; -fx-text-fill: white;"}
                              {:fx/type :label
                               :text "Coming soon..."
                               :style "-fx-text-fill: #808080;"}]}
      :settings {:fx/type :v-box
                 :padding 20
                 :children [{:fx/type :label
                             :text "Settings"
                             :style "-fx-font-size: 18; -fx-text-fill: white;"}
                            {:fx/type :label
                             :text "Coming soon..."
                             :style "-fx-text-fill: #808080;"}]}
      ;; Default
      {:fx/type :label :text "Unknown tab"})))

;; ============================================================================
;; Tab Bar
;; ============================================================================

(defn tab-button
  "A single tab button."
  [{:keys [tab label active? on-action]}]
  {:fx/type :button
   :text label
   :style (str "-fx-background-color: "
               (if active? "#4CAF50" "#3D3D3D")
               "; -fx-text-fill: white; "
               "-fx-background-radius: 4 4 0 0; "
               "-fx-padding: 8 16; "
               "-fx-cursor: hand;")
   :on-action on-action})

(defn tab-bar
  "Tab bar component."
  [{:keys [fx/context]}]
  (let [active-tab (fx/sub-ctx context subs/active-tab)]
    {:fx/type :h-box
     :style "-fx-background-color: #2D2D2D; -fx-padding: 8 8 0 8;"
     :spacing 4
     :children [{:fx/type tab-button
                 :tab :grid
                 :label "Grid"
                 :active? (= active-tab :grid)
                 :on-action {:event/type :ui/set-active-tab :tab :grid}}
                {:fx/type tab-button
                 :tab :effects
                 :label "Effects"
                 :active? (= active-tab :effects)
                 :on-action {:event/type :ui/set-active-tab :tab :effects}}
                {:fx/type tab-button
                 :tab :projectors
                 :label "Projectors"
                 :active? (= active-tab :projectors)
                 :on-action {:event/type :ui/set-active-tab :tab :projectors}}
                {:fx/type tab-button
                 :tab :settings
                 :label "Settings"
                 :active? (= active-tab :settings)
                 :on-action {:event/type :ui/set-active-tab :tab :settings}}]}))

;; ============================================================================
;; Main Content Area
;; ============================================================================

(defn main-content
  "Main content area with tab content and preview panel."
  [{:keys [fx/context]}]
  {:fx/type :split-pane
   :style "-fx-background-color: #1E1E1E;"
   :divider-positions [0.65]
   :items [{:fx/type :border-pane
            :style "-fx-background-color: #1E1E1E;"
            :center {:fx/type tab-content}}
           {:fx/type :border-pane
            :style "-fx-background-color: #2D2D2D;"
            :center {:fx/type preview/preview-panel}}]})

;; ============================================================================
;; Main Layout
;; ============================================================================

(defn main-layout
  "Main application layout."
  [{:keys [fx/context]}]
  {:fx/type :border-pane
   :style "-fx-background-color: #1E1E1E;"
   :top {:fx/type :v-box
         :children [{:fx/type menu-bar/menu-bar-view}
                    {:fx/type toolbar/toolbar}
                    {:fx/type tab-bar}]}
   :center {:fx/type main-content}
   :bottom {:fx/type status-bar/status-bar}})

;; ============================================================================
;; Root View
;; ============================================================================

(defn root-view
  "Root view with theming and dialog management.
   
   Uses fx/ext-many to manage multiple windows (main window + dialogs)."
  [{:keys [fx/context]}]
  (let [{:keys [title]} (fx/sub-ctx context subs/project-status)
        window-config (fx/sub-ctx context subs/window-config)
        effect-editor-open? (fx/sub-ctx context subs/dialog-open? :effect-chain-editor)]
    {:fx/type fx/ext-set-env
     :env {::theme theme}
     :desc {:fx/type fx/ext-many
            :desc (filterv
                    some?
                    [;; Main application window
                     {:fx/type :stage
                      :title title
                      :width (:width window-config 1200)
                      :height (:height window-config 800)
                      :showing true
                      :on-close-request (fn [_]
                                          ;; TODO: Check for unsaved changes
                                          (System/exit 0))
                      :scene {:fx/type :scene
                              :stylesheets [(str "data:text/css,"
                                                 (java.net.URLEncoder/encode
                                                   ".root { -fx-base: #1E1E1E; -fx-background: #1E1E1E; }"
                                                   "UTF-8"))]
                              :root {:fx/type main-layout}}}
                     ;; Effect chain editor dialog
                     (when effect-editor-open?
                       {:fx/type effect-chain-editor/effect-chain-editor-dialog})])}}))
