(ns laser-show.events.handlers.history
  "Event handlers for undo, redo, and transaction lifecycle management,
   plus the two pipeline middlewares:
   - wrap-history: pushes undo snapshots for undoable document edits
   - wrap-dirty: derives [:project :dirty?] from the saved baseline
   
   History meta keys (consumed here, never passed on to cljfx effects):
   - :history/skip?         (event or effects) never push an undo snapshot
   - :history/coalesce-key  (event or effects) merge bursts into one undo step
   - :history/description   (event or effects) undo label
   - :history/backfill      (effects) (fn [document] document) for content that
                            isn't a user edit; applied to all snapshots and the
                            saved baseline, so it's never undone or 'unsaved'
   Events dispatched by triggers (:source-trigger set) are always skipped.

   Gestures (reactivity plan 16): a press owns the one open transaction by
   its :history/gesture-id, a fresh UUID its control makes. Its begin, its
   members (each undoable edit of the drag), its end and its abort carry
   it; it is routing data, never a cljfx effect. Only an undoable change
   from the open press extends it. Any other undoable edit interrupts it:
   the press's step is sealed against the document before that edit, which
   then gets its own step, and the press's later events do nothing. Undo,
   Redo, Close and the deadline (stale-transaction-ms after its last
   change, a :history/gesture-alarm wake-up) seal it too."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.events.helpers :as h]
            [laser-show.events.safety-guard :as guard]
            [laser-show.state.history :as history]
            [laser-show.vibes.refs :as refs]))

(defn- settle
  "state with its open transaction, whichever press it is, sealed against
   its document as it is (history/commit-transaction)."
  [state]
  (if (get-in state [:history :transaction])
    (assoc state :history (history/commit-transaction (:history state) state))
    state))

(defn- restore
  "state with snapshot restored (history/restore-snapshot: its document, and
   the rig's copy of the definitions its membership needs): the restore of
   Undo, Redo and a press's abort. The effect cells the vibe engine
   switched on stay its own where the restore takes their on/off
   (history/effect-cell-sources, on the state before it; refs/restore-fx),
   so the next vibe and Save still switch them off. The projector UI lets go
   of a projector or virtual projector the restore took away
   (h/prune-projector-ui), and selects nothing: Redo of an Add brings the
   projector back unselected (reactivity plans 22, 23, 14)."
  [state snapshot]
  (-> state
      (history/restore-snapshot snapshot)
      (refs/restore-fx (history/effect-cell-sources state (:document-state snapshot)))
      (h/prune-projector-ui)))

(defn handle-begin-transaction
  "A press's transaction, on mouse down: :history/gesture-id is the press's
   own fresh UUID (history/begin-transaction). Without one nothing begins."
  [{:keys [label source-id ui-context state time] id :history/gesture-id}]
  (let [current-history (get state :history {})
        updated-history (history/begin-transaction current-history state
                                                   {:label (or label "Edit")
                                                    :gesture-id id
                                                    :source-id source-id
                                                    :ui-context ui-context
                                                    :now time})]
    {:state (assoc state :history updated-history)}))

(defn handle-commit-transaction
  "A press's end, on mouse up: its transaction sealed when
   :history/gesture-id is the open one's (history/finish-transaction)."
  [{:keys [state] id :history/gesture-id}]
  (let [current-history (get state :history {})
        updated-history (history/finish-transaction current-history state id)]
    {:state (assoc state :history updated-history)}))

(defn handle-abort-transaction
  "A press's abort (Escape): its transaction dropped and the document put
   back to its baseline, when :history/gesture-id is the open one's."
  [{:keys [state] id :history/gesture-id}]
  (let [current-history (get state :history {})
        {:keys [history baseline]} (history/abort-transaction current-history id)]
    (if baseline
      {:state (-> state
                  (restore baseline)
                  (assoc :history history))}
      {:state (assoc state :history history)})))

(defn handle-record-snapshot
  "Record a pre-action snapshot for discrete edits: a boundary, so an open
   press's step is sealed first."
  [{:keys [description ui-context state time]}]
  (let [state (settle state)
        snapshot (history/make-snapshot {:state state
                                         :description (or description "Action")
                                         :ui-context ui-context
                                         :timestamp time})]
    {:state (update state :history #(history/push-undo-snapshot (or % {}) snapshot))}))

(defn handle-gesture-timeout
  "The open press's wake-up (:history/gesture-alarm): its transaction sealed
   once due; before that (its last change moved the deadline), a wake-up for
   the rest of its time and no state. Any other press's: {}."
  [{:keys [state time] id :history/gesture-id}]
  (let [h (get state :history {})
        now (or time (System/currentTimeMillis))]
    (cond
      (or (nil? id) (not= id (history/gesture-id h))) {}
      (history/stale-transaction? h now) {:state (assoc state :history (history/commit-transaction h state))}
      :else {:history/gesture-alarm {:arm {:gesture-id id :delay-ms (- (history/transaction-deadline h) now)}}})))

(defn- ui-context [state]
  {:active-tab (get-in state [:ui :active-tab])
   :selected-cell (get-in state [:grid :selected-cell])})

(defn- prune-active-cues
  "Stop the cues whose cell has no cue now (the restore took it away), as
   any cue stops (cue-timing/deactivate-cue): the last one stops the
   transport, others leave it as it is."
  [state]
  (let [cue-chains (get-in state [:chains :cue-chains] {})
        active (get-in state [:playback :active-cues])
        gone (when (map? active) (remove #(contains? cue-chains %) (keys active)))]
    (if (seq gone)
      (update state :playback #(reduce cue-timing/deactivate-cue % gone))
      state)))

(defn- apply-restore
  "Restore a popped snapshot and build the effects map."
  [state {:keys [history snapshot]} verb]
  (let [restored-state (-> state
                           (restore snapshot)
                           (assoc :history history)
                           prune-active-cues)]
    (log/info verb "performed:" (:description snapshot))
    {:state restored-state}))

(defn- ask-first-if-it-grows
  "result (an Undo or Redo's restore), unless it would give a projector whose
   light is going out a larger safety zone and event isn't :confirmed?: then
   the shared confirm asks first, and nothing changes (zones plan Z1, D-28)."
  [state event result verb]
  (let [grown (guard/growing state (:state result))]
    (cond
      (or (:confirmed? event) (empty? grown)) result
      (guard/asking? state) {:state state}
      :else {:state (guard/ask state {:message (str verb " gives "
                                                    (guard/names-text (map #(guard/projector-name state %) grown))
                                                    " a larger area while "
                                                    (if (= 1 (count grown)) "it is" "they are") " on.")
                                      :confirm-label verb
                                      :event event})})))

(defn handle-undo
  "Undo the last action, the open press's step sealed first (so Undo while
   a stroke is held takes back that stroke). One that would give a live
   projector a larger safety zone asks first."
  [{:keys [state] :as event}]
  (let [state (settle state)
        current-history (get state :history {})]
    (if (history/can-undo? current-history)
      (ask-first-if-it-grows state event
                             (apply-restore state (history/pop-undo current-history state (ui-context state)) "Undo")
                             "Undo")
      (do
        (log/debug "Undo requested but stack is empty")
        {:state state}))))

(defn handle-redo
  "Redo the last undone action, the open press's step sealed first. One
   that would give a live projector a larger safety zone asks first."
  [{:keys [state] :as event}]
  (let [state (settle state)
        current-history (get state :history {})]
    (if (history/can-redo? current-history)
      (ask-first-if-it-grows state event
                             (apply-restore state (history/pop-redo current-history state (ui-context state)) "Redo")
                             "Redo")
      (do
        (log/debug "Redo requested but stack is empty")
        {:state state}))))

(defn handle-clear
  "Clear all undo/redo history; an open press's transaction is dropped with
   it."
  [{:keys [state]}]
  {:state (update state :history history/clear-history)})

(def history-excluded-events
  "Events that should not trigger automatic discrete history snapshots."
  #{:edit/undo
    :edit/redo
    :history/undo
    :history/redo
    :history/begin-transaction
    :history/commit-transaction
    :history/abort-transaction
    :history/gesture-timeout
    :history/clear
    :history/record-snapshot
    :file/new-project
    :file/open
    :file/open-result
    :file/project-loaded})

(def gesture-begin-events
  "The events that begin a press's gesture: their :history/gesture-id is the
   new press's, so they aren't held to the open one (wrap-history). A step
   grid's press begins its transaction in its own paint turn."
  #{:history/begin-transaction :step-grid/paint-start})

(def event-descriptions
  "Undo labels for events whose generated label would be unclear.
   Every key must be a real, routed event (checked by
   history-labels-name-real-events-test)."
  {:grid/clear-cell "Clear Cell"
   :grid/move-cell "Move Cell"
   :grid/paste-cell "Paste Cell"
   :grid/set-column-target "Change Column Target"
   :chain/set-items "Modify Chain"
   :modulator/toggle "Toggle Modulation"
   :cue-chain/add-preset "Add Preset"
   :cue-chain/add-effect-from-bank "Add Effect"
   :cue-chain/set-name "Rename Cue"
   :cue-chain/set-target "Change Cue Target"
   :cue-chain/set-item-target "Change Target"
   :cue-chain/set-trigger-mode "Change Trigger Mode"
   :timeline/add-track "Add Track"
   :timeline/add-folder "Add Folder"
   :timeline/update-tracks "Edit Tracks"
   :timeline/add-preset-to-track "Add Content to Track"
   :timeline/delete-selection "Delete Clip(s)"
   :timeline/nudge-selection "Nudge Clip(s)"
   :projectors/add-manual "Add Projector"
   :projectors/submit-manual-form "Add Projector"
   :projectors/remove "Remove Projector"
   :projectors/set-safety "Change Safety Zone"
   :projectors/set-safety-coordinate "Change Safety Zone"
   :projectors/reset-safety "Reset Safety Zone"
   :projectors/add-blocked-area "Add Blocked Area"
   :projectors/remove-blocked-area "Remove Blocked Area"
   :projectors/copy-safety "Copy Safety Zone"
   :projectors/set-enabled "Turn Projector On or Off"
   :projectors/enable-device "Turn Device On or Off"
   :projectors/set-exclude-from-all "Exclude From All"
   :projectors/set-tag "Change Tags"
   :projectors/set-fit "Change Fit Guard"
   :projectors/update-driver-config "Change Output Settings"
   :rig/move-projector "Move Projector"
   :rig/nudge-projector "Move Projector"
   :rig/set-middle "Change Middle Laser"
   :rig/set-split-width "Change Split Width"
   :rig/set-up-from-show "Set Up Rig From Show"
   :rig/import-new-lasers "Import Lasers"
   :rig/use-show-setup "Use Show's Setup"
   :rig/adopt-group "Use Rig's Group"
   :rig/repin "Re-pin Cues"
   :rig/add-groups-to-show "Add Rig's Groups"
   :rig/forget-projector "Forget Laser"
   :zone-groups/create-new "Add Zone Group"
   :zone-groups/save-edit "Edit Zone Group"
   :zone-groups/remove "Delete Zone Group"
   :zones/set-default-target "Change Show Target"
   :zones/set-label "Rename Zone"
   :zone-maps/create "New Zone Map"
   :zone-maps/duplicate "Duplicate Zone Map"
   :zone-maps/rename "Edit Zone Map"
   :zone-maps/set-lane "Edit Zone Map"
   :zone-maps/add-lane "Edit Zone Map"
   :zone-maps/remove-lane "Edit Zone Map"
   :zone-maps/set-override "Edit Zone Map"
   :zone-maps/delete "Delete Zone Map"
   :zone-map/set-pad-bar "Change Pad Timing"
   :zone-map/clear-control "Clear Control"
   :zone-patterns/set-cue-pattern "Change Zone Pattern"
   :zone-patterns/new-cell "New Zone Pattern"
   :pattern-pads/set-pad-bar "Change Pad Timing"
   :pattern-pads/clear-control "Clear Control"
   ;; A step grid (zones plan Z6): one click, key or drag is one step
   :step-grid/open "Edit Step Grid"
   :step-grid/toggle "Edit Step Grid"
   :step-grid/paint-start "Edit Step Grid"
   :step-grid/paint "Edit Step Grid"
   :step-grid/set-length "Edit Step Grid"
   :step-grid/add-row "Edit Step Grid"
   :step-grid/remove-row "Edit Step Grid"
   :step-grid/set-row "Edit Step Grid"
   :step-grid/move-row "Edit Step Grid"
   :step-grid/fill-row "Edit Step Grid"
   :step-grid/clear-row "Edit Step Grid"
   :step-grid/clear "Edit Step Grid"
   :step-grid/clear-step "Edit Step Grid"
   :step-grid/fill-step "Edit Step Grid"
   :step-grid/rotate "Edit Step Grid"
   :step-grid/start-from "Edit Step Grid"
   :step-grid/paste "Edit Step Grid"
   :vibe/clear-control "Clear Control"
   :vibe/add-master "Add Master Intensity"
   :vibe/add-morph "Add Morph"
   :vibe/add "Add Vibe"
   :vibe/duplicate "Duplicate Vibe"
   :vibe/remove "Delete Vibe"
   :vibe/move "Reorder Vibes"
   :vibe/rename "Rename Vibe"
   :vibe/set-color "Change Vibe"
   :vibe/set-energy "Change Vibe"
   :vibe/toggle-cell "Edit Vibe Cues"
   :vibe/toggle-fx "Edit Vibe Effects"
   :vibe/set-option "Change Vibe Option"
   :vibe/make-from-rows "Make Vibes From Rows"
   :vibe/add-reaction "Add Tap Reaction"
   :effects/new-reaction "Add Tap Reaction"})

(defn format-event-description
  "Generate a human-readable description for an event type."
  [event-type]
  (or (get event-descriptions event-type)
      (-> (name event-type)
          (str/replace #"-" " ")
          (str/capitalize))))

(defn- seal-due
  "event, its :state's open transaction sealed against the document as it
   is when the press is due at now (its deadline reached, a missed mouse-up):
   before this event, which can't extend it any more."
  [{:keys [state] :as event} now]
  (let [h (get state :history {})]
    (if (history/stale-transaction? h now)
      (do (log/info "Sealing a press's step after its deadline:" (get-in h [:transaction :label]))
          (assoc event :state (settle state)))
      event)))

(defn- apply-backfill
  "Fold a non-user change into every stored snapshot (see state.history Backfill)."
  [new-state backfill]
  (if backfill
    (update new-state :history #(history/backfill-history (or % {}) backfill))
    new-state))

(defn- push-step
  "history-state with state's snapshot pushed as type's undo step (its
   description, coalescing), taken at now."
  [history-state state type description coalesce-key now]
  (history/push-undo-snapshot (or history-state {})
                              (history/make-snapshot {:state state
                                                      :description (or description (format-event-description type))
                                                      :ui-context (ui-context state)
                                                      :timestamp now})
                              history/default-max-depth
                              {:coalesce-key coalesce-key :now now}))

(defn- publish
  "effects, with state as their :state when they have none and state isn't
   given (the due press sealed on the way in): a seal is a real change, even
   for a handler that returned {} or only a :dispatch."
  [effects given state]
  (if (and (nil? (:state effects)) (not (identical? given state)))
    (assoc effects :state state)
    effects))

(defn- with-gesture-alarm
  "effects, with the wake-up their change of press needs
   (:history/gesture-alarm, events/core.clj): a cancel for the press whose
   transaction ended, an arm, at its deadline, for the one that began."
  [effects given now]
  (let [after (:history (or (:state effects) given))
        before-id (history/gesture-id (:history given))
        after-id (history/gesture-id after)]
    (if (= before-id after-id)
      effects
      (update effects :history/gesture-alarm merge
              (cond-> {}
                before-id (assoc :cancel-id before-id)
                after-id (assoc :arm {:gesture-id after-id
                                      :delay-ms (max 0 (- (history/transaction-deadline after) now))}))))))

(defn- record
  "effects (the handler's for event, on state) with the undo history the
   change asks for. Only an undoable change counts; a press's own one
   extends its transaction, and any other ends the open press's step before
   it and is a step of its own (unless it is skipped)."
  [event state effects now]
  (let [type (:event/type event)
        id (:history/gesture-id event)
        backfill (:history/backfill effects)
        skip? (or (:history/skip? event)
                  (:history/skip? effects)
                  (some? (:source-trigger event)))
        coalesce-key (or (:history/coalesce-key event) (:history/coalesce-key effects))
        description (or (:history/description effects) (:history/description event))
        effects (dissoc effects :history/skip? :history/coalesce-key :history/description)
        new-state (:state effects)]
    (cond
      (nil? new-state)
      effects

      backfill
      (assoc effects :state (apply-backfill new-state backfill))

      (or (history-excluded-events type)
          (history/document-roots-identical? state new-state)
          (not (history/undoable-changed? (history/extract-document-state state)
                                          (history/extract-document-state new-state))))
      effects

      ;; The open press's own change, or the press that just began: it
      ;; extends that press's transaction
      (and (some? id) (= id (history/gesture-id (:history new-state))))
      (assoc effects :state (update new-state :history history/touch-transaction id now))

      ;; Any other undoable change while a press is open: that press's step
      ;; is sealed against the document before this change
      (get-in state [:history :transaction])
      (let [sealed (history/commit-transaction (:history state) state)]
        (assoc effects :state (assoc new-state :history (if skip?
                                                          sealed
                                                          (push-step sealed state type description coalesce-key now)))))

      skip?
      effects

      :else
      (assoc effects :state (assoc new-state :history (push-step (:history new-state) state type description coalesce-key now))))))

(defn wrap-history
  "Middleware that wraps handle-event to intercept state modifications. The
   handler runs once; its result already holds the show lock's refusal and
   the timeline invariants. For an undoable change it pushes the
   pre-action snapshot on the undo stack, with gestures (see the ns doc):
   - first, an open press that is due is sealed against the document as it
     is (and published, whatever the handler returns);
   - an event tagged with a press (:history/gesture-id) that isn't the open
     one does nothing: its handler doesn't run. A begin
     (gesture-begin-events) brings its own new id;
   - the open press's own undoable change extends its transaction (no step);
     any other undoable change seals it first, then is a step of its own;
     a change that isn't undoable (a beat, the UI, the live state) touches
     neither;
   - the wake-ups follow the open press (:history/gesture-alarm).
   :history/backfill (effects) is applied to all snapshots here and passed on
   to wrap-dirty for the saved baseline."
  [handler]
  (fn [event]
    (let [now (or (:time event) (System/currentTimeMillis))
          given (:state event)
          {:keys [state event/type] :as event} (seal-due event now)
          id (:history/gesture-id event)
          open-id (history/gesture-id (:history state))
          effects (if (and (some? id) (not= id open-id) (not (contains? gesture-begin-events type)))
                    {}
                    (record event state (handler event) now))]
      (-> effects
          (publish given state)
          (with-gesture-alarm given now)))))

(defn- transaction-closed?
  "Did the press whose transaction was open end, or another take its place?"
  [state new-state]
  (let [tx (get-in state [:history :transaction])
        new-tx (get-in new-state [:history :transaction])]
    (boolean (and tx (or (nil? new-tx) (not= (:gesture-id tx) (:gesture-id new-tx)))))))

(defn- full-dirty-recompute?
  "Events whose result can move the document back to (or re-define) the saved
   baseline: undo/redo/abort, a drag transaction closing, and anything that
   changed [:project] (save, load, save-failed, mark-clean)."
  [type state new-state]
  (or (= "history" (namespace type))
      (#{:edit/undo :edit/redo} type)
      (transaction-closed? state new-state)
      (not (identical? (:project state) (:project new-state)))))

(defn- next-dirty
  "The :dirty? flag after this event. Exact (compared with the saved baseline)
   whenever the answer could change, but cheap on the hot path:
   - events that don't change persisted data keep the flag (only the entries
     this event touched are compared, so trigger/live events stay cheap);
   - during an open drag transaction a dirty project stays dirty until the
     transaction closes, which recomputes."
  [type state new-state]
  (let [was-dirty? (boolean (get-in state [:project :dirty?]))]
    (cond
      (full-dirty-recompute? type state new-state)
      (history/derive-dirty state new-state)

      (history/document-roots-identical? state new-state)
      was-dirty?

      (and was-dirty? (get-in new-state [:history :transaction]))
      true

      (not (history/persisted-changed? (history/extract-document-state state)
                                       (history/extract-document-state new-state)))
      was-dirty?

      ;; Already dirty: this edit might restore the saved version
      was-dirty?
      (history/derive-dirty state new-state)

      :else true)))

(defn wrap-dirty
  "Middleware that derives [:project :dirty?] after every event.
   Must wrap wrap-history (outermost), so undo/redo are covered too."
  [handler]
  (fn [{:keys [state event/type] :as event}]
    (let [effects (handler event)
          backfill (:history/backfill effects)
          effects (dissoc effects :history/backfill)
          new-state (:state effects)]
      (if (or (nil? new-state)
              (and (history/document-roots-identical? state new-state)
                   (identical? (:project state) (:project new-state))
                   (not (transaction-closed? state new-state))))
        effects
        (let [new-state (cond-> new-state backfill (history/backfill-baseline backfill))
              dirty? (if backfill
                       ;; Backfilled content is never an unsaved change
                       (if (get-in new-state [:project :saved-document])
                         (history/derive-dirty state new-state)
                         (boolean (get-in state [:project :dirty?])))
                       (next-dirty type state new-state))]
          (assoc effects :state
                 (if (= dirty? (boolean (get-in new-state [:project :dirty?])))
                   new-state
                   (assoc-in new-state [:project :dirty?] dirty?))))))))
(defn handle
  "Dispatch history events."
  [{:keys [event/type] :as event}]
  (case type
    :history/begin-transaction (handle-begin-transaction event)
    :history/commit-transaction (handle-commit-transaction event)
    :history/abort-transaction (handle-abort-transaction event)
    :history/gesture-timeout (handle-gesture-timeout event)
    :history/record-snapshot (handle-record-snapshot event)
    :history/undo (handle-undo event)
    :history/redo (handle-redo event)
    :history/clear (handle-clear event)
    {}))
