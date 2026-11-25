(ns laser-show.state.clipboard
  "The clipboard's rules, all pure (keyboard plan Phase 4, Steps 4a and 4b).

   A clip is a map with a :type (clipboard-types), as copy builds it:
     {:type :cue-chain-items :items [...] :copied-at ms}   a cue list's presets and groups
     {:type :item-effects :effects [...] :copied-at ms}    an effect list's effects
     {:type :cue-chain :data cell :copied-at ms}           a grid cell
     {:type :effects-cell :data cell :copied-at ms}        an effects cell
     {:type :step-grid :grid {:zones :steps} :copied-at ms} a step grid

   A list's type comes from its component id (list-type), and each target
   takes only what payload says. A copied cell that holds a timeline
   (timeline-clip?) pastes onto no cell: timelines live in the Shows tab
   (timeline-shows plan TS-3). Cue items pasted into a list lose their
   timeline timing, so they never make a plain cue a timeline.

   The clipboard is [:ui :clipboard] in app state, written only in the
   :state a handler returns: copy puts its clip there, and a paste whose
   read of the OS clipboard found a clip while the slot was empty (adopt).
   Only two effects in events/core.clj touch the OS clipboard, on the FX
   thread: :clipboard/write puts a clip's text there (->system-text), and
   :clipboard/read reads it back (<-system-text) for a paste made while
   nothing is copied in this app (events.helpers/with-clipboard)."
  (:require [clojure.string :as str]
            [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.state.serialization :as ser]
            [laser-show.animation.modulation :as mod]))


;; Clipboard Types


(def clipboard-types
  "The types copy writes; a clip of any other type is read as nothing."
  #{:cue-chain-items :item-effects :cue-chain :effects-cell :step-grid})

(defn valid-clipboard-type?
  [type]
  (contains? clipboard-types type))


;; Effects as copy keeps them


(defn- serialize-effect-params
  "Convert effect params to pure data configs for serialization.
   Params should already be config maps - this just ensures they're serializable."
  [params]
  (when params
    (into {}
          (map (fn [[k v]]
                 (cond
                   ;; Modulator config map - pass through as-is
                   (mod/modulator-config? v)
                   [k v]

                   ;; Any function - use default (shouldn't happen with config-only approach)
                   (fn? v)
                   [k 1.0]

                   ;; Atom or other derefable - deref it
                   (instance? clojure.lang.IDeref v)
                   [k @v]

                   ;; Regular serializable value
                   :else
                   [k v]))
               params))))

(defn- serialize-effect-chain
  "Serialize an effect chain for clipboard.
   Each effect in the chain has its params serialized, inside groups too; a
   map without :params (a group) gets none."
  [effects]
  (when effects
    (mapv (fn [effect]
            (cond-> effect
              (contains? effect :params) (update :params serialize-effect-params)
              (vector? (:items effect)) (update :items serialize-effect-chain)))
          effects)))


;; Clips: what copy puts in [:ui :clipboard], its text on the OS
;; clipboard, and which target takes which clip


(def max-system-text-length
  "The longest OS clipboard text read as a clip. A 300-clip timeline cell is
   about 197,000 characters (keyboard plan probe p17)."
  5000000)

(defn list-type
  "The clipboard type of the list with component-id (04 §7, Step 4b): a cue
   list copies and pastes cue items (:cue-chain-items); an item's effects,
   the effects grid's chain and a projector's chain copy and paste effects
   (:item-effects). nil for any other list: it copies and pastes nothing."
  [component-id]
  (when (vector? component-id)
    (case (first component-id)
      :cue-chain :cue-chain-items
      (:item-effects :effect-chain :projector-effects) :item-effects
      nil)))

(defn list-clip
  "The clip a list copy of items makes at now (ms): effects for
   :item-effects, cue items for :cue-chain-items (list-type). nil when there
   are no items, or for any other type."
  [type items now]
  (when (seq items)
    (case type
      :item-effects {:type :item-effects :effects (serialize-effect-chain items) :copied-at now}
      :cue-chain-items {:type :cue-chain-items :items (vec items) :copied-at now}
      nil)))

(defn empty-cell?
  "Is cell (a cue or effects cell) empty: nil, or no :items and no :tracks?
   An empty cell copies nothing, so a paste never empties a cell."
  [cell]
  (and (empty? (:items cell)) (empty? (:tracks cell))))

(defn cell-clip
  "The clip a grid or effects cell's copy makes: {:type :data :copied-at};
   nil for an empty cell (empty-cell?)."
  [type cell now]
  (when-not (empty-cell? cell)
    {:type type :data cell :copied-at now}))

(defn grid-clip
  "The clip a step grid's Copy makes: grid is routing.step-grid/copy's
   {:zones :steps}."
  [grid now]
  {:type :step-grid :grid grid :copied-at now})

(defn ->system-text
  "clip as the OS clipboard's text: one line of EDN."
  [clip]
  (ser/serialize clip))

(defn <-system-text
  "The clip an OS clipboard text holds, or nil: text from another app, EDN
   that isn't a map with a known :type, or a text longer than
   max-system-text-length (not read at all)."
  [text]
  (when (and (string? text)
             (<= (count text) max-system-text-length)
             (str/starts-with? (str/triml text) "{"))
    (let [data (try (ser/deserialize text :silent? true)
                    (catch StackOverflowError _ nil))]
      (when (and (map? data) (valid-clipboard-type? (:type data)))
        data))))

(def ^:private list-targets #{:cue-chain-items :item-effects})

(defn timeline-clip?
  "Is clip a copied grid cell that holds a timeline (cue-chains/timeline-cue?,
   inferred ones too)? The grid never holds one (timeline-shows plan TS-3), so
   no cell takes it: it goes to the Shows tab, with New Show from Cue."
  [clip]
  (and (= :cue-chain (:type clip))
       (cue-chains/timeline-cue? (:data clip))))

(defn- untimed
  "items without the keys that place an item on a timeline (:timeline/start,
   :timeline/duration, :track-id), so pasted into a plain cue they can't make
   it an inferred timeline. Only the top-level items: a group's own items
   never carry them."
  [items]
  (mapv #(dissoc % :timeline/start :timeline/duration :track-id) items))

(defn payload
  "What target takes from clip, or nil (04 §7, second table). A list
   (list-type) takes the items of a clip of its own type: a cue list
   (:cue-chain-items) cue items, untimed (no :timeline/start,
   :timeline/duration or :track-id), an effect list (:item-effects) effects.
   With D9 A's two conversions, a cue list also takes a copied grid cell's
   items (not a timeline cue's clips), and an effect list a copied effects
   cell's effects. A cue or effects cell (:cue-chain, :effects-cell) takes a
   cell of its type, but a cue cell never a timeline (timeline-clip?); a
   step grid (:step-grid) a grid."
  [clip target]
  (cond
    (= :cue-chain-items target) (case (:type clip)
                                  :cue-chain-items (some-> (:items clip) untimed)
                                  :cue-chain (let [cell (:data clip)]
                                               (when-not (cue-chains/timeline-cue? cell) (:items cell)))
                                  nil)
    (= :item-effects target) (case (:type clip)
                               :item-effects (:effects clip)
                               :effects-cell (:items (:data clip))
                               nil)
    (contains? #{:cue-chain :effects-cell} target) (when (and (= target (:type clip))
                                                              (not (empty-cell? (:data clip)))
                                                              (not (timeline-clip? clip)))
                                                     (:data clip))
    (= :step-grid target) (when (= :step-grid (:type clip)) (:grid clip))
    :else nil))

(defn accepts?
  "Does target take clip? A list when it has something to paste; a cell or
   a step grid when the clip is of its type, a cue cell never a timeline
   (timeline-clip?)."
  [clip target]
  (boolean
   (if (contains? list-targets target)
     (seq (payload clip target))
     (and (some? clip) (some? target) (= target (:type clip))
          (if (contains? #{:cue-chain :effects-cell} target)
            (and (not (empty-cell? (:data clip)))
                 (not (timeline-clip? clip)))
            true)))))

(defn adopt
  "state with clip, read from the OS clipboard, as [:ui :clipboard], but
   only while the slot is still empty: a copy made meanwhile wins."
  [state clip]
  (if (and (some? clip) (nil? (get-in state [:ui :clipboard])))
    (assoc-in state [:ui :clipboard] clip)
    state))
