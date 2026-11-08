(ns laser-show.state.history
  "Pure state history management for undo/redo.
   
   This module handles:
   - Extracting document-only subtrees from global state
   - The document policy: which document data is undoable, which only marks
     the project unsaved, and which is live (neither)
   - Capturing immutable snapshots
   - Pushing/popping undo and redo stacks
   - Transaction boundary management (mouse-down -> continuous drag -> mouse-up)
   - Restoring snapshots while keeping live state and preferences
   - Deriving the unsaved-changes flag by comparing against the saved document
   
   Document policy (see plans/2026-11-08-undo-history-boundary.md):
   | Data                                              | Undo? | Unsaved? |
   |---------------------------------------------------|-------|----------|
   | content (chains, projectors, zone groups, grid,   | yes   | yes      |
   |   [:config :grid/:input])                         |       |          |
   | prefs ([:config <prefs-config-keys>])             | no    | yes      |
   | live: [:timing :bpm], effect cell :active?,       | no    | no       |
   |   item fields in live-item-paths                  |       |          |
   Everything above is still written when the project is saved."
  (:require [clojure.walk :as walk]
            [laser-show.common.util :as u]))

(def default-max-depth
  "Default maximum number of history snapshots to retain."
  50)

(def coalesce-window-ms
  "Pushes with the same :coalesce-key within this window merge into one undo step."
  1000)

(def stale-transaction-ms
  "An open transaction older than this is committed automatically
   (guards against a missed mouse-up disabling undo)."
  60000)

(def prefs-config-keys
  "[:config k] entries that are saved and mark the project unsaved, but are not undoable."
  #{:preview :window :idn :osc :midi :cue})

(def live-item-paths
  "Paths inside any chain map with an :id that hold view/live state.
   They are neither undoable nor unsaved changes."
  [[:collapsed?] [:keyframe-modulator :selected-keyframe]])

(def ^:private document-roots
  "Top-level state keys that extract-document-state reads from."
  [:config :timing :grid :projectors :virtual-projectors :zone-groups :chains])

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

(defn document-roots-identical?
  "True when no document root changed between the two states (cheap fast path)."
  [old-state new-state]
  (every? #(identical? (get old-state %) (get new-state %)) document-roots))


;; Document Policy: Comparison


(defn- differs?
  [a b]
  (and (not (identical? a b)) (not= a b)))

(defn- chain-item? [x]
  (and (map? x) (contains? x :id)))

(defn- dissoc-path
  [m path]
  (if (= 1 (count path))
    (dissoc m (first path))
    (let [parent (pop path)]
      (if (map? (get-in m parent))
        (update-in m parent dissoc (peek path))
        m))))

(defn- strip-live-item [item]
  (reduce dissoc-path item live-item-paths))

(defn- strip-chain-entry
  "Remove live fields from one chain entry (e.g. [:chains :cue-chains [0 0]])."
  [domain entry]
  (let [stripped (walk/postwalk #(if (chain-item? %) (strip-live-item %) %) entry)]
    (if (and (= domain :effect-chains) (map? stripped))
      (dissoc stripped :active?)
      stripped)))

(defn- chains-changed?
  [old-chains new-chains]
  (boolean
   (and (not (identical? old-chains new-chains))
        (some (fn [domain]
                (let [o (get old-chains domain)
                      n (get new-chains domain)]
                  (and (not (identical? o n))
                       (some (fn [k]
                               (let [oe (get o k)
                                     ne (get n k)]
                                 (and (not (identical? oe ne))
                                      (not= (strip-chain-entry domain oe)
                                            (strip-chain-entry domain ne)))))
                             (into (set (keys o)) (keys n))))))
              (into (set (keys old-chains)) (keys new-chains))))))

(defn- config-changed?
  [old-config new-config include-key?]
  (boolean
   (and (not (identical? old-config new-config))
        (some (fn [k]
                (and (include-key? k)
                     (differs? (get old-config k) (get new-config k))))
              (into (set (keys old-config)) (keys new-config))))))

(defn- content-changed?
  [old-doc new-doc]
  (boolean
   (or (some #(differs? (get old-doc %) (get new-doc %))
             [:grid :projectors :virtual-projectors :zone-groups])
       (config-changed? (:config old-doc) (:config new-doc) (complement prefs-config-keys))
       (chains-changed? (:chains old-doc) (:chains new-doc)))))

(defn undoable-changed?
  "True when undoable content differs between two documents (from extract-document-state).
   Ignores BPM, effect cell :active?, live item fields and config prefs."
  [old-doc new-doc]
  (content-changed? old-doc new-doc))

(defn persisted-changed?
  "True when anything that counts as an unsaved change differs between two documents:
   undoable content plus config prefs. Ignores live state."
  [old-doc new-doc]
  (boolean
   (or (content-changed? old-doc new-doc)
       (config-changed? (:config old-doc) (:config new-doc) prefs-config-keys))))


;; Document Policy: Restore


(defn- live-item-index
  "Map of item :id -> {path value-or-::absent} for every chain item in entry."
  [entry]
  (into {}
        (comp (filter chain-item?)
              (map (fn [item]
                     [(:id item)
                      (into {} (map (fn [p] [p (get-in item p ::absent)])) live-item-paths)])))
        (tree-seq coll? seq entry)))

(defn- graft-live-item
  [item fields]
  (reduce-kv (fn [m path v]
               (cond
                 (= ::absent v) (dissoc-path m path)
                 (or (= 1 (count path)) (map? (get-in m (pop path)))) (assoc-in m path v)
                 :else m))
             item
             fields))

(defn- graft-live-entry
  "Copy live fields from the current entry onto the restored entry."
  [domain current-entry restored-entry]
  (if (or (nil? current-entry) (identical? current-entry restored-entry) (not (map? restored-entry)))
    restored-entry
    (let [index (live-item-index current-entry)
          grafted (if (seq index)
                    (walk/postwalk (fn [x]
                                     (if-let [fields (and (chain-item? x) (get index (:id x)))]
                                       (graft-live-item x fields)
                                       x))
                                   restored-entry)
                    restored-entry)]
      (if (and (= domain :effect-chains) (contains? current-entry :active?))
        (assoc grafted :active? (:active? current-entry))
        grafted))))

(defn- graft-live-chains
  [current-chains restored-chains]
  (reduce-kv (fn [acc domain entries]
               (assoc acc domain
                      (reduce-kv (fn [m k entry]
                                   (assoc m k (graft-live-entry domain (get-in current-chains [domain k]) entry)))
                                 entries
                                 entries)))
             restored-chains
             restored-chains))

(defn restore-document
  "Restore the undoable content of a document snapshot into state.
   Keeps current live state (BPM, effect cell :active?, live item fields) and
   config prefs. Runtime systems are untouched. Does not set :dirty?; that is
   derived by the event pipeline."
  [state document-state]
  (let [{:keys [config grid projectors virtual-projectors zone-groups chains]} document-state
        current-config (:config state)
        restored-config (merge (apply dissoc config prefs-config-keys)
                               (select-keys current-config prefs-config-keys))]
    (-> state
        (assoc :config restored-config)
        (assoc-in [:grid :size] (:size grid))
        (assoc :projectors (or projectors {}))
        (assoc :virtual-projectors (or virtual-projectors {}))
        (assoc :zone-groups (or zone-groups {}))
        (assoc :chains (graft-live-chains (:chains state) (or chains {}))))))



;; Unsaved Changes


(defn mark-clean
  "Record the current document as the saved baseline and clear :dirty?."
  [state]
  (-> state
      (assoc-in [:project :saved-document] (extract-document-state state))
      (assoc-in [:project :dirty?] false)))

(defn derive-dirty
  "Return the :dirty? flag for new-state.
   With a saved baseline: true when the persisted document differs from it.
   Without one (bare states in tests): the flag already on new-state, or
   changed by this event."
  [old-state new-state]
  (if-let [baseline (get-in new-state [:project :saved-document])]
    (persisted-changed? baseline (extract-document-state new-state))
    (or (boolean (get-in new-state [:project :dirty?]))
        (and (not (document-roots-identical? old-state new-state))
             (persisted-changed? (extract-document-state old-state)
                                 (extract-document-state new-state))))))

(defn make-snapshot
  "Create a HistorySnapshot map from current state."
  [{:keys [state description ui-context timestamp]}]
  (let [document-state (extract-document-state state)
        snapshot-time (or timestamp (System/currentTimeMillis))]
    (u/->map& document-state description ui-context
              :timestamp snapshot-time)))

(defn- cap-stack
  [stack max-depth]
  (let [limit (or max-depth default-max-depth)]
    (if (>= (count stack) limit)
      (subvec stack (- (count stack) (dec limit)))
      stack)))

(defn push-undo-snapshot
  "Pushes a snapshot onto the undo stack and clears the redo stack.
   Enforces max-depth limit.
   
   With opts {:coalesce-key k :now ms}: if the top undo entry has the same
   :coalesce-key, was pushed or touched less than coalesce-window-ms ago, and
   redo is empty, the push is merged into it (the top entry keeps its older
   baseline). Used for bursts like arrow-key nudges."
  ([history-state snapshot]
   (push-undo-snapshot history-state snapshot default-max-depth))
  ([history-state snapshot max-depth]
   (push-undo-snapshot history-state snapshot max-depth nil))
  ([history-state snapshot max-depth {:keys [coalesce-key now]}]
   (let [undo-stack (:undo-stack history-state [])
         top (peek undo-stack)
         now (or now (System/currentTimeMillis))]
     (if (and coalesce-key
              top
              (= coalesce-key (:coalesce-key top))
              (empty? (:redo-stack history-state))
              (< (- now (or (:last-touched top) (:timestamp top) 0)) coalesce-window-ms))
       (assoc history-state :undo-stack (conj (pop undo-stack) (assoc top :last-touched now)))
       (assoc history-state
              :undo-stack (conj (cap-stack undo-stack max-depth)
                                (cond-> snapshot
                                  coalesce-key (assoc :coalesce-key coalesce-key :last-touched now)))
              :redo-stack [])))))

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

(defn commit-transaction
  "Commit a continuous interaction transaction on mouse-up.
   Compares the current undoable content to the baseline snapshot.
   If modified, pushes baseline snapshot to the undo stack.
   Clears active transaction.
   
   With opts {:source-id id}: only commits when id matches the open
   transaction's :source-id (a nil id commits whatever is open)."
  ([history-state current-state]
   (commit-transaction history-state current-state default-max-depth))
  ([history-state current-state max-depth]
   (commit-transaction history-state current-state max-depth nil))
  ([history-state current-state max-depth {:keys [source-id]}]
   (if-let [tx (:transaction history-state)]
     (if (and (some? source-id) (some? (:source-id tx)) (not= source-id (:source-id tx)))
       history-state
       (let [baseline (:baseline-snapshot tx)
             changed? (undoable-changed? (:document-state baseline)
                                         (extract-document-state current-state))
             history-without-tx (assoc history-state :transaction nil)]
         (if changed?
           (push-undo-snapshot history-without-tx baseline max-depth)
           history-without-tx)))
     history-state)))

(defn begin-transaction
  "Begin a continuous interaction transaction on mouse-down.
   Captures a baseline document snapshot of the current state.
   If a transaction from the same source is already open it is kept; one from
   a different source (e.g. its mouse-up was missed) is committed first."
  [history-state current-state {:keys [label source-id ui-context]}]
  (let [open-tx (:transaction history-state)]
    (if (and open-tx (= source-id (:source-id open-tx)))
      history-state
      (let [history-state (if open-tx
                            (commit-transaction history-state current-state)
                            history-state)
            baseline (make-snapshot {:state current-state
                                     :description label
                                     :ui-context ui-context})]
        (assoc history-state
               :transaction (u/->map& label source-id
                                      :baseline-snapshot baseline
                                      :start-time (System/currentTimeMillis)))))))

(defn stale-transaction?
  "True when an open transaction started more than stale-transaction-ms before now."
  [history-state now]
  (when-let [start (get-in history-state [:transaction :start-time])]
    (> (- now start) stale-transaction-ms)))

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
           new-undo (conj (cap-stack (:undo-stack history-state []) max-depth) undo-snapshot)
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

