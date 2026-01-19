(ns laser-show.animation.modulation-diagnostics
  "Diagnostic tools for debugging position-based modulators.
   
   This namespace provides REPL-friendly functions to diagnose issues
   with per-point modulators without flooding logs during frame rendering.
   
   Usage from REPL:
   (require '[laser-show.animation.modulation-diagnostics :as diag])
   
   ;; Test if params trigger per-point detection
   (diag/analyze-params {:hue {:type :pos-x :min 0 :max 360}})
   
   ;; Test modulator evaluation with sample points
   (diag/test-modulator-with-points {:type :pos-x :min 0 :max 360}
                                    [{:x -1.0 :y 0.0}
                                     {:x 0.0 :y 0.0}  
                                     {:x 1.0 :y 0.0}])
   
   ;; Capture diagnostic info for next N frames
   (diag/enable-capture! 1)
   ;; ... trigger frame rendering ...
   (diag/get-captured-data)"
  (:require [laser-show.animation.modulation :as mod]
            [clojure.pprint :as pp]))

;; Diagnostic state - captures info from frame rendering
(defonce !diagnostic-state 
  (atom {:enabled? false
         :frames-to-capture 0
         :captured-frames []
         :last-params nil
         :last-per-point-check nil}))

;; Public API for REPL diagnostics

(defn analyze-params
  "Analyze a params map to check if it would trigger per-point modulation.
   Returns a detailed analysis map."
  [params]
  (let [requires-per-point? (mod/any-param-requires-per-point? params)
        modulator-configs (filter mod/modulator-config? (vals params))
        per-point-mods (filter mod/config-requires-per-point? modulator-configs)]
    {:input-params params
     :requires-per-point? requires-per-point?
     :total-modulators (count modulator-configs)
     :per-point-modulators (count per-point-mods)
     :modulator-types (mapv :type modulator-configs)
     :per-point-types (mapv :type per-point-mods)
     :analysis (if requires-per-point?
                 "✅ Will take PER-POINT path (good for position-based effects)"
                 "⚠️ Will take GLOBAL path (position info not used)")}))

(defn test-modulator-with-points
  "Test a modulator config with a list of sample points.
   Returns the evaluated values for each point."
  [modulator-config sample-points]
  (let [time-ms (System/currentTimeMillis)
        bpm 120.0]
    {:modulator-config modulator-config
     :is-per-point-type? (mod/config-requires-per-point? modulator-config)
     :modulator-type (:type modulator-config)
     :results 
     (mapv (fn [{:keys [x y] :as point}]
             (let [context (mod/make-context {:time-ms time-ms
                                              :bpm bpm
                                              :x x
                                              :y y
                                              :point-index 0
                                              :point-count (count sample-points)})
                   result (mod/evaluate-modulator modulator-config context)]
               {:point point
                :x x
                :y y
                :x-nil? (nil? x)
                :y-nil? (nil? y)
                :result result}))
           sample-points)}))

(defn test-position-modulators
  "Run a quick test of all position-based modulator types.
   Uses a standard set of test points spanning the coordinate space."
  []
  (let [test-points [{:x -1.0 :y -1.0}  ;; bottom-left
                     {:x 0.0 :y 0.0}    ;; center
                     {:x 1.0 :y 1.0}    ;; top-right
                     {:x 0.5 :y -0.5}]  ;; somewhere else
        modulators [{:type :pos-x :min 0 :max 100}
                    {:type :pos-y :min 0 :max 100}
                    {:type :radial :min 0 :max 100}
                    {:type :angle :min 0 :max 360}
                    {:type :rainbow-hue :axis :x :speed 0}]]
    (println "=== Position-Based Modulator Test ===")
    (println "Test points:" (pr-str test-points))
    (println)
    (doseq [mod-config modulators]
      (println "--- Testing" (:type mod-config) "---")
      (let [results (test-modulator-with-points mod-config test-points)]
        (doseq [{:keys [x y result x-nil? y-nil?]} (:results results)]
          (println (format "  x=%.2f y=%.2f -> %.2f %s"
                           (double x) (double y) (double result)
                           (if (or x-nil? y-nil?) " ⚠️ NIL DETECTED!" ""))))
        (println)))))

(defn print-analysis
  "Pretty-print analysis of a params map."
  [params]
  (let [analysis (analyze-params params)]
    (println "=== Params Analysis ===")
    (println "Input params:")
    (pp/pprint params)
    (println)
    (println "Analysis:")
    (println "  Requires per-point?:" (:requires-per-point? analysis))
    (println "  Total modulators:" (:total-modulators analysis))
    (println "  Per-point modulators:" (:per-point-modulators analysis))
    (println "  Modulator types:" (:modulator-types analysis))
    (println "  Per-point types:" (:per-point-types analysis))
    (println)
    (println (:analysis analysis))))

;; Frame capture API (for capturing info during actual rendering)

(defn enable-capture!
  "Enable diagnostic capture for the next N frames.
   After N frames, capturing automatically stops."
  [num-frames]
  (swap! !diagnostic-state assoc
         :enabled? true
         :frames-to-capture num-frames
         :captured-frames [])
  (println (str "📊 Diagnostic capture enabled for " num-frames " frame(s).")))


(defn get-captured-data
  "Get the captured diagnostic data from recent frames."
  []
  (:captured-frames @!diagnostic-state))

(defn print-captured-data
  "Pretty-print the captured diagnostic data."
  []
  (let [frames (get-captured-data)]
    (if (empty? frames)
      (println "No frames captured. Use (enable-capture! n) first.")
      (doseq [[idx frame] (map-indexed vector frames)]
        (println (str "=== Frame " idx " ==="))
        (pp/pprint frame)
        (println)))))


;; Example usage comment
(comment
  ;; Test position-based modulators
  (test-position-modulators)
  
  ;; Analyze specific params
  (print-analysis {:hue {:type :pos-x :min 0 :max 360}})
  (print-analysis {:hue {:type :sine :min 0 :max 360 :period 1}})
  
  ;; Test a rainbow config
  (test-modulator-with-points {:type :rainbow-hue :axis :x :speed 60}
                              [{:x -1.0 :y 0.0}
                               {:x 0.0 :y 0.0}
                               {:x 1.0 :y 0.0}])
  
  ;; Enable frame capture
  (enable-capture! 1)
  ;; ... do something that renders frames ...
  (print-captured-data)
  
  nil)
