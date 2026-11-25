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
   With :component-id they are the list's command address: its keys, clicks
   and drops act on the list it shows when they come (list-address).
   What a list copies and pastes comes from its :component-id
   (state.clipboard/list-type).
   
   Public API:
   - list-editor - Complete list editor with keyboard handling, drag-drop,
                   multi-select, copy/paste, and grouping
   
   Styling is defined in laser-show.css.list."
  (:require
   [clojure.string :as str]
   [clj-font-awesome.core :as fa]
   [cljfx.api :as fx]
   [laser-show.animation.chains :as chains]
   [laser-show.common.util :as u]
   [laser-show.events.core :as events]
   [laser-show.state.clipboard :as clipboard]
   [laser-show.subs :as subs]
   [laser-show.views.components.list-dnd :as dnd]
   [laser-show.views.components.shortcuts :as shortcuts]
   [laser-show.views.keys :as keys])
  (:import [javafx.scene.input ContextMenuEvent MouseButton MouseEvent]))


;; The list's command address: which list a key, click or drop is for,
;; {:component-id :items-path :on-change-event :on-change-params}. It is the
;; list editor's :user-data, which each render sets. The listeners are
;; installed once, on nodes a later render can give to another list (the
;; Effects editor shows another cell in the same nodes, the item effects
;; list another preset's effects), so they read it when the input comes
;; (reactivity plan 04).

(defn- list-address
  "The command address of the list editor node is in, or is: as the last
   render set it. nil when node is in no list."
  [^javafx.scene.Node node]
  (loop [^javafx.scene.Node n node]
    (when n
      (or (::address (.getUserData n))
          (recur (.getParent n))))))

(defn- event-address
  "The command address of the list the source of e is in when e comes."
  [^javafx.event.Event e]
  (let [source (.getSource e)]
    (when (instance? javafx.scene.Node source)
      (list-address source))))


;; JavaFX Event Handler Helper

(defn- on-fx-event
  "Create a JavaFX EventHandler from a Clojure function."
  [f]
  (reify javafx.event.EventHandler
    (handle [_ event] (f event))))


;; Selection Logic (Event Dispatching)

(defn- handle-selection!
  "Handle selection based on click modifiers.
   Dispatches selection event to handler to resolve directly from application state.
   Supports single click, Ctrl+click toggle, Shift+click range, and Ctrl+Shift+click range add."
  [component-id item-id ctrl? shift? items-path]
  (let [mode (cond
               (and ctrl? shift?) :ctrl-shift
               shift? :shift
               ctrl? :ctrl
               :else :single)]
    (events/dispatch!
     (u/->map& component-id item-id mode items-path
               :event/type :list/select-item))))

(defn- select-all!
  "Select all items in the component by delegating to event handler with items-path."
  [component-id items-path]
  (events/dispatch! (u/->map& component-id items-path
                              :event/type :list/select-all)))

(defn- clear-selection!
  "Clear selection and cancel rename."
  [component-id]
  (events/dispatch! {:event/type :list/clear-selection
                     :component-id component-id}))


;; Item Operations - All dispatch events to handlers/list.clj
;; Handlers read items from state using :items-path to avoid stale closures.
;; Each takes the list's command address (list-address).

(defn- dispatch-delete-selected!
  "Dispatch :list/delete-selected event."
  [address]
  (let [{:keys [component-id items-path on-change-event on-change-params]} address]
    (events/dispatch! {:event/type :list/delete-selected
                       :component-id component-id
                       :items-path items-path
                       :on-change-event on-change-event
                       :on-change-params on-change-params})))

(defn- dispatch-copy-selected!
  "Dispatch :list/copy-selected event."
  [address]
  (let [{:keys [component-id items-path]} address]
    (events/dispatch! {:event/type :list/copy-selected
                       :component-id component-id
                       :items-path items-path})))

(defn- dispatch-cut-selected!
  "Dispatch :list/cut-selected event: copy and delete in one step."
  [address]
  (let [{:keys [component-id items-path on-change-event on-change-params]} address]
    (events/dispatch! {:event/type :list/cut-selected
                       :component-id component-id
                       :items-path items-path
                       :on-change-event on-change-event
                       :on-change-params on-change-params})))

(defn- dispatch-paste-items!
  "Dispatch :list/paste-items event."
  [address]
  (let [{:keys [component-id items-path on-change-event on-change-params]} address]
    (events/dispatch! {:event/type :list/paste-items
                       :component-id component-id
                       :items-path items-path
                       :on-change-event on-change-event
                       :on-change-params on-change-params})))


;; Group Operations - All dispatch events to handlers/list.clj

(defn- dispatch-create-empty-group!
  "Dispatch :list/create-empty-group event."
  [address]
  (let [{:keys [component-id items-path on-change-event on-change-params]} address]
    (events/dispatch! {:event/type :list/create-empty-group
                       :component-id component-id
                       :items-path items-path
                       :on-change-event on-change-event
                       :on-change-params on-change-params})))

(defn- dispatch-group-selected!
  "Dispatch :list/group-selected event."
  [address]
  (let [{:keys [component-id items-path on-change-event on-change-params]} address]
    (events/dispatch! {:event/type :list/group-selected
                       :component-id component-id
                       :items-path items-path
                       :on-change-event on-change-event
                       :on-change-params on-change-params})))

(defn- dispatch-ungroup!
  "Dispatch :list/ungroup event."
  [address group-id]
  (let [{:keys [component-id items-path on-change-event on-change-params]} address]
    (events/dispatch! {:event/type :list/ungroup
                       :component-id component-id
                       :items-path items-path
                       :item-id group-id
                       :on-change-event on-change-event
                       :on-change-params on-change-params})))

(defn- dispatch-toggle-collapse!
  "Dispatch :list/toggle-collapse event."
  [address group-id]
  (let [{:keys [items-path on-change-event on-change-params]} address]
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
  [address item-id new-name]
  (let [{:keys [component-id items-path on-change-event on-change-params]} address]
    (events/dispatch! {:event/type :list/commit-rename
                       :component-id component-id
                       :items-path items-path
                       :item-id item-id
                       :new-name new-name
                       :on-change-event on-change-event
                       :on-change-params on-change-params})))

(defn- dispatch-set-enabled!
  "Dispatch :list/set-enabled event."
  [address item-id enabled?]
  (let [{:keys [items-path on-change-event on-change-params]} address]
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
  [{:keys [depth selected? dragging? drop-before? drop-into? drop-after? effectively-disabled?]}]
  (let [d (depth->class depth)]
    (build-style-classes
     ["group-header" (str "group-depth-" d) (str "group-indent-" d)]
     [[selected? "group-header-selected"]
      [drop-into? "group-header-drop-into"]
      [drop-before? "group-header-drop-before"]
      [drop-after? "group-header-drop-after"]
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

(defn- select-for-menu!
  "Before item-id's row shows its menu, or on a right-click: the item
   selected for the menu and the keys after it, unless the selection has it
   (a right-click in a multi-selection keeps it; :mode :context, decided by
   the selection when it's handled), and the focus on the list, as a click
   gives it."
  [^javafx.scene.Node node item-id]
  (when-let [{:keys [component-id items-path]} (list-address node)]
    (events/dispatch! (u/->map& component-id item-id items-path
                                :event/type :list/select-item
                                :mode :context))
    (request-list-focus! node)))

(defn- setup-click-handler!
  "Setup click handler on a node for selection and double-click rename, in
   the list the node is in when the click comes (list-address). The row's
   menu asked for selects the row first (select-for-menu!): the menu opens
   on the press on macOS and Linux, and its window can take the release,
   so the row may get no click (follow-up B1)."
  [^javafx.scene.Node node item-id]
  (let [;; This press asked for the row's menu: its click, if one comes,
        ;; changes nothing more (macOS's Control-click would toggle the row
        ;; off again)
        menu-press? (volatile! false)]
    (.addEventFilter node MouseEvent/MOUSE_PRESSED
                     (on-fx-event (fn [_] (vreset! menu-press? false))))
    ;; It comes here before the label hosting the row's menu shows it
    (.addEventHandler node ContextMenuEvent/CONTEXT_MENU_REQUESTED
                      (on-fx-event (fn [_]
                                     (vreset! menu-press? true)
                                     (select-for-menu! node item-id))))
    (.setOnMouseClicked node
                        (on-fx-event
                         (fn [event]
                           (when-let [{:keys [component-id items-path]} (when-not @menu-press? (list-address node))]
                             (let [button (.getButton event)
                                   click-count (.getClickCount event)
                                   ctrl? (or (.isShortcutDown event) (.isControlDown event))
                                   shift? (.isShiftDown event)]
                               (cond
                                 (= button MouseButton/PRIMARY)
                                 (do
                                   (if (= click-count 2)
                                     (start-rename! component-id item-id)
                                     (handle-selection! component-id item-id ctrl? shift? items-path))
                                   (request-list-focus! node)
                                   (.consume event))

                                 ;; Where the menu comes after the click (Windows)
                                 (= button MouseButton/SECONDARY)
                                 (select-for-menu! node item-id)))))))))


;; The toolbars' buttons: each acts on the list it is in when it's clicked
;; (event-address), with one handler the same every render

(defn- list-action
  "An :on-action handler doing (dispatch! address) for the list its button
   is in when it's clicked."
  [dispatch!]
  (fn [e]
    (when-let [address (event-address e)]
      (dispatch! address))))

(def ^:private copy-action (list-action dispatch-copy-selected!))
(def ^:private cut-action (list-action dispatch-cut-selected!))
(def ^:private paste-action (list-action dispatch-paste-items!))
(def ^:private delete-action (list-action dispatch-delete-selected!))
(def ^:private new-group-action (list-action dispatch-create-empty-group!))
(def ^:private group-action (list-action dispatch-group-selected!))

(defn- enable-action
  "The :on-action of item-id's On box: the item on or off as the box is
   now, in the list it is in then."
  [item-id]
  (fn [^javafx.event.ActionEvent e]
    (when-let [address (event-address e)]
      (dispatch-set-enabled! address item-id (.isSelected ^javafx.scene.control.CheckBox (.getSource e))))))


;; Shared UI Helpers - Rename Text Field

(defn- cancel-rename-in!
  "Cancel rename mode in the list node is in, if it is in one."
  [node]
  (some-> (list-address node) :component-id cancel-rename!))

(defn- rename-text-field
  "A text field for inline renaming, focused with its text selected. Its
   Enter, Escape and losing the focus act on the list it is in then
   (list-address)."
  [{:keys [item-id current-name style-class]}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [^javafx.scene.control.TextField node]
                 (.addListener (.focusedProperty node)
                               (reify javafx.beans.value.ChangeListener
                                 (changed [_ _ _ focused?]
                                   (when-not focused?
                                     (cancel-rename-in! node)))))
                 (javafx.application.Platform/runLater
                  (fn []
                    (.requestFocus node)
                    (javafx.application.Platform/runLater #(.selectAll node)))))
   :desc {:fx/type :text-field
          :text (or current-name "Untitled")
          :style-class style-class
          :on-action (fn [^javafx.event.ActionEvent e]
                       (when-let [address (event-address e)]
                         (dispatch-commit-rename! address item-id
                                                  (.getText ^javafx.scene.control.TextField (.getSource e)))))
          :on-key-pressed (fn [^javafx.scene.input.KeyEvent e]
                            (when (= (.getCode e) javafx.scene.input.KeyCode/ESCAPE)
                              (cancel-rename-in! (.getSource e))))}})


;; Labels and menus: a function, or [f & args], a top-level function and
;; plain data, which stays equal from render to render as a closure made in
;; a render doesn't (reactivity plan 34)

(defn- call-spec
  "(f x) for spec f, a function; for [f & args], (apply f (conj args x))."
  [spec x]
  (if (vector? spec)
    (apply (first spec) (conj (subvec spec 1) x))
    (spec x)))

(defn- row-text
  "What item's row says, from labels (the list editor's label props): a
   group's :get-group-label, else its name; an item's :get-item-label, else
   its :name, its registry entry's (:item-registry-fn of its :item-id-key)
   or :fallback-label."
  [{:keys [get-item-label get-group-label item-id-key item-registry-fn fallback-label]} item]
  (if (chains/group? item)
    (if get-group-label (call-spec get-group-label item) (or (:name item) "Group"))
    (cond
      get-item-label (call-spec get-item-label item)
      (and item-id-key item-registry-fn) (or (:name item)
                                             (:name (call-spec item-registry-fn (get item item-id-key)))
                                             fallback-label)
      :else (or (:name item) fallback-label))))


;; A row's ui state: the list's selection, drag and rename as far as they
;; concern the row and, for a group, the rows inside it, so a change for
;; another row leaves its description as it was

(defn- list-ui
  "The list's :list-ui entry as rows take it: {:selected :dragging (sets of
   ids) :drop [id position] :renaming id}."
  [{:keys [selected-ids dragging-ids drop-target-id drop-position renaming-id]}]
  {:selected (set selected-ids)
   :dragging (set dragging-ids)
   :drop (when (some? drop-target-id) [drop-target-id drop-position])
   :renaming renaming-id})

(defn- row-ids
  "item's id and, for a group, every id inside it."
  [item]
  (into #{(:id item)} (mapcat row-ids) (when (chains/group? item) (:items item))))

(defn- ui-within
  "ui (list-ui's, or a part of it) for the rows of ids: each key only when
   it concerns one of them."
  [{:keys [selected dragging drop renaming]} ids]
  (cond-> {}
    (some ids selected) (assoc :selected (into #{} (filter ids) selected))
    (some ids dragging) (assoc :dragging (into #{} (filter ids) dragging))
    (and drop (contains? ids (first drop))) (assoc :drop drop)
    (and (some? renaming) (contains? ids renaming)) (assoc :renaming renaming)))


;; Group Header Component

(defn- group-header
  "Header for a group showing collapse toggle, name, and item count."
  [{:keys [group label depth selected? dragging? drop-position renaming? parent-disabled?
           show-enable-toggle? menus]}]
  (let [group-id (:id group)
        enabled? (:enabled? group true)
        effectively-disabled? (or (not enabled?) parent-disabled?)
        collapsed? (:collapsed? group false)
        item-count (chains/count-items-recursive (:items group []))
        drop-before? (= drop-position :before)
        drop-into? (= drop-position :into)
        drop-after? (= drop-position :after)

        header-classes (group-header-style-classes
                        (u/->map depth selected? dragging? drop-before? drop-into? drop-after? effectively-disabled?))
        name-classes (group-name-style-classes
                      (u/->map depth selected? effectively-disabled?))
        header-node {:fx/type fx/ext-on-instance-lifecycle
                     :on-created (fn [node]
                                   (let [address #(list-address node)]
                                     (dnd/setup-drag-source! node group-id address)
                                     (dnd/setup-drag-target! node group-id true address)
                                     (setup-click-handler! node group-id)))
                     :desc {:fx/type :h-box
                            :style-class header-classes
                            :alignment :center-left
                            :children (filterv
                                       some?
                                       [{:fx/type :button
                                        :graphic {:fx/type fa/icon
                                                  :name (if collapsed? :circle-chevron-right :circle-chevron-down)
                                                  :size 14
                                                  :style-class "group-collapse-icon"}
                                        :style-class "group-collapse-btn"
                                        :tooltip {:fx/type :tooltip :text (if collapsed? "Expand" "Collapse")}
                                        :on-action (fn [e] (some-> (event-address e) (dispatch-toggle-collapse! group-id)))}

                                       (when show-enable-toggle?
                                         {:fx/type :check-box
                                          :selected enabled?
                                          :on-action (enable-action group-id)})

                                       (if renaming?
                                         {:fx/type rename-text-field
                                          :item-id group-id
                                          :current-name (:name group)
                                          :style-class "group-name-input"}
                                         {:fx/type :label
                                          :text label
                                          :style-class name-classes})

                                       {:fx/type :label
                                        :text (str "(" item-count ")")
                                        :style-class "group-count-badge"}

                                       {:fx/type :region :h-box/hgrow :always}

                                       {:fx/type :button
                                        :text "⊗"
                                        :style-class "group-ungroup-btn"
                                        :tooltip {:fx/type :tooltip :text "Ungroup: keep the items, lose the group"}
                                        :on-action (fn [e] (some-> (event-address e) (dispatch-ungroup! group-id)))}])}}
        context-menu (some-> (:group menus) (call-spec group))]
    (if context-menu
      {:fx/type :label
       :content-display :graphic-only
       :padding 0
       :max-width Double/MAX_VALUE
       :style "-fx-background-color: transparent;"
       :context-menu context-menu
       :graphic header-node}
      header-node)))


;; ============================================================================
;; List Item Card Component
;; ============================================================================

(defn- list-item-card
  "A single item in the list with drag-and-drop support."
  [{:keys [item label depth selected? dragging? drop-position renaming? parent-disabled?
           show-enable-toggle? menus]}]
  (let [item-id (:id item)
        enabled? (:enabled? item true)
        effectively-disabled? (or (not enabled?) parent-disabled?)

        item-classes (list-item-style-classes
                      {:depth depth
                       :selected? selected?
                       :dragging? dragging?
                       :drop-target? (some? drop-position)
                       :drop-position drop-position
                       :effectively-disabled? effectively-disabled?})
        name-classes (list-item-name-style-classes
                      {:selected? selected?
                       :effectively-disabled? effectively-disabled?})
        card-node {:fx/type fx/ext-on-instance-lifecycle
                   :on-created (fn [node]
                                 (let [address #(list-address node)]
                                   (dnd/setup-drag-source! node item-id address)
                                   (dnd/setup-drag-target! node item-id false address)
                                   (setup-click-handler! node item-id)))
                   :desc {:fx/type :h-box
                          :style-class item-classes
                          :children (filterv
                                     some?
                                     [(when show-enable-toggle?
                                        {:fx/type :check-box
                                         :selected enabled?
                                         :on-action (enable-action item-id)})

                                      (if renaming?
                                        {:fx/type rename-text-field
                                         :item-id item-id
                                         :current-name label
                                         :style-class "list-item-name-input"}
                                        {:fx/type :label
                                         :text (or label "Unknown")
                                         :style-class name-classes})])}}
        context-menu (some-> (:item menus) (call-spec item))]
    (if context-menu
      {:fx/type :label
       :content-display :graphic-only
       :padding 0
       :max-width Double/MAX_VALUE
       :style "-fx-background-color: transparent;"
       :context-menu context-menu
       :graphic card-node}
      card-node)))


;; Recursive List Item Renderer

(declare render-list-item)

(defn- row
  "The description of item's row (render-list-item): what the row shows,
   so a change to another row leaves it equal, keyed by the item's id, so
   an insert or a reorder keeps the rows' nodes. From the list's :labels,
   :ui (list-ui's, or the part of it for the rows around),
   :show-enable-toggle? and :menus, and the row's :depth and
   :parent-disabled?."
  [{:keys [labels ui show-enable-toggle? menus depth parent-disabled?]} item]
  (cond-> {:fx/type render-list-item
           :fx/key (:id item)
           :item item
           :depth depth
           :parent-disabled? parent-disabled?
           :label (row-text labels item)
           :ui (ui-within ui (row-ids item))
           :show-enable-toggle? show-enable-toggle?
           :menus menus}
    ;; A group's rows show its items' labels
    (chains/group? item) (assoc :labels labels)))

(defn- render-list-item
  "A row (row's props): an item's card, or a group's header with its
   items' rows under it unless it is collapsed, those disabled with it."
  [{:keys [item depth parent-disabled? label ui show-enable-toggle? menus labels]}]
  (let [item-id (:id item)
        {:keys [selected dragging drop renaming]} ui
        own {:depth depth
             :label label
             :selected? (contains? selected item-id)
             :dragging? (contains? dragging item-id)
             :drop-position (when (and drop (= item-id (first drop))) (second drop))
             :renaming? (and (some? renaming) (= item-id renaming))
             :parent-disabled? parent-disabled?
             :show-enable-toggle? show-enable-toggle?
             :menus menus}]
    (if (chains/group? item)
      (let [rows-props {:labels labels :ui ui :show-enable-toggle? show-enable-toggle? :menus menus
                        :depth (inc depth)
                        :parent-disabled? (or (not (:enabled? item true)) parent-disabled?)}]
        {:fx/type :v-box
         :spacing 2
         :children (into [(assoc own
                                 :fx/type group-header
                                 :fx/key [item-id :header]
                                 :group item)]
                         (when-not (:collapsed? item false)
                           (mapv #(row rows-props %) (:items item []))))})
      (assoc own
             :fx/type list-item-card
             :item item))))


;; Group Toolbar

(defn- group-toolbar
  "Toolbar with group-related buttons."
  [{:keys [selection-count can-create-group?]}]
  {:fx/type :h-box
   :spacing 4
   :children [{:fx/type :button
    :id "chain-new-group"
    :text "New"
               :graphic {:fx/type fa/icon :name :folder-open :size 8}
               :style-class "chain-toolbar-btn"
               :tooltip {:fx/type :tooltip :text "New empty group"}
               :on-action new-group-action}
              {:fx/type :button
               :id "chain-group-selection"
               :text "Group"
               :graphic {:fx/type fa/icon :name :folder-tree :size 8}
               :disable (or (zero? selection-count) (not can-create-group?))
               :style-class "chain-toolbar-btn"
               :tooltip {:fx/type :tooltip
                         :text (str "Group the selected items (" (shortcuts/shortcut-text :g) ")")}
               :on-action group-action}]})


;; Main Sidebar Component

(defn- setup-background-drop!
  "A drop on the list's background, its empty text or the space around its
   rows, puts the dragged items at the end of the list node is in then."
  [node]
  (dnd/setup-container-drop-target! node #(list-address node)))

(defn- list-sidebar
  "INTERNAL: Low-level list rendering component.
   External code should use list-editor instead, which wraps this
   with keyboard handling and proper state management. It reads the
   list's selection, drag and rename state, and gives each row its part
   of it (row).
   
   When :compact? is true, the header/toolbar chrome is hidden.
   When :scrollable? is false, the items VBox is rendered directly
   (parent manages scrolling)."
  [{:keys [fx/context items labels menus show-enable-toggle?
           header-label empty-text allow-groups? component-id
           compact? scrollable?]
    :or {header-label "LIST"
         empty-text "No items"
         allow-groups? true
         compact? false
         scrollable? true}}]
  (let [ui-state (fx/sub-ctx context subs/list-ui-state component-id)
        selection-count (count (:selected-ids ui-state))
        chain-depth (chains/nesting-depth items)
        can-create-group? (and allow-groups? (< chain-depth chains/max-nesting-depth))
        ;; The clipboard says, for this list's type (keyboard plan D13 B,
        ;; D9 A); a list without a type pastes nothing
        list-type (clipboard/list-type component-id)
        can-paste? (and (some? list-type) (fx/sub-ctx context subs/can-paste? list-type))
        rows-props {:labels labels
                    :ui (list-ui ui-state)
                    :show-enable-toggle? show-enable-toggle?
                    :menus menus
                    :depth 0
                    :parent-disabled? false}
        items-vbox {:fx/type :v-box
                    :spacing 4
                    :children (mapv #(row rows-props %) items)}
        ;; Emptying or filling the list puts a new node here: its drops too
        items-content {:fx/type fx/ext-on-instance-lifecycle
                       :v-box/vgrow :always
                       :on-created setup-background-drop!
                       :on-advanced (fn [_ node] (setup-background-drop! node))
                       :desc (cond
                               (empty? items) {:fx/type :label
                                               :text empty-text
                                               :style-class "chain-empty-text"}
                               scrollable? {:fx/type :scroll-pane
                                            :fit-to-width true
                                            :fit-to-height false
                                            :hbar-policy :as-needed
                                            :style-class "scroll-pane-base"
                                            :content items-vbox}
                               :else items-vbox)}]
    {:fx/type :v-box
     :style-class "chain-sidebar"
     :children
     (if compact?
       [items-content]
       [{:fx/type :h-box
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
         :text (str (shortcuts/shortcut-text "Click") " multi-select • Drag to reorder • "
                    (shortcuts/shortcut-text :g) " group")
         :style-class "chain-hint-text"}

        (when allow-groups?
          {:fx/type group-toolbar
           :selection-count selection-count
           :can-create-group? can-create-group?})

        {:fx/type :h-box
         :spacing 4
         :children [{:fx/type :button
                     :id "chain-copy-selection"
                     :text "Copy"
                     :graphic {:fx/type fa/icon :name :copy :style :regular :size 8}
                     :disable (zero? selection-count)
                     :style-class "chain-toolbar-btn"
                     :tooltip {:fx/type :tooltip
                               :text (str "Copy the selected items (" (shortcuts/shortcut-text :c) ")")}
                     :on-action copy-action}
                    {:fx/type :button
                     :id "chain-cut-selection"
                     :text "Cut"
                     :graphic {:fx/type fa/icon :name :scissors :size 8}
                     :disable (zero? selection-count)
                     :style-class "chain-toolbar-btn"
                     :tooltip {:fx/type :tooltip
                               :text (str "Cut the selected items (" (shortcuts/shortcut-text :x) ")")}
                     :on-action cut-action}
                    {:fx/type :button
                     :id "chain-paste-items"
                     :text "Paste"
                     :graphic {:fx/type fa/icon :name :clipboard :size 8}
                     :disable (not can-paste?)
                     :style-class "chain-toolbar-btn"
                     :tooltip {:fx/type :tooltip
                               :text (str "Paste after the selection (" (shortcuts/shortcut-text :v) ")")}
                     :on-action paste-action}
                    {:fx/type :button
                                :id "chain-delete-selection"
                                :text "Delete"
                     :graphic {:fx/type fa/icon :name :trash-can :size 8}
                     :disable (zero? selection-count)
                     :style-class "chain-toolbar-btn-danger"
                     :tooltip {:fx/type :tooltip :text "Delete the selected items (Delete)"}
                     :on-action delete-action}]}
        items-content])}))


;; Keyboard Handler Setup

(defn- setup-keyboard-handlers!
  "Handle the list's keys while the focus is anywhere inside it (on a
   toolbar button or a row's box too), except in its rename field
   (keys/list-key-target?). The list a key is for is the one node shows
   when it comes (list-address)."
  [^javafx.scene.Node node]
  (keys/claim-keys! node #{:delete :escape})
  (.addEventFilter
   node
   javafx.scene.input.KeyEvent/KEY_PRESSED
   (on-fx-event
    (fn [event]
      (when (keys/list-key-target? node event)
        (let [code (.getCode event)
              ctrl? (or (.isShortcutDown event) (.isControlDown event))
              {:keys [component-id items-path] :as address} (list-address node)
              handled? (cond
                         (nil? address) false

                         (and ctrl? (= code javafx.scene.input.KeyCode/C))
                         (do (dispatch-copy-selected! address) true)

                         (and ctrl? (= code javafx.scene.input.KeyCode/V))
                         (do (dispatch-paste-items! address) true)

                         (and ctrl? (= code javafx.scene.input.KeyCode/X))
                         (do (dispatch-cut-selected! address) true)

                         (and ctrl? (= code javafx.scene.input.KeyCode/A))
                         (do (select-all! component-id items-path) true)

                         (and ctrl? (= code javafx.scene.input.KeyCode/G))
                         (do (dispatch-group-selected! address) true)

                         (= code javafx.scene.input.KeyCode/DELETE)
                         (do (dispatch-delete-selected! address) true)

                         (= code javafx.scene.input.KeyCode/ESCAPE)
                         (do (clear-selection! component-id) true)

                         :else false)]
          (when handled?
            (.consume event))))))))


;; All-in-One Wrapper Component

(defn list-editor
  "Complete hierarchical list editor with keyboard handling and event dispatch.
   This is the recommended component for most use cases.
   
   Selection state is managed internally in [:list-ui component-id] and can be
   read via subs/list-ui-state subscription.
   
   Props:
   - :items - Vector of items (required)
   - :component-id - Unique ID for state isolation (required)
   
   Label configuration (one of):
   - :get-item-label - Custom function item -> string
   - :item-id-key + :item-registry-fn - For registry lookup (always uses :name)
   
   Each label and menu function here (:get-item-label, :get-group-label,
   :item-registry-fn, :get-context-menu, :get-group-context-menu) is a
   top-level function, or [f & args], called (f args... item) (the
   registry's with the item's :item-id-key value): plain data with it, never
   a closure made in the caller's render, so the list's description stays
   equal while its inputs do and its rows don't render again.
   
   Event dispatch:
   - :on-change-event - Event type for items changed
   - :on-change-params - Base params for change event
   - :items-path - Direct path to items in state, or for an item's effects
     its owner, {:domain :entity-key :owner-id}: the item by its id, which
     the handlers find where it is when an input is handled
     (handlers.list/with-items-path)
   
   UI customization:
   - :header-label - Header text (default 'LIST')
   - :empty-text - Empty state text
   - :allow-groups? - Enable grouping (default true)
   - :fallback-label - Label for unknown items (default \"Unknown\")
   - :compact? - If true, hide toolbar/header chrome (default false)
   - :scrollable? - If true, wrap items in ScrollPane (default true)
   - :show-enable-toggle? - If false, rows have no enable checkbox (default true)
   - :get-context-menu - item -> its context menu's description, or nil
   - :get-group-context-menu - The same for a group (default :get-context-menu)"
  [{:keys [items component-id
           get-item-label get-group-label
           get-context-menu get-group-context-menu
           item-id-key item-registry-fn
           on-change-event on-change-params items-path
           header-label empty-text allow-groups? fallback-label
           compact? scrollable? show-enable-toggle?]
    :or {header-label "LIST"
         empty-text "No items"
         allow-groups? true
         fallback-label "Unknown"
         compact? false
         scrollable? true
         show-enable-toggle? true}}]
  {:fx/type fx/ext-on-instance-lifecycle
   ;; The keys, clicks and drops installed once read the list's command
   ;; address from the node's :user-data when they come (list-address)
   :on-created (fn [^javafx.scene.Node node]
                 (setup-keyboard-handlers! node)
                 ;; Focus comes from a click on a row (request-list-focus!),
                 ;; never on creation: the Cue Chain Editor has two lists
                 (.setFocusTraversable node true))
   :desc {:fx/type :v-box
          :id (str "list-editor-"
                   (str/join "-"
                             (map #(if (keyword? %) (name %) (str %))
                                  (if (sequential? component-id)
                                    component-id
                                    [component-id]))))
          :user-data {::address (u/->map component-id items-path on-change-event on-change-params)}
          :children [(u/->map&
                      items header-label empty-text allow-groups? component-id
                      compact? scrollable? show-enable-toggle?
                      :fx/type list-sidebar
                      :v-box/vgrow :always
                      :labels (u/->map get-item-label get-group-label item-id-key item-registry-fn fallback-label)
                      :menus {:item get-context-menu
                              :group (or get-group-context-menu get-context-menu)})]}})
