(ns laser-show.ui-fx.components.cell
  "Reusable grid cell component for cljfx.
   Used by both the cue grid and effects grid."
  (:require [cljfx.api :as fx]
            [laser-show.ui-fx.styles :as styles])
  (:import [javafx.scene.input MouseButton ClipboardContent TransferMode DragEvent MouseEvent]
           [javafx.scene Cursor]))

;; ============================================================================
;; Style Helpers
;; ============================================================================

(defn- cell-style-classes
  "Generate style class list based on cell state."
  [{:keys [active? selected? has-content?]}]
  (cond-> ["grid-cell"]
    has-content? (conj "has-content")
    active? (conj "active")
    selected? (conj "selected")))

(defn- cell-background-color
  "Get background color based on cell state and category."
  [{:keys [active? selected? has-content? category]}]
  (cond
    active? (:cell-active styles/colors)
    selected? (:cell-selected styles/colors)
    (and has-content? category) (styles/category-color category)
    has-content? (:cell-assigned styles/colors)
    :else (:cell-empty styles/colors)))

(defn- cell-inline-style
  "Generate inline style for cell background."
  [cell-data]
  (str "-fx-background-color: " (cell-background-color cell-data) ";"
       "-fx-background-radius: 4;"
       "-fx-border-radius: 4;"))

;; ============================================================================
;; Grid Cell Component
;; ============================================================================

(defn grid-cell
  "A grid cell component for the cue grid.
   
   Props:
   - :col - Column index
   - :row - Row index  
   - :preset-name - Name to display (or empty string)
   - :category - Preset category for coloring
   - :active? - Whether this cell is currently playing
   - :selected? - Whether this cell is selected
   - :has-content? - Whether this cell has content assigned
   - :on-click - Event map for click (left button)
   - :on-right-click - Event map for right click
   - :draggable? - Whether drag is enabled (default true)
   - :on-drag-start - Event map when drag starts
   - :on-drag-drop - Event map when drop occurs
   - :width - Cell width (default from styles)
   - :height - Cell height (default from styles)"
  [{:keys [col row preset-name category active? selected? has-content?
           on-click on-right-click draggable? on-drag-start on-drag-drop
           width height]
    :or {draggable? true
         width (:cell-width styles/dimensions)
         height (:cell-height styles/dimensions)}}]
  
  {:fx/type :stack-pane
   :style (cell-inline-style {:active? active?
                              :selected? selected?
                              :has-content? has-content?
                              :category category})
   :style-class (cell-style-classes {:active? active?
                                     :selected? selected?
                                     :has-content? has-content?})
   :pref-width width
   :pref-height height
   :min-width width
   :min-height height
   :cursor (if has-content? Cursor/HAND Cursor/DEFAULT)
   
   ;; Mouse events
   :on-mouse-clicked (fn [^MouseEvent e]
                       (cond
                         (= (.getButton e) MouseButton/PRIMARY)
                         (when on-click
                           (on-click))
                         
                         (= (.getButton e) MouseButton/SECONDARY)
                         (when on-right-click
                           (on-right-click e))))
   
   ;; Drag source - only set if draggable with content
   :on-drag-detected (fn [^MouseEvent e]
                       (when (and draggable? has-content? on-drag-start)
                         (let [source (.getSource e)
                               db (.startDragAndDrop source (into-array TransferMode [TransferMode/MOVE]))
                               content (ClipboardContent.)]
                           (.putString content (pr-str {:col col :row row}))
                           (.setContent db content)
                           (on-drag-start))
                         (.consume e)))
   
   ;; Drop target
   :on-drag-over (fn [^DragEvent e]
                   (when (and (.getDragboard e)
                              (.hasString (.getDragboard e)))
                     (.acceptTransferModes e (into-array TransferMode [TransferMode/MOVE])))
                   (.consume e))
   
   :on-drag-dropped (fn [^DragEvent e]
                      (let [db (.getDragboard e)
                            success (atom false)]
                        (when (.hasString db)
                          (try
                            (let [data (read-string (.getString db))
                                  from-col (:col data)
                                  from-row (:row data)]
                              (when (and on-drag-drop
                                         (not (and (= from-col col) (= from-row row))))
                                (on-drag-drop {:from-col from-col
                                               :from-row from-row
                                               :to-col col
                                               :to-row row})
                                (reset! success true)))
                            (catch Exception _ nil)))
                        (.setDropCompleted e @success)
                        (.consume e)))
   
   :on-drag-done (fn [^DragEvent e]
                   (.consume e))
   
   ;; Content
   :children [{:fx/type :label
               :text (or preset-name "")
               :style (str "-fx-text-fill: white;"
                          "-fx-font-size: 10px;"
                          "-fx-wrap-text: true;"
                          "-fx-text-alignment: center;")
               :wrap-text true
               :max-width (- width 8)}]})

;; ============================================================================
;; Effect Cell Component
;; ============================================================================

(defn effect-cell
  "A grid cell component for the effects grid.
   
   Props:
   - :col - Column index
   - :row - Row index
   - :effect-count - Number of effects in the chain
   - :first-effect-id - ID of first effect (for display)
   - :active? - Whether this effect chain is active
   - :has-effects? - Whether this cell has effects
   - :on-click - Event map for click (toggle or edit)
   - :on-double-click - Event map for double-click (edit)
   - :on-right-click - Event map for right click (context menu)
   - :width - Cell width
   - :height - Cell height"
  [{:keys [col row effect-count first-effect-id active? has-effects?
           on-click on-double-click on-right-click
           width height]
    :or {width (:cell-width styles/dimensions)
         height (:cell-height styles/dimensions)}}]
  
  (let [display-text (cond
                       (and has-effects? first-effect-id)
                       (str (name first-effect-id)
                            (when (> effect-count 1)
                              (str " +" (dec effect-count))))
                       
                       has-effects?
                       (str effect-count " fx")
                       
                       :else
                       "")]
    
    {:fx/type :stack-pane
     :style (str "-fx-background-color: "
                 (if has-effects?
                   (:category-effect styles/colors)
                   (:surface styles/colors))
                 ";"
                 "-fx-background-radius: 4;"
                 "-fx-border-radius: 4;"
                 "-fx-border-color: "
                 (if active?
                   (:success styles/colors)
                   (:border styles/colors))
                 ";"
                 "-fx-border-width: " (if active? "2" "1") ";")
     :style-class (cond-> ["effect-cell"]
                    has-effects? (conj "has-effects")
                    active? (conj "active"))
     :pref-width width
     :pref-height height
     :min-width width
     :min-height height
     :cursor Cursor/HAND
     
     :on-mouse-clicked (fn [^MouseEvent e]
                         (cond
                           (and (= (.getClickCount e) 2)
                                (= (.getButton e) MouseButton/PRIMARY))
                           (when on-double-click
                             (on-double-click))
                           
                           (= (.getButton e) MouseButton/PRIMARY)
                           (when on-click
                             (on-click))
                           
                           (= (.getButton e) MouseButton/SECONDARY)
                           (when on-right-click
                             (on-right-click e))))
     
     :children [{:fx/type :v-box
                 :alignment :center
                 :spacing 2
                 :children (filterv some?
                                    [{:fx/type :label
                                      :text display-text
                                      :style (str "-fx-text-fill: white;"
                                                 "-fx-font-size: 10px;")
                                      :wrap-text true
                                      :max-width (- width 8)}
                                     (when active?
                                       {:fx/type :region
                                        :style (str "-fx-background-color: " (:success styles/colors) ";"
                                                   "-fx-background-radius: 2;")
                                        :pref-width 20
                                        :pref-height 4})])}]}))

;; ============================================================================
;; Preset Button Component
;; ============================================================================

(defn preset-button
  "A button for the preset palette.
   
   Props:
   - :preset-id - Preset ID keyword
   - :name - Display name
   - :category - Category for styling
   - :on-click - Event when clicked"
  [{:keys [preset-id name category on-click]}]
  
  {:fx/type :button
   :text name
   :style-class ["preset-button" (when category (clojure.core/name category))]
   :style (str "-fx-background-color: " (styles/category-color category) ";"
              "-fx-text-fill: white;"
              "-fx-font-size: 10px;"
              "-fx-padding: 8 12;"
              "-fx-cursor: hand;")
   :on-action (fn [_]
                (when on-click
                  (on-click preset-id)))})
