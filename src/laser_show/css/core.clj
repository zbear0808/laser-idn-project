(ns laser-show.css.core
  "Central CSS aggregation for the laser show UI.
   
   This namespace:
   - Imports all CSS module namespaces
   - Provides a single function to get all stylesheet URLs
   - Exposes the theme for color constant access
   
   Usage:
   (css/all-stylesheet-urls) => vector of all CSS URLs for :stylesheets
   
   Access theme colors:
   (::theme/bg-dark css/theme) => \"#252525\""
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]
            [laser-show.css.buttons :as buttons]
            [laser-show.css.forms :as forms]
            [laser-show.css.grid-cells :as grid-cells]
            [laser-show.css.dialogs :as dialogs]
            [laser-show.css.layout :as layout]
            [laser-show.css.title-bar :as title-bar]
            [laser-show.css.effect-chain-sidebar :as effect-chain-sidebar]
            [laser-show.css.cue-chain-editor :as cue-chain-editor]))



(defn all-stylesheet-urls
  "Returns a vector of all CSS stylesheet URLs.
   Include this in your scene's :stylesheets prop.
   
   Example:
   {:fx/type :scene
    :stylesheets (css/all-stylesheet-urls)
    :root {...}}"
  []
  [(::css/url theme/theme)
   (::css/url buttons/buttons)
   (::css/url forms/forms)
   (::css/url grid-cells/grid-cells)
   (::css/url dialogs/dialogs)
   (::css/url layout/layout)
   (::css/url title-bar/menu-theme)
   (::css/url effect-chain-sidebar/effect-chain-sidebar)
   (::css/url cue-chain-editor/cue-chain-editor)])

(defn dialog-stylesheet-urls
  "Returns stylesheet URLs appropriate for dialogs.
   Includes theme, buttons, forms, grid-cells, layout, dialogs, menus,
   effect-chain-sidebar, and cue-chain-editor.
   
   Example:
   {:fx/type :scene
    :stylesheets (css/dialog-stylesheet-urls)
    :root {...}}"
  []
  [(::css/url theme/theme)
   (::css/url buttons/buttons)
   (::css/url forms/forms)
   (::css/url grid-cells/grid-cells)
   (::css/url layout/layout)
   (::css/url dialogs/dialogs)
   (::css/url title-bar/menu-theme)
   (::css/url effect-chain-sidebar/effect-chain-sidebar)
   (::css/url cue-chain-editor/cue-chain-editor)])


;; Theme color access helpers


(defn bg-darkest [] (::theme/bg-darkest theme/theme))
(defn bg-dark [] (::theme/bg-dark theme/theme))
(defn bg-medium [] (::theme/bg-medium theme/theme))
(defn bg-light [] (::theme/bg-light theme/theme))
(defn bg-hover [] (::theme/bg-hover theme/theme))

(defn text-primary [] (::theme/text-primary theme/theme))
(defn text-secondary [] (::theme/text-secondary theme/theme))
(defn text-muted [] (::theme/text-muted theme/theme))

(defn accent-primary [] (::theme/accent-primary theme/theme))
(defn accent-blue [] (::theme/accent-blue theme/theme))
(defn accent-red [] (::theme/accent-red theme/theme))
