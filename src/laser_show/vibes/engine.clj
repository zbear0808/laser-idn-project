(ns laser-show.vibes.engine
  "Vibe mode's five buttons on the current vibe (laser-show.vibes.library):
   HIT and ACCENT play the next cue of their pool, FLASH holds the big
   moment, VIBE switches (a tap) or locks (a hold), BLACKOUT kills everything
   until pressed again (plans/2026-11-18-vibe-mode/00-overview.md §4).

   Pure: press, release, select and release-all take state and the event
   ({:role :input-time :time :src}, {:vibe}, {:only}) and return {:state}
   plus a :dispatch for HIT's tap of the tempo, blackout's transport stop
   and play, and the change a FLASH release makes while armed; reconcile
   brings what plays up to date after an edit of the vibes. The engine keeps
   [:vibe-runtime] (state.domains), starts and
   stops cues through their intents (cue-timing/activate-cue,
   deactivate-cue; the show clock ends a decay) and switches effect cells'
   :active?. Times are the event's own: the input's when it has one, else the
   pipeline's :time.

   Each press records what it started (a voice), and its release undoes
   exactly that, whatever the vibe is by then. A voice is stopped only while
   its cue's intent still names its run, so the engine never stops a cue that
   something else started since. Picks come from shuffle bags seeded by the
   press's time, and run ids from a counter, so the same state and event give
   the same result.

   Every press of HIT, ACCENT and FLASH is also a pulse, held until its
   release, and every vibe change one of :vibe ([:playback :pulses], which
   the show clock stamps for tap reactions, laser-show.animation.pulses),
   whether or not the press plays anything. Nothing pulses during blackout.

   With auto-advance on (laser-show.vibes.rotation), the vibe also changes
   by itself: arm and advance follow the show clock's reports, a FLASH
   release while armed advances, and set-auto is the Perform tab's switch.

   A key that sticks is let go of too: release with :reason :watchdog is
   the show clock's report of a role held too long (laser-show.vibes
   .watchdog), and release-unbound lets go of the roles a key holds when the
   trigger map no longer binds that key to them."
  (:require [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.events.handlers.grid :as grid]
            [laser-show.events.helpers :as h]
            [laser-show.routing.zone-maps :as zone-maps]
            [laser-show.state.extractors :as ex]
            [laser-show.triggers.shuffle-bag :as bag]
            [laser-show.vibes.library :as library]
            [laser-show.vibes.rotation :as rotation]
            [laser-show.vibes.watchdog :as watchdog])
  (:import [java.util UUID]))


;; Cues and effect cells

(defn- live?
  "Does the voice's cue still play the run it started?"
  [state {:keys [cell run]}]
  (and (some? run) (= run (get-in state [:playback :active-cues cell :run]))))

(defn- start
  "[run state]: the cue at cell started as a new run, its id the next of the
   runtime's counter at time t."
  [state [col row] opts t]
  (let [n (long (get-in state [:vibe-runtime :started] 0))
        run (UUID. (long t) n)]
    [run (-> state
             (assoc-in [:vibe-runtime :started] (inc n))
             (grid/start-cue col row (assoc opts :run run)))]))

(defn- stop-voice
  "state with the voice's cue stopped, if it still plays the voice's run."
  [state voice]
  (if (and voice (live? state voice))
    (update state :playback cue-timing/deactivate-cue (:cell voice))
    state))

(defn- forget-stale
  "Voices whose cue no longer plays their run (it ended, was stopped, cleared
   or started again by something else) are forgotten. The flash's keeps what
   it switched on until its release, without its cue."
  [state]
  (reduce-kv (fn [s role voice]
               (cond
                 (or (nil? (:run voice)) (live? s voice)) s
                 (= role :flash) (update-in s [:vibe-runtime :voices :flash] dissoc :cell :run)
                 :else (update-in s [:vibe-runtime :voices] dissoc role)))
             state
             (get-in state [:vibe-runtime :voices])))

(defn- existing-fx [state cells]
  (filterv #(get-in state [:chains :effect-chains %]) (distinct cells)))

(defn- switch-on
  "[state cells]: those of cells that were off, now on. A Map cycle among
   them carries on if a map pad paused it (zones plan Z5)."
  [state cells]
  (let [[state on] (reduce (fn [[s on] cell]
                             (if (get-in s [:chains :effect-chains cell :active?])
                               [s on]
                               [(assoc-in s [:chains :effect-chains cell :active?] true) (conj on cell)]))
                           [state []]
                           (existing-fx state cells))]
    [(zone-maps/resume-cycle state on) on]))

(defn- switch-off [state cells]
  (reduce (fn [s cell]
            (cond-> s
              (get-in s [:chains :effect-chains cell :active?])
              (assoc-in [:chains :effect-chains cell :active?] false)))
          state
          cells))

(defn- problem [state p]
  (assoc-in state [:vibe-runtime :problem] p))

(defn- refusal
  "The problem a vibe reports when it left effect cells off because they
   can send pictures to the crowd (zones plan Z5, library/playable-fx): the
   first of refused, or nil."
  [refused]
  (when-let [cell (first refused)]
    {:why :audience-fx :cell cell}))

(defn- good-press
  "The problem after a press that played: none, but a cell the vibe left
   off stays said until the vibe changes."
  [p]
  (when (= :audience-fx (:why p)) p))

(defn- solo-flash? [state]
  (boolean (get-in state [:vibe-runtime :voices :flash :solo?])))

(defn- playable [state vibe cell]
  (when cell
    (first (library/playable-cells state vibe [cell]))))


;; Pulses: what tap reactions follow (plans/2026-11-18-vibe-mode/04)

(defn- pulse-press
  "role's pulse after a press from src: the next id, held."
  [state role src]
  (update-in state [:playback :pulses role]
             (fn [{:keys [id]}] {:id (inc (or id 0)) :held? true :src src})))

(defn- pulse-release
  "role's pulse let go, if it is held: by whichever press, and whether or not
   the engine holds the role (a press that played nothing)."
  [state role]
  (if (get-in state [:playback :pulses role :held?])
    (update-in state [:playback :pulses role] #(-> % (assoc :held? false) (dissoc :src)))
    state))

(defn- release-pulses
  "Every role's pulse that a press from only held (all with nil) let go."
  [state only]
  (reduce (fn [s role]
            (if (or (nil? only) (= only (get-in s [:playback :pulses role :src])))
              (pulse-release s role)
              s))
          state
          [:hit :accent :flash]))


;; Entering and leaving a vibe's look: its bed and its FX set

(defn- ends-with-the-look? [role {:keys [mode]}]
  (or (= :bed role) (= :sustain mode)))

(defn- release-fx
  "The FX set gives cells back: those it switched on (:fx-on) go off, and
   one the held flash asked for stays on, now the flash's."
  [state cells]
  (let [mine (filterv (set cells) (get-in state [:vibe-runtime :fx-on]))
        flash-wants (set (get-in state [:vibe-runtime :voices :flash :fx]))
        kept (filterv flash-wants mine)]
    (-> (switch-off state (remove flash-wants mine))
        (update-in [:vibe-runtime :fx-on] #(filterv (complement (set mine)) %))
        (cond-> (seq kept) (update-in [:vibe-runtime :voices :flash :fx-on] into kept)))))

(defn- take-fx
  "vibe's FX set takes cells (those that exist and it may switch on,
   library/playable-fx): switched on, or taken over from the held flash
   that switched them on. One already on otherwise stays as it is, and
   isn't the set's to switch off. One it may not switch on (it can send
   pictures to the crowd, zones plan Z5) stays off, and is the problem."
  [state vibe cells]
  (let [flash-owned (set (get-in state [:vibe-runtime :voices :flash :fx-on]))
        existing (existing-fx state cells)
        wanted (library/playable-fx state vibe existing)
        refused (remove (set wanted) existing)
        taken (filterv flash-owned wanted)
        [state switched] (switch-on state (remove flash-owned wanted))]
    (-> state
        (update-in [:vibe-runtime :fx-on] (fnil into []) (concat taken switched))
        (cond-> (seq taken) (update-in [:vibe-runtime :voices :flash :fx-on] #(filterv (complement (set taken)) %)))
        (cond-> (seq refused) (problem (refusal refused))))))

(defn- leave-look
  "Stop the bed and the :sustain voices, forget those a solo flash stepped
   aside, and switch off the FX set's effect cells (one the held flash asked
   for stays on, now the flash's)."
  [state]
  (let [{:keys [voices duck fx-on]} (:vibe-runtime state)]
    (-> (reduce-kv (fn [s role voice]
                     (if (ends-with-the-look? role voice)
                       (-> (stop-voice s voice) (update-in [:vibe-runtime :voices] dissoc role))
                       s))
                   state
                   (dissoc voices :flash))
        (release-fx fx-on)
        (assoc-in [:vibe-runtime :duck] (into {} (remove #(ends-with-the-look? (key %) (val %))) duck)))))

(defn- start-bed
  "The vibe's bed started (kept for its release while a solo flash is held),
   unless its cell plays already: that cue isn't the vibe's to stop."
  [state vibe t]
  (let [cell (playable state vibe (:bed vibe))]
    (cond
      (or (nil? cell) (get-in state [:playback :active-cues cell])) state
      (solo-flash? state) (assoc-in state [:vibe-runtime :duck :bed] {:cell cell})
      :else (let [[run state] (start state cell {} t)]
              (assoc-in state [:vibe-runtime :voices :bed] {:cell cell :run run})))))

(defn- enter-look
  "Switch on the vibe's FX set (a cell the held flash switched on becomes the
   set's) and start its bed. Nothing during blackout."
  [state vibe t]
  (if (get-in state [:vibe-runtime :blackout?])
    state
    (-> (take-fx state vibe (:fx vibe))
        (start-bed vibe t))))

(defn- restart-rotation
  "The vibe runtime with the vibe's time (the rotation's :seconds) counted
   from t: an armed change is called off, a lock stays."
  [vibe-runtime t]
  (cond-> (assoc vibe-runtime :entered-at t)
    (= :armed (:rotation vibe-runtime)) (assoc :rotation :active)))

(defn- switch-to
  "state with vibe id current from t: the old look left, the new one entered."
  [state id t]
  (if (= id (get-in state [:vibe-runtime :current]))
    state
    (-> (leave-look state)
        (update :vibe-runtime #(-> % (assoc :current id :problem nil) (restart-rotation t)))
        (enter-look (library/vibe state id) t))))

(defn- change-vibe
  "switch-to for a change of the vibe (a VIBE tap, :vibe/select, an
   auto-advance), which pulses :vibe when the vibe does change, outside
   blackout. A press making the first vibe current (ensure-current) isn't
   one."
  [state id t]
  (cond-> (switch-to state id t)
    (and (not= id (get-in state [:vibe-runtime :current]))
         (not (get-in state [:vibe-runtime :blackout?])))
    (update-in [:playback :pulses :vibe :id] (fnil inc 0))))

(defn- ensure-current
  "state whose :current names a vibe: the first of :order, entered at t, when
   it names none (nothing current yet, or the vibe was undone or deleted)."
  [state t]
  (let [id (library/current-id state)]
    (if (and id (not= id (get-in state [:vibe-runtime :current])))
      (switch-to state id t)
      state)))


;; HIT and ACCENT

(defn- pick
  "[cell state]: the next of pool from the bag for the vibe's role, made the
   first time with the press's time as its seed."
  [state vibe-id role pool select t]
  (let [k [vibe-id role]
        bag0 (get-in state [:vibe-runtime :bags k])
        b (if bag0 (bag/sync bag0 pool) (assoc (bag/make pool) :seed (long t) :n 0))
        refill? (empty? (:remaining b))
        [cell b] (bag/next b (if (= :sequence select) identity (bag/seeded (+ (:seed b) (:n b)))))]
    [cell (assoc-in state [:vibe-runtime :bags k] (cond-> b refill? (update :n inc)))]))

(defn- play-next
  "state with the next of pool picked and started as role's voice, a :decay
   one held until its release."
  [state vibe-id role pool select mode beats t]
  (let [[cell state] (pick state vibe-id role pool select t)
        [run state] (start state cell (if (= :decay mode)
                                        {:decay-beats (double (or beats 1.0)) :hold? true}
                                        {})
                           t)]
    (assoc-in state [:vibe-runtime :voices role] {:cell cell :run run :mode mode})))

(defn- with-tap
  "effects with HIT's tap of the tempo (V3) at the press's time t, on every
   HIT press, whatever it plays, unless the setting is off or Link sync is
   on."
  [effects state role t]
  (cond-> effects
    (and (= :hit role)
         (:hit-taps-tempo? (library/settings state))
         (not (ex/link-sync-enabled? state)))
    (assoc :dispatch {:event/type :timing/tap-tempo :input-time t})))

(defn- press-pool
  "{:state :dispatch?} for a HIT or ACCENT press: the role's last cue stops
   and the next of its pool starts (:decay held until the release). While a
   solo flash is held nothing is picked, so no press spends a turn of the
   bag: a :decay press is lost, and a :gate or :sustain one picks when the
   flash lets go (restore-ducked). HIT taps the tempo either way, even when
   it plays nothing."
  [state role t src]
  (let [state (ensure-current state t)
        id (library/current-id state)
        vibe (library/vibe state id)
        {:keys [cells select mode beats]} (get vibe role)
        pool (when vibe (library/playable-cells state vibe cells))]
    (cond
      (nil? vibe) (with-tap {:state (problem state {:why :no-vibes})} state role t)
      (empty? pool) (with-tap {:state (problem state {:role role :why :empty-pool})} state role t)
      :else
      (let [mode (or mode :decay)
            state (forget-stale state)
            state (-> (stop-voice state (get-in state [:vibe-runtime :voices role]))
                      (update-in [:vibe-runtime :voices] dissoc role)
                      (update-in [:vibe-runtime :duck] dissoc role))
            state (cond
                    (not (solo-flash? state)) (play-next state id role pool select mode beats t)
                    (= :decay mode) state
                    :else (assoc-in state [:vibe-runtime :duck role] {:mode mode}))]
        (with-tap {:state (update state :vibe-runtime #(-> %
                                                           (assoc-in [:held role] {:at t :src src})
                                                           (update :problem good-press)))}
                  state role t)))))

(defn- release-pool
  "A HIT or ACCENT release (cut? for release-all): a :decay voice let go
   before :quick-tap-ms decays on, held longer it stops now; :gate stops,
   :sustain stays until replaced. The mode is the one its press played."
  [state role t cut?]
  (let [{:keys [at]} (get-in state [:vibe-runtime :held role])
        state (-> (forget-stale state)
                  (update-in [:vibe-runtime :held] dissoc role))
        {:keys [cell mode] :as voice} (get-in state [:vibe-runtime :voices role])
        tap? (and (not cut?) (< (- t at) (:quick-tap-ms (library/settings state))))]
    (cond
      (nil? voice)
      (cond-> state
        (= :gate (get-in state [:vibe-runtime :duck role :mode])) (update-in [:vibe-runtime :duck] dissoc role))

      (= :sustain mode) state

      (and (= :decay mode) tap?)
      (assoc-in state [:playback :active-cues cell :hold?] false)

      :else
      (-> (stop-voice state voice)
          (update-in [:vibe-runtime :voices] dissoc role)))))


;; FLASH

(defn- restore-ducked
  "When the solo flash lets go: what it stepped aside starts again where
   nothing else plays now, and a HIT or ACCENT pressed while it was held
   picks and plays now, as the current vibe has the role (not a :decay one;
   a :gate one is still held, or its release dropped it)."
  [state t]
  (let [id (library/current-id state)
        vibe (library/vibe state id)]
    (reduce-kv (fn [s role {:keys [cell mode]}]
                 (if cell
                   (if (and (nil? (get-in s [:playback :active-cues cell])) (playable s vibe cell))
                     (let [[run s] (start s cell {} t)]
                       (assoc-in s [:vibe-runtime :voices role] (cond-> {:cell cell :run run} mode (assoc :mode mode))))
                     s)
                   (let [{:keys [cells select] :as cfg} (get vibe role)
                         now-mode (or (:mode cfg) :decay)
                         pool (library/playable-cells s vibe cells)]
                     (if (or (empty? pool) (= :decay now-mode))
                       s
                       (play-next s id role pool select now-mode nil t)))))
               (assoc-in state [:vibe-runtime :duck] {})
               (get-in state [:vibe-runtime :duck]))))

(defn- let-go-of-flash
  "The flash's cue stopped and the effect cells it switched on switched off."
  [state]
  (let [voice (get-in state [:vibe-runtime :voices :flash])]
    (-> (stop-voice state voice)
        (switch-off (:fx-on voice))
        (update-in [:vibe-runtime :voices] dissoc :flash))))

(defn- let-go-of-flash-key
  "FLASH's hold over: the flash let go of."
  [state]
  (-> (forget-stale state)
      (let-go-of-flash)
      (update-in [:vibe-runtime :held] dissoc :flash)))

(defn- release-flash
  "A FLASH release: the flash let go of, and what it stepped aside started
   again."
  [state t]
  (restore-ducked (let-go-of-flash-key state) t))

(defn- duck
  "A solo flash's press: the other voices stop; :decay ones are done, the
   others start again at its release."
  [state]
  (reduce (fn [s role]
            (let [voice (get-in s [:vibe-runtime :voices role])]
              (if (and voice (live? s voice))
                (cond-> (-> (stop-voice s voice) (update-in [:vibe-runtime :voices] dissoc role))
                  (not= :decay (:mode voice)) (assoc-in [:vibe-runtime :duck role] (select-keys voice [:cell :mode])))
                s)))
          state
          [:hit :accent :bed]))

(defn- press-flash
  "A FLASH press: the flash cue held (alone with :solo?) and the flash's
   effect cells on. A second press without a release lets the first go;
   what a solo flash stepped aside waits for the last release."
  [state t src]
  (let [state (ensure-current state t)
        vibe (library/current-vibe state)
        {:keys [cue fx solo?]} (:flash vibe)
        cell (playable state vibe cue)
        existing (existing-fx state fx)
        fx (library/playable-fx state vibe existing)
        refused (refusal (remove (set fx) existing))]
    (cond
      (nil? vibe) (problem state {:why :no-vibes})
      (and (nil? cell) (empty? fx)) (problem state (or refused {:role :flash :why :empty-pool}))
      :else
      (let [state (cond-> (let-go-of-flash (forget-stale state)) solo? duck)
            [run state] (if cell (start state cell {:hold? true} t) [nil state])
            [state fx-on] (switch-on state fx)]
        (-> state
            (update :vibe-runtime
                    #(-> %
                         (assoc-in [:voices :flash] (cond-> {:fx fx :fx-on fx-on :solo? (boolean solo?)}
                                                      cell (assoc :cell cell :run run)))
                         (assoc-in [:held :flash] {:at t :src src})
                         (update :problem (fn [p] (or refused (good-press p))))))
            (cond-> (not solo?) (restore-ducked t)))))))


;; VIBE and BLACKOUT

(defn- toggle-lock
  "A VIBE hold: the rotation locked, or unlocked with the vibe's time
   counted from t."
  [state t]
  (if (= :locked (get-in state [:vibe-runtime :rotation]))
    (update state :vibe-runtime #(-> % (assoc :rotation :active) (restart-rotation t)))
    (assoc-in state [:vibe-runtime :rotation] :locked)))

(defn- release-vibe
  "A VIBE tap switches to the next vibe; a hold of :lock-hold-ms or more
   locks or unlocks the rotation."
  [state t]
  (let [{:keys [at]} (get-in state [:vibe-runtime :held :vibe])
        state (update-in state [:vibe-runtime :held] dissoc :vibe)]
    (if (< (- t at) (:lock-hold-ms (library/settings state)))
      (if-let [id (library/next-id state (library/current-id state))]
        (change-vibe (forget-stale state) id t)
        (problem state {:why :no-vibes}))
      (toggle-lock state t))))

(defn- toggle-blackout
  "On: every voice stops, holds clear (the pulses' too, though nothing
   pulses), effect cells off, and the transport stops. Off: the current
   vibe's bed and FX set come back, its time counts from t, and it plays."
  [state t]
  (if (get-in state [:vibe-runtime :blackout?])
    (let [state (assoc-in state [:vibe-runtime :blackout?] false)
          current (get-in state [:vibe-runtime :current])]
      {:state (cond
                (nil? current) state
                (library/vibe state current) (-> (enter-look state (library/vibe state current) t)
                                                 (update :vibe-runtime restart-rotation t))
                :else (ensure-current state t))
       :dispatch {:event/type :transport/play}})
    (let [{:keys [voices fx-on]} (:vibe-runtime state)]
      {:state (-> (reduce stop-voice state (vals voices))
                  (switch-off (concat fx-on (get-in voices [:flash :fx-on])))
                  (release-pulses nil)
                  (update :vibe-runtime assoc :blackout? true :held {} :voices {} :duck {} :fx-on []))
       :dispatch {:event/type :transport/stop}})))


;; The events

(defn- event-time [event]
  (or (:input-time event) (h/current-time-ms event)))

(defn press
  "A role's press (:vibe/press). During blackout only BLACKOUT does anything.
   HIT, ACCENT and FLASH pulse, whatever they play, and every HIT press is
   :last-hit-at, where the rotation's phrase gap counts from."
  [state {:keys [role] :as event}]
  (let [t (event-time event)
        src (or (:src event) :input)]
    (cond
      (= :blackout role) (toggle-blackout state t)
      (get-in state [:vibe-runtime :blackout?]) {:state state}
      :else (case role
              (:hit :accent) (update (press-pool state role t src) :state
                                     #(cond-> (pulse-press % role src)
                                        (= :hit role) (assoc-in [:vibe-runtime :last-hit-at] t)))
              :flash {:state (pulse-press (press-flash state t src) :flash src)}
              :vibe {:state (assoc-in state [:vibe-runtime :held :vibe] {:at t :src src})}
              {:state state}))))

(defn- let-go-of-hold
  "role's hold over without its key's release (release-all, a stuck key):
   HIT and ACCENT stop now unless they sustain, FLASH lets go, and a held
   VIBE neither switches nor locks: a key that stuck mustn't change the
   look."
  [state role t]
  (case role
    (:hit :accent) (release-pool state role t true)
    :flash (release-flash state t)
    (update-in state [:vibe-runtime :held] dissoc role)))

(defn- let-go-of-stuck
  "The show clock's :vibe/release {:reason :watchdog} (vibes.watchdog): role
   let go of as release-all lets go, its pulse too, and :problem says the
   key stuck, while the watchdog still finds it stuck at the event's time;
   a report that came late, or again, changes nothing."
  [state {:keys [role] :as event}]
  (let [t (event-time event)]
    (if (watchdog/stuck? state event t)
      (-> (pulse-release state role)
          (cond-> (get-in state [:vibe-runtime :held role]) (let-go-of-hold role t))
          (problem {:role role :why :stuck-key}))
      state)))

(defn release
  "A role's release (:vibe/release). One that isn't held changes nothing,
   but for its pulse: a press that played nothing holds that alone. FLASH
   let go of while the rotation is armed and no other key is down goes on to
   the next vibe (:vibe/advance :flash, inline: beyond-vibe's flash mask),
   and what a solo flash stepped aside doesn't start again: it would end with
   the look it belongs to. With :reason :watchdog it is a stuck key's
   (let-go-of-stuck)."
  [state {:keys [role reason] :as event}]
  (if (= :watchdog reason)
    {:state (let-go-of-stuck state event)}
    (let [state (pulse-release state role)]
      (if (get-in state [:vibe-runtime :held role])
        (let [t (event-time event)]
          (case role
            (:hit :accent) {:state (release-pool state role t false)}
            :flash (let [state (let-go-of-flash-key state)]
                     (if (rotation/may-advance? state)
                       {:state state
                        :dispatch {:event/type :vibe/advance :reason :flash :input-time t
                                   :from (get-in state [:vibe-runtime :current])}}
                       {:state (restore-ducked state t)}))
            :vibe {:state (release-vibe state t)}
            {:state state}))
        {:state state}))))

(defn select
  "Make the vibe with id current (:vibe/select). During blackout it becomes
   current and its look waits for blackout to end."
  [state {:keys [vibe] :as event}]
  {:state (if (and (library/vibe state vibe) (not= vibe (get-in state [:vibe-runtime :current])))
            (change-vibe (forget-stale state) vibe (event-time event))
            state)})

(defn release-all
  "Let go of every held role whose :src is only (all with nil): HIT and
   ACCENT stop now unless they sustain, FLASH lets go, and a held VIBE
   neither switches nor locks (:vibe/release-all). The pulses those
   presses held, and those of presses that played nothing, are let go too."
  [state {:keys [only] :as event}]
  (let [t (event-time event)
        roles (sort-by #(= :flash %)
                       (for [[role {:keys [src]}] (get-in state [:vibe-runtime :held])
                             :when (or (nil? only) (= only src))]
                         role))]
    {:state (reduce #(let-go-of-hold %1 %2 t) (release-pulses state only) roles)}))

(defn release-unbound
  "state with each role a key holds (:src :input: the engine's hold, or the
   pulse of a press that played nothing) let go of, as release-all lets go,
   when the trigger map lost a binding of that role since was (the trigger
   map before an edit): a learn moved the role to another key, or Clear took
   it, so that key's release would reach nothing. A pad's hold stays: its
   release still comes."
  [state was t]
  (let [now (get-in state [:config :input :trigger-map])
        lost? (fn [role]
                (some (fn [[k action]] (and (library/binds-role? action role) (not (library/binds-role? (get now k) role))))
                      was))]
    (reduce (fn [s role]
              (cond-> s
                (= :input (get-in s [:playback :pulses role :src])) (pulse-release role)
                (= :input (get-in s [:vibe-runtime :held role :src])) (let-go-of-hold role t)))
            state
            (sort-by #(= :flash %) (filter lost? library/roles)))))


;; Auto-advance (laser-show.vibes.rotation): the show clock's reports and
;; the Perform tab's switch

(defn- bpm-of [state] (double (or (ex/bpm state) 120.0)))

(defn arm
  "The show clock's :vibe/arm {:from}: the vibe that plays has had its time,
   so the rotation waits for a phrase gap or a FLASH release (:armed), if
   the rotation still says so (rotation/due) and :from still plays. Again, it
   changes nothing."
  [state {:keys [from] :as event}]
  {:state (if (= {:event/type :vibe/arm :from from} (rotation/due state (event-time event) (bpm-of state)))
            (assoc-in state [:vibe-runtime :rotation] :armed)
            state)})

(defn advance
  "A change to the next vibe that nobody pressed (:vibe/advance {:reason
   :from}): the show clock's at a phrase gap (:gap), or a FLASH release's
   (:flash), while :from still plays and the rotation still says so
   (rotation/due, rotation/may-advance?), so that of every report sent for
   one armed vibe one takes effect. It pulses :vibe, as a VIBE tap does."
  [state {:keys [reason from] :as event}]
  (let [t (event-time event)
        go? (and (some? from)
                 (= from (get-in state [:vibe-runtime :current]))
                 (case reason
                   :gap (= {:event/type :vibe/advance :reason :gap :from from} (rotation/due state t (bpm-of state)))
                   :flash (rotation/may-advance? state)
                   false))]
    {:state (if go?
              (change-vibe (forget-stale state) (library/next-id state from) t)
              state)}))

(defn set-auto
  "The Perform tab's AUTO switch (:vibe/set-auto {:auto?}), for the session:
   it overrides the saved :mode until New or Open. Turned on, the vibe's
   time counts from now; turned off, an armed change is called off."
  [state {:keys [auto?] :as event}]
  (let [on? (boolean auto?)
        was (rotation/auto? state)
        state (assoc-in state [:vibe-runtime :auto?] on?)]
    {:state (cond
              (= was on?) state
              (not on?) (update-in state [:vibe-runtime :rotation] #(if (= :armed %) :active %))
              (get-in state [:vibe-runtime :current]) (update state :vibe-runtime restart-rotation (event-time event))
              :else state)}))


;; Edits (the Vibes tab, events/handlers/vibes.clj)

(defn- refresh-fx
  "The FX set from was's cells to vibe's (those each may switch on, so
   Allow audience cells counts): those it lost given back, those it gained
   taken."
  [state was vibe]
  (let [before (library/playable-fx state was (existing-fx state (:fx was)))
        after (library/playable-fx state vibe (existing-fx state (:fx vibe)))]
    (-> state
        (release-fx (remove (set after) before))
        (take-fx vibe (remove (set before) after)))))

(defn- refresh-bed
  "The bed started again when the cue it should play changed from was's to
   vibe's (another cell, or the audience rule): the old one stopped, also
   where a solo flash keeps it waiting, and the new one started."
  [state was vibe t]
  (if (= (playable state was (:bed was)) (playable state vibe (:bed vibe)))
    state
    (-> (stop-voice state (get-in state [:vibe-runtime :voices :bed]))
        (update-in [:vibe-runtime :voices] dissoc :bed)
        (update-in [:vibe-runtime :duck] dissoc :bed)
        (start-bed vibe t))))

(defn- refresh-audience
  "The voices whose cue vibe may no longer play now that was's audience
   rule changed (Allow audience cells unticked, V6): HIT's and ACCENT's
   stop, and the flash's cue stops while its effect cells stay on until
   FLASH is let go. The bed is refresh-bed's."
  [state was vibe]
  (reduce (fn [s role]
            (let [{:keys [cell] :as voice} (get-in s [:vibe-runtime :voices role])]
              (if (and cell (playable s was cell) (not (playable s vibe cell)))
                (let [s (stop-voice s voice)]
                  (if (= :flash role)
                    (update-in s [:vibe-runtime :voices :flash] dissoc :cell :run)
                    (update-in s [:vibe-runtime :voices] dissoc role)))
                s)))
          state
          [:hit :accent :flash]))

(defn reconcile
  "state after an edit of the vibes ([:config :vibes] was before it), with
   the look of the vibe that plays following the edit at time t, so the
   lasers show what the editor says: an effect cell its FX set gains
   switches on and one it loses switches off (if the engine switched it
   on), its bed starts again when the cue it should play changed, and a cue
   it may no longer play (Allow audience cells unticked) stops at once,
   whichever role plays it. When the vibe that plays is gone (the edit
   removed it, or an undo did before, which runs no handler), its look is
   left and the first of :order is entered, as a press would find it (01
   invariant 9). Nothing before the first press or select, nor during
   blackout, whose end enters the look as it is then. The pools, the flash
   and the options take effect at the next press."
  [state was t]
  (let [{:keys [current blackout?]} (:vibe-runtime state)
        old (get-in was [:library current])
        vibe (library/vibe state current)]
    (cond
      (nil? current) state
      (not (and old vibe)) (-> (leave-look state)
                               (assoc-in [:vibe-runtime :current] nil)
                               (ensure-current t))
      blackout? state
      :else (-> state
                (refresh-fx old vibe)
                (refresh-bed old vibe t)
                (refresh-audience old vibe)))))
