(ns laser-show.views.toolbar
  "Toolbar component with transport controls, BPM, beat indicator, and connection status."
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [clj-font-awesome.core :as fa]))


;; Transport Controls


(defn transport-button
  "A transport control button (play/stop/etc).
   Uses style-class for base styling, inline :style only for dynamic active state."
  [{:keys [icon tooltip on-action active?]}]
  {:fx/type :button
   :graphic {:fx/type fa/icon
             :name icon
             :size 12}
   :style-class (if active? "transport-btn-active" "transport-btn")
   :tooltip {:fx/type :tooltip :text tooltip}
   :on-action on-action})

(defn transport-controls
  "Play/Stop/Retrigger controls."
  [{:keys [fx/context]}]
  (let [playing? (fx/sub-ctx context subs/playing?)]
    {:fx/type :h-box
     :spacing 4
     :alignment :center-left
     :children [{:fx/type transport-button
                 :icon (if playing? :stop :play)
                 :tooltip (if playing? "Stop" "Play")
                 :on-action {:event/type (if playing? :transport/stop :transport/play)}
                 :active? playing?}
                {:fx/type transport-button
                 :icon :rotate-right
                 :tooltip "Retrigger"
                 :on-action {:event/type :transport/retrigger}
                 :active? false}]}))


;; Global Trigger Mode Selector


(def trigger-mode-options
  "Options for global trigger mode dropdown."
  [{:id :default :name "Default"}
   {:id :toggle :name "Toggle"}
   {:id :retrigger :name "Retrigger"}])

(defn trigger-mode-selector
  "Global trigger mode selector dropdown.
   
   Modes:
   - Default: Use per-cue trigger mode settings
   - Toggle: Override all cues to click ON/OFF behavior
   - Retrigger: Override all cues to always restart behavior"
  [{:keys [fx/context]}]
  (let [current-mode (fx/sub-ctx context subs/global-trigger-mode)
        current-option (or (first (filter #(= (:id %) current-mode) trigger-mode-options))
                           (first trigger-mode-options))]
    {:fx/type :h-box
     :spacing 4
     :alignment :center-left
     :children [{:fx/type :label
                 :text "Trigger:"
                 :style-class "label-secondary"}
                {:fx/type :combo-box
                 :value current-option
                 :pref-width 90
                 :style-class "combo-box-dark"
                 :items trigger-mode-options
                 :button-cell (fn [mode]
                                {:text (or (:name mode) "Default")})
                 :cell-factory {:fx/cell-type :list-cell
                                :describe (fn [mode]
                                            {:text (or (:name mode) "Default")})}
                 :on-value-changed {:event/type :timing/set-global-trigger-mode}}]}))


;; BPM Controls


(defn bpm-display
  "BPM display with editable value."
  [{:keys [fx/context]}]
  (let [bpm (fx/sub-ctx context subs/bpm)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text "BPM:"
                 :style-class "label-secondary"}
                {:fx/type :text-field
                 :text (format "%.1f" (double bpm))
                 :pref-width 60
                 :style-class "text-field-dark-sm"
                 :on-action (fn [e]
                              (let [text (.getText (.getSource e))]
                                (try
                                  (let [new-bpm (Double/parseDouble text)]
                                    {:event/type :timing/set-bpm :bpm new-bpm})
                                  (catch NumberFormatException _
                                    nil))))}]}))

(defn tap-tempo-button
  "Tap tempo button."
  [{:keys [fx/context]}]
  {:fx/type :button
   :text "TAP"
   :graphic {:fx/type fa/icon
             :name :drum
             :size 12}
   :style-class "btn-sm"
   :tooltip {:fx/type :tooltip :text "Tap to set BPM"}
   :on-action {:event/type :timing/tap-tempo}})

(defn beat-square
  "Individual beat square in the indicator."
  [{:keys [current-beat? downbeat?]}]
  {:fx/type :region
   :pref-width 12
   :pref-height 12
   :style-class (cond-> ["beat-square"]
                  current-beat? (conj "beat-active")
                  downbeat? (conj "beat-downbeat"))})

(defn beat-indicator
  "4-square beat indicator showing current beat position.
   Uses effective-beats which smoothly adjusts during tap tempo resync."
  [{:keys [fx/context]}]
  (let [{:keys [beat-index]} (fx/sub-ctx context subs/beat-position)]
    {:fx/type :h-box
     :spacing 3
     :alignment :center
     :style-class "beat-indicator"
     :children (mapv (fn [i]
                       {:fx/type beat-square
                        :current-beat? (= i beat-index)
                        :downbeat? (= i 0)})
                     (range 4))}))

(defn bpm-controls
  "BPM display, beat indicator, and tap tempo."
  [{:keys [fx/context]}]
  {:fx/type :h-box
   :spacing 8
   :alignment :center-left
   :children [{:fx/type bpm-display}
              {:fx/type beat-indicator}
              {:fx/type tap-tempo-button}]})


;; Link Sync Controls


(defn link-sync-button
 "Ableton Link sync button with status indicator."
 [{:keys [fx/context]}]
 (let [{:keys [carabiner-connected? link-enabled? sync-enabled? link-bpm link-peers]}
       (fx/sub-ctx context subs/link-status)]
   {:fx/type :button
    :text "LINK"
    :graphic {:fx/type fa/icon
              :name :link
              :size 12}
    :style-class (cond
                   (and link-enabled? sync-enabled?) "btn-link-active"
                   link-enabled? "btn-link-connected"
                   :else "btn-sm")
    :tooltip {:fx/type :tooltip
              :text (cond
                      (and link-enabled? sync-enabled?)
                      (format "Syncing BPM from Ableton Link (%.1f BPM, %d peers)"
                              (or link-bpm 0.0) (or link-peers 0))
                      
                      link-enabled?
                      (format "Link enabled (%.1f BPM, %d peers)\nClick to enable BPM sync"
                              (or link-bpm 0.0) (or link-peers 0))
                      
                      carabiner-connected?
                      "Carabiner ready - Click to enable Link"
                      
                      :else
                      "Carabiner not connected")}
    :on-action {:event/type :timing/link-toggle}}))


(defn connection-indicator
  "Visual connection status indicator.
   Uses dynamic inline style for color based on connection state."
  [{:keys [connected? connecting?]}]
  {:fx/type :region
   :pref-width 10
   :pref-height 10
   :style-class ["status-indicator"
                 (cond
                   connected? "status-indicator-connected"
                   connecting? "status-indicator-connecting"
                   :else "status-indicator-disconnected")]})

(defn connection-status
  "Connection status display for multi-projector streaming.
   
   Uses zone-aware streaming to all enabled projectors based on their
   zone group assignments. Legacy single-target streaming is deprecated."
  [{:keys [fx/context]}]
  (let [{:keys [connected? connecting? status-text]} (fx/sub-ctx context subs/connection-status)
        ;; Get enabled projector count for display
        projectors (fx/sub-val context :projectors)
        enabled-count (count (filter (fn [[_ p]] (:enabled? p)) projectors))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type connection-indicator
                 :connected? connected?
                 :connecting? connecting?}
                {:fx/type :label
                 :text (if connected?
                         (format "Streaming to %d projector(s)" enabled-count)
                         status-text)
                 :style-class "label-secondary"}
                {:fx/type :button
                  :text (if connected? "Stop" "Stream")
                  :graphic {:fx/type fa/icon
                            :name :network-wired
                            :size 12}
                  :style-class "btn-sm"
                  :on-action {:event/type (if connected?
                                            :idn/stop-multi-streaming
                                            :idn/start-multi-streaming)}}]}))



(defn toolbar
  "Main toolbar component."
  [{:keys [fx/context]}]
  {:fx/type :h-box
   :style-class "toolbar"
   :alignment :center-left
   :children [{:fx/type transport-controls}
              {:fx/type trigger-mode-selector}
              {:fx/type :separator :orientation :vertical}
              {:fx/type bpm-controls}
              {:fx/type link-sync-button}
              {:fx/type :separator :orientation :vertical}
              {:fx/type :region :h-box/hgrow :always} ;; Spacer
              {:fx/type connection-status}]})
