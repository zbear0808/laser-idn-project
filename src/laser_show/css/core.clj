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
            [laser-show.css.buttons :as buttons]
            [laser-show.css.forms :as forms]
            [laser-show.css.grid-cells :as grid-cells]
            [laser-show.css.layout :as layout]
            [laser-show.css.title-bar :as title-bar]
            [laser-show.css.cue-chain-editor :as cue-chain-editor]
            [laser-show.css.list :as list]))


;; =============================================================================
;; Stylesheet URL Functions
;; =============================================================================

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
   (::css/url layout/layout)
   (::css/url title-bar/menu-theme)
   (::css/url cue-chain-editor/cue-chain-editor)
   (::css/url list/list)])

(defn dialog-stylesheet-urls
  "Returns stylesheet URLs appropriate for dialogs.
   Includes theme, buttons, forms, grid-cells, layout, menus,
   cue-chain-editor, and list.
   
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
   (::css/url title-bar/menu-theme)
   (::css/url cue-chain-editor/cue-chain-editor)
   (::css/url list/list)])


;; =============================================================================
;; Background Color Accessors
;; =============================================================================

;; Semantic names (preferred)
(defn bg-primary 
  "Root/window background color. Darkest."
  [] (:bg-primary theme/semantic-colors))

(defn bg-surface
  "Panel background color."
  [] (:bg-surface theme/semantic-colors))

(defn bg-elevated
  "Elevated surfaces (cards, dialogs)."
  [] (:bg-elevated theme/semantic-colors))

(defn bg-interactive
  "Interactive element default state."
  [] (:bg-interactive theme/semantic-colors))

(defn bg-hover
  "Hover state background."
  [] (:bg-hover theme/semantic-colors))

(defn bg-active
  "Active/pressed state background."
  [] (:bg-active theme/semantic-colors))

;; Legacy names (for backward compatibility)
(defn bg-darkest 
  "Alias for bg-primary. Prefer bg-primary."
  [] (bg-primary))

(defn bg-dark 
  "Alias for bg-surface. Prefer bg-surface."
  [] (bg-surface))

(defn bg-medium 
  "Alias for bg-elevated. Prefer bg-elevated."
  [] (bg-elevated))

(defn bg-light 
  "Alias for bg-interactive. Prefer bg-interactive."
  [] (bg-interactive))


;; =============================================================================
;; Text Color Accessors
;; =============================================================================

(defn text-primary
  "Primary text color for main content."
  [] (:text-primary theme/semantic-colors))

(defn text-secondary
  "Secondary text color for less emphasized content."
  [] (:text-secondary theme/semantic-colors))

(defn text-muted
  "Muted text color for hints and placeholder text."
  [] (:text-muted theme/semantic-colors))

(defn text-disabled
  "Disabled text color."
  [] (:text-disabled theme/semantic-colors))


;; =============================================================================
;; Border Color Accessors
;; =============================================================================

(defn border-subtle
  "Subtle border color."
  [] (:border-subtle theme/semantic-colors))

(defn border-default
  "Default border color."
  [] (:border-default theme/semantic-colors))


;; =============================================================================
;; Accent Color Accessors (Semantic)
;; =============================================================================

(defn accent-success
  "Success/positive action color (green)."
  [] (:accent-success theme/semantic-colors))

(defn accent-info
  "Info/neutral action color (blue)."
  [] (:accent-info theme/semantic-colors))

(defn accent-warning
  "Warning color (orange)."
  [] (:accent-warning theme/semantic-colors))

(defn accent-danger
  "Danger/error color (red)."
  [] (:accent-danger theme/semantic-colors))

;; State variants
(defn accent-success-hover
  "Success color hover state."
  [] (:accent-success-hover theme/computed-colors))

(defn accent-danger-hover
  "Danger color hover state."
  [] (:accent-danger-hover theme/computed-colors))

;; Legacy names (for backward compatibility)
(defn accent-primary 
  "Alias for accent-success. Prefer accent-success."
  [] (accent-success))

(defn accent-blue 
  "Alias for accent-info. Prefer accent-info."
  [] (accent-info))

(defn accent-red 
  "Alias for accent-danger. Prefer accent-danger."
  [] (accent-danger))

(defn accent-orange
  "Alias for accent-warning. Prefer accent-warning."
  [] (accent-warning))


;; =============================================================================
;; Selection Color Accessors
;; =============================================================================

(defn selection-bg
  "Selected item background color."
  [] (:selection-bg theme/semantic-colors))

(defn selection-hover
  "Selected item hover state."
  [] (:selection-hover theme/computed-colors))


;; =============================================================================
;; Drop Target Color Accessors
;; =============================================================================

(defn drop-target-bg
  "Drag-and-drop target background color."
  [] (:drop-target-bg theme/computed-colors))

(defn drop-target-border
  "Drag-and-drop target border color."
  [] (:drop-target-border theme/computed-colors))


;; =============================================================================
;; Status Color Accessors
;; =============================================================================

(defn status-color
  "Get color for a given status.
   
   Parameters:
     status - One of :online, :connected, :offline, :connecting, :occupied, :unknown
   
   Example:
     (status-color :online) => \"#4CAF50\""
  [status]
  (get theme/status-colors status (:unknown theme/status-colors)))

(defn online-color [] (:online theme/status-colors))
(defn offline-color [] (:offline theme/status-colors))
(defn connecting-color [] (:connecting theme/status-colors))


;; =============================================================================
;; Category Color Accessors
;; =============================================================================

(defn category-color
  "Get color for a given category/depth.
   
   Parameters:
     category - One of :depth-0, :depth-1, :depth-2, :depth-3,
                or :geometric, :wave, :beam, :abstract
   
   Example:
     (category-color :depth-0) => \"#4A7B9D\""
  [category]
  (get theme/category-colors category (:depth-0 theme/category-colors)))

(defn depth-color
  "Get color for nesting depth (0-3).
   
   Example:
     (depth-color 0) => \"#4A7B9D\""
  [depth]
  (category-color (keyword (str "depth-" (min 3 depth)))))


;; =============================================================================
;; Channel Color Accessors
;; =============================================================================

(defn channel-color
  "Get color for RGB channel visualization.
   
   Parameters:
     channel - One of :r, :g, :b
   
   Example:
     (channel-color :r) => \"#FF5555\""
  [channel]
  (get theme/channel-colors channel "#FFFFFF"))


;; =============================================================================
;; Cell Content Color Accessors
;; =============================================================================

(defn cell-content
  "Color for cells with content (non-effects)."
  [] (:cell-content theme/computed-colors))

(defn cell-content-hover
  "Hover state for cells with content."
  [] (:cell-content-hover theme/computed-colors))

(defn effects-content
  "Color for effects cells."
  [] (:effects-content theme/computed-colors))

(defn effects-content-hover
  "Hover state for effects cells."
  [] (:effects-content-hover theme/computed-colors))


;; =============================================================================
;; Direct Access to Color Maps (for advanced usage)
;; =============================================================================

(def base-colors
  "Direct access to base color palette."
  theme/base-colors)

(def semantic-colors
  "Direct access to semantic color map."
  theme/semantic-colors)

(def computed-colors
  "Direct access to computed color variants."
  theme/computed-colors)

(def status-colors
  "Direct access to status colors."
  theme/status-colors)

(def category-colors
  "Direct access to category colors."
  theme/category-colors)

(def all-colors
  "Direct access to all colors merged."
  theme/all-colors)
