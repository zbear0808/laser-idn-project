(ns laser-show.state.clipboard
  "Clipboard state management for copy/paste operations.
   Supports copying cues, zones, projectors, and effects (future).
   Uses both the centralized state and the system clipboard for EDN data.
   
   NOTE: Clipboard state is stored in state.core at [:ui :clipboard]
   to maintain a single source of truth for all application state.
   
   THREADING: System clipboard operations MUST run on the JavaFX thread.
   All functions that access JavaFX Clipboard use fx/on-fx-thread internally."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [laser-show.state.core :as state]
            [laser-show.state.queries :as queries]
            [laser-show.state.serialization :as ser]
            [laser-show.animation.modulation :as mod])
  (:import [javafx.scene.input Clipboard ClipboardContent]))


;; Clipboard State - uses database/dynamic.clj !ui atom


;; No local atom - state is in state/!ui at [:clipboard]

(defn- get-internal-clipboard
  "Get the internal clipboard state from central store."
  []
  (queries/clipboard))

(defn- set-internal-clipboard!
  "Set the internal clipboard state in central store."
  [data]
  (state/assoc-in-state! [:ui :clipboard] data))


;; Clipboard Types


(def clipboard-types
  #{:cue :zone :projector :effect :effect-chain :cell-assignment
    :cue-chain :effects-cell :cue-chain-items :item-effects})

(defn valid-clipboard-type?
  [type]
  (contains? clipboard-types type))


;; System Clipboard Operations


(defn- get-system-clipboard
  "Get the system clipboard."
  []
  (Clipboard/getSystemClipboard))

(defn- copy-to-system-clipboard!
  "Copy EDN data to the system clipboard as a string.
   MUST be called from any thread - internally uses fx/on-fx-thread."
  [data]
  (let [edn-str (ser/serialize-for-clipboard data)]
    ;; Use fx/on-fx-thread to ensure we're on the JavaFX thread
    ;; This blocks until the operation completes
    @(fx/on-fx-thread
       (try
         (let [clipboard (get-system-clipboard)
               content (doto (ClipboardContent.)
                         (.putString edn-str))]
           (.setContent clipboard content)
           true)
         (catch Exception e
           (log/warn "Failed to copy to system clipboard:" (.getMessage e))
           false)))))

(defn- read-from-system-clipboard
  "Read string content from the system clipboard and try to parse as EDN.
   Only returns data if it's a valid clipboard data structure (has :type key).
   Silently returns nil for non-EDN content (like text from other apps).
   MUST be called from any thread - internally uses fx/on-fx-thread."
  []
  ;; Use fx/on-fx-thread to ensure we're on the JavaFX thread
  @(fx/on-fx-thread
     (try
       (let [clipboard (get-system-clipboard)]
         (when (.hasString clipboard)
           (let [content (.getString clipboard)]
             (when (and (string? content)
                        ;; Quick check - must start with { to be valid EDN map
                        (str/starts-with? (str/trim content) "{"))
               ;; Use silent mode to avoid logging errors for non-EDN clipboard content
               (let [data (ser/deserialize content :silent? true)]
                 (when (and (map? data)
                            (contains? data :type)
                            (valid-clipboard-type? (:type data)))
                   data))))))
       (catch Exception _
         ;; Silently return nil - system clipboard often contains non-parseable content
         nil))))


;; Generic Clipboard Operations


(defn get-clipboard
  "Get the current clipboard contents.
   First tries to read from system clipboard, falls back to internal state."
  []
  (or (read-from-system-clipboard)
      (get-internal-clipboard)))

(defn clear-clipboard!
  "Clear the clipboard (both internal state and system clipboard).
   MUST be called from any thread - internally uses fx/on-fx-thread."
  []
  (set-internal-clipboard! nil)
  @(fx/on-fx-thread
     (try
       (let [clipboard (get-system-clipboard)
             content (doto (ClipboardContent.)
                       (.putString ""))]
         (.setContent clipboard content))
       (catch Exception _
         nil))))

(defn clipboard-has-type?
  "Check if clipboard contains data of a specific type."
  [type]
  (let [clip (get-clipboard)]
    (= type (:type clip))))

(defn clipboard-empty?
  "Check if clipboard is empty."
  []
  (nil? (get-clipboard)))


;; Cell Assignment Copy/Paste


(defn copy-cell-assignment!
  "Copy a cell's preset assignment to clipboard.
   This is simpler than copying a full cue - just copies the preset-id.
   Also copies to system clipboard as EDN."
  [preset-id]
  (when preset-id
    (let [clip-data {:type :cell-assignment
                     :preset-id preset-id
                     :copied-at (System/currentTimeMillis)}]
      (set-internal-clipboard! clip-data)
      (copy-to-system-clipboard! clip-data)
      true)))

(defn paste-cell-assignment
  "Get the preset-id from clipboard for pasting into a cell.
   Returns the preset-id, or nil if clipboard doesn't contain a cell assignment.
   Reads from system clipboard first, falls back to internal atom."
  []
  (when (clipboard-has-type? :cell-assignment)
    (let [clip (get-clipboard)]
      (:preset-id clip))))

(defn can-paste-cell-assignment?
  "Check if clipboard contains a cell assignment that can be pasted."
  []
  (clipboard-has-type? :cell-assignment))


;; Effect Chain Copy/Paste (for effects grid)

;; 
;; Effect cells use chain format:
;; {:type :effect-chain :effects [{:effect-id :scale :params {...}} ...] :active true}

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

(defn copy-effect-chain!
  "Copy an effect chain to clipboard.
   Stores the full effect chain so it persists.
   Also copies to system clipboard as EDN.
   
   Expects cell-data in format: {:effects [{...}] :active true}"
  [cell-data]
  (when (and cell-data (seq (:effects cell-data)))
    (let [clip-data {:type :effect-chain
                     :effects (serialize-effect-chain (:effects cell-data))
                     :active (:active cell-data true)
                     :copied-at (System/currentTimeMillis)}]
      (set-internal-clipboard! clip-data)
      (copy-to-system-clipboard! clip-data)
      true)))



;; Smart Effect Paste - handles both single effects and chains


(defn can-paste-effects?
  "Check if clipboard contains effects (single or chain) that can be pasted."
  []
  (or (clipboard-has-type? :effect)
      (clipboard-has-type? :effect-chain)))

(defn get-effects-to-paste
  "Get effects from clipboard as a vector.
   Works for both :effect (returns [effect]) and :effect-chain (returns effects vec).
   Returns nil if clipboard doesn't contain pasteable effects."
  []
  (let [clip (get-clipboard)]
    (case (:type clip)
      :effect [(dissoc clip :type :copied-at)]
      :effect-chain (:effects clip [])
      nil)))


;; Cue Chain Copy/Paste (for grid cells)


(defn copy-cue-chain!
  "Copy a cue chain to clipboard (from grid cells).
   Also copies to system clipboard as EDN.
   
   Expects cue-chain-data in format: {:items [...] ...}"
  [cue-chain-data]
  (when cue-chain-data
    (let [clip-data {:type :cue-chain
                     :data cue-chain-data
                     :copied-at (System/currentTimeMillis)}]
      (set-internal-clipboard! clip-data)
      (copy-to-system-clipboard! clip-data)
      true)))

(defn can-paste-cue-chain?
  "Check if clipboard contains a cue chain that can be pasted."
  []
  (clipboard-has-type? :cue-chain))

(defn get-cue-chain-to-paste
  "Get cue chain data from clipboard for pasting.
   Returns the cue-chain data, or nil if clipboard doesn't contain a cue chain."
  []
  (when (clipboard-has-type? :cue-chain)
    (:data (get-clipboard))))


;; Effects Cell Copy/Paste (for effects grid cells)


(defn copy-effects-cell!
  "Copy an effects cell to clipboard (from effects grid).
   Also copies to system clipboard as EDN.
   
   Expects cell-data in format: {:items [...] :active true}"
  [cell-data]
  (when cell-data
    (let [clip-data {:type :effects-cell
                     :data cell-data
                     :copied-at (System/currentTimeMillis)}]
      (set-internal-clipboard! clip-data)
      (copy-to-system-clipboard! clip-data)
      true)))

(defn can-paste-effects-cell?
  "Check if clipboard contains an effects cell that can be pasted."
  []
  (clipboard-has-type? :effects-cell))

(defn get-effects-cell-to-paste
  "Get effects cell data from clipboard for pasting.
   Returns the cell data, or nil if clipboard doesn't contain an effects cell."
  []
  (when (clipboard-has-type? :effects-cell)
    (:data (get-clipboard))))


;; Cue Chain Items Copy/Paste (for cue chain editor items)


(defn copy-cue-chain-items!
  "Copy cue chain items (presets/groups) to clipboard.
   Also copies to system clipboard as EDN.
   
   Expects items vector: [{:preset-id :circle ...} ...]"
  [items]
  (when (seq items)
    (let [clip-data {:type :cue-chain-items
                     :items items
                     :copied-at (System/currentTimeMillis)}]
      (set-internal-clipboard! clip-data)
      (copy-to-system-clipboard! clip-data)
      true)))

(defn can-paste-cue-chain-items?
  "Check if clipboard contains cue chain items that can be pasted."
  []
  (clipboard-has-type? :cue-chain-items))

(defn get-cue-chain-items-to-paste
  "Get cue chain items from clipboard for pasting.
   Returns the items vector, or nil if clipboard doesn't contain cue chain items."
  []
  (when (clipboard-has-type? :cue-chain-items)
    (:items (get-clipboard))))


;; Item Effects Copy/Paste (for effects within cue chain items)


(defn copy-item-effects!
  "Copy item effects to clipboard (effects attached to cue chain items).
   Also copies to system clipboard as EDN.
   
   Expects effects vector: [{:effect-id :scale ...} ...]"
  [effects]
  (when (seq effects)
    (let [clip-data {:type :item-effects
                     :effects (serialize-effect-chain effects)
                     :copied-at (System/currentTimeMillis)}]
      (set-internal-clipboard! clip-data)
      (copy-to-system-clipboard! clip-data)
      true)))

(defn can-paste-item-effects?
  "Check if clipboard contains item effects that can be pasted."
  []
  (clipboard-has-type? :item-effects))

(defn get-item-effects-to-paste
  "Get item effects from clipboard for pasting.
   Returns the effects vector, or nil if clipboard doesn't contain item effects."
  []
  (when (clipboard-has-type? :item-effects)
    (:effects (get-clipboard))))


;; Clipboard Info


(defn get-clipboard-description
  "Get a human-readable description of clipboard contents."
  []
  (if-let [{:keys [type data preset-id effects effect-id items] :as clip} (get-internal-clipboard)]
    (case type
      :cue (str "Cue: " (:name data))
      :cell-assignment (str "Preset: " (name preset-id))
      :zone (str "Zone: " (:name data))
      :projector (str "Projector: " (:name data))
      :effect (str "Effect: " (name effect-id))
      :effect-chain (if (seq effects)
                      (let [first-effect (first effects)
                            effect-count (count effects)]
                        (str "Effects: " (name (:effect-id first-effect))
                             (when (> effect-count 1) (str " +" (dec effect-count) " more"))))
                      "Effects: (empty)")
      :cue-chain (str "Cue Chain: " (count (:items data [])) " items")
      :effects-cell (str "Effects Cell: " (count (:items data [])) " effects")
      :cue-chain-items (str "Cue Chain Items: " (count items) " items")
      :item-effects (str "Item Effects: " (count effects) " effects")
      "Unknown")
    "Empty"))
