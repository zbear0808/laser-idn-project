(ns laser-show.state.templates-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.show-time :as show-time]
            [laser-show.services.frame-service :as fs]
            [laser-show.state.domains :as domains]
            [laser-show.state.templates :as templates]
            [laser-show.vibes.faders :as faders]
            [laser-show.vibes.library :as library]))

(deftest starter-vibes-name-starter-cues-test
  (let [cues (templates/create-starter-cue-chains)
        {:keys [order next-id settings] :as vibes} (templates/create-starter-vibes)
        named (fn [vibe] (concat (get-in vibe [:hit :cells]) (get-in vibe [:accent :cells])
                                 [(get-in vibe [:flash :cue])] (some-> (:bed vibe) vector)))
        state (-> (domains/build-initial-state)
                  (templates/apply-starter-cue-chains)
                  (templates/apply-starter-vibes))]
    (is (= [:vibe-1 :vibe-2] order))
    (is (= (set order) (set (keys (:library vibes)))))
    (is (every? #(= % (get-in vibes [:library % :id])) order))
    (is (every? #(< (parse-long (subs (name %) 5)) next-id) order) "a new vibe's id is new")
    (is (= (:settings library/empty-library) settings))
    (doseq [vibe (vals (:library vibes))
            cell (named vibe)]
      (is (seq (get-in cues [cell :items])) (str (:name vibe) " names " cell ", a starter cue")))
    (testing "every cell they name can play"
      (doseq [vibe (vals (:library vibes))
              :let [cells (named vibe)]]
        (is (= (vec (distinct cells)) (library/playable-cells state vibe cells)) (:name vibe))))
    (testing "applied only while there are no vibes, keeping the settings"
      (let [mine (assoc-in (domains/build-initial-state) [:config :vibes :library] {:mine {:id :mine}})]
        (is (identical? mine (templates/apply-starter-vibes mine))))
      (is (= 99 (get-in (templates/apply-starter-vibes
                         (assoc-in (domains/build-initial-state) [:config :vibes :settings :quick-tap-ms] 99))
                        [:config :vibes :settings :quick-tap-ms]))))))


;; The faders' cells (plans/2026-11-18-vibe-mode/05 §7): neutral until a
;; fader moves

(def ^:private master-fader
  {:type :macro :active? true :slot :slider-1 :min 0.0 :max 1.0 :default 1.0})

(deftest starter-effects-test
  (let [fresh (templates/apply-starter-effects (domains/build-initial-state))
        master (get-in fresh [:chains :effect-chains [0 3]])]
    (is (= [[0 3]] (keys (get-in fresh [:chains :effect-chains]))) "one cell, at [0 3]")
    (is (= ["Master" true] ((juxt :name :active?) master)) "on")
    (is (= [{:effect-id :intensity :enabled? true :params {:amount master-fader}}]
           (mapv #(dissoc % :id) (:items master))))
    (is (uuid? (get-in master [:items 0 :id])))
    (is (= [0 3] (faders/master-at fresh)))
    (testing "applied only while the effects grid is empty: a project's own cells stay as they are"
      (let [mine (assoc-in (domains/build-initial-state) [:chains :effect-chains [2 2]] {:items []})]
        (is (identical? mine (templates/apply-starter-effects mine)))))))

(def ^:private cues
  "The starter cues, in a colour (white would hide a hue shift)."
  (update-vals (templates/create-starter-cue-chains)
               (fn [cue] (update cue :items (partial mapv #(update % :params merge {:red 1.0 :green 0.4 :blue 0.1}))))))

(def ^:private ctx
  "A cue 4 beats into its run, at 120 BPM, nothing pressed."
  {:accumulated-beats 4.0 :effective-beats 4.0 :phase-offset 0.0 :accumulated-ms 2000.0 :time-ms 2000.0 :bpm 120.0})

(defn- render
  "The starter cues' frames by zone, through the effects of cell as the
   effects grid's active chain (none without), with input-values the input
   store's values."
  [cell input-values]
  (let [c (assoc ctx :input-values input-values)]
    (into {}
          (for [[pos cue] cues]
            [pos (fs/generate-frames-by-zone cue c {:global-effects (when cell {:effects (:items cell)})
                                                    :global-ctx c})]))))

(defn- max-diff
  "The largest difference between two renders, point by point, over x y r g b."
  [a b]
  (apply max 0.0 (for [[pos zones] a
                       [zone frame] zones
                       [p q] (map vector frame (get-in b [pos zone]))
                       i (range 5)]
                   (Math/abs (- (double (p i)) (double (q i)))))))

(defn- scaled
  "A render with every point's colour times k (rgb?), or its x and y times k
   (kept within -1..1, as the effects keep them)."
  [r k & {:keys [rgb?]}]
  (let [clamp #(max -1.0 (min 1.0 (double %)))]
    (update-vals r (fn [zones]
                     (update-vals zones (fn [frame]
                                          (mapv (fn [[x y cr cg cb]]
                                                  (if rgb?
                                                    [x y (* k cr) (* k cg) (* k cb)]
                                                    [(clamp (* k x)) (clamp (* k y)) cr cg cb]))
                                                frame)))))))

(deftest master-cell-is-identity-until-the-fader-moves-test
  (testing "invariant 2: the Master on and its fader never moved: the frames as they were"
    (let [plain (render nil {})
          mastered (render (faders/master-cell) {})]
      (is (seq (mapcat vals (vals plain))) "the starter cues draw something")
      (is (< (max-diff plain mastered) 1e-9))
      (is (= plain mastered) "exactly: an Intensity of 1.0 multiplies by one")
      (is (= plain (render (faders/master-cell) {[:midi 0 1] 0.2 [:macro :slider-2] 0.0}))
          "nor does a control's own key, or the other fader")))
  (testing "through the render context the show clock makes from a new project's state"
    (let [base (-> (domains/build-initial-state) (templates/apply-starter-cue-chains))
          frames (fn [state]
                   (let [rctx (fs/render-context state {} (show-time/global-clock))]
                     [(get-in rctx [:global-effects :effects])
                      (into {} (for [[pos cue] (get-in state [:chains :cue-chains])]
                                 [pos (fs/generate-frames-by-zone cue ctx rctx)]))]))
          [none plain] (frames base)
          [effects mastered] (frames (templates/apply-starter-effects base))]
      (is (nil? none))
      (is (= [:intensity] (mapv :effect-id effects)) "the Master is on")
      (is (< (max-diff plain mastered) 1e-9)))))

(deftest master-cell-dims-with-the-fader-test
  (let [plain (render nil {})
        master (faders/master-cell)
        at (fn [v] (render master {[:macro :slider-1] v}))]
    (is (< (max-diff (scaled plain 0.25 :rgb? true) (at 0.25)) 1e-9) "a quarter of the way up: a quarter as bright")
    (is (< (max-diff plain (at 1.0)) 1e-9) "all the way up: full")
    (testing "invariant 3: at 0 the show is dark"
      (is (every? zero? (for [[_ zones] (at 0.0) [_ frame] zones [_ _ r g b] frame v [r g b]] v))))))

(deftest morph-templates-are-neutral-until-moved-test
  (let [plain (render nil {})]
    (is (= [:size :spin :colour] (mapv :kind faders/morph-templates)))
    (testing "within 1e-9 of the frames without them: Colour's Hue Shift goes through HSV (probe v17)"
      (doseq [{:keys [kind]} faders/morph-templates]
        (is (< (max-diff plain (render (faders/morph-cell kind nil) {})) 1e-9) (name kind))))
    (testing "and they move with fader 2, not fader 1"
      (let [at (fn [kind slot v] (render (faders/morph-cell kind nil) {[:macro slot] v}))]
        (is (< (max-diff (scaled plain 1.5) (at :size :slider-2 1.0)) 1e-9) "Size: 1.5x at the top")
        (is (< (max-diff (scaled plain 0.5) (at :size :slider-2 0.0)) 1e-9) "half at the bottom")
        (is (< 0.1 (max-diff plain (at :spin :slider-2 0.25))) "Spin: a quarter turn")
        (is (< 0.1 (max-diff plain (at :colour :slider-2 1.0))) "Colour: half way round")
        (is (< (max-diff plain (at :size :slider-1 1.0)) 1e-9) "fader 1 is the Master's")))))
