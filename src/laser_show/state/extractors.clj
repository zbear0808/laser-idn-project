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

(defn trigger-time [state]
  (:trigger-time (playback state)))

;; Beat Accumulation Extractors

(defn accumulated-beats [state]
  (:accumulated-beats (playback state)))

(defn accumulated-ms [state]
  (:accumulated-ms (playback state)))

(defn last-frame-time [state]
  (:last-frame-time (playback state)))

(defn phase-offset [state]
  (:phase-offset (playback state)))

(defn phase-offset-target [state]
  (:phase-offset-target (playback state)))

(defn resync-rate [state]
  (:resync-rate (playback state)))

(defn effective-beats
  "Calculate effective beats (accumulated-beats + phase-offset)."
  [state]
  (+ (or (accumulated-beats state) 0.0)
     (or (phase-offset state) 0.0)))

;; Grid Extractors


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


;; Zones Extractors


(defn zones [state]
  (:zones state))

(defn zones-items [state]
  (:items (zones state)))

(defn zone [state zone-id]
  (get (zones-items state) zone-id))

(defn zones-by-projector
  "Get all zones for a specific projector."
  [state projector-id]
  (->> (zones-items state)
       (filter (fn [[_id zone]] (= (:projector-id zone) projector-id)))
       (into {})))

(defn zones-by-zone-group
  "Get all zones that belong to a specific zone group."
  [state zone-group-id]
  (->> (zones-items state)
       (filter (fn [[_id zone]] (some #(= % zone-group-id) (:zone-groups zone))))
       (into {})))

(defn zones-by-type
  "Get all zones of a specific type (:default, :graphics, :crowd-scanning)."
  [state zone-type]
  (->> (zones-items state)
       (filter (fn [[_id zone]] (= (:type zone) zone-type)))
       (into {})))

(defn enabled-zones
  "Get all enabled zones."
  [state]
  (->> (zones-items state)
       (filter (fn [[_id zone]] (:enabled? zone)))
       (into {})))


;; Zone Groups Extractors


(defn zone-groups [state]
  (:zone-groups state))

(defn zone-groups-items [state]
  (:items (zone-groups state)))

(defn zone-group [state zone-group-id]
  (get (zone-groups-items state) zone-group-id))


;; Projectors Extractors


(defn projectors [state]
  (:projectors state))

(defn projectors-items [state]
  (:items (projectors state)))

(defn projector [state projector-id]
  (get (projectors-items state) projector-id))

(defn projector-zone-ids
  "Get the zone IDs for a projector."
  [state projector-id]
  (:zone-ids (projector state projector-id)))


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

(defn project-folder [state]
  (:current-folder (project state)))

(defn project-dirty? [state]
  (:dirty? (project state)))
