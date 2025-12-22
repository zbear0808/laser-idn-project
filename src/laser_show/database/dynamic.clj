(ns laser-show.database.dynamic
  "Dynamic runtime state for the laser show application.
   This state is volatile and not persisted between sessions.")

;; ============================================================================
;; Timing State
;; ============================================================================

(defonce !timing
  (atom {:bpm 120.0
         :tap-times []
         :beat-position 0.0           ; Current position within beat cycle (0.0-1.0)
         :bar-position 0.0            ; Current position within bar cycle (0.0-1.0)
         :last-beat-time 0            ; Timestamp of last beat
         :beats-elapsed 0             ; Total beats since playback started
         :quantization :beat}))       ; Current quantization setting (:beat, :bar, :off)

;; ============================================================================
;; Playback State
;; ============================================================================

(defonce !playback
  (atom {:playing false
         :current-animation nil
         :current-cue-id nil
         :animation-start-time 0
         :active-cell [nil nil]       ; [col row] of active cell
         :cue-queue []                ; Queue of upcoming cues
         :active-cue nil}))           ; Currently playing cue

;; ============================================================================
;; Streaming State
;; ============================================================================

(defonce !streaming
  (atom {:engines {}                  ; projector-id -> engine instance
         :running? false
         :connected-targets #{}       ; Set of connected "host:port" strings
         :frame-stats {}              ; Per-projector frame statistics
         :multi-engine-state nil}))   ; Multi-projector streaming state

;; ============================================================================
;; Input State
;; ============================================================================

(defonce !input
  (atom {:midi {:enabled true
                :connected-devices #{}
                :learn-mode nil        ; nil or {:target action-key}
                :device nil
                :receiver nil}
         :osc {:enabled false
               :server-running false
               :learn-mode nil         ; nil or {:target action-key}
               :server nil
               :port 8000}
         :keyboard {:enabled true
                    :attached-components #{}}
         :router {:handlers {}         ; action-key -> handler-fn
                  :event-log []
                  :enabled true}}))

;; ============================================================================
;; UI State
;; ============================================================================

(defonce !ui
  (atom {:selected-preset nil
         :selected-cell nil
         :clipboard nil
         :grid {:size [8 4]            ; [cols rows]
                :cell-states {}}       ; {[col row] {:animation animation, :preset-id id}}
         :preview {:current-animation nil
                   :frame nil
                   :last-render-time 0}
         :active-tab :grid
         :window {:width 1200
                  :height 800}}))

;; ============================================================================
;; Accessor Functions - Timing
;; ============================================================================

(defn get-bpm []
  "Get current BPM"
  (:bpm @!timing))

(defn set-bpm! [bpm]
  "Set BPM to a new value"
  (swap! !timing assoc :bpm (double bpm)))

(defn get-tap-times []
  "Get tap tempo buffer"
  (:tap-times @!timing))

(defn add-tap-time! [timestamp]
  "Add a tap timestamp to the buffer"
  (swap! !timing update :tap-times conj timestamp))

(defn clear-tap-times! []
  "Clear tap tempo buffer"
  (swap! !timing assoc :tap-times []))

(defn get-beat-position []
  "Get current position within beat (0.0-1.0)"
  (:beat-position @!timing))

(defn update-beat-position! [position]
  "Update current beat position"
  (swap! !timing assoc :beat-position position))

;; ============================================================================
;; Accessor Functions - Playback
;; ============================================================================

(defn is-playing? []
  "Check if playback is active"
  (:playing @!playback))

(defn start-playback! [animation]
  "Start playback with given animation"
  (swap! !playback assoc
         :playing true
         :current-animation animation
         :animation-start-time (System/currentTimeMillis)))

(defn stop-playback! []
  "Stop playback"
  (swap! !playback assoc
         :playing false
         :current-animation nil))

(defn get-current-animation []
  "Get currently playing animation"
  (:current-animation @!playback))

(defn set-active-cell! [col row]
  "Set the active grid cell"
  (swap! !playback assoc :active-cell [col row]))

(defn get-active-cell []
  "Get the active grid cell"
  (:active-cell @!playback))

;; ============================================================================
;; Accessor Functions - Streaming
;; ============================================================================

(defn is-streaming? []
  "Check if streaming is active"
  (:running? @!streaming))

(defn get-streaming-engines []
  "Get all streaming engines"
  (:engines @!streaming))

(defn add-streaming-engine! [projector-id engine]
  "Add a streaming engine"
  (swap! !streaming assoc-in [:engines projector-id] engine))

(defn remove-streaming-engine! [projector-id]
  "Remove a streaming engine"
  (swap! !streaming update :engines dissoc projector-id))

;; ============================================================================
;; Accessor Functions - Input
;; ============================================================================

(defn midi-enabled? []
  "Check if MIDI input is enabled"
  (get-in @!input [:midi :enabled]))

(defn enable-midi! [enabled]
  "Enable or disable MIDI input"
  (swap! !input assoc-in [:midi :enabled] enabled))

(defn osc-enabled? []
  "Check if OSC input is enabled"
  (get-in @!input [:osc :enabled]))

(defn enable-osc! [enabled]
  "Enable or disable OSC input"
  (swap! !input assoc-in [:osc :enabled] enabled))

;; ============================================================================
;; Accessor Functions - UI
;; ============================================================================

(defn get-grid-size []
  "Get current grid size [cols rows]"
  (get-in @!ui [:grid :size]))

(defn set-grid-size! [cols rows]
  "Set grid size"
  (swap! !ui assoc-in [:grid :size] [cols rows]))

(defn get-selected-preset []
  "Get currently selected preset"
  (:selected-preset @!ui))

(defn set-selected-preset! [preset-id]
  "Set selected preset"
  (swap! !ui assoc :selected-preset preset-id))

;; ============================================================================
;; State Reset (for testing)
;; ============================================================================

(defn reset-all-dynamic-state!
  "Reset all dynamic state to initial values. USE WITH CAUTION - mainly for testing."
  []
  (reset! !timing {:bpm 120.0
                   :tap-times []
                   :beat-position 0.0
                   :bar-position 0.0
                   :last-beat-time 0
                   :beats-elapsed 0
                   :quantization :beat})
  (reset! !playback {:playing false
                     :current-animation nil
                     :current-cue-id nil
                     :animation-start-time 0
                     :active-cell [nil nil]
                     :cue-queue []
                     :active-cue nil})
  (reset! !streaming {:engines {}
                      :running? false
                      :connected-targets #{}
                      :frame-stats {}
                      :multi-engine-state nil})
  (reset! !input {:midi {:enabled true
                         :connected-devices #{}
                         :learn-mode nil
                         :device nil
                         :receiver nil}
                  :osc {:enabled false
                        :server-running false
                        :learn-mode nil
                        :server nil
                        :port 8000}
                  :keyboard {:enabled true
                             :attached-components #{}}
                  :router {:handlers {}
                           :event-log []
                           :enabled true}})
  (reset! !ui {:selected-preset nil
               :selected-cell nil
               :clipboard nil
               :grid {:size [8 4]
                      :cell-states {}}
               :preview {:current-animation nil
                         :frame nil
                         :last-render-time 0}
               :active-tab :grid
               :window {:width 1200
                        :height 800}}))
