(ns laser-show.views.toolbar
  "Toolbar component with transport controls, BPM, beat indicator, and connection status."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.events.core :as events]
            [clj-font-awesome.core :as fa]
            [cljfx-svg.core :as svg]
            [laser-show.common.util :as u]))


;; Transport Controls


(defn transport-button
  "A transport control button (play/stop/etc).
   Uses style-class for base styling, inline :style only for dynamic active state."
  [{:keys [icon tooltip on-action active?]}]
  {:fx/type :button
   :id (case icon
         :play "play-btn"
         :stop "stop-btn"
         :rotate-right "retrigger-btn"
         "transport-btn")
   :graphic {:fx/type fa/icon
             :name icon
             :size 12}
   :style-class (if active? "transport-btn-active" "transport-btn")
   :tooltip {:fx/type :tooltip :text tooltip}
   :on-action on-action})

(defn- cues-text
  "\"1 cue\", \"4 cues\"."
  [n]
  (str n (if (= 1 n) " cue" " cues")))

(defn transport-controls
  "Play/Stop/Retrigger controls. Stop is the blackout: it holds the cues in
   play (subs/held-cue-count), and Play resumes them."
  [{:keys [fx/context]}]
  (let [playing? (fx/sub-ctx context subs/playing?)
        held (fx/sub-ctx context subs/held-cue-count)]
    {:fx/type :h-box
     :spacing 4
     :alignment :center-left
     :children [{:fx/type transport-button
                 :icon (if playing? :stop :play)
                 :tooltip (cond
                            playing? "Stop (blackout)"
                            (pos? held) (str "Resume " (cues-text held))
                            :else "Play")
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
        current-option (or (u/seek #(= (:id %) current-mode) trigger-mode-options)
                           (first trigger-mode-options))]
    {:fx/type :h-box
     :spacing 4
     :alignment :center-left
     :children [{:fx/type :label
                 :text "Trigger:"
                 :style-class "label-secondary"}
                {:fx/type :combo-box
                 :id "trigger-mode-selector"
                 :value current-option
                 :pref-width 90
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
                 :id "bpm-input"
                 :text (format "%.1f" (double bpm))
                 :pref-width 60
                 :style-class "text-field-dark-sm"
                 :on-action (fn [e]
                              (let [text (.getText (.getSource e))]
                                (try
                                  (let [new-bpm (Double/parseDouble text)]
                                   (events/dispatch! {:event/type :timing/set-bpm
                                                      :bpm new-bpm}))
                                 (catch NumberFormatException _
                                   nil))))}]}))

(defn tap-tempo-button
  "Tap tempo button."
  [{:keys [fx/context]}]
  {:fx/type :button
   :id "tap-tempo-btn"
   :pref-width 50
   :text "TAP"
   :graphic {:fx/type fa/icon
             :name :drum
             :size 16}
   :style-class "transport-btn"
   :tooltip {:fx/type :tooltip :text "Tap to set BPM"}
   :on-action {:event/type :timing/tap-tempo}})

(defn beat-square
  "Individual beat square in the indicator."
  [{:keys [index current-beat? downbeat?]}]
  {:fx/type :region
   :id (str "beat-square-" index)
   :pref-width 12
   :pref-height 12
   :style-class (cond-> ["beat-square"]
                  current-beat? (conj "beat-active")
                  downbeat? (conj "beat-downbeat"))})

(defn beat-indicator
  "4-square beat indicator showing the beat in the bar. It re-renders on each
   beat only: the show clock reports the beat (:show-clock/status), from
   effective beats, which glide smoothly during a tap tempo resync."
  [{:keys [fx/context]}]
  (let [beat-index (fx/sub-ctx context subs/beat-index)]
    {:fx/type :h-box
     :spacing 3
     :alignment :center
     :style-class "beat-indicator"
     :children (mapv (fn [i]
                       {:fx/type beat-square
                        :index i
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
       (fx/sub-ctx context subs/link-status)
       tooltip-text (cond
                      (and link-enabled? sync-enabled?)
                      (format "Syncing BPM from Ableton Link (%.1f BPM, %d peers)"
                              (or link-bpm 0.0) (or link-peers 0))
                      
                      link-enabled?
                      (format "Link enabled (%.1f BPM, %d peers)\nClick to enable BPM sync"
                              (or link-bpm 0.0) (or link-peers 0))
                      
                      carabiner-connected?
                      "Carabiner ready - Click to enable Link"
                      
                      :else
                      "Carabiner not connected")]
   {:fx/type :button
    :pref-width 50
    :graphic {:fx/type svg/icon
              :src "ableton.svg"
              :color :white
              :size 36}
    :style-class (if (and link-enabled? sync-enabled?)
                   "transport-btn-active"
                   "transport-btn")
    :tooltip {:fx/type :tooltip :text tooltip-text}
    :on-action {:event/type :timing/link-toggle}}))


(defn connection-indicator
  "Visual streaming status indicator (a style class per state)."
  [{:keys [state]}]
  {:fx/type :region
   :id "streaming-indicator"
   :pref-width 10
   :pref-height 10
   :style-class ["status-indicator"
                 (case state
                   :streaming "status-indicator-connected"
                   (:starting :stopping :idle) "status-indicator-connecting"
                   "status-indicator-disconnected")]})

(defn output-held-chip
  "\"OUTPUT OFF · 4 cues held\" while Stop holds cues: nothing is drawn, and
   Play resumes them. Out of the layout otherwise."
  [{:keys [fx/context]}]
  (let [held (fx/sub-ctx context subs/held-cue-count)]
    {:fx/type :label
     :id "output-held-chip"
     :text (str "OUTPUT OFF · " (cues-text held) " held")
     :style-class "output-held-chip"
     :tooltip {:fx/type :tooltip
               :text "Stop blacks the output out and keeps the cues. Play resumes them."}
     :visible (pos? held)
     :managed (pos? held)}))

(defn connection-status
  "Streaming status and the Stream/Stop button.
   
   The button follows the operator's intent ([:backend :streaming :running?]);
   the label says what the output runtime reports is actually streaming. On
   with no projector enabled, a link opens the Projectors tab."
  [{:keys [fx/context]}]
  (let [{:keys [running? state status-text errors]} (fx/sub-ctx context subs/connection-status)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children (filterv
                some?
                [{:fx/type connection-indicator
                  :state state}
                 (cond-> {:fx/type :label
                          :id "streaming-status-label"
                          :text status-text
                          :style-class "label-secondary"}
                   (seq errors) (assoc :tooltip {:fx/type :tooltip
                                                 :text (str/join "\n" errors)}))
                 (when (= :idle state)
                   {:fx/type :hyperlink
                    :id "streaming-projectors-link"
                    :text "Projectors…"
                    :style-class "toolbar-link"
                    :focus-traversable false
                    :on-action {:event/type :ui/set-active-tab :tab-id :projectors}})
                 {:fx/type :button
                  :id "stream-btn"
                  :text (if running? "Stop" "Stream")
                  :graphic {:fx/type fa/icon
                            :name :network-wired
                            :size 12}
                  :style-class "btn-sm"
                  :on-action {:event/type (if running?
                                            :idn/stop-multi-streaming
                                            :idn/start-multi-streaming)}}])}))



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
              {:fx/type output-held-chip}
              {:fx/type connection-status}]})
