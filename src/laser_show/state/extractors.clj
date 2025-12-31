(ns laser-show.state.extractors
  "Pure data extraction functions that work on raw state maps.
   
   These functions provide a single source of truth for accessing state data.
   They are used by both:
   - laser-show.state.queries (for backend/service thread-safe access)
   - laser-show.subs (for UI memoized subscriptions)
   
   All functions take state as first argument for consistent composition
   with thread-first macro (->) and for use with fx/sub-val identity.")


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

(defn tap-times [state]
  (:tap-times (timing state)))

;; Playback Extractors


(defn playback [state]
  (:playback state))

(defn playing? [state]
  (:playing? (playback state)))

(defn active-cell [state]
  (:active-cell (playback state)))

;; Grid Extractors


(defn grid [state]
  (:grid state))

(defn grid-cells [state]
  (:cells (grid state)))

(defn grid-size [state]
  (:size (grid state)))

(defn selected-cell [state]
  (:selected-cell (grid state)))

;; Effects Extractors

(defn effects [state]
  (:effects state))

(defn effects-cells [state]
  (:cells (effects state)))


(defn ui [state]
  (:ui state))

(defn clipboard [state]
  (:clipboard (ui state)))


;; Project Extractors

(defn project [state]
  (:project state))

(defn project-folder [state]
  (:current-folder (project state)))

(defn project-dirty? [state]
  (:dirty? (project state)))
