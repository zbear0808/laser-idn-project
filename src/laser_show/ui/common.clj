(ns laser-show.ui.common
  "Common UI utilities reused across components.
   
   Provides helpers for:
   - Mouse event handling (double-click, right-click, etc.)
   - Component styling
   - Event listener creation"
  (:import [java.awt.event MouseEvent MouseListener]))

;; ============================================================================
;; Mouse Event Utilities
;; ============================================================================

(defn create-mouse-listener
  "Create a MouseListener with optional handlers for different events.
   All handlers are optional - only provided ones will be called.
   
   Options:
   - :on-click - (fn [event]) called on any click
   - :on-double-click - (fn [event]) called on double-click (click count == 2)
   - :on-right-click - (fn [event]) called on right-click (button 3)
   - :on-press - (fn [event]) called on mouse press
   - :on-release - (fn [event]) called on mouse release
   - :on-enter - (fn [event]) called on mouse enter
   - :on-exit - (fn [event]) called on mouse exit"
  [{:keys [on-click on-double-click on-right-click on-press on-release on-enter on-exit]}]
  (reify MouseListener
    (mouseClicked [_ e]
      (cond
        ;; Double-click takes priority
        (and on-double-click (= (.getClickCount e) 2))
        (on-double-click e)
        
        ;; Right-click
        (and on-right-click (= (.getButton e) MouseEvent/BUTTON3))
        (on-right-click e)
        
        ;; Regular click
        on-click
        (on-click e)))
    
    (mousePressed [_ e]
      (when on-press (on-press e)))
    
    (mouseReleased [_ e]
      (when on-release (on-release e)))
    
    (mouseEntered [_ e]
      (when on-enter (on-enter e)))
    
    (mouseExited [_ e]
      (when on-exit (on-exit e)))))

(defn add-double-click-listener!
  "Add a double-click handler to a component.
   Simple helper for the common case of just needing double-click.
   
   Parameters:
   - component: Any AWT/Swing component
   - on-double-click: (fn [event]) called when double-clicked
   
   Returns: The created MouseListener (for potential removal)"
  [component on-double-click]
  (let [listener (create-mouse-listener {:on-double-click on-double-click})]
    (.addMouseListener component listener)
    listener))

(defn add-click-listener!
  "Add a click handler to a component.
   
   Parameters:
   - component: Any AWT/Swing component
   - on-click: (fn [event]) called when clicked
   
   Returns: The created MouseListener"
  [component on-click]
  (let [listener (create-mouse-listener {:on-click on-click})]
    (.addMouseListener component listener)
    listener))

(defn add-mouse-listeners!
  "Add multiple mouse handlers to a component.
   
   Parameters:
   - component: Any AWT/Swing component
   - handlers: Map with any of:
     - :on-click
     - :on-double-click
     - :on-right-click
     - :on-press
     - :on-release
     - :on-enter
     - :on-exit
   
   Returns: The created MouseListener"
  [component handlers]
  (let [listener (create-mouse-listener handlers)]
    (.addMouseListener component listener)
    listener))

;; ============================================================================
;; Event Type Checks
;; ============================================================================

(defn double-click?
  "Check if a mouse event is a double-click."
  [^MouseEvent e]
  (= (.getClickCount e) 2))

(defn right-click?
  "Check if a mouse event is a right-click."
  [^MouseEvent e]
  (= (.getButton e) MouseEvent/BUTTON3))

(defn left-click?
  "Check if a mouse event is a left-click."
  [^MouseEvent e]
  (= (.getButton e) MouseEvent/BUTTON1))
