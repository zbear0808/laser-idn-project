(ns laser-show.events.handlers.chain.helpers
  "Helper functions for chain operations.
   
   Contains:
   - Configuration factory (chain-config)
   - The vector a step grid looks its zone pattern up in (zone-pattern-items-path)
   - Where an effect instance is now (resolve-effect-instance-path), and an
     owner's effects (owner-effects-path)
   - The pipeline step that finds an edit's effect by its instance
     (wrap-effect-instance-target)
   
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

(defn zone-pattern-items-path
  "The one vector a step grid looks its zone pattern up in (zones plan Z6),
   where chain-config keeps it: a cue's :zone-patterns for
   :cue-zone-patterns, an effects cell's :items for :effect-chains. nil for
   any other domain, or an entity-key that isn't a cell's [col row]."
  [domain entity-key]
  (when (and (contains? #{:cue-zone-patterns :effect-chains} domain)
             (vector? entity-key)
             (= 2 (count entity-key))
             (every? nat-int? entity-key))
    (:items-path (chain-config domain entity-key))))

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

(defn owner-effects-path
  "Where the effects of the owner an address names are in app state now:
   the path to the :effects of {:domain :entity-key :owner-id}, a cue item
   or group (:cue-chains), a clip (:timeline-items), or a track or folder
   (:timeline-tracks), found by its id (chains/find-path-by-id, groups
   searched). nil for another domain, a missing key, or an owner that isn't
   there now. The cue chain editor's item effects list names its item this
   way (follow-up N14).
   
   Example:
   (owner-effects-path state {:domain :cue-chains :entity-key [0 0] :owner-id p})
   => [:chains :cue-chains [0 0] :items 1 :effects]"
  [state {:keys [domain entity-key owner-id]}]
  (when (and (contains? owned-domains domain) (some? entity-key) (some? owner-id))
    (let [items-path (:items-path (chain-config domain entity-key))
          items (get-in state items-path)]
      (when (sequential? items)
        (when-let [owner-path (chains/find-path-by-id (vec items) owner-id)]
          (-> items-path (into owner-path) (conj :effects)))))))

(def ^:private instance-namespaces
  "The events an editor can name their effect by its instance in: an
   effect's parameters (:chain/*) and its keyframes (:keyframe/*)."
  #{"chain" "keyframe"})

(defn wrap-effect-instance-target
  "Pipeline step just outside the show lock (events/core.clj). A :chain/*
   or :keyframe/* event that names its effect by its instance
   (:effect-instance-id, and :owner-id for an effect a cue item, a clip or
   a track owns: the effect parameter editor's) goes on with :effect-path
   where that effect is now (resolve-effect-instance-path), so the lock
   classifies the effect itself and its handler edits it, wherever a
   reorder, an insert or a delete queued ahead of it moved it. Once it is
   gone, the event does nothing ({}). An event that names no instance goes
   through as it is (follow-up N14)."
  [handler]
  (fn [{:keys [event/type effect-instance-id state] :as event}]
    (if (and (some? effect-instance-id)
             (keyword? type)
             (contains? instance-namespaces (namespace type)))
      (if-let [path (resolve-effect-instance-path state event)]
        (handler (assoc event :effect-path path))
        {})
      (handler event))))
