(ns laser-show.vibes.refs
  "Every place that names a grid cell, kept naming the same content when a
   cell moves and let go of when it is cleared. Pure.

   - move-cue, remove-cue: the vibes' pools, flash cue and bed
     ([:config :vibes]), and what the engine plays, ducked or bagged
     ([:vibe-runtime]), so a release still finds the cue its press started.
   - move-fx, remove-fx: the vibes' FX sets and flash effect cells, and the
     effect cells the engine switched on.
   - restore-fx: the effect cells the engine switched on, after a restore
     (Undo, Redo) put cells back (reactivity plan 22).
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

(defn move-fx
  "state after the effect cell at from moved to to (replacing what was
   there): every FX set and flash names to where it named from, and the cells
   the engine switched on follow."
  [state from to]
  (if (= from to)
    state
    (-> state
        (update-vibes (fn [vibe]
                        (-> vibe
                            (update-some [:fx] rename from to)
                            (update-some [:flash :fx] rename from to))))
        (update-runtime (fn [rt]
                          (-> rt
                              (update-some [:fx-on] moved-in from to)
                              (update-some [:voices :flash :fx] rename from to)
                              (update-some [:voices :flash :fx-on] moved-in from to)))))))

(defn remove-fx
  "state after the effect cell at cell was cleared: no FX set or flash names
   it, and the engine has nothing there to switch off."
  [state cell]
  (-> state
      (update-vibes (fn [vibe]
                      (-> vibe
                          (update-some [:fx] without cell)
                          (update-some [:flash :fx] without cell))))
      (update-runtime (fn [rt]
                        (-> rt
                            (update-some [:fx-on] without cell)
                            (update-some [:voices :flash :fx] without cell)
                            (update-some [:voices :flash :fx-on] without cell))))))

(defn- restored
  "cells after a restore that took each restored cell's on/off from a cell
   (sources: restored -> that cell): for each of cells in order, the
   restored cells that took theirs from it (sorted, when several), each
   once. One no restored cell took from is gone."
  [cells sources]
  (let [takers (reduce-kv (fn [m to from] (update m from (fnil conj []) to)) {} sources)
        cells' (into [] (comp (mapcat #(sort (get takers %))) (distinct)) cells)]
    (if (= cells cells') cells cells')))

(defn restore-fx
  "state after a document restore (Undo, Redo, a press's abort) put effect
   cells back, each taking its on/off from the cell sources names for it
   (state.history/effect-cell-sources): the cells the engine switched on,
   and those the held flash asked for and switched on, are now the
   restored cells that took theirs from one of them, so each switch keeps
   its owner. One whose content is gone is let go of, and no other becomes
   the engine's. Nothing is switched here, and the vibes' own FX sets and
   flash cells are the document's, restored with it."
  [state sources]
  (update-runtime state (fn [rt]
                          (-> rt
                              (update-some [:fx-on] restored sources)
                              (update-some [:voices :flash :fx] restored sources)
                              (update-some [:voices :flash :fx-on] restored sources)))))


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
