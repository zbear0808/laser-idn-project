(ns laser-show.views.components.list-dnd
  "Drag-and-drop functionality for the hierarchical list component.
   
   This namespace provides JavaFX drag source and target setup functions
   that work with the list component's event system.
   
   Event Flow:
   1. User starts dragging -> setup-drag-source! dispatches :list/start-drag
   2. User drags over target -> setup-drag-target! dispatches :list/update-drop-target
   3. User drops -> setup-drag-target! dispatches handle-drop! which triggers :list/perform-drop
   4. Drag completes -> clears drag state via :list/clear-drag
   
   CSS Classes Applied:
   - chain-item-dragging - on item being dragged
   - chain-item-drop-before / chain-item-drop-after - drop position indicators
   - group-header-drop-before / group-header-drop-into - group drop indicators"
  (:require
   [laser-show.common.util :as u]
   [laser-show.events.core :as events]
   [laser-show.state.core :as state])
  (:import
   [javafx.scene.input ClipboardContent TransferMode]))


;; State Access

(defn- get-ui-state
  "Get the list-ui state for a component."
  [component-id]
  (state/get-in-state [:list-ui component-id]))


;; Event Dispatch Functions

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


;; JavaFX Event Handler Helper

(defn- on-fx-event
  "Create a JavaFX EventHandler from a Clojure function."
  [f]
  (reify javafx.event.EventHandler
    (handle [_ event] (f event))))


;; Drop Indicator CSS Management

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


;; Drag Source Setup

(defn setup-drag-source!
  "Setup drag source handlers on a node.
   
   When drag starts:
   1. Creates dragboard with item ID as string content
   2. Dispatches :list/start-drag event
   3. Adds 'chain-item-dragging' CSS class
   
   When drag completes:
   1. Removes dragging CSS class
   2. Clears any lingering drop target styling
   3. Dispatches :list/clear-drag event"
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


;; Drag Target Setup

(defn setup-drag-target!
  "Setup drag target handlers on a node.
   
   Parameters:
   - node: JavaFX node to make a drop target
   - target-id: ID of the item this node represents
   - group?: true if this is a group header (enables :into drop position)
   - component-id: list component ID for state management
   - items: current items vector
   - props: component props containing on-change-event etc.
   
   Drop Position Calculation:
   - For groups: top 25% = :before, rest = :into
   - For items: top 50% = :before, bottom 50% = :after"
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
