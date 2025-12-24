(ns laser-show.ui.effects-grid
  "Effects grid UI for managing global effects.
   Displays a grid of effect cells that can be toggled on/off.
   Effects are applied in row-major order (row 0 col 0, row 0 col 1, etc.)
   
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
            [laser-show.state.clipboard :as clipboard]
            [laser-show.ui.effect-dialogs :as dialogs])
  (:import [java.awt Color]
           [java.awt.event MouseEvent]
           [javax.swing JPopupMenu]))

;; ============================================================================
;; Effect Cell State
;; ============================================================================

;; Cell state structure:
;; {:data {:effect-id :scale
;;         :enabled true
;;         :params {:x-scale 1.5 :y-scale 1.5}}
;;  :active false    ;; Is this effect currently "on" (toggled on)
;;  :selected false} ;; Is this cell selected

(defn make-effect-data
  "Create effect data for a cell."
  [effect-id & {:keys [enabled params] :or {enabled false}}]
  (let [default-params (fx/get-default-params effect-id)]
    {:effect-id effect-id
     :enabled enabled
     :params (merge default-params params)}))

(defn has-effect?
  "Check if cell state has an effect assigned."
  [cell-state]
  (some? (get-in cell-state [:data :effect-id])))

(defn is-effect-active?
  "Check if the effect in this cell is active (toggled on)."
  [cell-state]
  (boolean (:active cell-state)))

(defn has-modulated-params?
  "Check if any parameters in the effect are modulated."
  [cell-state]
  (when-let [params (get-in cell-state [:data :params])]
    (some (fn [[_k v]]
            (and (fn? v) (:laser-show.animation.modulation/modulator (meta v))))
          params)))

;; ============================================================================
;; Cell Rendering
;; ============================================================================

(defn render-effect-content
  "Render the display text for an effect cell."
  [cell-state]
  (if-let [effect-id (get-in cell-state [:data :effect-id])]
    (let [effect-def (fx/get-effect effect-id)
          name (or (:name effect-def) (name effect-id))
          active? (:active cell-state)
          modulated? (has-modulated-params? cell-state)]
      (str (when modulated? "~ ")
           name
           (when-not active? " (off)")))
    ""))

(defn get-effect-background
  "Get the background color for an effect cell."
  [cell-state]
  (cond
    ;; Selected state
    (:selected cell-state)
    colors/cell-selected
    
    ;; Has effect assigned
    (has-effect? cell-state)
    (let [effect-id (get-in cell-state [:data :effect-id])
          effect-def (fx/get-effect effect-id)
          category (or (:category effect-def) :shape)
          active? (:active cell-state)]
      (colors/get-effect-category-color category (not active?)))
    
    ;; Empty cell
    :else
    colors/cell-empty))

(defn get-effect-border-type
  "Get the border type for an effect cell."
  [cell-state]
  (if (has-effect? cell-state)
    :assigned
    :empty))

;; ============================================================================
;; Drag & Drop
;; ============================================================================

(defn- serialize-params-for-drag
  "Convert effect params to a serializable format.
   Modulator functions are converted to their config maps.
   This allows drag data to be EDN-serialized.
   
   Handles:
   - Modulator functions -> their config maps
   - Non-modulator functions -> static default (1.0)
   - Atoms/refs -> dereferenced value
   - All other values -> as-is"
  [params]
  (when params
    (into {}
      (map (fn [[k v]]
             (cond
               ;; Modulator function with config
               (and (fn? v) (mod/modulator? v))
               (if-let [config (mod/get-modulator-config v)]
                 [k {:modulator-config config}]
                 [k 1.0])
               
               ;; Any other function (non-modulator) - use default
               (fn? v)
               [k 1.0]
               
               ;; Atom or other derefable - deref it
               (instance? clojure.lang.IDeref v)
               [k @v]
               
               ;; Regular serializable value
               :else
               [k v]))
           params))))

(defn- deserialize-params-from-drag
  "Convert serialized params back to effect params.
   Modulator configs are converted back to modulator functions."
  [params]
  (when params
    (into {}
      (map (fn [[k v]]
             (if (and (map? v) (:modulator-config v))
               ;; Reconstruct modulator from config
               (let [{:keys [type min max freq phase loop-mode duration time-unit]
                      :or {freq 1.0
                           phase 0.0
                           loop-mode :loop
                           duration 1.0
                           time-unit :beats}} (:modulator-config v)]
                 [k (case type
                      :sine (mod/sine-mod min max freq phase loop-mode duration time-unit)
                      :triangle (mod/triangle-mod min max freq phase loop-mode duration time-unit)
                      :sawtooth (mod/sawtooth-mod min max freq phase)
                      :square (mod/square-mod min max freq)
                      :random (mod/random-mod min max freq)
                      :beat-decay (mod/beat-decay max min)
                      ;; Default to sine
                      (mod/sine-mod min max freq phase loop-mode duration time-unit))])
               [k v]))
           params))))

(defn get-effect-drag-data
  "Get drag data for an effect cell.
   Params are serialized to allow EDN transfer."
  [cell-state]
  (when-let [effect-data (:data cell-state)]
    (let [serializable-data (update effect-data :params serialize-params-for-drag)]
      {:type :effect-cell
       :source-id :effect-grid
       :data serializable-data})))

(defn accept-effect-drop?
  "Check if a drop should be accepted for an effect cell."
  [cell-key transfer-data]
  (and transfer-data
       (= (:type transfer-data) :effect-cell)
       ;; Don't allow dropping on self
       (not= (:cell-key transfer-data) cell-key)))

(defn create-effect-ghost
  "Create a ghost image for dragging an effect."
  [panel cell-state]
  (let [effect-id (get-in cell-state [:data :effect-id])
        effect-def (fx/get-effect effect-id)
        category (or (:category effect-def) :shape)
        color (colors/get-effect-category-color category false)
        name (or (:name effect-def) (name effect-id))]
    (dnd/create-simple-ghost-image 
     (.getWidth panel) (.getHeight panel)
     color
     :opacity 0.7
     :text name)))

;; ============================================================================
;; Context Menu
;; ============================================================================

(defn create-effect-context-menu
  "Create context menu for an effect cell."
  [cell-key cell-state grid-component on-new-effect on-edit-effect]
  (let [popup (JPopupMenu.)
        has-effect? (has-effect? cell-state)
        can-paste? (clipboard/can-paste-effect-assignment?)]
    
    (if has-effect?
      ;; Menu for assigned cell
      (do
        (.add popup (base-grid/create-menu-item 
                     "Edit Effect..." 
                     (fn [] (when on-edit-effect (on-edit-effect cell-key cell-state)))))
        (.add popup (base-grid/create-menu-item 
                     (if (:active cell-state) "Turn Off" "Turn On")
                     (fn [] ((:toggle-cell! grid-component) (first cell-key) (second cell-key)))))
        (.addSeparator popup)
        (.add popup (base-grid/create-menu-item 
                     "Copy"
                     (fn [] (clipboard/copy-effect-assignment! (:data cell-state)))))
        (.add popup (base-grid/create-menu-item 
                     "Paste"
                     (fn [] (when-let [effect-data (clipboard/paste-effect-assignment)]
                              ((:set-cell-effect! grid-component) 
                               (first cell-key) (second cell-key) effect-data)))
                     :enabled? can-paste?))
        (.addSeparator popup)
        (.add popup (base-grid/create-menu-item 
                     "Clear"
                     (fn [] ((:clear-cell! grid-component) (first cell-key) (second cell-key))))))
      
      ;; Menu for empty cell
      (do
        (.add popup (base-grid/create-menu-item 
                     "New Effect..."
                     (fn [] (when on-new-effect (on-new-effect cell-key)))))
        (.addSeparator popup)
        (.add popup (base-grid/create-menu-item 
                     "Paste"
                     (fn [] (when-let [effect-data (clipboard/paste-effect-assignment)]
                              ((:set-cell-effect! grid-component) 
                               (first cell-key) (second cell-key) effect-data)))
                     :enabled? can-paste?))))
    
    popup))

;; ============================================================================
;; Effects Grid Component
;; ============================================================================

(defn create-effects-grid-panel
  "Create the effects grid panel.
   
   Parameters:
   - :cols - number of columns (default 5)
   - :rows - number of rows (default 2)
   - :on-effects-change - (fn [active-effects]) called when active effects change
   - :on-new-effect - (fn [cell-key]) called when user wants to create new effect
   - :on-edit-effect - (fn [cell-key cell-state]) called when user wants to edit effect
   
   Returns a map with:
   - :panel - the JPanel
   - :set-cell-effect! - (fn [col row effect-data])
   - :get-cell-effect - (fn [col row]) -> effect-data
   - :toggle-cell! - (fn [col row]) toggle effect on/off
   - :clear-cell! - (fn [col row])
   - :get-active-effects - (fn []) -> vec of active effect instances (sorted)
   - :apply-to-frame - (fn [frame time-ms bpm]) -> transformed frame
   - :clear-all! - (fn [])"
  [& {:keys [cols rows on-effects-change on-new-effect on-edit-effect]
      :or {cols layout/default-effects-grid-cols
           rows layout/default-effects-grid-rows}}]
  
  ;; Store a reference to the grid component for use in callbacks
  (let [!grid-ref (atom nil)
        
        notify-change! (fn []
                         (when on-effects-change
                           (when-let [grid @!grid-ref]
                             (on-effects-change ((:get-active-effects grid))))))
        
        ;; Callback implementations
        cell-callbacks
        {:render-content render-effect-content
         :get-background get-effect-background
         :get-border-type get-effect-border-type
         
         ;; Click toggles the effect
         :on-click (fn [cell-key cell-state]
                     (when (has-effect? cell-state)
                       (when-let [grid @!grid-ref]
                         ((:toggle-cell! grid) (first cell-key) (second cell-key)))))
         
         ;; Right-click shows context menu
         :on-right-click (fn [cell-key cell-state panel event]
                           (when-let [grid @!grid-ref]
                             (let [menu (create-effect-context-menu
                                         cell-key cell-state grid
                                         on-new-effect on-edit-effect)]
                               (base-grid/show-context-menu! menu panel event))))
                        
                        ;; Double-click opens dialog
                        :on-double-click (fn [cell-key cell-state]
                                          (when-let [grid @!grid-ref]
                                            (if (has-effect? cell-state)
                                              ;; Has effect - open edit dialog
                                              (when on-edit-effect
                                                (on-edit-effect cell-key cell-state))
                                              ;; Empty cell - open new effect dialog
                                              (when on-new-effect
                                                (on-new-effect cell-key)))))
                        
                        ;; Drag & drop
                        :get-drag-data (fn [cell-state]
                          (when (has-effect? cell-state)
                            (assoc (get-effect-drag-data cell-state)
                                   :cell-key nil))) ;; cell-key set per-cell
         
         :accept-drop? accept-effect-drop?
         
         :on-drop (fn [cell-key transfer-data]
                    (when-let [grid @!grid-ref]
                      (let [source-key (:cell-key transfer-data)
                            ;; Deserialize params (reconstruct modulators from config)
                            effect-data (update (:data transfer-data) :params deserialize-params-from-drag)]
                        ;; Set the effect on target
                        ((:set-cell-effect! grid) (first cell-key) (second cell-key) effect-data)
                        ;; Clear source if it exists (MOVE not COPY)
                        (when source-key
                          ((:clear-cell! grid) (first source-key) (second source-key)))
                        true)))
         
         :create-ghost create-effect-ghost
         
         ;; Copy/paste
         :on-copy (fn [cell-key cell-state]
                    (when (has-effect? cell-state)
                      (clipboard/copy-effect-assignment! (:data cell-state))))
         
         :on-paste (fn [cell-key]
                     (when-let [effect-data (clipboard/paste-effect-assignment)]
                       (when-let [grid @!grid-ref]
                         ((:set-cell-effect! grid) (first cell-key) (second cell-key) effect-data))))}
        
        ;; Create the base grid
        base-grid-component (base-grid/create-grid-panel
                             :cols cols
                             :rows rows
                             :cell-callbacks cell-callbacks)
        
        ;; Build the effects grid API
        grid-component
        {:panel (:panel base-grid-component)
         
         :set-cell-effect! 
         (fn [col row effect-data]
           ((:update-cell! base-grid-component) col row
            (fn [state]
              (assoc state 
                     :data effect-data
                     :active (if effect-data 
                               (get effect-data :enabled true)
                               false))))
           (notify-change!))
         
         :get-cell-effect 
         (fn [col row]
           (when-let [state ((:get-cell-state base-grid-component) col row)]
             (:data state)))
         
         :toggle-cell! 
         (fn [col row]
           ((:update-cell! base-grid-component) col row
            (fn [state]
              (if (has-effect? state)
                (update state :active not)
                state)))
           (notify-change!))
         
         :clear-cell! 
         (fn [col row]
           ((:update-cell! base-grid-component) col row
            (fn [_state]
              {:data nil :active false :selected false}))
           (notify-change!))
         
         :get-active-effects 
         (fn []
           (let [effects (atom [])]
             ((:for-each-cell-sorted base-grid-component)
              (fn [_key cell]
                (let [state ((:get-state cell))]
                  (when (and (has-effect? state) (:active state))
                    (swap! effects conj 
                           (fx/make-effect-instance 
                            (get-in state [:data :effect-id])
                            :enabled true
                            :params (get-in state [:data :params])))))))
             @effects))
         
         :apply-to-frame 
         (fn [frame time-ms bpm]
           (when-let [grid @!grid-ref]
             (let [active-effects ((:get-active-effects grid))]
               (reduce
                (fn [f effect-instance]
                  (fx/apply-effect f effect-instance time-ms bpm))
                frame
                active-effects))))
         
         :clear-all! 
         (fn []
           (when-let [grid @!grid-ref]
             ((:for-each-cell base-grid-component)
              (fn [[col row] _cell]
                ((:clear-cell! grid) col row)))))
         
         :get-selected (fn [] ((:get-selected base-grid-component)))
         
         :cells (:cells base-grid-component)}]
    
    ;; Store reference for callbacks
    (reset! !grid-ref grid-component)
    
    grid-component))

;; ============================================================================
;; Demo/Test Functions
;; ============================================================================

(comment
  ;; Test creating an effects grid
  (require '[seesaw.core :as ss])
  
  (def test-grid 
    (create-effects-grid-panel
     :cols 5
     :rows 2
     :on-effects-change (fn [effects]
                          (println "Active effects:" (count effects)))))
  
  ;; Add a test effect
  ((:set-cell-effect! test-grid) 0 0 
   (make-effect-data :scale :enabled true :params {:x-scale 1.5 :y-scale 1.5}))
  
  ;; Show in a frame
  (-> (ss/frame :title "Effects Grid Test"
                :content (:panel test-grid)
                :size [500 :by 200])
      ss/show!)
  
  ;; Get active effects
  ((:get-active-effects test-grid))
  )
