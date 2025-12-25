(ns laser-show.ui.effects-grid
  "Effects grid UI for managing global effects.
   Displays a grid of effect cells that can be toggled on/off.
   Effects are applied in row-major order (row 0 col 0, row 0 col 1, etc.)
   
   Uses effects-service for all mutations (underlying logic layer).
   Uses subscriptions for derived display data.
   base-grid is only used for UI-transient state (selection, hover).
   
   Usage:
   (create-effects-grid-panel
     :cols 5
     :rows 2
     :on-effects-change (fn [active-effects] ...))"
  (:require [seesaw.core :as ss]
            [laser-show.ui.base-grid :as base-grid]
            [laser-show.ui.colors :as colors]
            [laser-show.ui.layout :as layout]
            [laser-show.ui.drag-drop :as dnd]
            [laser-show.animation.effects :as fx]
            [laser-show.animation.modulation :as mod]
            [laser-show.services.effects-service :as effects-service]
            [laser-show.state.subscriptions :as subs]
            [laser-show.state.clipboard :as clipboard])
  (:import [javax.swing JPopupMenu]))

;; ============================================================================
;; Effect Data Helpers
;; ============================================================================
;; 
;; Effect cells now use chain format:
;; {:effects [{:effect-id :scale :params {...}} ...] :active true}

(defn make-effect-cell
  "Create effect cell data with a single effect.
   Returns: {:effects [{:effect-id :x :params {...}}] :active true}
   Note: Defaults to active=true so new effects are immediately visible."
  [effect-id & {:keys [active params] :or {active true}}]
  (let [default-params (fx/get-default-params effect-id)]
    {:effects [{:effect-id effect-id
                :params (merge default-params params)}]
     :active active}))

(defn has-effect?
  "Check if cell has at least one effect in its chain."
  [cell-data]
  (and cell-data (seq (:effects cell-data))))

(defn get-first-effect
  "Get the first effect in a cell's chain."
  [cell-data]
  (first (:effects cell-data)))

(defn has-modulated-params?
  "Check if any effect in the chain has modulated parameters."
  [cell-data]
  (when-let [effects (:effects cell-data)]
    (some (fn [effect]
            (when-let [params (:params effect)]
              (some (fn [[_k v]]
                      (and (fn? v) (mod/modulator? v)))
                    params)))
          effects)))

;; ============================================================================
;; Cell Rendering - uses subscriptions for derived state
;; ============================================================================

(defn- get-effect-data-for-cell
  "Get raw effect data for a cell (for operations that need full data).
   Uses effects-service since we need the full cell data for drag/drop, menus, etc."
  [col row]
  (effects-service/get-effect-cell col row))

(defn- get-effect-cell-display
  "Get effect cell display data using subscriptions.
   Returns: {:has-effect? :effect-count :first-effect-id :active? :modulated? :display-text}"
  [col row]
  (subs/effect-cell-display col row))

(defn render-effect-content
  "Render the display text for an effect cell.
   Uses subscriptions for derived display data."
  [cell-key _ui-state]
  (let [[col row] cell-key
        cell-display (get-effect-cell-display col row)]
    (if (:has-effect? cell-display)
      (let [effect-def (when-let [eid (:first-effect-id cell-display)]
                         (fx/get-effect eid))
            effect-name (or (:name effect-def) 
                           (when-let [eid (:first-effect-id cell-display)]
                             (name eid)))
            effect-count (:effect-count cell-display)]
        (str (when (:modulated? cell-display) "~ ")
             effect-name
             (when (> effect-count 1) (str " +" (dec effect-count)))
             (when-not (:active? cell-display) " (off)")))
      "")))

(defn get-effect-background
  "Get the background color for an effect cell.
   Uses subscriptions for active/effect state."
  [cell-key ui-state]
  (let [[col row] cell-key
        cell-display (get-effect-cell-display col row)]
    (cond
      ;; Selected state (from UI)
      (:selected ui-state)
      colors/cell-selected
      
      ;; Has effect assigned - from subscription
      (:has-effect? cell-display)
      (let [effect-def (when-let [eid (:first-effect-id cell-display)]
                         (fx/get-effect eid))
            category (or (:category effect-def) :shape)]
        (colors/get-effect-category-color category (not (:active? cell-display))))
      
      ;; Empty cell
      :else
      colors/cell-empty)))

(defn get-effect-border-type
  "Get the border type for an effect cell.
   Uses subscriptions for effect check."
  [cell-key _ui-state]
  (let [[col row] cell-key
        cell-display (get-effect-cell-display col row)]
    (if (:has-effect? cell-display)
      :assigned
      :empty)))

(defn is-effect-active?
  "Check if the effect cell is active (enabled).
   Uses subscriptions for active state."
  [cell-key _ui-state]
  (let [[col row] cell-key
        cell-display (get-effect-cell-display col row)]
    (and (:has-effect? cell-display) (:active? cell-display))))

;; ============================================================================
;; Drag & Drop
;; ============================================================================

(defn- serialize-params-for-drag
  "Convert effect params to a serializable format.
   Modulator functions are converted to their pure data config maps.
   Params should already be configs, but this handles legacy function cases."
  [params]
  (when params
    (into {}
      (map (fn [[k v]]
             (cond
               ;; Modulator function with config - extract config only
               (and (fn? v) (mod/modulator? v))
               (if-let [config (mod/get-modulator-config v)]
                 [k config]  ; Just the config map, no wrapping
                 [k 1.0])    ; Fallback if no config
               
               ;; Modulator config map - pass through as-is
               (mod/modulator-config? v)
               [k v]
               
               ;; Any other function (non-modulator) - use default
               (fn? v)
               [k 1.0]
               
               ;; Atom or other derefable - deref it
               (instance? clojure.lang.IDeref v)
               [k @v]
               
               ;; Regular serializable value (including plain maps)
               :else
               [k v]))
           params))))

(defn- deserialize-params-from-drag
  "Pass through params as-is - they should already be pure data configs.
   No function conversion happens here - that's done by resolve-param during effect application."
  [params]
  params)

(defn- serialize-cell-for-drag
  "Serialize a cell (with effect chain) for dragging.
   Each effect's params get serialized."
  [cell-data]
  (when cell-data
    (update cell-data :effects
            (fn [effects]
              (mapv (fn [effect]
                      (update effect :params serialize-params-for-drag))
                    effects)))))

(defn get-effect-drag-data
  "Get drag data for an effect cell.
   Reads from !effects atom."
  [cell-key]
  (let [[col row] cell-key
        cell-data (get-effect-data-for-cell col row)]
    (when (has-effect? cell-data)
      (let [serializable-data (serialize-cell-for-drag cell-data)]
        {:type :effect-cell
         :source-id :effect-grid
         :cell-key cell-key
         :data serializable-data}))))

(defn accept-effect-drop?
  "Check if a drop should be accepted for an effect cell."
  [cell-key transfer-data]
  (and transfer-data
       (= (:type transfer-data) :effect-cell)
       ;; Don't allow dropping on self
       (not= (:cell-key transfer-data) cell-key)))

(defn create-effect-ghost
  "Create a ghost image for dragging an effect."
  [panel cell-key]
  (let [[col row] cell-key
        cell-data (get-effect-data-for-cell col row)
        first-effect (get-first-effect cell-data)
        effect-id (:effect-id first-effect)
        effect-def (fx/get-effect effect-id)
        category (or (:category effect-def) :shape)
        color (colors/get-effect-category-color category false)
        effect-count (count (:effects cell-data))
        effect-name (str (or (:name effect-def) (name effect-id))
                         (when (> effect-count 1) (str " +" (dec effect-count))))]
    (dnd/create-simple-ghost-image 
     (.getWidth panel) (.getHeight panel)
     color
     :opacity 0.7
     :text effect-name)))

;; ============================================================================
;; Context Menu
;; ============================================================================

(defn create-effect-context-menu
  "Create context menu for an effect cell."
  [cell-key on-toggle! on-set-effect! on-clear! on-new-effect on-edit-effect]
  (let [[col row] cell-key
        effect-data (get-effect-data-for-cell col row)
        has-effect? (has-effect? effect-data)
        can-paste? (clipboard/can-paste-effect-chain?)
        popup (JPopupMenu.)]
    
    (if has-effect?
      ;; Menu for assigned cell
      (do
        (.add popup (base-grid/create-menu-item 
                     "Edit Effect..." 
                     (fn [] (when on-edit-effect (on-edit-effect cell-key effect-data)))))
        (.add popup (base-grid/create-menu-item 
                     (if (:active effect-data) "Turn Off" "Turn On")
                     (fn [] (on-toggle! col row))))
        (.addSeparator popup)
        (.add popup (base-grid/create-menu-item 
                     "Copy"
                     (fn [] (clipboard/copy-effect-chain! effect-data))))
        (.add popup (base-grid/create-menu-item 
                     "Paste"
                     (fn [] (when-let [pasted (clipboard/paste-effect-chain)]
                              (on-set-effect! col row pasted)))
                     :enabled? can-paste?))
        (.addSeparator popup)
        (.add popup (base-grid/create-menu-item 
                     "Clear"
                     (fn [] (on-clear! col row)))))
      
      ;; Menu for empty cell
      (do
        (.add popup (base-grid/create-menu-item 
                     "New Effect..."
                     (fn [] (when on-new-effect (on-new-effect cell-key)))))
        (.addSeparator popup)
        (.add popup (base-grid/create-menu-item 
                     "Paste"
                     (fn [] (when-let [pasted (clipboard/paste-effect-chain)]
                              (on-set-effect! col row pasted)))
                     :enabled? can-paste?))))
    
    popup))

;; ============================================================================
;; Effects Grid Component
;; ============================================================================

(defn create-effects-grid-panel
  "Create the effects grid panel.
   
   Uses !effects atom as single source of truth for effect data.
   base-grid handles UI-transient state (selection).
   
   Parameters:
   - :cols - number of columns (default 5)
   - :rows - number of rows (default 2)
   - :on-effects-change - (fn [active-effects]) called when active effects change
   - :on-new-effect - (fn [cell-key]) called when user wants to create new effect
   - :on-edit-effect - (fn [cell-key effect-data]) called when user wants to edit effect
   
   Returns a map with:
   - :panel - the JPanel
   - :set-cell-effect! - (fn [col row effect-data])
   - :get-cell-effect - (fn [col row]) -> effect-data
   - :toggle-cell! - (fn [col row]) toggle effect on/off
   - :clear-cell! - (fn [col row])
   - :get-active-effects - (fn []) -> vec of active effect instances (sorted)
   - :apply-to-frame - (fn [frame time-ms bpm]) -> transformed frame
   - :clear-all! - (fn [])
   - :refresh-ui! - (fn []) force UI refresh from !effects state"
  [& {:keys [cols rows on-effects-change on-new-effect on-edit-effect]
      :or {cols layout/default-effects-grid-cols
           rows layout/default-effects-grid-rows}}]
  
  (let [;; Reference to base-grid component for UI updates
        !base-grid-ref (atom nil)
        
        ;; Notify callback and refresh UI
        notify-and-refresh! (fn []
                              (when-let [bg @!base-grid-ref]
                                ;; Refresh all cell appearances
                                ((:for-each-cell bg)
                                 (fn [_key cell]
                                   ((:update! cell)))))
                              (when on-effects-change
                                (on-effects-change (effects-service/get-all-active-effects))))
        
        ;; Effect operations - write through effects-service for underlying logic
        set-effect! (fn [col row effect-data]
                      (effects-service/set-effect-cell! col row effect-data)
                      (notify-and-refresh!))
        
        toggle-effect! (fn [col row]
                         (effects-service/toggle-effect-active! col row)
                         (notify-and-refresh!))
        
        clear-effect! (fn [col row]
                        (effects-service/clear-effect-cell! col row)
                        (notify-and-refresh!))
        
        ;; Cell callbacks for base-grid
        ;; These callbacks read from !effects atom, not from base-grid's internal state
        cell-callbacks
        {:render-content (fn [cell-state]
                           ;; cell-state from base-grid has :key which we use
                           ;; to look up effect data from !effects
                           (let [cell-key (:key cell-state)]
                             (render-effect-content cell-key cell-state)))
         
         :get-background (fn [cell-state]
                           (let [cell-key (:key cell-state)]
                             (get-effect-background cell-key cell-state)))
         
         :get-border-type (fn [cell-state]
                            (let [cell-key (:key cell-state)]
                              (get-effect-border-type cell-key cell-state)))
         
         :is-active? (fn [cell-state]
                       (let [cell-key (:key cell-state)]
                         (is-effect-active? cell-key cell-state)))
         
         ;; Click toggles the effect
         :on-click (fn [cell-key _ui-state]
                     (let [[col row] cell-key
                           effect-data (get-effect-data-for-cell col row)]
                       (when (has-effect? effect-data)
                         (toggle-effect! col row))))
         
         ;; Right-click shows context menu
         :on-right-click (fn [cell-key _ui-state panel event]
                           (let [menu (create-effect-context-menu
                                       cell-key
                                       toggle-effect!
                                       set-effect!
                                       clear-effect!
                                       on-new-effect
                                       on-edit-effect)]
                             (base-grid/show-context-menu! menu panel event)))
         
         ;; Double-click opens dialog
         :on-double-click (fn [cell-key _ui-state]
                            (let [[col row] cell-key
                                  effect-data (get-effect-data-for-cell col row)]
                              (if (has-effect? effect-data)
                                ;; Has effect - open edit dialog
                                (when on-edit-effect
                                  (on-edit-effect cell-key effect-data))
                                ;; Empty cell - open new effect dialog
                                (when on-new-effect
                                  (on-new-effect cell-key)))))
         
         ;; Drag & drop
         :get-drag-data (fn [cell-state]
                          (let [cell-key (:key cell-state)]
                            (get-effect-drag-data cell-key)))
         
         :accept-drop? accept-effect-drop?
         
         :on-drop (fn [cell-key transfer-data]
                    (let [source-key (:cell-key transfer-data)
                          ;; Deserialize params (reconstruct modulators from config)
                          effect-data (update (:data transfer-data) :params deserialize-params-from-drag)
                          [col row] cell-key]
                      ;; Set the effect on target
                      (set-effect! col row effect-data)
                      ;; Clear source if it exists (MOVE not COPY)
                      (when source-key
                        (let [[src-col src-row] source-key]
                          (clear-effect! src-col src-row)))
                      true))
         
         :create-ghost (fn [panel cell-state]
                         (let [cell-key (:key cell-state)]
                           (create-effect-ghost panel cell-key)))
         
         ;; Copy/paste
         :on-copy (fn [cell-key _ui-state]
                    (let [[col row] cell-key
                          effect-data (get-effect-data-for-cell col row)]
                      (when (has-effect? effect-data)
                        (clipboard/copy-effect-chain! effect-data))))
         
         :on-paste (fn [cell-key]
                     (when-let [effect-data (clipboard/paste-effect-chain)]
                       (let [[col row] cell-key]
                         (set-effect! col row effect-data))))}
        
        ;; Create the base grid - only for UI (selection state, hover)
        base-grid-component (base-grid/create-grid-panel
                             :cols cols
                             :rows rows
                             :cell-callbacks cell-callbacks)]
    
    ;; Store reference for callbacks
    (reset! !base-grid-ref base-grid-component)
    
    ;; Return the effects grid API
    {:panel (:panel base-grid-component)
     
     :set-cell-effect! set-effect!
     
     :get-cell-effect (fn [col row]
                        (effects-service/get-effect-cell col row))
     
     :toggle-cell! toggle-effect!
     
     :clear-cell! clear-effect!
     
     :get-active-effects effects-service/get-all-active-effects
     
     :apply-to-frame (fn [frame time-ms bpm]
                       (let [active-effects (effects-service/get-all-active-effects)]
                         (reduce
                          (fn [f effect-data]
                            (let [instance (fx/make-effect-instance
                                            (:effect-id effect-data)
                                            :enabled true
                                            :params (:params effect-data))]
                              (fx/apply-effect f instance time-ms bpm)))
                          frame
                          active-effects)))
     
     :clear-all! (fn []
                   (effects-service/clear-all-effects!)
                   (notify-and-refresh!))
     
     :refresh-ui! notify-and-refresh!
     
     :get-selected (fn [] ((:get-selected base-grid-component)))
     
     :cells (:cells base-grid-component)}))

;; ============================================================================
;; Demo/Test Functions
;; ============================================================================

(comment
  (require '[laser-show.state.atoms :as state])
  ;; Test creating an effects grid
  
  ;; Reset effects state
  (state/reset-effects!)
  
  (def test-grid 
    (create-effects-grid-panel
     :cols 5
     :rows 2
     :on-effects-change (fn [effects]
                          (println "Active effects:" (count effects)))))
  
  ;; Add a test effect via !effects atom
  (state/set-effect-cell! 0 0
                          {:effects [{:effect-id :scale :params {:x-scale 1.5 :y-scale 1.5}}] :active true})
  
  ;; Refresh UI to see the change
  ((:refresh-ui! test-grid))
  
  ;; Show in a frame
  (-> (ss/frame :title "Effects Grid Test"
                :content (:panel test-grid)
                :size [500 :by 200])
      ss/show!)
  
  ;; Get active effects
  (state/get-all-active-effect-instances)
  )
