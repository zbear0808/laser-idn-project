(ns laser-show.backend.cues
  "Cue storage and management for the laser show application.
   Handles cue triggering, sequencing, and persistence."
  (:require [laser-show.backend.config :as config]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; ============================================================================
;; Cue File Paths
;; ============================================================================

(def cues-file "config/cues.edn")
(def cue-lists-file "config/cue-lists.edn")

;; ============================================================================
;; Cue State
;; ============================================================================

(defonce !cues
  (atom {}))

(defonce !cue-lists
  (atom {}))

(defonce !active-cue
  (atom nil))

(defonce !cue-queue
  (atom []))

;; ============================================================================
;; Cue Definition
;; ============================================================================

(defn make-cue
  "Create a cue definition.
   - id: unique identifier for the cue
   - name: display name
   - preset-id: the animation preset to play
   - params: optional parameter overrides
   - duration: optional duration in ms (nil for infinite)"
  ([id name preset-id]
   (make-cue id name preset-id {} nil))
  ([id name preset-id params]
   (make-cue id name preset-id params nil))
  ([id name preset-id params duration]
   {:id id
    :name name
    :preset-id preset-id
    :params params
    :duration duration
    :created-at (System/currentTimeMillis)}))

;; ============================================================================
;; Cue CRUD Operations
;; ============================================================================

(defn add-cue!
  "Add a cue to the cue library."
  [cue]
  (swap! !cues assoc (:id cue) cue))

(defn get-cue
  "Get a cue by ID."
  [cue-id]
  (get @!cues cue-id))

(defn update-cue!
  "Update a cue's properties."
  [cue-id updates]
  (swap! !cues update cue-id merge updates))

(defn remove-cue!
  "Remove a cue from the library."
  [cue-id]
  (swap! !cues dissoc cue-id))

(defn list-cues
  "Get all cues as a sequence."
  []
  (vals @!cues))

;; ============================================================================
;; Cue Triggering
;; ============================================================================

(defn trigger-cue!
  "Trigger a cue to start playing.
   Returns the cue if found, nil otherwise."
  [cue-id]
  (when-let [cue (get-cue cue-id)]
    (reset! !active-cue cue)
    cue))

(defn stop-cue!
  "Stop the currently playing cue."
  []
  (reset! !active-cue nil))

(defn get-active-cue
  "Get the currently active cue."
  []
  @!active-cue)

(defn cue-active?
  "Check if a specific cue is currently active."
  [cue-id]
  (= cue-id (:id @!active-cue)))

;; ============================================================================
;; Cue Queue
;; ============================================================================

(defn queue-cue!
  "Add a cue to the queue."
  [cue-id]
  (when (get-cue cue-id)
    (swap! !cue-queue conj cue-id)))

(defn dequeue-cue!
  "Remove and return the next cue from the queue."
  []
  (let [queue @!cue-queue]
    (when (seq queue)
      (swap! !cue-queue rest)
      (first queue))))

(defn clear-queue!
  "Clear the cue queue."
  []
  (reset! !cue-queue []))

(defn get-queue
  "Get the current cue queue."
  []
  @!cue-queue)

(defn advance-queue!
  "Stop current cue and trigger the next one in queue."
  []
  (stop-cue!)
  (when-let [next-cue-id (dequeue-cue!)]
    (trigger-cue! next-cue-id)))

;; ============================================================================
;; Cue Lists (sequences of cues)
;; ============================================================================

(defn make-cue-list
  "Create a cue list (sequence of cues).
   - id: unique identifier
   - name: display name
   - cue-ids: ordered vector of cue IDs"
  [id name cue-ids]
  {:id id
   :name name
   :cue-ids (vec cue-ids)
   :created-at (System/currentTimeMillis)})

(defn add-cue-list!
  "Add a cue list."
  [cue-list]
  (swap! !cue-lists assoc (:id cue-list) cue-list))

(defn get-cue-list
  "Get a cue list by ID."
  [list-id]
  (get @!cue-lists list-id))

(defn update-cue-list!
  "Update a cue list's properties."
  [list-id updates]
  (swap! !cue-lists update list-id merge updates))

(defn remove-cue-list!
  "Remove a cue list."
  [list-id]
  (swap! !cue-lists dissoc list-id))

(defn list-cue-lists
  "Get all cue lists as a sequence."
  []
  (vals @!cue-lists))

(defn load-cue-list-to-queue!
  "Load all cues from a cue list into the queue."
  [list-id]
  (when-let [cue-list (get-cue-list list-id)]
    (clear-queue!)
    (doseq [cue-id (:cue-ids cue-list)]
      (queue-cue! cue-id))))

;; ============================================================================
;; Persistence
;; ============================================================================

(defn save-cues!
  "Save all cues to disk."
  []
  (config/ensure-config-dir!)
  (config/save-edn! cues-file @!cues))

(defn load-cues!
  "Load cues from disk."
  []
  (when-let [loaded (config/load-edn cues-file)]
    (reset! !cues loaded)))

(defn save-cue-lists!
  "Save all cue lists to disk."
  []
  (config/ensure-config-dir!)
  (config/save-edn! cue-lists-file @!cue-lists))

(defn load-cue-lists!
  "Load cue lists from disk."
  []
  (when-let [loaded (config/load-edn cue-lists-file)]
    (reset! !cue-lists loaded)))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize cue system by loading from disk."
  []
  (load-cues!)
  (load-cue-lists!))

(defn shutdown!
  "Save cues before shutdown."
  []
  (save-cues!)
  (save-cue-lists!))

;; ============================================================================
;; Quick Cue Creation from Grid
;; ============================================================================

(defn create-cue-from-cell!
  "Create a cue from a grid cell assignment.
   Uses the cell position as part of the cue ID."
  [col row preset-id & {:keys [name params duration]}]
  (let [cue-id (keyword (str "cell-" col "-" row))
        cue-name (or name (str "Cell " col "," row))
        cue (make-cue cue-id cue-name preset-id (or params {}) duration)]
    (add-cue! cue)
    cue))
