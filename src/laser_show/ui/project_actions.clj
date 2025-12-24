(ns laser-show.ui.project-actions
  "File menu action handlers for project management.
   
   Coordinates between UI dialogs, persistence layer, and state management
   to implement New, Open, Save, and Save As functionality."
  (:require [laser-show.state.atoms :as state]
            [laser-show.state.persistent :as persist]
            [laser-show.ui.dialogs :as dialogs]
            [clojure.string :as str]))

;; ============================================================================
;; Forward Declarations
;; ============================================================================

(declare handle-save-project!)
(declare handle-save-as-project!)

;; ============================================================================
;; Window Title Updates
;; ============================================================================

(defn- get-folder-name
  "Extract the folder name from a full path.
   
   Example:
   (get-folder-name \"/path/to/MyProject\")
   => \"MyProject\""
  [folder-path]
  (when folder-path
    (last (str/split folder-path #"[\\/]"))))

(defn update-window-title!
  "Update main window title to show project name and dirty status.
   
   Title format:
   - No project: \"Laser Show - IDN Controller\"
   - Clean project: \"Laser Show - IDN Controller - ProjectName\"
   - Dirty project: \"Laser Show - IDN Controller - ProjectName*\"
   
   Example:
   (update-window-title!)"
  []
  (when-let [frame (state/get-main-frame)]
    (let [base-title "Laser Show - IDN Controller"
          folder (state/get-project-folder)
          dirty? (state/project-dirty?)
          title (cond
                  (and folder dirty?)
                  (str base-title " - " (get-folder-name folder) "*")
                  
                  folder
                  (str base-title " - " (get-folder-name folder))
                  
                  :else base-title)]
      (.setTitle frame title))))

;; ============================================================================
;; New Project Handler
;; ============================================================================

(defn handle-new-project!
  "Handle File > New action.
   Prompts to save if dirty, then resets all state to create a new project.
   
   Parameters:
   - parent-frame - Parent window for dialogs
   
   Returns: true if new project was created, false if cancelled
   
   Example:
   (handle-new-project! frame)"
  [parent-frame]
  (try
    ;; Check if current project has unsaved changes
    (let [should-continue? (if (state/project-dirty?)
                             (case (dialogs/confirm-unsaved-changes parent-frame)
                               :save (handle-save-project! parent-frame)
                               :dont-save true
                               :cancel false)
                             true)]
      (when should-continue?
        ;; Reset all state
        (if (persist/new-project!)
          (do
            (update-window-title!)
            (println "New project created")
            true)
          (do
            (dialogs/show-error-dialog parent-frame "Error" "Failed to create new project")
            false))))
    (catch Exception e
      (dialogs/show-error-dialog parent-frame "Error" (.getMessage e))
      false)))

;; ============================================================================
;; Open Project Handler
;; ============================================================================

(defn handle-open-project!
  "Handle File > Open action.
   Prompts to save if dirty, shows folder chooser, loads project.
   
   Parameters:
   - parent-frame - Parent window for dialogs
   
   Returns: true if project was opened, false if cancelled or failed
   
   Example:
   (handle-open-project! frame)"
  [parent-frame]
  (try
    ;; Check if current project has unsaved changes
    (let [should-continue? (if (state/project-dirty?)
                             (case (dialogs/confirm-unsaved-changes parent-frame)
                               :save (handle-save-project! parent-frame)
                               :dont-save true
                               :cancel false)
                             true)]
      (when should-continue?
        ;; Show folder chooser
        (if-let [folder (dialogs/choose-project-folder parent-frame "Open Project" false)]
          (if (persist/load-project! folder)
            (do
              (update-window-title!)
              (println "Project loaded from:" folder)
              true)
            (do
              (dialogs/show-load-error-dialog 
                parent-frame 
                "Could not load project files. The folder may not contain a valid project."
                folder)
              false))
          false)))  ; User cancelled
    (catch Exception e
      (dialogs/show-load-error-dialog parent-frame (.getMessage e))
      false)))

;; ============================================================================
;; Save Project Handler
;; ============================================================================

(defn handle-save-project!
  "Handle File > Save action.
   Saves to current folder, or delegates to Save As if no current folder.
   
   Parameters:
   - parent-frame - Parent window for dialogs
   
   Returns: true if project was saved, false if failed or cancelled
   
   Example:
   (handle-save-project! frame)"
  [parent-frame]
  (if-let [current-folder (state/get-project-folder)]
    ;; Save to current folder
    (try
      (if (persist/save-project! current-folder)
        (do
          (update-window-title!)
          (println "Project saved to:" current-folder)
          true)
        (do
          (dialogs/show-save-error-dialog 
            parent-frame 
            "Failed to save project files"
            current-folder)
          false))
      (catch Exception e
        (dialogs/show-save-error-dialog 
          parent-frame 
          (.getMessage e)
          current-folder)
        false))
    ;; No current folder, use Save As
    (handle-save-as-project! parent-frame)))

;; ============================================================================
;; Save As Project Handler
;; ============================================================================

(defn handle-save-as-project!
  "Handle File > Save As action.
   Always prompts for new folder location, warns about overwriting.
   
   Parameters:
   - parent-frame - Parent window for dialogs
   
   Returns: true if project was saved, false if failed or cancelled
   
   Example:
   (handle-save-as-project! frame)"
  [parent-frame]
  (try
    ;; Show folder chooser in save mode
    (if-let [folder (dialogs/choose-project-folder parent-frame "Save Project As" true)]
      (let [should-save? (if (persist/folder-has-project-files? folder)
                           (dialogs/confirm-overwrite parent-frame folder)
                           true)]
        (if should-save?
          (if (persist/save-project! folder)
            (do
              (update-window-title!)
              (println "Project saved to:" folder)
              true)
            (do
              (dialogs/show-save-error-dialog 
                parent-frame 
                "Failed to save project files"
                folder)
              false))
          false))  ; User declined overwrite
      false)  ; User cancelled folder selection
    (catch Exception e
      (dialogs/show-save-error-dialog parent-frame (.getMessage e))
      false)))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn setup-dirty-tracking!
  "Add watchers to all persistent atoms to automatically mark project as dirty.
   Should be called once during application initialization.
   
   This enables automatic dirty flag management - any change to persistent
   state will set the dirty flag and update the window title.
   
   Example:
   (setup-dirty-tracking!)"
  []
  (let [persistent-atoms [state/!grid 
                          state/!config 
                          state/!projectors 
                          state/!zones 
                          state/!zone-groups 
                          state/!cues 
                          state/!cue-lists 
                          state/!effects
                          state/!effect-registry]]
    (doseq [atom-ref persistent-atoms]
      (add-watch atom-ref :dirty-tracker
        (fn [_key _ref _old _new]
          ;; Only mark dirty if we have a current project
          (when (state/has-current-project?)
            (state/mark-project-dirty!)
            (update-window-title!)))))))

(defn remove-dirty-tracking!
  "Remove dirty tracking watchers from all persistent atoms.
   Useful for cleanup or testing.
   
   Example:
   (remove-dirty-tracking!)"
  []
  (let [persistent-atoms [state/!grid 
                          state/!config 
                          state/!projectors 
                          state/!zones 
                          state/!zone-groups 
                          state/!cues 
                          state/!cue-lists 
                          state/!effects
                          state/!effect-registry]]
    (doseq [atom-ref persistent-atoms]
      (remove-watch atom-ref :dirty-tracker))))
