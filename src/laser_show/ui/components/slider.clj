(ns laser-show.ui.components.slider
  "Reusable slider component with synchronized text field.
   Supports both float and integer values with bidirectional binding."
  (:require [clojure.string :as str])
  (:import [java.awt Color Font]
           [javax.swing JSlider JTextField]
           [javax.swing.event ChangeListener DocumentListener]))

(defn- valid-number-text?
  "Check if text represents a valid parseable number.
   For integers, only digits and optional leading minus.
   For floats, allows formats like '1.5', '.5', '1.', '-.5', etc."
  [text integer?]
  (let [trimmed (str/trim (or text ""))]
    (when-not (str/blank? trimmed)
      (if integer?
        (re-matches #"-?\d+" trimmed)
        ;; Allow: 1, 1.5, .5, 1., -.5, -1., -1.5
        ;; Disallow: -, ., -.
        (and (re-matches #"-?\.?\d*\.?\d*" trimmed)
             (re-find #"\d" trimmed)  ;; Must have at least one digit
             (not (= trimmed "-"))
             (not (= trimmed "."))
             (not (= trimmed "-.")))))))

(defn create-slider
  "Create a slider with an editable text field for parameter editing.
   The slider and text field are synchronized bidirectionally.
   
   Options:
   - :min - Minimum value (default 0)
   - :max - Maximum value (default 1.0 for float, 100 for int)
   - :default - Default value (default min)
   - :integer? - If true, use integer values (default false for float)
   - :on-change - Callback fn called with new value when it changes
   - :decimal-places - Number of decimal places for float display (default 2)
   - :label-fn - Custom function to format the value for display
   - :slider-steps - Number of steps for the slider (default 1000 for float, range for int)
   
   Returns map with:
   - :slider - The JSlider component
   - :textfield - The JTextField component
   - :get-value - fn [] returns current value
   - :set-value! - fn [v] sets the value programmatically"
  [{min-opt :min max-opt :max :keys [default integer? on-change decimal-places label-fn slider-steps]}]
  (let [;; Determine defaults based on type
        integer? (boolean integer?)
        min-val (if (number? min-opt) 
                  (if integer? (int min-opt) (double min-opt))
                  (if integer? 0 0.0))
        max-val (if (number? max-opt)
                  (if integer? (int max-opt) (double max-opt))
                  (if integer? 100 1.0))
        default-val (if (number? default)
                      (if integer? (int default) (double default))
                      min-val)
        decimal-places (or decimal-places 2)
        format-str (str "%." decimal-places "f")
        
        ;; Slider steps - for floats use 1000 for fine granularity, for ints use actual range
        slider-steps (or slider-steps
                        (if integer?
                          (- max-val min-val)
                          1000))
        
        ;; Conversion helpers
        value->slider (if integer?
                        (fn [v] (- (int v) min-val))
                        (fn [v]
                          (let [range (- max-val min-val)]
                            (if (zero? range)
                              0
                              (int (* (/ (- v min-val) range) slider-steps))))))
        
        slider->value (if integer?
                        (fn [s] (+ s min-val))
                        (fn [s]
                          (let [range (- max-val min-val)]
                            (+ min-val (* (/ s (double slider-steps)) range)))))
        
        ;; Format value for display
        format-value (fn [v]
                       (if label-fn
                         (label-fn v)
                         (if integer?
                           (str (int v))
                           (format format-str (double v)))))
        
        ;; Parse text to value
        parse-text (fn [text]
                     (try
                       (if integer?
                         (Integer/parseInt (str/trim text))
                         (Double/parseDouble (str/trim text)))
                       (catch Exception _ nil)))
        
        ;; Create components
        slider (JSlider. 0 slider-steps (value->slider default-val))
        
        textfield (doto (JTextField. (format-value default-val) 8)
                    (.setFont (Font. "Monospaced" Font/PLAIN 11))
                    (.setBackground (Color. 60 60 60))
                    (.setForeground Color/WHITE)
                    (.setCaretColor Color/WHITE)
                    (.setHorizontalAlignment JTextField/CENTER))
        
        ;; Re-entrant lock to prevent infinite update loops
        updating? (atom false)
        
        ;; Update text field from slider value
        update-text-from-slider! (fn []
                                   (when-not @updating?
                                     (reset! updating? true)
                                     (let [v (slider->value (.getValue slider))]
                                       (.setText textfield (format-value v))
                                       (when on-change (on-change v)))
                                     (reset! updating? false)))
        
        ;; Update slider from text field value
        update-slider-from-text! (fn []
                                   (when-not @updating?
                                     (let [text (.getText textfield)]
                                       (when (valid-number-text? text integer?)
                                         (when-let [parsed (parse-text text)]
                                           (let [clamped (if integer?
                                                          (max min-val (min max-val (int parsed)))
                                                          (max min-val (min max-val (double parsed))))]
                                             (reset! updating? true)
                                             (.setValue slider (value->slider clamped))
                                             (when on-change (on-change clamped))
                                             (reset! updating? false)))))))]
    
    ;; Style slider
    (.setBackground slider (Color. 50 50 50))
    (.setForeground slider Color/WHITE)
    (.setPaintTicks slider false)
    
    ;; Slider change listener
    (.addChangeListener slider
      (reify ChangeListener
        (stateChanged [_ _]
          (update-text-from-slider!))))
    
    ;; Text field document listener for live updates while typing
    (.addDocumentListener (.getDocument textfield)
      (reify DocumentListener
        (insertUpdate [_ _] (update-slider-from-text!))
        (removeUpdate [_ _] (update-slider-from-text!))
        (changedUpdate [_ _] (update-slider-from-text!))))
    
    {:slider slider
     :textfield textfield
     :get-value (fn [] (slider->value (.getValue slider)))
     :set-value! (fn [v]
                   (reset! updating? true)
                   (let [clamped (if integer?
                                   (max min-val (min max-val (int v)))
                                   (max min-val (min max-val (double v))))]
                     (.setValue slider (value->slider clamped))
                     (.setText textfield (format-value clamped)))
                   (reset! updating? false))}))

;; Convenience aliases for backward compatibility
(def create-float-slider create-slider)

(defn create-int-slider
  "Create an integer slider. Convenience wrapper around create-slider."
  [opts]
  (create-slider (assoc opts :integer? true)))
