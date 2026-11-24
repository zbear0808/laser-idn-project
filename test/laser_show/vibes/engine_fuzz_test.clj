(ns laser-show.vibes.engine-fuzz-test
  "A seeded walk of 2,000 random events through the real handlers and the
   show clock's step: presses and releases of every role from either source,
   vibe switches, blackouts, clock ticks and their reports, clicks on the grid
   (some on a cell a voice plays, which takes its cue over), moves and clears
   of cue and effect cells, vibes deleted, put back and reordered as an undo
   would (no handler), the Vibes tab's edits of FX sets, beds, pools,
   the audience rule and deletes through their handlers (engine/reconcile),
   and auto-advance: AUTO switched on and off, the clock's arms and advances
   as show-clock/tick! sends them (each twice), and arms and advances that
   come late. And safety (07): the show lock on and off, undo and redo
   through their handlers, keys that stick (a press, then the clock past the
   hold limit, which the clock's stuck-key reports end) and stuck-key reports
   that come late. After every step it checks invariants 1-12
   of plans/2026-11-18-vibe-mode/01-phase1-engine-core.md §7, the pulses
   presses write for tap reactions (04 §7, check-pulses), auto-advance's
   invariants 1-4 (06 §7, check-auto, check-rotation), and 07's: what the
   lock refuses changes nothing but the notice and the rest does what it
   would unlocked (check-lock), a stuck key is let go of once, alone, with
   its problem (check-watchdog, check-stuck-holds), no engine cue plays
   without its voice, none is held without its key, and no count goes
   negative (check-always); what may play (invariant 1) and what may change
   the vibe by itself are worked out here, not with laser-show.vibes.library
   or laser-show.vibes.rotation.
   A failure prints the seed and the events up to the failing step, the
   shortest failing prefix. To run one seed: VIBE_FUZZ_SEED=<seed>; seeds 1
   to n: VIBE_FUZZ_SEEDS=<n>. The soak test (engine-soak-test) walks the
   same way for longer."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [laser-show.animation.show-time :as show-time]
            [laser-show.events.core :as events]
            [laser-show.events.show-lock :as show-lock]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.templates :as templates]
            [laser-show.timeline.playback :as playback]
            [laser-show.triggers.shuffle-bag :as bag]
            [laser-show.vibes.library :as library]
            [laser-show.vibes.rotation :as rotation]
            [laser-show.vibes.watchdog :as watchdog])
  (:import [java.util SplittableRandom UUID]))

(def ^:private steps 2000)

(def ^:private seeds
  (cond
    (System/getenv "VIBE_FUZZ_SEED") [(parse-long (System/getenv "VIBE_FUZZ_SEED"))]
    (System/getenv "VIBE_FUZZ_SEEDS") (range 1 (inc (parse-long (System/getenv "VIBE_FUZZ_SEEDS"))))
    :else [20261118 7 4242]))


;; The world: the starter show, with every mode, a bed and an FX set, two
;; audience cues (one sends to :crowd; one has no zone group set, so it goes
;; to :all, which the crowd-scanning projector is in), a timeline and one that
;; follows timecode. The starter cues send to :center.

(def ^:private shape {:items [{:type :preset :preset-id :circle}] :target :center})

(def ^:private extra-cues
  {[0 1] shape
   [1 1] shape
   [2 1] (assoc shape :target :crowd)
   [5 1] (assoc shape :target nil)
   [3 1] {:editor-type :timeline :timeline/chase :mtc :timeline/tempo-bpm 120.0
          :items [{:type :preset :preset-id :circle :timeline/start 0.0 :timeline/duration 4.0}]}
   [4 1] {:editor-type :timeline :target :center
          :items [{:type :preset :preset-id :square :timeline/start 0.0 :timeline/duration 4.0}]}})

(def ^:private extra-vibes
  {:vibe-3 {:id :vibe-3 :name "Crowd" :color "#E67E22" :energy 3 :allow-audience? false
            :hit {:cells [[2 1] [3 1] [0 0] [5 1]] :select :shuffle :mode :decay :beats 0.5}
            :accent {:cells [[4 1]] :select :shuffle :mode :decay :beats 2.0}
            :flash {:cue [2 1] :fx [] :solo? true}
            :bed nil :fx []}
   :vibe-4 {:id :vibe-4 :name "Allowed" :color "#D94A4A" :energy 4 :allow-audience? true
            :hit {:cells [[2 1] [5 0] [5 1]] :select :shuffle :mode :gate :beats 1.0}
            :accent {:cells [[1 1]] :select :sequence :mode :sustain :beats 1.0}
            :flash {:cue [2 1] :fx [[2 3]] :solo? true}
            :bed [0 1] :fx [[2 3] [0 3]]}})

(defn- world-state []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-vibes)
      (assoc-in [:timing :bpm] 120.0)
      ;; A show of its own, so the clock's timecode reports apply (reactivity plan 15)
      (assoc-in [:project :document-id] #uuid "00000000-0000-0000-0000-0000000f022d")
      (update-in [:chains :cue-chains] update-vals #(assoc % :target :center))
      (update-in [:chains :cue-chains] merge extra-cues)
      (assoc-in [:projectors :crowd-beam] {:name "Crowd beam" :host "10.0.0.9" :port 7255 :enabled? false
                                           :zone-groups [:all] :tags #{:crowd-scanning}})
      (assoc-in [:chains :effect-chains] {[0 3] {:items [{:effect-id :scale}] :active? false}
                                          [1 3] {:items [{:effect-id :hue-shift}] :active? false}
                                          [2 3] {:items [{:effect-id :rotation}] :active? false}})
      (update-in [:config :vibes :library :vibe-1] merge {:fx [[0 3]] :bed [0 1]})
      (update-in [:config :vibes :library :vibe-2] merge
                 {:hit {:cells [[5 0] [7 0]] :select :sequence :mode :sustain :beats 1.0}
                  :accent {:cells [[6 0]] :select :shuffle :mode :gate :beats 2.0}
                  :flash {:cue nil :fx [[1 3] [0 3]] :solo? false}
                  :fx [[1 3]] :bed [1 1]})
      (update-in [:config :vibes :library] merge extra-vibes)
      (assoc-in [:config :vibes :order] [:vibe-1 :vibe-2 :vibe-3 :vibe-4])
      (assoc-in [:config :vibes :next-id] 5)
      ;; A tap writes [:timing] (a document root; its :bpm is live). Off,
      ;; HIT's events keep every document root as it was (invariant 7), and
      ;; the tempo stays at 120 BPM, a beat of 500 ms, as check-rotation takes it
      (assoc-in [:config :vibes :settings :hit-taps-tempo?] false)
      ;; Auto-advance on, and soon: 3 s, then a gap of 2 beats
      (assoc-in [:config :vibes :settings :rotation] {:mode :auto :seconds 3 :pause-beats 2.0})
      ;; A key held 10 s is stuck: the walk's holds reach it now and then
      (assoc-in [:config :vibes :settings :max-hold-secs] 10)
      (history/mark-clean)))

(def ^:private cue-cells (vec (sort (keys (get-in (world-state) [:chains :cue-chains])))))
(def ^:private fx-cells [[0 3] [1 3] [2 3]])
(def ^:private tick-ms (/ 1000.0 60))
(def ^:private rotation-ms {:seconds 3000 :gap 1000})
(def ^:private hold-limit-ms 10000)


;; Running events and the clock

(defn- run
  "state after event through the real pipeline and each event it dispatches,
   as the agent runs them (no effect beyond :state and :dispatch, and, given
   the clock's runtime, :show-clock/read of its timecode episode at a cell)."
  ([state event t] (run state event t nil))
  ([state event t runtime]
   (let [effects (events/handle-event-pipeline (assoc event :state state :time t))
         s (or (:state effects) state)
         {:keys [on-read chase-cell] :as read} (:show-clock/read effects)]
     (cond
       (:dispatch effects) (run s (:dispatch effects) t runtime)
       (and runtime on-read (contains? read :chase-cell))
       (run s (assoc on-read :clock {:chase-id (show-time/chase-id runtime chase-cell)}) t runtime)
       :else s))))

(defn- handled
  "The pipeline's effects for event alone: what invariant 11 compares, since
   what it dispatches is other handlers' (Stop's restart request has a random
   id)."
  [state event t]
  (events/handle-event-pipeline (assoc event :state state :time t)))

(declare bad check-auto check-watchdog)

(defn- tick
  "The world k ticks of the show clock on, its reports handled, and vibe
   mode's as show-clock/tick! sends them: auto-advance's (rotation/due) and
   the stuck keys' (watchdog/due, with the tick's pulse view), on the tick's
   app state, at most every 250 ms by the runtime's :vibe-sent. Each of
   those is handled twice: the second must change nothing (06 invariant 2,
   07 invariant 5); check-auto and check-watchdog check the first. Problems
   go in :tick-problems."
  [{:keys [state rt now] :as world} k]
  (reduce (fn [{:keys [state rt now] :as w} _]
            (let [now (+ now tick-ms)
                  t (long now)
                  {:keys [runtime reports]} (show-time/step rt state nil now)
                  [vibe-sent vibe-reports] (show-time/due-reports (:vibe-sent runtime)
                                                                  (into (vec (watchdog/due state now (show-time/pulse-view runtime)))
                                                                        (keep identity [(rotation/due state now 120.0)]))
                                                                  now)
                  [state problems] (reduce (fn [[s problems] report]
                                             (let [s' (run s report t)]
                                               [s' (cond-> (into problems (if (= :watchdog (:reason report))
                                                                            (check-watchdog s report s' t)
                                                                            (check-auto s report s' t)))
                                                     (= s s')
                                                     (conj (bad "06: the clock's " (pr-str report) " changed nothing"))
                                                     (not= s' (run s' report t))
                                                     (conj (bad "06 inv 2: " (pr-str report) " again changed the state")))]))
                                           [(reduce #(run %1 %2 t runtime) state reports) []]
                                           vibe-reports)]
              (-> w
                  (assoc :rt (assoc runtime :vibe-sent vibe-sent) :now now :state state)
                  (update :tick-problems into problems)
                  (cond-> (not= (get-in (:state w) [:vibe-runtime :current]) (get-in state [:vibe-runtime :current]))
                    (update :tick-changes (fnil inc 0))))))
          (assoc world :state state :rt rt :now now :tick-changes 0)
          (range k)))


;; The generator

(defn- one-of [^SplittableRandom rng xs] (nth xs (.nextInt rng (count xs))))

(defn- quiet
  "Steps that let go of every key down in state, then 2 s of the clock: a
   phrase gap, for the rotation to find. With hold :flash, FLASH is held
   through 3 s of it and let go of after (beyond-vibe
   phrase_gap_waits_for_flash_release); with :flash-over-hit, HIT is pressed
   before that release and let go of after it."
  [state hold]
  (let [ticks {:kind :tick :ticks 60 :next {:kind :tick :ticks 60}}
        release (fn [role after] (cond-> {:kind :vibe :event {:event/type :vibe/release :role role}} after (assoc :next after)))
        flash-up (case hold
                   :flash (release :flash nil)
                   :flash-over-hit {:kind :vibe :event {:event/type :vibe/press :role :hit}
                                    :next (release :flash (release :hit nil))}
                   nil)
        gap (if flash-up
              {:kind :vibe :event {:event/type :vibe/press :role :flash}
               :next (assoc-in ticks [:next :next] {:kind :tick :ticks 60 :next flash-up})}
              ticks)]
    (reduce #(release %2 %1)
            gap
            (distinct (concat (keys (get-in state [:vibe-runtime :held]))
                              (filter #(get-in state [:playback :pulses % :held?]) [:hit :accent :flash]))))))

(defn- stuck
  "A press whose release never comes (a pulled cable): role pressed from
   src, then the clock past the hold limit, whose report lets go of it."
  [role src]
  {:kind :vibe :event {:event/type :vibe/press :role role :src src}
   :next {:kind :tick :ticks (long (Math/ceil (/ (+ hold-limit-ms 500.0) tick-ms)))}})

(defn- next-step
  "A random step for world: {:kind :event? :next?}, kinds :vibe, :tick, :grid,
   :fx, :edit, :undo and :vibe-edit; a step's :next is the step after it. Blackout is pressed
   rarely, and more often while it is on. Half the HIT and ACCENT presses are
   taps: the release follows within 140 ms. Half the grid's clicks start a
   cell a voice plays again (its cue is someone else's now), and half of those
   are followed by BLACKOUT, which stops every voice: the one event that stops
   voices without forgetting stale ones first. Now and then the AUTO switch,
   an arm or an advance that may be late (another vibe's) or have no reason,
   as the clock's reports could arrive, or a quiet spell: every key let go
   of, then 2 s of the clock. And now and then the show lock switched, an
   undo or a redo, a stuck key's report that may be late (or for an earlier
   press), or a key that sticks."
  [^SplittableRandom rng {:keys [state next]}]
  (or next
      (let [blackout? (get-in state [:vibe-runtime :blackout?])
            role #(one-of rng [:hit :hit :accent :flash :vibe])
            cell #(one-of rng cue-cells)
            vibe #(one-of rng [:vibe-1 :vibe-2 :vibe-3 :vibe-4])
            voice-cells (vec (distinct (keep :cell (vals (get-in state [:vibe-runtime :voices])))))]
        (cond
          (< (.nextInt rng 100) (if blackout? 20 1))
          {:kind :vibe :event {:event/type :vibe/press :role :blackout}}

          (< (.nextInt rng 100) 5)
          (let [from (if (.nextBoolean rng) (get-in state [:vibe-runtime :current]) (vibe))]
            (case (.nextInt rng 5)
              0 {:kind :vibe :event {:event/type :vibe/set-auto :auto? (.nextBoolean rng)}}
              1 {:kind :vibe :event {:event/type :vibe/arm :from from}}
              2 {:kind :vibe :event {:event/type :vibe/advance :reason (one-of rng [:gap :gap :flash nil]) :from from}}
              (quiet state (one-of rng [nil :flash :flash-over-hit]))))

          (< (.nextInt rng 100) 3)
          (case (.nextInt rng 4)
            0 {:kind :vibe :event {:event/type :vibe/set-show-lock :locked? (not (get-in state [:ui :show-lock?]))}}
            1 {:kind :undo :event {:event/type (one-of rng [:edit/undo :edit/undo :edit/redo :history/undo :history/redo])}}
            2 {:kind :vibe :event (cond-> {:event/type :vibe/release :role (role) :reason :watchdog}
                                    (.nextBoolean rng) (assoc :pulse (inc (.nextInt rng 8))))}
            (stuck (role) (one-of rng [:input :pad])))

          :else
          (let [r (.nextInt rng 100)]
            (cond
              (< r 30) (let [role (role)]
                         (cond-> {:kind :vibe :event {:event/type :vibe/press :role role :src (one-of rng [:input :pad])}}
                           (and (#{:hit :accent} role) (.nextBoolean rng))
                           (assoc :next {:kind :vibe :event {:event/type :vibe/release :role role} :quick? true})))
              (< r 60) {:kind :vibe :event {:event/type :vibe/release :role (role)}}
              (< r 70) {:kind :tick :ticks (inc (.nextInt rng 60))}
              (< r 75) {:kind :vibe-edit
                        :event (case (.nextInt rng 5)
                                 0 {:event/type :vibe/toggle-fx :vibe (vibe) :slot (one-of rng [:fx :fx :flash-fx]) :cell (one-of rng fx-cells)}
                                 1 {:event/type :vibe/toggle-cell :vibe (vibe) :slot :bed :cell (cell)}
                                 2 {:event/type :vibe/toggle-cell :vibe (vibe) :slot (one-of rng [:hit :accent]) :cell (cell)}
                                 3 {:event/type :vibe/set-option :vibe (vibe) :key :allow-audience? :value (.nextBoolean rng)}
                                 4 {:event/type :vibe/remove :vibe (vibe)})}
              (< r 78) {:kind :vibe :event {:event/type :vibe/select :vibe (one-of rng [:vibe-1 :vibe-2 :vibe-3 :vibe-4 :gone])}}
              (< r 80) {:kind :vibe :event {:event/type :vibe/release-all :only (one-of rng [nil :pad :input])}}
              (< r 84) (if (and (seq voice-cells) (.nextBoolean rng))
                         (let [[c r] (one-of rng voice-cells)]
                           (cond-> {:kind :grid :event {:event/type :grid/activate-cell :col c :row r}}
                             (.nextBoolean rng) (assoc :next {:kind :vibe :event {:event/type :vibe/press :role :blackout}})))
                         (let [[c r] (cell)] {:kind :grid :event {:event/type :grid/trigger-cell :col c :row r}}))
              (< r 86) (let [[fc fr] (cell) [tc tr] (cell)]
                         {:kind :grid :event {:event/type :grid/move-cell :from-col fc :from-row fr :to-col tc :to-row tr}})
              (< r 87) (let [[c r] (cell)] {:kind :grid :event {:event/type :grid/clear-cell :col c :row r}})
              (< r 90) {:kind :edit :edit [:restore-cue (cell)]}
              (< r 91) (let [[fc fr] (one-of rng fx-cells) [tc tr] (one-of rng fx-cells)]
                         {:kind :fx :event {:event/type :effects/move-cell :from-col fc :from-row fr :to-col tc :to-row tr}})
              (< r 92) (let [[c r] (one-of rng fx-cells)] {:kind :fx :event {:event/type :effects/clear-cell :col c :row r}})
              (< r 94) {:kind :edit :edit [:restore-fx (one-of rng fx-cells)]}
              (< r 95) {:kind :edit :edit [:delete-vibe (one-of rng [:vibe-1 :vibe-2 :vibe-3 :vibe-4])]}
              (< r 98) {:kind :edit :edit [:restore-vibe (one-of rng [:vibe-1 :vibe-2 :vibe-3 :vibe-4])]}
              :else {:kind :edit :edit [:reorder-vibes (.nextLong rng)]}))))))

(def ^:private original (world-state))

(defn- edit
  "state after a Phase 3 style edit (or an undo) of the document."
  [state [what arg]]
  (case what
    :restore-cue (cond-> state
                   (nil? (get-in state [:chains :cue-chains arg]))
                   (assoc-in [:chains :cue-chains arg] (get-in original [:chains :cue-chains arg] shape)))
    :restore-fx (cond-> state
                  (nil? (get-in state [:chains :effect-chains arg]))
                  (assoc-in [:chains :effect-chains arg] {:items [{:effect-id :scale}] :active? false}))
    :delete-vibe (-> state
                     (update-in [:config :vibes :library] dissoc arg)
                     (update-in [:config :vibes :order] #(filterv (complement #{arg}) %)))
    :restore-vibe (cond-> state
                    (nil? (get-in state [:config :vibes :library arg]))
                    (-> (assoc-in [:config :vibes :library arg] (get-in original [:config :vibes :library arg]))
                        (update-in [:config :vibes :order] conj arg)))
    :reorder-vibes (update-in state [:config :vibes :order] (bag/seeded arg))))


;; The invariants

(defn- vr [state & ks] (get-in state (into [:vibe-runtime] ks)))

(defn- live? [state {:keys [cell run]}]
  (and (some? run) (= run (get-in state [:playback :active-cues cell :run]))))

(defn- fx-on? [state cell] (true? (get-in state [:chains :effect-chains cell :active?])))

(defn- doc [state] (history/extract-document-state state))

(defn- may-play?
  "Invariant 1, worked out without laser-show.vibes.library: the cue at cell
   has items, doesn't follow timecode, and (unless the vibe allows the
   audience) doesn't go to :crowd or :all, the world's audience groups (the
   crowd-scanning projector is in :all; no item or track names a zone). A
   cue with no zone group set goes to :all, as the routing sends it."
  [state vibe cell]
  (let [cue (get-in state [:chains :cue-chains cell])]
    (boolean (and (seq (:items cue))
                  (not (playback/chases-mtc? cue))
                  (or (:allow-audience? vibe)
                      (not (#{:crowd :all} (or (get-in cue [:target]) :all))))))))

(defn- pool-of [state vibe role]
  (filterv #(may-play? state vibe %) (distinct (get-in vibe [role :cells]))))

(defn- bad [& kvs] (apply str kvs))

(defn- pulse [state role] (get-in state [:playback :pulses role]))

(defn- let-go
  "state with role's pulse let go, as every release of role does (Phase 4)."
  [state role]
  (cond-> state
    (:held? (pulse state role)) (update-in [:playback :pulses role] #(-> % (assoc :held? false) (dissoc :src)))))

(defn- engine-run?
  "Did the vibe engine start run? Its ids are (UUID. t n); a grid start's are
   random, of variant 2 (01 §10)."
  [run]
  (and (instance? UUID run) (not= 2 (.variant ^UUID run))))

(defn- check-always
  "Invariants 1, 3, 4 and 6 on any state, and 07's invariant 7: no engine cue
   plays that no voice owns (a role's old voice left playing), none is held
   (so never decays) unless its role is, and no count is negative. (A
   voice's cell can come to follow timecode or reach the crowd only by a
   move under a playing intent, which the clock drops at its next tick:
   presses are checked in check-press.)"
  [state]
  (let [voices (filter #(live? state (val %)) (vr state :voices))
        intents (get-in state [:playback :active-cues])]
    (concat
     (for [[role {:keys [cell]}] voices
           :when (empty? (get-in state [:chains :cue-chains cell :items]))]
       (bad "inv 1: the " role " voice plays " cell ", which has no content"))
     (when (not= (count voices) (count (set (map (comp :run val) voices))))
       [(bad "inv 4: two roles own one run " (pr-str voices))])
     (for [[cell {:keys [run]}] intents
           :when (and (engine-run? run) (not-any? #(= run (:run %)) (vals (vr state :voices))))]
       (bad "07 inv 7: " cell " plays an engine cue that no voice owns"))
     (for [[cell {:keys [run hold?]}] intents
           :when hold?
           :let [owner (some (fn [[role v]] (when (= run (:run v)) role)) (vr state :voices))]
           :when (not (and owner (vr state :held owner)))]
       (bad "07 inv 7: " cell " is held, so it never decays, but " (if owner (str owner " isn't") "no voice plays it")))
     (when-not (nat-int? (vr state :started))
       [(bad "07 inv 7: :started is " (vr state :started))])
     (for [[source {:keys [id]}] (get-in state [:playback :pulses])
           :when (and (some? id) (not (nat-int? id)))]
       (bad "07 inv 7: " source "'s pulse id is " id))
     (for [[k {:keys [n]}] (vr state :bags)
           :when (and (some? n) (not (nat-int? n)))]
       (bad "07 inv 7: bag " k "'s :n is " n))
     (for [[k {:keys [items remaining last]}] (vr state :bags)
           :when (or (not= (count remaining) (count (set remaining)))
                     (not-every? (set items) remaining)
                     (and (some? last) (some #{last} remaining)))]
       (bad "inv 3: bag " k " is inconsistent " (pr-str (vr state :bags k))))
     (when (vr state :blackout?)
       (concat
        (for [k [:voices :held :duck :fx-on]
              :when (seq (vr state k))]
          (bad "inv 6: during blackout " k " is " (pr-str (vr state k))))
        (for [r [:hit :accent :flash]
              :when (:held? (pulse state r))]
          (bad "04 inv 9: during blackout " r "'s pulse is held")))))))

(defn- check-press
  "Invariants 1, 2, 6 and 9 for a press, and every HIT press outside
   blackout is :last-hit-at (06: the phrase gap counts from it)."
  [before after role t]
  (let [blackout? (vr before :blackout?)
        vibe (library/current-vibe after)
        pool (when vibe (pool-of after vibe role))
        v0 (vr before :voices role)
        v1 (vr after :voices role)
        started? (and v1 (live? after v1) (not= (:run v0) (:run v1)))]
    (cond
      (= role :blackout) nil

      blackout?
      (when (not= before after) [(bad "inv 6: a " role " press during blackout changed the state")])

      (= role :vibe) nil

      (nil? vibe)
      (when (not= {:why :no-vibes} (vr after :problem)) ["inv 9: no vibe, and no :no-vibes problem"])

      :else
      (concat
       (when (and (= :hit role) (not= t (vr after :last-hit-at)))
         [(bad "06: a HIT press at " t " left :last-hit-at " (vr after :last-hit-at))])
       (when-not (library/vibe after (vr after :current))
         [(bad "inv 9: :current " (vr after :current) " is no vibe after a press")])
       (when (#{:hit :accent} role)
         (let [k [(vr after :current) role]
               bag0 (vr before :bags k)
               bag1 (vr after :bags k)
               picked (when (not= bag0 bag1) (:last bag1))
               cells (get-in vibe [role :cells])]
           (concat
            (when (not= pool (library/playable-cells after vibe cells))
              [(bad "inv 1: playable-cells gives " (pr-str (library/playable-cells after vibe cells))
                    " for " (:id vibe) " " role ", but the cells that may play are " (pr-str pool))])
            (if (empty? pool)
              (concat (when (not= {:role role :why :empty-pool} (vr after :problem))
                        [(bad "inv 1: an empty " role " pool and no problem")])
                      (when (and (= (vr before :current) (vr after :current))
                                 (not= (cond-> (-> before
                                                   (assoc-in [:vibe-runtime :problem] (vr after :problem))
                                                   (assoc-in [:playback :pulses role] (pulse after role)))
                                         (= :hit role) (assoc-in [:vibe-runtime :last-hit-at] t))
                                       after))
                        [(bad "inv 1: an empty " role " pool changed more than :problem, its pulse and :last-hit-at")]))
              (concat
               (when (and started? (not (some #{(:cell v1)} pool)))
                 [(bad "inv 1: " role " started " (:cell v1) ", not one of the cells that may play " pool)])
               (when (and picked (not (some #{picked} pool)))
                 [(bad "inv 1: " role " picked " picked ", not one of " pool)])
               (when (and picked bag0 (>= (count pool) 2) (= picked (:last bag0)))
                 [(bad "inv 2: " role " picked " picked " twice in a row from " pool)]))))))
       (when (= :flash role)
         (let [f0 (vr before :voices :flash)
               f1 (vr after :voices :flash)]
           (when (and (:run f1) (not= (:run f0) (:run f1))
                      (not (and (= (:cell f1) (get-in vibe [:flash :cue])) (may-play? after vibe (:cell f1)))))
             [(bad "inv 1: the flash started " (:cell f1) ", not its vibe's flash cue, or one that may not play")])))))))

(defn- check-release
  "Invariants 5 and 12 for a release, or for each hold release-all lets go of
   (cut? true)."
  [before after role t cut?]
  (let [held (vr before :held role)
        voice (vr before :voices role)
        live (and voice (live? before voice))]
    (cond
      (nil? held)
      (when (and (not cut?) (not= (let-go before role) after))
        [(bad "inv 5: releasing " role ", which isn't held, changed more than its pulse")])

      (= role :flash)
      (concat
       (when (and live (live? after voice)) ["inv 12: the flash's cue plays on after its release"])
       (for [cell (:fx-on voice)
             :when (and (fx-on? after cell) (not (some #{cell} (vr after :fx-on))))]
         (bad "inv 12: the flash's effect cell " cell " is still on"))
       (when (vr after :voices :flash) ["inv 12: the flash voice outlived its release"]))

      (and live (#{:hit :accent} role))
      (let [quick? (and (not cut?) (< (- t (:at held)) (:quick-tap-ms (library/settings before))))]
        (case (:mode voice)
          :sustain (when-not (live? after voice) [(bad "inv 12: a :sustain " role " stopped on its release")])
          :decay (if quick?
                   (when-not (and (live? after voice)
                                  (false? (get-in after [:playback :active-cues (:cell voice) :hold?])))
                     [(bad "inv 12: a " role " tap didn't let its decay run")])
                   (when (live? after voice) [(bad "inv 12: a held " role " wasn't cut on release")]))
          :gate (when (live? after voice) [(bad "inv 12: a :gate " role " plays on after its release")])
          nil))

      :else nil)))

(defn- check-watchdog
  "07 invariant 5 for a stuck key's report (:vibe/release :reason :watchdog,
   the clock's or a late one): a hold still stuck at t, the engine's held
   hold-limit-ms or more, or the pulse of the report's press that played
   nothing, still held, is let go of as release-all lets go (check-release,
   cut), once, with its problem, and the vibe, the lock and the other roles'
   holds stay. A report that finds nothing stuck changes nothing."
  [before {:keys [role] pulse-id :pulse} after t]
  (let [{:keys [at] :as held} (vr before :held role)
        stuck? (if held
                 (>= (- t at) hold-limit-ms)
                 (and (some? pulse-id) (= pulse-id (:id (pulse before role))) (true? (:held? (pulse before role)))))]
    (if-not stuck?
      (when (not= before after)
        [(bad "07 inv 5: a stuck-key report for " role ", which isn't stuck, changed the state")])
      (concat
       (when (vr after :held role) [(bad "07 inv 5: " role " is still held after its stuck-key report")])
       (when (:held? (pulse after role)) [(bad "07 inv 5: " role "'s pulse is still held after its stuck-key report")])
       (when (not= {:role role :why :stuck-key} (vr after :problem))
         [(bad "07 inv 5: a stuck " role " left the problem " (pr-str (vr after :problem)))])
       (when (not= (vr before :current) (vr after :current)) [(bad "07: a stuck " role " changed the vibe")])
       (when (not= (= :locked (vr before :rotation)) (= :locked (vr after :rotation)))
         [(bad "07: a stuck " role " locked or unlocked the rotation")])
       (when (not= (dissoc (vr before :held) role) (vr after :held))
         [(bad "07 inv 5: a stuck " role " changed another role's hold")])
       (check-release before after role t true)))))

(defn- check-stuck-holds
  "07 invariant 5 after the clock's ticks up to now (view: the last tick's
   pulse view): nothing is held longer than the limit, the watchdog let go of
   it."
  [state now view]
  (concat
   (for [[role {:keys [at]}] (vr state :held)
         :when (>= (- now at) hold-limit-ms)]
     (bad "07 inv 5: " role " is held " (- now at) " ms, past the limit, after a tick"))
   (for [r [:hit :accent :flash]
         :let [{:keys [held? ms]} (get view r)]
         :when (and (:held? (pulse state r)) (nil? (vr state :held r)) held? (>= ms hold-limit-ms))]
     (bad "07 inv 5: " r "'s pulse is held " ms " ms, past the limit, after a tick"))))

(defn- locked? [state] (true? (get-in state [:ui :show-lock?])))

(defn- without-notices [state] (update state :ui assoc :notice nil :show-lock-notice nil))

(defn- requests-without-ids
  "state's requests without their ids (Stop's restart has a random one)."
  [state]
  (update-in state [:playback :requests] #(update-vals (or % {}) (fn [r] (dissoc r :id)))))

(defn- check-lock
  "07 invariants 1, 2 and 8 for a step's event while locked: one the lock
   refuses changes nothing but the notice; unlocking changes the switch and
   the notice only; a vibe event does what it would unlocked."
  [before {:keys [kind event]} after t]
  (when (and event (locked? before))
    (let [type (:event/type event)]
      (cond
        (contains? show-lock/blocked-events type)
        (when (not= (without-notices before) (without-notices after))
          [(bad "07 inv 1: " type " changed more than the notice while locked")])

        (= :vibe/set-show-lock type)
        (when (and (not (:locked? event))
                   (not= (update before :ui assoc :show-lock? false :show-lock-notice nil) after))
          ["07 inv 2: unlocking changed more than the switch and the notice"])

        (= :vibe kind)
        (when (not= (requests-without-ids after)
                    (requests-without-ids (assoc-in (run (assoc-in before [:ui :show-lock?] false) event t) [:ui :show-lock?] true)))
          [(bad "07 inv 8: " type " " (:role event) " did otherwise while locked")])))))
(defn- check-pulses
  "Phase 4's pulses (plans/2026-11-18-vibe-mode/04 §7): a HIT, ACCENT or
   FLASH press outside blackout pulses, held, whatever it plays; every
   release lets its pulse go, and release-all those its source held;
   BLACKOUT never pulses; :vibe pulses once per change a VIBE tap, a select
   or an auto-advance makes (06: a gap, or a FLASH release while armed),
   never during blackout, and for nothing else."
  [before {:keys [event/type role only src reason]} after]
  (let [blackout? (vr before :blackout?)
        id (fn [s r] (:id (pulse s r) 0))
        vibe-change? (and (or (#{:vibe/select :vibe/advance} type) (and (= :vibe/release type) (#{:vibe :flash} role)))
                          (not= (vr before :current) (vr after :current))
                          (not blackout?))]
    (concat
     (when (and (= :vibe/press type) (#{:hit :accent :flash} role) (not blackout?)
                (not= {:id (inc (id before role)) :held? true :src (or src :input)} (pulse after role)))
       [(bad "04: a " role " press left its pulse " (pr-str (pulse after role)))])
     (when (and (= :vibe/press type) (= :blackout role)
                (not= (map #(id before %) [:hit :accent :flash :vibe]) (map #(id after %) [:hit :accent :flash :vibe])))
       ["04 inv 9: BLACKOUT pulsed"])
     ;; A stuck key's report that finds nothing stuck changes nothing (check-watchdog)
     (when (and (= :vibe/release type) (not= :watchdog reason) (:held? (pulse after role)))
       [(bad "04: " role "'s pulse is held after its release")])
     (when (= :vibe/release-all type)
       (for [r [:hit :accent :flash]
             :when (and (:held? (pulse after r)) (or (nil? only) (= only (:src (pulse before r)))))]
         (bad "04: release-all " (pr-str only) " kept " r "'s pulse held")))
     (when (not= (id after :vibe) (cond-> (id before :vibe) vibe-change? inc))
       [(bad "04: " type " " role " moved :vibe's pulse from " (id before :vibe) " to " (id after :vibe)
             (when vibe-change? ", for a change"))]))))


;; Auto-advance (plans/2026-11-18-vibe-mode/06 §7): what may change the vibe
;; by itself is worked out here, not with laser-show.vibes.rotation

(defn- blockers
  "Why the vibe of state can't change by itself now: a set of :auto-off
   :locked :blackout :one-vibe :nothing-entered :key-down :not-armed."
  [state]
  (let [switch (vr state :auto?)
        auto? (if (some? switch) switch (= :auto (get-in state [:config :vibes :settings :rotation :mode])))]
    (cond-> #{}
      (not auto?) (conj :auto-off)
      (= :locked (vr state :rotation)) (conj :locked)
      (vr state :blackout?) (conj :blackout)
      (< (count (get-in state [:config :vibes :order])) 2) (conj :one-vibe)
      (not (and (vr state :entered-at) (get-in state [:config :vibes :library (vr state :current)]))) (conj :nothing-entered)
      (or (seq (vr state :held)) (some #(:held? (pulse state %)) [:hit :accent :flash])) (conj :key-down)
      (not= :armed (vr state :rotation)) (conj :not-armed))))

(defn- next-of [state id]
  (let [order (get-in state [:config :vibes :order])]
    (or (second (drop-while #(not= id %) order)) (first order))))

(defn- check-auto
  "06 invariants 1-3 for what nobody pressed: an arm, an advance (the
   clock's or the walk's), and the change a FLASH release makes (type
   ::flash-release, with before the state the release left: FLASH no longer
   down). A change of the vibe needs nothing to block it, :from current and,
   for a gap, 2 beats without a HIT; it goes to the next vibe, which is
   :active from t, with one more :vibe pulse. Arming needs the same but for
   the keys, and 3 s since the vibe was entered. An arm or an advance that
   does neither changes nothing."
  [before {:keys [event/type reason from]} after t]
  (let [old (vr before :current)
        changed? (not= old (vr after :current))
        armed? (and (= :armed (vr after :rotation)) (not= :armed (vr before :rotation)))
        last-hit (vr before :last-hit-at)
        entered (vr before :entered-at)]
    (concat
     (when changed?
       (concat
        (when-let [why (seq (blockers before))]
          [(bad "06 inv 1: " type " " reason " changed " old " though " (vec why))])
        (when (not= from old)
          [(bad "06 inv 2: an advance from " from " changed " old)])
        (when (and (= :gap reason) last-hit (< (- t last-hit) (:gap rotation-ms)))
          [(bad "06 inv 1: a gap change " (- t last-hit) " ms after a HIT")])
        (when (not= (next-of before old) (vr after :current))
          [(bad "06: " old " changed to " (vr after :current) ", not to the next of " (get-in before [:config :vibes :order]))])
        (when (not= [t :active] [(vr after :entered-at) (vr after :rotation)])
          [(bad "06 inv 3: the new vibe is " (vr after :rotation) " from " (vr after :entered-at) ", not :active from " t)])
        (when (not= (inc (:id (pulse before :vibe) 0)) (:id (pulse after :vibe) 0))
          [(bad "06: an automatic change didn't pulse :vibe once")])))
     (when armed?
       (concat
        (when-not (= :vibe/arm type) [(bad "06: " type " armed the rotation")])
        (when-let [why (seq (disj (blockers before) :key-down :not-armed))]
          [(bad "06 inv 1: armed " old " though " (vec why))])
        (when (or (nil? entered) (< (- t entered) (:seconds rotation-ms)))
          [(bad "06: armed " old " at " t ", entered at " entered)])
        (when changed? ["06: arming changed the vibe"])))
     (when (and (= :vibe/arm type) (not armed?) (not= before after))
       ["06: an arm that didn't arm changed the state"])
     (when (and (= :vibe/advance type) (not changed?) (not= before after))
       ["06 inv 2: an advance that didn't change the vibe changed the state"])
     (when (and (= ::flash-release type) (not changed?) (empty? (blockers before)))
       ["06: FLASH was let go of while armed and nothing else was down, and the vibe stayed"]))))

(defn- check-rotation
  "06 invariants 1-4 for a vibe event of the walk: the arms and advances
   (check-auto), a FLASH release's change, AUTO's switch (it changes no
   document root), and that every change of the vibe starts its time again,
   as unlocking and BLACKOUT going off do."
  [before {:keys [event/type role] :as event} after t]
  (let [changed? (not= (vr before :current) (vr after :current))
        restarted (fn [what]
                    (when (or (not= t (vr after :entered-at)) (= :armed (vr after :rotation)))
                      [(bad "06 inv 3: " what " left the vibe's time from " (vr after :entered-at)
                            " (" (vr after :rotation) "), not from " t)]))]
    (concat
     (case type
       (:vibe/arm :vibe/advance) (check-auto before event after t)
       :vibe/release (cond
                       ;; A stuck key's: check-watchdog
                       (= :watchdog (:reason event)) nil
                       (= :flash role) (when (vr before :held :flash)
                                         (check-auto (-> before (update-in [:vibe-runtime :held] dissoc :flash) (let-go :flash))
                                                     {:event/type ::flash-release :reason :flash :from (vr before :current)}
                                                     after t))
                       :else (when (and changed? (not= :vibe role))
                               [(bad "06 inv 1: releasing " role " changed the vibe")]))
       :vibe/set-auto (concat
                       (when changed? ["06: AUTO's switch changed the vibe"])
                       (when-not (history/document-roots-identical? before after)
                         ["06 inv 4: AUTO's switch changed a document root"])
                       (when (and (not (:auto? event)) (= :armed (vr after :rotation)))
                         ["06: AUTO off left the rotation armed"])
                       (when (and (:auto? event) (contains? (blockers before) :auto-off) (vr before :current))
                         (restarted "AUTO turned on")))
       :vibe/release-all (when changed? ["06 inv 1: release-all changed the vibe"])
       nil)
     (when (and changed? (vr after :current) (not (#{:vibe/arm :vibe/advance} type)) (not= [:vibe/release :flash] [type role]))
       (restarted (str type " " role "'s change of the vibe")))
     (when (and (= [:vibe/release :vibe] [type role]) (= [:locked :active] [(vr before :rotation) (vr after :rotation)]))
       (restarted "unlocking"))
     (when (and (= [:vibe/press :blackout] [type role]) (vr before :blackout?)
                (get-in after [:config :vibes :library (vr after :current)]))
       (restarted "BLACKOUT going off")))))

(defn- check-vibe-event
  "Invariants 4, 5, 7, 11 and 12 for any vibe event, and 1, 2, 6, 9 for presses."
  [before {:keys [event/type role only] :as event} after t]
  (let [ours (set (keep :run (vals (vr before :voices))))
        theirs (for [[cell {:keys [run]}] (get-in before [:playback :active-cues])
                     :when (not (contains? ours run))]
                 cell)]
    (concat
     (check-pulses before event after)
     (for [cell theirs
           :when (nil? (get-in after [:playback :active-cues cell]))]
       (bad "inv 4: " type " stopped " cell ", which the vibe layer didn't start"))
     (when (not= (get-in before [:history :undo-stack]) (get-in after [:history :undo-stack]))
       ["inv 7: a vibe event made an undo step"])
     (when (not= (get-in before [:project :dirty?]) (get-in after [:project :dirty?]))
       ["inv 7: a vibe event changed the unsaved flag"])
     (when (or (history/undoable-changed? (doc before) (doc after))
               (history/persisted-changed? (doc before) (doc after)))
       ["inv 7: a vibe event changed the document beyond effect cells' :active?"])
     (when (and (#{:hit :accent} role)
                (#{:vibe/press :vibe/release} type)
                (= (vr before :current) (vr after :current))
                (not (history/document-roots-identical? before after)))
       [(bad "inv 7: a " role " " (name type) " changed a document root")])
     (let [vibe (library/current-vibe after)]
       (for [[role {:keys [cell run]}] (vr after :voices)
             :when (and run (not (contains? ours run)) (not (may-play? after vibe cell)))]
         (bad "inv 1: " type " started " cell " for " role ", which " (:id vibe) " may not play")))
     (for [[[_ role :as k] bag1] (vr after :bags)
           :when (not= bag1 (vr before :bags k))
           :let [run (get-in after [:playback :active-cues (:last bag1) :run])]
           ;; Two roles waiting on one solo flash may pick the same cell when
           ;; it lets go (their pools share it): it starts once, for both
           :when (not (and run (not (contains? ours run)) (some #(= run (:run %)) (vals (vr after :voices)))))]
       (bad "inv 3: " type " picked " (:last bag1) " for " k ", which didn't start: it lost its turn"))
     (let [b1 (vr after :voices :bed)
           vibe (library/current-vibe after)]
       (when (and b1 (not= (vr before :current) (vr after :current)) (not= (:cell b1) (:bed vibe)))
         [(bad "inv 1: entering " (:id vibe) " started " (:cell b1) " as its bed, not " (:bed vibe))]))
     (when (not= (handled before event t) (handled before event t))
       ["inv 11: the same state, event and time gave another result"])
     (case type
       :vibe/press (check-press before after role t)
       :vibe/release (if (= :watchdog (:reason event))
                       (check-watchdog before event after t)
                       (check-release before after role t false))
       :vibe/release-all (let [roles (for [[r {:keys [src]}] (vr before :held)
                                           :when (or (nil? only) (= only src))]
                                       r)]
                           (concat (mapcat #(check-release before after % t true) roles)
                                   (when (some (set roles) (keys (vr after :held)))
                                     ["release-all kept a hold it should let go of"])))
       nil))))

(defn- rename [from to] #(if (= from %) to %))

(defn- check-vibe-edit
  "Phase 3's edits (engine/reconcile): they stop no cue the vibe layer
   didn't start (invariant 4) and start only what may play (1). When they
   edit the vibe that plays, a HIT or ACCENT cue plays on unless the vibe
   may no longer play it (07: Allow audience cells unticked stops every
   audience cue at once, and no voice plays one after), a cell its FX set
   gains is on, one it loses that the set switched on is off (unless the
   held flash asked for it), and no other effect cell changes."
  [before after]
  (let [ours (set (keep :run (vals (vr before :voices))))
        current (vr before :current)
        old (library/vibe before current)
        new (library/vibe after current)
        refreshed? (and old new (= current (vr after :current)) (not (vr before :blackout?)))
        existing (fn [state cells] (set (filter #(get-in state [:chains :effect-chains %]) cells)))
        was (existing before (:fx old))
        now (existing after (:fx new))
        touched (into was now)
        on (fn [state] (update-vals (get-in state [:chains :effect-chains]) #(true? (:active? %))))]
    (concat
     (for [[cell {:keys [run]}] (get-in before [:playback :active-cues])
           :when (and (not (contains? ours run)) (nil? (get-in after [:playback :active-cues cell])))]
       (bad "inv 4: an edit stopped " cell ", which the vibe layer didn't start"))
     (for [[role {:keys [cell run]}] (vr after :voices)
           :when (and run (not (contains? ours run))
                      (not (may-play? after (library/vibe after (vr after :current)) cell)))]
       (bad "inv 1: an edit started " cell " for " role ", which " (vr after :current) " may not play"))
     (when refreshed?
       (concat
        (for [role [:hit :accent]
              :let [voice (vr before :voices role)]
              :when (and voice (live? before voice) (not (live? after voice)) (may-play? after new (:cell voice)))]
          (bad "an edit of " current " stopped its " role " cue"))
        (for [[role {:keys [cell] :as voice}] (vr after :voices)
              :when (and (live? after voice) (may-play? before old cell) (not (may-play? after new cell)))]
          (bad "07: an edit of " current "'s audience rule left " role " playing " cell ", which it may no longer play"))
        (for [cell (remove was now) :when (not (fx-on? after cell))]
          (bad "an edit gave " current "'s FX set " cell ", which is off"))
        (for [cell (remove now was)
              :when (and (some #{cell} (vr before :fx-on))
                         (not (some #{cell} (vr after :voices :flash :fx)))
                         (fx-on? after cell))]
          (bad "an edit took " cell " out of " current "'s FX set, and it is still on"))
        (let [on-before (on before)]
          (for [[cell on?] (on after)
                :when (and (not (touched cell)) (not= on? (on-before cell false)))]
            (bad "an edit switched " cell ", which " current "'s FX set neither gained nor lost"))))))))

(defn- check-moved
  "Invariant 10 for a move or clear of a cue (grid? true) or effect cell."
  [before after {:keys [event/type from-col from-row to-col to-row col row]} grid?]
  (let [cells (if grid?
                (fn [vibe] {:hit (get-in vibe [:hit :cells]) :accent (get-in vibe [:accent :cells])
                            :flash [(get-in vibe [:flash :cue])] :bed [(:bed vibe)]})
                (fn [vibe] {:fx (:fx vibe) :flash-fx (get-in vibe [:flash :fx])}))
        grid (if grid? :cue-chains :effect-chains)
        move? (#{:grid/move-cell :effects/move-cell} type)
        from (if move? [from-col from-row] [col row])
        to [to-col to-row]
        expected (fn [xs] (if move?
                            (vec (distinct (map (rename from to) xs)))
                            (mapv #(when (not= from %) %) xs)))]
    (when (if move? (and (get-in before [:chains grid from]) (not= from to)) true)
      (for [[id vibe] (get-in before [:config :vibes :library])
            [k xs] (cells vibe)
            :let [now (get (cells (get-in after [:config :vibes :library id])) k)
                  want (if (#{:flash :bed} k) (expected xs) (vec (remove nil? (expected xs))))]
            :when (not= (vec (remove nil? now)) (vec (remove nil? want)))]
        (bad "inv 10: " type " left " id " " k " as " (pr-str now) ", not " (pr-str want))))))

(defn- check-clock
  "Invariant 8 on the show clock's runs (their :hold? is the intent's at the
   last tick)."
  [rt]
  (for [[id {:keys [decay-beats hold? ended? timeline? accumulated-beats]}] (:runs rt)
        :when decay-beats
        :let [problem (cond
                        (and hold? ended? (not timeline?)) "a held run ended by decay"
                        (and (not hold?) (not ended?) (>= accumulated-beats decay-beats)) "a let-go run is past its decay")]
        :when problem]
    (bad "inv 8: " problem " " id)))


;; Bag cycles (invariant 3): every playable cell once per cycle, while the
;; pool stays the same (a cell that leaves the pool and comes back is new).
;; check-vibe-event checks that every pick starts its cell.

(defn- track-cycles
  "cycles {bag-key {:picked #{} :items #{} :clean? bool}} after a step: a
   pick joins its cycle (twice is a problem), a refill closes it (all items,
   if the pool didn't change meanwhile), and a move renames what was picked.
   A pick can come in a tick too (a stuck FLASH let go of starts what it
   kept waiting), and an undo or a redo of the document may rename cells
   behind the bags' backs: the cycles then aren't clean."
  [cycles before after step]
  (let [{:keys [event]} step
        cycles (cond
                 (and (= :grid (:kind step)) (= :grid/move-cell (:event/type event))
                      (get-in before [:chains :cue-chains [(:from-col event) (:from-row event)]])
                      (not= [(:from-col event) (:from-row event)] [(:to-col event) (:to-row event)])
                      ;; not one the show lock refused
                      (not= (get-in before [:chains :cue-chains]) (get-in after [:chains :cue-chains])))
                 (let [from [(:from-col event) (:from-row event)]
                       to [(:to-col event) (:to-row event)]]
                   (update-vals cycles #(update % :picked (fn [p] (set (map (rename from to) (disj p to)))))))

                 (and (= :undo (:kind step)) (not= (doc before) (doc after)))
                 (update-vals cycles #(assoc % :clean? false))

                 :else cycles)]
    (reduce-kv
     (fn [[cycles problems] k bag1]
       (let [bag0 (vr before :bags k)
             c (get cycles k)
             c (if (and c (not= (set (:items bag1)) (:items c))) (assoc c :clean? false :items (set (:items bag1))) c)]
         (if (or (= bag0 bag1) (not (#{:vibe :tick} (:kind step))))
           [(assoc cycles k c) problems]
           (let [pick (:last bag1)]
             (if (or (nil? c) (nil? bag0) (> (:n bag1 0) (:n bag0 0)))
               [(assoc cycles k {:picked #{pick} :items (set (:items bag1)) :clean? true})
                (cond-> problems
                  (and c (:clean? c) (not= (:picked c) (:items c)))
                  (conj (bad "inv 3: bag " k " refilled after playing " (sort (:picked c)) " of " (sort (:items c)))))]
               [(assoc cycles k (update c :picked conj pick))
                (cond-> problems
                  (and (:clean? c) (contains? (:picked c) pick))
                  (conj (bad "inv 3: bag " k " played " pick " twice in one cycle")))])))))
     [cycles []]
     (or (vr after :bags) {}))))


;; The walk

(defn- step!
  "[world' problems] after one step. While the show lock is on, a direct
   edit (one an undo would make) doesn't happen: the lock refuses undo."
  [world {:keys [kind event] :as step} dt]
  (let [before (:state world)
        locked (locked? before)
        world (update world :now + dt)
        t (long (:now world))
        world (case kind
                :tick (tick world (:ticks step))
                :edit (cond-> world (not locked) (update :state edit (:edit step)))
                (assoc world :state (run before event t)))
        after (:state world)
        [cycles cycle-problems] (track-cycles (:cycles world) before after step)]
    [(assoc world :cycles cycles :next (:next step) :tick-problems [])
     (concat (check-always after)
             cycle-problems
             (:tick-problems world)
             (check-lock before step after t)
             (case kind
               :vibe (concat (check-vibe-event before event after t)
                             (check-rotation before event after t))
               :tick (concat (check-clock (:rt world))
                             (check-stuck-holds after (:now world) (show-time/pulse-view (:rt world)))
                             ;; One :vibe pulse for each tick that changed the vibe: a long
                             ;; tick step (a stuck key's 10 s) may change it more than once
                             (when (and (pos? (:tick-changes world 0)) (empty? (:tick-problems world)))
                               (let [changes (- (:id (pulse after :vibe) 0) (:id (pulse before :vibe) 0))]
                                 (when (not= (:tick-changes world) changes)
                                   [(bad "06: a tick step changed the vibe " (:tick-changes world) " times with "
                                         changes " :vibe pulses")]))))
               :vibe-edit (when-not locked (check-vibe-edit before after))
               :grid (when (and (not locked) (#{:grid/move-cell :grid/clear-cell} (:event/type event)))
                       (check-moved before after event true))
               :fx (when-not locked (check-moved before after event false))
               nil))]))

(defn new-world
  "The walk's world before its first step (for engine-soak-test)."
  []
  {:state (world-state) :rt show-time/initial-runtime :now 1000.0 :cycles {}})

(defn random-step
  "[step dt]: the walk's next step and the ms before it (for engine-soak-test)."
  [^SplittableRandom rng world]
  (let [step (next-step rng world)]
    [step (.nextInt rng (if (:quick? step) 140 300))]))

(defn take-step
  "[world' problems] after step, as the walk takes it (for engine-soak-test)."
  [world step dt]
  (try
    (step! world step dt)
    (catch Throwable e
      [world [(bad "exception: " (.getMessage e))]])))

(defn- walk
  "nil, or {:seed :step :problems :prefix} for the first step that breaks an
   invariant or throws."
  [seed]
  (let [rng (SplittableRandom. (long seed))]
    (loop [world (new-world)
           i 0
           prefix []]
      (when (< i steps)
        (let [[step dt] (random-step rng world)
              prefix (conj prefix (-> step (dissoc :next) (assoc :dt dt)))
              [world' problems] (take-step world step dt)]
          (if-let [problems (seq (remove nil? problems))]
            {:seed seed :step i :problems problems :prefix prefix}
            (recur world' (inc i) prefix)))))))

(deftest engine-invariants-hold-for-random-events-test
  (doseq [seed seeds]
    (let [failure (walk seed)]
      (when failure
        (println (str "VIBE FUZZ FAILURE, seed " seed " (VIBE_FUZZ_SEED=" seed "), step " (:step failure) ":\n"
                      (str/join "\n" (:problems failure))
                      "\nShortest failing prefix (" (count (:prefix failure)) " steps):\n"
                      (pr-str (:prefix failure)))))
      (is (nil? failure) (str "seed " seed ": " (first (:problems failure)))))))
