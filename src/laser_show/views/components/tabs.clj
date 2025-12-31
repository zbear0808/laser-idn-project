(ns laser-show.views.components.tabs
  "Shared tab components for consistent tab styling across the application.
   
   Provides:
   - styled-tab-button: Individual tab button with consistent styling
   - styled-tab-bar: Horizontal bar of tab buttons
   
   Uses CSS classes from laser-show.css.buttons:
   - .tab-btn: Base tab button style
   - .tab-btn-active: Active tab button style
   
   Usage:
   {:fx/type styled-tab-bar
    :tabs [{:id :color :label \"Color\"}
           {:id :shape :label \"Shape\"}]
    :active-tab :color
    :on-tab-change {:event/type :some/event}}")


;; Tab Button Component


(defn styled-tab-button
  "A single styled tab button.
   
   Props:
   - :tab-id - Unique identifier for this tab
   - :label - Display text for the tab
   - :active? - Whether this tab is currently active
   - :on-action - Event map to dispatch when clicked (receives :tab-id in event)
   
   Uses CSS classes: .tab-btn, .tab-btn-active"
  [{:keys [tab-id label active? on-action]}]
  {:fx/type :button
   :text label
   ;; Use CSS classes from buttons.clj
   :style-class (if active? "tab-btn-active" "tab-btn")
   :on-action (if (map? on-action)
                (assoc on-action :tab-id tab-id)
                on-action)})


;; Tab Bar Component


(defn styled-tab-bar
  "A horizontal tab bar with consistent styling.
   
   Props:
   - :tabs - Vector of tab definitions [{:id :tab-id :label \"Label\"} ...]
   - :active-tab - The currently active tab id
   - :on-tab-change - Event map for when a tab is clicked. Will receive :tab-id key.
   - :spacing (optional) - Spacing between tabs, default 4
   
   Uses CSS class: .tab-header from layout.clj"
  [{:keys [tabs active-tab on-tab-change spacing]}]
  {:fx/type :h-box
   ;; Use CSS class from layout.clj
   :style-class "tab-header"
   :spacing (or spacing 4)
   :children (vec
               (for [{:keys [id label]} tabs]
                 {:fx/type styled-tab-button
                  :tab-id id
                  :label label
                  :active? (= active-tab id)
                  :on-action on-tab-change}))})

