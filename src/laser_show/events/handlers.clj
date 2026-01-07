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
   [laser-show.events.handlers.chain :as chain]
   [laser-show.events.handlers.list :as list]))

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
      "list" (list/handle event)
      ;; Unknown domain
      (do
        (log/warn "Unknown event domain:" domain "type:" type)
        {}))))
