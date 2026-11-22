(ns laser-show.views.keys
  "Who a key press belongs to (keyboard plan 03, D5 A). JavaFX already gives
   the focused component the first look at a key; a component that handles
   keys for a whole region (a list) asks whether the focus is inside it, and
   leaves the key alone while someone is typing."
  (:import [javafx.scene Node]
           [javafx.scene.control ComboBoxBase Spinner TextInputControl]
           [javafx.scene.input KeyEvent]))

(defn typing-target?
  "Is the key press going to something you type in: a text field or text
   area, an editable combo or spinner, or one's editor?"
  [^KeyEvent e]
  (let [t (.getTarget e)]
    (boolean
     (or (instance? TextInputControl t)
         (and (instance? ComboBoxBase t) (.isEditable ^ComboBoxBase t))
         (and (instance? Spinner t) (.isEditable ^Spinner t))))))

(defn focus-within?
  "Is the focus on node or anywhere inside it?"
  [^Node node]
  (boolean (some-> node .isFocusWithin)))

(defn list-key-target?
  "Is the key press e one for the list at node: the focus inside it, and
   nobody typing?"
  [node e]
  (and (focus-within? node) (not (typing-target? e))))
