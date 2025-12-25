(ns laser-show.ui-fx.components.menu-bar
  "Menu bar component with dark theme support.
   Creates menus with white text labels for use in dark title bars."
  (:require [laser-show.ui-fx.styles :as styles])
  (:import [javafx.scene.control Menu MenuItem MenuBar SeparatorMenuItem Label]
           [javafx.scene.input KeyCombination]
           [javafx.scene.layout HBox]
           [javafx.scene.image ImageView Image]
           [javafx.geometry Pos Insets]))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn- create-menu-item
  "Create a MenuItem with optional accelerator.
   
   Args:
   - text: Menu item label text
   - accelerator: Optional keyboard shortcut string (e.g. 'Shortcut+N')"
  ([text]
   (MenuItem. text))
  ([text accelerator]
   (doto (MenuItem. text)
     (.setAccelerator (KeyCombination/keyCombination accelerator)))))

(defn- create-menu
  "Create a Menu with white text style for dark theme.
   Uses a Label as graphic to ensure white text displays correctly.
   
   Args:
   - text: Menu label text (e.g. 'File')
   - items: Collection of MenuItem instances"
  [text items]
  (let [menu (Menu. text)
        label (Label. text)]
    (.setStyle label "-fx-text-fill: #e0e0e0;")
    (.setGraphic menu label)
    (.setText menu "")  ;; Clear text since we're using graphic
    (-> menu .getItems (.addAll items))
    menu))

;; ============================================================================
;; Menu Definitions
;; ============================================================================

(defn- create-file-menu
  "Create the File menu with standard items."
  []
  (create-menu "File"
    [(create-menu-item "New Project" "Shortcut+N")
     (create-menu-item "Open..." "Shortcut+O")
     (SeparatorMenuItem.)
     (create-menu-item "Save" "Shortcut+S")
     (create-menu-item "Save As..." "Shortcut+Shift+S")
     (SeparatorMenuItem.)
     (create-menu-item "Exit")]))

(defn- create-edit-menu
  "Create the Edit menu with standard items."
  []
  (create-menu "Edit"
    [(create-menu-item "Copy" "Shortcut+C")
     (create-menu-item "Paste" "Shortcut+V")
     (SeparatorMenuItem.)
     (create-menu-item "Clear Cell" "Delete")]))

(defn- create-transport-menu
  "Create the Transport menu with playback controls."
  []
  (create-menu "Transport"
    [(create-menu-item "Play/Pause" "Space")
     (create-menu-item "Stop" "Escape")
     (create-menu-item "Retrigger" "R")]))

(defn- create-help-menu
  "Create the Help menu."
  []
  (create-menu "Help"
    [(create-menu-item "About")]))

;; ============================================================================
;; Public API
;; ============================================================================

(defn create-menu-bar
  "Create a complete MenuBar with all application menus.
   Styled for dark theme with transparent background.
   
   Returns: javafx.scene.control.MenuBar instance"
  []
  (let [menu-bar (doto (MenuBar.)
                   (.setStyle "-fx-background-color: transparent;")
                   (.setUseSystemMenuBar false))
        file-menu (create-file-menu)
        edit-menu (create-edit-menu)
        transport-menu (create-transport-menu)
        help-menu (create-help-menu)]
    (-> menu-bar .getMenus (.addAll [file-menu edit-menu transport-menu help-menu]))
    menu-bar))

(defn create-app-icon
  "Create the application icon ImageView.
   
   Returns: javafx.scene.image.ImageView instance"
  []
  (doto (ImageView.)
    (.setImage (Image. "file:resources/laser-warning-square.png"))
    (.setFitWidth 18)
    (.setFitHeight 18)
    (.setPreserveRatio true)))

(defn create-header-leading-box
  "Create the leading content box for HeaderBar.
   Contains the app icon and menu bar.
   
   Returns: javafx.scene.layout.HBox instance"
  []
  (let [icon (create-app-icon)
        menu-bar (create-menu-bar)
        leading-box (doto (HBox.)
                      (.setAlignment Pos/CENTER_LEFT)
                      (.setSpacing 4))]
    (HBox/setMargin icon (Insets. 0 4 0 8))
    (-> leading-box .getChildren (.addAll [icon menu-bar]))
    leading-box))
