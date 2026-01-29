(ns laser-show.views.components.effect-param-ui
  "Shared effect parameter UI components.
   
   Used by both effect-chain-editor and cue-chain-editor to render
   effect parameters with standard controls (sliders, checkboxes, etc.)
   and custom visual editors (translate, corner-pin, RGB curves).
   
   Key Features:
   - Event-template pattern for flexible event routing
   - Support for visual/numeric mode toggle
   - Automatic control type selection based on parameter spec
   - Custom visual renderers for spatial and curve effects
   - Optional modulator support for numeric parameters
   
   Uses parameter-controls namespace for basic control components."
  (:require [laser-show.animation.modulator-defs :as mod-defs]
            [laser-show.views.components.parameter-controls :as param-controls]
            [laser-show.views.components.visual-editors.custom-param-renderers :as custom-renderers]
            [laser-show.views.components.modulator-param-control :as mod-param]
            [laser-show.views.components.icons :as icons]))


;; Re-export commonly used functions from parameter-controls


(def params-vector->map
  "Convert effect parameters from vector format (registry) to map format (UI).
   See parameter-controls/params-vector->map for full docs."
  param-controls/params-vector->map)

(def modulatable-param-controls-list
  "Parameter controls list with modulator support.
   See modulator-param-control/modulatable-param-controls-list for full docs."
  mod-param/modulatable-param-controls-list)


;; Custom Parameter Renderer with Visual Editors


(defn custom-param-renderer
  "Renders effect parameters with custom UI and mode toggle.
   
   Supports custom renderers:
   1. RGB Curves - Visual-only curve editor with R/G/B tabs
   2. Translate (spatial-2d) - Drag point to adjust X/Y position
   3. Corner Pin (corner-pin-2d) - Drag 4 corners for perspective mapping
   4. Rotation (rotation-dial) - Circular dial for angle adjustment
   5. Scale (scale-2d) - Rectangle with edge/corner handles for X/Y scaling
   
   Props:
   - :effect-def - Effect definition with :ui-hints {:renderer :spatial-2d ...}
   - :current-params - Current parameter values map
   - :params-map - Parameter specifications map (from params-vector->map)
   - :ui-mode - Current UI mode (:visual or :numeric)
   - :dialog-data - Dialog state data (for curve editor channel tracking, ui-modes)
   - :enable-modulators? - Enable modulator toggle buttons on numeric params (default false)
   
   Event template props (for visual editors):
   - :spatial-event-template - Base event for spatial param updates (translate, corner-pin)
   - :spatial-event-keys - Additional keys to pass through (e.g., :col, :row, :effect-path)
   
   Event template props (for param controls):
   - :on-change-event - Event template for param value changes
   - :on-text-event - Event template for text field updates
   - :on-mode-change-event - Event template for visual/numeric mode toggle
   - :modulator-event-base - Base event template for modulator operations (domain, entity-key, effect-path)
   
   RGB Curves props:
   - :rgb-domain - Domain for RGB curves (:effect-chains, :item-effects, etc.)
   - :rgb-entity-key - Entity key for RGB curves
   - :rgb-effect-path - Effect path for RGB curves"
  [{:keys [fx/context effect-def current-params ui-mode params-map dialog-data
           spatial-event-template spatial-event-keys
           on-change-event on-text-event on-mode-change-event modulator-event-base
           rgb-domain rgb-entity-key rgb-effect-path
           enable-modulators?]}]
  (let [;; Build canvas fx/key that includes keyframe index when in keyframe mode
        ;; This ensures the canvas is recreated when switching keyframes
        effect-path (get spatial-event-keys :effect-path)
        keyframe-idx (get spatial-event-keys :keyframe-idx)
        canvas-fx-key (if keyframe-idx
                        [effect-path :kf keyframe-idx]
                        effect-path)
        
        ui-hints (:ui-hints effect-def)
        renderer-type (:renderer ui-hints)
        ;; Check if any params have ACTIVE modulators
        has-modulators? (boolean (some (fn [[_k v]] (mod-defs/active-modulator? v)) current-params))
        ;; Single-param visual editors handle their own modulator UI, so they can
        ;; display in either mode even when modulators are active
        single-param-visual-editor? (contains? #{:rotation-dial :hue-slider :hue-shift-strip}
                                               renderer-type)
        ;; Determine actual mode: respect user's choice for single-param visual editors,
        ;; force numeric mode for other editors with modulators
        actual-mode (cond
                      ;; For single-param visual editors, always respect the user's requested mode
                      ;; (they support modulators in both visual and numeric modes)
                      single-param-visual-editor? (or ui-mode (:default-mode ui-hints :visual))
                      ;; Other effects with modulators must use numeric mode
                      has-modulators? :numeric
                      ;; No modulators - use requested mode or default
                      :else (or ui-mode (:default-mode ui-hints :visual)))
        ;; Select the appropriate param controls list based on modulator support
        param-list-type (if enable-modulators?
                          mod-param/modulatable-param-controls-list
                          param-controls/param-controls-list)]
    ;; RGB Curves is visual-only, no mode toggle
    (cond
      (= renderer-type :rgb-curves)
      {:fx/type custom-renderers/rgb-curves-visual-editor
       :current-params current-params
       :event-template {:event/type :chain/update-curve-point
                        :domain rgb-domain
                        :entity-key rgb-entity-key
                        :effect-path rgb-effect-path}
       :dialog-data dialog-data}
      
      ;; Zone reroute is visual-only (zone group selector)
      (= renderer-type :zone-reroute)
      {:fx/type custom-renderers/zone-reroute-visual-editor
       :fx/context context
       :current-params current-params
       :event-template on-change-event}
      
      ;; Other custom renderers have mode toggle
      :else
      {:fx/type :v-box
       :spacing 8
       :children [;; Mode toggle buttons
                  {:fx/type :h-box
                   :spacing 6
                   :alignment :center-left
                   :padding {:bottom 8}
                   :children (filterv some?
                               [{:fx/type :label
                                :text "Edit Mode:"
                                :style-class ["label-hint"]
                                :style "-fx-font-size: 10; -fx-font-style: normal;"}
                               ;; Visual mode button
                               ;; Single-param visual editors support modulators in both modes, so never disable visual
                               ;; Other editors with modulators must stay in numeric mode
                               (let [disable-visual? (and has-modulators? (not single-param-visual-editor?))]
                                 (cond-> {:fx/type :button
                                          :text "Visual"
                                          :graphic {:fx/type icons/icon :icon :eye :size 9}
                                          :disable disable-visual?
                                          :style-class [(cond
                                                          disable-visual? "chip"
                                                          (= actual-mode :visual) "chip-selected"
                                                          :else "chip")]
                                          :style (str "-fx-font-size: 9; -fx-padding: 3 10;"
                                                     (when disable-visual? " -fx-cursor: not-allowed;"))}
                                   ;; Add :on-action when we have a valid event and visual mode isn't blocked
                                   (and (not disable-visual?) on-mode-change-event)
                                   (assoc :on-action (assoc on-mode-change-event :mode :visual))))
                               ;; Numeric mode button - conditionally add :on-action only when valid
                               (cond-> {:fx/type :button
                                        :text "Numeric"
                                        :graphic {:fx/type icons/icon :icon :sliders :size 9}
                                        :style-class [(if (= actual-mode :numeric) "chip-selected" "chip")]
                                        :style "-fx-font-size: 9; -fx-padding: 3 10;"}
                                 on-mode-change-event
                                 (assoc :on-action (assoc on-mode-change-event :mode :numeric)))
                               ;; Show tooltip when modulators are blocking visual mode
                               ;; (but not for single-param visual editors that handle it internally)
                               (when (and has-modulators? (not single-param-visual-editor?))
                                 {:fx/type :label
                                  :text "(modulators active)"
                                  :style-class ["label-hint"]
                                  :style "-fx-font-size: 9;"})])}
                  
                  ;; Render based on mode
                  (if (= actual-mode :visual)
                    ;; Custom visual renderer
                    ;; Note: We use BOTH :fx/key (for cljfx component identity) and :fx-key (passed to inner canvas)
                    ;; The :fx/key on the outer component forces cljfx to recreate the whole tree when keyframe changes
                    (case renderer-type
                      :spatial-2d {:fx/type custom-renderers/translate-visual-editor
                                  :fx/key canvas-fx-key
                                  :current-params current-params
                                  :param-specs (:parameters effect-def)
                                  :event-template (merge spatial-event-template spatial-event-keys)
                                  :fx-key canvas-fx-key}
                      
                      :corner-pin-2d {:fx/type custom-renderers/corner-pin-visual-editor
                                     :fx/key canvas-fx-key
                                     :current-params current-params
                                     :param-specs (:parameters effect-def)
                                     :event-template (merge spatial-event-template spatial-event-keys)
                                     :fx-key canvas-fx-key}
                      
                      :rotation-dial {:fx/type custom-renderers/rotate-visual-editor
                                     :fx/key canvas-fx-key
                                     :current-params current-params
                                     :param-specs (:parameters effect-def)
                                     :event-template on-change-event
                                     :reset-event {:event/type :chain/reset-params
                                                   :domain (get spatial-event-keys :domain)
                                                   :entity-key (get spatial-event-keys :entity-key)
                                                   :effect-path effect-path}
                                     :fx-key canvas-fx-key
                                     ;; Modulator support for single-param visual editor
                                     :enable-modulator? enable-modulators?
                                     :param-spec (get params-map :angle)
                                     :modulator-event-base modulator-event-base}
                      
                      :scale-2d {:fx/type custom-renderers/scale-visual-editor
                                :fx/key canvas-fx-key
                                :current-params current-params
                                :param-specs (:parameters effect-def)
                                :event-template {:event/type :chain/update-scale-params
                                                 :domain (get spatial-event-keys :domain)
                                                 :entity-key (get spatial-event-keys :entity-key)
                                                 :effect-path effect-path}
                                :reset-event {:event/type :chain/reset-params
                                              :domain (get spatial-event-keys :domain)
                                              :entity-key (get spatial-event-keys :entity-key)
                                              :effect-path effect-path}
                                :fx-key canvas-fx-key}
                      
                      :hue-slider {:fx/type custom-renderers/hue-visual-editor
                                  :fx/key canvas-fx-key
                                  :current-params current-params
                                  :event-template on-change-event
                                  :fx-key canvas-fx-key
                                  ;; Modulator support for single-param visual editor
                                  :enable-modulator? enable-modulators?
                                  :param-spec (get params-map :hue)
                                  :modulator-event-base modulator-event-base}
                      
                      :hue-shift-strip {:fx/type custom-renderers/hue-shift-strip-visual-editor
                                       :fx/key canvas-fx-key
                                       :current-params current-params
                                       :event-template on-change-event
                                       :fx-key canvas-fx-key
                                       ;; Modulator support for single-param visual editor
                                       :enable-modulator? enable-modulators?
                                       :param-spec (get params-map :degrees)
                                       :modulator-event-base modulator-event-base}
                      
                      :set-color-picker {:fx/type custom-renderers/set-color-picker-visual-editor
                                        :fx/key canvas-fx-key
                                        :current-params current-params
                                        :event-template on-change-event
                                        :fx-key canvas-fx-key}
                      
                      ;; Fallback to standard params (with optional modulator support)
                      {:fx/type param-list-type
                       :params-map params-map
                       :current-params current-params
                       :on-change-event on-change-event
                       :on-text-event on-text-event
                       :modulator-event-base modulator-event-base})
                    
                    ;; Numeric mode - sliders (with optional modulator support)
                    {:fx/type param-list-type
                     :params-map params-map
                     :current-params current-params
                     :on-change-event on-change-event
                     :on-text-event on-text-event
                     :modulator-event-base modulator-event-base})]})))
