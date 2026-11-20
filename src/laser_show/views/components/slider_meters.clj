(ns laser-show.views.components.slider-meters
  "The Perform tab's two meters, under the pads: where the faders are, 1
   Intensity and 2 Morph (plans/2026-11-18-vibe-mode/05). Each draws its
   fader from the show clock's newest tick (the snapshot's :faders, the
   values the render used: the control learned for each fader now,
   vibes.faders/fader-values) on each screen refresh
   (clock-view/ext-clock-redraw), so a fader never goes through app state:
   a bar and a percentage, \"–\" until the fader first moves or while no
   control is learned for it, and \"not wired\" (dimmed) while nothing the
   vibe that plays plays follows it (subs/vibe-meter)."
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.views.components.clock-view :as clock-view])
  (:import [javafx.scene Node]
           [javafx.scene.control Label ProgressBar]))

(defn meter-text
  "What a meter says: \"Intensity 45 %\", \"Intensity –\" before the
   fader's first value, then \" · not wired\" while nothing follows it."
  [label value wired?]
  (str label " " (if value (str (Math/round (* 100.0 (double value))) " %") "–")
       (when-not wired? " · not wired")))

(defn redraw-meter!
  "A meter, whose user data is {:slot :label :wired?}: its bar and its text
   from where the newest tick had the fader (snapshot :faders; none before
   the first tick)."
  [^Node node snapshot {:keys [slot label wired?]}]
  (let [value (get-in snapshot [:faders slot])]
    (when-let [^ProgressBar bar (.lookup node ".vibe-meter-bar")]
      (.setProgress bar (if value (double value) 0.0)))
    (when-let [^Label text (.lookup node ".vibe-meter-text")]
      (.setText text (meter-text label value wired?)))))

(defn- meter
  "One fader's meter (props: :slot)."
  [{:keys [fx/context slot]}]
  (let [{:keys [wired?] :as data} (fx/sub-ctx context subs/vibe-meter slot)
        id (str "vibe-meter-" (name slot))]
    {:fx/type clock-view/ext-clock-redraw
     :redraw! #'redraw-meter!
     :desc {:fx/type :v-box
            :id id
            :user-data data
            :style-class (cond-> ["vibe-meter"] (not wired?) (conj "vibe-meter-off"))
            :spacing 6
            :min-width 120
            :pref-width 120
            :max-width Double/MAX_VALUE
            :children [{:fx/type :label
                        :id (str id "-text")
                        :style-class "vibe-meter-text"
                        :text ""}
                       {:fx/type :progress-bar
                        :id (str id "-bar")
                        :style-class "vibe-meter-bar"
                        :max-width Double/MAX_VALUE
                        :progress 0.0}]}}))

(defn meter-row
  "The two meters, side by side, sharing the width."
  [_]
  {:fx/type :h-box
   :id "vibe-meter-row"
   :spacing 12
   :children [{:fx/type meter :slot :slider-1 :h-box/hgrow :always}
              {:fx/type meter :slot :slider-2 :h-box/hgrow :always}]})
