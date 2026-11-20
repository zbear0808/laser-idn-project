(ns laser-show.views.tabs.perform
  "The Perform tab: vibe mode, made to read from two metres. The current
   vibe's name and colour with the tempo and the beat, the vibes to jump to,
   the five pads, where the two faders are, and whether the output and the
   controllers are there. The preview stays beside it (views/root.clj).
   
   Each part subscribes to what it shows (subs.clj, Vibe mode), so a press
   re-renders its own pad, a vibe change the strip, the header and VIBE's
   pad, and a beat the tempo only. The banners are always there, hidden
   while they have nothing to say, so showing one never makes new nodes of
   the pads below it: a held pad keeps getting its release. The header
   flashes on each vibe change, from the show clock's newest tick. The show
   lock's switch is in the header (events/show_lock.clj)."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.views.components.clock-view :as clock-view]
            [laser-show.views.components.slider-meters :as slider-meters]
            [laser-show.views.components.vibe-pads :as vibe-pads]
            [laser-show.views.components.vibe-rotation :as vibe-rotation]
            [laser-show.views.components.vibe-strip :as vibe-strip]
            [laser-show.views.components.zone-map-pads :as zone-map-pads]))

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

(defn- output-banner
  "NO OUTPUT, full width, once vibe mode has played and while nothing goes
   to the lasers (the toolbar's status): why a press shows nothing."
  [{:keys [fx/context]}]
  (let [text (fx/sub-ctx context subs/vibe-output-banner)]
    {:fx/type :label
     :id "vibe-no-output"
     :style-class "vibe-banner"
     :max-width Double/MAX_VALUE
     :wrap-text true
     :text (or text "")
     :visible (some? text)
     :managed (some? text)}))

(defn- show-lock-controls
  "The show lock switch (:vibe/set-show-lock), and what it refused last."
  [{:keys [fx/context]}]
  (let [{:keys [locked? notice]} (fx/sub-ctx context subs/vibe-show-lock)]
    {:fx/type :h-box
     :spacing 12
     :alignment :center-right
     :children [{:fx/type :label
                 :id "vibe-lock-notice"
                 :style-class "vibe-lock-notice"
                 :text (or notice "")
                 :visible (some? notice)
                 :managed (some? notice)}
                {:fx/type :toggle-button
                 :id "vibe-show-lock"
                 :text "SHOW LOCK"
                 :style-class ["toggle-button" "vibe-show-lock"]
                 :focus-traversable false
                 :selected locked?
                 :on-action {:event/type :vibe/set-show-lock :locked? (not locked?)}}]}))

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
  "The current vibe in its colour, its energy, the lock, whether it may
   reach the audience, the tempo, what a learn waits for, auto-advance (its
   AUTO switch and countdown) and the show lock; a flash over it on each
   vibe change."
  [{:keys [fx/context]}]
  (let [{vibe-name :name :keys [color energy rotation learning]} (fx/sub-ctx context subs/vibe-header)
        locked? (= :locked rotation)
        audience? (fx/sub-ctx context subs/vibe-audience?)]
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
                              {:fx/type :label
                               :id "vibe-header-audience"
                               :style-class "vibe-header-audience"
                               :text "AUDIENCE ALLOWED"
                               :visible audience?
                               :managed audience?}
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
                               :managed (some? learning)}
                              {:fx/type :region
                               :h-box/hgrow :always}
                              {:fx/type vibe-rotation/rotation-controls}
                              {:fx/type show-lock-controls}]}]}
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
  "The Perform tab: the BLACKOUT and NO OUTPUT banners, header, vibe strip,
   pads, the faders' meters, the zone map pads (zones plan Z4) and status
   line."
  [_]
  {:fx/type :v-box
   :id "perform-content"
   :spacing 14
   :padding 16
   :children [{:fx/type banner}
              {:fx/type output-banner}
              {:fx/type header}
              {:fx/type vibe-strip/vibe-strip}
              {:fx/type vibe-pads/pad-row :v-box/vgrow :always}
              {:fx/type slider-meters/meter-row}
              {:fx/type zone-map-pads/map-pad-row}
              {:fx/type status-line}]})
