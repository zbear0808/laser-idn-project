(ns laser-show.vibes.engine-fuzz-test
  "A seeded walk of 2,000 random events through the real handlers and the
   show clock's step: presses and releases of every role from either source,
   vibe switches, blackouts, clock ticks and their reports, clicks on the grid
   (some on a cell a voice plays, which takes its cue over), moves and clears
   of cue and effect cells, vibes deleted, put back and reordered as an undo
   would (no handler), and the Vibes tab's edits of FX sets, beds, pools,
   the audience rule and deletes through their handlers (engine/reconcile).
   After every step it checks invariants 1-12
   of plans/2026-11-18-vibe-mode/01-phase1-engine-core.md §7, and the pulses
   presses write for tap reactions (04 §7, check-pulses); what may play
   (invariant 1) is worked out here, not with laser-show.vibes.library.
   A failure prints the seed and the events up to the failing step, the
   shortest failing prefix. To run one seed: VIBE_FUZZ_SEED=<seed>; seeds 1
   to n: VIBE_FUZZ_SEEDS=<n>."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [laser-show.animation.show-time :as show-time]
            [laser-show.events.core :as events]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.templates :as templates]
            [laser-show.timeline.playback :as playback]
            [laser-show.triggers.shuffle-bag :as bag]
            [laser-show.vibes.library :as library])
  (:import [java.util SplittableRandom]))

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

(def ^:private shape {:items [{:type :preset :preset-id :circle}] :destination-zone {:zone-group-id :center}})

(def ^:private extra-cues
  {[0 1] shape
   [1 1] shape
   [2 1] (assoc shape :destination-zone {:zone-group-id :crowd})
   [5 1] (assoc shape :destination-zone {:zone-group-id nil})
   [3 1] {:editor-type :timeline :timeline/chase :mtc :timeline/tempo-bpm 120.0
          :items [{:type :preset :preset-id :circle :timeline/start 0.0 :timeline/duration 4.0}]}
   [4 1] {:editor-type :timeline :destination-zone {:zone-group-id :center}
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
      (update-in [:chains :cue-chains] update-vals #(assoc % :destination-zone {:zone-group-id :center}))
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
      ;; The pipeline here runs no effects, so a tap could never move the BPM;
      ;; off, HIT's events keep every document root as it was (invariant 7)
      (assoc-in [:config :vibes :settings :hit-taps-tempo?] false)
      (history/mark-clean)))

(def ^:private cue-cells (vec (sort (keys (get-in (world-state) [:chains :cue-chains])))))
(def ^:private fx-cells [[0 3] [1 3] [2 3]])
(def ^:private tick-ms (/ 1000.0 60))


;; Running events and the clock

(defn- run
  "state after event through the real pipeline and each event it dispatches,
   as the agent runs them (no effect beyond :state and :dispatch)."
  [state event t]
  (let [effects (events/handle-event-pipeline (assoc event :state state :time t))
        s (or (:state effects) state)]
    (if-let [next-event (:dispatch effects)]
      (run s next-event t)
      s)))

(defn- handled
  "The pipeline's effects for event alone: what invariant 11 compares, since
   what it dispatches is other handlers' (Stop's restart request has a random
   id)."
  [state event t]
  (events/handle-event-pipeline (assoc event :state state :time t)))

(defn- tick
  "The world k ticks of the show clock on, its reports handled."
  [{:keys [state rt now] :as world} k]
  (reduce (fn [{:keys [state rt now] :as w} _]
            (let [now (+ now tick-ms)
                  {:keys [runtime reports]} (show-time/step rt state nil now)]
              (assoc w :rt runtime :now now
                     :state (reduce #(run %1 %2 (long now)) state reports))))
          (assoc world :state state :rt rt :now now)
          (range k)))


;; The generator

(defn- one-of [^SplittableRandom rng xs] (nth xs (.nextInt rng (count xs))))

(defn- next-step
  "A random step for world: {:kind :event? :next?}, kinds :vibe, :tick, :grid,
   :fx, :edit and :vibe-edit; a step's :next is the step after it. Blackout is pressed
   rarely, and more often while it is on. Half the HIT and ACCENT presses are
   taps: the release follows within 140 ms. Half the grid's clicks start a
   cell a voice plays again (its cue is someone else's now), and half of those
   are followed by BLACKOUT, which stops every voice: the one event that stops
   voices without forgetting stale ones first."
  [^SplittableRandom rng {:keys [state next]}]
  (or next
      (let [blackout? (get-in state [:vibe-runtime :blackout?])
            role #(one-of rng [:hit :hit :accent :flash :vibe])
            cell #(one-of rng cue-cells)
            vibe #(one-of rng [:vibe-1 :vibe-2 :vibe-3 :vibe-4])
            voice-cells (vec (distinct (keep :cell (vals (get-in state [:vibe-runtime :voices])))))]
        (if (< (.nextInt rng 100) (if blackout? 20 1))
          {:kind :vibe :event {:event/type :vibe/press :role :blackout}}
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
                      (not (#{:crowd :all} (or (get-in cue [:destination-zone :zone-group-id]) :all))))))))

(defn- pool-of [state vibe role]
  (filterv #(may-play? state vibe %) (distinct (get-in vibe [role :cells]))))

(defn- bad [& kvs] (apply str kvs))

(defn- pulse [state role] (get-in state [:playback :pulses role]))

(defn- let-go
  "state with role's pulse let go, as every release of role does (Phase 4)."
  [state role]
  (cond-> state
    (:held? (pulse state role)) (update-in [:playback :pulses role] #(-> % (assoc :held? false) (dissoc :src)))))

(defn- check-always
  "Invariants 1, 3, 4 and 6 on any state. (A voice's cell can come to follow
   timecode or reach the crowd only by a move under a playing intent, which
   the clock drops at its next tick: presses are checked in check-press.)"
  [state]
  (let [voices (filter #(live? state (val %)) (vr state :voices))]
    (concat
     (for [[role {:keys [cell]}] voices
           :when (empty? (get-in state [:chains :cue-chains cell :items]))]
       (bad "inv 1: the " role " voice plays " cell ", which has no content"))
     (when (not= (count voices) (count (set (map (comp :run val) voices))))
       [(bad "inv 4: two roles own one run " (pr-str voices))])
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
  "Invariants 1, 2, 6 and 9 for a press."
  [before after role]
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
                                 (not= (-> before
                                           (assoc-in [:vibe-runtime :problem] (vr after :problem))
                                           (assoc-in [:playback :pulses role] (pulse after role)))
                                       after))
                        [(bad "inv 1: an empty " role " pool changed more than :problem and its pulse")]))
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

(defn- check-pulses
  "Phase 4's pulses (plans/2026-11-18-vibe-mode/04 §7): a HIT, ACCENT or
   FLASH press outside blackout pulses, held, whatever it plays; every
   release lets its pulse go, and release-all those its source held;
   BLACKOUT never pulses; :vibe pulses once per change a VIBE tap or a
   select makes, never during blackout, and for nothing else."
  [before {:keys [event/type role only src]} after]
  (let [blackout? (vr before :blackout?)
        id (fn [s r] (:id (pulse s r) 0))
        vibe-change? (and (or (= :vibe/select type) (and (= :vibe/release type) (= :vibe role)))
                          (not= (vr before :current) (vr after :current))
                          (not blackout?))]
    (concat
     (when (and (= :vibe/press type) (#{:hit :accent :flash} role) (not blackout?)
                (not= {:id (inc (id before role)) :held? true :src (or src :input)} (pulse after role)))
       [(bad "04: a " role " press left its pulse " (pr-str (pulse after role)))])
     (when (and (= :vibe/press type) (= :blackout role)
                (not= (map #(id before %) [:hit :accent :flash :vibe]) (map #(id after %) [:hit :accent :flash :vibe])))
       ["04 inv 9: BLACKOUT pulsed"])
     (when (and (= :vibe/release type) (:held? (pulse after role)))
       [(bad "04: " role "'s pulse is held after its release")])
     (when (= :vibe/release-all type)
       (for [r [:hit :accent :flash]
             :when (and (:held? (pulse after r)) (or (nil? only) (= only (:src (pulse before r)))))]
         (bad "04: release-all " (pr-str only) " kept " r "'s pulse held")))
     (when (not= (id after :vibe) (cond-> (id before :vibe) vibe-change? inc))
       [(bad "04: " type " " role " moved :vibe's pulse from " (id before :vibe) " to " (id after :vibe)
             (when vibe-change? ", for a change"))]))))

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
       :vibe/press (check-press before after role)
       :vibe/release (check-release before after role t false)
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
   edit the vibe that plays, a HIT or ACCENT cue plays on, a cell its FX set
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
              :when (and voice (live? before voice) (not (live? after voice)))]
          (bad "an edit of " current " stopped its " role " cue"))
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
   if the pool didn't change meanwhile), and a move renames what was picked."
  [cycles before after step]
  (let [{:keys [event]} step
        cycles (if (and (= :grid (:kind step)) (= :grid/move-cell (:event/type event))
                        (get-in before [:chains :cue-chains [(:from-col event) (:from-row event)]])
                        (not= [(:from-col event) (:from-row event)] [(:to-col event) (:to-row event)]))
                 (let [from [(:from-col event) (:from-row event)]
                       to [(:to-col event) (:to-row event)]]
                   (update-vals cycles #(update % :picked (fn [p] (set (map (rename from to) (disj p to)))))))
                 cycles)]
    (reduce-kv
     (fn [[cycles problems] k bag1]
       (let [bag0 (vr before :bags k)
             c (get cycles k)
             c (if (and c (not= (set (:items bag1)) (:items c))) (assoc c :clean? false :items (set (:items bag1))) c)]
         (if (or (= bag0 bag1) (not= :vibe (:kind step)))
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
  "[world' problems] after one step."
  [world {:keys [kind event] :as step} dt]
  (let [before (:state world)
        world (update world :now + dt)
        t (long (:now world))
        world (case kind
                :tick (tick world (:ticks step))
                :edit (update world :state edit (:edit step))
                (assoc world :state (run before event t)))
        after (:state world)
        [cycles cycle-problems] (track-cycles (:cycles world) before after step)]
    [(assoc world :cycles cycles :next (:next step))
     (concat (check-always after)
             cycle-problems
             (case kind
               :vibe (check-vibe-event before event after t)
               :tick (check-clock (:rt world))
               :vibe-edit (check-vibe-edit before after)
               :grid (when (#{:grid/move-cell :grid/clear-cell} (:event/type event)) (check-moved before after event true))
               :fx (check-moved before after event false)
               nil))]))

(defn- walk
  "nil, or {:seed :step :problems :prefix} for the first step that breaks an
   invariant or throws."
  [seed]
  (let [rng (SplittableRandom. (long seed))]
    (loop [world {:state (world-state) :rt show-time/initial-runtime :now 1000.0 :cycles {}}
           i 0
           prefix []]
      (when (< i steps)
        (let [step (next-step rng world)
              dt (.nextInt rng (if (:quick? step) 140 300))
              prefix (conj prefix (-> step (dissoc :next) (assoc :dt dt)))
              [world' problems] (try
                                  (step! world step dt)
                                  (catch Throwable e
                                    [world [(bad "exception: " (.getMessage e))]]))]
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
