(ns laser-show.state.dynamic
  "Dynamic runtime state accessor functions for the laser show application.
   
   This namespace provides accessor functions for all runtime state.
   The actual atom definitions are in laser-show.state.atoms.
   
   This is the public API for runtime state access."
  (:require [laser-show.state.atoms :as atoms]))

;; ============================================================================
;; Re-export Constants for Backward Compatibility
;; ============================================================================

(def default-bpm atoms/default-bpm)
(def default-osc-port atoms/default-osc-port)
(def default-window-width atoms/default-window-width)
(def default-window-height atoms/default-window-height)
(def default-grid-cols atoms/default-grid-cols)
(def default-grid-rows atoms/default-grid-rows)
(def default-log-path atoms/default-log-path)

;; ============================================================================
;; Re-export Atoms for Direct Access (when needed by watchers, etc.)
;; ============================================================================

(def !timing atoms/!timing)
(def !playback atoms/!playback)
(def !grid atoms/!grid)
(def !streaming atoms/!streaming)
(def !idn atoms/!idn)
(def !input atoms/!input)
(def !ui atoms/!ui)
(def !logging atoms/!logging)
(def !effects atoms/!effects)

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
