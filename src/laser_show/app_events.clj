(ns laser-show.app-events
  "Event system for the Laser Show application.
   Handles state transitions based on dispatched events.
   
   Events update the dynamic atoms in database.dynamic."
  (:require [laser-show.database.dynamic :as dyn]
            [laser-show.state.clipboard]))

;; ============================================================================
;; Dispatch Infrastructure
;; ============================================================================

(defn dispatch! 
  "Dispatch an event to change the application state.
   Usage: (dispatch! [:event-id arg1 arg2])"
  [event]
  (when (not= (first event) :tick)
    (println "Event:" event))
  (let [[event-id & args] event]
    (case event-id
      ;; Grid Events
      :grid/select-cell (apply dyn/set-selected-cell! args)
      :grid/trigger-cell (apply dyn/trigger-cell! args)
      :grid/stop-active (dyn/stop-playback!)
      :grid/clear-cell (apply dyn/clear-cell! args)
      :grid/set-preset (let [[col row preset-id] args]
                         (dyn/set-cell-preset! col row preset-id))
      
      ;; Transport Events  
      :transport/stop (dyn/stop-playback!)
      :transport/play-pause (if (dyn/playing?)
                              (dyn/stop-playback!)
                              (when-let [active-cell (dyn/get-active-cell)]
                                (let [[c r] active-cell]
                                  (dyn/trigger-cell! c r))))
      
      ;; Clipboard Events
      :clipboard/copy-cell (let [[[col row]] args
                                 cell (dyn/get-cell col row)]
                             (laser-show.state.clipboard/copy-cell-assignment! (:preset-id cell)))
      :clipboard/copy-selected (when-let [selected (dyn/get-selected-cell)]
                                 (let [cell (dyn/get-cell (first selected) (second selected))]
                                   (laser-show.state.clipboard/copy-cell-assignment! (:preset-id cell))))
      :clipboard/paste-cell (let [[[col row]] args]
                              (when-let [preset-id (laser-show.state.clipboard/paste-cell-assignment)]
                                (dyn/set-cell-preset! col row preset-id)))
      :clipboard/paste-to-selected (when-let [selected (dyn/get-selected-cell)]
                                     (when-let [preset-id (laser-show.state.clipboard/paste-cell-assignment)]
                                       (dyn/set-cell-preset! (first selected) (second selected) preset-id)))
      
      :grid/set-selected-preset (let [[preset-id] args]
                                  (when-let [selected (dyn/get-selected-cell)]
                                    (dyn/set-cell-preset! (first selected) (second selected) preset-id)))
      
      :grid/move-cell (let [[from-col from-row to-col to-row] args]
                        (dyn/move-cell! from-col from-row to-col to-row))
      
      ;; IDN Events
      :idn/set-connection-status (let [[connected? target engine] args]
                                   (dyn/set-idn-connection! connected? target engine))
      
      ;; Timing Events
      :timing/set-bpm (let [[bpm] args] (dyn/set-bpm! bpm))
      :timing/tap (let [[timestamp] args] (dyn/add-tap-time! timestamp))
      :timing/clear-taps (dyn/clear-tap-times!)
      
      ;; Playback Events
      :playback/trigger (dyn/trigger!)
      :playback/set-trigger-time (let [[time-ms] args] (dyn/set-trigger-time! time-ms))
      
      ;; UI Component Events
      :ui/set-component (let [[component-key component] args]
                          (dyn/set-ui-component! component-key component))
      
      ;; Logging Events
      :logging/set-enabled (let [[enabled?] args] (dyn/set-logging-enabled! enabled?))
      
      ;; Default
      (println "Unknown event:" event-id))))
