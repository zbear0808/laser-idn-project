(ns laser-show.state.queries
   "Thread-safe state queries for backend/services.
   
   These functions use get-raw-state() which is safe to call from background
   threads without triggering fx/sub-val assertions. They are NOT memoized
   and should NOT be used in UI components - use laser-show.subs for that.
   
   Usage:
   - Backend services: Use these query functions
   - UI components: Use laser-show.subs with fx/sub-ctx
   - Event handlers: Can use either (if in co-effects) or queries (if async)"
 (:require [laser-show.state.core :as state]))
 
;; ============================================================================
;; Helper - Get Raw State
;; ============================================================================

(defn- raw-state []
  (state/get-raw-state))

;; ============================================================================
;; Config Queries
;; ============================================================================

(defn config []
  (:config (raw-state)))

(defn grid-config []
  (:grid (config)))

(defn window-config []
  (:window (config)))

(defn preview-config []
  (:preview (config)))

(defn idn-config []
  (:idn (config)))

;; ============================================================================
;; Timing Queries
;; ============================================================================

(defn timing []
  (:timing (raw-state)))

(defn bpm []
  (:bpm (timing)))

(defn beat-position []
  (:beat-position (timing)))

(defn tap-times []
  (:tap-times (timing)))

;; ============================================================================
;; Playback Queries
;; ============================================================================

(defn playback []
  (:playback (raw-state)))

(defn playing? []
  (:playing? (playback)))

(defn trigger-time []
  (:trigger-time (playback)))

(defn active-cell []
  (:active-cell (playback)))

(defn active-cue []
  (:active-cue (playback)))

;; ============================================================================
;; Grid Queries
;; ============================================================================

(defn grid []
  (:grid (raw-state)))

(defn grid-cells []
  (:cells (grid)))

(defn grid-size []
  (:size (grid)))

(defn selected-cell []
  (:selected-cell (grid)))

(defn cell [col row]
  (get-in (grid) [:cells [col row]]))

(defn cell-preset [col row]
  (:preset-id (cell col row)))

;; ============================================================================
;; Effects Queries
;; ============================================================================

(defn effects []
  (:effects (raw-state)))

(defn effects-cells []
  (:cells (effects)))

(defn effect-cell [col row]
  (get-in (effects) [:cells [col row]]))

(defn effect-cell-effects [col row]
  (:effects (effect-cell col row)))

(defn effect-cell-active? [col row]
  (:active (effect-cell col row)))

(defn cell-has-effects? [col row]
  (boolean (seq (effect-cell-effects col row))))

(defn effect-count [col row]
  (count (effect-cell-effects col row)))

(defn all-active-effect-instances
  "Get all effect instances from active cells, flattened in row-major order."
  []
  (let [cells (effects-cells)
        sorted-keys (sort-by (fn [[col row]] [row col]) (keys cells))]
    (into []
          (comp
           (map #(get cells %))
           (filter :active)
           (mapcat :effects)
           (map #(select-keys % [:effect-id :params])))
          sorted-keys)))

;; ============================================================================
;; Projector Queries
;; ============================================================================

(defn projectors []
  (get-in (raw-state) [:projectors :items]))

(defn projector [projector-id]
  (get (projectors) projector-id))

(defn projector-ids []
  (keys (projectors)))

;; ============================================================================
;; Zone Queries
;; ============================================================================

(defn zones []
  (get-in (raw-state) [:zones :items]))

(defn zone [zone-id]
  (get (zones) zone-id))

(defn zone-ids []
  (keys (zones)))

;; ============================================================================
;; Zone Group Queries
;; ============================================================================

(defn zone-groups []
  (get-in (raw-state) [:zone-groups :items]))

(defn zone-group [group-id]
  (get (zone-groups) group-id))

(defn zone-group-ids []
  (keys (zone-groups)))

;; ============================================================================
;; Cue Queries
;; ============================================================================

(defn cues []
  (get-in (raw-state) [:cues :items]))

(defn cue [cue-id]
  (get (cues) cue-id))

(defn cue-ids []
  (keys (cues)))

;; ============================================================================
;; Cue List Queries
;; ============================================================================

(defn cue-lists []
  (get-in (raw-state) [:cue-lists :items]))

(defn cue-list [list-id]
  (get (cue-lists) list-id))

(defn cue-list-ids []
  (keys (cue-lists)))

(defn cue-queue []
  (get-in (raw-state) [:playback :cue-queue]))

;; ============================================================================
;; Effect Registry Queries
;; ============================================================================

(defn effect-registry []
  (get-in (raw-state) [:effect-registry :items]))

(defn registered-effect [effect-id]
  (get (effect-registry) effect-id))

;; ============================================================================
;; Streaming Queries
;; ============================================================================

(defn streaming []
  (:streaming (raw-state)))

(defn streaming-running? []
  (:running? (streaming)))

(defn streaming-engines []
  (:engines (streaming)))

(defn streaming-engine [projector-id]
  (get (streaming-engines) projector-id))

;; ============================================================================
;; UI Queries
;; ============================================================================

(defn ui []
  (:ui (raw-state)))

(defn clipboard []
  (:clipboard (ui)))

(defn selected-preset []
  (:selected-preset (ui)))

(defn drag []
  (:drag (ui)))

(defn ui-components []
  (:components (ui)))

(defn ui-component [component-key]
  (get (ui-components) component-key))

(defn main-frame []
  (ui-component :main-frame))

;; ============================================================================
;; Project Queries
;; ============================================================================

(defn project []
  (:project (raw-state)))

(defn project-folder []
  (:current-folder (project)))

(defn project-dirty? []
  (:dirty? (project)))

(defn project-last-saved []
  (:last-saved (project)))

(defn has-project? []
  (some? (project-folder)))

;; ============================================================================
;; IDN Queries
;; ============================================================================

(defn idn []
  (:idn (raw-state)))

(defn idn-connected? []
  (:connected? (idn)))

(defn idn-target []
  (:target (idn)))

;; ============================================================================
;; Backend Queries
;; ============================================================================

(defn backend []
  (:backend (raw-state)))
