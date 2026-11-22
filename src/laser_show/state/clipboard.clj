(ns laser-show.state.clipboard
  "The clipboard's rules, all pure (keyboard plan Phase 4, Step 4a).

   A clip is a map with a :type (clipboard-types), as copy builds it:
     {:type :cue-chain-items :items [...] :copied-at ms}   a list's presets and groups
     {:type :item-effects :effects [...] :copied-at ms}    a list's effects
     {:type :cue-chain :data cell :copied-at ms}           a grid cell
     {:type :effects-cell :data cell :copied-at ms}        an effects cell
     {:type :step-grid :grid {:zones :steps} :copied-at ms} a step grid

   The clipboard is [:ui :clipboard] in app state, written only in the
   :state a handler returns: copy puts its clip there, and a paste whose
   read of the OS clipboard found a clip while the slot was empty (adopt).
   Only two effects in events/core.clj touch the OS clipboard, on the FX
   thread: :clipboard/write puts a clip's text there (->system-text), and
   :clipboard/read reads it back (<-system-text) for a paste made while
   nothing is copied in this app (events.helpers/with-clipboard)."
  (:require [clojure.string :as str]
            [laser-show.state.serialization :as ser]
            [laser-show.animation.modulation :as mod]))


;; Clipboard Types


(def clipboard-types
  #{:cue :zone :projector :effect :effect-chain :cell-assignment
    :cue-chain :effects-cell :cue-chain-items :item-effects :timeline-tracks
    :step-grid})

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
   Each effect in the chain has its params serialized."
  [effects]
  (when effects
    (mapv (fn [effect]
            (update effect :params serialize-effect-params))
          effects)))


;; Clips: what copy puts in [:ui :clipboard], its text on the OS
;; clipboard, and which target takes which clip


(def max-system-text-length
  "The longest OS clipboard text read as a clip. A 300-clip timeline cell is
   about 197,000 characters (keyboard plan probe p17)."
  5000000)

(defn list-clip
  "The clip a list copy of items makes at now (ms): effects for
   :item-effects, tracks for :timeline-tracks, cue items otherwise. nil
   when there are no items."
  [type items now]
  (when (seq items)
    (case type
      :item-effects {:type :item-effects :effects (serialize-effect-chain items) :copied-at now}
      :timeline-tracks {:type :timeline-tracks :items (vec items) :copied-at now}
      {:type :cue-chain-items :items (vec items) :copied-at now})))

(defn cell-clip
  "The clip a grid or effects cell's copy makes: {:type :data :copied-at}."
  [type cell now]
  {:type type :data cell :copied-at now})

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

(def ^:private list-targets #{:cue-chain-items :item-effects :timeline-tracks})

(defn payload
  "What target takes from clip, or nil. A list of effects (:item-effects)
   takes a clip of effects, an effect chain or one effect; any other list
   the items of a clip of its own type; a cue or effects cell (:cue-chain,
   :effects-cell) a cell of its type; a step grid (:step-grid) a grid."
  [clip target]
  (cond
    (= :item-effects target) (case (:type clip)
                               :effect [(dissoc clip :type :copied-at)]
                               (:effect-chain :item-effects) (:effects clip)
                               nil)
    (contains? list-targets target) (when (= target (:type clip)) (:items clip))
    (contains? #{:cue-chain :effects-cell} target) (when (= target (:type clip)) (:data clip))
    (= :step-grid target) (when (= :step-grid (:type clip)) (:grid clip))
    :else nil))

(defn accepts?
  "Does target take clip? A list when it has something to paste; a cell or
   a step grid when the clip is of its type."
  [clip target]
  (boolean
   (if (contains? list-targets target)
     (seq (payload clip target))
     (and (some? clip) (= target (:type clip))))))

(defn adopt
  "state with clip, read from the OS clipboard, as [:ui :clipboard], but
   only while the slot is still empty: a copy made meanwhile wins."
  [state clip]
  (if (and (some? clip) (nil? (get-in state [:ui :clipboard])))
    (assoc-in state [:ui :clipboard] clip)
    state))
