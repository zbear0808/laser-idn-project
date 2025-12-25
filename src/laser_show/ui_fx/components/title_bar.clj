(ns laser-show.ui-fx.components.title-bar
  "Custom title bar component for undecorated windows.
   Provides a dark-themed title bar with window controls."
  (:require [cljfx.api :as fx]
            [laser-show.ui-fx.styles :as styles])
  (:import [javafx.stage Stage]
           [javafx.scene.input MouseEvent]
           [javafx.scene Cursor]))

;; ============================================================================
;; Window Dragging State
;; ============================================================================

(defonce ^:private !drag-state (atom {:x-offset 0 :y-offset 0}))

;; ============================================================================
;; Window Control Handlers
;; ============================================================================

(defn- get-stage [^MouseEvent event]
  (-> event .getSource .getScene .getWindow))

(defn- minimize-window! [^MouseEvent event]
  (let [^Stage stage (get-stage event)]
    (.setIconified stage true)))

(defn- maximize-window! [^MouseEvent event]
  (let [^Stage stage (get-stage event)]
    (.setMaximized stage (not (.isMaximized stage)))))

(defn- close-window! [^MouseEvent event]
  (let [^Stage stage (get-stage event)]
    (.close stage)))

;; ============================================================================
;; Window Dragging
;; ============================================================================

(defn- on-drag-start [^MouseEvent event]
  (let [^Stage stage (get-stage event)]
    (when-not (.isMaximized stage)
      (reset! !drag-state {:x-offset (- (.getX stage) (.getScreenX event))
                           :y-offset (- (.getY stage) (.getScreenY event))}))))

(defn- on-drag [^MouseEvent event]
  (let [^Stage stage (get-stage event)]
    (when-not (.isMaximized stage)
      (let [{:keys [x-offset y-offset]} @!drag-state]
        (.setX stage (+ (.getScreenX event) x-offset))
        (.setY stage (+ (.getScreenY event) y-offset))))))

;; ============================================================================
;; Title Bar Button Styles
;; ============================================================================

(def ^:private button-base-style
  (str "-fx-background-color: transparent;"
       "-fx-border-color: transparent;"
       "-fx-text-fill: " (:text-secondary styles/colors) ";"
       "-fx-font-size: 12px;"
       "-fx-padding: 0 12;"
       "-fx-min-width: 46;"
       "-fx-min-height: 30;"
       "-fx-cursor: hand;"))

(def ^:private button-hover-style
  (str "-fx-background-color: " (:surface-hover styles/colors) ";"
       "-fx-text-fill: " (:text-primary styles/colors) ";"))

(def ^:private close-button-hover-style
  (str "-fx-background-color: #e81123;"
       "-fx-text-fill: white;"))

;; ============================================================================
;; Window Control Button Component
;; ============================================================================

(defn window-control-button
  "A window control button (minimize, maximize, close).
   
   Props:
   - :text - Button text/symbol
   - :on-click - Click handler fn
   - :close? - If true, uses red hover color"
  [{:keys [text on-click close?]}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [button]
                 (let [hover-style (if close? 
                                     close-button-hover-style 
                                     button-hover-style)]
                   (.setOnMouseEntered button
                     (fn [_] (.setStyle button (str button-base-style hover-style))))
                   (.setOnMouseExited button
                     (fn [_] (.setStyle button button-base-style)))))
   :desc {:fx/type :button
          :text text
          :style button-base-style
          :on-action on-click}})

;; ============================================================================
;; Title Bar Component
;; ============================================================================

(defn title-bar
  "Custom title bar component.
   
   Props:
   - :title - Window title text
   - :icon-path - Optional path to icon image
   - :maximized? - Whether window is maximized"
  [{:keys [title icon-path maximized?]
    :or {title "Laser Show"
         maximized? false}}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [pane]
                 (.setOnMousePressed pane on-drag-start)
                 (.setOnMouseDragged pane on-drag)
                 ;; Double-click to maximize
                 (.setOnMouseClicked pane 
                   (fn [^MouseEvent event]
                     (when (= 2 (.getClickCount event))
                       (maximize-window! event)))))
   :desc {:fx/type :h-box
          :style (str "-fx-background-color: " (:surface styles/colors) ";"
                     "-fx-padding: 0;")
          :alignment :center-left
          :min-height 30
          :pref-height 30
          :children (filterv some?
                     [;; Icon (if provided)
                      (when icon-path
                        {:fx/type :h-box
                         :padding {:left 8}
                         :alignment :center
                         :children [{:fx/type :image-view
                                     :image {:url (str "file:resources/" icon-path)
                                             :requested-width 16
                                             :requested-height 16
                                             :preserve-ratio true}
                                     :fit-width 16
                                     :fit-height 16}]})
                      
                      ;; Title
                      {:fx/type :label
                       :text title
                       :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                                  "-fx-font-size: 12px;"
                                  "-fx-padding: 0 12;")
                       :h-box/margin {:left (if icon-path 4 8)}}
                      
                      ;; Spacer
                      {:fx/type :region
                       :h-box/hgrow :always}
                      
                      ;; Window controls
                      {:fx/type :h-box
                       :alignment :center-right
                       :children [{:fx/type window-control-button
                                   :text "─"
                                   :on-click minimize-window!}
                                  {:fx/type window-control-button
                                   :text (if maximized? "❐" "□")
                                   :on-click maximize-window!}
                                  {:fx/type window-control-button
                                   :text "✕"
                                   :on-click close-window!
                                   :close? true}]}])}})

;; ============================================================================
;; Title Bar with App Icon (convenience wrapper)
;; ============================================================================

(defn title-bar-with-icon
  "Title bar with an application icon. Just a convenience wrapper.
   
   Props:
   - :title - Window title
   - :icon-path - Path to icon image resource
   - :maximized? - Whether window is maximized"
  [{:keys [title icon-path maximized?]
    :or {title "Laser Show"
         icon-path "laser-warning-square.png"}}]
  {:fx/type title-bar
   :title title
   :icon-path icon-path
   :maximized? maximized?})

;; ============================================================================
;; Resize Handles (for UNDECORATED windows)
;; ============================================================================

(def ^:private resize-handle-size 5)

(defn- resize-cursor [edge]
  (case edge
    :n Cursor/N_RESIZE
    :ne Cursor/NE_RESIZE
    :e Cursor/E_RESIZE
    :se Cursor/SE_RESIZE
    :s Cursor/S_RESIZE
    :sw Cursor/SW_RESIZE
    :w Cursor/W_RESIZE
    :nw Cursor/NW_RESIZE
    Cursor/DEFAULT))

(defn resize-border
  "A border around the window for resizing.
   Should be added to a StackPane containing the main content."
  [{:keys [on-resize]}]
  ;; This would need complex implementation for proper resize handles
  ;; For simplicity, we'll make the window non-resizable when UNDECORATED
  ;; or implement a simpler edge-based resize
  {:fx/type :region
   :style "-fx-background-color: transparent;"
   :mouse-transparent true})
