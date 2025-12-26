(ns laser-show.views.components.grid-cell
  "Grid cell component - a single cue trigger button.
   
   Each cell displays:
   - Preset name if assigned
   - Visual state (active, selected, empty)
   - Responds to clicks for triggering
   
   Supports:
   - Drag and drop between cells
   - Right-click context menu (copy, paste, clear)"
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.events.core :as events])
  (:import [javafx.scene.input MouseEvent MouseButton TransferMode DragEvent ClipboardContent]))

;; ============================================================================
;; Cell Colors
;; ============================================================================

(def cell-colors
  "Color scheme for cell states."
  {:empty "#424242"
   :content "#616161"
   :active "#4CAF50"
   :selected "#2196F3"
   :hover "#505050"
   :drag-over "#FFA726"})

(defn cell-background-color
  "Determine background color based on cell state."
  [{:keys [active? selected? has-content?]}]
  (cond
    active? (:active cell-colors)
    selected? (:selected cell-colors)
    has-content? (:content cell-colors)
    :else (:empty cell-colors)))

;; ============================================================================
;; Cell Content
;; ============================================================================

(defn cell-label
  "Display text for a cell."
  [{:keys [preset-id]}]
  (if preset-id
    (-> preset-id name (str/replace "-" " "))
    ""))

;; ============================================================================
;; Grid Cell Component
;; ============================================================================

(defn grid-cell
  "A single grid cell button with drag/drop and context menu support.
   
   Props:
   - col: Column index
   - row: Row index
   
   Behavior:
   - Left-click: Trigger the cell
   - Right-click: Select cell
   - Drag from cell: Start dragging cell content
   - Drop on cell: Move cell content"
  [{:keys [fx/context col row]}]
  (let [{:keys [preset-id active? selected? has-content?] :as display-data}
        (fx/sub-ctx context subs/cell-display-data col row)
        bg-color (cell-background-color display-data)
        label-text (cell-label display-data)]
    {:fx/type :stack-pane
     :pref-width 80
     :pref-height 60
     :style (str "-fx-background-color: " bg-color "; "
                 "-fx-background-radius: 4; "
                 "-fx-cursor: hand; "
                 (when selected?
                   "-fx-border-color: #2196F3; -fx-border-width: 2; -fx-border-radius: 4;")
                 (when active?
                   "-fx-effect: dropshadow(gaussian, #4CAF50, 10, 0.5, 0, 0);"))
     
     ;; Mouse click handler - use function to check button, then dispatch
     :on-mouse-clicked (fn [^MouseEvent e]
                         (let [button (.getButton e)
                               event-map (cond
                                           ;; Right click - select (for context menu later)
                                           (= button MouseButton/SECONDARY)
                                           {:event/type :grid/select-cell :col col :row row}
                                           
                                           ;; Left click on cell with content - trigger
                                           has-content?
                                           {:event/type :grid/trigger-cell :col col :row row}
                                           
                                           ;; Left click on empty - select
                                           :else
                                           {:event/type :grid/select-cell :col col :row row})]
                           ;; Dispatch the event manually
                           (events/dispatch! event-map)))
     
     ;; Drag detection - start drag when content exists
     :on-drag-detected (fn [^MouseEvent e]
                         (when has-content?
                           (let [source (.getSource e)
                                 db (.startDragAndDrop source (into-array TransferMode [TransferMode/MOVE]))
                                 content (ClipboardContent.)]
                             (.putString content (pr-str {:col col :row row :type :grid-cell}))
                             (.setContent db content)
                             (.consume e))))
     
     ;; Drag over - accept drops
     :on-drag-over (fn [^DragEvent e]
                     (when (and (.getDragboard e)
                                (.hasString (.getDragboard e)))
                       (let [data (try (read-string (.getString (.getDragboard e))) (catch Exception _ nil))]
                         (when (= :grid-cell (:type data))
                           (.acceptTransferModes e (into-array TransferMode [TransferMode/MOVE])))))
                     (.consume e))
     
     ;; Drag dropped - complete the move
     :on-drag-dropped (fn [^DragEvent e]
                        (let [db (.getDragboard e)]
                          (when (.hasString db)
                            (let [data (try (read-string (.getString db)) (catch Exception _ nil))]
                              (when (and data (= :grid-cell (:type data)))
                                (let [from-col (:col data)
                                      from-row (:row data)]
                                  ;; Dispatch move event
                                  (events/dispatch! {:event/type :grid/move-cell
                                                     :from-col from-col :from-row from-row
                                                     :to-col col :to-row row})
                                  (.setDropCompleted e true)))))
                          (when-not (.isDropCompleted e)
                            (.setDropCompleted e false))
                          (.consume e)))
     
     :children [{:fx/type :v-box
                 :alignment :center
                 :spacing 4
                 :children (filterv some?
                                    [{:fx/type :label
                                      :text label-text
                                      :style "-fx-text-fill: white; -fx-font-size: 10;"}
                                     (when active?
                                       {:fx/type :region
                                        :pref-width 8
                                        :pref-height 8
                                        :style "-fx-background-color: white; -fx-background-radius: 4;"})])}]}))

;; ============================================================================
;; Effects Cell Component (for effects grid)
;; ============================================================================

(defn effects-cell
  "A single effects grid cell with drag/drop and context menu support.
   
   Props:
   - col: Column index
   - row: Row index"
  [{:keys [fx/context col row]}]
  (let [{:keys [effect-count first-effect-id active? has-effects?] :as display-data}
        (fx/sub-ctx context subs/effect-cell-display-data col row)
        bg-color (cond
                   active? "#4CAF50"
                   has-effects? "#7E57C2"
                   :else "#424242")
        label-text (if has-effects?
                     (str effect-count " fx")
                     "")]
    {:fx/type :stack-pane
     :pref-width 80
     :pref-height 60
     :style (str "-fx-background-color: " bg-color "; "
                 "-fx-background-radius: 4; "
                 "-fx-cursor: hand;")
     
     ;; Mouse click handler
     :on-mouse-clicked (fn [^MouseEvent e]
                         (let [button (.getButton e)]
                           (if (= button MouseButton/SECONDARY)
                             ;; Right-click: select cell and open editor dialog
                             (do
                               (events/dispatch! {:event/type :effects/select-cell :col col :row row})
                               (events/dispatch! {:event/type :ui/open-dialog
                                                  :dialog-id :effect-chain-editor
                                                  :data {:col col :row row}}))
                             ;; Left-click: toggle cell
                             (events/dispatch! {:event/type :effects/toggle-cell :col col :row row}))))
     
     ;; Drag detection
     :on-drag-detected (fn [^MouseEvent e]
                         (when has-effects?
                           (let [source (.getSource e)
                                 db (.startDragAndDrop source (into-array TransferMode [TransferMode/MOVE]))
                                 content (ClipboardContent.)]
                             (.putString content (pr-str {:col col :row row :type :effects-cell}))
                             (.setContent db content)
                             (.consume e))))
     
     ;; Drag over
     :on-drag-over (fn [^DragEvent e]
                     (when (and (.getDragboard e)
                                (.hasString (.getDragboard e)))
                       (let [data (try (read-string (.getString (.getDragboard e))) (catch Exception _ nil))]
                         (when (= :effects-cell (:type data))
                           (.acceptTransferModes e (into-array TransferMode [TransferMode/MOVE])))))
                     (.consume e))
     
     ;; Drag dropped
     :on-drag-dropped (fn [^DragEvent e]
                        (let [db (.getDragboard e)]
                          (when (.hasString db)
                            (let [data (try (read-string (.getString db)) (catch Exception _ nil))]
                              (when (and data (= :effects-cell (:type data)))
                                (events/dispatch! {:event/type :effects/move-cell
                                                   :from-col (:col data) :from-row (:row data)
                                                   :to-col col :to-row row})
                                (.setDropCompleted e true))))
                          (when-not (.isDropCompleted e)
                            (.setDropCompleted e false))
                          (.consume e)))
     
     :children [{:fx/type :v-box
                 :alignment :center
                 :spacing 4
                 :children (filterv some?
                                    [{:fx/type :label
                                      :text label-text
                                      :style "-fx-text-fill: white; -fx-font-size: 10;"}
                                     (when active?
                                       {:fx/type :region
                                        :pref-width 8
                                        :pref-height 8
                                        :style "-fx-background-color: white; -fx-background-radius: 4;"})])}]}))
