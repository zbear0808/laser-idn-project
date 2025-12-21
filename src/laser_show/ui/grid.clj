(ns laser-show.ui.grid
  "Launchpad-style grid UI for triggering laser animations."
  (:require [seesaw.core :as ss]
            [seesaw.border :as border]
            [seesaw.color :as sc]
            [seesaw.mig :as mig]
            [laser-show.animation.types :as t]
            [laser-show.animation.presets :as presets]
            [laser-show.ui.preview :as preview])
  (:import [java.awt Color Dimension Font]
           [java.awt.event MouseAdapter MouseEvent]
           [javax.swing BorderFactory JPanel]))

;; ============================================================================
;; Grid State
;; ============================================================================

(defonce grid-state 
  (atom {:grid-size [8 4]           ; columns x rows
         :cells {}                   ; {[col row] -> cell-state}
         :active-cell nil            ; Currently active/playing cell
         :selected-cell nil}))       ; Selected for editing

;; ============================================================================
;; Cell Colors
;; ============================================================================

(def cell-colors
  {:empty (Color. 40 40 40)
   :assigned (Color. 60 60 80)
   :selected (Color. 80 80 120)
   :active (Color. 0 200 100)
   :queued (Color. 200 200 0)})

(defn get-category-color
  "Get the color for a preset category."
  [category]
  (if-let [cat-info (get presets/categories category)]
    (let [[r g b] (:color cat-info)]
      (Color. r g b))
    (:assigned cell-colors)))

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
  "Create a single grid cell panel."
  [col row on-click on-right-click]
  (let [cell-key [col row]
        state-atom (atom {:preset-id nil
                         :animation nil
                         :active false
                         :selected false})
        
        name-label (create-cell-label "")
        
        panel (ss/border-panel
               :center name-label
               :background (:empty cell-colors)
               :border (BorderFactory/createLineBorder (Color. 20 20 20) 1))
        
        update-appearance! (fn []
                             (let [{:keys [preset-id active selected]} @state-atom
                                   bg-color (cond
                                              active (:active cell-colors)
                                              selected (:selected cell-colors)
                                              preset-id (if-let [preset (presets/get-preset preset-id)]
                                                          (get-category-color (:category preset))
                                                          (:assigned cell-colors))
                                              :else (:empty cell-colors))]
                               (ss/config! panel :background bg-color)
                               (ss/config! name-label :text (if preset-id
                                                             (:name (presets/get-preset preset-id))
                                                             ""))))]
    
    ;; Set preferred size
    (.setPreferredSize panel (Dimension. 80 60))
    (.setMinimumSize panel (Dimension. 60 50))
    
    ;; Add mouse listeners
    (.addMouseListener panel
                       (proxy [MouseAdapter] []
                         (mouseClicked [^MouseEvent e]
                           (if (= (.getButton e) MouseEvent/BUTTON3)
                             (on-right-click cell-key @state-atom)
                             (on-click cell-key @state-atom)))
                         (mouseEntered [^MouseEvent e]
                           (.setBorder panel (BorderFactory/createLineBorder Color/WHITE 2)))
                         (mouseExited [^MouseEvent e]
                           (.setBorder panel (BorderFactory/createLineBorder (Color. 20 20 20) 1)))))
    
    ;; Return cell info
    {:panel panel
     :key cell-key
     :state state-atom
     :set-preset! (fn [preset-id]
                    (swap! state-atom assoc :preset-id preset-id)
                    (when preset-id
                      (swap! state-atom assoc :animation 
                             (presets/create-animation-from-preset preset-id)))
                    (update-appearance!))
     :set-active! (fn [active]
                    (swap! state-atom assoc :active active)
                    (update-appearance!))
     :set-selected! (fn [selected]
                      (swap! state-atom assoc :selected selected)
                      (update-appearance!))
     :get-animation (fn [] (:animation @state-atom))
     :update! update-appearance!}))

;; ============================================================================
;; Grid Panel
;; ============================================================================

(defn create-grid-panel
  "Create the main grid panel with cells.
   Returns a map with :panel and control functions."
  [& {:keys [cols rows on-cell-click on-cell-right-click]
      :or {cols 8 rows 4
           on-cell-click (fn [k s] (println "Cell clicked:" k))
           on-cell-right-click (fn [k s] (println "Cell right-clicked:" k))}}]
  (let [cells-atom (atom {})
        selected-atom (atom nil)
        active-atom (atom nil)
        
        ;; Create constraint string for MIG layout
        col-constraints (apply str (repeat cols "[80!, grow, fill]"))
        row-constraints (apply str (repeat rows "[60!, grow, fill]"))
        
        grid-panel (mig/mig-panel
                    :constraints ["gap 2, insets 5"
                                  col-constraints
                                  row-constraints]
                    :background (Color. 30 30 30))
        
        ;; Cell click handler
        handle-click (fn [cell-key cell-state]
                       (when-let [prev-selected @selected-atom]
                         (when-let [prev-cell (get @cells-atom prev-selected)]
                           ((:set-selected! prev-cell) false)))
                       (when-let [cell (get @cells-atom cell-key)]
                         ((:set-selected! cell) true)
                         (reset! selected-atom cell-key))
                       (on-cell-click cell-key cell-state))
        
        ;; Right-click handler
        handle-right-click (fn [cell-key cell-state]
                             (on-cell-right-click cell-key cell-state))]
    
    ;; Create all cells
    (doseq [row (range rows)
            col (range cols)]
      (let [cell (create-cell-panel col row handle-click handle-right-click)]
        (swap! cells-atom assoc [col row] cell)
        (ss/add! grid-panel (:panel cell) (str "cell " col " " row))))
    
    {:panel grid-panel
     :cells cells-atom
     
     :set-cell-preset! (fn [col row preset-id]
                         (when-let [cell (get @cells-atom [col row])]
                           ((:set-preset! cell) preset-id)))
     
     :set-active-cell! (fn [col row]
                         ;; Deactivate previous
                         (when-let [prev @active-atom]
                           (when-let [prev-cell (get @cells-atom prev)]
                             ((:set-active! prev-cell) false)))
                         ;; Activate new
                         (when (and col row)
                           (when-let [cell (get @cells-atom [col row])]
                             ((:set-active! cell) true)))
                         (reset! active-atom (when col [col row])))
     
     :get-cell-animation (fn [col row]
                           (when-let [cell (get @cells-atom [col row])]
                             ((:get-animation cell))))
     
     :get-selected-cell (fn [] @selected-atom)
     
     :clear-all! (fn []
                   (doseq [[_ cell] @cells-atom]
                     ((:set-preset! cell) nil)))}))

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
