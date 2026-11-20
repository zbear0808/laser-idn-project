(ns laser-show.vibes.engine-soak-test
  "The fuzz's walk (laser-show.vibes.engine-fuzz-test) for long: 200,000
   events by default, about half an hour (VIBE_SOAK_EVENTS=<n> for another
   number, VIBE_SOAK_SEED=<seed> for another walk). Every step's invariants,
   as the fuzz checks them, and two of its own
   (plans/2026-11-18-vibe-mode/07 §8): what vibe mode keeps stays bounded,
   and the last 1,000 presses cost no more than 3 times the first 1,000 (no
   growth). Tagged ^:performance, so only the timing alias runs it:
   clj -M:mac-arm:test-timing soak"
  (:require [clojure.test :refer [deftest is]]
            [laser-show.events.core :as events]
            [laser-show.vibes.engine-fuzz-test :as fuzz])
  (:import [java.util ArrayList SplittableRandom]))

(def ^:private events-to-walk
  (or (some-> (System/getenv "VIBE_SOAK_EVENTS") parse-long) 200000))

(def ^:private seed
  (or (some-> (System/getenv "VIBE_SOAK_SEED") parse-long) 20261119))

(defn- size
  "How much vibe mode keeps in state: [:vibe-runtime]'s maps, the cues that
   play, the press sources, the undo stack, and the runtime printed."
  [state]
  (let [rt (:vibe-runtime state)]
    {:bags (count (:bags rt)) :voices (count (:voices rt)) :held (count (:held rt)) :duck (count (:duck rt))
     :fx-on (count (:fx-on rt)) :intents (count (get-in state [:playback :active-cues]))
     :pulses (count (get-in state [:playback :pulses])) :undo (count (get-in state [:history :undo-stack]))
     :printed (count (pr-str rt))}))

(def ^:private bounds
  "The world's ceilings: 4 vibes' HIT and ACCENT bags, a voice and a hold a
   role, what a solo flash keeps waiting (HIT, ACCENT, the bed), the 3
   effect cells, the 14 cue cells, the 5 press sources (HIT, ACCENT, FLASH,
   a vibe change, a cue start), the undo history's 50 steps, and the
   runtime printed in 12,000 characters."
  {:bags 8 :voices 4 :held 4 :duck 3 :fx-on 3 :intents 14 :pulses 5 :undo 50 :printed 12000})

(defn- median [xs]
  (let [v (vec (sort xs))]
    (when (seq v) (nth v (quot (count v) 2)))))

(deftest ^:performance engine-soak-test
  (let [rng (SplittableRandom. (long seed))
        press-ns (ArrayList.)
        started (System/nanoTime)]
    (loop [world (fuzz/new-world)
           i 0
           maxima {}]
      (if (< i events-to-walk)
        (let [[step dt] (fuzz/random-step rng world)
              _ (when (= :vibe/press (get-in step [:event :event/type]))
                  (let [e (assoc (:event step) :state (:state world) :time (long (+ (:now world) dt)))
                        t0 (System/nanoTime)]
                    (events/handle-event-pipeline e)
                    (.add press-ns (- (System/nanoTime) t0))))
              [world' problems] (fuzz/take-step world step dt)]
          (when (zero? (mod (inc i) 20000))
            (println (format "soak: %,d of %,d events, %.0f s" (inc i) events-to-walk (/ (- (System/nanoTime) started) 1e9))))
          (if-let [problems (seq (remove nil? problems))]
            (is false (str "seed " seed ", step " i ": " (first problems)))
            (recur world' (inc i) (merge-with max maxima (size (:state world'))))))
        (let [presses (vec press-ns)
              first-k (median (take 1000 presses))
              last-k (median (take-last 1000 presses))]
          (println (format "soak: %,d events in %.0f s, seed %d; %,d presses, median %.1f µs (first 1,000) and %.1f µs (last 1,000); at most %s"
                           events-to-walk (/ (- (System/nanoTime) started) 1e9) seed (count presses)
                           (/ first-k 1e3) (/ last-k 1e3) (pr-str maxima)))
          (doseq [[k ceiling] bounds]
            (is (<= (get maxima k 0) ceiling) (str k " grew to " (get maxima k) ", past " ceiling)))
          (is (<= 2000 (count presses)) "enough presses to compare")
          (is (<= last-k (* 3 first-k)) (str "the last 1,000 presses cost " last-k " ns, the first " first-k)))))))
