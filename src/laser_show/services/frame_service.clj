(ns laser-show.services.frame-service
  "Frame service for generating preview frames.
   
   This service:
   - Reads from the new unified state
   - Generates frames based on active cell and preset
   - Applies effects from the effects grid
   - Provides frames for preview canvas and IDN streaming"
  (:require [laser-show.state.core :as state]
            [laser-show.animation.presets :as presets]
            [laser-show.animation.types :as t]))

;; ============================================================================
;; Frame Generation
;; ============================================================================

(defn get-active-cell-preset
  "Get the preset ID for the currently active cell."
  []
  (let [s (state/get-state)
        active-cell (get-in s [:playback :active-cell])]
    (when active-cell
      (let [[col row] active-cell]
        (get-in s [:grid :cells [col row] :preset-id])))))

(defn get-trigger-time
  "Get the trigger time for animation timing."
  []
  (get-in (state/get-state) [:playback :trigger-time] 0))

(defn get-bpm
  "Get the current BPM."
  []
  (get-in (state/get-state) [:timing :bpm] 120.0))

(defn is-playing?
  "Check if playback is active."
  []
  (get-in (state/get-state) [:playback :playing?] false))

(defn generate-current-frame
  "Generate the current animation frame based on state.
   Returns a LaserFrame or nil if nothing to render."
  []
  (when (is-playing?)
    (when-let [preset-id (get-active-cell-preset)]
      (when-let [anim (presets/create-animation-from-preset preset-id)]
        (let [trigger-time (get-trigger-time)
              elapsed (- (System/currentTimeMillis) trigger-time)]
          (t/get-frame anim elapsed))))))

;; ============================================================================
;; Frame Conversion for Preview
;; ============================================================================

(defn laser-point->preview-point
  "Convert a LaserPoint to a map suitable for preview rendering."
  [^laser_show.animation.types.LaserPoint point]
  {:x (/ (:x point) 32767.0)  ;; Convert back to -1..1 range
   :y (/ (:y point) 32767.0)
   :r (bit-and (int (:r point)) 0xFF)  ;; Convert byte to unsigned
   :g (bit-and (int (:g point)) 0xFF)
   :b (bit-and (int (:b point)) 0xFF)
   :blanked? (t/blanked? point)})

(defn frame->preview-data
  "Convert a LaserFrame to preview-friendly data."
  [frame]
  (when frame
    {:points (mapv laser-point->preview-point (:points frame))
     :timestamp (:timestamp frame)
     :metadata (:metadata frame)}))

(defn get-preview-frame
  "Get the current frame in preview-friendly format."
  []
  (frame->preview-data (generate-current-frame)))

;; ============================================================================
;; Frame Provider for Streaming
;; ============================================================================

(defn create-frame-provider
  "Create a frame provider function for IDN streaming.
   Returns a zero-arity function that returns the current LaserFrame."
  []
  (fn []
    (generate-current-frame)))

;; ============================================================================
;; State Update Integration
;; ============================================================================

(defn update-preview-frame!
  "Update the preview frame in state.
   Call this periodically to update the preview."
  []
  (let [frame (get-preview-frame)]
    (state/swap-state! assoc-in [:backend :streaming :current-frame] frame)))

;; ============================================================================
;; Preview Update Timer
;; ============================================================================

(defonce ^:private preview-timer (atom nil))

(defn stop-preview-updates!
  "Stop periodic preview frame updates."
  []
  (when-let [timer @preview-timer]
    (.cancel timer)
    (reset! preview-timer nil)
    (println "Preview updates stopped")))

(defn start-preview-updates!
  "Start periodic preview frame updates.
   FPS determines how often frames are generated."
  ([]
   (start-preview-updates! 30)) ;; Default 30 FPS
  ([fps]
   (stop-preview-updates!)
   (let [interval-ms (long (/ 1000 fps))
         timer (java.util.Timer. "preview-update" true)]
     (.scheduleAtFixedRate timer
                           (proxy [java.util.TimerTask] []
                             (run []
                               (try
                                 (update-preview-frame!)
                                 (catch Exception e
                                   (println "Preview update error:" (.getMessage e))))))
                           0
                           interval-ms)
     (reset! preview-timer timer)
     (println "Preview updates started at" fps "FPS"))))

(defn preview-running?
  "Check if preview updates are running."
  []
  (some? @preview-timer))

;; ============================================================================
;; REPL / Testing
;; ============================================================================

(comment
  ;; Start preview updates
  (start-preview-updates! 30)
  
  ;; Stop preview updates
  (stop-preview-updates!)
  
  ;; Check current frame
  (get-preview-frame)
  
  ;; Manual frame generation test
  (require '[laser-show.state.core :as state]
           '[laser-show.state.domains :as domains])
  
  ;; Initialize state
  (state/init-state! (domains/build-initial-state))
  
  ;; Set up a test cell
  (state/swap-state! assoc-in [:grid :cells [0 0]] {:preset-id :circle})
  (state/swap-state! assoc-in [:playback :active-cell] [0 0])
  (state/swap-state! assoc-in [:playback :playing?] true)
  (state/swap-state! assoc-in [:playback :trigger-time] (System/currentTimeMillis))
  
  ;; Get frame
  (get-preview-frame)
  )
