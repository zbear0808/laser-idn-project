(ns laser-show.views.components.list
  "Self-contained hierarchical list component with drag-and-drop reordering and group support.
   
   This component manages its own internal UI state (selection, drag/drop) and
   communicates changes to parents via event dispatch. This eliminates the need for
   external event dispatchers and ensures consistent behavior across all usages.
   
   Features:
   - Multi-select with Click, Ctrl+Click, Shift+Click
   - Drag-and-drop reordering (single item or multiple selected items)
   - Copy/paste operations with deep copy (new UUIDs)
   - Delete with proper cleanup
   - Groups/folders with collapse/expand, nesting up to 3 levels
   - Renaming items and groups
   - Keyboard shortcuts (Ctrl+C/V/X/A/G, Delete, Escape, F2)
   
   Event Dispatch API:
   - :on-change-event + :on-change-params - Dispatches event when items change
   - :on-copy-fn - Called with copied items for parent to store in clipboard
   
   Public API:
   - list-editor - Complete list editor with keyboard handling, drag-drop,
                   multi-select, copy/paste, and grouping
   
   Styling is defined in laser-show.css.list."
  (:require
   [cljfx.api :as fx]
   [clojure.tools.logging :as log]
   [laser-show.animation.chains :as chains]
   [laser-show.common.util :as u]
   [laser-show.events.core :as events]
   [laser-show.subs :as subs]
   [laser-show.state.core :as state]
   [laser-show.views.components.list-dnd :as dnd]))

;; State Access Helpers

(defn- get-ui-state
  "Get the list-ui state for a component."
  [component-id]
  (state/get-in-state [:list-ui component-id]))

(defn- get-selected-ids
  "Get selected IDs set from component state."
  [component-id]
  (or (:selected-ids (get-ui-state component-id)) #{}))


;; Event Dispatch Helpers

(defn- invoke-items-changed!
  "Dispatch the on-change-event if present.
   Always uses :items key in the dispatched event."
  [props new-items]
  (when-let [event-type (:on-change-event props)]
    (events/dispatch! (assoc (or (:on-change-params props) {})
                             :event/type event-type
                             :items new-items))))

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


;; Item Operations (Event Dispatch - No State Mutation)

(defn- delete-selected!
  "Delete selected items and dispatch on-change-event."
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
      (invoke-items-changed! props new-items))))


;; Group Operations (Event Dispatch)

(defn- normalize-selected-ids
  "Remove redundant descendant IDs when a group AND all its descendants are selected."
  [selected-ids items]
  (let [selected-ids (set selected-ids)  ; Ensure it's a set for contains? checks
        id->path (chains/find-paths-by-ids items selected-ids)
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
  (log/debug "group-selected! called"
             {:component-id component-id
              :items-count (count items)
              :props-keys (keys props)})
  (if (empty? items)
    (log/warn "group-selected! called with EMPTY items vector!" {:component-id component-id})
    (if-let [selected-ids (seq (get-selected-ids component-id))]
      (let [normalized-ids (normalize-selected-ids selected-ids items)
            _ (log/debug "group-selected! normalized IDs"
                         {:original-count (count selected-ids)
                          :normalized-count (count normalized-ids)
                          :normalized-ids normalized-ids})
            id->path (chains/find-paths-by-ids items normalized-ids)
            all-paths (vals id->path)
            _ (log/debug "group-selected! paths found"
                         {:paths-count (count all-paths)
                          :paths all-paths})
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
                _ (log/debug "group-selected! items to group"
                             {:items-to-group-count (count items-to-group)
                              :items-to-group items-to-group})
                new-group (chains/create-group items-to-group)
                after-remove (chains/delete-paths-safely items sorted-paths)
                _ (log/debug "group-selected! after remove"
                             {:after-remove-count (count after-remove)})
                first-path (first sorted-paths)
                insert-path (if (empty? common-parent)
                              [(first first-path)]
                              (conj common-parent (last first-path)))
                new-items (chains/insert-at-path after-remove insert-path new-group)
                _ (log/debug "group-selected! new items after insert"
                             {:new-items-count (count new-items)
                              :new-items new-items})
                group-id (:id new-group)]
            (events/dispatch! {:event/type :list/select-item
                               :component-id component-id
                               :item-id group-id
                               :mode :single})
            (invoke-items-changed! props new-items))
          (log/warn "group-selected! - items not at same level or no paths found"
                    {:same-level? same-level?
                     :all-paths-count (count all-paths)})))
      (log/warn "group-selected! - no items selected"))))

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
                   (dnd/setup-drag-source! node group-id component-id)
                   (dnd/setup-drag-target! node group-id true component-id items props)
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
                   (dnd/setup-drag-source! node item-id component-id)
                   (dnd/setup-drag-target! node item-id false component-id items props)
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

(defn- list-sidebar
  "INTERNAL: Low-level list rendering component.
   External code should use list-editor instead, which wraps this
   with keyboard handling and proper state management."
  [{:keys [fx/context items get-item-label
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


;; Label Function Factories

(defn- make-registry-label
  "INTERNAL: Create a get-item-label function that looks up names from a registry.
   Always uses :name key for registry lookup since all registries use :name."
  [id-key lookup-fn fallback]
  (fn [item]
    (or (:name item)
        (:name (lookup-fn (get item id-key)))
        fallback)))



;; Keyboard Handler Setup

(defonce ^:private keyboard-handler-atoms (atom {}))

(defn- get-or-create-handler-atoms!
  "Get or create atoms for a component's keyboard handler state."
  [component-id]
  (or (get @keyboard-handler-atoms component-id)
      (let [new-atoms {:items-atom (atom [])
                       :props-atom (atom {})}]
        (log/debug "Creating new handler atoms for component" {:component-id component-id})
        (swap! keyboard-handler-atoms assoc component-id new-atoms)
        new-atoms)))

(defn- update-handler-atoms!
  "Update the atoms with current items and props values."
  [component-id items props]
  (let [{:keys [items-atom props-atom]} (get-or-create-handler-atoms! component-id)]
    (log/debug "Updating handler atoms"
               {:component-id component-id
                :items-count (count items)
                :props-keys (keys props)})
    (reset! items-atom items)
    (reset! props-atom props)))

(defn- setup-keyboard-handlers!
  "Setup keyboard handlers on a parent node for a hierarchical list component."
  [^javafx.scene.Node node component-id items-atom props-atom]
  (log/info "Setting up keyboard handlers"
            {:component-id component-id
             :node-class (.getSimpleName (class node))
             :focus-traversable? (.isFocusTraversable node)})
  (.addEventFilter node
    javafx.scene.input.KeyEvent/KEY_PRESSED
    (on-fx-event
      (fn [event]
        (let [code (.getCode event)
              ctrl? (.isShortcutDown event)
              shift? (.isShiftDown event)
              alt? (.isAltDown event)
              items @items-atom
              props @props-atom
              focused? (.isFocused node)
              scene (.getScene node)
              focus-owner (when scene (.getFocusOwner scene))]
          (log/debug "KEY_PRESSED event received in list handler"
                     {:component-id component-id
                      :key-code (str code)
                      :ctrl? ctrl?
                      :shift? shift?
                      :alt? alt?
                      :node-focused? focused?
                      :focus-owner-class (when focus-owner (.getSimpleName (class focus-owner)))
                      :items-count (count items)
                      :props-keys (keys props)
                      :selected-ids (get-selected-ids component-id)})
          ;; Only handle keyboard shortcuts when this node has focus
          ;; This prevents multiple list handlers from intercepting the same event
          (when focused?
            (let [handled? (cond
                             (and ctrl? (= code javafx.scene.input.KeyCode/C))
                             (do (log/debug "Handling Ctrl+C (copy)")
                                 (copy-selected! component-id items props)
                                 true)

                             (and ctrl? (= code javafx.scene.input.KeyCode/V))
                             (do (log/debug "Handling Ctrl+V (paste)"
                                            {:clipboard-items-count (count (:clipboard-items props))})
                                 (paste-items! component-id items props)
                                 true)

                             (and ctrl? (= code javafx.scene.input.KeyCode/X))
                             (do (log/debug "Handling Ctrl+X (cut)")
                                 (copy-selected! component-id items props)
                                 (delete-selected! component-id items props)
                                 true)

                             (and ctrl? (= code javafx.scene.input.KeyCode/A))
                             (do (log/debug "Handling Ctrl+A (select all)")
                                 (select-all! component-id items)
                                 true)

                             (and ctrl? (= code javafx.scene.input.KeyCode/G))
                             (do (log/debug "Handling Ctrl+G (group)")
                                 (group-selected! component-id items props)
                                 true)

                             (= code javafx.scene.input.KeyCode/DELETE)
                             (do (log/debug "Handling Delete")
                                 (delete-selected! component-id items props)
                                 true)

                             (= code javafx.scene.input.KeyCode/ESCAPE)
                             (do (log/debug "Handling Escape")
                                 (clear-selection! component-id)
                                 true)
                             
                             :else false)]
              (when handled?
                (log/debug "Event consumed" {:key-code (str code)})
                (.consume event)))))))))


;; All-in-One Wrapper Component

(defn list-editor
  "Complete hierarchical list editor with keyboard handling and event dispatch.
   This is the recommended component for most use cases.
   
   Selection state is managed internally in [:list-ui component-id] and can be
   read via subs/list-ui-state subscription.
   
   Props:
   - :fx/context - cljfx context (required)
   - :items - Vector of items (required)
   - :component-id - Unique ID for state isolation (required)
   
   Label configuration (one of):
   - :get-item-label - Custom function item -> string
   - :item-id-key + :item-registry-fn - For registry lookup (always uses :name)
   
   Event dispatch:
   - :on-change-event - Event type for items changed
   - :on-change-params - Base params for change event
   - :items-path - Direct path to items in state
   
   - :on-copy-fn - Custom copy function
   - :clipboard-items - Items for paste
   
   UI customization:
   - :header-label - Header text (default 'LIST')
   - :empty-text - Empty state text
   - :allow-groups? - Enable grouping (default true)
   - :fallback-label - Label for unknown items (default \"Unknown\")"
  [{:keys [fx/context items component-id
           get-item-label item-id-key item-registry-fn
           on-change-event on-change-params items-path
           on-copy-fn clipboard-items
           header-label empty-text allow-groups? fallback-label]
    :or {header-label "LIST"
         empty-text "No items"
         allow-groups? true
         fallback-label "Unknown"}}]
  (log/debug "list-editor render"
             {:component-id component-id
              :items-count (count items)
              :clipboard-items-count (count clipboard-items)})
  (let [label-fn (cond
                   get-item-label
                   get-item-label
                   
                   (and item-id-key item-registry-fn)
                   (make-registry-label item-id-key item-registry-fn fallback-label)
                   
                   :else
                   (fn [item] (or (:name item) fallback-label)))
        
        handler-props {:on-change-event on-change-event
                       :on-change-params on-change-params
                       :clipboard-items clipboard-items
                       :on-copy on-copy-fn}
        
        {:keys [items-atom props-atom]} (get-or-create-handler-atoms! component-id)
        _ (update-handler-atoms! component-id items handler-props)]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [^javafx.scene.Node node]
                   (log/info "list-editor on-created"
                             {:component-id component-id
                              :node-class (.getSimpleName (class node))})
                   (setup-keyboard-handlers! node component-id items-atom props-atom)
                   (.setFocusTraversable node true)
                   ;; NOTE: Don't auto-request focus on creation!
                   ;; Focus should be determined by user interaction (clicking items).
                   ;; Auto-requesting focus causes focus stealing between multiple lists
                   ;; in the same container (e.g., cue chain editor with two lists).
                   ;; Also add a focus listener for debugging
                   (.addListener (.focusedProperty node)
                     (reify javafx.beans.value.ChangeListener
                       (changed [_ _ old-val new-val]
                         (log/debug "list-editor focus changed"
                                    {:component-id component-id
                                     :old-focused? old-val
                                     :new-focused? new-val})))))
     :desc {:fx/type :v-box
            :children [(u/->map&
                        items clipboard-items header-label
                        empty-text allow-groups? component-id on-change-event on-change-params items-path
                        :fx/type list-sidebar
                        :fx/context context
                        :v-box/vgrow :always
                        :get-item-label label-fn
                        :on-copy on-copy-fn)]}}))
