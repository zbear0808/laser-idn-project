(ns laser-show.views.components.clock-view
  "Widgets that draw straight from the show clock's newest tick
   (show-time/latest) on each screen refresh, without app state: the preview
   cells and the timeline's playhead and readout (plans/2026-11-17-show-clock/
   03). Their cljfx descs re-render only when their own props change.

   ext-clock-redraw gives the node an AnimationTimer, which runs on the FX
   thread once per pulse and redraws when the tick, the node's user data (the
   props it last rendered with) or whether the tick is stale changed. It
   skips pulses while the node's window isn't showing."
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

(defn- start-timer!
  [^Node node redraw!]
  (let [last-drawn (volatile! ::never)
        failed? (volatile! false)
        timer (proxy [AnimationTimer] []
                (handle [_pulse-ns]
                  (when (showing? node)
                    (let [snapshot (show-time/latest)
                          data (.getUserData node)
                          drawn [(:tick snapshot) (some-> snapshot (show-time/stale? (System/nanoTime))) data]]
                      (when (not= drawn @last-drawn)
                        (vreset! last-drawn drawn)
                        (try
                          (redraw! node snapshot data)
                          (catch Throwable t
                            (when-not @failed?
                              (vreset! failed? true)
                              (log/warn t "A clock widget failed to draw:"
                                        (or (.getId node) (.getSimpleName (class node))))))))))))]
    (.put (.getProperties node) ::timer timer)
    (swap! !running inc)
    (.start ^AnimationTimer timer)))

(defn- stop-timer! [^Node node]
  (when-let [^AnimationTimer timer (.remove (.getProperties node) ::timer)]
    (.stop timer)
    (swap! !running dec)))

(defn ext-clock-redraw
  "desc, redrawn from the show clock's newest tick: on each pulse where
   something changed, (redraw! node snapshot user-data) on the FX thread.
   snapshot is nil before the first tick, and may be stale
   (show-time/stale?). Put what the drawing needs from props in desc's
   :user-data. Pass redraw! as a var, so a code reload takes effect."
  [{:keys [desc redraw!]}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created #(start-timer! % redraw!)
   :on-advanced (fn [old new]
                  (stop-timer! old)
                  (start-timer! new redraw!))
   :on-deleted stop-timer!
   :desc desc})
