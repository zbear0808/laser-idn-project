(ns laser-show.state.clipboard
  "Clipboard state management for copy/paste operations.
   Supports copying cues, zones, projectors, and effects (future).
   Uses both the centralized dynamic state and the system clipboard for EDN data.
   
   NOTE: Clipboard state is stored in database/dynamic.clj at [:ui :clipboard]
   to maintain a single source of truth for all application state."
  (:require [laser-show.backend.cues :as cues]
            [laser-show.backend.zones :as zones]
            [laser-show.backend.projectors :as projectors]
            [laser-show.state.dynamic :as dyn]
            [laser-show.state.serialization :as ser])
  (:import [java.awt Toolkit]
           [java.awt.datatransfer StringSelection DataFlavor Clipboard]))

;; ============================================================================
;; Clipboard State - uses database/dynamic.clj !ui atom
;; ============================================================================

;; No local atom - state is in dyn/!ui at [:clipboard]

(defn- get-internal-clipboard
  "Get the internal clipboard state from central store."
  []
  (dyn/get-clipboard))

(defn- set-internal-clipboard!
  "Set the internal clipboard state in central store."
  [data]
  (dyn/set-clipboard! data))

;; ============================================================================
;; Clipboard Types
;; ============================================================================

(def clipboard-types
  #{:cue :zone :projector :effect :cell-assignment :effect-assignment})

(defn valid-clipboard-type?
  [type]
  (contains? clipboard-types type))

;; ============================================================================
;; System Clipboard Operations
;; ============================================================================

(defn- get-system-clipboard
  "Get the system clipboard."
  ^Clipboard []
  (.getSystemClipboard (Toolkit/getDefaultToolkit)))

(defn- copy-to-system-clipboard!
  "Copy EDN data to the system clipboard as a string."
  [data]
  (try
    (let [clipboard (get-system-clipboard)
          edn-str (ser/serialize-for-clipboard data)
          selection (StringSelection. edn-str)]
      (.setContents clipboard selection nil)
      true)
    (catch Exception e
      (println "Failed to copy to system clipboard:" (.getMessage e))
      false)))

(defn- read-from-system-clipboard
  "Read string content from the system clipboard and try to parse as EDN.
   Only returns data if it's a valid clipboard data structure (has :type key)."
  []
  (try
    (let [clipboard (get-system-clipboard)]
      (when (.isDataFlavorAvailable clipboard DataFlavor/stringFlavor)
        (let [content (.getData clipboard DataFlavor/stringFlavor)]
          (when (string? content)
            (ser/deserialize-from-clipboard content
              :schema-fn (fn [data]
                          (and (map? data)
                               (contains? data :type)
                               (valid-clipboard-type? (:type data)))))))))
    (catch Exception e
      (println "Failed to read from system clipboard:" (.getMessage e))
      nil)))

;; ============================================================================
;; Generic Clipboard Operations
;; ============================================================================

(defn get-clipboard
  "Get the current clipboard contents.
   First tries to read from system clipboard, falls back to internal state."
  []
  (or (read-from-system-clipboard)
      (get-internal-clipboard)))

(defn clear-clipboard!
  "Clear the clipboard (both internal state and system clipboard)."
  []
  (set-internal-clipboard! nil)
  (try
    (let [clipboard (get-system-clipboard)
          selection (StringSelection. "")]
      (.setContents clipboard selection nil))
    (catch Exception _
      nil)))

(defn clipboard-has-type?
  "Check if clipboard contains data of a specific type."
  [type]
  (let [clip (get-clipboard)]
    (= type (:type clip))))

(defn clipboard-empty?
  "Check if clipboard is empty."
  []
  (nil? (get-clipboard)))

;; ============================================================================
;; Cue Copy/Paste
;; ============================================================================

(defn copy-cue!
  "Copy a cue to the clipboard by ID.
   Stores the full cue data so it persists even if original is deleted.
   Also copies to system clipboard as EDN."
  [cue-id]
  (when-let [cue (cues/get-cue cue-id)]
    (let [clip-data {:type :cue
                     :data cue
                     :copied-at (System/currentTimeMillis)}]
      (set-internal-clipboard! clip-data)
      (copy-to-system-clipboard! clip-data)
      true)))

(defn paste-cue!
  "Paste a cue from clipboard, creating a new cue with a unique ID.
   Returns the new cue, or nil if clipboard doesn't contain a cue."
  []
  (when (clipboard-has-type? :cue)
    (let [{:keys [data]} (get-internal-clipboard)
          new-id (keyword (str "cue-" (System/currentTimeMillis)))
          new-cue (assoc data 
                         :id new-id 
                         :name (str (:name data) " (copy)")
                         :created-at (System/currentTimeMillis))]
      (cues/add-cue! new-cue)
      new-cue)))

(defn can-paste-cue?
  "Check if clipboard contains a cue that can be pasted."
  []
  (clipboard-has-type? :cue))

;; ============================================================================
;; Cell Assignment Copy/Paste
;; ============================================================================

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

;; ============================================================================
;; Zone Copy/Paste (for future use)
;; ============================================================================

(defn copy-zone!
  "Copy a zone to the clipboard by ID."
  [zone-id]
  (when-let [zone (zones/get-zone zone-id)]
    (let [clip-data {:type :zone
                     :data zone
                     :copied-at (System/currentTimeMillis)}]
      (set-internal-clipboard! clip-data)
      (copy-to-system-clipboard! clip-data)
      true)))

(defn paste-zone!
  "Paste a zone from clipboard, creating a new zone with a unique ID.
   Returns the new zone, or nil if clipboard doesn't contain a zone."
  []
  (when (clipboard-has-type? :zone)
    (let [{:keys [data]} (get-internal-clipboard)
          new-id (keyword (str "zone-" (System/currentTimeMillis)))
          new-zone (assoc data 
                          :id new-id 
                          :name (str (:name data) " (copy)")
                          :created-at (System/currentTimeMillis))]
      (zones/create-zone! new-zone)
      new-zone)))

(defn can-paste-zone?
  "Check if clipboard contains a zone that can be pasted."
  []
  (clipboard-has-type? :zone))

;; ============================================================================
;; Projector Copy/Paste (for future use)
;; ============================================================================

(defn copy-projector!
  "Copy a projector to the clipboard by ID."
  [projector-id]
  (when-let [projector (projectors/get-projector projector-id)]
    (let [clip-data {:type :projector
                     :data projector
                     :copied-at (System/currentTimeMillis)}]
      (set-internal-clipboard! clip-data)
      (copy-to-system-clipboard! clip-data)
      true)))

(defn paste-projector!
  "Paste a projector from clipboard, creating a new projector with a unique ID.
   Returns the new projector, or nil if clipboard doesn't contain a projector."
  []
  (when (clipboard-has-type? :projector)
    (let [{:keys [data]} (get-internal-clipboard)
          new-id (keyword (str "projector-" (System/currentTimeMillis)))
          new-projector (assoc data 
                               :id new-id 
                               :name (str (:name data) " (copy)")
                               :created-at (System/currentTimeMillis))]
      (projectors/register-projector! new-projector)
      new-projector)))

(defn can-paste-projector?
  "Check if clipboard contains a projector that can be pasted."
  []
  (clipboard-has-type? :projector))

;; ============================================================================
;; Effect Assignment Copy/Paste (for effects grid)
;; ============================================================================

(defn copy-effect-assignment!
  "Copy an effect instance to clipboard.
   Stores the full effect configuration so it persists.
   Also copies to system clipboard as EDN."
  [effect-instance]
  (when effect-instance
    (let [clip-data {:type :effect-assignment
                     :effect-id (:effect-id effect-instance)
                     :enabled (:enabled effect-instance)
                     :params (:params effect-instance)
                     :copied-at (System/currentTimeMillis)}]
      (set-internal-clipboard! clip-data)
      (copy-to-system-clipboard! clip-data)
      true)))

(defn paste-effect-assignment
  "Get the effect instance from clipboard for pasting into a cell.
   Returns an effect instance map, or nil if clipboard doesn't contain one.
   Reads from system clipboard first, falls back to internal atom."
  []
  (when (clipboard-has-type? :effect-assignment)
    (let [clip (get-clipboard)]
      {:effect-id (:effect-id clip)
       :enabled (:enabled clip true)
       :params (:params clip {})})))

(defn can-paste-effect-assignment?
  "Check if clipboard contains an effect assignment that can be pasted."
  []
  (clipboard-has-type? :effect-assignment))

;; ============================================================================
;; Clipboard Info
;; ============================================================================

(defn get-clipboard-description
  "Get a human-readable description of clipboard contents."
  []
  (if-let [{:keys [type data preset-id effect-id]} (get-internal-clipboard)]
    (case type
      :cue (str "Cue: " (:name data))
      :cell-assignment (str "Preset: " (name preset-id))
      :zone (str "Zone: " (:name data))
      :projector (str "Projector: " (:name data))
      :effect (str "Effect: " (:name data))
      :effect-assignment (str "Effect: " (name effect-id))
      "Unknown")
    "Empty"))
