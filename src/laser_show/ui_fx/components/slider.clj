(ns laser-show.ui-fx.components.slider
  "Slider component with text field for precise value entry."
  (:require [cljfx.api :as fx]
            [laser-show.ui-fx.styles :as styles]))

;; ============================================================================
;; Value Slider Component
;; ============================================================================

(defn value-slider
  "A slider with a linked text field for precise value entry.
   
   Props:
   - :value - Current value
   - :min - Minimum value (default 0)
   - :max - Maximum value (default 1)
   - :step - Step increment (optional)
   - :integer? - Whether to use integer values (default false)
   - :label - Optional label text
   - :on-change - Callback (fn [new-value])
   - :width - Slider width (default 150)"
  [{:keys [value min max step integer? label on-change width]
    :or {min 0.0 max 1.0 integer? false width 150}}]
  
  (let [format-value (if integer?
                       #(str (int %))
                       #(format "%.2f" (double %)))
        parse-value (if integer?
                      #(try (Integer/parseInt %) (catch Exception _ nil))
                      #(try (Double/parseDouble %) (catch Exception _ nil)))]
    
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children (filterv
                some?
                [(when label
                   {:fx/type :label
                    :text (str label ":")
                    :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                               "-fx-min-width: 80;")})
                 
                 {:fx/type :slider
                  :min min
                  :max max
                  :value (or value min)
                  :pref-width width
                  :block-increment (or step (/ (- max min) 100))
                  :on-value-changed (fn [new-val]
                                      (when on-change
                                        (on-change (if integer?
                                                     (int new-val)
                                                     new-val))))}
                 
                 {:fx/type :text-field
                  :text (format-value (or value min))
                  :pref-width 60
                  :style "-fx-font-size: 11px;"
                  :on-action (fn [e]
                               (let [text (.getText (.getSource e))
                                     parsed (parse-value text)]
                                 (when (and parsed on-change
                                            (>= parsed min)
                                            (<= parsed max))
                                   (on-change parsed))))}])}))

;; ============================================================================
;; Parameter Control Component
;; ============================================================================

(defn param-control
  "A parameter control based on type.
   
   Props:
   - :param-def - Parameter definition map with :key, :label, :type, :min, :max, :default
   - :value - Current value
   - :on-change - Callback (fn [new-value])"
  [{:keys [param-def value on-change]}]
  (let [{:keys [key label type min max default]} param-def
        current-value (if (some? value) value default)]
    (case type
      :float
      {:fx/type value-slider
       :label label
       :value current-value
       :min (or min 0.0)
       :max (or max 1.0)
       :on-change on-change}
      
      :int
      {:fx/type value-slider
       :label label
       :value current-value
       :min (or min 0)
       :max (or max 255)
       :integer? true
       :on-change on-change}
      
      :bool
      {:fx/type :h-box
       :spacing 8
       :alignment :center-left
       :children [{:fx/type :check-box
                   :text label
                   :selected (boolean current-value)
                   :style (str "-fx-text-fill: " (:text-primary styles/colors) ";")
                   :on-selected-changed (fn [selected]
                                          (when on-change
                                            (on-change selected)))}]}
      
      :choice
      {:fx/type :h-box
       :spacing 8
       :alignment :center-left
       :children [{:fx/type :label
                   :text (str label ":")
                   :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                              "-fx-min-width: 80;")}
                  {:fx/type :combo-box
                   :items (or (:options param-def) [])
                   :value current-value
                   :on-value-changed (fn [new-val]
                                       (when on-change
                                         (on-change new-val)))}]}
      
      ;; Default: float slider
      {:fx/type value-slider
       :label label
       :value current-value
       :min 0.0
       :max 1.0
       :on-change on-change})))

;; ============================================================================
;; Compact Slider (no label)
;; ============================================================================

(defn compact-slider
  "A compact slider without label, just slider + value display.
   
   Props:
   - :value - Current value
   - :min - Minimum value
   - :max - Maximum value
   - :integer? - Use integers
   - :on-change - Callback"
  [{:keys [value min max integer? on-change]
    :or {min 0.0 max 1.0 integer? false}}]
  
  (let [format-value (if integer?
                       #(str (int %))
                       #(format "%.2f" (double %)))]
    {:fx/type :h-box
     :spacing 4
     :alignment :center-left
     :children [{:fx/type :slider
                 :min min
                 :max max
                 :value (or value min)
                 :pref-width 100
                 :on-value-changed (fn [new-val]
                                     (when on-change
                                       (on-change (if integer?
                                                    (int new-val)
                                                    new-val))))}
                {:fx/type :label
                 :text (format-value (or value min))
                 :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                            "-fx-font-size: 10px;"
                            "-fx-min-width: 40;")}]}))
