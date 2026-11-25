(ns laser-show.vibes.refs
  "Every place that names a grid cell, kept naming the same content when a
   cell moves and let go of when it is cleared. Pure.

   - move-cue, remove-cue: the vibes' pools, flash cue and bed
     ([:config :vibes]), and what the engine plays, ducked or bagged
     ([:vibe-runtime]), so a release still finds the cue its press started.
   - move-fx, remove-fx: the vibes' FX sets and flash effect cells, the
     effect cells the engine switched on, the cell its refusal names
     ([:vibe-runtime :problem]), and the cell a held key switched on
     ([:trigger-runtime :activations]), so its release switches that off.
   - restore-fx: the same live references, after a restore (Undo, Redo)
     put cells back (reactivity plan 22), and the owner a cell a move had
     replaced had then (fx-owners, kept with each undo step; follow-up B8).
   - move-trigger-targets, remove-trigger-targets: the trigger map, for
     either grid.

   Cue cells and effect cells are separate grids: [0 0] in one is not [0 0]
   in the other.")

(defn- rename
  "cells with from as to, each once, in order (cells as they were without
   from)."
  [cells from to]
  (if (some #{from} cells)
    (into [] (comp (map #(if (= from %) to %)) (distinct)) cells)
    cells))

(defn- without [cells cell]
  (if (some #{cell} cells)
    (filterv #(not= cell %) cells)
    cells))

(defn- moved-in
  "cells after the content at from replaced the content at to: what was at
   to is gone, and from is now called to."
  [cells from to]
  (rename (without cells to) from to))

(defn- update-some
  "m with (f value) at ks, only where m has a value there."
  [m ks f & args]
  (if (some? (get-in m ks))
    (apply update-in m ks f args)
    m))

(defn- update-vibes
  "state with f applied to every vibe in the library. The library stays the
   same map when f changes no vibe."
  [state f]
  (let [library (get-in state [:config :vibes :library])
        library' (reduce-kv (fn [m id vibe] (assoc m id (f vibe))) library library)]
    (if (identical? library library')
      state
      (assoc-in state [:config :vibes :library] library'))))

(defn- update-runtime [state f]
  (if (:vibe-runtime state)
    (update state :vibe-runtime f)
    state))

(defn- update-cells
  "m's values that are maps naming cell (at :cell) with :cell (f cell), the
   others as they are; entries whose new cell is nil go."
  [m f]
  (reduce-kv (fn [acc k v]
               (if-let [cell (:cell v)]
                 (if-let [cell' (f cell)]
                   (assoc acc k (assoc v :cell cell'))
                   (dissoc acc k))
                 acc))
             m m))

(defn- same-or [from to] #(if (= from %) to %))


;; Cue cells

(defn move-cue
  "state after the cue at from moved to to (replacing what was there):
   every vibe names to where it named from, each pool keeping its order and
   naming a cell once; what the engine plays at from, or keeps for a solo
   flash's release, is at to, and the bags keep their cycle."
  [state from to]
  (if (= from to)
    state
    (-> state
        (update-vibes (fn [vibe]
                        (-> vibe
                            (update-some [:hit :cells] rename from to)
                            (update-some [:accent :cells] rename from to)
                            (update-some [:flash :cue] (same-or from to))
                            (update-some [:bed] (same-or from to)))))
        (update-runtime
         (fn [rt]
           (-> rt
               (update-some [:voices] update-cells (same-or from to))
               (update-some [:duck] update-cells #(cond (= to %) nil (= from %) to :else %))
               (update-some [:bags] update-vals
                            (fn [bag]
                              (-> bag
                                  (update :items moved-in from to)
                                  (update :remaining moved-in from to)
                                  (update :last #(cond (= from %) to (= to %) nil :else %)))))))))))

(defn remove-cue
  "state after the cue at cell was cleared: no pool names it, a flash cue or
   bed that was it is nil, and a solo flash won't start it again. A voice
   that played it is forgotten once its run is gone (laser-show.vibes.engine)."
  [state cell]
  (-> state
      (update-vibes (fn [vibe]
                      (-> vibe
                          (update-some [:hit :cells] without cell)
                          (update-some [:accent :cells] without cell)
                          (update-some [:flash :cue] #(when (not= cell %) %))
                          (update-some [:bed] #(when (not= cell %) %)))))
      (update-runtime #(update-some % [:duck] update-cells (fn [c] (when (not= cell c) c))))))


;; Effect cells

(defn- update-problem-cell
  "state with the vibe's refusal ([:vibe-runtime :problem] {:why
   :audience-fx :cell}: an effect cell it left off) naming (f cell), and
   none when that is nil: what it said is about a cell that is gone."
  [state f]
  (if-let [cell (get-in state [:vibe-runtime :problem :cell])]
    (let [cell' (f cell)]
      (cond
        (= cell cell') state
        (nil? cell') (assoc-in state [:vibe-runtime :problem] nil)
        :else (assoc-in state [:vibe-runtime :problem :cell] cell')))
    state))

(defn- update-held-fx
  "state with the effect cell each held key switched on (its activation's
   :target, a :trigger-effect: events/handlers/triggers.clj) as (f cell),
   so the key's release switches off that one; nil, its cell gone, and the
   release switches nothing."
  [state f]
  (if-let [activations (not-empty (get-in state [:trigger-runtime :activations]))]
    (let [activations' (reduce-kv (fn [m k {:keys [type target] :as action}]
                                    (if (and (= :trigger-effect type) (vector? target))
                                      (let [target' (f target)]
                                        (cond-> m (not= target target') (assoc k (assoc action :target target'))))
                                      m))
                                  activations activations)]
      (if (identical? activations activations')
        state
        (assoc-in state [:trigger-runtime :activations] activations')))
    state))

(defn move-fx
  "state after the effect cell at from moved to to (replacing what was
   there): every FX set and flash names to where it named from, and the cells
   the engine switched on follow, as do its refusal and what a held key
   switched on; what named to's own cell lets go of it."
  [state from to]
  (if (= from to)
    state
    (let [moved #(cond (= to %) nil (= from %) to :else %)]
      (-> state
          (update-vibes (fn [vibe]
                          (-> vibe
                              (update-some [:fx] rename from to)
                              (update-some [:flash :fx] rename from to))))
          (update-runtime (fn [rt]
                            (-> rt
                                (update-some [:fx-on] moved-in from to)
                                (update-some [:voices :flash :fx] rename from to)
                                (update-some [:voices :flash :fx-on] moved-in from to))))
          (update-problem-cell moved)
          (update-held-fx moved)))))

(defn remove-fx
  "state after the effect cell at cell was cleared: no FX set or flash names
   it, and the engine has nothing there to switch off; its refusal goes, and
   a held key's release switches nothing there."
  [state cell]
  (let [gone #(when (not= cell %) %)]
    (-> state
        (update-vibes (fn [vibe]
                        (-> vibe
                            (update-some [:fx] without cell)
                            (update-some [:flash :fx] without cell))))
        (update-runtime (fn [rt]
                          (-> rt
                              (update-some [:fx-on] without cell)
                              (update-some [:voices :flash :fx] without cell)
                              (update-some [:voices :flash :fx-on] without cell))))
        (update-problem-cell gone)
        (update-held-fx gone))))

(defn- restored
  "cells after a restore that took each restored cell's on/off from a cell
   (sources: restored -> that cell): for each of cells in order, the
   restored cells that took theirs from it (sorted, when several), each
   once. One no restored cell took from is gone."
  [cells sources]
  (let [takers (reduce-kv (fn [m to from] (update m from (fnil conj []) to)) {} sources)
        cells' (into [] (comp (mapcat #(sort (get takers %))) (distinct)) cells)]
    (if (= cells cells') cells cells')))

(defn- restored-cell
  "The restored cell that took its on/off from cell (sources): cell itself
   when it is one of them, else the first; nil when none did (its content
   is gone)."
  [sources cell]
  (let [takers (sort (keep (fn [[to from]] (when (= from cell) to)) sources))]
    (if (some #{cell} takers) cell (first takers))))

(defn fx-owners
  "Who owns the effect cells that are on, for an undo step to keep beside
   its document (state.history/make-snapshot): what restore-fx gives a cell
   a move replaced back. The FX set's cells (:fx-on) and the look they were
   switched on in (:look, the vibe that plays and since when; none during
   blackout), the held flash's cells (:fx asked for, :fx-on switched on)
   and its press (:press, when FLASH was pressed), and the cell each held
   key switched on (:holds {key cell}). nil when nothing owns any."
  [state]
  (let [{:keys [fx-on current entered-at blackout? voices held]} (:vibe-runtime state)
        flash (:flash voices)
        holds (into {}
                    (keep (fn [[k {:keys [type target]}]]
                            (when (and (= :trigger-effect type) (vector? target)) [k target])))
                    (get-in state [:trigger-runtime :activations]))]
    (not-empty
     (cond-> {}
       (seq fx-on) (assoc :fx-on (vec fx-on))
       (and current (not blackout?)) (assoc :look [current entered-at])
       flash (assoc :flash {:fx (vec (:fx flash)) :fx-on (vec (:fx-on flash)) :press (get-in held [:flash :at])})
       (seq holds) (assoc :holds holds)))))

(defn- give-back
  "state, after a restore that put back cell, whose content a move had
   replaced (state.history/replaced-effect-cells), on or off as the undo
   step has it: given back to what owned it then (owners, fx-owners) where
   that owner is still the same: the FX set while its look still plays,
   the held flash while the same press holds it, a key while it holds the
   activation the move let go of. A cell one of them had switched on whose
   owner is gone goes off; one on by hand stays on."
  [state cell {:keys [fx-on look flash holds]}]
  (let [{:keys [current entered-at blackout? held] :as rt} (:vibe-runtime state)
        in? (fn [cells] (boolean (some #{cell} cells)))
        fx? (and (in? fx-on) (some? look) (not blackout?) (= look [current entered-at]))
        press? (and (some? (:press flash)) (some? (get-in rt [:voices :flash]))
                    (= (:press flash) (get-in held [:flash :at])))
        key (some (fn [[k target]]
                    (when (and (= cell target)
                               (let [{:keys [type target] :as action} (get-in state [:trigger-runtime :activations k])]
                                 (and action (= :trigger-effect type) (nil? target))))
                      k))
                  holds)
        owned? (or (in? fx-on) (in? (:fx-on flash)) (some #(= cell (val %)) holds))
        kept? (or fx? (and press? (in? (:fx-on flash))) (some? key))]
    (cond-> state
      fx? (update-in [:vibe-runtime :fx-on] (fnil conj []) cell)
      (and press? (in? (:fx flash))) (update-in [:vibe-runtime :voices :flash :fx] #(vec (distinct (conj (vec %) cell))))
      (and press? (in? (:fx-on flash))) (update-in [:vibe-runtime :voices :flash :fx-on] (fnil conj []) cell)
      key (assoc-in [:trigger-runtime :activations key :target] cell)
      (and owned? (not kept?) (get-in state [:chains :effect-chains cell :active?]))
      (assoc-in [:chains :effect-chains cell :active?] false))))

(defn restore-fx
  "state after a document restore (Undo, Redo, a press's abort) put effect
   cells back, each taking its on/off from the cell sources names for it
   (state.history/effect-cell-sources): the cells the engine switched on,
   and those the held flash asked for and switched on, are now the
   restored cells that took theirs from one of them, so each switch keeps
   its owner, and so do the vibe's refusal and what a held key switched
   on. One whose content is gone is let go of, and no other becomes the
   engine's. A cell whose content a move had replaced (replaced, back
   with its own on/off) gets its owner back from owners, the step's
   fx-owners (give-back). Nothing else is switched here, and the vibes'
   own FX sets and flash cells are the document's, restored with it."
  ([state sources] (restore-fx state sources #{} nil))
  ([state sources replaced owners]
   (let [state (-> state
                   (update-runtime (fn [rt]
                                     (-> rt
                                         (update-some [:fx-on] restored sources)
                                         (update-some [:voices :flash :fx] restored sources)
                                         (update-some [:voices :flash :fx-on] restored sources))))
                   (update-problem-cell #(restored-cell sources %))
                   (update-held-fx #(restored-cell sources %)))]
     (reduce #(give-back %1 %2 owners) state (sort replaced)))))


;; The trigger map

(defn move-trigger-targets
  "trigger-map with the target-type bindings to from-pos bound to to-pos."
  [trigger-map from-pos to-pos target-type]
  (if (seq trigger-map)
    (into {}
          (map (fn [[k v]]
                 [k (if (and (= (:type v) target-type)
                             (= (:target v) from-pos))
                      (assoc v :target to-pos)
                      v)]))
          trigger-map)
    trigger-map))

(defn remove-trigger-targets
  "trigger-map without the target-type bindings to pos."
  [trigger-map pos target-type]
  (if (seq trigger-map)
    (into {}
          (remove (fn [[_ v]]
                    (and (= (:type v) target-type)
                         (= (:target v) pos))))
          trigger-map)
    trigger-map))
