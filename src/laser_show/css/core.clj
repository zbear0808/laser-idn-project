(ns laser-show.css.core
  "Central CSS aggregation for the laser show UI.
   
   This namespace:
   - Imports all CSS module namespaces
   - Provides a single function to get all stylesheet URLs
   - Exposes accessor functions for all theme colors
   
   Usage:
   (css/all-stylesheet-urls) => vector of all CSS URLs for :stylesheets
   
   Access theme colors via accessor functions:
   (css/bg-primary)      => \"#1E1E1E\"
   (css/accent-success)  => \"#4CAF50\"
   (css/status-color :online) => \"#4CAF50\""
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]
            [laser-show.css.typography :as typography]
            [laser-show.css.components :as components]
            [laser-show.css.buttons :as buttons]
            [laser-show.css.forms :as forms]
            [laser-show.css.grid-cells :as grid-cells]
            [laser-show.css.layout :as layout]
            [laser-show.css.title-bar :as title-bar]
            [laser-show.css.cue-chain-editor :as cue-chain-editor]
            [laser-show.css.list :as list]
            [laser-show.css.visual-editors :as visual-editors]
            [laser-show.css.icons :as icons]))



;; Stylesheet URL Functions


(defn all-stylesheet-urls
  "Returns a vector of all CSS stylesheet URLs.
   Include this in your scene's :stylesheets prop.
   
   Example:
   {:fx/type :scene
    :stylesheets (css/all-stylesheet-urls)
    :root {...}}"
  []
  [(::css/url theme/theme)
   (::css/url typography/typography)
   (::css/url components/components)
   (::css/url buttons/buttons)
   (::css/url forms/forms)
   (::css/url grid-cells/grid-cells)
   (::css/url layout/layout)
   (::css/url title-bar/menu-theme)
   (::css/url cue-chain-editor/cue-chain-editor)
   (::css/url list/list)
   (::css/url visual-editors/visual-editors)
   (::css/url icons/styles)])

(defn dialog-stylesheet-urls
  "Returns stylesheet URLs appropriate for dialogs.
   Includes theme, typography, components, buttons, forms, grid-cells,
   layout, menus, cue-chain-editor, list, and visual-editors.
   
   Example:
   {:fx/type :scene
    :stylesheets (css/dialog-stylesheet-urls)
    :root {...}}"
  []
  [(::css/url theme/theme)
   (::css/url typography/typography)
   (::css/url components/components)
   (::css/url buttons/buttons)
   (::css/url forms/forms)
   (::css/url grid-cells/grid-cells)
   (::css/url layout/layout)
   (::css/url title-bar/menu-theme)
   (::css/url cue-chain-editor/cue-chain-editor)
   (::css/url list/list)
   (::css/url visual-editors/visual-editors)
   (::css/url icons/styles)])



(defn bg-primary
  "Root/window background color. Darkest."
  [] (:bg-primary theme/semantic-colors))

(defn bg-surface
  "Panel background color."
  [] (:bg-surface theme/semantic-colors))

(defn bg-elevated
  "Elevated surfaces (cards, dialogs)."
  [] (:bg-elevated theme/semantic-colors))



(defn text-primary
  "Primary text color for main content."
  [] (:text-primary theme/semantic-colors))

(defn text-secondary
  "Secondary text color for less emphasized content."
  [] (:text-secondary theme/semantic-colors))

(defn text-muted
  "Muted text color for hints and placeholder text."
  [] (:text-muted theme/semantic-colors))


(defn border-default
  "Default border color."
  [] (:border-default theme/semantic-colors))


(defn accent-success
  "Success/positive action color (green)."
  [] (:accent-success theme/semantic-colors))

(defn accent-warning
  "Warning color (orange)."
  [] (:accent-warning theme/semantic-colors))

(defn selection-bg
  "Selected item background color."
  [] (:selection-bg theme/semantic-colors))

(defn green
  "Success/action color."
  [] (:green theme/base-colors))

(defn blue
  "Selection/active state color."
  [] (:blue theme/base-colors))

(defn border
  "Border and hover state color."
  [] (:border theme/base-colors))

(defn orange
  "Warning accent color."
  [] (:orange theme/base-colors))

(defn red
  "Danger/error color."
  [] (:red theme/base-colors))
