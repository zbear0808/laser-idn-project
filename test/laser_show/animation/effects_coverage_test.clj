(ns laser-show.animation.effects-coverage-test
  "Table-driven coverage over every registered effect.

   For each effect: running it with its default params must not throw, must
   keep the point count and produce finite values. For each declared param:
   changing it away from its default must change the output for at least one
   candidate value. This catches param keys an effect never reads (e.g. the
   blackout :enabled / :enabled? mismatch)."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.calibration]
            [laser-show.animation.effects.color]
            [laser-show.animation.effects.intensity]
            [laser-show.animation.effects.shape]
            [laser-show.animation.effects.zone]
            [laser-show.animation.types :as t]))


(def ^:private param-exemptions
  "Params that legitimately don't change frame output. Keep each one explained."
  {;; Routing-only: read by the zone routing layer, the frame transducer is identity.
   [:zone-selector :target-zone] "routing param, not a frame transform"
   ;; Editor-only constraint: the scale editor uses it to keep x/y scale locked
   ;; together and writes both :x-scale and :y-scale. Applying it in scale-xf
   ;; (forcing y = x) would break uniform mirroring (e.g. x -1.2, y 1.2).
   [:scale :uniform?] "UI aspect-lock flag, scale-xf reads :x-scale/:y-scale only"})

(def ^:private expected-effect-ids
  "Guard so this test fails loudly if an effect namespace stops loading."
  #{:scale :translate :rotation :corner-pin :lens-distortion
    :hue-shift :saturation :color-filter :set-hue :set-color :oklab-hue-shift
    :intensity :blackout :threshold :trace-fade
    :rgb-curves :axis-transform
    :zone-selector})

(def ^:private test-frame
  "Small frame: varied positions well inside [-1, 1] and saturated, non-white
   colours (one dim point) so shape, colour and threshold changes are visible."
  (t/make-frame
   [(t/make-point 0.3 0.2 1.0 0.2 0.0)
    (t/make-point -0.4 0.5 0.1 0.8 0.3)
    (t/make-point 0.5 -0.3 0.0 0.4 0.9)
    (t/make-point -0.2 -0.6 0.7 0.7 0.1)
    (t/make-point 0.1 0.4 0.5 0.0 0.6)
    (t/make-point -0.5 -0.1 0.02 0.03 0.01)]))

(def ^:private time-ms 250.0)
(def ^:private bpm 120.0)

(defn- run-effect
  "Run an effect's transducer directly (not via make-effect-transducer, which
   swallows exceptions) with params merged over the defaults."
  [effect-id params]
  (let [{:keys [apply-transducer]} (effects/get-effect effect-id)
        merged (effects/merge-with-defaults effect-id params)
        ctx {:point-count (count test-frame) :timing-ctx nil}]
    (into [] (apply-transducer time-ms bpm merged ctx) test-frame)))

(defn- candidate-values
  "Values to try for a param, other than its default. nil = no strategy."
  [{:keys [type default min max choices]}]
  (case type
    (:float :int) (when (and min max)
                    (let [mid (/ (+ (double min) (double max)) 2.0)
                          vs (cond-> [min max mid]
                               (= type :int) (->> (map long)))]
                      (remove #(== (double %) (double default)) vs)))
    (:bool :boolean) [(not default)]
    :choice (remove #{default} choices)
    :curve-points [[[0.0 0.0] [0.5 0.2] [1.0 1.0]]]
    nil))

(defn- finite-point? [pt]
  (every? #(and (number? %) (Double/isFinite (double %))) pt))


(deftest all-expected-effects-registered-test
  (is (every? (set (map :id (effects/list-effects))) expected-effect-ids)))

(deftest every-effect-runs-with-defaults-test
  (doseq [{:keys [id]} (effects/list-effects)]
    (testing (str id)
      (let [out (run-effect id {})]
        (is (= (count test-frame) (count out)))
        (is (every? finite-point? out))))))

(deftest every-param-changes-output-test
  (doseq [{effect-id :id :keys [parameters]} (effects/list-effects)
          {param-key :key :as param-def} parameters
          :when (not (contains? param-exemptions [effect-id param-key]))]
    (testing (str effect-id " " param-key)
      (let [candidates (candidate-values param-def)
            baseline (run-effect effect-id {})]
        (is (seq candidates)
            (str "no variation strategy for param type " (:type param-def)
                 "; extend candidate-values or add an exemption"))
        (is (some #(not= baseline (run-effect effect-id {param-key %})) candidates)
            (str "varying " param-key " over " (vec candidates)
                 " never changed the output: the effect probably doesn't read it"))))))

(deftest exemptions-are-current-test
  (testing "every exemption names a registered effect param"
    (doseq [[effect-id param-key] (keys param-exemptions)]
      (is (some #(= param-key (:key %)) (:parameters (effects/get-effect effect-id)))
          (str [effect-id param-key] " is exempt but no longer declared")))))
