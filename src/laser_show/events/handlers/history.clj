(ns laser-show.events.handlers.history
  "Event handlers for undo, redo, and transaction lifecycle management,
   plus the two pipeline middlewares:
   - wrap-history: pushes undo snapshots for undoable document edits
   - wrap-dirty: derives [:project :dirty?] from the saved baseline
   
   History meta keys (consumed here, never passed on to cljfx effects):
   - :history/skip?         (event or effects) never push an undo snapshot
   - :history/coalesce-key  (event or effects) merge bursts into one undo step
   - :history/description   (event) undo label
   - :document/rebase?      (effects) if the project was clean, the change
                            becomes part of the saved baseline (not 'unsaved')
   Events dispatched by triggers (:source-trigger set) are always skipped."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [laser-show.state.history :as history]))

(defn handle-begin-transaction
  "Begin a continuous interaction transaction on mouse down."
  [{:keys [label source-id ui-context state]}]
  (let [current-history (get state :history {})
        updated-history (history/begin-transaction current-history state
                                                   {:label (or label "Edit")
                                                    :source-id source-id
                                                    :ui-context ui-context})]
    {:state (assoc state :history updated-history)}))

(defn handle-commit-transaction
  "Commit a continuous interaction transaction on mouse up."
  [{:keys [source-id state]}]
  (let [current-history (get state :history {})
        updated-history (history/commit-transaction current-history state
                                                    history/default-max-depth
                                                    {:source-id source-id})]
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

(defn- hardware-changed?
  "True when projector configuration differs, so streaming engines need a refresh."
  [old-state new-state]
  (some (fn [path]
          (let [a (get-in old-state path)
                b (get-in new-state path)]
            (and (not (identical? a b)) (not= a b))))
        [[:projectors] [:virtual-projectors] [:chains :projector-effects]]))

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
    (cond-> {:state restored-state}
      (hardware-changed? state restored-state) (assoc :multi-engine/refresh true))))

(defn handle-undo
  "Undo the last action."
  [{:keys [state]}]
  (let [current-history (get state :history {})]
    (if (history/can-undo? current-history)
      (apply-restore state (history/pop-undo current-history state (ui-context state)) "Undo")
      (do
        (log/debug "Undo requested but stack is empty")
        {:state state}))))

(defn handle-redo
  "Redo the last undone action."
  [{:keys [state]}]
  (let [current-history (get state :history {})]
    (if (history/can-redo? current-history)
      (apply-restore state (history/pop-redo current-history state (ui-context state)) "Redo")
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
   :chain/set-items "Modify Chain"
   :cue-chain/add-preset "Add Preset"
   :cue-chain/add-effect-from-bank "Add Effect"
   :cue-chain/set-name "Rename Cue"
   :cue-chain/set-destination-zone-group "Change Zone Group"
   :cue-chain/set-trigger-mode "Change Trigger Mode"
   :timeline/add-track "Add Track"
   :timeline/add-folder "Add Folder"
   :timeline/update-tracks "Edit Tracks"
   :timeline/add-preset-to-track "Add Content to Track"
   :timeline/delete-selection "Delete Clip(s)"
   :timeline/move-item-to-track "Move Clip to Track"
   :timeline/update-item-timing "Move Clip"
   :timeline/nudge-selection "Nudge Clip(s)"
   :timeline/update-loop-timing "Edit Loop"
   :projectors/add-manual "Add Projector"
   :zone-groups/create-new "Add Zone Group"
   :zone-groups/save-edit "Edit Zone Group"
   :zone-groups/remove "Delete Zone Group"})

(defn format-event-description
  "Generate a human-readable description for an event type."
  [event-type]
  (or (get event-descriptions event-type)
      (-> (name event-type)
          (str/replace #"-" " ")
          (str/capitalize))))

(defn- commit-stale-transaction
  "Commit a transaction left open too long (e.g. a missed mouse-up)."
  [{:keys [state time event/type] :as event}]
  (let [h (get state :history {})]
    (if (and (not= "history" (namespace type))
             (history/stale-transaction? h (or time (System/currentTimeMillis))))
      (do (log/warn "Auto-committing stale history transaction" (get-in h [:transaction :label]))
          (assoc event :state (assoc state :history (history/commit-transaction h state))))
      event)))

(defn wrap-history
  "Middleware that wraps handle-event to intercept state modifications.
   For discrete events that change undoable content while no transaction is
   active, captures a pre-action snapshot on the undo stack."
  [handler]
  (fn [event]
    (let [{:keys [state event/type] :as event} (commit-stale-transaction event)
          effects (handler event)
          skip? (or (:history/skip? event)
                    (:history/skip? effects)
                    (some? (:source-trigger event)))
          coalesce-key (or (:history/coalesce-key event) (:history/coalesce-key effects))
          effects (dissoc effects :history/skip? :history/coalesce-key)
          new-state (:state effects)]
      (if (or (nil? new-state)
              skip?
              (history-excluded-events type)
              (get-in state [:history :transaction])
              (history/document-roots-identical? state new-state)
              (not (history/undoable-changed? (history/extract-document-state state)
                                              (history/extract-document-state new-state))))
        effects
        (let [desc (or (:history/description event)
                       (format-event-description type))
              snapshot (history/make-snapshot {:state state
                                               :description desc
                                               :ui-context (ui-context state)})
              updated-history (history/push-undo-snapshot (get new-state :history {})
                                                          snapshot
                                                          history/default-max-depth
                                                          {:coalesce-key coalesce-key
                                                           :now (:time event)})]
          (assoc effects :state (assoc new-state :history updated-history)))))))

(defn wrap-dirty
  "Middleware that derives [:project :dirty?] after every event.
   Must wrap wrap-history (outermost), so undo/redo are covered too."
  [handler]
  (fn [{:keys [state] :as event}]
    (let [effects (handler event)
          rebase? (:document/rebase? effects)
          effects (dissoc effects :document/rebase?)
          new-state (:state effects)]
      (if (or (nil? new-state)
              (and (history/document-roots-identical? state new-state)
                   (identical? (:project state) (:project new-state))))
        effects
        (let [was-dirty? (boolean (get-in state [:project :dirty?]))
              new-state' (if (and rebase?
                                  (not was-dirty?)
                                  (get-in new-state [:project :saved-document]))
                           (history/mark-clean new-state)
                           (let [dirty? (history/derive-dirty state new-state)]
                             (if (= dirty? (boolean (get-in new-state [:project :dirty?])))
                               new-state
                               (assoc-in new-state [:project :dirty?] dirty?))))]
          (assoc effects :state new-state'))))))

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
