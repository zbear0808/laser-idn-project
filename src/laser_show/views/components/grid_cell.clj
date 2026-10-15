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
  [cell-type {:keys [active? has-content? selected? learning? has-trigger?]}]
  (cond-> ["grid-cell" (str "grid-cell-" (name cell-type))]
    selected? (conj "grid-cell-selected")
    active? (conj "grid-cell-active")
    has-content? (conj "grid-cell-content")
    (not has-content?) (conj "grid-cell-empty")
    has-trigger? (conj "grid-cell-has-trigger")
    learning? (conj "grid-cell-learning")))


;; Label Functions


(defn cell-label
  "Display text for a cue cell.
   Shows custom name if set, otherwise first preset name, or preset count if multiple.
   Shows 'Timeline' for timeline cues that do not yet have presets."
  [{:keys [name first-preset-id preset-count timeline?]}]
  (cond
    ;; Custom name takes priority
    (seq name) name
    ;; No presets
    (nil? first-preset-id) (if timeline? "Timeline" "")
    ;; Single preset - show preset name
    (= preset-count 1) (-> first-preset-id clojure.core/name (str/replace "-" " "))
    ;; Multiple presets - show first + count
    :else (str (-> first-preset-id clojure.core/name (str/replace "-" " "))
               " +" (dec preset-count))))


(defn effects-label
  "Display text for an effects cell.
   Shows custom name if set, otherwise effect count."
  [{:keys [name effect-count has-effects?]}]
  (cond
    ;; Custom name takes priority
    (seq name) name
    ;; Effects present - show count
    has-effects? (str effect-count " fx")
    ;; Empty
    :else ""))


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
   - :context-menu-items - Optional sequence of menu items for context menu. Displayed only when cell is empty.
   
   Example:
   {:fx/type generic-grid-cell
    :col 0 :row 0
    :cell-type :grid
    :display-data {:active? true :has-content? true}
    :on-click {:event/type :grid/cell-clicked}
    :on-right-click {:event/type :cue-chain/open-editor}
    :drag-config {:drag-type :grid-cell
                  :on-drop {:event/type :grid/move-cell}}
    :label-fn cell-label
    :context-menu-items [{:fx/type :menu-item :text \"Create Cue\" ...}]}
    "
  [{:keys [col row cell-type display-data on-click on-right-click
           drag-config label-fn context-menu-items]}]
  (let [{:keys [active? has-content?]} display-data
        label-text (label-fn display-data)
        style-classes (build-style-classes cell-type display-data)
        ;; Create drag handlers with current values (re-created on each render)
        drag-type (:drag-type drag-config)
        on-drop (:on-drop drag-config)
        has-context-menu? (boolean (seq context-menu-items))
        node {:fx/type :stack-pane
              :id (format "%s-cell-%d-%d" (name cell-type) col row)
              :pick-on-bounds true  ; Ensure mouse events are captured by parent, not children
              :pref-width 80
              :pref-height 60
              :style-class style-classes

              ;; Mouse click handler - handle left/right click and double-click
              :on-mouse-clicked (fn [^MouseEvent e]
                                  (let [button (.getButton e)
                                        click-count (.getClickCount e)]
                                    (cond
                                      ;; Double-click left: open editor directly
                                      (and (= button MouseButton/PRIMARY) (>= click-count 2))
                                      (events/dispatch! (assoc on-right-click
                                                               :col col :row row))

                                      ;; Secondary click when no context menu (fallback)
                                      (and (= button MouseButton/SECONDARY) (not has-context-menu?))
                                      (do
                                        (log/debug "Grid cell right-click"
                                                   {:cell-type cell-type
                                                    :col col
                                                    :row row
                                                    :event-type (:event/type on-right-click)})
                                        (events/dispatch! (assoc on-right-click
                                                                 :col col :row row)))

                                      ;; Single left-click: dispatch click event (trigger / select)
                                      (= button MouseButton/PRIMARY)
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
                          :spacing 2
                          :children (filterv some?
                                             [(when (:learning? display-data)
                                                {:fx/type :label
                                                 :text "LEARN"
                                                 :style-class "grid-cell-learning-badge"})
                                              {:fx/type :label
                                               :text label-text
                                               :style-class "grid-cell-label"}
                                              (when active?
                                                {:fx/type :region
                                                 :pref-width 8
                                                 :pref-height 8
                                                 :style-class "grid-cell-active-indicator"})
                                              (when (:has-trigger? display-data)
                                                {:fx/type :label
                                                 :text "⚡"
                                                 :style-class "grid-cell-trigger-indicator"})])}]}]
    (if has-context-menu?
      {:fx/type :label
       :content-display :graphic-only
       :padding 0
       :context-menu {:fx/type :context-menu
                      :items context-menu-items}
       :graphic node}
      node)))


;; Cue Grid Cell


(defn grid-cell
  "Cue grid cell - wrapper around generic-grid-cell.
   
   Props:
   - col: Column index
   - row: Row index
   
   Behavior:
   - Left-click: Trigger the cell (if has content) or select (if empty)
   - Double-click: Open appropriate editor (timeline or cue chain)
   - Right-click: Context menu with Copy/Paste/Edit/Clear and Trigger actions
   - Drag from cell: Start dragging cell content
   - Drop on cell: Move cell content"
  [{:keys [fx/context col row]}]
  (let [display-data (fx/sub-ctx context subs/cell-display-data col row)
        trigger-map (fx/sub-ctx context subs/input-trigger-map)
        learning-target (fx/sub-ctx context subs/input-learning-target)
        has-content? (:has-content? display-data)
        timeline? (:timeline? display-data)
        has-trigger? (boolean (some (fn [[_ v]]
                                      (and (= (:type v) :trigger-cue)
                                           (= (:target v) [col row])))
                                    trigger-map))
        learning? (= learning-target {:type :trigger-cue :target [col row]})
        cell-display-data (assoc display-data
                                 :has-trigger? has-trigger?
                                 :learning? learning?)
        open-editor-event (if timeline?
                            {:event/type :timeline-cue-editor/open}
                            {:event/type :cue-chain/open-editor})
        base-menu-items (if has-content?
                          [{:fx/type :menu-item
                            :text "Copy Cue (Ctrl+C)"
                            :on-action {:event/type :grid/copy-cell :col col :row row}}
                           {:fx/type :menu-item
                            :text "Paste Cue (Ctrl+V)"
                            :on-action {:event/type :grid/paste-cell :col col :row row}}
                           {:fx/type :menu-item
                            :text "Edit Cue..."
                            :on-action (assoc open-editor-event :col col :row row)}
                           {:fx/type :separator-menu-item}
                           {:fx/type :menu-item
                            :text "Clear Cell"
                            :on-action {:event/type :grid/clear-cell :col col :row row}}]
                          [{:fx/type :menu-item
                            :text "Create Cue"
                            :on-action {:event/type :cue-chain/open-editor :col col :row row}}
                           {:fx/type :menu-item
                            :text "Create Timeline Cue"
                            :on-action {:event/type :timeline-cue-editor/open :col col :row row}}
                           {:fx/type :menu-item
                            :text "Paste Cue (Ctrl+V)"
                            :on-action {:event/type :grid/paste-cell :col col :row row}}])
        trigger-menu-items (filterv some?
                                    [{:fx/type :separator-menu-item}
                                     (if learning?
                                       {:fx/type :menu-item
                                        :text "Cancel Learning Trigger"
                                        :on-action {:event/type :input/cancel-learn}}
                                       {:fx/type :menu-item
                                        :text "Learn Trigger..."
                                        :on-action {:event/type :input/start-learn
                                                    :target-action {:type :trigger-cue :target [col row]}}})
                                     (when has-trigger?
                                       {:fx/type :menu-item
                                        :text "Clear Triggers"
                                        :on-action {:event/type :input/clear-cell-triggers
                                                    :target-action {:type :trigger-cue :target [col row]}}})
                                     {:fx/type :menu-item
                                      :id "configure-triggers-menu-item"
                                      :text "Configure Triggers..."
                                      :on-action {:event/type :ui/open-dialog
                                                  :dialog-id :trigger-editor
                                                  :data {:col col
                                                         :row row
                                                         :cell-mode :cue}}}])]
    {:fx/type generic-grid-cell
     :col col
     :row row
     :cell-type :grid
     :display-data cell-display-data
     :on-click {:event/type :grid/cell-clicked}
     :on-right-click open-editor-event
     :drag-config {:drag-type :grid-cell
                   :on-drop {:event/type :grid/move-cell}}
     :label-fn cell-label
     :context-menu-items (into base-menu-items trigger-menu-items)}))


;; Effects Grid Cell


(defn effects-cell
  "Effects grid cell - wrapper around generic-grid-cell.
   
   Props:
   - col: Column index
   - row: Row index
   
   Behavior:
   - Left-click: Toggle the effect cell
   - Double-click: Open effect chain editor dialog
   - Right-click: Context menu with Copy/Paste/Edit/Clear and Trigger actions
   - Drag from cell: Start dragging effects
   - Drop on cell: Move effects"
  [{:keys [fx/context col row]}]
  (let [display-data (fx/sub-ctx context subs/effect-cell-display-data col row)
        trigger-map (fx/sub-ctx context subs/input-trigger-map)
        learning-target (fx/sub-ctx context subs/input-learning-target)
        has-effects? (:has-effects? display-data)
        has-trigger? (boolean (some (fn [[_ v]]
                                      (and (= (:type v) :trigger-effect)
                                           (= (:target v) [col row])))
                                    trigger-map))
        learning? (= learning-target {:type :trigger-effect :target [col row]})
        adapted-data {:active? (:active? display-data)
                      :selected? (:selected? display-data)
                      :has-content? has-effects?
                      :effect-count (:effect-count display-data)
                      :has-effects? has-effects?
                      :name (:name display-data)
                      :has-trigger? has-trigger?
                      :learning? learning?}
        context-menu-items (filterv some?
                                    [{:fx/type :menu-item
                                      :text "Copy Effects (Ctrl+C)"
                                      :on-action {:event/type :effects/copy-cell :col col :row row}}
                                     {:fx/type :menu-item
                                      :text "Paste Effects (Ctrl+V)"
                                      :on-action {:event/type :effects/paste-cell :col col :row row}}
                                     {:fx/type :menu-item
                                      :text "Edit Effect Chain..."
                                      :on-action {:event/type :effect-chain/open-editor :col col :row row}}
                                     {:fx/type :separator-menu-item}
                                     (if learning?
                                       {:fx/type :menu-item
                                        :text "Cancel Learning Trigger"
                                        :on-action {:event/type :input/cancel-learn}}
                                       {:fx/type :menu-item
                                        :text "Learn Trigger..."
                                        :on-action {:event/type :input/start-learn
                                                    :target-action {:type :trigger-effect :target [col row]}}})
                                     (when has-trigger?
                                       {:fx/type :menu-item
                                        :text "Clear Triggers"
                                        :on-action {:event/type :input/clear-cell-triggers
                                                    :target-action {:type :trigger-effect :target [col row]}}})
                                     {:fx/type :menu-item
                                      :text "Configure Triggers..."
                                      :on-action {:event/type :ui/open-dialog
                                                  :dialog-id :trigger-editor
                                                  :data {:col col
                                                         :row row
                                                         :cell-mode :effects}}}
                                     {:fx/type :separator-menu-item}
                                     {:fx/type :menu-item
                                      :text "Clear Cell"
                                      :on-action {:event/type :effects/clear-cell :col col :row row}}])]
    {:fx/type generic-grid-cell
     :col col
     :row row
     :cell-type :effects
     :display-data adapted-data
     :on-click {:event/type :effects/toggle-cell}
     :on-right-click {:event/type :effect-chain/open-editor}
     :drag-config {:drag-type :effects-cell
                   :on-drop {:event/type :effects/move-cell}}
     :label-fn effects-label
     :context-menu-items context-menu-items}))
