(ns laser-show.views.components.grid-cell
  "Grid cell components with shared behavior via generic-grid-cell.
   
   This namespace provides:
   - generic-grid-cell: Configurable base component with drag/drop
   - grid-cell: Cue grid cell (wrapper)
   - effects-cell: Effects grid cell (wrapper)
   
   Key features:
   - DRY: Single implementation of drag-and-drop logic
   - CSS Best Practices: State-based styling via CSS classes
   - Flexible: Easy to add new cell types
   
   Styling:
   - All background colors via CSS classes (no inline styles)
   - State classes: grid-cell-empty, grid-cell-content, grid-cell-active
   - Type variants: grid-cell-effects"
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [laser-show.subs :as subs]
            [laser-show.events.core :as events]
            [laser-show.views.components.drag-drop-cell :as drag-drop])
  (:import [javafx.scene.input MouseEvent MouseButton]))


;; Style Class Builders


(defn- build-style-classes
  "Build style-class vector based on cell state.
   
   Base class 'grid-cell' is always included.
   Cell type adds 'grid-cell-<type>' (e.g., grid-cell-effects).
   State classes are added based on display-data."
  [cell-type {:keys [active? has-content? selected?]}]
  (cond-> ["grid-cell" (str "grid-cell-" (name cell-type))]
    active? (conj "grid-cell-active")
    selected? (conj "grid-cell-selected")
    has-content? (conj "grid-cell-content")
    (not has-content?) (conj "grid-cell-empty")))


;; Label Functions


(defn cell-label
  "Display text for a cue cell.
   Shows first preset name, or preset count if multiple."
  [{:keys [first-preset-id preset-count]}]
  (cond
    (nil? first-preset-id) ""
    (= preset-count 1) (-> first-preset-id name (str/replace "-" " "))
    :else (str (-> first-preset-id name (str/replace "-" " "))
               " +" (dec preset-count))))


(defn effects-label
  "Display text for an effects cell.
   Shows effect count if any effects present."
  [{:keys [effect-count has-effects?]}]
  (if has-effects?
    (str effect-count " fx")
    ""))


;; Generic Grid Cell Component


(defn generic-grid-cell
  "Generic grid cell with configurable behavior.
   
   Props:
   - :col, :row - Cell coordinates
   - :cell-type - :grid or :effects (used for CSS class)
   - :display-data - Map with cell state (:active?, :has-content?, :selected?)
   - :on-click - Event map for click handling
   - :on-right-click - Event map for right-click (augmented with :col, :row)
   - :drag-config - Map with :drag-type and :on-drop event
   - :label-fn - Function (fn [display-data] -> string)
   
   Example:
   {:fx/type generic-grid-cell
    :col 0 :row 0
    :cell-type :grid
    :display-data {:active? true :has-content? true}
    :on-click {:event/type :grid/cell-clicked}
    :on-right-click {:event/type :cue-chain/open-editor}
    :drag-config {:drag-type :grid-cell
                  :on-drop {:event/type :grid/move-cell}}
    :label-fn cell-label}"
  [{:keys [col row cell-type display-data on-click on-right-click 
           drag-config label-fn]}]
  (let [{:keys [active? has-content?]} display-data
        label-text (label-fn display-data)
        style-classes (build-style-classes cell-type display-data)
        ;; Create drag handlers with current values (re-created on each render)
        drag-type (:drag-type drag-config)
        on-drop (:on-drop drag-config)]
    {:fx/type :stack-pane
     :pick-on-bounds true  ; Ensure mouse events are captured by parent, not children
     :pref-width 80
     :pref-height 60
     :style-class style-classes
     
     ;; Mouse click handler - handle left/right click and double-click
     :on-mouse-clicked (fn [^MouseEvent e]
                         (let [button (.getButton e)
                               click-count (.getClickCount e)]
                           (cond
                             ;; Right-click or double-click: dispatch right-click event
                             (or (= button MouseButton/SECONDARY)
                                 (>= click-count 2))
                             (do
                               (log/debug "Grid cell right-click/double-click"
                                         {:cell-type cell-type
                                          :col col
                                          :row row
                                          :event-type (:event/type on-right-click)
                                          :button (str button)
                                          :click-count click-count})
                               (events/dispatch! (assoc on-right-click
                                                       :col col :row row)))
                             
                             ;; Single left-click: dispatch click event
                             :else
                             (events/dispatch! (assoc on-click
                                                     :col col :row row
                                                     :has-content? has-content?)))))
     
     ;; Drag handlers - use inline handlers that are re-created on each render
     ;; This ensures has-content? and other values are always current
     :on-drag-detected (when drag-config
                         (drag-drop/make-drag-detected-handler
                           {:drag-type drag-type
                            :col col
                            :row row
                            :has-content? has-content?}))
     
     :on-drag-over (when drag-config
                     (drag-drop/make-drag-over-handler
                       {:drag-type drag-type}))
     
     :on-drag-dropped (when drag-config
                        (drag-drop/make-drag-dropped-handler
                          {:drag-type drag-type
                           :col col
                           :row row
                           :on-drop on-drop}))
     
     :children [{:fx/type :v-box
                 :alignment :center
                 :spacing 4
                 :children (filterv some?
                                   [{:fx/type :label
                                     :text label-text
                                     :style-class "grid-cell-label"}
                                    (when active?
                                      {:fx/type :region
                                       :pref-width 8
                                       :pref-height 8
                                       :style-class "grid-cell-active-indicator"})])}]}))


;; Cue Grid Cell


(defn grid-cell
  "Cue grid cell - wrapper around generic-grid-cell.
   
   Props:
   - col: Column index
   - row: Row index
   
   Behavior:
   - Left-click: Trigger the cell (if has content) or select (if empty)
   - Right-click/Double-click: Open cue chain editor
   - Drag from cell: Start dragging cell content
   - Drop on cell: Move cell content"
  [{:keys [fx/context col row]}]
  (let [display-data (fx/sub-ctx context subs/cell-display-data col row)
        ;; Map the subscription data to the generic component's expected format
        adapted-data (assoc display-data :has-content? (:has-content? display-data))]
    {:fx/type generic-grid-cell
     :col col
     :row row
     :cell-type :grid
     :display-data adapted-data
     :on-click {:event/type :grid/cell-clicked}
     :on-right-click {:event/type :cue-chain/open-editor}
     :drag-config {:drag-type :grid-cell
                   :on-drop {:event/type :grid/move-cell}}
     :label-fn cell-label}))


;; Effects Grid Cell


(defn effects-cell
  "Effects grid cell - wrapper around generic-grid-cell.
   
   Props:
   - col: Column index
   - row: Row index
   
   Behavior:
   - Left-click: Toggle the effect cell
   - Right-click: Open effect chain editor dialog
   - Drag from cell: Start dragging effects
   - Drop on cell: Move effects"
  [{:keys [fx/context col row]}]
  (let [display-data (fx/sub-ctx context subs/effect-cell-display-data col row)
        ;; Map the subscription data - effects cells use has-effects? instead of has-content?
        adapted-data {:active? (:active? display-data)
                      :has-content? (:has-effects? display-data)
                      :effect-count (:effect-count display-data)
                      :has-effects? (:has-effects? display-data)}]
    {:fx/type generic-grid-cell
     :col col
     :row row
     :cell-type :effects
     :display-data adapted-data
     :on-click {:event/type :effects/toggle-cell}
     :on-right-click {:event/type :ui/open-dialog
                      :dialog-id :effect-chain-editor
                      :data {:col col :row row}}
     :drag-config {:drag-type :effects-cell
                   :on-drop {:event/type :effects/move-cell}}
     :label-fn effects-label}))
