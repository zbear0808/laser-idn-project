(ns laser-show.views.components.inline-edit
  "Reusable inline-editable text component for dialog headers.
   
   Uses double-click to enter edit mode, similar to the existing
   rename functionality in list items.
   
   Edit state is managed externally via :editing? prop.
   
   Usage:
   {:fx/type inline-edit-text
    :value \"My Text\"
    :placeholder \"Click to name\"
    :editing? false
    :on-start-edit {:event/type :my/start-edit}
    :on-commit {:event/type :my/set-name}
    :on-cancel {:event/type :my/cancel-edit}}"
  (:require [cljfx.api :as fx]
            [clojure.tools.logging :as log]
            [laser-show.events.core :as events])
  (:import [javafx.scene.input KeyCode MouseButton MouseEvent]))


(defn- rename-text-field
  "Text field for inline renaming with auto-focus and select-all.
   Commits on Enter, cancels on Escape or focus loss."
  [{:keys [value placeholder on-commit on-cancel]}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [^javafx.scene.control.TextField node]
                 ;; Auto-focus and select all when created
                 (javafx.application.Platform/runLater
                   (fn []
                     (.requestFocus node)
                     (javafx.application.Platform/runLater #(.selectAll node))))
                 ;; Handle focus loss to cancel editing
                 (.addListener (.focusedProperty node)
                   (reify javafx.beans.value.ChangeListener
                     (changed [_ _ _ new-focused?]
                       (when (and (not new-focused?) on-cancel)
                         (events/dispatch! on-cancel))))))
   :desc {:fx/type :text-field
          :text (or value "")
          :prompt-text placeholder
          :style-class "dialog-name-field"
          :on-action (fn [^javafx.event.ActionEvent e]
                       (let [new-value (.getText ^javafx.scene.control.TextField (.getSource e))]
                         (when on-commit
                           (events/dispatch! (assoc on-commit :name new-value)))))
          :on-key-pressed (fn [^javafx.scene.input.KeyEvent e]
                            (when (= (.getCode e) KeyCode/ESCAPE)
                              (when on-cancel
                                (events/dispatch! on-cancel))))}})


(defn inline-edit-text
  "Inline editable text component for dialog headers.
   
   Uses double-click to enter edit mode (like list item rename).
   
   Props:
   - :value - Current text value (string or nil)
   - :placeholder - Text shown when value is empty/nil
   - :editing? - Whether edit mode is active
   - :on-start-edit - Event map dispatched on double-click
   - :on-commit - Event map dispatched when editing completes, receives :name key
   - :on-cancel - Event map dispatched when editing is cancelled"
  [{:keys [value placeholder editing? on-start-edit on-commit on-cancel]}]
  (if editing?
    {:fx/type rename-text-field
     :value value
     :placeholder placeholder
     :on-commit on-commit
     :on-cancel on-cancel}
    ;; Use ext-let-refs to create a label ref and setup click handling on every render
    {:fx/type fx/ext-let-refs
     :refs {::label {:fx/type fx/ext-on-instance-lifecycle
                     :on-created (fn [^javafx.scene.control.Label node]
                                   (.setOnMouseClicked node
                                     (reify javafx.event.EventHandler
                                       (handle [_ e]
                                         (let [^MouseEvent event e
                                               click-count (.getClickCount event)
                                               button (.getButton event)]
                                           (when (and (= button MouseButton/PRIMARY)
                                                      (>= click-count 2))
                                             (when on-start-edit
                                               (events/dispatch! on-start-edit))))))))
                     :desc {:fx/type :label
                            :text (if (seq value) value placeholder)
                            :style-class ["dialog-name-label"
                                          (when-not (seq value) "inline-edit-placeholder")]}}}
     :desc {:fx/type fx/ext-get-ref
            :ref ::label}}))
