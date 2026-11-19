(ns laser-show.vibes.engine-test
  "laser-show.vibes.engine: one test per row of the press-and-release table
   (plans/2026-11-18-vibe-mode/01 §7), then the beyond-vibe cases. States are
   the starter cues and vibes; times are the events' own (ms)."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.events.handlers :as handlers]
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
    (is (= {:event/type :timing/tap-tempo} dispatch))
    (testing "no tap without the setting, nor while Link sync is on (V3)"
      (is (nil? (:dispatch (engine/press (assoc-in (starter) [:config :vibes :settings :hit-taps-tempo?] false)
                                         {:role :hit :time 1000}))))
      (is (nil? (:dispatch (engine/press (assoc-in (starter) [:backend :link :sync-enabled?] true)
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
      (is (= (assoc-in empty [:vibe-runtime :problem] {:role :hit :why :empty-pool}) s2)
          "nothing changes but :problem: nothing plays, nothing is held")
      (is (nil? (:dispatch (engine/press empty {:role :hit :time 1000}))) "no tap either")
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
        ended (:state (handlers/handle-event {:event/type :show-clock/cue-ended :cell cell :run run :state s1}))]
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
    (is (= {:event/type :timing/tap-tempo} dispatch) "the press still taps")
    (is (= {[0 0] [:sustain]} (let [s (release state :flash 1200)]
                                {(rt s :voices :hit :cell) [(rt s :voices :hit :mode)]}))
        "it picks and starts when the flash lets go")
    (testing "a :decay press is lost, and leaves the bag as it was"
      (let [s1 (-> (starter) (tap :hit 0) (press :flash 1000))
            {:keys [state dispatch]} (engine/press s1 {:role :hit :time 1100})
            s (-> state (release :hit 1150) (release :flash 1200))]
        (is (= (rt s1 :bags) (rt state :bags)))
        (is (= {:at 1100 :src :input} (rt state :held :hit)))
        (is (= {:event/type :timing/tap-tempo} dispatch))
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
