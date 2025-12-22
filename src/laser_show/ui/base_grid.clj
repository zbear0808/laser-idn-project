(ns laser-show.ui.base-grid
  "Shared grid infrastructure for cue and effect grids.
   Provides common functionality for cell-based grid UIs:
   - Cell panel creation with hover/selection styling
   - Grid panel layout with MIG
   - Selection management
   - Keyboard shortcuts (Ctrl+C, Ctrl+V)
   - Drag & drop infrastructure
   
   Usage:
   (create-grid-panel
     :cols 5
     :rows 4
     :cell-config {...}
     :callbacks {...})"
  (:require [seesaw.core :as ss]
            [seesaw.mig :as mig]
            [laser-show.ui.colors :as colors]
            [laser-show.ui.layout :as layout]
            [laser-show.ui.drag-drop :as dnd])
  (:import [java.awt Color Dimension Font BasicStroke Graphics2D RenderingHints]
           [java.awt.event MouseAdapter MouseEvent KeyEvent InputEvent ActionEvent]
           [javax.swing BorderFactory JPanel JPopupMenu JMenuItem KeyStroke AbstractAction]
           [javax.swing.border Border]))

;; ============================================================================
;; Custom Dashed Border
;; ============================================================================

(defn create-dashed-border
  "Create a dashed border for empty cells."
  [color thickness]
  (proxy [Border] []
    (getBorderInsets [_c]
      (java.awt.Insets. thickness thickness thickness thickness))
    (isBorderOpaque []
      false)
    (paintBorder [_c g x y width height]
      (let [g2d ^Graphics2D (.create g)]
        (.setRenderingHint g2d RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
        (.setColor g2d color)
        (.setStroke g2d (BasicStroke. (float thickness) 
                                       BasicStroke/CAP_BUTT 
                                       BasicStroke/JOIN_MITER 
                                       10.0 
                                       (float-array [4.0 4.0]) 
                                       0.0))
        (.drawRect g2d x y (dec width) (dec height))
        (.dispose g2d)))))

;; ============================================================================
;; Border Factories
;; ============================================================================

(defn make-borders
  "Create a set of borders for different cell states."
  []
  {:empty (create-dashed-border colors/border-light layout/cell-border-width)
   :assigned (BorderFactory/createLineBorder colors/border-dark layout/cell-border-width)
   :hover (BorderFactory/createLineBorder colors/border-highlight layout/cell-border-hover-width)
   :drop-target (dnd/create-highlight-border)})

;; ============================================================================
;; Cell Label Component
;; ============================================================================

(defn create-cell-label
  "Create the label showing the cell's display text."
  [& {:keys [text font-size]
      :or {text "" font-size 11}}]
  (ss/label :text text
            :font (Font. "SansSerif" Font/BOLD font-size)
            :foreground Color/WHITE
            :halign :center
            :valign :center))

;; ============================================================================
;; Generic Cell Panel
;; ============================================================================

(defn create-cell-panel
  "Create a single grid cell panel with common functionality.
   
   Parameters:
   - cell-key: [col row] identifier for this cell
   - callbacks: Map of callback functions:
     - :on-click (fn [cell-key cell-state]) - left click handler
     - :on-double-click (fn [cell-key cell-state]) - double-click handler
     - :on-right-click (fn [cell-key cell-state panel event]) - right click handler (shows context menu)
     - :render-content (fn [cell-state] -> text) - returns display text for cell
     - :get-background (fn [cell-state] -> Color) - returns background color
     - :get-border-type (fn [cell-state] -> :empty/:assigned) - returns border type
     - :get-drag-data (fn [cell-state] -> data-map or nil) - returns data for drag, nil if not draggable
     - :accept-drop? (fn [cell-key transfer-data] -> bool) - whether to accept drop
     - :on-drop (fn [cell-key transfer-data] -> bool) - handle drop, return success
     - :create-ghost (fn [panel cell-state] -> Image) - create drag ghost image
   
   Returns map with:
   - :panel - the JPanel
   - :key - the cell key
   - :state - atom containing cell state
   - :set-state! - function to update state
   - :get-state - function to get current state
   - :update! - function to refresh appearance"
  [cell-key callbacks]
  (let [!cell-state (atom {:data nil
                           :selected false})
        borders (make-borders)
        
        name-label (create-cell-label)
        
        panel (ss/border-panel
               :center name-label
               :background colors/cell-empty
               :border (:empty borders))
        
        get-current-border (fn []
                             (let [state @!cell-state
                                   border-type (if-let [f (:get-border-type callbacks)]
                                                 (f state)
                                                 (if (:data state) :assigned :empty))]
                               (get borders border-type (:empty borders))))
        
        update-appearance! (fn []
                             (let [state @!cell-state
                                   bg-color (if-let [f (:get-background callbacks)]
                                              (f state)
                                              (if (:data state) colors/cell-assigned colors/cell-empty))
                                   text (if-let [f (:render-content callbacks)]
                                          (f state)
                                          "")
                                   border (get-current-border)]
                               (ss/config! panel :background bg-color)
                               (ss/config! panel :border border)
                               (ss/config! name-label :text text)))]
    
    (.setPreferredSize panel (Dimension. layout/cell-width layout/cell-height))
    (.setMinimumSize panel (Dimension. layout/cell-min-width layout/cell-min-height))
    
    ;; Mouse listeners for click and hover
    (.addMouseListener panel
                       (proxy [MouseAdapter] []
                         (mouseClicked [^MouseEvent e]
                           (cond
                             ;; Right-click (button 3) - show context menu
                             (= (.getButton e) MouseEvent/BUTTON3)
                             (when-let [f (:on-right-click callbacks)]
                               (f cell-key @!cell-state panel e))
                             
                             ;; Double-click (left button, 2 clicks) - trigger double-click callback
                             (and (= (.getButton e) MouseEvent/BUTTON1)
                                  (= (.getClickCount e) 2))
                             (when-let [f (:on-double-click callbacks)]
                               (f cell-key @!cell-state))
                             
                             ;; Single left-click - normal click behavior
                             :else
                             (when-let [f (:on-click callbacks)]
                               (f cell-key @!cell-state))))
                         
                         (mouseEntered [^MouseEvent _e]
                           (.setBorder panel (:hover borders)))
                         
                         (mouseExited [^MouseEvent _e]
                           (.setBorder panel (get-current-border)))))
    
    ;; Set up drag support if get-drag-data callback provided
    (when (:get-drag-data callbacks)
      (dnd/make-draggable! panel
        {:data-fn (fn []
                    (when-let [f (:get-drag-data callbacks)]
                      (f @!cell-state)))
         :ghost-fn (fn [comp data]
                     (if-let [f (:create-ghost callbacks)]
                       (f comp @!cell-state)
                       (dnd/create-ghost-image comp :opacity 0.6)))
         :enabled-fn (fn []
                       (when-let [f (:get-drag-data callbacks)]
                         (some? (f @!cell-state))))}))
    
    ;; Set up drop target if accept-drop? callback provided
    (when (:accept-drop? callbacks)
      (dnd/make-drop-target! panel
        {:accept-fn (fn [transfer-data]
                      (if-let [f (:accept-drop? callbacks)]
                        (f cell-key transfer-data)
                        false))
         :on-drop (fn [transfer-data]
                    (if-let [f (:on-drop callbacks)]
                      (f cell-key transfer-data)
                      false))
         :on-drag-enter (fn [_data]
                          (.setBorder panel (:drop-target borders)))
         :on-drag-exit (fn []
                         (.setBorder panel (get-current-border)))}))
    
    {:panel panel
     :key cell-key
     :state !cell-state
     :set-state! (fn [new-state]
                   (reset! !cell-state new-state)
                   (update-appearance!))
     :update-state! (fn [f & args]
                      (apply swap! !cell-state f args)
                      (update-appearance!))
     :get-state (fn [] @!cell-state)
     :update! update-appearance!}))

;; ============================================================================
;; Grid Panel
;; ============================================================================

(defn create-grid-panel
  "Create a grid panel with cells.
   
   Parameters:
   - :cols - number of columns (default from layout config)
   - :rows - number of rows (default from layout config)
   - :cell-callbacks - callbacks passed to each cell (see create-cell-panel)
   - :on-selection-change - (fn [old-key new-key]) called when selection changes
   
   Returns a map with:
   - :panel - the JPanel containing the grid
   - :cells - atom containing map of [col row] -> cell component
   - :get-cell - (fn [col row]) get cell at position
   - :set-cell-state! - (fn [col row state]) set cell state
   - :get-cell-state - (fn [col row]) get cell state
   - :selected-cell - atom containing currently selected [col row] or nil
   - :select-cell! - (fn [col row]) select a cell
   - :clear-selection! - (fn []) clear selection
   - :for-each-cell - (fn [f]) call f with each [key cell]
   - :copy-selected! - (fn [on-copy]) copy selected cell
   - :paste-to-selected! - (fn [on-paste]) paste to selected cell"
  [& {:keys [cols rows cell-callbacks on-selection-change]
      :or {cols layout/default-grid-cols
           rows layout/default-grid-rows}}]
  (let [!cells (atom {})
        !selected (atom nil)
        
        layout-str (str "wrap " cols ", gap " layout/cell-gap ", insets " layout/panel-insets)
        
        grid-panel (mig/mig-panel
                    :constraints [layout-str]
                    :background colors/background-dark)
        
        select-cell! (fn [cell-key]
                       (let [old-key @!selected]
                         ;; Deselect old cell
                         (when old-key
                           (when-let [old-cell (get @!cells old-key)]
                             ((:update-state! old-cell) assoc :selected false)))
                         ;; Select new cell
                         (when cell-key
                           (when-let [new-cell (get @!cells cell-key)]
                             ((:update-state! new-cell) assoc :selected true)))
                         (reset! !selected cell-key)
                         (when on-selection-change
                           (on-selection-change old-key cell-key))))
        
        ;; Wrap on-click to handle selection
        wrapped-callbacks (assoc cell-callbacks
                                 :on-click (fn [cell-key cell-state]
                                             (select-cell! cell-key)
                                             (when-let [f (:on-click cell-callbacks)]
                                               (f cell-key cell-state))))]
    
    ;; Create cells in row-major order
    (doseq [row (range rows)
            col (range cols)]
      (let [cell-key [col row]
            cell (create-cell-panel cell-key wrapped-callbacks)]
        (swap! !cells assoc cell-key cell)
        (ss/add! grid-panel (:panel cell))))
    
    ;; Set preferred size
    (let [total-width (+ (* cols (+ layout/cell-width layout/cell-gap)) (* 2 layout/panel-insets))
          total-height (+ (* rows (+ layout/cell-height layout/cell-gap)) (* 2 layout/panel-insets))]
      (.setPreferredSize grid-panel (Dimension. total-width total-height))
      (.setMinimumSize grid-panel (Dimension. total-width total-height)))
    
    ;; Keyboard shortcuts
    (let [input-map (.getInputMap grid-panel JPanel/WHEN_IN_FOCUSED_WINDOW)
          action-map (.getActionMap grid-panel)
          
          ctrl-c (KeyStroke/getKeyStroke KeyEvent/VK_C InputEvent/CTRL_DOWN_MASK)
          ctrl-v (KeyStroke/getKeyStroke KeyEvent/VK_V InputEvent/CTRL_DOWN_MASK)]
      
      (.put input-map ctrl-c "copy-cell")
      (.put action-map "copy-cell"
            (proxy [AbstractAction] []
              (actionPerformed [^ActionEvent _e]
                (when-let [selected-key @!selected]
                  (when-let [cell (get @!cells selected-key)]
                    (when-let [f (:on-copy cell-callbacks)]
                      (f selected-key ((:get-state cell)))))))))
      
      (.put input-map ctrl-v "paste-cell")
      (.put action-map "paste-cell"
            (proxy [AbstractAction] []
              (actionPerformed [^ActionEvent _e]
                (when-let [selected-key @!selected]
                  (when-let [f (:on-paste cell-callbacks)]
                    (f selected-key)))))))
    
    {:panel grid-panel
     :cells !cells
     :selected-cell !selected
     
     :get-cell (fn [col row]
                 (get @!cells [col row]))
     
     :set-cell-state! (fn [col row state]
                        (when-let [cell (get @!cells [col row])]
                          ((:set-state! cell) state)))
     
     :get-cell-state (fn [col row]
                       (when-let [cell (get @!cells [col row])]
                         ((:get-state cell))))
     
     :update-cell! (fn [col row f & args]
                     (when-let [cell (get @!cells [col row])]
                       (apply (:update-state! cell) f args)))
     
     :select-cell! (fn [col row]
                     (select-cell! (when col [col row])))
     
     :clear-selection! (fn []
                         (select-cell! nil))
     
     :get-selected (fn []
                     @!selected)
     
     :for-each-cell (fn [f]
                      (doseq [[k cell] @!cells]
                        (f k cell)))
     
     :for-each-cell-sorted (fn [f]
                             (let [sorted-keys (sort-by (fn [[col row]] [row col]) (keys @!cells))]
                               (doseq [k sorted-keys]
                                 (f k (get @!cells k)))))}))

;; ============================================================================
;; Context Menu Helpers
;; ============================================================================

(defn create-menu-item
  "Create a JMenuItem with an action handler."
  [text handler & {:keys [enabled?] :or {enabled? true}}]
  (let [item (JMenuItem. text)]
    (.setEnabled item enabled?)
    (.addActionListener item
                        (reify java.awt.event.ActionListener
                          (actionPerformed [_ _e]
                            (handler))))
    item))

(defn show-context-menu!
  "Show a popup menu at the event location."
  [^JPopupMenu menu ^JPanel panel ^MouseEvent event]
  (.show menu panel (.getX event) (.getY event)))
