(ns laser-show.ui.effect-dialogs
  "Dialogs for creating and editing effects in the effects grid.
   Includes:
   - Effect selection dialog (choose effect type and configure parameters)
   - Modulator configuration dialog (add modulation to parameters)"
(:require [seesaw.core :as ss]
            [seesaw.mig :as mig]
            [seesaw.border :as border]
            [clojure.string :as str]
            [laser-show.animation.effects :as fx]
            [laser-show.animation.modulation :as mod]
            [laser-show.ui.colors :as colors :refer [background-dark background-medium 
                                                      background-light text-primary text-secondary
                                                      get-effect-category-color]])
  (:import [java.awt Color Font Dimension Cursor]
           [java.awt.event KeyAdapter KeyEvent]
           [javax.swing JDialog JSlider JSpinner SpinnerNumberModel DefaultListModel
                        DefaultComboBoxModel ListSelectionModel BorderFactory JTextField]
           [javax.swing.event ChangeListener ListSelectionListener DocumentListener]))

;; ============================================================================
;; Constants
;; ============================================================================

(def effect-categories
  "Available effect categories for the UI."
  [{:id :shape :name "Shape"}
   {:id :color :name "Color"}
   {:id :intensity :name "Intensity"}])

(def modulator-types
  "Available modulator types for parameter modulation."
  [{:id :sine :name "Sine Wave" :description "Smooth oscillation"}
   {:id :triangle :name "Triangle Wave" :description "Linear up/down"}
   {:id :sawtooth :name "Sawtooth Wave" :description "Ramp up, reset"}
   {:id :square :name "Square Wave" :description "On/off toggle"}
   {:id :random :name "Random" :description "Random values per beat"}
   {:id :beat-decay :name "Beat Decay" :description "Decay each beat"}])

(def modulator-presets
  "Preset modulator configurations."
  [{:id :gentle-pulse :name "Gentle Pulse" :type :sine :min 0.7 :max 1.0 :freq 1.0}
   {:id :strong-pulse :name "Strong Pulse" :type :sine :min 0.3 :max 1.0 :freq 2.0}
   {:id :breathe :name "Breathe" :type :sine :min 0.5 :max 1.0 :freq 0.25}
   {:id :strobe-4x :name "Strobe 4x" :type :square :min 0.0 :max 1.0 :freq 4.0}
   {:id :ramp-up :name "Ramp Up" :type :sawtooth :min 0.0 :max 1.0 :freq 1.0}
   {:id :wobble :name "Wobble" :type :sine :min 0.9 :max 1.1 :freq 4.0}])

;; ============================================================================
;; UI Helpers
;; ============================================================================

(defn- create-slider-with-textfield
  "Create a slider with an editable text field for float parameter editing.
   The slider and text field are synchronized bidirectionally.
   Uses a normalized slider range [0, slider-steps] internally, then maps to [min, max].
   Returns {:slider JSlider :textfield JTextField :get-value fn :set-value! fn}"
  [{:keys [min max default on-change steps label-fn decimal-places]}]
  (let [min-val (double (if (number? min) min 0.0))
        max-val (double (if (number? max) max 1.0))
        default-val (double (if (number? default) default min-val))
        decimal-places (or decimal-places 2)
        format-str (str "%." decimal-places "f")
        ;; Use 1000 steps for finer granularity
        slider-steps 1000
        ;; Helper to convert value to slider position
        value->slider (fn [v]
                        (let [range (- max-val min-val)]
                          (if (zero? range)
                            0
                            (int (* (/ (- v min-val) range) slider-steps)))))
        ;; Helper to convert slider position to value
        slider->value (fn [s]
                        (let [range (- max-val min-val)]
                          (+ min-val (* (/ s (double slider-steps)) range))))
        slider (JSlider. 0 slider-steps (value->slider default-val))
        updating-atom (atom false)
        ;; Create JTextField directly for better control over events
        textfield (doto (JTextField. (if label-fn 
                                       (label-fn default-val)
                                       (format format-str default-val))
                                     5)
                    (.setFont (Font. "Monospaced" Font/PLAIN 11))
                    (.setBackground (Color. 60 60 60))
                    (.setForeground Color/WHITE)
                    (.setHorizontalAlignment JTextField/CENTER))
        
        update-from-slider! (fn []
                              (when-not @updating-atom
                                (reset! updating-atom true)
                                (let [v (slider->value (.getValue slider))]
                                  (.setText textfield (if label-fn
                                                        (label-fn v)
                                                        (format format-str v)))
                                  (when on-change (on-change v)))
                                (reset! updating-atom false)))
        
        update-from-textfield! (fn []
                                 (when-not @updating-atom
                                   (reset! updating-atom true)
                                   (try
                                     (let [text-val (.getText textfield)
                                           ;; Parse numeric value, allowing negative and decimal
                                           cleaned (str/replace text-val #"[^\d.\-]" "")
                                           parsed (if (empty? cleaned) 
                                                    (slider->value (.getValue slider))
                                                    (Double/parseDouble cleaned))
                                           clamped (max min-val (min max-val parsed))]
                                       (.setValue slider (value->slider clamped))
                                       (.setText textfield (if label-fn
                                                            (label-fn clamped)
                                                            (format format-str clamped)))
                                       (when on-change (on-change clamped)))
                                     (catch Exception _
                                       (let [current-v (slider->value (.getValue slider))]
                                         (.setText textfield (if label-fn
                                                              (label-fn current-v)
                                                              (format format-str current-v))))))
                                   (reset! updating-atom false)))]
    
    (.setMinorTickSpacing slider (or steps 1))
    (.setPaintTicks slider false)
    (.setBackground slider (Color. 50 50 50))
    (.setForeground slider Color/WHITE)
    
    (.addChangeListener slider
      (reify ChangeListener
        (stateChanged [_ _]
          (update-from-slider!))))
    
    ;; Focus lost listener
    (.addFocusListener textfield
      (reify java.awt.event.FocusListener
        (focusGained [_ _])
        (focusLost [_ _] (update-from-textfield!))))
    
    ;; Key listener for Enter key
    (.addKeyListener textfield
      (proxy [KeyAdapter] []
        (keyPressed [e]
          (when (= (.getKeyCode e) KeyEvent/VK_ENTER)
            (update-from-textfield!)))))
    
    {:slider slider
     :textfield textfield
     :get-value (fn [] (slider->value (.getValue slider)))
     :set-value! (fn [v]
                   (reset! updating-atom true)
                   (.setValue slider (value->slider v))
                   (ss/text! textfield (if label-fn
                                         (label-fn v)
                                         (format format-str v)))
                   (reset! updating-atom false))}))

(defn- create-slider
  "Create a slider with an editable text field for float/int parameter editing.
   Wraps create-slider-with-textfield for backward compatibility."
  [{:keys [min max default on-change steps label-fn] :as opts}]
  (let [ctrl (create-slider-with-textfield opts)]
    {:slider (:slider ctrl)
     :label (:textfield ctrl)
     :get-value (:get-value ctrl)
     :set-value! (:set-value! ctrl)}))

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

(defn- create-tab-panel
  "Create a horizontal tab panel for category selection.
   Returns {:panel JPanel :set-active-tab! fn :get-active-tab fn}
   
   Parameters:
   - categories: Sequence of {:id :keyword :name \"string\"} maps
   - initial-category: The :id of the initially active category
   - on-category-change: (fn [category-id]) called when tab is clicked"
  [categories initial-category on-category-change]
  (let [active-tab-atom (atom initial-category)
        buttons-atom (atom [])
        
        update-buttons! (fn [new-active]
                          (doseq [[btn cat] @buttons-atom]
                            (let [active? (= (:id cat) new-active)
                                  cat-color (get-effect-category-color (:id cat))]
                              (ss/config! btn
                                          :background (if active? cat-color background-medium)
                                          :foreground (if active? text-primary text-secondary)
                                          :font (Font. "SansSerif" (if active? Font/BOLD Font/PLAIN) 12))
                              (.setBorder btn (BorderFactory/createEmptyBorder 8 16 8 16))
                              (.setCursor btn (Cursor/getPredefinedCursor Cursor/HAND_CURSOR)))))
        
        buttons (mapv (fn [cat]
                        (let [btn (ss/button 
                                   :text (:name cat)
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
     :get-active-tab (fn [] @active-tab-atom)}))

;; ============================================================================
;; Parameter Panel Builder
;; ============================================================================

(defn- create-int-slider-with-textfield
  "Create a slider with an editable text field for integer parameter editing.
   The slider and text field are synchronized bidirectionally.
   Returns {:slider JSlider :textfield JTextField :get-value fn :set-value! fn}"
  [{:keys [min max default on-change]}]
  (let [min-val (if (number? min) (int min) 0)
        max-val (if (number? max) (int max) 100)
        default-val (if (number? default) (int default) min-val)
        slider (JSlider. min-val max-val default-val)
        updating-atom (atom false)
        ;; Create JTextField directly for better control over events
        textfield (doto (JTextField. (str default-val) 5)
                    (.setFont (Font. "Monospaced" Font/PLAIN 11))
                    (.setBackground (Color. 60 60 60))
                    (.setForeground Color/WHITE)
                    (.setHorizontalAlignment JTextField/CENTER))
        
        update-from-slider! (fn []
                              (when-not @updating-atom
                                (reset! updating-atom true)
                                (let [v (.getValue slider)]
                                  (.setText textfield (str v))
                                  (when on-change (on-change v)))
                                (reset! updating-atom false)))
        
        update-from-textfield! (fn []
                                 (when-not @updating-atom
                                   (reset! updating-atom true)
                                   (try
                                     (let [text-val (.getText textfield)
                                           cleaned (str/trim text-val)
                                           parsed (if (empty? cleaned)
                                                    (.getValue slider)
                                                    (Integer/parseInt cleaned))
                                           clamped (max min-val (min max-val parsed))]
                                       (.setValue slider clamped)
                                       (.setText textfield (str clamped))
                                       (when on-change (on-change clamped)))
                                     (catch Exception _
                                       (let [current-v (.getValue slider)]
                                         (.setText textfield (str current-v)))))
                                   (reset! updating-atom false)))]
    
    (.setMinorTickSpacing slider 1)
    (.setPaintTicks slider false)
    (.setBackground slider (Color. 50 50 50))
    (.setForeground slider Color/WHITE)
    
    (.addChangeListener slider
      (reify ChangeListener
        (stateChanged [_ _]
          (update-from-slider!))))
    
    ;; Focus lost listener
    (.addFocusListener textfield
      (reify java.awt.event.FocusListener
        (focusGained [_ _])
        (focusLost [_ _] (update-from-textfield!))))
    
    ;; Key listener for Enter key
    (.addKeyListener textfield
      (proxy [KeyAdapter] []
        (keyPressed [e]
          (when (= (.getKeyCode e) KeyEvent/VK_ENTER)
            (update-from-textfield!)))))
    
    {:slider slider
     :textfield textfield
     :get-value (fn [] (.getValue slider))
     :set-value! (fn [v]
                   (reset! updating-atom true)
                   (.setValue slider (int v))
                   (.setText textfield (str (int v)))
                   (reset! updating-atom false))}))

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
                (let [ctrl (create-slider {:min (or min 0.0)
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
                   :constraints ["insets 2", "[100!][grow, fill][50!][80!][20!]", ""]
                   :items [[(ss/label :text (str label ":") 
                                     :foreground Color/WHITE) ""]
                           [(:slider ctrl) "growx"]
                           [(:label ctrl) ""]
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
                (let [ctrl (create-int-slider-with-textfield 
                            {:min (or min 0)
                             :max (or max 255)
                             :default default
                             :on-change (fn [_v]
                                          (reset! modulator-atom nil)
                                          (ss/config! mod-indicator :text "")
                                          (when on-param-change (on-param-change)))})]
                  (reset! control-atom ctrl)
                  (mig/mig-panel
                   :constraints ["insets 2", "[100!][grow, fill][50!][80!][20!]", ""]
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
                (let [ctrl (create-slider {:min 0.0 :max 1.0 :default (or default 0.5)
                                          :on-change (fn [_v]
                                                       (when on-param-change (on-param-change)))})]
                  (reset! control-atom ctrl)
                  (mig/mig-panel
                   :constraints ["insets 2", "[100!][grow, fill][50!]", ""]
                   :items [[(ss/label :text (str label ":") 
                                     :foreground Color/WHITE) ""]
                           [(:slider ctrl) "growx"]
                           [(:label ctrl) ""]])))]
    
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
                        (if (fn? v)
                          ((:set-modulator! ctrl) v)
                          ((:set-value! ctrl) v)))))}))

;; ============================================================================
;; Modulator Dialog
;; ============================================================================

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
        
        ;; Extract existing modulator config if available
        existing-config (when existing-modulator
                          (mod/get-modulator-config existing-modulator))
        
        ;; Modulator type combo
        type-model (DefaultComboBoxModel. (into-array (map :name modulator-types)))
        type-combo (ss/combobox :model type-model)
        
        ;; Parameter defaults based on param-def
        param-min (or (:min param-def) 0.0)
        param-max (or (:max param-def) 1.0)
        param-default (or (:default param-def) (/ (+ param-min param-max) 2))
        
        ;; Initial values from existing config or defaults
        init-min (or (:min existing-config) param-min)
        init-max (or (:max existing-config) param-max)
        init-freq (or (:freq existing-config) 1.0)
        init-phase (or (:phase existing-config) 0.0)
        init-type-idx (if-let [t (:type existing-config)]
                        (case t
                          :sine 0
                          :triangle 1
                          :sawtooth 2
                          :square 3
                          :random 4
                          :beat-decay 5
                          0)
                        0)
        
        ;; Value controls
        min-ctrl (create-slider {:min param-min :max param-max :default init-min
                                :label-fn #(format "%.2f" %)})
        max-ctrl (create-slider {:min param-min :max param-max :default init-max
                                :label-fn #(format "%.2f" %)})
        freq-ctrl (create-slider {:min 0.1 :max 8.0 :default init-freq
                                 :label-fn #(format "%.1fx" %)})
        phase-ctrl (create-slider {:min 0.0 :max 1.0 :default init-phase
                                  :label-fn #(format "%.2f" %)})
        
        ;; Preset buttons
        preset-panel (ss/horizontal-panel
                      :items (mapv (fn [preset]
                                    (ss/button 
                                     :text (:name preset)
                                     :font (Font. "SansSerif" Font/PLAIN 10)
                                     :listen [:action 
                                              (fn [_]
                                                ;; Apply preset
                                                (let [type-idx (case (:type preset)
                                                                :sine 0
                                                                :triangle 1
                                                                :sawtooth 2
                                                                :square 3
                                                                :random 4
                                                                :beat-decay 5
                                                                0)]
                                                  (.setSelectedIndex type-combo type-idx))
                                                ((:set-value! min-ctrl) (:min preset))
                                                ((:set-value! max-ctrl) (:max preset))
                                                ((:set-value! freq-ctrl) (:freq preset)))]))
                                  modulator-presets)
                      :background (Color. 45 45 45))
        
        ;; Create modulator based on current settings
        create-modulator (fn []
                          (let [type-idx (.getSelectedIndex type-combo)
                                mod-type (:id (nth modulator-types type-idx))
                                min-v ((:get-value min-ctrl))
                                max-v ((:get-value max-ctrl))
                                freq ((:get-value freq-ctrl))
                                phase ((:get-value phase-ctrl))]
                            (case mod-type
                              :sine (mod/sine-mod min-v max-v freq phase)
                              :triangle (mod/triangle-mod min-v max-v freq phase)
                              :sawtooth (mod/sawtooth-mod min-v max-v freq phase)
                              :square (mod/square-mod min-v max-v freq)
                              :random (mod/random-mod min-v max-v freq)
                              :beat-decay (mod/beat-decay max-v min-v)
                              (mod/sine-mod min-v max-v freq))))
        
        ;; Notify about modulator change for live preview
        notify-modulator-change! (fn []
                                   (when on-modulator-change
                                     (let [m (create-modulator)]
                                       (on-modulator-change m))))
        
        ;; Add change listeners to sliders for live preview
        _ (when on-modulator-change
            (.addChangeListener (:slider min-ctrl)
              (reify ChangeListener
                (stateChanged [_ _] (notify-modulator-change!))))
            (.addChangeListener (:slider max-ctrl)
              (reify ChangeListener
                (stateChanged [_ _] (notify-modulator-change!))))
            (.addChangeListener (:slider freq-ctrl)
              (reify ChangeListener
                (stateChanged [_ _] (notify-modulator-change!))))
            (.addChangeListener (:slider phase-ctrl)
              (reify ChangeListener
                (stateChanged [_ _] (notify-modulator-change!))))
            ;; Also listen to type combo changes
            (ss/listen type-combo :action (fn [_] (notify-modulator-change!))))
        
        ;; Buttons
        ok-btn (ss/button :text "Apply Modulator"
                         :listen [:action (fn [_]
                                           (let [m (create-modulator)]
                                             (reset! result-atom m)
                                             (when on-confirm (on-confirm m))
                                             (.dispose dialog)))])
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
                         
                         ;; Type selection
                         [(mig/mig-panel
                           :constraints ["insets 5", "[100!][grow]", ""]
                           :items [[(ss/label :text "Type:" :foreground Color/WHITE) ""]
                                   [type-combo "growx"]]
                           :background (Color. 45 45 45)) "growx"]
                         
                         ;; Parameters
                         [(ss/label :text "Parameters" 
                                   :font (Font. "SansSerif" Font/BOLD 11)
                                   :foreground (Color. 180 180 180)) ""]
                         
                         [(mig/mig-panel
                           :constraints ["insets 5", "[80!][grow, fill][50!]", ""]
                           :items [[(ss/label :text "Min Value:" :foreground Color/WHITE) ""]
                                   [(:slider min-ctrl) "growx"]
                                   [(:label min-ctrl) ""]]
                           :background (Color. 45 45 45)) "growx"]
                         
                         [(mig/mig-panel
                           :constraints ["insets 5", "[80!][grow, fill][50!]", ""]
                           :items [[(ss/label :text "Max Value:" :foreground Color/WHITE) ""]
                                   [(:slider max-ctrl) "growx"]
                                   [(:label max-ctrl) ""]]
                           :background (Color. 45 45 45)) "growx"]
                         
                         [(mig/mig-panel
                           :constraints ["insets 5", "[80!][grow, fill][50!]", ""]
                           :items [[(ss/label :text "Frequency:" :foreground Color/WHITE) ""]
                                   [(:slider freq-ctrl) "growx"]
                                   [(:label freq-ctrl) ""]]
                           :background (Color. 45 45 45)) "growx"]
                         
                         [(mig/mig-panel
                           :constraints ["insets 5", "[80!][grow, fill][50!]", ""]
                           :items [[(ss/label :text "Phase:" :foreground Color/WHITE) ""]
                                   [(:slider phase-ctrl) "growx"]
                                   [(:label phase-ctrl) ""]]
                           :background (Color. 45 45 45)) "growx"]
                         
                         ;; Presets
                         [(ss/label :text "Presets" 
                                   :font (Font. "SansSerif" Font/BOLD 11)
                                   :foreground (Color. 180 180 180)) ""]
                         [(ss/scrollable preset-panel :hscroll :as-needed :vscroll :never) "growx"]
                         
                         ;; Buttons
                         [(mig/mig-panel
                           :constraints ["insets 10", "[grow][][]", ""]
                           :items [[remove-btn ""]
                                   [cancel-btn ""]
                                   [ok-btn ""]]
                           :background (Color. 45 45 45)) "growx, dock south"]])]
    
    (style-dialog-panel content)
    
    ;; Initialize type combo from existing config
    (when existing-config
      (.setSelectedIndex type-combo init-type-idx))
    
    (.setContentPane dialog content)
    (.setSize dialog 450 450)
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
        
        ;; Function to update params panel
        update-params-panel! (fn [effect-def]
                              ;; Remove all existing content
                              (.removeAll params-container)
                              (if effect-def
                                ;; Pass notify-effect-change! so param changes trigger live preview
                                (let [pp (create-params-panel effect-def on-modulate notify-effect-change!)]
                                  (reset! params-panel-ref pp)
                                  (.add params-container (:panel pp) java.awt.BorderLayout/CENTER)
                                  ;; If editing, set existing params
                                  (when existing-effect
                                    ((:set-params! pp) (:params existing-effect))))
                                ;; No effect selected - show placeholder
                                (do
                                  (reset! params-panel-ref nil)
                                  (.add params-container empty-placeholder java.awt.BorderLayout/CENTER)))
                              (.revalidate params-container)
                              (.repaint params-container)
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
        
        ;; Main content
        content (mig/mig-panel
                 :constraints ["insets 10, wrap 1", "[grow, fill]", "[][][grow, fill][grow, fill][]"]
                 :items [;; Title
                         [(ss/label :text (if existing-effect "Edit Effect" "New Effect")
                                   :font (Font. "SansSerif" Font/BOLD 16)
                                   :foreground Color/WHITE) ""]
                         
                         ;; Category selection (tab panel)
                         [(:panel tab-panel) "growx"]
                         
                         ;; Effect list
                         [(ss/border-panel
                           :north (ss/label :text "Effects"
                                           :font (Font. "SansSerif" Font/BOLD 11)
                                           :foreground (Color. 180 180 180))
                           :center (ss/scrollable effect-list 
                                                 :border (border/line-border :color (Color. 60 60 60)))
                           :background (Color. 45 45 45)) "grow, h 150!"]
                         
                         ;; Parameters panel
                         [(ss/border-panel
                           :north (ss/label :text "Parameters"
                                           :font (Font. "SansSerif" Font/BOLD 11)
                                           :foreground (Color. 180 180 180))
                           :center (ss/scrollable params-container
                                                 :border (border/line-border :color (Color. 60 60 60)))
                           :background (Color. 45 45 45)) "grow"]
                         
                         ;; Buttons
                         [(mig/mig-panel
                           :constraints ["insets 10", "[grow][][]", ""]
                           :items [[(ss/label) "growx"]
                                   [cancel-btn ""]
                                   [ok-btn ""]]
                           :background (Color. 45 45 45)) "growx, dock south"]])]
    
    (style-dialog-panel content)
    
    ;; Initialize with first category
    (let [first-cat (:id (first effect-categories))]
      (update-effect-list! first-cat))
    
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
    (.setSize dialog 500 550)
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
