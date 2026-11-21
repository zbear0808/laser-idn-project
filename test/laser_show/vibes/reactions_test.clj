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
            [laser-show.routing.rig :as rig]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.vibes.library :as library]
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
  (is (= [:punch-size :pump :step-colour :step-spin :zone-hop :flip :scatter :zone-swap :zone-shift
          :swell :strobe :zone-chase :fade-in :energy-size]
         (mapv :kind reactions/templates)))
  (is (= {:hit 5 :accent 4 :flash 3 :vibe 1 :any 1} (frequencies (map :role reactions/templates))))
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
  (testing "invariant 11: until its source's first press each template leaves the frames as they are, to within 1e-9
            (the point effects here; the zone reactions move frames between places: zone-reactions-at-rest-test)"
    (let [plain (render nil)]
      (doseq [{:keys [kind into] :as template} reactions/templates
              :when (and (not= :flash-fx into) (not (reactions/zone-reaction? template)))
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


;; Zone reactions (vibe Phase 8b, plans/2026-11-19-zones/11 §12): zone
;; patterns that step on a press

(def ^:private three-lasers
  "p1, p2 and p3, on and in All, left to right (by their addresses)."
  {:p1 {:name "Laser 1" :host "10.0.0.1" :zone-groups [] :enabled? true}
   :p2 {:name "Laser 2" :host "10.0.0.2" :zone-groups [] :enabled? true}
   :p3 {:name "Laser 3" :host "10.0.0.3" :zone-groups [] :enabled? true}})

(defn- on-lasers [projectors] (assoc (starter) :projectors projectors))

(defn- scanning
  "projectors with id tagged as scanning the audience, and more of its keys."
  [projectors id & {:as more}]
  (update projectors id merge {:tags #{:crowd-scanning}} more))

(def ^:private all-cue
  "A cue drawing a circle into All."
  {:items [{:id :c :type :preset :preset-id :circle :enabled? true :effects []}]})

(def ^:private zones-cue
  "A cue drawing a circle into zone A and a square into zone B."
  {:items [{:id :x :type :preset :preset-id :circle :enabled? true :effects [] :target :zone/a}
           {:id :y :type :preset :preset-id :square :enabled? true :effects [] :target :zone/b}]})

(defn- presses
  "A pulse view of n presses of source, the newest beats ago."
  ([source n] (presses source n 0.5))
  ([source n beats]
   {source {:count n :beats beats :ms (* 500.0 beats) :held? false :since-release beats :recent [beats]}}))

(defn- staged
  "cue's frames by place through the zone patterns of cell (a reaction's,
   as the effects grid runs it) on state's rig, at the show's beats, with
   pulses (a pulse view; nil for no press)."
  [state cell cue beats pulses]
  (let [c (cond-> (assoc ctx :accumulated-beats beats :effective-beats beats) pulses (assoc :pulses pulses))]
    (fs/generate-frames-by-zone cue c {:zone-effects (mapv (fn [fx] {:cell [1 3] :effect fx}) (:items cell))
                                       :global-ctx c
                                       :zone-ctx (fs/zone-ctx (rig/routing (rig/routing-inputs state nil)) nil)})))

(deftest zone-templates-are-zone-patterns-test
  (testing "each is Z5's preset of its style for a cell, stepping on its role's press itself (07 §8.3), not a Step
            modulator on its offset"
    (doseq [[kind role menu cell-name style press] [[:zone-hop :hit "Zone hop: next zone (one zone at a time)" "Zone hop"
                                                     :chase {:step-on :hit}]
                                                    [:zone-swap :accent "Zone swap" "Zone swap" :swap {:step-on :accent}]
                                                    [:zone-shift :accent "Zone shift: next zone" "Zone shift" :shift
                                                     {:step-on :accent}]
                                                    [:zone-chase :flash "Zone chase while held" "Zone chase" :chase
                                                     {:restart-on :flash}]]
            :let [t (reactions/template kind)
                  [fx & more] (:effects t)]]
      (testing (name kind)
        (is (reactions/zone-reaction? t))
        (is (= [role menu cell-name] ((juxt :role :menu :name) t)))
        (is (nil? more) "one effect")
        (is (= :zone-pattern (:effect-id fx)))
        (is (= (merge (:params (zone-patterns/preset-instance style :cell)) press) (:params fx))
            "rate 1, offset 0, the show's beats, following the pattern pads: the rest is Z5's")
        (is (= (str "Groove · " cell-name) (:name (reactions/reaction-cell kind "Groove"))))
        (is (= #{role} (reactions/listens-to (reactions/reaction-cell kind nil))) "its role hears it (Z5 S-10)"))))
  (testing "their places (§12.3): Every laser for the moves, zones for the trades"
    (is (= {:zone-hop [:lane/lasers] :zone-swap [:zone/a :zone/b] :zone-shift [:zone/a :zone/b :zone/c :zone/d]
            :zone-chase [:lane/lasers]}
           (into {} (for [t reactions/templates :when (reactions/zone-reaction? t)]
                      [(:kind t) (get-in t [:effects 0 :params :zones])]))))
    (is (= :clock (get-in (reactions/template :zone-chase) [:effects 0 :params :step-on])) "the chase runs on the beat")
    (is (= :flash-fx (:into (reactions/template :zone-chase))) "only on while FLASH is held")
    (is (every? #(nil? (:into (reactions/template %))) [:zone-hop :zone-swap :zone-shift]) "the others: the FX set"))
  (testing "a cell made with places has them as its lane"
    (is (= [:auto.laser/p1 :auto.laser/p3]
           (get-in (reactions/reaction-cell :zone-hop "Groove" [:auto.laser/p1 :auto.laser/p3]) [:items 0 :params :zones])))
    (is (= (reactions/reaction-cell :punch-size "Groove") (reactions/reaction-cell :punch-size "Groove" [:zone/a]))
        "a point effect has no places"))
  (is (= [:zone-hop :zone-swap :zone-shift :zone-chase] (mapv :kind (filter reactions/zone-reaction? reactions/templates)))
      "Phase 4's templates are point effects"))

(deftest zone-reactions-at-rest-test
  (let [s (on-lasers three-lasers)
        P (:all (fs/generate-frames-by-zone all-cue ctx {}))
        drawn (fs/generate-frames-by-zone zones-cue ctx {})]
    (testing "the swap and the shift: as drawn, before ACCENT's first press"
      (doseq [kind [:zone-swap :zone-shift]]
        (is (= drawn (staged s (reactions/reaction-cell kind nil) zones-cue 4.0 nil)) (name kind))))
    (testing "the hop: the All picture on the first laser until the first HIT, by design (§7)"
      (is (= {:auto.laser/p1 P} (staged s (reactions/reaction-cell :zone-hop nil) all-cue 4.0 nil)))
      (is (= {:auto/left P} (staged (on-lasers (select-keys three-lasers [:p1])) (reactions/reaction-cell :zone-hop nil)
                                    all-cue 4.0 nil))
          "one laser: its left half"))
    (testing "the chase is off: made off, in the flash FX, so it is on only while FLASH is held"
      (is (false? (:active? (reactions/reaction-cell :zone-chase nil))))
      (is (= [[0 3]] (get-in (reactions/add-to-vibe s :vibe-1 :zone-chase) [:config :vibes :library :vibe-1 :flash :fx]))))
    (testing "what they don't move stays: the hop leaves the zones, the trades leave All"
      (is (= drawn (staged s (reactions/reaction-cell :zone-hop nil) zones-cue 4.0 (presses :hit 1))))
      (is (= {:all P} (staged s (reactions/reaction-cell :zone-swap nil) all-cue 4.0 (presses :accent 1)))))))

(deftest zone-hop-moves-the-picture-on-each-hit-test
  (testing "§8: the frames after 0, 1, 2 and 3 HITs, through the engine's pulses and the show clock"
    (state/init-state! (-> (on-lasers three-lasers)
                           (assoc-in [:config :vibes :settings :hit-taps-tempo?] false)
                           (assoc-in [:config :vibes :library :vibe-1 :hit :cells] [])))
    (show-time/reset-runtime!)
    (with-redefs [events/dispatch! (fn [_])]
      (events/event-handler {:event/type :vibe/select :vibe :vibe-1 :input-time 0})
      (events/event-handler {:event/type :vibe/add-reaction :vibe :vibe-1 :kind :zone-hop})
      (events/event-handler {:event/type :grid/trigger-cell :col 0 :row 0})
      (let [ms 1000000
            places (fn [t] (set (mapcat (comp keys :zone-frames) (:cues (show-clock/tick! (* t ms))))))
            hit! (fn [t]
                   (events/event-handler {:event/type :vibe/press :role :hit :input-time t})
                   (events/event-handler {:event/type :vibe/release :role :hit :input-time (+ t 50)}))]
        (is (= "Shapes · Zone hop" (get-in (state/get-raw-state) [:chains :effect-chains [0 3] :name])))
        (is (true? (get-in (state/get-raw-state) [:chains :effect-chains [0 3] :active?])) "Shapes plays: on at once")
        (is (= #{:auto.laser/p1} (places 16)) "no HIT yet: the first laser, by design")
        (hit! 100)
        (is (= #{:auto.laser/p2} (places 200)) "a HIT: the next laser")
        (hit! 300)
        (is (= #{:auto.laser/p3} (places 400)))
        (hit! 500)
        (is (= #{:auto.laser/p1} (places 600)) "round again")
        (is (= #{:auto.laser/p1} (places 700)) "and there until the next HIT")))))

(deftest zone-swap-and-shift-on-each-accent-test
  (let [s (on-lasers three-lasers)
        {X :zone/a Y :zone/b} (fs/generate-frames-by-zone zones-cue ctx {})
        at (fn [kind n] (staged s (reactions/reaction-cell kind nil) zones-cue 4.0 (presses :accent n)))]
    (testing "the swap: the 1st ACCENT trades A and B (Left and Right under Left / Right), the 2nd trades them back"
      (is (= {:zone/a Y :zone/b X} (at :zone-swap 1)))
      (is (= {:zone/a X :zone/b Y} (at :zone-swap 2)))
      (is (= {:zone/a Y :zone/b X} (at :zone-swap 3))))
    (testing "the shift: what is in each zone moves one zone on, round A, B, C, D"
      (is (= {:zone/b X :zone/c Y} (at :zone-shift 1)))
      (is (= {:zone/c X :zone/d Y} (at :zone-shift 2)))
      (is (= {:zone/d X :zone/a Y} (at :zone-shift 3)))
      (is (= {:zone/a X :zone/b Y} (at :zone-shift 4)) "a whole turn"))
    (testing "HIT isn't ACCENT"
      (is (= {:zone/a X :zone/b Y} (staged s (reactions/reaction-cell :zone-swap nil) zones-cue 4.0 (presses :hit 1)))))))

(deftest zone-chase-restarts-on-each-flash-test
  (let [s (on-lasers three-lasers)
        chase (reactions/reaction-cell :zone-chase nil)
        P (:all (fs/generate-frames-by-zone all-cue ctx {}))
        at (fn [beats flash] (keys (staged s chase all-cue beats flash)))]
    (testing "a laser a beat, from the first at each FLASH (Restart on), whatever the show's beat"
      (is (= [:auto.laser/p1] (at 9.3 (presses :flash 1 0.0))) "the press: the first laser")
      (is (= [:auto.laser/p1] (at 9.8 (presses :flash 1 0.5))))
      (is (= [:auto.laser/p2] (at 10.3 (presses :flash 1 1.0))))
      (is (= [:auto.laser/p3] (at 11.3 (presses :flash 1 2.0))))
      (is (= [:auto.laser/p1] (at 12.3 (presses :flash 1 3.0))) "and round")
      (is (= [:auto.laser/p1] (at 12.6 (presses :flash 2 0.0))) "a new FLASH: from the first again"))
    (testing "it moves the All picture, and nothing else"
      (is (= {:auto.laser/p2 P} (staged s chase all-cue 10.3 (presses :flash 1 1.0)))))))

(deftest lane-leaves-a-scanner-out-test
  (let [lane (fn [projectors kind]
               (let [s (on-lasers projectors)]
                 (reactions/lane s (library/audience-groups s) kind)))]
    (testing "Every laser, left to right; zones for the swap and the shift"
      (is (= [:lane/lasers] (lane three-lasers :zone-hop)))
      (is (= [:lane/lasers] (lane three-lasers :zone-chase)))
      (is (= [:zone/a :zone/b] (lane three-lasers :zone-swap)))
      (is (= [:zone/a :zone/b :zone/c :zone/d] (lane three-lasers :zone-shift)))
      (is (nil? (lane three-lasers :punch-size)) "a point effect has no places"))
    (testing "a laser in All that scans the audience: the lasers that are on and in All and don't, left to right"
      (is (= [:auto.laser/p1 :auto.laser/p3] (lane (scanning three-lasers :p2) :zone-hop)))
      (is (= [:auto.laser/p1 :auto.laser/p3] (lane (scanning three-lasers :p2) :zone-chase)))
      (is (= [:zone/a :zone/b] (lane (scanning three-lasers :p2) :zone-swap)) "zones stay: the audience rule decides")
      (is (= [:auto.laser/p1 :auto.laser/p3] (lane (scanning three-lasers :p2 :enabled? false) :zone-hop))
          "off, it could still be turned on mid-show")
      (is (= [:auto.laser/p3] (lane (-> three-lasers (scanning :p2) (assoc-in [:p1 :enabled?] false)) :zone-hop))
          "a laser that is off isn't one of them")
      (is (= [:auto.laser/p3] (lane (-> three-lasers (scanning :p2) (assoc-in [:p1 :exclude-from-all?] true)) :zone-hop))
          "nor one out of All"))
    (testing "a scanner out of All is never in Every laser"
      (is (= [:lane/lasers] (lane (scanning three-lasers :p2 :exclude-from-all? true) :zone-hop))))
    (testing "without audience groups, Every laser"
      (is (= [:lane/lasers] (reactions/lane (on-lasers (scanning three-lasers :p2)) #{} :zone-hop))))))

(deftest zone-reactions-need-two-places-test
  (let [hint (fn [projectors kind]
               (let [s (on-lasers projectors)]
                 (reactions/zone-hint s (library/audience-groups s) kind)))
        no-laser "needs a laser that's on and in All (Projectors tab)"
        too-few "needs two lasers in All that don't scan the audience (Projectors tab)"]
    (testing "no laser on and in All: the hop and the chase have nowhere to go"
      (is (= no-laser (hint {} :zone-hop)))
      (is (= no-laser (hint {} :zone-chase)))
      (is (= no-laser (hint (update-vals three-lasers #(assoc % :enabled? false)) :zone-hop)) "all off")
      (is (= no-laser (hint (update-vals three-lasers #(assoc % :exclude-from-all? true)) :zone-hop)) "all out of All"))
    (testing "the swap and the shift never: A and B are two places on any rig"
      (is (nil? (hint {} :zone-swap)))
      (is (nil? (hint {} :zone-shift))))
    (testing "one laser on its own: its two halves"
      (is (nil? (hint (select-keys three-lasers [:p1]) :zone-hop)))
      (is (= [:auto/left :auto/right] (reactions/places (on-lasers (select-keys three-lasers [:p1])) [:lane/lasers]))))
    (testing "a scanner in All and one other laser: one place is too few"
      (is (= too-few (hint (scanning (select-keys three-lasers [:p1 :p2]) :p2) :zone-hop)))
      (is (= too-few (hint (scanning (select-keys three-lasers [:p1 :p2]) :p2) :zone-chase)))
      (is (nil? (hint (scanning (select-keys three-lasers [:p1 :p2]) :p2 :exclude-from-all? true) :zone-hop))
          "the scanner out of All: the other laser's halves")
      (is (nil? (hint (scanning three-lasers :p2) :zone-hop)) "two lasers besides it"))
    (testing "not a zone reaction, or no such kind"
      (is (nil? (hint {} :punch-size)))
      (is (nil? (hint {} :nope))))))
