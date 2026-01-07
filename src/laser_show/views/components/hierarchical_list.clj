(ns laser-show.views.components.hierarchical-list
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
   
   New callback-based API (replaces dispatcher protocol):
   - :on-items-changed - Called with new items vector when items are reordered/modified
   - :on-selection-changed - Called with {:selected-ids #{...} :last-selected-id uuid}
   - :on-copy - Called with copied items for parent to store in clipboard
   
   Helper factories for common patterns:
   - make-dispatch-callback - Creates :on-items-changed that dispatches an event
   - make-selection-callback - Creates :on-selection-changed that dispatches an event
   - make-registry-label - Creates :get-item-label that looks up from a registry
   - make-case-label - Creates :get-item-label from a keyword->string map
   
   Wrapper component for even simpler usage:
   - hierarchical-list-editor - All-in-one wrapper with keyboard handling
   
   Styling is defined in laser-show.css.hierarchical-list."
  (:require [cljfx.api :as fx]
            [laser-show.animation.chains :as chains]
            [laser-show.events.core :as events])
  (:import [javafx.scene.input TransferMode ClipboardContent]))


;; ============================================================================
;; Internal Component State Management
;; ============================================================================


;; Atom holding state for all active component instances.
;; Map of component-id -> {:selected-ids #{} :dragging-ids #{} ...}
(defonce ^:private component-states (atom {}))

(defn- get-state
  "Get component state for the given component ID."
  [component-id]
  (get @component-states component-id
       {:selected-ids #{}
        :last-selected-id nil
        :dragging-ids nil
        :drop-target-id nil
        :drop-position nil
        :renaming-id nil
        :items nil         ;; Current items (updated on each render)
        :props nil}))      ;; Current props (updated on each render)

(defn- sync-items-and-props!
  "Sync items and props into component state for use by event handlers.
   This must be called on each render to keep the state fresh."
  [component-id items props]
  (swap! component-states update component-id
         (fn [state]
           (-> (or state {})
               (assoc :items items)
               (assoc :props props)))))

(defn- update-state!
  "Update component state, optionally calling callback on change."
  ([component-id f]
   (swap! component-states update component-id
          (fn [state] (f (or state {})))))
  ([component-id f callback-key props]
   (let [old-state (get @component-states component-id)
         new-state (swap! component-states update component-id
                         (fn [state] (f (or state {}))))]
     ;; Call callback if selection changed and callback exists
     (when (and callback-key
                (get props callback-key)
                (not= (:selected-ids old-state) (:selected-ids (get new-state component-id))))
       ((get props callback-key)
        {:selected-ids (:selected-ids (get new-state component-id) #{})
         :last-selected-id (:last-selected-id (get new-state component-id))})))))

(defn- cleanup-state!
  "Remove component state on unmount."
  [component-id]
  (swap! component-states dissoc component-id))




;; ============================================================================
;; Selection Logic (Internal)
;; ============================================================================


(defn- handle-selection!
  "Handle selection based on click modifiers.
   Updates internal state and calls :on-selection-changed callback.
   
   IMPORTANT: Reads items and props from component-state to avoid stale closure
   issues. The items/props are synced on each render via sync-items-and-props!"
  [component-id item-id ctrl? shift?]
  (let [state (get-state component-id)
        items (:items state)
        props (:props state)
        selected-ids (:selected-ids state #{})
        last-selected-id (:last-selected-id state)]
    ;; Debug logging
    (println "[handle-selection!] component-id=" component-id "item-id=" item-id
             "ctrl?=" ctrl? "shift?=" shift?)
    (println "  items count:" (count items) "selected-ids:" selected-ids
             "last-selected-id:" last-selected-id)
    (when (and items props)
      (let [new-state (cond
                        ;; Ctrl+Click - toggle selection
                        ctrl?
                        {:selected-ids (if (contains? selected-ids item-id)
                                         (disj selected-ids item-id)
                                         (conj selected-ids item-id))
                         :last-selected-id item-id}
                        
                        ;; Shift+Click - range select
                        shift?
                        (let [all-ids (chains/collect-all-ids items)
                              _ (println "  all-ids for shift-select:" all-ids)
                              anchor-id (or last-selected-id item-id)
                              anchor-idx (.indexOf all-ids anchor-id)
                              target-idx (.indexOf all-ids item-id)
                              start (min anchor-idx target-idx)
                              end (max anchor-idx target-idx)]
                          (println "  anchor-id:" anchor-id "anchor-idx:" anchor-idx
                                   "target-idx:" target-idx "start:" start "end:" end)
                          (if (and (>= anchor-idx 0) (>= target-idx 0))
                            {:selected-ids (set (subvec all-ids start (inc end)))
                             :last-selected-id last-selected-id}  ;; Keep anchor for shift
                            {:selected-ids #{item-id}
                             :last-selected-id item-id}))
                        
                        ;; Plain click - single select
                        :else
                        {:selected-ids #{item-id}
                         :last-selected-id item-id})]
        (println "  new-state:" new-state)
        ;; Update internal state
        (update-state! component-id #(merge % new-state))
        ;; Notify parent
        (when-let [callback (:on-selection-changed props)]
          (callback new-state))))))

(defn- select-all!
  "Select all items in the chain."
  [component-id items props]
  (let [all-ids (chains/collect-all-ids-set items)
        new-state {:selected-ids all-ids
                   :last-selected-id (first all-ids)}]
    (update-state! component-id #(merge % new-state))
    (when-let [callback (:on-selection-changed props)]
      (callback new-state))))

(defn- clear-selection!
  "Clear selection and cancel rename."
  [component-id props]
  (let [new-state {:selected-ids #{}
                   :last-selected-id nil
                   :renaming-id nil}]
    (update-state! component-id #(merge % new-state))
    (when-let [callback (:on-selection-changed props)]
      (callback new-state))))


;; ============================================================================
;; Drag-and-Drop Logic (Internal)
;; ============================================================================


(defn- start-drag!
  "Start a drag operation. If dragged item is selected, drag all selected items.
   Otherwise, select only the dragged item and drag it."
  [component-id item-id]
  (let [state (get-state component-id)
        selected-ids (:selected-ids state #{})
        dragging-ids (if (contains? selected-ids item-id)
                       selected-ids
                       #{item-id})
        new-selected (if (contains? selected-ids item-id)
                       selected-ids
                       #{item-id})]
    (update-state! component-id
                   #(assoc %
                           :dragging-ids dragging-ids
                           :selected-ids new-selected
                           :last-selected-id item-id))))

(defn- update-drop-target!
  "Update drop target during drag over."
  [component-id target-id drop-position]
  (update-state! component-id
                 #(assoc %
                         :drop-target-id target-id
                         :drop-position drop-position)))

(defn- handle-drop!
  "Handle drop operation. Moves dragged items to target location
   and calls :on-items-changed callback.
   
   IMPORTANT: This function reads items and props from component-state to avoid
   stale closure issues. The items/props are synced on each render via
   sync-items-and-props!"
  [component-id target-id drop-position]
  (let [state (get-state component-id)
        dragging-ids (:dragging-ids state)
        items (:items state)
        props (:props state)]
    (when (and (seq dragging-ids) (seq items))
      ;; Convert IDs to paths (fresh lookup from current items)
      (let [id->path (chains/find-paths-by-ids items dragging-ids)
            from-paths (set (vals id->path))]
        (when (seq from-paths)
          ;; Use centralized move logic from chains.clj
          (let [new-items (chains/move-items-to-target items from-paths target-id drop-position)]
            ;; Notify parent with new items
            (when-let [callback (:on-items-changed props)]
              (callback new-items))))))))

(defn- clear-drag-state!
  "Clear all drag-related state after drop or cancel."
  [component-id]
  (update-state! component-id
                 #(assoc %
                         :dragging-ids nil
                         :drop-target-id nil
                         :drop-position nil)))


;; ============================================================================
;; Item Operations (Internal)
;; ============================================================================


(defn- delete-selected!
  "Delete selected items and call :on-items-changed."
  [component-id items props]
  (let [state (get-state component-id)
        selected-ids (:selected-ids state #{})]
    (when (seq selected-ids)
      (let [id->path (chains/find-paths-by-ids items selected-ids)
            paths-to-delete (vals id->path)
            new-items (chains/delete-paths-safely items paths-to-delete)]
        ;; Clear selection
        (update-state! component-id #(assoc % :selected-ids #{} :last-selected-id nil))
        ;; Notify parent
        (when-let [callback (:on-items-changed props)]
          (callback new-items))
        (when-let [callback (:on-selection-changed props)]
          (callback {:selected-ids #{} :last-selected-id nil}))))))

(defn- copy-selected!
  "Copy selected items and call :on-copy callback."
  [component-id items props]
  (let [state (get-state component-id)
        selected-ids (:selected-ids state #{})]
    (when (seq selected-ids)
      (let [id->path (chains/find-paths-by-ids items selected-ids)
            selected-items (mapv #(chains/get-item-at-path items %) (vals id->path))
            copied-items (chains/deep-copy-items selected-items)]
        (when-let [callback (:on-copy props)]
          (callback copied-items))))))

(defn- paste-items!
  "Paste items from clipboard after selected item (or at end)."
  [component-id items props]
  (when-let [clipboard-items (:clipboard-items props)]
    (when (seq clipboard-items)
      (let [state (get-state component-id)
            selected-ids (:selected-ids state #{})
            ;; Fresh copy with new IDs
            items-to-paste (chains/deep-copy-items clipboard-items)
            ;; Find insert position
            insert-idx (if-let [last-id (:last-selected-id state)]
                         (if-let [path (chains/find-path-by-id items last-id)]
                           (inc (first path))
                           (count items))
                         (count items))
            ;; Insert items
            new-items (reduce-kv
                        (fn [chain idx item]
                          (chains/insert-at-path chain [(+ insert-idx idx)] item))
                        items
                        (vec items-to-paste))
            ;; Select pasted items
            pasted-ids (set (map :id items-to-paste))]
        ;; Update selection to pasted items
        (update-state! component-id #(assoc %
                                            :selected-ids pasted-ids
                                            :last-selected-id (:id (last items-to-paste))))
        ;; Notify parent
        (when-let [callback (:on-items-changed props)]
          (callback new-items))
        (when-let [callback (:on-selection-changed props)]
          (callback {:selected-ids pasted-ids
                     :last-selected-id (:id (last items-to-paste))}))))))


;; ============================================================================
;; Group Operations (Internal)
;; ============================================================================


(defn- create-empty-group!
  "Create an empty group at the end of the chain."
  [component-id items props]
  (let [new-group (chains/create-group [])
        new-items (conj items new-group)
        group-id (:id new-group)]
    ;; Select the new group
    (update-state! component-id #(assoc %
                                        :selected-ids #{group-id}
                                        :last-selected-id group-id))
    ;; Notify parent
    (when-let [callback (:on-items-changed props)]
      (callback new-items))
    (when-let [callback (:on-selection-changed props)]
      (callback {:selected-ids #{group-id} :last-selected-id group-id}))))

(defn- group-selected!
  "Group selected items into a new folder."
  [component-id items props]
  (let [state (get-state component-id)
        selected-ids (:selected-ids state #{})]
    (when (seq selected-ids)
      (let [id->path (chains/find-paths-by-ids items selected-ids)
            ;; Only group root-level items (not items inside other selections)
            root-paths (filter #(= 1 (count %)) (vals id->path))]
        (when (seq root-paths)
          (let [;; Get items in document order
                sorted-paths (sort (fn [a b] (compare (first a) (first b))) root-paths)
                items-to-group (mapv #(chains/get-item-at-path items %) sorted-paths)
                ;; Create group
                new-group (chains/create-group items-to-group)
                ;; Remove old items (from highest index first)
                after-remove (chains/delete-paths-safely items sorted-paths)
                ;; Insert group at first item's position
                insert-idx (first (first sorted-paths))
                new-items (chains/insert-at-path after-remove [insert-idx] new-group)
                group-id (:id new-group)]
            ;; Select the new group
            (update-state! component-id #(assoc %
                                                :selected-ids #{group-id}
                                                :last-selected-id group-id))
            ;; Notify parent
            (when-let [callback (:on-items-changed props)]
              (callback new-items))
            (when-let [callback (:on-selection-changed props)]
              (callback {:selected-ids #{group-id} :last-selected-id group-id}))))))))

(defn- ungroup!
  "Ungroup a folder, splicing its contents into the parent."
  [component-id items props group-id]
  (when-let [path (chains/find-path-by-id items group-id)]
    (let [group (chains/get-item-at-path items path)]
      (when (chains/group? group)
        (let [new-items (chains/ungroup items path)]
          ;; Clear selection
          (update-state! component-id #(assoc % :selected-ids #{} :last-selected-id nil))
          ;; Notify parent
          (when-let [callback (:on-items-changed props)]
            (callback new-items))
          (when-let [callback (:on-selection-changed props)]
            (callback {:selected-ids #{} :last-selected-id nil})))))))

(defn- toggle-collapse!
  "Toggle collapse/expand state of a group."
  [component-id items props group-id]
  (when-let [path (chains/find-path-by-id items group-id)]
    (let [new-items (chains/update-at-path items path
                                           #(update % :collapsed? not))]
      (when-let [callback (:on-items-changed props)]
        (callback new-items)))))


;; ============================================================================
;; Rename Operations (Internal)
;; ============================================================================


(defn- start-rename!
  "Enter rename mode for an item."
  [component-id item-id]
  (update-state! component-id #(assoc % :renaming-id item-id)))

(defn- cancel-rename!
  "Cancel rename mode."
  [component-id]
  (update-state! component-id #(assoc % :renaming-id nil)))

(defn- commit-rename!
  "Commit rename and update item name."
  [component-id items props item-id new-name]
  (when-let [path (chains/find-path-by-id items item-id)]
    (let [new-items (chains/update-at-path items path #(assoc % :name new-name))]
      ;; Exit rename mode
      (update-state! component-id #(assoc % :renaming-id nil))
      ;; Notify parent
      (when-let [callback (:on-items-changed props)]
        (callback new-items)))))


;; ============================================================================
;; Enable/Disable Operations (Internal)
;; ============================================================================


(defn- set-enabled!
  "Set enabled/disabled state for an item."
  [component-id items props item-id enabled?]
  (when-let [path (chains/find-path-by-id items item-id)]
    (let [new-items (chains/update-at-path items path #(assoc % :enabled? enabled?))]
      (when-let [callback (:on-items-changed props)]
        (callback new-items)))))


;; ============================================================================
;; Helper Functions
;; ============================================================================


(defn- depth-class
  "Returns the depth capped at 3 (max nesting level for CSS classes)."
  [depth]
  (min depth 3))


;; ============================================================================
;; Style Class Builders
;; ============================================================================


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


;; ============================================================================
;; Drag-and-Drop Handlers Setup
;; ============================================================================

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
    ;; First clear any existing drop classes
    (.removeAll style-class ["chain-item-drop-before" "chain-item-drop-after"
                             "group-header-drop-before" "group-header-drop-into"])
    ;; Then add the appropriate one
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
          ;; Store the item ID in dragboard
          (.putString content (pr-str item-id))
          (.setContent dragboard content)
          ;; Update internal drag state
          (start-drag! component-id item-id)
          ;; Add dragging class to source
          (.add (.getStyleClass node) "chain-item-dragging")
          (.consume event)))))
  (.setOnDragDone
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        ;; Clear dragging class from source
        (.remove (.getStyleClass node) "chain-item-dragging")
        ;; Clear any lingering drop target
        (when-let [old-target @current-drop-target-node]
          (clear-drop-indicator-classes! old-target))
        (reset! current-drop-target-node nil)
        (clear-drag-state! component-id)
        (.consume event)))))

(defn- setup-drag-target!
  "Setup drag target handlers on a node.
   For groups: top 25% = :before, rest = :into
   For items: top 50% = :before, bottom 50% = :after
   
   Uses imperative CSS class manipulation to show visual feedback immediately
   without waiting for cljfx re-render."
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
                            ;; For groups: top 25% = before, rest = into
                            (if (< y (* height 0.25)) :before :into)
                            ;; For items: top 50% = before, bottom 50% = after
                            (if (< y (* height 0.5)) :before :after))]
              (when (not= @drop-position-atom new-pos)
                (reset! drop-position-atom new-pos)
                ;; Update visual indicator immediately
                (set-drop-indicator-class! node new-pos is-group?)
                ;; Update internal state for drop handling
                (update-drop-target! component-id target-id new-pos))))
          (.consume event))))
    (.setOnDragEntered
      node
      (reify javafx.event.EventHandler
        (handle [_ event]
          (when (.hasString (.getDragboard event))
            ;; Clear previous target's indicator
            (when-let [old-target @current-drop-target-node]
              (when (not= old-target node)
                (clear-drop-indicator-classes! old-target)))
            ;; Set this node as current target
            (reset! current-drop-target-node node)
            (let [initial-pos (if is-group? :into :before)]
              (reset! drop-position-atom initial-pos)
              ;; Show visual indicator immediately
              (set-drop-indicator-class! node initial-pos is-group?)
              ;; Update internal state
              (update-drop-target! component-id target-id initial-pos)))
          (.consume event))))
    (.setOnDragExited
      node
      (reify javafx.event.EventHandler
        (handle [_ event]
          ;; Clear visual indicator when leaving this node
          (clear-drop-indicator-classes! node)
          (.consume event))))
    (.setOnDragDropped
      node
      (reify javafx.event.EventHandler
        (handle [_ event]
          (let [drop-pos @drop-position-atom]
            ;; Clear visual indicator
            (clear-drop-indicator-classes! node)
            (reset! current-drop-target-node nil)
            ;; Perform the drop
            (handle-drop! component-id target-id drop-pos)
            (clear-drag-state! component-id)
            (.setDropCompleted event true)
            (.consume event)))))))


;; ============================================================================
;; Drop Indicator Line Component
;; ============================================================================


(defn- drop-indicator-line
  "Visual horizontal line showing where item will be inserted during drag-and-drop."
  [_]
  {:fx/type :h-box
   :style-class "drop-indicator-line"
   :alignment :center-left
   :children [{:fx/type :label
               :text "▶"
               :style-class "drop-indicator-arrow"}
              {:fx/type :region
               :style-class "drop-indicator-bar"
               :h-box/hgrow :always}]})


;; ============================================================================
;; Group Header Component
;; ============================================================================


(defn- group-header
  "Header for a group showing collapse toggle, name, and item count.
   Shows visual drop indicator line when dropping before this group."
  [{:keys [component-id items props group depth selected? dragging? drop-target-id drop-position renaming? parent-disabled?]}]
  (let [group-id (:id group)
        enabled? (:enabled? group true)
        effectively-disabled? (or (not enabled?) parent-disabled?)
        collapsed? (:collapsed? group false)
        item-count (chains/count-items-recursive (:items group []))
        is-drop-target? (= group-id drop-target-id)
        drop-before? (and is-drop-target? (= drop-position :before))
        drop-into? (and is-drop-target? (= drop-position :into))
        show-before-indicator? drop-before?
        
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
                       :effectively-disabled? effectively-disabled?})
        
        ;; The actual header card content
        header-card {:fx/type fx/ext-on-instance-lifecycle
                     :on-created (fn [node]
                                   (setup-drag-source! node group-id component-id)
                                   (setup-drag-target! node group-id true component-id items props)
                                   ;; Click handler for selection and double-click rename
                                   (.setOnMouseClicked node
                                     (reify javafx.event.EventHandler
                                       (handle [_ event]
                                         (let [click-count (.getClickCount event)
                                               ctrl? (.isShortcutDown event)
                                               shift? (.isShiftDown event)]
                                           (cond
                                             ;; Double-click - start rename
                                             (= click-count 2)
                                             (do
                                               (start-rename! component-id group-id)
                                               (.consume event))
                                             ;; Single-click - select
                                             :else
                                             (do
                                               (handle-selection! component-id group-id ctrl? shift?)
                                               (.consume event))))))))
                     :desc {:fx/type :h-box
                            :style-class header-classes
                            :children [;; Collapse/Expand chevron
                                       {:fx/type :button
                                        :text (if collapsed? "▶" "▼")
                                        :style-class "group-collapse-btn"
                                        :on-action (fn [_] (toggle-collapse! component-id items props group-id))}
                                       
                                       ;; Enable/disable checkbox
                                       {:fx/type :check-box
                                        :selected enabled?
                                        :on-selected-changed (fn [enabled?]
                                                               (set-enabled! component-id items props group-id enabled?))}
                                       
                                       ;; Group name (editable when renaming)
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
                                                                (commit-rename! component-id items props group-id new-name)))
                                                 :on-key-pressed (fn [^javafx.scene.input.KeyEvent e]
                                                                   (case (.getCode e)
                                                                     javafx.scene.input.KeyCode/ESCAPE
                                                                     (cancel-rename! component-id)
                                                                     nil))
                                                 :on-focused-changed (fn [focused?]
                                                                       (when-not focused?
                                                                         (cancel-rename! component-id)))}}
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
                                        :on-action (fn [_] (ungroup! component-id items props group-id))}]}}]
    ;; Wrap in v-box to show drop indicator line when dropping before
    (if show-before-indicator?
      {:fx/type :v-box
       :spacing 0
       :children [{:fx/type drop-indicator-line}
                  header-card]}
      ;; No indicator needed - just return the header card
      header-card)))


;; ============================================================================
;; List Item Card Component
;; ============================================================================


(defn- list-item-card
  "A single item in the list with drag-and-drop support.
   Shows visual drop indicator lines when this item is a drop target."
  [{:keys [component-id items props item item-label depth selected? dragging? drop-target-id drop-position parent-disabled?]}]
  (let [item-id (:id item)
        enabled? (:enabled? item true)
        effectively-disabled? (or (not enabled?) parent-disabled?)
        is-drop-target? (= item-id drop-target-id)
        show-before-indicator? (and is-drop-target? (= drop-position :before))
        show-after-indicator? (and is-drop-target? (= drop-position :after))
        
        item-classes (list-item-style-classes
                      {:depth depth
                       :selected? selected?
                       :dragging? dragging?
                       :drop-target? is-drop-target?
                       :drop-position drop-position
                       :effectively-disabled? effectively-disabled?})
        name-classes (list-item-name-style-classes
                      {:selected? selected?
                       :effectively-disabled? effectively-disabled?})
        
        ;; The actual item card content
        item-card {:fx/type fx/ext-on-instance-lifecycle
                   :on-created (fn [node]
                                 (setup-drag-source! node item-id component-id)
                                 (setup-drag-target! node item-id false component-id items props)
                                 ;; Click handler for selection
                                 (.setOnMouseClicked node
                                   (reify javafx.event.EventHandler
                                     (handle [_ event]
                                       (let [ctrl? (.isShortcutDown event)
                                             shift? (.isShiftDown event)]
                                         (handle-selection! component-id item-id ctrl? shift?)
                                         (.consume event))))))
                   :desc {:fx/type :h-box
                          :style-class item-classes
                          :children [;; Enable/disable checkbox
                                     {:fx/type :check-box
                                      :selected enabled?
                                      :on-selected-changed (fn [enabled?]
                                                             (set-enabled! component-id items props item-id enabled?))}
                                     
                                     ;; Item name
                                     {:fx/type :label
                                      :text (or item-label "Unknown")
                                      :style-class name-classes}]}}]
    ;; Wrap in v-box to show drop indicator lines
    (if (or show-before-indicator? show-after-indicator?)
      {:fx/type :v-box
       :spacing 0
       :children (filterv some?
                   [(when show-before-indicator?
                      {:fx/type drop-indicator-line})
                    item-card
                    (when show-after-indicator?
                      {:fx/type drop-indicator-line})])}
      ;; No indicators needed - just return the item card
      item-card)))


;; ============================================================================
;; Recursive List Item Renderer
;; ============================================================================


(defn- render-list-item
  "Recursively render a list item (leaf item or group)."
  [{:keys [component-id items props item depth selected-ids dragging-ids
           drop-target-id drop-position renaming-id parent-disabled? get-item-label]}]
  (let [item-id (:id item)
        selected? (contains? (or selected-ids #{}) item-id)
        dragging? (contains? (or dragging-ids #{}) item-id)]
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
      ;; Render leaf item
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
       :parent-disabled? parent-disabled?})))


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


;; ============================================================================
;; Main Sidebar Component
;; ============================================================================


(defn hierarchical-list-sidebar
  "Generic hierarchical list sidebar component with self-contained state.
   
   Props:
   - :items - Vector of items (required)
   - :get-item-label - Function item -> string (required)
   - :on-items-changed - Callback with new items vector (required)
   - :on-selection-changed - Callback with selection map (optional)
   - :on-copy - Callback with copied items (optional)
   - :clipboard-items - Items available to paste (optional)
   - :header-label - Header text (default 'LIST')
   - :empty-text - Empty state text (optional)
   - :allow-groups? - Enable grouping (default true)
   - :component-id - Unique ID for state isolation (auto-generated if not provided)"
  [{:keys [items get-item-label on-items-changed on-selection-changed
           on-copy clipboard-items header-label empty-text allow-groups?
           component-id]
    :or {header-label "LIST"
         empty-text "No items"
         allow-groups? true}
    :as props}]
  ;; Generate component ID on first render if not provided
  (let [comp-id (or component-id (hash [items header-label]))
        ;; Sync items and props into component state for handlers to access fresh data
        _ (sync-items-and-props! comp-id items props)
        state (get-state comp-id)
        selected-ids (:selected-ids state #{})
        dragging-ids (:dragging-ids state)
        drop-target-id (:drop-target-id state)
        drop-position (:drop-position state)
        renaming-id (:renaming-id state)
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
                
                ;; Group toolbar
                (when allow-groups?
                  {:fx/type group-toolbar
                   :component-id comp-id
                   :items items
                   :props props
                   :selection-count selection-count
                   :can-create-group? can-create-group?})
                
                ;; Action buttons for copy/paste
                {:fx/type :h-box
                 :spacing 4
                 :children [{:fx/type :button
                             :text "Copy"
                             :disable (zero? selection-count)
                             :style-class "chain-toolbar-btn"
                             :on-action (fn [_] (copy-selected! comp-id items props))}
                            {:fx/type :button
                             :text "Paste"
                             :disable (not can-paste?)
                             :style-class "chain-toolbar-btn"
                             :on-action (fn [_] (paste-items! comp-id items props))}
                            {:fx/type :button
                             :text "Del"
                             :disable (zero? selection-count)
                             :style-class "chain-toolbar-btn-danger"
                             :on-action (fn [_] (delete-selected! comp-id items props))}]}
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
                             :children (vec
                                        (map-indexed
                                          (fn [idx item]
                                            {:fx/type render-list-item
                                             :fx/key [(:id item) idx]
                                             :component-id comp-id
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
                                          items))}})]}))


;; ============================================================================
;; Keyboard Handler
;; ============================================================================


(defn create-keyboard-handler
  "Creates a keyboard event handler function for the hierarchical list.
   
   This handler supports:
   - Ctrl+C: Copy selected items
   - Ctrl+X: Cut selected items (copy + delete)
   - Ctrl+V: Paste items
   - Ctrl+A: Select all items
   - Ctrl+G: Group selected items
   - Delete/Backspace: Delete selected items
   - Escape: Clear selection / Cancel rename
   - F2: Rename selected item (groups only)
   
   Returns a function that takes component-id, items, and props."
  [component-id items props]
  (fn [^javafx.scene.input.KeyEvent event]
    (let [code (.getCode event)
          ctrl? (.isShortcutDown event)]
      (cond
        ;; Ctrl+C - Copy
        (and ctrl? (= code javafx.scene.input.KeyCode/C))
        (do (copy-selected! component-id items props)
            (.consume event))
        
        ;; Ctrl+X - Cut (copy then delete)
        (and ctrl? (= code javafx.scene.input.KeyCode/X))
        (do (copy-selected! component-id items props)
            (delete-selected! component-id items props)
            (.consume event))
        
        ;; Ctrl+V - Paste
        (and ctrl? (= code javafx.scene.input.KeyCode/V))
        (do (paste-items! component-id items props)
            (.consume event))
        
        ;; Ctrl+A - Select all
        (and ctrl? (= code javafx.scene.input.KeyCode/A))
        (do (select-all! component-id items props)
            (.consume event))
        
        ;; Ctrl+G - Group selected
        (and ctrl? (= code javafx.scene.input.KeyCode/G))
        (do (group-selected! component-id items props)
            (.consume event))
        
        ;; Delete key - Delete selected (not backspace)
        (= code javafx.scene.input.KeyCode/DELETE)
        (do (delete-selected! component-id items props)
            (.consume event))
        
        ;; F2 - Rename first selected group
        (= code javafx.scene.input.KeyCode/F2)
        (let [state (get-state component-id)
              first-id (first (:selected-ids state))]
          (when first-id
            (when-let [path (chains/find-path-by-id items first-id)]
              (when (chains/group? (chains/get-item-at-path items path))
                (start-rename! component-id first-id))))
          (.consume event))
        
        ;; Escape - Clear selection / Cancel rename
        (= code javafx.scene.input.KeyCode/ESCAPE)
        (do (clear-selection! component-id props)
            (.consume event))))))

(defn setup-keyboard-handlers!
  "Setup keyboard handlers on a JavaFX node for hierarchical list editing.
   
   This sets up both an event filter (for Ctrl+C/V/X which need to intercept
   system clipboard handling) and a regular key handler (for other shortcuts).
   
   Args:
   - node: The JavaFX node to attach handlers to
   - component-id: Component state ID
   - items: Current items vector
   - props: Props map with callbacks"
  [^javafx.scene.Node node component-id items props]
  (let [handler-fn (create-keyboard-handler component-id items props)]
    ;; Use event filter for Ctrl+C, Ctrl+V, Ctrl+X to intercept system clipboard
    (.addEventFilter
      node
      javafx.scene.input.KeyEvent/KEY_PRESSED
      (reify javafx.event.EventHandler
        (handle [_ event]
          (let [code (.getCode event)
                ctrl? (.isShortcutDown event)]
            (when (and ctrl? (or (= code javafx.scene.input.KeyCode/C)
                                  (= code javafx.scene.input.KeyCode/V)
                                  (= code javafx.scene.input.KeyCode/X)))
              (handler-fn event))))))
    
    ;; Use regular key handler for other shortcuts
    (.setOnKeyPressed
      node
      (reify javafx.event.EventHandler
        (handle [_ event]
          (handler-fn event))))))


;; ============================================================================
;; Public API for External Keyboard Handlers
;; ============================================================================
;; These functions allow external code (like dialog keyboard handlers) to trigger
;; the same operations as the internal buttons, ensuring consistent behavior.


(defn get-component-state
  "Get the current state for a component. Returns map with :items, :props, :selected-ids etc."
  [component-id]
  (get-state component-id))

(defn copy-selected-from-component!
  "Copy selected items from a component (for external keyboard handlers).
   Same behavior as the Copy button."
  [component-id]
  (let [state (get-state component-id)
        items (:items state)
        props (:props state)]
    (when (and items props)
      (copy-selected! component-id items props))))

(defn paste-items-from-component!
  "Paste items into a component (for external keyboard handlers).
   Same behavior as the Paste button."
  [component-id]
  (let [state (get-state component-id)
        items (:items state)
        props (:props state)]
    (when (and items props)
      (paste-items! component-id items props))))

(defn delete-selected-from-component!
  "Delete selected items from a component (for external keyboard handlers).
   Same behavior as the Del button."
  [component-id]
  (let [state (get-state component-id)
        items (:items state)
        props (:props state)]
    (when (and items props)
      (delete-selected! component-id items props))))

(defn select-all-from-component!
  "Select all items in a component (for external keyboard handlers)."
  [component-id]
  (let [state (get-state component-id)
        items (:items state)
        props (:props state)]
    (when (and items props)
      (select-all! component-id items props))))

(defn group-selected-from-component!
  "Group selected items in a component (for external keyboard handlers).
   This calls the same internal function as the toolbar Group button."
  [component-id]
  (let [state (get-state component-id)
        items (:items state)
        props (:props state)]
    (when (and items props)
      (group-selected! component-id items props))))

(defn clear-selection-from-component!
  "Clear selection in a component (for external keyboard handlers)."
  [component-id]
  (let [state (get-state component-id)
        props (:props state)]
    (when props
      (clear-selection! component-id props))))


;; ============================================================================
;; Callback Factory Functions
;; ============================================================================
;; These helpers reduce boilerplate when configuring hierarchical list components.


(defn make-dispatch-callback
  "Create an on-items-changed callback that dispatches an event.
   
   Usage:
   :on-items-changed (make-dispatch-callback :effects/set-chain {:col col :row row} :items)
   
   This replaces the verbose:
   :on-items-changed (fn [new-items]
                       (events/dispatch! {:event/type :effects/set-chain
                                          :col col :row row
                                          :items new-items}))"
  [event-type base-params items-key]
  (fn [new-items]
    (events/dispatch! (assoc base-params
                             :event/type event-type
                             items-key new-items))))

(defn make-selection-callback
  "Create an on-selection-changed callback that dispatches an event.
   
   Usage:
   :on-selection-changed (make-selection-callback :ui/update-dialog-data
                                                   {:dialog-id :effect-chain-editor}
                                                   :selected-ids)"
  ([event-type base-params]
   (make-selection-callback event-type base-params :selected-ids))
  ([event-type base-params selection-key]
   (fn [{:keys [selected-ids]}]
     (events/dispatch! (assoc base-params
                              :event/type event-type
                              selection-key selected-ids)))))

(defn make-copy-callback
  "Create an on-copy callback that dispatches an event or calls a function.
   
   Usage:
   :on-copy (make-copy-callback :effects/set-clipboard {:col col :row row} :items)"
  [event-type base-params items-key]
  (fn [copied-items]
    (events/dispatch! (assoc base-params
                             :event/type event-type
                             items-key copied-items))))


;; ============================================================================
;; Label Function Factories
;; ============================================================================
;; These helpers create :get-item-label functions for common patterns.


(defn make-registry-label
  "Create a get-item-label function that looks up names from a registry.
   
   Usage:
   :get-item-label (make-registry-label :effect-id effects/get-effect :name \"Unknown Effect\")
   
   This replaces:
   (defn- get-effect-label [item]
     (if-let [effect-def (effects/get-effect (:effect-id item))]
       (:name effect-def)
       \"Unknown Effect\"))"
  [id-key lookup-fn name-key fallback]
  (fn [item]
    (if-let [def-map (lookup-fn (get item id-key))]
      (get def-map name-key fallback)
      fallback)))

(defn make-case-label
  "Create a get-item-label function from a keyword->string map.
   
   Usage:
   :get-item-label (make-case-label :effect-id
                     {:corner-pin \"Corner Pin\"
                      :rgb-calibration \"RGB Calibration\"}
                     \"Unknown Effect\")
   
   This replaces:
   (defn- get-calibration-effect-label [item]
     (case (:effect-id item)
       :corner-pin \"Corner Pin\"
       :rgb-calibration \"RGB Calibration\"
       \"Unknown Effect\"))"
  [id-key label-map fallback]
  (fn [item]
    (get label-map (get item id-key) fallback)))


;; ============================================================================
;; Keyboard Handler Setup for Parent Components
;; ============================================================================


(defn setup-keyboard-handlers-for-component!
  "Setup keyboard handlers on a parent node for a hierarchical list component.
   This eliminates the need for each consumer to duplicate keyboard setup code.
   
   This sets up an event filter to intercept Ctrl+C/V/X before system clipboard
   and handles all standard keyboard shortcuts:
   - Ctrl+C: Copy selected items
   - Ctrl+V: Paste items
   - Ctrl+X: Cut (copy + delete)
   - Ctrl+A: Select all
   - Ctrl+G: Group selected items
   - Delete: Delete selected items
   - Escape: Clear selection
   
   Args:
   - node: The JavaFX node to attach handlers to
   - component-id: The component ID used to identify the hierarchical list state"
  [^javafx.scene.Node node component-id]
  (.addEventFilter
    node
    javafx.scene.input.KeyEvent/KEY_PRESSED
    (reify javafx.event.EventHandler
      (handle [_ event]
        (let [code (.getCode event)
              ctrl? (.isShortcutDown event)
              ;; Get fresh state for this component
              state (get-state component-id)
              items (:items state)
              props (:props state)]
          (when (and items props)
            (cond
              ;; Ctrl+C - Copy
              (and ctrl? (= code javafx.scene.input.KeyCode/C))
              (do (copy-selected-from-component! component-id)
                  (.consume event))
              
              ;; Ctrl+V - Paste
              (and ctrl? (= code javafx.scene.input.KeyCode/V))
              (do (paste-items-from-component! component-id)
                  (.consume event))
              
              ;; Ctrl+X - Cut (copy + delete)
              (and ctrl? (= code javafx.scene.input.KeyCode/X))
              (do (copy-selected-from-component! component-id)
                  (delete-selected-from-component! component-id)
                  (.consume event))
              
              ;; Ctrl+A - Select all
              (and ctrl? (= code javafx.scene.input.KeyCode/A))
              (do (select-all-from-component! component-id)
                  (.consume event))
              
              ;; Ctrl+G - Group selected
              (and ctrl? (= code javafx.scene.input.KeyCode/G))
              (do (group-selected-from-component! component-id)
                  (.consume event))
              
              ;; Delete key - Delete selected
              (= code javafx.scene.input.KeyCode/DELETE)
              (do (delete-selected-from-component! component-id)
                  (.consume event))
              
              ;; Escape - Clear selection
              (= code javafx.scene.input.KeyCode/ESCAPE)
              (do (clear-selection-from-component! component-id)
                  (.consume event)))))))))


;; ============================================================================
;; All-in-One Wrapper Component
;; ============================================================================


(defn hierarchical-list-editor
  "Complete hierarchical list editor with keyboard handling and event dispatch.
   This is the recommended component for most use cases - it provides all
   features with minimal configuration.
   
   Props:
   - :items - Vector of items (required)
   - :component-id - Unique ID for state isolation (required)
   
   Label configuration (one of):
   - :get-item-label - Custom function item -> string
   - :item-id-key + :item-registry-fn + :item-name-key - For registry lookup
   - :item-id-key + :label-map - For case-based labels
   
   Event dispatch:
   - :on-change-event - Event type for items changed (e.g., :effects/set-chain)
   - :on-change-params - Base params for change event (e.g., {:col col :row row})
   - :items-key - Key for items in event (default :items)
   
   - :on-selection-event - Event type for selection changed (optional)
   - :on-selection-params - Base params for selection event
   - :selection-key - Key for selection in event (default :selected-ids)
   
   - :on-copy-fn - Custom copy function (optional)
   - :clipboard-items - Items for paste (optional)
   
   UI customization:
   - :header-label - Header text (default 'LIST')
   - :empty-text - Empty state text (optional)
   - :allow-groups? - Enable grouping (default true)
   - :fallback-label - Label for unknown items (default \"Unknown\")
   
   Example usage:
   {:fx/type hierarchical-list/hierarchical-list-editor
    :items effect-chain
    :component-id [:effect-chain col row]
    :item-id-key :effect-id
    :item-registry-fn effects/get-effect
    :item-name-key :name
    :on-change-event :effects/set-chain
    :on-change-params {:col col :row row}
    :on-selection-event :ui/update-dialog-data
    :on-selection-params {:dialog-id :effect-chain-editor :updates {}}
    :clipboard-items clipboard-items
    :header-label \"CHAIN\"
    :empty-text \"No effects\\nAdd from bank →\"}"
  [{:keys [items component-id
           ;; Label options
           get-item-label item-id-key item-registry-fn item-name-key label-map
           ;; Event dispatch options
           on-change-event on-change-params items-key
           on-selection-event on-selection-params selection-key
           on-copy-fn clipboard-items
           ;; UI options
           header-label empty-text allow-groups? fallback-label]
    :or {items-key :items
         selection-key :selected-ids
         header-label "LIST"
         empty-text "No items"
         allow-groups? true
         fallback-label "Unknown"}
    :as props}]
  (let [;; Determine the label function
        label-fn (cond
                   ;; Explicit function provided
                   get-item-label
                   get-item-label
                   
                   ;; Registry lookup pattern
                   (and item-id-key item-registry-fn)
                   (make-registry-label item-id-key item-registry-fn
                                        (or item-name-key :name) fallback-label)
                   
                   ;; Case/map pattern
                   (and item-id-key label-map)
                   (make-case-label item-id-key label-map fallback-label)
                   
                   ;; Default - try :name field
                   :else
                   (fn [item] (or (:name item) fallback-label)))
        
        ;; Build on-items-changed callback
        on-items-changed (when on-change-event
                           (make-dispatch-callback on-change-event on-change-params items-key))
        
        ;; Build on-selection-changed callback
        on-selection-changed (when on-selection-event
                               (make-selection-callback on-selection-event on-selection-params selection-key))]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [node]
                   (setup-keyboard-handlers-for-component! node component-id)
                   (.setFocusTraversable node true)
                   (.requestFocus node))
     :desc {:fx/type :v-box
            :children [{:fx/type hierarchical-list-sidebar
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
                        :component-id component-id}]}}))
