(ns laser-show.vibes.rotation-test
  "laser-show.vibes.rotation/due, what the show clock sends when
   (plans/2026-11-18-vibe-mode/06 §7): settings 45 s and 2 beats, 120 BPM (a
   beat is 500 ms), three vibes, auto on; vibe-1 entered at 0."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.state.domains :as domains]
            [laser-show.state.templates :as templates]
            [laser-show.vibes.rotation :as rotation])
  (:import [com.sun.management ThreadMXBean]
           [java.lang.management ManagementFactory]))

(defn- world
  "The starter vibes and a third, Build, with the vibe runtime merged with
   rt."
  ([] (world {}))
  ([rt]
   (let [s (-> (domains/build-initial-state)
               (templates/apply-starter-cue-chains)
               (templates/apply-starter-vibes)
               (assoc-in [:timing :bpm] 120.0))]
     (-> s
         (assoc-in [:config :vibes :library :vibe-3]
                   (assoc (get-in s [:config :vibes :library :vibe-2]) :id :vibe-3 :name "Build"))
         (update-in [:config :vibes :order] conj :vibe-3)
         (update :vibe-runtime merge {:current :vibe-1 :entered-at 0 :rotation :active :auto? true} rt)))))

(defn- due [state now-ms] (rotation/due state now-ms 120.0))

(def ^:private arm {:event/type :vibe/arm :from :vibe-1})
(def ^:private advance {:event/type :vibe/advance :reason :gap :from :vibe-1})

(deftest armed-after-the-duration-test
  (is (nil? (due (world) 44000)))
  (is (nil? (due (world) 44999)))
  (is (= arm (due (world) 45000)))
  (is (= arm (due (world) 100000)) "every tick, until app state has heard it")
  (testing "the vibe's own entry time, and the saved :seconds"
    (is (= {:event/type :vibe/arm :from :vibe-2} (due (world {:current :vibe-2 :entered-at 10000}) 55000)))
    (is (nil? (due (world {:entered-at 10000}) 54999)))
    (is (= arm (due (assoc-in (world) [:config :vibes :settings :rotation :seconds] 30) 30000))))
  (testing "a project saved before the setting, or with one that is no number: the defaults"
    (is (= arm (due (update-in (world) [:config :vibes :settings] dissoc :rotation) 45000)))
    (is (= arm (due (assoc-in (world) [:config :vibes :settings :rotation] {:seconds "45" :pause-beats nil}) 45000)))))

(deftest gap-advances-after-the-pause-test
  (let [armed (world {:rotation :armed :last-hit-at 10000})]
    (is (nil? (due armed 10900)) "less than 2 beats (1000 ms) since the last HIT")
    (is (= advance (due armed 11000)))
    (testing "the beats are the tempo's"
      (is (nil? (rotation/due armed 11999 60.0)) "at 60 BPM, two beats are 2 s")
      (is (= advance (rotation/due armed 12000 60.0))))
    (testing "and as many as :pause-beats says"
      (is (nil? (due (assoc-in armed [:config :vibes :settings :rotation :pause-beats] 4.0) 11999)))
      (is (= advance (due (assoc-in armed [:config :vibes :settings :rotation :pause-beats] 4.0) 12000))))
    (testing "no HIT yet: the gap is there"
      (is (= advance (due (world {:rotation :armed}) 45000))))))

(deftest held-key-defers-the-switch-test
  (testing "a key down defers the switch (beyond-vibe phrase_gap_waits_for_flash_release)"
    (doseq [role [:hit :accent :flash :vibe]]
      (is (nil? (due (world {:rotation :armed :last-hit-at 0 :held {role {:at 0 :src :input}}}) 5000))
          (str (name role) " held")))
    (testing "also a press that played nothing, which only its pulse holds"
      (doseq [role [:hit :accent :flash]]
        (is (nil? (due (assoc-in (world {:rotation :armed}) [:playback :pulses role] {:id 1 :held? true :src :input}) 5000))
            (str (name role) "'s pulse"))))
    (is (= advance (due (assoc-in (world {:rotation :armed}) [:playback :pulses :hit] {:id 1 :held? false}) 5000))
        "let go: the next tick switches")))

(deftest locked-never-advances-test
  (doseq [now [0 45000 1000000]]
    (is (nil? (due (world {:rotation :locked}) now)) (str now))
    (is (nil? (due (world {:rotation :locked :last-hit-at 0}) now)))))

(deftest auto-off-never-advances-test
  (doseq [rotation [:active :armed]
          now [45000 1000000]]
    (is (nil? (due (world {:rotation rotation :auto? false}) now)) (str rotation " " now)))
  (testing "nil until the switch is used: the saved :mode, off by default (V4 A)"
    (is (nil? (due (world {:auto? nil}) 1000000)))
    (is (= arm (due (assoc-in (world {:auto? nil}) [:config :vibes :settings :rotation :mode] :auto) 45000)))
    (is (nil? (due (assoc-in (world {:auto? false}) [:config :vibes :settings :rotation :mode] :auto) 45000))
        "the switch wins")))

(deftest single-vibe-never-advances-test
  (let [one (assoc-in (world) [:config :vibes :order] [:vibe-1])]
    (is (nil? (due one 1000000)))
    (is (nil? (due (assoc-in one [:vibe-runtime :rotation] :armed) 1000000))))
  (testing "nor before a vibe plays, nor while one that went (an undo) is still named"
    (is (nil? (due (world {:current nil :entered-at nil}) 1000000)))
    (is (nil? (due (world {:current :gone}) 1000000)))))

(deftest blackout-never-advances-test
  (doseq [rotation [:active :armed]]
    (is (nil? (due (world {:rotation rotation :blackout? true}) 1000000)) (str rotation))))

(defn- allocated-bytes ^long []
  (.getCurrentThreadAllocatedBytes ^ThreadMXBean (ManagementFactory/getThreadMXBean)))

(deftest due-allocates-nothing-when-idle-test
  (testing "the clock asks on every tick: when nothing is due, the check is comparisons only
            (invariant 5; bytes this thread allocated over 10,000 calls, a count, not a timing)"
    (doseq [[what state now] [["auto off (the saved :mode)" (world {:auto? nil}) 1000000]
                              ["active, not due yet" (world) 44000]
                              ["armed, a key down" (world {:rotation :armed :held {:hit {:at 0 :src :input}}}) 50000]
                              ["armed, a press that played nothing held" (assoc-in (world {:rotation :armed}) [:playback :pulses :flash] {:id 1 :held? true}) 50000]
                              ["armed, inside the gap" (world {:rotation :armed :last-hit-at 49500}) 50000]
                              ["locked" (world {:rotation :locked}) 1000000]
                              ["blackout" (world {:blackout? true}) 1000000]
                              ["one vibe" (assoc-in (world) [:config :vibes :order] [:vibe-1]) 1000000]
                              ["no vibe yet" (world {:current nil :entered-at nil}) 1000000]]]
      (is (nil? (due state now)) what)
      (dotimes [_ 100] (due state now))
      (allocated-bytes)
      (let [before (allocated-bytes)]
        (dotimes [_ 10000] (due state now))
        (let [bytes (- (allocated-bytes) before)]
          (is (< bytes 4096) (str what ": " bytes " bytes")))))))
