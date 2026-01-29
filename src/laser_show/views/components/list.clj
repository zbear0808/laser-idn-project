(ns laser-show.views.components.list
  "Self-contained hierarchical list component with drag-and-drop reordering and group support.
   
   This component manages its own internal UI state (selection, drag/drop) and
   communicates changes to parents via event dispatch. This eliminates the need for
   external event dispatchers and ensures consistent behavior across all usages.
   
   Features:
   - Multi-select with Click, Ctrl+Click, Shift+Click
   - Drag-and-drop reordering (single item or multiple selected items)
   - Copy/paste operations with deep copy (new UUIDs) via centralized clipboard
   - Delete with proper cleanup
   - Groups/folders with collapse/expand, nesting up to 3 levels
   - Renaming items and groups
   - Keyboard shortcuts (Ctrl+C/V/X/A/G, Delete, Escape, F2)
   
   Event Dispatch API:
   All item operations dispatch to :list/* event handlers in handlers/list.clj.
   Required props:
   - :on-change-event + :on-change-params - Event to dispatch when items change
   - :items-path - Path to items in state (for handlers to read current items)
   - :clipboard-type - Type for clipboard operations (:cue-chain-items or :item-effects)
   
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
   [laser-show.state.clipboard :as clipboard]
   [laser-show.state.core :as state]
   [laser-show.views.components.list-dnd :as dnd]
   [laser-show.views.components.icons :as icons]))

;; State Access Helpers

(defn- get-ui-state
  "Get the list-ui state for a component."
  [component-id]
  (state/get-in-state [:list-ui component-id]))

(defn- get-selected-ids
  "Get selected IDs set from component state."
  [component-id]
  (or (:selected-ids (get-ui-state component-id)) #{}))


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


;; Item Operations - All dispatch events to handlers/list.clj
;; Handlers read items from state using :items-path to avoid stale closures

(defn- dispatch-delete-selected!
  "Dispatch :list/delete-selected event."
  [props]
  (let [{:keys [component-id items-path on-change-event on-change-params]} props]
    (events/dispatch! {:event/type :list/delete-selected
                       :component-id component-id
                       :items-path items-path
                       :on-change-event on-change-event
                       :on-change-params on-change-params})))

(defn- dispatch-copy-selected!
  "Dispatch :list/copy-selected event."
  [props]
  (let [{:keys [component-id items-path clipboard-type]} props]
    (events/dispatch! {:event/type :list/copy-selected
                       :component-id component-id
                       :items-path items-path
                       :clipboard-type (or clipboard-type :cue-chain-items)})))

(defn- dispatch-paste-items!
  "Dispatch :list/paste-items event."
  [props]
  (let [{:keys [component-id items-path on-change-event on-change-params clipboard-type]} props]
    (events/dispatch! {:event/type :list/paste-items
                       :component-id component-id
                       :items-path items-path
                       :on-change-event on-change-event
                       :on-change-params on-change-params
                       :clipboard-type (or clipboard-type :cue-chain-items)})))


;; Group Operations - All dispatch events to handlers/list.clj

(defn- dispatch-create-empty-group!
  "Dispatch :list/create-empty-group event."
  [props]
  (let [{:keys [component-id items-path on-change-event on-change-params]} props]
    (events/dispatch! {:event/type :list/create-empty-group
                       :component-id component-id
                       :items-path items-path
                       :on-change-event on-change-event
                       :on-change-params on-change-params})))

(defn- dispatch-group-selected!
  "Dispatch :list/group-selected event."
  [props]
  (let [{:keys [component-id items-path on-change-event on-change-params]} props]
    (events/dispatch! {:event/type :list/group-selected
                       :component-id component-id
                       :items-path items-path
                       :on-change-event on-change-event
                       :on-change-params on-change-params})))

(defn- dispatch-ungroup!
  "Dispatch :list/ungroup event."
  [props group-id]
  (let [{:keys [component-id items-path on-change-event on-change-params]} props]
    (events/dispatch! {:event/type :list/ungroup
                       :component-id component-id
                       :items-path items-path
                       :item-id group-id
                       :on-change-event on-change-event
                       :on-change-params on-change-params})))

(defn- dispatch-toggle-collapse!
  "Dispatch :list/toggle-collapse event."
  [props group-id]
  (let [{:keys [items-path on-change-event on-change-params]} props]
    (events/dispatch! {:event/type :list/toggle-collapse
                       :items-path items-path
                       :item-id group-id
                       :on-change-event on-change-event
                       :on-change-params on-change-params})))


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

(defn- dispatch-commit-rename!
  "Dispatch :list/commit-rename event."
  [props item-id new-name]
  (let [{:keys [component-id items-path on-change-event on-change-params]} props]
    (events/dispatch! {:event/type :list/commit-rename
                       :component-id component-id
                       :items-path items-path
                       :item-id item-id
                       :new-name new-name
                       :on-change-event on-change-event
                       :on-change-params on-change-params})))

(defn- dispatch-set-enabled!
  "Dispatch :list/set-enabled event."
  [props item-id enabled?]
  (let [{:keys [items-path on-change-event on-change-params]} props]
    (events/dispatch! {:event/type :list/set-enabled
                       :items-path items-path
                       :item-id item-id
                       :enabled? enabled?
                       :on-change-event on-change-event
                       :on-change-params on-change-params})))


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
                             (start-rename! component-id item-id)
                             (do (handle-selection! component-id item-id ctrl? shift? items)
                                 (request-list-focus! node)))
                           (.consume event))))))


;; Shared UI Helpers - Rename Text Field

(defn- rename-text-field
  "Create a text field for inline renaming with auto-focus and select-all."
  [{:keys [component-id props item-id current-name style-class]}]
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
                       (dispatch-commit-rename! props item-id
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
                        :on-action (fn [_] (dispatch-toggle-collapse! props group-id))}

                       {:fx/type :check-box
                        :selected enabled?
                        :on-selected-changed (fn [new-enabled?]
                                               (dispatch-set-enabled! props group-id new-enabled?))}

                       (if renaming?
                         {:fx/type rename-text-field
                          :component-id component-id
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
                        :on-action (fn [_] (dispatch-ungroup! props group-id))}]}}))


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
                                               (dispatch-set-enabled! props item-id new-enabled?))}

                       (if renaming?
                         {:fx/type rename-text-field
                          :component-id component-id
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
  [{:keys [props selection-count can-create-group?]}]
  {:fx/type :h-box
   :spacing 4
   :children [{:fx/type :button
               :text "New"
               :graphic {:fx/type icons/icon :icon :folder-open-alt :size 8}
               :style-class "chain-toolbar-btn"
               :on-action (fn [_] (dispatch-create-empty-group! props))}
              {:fx/type :button
               :text "Group"
               :graphic {:fx/type icons/icon :icon :group :size 8}
               :disable (or (zero? selection-count) (not can-create-group?))
               :style-class "chain-toolbar-btn"
               :on-action (fn [_] (dispatch-group-selected! props))}]})


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
                                      :style-class "header-section"}
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
                   :props props
                   :selection-count selection-count
                   :can-create-group? can-create-group?})

                {:fx/type :h-box
                 :spacing 4
                 :children [{:fx/type :button
                             :text "Copy"
                             :graphic {:fx/type icons/icon :icon :copy :size 8}
                             :disable (zero? selection-count)
                             :style-class "chain-toolbar-btn"
                             :on-action (fn [_] (dispatch-copy-selected! props))}
                            {:fx/type :button
                             :text "Paste"
                             :graphic {:fx/type icons/icon :icon :paste :size 8}
                             :disable (not can-paste?)
                             :style-class "chain-toolbar-btn"
                             :on-action (fn [_] (dispatch-paste-items! props))}
                            {:fx/type :button
                             :text "Del"
                             :graphic {:fx/type icons/icon :icon :trash-alt :size 8}
                             :disable (zero? selection-count)
                             :style-class "chain-toolbar-btn-danger"
                             :on-action (fn [_] (dispatch-delete-selected! props))}]}
                (if (empty? items)
                  {:fx/type :label
                   :text empty-text
                   :style-class "chain-empty-text"}
                  {:fx/type :scroll-pane
                   :fit-to-width true
                   :fit-to-height false
                   :hbar-policy :as-needed
                   :v-box/vgrow :always
                   :style-class "scroll-pane-base"
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
  (.addEventFilter
   node
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
                               (dispatch-copy-selected! props)
                               true)

                           (and ctrl? (= code javafx.scene.input.KeyCode/V))
                           (do (log/debug "Handling Ctrl+V (paste)"
                                          {:clipboard-items-count (count (:clipboard-items props))})
                               (dispatch-paste-items! props)
                               true)

                           (and ctrl? (= code javafx.scene.input.KeyCode/X))
                           (do (log/debug "Handling Ctrl+X (cut)")
                               (dispatch-copy-selected! props)
                               (dispatch-delete-selected! props)
                               true)

                           (and ctrl? (= code javafx.scene.input.KeyCode/A))
                           (do (log/debug "Handling Ctrl+A (select all)")
                               (select-all! component-id items)
                               true)

                           (and ctrl? (= code javafx.scene.input.KeyCode/G))
                           (do (log/debug "Handling Ctrl+G (group)")
                               (dispatch-group-selected! props)
                               true)

                           (= code javafx.scene.input.KeyCode/DELETE)
                           (do (log/debug "Handling Delete")
                               (dispatch-delete-selected! props)
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

        handler-props {:component-id component-id
                       :items-path items-path
                       :on-change-event on-change-event
                       :on-change-params on-change-params
                       :clipboard-items clipboard-items
                       :clipboard-type (get on-change-params :clipboard-type :cue-chain-items)
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
