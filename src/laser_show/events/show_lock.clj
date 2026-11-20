(ns laser-show.events.show-lock
  "The show lock (plans/2026-11-18-vibe-mode/07 §7): the Perform tab's
   switch ([:ui :show-lock?], for the session, never saved,
   :vibe/set-show-lock). While it is on, the events that would move or
   remove what the show is built from are refused: cells, vibes, projectors
   and zone groups, undo and redo, and the project file, whose reads and
   writes also block the event agent (probes v10, v12). A refused event
   changes nothing but the notice that says so ([:ui :show-lock-notice],
   shown under the switch, and the status bar's). What a performer plays is
   never refused: the vibe keys, BLACKOUT, the grid's clicks, the transport,
   the tempo, streaming, learning a key, and Exit, which still asks about
   unsaved changes.

   wrap-show-lock is the pipeline's innermost wrapper (events/core.clj), so
   the undo history, the unsaved flag and the output sync see a change to
   [:ui] only. An event the lock refuses that another event dispatches is
   refused too: each goes through the pipeline."
  (:require [laser-show.events.handlers.history :as history]
            [laser-show.events.helpers :as h]))

(def blocked-events
  "The events the show lock refuses, each a routed event
   (show-lock-test/blocked-events-name-real-events-test)."
  #{;; A cell that moves or goes breaks the vibes that name it
    :grid/move-cell :grid/clear-cell :grid/paste-cell
    :effects/move-cell :effects/clear-cell :effects/paste-cell
    :edit/clear-cell :edit/paste
    ;; An undo can remove a vibe mid-show
    :edit/undo :edit/redo :history/undo :history/redo
    ;; They replace the document, or block the agent with file I/O; a
    ;; chooser's result and a read's are refused with the Open they follow
    :file/new-project :file/open :file/open-result :file/project-loaded :file/save :file/save-as
    ;; The vibes are fixed for the show: every edit with an undo label
    :vibe/add :vibe/duplicate :vibe/remove :vibe/move :vibe/rename :vibe/set-color :vibe/set-energy
    :vibe/toggle-cell :vibe/toggle-fx :vibe/set-option :vibe/make-from-rows :vibe/clear-control
    :vibe/add-reaction :vibe/add-master :vibe/add-morph
    ;; They rebuild or re-route the engines
    :projectors/add-manual :projectors/update-settings :projectors/toggle-enabled
    :projectors/toggle-zone-group :projectors/vp-toggle-zone-group :projectors/set-service-enabled
    :projectors/enable-all-by-ip :projectors/request-remove :projectors/remove
    :zone-groups/request-remove :zone-groups/remove})

(defn refused-text
  "What the Perform tab says under the switch when the lock refused an event
   of type: \"Move Cell refused\"."
  [type]
  (str (history/format-event-description type) " refused"))

(defn notice-text
  "What the status bar says when the lock refuses an event of type."
  [type]
  (str "Show lock is on: " (refused-text type) ". Unlock it on the Perform tab"))

(defn locked?
  "Is the show lock on?"
  [state]
  (true? (get-in state [:ui :show-lock?])))

(defn wrap-show-lock
  "The pipeline's innermost wrapper around handler: while the lock is on, a
   blocked event's result is the notice and nothing else (state with
   [:ui :show-lock-notice] {:text :event}, and the status bar's notice)."
  [handler]
  (fn [{:keys [state event/type] :as event}]
    (if (and (locked? state) (contains? blocked-events type))
      (update (h/show-notice state (notice-text type) :warning)
              :state assoc-in [:ui :show-lock-notice] {:text (refused-text type) :event type})
      (handler event))))
