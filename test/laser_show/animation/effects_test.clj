(ns laser-show.animation.effects-test
  "Tests for effect chain groups functionality."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.tools.logging.test :as log-test]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.color]      ;; Load color effects
            [laser-show.animation.effects.intensity]  ;; Load intensity effects
            [laser-show.animation.effects.shape]
            [laser-show.animation.effects.zone]
            [laser-show.animation.modulation :as mod]
            [laser-show.animation.types :as t]
            [laser-show.backend.output-errors :as oe]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.routing.zone-remap :as zone-remap]))

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
          
          result-frame (effects/apply-effect-chain frame effect-chain {:time-ms 0 :bpm 120.0})
          p0 (first result-frame)    ;; x = -1.0
          p1 (second result-frame)   ;; x = 0.0
          p2 (nth result-frame 2)]
      
      ;; Get the resulting points (frame IS the points now)
      ;; x = 1.0
      
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
      (is (< (point-b p0) 0.01)))))

(deftest set-hue-with-position-modulator-test
  (testing "set-hue with pos-x modulator produces different hues"
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
          
          result-frame (effects/apply-effect-chain frame effect-chain {:time-ms 0 :bpm 120.0})
          p0 (first result-frame)    ;; x = -1.0, hue = 0 (red)
          p1 (second result-frame)   ;; x = 0.0, hue = 120 (green)
          p2 (nth result-frame 2)]
      
      ;; Get the resulting points (frame IS the points now)
      ;; x = 1.0, hue = 240 (blue)
      
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
          "Points at different x positions should have different colors"))))

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
          
          result-frame (effects/apply-effect-chain frame effect-chain {:time-ms 0 :bpm 120.0})
          p0 (first result-frame)    ;; x = -1.0, shift=0° -> still red
          p2 (nth result-frame 2)]
      
      ;; Get the resulting points (frame IS the points now)
      ;; x = 1.0, shift=180° -> cyan
      
      ;; First point (x=-1.0) should stay mostly red (hue shift 0°)
      (is (> (point-r p0) 0.9)
          (str "Point at x=-1.0 should still be red, got r=" (point-r p0)))
      
      ;; Last point (x=1.0) should be cyan (hue shift 180°)
      ;; Cyan has high green and blue, low red
      (is (< (point-r p2) 0.1)
          (str "Point at x=1.0 should have low red (cyan), got r=" (point-r p2))))))

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
      (let [frame (t/make-frame [point-left point-right])
            effect-chain {:effects [{:effect-id :set-color
                                     :enabled? true
                                     :params {:red {:type :pos-x :min 0.0 :max 1.0}
                                              :green 0.0
                                              :blue 0.0}}]}]
        
        ;; Step 3: Create effect with pos-x modulator on red
        ;; min=0.0, max=1.0 means: at x=-1 -> red=0.0, at x=+1 -> red=1.0 
        
        
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
        (let [result-frame (effects/apply-effect-chain frame effect-chain {:time-ms 0 :bpm 120.0})
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
              "Left and right points should have different red values"))))))

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
      (let [ctx (mod/make-context {:time-ms 0 :bpm 120 :x 1.0 :y 0.0})
            result (mod/evaluate-modulator modulator ctx)]
        (is (< 65 result 80)
            (str "radial at (1,0) should return ~71, got " result))))))

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
            result (effects/apply-effect-chain frame effect-chain {:time-ms 0 :bpm 120.0})]
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
            result (effects/apply-effect-chain frame effect-chain {:time-ms 0 :bpm 120.0})]
        (println "\n--- radial test ---")
        (println "Center:" (point-b (second result)))
        (println "Corner:" (point-b (nth result 2)))
        (is (< (point-b (second result)) 0.1) "Center point should have low blue (near origin)")
        (is (> (point-b (nth result 2)) 0.9) "Corner point should have high blue (far from origin)")))))

(deftest blackout-effect-test
  (let [frame (make-test-frame [-0.5 0.0 0.5])]
    (testing "blackout with :enabled true zeroes all RGB and keeps positions"
      (let [result (effects/apply-effect-chain
                    frame {:effects [{:effect-id :blackout :enabled? true
                                      :params {:enabled true}}]})]
        (is (every? #(= [0.0 0.0 0.0] [(point-r %) (point-g %) (point-b %)]) result))
        (is (= (mapv point-x frame) (mapv point-x result)))))
    (testing "blackout with default params (:enabled false) passes points through"
      (let [result (effects/apply-effect-chain
                    frame {:effects [{:effect-id :blackout :enabled? true :params {}}]})]
        (is (= frame result))))))


;; Effect errors (output runtime Phase 2, O2): a failing effect skips only itself

(def ^:private test-effects
  [{:id :test-shift-x :name "Test: shift x" :category :shape :timing :static :parameters []
    :apply-transducer (fn [_ _ _ _] (map (fn [pt] (t/update-point-xy pt (+ (pt t/X) 0.25) (pt t/Y)))))}
   {:id :test-halve-red :name "Test: halve red" :category :color :timing :static :parameters []
    :apply-transducer (fn [_ _ _ _] (map (fn [pt] (assoc pt t/R (* 0.5 (pt t/R))))))}
   {:id :test-fails-per-point :name "Test: fails per point" :category :shape :timing :static :parameters []
    :apply-transducer (fn [_ _ _ _] (map (fn [_] (throw (AssertionError. "test: per point")))))}
   {:id :test-fails-to-build :name "Test: fails to build" :category :shape :timing :static :parameters []
    :apply-transducer (fn [_ _ _ _] (throw (ex-info "test: bad params" {})))}])

(defn- call-with-test-effects
  "Call f with test-effects registered, then leave the registry as it was, so
   tests that walk every registered effect never see them."
  [f]
  (run! effects/register-effect! test-effects)
  (oe/clear!)
  (try
    (f)
    (finally
      (oe/clear!)
      (swap! effects/!effect-registry #(apply dissoc % (map :id test-effects))))))

(defmacro ^:private with-test-effects [& body]
  `(call-with-test-effects (fn [] ~@body)))

(defn- instance [effect-id]
  {:id (keyword (str (name effect-id) "-1")) :effect-id effect-id :enabled? true :params {}})

(def ^:private error-frame
  [[-0.5 0.0 0.0 0.0 0.0] [0.0 0.0 1.0 1.0 1.0] [0.5 0.5 0.8 0.2 0.4]])

(def ^:private a-then-c
  "error-frame with :test-shift-x then :test-halve-red applied"
  [[-0.25 0.0 0.0 0.0 0.0] [0.25 0.0 0.5 1.0 1.0] [0.75 0.5 0.4 0.2 0.4]])

(defn- error-log-entries []
  (filterv #(#{:warn :error} (:level %)) (log-test/the-log)))

(deftest failing-effect-skips-only-itself-test
  (with-test-effects
    (log-test/with-log
      (let [chain {:effects [(instance :test-shift-x) (instance :test-fails-per-point) (instance :test-halve-red)]}]
        (dotimes [_ 5]
          (is (= a-then-c (effects/apply-effect-chain error-frame chain))
              "a and c are applied, b is skipped"))
        (testing "inside a group"
          (is (= a-then-c (effects/apply-effect-chain
                           error-frame
                           {:effects [(instance :test-shift-x)
                                      {:type :group :id "g" :enabled? true
                                       :items [(instance :test-fails-per-point) (instance :test-halve-red)]}]}))))
        (is (= 6 (get-in (oe/snapshot) [[:effect :test-fails-per-point-1] :count])))
        (is (= 1 (count (error-log-entries))) "logged once")))))

(deftest effect-that-fails-to-build-is-skipped-test
  (with-test-effects
    (log-test/with-log
      (let [chain {:effects [(instance :test-shift-x) (instance :test-fails-to-build) (instance :test-halve-red)]}]
        (dotimes [_ 5]
          (is (= a-then-c (effects/apply-effect-chain error-frame chain))))
        (is (= 5 (get-in (oe/snapshot) [[:effect :test-fails-to-build-1] :count])))
        (is (= 1 (count (error-log-entries))) "logged once, not every frame")))))

(deftest chain-without-errors-is-unchanged-test
  (with-test-effects
    (let [frame (make-test-frame [-0.8 -0.3 0.0 0.4 0.9])
          chain {:effects [{:id :e1 :effect-id :set-hue :enabled? true
                            :params {:hue {:type :pos-x :min 0.0 :max 360.0}}}
                           {:type :group :id "g" :enabled? true
                            :items [{:id :e2 :effect-id :hue-shift :enabled? true :params {:degrees 90.0}}
                                    {:id :e3 :effect-id :test-fails-per-point :enabled? false :params {}}]}
                           {:id :e4 :effect-id :test-shift-x :enabled? true :params {}}
                           {:id :e5 :effect-id :test-halve-red :enabled? true :params {}}]}
          timing-ctx {:time-ms 250 :bpm 120.0}
          frame-ctx {:point-count (count frame) :timing-ctx timing-ctx}
          composed (into [] (effects/compose-effect-transducers chain frame-ctx) frame)
          one-by-one (resolve 'laser-show.animation.effects/apply-effects-one-by-one)]
      (is (= composed (effects/apply-effect-chain frame chain timing-ctx))
          "the fast path is today's composed single pass")
      (is (some? one-by-one) "the per-effect fallback exists")
      (when one-by-one
        (is (= composed (one-by-one frame chain frame-ctx))
            "applying the effects one at a time gives the same frame")))))


;; One timing context down the chain (plans/2026-11-17-show-clock/04)

(defn- sine [lo hi period] {:type :sine :min lo :max hi :period period})

(def ^:private half-a-beat-in
  "A cue's timing context (cue-timing/get-cue-timing-context) half a beat in,
   at 120 BPM."
  {:accumulated-beats 0.5 :accumulated-ms 250.0 :phase-offset 0.0 :effective-beats 0.5
   :time-ms 250.0 :bpm 120.0})

(def ^:private three-modulated-params
  {:effects [{:id :hue :effect-id :hue-shift :enabled? true :params {:degrees (sine 0.0 180.0 2.0)}}
             {:id :filter :effect-id :color-filter :enabled? true
              :params {:r-mult (sine 0.2 1.0 1.0) :g-mult (sine 0.5 1.0 4.0) :b-mult 1.0}}]})

(defn- contexts-built
  "{:make-context n :make-base-context n}: the modulation contexts (f) builds."
  [f]
  (let [calls (atom {:make-context 0 :make-base-context 0})
        make-context mod/make-context
        make-base-context mod/make-base-context]
    (with-redefs [mod/make-context (fn [m] (swap! calls update :make-context inc) (make-context m))
                  mod/make-base-context (fn [m] (swap! calls update :make-base-context inc) (make-base-context m))]
      (f))
    @calls))

(deftest one-context-per-chain-test
  (let [frame (make-test-frame [-0.5 0.0 0.5])]
    (testing "three modulated parameters share the chain's one context (04 §7, invariant 3)"
      (is (= {:make-context 1 :make-base-context 0}
             (contexts-built #(effects/apply-effect-chain frame three-modulated-params half-a-beat-in)))))
    (testing "per-point parameters without a compiled form share one base context"
      (with-redefs [mod/compile-per-point-modulator (constantly nil)]
        (is (= {:make-context 0 :make-base-context 1}
               (contexts-built
                #(effects/apply-effect-chain
                  frame
                  {:effects [{:id :hue :effect-id :set-hue :enabled? true
                              :params {:hue {:type :pos-x :min 0.0 :max 360.0}}}
                             {:id :filter :effect-id :color-filter :enabled? true
                              :params {:r-mult {:type :pos-y :min 0.0 :max 1.0} :g-mult 1.0 :b-mult 1.0}}]}
                  half-a-beat-in))))))
    (testing "a chain whose parameters are all static builds none, also through resolve-params-global (blackout)"
      (is (= {:make-context 0 :make-base-context 0}
             (contexts-built
              #(effects/apply-effect-chain
                frame
                {:effects [{:id :hue :effect-id :hue-shift :enabled? true :params {:degrees 90.0}}
                           {:id :off :effect-id :blackout :enabled? true :params {:enabled false}}]}
                half-a-beat-in)))))))

(defn- each-param-its-own-context
  "frame through chain's effects, each run by its own transducer with a frame
   context that carries no shared modulation context, so every parameter's
   resolver builds its own from the bare timing context (the chain before 04)."
  [frame chain timing-ctx]
  (reduce (fn [frame {:keys [effect-id params]}]
            (into [] ((:apply-transducer (effects/get-effect effect-id))
                      (:time-ms timing-ctx) (:bpm timing-ctx)
                      (effects/merge-with-defaults effect-id params)
                      {:point-count (count frame) :timing-ctx timing-ctx})
                  frame))
          frame
          (:effects chain)))

(deftest chain-takes-a-timing-context-test
  (let [frame [[-0.5 0.0 1.0 0.2 0.0] [0.0 0.0 0.0 1.0 0.3] [0.5 0.0 0.4 0.1 0.9]]
        at (fn [timing-ctx] (effects/apply-effect-chain frame three-modulated-params timing-ctx))]
    (testing "the chain's one context gives the frame a context per parameter gives"
      (is (= (each-param-its-own-context frame three-modulated-params half-a-beat-in)
             (at half-a-beat-in)))
      (is (not= (at half-a-beat-in) (at (assoc half-a-beat-in :accumulated-beats 0.75 :effective-beats 0.75)))
          "a quarter beat later, another frame: the modulators read the context's time"))
    (testing "the effects get the context's time and tempo: with no accumulators, trace fade
              at 250 ms and 120 BPM is half a beat in"
      (let [fade {:effects [{:id :fade :effect-id :trace-fade :enabled? true
                             :params {:period 1.0 :tail-length 0.5}}]}]
        (is (= (each-param-its-own-context frame fade half-a-beat-in)
               (effects/apply-effect-chain frame fade {:time-ms 250.0 :bpm 120.0})))))
    (testing "without a timing context, a static chain still applies"
      (let [shift {:effects [{:id :hue :effect-id :hue-shift :enabled? true :params {:degrees 90.0}}]}]
        (is (= (each-param-its-own-context frame shift nil)
               (effects/apply-effect-chain frame shift)))
        (is (not= frame (effects/apply-effect-chain frame shift)))))))


;; A zone-stage effect's parameters (zones plan Z5, 07 §4.3): resolved once
;; per tick, keyframes or params, the defaults under them, and a choice may
;; follow a modulator by index

(deftest resolve-effect-params-keyframes-then-params-test
  (let [pattern {:effect-id :zone-pattern :enabled? true
                 :params {:style :chase :zones [:lane/lasers] :offset (sine 0.0 2.0 4.0)}}
        at (fn [instance timing-ctx] (effects/resolve-effect-params instance timing-ctx))]
    (testing "the params, the defaults under them, modulators read at the context's time"
      (let [p (at pattern half-a-beat-in)]
        (is (= :chase (:style p)))
        (is (= [:lane/lasers] (:zones p)))
        (is (= 1.0 (:rate p)) "a default")
        (is (= :all (:from p)) "a default")
        (is (number? (:offset p)) "the sine, resolved")
        (is (= (mod/resolve-param (sine 0.0 2.0 4.0) (mod/make-context half-a-beat-in)) (:offset p)))))
    (testing "an enabled keyframe animation wins over the params"
      (let [keyed (assoc pattern :keyframe-modulator
                         {:enabled? true :period 4.0 :time-unit :beats
                          :keyframes [{:position 0.0 :params {:style :shift :offset 0.0 :zones [:zone/a :zone/b]} :interpolation :linear}
                                      {:position 1.0 :params {:style :swap :offset 4.0 :zones [:zone/a :zone/b]} :interpolation :linear}]})
            p (at keyed {:accumulated-beats 2.0 :accumulated-ms 1000.0 :phase-offset 0.0 :effective-beats 2.0 :time-ms 1000.0 :bpm 120.0})]
        (is (= :shift (:style p)) "a choice holds the earlier keyframe's")
        (is (= [:zone/a :zone/b] (:zones p)))
        (is (< 1.9 (double (:offset p)) 2.1) "numbers between keyframes interpolate: half way")
        (is (= 1.0 (:rate p)) "what no keyframe sets is the default")
        (is (= (:offset (at pattern half-a-beat-in))
               (:offset (at (assoc-in keyed [:keyframe-modulator :enabled?] false) half-a-beat-in)))
            "a disabled one is ignored")))
    (testing "an unknown effect: its params as they are"
      (is (= {:x 1} (at {:effect-id :nope :params {:x 1}} half-a-beat-in))))))

(deftest modulated-choice-picks-by-index-test
  (testing "choice-at: the index ⌊v + 1e-9⌋, clamped"
    (let [choices [:alternate :chase :ping-pong :random :shift :swap]]
      (is (= [:alternate :chase :ping-pong :random] (mapv #(effects/choice-at choices %) [0.0 1.2 2.5 2.9999999999])))
      (is (= [:alternate :swap :swap] (mapv #(effects/choice-at choices %) [-3.0 5.0 99.0])) "clamped to the list")
      (is (nil? (effects/choice-at [] 1.0)))))
  (testing "a Step on Style following HIT moves to the next style on each press (07 §8.3)"
    (let [step {:type :step :active? true :source :hit :min 0.0 :max 5.0 :steps 6 :order :up :wrap? false :glide 0.0 :values []}
          pattern {:effect-id :zone-pattern :enabled? true :params {:style step}}
          after (fn [n] (:style (effects/resolve-effect-params pattern (assoc half-a-beat-in :pulses (when (pos? n) {:hit {:count n :beats 0.1}})))))]
      (is (= [:alternate :chase :ping-pong :random :shift :swap :alternate] (mapv after [0 1 2 3 4 5 6])))))
  (testing "Moves and Path follow one too; a choice that doesn't say so keeps its value"
    (let [p (effects/resolve-effect-params {:effect-id :zone-pattern
                                            :params {:source {:type :sine :active? false :value 1.0}
                                                     :path {:type :sine :active? false :value 1.0}
                                                     :step-on :hit}}
                                           half-a-beat-in)]
      (is (= :picture (:source p)))
      (is (= :bounce (:path p)))
      (is (= :hit (:step-on p)))))
  (testing "a static choice is its own value"
    (is (= :swap (:style (effects/resolve-effect-params {:effect-id :zone-pattern :params {:style :swap}} half-a-beat-in))))))

(deftest zone-stage-effects-register-test
  (let [pattern (effects/get-effect :zone-pattern)
        cycle (effects/get-effect :zone-map-cycle)
        frame [[0.1 0.2 1.0 0.5 0.0] [0.3 0.4 0.0 1.0 1.0]]]
    (is (= "Zone pattern" (:name pattern)))
    (is (= "Map cycle" (:name cycle)))
    (doseq [fx [pattern cycle]]
      (testing (:name fx)
        (is (= :zone (:category fx)))
        (is (= :bpm (:timing fx)))
        (is (= #{:global} (:scope fx)) "offered on the effects grid only")
        (is (not (:hidden? fx)))
        (is (effects/zone-stage-effect? fx))
        (is (= frame (into [] ((:apply-transducer fx) 0.0 120.0 {} {:point-count 2}) frame))
            "as a point effect it is identity: nothing reads it there")))
    (is (= zone-patterns/pattern-parameters (:parameters pattern)))
    (is (= zone-patterns/cycle-parameters (:parameters cycle)))
    (is (= zone-remap/apply-pattern (:apply-zones pattern)) "the stage runs it")
    (is (= zone-patterns/cycle-pick (:select-map cycle)) "the show clock picks the map with it")
    (is (= {:renderer :zone-pattern} (:ui-hints pattern)))
    (is (= {:renderer :map-cycle} (:ui-hints cycle)))
    (is (not (effects/zone-stage-effect? (effects/get-effect :scale))))
    (is (not (effects/zone-stage-effect? (effects/get-effect :zone-selector))) "the hidden selector routes nothing")
    (is (not (effects/zone-stage-effect? nil)))))
