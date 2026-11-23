(ns laser-show.events.handlers.ui
  "Event handlers for UI state management.
   
   Handles:
   - Tab switching
   - Dialog management
   - The status bar's notice (clearing it)
   - Where the divider between a tab and the preview sits
   - Preview zone filtering
   - Preview grid layout"
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]))


(defn- handle-ui-set-active-tab
  "Change the active tab."
  [{:keys [tab-id state]}]
  {:state (assoc-in state [:ui :active-tab] tab-id)})

(defn- handle-ui-reveal-projector
  "Open a laser's card: the Projectors tab, with the laser selected (the
   toolbar's LOW FPS chip, zones plan Z3). A laser that is gone opens the
   tab only."
  [{:keys [projector-id state]}]
  {:state (cond-> (assoc-in state [:ui :active-tab] :projectors)
            (contains? (:projectors state) projector-id)
            (-> (assoc-in [:projector-ui :active-projector] projector-id)
                (assoc-in [:projector-ui :active-virtual-projector] nil)))})

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
  "Close the dialog. Cancel and window close discard the Add Projector form,
   just like a successful Add; editors keep their per-dialog UI state."
  [{:keys [dialog-id state]}]
  {:state (if (= :add-projector-manual dialog-id)
            (assoc-in state [:ui :dialogs dialog-id] {:open? false})
            (assoc-in state [:ui :dialogs dialog-id :open?] false))})

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

(defn- handle-ui-clear-notice
  "Clear the notice with :id: its timer's (events/helpers.clj show-notice) or
   a click on it. A later notice, with another id, stays; with nothing to
   clear there is no effect."
  [{:keys [id state]}]
  (if (and (some? id) (= id (get-in state [:ui :notice :id])))
    {:state (assoc-in state [:ui :notice] nil)}
    {}))

(defn- handle-ui-confirm
  "The shared confirm dialog's confirm button (views/dialogs/confirm.clj):
   close it, and continue with its :confirm-event, plus the picked option
   under its choice's :key when it has a choice. A closed dialog continues
   with nothing."
  [{:keys [state]}]
  (let [{:keys [open? confirm-event choice choice-value]} (get-in state [:ui :dialogs :confirm])
        closed (assoc-in state [:ui :dialogs :confirm] {:open? false})]
    (if (and open? confirm-event)
      {:state closed
       :dispatch (cond-> confirm-event
                   (:key choice) (assoc (:key choice) choice-value))}
      {:state closed})))

(defn- handle-ui-set-grid-split
  "Remember where the user left the divider between a tab and the preview
   (the split pane dispatches it when the mouse lets go of the divider,
   views/root.clj). [:config :window :grid-split] is a preference: saved
   with the project, not undoable."
  [{:keys [position state]}]
  (if (number? position)
    {:state (assoc-in state [:config :window :grid-split] (double position))}
    {}))

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
                                            [:all :auto/left :auto/right :auto/center])))
        total-cells (* cols rows)
        ;; Resize cells - keep existing, fill new ones from defaults
        new-cells (vec (map-indexed
                        (fn [idx _]
                          (or (get current-cells idx)
                              {:target (nth default-zone-ids idx nil)}))
                        (range total-cells)))]
    (log/debug "Setting preview grid layout:" [cols rows] "cells:" (count new-cells))
    {:state (-> state
                (assoc-in [:config :preview :grid-layout] [cols rows])
                (assoc-in [:config :preview :grid-cells] new-cells))}))

(defn- handle-preview-set-cell-zone
  "Set what a preview cell shows: :target, a ref (a group: what is sent to
   it; a laser's :auto.laser/<id>: what that laser draws) or nil (the master
   view, everything); :zone-group-id, the old key, still works. The cell is
   written {:target …} (zones plan Z3; Z4 renames this event
   :preview/set-cell-target), and the zone selector popup closes."
  [{:keys [cell-index state] :as event}]
  (let [target (if (contains? event :target) (:target event) (:zone-group-id event))]
    (log/debug "Setting preview cell" cell-index "target to:" target)
    {:state (-> state
                (assoc-in [:config :preview :grid-cells cell-index] {:target target})
                (assoc-in [:ui :preview-zone-selector-open] nil))}))

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
    :ui/reveal-projector (handle-ui-reveal-projector event)
    :ui/open-dialog (handle-ui-open-dialog event)
    :ui/close-dialog (handle-ui-close-dialog event)
    :ui/update-dialog-data (handle-ui-update-dialog-data event)
    :ui/clear-notice (handle-ui-clear-notice event)
    :ui/confirm (handle-ui-confirm event)
    :ui/set-grid-split (handle-ui-set-grid-split event)
    :preview/set-zone-filter (handle-preview-set-zone-filter event)
    :preview/set-grid-layout (handle-preview-set-grid-layout event)
    :preview/set-cell-zone (handle-preview-set-cell-zone event)
    :preview/open-zone-selector (handle-preview-open-zone-selector event)
    :preview/close-zone-selector (handle-preview-close-zone-selector event)

    ;; Unknown event in this domain
    {}))
