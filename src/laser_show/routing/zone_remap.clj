(ns laser-show.routing.zone-remap
  "The zone stage (zones plan Z5, plans/2026-11-19-zones/07 §4.1, built
   with vibe Phase 8's names, 11 §4, §7): a cue's frames by key move
   between places on the beat or on a press, before the point effects run.
   Pure: it requires no frame service, so the render and the tests reach
   it alone.

   A frame key is a set of places (spec 01 §1: a target in normal form). A
   zone pattern turns each key into a new key, or drops it:
   - move (Alternate, Chase, Ping-pong, Random, from a picture): each key
     that holds :from becomes (k − {from}) ∪ S, S the step's lit places (an
     empty S hides the picture); with :from :any every key becomes S;
   - gate (the same styles, on what is already in the zones): (k − L) ∪
     (k ∩ L ∩ S), L the lane; nothing left hides the picture;
   - permute (Shift, Swap): each lane ref is replaced by its image, a
     bijection, so nothing hides and nothing is copied.
   A picture lit in several places is one frame under the union of those
   places (spec §6.1, Z5 S-1), never one object under several keys. Frames
   that land on one key are joined (animation.frames) in the order of their
   old keys (routing.compose/key-order), each object once. A remap changes
   no point and no colour.

   The step is ⌊offset + rate·t + 1e-9⌋ (step-index), t the beats of the
   pattern's clock (the cue's or the show's, since a press of :restart-on),
   or a pulse source's press count (pattern-t).

   A step grid (zones plan Z6, plans/2026-11-19-zones/08 §4) is one more
   lit style, :steps, whose lit places are data: step k lights steps[k mod
   L] of its :steps (L of them, each the refs lit on it; [] a rest), those
   in the lane. With no steps it is the identity."
  (:require [laser-show.animation.frames :as frames]
            [laser-show.animation.pulses :as pulses]
            [laser-show.routing.compose :as compose]
            [laser-show.routing.rig :as rig]
            [laser-show.routing.targets :as targets]))


;; Styles

(def lit-styles
  "The styles that light some of the lane's places each step: they move a
   picture there (copying it when several are lit), or keep only what is
   lit. :steps is a step grid's (zones plan Z6): its places are data."
  #{:alternate :chase :ping-pong :random :steps})

(def permute-styles
  "The styles that trade what is in the lane's places: a bijection."
  #{:shift :swap})

(def ^:private defaults
  "What a pattern's parameters are when it doesn't say (routing.zone-patterns
   has their definitions)."
  {:style :alternate :zones [] :steps [] :source :auto :from :all :rate 1.0 :offset 0.0
   :step-on :clock :clock :global :path :loop :width 1 :seed 0 :follow-pads? true :restart-on :own})


;; Steps

(defn step-index
  "The step a pattern is at: ⌊offset + rate·t + 1e-9⌋ (vibe Phase 8: the
   allowance keeps a Step's 2.9999999999 on step 3). Negative for a
   backwards rate before t's start."
  ^long [offset rate t]
  (long (Math/floor (+ (double offset) (* (double rate) (double t)) 1e-9))))

(defn ring-index
  "Which of n places step is at along path: :loop goes round (step mod n),
   :bounce goes there and back (m = step mod (2n − 2), then m, or 2n − 2 − m
   past the end). One place, or none: 0."
  ^long [step n path]
  (let [n (long n)
        step (long step)]
    (cond
      (<= n 1) 0
      (= :bounce path) (let [span (- (* 2 n) 2)
                             m (mod step span)]
                         (if (< m n) m (- span m)))
      :else (mod step n))))

(defn scatter-index
  "Random's place for step of n (Z5-Q5 A): vibe Phase 4a's golden-ratio walk
   (pulses/step-index :scatter) at step + seed, which never gives the same
   place twice in a row for 3 or more, and works for a negative step (its
   fraction is floored); for 2, (step + seed) mod 2; for 1, 0."
  ^long [step n seed]
  (let [n (long n)
        k (+ (long step) (long seed))]
    (cond
      (<= n 1) 0
      (= 2 n) (mod k 2)
      :else (pulses/step-index :scatter n k))))

(defn pattern-t
  "What a pattern's step counts (07 §8.3), for params at ctx {:beats {:cue
   b :global b} :pulses (the tick's pulse view)}: with :step-on :clock, the
   beats of its :clock (the cue's from its start, or the show's), or with a
   :restart-on source the beats since that source's newest press
   (pulses/rebase), its own clock before the first; with a pulse source,
   that source's press count in the view, 0 before its first press."
  ^double [params {:keys [beats pulses]}]
  (let [step-on (or (:step-on params) :clock)]
    (if (= :clock step-on)
      (let [base (double (or (get beats (if (= :cue (:clock params)) :cue :global)) 0.0))
            restart (:restart-on params)]
        (if (and restart (not= :own restart))
          (double (:effective-beats (pulses/rebase {:effective-beats base :pulses pulses} restart true)))
          base))
      (double (or (get-in pulses [step-on :count]) 0)))))

(defn effective-style
  "The style a pattern plays: the pattern pads' live style (live: nil As
   set, :off, or a style) when the pattern follows the pads (:follow-pads?,
   true when unset), else its own. :off means it doesn't move."
  [params live]
  (if (and (some? live) (not (false? (:follow-pads? params))))
    live
    (:style params)))


;; Lanes

(defn lane
  "A pattern's places, in order (vibe Phase 8's lane, over refs): refs, with
   the token :lane/lasers (\"Every laser, left to right\", Z5-Q4 A) put as
   each laser of lasers (E's ids, in rig order) by its own ref,
   :auto.laser/<id>, or as the halves Left and Right with one laser; then
   the refs known? doesn't know, and repeats, dropped."
  [refs known? lasers]
  (let [known? (or known? (constantly true))]
    (into []
          (comp (mapcat (fn [r]
                          (if (= :lane/lasers r)
                            (if (= 1 (count lasers)) [:auto/left :auto/right] (map rig/laser-ref lasers))
                            [r])))
                (filter #(and (keyword? %) (known? %)))
                (distinct))
          refs)))

(defn- clamp-width ^long [width n]
  (max 1 (min (long n) (long (if (number? width) width 1)))))

(defn- window
  "width places of l from start, wrapping."
  [l start width]
  (let [n (count l)]
    (into #{} (map #(nth l (mod (+ (long start) (long %)) n))) (range width))))

(defn grid-steps
  "A step grid's steps (zones plan Z6): its :steps when they are a vector
   of steps, else none. A step that isn't a collection of refs is a rest."
  [params]
  (let [steps (:steps params)]
    (if (sequential? steps) steps [])))

(defn grid-step
  "The refs step k of a step grid lights, before the lane: steps[k mod L]
   (backwards too); nil with no steps."
  [params k]
  (let [steps (grid-steps params)
        n (count steps)]
    (when (pos? n)
      (let [step (nth steps (mod (long k) n))]
        (when (sequential? step) step)))))

(defn lit-set
  "The places of l (a lane) lit at step for a lit style (07 §8.2):
   Alternate the even places on even steps and the odd ones on odd steps
   (with one place, none on odd steps: the picture is hidden then); Chase
   :width places from ring-index along :path (wrapping; with :bounce the
   window bounces inside the lane); Ping-pong a bouncing Chase; Random
   :width places from scatter-index (:seed); a step grid (:steps, zones plan
   Z6) the places of l its step lights (grid-step). A Chase, Ping-pong or
   Random on one place lights it on every step; no place lights nothing."
  [style params l step]
  (let [n (count l)
        width (clamp-width (:width params) n)]
    (if (zero? n)
      #{}
      (case style
        :alternate (into #{} (keep-indexed (fn [i r] (when (= (mod i 2) (mod step 2)) r))) l)
        (:chase :ping-pong) (let [path (if (= :ping-pong style) :bounce (:path params))]
                              (if (= :bounce path)
                                (let [start (ring-index step (inc (- n width)) :bounce)]
                                  (into #{} (subvec l start (+ start width))))
                                (window l (ring-index step n :loop) width)))
        :random (window l (scatter-index step n (or (:seed params) 0)) width)
        :steps (let [lit (set (grid-step params step))]
                 (into #{} (filter lit) l))
        #{}))))

(defn permutation
  "Where each place of l (a lane) sends what is in it at step, for a
   permuting style: Shift l[i] → l[(i + step) mod n], Swap l[0] ⇄ l[1] on
   odd steps. Only the places that move: {} when none does (Shift by a
   whole turn, Swap on even steps, a lane under two)."
  [style l step]
  (let [n (count l)]
    (if (< n 2)
      {}
      (case style
        :shift (let [k (mod (long step) n)]
                 (if (zero? k)
                   {}
                   (into {} (map-indexed (fn [i r] [r (nth l (mod (+ i k) n))])) l)))
        :swap (if (odd? (long step)) {(nth l 0) (nth l 1) (nth l 1) (nth l 0)} {})
        {}))))


;; Re-keying

(defn- join
  "One frame of fs (in order), each object once: the frame itself when only
   one is left, else their blanking join."
  [fs]
  (let [once (reduce (fn [acc f] (if (some #(identical? % f) acc) acc (conj acc f))) [] fs)]
    (if (= 1 (count once))
      (first once)
      (frames/concatenate-frames once))))

(defn rekey
  "frames ({key frame}) with each key k put under (f k), a key in normal
   form, or left out for nil (hidden). Frames that land on one key are
   joined in compose/key-order of their old keys, an object already in the
   join not added again. The same map object when f leaves every key alone."
  [frames f]
  (let [moves (mapv (fn [[k frame]] [k (f k) frame]) frames)]
    (if (every? (fn [[k k']] (= k k')) moves)
      frames
      (reduce-kv (fn [acc k' entries]
                   (if-let [frame (join (map #(nth % 2) (sort-by (comp compose/key-order first) entries)))]
                     (assoc acc k' frame)
                     acc))
                 {}
                 (group-by second (filterv #(some? (nth % 1)) moves))))))

(defn rekey-item-points
  "Z3's :item-points ([[item-id {key points}] …], an edited cue's) after the
   key function f: each item's points under the keys its frames went to, so
   an editor's \"Most points\" still finds the item's laser."
  [item-points f]
  (when item-points
    (mapv (fn [[id by-key]]
            [id (reduce-kv (fn [m k n] (if-let [k' (f k)] (update m k' (fnil + 0) n) m)) {} by-key)])
          item-points)))

(defn- holds? [k ref]
  (boolean (some #{ref} (targets/key-refs k))))

(defn- move-fn
  "move: each key that holds from (every key for :any) becomes (k − {from})
   ∪ lit, nil when nothing is left."
  [from lit]
  (fn [k]
    (let [refs (targets/key-refs k)]
      (cond
        (= :any from) (targets/normalize-target lit)
        (some #{from} refs) (targets/normalize-target (into (remove #{from} refs) lit))
        :else k))))

(defn- gate-fn
  "gate: the lane's places that aren't lit leave each key, nil when nothing
   is left; a key with no lane place stays."
  [l lit]
  (let [in-lane (set l)]
    (fn [k]
      (let [refs (targets/key-refs k)]
        (if (some in-lane refs)
          (targets/normalize-target (remove #(and (in-lane %) (not (contains? lit %))) refs))
          k)))))

(defn- permute-fn
  "permute: each moved lane place for its image."
  [perm]
  (fn [k]
    (let [refs (targets/key-refs k)]
      (if (some perm refs)
        (targets/normalize-target (map #(get perm % %) refs))
        k))))

(defn- moves-the-picture?
  "Does a lit style move :from's picture (:source :picture, or :auto with a
   key that holds it), or keep what is in the zones (:zones, or :auto
   without)?"
  [{:keys [source from]} frames]
  (case source
    :picture true
    :zones false
    (if (= :any from)
      (boolean (seq frames))
      (boolean (some #(holds? % from) (keys frames))))))

(defn- key-fn
  "The key function of a pattern playing style at step on lane l, or nil
   when it leaves every key alone (a step grid with no steps too)."
  [style params l step frames]
  (cond
    (or (empty? frames) (empty? l)) nil
    (and (= :steps style) (empty? (grid-steps params))) nil
    (contains? lit-styles style) (let [lit (lit-set style params l step)]
                                   (if (moves-the-picture? params frames)
                                     (move-fn (:from params) lit)
                                     (gate-fn l lit)))
    (contains? permute-styles style) (let [perm (permutation style l step)]
                                       (when (seq perm) (permute-fn perm)))
    :else nil))

(defn- with-defaults [params]
  (reduce-kv (fn [p k v] (if (nil? (get p k)) (assoc p k v) p))
             (or params {})
             defaults))

(defn- number-or [x default]
  (if (number? x) (double x) default))

(defn pattern-step
  "The step pattern params is at, at ctx (step-index of pattern-t)."
  ^long [params ctx]
  (let [params (with-defaults params)]
    (step-index (number-or (:offset params) 0.0) (number-or (:rate params) 1.0) (pattern-t params ctx))))

(defn run-pattern
  "One zone pattern (params resolved: effects/resolve-effect-params) on
   frames at ctx {:beats :pulses :known? (the refs the rig knows) :lasers
   (E's ids, in rig order) :live-style (the pattern pads')}: {:frames (the
   frames after it) :step :f (its key function, nil when it changed no
   key)}."
  [frames params ctx]
  (let [params (with-defaults params)
        style (effective-style params (:live-style ctx))
        step (pattern-step params ctx)
        l (lane (:zones params) (:known? ctx) (:lasers ctx))
        f (when (not= :off style) (key-fn style params l step frames))]
    {:frames (if f (rekey frames f) frames)
     :step step
     :f f}))

(defn apply-pattern
  "frames after one zone pattern (run-pattern): the :zone-pattern effect's
   :apply-zones."
  [frames params ctx]
  (:frames (run-pattern frames params ctx)))

(defn run-stage
  "patterns ([{:id :params} …], params resolved) on frames in order (07
   §8.7: the cue's own, then the effects grid's in reading order), at ctx:
   {:frames :steps {pattern-id step} :fs [f …] (the key functions that
   changed something, in order)}. With no pattern, frames itself."
  [frames patterns ctx]
  (reduce (fn [acc {:keys [id params]}]
            (let [{:keys [frames step f]} (run-pattern (:frames acc) params ctx)]
              (cond-> (assoc acc :frames frames)
                (some? id) (assoc-in [:steps id] step)
                f (update :fs conj f))))
          {:frames frames :steps {} :fs []}
          patterns))

(defn apply-zone-stage
  "frames after patterns, in order (run-stage; vibe Phase 8's
   apply-zone-effects). With no pattern, the same map object."
  [frames patterns ctx]
  (if (empty? patterns)
    frames
    (:frames (run-stage frames patterns ctx))))

(defn routing-ctx
  "What the stage reads of a tick's routing (routing.rig/routing), the rig
   alone (07 invariant 9): {:known? (a ref a lane may name: a zone, or any
   ref the rig knows) :lasers (E's ids, in rig order: Every laser)}."
  [routing]
  (let [refs (:refs routing)]
    {:known? (fn [ref] (or (targets/zone-ref? ref) (contains? refs ref)))
     :lasers (into [] (comp (filter (comp some? :index val)) (map key))
                   (sort-by (comp :index val) (:outputs routing)))}))
