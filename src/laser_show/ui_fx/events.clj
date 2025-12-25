(ns laser-show.ui-fx.events
  "Event handlers for cljfx UI.
   Integrates with existing state atoms and dispatch system."
  (:require [laser-show.state.atoms :as state]
            [laser-show.state.clipboard :as clipboard]
            [laser-show.events.dispatch :as dispatch]))

;; ============================================================================
;; Event Handler Multimethod
;; ============================================================================

(defmulti handle-event
  "Handle cljfx events. Dispatches on :event/type."
  :event/type)

(defmethod handle-event :default
  [event]
  (println "Unhandled event:" (:event/type event)))

;; ============================================================================
;; Grid Events
;; ============================================================================

(defmethod handle-event :grid/trigger-cell
  [{:keys [col row]}]
  (state/trigger-cell! col row))

(defmethod handle-event :grid/select-cell
  [{:keys [col row]}]
  (state/set-selected-cell! col row))

(defmethod handle-event :grid/clear-cell
  [{:keys [col row]}]
  (state/clear-cell! col row))

(defmethod handle-event :grid/set-cell-preset
  [{:keys [col row preset-id]}]
  (state/set-cell-preset! col row preset-id))

(defmethod handle-event :grid/move-cell
  [{:keys [from-col from-row to-col to-row]}]
  (state/move-cell! from-col from-row to-col to-row))

(defmethod handle-event :grid/set-selected-preset
  [{:keys [preset-id]}]
  (state/set-selected-preset! preset-id)
  (when-let [[col row] (state/get-selected-cell)]
    (state/set-cell-preset! col row preset-id)))

;; ============================================================================
;; Playback/Transport Events
;; ============================================================================

(defmethod handle-event :transport/play
  [_]
  (state/start-playback!))

(defmethod handle-event :transport/stop
  [_]
  (state/stop-playback!))

(defmethod handle-event :transport/play-pause
  [_]
  (if (state/playing?)
    (state/stop-playback!)
    (state/start-playback!)))

(defmethod handle-event :transport/retrigger
  [_]
  (state/trigger!))

;; ============================================================================
;; Timing Events
;; ============================================================================

(defmethod handle-event :timing/set-bpm
  [{:keys [bpm]}]
  (state/set-bpm! bpm))

(defmethod handle-event :timing/tap-tempo
  [_]
  (state/add-tap-time! (System/currentTimeMillis)))

;; ============================================================================
;; Connection Events
;; ============================================================================

(defmethod handle-event :idn/connect
  [{:keys [target frame-provider]}]
  (dispatch/dispatch! [:idn/connect target frame-provider]))

(defmethod handle-event :idn/disconnect
  [_]
  (dispatch/dispatch! [:idn/disconnect]))

;; ============================================================================
;; Effects Events
;; ============================================================================

(defmethod handle-event :effects/toggle-cell
  [{:keys [col row]}]
  (state/toggle-effect-cell-active! col row))

(defmethod handle-event :effects/add-effect
  [{:keys [col row effect]}]
  (state/add-effect-to-cell! col row effect))

(defmethod handle-event :effects/remove-effect
  [{:keys [col row index]}]
  (state/remove-effect-from-cell! col row index))

(defmethod handle-event :effects/update-effect
  [{:keys [col row index effect]}]
  (state/update-effect-in-cell! col row index effect))

(defmethod handle-event :effects/update-param
  [{:keys [col row effect-idx param-key value]}]
  (state/update-effect-param! col row effect-idx param-key value))

(defmethod handle-event :effects/clear-cell
  [{:keys [col row]}]
  (state/clear-effect-cell! col row))

(defmethod handle-event :effects/reorder
  [{:keys [col row from-idx to-idx]}]
  (state/reorder-effects-in-cell! col row from-idx to-idx))

;; ============================================================================
;; Clipboard Events
;; ============================================================================

(defmethod handle-event :clipboard/copy-cell
  [{:keys [col row]}]
  (when-let [cell (state/get-cell col row)]
    (clipboard/copy-cell-assignment! cell)))

(defmethod handle-event :clipboard/paste-cell
  [{:keys [col row]}]
  (when-let [preset-id (clipboard/paste-cell-assignment)]
    (state/set-cell! col row {:preset-id preset-id})))

(defmethod handle-event :clipboard/copy-effect-cell
  [{:keys [col row]}]
  (when-let [cell (state/get-effect-cell col row)]
    (state/set-clipboard! {:type :effect-cell :data cell})))

(defmethod handle-event :clipboard/paste-effect-cell
  [{:keys [col row]}]
  (let [clip (state/get-clipboard)]
    (when (= (:type clip) :effect-cell)
      (state/set-effect-cell! col row (:data clip)))))

;; ============================================================================
;; UI Events
;; ============================================================================

(defmethod handle-event :ui/select-preset
  [{:keys [preset-id]}]
  (state/set-selected-preset! preset-id))

;; ============================================================================
;; Project Events
;; ============================================================================

(defmethod handle-event :project/mark-dirty
  [_]
  (state/mark-project-dirty!))

(defmethod handle-event :project/mark-clean
  [_]
  (state/mark-project-clean!))

;; ============================================================================
;; Drag and Drop Events
;; ============================================================================

(defmethod handle-event :drag/start
  [{:keys [source-type source-id source-key data]}]
  (state/start-drag! source-type source-id source-key data))

(defmethod handle-event :drag/end
  [_]
  (state/end-drag!))

(defmethod handle-event :drag/drop-cell
  [{:keys [from-col from-row to-col to-row]}]
  (state/move-cell! from-col from-row to-col to-row)
  (state/end-drag!))

;; ============================================================================
;; Window Events
;; ============================================================================

(defmethod handle-event :window/close-request
  [{:keys [fx/event]}]
  (when (state/project-dirty?)
    ;; Could show a dialog here asking to save
    (println "Project has unsaved changes"))
  ;; Allow close
  true)

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn dispatch!
  "Dispatch an event to the handler.
   Can be used as event handler in cljfx components."
  [event]
  (handle-event event))

(defn event-handler
  "Create a cljfx event handler map for the given event type and params."
  [event-type & {:as params}]
  (merge {:event/type event-type} params))
