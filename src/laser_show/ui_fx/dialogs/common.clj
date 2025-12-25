(ns laser-show.ui-fx.dialogs.common
  "Common dialog utilities for JavaFX dialogs."
  (:require [cljfx.api :as fx]
            [laser-show.ui-fx.styles :as styles])
  (:import [javafx.scene.control Alert Alert$AlertType ButtonType ButtonBar$ButtonData]
           [javafx.stage FileChooser FileChooser$ExtensionFilter DirectoryChooser Stage Modality]
           [javafx.scene Scene]
           [java.io File]))

;; ============================================================================
;; Alert Dialogs
;; ============================================================================

(defn show-info
  "Show an information dialog."
  [title message]
  (fx/on-fx-thread
   (let [alert (Alert. Alert$AlertType/INFORMATION)]
     (.setTitle alert title)
     (.setHeaderText alert nil)
     (.setContentText alert message)
     (.showAndWait alert))))

(defn show-warning
  "Show a warning dialog."
  [title message]
  (fx/on-fx-thread
   (let [alert (Alert. Alert$AlertType/WARNING)]
     (.setTitle alert title)
     (.setHeaderText alert nil)
     (.setContentText alert message)
     (.showAndWait alert))))

(defn show-error
  "Show an error dialog."
  [title message]
  (fx/on-fx-thread
   (let [alert (Alert. Alert$AlertType/ERROR)]
     (.setTitle alert title)
     (.setHeaderText alert nil)
     (.setContentText alert message)
     (.showAndWait alert))))

(defn show-confirmation
  "Show a confirmation dialog with Yes/No buttons.
   Returns true if user clicked Yes."
  [title message]
  (let [result (atom false)]
    (fx/on-fx-thread
     (let [alert (Alert. Alert$AlertType/CONFIRMATION)]
       (.setTitle alert title)
       (.setHeaderText alert nil)
       (.setContentText alert message)
       (let [response (.showAndWait alert)]
         (when (.isPresent response)
           (reset! result (= (.get response) ButtonType/OK))))))
    @result))

(defn show-save-discard-cancel
  "Show a dialog with Save/Discard/Cancel options.
   Returns :save, :discard, or :cancel."
  [title message]
  (let [result (atom :cancel)]
    (fx/on-fx-thread
     (let [alert (Alert. Alert$AlertType/CONFIRMATION)
           save-btn (ButtonType. "Save" ButtonBar$ButtonData/YES)
           discard-btn (ButtonType. "Don't Save" ButtonBar$ButtonData/NO)
           cancel-btn (ButtonType. "Cancel" ButtonBar$ButtonData/CANCEL_CLOSE)]
       (.setTitle alert title)
       (.setHeaderText alert nil)
       (.setContentText alert message)
       (.setAll (.getButtonTypes alert) save-btn discard-btn cancel-btn)
       (let [response (.showAndWait alert)]
         (when (.isPresent response)
           (reset! result
                   (condp = (.get response)
                     save-btn :save
                     discard-btn :discard
                     :cancel))))))
    @result))

;; ============================================================================
;; File/Folder Choosers
;; ============================================================================

(defn choose-directory
  "Show a directory chooser dialog.
   Returns selected directory path or nil."
  [title & {:keys [initial-dir]}]
  (let [result (atom nil)]
    (fx/on-fx-thread
     (let [chooser (DirectoryChooser.)]
       (.setTitle chooser title)
       (when initial-dir
         (.setInitialDirectory chooser (File. initial-dir)))
       (when-let [dir (.showDialog chooser nil)]
         (reset! result (.getAbsolutePath dir)))))
    @result))

(defn choose-file-open
  "Show a file open dialog.
   Returns selected file path or nil."
  [title & {:keys [initial-dir extensions]}]
  (let [result (atom nil)]
    (fx/on-fx-thread
     (let [chooser (FileChooser.)]
       (.setTitle chooser title)
       (when initial-dir
         (.setInitialDirectory chooser (File. initial-dir)))
       (when extensions
         (doseq [[desc ext] extensions]
           (.add (.getExtensionFilters chooser)
                 (FileChooser$ExtensionFilter. desc (into-array String [ext])))))
       (when-let [file (.showOpenDialog chooser nil)]
         (reset! result (.getAbsolutePath file)))))
    @result))

(defn choose-file-save
  "Show a file save dialog.
   Returns selected file path or nil."
  [title & {:keys [initial-dir initial-name extensions]}]
  (let [result (atom nil)]
    (fx/on-fx-thread
     (let [chooser (FileChooser.)]
       (.setTitle chooser title)
       (when initial-dir
         (.setInitialDirectory chooser (File. initial-dir)))
       (when initial-name
         (.setInitialFileName chooser initial-name))
       (when extensions
         (doseq [[desc ext] extensions]
           (.add (.getExtensionFilters chooser)
                 (FileChooser$ExtensionFilter. desc (into-array String [ext])))))
       (when-let [file (.showSaveDialog chooser nil)]
         (reset! result (.getAbsolutePath file)))))
    @result))

;; ============================================================================
;; Custom Dialog Helper
;; ============================================================================

(defn create-modal-dialog
  "Create a modal dialog stage with the given content.
   Returns the Stage (not shown yet).
   
   Props:
   - :title - Window title
   - :width - Window width
   - :height - Window height
   - :content - cljfx component description for the content"
  [{:keys [title width height content]
    :or {width 400 height 300}}]
  (let [stage (Stage.)
        scene (Scene. (fx/create-component content) width height)]
    (.setTitle stage title)
    (.setScene stage scene)
    (.initModality stage Modality/APPLICATION_MODAL)
    stage))

(defn show-modal-dialog!
  "Create and show a modal dialog with the given content.
   
   Props:
   - :title - Window title
   - :width - Window width
   - :height - Window height
   - :content - cljfx component description for the content"
  [props]
  (fx/on-fx-thread
   (let [stage (create-modal-dialog props)]
     (.showAndWait stage))))

;; ============================================================================
;; Dialog Content Builders
;; ============================================================================

(defn dialog-button-bar
  "Create a standard button bar for dialogs.
   
   Props:
   - :buttons - Vector of {:text \"label\" :style :primary/:default :on-action fn}"
  [{:keys [buttons]}]
  {:fx/type :h-box
   :style (str "-fx-background-color: " (:surface styles/colors) ";"
              "-fx-padding: 8 16;"
              "-fx-spacing: 8;")
   :alignment :center-right
   :children (mapv (fn [{:keys [text style on-action]}]
                     {:fx/type :button
                      :text text
                      :style-class (case style
                                     :primary ["button" "primary"]
                                     ["button"])
                      :on-action on-action})
                   buttons)})

(defn dialog-layout
  "Standard dialog layout with header, content, and button bar.
   
   Props:
   - :header - Header text (optional)
   - :content - Main content component
   - :buttons - Button definitions for button bar"
  [{:keys [header content buttons]}]
  {:fx/type :border-pane
   :style (str "-fx-background-color: " (:background styles/colors) ";")
   :top (when header
          {:fx/type :label
           :text header
           :style (str "-fx-font-size: 14px;"
                      "-fx-font-weight: bold;"
                      "-fx-text-fill: " (:text-primary styles/colors) ";"
                      "-fx-padding: 16;"
                      "-fx-background-color: " (:surface styles/colors) ";")})
   :center content
   :bottom {:fx/type dialog-button-bar
            :buttons buttons}})
