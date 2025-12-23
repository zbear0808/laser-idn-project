(ns laser-show.ui.effect-dialogs
  "Dialogs for creating and editing effects in the effects grid.
   Includes:
   - Effect selection dialog (choose effect type and configure parameters)
   - Modulator configuration dialog (add modulation to parameters)"
  (:require [seesaw.core :as ss]
            [seesaw.mig :as mig]
            [seesaw.border :as border]
            [laser-show.animation.effects :as fx]
            [laser-show.animation.modulation :as mod]
            [laser-show.ui.colors :refer [background-dark background-medium
                                          background-light text-primary text-secondary
                                          get-effect-category-color get-modulator-category-color]]
            [laser-show.ui.components.slider :as slider]
            [laser-show.ui.components.corner-pin-editor :as corner-pin])
  (:import [java.awt Color Font Dimension Cursor Graphics2D RenderingHints]
           [java.awt.geom RoundRectangle2D$Float]
           [javax.swing JDialog JSpinner SpinnerNumberModel DefaultListModel JPanel
                        DefaultComboBoxModel ListSelectionModel BorderFactory]
           [javax.swing.event ChangeListener ListSelectionListener]))

;; ============================================================================
;; Constants
;; ============================================================================

(def effect-categories
  "Available effect categories for the UI."
  [{:id :shape :name "Shape"}
   {:id :color :name "Color"}
   {:id :intensity :name "Intensity"}])

(def modulator-categories
  "Hierarchical modulator categories with their types and parameters."
  [{:id :time
    :name "Time"
    :icon "🕐"
    :description "Modulators that vary over time, synced to BPM"
    :types [{:id :sine :name "Sine Wave" :description "Smooth oscillation"
             :params [:min :max :freq :phase :loop-mode :duration :time-unit]}
            {:id :triangle :name "Triangle Wave" :description "Linear up/down"
             :params [:min :max :freq :phase :loop-mode :duration :time-unit]}
            {:id :sawtooth :name "Sawtooth Wave" :description "Ramp up, reset"
             :params [:min :max :freq :phase :loop-mode :duration :time-unit]}
            {:id :square :name "Square Wave" :description "On/off toggle"
             :params [:min :max :freq :phase :loop-mode :duration :time-unit]}
            :separator
            {:id :beat-decay :name "Beat Decay" :description "Decay each beat"
             :params [:min :max]}
            {:id :random :name "Random" :description "Random values per beat"
             :params [:min :max :freq]}
            {:id :step :name "Step Sequencer" :description "Cycle through values"
             :params [:values :freq]}]}
   {:id :space
    :name "Space"
    :icon "📍"
    :description "Modulators based on point position in 2D space"
    :types [{:id :pos-x :name "X Position" :description "Left ↔ Right gradient"
             :params [:min :max]}
            {:id :pos-y :name "Y Position" :description "Bottom ↔ Top gradient"
             :params [:min :max]}
            :separator
            {:id :radial :name "Radial Distance" :description "Center → Edge gradient"
             :params [:min :max]}
            {:id :angle :name "Angle" :description "Rotation around center"
             :params [:min :max]}
            :separator
            {:id :point-index :name "Point Index" :description "First → Last point"
             :params [:min :max :wrap?]}
            {:id :point-wave :name "Point Wave" :description "Wave along path"
             :params [:min :max :cycles :wave-type]}
            :separator
            {:id :pos-wave :name "Position Wave" :description "Spatial wave pattern"
             :params [:min :max :axis :freq :wave-type]}]}
   {:id :animated
    :name "Animated"
    :icon "🌊"
    :description "Combined space + time modulators"
    :types [{:id :pos-scroll :name "Position Scroll" :description "Scrolling wave pattern"
             :params [:min :max :axis :speed :wave-type]}
            {:id :rainbow-hue :name "Rainbow Hue" :description "Rotating color wheel"
             :params [:axis :speed]}]}
   {:id :control
    :name "Control"
    :icon "🎛️"
    :description "External control and utility modulators"
    :types [{:id :midi :name "MIDI CC" :description "MIDI controller input"
             :params [:channel :cc :min :max]}
            {:id :osc :name "OSC" :description "OSC message input"
             :params [:path :min :max]}
            {:id :constant :name "Constant" :description "Fixed value"
             :params [:value]}]}])

(def modulator-presets-by-category
  "Preset modulator configurations organized by category."
  {:time [{:id :gentle-pulse :name "Gentle Pulse" :type :sine :min 0.7 :max 1.0 :freq 1.0 :phase 0.0}
          {:id :strong-pulse :name "Strong Pulse" :type :sine :min 0.3 :max 1.0 :freq 2.0 :phase 0.0}
          {:id :breathe :name "Breathe" :type :sine :min 0.5 :max 1.0 :freq 0.25 :phase 0.0}
          {:id :strobe-4x :name "Strobe 4x" :type :square :min 0.0 :max 1.0 :freq 4.0 :phase 0.0}
          {:id :strobe-8x :name "Strobe 8x" :type :square :min 0.0 :max 1.0 :freq 8.0 :phase 0.0}
          {:id :ramp-up :name "Ramp Up" :type :sawtooth :min 0.0 :max 1.0 :freq 1.0 :phase 0.0}
          {:id :wobble :name "Wobble" :type :sine :min 0.9 :max 1.1 :freq 4.0 :phase 0.0}
          {:id :beat-flash :name "Beat Flash" :type :beat-decay :min 0.3 :max 1.0}]
   :space [{:id :fade-x :name "Fade X" :type :pos-x :min 0.0 :max 1.0}
           {:id :fade-y :name "Fade Y" :type :pos-y :min 0.0 :max 1.0}
           {:id :glow-center :name "Glow Center" :type :radial :min 1.0 :max 0.3}
           {:id :fade-edges :name "Fade Edges" :type :radial :min 0.3 :max 1.0}
           {:id :fade-path :name "Fade Path" :type :point-index :min 0.0 :max 1.0}
           {:id :wave-path :name "Wave Path" :type :point-wave :min 0.3 :max 1.0 :cycles 2.0 :wave-type :sine}]
   :animated [{:id :scroll-x :name "Scroll X" :type :pos-scroll :min 0.0 :max 1.0 :axis :x :speed 1.0 :wave-type :sine}
              {:id :scroll-y :name "Scroll Y" :type :pos-scroll :min 0.0 :max 1.0 :axis :y :speed 1.0 :wave-type :sine}
              {:id :rainbow-x :name "Rainbow X" :type :rainbow-hue :axis :x :speed 60.0}
              {:id :rainbow-angle :name "Rainbow Angle" :type :rainbow-hue :axis :angle :speed 60.0}]
   :control []})

;; ============================================================================
;; UI Helpers
;; ============================================================================

(defn- create-spinner
  "Create a spinner for numeric parameter editing."
  [{:keys [min max default step on-change]}]
  (let [step (or step 0.1)
        model (SpinnerNumberModel. (double (or default min)) 
                                   (double min) 
                                   (double max) 
                                   (double step))
        spinner (JSpinner. model)]
    (.setPreferredSize spinner (Dimension. 70 25))
    (.addChangeListener spinner
      (reify ChangeListener
        (stateChanged [_ _]
          (when on-change (on-change (.getValue model))))))
    {:spinner spinner
     :get-value (fn [] (.getValue model))
     :set-value! (fn [v] (.setValue model (double v)))}))

(defn- style-dialog-panel
  "Apply consistent styling to a panel."
  [panel]
  (ss/config! panel :background (Color. 45 45 45))
  panel)

;; ============================================================================
;; Tab Panel for Category Selection  
;; ============================================================================

(defn- create-rounded-tab-button
  "Create a custom panel that acts as a rounded tab button."
  [text active? color on-click]
  (let [radius 10
        panel (proxy [JPanel] []
                (paintComponent [^Graphics2D g]
                  (let [w (.getWidth this)
                        h (.getHeight this)
                        bg-color (if active? color background-medium)]
                    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING 
                                       RenderingHints/VALUE_ANTIALIAS_ON)
                    (.setColor g bg-color)
                    ;; Draw rounded rectangle (only top corners rounded)
                    (.fill g (RoundRectangle2D$Float. 0 0 w (+ h radius) radius radius))
                    ;; Fill bottom to make it square at bottom
                    (.fillRect g 0 (- h 2) w (+ radius 2)))))]
    (.setOpaque panel false)
    (.setPreferredSize panel (Dimension. 80 32))
    (.setCursor panel (Cursor/getPredefinedCursor Cursor/HAND_CURSOR))
    (.setLayout panel (java.awt.BorderLayout.))
    (let [label (ss/label :text text
                          :foreground (if active? text-primary text-secondary)
                          :font (Font. "SansSerif" (if active? Font/BOLD Font/PLAIN) 11)
                          :halign :center)]
      (.add panel label java.awt.BorderLayout/CENTER))
    (.addMouseListener panel
      (reify java.awt.event.MouseListener
        (mouseClicked [_ _] (when on-click (on-click)))
        (mousePressed [_ _])
        (mouseReleased [_ _])
        (mouseEntered [_ _])
        (mouseExited [_ _])))
    panel))

(defn- create-tab-panel
  "Create a horizontal tab panel for category selection.
   Returns {:panel JPanel :set-active-tab! fn :get-active-tab fn}
   
   Parameters:
   - categories: Sequence of {:id :keyword :name \"string\"} maps
   - initial-category: The :id of the initially active category
   - on-category-change: (fn [category-id]) called when tab is clicked
   - get-color-fn: (optional) Function to get color for category, defaults to get-effect-category-color"
  ([categories initial-category on-category-change]
   (create-tab-panel categories initial-category on-category-change get-effect-category-color))
  ([categories initial-category on-category-change get-color-fn]
   (let [active-tab-atom (atom initial-category)
         buttons-atom (atom [])
         
         update-buttons! (fn [new-active]
                           (doseq [[btn cat] @buttons-atom]
                             (let [active? (= (:id cat) new-active)
                                   cat-color (get-color-fn (:id cat))]
                               (ss/config! btn
                                           :background (if active? cat-color background-medium)
                                           :foreground (if active? text-primary text-secondary)
                                           :font (Font. "SansSerif" (if active? Font/BOLD Font/PLAIN) 12))
                               (.setBorder btn (BorderFactory/createEmptyBorder 8 16 8 16))
                               (.setCursor btn (Cursor/getPredefinedCursor Cursor/HAND_CURSOR)))))
         
         buttons (mapv (fn [cat]
                         (let [btn (ss/button 
                                    :text (str (or (:icon cat) "") " " (:name cat))
                                    :focusable? false
                                    :listen [:action (fn [_]
                                                       (reset! active-tab-atom (:id cat))
                                                       (update-buttons! (:id cat))
                                                       (when on-category-change
                                                         (on-category-change (:id cat))))])]
                           [btn cat]))
                       categories)
         
         panel (ss/horizontal-panel
                :items (mapv first buttons)
                :background background-dark)]
     
     (reset! buttons-atom buttons)
     (update-buttons! initial-category)
     
     {:panel panel
      :set-active-tab! (fn [cat-id]
                         (reset! active-tab-atom cat-id)
                         (update-buttons! cat-id))
      :get-active-tab (fn [] @active-tab-atom)})))

;; ============================================================================
;; Modulator Tab Panel
;; ============================================================================

(defn- create-modulator-tab-panel
  "Create a tab panel specifically for modulator categories.
   Uses modulator category colors."
  [initial-category on-category-change]
  (create-tab-panel modulator-categories initial-category on-category-change get-modulator-category-color))

;; ============================================================================
;; Helper Functions for Modulator Dialog
;; ============================================================================

(defn- get-category-types
  "Get modulator types for a category (excluding separators)."
  [category-id]
  (let [cat (first (filter #(= (:id %) category-id) modulator-categories))]
    (vec (filter map? (:types cat)))))

(defn- get-modulator-type-by-id
  "Find a modulator type definition by its id."
  [type-id]
  (first (for [cat modulator-categories
               type-def (:types cat)
               :when (and (map? type-def) (= (:id type-def) type-id))]
           type-def)))

(defn- detect-modulator-category
  "Detect which category a modulator type belongs to."
  [mod-type-id]
  (or (first (for [cat modulator-categories
                   type-def (:types cat)
                   :when (and (map? type-def) (= (:id type-def) mod-type-id))]
               (:id cat)))
      :time))

(defn- modulator-needs-param?
  "Check if a modulator type needs a specific parameter."
  [mod-type-id param-key]
  (let [type-def (get-modulator-type-by-id mod-type-id)]
    (boolean (some #{param-key} (:params type-def)))))

;; ============================================================================
;; Parameter Panel Builder
;; ============================================================================

(defn- create-param-control
  "Create a control for a single parameter definition.
   Returns {:panel JPanel :get-value fn :set-value! fn :set-modulator! fn}
   
   Parameters:
   - param-def: The parameter definition map
   - on-modulate: Callback for modulator button
   - on-param-change: (optional) Callback called when parameter value changes"
  [param-def on-modulate on-param-change]
  (let [{:keys [key label type default min max choices]} param-def
        control-atom (atom nil)
        modulator-atom (atom nil)
        mod-indicator (ss/label :text "" :foreground (Color. 100 200 255))
        
        panel (case type
                :float
                (let [ctrl (slider/create-slider {:min (or min 0.0)
                                                  :max (or max 1.0)
                                                  :default default
                                                  :on-change (fn [_v] 
                                                               ;; Clear modulator when manually changed
                                                               (reset! modulator-atom nil)
                                                               (ss/config! mod-indicator :text "")
                                                               ;; Notify about parameter change for live preview
                                                               (when on-param-change (on-param-change)))})]
                  (reset! control-atom ctrl)
                  (mig/mig-panel
                   :constraints ["insets 2", "[100!][grow, fill][90!][80!][20!]", ""]
                   :items [[(ss/label :text (str label ":") 
                                     :foreground Color/WHITE) ""]
                           [(:slider ctrl) "growx"]
                           [(:textfield ctrl) ""]
                           [(ss/button :text "Modulate" 
                                       :font (Font. "SansSerif" Font/PLAIN 10)
                                       :listen [:action (fn [_]
                                                          (when on-modulate
                                                            (on-modulate key param-def
                                                              (fn [modulator]
                                                                (reset! modulator-atom modulator)
                                                                (ss/config! mod-indicator :text (if modulator "~" "")))
                                                              :existing-modulator @modulator-atom)))]) ""]
                           [mod-indicator ""]]))
                
                :int
                (let [ctrl (slider/create-slider 
                            {:min (or min 0)
                             :max (or max 255)
                             :default default
                             :integer? true
                             :on-change (fn [_v]
                                          (reset! modulator-atom nil)
                                          (ss/config! mod-indicator :text "")
                                          (when on-param-change (on-param-change)))})]
                  (reset! control-atom ctrl)
                  (mig/mig-panel
                   :constraints ["insets 2", "[100!][grow, fill][90!][80!][20!]", ""]
                   :items [[(ss/label :text (str label ":") 
                                     :foreground Color/WHITE) ""]
                           [(:slider ctrl) "growx"]
                           [(:textfield ctrl) ""]
                           [(ss/button :text "Modulate"
                                       :font (Font. "SansSerif" Font/PLAIN 10)
                                       :listen [:action (fn [_]
                                                          (when on-modulate
                                                            (on-modulate key param-def
                                                              (fn [modulator]
                                                                (reset! modulator-atom modulator)
                                                                (ss/config! mod-indicator :text (if modulator "~" "")))
                                                              :existing-modulator @modulator-atom)))]) ""]
                           [mod-indicator ""]]))
                
                :bool
                (let [checkbox (ss/checkbox :text label
                                           :selected? (boolean default)
                                           :foreground Color/WHITE
                                           :background (Color. 45 45 45)
                                           :listen [:action (fn [_] 
                                                              (when on-param-change (on-param-change)))])]
                  (reset! control-atom {:checkbox checkbox
                                        :get-value (fn [] (ss/value checkbox))
                                        :set-value! (fn [v] (ss/value! checkbox v))})
                  (mig/mig-panel
                   :constraints ["insets 2", "[grow]", ""]
                   :items [[checkbox ""]]))
                
                :choice
                (let [combo (ss/combobox :model (vec choices)
                                        :renderer (fn [_ {:keys [value]}]
                                                    {:text (str value)})
                                        :listen [:action (fn [_]
                                                           (when on-param-change (on-param-change)))])]
                  (when default
                    (ss/selection! combo default))
                  (reset! control-atom {:combo combo
                                        :get-value (fn [] (ss/selection combo))
                                        :set-value! (fn [v] (ss/selection! combo v))})
                  (mig/mig-panel
                   :constraints ["insets 2", "[100!][grow]", ""]
                   :items [[(ss/label :text (str label ":") 
                                     :foreground Color/WHITE) ""]
                           [combo ""]]))
                
                ;; Default: treat as float
                (let [ctrl (slider/create-slider {:min 0.0 :max 1.0 :default (or default 0.5)
                                                  :on-change (fn [_v]
                                                               (when on-param-change (on-param-change)))})]
                  (reset! control-atom ctrl)
                  (mig/mig-panel
                   :constraints ["insets 2", "[100!][grow, fill][50!]", ""]
                   :items [[(ss/label :text (str label ":") 
                                     :foreground Color/WHITE) ""]
                           [(:slider ctrl) "growx"]
                           [(:textfield ctrl) ""]])))]
    
    (style-dialog-panel panel)
    {:panel panel
     :param-key key
     :get-value (fn [] 
                  (if-let [m @modulator-atom]
                    m
                    (when-let [ctrl @control-atom]
                      ((:get-value ctrl)))))
     :set-value! (fn [v]
                   (when-let [ctrl @control-atom]
                     (when (and (not (fn? v)) (:set-value! ctrl))
                       ((:set-value! ctrl) v))))
     :set-modulator! (fn [m]
                       (reset! modulator-atom m)
                       (ss/config! mod-indicator :text (if m "~" "")))}))

(defn- create-params-panel
  "Create a panel with controls for all effect parameters.
   Returns {:panel JPanel :get-params fn :set-params! fn}
   
   Parameters:
   - effect-def: The effect definition
   - on-modulate: Callback for modulator buttons
   - on-param-change: (optional) Callback called when any parameter changes"
  [effect-def on-modulate & [on-param-change]]
  ;; Special case: Corner pin effect uses visual editor instead of sliders
  (if (= (:id effect-def) :corner-pin)
    (let [editor (corner-pin/create-corner-pin-editor
                  :on-change (fn [_corners]
                               (when on-param-change (on-param-change))))]
      
      {:panel (style-dialog-panel (:panel editor))
       :get-params (fn []
                     ;; Convert corners map to flat parameter structure
                     (let [corners ((:get-corners editor))]
                       {:tl-x (get-in corners [:tl :x])
                        :tl-y (get-in corners [:tl :y])
                        :tr-x (get-in corners [:tr :x])
                        :tr-y (get-in corners [:tr :y])
                        :bl-x (get-in corners [:bl :x])
                        :bl-y (get-in corners [:bl :y])
                        :br-x (get-in corners [:br :x])
                        :br-y (get-in corners [:br :y])}))
       :set-params! (fn [params]
                      ;; Convert flat params to corners map
                      (when (and (:tl-x params) (:tl-y params)
                                 (:tr-x params) (:tr-y params)
                                 (:bl-x params) (:bl-y params)
                                 (:br-x params) (:br-y params))
                        ((:set-corners! editor)
                         {:tl {:x (:tl-x params) :y (:tl-y params)}
                          :tr {:x (:tr-x params) :y (:tr-y params)}
                          :bl {:x (:bl-x params) :y (:bl-y params)}
                          :br {:x (:br-x params) :y (:br-y params)}})))})
    
    ;; Default case: Use slider-based controls for all other effects
    (let [param-defs (:parameters effect-def)
          controls (mapv #(create-param-control % on-modulate on-param-change) param-defs)
          panel (mig/mig-panel
                 :constraints ["insets 5, wrap 1", "[grow, fill]", ""]
                 :items (mapv (fn [ctrl] [(:panel ctrl) "growx"]) controls))]
      (style-dialog-panel panel)
      {:panel panel
       :get-params (fn []
                     (into {}
                       (map (fn [ctrl]
                              [(:param-key ctrl) ((:get-value ctrl))])
                            controls)))
       :set-params! (fn [params]
                      (doseq [ctrl controls]
                        (when-let [v (get params (:param-key ctrl))]
                          (if (mod/modulator-config? v)
                            ;; Modulator config map (pure data)
                            ((:set-modulator! ctrl) v)
                            ;; Static value
                            ((:set-value! ctrl) v)))))})))

;; ============================================================================
;; Modulator Dialog
;; ============================================================================

(defn- create-modulator-config
  "Create a modulator config map (pure data) based on type and parameters.
   This returns a data structure that can be serialized to EDN.
   The modulator function will be created at runtime by resolve-param.
   
   Note: Trigger time is NOT stored in the config.
   It comes from the modulation context at runtime (see effects.clj apply-effect)."
  [mod-type params]
  (let [{:keys [min-val max-val freq phase axis speed wave-type cycles
                channel cc path value wrap? loop-mode duration time-unit]} params
        loop-mode (or loop-mode :loop)
        duration (or duration 2.0)
        time-unit (or time-unit :beats)]
    ;; Return pure data config - the modulator function will be created at runtime
    (case mod-type
      ;; Time-based modulators
      :sine {:type :sine :min min-val :max max-val :freq (or freq 1.0) :phase (or phase 0.0)
             :loop-mode loop-mode :duration duration :time-unit time-unit}
      :triangle {:type :triangle :min min-val :max max-val :freq (or freq 1.0) :phase (or phase 0.0)
                 :loop-mode loop-mode :duration duration :time-unit time-unit}
      :sawtooth {:type :sawtooth :min min-val :max max-val :freq (or freq 1.0) :phase (or phase 0.0)}
      :square {:type :square :min min-val :max max-val :freq (or freq 1.0) :phase (or phase 0.0)}
      :beat-decay {:type :beat-decay :min min-val :max max-val}
      :random {:type :random :min min-val :max max-val :freq (or freq 1.0)}
      
      ;; Space-based modulators
      :pos-x {:type :pos-x :min min-val :max max-val}
      :pos-y {:type :pos-y :min min-val :max max-val}
      :radial {:type :radial :min min-val :max max-val}
      :angle {:type :angle :min min-val :max max-val}
      :point-index {:type :point-index :min min-val :max max-val :wrap? (boolean wrap?)}
      :point-wave {:type :point-wave :min min-val :max max-val :cycles (or cycles 1.0) :wave-type (or wave-type :sine)}
      :pos-wave {:type :pos-wave :min min-val :max max-val :axis (or axis :x) :freq (or freq 1.0) :wave-type (or wave-type :sine)}
      
      ;; Animated modulators
      :pos-scroll {:type :pos-scroll :min min-val :max max-val :axis (or axis :x) :speed (or speed 1.0) :wave-type (or wave-type :sine)}
      :rainbow-hue {:type :rainbow-hue :axis (or axis :x) :speed (or speed 60.0)}
      
      ;; Control modulators
      :midi {:type :midi :channel (or channel 1) :cc (or cc 1) :min min-val :max max-val}
      :osc {:type :osc :path (or path "/control") :min min-val :max max-val}
      :constant {:type :constant :value (or value min-val)}
      
      ;; Default to sine
      {:type :sine :min (or min-val 0.0) :max (or max-val 1.0) :freq 1.0 :phase 0.0})))

(defn show-modulator-dialog!
  "Show dialog to configure a modulator for a parameter.
   
   Parameters:
   - parent: Parent component for dialog
   - param-key: The parameter key being modulated
   - param-def: The parameter definition
   - on-confirm: (fn [modulator]) called when user confirms
   - existing-modulator: (optional) Existing modulator to edit - will initialize UI with its config
   - on-modulator-change: (optional fn [modulator]) called when modulator settings change
                          Used for live preview - the modulator is immediately applied
   
   Returns: The modulator function or nil if cancelled"
  [parent param-key param-def on-confirm & {:keys [existing-modulator on-modulator-change]}]
  (let [result-atom (atom nil)
        dialog (JDialog. (ss/to-root parent) "Configure Modulator" true)
        
        ;; Extract existing modulator config (pure data only)
        existing-config (when (and existing-modulator (mod/modulator-config? existing-modulator))
                          existing-modulator)
        
        ;; Determine initial category from existing config
        init-category (if-let [t (:type existing-config)]
                        (detect-modulator-category t)
                        :time)
        
        ;; Parameter defaults based on param-def
        param-min (or (:min param-def) 0.0)
        param-max (or (:max param-def) 1.0)
        
        ;; State atoms
        active-category-atom (atom init-category)
        selected-type-atom (atom (or (:type existing-config) :sine))
        
        ;; Initial values from existing config or defaults
        init-min (or (:min existing-config) param-min)
        init-max (or (:max existing-config) param-max)
        init-freq (or (:freq existing-config) 1.0)
        init-phase (or (:phase existing-config) 0.0)
        init-axis (or (:axis existing-config) :x)
        init-speed (or (:speed existing-config) 1.0)
        init-cycles (or (:cycles existing-config) 1.0)
        init-loop-mode (or (:loop-mode existing-config) :loop)
        init-duration (or (:duration existing-config) 2.0)
        init-time-unit (or (:time-unit existing-config) :beats)
        
        ;; State atom for loop-mode (affects visibility of duration/time-unit)
        loop-mode-atom (atom init-loop-mode)
        
        ;; Atom to hold notification function
        notify-fn-atom (atom nil)
        
        ;; Parameter controls - create all of them, show/hide based on type
        min-ctrl (slider/create-slider {:min param-min :max param-max :default init-min
                                        :label-fn #(format "%.2f" %)
                                        :on-change (fn [_] (when-let [f @notify-fn-atom] (f)))})
        max-ctrl (slider/create-slider {:min param-min :max param-max :default init-max
                                        :label-fn #(format "%.2f" %)
                                        :on-change (fn [_] (when-let [f @notify-fn-atom] (f)))})
        freq-ctrl (slider/create-slider {:min 0.1 :max 8.0 :default init-freq
                                         :label-fn #(format "%.1fx" %)
                                         :on-change (fn [_] (when-let [f @notify-fn-atom] (f)))})
        phase-ctrl (slider/create-slider {:min 0.0 :max 1.0 :default init-phase
                                          :label-fn #(format "%.2f" %)
                                          :on-change (fn [_] (when-let [f @notify-fn-atom] (f)))})
        speed-ctrl (slider/create-slider {:min 0.1 :max 8.0 :default init-speed
                                          :label-fn #(format "%.1fx" %)
                                          :on-change (fn [_] (when-let [f @notify-fn-atom] (f)))})
        cycles-ctrl (slider/create-slider {:min 0.5 :max 8.0 :default init-cycles
                                           :label-fn #(format "%.1f" %)
                                           :on-change (fn [_] (when-let [f @notify-fn-atom] (f)))})
        
        axis-combo (ss/combobox :model [:x :y :radial :angle]
                               :listen [:action (fn [_] (when-let [f @notify-fn-atom] (f)))])
        _ (ss/selection! axis-combo init-axis)
        
        wave-type-combo (ss/combobox :model [:sine :triangle :sawtooth :square]
                                    :listen [:action (fn [_] (when-let [f @notify-fn-atom] (f)))])
        _ (ss/selection! wave-type-combo (or (:wave-type existing-config) :sine))
        
        wrap-checkbox (ss/checkbox :text "Wrap around" 
                                   :selected? (boolean (:wrap? existing-config))
                                   :foreground Color/WHITE
                                   :background (Color. 45 45 45)
                                   :listen [:action (fn [_] (when-let [f @notify-fn-atom] (f)))])
        
        ;; Loop mode controls
        duration-ctrl (slider/create-slider {:min 0.25 :max 16.0 :default init-duration
                                             :label-fn #(format "%.2f" %)
                                             :on-change (fn [_] (when-let [f @notify-fn-atom] (f)))})
        
        ;; Trigger time atom for "once" preview
        trigger-time-atom (atom (System/currentTimeMillis))
        
        ;; Loop mode toggle button (instead of dropdown)
        update-params-visibility-ref (atom nil) ;; Forward reference for update function
        loop-toggle-btn (ss/button :text (if (= init-loop-mode :once) "Once" "Loop")
                                   :font (Font. "SansSerif" Font/BOLD 11))
        _ (ss/listen loop-toggle-btn :action 
                     (fn [_]
                       (let [new-mode (if (= @loop-mode-atom :loop) :once :loop)]
                         (reset! loop-mode-atom new-mode)
                         (ss/config! loop-toggle-btn :text (if (= new-mode :once) "Once" "Loop"))
                         ;; Reset trigger time when switching to once mode
                         (when (= new-mode :once)
                           (reset! trigger-time-atom (System/currentTimeMillis)))
                         ;; Rebuild visibility when loop mode changes
                         (when-let [update-fn @update-params-visibility-ref]
                           (update-fn @selected-type-atom))
                         (when-let [f @notify-fn-atom] (f)))))
        
        ;; Retrigger button - resets animation to phase 0
        ;; Available for BOTH loop and once modes for consistent retriggering
        ;; NOTE: This button ONLY resets the trigger-time atom.
        ;; It does NOT recreate the modulator. The existing modulator
        ;; already holds a reference to the atom and will read the new
        ;; time on the next render frame.
        trigger-btn (ss/button :text "↻ Retrigger"
                               :font (Font. "SansSerif" Font/BOLD 11)
                               :background (Color. 80 140 80)
                               :foreground Color/WHITE)
        _ (ss/listen trigger-btn :action 
                     (fn [_]
                       (let [new-time (System/currentTimeMillis)]
                         (println "[DEBUG] Trigger clicked! Resetting trigger-time atom to:" new-time)
                         (reset! trigger-time-atom new-time))))
        
        time-unit-combo (ss/combobox :model [:beats :seconds])
        _ (ss/listen time-unit-combo :action (fn [_] (when-let [f @notify-fn-atom] (f))))
        _ (ss/selection! time-unit-combo init-time-unit)
        
        ;; Loop mode toggle panel with trigger button
        loop-mode-toggle-panel (mig/mig-panel
                                :constraints ["insets 5", "[80!][100!][100!]", ""]
                                :items [[(ss/label :text "Loop Mode:" :foreground Color/WHITE) ""]
                                        [loop-toggle-btn "growx"]
                                        [trigger-btn "growx"]]
                                :background (Color. 45 45 45))
        
        ;; Parameter panels (will show/hide based on selected type)
        min-panel (mig/mig-panel
                   :constraints ["insets 5", "[80!][grow, fill][90!]", ""]
                   :items [[(ss/label :text "Min Value:" :foreground Color/WHITE) ""]
                           [(:slider min-ctrl) "growx"]
                           [(:textfield min-ctrl) ""]]
                   :background (Color. 45 45 45))
        
        max-panel (mig/mig-panel
                   :constraints ["insets 5", "[80!][grow, fill][90!]", ""]
                   :items [[(ss/label :text "Max Value:" :foreground Color/WHITE) ""]
                           [(:slider max-ctrl) "growx"]
                           [(:textfield max-ctrl) ""]]
                   :background (Color. 45 45 45))
        
        freq-panel (mig/mig-panel
                    :constraints ["insets 5", "[80!][grow, fill][90!]", ""]
                    :items [[(ss/label :text "Frequency:" :foreground Color/WHITE) ""]
                            [(:slider freq-ctrl) "growx"]
                            [(:textfield freq-ctrl) ""]]
                    :background (Color. 45 45 45))
        
        phase-panel (mig/mig-panel
                     :constraints ["insets 5", "[80!][grow, fill][90!]", ""]
                     :items [[(ss/label :text "Phase:" :foreground Color/WHITE) ""]
                             [(:slider phase-ctrl) "growx"]
                             [(:textfield phase-ctrl) ""]]
                     :background (Color. 45 45 45))
        
        speed-panel (mig/mig-panel
                     :constraints ["insets 5", "[80!][grow, fill][90!]", ""]
                     :items [[(ss/label :text "Speed:" :foreground Color/WHITE) ""]
                             [(:slider speed-ctrl) "growx"]
                             [(:textfield speed-ctrl) ""]]
                     :background (Color. 45 45 45))
        
        cycles-panel (mig/mig-panel
                      :constraints ["insets 5", "[80!][grow, fill][90!]", ""]
                      :items [[(ss/label :text "Cycles:" :foreground Color/WHITE) ""]
                              [(:slider cycles-ctrl) "growx"]
                              [(:textfield cycles-ctrl) ""]]
                      :background (Color. 45 45 45))
        
        axis-panel (mig/mig-panel
                    :constraints ["insets 5", "[80!][grow]", ""]
                    :items [[(ss/label :text "Axis:" :foreground Color/WHITE) ""]
                            [axis-combo "growx"]]
                    :background (Color. 45 45 45))
        
        wave-type-panel (mig/mig-panel
                         :constraints ["insets 5", "[80!][grow]", ""]
                         :items [[(ss/label :text "Wave Type:" :foreground Color/WHITE) ""]
                                 [wave-type-combo "growx"]]
                         :background (Color. 45 45 45))
        
        wrap-panel (mig/mig-panel
                    :constraints ["insets 5", "[grow]", ""]
                    :items [[wrap-checkbox ""]]
                    :background (Color. 45 45 45))
        
        
        duration-panel (mig/mig-panel
                        :constraints ["insets 5", "[80!][grow, fill][90!]", ""]
                        :items [[(ss/label :text "Duration:" :foreground Color/WHITE) ""]
                                [(:slider duration-ctrl) "growx"]
                                [(:textfield duration-ctrl) ""]]
                        :background (Color. 45 45 45))
        
        time-unit-panel (mig/mig-panel
                         :constraints ["insets 5", "[80!][grow]", ""]
                         :items [[(ss/label :text "Time Unit:" :foreground Color/WHITE) ""]
                                 [time-unit-combo "growx"]]
                         :background (Color. 45 45 45))
        
        ;; Container for dynamic parameters
        params-container (ss/border-panel :background (Color. 45 45 45))
        
        ;; Function to update parameter visibility based on selected type and loop-mode
        update-params-visibility! (fn [mod-type-id]
                                    (.removeAll params-container)
                                    (let [type-def (get-modulator-type-by-id mod-type-id)
                                          params-needed (set (or (:params type-def) [:min :max :freq :phase]))
                                          show-once-params? (= @loop-mode-atom :once)
                                          ;; Retrigger button is ALWAYS visible for both loop and once modes
                                          _ (ss/config! trigger-btn :visible? true)
                                          panel-items (cond-> []
                                                        (params-needed :min) (conj [min-panel "growx, wrap"])
                                                        (params-needed :max) (conj [max-panel "growx, wrap"])
                                                        (params-needed :freq) (conj [freq-panel "growx, wrap"])
                                                        (params-needed :phase) (conj [phase-panel "growx, wrap"])
                                                        (params-needed :speed) (conj [speed-panel "growx, wrap"])
                                                        (params-needed :cycles) (conj [cycles-panel "growx, wrap"])
                                                        (params-needed :axis) (conj [axis-panel "growx, wrap"])
                                                        (params-needed :wave-type) (conj [wave-type-panel "growx, wrap"])
                                                        (params-needed :wrap?) (conj [wrap-panel "growx, wrap"])
                                                        (params-needed :loop-mode) (conj [loop-mode-toggle-panel "growx, wrap"])
                                                        (and (params-needed :duration) show-once-params?) (conj [duration-panel "growx, wrap"])
                                                        (and (params-needed :time-unit) show-once-params?) (conj [time-unit-panel "growx, wrap"]))
                                          inner-panel (mig/mig-panel
                                                       :constraints ["insets 0, wrap 1", "[grow, fill]", ""]
                                                       :items panel-items
                                                       :background (Color. 45 45 45))]
                                      (.add params-container inner-panel java.awt.BorderLayout/CENTER))
                                    (.revalidate params-container)
                                    (.repaint params-container))
        
        ;; Set the forward reference so loop-mode listener can call it
        _ (reset! update-params-visibility-ref update-params-visibility!)
        
        ;; Modulator type list
        type-list-model (DefaultListModel.)
        type-list (ss/listbox :model type-list-model
                             :renderer (fn [renderer {:keys [value selected?]}]
                                         (if value
                                           (let [bg-color (if selected?
                                                           (get-modulator-category-color @active-category-atom)
                                                           (Color. 50 50 50))]
                                             (ss/config! renderer 
                                                        :text (str (:name value) " - " (:description value))
                                                        :foreground Color/WHITE
                                                        :background bg-color))
                                           (ss/config! renderer :text ""))))
        _ (.setSelectionMode type-list ListSelectionModel/SINGLE_SELECTION)
        _ (.setBackground type-list (Color. 50 50 50))
        _ (.setForeground type-list Color/WHITE)
        
        ;; Function to update type list based on category
        update-type-list! (fn [category-id]
                            (.clear type-list-model)
                            (doseq [type-def (get-category-types category-id)]
                              (.addElement type-list-model type-def))
                            ;; Select first item
                            (when (pos? (.getSize type-list-model))
                              (.setSelectedIndex type-list 0)))
        
        ;; Type selection listener
        _ (.addListSelectionListener type-list
            (reify ListSelectionListener
              (valueChanged [_ e]
                (when-not (.getValueIsAdjusting e)
                  (when-let [type-def (.getSelectedValue type-list)]
                    (reset! selected-type-atom (:id type-def))
                    (update-params-visibility! (:id type-def))
                    (when-let [f @notify-fn-atom] (f)))))))
        
        ;; Presets panel (filtered by category)
        presets-container (ss/border-panel :background (Color. 45 45 45))
        
        update-presets-panel! (fn [category-id]
                                (.removeAll presets-container)
                                (let [category-presets (get modulator-presets-by-category category-id [])
                                      preset-buttons (mapv (fn [preset]
                                                            (ss/button 
                                                             :text (:name preset)
                                                             :font (Font. "SansSerif" Font/PLAIN 10)
                                                             :listen [:action 
                                                                      (fn [_]
                                                                        ;; Find and select the type in the list
                                                                        (let [list-size (.getSize type-list-model)]
                                                                          (doseq [i (range list-size)]
                                                                            (let [item (.getElementAt type-list-model i)]
                                                                              (when (= (:id item) (:type preset))
                                                                                (.setSelectedIndex type-list i)))))
                                                                        ;; Apply preset values
                                                                        (when (:min preset) ((:set-value! min-ctrl) (:min preset)))
                                                                        (when (:max preset) ((:set-value! max-ctrl) (:max preset)))
                                                                        (when (:freq preset) ((:set-value! freq-ctrl) (:freq preset)))
                                                                        (when (:phase preset) ((:set-value! phase-ctrl) (:phase preset)))
                                                                        (when (:speed preset) ((:set-value! speed-ctrl) (:speed preset)))
                                                                        (when (:cycles preset) ((:set-value! cycles-ctrl) (:cycles preset)))
                                                                        (when (:axis preset) (ss/selection! axis-combo (:axis preset)))
                                                                        (when (:wave-type preset) (ss/selection! wave-type-combo (:wave-type preset))))]))
                                                          category-presets)]
                                  (if (seq preset-buttons)
                                    (let [inner-panel (ss/horizontal-panel
                                                       :items preset-buttons
                                                       :background (Color. 45 45 45))]
                                      (.add presets-container (ss/scrollable inner-panel :hscroll :as-needed :vscroll :never)
                                            java.awt.BorderLayout/CENTER))
                                    (.add presets-container 
                                          (ss/label :text "No presets for this category" 
                                                    :foreground (Color. 120 120 120)
                                                    :halign :center)
                                          java.awt.BorderLayout/CENTER)))
                                (.revalidate presets-container)
                                (.repaint presets-container))
        
        ;; Category change handler
        on-category-change (fn [category-id]
                             (reset! active-category-atom category-id)
                             (update-type-list! category-id)
                             (update-presets-panel! category-id))
        
        ;; Create category tabs
        tab-panel (create-modulator-tab-panel init-category on-category-change)
        
        ;; Create modulator config (pure data) based on current settings
        ;; Note: Trigger time is NOT stored in the config - it comes from global playback state
        ;; at runtime via the modulation context (see effects.clj apply-effect).
        create-modulator (fn []
                           (let [mod-type @selected-type-atom
                                 loop-mode @loop-mode-atom
                                 params {:min-val ((:get-value min-ctrl))
                                         :max-val ((:get-value max-ctrl))
                                         :freq ((:get-value freq-ctrl))
                                         :phase ((:get-value phase-ctrl))
                                         :speed ((:get-value speed-ctrl))
                                         :cycles ((:get-value cycles-ctrl))
                                         :axis (ss/selection axis-combo)
                                         :wave-type (ss/selection wave-type-combo)
                                         :wrap? (ss/value wrap-checkbox)
                                         :loop-mode loop-mode
                                         :duration ((:get-value duration-ctrl))
                                         :time-unit (ss/selection time-unit-combo)}]
                             ;; Return pure data config - serializable to EDN
                             (create-modulator-config mod-type params)))
        
        ;; Notify about modulator change for live preview
        notify-modulator-change! (fn []
                                   (when on-modulator-change
                                     (try
                                       (let [m (create-modulator)]
                                         (println "[DEBUG] Modulator created, calling on-modulator-change")
                                         (on-modulator-change m))
                                       (catch Exception e 
                                         (println "[DEBUG] Exception creating modulator:" (.getMessage e))))))
        
        ;; Set the notify function atom
        _ (reset! notify-fn-atom notify-modulator-change!)
        
        ;; Buttons
        ok-btn (ss/button :text "Apply Modulator"
                         :listen [:action (fn [_]
                                           (try
                                             (let [m (create-modulator)]
                                               (reset! result-atom m)
                                               (when on-confirm (on-confirm m))
                                               (.dispose dialog))
                                             (catch Exception e
                                               (ss/alert dialog (str "Error creating modulator: " (.getMessage e))))))])
        cancel-btn (ss/button :text "Cancel"
                             :listen [:action (fn [_] (.dispose dialog))])
        remove-btn (ss/button :text "Remove Modulator"
                             :listen [:action (fn [_]
                                               (reset! result-atom nil)
                                               (when on-confirm (on-confirm nil))
                                               (.dispose dialog))])
        
        ;; Main panel
        content (mig/mig-panel
                 :constraints ["insets 10, wrap 1", "[grow, fill]", ""]
                 :items [[(ss/label :text (str "Modulate: " (or (:label param-def) (name param-key)))
                                   :font (Font. "SansSerif" Font/BOLD 14)
                                   :foreground Color/WHITE) ""]
                         
                         ;; Category tabs
                         [(:panel tab-panel) "growx"]
                         
                         ;; Category description
                         [(let [cat (first (filter #(= (:id %) @active-category-atom) modulator-categories))]
                            (ss/label :text (or (:description cat) "")
                                     :foreground (Color. 150 150 150)
                                     :font (Font. "SansSerif" Font/ITALIC 10))) "growx"]
                         
                         ;; Type selection list
                         [(ss/border-panel
                           :north (ss/label :text "Modulator Type"
                                           :font (Font. "SansSerif" Font/BOLD 11)
                                           :foreground (Color. 180 180 180))
                           :center (ss/scrollable type-list 
                                                 :border (border/line-border :color (Color. 60 60 60)))
                           :background (Color. 45 45 45)) "grow, h 120!"]
                         
                         ;; Parameters
                         [(ss/label :text "Parameters" 
                                   :font (Font. "SansSerif" Font/BOLD 11)
                                   :foreground (Color. 180 180 180)) ""]
                         
                         [(ss/scrollable params-container
                                        :border (border/line-border :color (Color. 60 60 60))) "grow, h 150!"]
                         
                         ;; Presets
                         [(ss/label :text "Quick Presets" 
                                   :font (Font. "SansSerif" Font/BOLD 11)
                                   :foreground (Color. 180 180 180)) ""]
                         [presets-container "growx, h 40!"]
                         
                         ;; Buttons
                         [(mig/mig-panel
                           :constraints ["insets 10", "[grow][][]", ""]
                           :items [[remove-btn ""]
                                   [cancel-btn ""]
                                   [ok-btn ""]]
                           :background (Color. 45 45 45)) "growx, dock south"]])]
    
    (style-dialog-panel content)
    
    ;; Initialize the UI
    (update-type-list! init-category)
    (update-presets-panel! init-category)
    
    ;; If editing existing modulator, select the right type
    (when-let [t (:type existing-config)]
      (let [list-size (.getSize type-list-model)]
        (doseq [i (range list-size)]
          (let [item (.getElementAt type-list-model i)]
            (when (= (:id item) t)
              (.setSelectedIndex type-list i))))))
    
    (.setContentPane dialog content)
    (.setSize dialog 550 600)
    (.setLocationRelativeTo dialog parent)
    (.setVisible dialog true)
    
    @result-atom))

;; ============================================================================
;; Effect Selection Dialog
;; ============================================================================

(defn show-effect-dialog!
  "Show dialog to create or edit an effect.
   
   Parameters:
   - parent: Parent component for dialog
   - existing-effect: Existing effect data to edit, or nil for new effect
   - on-confirm: (fn [effect-data]) called when user confirms
   - on-effect-change: (optional fn [effect-data]) called when effect selection or params change
                       Used for live preview - the effect is immediately applied to the chain
   - on-cancel: (optional fn []) called when dialog is cancelled
   
   Returns: Effect data map or nil if cancelled"
  [parent existing-effect on-confirm & {:keys [on-effect-change on-cancel]}]
  (let [result-atom (atom nil)
        dialog (JDialog. (ss/to-root parent) 
                        (if existing-effect "Edit Effect" "New Effect") 
                        true)
        
        ;; State
        selected-effect-atom (atom (when existing-effect
                                    (fx/get-effect (:effect-id existing-effect))))
        params-panel-ref (atom nil)
        tab-panel-ref (atom nil)
        
        ;; Effect list with custom renderer that shows selection highlighting
        effect-list-model (DefaultListModel.)
        effect-list (ss/listbox :model effect-list-model
                               :renderer (fn [renderer {:keys [value selected?]}]
                                          (if value
                                            (let [bg-color (if selected?
                                                            background-light
                                                            (Color. 50 50 50))]
                                              (ss/config! renderer 
                                                         :text (:name value)
                                                         :foreground Color/WHITE
                                                         :background bg-color))
                                            (ss/config! renderer :text ""))))
        _ (.setSelectionMode effect-list ListSelectionModel/SINGLE_SELECTION)
        _ (.setBackground effect-list (Color. 50 50 50))
        _ (.setForeground effect-list Color/WHITE)
        _ (.setSelectionBackground effect-list background-light)
        _ (.setSelectionForeground effect-list Color/WHITE)
        
        ;; Parameters container
        params-container (ss/border-panel :background (Color. 45 45 45))
        
        ;; Function to update effect list based on category
        update-effect-list! (fn [category-id]
                              (.clear effect-list-model)
                              (doseq [effect (fx/list-effects-by-category category-id)]
                                (.addElement effect-list-model effect)))
        
        ;; Empty placeholder for when no effect is selected
        empty-placeholder (ss/label :text "Select an effect to configure parameters"
                                    :foreground (Color. 120 120 120)
                                    :halign :center)
        
        ;; Function to build and notify current effect data (defined first so on-modulate can use it)
        notify-effect-change! (fn []
                               (when on-effect-change
                                 (when-let [effect-def @selected-effect-atom]
                                   (let [params (if-let [pp @params-panel-ref]
                                                  ((:get-params pp))
                                                  {})
                                         effect-data {:effect-id (:id effect-def)
                                                     :enabled true
                                                     :params params}]
                                     (on-effect-change effect-data)))))
        
        ;; Function to create modulator via dialog
        ;; This callback is passed to create-params-panel and receives the existing modulator
        on-modulate (fn [param-key param-def on-apply & {:keys [existing-modulator]}]
                      (show-modulator-dialog! dialog param-key param-def on-apply
                        :existing-modulator existing-modulator
                        :on-modulator-change (fn [m]
                                               ;; Apply the modulator and notify for live preview
                                               (on-apply m)
                                               (notify-effect-change!))))
        
        ;; Container that holds both effect list panel and params panel
        ;; This allows us to reorganize the layout for corner-pin
        main-content-container (ss/border-panel :background (Color. 45 45 45))
        
        ;; Effect list panel (extracted so we can reuse it)
        effect-list-panel (ss/border-panel
                           :north (ss/label :text "Effects"
                                           :font (Font. "SansSerif" Font/BOLD 11)
                                           :foreground (Color. 180 180 180))
                           :center (ss/scrollable effect-list
                                                 :border (border/line-border :color (Color. 60 60 60)))
                           :background (Color. 45 45 45))
        
        ;; Function to update params panel and potentially reorganize layout
        update-params-panel! (fn [effect-def]
                              ;; Remove all existing content
                              (.removeAll params-container)
                              (.removeAll main-content-container)
                              
                              (if effect-def
                                ;; Pass notify-effect-change! so param changes trigger live preview
                                (let [pp (create-params-panel effect-def on-modulate notify-effect-change!)
                                      is-corner-pin? (= (:id effect-def) :corner-pin)]
                                  (reset! params-panel-ref pp)
                                  (.add params-container (:panel pp) java.awt.BorderLayout/CENTER)
                                  
                                  ;; For corner-pin, use horizontal layout
                                  (if is-corner-pin?
                                    (let [left-panel (ss/border-panel
                                                      :center effect-list-panel
                                                      :background (Color. 45 45 45))
                                          right-panel (ss/border-panel
                                                       :north (ss/label :text "Corner Pin Editor"
                                                                       :font (Font. "SansSerif" Font/BOLD 11)
                                                                       :foreground (Color. 180 180 180))
                                                       :center params-container
                                                       :background (Color. 45 45 45))]
                                      (.add main-content-container left-panel java.awt.BorderLayout/WEST)
                                      (.add main-content-container right-panel java.awt.BorderLayout/CENTER))
                                    ;; Default vertical layout
                                    (let [vertical-panel (mig/mig-panel
                                                          :constraints ["insets 0, wrap 1", "[grow, fill]", "[grow][grow]"]
                                                          :items [[effect-list-panel "grow, h 150!"]
                                                                  [(ss/border-panel
                                                                    :north (ss/label :text "Parameters"
                                                                                    :font (Font. "SansSerif" Font/BOLD 11)
                                                                                    :foreground (Color. 180 180 180))
                                                                    :center (ss/scrollable params-container
                                                                                          :border (border/line-border :color (Color. 60 60 60)))
                                                                    :background (Color. 45 45 45)) "grow"]]
                                                          :background (Color. 45 45 45))]
                                      (.add main-content-container vertical-panel java.awt.BorderLayout/CENTER)))
                                  
                                  ;; If editing, set existing params
                                  (when existing-effect
                                    ((:set-params! pp) (:params existing-effect))))
                                ;; No effect selected - show placeholder
                                (do
                                  (reset! params-panel-ref nil)
                                  (.add params-container empty-placeholder java.awt.BorderLayout/CENTER)
                                  (let [vertical-panel (mig/mig-panel
                                                        :constraints ["insets 0, wrap 1", "[grow, fill]", "[grow][grow]"]
                                                        :items [[effect-list-panel "grow, h 150!"]
                                                                [(ss/border-panel
                                                                  :north (ss/label :text "Parameters"
                                                                                  :font (Font. "SansSerif" Font/BOLD 11)
                                                                                  :foreground (Color. 180 180 180))
                                                                  :center params-container
                                                                  :background (Color. 45 45 45)) "grow"]]
                                                        :background (Color. 45 45 45))]
                                    (.add main-content-container vertical-panel java.awt.BorderLayout/CENTER))))
                              
                              (.revalidate main-content-container)
                              (.repaint main-content-container)
                              ;; Notify about the effect change (new effect selected with default params)
                              (notify-effect-change!))
        
        ;; Category tab panel (replaces dropdown)
        on-category-change (fn [category-id]
                             (update-effect-list! category-id)
                             (reset! selected-effect-atom nil)
                             (update-params-panel! nil))
        
        tab-panel (create-tab-panel effect-categories :shape on-category-change)
        _ (reset! tab-panel-ref tab-panel)
        
        ;; Effect selection listener
        _ (.addListSelectionListener effect-list
            (reify ListSelectionListener
              (valueChanged [_ e]
                (when-not (.getValueIsAdjusting e)
                  (when-let [effect-def (.getSelectedValue effect-list)]
                    (reset! selected-effect-atom effect-def)
                    (update-params-panel! effect-def))))))
        
        ;; Buttons
        ok-btn (ss/button :text (if existing-effect "Update Effect" "Create Effect")
                         :listen [:action (fn [_]
                                           (when-let [effect-def @selected-effect-atom]
                                             (let [params (if-let [pp @params-panel-ref]
                                                           ((:get-params pp))
                                                           {})
                                                   effect-data {:effect-id (:id effect-def)
                                                               :enabled (if existing-effect
                                                                         (:enabled existing-effect true)
                                                                         true)
                                                               :params params}]
                                               (reset! result-atom effect-data)
                                               (when on-confirm (on-confirm effect-data))
                                               (.dispose dialog))))])
        cancel-btn (ss/button :text "Cancel"
                             :listen [:action (fn [_] 
                                               (when on-cancel (on-cancel))
                                               (.dispose dialog))])
        
        ;; Main content - uses dynamic main-content-container
        content (mig/mig-panel
                 :constraints ["insets 10, wrap 1", "[grow, fill]", "[][][][grow][]"]
                 :items [;; Title
                         [(ss/label :text (if existing-effect "Edit Effect" "New Effect")
                                   :font (Font. "SansSerif" Font/BOLD 16)
                                   :foreground Color/WHITE) ""]
                         
                         ;; Category selection (tab panel)
                         [(:panel tab-panel) "growx"]
                         
                         ;; Main content area (dynamically reorganizes for corner-pin vs other effects)
                         [main-content-container "grow"]
                         
                         ;; Buttons
                         [(mig/mig-panel
                           :constraints ["insets 10", "[grow][][]", ""]
                           :items [[(ss/label) "growx"]
                                   [cancel-btn ""]
                                   [ok-btn ""]]
                           :background (Color. 45 45 45)) "growx"]])]
    
    (style-dialog-panel content)
    
    ;; Initialize with first category and set up initial layout
    (let [first-cat (:id (first effect-categories))]
      (update-effect-list! first-cat)
      ;; Initialize main-content-container with default vertical layout showing effect list
      (update-params-panel! nil))
    
    ;; If editing, select the existing effect's category and effect
    (when existing-effect
      (let [effect-def (fx/get-effect (:effect-id existing-effect))
            cat (:category effect-def)]
        ;; Set the active tab to the effect's category
        ((:set-active-tab! tab-panel) cat)
        (update-effect-list! cat)
        ;; Find and select the effect in the list
        (let [list-size (.getSize effect-list-model)]
          (doseq [i (range list-size)]
            (let [item (.getElementAt effect-list-model i)]
              (when (= (:id item) (:effect-id existing-effect))
                (.setSelectedIndex effect-list i)))))))
    
    (.setContentPane dialog content)
    ;; Use wider dialog to accommodate visual editor when corner-pin is selected
    (.setSize dialog 650 700)
    (.setLocationRelativeTo dialog parent)
    (.setVisible dialog true)
    
    @result-atom))

;; ============================================================================
;; Quick Effect Creation (for testing/demo)
;; ============================================================================

(defn create-quick-effect
  "Create an effect data map with defaults.
   Useful for quick testing without going through the dialog."
  [effect-id & {:keys [enabled params] :or {enabled true params {}}}]
  {:effect-id effect-id
   :enabled enabled
   :params (merge (fx/get-default-params effect-id) params)})

;; ============================================================================
;; Demo/Test
;; ============================================================================

(comment
  ;; Test effect dialog
  (require '[seesaw.core :as ss])
  
  ;; Make sure effects are registered
  (require '[laser-show.animation.effects.shape])
  (require '[laser-show.animation.effects.color])
  (require '[laser-show.animation.effects.intensity])
  
  ;; Show new effect dialog
  (ss/invoke-later
   (show-effect-dialog! nil nil
     (fn [effect-data]
       (println "Created effect:" effect-data))))
  
  ;; Show edit effect dialog
  (ss/invoke-later
   (show-effect-dialog! nil 
     {:effect-id :scale :enabled true :params {:x-scale 1.5 :y-scale 1.5}}
     (fn [effect-data]
       (println "Updated effect:" effect-data))))
  
  ;; Test modulator dialog
  (ss/invoke-later
   (show-modulator-dialog! nil :x-scale 
     {:key :x-scale :label "X Scale" :type :float :min 0.0 :max 5.0 :default 1.0}
     (fn [modulator]
       (println "Modulator:" modulator))))
  )
