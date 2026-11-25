(ns laser-show.views.toolbar
  "Toolbar component with transport controls, BPM, beat indicator, and connection status."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.views.components.commit-field :as commit-field]
            [laser-show.views.components.load-warning :as load-warning]
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
   ;; Mouse-only, as the rest of the toolbar: a click leaves the focus where
   ;; it was, so Space, R and Escape still reach the Transport menu
   ;; (keyboard plan 03, D6 A)
   :focus-traversable false
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
                 :focus-traversable false
                 :items trigger-mode-options
                 :button-cell (fn [mode]
                                {:text (or (:name mode) "Default")})
                 :cell-factory {:fx/cell-type :list-cell
                                :describe (fn [mode]
                                            {:text (or (:name mode) "Default")})}
                 :on-value-changed {:event/type :timing/set-global-trigger-mode}}]}))


;; BPM Controls


(defn- parse-bpm
  "The BPM typed into the field: a number above 0, or nil."
  [text]
  (when-let [bpm (parse-double (str/trim text))]
    (when (and (pos? bpm) (Double/isFinite bpm))
      bpm)))

(defn bpm-display
  "BPM display with editable value. It sets the BPM on Enter or when it
   loses focus; text that is no BPM puts the BPM back. Disabled while the
   app follows Link's tempo (Link on with Sync BPM): it shows Link's then,
   and a typed one would change nothing."
  [{:keys [fx/context]}]
  (let [bpm (fx/sub-ctx context subs/bpm)
        following-link? (boolean (fx/sub-ctx context subs/link-sync-enabled?))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text "BPM:"
                 :style-class "label-secondary"}
                {:fx/type commit-field/commit-field
                 :id "bpm-input"
                 :text (format "%.1f" (double bpm))
                 :disable following-link?
                 :pref-width 60
                 :style-class "text-field-dark-sm"
                 :on-commit (fn [typed]
                              (when-let [new-bpm (parse-bpm typed)]
                                {:event/type :timing/set-bpm
                                 :bpm new-bpm}))}]}))

(defn tap-tempo-button
  "Tap tempo button. Disabled while the app follows Link's tempo (Link on
   with Sync BPM): taps do nothing then, from any source."
  [{:keys [fx/context]}]
  {:fx/type :button
   :id "tap-tempo-btn"
   :disable (boolean (fx/sub-ctx context subs/link-sync-enabled?))
   :pref-width 50
   :text "TAP"
   :graphic {:fx/type fa/icon
             :name :drum
             :size 16}
   :style-class "transport-btn"
   :focus-traversable false
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
  "Connect Carabiner while it isn't connected (one click, as Settings'
   Connect: :timing/carabiner-connect; disabled while that connect waits);
   then enable Link; once enabled, toggle whether the app follows its
   tempo."
  [{:keys [fx/context]}]
  (let [{:keys [carabiner-connected? connecting link-enabled? sync-enabled? link-bpm link-peers]}
        (fx/sub-ctx context subs/link-status)
        tempo (if link-bpm (format "%.1f BPM" (double link-bpm)) "waiting for tempo")
        tooltip-text (cond
                       (and (not carabiner-connected?) connecting) "Connecting to Carabiner…"
                       (not carabiner-connected?) "Carabiner not connected — click to connect"
                       (not link-enabled?) "Carabiner ready - Click to enable Link"
                       :else (format "%s (%s, %d peers)\nClick to %s BPM sync"
                                     (if sync-enabled? "Syncing BPM from Ableton Link" "Link enabled")
                                     tempo (or link-peers 0) (if sync-enabled? "stop" "enable")))]
    {:fx/type :button
     :id "link-sync-btn"
     :disable (and (not carabiner-connected?) (some? connecting))
     :pref-width 50
     :graphic {:fx/type svg/icon :src "ableton.svg" :color :white :size 36}
     :style-class (if (and link-enabled? sync-enabled?) "transport-btn-active" "transport-btn")
     :focus-traversable false
     :tooltip {:fx/type :tooltip :text tooltip-text}
     :on-action {:event/type (cond
                               (not carabiner-connected?) :timing/carabiner-connect
                               link-enabled? :timing/toggle-link-sync
                               :else :timing/link-toggle)}}))


(defn connection-indicator
  "Visual streaming status indicator (a style class per state): green only
   while every wanted output streams; amber while they start, some still
   start (:mixed), they stop, or none is enabled."
  [{:keys [state]}]
  {:fx/type :region
   :id "streaming-indicator"
   :pref-width 10
   :pref-height 10
   :style-class ["status-indicator"
                 (case state
                   :streaming "status-indicator-connected"
                   (:starting :mixed :stopping :idle) "status-indicator-connecting"
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

(defn low-fps-chip
  "\"LOW FPS · Laser R 14 fps\" while a laser is still below its minimum fps
   after the fit guard (subs/low-fps-projectors, the lowest, and \" +1\" for
   each other one; zones plan Z3); a click opens that laser's card. A laser
   the guard thinned back up to its minimum gets no chip (Q-Z3-5 A). Out of
   the layout otherwise."
  [{:keys [fx/context]}]
  (let [lows (fx/sub-ctx context subs/low-fps-projectors)
        text (load-warning/low-fps-text lows)]
    {:fx/type :label
     :id "low-fps-chip"
     :text (or text "")
     :style-class "low-fps-chip"
     :tooltip {:fx/type :tooltip :text (load-warning/low-fps-tooltip lows)}
     :visible (some? text)
     :managed (some? text)
     :on-mouse-clicked {:event/type :ui/reveal-projector :projector-id (:id (first lows))}}))

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
                  :focus-traversable false
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
              {:fx/type low-fps-chip}
              {:fx/type connection-status}]})
