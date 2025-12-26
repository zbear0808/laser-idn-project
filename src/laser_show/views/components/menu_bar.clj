(ns laser-show.views.components.menu-bar
  "Menu bar component using cljfx declarative API.
   
   This component creates a standard menu bar with File, Edit, Transport,
   View, and Help menus. It integrates with the application's event system
   and can reflect dynamic state through subscriptions.
   
   Usage:
   {:fx/type menu-bar/menu-bar-view}"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]))

;; ============================================================================
;; Menu Item Definitions
;; ============================================================================

(defn- file-menu-items
  "Create File menu items with dynamic state."
  [context]
  (let [has-project? (fx/sub-ctx context subs/project-folder)
        dirty? (fx/sub-ctx context subs/project-dirty?)]
    [{:fx/type :menu-item
      :text "New Project"
      :accelerator [:shortcut :n]
      :on-action {:event/type :file/new-project}}
     
     {:fx/type :menu-item
      :text "Open..."
      :accelerator [:shortcut :o]
      :on-action {:event/type :file/open}}
     
     {:fx/type :separator-menu-item}
     
     {:fx/type :menu-item
      :text "Save"
      :accelerator [:shortcut :s]
      :disable (not dirty?)
      :on-action {:event/type :file/save}}
     
     {:fx/type :menu-item
      :text "Save As..."
      :accelerator [:shortcut :shift :s]
      :disable (not has-project?)
      :on-action {:event/type :file/save-as}}
     
     {:fx/type :separator-menu-item}
     
     {:fx/type :menu-item
      :text "Export..."
      :disable (not has-project?)
      :on-action {:event/type :file/export}}
     
     {:fx/type :separator-menu-item}
     
     {:fx/type :menu-item
      :text "Exit"
      :on-action {:event/type :file/exit}}]))

(defn- edit-menu-items
  "Create Edit menu items."
  [context]
  (let [clipboard (fx/sub-ctx context subs/clipboard)
        has-clipboard? (some? clipboard)]
    [{:fx/type :menu-item
      :text "Undo"
      :accelerator [:shortcut :z]
      :disable true  ; TODO: Implement undo/redo
      :on-action {:event/type :edit/undo}}
     
     {:fx/type :menu-item
      :text "Redo"
      :accelerator [:shortcut :shift :z]
      :disable true  ; TODO: Implement undo/redo
      :on-action {:event/type :edit/redo}}
     
     {:fx/type :separator-menu-item}
     
     {:fx/type :menu-item
      :text "Copy"
      :accelerator [:shortcut :c]
      :on-action {:event/type :edit/copy}}
     
     {:fx/type :menu-item
      :text "Paste"
      :accelerator [:shortcut :v]
      :disable (not has-clipboard?)
      :on-action {:event/type :edit/paste}}
     
     {:fx/type :separator-menu-item}
     
     {:fx/type :menu-item
      :text "Clear Cell"
      :accelerator [:delete]
      :on-action {:event/type :edit/clear-cell}}]))

(defn- transport-menu-items
  "Create Transport menu items."
  [context]
  (let [playing? (fx/sub-ctx context subs/playing?)]
    [{:fx/type :menu-item
      :text (if playing? "Pause" "Play")
      :accelerator [:space]
      :on-action {:event/type (if playing? :transport/stop :transport/play)}}
     
     {:fx/type :menu-item
      :text "Stop"
      :accelerator [:escape]
      :disable (not playing?)
      :on-action {:event/type :transport/stop}}
     
     {:fx/type :menu-item
      :text "Retrigger"
      :accelerator [:r]
      :disable (not playing?)
      :on-action {:event/type :transport/retrigger}}]))

(defn- view-menu-items
  "Create View menu items."
  [_context]
  [{:fx/type :menu-item
    :text "Toggle Preview"
    :accelerator [:shortcut :p]
    :on-action {:event/type :view/toggle-preview}}
   
   {:fx/type :menu-item
    :text "Fullscreen"
    :accelerator [:f11]
    :on-action {:event/type :view/fullscreen}}])

(defn- help-menu-items
  "Create Help menu items."
  [_context]
  [{:fx/type :menu-item
    :text "Documentation"
    :on-action {:event/type :help/documentation}}
   
   {:fx/type :separator-menu-item}
   
   {:fx/type :menu-item
    :text "About"
    :on-action {:event/type :help/about}}
   
   {:fx/type :menu-item
    :text "Check for Updates"
    :on-action {:event/type :help/check-updates}}])

;; ============================================================================
;; Menu Definitions
;; ============================================================================

(defn- file-menu
  "File menu component."
  [{:keys [fx/context]}]
  {:fx/type :menu
   :text "File"
   :items (file-menu-items context)})

(defn- edit-menu
  "Edit menu component."
  [{:keys [fx/context]}]
  {:fx/type :menu
   :text "Edit"
   :items (edit-menu-items context)})

(defn- transport-menu
  "Transport menu component."
  [{:keys [fx/context]}]
  {:fx/type :menu
   :text "Transport"
   :items (transport-menu-items context)})

(defn- view-menu
  "View menu component."
  [{:keys [fx/context]}]
  {:fx/type :menu
   :text "View"
   :items (view-menu-items context)})

(defn- help-menu
  "Help menu component."
  [{:keys [fx/context]}]
  {:fx/type :menu
   :text "Help"
   :items (help-menu-items context)})

;; ============================================================================
;; Public API
;; ============================================================================

(defn menu-bar-view
  "Main menu bar component.
   
   This creates a MenuBar with all application menus. The menu bar
   is styled to integrate with the dark theme and reflects application
   state through subscriptions.
   
   Props:
   - :fx/context - cljfx context (passed automatically by renderer)"
  [{:keys [fx/context]}]
  {:fx/type :menu-bar
   :use-system-menu-bar false
   :style (str "-fx-background-color: #2D2D2D; "
               "-fx-border-color: #1E1E1E; "
               "-fx-border-width: 0 0 1 0; "
               "-fx-padding: 0;")
   :menus [{:fx/type file-menu}
           {:fx/type edit-menu}
           {:fx/type transport-menu}
           {:fx/type view-menu}
           {:fx/type help-menu}]})
