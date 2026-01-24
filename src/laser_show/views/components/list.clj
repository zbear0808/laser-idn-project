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



;; Selection Logic (Event Dispatching)



(defn- handle-selection!
  "Handle selection based on click modifiers.
   Dispatches selection event to update canonical list-ui state."
  [component-id item-id ctrl? shift? items]
  (let [mode (cond ctrl? :ctrl
                   shift? :shift
                   :else :single)
        event (if shift?
                (let [current-state (state/get-in-state [:list-ui component-id])
                      last-selected-id (:last-selected-id current-state)
                      all-ids (chains/collect-all-ids items)
                      anchor-id (or last-selected-id item-id)
                      anchor-idx (.indexOf all-ids anchor-id)
                      target-idx (.indexOf all-ids item-id)
                      start (min anchor-idx target-idx)
                      end (max anchor-idx target-idx)
                      new-selected (if (and (>= anchor-idx 0) (>= target-idx 0))
                                     (set (subvec all-ids start (inc end)))
                                     #{item-id})]
                  {:event/type :list/select-item
                   :component-id component-id
                   :item-id item-id
                   :mode mode
                   :selected-ids-override new-selected
                   :last-id-override last-selected-id})
                {:event/type :list/select-item
                 :component-id component-id
                 :item-id item-id
                 :mode mode})]
    (events/dispatch! event)))

(defn- select-all!
  "Select all items in the component."
  [component-id items]
  (let [all-ids (chains/collect-all-ids items)]
    (events/dispatch! {:event/type :list/select-all
                       :component-id component-id
                       :all-ids all-ids})))

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
  (events/dispatch! {:event/type :list/start-drag
                     :component-id component-id
                     :item-id item-id}))

(defn- update-drop-target!
  "Update drop target during drag over."
  [component-id target-id drop-position]
  (events/dispatch! {:event/type :list/update-drop-target
                     :component-id component-id
                     :target-id target-id
                     :drop-position drop-position}))

(defn- handle-drop!
  "Handle drop operation by dispatching to async handler."
  [component-id target-id drop-position items props]
  (let [current-state (state/get-in-state [:list-ui component-id])
        dragging-ids (:dragging-ids current-state)]
    (when (and (seq dragging-ids) (seq items))
      (events/dispatch!
        {:event/type :list/perform-drop
         :component-id component-id
         :items items
         :dragging-ids dragging-ids
         :target-id target-id
         :drop-position drop-position
         :on-change-event (:on-change-event props)
         :on-change-params (:on-change-params props)
         :items-key (or (:items-key props) :items)
         :items-path (:items-path props)}))))

(defn- clear-drag-state!
  "Clear all drag-related state after drop or cancel."
  [component-id]
  (events/dispatch! {:event/type :list/clear-drag
                     :component-id component-id}))



;; Item Operations (Callbacks Only - No State Mutation)



(defn- delete-selected!
  "Delete selected items and call :on-items-changed."
  [component-id items props]
  (let [current-state (state/get-in-state [:list-ui component-id])
        selected-ids (:selected-ids current-state #{})]
    (when (seq selected-ids)
      (let [id->path (chains/find-paths-by-ids items selected-ids)
            paths-to-delete (vals id->path)
            new-items (chains/delete-paths-safely items paths-to-delete)]
        (events/dispatch! {:event/type :list/clear-selection
                           :component-id component-id})
        (when-let [callback (:on-items-changed props)]
          (callback new-items))))))

(defn- copy-selected!
  "Copy selected items and call :on-copy callback."
  [component-id items props]
  (let [current-state (state/get-in-state [:list-ui component-id])
        selected-ids (:selected-ids current-state #{})]
    (when (seq selected-ids)
      (let [id->path (chains/find-paths-by-ids items selected-ids)
            selected-items (mapv #(chains/get-item-at-path items %) (vals id->path))
            copied-items (chains/deep-copy-items selected-items)]
        (if-let [callback (:on-copy props)]
          (callback copied-items)
          (log/warn "copy-selected! no :on-copy callback in props"))))))

(defn- paste-items!
  "Paste items from clipboard after selected item (or at end)."
  [component-id items props]
  (when-let [clipboard-items (:clipboard-items props)]
    (when (seq clipboard-items)
      (let [current-state (state/get-in-state [:list-ui component-id])
            items-to-paste (chains/deep-copy-items clipboard-items)
            last-id (:last-selected-id current-state)
            path-for-last (when last-id (chains/find-path-by-id items last-id))
            insert-idx (if (and last-id path-for-last)
                         (inc (first path-for-last))
                         (count items))
            new-items (reduce-kv
                        (fn [chain idx item]
                          (chains/insert-at-path chain [(+ insert-idx idx)] item))
                        items
                        (vec items-to-paste))
            pasted-ids (set (map :id items-to-paste))]
        (events/dispatch! {:event/type :list/select-item
                           :component-id component-id
                           :item-id (:id (last items-to-paste))
                           :mode :single
                           :selected-ids-override pasted-ids})
        (if-let [callback (:on-items-changed props)]
          (callback new-items)
          (log/warn "paste-items! no :on-items-changed callback"))))))



;; Group Operations (Callbacks Only)



(defn- normalize-selected-ids
  "Remove redundant descendant IDs when a group AND all its descendants are selected."
  [selected-ids items]
  (let [id->path (chains/find-paths-by-ids items selected-ids)
        group-ids (filter
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
    (when-let [callback (:on-items-changed props)]
      (callback new-items))))

(defn- group-selected!
  "Group selected items into a new folder.
   Items must be at the same nesting level to be grouped together."
  [component-id items props]
  (let [current-state (state/get-in-state [:list-ui component-id])
        selected-ids (:selected-ids current-state #{})]
    (cond
      (empty? selected-ids)
      (log/warn "group-selected! - no items selected")
      
      :else
      (let [normalized-ids (normalize-selected-ids selected-ids items)
            id->path (chains/find-paths-by-ids items normalized-ids)
            all-paths (vals id->path)
            parent-paths (map (fn [path]
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
                              (let [local-idx (last first-path)]
                                (conj common-parent local-idx)))
                new-items (chains/insert-at-path after-remove insert-path new-group)
                group-id (:id new-group)]
            (events/dispatch! {:event/type :list/select-item
                               :component-id component-id
                               :item-id group-id
                               :mode :single})
            (when-let [callback (:on-items-changed props)]
              (callback new-items)))
          (log/warn "group-selected! - items not at same level"))))))

(defn- ungroup!
  "Ungroup a folder, splicing its contents into the parent."
  [component-id items props group-id]
  (when-let [path (chains/find-path-by-id items group-id)]
    (let [group (chains/get-item-at-path items path)]
      (when (chains/group? group)
        (let [new-items (chains/ungroup items path)]
          (events/dispatch! {:event/type :list/clear-selection
                             :component-id component-id})
          (when-let [callback (:on-items-changed props)]
            (callback new-items)))))))

(defn- toggle-collapse!
  "Toggle collapse/expand state of a group."
  [component-id items props group-id]
  (when-let [path (chains/find-path-by-id items group-id)]
    (let [new-items (chains/update-at-path items path
                                           #(update % :collapsed? not))]
      (when-let [callback (:on-items-changed props)]
        (callback new-items)))))



;; Rename Operations (Event Dispatching)



(defn- start-rename!
  "Enter rename mode for an item."
  [component-id item-id]
  (events/dispatch! {:event/type :list/start-rename
                     :component-id component-id
                     :item-id item-id}))

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
      (when-let [callback (:on-items-changed props)]
        (callback new-items)))))



;; Enable/Disable Operations (Internal)



(defn- set-enabled!
  "Set enabled/disabled state for an item."
  [component-id items props item-id enabled?]
  (when-let [path (chains/find-path-by-id items item-id)]
    (let [new-items (chains/update-at-path items path #(assoc % :enabled? enabled?))]
      (when-let [callback (:on-items-changed props)]
        (callback new-items)))))



;; Helper Functions



(defn- depth-class
  "Returns the depth capped at 3 (max nesting level for CSS classes)."
  [depth]
  (min depth 3))

(defn- request-list-focus!
  "Walk up the parent chain to find the focusable list container and request focus on it."
  [^javafx.scene.Node node]
  (loop [current (.getParent node)]
    (when current
      (if (.isFocusTraversable current)
        (.requestFocus current)
        (recur (.getParent current))))))



;; Shared UI Helpers - Click Handler



(defn- setup-click-handler!
  "Setup click handler on a node for selection and double-click rename."
  [^javafx.scene.Node node component-id item-id items]
  (.setOnMouseClicked node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (let [click-count (.getClickCount event)
              ctrl? (.isShortcutDown event)
              shift? (.isShiftDown event)]
          (if (= click-count 2)
            (do (start-rename! component-id item-id) (.consume event))
            (do (handle-selection! component-id item-id ctrl? shift? items)
                (request-list-focus! node)
                (.consume event))))))))



;; Shared UI Helpers - Rename Text Field



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

(defn- list-item-style-classes
  "Build style class vector for list item based on state."
  [{:keys [depth selected? dragging? drop-target? drop-position effectively-disabled?]}]
  (cond-> ["chain-item"
           (str "item-indent-" (depth-class depth))]
    selected? (conj "chain-item-selected")
    (and drop-target? (= drop-position :before)) (conj "chain-item-drop-before")
    (and drop-target? (= drop-position :after)) (conj "chain-item-drop-after")
    dragging? (conj "chain-item-dragging")
    effectively-disabled? (conj "chain-item-disabled")))

(defn- list-item-name-style-classes
  "Build style class vector for list item name label based on state."
  [{:keys [selected? effectively-disabled?]}]
  (cond-> ["chain-item-name"]
    selected? (conj "chain-item-name-selected")
    effectively-disabled? (conj "chain-item-name-disabled")))



;; Drag-and-Drop Handlers Setup


;; Track the current visual drop target node globally so we can clear it
(defonce ^:private current-drop-target-node (atom nil))

(defn- clear-drop-indicator-classes!
  "Remove all drop indicator CSS classes from a node."
  [^javafx.scene.Node node]
  (when node
    (let [style-class (.getStyleClass node)]
      (.removeAll style-class ["chain-item-drop-before" "chain-item-drop-after"
                               "group-header-drop-before" "group-header-drop-into"]))))

(defn- set-drop-indicator-class!
  "Set the appropriate drop indicator CSS class on a node."
  [^javafx.scene.Node node position is-group?]
  (let [style-class (.getStyleClass node)]
    (.removeAll style-class ["chain-item-drop-before" "chain-item-drop-after"
                             "group-header-drop-before" "group-header-drop-into"])
    (cond
      (and is-group? (= position :before))
      (.add style-class "group-header-drop-before")
      
      (and is-group? (= position :into))
      (.add style-class "group-header-drop-into")
      
      (= position :before)
      (.add style-class "chain-item-drop-before")
      
      (= position :after)
      (.add style-class "chain-item-drop-after"))))

(defn- setup-drag-source!
  "Setup drag source handlers on a node."
  [^javafx.scene.Node node item-id component-id]
  (.setOnDragDetected
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (let [dragboard (.startDragAndDrop node (into-array TransferMode [TransferMode/MOVE]))
              content (ClipboardContent.)]
          (.putString content (pr-str item-id))
          (.setContent dragboard content)
          (start-drag! component-id item-id)
          (.add (.getStyleClass node) "chain-item-dragging")
          (.consume event)))))
  (.setOnDragDone
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (.remove (.getStyleClass node) "chain-item-dragging")
        (when-let [old-target @current-drop-target-node]
          (clear-drop-indicator-classes! old-target))
        (reset! current-drop-target-node nil)
        (clear-drag-state! component-id)
        (.consume event)))))

(defn- setup-drag-target!
  "Setup drag target handlers on a node."
  [^javafx.scene.Node node target-id is-group? component-id items props]
  (let [drop-position-atom (atom :before)]
    (.setOnDragOver
      node
      (reify javafx.event.EventHandler
        (handle [_ event]
          (when (and (.getDragboard event)
                     (.hasString (.getDragboard event)))
            (.acceptTransferModes event (into-array TransferMode [TransferMode/MOVE]))
            (let [bounds (.getBoundsInLocal node)
                  y (.getY event)
                  height (.getHeight bounds)
                  new-pos (if is-group?
                            (if (< y (* height 0.25)) :before :into)
                            (if (< y (* height 0.5)) :before :after))]
              (when (not= @drop-position-atom new-pos)
                (reset! drop-position-atom new-pos)
                (set-drop-indicator-class! node new-pos is-group?)
                (update-drop-target! component-id target-id new-pos))))
          (.consume event))))
    (.setOnDragEntered
      node
      (reify javafx.event.EventHandler
        (handle [_ event]
          (when (.hasString (.getDragboard event))
            (when-let [old-target @current-drop-target-node]
              (when (not= old-target node)
                (clear-drop-indicator-classes! old-target)))
            (reset! current-drop-target-node node)
            (let [initial-pos (if is-group? :into :before)]
              (reset! drop-position-atom initial-pos)
              (set-drop-indicator-class! node initial-pos is-group?)
              (update-drop-target! component-id target-id initial-pos)))
          (.consume event))))
    (.setOnDragExited
      node
      (reify javafx.event.EventHandler
        (handle [_ event]
          (clear-drop-indicator-classes! node)
          (.consume event))))
    (.setOnDragDropped
      node
      (reify javafx.event.EventHandler
        (handle [_ event]
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
  [{:keys [component-id items props group depth selected? dragging? drop-target-id drop-position renaming? parent-disabled?]}]
  (let [group-id (:id group)
        enabled? (:enabled? group true)
        effectively-disabled? (or (not enabled?) parent-disabled?)
        collapsed? (:collapsed? group false)
        item-count (chains/count-items-recursive (:items group []))
        is-drop-target? (= group-id drop-target-id)
        drop-before? (and is-drop-target? (= drop-position :before))
        drop-into? (and is-drop-target? (= drop-position :into))
        
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
                        :on-action (fn [_] (toggle-collapse! component-id items props group-id))}
                       
                       {:fx/type :check-box
                        :selected enabled?
                        :on-selected-changed (fn [enabled?]
                                               (set-enabled! component-id items props group-id enabled?))}
                       
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


;; List Item Card Component


(defn- list-item-card
  "A single item in the list with drag-and-drop support."
  [{:keys [component-id items props item item-label depth selected? dragging? drop-target-id drop-position renaming? parent-disabled?]}]
  (let [item-id (:id item)
        enabled? (:enabled? item true)
        effectively-disabled? (or (not enabled?) parent-disabled?)
        is-drop-target? (= item-id drop-target-id)
        
        item-classes (list-item-style-classes
                       (u/->map& depth selected? dragging? drop-position effectively-disabled?
                                 :drop-target? is-drop-target?))
        name-classes (list-item-name-style-classes
                       (u/->map selected? effectively-disabled?))]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [node]
                   (setup-drag-source! node item-id component-id)
                   (setup-drag-target! node item-id false component-id items props)
                   (setup-click-handler! node component-id item-id items))
     :desc {:fx/type :h-box
            :style-class item-classes
            :children [{:fx/type :check-box
                        :selected enabled?
                        :on-selected-changed (fn [enabled?]
                                               (set-enabled! component-id items props item-id enabled?))}
                       
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
        dragging? (contains? (or dragging-ids #{}) item-id)]
    (if (chains/group? item)
      (let [collapsed? (:collapsed? item false)
            children-items (:items item [])
            enabled? (:enabled? item true)
            effectively-disabled? (or (not enabled?) parent-disabled?)]
        {:fx/type :v-box
         :spacing 2
         :children (into
                    [{:fx/type group-header
                      :component-id component-id
                      :items items
                      :props props
                      :group item
                      :depth depth
                      :selected? selected?
                      :dragging? dragging?
                      :drop-target-id drop-target-id
                      :drop-position drop-position
                      :renaming? (= item-id renaming-id)
                      :parent-disabled? parent-disabled?}]
                    (when-not collapsed?
                      (map-indexed
                        (fn [idx child]
                          {:fx/type render-list-item
                           :fx/key [(:id child) idx]
                           :component-id component-id
                           :items items
                           :props props
                           :item child
                           :depth (inc depth)
                           :selected-ids selected-ids
                           :dragging-ids dragging-ids
                           :drop-target-id drop-target-id
                           :drop-position drop-position
                           :renaming-id renaming-id
                           :parent-disabled? effectively-disabled?
                           :get-item-label get-item-label})
                        children-items)))})
      {:fx/type list-item-card
       :component-id component-id
       :items items
       :props props
       :item item
       :item-label (get-item-label item)
       :depth depth
       :selected? selected?
       :dragging? dragging?
       :drop-target-id drop-target-id
       :drop-position drop-position
       :renaming? (= item-id renaming-id)
       :parent-disabled? parent-disabled?})))


;; Group Toolbar


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
   - :items-key - Key for items in dispatched event (optional)
   - :items-path - Direct path to items in state for async drag-drop (optional)"
  [{:keys [fx/context items get-item-label on-items-changed on-selection-changed
           on-copy clipboard-items header-label empty-text allow-groups?
           component-id on-change-event on-change-params items-key items-path]
    :or {header-label "LIST"
         empty-text "No items"
         allow-groups? true
         items-key :items}
    :as props}]
  (let [ui-state (fx/sub-ctx context subs/list-ui-state component-id)
        {:keys [selected-ids dragging-ids drop-target-id
                drop-position renaming-id]} ui-state
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
                               :text (or header-label "LIST")
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
                   :text (or empty-text "No items\nAdd from bank →")
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
                                           :get-item-label get-item-label})
                                        items)}})]}))



;; Callback Factory Functions



(defn make-dispatch-callback
  "Create an on-items-changed callback that dispatches an event.
   
   Usage:
   :on-items-changed (make-dispatch-callback :effects/set-chain {:col col :row row} :items)"
  [event-type base-params items-key]
  (fn [new-items]
    (events/dispatch! (assoc base-params
                             :event/type event-type
                             items-key new-items))))

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
  (if-let [existing (get @keyboard-handler-atoms component-id)]
    existing
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
  (.addEventFilter
    node
    javafx.scene.input.KeyEvent/KEY_PRESSED
    (reify javafx.event.EventHandler
      (handle [_ event]
        (let [code (.getCode event)
              ctrl? (.isShortcutDown event)
              items @items-atom
              props @props-atom]
          (cond
            (and ctrl? (= code javafx.scene.input.KeyCode/C))
            (do (copy-selected! component-id items props) (.consume event))

            (and ctrl? (= code javafx.scene.input.KeyCode/V))
            (do (paste-items! component-id items props) (.consume event))

            (and ctrl? (= code javafx.scene.input.KeyCode/X))
            (do (copy-selected! component-id items props)
                (delete-selected! component-id items props)
                (.consume event))

            (and ctrl? (= code javafx.scene.input.KeyCode/A))
            (do (select-all! component-id items) (.consume event))
            
            (and ctrl? (= code javafx.scene.input.KeyCode/G))
            (do (group-selected! component-id items props) (.consume event))

            (= code javafx.scene.input.KeyCode/DELETE)
            (do (delete-selected! component-id items props) (.consume event))

            (= code javafx.scene.input.KeyCode/ESCAPE)
            (do (clear-selection! component-id) (.consume event))))))))



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
   - :items-key - Key for items in event (default :items)
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
           on-change-event on-change-params items-key items-path
           on-selection-event on-selection-params selection-key
           on-copy-fn clipboard-items
           header-label empty-text allow-groups? fallback-label]
    :or {items-key :items
         selection-key :selected-ids
         header-label "LIST"
         empty-text "No items"
         allow-groups? true
         fallback-label "Unknown"}
    :as props}]
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
                           (make-dispatch-callback on-change-event on-change-params items-key))
        
        on-selection-changed (when on-selection-event
                               (make-selection-callback on-selection-event on-selection-params selection-key))
        
        handler-props {:on-items-changed on-items-changed
                       :on-selection-changed on-selection-changed
                       :on-copy on-copy-fn
                       :clipboard-items clipboard-items}
        
        {:keys [items-atom props-atom]} (get-or-create-handler-atoms! component-id)
        _ (update-handler-atoms! component-id items handler-props)]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [node]
                   (setup-keyboard-handlers! node component-id items-atom props-atom)
                   (.setFocusTraversable node true)
                   (.requestFocus node))
     :desc {:fx/type :v-box
            :children [{:fx/type list-sidebar
                        :fx/context context
                        :v-box/vgrow :always
                        :items items
                        :get-item-label label-fn
                        :on-items-changed on-items-changed
                        :on-selection-changed on-selection-changed
                        :on-copy on-copy-fn
                        :clipboard-items clipboard-items
                        :header-label header-label
                        :empty-text empty-text
                        :allow-groups? allow-groups?
                        :component-id component-id
                        :on-change-event on-change-event
                        :on-change-params on-change-params
                        :items-key items-key
                        :items-path items-path}]}}))
