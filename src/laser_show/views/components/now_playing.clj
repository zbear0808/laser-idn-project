(ns laser-show.views.components.now-playing
  "The Shows tab's now-playing bar (plans/2026-11-25-timeline-shows 01 §7,
   07 §4.8): ⏮ ⏯ ⏹ ⏭, the title and artist, elapsed and remaining time, a
   seek bar, the show timecode, HELD while Stop holds the output, and ✎.
   Which show it names is subs/now-playing's choice; while timecode drives
   that show, the transport and the seek bar are disabled and a TC chip
   stands in for ⏯.

   The times, the timecode and the seek bar's thumb are drawn from the show
   clock's newest tick (clock-view/ext-clock-redraw, redraw-now-playing!),
   never through app state: cljfx gives those labels no text and the seek
   bar no :value. The seek bar isn't redrawn while it is pressed
   (gesture/capture), and seeks once, on release."
  (:require [cljfx.api :as fx]
            [clj-font-awesome.core :as fa]
            [laser-show.animation.show-time :as show-time]
            [laser-show.events.core :as events]
            [laser-show.subs :as subs]
            [laser-show.timeline.shows :as shows]
            [laser-show.timeline.timecode :as tc]
            [laser-show.views.components.clock-view :as clock-view]
            [laser-show.views.components.gesture :as gesture])
  (:import [javafx.application Platform]
           [javafx.event EventHandler]
           [javafx.scene.control Label Slider]
           [javafx.scene.input MouseButton MouseEvent]
           [javafx.scene.layout HBox]))


;; Drawn from the clock

(defn- set-text! [^Label label text]
  (when (not= text (.getText label))
    (.setText label text)))

(defn redraw-now-playing!
  "The clock box's labels and the seek bar's thumb from the show clock's
   tick (clock-view): the show's position, 0 while it has none. Elapsed and
   remaining (\"loop\" for a show that loops) at its tempo, its timecode
   (≈ while the tempo isn't locked). The thumb stays where the user holds
   it while the seek bar is pressed. A label is written only when its text
   changed."
  [^HBox box snapshot {:keys [show-id length bpm approx? tc-start fps loop?]}]
  (let [pos (double (or (show-time/cue-beats snapshot (shows/play-key show-id)) 0.0))
        bpm (if (and (number? bpm) (pos? bpm)) bpm 120.0)
        elapsed-s (tc/beats->seconds pos bpm)
        children (.getChildren box)
        ^Slider slider (.get children 1)]
    (set-text! (.get children 0) (tc/format-clock elapsed-s))
    (set-text! (.get children 2) (if loop?
                                   "loop"
                                   (str "-" (tc/format-clock (tc/beats->seconds (max 0.0 (- (double (or length 0.0)) pos)) bpm)))))
    (set-text! (.get children 3) (str (when approx? "≈")
                                      (tc/format-tc (tc/seconds->tc (+ (double (or tc-start 0.0)) elapsed-s) (or fps 30)))))
    (when (and (nil? (gesture/capture slider)) (not= pos (.getValue slider)))
      (.setValue slider pos))))


;; The seek bar

(defn- primary? [^MouseEvent e] (= MouseButton/PRIMARY (.getButton e)))

(defn- seek-press!
  "A primary press on the seek bar captures it for the show its last render
   named (its :user-data), before the skin moves the thumb. Its end seeks
   that show to where the thumb is, but only on release: a press that ends
   any other way (focus lost, the bar gone) sends nothing, and the next
   redraw puts the thumb back."
  [^Slider slider e]
  (when (primary? e)
    (when-let [show-id (:show-id (.getUserData slider))]
      (gesture/capture! slider {:show-id show-id}
                        (fn [{:keys [address]} reason]
                          (when (= :release reason)
                            (events/dispatch! {:event/type :shows/seek
                                               :show-id (:show-id address)
                                               :beat (.getValue slider)})))))))

(defn- seek-release!
  "A primary release ends the press after the skin's last value."
  [^Slider slider e]
  (when (primary? e)
    (when-let [{:keys [gesture-id]} (gesture/capture slider)]
      (Platform/runLater #(gesture/finish! slider gesture-id :release)))))

(defn- install-seek! [^Slider slider]
  (.addEventFilter slider MouseEvent/MOUSE_PRESSED (reify EventHandler (handle [_ e] (seek-press! slider e))))
  (.addEventFilter slider MouseEvent/MOUSE_RELEASED (reify EventHandler (handle [_ e] (seek-release! slider e)))))

(defn- seek-slider
  "The seek bar over show-id's length in beats. No :value: only
   redraw-now-playing! moves it."
  [{:keys [show-id length disable]}]
  (gesture/wrap-owner {:fx/type :slider
                       :id "now-playing-seek"
                       :min 0.0
                       :max (double (or length 0.0))
                       :disable (boolean disable)
                       :focus-traversable false
                       :max-width Double/MAX_VALUE
                       :h-box/hgrow :always
                       :user-data {:show-id show-id}}
                      {:on-created install-seek!}))

(defn- clock-box
  "Elapsed, the seek bar, remaining and the timecode, drawn from the clock."
  [{:keys [show-id length tc?] :as np}]
  {:fx/type clock-view/ext-clock-redraw
   :redraw! #'redraw-now-playing!
   :desc {:fx/type :h-box
          :alignment :center-left
          :spacing 6
          :max-width Double/MAX_VALUE
          :h-box/hgrow :always
          :user-data (select-keys np [:show-id :length :bpm :approx? :tc-start :fps :loop?])
          :children [{:fx/type :label :id "now-playing-elapsed" :style-class "tl-readout"}
                     (seek-slider {:show-id show-id :length length :disable tc?})
                     {:fx/type :label :id "now-playing-remaining" :style-class "tl-readout"}
                     {:fx/type :label :id "now-playing-tc" :style-class ["tl-readout" "tl-readout-tc"]}]}})


;; The bar

(defn- icon-button [{:keys [id icon tooltip on-action disable visible]
                     :or {visible true}}]
  {:fx/type :button
   :id id
   :style-class ["button" "tl-icon-button"]
   :focus-traversable false
   :graphic {:fx/type fa/icon :name icon :size 12}
   :tooltip {:fx/type :tooltip :text tooltip}
   :disable (boolean disable)
   :visible (boolean visible)
   :managed (boolean visible)
   :on-action on-action})

(defn now-playing-bar
  "The bar (subs/now-playing). With nothing to show it reads \"Nothing
   playing\"; ⏯ then plays the first selected show."
  [{:keys [fx/context]}]
  (let [{:keys [show-id title artist source state held?] :as np} (fx/sub-ctx context subs/now-playing)
        tc? (= :timecode source)
        tc-tip "Timecode drives this show"]
    {:fx/type :h-box
     :id "now-playing"
     :style-class "tl-toolbar"
     :alignment :center-left
     :spacing 6
     :children
     (into [(icon-button {:id "now-playing-prev" :icon :backward-step
                          :tooltip (if tc? tc-tip "Previous show")
                          :disable tc?
                          :on-action {:event/type :shows/previous}})
            (icon-button {:id "now-playing-toggle" :icon (if (= :playing state) :pause :play)
                          :tooltip (if (= :playing state) "Pause" "Play")
                          :visible (not tc?)
                          :on-action {:event/type :shows/toggle-play}})
            {:fx/type :label
             :id "now-playing-tc-chip"
             :style-class ["label" "tl-mtc-chip" "tl-mtc-running"]
             :text "TC"
             :tooltip {:fx/type :tooltip :text tc-tip}
             :visible tc?
             :managed tc?}
            (icon-button {:id "now-playing-stop" :icon :stop
                          :tooltip (if tc? tc-tip "Stop")
                          :disable (or tc? (nil? np) (= :stopped state))
                          :on-action {:event/type :shows/stop}})
            (icon-button {:id "now-playing-next" :icon :forward-step
                          :tooltip (if tc? tc-tip "Next show")
                          :disable tc?
                          :on-action {:event/type :shows/next}})]
           (if (nil? np)
             [{:fx/type :label
               :id "now-playing-empty"
               :style-class "text-muted"
               :text "Nothing playing"}]
             [{:fx/type :label
               :id "now-playing-title"
               :style-class "label-primary"
               :min-width 0
               :text title}
              {:fx/type :label
               :id "now-playing-artist"
               :style-class "text-muted"
               :min-width 0
               :text (or artist "")
               :visible (some? artist)
               :managed (some? artist)}
              (clock-box (assoc np :tc? tc?))
              {:fx/type :label
               :id "now-playing-held"
               :style-class "output-held-chip"
               :text "HELD"
               :tooltip {:fx/type :tooltip
                         :text "Stop (blackout) holds the output. Transport Play resumes it"}
               :visible (boolean held?)
               :managed (boolean held?)}
              (icon-button {:id "now-playing-edit" :icon :pen
                            :tooltip "Open in Editor"
                            :on-action {:event/type :shows/open-editor :show-id show-id}})]))}))
