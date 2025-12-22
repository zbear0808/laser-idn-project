(ns laser-show.ui.grid
  "Launchpad-style grid UI for triggering laser animations."
  (:require [seesaw.core :as ss]
            [seesaw.border :as border]
            [seesaw.color :as sc]
            [seesaw.mig :as mig]
            [laser-show.animation.types :as t]
            [laser-show.animation.presets :as presets]
            [laser-show.ui.preview :as preview]
            [laser-show.ui.colors :as colors]
            [laser-show.ui.layout :as layout]
            [laser-show.ui.drag-drop :as dnd]
            [laser-show.state.clipboard :as clipboard])
  (:import [java.awt Color Dimension Font BasicStroke Graphics2D RenderingHints]
           [java.awt.event MouseAdapter MouseEvent KeyEvent InputEvent ActionEvent]
           [javax.swing BorderFactory JPanel JPopupMenu JMenuItem KeyStroke AbstractAction]
           [javax.swing.border Border]))

;; ============================================================================
;; Grid State
;; ============================================================================

(defonce !grid-state
  (atom {:grid-size [layout/default-grid-cols layout/default-grid-rows]
         :cells {}
         :active-cell nil
         :selected-cell nil}))

;; ============================================================================
;; Custom Empty Cell Border
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
;; Context Menu
;; ============================================================================

(defn create-cell-context-menu
  "Create a context menu for a grid cell."
  [cell-key cell-state on-copy on-paste on-clear]
  (let [popup (JPopupMenu.)
        has-preset? (boolean (:preset-id cell-state))
        can-paste? (clipboard/can-paste-cell-assignment?)
        
        copy-item (JMenuItem. "Copy")
        paste-item (JMenuItem. "Paste")
        clear-item (JMenuItem. "Clear")]
    
    (.setEnabled copy-item has-preset?)
    (.setEnabled paste-item can-paste?)
    (.setEnabled clear-item has-preset?)
    
    (.addActionListener copy-item
                        (reify java.awt.event.ActionListener
                          (actionPerformed [_ _e]
                            (on-copy cell-key cell-state))))
    
    (.addActionListener paste-item
                        (reify java.awt.event.ActionListener
                          (actionPerformed [_ _e]
                            (on-paste cell-key))))
    
    (.addActionListener clear-item
                        (reify java.awt.event.ActionListener
                          (actionPerformed [_ _e]
                            (on-clear cell-key))))
    
    (.add popup copy-item)
    (.add popup paste-item)
    (.addSeparator popup)
    (.add popup clear-item)
    
    popup))

;; ============================================================================
;; Cell Component
;; ============================================================================

(defn- create-cell-label
  "Create the label showing the animation name."
  [text]
  (ss/label :text (or text "")
            :font (Font. "SansSerif" Font/BOLD 11)
            :foreground Color/WHITE
            :halign :center
            :valign :center))

(defn- create-cell-panel
  "Create a single grid cell panel.
   
   Parameters:
   - col, row: Grid coordinates
   - on-click: Called when cell is left-clicked
   - on-right-click: Called when cell is right-clicked
   - on-copy, on-paste, on-clear: Context menu actions
   - on-drag-drop: Called when a preset is dropped from another cell: (on-drag-drop source-key preset-id)"
  [col row on-click on-right-click on-copy on-paste on-clear on-drag-drop]
  (let [cell-key [col row]
        !cell-state (atom {:preset-id nil
                          :animation nil
                          :active false
                          :selected false})
        
        name-label (create-cell-label "")
        
        empty-border (create-dashed-border colors/border-light layout/cell-border-width)
        assigned-border (BorderFactory/createLineBorder colors/border-dark layout/cell-border-width)
        hover-border (BorderFactory/createLineBorder colors/border-highlight layout/cell-border-hover-width)
        
        panel (ss/border-panel
               :center name-label
               :background colors/cell-empty
               :border empty-border)
        
        get-current-border (fn []
                             (let [{:keys [preset-id]} @!cell-state]
                               (if preset-id assigned-border empty-border)))
        
        update-appearance! (fn []
                             (let [{:keys [preset-id active selected]} @!cell-state
                                   bg-color (cond
                                              active colors/cell-active
                                              selected colors/cell-selected
                                              preset-id (if-let [preset (presets/get-preset preset-id)]
                                                          (colors/get-category-color (:category preset))
                                                          colors/cell-assigned)
                                              :else colors/cell-empty)
                                   border (if preset-id assigned-border empty-border)]
                               (ss/config! panel :background bg-color)
                               (ss/config! panel :border border)
                               (ss/config! name-label :text (if preset-id
                                                             (:name (presets/get-preset preset-id))
                                                             ""))))]
    
    (.setPreferredSize panel (Dimension. 80 60))
    (.setMinimumSize panel (Dimension. 60 50))
    
    ;; Mouse listeners for click and hover
    (.addMouseListener panel
                       (proxy [MouseAdapter] []
                         (mouseClicked [^MouseEvent e]
                           (cond
                             (= (.getButton e) MouseEvent/BUTTON3)
                             (let [popup (create-cell-context-menu 
                                          cell-key 
                                          @!cell-state
                                          on-copy
                                          on-paste
                                          on-clear)]
                               (.show popup panel (.getX e) (.getY e)))
                             
                             :else
                             (on-click cell-key @!cell-state)))
                         
                         (mouseEntered [^MouseEvent _e]
                           (.setBorder panel hover-border))
                         
                         (mouseExited [^MouseEvent _e]
                           (.setBorder panel (get-current-border)))))
    
    ;; Set up drag support - cell can be dragged if it has a preset
    (dnd/make-draggable! panel
      {:data-fn (fn []
                  (when-let [preset-id (:preset-id @!cell-state)]
                    {:type :cue-cell
                     :source-id :cue-grid
                     :cell-key cell-key
                     :data {:preset-id preset-id}}))
       :ghost-fn (fn [comp _data]
                   (let [{:keys [preset-id]} @!cell-state
                         color (if-let [preset (presets/get-preset preset-id)]
                                 (colors/get-category-color (:category preset))
                                 colors/cell-assigned)]
                     (dnd/create-simple-ghost-image 
                      (.getWidth comp) (.getHeight comp)
                      color
                      :opacity 0.7
                      :text (:name (presets/get-preset preset-id)))))
       :on-drag-start (fn [_data]
                        (println "Drag started from cell" cell-key))
       :on-drag-end (fn [_data success?]
                      (println "Drag ended from cell" cell-key "success:" success?))
       :enabled-fn (fn [] (some? (:preset-id @!cell-state)))})
    
    ;; Set up drop target - cell accepts drops from other cue cells
    (dnd/make-drop-target! panel
      {:accept-fn (fn [transfer-data]
                    (and transfer-data
                         (= (:type transfer-data) :cue-cell)
                         ;; Don't allow dropping on self
                         (not= (:cell-key transfer-data) cell-key)))
       :on-drop (fn [transfer-data]
                  (let [source-key (:cell-key transfer-data)
                        preset-id (get-in transfer-data [:data :preset-id])]
                    (when (and source-key preset-id on-drag-drop)
                      (on-drag-drop source-key preset-id)
                      true)))
       :on-drag-enter (fn [_data]
                        (.setBorder panel (dnd/create-highlight-border)))
       :on-drag-exit (fn []
                       (.setBorder panel (get-current-border)))})
    
    {:panel panel
     :key cell-key
     :state !cell-state
     :set-preset! (fn [preset-id]
                    ;; Clear both preset-id and animation together
                    ;; When preset-id is nil, animation should also be nil
                    (swap! !cell-state assoc 
                           :preset-id preset-id
                           :animation (when preset-id
                                        (presets/create-animation-from-preset preset-id)))
                    (update-appearance!))
     :set-active! (fn [active]
                    (swap! !cell-state assoc :active active)
                    (update-appearance!))
     :set-selected! (fn [selected]
                      (swap! !cell-state assoc :selected selected)
                      (update-appearance!))
     :get-animation (fn [] (:animation @!cell-state))
     :get-state (fn [] @!cell-state)
     :update! update-appearance!}))

;; ============================================================================
;; Grid Panel
;; ============================================================================

(defn create-grid-panel
  "Create the main grid panel with cells.
   Returns a map with :panel and control functions."
  [& {:keys [cols rows on-cell-click on-cell-right-click on-copy on-paste on-clear]
      :or {cols layout/default-grid-cols
           rows layout/default-grid-rows
           on-cell-click (fn [k s] (println "Cell clicked:" k))
           on-cell-right-click (fn [k s] (println "Cell right-clicked:" k))
           on-copy (fn [k s] (println "Copy:" k))
           on-paste (fn [k] (println "Paste:" k))
           on-clear (fn [k] (println "Clear:" k))}}]
  (let [!cells (atom {})
        !selected (atom nil)
        !active (atom nil)
        
        ;; Use simple wrap-based layout instead of explicit cell positioning
        layout-str (str "wrap " cols ", gap " layout/cell-gap ", insets " layout/panel-insets)
        
        grid-panel (mig/mig-panel
                    :constraints [layout-str]
                    :background colors/background-dark)
        
        handle-click (fn [cell-key cell-state]
                       (when-let [prev-selected @!selected]
                         (when-let [prev-cell (get @!cells prev-selected)]
                           ((:set-selected! prev-cell) false)))
                       (when-let [cell (get @!cells cell-key)]
                         ((:set-selected! cell) true)
                         (reset! !selected cell-key))
                       (on-cell-click cell-key cell-state))
        
        handle-right-click (fn [cell-key cell-state]
                             (on-cell-right-click cell-key cell-state))
        
        handle-copy (fn [cell-key cell-state]
                      (on-copy cell-key cell-state))
        
        handle-paste (fn [cell-key]
                       (on-paste cell-key))
        
        handle-clear (fn [cell-key]
                       (on-clear cell-key))
        
        copy-selected! (fn []
                         (when-let [selected-key @!selected]
                           (when-let [cell (get @!cells selected-key)]
                             (let [state ((:get-state cell))]
                               (handle-copy selected-key state)))))
        
        paste-to-selected! (fn []
                             (when-let [selected-key @!selected]
                               (handle-paste selected-key)))
        
        ;; Handler for drag-drop operations (move preset from source to target)
        handle-drag-drop (fn [target-cell-key source-key preset-id]
                           ;; Set the preset on the target cell
                           (when-let [target-cell (get @!cells target-cell-key)]
                             ((:set-preset! target-cell) preset-id))
                           ;; Clear the source cell
                           (when-let [source-cell (get @!cells source-key)]
                             ((:set-preset! source-cell) nil))
                           (println "Moved preset" preset-id "from" source-key "to" target-cell-key))]
    
    ;; Add cells in row-major order (row 0 first, then row 1, etc.)
    ;; With wrap constraint, cells are added left-to-right, top-to-bottom
    (doseq [row (range rows)
            col (range cols)]
      (let [cell-key [col row]
            ;; Create drag-drop handler that knows this cell's key
            cell-drag-drop (fn [source-key preset-id]
                             (handle-drag-drop cell-key source-key preset-id))
            cell (create-cell-panel col row handle-click handle-right-click
                                    handle-copy handle-paste handle-clear cell-drag-drop)]
        (swap! !cells assoc [col row] cell)
        ;; Use empty constraint - cells have preferred size set already
        (ss/add! grid-panel (:panel cell))))
    
    ;; Set preferred size to ensure all cells are visible
    (let [total-width (+ (* cols (+ layout/cell-width layout/cell-gap)) (* 2 layout/panel-insets))
          total-height (+ (* rows (+ layout/cell-height layout/cell-gap)) (* 2 layout/panel-insets))]
      (.setPreferredSize grid-panel (Dimension. total-width total-height))
      (.setMinimumSize grid-panel (Dimension. total-width total-height)))
    
    ;; Add keyboard shortcuts for copy/paste
    (let [input-map (.getInputMap grid-panel JPanel/WHEN_IN_FOCUSED_WINDOW)
          action-map (.getActionMap grid-panel)
          
          ctrl-c (KeyStroke/getKeyStroke KeyEvent/VK_C InputEvent/CTRL_DOWN_MASK)
          ctrl-v (KeyStroke/getKeyStroke KeyEvent/VK_V InputEvent/CTRL_DOWN_MASK)]
      
      (.put input-map ctrl-c "copy-cell")
      (.put action-map "copy-cell"
            (proxy [AbstractAction] []
              (actionPerformed [^ActionEvent _e]
                (copy-selected!))))
      
      (.put input-map ctrl-v "paste-cell")
      (.put action-map "paste-cell"
            (proxy [AbstractAction] []
              (actionPerformed [^ActionEvent _e]
                (paste-to-selected!)))))
    
    {:panel grid-panel
     :cells !cells
     
     :set-cell-preset! (fn [col row preset-id]
                         (when-let [cell (get @!cells [col row])]
                           ((:set-preset! cell) preset-id)))
     
     :set-active-cell! (fn [col row]
                         (when-let [prev @!active]
                           (when-let [prev-cell (get @!cells prev)]
                             ((:set-active! prev-cell) false)))
                         (when (and col row)
                           (when-let [cell (get @!cells [col row])]
                             ((:set-active! cell) true)))
                         (reset! !active (when col [col row])))
     
     :get-cell-animation (fn [col row]
                           (when-let [cell (get @!cells [col row])]
                             ((:get-animation cell))))
     
     :get-cell-state (fn [col row]
                       (when-let [cell (get @!cells [col row])]
                         ((:get-state cell))))
     
     :get-selected-cell (fn [] @!selected)
     
     :clear-all! (fn []
                   (doseq [[_ cell] @!cells]
                     ((:set-preset! cell) nil)))
     
     :copy-selected! copy-selected!
     :paste-to-selected! paste-to-selected!}))

;; ============================================================================
;; Preset Palette (for selecting presets)
;; ============================================================================

(defn create-preset-palette
  "Create a panel showing available presets for assignment."
  [on-preset-select]
  (let [preset-buttons (for [preset presets/all-presets]
                         (let [[r g b] (or (get-in presets/categories [(:category preset) :color])
                                           [100 100 100])
                               btn (ss/button
                                    :text (:name preset)
                                    :font (Font. "SansSerif" Font/PLAIN 10)
                                    :background (Color. r g b)
                                    :foreground Color/WHITE)]
                           (ss/listen btn :action (fn [_] (on-preset-select (:id preset))))
                           btn))]
    (ss/scrollable
     (mig/mig-panel
      :constraints ["wrap 2, gap 5, insets 10"]
      :items (mapv (fn [btn] [btn "grow, w 100!"]) preset-buttons)
      :background (Color. 45 45 45))
     :hscroll :never)))
