(ns laser-show.views.components.effect-chain-events
  "Event dispatcher protocol for effect chain components.
   
   This protocol abstracts event dispatching so that the effect chain sidebar
   can be reused for both grid cell effects and projector calibration effects.
   Each implementation routes events to the appropriate event namespace.
   
   Two implementations are provided:
   - GridCellEventDispatcher: Routes to :effects/* events for grid cell effects
   - ProjectorEventDispatcher: Routes to :projectors/* events for projector calibration"
  (:require [laser-show.events.core :as events]))


;; Event Dispatcher Protocol


(defprotocol IEffectChainEventDispatcher
  "Protocol for effect chain event dispatching.
   Implementations route events to different namespaces based on context."
  
  ;; Selection Events
  (select-item-at-path [this path ctrl? shift?]
    "Select an item at the given path. Supports Ctrl+Click (toggle) and Shift+Click (range).")
  (select-all [this]
    "Select all items in the chain.")
  (clear-selection [this]
    "Clear the current selection.")
  
  ;; Clipboard Events  
  (copy-selected [this]
    "Copy selected items to clipboard.")
  (paste-into-chain [this]
    "Paste items from clipboard into the chain.")
  (delete-selected [this]
    "Delete selected items from the chain.")
  
  ;; Drag-and-Drop Events
  (start-multi-drag [this initiating-path]
    "Start a multi-item drag operation from the given path.")
  (move-items [this target-id drop-position]
    "Move dragged items to the target position.")
  (update-drag-state [this updates]
    "Update drag/drop UI state (target path, position, etc).")
  
  ;; Group Events
  (group-selected [this]
    "Group selected items together.")
  (ungroup [this path]
    "Ungroup the group at the given path.")
  (create-empty-group [this]
    "Create a new empty group.")
  (toggle-group-collapse [this path]
    "Toggle collapse/expand state of a group.")
  (start-rename-group [this path]
    "Start renaming the group at the given path.")
  (rename-group [this path name]
    "Rename the group at the given path.")
  (cancel-rename-group [this]
    "Cancel the current group rename operation.")
  
  ;; Item State Events
  (set-item-enabled [this path enabled?]
    "Enable or disable the item at the given path.")
  (add-effect [this effect]
    "Add a new effect to the chain.")
  
  ;; Parameter Events
  (update-param [this effect-path param-key value]
    "Update a parameter value for an effect.")
  (update-param-from-text [this effect-path param-key text-value min max]
    "Update a parameter from text input with validation.")
  (set-param-ui-mode [this effect-path mode]
    "Set the UI mode (:visual or :numeric) for an effect's parameters.")
  
  ;; Context Access
  (get-dialog-id [this]
    "Get the dialog ID for UI state updates.")
  (get-context-info [this]
    "Get context-specific info map (col/row for grid cells, projector-id for projectors)."))


;; Grid Cell Event Dispatcher


(defrecord GridCellEventDispatcher [col row]
  IEffectChainEventDispatcher
  
  ;; Selection Events
  (select-item-at-path [_ path ctrl? shift?]
    (events/dispatch! {:event/type :effects/select-item-at-path
                       :path path
                       :col col :row row
                       :ctrl? ctrl?
                       :shift? shift?}))
  
  (select-all [_]
    (events/dispatch! {:event/type :effects/select-all
                       :col col :row row}))
  
  (clear-selection [_]
    (events/dispatch! {:event/type :effects/clear-selection}))
  
  ;; Clipboard Events
  (copy-selected [_]
    (events/dispatch! {:event/type :effects/copy-selected
                       :col col :row row}))
  
  (paste-into-chain [_]
    (events/dispatch! {:event/type :effects/paste-into-chain
                       :col col :row row}))
  
  (delete-selected [_]
    (events/dispatch! {:event/type :effects/delete-selected
                       :col col :row row}))
  
  ;; Drag-and-Drop Events
  (start-multi-drag [_ initiating-path]
    (events/dispatch! {:event/type :effects/start-multi-drag
                       :col col :row row
                       :initiating-path initiating-path}))
  
  (move-items [_ target-id drop-position]
    (events/dispatch! {:event/type :effects/move-items
                       :col col :row row
                       :target-id target-id
                       :drop-position drop-position}))
  
  (update-drag-state [_ updates]
    (events/dispatch! {:event/type :ui/update-dialog-data
                       :dialog-id :effect-chain-editor
                       :updates updates}))
  
  ;; Group Events
  (group-selected [_]
    (events/dispatch! {:event/type :effects/group-selected
                       :col col :row row}))
  
  (ungroup [_ path]
    (events/dispatch! {:event/type :effects/ungroup
                       :col col :row row
                       :path path}))
  
  (create-empty-group [_]
    (events/dispatch! {:event/type :effects/create-empty-group
                       :col col :row row}))
  
  (toggle-group-collapse [_ path]
    (events/dispatch! {:event/type :effects/toggle-group-collapse
                       :col col :row row
                       :path path}))
  
  (start-rename-group [_ path]
    (events/dispatch! {:event/type :effects/start-rename-group
                       :path path}))
  
  (rename-group [_ path name]
    (events/dispatch! {:event/type :effects/rename-group
                       :col col :row row
                       :path path
                       :name name}))
  
  (cancel-rename-group [_]
    (events/dispatch! {:event/type :effects/cancel-rename-group}))
  
  ;; Item State Events
  (set-item-enabled [_ path enabled?]
    (events/dispatch! {:event/type :effects/set-item-enabled-at-path
                       :col col :row row
                       :path path
                       :enabled? enabled?}))
  
  (add-effect [_ effect]
    (events/dispatch! {:event/type :effects/add-effect
                       :col col :row row
                       :effect effect}))
  
  ;; Parameter Events
  (update-param [_ effect-path param-key value]
    (events/dispatch! {:event/type :effects/update-param
                       :col col :row row
                       :effect-path effect-path
                       :param-key param-key
                       :value value}))
  
  (update-param-from-text [_ effect-path param-key text-value min max]
    (events/dispatch! {:event/type :effects/update-param-from-text
                       :col col :row row
                       :effect-path effect-path
                       :param-key param-key
                       :text-value text-value
                       :min min :max max}))
  
  (set-param-ui-mode [_ effect-path mode]
    (events/dispatch! {:event/type :effects/set-param-ui-mode
                       :effect-path effect-path
                       :mode mode}))
  
  ;; Context Access
  (get-dialog-id [_]
    :effect-chain-editor)
  
  (get-context-info [_]
    {:col col :row row}))


;; Projector Event Dispatcher


(defrecord ProjectorEventDispatcher [projector-id]
  IEffectChainEventDispatcher
  
  ;; Selection Events
  (select-item-at-path [_ path ctrl? shift?]
    (events/dispatch! {:event/type :projectors/select-effect
                       :projector-id projector-id
                       :path path
                       :ctrl? ctrl?
                       :shift? shift?}))
  
  (select-all [_]
    (events/dispatch! {:event/type :projectors/select-all-effects
                       :projector-id projector-id}))
  
  (clear-selection [_]
    (events/dispatch! {:event/type :projectors/clear-effect-selection
                       :projector-id projector-id}))
  
  ;; Clipboard Events
  (copy-selected [_]
    (events/dispatch! {:event/type :projectors/copy-effects
                       :projector-id projector-id}))
  
  (paste-into-chain [_]
    (events/dispatch! {:event/type :projectors/paste-effects
                       :projector-id projector-id}))
  
  (delete-selected [_]
    (events/dispatch! {:event/type :projectors/delete-effects
                       :projector-id projector-id}))
  
  ;; Drag-and-Drop Events
  (start-multi-drag [_ initiating-path]
    (events/dispatch! {:event/type :projectors/start-effect-drag
                       :projector-id projector-id
                       :initiating-path initiating-path}))
  
  (move-items [_ target-id drop-position]
    (events/dispatch! {:event/type :projectors/move-effects
                       :projector-id projector-id
                       :target-id target-id
                       :drop-position drop-position}))
  
  (update-drag-state [_ updates]
    (events/dispatch! {:event/type :projectors/update-effect-ui-state
                       :projector-id projector-id
                       :updates updates}))
  
  ;; Group Events
  (group-selected [_]
    (events/dispatch! {:event/type :projectors/group-effects
                       :projector-id projector-id}))
  
  (ungroup [_ path]
    (events/dispatch! {:event/type :projectors/ungroup-effects
                       :projector-id projector-id
                       :path path}))
  
  (create-empty-group [_]
    (events/dispatch! {:event/type :projectors/create-effect-group
                       :projector-id projector-id}))
  
  (toggle-group-collapse [_ path]
    (events/dispatch! {:event/type :projectors/toggle-effect-group-collapse
                       :projector-id projector-id
                       :path path}))
  
  (start-rename-group [_ path]
    (events/dispatch! {:event/type :projectors/start-rename-effect-group
                       :projector-id projector-id
                       :path path}))
  
  (rename-group [_ path name]
    (events/dispatch! {:event/type :projectors/rename-effect-group
                       :projector-id projector-id
                       :path path
                       :name name}))
  
  (cancel-rename-group [_]
    (events/dispatch! {:event/type :projectors/cancel-rename-effect-group
                       :projector-id projector-id}))
  
  ;; Item State Events
  (set-item-enabled [_ path enabled?]
    (events/dispatch! {:event/type :projectors/set-effect-enabled
                       :projector-id projector-id
                       :path path
                       :enabled? enabled?}))
  
  (add-effect [_ effect]
    (events/dispatch! {:event/type :projectors/add-calibration-effect
                       :projector-id projector-id
                       :effect effect}))
  
  ;; Parameter Events
  (update-param [_ effect-path param-key value]
    (events/dispatch! {:event/type :projectors/update-effect-param
                       :projector-id projector-id
                       :effect-path effect-path
                       :param-key param-key
                       :value value}))
  
  (update-param-from-text [_ effect-path param-key text-value min max]
    (events/dispatch! {:event/type :projectors/update-effect-param-from-text
                       :projector-id projector-id
                       :effect-path effect-path
                       :param-key param-key
                       :text-value text-value
                       :min min :max max}))
  
  (set-param-ui-mode [_ effect-path mode]
    (events/dispatch! {:event/type :projectors/set-effect-ui-mode
                       :projector-id projector-id
                       :effect-path effect-path
                       :mode mode}))
  
  ;; Context Access
  (get-dialog-id [_]
    :projector-effect-editor)
  
  (get-context-info [_]
    {:projector-id projector-id}))


;; Factory Functions


(defn create-grid-cell-dispatcher
  "Create an event dispatcher for grid cell effects.
   
   Args:
   - col: Column index
   - row: Row index"
  [col row]
  (->GridCellEventDispatcher col row))

(defn create-projector-dispatcher
  "Create an event dispatcher for projector calibration effects.
   
   Args:
   - projector-id: The projector identifier"
  [projector-id]
  (->ProjectorEventDispatcher projector-id))


;; Keyboard Handler Factory


(defn create-keyboard-handler
  "Creates a keyboard event handler function using the given event dispatcher.
   
   This handler supports:
   - Ctrl+C: Copy selected effects
   - Ctrl+V: Paste effects  
   - Ctrl+A: Select all effects
   - Ctrl+G: Group selected effects
   - Delete/Backspace: Delete selected effects
   - Escape: Clear selection
   
   Returns a function that takes a KeyEvent and handles it appropriately."
  [dispatcher]
  (fn [^javafx.scene.input.KeyEvent event]
    (let [code (.getCode event)
          ctrl? (.isControlDown event)]
      (cond
        ;; Ctrl+C - Copy
        (and ctrl? (= code javafx.scene.input.KeyCode/C))
        (do (copy-selected dispatcher)
            (.consume event))
        
        ;; Ctrl+V - Paste
        (and ctrl? (= code javafx.scene.input.KeyCode/V))
        (do (paste-into-chain dispatcher)
            (.consume event))
        
        ;; Ctrl+A - Select all
        (and ctrl? (= code javafx.scene.input.KeyCode/A))
        (do (select-all dispatcher)
            (.consume event))
        
        ;; Ctrl+G - Group selected
        (and ctrl? (= code javafx.scene.input.KeyCode/G))
        (do (group-selected dispatcher)
            (.consume event))
        
        ;; Delete or Backspace - Delete selected
        (or (= code javafx.scene.input.KeyCode/DELETE)
            (= code javafx.scene.input.KeyCode/BACK_SPACE))
        (do (delete-selected dispatcher)
            (.consume event))
        
        ;; Escape - Clear selection
        (= code javafx.scene.input.KeyCode/ESCAPE)
        (do (clear-selection dispatcher)
            (.consume event))))))

(defn setup-keyboard-handlers!
  "Setup keyboard handlers on a JavaFX node for effect chain editing.
   
   This sets up both an event filter (for Ctrl+C/V which need to intercept
   system clipboard handling) and a regular key handler (for other shortcuts).
   
   Args:
   - node: The JavaFX node to attach handlers to
   - dispatcher: An IEffectChainEventDispatcher implementation"
  [^javafx.scene.Node node dispatcher]
  (let [handler-fn (create-keyboard-handler dispatcher)]
    ;; Use event filter for Ctrl+C and Ctrl+V to intercept system clipboard
    (.addEventFilter
      node
      javafx.scene.input.KeyEvent/KEY_PRESSED
      (reify javafx.event.EventHandler
        (handle [_ event]
          (let [code (.getCode event)
                ctrl? (.isControlDown event)]
            (when (and ctrl? (or (= code javafx.scene.input.KeyCode/C)
                                  (= code javafx.scene.input.KeyCode/V)))
              (handler-fn event))))))
    
    ;; Use regular key handler for other shortcuts
    (.setOnKeyPressed
      node
      (reify javafx.event.EventHandler
        (handle [_ event]
          (handler-fn event))))))
