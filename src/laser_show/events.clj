(ns laser-show.events
  "Event system for the Laser Show application.
   Handles state transitions based on dispatched events."
  (:require [laser-show.state :refer [app-state]]
            [laser-show.state.clipboard]
            [laser-show.animation.presets :as presets]))

;; ============================================================================
;; Dispatch Infrastructure
;; ============================================================================

(defmulti handle-event 
  "Pure function that takes (state, event) and returns new state.
   Event is a vector: [event-id & args]"
  (fn [_state [event-id & _]] event-id))

(defn dispatch! 
  "Dispatch an event to change the application state.
   Usage: (dispatch! [:event-id arg1 arg2])"
  [event]
  ;; Log event for debugging (optional)
  (when (not= (first event) :tick) ; Don't log high-frequency events
    (println "Event:" event))
  (swap! app-state handle-event event))

;; ============================================================================
;; Grid Events
;; ============================================================================

(defmethod handle-event :grid/select-cell
  [state [_ col row]]
  (assoc-in state [:grid :selected-cell] (when (and col row) [col row])))

(defmethod handle-event :grid/trigger-cell
  [state [_ col row]]
  (let [cell (get-in state [:grid :cells [col row]])]
    (if (:preset-id cell)
      ;; Use helper or inline logic to set playing state
      (-> state
          (assoc-in [:grid :active-cell] [col row])
          (assoc :playing? true
                 :current-animation (presets/create-animation-from-preset (:preset-id cell))
                 :animation-start-time (System/currentTimeMillis)))
      ;; Empty cell - just select it? do nothing?
      state)))

(defmethod handle-event :grid/stop-active
  [state _]
  (-> state
      (assoc-in [:grid :active-cell] nil)
      (assoc :playing? false
             :current-animation nil)))

(defmethod handle-event :grid/clear-cell
  [state [_ col row]]
  (update-in state [:grid :cells] dissoc [col row]))

(defmethod handle-event :grid/set-preset
  [state [_ col row preset-id]]
  (assoc-in state [:grid :cells [col row]] {:preset-id preset-id}))

;; ============================================================================
;; Transport Events
;; ============================================================================

(defmethod handle-event :transport/stop
  [state _]
  (-> state
      (assoc-in [:grid :active-cell] nil)
      (assoc :playing? false 
             :current-animation nil)))

(defmethod handle-event :transport/play-pause
  [state _]
  (if (:playing? state)
    (handle-event state [:transport/stop])
    (if-let [active-cell (get-in state [:grid :active-cell])]
      ;; Access active cell to restart?
      (let [[c r] active-cell]
         (handle-event state [:grid/trigger-cell c r]))
      ;; No active cell, maybe ensure playing is true if animation exists?
      (if (:current-animation state)
        (assoc state :playing? true)
        state))))

;; ============================================================================
;; Clipboard Events
;; ============================================================================

(defmethod handle-event :clipboard/copy-cell
  [state [_ [col row]]]
  (let [cell-data (get-in state [:grid :cells [col row]])]
    (laser-show.state.clipboard/copy-cell-assignment! (:preset-id cell-data))
    state))

(defmethod handle-event :clipboard/copy-selected
  [state _]
  (if-let [selected (get-in state [:grid :selected-cell])]
    (let [cell-data (get-in state [:grid :cells selected])]
      (laser-show.state.clipboard/copy-cell-assignment! (:preset-id cell-data))
      state)
    state))

(defmethod handle-event :clipboard/paste-cell
  [state [_ [col row]]]
  (if-let [preset-id (laser-show.state.clipboard/paste-cell-assignment)]
    (handle-event state [:grid/set-preset col row preset-id])
    state))

(defmethod handle-event :clipboard/paste-to-selected
  [state _]
  (if-let [selected (get-in state [:grid :selected-cell])]
    (handle-event state [:clipboard/paste-cell selected])
    state))

(defmethod handle-event :grid/set-selected-preset
  [state [_ preset-id]]
  (if-let [selected (get-in state [:grid :selected-cell])]
    (handle-event state [:grid/set-preset (first selected) (second selected) preset-id])
    state))

(defmethod handle-event :grid/move-cell
  [state [_ from-col from-row to-col to-row]]
  (let [from-key [from-col from-row]
        to-key [to-col to-row]
        cell-data (get-in state [:grid :cells from-key])]
    (if cell-data
      (-> state
          (update-in [:grid :cells] dissoc from-key)
          (assoc-in [:grid :cells to-key] cell-data))
      state)))

;; ============================================================================
;; IDN Events
;; ============================================================================

(defmethod handle-event :idn/set-connection-status
  [state [_ connected? target engine]]
  (assoc-in state [:idn] {:connected? connected?
                          :target target
                          :streaming-engine engine}))
