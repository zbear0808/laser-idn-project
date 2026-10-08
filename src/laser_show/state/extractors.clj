(ns laser-show.state.extractors
  "Pure data extraction functions that work on raw state maps.
   
   These functions provide a single source of truth for accessing state data.
   They are used by:
   - Backend/services: Call directly with (state/get-raw-state)
   - UI components: Via laser-show.subs with fx/sub-val
   
   All functions take state as first argument for consistent composition
   with thread-first macro (->) and for use with fx/sub-val identity.
   
   Example backend usage:
     (ex/bpm (state/get-raw-state))
   
   Example UI subscription:
     (fx/sub-val context ex/bpm)"
  (:require [laser-show.animation.chains :as chains]))


(defn resolve-items-path
  "Resolve canonical path in state to the items vector for a component-id."
  [component-id]
  (cond
    (vector? component-id)
    (case (first component-id)
      :cue-chain
      (let [[_ col row] component-id]
        [:chains :cue-chains [col row] :items])

      :effect-chain
      (let [[_ col row] component-id]
        [:chains :effect-chains [col row] :items])

      :projector-effects
      (let [[_ proj-id] component-id]
        [:chains :projector-effects proj-id :items])

      :timeline-cue-chain
      (let [[_ col row] component-id]
        [:chains :cue-chains [col row] :items])

      nil)

    :else nil))


(defn chain-items
  "Extract raw chain items vector from state at items-path or component-id."
  [state path-or-component-id]
  (cond
    (nil? path-or-component-id)
    []

    ;; Already a collection of item maps
    (and (vector? path-or-component-id)
         (seq path-or-component-id)
         (map? (first path-or-component-id)))
    path-or-component-id

    ;; Direct path in state
    (and (vector? path-or-component-id)
         (contains? state (first path-or-component-id)))
    (get-in state path-or-component-id [])

    ;; Component-id that maps to a path in state
    :else
    (if-let [p (resolve-items-path path-or-component-id)]
      (get-in state p [])
      (if (sequential? path-or-component-id)
        (get-in state path-or-component-id [])
        []))))


(defn chain-ordered-ids
  "Extract ordered vector of item IDs from state for a given items-path or component-id.
   Returns an eager vector of UUIDs in visual document order."
  [state path-or-component-id]
  (let [items (chain-items state path-or-component-id)]
    (chains/collect-all-ids items)))


(defn config [state]
  (:config state))

(defn window-config [state]
  (:window (config state)))

(defn preview-config [state]
  (:preview (config state)))

(defn timing [state]
  (:timing state))

(defn bpm [state]
  (:bpm (timing state)))


;; Global Clock Extractors


(defn global-clock
  "Get the global clock state from timing domain.
   Returns {:accumulated-beats :accumulated-ms :last-frame-time}"
  [state]
  (get-in state [:timing :global-clock]))

(defn global-accumulated-beats
  "Get accumulated beats from global clock, including smooth phase offset.
   Use for BPM visualization and phase calculations."
  [state]
  (let [gc (global-clock state)]
    (or (:effective-beats gc)
        (when-let [beats (:accumulated-beats gc)]
          (+ (double beats) (double (or (:phase-offset gc) 0.0))))
        0.0)))


;; Playback Extractors


(defn playback [state]
  (:playback state))

(defn playing? [state]
  (:playing? (playback state)))

(defn active-cues
  "Get the map of active cues.
   Returns {[col row] {:trigger-time :accumulated-beats ...} ...}"
  [state]
  (or (:active-cues (playback state)) {}))

(defn resync-rate
  "Get the phase resync rate (beats to reach ~63% correction)."
  [state]
  (or (:resync-rate (playback state)) 4.0))

;; DEPRECATED - use active-cues instead
(defn active-cell
  "DEPRECATED: Use active-cues for multi-cue support.
   Returns first active cue cell for backward compatibility."
  [state]
  (first (keys (active-cues state))))

(defn grid [state]
  (:grid state))

(defn grid-size [state]
  (:size (grid state)))


(defn ui [state]
  (:ui state))

(defn active-tab [state]
  (:active-tab (ui state)))

(defn clipboard [state]
  (:clipboard (ui state)))

(defn dialogs [state]
  (:dialogs (ui state)))

;; Projectors Extractors


(defn projectors
  "Get the projectors map (projector-id -> config).
   The :projectors domain IS the map directly (no :items nesting)."
  [state]
  (:projectors state))

;; Alias for backwards compatibility - projectors domain IS the items map now
(defn projectors-items [state]
  (projectors state))


(defn backend [state]
  (:backend state))

(defn idn-data [state]
  (:idn (backend state)))

(defn streaming-data [state]
  (:streaming (backend state)))


;; Link Extractors


(defn link-data [state]
  (:link (backend state)))

(defn link-connected?
  "DEPRECATED: Use carabiner-connected? or link-enabled? instead.
   Returns true if both Carabiner is connected AND Link is enabled."
  [state]
  (let [data (link-data state)]
    (and (:carabiner-connected? data)
         (:link-enabled? data))))

(defn link-sync-enabled? [state]
  (:sync-enabled? (link-data state)))

(defn link-bpm [state]
  (:link-bpm (link-data state)))



;; Project Extractors

(defn project [state]
  (:project state))

(defn project-file [state]
  (:current-file (project state)))

(defn project-dirty? [state]
  (:dirty? (project state)))
