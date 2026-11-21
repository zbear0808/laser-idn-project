(ns laser-show.routing.zone-maps
  "Zone maps (zones plan Z4, plans/2026-11-19-zones/06 §4.1, spec 01 §4,
   §9): which group each zone means right now. Pure; it requires
   routing.targets only (routing.rig requires it, never the reverse).

   A map is {:id :name :lanes [ref …] :overrides {zone ref}}: zone i goes to
   its override, else to lane i mod n, so zones wrap over the lanes
   (zone-lane). The built-ins (Left / Right, the default, Odd / Even,
   Unison) are derived, never saved; yours are the show's, [:zone-maps id].

   The map in use is [:zone-map :active], live and saved with the show
   (D-14). A press schedules a switch (schedule): at once within 50 ms of a
   beat, else on the next beat, or bar; it waits in [:zone-map :pending]
   until the show clock's beats reach it, and the clock treats it as the
   effective map from then on (effective-map-id) while the commit comes back
   as an event. Every switch lands through commit."
  (:require [clojure.string :as str]
            [laser-show.routing.targets :as targets]))


;; The maps

(def default-map-id
  "The map a show starts on, and any id that is no map falls back to."
  :map/left-right)

(def built-ins
  "The built-in maps, in their order (spec §4): derived, never saved, read
   only."
  [{:id :map/left-right :name "Left / Right" :lanes [:auto/left :auto/right] :overrides {} :built-in? true}
   {:id :map/odd-even :name "Odd / Even" :lanes [:auto/odd :auto/even] :overrides {} :built-in? true}
   {:id :map/unison :name "Unison" :lanes [:all] :overrides {} :built-in? true}])

(def ^:private built-in-by-id (into {} (map (juxt :id identity)) built-ins))

(defn built-in?
  "Is id a built-in map's?"
  [id]
  (contains? built-in-by-id id))

(defn all-maps
  "Every map of state's show: the built-ins, then yours ([:zone-maps]) by
   name, then id. A stored map with a built-in's id is left out."
  [state]
  (into built-ins
        (->> (:zone-maps state)
             (remove (comp built-in? key))
             (map (fn [[id m]] (assoc m :id id)))
             (sort-by (fn [m] [(str/lower-case (str (:name m))) (str (:id m))])))))

(defn map-by-id
  "The map id names in state's show (a built-in, or one of yours), or nil."
  [state id]
  (or (built-in-by-id id)
      (when-let [m (get-in state [:zone-maps id])]
        (assoc m :id id))))

(def ^{:arglists '([m zone])
       :doc "routing.targets/zone-lane: zone's lane in map m, its override first;
   zones wrap over the lanes; nil (nothing) with no lanes or an empty one."}
  zone-lane
  targets/zone-lane)

(def ^{:arglists '([m t])
       :doc "routing.targets/pin: t with each zone swapped for its lane in m."}
  pin
  targets/pin)


;; The effective map (spec §4)

(defn due?
  "Has a switch pending at at-beat come, at the global beats?"
  [at-beat beats]
  (>= (+ (double beats) 1e-9) (double at-beat)))

(defn effective-map-id
  "The map in use at a tick, at the global beats (spec §4's order): the
   pending map once due?; else cycle, the Map cycle's pick (zones plan Z5;
   nil until then), unless [:zone-map :cycle-paused?]; else [:zone-map
   :active]. An id that is no map is Left / Right."
  [state beats cycle]
  (let [{:keys [active pending cycle-paused?]} (:zone-map state)
        id (cond
             (and pending (due? (:at-beat pending) beats)) (:map pending)
             (and cycle (not cycle-paused?)) cycle
             :else active)]
    (if (map-by-id state id) id default-map-id)))


;; When a switch lands (spec §9, 06 §8.4)

(def window-s
  "A press this close after a beat counts from that beat: at once."
  0.05)

(def beats-per-bar
  "Bars of 4 beats, counted from global beat 0, as the beat squares are."
  4)

(def ^:private max-input-age-ms
  "How far back a learned key's press is dated, at most."
  250.0)

(defn switch-at
  "The global beat a press at beats lands on, or nil for at once: within
   window-s after a beat (or a bar, bar?) it is that one, now; otherwise the
   next one. With no clock (clock? false: the clock thread off, or the
   transport stopped) or no tempo (bpm not positive), at once."
  [{:keys [beats bpm bar? clock?]}]
  (when (and clock? (number? bpm) (pos? (double bpm)) (number? beats))
    (let [unit (double (if bar? beats-per-bar 1))
          t (/ (double beats) unit)
          window (/ (* window-s (double bpm)) 60.0 unit)
          fraction (- t (Math/floor t))]
      (when (> fraction window)
        (* unit (Math/ceil t))))))

(defn press-beats
  "The beats a press counts from: clock's :beats, backed off to its
   :input-time (a learned key's, on clock's :now-ms wall clock) by bpm, at
   most 250 ms and never ahead. A pad press has none: now."
  [{:keys [beats now-ms input-time]} bpm]
  (let [beats (double (or beats 0.0))]
    (if (and (number? input-time) (number? now-ms) (number? bpm))
      (let [age (max 0.0 (min max-input-age-ms (- (double now-ms) (double input-time))))]
        (- beats (/ (* (double bpm) age) 60000.0)))
      beats)))


;; Switching

(defn commit
  "state with map id in use, and no switch pending. Every switch lands here:
   a press at once, a due one, Stop and Retrigger, Use now (zones plan Z5
   pauses a running Map cycle here too)."
  [state id]
  (-> state
      (assoc-in [:zone-map :active] id)
      (assoc-in [:zone-map :pending] nil)))

(defn- clock-on?
  "Can a press wait for a beat: the clock thread runs and the transport
   plays (06 SQ-2)?"
  [clock]
  (boolean (and (:running? clock) (:playing? clock))))

(defn schedule-slot
  "The rule for when a live switch lands (spec §9, 06 §8.4), the map pads'
   and the pattern pads' alike (zones plan Z5): a press of value (Shift or
   the pad's setting: bar?) on slot {:active (the value in use) :pending (a
   switch waiting, {:id k value :at-beat :bar?}, or nil) :key k (what the
   waiting switch names its value by: :map, :style)}, at clock {:beats (the
   global beats) :running? (the clock thread's) :playing? (the transport's)
   :bpm :now-ms :input-time :id}. A waiting switch already due lands first;
   then pressing the value in use cancels a waiting one; otherwise the press
   lands at once (switch-at nil: no clock, no tempo; or a beat already
   reached) or waits, replacing any other, named by :id (a new uuid
   without). nil is a value like any other. Returns {:active :pending
   :landed [v …]}: the slot after the press, and what landed, in order."
  [{:keys [active pending key]} value bar? clock]
  (let [now (double (or (:beats clock) 0.0))
        due-first? (boolean (and pending (due? (:at-beat pending) now)))
        landed (if due-first? [(get pending key)] [])
        active (if due-first? (get pending key) active)]
    (if (= value active)
      {:active active :pending nil :landed landed}
      (let [bpm (:bpm clock)
            at (switch-at {:beats (press-beats clock bpm) :bpm bpm :bar? bar? :clock? (clock-on? clock)})]
        (if (or (nil? at) (due? at now))
          {:active value :pending nil :landed (conj landed value)}
          {:active active
           :pending {:id (or (:id clock) (random-uuid)) key value :at-beat at :bar? (boolean bar?)}
           :landed landed})))))

(defn schedule
  "A press of map-id (Shift or the pad's setting: bar?), from clock ({:beats
   the global beats :running? the clock thread's :now-ms :input-time :id}):
   schedule-slot on [:zone-map], with the tempo [:timing :bpm] and the
   transport [:playback :playing?]. What lands goes through commit; a
   switch that waits is [:zone-map :pending] {:id :map :at-beat :bar?}."
  [state map-id bar? clock]
  (let [{:keys [pending landed]} (schedule-slot {:active (or (get-in state [:zone-map :active]) default-map-id)
                                                 :pending (get-in state [:zone-map :pending])
                                                 :key :map}
                                                map-id bar?
                                                (assoc clock
                                                       :bpm (get-in state [:timing :bpm])
                                                       :playing? (boolean (get-in state [:playback :playing?]))))]
    (assoc-in (reduce commit state landed) [:zone-map :pending] pending)))


;; The map editor's warnings (06 §7.5)

(defn- lane-zones
  "The zones that land on lane i of m (no override of their own): those the
   show uses (zones), or when none, the first two that would."
  [m zones i]
  (let [n (count (:lanes m))
        on-lane? #(and (= i (mod (targets/zone-index %) n)) (not (contains? (:overrides m) %)))
        used (sort-by targets/zone-index (filter on-lane? zones))]
    (if (seq used)
      used
      (take 2 (filter on-lane? (map targets/zone-ref (iterate #(+ n %) i)))))))

(defn- zones-text [zs]
  (if (next zs)
    (str "zones " (str/join ", " (map targets/zone-letter zs)) " go nowhere")
    (str "zone " (targets/zone-letter (first zs)) " goes nowhere")))

(defn- known? [names ref]
  (or (= :all ref) (contains? (:groups names) ref)))

(defn map-warnings
  "What the map editor says of map m (06 §7.5), in lane order, then the
   overrides: \"No lanes: every zone goes nowhere.\", \"Lane 2 is empty:
   zones B, D go nowhere.\", \"Lane 2 (Graphics) has no projector: …\",
   \"Lane 2 (Crowd Scanning) reaches the audience.\", \"Override C → Front:
   Front no longer exists.\" ctx: {:names (targets/names) :zones (the zones
   the show uses) :reach {ref n} (how many lasers each ref reaches)
   :audience (the refs that reach the crowd)}."
  [m {:keys [names zones reach audience]}]
  (let [lanes (:lanes m)]
    (if (empty? lanes)
      ["No lanes: every zone goes nowhere."]
      (into (vec (keep-indexed
                  (fn [i lane]
                    (let [where (lane-zones m zones i)
                          lane-n (inc i)]
                      (cond
                        (nil? lane) (str "Lane " lane-n " is empty: " (zones-text where) ".")
                        (not (known? names lane)) (str "Lane " lane-n ": " (str/replace (targets/ref-name names lane) #" \(missing\)$" "")
                                                       " no longer exists, so " (zones-text where) ".")
                        (zero? (long (get reach lane 0))) (str "Lane " lane-n " (" (targets/ref-name names lane) ") has no projector: "
                                                               (zones-text where) ".")
                        (contains? audience lane) (str "Lane " lane-n " (" (targets/ref-name names lane) ") reaches the audience."))))
                  lanes))
            (keep (fn [[zone ref]]
                    (when-not (known? names ref)
                      (let [n (str/replace (targets/ref-name names ref) #" \(missing\)$" "")]
                        (str "Override " (targets/zone-letter zone) " → " n ": " n " no longer exists."))))
                  (sort-by (comp targets/zone-index key) (:overrides m)))))))


;; Who uses a group (06 §8.7)

(defn usage
  "Who draws to ref (06 §8.7, Q-Z5 A), from ref-sets (each cue's refs,
   targets/cue-refs: its column's and the show's defaults included) and m,
   the map in use: {:direct (the cues with ref among their refs) :through
   (the others, with a zone m sends to ref) :zones (those zones, in order)}.
   A cue that does both counts once, as direct, so the two add up to every
   cue that reaches ref."
  [m ref-sets ref]
  (let [sends? #(and (targets/zone-ref? %) (= ref (zone-lane m %)))]
    (update (reduce (fn [acc refs]
                      (if (contains? refs ref)
                        (update acc :direct inc)
                        (if-let [zs (seq (filter sends? refs))]
                          (-> acc (update :through inc) (update :zones into zs))
                          acc)))
                    {:direct 0 :through 0 :zones #{}}
                    ref-sets)
            :zones #(vec (sort-by targets/zone-index %)))))

(defn next-custom-id
  "A new custom map's id: :map/custom-1, or the first unused one."
  [state]
  (first (remove #(contains? (:zone-maps state) %)
                 (map #(keyword "map" (str "custom-" %)) (iterate inc 1)))))
