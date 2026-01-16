(ns laser-show.views.components.title-bar
  "Application header component using javafx preview feature HeaderBar
   
   This namespace provides:
   1. HeaderBar lifecycle - JavaFX 26 component for title bar integration
   2. Menu definitions - File, Edit, Transport, View, Help menus
   3. header-view - The assembled header component for the app
   
   Usage:
   {:fx/type header/header-view}"
  (:require [cljfx.api :as fx]
            [cljfx.composite :as composite]
            [cljfx.lifecycle :as lifecycle]
            [cljfx.fx.region :as fx.region]
            [laser-show.subs :as subs]
            [clojure.java.io :as io])
  (:import [javafx.scene.layout HeaderBar]
           [javafx.scene.image Image]))


;; HeaderBar Lifecycle (JavaFX 26 Preview Feature)


(def header-bar-props
  "Property map for HeaderBar component.
   
   HeaderBar extends Region, so we inherit all Region props (style, padding, etc.)
   plus HeaderBar-specific slots: leading, center, and trailing."
  (merge
    fx.region/props
    (composite/props HeaderBar
      :leading [:setter lifecycle/dynamic]
      :center [:setter lifecycle/dynamic]
      :trailing [:setter lifecycle/dynamic])))

(def header-bar-lifecycle
  "Cljfx lifecycle for HeaderBar.
   
   Use this as the :fx/type value:
   {:fx/type header/header-bar-lifecycle
    :leading {...}
    :center {...}
    :trailing {...}}"
  (composite/describe HeaderBar
    :ctor []
    :props header-bar-props))


;; Menu Item Definitions


(defn- file-menu-items
  "Create File menu items with dynamic state."
  [context]
  (let [has-project? (fx/sub-ctx context subs/project-file)
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
      :disable true
      :on-action {:event/type :edit/undo}}
     
     {:fx/type :menu-item
      :text "Redo"
      :accelerator [:shortcut :shift :z]
      :disable true
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


;; Menu Components


(defn- file-menu
  [{:keys [fx/context]}]
  {:fx/type :menu
   :text ""
   :graphic {:fx/type :label
             :text "File"
             :style-class "menu-label"}
   :items (file-menu-items context)})

(defn- edit-menu
  [{:keys [fx/context]}]
  {:fx/type :menu
   :text ""
   :graphic {:fx/type :label
             :text "Edit"
             :style-class "menu-label"}
   :items (edit-menu-items context)})

(defn- transport-menu
  [{:keys [fx/context]}]
  {:fx/type :menu
   :text ""
   :graphic {:fx/type :label
             :text "Transport"
             :style-class "menu-label"}
   :items (transport-menu-items context)})

(defn- view-menu
  [{:keys [fx/context]}]
  {:fx/type :menu
   :text ""
   :graphic {:fx/type :label
             :text "View"
             :style-class "menu-label"}
   :items (view-menu-items context)})

(defn- help-menu
  [{:keys [fx/context]}]
  {:fx/type :menu
   :text ""
   :graphic {:fx/type :label
             :text "Help"
             :style-class "menu-label"}
   :items (help-menu-items context)})


;; Icon Loading


(def app-icon
  "Lazy-loaded application icon image.
   Loads the laser warning square icon from resources."
  (-> "laser-warning-square.png"
      (io/resource)
      (.toString)
      (Image.)))

(defn- app-icon-view
  "Application icon component - laser warning square."
  [{:keys [fx/context]}]
  {:fx/type :image-view
   :image app-icon
   :fit-width 20
   :fit-height 20
   :preserve-ratio true})


;; Menu Bar Component


(defn- menu-bar
  "Internal menu bar component with all application menus and icon."
  [{:keys [fx/context]}]
  {:fx/type :h-box
   :alignment :center-left
   :spacing 8
   :padding {:left 13}
   :style-class "menu-container"
   :children [{:fx/type app-icon-view}
              {:fx/type :menu-bar
               :use-system-menu-bar false
               :style-class "menu-bar-transparent"
               :menus [{:fx/type file-menu}
                       {:fx/type edit-menu}
                       {:fx/type transport-menu}
                       {:fx/type view-menu}
                       {:fx/type help-menu}]}]})


;; Title Component


(defn- window-title
  "Window title component showing project name and dirty indicator."
  [{:keys [fx/context]}]
  (let [{:keys [title]} (fx/sub-ctx context subs/project-status)]
    {:fx/type :label
     :text title
     :style-class "window-title"}))


;; Public API


(defn header-view
  "Application header component.
   
   Uses JavaFX 26 HeaderBar to integrate the menu bar into the window title bar.
   Requires :style :extended on the Stage for proper title bar integration.
   
   Props:
   - :fx/context - cljfx context (passed automatically by renderer)"
  [{:keys [fx/context]}]
  {:fx/type header-bar-lifecycle
   :style-class "header-bar"
   :leading {:fx/type menu-bar}
   :center {:fx/type window-title}})
