(ns laser-show.events.handlers.projector
  "Event handlers for projector configuration and effects.
   
   Handles:
   - Network device discovery (scan, add devices/services)
   - Projector configuration (settings, enabled state)
   - Effect chain management (add, remove, reorder, enable/disable)
   - Parameter updates (sliders, text input, spatial, curves)
   - Calibration effects (RGB curves, corner pin)
   - Selection and clipboard operations
   - Group management
   - Test patterns and broadcast address configuration
   - Connection status updates"
  (:require [clojure.tools.logging :as log]
            [laser-show.events.helpers :as h]
            [laser-show.events.handlers.chain :as chain-handlers]
            [laser-show.animation.chains :as chains]))


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


;; Device/Service Addition


(defn- handle-projectors-add-device
  "Add a discovered device as a configured projector.
   If device has services, adds the default service (or first one).
   For multi-output devices, use :projectors/add-service instead."
  [{:keys [device state]}]
  (let [host (:address device)
        services (:services device [])
        ;; Use default service if available, otherwise first, otherwise nil
        default-service (or (first (filter #(get-in % [:flags :default-service]) services))
                            (first services))
        service-id (if default-service (:service-id default-service) 0)
        service-name (when default-service (:name default-service))
        host-name (:host-name device)
        ;; Prefer service name, then host name, then IP address
        name (or service-name host-name host)
        projector-id (keyword (str "projector-" (System/currentTimeMillis)))
        ;; Default effect chain with color calibration and corner pin
        default-effects [{:effect-id :rgb-curves
                          :id (random-uuid)
                          :enabled? true
                          :params {:r-curve-points [[0 0] [255 255]]
                                   :g-curve-points [[0 0] [255 255]]
                                   :b-curve-points [[0 0] [255 255]]}}
                         {:effect-id :corner-pin
                          :id (random-uuid)
                          :enabled? true
                          :params {:tl-x -1.0 :tl-y 1.0
                                   :tr-x 1.0 :tr-y 1.0
                                   :bl-x -1.0 :bl-y -1.0
                                   :br-x 1.0 :br-y -1.0}}]
        projector-config {:name name
                          :host host
                          :port (:port device 7255)
                          :service-id service-id
                          :service-name service-name
                          :unit-id (:unit-id device)
                          :enabled? true
                          :output-config {:color-bit-depth 8
                                          :xy-bit-depth 16}
                          :status {:connected? false}}]
    {:state (-> state
                (assoc-in [:projectors :items projector-id] projector-config)
                (assoc-in [:chains :projector-effects projector-id :items] default-effects)
                (assoc-in [:projectors :active-projector] projector-id)
                h/mark-dirty)}))

(defn- handle-projectors-add-service
  "Add a specific service/output from a discovered device as a projector.
   Used when a device has multiple outputs and user wants to add a specific one."
  [{:keys [device service state]}]
  (let [host (:address device)
        service-id (:service-id service)
        service-name (:name service)
        host-name (:host-name device)
        ;; Prefer service name, then host name, then IP address
        name (or service-name host-name host)
        projector-id (keyword (str "projector-" (System/currentTimeMillis) "-" service-id))
        ;; Default effect chain with color calibration and corner pin
        default-effects [{:effect-id :rgb-curves
                          :id (random-uuid)
                          :enabled? true
                          :params {:r-curve-points [[0 0] [255 255]]
                                   :g-curve-points [[0 0] [255 255]]
                                   :b-curve-points [[0 0] [255 255]]}}
                         {:effect-id :corner-pin
                          :id (random-uuid)
                          :enabled? true
                          :params {:tl-x -1.0 :tl-y 1.0
                                   :tr-x 1.0 :tr-y 1.0
                                   :bl-x -1.0 :bl-y -1.0
                                   :br-x 1.0 :br-y -1.0}}]
        projector-config {:name name
                          :host host
                          :port (:port device 7255)
                          :service-id service-id
                          :service-name service-name
                          :unit-id (:unit-id device)
                          :enabled? true
                          :output-config {:color-bit-depth 8
                                          :xy-bit-depth 16}
                          :status {:connected? false}}]
    {:state (-> state
                (assoc-in [:projectors :items projector-id] projector-config)
                (assoc-in [:chains :projector-effects projector-id :items] default-effects)
                (assoc-in [:projectors :active-projector] projector-id)
                h/mark-dirty)}))

(defn- handle-projectors-add-all-services
  "Add all services from a discovered device as configured projectors."
  [{:keys [device state]}]
  (let [host (:address device)
        services (:services device [])
        host-name (:host-name device)
        now (System/currentTimeMillis)
        ;; Build projector configs and effect chains separately
        projector-data (reduce
                        (fn [acc [idx service]]
                          (let [service-id (:service-id service)
                                service-name (:name service)
                                ;; Prefer service name, then host name, then IP address
                                name (or service-name host-name host)
                                projector-id (keyword (str "projector-" now "-" service-id "-" idx))
                                default-effects [{:effect-id :rgb-curves
                                                  :id (random-uuid)
                                                  :enabled? true
                                                  :params {:r-curve-points [[0 0] [255 255]]
                                                           :g-curve-points [[0 0] [255 255]]
                                                           :b-curve-points [[0 0] [255 255]]}}
                                                 {:effect-id :corner-pin
                                                  :id (random-uuid)
                                                  :enabled? true
                                                  :params {:tl-x -1.0 :tl-y 1.0
                                                           :tr-x 1.0 :tr-y 1.0
                                                           :bl-x -1.0 :bl-y -1.0
                                                           :br-x 1.0 :br-y -1.0}}]
                                projector-config {:name name
                                                  :host host
                                                  :port (:port device 7255)
                                                  :service-id service-id
                                                  :service-name service-name
                                                  :unit-id (:unit-id device)
                                                  :enabled? true
                                                  :output-config {:color-bit-depth 8
                                                                  :xy-bit-depth 16}
                                                  :status {:connected? false}}]
                            (-> acc
                                (assoc-in [:projectors projector-id] projector-config)
                                ;; Wrap effects in {:items ...} to match expected structure
                                (assoc-in [:effects projector-id] {:items default-effects}))))
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
        default-effects [{:effect-id :rgb-curves
                          :id (random-uuid)
                          :enabled? true
                          :params {:r-curve-points [[0 0] [255 255]]
                                   :g-curve-points [[0 0] [255 255]]
                                   :b-curve-points [[0 0] [255 255]]}}
                         {:effect-id :corner-pin
                          :id (random-uuid)
                          :enabled? true
                          :params {:tl-x -1.0 :tl-y 1.0
                                   :tr-x 1.0 :tr-y 1.0
                                   :bl-x -1.0 :bl-y -1.0
                                   :br-x 1.0 :br-y -1.0}}]
        projector-config {:name (or name host)
                          :host host
                          :port (or port 7255)
                          :unit-id nil
                          :enabled? true
                          :output-config {:color-bit-depth 8
                                          :xy-bit-depth 16}
                          :status {:connected? false}}]
    {:state (-> state
                (assoc-in [:projectors :items projector-id] projector-config)
                (assoc-in [:chains :projector-effects projector-id :items] default-effects)
                (assoc-in [:projectors :active-projector] projector-id)
                h/mark-dirty)}))


;; Projector Management


(defn- handle-projectors-select-projector
  "Select a projector for editing."
  [{:keys [projector-id state]}]
  {:state (assoc-in state [:projectors :active-projector] projector-id)})

(defn- handle-projectors-remove-projector
  "Remove a projector configuration."
  [{:keys [projector-id state]}]
  (let [active (get-in state [:projectors :active-projector])
        items (get-in state [:projectors :items])
        new-items (dissoc items projector-id)
        ;; If removing active projector, select first remaining
        new-active (if (= active projector-id)
                     (first (keys new-items))
                     active)]
    {:state (-> state
                (assoc-in [:projectors :items] new-items)
                (assoc-in [:projectors :active-projector] new-active)
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


;; Effect Chain Management


(defn- handle-projectors-add-effect
  "Add an effect to a projector's chain."
  [{:keys [projector-id effect state]}]
  (let [effect-with-fields (cond-> effect
                             (not (contains? effect :enabled?))
                             (assoc :enabled? true)
                             (not (contains? effect :id))
                             (assoc :id (random-uuid)))]
    {:state (-> state
                (update-in [:chains :projector-effects projector-id :items] conj effect-with-fields)
                h/mark-dirty)}))

(defn- handle-projectors-remove-effect
  "Remove an effect from a projector's chain."
  [{:keys [projector-id effect-idx state]}]
  (let [effects-vec (get-in state [:chains :projector-effects projector-id :items] [])
        new-effects (vec (concat (subvec effects-vec 0 effect-idx)
                                 (subvec effects-vec (inc effect-idx))))]
    {:state (-> state
                (assoc-in [:chains :projector-effects projector-id :items] new-effects)
                h/mark-dirty)}))

(defn- handle-projectors-update-effect-param
  "Update a parameter in a projector's effect."
  [{:keys [projector-id effect-idx param-key state] :as event}]
  (let [value (or (:fx/event event) (:value event))]
    {:state (assoc-in state
                      [:chains :projector-effects projector-id :items effect-idx :params param-key]
                      value)}))

(defn- handle-projectors-reorder-effects
  "Reorder effects in a projector's chain."
  [{:keys [projector-id from-idx to-idx state]}]
  (let [effects-vec (get-in state [:chains :projector-effects projector-id :items] [])
        effect (nth effects-vec from-idx)
        without (vec (concat (subvec effects-vec 0 from-idx)
                             (subvec effects-vec (inc from-idx))))
        reordered (vec (concat (subvec without 0 to-idx)
                               [effect]
                               (subvec without to-idx)))]
    {:state (-> state
                (assoc-in [:chains :projector-effects projector-id :items] reordered)
                h/mark-dirty)}))


;; Forward declaration for path-based selection
(declare handle-projectors-select-effect-at-path)


;; Legacy Index-Based Selection


(defn- handle-projectors-select-effect
  "Select an effect in the projector's chain for editing.
   Supports both legacy index-based selection (:effect-idx) and
   new path-based selection (:path with :ctrl? and :shift? modifiers)."
  [{:keys [projector-id effect-idx path ctrl? shift? state] :as event}]
  (if path
    ;; New path-based selection (delegate to path-based handler)
    (handle-projectors-select-effect-at-path event)
    ;; Legacy index-based selection
    {:state (assoc-in state [:projectors :selected-effect-idx] effect-idx)}))


;; Calibration Effects


(defn- handle-projectors-add-calibration-effect
  "Add a calibration effect to projector chain."
  [{:keys [projector-id effect state]}]
  (let [effect-with-fields (cond-> effect
                             (not (contains? effect :enabled?))
                             (assoc :enabled? true)
                             (not (contains? effect :id))
                             (assoc :id (random-uuid)))
        current-effects (get-in state (projector-effects-path projector-id) [])
        new-effect-idx (count current-effects)
        new-effect-path [new-effect-idx]
        ui-path (projector-ui-state-path projector-id)]
    {:state (-> state
                (update-in (projector-effects-path projector-id) conj effect-with-fields)
                (assoc-in (conj ui-path :selected-paths) #{new-effect-path})
                (assoc-in (conj ui-path :last-selected-path) new-effect-path)
                h/mark-dirty)}))


;; Corner Pin


(defn- handle-projectors-update-corner-pin
  "Update corner pin parameters from spatial drag.
   Event keys:
   - :projector-id - ID of the projector
   - :effect-idx - Index of the corner pin effect
   - :point-id - Corner being dragged (:tl, :tr, :bl, :br)
   - :x, :y - New coordinates
   - :param-map - Mapping of point IDs to param keys"
  [{:keys [projector-id effect-idx point-id x y param-map state]}]
  (let [point-params (get param-map point-id)]
    (if point-params
      (let [x-key (:x point-params)
            y-key (:y point-params)]
        {:state (-> state
                    (assoc-in [:chains :projector-effects projector-id :items effect-idx :params x-key] x)
                    (assoc-in [:chains :projector-effects projector-id :items effect-idx :params y-key] y))})
      {:state state})))

(defn- handle-projectors-reset-corner-pin
  "Reset corner pin effect to default values."
  [{:keys [projector-id effect-idx state]}]
  {:state (assoc-in state [:chains :projector-effects projector-id :items effect-idx :params]
                    {:tl-x -1.0 :tl-y 1.0
                     :tr-x 1.0 :tr-y 1.0
                     :bl-x -1.0 :bl-y -1.0
                     :br-x 1.0 :br-y -1.0})})


;; Path-Based Effect Operations


(defn- handle-projectors-select-effect-at-path
  "Select an effect at a path using path-based selection (supports nested structures).
   Handles Ctrl+click (toggle) and Shift+click (range select)."
  [{:keys [projector-id path ctrl? shift? state]}]
  (let [ui-path (projector-ui-state-path projector-id)
        current-paths (get-in state (conj ui-path :selected-paths) #{})
        last-selected (get-in state (conj ui-path :last-selected-path))
        effects-vec (get-in state (projector-effects-path projector-id) [])
        new-paths (cond
                    ctrl?
                    (if (contains? current-paths path)
                      (disj current-paths path)
                      (conj current-paths path))
                    
                    shift?
                    (if last-selected
                      (let [all-paths (vec (chains/paths-in-chain effects-vec))
                            anchor-idx (.indexOf all-paths last-selected)
                            target-idx (.indexOf all-paths path)]
                        (if (and (>= anchor-idx 0) (>= target-idx 0))
                          (let [start-idx (min anchor-idx target-idx)
                                end-idx (max anchor-idx target-idx)]
                            (into #{} (subvec all-paths start-idx (inc end-idx))))
                          #{path}))
                      #{path})
                    
                    :else
                    #{path})
        update-anchor? (not shift?)]
    {:state (-> state
                (assoc-in (conj ui-path :selected-paths) new-paths)
                (cond-> update-anchor?
                  (assoc-in (conj ui-path :last-selected-path) path)))}))

(defn- handle-projectors-select-all-effects
  "Select all effects in a projector's chain."
  [{:keys [projector-id state]}]
  (let [effects-vec (get-in state (projector-effects-path projector-id) [])
        all-paths (into #{} (chains/paths-in-chain effects-vec))
        ui-path (projector-ui-state-path projector-id)]
    {:state (assoc-in state (conj ui-path :selected-paths) all-paths)}))

(defn- handle-projectors-clear-effect-selection
  "Clear effect selection for a projector."
  [{:keys [projector-id state]}]
  (let [ui-path (projector-ui-state-path projector-id)]
    {:state (-> state
                (assoc-in (conj ui-path :selected-paths) #{})
                (assoc-in (conj ui-path :last-selected-path) nil))}))


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

(defn- handle-projectors-delete-effects
  "Delete selected effects from projector chain."
  [{:keys [projector-id state]}]
  (let [ui-path (projector-ui-state-path projector-id)
        selected-paths (get-in state (conj ui-path :selected-paths) #{})
        effects-vec (get-in state (projector-effects-path projector-id) [])]
    (if (seq selected-paths)
      (let [root-paths (h/filter-to-root-paths selected-paths)
            sorted-paths (sort-by (comp - count) root-paths)
            new-effects (reduce h/remove-at-path effects-vec sorted-paths)]
        {:state (-> state
                    (assoc-in (projector-effects-path projector-id) new-effects)
                    (assoc-in (conj ui-path :selected-paths) #{})
                    h/mark-dirty)})
      {:state state})))


;; Drag and Drop


(defn- handle-projectors-start-effect-drag
  "Start a multi-drag operation for projector effects."
  [{:keys [projector-id initiating-path state]}]
  (let [ui-path (projector-ui-state-path projector-id)
        selected-paths (get-in state (conj ui-path :selected-paths) #{})
        dragging-paths (if (contains? selected-paths initiating-path)
                         selected-paths
                         #{initiating-path})
        new-selected (if (contains? selected-paths initiating-path)
                       selected-paths
                       #{initiating-path})]
    {:state (-> state
                (assoc-in (conj ui-path :dragging-paths) dragging-paths)
                (assoc-in (conj ui-path :selected-paths) new-selected))}))

(defn- handle-projectors-move-effects
  "Move multiple effects to a new position in projector chain.
   Uses the centralized chains/move-items-to-target for correct ordering."
  [{:keys [projector-id target-id drop-position state]}]
  (let [ui-path (projector-ui-state-path projector-id)
        effects-vec (get-in state (projector-effects-path projector-id) [])
        dragging-paths (get-in state (conj ui-path :dragging-paths) #{})
        root-paths (h/filter-to-root-paths dragging-paths)
        target-path (chains/find-path-by-id effects-vec target-id)
        target-in-drag? (or (contains? root-paths target-path)
                           (some #(h/path-is-ancestor? % target-path) root-paths))]
    (if (or (empty? root-paths) target-in-drag?)
      {:state (assoc-in state (conj ui-path :dragging-paths) nil)}
      ;; Use centralized move-items-to-target for correct ordering
      (let [new-effects (chains/move-items-to-target effects-vec root-paths target-id drop-position)]
        {:state (-> state
                    (assoc-in (projector-effects-path projector-id) new-effects)
                    (assoc-in (conj ui-path :dragging-paths) nil)
                    (assoc-in (conj ui-path :selected-paths) #{})
                    h/mark-dirty)}))))

(defn- handle-projectors-update-effect-ui-state
  "Update projector effect UI state (for drag-and-drop feedback)."
  [{:keys [projector-id updates state]}]
  (let [ui-path (projector-ui-state-path projector-id)]
    {:state (update-in state ui-path merge updates)}))


;; Group Management (via chain-handlers)


(defn- handle-projectors-group-effects
  "Group selected effects in projector chain."
  [{:keys [projector-id name state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-group-selected state config name)}))

(defn- handle-projectors-ungroup-effects
  "Ungroup a group in projector chain."
  [{:keys [projector-id path state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-ungroup state config path)}))

(defn- handle-projectors-create-effect-group
  "Create empty group in projector chain."
  [{:keys [projector-id state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-create-empty-group state config)}))

(defn- handle-projectors-toggle-effect-group-collapse
  "Toggle collapse state of a group in projector chain."
  [{:keys [projector-id path state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-toggle-collapse state config path)}))

(defn- handle-projectors-start-rename-effect-group
  "Start renaming a group in projector chain."
  [{:keys [projector-id path state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-start-rename state config path)}))

(defn- handle-projectors-rename-effect-group
  "Rename a group in projector chain."
  [{:keys [projector-id path name state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-rename-item state config path name)}))

(defn- handle-projectors-cancel-rename-effect-group
  "Cancel renaming a group in projector chain."
  [{:keys [projector-id state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-cancel-rename state config)}))


;; Effect Parameters (Path-Based)


(defn- handle-projectors-set-effect-enabled-at-path
  "Set enabled state of an effect at a path in projector chain."
  [{:keys [projector-id path enabled? state] :as event}]
  (let [enabled-val (if (contains? event :fx/event) (:fx/event event) enabled?)
        path-vec (vec path)]
    {:state (-> state
                (assoc-in (into (projector-effects-path projector-id) (conj path-vec :enabled?)) enabled-val)
                h/mark-dirty)}))

(defn- handle-projectors-update-effect-param-at-path
  "Update a parameter in a projector effect using path-based addressing."
  [{:keys [projector-id effect-path param-key state] :as event}]
  (let [value (or (:fx/event event) (:value event))
        effects-vec (vec (get-in state (projector-effects-path projector-id) []))
        updated-effects (assoc-in effects-vec (conj (vec effect-path) :params param-key) value)]
    {:state (assoc-in state (projector-effects-path projector-id) updated-effects)}))

(defn- handle-projectors-update-effect-param-from-text
  "Update a projector effect parameter from text input."
  [{:keys [projector-id effect-path param-key min max state] :as event}]
  (let [action-event (:fx/event event)
        text-field (.getSource action-event)
        text (.getText text-field)
        parsed (try (Double/parseDouble text) (catch Exception _ nil))]
    (if parsed
      (let [clamped (-> parsed (clojure.core/max min) (clojure.core/min max))
            effects-vec (vec (get-in state (projector-effects-path projector-id) []))
            updated-effects (assoc-in effects-vec (conj (vec effect-path) :params param-key) clamped)]
        {:state (assoc-in state (projector-effects-path projector-id) updated-effects)})
      {:state state})))

(defn- handle-projectors-set-effect-ui-mode
  "Set UI mode for a projector effect's parameters."
  [{:keys [projector-id effect-path mode state]}]
  (let [ui-path (projector-ui-state-path projector-id)]
    {:state (assoc-in state (conj ui-path :ui-modes effect-path) mode)}))


;; Curve Editor


(defn- handle-projectors-add-curve-point
  "Add a new control point to a projector's curve.
   Event keys:
   - :projector-id - ID of the projector
   - :effect-path - Path to effect (supports nested)
   - :channel - Channel keyword (:r, :g, or :b)
   - :x, :y - Point coordinates"
  [{:keys [projector-id effect-path channel x y state]}]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        effects-vec (vec (get-in state (projector-effects-path projector-id) []))
        current-points (get-in effects-vec (conj (vec effect-path) :params param-key) [[0 0] [255 255]])
        new-point [(int x) (int y)]
        new-points (->> (conj current-points new-point)
                        (sort-by first)
                        vec)
        updated-effects (assoc-in effects-vec (conj (vec effect-path) :params param-key) new-points)]
    {:state (assoc-in state (projector-effects-path projector-id) updated-effects)}))

(defn- handle-projectors-update-curve-point
  "Update a control point in a projector's curve.
   Event keys:
   - :projector-id - ID of the projector
   - :effect-path - Path to effect (supports nested)
   - :channel - Channel keyword (:r, :g, or :b)
   - :point-idx - Index of the point to update
   - :x, :y - New coordinates"
  [{:keys [projector-id effect-path channel point-idx x y state]}]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        effects-vec (vec (get-in state (projector-effects-path projector-id) []))
        current-points (get-in effects-vec (conj (vec effect-path) :params param-key) [[0 0] [255 255]])
        num-points (count current-points)
        ;; Corner points (first and last) can only move in Y
        is-corner? (or (= point-idx 0) (= point-idx (dec num-points)))
        current-point (nth current-points point-idx [0 0])
        updated-point (if is-corner?
                        [(first current-point) (int y)]  ;; Keep original X for corners
                        [(int x) (int y)])
        updated-points (assoc current-points point-idx updated-point)
        sorted-points (->> updated-points
                          (sort-by first)
                          vec)
        updated-effects (assoc-in effects-vec (conj (vec effect-path) :params param-key) sorted-points)]
    {:state (assoc-in state (projector-effects-path projector-id) updated-effects)}))

(defn- handle-projectors-remove-curve-point
  "Remove a control point from a projector's curve.
   Event keys:
   - :projector-id - ID of the projector
   - :effect-path - Path to effect (supports nested)
   - :channel - Channel keyword (:r, :g, or :b)
   - :point-idx - Index of the point to remove"
  [{:keys [projector-id effect-path channel point-idx state]}]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        effects-vec (vec (get-in state (projector-effects-path projector-id) []))
        current-points (get-in effects-vec (conj (vec effect-path) :params param-key) [[0 0] [255 255]])
        num-points (count current-points)
        ;; Cannot remove corner points (first and last)
        is-corner? (or (= point-idx 0) (= point-idx (dec num-points)))]
    (if is-corner?
      {:state state}  ;; No change for corner points
      (let [updated-points (vec (concat (subvec current-points 0 point-idx)
                                        (subvec current-points (inc point-idx))))
            updated-effects (assoc-in effects-vec (conj (vec effect-path) :params param-key) updated-points)]
        {:state (assoc-in state (projector-effects-path projector-id) updated-effects)}))))

(defn- handle-projectors-set-active-curve-channel
  "Set the active curve channel (R/G/B) for the projector curve editor.
   Event keys:
   - :projector-id - ID of the projector
   - :effect-path - Path to effect
   - :tab-id - Channel keyword (:r, :g, or :b)"
  [{:keys [projector-id effect-path tab-id state]}]
  (log/debug "[CURVE TAB DEBUG] handle-projectors-set-active-curve-channel called:"
             "projector-id=" projector-id
             "effect-path=" effect-path
             "tab-id=" tab-id)
  (let [ui-path (projector-ui-state-path projector-id)
        full-path (conj ui-path :ui-modes effect-path :active-curve-channel)]
    (log/debug "[CURVE TAB DEBUG] Storing at path:" full-path)
    {:state (assoc-in state full-path tab-id)}))


;; Hierarchical List Integration


(defn- handle-projectors-set-effects
  "Set the entire effects chain for a projector (simple persistence callback).
   Called by hierarchical-list component's :on-items-changed callback.
   Event keys:
   - :projector-id - ID of the projector
   - :effects - New effects vector"
  [{:keys [projector-id effects state]}]
  {:state (-> state
              (assoc-in (projector-effects-path projector-id) effects)
              h/mark-dirty)})

(defn- handle-projectors-update-effect-selection
  "Update the selection state for projector effects.
   Called by hierarchical-list component's :on-selection-changed callback.
   Event keys:
   - :projector-id - ID of the projector
   - :selected-ids - Set of selected item IDs"
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
  "Toggle the expanded state of a device in the discovery panel.
   Used to show/hide the service list for multi-output devices."
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
    
    ;; Effect chain management
    :projectors/add-effect (handle-projectors-add-effect event)
    :projectors/remove-effect (handle-projectors-remove-effect event)
    :projectors/update-effect-param (handle-projectors-update-effect-param event)
    :projectors/reorder-effects (handle-projectors-reorder-effects event)
    
    ;; Legacy selection
    :projectors/select-effect (handle-projectors-select-effect event)
    
    ;; Calibration
    :projectors/add-calibration-effect (handle-projectors-add-calibration-effect event)
    :projectors/update-corner-pin (handle-projectors-update-corner-pin event)
    :projectors/reset-corner-pin (handle-projectors-reset-corner-pin event)
    
    ;; Path-based selection
    :projectors/select-all-effects (handle-projectors-select-all-effects event)
    :projectors/clear-effect-selection (handle-projectors-clear-effect-selection event)
    
    ;; Clipboard
    :projectors/copy-effects (handle-projectors-copy-effects event)
    :projectors/paste-effects (handle-projectors-paste-effects event)
    :projectors/insert-pasted-effects (handle-projectors-insert-pasted-effects event)
    :projectors/delete-effects (handle-projectors-delete-effects event)
    
    ;; Drag and drop
    :projectors/start-effect-drag (handle-projectors-start-effect-drag event)
    :projectors/move-effects (handle-projectors-move-effects event)
    :projectors/update-effect-ui-state (handle-projectors-update-effect-ui-state event)
    
    ;; Groups
    :projectors/group-effects (handle-projectors-group-effects event)
    :projectors/ungroup-effects (handle-projectors-ungroup-effects event)
    :projectors/create-effect-group (handle-projectors-create-effect-group event)
    :projectors/toggle-effect-group-collapse (handle-projectors-toggle-effect-group-collapse event)
    :projectors/start-rename-effect-group (handle-projectors-start-rename-effect-group event)
    :projectors/rename-effect-group (handle-projectors-rename-effect-group event)
    :projectors/cancel-rename-effect-group (handle-projectors-cancel-rename-effect-group event)
    
    ;; Effect parameters
    :projectors/set-effect-enabled-at-path (handle-projectors-set-effect-enabled-at-path event)
    :projectors/update-effect-param-at-path (handle-projectors-update-effect-param-at-path event)
    :projectors/update-effect-param-from-text (handle-projectors-update-effect-param-from-text event)
    :projectors/set-effect-ui-mode (handle-projectors-set-effect-ui-mode event)
    
    ;; Curve editor
    :projectors/add-curve-point (handle-projectors-add-curve-point event)
    :projectors/update-curve-point (handle-projectors-update-curve-point event)
    :projectors/remove-curve-point (handle-projectors-remove-curve-point event)
    :projectors/set-active-curve-channel (handle-projectors-set-active-curve-channel event)
    
    ;; Hierarchical list integration
    :projectors/set-effects (handle-projectors-set-effects event)
    :projectors/update-effect-selection (handle-projectors-update-effect-selection event)
    
    ;; Configuration
    :projectors/set-test-pattern (handle-projectors-set-test-pattern event)
    :projectors/set-broadcast-address (handle-projectors-set-broadcast-address event)
    :projectors/toggle-device-expand (handle-projectors-toggle-device-expand event)
    
    ;; Unknown event in this domain
    {}))
