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


(defn projectors
  "Get the projectors map (projector-id -> config).
   The :projectors domain IS the map directly (no :items nesting)."
  [state]
  (:projectors state))

;; Alias for backwards compatibility - projectors domain IS the items map now
(defn projectors-items [state]
  (projectors state))


(defn enabled-projectors
  "Get all enabled projectors."
  [state]
  (->> (projectors state)
       (filter (fn [[_id proj]] (:enabled? proj true)))
       (into {})))

(defn virtual-projectors
  "Get the virtual-projectors map (vp-id -> config).
   This is now its own domain at :virtual-projectors."
  [state]
  (:virtual-projectors state))


(defn backend [state]
  (:backend state))

(defn idn-data [state]
  (:idn (backend state)))

(defn streaming-data [state]
  (:streaming (backend state)))


;; Zone Groups Extractors


(defn zone-groups
  "Get the zone-groups map (zone-group-id -> config)."
  [state]
  (:zone-groups state))

(defn zone-group-ids
  "Get a set of all zone group IDs."
  [state]
  (set (keys (zone-groups state))))


;; Project Extractors

(defn project [state]
  (:project state))

(defn project-file [state]
  (:current-file (project state)))

(defn project-dirty? [state]
  (:dirty? (project state)))
