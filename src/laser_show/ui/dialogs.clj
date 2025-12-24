(ns laser-show.ui.dialogs
  "File and folder selection dialogs for project management."
  (:import [javax.swing JOptionPane]
           [com.formdev.flatlaf.util SystemFileChooser]
           [java.io File]))

;; ============================================================================
;; Folder Selection Dialogs
;; ============================================================================

(defn choose-project-folder
  "Show folder chooser dialog for selecting a project folder.
   Uses FlatLaf SystemFileChooser which automatically uses native OS dialogs when available
   and falls back to Swing on unsupported platforms.
   
   Parameters:
   - parent-frame - Parent window for dialog
   - title - Dialog title string
   - create-mode? - If true, optimized for creating new folders (Save As)
                    If false, optimized for opening existing folders (Open)
   
   Returns: Selected folder path as string, or nil if cancelled
   
   Example:
   (choose-project-folder frame \"Open Project\" false)
   => \"/path/to/project\" or nil"
  [parent-frame title create-mode?]
  (let [chooser (SystemFileChooser.)]
    ;; Configure the chooser for directory selection
    (.setFileSelectionMode chooser SystemFileChooser/DIRECTORIES_ONLY)
    (.setDialogTitle chooser title)
    
    ;; Set current directory to user's home if available
    (when-let [home (System/getProperty "user.home")]
      (.setCurrentDirectory chooser (File. home)))
    
    ;; Show the appropriate dialog
    (let [result (if create-mode?
                   (.showSaveDialog chooser parent-frame)
                   (.showOpenDialog chooser parent-frame))]
      ;; Check if user approved
      (when (= result SystemFileChooser/APPROVE_OPTION)
        (when-let [selected-file (.getSelectedFile chooser)]
          (.getAbsolutePath selected-file))))))

;; ============================================================================
;; Confirmation Dialogs
;; ============================================================================

(defn confirm-unsaved-changes
  "Show confirmation dialog for unsaved changes.
   
   Parameters:
   - parent-frame - Parent window for dialog
   
   Returns: One of :save, :dont-save, or :cancel
   
   Example:
   (confirm-unsaved-changes frame)
   => :save  ; User clicked Save
   => :dont-save  ; User clicked Don't Save
   => :cancel  ; User clicked Cancel or closed dialog"
  [parent-frame]
  (let [options (into-array ["Save" "Don't Save" "Cancel"])
        result (JOptionPane/showOptionDialog
                 parent-frame
                 "You have unsaved changes. Do you want to save before continuing?"
                 "Unsaved Changes"
                 JOptionPane/YES_NO_CANCEL_OPTION
                 JOptionPane/WARNING_MESSAGE
                 nil
                 options
                 (aget options 0))]
    (case result
      0 :save
      1 :dont-save
      :cancel)))

(defn confirm-overwrite
  "Show confirmation dialog for overwriting existing project files.
   
   Parameters:
   - parent-frame - Parent window for dialog
   - folder-path - Path to folder that will be overwritten
   
   Returns: true if user confirms, false otherwise
   
   Example:
   (confirm-overwrite frame \"/path/to/existing\")
   => true or false"
  [parent-frame folder-path]
  (let [result (JOptionPane/showConfirmDialog
                 parent-frame
                 (str "The folder \"" folder-path "\" already contains files.\n"
                      "Do you want to overwrite them?")
                 "Confirm Overwrite"
                 JOptionPane/YES_NO_OPTION
                 JOptionPane/WARNING_MESSAGE)]
    (= result JOptionPane/YES_OPTION)))

;; ============================================================================
;; Error Dialogs
;; ============================================================================

(defn show-error-dialog
  "Show a generic error dialog.
   
   Parameters:
   - parent-frame - Parent window for dialog
   - title - Dialog title
   - message - Error message to display
   
   Example:
   (show-error-dialog frame \"Save Error\" \"Could not write to disk.\")"
  [parent-frame title message]
  (JOptionPane/showMessageDialog
    parent-frame
    message
    title
    JOptionPane/ERROR_MESSAGE))

(defn show-save-error-dialog
  "Show error dialog for save failures.
   
   Parameters:
   - parent-frame - Parent window for dialog
   - message - Specific error message
   - folder-path - Path that failed (optional)
   
   Example:
   (show-save-error-dialog frame \"Permission denied\" \"/path/to/folder\")"
  [parent-frame message & [folder-path]]
  (let [full-message (if folder-path
                       (str "Failed to save project to:\n"
                            folder-path "\n\n"
                            "Error: " message)
                       (str "Failed to save project.\n\n"
                            "Error: " message))]
    (show-error-dialog parent-frame "Save Error" full-message)))

(defn show-load-error-dialog
  "Show error dialog for load failures.
   
   Parameters:
   - parent-frame - Parent window for dialog
   - message - Specific error message
   - folder-path - Path that failed (optional)
   
   Example:
   (show-load-error-dialog frame \"Folder not found\" \"/path/to/folder\")"
  [parent-frame message & [folder-path]]
  (let [full-message (if folder-path
                       (str "Failed to load project from:\n"
                            folder-path "\n\n"
                            "Error: " message)
                       (str "Failed to load project.\n\n"
                            "Error: " message))]
    (show-error-dialog parent-frame "Load Error" full-message)))

;; ============================================================================
;; Success/Info Dialogs
;; ============================================================================

(defn show-info-dialog
  "Show an informational dialog.
   
   Parameters:
   - parent-frame - Parent window for dialog
   - title - Dialog title
   - message - Information message to display
   
   Example:
   (show-info-dialog frame \"Success\" \"Project saved successfully.\")"
  [parent-frame title message]
  (JOptionPane/showMessageDialog
    parent-frame
    message
    title
    JOptionPane/INFORMATION_MESSAGE))

(defn show-save-success-dialog
  "Show success dialog after saving (optional - can be silent).
   
   Parameters:
   - parent-frame - Parent window for dialog
   - folder-path - Path where project was saved
   
   Example:
   (show-save-success-dialog frame \"/path/to/project\")"
  [parent-frame folder-path]
  (show-info-dialog
    parent-frame
    "Project Saved"
    (str "Project saved successfully to:\n" folder-path)))
