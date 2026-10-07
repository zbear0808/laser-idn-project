(ns laser-show.events.handlers.history
  "Event handlers for undo, redo, and transaction lifecycle management."
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
                  (history/apply-document-state (:document-state baseline))
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

(defn handle-undo
  "Undo the last action."
  [{:keys [state]}]
  (let [current-history (get state :history {})]
    (if (history/can-undo? current-history)
      (let [ui-context {:active-tab (get-in state [:ui :active-tab])
                        :selected-cell (get-in state [:grid :selected-cell])}
            {:keys [history snapshot]} (history/pop-undo current-history state ui-context)
            restored-state (-> state
                               (history/apply-document-state (:document-state snapshot))
                               (assoc :history history))]
        (log/info "Undo performed:" (:description snapshot))
        {:state restored-state})
      (do
        (log/debug "Undo requested but stack is empty")
        {:state state}))))

(defn handle-redo
  "Redo the last undone action."
  [{:keys [state]}]
  (let [current-history (get state :history {})]
    (if (history/can-redo? current-history)
      (let [ui-context {:active-tab (get-in state [:ui :active-tab])
                        :selected-cell (get-in state [:grid :selected-cell])}
            {:keys [history snapshot]} (history/pop-redo current-history state ui-context)
            restored-state (-> state
                               (history/apply-document-state (:document-state snapshot))
                               (assoc :history history))]
        (log/info "Redo performed:" (:description snapshot))
        {:state restored-state})
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
    :file/open-result})

(defn format-event-description
  "Generate a human-readable description for an event type."
  [event-type]
  (let [event-name (name event-type)]
    (case event-type
      :grid/clear-cell "Clear Cell"
      :grid/move-cell "Move Cell"
      :grid/paste-cell "Paste Cell"
      :chain/set-items "Modify Chain"
      :list/delete-selected "Delete Item(s)"
      :list/perform-drop "Reorder Items"
      :list/group-selected "Group Items"
      :list/ungroup "Ungroup"
      :list/commit-rename "Rename Item"
      :cue-chain/add-preset "Add Preset"
      :cue-chain/add-effect-from-bank "Add Effect"
      :cue-chain/set-name "Rename Cue"
      :cue-chain/set-destination-zone-group "Change Zone Group"
      :cue-chain/set-trigger-mode "Change Trigger Mode"
      :timeline/add-track "Add Track"
      :timeline/delete-track "Delete Track"
      :timeline/move-track "Move Track"
      :timeline/add-preset-to-track "Add Content to Track"
      :projectors/add-projector "Add Projector"
      :projectors/delete-projector "Delete Projector"
      :zone-groups/add-group "Add Zone Group"
      :zone-groups/delete-group "Delete Zone Group"
      (-> event-name
          (str/replace #"^.+/" "")
          (str/replace #"-" " ")
          (str/capitalize)))))

(defn wrap-history
  "Middleware that wraps handle-event to intercept state modifications.
   For discrete events that modify document state while no transaction is active,
   captures a pre-action snapshot on the undo stack."
  [handler]
  (fn [{:keys [state event/type] :as event}]
    (let [effects (handler event)]
      (if-let [new-state (:state effects)]
        (if (or (history-excluded-events type)
                (get-in state [:history :transaction]))
          effects
          (let [old-doc (history/extract-document-state state)
                new-doc (history/extract-document-state new-state)]
            (if (= old-doc new-doc)
              effects
              (let [desc (or (:history/description event)
                             (format-event-description type))
                    ui-context {:active-tab (get-in state [:ui :active-tab])
                                :selected-cell (get-in state [:grid :selected-cell])}
                    snapshot (history/make-snapshot {:state state
                                                     :description desc
                                                     :ui-context ui-context})
                    history-with-snapshot (history/push-undo-snapshot (get new-state :history {}) snapshot)]
                (assoc effects :state (assoc new-state :history history-with-snapshot))))))
        effects))))

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
