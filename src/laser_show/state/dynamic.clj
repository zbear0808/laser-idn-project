(ns laser-show.state.dynamic
  "Dynamic runtime state for the laser show application.
   This state is volatile and not persisted between sessions.
   
   This is the single source of truth for all runtime state.")

;; ============================================================================
;; Default Constants
;; ============================================================================

(def default-bpm 120.0)
(def default-osc-port 8000)
(def default-window-width 1200)
(def default-window-height 800)
(def default-grid-cols 8)
(def default-grid-rows 4)
(def default-log-path "idn-packets.log")

;; ============================================================================
;; Timing State
;; ============================================================================

(defonce !timing
  (atom {:bpm default-bpm
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
  (atom {:playing? false
         :trigger-time 0              ; KEY for retriggering - timestamp when cue was triggered
         :active-cell nil             ; [col row] of active cell, or nil
         :active-cue nil              ; Currently playing cue
         :cue-queue []}))             ; Queue of upcoming cues

;; ============================================================================
;; Grid State (Cue assignments and cells)
;; ============================================================================

(defonce !grid
  (atom {:cells {[0 0] {:preset-id :circle}
                 [1 0] {:preset-id :spinning-square}
                 [2 0] {:preset-id :triangle}
                 [3 0] {:preset-id :star}
                 [4 0] {:preset-id :spiral}
                 [5 0] {:preset-id :wave}
                 [6 0] {:preset-id :beam-fan}
                 [7 0] {:preset-id :rainbow-circle}}
         :selected-cell nil
         :size [8 4]}))

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
;; IDN / Network State
;; ============================================================================

(defonce !idn
  (atom {:connected? false
         :target nil
         :streaming-engine nil}))

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
               :port default-osc-port}
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
         :clipboard nil
         :preview {:frame nil
                   :last-render-time 0}
         :active-tab :grid
         :window {:width default-window-width
                  :height default-window-height}
         :drag {:active? false      ; Is a drag currently in progress?
                :source-type nil    ; :grid-cell, :effect-cell, :preset, etc.
                :source-id nil      ; Grid identifier
                :source-key nil     ; [col row] of source cell
                :data nil}          ; The data being dragged
         :components {:main-frame nil
                      :preview-panel nil
                      :grid-panel nil
                      :effects-panel nil
                      :status-bar nil
                      :toolbar nil}}))

;; ============================================================================
;; Logging State
;; ============================================================================

(defonce !logging
  (atom {:enabled? false
         :file nil
         :path default-log-path}))

;; ============================================================================
;; Effects Grid State
;; ============================================================================

(defonce !effects
  (atom {:active-effects {}}))        ; {[col row] effect-data}

;; ============================================================================
;; Accessor Functions - Timing
;; ============================================================================

(defn get-bpm []
  (:bpm @!timing))

(defn set-bpm! [bpm]
  (swap! !timing assoc :bpm (double bpm)))

(defn get-tap-times []
  (:tap-times @!timing))

(defn add-tap-time! [timestamp]
  (swap! !timing update :tap-times conj timestamp))

(defn clear-tap-times! []
  (swap! !timing assoc :tap-times []))

(defn get-beat-position []
  (:beat-position @!timing))

(defn update-beat-position! [position]
  (swap! !timing assoc :beat-position position))

;; ============================================================================
;; Accessor Functions - Playback
;; ============================================================================

(defn playing? []
  (:playing? @!playback))

(defn get-trigger-time []
  (:trigger-time @!playback))

(defn trigger!
  "Set trigger-time to current timestamp. KEY function for retriggering animations."
  []
  (swap! !playback assoc :trigger-time (System/currentTimeMillis)))

(defn set-trigger-time! [time-ms]
  (swap! !playback assoc :trigger-time time-ms))

(defn start-playback!
  "Start playback and reset trigger time."
  []
  (swap! !playback #(-> %
                        (assoc :playing? true)
                        (assoc :trigger-time (System/currentTimeMillis)))))

(defn stop-playback! []
  (swap! !playback #(-> %
                        (assoc :playing? false)
                        (assoc :active-cell nil))))

(defn get-active-cell []
  (:active-cell @!playback))

(defn set-active-cell! [col row]
  (swap! !playback assoc :active-cell (when (and col row) [col row])))

(defn get-active-cue []
  (:active-cue @!playback))

(defn set-active-cue! [cue]
  (swap! !playback assoc :active-cue cue))

(defn trigger-cell!
  "Trigger a grid cell - sets it as active and updates trigger time."
  [col row]
  (swap! !playback #(-> %
                        (assoc :active-cell [col row])
                        (assoc :playing? true)
                        (assoc :trigger-time (System/currentTimeMillis)))))

;; ============================================================================
;; Accessor Functions - Grid
;; ============================================================================

(defn get-grid-cells []
  (:cells @!grid))

(defn get-cell [col row]
  (get-in @!grid [:cells [col row]]))

(defn set-cell! [col row cell-data]
  (swap! !grid assoc-in [:cells [col row]] cell-data))

(defn set-cell-preset! [col row preset-id]
  (swap! !grid assoc-in [:cells [col row]] {:preset-id preset-id}))

(defn clear-cell! [col row]
  (swap! !grid update :cells dissoc [col row]))

(defn get-selected-cell []
  (:selected-cell @!grid))

(defn set-selected-cell! [col row]
  (swap! !grid assoc :selected-cell (when (and col row) [col row])))

(defn clear-selected-cell! []
  (swap! !grid assoc :selected-cell nil))

(defn get-grid-size []
  (:size @!grid))

(defn set-grid-size! [cols rows]
  (swap! !grid assoc :size [cols rows]))

(defn move-cell! [from-col from-row to-col to-row]
  (swap! !grid (fn [grid]
                 (let [cell-data (get-in grid [:cells [from-col from-row]])]
                   (if cell-data
                     (-> grid
                         (update :cells dissoc [from-col from-row])
                         (assoc-in [:cells [to-col to-row]] cell-data))
                     grid)))))

;; ============================================================================
;; Accessor Functions - Streaming
;; ============================================================================

(defn streaming? []
  (:running? @!streaming))

(defn get-streaming-engines []
  (:engines @!streaming))

(defn add-streaming-engine! [projector-id engine]
  (swap! !streaming assoc-in [:engines projector-id] engine))

(defn remove-streaming-engine! [projector-id]
  (swap! !streaming update :engines dissoc projector-id))

;; ============================================================================
;; Accessor Functions - IDN
;; ============================================================================

(defn idn-connected? []
  (:connected? @!idn))

(defn get-idn-target []
  (:target @!idn))

(defn set-idn-connection! [connected? target engine]
  (reset! !idn {:connected? connected?
                :target target
                :streaming-engine engine}))

;; ============================================================================
;; Accessor Functions - Input
;; ============================================================================

(defn midi-enabled? []
  (get-in @!input [:midi :enabled]))

(defn enable-midi! [enabled]
  (swap! !input assoc-in [:midi :enabled] enabled))

(defn osc-enabled? []
  (get-in @!input [:osc :enabled]))

(defn enable-osc! [enabled]
  (swap! !input assoc-in [:osc :enabled] enabled))

;; ============================================================================
;; Accessor Functions - UI
;; ============================================================================

(defn get-selected-preset []
  (:selected-preset @!ui))

(defn set-selected-preset! [preset-id]
  (swap! !ui assoc :selected-preset preset-id))

(defn get-clipboard []
  (:clipboard @!ui))

(defn set-clipboard! [data]
  (swap! !ui assoc :clipboard data))

(defn get-ui-component [component-key]
  (get-in @!ui [:components component-key]))

(defn set-ui-component! [component-key component]
  (swap! !ui assoc-in [:components component-key] component))

(defn get-main-frame []
  (get-ui-component :main-frame))

(defn set-main-frame! [frame]
  (set-ui-component! :main-frame frame))

;; ============================================================================
;; Accessor Functions - Drag State
;; ============================================================================

(defn dragging? []
  (get-in @!ui [:drag :active?]))

(defn get-drag-data []
  (get-in @!ui [:drag]))

(defn start-drag!
  "Start a drag operation.
   Parameters:
   - source-type: :grid-cell, :effect-cell, :preset, etc.
   - source-id: identifier for the source grid
   - source-key: [col row] of source cell
   - data: the data being dragged"
  [source-type source-id source-key data]
  (swap! !ui assoc :drag {:active? true
                          :source-type source-type
                          :source-id source-id
                          :source-key source-key
                          :data data}))

(defn end-drag!
  "End the current drag operation."
  []
  (swap! !ui assoc :drag {:active? false
                          :source-type nil
                          :source-id nil
                          :source-key nil
                          :data nil}))

;; ============================================================================
;; Accessor Functions - Logging
;; ============================================================================

(defn logging-enabled? []
  (:enabled? @!logging))

(defn set-logging-enabled! [enabled]
  (swap! !logging assoc :enabled? enabled))

(defn get-log-path []
  (:path @!logging))

;; ============================================================================
;; Accessor Functions - Effects
;; ============================================================================

(defn get-active-effects []
  (:active-effects @!effects))

(defn get-effect-at [col row]
  (get-in @!effects [:active-effects [col row]]))

(defn set-effect-at! [col row effect-data]
  (swap! !effects assoc-in [:active-effects [col row]] effect-data))

(defn clear-effect-at! [col row]
  (swap! !effects update :active-effects dissoc [col row]))

;; ============================================================================
;; State Reset (for testing)
;; ============================================================================

(defn reset-all-dynamic-state!
  "Reset all dynamic state to initial values. USE WITH CAUTION - mainly for testing."
  []
  (reset! !timing {:bpm default-bpm
                   :tap-times []
                   :beat-position 0.0
                   :bar-position 0.0
                   :last-beat-time 0
                   :beats-elapsed 0
                   :quantization :beat})
  (reset! !playback {:playing? false
                     :trigger-time 0
                     :active-cell nil
                     :active-cue nil
                     :cue-queue []})
  (reset! !grid {:cells {[0 0] {:preset-id :circle}
                         [1 0] {:preset-id :spinning-square}
                         [2 0] {:preset-id :triangle}
                         [3 0] {:preset-id :star}
                         [4 0] {:preset-id :spiral}
                         [5 0] {:preset-id :wave}
                         [6 0] {:preset-id :beam-fan}
                         [7 0] {:preset-id :rainbow-circle}}
                 :selected-cell nil
                 :size [8 4]})
  (reset! !streaming {:engines {}
                      :running? false
                      :connected-targets #{}
                      :frame-stats {}
                      :multi-engine-state nil})
  (reset! !idn {:connected? false
                :target nil
                :streaming-engine nil})
  (reset! !input {:midi {:enabled true
                         :connected-devices #{}
                         :learn-mode nil
                         :device nil
                         :receiver nil}
                  :osc {:enabled false
                        :server-running false
                        :learn-mode nil
                        :server nil
                        :port default-osc-port}
                  :keyboard {:enabled true
                             :attached-components #{}}
                  :router {:handlers {}
                           :event-log []
                           :enabled true}})
  (reset! !ui {:selected-preset nil
               :clipboard nil
               :preview {:frame nil
                         :last-render-time 0}
               :active-tab :grid
               :window {:width default-window-width
                        :height default-window-height}
               :drag {:active? false
                      :source-type nil
                      :source-id nil
                      :source-key nil
                      :data nil}
               :components {:main-frame nil
                            :preview-panel nil
                            :grid-panel nil
                            :effects-panel nil
                            :status-bar nil
                            :toolbar nil}})
  (reset! !logging {:enabled? false
                    :file nil
                    :path default-log-path})
  (reset! !effects {:active-effects {}}))
