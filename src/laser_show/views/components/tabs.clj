(ns laser-show.views.components.tabs
  "Shared tab components for consistent tab styling across the application.
   
   Provides:
   - styled-tab-button: Individual tab button with consistent styling
   - styled-tab-bar: Horizontal bar of tab buttons
   
   Usage:
   {:fx/type styled-tab-bar
    :tabs [{:id :color :label \"Color\"}
           {:id :shape :label \"Shape\"}]
    :active-tab :color
    :on-tab-change {:event/type :some/event}}")

;; ============================================================================
;; Theme Constants
;; ============================================================================

(def tab-colors
  "Tab color scheme for consistent styling."
  {:active "#4CAF50"
   :hover "#5CAF60"
   :inactive "#3D3D3D"
   :text "#FFFFFF"
   :background "#2D2D2D"})

;; ============================================================================
;; Tab Button Component
;; ============================================================================

(defn styled-tab-button
  "A single styled tab button.
   
   Props:
   - :tab-id - Unique identifier for this tab
   - :label - Display text for the tab
   - :active? - Whether this tab is currently active
   - :on-action - Event map to dispatch when clicked (receives :tab-id in event)"
  [{:keys [tab-id label active? on-action]}]
  {:fx/type :button
   :text label
   :style-class (if active?
                  ["button" "tab-button" "tab-button-active"]
                  ["button" "tab-button"])
   :on-action (if (map? on-action)
                (assoc on-action :tab-id tab-id)
                on-action)})

;; ============================================================================
;; Tab Bar Component
;; ============================================================================

(defn styled-tab-bar
  "A horizontal tab bar with consistent styling.
   
   Props:
   - :tabs - Vector of tab definitions [{:id :tab-id :label \"Label\"} ...]
   - :active-tab - The currently active tab id
   - :on-tab-change - Event map for when a tab is clicked. Will receive :tab-id key.
   - :style (optional) - Additional style string to apply to container
   - :spacing (optional) - Spacing between tabs, default 4"
  [{:keys [tabs active-tab on-tab-change style spacing]}]
  {:fx/type :h-box
   :style (str "-fx-background-color: " (:background tab-colors) "; "
               "-fx-padding: 8 8 0 8;"
               (when style (str " " style)))
   :spacing (or spacing 4)
   :children (vec
               (for [{:keys [id label]} tabs]
                 {:fx/type styled-tab-button
                  :tab-id id
                  :label label
                  :active? (= active-tab id)
                  :on-action on-tab-change}))})

;; ============================================================================
;; Tab Content Container
;; ============================================================================

(defn styled-tab-content-area
  "Container for tab content with consistent styling.
   
   Props:
   - :children - Content to render (typically a single child based on active tab)
   - :style (optional) - Additional style string"
  [{:keys [children style]}]
  {:fx/type :v-box
   :style (str "-fx-background-color: #1E1E1E;"
               (when style (str " " style)))
   :v-box/vgrow :always
   :children (if (vector? children) children [children])})
