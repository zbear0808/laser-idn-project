(ns laser-show.backend.projectors
  "Projector configuration and management.
   Handles registration, status tracking, and network configuration for laser projectors.
   
   Projectors support effect chains for projector-level calibration and effects:
   - :effect-chain {:effects [{:effect-id ... :enabled true :params {...}} ...]}"
  (:require [laser-show.database.persistent :as persist]
            [laser-show.animation.effects :as fx]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:const DEFAULT_PORT 7255)
(def ^:const DEFAULT_CHANNEL_ID 0)

;; ============================================================================
;; Projector Registry (now delegated to database/persistent)
;; ============================================================================

;; ============================================================================
;; Projector Data Structure
;; ============================================================================

(defn make-projector
  "Create a projector definition.
   
   Parameters:
   - id: Unique keyword identifier (e.g., :projector-1)
   - name: Human-readable name (e.g., \"Left Stage Laser\")
   - address: IP address for IDN streaming
   - opts: Optional map with:
     - :port - UDP port (default 7255)
     - :channel-id - IDN channel ID (default 0)
     - :status - :active or :inactive (default :active)
     - :metadata - Additional projector metadata"
  [id name address & {:keys [port channel-id status metadata]
                      :or {port DEFAULT_PORT
                           channel-id DEFAULT_CHANNEL_ID
                           status :active
                           metadata {}}}]
  {:id id
   :name name
   :address address
   :port port
   :channel-id channel-id
   :status status
   :metadata metadata
   :created-at (System/currentTimeMillis)})

(defn valid-projector?
  "Validate a projector definition."
  [projector]
  (and (keyword? (:id projector))
       (string? (:name projector))
       (string? (:address projector))
       (integer? (:port projector))
       (integer? (:channel-id projector))
       (#{:active :inactive} (:status projector))))

;; ============================================================================
;; CRUD Operations
;; ============================================================================

(defn register-projector!
  "Register a projector in the registry.
   Can accept either a projector map or individual parameters."
  ([projector]
   (when (valid-projector? projector)
     (persist/add-projector! (:id projector) projector)
     projector))
  ([id name address & opts]
   (let [projector (apply make-projector id name address opts)]
     (register-projector! projector))))

(defn get-projector
  "Get a projector by ID."
  [projector-id]
  (persist/get-projector projector-id))

(defn update-projector!
  "Update a projector's properties."
  [projector-id updates]
  (when (get-projector projector-id)
    (let [updated (merge (get-projector projector-id) updates)]
      (persist/add-projector! projector-id updated)
      updated)))

(defn remove-projector!
  "Remove a projector from the registry."
  [projector-id]
  (persist/remove-projector! projector-id))

(defn list-projectors
  "Get all projectors as a sequence."
  []
  (vals (persist/get-projectors)))

(defn get-projector-ids
  "Get all projector IDs."
  []
  (keys (persist/get-projectors)))

(defn clear-projectors!
  "Clear all projectors from the registry."
  []
  (reset! persist/!projectors {})
  (persist/save-projectors!))

;; ============================================================================
;; Status Management
;; ============================================================================

(defn set-projector-status!
  "Set a projector's status (:active or :inactive)."
  [projector-id status]
  (when (#{:active :inactive} status)
    (update-projector! projector-id {:status status})))

(defn projector-active?
  "Check if a projector is active."
  [projector-id]
  (= :active (:status (get-projector projector-id))))

(defn get-active-projectors
  "Get all active projectors."
  []
  (filter #(= :active (:status %)) (list-projectors)))

(defn get-active-projector-ids
  "Get IDs of all active projectors."
  []
  (map :id (get-active-projectors)))

;; ============================================================================
;; Network Configuration Helpers
;; ============================================================================

(defn get-projector-address
  "Get the network address for a projector."
  [projector-id]
  (:address (get-projector projector-id)))

(defn get-projector-port
  "Get the port for a projector."
  [projector-id]
  (:port (get-projector projector-id)))

(defn get-projector-channel-id
  "Get the IDN channel ID for a projector."
  [projector-id]
  (:channel-id (get-projector projector-id)))

(defn get-projector-endpoint
  "Get the full endpoint [address port] for a projector."
  [projector-id]
  (when-let [proj (get-projector projector-id)]
    [(:address proj) (:port proj)]))

;; ============================================================================
;; Persistence (delegated to database/persistent)
;; ============================================================================

(defn save-projectors!
  "Save all projectors to disk."
  []
  (persist/save-projectors!))

(defn load-projectors!
  "Load projectors from disk."
  []
  (persist/load-projectors!))

;; ============================================================================
;; Default Projector
;; ============================================================================

(defn ensure-default-projector!
  "Ensure at least one default projector exists.
   Creates :projector-1 if no projectors are registered."
  []
  (when (empty? (persist/get-projectors))
    (register-projector!
     (make-projector :projector-1 "Default Projector" "192.168.1.100"))))

(defn get-default-projector-id
  "Get the default projector ID (first registered or :projector-1)."
  []
  (or (first (get-projector-ids))
      :projector-1))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize projector system by loading from disk."
  []
  (load-projectors!)
  (ensure-default-projector!))

(defn shutdown!
  "Save projectors before shutdown."
  []
  (save-projectors!))

;; ============================================================================
;; Effect Chain Management
;; ============================================================================

(defn set-projector-effect-chain!
  "Set the effect chain for a projector."
  [projector-id effect-chain]
  (update-projector! projector-id {:effect-chain effect-chain}))

(defn get-projector-effect-chain
  "Get the effect chain for a projector."
  [projector-id]
  (:effect-chain (get-projector projector-id)))

(defn add-effect-to-projector!
  "Add an effect to a projector's effect chain."
  [projector-id effect-instance]
  (let [projector (get-projector projector-id)
        current-chain (or (:effect-chain projector) (fx/empty-effect-chain))
        new-chain (fx/add-effect-to-chain current-chain effect-instance)]
    (update-projector! projector-id {:effect-chain new-chain})))

(defn remove-effect-from-projector!
  "Remove an effect from a projector's effect chain by index."
  [projector-id effect-index]
  (when-let [projector (get-projector projector-id)]
    (when-let [chain (:effect-chain projector)]
      (let [new-chain (fx/remove-effect-at chain effect-index)]
        (update-projector! projector-id {:effect-chain new-chain})))))

(defn update-projector-effect!
  "Update an effect in a projector's effect chain."
  [projector-id effect-index updates]
  (when-let [projector (get-projector projector-id)]
    (when-let [chain (:effect-chain projector)]
      (let [new-chain (fx/update-effect-at chain effect-index updates)]
        (update-projector! projector-id {:effect-chain new-chain})))))

(defn enable-projector-effect!
  "Enable an effect in a projector's effect chain."
  [projector-id effect-index]
  (update-projector-effect! projector-id effect-index {:enabled true}))

(defn disable-projector-effect!
  "Disable an effect in a projector's effect chain."
  [projector-id effect-index]
  (update-projector-effect! projector-id effect-index {:enabled false}))

(defn apply-projector-effects
  "Apply a projector's effect chain to a frame.
   This is typically used for projector-level calibration effects."
  [projector-id frame time-ms bpm]
  (if-let [chain (get-projector-effect-chain projector-id)]
    (fx/apply-effect-chain frame chain time-ms bpm)
    frame))
