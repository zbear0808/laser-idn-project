(ns laser-show.events.handlers.ui
  "Event handlers for UI state management.
   
   Handles:
   - Tab switching
   - Dialog management
   - Preview zone filtering
   - Preview grid layout"
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]))


(defn- handle-ui-set-active-tab
  "Change the active tab."
  [{:keys [tab-id state]}]
  {:state (assoc-in state [:ui :active-tab] tab-id)})

(defn- handle-ui-open-dialog
  "Open a dialog.
   
   FLATTENED STRUCTURE: Dialog fields are stored directly alongside :open?
   Data map is merged into [:ui :dialogs dialog-id] (not into a nested :data key)."
  [{:keys [dialog-id data state]}]
  (log/debug "Opening dialog via ui/open-dialog"
             {:dialog-id dialog-id
              :data data
              :current-open? (get-in state [:ui :dialogs dialog-id :open?])})
  {:state (-> state
              (update-in [:ui :dialogs dialog-id] merge data)
              (assoc-in [:ui :dialogs dialog-id :open?] true))})

(defn- handle-ui-close-dialog
  "Close a dialog."
  [{:keys [dialog-id state]}]
  {:state (assoc-in state [:ui :dialogs dialog-id :open?] false)})

(defn- handle-ui-update-dialog-data
  "Update data associated with a dialog (e.g., selected item within dialog).
   
   FLATTENED STRUCTURE: Updates are merged directly into [:ui :dialogs dialog-id]
   (not into a nested :data key).
   
   Supports:
   - :updates - A map of keys to merge into dialog state
   - :tab-id - If present without :updates, sets :active-bank-tab to this value
               (used by styled-tab-bar in dialogs)
   - Any other keys (except reserved ones) are merged directly as updates"
  [{:keys [dialog-id updates tab-id state] :as event}]
  ;; Extract any extra keys from event that should be merged directly
  ;; This allows callers to pass keys like :editing-name? directly without wrapping in :updates
  (let [reserved-keys #{:event/type :dialog-id :updates :tab-id :state :fx/event}
        extra-updates (into {} (remove (fn [[k _]] (reserved-keys k)) event))
        actual-updates (cond
                         updates updates
                         tab-id {:active-bank-tab tab-id}
                         (seq extra-updates) extra-updates
                         :else {})]
    {:state (update-in state [:ui :dialogs dialog-id] merge actual-updates)}))

(defn- handle-preview-set-zone-filter
  "Set the preview zone group filter.
   
   Filter values:
   - nil: show all content (master view, ignores routing)
   - :all: show only content routed to :all zone group
   - :left, :right, etc.: show only content routed to that zone group"
  [{:keys [state] :as event}]
  (let [;; :fx/event is the selected item from combo-box (a map with :id key)
        evt (:fx/event event)
        zone-group-id (if (map? evt)
                        (:id evt)
                        evt)]
    (log/debug "Setting preview zone filter:" zone-group-id)
    {:state (assoc-in state [:config :preview :zone-group-filter] zone-group-id)}))

(defn- parse-grid-layout
  "Parse grid layout string like '2x2' to [cols rows]."
  [s]
  (let [[cols rows] (mapv #(Integer/parseInt %) (str/split s #"x"))]
    [cols rows]))

(defn- handle-preview-set-grid-layout
  "Set the preview grid layout.
   
   Parses layout string like '2x2' and resizes cell array as needed.
   Zone groups are cycled dynamically from available zone groups."
  [{:keys [state] :as event}]
  (let [layout-str (or (:fx/event event) "2x2")
        [cols rows] (parse-grid-layout layout-str)
        current-cells (get-in state [:config :preview :grid-cells] [])
        ;; Get available zone groups from state
        zone-groups (vals (get state :zone-groups {}))
        zone-group-ids (mapv :id zone-groups)
        ;; Build defaults: nil (master) first, then cycle through zone groups
        default-zone-ids (cons nil (cycle (if (seq zone-group-ids)
                                            zone-group-ids
                                            [:all :left :right :center])))
        total-cells (* cols rows)
        ;; Resize cells - keep existing, fill new ones from defaults
        new-cells (vec (map-indexed
                         (fn [idx _]
                           (or (get current-cells idx)
                               {:zone-group-id (nth default-zone-ids idx nil)}))
                         (range total-cells)))]
    (log/debug "Setting preview grid layout:" [cols rows] "cells:" (count new-cells))
    {:state (-> state
                (assoc-in [:config :preview :grid-layout] [cols rows])
                (assoc-in [:config :preview :grid-cells] new-cells))}))

(defn- handle-preview-set-cell-zone
  "Set the zone filter for a specific grid cell.
   Also closes the zone selector popup after selection."
  [{:keys [cell-index zone-group-id state]}]
  (log/debug "Setting preview cell" cell-index "zone to:" zone-group-id)
  {:state (-> state
              (assoc-in [:config :preview :grid-cells cell-index :zone-group-id] zone-group-id)
              (assoc-in [:ui :preview-zone-selector-open] nil))})

(defn- handle-preview-open-zone-selector
  "Open the zone selector popup for a specific cell."
  [{:keys [cell-index state]}]
  (log/debug "Opening zone selector for cell:" cell-index)
  {:state (assoc-in state [:ui :preview-zone-selector-open] cell-index)})

(defn- handle-preview-close-zone-selector
  "Close the zone selector popup."
  [{:keys [state]}]
  (log/debug "Closing zone selector popup")
  {:state (assoc-in state [:ui :preview-zone-selector-open] nil)})


;; Public API


(defn handle
  "Dispatch UI events to their handlers.
   
   Accepts events with :event/type in the :ui/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    :ui/set-active-tab (handle-ui-set-active-tab event)
    :ui/open-dialog (handle-ui-open-dialog event)
    :ui/close-dialog (handle-ui-close-dialog event)
    :ui/update-dialog-data (handle-ui-update-dialog-data event)
    :preview/set-zone-filter (handle-preview-set-zone-filter event)
    :preview/set-grid-layout (handle-preview-set-grid-layout event)
    :preview/set-cell-zone (handle-preview-set-cell-zone event)
    :preview/open-zone-selector (handle-preview-open-zone-selector event)
    :preview/close-zone-selector (handle-preview-close-zone-selector event)
    
    ;; Unknown event in this domain
    {}))
