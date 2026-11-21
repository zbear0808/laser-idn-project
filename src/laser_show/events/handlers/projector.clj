(ns laser-show.events.handlers.projector
  "Event handlers for projector configuration and effects.
   
   SIMPLIFIED ARCHITECTURE (v2):
   - Projectors have corner-pin geometry directly (no separate zones)
   - Projectors are assigned to zone groups directly
   - Virtual projectors provide alternate corner-pin with inherited color curves
   
   AUTO-DISCOVERY FLOW:
   - A scan matches each output it finds to a projector by its stable
     id (routing.rig/match-discovered): a known unit and output keeps its id
     and setup when its address changes (it moves); anything else becomes a
     new projector, disabled by default
   - Users enable/disable projectors via toggle controls
   - A projector can be removed (:projectors/remove, after a confirm); a later
     scan finds the device again, turned off, with default calibration
   - The card menu can enable every output on one device
   
   This file handles:
   - Output device discovery (scan, auto-create projectors)
   - Projector configuration (settings, enabled state, corner-pin)
   - Zone group assignment
   - Virtual projector creation and management
   - Connection status updates
   - Test patterns"
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [laser-show.events.handlers.zone-groups :as zone-groups]
            [laser-show.events.helpers :as h]
            [laser-show.idn.stream :as stream]
            [laser-show.animation.fit-guard :as fit-guard]
            [laser-show.animation.safety :as safety]
            [laser-show.animation.test-pattern :as test-pattern]
            [laser-show.events.safety-guard :as guard]
            [laser-show.output.driver :as driver]
            [laser-show.routing.refs :as refs]
            [laser-show.routing.rig :as rig]))


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


;; Projector Configuration Factory


(defn- make-projector-config
  "Create a projector configuration map with defaults.
   
   Required keys: :name, and :host for IDN (no :driver, or :driver :idn)
   Optional keys: :port, :service-id, :service-name, :unit-id, :stable-id, :zone-groups, :tags, :scan-rate, :enabled?
   Without :stable-id, it is the one routing.rig/stable-id gives. A new
   projector lists no group and plays All (:exclude-from-all? false).
   
   Any other driver (plans/2026-11-19-output-drivers/01 §5.1): :driver,
   :driver-config (filled in by Add Manual, as reported by Scan) and :stable-id
   (required), and no :host, :port, :service-id or :output-config.
   
   NOTE: Auto-discovered projectors default to enabled?=false.
   Manual projectors should pass enabled?=true explicitly.
   
   Throws: IllegalArgumentException if an IDN projector's host is nil or blank"
  [{:keys [name host port service-id service-name unit-id stable-id zone-groups tags scan-rate enabled?
           driver driver-config]
    :or {port 7255 zone-groups [] tags #{} scan-rate stream/DEFAULT_SCAN_RATE enabled? false}}]
  {:pre [(or (and (keyword? driver) (not= :idn driver) (vector? stable-id))
             (and host (not (str/blank? host))))]}
  (if (and driver (not= :idn driver))
    {:name name
     :driver driver
     :driver-config driver-config
     :stable-id stable-id
     :zone-groups zone-groups
     :exclude-from-all? false
     :tags tags
     :enabled? enabled?
     :scan-rate scan-rate}
    {:name name
     :host host
     :port port
     :service-id service-id
     :service-name service-name
     :unit-id unit-id
     :stable-id (or stable-id (rig/stable-id {:unit-id unit-id :service-id service-id :host host}))
     :zone-groups zone-groups
     :exclude-from-all? false
     :tags tags
     :enabled? enabled?
     :scan-rate scan-rate
     :output-config {:color-bit-depth 8
                     :xy-bit-depth 16}}))


;; Network Discovery


(defn- handle-projectors-scan-network
  "Start a scan (Scan): IDN's discovery and every output driver's
   :discover (the :projectors/scan effect)."
  [{:keys [state]}]
  (let [broadcast-addr (get-in state [:projector-ui :broadcast-address] "255.255.255.255")]
    {:state (assoc-in state [:projector-ui :scanning?] true)
     :projectors/scan {:broadcast-address broadcast-addr}}))


(defn- descriptor-of
  "The descriptor of driver id in state ([:backend :output :drivers]), nil
   when it isn't installed."
  [state id]
  (get-in state [:backend :output :drivers id]))

(defn- driver-output-name
  "What a new projector for output of another driver's device is called:
   the device's name (else its key), and with more outputs the output's
   name (else \"Output <id>\") after it: \"Nova 00123\", \"Nova 7 · Output A\"."
  [{:keys [device-key outputs] device-name :name} {:keys [output-id] output-name :name}]
  (let [base (if (and (string? device-name) (not (str/blank? device-name))) device-name (str device-key))]
    (if (< 1 (count outputs))
      (str base " · " (if (and (string? output-name) (not (str/blank? output-name))) output-name (str "Output " output-id)))
      base)))

(defn- discovered-driver-projector
  "A new projector for output of another driver's device a scan found
   (01 §5.1): disabled, with the output's config, its stable device id,
   and the driver's default scan rate."
  [state {:keys [device output stable-id]}]
  (let [driver (:driver device)
        descriptor (descriptor-of state driver)]
    (make-projector-config
      {:name (driver-output-name device output)
       :driver driver
       :driver-config (:driver-config output)
       :stable-id stable-id
       :scan-rate (driver/default-scan-rate descriptor)
       :enabled? false})))

(defn- discovered-idn-projector
  "A new projector for an IDN output a scan found: disabled, named after
   the output or the device."
  [device service stable-id]
  (let [{:keys [address host-name unit-id] device-port :port} device
        {:keys [service-id] service-name :name :or {service-id 0}} service]
    (make-projector-config
      {:name (or service-name
                 (when host-name (str host-name (when (pos? service-id) (str " #" service-id))))
                 (str address (when (pos? service-id) (str " #" service-id))))
       :host address
       :port (or device-port 7255)
       :service-id service-id
       :service-name service-name
       :unit-id unit-id
       :stable-id stable-id
       :enabled? false})))

(defn- discovered-projector
  "A new projector for an output a scan found (one of match-discovered's
   :new): an IDN one, or another driver's (discovered-driver-projector)."
  [state {:keys [device service stable-id] :as found}]
  (if (= :idn (:driver device :idn))
    (discovered-idn-projector device service stable-id)
    (discovered-driver-projector state found)))

(defn- with-identity
  "p with the stable id a scan found, and that unit id when it is a unit's."
  [p [kind v :as sid]]
  (cond-> (assoc p :stable-id sid)
    (and (= :unit kind) (not= v (second (rig/stable-id p)))) (assoc :unit-id v)))

(defn- found-in-scan
  "(fn [document] document) that puts what a scan found into a document:
   the new projectors and their calibration, the moves (host and port, or
   another driver's :driver-config) and the identities. A projector the
   document doesn't have is left out of the moves and identities."
  [new-projectors new-effects moves upgrades]
  (let [present (fn [f m] (fn [ps] (reduce-kv (fn [ps id v] (if (contains? ps id) (update ps id f v) ps)) ps m)))
        move (present merge moves)
        upgrade (present with-identity upgrades)]
    (fn [doc]
      (-> doc
          (update :projectors #(-> (merge new-projectors %) move upgrade))
          (update-in [:chains :projector-effects] #(merge new-effects %))))))

(defn- handle-projectors-scan-complete
  "Handle scan completion: the discovered devices list, and each output found
   matched to a projector by its stable id (routing.rig/match-discovered):
   - a known unit and output at a new address moves there (host and port), and
     keeps its id, name, calibration, groups and place; a notice says so;
   - a known output of another driver's device whose :driver-config changed
     moves the same way (only its connection config is replaced);
   - a projector added by hand, or known by its address, takes the unit's
     identity;
   - any other output is a new projector, disabled, with a free id and the
     default calibration (another driver's: driver-projector-id, plans/
     2026-11-19-output-drivers/01 §5.3).
   :warnings (a discoverer that failed or timed out, a device left out) show
   as one :warning notice, after any move's.
   
   Discovery isn't a user edit: all of it is backfilled into undo history and
   the saved baseline, so it is never undone and never counts as an unsaved
   change."
  [{:keys [devices warnings state]}]
  (let [{:keys [moves upgrades new notices]} (rig/match-discovered (:projectors state) devices)
        new-projectors (into {} (map (fn [output] [(:id output) (discovered-projector state output)])) new)
        new-effects (into {} (map (fn [id] [id {:items (mapv #(assoc % :id (random-uuid)) DEFAULT_PROJECTOR_EFFECTS)}]))
                          (keys new-projectors))
        found (found-in-scan new-projectors new-effects moves upgrades)
        new-state (-> (found state)
                      (assoc-in [:projector-ui :scanning?] false)
                      (assoc-in [:projector-ui :discovered-devices] (vec devices))
                      (assoc-in [:rig-ui :scanned?] true))]
    (cond-> (cond
              (seq warnings) (h/show-notice new-state (str/join " " (conj (vec notices) (str/join "; " warnings))) :warning)
              (seq notices) (h/show-notice new-state (str/join " " notices) :info)
              :else {:state new-state})
      (or (seq new-projectors) (seq moves) (seq upgrades))
      (assoc :history/backfill found))))


(defn- handle-projectors-scan-failed
  "Handle scan failure: Scan is back, and a notice says why."
  [{:keys [error state]}]
  (log/warn "Network scan failed:" error)
  (h/show-notice (assoc-in state [:projector-ui :scanning?] false)
                 (str "Could not scan the network" (when-not (str/blank? error) (str ": " error)))
                 :error))


;; Enable one or all outputs of a device


(defn- handle-projectors-enable-all-by-ip
  "Turn on the IDN outputs of the given host."
  [{:keys [address state]}]
  (let [projector-ids (->> (get state :projectors {})
                           (filter (fn [[_ p]] (and address (= :idn (driver/projector-driver-id p)) (= (:host p) address))))
                           (map first))]
    {:state (reduce #(assoc-in %1 [:projectors %2 :enabled?] true)
                    state
                    projector-ids)}))


(defn- handle-projectors-enable-device
  "Turn every output of another driver's device on or off (:enabled? true
   or false), from the card menu's \"Turn on every output of …\": the
   projectors whose stable id is [:device driver device-key …]. Any other
   :enabled?, or a device with none, changes nothing."
  [{:keys [driver device-key enabled? state]}]
  {:state (if (boolean? enabled?)
            (reduce-kv (fn [s id p]
                         (if (= [driver device-key] (some-> (rig/output-key p) pop))
                           (assoc-in s [:projectors id :enabled?] enabled?) s))
                       state (:projectors state))
            state)})


(defn- handle-projectors-set-service-enabled
  "Set enabled state for the projector at host with service-id (0 for none),
   found by its address and output, so a laser that moved is found at its
   new address."
  [{:keys [host service-id enabled? state]}]
  (let [service (or service-id 0)
        projector-id (some (fn [[id p]] (when (= [host service] (rig/output-key p)) id))
                           (sort-by (comp str key) (:projectors state)))]
    (if projector-id
      {:state (assoc-in state [:projectors projector-id :enabled?] enabled?)}
      {:state state})))


;; Manual Addition (still supported for non-discovered projectors)


(defn- added
  "state with projector added at projector-id, selected, with the default
   calibration, and the Add Projector dialog closed and cleared."
  [state projector-id projector]
  (-> state
      (assoc-in [:projectors projector-id] projector)
      (assoc-in [:chains :projector-effects projector-id :items] (mapv #(assoc % :id (random-uuid)) DEFAULT_PROJECTOR_EFFECTS))
      (assoc-in [:projector-ui :active-projector] projector-id)
      (assoc-in [:ui :dialogs :add-projector-manual] {:open? false})))

(defn- handle-projectors-add-manual
  "Add a projector manually (not from discovery).
   Manual projectors are enabled by default. The Add Projector dialog
   closes, and opens blank next time: a second click can't add it twice.
   
   With :driver other than :idn (plans/2026-11-19-output-drivers/01 §5.1),
   :driver-config is the typed field values, checked by driver/coerce-config
   against the driver's descriptor: the defaults fill in, and with any
   error, or a driver that isn't installed, nothing is added. Its stable id
   is [:manual driver projector-id] (01 §5.3), so a scan never takes it for
   another laser, and it starts at the driver's default scan rate. An
   unnamed one is its driver's short name and a number
   (driver/default-projector-name: \"LSPS 1\"), so its card doesn't say its
   settings twice; an unnamed IDN one is named after its host."
  [{:keys [name host port driver driver-config state]}]
  (let [projector-id (keyword (str "projector-" (System/currentTimeMillis)))]
    (if (and driver (not= :idn driver))
      (let [descriptor (descriptor-of state driver)
            {:keys [config errors]} (driver/coerce-config (:fields descriptor) driver-config)]
        (if (or (nil? descriptor) (seq errors))
          {:state state}
          (let [projector (make-projector-config
                            {:name name
                             :driver driver
                             :driver-config (merge (driver/field-defaults (:fields descriptor)) config)
                             :stable-id (rig/manual-stable-id driver projector-id)
                             :scan-rate (driver/default-scan-rate descriptor)
                             :enabled? true})
                projector (cond-> projector
                            (or (not (string? name)) (str/blank? name))
                            (assoc :name (driver/default-projector-name (:projectors state) descriptor driver)))]
            {:state (added state projector-id projector)})))
      (let [proj-name (or name host)
            projector-config (make-projector-config
                               {:name proj-name
                                :host host
                                :port (or port 7255)
                                :enabled? true})]  ;; Manual = enabled by default
        {:state (added state projector-id projector-config)}))))

(def ^:private manual-dialog-path [:ui :dialogs :add-projector-manual])

(defn- handle-projectors-set-manual-driver
  "The Add Projector dialog's Type (:driver): the fields typed for another
   type are cleared; the name stays."
  [{:keys [driver state]}]
  (if (and (keyword? driver) (not= driver (get-in state (conj manual-dialog-path :driver) :idn)))
    {:state (update-in state manual-dialog-path #(-> % (assoc :driver driver) (dissoc :driver-config)))}
    {:state state}))

(defn- handle-projectors-set-manual-field
  "What is typed into one of the Add Projector dialog's driver fields (:key,
   :value), kept as typed: Add checks it (driver/coerce-config)."
  [{:keys [key value state]}]
  {:state (assoc-in state (into manual-dialog-path [:driver-config key]) value)})


;; Projector Management


(defn- handle-projectors-select-projector
  "Select a projector for editing."
  [{:keys [projector-id state]}]
  {:state (-> state
              (assoc-in [:projector-ui :active-projector] projector-id)
              (assoc-in [:projector-ui :active-virtual-projector] nil))})




(defn valid-port?
  "A UDP port number: an integer from 1 to 65535."
  [port]
  (and (int? port) (<= 1 port 65535)))

(defn- clamp-scan-rate-update
  "updates with its :scan-rate clamped (driver/clamp-scan-rate: 1 000-100 000
   pts/s, and the driver's :capabilities range when its descriptor has one),
   or without it when it isn't a number."
  [updates descriptor]
  (if-let [rate (driver/clamp-scan-rate descriptor (:scan-rate updates))]
    (assoc updates :scan-rate rate)
    (dissoc updates :scan-rate)))

(defn- handle-projectors-update-settings
  "Update projector settings (name, host, port, scan rate, enabled, output-config).
   
   A nil or blank host and a port outside 1-65535 are ignored (the other
   updates still apply), so a half-cleared field can't reach an engine. A scan
   rate is clamped to 1 000-100 000 pts/s, and into the range of the
   projector's driver (its :capabilities :scan-rate); one that isn't a number
   is ignored. :driver and :driver-config are ignored: a projector's driver
   never changes (OD-11), and its settings change through
   :projectors/update-driver-config, which checks them.
   A projector known by its address ([:host …] stable id) is known by the new
   one after a host change; a unit's identity doesn't change."
  [{:keys [projector-id updates state]}]
  (let [descriptor (descriptor-of state (driver/projector-driver-id (get-in state [:projectors projector-id])))
        updates (cond-> (dissoc updates :driver :driver-config)
                  (and (contains? updates :host)
                       (not (and (string? (:host updates))
                                 (not (str/blank? (:host updates))))))
                  (dissoc :host)

                  (and (contains? updates :port) (not (valid-port? (:port updates))))
                  (dissoc :port)

                  (contains? updates :scan-rate)
                  (clamp-scan-rate-update descriptor))
        follow-host (fn [p]
                      (let [[kind _ s] (:stable-id p)]
                        (if (and (contains? updates :host) (= :host kind))
                          (assoc p :stable-id [:host (:host p) s])
                          p)))]
    {:state (update-in state [:projectors projector-id] #(follow-host (merge % updates)))}))

(defn- handle-projectors-update-driver-config
  "Set one of a non-IDN projector's driver fields (:key) to :value, typed
   text or a value, checked by driver/coerce-field against the field spec in
   its driver's descriptor (01 §5.4): only an {:ok v} applies (nil unsets
   it, so its default applies). An invalid value, a key no field has
   (:driver among them: OD-11), an IDN projector, one whose driver isn't
   installed (its settings are kept as they are, OD-10), an unknown
   projector, or the value it has change nothing."
  [{:keys [projector-id key value state]}]
  (let [p (get-in state [:projectors projector-id])
        id (driver/projector-driver-id p)
        spec (some #(when (= key (:key %)) %) (:fields (descriptor-of state id)))
        {:keys [ok] :as result} (when (and p (not= :idn id) spec) (driver/coerce-field spec value))
        config (:driver-config p)
        new-config (if (some? ok) (assoc config key ok) (dissoc config key))]
    (if (and result (not (contains? result :error)) (not= config new-config))
      {:state (assoc-in state [:projectors projector-id :driver-config] new-config)}
      {:state state})))


(defn- handle-projectors-toggle-enabled
  "Toggle a projector's enabled state.
   Coerces enabled? to boolean to prevent ClassCastException."
  [{:keys [projector-id state]}]
  (let [current (boolean (get-in state [:projectors projector-id :enabled?]))]
    {:state (assoc-in state [:projectors projector-id :enabled?] (not current))}))


;; The rig strip and its inspector (plans/2026-11-19-zones/04 §6): setters,
;; so a box sets the value it shows and can never toggle twice


(defn- handle-projectors-set-enabled
  "Turn a projector's output on or off (:enabled? true or false), as its
   card's On box says. An unknown projector, any other value, or the value
   it has changes nothing."
  [{:keys [projector-id enabled? state]}]
  (if (and (contains? (:projectors state) projector-id)
           (boolean? enabled?)
           (not= enabled? (get-in state [:projectors projector-id :enabled?])))
    {:state (assoc-in state [:projectors projector-id :enabled?] enabled?)}
    {:state state}))

(defn- handle-projectors-set-exclude-from-all
  "Keep a projector out of All, and so out of Left, Right, Center, Odd and
   Even (D-33), or put it back (:exclude? true or false), from the All row
   or the inspector. It still plays its own group and its groups. An unknown
   projector, any other value, or the value it has changes nothing."
  [{:keys [projector-id exclude? state]}]
  (if (and (contains? (:projectors state) projector-id)
           (boolean? exclude?)
           (not= exclude? (boolean (get-in state [:projectors projector-id :exclude-from-all?]))))
    {:state (assoc-in state [:projectors projector-id :exclude-from-all?] exclude?)}
    {:state state}))

(defn- handle-projectors-set-tag
  "Tag a projector (:graphics, :crowd-scanning) or untag it (:on? true or
   false). :tags is kept a set (an old file may have a vector). The vibes
   read :crowd-scanning (vibes.library/audience-groups). An unknown
   projector, any other value, or the tag as it is changes nothing."
  [{:keys [projector-id tag on? state]}]
  (let [tags (set (get-in state [:projectors projector-id :tags]))]
    (if (and (contains? (:projectors state) projector-id)
             (keyword? tag)
             (boolean? on?)
             (not= on? (contains? tags tag)))
      {:state (assoc-in state [:projectors projector-id :tags] ((if on? conj disj) tags tag))}
      {:state state})))

(defn- fit-value?
  "Is value one :projectors/set-fit takes for key?"
  [key value]
  (case key
    (:reduce-points? :dim?) (boolean? value)
    (:min-fps :point-floor :dim-floor) (and (number? value) (Double/isFinite (double value)))
    false))

(defn- handle-projectors-set-fit
  "Set one of a projector's fit guard settings (zones plan Z3: :min-fps,
   :reduce-points?, :point-floor, :dim? or :dim-floor), from the inspector's
   Fit guard controls, clamped to its range (animation.fit-guard/settings).
   [:projectors id :fit] is stored whole. It restarts no engine: the routing
   carries the settings to the engines on the next tick. An unknown
   projector or key, a value of the wrong type, or the value it has changes
   nothing."
  [{:keys [projector-id key value state]}]
  (let [current (fit-guard/settings (get-in state [:projectors projector-id :fit]))
        fit (when (and (contains? (:projectors state) projector-id) (fit-value? key value))
              (fit-guard/settings (assoc current key value)))]
    (if (and fit (not= fit current))
      {:state (assoc-in state [:projectors projector-id :fit] fit)}
      {:state state})))




;; Zone Group Assignment


(defn- toggled-groups
  "groups with group-id taken out, or added at the end."
  [groups group-id]
  (let [groups (or groups [])]
    (if (some #{group-id} groups)
      (vec (remove #{group-id} groups))
      (conj (vec groups) group-id))))

(defn- handle-projectors-toggle-zone-group
  "Toggle a projector's membership in a custom zone group. All and the
   automatic groups (routing.rig/derived-ref?) are never stored: they are
   refused, and state is returned as it is."
  [{:keys [projector-id zone-group-id state]}]
  (if (rig/derived-ref? zone-group-id)
    {:state state}
    {:state (update-in state [:projectors projector-id :zone-groups] toggled-groups zone-group-id)}))




;; Virtual Projector Management


(defn- handle-vp-toggle-zone-group
  "Toggle a virtual projector's membership in a custom zone group (never All
   or an automatic group)."
  [{:keys [vp-id zone-group-id state]}]
  (if (rig/derived-ref? zone-group-id)
    {:state state}
    {:state (update-in state [:virtual-projectors vp-id :zone-groups] toggled-groups zone-group-id)}))




;; Effect Chain Management (for color curves)


(defn- handle-projectors-add-effect
  "Add an effect to a projector's chain."
  [{:keys [projector-id effect state]}]
  (let [effect-with-fields (h/ensure-item-fields effect)]
    {:state (-> state
                (update-in [:chains :projector-effects projector-id :items] conj effect-with-fields))}))





;; Removing a projector


(defn- projector-label [state projector-id]
  (let [n (get-in state [:projectors projector-id :name])]
    (if (and (string? n) (not (str/blank? n))) n (name projector-id))))

(defn- handle-projectors-request-remove
  "Ask before removing a projector (the shared confirm dialog). When cues are
   pinned to it (they name its own group, :auto.laser/<id>), it says how
   many, and asks where they go, All by default, as deleting a group does
   (04 S-13)."
  [{:keys [projector-id state]}]
  (if-not (contains? (:projectors state) projector-id)
    {:state state}
    (let [pin (rig/laser-ref projector-id)
          cues (refs/cues-using (get-in state [:chains :cue-chains]) pin)]
      {:state (assoc-in state [:ui :dialogs :confirm]
                        (cond-> {:open? true
                                 :title (str "Remove " (projector-label state projector-id) "?")
                                 :message (str "Its calibration goes too. If it's still on the network, the next scan finds it again, turned off."
                                               (when (seq cues)
                                                 (str " " (zone-groups/plural (count cues) "cue" "cues")
                                                      (if (= 1 (count cues)) " is" " are") " pinned to it.")))
                                 :confirm-label "Remove"
                                 :danger? true
                                 :confirm-event {:event/type :projectors/remove :projector-id projector-id}}
                          (seq cues) (assoc :details (zone-groups/cue-list-text state cues)
                                            :choice {:key :retarget-to
                                                     :label "Send its cues to:"
                                                     :options (zone-groups/retarget-options state pin)}
                                            :choice-value :all)))})))

(defn- drop-discovered-output
  "The scan's device list without projector p's output: an IDN host's
   service-id, or another driver's output by its stable id [:device driver
   key output-id], so it doesn't keep an output no projector has."
  [devices {:keys [host service-id stable-id] :as p}]
  (let [[kind driver key output-id] stable-id]
    (mapv (fn [device]
            (cond
              (and (= :idn (:driver p :idn)) (= :idn (:driver device :idn)) (= host (:address device)))
              (update device :services (fn [services] (filterv #(not= (or service-id 0) (or (:service-id %) 0)) services)))

              (and (= :device kind) (= driver (:driver device)) (= key (:device-key device)))
              (update device :outputs (fn [outputs] (filterv #(not= output-id (:output-id %)) outputs)))

              :else device))
          devices)))

(defn- handle-projectors-remove
  "Remove a projector, its calibration chain, its editor and list state, its
   virtual projectors and its place in the rig order, and clear the selection
   and calibration mode that point at it. Cues pinned to it go to
   :retarget-to (zone-groups/retarget-value: All by default, nil or :inherit
   for Nothing), with a notice, so nothing names it any more. While
   streaming, the output-sync middleware stops its engine. An unknown id
   changes nothing."
  [{:keys [projector-id state] :as event}]
  (if-let [p (get-in state [:projectors projector-id])]
    (let [clear-if (fn [s path] (if (= projector-id (get-in s path)) (assoc-in s path nil) s))
          pin (rig/laser-ref projector-id)
          pinned (count (refs/cues-using (get-in state [:chains :cue-chains]) pin))
          target (zone-groups/retarget-value state pin event)
          removed (-> state
                      (refs/replace-refs {pin target})
                      (update :projectors dissoc projector-id)
                      (update-in [:chains :projector-effects] dissoc projector-id)
                      (update-in [:ui :projector-effect-ui-state] dissoc projector-id)
                      (update :list-ui dissoc [:projector-effects projector-id])
                      (update :virtual-projectors
                              #(into {} (remove (fn [[_ vp]] (= projector-id (:parent-projector-id vp)))) %))
                      (update-in [:rig :order] #(filterv (complement #{projector-id}) %))
                      (clear-if [:projector-ui :active-projector])
                      (clear-if [:projector-ui :calibrating-projector-id])
                      (update-in [:projector-ui :identify] dissoc projector-id)
                      (update-in [:projector-ui :discovered-devices] drop-discovered-output p))]
      (if (pos? pinned)
        (h/show-notice removed
                       (str "Removed " (projector-label state projector-id) " · "
                            (zone-groups/plural pinned "cue" "cues") " now "
                            (if target (str "go to " (rig/ref-name state target)) "inherit"))
                       :info)
        {:state removed}))
    {:state state}))


;; Identify and safety zones (zones plan Z1)


(defn- show-playing? [state]
  (boolean (and (get-in state [:playback :playing?])
                (seq (get-in state [:playback :active-cues])))))

(defn- handle-projectors-identify
  "Flash projector-id's test pattern for 3 s (multi-engine shows it while
   [:projector-ui :identify id] is in the future). Only for a projector that is
   outputting; while a cue plays, it asks first. Pressing it again restarts
   the 3 s."
  [{:keys [projector-id time confirmed? state] :as event}]
  (cond
    (not (guard/outputting? state projector-id)) {:state state}

    (and (show-playing? state) (not confirmed?))
    {:state (guard/ask state {:title (str "Identify " (guard/projector-name state projector-id) "?")
                              :message "It shows the test pattern for 3 seconds instead of the show."
                              :confirm-label "Identify"
                              :event event})}

    :else
    (let [until (+ (long (or time 0)) test-pattern/identify-ms)]
      {:state (assoc-in state [:projector-ui :identify projector-id] until)
       :dispatch-later {:event {:event/type :projectors/identify-ended :projector-id projector-id :until until}
                        :delay-ms test-pattern/identify-ms}})))

(defn- handle-projectors-identify-ended
  "Identify's flash is over: clear it, unless a newer press restarted it."
  [{:keys [projector-id until state]}]
  (if (= until (get-in state [:projector-ui :identify projector-id]))
    {:state (update-in state [:projector-ui :identify] dissoc projector-id)}
    {:state state}))


(defn- write-zone
  "state with projector-id's safety zone set to zone, asking first while its
   light is going out and zone would let light somewhere new (D-28). zone
   is normalized; one normalize can't read is refused. While the question is
   open, another widening is dropped."
  [state projector-id zone {:keys [confirmed?] :as event} message]
  (let [old (get-in state [:projectors projector-id :safety])
        new (try (safety/normalize zone) (catch clojure.lang.ExceptionInfo _ ::unreadable))]
    (cond
      (= ::unreadable new) {:state state}

      (and (not confirmed?)
           (guard/outputting? state projector-id)
           (safety/widens? old new))
      (if (guard/asking? state)
        {:state state}
        {:state (guard/ask state {:message message :event event})})

      :else {:state (assoc-in state [:projectors projector-id :safety] new)})))

(defn- live-message [state projector-id]
  (str (guard/projector-name state projector-id) " is on. Light can reach places it couldn't before."))

(defn- handle-projectors-set-safety
  "Set a projector's safety zone (the safety editor, Reset)."
  [{:keys [projector-id safety state] :as event}]
  (if-not (contains? (:projectors state) projector-id)
    {:state state}
    (write-zone state projector-id safety event (live-message state projector-id))))

(def ^:const max-blocked-areas 8)

(defn- handle-projectors-add-blocked-area
  "Add a 0.4 × 0.2 blocked area in the middle of the limit (it only shrinks
   the allowed area, so it applies at once). Nothing at 8."
  [{:keys [projector-id state]}]
  (let [zone (try (safety/normalize (get-in state [:projectors projector-id :safety]))
                  (catch clojure.lang.ExceptionInfo _ nil))]
    (if (or (not (contains? (:projectors state) projector-id))
            (nil? zone)
            (>= (count (:blocked zone)) max-blocked-areas))
      {:state state}
      (let [[x0 y0 x1 y1] (:limit zone)
            cx (/ (+ x0 x1) 2.0) cy (/ (+ y0 y1) 2.0)
            area [(max x0 (- cx 0.2)) (max y0 (- cy 0.1)) (min x1 (+ cx 0.2)) (min y1 (+ cy 0.1))]]
        {:state (assoc-in state [:projectors projector-id :safety] (update zone :blocked conj area))}))))

(defn- handle-projectors-remove-blocked-area
  "Remove a blocked area. That lets light into it again, so it asks first
   while the projector's light is going out."
  [{:keys [projector-id index state] :as event}]
  (let [zone (try (safety/normalize (get-in state [:projectors projector-id :safety]))
                  (catch clojure.lang.ExceptionInfo _ nil))]
    (if (or (not (contains? (:projectors state) projector-id)) (nil? zone)
            (not (< -1 (or index -1) (count (:blocked zone)))))
      {:state state}
      (write-zone state projector-id
                  (update zone :blocked #(into (subvec % 0 index) (subvec % (inc index))))
                  event
                  (str (guard/projector-name state projector-id) " is on. Light can reach Blocked "
                       (inc index) " again.")))))

(defn- handle-projectors-copy-safety
  "Copy (or mirror left to right) one projector's safety zone to others, in
   one undo step. If it would give live projectors a larger area, one
   question for all of them first."
  [{:keys [from to mirror? confirmed? state] :as event}]
  (let [zone (try (safety/normalize (get-in state [:projectors from :safety]))
                  (catch clojure.lang.ExceptionInfo _ nil))
        targets (filterv #(and (contains? (:projectors state) %) (not= from %)) to)]
    (if (or (not (contains? (:projectors state) from)) (nil? zone) (empty? targets))
      {:state state}
      (let [new-zone (if mirror? (safety/mirror-x zone) zone)
            after (reduce #(assoc-in %1 [:projectors %2 :safety] new-zone) state targets)
            grown (guard/growing state after)]
        (cond
          (or confirmed? (empty? grown)) {:state after}
          (guard/asking? state) {:state state}
          :else {:state (guard/ask state {:title (str "Allow light in a larger area on "
                                                      (guard/names-text (map #(guard/projector-name state %) grown))
                                                      "?")
                                          :message "They are on. Light can reach places it couldn't before."
                                          :event event})})))))


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
    :projectors/enable-device (handle-projectors-enable-device event)
    :projectors/set-service-enabled (handle-projectors-set-service-enabled event)
    :projectors/add-manual (handle-projectors-add-manual event)
    :projectors/set-manual-driver (handle-projectors-set-manual-driver event)
    :projectors/set-manual-field (handle-projectors-set-manual-field event)
    
    ;; Projector management
    :projectors/select-projector (handle-projectors-select-projector event)
    :projectors/update-settings (handle-projectors-update-settings event)
    :projectors/update-driver-config (handle-projectors-update-driver-config event)
    :projectors/toggle-enabled (handle-projectors-toggle-enabled event)
    
    ;; Zone group assignment
    :projectors/toggle-zone-group (handle-projectors-toggle-zone-group event)
    
    ;; Virtual projector management
    :projectors/vp-toggle-zone-group (handle-vp-toggle-zone-group event)
    
    ;; Effect chain management (for color curves)
    :projectors/add-effect (handle-projectors-add-effect event)
    :projectors/request-remove (handle-projectors-request-remove event)
    :projectors/remove (handle-projectors-remove event)
    :projectors/identify (handle-projectors-identify event)
    :projectors/identify-ended (handle-projectors-identify-ended event)
    :projectors/set-safety (handle-projectors-set-safety event)
    :projectors/add-blocked-area (handle-projectors-add-blocked-area event)
    :projectors/remove-blocked-area (handle-projectors-remove-blocked-area event)
    :projectors/copy-safety (handle-projectors-copy-safety event)
    
    ;; Calibration mode
    :projectors/toggle-calibration (handle-projectors-toggle-calibration event)
    :projectors/set-calibration-brightness (handle-projectors-set-calibration-brightness event)
    
    ;; The rig strip and its inspector (Z2)
    :projectors/set-enabled (handle-projectors-set-enabled event)
    :projectors/set-exclude-from-all (handle-projectors-set-exclude-from-all event)
    :projectors/set-tag (handle-projectors-set-tag event)
    
    ;; The fit guard (Z3)
    :projectors/set-fit (handle-projectors-set-fit event)
    
    ;; Configuration
    :projectors/toggle-device-expand (handle-projectors-toggle-device-expand event)
    
    ;; Unknown event in this domain
    {}))
