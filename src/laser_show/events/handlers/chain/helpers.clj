(ns laser-show.events.handlers.chain.helpers
  "Helper functions for chain operations.
   
   Contains:
   - Configuration factory (chain-config)
   - Group creation (make-group)
   
   This module has NO dependencies on other chain sub-modules to avoid cycles.
   
   NOTE: Selection state helpers (get-selected-paths, etc.) were removed as dead code.
   Selection is now handled by the list.clj component using ID-based operations
   with state at [:list-ui component-id].")


;; ============================================================================
;; Group Creation
;; ============================================================================


(defn make-group
  "Create a new group with given name and items."
  [name items]
  {:type :group
   :id (random-uuid)
   :name name
   :collapsed? false
   :enabled? true
   :items (vec items)})


;; ============================================================================
;; Configuration Factory
;; ============================================================================


(defn chain-config
  "Create a unified chain configuration for any domain.
   
   This is the primary config factory for the new :chains-based architecture.
   All three chain types use consistent paths under [:chains <domain> <key>].
   
   Usage:
   (chain-config :effect-chains [col row])
   (chain-config :cue-chains [col row])
   (chain-config :projector-effects projector-id)
   
   Parameters:
   - domain: One of :effect-chains, :cue-chains, :projector-effects
   - entity-key: The key within the domain ([col row] or projector-id)
   
   Returns: Configuration map with :items-path, :ui-path, :domain, :entity-key"
  [domain entity-key]
  (let [base-path [:chains domain entity-key]]
    {:items-path (conj base-path :items)
     :metadata-path base-path
     ;; FLATTENED: Dialog fields live alongside :open?, not under :data
     :ui-path (case domain
                :effect-chains [:ui :dialogs :effect-chain-editor]
                :cue-chains [:ui :dialogs :cue-chain-editor]
                :projector-effects [:ui :projector-effect-ui-state entity-key])
     :domain domain
     :entity-key entity-key}))
