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
  "Update a parameter in an effect.
   Value comes from :fx/event when using map event handlers."
  [{:keys [col row effect-idx param-key state] :as event}]
  (let [value (or (:fx/event event) (:value event))
        effects-vec (vec (get-in state [:effects :cells [col row] :effects] []))
        updated-effects (assoc-in effects-vec [effect-idx :params param-key] value)]
    {:state (assoc-in state [:effects :cells [col row] :effects] updated-effects)}))

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

(defn- handle-effects-copy-cell
  "Copy an effects cell to clipboard."
  [{:keys [col row state]}]
  (let [cell-data (get-in state [:effects :cells [col row]])]
    {:state (assoc-in state [:ui :clipboard]
                      {:type :effects-cell
                       :data cell-data})}))

(defn- handle-effects-paste-cell
  "Paste clipboard to an effects cell."
  [{:keys [col row state]}]
  (let [clipboard (get-in state [:ui :clipboard])]
    (if (and clipboard (= :effects-cell (:type clipboard)))
      {:state (-> state
                  (assoc-in [:effects :cells [col row]] (:data clipboard))
                  mark-dirty)}
      {:state state})))

(defn- handle-effects-move-cell
  "Move an effects cell from one position to another."
  [{:keys [from-col from-row to-col to-row state]}]
  (let [cell-data (get-in state [:effects :cells [from-col from-row]])]
    (if cell-data
      {:state (-> state
                  (update-in [:effects :cells] dissoc [from-col from-row])
                  (assoc-in [:effects :cells [to-col to-row]] cell-data)
                  mark-dirty)}
      {:state state})))

(defn- handle-effects-select-cell
  "Select an effects cell for editing."
  [{:keys [col row state]}]
  {:state (assoc-in state [:effects :selected-cell] [col row])})

(defn- handle-effects-remove-from-chain-and-clear-selection
  "Remove an effect from chain and clear the dialog selection if needed.
   Used by the effect chain editor dialog."
  [{:keys [col row effect-idx state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
        new-effects (vec (concat (subvec effects-vec 0 effect-idx)
                                 (subvec effects-vec (inc effect-idx))))
        selected-indices (get-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})
        new-indices (-> selected-indices
                        (disj effect-idx)
                        (->> (mapv (fn [idx]
                                     (if (> idx effect-idx)
                                       (dec idx)
                                       idx)))
                             (into #{})))]
    {:state (-> state
                (assoc-in [:effects :cells [col row] :effects] new-effects)
                (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] new-indices)
                mark-dirty)}))

;; ============================================================================
;; Effect Chain Editor Multi-Select Events
;; ============================================================================

(defn- handle-effects-select-effect
  "Select a single effect in the chain editor (replaces existing selection)."
  [{:keys [effect-idx state]}]
  {:state (assoc-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices]
                    #{effect-idx})})

(defn- handle-effects-toggle-effect-selection
  "Toggle an effect's selection state (Ctrl+click behavior)."
  [{:keys [effect-idx state]}]
  (let [current-indices (get-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})
        new-indices (if (contains? current-indices effect-idx)
                      (disj current-indices effect-idx)
                      (conj current-indices effect-idx))]
    {:state (-> state
                (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] new-indices)
                (assoc-in [:ui :dialogs :effect-chain-editor :data :last-selected-idx] effect-idx))}))

(defn- handle-effects-range-select
  "Range select effects from last selected to clicked (Shift+click behavior)."
  [{:keys [effect-idx state]}]
  (let [last-idx (get-in state [:ui :dialogs :effect-chain-editor :data :last-selected-idx])
        start-idx (or last-idx 0)
        [from to] (if (<= start-idx effect-idx)
                    [start-idx effect-idx]
                    [effect-idx start-idx])
        range-indices (into #{} (range from (inc to)))]
    {:state (assoc-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices]
                      range-indices)}))

(defn- handle-effects-select-all
  "Select all effects in the current chain."
  [{:keys [col row state]}]
  (let [effects-count (count (get-in state [:effects :cells [col row] :effects] []))
        all-indices (into #{} (range effects-count))]
    {:state (assoc-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices]
                      all-indices)}))

(defn- handle-effects-clear-selection
  "Clear all effect selection in chain editor."
  [{:keys [state]}]
  {:state (assoc-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})})

(defn- handle-effects-copy-selected
  "Copy selected effects to clipboard."
  [{:keys [col row state]}]
  (let [selected-indices (get-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})
        effects-vec (get-in state [:effects :cells [col row] :effects] [])
        sorted-indices (sort selected-indices)
        selected-effects (mapv #(nth effects-vec % nil) sorted-indices)
        valid-effects (filterv some? selected-effects)]
    (if (seq valid-effects)
      (if (= 1 (count valid-effects))
        ;; Single effect - use :effect type
        {:state state
         :clipboard/copy-effect (first valid-effects)}
        ;; Multiple effects - use :effect-chain type  
        {:state state
         :clipboard/copy-effects valid-effects})
      {:state state})))

(defn- handle-effects-paste-into-chain
  "Paste effects from clipboard into current chain."
  [{:keys [col row state]}]
  (let [;; Get paste position (after last selected, or at end)
        selected-indices (get-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})
        effects-vec (get-in state [:effects :cells [col row] :effects] [])
        insert-pos (if (seq selected-indices)
                     (inc (apply max selected-indices))
                     (count effects-vec))]
    {:state state
     :clipboard/paste-effects {:col col
                               :row row
                               :insert-pos insert-pos}}))

(defn- handle-effects-insert-pasted
  "Actually insert pasted effects into the chain (called by effect handler)."
  [{:keys [col row insert-pos effects state]}]
  (let [ensure-cell (fn [s]
                      (if (get-in s [:effects :cells [col row]])
                        s
                        (assoc-in s [:effects :cells [col row]] {:effects [] :active true})))
        current-effects (get-in state [:effects :cells [col row] :effects] [])
        safe-pos (min insert-pos (count current-effects))
        new-effects (vec (concat (subvec current-effects 0 safe-pos)
                                 effects
                                 (subvec current-effects safe-pos)))
        ;; Select the newly pasted effects
        new-indices (into #{} (range safe-pos (+ safe-pos (count effects))))]
    {:state (-> state
                ensure-cell
                (assoc-in [:effects :cells [col row] :effects] new-effects)
                (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] new-indices)
                mark-dirty)}))

(defn- handle-effects-delete-selected
  "Delete all selected effects from the chain."
  [{:keys [col row state]}]
  (let [selected-indices (get-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})
        effects-vec (get-in state [:effects :cells [col row] :effects] [])]
    (if (seq selected-indices)
      (let [new-effects (vec (keep-indexed
                               (fn [idx effect]
                                 (when-not (contains? selected-indices idx)
                                   effect))
                               effects-vec))]
        {:state (-> state
                    (assoc-in [:effects :cells [col row] :effects] new-effects)
                    (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})
                    mark-dirty)})
      {:state state})))

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

(defn- handle-ui-update-dialog-data
  "Update data associated with a dialog (e.g., selected item within dialog)."
  [{:keys [dialog-id updates state]}]
  {:state (update-in state [:ui :dialogs dialog-id :data] merge updates)})

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
;; File Menu Events
;; ============================================================================

(defn- handle-file-new-project
  "Create a new project (TODO: Implement confirmation dialog if dirty)."
  [{:keys [state]}]
  (println "File > New Project")
  ;; TODO: Show confirmation dialog if project is dirty
  ;; For now, just log
  {:state state})

(defn- handle-file-open
  "Open a project from disk (TODO: Implement file dialog)."
  [{:keys [state]}]
  (println "File > Open")
  ;; TODO: Show file chooser dialog
  ;; :file/open-dialog effect to be implemented
  {:state state})

(defn- handle-file-save
  "Save the current project (TODO: Implement save logic)."
  [{:keys [state]}]
  (println "File > Save")
  ;; TODO: Implement actual save to disk
  ;; For now, just mark as clean
  {:state (-> state
              (assoc-in [:project :dirty?] false)
              (assoc-in [:project :last-saved] (System/currentTimeMillis)))})

(defn- handle-file-save-as
  "Save the project to a new location (TODO: Implement file dialog)."
  [{:keys [state]}]
  (println "File > Save As")
  ;; TODO: Show file chooser dialog and save
  {:state state})

(defn- handle-file-export
  "Export project data (TODO: Implement export dialog)."
  [{:keys [state]}]
  (println "File > Export")
  ;; TODO: Show export options dialog
  {:state state})

(defn- handle-file-exit
  "Exit the application."
  [{:keys [state]}]
  (println "File > Exit")
  ;; TODO: Show confirmation dialog if project is dirty
  ;; For now, just exit
  {:system/exit true})

;; ============================================================================
;; Edit Menu Events
;; ============================================================================

(defn- handle-edit-undo
  "Undo the last action (TODO: Implement undo stack)."
  [{:keys [state]}]
  (println "Edit > Undo")
  ;; TODO: Implement undo/redo system
  {:state state})

(defn- handle-edit-redo
  "Redo the last undone action (TODO: Implement redo stack)."
  [{:keys [state]}]
  (println "Edit > Redo")
  ;; TODO: Implement undo/redo system
  {:state state})

(defn- handle-edit-copy
  "Copy selected cell to clipboard."
  [{:keys [state]}]
  (let [active-tab (get-in state [:ui :active-tab])
        selected-cell (case active-tab
                        :grid (get-in state [:grid :selected-cell])
                        :effects (get-in state [:effects :selected-cell])
                        nil)]
    (if selected-cell
      (let [[col row] selected-cell]
        (case active-tab
          :grid (handle-grid-copy-cell {:col col :row row :state state})
          :effects (handle-effects-copy-cell {:col col :row row :state state})
          {:state state}))
      (do
        (println "Edit > Copy: No cell selected")
        {:state state}))))

(defn- handle-edit-paste
  "Paste clipboard to selected cell."
  [{:keys [state]}]
  (let [active-tab (get-in state [:ui :active-tab])
        selected-cell (case active-tab
                        :grid (get-in state [:grid :selected-cell])
                        :effects (get-in state [:effects :selected-cell])
                        nil)]
    (if selected-cell
      (let [[col row] selected-cell]
        (case active-tab
          :grid (handle-grid-paste-cell {:col col :row row :state state})
          :effects (handle-effects-paste-cell {:col col :row row :state state})
          {:state state}))
      (do
        (println "Edit > Paste: No cell selected")
        {:state state}))))

(defn- handle-edit-clear-cell
  "Clear the selected cell."
  [{:keys [state]}]
  (let [active-tab (get-in state [:ui :active-tab])
        selected-cell (case active-tab
                        :grid (get-in state [:grid :selected-cell])
                        :effects (get-in state [:effects :selected-cell])
                        nil)]
    (if selected-cell
      (let [[col row] selected-cell]
        (case active-tab
          :grid (handle-grid-clear-cell {:col col :row row :state state})
          :effects (handle-effects-clear-cell {:col col :row row :state state})
          {:state state}))
      (do
        (println "Edit > Clear Cell: No cell selected")
        {:state state}))))

;; ============================================================================
;; View Menu Events
;; ============================================================================

(defn- handle-view-toggle-preview
  "Toggle preview panel visibility (TODO: Implement preview toggle)."
  [{:keys [state]}]
  (println "View > Toggle Preview")
  ;; TODO: Implement preview panel show/hide
  {:state (update-in state [:ui :preview-visible?] not)})

(defn- handle-view-fullscreen
  "Toggle fullscreen mode (TODO: Implement fullscreen)."
  [{:keys [state]}]
  (println "View > Fullscreen")
  ;; TODO: Implement fullscreen toggle via JavaFX stage
  {:state (update-in state [:ui :fullscreen?] not)})

;; ============================================================================
;; Help Menu Events
;; ============================================================================

(defn- handle-help-documentation
  "Open documentation in browser."
  [{:keys [state]}]
  (println "Help > Documentation")
  ;; TODO: Open URL in system browser
  {:state state
   :system/open-url "https://github.com/your-repo/docs"})

(defn- handle-help-about
  "Show About dialog."
  [{:keys [state]}]
  (println "Help > About")
  {:state (assoc-in state [:ui :dialogs :about :open?] true)})

(defn- handle-help-check-updates
  "Check for application updates."
  [{:keys [state]}]
  (println "Help > Check for Updates")
  ;; TODO: Implement update check
  {:state state})

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
    :effects/copy-cell (handle-effects-copy-cell event)
    :effects/paste-cell (handle-effects-paste-cell event)
    :effects/move-cell (handle-effects-move-cell event)
    :effects/select-cell (handle-effects-select-cell event)
    :effects/remove-from-chain-and-clear-selection (handle-effects-remove-from-chain-and-clear-selection event)
    
    ;; Effect chain editor multi-select events
    :effects/select-effect (handle-effects-select-effect event)
    :effects/toggle-effect-selection (handle-effects-toggle-effect-selection event)
    :effects/range-select (handle-effects-range-select event)
    :effects/select-all (handle-effects-select-all event)
    :effects/clear-selection (handle-effects-clear-selection event)
    :effects/copy-selected (handle-effects-copy-selected event)
    :effects/paste-into-chain (handle-effects-paste-into-chain event)
    :effects/insert-pasted (handle-effects-insert-pasted event)
    :effects/delete-selected (handle-effects-delete-selected event)
    
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
    :ui/update-dialog-data (handle-ui-update-dialog-data event)
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
    
    ;; File menu events
    :file/new-project (handle-file-new-project event)
    :file/open (handle-file-open event)
    :file/save (handle-file-save event)
    :file/save-as (handle-file-save-as event)
    :file/export (handle-file-export event)
    :file/exit (handle-file-exit event)
    
    ;; Edit menu events
    :edit/undo (handle-edit-undo event)
    :edit/redo (handle-edit-redo event)
    :edit/copy (handle-edit-copy event)
    :edit/paste (handle-edit-paste event)
    :edit/clear-cell (handle-edit-clear-cell event)
    
    ;; View menu events
    :view/toggle-preview (handle-view-toggle-preview event)
    :view/fullscreen (handle-view-fullscreen event)
    
    ;; Help menu events
    :help/documentation (handle-help-documentation event)
    :help/about (handle-help-about event)
    :help/check-updates (handle-help-check-updates event)
    
    ;; Unknown event
    (do
      (println "Unknown event type:" type)
      {})))
