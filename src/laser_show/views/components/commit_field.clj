(ns laser-show.views.components.commit-field
  "A text field that applies its text on Enter or when it loses focus, not on
   every keystroke."
  (:require [cljfx.api :as fx]
            [laser-show.events.core :as events]))

(defn commit-field
  "A text field showing text, that applies what was typed on Enter or when it
   loses focus. (on-commit typed) returns the event to dispatch, or nil to
   reject the text, which puts text back. Text left as it was commits
   nothing.
   
   Props: :id, :text, :on-commit, :pref-width, :style-class (default
   [\"text-field-dark\"])"
  [{:keys [id text on-commit pref-width style-class]
    :or {style-class ["text-field-dark"]}}]
  (let [commit! (fn [^javafx.scene.control.TextField field]
                  (let [typed (.getText field)]
                    (when (not= typed text)
                      (if-let [event (on-commit typed)]
                        (events/dispatch! event)
                        (.setText field text)))))]
    {:fx/type fx/ext-on-instance-lifecycle
     ;; The listener reads the commit fn from the field, so it is always this
     ;; render's
     :on-created (fn [^javafx.scene.control.TextField field]
                   (.addListener (.focusedProperty field)
                                 (reify javafx.beans.value.ChangeListener
                                   (changed [_ _ _ focused?]
                                     (when-not focused?
                                       (when-let [f (.getUserData field)]
                                         (f field)))))))
     :desc {:fx/type :text-field
            :id id
            :text text
            :pref-width pref-width
            :style-class style-class
            :user-data commit!
            :on-action (fn [^javafx.event.ActionEvent e]
                         (commit! (.getSource e)))}}))
