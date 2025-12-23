(ns laser-show.events.dispatch
  "Event system for the Laser Show application.
   Handles state transitions based on dispatched events.
   
   Events update the state atoms in state.atoms."
  (:require [laser-show.state.atoms :as state]
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
      :grid/select-cell (apply state/set-selected-cell! args)
      :grid/trigger-cell (apply state/trigger-cell! args)
      :grid/stop-active (state/stop-playback!)
      :grid/clear-cell (apply state/clear-cell! args)
      :grid/set-preset (let [[col row preset-id] args]
                         (state/set-cell-preset! col row preset-id))
      
      ;; Transport Events  
      :transport/stop (state/stop-playback!)
      :transport/play-pause (if (state/playing?)
                              (state/stop-playback!)
                              (when-let [active-cell (state/get-active-cell)]
                                (let [[c r] active-cell]
                                  (state/trigger-cell! c r))))
      
      ;; Clipboard Events
      :clipboard/copy-cell (let [[[col row]] args
                                 cell (state/get-cell col row)]
                             (laser-show.state.clipboard/copy-cell-assignment! (:preset-id cell)))
      :clipboard/copy-selected (when-let [selected (state/get-selected-cell)]
                                 (let [cell (state/get-cell (first selected) (second selected))]
                                   (laser-show.state.clipboard/copy-cell-assignment! (:preset-id cell))))
      :clipboard/paste-cell (let [[[col row]] args]
                              (when-let [preset-id (laser-show.state.clipboard/paste-cell-assignment)]
                                (state/set-cell-preset! col row preset-id)))
      :clipboard/paste-to-selected (when-let [selected (state/get-selected-cell)]
                                     (when-let [preset-id (laser-show.state.clipboard/paste-cell-assignment)]
                                       (state/set-cell-preset! (first selected) (second selected) preset-id)))
      
      :grid/set-selected-preset (let [[preset-id] args]
                                  (when-let [selected (state/get-selected-cell)]
                                    (state/set-cell-preset! (first selected) (second selected) preset-id)))
      
      :grid/move-cell (let [[from-col from-row to-col to-row] args]
                        (state/move-cell! from-col from-row to-col to-row))
      
      ;; IDN Events
      :idn/set-connection-status (let [[connected? target engine] args]
                                   (state/set-idn-connection! connected? target engine))
      
      ;; Timing Events
      :timing/set-bpm (let [[bpm] args] (state/set-bpm! bpm))
      :timing/tap (let [[timestamp] args] (state/add-tap-time! timestamp))
      :timing/clear-taps (state/clear-tap-times!)
      
      ;; Playback Events
      :playback/trigger (state/trigger!)
      :playback/set-trigger-time (let [[time-ms] args] (state/set-trigger-time! time-ms))
      
      ;; UI Component Events
      :ui/set-component (let [[component-key component] args]
                          (state/set-ui-component! component-key component))
      
      ;; Logging Events
      :logging/set-enabled (let [[enabled?] args] (state/set-logging-enabled! enabled?))
      
      ;; Default
      (println "Unknown event:" event-id))))
