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

(defn projector-zone-groups
  "Get the zone group IDs for a projector."
  [state projector-id]
  (:zone-groups (projector state projector-id) []))

(defn projector-corner-pin
  "Get the corner-pin geometry for a projector."
  [state projector-id]
  (:corner-pin (projector state projector-id)))

(defn projector-tags
  "Get the tags set for a projector (:graphics, :crowd-scanning)."
  [state projector-id]
  (:tags (projector state projector-id) #{}))

(defn enabled-projectors
  "Get all enabled projectors."
  [state]
  (->> (projectors-items state)
       (filter (fn [[_id proj]] (:enabled? proj true)))
       (into {})))

(defn projectors-by-zone-group
  "Get all projectors that belong to a specific zone group."
  [state zone-group-id]
  (->> (projectors-items state)
       (filter (fn [[_id proj]]
                 (some #(= % zone-group-id) (:zone-groups proj []))))
       (into {})))

(defn projectors-by-tag
  "Get all projectors with a specific tag."
  [state tag]
  (->> (projectors-items state)
       (filter (fn [[_id proj]] (contains? (:tags proj #{}) tag)))
       (into {})))


;; Virtual Projectors Extractors


(defn virtual-projectors [state]
  (:virtual-projectors (projectors state)))

(defn virtual-projector [state vp-id]
  (get (virtual-projectors state) vp-id))

(defn virtual-projectors-for-projector
  "Get all virtual projectors that belong to a specific parent projector."
  [state parent-projector-id]
  (->> (virtual-projectors state)
       (filter (fn [[_id vp]] (= (:parent-projector-id vp) parent-projector-id)))
       (into {})))

(defn enabled-virtual-projectors
  "Get all enabled virtual projectors."
  [state]
  (->> (virtual-projectors state)
       (filter (fn [[_id vp]] (:enabled? vp true)))
       (into {})))

(defn virtual-projectors-by-zone-group
  "Get all virtual projectors that belong to a specific zone group."
  [state zone-group-id]
  (->> (virtual-projectors state)
       (filter (fn [[_id vp]]
                 (some #(= % zone-group-id) (:zone-groups vp []))))
       (into {})))

(defn virtual-projector-corner-pin
  "Get the corner-pin geometry for a virtual projector."
  [state vp-id]
  (:corner-pin (virtual-projector state vp-id)))

(defn virtual-projector-parent
  "Get the parent projector for a virtual projector."
  [state vp-id]
  (let [vp (virtual-projector state vp-id)]
    (when vp
      (projector state (:parent-projector-id vp)))))


;; Routing Helpers - get all outputs for a zone group


(defn all-outputs-for-zone-group
  "Get all projectors AND virtual projectors assigned to a zone group.
   Returns a vector of output configs, each with:
   {:type :projector or :virtual-projector
    :id projector-id or vp-id
    :projector-id always the physical projector id
    :corner-pin the geometry config
    :enabled? whether output is enabled}"
  [state zone-group-id]
  (let [projs (projectors-by-zone-group state zone-group-id)
        vps (virtual-projectors-by-zone-group state zone-group-id)]
    (into
      ;; Physical projectors
      (mapv (fn [[proj-id proj]]
              {:type :projector
               :id proj-id
               :projector-id proj-id
               :corner-pin (:corner-pin proj)
               :enabled? (:enabled? proj true)})
            projs)
      ;; Virtual projectors
      (mapv (fn [[vp-id vp]]
              {:type :virtual-projector
               :id vp-id
               :projector-id (:parent-projector-id vp)
               :corner-pin (:corner-pin vp)
               :enabled? (:enabled? vp true)})
            vps))))


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
