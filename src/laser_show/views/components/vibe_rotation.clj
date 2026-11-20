(ns laser-show.views.components.vibe-rotation
  "The Perform tab's auto-advance (plans/2026-11-18-vibe-mode/06): the AUTO
   switch, the session's choice over the saved :mode (:vibe/set-auto), and,
   while the vibe can change by itself, how long it has played of its time,
   \"ACTIVE 0:31 / 0:45\", then \"ARMED → next: Build\", pulsing twice a
   second, until a phrase gap or a FLASH release changes it. Hidden while
   auto is off, locked, in blackout or with one vibe.

   The countdown draws itself on the show clock's ticks (clock-view) from
   the wall clock, which app state's times are on, and sets its text only
   when the second changes."
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.views.components.clock-view :as clock-view])
  (:import [javafx.scene.control Label]))

(defn- m-ss [ms]
  (let [s (quot (long ms) 1000)]
    (format "%d:%02d" (quot s 60) (rem s 60))))

(defn countdown-text
  "What the countdown says at now-ms for the rotation (subs/vibe-rotation):
   the vibe's time so far of its :seconds (0 before a vibe plays), or, armed,
   the vibe it goes to."
  [{:keys [rotation entered-at seconds next]} now-ms]
  (if (= :armed rotation)
    (str "ARMED → next: " next)
    (let [total (* 1000.0 (double seconds))
          elapsed (if entered-at (min total (max 0.0 (double (- now-ms entered-at)))) 0.0)]
      (str "ACTIVE " (m-ss elapsed) " / " (m-ss total)))))

(defn armed-opacity
  "The armed countdown's opacity at now-ms: from 1 down to 0.35 and back,
   twice a second."
  ^double [now-ms]
  (+ 0.675 (* 0.325 (Math/cos (/ (* 2.0 Math/PI (double (mod now-ms 500))) 500.0)))))

(defn redraw-countdown!
  "The countdown, whose user data is the rotation, at the wall clock's now."
  [^Label label _snapshot data]
  (let [now (System/currentTimeMillis)
        text (countdown-text data now)]
    (when (not= text (.getText label))
      (.setText label text))
    (.setOpacity label (if (= :armed (:rotation data)) (armed-opacity now) 1.0))))

(defn rotation-controls
  "The countdown and the AUTO switch, for the Perform tab's header."
  [{:keys [fx/context]}]
  (let [{:keys [auto? rotation blackout? next] :as data} (fx/sub-ctx context subs/vibe-rotation)
        shown? (boolean (and auto? next (not blackout?) (not= :locked rotation)))]
    {:fx/type :h-box
     :spacing 12
     :alignment :center-right
     :children [{:fx/type clock-view/ext-clock-redraw
                 :redraw! #'redraw-countdown!
                 :desc {:fx/type :label
                        :id "vibe-countdown"
                        :style-class (cond-> ["vibe-countdown"] (= :armed rotation) (conj "vibe-countdown-armed"))
                        :visible shown?
                        :managed shown?
                        :user-data (select-keys data [:rotation :entered-at :seconds :next])}}
                {:fx/type :toggle-button
                 :id "vibe-auto"
                 :text "AUTO"
                 :style-class ["toggle-button" "vibe-auto"]
                 :focus-traversable false
                 :selected (boolean auto?)
                 :on-action {:event/type :vibe/set-auto :auto? (not auto?)}}]}))
