(ns laser-show.input.gamepad
  "Gamepad input handler using Jamepad for Xbox controller support.

   A single daemon thread polls the controllers at ~60 Hz and sends the
   differences to the input router: button presses/releases as :trigger /
   :trigger-release events, axis moves as :control-change events, each once
   the axis is past a small threshold from the value last sent for it
   (which the input mapper writes to the fast input store, not the cljfx
   context)."
  (:require [clojure.tools.logging :as log]
            [laser-show.input.events :as events])
  (:import [com.studiohartman.jamepad ControllerManager ControllerIndex ControllerButton ControllerAxis]))

(def buttons
  {:a ControllerButton/A
   :b ControllerButton/B
   :x ControllerButton/X
   :y ControllerButton/Y
   :back ControllerButton/BACK
   :guide ControllerButton/GUIDE
   :start ControllerButton/START
   :left-stick ControllerButton/LEFTSTICK
   :right-stick ControllerButton/RIGHTSTICK
   :left-shoulder ControllerButton/LEFTBUMPER
   :right-shoulder ControllerButton/RIGHTBUMPER
   :dpad-up ControllerButton/DPAD_UP
   :dpad-down ControllerButton/DPAD_DOWN
   :dpad-left ControllerButton/DPAD_LEFT
   :dpad-right ControllerButton/DPAD_RIGHT})

(def axes
  {:left-x ControllerAxis/LEFTX
   :left-y ControllerAxis/LEFTY
   :right-x ControllerAxis/RIGHTX
   :right-y ControllerAxis/RIGHTY
   :trigger-left ControllerAxis/TRIGGERLEFT
   :trigger-right ControllerAxis/TRIGGERRIGHT})

(def ^:private axis-threshold 0.005)

(def ^:private poll-interval-ms 16)

(def ^:private disabled-poll-interval-ms 100)

(defn- normalize-axis [axis-kw value]
  ;; Triggers are 0.0 to 1.0, sticks are -1.0 to 1.0
  (if (or (= axis-kw :trigger-left) (= axis-kw :trigger-right))
    value
    (/ (+ value 1.0) 2.0)))


;; Pure state diffing


(defn diff-controller-states
  "Returns the input events needed to go from prev to next.

   Both are maps of controller-id -> {:buttons {kw bool} :axes {kw 0..1}}:
   next a sample, prev what the poll loop compares it with
   (next-poll-baseline): each button as last sampled, each axis at the
   value last sent for it, an axis never sent being taken as 0.5. Buttons
   emit a trigger on press and a release on release. Held buttons on a
   controller that disappeared from next are released. Axes emit a
   control-change when they are more than a small threshold from prev's."
  [prev next]
  (let [button-event (fn [ctrl-id btn-kw pressed?]
                       (events/trigger :gamepad [ctrl-id btn-kw]
                                       (if pressed? :pressed :released)))]
    (vec
     (concat
      (for [[ctrl-id ctrl-state] next
            :let [prev-ctrl (get prev ctrl-id)]
            event (concat
                   (for [[btn-kw pressed?] (:buttons ctrl-state)
                         :let [prev-pressed? (boolean (get-in prev-ctrl [:buttons btn-kw]))]
                         :when (not= (boolean pressed?) prev-pressed?)]
                     (button-event ctrl-id btn-kw pressed?))
                   (for [[axis-kw value] (:axes ctrl-state)
                         :let [prev-value (get-in prev-ctrl [:axes axis-kw] 0.5)]
                         :when (> (Math/abs (- (double value) (double prev-value))) axis-threshold)]
                     (events/control-change :gamepad ctrl-id axis-kw value)))]
        event)
      (for [[ctrl-id prev-ctrl] prev
            :when (not (contains? next ctrl-id))
            [btn-kw pressed?] (:buttons prev-ctrl)
            :when pressed?]
        (button-event ctrl-id btn-kw false))))))

(defn- next-poll-baseline
  "What the next sample is compared with (diff-controller-states' prev),
   once sample's events, emitted, are sent: each controller still in
   sample, with its sampled buttons and, for its axes still in it, the
   value each was last sent at: emitted's control changes, else prev's.
   An axis not past the threshold keeps its last sent value, so a slow
   sweep is sent once it has moved that far in all, and jitter round a
   value isn't. A controller gone from sample is forgotten: back, it
   starts afresh."
  [prev sample emitted]
  (reduce (fn [baseline {:keys [channel control value] :as event}]
            (if (events/control-change? event)
              (assoc-in baseline [channel :axes control] value)
              baseline))
          (reduce-kv (fn [baseline ctrl-id {:keys [buttons axes]}]
                       (assoc baseline ctrl-id {:buttons buttons
                                                :axes (select-keys (get-in prev [ctrl-id :axes]) (keys axes))}))
                     {}
                     sample)
          emitted))


;; Polling


(defn- read-controllers [^ControllerManager manager]
  (.update manager)
  (reduce
   (fn [acc i]
     (let [^ControllerIndex controller (.getControllerIndex manager (int i))]
       (if (.isConnected controller)
         (assoc acc i
                {:buttons (reduce-kv (fn [m k v] (assoc m k (.isButtonPressed controller ^ControllerButton v))) {} buttons)
                 :axes (reduce-kv (fn [m k v] (assoc m k (normalize-axis k (.getAxisState controller ^ControllerAxis v)))) {} axes)})
         acc)))
   {}
   (range (.getNumControllers manager))))

(defn- poll-loop
  "Every poll-interval-ms (disabled-poll-interval-ms, reading no
   controllers, while enabled-fn says no) sends dispatch-fn the events
   from the baseline to the sample (diff-controller-states), then moves the
   baseline on by them (next-poll-baseline), until running is false or the
   thread is interrupted. After an error it waits a second and goes on from
   the baseline it had."
  [manager dispatch-fn enabled-fn running]
  (loop [baseline {}]
    (when @running
      (let [next-baseline
            (try
              (let [enabled? (enabled-fn)
                    sample (if enabled? (read-controllers manager) {})
                    emitted (diff-controller-states baseline sample)]
                (run! dispatch-fn emitted)
                (Thread/sleep (long (if enabled? poll-interval-ms disabled-poll-interval-ms)))
                (next-poll-baseline baseline sample emitted))
              (catch InterruptedException _
                (log/info "Gamepad polling loop interrupted")
                ::stop)
              (catch Exception e
                (log/error "Error in gamepad polling loop:" (.getMessage e))
                (try
                  (Thread/sleep 1000)
                  baseline
                  (catch InterruptedException _
                    ::stop))))]
        (when-not (= ::stop next-baseline)
          (recur next-baseline))))))


;; Lifecycle


(defonce ^:private !instance (atom nil))

(defn stop!
  "Stops the polling thread, waits for it to finish, then shuts down SDL.
   With no argument, stops the instance started by start!. Idempotent."
  ([]
   (when-let [instance (first (reset-vals! !instance nil))]
     (stop! instance)))
  ([{:keys [^Thread thread ^ControllerManager manager running]}]
   (when running
     (reset! running false))
   (let [stopped? (if thread
                    (do (.interrupt thread)
                        (.join thread 1000)
                        (not (.isAlive thread)))
                    true)]
     (if (and manager stopped?)
       (try
         (.quitSDLGamepad manager)
         (catch Throwable t
           (log/warn "Error shutting down SDL gamepad:" (.getMessage t))))
       (when manager
         (log/warn "Gamepad poll thread did not stop in time; skipping SDL shutdown"))))
   (log/info "Stopped Gamepad input manager")
   nil))

(defn start!
  "Starts polling gamepads on a daemon thread, sending events to dispatch-fn
   (normally laser-show.input.router/dispatch!). enabled-fn is called every
   poll; while it returns false no controllers are read and held buttons are
   released. Stops any previously started instance first.

   Returns the instance map, or nil if the native gamepad library failed to
   load."
  ([dispatch-fn]
   (start! dispatch-fn (constantly true)))
  ([dispatch-fn enabled-fn]
   (stop!)
   (log/info "Starting Gamepad input manager")
   (try
     (let [manager (ControllerManager.)
           _ (.initSDLGamepad manager)
           running (atom true)
           thread (doto (Thread. ^Runnable #(poll-loop manager dispatch-fn enabled-fn running)
                                 "gamepad-poll")
                    (.setDaemon true)
                    (.start))
           instance {:manager manager :thread thread :running running}]
       (reset! !instance instance)
       instance)
     (catch Throwable t
       (log/error "Gamepad input unavailable:" (.getMessage t))
       nil))))

(defn running?
  "True if a polling thread started by start! is alive."
  []
  (boolean (when-let [^Thread t (:thread @!instance)] (.isAlive t))))
