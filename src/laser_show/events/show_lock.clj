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
   the tempo, streaming, learning a key, switching the zone map (a pad, its
   key, Use now), and Exit, which still asks about unsaved changes. A safety zone edit goes through while it makes no zone
   larger, so the lock never stands between you and a safer show, and is
   refused when it would let light somewhere new (zones plan Q-Z1-5 A).

   wrap-show-lock is the pipeline's innermost wrapper (events/core.clj), so
   the undo history, the unsaved flag and the output sync see a change to
   [:ui] only. An event the lock refuses that another event dispatches is
   refused too: each goes through the pipeline."
  (:require [laser-show.animation.safety :as safety]
            [laser-show.events.handlers.history :as history]
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
    :zone-groups/request-remove :zone-groups/remove
    ;; The rig's order, middle laser and split width, and what a laser is in
    ;; (zones plan Z2): they re-route the engines or change which cues reach
    ;; the audience
    :rig/move-projector :rig/nudge-projector :rig/set-middle :rig/set-split-width
    :projectors/set-enabled :projectors/set-exclude-from-all :projectors/set-tag
    ;; A show's rig onto this one (zones plan ZR): they rebuild and re-route
    ;; the engines, change safety zones and calibration, or re-route cues
    :rig/set-up-from-show :rig/import-new-lasers :rig/request-use-show-setup :rig/use-show-setup
    :rig/adopt-group :rig/repin :rig/request-forget :rig/forget-projector
    ;; A grid column's default target and the show's (zones plan Z4): they
    ;; re-route every cue that inherits them, and can change which cues
    ;; reach the audience
    :grid/set-column-target :zones/set-default-target
    ;; A zone map pad's setting and its keys, as a vibe's options and keys
    ;; (a press, a key's, a due switch and Use now are a performer's, and go
    ;; through)
    :zone-map/set-pad-bar :zone-map/clear-control})

(def safety-edits
  "The safety zone edits (zones plan Z1). The lock refuses one only when it
   would make a zone larger (widens-a-zone?)."
  #{:projectors/set-safety :projectors/add-blocked-area :projectors/remove-blocked-area
    :projectors/copy-safety})

(def ^:private larger-zone-refused "Larger safety zone refused")

(defn refused-text
  "What the Perform tab says under the switch when the lock refused an event
   of type: \"Move Cell refused\"."
  [type]
  (str (history/format-event-description type) " refused"))

(defn notice-text
  "What the status bar says when the lock refuses an event of type, or with
   what the switch says (refused)."
  ([type] (notice-text type (refused-text type)))
  ([_type refused]
   (str "Show lock is on: " refused ". Unlock it on the Perform tab")))

(defn locked?
  "Is the show lock on?"
  [state]
  (true? (get-in state [:ui :show-lock?])))

(defn- widens-a-zone?
  "Would event, answered yes, give any projector a larger safety zone? The
   handler is pure, so it runs once more here with :confirmed? true."
  [handler {:keys [state] :as event}]
  (let [after (:state (handler (assoc event :confirmed? true)))]
    (boolean (some (fn [id] (safety/widens? (get-in state [:projectors id :safety])
                                            (get-in after [:projectors id :safety])))
                   (keys (:projectors after))))))

(defn- refuse
  "state with the notice that the lock refused an event of type: under the
   switch ([:ui :show-lock-notice] {:text :event}) and on the status bar."
  [state type refused]
  (update (h/show-notice state (notice-text type refused) :warning)
          :state assoc-in [:ui :show-lock-notice] {:text refused :event type}))

(defn wrap-show-lock
  "The pipeline's innermost wrapper around handler: while the lock is on, a
   blocked event's result is the notice and nothing else, and so is a safety
   zone edit that would make a zone larger."
  [handler]
  (fn [{:keys [state event/type] :as event}]
    (cond
      (not (locked? state)) (handler event)
      (contains? blocked-events type) (refuse state type (refused-text type))
      (and (contains? safety-edits type) (widens-a-zone? handler event)) (refuse state type larger-zone-refused)
      :else (handler event))))
