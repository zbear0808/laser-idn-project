(ns laser-show.vibes.reactions-test
  "laser-show.vibes.reactions: the tap reaction templates, where a new one
   goes, and which cells react to which role
   (plans/2026-11-18-vibe-mode/04-phase4-tap-reactions.md §7)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.modulator-registry :as reg]
            [laser-show.animation.show-time :as show-time]
            [laser-show.events.core :as events]
            [laser-show.services.frame-service :as fs]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.templates :as templates]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.vibes.reactions :as reactions]))

(defn- starter []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-vibes)
      (assoc-in [:timing :bpm] 120.0)))

(use-fixtures :each (fn [f]
                      (try (f)
                           (finally (show-time/reset-runtime!)))))

(def ^:private ctx
  "A cue 4 beats into its run, at 120 BPM, nothing pressed."
  {:accumulated-beats 4.0 :effective-beats 4.0 :phase-offset 0.0 :accumulated-ms 2000.0 :time-ms 2000.0 :bpm 120.0})

(defn- press [source beats]
  {source {:count 1 :beats beats :ms (* 500.0 beats) :held? false :since-release beats :recent [beats]}})

(defn- render
  "The starter cues' frames by zone, through the effects of cell as the
   effects grid's active chain (none without), at timing context c."
  ([cell] (render cell ctx))
  ([cell c]
   (into {}
         (for [[pos cue] (get-in (starter) [:chains :cue-chains])]
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
  "A render with every point's x and y times k (kept within -1..1, as the
   effects keep them), or its colour times k (rgb?)."
  [r k & {:keys [rgb?]}]
  (let [clamp #(max -1.0 (min 1.0 (double %)))]
    (update-vals r (fn [zones]
                     (update-vals zones (fn [frame]
                                          (mapv (fn [[x y cr cg cb]]
                                                  (if rgb?
                                                    [x y (* k cr) (* k cg) (* k cb)]
                                                    [(clamp (* k x)) (clamp (* k y)) cr cg cb]))
                                                frame)))))))


;; The templates

(deftest every-template-is-valid-test
  (is (= [:punch-size :pump :step-colour :step-spin :flip :scatter :swell :strobe :fade-in :energy-size]
         (mapv :kind reactions/templates)))
  (is (= {:hit 4 :accent 2 :flash 2 :vibe 1 :any 1} (frequencies (map :role reactions/templates))))
  (doseq [{:keys [kind menu effects]} reactions/templates]
    (testing (name kind)
      (is (seq menu))
      (doseq [{:keys [effect-id params]} effects
              :let [effect (effects/get-effect effect-id)]]
        (is (some? effect) (str effect-id " is a registered effect"))
        (doseq [[k v] params
                :let [spec (first (filter #(= k (:key %)) (:parameters effect)))]]
          (is (some? spec) (str effect-id " has a param " k))
          (when (map? v)
            (is (reg/valid-modulator-type? (:type v)) (str (:type v) " is registered"))
            (doseq [bound [:min :max]]
              (is (<= (:min spec) (double (get v bound)) (:max spec))
                  (str kind " " k " " bound " within the effect's range")))
            (doseq [{pk :key lo :min hi :max ptype :type choices :choices} (reg/get-params (:type v))
                    :let [pv (get v pk)]
                    :when (and (some? pv) (not (#{:min :max} pk)))]
              (case ptype
                (:float :int) (is (<= lo (double pv) hi) (str kind " " pk " " pv " within " lo "-" hi))
                :choice (is (some #{pv} choices) (str kind " " pk " " pv " is a choice"))
                nil))))))))

(deftest templates-are-identity-at-rest-test
  (testing "invariant 11: until its source's first press each template leaves the frames as they are, to within 1e-9"
    (let [plain (render nil)]
      (doseq [{:keys [kind into]} reactions/templates
              :when (not= :flash-fx into)
              :let [r (render (reactions/reaction-cell kind nil))]]
        (if (= :pump kind)
          (is (< (max-diff (scaled plain 0.35 :rgb? true) r) 1e-9) "Pump: 35 % between hits, by design")
          (is (< (max-diff plain r) 1e-9) (name kind)))))
    (testing "and the Strobe is only on while FLASH is held"
      (is (= :flash-fx (:into (reactions/template :strobe)))))))

(deftest punch-scales-then-returns-test
  (let [plain (render nil)
        punch (reactions/reaction-cell :punch-size nil)
        at (fn [beats] (render punch (assoc ctx :pulses (press :hit beats))))]
    (is (< (max-diff (scaled plain 1.25) (at 0.0)) 1e-9) "a quarter bigger at the press")
    (is (< (max-diff (scaled plain 1.01896) (at 0.25)) 1e-4) "nearly back a quarter of a beat later")
    (is (< (max-diff plain (at 0.5)) 1e-9) "back at half a beat")
    (is (< (max-diff plain (render punch (assoc ctx :pulses (press :accent 0.0)))) 1e-9) "ACCENT isn't HIT")))


;; Where a reaction goes

(deftest first-empty-effect-cell-test
  (let [s (starter)]
    (is (= [0 3] (reactions/first-empty-effect-cell s)) "the bottom row first, out of the way")
    (is (= [1 3] (reactions/first-empty-effect-cell (assoc-in s [:chains :effect-chains [0 3]] {:name "Master" :items []}))))
    (is (= [0 2] (reactions/first-empty-effect-cell
                  (update-in s [:chains :effect-chains] merge (into {} (for [c (range 8)] [[c 3] {:items []}]))))))
    (is (nil? (reactions/first-empty-effect-cell
               (update-in s [:chains :effect-chains] merge (into {} (for [c (range 8) r (range 4)] [[c r] {:items []}]))))))))

(deftest reaction-cell-test
  (is (= {:name "Groove · Punch size" :active? false
          :items [{:effect-id :scale :enabled? true
                   :params {:x-scale {:type :envelope :active? true :source :hit :min 1.0 :max 1.25
                                      :attack 0.0 :release 0.5 :curve :exp :hold? false}
                            :y-scale {:type :envelope :active? true :source :hit :min 1.0 :max 1.25
                                      :attack 0.0 :release 0.5 :curve :exp :hold? false}
                            :uniform? false}}]}
         (reactions/reaction-cell :punch-size "Groove")))
  (is (= "Punch size" (:name (reactions/reaction-cell :punch-size nil))))
  (is (nil? (reactions/reaction-cell :no-such-kind "Groove"))))

(deftest strobe-goes-into-flash-fx-test
  (let [s (reactions/add-to-vibe (starter) :vibe-1 :strobe)]
    (is (= [[0 3]] (get-in s [:config :vibes :library :vibe-1 :flash :fx])))
    (is (= [] (get-in s [:config :vibes :library :vibe-1 :fx])))
    (is (= "Shapes · Strobe" (get-in s [:chains :effect-chains [0 3] :name])))
    (is (false? (get-in s [:chains :effect-chains [0 3] :active?])) "off until FLASH is held")))


;; Fade in, through the show clock (invariant 7)

(defn- brightness [snapshot]
  (reduce + 0.0 (for [{:keys [zone-frames]} (:cues snapshot)
                      [_ frame] zone-frames
                      [_ _ r g b] frame]
                  (+ r g b))))

(deftest fade-in-starts-dark-and-returns-to-full-test
  (state/init-state! (assoc-in (starter) [:config :vibes :settings :hit-taps-tempo?] false))
  (show-time/reset-runtime!)
  (with-redefs [events/dispatch! (fn [_])]
    (events/event-handler {:event/type :vibe/add-reaction :vibe :vibe-2 :kind :fade-in})
    (events/event-handler {:event/type :vibe/select :vibe :vibe-1 :input-time 0})
    (events/event-handler {:event/type :grid/trigger-cell :col 0 :row 0})
    (let [ms 1000000
          full (brightness (last (mapv #(show-clock/tick! (* % 16 ms)) (range 3))))]
      (is (pos? full))
      (is (false? (get-in (state/get-raw-state) [:chains :effect-chains [0 3] :active?])) "vibe-2's, off in vibe-1")
      (events/event-handler {:event/type :vibe/press :role :vibe :input-time 100})
      (events/event-handler {:event/type :vibe/release :role :vibe :input-time 200})
      (is (= :vibe-2 (get-in (state/get-raw-state) [:vibe-runtime :current])))
      (is (true? (get-in (state/get-raw-state) [:chains :effect-chains [0 3] :active?])))
      (let [dip (show-clock/tick! (* 48 ms))
            half (show-clock/tick! (* 298 ms))
            back (show-clock/tick! (* 548 ms))]
        (is (< (brightness dip) 1e-9) "the first frame after the switch is dark: cell and pulse in the same tick")
        (is (< (Math/abs (- (* 0.5 full) (brightness half))) (* 1e-6 full)) "half a beat on: half")
        (is (< (Math/abs (- full (brightness back))) (* 1e-6 full)) "a beat on: full")))))


;; Which cells react to which role

(deftest reactions-index-test
  (let [env (fn [source] {:type :envelope :source source :min 1.0 :max 1.25})
        cell (fn [name & params] {:name name :active? false
                                  :items [{:effect-id :scale :enabled? true :params (apply hash-map params)}]})
        s (-> (starter)
              (assoc-in [:chains :effect-chains [0 3]] (cell "Shapes · Punch size" :x-scale (env :hit)))
              (assoc-in [:chains :effect-chains [1 3]] (cell "Shapes · Energy" :x-scale {:type :energy :min 1.0 :max 1.4}))
              (assoc-in [:chains :effect-chains [2 3]] (cell "Strobe" :x-scale {:type :square :restart-on :flash}))
              (assoc-in [:chains :effect-chains [3 3]] (assoc (cell "On the grid" :y-scale (env :accent)) :active? true))
              (assoc-in [:chains :effect-chains [4 3]] (assoc (cell "Off on the grid" :y-scale (env :accent)) :active? false))
              (assoc-in [:chains :effect-chains [5 3]] (cell "Cue start" :x-scale (env :cue)))
              (assoc-in [:chains :effect-chains [6 3]] (cell "Unplugged" :x-scale (assoc (env :hit) :active? false)))
              (update-in [:config :vibes :library :vibe-1] merge {:fx [[0 3] [1 3] [5 3] [6 3]]
                                                                  :flash {:cue [6 0] :fx [[2 3]] :solo? true}})
              (assoc-in [:chains :cue-chains [1 0] :items 0 :effects]
                        [{:effect-id :rotation :enabled? true :params {:angle {:type :step :source :hit :min 0 :max 90}}}]))
        index (reactions/reactions s :vibe-1)]
    (is (= {:hit [{:grid :effects :cell [0 3] :name "Punch size"}
                  {:grid :effects :cell [1 3] :name "Energy"}
                  {:grid :cues :cell [1 0] :name "square"}]
            :accent [{:grid :effects :cell [1 3] :name "Energy"}
                     {:grid :effects :cell [3 3] :name "On the grid"}]
            :flash [{:grid :effects :cell [1 3] :name "Energy"}
                    {:grid :effects :cell [2 3] :name "Strobe"}]}
           index)
        "its FX set (the vibe's name dropped), its flash FX, the grid's cells that are on and its pool cues; :any is
         all three roles, a cue start none, and a modulator that's off nothing")
    (is (= {:accent [{:grid :effects :cell [3 3] :name "On the grid"}]} (reactions/reactions s :vibe-2))
        "a grid cell that is on reacts whatever the vibe")
    (is (= {} (reactions/reactions s :gone)))
    (testing "listens-to: a pulse type's source, a Restart on, a keyframe's Restart on"
      (is (= #{:hit} (reactions/listens-to (get-in s [:chains :effect-chains [0 3]]))))
      (is (= #{:flash} (reactions/listens-to (get-in s [:chains :effect-chains [2 3]]))))
      (is (= #{:any} (reactions/listens-to (get-in s [:chains :effect-chains [1 3]]))) "Energy's default")
      (is (= #{} (reactions/listens-to {:items [{:effect-id :scale :enabled? false :params {:x-scale (env :hit)}}]}))
          "a disabled effect")
      (is (= #{:vibe} (reactions/listens-to {:items [{:effect-id :scale :enabled? true :params {}
                                                      :keyframe-modulator {:enabled? true :restart-on :vibe}}]})))
      (is (= #{:accent} (reactions/listens-to {:items [{:type :group :enabled? true
                                                        :items [{:effect-id :scale :params {:x-scale (env :accent)}}]}]}))
          "through a group"))))

;; Zone patterns that step on a press (zones plan Z5, 07 §4.7, S-10)

(defn- pattern-cell
  "An effects cell, off, holding a Chase across every laser with params."
  [cell-name & {:as params}]
  {:name cell-name :active? false
   :items [(update (zone-patterns/preset-instance :chase :cell) :params merge params)]})

(deftest pattern-on-hit-is-a-hit-reaction-test
  (let [s (-> (starter)
              (assoc-in [:chains :effect-chains [2 1]] (pattern-cell "Shapes · Run" :step-on :hit))
              (update-in [:config :vibes :library :vibe-1] merge {:fx [[2 1]]}))]
    (is (= #{:hit} (reactions/listens-to (get-in s [:chains :effect-chains [2 1]]))) "its Step on, a plain parameter")
    (is (= [{:grid :effects :cell [2 1] :name "Run"}] (:hit (reactions/reactions s :vibe-1)))
        "one of HIT's tap reactions: the pad names it")
    (testing "a Map cycle's Step on"
      (is (= #{:accent} (reactions/listens-to {:items [(assoc-in (zone-patterns/cycle-instance) [:params :step-on] :accent)]}))))
    (testing "a cue's own pattern: the cue listens"
      (is (= #{:flash} (reactions/listens-to {:items [{:type :preset :preset-id :circle}]
                                               :zone-patterns [(assoc-in (zone-patterns/preset-instance :alternate :cue)
                                                                         [:params :step-on] :flash)]}))))
    (testing "a disabled pattern listens to nothing"
      (is (= #{} (reactions/listens-to (assoc-in (pattern-cell "Run" :step-on :hit) [:items 0 :enabled?] false)))))))

(deftest pattern-restart-on-is-heard-test
  (is (= #{:flash} (reactions/listens-to (pattern-cell "Run" :step-on :clock :restart-on :flash)))
      "Restart on, while it steps on the beat")
  (is (= #{:hit} (reactions/listens-to (pattern-cell "Run" :step-on :hit :restart-on :flash)))
      "one that steps on a press never restarts: its Restart on isn't heard")
  (is (= #{:accent :flash} (reactions/listens-to (pattern-cell "Run" :restart-on :flash :rate {:type :envelope :source :accent :min 1.0 :max 2.0})))
      "and a pulse modulator on its Rate, as any effect's"))

(deftest clock-pattern-listens-to-nothing-test
  (is (= #{} (reactions/listens-to (pattern-cell "Run"))) "the beat, from its own clock")
  (is (= #{} (reactions/listens-to {:items [(zone-patterns/cycle-instance)]})) "a Map cycle, a bar each"))

(deftest zone-step-is-no-roles-test
  (let [s (-> (starter)
              (assoc-in [:chains :effect-chains [2 1]] (pattern-cell "Run" :step-on :zone-step))
              (update-in [:config :vibes :library :vibe-1] merge {:fx [[2 1]]}))]
    (is (= #{:zone-step} (reactions/listens-to (get-in s [:chains :effect-chains [2 1]]))))
    (is (nil? (reactions/roles-of :zone-step)) "like a cue start, no role's")
    (is (= {} (reactions/reactions s :vibe-1)) "so no role's tap reaction")))


(deftest own-reactions-leave-the-grid-out-test
  (let [s (-> (starter)
              (reactions/add-to-vibe :vibe-1 :punch-size)
              (assoc-in [:chains :effect-chains [7 3]] (assoc (reactions/reaction-cell :flip nil) :active? true)))]
    (is (= {:hit [{:grid :effects :cell [0 3] :name "Punch size"}]
            :accent [{:grid :effects :cell [7 3] :name "Flip"}]}
           (reactions/reactions s :vibe-1))
        "while it plays, the effects grid's cells that are on move too (the Perform pads)")
    (is (= {:hit [{:grid :effects :cell [0 3] :name "Punch size"}]}
           (reactions/reactions s :vibe-1 {:own? true}))
        "its own cells only: its FX set, its flash effects and its cues (the Vibes tab and its lint)")))
