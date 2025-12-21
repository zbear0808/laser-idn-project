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
            [laser-show.ui.layout :as layout])
  (:import [java.awt Color Dimension Font]
           [java.awt.event MouseAdapter MouseEvent]
           [javax.swing BorderFactory JPanel]))

;; ============================================================================
;; Grid State
;; ============================================================================

(defonce !grid-state
  (atom {:grid-size [layout/default-grid-cols layout/default-grid-rows]
         :cells {}
         :active-cell nil
         :selected-cell nil}))

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
        !cell-state (atom {:preset-id nil
                          :animation nil
                          :active false
                          :selected false})
        
        name-label (create-cell-label "")
        
        panel (ss/border-panel
               :center name-label
               :background colors/cell-empty
               :border (BorderFactory/createLineBorder colors/border-dark layout/cell-border-width))
        
        update-appearance! (fn []
                             (let [{:keys [preset-id active selected]} @!cell-state
                                   bg-color (cond
                                              active colors/cell-active
                                              selected colors/cell-selected
                                              preset-id (if-let [preset (presets/get-preset preset-id)]
                                                          (colors/get-category-color (:category preset))
                                                          colors/cell-assigned)
                                              :else colors/cell-empty)]
                               (ss/config! panel :background bg-color)
                               (ss/config! name-label :text (if preset-id
                                                             (:name (presets/get-preset preset-id))
                                                             ""))))]
    
    ;; Set preferred size
    (.setPreferredSize panel (Dimension. 80 60))
    (.setMinimumSize panel (Dimension. 60 50))
    
    (.addMouseListener panel
                       (proxy [MouseAdapter] []
                         (mouseClicked [^MouseEvent e]
                           (if (= (.getButton e) MouseEvent/BUTTON3)
                             (on-right-click cell-key @!cell-state)
                             (on-click cell-key @!cell-state)))
                         (mouseEntered [^MouseEvent e]
                           (.setBorder panel (BorderFactory/createLineBorder colors/border-highlight layout/cell-border-hover-width)))
                         (mouseExited [^MouseEvent e]
                           (.setBorder panel (BorderFactory/createLineBorder colors/border-dark layout/cell-border-width)))))
    
    {:panel panel
     :key cell-key
     :state !cell-state
     :set-preset! (fn [preset-id]
                    (swap! !cell-state assoc :preset-id preset-id)
                    (when preset-id
                      (swap! !cell-state assoc :animation
                             (presets/create-animation-from-preset preset-id)))
                    (update-appearance!))
     :set-active! (fn [active]
                    (swap! !cell-state assoc :active active)
                    (update-appearance!))
     :set-selected! (fn [selected]
                      (swap! !cell-state assoc :selected selected)
                      (update-appearance!))
     :get-animation (fn [] (:animation @!cell-state))
     :update! update-appearance!}))

;; ============================================================================
;; Grid Panel
;; ============================================================================

(defn create-grid-panel
  "Create the main grid panel with cells.
   Returns a map with :panel and control functions."
  [& {:keys [cols rows on-cell-click on-cell-right-click]
      :or {cols layout/default-grid-cols
           rows layout/default-grid-rows
           on-cell-click (fn [k s] (println "Cell clicked:" k))
           on-cell-right-click (fn [k s] (println "Cell right-clicked:" k))}}]
  (let [!cells (atom {})
        !selected (atom nil)
        !active (atom nil)
        
        {:keys [layout]
         col-constraints :cols
         row-constraints :rows} (layout/make-grid-constraints
                                 {:cols cols :rows rows
                                  :cell-width layout/cell-width
                                  :cell-height layout/cell-height})
        
        grid-panel (mig/mig-panel
                    :constraints [layout col-constraints row-constraints]
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
                             (on-cell-right-click cell-key cell-state))]
    
    (doseq [row (range rows)
            col (range cols)]
      (let [cell (create-cell-panel col row handle-click handle-right-click)]
        (swap! !cells assoc [col row] cell)
        (ss/add! grid-panel (:panel cell) (str "cell " col " " row))))
    
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
     
     :get-selected-cell (fn [] @!selected)
     
     :clear-all! (fn []
                   (doseq [[_ cell] @!cells]
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
