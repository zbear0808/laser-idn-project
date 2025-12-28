(ns laser-show.state.domains
  "State domain definitions for the laser show application.
   
   This file uses the defstate macro to declaratively define all state domains.
   Each domain generates:
   - Initial state map
   - Accessor functions (get-*, set-*!, update-*!)
   - Registration in the domain registry
   
   After loading this namespace, call (build-initial-state) to get the
   complete initial state map for the application."
  (:require [laser-show.state.core :refer [defstate build-initial-state-from-domains]]))

;; ============================================================================
;; UI State Domains (subscribed by components)
;; ============================================================================

(defstate timing
  "Timing and BPM management for animation synchronization."
  {:bpm {:default 120.0
         :doc "beats per minute"}
   :tap-times {:default []
               :doc "vector of tap-tempo timestamps for BPM calculation"}
   :beat-position {:default 0.0
                   :doc "position within current beat (0.0-1.0)"}
   :bar-position {:default 0.0
                  :doc "position within current bar (0.0-1.0)"}
   :quantization {:default :beat
                  :doc "quantization mode (:beat :bar :none)"}})

(defstate playback
  "Playback control state for animation triggering."
  {:playing? {:default false
              :doc "whether playback is active"}
   :trigger-time {:default 0
                  :doc "timestamp when current animation was triggered"}
   :active-cell {:default nil
                 :doc "[col row] of currently playing grid cell"}
   :active-cue {:default nil
                :doc "currently active cue data"}})

(defstate grid
  "Cue grid state - the main trigger interface."
  {:cells {:default {[0 0] {:preset-id :circle}
                     [1 0] {:preset-id :square}
                     [2 0] {:preset-id :triangle}
                     [3 0] {:preset-id :star}
                     [4 0] {:preset-id :spiral}
                     [5 0] {:preset-id :wave}
                     [6 0] {:preset-id :beam-fan}
                     [7 0] {:preset-id :rainbow-circle}}
           :doc "map of [col row] -> {:preset-id keyword}"}
   :selected-cell {:default nil
                   :doc "[col row] of selected cell for editing"}
   :size {:default [8 4]
          :doc "[cols rows] grid dimensions"}})

(defstate effects
  "Effects grid state - chains of effects per cell."
  {:cells {:default {}
           :doc "map of [col row] -> {:effects [...] :active true}"}})

(defstate ui
  "UI interaction state."
  {:selected-preset {:default nil
                     :doc "currently selected preset in browser"}
   :clipboard {:default nil
               :doc "clipboard data for copy/paste"}
   :active-tab {:default :grid
                :doc "currently active tab (:grid :effects :projectors :settings)"}
   :drag {:default {:active? false
                    :source-type nil
                    :source-key nil
                    :data nil}
          :doc "current drag operation state"}
   :dialogs {:default {:zone-editor {:open? false :zone-id nil}
                       :projector-config {:open? false}
                       :effect-editor {:open? false :cell nil}
                       :settings {:open? false}}
             :doc "dialog visibility and data states"}})

(defstate project
  "Project file management state."
  {:current-folder {:default nil
                    :doc "path to current project folder"}
   :dirty? {:default false
            :doc "whether changes have been made since last save"}
   :last-saved {:default nil
                :doc "timestamp of last save"}})

;; ============================================================================
;; Configuration Domains (persisted to disk)
;; ============================================================================

(defstate config
  "Application configuration settings."
  {:grid {:default {:cols 8 :rows 4}
          :doc "grid dimensions config"}
   :window {:default {:width 1200 :height 800}
            :doc "window dimensions"}
   :preview {:default {:width 400 :height 400}
             :doc "preview panel dimensions"}
   :idn {:default {:host "127.0.0.1" :port 7255}
         :doc "default IDN connection settings"}
   :osc {:default {:enabled false :port 8000}
         :doc "OSC server settings"}
   :midi {:default {:enabled true :device nil}
          :doc "MIDI input settings"}})

(defstate projectors
  "Projector configurations - map of projector-id to config."
  {:entries {:default {}
             :doc "map of projector-id -> {:name :host :port :zones}"}})

(defstate zones
  "Zone configurations for spatial mapping."
  {:entries {:default {}
             :doc "map of zone-id -> {:corners [...] :transform {...}}"}})

(defstate zone-groups
  "Zone group configurations for multi-zone output."
  {:entries {:default {}
             :doc "map of group-id -> {:name :zone-ids [...]}"}})

;; ============================================================================
;; Backend State Domains (not directly subscribed by UI)
;; ============================================================================

(defstate backend
  "Backend state for streaming and connections."
  {:idn {:default {:connected? false
                   :connecting? false
                   :target nil
                   :streaming-engine nil
                   :error nil}
         :doc "IDN connection state"}
   :streaming {:default {:running? false
                         :engines {}
                         :frame-stats {:last-render-ms 0
                                       :fps 0}}
               :doc "streaming engine state"}
   :input {:default {:midi {:enabled true
                            :device nil
                            :connected? false}
                     :osc {:enabled false
                           :server-running? false
                           :port 8000}
                     :keyboard {:enabled true}}
           :doc "input handling state"}
   :logging {:default {:enabled? false
                       :file nil
                       :path "idn-packets.log"}
             :doc "packet logging state"}})

;; ============================================================================
;; State Builder
;; ============================================================================

(defn build-initial-state
  "Build the complete initial state map from all registered domains.
   Call this after all defstate forms have been evaluated.
   
   Also includes :styles for CSS hot-reload support. Styles are stored
   in context for reactivity but not as a domain (they're view configuration,
   not application domain state)."
  []
  (merge
    (build-initial-state-from-domains)
    ;; Styles: stored in context for hot-reload reactivity
    ;; Initialized to nil, set at app startup with actual CSS URLs
    {:styles {:menu-theme nil}}))

;; ============================================================================
;; Convenience Re-exports
;; ============================================================================
;; 
;; The defstate macro generates accessor functions in this namespace.
;; For example, (defstate timing ...) generates:
;; - get-timing
;; - get-timing-bpm, set-timing-bpm!, update-timing-bpm!
;; - get-timing-tap-times, set-timing-tap-times!, update-timing-tap-times!
;; etc.
;;
;; These can be used directly:
;;   (require '[laser-show.state.domains :as d])
;;   (d/get-timing-bpm) => 120.0
;;   (d/set-timing-bpm! 140.0)

#_
(comment
  ;; Test the generated accessors
  (require '[laser-show.state.core :as core])
  
  ;; Initialize state
  (core/init-state! (build-initial-state))
  
  ;; Now we can use generated accessors
  (get-timing)              ;; => full timing map
  (get-timing-bpm)          ;; => 120.0
  (set-timing-bpm! 140.0)
  (get-timing-bpm)          ;; => 140.0
  
  (get-grid-cells)          ;; => {[0 0] {:preset-id :circle} ...}
  (get-grid-size)           ;; => [8 4]
  
  (get-playback-playing?)   ;; => false
  (set-playback-playing?! true)
  
  ;; Check registered domains
  (core/debug-domains)
  
  ;; Full state
  (core/debug-state)
  )
