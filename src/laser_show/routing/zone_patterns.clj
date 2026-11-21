(ns laser-show.routing.zone-patterns
  "Zone patterns, the model (zones plan Z5, plans/2026-11-19-zones/07 §4.2,
   §8.2, §8.6, §8.8). Pure. The stage that runs them on a cue's frames is
   routing.zone-remap; this is what the effects, the editors, the pattern
   pads, the show clock and the vibes know of them.

   A zone pattern is one effect, :zone-pattern, for every style (so a pad
   or a modulator can switch the style): Alternate, Chase, Ping-pong, Random,
   Shift, Swap. It lives on a cue (its :zone-patterns) or in an effects-grid
   cell. A Map cycle (:zone-map-cycle) lives in a cell only: it picks the
   zone map, a bar each, or on a press (cycle-pick, active-cycle).

   The pattern pads switch the style of every pattern that follows them
   (Z5-Q1 A): [:pattern-pads :style] (nil As set, :off, a style), live, not
   saved (Z5-Q3 A), and a switch waits for the beat or the bar as a map
   pad's does (schedule-style, routing.zone-maps/schedule-slot)."
  (:require [clojure.string :as str]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.modulators :as modulators]
            [laser-show.animation.pulses :as pulses]
            [laser-show.routing.rig :as rig]
            [laser-show.routing.targets :as targets]
            [laser-show.routing.zone-maps :as zone-maps]
            [laser-show.routing.zone-remap :as zone-remap]))


;; Styles and their parameters (07 §8.2)

(def styles
  "The styles, in the menus' order (zones plan Z6 adds :steps)."
  [:alternate :chase :ping-pong :random :shift :swap])

(def style-names
  {:alternate "Alternate" :chase "Chase" :ping-pong "Ping-pong" :random "Random" :shift "Shift" :swap "Swap"})

(def step-sources
  "What a pattern steps on: the beat (:clock), or a press of one of vibe
   Phase 4a's pulse sources (and the Zone step key, :zone-step)."
  (into [:clock] pulses/sources))

(def ^{:arglists '([params ctx])
       :doc "routing.zone-remap/pattern-t: what a pattern's step counts at ctx {:beats {:cue
   :global} :pulses}: its clock's beats (since a :restart-on press), or a pulse
   source's press count, 0 before its first (07 §8.3)."}
  pattern-t
  zone-remap/pattern-t)

(def ^{:arglists '([params ctx])
       :doc "routing.zone-remap/pattern-step: the step ⌊offset + rate·t + 1e-9⌋ a pattern is at."}
  pattern-step
  zone-remap/pattern-step)

(def ^{:arglists '([params live])
       :doc "routing.zone-remap/effective-style: the pads' live style for a pattern that
   follows them (:off: it doesn't move), else its own."}
  effective-style
  zone-remap/effective-style)

(def ^:private source-words
  "The pulse sources as the pattern editor says them (Phase 4a's words)."
  (dissoc modulators/source-labels :own))

(def pattern-parameters
  "The :zone-pattern effect's parameters (07 §8.2). :style, :source and
   :path may follow a modulator (:modulatable?: its value picks a choice by
   index, animation.effects/choice-at)."
  [{:key :style :label "Style" :type :choice :choices styles :labels style-names :default :alternate :modulatable? true}
   {:key :zones :label "Zones" :type :ref-list :default [:zone/a :zone/b]}
   {:key :source :label "Moves" :type :choice :choices [:auto :picture :zones]
    :labels {:auto "Automatic" :picture "The All picture" :zones "What's already in the zones"}
    :default :auto :modulatable? true}
   {:key :from :label "From" :type :ref :default :all}
   {:key :rate :label "Rate" :type :float :min -8.0 :max 8.0 :default 1.0}
   {:key :offset :label "Offset" :type :float :min -64.0 :max 64.0 :default 0.0}
   {:key :step-on :label "Step on" :type :choice :choices step-sources
    :labels (assoc source-words :clock "The beat") :default :clock}
   {:key :clock :label "Count from" :type :choice :choices [:cue :global]
    :labels {:cue "the cue's start" :global "the show's beats"} :default :global}
   {:key :restart-on :label "Restart on" :type :choice :choices (into [:own] pulses/sources)
    :labels (assoc source-words :own "No") :default :own}
   {:key :path :label "Path" :type :choice :choices [:loop :bounce] :labels {:loop "Loop" :bounce "There and back"}
    :default :loop :modulatable? true}
   {:key :width :label "Width" :type :int :min 1 :max 8 :default 1}
   {:key :seed :label "Seed" :type :int :min 0 :max 99 :default 0}
   {:key :follow-pads? :label "Follows the pattern pads" :type :bool :default true}])

(def cycle-parameters
  "The :zone-map-cycle effect's parameters (07 §8.2): it always counts the
   show's beats."
  [{:key :maps :label "Maps" :type :map-list :default [:map/left-right :map/odd-even]}
   {:key :rate :label "Every" :type :float :min -8.0 :max 8.0 :default 0.25}
   {:key :offset :label "Offset" :type :float :min -64.0 :max 64.0 :default 0.0}
   {:key :path :label "Path" :type :choice :choices [:loop :bounce] :labels {:loop "Loop" :bounce "There and back"}
    :default :loop}
   {:key :step-on :label "Step on" :type :choice :choices step-sources
    :labels (assoc source-words :clock "The beat") :default :clock}])

(defn- defaults [parameters]
  (into {} (map (juxt :key :default)) parameters))

(def presets
  "What each style's preset sets over the defaults: its lane (07 §8.2)."
  {:alternate {:zones [:zone/a :zone/b]}
   :chase {:zones [:lane/lasers]}
   :ping-pong {:zones [:lane/lasers]}
   :random {:zones [:lane/lasers]}
   :shift {:zones [:zone/a :zone/b :zone/c :zone/d]}
   :swap {:zones [:zone/a :zone/b]}})

(defn preset-instance
  "A new zone pattern of style, as an effect instance (no :id yet): its
   preset's lane, the cue's start as its clock on a cue (where :cue, Z5-Q6
   A), the show's beats in a cell."
  [style where]
  {:effect-id :zone-pattern
   :enabled? true
   :params (merge (defaults pattern-parameters)
                  (get presets style)
                  {:style style :clock (if (= :cue where) :cue :global)})})

(defn cycle-instance
  "A new Map cycle, as an effect instance (no :id yet): Left / Right, then
   Odd / Even, a bar each."
  []
  {:effect-id :zone-map-cycle :enabled? true :params (defaults cycle-parameters)})


;; What a pattern says of itself

(defn- number-text [x]
  (let [x (double x)]
    (if (== x (Math/rint x))
      (str (long x))
      (str/replace (format "%.2f" x) #"0$" ""))))

(defn- lane-place-name
  "A lane place as a pattern says it: a zone by its letters, Every laser,
   anything else by name."
  [names ref]
  (cond
    (= :lane/lasers ref) "every laser"
    (targets/zone-ref? ref) (targets/zone-letter ref)
    :else (targets/ref-name names ref)))

(defn lane-text
  "A pattern's lane as its style says it: Alternate \"A ⇄ B\" (the even
   places ⇄ the odd ones: \"A C ⇄ B D\"), Swap its first two, the others
   in order, \"A → B → C\"; \"every laser\"; \"no zones\" for none."
  [style refs names]
  (let [refs (vec (distinct (filter keyword? refs)))
        say (fn [rs] (str/join " " (map #(lane-place-name names %) rs)))]
    (cond
      (empty? refs) "no zones"
      (and (= :alternate style) (next refs)) (str (say (take-nth 2 refs)) " ⇄ " (say (take-nth 2 (rest refs))))
      (and (= :swap style) (next refs)) (str (say [(first refs)]) " ⇄ " (say [(second refs)]))
      :else (str/join " → " (map #(lane-place-name names %) refs)))))

(def ^:private press-words
  {:hit "HIT" :accent "ACCENT" :flash "FLASH" :vibe "vibe change" :any "HIT, ACCENT or FLASH" :cue "cue start"
   :zone-step "Zone step"})

(defn- rate-text
  "How fast a pattern steps: \"1 a beat\", \"1 every 2 beats\", \"on each
   HIT\", \"2 on each Zone step\", \"still\"; a backwards one says so."
  [{:keys [rate step-on]}]
  (let [r (if (number? rate) (double rate) 1.0)
        a (Math/abs r)
        step-on (or step-on :clock)]
    (cond
      (zero? a) "still"
      (not= :clock step-on) (str (if (== 1.0 a) "on each " (str (number-text a) " on each "))
                                 (get press-words step-on (name step-on))
                                 (when (neg? r) ", backwards"))
      :else (str (if (and (< a 1.0) (== (/ 1.0 a) (Math/rint (/ 1.0 a))))
                   (str "1 every " (number-text (/ 1.0 a)) " beats")
                   (str (number-text a) " a beat"))
                 (when (neg? r) ", backwards")))))

(defn describe
  "A pattern in one line (07 §4.2): \"Alternate · A ⇄ B · 1 a beat\"."
  [params names]
  (str (get style-names (:style params) "Zone pattern")
       " · " (lane-text (:style params) (:zones params) names)
       " · " (rate-text params)))

(defn- cycle-rate-text
  "How long a Map cycle stays on each map: \"a bar each\", \"2 bars each\",
   \"a beat each\", \"2 beats each\", or \"one on each HIT\"."
  [{:keys [rate step-on]}]
  (let [r (Math/abs (if (number? rate) (double rate) 0.25))
        beats (when (pos? r) (/ 1.0 r))]
    (cond
      (and step-on (not= :clock step-on)) (str "one on each " (get press-words step-on (name step-on)))
      (nil? beats) "still"
      (and (== beats (Math/rint beats)) (zero? (mod (long beats) 4)))
      (let [bars (quot (long beats) 4)] (if (= 1 bars) "a bar each" (str bars " bars each")))
      (== 1.0 beats) "a beat each"
      :else (str (number-text beats) " beats each"))))

(defn describe-cycle
  "A Map cycle in one line (07 §7.4): \"Map cycle: Left / Right, Odd /
   Even, a bar each\"; its maps by name in state's show, those that are gone
   left out."
  [params state]
  (let [ms (keep #(zone-maps/map-by-id state %) (:maps params))]
    (if (seq ms)
      (str "Map cycle: " (str/join ", " (map :name ms)) ", " (cycle-rate-text params))
      "Map cycle: no maps")))


;; The Map cycle (07 §8.6)

(defn- reading-order
  "The effects grid's cells, row by row, left to right (vibe Phase 8 §7)."
  [effect-chains]
  (sort-by (fn [[[col row]]] [row col]) effect-chains))

(defn active-cycles
  "Every Map cycle that runs: {:cell :effect :params} of each enabled
   :zone-map-cycle in a cell that is on, in reading order (row, column, then
   chain order)."
  [state]
  (vec (for [[cell chain] (reading-order (get-in state [:chains :effect-chains]))
             :when (:active? chain)
             fx (chains/flatten-chain (:items chain))
             :when (= :zone-map-cycle (:effect-id fx))]
         {:cell cell :effect fx :params (:params fx)})))

(defn active-cycle
  "The Map cycle that picks the map (07 §8.6): the last of active-cycles, in
   reading order; nil when none runs."
  [state]
  (peek (active-cycles state)))

(defn cycle-pick
  "The map a Map cycle (params, resolved) shows at the show's beats, or on
   its step source's press count in pulses (the tick's pulse view): step
   ⌊offset + rate·t⌋ along :path over its :maps, those not among maps (the
   show's map ids) left out. nil when none is left."
  [params pulses beats maps]
  (let [known (set maps)
        ids (into [] (comp (filter known) (distinct)) (:maps params))
        n (count ids)]
    (when (pos? n)
      (let [rate (if (number? (:rate params)) (:rate params) 0.25)
            offset (if (number? (:offset params)) (:offset params) 0.0)
            t (zone-remap/pattern-t (assoc params :clock :global :restart-on nil) {:beats {:global beats} :pulses pulses})]
        (nth ids (zone-remap/ring-index (zone-remap/step-index offset rate t) n (:path params)))))))


;; How far a pattern reaches (07 §8.8)

(defn- known-ref?
  "Does state's rig or show know ref: a zone, or a group or laser
   (routing.rig/known-ref?)."
  [state ref]
  (or (targets/zone-ref? ref) (rig/known-ref? state ref)))

(defn reach
  "Every ref a pattern (params) can send content to: its whole lane, over
   the rig as it is (zone-remap/lane), Every laser being each laser in All,
   on or off (one that is off can be turned on mid-show). A pad can switch
   any pattern to a style that moves, so the style doesn't narrow it, and
   :from is where a picture comes from, so it doesn't count."
  [state params]
  (let [inputs (rig/rig-inputs state)
        lasers (filterv #(not (contains? (:excluded inputs) %)) (:order inputs))]
    (zone-remap/lane (:zones params) #(known-ref? state %) lasers)))


;; Warnings (07 §8.8)

(defn- e-lasers
  "E's ids, in rig order: the lasers a pattern's Every laser runs over."
  [state]
  (rig/e-order (rig/rig-inputs state)))

(defn- routing-now
  "The routing under the map in use."
  [state]
  (rig/routing (rig/routing-inputs state (zone-maps/effective-map-id state 0.0 nil))))

(defn- zone-step-learned? [state]
  (boolean (some #(= :zone-step (:type %)) (vals (get-in state [:config :input :trigger-map])))))

(defn warnings
  "What a pattern (params) should know, worst first, each {:level :text},
   the text about subject (\"This pattern\"; the lint names the cell): it
   needs two places and has fewer (error); its lane names a group or laser
   that's gone; a place reaches no laser under the map in use (the picture
   vanishes on that step; not while no laser is on at all, which the rig
   says); under Unison a lane of zones doesn't move; it
   steps on the Zone step key and no key is learned for it. names:
   routing.targets/names."
  ([state params names] (warnings state params names "This pattern"))
  ([state params names subject]
   (let [zones (filter keyword? (:zones params))
         e (e-lasers state)
         l (zone-remap/lane zones #(known-ref? state %) e)
         missing (remove #(or (= :lane/lasers %) (known-ref? state %)) (distinct zones))
         routing (routing-now state)
         dark (when (seq e) (filter #(empty? (rig/resolve-ref routing %)) l))
         say (fn [ref] (targets/ref-name names ref))]
     (vec (concat
           (when (< (count l) 2)
             [{:level :error
               :text (str subject " needs two zones (it has " (if (seq l) (str "one: " (say (first l))) "none") ")")}])
           (for [ref missing]
             {:level :warn
              :text (str subject " lists " (str/replace (say ref) #" \(missing\)$" "") ", which no longer exists")})
           (for [ref dark]
             {:level :warn :text (str subject ": " (say ref) " has no laser, so the picture vanishes on that step")})
           (when (and (= :map/unison (:map routing)) (seq l) (every? targets/zone-ref? l))
             [{:level :info :text (str subject " doesn't move under Unison")}])
           (when (and (some #{:zone-step} [(:step-on params) (:restart-on params)]) (not (zone-step-learned? state)))
             [{:level :info :text (str subject " steps on the Zone step key, which has no key yet")}]))))))


;; How a pattern folds onto the rig (07 §7.1, §8.5)

(defn- laser-count [n]
  (str n (if (= 1 n) " laser" " lasers")))

(defn- and-list [xs]
  (case (count xs)
    0 "nothing"
    1 (first xs)
    (str (str/join ", " (butlast xs)) " and " (last xs))))

(defn- entry-text
  "One entry of a place [projector region]: a laser, or a half of one; with
   one laser, \"its left half\"."
  [state one? [id region]]
  (let [n (rig/projector-name state id)]
    (case region
      :L (if one? "its left half" (str "the left half of " n))
      :R (if one? "its right half" (str "the right half of " n))
      (if one? "all of it" n))))

(defn fold-text
  "How a pattern (params) folds onto state's rig under the map in use (the
   pattern window's ⓘ line): \"This rig (3 lasers, Left / Right): A is
   Laser 1 and the left half of Laser 2; B the right half of Laser 2 and
   Laser 3.\"; Every laser by its lasers, left to right; under Unison a
   lane of zones doesn't move; no laser on. names: routing.targets/names."
  [state params names]
  (let [e (e-lasers state)
        n (count e)
        one? (= 1 n)
        routing (routing-now state)
        refs (vec (distinct (filter keyword? (:zones params))))
        l (zone-remap/lane refs #(known-ref? state %) e)
        place (fn [ref] (if (targets/zone-ref? ref) (targets/zone-letter ref) (targets/ref-name names ref)))
        reaches (fn [ref]
                  (and-list (into [] (comp (map #(subvec (vec %) 0 2)) (distinct) (map #(entry-text state one? %)))
                                  (rig/resolve-ref routing ref))))]
    (cond
      (zero? n) "No laser is on, so this pattern has nowhere to send anything."
      (and (= :map/unison (:map routing)) (seq l) (every? targets/zone-ref? l))
      "Under Unison every zone is All, so this pattern doesn't move."
      (= [:lane/lasers] refs)
      (str "This rig (" (laser-count n) "): "
           (if one?
             "its left half, then its right half"
             (str (and-list (mapv #(rig/projector-name state %) e)) ", left to right"))
           ".")
      :else
      (str "This rig (" (laser-count n)
           (when (some targets/zone-ref? l) (str ", " (:name (zone-maps/map-by-id state (:map routing)))))
           "): "
           (str/join (if one? ", " "; ")
                     (map-indexed (fn [i ref] (str (place ref) (when (zero? i) " is") " " (reaches ref))) l))
           "."))))


;; The pattern pads (07 §7.3)

(def pad-ids
  "The pattern pads, in order: As set, Off, then a pad per style."
  (into [:as-set :off] styles))

(defn pad-style
  "The live style a pad sets: nil for As set, else its id."
  [pad-id]
  (when-not (= :as-set pad-id) pad-id))

(defn style-pad
  "The pad of a live style: As set for nil."
  [style]
  (or style :as-set))

(defn pad-name
  "A pad's name: \"As set\", \"Off\", a style's (\"Ping-pong\")."
  [pad-id]
  (case pad-id
    :as-set "As set"
    :off "Off"
    (get style-names pad-id (some-> pad-id name))))

(def zone-stage-ids
  "The zone-stage effects' ids: a zone pattern and a Map cycle (what the
   show lock counts as a pattern edit, events.show-lock)."
  #{:zone-pattern :zone-map-cycle})

(defn live-style
  "The pattern pads' style at the global beats: the waiting switch's once
   due, else [:pattern-pads :style]; nil As set, :off, or a style."
  [state beats]
  (let [{:keys [style pending]} (:pattern-pads state)]
    (if (and pending (zone-maps/due? (:at-beat pending) beats))
      (:style pending)
      style)))

(defn commit-style
  "state with the pads' style style, and no switch waiting."
  [state style]
  (update state :pattern-pads assoc :style style :pending nil))

(defn schedule-style
  "A pattern pad's press of style (nil As set; Shift or the pad's setting:
   bar?) at clock: the map pads' rule (zone-maps/schedule-slot) on
   [:pattern-pads], with the tempo and the transport of state."
  [state style bar? clock]
  (let [{:keys [active pending]} (zone-maps/schedule-slot {:active (get-in state [:pattern-pads :style])
                                                           :pending (get-in state [:pattern-pads :pending])
                                                           :key :style}
                                                          style bar?
                                                          (assoc clock
                                                                 :bpm (get-in state [:timing :bpm])
                                                                 :playing? (boolean (get-in state [:playback :playing?]))))]
    (update state :pattern-pads assoc :style active :pending pending)))
