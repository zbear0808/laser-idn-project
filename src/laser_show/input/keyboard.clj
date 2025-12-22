(ns laser-show.input.keyboard
  "Keyboard input handler using Seesaw/Swing KeyListener.
   Converts key presses to standardized input events."
  (:require [seesaw.core :as ss]
            [seesaw.keymap :as km]
            [laser-show.input.events :as events]
            [laser-show.input.router :as router])
  (:import [java.awt.event KeyEvent KeyListener]))

;; ============================================================================
;; Keyboard State
;; ============================================================================

(defonce keyboard-state
  (atom {:enabled true
         :key-mappings {}        ; Map of key-code -> {:action trigger-id} or {:note note-num}
         :pressed-keys #{}       ; Currently pressed keys
         :listeners []           ; Active KeyListeners
         :components []}))       ; Components with listeners attached

;; ============================================================================
;; Default Key Mappings
;; ============================================================================

(def default-grid-mapping
  "Default mapping for grid cells using number/letter keys.
   Row 0: 1-8, Row 1: Q-I, Row 2: A-K, Row 3: Z-,"
  {;; Row 0 (top): 1-8
   KeyEvent/VK_1 {:note 0}
   KeyEvent/VK_2 {:note 1}
   KeyEvent/VK_3 {:note 2}
   KeyEvent/VK_4 {:note 3}
   KeyEvent/VK_5 {:note 4}
   KeyEvent/VK_6 {:note 5}
   KeyEvent/VK_7 {:note 6}
   KeyEvent/VK_8 {:note 7}
   ;; Row 1: Q-I
   KeyEvent/VK_Q {:note 8}
   KeyEvent/VK_W {:note 9}
   KeyEvent/VK_E {:note 10}
   KeyEvent/VK_R {:note 11}
   KeyEvent/VK_T {:note 12}
   KeyEvent/VK_Y {:note 13}
   KeyEvent/VK_U {:note 14}
   KeyEvent/VK_I {:note 15}
   ;; Row 2: A-K
   KeyEvent/VK_A {:note 16}
   KeyEvent/VK_S {:note 17}
   KeyEvent/VK_D {:note 18}
   KeyEvent/VK_F {:note 19}
   KeyEvent/VK_G {:note 20}
   KeyEvent/VK_H {:note 21}
   KeyEvent/VK_J {:note 22}
   KeyEvent/VK_K {:note 23}
   ;; Row 3: Z-,
   KeyEvent/VK_Z {:note 24}
   KeyEvent/VK_X {:note 25}
   KeyEvent/VK_C {:note 26}
   KeyEvent/VK_V {:note 27}
   KeyEvent/VK_B {:note 28}
   KeyEvent/VK_N {:note 29}
   KeyEvent/VK_M {:note 30}
   KeyEvent/VK_COMMA {:note 31}})

(def default-control-mapping
  "Default mapping for control keys."
  {KeyEvent/VK_SPACE {:action :play-pause}
   KeyEvent/VK_ESCAPE {:action :stop}
   KeyEvent/VK_ENTER {:action :trigger}
   KeyEvent/VK_TAB {:action :next}
   KeyEvent/VK_BACK_SPACE {:action :clear}
   ;; Function keys for presets
   KeyEvent/VK_F1 {:action :preset-1}
   KeyEvent/VK_F2 {:action :preset-2}
   KeyEvent/VK_F3 {:action :preset-3}
   KeyEvent/VK_F4 {:action :preset-4}
   KeyEvent/VK_F5 {:action :preset-5}
   KeyEvent/VK_F6 {:action :preset-6}
   KeyEvent/VK_F7 {:action :preset-7}
   KeyEvent/VK_F8 {:action :preset-8}
   ;; Arrow keys for navigation
   KeyEvent/VK_UP {:action :up}
   KeyEvent/VK_DOWN {:action :down}
   KeyEvent/VK_LEFT {:action :left}
   KeyEvent/VK_RIGHT {:action :right}})

;; ============================================================================
;; Key Event Conversion
;; ============================================================================

(defn key-code->event
  "Converts a Java KeyEvent to a unified input event based on mappings."
  [^KeyEvent key-event event-type]
  (let [key-code (.getKeyCode key-event)
        mappings (:key-mappings @keyboard-state)
        mapping (get mappings key-code)]
    (when mapping
      (cond
        ;; Note mapping - treat like MIDI notes
        (:note mapping)
        (if (= event-type :pressed)
          (events/note-on :keyboard 0 (:note mapping) 1.0)
          (events/note-off :keyboard 0 (:note mapping)))
        
        ;; Action/trigger mapping
        (:action mapping)
        (events/trigger :keyboard (:action mapping) 
                       (if (= event-type :pressed) :pressed :released))))))

(defn key-name
  "Returns a readable name for a key code."
  [key-code]
  (KeyEvent/getKeyText key-code))

;; ============================================================================
;; KeyListener Implementation
;; ============================================================================

(defn create-key-listener
  "Creates a KeyListener that dispatches to the event router."
  []
  (reify KeyListener
    (keyPressed [_ e]
      (when (:enabled @keyboard-state)
        (let [key-code (.getKeyCode e)]
          ;; Track pressed keys to avoid repeat events
          (when-not (contains? (:pressed-keys @keyboard-state) key-code)
            (swap! keyboard-state update :pressed-keys conj key-code)
            (when-let [event (key-code->event e :pressed)]
              (router/dispatch! event))))))
    
    (keyReleased [_ e]
      (when (:enabled @keyboard-state)
        (let [key-code (.getKeyCode e)]
          (swap! keyboard-state update :pressed-keys disj key-code)
          (when-let [event (key-code->event e :released)]
            (router/dispatch! event)))))
    
    (keyTyped [_ _e]
      ;; Not used - we handle keyPressed and keyReleased instead
      )))

;; ============================================================================
;; Setup and Teardown
;; ============================================================================

(defn attach-to-component!
  "Attaches keyboard listener to a Swing component (usually the main frame).
   The component must be focusable to receive key events."
  [component]
  (let [listener (create-key-listener)]
    ;; Ensure component is focusable
    (.setFocusable component true)
    (.addKeyListener component listener)
    (swap! keyboard-state 
           (fn [state]
             (-> state
                 (update :listeners conj listener)
                 (update :components conj component))))
    listener))

(defn detach-from-component!
  "Removes keyboard listener from a component."
  [component listener]
  (.removeKeyListener component listener)
  (swap! keyboard-state
         (fn [state]
           (-> state
               (update :listeners #(remove #{listener} %))
               (update :components #(remove #{component} %))))))

(defn detach-all!
  "Removes all keyboard listeners from all components."
  []
  (let [{:keys [listeners components]} @keyboard-state]
    (doseq [[component listener] (map vector components listeners)]
      (try
        (.removeKeyListener component listener)
        (catch Exception _))))
  (swap! keyboard-state assoc :listeners [] :components []))

;; ============================================================================
;; Configuration
;; ============================================================================

(defn set-key-mapping!
  "Sets the mapping for a specific key code.
   - key-code: Java KeyEvent key code (e.g., KeyEvent/VK_A)
   - mapping: {:note n} or {:action :action-keyword}"
  [key-code mapping]
  (swap! keyboard-state assoc-in [:key-mappings key-code] mapping))

(defn remove-key-mapping!
  "Removes the mapping for a specific key code."
  [key-code]
  (swap! keyboard-state update :key-mappings dissoc key-code))

(defn set-mappings!
  "Sets all key mappings at once."
  [mappings]
  (swap! keyboard-state assoc :key-mappings mappings))

(defn load-default-mappings!
  "Loads the default grid and control key mappings."
  []
  (swap! keyboard-state assoc :key-mappings 
         (merge default-grid-mapping default-control-mapping)))

(defn get-mappings
  "Returns current key mappings."
  []
  (:key-mappings @keyboard-state))

;; ============================================================================
;; Enable/Disable
;; ============================================================================

(defn enable!
  "Enables keyboard input processing."
  []
  (swap! keyboard-state assoc :enabled true))

(defn disable!
  "Disables keyboard input processing."
  []
  (swap! keyboard-state assoc :enabled false))

(defn enabled?
  "Returns true if keyboard input is enabled."
  []
  (:enabled @keyboard-state))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn pressed-keys
  "Returns set of currently pressed key codes."
  []
  (:pressed-keys @keyboard-state))

(defn note->grid-position
  "Converts a note number to grid [col row] position.
   Assumes 8-column grid layout."
  [note cols]
  [(mod note cols) (quot note cols)])

(defn grid-position->note
  "Converts grid [col row] position to note number.
   Assumes 8-column grid layout."
  [[col row] cols]
  (+ col (* row cols)))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initializes keyboard input system with default mappings."
  []
  (load-default-mappings!)
  (enable!))
