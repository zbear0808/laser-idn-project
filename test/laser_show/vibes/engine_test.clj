(ns laser-show.vibes.engine-test
  "laser-show.vibes.engine: one test per row of the press-and-release table
   (plans/2026-11-18-vibe-mode/01 §7), then the beyond-vibe cases. States are
   the starter cues and vibes; times are the events' own (ms)."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.events.handlers :as handlers]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.state.domains :as domains]
            [laser-show.state.templates :as templates]
            [laser-show.vibes.engine :as engine]
            [laser-show.vibes.library :as library]))

(def ^:private shape
  {:items [{:type :preset :preset-id :circle}] :destination-zone {:zone-group-id :all}})

(defn- starter
  "The starter cues and vibes, two more cues for beds ([0 1], [1 1]) and two
   effect cells, both off ([0 3], [1 3])."
  []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-vibes)
      (assoc-in [:timing :bpm] 120.0)
      (assoc-in [:chains :cue-chains [0 1]] shape)
      (assoc-in [:chains :cue-chains [1 1]] shape)
      (assoc-in [:chains :effect-chains [0 3]] {:items [{:effect-id :scale}] :active? false})
      (assoc-in [:chains :effect-chains [1 3]] {:items [{:effect-id :hue-shift}] :active? false})))

(defn- with-vibe
  "state with vibe id's keys merged with m."
  [state id m]
  (update-in state [:config :vibes :library id] merge m))

(defn- press [state role t] (:state (engine/press state {:role role :time t})))
(defn- release [state role t] (:state (engine/release state {:role role :time t})))
(defn- tap [state role t] (-> state (press role t) (release role (+ t 50))))

(defn- rt [state & ks] (get-in state (into [:vibe-runtime] ks)))
(defn- intent [state cell] (get-in state [:playback :active-cues cell]))
(defn- playing [state] (set (keys (get-in state [:playback :active-cues]))))
(defn- fx-on? [state cell] (true? (get-in state [:chains :effect-chains cell :active?])))

(def ^:private pool-1 #{[0 0] [1 0] [2 0] [3 0]})


;; The table, rows 1-12

(deftest hit-press-starts-a-pool-cue-test
  (let [{:keys [state dispatch]} (engine/press (starter) {:role :hit :time 1000})
        {:keys [cell run]} (rt state :voices :hit)]
    (is (= [:vibe-1 1000] [(rt state :current) (rt state :entered-at)]) "no vibe current: the first")
    (is (contains? pool-1 cell))
    (is (= {:run run :decay-beats 1.0 :hold? true} (intent state cell)))
    (is (true? (get-in state [:playback :playing?])))
    (is (= {:hit {:at 1000 :src :input}} (rt state :held)))
    (is (= 1000 (rt state :last-hit-at)))
    (let [bag (rt state :bags [:vibe-1 :hit])]
      (is (= pool-1 (set (:items bag))) "made from the 4 playable cells")
      (is (= (disj pool-1 cell) (set (:remaining bag))))
      (is (= [cell 1000 1] ((juxt :last :seed :n) bag)) "seeded by the press's time; one refill so far"))
    (is (= {:event/type :timing/tap-tempo :input-time 1000} dispatch) "the tap, at the press's own time")
    (testing "no tap without the setting, nor while Link sync is on (V3)"
      (is (nil? (:dispatch (engine/press (assoc-in (starter) [:config :vibes :settings :hit-taps-tempo?] false)
                                         {:role :hit :time 1000}))))
      (is (nil? (:dispatch (engine/press (update-in (starter) [:backend :link] assoc :link-enabled? true :sync-enabled? true)
                                         {:role :hit :time 1000})))))
    (testing "the input's own time, and a pad's press says so"
      (let [s (:state (engine/press (starter) {:role :hit :time 5000 :input-time 1000 :src :pad}))]
        (is (= {:at 1000 :src :pad} (rt s :held :hit)))))))

(deftest quick-tap-keeps-decaying-test
  (let [s1 (press (starter) :hit 1000)
        {:keys [cell run] :as voice} (rt s1 :voices :hit)
        s2 (release s1 :hit 1090)]
    (is (= {:run run :decay-beats 1.0 :hold? false} (intent s2 cell)) "the clock ends the run at 1 beat")
    (is (nil? (rt s2 :held :hit)))
    (is (= voice (rt s2 :voices :hit)))))

(deftest long-hold-cuts-on-release-test
  (let [s1 (press (starter) :hit 1000)
        {:keys [cell]} (rt s1 :voices :hit)
        s2 (release s1 :hit 1400)]
    (is (nil? (intent s2 cell)))
    (is (nil? (rt s2 :voices :hit)))
    (is (false? (get-in s2 [:playback :playing?])) "no other cue plays")
    (testing "150 ms is already a hold"
      (is (nil? (intent (release s1 :hit 1150) cell)))
      (is (some? (intent (release s1 :hit 1149) cell))))))

(deftest new-press-stops-the-old-cue-test
  (let [s1 (-> (starter) (press :hit 1000) (release :hit 1090))
        {c1 :cell} (rt s1 :voices :hit)
        s2 (press s1 :hit 1500)
        {c2 :cell r2 :run} (rt s2 :voices :hit)]
    (is (= (first (rt s1 :bags [:vibe-1 :hit] :remaining)) c2) "the next from the bag")
    (is (not= c1 c2))
    (is (nil? (intent s2 c1)) "its run was still the voice's: stopped")
    (is (= {:run r2 :decay-beats 1.0 :hold? true} (intent s2 c2)))
    (is (= #{c2} (playing s2)))))

(deftest accent-has-its-own-voice-test
  (let [s1 (press (starter) :hit 1000)
        {:keys [state dispatch]} (engine/press s1 {:role :accent :time 1520})
        {:keys [cell run]} (rt state :voices :accent)]
    (is (= [4 0] cell))
    (is (= {:run run :decay-beats 2.0 :hold? true} (intent state [4 0])))
    (is (= (rt s1 :voices :hit) (rt state :voices :hit)) "HIT untouched")
    (is (= (intent s1 (rt s1 :voices :hit :cell)) (intent state (rt s1 :voices :hit :cell))))
    (is (nil? dispatch) "only HIT taps the tempo")))

(defn- sustained-hit
  "vibe-1 with HIT [0 0] on :sustain: the hit playing at 1000, the accent [4 0] decaying from 1100."
  []
  (-> (starter)
      (with-vibe :vibe-1 {:hit {:cells [[0 0]] :select :shuffle :mode :sustain}})
      (tap :hit 1000)
      (tap :accent 1100)))

(deftest solo-flash-ducks-and-restores-test
  (let [s1 (sustained-hit)
        hit-run (rt s1 :voices :hit :run)
        s2 (press s1 :flash 1200)
        flash-run (rt s2 :voices :flash :run)]
    (is (= #{[0 0] [4 0]} (playing s1)))
    (is (nil? (intent s2 [4 0])) "a decaying voice is dropped, not restored")
    (is (nil? (rt s2 :voices :accent)))
    (is (nil? (intent s2 [0 0])) "the sustained hit steps aside")
    (is (= {:hit {:cell [0 0] :mode :sustain}} (rt s2 :duck)))
    (is (= {:run flash-run :hold? true} (intent s2 [6 0])))
    (is (= #{[6 0]} (playing s2)))
    (testing "row 7: the release"
      (let [s3 (release s2 :flash 1800)
            {:keys [cell run]} (rt s3 :voices :hit)]
        (is (nil? (intent s3 [6 0])))
        (is (= [0 0] cell))
        (is (not= hit-run run) "started again as a new run")
        (is (= {:run run} (intent s3 [0 0])))
        (is (empty? (rt s3 :duck)))
        (is (= #{[0 0]} (playing s3)) "the accent doesn't come back")
        (is (nil? (rt s3 :voices :flash)))
        (is (nil? (rt s3 :held :flash)))))))

(deftest flash-fx-switches-and-restores-test
  (let [s0 (-> (starter)
               (with-vibe :vibe-1 {:flash {:cue [6 0] :fx [[0 3] [1 3]] :solo? false}})
               (assoc-in [:chains :effect-chains [1 3] :active?] true)
               (tap :hit 1000))
        hit (rt s0 :voices :hit)
        s1 (press s0 :flash 1200)]
    (is (fx-on? s1 [0 3]))
    (is (= [[0 3]] (rt s1 :voices :flash :fx-on)) "[1 3] was on already: not the flash's")
    (is (= hit (rt s1 :voices :hit)) "not solo: the hit plays on")
    (is (= #{(:cell hit) [6 0]} (playing s1)))
    (let [s2 (release s1 :flash 1500)]
      (is (not (fx-on? s2 [0 3])) "what it switched on is switched off")
      (is (fx-on? s2 [1 3]) "what was on stays on")
      (is (nil? (intent s2 [6 0]))))
    (testing "a flash of effect cells only"
      (let [s (with-vibe (starter) :vibe-1 {:flash {:cue nil :fx [[0 3]] :solo? true}})
            s1 (press s :flash 1000)]
        (is (fx-on? s1 [0 3]))
        (is (empty? (playing s1)))
        (is (not (fx-on? (release s1 :flash 1300) [0 3])))))))

(defn- two-looks
  "vibe-1: HIT [0 0] :sustain, bed [0 1], FX set [0 3]; vibe-2: bed [1 1], FX set [1 3]."
  []
  (-> (starter)
      (with-vibe :vibe-1 {:hit {:cells [[0 0]] :select :shuffle :mode :sustain} :bed [0 1] :fx [[0 3]]})
      (with-vibe :vibe-2 {:bed [1 1] :fx [[1 3]]})))

(deftest vibe-tap-next-wraps-test
  (let [s1 (-> (two-looks) (tap :hit 1000) (tap :accent 1100))
        bags (rt s1 :bags)]
    (is (= :vibe-1 (rt s1 :current)))
    (is (= #{[0 0] [4 0] [0 1]} (playing s1)) "the first press entered vibe-1: its bed plays")
    (is (fx-on? s1 [0 3]))
    (let [s2 (-> s1 (press :vibe 1000) (release :vibe 1200))]
      (is (= [:vibe-2 1200] [(rt s2 :current) (rt s2 :entered-at)]))
      (is (= #{[4 0] [1 1]} (playing s2)) "old bed and sustain voices stop, the decaying accent plays on, the new bed starts")
      (is (= [1 1] (rt s2 :voices :bed :cell)))
      (is (not (fx-on? s2 [0 3])) "old FX set off")
      (is (fx-on? s2 [1 3]) "new FX set on")
      (is (= [[1 3]] (rt s2 :fx-on)))
      (is (= bags (rt s2 :bags)) "bags kept")
      (testing "it wraps"
        (let [s3 (-> s2 (press :vibe 2000) (release :vibe 2100))]
          (is (= :vibe-1 (rt s3 :current)))
          (is (= #{[4 0] [0 1]} (playing s3)))
          (is (= [true false] [(fx-on? s3 [0 3]) (fx-on? s3 [1 3])])))))))

(deftest vibe-hold-locks-test
  (let [s1 (-> (starter) (tap :hit 900) (press :vibe 1000) (release :vibe 1700))]
    (is (= :locked (rt s1 :rotation)))
    (is (= :vibe-1 (rt s1 :current)) "the vibe doesn't change")
    (is (= :active (rt (-> s1 (press :vibe 2000) (release :vibe 2600)) :rotation)) "600 ms or more: unlock")))

(deftest blackout-is-sticky-test
  (let [s0 (-> (two-looks)
               (with-vibe :vibe-1 {:flash {:cue [6 0] :fx [[1 3]] :solo? false}})
               (press :hit 1000)
               (tap :accent 1100)
               (press :flash 1200))
        {:keys [state dispatch]} (engine/press s0 {:role :blackout :time 1300})]
    (is (= #{[0 0] [4 0] [6 0] [0 1]} (playing s0)))
    (is (empty? (playing state)) "every voice stopped")
    (is (= [{} {} {} []] [(rt state :held) (rt state :voices) (rt state :duck) (rt state :fx-on)]))
    (is (not-any? #(fx-on? state %) [[0 3] [1 3]]) "the FX set and the flash's effect cells off")
    (is (true? (rt state :blackout?)))
    (is (= {:event/type :transport/stop} dispatch))
    (testing "HIT, ACCENT, FLASH and VIBE presses change nothing (beyond-vibe blackout_blocks_triggers)"
      (doseq [role [:hit :accent :flash :vibe]]
        (is (= {:state state} (engine/press state {:role role :time 1400})) (str role)))
      (is (= {:state state} (engine/release state {:role :hit :time 1500})) "nothing is held"))
    (testing "pressed again, HIT plays"
      (let [s (-> state (press :blackout 1600) (press :hit 1700))]
        (is (some? (rt s :voices :hit)))))))

(deftest blackout-off-restores-the-bed-test
  (let [s1 (-> (two-looks) (tap :hit 1000))
        bed-run (rt s1 :voices :bed :run)
        s2 (press s1 :blackout 1100)
        {:keys [state dispatch]} (engine/press s2 {:role :blackout :time 1200})
        {:keys [cell run]} (rt state :voices :bed)]
    (is (false? (rt state :blackout?)))
    (is (= {:event/type :transport/play} dispatch))
    (is (= [0 1] cell) "the bed starts again")
    (is (not= bed-run run))
    (is (= #{[0 1]} (playing state)) "the sustained hit doesn't")
    (is (fx-on? state [0 3]) "and the FX set")))

(deftest empty-pool-reports-a-problem-test
  (let [s0 (-> (starter)
               (with-vibe :vibe-1 {:hit {:cells [[0 0] [1 0] [2 0]] :select :shuffle :mode :decay :beats 1.0}})
               (update-in [:chains :cue-chains] dissoc [1 0])
               (assoc-in [:chains :cue-chains [2 0] :items] []))]
    (is (= [[0 0]] (rt (tap s0 :hit 1000) :bags [:vibe-1 :hit] :items)) "the bag is over the one playable cell")
    (is (= #{[0 0]} (set (map #(rt % :voices :hit :cell) (rest (reductions #(tap %1 :hit %2) s0 [1000 2000 3000])))))
        "and plays it every time")
    (let [s1 (press s0 :accent 900)
          empty (update-in s1 [:chains :cue-chains] dissoc [0 0])
          s2 (press empty :hit 1000)]
      (is (= {:role :hit :why :empty-pool} (rt s2 :problem)))
      (is (= (-> empty
                 (assoc-in [:vibe-runtime :problem] {:role :hit :why :empty-pool})
                 (assoc-in [:vibe-runtime :last-hit-at] 1000)
                 (assoc-in [:playback :pulses :hit] {:id 1 :held? true :src :input}))
             s2)
          "nothing changes but :problem, HIT's pulse (Phase 4) and :last-hit-at (Phase 6: the phrase gap): nothing plays, the engine holds nothing")
      (is (= {:event/type :timing/tap-tempo :input-time 1000} (:dispatch (engine/press empty {:role :hit :time 1000})))
          "and HIT still taps the tempo (Phase 4, V3)")
      (is (nil? (rt (press s2 :accent 1100) :problem)) "until the next good press"))
    (testing "a flash with neither a cue nor effect cells"
      (is (= {:role :flash :why :empty-pool}
             (rt (press (with-vibe s0 :vibe-1 {:flash {:cue nil :fx [] :solo? true}}) :flash 1000) :problem))))
    (testing "no vibes at all"
      (let [none (assoc-in s0 [:config :vibes] library/empty-library)]
        (is (= {:why :no-vibes} (rt (press none :hit 1000) :problem)))
        (is (empty? (playing (press none :hit 1000))))))))


;; Modes, and the beyond-vibe tests

(deftest gate-mode-stops-on-any-release-test
  (let [s1 (-> (starter)
               (with-vibe :vibe-1 {:hit {:cells [[0 0] [1 0]] :select :shuffle :mode :gate}})
               (press :hit 1000))
        {:keys [cell run]} (rt s1 :voices :hit)]
    (is (= {:run run} (intent s1 cell)) "no decay: it plays while held")
    (is (nil? (intent (release s1 :hit 1050) cell)) "even a quick tap stops it")))

(deftest sustain-mode-stays-until-replaced-test
  (let [s1 (-> (starter)
               (with-vibe :vibe-1 {:hit {:cells [[0 0] [1 0]] :select :shuffle :mode :sustain}})
               (press :hit 1000)
               (release :hit 1400))
        {c1 :cell r1 :run} (rt s1 :voices :hit)]
    (is (= {:run r1} (intent s1 c1)) "held 400 ms and let go: still on")
    (let [s2 (press s1 :hit 2000)
          {c2 :cell r2 :run} (rt s2 :voices :hit)]
      (is (nil? (intent s2 c1)) "replaced")
      (is (= {:run r2} (intent s2 c2))))))

(deftest releasing-hit-leaves-accent-playing-test
  (let [s (-> (starter)
              (press :hit 0)
              (press :accent 1000)
              (release :accent 1050)
              (release :hit 1100))]
    (is (nil? (rt s :voices :hit)) "held 1.1 s: cut")
    (is (= #{[4 0]} (playing s)) "beyond-vibe releasing_main_does_not_cut_sub_cue")
    (is (false? (:hold? (intent s [4 0]))))))

(deftest flash-release-stops-its-own-voice-after-a-vibe-change-test
  (let [s0 (-> (starter)
               (with-vibe :vibe-2 {:flash {:cue nil :fx [[0 3]] :solo? false}})
               (tap :hit 0)
               (press :flash 100))
        s1 (-> s0 (press :vibe 200) (release :vibe 300))
        s2 (release s1 :flash 400)]
    (is (contains? (playing s0) [6 0]))
    (is (= :vibe-2 (rt s1 :current)))
    (is (nil? (intent s2 [6 0])) "vibe-1's solo flash stops, though vibe-2's flash is effect cells")
    (is (not (fx-on? s2 [0 3])) "and vibe-2's flash isn't touched")
    (is (nil? (rt s2 :voices :flash)))
    (is (empty? (rt s2 :duck)))))

(deftest vibe-tap-next-keeps-the-lock-test
  (let [s (-> (starter)
              (press :vibe 0) (release :vibe 800)
              (press :vibe 1000) (release :vibe 1100))]
    (is (= :vibe-2 (rt s :current)))
    (is (= :locked (rt s :rotation)))))

(deftest sequence-select-walks-in-order-test
  (let [s0 (with-vibe (starter) :vibe-1 {:hit {:cells [[3 0] [0 0] [2 0]] :select :sequence :mode :decay :beats 1.0}})
        cells (map #(rt % :voices :hit :cell) (rest (reductions #(tap %1 :hit %2) s0 (range 1000 8000 1000))))]
    (is (= [[3 0] [0 0] [2 0] [3 0] [0 0] [2 0] [3 0]] cells))))

(deftest never-stops-someone-elses-cue-test
  (let [s1 (-> (starter) (with-vibe :vibe-1 {:hit {:cells [[0 0] [1 0]] :select :sequence :mode :decay :beats 1.0}})
               (press :hit 1000))
        ;; the grid starts [0 0] again: a new run, not the voice's
        theirs (update s1 :playback cue-timing/activate-cue [0 0] :their-run {})]
    (testing "the release finds the voice's run gone"
      (let [s (release theirs :hit 1400)]
        (is (= {:run :their-run} (intent s [0 0])))
        (is (nil? (rt s :voices :hit)))))
    (testing "nor does the next press stop it"
      (let [s (-> theirs (release :hit 1050) (press :hit 2000))]
        (is (= {:run :their-run} (intent s [0 0])))
        (is (= [1 0] (rt s :voices :hit :cell)))))
    (testing "nor does blackout, which stops every voice"
      (let [s (press theirs :blackout 1500)]
        (is (= {:run :their-run} (intent s [0 0])))
        (is (empty? (rt s :voices)))))
    (testing "nor does leaving a vanished vibe's look, which stops its bed"
      (let [s0 (-> (starter) (with-vibe :vibe-2 {:bed [1 1]}) (press :vibe 0) (release :vibe 100))
            gone (-> s0
                     (update :playback cue-timing/activate-cue [1 1] :their-run {})
                     (update-in [:config :vibes :library] dissoc :vibe-2)
                     (assoc-in [:config :vibes :order] [:vibe-1]))
            s (press gone :hit 1000)]
        (is (= [1 1] (rt s0 :voices :bed :cell)))
        (is (= :vibe-1 (rt s :current)))
        (is (= {:run :their-run} (intent s [1 1])))))))

(deftest vanished-vibe-falls-back-test
  (let [s1 (-> (starter) (with-vibe :vibe-2 {:bed [1 1]}) (press :vibe 0) (release :vibe 100))
        gone (-> s1
                 (update-in [:config :vibes :library] dissoc :vibe-2)
                 (assoc-in [:config :vibes :order] [:vibe-1]))
        s2 (press gone :hit 1000)]
    (is (= [:vibe-2 [1 1]] [(rt s1 :current) (rt s1 :voices :bed :cell)]))
    (is (= [:vibe-1 1000] [(rt s2 :current) (rt s2 :entered-at)]) "the first of :order")
    (is (contains? pool-1 (rt s2 :voices :hit :cell)))
    (is (nil? (intent s2 [1 1])) "the gone vibe's bed stops")
    (testing "with no vibes left: a problem, no exception"
      (let [none (assoc-in gone [:config :vibes] library/empty-library)]
        (doseq [role [:hit :accent :flash]]
          (is (= {:why :no-vibes} (rt (press none role 2000) :problem)) (str role)))
        (is (= {:why :no-vibes} (rt (-> none (press :vibe 2000) (release :vibe 2100)) :problem)))))))

(deftest same-input-same-output-test
  (let [s (-> (two-looks) (tap :hit 1000) (press :flash 1100))
        events [[engine/press {:role :hit :time 2000}]
                [engine/press {:role :accent :time 2000}]
                [engine/release {:role :flash :time 2000}]
                [engine/press {:role :blackout :time 2000}]
                [engine/select {:vibe :vibe-2 :time 2000}]
                [engine/release-all {:only nil :time 2000}]]]
    (doseq [[f event] events]
      (is (= (f s event) (f s event)) (pr-str event)))
    (let [s (starter)]
      (is (= (press s :hit 1000) (press s :hit 1000)) "picks and run ids alike")
      (is (not= (rt (press s :hit 1000) :bags) (rt (press s :hit 1001) :bags))
          "the bag's seed is the press's time"))))

(deftest stale-voices-are-forgotten-test
  (let [s1 (tap (starter) :hit 1000)
        {:keys [cell run]} (rt s1 :voices :hit)
        ended (:state (handlers/handle-event {:event/type :show-clock/cue-ended :cell cell :run run
                                              :position-ids {:restart nil :seek nil} :state s1}))]
    (is (nil? (intent ended cell)) "the clock ended its run")
    (is (nil? (rt (press ended :accent 1500) :voices :hit)) "the next event forgets the voice")
    (testing "a flash whose cue went keeps its effect cells to switch off"
      (let [s (-> (with-vibe (starter) :vibe-1 {:flash {:cue [6 0] :fx [[0 3]] :solo? false}})
                  (press :flash 1000)
                  (update :playback cue-timing/deactivate-cue [6 0]))
            s' (press s :hit 1100)]
        (is (= {:fx [[0 3]] :fx-on [[0 3]] :solo? false} (rt s' :voices :flash)))
        (is (not (fx-on? (release s' :flash 1200) [0 3])))))))


;; Beyond the table

(deftest hit-during-a-solo-flash-waits-for-its-release-test
  (let [s0 (-> (starter)
               (with-vibe :vibe-1 {:hit {:cells [[0 0] [1 0]] :select :sequence :mode :sustain}})
               (press :flash 1000))
        {:keys [state dispatch]} (engine/press s0 {:role :hit :time 1100})]
    (is (= #{[6 0]} (playing state)) "the solo flash plays alone (beyond-vibe janitor.rs:27-40)")
    (is (= {:hit {:mode :sustain}} (rt state :duck)) "a :sustain press waits for the flash")
    (is (= (rt s0 :bags) (rt state :bags)) "and picks nothing yet: no cell loses its turn")
    (is (= {:at 1100 :src :input} (rt state :held :hit)))
    (is (= {:event/type :timing/tap-tempo :input-time 1100} dispatch) "the press still taps")
    (is (= {[0 0] [:sustain]} (let [s (release state :flash 1200)]
                                {(rt s :voices :hit :cell) [(rt s :voices :hit :mode)]}))
        "it picks and starts when the flash lets go")
    (testing "a :decay press is lost, and leaves the bag as it was"
      (let [s1 (-> (starter) (tap :hit 0) (press :flash 1000))
            {:keys [state dispatch]} (engine/press s1 {:role :hit :time 1100})
            s (-> state (release :hit 1150) (release :flash 1200))]
        (is (= (rt s1 :bags) (rt state :bags)))
        (is (= {:at 1100 :src :input} (rt state :held :hit)))
        (is (= {:event/type :timing/tap-tempo :input-time 1100} dispatch))
        (is (empty? (playing s)))
        (is (= (rt s1 :bags) (rt s :bags)))))
    (testing "a :gate press let go of before the flash plays nothing"
      (let [s (-> (starter)
                  (with-vibe :vibe-1 {:hit {:cells [[0 0] [1 0]] :select :sequence :mode :gate}})
                  (press :flash 1000) (press :hit 1100) (release :hit 1200) (release :flash 1300))]
        (is (empty? (playing s)))
        (is (nil? (rt s :bags [:vibe-1 :hit])))))))

(deftest flash-and-fx-set-share-an-effect-cell-test
  (let [flash {:cue nil :fx [[0 3]] :solo? false}
        s0 (-> (starter)
               (with-vibe :vibe-1 {:flash flash})
               (with-vibe :vibe-2 {:fx [[0 3]] :flash flash})
               (tap :hit 0))]
    (testing "switched on by the flash, then the vibe's FX set wants it: it stays on after the flash"
      (let [s (-> s0 (press :flash 100) (press :vibe 200) (release :vibe 300) (release :flash 400))]
        (is (= :vibe-2 (rt s :current)))
        (is (fx-on? s [0 3]))
        (is (= [[0 3]] (rt s :fx-on)))))
    (testing "the FX set's, and the held flash wants it: it stays on until the flash lets go"
      (let [s1 (-> s0 (press :vibe 100) (release :vibe 200) (press :flash 300) (press :vibe 400) (release :vibe 500))]
        (is (= :vibe-1 (rt s1 :current)))
        (is (fx-on? s1 [0 3]))
        (is (not (fx-on? (release s1 :flash 600) [0 3])))))))

(deftest release-all-lets-go-of-one-source-test
  (let [s0 (-> (starter)
               (with-vibe :vibe-1 {:flash {:cue [6 0] :fx [] :solo? false}})
               (press :hit 1000)
               (#(:state (engine/press % {:role :flash :time 1010 :src :pad})))
               (press :vibe 1020))
        s1 (:state (engine/release-all s0 {:only :pad :time 1030}))]
    (is (= #{:hit :vibe} (set (keys (rt s1 :held)))) "only the pad's hold")
    (is (nil? (intent s1 [6 0])))
    (is (some? (rt s1 :voices :hit)))
    (let [s2 (:state (engine/release-all s1 {:only nil :time 1040}))]
      (is (empty? (rt s2 :held)))
      (is (empty? (playing s2)) "a held HIT is cut, however short")
      (is (= :vibe-1 (rt s2 :current)) "and VIBE neither switches nor locks")
      (is (= :active (rt s2 :rotation))))))

(deftest release-all-lets-go-of-some-roles-test
  (testing ":roles: the keys of an input that can't send their releases any more hold those (events/handlers/triggers.clj)"
    (let [s0 (-> (starter)
                 (press :hit 1000)
                 (press :accent 1005)
                 (press :vibe 1020))
          s1 (:state (engine/release-all s0 {:only :input :roles #{:hit :vibe} :time 1030}))]
      (is (= #{:accent} (set (keys (rt s1 :held)))) "ACCENT's key holds on")
      (is (nil? (rt s1 :voices :hit)) "HIT is cut")
      (is (some? (rt s1 :voices :accent)))
      (is (= [false true] (mapv #(get-in s1 [:playback :pulses % :held?]) [:hit :accent])) "the pulses with them")
      (is (= :vibe-1 (rt s1 :current)) "VIBE neither switches nor locks")
      (is (= :active (rt s1 :rotation)))
      (is (= s1 (:state (engine/release-all s1 {:only :input :roles #{:hit} :time 1040}))) "HIT again: nothing to let go"))))


;; Edits to the vibe that plays (Phase 3): the Vibes tab's edits take effect
;; at once, so the lasers show what the editor says

(defn- edited
  "state after (f vibes & args) on [:config :vibes] at time t, as the edit
   events run it (events/handlers/vibes.clj)."
  [state t f & args]
  (let [was (get-in state [:config :vibes])]
    (engine/reconcile (assoc-in state [:config :vibes] (apply f was args)) was t)))

(defn- select [state id t] (:state (engine/select state {:vibe id :time t})))

(deftest removing-the-current-vibe-leaves-its-look-test
  (let [s0 (-> (starter)
               (with-vibe :vibe-1 {:fx [[0 3]] :bed [0 1]})
               (with-vibe :vibe-2 {:fx [[1 3]] :bed [1 1]})
               (tap :hit 1000))
        hit (rt s0 :voices :hit)
        s1 (edited s0 2000 library/remove-vibe :vibe-1)]
    (is (and (fx-on? s0 [0 3]) (some? (intent s0 [0 1]))) "vibe-1's look: its FX set and its bed")
    (is (not (fx-on? s1 [0 3])) "its FX set goes off")
    (is (nil? (intent s1 [0 1])) "its bed stops")
    (is (= [:vibe-2 2000] [(rt s1 :current) (rt s1 :entered-at)]) "the first of :order takes over (01 invariant 9)")
    (is (and (fx-on? s1 [1 3]) (= [1 1] (rt s1 :voices :bed :cell))) "with its look")
    (is (= (:run hit) (:run (intent s1 (:cell hit)))) "a decaying HIT plays out, as after a switch")
    (testing "the last vibe: its look goes, and nothing is current"
      (let [s2 (edited s1 3000 library/remove-vibe :vibe-2)]
        (is (not (fx-on? s2 [1 3])))
        (is (nil? (intent s2 [1 1])))
        (is (nil? (rt s2 :current)))))
    (testing "another vibe: nothing plays differently"
      (is (= (:vibe-runtime s0) (:vibe-runtime (edited s0 2000 library/remove-vibe :vibe-2)))))
    (testing "during blackout the next vibe is current, and its look waits for blackout to end"
      (let [s (-> s0 (press :blackout 1500) (edited 2000 library/remove-vibe :vibe-1))]
        (is (= :vibe-2 (rt s :current)))
        (is (not (fx-on? s [1 3])))
        (is (fx-on? (press s :blackout 2500) [1 3]))))))

(deftest fx-set-edits-switch-the-playing-vibe-test
  (let [s0 (-> (starter) (with-vibe :vibe-1 {:fx [[0 3]]}) (tap :hit 1000))
        s1 (edited s0 2000 library/toggle-fx :vibe-1 :fx [1 3])
        s2 (edited s1 3000 library/toggle-fx :vibe-1 :fx [0 3])]
    (is (and (fx-on? s1 [1 3]) (= [[0 3] [1 3]] (rt s1 :fx-on))) "a cell the FX set gains switches on")
    (is (and (not (fx-on? s2 [0 3])) (= [[1 3]] (rt s2 :fx-on))) "one it loses switches off")
    (testing "a cell switched on by hand stays the user's: added, it isn't the set's to switch off"
      (let [by-hand (assoc-in s0 [:chains :effect-chains [1 3] :active?] true)
            s (edited by-hand 2000 library/toggle-fx :vibe-1 :fx [1 3])]
        (is (= [[0 3]] (rt s :fx-on)))
        (is (fx-on? (edited s 3000 library/toggle-fx :vibe-1 :fx [1 3]) [1 3]))))
    (testing "another vibe's FX set and the flash's effects wait for their turn"
      (is (not (fx-on? (edited s0 2000 library/toggle-fx :vibe-2 :fx [1 3]) [1 3])))
      (is (not (fx-on? (edited s0 2000 library/toggle-fx :vibe-1 :flash-fx [1 3]) [1 3]))))
    (testing "nothing before the first press, nor during blackout, which brings the new set back"
      (is (not (fx-on? (edited (with-vibe (starter) :vibe-1 {:fx [[0 3]]}) 2000 library/toggle-fx :vibe-1 :fx [1 3]) [1 3])))
      (let [s (-> s0 (press :blackout 1500) (edited 2000 library/toggle-fx :vibe-1 :fx [1 3]))]
        (is (not (fx-on? s [1 3])))
        (is (fx-on? (press s :blackout 2500) [1 3]))))
    (testing "a held flash: a cell it switched on becomes the set's, one it asked for stays on until it lets go"
      (let [s (-> (starter)
                  (with-vibe :vibe-1 {:fx [[0 3]] :flash {:cue nil :fx [[0 3] [1 3]] :solo? false}})
                  (tap :hit 1000)
                  (press :flash 1100))
            s1 (edited s 1200 library/toggle-fx :vibe-1 :fx [1 3])
            s2 (edited s1 1300 library/toggle-fx :vibe-1 :fx [0 3])
            s3 (release s2 :flash 1400)]
        (is (= [[[0 3] [1 3]] []] [(rt s1 :fx-on) (rt s1 :voices :flash :fx-on)]))
        (is (= [[[1 3]] [[0 3]]] [(rt s2 :fx-on) (rt s2 :voices :flash :fx-on)]))
        (is (fx-on? s2 [0 3]))
        (is (and (not (fx-on? s3 [0 3])) (fx-on? s3 [1 3])))))))

(deftest bed-edits-restart-the-bed-test
  (let [s0 (-> (starter) (with-vibe :vibe-1 {:bed [0 1]}) (tap :hit 1000))
        bed (rt s0 :voices :bed)
        s1 (edited s0 2000 library/set-single :vibe-1 :bed [1 1])]
    (is (= [0 1] (:cell bed)))
    (is (nil? (intent s1 [0 1])) "the old bed stops")
    (is (and (= [1 1] (rt s1 :voices :bed :cell)) (some? (intent s1 [1 1]))) "the new one plays, as the bed")
    (testing "cleared, it stops"
      (let [s2 (edited s1 3000 library/set-single :vibe-1 :bed nil)]
        (is (nil? (intent s2 [1 1])))
        (is (nil? (rt s2 :voices :bed)))))
    (testing "an edit that leaves the bed as it is doesn't restart it"
      (is (= bed (rt (edited s0 2000 library/rename-vibe :vibe-1 "Calm") :voices :bed))))
    (testing "a bed cell the grid started again is the grid's: it plays on"
      (let [taken (:state (handlers/handle-event {:event/type :grid/activate-cell :col 0 :row 1 :time 1500 :state s0}))
            s (edited taken 2000 library/set-single :vibe-1 :bed [1 1])]
        (is (not= (:run bed) (:run (intent taken [0 1]))))
        (is (= (intent taken [0 1]) (intent s [0 1])))
        (is (= [1 1] (rt s :voices :bed :cell)))))
    (testing "Allow audience cells: an audience bed plays only while the vibe allows it"
      (let [crowd (-> s0
                      (assoc-in [:chains :cue-chains [1 1] :target] :crowd)
                      (edited 2000 library/set-single :vibe-1 :bed [1 1]))
            allowed (edited crowd 3000 library/set-role-option :vibe-1 nil :allow-audience? true)]
        (is (nil? (intent crowd [0 1])))
        (is (nil? (intent crowd [1 1])) "not allowed: it doesn't start")
        (is (= [1 1] (rt allowed :voices :bed :cell)))
        (is (nil? (intent (edited allowed 4000 library/set-role-option :vibe-1 nil :allow-audience? false) [1 1])))))
    (testing "while a solo flash holds the bed back, the new bed waits for its release"
      (let [s (-> s0 (press :flash 1500) (edited 2000 library/set-single :vibe-1 :bed [1 1]))]
        (is (nil? (intent s [1 1])))
        (is (= {:cell [1 1]} (rt s :duck :bed)))
        (is (= [1 1] (rt (release s :flash 2500) :voices :bed :cell)))))))

(deftest pool-edits-wait-for-the-next-press-test
  (let [s0 (-> (starter)
               (with-vibe :vibe-1 {:hit {:cells [[0 0] [1 0]] :select :sequence :mode :sustain}})
               (tap :hit 1000))
        {:keys [cell run]} (rt s0 :voices :hit)
        s1 (edited s0 2000 library/toggle-cell :vibe-1 :hit [0 0])]
    (is (= [0 0] cell))
    (is (= run (:run (intent s1 cell))) "the cue a press started plays on")
    (is (= (:vibe-runtime s0) (:vibe-runtime s1)))
    (is (= [1 0] (rt (press s1 :hit 3000) :voices :hit :cell)) "the next press picks from the pool as it is now")))

(deftest edits-after-an-undo-converge-test
  (testing "an undo took the vibe that plays away (an undo runs no handler): the next edit leaves its look"
    (let [s0 (-> (starter) (with-vibe :vibe-2 {:fx [[1 3]]}) (select :vibe-2 1000))
          undone (-> s0 (update-in [:config :vibes :library] dissoc :vibe-2) (update-in [:config :vibes :order] pop))
          s1 (edited undone 2000 library/rename-vibe :vibe-1 "Calm")]
      (is (fx-on? undone [1 3]))
      (is (not (fx-on? s1 [1 3])))
      (is (= :vibe-1 (rt s1 :current)))))
  (testing "an id handed out again after an undo isn't taken for the vibe that played"
    (let [s0 (-> (starter) (edited 1000 library/add-vibe) (with-vibe :vibe-3 {:fx [[1 3]]}) (select :vibe-3 1500))
          undone (assoc-in s0 [:config :vibes] (get-in (starter) [:config :vibes]))
          s1 (edited undone 2000 library/add-vibe)]
      (is (fx-on? s0 [1 3]))
      (is (= :vibe-3 (peek (get-in s1 [:config :vibes :order]))) "the id comes back")
      (is (= :vibe-1 (rt s1 :current)) "but not as the vibe that played")
      (is (not (fx-on? s1 [1 3]))))))


;; Pulses: what tap reactions follow (plans/2026-11-18-vibe-mode/04 §7, "What a press writes")

(defn- pulse [state source] (get-in state [:playback :pulses source]))
(defn- pulse-of [state source] (select-keys (pulse state source) [:id :held?]))

(deftest role-press-and-release-write-pulses-test
  (let [s0 (assoc-in (starter) [:playback :pulses :hit] {:id 11 :held? false})
        s1 (press s0 :hit 1000)]
    (is (= {:id 12 :held? true :src :input} (pulse s1 :hit)) "HIT press, the count was 11")
    (is (= {:id 12 :held? false} (pulse (release s1 :hit 1090) :hit)) "its release")
    (is (= {:id 12 :held? false} (pulse (release s1 :hit 1400) :hit)) "a hold's release too")
    (is (= {:id 1 :held? true :src :pad} (pulse (:state (engine/press s0 {:role :accent :time 1000 :src :pad})) :accent)))
    (testing "FLASH, held 2 beats"
      (let [s2 (press s0 :flash 1000)]
        (is (= {:id 1 :held? true} (pulse-of s2 :flash)))
        (is (= {:id 1 :held? false} (pulse-of (release s2 :flash 2000) :flash)))))
    (testing "a release with nothing held changes nothing"
      (is (= s0 (release s0 :hit 1000))))
    (testing "release-all lets go of the pulses its source held, as of the holds"
      (let [s (-> s0 (press :hit 1000) (#(:state (engine/press % {:role :flash :time 1010 :src :pad}))))
            pad (:state (engine/release-all s {:only :pad :time 1030}))]
        (is (= [true false] [(:held? (pulse pad :hit)) (:held? (pulse pad :flash))]))
        (is (= [false false] (mapv #(:held? (pulse (:state (engine/release-all pad {:time 1040})) %)) [:hit :flash])))))))

(deftest every-vibe-switch-pulses-test
  (let [s0 (starter)]
    (testing "a VIBE tap"
      (let [s1 (-> s0 (press :vibe 1000) (release :vibe 1200))]
        (is (= :vibe-2 (rt s1 :current)))
        (is (= {:id 1} (pulse s1 :vibe)))
        (is (= {:id 2} (pulse (-> s1 (press :vibe 1300) (release :vibe 1400)) :vibe)) "and the next, which wraps")))
    (testing ":vibe/select, once per change"
      (let [s1 (:state (engine/select s0 {:vibe :vibe-2 :time 1000}))]
        (is (= {:id 1} (pulse s1 :vibe)))
        (is (= s1 (:state (engine/select s1 {:vibe :vibe-2 :time 1100}))) "the vibe playing: no change, no pulse")))
    (testing "the first press makes the first vibe current: not a change of the performer's"
      (is (nil? (pulse (press s0 :hit 1000) :vibe))))
    (testing "a tap with one vibe goes nowhere"
      (let [one (assoc-in s0 [:config :vibes :order] [:vibe-1])]
        (is (nil? (pulse (-> one (press :hit 900) (press :vibe 1000) (release :vibe 1100)) :vibe)))))))

(deftest lock-toggle-writes-no-pulse-test
  (let [s1 (-> (starter) (tap :hit 900))
        s2 (-> s1 (press :vibe 1000) (release :vibe 1700))]
    (is (= :locked (rt s2 :rotation)))
    (is (= (get-in s1 [:playback :pulses]) (get-in s2 [:playback :pulses])))))

(deftest blackout-writes-no-pulse-test
  (let [s0 (-> (starter) (tap :accent 900) (press :hit 1000) (press :flash 1100))
        {:keys [state]} (engine/press s0 {:role :blackout :time 1200})]
    (is (= (update-vals (get-in s0 [:playback :pulses]) :id) (update-vals (get-in state [:playback :pulses]) :id))
        "BLACKOUT is no press")
    (is (not-any? #(:held? (pulse state %)) [:hit :accent :flash]) "it lets go of every hold, as of the engine's")
    (testing "invariant 9: no press pulses during blackout, nor a vibe change"
      (let [s (-> state (press :hit 1300) (press :flash 1310) (press :vibe 1320) (release :vibe 1330)
                  (#(:state (engine/select % {:vibe :vibe-2 :time 1340}))))]
        (is (= :vibe-2 (rt s :current)))
        (is (= (get-in state [:playback :pulses]) (get-in s [:playback :pulses])))
        (is (= (get-in state [:playback :pulses]) (get-in (press s :blackout 1400) [:playback :pulses]))
            "nor BLACKOUT going off")))))

(deftest empty-pool-still-pulses-test
  (testing "a press that plays nothing still moves its reactions, and its release ends its hold"
    (let [s0 (-> (starter)
                 (with-vibe :vibe-1 {:hit {:cells [] :select :shuffle :mode :decay :beats 1.0}
                                     :flash {:cue nil :fx [] :solo? true}}))
          {:keys [state dispatch]} (engine/press s0 {:role :hit :time 1000})]
      (is (= {:role :hit :why :empty-pool} (rt state :problem)))
      (is (nil? (rt state :held :hit)) "the engine holds nothing")
      (is (= {:id 1 :held? true :src :input} (pulse state :hit)))
      (is (= {:event/type :timing/tap-tempo :input-time 1000} dispatch) "and HIT taps the tempo (V3)")
      (is (= {:id 1 :held? false} (pulse (release state :hit 1050) :hit)))
      (let [s (press s0 :flash 1000)]
        (is (= {:role :flash :why :empty-pool} (rt s :problem)))
        (is (= {:id 1 :held? false} (pulse (release s :flash 3000) :flash)) "FLASH the same")))))

(deftest no-vibes-still-pulses-test
  (testing "with no vibes at all, HIT still pulses and taps: a Grid-tab player can bind HIT to move effects"
    (let [none (assoc-in (starter) [:config :vibes] library/empty-library)
          {:keys [state dispatch]} (engine/press none {:role :hit :time 1000 :src :pad})]
      (is (= {:why :no-vibes} (rt state :problem)))
      (is (= {:id 1 :held? true :src :pad} (pulse state :hit)))
      (is (= {:event/type :timing/tap-tempo :input-time 1000} dispatch))
      (is (= {:id 1 :held? false} (pulse (release state :hit 1100) :hit)))
      (is (= {:id 1 :held? false} (pulse (release (press none :accent 1000) :accent 1100) :accent))))))


;; One tap tempo (plans/2026-11-18-vibe-mode/06, V3 A)

(deftest hit-skips-the-tap-while-link-sync-is-on-test
  (let [linked (update-in (starter) [:backend :link] assoc :link-enabled? true :sync-enabled? true)
        {:keys [state dispatch]} (engine/press linked {:role :hit :time 1000})
        button (fn [s t] (:state (handlers/handle-event {:event/type :timing/tap-tempo :time t :state s})))]
    (is (nil? dispatch) "invariant 8: with Link sync on the tempo is Link's, not the hits'")
    (is (= :timing/tap-tempo
           (get-in (engine/press (assoc-in linked [:backend :link :link-enabled?] false)
                                 {:role :hit :time 1000}) [:dispatch :event/type]))
        "turning Link off restores HIT tap tempo even with the sync preference still checked")
    (is (some? (rt state :voices :hit)) "the press plays as ever")
    (is (= {:id 1 :held? true :src :input} (pulse state :hit)))
    (is (= (:timing linked) (:timing (-> linked (button 1000) (button 1500))))
        "nor does the TAP button: following Link, a tap does nothing")
    (is (= [1000 1500] (get-in (-> linked (assoc-in [:backend :link :link-enabled?] false) (button 1000) (button 1500))
                               [:timing :tap :taps]))
        "with Link off it taps again")))

(deftest hit-skips-the-tap-when-the-setting-is-off-test
  (let [{:keys [state dispatch]} (engine/press (assoc-in (starter) [:config :vibes :settings :hit-taps-tempo?] false)
                                               {:role :hit :time 1000})]
    (is (nil? dispatch))
    (is (some? (rt state :voices :hit)) "the press plays as ever")
    (is (= {:event/type :timing/tap-tempo :input-time 1000} (:dispatch (engine/press (starter) {:role :hit :time 1000})))
        "on, the default, it taps")))


;; Zone patterns that can reach the crowd (zones plan Z5, 07 §4.7, V10 A)

(defn- crowd-rig
  "starter with p1, and p5 scanning the crowd, out of All (so the cues,
   drawn to All, can't reach it); [2 3] a Chase over P1 and P5, off."
  []
  (-> (starter)
      (assoc :projectors {:p1 {:name "P1" :host "10.0.0.1" :zone-groups [] :enabled? true}
                          :p5 {:name "P5" :host "10.0.0.5" :zone-groups [] :tags #{:crowd-scanning} :enabled? true
                               :exclude-from-all? true}})
      (assoc-in [:chains :effect-chains [2 3]]
                {:active? false :items [(assoc-in (zone-patterns/preset-instance :chase :cell) [:params :zones]
                                                  [:auto.laser/p1 :auto.laser/p5])]})))

(deftest audience-pattern-cell-is-not-switched-on-test
  (let [s (-> (crowd-rig)
              (with-vibe :vibe-1 {:fx [[0 3] [2 3]]})
              (tap :hit 1000))]
    (is (fx-on? s [0 3]) "the rest of the FX set comes on")
    (is (not (fx-on? s [2 3])) "the pattern that can reach the crowd doesn't")
    (is (= [[0 3]] (rt s :fx-on)) "and isn't the set's")
    (testing "a vibe change to it, and back"
      (let [s (-> (crowd-rig)
                  (with-vibe :vibe-2 {:fx [[2 3]]})
                  (tap :hit 1000)
                  (tap :vibe 2000))]
        (is (= :vibe-2 (rt s :current)))
        (is (not (fx-on? s [2 3])))))))

(deftest allowed-audience-cell-switches-on-test
  (let [s (-> (crowd-rig)
              (with-vibe :vibe-1 {:fx [[0 3] [2 3]] :allow-audience? true})
              (tap :hit 1000))]
    (is (fx-on? s [2 3]))
    (is (= [[0 3] [2 3]] (rt s :fx-on)))
    (is (nil? (rt s :problem))))
  (testing "allowing it while the vibe plays switches it on; taking that back switches it off"
    (let [s0 (-> (crowd-rig) (with-vibe :vibe-1 {:fx [[2 3]]}) (tap :hit 1000))
          allow (fn [s v] (edited s 1500 assoc-in [:library :vibe-1 :allow-audience?] v))]
      (is (not (fx-on? s0 [2 3])))
      (let [s1 (allow s0 true)]
        (is (fx-on? s1 [2 3]))
        (is (not (fx-on? (allow s1 false) [2 3])))))))

(deftest refused-cell-is-reported-test
  (testing "the FX set's"
    (let [s (-> (crowd-rig) (with-vibe :vibe-1 {:fx [[2 3]]}) (tap :hit 1000))]
      (is (= {:why :audience-fx :cell [2 3]} (rt s :problem)))
      (is (some? (rt s :voices :hit)) "HIT played its cue all the same")))
  (testing "the flash's: its cue plays, the pattern stays off, and no pad says it had nothing to play"
    (let [s (-> (crowd-rig)
                (with-vibe :vibe-1 {:flash {:cue [6 0] :fx [[0 3] [2 3]] :solo? true}})
                (press :flash 1000))]
      (is (some? (intent s [6 0])))
      (is (fx-on? s [0 3]))
      (is (not (fx-on? s [2 3])))
      (is (= {:why :audience-fx :cell [2 3]} (rt s :problem)))))
  (testing "a flash of the refused cell only: nothing to play, and why"
    (let [s (-> (crowd-rig)
                (with-vibe :vibe-1 {:flash {:cue nil :fx [[2 3]] :solo? true}})
                (press :flash 1000))]
      (is (not (fx-on? s [2 3])))
      (is (= {:why :audience-fx :cell [2 3]} (rt s :problem))))))

(deftest manual-toggle-is-not-blocked-test
  (let [s (-> (crowd-rig) (with-vibe :vibe-1 {:fx [[2 3]]}) (tap :hit 1000))
        on (:state (handlers/handle-event {:event/type :effects/toggle-cell :col 2 :row 3 :state s}))]
    (is (fx-on? on [2 3]) "a click on the Effects grid is a decision: it isn't blocked")))
