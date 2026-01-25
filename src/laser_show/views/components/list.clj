(ns laser-show.views.components.list
  "Self-contained hierarchical list component with drag-and-drop reordering and group support.
   
   This component manages its own internal UI state (selection, drag/drop) and
   communicates changes to parents via callbacks. This eliminates the need for
   external event dispatchers and ensures consistent behavior across all usages.
   
   Features:
   - Multi-select with Click, Ctrl+Click, Shift+Click
   - Drag-and-drop reordering (single item or multiple selected items)
   - Copy/paste operations with deep copy (new UUIDs)
   - Delete with proper cleanup
   - Groups/folders with collapse/expand, nesting up to 3 levels
   - Renaming items and groups
   - Keyboard shortcuts (Ctrl+C/V/X/A/G, Delete, Escape, F2)
   
   Callback-based API:
   - :on-items-changed - Called with new items vector when items are reordered/modified
   - :on-selection-changed - Called with {:selected-ids #{...} :last-selected-id uuid}
   - :on-copy - Called with copied items for parent to store in clipboard
   
   Helper factories for common patterns:
   - make-dispatch-callback - Creates :on-items-changed that dispatches an event
   - make-selection-callback - Creates :on-selection-changed that dispatches an event
   - make-registry-label - Creates :get-item-label that looks up from a registry
   - make-case-label - Creates :get-item-label from a keyword->string map
   
   Wrapper component for even simpler usage:
   - list-editor - All-in-one wrapper with keyboard handling
   
   Styling is defined in laser-show.css.list."
  (:require
   [cljfx.api :as fx]
   [clojure.tools.logging :as log]
   [laser-show.animation.chains :as chains]
   [laser-show.common.util :as u]
   [laser-show.events.core :as events]
   [laser-show.subs :as subs]
   [laser-show.state.core :as state])
  (:import
   [javafx.scene.input ClipboardContent TransferMode]))

;; State Access Helpers

(defn- get-ui-state
  "Get the list-ui state for a component."
  [component-id]
  (state/get-in-state [:list-ui component-id]))

(defn- get-selected-ids
  "Get selected IDs set from component state."
  [component-id]
  (or (:selected-ids (get-ui-state component-id)) #{}))


;; Callback Helpers

(defn- invoke-items-changed!
  "Call the :on-items-changed callback if present."
  [props new-items]
  (when-let [callback (:on-items-changed props)]
    (callback new-items)))

(defn- invoke-copy!
  "Call the :on-copy callback if present, with warning if missing."
  [props copied-items]
  (if-let [callback (:on-copy props)]
    (callback copied-items)
    (log/warn "copy-selected! no :on-copy callback in props")))


;; JavaFX Event Handler Helper

(defn- on-fx-event
  "Create a JavaFX EventHandler from a Clojure function."
  [f]
  (reify javafx.event.EventHandler
    (handle [_ event] (f event))))


;; Selection Logic (Event Dispatching)

(defn- handle-selection!
  "Handle selection based on click modifiers."
  [component-id item-id ctrl? shift? items]
  (let [mode (cond ctrl? :ctrl
                   shift? :shift
                   :else :single)
        event (if shift?
                (let [{:keys [last-selected-id]} (get-ui-state component-id)
                      all-ids (chains/collect-all-ids items)
                      anchor-id (or last-selected-id item-id)
                      anchor-idx (.indexOf all-ids anchor-id)
                      target-idx (.indexOf all-ids item-id)
                      start (min anchor-idx target-idx)
                      end (max anchor-idx target-idx)
                      new-selected (if (and (>= anchor-idx 0) (>= target-idx 0))
                                     (set (subvec all-ids start (inc end)))
                                     #{item-id})]
                  (u/->map& component-id item-id mode
                            :event/type :list/select-item
                            :selected-ids-override new-selected
                            :last-id-override last-selected-id))
                (u/->map& component-id item-id mode
                          :event/type :list/select-item))]
    (events/dispatch! event)))

(defn- select-all!
  "Select all items in the component."
  [component-id items]
  (let [all-ids (chains/collect-all-ids items)]
    (events/dispatch! (u/->map& component-id all-ids
                                :event/type :list/select-all))))

(defn- clear-selection!
  "Clear selection and cancel rename."
  [component-id]
  (events/dispatch! {:event/type :list/clear-selection
                     :component-id component-id}))


;; Drag-and-Drop Logic (Event Dispatching)

(defn- start-drag!
  "Start a drag operation. If dragged item is selected, drag all selected items.
   Otherwise, select only the dragged item and drag it."
  [component-id item-id]
  (events/dispatch! (u/->map& component-id item-id
                              :event/type :list/start-drag)))

(defn- update-drop-target!
  "Update drop target during drag over."
  [component-id target-id drop-position]
  (events/dispatch! (u/->map& component-id target-id drop-position
                              :event/type :list/update-drop-target)))

(defn- handle-drop!
  "Handle drop operation by dispatching to async handler."
  [component-id target-id drop-position items props]
  (when-let [dragging-ids (seq (:dragging-ids (get-ui-state component-id)))]
    (when (seq items)
      (let [{:keys [on-change-event on-change-params items-path]} props]
        (events/dispatch!
          (u/->map& component-id items dragging-ids target-id drop-position
                    on-change-event on-change-params items-path
                    :event/type :list/perform-drop
                    :items-key :items))))))

(defn- clear-drag-state!
  "Clear all drag-related state after drop or cancel."
  [component-id]
  (events/dispatch! {:event/type :list/clear-drag
                     :component-id component-id}))


;; Item Operations (Callbacks Only - No State Mutation)

(defn- delete-selected!
  "Delete selected items and call :on-items-changed."
  [component-id items props]
  (when-let [selected-ids (seq (get-selected-ids component-id))]
    (let [id->path (chains/find-paths-by-ids items selected-ids)
          paths-to-delete (vals id->path)
          new-items (chains/delete-paths-safely items paths-to-delete)]
      (clear-selection! component-id)
      (invoke-items-changed! props new-items))))

(defn- copy-selected!
  "Copy selected items and call :on-copy callback."
  [component-id items props]
  (when-let [selected-ids (seq (get-selected-ids component-id))]
    (let [id->path (chains/find-paths-by-ids items selected-ids)
          selected-items (mapv #(chains/get-item-at-path items %) (vals id->path))
          copied-items (chains/deep-copy-items selected-items)]
      (invoke-copy! props copied-items))))

(defn- paste-items!
  "Paste items from clipboard after selected item (or at end)."
  [component-id items props]
  (when-let [clipboard-items (seq (:clipboard-items props))]
    (let [{:keys [last-selected-id]} (get-ui-state component-id)
          items-to-paste (chains/deep-copy-items clipboard-items)
          path-for-last (when last-selected-id (chains/find-path-by-id items last-selected-id))
          insert-idx (if path-for-last
                       (inc (first path-for-last))
                       (count items))
          new-items (reduce-kv
                      (fn [chain idx item]
                        (chains/insert-at-path chain [(+ insert-idx idx)] item))
                      items
                      (vec items-to-paste))
          pasted-ids (set (map :id items-to-paste))
          item-id (:id (last items-to-paste))]
      (events/dispatch! (u/->map& component-id item-id
                                  :event/type :list/select-item
                                  :mode :single
                                  :selected-ids-override pasted-ids))
      (if-let [callback (:on-items-changed props)]
        (callback new-items)
        (log/warn "paste-items! no :on-items-changed callback")))))


;; Group Operations (Callbacks Only)

(defn- normalize-selected-ids
  "Remove redundant descendant IDs when a group AND all its descendants are selected."
  [selected-ids items]
  (let [id->path (chains/find-paths-by-ids items selected-ids)
        group-ids (filterv
                    (fn [id]
                      (when-let [path (get id->path id)]
                        (chains/group? (chains/get-item-at-path items path))))
                    selected-ids)]
    (reduce
      (fn [ids group-id]
        (let [path (get id->path group-id)
              group (chains/get-item-at-path items path)
              descendant-ids (chains/collect-descendant-ids group)]
          (if (and (seq descendant-ids)
                   (every? #(contains? selected-ids %) descendant-ids))
            (apply disj ids descendant-ids)
            ids)))
      (set selected-ids)
      group-ids)))

(defn- create-empty-group!
  "Create an empty group at the end of the chain."
  [component-id items props]
  (let [new-group (chains/create-group [])
        new-items (conj items new-group)
        group-id (:id new-group)]
    (events/dispatch! {:event/type :list/select-item
                       :component-id component-id
                       :item-id group-id
                       :mode :single})
    (invoke-items-changed! props new-items)))

(defn- group-selected!
  "Group selected items into a new folder.
   Items must be at the same nesting level to be grouped together."
  [component-id items props]
  (if-let [selected-ids (seq (get-selected-ids component-id))]
    (let [normalized-ids (normalize-selected-ids selected-ids items)
          id->path (chains/find-paths-by-ids items normalized-ids)
          all-paths (vals id->path)
          parent-paths (mapv (fn [path]
                               (if (= 1 (count path))
                                 []
                                 (vec (butlast path))))
                             all-paths)
          unique-parents (set parent-paths)
          same-level? (= 1 (count unique-parents))
          common-parent (first unique-parents)]
      (if (and same-level? (seq all-paths))
        (let [sorted-paths (sort (fn [a b] (compare (vec a) (vec b))) all-paths)
              items-to-group (mapv #(chains/get-item-at-path items %) sorted-paths)
              new-group (chains/create-group items-to-group)
              after-remove (chains/delete-paths-safely items sorted-paths)
              first-path (first sorted-paths)
              insert-path (if (empty? common-parent)
                            [(first first-path)]
                            (conj common-parent (last first-path)))
              new-items (chains/insert-at-path after-remove insert-path new-group)
              group-id (:id new-group)]
          (events/dispatch! {:event/type :list/select-item
                             :component-id component-id
                             :item-id group-id
                             :mode :single})
          (invoke-items-changed! props new-items))
        (log/warn "group-selected! - items not at same level")))
    (log/warn "group-selected! - no items selected")))

(defn- ungroup!
  "Ungroup a folder, splicing its contents into the parent."
  [component-id items props group-id]
  (when-let [path (chains/find-path-by-id items group-id)]
    (when (chains/group? (chains/get-item-at-path items path))
      (let [new-items (chains/ungroup items path)]
        (clear-selection! component-id)
        (invoke-items-changed! props new-items)))))

(defn- toggle-collapse!
  "Toggle collapse/expand state of a group."
  [items props group-id]
  (when-let [path (chains/find-path-by-id items group-id)]
    (let [new-items (chains/update-at-path items path #(update % :collapsed? not))]
      (invoke-items-changed! props new-items))))


;; Rename Operations (Event Dispatching)

(defn- start-rename!
  "Enter rename mode for an item."
  [component-id item-id]
  (events/dispatch! (u/->map& component-id item-id
                              :event/type :list/start-rename)))

(defn- cancel-rename!
  "Cancel rename mode."
  [component-id]
  (events/dispatch! {:event/type :list/cancel-rename
                     :component-id component-id}))

(defn- commit-rename!
  "Commit rename and update item name."
  [component-id items props item-id new-name]
  (when-let [path (chains/find-path-by-id items item-id)]
    (let [new-items (chains/update-at-path items path #(assoc % :name new-name))]
      (cancel-rename! component-id)
      (invoke-items-changed! props new-items))))



(defn- set-enabled!
  "Set enabled/disabled state for an item."
  [items props item-id enabled?]
  (when-let [path (chains/find-path-by-id items item-id)]
    (let [new-items (chains/update-at-path items path #(assoc % :enabled? enabled?))]
      (invoke-items-changed! props new-items))))


;; Helper Functions

(def ^:private max-depth-class
  "Maximum nesting level for CSS classes."
  3)

(defn- depth->class
  "Returns the depth capped at max-depth-class for CSS classes."
  [depth]
  (min depth max-depth-class))

(defn- request-list-focus!
  "Walk up the parent chain to find the focusable list container and request focus on it."
  [^javafx.scene.Node node]
  (loop [current (.getParent node)]
    (when current
      (if (.isFocusTraversable current)
        (.requestFocus current)
        (recur (.getParent current))))))


;; Style Class Builders

(defn- build-style-classes
  "Build a vector of style classes from a base class and conditional modifiers.
   modifiers is a sequence of [condition class-to-add] pairs."
  [base-classes modifiers]
  (reduce (fn [classes [condition class-str]]
            (if condition
              (conj classes class-str)
              classes))
          (vec base-classes)
          modifiers))

(defn- group-header-style-classes
  "Build style class vector for group header based on state."
  [{:keys [depth selected? dragging? drop-before? drop-into? effectively-disabled?]}]
  (let [d (depth->class depth)]
    (build-style-classes
      ["group-header" (str "group-depth-" d) (str "group-indent-" d)]
      [[selected? "group-header-selected"]
       [drop-into? "group-header-drop-into"]
       [drop-before? "group-header-drop-before"]
       [dragging? "group-header-dragging"]
       [effectively-disabled? "group-header-disabled"]])))

(defn- group-name-style-classes
  "Build style class vector for group name label based on state."
  [{:keys [depth selected? effectively-disabled?]}]
  (build-style-classes
    ["group-name-label"]
    [[(and (not selected?) (not effectively-disabled?))
      (str "group-name-depth-" (depth->class depth))]
     [selected? "group-name-selected"]
     [effectively-disabled? "group-name-disabled"]]))

(defn- list-item-style-classes
  "Build style class vector for list item based on state."
  [{:keys [depth selected? dragging? drop-target? drop-position effectively-disabled?]}]
  (build-style-classes
    ["chain-item" (str "item-indent-" (depth->class depth))]
    [[selected? "chain-item-selected"]
     [(and drop-target? (= drop-position :before)) "chain-item-drop-before"]
     [(and drop-target? (= drop-position :after)) "chain-item-drop-after"]
     [dragging? "chain-item-dragging"]
     [effectively-disabled? "chain-item-disabled"]]))

(defn- list-item-name-style-classes
  "Build style class vector for list item name label based on state."
  [{:keys [selected? effectively-disabled?]}]
  (build-style-classes
    ["chain-item-name"]
    [[selected? "chain-item-name-selected"]
     [effectively-disabled? "chain-item-name-disabled"]]))


;; Shared UI Helpers - Click Handler

(defn- setup-click-handler!
  "Setup click handler on a node for selection and double-click rename."
  [^javafx.scene.Node node component-id item-id items]
  (.setOnMouseClicked node
    (on-fx-event
      (fn [event]
        (let [click-count (.getClickCount event)
              ctrl? (.isShortcutDown event)
              shift? (.isShiftDown event)]
          (if (= click-count 2)
            (do (start-rename! component-id item-id)
                (.consume event))
            (do (handle-selection! component-id item-id ctrl? shift? items)
                (request-list-focus! node)
                (.consume event))))))))


;; ============================================================================
;; Shared UI Helpers - Rename Text Field
;; ============================================================================

(defn- rename-text-field
  "Create a text field for inline renaming with auto-focus and select-all."
  [{:keys [component-id items props item-id current-name style-class]}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [^javafx.scene.control.TextField node]
                 (javafx.application.Platform/runLater
                   (fn []
                     (.requestFocus node)
                     (javafx.application.Platform/runLater #(.selectAll node)))))
   :desc {:fx/type :text-field
          :text (or current-name "Untitled")
          :style-class style-class
          :on-action (fn [^javafx.event.ActionEvent e]
                       (commit-rename! component-id items props item-id
                                       (.getText ^javafx.scene.control.TextField (.getSource e))))
          :on-key-pressed (fn [^javafx.scene.input.KeyEvent e]
                            (when (= (.getCode e) javafx.scene.input.KeyCode/ESCAPE)
                              (cancel-rename! component-id)))
          :on-focused-changed (fn [focused?]
                                (when-not focused?
                                  (cancel-rename! component-id)))}})


;; Drag-and-Drop Handlers Setup

(defonce ^:private current-drop-target-node (atom nil))

(def ^:private drop-indicator-classes
  ["chain-item-drop-before" "chain-item-drop-after"
   "group-header-drop-before" "group-header-drop-into"])

(defn- clear-drop-indicator-classes!
  "Remove all drop indicator CSS classes from a node."
  [^javafx.scene.Node node]
  (when node
    (.removeAll (.getStyleClass node) drop-indicator-classes)))

(defn- set-drop-indicator-class!
  "Set the appropriate drop indicator CSS class on a node."
  [^javafx.scene.Node node position group?]
  (let [style-class (.getStyleClass node)]
    (.removeAll style-class drop-indicator-classes)
    (let [class-to-add (cond
                         (and group? (= position :before)) "group-header-drop-before"
                         (and group? (= position :into)) "group-header-drop-into"
                         (= position :before) "chain-item-drop-before"
                         (= position :after) "chain-item-drop-after")]
      (when class-to-add
        (.add style-class class-to-add)))))

(defn- setup-drag-source!
  "Setup drag source handlers on a node."
  [^javafx.scene.Node node item-id component-id]
  (.setOnDragDetected node
    (on-fx-event
      (fn [event]
        (let [dragboard (.startDragAndDrop node (into-array TransferMode [TransferMode/MOVE]))
              content (ClipboardContent.)]
          (.putString content (pr-str item-id))
          (.setContent dragboard content)
          (start-drag! component-id item-id)
          (.add (.getStyleClass node) "chain-item-dragging")
          (.consume event)))))
  (.setOnDragDone node
    (on-fx-event
      (fn [event]
        (.remove (.getStyleClass node) "chain-item-dragging")
        (when-let [old-target @current-drop-target-node]
          (clear-drop-indicator-classes! old-target))
        (reset! current-drop-target-node nil)
        (clear-drag-state! component-id)
        (.consume event)))))

(defn- setup-drag-target!
  "Setup drag target handlers on a node."
  [^javafx.scene.Node node target-id group? component-id items props]
  (let [drop-position-atom (atom :before)]
    (.setOnDragOver node
      (on-fx-event
        (fn [event]
          (when (and (.getDragboard event)
                     (.hasString (.getDragboard event)))
            (.acceptTransferModes event (into-array TransferMode [TransferMode/MOVE]))
            (let [bounds (.getBoundsInLocal node)
                  y (.getY event)
                  height (.getHeight bounds)
                  new-pos (if group?
                            (if (< y (* height 0.25)) :before :into)
                            (if (< y (* height 0.5)) :before :after))]
              (when (not= @drop-position-atom new-pos)
                (reset! drop-position-atom new-pos)
                (set-drop-indicator-class! node new-pos group?)
                (update-drop-target! component-id target-id new-pos))))
          (.consume event))))
    (.setOnDragEntered node
      (on-fx-event
        (fn [event]
          (when (.hasString (.getDragboard event))
            (when-let [old-target @current-drop-target-node]
              (when (not= old-target node)
                (clear-drop-indicator-classes! old-target)))
            (reset! current-drop-target-node node)
            (let [initial-pos (if group? :into :before)]
              (reset! drop-position-atom initial-pos)
              (set-drop-indicator-class! node initial-pos group?)
              (update-drop-target! component-id target-id initial-pos)))
          (.consume event))))
    (.setOnDragExited node
      (on-fx-event
        (fn [event]
          (clear-drop-indicator-classes! node)
          (.consume event))))
    (.setOnDragDropped node
      (on-fx-event
        (fn [event]
          (let [drop-pos @drop-position-atom]
            (clear-drop-indicator-classes! node)
            (reset! current-drop-target-node nil)
            (handle-drop! component-id target-id drop-pos items props)
            (clear-drag-state! component-id)
            (.setDropCompleted event true)
            (.consume event)))))))


;; Group Header Component

(defn- group-header
  "Header for a group showing collapse toggle, name, and item count."
  [{:keys [component-id items props group depth selected? dragging?
           drop-target-id drop-position renaming? parent-disabled?]}]
  (let [group-id (:id group)
        enabled? (:enabled? group true)
        effectively-disabled? (or (not enabled?) parent-disabled?)
        collapsed? (:collapsed? group false)
        item-count (chains/count-items-recursive (:items group []))
        drop-target? (= group-id drop-target-id)
        drop-before? (and drop-target? (= drop-position :before))
        drop-into? (and drop-target? (= drop-position :into))
        
        header-classes (group-header-style-classes
                         (u/->map depth selected? dragging? drop-before? drop-into? effectively-disabled?))
        name-classes (group-name-style-classes
                       (u/->map depth selected? effectively-disabled?))]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [node]
                   (setup-drag-source! node group-id component-id)
                   (setup-drag-target! node group-id true component-id items props)
                   (setup-click-handler! node component-id group-id items))
     :desc {:fx/type :h-box
            :style-class header-classes
            :children [{:fx/type :button
                        :text (if collapsed? "▶" "▼")
                        :style-class "group-collapse-btn"
                        :on-action (fn [_] (toggle-collapse! items props group-id))}
                       
                       {:fx/type :check-box
                        :selected enabled?
                        :on-selected-changed (fn [new-enabled?]
                                               (set-enabled! items props group-id new-enabled?))}
                       
                       (if renaming?
                         {:fx/type rename-text-field
                          :component-id component-id
                          :items items
                          :props props
                          :item-id group-id
                          :current-name (:name group)
                          :style-class "group-name-input"}
                         {:fx/type :label
                          :text (or (:name group) "Group")
                          :style-class name-classes})
                       
                       {:fx/type :label
                        :text (str "(" item-count ")")
                        :style-class "group-count-badge"}
                       
                       {:fx/type :region :h-box/hgrow :always}
                       
                       {:fx/type :button
                        :text "⊗"
                        :style-class "group-ungroup-btn"
                        :on-action (fn [_] (ungroup! component-id items props group-id))}]}}))


;; ============================================================================
;; List Item Card Component
;; ============================================================================

(defn- list-item-card
  "A single item in the list with drag-and-drop support."
  [{:keys [component-id items props item item-label depth selected? dragging?
           drop-target-id drop-position renaming? parent-disabled?]}]
  (let [item-id (:id item)
        enabled? (:enabled? item true)
        effectively-disabled? (or (not enabled?) parent-disabled?)
        drop-target? (= item-id drop-target-id)
        
        item-classes (list-item-style-classes
                       {:depth depth
                        :selected? selected?
                        :dragging? dragging?
                        :drop-target? drop-target?
                        :drop-position drop-position
                        :effectively-disabled? effectively-disabled?})
        name-classes (list-item-name-style-classes
                       {:selected? selected?
                        :effectively-disabled? effectively-disabled?})]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [node]
                   (setup-drag-source! node item-id component-id)
                   (setup-drag-target! node item-id false component-id items props)
                   (setup-click-handler! node component-id item-id items))
     :desc {:fx/type :h-box
            :style-class item-classes
            :children [{:fx/type :check-box
                        :selected enabled?
                        :on-selected-changed (fn [new-enabled?]
                                               (set-enabled! items props item-id new-enabled?))}
                       
                       (if renaming?
                         {:fx/type rename-text-field
                          :component-id component-id
                          :items items
                          :props props
                          :item-id item-id
                          :current-name item-label
                          :style-class "list-item-name-input"}
                         {:fx/type :label
                          :text (or item-label "Unknown")
                          :style-class name-classes})]}}))


;; Recursive List Item Renderer

(defn- render-list-item
  "Recursively render a list item (leaf item or group)."
  [{:keys [component-id items props item depth selected-ids dragging-ids
           drop-target-id drop-position renaming-id parent-disabled? get-item-label]}]
  (let [item-id (:id item)
        selected? (contains? (or selected-ids #{}) item-id)
        dragging? (contains? (or dragging-ids #{}) item-id)
        common-props (u/->map component-id items props depth selected? dragging? 
                              drop-target-id drop-position parent-disabled?)]
    (if (chains/group? item)
      (let [collapsed? (:collapsed? item false)
            children-items (:items item [])
            enabled? (:enabled? item true)
            effectively-disabled? (or (not enabled?) parent-disabled?)]
        {:fx/type :v-box
         :spacing 2
         :children (into
                    [(assoc common-props
                            :fx/type group-header
                            :group item
                            :renaming? (= item-id renaming-id))]
                    (when-not collapsed?
                      (u/mapv-indexed
                       (fn [idx child]
                         (u/->map&
                          component-id items props selected-ids dragging-ids drop-target-id
                          drop-position renaming-id effectively-disabled? get-item-label
                          :fx/type render-list-item
                          :fx/key [(:id child) idx]
                          :depth (inc depth)
                          :item child))
                       children-items)))})
      (assoc common-props
             :fx/type list-item-card
             :item item
             :item-label (get-item-label item)
             :renaming? (= item-id renaming-id)))))


;; ============================================================================
;; Group Toolbar
;; ============================================================================

(defn- group-toolbar
  "Toolbar with group-related buttons."
  [{:keys [component-id items props selection-count can-create-group?]}]
  {:fx/type :h-box
   :spacing 4
   :children [{:fx/type :button
               :text "🗁 New"
               :style-class "chain-toolbar-btn"
               :on-action (fn [_] (create-empty-group! component-id items props))}
              {:fx/type :button
               :text "☐ Group"
               :disable (or (zero? selection-count) (not can-create-group?))
               :style-class "chain-toolbar-btn"
               :on-action (fn [_] (group-selected! component-id items props))}]})


;; Main Sidebar Component

(defn list-sidebar
  "Generic hierarchical list sidebar component using context-based state.
   
   Props:
   - :fx/context - cljfx context (required)
   - :items - Vector of items (required)
   - :get-item-label - Function item -> string (required)
   - :on-items-changed - Callback with new items vector (required)
   - :on-selection-changed - Callback with selection map (optional)
   - :on-copy - Callback with copied items (optional)
   - :clipboard-items - Items available to paste (optional)
   - :header-label - Header text (default 'LIST')
   - :empty-text - Empty state text (optional)
   - :allow-groups? - Enable grouping (default true)
   - :component-id - Unique ID for state isolation (required)
   - :on-change-event - Event type for async drag-drop (optional)
   - :on-change-params - Base params for async drag-drop (optional)
   - :items-path - Direct path to items in state for async drag-drop (optional)"
  [{:keys [fx/context items get-item-label on-items-changed
           on-copy clipboard-items header-label empty-text allow-groups?
           component-id on-change-event on-change-params items-path]
    :or {header-label "LIST"
         empty-text "No items"
         allow-groups? true}
    :as props}]
  (let [ui-state (fx/sub-ctx context subs/list-ui-state component-id)
        {:keys [selected-ids dragging-ids drop-target-id drop-position renaming-id]} ui-state
        selection-count (count selected-ids)
        chain-depth (chains/nesting-depth items)
        can-create-group? (and allow-groups? (< chain-depth chains/max-nesting-depth))
        can-paste? (boolean (seq clipboard-items))]
    {:fx/type :v-box
     :style-class "chain-sidebar"
     :children [{:fx/type :h-box
                 :alignment :center-left
                 :children (filterv some?
                                    [{:fx/type :label
                                      :text header-label
                                      :style-class "chain-header-label"}
                                     {:fx/type :region :h-box/hgrow :always}
                                     (when (pos? selection-count)
                                       {:fx/type :label
                                        :text (str selection-count " selected")
                                        :style-class "chain-selection-count"})])}
                {:fx/type :label
                 :text "Ctrl+Click multi-select • Drag to reorder • Ctrl+G group"
                 :style-class "chain-hint-text"}

                (when allow-groups?
                  {:fx/type group-toolbar
                   :component-id component-id
                   :items items
                   :props props
                   :selection-count selection-count
                   :can-create-group? can-create-group?})

                {:fx/type :h-box
                 :spacing 4
                 :children [{:fx/type :button
                             :text "Copy"
                             :disable (zero? selection-count)
                             :style-class "chain-toolbar-btn"
                             :on-action (fn [_] (copy-selected! component-id items props))}
                            {:fx/type :button
                             :text "Paste"
                             :disable (not can-paste?)
                             :style-class "chain-toolbar-btn"
                             :on-action (fn [_] (paste-items! component-id items props))}
                            {:fx/type :button
                             :text "Del"
                             :disable (zero? selection-count)
                             :style-class "chain-toolbar-btn-danger"
                             :on-action (fn [_] (delete-selected! component-id items props))}]}
                (if (empty? items)
                  {:fx/type :label
                   :text empty-text
                   :style-class "chain-empty-text"}
                  {:fx/type :scroll-pane
                   :fit-to-width true
                   :fit-to-height false
                   :hbar-policy :as-needed
                   :v-box/vgrow :always
                   :style-class "chain-scroll-pane"
                   :content {:fx/type :v-box
                             :spacing 4
                             :children (u/mapv-indexed
                                        (fn [idx item]
                                          {:fx/type render-list-item
                                           :fx/key [(:id item) idx]
                                           :component-id component-id
                                           :items items
                                           :props props
                                           :item item
                                           :depth 0
                                           :selected-ids selected-ids
                                           :dragging-ids dragging-ids
                                           :drop-target-id drop-target-id
                                           :drop-position drop-position
                                           :renaming-id renaming-id
                                           :parent-disabled? false
                                           :get-item-label get-item-label})
                                        items)}})]}))


;; Callback Factory Functions

(defn make-dispatch-callback
  "Create an on-items-changed callback that dispatches an event.
   
   Usage:
   :on-items-changed (make-dispatch-callback :effects/set-chain {:col col :row row})"
  [event-type base-params]
  (fn [new-items]
    (events/dispatch! (assoc base-params
                             :event/type event-type
                             :items new-items))))

(defn make-selection-callback
  "Create an on-selection-changed callback that dispatches an event."
  ([event-type base-params]
   (make-selection-callback event-type base-params :selected-ids))
  ([event-type base-params selection-key]
   (fn [{:keys [selected-ids]}]
     (events/dispatch! (assoc base-params
                              :event/type event-type
                              selection-key selected-ids)))))


;; Label Function Factories

(defn make-registry-label
  "Create a get-item-label function that looks up names from a registry.
   Checks custom :name field first before falling back to registry."
  [id-key lookup-fn name-key fallback]
  (fn [item]
    (or (:name item)
        (when-let [def-map (lookup-fn (get item id-key))]
          (get def-map name-key fallback))
        fallback)))

(defn make-case-label
  "Create a get-item-label function from a keyword->string map."
  [id-key label-map fallback]
  (fn [item]
    (get label-map (get item id-key) fallback)))


;; Keyboard Handler Setup

(defonce ^:private keyboard-handler-atoms (atom {}))

(defn- get-or-create-handler-atoms!
  "Get or create atoms for a component's keyboard handler state."
  [component-id]
  (or (get @keyboard-handler-atoms component-id)
      (let [new-atoms {:items-atom (atom [])
                       :props-atom (atom {})}]
        (swap! keyboard-handler-atoms assoc component-id new-atoms)
        new-atoms)))

(defn- update-handler-atoms!
  "Update the atoms with current items and props values."
  [component-id items props]
  (let [{:keys [items-atom props-atom]} (get-or-create-handler-atoms! component-id)]
    (reset! items-atom items)
    (reset! props-atom props)))

(defn- setup-keyboard-handlers!
  "Setup keyboard handlers on a parent node for a hierarchical list component."
  [^javafx.scene.Node node component-id items-atom props-atom]
  (.addEventFilter node
    javafx.scene.input.KeyEvent/KEY_PRESSED
    (on-fx-event
      (fn [event]
        (let [code (.getCode event)
              ctrl? (.isShortcutDown event)
              items @items-atom
              props @props-atom]
          (when (cond
                  (and ctrl? (= code javafx.scene.input.KeyCode/C))
                  (copy-selected! component-id items props)

                  (and ctrl? (= code javafx.scene.input.KeyCode/V))
                  (paste-items! component-id items props)

                  (and ctrl? (= code javafx.scene.input.KeyCode/X))
                  (do (copy-selected! component-id items props)
                      (delete-selected! component-id items props))

                  (and ctrl? (= code javafx.scene.input.KeyCode/A))
                  (select-all! component-id items)

                  (and ctrl? (= code javafx.scene.input.KeyCode/G))
                  (group-selected! component-id items props)

                  (= code javafx.scene.input.KeyCode/DELETE)
                  (delete-selected! component-id items props)

                  (= code javafx.scene.input.KeyCode/ESCAPE)
                  (clear-selection! component-id))
            (.consume event)))))))


;; All-in-One Wrapper Component

(defn list-editor
  "Complete hierarchical list editor with keyboard handling and event dispatch.
   This is the recommended component for most use cases.
   
   Props:
   - :fx/context - cljfx context (required)
   - :items - Vector of items (required)
   - :component-id - Unique ID for state isolation (required)
   
   Label configuration (one of):
   - :get-item-label - Custom function item -> string
   - :item-id-key + :item-registry-fn + :item-name-key - For registry lookup
   - :item-id-key + :label-map - For case-based labels
   
   Event dispatch:
   - :on-change-event - Event type for items changed
   - :on-change-params - Base params for change event
   - :items-path - Direct path to items in state
   
   - :on-selection-event - Event type for selection changed
   - :on-selection-params - Base params for selection event
   - :selection-key - Key for selection in event (default :selected-ids)
   
   - :on-copy-fn - Custom copy function
   - :clipboard-items - Items for paste
   
   UI customization:
   - :header-label - Header text (default 'LIST')
   - :empty-text - Empty state text
   - :allow-groups? - Enable grouping (default true)
   - :fallback-label - Label for unknown items (default \"Unknown\")"
  [{:keys [fx/context items component-id
           get-item-label item-id-key item-registry-fn item-name-key label-map
           on-change-event on-change-params items-path
           on-selection-event on-selection-params selection-key
           on-copy-fn clipboard-items
           header-label empty-text allow-groups? fallback-label]
    :or {selection-key :selected-ids
         header-label "LIST"
         empty-text "No items"
         allow-groups? true
         fallback-label "Unknown"}}]
  (let [label-fn (cond
                   get-item-label
                   get-item-label
                   
                   (and item-id-key item-registry-fn)
                   (make-registry-label item-id-key item-registry-fn
                                        (or item-name-key :name) fallback-label)
                   
                   (and item-id-key label-map)
                   (make-case-label item-id-key label-map fallback-label)
                   
                   :else
                   (fn [item] (or (:name item) fallback-label)))
        
        on-items-changed (when on-change-event
                           (make-dispatch-callback on-change-event on-change-params))
        
        on-selection-changed (when on-selection-event
                               (make-selection-callback on-selection-event on-selection-params selection-key))
        
        handler-props {:on-items-changed on-items-changed
                       :on-selection-changed on-selection-changed
                       :clipboard-items clipboard-items
                       :on-copy on-copy-fn}
        
        {:keys [items-atom props-atom]} (get-or-create-handler-atoms! component-id)
        _ (update-handler-atoms! component-id items handler-props)]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [node]
                   (setup-keyboard-handlers! node component-id items-atom props-atom)
                   (.setFocusTraversable node true)
                   (.requestFocus node))
     :desc {:fx/type :v-box
            :children [(u/->map&
                        items on-items-changed on-selection-changed clipboard-items header-label
                        empty-text allow-groups? component-id on-change-event on-change-params items-path
                        :fx/type list-sidebar
                        :fx/context context
                        :v-box/vgrow :always
                        :get-item-label label-fn
                        :on-copy on-copy-fn)]}}))
