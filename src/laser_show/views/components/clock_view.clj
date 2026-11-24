(ns laser-show.views.components.clock-view
  "Widgets that redraw themselves from runtime data on the FX thread,
   without app state. Most draw the show clock's newest tick
   (show-time/latest) on each screen refresh: the preview cells, the
   timeline's playhead and readout (plans/2026-11-17-show-clock/03). A rig
   card's thumbnail samples its laser's last driver return instead, on its
   own clock (:sample-every-interval?). Their cljfx descs re-render only when
   their own props change.

   ext-clock-redraw gives the node a pulse timer (an AnimationTimer), which
   runs on the FX thread once per pulse. By default it redraws when the
   tick, the node's user data (the props it last rendered with) or whether
   the tick is stale changed; with :sample-every-interval? it calls the
   painter with the node's current user data once every :every-ms, whatever
   the tick does. It skips pulses while the node's window isn't showing."
  (:require [cljfx.api :as fx]
            [clojure.tools.logging :as log]
            [laser-show.animation.show-time :as show-time])
  (:import [javafx.animation AnimationTimer]
           [javafx.scene Node]))

(defonce ^:private !running
  ;; Timers started and not stopped yet (running-timers)
  (atom 0))

(defn running-timers
  "How many clock widgets have a running timer (for tests: they stop when
   their node goes)."
  []
  @!running)

(defn- showing? [^Node node]
  (some-> node .getScene .getWindow .isShowing))

(defn- pulse-timer
  "What gives a clock widget its pulses: an AnimationTimer calling (handle!
   pulse-ns) on the FX thread once per pulse while it is started. Tests
   replace it to deliver the pulses themselves."
  ^AnimationTimer [handle!]
  (proxy [AnimationTimer] []
    (handle [pulse-ns]
      (handle! pulse-ns))))

(defn- start-timer!
  [^Node node redraw! every-ms sample?]
  (let [last-drawn (volatile! ::never)
        last-at (volatile! nil)
        every-ns (when every-ms (* (long every-ms) 1000000))
        failed? (volatile! false)
        due? (fn [pulse-ns]
               (or (nil? every-ns) (nil? @last-at) (>= (- (long pulse-ns) (long @last-at)) every-ns)))
        draw! (fn [snapshot data]
                (try
                  (redraw! node snapshot data)
                  (catch Throwable t
                    (when-not @failed?
                      (vreset! failed? true)
                      (log/warn t "A clock widget failed to draw:"
                                (or (.getId node) (.getSimpleName (class node))))))))
        timer (pulse-timer
               (if sample?
                 ;; Every sample counts for the interval, whether it changed
                 ;; anything or not; a late pulse is one sample, never a catch-up
                 (fn [pulse-ns]
                   (when (and (showing? node) (due? pulse-ns))
                     (vreset! last-at pulse-ns)
                     (draw! nil (.getUserData node))))
                 (fn [pulse-ns]
                   (when (and (showing? node) (due? pulse-ns))
                     (let [snapshot (show-time/latest)
                           data (.getUserData node)
                           drawn [(:tick snapshot) (some-> snapshot (show-time/stale? (System/nanoTime))) data]]
                       (when (not= drawn @last-drawn)
                         (vreset! last-drawn drawn)
                         (vreset! last-at pulse-ns)
                         (draw! snapshot data)))))))]
    (.put (.getProperties node) ::timer timer)
    (swap! !running inc)
    (.start timer)))

(defn- stop-timer! [^Node node]
  (when-let [^AnimationTimer timer (.remove (.getProperties node) ::timer)]
    (.stop timer)
    (swap! !running dec)))

(defn ext-clock-redraw
  "desc, redrawn on the FX thread by (redraw! node snapshot user-data): on
   each pulse where the show clock's newest tick, its staleness or the
   user data changed, and with :every-ms at most that often. snapshot is
   nil before the first tick, and may be stale (show-time/stale?). With
   :sample-every-interval? (and :every-ms) it is called once every :every-ms
   instead, with snapshot nil and the node's current user data, for a
   widget that reads its own source (a rig card's thumbnail: 100). Put what
   the drawing needs from props in desc's :user-data. Pass redraw! as a
   var, so a code reload takes effect. The timer stops when the node goes."
  [{:keys [desc redraw! every-ms sample-every-interval?]}]
  (let [sample? (boolean (and sample-every-interval? every-ms))]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created #(start-timer! % redraw! every-ms sample?)
     :on-advanced (fn [old new]
                    (stop-timer! old)
                    (start-timer! new redraw! every-ms sample?))
     :on-deleted stop-timer!
     :desc desc}))
