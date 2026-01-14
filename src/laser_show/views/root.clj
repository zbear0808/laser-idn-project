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
(:require
   [cljfx.api :as fx]
   [laser-show.subs :as subs]
   [laser-show.css.core :as css]
   [laser-show.views.components.title-bar :as title-bar]
   [laser-show.views.components.preview :as preview]
   [laser-show.views.components.tabs :as tabs]
   [laser-show.views.dialogs.effect-chain-editor :as effect-chain-editor]
   [laser-show.views.dialogs.cue-chain-editor :as cue-chain-editor]
   [laser-show.views.dialogs.add-projector-manual :as add-projector-dialog]
   [laser-show.views.status-bar :as status-bar]
   [laser-show.views.tabs.effects :as effects-tab]
   [laser-show.views.tabs.grid :as grid-tab]
   [laser-show.views.tabs.projectors :as projectors-tab]
   [laser-show.views.toolbar :as toolbar]))


;; Layout Constants (non-color theme values)

(def layout-config
  "Layout spacing and font configuration.
   For colors, use laser-show.css.core accessor functions."
  {:spacing {:xs 4 :sm 8 :md 16 :lg 24 :xl 32}
   :fonts {:default "System"
           :mono "Consolas"}})


;; Tab Content Router


(defn tab-content
  "Render the appropriate tab content based on active tab."
  [{:keys [fx/context]}]
  (let [active-tab (fx/sub-ctx context subs/active-tab)]
    (case active-tab
      :grid {:fx/type grid-tab/grid-tab}
      :effects {:fx/type effects-tab/effects-tab}
      :projectors {:fx/type projectors-tab/projectors-tab}
      :settings {:fx/type :v-box
                 :padding 20
                 :children [{:fx/type :label
                             :text "Settings"
                             :style (str "-fx-font-size: 18; -fx-text-fill: " (css/text-primary) ";")}
                            {:fx/type :label
                             :text "Coming soon..."
                             :style (str "-fx-text-fill: " (css/text-muted) ";")}]}
      ;; Default
      {:fx/type :label :text "Unknown tab"})))


;; Tab Bar


(def main-tabs
  "Tab definitions for the main window."
  [{:id :grid :label "Grid"}
   {:id :effects :label "Effects"}
   {:id :projectors :label "Projectors"}
   {:id :settings :label "Settings"}])

(defn tab-bar
  "Tab bar component using shared styled-tab-bar."
  [{:keys [fx/context]}]
  (let [active-tab (fx/sub-ctx context subs/active-tab)]
    {:fx/type tabs/styled-tab-bar
     :tabs main-tabs
     :active-tab active-tab
     :on-tab-change {:event/type :ui/set-active-tab}}))


;; Main Content Area


(defn main-content
  "Main content area with tab content and optional preview panel.
   Preview is hidden when on the projectors tab."
  [{:keys [fx/context]}]
  (let [active-tab (fx/sub-ctx context subs/active-tab)
        show-preview? (not= active-tab :projectors)
        bg-primary (css/bg-primary)
        bg-elevated (css/bg-elevated)]
    (if show-preview?
      ;; Show split pane with preview for non-projector tabs
      {:fx/type :split-pane
       :style (str "-fx-background-color: " bg-primary ";")
       :divider-positions [0.65]
       :items [{:fx/type :border-pane
                :style (str "-fx-background-color: " bg-primary ";")
                :center {:fx/type tab-content}}
               {:fx/type :border-pane
                :style (str "-fx-background-color: " bg-elevated ";")
                :center {:fx/type preview/preview-panel}}]}
      ;; Just show tab content without preview for projectors tab
      {:fx/type :border-pane
       :style (str "-fx-background-color: " bg-primary ";")
       :center {:fx/type tab-content}})))


;; Main Layout


(defn main-layout
  "Main application layout with HeaderBar in title bar area."
  [{:keys [fx/context]}]
  {:fx/type :border-pane
   :style (str "-fx-background-color: " (css/bg-primary) ";")
   :top {:fx/type :v-box
         :children [{:fx/type title-bar/header-view}
                    {:fx/type toolbar/toolbar}
                    {:fx/type tab-bar}]}
   :center {:fx/type main-content}
   :bottom {:fx/type status-bar/status-bar}})


;; Root View


(defn root-view
  "Root view with theming and dialog management.
   
   Uses fx/ext-many to manage multiple windows (main window + dialogs).
   Stylesheets are subscribed from state for CSS hot-reload support."
  [{:keys [fx/context]}]
  (let [{:keys [title]} (fx/sub-ctx context subs/project-status)
        window-config (fx/sub-ctx context subs/window-config)
        stylesheets (fx/sub-ctx context subs/stylesheet-urls)
        effect-editor-open? (fx/sub-ctx context subs/dialog-open? :effect-chain-editor)
        cue-editor-open? (fx/sub-ctx context subs/dialog-open? :cue-chain-editor)
        add-projector-open? (fx/sub-ctx context subs/dialog-open? :add-projector-manual)]
    {:fx/type fx/ext-set-env
     :env {::layout-config layout-config}  ; For any components that need spacing/fonts
     :desc {:fx/type fx/ext-many
            :desc (filterv
                    some?
                    [;; Main application window with extended style for title bar integration
                     ;; IMPORTANT: Each component in fx/ext-many needs :fx/key to prevent
                     ;; position-based matching when dialogs open/close
                     {:fx/type :stage
                      :fx/key :main-window
                      :title title
                      :style :extended
                      :width (:width window-config 1200)
                      :height (:height window-config 800)
                      :showing true
                      :on-close-request (fn [_]
                                          ;; TODO: Check for unsaved changes
                                          (System/exit 0))
                      :scene {:fx/type :scene
                              :stylesheets stylesheets
                              :root {:fx/type main-layout}}}
                     ;; Effect chain editor dialog
                     (when effect-editor-open?
                       {:fx/type effect-chain-editor/effect-chain-editor-dialog
                        :fx/key :effect-chain-editor-dialog})
                     ;; Cue chain editor dialog
                     (when cue-editor-open?
                       {:fx/type cue-chain-editor/cue-chain-editor-dialog
                        :fx/key :cue-chain-editor-dialog})
                     ;; Add projector manually dialog
                     (when add-projector-open?
                       {:fx/type add-projector-dialog/add-projector-manual-dialog
                        :fx/key :add-projector-manual-dialog})])}}))
