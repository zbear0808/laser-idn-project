(ns laser-show.events.handlers.projector
  "Event handlers for projector configuration and effects.
   
   SIMPLIFIED ARCHITECTURE (v2):
   - Projectors have corner-pin geometry directly (no separate zones)
   - Projectors are assigned to zone groups directly
   - Virtual projectors provide alternate corner-pin with inherited color curves
   - Tags (:graphics, :crowd-scanning) auto-add to matching zone groups
   
   This file handles:
   - Network device discovery (scan, add devices/services)
   - Projector configuration (settings, enabled state, corner-pin)
   - Zone group assignment
   - Tag management
   - Virtual projector creation and management
   - Connection status updates
   - Test patterns"
  (:require [clojure.tools.logging :as log]
            [laser-show.events.helpers :as h]
            [laser-show.events.handlers.chain :as chain-handlers]
            [laser-show.common.util :as u]))


;; Constants


(def DEFAULT_CORNER_PIN
  "Default corner-pin with no transformation (identity mapping)."
  {:tl-x -1.0 :tl-y 1.0
   :tr-x 1.0 :tr-y 1.0
   :bl-x -1.0 :bl-y -1.0
   :br-x 1.0 :br-y -1.0})


(def DEFAULT_PROJECTOR_EFFECTS
  "Default effects for all projectors - color calibration and corner pin.
   Uses normalized 0.0-1.0 color values and -1.0 to 1.0 corner pin bounds."
  [{:effect-id :rgb-curves
    :id (random-uuid)
    :enabled? true
    :params {:r-curve-points [[0.0 0.0] [1.0 1.0]]
             :g-curve-points [[0.0 0.0] [1.0 1.0]]
             :b-curve-points [[0.0 0.0] [1.0 1.0]]}}
   {:effect-id :corner-pin
    :id (random-uuid)
    :enabled? true
    :params {:tl-x -1.0 :tl-y 1.0
             :tr-x 1.0 :tr-y 1.0
             :bl-x -1.0 :bl-y -1.0
             :br-x 1.0 :br-y -1.0}}])


;; Path Helpers


(defn- projector-effects-path
  "Get the path to a projector's effects in state.
   Uses unified :chains domain structure."
  [projector-id]
  [:chains :projector-effects projector-id :items])


(defn- projector-ui-state-path
  "Get the path to projector effect chain UI state."
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


;; Projector Configuration Factory


(defn- make-projector-config
  "Create a projector configuration map with defaults.
   
   Required keys: :name, :host
   Optional keys: :port, :service-id, :service-name, :unit-id, :zone-groups, :tags, :scan-rate, :corner-pin"
  [{:keys [name host port service-id service-name unit-id zone-groups tags scan-rate corner-pin]
    :or {port 7255 zone-groups [:all] tags #{} scan-rate 30000 corner-pin DEFAULT_CORNER_PIN}}]
  {:name name
   :host host
   :port port
   :service-id service-id
   :service-name service-name
   :unit-id unit-id
   :zone-groups zone-groups
   :tags tags
   :corner-pin corner-pin
   :enabled? true
   :scan-rate scan-rate
   :output-config {:color-bit-depth 8
                   :xy-bit-depth 16}
   :status {:connected? false}})


;; Device/Service Addition


(defn- handle-projectors-add-device
  "Add a discovered device as a configured projector.
   If device has services, adds the default service (or first one).
   For multi-output devices, use :projectors/add-service instead."
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
        projector-config (make-projector-config
                           {:name name
                            :host address
                            :port (or device-port 7255)
                            :service-id service-id
                            :service-name service-name
                            :unit-id unit-id})]
    {:state (-> state
                (assoc-in [:projectors :items projector-id] projector-config)
                (assoc-in [:chains :projector-effects projector-id :items] (mapv #(assoc % :id (random-uuid)) DEFAULT_PROJECTOR_EFFECTS))
                (assoc-in [:projectors :active-projector] projector-id)
                h/mark-dirty)}))


(defn- handle-projectors-add-service
  "Add a specific service/output from a discovered device as a projector.
   Used when a device has multiple outputs and user wants to add a specific one."
  [{:keys [device service state]}]
  (let [{:keys [address host-name unit-id]
         device-port :port} device
        {:keys [service-id]
         service-name :name} service
        name (or service-name host-name address)
        projector-id (keyword (str "projector-" (System/currentTimeMillis) "-" service-id))
        projector-config (make-projector-config
                           {:name name
                            :host address
                            :port (or device-port 7255)
                            :service-id service-id
                            :service-name service-name
                            :unit-id unit-id})]
    {:state (-> state
                (assoc-in [:projectors :items projector-id] projector-config)
                (assoc-in [:chains :projector-effects projector-id :items] (mapv #(assoc % :id (random-uuid)) DEFAULT_PROJECTOR_EFFECTS))
                (assoc-in [:projectors :active-projector] projector-id)
                h/mark-dirty)}))


(defn- handle-projectors-add-all-services
  "Add all services from a discovered device as configured projectors."
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
                                 projector-config (make-projector-config
                                                    {:name name
                                                     :host address
                                                     :port (or device-port 7255)
                                                     :service-id service-id
                                                     :service-name service-name
                                                     :unit-id unit-id})]
                             (-> acc
                                 (assoc-in [:projectors projector-id] projector-config)
                                 (assoc-in [:effects projector-id] {:items (mapv #(assoc % :id (random-uuid)) DEFAULT_PROJECTOR_EFFECTS)}))))
                         {:projectors {} :effects {}}
                         (map-indexed vector services))
        first-projector-id (first (keys (:projectors projector-data)))]
    {:state (-> state
                (update-in [:projectors :items] merge (:projectors projector-data))
                (update-in [:chains :projector-effects] merge (:effects projector-data))
                (assoc-in [:projectors :active-projector] first-projector-id)
                h/mark-dirty)}))


(defn- handle-projectors-add-manual
  "Add a projector manually (not from discovery)."
  [{:keys [name host port state]}]
  (let [projector-id (keyword (str "projector-" (System/currentTimeMillis)))
        proj-name (or name host)
        projector-config (make-projector-config
                           {:name proj-name
                            :host host
                            :port (or port 7255)})]
    {:state (-> state
                (assoc-in [:projectors :items projector-id] projector-config)
                (assoc-in [:chains :projector-effects projector-id :items] (mapv #(assoc % :id (random-uuid)) DEFAULT_PROJECTOR_EFFECTS))
                (assoc-in [:projectors :active-projector] projector-id)
                h/mark-dirty)}))


;; Projector Management


(defn- handle-projectors-select-projector
  "Select a projector for editing."
  [{:keys [projector-id state]}]
  {:state (-> state
              (assoc-in [:projectors :active-projector] projector-id)
              (assoc-in [:projectors :active-virtual-projector] nil))})


(defn- handle-projectors-remove-projector
  "Remove a projector configuration and its virtual projectors."
  [{:keys [projector-id state]}]
  (let [active (get-in state [:projectors :active-projector])
        items (get-in state [:projectors :items])
        new-items (dissoc items projector-id)
        ;; Remove virtual projectors for this projector
        vps (get-in state [:projectors :virtual-projectors] {})
        vp-ids-to-remove (keep (fn [[vp-id vp]]
                                 (when (= (:parent-projector-id vp) projector-id)
                                   vp-id))
                               vps)
        new-vps (apply dissoc vps vp-ids-to-remove)
        ;; If removing active projector, select first remaining
        new-active (if (= active projector-id)
                     (first (keys new-items))
                     active)]
    {:state (-> state
                (assoc-in [:projectors :items] new-items)
                (assoc-in [:projectors :virtual-projectors] new-vps)
                (assoc-in [:projectors :active-projector] new-active)
                (assoc-in [:projectors :active-virtual-projector] nil)
                (update-in [:chains :projector-effects] dissoc projector-id)
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


;; Corner Pin Management


(defn- handle-projectors-update-corner-pin
  "Update corner pin parameters from spatial drag."
  [{:keys [projector-id point-id x y state]}]
  (let [param-key (case point-id
                    :tl [:tl-x :tl-y]
                    :tr [:tr-x :tr-y]
                    :bl [:bl-x :bl-y]
                    :br [:br-x :br-y]
                    nil)
        [x-key y-key] param-key]
    (if param-key
      {:state (-> state
                  (assoc-in [:projectors :items projector-id :corner-pin x-key] x)
                  (assoc-in [:projectors :items projector-id :corner-pin y-key] y)
                  h/mark-dirty)}
      {:state state})))


(defn- handle-projectors-reset-corner-pin
  "Reset corner pin to default values."
  [{:keys [projector-id state]}]
  {:state (-> state
              (assoc-in [:projectors :items projector-id :corner-pin] DEFAULT_CORNER_PIN)
              h/mark-dirty)})


;; Zone Group Assignment


(defn- handle-projectors-toggle-zone-group
  "Toggle a projector's membership in a zone group."
  [{:keys [projector-id zone-group-id state]}]
  (let [current-groups (get-in state [:projectors :items projector-id :zone-groups] [])
        member? (some #{zone-group-id} current-groups)
        new-groups (if member?
                     (vec (remove #{zone-group-id} current-groups))
                     (conj current-groups zone-group-id))]
    {:state (-> state
                (assoc-in [:projectors :items projector-id :zone-groups] new-groups)
                h/mark-dirty)}))


(defn- handle-projectors-set-zone-groups
  "Set all zone groups for a projector."
  [{:keys [projector-id zone-groups state]}]
  {:state (-> state
              (assoc-in [:projectors :items projector-id :zone-groups] zone-groups)
              h/mark-dirty)})


;; Tag Management


(defn- tag->auto-zone-group
  "Get the zone group that a tag auto-assigns to."
  [tag]
  (case tag
    :graphics :graphics
    :crowd-scanning :crowd
    nil))


(defn- handle-projectors-add-tag
  "Add a tag to a projector. Auto-adds to matching zone group if not already member."
  [{:keys [projector-id tag state]}]
  (let [auto-group (tag->auto-zone-group tag)
        current-groups (get-in state [:projectors :items projector-id :zone-groups] [])]
    {:state (-> state
                (update-in [:projectors :items projector-id :tags] (fnil conj #{}) tag)
                ;; Auto-add to matching zone group if not already member
                (cond-> (and auto-group (not (some #{auto-group} current-groups)))
                  (update-in [:projectors :items projector-id :zone-groups] conj auto-group))
                h/mark-dirty)}))


(defn- handle-projectors-remove-tag
  "Remove a tag from a projector. Does NOT auto-remove from zone group."
  [{:keys [projector-id tag state]}]
  {:state (-> state
              (update-in [:projectors :items projector-id :tags] disj tag)
              h/mark-dirty)})


;; Virtual Projector Management


(defn- handle-projectors-add-virtual-projector
  "Add a virtual projector for an existing physical projector.
   The VP starts with a copy of the parent's corner-pin."
  [{:keys [parent-projector-id name state]}]
  (let [vp-id (random-uuid)
        parent (get-in state [:projectors :items parent-projector-id])
        parent-name (:name parent "Unknown")
        vp-name (or name (str parent-name " - Virtual"))
        parent-corner-pin (or (:corner-pin parent) DEFAULT_CORNER_PIN)
        vp-config {:name vp-name
                   :parent-projector-id parent-projector-id
                   :zone-groups [:all]
                   :tags #{}
                   :corner-pin parent-corner-pin
                   :enabled? true}]
    {:state (-> state
                (assoc-in [:projectors :virtual-projectors vp-id] vp-config)
                (assoc-in [:projectors :active-virtual-projector] vp-id)
                h/mark-dirty)}))


(defn- handle-projectors-select-virtual-projector
  "Select a virtual projector for editing."
  [{:keys [vp-id state]}]
  {:state (-> state
              (assoc-in [:projectors :active-virtual-projector] vp-id)
              (assoc-in [:projectors :active-projector] nil))})


(defn- handle-projectors-remove-virtual-projector
  "Remove a virtual projector."
  [{:keys [vp-id state]}]
  (let [active (get-in state [:projectors :active-virtual-projector])]
    {:state (-> state
                (update-in [:projectors :virtual-projectors] dissoc vp-id)
                (cond-> (= active vp-id)
                  (assoc-in [:projectors :active-virtual-projector] nil))
                h/mark-dirty)}))


(defn- handle-projectors-update-virtual-projector
  "Update virtual projector settings (name, enabled)."
  [{:keys [vp-id updates state]}]
  {:state (-> state
              (update-in [:projectors :virtual-projectors vp-id] merge updates)
              h/mark-dirty)})


(defn- handle-vp-toggle-enabled
  "Toggle a virtual projector's enabled state."
  [{:keys [vp-id state]}]
  (let [current (get-in state [:projectors :virtual-projectors vp-id :enabled?] true)]
    {:state (-> state
                (assoc-in [:projectors :virtual-projectors vp-id :enabled?] (not current))
                h/mark-dirty)}))


(defn- handle-vp-update-corner-pin
  "Update corner pin for a virtual projector."
  [{:keys [vp-id point-id x y state]}]
  (let [param-key (case point-id
                    :tl [:tl-x :tl-y]
                    :tr [:tr-x :tr-y]
                    :bl [:bl-x :bl-y]
                    :br [:br-x :br-y]
                    nil)
        [x-key y-key] param-key]
    (if param-key
      {:state (-> state
                  (assoc-in [:projectors :virtual-projectors vp-id :corner-pin x-key] x)
                  (assoc-in [:projectors :virtual-projectors vp-id :corner-pin y-key] y)
                  h/mark-dirty)}
      {:state state})))


(defn- handle-vp-reset-corner-pin
  "Reset virtual projector corner pin to parent's values."
  [{:keys [vp-id state]}]
  (let [vp (get-in state [:projectors :virtual-projectors vp-id])
        parent-id (:parent-projector-id vp)
        parent-corner-pin (get-in state [:projectors :items parent-id :corner-pin] DEFAULT_CORNER_PIN)]
    {:state (-> state
                (assoc-in [:projectors :virtual-projectors vp-id :corner-pin] parent-corner-pin)
                h/mark-dirty)}))


(defn- handle-vp-toggle-zone-group
  "Toggle a virtual projector's membership in a zone group."
  [{:keys [vp-id zone-group-id state]}]
  (let [current-groups (get-in state [:projectors :virtual-projectors vp-id :zone-groups] [])
        member? (some #{zone-group-id} current-groups)
        new-groups (if member?
                     (vec (remove #{zone-group-id} current-groups))
                     (conj current-groups zone-group-id))]
    {:state (-> state
                (assoc-in [:projectors :virtual-projectors vp-id :zone-groups] new-groups)
                h/mark-dirty)}))


(defn- handle-vp-set-zone-groups
  "Set all zone groups for a virtual projector."
  [{:keys [vp-id zone-groups state]}]
  {:state (-> state
              (assoc-in [:projectors :virtual-projectors vp-id :zone-groups] zone-groups)
              h/mark-dirty)})


(defn- handle-vp-add-tag
  "Add a tag to a virtual projector."
  [{:keys [vp-id tag state]}]
  (let [auto-group (tag->auto-zone-group tag)
        current-groups (get-in state [:projectors :virtual-projectors vp-id :zone-groups] [])]
    {:state (-> state
                (update-in [:projectors :virtual-projectors vp-id :tags] (fnil conj #{}) tag)
                (cond-> (and auto-group (not (some #{auto-group} current-groups)))
                  (update-in [:projectors :virtual-projectors vp-id :zone-groups] conj auto-group))
                h/mark-dirty)}))


(defn- handle-vp-remove-tag
  "Remove a tag from a virtual projector."
  [{:keys [vp-id tag state]}]
  {:state (-> state
              (update-in [:projectors :virtual-projectors vp-id :tags] disj tag)
              h/mark-dirty)})


;; Effect Chain Management (for color curves)


(defn- handle-projectors-add-effect
  "Add an effect to a projector's chain."
  [{:keys [projector-id effect state]}]
  (let [effect-with-fields (h/ensure-item-fields effect)]
    {:state (-> state
                (update-in [:chains :projector-effects projector-id :items] conj effect-with-fields)
                h/mark-dirty)}))


(defn- handle-projectors-remove-effect
  "Remove an effect from a projector's chain."
  [{:keys [projector-id effect-idx state]}]
  (let [effects-vec (get-in state [:chains :projector-effects projector-id :items] [])
        new-effects (u/removev-indexed (fn [i _] (= i effect-idx)) effects-vec)]
    {:state (-> state
                (assoc-in [:chains :projector-effects projector-id :items] new-effects)
                h/mark-dirty)}))


(defn- handle-projectors-select-effect
  "Select an effect in the projector's chain for editing."
  [{:keys [projector-id path ctrl? shift? state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-select-item state config path ctrl? shift?)}))


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
   
   Accepts events with :event/type in the :projectors/* namespace."
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
    
    ;; Corner pin
    :projectors/update-corner-pin (handle-projectors-update-corner-pin event)
    :projectors/reset-corner-pin (handle-projectors-reset-corner-pin event)
    
    ;; Zone group assignment
    :projectors/toggle-zone-group (handle-projectors-toggle-zone-group event)
    :projectors/set-zone-groups (handle-projectors-set-zone-groups event)
    
    ;; Tags
    :projectors/add-tag (handle-projectors-add-tag event)
    :projectors/remove-tag (handle-projectors-remove-tag event)
    
    ;; Virtual projector management
    :projectors/add-virtual-projector (handle-projectors-add-virtual-projector event)
    :projectors/select-virtual-projector (handle-projectors-select-virtual-projector event)
    :projectors/remove-virtual-projector (handle-projectors-remove-virtual-projector event)
    :projectors/update-virtual-projector (handle-projectors-update-virtual-projector event)
    :projectors/vp-toggle-enabled (handle-vp-toggle-enabled event)
    :projectors/vp-update-corner-pin (handle-vp-update-corner-pin event)
    :projectors/vp-reset-corner-pin (handle-vp-reset-corner-pin event)
    :projectors/vp-toggle-zone-group (handle-vp-toggle-zone-group event)
    :projectors/vp-set-zone-groups (handle-vp-set-zone-groups event)
    :projectors/vp-add-tag (handle-vp-add-tag event)
    :projectors/vp-remove-tag (handle-vp-remove-tag event)
    
    ;; Effect chain management (for color curves)
    :projectors/add-effect (handle-projectors-add-effect event)
    :projectors/remove-effect (handle-projectors-remove-effect event)
    :projectors/select-effect (handle-projectors-select-effect event)
    
    ;; Configuration
    :projectors/set-test-pattern (handle-projectors-set-test-pattern event)
    :projectors/set-broadcast-address (handle-projectors-set-broadcast-address event)
    :projectors/toggle-device-expand (handle-projectors-toggle-device-expand event)
    
    ;; Unknown event in this domain
    {}))
