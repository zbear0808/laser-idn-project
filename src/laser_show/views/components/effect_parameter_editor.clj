(ns laser-show.views.components.effect-parameter-editor
  "Unified effect parameter editor component with keyframe modulator support.
   
   Works for both :effect-chains and :cue-chains domains.
   
   Features:
   - Keyframe modulator panel integration
   - Parameter source selection (keyframe params vs base params)
   - Event routing (keyframe events vs standard events)
   - Custom renderer support (RGB curves, spatial editors)
   - Domain-aware path handling
   
   Usage in effect chain editor:
   {:fx/type effect-parameter-editor
    :domain :effect-chains
    :entity-key [col row]
    :effect-path [0]               ; direct path to effect in chain
    :effect effect-instance
    :effect-def effect-definition
    :dialog-data dialog-data}
   
   Usage in cue chain editor:
   {:fx/type effect-parameter-editor
    :domain :cue-chains
    :entity-key [col row]
    :effect-path [0]               ; path within item's :effects
    :item-path [0 :items 1]        ; path to parent item in cue chain
    :effect effect-instance
    :effect-def effect-definition
    :dialog-data dialog-data}"
  (:require

   [laser-show.views.components.visual-editors.keyframe-modulator-panel :as keyframe-panel]
   [laser-show.views.components.effect-param-ui :as effect-param-ui]
   [laser-show.views.components.transform-advanced :as transform-advanced]))


;; Helper Functions


(defn- compute-full-effect-path
  "Compute the full effect path based on domain.
   
   For :effect-chains: returns effect-path as-is
   For :cue-chains (and the timeline's :timeline-items / :timeline-tracks):
   returns (concat item-path [:effects] effect-path)"
  [domain item-path effect-path]
  (if (#{:cue-chains :timeline-items :timeline-tracks} domain)
    (vec (concat item-path [:effects] effect-path))
    (vec effect-path)))


;; Internal Renderer Components


(defn- custom-param-renderer-internal
  "Internal custom param renderer with keyframe-aware event routing.
   
   Props:
   - :entity-key - [col row]
   - :full-effect-path - Full computed path to effect
   - :effect-def - Effect definition with :ui-hints
   - :current-params - Current parameter values (may be from keyframe)
   - :ui-mode - Current UI mode (:visual or :numeric)
   - :params-map - Parameter specifications map
   - :dialog-data - Dialog state data
   - :domain - :effect-chains or :cue-chains
   - :keyframe-enabled? - Whether keyframe mode is active
   - :selected-kf-idx - Selected keyframe index (used for canvas key when keyframe mode active)
   - :on-change-event - Event for parameter changes
   - :on-text-event - Event for text input changes
   - :spatial-event-template - Event for spatial editor updates
   - :modulator-event-base - Base of the modulator events, nil while
     keyframe mode is active"
  [{:keys [entity-key full-effect-path effect-def current-params
           ui-mode params-map dialog-data domain keyframe-enabled? selected-kf-idx
           on-change-event on-text-event spatial-event-template on-color-change-event
           modulator-event-base]}]
  {:fx/type effect-param-ui/custom-param-renderer
   :effect-def effect-def
   :current-params current-params
   :ui-mode ui-mode
   :params-map params-map
   :dialog-data dialog-data

   ;; Disable modulators when keyframe mode is active
   :enable-modulators? (not keyframe-enabled?)

   ;; Event templates for spatial editors (translate, corner-pin)
   ;; Include keyframe-idx when keyframe mode is enabled so canvas is recreated on keyframe change
   :spatial-event-template spatial-event-template
   :spatial-event-keys {:domain domain
                        :entity-key entity-key
                        :effect-path full-effect-path
                        :effect-instance-id (:effect-instance-id on-change-event)
                        :keyframe-idx (when keyframe-enabled? selected-kf-idx)}

   ;; Event templates for param controls
   :on-change-event on-change-event
   :on-text-event on-text-event
   :on-mode-change-event {:event/type :chain/set-ui-mode
                          :domain domain
                          :entity-key entity-key
                          :effect-path full-effect-path}
   ;; Modulator event base (disabled when keyframe mode active)
   :modulator-event-base modulator-event-base

   ;; RGB curves props
   :rgb-domain domain
   :rgb-entity-key entity-key
   :rgb-effect-path full-effect-path
   :on-color-change-event on-color-change-event})


;; Main Component


(defn effect-parameter-editor
  "Unified parameter editor with keyframe support.
   
   Works for both effect chains and cue chains.
   
   Props:
   - :domain - :effect-chains or :cue-chains (required)
   - :entity-key - [col row] grid coordinates (required)
   - :effect-path - Path to effect (required)
     - For effect chains: direct path like [0]
     - For cue chains: path within item's :effects like [0]
   - :item-path - Path to parent item (required for cue chains only)
   - :owner-id - The :id of the item, clip, track or folder that owns the
     effect (cue chains and the timeline's): with the effect's own :id, how
     the modulator's field edits find it (reactivity plan 07)
   - :effect - The effect instance map (required)
   - :effect-def - Effect definition from registry (required)
   - :dialog-data - Dialog state data for UI modes, etc. (required)
   - :current-phase - Current playback phase for timeline preview (optional)"
  [{:keys [domain entity-key effect-path item-path owner-id
           effect effect-def dialog-data current-phase]}]
  (let [;; Compute full effect path based on domain
        full-effect-path (compute-full-effect-path domain item-path effect-path)

        ;; Extract keyframe modulator state
        keyframe-mod (:keyframe-modulator effect)
        keyframe-enabled? (:enabled? keyframe-mod false)
        selected-kf-idx (:selected-keyframe keyframe-mod 0)
        keyframes (:keyframes keyframe-mod [])

        ;; Choose param source:
        ;; - When keyframe mode enabled: edit selected keyframe's params
        ;; - When keyframe mode disabled: edit effect's base params
        current-params (if (and keyframe-enabled? (seq keyframes))
                         (get-in keyframes [selected-kf-idx :params] {})
                         (:params effect {}))



        ;; Convert params to map format for UI
        params-map (effect-param-ui/params-vector->map (:parameters effect-def []))

        ;; Get UI mode from dialog data - stored by full effect path
        ui-mode (get-in dialog-data [:ui-modes full-effect-path])

        ;; Build base event params
        base-event-params {:domain domain
                           :entity-key entity-key
                           :effect-path full-effect-path}

        ;; Choose event types based on keyframe mode; both name the instance
        ;; (the step grid window opens on it, from Edit grid…)
        on-change-event (if keyframe-enabled?
                          {:event/type :keyframe/update-param
                           :domain domain
                           :entity-key entity-key
                           :effect-path full-effect-path
                           :effect-instance-id (:id effect)
                           :keyframe-idx selected-kf-idx}
                          {:event/type :chain/update-param
                           :domain domain
                           :entity-key entity-key
                           :effect-path full-effect-path
                           :effect-instance-id (:id effect)})

        on-text-event (if keyframe-enabled?
                        {:event/type :keyframe/update-param
                         :domain domain
                         :entity-key entity-key
                         :effect-path full-effect-path
                         :keyframe-idx selected-kf-idx}
                        {:event/type :chain/update-param-from-text
                         :domain domain
                         :entity-key entity-key
                         :effect-path full-effect-path})

        ;; Color change event (depends on keyframe mode)
        on-color-change-event (if keyframe-enabled?
                                {:event/type :keyframe/update-color-param
                                 :domain domain
                                 :entity-key entity-key
                                 :effect-path full-effect-path
                                 :keyframe-idx selected-kf-idx}
                                {:event/type :chain/update-color-param
                                 :domain domain
                                 :entity-key entity-key
                                 :effect-path full-effect-path})

        ;; Spatial editor event template
        ;; Note: Using :keyframe/update-spatial-params which converts point-id/x/y
        ;; to proper params using param-map (same as :chain/update-spatial-params)
        spatial-event-template (if keyframe-enabled?
                                 {:event/type :keyframe/update-spatial-params
                                  :domain domain
                                  :entity-key entity-key
                                  :effect-path full-effect-path
                                  :keyframe-idx selected-kf-idx}
                                 {:event/type :chain/update-spatial-params
                                  :domain domain
                                  :entity-key entity-key
                                  :effect-path full-effect-path})

        ;; Modulator event base, none while keyframes are on: the toggle finds
        ;; the effect at its path, the modulator's field edits by its id and
        ;; owner's, wherever it is when they arrive (reactivity plan 07)
        modulator-event-base (when-not keyframe-enabled?
                               (cond-> (assoc base-event-params :effect-instance-id (:id effect))
                                 (some? owner-id) (assoc :owner-id owner-id)))]

    {:fx/type :v-box
     :spacing 8
     :style-class "dialog-section"
     ;; Filter out nil children
     :children (filterv some?
                        [;; Keyframe modulator panel (always shown for effects that could use it)
                         (when effect-def
                           {:fx/type keyframe-panel/keyframe-modulator-panel
                            :keyframe-modulator keyframe-mod
                            :domain domain
                            :entity-key entity-key
                            :effect-path full-effect-path
                            :current-phase current-phase
                            :effect-def effect-def})

                         ;; Section header with effect name and keyframe indicator
                         {:fx/type :label
                          :text (if effect-def
                                  (str "PARAMETERS: " (:name effect-def)
                                       (when keyframe-enabled?
                                         (str " (Keyframe " (inc selected-kf-idx) ")")))
                                  "PARAMETERS")
                          :style-class "header-section"}

                         ;; Parameter controls
                         (if effect-def
                           (if (:ui-hints effect-def)
                             ;; Has custom UI - use custom renderer with mode toggle
                             ;; Add :fx/key to force recreation when keyframe changes (spatial canvas has internal atoms)
                             (let [param-editor-key (if keyframe-enabled?
                                                      [full-effect-path :kf selected-kf-idx]
                                                      full-effect-path)]
                               {:fx/type :scroll-pane
                                :fx/key param-editor-key
                                :fit-to-width true
                                :style-class "scroll-pane-base"
                                :v-box/vgrow :always
                                :content {:fx/type custom-param-renderer-internal
                                          :entity-key entity-key
                                          :full-effect-path full-effect-path
                                          :effect-def effect-def
                                          :current-params current-params
                                          :ui-mode ui-mode
                                          :params-map params-map
                                          :dialog-data dialog-data
                                          :domain domain
                                          :keyframe-enabled? keyframe-enabled?
                                          :selected-kf-idx selected-kf-idx
                                          :on-change-event on-change-event
                                          :on-text-event on-text-event
                                          :on-color-change-event on-color-change-event
                                          :spatial-event-template spatial-event-template
                                          :modulator-event-base modulator-event-base}})

                             ;; Standard parameters - use modulatable param controls
                             ;; Disable modulators when in keyframe mode (keyframes replace modulators)
                             {:fx/type :scroll-pane
                              :fit-to-width true
                              :style-class "scroll-pane-base"
                              :v-box/vgrow :always
                              :content {:fx/type effect-param-ui/modulatable-param-controls-list
                                        :params-map params-map
                                        :current-params current-params
                                        :on-change-event on-change-event
                                        :on-text-event on-text-event
                                        ;; Disable per-param modulators when keyframe mode is on
                                        :enable-modulators? (not keyframe-enabled?)
                                        :modulator-event-base modulator-event-base}})

                           ;; No effect selected
                           {:fx/type :label
                            :text "Select an effect from the chain"
                            :style-class "dialog-placeholder-text"})
                         (transform-advanced/control effect-def effect base-event-params)])}))
