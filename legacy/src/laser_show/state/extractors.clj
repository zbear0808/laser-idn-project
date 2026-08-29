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
     (fx/sub-val context ex/bpm)")


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
  "Get accumulated beats from global clock.
   Use for BPM visualization and phase calculations."
  [state]
  (or (:accumulated-beats (global-clock state)) 0.0))


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
