(ns laser-show.events.handlers
  "Main event dispatcher - routes events to domain-specific handlers.
   
   This module is now a thin routing layer that delegates to specialized
   handler modules organized by domain. The actual event handling logic
   has been extracted into focused, maintainable modules.
   
   Handler modules:
   - grid-handlers: Grid cell operations
   - effects-handlers: Effect chain operations
   - cue-chain-handlers: Cue chain editor (presets and groups)
   - projector-handlers: Projector configuration and effects
   - timing-handlers: BPM, tap tempo, and transport controls
   - ui-handlers: UI state (tabs, dialogs, drag/drop)
   - project-handlers: Project state management
   - connection-handlers: IDN connection and configuration
   - menu-handlers: Menu commands (File/Edit/View/Help)
   - chain-handlers: Generic chain operations (shared)"
  (:require
   [clojure.tools.logging :as log]
   [laser-show.events.handlers.grid :as grid]
   [laser-show.events.handlers.effects :as effects]
   [laser-show.events.handlers.cue-chain :as cue-chain]
   [laser-show.events.handlers.projector :as projectors]
   [laser-show.events.handlers.timing :as timing]
   [laser-show.events.handlers.ui :as ui]
   [laser-show.events.handlers.project :as project]
   [laser-show.events.handlers.connection :as connection]
   [laser-show.events.handlers.menu :as menu]
   [laser-show.events.handlers.chain :as chain-handlers]))


;; Generic Chain Event Handlers
;; These handlers use the unified chain-handlers module for operations
;; that work across all chain types (effect-chains, cue-chains, projector-effects)


(defn- handle-generic-chain-event
  "Handle generic chain events that work across all chain types.
   
   These events use :domain and :entity-key to create a config and
   delegate to chain-handlers functions."
  [{:keys [event/type domain entity-key state] :as event}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    (case type
      :chain/set-items
      {:state (-> state
                  (assoc-in (:items-path config) (:items event))
                  ((fn [s] (assoc-in s [:project :dirty?] true))))}
      
      :chain/update-selection
      {:state (chain-handlers/handle-clear-selection state config)}  ; Simplified - just clear for now
      
      :chain/select-item
      {:state (chain-handlers/handle-select-item state config (:path event) (:ctrl? event) (:shift? event))}
      
      :chain/select-all
      {:state (chain-handlers/handle-select-all state config)}
      
      :chain/clear-selection
      {:state (chain-handlers/handle-clear-selection state config)}
      
      :chain/delete-selected
      {:state (chain-handlers/handle-delete-selected state config)}
      
      :chain/group-selected
      {:state (chain-handlers/handle-group-selected state config (:name event))}
      
      :chain/ungroup
      {:state (chain-handlers/handle-ungroup state config (:path event))}
      
      :chain/toggle-collapse
      {:state (chain-handlers/handle-toggle-collapse state config (:path event))}
      
      :chain/start-rename
      {:state (chain-handlers/handle-start-rename state config (:path event))}
      
      :chain/rename-item
      {:state (chain-handlers/handle-rename-item state config (:path event) (:new-name event))}
      
      :chain/cancel-rename
      {:state (chain-handlers/handle-cancel-rename state config)}
      
      :chain/set-item-enabled
      {:state (chain-handlers/handle-set-item-enabled state config (:path event) (:enabled? event))}
      
      :chain/start-drag
      {:state (chain-handlers/handle-start-drag state config (:initiating-path event))}
      
      :chain/move-items
      {:state (chain-handlers/handle-move-items state config (:target-id event) (:drop-position event))}
      
      :chain/clear-drag-state
      {:state (chain-handlers/handle-clear-drag-state state config)}
      
      :chain/add-curve-point
      {:state (chain-handlers/handle-add-curve-point state config event)}
      
      :chain/update-curve-point
      {:state (chain-handlers/handle-update-curve-point state config event)}
      
      :chain/remove-curve-point
      {:state (chain-handlers/handle-remove-curve-point state config event)}
      
      :chain/set-active-curve-channel
      {:state (chain-handlers/handle-set-active-curve-channel state config event)}
      
      :chain/update-spatial-params
      {:state (chain-handlers/handle-update-spatial-params state config event)}
      
      ;; Unknown chain event
      (do
        (log/warn "Unknown chain event type:" type)
        {}))))


;; Main Event Dispatcher


(defn handle-event
  "Main event dispatcher - PURE FUNCTION.
   
   Routes events to domain-specific handlers based on the namespace
   of the :event/type keyword.
   
   Input: Event map with :event/type and co-effects (:state, :time)
   Output: Effects map (:state, :dispatch, custom effects)
   
   This is the central event dispatcher. All events flow through here
   and are routed to the appropriate domain handler."
  [{:keys [event/type] :as event}]
  (let [domain (namespace type)]
    (case domain
      "grid" (grid/handle event)
      "effects" (effects/handle event)
      "cue-chain" (cue-chain/handle event)
      "projectors" (projectors/handle event)
      "timing" (timing/handle event)
      "transport" (timing/handle event)  ; Transport grouped with timing
      "ui" (ui/handle event)
      "project" (project/handle event)
      "idn" (connection/handle event)
      "config" (connection/handle event)
      "file" (menu/handle event)
      "edit" (menu/handle event)
      "view" (menu/handle event)
      "help" (menu/handle event)
      "chain" (chain/handle event)
      ;; Unknown domain
      (do
        (log/warn "Unknown event domain:" domain "type:" type)
        {}))))
