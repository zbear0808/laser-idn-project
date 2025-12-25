(ns laser-show.events.handlers
  "Pure event handlers for the laser show application.
   
   This module contains PURE FUNCTIONS that transform state.
   
   Event handlers:
   - Receive event map with :event/type and co-effects (:state, :time)
   - Return effects map (:state, :dispatch, custom effects)
   
   Benefits of pure handlers:
   - Easy to test: (handle-event event) => effects-map
   - No mocks needed
   - Clear data flow
   - Composable
   
   Usage:
   (handle-event {:event/type :grid/trigger-cell :col 0 :row 0 :state current-state})
   => {:state new-state}"
  (:require [laser-show.animation.time :as anim-time]))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn- mark-dirty
  "Mark project as having unsaved changes."
  [state]
  (assoc-in state [:project :dirty?] true))

(defn- current-time-ms
  "Get current time from event or system."
  [event]
  (or (:time event) (System/currentTimeMillis)))

;; ============================================================================
;; Grid Events
;; ============================================================================

(defn- handle-grid-trigger-cell
  "Trigger a cell to start playing its preset."
  [{:keys [col row state] :as event}]
  (let [now (current-time-ms event)]
    {:state (-> state
                (assoc-in [:playback :active-cell] [col row])
                (assoc-in [:playback :playing?] true)
                (assoc-in [:playback :trigger-time] now))}))

(defn- handle-grid-select-cell
  "Select a cell for editing."
  [{:keys [col row state]}]
  {:state (assoc-in state [:grid :selected-cell] [col row])})

(defn- handle-grid-deselect-cell
  "Clear cell selection."
  [{:keys [state]}]
  {:state (assoc-in state [:grid :selected-cell] nil)})

(defn- handle-grid-set-cell-preset
  "Set a preset for a grid cell."
  [{:keys [col row preset-id state]}]
  {:state (-> state
              (assoc-in [:grid :cells [col row]] {:preset-id preset-id})
              mark-dirty)})

(defn- handle-grid-clear-cell
  "Clear a grid cell."
  [{:keys [col row state]}]
  (let [active-cell (get-in state [:playback :active-cell])
        clearing-active? (= [col row] active-cell)]
    {:state (-> state
                (update-in [:grid :cells] dissoc [col row])
                (cond-> clearing-active?
                  (-> (assoc-in [:playback :playing?] false)
                      (assoc-in [:playback :active-cell] nil)))
                mark-dirty)}))

(defn- handle-grid-move-cell
  "Move a cell from one position to another."
  [{:keys [from-col from-row to-col to-row state]}]
  (let [cell-data (get-in state [:grid :cells [from-col from-row]])]
    (if cell-data
      {:state (-> state
                  (update-in [:grid :cells] dissoc [from-col from-row])
                  (assoc-in [:grid :cells [to-col to-row]] cell-data)
                  mark-dirty)}
      {:state state})))

(defn- handle-grid-copy-cell
  "Copy a cell to clipboard."
  [{:keys [col row state]}]
  (let [cell-data (get-in state [:grid :cells [col row]])]
    {:state (assoc-in state [:ui :clipboard]
                      {:type :grid-cell
                       :data cell-data})}))

(defn- handle-grid-paste-cell
  "Paste clipboard to a cell."
  [{:keys [col row state]}]
  (let [clipboard (get-in state [:ui :clipboard])]
    (if (and clipboard (= :grid-cell (:type clipboard)))
      {:state (-> state
                  (assoc-in [:grid :cells [col row]] (:data clipboard))
                  mark-dirty)}
      {:state state})))

;; ============================================================================
;; Effects Grid Events
;; ============================================================================

(defn- handle-effects-toggle-cell
  "Toggle an effects cell on/off."
  [{:keys [col row state]}]
  (let [current-active (get-in state [:effects :cells [col row] :active] false)]
    {:state (assoc-in state [:effects :cells [col row] :active] (not current-active))}))

(defn- handle-effects-add-effect
  "Add an effect to a cell's chain."
  [{:keys [col row effect state]}]
  (let [ensure-cell (fn [s]
                      (if (get-in s [:effects :cells [col row]])
                        s
                        (assoc-in s [:effects :cells [col row]] {:effects [] :active true})))]
    {:state (-> state
                ensure-cell
                (update-in [:effects :cells [col row] :effects] conj effect)
                mark-dirty)}))

(defn- handle-effects-remove-effect
  "Remove an effect from a cell's chain by index."
  [{:keys [col row effect-idx state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
        new-effects (vec (concat (subvec effects-vec 0 effect-idx)
                                 (subvec effects-vec (inc effect-idx))))]
    {:state (-> state
                (assoc-in [:effects :cells [col row] :effects] new-effects)
                mark-dirty)}))

(defn- handle-effects-update-param
  "Update a parameter in an effect."
  [{:keys [col row effect-idx param-key value state]}]
  {:state (assoc-in state [:effects :cells [col row] :effects effect-idx :params param-key] value)})

(defn- handle-effects-clear-cell
  "Clear all effects from a cell."
  [{:keys [col row state]}]
  {:state (-> state
              (update-in [:effects :cells] dissoc [col row])
              mark-dirty)})

(defn- handle-effects-reorder
  "Reorder effects in a cell's chain."
  [{:keys [col row from-idx to-idx state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
        effect (nth effects-vec from-idx)
        without (vec (concat (subvec effects-vec 0 from-idx)
                             (subvec effects-vec (inc from-idx))))
        reordered (vec (concat (subvec without 0 to-idx)
                               [effect]
                               (subvec without to-idx)))]
    {:state (-> state
                (assoc-in [:effects :cells [col row] :effects] reordered)
                mark-dirty)}))

;; ============================================================================
;; Timing Events
;; ============================================================================

(defn- handle-timing-set-bpm
  "Set the BPM."
  [{:keys [bpm state]}]
  {:state (assoc-in state [:timing :bpm] (double bpm))})

(defn- handle-timing-tap-tempo
  "Record a tap for tap-tempo calculation."
  [{:keys [state] :as event}]
  (let [now (current-time-ms event)]
    {:state (update-in state [:timing :tap-times] conj now)
     :timing/calculate-bpm true}))

(defn- handle-timing-clear-taps
  "Clear tap tempo timestamps."
  [{:keys [state]}]
  {:state (assoc-in state [:timing :tap-times] [])})

(defn- handle-timing-set-quantization
  "Set quantization mode."
  [{:keys [mode state]}]
  {:state (assoc-in state [:timing :quantization] mode)})

;; ============================================================================
;; Transport Events
;; ============================================================================

(defn- handle-transport-play
  "Start playback."
  [{:keys [state]}]
  {:state (assoc-in state [:playback :playing?] true)})

(defn- handle-transport-stop
  "Stop playback."
  [{:keys [state]}]
  {:state (-> state
              (assoc-in [:playback :playing?] false)
              (assoc-in [:playback :active-cell] nil))})

(defn- handle-transport-retrigger
  "Retrigger the current animation."
  [{:keys [state] :as event}]
  (let [now (current-time-ms event)]
    {:state (assoc-in state [:playback :trigger-time] now)}))

;; ============================================================================
;; UI Events
;; ============================================================================

(defn- handle-ui-set-active-tab
  "Change the active tab."
  [{:keys [tab state]}]
  {:state (assoc-in state [:ui :active-tab] tab)})

(defn- handle-ui-select-preset
  "Select a preset in the browser."
  [{:keys [preset-id state]}]
  {:state (assoc-in state [:ui :selected-preset] preset-id)})

(defn- handle-ui-open-dialog
  "Open a dialog."
  [{:keys [dialog-id data state]}]
  {:state (-> state
              (assoc-in [:ui :dialogs dialog-id :open?] true)
              (assoc-in [:ui :dialogs dialog-id :data] data))})

(defn- handle-ui-close-dialog
  "Close a dialog."
  [{:keys [dialog-id state]}]
  {:state (assoc-in state [:ui :dialogs dialog-id :open?] false)})

(defn- handle-ui-start-drag
  "Start a drag operation."
  [{:keys [source-type source-key data state]}]
  {:state (assoc-in state [:ui :drag]
                    {:active? true
                     :source-type source-type
                     :source-key source-key
                     :data data})})

(defn- handle-ui-end-drag
  "End a drag operation."
  [{:keys [state]}]
  {:state (assoc-in state [:ui :drag]
                    {:active? false
                     :source-type nil
                     :source-key nil
                     :data nil})})

;; ============================================================================
;; Project Events
;; ============================================================================

(defn- handle-project-mark-dirty
  "Mark project as having unsaved changes."
  [{:keys [state]}]
  {:state (assoc-in state [:project :dirty?] true)})

(defn- handle-project-mark-clean
  "Mark project as saved."
  [{:keys [state] :as event}]
  (let [now (current-time-ms event)]
    {:state (-> state
                (assoc-in [:project :dirty?] false)
                (assoc-in [:project :last-saved] now))}))

(defn- handle-project-set-folder
  "Set the current project folder."
  [{:keys [folder state]}]
  {:state (assoc-in state [:project :current-folder] folder)})

;; ============================================================================
;; IDN Connection Events
;; ============================================================================

(defn- handle-idn-connect
  "Start IDN connection."
  [{:keys [host port state]}]
  {:state (-> state
              (assoc-in [:backend :idn :connecting?] true)
              (assoc-in [:backend :idn :error] nil))
   :idn/start-streaming {:host host :port (or port 7255)}})

(defn- handle-idn-connected
  "IDN connection established."
  [{:keys [engine target state]}]
  {:state (-> state
              (assoc-in [:backend :idn :connected?] true)
              (assoc-in [:backend :idn :connecting?] false)
              (assoc-in [:backend :idn :target] target)
              (assoc-in [:backend :idn :streaming-engine] engine))})

(defn- handle-idn-connection-failed
  "IDN connection failed."
  [{:keys [error state]}]
  {:state (-> state
              (assoc-in [:backend :idn :connected?] false)
              (assoc-in [:backend :idn :connecting?] false)
              (assoc-in [:backend :idn :error] error))})

(defn- handle-idn-disconnect
  "Disconnect from IDN target."
  [{:keys [state]}]
  {:state (-> state
              (assoc-in [:backend :idn :connected?] false)
              (assoc-in [:backend :idn :connecting?] false)
              (assoc-in [:backend :idn :target] nil)
              (assoc-in [:backend :idn :streaming-engine] nil))
   :idn/stop-streaming true})

;; ============================================================================
;; Config Events
;; ============================================================================

(defn- handle-config-update
  "Update a config value."
  [{:keys [path value state]}]
  {:state (assoc-in state (into [:config] path) value)})

;; ============================================================================
;; Main Event Handler
;; ============================================================================

(defn handle-event
  "Main event handler - PURE FUNCTION.
   
   Input: Event map with :event/type and co-effects (:state, :time)
   Output: Effects map (:state, :dispatch, custom effects)
   
   This is the central event dispatcher. All events flow through here."
  [{:keys [event/type] :as event}]
  (case type
    ;; Grid events
    :grid/trigger-cell (handle-grid-trigger-cell event)
    :grid/select-cell (handle-grid-select-cell event)
    :grid/deselect-cell (handle-grid-deselect-cell event)
    :grid/set-cell-preset (handle-grid-set-cell-preset event)
    :grid/clear-cell (handle-grid-clear-cell event)
    :grid/move-cell (handle-grid-move-cell event)
    :grid/copy-cell (handle-grid-copy-cell event)
    :grid/paste-cell (handle-grid-paste-cell event)
    
    ;; Effects events
    :effects/toggle-cell (handle-effects-toggle-cell event)
    :effects/add-effect (handle-effects-add-effect event)
    :effects/remove-effect (handle-effects-remove-effect event)
    :effects/update-param (handle-effects-update-param event)
    :effects/clear-cell (handle-effects-clear-cell event)
    :effects/reorder (handle-effects-reorder event)
    
    ;; Timing events
    :timing/set-bpm (handle-timing-set-bpm event)
    :timing/tap-tempo (handle-timing-tap-tempo event)
    :timing/clear-taps (handle-timing-clear-taps event)
    :timing/set-quantization (handle-timing-set-quantization event)
    
    ;; Transport events
    :transport/play (handle-transport-play event)
    :transport/stop (handle-transport-stop event)
    :transport/retrigger (handle-transport-retrigger event)
    
    ;; UI events
    :ui/set-active-tab (handle-ui-set-active-tab event)
    :ui/select-preset (handle-ui-select-preset event)
    :ui/open-dialog (handle-ui-open-dialog event)
    :ui/close-dialog (handle-ui-close-dialog event)
    :ui/start-drag (handle-ui-start-drag event)
    :ui/end-drag (handle-ui-end-drag event)
    
    ;; Project events
    :project/mark-dirty (handle-project-mark-dirty event)
    :project/mark-clean (handle-project-mark-clean event)
    :project/set-folder (handle-project-set-folder event)
    
    ;; IDN events
    :idn/connect (handle-idn-connect event)
    :idn/connected (handle-idn-connected event)
    :idn/connection-failed (handle-idn-connection-failed event)
    :idn/disconnect (handle-idn-disconnect event)
    
    ;; Config events
    :config/update (handle-config-update event)
    
    ;; Unknown event
    (do
      (println "Unknown event type:" type)
      {})))
