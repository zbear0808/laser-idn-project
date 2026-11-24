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
   Events dispatched by triggers (:source-trigger set) are always skipped."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [laser-show.events.safety-guard :as guard]
            [laser-show.state.history :as history]))

(defn handle-begin-transaction
  "Begin a continuous interaction transaction on mouse down."
  [{:keys [label source-id ui-context state time]}]
  (let [current-history (get state :history {})
        updated-history (history/begin-transaction current-history state
                                                   {:label (or label "Edit")
                                                    :source-id source-id
                                                    :ui-context ui-context
                                                    :now time})]
    {:state (assoc state :history updated-history)}))

(defn handle-commit-transaction
  "Commit a continuous interaction transaction on mouse up."
  [{:keys [state]}]
  (let [current-history (get state :history {})
        updated-history (history/commit-transaction current-history state)]
    {:state (assoc state :history updated-history)}))

(defn handle-abort-transaction
  "Abort an active continuous transaction, reverting document to baseline."
  [{:keys [state]}]
  (let [current-history (get state :history {})
        {:keys [history baseline]} (history/abort-transaction current-history)]
    (if baseline
      {:state (-> state
                  (history/restore-document (:document-state baseline))
                  (assoc :history history))}
      {:state (assoc state :history history)})))

(defn handle-record-snapshot
  "Record a pre-action snapshot for discrete edits."
  [{:keys [description ui-context state]}]
  (let [current-history (get state :history {})
        updated-history (if (:transaction current-history)
                          current-history
                          (let [snapshot (history/make-snapshot {:state state
                                                                :description (or description "Action")
                                                                :ui-context ui-context})]
                            (history/push-undo-snapshot current-history snapshot)))]
    {:state (assoc state :history updated-history)}))

(defn- ui-context [state]
  {:active-tab (get-in state [:ui :active-tab])
   :selected-cell (get-in state [:grid :selected-cell])})

(defn- prune-active-cues
  "Drop active cues whose cue cell no longer exists."
  [state]
  (let [cue-chains (get-in state [:chains :cue-chains] {})
        active (get-in state [:playback :active-cues])]
    (if (and (map? active) (some #(not (contains? cue-chains %)) (keys active)))
      (assoc-in state [:playback :active-cues]
                (into {} (filter (fn [[k _]] (contains? cue-chains k))) active))
      state)))

(defn- apply-restore
  "Restore a popped snapshot and build the effects map."
  [state {:keys [history snapshot]} verb]
  (let [restored-state (-> state
                           (history/restore-document (:document-state snapshot))
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
  "Undo the last action. One that would give a live projector a larger
   safety zone asks first."
  [{:keys [state] :as event}]
  (let [current-history (get state :history {})]
    (if (history/can-undo? current-history)
      (ask-first-if-it-grows state event
                             (apply-restore state (history/pop-undo current-history state (ui-context state)) "Undo")
                             "Undo")
      (do
        (log/debug "Undo requested but stack is empty")
        {:state state}))))

(defn handle-redo
  "Redo the last undone action. One that would give a live projector a
   larger safety zone asks first."
  [{:keys [state] :as event}]
  (let [current-history (get state :history {})]
    (if (history/can-redo? current-history)
      (ask-first-if-it-grows state event
                             (apply-restore state (history/pop-redo current-history state (ui-context state)) "Redo")
                             "Redo")
      (do
        (log/debug "Redo requested but stack is empty")
        {:state state}))))

(defn handle-clear
  "Clear all undo/redo history."
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
    :history/clear
    :history/record-snapshot
    :file/new-project
    :file/open
    :file/open-result
    :file/project-loaded})

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
   :projectors/remove "Remove Projector"
   :projectors/set-safety "Change Safety Zone"
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

(defn- commit-stale-transaction
  "Commit a transaction left open with no activity for too long (e.g. a missed mouse-up)."
  [{:keys [state time event/type] :as event}]
  (let [h (get state :history {})]
    (if (and (not= "history" (namespace type))
             (history/stale-transaction? h (or time (System/currentTimeMillis))))
      (do (log/warn "Auto-committing stale history transaction" (get-in h [:transaction :label]))
          (assoc event :state (assoc state :history (history/commit-transaction h state))))
      event)))

(defn- apply-backfill
  "Fold a non-user change into every stored snapshot (see state.history Backfill)."
  [new-state backfill]
  (if backfill
    (update new-state :history #(history/backfill-history (or % {}) backfill))
    new-state))

(defn wrap-history
  "Middleware that wraps handle-event to intercept state modifications.
   For discrete events that change undoable content while no transaction is
   active, captures a pre-action snapshot on the undo stack.
   
   :history/backfill (effects) is applied to all snapshots here and passed on
   to wrap-dirty for the saved baseline."
  [handler]
  (fn [event]
    (let [{:keys [state event/type] :as event} (commit-stale-transaction event)
          effects (handler event)
          backfill (:history/backfill effects)
          skip? (or backfill
                    (:history/skip? event)
                    (:history/skip? effects)
                    (some? (:source-trigger event)))
          coalesce-key (or (:history/coalesce-key event) (:history/coalesce-key effects))
          description (or (:history/description effects) (:history/description event))
          effects (dissoc effects :history/skip? :history/coalesce-key :history/description)
          new-state (:state effects)
          now (or (:time event) (System/currentTimeMillis))]
      (cond
        (nil? new-state)
        effects

        backfill
        (assoc effects :state (apply-backfill new-state backfill))

        (or skip?
            (history-excluded-events type)
            (history/document-roots-identical? state new-state))
        effects

        (get-in state [:history :transaction])
        (if (get-in new-state [:history :transaction])
          (assoc effects :state (update new-state :history history/touch-transaction now))
          effects)

        (not (history/undoable-changed? (history/extract-document-state state)
                                        (history/extract-document-state new-state)))
        effects

        :else
        (let [desc (or description (format-event-description type))
              snapshot (history/make-snapshot {:state state
                                               :description desc
                                               :ui-context (ui-context state)})
              updated-history (history/push-undo-snapshot (get new-state :history {})
                                                          snapshot
                                                          history/default-max-depth
                                                          {:coalesce-key coalesce-key
                                                           :now now})]
          (assoc effects :state (assoc new-state :history updated-history)))))))

(defn- transaction-closed? [state new-state]
  (and (get-in state [:history :transaction])
       (nil? (get-in new-state [:history :transaction]))))

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
    :history/record-snapshot (handle-record-snapshot event)
    :history/undo (handle-undo event)
    :history/redo (handle-redo event)
    :history/clear (handle-clear event)
    {}))
