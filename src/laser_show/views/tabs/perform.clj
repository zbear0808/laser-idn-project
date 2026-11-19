(ns laser-show.views.tabs.perform
  "The Perform tab: vibe mode, made to read from two metres. The current
   vibe's name and colour with the tempo and the beat, the vibes to jump to,
   the five pads, and whether the output and the controllers are there. The
   preview stays beside it (views/root.clj).
   
   Each part subscribes to what it shows (subs.clj, Vibe mode), so a press
   re-renders its own pad, a vibe change the strip, the header and VIBE's
   pad, and a beat the tempo only. The banner is always there, hidden while
   it has nothing to say, so showing it never makes new nodes of the pads
   below it: a held pad keeps getting its release. The header flashes on
   each vibe change, from the show clock's newest tick."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.views.components.clock-view :as clock-view]
            [laser-show.views.components.vibe-pads :as vibe-pads]
            [laser-show.views.components.vibe-strip :as vibe-strip]))

(defn- banner
  "BLACKOUT, full width, and whether a cue lights the room anyway."
  [{:keys [fx/context]}]
  (let [{:keys [banner]} (fx/sub-ctx context subs/vibe-header)]
    {:fx/type :label
     :id "vibe-banner"
     :style-class "vibe-banner"
     :max-width Double/MAX_VALUE
     :wrap-text true
     :text (or banner "")
     :visible (some? banner)
     :managed (some? banner)}))

(defn- tempo
  "The tempo and the beat in the bar (subs/beat-index changes on each beat)."
  [{:keys [fx/context]}]
  (let [bpm (fx/sub-ctx context subs/bpm)
        beat (fx/sub-ctx context subs/beat-index)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-right
     :children (into [{:fx/type :label
                       :id "vibe-bpm"
                       :style-class "vibe-header-text"
                       :text (format "%.1f BPM" (double bpm))}]
                     (map (fn [i]
                            {:fx/type :region
                             :id (str "vibe-beat-" i)
                             :min-width 20
                             :min-height 20
                             :max-width 20
                             :max-height 20
                             :style-class (cond-> ["beat-square"]
                                            (= i beat) (conj "beat-active")
                                            (zero? i) (conj "beat-downbeat"))}))
                     (range 4))}))

(defn- header
  "The current vibe in its colour, its energy, the lock, the tempo, and what
   a learn waits for; a flash over it on each vibe change."
  [{:keys [fx/context]}]
  (let [{vibe-name :name :keys [color energy rotation learning]} (fx/sub-ctx context subs/vibe-header)
        locked? (= :locked rotation)]
    {:fx/type :stack-pane
     :children
     [{:fx/type :v-box
       :id "vibe-header"
       :style-class "vibe-header"
       :style (when color (str "-fx-background-color: " color ";"))
       :spacing 6
       :children [{:fx/type :h-box
                   :alignment :center-left
                   :spacing 16
                   :children [{:fx/type :label
                               :id "vibe-header-name"
                               :style-class "vibe-header-name"
                               :text (if vibe-name (str/upper-case vibe-name) "No vibes yet")}
                              {:fx/type :label
                               :id "vibe-header-locked"
                               :style-class "vibe-header-locked"
                               :text "LOCKED"
                               :visible locked?
                               :managed locked?}
                              {:fx/type :region
                               :h-box/hgrow :always}
                              {:fx/type tempo}]}
                  {:fx/type :h-box
                   :alignment :center-left
                   :spacing 16
                   :children [{:fx/type :label
                               :id "vibe-header-energy"
                               :style-class "vibe-header-text"
                               :text (if energy (str "energy " (vibe-strip/energy-dots energy)) "")}
                              {:fx/type :label
                               :id "vibe-learn-prompt"
                               :style-class "vibe-learn-prompt"
                               :text (if learning (str "Press the key for " (str/upper-case (name learning))) "")
                               :visible (some? learning)
                               :managed (some? learning)}]}]}
      {:fx/type clock-view/ext-clock-redraw
       :redraw! #'vibe-pads/redraw-pulse!
       :desc {:fx/type :region
              :id "vibe-header-flash"
              :user-data :vibe
              :opacity 0.0
              :mouse-transparent true
              :style-class "vibe-header-flash"}}]}))

(defn- status-line
  "Whether the output streams and which MIDI controllers are connected."
  [{:keys [fx/context]}]
  (let [{:keys [status-text]} (fx/sub-ctx context subs/connection-status)
        devices (fx/sub-ctx context subs/midi-connected-devices)]
    {:fx/type :h-box
     :id "vibe-status"
     :spacing 24
     :alignment :center-left
     :children [{:fx/type :label
                 :id "vibe-output"
                 :style-class "vibe-status"
                 :text (str "Output: " status-text)}
                {:fx/type :label
                 :id "vibe-midi"
                 :style-class (cond-> ["vibe-status"] (empty? devices) (conj "vibe-status-none"))
                 :text (if (seq devices)
                         (str "MIDI: " (str/join ", " (sort devices)) " ✓")
                         "MIDI: none connected")}]}))

(defn perform-tab
  "The Perform tab: banner, header, vibe strip, pads and status line."
  [_]
  {:fx/type :v-box
   :id "perform-content"
   :spacing 14
   :padding 16
   :children [{:fx/type banner}
              {:fx/type header}
              {:fx/type vibe-strip/vibe-strip}
              {:fx/type vibe-pads/pad-row :v-box/vgrow :always}
              {:fx/type status-line}]})
