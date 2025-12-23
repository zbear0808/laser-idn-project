(ns laser-show.state.atoms
  "Core atom definitions for runtime state.
   
   This file defines all runtime state atoms used by the application.
   These atoms are the single source of truth for volatile runtime state
   that is not persisted between sessions.
   
   Usage:
   - Import this namespace to access raw atoms directly
   - For higher-level operations, use laser-show.state.dynamic")

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
;; Initial State Definitions
;; ============================================================================

(def initial-timing-state
  {:bpm default-bpm
   :tap-times []
   :beat-position 0.0
   :bar-position 0.0
   :last-beat-time 0
   :beats-elapsed 0
   :quantization :beat})

(def initial-playback-state
  {:playing? false
   :trigger-time 0
   :active-cell nil
   :active-cue nil
   :cue-queue []})

(def initial-grid-state
  {:cells {[0 0] {:preset-id :circle}
           [1 0] {:preset-id :spinning-square}
           [2 0] {:preset-id :triangle}
           [3 0] {:preset-id :star}
           [4 0] {:preset-id :spiral}
           [5 0] {:preset-id :wave}
           [6 0] {:preset-id :beam-fan}
           [7 0] {:preset-id :rainbow-circle}}
   :selected-cell nil
   :size [default-grid-cols default-grid-rows]})

(def initial-streaming-state
  {:engines {}
   :running? false
   :connected-targets #{}
   :frame-stats {}
   :multi-engine-state nil})

(def initial-idn-state
  {:connected? false
   :target nil
   :streaming-engine nil})

(def initial-input-state
  {:midi {:enabled true
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

(def initial-ui-state
  {:selected-preset nil
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

(def initial-logging-state
  {:enabled? false
   :file nil
   :path default-log-path})

(def initial-effects-state
  {:active-effects {}})

;; ============================================================================
;; Atom Definitions
;; ============================================================================

(defonce !timing (atom initial-timing-state))

(defonce !playback (atom initial-playback-state))

(defonce !grid (atom initial-grid-state))

(defonce !streaming (atom initial-streaming-state))

(defonce !idn (atom initial-idn-state))

(defonce !input (atom initial-input-state))

(defonce !ui (atom initial-ui-state))

(defonce !logging (atom initial-logging-state))

(defonce !effects (atom initial-effects-state))

;; ============================================================================
;; Reset Functions
;; ============================================================================

(defn reset-timing! []
  (reset! !timing initial-timing-state))

(defn reset-playback! []
  (reset! !playback initial-playback-state))

(defn reset-grid! []
  (reset! !grid initial-grid-state))

(defn reset-streaming! []
  (reset! !streaming initial-streaming-state))

(defn reset-idn! []
  (reset! !idn initial-idn-state))

(defn reset-input! []
  (reset! !input initial-input-state))

(defn reset-ui! []
  (reset! !ui initial-ui-state))

(defn reset-logging! []
  (reset! !logging initial-logging-state))

(defn reset-effects! []
  (reset! !effects initial-effects-state))

(defn reset-all!
  "Reset all state atoms to their initial values.
   USE WITH CAUTION - mainly for testing."
  []
  (reset-timing!)
  (reset-playback!)
  (reset-grid!)
  (reset-streaming!)
  (reset-idn!)
  (reset-input!)
  (reset-ui!)
  (reset-logging!)
  (reset-effects!))
