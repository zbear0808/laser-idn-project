(ns laser-show.events.handlers.projector
  "Event handlers for projector configuration and effects.
   
   SIMPLIFIED ARCHITECTURE (v2):
   - Projectors have corner-pin geometry directly (no separate zones)
   - Projectors are assigned to zone groups directly
   - Virtual projectors provide alternate corner-pin with inherited color curves
   
   AUTO-DISCOVERY FLOW:
   - Network scan automatically creates projector entries (disabled by default)
   - Users enable/disable projectors via toggle controls
   - Projectors cannot be deleted once discovered (they persist)
   - 'Enable All' enables all projectors from an IP address
   
   This file handles:
   - Network device discovery (scan, auto-create projectors)
   - Projector configuration (settings, enabled state, corner-pin)
   - Zone group assignment
   - Virtual projector creation and management
   - Connection status updates
   - Test patterns"
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [laser-show.events.helpers :as h]
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


;; Deterministic Projector ID


(defn- make-projector-id
  "Create a deterministic projector ID based on host and service-id.
   This ensures the same device always gets the same ID across scans,
   preventing duplicate entries."
  [host service-id]
  (let [;; Sanitize host for keyword (replace dots with dashes)
        sanitized-host (str/replace (str host) "." "-")]
    (keyword (str "projector-" sanitized-host "-" (or service-id 0)))))


;; Projector Configuration Factory


(defn- make-projector-config
  "Create a projector configuration map with defaults.
   
   Required keys: :name, :host
   Optional keys: :port, :service-id, :service-name, :unit-id, :zone-groups, :tags, :scan-rate, :enabled?
   
   NOTE: Auto-discovered projectors default to enabled?=false.
   Manual projectors should pass enabled?=true explicitly.
   
   Throws: IllegalArgumentException if host is nil or blank"
  [{:keys [name host port service-id service-name unit-id zone-groups tags scan-rate enabled?]
    :or {port 7255 zone-groups [:all] tags #{} scan-rate 30000 enabled? false}}]
  {:pre [(and host (not (str/blank? host)))]}
  {:name name
   :host host
   :port port
   :service-id service-id
   :service-name service-name
   :unit-id unit-id
   :zone-groups zone-groups
   :tags tags
   :enabled? enabled?
   :scan-rate scan-rate
   :output-config {:color-bit-depth 8
                   :xy-bit-depth 16}
   :status {:connected? false}})


;; Network Discovery


(defn- handle-projectors-scan-network
  "Start scanning the network for IDN devices."
  [{:keys [state]}]
  (let [broadcast-addr (get-in state [:projector-ui :broadcast-address] "255.255.255.255")]
    {:state (assoc-in state [:projector-ui :scanning?] true)
     :projectors/scan {:broadcast-address broadcast-addr}}))


(defn- auto-create-projector-for-service
  "Create a projector entry for a discovered device service if it doesn't exist.
   Returns updated state. Does NOT overwrite existing projectors."
  [state device service-or-nil]
  (let [{:keys [address host-name unit-id]
         device-port :port} device
        {:keys [service-id]
         service-name :name
         :or {service-id 0}} service-or-nil
        projector-id (make-projector-id address service-id)]
    ;; Only create if projector doesn't already exist
    (if (get-in state [:projectors projector-id])
      state  ;; Already exists, don't overwrite
      (let [proj-name (or service-name
                          (when host-name (str host-name (when (pos? service-id) (str " #" service-id))))
                          (str address (when (pos? service-id) (str " #" service-id))))
            projector-config (make-projector-config
                               {:name proj-name
                                :host address
                                :port (or device-port 7255)
                                :service-id service-id
                                :service-name service-name
                                :unit-id unit-id
                                :enabled? false})]  ;; Auto-discovered = disabled by default
        (-> state
            (assoc-in [:projectors projector-id] projector-config)
            (assoc-in [:chains :projector-effects projector-id :items]
                      (mapv #(assoc % :id (random-uuid)) DEFAULT_PROJECTOR_EFFECTS)))))))


(defn- auto-create-projectors-for-device
  "Create projector entries for all services on a device.
   For devices with no services, creates one projector with service-id 0."
  [state device]
  (let [services (:services device)]
    (if (seq services)
      ;; Device has services - create one projector per service
      (reduce #(auto-create-projector-for-service %1 device %2) state services)
      ;; No services - create single projector with service-id 0
      (auto-create-projector-for-service state device nil))))


(defn- handle-projectors-scan-complete
  "Handle scan completion - update discovered devices list and auto-create projectors.
   
   Auto-creates projector entries for all discovered devices/services:
   - Uses deterministic IDs so re-scanning doesn't create duplicates
   - New projectors are disabled by default (enabled? = false)
   - Existing projectors are NOT overwritten (preserves user settings)"
  [{:keys [devices state]}]
  (let [;; Auto-create projectors for all discovered devices
        state-with-projectors (reduce auto-create-projectors-for-device state devices)
        ;; Check if any new projectors were created
        had-new-projectors? (not= (get state :projectors) (get state-with-projectors :projectors))]
    {:state (-> state-with-projectors
                (assoc-in [:projector-ui :scanning?] false)
                (assoc-in [:projector-ui :discovered-devices] (vec devices))
                (cond-> had-new-projectors? h/mark-dirty))}))


(defn- handle-projectors-scan-failed
  "Handle scan failure."
  [{:keys [error state]}]
  (log/warn "Network scan failed:" error)
  {:state (assoc-in state [:projector-ui :scanning?] false)})


;; Enable/Disable by IP Address (New auto-discovery flow)


(defn- handle-projectors-enable-all-by-ip
  "Enable all projectors matching a given IP address.
   Used by 'Enable All' button in discovery panel.
   Triggers engine refresh if streaming is currently running."
  [{:keys [address state]}]
  (let [projector-ids (->> (get state :projectors {})
                           (filter (fn [[_ p]] (= (:host p) address)))
                           (map first))
        streaming-running? (get-in state [:backend :streaming :running?])]
    (if (seq projector-ids)
      (cond-> {:state (-> (reduce #(assoc-in %1 [:projectors %2 :enabled?] true)
                                  state
                                  projector-ids)
                          h/mark-dirty)}
        ;; Trigger engine refresh if streaming is active
        streaming-running? (assoc :multi-engine/refresh true))
      {:state state})))


(defn- handle-projectors-set-service-enabled
  "Set enabled state for a projector identified by host and service-id.
   Used by enable/disable toggles in discovery panel.
   Coerces enabled? to boolean to prevent ClassCastException.
   Triggers engine refresh if streaming is currently running."
  [{:keys [host service-id enabled? state]}]
  (let [projector-id (make-projector-id host service-id)
        ;; Coerce to boolean to handle edge cases where :fx/event might not be substituted
        enabled-bool? (boolean enabled?)
        streaming-running? (get-in state [:backend :streaming :running?])]
    (if (get-in state [:projectors projector-id])
      (cond-> {:state (-> state
                          (assoc-in [:projectors projector-id :enabled?] enabled-bool?)
                          h/mark-dirty)}
        ;; Trigger engine refresh if streaming is active
        streaming-running? (assoc :multi-engine/refresh true))
      {:state state})))


;; Manual Addition (still supported for non-discovered projectors)


(defn- handle-projectors-add-manual
  "Add a projector manually (not from discovery).
   Manual projectors are enabled by default."
  [{:keys [name host port state]}]
  (let [projector-id (keyword (str "projector-" (System/currentTimeMillis)))
        proj-name (or name host)
        projector-config (make-projector-config
                           {:name proj-name
                            :host host
                            :port (or port 7255)
                            :enabled? true})]  ;; Manual = enabled by default
    {:state (-> state
                (assoc-in [:projectors projector-id] projector-config)
                (assoc-in [:chains :projector-effects projector-id :items] (mapv #(assoc % :id (random-uuid)) DEFAULT_PROJECTOR_EFFECTS))
                (assoc-in [:projector-ui :active-projector] projector-id)
                h/mark-dirty)}))


;; Projector Management


(defn- handle-projectors-select-projector
  "Select a projector for editing."
  [{:keys [projector-id state]}]
  {:state (-> state
              (assoc-in [:projector-ui :active-projector] projector-id)
              (assoc-in [:projector-ui :active-virtual-projector] nil))})




(defn- handle-projectors-update-settings
  "Update projector settings (name, host, port, enabled, output-config).
   
   Validates that host (if provided in updates) is not nil or blank."
  [{:keys [projector-id updates state]}]
  ;; If updating host, validate it's not nil or blank
  (when (and (contains? updates :host)
             (let [new-host (:host updates)]
               (or (nil? new-host) (str/blank? new-host))))
    (throw (ex-info "Invalid host: host cannot be nil or blank"
                    {:projector-id projector-id
                     :updates updates})))
  {:state (-> state
              (update-in [:projectors projector-id] merge updates)
              h/mark-dirty)})


(defn- handle-projectors-toggle-enabled
  "Toggle a projector's enabled state.
   Coerces enabled? to boolean to prevent ClassCastException.
   Triggers engine refresh if streaming is currently running."
  [{:keys [projector-id state]}]
  (let [current (boolean (get-in state [:projectors projector-id :enabled?]))
        streaming-running? (get-in state [:backend :streaming :running?])]
    (cond-> {:state (-> state
                        (assoc-in [:projectors projector-id :enabled?] (not current))
                        h/mark-dirty)}
      ;; Trigger engine refresh if streaming is active
      streaming-running? (assoc :multi-engine/refresh true))))




;; Zone Group Assignment


(defn- handle-projectors-toggle-zone-group
  "Toggle a projector's membership in a zone group."
  [{:keys [projector-id zone-group-id state]}]
  (let [current-groups (get-in state [:projectors projector-id :zone-groups] [])
        member? (some #{zone-group-id} current-groups)
        new-groups (if member?
                     (vec (remove #{zone-group-id} current-groups))
                     (conj current-groups zone-group-id))]
    {:state (-> state
                (assoc-in [:projectors projector-id :zone-groups] new-groups)
                h/mark-dirty)}))




;; Virtual Projector Management


(defn- get-parent-corner-pin
  "Get corner-pin params from a projector's effect chain.
   Returns DEFAULT_CORNER_PIN if no corner-pin effect is found."
  [state projector-id]
  (let [effects (get-in state (projector-effects-path projector-id) [])]
    (if-let [corner-pin-effect (first (filter #(= :corner-pin (:effect-id %)) effects))]
      (:params corner-pin-effect)
      DEFAULT_CORNER_PIN)))




(defn- handle-vp-toggle-zone-group
  "Toggle a virtual projector's membership in a zone group."
  [{:keys [vp-id zone-group-id state]}]
  (let [current-groups (get-in state [:virtual-projectors vp-id :zone-groups] [])
        member? (some #{zone-group-id} current-groups)
        new-groups (if member?
                     (vec (remove #{zone-group-id} current-groups))
                     (conj current-groups zone-group-id))]
    {:state (-> state
                (assoc-in [:virtual-projectors vp-id :zone-groups] new-groups)
                h/mark-dirty)}))




;; Effect Chain Management (for color curves)


(defn- handle-projectors-add-effect
  "Add an effect to a projector's chain."
  [{:keys [projector-id effect state]}]
  (let [effect-with-fields (h/ensure-item-fields effect)]
    {:state (-> state
                (update-in [:chains :projector-effects projector-id :items] conj effect-with-fields)
                h/mark-dirty)}))




;; Calibration Mode


(defn- handle-projectors-toggle-calibration
  "Toggle calibration mode for a specific projector.
   Only one projector can be in calibration mode at a time."
  [{:keys [projector-id state]}]
  (let [current (get-in state [:projector-ui :calibrating-projector-id])]
    {:state (assoc-in state [:projector-ui :calibrating-projector-id]
                      (if (= current projector-id) nil projector-id))}))


(defn- handle-projectors-set-calibration-brightness
  "Set the brightness for calibration test pattern."
  [{:keys [brightness state]}]
  {:state (assoc-in state [:projector-ui :calibration-brightness]
                    (max 0.05 (min 0.5 (double brightness))))})




(defn- handle-projectors-toggle-device-expand
  "Toggle the expanded state of a device in the discovery panel."
  [{:keys [address state]}]
  (let [current-expanded (get-in state [:projector-ui :expanded-devices] #{})]
    {:state (assoc-in state [:projector-ui :expanded-devices]
                      (if (contains? current-expanded address)
                        (disj current-expanded address)
                        (conj current-expanded address)))}))


;; Public API


(defn handle
  "Dispatch projector events to their handlers.
   
   Accepts events with :event/type in the :projectors/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    ;; Network discovery (auto-creates projectors on scan complete)
    :projectors/scan-network (handle-projectors-scan-network event)
    :projectors/scan-complete (handle-projectors-scan-complete event)
    :projectors/scan-failed (handle-projectors-scan-failed event)
    
    ;; Enable/disable projectors (new auto-discovery flow)
    :projectors/enable-all-by-ip (handle-projectors-enable-all-by-ip event)
    :projectors/set-service-enabled (handle-projectors-set-service-enabled event)
    :projectors/add-manual (handle-projectors-add-manual event)
    
    ;; Projector management
    :projectors/select-projector (handle-projectors-select-projector event)
    :projectors/update-settings (handle-projectors-update-settings event)
    :projectors/toggle-enabled (handle-projectors-toggle-enabled event)
    
    ;; Zone group assignment
    :projectors/toggle-zone-group (handle-projectors-toggle-zone-group event)
    
    ;; Virtual projector management
    :projectors/vp-toggle-zone-group (handle-vp-toggle-zone-group event)
    
    ;; Effect chain management (for color curves)
    :projectors/add-effect (handle-projectors-add-effect event)
    
    ;; Calibration mode
    :projectors/toggle-calibration (handle-projectors-toggle-calibration event)
    :projectors/set-calibration-brightness (handle-projectors-set-calibration-brightness event)
    
    ;; Configuration
    :projectors/toggle-device-expand (handle-projectors-toggle-device-expand event)
    
    ;; Unknown event in this domain
    {}))
