(ns laser-show.ui-fx.views.effects-grid
  "Effects grid view component - grid for managing effect chains.
   
   Uses cljfx context for reactive subscriptions."
  (:require [cljfx.api :as fx]
            [laser-show.ui-fx.styles :as styles]
            [laser-show.ui-fx.subs :as subs]
            [laser-show.ui-fx.events :as events]
            [laser-show.ui-fx.components.cell :as cell]
            [laser-show.ui-fx.dialogs.effect-chain-editor :as effect-chain-editor])
  (:import [javafx.scene.control ContextMenu MenuItem]
           [javafx.scene.input MouseEvent]))

;; ============================================================================
;; Context Menu
;; ============================================================================

(defn- show-effect-context-menu!
  "Show context menu for effect cell."
  [^MouseEvent event col row has-effects?]
  (let [menu (ContextMenu.)
        items (.getItems menu)]
    
    (if has-effects?
      (do
        (.add items (doto (MenuItem. "Edit")
                      (.setOnAction (fn [_]
                                      (effect-chain-editor/show-effect-chain-editor! col row)))))
        (.add items (doto (MenuItem. "Toggle Active")
                      (.setOnAction (fn [_]
                                      (events/dispatch! {:event/type :effects/toggle-cell
                                                         :col col :row row})))))
        (.addSeparator menu)
        (.add items (doto (MenuItem. "Copy")
                      (.setOnAction (fn [_]
                                      (events/dispatch! {:event/type :clipboard/copy-effect-cell
                                                         :col col :row row})))))
        (.add items (doto (MenuItem. "Paste")
                      (.setOnAction (fn [_]
                                      (events/dispatch! {:event/type :clipboard/paste-effect-cell
                                                         :col col :row row})))))
        (.addSeparator menu)
        (.add items (doto (MenuItem. "Clear")
                      (.setOnAction (fn [_]
                                      (events/dispatch! {:event/type :effects/clear-cell
                                                         :col col :row row}))))))
      
      ;; Empty cell menu
      (do
        (.add items (doto (MenuItem. "Add Effect...")
                      (.setOnAction (fn [_]
                                      (effect-chain-editor/show-effect-chain-editor! col row)))))
        (.add items (doto (MenuItem. "Paste")
                      (.setOnAction (fn [_]
                                      (events/dispatch! {:event/type :clipboard/paste-effect-cell
                                                         :col col :row row})))))))
    
    (.show menu
           (.getSource event)
           (.getScreenX event)
           (.getScreenY event))))

;; ============================================================================
;; Effect Cell Wrapper
;; ============================================================================

(defn effect-cell-view
  "Individual effect grid cell with data from context subscriptions.
   
   Props:
   - :fx/context - cljfx context (passed automatically)
   - :col - Column index
   - :row - Row index"
  [{:keys [fx/context col row]}]
  ;; Subscribe to cell data via context
  (let [data (fx/sub-ctx context subs/effect-cell-display-data col row)]
    {:fx/type cell/effect-cell
     :col col
     :row row
     :effect-count (:effect-count data)
     :first-effect-id (:first-effect-id data)
     :active? (:active? data)
     :has-effects? (:has-effects? data)
     
     :on-click (fn []
                 (events/dispatch! {:event/type :effects/toggle-cell
                                    :col col :row row}))
     
     :on-double-click (fn []
                        ;; Open effect editor dialog
                        (effect-chain-editor/show-effect-chain-editor! col row))
     
     :on-right-click (fn [e]
                       (show-effect-context-menu! e col row (:has-effects? data)))}))

;; ============================================================================
;; Main Effects Grid Component
;; ============================================================================

(defn effects-grid
  "Effects grid panel.
   
   Props:
   - :fx/context - cljfx context (passed automatically)
   - :cols - Number of columns (default 5)
   - :rows - Number of rows (default 2)"
  [{:keys [cols rows]
    :or {cols 5 rows 2}}]
  (let [gap (:cell-gap styles/dimensions)]
    {:fx/type :v-box
     :style (str "-fx-background-color: " (:surface-light styles/colors) ";"
                "-fx-padding: 8;")
     :spacing gap
     :children (mapv (fn [row]
                       {:fx/type :h-box
                        :spacing gap
                        :children (mapv (fn [col]
                                          {:fx/type effect-cell-view
                                           :fx/key [col row]
                                           :col col
                                           :row row})
                                        (range cols))})
                     (range rows))}))

;; ============================================================================
;; Effects Grid with Header
;; ============================================================================

(defn effects-grid-panel
  "Effects grid with header and controls.
   
   Props:
   - :fx/context - cljfx context (passed automatically)
   - :title - Optional title
   - :cols - Number of columns
   - :rows - Number of rows"
  [{:keys [title cols rows]
    :or {title "Effects"
         cols 5
         rows 2}}]
  {:fx/type :v-box
   :style (str "-fx-background-color: " (:surface-light styles/colors) ";"
              "-fx-border-color: " (:border styles/colors) ";"
              "-fx-border-width: 1 0 0 0;")
   :children [{:fx/type :h-box
               :style (str "-fx-background-color: " (:surface styles/colors) ";"
                          "-fx-padding: 4 8;")
               :alignment :center-left
               :spacing 8
               :children [{:fx/type :label
                           :text title
                           :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                                      "-fx-font-size: 11px;"
                                      "-fx-font-weight: bold;")}
                          {:fx/type :region
                           :h-box/hgrow :always}
                          {:fx/type :button
                           :text "Clear All"
                           :style "-fx-font-size: 10px; -fx-padding: 2 8;"
                           :on-action (fn [_]
                                        ;; Clear all effect cells
                                        (doseq [r (range rows)
                                                c (range cols)]
                                          (events/dispatch! {:event/type :effects/clear-cell
                                                             :col c :row r})))}]}
              {:fx/type effects-grid
               :cols cols
               :rows rows}]})

;; ============================================================================
;; Active Effects Summary
;; ============================================================================

(defn active-effects-summary
  "Shows a summary of currently active effects.
   
   Props:
   - :fx/context - cljfx context (passed automatically)"
  [{:keys [fx/context]}]
  (let [active (fx/sub-ctx context subs/all-active-effect-instances)
        effect-count (count active)]
    {:fx/type :h-box
     :spacing 4
     :alignment :center-left
     :children [{:fx/type :label
                 :text (str effect-count " active effect" (when (not= effect-count 1) "s"))
                 :style (str "-fx-text-fill: " 
                            (if (pos? effect-count)
                              (:success styles/colors)
                              (:text-secondary styles/colors))
                            ";"
                            "-fx-font-size: 10px;")}]}))
