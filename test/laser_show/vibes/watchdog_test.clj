(ns laser-show.vibes.watchdog-test
  "laser-show.vibes.watchdog: a role held for :max-hold-secs or longer is let
   go of, as a stuck key (plans/2026-11-18-vibe-mode/07 §7, the watchdog
   table). The starter cues and vibes, the limit 60 s; presses at 1000 ms,
   through the engine as the pipeline would hand them on."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.state.domains :as domains]
            [laser-show.state.templates :as templates]
            [laser-show.vibes.engine :as engine]
            [laser-show.vibes.watchdog :as watchdog])
  (:import [com.sun.management ThreadMXBean]
           [java.lang.management ManagementFactory]))

(defn- starter []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-vibes)
      (assoc-in [:timing :bpm] 120.0)
      (assoc-in [:config :vibes :settings :hit-taps-tempo?] false)))

(defn- press [state role t] (:state (engine/press state {:role role :time t})))
(defn- release [state role t] (:state (engine/release state {:role role :time t})))

(defn- report [role] {:event/type :vibe/release :role role :reason :watchdog})

(defn- handle
  "state after the watchdog's report for role, handled at t."
  [state rep t]
  (:state (engine/release state (assoc rep :time t))))

(defn- rt [state & ks] (get-in state (into [:vibe-runtime] ks)))
(defn- intent [state cell] (get-in state [:playback :active-cues cell]))

(deftest stuck-hold-is-released-test
  (let [held (press (starter) :hit 1000)
        {:keys [cell run]} (rt held :voices :hit)]
    (testing "HIT (:decay) held 59 s: nothing"
      (is (nil? (watchdog/due held 60000 nil))))
    (testing "held 60 s or more: one report"
      (is (= [(report :hit)] (watchdog/due held 61000 nil)))
      (is (= [(report :hit)] (watchdog/due held 62000 nil)) "every tick, until app state has heard it"))
    (testing "handled: the hold ends as a cut, and the pad says why"
      (let [s (handle held (report :hit) 61000)]
        (is (nil? (rt s :held :hit)))
        (is (nil? (intent s cell)) "held a minute: the cue stops, as a long hold's release does")
        (is (not= run (get-in s [:playback :active-cues cell :run])))
        (is (false? (get-in s [:playback :pulses :hit :held?])) "its pulse is let go")
        (is (= {:role :hit :why :stuck-key} (rt s :problem)))
        (is (nil? (watchdog/due s 70000 nil)) "nothing more to report")))
    (testing "FLASH held 61 s: the flash ends, and what its solo flash stepped aside comes back"
      (let [s (-> (starter)
                  (assoc-in [:config :vibes :library :vibe-1 :hit :mode] :sustain)
                  (press :hit 1000) (release :hit 1050)
                  (press :flash 2000))
            hit-cell (get-in s [:vibe-runtime :duck :hit :cell])]
        (is (some? hit-cell) "the solo flash stepped the HIT aside")
        (is (= [(report :flash)] (watchdog/due s 62000 nil)))
        (let [s' (handle s (report :flash) 62000)]
          (is (nil? (rt s' :voices :flash)))
          (is (nil? (intent s' [6 0])) "the flash cue stops")
          (is (some? (intent s' hit-cell)) "the HIT it stepped aside plays again")
          (is (= {:role :flash :why :stuck-key} (rt s' :problem))))))
    (testing "VIBE held 61 s: let go of without a lock or a switch"
      (let [s (press (starter) :vibe 1000)
            s' (handle s (report :vibe) 62000)]
        (is (= [(report :vibe)] (watchdog/due s 62000 nil)))
        (is (nil? (rt s' :held :vibe)))
        (is (= :active (rt s' :rotation)) "a minute is a hold, but a stuck key doesn't lock")
        (is (= (rt s :current) (rt s' :current)) "nor switch")
        (is (= {:role :vibe :why :stuck-key} (rt s' :problem)))))
    (testing "the limit is the project's :max-hold-secs; one that is no positive number is 60"
      (let [at (fn [secs] (assoc-in held [:config :vibes :settings :max-hold-secs] secs))]
        (is (= [(report :hit)] (watchdog/due (at 10) 11000 nil)))
        (is (nil? (watchdog/due (at 10) 10999 nil)))
        (is (nil? (watchdog/due (at "10") 11000 nil)))
        (is (nil? (watchdog/due (at 0) 11000 nil)))
        (is (= [(report :hit)] (watchdog/due (update-in held [:config :vibes :settings] dissoc :max-hold-secs) 61000 nil))
            "a project saved before the setting")))))

(deftest sustain-is-not-a-hold-test
  (testing "a :sustain HIT let go of plays for 5 minutes: no key is down"
    (let [s (-> (starter)
                (assoc-in [:config :vibes :library :vibe-1 :hit :mode] :sustain)
                (press :hit 1000) (release :hit 1050))]
      (is (some? (intent s (rt s :voices :hit :cell))))
      (is (nil? (watchdog/due s (+ 1000 (* 5 60000)) {:hit {:held? false :ms 300000.0}}))))))

(deftest released-once-test
  (let [held (press (starter) :hit 1000)
        s (handle held (report :hit) 61000)]
    (testing "the same report again, as the clock sends it after 250 ms while the agent is behind"
      (is (= s (handle s (report :hit) 61300))))
    (testing "a late one, for a hold that ended and began again since: the new hold stays"
      (let [again (-> held (release :hit 61000) (press :hit 61100))]
        (is (= again (handle again (report :hit) 61200)))))))

(deftest other-roles-untouched-test
  (let [s (-> (starter) (press :hit 1000) (press :accent 52000))
        accent (rt s :voices :accent)]
    (is (= [(report :hit)] (watchdog/due s 61000 nil)) "ACCENT, held 9 s, isn't reported")
    (let [s' (handle s (report :hit) 61000)]
      (is (nil? (rt s' :held :hit)))
      (is (= {:at 52000 :src :input} (rt s' :held :accent)) "ACCENT's hold stays")
      (is (= accent (rt s' :voices :accent)))
      (is (some? (intent s' (:cell accent))) "and its cue plays on"))))

(deftest a-press-that-played-nothing-is-a-hold-too-test
  (testing "an effects-only HIT holds its pulse only: the clock's pulse view says how long"
    (let [s (-> (starter)
                (assoc-in [:config :vibes :library :vibe-1 :hit :cells] [])
                (press :hit 1000))
          id (get-in s [:playback :pulses :hit :id])
          rep (assoc (report :hit) :pulse id)]
      (is (nil? (rt s :held :hit)) "the engine holds nothing")
      (is (true? (get-in s [:playback :pulses :hit :held?])))
      (is (nil? (watchdog/due s 61000 {:hit {:held? true :ms 59000.0}})))
      (is (= [rep] (watchdog/due s 61000 {:hit {:held? true :ms 60000.0}})))
      (is (nil? (watchdog/due s 61000 nil)) "no pulse view, no pulses judged")
      (let [s' (handle s rep 61000)]
        (is (false? (get-in s' [:playback :pulses :hit :held?])))
        (is (= {:role :hit :why :stuck-key} (rt s' :problem))))
      (testing "a report for an earlier press of the same key changes nothing"
        (let [again (-> s (release :hit 2000) (press :hit 2100))]
          (is (= again (handle again rep 61000))))))))

(defn- allocated-bytes ^long []
  (.getCurrentThreadAllocatedBytes ^ThreadMXBean (ManagementFactory/getThreadMXBean)))

(deftest due-allocates-nothing-when-idle-test
  (testing "the clock asks on every tick: while no key is down, the check allocates nothing
            (bytes this thread allocated over 10,000 calls, a count, not a timing)"
    (doseq [[what state] [["a new project" (starter)]
                          ["a :sustain HIT playing, let go of" (-> (starter)
                                                                   (assoc-in [:config :vibes :library :vibe-1 :hit :mode] :sustain)
                                                                   (press :hit 1000) (release :hit 1050))]
                          ["presses let go of" (-> (starter) (press :hit 1000) (release :hit 1050) (press :flash 2000) (release :flash 2050))]]]
      (is (nil? (watchdog/due state 1000000 {:hit {:held? false :ms 1.0}})) what)
      (dotimes [_ 100] (watchdog/due state 1000000 nil))
      (allocated-bytes)
      (let [before (allocated-bytes)]
        (dotimes [_ 10000] (watchdog/due state 1000000 nil))
        (let [bytes (- (allocated-bytes) before)]
          (is (< bytes 4096) (str what ": " bytes " bytes")))))))
