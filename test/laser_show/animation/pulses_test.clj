(ns laser-show.animation.pulses-test
  "laser-show.animation.pulses: the stamps the show clock makes of presses,
   what effects see of them, Restart on, and the Envelope, Step and Energy
   maths (plans/2026-11-18-vibe-mode/04-phase4-tap-reactions.md §7)."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.pulses :as pulses]
            [laser-show.animation.show-time :as show-time]))

(defn- clock
  "A pulse clock at beats, 120 BPM."
  [beats]
  {:accumulated-beats beats :accumulated-ms (* 500.0 beats)})

(defn- close?
  ([expected actual] (close? expected actual 1e-9))
  ([expected actual tolerance]
   (and (number? actual) (< (Math/abs (- (double expected) (double actual))) tolerance))))

(defn- stamps-at
  "The stamps after each [app-pulses beats] step, in order."
  [& steps]
  (reduce (fn [stamps [app beats]] (pulses/stamp stamps app (clock beats))) {} steps))

(defn- pulse
  "What an effect sees of a press beats ago: the view of one press of source."
  [beats & {:keys [held? since-release count recent] :or {count 1}}]
  {:count count :beats beats :ms (* 500.0 beats) :held? (boolean held?)
   :since-release since-release :recent (or recent [beats])})


;; The stamps (§7, "What the clock stamps")

(deftest stamp-new-press-test
  (is (= {:hit {:id 1 :count 1 :press-beat 10.0 :press-ms 5000.0 :held? true :release-beat nil :recent [10.0]}}
         (pulses/stamp {} {:hit {:id 1 :held? true :src :input}} (clock 10.0))))
  (testing "a vibe change or a cue start has no hold and no release"
    (is (= {:vibe {:id 3 :count 3 :press-beat 2.0 :press-ms 1000.0 :held? false :release-beat nil :recent [2.0 2.0 2.0]}}
           (pulses/stamp {} {:vibe {:id 3}} (clock 2.0)))
        "three switches before the first stamp count three times")))

(deftest stamp-release-test
  (let [pressed (stamps-at [{:hit {:id 1 :held? true}} 10.0])
        released (pulses/stamp pressed {:hit {:id 1 :held? false}} (clock 10.2))]
    (is (= (assoc (:hit pressed) :held? false :release-beat 10.2) (:hit released)))
    (is (= released (pulses/stamp released {:hit {:id 1 :held? false}} (clock 10.3)))
        "a release already stamped: unchanged")
    (is (= pressed (pulses/stamp pressed {:hit {:id 1 :held? true}} (clock 10.3)))
        "still held: unchanged")))

(deftest two-presses-in-one-tick-count-twice-test
  (let [s (stamps-at [{:hit {:id 1 :held? true}} 10.0] [{:hit {:id 3 :held? true}} 11.0])]
    (is (= {:id 3 :count 3 :press-beat 11.0 :press-ms 5500.0 :held? true :release-beat nil :recent [11.0 11.0 10.0]}
           (:hit s)))
    (testing "invariant 10: the energy counts both"
      (let [cfg {:min 0.0 :max 1.0 :rise 0.25 :fall 0.25}
            view (:hit (pulses/view s (clock 11.0)))]
        (is (close? 0.5 (pulses/energy cfg view)) "two rises at once")))))

(deftest press-and-release-in-one-tick-test
  (let [s (stamps-at [{:hit {:id 1 :held? true}} 10.0]
                     [{:hit {:id 3 :held? true}} 11.0]
                     [{:hit {:id 4 :held? false}} 12.0])]
    (is (= {:id 4 :count 4 :press-beat 12.0 :press-ms 6000.0 :held? false :release-beat 12.0
            :recent [12.0 11.0 11.0 10.0]}
           (:hit s))
        "a pad that sends its note-off at once: pressed and let go at the same beat")
    (testing "a Swell plays its attack, then its release"
      (let [swell {:min 1.0 :max 1.4 :attack 0.5 :release 1.0 :curve :linear :hold? true}
            at (fn [beats] (pulses/envelope swell (:hit (pulses/view s (clock (+ 12.0 beats))))))]
        (is (close? 1.0 (at 0.0)))
        (is (close? 1.2 (at 0.25)) "half way up")
        (is (close? 1.4 (at 0.5)) "the top of the attack")
        (is (close? 1.2 (at 1.0)) "then half way down")
        (is (close? 1.0 (at 1.5)))))))

(deftest pulse-clock-ignores-restart-align-and-shift-test
  (testing "invariant 5: Stop, Retrigger, a tap's beat alignment and a Link shift never move a pulse's age"
    (let [tick (/ 1000.0 60)
          state (fn [requests] {:timing {:bpm 120.0}
                                :playback {:pulses {:hit {:id 1 :held? false}} :requests requests}})
          rt0 (reduce (fn [rt i] (:runtime (show-time/step rt (state {}) nil (* i tick))))
                      show-time/initial-runtime
                      (range 60))
          before (show-time/pulse-view rt0)
          rt1 (:runtime (show-time/step rt0 (state {:restart {:id 1} :align {:id 2} :shift {:id 3 :offset 0.5}})
                                        nil (* 60 tick)))
          after (show-time/pulse-view rt1)]
      (is (< (get-in rt1 [:global :accumulated-beats]) 0.05) "the global clock restarted (and ran one tick)")
      (is (= (:pulses rt0) (:pulses rt1)) "the stamps are as they were")
      (is (close? (+ (get-in before [:hit :beats]) (/ 1.0 30)) (get-in after [:hit :beats]))
          "the press one tick older, nothing else")
      (is (= 0.0 (get-in rt1 [:pulse-clock :phase-offset-target])) "no align or shift reached the pulse clock"))))


;; What effects see

(deftest view-ages-test
  (let [s (stamps-at [{:hit {:id 1 :held? true}} 10.0] [{:hit {:id 1 :held? false}} 10.2])
        {:keys [count beats ms held? since-release recent]} (:hit (pulses/view s (clock 10.5)))]
    (is (= [1 false] [count held?]))
    (is (close? 0.5 beats))
    (is (close? 250.0 ms))
    (is (close? 0.3 since-release))
    (is (= 1 (clojure.core/count recent)))
    (is (close? 0.5 (first recent))))
  (testing "a source with no press yet is absent, and so is :any without a role's press"
    (is (= {} (pulses/view {} (clock 3.0))))
    (is (= #{:vibe} (set (keys (pulses/view (stamps-at [{:vibe {:id 1}} 1.0]) (clock 3.0)))))))
  (testing "a stamp the clock seeded from app state (show-time/reset-runtime!) isn't a press"
    (is (= {} (pulses/view (pulses/seed {:hit {:id 7 :held? false}}) (clock 3.0))))
    (is (= 1 (:count (:hit (pulses/view (pulses/stamp (pulses/seed {:hit {:id 7}}) {:hit {:id 8 :held? true}} (clock 4.0))
                                        (clock 4.0)))))
        "the next press is the first")))

(deftest any-merges-the-three-roles-test
  (let [s (stamps-at [{:hit {:id 2 :held? false}} 1.0]
                     [{:hit {:id 2 :held? false} :accent {:id 1 :held? true}} 2.0]
                     [{:hit {:id 2 :held? false} :accent {:id 1 :held? true} :flash {:id 1 :held? false}} 2.5])
        {:keys [count beats held? since-release recent]} (:any (pulses/view (assoc s :vibe {:id 1 :count 1 :press-beat 2.9 :press-ms 0.0 :recent [2.9]})
                                                                            (clock 3.0)))]
    (is (= 4 count) "the counts summed (a vibe change isn't one of them)")
    (is (close? 0.5 beats) "the newest press's age: FLASH's")
    (is (false? held?))
    (is (close? 0.5 since-release))
    (is (= [5 10 20 20] (mapv #(Math/round (* 10.0 (double %))) recent))
        "the three :recent merged, newest first")))

(deftest recent-is-capped-at-16-test
  (let [s (reduce (fn [s i] (pulses/stamp s {:hit {:id i :held? false}} (clock (double i))))
                  {}
                  (range 1 31))]
    (is (= 30 (get-in s [:hit :count])))
    (is (= (mapv double (range 30 14 -1)) (get-in s [:hit :recent])) "the 16 newest, newest first")
    (is (= 16 (clojure.core/count (:recent (:hit (pulses/view (pulses/stamp {} {:hit {:id 40}} (clock 1.0)) (clock 1.0))))))
        "40 presses in one tick: 16 kept")))

(deftest forgotten-source-test
  (testing "a source app state no longer has (a new project) is forgotten"
    (is (= {} (pulses/stamp (stamps-at [{:hit {:id 3 :held? false}} 1.0]) {} (clock 2.0))))))


;; Restart on (§7, "Restart on")

(deftest rebase-test
  (let [ctx {:accumulated-beats 9.0 :effective-beats 9.25 :accumulated-ms 4500.0 :time-ms 4500.0
             :phase-offset 0.25 :bpm 120.0 :pulses {:hit (pulse 0.5) :vibe (pulse 2.0 :count 3)}}]
    (testing "beats and ms since the press, no phase glide, and the press count"
      (is (= {:accumulated-beats 0.5 :effective-beats 0.5 :accumulated-ms 250.0 :time-ms 250.0
              :phase-offset 0.0 :pulse-count 1}
             (select-keys (pulses/rebase ctx :hit true) [:accumulated-beats :effective-beats :accumulated-ms
                                                          :time-ms :phase-offset :pulse-count])))
      (is (= 3 (:pulse-count (pulses/rebase ctx :vibe false))))
      (is (= [120.0 (:pulses ctx)] ((juxt :bpm :pulses) (pulses/rebase ctx :hit true))) "the rest is kept"))
    (testing "no press yet: a loop runs as it would without Restart on"
      (is (identical? ctx (pulses/rebase ctx :accent true))))
    (testing "no press yet: a once-mode one (or a Decay) is done"
      (let [done (pulses/rebase ctx :accent false)]
        (is (< 1.0e6 (:accumulated-beats done)))
        (is (< 1.0e6 (:accumulated-ms done)))
        (is (= 0 (:pulse-count done)))))))

(deftest no-press-yet-is-at-rest-test
  (testing "invariant 2: before a source's first press every Envelope, Step and Energy sits at rest"
    (is (= 1.0 (pulses/envelope {:min 1.0 :max 1.25 :release 0.5 :curve :exp :hold? false} nil)))
    (is (= 1.0 (pulses/envelope {:min 1.0 :max 0.0 :release 1.0 :curve :linear :hold? false} nil)) "Fade in: full")
    (is (= 0.0 (pulses/step-value {:min 0.0 :max 360.0 :steps 8 :wrap? true :order :up :glide 0.25} nil)))
    (is (= 1.0 (pulses/step-value {:min -1.0 :max 1.0 :steps 2 :order :down} nil)) "Flip: as it is")
    (is (close? 0.0 (pulses/step-value {:min -0.4 :max 0.4 :steps 9 :order :scatter} nil)) "Scatter: centred")
    (is (= 1.0 (pulses/energy {:min 1.0 :max 1.4 :rise 0.25 :fall 0.25} nil)))))


;; Envelope (§7)

(deftest envelope-examples-test
  (let [punch {:min 1.0 :max 1.25 :attack 0.0 :release 0.5 :curve :exp :hold? false}
        swell {:min 1.0 :max 1.4 :attack 0.5 :release 1.0 :curve :linear :hold? true}]
    (testing "Punch size"
      (is (close? 1.25 (pulses/envelope punch (pulse 0.0 :held? true))))
      (is (close? 1.091 (pulses/envelope punch (pulse 0.1)) 5e-4))
      (is (close? 1.019 (pulses/envelope punch (pulse 0.25)) 5e-4))
      (is (close? 1.0 (pulses/envelope punch (pulse 0.5))))
      (is (close? 1.0 (pulses/envelope punch (pulse 3.0 :held? true))) "no hold: held or not"))
    (testing "Swell while FLASH is held"
      (is (close? 1.2 (pulses/envelope swell (pulse 0.25 :held? true))))
      (is (close? 1.4 (pulses/envelope swell (pulse 2.0 :held? true))))
      (is (close? 1.2 (pulses/envelope swell (pulse 2.5 :since-release 0.5)))))))

(deftest envelope-release-during-attack-test
  (let [swell {:min 1.0 :max 1.4 :attack 0.5 :release 1.0 :curve :linear :hold? true}]
    (is (close? 1.24 (pulses/envelope swell (pulse 0.3 :since-release 0.1))) "let go at 0.2: the attack goes on")
    (is (close? 1.2 (pulses/envelope swell (pulse 1.0 :since-release 0.8)))
        "the attack ends at 0.5, then the release runs")))

(deftest envelope-peak-below-rest-test
  (let [fade-in {:min 1.0 :max 0.0 :attack 0.0 :release 1.0 :curve :linear :hold? false}]
    (is (close? 0.0 (pulses/envelope fade-in (pulse 0.0))) "a switch 0 beats ago: dark")
    (is (close? 0.25 (pulses/envelope fade-in (pulse 0.25))))
    (is (close? 1.0 (pulses/envelope fade-in (pulse 1.0))))
    (is (close? 1.0 (pulses/envelope fade-in (pulse 4.0))))))


;; Step (§7)

(defn- steps
  "The Step's value at counts 0 to 8, 1 beat after each press (no glide)."
  [cfg]
  (mapv #(pulses/step-value cfg (when (pos? %) (pulse 1.0 :count %))) (range 9)))

(defn- rounded [xs] (mapv #(/ (Math/round (* 10.0 (double %))) 10.0) xs))

(deftest step-orders-test
  (is (= [0.0 45.0 90.0 135.0 180.0 225.0 270.0 315.0 0.0] (steps {:min 0 :max 360 :steps 8 :wrap? true :order :up}))
      "Step spin")
  (is (= [0.0 60.0 120.0 180.0 0.0 60.0 120.0 180.0 0.0] (steps {:min 0 :max 180 :steps 4 :order :up}))
      "Step colour")
  (is (= [0.0 1.0 2.0 3.0 2.0 1.0 0.0 1.0 2.0] (steps {:min 0 :max 3 :steps 4 :order :ping-pong})))
  (is (= [1.0 -1.0 1.0 -1.0 1.0 -1.0 1.0 -1.0 1.0] (steps {:min -1 :max 1 :steps 2 :order :down})) "Flip")
  (is (= [0.0 -0.4 0.2 -0.2 0.4 0.0 -0.3 0.2] (subvec (rounded (steps {:min -0.4 :max 0.4 :steps 9 :order :scatter})) 0 8))
      "Scatter")
  (testing "the index by itself, and its edges"
    (is (= [0 1 0 1] (mapv #(pulses/step-index :scatter 2 %) (range 4))) "two positions: as :up")
    (is (= [0 0 0] (mapv #(pulses/step-index :ping-pong 1 %) (range 3))) "one position: the one value")))

(deftest step-palette-test
  (is (= [200.0 215.0 320.0 200.0 215.0 320.0 200.0 215.0 320.0] (steps {:values [200 215 320] :order :up :min 0 :max 1 :steps 4}))
      "the palette's values in the order typed; :min, :max and :steps aren't used")
  (is (= [0.0 90.0 270.0 90.0 0.0 90.0 270.0 90.0 0.0] (steps {:values [0 90 270] :order :ping-pong}))))

(deftest step-glide-and-wrap-test
  (let [spin {:min 0 :max 360 :steps 8 :wrap? true :order :up :glide 0.25}]
    (is (close? 337.5 (pulses/step-value spin (pulse 0.125 :count 8)))
        "half way from 315 forward to 360 (= 0), the shorter way round")
    (is (close? 0.0 (pulses/step-value spin (pulse 0.25 :count 8))) "the glide over")
    (is (close? 45.0 (pulses/step-value spin (pulse 0.0 :count 2))) "a glide starts where the last press left it"))
  (testing "without wrap, a glide goes the straight way"
    (is (close? 90.0 (pulses/step-value {:min 0 :max 180 :steps 4 :order :up :glide 1.0} (pulse 0.5 :count 4)))
        "from 180 down to 0"))
  (testing "a palette on a circle of 360: hues glide the short way"
    (is (close? 345.0 (pulses/step-value {:values [330 0] :wrap? true :order :up :glide 1.0} (pulse 0.5 :count 3)))
        "from 330 on to 0 (= 360) through 345, not back through 165")))

(deftest scatter-never-repeats-test
  (testing "invariant 6: 3 or more steps never give the same position twice in a row"
    (doseq [n (range 3 17)]
      (let [idx (mapv #(pulses/step-index :scatter n %) (range 10001))]
        (is (not-any? true? (map = idx (rest idx))) (str n " steps"))
        (is (every? #(< -1 % n) idx))))))


;; Energy (§7)

(defn- taps
  "The view of n presses every gap beats, the newest age beats ago."
  [n gap age]
  (let [recent (mapv #(+ age (* gap %)) (range n))]
    (pulse age :count n :recent recent)))

(deftest energy-examples-test
  (let [cfg {:min 1.0 :max 1.4 :rise 0.25 :fall 0.25}]
    (is (close? 1.0 (pulses/energy cfg nil)) "none")
    (is (close? 1.1 (pulses/energy cfg (taps 1 1.0 0.0))) "one")
    (is (close? 1.0 (pulses/energy cfg (taps 1 1.0 1.0))) "back to rest a beat later")
    (is (close? 1.1 (pulses/energy cfg (taps 6 1.0 0.0))) "one per beat, at each tap")
    (is (close? 1.0 (pulses/energy cfg (taps 6 1.0 0.999)) 1e-3) "just before the next")
    (is (close? 1.25 (pulses/energy cfg (taps 4 0.5 0.0))) "one per half beat, the 4th")
    (is (close? 1.4 (pulses/energy cfg (taps 5 0.25 0.0))) "one per quarter beat, the 5th")
    (is (close? 1.4 (pulses/energy cfg (taps 12 0.25 0.0))) "and later: full")
    (testing "a press after a long pause starts again from rest, as the first did"
      (is (close? 1.1 (pulses/energy cfg (pulse 0.0 :count 2 :recent [0.0 8.0])))))))


;; The Zone step key (zones plan Z5, 07 §4.2): one more source

(deftest zone-step-is-stamped-and-viewed-test
  (is (= [:hit :accent :flash :vibe :any :cue :zone-step] pulses/sources))
  (let [s (stamps-at [{:zone-step {:id 1}} 10.0] [{:zone-step {:id 3}} 11.0])
        view (pulses/view s (clock 11.5))]
    (is (= {:id 3 :count 3 :press-beat 11.0 :press-ms 5500.0 :held? false :release-beat nil :recent [11.0 11.0 10.0]}
           (:zone-step s))
        "stamped as any source: two presses since the last tick count twice")
    (is (= 3 (get-in view [:zone-step :count])))
    (is (close? 0.5 (get-in view [:zone-step :beats])) "its age on the pulse clock")
    (is (nil? (get-in view [:zone-step :since-release])) "no release, as a vibe change or a cue start")
    (testing "a restarted clock seeds it, so it isn't pressed again"
      (is (= {:zone-step {:id 7}} (pulses/seed {:zone-step {:id 7}}))))))

(deftest zone-step-is-not-in-any-test
  (let [view (pulses/view (stamps-at [{:hit {:id 2 :held? false} :zone-step {:id 5}} 4.0]) (clock 4.0))]
    (is (= 2 (get-in view [:any :count])) ":any is HIT, ACCENT and FLASH, not the Zone step key")
    (is (= 5 (get-in view [:zone-step :count])))
    (is (nil? (:any (pulses/view (stamps-at [{:zone-step {:id 5}} 4.0]) (clock 4.0)))) "the Zone step key alone makes no :any")))
