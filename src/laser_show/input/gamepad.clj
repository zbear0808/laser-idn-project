(ns laser-show.input.gamepad
  "Gamepad input handler using Jamepad for Xbox controller support.

   A single daemon thread polls the controllers at ~60 Hz and sends the
   differences to the input router: button presses/releases as :trigger /
   :trigger-release events, axis moves as :control-change events (which the
   input mapper writes to the fast input store, not the cljfx context)."
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

   Both are maps of controller-id -> {:buttons {kw bool} :axes {kw 0..1}}.
   Buttons emit a trigger on press and a release on release. Held buttons on
   a controller that disappeared from next are released. Axes emit a
   control-change when they move more than a small threshold."
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

(defn- poll-loop [manager dispatch-fn enabled-fn running]
  (loop [prev-state {}]
    (when @running
      (let [next-state
            (try
              (let [enabled? (enabled-fn)
                    new-state (if enabled? (read-controllers manager) {})]
                (doseq [event (diff-controller-states prev-state new-state)]
                  (dispatch-fn event))
                (Thread/sleep (long (if enabled? poll-interval-ms disabled-poll-interval-ms)))
                new-state)
              (catch InterruptedException _
                (log/info "Gamepad polling loop interrupted")
                ::stop)
              (catch Exception e
                (log/error "Error in gamepad polling loop:" (.getMessage e))
                (try
                  (Thread/sleep 1000)
                  prev-state
                  (catch InterruptedException _
                    ::stop))))]
        (when-not (= ::stop next-state)
          (recur next-state))))))


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
