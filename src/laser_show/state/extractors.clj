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


;; Playback Extractors


(defn playback [state]
  (:playback state))

(defn playing? [state]
  (:playing? (playback state)))

(defn active-cell [state]
  (:active-cell (playback state)))

(defn accumulated-beats [state]
  (or (:accumulated-beats (playback state)) 0.0))

(defn phase-offset [state]
  (or (:phase-offset (playback state)) 0.0))

(defn effective-beats [state]
  (+ (accumulated-beats state) (phase-offset state)))

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


;; Project Extractors

(defn project [state]
  (:project state))

(defn project-file [state]
  (:current-file (project state)))

(defn project-dirty? [state]
  (:dirty? (project state)))
