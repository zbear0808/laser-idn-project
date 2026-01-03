(ns laser-show.views.components.effect-chain-sidebar
  "Effect chain sidebar component with drag-and-drop reordering and group support.
   
   This component manages the left sidebar of the effect chain editor,
   displaying the current chain of effects with support for:
   - Multi-select with Click, Ctrl+Click, Shift+Click
   - Drag-and-drop reordering (single item or multiple selected items)
   - Copy/paste operations
   - Enable/disable individual effects
   - Groups with collapse/expand, nesting up to 3 levels
   
   Multi-select drag-and-drop:
   - Dragging a selected item moves all selected items together
   - Dragging an unselected item auto-selects and moves just that item
   - Items maintain their relative order when moved
   
   Extracted from effect-chain-editor for maintainability."
  (:require [cljfx.api :as fx]
            [laser-show.animation.effects :as effects]
            [laser-show.events.core :as events])
  (:import [javafx.scene.input TransferMode ClipboardContent]))


;; Constants


(def group-colors
  "Colors for different group nesting depths."
  ["#4A7B9D"   ;; Level 0 - Blue
   "#6B5B8C"   ;; Level 1 - Purple
   "#5B8C6B"   ;; Level 2 - Green
   "#8C7B5B"]) ;; Level 3 - Brown

(def depth-indent
  "Indentation in pixels per nesting level."
  16)


;; Effect Registry Access


(defn- effect-by-id
  "Get an effect definition by its ID from the registry."
  [effect-id]
  (effects/get-effect effect-id))


;; Drag-and-Drop Handlers


(defn- setup-drag-source!
  "Setup drag source handlers on a node for reordering effects.
   Supports multi-select drag: if the dragged item is part of a selection,
   all selected items will be moved together. Otherwise, just the dragged item moves.
   
   The actual determination of which paths to drag is done by the :effects/start-multi-drag
   event handler, which has access to the current selection state."
  [^javafx.scene.Node node path col row dispatch!]
  (.setOnDragDetected
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (let [dragboard (.startDragAndDrop node (into-array TransferMode [TransferMode/MOVE]))
              content (ClipboardContent.)]
          ;; Store initiating path as string (the event handler will determine all paths to drag)
          (.putString content (pr-str path))
          (.setContent dragboard content)
          ;; Dispatch event to set up multi-drag state (determines dragging-paths from selection)
          (dispatch! {:event/type :effects/start-multi-drag
                      :col col :row row
                      :initiating-path path})
          (.consume event)))))
  (.setOnDragDone
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (dispatch! {:event/type :ui/update-dialog-data
                    :dialog-id :effect-chain-editor
                    :updates {:dragging-paths nil
                              :drop-target-path nil}})
        (.consume event)))))

(defn- setup-drag-target!
  "Setup drag target handlers on a node for accepting dropped items.
   Supports multi-select drag: dispatches :effects/move-items which moves all
   items in :dragging-paths to the target location.
   
   Uses ID-based targeting for robust drop handling.
   
   For groups, detects drop position based on mouse Y:
   - Upper 25%: drop BEFORE the group
   - Lower 75%: drop INTO the group
   
   For effects, always drops BEFORE (insert at position)."
  [^javafx.scene.Node node target-id path is-group? col row dispatch!]
  (let [drop-position-atom (atom :before)]
    (.setOnDragOver
      node
      (reify javafx.event.EventHandler
        (handle [_ event]
          (when (and (.getDragboard event)
                     (.hasString (.getDragboard event)))
            (.acceptTransferModes event (into-array TransferMode [TransferMode/MOVE]))
            ;; For groups, detect if in upper zone for "before" placement
            (when is-group?
              (let [bounds (.getBoundsInLocal node)
                    y (.getY event)
                    height (.getHeight bounds)
                    new-pos (if (< y (* height 0.25)) :before :into)]
                (when (not= @drop-position-atom new-pos)
                  (reset! drop-position-atom new-pos)
                  (dispatch! {:event/type :ui/update-dialog-data
                              :dialog-id :effect-chain-editor
                              :updates {:drop-position new-pos}})))))
          (.consume event))))
    (.setOnDragEntered
      node
      (reify javafx.event.EventHandler
        (handle [_ event]
          (when (.hasString (.getDragboard event))
            (reset! drop-position-atom (if is-group? :into :before))
            (dispatch! {:event/type :ui/update-dialog-data
                        :dialog-id :effect-chain-editor
                        :updates {:drop-target-path path
                                  :drop-target-id target-id
                                  :drop-position (if is-group? :into :before)}}))
          (.consume event))))
    (.setOnDragExited
      node
      (reify javafx.event.EventHandler
        (handle [_ event]
          (.consume event))))
    (.setOnDragDropped
      node
      (reify javafx.event.EventHandler
        (handle [_ event]
          (let [drop-pos @drop-position-atom]
            ;; Dispatch move-items event - the handler will use dragging-paths from state
            (dispatch! {:event/type :effects/move-items
                        :col col :row row
                        :target-id target-id
                        :drop-position drop-pos})
            (.setDropCompleted event true)
            (.consume event)))))))


;; Selection Handlers


(defn- setup-click-handler!
  "Setup click handler on a node for multi-select behavior.
   Now uses path-based selection for nested items.
   - Click: Select single
   - Ctrl+Click: Toggle selection
   - Shift+Click: Range select (based on visual order)"
  [^javafx.scene.Node node path col row]
  (.setOnMouseClicked
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (let [ctrl? (.isControlDown event)
              shift? (.isShiftDown event)]
          (events/dispatch! {:event/type :effects/select-item-at-path
                             :path path
                             :col col
                             :row row
                             :ctrl? ctrl?
                             :shift? shift?}))
        (.consume event)))))


;; Group Header Component


(defn group-header
  "Header for a group showing collapse toggle, name, and item count.
   
   Props:
   - :col, :row - Grid cell coordinates
   - :path - Path to this group
   - :group - Group data map
   - :depth - Nesting depth (0 = top level)
   - :selected? - Whether this group is selected
   - :dragging? - Whether this group is being dragged (part of dragging-paths)
   - :drop-target? - Whether this is the current drop target
   - :drop-position - Current drop position (:before or :into) when drop-target?
   - :renaming? - Whether this group is currently being renamed
   - :parent-disabled? - Whether any parent group is disabled"
  [{:keys [col row path group depth selected? dragging? drop-target? drop-position renaming? parent-disabled?]}]
  (let [enabled? (:enabled? group true)
        effectively-disabled? (or (not enabled?) parent-disabled?)
        collapsed? (:collapsed? group false)
        item-count (count (:items group []))
        effect-count (effects/count-effects-recursive (:items group []))
        border-color (get group-colors (min depth (dec (count group-colors))))
        ;; Visual feedback: "before" = top border only, "into" = full border
        drop-before? (and drop-target? (= drop-position :before))
        drop-into? (and drop-target? (= drop-position :into))]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [node]
                   (let [dispatch! events/dispatch!
                         target-id (:id group)]
                     (setup-drag-source! node path col row dispatch!)
                     (setup-drag-target! node target-id path true col row dispatch!)
                     ;; Add double-click-to-rename on the whole row
                     (.setOnMouseClicked node
                       (reify javafx.event.EventHandler
                         (handle [_ event]
                           (let [click-count (.getClickCount event)
                                 ctrl? (.isControlDown event)
                                 shift? (.isShiftDown event)]
                             (cond
                               ;; Double-click anywhere on group row - start rename
                               (= click-count 2)
                               (do
                                 (dispatch! {:event/type :effects/start-rename-group
                                             :path path})
                                 (.consume event))
                               
                               ;; Single-click - select (unless on checkbox/buttons)
                               :else
                               (do
                                 (dispatch! {:event/type :effects/select-item-at-path
                                             :path path
                                             :col col
                                             :row row
                                             :ctrl? ctrl?
                                             :shift? shift?})
                                 (.consume event)))))))))
     :desc {:fx/type :h-box
            :spacing 6
            :alignment :center-left
            :style (str "-fx-background-color: " (cond
                                                    drop-into? "#5A8FCF"
                                                    selected? "#4A6FA5"
                                                    :else "#333333") ";"
                        "-fx-padding: 6 8 6 " (+ 8 (* depth depth-indent)) ";"
                        "-fx-background-radius: 4;"
                        "-fx-border-color: " border-color ";"
                        "-fx-border-width: 0 0 0 3;"
                        "-fx-border-radius: 4;"
                        ;; Different visual feedback for before vs into
                        (cond
                          drop-before? "-fx-border-color: #7AB8FF; -fx-border-width: 3 0 0 3;"
                          drop-into? "-fx-border-color: #7AB8FF; -fx-border-width: 2;"
                          :else nil)
                        (when dragging? "-fx-opacity: 0.5;")
                        (when effectively-disabled? "-fx-opacity: 0.6;"))
            :children [;; Collapse/Expand chevron
                       {:fx/type :button
                        :text (if collapsed? "▶" "▼")
                        :style "-fx-background-color: transparent; -fx-text-fill: #808080; -fx-font-size: 10; -fx-padding: 0 2; -fx-min-width: 16;"
                        :on-action {:event/type :effects/toggle-group-collapse
                                    :col col :row row
                                    :path path}}
                       
                       ;; Enable/disable checkbox
                       {:fx/type :check-box
                        :selected enabled?
                        :on-selected-changed {:event/type :effects/set-item-enabled-at-path
                                              :col col :row row
                                              :path path}}
                       
                       ;; Group name (clickable for selection, double-clickable for rename)
                       (if renaming?
                         ;; Text field for renaming
                         {:fx/type fx/ext-on-instance-lifecycle
                          :on-created (fn [^javafx.scene.control.TextField node]
                                        (.requestFocus node)
                                        (.selectAll node))
                          :desc {:fx/type :text-field
                                 :text (or (:name group) "Group")
                                 :style "-fx-background-color: #252525; -fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold; -fx-padding: 0 4;"
                                 :on-action (fn [^javafx.event.ActionEvent e]
                                              (let [text-field (.getSource e)
                                                    new-name (.getText text-field)]
                                                (events/dispatch! {:event/type :effects/rename-group
                                                                   :col col :row row
                                                                   :path path
                                                                   :name new-name})))
                                 :on-key-pressed (fn [^javafx.scene.input.KeyEvent e]
                                                   (case (.getCode e)
                                                     javafx.scene.input.KeyCode/ESCAPE
                                                     (events/dispatch! {:event/type :effects/cancel-rename-group})
                                                     nil))
                                 :on-focused-changed (fn [focused?]
                                                       (when-not focused?
                                                         (events/dispatch! {:event/type :effects/cancel-rename-group})))}}
                         ;; Label for display (click handled by parent h-box)
                         {:fx/type :label
                          :text (or (:name group) "Group")
                          :style (str "-fx-text-fill: "
                                     (cond
                                       effectively-disabled? "#808080"
                                       selected? "white"
                                       :else border-color) ";"
                                     "-fx-font-size: 12;"
                                     "-fx-font-weight: bold;"
                                     "-fx-cursor: hand;")})
                       
                       ;; Item count badge
                       {:fx/type :label
                        :text (str "(" effect-count ")")
                        :style "-fx-text-fill: #606060; -fx-font-size: 10;"}
                       
                       {:fx/type :region :h-box/hgrow :always}
                       
                       ;; Ungroup button
                       {:fx/type :button
                        :text "⊗"
                        :style "-fx-background-color: #505050; -fx-text-fill: #A0A0A0; -fx-padding: 1 3; -fx-font-size: 8;"
                        :on-action {:event/type :effects/ungroup
                                    :col col :row row
                                    :path path}}]}}))


;; Chain Item Card Component (for effects)


(defn chain-item-card
  "A single effect in the chain sidebar with drag-and-drop support.
   Shows enable checkbox, effect name with delete button.
   Supports multi-select with Click/Ctrl+Click/Shift+Click on the name label.
   
   Props:
   - :col, :row - Grid cell coordinates
   - :path - Path to this effect (e.g., [0] or [1 :items 2])
   - :effect - Effect instance data
   - :effect-def - Effect definition from registry
   - :depth - Nesting depth (0 = top level)
   - :selected? - Whether this item is selected
   - :dragging? - Whether this item is being dragged (part of dragging-paths)
   - :drop-target? - Whether this is the current drop target
   - :parent-disabled? - Whether any parent group is disabled"
  [{:keys [col row path effect effect-def depth selected? dragging? drop-target? parent-disabled?]}]
  (let [enabled? (:enabled? effect true)
        effectively-disabled? (or (not enabled?) parent-disabled?)]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [node]
                   (let [dispatch! events/dispatch!
                         target-id (:id effect)]
                     (setup-drag-source! node path col row dispatch!)
                     (setup-drag-target! node target-id path false col row dispatch!)
                     (setup-click-handler! node path col row)))
     :desc {:fx/type :h-box
            :spacing 6
            :alignment :center-left
            :style (str "-fx-background-color: " (cond
                                                    drop-target? "#5A8FCF"
                                                    selected? "#4A6FA5"
                                                    :else "#3D3D3D") ";"
                        "-fx-padding: 6 8 6 " (+ 8 (* depth depth-indent)) ";"
                        "-fx-background-radius: 4;"
                        (when drop-target? "-fx-border-color: #7AB8FF; -fx-border-width: 2 0 0 0;")
                        (when dragging? "-fx-opacity: 0.5;")
                        (when effectively-disabled? "-fx-opacity: 0.6;"))
            :children [;; Enable/disable checkbox (does NOT trigger selection)
                       {:fx/type :check-box
                        :selected enabled?
                        :on-selected-changed {:event/type :effects/set-item-enabled-at-path
                                              :col col :row row
                                              :path path}}
                       
                       ;; Effect name
                       {:fx/type :label
                        :text (or (:name effect-def) "Unknown")
                        :style (str "-fx-text-fill: "
                                   (cond
                                     effectively-disabled? "#808080"
                                     selected? "white"
                                     :else "#E0E0E0") ";"
                                   "-fx-font-size: 12;")}]}}))


;; Recursive Chain Item Renderer


(defn render-chain-item
  "Recursively render a chain item (effect or group).
   
   Props:
   - :col, :row - Grid cell coordinates
   - :item - The item to render (effect or group)
   - :path - Path to this item
   - :depth - Nesting depth (0 = top level)
   - :selected-paths - Set of selected paths
   - :dragging-paths - Set of paths being dragged (or nil/empty for none)
   - :drop-target-path - Current drop target path (or nil)
   - :drop-position - Current drop position (:before or :into)
   - :renaming-path - Path of group being renamed (or nil)
   - :parent-disabled? - Whether any parent group is disabled"
  [{:keys [col row item path depth selected-paths dragging-paths drop-target-path drop-position renaming-path parent-disabled?]}]
  (if (effects/group? item)
    ;; Render group with header and children
    (let [collapsed? (:collapsed? item false)
          children-items (:items item [])
          enabled? (:enabled? item true)
          effectively-disabled? (or (not enabled?) parent-disabled?)]
      {:fx/type :v-box
       :spacing 2
       :children (into
                  [{:fx/type group-header
                    :col col :row row
                    :path path
                    :group item
                    :depth depth
                    :selected? (contains? selected-paths path)
                    :dragging? (contains? (or dragging-paths #{}) path)
                    :drop-target? (= path drop-target-path)
                    :drop-position drop-position
                    :renaming? (= path renaming-path)
                    :parent-disabled? parent-disabled?}]
                  (when-not collapsed?
                    (map-indexed
                      (fn [idx child]
                        {:fx/type render-chain-item
                         :fx/key (conj path :items idx)
                         :col col :row row
                         :item child
                         :path (vec (concat path [:items idx]))
                         :depth (inc depth)
                         :selected-paths selected-paths
                         :dragging-paths dragging-paths
                         :drop-target-path drop-target-path
                         :drop-position drop-position
                         :renaming-path renaming-path
                         :parent-disabled? effectively-disabled?})
                      children-items)))})
    ;; Render effect
    {:fx/type chain-item-card
     :col col :row row
     :path path
     :effect item
     :effect-def (effect-by-id (:effect-id item))
     :depth depth
     :selected? (contains? selected-paths path)
     :dragging? (contains? (or dragging-paths #{}) path)
     :drop-target? (= path drop-target-path)
     :parent-disabled? parent-disabled?}))


;; Group Toolbar


(defn group-toolbar
  "Toolbar with group-related buttons.
   
   Props:
   - :col, :row - Grid cell coordinates
   - :selection-count - Number of selected items
   - :can-create-group? - Whether a new group can be created at current depth"
  [{:keys [col row selection-count can-create-group?]}]
  {:fx/type :h-box
   :spacing 4
   :children [{:fx/type :button
               :text "🗁 New"
               :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 9; -fx-padding: 2 6;"
               :on-action {:event/type :effects/create-empty-group
                           :col col :row row}}
              {:fx/type :button
               :text "☐ Group"
               :disable (or (zero? selection-count) (not can-create-group?))
               :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 9; -fx-padding: 2 6;"
               :on-action {:event/type :effects/group-selected
                           :col col :row row}}]})


;; Main Sidebar Component


(defn chain-list-sidebar
  "Left sidebar showing the effect chain with drag-and-drop reordering.
   Supports groups with nesting and multi-select via selected-paths set.
   
   Multi-select drag-and-drop: when dragging a selected item, all selected
   items move together. Items maintain their relative order.
   
   Props:
   - :col, :row - Grid cell coordinates
   - :effect-chain - Vector of effect/group instances
   - :selected-effect-indices - Set of selected indices (legacy, for flat selection)
   - :selected-paths - Set of selected paths (for nested selection)
   - :dragging-paths - Set of paths being dragged (or nil for none)
   - :drop-target-path - Path of current drop target (or nil)
   - :drop-position - Current drop position (:before or :into)
   - :renaming-path - Path of group being renamed (or nil)
   - :can-paste? - Whether clipboard has pasteable effects"
  [{:keys [col row effect-chain selected-effect-indices selected-paths
           dragging-paths drop-target-path drop-position renaming-path can-paste?]}]
  (let [;; Support both legacy index selection and new path selection
        selected-paths-set (or selected-paths
                               (when selected-effect-indices
                                 (into #{} (map vector selected-effect-indices))))
        selection-count (count (or selected-paths-set #{}))
        chain-depth (effects/nesting-depth effect-chain)
        can-create-group? (< chain-depth effects/max-nesting-depth)]
    {:fx/type :v-box
     :spacing 8
     :pref-width 200
     :min-width 200
     :style "-fx-background-color: #252525; -fx-padding: 8;"
     :children [{:fx/type :h-box
                 :alignment :center-left
                 :children (filterv some?
                             [{:fx/type :label
                               :text "CHAIN"
                               :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                              {:fx/type :region :h-box/hgrow :always}
                              (when (pos? selection-count)
                                {:fx/type :label
                                 :text (str selection-count " selected")
                                 :style "-fx-text-fill: #4A6FA5; -fx-font-size: 9;"})])}
                {:fx/type :label
                 :text "Ctrl+Click multi-select • Drag to reorder"
                 :style "-fx-text-fill: #505050; -fx-font-size: 8; -fx-font-style: italic;"}
                
                ;; Group toolbar
                {:fx/type group-toolbar
                 :col col :row row
                 :selection-count selection-count
                 :can-create-group? can-create-group?}
                
                ;; Action buttons for copy/paste
                {:fx/type :h-box
                 :spacing 4
                 :children [{:fx/type :button
                             :text "Copy"
                             :disable (zero? selection-count)
                             :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 9; -fx-padding: 2 6;"
                             :on-action {:event/type :effects/copy-selected
                                         :col col :row row}}
                            {:fx/type :button
                             :text "Paste"
                             :disable (not can-paste?)
                             :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 9; -fx-padding: 2 6;"
                             :on-action {:event/type :effects/paste-into-chain
                                         :col col :row row}}
                            {:fx/type :button
                             :text "Del"
                             :disable (zero? selection-count)
                             :style "-fx-background-color: #803030; -fx-text-fill: white; -fx-font-size: 9; -fx-padding: 2 6;"
                             :on-action {:event/type :effects/delete-selected
                                         :col col :row row}}]}
                (if (empty? effect-chain)
                  {:fx/type :label
                   :text "No effects\nAdd from bank →"
                   :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-font-size: 11;"}
                  {:fx/type :scroll-pane
                   :fit-to-width true
                   :v-box/vgrow :always
                   :style "-fx-background-color: transparent; -fx-background: #252525;"
                   :content {:fx/type :v-box
                             :spacing 4
                             :children (vec
                                        (map-indexed
                                          (fn [idx item]
                                            {:fx/type render-chain-item
                                             :fx/key [idx]
                                             :col col :row row
                                             :item item
                                             :path [idx]
                                             :depth 0
                                             :selected-paths selected-paths-set
                                             :dragging-paths dragging-paths
                                             :drop-target-path drop-target-path
                                             :drop-position drop-position
                                             :renaming-path renaming-path})
                                          effect-chain))}})]}))
