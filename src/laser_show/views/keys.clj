(ns laser-show.views.keys
  "Who a key press belongs to. The scene's trigger filter checks the focused
   editor's claimed keys. A component that handles keys for a whole region
   (a list) leaves them alone while someone is typing."
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

(defn claim-keys!
  "Declare the bare keys node edits with (lower-case KeyCode enum names).
   Adds to any claims already on the node. Call on the FX thread."
  [^Node node key-ids]
  (when (seq key-ids)
    (let [props (.getProperties node)]
      (.put props ::claimed-keys (into (get props ::claimed-keys #{}) key-ids)))))

(defn set-key-claim-predicate!
  "For a reusable editor whose keys depend on its latest props or local
   selection. pred receives a key id; nil removes this dynamic claim only.
   Call on the FX thread."
  [^Node node pred]
  (if pred
    (.put (.getProperties node) ::claims-key? pred)
    (when (.hasProperties node)
      (.remove (.getProperties node) ::claims-key?))))

(defn- node-claims? [^Node node key-id]
  (when (.hasProperties node)
    (let [props (.getProperties node)]
      (or (contains? (get props ::claimed-keys) key-id)
          (when-let [pred (get props ::claims-key?)] (pred key-id))))))

(defn claims?
  "Does the focus owner, or any of its ancestors, edit with key-id?
   Call on the FX thread. A scene with no focus owner has no node claims."
  [^Node focus-owner key-id]
  (loop [node focus-owner]
    (cond
      (nil? node) false
      (node-claims? node key-id) true
      :else (recur (.getParent node)))))
