(ns laser-show.state.history
  "Pure state history management for undo/redo.
   
   This module handles:
   - Extracting document-only subtrees from global state
   - Capturing immutable snapshots
   - Pushing/popping undo and redo stacks
   - Transaction boundary management (mouse-down -> continuous drag -> mouse-up)
   - Applying restored snapshots to state while preserving runtime systems"
  (:require [laser-show.common.util :as u]))

(def default-max-depth
  "Default maximum number of history snapshots to retain."
  50)

(defn extract-document-state
  "Extract document configuration from state.
   Only captures persistent project fields (cues, effects, projectors, zones, config, bpm, grid size).
   Excludes all runtime data (playback, streaming engines, IDN discovery, transient UI, history)."
  [state]
  (let [{:keys [config timing grid projectors virtual-projectors zone-groups chains]} state]
    {:config config
     :timing {:bpm (:bpm timing)}
     :grid {:size (:size grid)}
     :projectors (or projectors {})
     :virtual-projectors (or virtual-projectors {})
     :zone-groups (or zone-groups {})
     :chains (or chains {})}))

(defn apply-document-state
  "Applies a document snapshot back to the application state.
   Preserves runtime systems (playback, clocks, backend connections, dialog open states).
   Marks the project dirty."
  [state document-state]
  (let [{:keys [config timing grid projectors virtual-projectors zone-groups chains]} document-state]
    (-> state
        (assoc :config config)
        (assoc-in [:timing :bpm] (:bpm timing))
        (assoc-in [:grid :size] (:size grid))
        (assoc :projectors (or projectors {}))
        (assoc :virtual-projectors (or virtual-projectors {}))
        (assoc :zone-groups (or zone-groups {}))
        (assoc :chains (or chains {}))
        (assoc-in [:project :dirty?] true))))

(defn make-snapshot
  "Create a HistorySnapshot map from current state."
  [{:keys [state description ui-context timestamp]}]
  (let [document-state (extract-document-state state)
        snapshot-time (or timestamp (System/currentTimeMillis))]
    (u/->map& document-state description ui-context
              :timestamp snapshot-time)))

(defn push-undo-snapshot
  "Pushes a snapshot onto the undo stack and clears the redo stack.
   Enforces max-depth limit."
  ([history-state snapshot]
   (push-undo-snapshot history-state snapshot default-max-depth))
  ([history-state snapshot max-depth]
   (let [undo-stack (:undo-stack history-state [])
         limit (or max-depth default-max-depth)
         capped-stack (if (>= (count undo-stack) limit)
                        (subvec undo-stack (- (count undo-stack) (dec limit)))
                        undo-stack)]
     (assoc history-state
            :undo-stack (conj capped-stack snapshot)
            :redo-stack []))))

(defn can-undo?
  "Returns true if an undo action is available."
  [history-state]
  (boolean (seq (:undo-stack history-state))))

(defn can-redo?
  "Returns true if a redo action is available."
  [history-state]
  (boolean (seq (:redo-stack history-state))))

(defn undo-label
  "Returns the description of the action that will be undone, or nil."
  [history-state]
  (:description (peek (:undo-stack history-state))))

(defn redo-label
  "Returns the description of the action that will be redone, or nil."
  [history-state]
  (:description (peek (:redo-stack history-state))))

(defn begin-transaction
  "Begin a continuous interaction transaction on mouse-down.
   Captures a baseline document snapshot of the current state."
  [history-state current-state {:keys [label source-id ui-context]}]
  (if (:transaction history-state)
    history-state
    (let [baseline (make-snapshot {:state current-state
                                   :description label
                                   :ui-context ui-context})]
      (assoc history-state
             :transaction (u/->map& label source-id
                                    :baseline-snapshot baseline
                                    :start-time (System/currentTimeMillis))))))

(defn commit-transaction
  "Commit a continuous interaction transaction on mouse-up.
   Compares the current document state to the baseline snapshot.
   If modified, pushes baseline snapshot to the undo stack.
   Clears active transaction."
  ([history-state current-state]
   (commit-transaction history-state current-state default-max-depth))
  ([history-state current-state max-depth]
   (if-let [tx (:transaction history-state)]
     (let [baseline (:baseline-snapshot tx)
           baseline-doc (:document-state baseline)
           current-doc (extract-document-state current-state)
           changed? (not= baseline-doc current-doc)
           history-without-tx (assoc history-state :transaction nil)]
       (if changed?
         (push-undo-snapshot history-without-tx baseline max-depth)
         history-without-tx))
     history-state)))

(defn abort-transaction
  "Abort an in-progress transaction without saving to undo stack.
   Returns map with updated history state and the baseline snapshot to restore."
  [history-state]
  (if-let [tx (:transaction history-state)]
    {:history (assoc history-state :transaction nil)
     :baseline (:baseline-snapshot tx)}
    {:history history-state
     :baseline nil}))

(defn pop-undo
  "Pop the top snapshot from the undo stack to restore it.
   Captures current document state onto the redo stack.
   Returns {:history updated-history-state :snapshot snapshot-to-restore} or nil."
  [history-state current-state current-ui-context]
  (when (can-undo? history-state)
    (let [snapshot-to-restore (peek (:undo-stack history-state))
          undo-description (or (:description snapshot-to-restore) "Action")
          redo-snapshot (make-snapshot {:state current-state
                                        :description undo-description
                                        :ui-context current-ui-context})
          new-undo (pop (:undo-stack history-state))
          new-redo (conj (or (:redo-stack history-state) []) redo-snapshot)]
      {:history (assoc history-state
                       :undo-stack new-undo
                       :redo-stack new-redo
                       :transaction nil)
       :snapshot snapshot-to-restore})))

(defn pop-redo
  "Pop the top snapshot from the redo stack to re-apply it.
   Captures current document state onto the undo stack.
   Returns {:history updated-history-state :snapshot snapshot-to-restore} or nil."
  ([history-state current-state current-ui-context]
   (pop-redo history-state current-state current-ui-context default-max-depth))
  ([history-state current-state current-ui-context max-depth]
   (when (can-redo? history-state)
     (let [snapshot-to-restore (peek (:redo-stack history-state))
           redo-description (or (:description snapshot-to-restore) "Action")
           undo-snapshot (make-snapshot {:state current-state
                                         :description redo-description
                                         :ui-context current-ui-context})
           limit (or max-depth default-max-depth)
           undo-stack (:undo-stack history-state [])
           capped-undo (if (>= (count undo-stack) limit)
                         (subvec undo-stack (- (count undo-stack) (dec limit)))
                         undo-stack)
           new-undo (conj capped-undo undo-snapshot)
           new-redo (pop (:redo-stack history-state))]
       {:history (assoc history-state
                        :undo-stack new-undo
                        :redo-stack new-redo
                        :transaction nil)
        :snapshot snapshot-to-restore}))))

(defn clear-history
  "Clears all undo and redo history."
  [_history-state]
  {:undo-stack []
   :redo-stack []
   :transaction nil})

