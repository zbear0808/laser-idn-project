(ns laser-show.state.domains
  "State domain definitions for the laser show application.
   
   This file uses the defstate macro to declaratively define all state domains.
   Each domain generates:
   - Initial state map
   - Accessor functions (get-*, set-*!, update-*!)
   - Registration in the domain registry
   
   After loading this namespace, call (build-initial-state) to get the
   complete initial state map for the application.
   
   NOTE: This is the single source of truth for ALL application state."
  (:require [laser-show.state.core :refer [defstate build-initial-state-from-domains]]))


;; Default Constants


(def default-bpm 120.0)
(def default-osc-port 8000)
(def default-window-width 1200)
(def default-window-height 800)
(def default-grid-cols 8)
(def default-grid-rows 4)
(def default-log-path "idn-packets.log")


;; Core UI State Domains


(defstate timing
  "Timing and BPM management for animation synchronization."
  {:bpm {:default default-bpm
         :doc "beats per minute"}
   :tap-times {:default []
               :doc "vector of tap-tempo timestamps for BPM calculation"}
   :beat-position {:default 0.0
                   :doc "position within current beat (0.0-1.0)"}
   :bar-position {:default 0.0
                  :doc "position within current bar (0.0-1.0)"}
   :last-beat-time {:default 0
                    :doc "timestamp of last beat"}
   :beats-elapsed {:default 0
                   :doc "total beats elapsed since start"}
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
                :doc "currently active cue data"}
   :cue-queue {:default []
               :doc "queue of pending cues"}})

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
   :size {:default [default-grid-cols default-grid-rows]
          :doc "[cols rows] grid dimensions"}})

(defstate effects
  "Effects grid state - chains of effects per cell."
  {:cells {:default {}
           :doc "map of [col row] -> {:effects [...] :active true}"}})

(defstate ui
  "UI interaction state including window, preview, and component references."
  {:selected-preset {:default nil
                     :doc "currently selected preset in browser"}
   :clipboard {:default nil
               :doc "clipboard data for copy/paste"}
   :active-tab {:default :grid
                :doc "currently active tab (:grid :effects :projectors :settings)"}
   :drag {:default {:active? false
                    :source-type nil
                    :source-id nil
                    :source-key nil
                    :data nil}
          :doc "current drag operation state"}
   :dialogs {:default {:zone-editor {:open? false :zone-id nil}
                       :projector-config {:open? false}
                       :effect-editor {:open? false :cell nil}
                       :settings {:open? false}}
             :doc "dialog visibility and data states"}
   :preview {:default {:frame nil
                       :last-render-time 0}
             :doc "preview panel state"}
   :window {:default {:width default-window-width
                      :height default-window-height}
            :doc "window dimensions"}
   :components {:default {:main-frame nil
                          :preview-panel nil
                          :grid-panel nil
                          :effects-panel nil
                          :status-bar nil
                          :toolbar nil}
                :doc "references to UI components for programmatic access"}})

(defstate project
  "Project file management state."
  {:current-folder {:default nil
                    :doc "path to current project folder"}
   :dirty? {:default false
            :doc "whether changes have been made since last save"}
   :last-saved {:default nil
                :doc "timestamp of last save"}})


;; Configuration Domains (persisted to disk)


(defstate config
  "Application configuration settings."
  {:grid {:default {:cols default-grid-cols :rows default-grid-rows}
          :doc "grid dimensions config"}
   :window {:default {:width default-window-width :height default-window-height}
            :doc "window dimensions"}
   :preview {:default {:width 400 :height 400}
             :doc "preview panel dimensions"}
   :idn {:default {:host nil :port 7255}
         :doc "default IDN connection settings"}
   :osc {:default {:enabled false :port default-osc-port}
         :doc "OSC server settings"}
   :midi {:default {:enabled false :device nil}
          :doc "MIDI input settings"}})

(defstate projectors
  "Projector configurations."
  {:items {:default {}
           :doc "map of projector-id -> {:name :host :port :zones ...}"}})

(defstate zones
  "Zone configurations for spatial mapping."
  {:items {:default {}
           :doc "map of zone-id -> {:corners [...] :transform {...} ...}"}})

(defstate zone-groups
  "Zone group configurations for multi-zone output."
  {:items {:default {}
           :doc "map of group-id -> {:name :zone-ids [...]}"}})

(defstate cues
  "Cue definitions."
  {:items {:default {}
           :doc "map of cue-id -> {:name :preset-id :animation ...}"}})

(defstate cue-lists
  "Cue list definitions."
  {:items {:default {}
           :doc "map of list-id -> {:name :cues [...]}"}})

(defstate effect-registry
  "Effect type registry."
  {:items {:default {}
           :doc "map of effect-id -> effect-definition"}})


;; Backend State Domain


(defstate backend
  "Backend services state - IDN, streaming, input, logging."
  {:idn {:default {:connected? false
                   :connecting? false
                   :target nil
                   :streaming-engine nil
                   :error nil}
         :doc "IDN connection state"}
   :streaming {:default {:engines {}
                         :running? false
                         :connected-targets #{}
                         :frame-stats {}
                         :multi-engine-state nil
                         :current-frame nil}
               :doc "Streaming engine state for IDN output"}
   :input {:default {:midi {:enabled true
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
                              :enabled true}}
           :doc "Input handling state for MIDI, OSC, and keyboard"}
   :logging {:default {:enabled? false
                       :file nil
                       :path default-log-path}
             :doc "Packet logging state"}})


;; State Builder


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
