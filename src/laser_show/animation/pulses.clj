(ns laser-show.animation.pulses
  "Pulses: presses as effects see them (plans/2026-11-18-vibe-mode/04).

   App state counts presses at [:playback :pulses] {source {:id n :held?}}:
   each HIT, ACCENT and FLASH press, held until its release (the vibe
   engine), each vibe change (:vibe), each cue start (:cue,
   grid/start-cue) and each press of the Zone step key (:zone-step, zones
   plan Z5, :pattern-pads/step). The show clock stamps them (stamp) on a clock of its own,
   the pulse clock, which Stop, Retrigger, a tap's beat alignment and a Link
   shift never move (show-time), and every cue's and the effects grid's
   render sees their ages (view). Calibration never does.

   What follows a press, all pure:
   - Restart on (rebase): a time-based modulator counts from its source's
     newest press instead of from its cue's start;
   - the Envelope (envelope), Step (step-value) and Energy (energy)
     modulators, which read their source's press from the view."
  (:require [laser-show.animation.time :as time]
            [laser-show.common.util :as u]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(def sources
  "What a reaction can follow: a role's press (:hit :accent :flash), one of
   the three (:any), a vibe change (:vibe), any cue start (:cue), or a press
   of the Zone step key (:zone-step, zones plan Z5: the STEP pad and its
   learned key, which do nothing else). :zone-step has no release, as :vibe
   and :cue, and isn't one of :any's."
  [:hit :accent :flash :vibe :any :cue :zone-step])

(def ^:private any-of
  "The roles :any is made of."
  [:hit :accent :flash])

(def max-recent
  "How many presses a stamp keeps the beats of (:recent, for Energy)."
  16)


;; The stamps: show time's record of the presses, made once per tick

(defn seed
  "Stamps for the presses already in app-pulses, none of them seen as a
   press, so a clock that starts again doesn't replay them
   (show-time/reset-runtime!)."
  [app-pulses]
  (into {} (keep (fn [[source {:keys [id]}]] (when id [source {:id id}]))) app-pulses))

(defn- press-stamp
  "stamp after n more presses at beat (and ms) of the pulse clock, the newest
   one held, or let go already when app state says :held? false."
  [stamp {:keys [id held?]} n beat ms]
  {:id id
   :count (+ (long (or (:count stamp) 0)) (long n))
   :press-beat beat
   :press-ms ms
   :held? (true? held?)
   :release-beat (when (false? held?) beat)
   :recent (into [] (take max-recent) (concat (repeat n beat) (:recent stamp)))})

(defn- stamp-one [stamp {:keys [id held?] :as pulse} beat ms]
  (cond
    (not= id (:id stamp))
    (press-stamp stamp pulse (max 1 (- (long id) (long (or (:id stamp) 0)))) beat ms)

    (and (:held? stamp) (not held?))
    (assoc stamp :held? false :release-beat beat)

    :else stamp))

(defn stamp
  "The stamps after a tick, from app-pulses ([:playback :pulses]) at
   pulse-clock now: a source whose :id moved had that many presses since the
   last tick (at least one: the ids start again with a new project), stamped
   now, so :count grows by as many and :recent gets as many entries; a press
   held at the last tick and let go since is stamped let go now. A source app
   state no longer has is forgotten."
  [stamps app-pulses pulse-clock]
  (let [beat (double (or (:accumulated-beats pulse-clock) 0.0))
        ms (double (or (:accumulated-ms pulse-clock) 0.0))]
    (reduce-kv (fn [acc source pulse]
                 (if (:id pulse)
                   (assoc acc source (stamp-one (get stamps source) pulse beat ms))
                   acc))
               {}
               (or app-pulses {}))))


;; The view: what every effect of a tick sees

(defn- age [{:keys [count press-beat press-ms held? release-beat recent]} ^double beat ^double ms]
  {:count count
   :beats (- beat (double press-beat))
   :ms (- ms (double press-ms))
   :held? (boolean held?)
   :since-release (when release-beat (- beat (double release-beat)))
   :recent (mapv #(- beat (double %)) recent)})

(defn- newer [a b]
  (if (<= (double (:beats a)) (double (:beats b))) a b))

(defn- any-pulse [pulses]
  (assoc (select-keys (reduce newer pulses) [:beats :ms :held? :since-release])
         :count (transduce (map :count) + pulses)
         :recent (into [] (take max-recent) (sort (mapcat :recent pulses)))))

(defn view
  "What effects see of stamps at pulse-clock now: for each source pressed
   at least once, {:count :beats :ms :held? :since-release :recent}, the
   newest press's age in beats and ms of the pulse clock, how long ago it
   was let go (nil while held, and for :vibe and :cue, which have no
   release), and the ages of the newest presses, newest first. :any is HIT,
   ACCENT and FLASH together: their counts summed, the newest press's ages,
   their :recent merged."
  [stamps pulse-clock]
  (let [beat (double (or (:accumulated-beats pulse-clock) 0.0))
        ms (double (or (:accumulated-ms pulse-clock) 0.0))
        v (reduce-kv (fn [acc source s]
                       (if (:press-beat s) (assoc acc source (age s beat ms)) acc))
                     {}
                     stamps)
        roles (keep v any-of)]
    (cond-> v
      (seq roles) (assoc :any (any-pulse roles)))))


;; Restart on

(def ^:private long-ago
  "Beats, and ms, a once-mode modulator counts from while its source has no
   press yet: long enough to be done."
  1.0e9)

(defn rebase
  "The context a modulator that restarts on source sees: the time since the
   source's newest press as its beats (:accumulated-beats,
   :effective-beats) and ms (:accumulated-ms, :time-ms), no phase glide, and
   the press count as :pulse-count. Before the first press a looping one
   (loop?) runs as it would without Restart on, and a once-mode one or a
   Decay is done: at rest."
  [context source loop?]
  (if-let [{:keys [count beats ms]} (get (:pulses context) source)]
    (assoc context
           :accumulated-beats beats :effective-beats beats
           :accumulated-ms ms :time-ms ms
           :phase-offset 0.0 :pulse-count count)
    (if loop?
      context
      (assoc context
             :accumulated-beats long-ago :effective-beats long-ago
             :accumulated-ms long-ago :time-ms long-ago
             :phase-offset 0.0 :pulse-count 0))))


;; Envelope, Step and Energy: pulse is the source's view, nil before its first press

(defn- clamp01 ^double [^double x]
  (max 0.0 (min 1.0 x)))

(defn envelope
  "An Envelope's value: :min at rest; a press ramps it to :max over :attack
   beats, holds it there while the key is down if :hold?, then brings it
   back to :min over :release beats along :curve (time/decay-curve-factor).
   The release starts when the attack ends or, if held longer, when the key
   is let go; for a source with no release (:vibe, :cue) or without :hold?,
   when the attack ends. :max may be below :min, a dip (Fade in)."
  ^double [{:keys [min max attack release curve hold?]} pulse]
  (let [lo (double (or min 0.0))
        hi (double (or max 1.0))]
    (if-not pulse
      lo
      (let [a (double (:beats pulse))
            attack (double (or attack 0.0))
            release (double (or release 1.0))
            since (:since-release pulse)]
        (cond
          (< a attack) (+ lo (* (- hi lo) (/ a attack)))
          (and hold? (:held? pulse)) hi
          :else (let [start (if (and hold? since) (clojure.core/max attack (- a (double since))) attack)
                      p (if (pos? release) (clamp01 (/ (- a start) release)) 1.0)]
                  (+ lo (* (time/decay-curve-factor (or curve :exp) p) (- hi lo)))))))))

(def ^:private ^:const phi 0.6180339887)

(defn step-index
  "Which of n positions a Step is at after count presses (0 before the
   first), in order :up (the default), :down, :ping-pong or :scatter: a
   golden-ratio walk from the middle that never gives the same position twice
   in a row with 3 or more (with 2, as :up). With one position, always it."
  ^long [order n count]
  (let [n (long n)
        k (long count)]
    (if (<= n 1)
      0
      (case order
        :down (- (dec n) (rem k n))
        :ping-pong (let [i (rem k (- (* 2 n) 2))]
                     (if (< i n) i (- (* 2 n) 2 i)))
        :scatter (if (= 2 n)
                   (rem k n)
                   (rem (+ (quot n 2) (long (Math/floor (* (u/fmod (* (double k) phi) 1.0) (double n))))) n))
        (rem k n)))))

(defn- position-count ^long [{:keys [values steps]}]
  (if (seq values)
    (count values)
    (max 1 (long (or steps 4)))))

(defn- position
  "Position i of n: the palette's ith value, or the ith even step from :min to
   :max (with :wrap?, :max is the same place as :min, so it isn't one)."
  ^double [{:keys [min max wrap? values]} ^long n ^long i]
  (if (seq values)
    (double (nth values i))
    (let [lo (double (or min 0.0))
          hi (double (or max 1.0))]
      (if (<= n 1)
        lo
        (+ lo (* (double i) (/ (- hi lo) (double (if wrap? n (dec n))))))))))

(defn- smoothstep ^double [^double t]
  (* t t (- 3.0 (* 2.0 t))))

(defn- nearer
  "to, moved by whole turns of span to the side of from it is nearer."
  ^double [^double from ^double to ^double span]
  (if (pos? span)
    (let [d (- to from)]
      (+ from (- d (* span (Math/rint (/ d span))))))
    to))

(defn step-value
  "A Step's value: the position step-index gives for the press count, among
   the palette's :values in the order typed, when there are any, else :steps
   even steps from :min to :max. With :glide it moves there from the last
   position over that many beats (smoothstep), and with :wrap? the shorter
   way round, a circle of :max - :min, or of 360 for a palette (hues)."
  ^double [{:keys [order glide wrap? values min max] :as config} pulse]
  (let [n (position-count config)
        k (long (or (:count pulse) 0))
        to (position config n (step-index order n k))
        glide (double (or glide 0.0))
        beats (double (or (:beats pulse) 0.0))]
    (if (and (pos? k) (pos? glide) (< beats glide))
      (let [from (position config n (step-index order n (dec k)))
            span (if (seq values) 360.0 (- (double (or max 1.0)) (double (or min 0.0))))
            to (if wrap? (nearer from to span) to)]
        (+ from (* (smoothstep (/ beats glide)) (- to from))))
      to)))

(defn energy
  "Energy's value from its source's recent presses, oldest first: each adds
   :rise and it falls by :fall a beat, between 0 and 1, then it falls since
   the newest; :min at 0, :max at 1. Faster taps push it higher, and it
   settles back to :min when they stop."
  ^double [{:keys [min max rise fall]} pulse]
  (let [lo (double (or min 0.0))
        hi (double (or max 1.0))
        recent (:recent pulse)]
    (if (empty? recent)
      lo
      (let [rise (double (or rise 0.25))
            fall (double (or fall 0.25))
            oldest-first (rseq (vec recent))
            e (double (loop [e 0.0
                             prev (double (first oldest-first))
                             ages oldest-first]
                        (if-let [s (seq ages)]
                          (let [a (double (first s))]
                            (recur (clamp01 (+ (clojure.core/max 0.0 (- e (* fall (- prev a)))) rise)) a (next s)))
                          (clojure.core/max 0.0 (- e (* fall prev))))))]
        (+ lo (* e (- hi lo)))))))

(set! *warn-on-reflection* false)
(set! *unchecked-math* false)
