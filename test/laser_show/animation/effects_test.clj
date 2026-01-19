(ns laser-show.animation.effects-test
  "Tests for effect chain groups functionality."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.color]  ;; Load color effects
            [laser-show.animation.modulation :as mod]
            [laser-show.animation.types :as t]))

(def sample-effect-1
  {:effect-id :scale :params {:x-scale 1.5} :enabled? true})

(def sample-effect-2
  {:effect-id :rotate :params {:angle 45} :enabled? true})


(def sample-group
  {:type :group
   :id "group-1"
   :name "Test Group"
   :collapsed? false
   :enabled? true
   :items [sample-effect-1 sample-effect-2]})


(deftest effect?-test
  (testing "identifies effects correctly"
    (is (effects/effect? sample-effect-1))
    (is (effects/effect? {:effect-id :scale}))
    (is (effects/effect? {:type :effect :effect-id :scale})))

  (testing "returns false for groups"
    (is (not (effects/effect? sample-group)))
    (is (not (effects/effect? {:type :group :items []})))))


;; Position-Based Modulator Tests

(defn make-test-frame
  "Create a test frame with points at specified x positions.
   All points start white (r=g=b=1.0)."
  [x-positions]
  (t/make-frame
   (mapv (fn [x] (t/make-point x 0.0 1.0 1.0 1.0)) x-positions)))

(defn point-r [point] (point t/R))
(defn point-g [point] (point t/G))
(defn point-b [point] (point t/B))
(defn point-x [point] (point t/X))
(defn point-y [point] (point t/Y))

(deftest position-based-modulator-detection-test
  (testing "pos-x modulator is detected as per-point"
    (let [params {:red {:type :pos-x :min 0.0 :max 1.0}}]
      (is (mod/any-param-requires-per-point? params)
          "pos-x should be detected as per-point modulator")))
  
  (testing "static value is not detected as per-point"
    (let [params {:red 0.5}]
      (is (not (mod/any-param-requires-per-point? params))
          "static value should not be detected as per-point")))
  
  (testing "sine modulator is not detected as per-point"
    (let [params {:red {:type :sine :min 0.0 :max 1.0 :period 1.0}}]
      (is (not (mod/any-param-requires-per-point? params))
          "sine should not be detected as per-point modulator"))))

(deftest set-color-with-position-modulator-test
  (testing "set-color with pos-x modulator produces different colors"
    (let [;; Create a frame with points at different x positions
          frame (make-test-frame [-1.0 0.0 1.0])
          
          ;; Apply set-color effect with red modulated by x position
          ;; Using normalized 0.0-1.0 range for color values
          effect-chain {:effects [{:effect-id :set-color
                                   :enabled? true
                                   :params {:red {:type :pos-x :min 0.0 :max 1.0}
                                            :green 0.0
                                            :blue 0.0}}]}
          
          result-frame (effects/apply-effect-chain frame effect-chain 0 120.0)]
      
      ;; Get the resulting points (frame IS the points now)
      (let [p0 (first result-frame)    ;; x = -1.0
            p1 (second result-frame)   ;; x = 0.0
            p2 (nth result-frame 2)]   ;; x = 1.0
        
        ;; Points should have different red values based on x position
        ;; x=-1.0 -> t=0.0 -> red=0.0
        ;; x=0.0  -> t=0.5 -> red=0.5
        ;; x=1.0  -> t=1.0 -> red=1.0
        
        (is (< (point-r p0) 0.1)
            (str "Point at x=-1.0 should have low red, got " (point-r p0)))
        (is (< 0.4 (point-r p1) 0.6)
            (str "Point at x=0.0 should have mid red, got " (point-r p1)))
        (is (> (point-r p2) 0.9)
            (str "Point at x=1.0 should have high red, got " (point-r p2)))
        
        ;; All points should have zero green and blue
        (is (< (point-g p0) 0.01))
        (is (< (point-b p0) 0.01))))))

(deftest set-hue-with-position-modulator-test
  (testing "set-hue with rainbow-hue modulator produces different hues"
    ;; NOTE: set-hue only works on points with saturation > 0
    ;; White points (r=g=b=1) have saturation=0, so we need colored points
    ;; Let's use red points instead (h=0, s=1, v=1)
    (let [frame (t/make-frame [(t/make-point -1.0 0.0 1.0 0.0 0.0)   ;; red point left
                               (t/make-point 0.0 0.0 1.0 0.0 0.0)    ;; red point center
                               (t/make-point 1.0 0.0 1.0 0.0 0.0)])  ;; red point right
          
          ;; Apply set-hue effect with pos-x modulator (simpler than rainbow-hue)
          effect-chain {:effects [{:effect-id :set-hue
                                   :enabled? true
                                   :params {:hue {:type :pos-x :min 0 :max 240}}}]}  ;; red to blue gradient
          
          result-frame (effects/apply-effect-chain frame effect-chain 0 120.0)]
      
      ;; Get the resulting points (frame IS the points now)
      (let [p0 (first result-frame)    ;; x = -1.0, hue = 0 (red)
            p1 (second result-frame)   ;; x = 0.0, hue = 120 (green)
            p2 (nth result-frame 2)]   ;; x = 1.0, hue = 240 (blue)
        
        (println "\n--- SET-HUE TEST ---")
        (println "Left (x=-1, hue=0/red):    r=" (point-r p0) " g=" (point-g p0) " b=" (point-b p0))
        (println "Center (x=0, hue=120/green): r=" (point-r p1) " g=" (point-g p1) " b=" (point-b p1))
        (println "Right (x=1, hue=240/blue):  r=" (point-r p2) " g=" (point-g p2) " b=" (point-b p2))
        
        ;; Left should be reddish (hue 0)
        (is (> (point-r p0) 0.5) "Left point should have high red")
        ;; Center should be greenish (hue 120)
        (is (> (point-g p1) 0.5) "Center point should have high green")
        ;; Right should be blueish (hue 240)
        (is (> (point-b p2) 0.5) "Right point should have high blue")
        
        ;; Points should have different colors
        (is (not (and (= (point-r p0) (point-r p1) (point-r p2))
                      (= (point-g p0) (point-g p1) (point-g p2))
                      (= (point-b p0) (point-b p1) (point-b p2))))
            "Points at different x positions should have different colors")))))

(deftest hue-shift-with-position-modulator-test
  (testing "hue-shift with pos-x modulator shifts hues differently"
    (let [;; Create a frame with red points at different x positions
          frame (t/make-frame [(t/make-point -1.0 0.0 1.0 0.0 0.0)
                               (t/make-point 0.0 0.0 1.0 0.0 0.0)
                               (t/make-point 1.0 0.0 1.0 0.0 0.0)])
          
          ;; Apply hue-shift effect with degrees modulated by x position
          effect-chain {:effects [{:effect-id :hue-shift
                                   :enabled? true
                                   :params {:degrees {:type :pos-x :min 0 :max 180}}}]}
          
          result-frame (effects/apply-effect-chain frame effect-chain 0 120.0)]
      
      ;; Get the resulting points (frame IS the points now)
      (let [p0 (first result-frame)    ;; x = -1.0, shift=0° -> still red
            p2 (nth result-frame 2)]   ;; x = 1.0, shift=180° -> cyan
        
        ;; First point (x=-1.0) should stay mostly red (hue shift 0°)
        (is (> (point-r p0) 0.9)
            (str "Point at x=-1.0 should still be red, got r=" (point-r p0)))
        
        ;; Last point (x=1.0) should be cyan (hue shift 180°)
        ;; Cyan has high green and blue, low red
        (is (< (point-r p2) 0.1)
            (str "Point at x=1.0 should have low red (cyan), got r=" (point-r p2)))))))

;; Detailed Debug Test - Let's trace EXACTLY what happens

(deftest simple-position-modulator-trace-test
  (testing "trace what happens with pos-x modulator on red channel"
    (println "\n=== POSITION MODULATOR TRACE TEST ===")
    
    ;; Step 1: Create two points - one at x=-1, one at x=+1
    (let [point-left (t/make-point -1.0 0.0 1.0 1.0 1.0)   ;; white point on left
          point-right (t/make-point 1.0 0.0 1.0 1.0 1.0)]  ;; white point on right
      
      (println "\n--- INPUT POINTS ---")
      (println "Left point:  x=" (point-x point-left) " y=" (point-y point-left)
               " r=" (point-r point-left) " g=" (point-g point-left) " b=" (point-b point-left))
      (println "Right point: x=" (point-x point-right) " y=" (point-y point-right)
               " r=" (point-r point-right) " g=" (point-g point-right) " b=" (point-b point-right))
      
      ;; Step 2: Create a frame with these two points
      (let [frame (t/make-frame [point-left point-right])]
        
        ;; Step 3: Create effect with pos-x modulator on red
        ;; min=0.0, max=1.0 means: at x=-1 -> red=0.0, at x=+1 -> red=1.0
        (let [effect-chain {:effects [{:effect-id :set-color
                                       :enabled? true
                                       :params {:red {:type :pos-x :min 0.0 :max 1.0}
                                                :green 0.0
                                                :blue 0.0}}]}]
          
          (println "\n--- EFFECT CONFIG ---")
          (println "Effect: set-color")
          (println "Params: {:red {:type :pos-x :min 0.0 :max 1.0} :green 0.0 :blue 0.0}")
          (println "Expected: left point (x=-1) gets red=0.0, right point (x=+1) gets red=1.0")
          
          ;; Step 4: Check if params require per-point
          (let [params (:params (first (:effects effect-chain)))]
            (println "\n--- PER-POINT DETECTION ---")
            (println "any-param-requires-per-point?:" (mod/any-param-requires-per-point? params))
            (println "Red param is modulator?:" (mod/modulator-config? (:red params)))
            (println "Red param config-requires-per-point?:" (mod/config-requires-per-point? (:red params))))
          
          ;; Step 5: Apply the effect (frame IS the points now)
          (let [result-frame (effects/apply-effect-chain frame effect-chain 0 120.0)
                result-left (first result-frame)
                result-right (second result-frame)]
            
            (println "\n--- OUTPUT POINTS ---")
            (println "Left result:  x=" (point-x result-left) " y=" (point-y result-left)
                     " r=" (point-r result-left) " g=" (point-g result-left) " b=" (point-b result-left))
            (println "Right result: x=" (point-x result-right) " y=" (point-y result-right)
                     " r=" (point-r result-right) " g=" (point-g result-right) " b=" (point-b result-right))
            
            ;; Expected behavior:
            ;; Left point (x=-1): pos-x normalizes -1 to t=0.0, so red = 0.0 + 0*(1.0-0.0) = 0.0
            ;; Right point (x=+1): pos-x normalizes +1 to t=1.0, so red = 0.0 + 1*(1.0-0.0) = 1.0
            
            (println "\n--- ASSERTIONS ---")
            (println "Left red expected: ~0.0, actual:" (point-r result-left))
            (println "Right red expected: ~1.0, actual:" (point-r result-right))
            
            ;; The actual test assertions
            (is (< (point-r result-left) 0.1)
                (str "Left point (x=-1) should have red ~0, got " (point-r result-left)))
            (is (> (point-r result-right) 0.9)
                (str "Right point (x=+1) should have red ~1.0, got " (point-r result-right)))
            (is (not= (point-r result-left) (point-r result-right))
                "Left and right points should have different red values")))))))

(deftest trace-modulator-evaluation-test
  (testing "trace raw modulator evaluation with x values"
    (println "\n=== RAW MODULATOR EVALUATION TEST ===")
    
    (let [modulator {:type :pos-x :min 0.0 :max 1.0}]
      (println "Modulator config:" modulator)
      
      ;; Test at x=-1
      (let [context-left (mod/make-context {:time-ms 0 :bpm 120 :x -1.0 :y 0.0 :point-index 0 :point-count 2})]
        (println "\n--- Context at x=-1 ---")
        (println "Context:" (select-keys context-left [:x :y :point-index :point-count]))
        (let [result (mod/evaluate-modulator modulator context-left)]
          (println "Modulator result:" result)
          (is (< result 0.01) (str "At x=-1, modulator should return ~0.0, got " result))))
      
      ;; Test at x=+1
      (let [context-right (mod/make-context {:time-ms 0 :bpm 120 :x 1.0 :y 0.0 :point-index 1 :point-count 2})]
        (println "\n--- Context at x=+1 ---")
        (println "Context:" (select-keys context-right [:x :y :point-index :point-count]))
        (let [result (mod/evaluate-modulator modulator context-right)]
          (println "Modulator result:" result)
          (is (> result 0.99) (str "At x=+1, modulator should return ~1.0, got " result)))))))

;; Comprehensive tests for ALL position-based modulators

(deftest pos-y-modulator-test
  (testing ":pos-y modulator varies with y coordinate"
    (let [modulator {:type :pos-y :min 0 :max 100}]
      ;; At y=-1, t=0, result = 0
      (let [ctx (mod/make-context {:time-ms 0 :bpm 120 :x 0.0 :y -1.0})]
        (is (< (mod/evaluate-modulator modulator ctx) 1)
            "pos-y at y=-1 should return ~0"))
      ;; At y=0, t=0.5, result = 50
      (let [ctx (mod/make-context {:time-ms 0 :bpm 120 :x 0.0 :y 0.0})]
        (is (< 45 (mod/evaluate-modulator modulator ctx) 55)
            "pos-y at y=0 should return ~50"))
      ;; At y=+1, t=1, result = 100
      (let [ctx (mod/make-context {:time-ms 0 :bpm 120 :x 0.0 :y 1.0})]
        (is (> (mod/evaluate-modulator modulator ctx) 99)
            "pos-y at y=+1 should return ~100")))))

(deftest radial-modulator-test
  (testing ":radial modulator varies with distance from center"
    (let [modulator {:type :radial :min 0 :max 100 :normalize? true}]
      ;; At center (0,0), distance=0, result = 0
      (let [ctx (mod/make-context {:time-ms 0 :bpm 120 :x 0.0 :y 0.0})]
        (is (< (mod/evaluate-modulator modulator ctx) 1)
            "radial at center should return ~0"))
      ;; At corner (1,1), distance=sqrt(2), normalized t=1, result = 100
      (let [ctx (mod/make-context {:time-ms 0 :bpm 120 :x 1.0 :y 1.0})]
        (is (> (mod/evaluate-modulator modulator ctx) 99)
            "radial at corner (1,1) should return ~100"))
      ;; At edge (1,0), distance=1, normalized t=1/sqrt(2)≈0.71, result ≈ 71
      (let [ctx (mod/make-context {:time-ms 0 :bpm 120 :x 1.0 :y 0.0})]
        (let [result (mod/evaluate-modulator modulator ctx)]
          (is (< 65 result 80)
              (str "radial at (1,0) should return ~71, got " result)))))))

(deftest angle-modulator-test
  (testing ":angle modulator varies with angle from origin"
    (let [modulator {:type :angle :min 0 :max 360}]
      ;; At (1,0) = 0° -> t = 0.5 (since atan2 returns -π to π)
      ;; Actually: angle=0, t = (0 + π) / 2π = 0.5, result = 180
      (let [ctx (mod/make-context {:time-ms 0 :bpm 120 :x 1.0 :y 0.0})]
        (let [result (mod/evaluate-modulator modulator ctx)]
          (is (< 175 result 185)
              (str "angle at (1,0) should return ~180, got " result))))
      ;; At (0,1) = 90° = π/2 -> t = (π/2 + π) / 2π = 0.75, result = 270
      (let [ctx (mod/make-context {:time-ms 0 :bpm 120 :x 0.0 :y 1.0})]
        (let [result (mod/evaluate-modulator modulator ctx)]
          (is (< 265 result 275)
              (str "angle at (0,1) should return ~270, got " result))))
      ;; At (-1,0) = 180° = π -> t = (π + π) / 2π = 1.0, result = 360
      (let [ctx (mod/make-context {:time-ms 0 :bpm 120 :x -1.0 :y 0.0})]
        (let [result (mod/evaluate-modulator modulator ctx)]
          (is (> result 355)
              (str "angle at (-1,0) should return ~360, got " result)))))))

(deftest rainbow-hue-modulator-test
  (testing ":rainbow-hue modulator varies with position"
    ;; rainbow-hue returns hue degrees 0-360
    (let [modulator {:type :rainbow-hue :axis :x :speed 0}]  ;; speed 0 = no time animation
      ;; At x=-1, position=0, hue = 0
      (let [ctx (mod/make-context {:time-ms 0 :bpm 120 :x -1.0 :y 0.0})]
        (is (< (mod/evaluate-modulator modulator ctx) 5)
            "rainbow-hue at x=-1 should return ~0"))
      ;; At x=0, position=0.5, hue = 180
      (let [ctx (mod/make-context {:time-ms 0 :bpm 120 :x 0.0 :y 0.0})]
        (let [result (mod/evaluate-modulator modulator ctx)]
          (is (< 175 result 185)
              (str "rainbow-hue at x=0 should return ~180, got " result))))
      ;; At x=+1, position=1, hue = 360 (wraps to 0)
      (let [ctx (mod/make-context {:time-ms 0 :bpm 120 :x 1.0 :y 0.0})]
        (let [result (mod/evaluate-modulator modulator ctx)]
          ;; 360 mod 360 = 0, but the formula is position*360 = 360, so result is 0 or 360
          (is (or (< result 5) (> result 355))
              (str "rainbow-hue at x=+1 should return ~0 or ~360, got " result)))))))

(deftest all-position-modulators-integration-test
  (testing "all position modulators work with set-color effect"
    (let [;; Create points at different positions
          points [(t/make-point -1.0 -1.0 1.0 1.0 1.0)   ;; bottom-left
                  (t/make-point 0.0 0.0 1.0 1.0 1.0)     ;; center
                  (t/make-point 1.0 1.0 1.0 1.0 1.0)]    ;; top-right
          frame (t/make-frame points)]
      
      ;; Test pos-y (now using normalized 0.0-1.0 range)
      (let [effect-chain {:effects [{:effect-id :set-color
                                     :enabled? true
                                     :params {:red 0.0 :green {:type :pos-y :min 0.0 :max 1.0} :blue 0.0}}]}
            result (effects/apply-effect-chain frame effect-chain 0 120.0)]
        (println "\n--- pos-y test ---")
        (println "Bottom (y=-1):" (point-g (first result)))
        (println "Center (y=0):" (point-g (second result)))
        (println "Top (y=+1):" (point-g (nth result 2)))
        (is (< (point-g (first result)) 0.1) "Bottom point should have low green")
        (is (> (point-g (nth result 2)) 0.9) "Top point should have high green"))
      
      ;; Test radial (now using normalized 0.0-1.0 range)
      (let [effect-chain {:effects [{:effect-id :set-color
                                     :enabled? true
                                     :params {:red 0.0 :green 0.0 :blue {:type :radial :min 0.0 :max 1.0}}}]}
            result (effects/apply-effect-chain frame effect-chain 0 120.0)]
        (println "\n--- radial test ---")
        (println "Center:" (point-b (second result)))
        (println "Corner:" (point-b (nth result 2)))
        (is (< (point-b (second result)) 0.1) "Center point should have low blue (near origin)")
        (is (> (point-b (nth result 2)) 0.9) "Corner point should have high blue (far from origin)")))))

