(ns laser-show.views.components.cue-chain-sidebar
  "Cue chain sidebar component with drag-and-drop reordering and group support.
   
   This component manages the left sidebar of the cue chain editor,
   displaying the current chain of presets with support for:
   - Multi-select with Click, Ctrl+Click, Shift+Click
   - Drag-and-drop reordering (single item or multiple selected items)
   - Copy/paste operations
   - Enable/disable individual presets
   - Groups with collapse/expand, nesting up to 3 levels
   
   Similar to effect-chain-sidebar but for preset instances."
  (:require [cljfx.api :as fx]
            [laser-show.animation.presets :as presets]
            [laser-show.animation.chains :as chains]
            [laser-show.events.core :as events])
  (:import [javafx.scene.input TransferMode ClipboardContent]))


;; Helper Functions


(defn- depth-class
  "Returns the depth capped at 3 (max nesting level for CSS classes)."
  [depth]
  (min depth 3))


;; Preset Registry Access


(defn- preset-by-id
  "Get a preset definition by its ID from the registry."
  [preset-id]
  (presets/get-preset preset-id))


;; Drag-and-Drop Handlers


(defn- setup-drag-source!
  "Setup drag source handlers on a node for reordering presets."
  [^javafx.scene.Node node path col row]
  (.setOnDragDetected
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (let [dragboard (.startDragAndDrop node (into-array TransferMode [TransferMode/MOVE]))
              content (ClipboardContent.)]
          (.putString content (pr-str path))
          (.setContent dragboard content)
          (events/dispatch! {:event/type :cue-chain/start-multi-drag
                             :col col :row row
                             :initiating-path path})
          (.consume event)))))
  (.setOnDragDone
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (events/dispatch! {:event/type :cue-chain/clear-drag-state})
        (.consume event)))))

(defn- setup-drag-target!
  "Setup drag target handlers on a node for accepting dropped items."
  [^javafx.scene.Node node target-id path is-group? col row]
  (let [drop-position-atom (atom :before)]
    (.setOnDragOver
      node
      (reify javafx.event.EventHandler
        (handle [_ event]
          (when (and (.getDragboard event)
                     (.hasString (.getDragboard event)))
            (.acceptTransferModes event (into-array TransferMode [TransferMode/MOVE]))
            (when is-group?
              (let [bounds (.getBoundsInLocal node)
                    y (.getY event)
                    height (.getHeight bounds)
                    new-pos (if (< y (* height 0.25)) :before :into)]
                (when (not= @drop-position-atom new-pos)
                  (reset! drop-position-atom new-pos)
                  (events/dispatch! {:event/type :cue-chain/update-drop-position
                                     :drop-position new-pos})))))
          (.consume event))))
    (.setOnDragEntered
      node
      (reify javafx.event.EventHandler
        (handle [_ event]
          (when (.hasString (.getDragboard event))
            (reset! drop-position-atom (if is-group? :into :before))
            (events/dispatch! {:event/type :cue-chain/set-drop-target
                               :drop-target-path path
                               :drop-target-id target-id
                               :drop-position (if is-group? :into :before)}))
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
            (events/dispatch! {:event/type :cue-chain/move-items
                               :col col :row row
                               :target-id target-id
                               :drop-position drop-pos})
            (.setDropCompleted event true)
            (.consume event)))))))


;; Selection Handlers


(defn- setup-click-handler!
  "Setup click handler on a node for multi-select behavior."
  [^javafx.scene.Node node path col row]
  (.setOnMouseClicked
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (let [ctrl? (.isControlDown event)
              shift? (.isShiftDown event)]
          (events/dispatch! {:event/type :cue-chain/select-item-at-path
                             :path path
                             :col col
                             :row row
                             :ctrl? ctrl?
                             :shift? shift?}))
        (.consume event)))))


;; Style Class Builders


(defn- group-header-style-classes
  "Build style class vector for group header based on state."
  [{:keys [depth selected? dragging? drop-before? drop-into? effectively-disabled?]}]
  (cond-> ["group-header"
           (str "group-depth-" (depth-class depth))
           (str "group-indent-" (depth-class depth))]
    selected? (conj "group-header-selected")
    drop-into? (conj "group-header-drop-into")
    drop-before? (conj "group-header-drop-before")
    dragging? (conj "group-header-dragging")
    effectively-disabled? (conj "group-header-disabled")))

(defn- group-name-style-classes
  "Build style class vector for group name label based on state."
  [{:keys [depth selected? effectively-disabled?]}]
  (cond-> ["group-name-label"]
    (and (not selected?) (not effectively-disabled?))
    (conj (str "group-name-depth-" (depth-class depth)))
    
    selected? (conj "group-name-selected")
    effectively-disabled? (conj "group-name-disabled")))

(defn- preset-item-style-classes
  "Build style class vector for preset item based on state."
  [{:keys [depth selected? dragging? drop-target? effectively-disabled?]}]
  (cond-> ["chain-item"
           (str "item-indent-" (depth-class depth))]
    selected? (conj "chain-item-selected")
    drop-target? (conj "chain-item-drop-target")
    dragging? (conj "chain-item-dragging")
    effectively-disabled? (conj "chain-item-disabled")))

(defn- preset-name-style-classes
  "Build style class vector for preset name label based on state."
  [{:keys [selected? effectively-disabled?]}]
  (cond-> ["chain-item-name"]
    selected? (conj "chain-item-name-selected")
    effectively-disabled? (conj "chain-item-name-disabled")))


;; Group Header Component


(defn- group-header
  "Header for a group showing collapse toggle, name, and item count."
  [{:keys [col row path group depth selected? dragging? drop-target? drop-position renaming? parent-disabled?]}]
  (let [enabled? (:enabled? group true)
        effectively-disabled? (or (not enabled?) parent-disabled?)
        collapsed? (:collapsed? group false)
        item-count (chains/count-items-recursive (:items group []))
        drop-before? (and drop-target? (= drop-position :before))
        drop-into? (and drop-target? (= drop-position :into))
        
        header-classes (group-header-style-classes
                        {:depth depth
                         :selected? selected?
                         :dragging? dragging?
                         :drop-before? drop-before?
                         :drop-into? drop-into?
                         :effectively-disabled? effectively-disabled?})
        name-classes (group-name-style-classes
                      {:depth depth
                       :selected? selected?
                       :effectively-disabled? effectively-disabled?})]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [node]
                   (let [target-id (:id group)]
                     (setup-drag-source! node path col row)
                     (setup-drag-target! node target-id path true col row)
                     (.setOnMouseClicked node
                       (reify javafx.event.EventHandler
                         (handle [_ event]
                           (let [click-count (.getClickCount event)
                                 ctrl? (.isControlDown event)
                                 shift? (.isShiftDown event)]
                             (cond
                               (= click-count 2)
                               (do
                                 (events/dispatch! {:event/type :cue-chain/start-rename-group
                                                    :path path})
                                 (.consume event))
                               :else
                               (do
                                 (events/dispatch! {:event/type :cue-chain/select-item-at-path
                                                    :path path
                                                    :col col
                                                    :row row
                                                    :ctrl? ctrl?
                                                    :shift? shift?})
                                 (.consume event)))))))))
     :desc {:fx/type :h-box
            :style-class header-classes
            :children [;; Collapse/Expand chevron
                       {:fx/type :button
                        :text (if collapsed? "▶" "▼")
                        :style-class "group-collapse-btn"
                        :on-action {:event/type :cue-chain/toggle-group-collapse
                                    :col col :row row
                                    :path path}}
                       
                       ;; Enable/disable checkbox
                       {:fx/type :check-box
                        :selected enabled?
                        :on-selected-changed {:event/type :cue-chain/set-item-enabled
                                              :col col :row row
                                              :path path}}
                       
                       ;; Group name
                       (if renaming?
                         {:fx/type fx/ext-on-instance-lifecycle
                          :on-created (fn [^javafx.scene.control.TextField node]
                                        (.requestFocus node)
                                        (.selectAll node))
                          :desc {:fx/type :text-field
                                 :text (or (:name group) "Group")
                                 :style-class "group-name-input"
                                 :on-action (fn [^javafx.event.ActionEvent e]
                                              (let [text-field (.getSource e)
                                                    new-name (.getText text-field)]
                                                (events/dispatch! {:event/type :cue-chain/rename-group
                                                                   :col col :row row
                                                                   :path path
                                                                   :name new-name})))
                                 :on-key-pressed (fn [^javafx.scene.input.KeyEvent e]
                                                   (case (.getCode e)
                                                     javafx.scene.input.KeyCode/ESCAPE
                                                     (events/dispatch! {:event/type :cue-chain/cancel-rename-group})
                                                     nil))
                                 :on-focused-changed (fn [focused?]
                                                       (when-not focused?
                                                         (events/dispatch! {:event/type :cue-chain/cancel-rename-group})))}}
                         {:fx/type :label
                          :text (or (:name group) "Group")
                          :style-class name-classes})
                       
                       ;; Item count badge
                       {:fx/type :label
                        :text (str "(" item-count ")")
                        :style-class "group-count-badge"}
                       
                       {:fx/type :region :h-box/hgrow :always}
                       
                       ;; Ungroup button
                       {:fx/type :button
                        :text "⊗"
                        :style-class "group-ungroup-btn"
                        :on-action {:event/type :cue-chain/ungroup
                                    :col col :row row
                                    :path path}}]}}))


;; Preset Item Card Component


(defn- preset-item-card
  "A single preset in the cue chain sidebar with drag-and-drop support."
  [{:keys [col row path preset-instance preset-def depth selected? dragging? drop-target? parent-disabled?]}]
  (let [enabled? (:enabled? preset-instance true)
        effectively-disabled? (or (not enabled?) parent-disabled?)
        
        item-classes (preset-item-style-classes
                      {:depth depth
                       :selected? selected?
                       :dragging? dragging?
                       :drop-target? drop-target?
                       :effectively-disabled? effectively-disabled?})
        name-classes (preset-name-style-classes
                      {:selected? selected?
                       :effectively-disabled? effectively-disabled?})]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [node]
                   (let [target-id (:id preset-instance)]
                     (setup-drag-source! node path col row)
                     (setup-drag-target! node target-id path false col row)
                     (setup-click-handler! node path col row)))
     :desc {:fx/type :h-box
            :style-class item-classes
            :children (filterv
                        some?
                        [;; Enable/disable checkbox
                         {:fx/type :check-box
                          :selected enabled?
                          :on-selected-changed {:event/type :cue-chain/set-item-enabled
                                                :col col :row row
                                                :path path}}
                         
                         ;; Preset name
                         {:fx/type :label
                          :text (or (:name preset-def) "Unknown")
                          :style-class name-classes}
                         
                         ;; Effects indicator (show if preset has effects)
                         (when (seq (:effects preset-instance))
                           {:fx/type :label
                            :text (str " [" (count (:effects preset-instance)) " fx]")
                            :style "-fx-text-fill: #6090C0; -fx-font-size: 9;"})])}}))


;; Recursive Chain Item Renderer


(defn render-cue-chain-item
  "Recursively render a cue chain item (preset or group)."
  [{:keys [col row item path depth selected-paths dragging-paths drop-target-path drop-position renaming-path parent-disabled?]}]
  (if (chains/group? item)
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
                        {:fx/type render-cue-chain-item
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
    ;; Render preset
    {:fx/type preset-item-card
     :col col :row row
     :path path
     :preset-instance item
     :preset-def (preset-by-id (:preset-id item))
     :depth depth
     :selected? (contains? selected-paths path)
     :dragging? (contains? (or dragging-paths #{}) path)
     :drop-target? (= path drop-target-path)
     :parent-disabled? parent-disabled?}))


;; Group Toolbar


(defn- group-toolbar
  "Toolbar with group-related buttons."
  [{:keys [col row selection-count can-create-group?]}]
  {:fx/type :h-box
   :spacing 4
   :children [{:fx/type :button
               :text "🗁 New"
               :style-class "chain-toolbar-btn"
               :on-action {:event/type :cue-chain/create-empty-group
                           :col col :row row}}
              {:fx/type :button
               :text "☐ Group"
               :disable (or (zero? selection-count) (not can-create-group?))
               :style-class "chain-toolbar-btn"
               :on-action {:event/type :cue-chain/group-selected
                           :col col :row row}}]})


;; Main Sidebar Component


(defn cue-chain-sidebar
  "Left sidebar showing the cue chain with drag-and-drop reordering.
   
   Props:
   - :col, :row - Grid cell coordinates
   - :cue-chain - The cue chain data {:items [...]}
   - :selected-paths - Set of selected paths
   - :dragging-paths - Set of paths being dragged
   - :drop-target-path - Path of current drop target
   - :drop-position - Current drop position (:before or :into)
   - :renaming-path - Path of group being renamed
   - :can-paste? - Whether clipboard has pasteable items"
  [{:keys [col row cue-chain selected-paths dragging-paths drop-target-path drop-position renaming-path can-paste?]}]
  (let [items (:items cue-chain [])
        selected-paths-set (or selected-paths #{})
        selection-count (count selected-paths-set)
        chain-depth (chains/nesting-depth items)
        can-create-group? (< chain-depth chains/max-nesting-depth)]
    {:fx/type :v-box
     :style-class "chain-sidebar"
     :children [{:fx/type :h-box
                 :alignment :center-left
                 :children (filterv some?
                             [{:fx/type :label
                               :text "CUE CHAIN"
                               :style-class "chain-header-label"}
                              {:fx/type :region :h-box/hgrow :always}
                              (when (pos? selection-count)
                                {:fx/type :label
                                 :text (str selection-count " selected")
                                 :style-class "chain-selection-count"})])}
                {:fx/type :label
                 :text "Ctrl+Click multi-select • Drag to reorder • Ctrl+G group"
                 :style-class "chain-hint-text"}
                
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
                             :style-class "chain-toolbar-btn"
                             :on-action {:event/type :cue-chain/copy-selected
                                         :col col :row row}}
                            {:fx/type :button
                             :text "Paste"
                             :disable (not can-paste?)
                             :style-class "chain-toolbar-btn"
                             :on-action {:event/type :cue-chain/paste-items
                                         :col col :row row}}
                            {:fx/type :button
                             :text "Del"
                             :disable (zero? selection-count)
                             :style-class "chain-toolbar-btn-danger"
                             :on-action {:event/type :cue-chain/delete-selected
                                         :col col :row row}}]}
                (if (empty? items)
                  {:fx/type :label
                   :text "No presets\nAdd from bank →"
                   :style-class "chain-empty-text"}
                  {:fx/type :scroll-pane
                   :fit-to-width true
                   :fit-to-height false
                   :hbar-policy :as-needed
                   :v-box/vgrow :always
                   :style-class "chain-scroll-pane"
                   :content {:fx/type :v-box
                             :spacing 4
                             :children (vec
                                        (map-indexed
                                          (fn [idx item]
                                            {:fx/type render-cue-chain-item
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
                                          items))}})]}))
