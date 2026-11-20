(ns laser-show.events.handlers.menu
  "Event handlers for menu commands (File/Edit/View/Help).
   
   Handles:
   - File menu: New, Open, Save, Save As, Export, Exit
   - Edit menu: Undo, Redo, Copy, Paste, Clear
   - View menu: Toggle Preview, Fullscreen
   - Help menu: Documentation, About, Check Updates, IDN Stream Logging, Zone Routing Debug"
  (:require [clojure.tools.logging :as log]
            [laser-show.dev-config :as dev-config]
            [laser-show.routing.zone-effects :as ze]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.rig-file :as rig-file]
            [laser-show.events.helpers :as h]
            [laser-show.events.handlers.grid :as grid]
            [laser-show.events.handlers.effects :as effects]
            [laser-show.events.handlers.history :as history]))



;; File Menu Events


;; Unsaved-changes guard. New, Open and Exit throw the current document away, so
;; when [:project :dirty?] is true they ask first: the event is parked in
;; [:ui :dialogs :unsaved-confirm :pending] and the dialog is opened. The dialog's
;; buttons then drop it (:file/confirm-cancel), re-dispatch it with :force? true
;; (:file/confirm-discard), or save first and re-dispatch it once the write
;; succeeded (:file/confirm-save).

(def ^:private confirm-path [:ui :dialogs :unsaved-confirm])

(defn- guard-needed?
  "True when the event would discard unsaved work and wasn't already confirmed."
  [state {:keys [force?]}]
  (and (not force?)
       (boolean (get-in state [:project :dirty?]))))

(defn- ask-first
  "Open the unsaved-changes dialog for event (ignored if it is already open)."
  [state event]
  (if (get-in state (conj confirm-path :open?))
    {:state state}
    {:state (update-in state confirm-path assoc
                       :open? true
                       :pending (select-keys event [:event/type]))}))

(defn- close-confirm [state]
  (update-in state confirm-path assoc :open? false :pending nil))

(defn- handle-file-new-project
  "Replace the document with a fresh starter project.
   Backend/runtime state (streaming, Link, MIDI/OSC) is left untouched.
   Asks first when the project has unsaved changes."
  [{:keys [state] :as event}]
  (if (guard-needed? state event)
    (ask-first state event)
    (do
      (log/info "File > New Project")
      {:state (persistent/new-project-state state)})))

(defn- handle-file-open
  "Open a project from disk using file chooser dialog.
   Asks first (before the chooser) when the project has unsaved changes."
  [{:keys [state] :as event}]
  (if (guard-needed? state event)
    (ask-first state event)
    (do
      (log/info "File > Open")
      {:state state
       :fx/show-file-chooser {:title "Open Project"
                              :initial-directory (or persistent/default-projects-dir
                                                    (System/getProperty "user.home"))
                              :extension-filters [{:description "Laser Show Projects"
                                                  :extensions ["*.zip"]}]
                              :on-result {:event/type :file/open-result}}})))

(defn- handle-file-open-result
  "Handle result from open file dialog by requesting a project read."
  [{:keys [file-path]}]
  (if file-path
    (do
      (log/info "Opening project:" file-path)
      {:project/load {:file-path file-path
                      :on-loaded {:event/type :file/project-loaded}
                      :on-failed {:event/type :file/project-load-failed}}})
    {}))

(defn- file-name
  "The file's name without its folders (\"show.zip\"), for a notice."
  [file-path]
  (or (some-> file-path java.io.File. .getName) "the project"))

(defn- handle-file-project-loaded
  "Replace the show with the loaded project contents. This machine's rig,
   its safety zones and calibration with it, stays as it is (D-30): the
   show's copy of a rig is only read, to match the show onto this one. It is
   kept in [:ui :dialogs :rig-report] while the show is open (rig-file):
   on a rig with no laser on, and a show made with lasers, it opens the
   set-up question (Q-ZR-3)."
  [{:keys [state file-path contents]}]
  (log/info "Project loaded:" file-path)
  (let [loaded (persistent/apply-loaded-project state contents file-path)
        hardware (get contents (:hardware persistent/zip-filenames))
        set-up? (boolean (and (not (rig-file/set-up? loaded)) (rig-file/set-up-question loaded hardware)))]
    {:state (assoc-in loaded [:ui :dialogs :rig-report]
                      {:open? set-up?
                       :mode (if set-up? :set-up :report)
                       :file (file-name file-path)
                       :hardware hardware
                       :chains (get-in contents [(:content persistent/zip-filenames) :chains :projector-effects])
                       :pins (rig-file/pin-map state hardware)})}))

(defn- handle-file-project-load-failed
  "The project couldn't be read: the document stays, and a notice says so."
  [{:keys [file-path state]}]
  (log/error "Failed to open project:" file-path)
  (h/show-notice state (str "Could not open " (file-name file-path)) :error))

(defn- save-to
  "Mark the project saved to file-path and request the zip write.
   If the write fails, :file/save-failed restores the previous project status.
   An optional :then event on the event is dispatched only after the write
   succeeded (the unsaved-changes dialog uses it to continue New/Open/Exit)."
  [{:keys [state then] :as event} file-path]
  (log/info "Saving project to:" file-path)
  {:state (persistent/mark-saved state file-path (h/current-time-ms event))
   :project/save (cond-> {:file-path file-path
                          :files (persistent/project-files state)
                          :on-failed {:event/type :file/save-failed
                                      :previous-project (:project state)}}
                   then (assoc :on-saved then))})

(defn- handle-file-save-failed
  "Restore the project status from before the failed save, including the
   previous saved baseline; the pipeline then re-derives :dirty?. A notice
   says the save failed."
  [{:keys [state file-path previous-project]}]
  (log/error "Failed to save project:" file-path)
  (h/show-notice (update state :project merge
                         (into {} (map (fn [k] [k (get previous-project k)]))
                               [:current-file :last-saved :saved-document :dirty?]))
                 (str "Could not save " (file-name file-path))
                 :error))

(defn- handle-file-save-as
  "Save the project to a new location using file chooser dialog.
   An optional :then event is carried through the chooser to the write."
  [{:keys [state then]}]
  (log/info "File > Save As")
  (let [current-file (get-in state [:project :current-file])
        initial-dir (if current-file
                      (.getParent (java.io.File. current-file))
                      (or persistent/default-projects-dir
                          (System/getProperty "user.home")))]
    {:state state
     :fx/show-file-chooser {:title "Save Project As"
                            :initial-directory initial-dir
                            :initial-file-name (or (when current-file
                                                    (.getName (java.io.File. current-file)))
                                                  (persistent/get-default-project-filename))
                            :extension-filters [{:description "Laser Show Projects"
                                                :extensions ["*.zip"]}]
                            :mode :save
                            :on-result (cond-> {:event/type :file/save-as-result}
                                         then (assoc :then then))}}))

(defn- handle-file-save
  "Save the current project."
  [{:keys [state] :as event}]
  (if-let [current-file (get-in state [:project :current-file])]
    (save-to event current-file)
    ;; No current file, trigger Save As
    (handle-file-save-as event)))

(defn- handle-file-save-as-result
  "Handle result from save-as file dialog."
  [{:keys [state file-path] :as event}]
  (if file-path
    (save-to event file-path)
    {:state state}))

(defn- handle-file-export
  "Export project data (TODO: Implement export dialog)."
  [{:keys [state]}]
  (log/debug "File > Export")
  ;; TODO: Show export options dialog
  {:state state})

(defn- handle-file-exit
  "Exit the application. Asks first when the project has unsaved changes.
   Also the handler for closing the main window."
  [{:keys [state] :as event}]
  (log/debug "File > Exit")
  (if (guard-needed? state event)
    (ask-first state event)
    {:system/exit true}))


;; Unsaved-changes dialog buttons


(defn- pending-action [state]
  (get-in state (conj confirm-path :pending)))

(defn- handle-file-confirm-cancel
  "Cancel: drop the parked action; the document stays as it is."
  [{:keys [state]}]
  {:state (close-confirm state)})

(defn- handle-file-confirm-discard
  "Don't Save: carry on with the parked action, unsaved changes and all."
  [{:keys [state]}]
  (let [pending (pending-action state)]
    (cond-> {:state (close-confirm state)}
      pending (assoc :dispatch (assoc pending :force? true)))))

(defn- handle-file-confirm-save
  "Save: write the project (asking for a file name first if it has none), then
   carry on with the parked action once the write has succeeded. Cancelling the
   file chooser or a failed write ends the chain with nothing lost."
  [{:keys [state] :as event}]
  (let [pending (pending-action state)
        closed (close-confirm state)]
    (if pending
      (handle-file-save (assoc event
                               :state closed
                               :then (assoc pending :force? true)))
      {:state closed})))


;; Edit Menu Events
;; Note: Copy/Paste/Clear need to be delegated to grid-handlers or effects-handlers
;; depending on active tab. These will be imported when handlers.clj is refactored.


(defn- handle-edit-undo
  "Undo the last action via history handler."
  [event]
  (log/debug "Edit > Undo")
  (history/handle-undo event))

(defn- handle-edit-redo
  "Redo the last undone action via history handler."
  [event]
  (log/debug "Edit > Redo")
  (history/handle-redo event))

(defn- handle-edit-copy
  "Copy selected cell to clipboard.
   Delegates to grid or effects handler depending on active tab.
   Falls back to active playing cue on grid tab if no cell is explicitly selected."
  [{:keys [state]}]
  (let [active-tab (get-in state [:ui :active-tab])
        selected-cell (case active-tab
                        :grid (or (get-in state [:grid :selected-cell])
                                  (first (keys (get-in state [:playback :active-cues]))))
                        :effects (get-in state [:effects :selected-cell])
                        nil)]
    (if selected-cell
      (let [[col row] selected-cell]
        (log/debug "Edit > Copy: delegating to" active-tab "handler" {:col col :row row})
        (case active-tab
          :grid (grid/handle {:event/type :grid/copy-cell :col col :row row :state state})
          :effects (effects/handle {:event/type :effects/copy-cell :col col :row row :state state})
          {:state state}))
      (do
        (log/debug "Edit > Copy: No cell selected")
        {:state state}))))

(defn- handle-edit-paste
  "Paste clipboard to selected cell.
   Delegates to grid or effects handler depending on active tab."
  [{:keys [state]}]
  (let [active-tab (get-in state [:ui :active-tab])
        selected-cell (case active-tab
                        :grid (get-in state [:grid :selected-cell])
                        :effects (get-in state [:effects :selected-cell])
                        nil)]
    (if selected-cell
      (let [[col row] selected-cell]
        (log/debug "Edit > Paste: delegating to" active-tab "handler" {:col col :row row})
        (case active-tab
          :grid (grid/handle {:event/type :grid/paste-cell :col col :row row :state state})
          :effects (effects/handle {:event/type :effects/paste-cell :col col :row row :state state})
          {:state state}))
      (do
        (log/debug "Edit > Paste: No cell selected")
        {:state state}))))

(defn- handle-edit-clear-cell
  "Clear the selected cell.
   Delegates to grid or effects handler depending on active tab."
  [{:keys [state]}]
  (let [active-tab (get-in state [:ui :active-tab])
        selected-cell (case active-tab
                        :grid (get-in state [:grid :selected-cell])
                        :effects (get-in state [:effects :selected-cell])
                        nil)]
    (if selected-cell
      (let [[col row] selected-cell]
        (log/debug "Edit > Clear Cell: delegating to" active-tab "handler" {:col col :row row})
        (case active-tab
          :grid (grid/handle {:event/type :grid/clear-cell :col col :row row :state state})
          :effects (effects/handle {:event/type :effects/clear-cell :col col :row row :state state})
          {:state state}))
      (do
        (log/debug "Edit > Clear Cell: No cell selected")
        {:state state}))))


;; View Menu Events


(defn- handle-view-toggle-preview
  "Toggle preview panel visibility (TODO: Implement preview toggle)."
  [{:keys [state]}]
  (log/debug "View > Toggle Preview")
  ;; TODO: Implement preview panel show/hide
  {:state (update-in state [:ui :preview-visible?] not)})

(defn- handle-view-fullscreen
  "Toggle fullscreen mode (TODO: Implement fullscreen)."
  [{:keys [state]}]
  (log/debug "View > Fullscreen")
  ;; TODO: Implement fullscreen toggle via JavaFX stage
  {:state (update-in state [:ui :fullscreen?] not)})


;; Help Menu Events


(def ^:private documentation-url
  "https://github.com/zbear0808/laser-idn-project/tree/master/docs")

(defn- handle-help-documentation
  "Open documentation in browser."
  [_event]
  (log/debug "Help > Documentation")
  {:system/open-url documentation-url})

(defn- handle-help-about
  "Show About dialog."
  [{:keys [state]}]
  (log/debug "Help > About")
  {:state (assoc-in state [:ui :dialogs :about :open?] true)})

(defn- handle-help-check-updates
  "Check for application updates."
  [{:keys [state]}]
  (log/debug "Help > Check for Updates")
  ;; TODO: Implement update check
  {:state state})

(defn- handle-help-toggle-idn-stream-logging
  "Toggle IDN stream debug logging on/off."
  [{:keys [state]}]
  (let [current (dev-config/idn-stream-logging?)
        new-value (not current)]
    (dev-config/set-idn-stream-logging! new-value)
    (log/info "IDN Stream Logging:" (if new-value "enabled" "disabled"))
    {:state (assoc-in state [:debug :idn-stream-logging?] new-value)}))

(defn- handle-help-toggle-zone-routing-debug
  "Toggle zone routing debug logging on/off.
   Logs zone routing information once per playback session when enabled."
  [{:keys [state]}]
  (let [current (get-in state [:debug :zone-routing-debug?] false)
        new-value (not current)]
    (if new-value
      (ze/enable-routing-debug!)
      (ze/disable-routing-debug!))
    (log/info "Zone Routing Debug:" (if new-value "enabled" "disabled"))
    {:state (assoc-in state [:debug :zone-routing-debug?] new-value)}))

(defn- handle-help-reload-styles
  "Reload all CSS stylesheets (dev only). The reload bumps
   [:styles :reload-trigger] itself, so this handler returns no :state that
   could overwrite it."
  [_event]
  (log/info "Help > Reload Styles")
  {:dev/reload :styles})

(defn- handle-help-reload-app
  "Reload all app code without restarting the JVM (dev only)."
  [_event]
  (log/info "Help > Reload App Code")
  {:dev/reload :app})

(defn- handle-help-save-state-debug
  "Save current application state to an EDN file for debugging."
  [{:keys [state]}]
  (log/info "Help > Save State Debug")
  (require 'laser-show.state.core)
  (let [path ((resolve 'laser-show.state.core/save-state-debug!))]
    (log/info "State debug saved to:" path))
  {:state state})


;; Public API


(defn handle
  "Dispatch menu events to their handlers.
   
   Accepts events with :event/type in the :file/*, :edit/*, :view/*, or :help/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    ;; File menu
    :file/new-project (handle-file-new-project event)
    :file/open (handle-file-open event)
    :file/open-result (handle-file-open-result event)
    :file/project-loaded (handle-file-project-loaded event)
    :file/project-load-failed (handle-file-project-load-failed event)
    :file/save (handle-file-save event)
    :file/save-as (handle-file-save-as event)
    :file/save-as-result (handle-file-save-as-result event)
    :file/save-failed (handle-file-save-failed event)
    :file/export (handle-file-export event)
    :file/exit (handle-file-exit event)
    :file/confirm-save (handle-file-confirm-save event)
    :file/confirm-discard (handle-file-confirm-discard event)
    :file/confirm-cancel (handle-file-confirm-cancel event)
    
    ;; Edit menu
    :edit/undo (handle-edit-undo event)
    :edit/redo (handle-edit-redo event)
    :edit/copy (handle-edit-copy event)
    :edit/paste (handle-edit-paste event)
    :edit/clear-cell (handle-edit-clear-cell event)
    
    ;; View menu
    :view/toggle-preview (handle-view-toggle-preview event)
    :view/fullscreen (handle-view-fullscreen event)
    
    ;; Help menu
    :help/documentation (handle-help-documentation event)
    :help/about (handle-help-about event)
    :help/check-updates (handle-help-check-updates event)
    :help/toggle-idn-stream-logging (handle-help-toggle-idn-stream-logging event)
    :help/toggle-zone-routing-debug (handle-help-toggle-zone-routing-debug event)
    :help/reload-styles (handle-help-reload-styles event)
    :help/reload-app (handle-help-reload-app event)
    :help/save-state-debug (handle-help-save-state-debug event)
    
    ;; Unknown event in this domain
    {}))
