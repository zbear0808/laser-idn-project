(ns laser-show.events.handlers.projector
  "Event handlers for projector configuration and effects.
   
   This file handles projector-specific operations:
   - Network device discovery (scan, add devices/services)
   - Projector configuration (settings, enabled state)
   - Connection status updates
   - Test patterns and broadcast address configuration
   - Legacy effect operations (for backward compatibility)
   
   Effect-level operations have moved to chain.clj:
   - Parameter updates (update-param, update-param-from-text)
   - Spatial and curve editing
   - UI mode switching
   
   UI components should use :chain/* events with {:domain :projector-effects}
   for parameter and curve operations."
  (:require [clojure.tools.logging :as log]
            [laser-show.events.helpers :as h]
            [laser-show.events.handlers.chain :as chain-handlers]
            [laser-show.events.handlers.effect-params :as effect-params]
            [laser-show.common.util :as u]))


;; Path Helpers


(defn- projector-effects-path
  "Get the path to a projector's effects in state.
   Uses new unified :chains domain structure."
  [projector-id]
  [:chains :projector-effects projector-id :items])

(defn- projector-ui-state-path
  "Get the path to projector effect chain UI state.
   
   UI state is stored in :ui domain (not :projectors) to avoid
   subscription cascade - changes to drag state won't invalidate
   all projector-related subscriptions."
  [projector-id]
  [:ui :projector-effect-ui-state projector-id])


;; Network Discovery


(defn- handle-projectors-scan-network
  "Start scanning the network for IDN devices."
  [{:keys [state]}]
  (let [broadcast-addr (get-in state [:projectors :broadcast-address] "255.255.255.255")]
    {:state (assoc-in state [:projectors :scanning?] true)
     :projectors/scan {:broadcast-address broadcast-addr}}))

(defn- handle-projectors-scan-complete
  "Handle scan completion - update discovered devices list."
  [{:keys [devices state]}]
  {:state (-> state
              (assoc-in [:projectors :scanning?] false)
              (assoc-in [:projectors :discovered-devices] (vec devices)))})

(defn- handle-projectors-scan-failed
  "Handle scan failure."
  [{:keys [error state]}]
  (log/warn "Network scan failed:" error)
  {:state (assoc-in state [:projectors :scanning?] false)})


;; Zone Creation Helpers


(defn- zone-type->default-groups
  "Return default zone groups for a zone type."
  [zone-type]
  (case zone-type
    :default [:all]
    :graphics [:all :graphics]
    :crowd-scanning [:all :crowd]
    [:all]))

(defn- zone-type->name-suffix
  "Return name suffix for a zone type."
  [zone-type]
  (case zone-type
    :default "Default"
    :graphics "Graphics"
    :crowd-scanning "Crowd Scanning"
    "Unknown"))

(defn- create-zone
  "Create a zone configuration for a projector.
   
   Args:
   - projector-id: keyword ID of the parent projector
   - projector-name: name of the projector (for zone naming)
   - zone-type: :default, :graphics, or :crowd-scanning
   
   NOTE: Zone effects (geometry, etc.) are stored separately in :chains :zone-effects.
   This function only creates the zone metadata."
  [projector-id projector-name zone-type]
  (let [zone-id (random-uuid)]
    {:id zone-id
     :name (str projector-name " - " (zone-type->name-suffix zone-type))
     :projector-id projector-id
     :type zone-type
     :enabled? true
     :zone-groups (zone-type->default-groups zone-type)}))

(defn- make-default-zone-effects
  "Create default effects chain for a zone.
   Zones typically need basic geometry effects like corner-pin for calibration."
  []
  [{:effect-id :corner-pin
    :id (random-uuid)
    :enabled? true
    :params {:tl-x -1.0 :tl-y 1.0
             :tr-x 1.0 :tr-y 1.0
             :bl-x -1.0 :bl-y -1.0
             :br-x 1.0 :br-y -1.0}}])

(defn- create-projector-zones
  "Create all 3 zones for a new projector.
   Returns:
   - :zones-map - Map of {zone-id zone-config}
   - :zone-ids - Vector of zone UUIDs
   - :zone-effects-map - Map of {zone-id {:items [effects]}} for chains storage"
  [projector-id projector-name]
  (let [zone-types [:default :graphics :crowd-scanning]
        zones (mapv #(create-zone projector-id projector-name %) zone-types)]
    {:zones-map (into {} (map (fn [z] [(:id z) z]) zones))
     :zone-ids (mapv :id zones)
     :zone-effects-map (into {} (map (fn [z] [(:id z) {:items (make-default-zone-effects)}]) zones))}))


;; Device/Service Addition


(def DEFAULT_PROJECTOR_EFFECTS
  "Default effects for all projectors - color calibration only.
   
   Spatial/geometry calibration (corner-pin, flip, scale, offset, rotation)
   should be applied at the ZONE level, not the projector level.
   
   Projectors handle color calibration:
   - RGB curves for color balancing (using normalized 0.0-1.0 values)
   - (future: white balance, intensity curves, etc.)"
  [{:effect-id :rgb-curves
    :id (random-uuid)
    :enabled? true
    :params {:r-curve-points [[0.0 0.0] [1.0 1.0]]
             :g-curve-points [[0.0 0.0] [1.0 1.0]]
             :b-curve-points [[0.0 0.0] [1.0 1.0]]}}])


(defn- make-projector-config
  "Create a projector configuration map with defaults.
   
   Required keys: :name, :host
   Optional keys: :port, :service-id, :service-name, :unit-id, :zone-ids, :scan-rate
   
   :scan-rate is in points per second (pps). Default is 30,000 pps which is common
   for entry-level galvo systems. Higher-end systems may support 40-60k+ pps.
   
   TODO: Use scan-rate to calculate point limits for projected images (max points = scan-rate / fps)"
  [{:keys [name host port service-id service-name unit-id zone-ids scan-rate]
    :or {port 7255 zone-ids [] scan-rate 30000}}]
  {:name name
   :host host
   :port port
   :service-id service-id
   :service-name service-name
   :unit-id unit-id
   :zone-ids zone-ids
   :enabled? true
   :scan-rate scan-rate
   :output-config {:color-bit-depth 8
                   :xy-bit-depth 16}
   :status {:connected? false}})


(defn- handle-projectors-add-device
  "Add a discovered device as a configured projector.
   If device has services, adds the default service (or first one).
   For multi-output devices, use :projectors/add-service instead.
   
   Also creates 3 zones for the projector: default, graphics, crowd-scanning.
   Zone effects are stored in [:chains :zone-effects zone-id :items]."
  [{:keys [device state]}]
  (let [{:keys [address host-name unit-id]
         device-services :services
         device-port :port} device
        services (or device-services [])
        default-service (or (first (filter #(get-in % [:flags :default-service]) services))
                            (first services))
        {:keys [service-id]
         :or {service-id 0}} default-service
        service-name (:name default-service)
        name (or service-name
                 (when host-name (str host-name "." service-id))
                 address)
        projector-id (keyword (str "projector-" (System/currentTimeMillis)))
        ;; Create zones for this projector (includes zone effects)
        {:keys [zones-map zone-ids zone-effects-map]} (create-projector-zones projector-id name)
        projector-config (make-projector-config
                           {:name name
                            :host address
                            :port (or device-port 7255)
                            :service-id service-id
                            :service-name service-name
                            :unit-id unit-id
                            :zone-ids zone-ids})]
    {:state (-> state
                (assoc-in [:projectors :items projector-id] projector-config)
                (assoc-in [:chains :projector-effects projector-id :items] DEFAULT_PROJECTOR_EFFECTS)
                (update-in [:zones :items] merge zones-map)
                (update-in [:chains :zone-effects] merge zone-effects-map)
                (assoc-in [:projectors :active-projector] projector-id)
                (h/mark-dirty))}))


(defn- handle-projectors-add-service
  "Add a specific service/output from a discovered device as a projector.
   Used when a device has multiple outputs and user wants to add a specific one.
   
   Also creates 3 zones for the projector: default, graphics, crowd-scanning.
   Zone effects are stored in [:chains :zone-effects zone-id :items]."
  [{:keys [device service state]}]
  (let [{:keys [address host-name unit-id]
         device-port :port} device
        {:keys [service-id]
         service-name :name} service
        name (or service-name host-name address)
        projector-id (keyword (str "projector-" (System/currentTimeMillis) "-" service-id))
        ;; Create zones for this projector (includes zone effects)
        {:keys [zones-map zone-ids zone-effects-map]} (create-projector-zones projector-id name)
        projector-config (make-projector-config
                           {:name name
                            :host address
                            :port (or device-port 7255)
                            :service-id service-id
                            :service-name service-name
                            :unit-id unit-id
                            :zone-ids zone-ids})]
    {:state (-> state
                (assoc-in [:projectors :items projector-id] projector-config)
                (assoc-in [:chains :projector-effects projector-id :items] DEFAULT_PROJECTOR_EFFECTS)
                (update-in [:zones :items] merge zones-map)
                (update-in [:chains :zone-effects] merge zone-effects-map)
                (assoc-in [:projectors :active-projector] projector-id)
                (h/mark-dirty))}))


(defn- handle-projectors-add-all-services
  "Add all services from a discovered device as configured projectors.
   
   Also creates 3 zones per projector: default, graphics, crowd-scanning.
   Zone effects are stored in [:chains :zone-effects zone-id :items]."
  [{:keys [device state]}]
  (let [{:keys [address host-name unit-id]
         device-services :services
         device-port :port} device
        services (or device-services [])
        now (System/currentTimeMillis)
        projector-data (reduce
                         (fn [acc [idx service]]
                           (let [{:keys [service-id]
                                  service-name :name} service
                                 name (or service-name host-name address)
                                 projector-id (keyword (str "projector-" now "-" service-id "-" idx))
                                 ;; Create zones for this projector (includes zone effects)
                                 {:keys [zones-map zone-ids zone-effects-map]} (create-projector-zones projector-id name)
                                 projector-config (make-projector-config
                                                    {:name name
                                                     :host address
                                                     :port (or device-port 7255)
                                                     :service-id service-id
                                                     :service-name service-name
                                                     :unit-id unit-id
                                                     :zone-ids zone-ids})]
                             (-> acc
                                 (assoc-in [:projectors projector-id] projector-config)
                                 (assoc-in [:effects projector-id] {:items DEFAULT_PROJECTOR_EFFECTS})
                                 (update :zones merge zones-map)
                                 (update :zone-effects merge zone-effects-map))))
                         {:projectors {} :effects {} :zones {} :zone-effects {}}
                         (map-indexed vector services))
        first-projector-id (first (keys (:projectors projector-data)))]
    {:state (-> state
                (update-in [:projectors :items] merge (:projectors projector-data))
                (update-in [:chains :projector-effects] merge (:effects projector-data))
                (update-in [:zones :items] merge (:zones projector-data))
                (update-in [:chains :zone-effects] merge (:zone-effects projector-data))
                (assoc-in [:projectors :active-projector] first-projector-id)
                h/mark-dirty)}))


(defn- handle-projectors-add-manual
  "Add a projector manually (not from discovery).
   
   Also creates 3 zones for the projector: default, graphics, crowd-scanning.
   Zone effects are stored in [:chains :zone-effects zone-id :items]."
  [{:keys [name host port state]}]
  (let [projector-id (keyword (str "projector-" (System/currentTimeMillis)))
        proj-name (or name host)
        ;; Create zones for this projector (includes zone effects)
        {:keys [zones-map zone-ids zone-effects-map]} (create-projector-zones projector-id proj-name)
        projector-config (make-projector-config
                           {:name proj-name
                            :host host
                            :port (or port 7255)
                            :zone-ids zone-ids})]
    {:state (-> state
                (assoc-in [:projectors :items projector-id] projector-config)
                (assoc-in [:chains :projector-effects projector-id :items] DEFAULT_PROJECTOR_EFFECTS)
                (update-in [:zones :items] merge zones-map)
                (update-in [:chains :zone-effects] merge zone-effects-map)
                (assoc-in [:projectors :active-projector] projector-id)
                (h/mark-dirty))}))


;; Projector Management


(defn- handle-projectors-select-projector
  "Select a projector for editing."
  [{:keys [projector-id state]}]
  {:state (assoc-in state [:projectors :active-projector] projector-id)})

(defn- handle-projectors-remove-projector
  "Remove a projector configuration and its associated zones and zone effects."
  [{:keys [projector-id state]}]
  (let [active (get-in state [:projectors :active-projector])
        items (get-in state [:projectors :items])
        ;; Get zone IDs to delete
        zone-ids-to-remove (get-in items [projector-id :zone-ids] [])
        new-items (dissoc items projector-id)
        ;; If removing active projector, select first remaining
        new-active (if (= active projector-id)
                     (first (keys new-items))
                     active)
        ;; Remove zones from zones domain
        zones-items (get-in state [:zones :items] {})
        new-zones-items (apply dissoc zones-items zone-ids-to-remove)
        ;; Remove zone effects from chains domain
        zone-effects (get-in state [:chains :zone-effects] {})
        new-zone-effects (apply dissoc zone-effects zone-ids-to-remove)]
    {:state (-> state
                (assoc-in [:projectors :items] new-items)
                (assoc-in [:projectors :active-projector] new-active)
                (assoc-in [:zones :items] new-zones-items)
                (assoc-in [:chains :zone-effects] new-zone-effects)
                h/mark-dirty)}))

(defn- handle-projectors-update-settings
  "Update projector settings (name, host, port, enabled, output-config)."
  [{:keys [projector-id updates state]}]
  {:state (-> state
              (update-in [:projectors :items projector-id] merge updates)
              h/mark-dirty)})

(defn- handle-projectors-toggle-enabled
  "Toggle a projector's enabled state."
  [{:keys [projector-id state]}]
  (let [current (get-in state [:projectors :items projector-id :enabled?] true)]
    {:state (-> state
                (assoc-in [:projectors :items projector-id :enabled?] (not current))
                h/mark-dirty)}))

(defn- handle-projectors-update-connection-status
  "Update a projector's connection status (called by streaming service)."
  [{:keys [projector-id status state]}]
  {:state (update-in state [:projectors :items projector-id :status] merge status)})


;; Legacy Effect Chain Management (kept for backward compatibility)


(defn- handle-projectors-add-effect
  "Add an effect to a projector's chain.
   DEPRECATED: Use :chain/add-item with {:domain :projector-effects} instead."
  [{:keys [projector-id effect state]}]
  (let [effect-with-fields (h/ensure-item-fields effect)]
    {:state (-> state
                (update-in [:chains :projector-effects projector-id :items] conj effect-with-fields)
                h/mark-dirty)}))

(defn- handle-projectors-remove-effect
  "Remove an effect from a projector's chain.
   DEPRECATED: Use :chain/remove-item-at-path with {:domain :projector-effects} instead."
  [{:keys [projector-id effect-idx state]}]
  (let [effects-vec (get-in state [:chains :projector-effects projector-id :items] [])
        new-effects (u/removev-indexed (fn [i _] (= i effect-idx)) effects-vec)]
    {:state (-> state
                (assoc-in [:chains :projector-effects projector-id :items] new-effects)
                h/mark-dirty)}))


(defn- handle-projectors-reorder-effects
  "Reorder effects in a projector's chain.
   DEPRECATED: Use :chain/reorder-items with {:domain :projector-effects} instead."
  [{:keys [projector-id from-idx to-idx state]}]
  (let [effects-vec (get-in state [:chains :projector-effects projector-id :items] [])
        effect (nth effects-vec from-idx)
        without (u/removev-indexed (fn [i _] (= i from-idx)) effects-vec)
        reordered (u/concatv (subvec without 0 to-idx)
                             [effect]
                             (subvec without to-idx))]
    {:state (-> state
                (assoc-in [:chains :projector-effects projector-id :items] reordered)
                h/mark-dirty)}))


;; Effect Selection


(defn- handle-projectors-select-effect
  "Select an effect in the projector's chain for editing.
   Uses path-based selection with :ctrl? and :shift? modifiers."
  [{:keys [projector-id path ctrl? shift? state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-select-item state config path ctrl? shift?)}))


;; Calibration Effects


(defn- handle-projectors-add-calibration-effect
  "Add a calibration effect to projector chain with auto-selection."
  [{:keys [projector-id effect state]}]
  (let [effect-with-fields (h/ensure-item-fields effect)
        current-effects (get-in state (projector-effects-path projector-id) [])
        new-effect-idx (count current-effects)
        new-effect-path [new-effect-idx]
        ui-path (projector-ui-state-path projector-id)]
    {:state (-> state
                (update-in (projector-effects-path projector-id) conj effect-with-fields)
                (assoc-in (conj ui-path :selected-paths) #{new-effect-path})
                (assoc-in (conj ui-path :last-selected-path) new-effect-path)
                (h/mark-dirty))}))


;; Corner Pin (projector-specific calibration)


(defn- handle-projectors-update-corner-pin
  "Update corner pin parameters from spatial drag.
   Note: This uses effect-idx for legacy compatibility.
   For path-based updates, use :chain/update-spatial-params."
  [{:keys [projector-id effect-idx point-id x y param-map state]}]
  (let [params-path [:chains :projector-effects projector-id :items effect-idx :params]]
    {:state (effect-params/update-spatial-params state params-path point-id x y param-map)}))

(defn- handle-projectors-reset-corner-pin
  "Reset corner pin effect to default values."
  [{:keys [projector-id effect-idx state]}]
  {:state (assoc-in state [:chains :projector-effects projector-id :items effect-idx :params]
                    {:tl-x -1.0 :tl-y 1.0
                     :tr-x 1.0 :tr-y 1.0
                     :bl-x -1.0 :bl-y -1.0
                     :br-x 1.0 :br-y -1.0})})


;; Chain-Handler Delegated Operations


(defn- handle-projectors-select-all-effects
  "Select all effects in a projector's chain."
  [{:keys [projector-id state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-select-all state config)}))

(defn- handle-projectors-clear-effect-selection
  "Clear effect selection for a projector."
  [{:keys [projector-id state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-clear-selection state config)}))

(defn- handle-projectors-delete-effects
  "Delete selected effects from projector chain."
  [{:keys [projector-id state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-delete-selected state config)}))

(defn- handle-projectors-start-effect-drag
  "Start a multi-drag operation for projector effects."
  [{:keys [projector-id initiating-path state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-start-drag state config initiating-path)}))

(defn- handle-projectors-move-effects
  "Move multiple effects to a new position in projector chain."
  [{:keys [projector-id target-id drop-position state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-move-items state config target-id drop-position)}))


;; Clipboard Operations


(defn- handle-projectors-copy-effects
  "Copy selected effects to clipboard."
  [{:keys [projector-id state]}]
  (let [ui-path (projector-ui-state-path projector-id)
        selected-paths (get-in state (conj ui-path :selected-paths) #{})
        effects-vec (get-in state (projector-effects-path projector-id) [])
        valid-effects (when (seq selected-paths)
                        (vec (keep #(get-in effects-vec %) selected-paths)))]
    (cond-> {:state state}
      (seq valid-effects)
      (assoc :clipboard/copy-effects valid-effects))))

(defn- handle-projectors-paste-effects
  "Paste effects from clipboard into projector chain."
  [{:keys [projector-id state]}]
  (let [ui-path (projector-ui-state-path projector-id)
        selected-paths (get-in state (conj ui-path :selected-paths) #{})
        effects-vec (get-in state (projector-effects-path projector-id) [])
        insert-pos (if (seq selected-paths)
                     (let [top-level-indices (map first selected-paths)]
                       (inc (apply max top-level-indices)))
                     (count effects-vec))]
    {:state state
     :clipboard/paste-projector-effects {:projector-id projector-id
                                         :insert-pos insert-pos}}))

(defn- handle-projectors-insert-pasted-effects
  "Actually insert pasted effects into projector chain (called by effect handler)."
  [{:keys [projector-id insert-pos effects state]}]
  (let [effects-with-new-ids (mapv h/regenerate-ids effects)
        current-effects (vec (get-in state (projector-effects-path projector-id) []))
        safe-pos (min insert-pos (count current-effects))
        new-effects (vec (concat (subvec current-effects 0 safe-pos)
                                 effects-with-new-ids
                                 (subvec current-effects safe-pos)))
        ui-path (projector-ui-state-path projector-id)
        new-paths (into #{} (map (fn [i] [(+ safe-pos i)]) (range (count effects-with-new-ids))))]
    {:state (-> state
                (assoc-in (projector-effects-path projector-id) new-effects)
                (assoc-in (conj ui-path :selected-paths) new-paths)
                h/mark-dirty)}))


;; UI State


(defn- handle-projectors-update-effect-ui-state
  "Update projector effect UI state (for drag-and-drop feedback)."
  [{:keys [projector-id updates state]}]
  (let [ui-path (projector-ui-state-path projector-id)]
    {:state (update-in state ui-path merge updates)}))


;; Hierarchical List Integration


(defn- handle-projectors-set-effects
  "Set the entire effects chain for a projector (simple persistence callback)."
  [{:keys [projector-id effects state]}]
  {:state (-> state
              (assoc-in (projector-effects-path projector-id) effects)
              h/mark-dirty)})

(defn- handle-projectors-update-effect-selection
  "Update the selection state for projector effects."
  [{:keys [projector-id selected-ids state]}]
  (let [ui-path (projector-ui-state-path projector-id)]
    {:state (assoc-in state (conj ui-path :selected-ids) selected-ids)}))


;; Configuration


(defn- handle-projectors-set-test-pattern
  "Set or clear the test pattern mode."
  [{:keys [pattern state]}]
  {:state (assoc-in state [:projectors :test-pattern-mode] pattern)})

(defn- handle-projectors-set-broadcast-address
  "Set the broadcast address for network scanning."
  [{:keys [address state]}]
  {:state (assoc-in state [:projectors :broadcast-address] address)})

(defn- handle-projectors-toggle-device-expand
  "Toggle the expanded state of a device in the discovery panel."
  [{:keys [address state]}]
  (let [current-expanded (get-in state [:projectors :expanded-devices] #{})]
    {:state (assoc-in state [:projectors :expanded-devices]
                      (if (contains? current-expanded address)
                        (disj current-expanded address)
                        (conj current-expanded address)))}))


;; Public API


(defn handle
  "Dispatch projector events to their handlers.
   
   Accepts events with :event/type in the :projectors/* namespace.
   
   Note: Parameter, curve, and UI mode operations should use :chain/* events:
   - :chain/update-param, :chain/update-param-from-text
   - :chain/add-curve-point, :chain/update-curve-point, :chain/remove-curve-point
   - :chain/set-active-curve-channel
   - :chain/update-spatial-params
   - :chain/set-ui-mode
   
   Pass {:domain :projector-effects :entity-key projector-id} to these events."
  [{:keys [event/type] :as event}]
  (case type
    ;; Network discovery
    :projectors/scan-network (handle-projectors-scan-network event)
    :projectors/scan-complete (handle-projectors-scan-complete event)
    :projectors/scan-failed (handle-projectors-scan-failed event)
    
    ;; Device/service addition
    :projectors/add-device (handle-projectors-add-device event)
    :projectors/add-service (handle-projectors-add-service event)
    :projectors/add-all-services (handle-projectors-add-all-services event)
    :projectors/add-manual (handle-projectors-add-manual event)
    
    ;; Projector management
    :projectors/select-projector (handle-projectors-select-projector event)
    :projectors/remove-projector (handle-projectors-remove-projector event)
    :projectors/update-settings (handle-projectors-update-settings event)
    :projectors/toggle-enabled (handle-projectors-toggle-enabled event)
    :projectors/update-connection-status (handle-projectors-update-connection-status event)
    
    ;; Legacy effect chain management (kept for backward compatibility)
    :projectors/add-effect (handle-projectors-add-effect event)
    :projectors/remove-effect (handle-projectors-remove-effect event)
    :projectors/reorder-effects (handle-projectors-reorder-effects event)
    
    ;; Legacy selection
    :projectors/select-effect (handle-projectors-select-effect event)
    
    ;; Calibration
    :projectors/add-calibration-effect (handle-projectors-add-calibration-effect event)
    :projectors/update-corner-pin (handle-projectors-update-corner-pin event)
    :projectors/reset-corner-pin (handle-projectors-reset-corner-pin event)
    
    ;; Path-based selection (delegated to chain-handlers)
    :projectors/select-all-effects (handle-projectors-select-all-effects event)
    :projectors/clear-effect-selection (handle-projectors-clear-effect-selection event)
    
    ;; Clipboard
    :projectors/copy-effects (handle-projectors-copy-effects event)
    :projectors/paste-effects (handle-projectors-paste-effects event)
    :projectors/insert-pasted-effects (handle-projectors-insert-pasted-effects event)
    :projectors/delete-effects (handle-projectors-delete-effects event)
    
    ;; Drag and drop (delegated to chain-handlers)
    :projectors/start-effect-drag (handle-projectors-start-effect-drag event)
    :projectors/move-effects (handle-projectors-move-effects event)
    :projectors/update-effect-ui-state (handle-projectors-update-effect-ui-state event)
    
    ;; Hierarchical list integration
    :projectors/set-effects (handle-projectors-set-effects event)
    :projectors/update-effect-selection (handle-projectors-update-effect-selection event)
    
    ;; Configuration
    :projectors/set-test-pattern (handle-projectors-set-test-pattern event)
    :projectors/set-broadcast-address (handle-projectors-set-broadcast-address event)
    :projectors/toggle-device-expand (handle-projectors-toggle-device-expand event)
    
    ;; Unknown event in this domain
    {}))
