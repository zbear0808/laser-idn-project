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


;; Playback Extractors


(defn playback [state]
  (:playback state))

(defn playing? [state]
  (:playing? (playback state)))

(defn active-cell [state]
  (:active-cell (playback state)))



(defn grid [state]
  (:grid state))

;; REMOVED: grid-cells function
;; All cue content is now stored in [:chains :cue-chains [col row] :items]
;; Access cue chains directly: (get-in state [:chains :cue-chains])

(defn grid-size [state]
  (:size (grid state)))

(defn selected-cell [state]
  (:selected-cell (grid state)))


(defn ui [state]
  (:ui state))

(defn active-tab [state]
  (:active-tab (ui state)))

(defn clipboard [state]
  (:clipboard (ui state)))

(defn dialogs [state]
  (:dialogs (ui state)))

;; Projectors Extractors


(defn projectors [state]
  (:projectors state))

(defn projectors-items [state]
  (:items (projectors state)))


(defn enabled-projectors
  "Get all enabled projectors."
  [state]
  (->> (projectors-items state)
       (filter (fn [[_id proj]] (:enabled? proj true)))
       (into {})))

(defn virtual-projectors [state]
  (:virtual-projectors (projectors state)))


;; Backend Extractors

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
