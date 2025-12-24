(ns laser-show.ui.grid
  "Launchpad-style grid UI for triggering laser animations.
   Refactored to use base-grid for shared UI infrastructure.
   Uses subscriptions for derived state data.
   
   Usage:
   (create-grid-panel dispatch! :cols 5 :rows 4)"
  (:require [seesaw.core :as ss]
            [seesaw.mig :as mig]
            [laser-show.animation.presets :as presets]
            [laser-show.ui.base-grid :as base-grid]
            [laser-show.ui.colors :as colors]
            [laser-show.ui.drag-drop :as dnd]
            [laser-show.ui.layout :as layout]
            [laser-show.state.atoms :as state]
            [laser-show.state.subscriptions :as subs]
            [laser-show.state.clipboard :as clipboard])
  (:import [java.awt Color Font]
           [javax.swing JPopupMenu]))

;; ============================================================================
;; Cue Data Helpers (using subscriptions)
;; ============================================================================

(defn- get-cell-display
  "Get cell display data using subscriptions.
   Returns: {:preset-id :active? :selected? :has-content?}"
  [col row]
  (subs/grid-cell-display col row))

(defn- get-cue-data-for-cell
  "Get raw cue data for a cell (for drag/drop and context menu).
   Uses state directly since we need the full cell data."
  [col row]
  (state/get-cell col row))

(defn has-preset?
  "Check if cell has a preset assigned."
  [cell-data]
  (boolean (:preset-id cell-data)))

;; ============================================================================
;; Cell Rendering - uses subscriptions for derived state
;; ============================================================================

(defn render-cue-content
  "Render the display text for a cue cell.
   Uses subscriptions for cell data."
  [cell-state]
  (let [[col row] (:key cell-state)
        cell-display (get-cell-display col row)
        preset-id (:preset-id cell-display)]
    (if preset-id
      (if-let [preset (presets/get-preset preset-id)]
        (:name preset)
        (name preset-id))
      "")))

(defn get-cue-background
  "Get the background color for a cue cell.
   Uses subscriptions for active/selected state."
  [cell-state]
  (let [[col row] (:key cell-state)
        cell-display (get-cell-display col row)]
    (cond
      ;; Active (currently playing) - from subscription
      (:active? cell-display)
      colors/cell-active
      
      ;; Selected state (from UI cell-state, not subscription)
      (:selected cell-state)
      colors/cell-selected
      
      ;; Has preset assigned - from subscription
      (:has-content? cell-display)
      (if-let [preset (presets/get-preset (:preset-id cell-display))]
        (colors/get-category-color (:category preset))
        colors/cell-assigned)
      
      ;; Empty cell
      :else
      colors/cell-empty)))

(defn get-cue-border-type
  "Get the border type for a cue cell.
   Uses subscriptions for content check."
  [cell-state]
  (let [[col row] (:key cell-state)
        cell-display (get-cell-display col row)]
    (if (:has-content? cell-display)
      :assigned
      :empty)))

(defn is-cue-active?
  "Check if the cue cell is currently playing.
   Uses subscriptions for active state."
  [cell-state]
  (let [[col row] (:key cell-state)
        cell-display (get-cell-display col row)]
    (:active? cell-display)))

;; ============================================================================
;; Drag & Drop
;; ============================================================================

(defn get-cue-drag-data
  "Get drag data for a cue cell."
  [cell-state]
  (let [[col row] (:key cell-state)
        cell-data (get-cue-data-for-cell col row)]
    (when (has-preset? cell-data)
      {:type :cue-cell
       :source-id :main-grid
       :cell-key [col row]
       :data cell-data})))

(defn accept-cue-drop?
  "Check if a drop should be accepted for a cue cell."
  [cell-key transfer-data]
  (and transfer-data
       (= (:type transfer-data) :cue-cell)
       (not= (:cell-key transfer-data) cell-key)))

;; ============================================================================
;; Context Menu
;; ============================================================================

(defn create-cue-context-menu
  "Create context menu for a cue cell."
  [cell-key dispatch!]
  (let [[col row] cell-key
        cell-data (get-cue-data-for-cell col row)
        has-preset? (has-preset? cell-data)
        can-paste? (clipboard/can-paste-cell-assignment?)
        popup (JPopupMenu.)]
    
    (if has-preset?
      ;; Menu for assigned cell
      (do
        (.add popup (base-grid/create-menu-item 
                     "Copy"
                     (fn [] (dispatch! [:clipboard/copy-cell cell-key]))))
        (.add popup (base-grid/create-menu-item 
                     "Paste"
                     (fn [] (dispatch! [:clipboard/paste-cell cell-key]))
                     :enabled? can-paste?))
        (.addSeparator popup)
        (.add popup (base-grid/create-menu-item 
                     "Clear"
                     (fn [] (dispatch! [:grid/clear-cell col row])))))
      
      ;; Menu for empty cell
      (.add popup (base-grid/create-menu-item 
                   "Paste"
                   (fn [] (dispatch! [:clipboard/paste-cell cell-key]))
                   :enabled? can-paste?)))
    
    popup))

;; ============================================================================
;; Cue Grid Component
;; ============================================================================

(defn create-grid-panel
  "Create the main cue grid panel.
   
   Uses base-grid for shared UI infrastructure.
   Cue data is stored in !grid atom.
   
   Parameters:
   - dispatch!: Function (fn [event-vector]) to dispatch events
   - :cols - number of columns (default from layout config)
   - :rows - number of rows (default from layout config)
   
   Returns a map with:
   - :panel - the JPanel
   - :update-view! - (fn [app-state]) to refresh UI from state"
  [dispatch! & {:keys [cols rows]
                :or {cols layout/default-grid-cols
                     rows layout/default-grid-rows}}]
  
  (let [;; Reference to base-grid component for UI updates
        !base-grid-ref (atom nil)
        
        ;; Cell callbacks for base-grid
        cell-callbacks
        {:render-content render-cue-content
         
         :get-background get-cue-background
         
         :get-border-type get-cue-border-type
         
         :is-active? is-cue-active?
         
         ;; Click selects AND triggers the cell
         :on-click (fn [cell-key _ui-state]
                     (let [[col row] cell-key]
                       (dispatch! [:grid/select-cell col row])
                       (dispatch! [:grid/trigger-cell col row])))
         
         ;; Right-click shows context menu
         :on-right-click (fn [cell-key _ui-state panel event]
                           (let [menu (create-cue-context-menu cell-key dispatch!)]
                             (base-grid/show-context-menu! menu panel event)))
         
         ;; Drag & drop
         :get-drag-data get-cue-drag-data
         
         :accept-drop? accept-cue-drop?
         
         :on-drop (fn [cell-key transfer-data]
                    (let [source-key (:cell-key transfer-data)
                          [to-col to-row] cell-key
                          [from-col from-row] source-key]
                      ;; Move cell via dispatch
                      (dispatch! [:grid/move-cell from-col from-row to-col to-row])
                      true))
         
         :create-ghost (fn [panel cell-state]
                         (let [[col row] (:key cell-state)
                               cell-data (get-cue-data-for-cell col row)
                               preset-id (:preset-id cell-data)
                               preset (presets/get-preset preset-id)
                               category (or (:category preset) :geometric)
                               color (colors/get-category-color category)
                               preset-name (or (:name preset) (name preset-id))]
                           (dnd/create-simple-ghost-image
                            (.getWidth panel) (.getHeight panel)
                            color
                            :opacity 0.7
                            :text preset-name)))
         
         ;; Copy/paste via dispatch
         :on-copy (fn [cell-key _ui-state]
                    (dispatch! [:clipboard/copy-cell cell-key]))
         
         :on-paste (fn [cell-key]
                     (dispatch! [:clipboard/paste-cell cell-key]))}
        
        ;; Create the base grid
        base-grid-component (base-grid/create-grid-panel
                             :cols cols
                             :rows rows
                             :cell-callbacks cell-callbacks)]
    
    ;; Store reference for callbacks
    (reset! !base-grid-ref base-grid-component)
    
    ;; Return the cue grid API
    {:panel (:panel base-grid-component)
     
     ;; Update function called when app-state changes
     :update-view! (fn [_app-state]
                     ;; Refresh all cell appearances from !grid state
                     (when-let [bg @!base-grid-ref]
                       ((:for-each-cell bg)
                        (fn [_key cell]
                          ((:update! cell))))))}))

;; ============================================================================
;; Preset Palette
;; ============================================================================

(defn create-preset-palette
  "Create a panel showing available presets."
  [dispatch!]
  (let [preset-buttons (for [preset presets/all-presets]
                         (let [[r g b] (or (get-in presets/categories [(:category preset) :color])
                                           [100 100 100])
                               btn (ss/button
                                    :text (:name preset)
                                    :font (Font. "SansSerif" Font/PLAIN 10)
                                    :background (Color. r g b)
                                    :foreground Color/WHITE)]
                           (ss/listen btn :action (fn [_] (dispatch! [:grid/set-selected-preset (:id preset)])))
                           btn))]
    (ss/scrollable
     (mig/mig-panel
      :constraints ["wrap 2, gap 5, insets 10"]
      :items (mapv (fn [btn] [btn "grow, w 100!"]) preset-buttons)
      :background (Color. 45 45 45))
     :hscroll :never)))
