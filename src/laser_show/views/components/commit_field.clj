(ns laser-show.views.components.commit-field
  "A text field that applies its text on Enter or when it loses focus, not on
   every keystroke."
  (:require [cljfx.api :as fx]
            [laser-show.events.core :as events])
  (:import [javafx.animation PauseTransition]
           [javafx.event EventHandler]
           [javafx.scene.control TextField]
           [javafx.scene.input KeyCode KeyCombination KeyCombination$Modifier KeyCodeCombination KeyEvent]
           [javafx.util Duration]))

(def ^:private undo-keys
  (KeyCodeCombination. KeyCode/Z (into-array KeyCombination$Modifier [KeyCombination/SHORTCUT_DOWN])))

(def ^:private redo-keys
  (KeyCodeCombination. KeyCode/Z (into-array KeyCombination$Modifier [KeyCombination/SHORTCUT_DOWN KeyCombination/SHIFT_DOWN])))

(defn- undo-filter
  "⌘Z / ⇧⌘Z (Ctrl elsewhere) in a field showing what it applied: the app's
   Undo and Redo (the Edit menu's), so an edit applied with Enter is undone
   from where it was typed. While something is typed and not applied, the
   field's own undo."
  ^EventHandler []
  (reify EventHandler
    (handle [_ e]
      (let [^KeyEvent e e
            ^TextField field (.getSource e)
            {:keys [text]} (.getUserData field)
            event (cond
                    (.match ^KeyCodeCombination redo-keys e) :edit/redo
                    (.match ^KeyCodeCombination undo-keys e) :edit/undo)]
        (when (and event (= text (.getText field)))
          (.consume e)
          (events/dispatch! {:event/type event}))))))

(def ^:private settle-ms
  "How long after a commit a field waits for the applied value to come back
   before it shows what applies again (a refused edit: the show lock)."
  300)

(defn- show-applied-later!
  "After a dispatched commit of typed: once settle-ms has passed, if the field
   still shows typed but what applies (its :text, which a render updates)
   isn't typed, show what applies. An edit that applies renders its own
   text; one the app refused (the show lock) changes nothing, so the field
   would go on showing a value that isn't applied, and send it again when it
   loses focus."
  [^TextField field typed]
  (doto (PauseTransition. (Duration/millis settle-ms))
    (.setOnFinished (reify EventHandler
                      (handle [_ _]
                        (let [{:keys [text]} (.getUserData field)]
                          (when (and (= typed (.getText field)) (not= typed text))
                            (.setText field text))))))
    (.play)))

(defn commit-field
  "A text field showing text, that applies what was typed on Enter or when it
   loses focus. (on-commit typed) returns the event to dispatch, or nil to
   reject the text, which puts text back. Text left as it was commits
   nothing; an event that applies nothing (refused by the show lock) puts it
   back too (show-applied-later!). ⌘Z / ⇧⌘Z while it shows text are the
   app's Undo and Redo (undo-filter).
   
   Props: :id, :text, :on-commit, :pref-width, :disable, :style-class
   (default [\"text-field-dark\"]), :style"
  [{:keys [id text on-commit pref-width disable style-class style]
    :or {style-class ["text-field-dark"]}}]
  (let [commit! (fn [^TextField field]
                  (let [typed (.getText field)]
                    (when (not= typed text)
                      (if-let [event (on-commit typed)]
                        (do (events/dispatch! event)
                            (show-applied-later! field typed))
                        (.setText field text)))))]
    {:fx/type fx/ext-on-instance-lifecycle
     ;; The listeners read the commit fn and the text from the field, so they
     ;; are always this render's
     :on-created (fn [^TextField field]
                   (.addListener (.focusedProperty field)
                                 (reify javafx.beans.value.ChangeListener
                                   (changed [_ _ _ focused?]
                                     (when-not focused?
                                       (when-let [f (:commit! (.getUserData field))]
                                         (f field))))))
                   (.addEventFilter field KeyEvent/KEY_PRESSED (undo-filter)))
     :desc (cond-> {:fx/type :text-field
                    :id id
                    :text text
                    :pref-width pref-width
                    :disable (boolean disable)
                    :style-class style-class
                    :user-data {:commit! commit! :text text}
                    :on-action (fn [^javafx.event.ActionEvent e]
                                 (commit! (.getSource e)))}
             style (assoc :style style))}))
