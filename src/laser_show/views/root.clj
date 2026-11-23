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
   │   ├── tab-content (grid/vibes/perform/effects/projectors/zone-groups/settings)
   │   └── preview-panel
   ├── status-bar
   └── dialogs (via refs)"
  (:require
   [cljfx.api :as fx]
   [laser-show.events.core :as events]
   [laser-show.subs :as subs]
   [laser-show.css.core :as css]
   [laser-show.views.components.title-bar :as title-bar]
   [laser-show.views.components.preview :as preview]
   [laser-show.views.components.tabs :as tabs]
   [laser-show.views.dialogs.effect-chain-editor :as effect-chain-editor]
   [laser-show.views.dialogs.cue-chain-editor :as cue-chain-editor]
   [laser-show.views.dialogs.timeline-cue-editor :as timeline-cue-editor]
   [laser-show.views.dialogs.zone-pattern-editor :as zone-pattern-editor]
  [laser-show.views.dialogs.step-grid-editor :as step-grid-editor]
   [laser-show.views.dialogs.add-projector-manual :as add-projector-dialog]
   [laser-show.views.dialogs.zone-group-editor :as zone-group-editor]
   [laser-show.views.dialogs.cell-trigger-editor :as cell-trigger-editor]
   [laser-show.views.dialogs.unsaved-confirm :as unsaved-confirm]
   [laser-show.views.dialogs.rig-report :as rig-report]
   [laser-show.views.dialogs.confirm :as confirm]
   [laser-show.views.status-bar :as status-bar]
   [laser-show.views.tabs.effects :as effects-tab]
   [laser-show.views.tabs.grid :as grid-tab]
   [laser-show.views.tabs.perform :as perform-tab]
   [laser-show.views.tabs.projectors :as projectors-tab]
   [laser-show.views.tabs.zones :as zones-tab]
   [laser-show.views.tabs.settings :as settings-tab]
   [laser-show.views.tabs.vibes :as vibes-tab]
   [laser-show.views.toolbar :as toolbar])
  (:import [javafx.stage WindowEvent]))


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
      :vibes {:fx/type vibes-tab/vibes-tab}
      :perform {:fx/type perform-tab/perform-tab}
      :effects {:fx/type effects-tab/effects-tab}
      :projectors {:fx/type projectors-tab/projectors-tab}
      :zones {:fx/type zones-tab/zones-tab}
      :settings {:fx/type settings-tab/settings-tab}
      ;; Default
      {:fx/type :label :text "Unknown tab"})))


;; Tab Bar


(def main-tabs
  "Tab definitions for the main window."
  [{:id :grid :label "Grid"}
   {:id :vibes :label "Vibes"}
   {:id :perform :label "Perform"}
   {:id :effects :label "Effects"}
   {:id :projectors :label "Projectors"}
   {:id :zones :label "Zone Groups"}
   {:id :settings :label "Settings"}])

(defn tab-bar
  "Tab bar component using shared styled-tab-bar. The Vibes tab's label
   counts its lint's errors (subs/vibes-tab-label)."
  [{:keys [fx/context]}]
  (let [active-tab (fx/sub-ctx context subs/active-tab)
        vibes-label (fx/sub-ctx context subs/vibes-tab-label)]
    {:fx/type tabs/styled-tab-bar
     :tabs (mapv #(cond-> % (= :vibes (:id %)) (assoc :label vibes-label)) main-tabs)
     :active-tab active-tab
     :on-tab-change {:event/type :ui/set-active-tab}}))


;; Main Content Area


(defn- on-divider?
  "Is node one of split's dividers, or inside one?"
  [split node]
  (loop [n node]
    (cond
      (or (not (instance? javafx.scene.Node n)) (identical? n split)) false
      (.contains (.getStyleClass ^javafx.scene.Node n) "split-pane-divider") true
      :else (recur (.getParent ^javafx.scene.Node n)))))

(defn- remember-split
  "The split pane's event filter: the mouse letting go of the divider (it
   gets the release of the press that dragged it) is the user moving it, so
   remember where it is now, [:config :window :grid-split]. A layout pass
   that moves it, as a resize does, remembers nothing."
  [^javafx.event.Event e]
  (when (= javafx.scene.input.MouseEvent/MOUSE_RELEASED (.getEventType e))
    (let [^javafx.scene.control.SplitPane split (.getSource e)]
      (when (on-divider? split (.getTarget e))
        (events/dispatch! {:event/type :ui/set-grid-split
                           :position (first (.getDividerPositions split))})))))

(defn main-content
  "Main content area with tab content and optional preview panel.
   Preview is hidden when on the vibes, projectors, zones, or settings tabs,
   and on every tab while View > Toggle Preview has it off."
  [{:keys [fx/context]}]
  (let [active-tab (fx/sub-ctx context subs/active-tab)
        show-preview? (and (fx/sub-ctx context subs/preview-visible?)
                           (not (#{:vibes :projectors :zones :settings} active-tab)))
        bg-primary (css/bg-primary)
        bg-elevated (css/bg-elevated)]
    (if show-preview?
      ;; Show split pane with preview grid for non-projector tabs
      {:fx/type :split-pane
       :id "main-split"
       :style (str "-fx-background-color: " bg-primary ";")
       :divider-positions [(fx/sub-ctx context subs/grid-split)]
       :event-filter remember-split
       :items [{:fx/type :border-pane
                :style (str "-fx-background-color: " bg-primary ";")
                :center {:fx/type tab-content}}
               {:fx/type :border-pane
                :style (str "-fx-background-color: " bg-elevated ";")
                :center {:fx/type preview/preview-grid-panel}}]}
      ;; Just show tab content without preview for projectors tab
      {:fx/type :border-pane
       :style (str "-fx-background-color: " bg-primary ";")
       :center {:fx/type tab-content}})))


;; Main Layout


(defn- leave-text-field-on-click
  "A press outside the text field being typed in, on something that doesn't
   take the focus (a toolbar or tab button, a grid cell, empty space): the
   focus goes to the layout, so the field applies what was typed, as it
   would if the button took the focus, and Space plays again (keyboard plan
   03, D6 A)."
  [^javafx.event.Event e]
  (when (= javafx.scene.input.MouseEvent/MOUSE_PRESSED (.getEventType e))
    (let [^javafx.scene.Node layout (.getSource e)
          owner (some-> layout .getScene .getFocusOwner)]
      (when (instance? javafx.scene.control.TextInputControl owner)
        (when-not (loop [n (.getTarget e)]
                    (cond
                      (or (not (instance? javafx.scene.Node n)) (identical? n layout)) false
                      (.isFocusTraversable ^javafx.scene.Node n) true
                      :else (recur (.getParent ^javafx.scene.Node n))))
          (.requestFocus layout))))))

(defn- take-focus-from-removed-owners!
  "When the node with the focus leaves the window (its tab's content goes),
   JavaFX gives the focus to the next node that takes it, which is the BPM
   field, as the toolbar's and tab bar's buttons don't (D6 A): give it to
   layout instead, so Space still plays."
  [^javafx.scene.Node layout]
  (when-let [scene (.getScene layout)]
    (.addListener (.focusOwnerProperty scene)
                  ^javafx.beans.value.ChangeListener
                  (reify javafx.beans.value.ChangeListener
                    (changed [_ _ old _]
                      (when (and (instance? javafx.scene.Node old)
                                 (nil? (.getScene ^javafx.scene.Node old)))
                        (javafx.application.Platform/runLater #(.requestFocus layout))))))))

(defn- report-fullscreen-changes!
  "The window's fullscreen changes, whoever made them (View > Fullscreen,
   Escape, the OS's control), go to [:ui :fullscreen?]."
  [^javafx.scene.Node layout]
  (when-let [^javafx.stage.Stage stage (some-> layout .getScene .getWindow)]
    (.addListener (.fullScreenProperty stage)
                  ^javafx.beans.value.ChangeListener
                  (reify javafx.beans.value.ChangeListener
                    (changed [_ _ _ on?]
                      (events/dispatch! {:event/type :view/fullscreen-changed
                                         :fullscreen? (boolean on?)}))))))

(defn main-layout
  "Main application layout with HeaderBar in title bar area. It takes the
   focus when it is shown, so the window starts with it off the BPM field:
   the toolbar's and tab bar's buttons don't take it (keyboard plan 03,
   D6 A), and Space, R and Delete must reach the Transport and Edit menus."
  [{:keys [fx/context]}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [^javafx.scene.Node node]
                 (javafx.application.Platform/runLater
                  #(do (.requestFocus node)
                       (take-focus-from-removed-owners! node)
                       (report-fullscreen-changes! node))))
   :desc {:fx/type :border-pane
          :id "main-layout"
          :style (str "-fx-background-color: " (css/bg-primary) ";")
          :event-filter leave-text-field-on-click
          :top {:fx/type :v-box
                :children [{:fx/type title-bar/header-view}
                           {:fx/type toolbar/toolbar}
                           {:fx/type tab-bar}]}
          :center {:fx/type main-content}
          :bottom {:fx/type status-bar/status-bar}}})


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
        timeline-editor-open? (fx/sub-ctx context subs/dialog-open? :timeline-cue-editor)
        zone-pattern-editor-open? (fx/sub-ctx context subs/dialog-open? :zone-pattern-editor)
        step-grid-editor-open? (fx/sub-ctx context subs/dialog-open? :step-grid-editor)
        add-projector-open? (fx/sub-ctx context subs/dialog-open? :add-projector-manual)
        zone-group-editor-open? (fx/sub-ctx context subs/dialog-open? :zone-group-editor)
        trigger-editor-open? (fx/sub-ctx context subs/dialog-open? :trigger-editor)
        unsaved-confirm-open? (fx/sub-ctx context subs/dialog-open? :unsaved-confirm)
        rig-report-open? (fx/sub-ctx context subs/dialog-open? :rig-report)
        confirm-open? (fx/sub-ctx context subs/dialog-open? :confirm)]
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
                     ;; A pad's release may never come once the window lost
                     ;; focus: the pads' holds end then (handlers/vibes.clj)
                     :on-focused-changed {:event/type :vibe/window-focus}
                     :on-close-request (fn [^WindowEvent e]
                                         ;; Consume it: the window only closes through
                                         ;; the exit effect, after the unsaved-changes
                                         ;; check. A cljfx event map would be dispatched
                                         ;; asynchronously, too late to cancel the close.
                                         (.consume e)
                                         (events/dispatch! {:event/type :file/exit}))
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
                    ;; Timeline cue editor dialog (alternate timeline-based view)
                    (when timeline-editor-open?
                      {:fx/type timeline-cue-editor/timeline-cue-editor-dialog
                       :fx/key :timeline-cue-editor-dialog})
                    ;; A cue's zone pattern (zones plan Z5)
                    (when zone-pattern-editor-open?
                      {:fx/type zone-pattern-editor/zone-pattern-editor-dialog
                       :fx/key :zone-pattern-editor-dialog})
                    ;; A zone pattern's step grid (zones plan Z6)
                    (when step-grid-editor-open?
                      {:fx/type step-grid-editor/step-grid-editor-dialog
                       :fx/key :step-grid-editor-dialog})
                    ;; Add projector manually dialog
                    (when add-projector-open?
                      {:fx/type add-projector-dialog/add-projector-manual-dialog
                       :fx/key :add-projector-manual-dialog})
                    ;; Zone group editor dialog
                    (when zone-group-editor-open?
                      {:fx/type zone-group-editor/zone-group-editor-dialog
                       :fx/key :zone-group-editor-dialog})
                    ;; Cell trigger editor dialog
                    (when trigger-editor-open?
                      {:fx/type cell-trigger-editor/cell-trigger-editor-dialog
                       :fx/key :cell-trigger-editor-dialog})
                    ;; Unsaved-changes question (New / Open / Exit)
                    (when unsaved-confirm-open?
                      {:fx/type unsaved-confirm/unsaved-confirm-dialog
                       :fx/key :unsaved-confirm-dialog})
                    ;; A show's rig onto this one: the set-up question or the report
                    (when rig-report-open?
                      {:fx/type rig-report/rig-report-dialog
                       :fx/key :rig-report-dialog})
                    ;; The setup screens' shared confirm dialog, on top of the report
                    (when confirm-open?
                      {:fx/type confirm/confirm-dialog
                       :fx/key :confirm-dialog})])}}))
