(ns laser-show.views.tabs.projectors
  "Projectors tab - for managing IDN laser projectors.
   
   Features:
   - Device discovery via network scan
   - Configure projectors with effect chains (color calibration, corner pin)
   - Test pattern mode for calibration
   - Enable/disable individual projectors"
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.effects :as effects]
            [laser-show.state.clipboard :as clipboard]
            [laser-show.common.util :as u]
            [laser-show.css.core :as css]
            [laser-show.idn.stream :as stream]
            [laser-show.views.components.visual-editors.custom-param-renderers :as custom-renderers]
            [laser-show.views.components.parameter-controls :as param-controls]
            [laser-show.views.components.list :as list]
            [laser-show.views.components.commit-field :as commit-field]
            [laser-show.views.components.zone-chips :as zone-chips]
            [clj-font-awesome.core :as fa]))


;; Status Indicators


(defn- status-indicator
  "Colored circle indicating device/connection status."
  [{:keys [id status size]
    :or {size 8}}]
  (let [color (case status
                :online (css/green)
                :occupied (css/orange)
                :offline (css/red)
                :connected (css/green)
                :connecting (css/blue)
                (css/text-muted))]
    (cond-> {:fx/type :circle
             :radius (/ size 2)
             :fill color}
      id (assoc :id id))))


;; What the text fields take (they commit on Enter or focus loss)


(defn- parse-port
  "The port typed into the Port field, or nil unless it is 1-65535."
  [text]
  (let [port (parse-long (str/trim text))]
    (when (and port (<= 1 port 65535))
      port)))

(defn- parse-scan-rate
  "Points per second typed into the Scan rate field: digits, with spaces or
   commas between them if you like (\"25 000\", \"25,000\"), or thousands with
   a k (\"25k\", \"27.5k\"). nil for anything else."
  [text]
  (let [t (str/lower-case (str/trim text))]
    (if-let [[_ thousands] (re-matches #"(\d+(?:\.\d+)?)\s*k" t)]
      (Math/round (* 1000.0 (parse-double thousands)))
      (when (re-matches #"\d[\d ,]*" t)
        (parse-long (str/replace t #"[ ,]" ""))))))


;; Device Discovery Panel


(defn- address-id
  "A device address as part of a node id (a CSS id selector can't have dots)."
  [address]
  (str/replace (str address) "." "-"))

(defn- service-entry-item
  "Single service/output entry within a device.
   Shows service name and checkbox showing enabled state for multi-output devices."
  [{:keys [device service enabled?]}]
  (let [{:keys [service-id name]} service
        {:keys [address]} device
        service-display-name (or name (str "Output " service-id))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :padding {:left 24 :right 8 :top 4 :bottom 4}
     :style-class ["card"]
     :children [{:fx/type :label
                 :text (str "• " service-display-name)
                 :style-class ["label-secondary"]
                 :style "-fx-font-size: 11;"}
                {:fx/type :region :h-box/hgrow :always}
                {:fx/type :check-box
                 :id (str "discovered-output-" (address-id address) "-" service-id)
                 :selected enabled?
                 :on-selected-changed {:event/type :projectors/set-service-enabled
                                       :host address
                                       :service-id service-id
                                       :enabled? :fx/event}}]}))

(defn- discovered-device-item
  "Single discovered device in the list.
   For devices with multiple services, shows expandable service list (click anywhere to expand).
   For single-service devices, shows inline checkbox for enabled state."
  [{:keys [device enabled-services expanded? on-toggle-expand]}]
  (let [{:keys [address host-name status services]} device
        service-count (count services)
        has-multiple-services? (> service-count 1)
        ;; For single-service devices, check if that service is enabled
        single-service-enabled? (and (= service-count 1)
                                     (contains? enabled-services
                                                [address (:service-id (first services))]))
        ;; For no-service devices (service-id 0), check if enabled
        no-service-enabled? (and (zero? service-count)
                                 (contains? enabled-services [address 0]))
        ;; For multi-service devices, check if all services are enabled
        all-services-enabled? (and has-multiple-services?
                                   (every? #(contains? enabled-services [address (:service-id %)])
                                           services))
        device-status (cond
                        (:offline status) :offline
                        (:occupied status) :occupied
                        :else :online)
        ;; Build address info children (filter out nil)
        address-info-children (filterv some?
                                       [{:fx/type :label
                                         :text address
                                         :style (str "-fx-text-fill: " (css/text-muted) "; -fx-font-size: 10;")}
                                        (when (pos? service-count)
                                          {:fx/type :label
                                           :text (str "(" service-count " output" (when (> service-count 1) "s") ")")
                                           :style (str "-fx-text-fill: " (css/text-muted) "; -fx-font-size: 10;")})])
        ;; Build header children (filter out nil)
        header-children (filterv some?
                                 (concat
                                   ;; Expand indicator (visual only) for multi-service devices
                                   (when has-multiple-services?
                                     [{:fx/type fa/icon
                                       :name (if expanded? :circle-chevron-down :circle-chevron-right)
                                       :size 10
                                       :style-class ["text-muted"]}])
                                   ;; Status indicator and device info
                                   [{:fx/type status-indicator
                                     :status device-status}
                                    {:fx/type :v-box
                                     :children [{:fx/type :label
                                                 :text (or host-name address)
                                                 :style "-fx-text-fill: white; -fx-font-weight: bold;"}
                                                {:fx/type :h-box
                                                 :spacing 8
                                                 :children address-info-children}]}
                                    {:fx/type :region :h-box/hgrow :always}]
                                   ;; For multi-service devices, show "Enable All" button
                                   (when has-multiple-services?
                                     [{:fx/type :button
                                       :text "Enable All"
                                       :disable all-services-enabled?
                                       :style-class ["button-primary"]
                                       :on-action {:event/type :projectors/enable-all-by-ip
                                                   :address address}}])
                                   ;; For single-service or no-service devices, show checkbox directly
                                   (when (not has-multiple-services?)
                                     (let [service-id (if (zero? service-count)
                                                        0
                                                        (:service-id (first services)))]
                                       [{:fx/type :check-box
                                         :id (str "discovered-output-" (address-id address) "-" service-id)
                                         :selected (or single-service-enabled? no-service-enabled?)
                                         :on-selected-changed {:event/type :projectors/set-service-enabled
                                                               :host address
                                                               :service-id service-id
                                                               :enabled? :fx/event}}]))))]
    {:fx/type :v-box
     :spacing 2
     :children
     (vec
       (concat
         ;; Device header row - clickable anywhere for multi-service devices
         [(cond-> {:fx/type :h-box
                   :id (str "discovered-device-" (address-id address))
                   :spacing 8
                   :alignment :center-left
                   :padding 8
                   :style (str "-fx-background-color: #3D3D3D; -fx-background-radius: 4;"
                               (when has-multiple-services? " -fx-cursor: hand;"))
                   :children header-children}
            ;; cljfx can't take a nil handler
            has-multiple-services? (assoc :on-mouse-clicked on-toggle-expand))]
         ;; Expanded services list for multi-service devices
          (when (and has-multiple-services? expanded?)
            (for [service services]
              {:fx/type service-entry-item
               :fx/key (str address "-" (:service-id service))
               :device device
               :service service
               :enabled? (contains? enabled-services [address (:service-id service)])}))))}))

(defn- device-discovery-panel
  "Panel for scanning network and listing discovered devices.
   Shows expandable entries for multi-output devices with service list."
  [{:keys [fx/context]}]
  (let [discovered (fx/sub-ctx context subs/discovered-devices)
        scanning? (fx/sub-ctx context subs/projector-scanning?)
        ;; Use enabled-projector-services to show actual enabled state, not just existence
        enabled-services (fx/sub-ctx context subs/enabled-projector-services)
        expanded-devices (fx/sub-ctx context subs/expanded-discovered-devices)]
    {:fx/type :v-box
     :spacing 8
     :pref-width 300
     :style (str "-fx-border-color: " (css/border-default) "; -fx-border-width: 0 1px 0 0;")
     :children [{:fx/type :label
                 :text "DISCOVER DEVICES"
                 :style-class ["header-section"]}
                {:fx/type :h-box
                 :spacing 8
                 :children [{:fx/type :button
                             :text (if scanning? "Scanning..." "Scan Network")
                             :disable scanning?
                             :style-class ["button-primary"]
                             :on-action {:event/type :projectors/scan-network}}
                            {:fx/type :button
                             :id "projectors-add-manual-button"
                             :text "Add Manual..."
                             :style-class ["button-secondary"]
                             :on-action {:event/type :ui/open-dialog
                                         :dialog-id :add-projector-manual}}]}
                {:fx/type :scroll-pane
                 :fit-to-width true
                 :v-box/vgrow :always
                 :style-class ["scroll-pane-dark"]
                 :content {:fx/type :v-box
                           :spacing 4
                           :padding 4
                           :children (if (seq discovered)
                                       (vec (for [device discovered]
                                              (let [address (:address device)]
                                                {:fx/type discovered-device-item
                                                 :fx/key address
                                                 :device device
                                                 :enabled-services enabled-services
                                                 :expanded? (contains? expanded-devices address)
                                                 :on-toggle-expand {:event/type :projectors/toggle-device-expand
                                                                    :address address}})))
                                       [{:fx/type :label
                                         :text (if scanning?
                                                 "Searching for devices..."
                                                 "No devices found.\nClick 'Scan Network' to search.")
                                         :style-class ["label-hint"]
                                         :style "-fx-padding: 16;"}])}}]}))


;; Configured Projectors List


(defn- output-status-text
  "One line for a projector's output status, or nil when it isn't streaming.
   The fps is how many new frames the projector draws per second; a warning
   (a calibration effect skipped because it fails) comes last."
  [{:keys [state fps scan-limited? error warning]}]
  (case state
    :streaming (cond-> (cond
                         (nil? fps) "Streaming"
                         scan-limited? (format "Streaming · %.1f fps (limited by scan rate)" (double fps))
                         :else (format "Streaming · %.1f fps" (double fps)))
                 warning (str " · " warning))
    :starting "Starting…"
    :error (str "Error: " error)
    nil))

(defn- projector-list-item
  "Single configured projector in the list.
   Shows service ID when configured for a specific output, and its output
   status (the dot, and a line while it streams or fails)."
  [{:keys [fx/context projector selected?]}]
  (let [{:keys [id name host service-id]} projector
        {:keys [state] :as output-status} (fx/sub-ctx context subs/projector-output-status id)
        status-text (output-status-text output-status)
        ;; Coerce enabled? to boolean to prevent ClassCastException if corrupted data
        enabled-bool? (boolean (:enabled? projector))
        ;; Display host:service or just host
        host-display (if (and service-id (pos? service-id))
                       (str host " #" service-id)
                       host)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :padding 8
     :style-class [(if selected? "list-item-selected" "list-item")]
     :on-mouse-clicked {:event/type :projectors/select-projector
                        :projector-id id}
     :children [{:fx/type :check-box
                 :selected enabled-bool?
                 :on-selected-changed {:event/type :projectors/toggle-enabled
                                       :projector-id id}}
                {:fx/type status-indicator
                 :id (str "projector-dot-" (clojure.core/name id))
                 :status (case state
                           :streaming :connected
                           :starting :connecting
                           :error :offline
                           nil)}
                {:fx/type :v-box
                 :children (cond-> [{:fx/type :label
                                     :text (or (when (string? name) name) "")
                                     :style-class ["label-bold"]}
                                    {:fx/type :label
                                     :text (str host-display)
                                     :style-class ["text-small" "label-secondary"]}]
                             status-text
                             (conj (cond-> {:fx/type :label
                                            :id (str "projector-status-" (clojure.core/name id))
                                            :text status-text
                                            :wrap-text true
                                            :max-width 200
                                            :style-class ["text-small" "label-secondary"]}
                                     (= :error state) (assoc :style (str "-fx-text-fill: " (css/red) ";"))
                                     (and (= :streaming state) (:warning output-status))
                                     (assoc :style (str "-fx-text-fill: " (css/orange) ";")))))}
                {:fx/type :region :h-box/hgrow :always}]}))

(defn- configured-projectors-list
  "List of configured projectors."
  [{:keys [fx/context]}]
  (let [projectors (fx/sub-ctx context subs/projectors-list)
        active-id (fx/sub-ctx context subs/active-projector-id)]
    {:fx/type :v-box
     :spacing 8
     :pref-width 280
     :style (str "-fx-border-color: " (css/border-default) "; -fx-border-width: 0 1px 0 0;")
     :children [{:fx/type :label
                 :text "CONFIGURED PROJECTORS"
                 :style-class ["header-section"]}
                {:fx/type :scroll-pane
                 :fit-to-width true
                 :v-box/vgrow :always
                 :style-class ["scroll-pane-dark"]
                 :content {:fx/type :v-box
                           :spacing 4
                           :padding 4
                           :children (if (seq projectors)
                                       (vec (for [projector projectors]
                                              {:fx/type projector-list-item
                                               :fx/key (:id projector)
                                               :projector projector
                                               :selected? (= (:id projector) active-id)}))
                                       [{:fx/type :label
                                         :text "No projectors configured.\nAdd from discovered devices or manually."
                                         :style-class ["label-hint"]
                                         :style "-fx-padding: 16;"}])}}]}))


;; Projector Configuration Panel


(defn- projector-basic-settings
  "Basic projector settings (name, host, port, scan rate, service info)."
  [{:keys [projector]}]
  (let [{:keys [id name host port scan-rate service-id service-name]} projector
        stored-rate (or scan-rate stream/DEFAULT_SCAN_RATE)]
    {:fx/type :v-box
     :spacing 8
     :children (vec
                 (concat
                   ;; Name field
                   [{:fx/type :h-box
                     :spacing 8
                     :alignment :center-left
                     :children [{:fx/type :label
                                 :text "Name:"
                                 :pref-width 50
                                 :style-class ["label-secondary"]}
                                {:fx/type :text-field
                                 :id "projector-name-field"
                                 :text (or name "")
                                 :pref-width 200
                                 :style-class ["text-field-dark"]
                                 :on-text-changed {:event/type :projectors/update-settings
                                                   :projector-id id
                                                   :updates {:name :fx/event}}}]}
                    ;; Host and port
                    {:fx/type :h-box
                     :spacing 8
                     :alignment :center-left
                     :children [{:fx/type :label
                                 :text "Host:"
                                 :pref-width 50
                                 :style-class ["label-secondary"]}
                                {:fx/type commit-field/commit-field
                                 :id "projector-host-field"
                                 :text (or host "")
                                 :pref-width 150
                                 :on-commit (fn [typed]
                                              (let [new-host (str/trim typed)]
                                                (when-not (str/blank? new-host)
                                                  {:event/type :projectors/update-settings
                                                   :projector-id id
                                                   :updates {:host new-host}})))}
                                {:fx/type :label
                                 :id "projector-port-label"
                                 :text "Port:"
                                 :min-width :use-pref-size
                                 :style-class ["label-secondary"]}
                                {:fx/type commit-field/commit-field
                                 :id "projector-port-field"
                                 :text (str (or port 7255))
                                 :pref-width 60
                                 :on-commit (fn [typed]
                                              (when-let [new-port (parse-port typed)]
                                                {:event/type :projectors/update-settings
                                                 :projector-id id
                                                 :updates {:port new-port}}))}]}
                    ;; Scan rate: sets how long each frame takes to draw
                    {:fx/type :h-box
                     :spacing 8
                     :alignment :center-left
                     :children [{:fx/type :label
                                 :text "Scan rate:"
                                 :style-class ["label-secondary"]}
                                {:fx/type commit-field/commit-field
                                 :id "projector-scan-rate-field"
                                 :text (str stored-rate)
                                 :pref-width 80
                                 :on-commit (fn [typed]
                                              (let [rate (stream/clamp-scan-rate (parse-scan-rate typed))]
                                                (when (and rate (not= rate stored-rate))
                                                  {:event/type :projectors/update-settings
                                                   :projector-id id
                                                   :updates {:scan-rate rate}})))}
                                {:fx/type :label
                                 :text "pts/s"
                                 :style-class ["label-secondary"]}]}]
                   ;; Service info (if multi-output device)
                   (when (and service-id (pos? service-id))
                     [{:fx/type :h-box
                       :spacing 8
                       :alignment :center-left
                       :children [{:fx/type :label
                                   :text "Output:"
                                   :pref-width 50
                                   :style-class ["label-secondary"]}
                                  {:fx/type :label
                                   :text (str "Service #" service-id
                                             (when service-name (str " - " service-name)))
                             :style (str "-fx-text-fill: " (css/selection-bg) "; -fx-font-size: 11;")}]}])))}))


;; Zone Group Assignment UI


(defn- zone-group-assignment-section
"Section for assigning a projector to zone groups."
[{:keys [fx/context projector-id]}]
(let [all-groups (fx/sub-ctx context subs/zone-groups-list)
  current-groups (fx/sub-ctx context subs/projector-zone-groups projector-id)]
{:fx/type :v-box
:spacing 8
:children [{:fx/type :h-box
           :alignment :center-left
           :children [{:fx/type :label
                       :text "ZONE GROUPS"
                       :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                      {:fx/type :region :h-box/hgrow :always}
                      {:fx/type :label
                       :text (str (count current-groups) " assigned")
                       :style "-fx-text-fill: #606060; -fx-font-size: 10;"}]}
          {:fx/type :flow-pane
           :hgap 6
           :vgap 6
           :children (vec (for [group all-groups]
                            {:fx/type zone-chips/zone-group-chip
                             :fx/key (:id group)
                             :group group
                             :selected? (some #{(:id group)} current-groups)
                             :on-toggle {:event/type :projectors/toggle-zone-group
                                         :projector-id projector-id
                                         :zone-group-id (:id group)}}))}
          {:fx/type :label
           :text "Click to toggle membership"
           :style "-fx-text-fill: #505050; -fx-font-size: 9; -fx-font-style: italic;"}]}))


;; Calibration effect helpers


(defn- get-calibration-effects
  "Get all registered calibration effects from the effect registry."
  []
  (effects/list-effects-by-category :calibration))

(defn- calibration-effect-labels
  "Dynamically build map of calibration effect IDs to display labels."
  []
  (u/map-into :id :name (get-calibration-effects)))


;; Effect Parameter Editors
;; Uses shared param-controls with projector-specific event templates


(defn- make-projector-param-events
  "Event templates for a projector effect's parameters: the :chain/* param
   events, as the curves and corner pin editors use, at the effect's full
   path in the chain (an effect inside a group is the one edited).
   Returns map with :on-change-event and :on-text-event."
  [projector-id effect-path]
  {:on-change-event {:event/type :chain/update-param
                     :domain :projector-effects
                     :entity-key projector-id
                     :effect-path effect-path}
   :on-text-event {:event/type :chain/update-param-from-text
                   :domain :projector-effects
                   :entity-key projector-id
                   :effect-path effect-path}})

(defn- standard-effect-params-editor
  "Generic parameter editor using effect registry parameters.
   
   Uses param-controls-list to render all parameters from the effect definition.
   This replaces individual effect-specific editors like rgb-calibration-editor,
   gamma-correction-editor, axis-flip-editor, and rotation-offset-editor."
  [{:keys [projector-id effect-path effect-def params]}]
  (let [{:keys [on-change-event on-text-event]} (make-projector-param-events projector-id effect-path)
        params-map (param-controls/params-vector->map (:parameters effect-def []))]
    {:fx/type param-controls/param-controls-list
     :params-map params-map
     :current-params params
     :on-change-event on-change-event
     :on-text-event on-text-event}))

(defn- effect-parameter-editor
  "Renders the appropriate editor for a selected effect.
   Checks for custom ui-hints renderer, falls back to generic params editor.
   
   Props:
   - :projector-id - ID of the projector
   - :effect-path - The effect's full path in the chain ([1], or [2 :items 0] inside a group)
   - :effect - The effect map
   - :ui-state - UI state for this projector (from projector-effect-ui-state sub)"
  [{:keys [projector-id effect-path effect ui-state]}]
  (let [{:keys [effect-id params]} effect
        effect-def (when effect-id (effects/get-effect effect-id))
        effect-name (or (:name effect-def)
                        (when effect-id (name effect-id))
                        "Unknown Effect")
        renderer-type (get-in effect-def [:ui-hints :renderer])]
    {:fx/type :v-box
     :spacing 8
     :padding 8
     :style-class ["panel-elevated"]
     :children [{:fx/type :label
                 :text (str "Edit: " effect-name)
                 :style-class ["label-bold"]
                 :style "-fx-font-size: 11;"}
                (cond
                   ;; Check for custom renderer in ui-hints: rgb-curves
                   (= :rgb-curves renderer-type)
                   {:fx/type custom-renderers/rgb-curves-visual-editor
                    :current-params params
                    :event-template {:event/type :chain/update-curve-point
                                     :domain :projector-effects
                                     :entity-key projector-id
                                     :effect-path effect-path}
                    :dialog-data ui-state}
                  
                  ;; Check for custom renderer in ui-hints: corner-pin-2d
                  ;; Uses standard chain events - same as all other effect param editors
                  (= :corner-pin-2d renderer-type)
                  {:fx/type custom-renderers/corner-pin-visual-editor
                   :params params
                   :event-template {:event/type :chain/update-spatial-params
                                    :domain :projector-effects
                                    :entity-key projector-id
                                    :effect-path effect-path}
                   :reset-event {:event/type :chain/reset-params
                                 :domain :projector-effects
                                 :entity-key projector-id
                                 :effect-path effect-path
                                 :defaults-map {:tl-x -1.0 :tl-y 1.0
                                                :tr-x 1.0 :tr-y 1.0
                                                :bl-x -1.0 :bl-y -1.0
                                                :br-x 1.0 :br-y -1.0}}
                   :fx-key [:projector projector-id effect-path]
                   :hint-text "Drag corners to adjust output geometry"}
                  
                  ;; Default: use generic param-controls-list based on effect registry
                  ;; This replaces individual editors like rgb-calibration-editor,
                  ;; gamma-correction-editor, axis-flip-editor, rotation-offset-editor
                  effect-def
                  {:fx/type standard-effect-params-editor
                   :projector-id projector-id
                   :effect-path effect-path
                   :effect-def effect-def
                   :params params}
                  
                  ;; No effect definition found
                  :else
                  {:fx/type :label
                   :text "Unknown effect - no parameters available"
                   :style "-fx-text-fill: #808080; -fx-font-style: italic;"})]}))

(defn- projector-effects-sidebar
  "Effect chain sidebar using list-editor with built-in keyboard handling.
   Props:
   - :fx/context - Context for subscriptions
   - :projector-id - ID of the projector
   - :selected-ids - Set of selected item IDs
   - :clipboard-items - Items available for pasting"
  [{:keys [fx/context projector-id selected-ids clipboard-items]}]
  (let [;; Read effects from chains domain, not from projector config
        effects (fx/sub-ctx context subs/projector-effect-chain projector-id)
        ;; Build label map from calibration effects
        labels (calibration-effect-labels)]
    {:fx/type list/list-editor
     :fx/context context  ;; Required for list-sidebar to subscribe to list-ui-state
     :items (or effects [])
     :component-id [:projector-effects projector-id]
     :items-path [:chains :projector-effects projector-id :items]
     ;; Custom label function that looks up effect-id in calibration-effect-labels
     :get-item-label (fn [item]
                       (get labels (:effect-id item) "Unknown Effect"))
     :on-change-event :chain/set-items
     ;; Copied and pasted as effects, so the Paste button (lit by
     ;; clipboard/get-effects-to-paste) and ⌘V paste the same thing
     :on-change-params {:domain :projector-effects :entity-key projector-id :clipboard-type :item-effects}
     :clipboard-items clipboard-items
     :header-label "EFFECTS"
     :empty-text "No calibration effects\nAdd from menu above"}))

(defn- projector-add-effect-menu
  "Menu button for adding calibration effects to a projector.
   Dynamically builds menu from registered calibration effects."
  [{:keys [projector-id]}]
  (let [calibration-effects (get-calibration-effects)
        menu-items (vec (for [effect-def calibration-effects]
                          (let [effect-id (:id effect-def)
                                effect-name (:name effect-def)
                                default-params (effects/get-default-params effect-id)]
                            {:fx/type :menu-item
                             :text effect-name
                             :on-action {:event/type :projectors/add-effect
                                         :projector-id projector-id
                                         :effect {:effect-id effect-id
                                                  :params default-params}}})))]
    {:fx/type :menu-button
     :text "+ Add Effect"
     :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-font-size: 10;"
     :items menu-items}))

(defn- calibration-controls
  "Calibration mode toggle for the selected projector.
   Displays a boundary box test pattern for corner pin calibration."
  [{:keys [fx/context projector-id]}]
  (let [calibrating-id (fx/sub-ctx context subs/calibrating-projector-id)
        calibrating? (= calibrating-id projector-id)
        brightness (fx/sub-ctx context subs/calibration-brightness)]
    {:fx/type :v-box
     :spacing 8
     :children (filterv some?
                 [{:fx/type :h-box
                   :spacing 8
                   :alignment :center-left
                   :children [{:fx/type :label
                               :text "CALIBRATION:"
                               :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                              {:fx/type :button
                               :id "projector-calibration-button"
                               :text (if calibrating? "Stop Calibration" "Start Calibration")
                               :style-class [(if calibrating? "button-danger" "button-primary")]
                               :on-action {:event/type :projectors/toggle-calibration
                                           :projector-id projector-id}}]}
                  (when calibrating?
                    {:fx/type :h-box
                     :spacing 8
                     :alignment :center-left
                     :children [{:fx/type :label
                                 :text "Brightness:"
                                 :style "-fx-text-fill: #808080; -fx-font-size: 11;"}
                                {:fx/type :slider
                                 :id "calibration-brightness-slider"
                                 :min 0.05
                                 :max 0.5
                                 :value brightness
                                 :pref-width 150
                                 :on-value-changed {:event/type :projectors/set-calibration-brightness
                                                    :brightness :fx/event}}
                                {:fx/type :label
                                 :id "calibration-brightness-label"
                                 :text (format "%.0f%%" (* brightness 100))
                                 :pref-width 40
                                 :style "-fx-text-fill: #808080; -fx-font-size: 10;"}]})])}))

(defn- projector-config-panel
  "Configuration panel for the selected projector.
   Uses the self-contained effect chain sidebar with callbacks."
  [{:keys [fx/context]}]
  (let [projector (fx/sub-ctx context subs/active-projector)
        projector-id (:id projector)
        ;; Get UI state from list-ui domain (where list/list-editor stores selection)
        ;; Component ID must match what's used in projector-effects-sidebar
        list-component-id (when projector-id [:projector-effects projector-id])
        list-ui-state (when list-component-id
                        (fx/sub-ctx context subs/list-ui-state list-component-id))
        selected-ids (:selected-ids list-ui-state #{})
        ;; Also get projector-effect-ui-state for UI modes (active curve channel, etc.)
        ;; This is separate from selection state and used by effect parameter editors
        effect-ui-state (when projector-id
                          (fx/sub-ctx context subs/projector-effect-ui-state projector-id))
        ;; Read effects from chains domain
        effects (when projector-id
                  (fx/sub-ctx context subs/projector-effect-chain projector-id))
        ;; Get selected effect for parameter editor (first selected if single select)
        first-selected-id (when (= 1 (count selected-ids))
                            (first selected-ids))
        selected-effect-path (when first-selected-id
                               (chains/find-path-by-id effects first-selected-id))
        selected-effect (when selected-effect-path
                          (chains/get-item-at-path effects selected-effect-path))
        clipboard-items (clipboard/get-effects-to-paste)]
    (if projector
      {:fx/type :v-box
       :children [{:fx/type :scroll-pane
                   :v-box/vgrow :always
                   :fit-to-width true
                   :fit-to-height true
                   :style (str "-fx-background-color: " (css/bg-primary) "; -fx-background: " (css/bg-primary) ";")
                   :content {:fx/type :h-box
                             :spacing 16
                             :padding 16
                             :style (str "-fx-background-color: " (css/bg-primary) ";")
                             :children [;; Left: Effect chain sidebar
                                        {:fx/type :v-box
                                         :spacing 8
                                         :pref-width 280
                                         :children [{:fx/type :label
                                                     :id "projector-config-title"
                                                     :text (str "CONFIGURE: " (:name projector))
                                                     :style "-fx-text-fill: white; -fx-font-size: 14; -fx-font-weight: bold;"}
                                                    {:fx/type projector-basic-settings
                                                     :projector projector}
                                                    {:fx/type :separator}
                                                    ;; Zone Group Assignment
                                                    {:fx/type zone-group-assignment-section
                                                     :fx/context context
                                                     :projector-id projector-id}
                                                    {:fx/type :separator}
                                                    {:fx/type :h-box
                                                     :alignment :center-left
                                                     :children [{:fx/type :label
                                                                 :text "CALIBRATION EFFECTS"
                                                                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                                                                {:fx/type :region :h-box/hgrow :always}
                                                                {:fx/type projector-add-effect-menu
                                                                 :projector-id projector-id}]}
                                                    {:fx/type projector-effects-sidebar
                                                     :fx/key projector-id  ;; Force recreation when projector changes to avoid stale closures
                                                     :fx/context context
                                                     :projector-id projector-id
                                                     :selected-ids selected-ids
                                                     :clipboard-items clipboard-items}
                                                    {:fx/type :separator}
                                                    {:fx/type :button
                                                     :id "projector-remove-button"
                                                     :text "Remove..."
                                                     :style-class ["button-danger"]
                                                     :tooltip {:fx/type :tooltip
                                                               :text "Remove this projector and its calibration"}
                                                     :on-action {:event/type :projectors/request-remove
                                                                 :projector-id projector-id}}]}
                                        ;; Right: Parameter editor + calibration controls
                                        {:fx/type :v-box
                                         :spacing 16
                                         :h-box/hgrow :always
                                         :children (vec
                                                      (concat
                                                        (when selected-effect
                                                           [{:fx/type effect-parameter-editor
                                                             :projector-id projector-id
                                                             :effect-path selected-effect-path
                                                             :effect selected-effect
                                                             :ui-state effect-ui-state}])
                                                       [{:fx/type :region :v-box/vgrow :always}
                                                        {:fx/type calibration-controls
                                                         :fx/context context
                                                         :projector-id projector-id}]))}]}}]}
      ;; No projector selected
      {:fx/type :v-box
       :children [{:fx/type :scroll-pane
                   :v-box/vgrow :always
                   :fit-to-width true
                   :fit-to-height true
                   :style (str "-fx-background-color: " (css/bg-primary) "; -fx-background: " (css/bg-primary) ";")
                   :content {:fx/type :v-box
                             :spacing 16
                             :padding 16
                             :style (str "-fx-background-color: " (css/bg-primary) "; -fx-background-radius: 4;")
                             :children [{:fx/type :label
                                         :text "Select a projector to configure"
                                         :style "-fx-text-fill: #606060; -fx-font-style: italic;"}]}}]})))


;; Main Tab


(defn projectors-tab
  "Complete projectors tab with discovery and configuration."
  [{:keys [fx/context]}]
  {:fx/type :v-box
   :style (str "-fx-background-color: " (css/bg-primary) ";")
   :padding 16
   :spacing 8
   :children [{:fx/type :label
               :text "Projector Configuration"
               :style "-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;"}
              {:fx/type :label
               :id "projectors-subtitle"
               :text "Scan for IDN devices • Configure color calibration"
               :style "-fx-text-fill: #808080; -fx-font-size: 11;"}
              {:fx/type :h-box
               :spacing 16
               :v-box/vgrow :always
               :children [{:fx/type device-discovery-panel}
                          {:fx/type configured-projectors-list}
                          {:fx/type projector-config-panel}]}]})
