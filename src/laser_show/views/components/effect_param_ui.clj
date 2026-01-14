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
  (:require [laser-show.views.components.parameter-controls :as param-controls]
            [laser-show.views.components.custom-param-renderers :as custom-renderers]
            [laser-show.views.components.modulator-param-control :as mod-param]))


;; Re-export commonly used functions from parameter-controls


(def params-vector->map
  "Convert effect parameters from vector format (registry) to map format (UI).
   See parameter-controls/params-vector->map for full docs."
  param-controls/params-vector->map)

(def param-slider
  "Slider control for numeric parameters with editable text field.
   See parameter-controls/param-slider for full docs."
  param-controls/param-slider)

(def param-choice
  "Combo-box for choice parameters.
   See parameter-controls/param-choice for full docs."
  param-controls/param-choice)

(def param-checkbox
  "Checkbox for boolean parameters.
   See parameter-controls/param-checkbox for full docs."
  param-controls/param-checkbox)

(def param-control
  "Render appropriate control based on parameter type.
   See parameter-controls/param-control for full docs."
  param-controls/param-control)


;; Re-export modulator-aware controls

(def modulatable-param-control
  "Parameter control with modulator support for numeric types.
   See modulator-param-control/modulatable-param-control for full docs."
  mod-param/modulatable-param-control)

(def modulatable-param-controls-list
  "Parameter controls list with modulator support.
   See modulator-param-control/modulatable-param-controls-list for full docs."
  mod-param/modulatable-param-controls-list)


;; Custom Parameter Renderer with Visual Editors


(defn custom-param-renderer
  "Renders effect parameters with custom UI and mode toggle.
   
   Supports three types of custom renderers:
   1. RGB Curves - Visual-only curve editor with R/G/B tabs
   2. Translate (spatial-2d) - Drag point to adjust X/Y position
   3. Corner Pin (corner-pin-2d) - Drag 4 corners for perspective mapping
   
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
  [{:keys [effect-def current-params ui-mode params-map dialog-data
           spatial-event-template spatial-event-keys
           on-change-event on-text-event on-mode-change-event modulator-event-base
           rgb-domain rgb-entity-key rgb-effect-path
           enable-modulators?]}]
  (let [ui-hints (:ui-hints effect-def)
        renderer-type (:renderer ui-hints)
        ;; Check if any params have active modulators
        has-modulators? (boolean (some (fn [[_k v]] (map? v)) current-params))
        ;; Force numeric mode if modulators are active
        actual-mode (if has-modulators?
                      :numeric
                      (or ui-mode (:default-mode ui-hints :visual)))
        ;; Select the appropriate param controls list based on modulator support
        param-list-type (if enable-modulators?
                          mod-param/modulatable-param-controls-list
                          param-controls/param-controls-list)]
    ;; RGB Curves is visual-only, no mode toggle - use the public rgb-curves-visual-editor
    (if (= renderer-type :rgb-curves)
      {:fx/type custom-renderers/rgb-curves-visual-editor
       :domain rgb-domain
       :entity-key rgb-entity-key
       :effect-path rgb-effect-path
       :current-params current-params
       :dialog-data dialog-data}
      
      ;; Other custom renderers have mode toggle
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
                               :style "-fx-text-fill: #808080; -fx-font-size: 10;"}
                              ;; Visual mode button - conditionally add :on-action only when valid
                              (cond-> {:fx/type :button
                                       :text "👁 Visual"
                                       :disable has-modulators?
                                       :style (str "-fx-font-size: 9; -fx-padding: 3 10; "
                                                  (cond
                                                    has-modulators?
                                                    "-fx-background-color: #353535; -fx-text-fill: #606060; -fx-cursor: not-allowed;"
                                                    (= actual-mode :visual)
                                                    "-fx-background-color: #4A6FA5; -fx-text-fill: white;"
                                                    :else
                                                    "-fx-background-color: #404040; -fx-text-fill: #B0B0B0;"))}
                                ;; Only add :on-action when we have a valid event and modulators aren't blocking
                                (and (not has-modulators?) on-mode-change-event)
                                (assoc :on-action (assoc on-mode-change-event :mode :visual)))
                              ;; Numeric mode button - conditionally add :on-action only when valid
                              (cond-> {:fx/type :button
                                       :text "🔢 Numeric"
                                       :style (str "-fx-font-size: 9; -fx-padding: 3 10; "
                                                  (if (= actual-mode :numeric)
                                                    "-fx-background-color: #4A6FA5; -fx-text-fill: white;"
                                                    "-fx-background-color: #404040; -fx-text-fill: #B0B0B0;"))}
                                on-mode-change-event
                                (assoc :on-action (assoc on-mode-change-event :mode :numeric)))
                              ;; Show tooltip when modulators are blocking visual mode
                              (when has-modulators?
                                {:fx/type :label
                                 :text "(modulators active)"
                                 :style "-fx-text-fill: #707070; -fx-font-size: 9; -fx-font-style: italic;"})])}
                  
                  ;; Render based on mode
                  (if (= actual-mode :visual)
                    ;; Custom visual renderer
                    (case renderer-type
                      :spatial-2d {:fx/type custom-renderers/translate-visual-editor
                                  :current-params current-params
                                  :param-specs (:parameters effect-def)
                                  :event-template (merge spatial-event-template spatial-event-keys)
                                  :fx-key (get spatial-event-keys :effect-path)}
                      
                      :corner-pin-2d {:fx/type custom-renderers/corner-pin-visual-editor
                                     :current-params current-params
                                     :param-specs (:parameters effect-def)
                                     :event-template (merge spatial-event-template spatial-event-keys)
                                     :fx-key (get spatial-event-keys :effect-path)}
                      
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
