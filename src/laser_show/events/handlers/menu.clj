(ns laser-show.events.handlers.menu
  "Event handlers for menu commands (File/Edit/View/Help).
   
   Handles:
   - File menu: New, Open, Save, Save As, Export, Exit
   - Edit menu: Undo, Redo, Copy, Paste, Clear
   - View menu: Toggle Preview, Fullscreen
   - Help menu: Documentation, About, Check Updates, IDN Stream Logging"
  (:require [clojure.tools.logging :as log]
            [laser-show.dev-config :as dev-config]
            [laser-show.state.persistent :as persistent]))



;; File Menu Events


(defn- handle-file-new-project
  "Create a new project."
  [{:keys [state]}]
  (log/info "File > New Project")
  ;; TODO: Show confirmation dialog if project is dirty
  (persistent/new-project!)
  {:state state})

(defn- handle-file-open
  "Open a project from disk using file chooser dialog."
  [{:keys [state]}]
  (log/info "File > Open")
  {:state state
   :fx/show-file-chooser {:title "Open Project"
                          :initial-directory (or persistent/default-projects-dir
                                                (System/getProperty "user.home"))
                          :extension-filters [{:description "Laser Show Projects"
                                              :extensions ["*.zip"]}]
                          :on-result {:event/type :file/open-result}}})

(defn- handle-file-open-result
  "Handle result from open file dialog."
  [{:keys [state file-path]}]
  (if file-path
    (do
      (log/info "Opening project:" file-path)
      (persistent/load-project! file-path)
      {:state state})
    {:state state}))

(defn- handle-file-save-as
  "Save the project to a new location using file chooser dialog."
  [{:keys [state]}]
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
                            :on-result {:event/type :file/save-as-result}}}))

(defn- handle-file-save
  "Save the current project."
  [{:keys [state]}]
  (let [current-file (get-in state [:project :current-file])]
    (if current-file
      (do
        (log/info "Saving project to:" current-file)
        (persistent/save-project! current-file)
        {:state state})
      ;; No current file, trigger Save As
      (handle-file-save-as {:state state}))))

(defn- handle-file-save-as-result
  "Handle result from save-as file dialog."
  [{:keys [state file-path]}]
  (if file-path
    (do
      (log/info "Saving project as:" file-path)
      (persistent/save-project! file-path)
      {:state state})
    {:state state}))

(defn- handle-file-export
  "Export project data (TODO: Implement export dialog)."
  [{:keys [state]}]
  (log/debug "File > Export")
  ;; TODO: Show export options dialog
  {:state state})

(defn- handle-file-exit
  "Exit the application."
  [{:keys [state]}]
  (log/debug "File > Exit")
  ;; TODO: Show confirmation dialog if project is dirty
  ;; For now, just exit
  {:system/exit true})


;; Edit Menu Events
;; Note: Copy/Paste/Clear need to be delegated to grid-handlers or effects-handlers
;; depending on active tab. These will be imported when handlers.clj is refactored.


(defn- handle-edit-undo
  "Undo the last action (TODO: Implement undo stack)."
  [{:keys [state]}]
  (log/debug "Edit > Undo")
  ;; TODO: Implement undo/redo system
  {:state state})

(defn- handle-edit-redo
  "Redo the last undone action (TODO: Implement redo stack)."
  [{:keys [state]}]
  (log/debug "Edit > Redo")
  ;; TODO: Implement undo/redo system
  {:state state})

(defn- handle-edit-copy
  "Copy selected cell to clipboard.
   NOTE: This handler delegates to domain-specific handlers based on active tab.
   It will need to require grid-handlers and effects-handlers modules."
  [{:keys [state]}]
  (let [active-tab (get-in state [:ui :active-tab])
        selected-cell (case active-tab
                        :grid (get-in state [:grid :selected-cell])
                        :effects (get-in state [:effects :selected-cell])
                        nil)]
    (if selected-cell
      (let [[col row] selected-cell]
        ;; TODO: Import and call appropriate handler when modules are refactored
        ;; For now, return empty effects to avoid breaking compilation
        (log/debug "Edit > Copy: delegating to" active-tab "handler")
        {:state state})
      (do
        (log/debug "Edit > Copy: No cell selected")
        {:state state}))))

(defn- handle-edit-paste
  "Paste clipboard to selected cell.
   NOTE: This handler delegates to domain-specific handlers based on active tab.
   It will need to require grid-handlers and effects-handlers modules."
  [{:keys [state]}]
  (let [active-tab (get-in state [:ui :active-tab])
        selected-cell (case active-tab
                        :grid (get-in state [:grid :selected-cell])
                        :effects (get-in state [:effects :selected-cell])
                        nil)]
    (if selected-cell
      (let [[col row] selected-cell]
        ;; TODO: Import and call appropriate handler when modules are refactored
        (log/debug "Edit > Paste: delegating to" active-tab "handler")
        {:state state})
      (do
        (log/debug "Edit > Paste: No cell selected")
        {:state state}))))

(defn- handle-edit-clear-cell
  "Clear the selected cell.
   NOTE: This handler delegates to domain-specific handlers based on active tab.
   It will need to require grid-handlers and effects-handlers modules."
  [{:keys [state]}]
  (let [active-tab (get-in state [:ui :active-tab])
        selected-cell (case active-tab
                        :grid (get-in state [:grid :selected-cell])
                        :effects (get-in state [:effects :selected-cell])
                        nil)]
    (if selected-cell
      (let [[col row] selected-cell]
        ;; TODO: Import and call appropriate handler when modules are refactored
        (log/debug "Edit > Clear Cell: delegating to" active-tab "handler")
        {:state state})
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


(defn- handle-help-documentation
  "Open documentation in browser."
  [{:keys [state]}]
  (log/debug "Help > Documentation")
  ;; TODO: Open URL in system browser
  {:state state
   :system/open-url "https://github.com/your-repo/docs"})

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

(defn- handle-help-reload-styles
  "Reload all CSS stylesheets to pick up style changes."
  [{:keys [state]}]
  (log/info "Help > Reload Styles")
  (require 'laser-show.css.reload)
  (let [result ((resolve 'laser-show.css.reload/reload-all-styles!))]
    (if (:success? result)
      (log/info "CSS styles reloaded successfully")
      (log/error "Failed to reload CSS styles:" (:error result))))
  {:state state})

(defn- handle-help-reload-app
  "Reload all app code to pick up code changes without restarting JVM."
  [{:keys [state]}]
  (log/info "Help > Reload App Code")
  (require 'laser-show.dev.reload)
  (let [result ((resolve 'laser-show.dev.reload/reload-app-code!))]
    (if (:success? result)
      (log/info "App code reloaded successfully")
      (log/error "Failed to reload app code:" (:error result))))
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
    :file/save (handle-file-save event)
    :file/save-as (handle-file-save-as event)
    :file/save-as-result (handle-file-save-as-result event)
    :file/export (handle-file-export event)
    :file/exit (handle-file-exit event)
    
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
    :help/reload-styles (handle-help-reload-styles event)
    :help/reload-app (handle-help-reload-app event)
    
    ;; Unknown event in this domain
    {}))
