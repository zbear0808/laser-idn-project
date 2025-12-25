(ns laser-show.ui-fx.styles
  "Styling constants and CSS for the cljfx UI.
   Dark theme matching the existing FlatLaf look.")

;; ============================================================================
;; Color Palette
;; ============================================================================

(def colors
  "Color definitions for the dark theme."
  {:background       "#1e1e1e"
   :surface          "#252526"
   :surface-light    "#2d2d30"
   :surface-hover    "#3c3c3c"
   :border           "#3c3c3c"
   :border-light     "#4a4a4a"
   
   :text-primary     "#cccccc"
   :text-secondary   "#808080"
   :text-disabled    "#5a5a5a"
   
   :accent           "#007acc"
   :accent-hover     "#1c97ea"
   :accent-active    "#0e639c"
   
   :success          "#4ec9b0"
   :warning          "#dcdcaa"
   :error            "#f14c4c"
   
   ;; Cell states
   :cell-empty       "#2d2d30"
   :cell-assigned    "#3c5c3c"
   :cell-active      "#4a9f4a"
   :cell-selected    "#264f78"
   :cell-hover       "#3c3c3c"
   
   ;; Category colors (for presets)
   :category-geometric  "#4a90d9"
   :category-organic    "#7c4dff"
   :category-beam       "#ff6b6b"
   :category-text       "#ffd93d"
   :category-effect     "#6bcb77"
   
   ;; Preview
   :preview-background  "#000000"
   :preview-grid        "#1a1a1a"})

;; ============================================================================
;; Dimensions
;; ============================================================================

(def dimensions
  "Size constants for UI elements."
  {:cell-width         80
   :cell-height        60
   :cell-gap           2
   :cell-border-radius 4
   
   :preview-width      350
   :preview-height     350
   
   :toolbar-height     40
   :status-bar-height  24
   
   :button-height      28
   :button-padding     8
   
   :font-size-small    10
   :font-size-normal   12
   :font-size-large    14
   :font-size-title    18})

;; ============================================================================
;; Menu Bar Dark Theme CSS
;; ============================================================================

(def menu-bar-css
  "CSS for dark-themed menu bar with white text."
  (str
   ;; Menu bar itself
   ".menu-bar {"
   "  -fx-background-color: transparent;"
   "  -fx-padding: 0;"
   "}"
   
   ;; Menu labels (File, Edit, etc.) - multiple selectors for coverage
   ".menu-bar .label {"
   "  -fx-text-fill: #e0e0e0;"
   "}"
   ".menu-bar > .container > .menu-button > .label {"
   "  -fx-text-fill: #e0e0e0;"
   "}"
   ".menu-button .label {"
   "  -fx-text-fill: #e0e0e0;"
   "}"
   ".menu .label {"
   "  -fx-text-fill: #e0e0e0;"
   "}"
   
   ;; Menu button (the clickable area)
   ".menu-bar > .container > .menu-button {"
   "  -fx-background-color: transparent;"
   "}"
   ".menu-bar > .container > .menu-button:hover {"
   "  -fx-background-color: " (:surface-hover colors) ";"
   "}"
   ".menu-bar > .container > .menu-button:showing {"
   "  -fx-background-color: " (:surface-hover colors) ";"
   "}"
   
   ;; Context menus (dropdown menus) - both .context-menu and .popup
   ".context-menu {"
   "  -fx-background-color: " (:surface colors) ";"
   "  -fx-background-radius: 4;"
   "  -fx-padding: 4;"
   "  -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 8, 0, 0, 2);"
   "}"
   
   ;; Popup styling for dropdown menus
   ".popup {"
   "  -fx-background-color: " (:surface colors) ";"
   "}"
   
   ;; Menu items in dropdowns - all states
   ".menu-item {"
   "  -fx-background-color: transparent;"
   "  -fx-padding: 4 8;"
   "}"
   ".menu-item .label {"
   "  -fx-text-fill: " (:text-primary colors) ";"
   "}"
   ".menu-item > .label {"
   "  -fx-text-fill: " (:text-primary colors) ";"
   "}"
   ".menu-item:hover {"
   "  -fx-background-color: " (:accent colors) ";"
   "}"
   ".menu-item:hover .label {"
   "  -fx-text-fill: white;"
   "}"
   ".menu-item:hover > .label {"
   "  -fx-text-fill: white;"
   "}"
   ".menu-item:focused {"
   "  -fx-background-color: " (:accent colors) ";"
   "}"
   ".menu-item:focused .label {"
   "  -fx-text-fill: white;"
   "}"
   ".menu-item:focused > .label {"
   "  -fx-text-fill: white;"
   "}"
   
   ;; Accelerator text (keyboard shortcuts)
   ".menu-item .accelerator-text {"
   "  -fx-fill: " (:text-secondary colors) ";"
   "}"
   ".menu-item > .accelerator-text {"
   "  -fx-fill: " (:text-secondary colors) ";"
   "}"
   ".accelerator-text {"
   "  -fx-fill: " (:text-secondary colors) ";"
   "}"
   
   ;; Separator in menus
   ".separator-menu-item {"
   "  -fx-padding: 4 0;"
   "}"
   ".separator-menu-item > .line {"
   "  -fx-border-color: " (:border colors) ";"
   "  -fx-border-width: 1 0 0 0;"
   "}"
   
   ;; Submenu arrow
   ".menu-item .arrow {"
   "  -fx-background-color: " (:text-secondary colors) ";"
   "}"
   ".menu-item:hover .arrow {"
   "  -fx-background-color: white;"
   "}"))

;; ============================================================================
;; Root CSS (applied to entire scene)
;; ============================================================================

(def root-css
  "Root CSS styles for the entire cljfx UI."
  (str
   menu-bar-css
   ;; Root dark theme settings
   ".root {"
   "  -fx-font-family: 'Segoe UI', 'SF Pro Text', 'Helvetica Neue', sans-serif;"
   "  -fx-font-size: 12px;"
   "  -fx-base: " (:background colors) ";"
   "  -fx-background: " (:background colors) ";"
   "  -fx-control-inner-background: " (:surface colors) ";"
   "  -fx-control-inner-background-alt: " (:surface-light colors) ";"
   "  -fx-text-fill: " (:text-primary colors) ";"
   "  -fx-accent: " (:accent colors) ";"
   "  -fx-focus-color: " (:accent colors) ";"
   "  -fx-faint-focus-color: transparent;"
   "  -fx-selection-bar: " (:accent colors) ";"
   "  -fx-selection-bar-non-focused: " (:surface-hover colors) ";"
   "  -fx-dark-text-color: white;"
   "  -fx-mid-text-color: " (:text-primary colors) ";"
   "  -fx-light-text-color: " (:text-primary colors) ";"
   "}"
   
   ".label {"
   "  -fx-text-fill: " (:text-primary colors) ";"
   "}"
   
   ".button {"
   "  -fx-background-color: " (:surface-light colors) ";"
   "  -fx-text-fill: " (:text-primary colors) ";"
   "  -fx-border-color: " (:border colors) ";"
   "  -fx-border-radius: 3;"
   "  -fx-background-radius: 3;"
   "  -fx-padding: 4 12;"
   "  -fx-cursor: hand;"
   "}"
   
   ".button:hover {"
   "  -fx-background-color: " (:surface-hover colors) ";"
   "}"
   
   ".button:pressed {"
   "  -fx-background-color: " (:accent-active colors) ";"
   "}"
   
   ".button.primary {"
   "  -fx-background-color: " (:accent colors) ";"
   "  -fx-text-fill: white;"
   "}"
   
   ".button.primary:hover {"
   "  -fx-background-color: " (:accent-hover colors) ";"
   "}"
   
   ".scroll-pane {"
   "  -fx-background-color: " (:background colors) ";"
   "  -fx-background: " (:background colors) ";"
   "}"
   
   ".scroll-pane > .viewport {"
   "  -fx-background-color: " (:background colors) ";"
   "}"
   
   ".split-pane {"
   "  -fx-background-color: " (:background colors) ";"
   "}"
   
   ".split-pane > .split-pane-divider {"
   "  -fx-background-color: " (:border colors) ";"
   "  -fx-padding: 0 2;"
   "}"
   
   ".menu-bar {"
   "  -fx-background-color: " (:surface colors) ";"
   "}"
   
   ".menu-bar .menu {"
   "  -fx-background-color: transparent;"
   "}"
   
   ".menu-bar .menu .label {"
   "  -fx-text-fill: " (:text-primary colors) ";"
   "}"
   
   ".context-menu {"
   "  -fx-background-color: " (:surface colors) ";"
   "  -fx-border-color: " (:border colors) ";"
   "}"
   
   ".menu-item {"
   "  -fx-background-color: transparent;"
   "}"
   
   ".menu-item:hover {"
   "  -fx-background-color: " (:accent colors) ";"
   "}"
   
   ".menu-item .label {"
   "  -fx-text-fill: " (:text-primary colors) ";"
   "}"
   
   ".text-field {"
   "  -fx-background-color: " (:surface colors) ";"
   "  -fx-text-fill: " (:text-primary colors) ";"
   "  -fx-border-color: " (:border colors) ";"
   "  -fx-border-radius: 3;"
   "  -fx-background-radius: 3;"
   "}"
   
   ".text-field:focused {"
   "  -fx-border-color: " (:accent colors) ";"
   "}"
   
   ".combo-box {"
   "  -fx-background-color: " (:surface colors) ";"
   "  -fx-border-color: " (:border colors) ";"
   "}"
   
   ".combo-box .list-cell {"
   "  -fx-background-color: " (:surface colors) ";"
   "  -fx-text-fill: " (:text-primary colors) ";"
   "}"
   
   ".combo-box-popup .list-view {"
   "  -fx-background-color: " (:surface colors) ";"
   "}"
   
   ".combo-box-popup .list-cell {"
   "  -fx-background-color: " (:surface colors) ";"
   "  -fx-text-fill: " (:text-primary colors) ";"
   "}"
   
   ".combo-box-popup .list-cell:hover {"
   "  -fx-background-color: " (:accent colors) ";"
   "}"
   
   ".slider {"
   "  -fx-control-inner-background: " (:surface-light colors) ";"
   "}"
   
   ".slider .track {"
   "  -fx-background-color: " (:surface-light colors) ";"
   "}"
   
   ".slider .thumb {"
   "  -fx-background-color: " (:accent colors) ";"
   "}"
   
   ".titled-pane {"
   "  -fx-text-fill: " (:text-primary colors) ";"
   "}"
   
   ".titled-pane > .title {"
   "  -fx-background-color: " (:surface colors) ";"
   "}"
   
   ".titled-pane > .content {"
   "  -fx-background-color: " (:surface-light colors) ";"
   "  -fx-border-color: " (:border colors) ";"
   "}"
   
   ".tooltip {"
   "  -fx-background-color: " (:surface colors) ";"
   "  -fx-text-fill: " (:text-primary colors) ";"
   "  -fx-border-color: " (:border colors) ";"
   "}"
   
   ;; Scrollbar dark styling
   ".scroll-bar {"
   "  -fx-background-color: " (:background colors) ";"
   "}"
   
   ".scroll-bar .track {"
   "  -fx-background-color: " (:surface colors) ";"
   "  -fx-background-radius: 0;"
   "}"
   
   ".scroll-bar .thumb {"
   "  -fx-background-color: " (:surface-hover colors) ";"
   "  -fx-background-radius: 4;"
   "}"
   
   ".scroll-bar .thumb:hover {"
   "  -fx-background-color: " (:border-light colors) ";"
   "}"
   
   ".scroll-bar .increment-button, .scroll-bar .decrement-button {"
   "  -fx-background-color: " (:surface colors) ";"
   "}"
   
   ".scroll-bar .increment-arrow, .scroll-bar .decrement-arrow {"
   "  -fx-background-color: " (:text-secondary colors) ";"
   "}"
   
   ;; Tab pane dark styling
   ".tab-pane {"
   "  -fx-background-color: " (:background colors) ";"
   "}"
   
   ".tab-pane .tab-header-area {"
   "  -fx-background-color: " (:surface colors) ";"
   "}"
   
   ".tab-pane .tab {"
   "  -fx-background-color: " (:surface colors) ";"
   "  -fx-border-color: " (:border colors) ";"
   "}"
   
   ".tab-pane .tab:selected {"
   "  -fx-background-color: " (:surface-light colors) ";"
   "}"
   
   ".tab-pane .tab .tab-label {"
   "  -fx-text-fill: " (:text-primary colors) ";"
   "}"
   
   ;; Check box dark styling
   ".check-box {"
   "  -fx-text-fill: " (:text-primary colors) ";"
   "}"
   
   ".check-box .box {"
   "  -fx-background-color: " (:surface colors) ";"
   "  -fx-border-color: " (:border colors) ";"
   "}"
   
   ".check-box:selected .box {"
   "  -fx-background-color: " (:accent colors) ";"
   "}"
   
   ".check-box .mark {"
   "  -fx-background-color: white;"
   "}"
   
   ;; Spinner dark styling
   ".spinner {"
   "  -fx-background-color: " (:surface colors) ";"
   "  -fx-border-color: " (:border colors) ";"
   "}"
   
   ".spinner .text-field {"
   "  -fx-background-color: " (:surface colors) ";"
   "}"
   
   ".spinner .increment-arrow-button, .spinner .decrement-arrow-button {"
   "  -fx-background-color: " (:surface-light colors) ";"
   "}"
   
   ;; List view dark styling  
   ".list-view {"
   "  -fx-background-color: " (:surface colors) ";"
   "}"
   
   ".list-view .list-cell {"
   "  -fx-background-color: " (:surface colors) ";"
   "  -fx-text-fill: " (:text-primary colors) ";"
   "}"
   
   ".list-view .list-cell:hover {"
   "  -fx-background-color: " (:surface-hover colors) ";"
   "}"
   
   ".list-view .list-cell:selected {"
   "  -fx-background-color: " (:accent colors) ";"
   "}"
   
   ;; Table view dark styling
   ".table-view {"
   "  -fx-background-color: " (:surface colors) ";"
   "}"
   
   ".table-view .column-header {"
   "  -fx-background-color: " (:surface colors) ";"
   "}"
   
   ".table-view .column-header .label {"
   "  -fx-text-fill: " (:text-primary colors) ";"
   "}"
   
   ".table-row-cell {"
   "  -fx-background-color: " (:surface colors) ";"
   "  -fx-text-fill: " (:text-primary colors) ";"
   "}"
   
   ".table-row-cell:selected {"
   "  -fx-background-color: " (:accent colors) ";"
   "}"
   
   ;; Progress bar dark styling
   ".progress-bar {"
   "  -fx-background-color: " (:surface-light colors) ";"
   "}"
   
   ".progress-bar .track {"
   "  -fx-background-color: " (:surface-light colors) ";"
   "}"
   
   ".progress-bar .bar {"
   "  -fx-background-color: " (:accent colors) ";"
   "}"
   
   ;; Separator dark styling
   ".separator {"
   "  -fx-background-color: " (:border colors) ";"
   "}"
   
   ".separator .line {"
   "  -fx-border-color: " (:border colors) ";"
   "}"
   
   ;; Dialog/Modal dark styling
   ".dialog-pane {"
   "  -fx-background-color: " (:background colors) ";"
   "}"
   
   ".dialog-pane .content {"
   "  -fx-background-color: " (:background colors) ";"
   "}"
   
   ".dialog-pane .header-panel {"
   "  -fx-background-color: " (:surface colors) ";"
   "}"
   
   ".dialog-pane .button-bar {"
   "  -fx-background-color: " (:surface colors) ";"
   "}"
   
   ;; Hyperlink dark styling
   ".hyperlink {"
   "  -fx-text-fill: " (:accent colors) ";"
   "}"
   
   ".hyperlink:visited {"
   "  -fx-text-fill: " (:accent-hover colors) ";"
   "}"
   
   ;; Grid cell styles
   ".grid-cell {"
   "  -fx-background-color: " (:cell-empty colors) ";"
   "  -fx-border-color: " (:border colors) ";"
   "  -fx-border-width: 1;"
   "  -fx-border-radius: 4;"
   "  -fx-background-radius: 4;"
   "  -fx-cursor: hand;"
   "}"
   
   ".grid-cell:hover {"
   "  -fx-border-color: " (:border-light colors) ";"
   "  -fx-border-width: 2;"
   "}"
   
   ".grid-cell.has-content {"
   "  -fx-background-color: " (:cell-assigned colors) ";"
   "}"
   
   ".grid-cell.active {"
   "  -fx-background-color: " (:cell-active colors) ";"
   "  -fx-border-color: " (:success colors) ";"
   "  -fx-border-width: 2;"
   "}"
   
   ".grid-cell.selected {"
   "  -fx-border-color: " (:accent colors) ";"
   "  -fx-border-width: 2;"
   "}"
   
   ".grid-cell .label {"
   "  -fx-text-fill: white;"
   "  -fx-font-size: 10px;"
   "}"
   
   ;; Toolbar styles
   ".toolbar {"
   "  -fx-background-color: " (:surface colors) ";"
   "  -fx-padding: 4 8;"
   "  -fx-spacing: 8;"
   "}"
   
   ".toolbar .separator {"
   "  -fx-background-color: " (:border colors) ";"
   "}"
   
   ;; Status bar styles
   ".status-bar {"
   "  -fx-background-color: " (:surface colors) ";"
   "  -fx-padding: 2 8;"
   "  -fx-font-size: 11px;"
   "}"
   
   ".status-bar .label {"
   "  -fx-text-fill: " (:text-secondary colors) ";"
   "}"
   
   ".status-bar .label.connected {"
   "  -fx-text-fill: " (:success colors) ";"
   "}"
   
   ".status-bar .label.disconnected {"
   "  -fx-text-fill: " (:text-secondary colors) ";"
   "}"
   
   ;; Preview panel styles
   ".preview-panel {"
   "  -fx-background-color: " (:preview-background colors) ";"
   "  -fx-border-color: " (:border colors) ";"
   "  -fx-border-width: 1;"
   "}"
   
   ;; Effect cell styles
   ".effect-cell {"
   "  -fx-background-color: " (:surface colors) ";"
   "  -fx-border-color: " (:border colors) ";"
   "  -fx-border-width: 1;"
   "  -fx-border-radius: 4;"
   "  -fx-background-radius: 4;"
   "}"
   
   ".effect-cell.has-effects {"
   "  -fx-background-color: " (:category-effect colors) ";"
   "}"
   
   ".effect-cell.active {"
   "  -fx-border-color: " (:success colors) ";"
   "  -fx-border-width: 2;"
   "}"
   
   ;; Preset button styles
   ".preset-button {"
   "  -fx-background-radius: 4;"
   "  -fx-border-radius: 4;"
   "  -fx-font-size: 10px;"
   "  -fx-text-fill: white;"
   "  -fx-cursor: hand;"
   "  -fx-padding: 8 12;"
   "}"
   
   ".preset-button:hover {"
   "  -fx-effect: dropshadow(gaussian, rgba(255,255,255,0.3), 4, 0, 0, 0);"
   "}"
   
   ".preset-button.geometric {"
   "  -fx-background-color: " (:category-geometric colors) ";"
   "}"
   
   ".preset-button.organic {"
   "  -fx-background-color: " (:category-organic colors) ";"
   "}"
   
   ".preset-button.beam {"
   "  -fx-background-color: " (:category-beam colors) ";"
   "}"
   
   ".preset-button.text {"
   "  -fx-background-color: " (:category-text colors) ";"
   "}"
   
   ".preset-button.effect {"
   "  -fx-background-color: " (:category-effect colors) ";"
   "}"))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn color
  "Get a color from the palette by key."
  [color-key]
  (get colors color-key (:text-primary colors)))

(defn dim
  "Get a dimension value by key."
  [dim-key]
  (get dimensions dim-key 0))

(defn category-color
  "Get the color for a preset category."
  [category]
  (case category
    :geometric (:category-geometric colors)
    :organic   (:category-organic colors)
    :beam      (:category-beam colors)
    :text      (:category-text colors)
    :effect    (:category-effect colors)
    (:cell-assigned colors)))

(defn inline-style
  "Create an inline style string from a map of properties."
  [style-map]
  (->> style-map
       (map (fn [[k v]] (str (name k) ": " v ";")))
       (apply str)))
