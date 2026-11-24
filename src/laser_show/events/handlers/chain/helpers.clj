(ns laser-show.events.handlers.chain.helpers
  "Helper functions for chain operations.
   
   Contains:
   - Configuration factory (chain-config)
   - Where an effect instance is now (resolve-effect-instance-path)
   
   This module has NO dependencies on other chain sub-modules to avoid cycles.
   
   NOTE: Selection state helpers (get-selected-paths, etc.) were removed as dead code.
   Selection is now handled by the list.clj component using ID-based operations
   with state at [:list-ui component-id]."
  (:require [laser-show.animation.chains :as chains]))


(defn chain-config
  "Create a unified chain configuration for any domain.
   
   This is the primary config factory for the new :chains-based architecture.
   All three chain types use consistent paths under [:chains <domain> <key>].
   
   Usage:
   (chain-config :effect-chains [col row])
   (chain-config :cue-chains [col row])
   (chain-config :projector-effects projector-id)
   
   Parameters:
   - domain: One of :effect-chains, :cue-chains, :projector-effects, the
     timeline editor's :timeline-items (a timeline cue's clips) and
     :timeline-tracks (its tracks and folders, for track effects), or
     :cue-zone-patterns (a cue's zone patterns, zones plan Z5: the pattern
     window edits them as a chain)
   - entity-key: The key within the domain ([col row] or projector-id)
   
   Returns: Configuration map with :items-path, :ui-path, :domain, :entity-key"
  [domain entity-key]
  (let [base-path [:chains (case domain (:timeline-items :timeline-tracks :cue-zone-patterns) :cue-chains domain) entity-key]]
    {:items-path (conj base-path (case domain
                                   :timeline-tracks :tracks
                                   :cue-zone-patterns :zone-patterns
                                   :items))
     :metadata-path base-path
     ;; FLATTENED: Dialog fields live alongside :open?, not under :data
     :ui-path (case domain
                :effect-chains [:ui :dialogs :effect-chain-editor]
                :cue-chains [:ui :dialogs :cue-chain-editor]
                :projector-effects [:ui :projector-effect-ui-state entity-key]
                :timeline-items [:ui :dialogs :timeline-cue-editor :effect-ui :items]
                :timeline-tracks [:ui :dialogs :timeline-cue-editor :effect-ui :tracks]
                :cue-zone-patterns [:ui :dialogs :zone-pattern-editor])
     :domain domain
     :entity-key entity-key}))

(def ^:private direct-domains
  "The domains whose chain holds the effects themselves."
  #{:effect-chains :cue-zone-patterns :projector-effects})

(def ^:private owned-domains
  "The domains whose chain holds owners (a cue's items, a timeline's clips,
   its tracks and folders), each with its effects under :effects."
  #{:cue-chains :timeline-items :timeline-tracks})

(defn resolve-effect-instance-path
  "Where the effect an event names by its instance is in state now: its
   path under (chain-config domain entity-key)'s :items-path, or nil. The
   address has :domain, :entity-key and :effect-instance-id (the effect's
   :id), and in :cue-chains, :timeline-items and :timeline-tracks
   :owner-id too (the cue item's, clip's, track's or folder's :id): the
   owner is found in the chain, then the effect among the owner's
   :effects, groups searched at both levels (chains/find-path-by-id).
   Never the place the effect had, the selection or another cell: nil for
   an unsupported domain, a missing key, or an effect (or owner) that
   isn't there now.
   
   Example:
   (resolve-effect-instance-path state {:domain :cue-chains :entity-key [0 0]
                                        :owner-id p :effect-instance-id a})
   => [1 :effects 1]"
  [state {:keys [domain entity-key effect-instance-id owner-id]}]
  (when (and (or (contains? direct-domains domain) (contains? owned-domains domain))
             (some? entity-key)
             (some? effect-instance-id))
    (let [items (get-in state (:items-path (chain-config domain entity-key)))]
      (when (sequential? items)
        (if (contains? direct-domains domain)
          (chains/find-path-by-id (vec items) effect-instance-id)
          (when (some? owner-id)
            (when-let [owner-path (chains/find-path-by-id (vec items) owner-id)]
              (let [effects (:effects (get-in (vec items) owner-path))]
                (when (sequential? effects)
                  (when-let [effect-path (chains/find-path-by-id (vec effects) effect-instance-id)]
                    (into (conj owner-path :effects) effect-path)))))))))))
