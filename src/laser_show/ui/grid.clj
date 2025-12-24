(ns laser-show.ui.grid
  "Launchpad-style grid UI for triggering laser animations.
   Refactored to use Uni-directional Data Flow."
  (:require [seesaw.core :as ss]
            [seesaw.border :as border]
            [seesaw.mig :as mig]
            [laser-show.animation.presets :as presets]
            [laser-show.ui.colors :as colors]
            [laser-show.ui.layout :as layout]
            [laser-show.ui.drag-drop :as dnd])
  (:import [java.awt Color Dimension Font BasicStroke Graphics2D RenderingHints BorderLayout]
           [java.awt.event MouseAdapter MouseEvent KeyEvent InputEvent ActionEvent]
           [javax.swing BorderFactory JPanel JPopupMenu JMenuItem KeyStroke AbstractAction JLabel]
           [javax.swing.border Border]))

;; ============================================================================
;; Custom Empty Cell Border (No changes, helper)
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
  [cell-key cell-data dispatch!]
  (let [popup (JPopupMenu.)
        has-preset? (boolean (:preset-id cell-data))
        ;; Simple placeholders for copy/paste dispatch
        
        copy-item (JMenuItem. "Copy")
        paste-item (JMenuItem. "Paste")
        clear-item (JMenuItem. "Clear")]
    
    (.setEnabled copy-item has-preset?)
    (.setEnabled paste-item true) ;; Assume always can paste for now
    (.setEnabled clear-item has-preset?)
    
    (.addActionListener copy-item
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _e]
          ;; Dispatch copy event
          (dispatch! [:clipboard/copy-cell cell-key]))))
    
    (.addActionListener paste-item
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _e]
          (dispatch! [:clipboard/paste-cell cell-key]))))
    
    (.addActionListener clear-item
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _e]
          (dispatch! [:grid/clear-cell (first cell-key) (second cell-key)]))))
    
    (.add popup copy-item)
    (.add popup paste-item)
    (.addSeparator popup)
    (.add popup clear-item)
    
    popup))

;; ============================================================================
;; Cell Component
;; ============================================================================

(defn- create-cell-label
  [text]
  (ss/label :text (or text "")
            :font (Font. "SansSerif" Font/BOLD 11)
            :foreground Color/WHITE
            :halign :center
            :valign :center))

(defn- create-active-indicator
  "Create a small green circle indicator for active state."
  []
  (let [indicator-size 10
        indicator (proxy [JPanel] []
                    (paintComponent [^Graphics2D g]
                      (proxy-super paintComponent g)
                      (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
                      (.setColor g (Color. 0 200 100))  ;; Bright green
                      (.fillOval g 2 2 (- indicator-size 4) (- indicator-size 4))))]
    (.setOpaque indicator false)
    (.setPreferredSize indicator (Dimension. indicator-size indicator-size))
    (.setVisible indicator false)
    indicator))

(defn- create-cell-panel
  "Create a single grid cell panel.
   Interactions now dispatch events."
  [col row dispatch!]
  (let [cell-key [col row]
        name-label (create-cell-label "")
        
        empty-border (create-dashed-border colors/border-light layout/cell-border-width)
        assigned-border (BorderFactory/createLineBorder colors/border-dark layout/cell-border-width)
        hover-border (BorderFactory/createLineBorder colors/border-highlight layout/cell-border-hover-width)
        
        ;; Active indicator (green circle in top-right corner)
        active-indicator (create-active-indicator)
        
        ;; Top panel for indicator positioning
        top-panel (doto (JPanel. (BorderLayout.))
                    (.setOpaque false)
                    (.add active-indicator BorderLayout/EAST))
        
        panel (ss/border-panel
               :north top-panel
               :center name-label
               :background colors/cell-empty
               :border empty-border)
        
        ;; Mutable state for UI interactions (hover) - not app state
        !ui-state (atom {:hover false})
        !last-render-state (atom nil)] ; Cache to avoid unnecessary Swing updates
    
    (.setPreferredSize panel (Dimension. 80 60))
    (.setMinimumSize panel (Dimension. 60 50))
    
    ;; Mouse listeners
    (.addMouseListener panel
      (proxy [MouseAdapter] []
        (mouseClicked [^MouseEvent e]
          (cond
            (= (.getButton e) MouseEvent/BUTTON3)
            (let [popup (create-cell-context-menu 
                         cell-key 
                         (:data @!last-render-state) ;; Use last rendered data
                         dispatch!)]
              (.show popup panel (.getX e) (.getY e)))
            
            :else
            (do
              ;; Dispatch selection AND trigger
              (dispatch! [:grid/select-cell col row])
              (dispatch! [:grid/trigger-cell col row]))))
        
        (mouseEntered [^MouseEvent _e]
          (swap! !ui-state assoc :hover true)
          (.setBorder panel hover-border))
        
        (mouseExited [^MouseEvent _e]
          (swap! !ui-state assoc :hover false)
          ;; Restore border based on assigned state only
          (let [{:keys [preset-id]} (:data @!last-render-state)]
             (.setBorder panel (if preset-id assigned-border empty-border))))))
    
    ;; Drag and Drop - Enable dragging cells with simple ghost image
    (dnd/make-cell-draggable! panel
      {:cell-key cell-key
       :get-data-fn (fn [] (:data @!last-render-state))
       :data-type :cue-cell
       :source-id :main-grid
       :ghost-color colors/cell-assigned})
    
    ;; Make cell a drop target
    (dnd/make-cell-drop-target! panel
      {:cell-key cell-key
       :accept-types #{:cue-cell}
       :source-id :main-grid
       :on-drop-fn (fn [source-key _data]
                     (when source-key
                       (dispatch! [:grid/move-cell 
                                   (first source-key) (second source-key)
                                   col row]))
                     true)
       :default-border empty-border})
    
    {:panel panel
     :key cell-key
     
     ;; Update Function: Called when global state changes
     :update-view! 
     (fn [grid-state active-cell selected-cell]
       (let [cell-data (get-in grid-state [:cells cell-key])
             preset-id (:preset-id cell-data)
             active?   (= active-cell cell-key)
             selected? (= selected-cell cell-key)
             
             new-render-state {:data cell-data 
                               :active active? 
                               :selected selected?}]
         
         (when (not= new-render-state @!last-render-state)
           (reset! !last-render-state new-render-state)
           
           (let [bg-color (cond
                            active? colors/cell-active
                            selected? colors/cell-selected
                            preset-id (if-let [preset (presets/get-preset preset-id)]
                                        (colors/get-category-color (:category preset))
                                        colors/cell-assigned)
                            :else colors/cell-empty)
                 border (if preset-id assigned-border empty-border)]
             
             ;; Show/hide active indicator (green circle)
             (.setVisible active-indicator active?)
             
             (ss/config! panel :background bg-color)
             (when-not (:hover @!ui-state)
               (ss/config! panel :border border))
             (ss/config! name-label :text (if preset-id
                                            (:name (presets/get-preset preset-id))
                                            ""))))))}))

;; ============================================================================
;; Grid Panel
;; ============================================================================

(defn create-grid-panel
  "Create the main grid panel.
   
   arguments:
   - dispatch!: function (fn [event-vector])
   - cols, rows (optional)
   
   returns map:
   {:panel seesaw-panel
    :update-view! (fn [app-state] ...)}"
  [dispatch! & {:keys [cols rows]
                :or {cols layout/default-grid-cols
                     rows layout/default-grid-rows}}]
  (let [;; We still store refernece to cells to update them
        !cell-components (atom {}) 
        
        layout-str (str "wrap " cols ", gap " layout/cell-gap ", insets " layout/panel-insets)
        grid-panel (mig/mig-panel
                    :constraints [layout-str]
                    :background colors/background-dark)]
    
    ;; Create cells
    (doseq [row (range rows)
            col (range cols)]
      (let [cell-comp (create-cell-panel col row dispatch!)]
        (swap! !cell-components assoc [col row] cell-comp)
        (ss/add! grid-panel (:panel cell-comp))))
    
    ;; Set size
     (let [total-width (+ (* cols (+ layout/cell-width layout/cell-gap)) (* 2 layout/panel-insets))
           total-height (+ (* rows (+ layout/cell-height layout/cell-gap)) (* 2 layout/panel-insets))]
       (.setPreferredSize grid-panel (Dimension. total-width total-height))
       (.setMinimumSize grid-panel (Dimension. total-width total-height)))
    
    ;; Keyboard Shortcuts (Copy/Paste)
    (let [input-map (.getInputMap grid-panel JPanel/WHEN_IN_FOCUSED_WINDOW)
          action-map (.getActionMap grid-panel)
          ctrl-c (KeyStroke/getKeyStroke KeyEvent/VK_C InputEvent/CTRL_DOWN_MASK)
          ctrl-v (KeyStroke/getKeyStroke KeyEvent/VK_V InputEvent/CTRL_DOWN_MASK)]
      
      (.put input-map ctrl-c "copy-cell")
      (.put action-map "copy-cell"
            (proxy [AbstractAction] []
              (actionPerformed [_ _]
                (dispatch! [:clipboard/copy-selected]))))
      
      (.put input-map ctrl-v "paste-cell")
      (.put action-map "paste-cell"
            (proxy [AbstractAction] []
              (actionPerformed [_ _]
                (dispatch! [:clipboard/paste-to-selected])))))
    
    {:panel grid-panel
     ;; This function is called by the parent (window) whenever app-state atom changes
     :update-view! (fn [app-state]
                     (let [grid-state (:grid app-state)
                           active-cell (:active-cell grid-state)
                           selected-cell (:selected-cell grid-state)]
                       ;; Update all cells
                       (doseq [[_ cell-comp] @!cell-components]
                         ((:update-view! cell-comp) grid-state active-cell selected-cell))))}))

;; ============================================================================
;; Preset Palette (unchanged, just use dispatch helper)
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
