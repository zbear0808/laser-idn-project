(ns laser-show.input.gamepad
  "Gamepad input handler using Jamepad for Xbox controller support."
  (:require [clojure.tools.logging :as log]
            [laser-show.input.events :as events])
  (:import [com.studiohartman.jamepad ControllerManager ControllerButton ControllerAxis]))

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

(defn- normalize-axis [axis-kw value]
  ;; Triggers are 0.0 to 1.0, sticks are -1.0 to 1.0
  (if (or (= axis-kw :trigger-left) (= axis-kw :trigger-right))
    value
    (/ (+ value 1.0) 2.0)))

(defn- poll-loop [manager dispatch-fn]
  (loop [prev-state {}]
    (let [[status next-state]
          (try
            (.updateControllers manager)
            (let [num-controllers (.getNumControllers manager)
                  new-state
                  (reduce
                   (fn [acc i]
                     (let [controller (.getControllerIndex manager i)]
                       (if (.isConnected controller)
                         (let [state-map {:buttons (reduce-kv (fn [m k v] (assoc m k (.isButtonPressed controller v))) {} buttons)
                                          :axes (reduce-kv (fn [m k v] (assoc m k (normalize-axis k (.getAxisState controller v)))) {} axes)}]
                           (assoc acc i state-map))
                         acc)))
                   {}
                   (range num-controllers))]

              ;; Compare new-state and prev-state, dispatch events
              (doseq [[ctrl-id ctrl-state] new-state]
                (let [prev-ctrl (get prev-state ctrl-id)]
                  ;; Diff buttons
                  (doseq [[btn-kw pressed?] (:buttons ctrl-state)]
                    (let [prev-pressed? (get prev-ctrl btn-kw false)]
                      (when (not= pressed? prev-pressed?)
                        (dispatch-fn (events/trigger :gamepad [ctrl-id btn-kw] (if pressed? :pressed :released))))))
                  ;; Diff axes
                  (doseq [[axis-kw val] (:axes ctrl-state)]
                    (let [prev-val (get-in prev-ctrl [:axes axis-kw] 0.5)]
                      (when (> (Math/abs (- (double val) (double prev-val))) 0.005) ;; small deadzone/threshold
                        (dispatch-fn (events/control-change :gamepad ctrl-id axis-kw val)))))))

              (Thread/sleep 16) ;; ~60Hz polling
              [:ok new-state])

            (catch InterruptedException _
              (log/info "Gamepad polling loop interrupted")
              [:interrupted nil])
            (catch Exception e
              (log/error "Error in gamepad polling loop:" (.getMessage e))
              (try
                (Thread/sleep 1000)
                [:error prev-state]
                (catch InterruptedException _
                  [:interrupted nil]))))]
      (when-not (= status :interrupted)
        (recur next-state)))))

(defn start! [dispatch-fn]
  (log/info "Starting Gamepad input manager")
  (let [manager (ControllerManager.)]
    (.initSDLGamepad manager)
    (let [polling-thread (Thread. ^Runnable #(poll-loop manager dispatch-fn))]
      (.start polling-thread)
      {:manager manager
       :thread polling-thread})))

(defn stop! [state]
  (when-let [^Thread t (:thread state)]
    (.interrupt t))
  (when-let [^ControllerManager m (:manager state)]
    (.quitSDLGamepad m))
  (log/info "Stopped Gamepad input manager"))

