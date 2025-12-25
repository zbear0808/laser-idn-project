(ns laser-show.ui-fx.views.grid
  "Cue grid view component - launchpad-style grid for triggering animations."
  (:require [cljfx.api :as fx]
            [laser-show.ui-fx.styles :as styles]
            [laser-show.ui-fx.subs :as subs]
            [laser-show.ui-fx.events :as events]
            [laser-show.ui-fx.components.cell :as cell])
  (:import [javafx.scene.control ContextMenu MenuItem]
           [javafx.scene.input MouseEvent]))

;; ============================================================================
;; Context Menu
;; ============================================================================

(defn- show-context-menu!
  "Show context menu at mouse position."
  [^MouseEvent event col row has-content?]
  (let [menu (ContextMenu.)
        items (.getItems menu)]
    
    (if has-content?
      ;; Menu for cell with content
      (do
        (.add items (doto (MenuItem. "Copy")
                      (.setOnAction (fn [_]
                                      (events/dispatch! {:event/type :clipboard/copy-cell
                                                         :col col :row row})))))
        (.add items (doto (MenuItem. "Paste")
                      (.setOnAction (fn [_]
                                      (events/dispatch! {:event/type :clipboard/paste-cell
                                                         :col col :row row})))))
        (.add items (doto (MenuItem. "Clear")
                      (.setOnAction (fn [_]
                                      (events/dispatch! {:event/type :grid/clear-cell
                                                         :col col :row row}))))))
      
      ;; Menu for empty cell
      (.add items (doto (MenuItem. "Paste")
                    (.setOnAction (fn [_]
                                    (events/dispatch! {:event/type :clipboard/paste-cell
                                                       :col col :row row}))))))
    
    (.show menu
           (.getSource event)
           (.getScreenX event)
           (.getScreenY event))))

;; ============================================================================
;; Grid Cell Wrapper
;; ============================================================================

(defn grid-cell-view
  "Individual grid cell with data from subscriptions.
   
   Props:
   - :col - Column index
   - :row - Row index"
  [{:keys [col row]}]
  (let [data (subs/cell-display-data col row)]
    {:fx/type cell/grid-cell
     :col col
     :row row
     :preset-name (:preset-name data)
     :category (:category data)
     :active? (:active? data)
     :selected? (:selected? data)
     :has-content? (:has-content? data)
     
     :on-click (fn []
                 (events/dispatch! {:event/type :grid/select-cell :col col :row row})
                 (events/dispatch! {:event/type :grid/trigger-cell :col col :row row}))
     
     :on-right-click (fn [e]
                       (show-context-menu! e col row (:has-content? data)))
     
     :on-drag-start (fn []
                      (events/dispatch! {:event/type :drag/start
                                         :source-type :grid-cell
                                         :source-id :main-grid
                                         :source-key [col row]
                                         :data (subs/grid-cell col row)}))
     
     :on-drag-drop (fn [{:keys [from-col from-row to-col to-row]}]
                     (events/dispatch! {:event/type :grid/move-cell
                                        :from-col from-col
                                        :from-row from-row
                                        :to-col to-col
                                        :to-row to-row}))}))

;; ============================================================================
;; Main Grid Component
;; ============================================================================

(defn cue-grid
  "Main cue grid panel.
   
   Props:
   - :cols - Number of columns (default 8)
   - :rows - Number of rows (default 4)"
  [{:keys [cols rows]
    :or {cols 8 rows 4}}]
  (let [gap (:cell-gap styles/dimensions)]
    {:fx/type :v-box
     :style (str "-fx-background-color: " (:background styles/colors) ";"
                "-fx-padding: 8;")
     :spacing gap
     :children (mapv (fn [row]
                       {:fx/type :h-box
                        :spacing gap
                        :children (mapv (fn [col]
                                          {:fx/type grid-cell-view
                                           :col col
                                           :row row})
                                        (range cols))})
                     (range rows))}))

;; ============================================================================
;; Scrollable Grid Wrapper
;; ============================================================================

(defn scrollable-cue-grid
  "Cue grid wrapped in a scroll pane.
   
   Props:
   - :cols - Number of columns
   - :rows - Number of rows"
  [{:keys [cols rows]
    :or {cols 8 rows 4}}]
  {:fx/type :scroll-pane
   :fit-to-width true
   :style (str "-fx-background-color: " (:background styles/colors) ";"
              "-fx-background: " (:background styles/colors) ";")
   :content {:fx/type cue-grid
             :cols cols
             :rows rows}})

;; ============================================================================
;; Grid with Header
;; ============================================================================

(defn grid-panel
  "Complete grid panel with header.
   
   Props:
   - :title - Optional title
   - :cols - Number of columns
   - :rows - Number of rows"
  [{:keys [title cols rows]
    :or {cols 8 rows 4}}]
  {:fx/type :v-box
   :style (str "-fx-background-color: " (:background styles/colors) ";")
   :children (vec
              (concat
               (when title
                 [{:fx/type :label
                   :text title
                   :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                              "-fx-font-size: 14px;"
                              "-fx-font-weight: bold;"
                              "-fx-padding: 8;")}])
               [{:fx/type scrollable-cue-grid
                 :cols cols
                 :rows rows
                 :v-box/vgrow :always}]))})
