(ns laser-show.animation.effect-presets-test
  "Validates the shipped resources/effect-presets/*.edn files against the
   effect and modulator registries.

   Nothing loads these files at runtime yet; this keeps them valid until a
   loader is added."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.calibration]
            [laser-show.animation.effects.color]
            [laser-show.animation.effects.intensity]
            [laser-show.animation.effects.shape]
            [laser-show.animation.effects.zone]
            [laser-show.animation.modulator-registry :as mod-reg]
            [laser-show.animation.modulators]
            [laser-show.animation.types :as t]))


(defn- preset-files []
  (->> (file-seq (io/file (io/resource "effect-presets")))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
       (sort-by #(.getName ^java.io.File %))))

(defn- load-preset [f]
  (edn/read-string (slurp f)))

(def ^:private modulator-meta-keys
  "Keys a modulator config may carry besides its registered :params."
  #{:type :active? :value})

(defn- modulator-config? [v]
  (and (map? v) (keyword? (:type v))))

(defn- choice-ok? [param-def v]
  (or (not= :choice (:type param-def))
      (some #{v} (:choices param-def))))

(defn- modulator-problems [m]
  (let [mod-def (mod-reg/get-modulator (:type m))
        param-defs (into {} (map (juxt :key identity)) (:params mod-def))]
    (cond
      (nil? mod-def) [(str "unknown modulator type " (:type m))]
      (= :internal (:category mod-def)) [(str "internal modulator type " (:type m))]
      :else (concat
             (for [k (keys m)
                   :when (not (or (contains? param-defs k) (modulator-meta-keys k)))]
               (str "modulator " (:type m) " has unknown key " k))
             (for [[k v] m
                   :let [pd (param-defs k)]
                   :when (and pd (not (choice-ok? pd v)))]
               (str "modulator " (:type m) " " k " = " v " not in " (:choices pd)))))))

(defn- effect-problems [{:keys [effect-id params]}]
  (if-let [effect-def (effects/get-effect effect-id)]
    (let [param-defs (into {} (map (juxt :key identity)) (:parameters effect-def))]
      (mapcat (fn [[k v]]
                (let [pd (param-defs k)]
                  (cond
                    (nil? pd) [(str effect-id " has unknown param " k)]
                    (modulator-config? v) (modulator-problems v)
                    (not (choice-ok? pd v)) [(str effect-id " " k " = " v " not in " (:choices pd))]
                    :else [])))
              params))
    [(str "unknown effect-id " effect-id)]))

(def ^:private test-frame
  (t/make-frame
   [(t/make-point 0.3 0.2 1.0 0.2 0.0)
    (t/make-point -0.4 0.5 0.1 0.8 0.3)
    (t/make-point 0.5 -0.3 0.0 0.4 0.9)
    (t/make-point -0.2 -0.6 0.7 0.7 0.1)]))


(deftest presets-exist-test
  (is (>= (count (preset-files)) 8)))

(deftest presets-reference-registered-effects-and-params-test
  (doseq [f (preset-files)]
    (testing (.getName ^java.io.File f)
      (let [{:keys [name effects] :as preset} (load-preset f)]
        (is (map? preset))
        (is (string? name))
        (is (and (vector? effects) (seq effects)))
        (is (empty? (mapcat effect-problems effects)))))))

(deftest presets-change-the-frame-test
  (testing "each preset chain visibly changes a frame at some point in a 4-beat window"
    (doseq [f (preset-files)]
      (testing (.getName ^java.io.File f)
        (let [chain {:effects (:effects (load-preset f))}
              ;; 37ms steps avoid aliasing with beat-fraction periods (e.g. strobe)
              outputs (for [ms (range 0 2000 37)]
                        (effects/apply-effect-chain test-frame chain ms 120.0 0
                                                    {:accumulated-ms (double ms)
                                                     :accumulated-beats (/ ms 500.0)
                                                     :effective-beats (/ ms 500.0)
                                                     :phase-offset 0.0}))]
          (is (every? #(= (count test-frame) (count %)) outputs))
          (is (some #(not= test-frame %) outputs) "preset never changed the frame"))))))
