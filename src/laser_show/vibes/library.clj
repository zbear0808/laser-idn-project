(ns laser-show.vibes.library
  "Vibes: cues grouped by feel, at [:config :vibes] (saved, undoable).

   {:order [vibe-id ...] :next-id n
    :library {vibe-id vibe}
    :settings {:quick-tap-ms :lock-hold-ms :hit-taps-tempo? :rotation :max-hold-secs}}

   A vibe names cue cells per role and effect cells for its FX set:
   {:id :name :color :energy :allow-audience?
    :hit {:cells [[col row] ...] :select :shuffle|:sequence
          :mode :decay|:gate|:sustain :beats 1.0}
    :accent {...the same}
    :flash {:cue [col row] :fx [[col row] ...] :solo? bool}
    :bed [col row] or nil
    :fx [[col row] ...]}

   Pure: reads, the Vibes tab's edits (each takes the vibes, [:config
   :vibes], and returns them) and the lint. laser-show.vibes.engine plays
   them."
  (:require [clojure.string :as str]
            [laser-show.routing.rig :as rig]
            [laser-show.routing.targets :as targets]
            [laser-show.routing.zone-maps :as zone-maps]
            [laser-show.state.domains :as domains]
            [laser-show.timeline.playback :as playback]
            [laser-show.vibes.reactions :as reactions]))

(def empty-library
  "A project's vibes before there are any (state.domains config :vibes)."
  (:vibes domains/config-initial))

(def roles
  "The five controls, in the order the pads show them."
  [:hit :accent :flash :vibe :blackout])

(defn settings
  "The vibe settings, a key missing from the project as its default."
  [state]
  (merge (:settings empty-library) (get-in state [:config :vibes :settings])))

(defn vibe
  "The vibe with id, or nil."
  [state id]
  (get-in state [:config :vibes :library id]))

(defn current-id
  "The vibe playing: [:vibe-runtime :current] while it names one, else the
   first of :order (nothing current yet, or it was undone or deleted). nil
   without vibes."
  [state]
  (let [current (get-in state [:vibe-runtime :current])]
    (if (vibe state current)
      current
      (first (get-in state [:config :vibes :order])))))

(defn current-vibe [state]
  (vibe state (current-id state)))

(defn next-id
  "The vibe after id in :order, wrapping (the first when id isn't in it); nil
   without vibes."
  [state id]
  (let [order (get-in state [:config :vibes :order])
        after (rest (drop-while #(not= id %) order))]
    (or (first after) (first order))))

(defn binds-role?
  "Is the trigger-map action a binding of role ({:type :vibe :role role})?"
  [action role]
  (and (= :vibe (:type action)) (= role (:role action))))


;; Who a cue reaches (V6)

(defn- crowd?
  "Does an output scan the audience (tagged :crowd-scanning)?"
  [output]
  (boolean (some #{:crowd-scanning} (:tags output))))

(defn- crowd-virtual-projectors
  "The virtual projectors that scan the audience: tagged, or whose projector
   is."
  [state]
  (let [projectors (:projectors state)]
    (filter #(or (crowd? %) (crowd? (get projectors (:parent-projector-id %))))
            (vals (:virtual-projectors state)))))

(defn audience-groups
  "Refs that reach the audience: :crowd; every ref that reaches a projector
   tagged :crowd-scanning (routing.rig/refs-reaching: All unless it is
   excluded, its groups, the automatic groups it is in, which follow the
   rig's order, and its own); and All and the groups of a virtual projector
   that is tagged or whose projector is. The refs that make a laser draw a
   cue are the ones that make it an audience cue (04 invariant 10)."
  [state]
  (-> #{:crowd}
      (into (rig/refs-reaching state crowd?))
      (into (mapcat rig/stored-refs) (crowd-virtual-projectors state))))

(defn audience-ref?
  "Does ref reach the audience (zones plan Z4, 06 §8.6): it is one of groups
   (audience-groups), or it is a zone whose lane, in some map of state's
   show (the built-ins and yours, overrides included), is one. Every map,
   not the one in use: a vibe checks a cue once, when it picks it, and the
   map can switch while the cue plays."
  [state groups ref]
  (if (targets/zone-ref? ref)
    (boolean (some #(contains? groups (targets/zone-lane % ref)) (zone-maps/all-maps state)))
    (contains? groups ref)))

(defn- cue-refs
  "Every ref the content of cue, at cell, draws to (routing.targets/cue-refs):
   its column's default and the show's included."
  [state [col] cue]
  (targets/cue-refs cue (get-in state [:grid :column-targets col]) (get-in state [:config :default-target])))

(defn audience-cue?
  "Can cue, at cell, reach the audience: does a ref its content draws to
   pass audience-ref? (groups: audience-groups)."
  [state cell cue groups]
  (boolean (some #(audience-ref? state groups %) (cue-refs state cell cue))))

(defn audience-cell?
  "Can the cue at cell reach the audience (audience-cue?)?"
  ([state cell] (audience-cell? state cell (audience-groups state)))
  ([state cell groups]
   (audience-cue? state cell (get-in state [:chains :cue-chains cell]) groups)))

(defn unplayable
  "Why vibe mode never plays cue, at cell: :empty (no items), :timecode (it
   follows MIDI timecode) or :crowd (it can reach the audience, audience-cue?,
   in a vibe that doesn't allow it; nil groups for one that does). nil when
   it can play it."
  [state cell cue groups]
  (cond
    (empty? (:items cue)) :empty
    (playback/chases-mtc? cue) :timecode
    (and groups (audience-cue? state cell cue groups)) :crowd))

(defn playable-cells
  "Those of cells (in order, each once) vibe may start: the cue has items,
   doesn't follow MIDI timecode, and can't reach the audience, unless the
   vibe has :allow-audience? true."
  [state vibe cells]
  (let [chains (get-in state [:chains :cue-chains])
        groups (when-not (:allow-audience? vibe) (audience-groups state))]
    (into []
          (comp (distinct)
                (filter #(nil? (unplayable state % (get chains %) groups))))
          cells)))


;; The Vibes tab's slots: what a click on its picker fills

(def cue-slots
  "The roles that name cue cells, as the Vibes tab's picker fills them: the
   HIT and ACCENT pools, and the FLASH cue and the BED, one cell each."
  [:hit :accent :flash-cue :bed])

(def fx-slots
  "The roles that name effect cells: the FX set and the FLASH effects."
  [:fx :flash-fx])

(def slots
  "Every slot, in the Vibes tab's order."
  (into cue-slots fx-slots))

(def ^:private slot-paths
  {:hit [:hit :cells] :accent [:accent :cells] :flash-cue [:flash :cue] :bed [:bed]
   :fx [:fx] :flash-fx [:flash :fx]})

(def ^:private one-cell? #{:flash-cue :bed})

(defn slot-cells
  "The cells vibe names in slot, in order: none for an unset FLASH cue or
   BED, or a slot that isn't one."
  [vibe slot]
  (if-let [path (slot-paths slot)]
    (let [v (get-in vibe path)]
      (if (one-cell? slot) (if v [v] []) (vec v)))
    []))

(defn in-slot?
  "Does the vibe with id name cell in slot?"
  [vibes id slot cell]
  (boolean (some #{cell} (slot-cells (get-in vibes [:library id]) slot))))

(defn edited-id
  "The vibe the Vibes tab edits: the one selected ([:ui :vibes :selected]),
   or the first of :order while none is (or it went); nil without vibes."
  [state]
  (let [selected (get-in state [:ui :vibes :selected])]
    (if (vibe state selected) selected (first (get-in state [:config :vibes :order])))))


;; Edits (the Vibes tab). Each takes the vibes and returns them, as they
;; were when there is nothing to do: an unknown vibe, a bad value.

(def palette
  "The colours new vibes take in turn; the starter vibes have the first two."
  ["#4A90D9" "#9B59B6" "#E67E22" "#1ABC9C" "#D94A4A" "#F1C40F" "#E91E63" "#4AD94A"])

(defn new-vibe
  "An empty vibe with id, called vibe-name, in the palette's colour for its
   number n."
  [id n vibe-name]
  {:id id :name vibe-name :color (nth palette (mod (dec n) (count palette))) :energy 2 :allow-audience? false
   :hit {:cells [] :select :shuffle :mode :decay :beats 1.0}
   :accent {:cells [] :select :shuffle :mode :decay :beats 2.0}
   :flash {:cue nil :fx [] :solo? true}
   :bed nil :fx []})

(defn- take-id
  "[id n vibes]: the first free id vibe-<n> from :next-id on, and vibes with
   :next-id past it. :next-id only grows, so the id of a vibe that went is
   never handed out again (an undo puts it back with the rest)."
  [vibes]
  (let [n (first (remove #(contains? (:library vibes) (keyword (str "vibe-" %)))
                         (iterate inc (max 1 (or (:next-id vibes) 1)))))]
    [(keyword (str "vibe-" n)) n (assoc vibes :next-id (inc n))]))

(defn- index-of [xs x]
  (first (keep-indexed #(when (= x %2) %1) xs)))

(defn- insert-at [v i x]
  (into (conj (subvec v 0 i) x) (subvec v i)))

(defn- append-id [vibes id]
  (update vibes :order #(conj (vec %) id)))

(defn add-vibe
  "vibes with a new empty vibe, \"Vibe <n>\", last in :order."
  [vibes]
  (let [[id n vibes] (take-id vibes)]
    (-> vibes
        (assoc-in [:library id] (new-vibe id n (str "Vibe " n)))
        (append-id id))))

(defn duplicate-vibe
  "vibes with a copy of the vibe with id, \"<name> copy\", right after it in
   :order."
  [vibes id]
  (if-let [vibe (get-in vibes [:library id])]
    (let [[copy _ vibes] (take-id vibes)
          order (vec (:order vibes))]
      (-> vibes
          (assoc-in [:library copy] (assoc vibe :id copy :name (str (:name vibe) " copy")))
          (assoc :order (insert-at order (inc (or (index-of order id) (dec (count order)))) copy))))
    vibes))

(defn remove-vibe
  "vibes without the vibe with id, in the library and :order. :next-id
   stays."
  [vibes id]
  (if (or (get-in vibes [:library id]) (index-of (:order vibes) id))
    (-> vibes
        (update :library dissoc id)
        (update :order #(filterv (complement #{id}) %)))
    vibes))

(defn move-vibe
  "vibes with the vibe with id moved to index to of :order (clamped)."
  [vibes id to]
  (let [order (vec (:order vibes))]
    (if (and (index-of order id) (integer? to))
      (let [others (filterv #(not= id %) order)]
        (assoc vibes :order (insert-at others (max 0 (min to (count others))) id)))
      vibes)))

(defn- edit-vibe
  "vibes with (f vibe & args) for the vibe with id, if there is one."
  [vibes id f & args]
  (if (get-in vibes [:library id])
    (apply update-in vibes [:library id] f args)
    vibes))

(defn rename-vibe
  "vibes with the vibe with id called vibe-name, trimmed. A blank name
   changes nothing."
  [vibes id vibe-name]
  (let [vibe-name (some-> vibe-name str str/trim)]
    (if (seq vibe-name)
      (edit-vibe vibes id assoc :name vibe-name)
      vibes)))

(defn set-color
  "vibes with the vibe with id in color, \"#RRGGBB\"."
  [vibes id color]
  (if (and (string? color) (re-matches #"#[0-9A-Fa-f]{6}" color))
    (edit-vibe vibes id assoc :color color)
    vibes))

(defn set-energy
  "vibes with the vibe with id at energy 1 to 4."
  [vibes id energy]
  (if (and (integer? energy) (<= 1 energy 4))
    (edit-vibe vibes id assoc :energy energy)
    vibes))

(defn- toggled
  "cells without cell if it is there, else with it at the end."
  [cells cell]
  (if (some #{cell} cells)
    (filterv #(not= cell %) cells)
    (conj (vec cells) cell)))

(defn set-single
  "vibes with the FLASH cue or the BED (slot :flash-cue or :bed) of the vibe
   with id set to cell, or cleared with nil."
  [vibes id slot cell]
  (if (one-cell? slot)
    (edit-vibe vibes id assoc-in (slot-paths slot) cell)
    vibes))

(defn toggle-cell
  "vibes with cue cell put in slot (cue-slots) of the vibe with id, or
   taken out: a HIT or ACCENT pool grows at its end (a pool's order is the
   order of the clicks), the FLASH cue and the BED are replaced or cleared."
  [vibes id slot cell]
  (cond
    (nil? cell) vibes
    (one-cell? slot) (set-single vibes id slot (when-not (in-slot? vibes id slot cell) cell))
    (#{:hit :accent} slot) (edit-vibe vibes id update-in (slot-paths slot) toggled cell)
    :else vibes))

(defn toggle-fx
  "vibes with effect cell put at the end of the FX set (slot :fx) or the
   FLASH effects (:flash-fx) of the vibe with id, or taken out."
  [vibes id slot cell]
  (if (and cell (#{:fx :flash-fx} slot))
    (edit-vibe vibes id update-in (slot-paths slot) toggled cell)
    vibes))

(def ^:private options
  "role -> option -> the values it takes (nil: the vibe's own options)."
  (let [pool {:mode #{:decay :gate :sustain}
              :select #{:shuffle :sequence}
              :beats #(and (number? %) (<= 0.25 % 64))}]
    {:hit pool :accent pool :flash {:solo? boolean?} nil {:allow-audience? boolean?}}))

(defn set-role-option
  "vibes with option k of role (nil: the vibe's own) of the vibe with id set
   to v, if it is a value that option takes: HIT's and ACCENT's :mode
   (:decay :gate :sustain), :select (:shuffle :sequence) and :beats (0.25
   to 64), FLASH's :solo?, and the vibe's :allow-audience?."
  [vibes id role k v]
  (let [valid? (get-in options [role k])]
    (if (and valid? (valid? v))
      (edit-vibe vibes id assoc-in (if role [role k] [k]) (if (= :beats k) (double v) v))
      vibes)))

(defn make-from-rows
  "vibes with a vibe per row of the cue grid that has cues, \"Row A\" …,
   after the others in :order, its HIT pool the row's cues left to right. A
   row whose cues are some vibe's HIT pool already is left out and no vibe
   changes, so making them again adds nothing. cue-chains: [:chains
   :cue-chains]; size: the grid's [cols rows]."
  [vibes cue-chains [cols rows]]
  (reduce (fn [vibes row]
            (let [cells (filterv #(seq (:items (get cue-chains %))) (for [col (range (or cols 0))] [col row]))
                  pools (set (map #(set (get-in % [:hit :cells])) (vals (:library vibes))))]
              (if (or (empty? cells) (contains? pools (set cells)))
                vibes
                (let [[id n vibes] (take-id vibes)]
                  (-> vibes
                      (assoc-in [:library id] (assoc-in (new-vibe id n (str "Row " (char (+ 65 row)))) [:hit :cells] cells))
                      (append-id id))))))
          vibes
          (range (or rows 0))))


;; Who names a cell (the Vibes tab's picker)

(defn cell-index
  "Who names each cell, and as what: {cell [{:vibe :color :role :pos}]}, on
   the cue grid (grid :cue; roles :hit :accent :flash-cue :bed) or the
   effects grid (:fx; :fx :flash-fx), in the vibes' order, then the roles'.
   :pos is the cell's place in a HIT or ACCENT pool, from 1."
  [vibes grid]
  (reduce
   (fn [index id]
     (let [vibe (get-in vibes [:library id])]
       (reduce (fn [index slot]
                 (reduce-kv (fn [index i cell]
                              (update index cell (fnil conj [])
                                      (cond-> {:vibe id :color (:color vibe) :role slot}
                                        (#{:hit :accent} slot) (assoc :pos (inc i)))))
                            index
                            (vec (distinct (slot-cells vibe slot)))))
               index
               (if (= :fx grid) fx-slots cue-slots))))
   {}
   (filter #(get-in vibes [:library %]) (distinct (:order vibes)))))


;; Lint and the checklist (the Vibes tab). Lint never blocks saving,
;; editing or playing: the engine plays what it can.

(defn- cell-name
  "A cell as the grid's row letters and column numbers name it: \"A1\"."
  [[col row]]
  (str (char (+ 65 row)) (inc col)))

(defn- heard-roles
  "The controls a key is bound to."
  [state]
  (let [actions (vals (get-in state [:config :input :trigger-map]))]
    (into #{} (filter (fn [role] (some #(binds-role? % role) actions))) roles)))

(defn- learned
  "How many of the five controls have a binding."
  [state]
  (count (heard-roles state)))

(defn- scanner-through
  "The name of the scanner that group reaches: the first projector tagged
   :crowd-scanning, in rig order, that group reaches (routing.rig/reaching),
   else the first such virtual projector; nil for none."
  [state group]
  (let [projectors (:projectors state)]
    (or (some (fn [id]
                (when (and (crowd? (get projectors id)) (some #{group} (rig/reaching state id)))
                  (rig/projector-name state id)))
              (rig/effective-order (get-in state [:rig :order]) projectors))
        (some #(when (some #{group} (rig/stored-refs %)) (:name %)) (crowd-virtual-projectors state)))))

(defn- audience-text
  "Why vibe-name never plays the cue at cell: the audience group it reaches,
   named as the pickers name it (\"All\", \"Left\", a laser, a group), and the
   projector that scans the crowd there (not for :crowd, nor for a laser's
   own group, which names it); or the zone, and the map that sends it there
   (zones plan Z4): \"through Zone B (the Crowd sweep map sends it to Crowd
   Scanning)\". A group before a zone."
  [state vibe-name cell cue groups]
  (let [hits (filter #(audience-ref? state groups %) (cue-refs state cell cue))
        ref (if (some #{:crowd} hits)
              :crowd
              (first (sort-by (fn [r] [(boolean (targets/zone-ref? r)) (or (targets/zone-index r) 0) r]) hits)))
        through (if (targets/zone-ref? ref)
                  (let [m (first (filter #(contains? groups (targets/zone-lane % ref)) (zone-maps/all-maps state)))]
                    (str "Zone " (targets/zone-letter ref) " (the " (:name m) " map sends it to "
                         (rig/ref-name state (targets/zone-lane m ref)) ")"))
                  (let [scanner (when-not (or (= :crowd ref) (rig/laser-of ref))
                                  (scanner-through state ref))]
                    (str (rig/ref-name state ref) (when scanner (str " (" scanner ")")))))]
    (str vibe-name ": " (cell-name cell) " reaches the audience through " through
         ", so this vibe never plays it. Allow audience cells, or take it out")))

(defn- cell-problems
  "Lint rows for the cell a vibe names, label saying as what (\"FLASH cue \")."
  [state vibe-name label cell groups]
  (let [cue (get-in state [:chains :cue-chains cell])]
    (case (unplayable state cell cue groups)
      :empty [{:level :error :text (str vibe-name ": " label (cell-name cell) " can't be played (empty)")}]
      :timecode [{:level :error :text (str vibe-name ": " label (cell-name cell) " can't be played (follows timecode)")}]
      :crowd [{:level :warn :text (audience-text state vibe-name cell cue groups)}]
      nil)))

(defn- moves-only
  "The info row for a role (role-name, \"HIT\") whose press plays no cue but
   moves effects, its reactions (moves); none without any."
  [vibe-name role-name moves]
  (when-let [names (seq (distinct (map :name moves)))]
    [{:level :info :text (str vibe-name ": " role-name " moves effects only (" (str/join ", " names) ")")}]))

(def ^:private source-names
  {:hit "HIT" :accent "ACCENT" :flash "FLASH" :vibe "VIBE" :any "HIT, ACCENT or FLASH"})

(defn- unheard
  "Warn rows for the reactions of a vibe (moves, by role) that follow a
   control no key is bound to (heard: those a key is): one that follows
   HIT, ACCENT or FLASH only when none of them has one. None while no key
   is bound at all: the controls' own row says so."
  [state vibe-name moves heard]
  (when (seq heard)
    (for [{:keys [grid cell] reaction :name} (distinct (mapcat val moves))
          :let [sources (reactions/listens-to (get-in state [:chains (if (= :effects grid) :effect-chains :cue-chains) cell]))]
          source [:hit :accent :flash :vibe :any]
          :when (and (contains? sources source) (not-any? heard (reactions/roles-of source)))]
      {:level :warn
       :text (str vibe-name " · " reaction " listens to " (source-names source)
                  (if (= :any source) ", none of which has a key yet" ", which has no key yet"))})))

(defn- vibe-problems
  "Lint rows for one vibe; groups: the audience groups, nil if it allows
   them; heard: the controls a key is bound to. Its tap reactions are its
   own cells' (vibes.reactions/reactions :own?), not an effect cell's that
   was switched on by hand: that one is no vibe's, and can go off at any
   time."
  [state vibe groups heard]
  (let [vibe-name (or (not-empty (:name vibe)) (name (:id vibe)))
        hit (vec (distinct (get-in vibe [:hit :cells])))
        accent (vec (distinct (get-in vibe [:accent :cells])))
        {:keys [cue fx]} (:flash vibe)
        cells (fn [label cs] (mapcat #(cell-problems state vibe-name label % groups) cs))
        moves (reactions/reactions state (:id vibe) :own? true)
        quiet? (fn [cs] (empty? (playable-cells state vibe cs)))]
    (concat
     (cells "" hit)
     (cells "" accent)
     (case (count (playable-cells state vibe hit))
       0 (or (moves-only vibe-name "HIT" (:hit moves))
             [{:level :error :text (str vibe-name ": HIT does nothing (no cues, no tap reactions)")}])
       1 [{:level :warn :text (str vibe-name ": one cue in HIT repeats on every press")}]
       nil)
     (when (quiet? accent) (moves-only vibe-name "ACCENT" (:accent moves)))
     (when cue (cells "FLASH cue " [cue]))
     (cond
       (and (nil? cue) (empty? fx) (empty? (:flash moves)))
       [{:level :warn :text (str vibe-name ": FLASH does nothing in this vibe")}]
       (quiet? (when cue [cue])) (moves-only vibe-name "FLASH" (:flash moves)))
     (when (:bed vibe) (cells "BED " [(:bed vibe)]))
     (for [cell hit :when (some #{cell} accent)]
       {:level :warn :text (str vibe-name ": " (cell-name cell) " is in both HIT and ACCENT")})
     (unheard state vibe-name moves heard))))

(defn lint
  "What to fix before the show, worst first: [{:level :error|:warn|:info
   :vibe (the vibe it is about, or nil) :text}]. state needs the vibes, the
   cue and effects grids, the projectors, the zone groups and the trigger
   map."
  [state]
  (let [{:keys [order library]} (get-in state [:config :vibes])
        groups (audience-groups state)
        vibes (keep library (distinct order))
        names (frequencies (map :name vibes))
        n (learned state)
        heard (heard-roles state)]
    (->> (concat
          (when (or (not= (count order) (count (distinct order))) (not= (set order) (set (keys library))))
            [{:level :error :text "Order and library disagree"}])
          (mapcat (fn [vibe]
                    (map #(assoc % :vibe (:id vibe))
                         (vibe-problems state vibe (when-not (:allow-audience? vibe) groups) heard)))
                  vibes)
          (for [vibe-name (distinct (map :name vibes))
                :let [k (names vibe-name)]
                :when (and vibe-name (> k 1))]
            {:level :warn :text (str (if (= 2 k) "Two" k) " vibes are called " vibe-name)})
          (when (< n (count roles))
            [{:level :info :text (str n " of " (count roles) " controls learned")}]))
         (sort-by (comp {:error 0 :warn 1 :info 2} :level))
         vec)))

(defn checklist
  "What is left to do before vibe mode plays, from the data: [{:step :text
   :done?}] for cues on the grid, a vibe, HIT cues to play in every vibe,
   and the five controls. state as lint's."
  [state]
  (let [{:keys [order library]} (get-in state [:config :vibes])
        vibes (keep library order)
        n (learned state)]
    [{:step :cues :text "Make cues on the Grid tab"
      :done? (boolean (some (comp seq :items) (vals (get-in state [:chains :cue-chains]))))}
     {:step :vibes :text "Add a vibe" :done? (boolean (seq vibes))}
     {:step :hit :text "Put HIT cues in every vibe"
      :done? (boolean (and (seq vibes) (every? #(seq (playable-cells state % (get-in % [:hit :cells]))) vibes)))}
     {:step :controls :text (str "Learn the five controls (" n " of " (count roles) ")")
      :done? (= n (count roles))}]))
