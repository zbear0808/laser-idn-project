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
;;
;; Comparisons ignore live fields without rebuilding anything: they walk both
;; values in step and stop at the first identical? pair, so an edit to one
;; item only compares the path down to that item.


(defn- differs?
  [a b]
  (and (not (identical? a b)) (not= a b)))

(defn- chain-item? [x]
  (and (map? x) (contains? x :id)))

(def ^:private live-item-keys #{:collapsed?})
(def ^:private live-keyframe-keys #{:selected-keyframe})

(declare equiv-ignoring-live?)

(defn- map-equiv?
  "Map equality ignoring the keys in ignore. Chain items also ignore their
   live fields (including :selected-keyframe inside :keyframe-modulator)."
  [a b ignore]
  (let [item? (or (chain-item? a) (chain-item? b))
        ignore (if item? (into ignore live-item-keys) ignore)
        child-equiv? (fn [k x y]
                       (if (and item? (= k :keyframe-modulator) (map? x) (map? y))
                         (or (identical? x y) (map-equiv? x y live-keyframe-keys))
                         (equiv-ignoring-live? x y)))]
    (and (every? (fn [[k v]]
                   (or (contains? ignore k)
                       (and (contains? b k) (child-equiv? k v (get b k)))))
                 a)
         (every? (fn [k] (or (contains? ignore k) (contains? a k))) (keys b)))))

(defn- equiv-ignoring-live?
  [a b]
  (cond
    (identical? a b) true
    (and (map? a) (map? b)) (map-equiv? a b #{})
    (and (sequential? a) (sequential? b)) (and (= (count a) (count b))
                                               (every? true? (map equiv-ignoring-live? a b)))
    :else (= a b)))

(defn- entry-equiv?
  "Compare one chain entry (e.g. [:chains :cue-chains [0 0]]). For effect cells
   :active? is live, and an entry holding nothing but :active? equals no entry."
  [domain a b]
  (if (= domain :effect-chains)
    (let [a (or a {}) b (or b {})]
      (or (identical? a b)
          (and (map? a) (map? b) (map-equiv? a b #{:active?}))))
    (equiv-ignoring-live? a b)))

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
                                      (not (entry-equiv? domain oe ne)))))
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


(defn- chain-items [entry]
  (filter chain-item? (tree-seq coll? seq entry)))

(defn- live-item-index
  "Map of item :id -> current item, for ids that occur exactly once in entry
   (duplicate ids are ambiguous, so those items keep the snapshot's values)."
  [entry]
  (let [items (chain-items entry)
        counts (frequencies (map :id items))]
    (into {} (comp (filter #(= 1 (counts (:id %)))) (map (juxt :id identity))) items)))

(defn- clamp-selected-keyframe [kf-mod]
  (let [n (count (:keyframes kf-mod))
        sel (:selected-keyframe kf-mod)]
    (if (and (integer? sel) (or (neg? sel) (>= sel n)))
      (assoc kf-mod :selected-keyframe (max 0 (dec n)))
      kf-mod)))

(defn- graft-live-item
  "Copy live fields from the current version of an item onto the restored one."
  [restored current]
  (let [item (reduce (fn [m k]
                       (if (contains? current k) (assoc m k (get current k)) (dissoc m k)))
                     restored
                     live-item-keys)
        r-kf (:keyframe-modulator item)
        c-kf (:keyframe-modulator current)]
    (if (map? r-kf)
      (assoc item :keyframe-modulator
             (clamp-selected-keyframe
              ;; The current selection only means the same keyframe if the
              ;; keyframes themselves are unchanged
              (if (and (map? c-kf)
                       (contains? c-kf :selected-keyframe)
                       (= (:keyframes c-kf) (:keyframes r-kf)))
                (assoc r-kf :selected-keyframe (:selected-keyframe c-kf))
                r-kf)))
      item)))

(defn- graft-live-entry
  "Copy live fields from the current entry onto the restored entry."
  [current-entry restored-entry]
  (if (or (nil? current-entry) (identical? current-entry restored-entry) (not (map? restored-entry)))
    restored-entry
    (let [index (live-item-index current-entry)]
      (if (seq index)
        (walk/postwalk (fn [x]
                         (if-let [current (and (chain-item? x) (get index (:id x)))]
                           (graft-live-item x current)
                           x))
                       restored-entry)
        restored-entry))))

(defn- item-id-set [entry]
  (not-empty (set (keep :id (:items entry)))))

(defn- restored-effect-active
  "On/off state for a restored effect cell: the current cell's state; for a cell
   that doesn't exist now, the state of the current cell holding the same items
   (it was moved), otherwise off."
  [current-cells k restored-entry]
  (if-let [current (get current-cells k)]
    (boolean (:active? current))
    (let [ids (item-id-set restored-entry)
          moved (when ids (some (fn [[_ e]] (when (= ids (item-id-set e)) e)) current-cells))]
      (boolean (:active? moved)))))

(defn- graft-live-chains
  [current-chains restored-chains]
  (reduce-kv
   (fn [acc domain entries]
     (let [current-cells (get current-chains domain)]
       (assoc acc domain
              (reduce-kv (fn [m k entry]
                           (let [grafted (graft-live-entry (get current-cells k) entry)]
                             (assoc m k (if (and (= domain :effect-chains) (map? grafted))
                                          (assoc grafted :active? (restored-effect-active current-cells k entry))
                                          grafted))))
                         entries
                         entries))))
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


;; Backfill
;;
;; Some events add document content that isn't a user edit (network scan
;; creating projectors, filling in timeline fields). They return a pure
;; (fn [document] document) as :history/backfill. Applying it to every stored
;; snapshot and to the saved baseline means undo/redo never remove that content
;; and it never counts as an unsaved change.


(defn- backfill-snapshot [snapshot f]
  (if snapshot (update snapshot :document-state f) snapshot))

(defn backfill-history
  "Apply f to the document of every undo/redo snapshot and the open transaction baseline."
  [history-state f]
  (cond-> history-state
    (:undo-stack history-state) (update :undo-stack #(mapv (fn [s] (backfill-snapshot s f)) %))
    (:redo-stack history-state) (update :redo-stack #(mapv (fn [s] (backfill-snapshot s f)) %))
    (get-in history-state [:transaction :baseline-snapshot])
    (update-in [:transaction :baseline-snapshot] backfill-snapshot f)))

(defn backfill-baseline
  "Apply f to the saved baseline document, if there is one."
  [state f]
  (if (get-in state [:project :saved-document])
    (update-in state [:project :saved-document] f)
    state))

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
   
   Any mouse-up commits whatever transaction is open: if the mouse-up of the
   control that opened it was missed, the next one closes it."
  ([history-state current-state]
   (commit-transaction history-state current-state default-max-depth))
  ([history-state current-state max-depth]
   (if-let [tx (:transaction history-state)]
     (let [baseline (:baseline-snapshot tx)
           changed? (undoable-changed? (:document-state baseline)
                                       (extract-document-state current-state))
           history-without-tx (assoc history-state :transaction nil)]
       (if changed?
         (push-undo-snapshot history-without-tx baseline max-depth)
         history-without-tx))
     history-state)))

(defn begin-transaction
  "Begin a continuous interaction transaction on mouse-down.
   Captures a baseline document snapshot of the current state.
   If a transaction from the same source is already open it is kept; one from
   a different source (e.g. its mouse-up was missed) is committed first."
  [history-state current-state {:keys [label source-id ui-context now]}]
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
                                      :start-time (or now (System/currentTimeMillis))))))))

(defn stale-transaction?
  "True when an open transaction has had no activity (see touch-transaction)
   for more than stale-transaction-ms before now."
  [history-state now]
  (when-let [tx (:transaction history-state)]
    (when-let [last-active (or (:last-activity tx) (:start-time tx))]
      (> (- now last-active) stale-transaction-ms))))

(defn touch-transaction
  "Record activity on the open transaction (a drag tick), so a long drag
   isn't treated as stale."
  [history-state now]
  (if (:transaction history-state)
    (assoc-in history-state [:transaction :last-activity] now)
    history-state))

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

