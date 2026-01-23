(ns laser-show.events.handlers.keyframe
  "Event handlers for keyframe modulator operations.
   
   Keyframe modulators allow users to define parameter values at specific
   positions within a period, with automatic linear interpolation between
   keyframes. Unlike per-parameter modulators, keyframe modulators control
   all parameters of an effect at once.
   
   Data Model:
   Effect instances can have an optional :keyframe-modulator property:
   {:id #uuid \"...\"
    :effect-id :translate
    :params {:x 0.5 :y 0.3}  ;; Static base values
    :keyframe-modulator {:enabled? true
                         :period 4.0
                         :time-unit :beats
                         :loop-mode :loop
                         :selected-keyframe 0
                         :keyframes [{:position 0.0 :params {...}}
                                     {:position 0.5 :params {...}}
                                     {:position 1.0 :params {...}}]}}
   
   Events use :domain and :entity-key to locate the chain, and :effect-path
   to locate the effect within that chain.
   
   DEBUG: Set log level to DEBUG for this namespace to see keyframe operations."
  (:require
   [clojure.tools.logging :as log]
   [laser-show.animation.modulator-defs :as mod-defs]
   [laser-show.events.helpers :as h]
   [laser-show.events.handlers.chain :as chain]))


;; Helper Functions


(defn- get-keyframe-modulator-path
  "Get the full path to the keyframe modulator for an effect.
   
   Parameters:
   - config: Chain config from chain/chain-config
   - effect-path: Path to effect within chain
   
   Returns: Full path vector to :keyframe-modulator"
  [config effect-path]
  (into (:items-path config) (conj (vec effect-path) :keyframe-modulator)))

(defn- get-keyframe-modulator
  "Get the keyframe modulator for an effect."
  [state config effect-path]
  (get-in state (get-keyframe-modulator-path config effect-path)))

(defn- set-keyframe-modulator
  "Set the keyframe modulator for an effect."
  [state config effect-path keyframe-mod]
  (assoc-in state (get-keyframe-modulator-path config effect-path) keyframe-mod))

(defn- update-keyframe-modulator
  "Update the keyframe modulator for an effect."
  [state config effect-path f & args]
  (apply update-in state (get-keyframe-modulator-path config effect-path) f args))

(defn- get-effect-params
  "Get the current params of an effect."
  [state config effect-path]
  (let [items-path (:items-path config)
        effect (get-in state (into items-path (vec effect-path)))]
    (:params effect {})))

(defn- sort-keyframes
  "Sort keyframes by position."
  [keyframes]
  (vec (sort-by :position keyframes)))

(defn- create-default-keyframes
  "Create default keyframes using the effect's current params.
   Creates two keyframes at positions 0.0 and 1.0 with identical params."
  [params]
  [{:position 0.0 :params params}
   {:position 1.0 :params params}])

(defn- clamp-position
  "Clamp a position to valid range [0.0, 1.0]."
  ^double [^double pos]
  (max 0.0 (min 1.0 pos)))

(defn- normalize-param-value
  "Extract static value from a param that may be a modulator config.
   Keyframes should only contain static numeric values."
  [value default]
  (if (mod-defs/modulated? value)
    (mod-defs/get-static-value value default)
    (or value default)))

(defn- normalize-params-for-keyframes
  "Extract static values from any modulator configs in params.
   Keyframes should only contain static numeric values."
  [params]
  (into {}
        (map (fn [[k v]]
               [k (normalize-param-value v 0.0)]))
        params))

(defn- deactivate-all-modulators
  "Set :active? false on all modulator configs in params.
   This preserves the modulator settings but prevents them from running."
  [params]
  (into {}
        (map (fn [[k v]]
               (if (mod-defs/modulated? v)
                 [k (assoc v :active? false)]
                 [k v])))
        params))

(defn- get-effect-path-for-params
  "Get the full path to effect params."
  [config effect-path]
  (into (:items-path config) (conj (vec effect-path) :params)))

(defn- update-effect-params
  "Update the params of an effect."
  [state config effect-path f & args]
  (apply update-in state (get-effect-path-for-params config effect-path) f args))


;; Keyframe Modulator Handlers


(defn handle-toggle-enabled
  "Toggle the enabled state of the keyframe modulator.
   If enabling and no keyframe modulator exists, initializes one with defaults.
   
   When enabling keyframes:
   - Normalizes params (extracts static values from any modulator configs)
   - Deactivates all per-param modulators (keyframes and modulators are mutually exclusive)
   
   Parameters:
   - state: Application state
   - config: Chain config
   - effect-path: Path to effect
   - enabled?: New enabled state
   
   Returns: Updated state"
  [state config effect-path enabled?]
  (let [current-mod (get-keyframe-modulator state config effect-path)]
    (if (and enabled? (nil? current-mod))
      ;; Initialize keyframe modulator with defaults when enabling
      (let [params (get-effect-params state config effect-path)
            ;; Normalize params: extract static values from any modulator configs
            normalized-params (normalize-params-for-keyframes params)
            new-mod {:enabled? true
                     :period 4.0
                     :time-unit :beats
                     :loop-mode :loop
                     :selected-keyframe 0
                     :keyframes (create-default-keyframes normalized-params)}]
        (-> state
            ;; Deactivate all per-param modulators
            (update-effect-params config effect-path deactivate-all-modulators)
            (set-keyframe-modulator config effect-path new-mod)
            h/mark-dirty))
      ;; Just toggle enabled state
      (if enabled?
        ;; Re-enabling existing keyframe modulator - also deactivate modulators
        (-> state
            (update-effect-params config effect-path deactivate-all-modulators)
            (update-keyframe-modulator config effect-path assoc :enabled? enabled?)
            h/mark-dirty)
        ;; Disabling keyframes - just toggle
        (-> state
            (update-keyframe-modulator config effect-path assoc :enabled? enabled?)
            h/mark-dirty)))))

(defn handle-initialize
  "Initialize a keyframe modulator with default keyframes.
   Uses the effect's current params as the initial keyframe values.
   Normalizes params and deactivates any active modulators.
   
   Parameters:
   - state: Application state
   - config: Chain config
   - effect-path: Path to effect
   
   Returns: Updated state"
  [state config effect-path]
  (let [params (get-effect-params state config effect-path)
        normalized-params (normalize-params-for-keyframes params)
        new-mod {:enabled? true
                 :period 4.0
                 :time-unit :beats
                 :loop-mode :loop
                 :selected-keyframe 0
                 :keyframes (create-default-keyframes normalized-params)}]
    (-> state
        (update-effect-params config effect-path deactivate-all-modulators)
        (set-keyframe-modulator config effect-path new-mod)
        h/mark-dirty)))

(defn handle-update-settings
  "Update keyframe modulator settings (period, time-unit, loop-mode).
   
   Parameters:
   - state: Application state
   - config: Chain config
   - effect-path: Path to effect
   - settings: Map with any of :period, :time-unit, :loop-mode
   
   Returns: Updated state"
  [state config effect-path settings]
  (-> state
      (update-keyframe-modulator config effect-path merge settings)
      h/mark-dirty))

(defn handle-update-setting
  "Update a single keyframe modulator setting.
   
   Parameters:
   - state: Application state
   - config: Chain config
   - effect-path: Path to effect
   - setting-key: One of :period, :time-unit, :loop-mode
   - value: New value for the setting
   
   Returns: Updated state"
  [state config effect-path setting-key value]
  (-> state
      (update-keyframe-modulator config effect-path assoc setting-key value)
      h/mark-dirty))

(defn handle-select-keyframe
  "Select a keyframe for editing.
   
   Parameters:
   - state: Application state
   - config: Chain config
   - effect-path: Path to effect
   - keyframe-idx: Index of keyframe to select
   
   Returns: Updated state"
  [state config effect-path keyframe-idx]
  (let [keyframe-mod (get-keyframe-modulator state config effect-path)
        keyframes (:keyframes keyframe-mod [])
        selected-kf-params (when (and keyframe-idx (< keyframe-idx (count keyframes)))
                             (get-in keyframes [keyframe-idx :params]))]
    (log/info "KEYFRAME SELECT - idx:" keyframe-idx
              "total-keyframes:" (count keyframes)
              "selected-kf-params:" selected-kf-params)
    (update-keyframe-modulator state config effect-path
                               assoc :selected-keyframe keyframe-idx)))

(defn handle-add-keyframe
  "Add a new keyframe at the specified position.
   Interpolates params from surrounding keyframes, or uses provided params.
   
   Parameters:
   - state: Application state
   - config: Chain config
   - effect-path: Path to effect
   - position: Position (0.0-1.0) for the new keyframe
   - params: Optional params map (if nil, interpolates from neighbors)
   
   Returns: Updated state"
  [state config effect-path position params]
  (let [keyframe-mod (get-keyframe-modulator state config effect-path)
        keyframes (:keyframes keyframe-mod [])
        clamped-pos (clamp-position position)
        ;; Use provided params or interpolate/copy from nearest keyframe
        kf-params (or params
                      (if (seq keyframes)
                        (:params (first (sort-by #(Math/abs (- (:position %) clamped-pos)) keyframes)))
                        (get-effect-params state config effect-path)))
        new-keyframe {:position clamped-pos :params kf-params}
        new-keyframes (sort-keyframes (conj keyframes new-keyframe))
        new-idx (.indexOf new-keyframes new-keyframe)]
    (-> state
        (update-keyframe-modulator config effect-path
                                   assoc
                                   :keyframes new-keyframes
                                   :selected-keyframe new-idx)
        h/mark-dirty)))

(defn handle-move-keyframe
  "Move a keyframe to a new position.
   
   Parameters:
   - state: Application state
   - config: Chain config
   - effect-path: Path to effect
   - keyframe-idx: Index of keyframe to move
   - new-position: New position (0.0-1.0)
   
   Returns: Updated state (unchanged if index invalid)"
  [state config effect-path keyframe-idx new-position]
  (let [keyframe-mod (get-keyframe-modulator state config effect-path)
        keyframes (:keyframes keyframe-mod [])
        clamped-pos (clamp-position new-position)]
    (if (and (>= keyframe-idx 0) (< keyframe-idx (count keyframes)))
      (let [updated-keyframe (assoc (nth keyframes keyframe-idx) :position clamped-pos)
            updated-keyframes (sort-keyframes
                               (assoc keyframes keyframe-idx updated-keyframe))
            ;; Find new index after sorting
            new-idx (.indexOf updated-keyframes updated-keyframe)]
        (-> state
            (update-keyframe-modulator config effect-path
                                       assoc
                                       :keyframes updated-keyframes
                                       :selected-keyframe new-idx)
            h/mark-dirty))
      state)))

(defn handle-delete-keyframe
  "Delete a keyframe. Cannot delete if only one keyframe remains.
   
   Parameters:
   - state: Application state
   - config: Chain config
   - effect-path: Path to effect
   - keyframe-idx: Index of keyframe to delete
   
   Returns: Updated state"
  [state config effect-path keyframe-idx]
  (let [keyframe-mod (get-keyframe-modulator state config effect-path)
        keyframes (:keyframes keyframe-mod [])]
    ;; Don't delete if it would leave less than 1 keyframe
    (if (and (> (count keyframes) 1)
             (>= keyframe-idx 0)
             (< keyframe-idx (count keyframes)))
      (let [new-keyframes (vec (concat (subvec keyframes 0 keyframe-idx)
                                       (subvec keyframes (inc keyframe-idx))))
            ;; Adjust selected index if needed
            selected (:selected-keyframe keyframe-mod 0)
            new-selected (cond
                           (< selected keyframe-idx) selected
                           (>= selected (count new-keyframes)) (dec (count new-keyframes))
                           :else (max 0 (dec selected)))]
        (-> state
            (update-keyframe-modulator config effect-path
                                       assoc
                                       :keyframes new-keyframes
                                       :selected-keyframe new-selected)
            h/mark-dirty))
      state)))

(defn handle-update-keyframe-param
  "Update a specific parameter in the selected keyframe.
   
   Parameters:
   - state: Application state
   - config: Chain config
   - effect-path: Path to effect
   - keyframe-idx: Index of keyframe to update
   - param-key: Parameter key to update
   - value: New value
   
   Returns: Updated state"
  [state config effect-path keyframe-idx param-key value]
  (let [keyframe-mod (get-keyframe-modulator state config effect-path)
        keyframes (:keyframes keyframe-mod [])]
    (if (and (>= keyframe-idx 0) (< keyframe-idx (count keyframes)))
      (-> state
          (update-keyframe-modulator config effect-path
                                     assoc-in [:keyframes keyframe-idx :params param-key] value)
          h/mark-dirty)
      state)))

(defn handle-update-keyframe-params
  "Update all parameters for a keyframe (e.g., from spatial editor).
   
   Parameters:
   - state: Application state
   - config: Chain config
   - effect-path: Path to effect
   - keyframe-idx: Index of keyframe to update
   - params: Full params map
   
   Returns: Updated state"
  [state config effect-path keyframe-idx params]
  (let [keyframe-mod (get-keyframe-modulator state config effect-path)
        keyframes (:keyframes keyframe-mod [])]
    (if (and (>= keyframe-idx 0) (< keyframe-idx (count keyframes)))
      (-> state
          (update-keyframe-modulator config effect-path
                                     update-in [:keyframes keyframe-idx :params] merge params)
          h/mark-dirty)
      state)))

(defn handle-update-spatial-params
  "Update keyframe params from spatial drag operation (translate, corner-pin).
   Converts point-id/x/y coordinates to params using param-map.
   
   Parameters:
   - state: Application state
   - config: Chain config
   - effect-path: Path to effect
   - keyframe-idx: Index of keyframe to update
   - point-id: ID of dragged point (e.g., :center, :tl, :tr, :br, :bl)
   - x, y: New coordinates in world space
   - param-map: Map from point IDs to param key pairs
                Example: {:center {:x :x :y :y}
                         :tl {:x :tl-x :y :tl-y}}
   
   Returns: Updated state with both x and y params set, or unchanged if point-id not found"
  [state config effect-path keyframe-idx point-id x y param-map]
  (let [point-params (get param-map point-id)]
    (if point-params
      (let [x-key (:x point-params)
            y-key (:y point-params)
            params {x-key x y-key y}]
        (handle-update-keyframe-params state config effect-path keyframe-idx params))
      state)))

(defn handle-copy-effect-params-to-keyframe
  "Copy the effect's current base params to a keyframe.
   
   Parameters:
   - state: Application state
   - config: Chain config
   - effect-path: Path to effect
   - keyframe-idx: Index of keyframe to copy params to
   
   Returns: Updated state"
  [state config effect-path keyframe-idx]
  (let [params (get-effect-params state config effect-path)
        keyframe-mod (get-keyframe-modulator state config effect-path)
        keyframes (:keyframes keyframe-mod [])]
    (if (and (>= keyframe-idx 0) (< keyframe-idx (count keyframes)))
      (-> state
          (update-keyframe-modulator config effect-path
                                     assoc-in [:keyframes keyframe-idx :params] params)
          h/mark-dirty)
      state)))



(defn handle
  "Handle keyframe modulator events.
   
   Events use :domain and :entity-key to locate the chain via chain-config,
   and :effect-path to locate the effect within that chain."
  [{:keys [event/type domain entity-key effect-path state] :as event}]
  (log/debug "keyframe/handle - type:" type "domain:" domain "entity-key:" entity-key
             "effect-path:" effect-path)
  (let [config (chain/chain-config domain entity-key)]
    (case type
      :keyframe/toggle-enabled
      {:state (handle-toggle-enabled state config effect-path (:enabled? event))}
      
      :keyframe/initialize
      {:state (handle-initialize state config effect-path)}
      
      :keyframe/update-settings
      {:state (handle-update-settings state config effect-path
                                      (select-keys event [:period :time-unit :loop-mode]))}
      
      :keyframe/update-setting
      {:state (handle-update-setting state config effect-path
                                     (:setting-key event)
                                     (:fx/event event))}
      
      :keyframe/select
      {:state (handle-select-keyframe state config effect-path (:keyframe-idx event))}
      
      :keyframe/add
      {:state (handle-add-keyframe state config effect-path (:position event) (:params event))}
      
      :keyframe/move
      {:state (handle-move-keyframe state config effect-path
                                    (:keyframe-idx event) (:new-position event))}
      
      :keyframe/delete
      {:state (handle-delete-keyframe state config effect-path (:keyframe-idx event))}
      
      :keyframe/update-param
      {:state (handle-update-keyframe-param state config effect-path
                                            (:keyframe-idx event)
                                            (:param-key event)
                                            (or (:value event) (:fx/event event)))}
      
      :keyframe/update-params
      {:state (handle-update-keyframe-params state config effect-path
                                             (:keyframe-idx event)
                                             (:params event))}
      
      :keyframe/update-spatial-params
      {:state (handle-update-spatial-params state config effect-path
                                            (:keyframe-idx event)
                                            (:point-id event)
                                            (:x event)
                                            (:y event)
                                            (:param-map event))}
      
      :keyframe/copy-effect-params
      {:state (handle-copy-effect-params-to-keyframe state config effect-path
                                                     (:keyframe-idx event))}
      
      ;; Unknown keyframe event
      (do
        (log/warn "Unknown keyframe event type:" type)
        {}))))
