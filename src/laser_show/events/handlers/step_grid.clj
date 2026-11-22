(ns laser-show.events.handlers.step-grid
  "The step grid's events (zones plan Z6, plans/2026-11-19-zones/08 §6),
   routed for \"step-grid\" (events/handlers.clj). A grid is a zone pattern
   (routing.step-grid), addressed as the pattern window and the effects
   grid's editor address it, {:domain :entity-key :effect-path}: a cue's
   pattern (:cue-zone-patterns) or an effects cell's (:effect-chains). Each
   edit reads and writes the instance's :params where chain-config puts
   them, so it works for both alike, and is one undo step, \"Edit Step
   Grid\" (events/handlers/history.clj); a drag is one too, a history
   transaction from press to release. While the pattern's keyframes are on,
   no edit changes it (08 invariant 9), and opening it makes no grid.

   :step-grid/open opens the window ([:ui :dialogs :step-grid-editor]) and
   makes the pattern a grid if it isn't one (routing.step-grid/as-grid), or
   gives a cue with none a new grid; while the show lock is on it only
   opens the window (events/show_lock.clj refuses the edits). The cursor
   ([:ui :dialogs :step-grid-editor :cursor] [row step]) is UI state, never
   undone. Copy and Paste use the one clipboard (keyboard plan Phase 4,
   Step 4a): Copy puts a :step-grid clip in [:ui :clipboard] and its EDN on
   the OS clipboard; Paste pastes the clipboard's grid, reading the OS
   clipboard first while nothing is copied in this app (h/with-clipboard).

   :step-grid/pointer is the grid body's mouse: it reads the JavaFX event
   and dispatches :step-grid/paint-start (the press: begins the transaction
   and paints the cell with the opposite of its state, Z6-Q3 A), :paint
   (each cell the drag crosses, with that value) and :paint-end (the
   release: commits). The lock refuses the first two; a release always lets
   go."
  (:require [laser-show.events.handlers.chain.helpers :as chain-helpers]
            [laser-show.events.handlers.history :as history-handlers]
            [laser-show.events.helpers :as h]
            [laser-show.events.show-lock :as show-lock]
            [laser-show.routing.rig :as rig]
            [laser-show.routing.step-grid :as step-grid]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.state.clipboard :as clipboard])
  (:import [javafx.scene.input MouseButton MouseEvent]))

(def ^:private dialog-path [:ui :dialogs :step-grid-editor])

(def ^:private label "Edit Step Grid")


;; Where a grid is

(defn instance-path
  "The path in app state of target's zone pattern instance
   ({:domain :entity-key :effect-path}, as chain-config reads them), or nil
   for a target that isn't a cue's pattern or an effects cell's."
  [{:keys [domain entity-key effect-path]}]
  (when (and (contains? #{:cue-zone-patterns :effect-chains} domain)
             (some? entity-key)
             (sequential? effect-path)
             (seq effect-path))
    (into (:items-path (chain-helpers/chain-config domain entity-key)) effect-path)))

(defn- pattern-at
  "target's instance when it is a zone pattern, else nil."
  [state target]
  (when-let [path (instance-path target)]
    (let [fx (get-in state path)]
      (when (= :zone-pattern (:effect-id fx)) fx))))

(defn keyframes-on?
  "Are the pattern's keyframes on? Its grid isn't edited then (08
   invariant 9)."
  [fx]
  (true? (get-in fx [:keyframe-modulator :enabled?])))

(defn laser-count
  "How many lasers the rig has on now: Every laser becomes that many zones
   in a grid."
  [state]
  (count (rig/e-order (rig/rig-inputs state))))

(defn grid-params
  "target's params as its grid shows them (routing.step-grid/as-grid), or
   nil when it holds no zone pattern."
  [state target]
  (when-let [fx (pattern-at state target)]
    (step-grid/as-grid (:params fx) (laser-count state))))

(defn- row-ref
  "A row by its ref, or by its index (the cursor's)."
  [params row]
  (if (keyword? row) row (when (integer? row) (get (step-grid/rows params) row))))


;; The cursor

(defn- clamp [x lo hi] (max lo (min hi x)))

(defn- fit-cursor
  "The cursor inside the grid at target, [0 0] for none."
  [state target [r s]]
  (let [params (grid-params state target)
        rows (count (step-grid/rows params))
        len (step-grid/length params)]
    [(clamp (long (or r 0)) 0 (max 0 (dec rows))) (clamp (long (or s 0)) 0 (max 0 (dec len)))]))

(defn- set-cursor [state target cell]
  (if (= target (get-in state (conj dialog-path :target)))
    (assoc-in state (conj dialog-path :cursor) (fit-cursor state target cell))
    state))

(defn- cursor [state] (get-in state (conj dialog-path :cursor) [0 0]))


;; Edits

(defn- edit
  "state with (f params) as target's params, f given the grid as it shows
   (a pattern of another style becomes one first): unchanged while its
   keyframes are on, for a target that holds no zone pattern, and when f
   changes nothing. The cursor stays inside the grid."
  [state target f]
  (let [fx (pattern-at state target)]
    (if (or (nil? fx) (keyframes-on? fx))
      state
      (let [params (step-grid/as-grid (:params fx) (laser-count state))
            edited (step-grid/normalize (f params))]
        (if (= edited (:params fx))
          state
          (-> state
              (assoc-in (conj (instance-path target) :params) edited)
              (set-cursor target (cursor state))))))))

(defn- handle-edit
  "An event whose edit is (f params event)."
  [{:keys [target state] :as event} f]
  {:state (edit state target #(f % event))})

(defn- cell
  "The [ref step] an event names: its :row (a ref or an index) and :step,
   else the cursor's."
  [state params {:keys [row step]}]
  (if (some? step)
    [(row-ref params row) step]
    (let [[r s] (cursor state)]
      [(row-ref params r) s])))


;; Opening the window

(defn- with-new-grid
  "state with a new grid on the cue target names, when it is a cue's first
   pattern that it hasn't got: 8 steps at 2 a beat over A and B (Z6-Q1 A)."
  [state {:keys [domain entity-key effect-path] :as target}]
  (if (and (= :cue-zone-patterns domain)
           (= [0] effect-path)
           (some? (get-in state [:chains :cue-chains entity-key]))
           (empty? (get-in state [:chains :cue-chains entity-key :zone-patterns])))
    (assoc-in state [:chains :cue-chains entity-key :zone-patterns]
              [(h/ensure-item-fields (zone-patterns/preset-instance :steps :cue))])
    state))

(defn- handle-open
  "The step grid window on target: the pattern a grid (as-grid, its Every
   laser as zones, which the window names), or a cue with none a new grid,
   unless the show lock is on or its keyframes are; the cursor at the top
   left, as it was on the same grid."
  [{:keys [target state]}]
  (let [locked? (show-lock/locked? state)
        state' (cond-> state (not locked?) (with-new-grid target))
        fx (pattern-at state' target)]
    (if (nil? fx)
      {:state state}
      (let [convert? (and (not locked?) (not (keyframes-on? fx)) (not (step-grid/grid? (:params fx))))
            n (laser-count state)
            same? (= target (get-in state (conj dialog-path :target)))
            state' (cond-> state'
                     convert? (assoc-in (conj (instance-path target) :params) (step-grid/as-grid (:params fx) n)))]
        {:state (assoc-in state' dialog-path
                          (cond-> {:open? true
                                   :target target
                                   :cursor (if same? (fit-cursor state' target (cursor state)) [0 0])}
                            (and convert? (step-grid/became-zones (get-in fx [:params :zones]) n))
                            (assoc :every-laser (step-grid/became-zones (get-in fx [:params :zones]) n))
                            (and same? (not convert?) (get-in state (conj dialog-path :every-laser)))
                            (assoc :every-laser (get-in state (conj dialog-path :every-laser)))))}))))

(defn- handle-cursor
  "The cursor: to [row step] (a click), by :move [rows steps] (the arrows),
   or :to :first or :last (Home, End), inside the grid."
  [{:keys [target row step move to state]}]
  (let [target (or target (get-in state (conj dialog-path :target)))]
    (if (and (= target (get-in state (conj dialog-path :target))) (grid-params state target))
      (let [[r s] (cursor state)
            len (step-grid/length (grid-params state target))
            to-cell (cond
                      (sequential? move) [(+ r (long (first move))) (+ s (long (second move)))]
                      (= :first to) [r 0]
                      (= :last to) [r (dec len)]
                      (some? step) [(or (when (integer? row) row) r) step]
                      :else [r s])]
        {:state (set-cursor state target to-cell)})
      {:state state})))


;; The mouse (08 §6)

(defn pointer-phase
  "What the grid body's raw JavaFX event is: :press (the primary button
   down), :drag (moving with it down), :release, or nil. The body has no
   menu of its own, so a press that is also a popup trigger (a Ctrl-click
   on macOS, every headless robot press) paints too."
  [e]
  (when (instance? MouseEvent e)
    (let [^MouseEvent e e
          t (.getEventType e)]
      (cond
        (= t MouseEvent/MOUSE_PRESSED) (when (= MouseButton/PRIMARY (.getButton e)) :press)
        (= t MouseEvent/MOUSE_DRAGGED) (when (.isPrimaryButtonDown e) :drag)
        (= t MouseEvent/MOUSE_RELEASED) (when (= MouseButton/PRIMARY (.getButton e)) :release)
        :else nil))))

(defn- handle-pointer
  "The grid body's mouse (:fx/event), its cells cell-w × cell-h: a press on
   a cell starts painting it, a drag onto another cell paints on, a release
   ends a drag; anything else does nothing."
  [{:keys [target cell-w cell-h state] e :fx/event}]
  (let [phase (pointer-phase e)
        params (when (#{:press :drag} phase) (grid-params state target))
        at (when params
             (step-grid/cell-at (.getX ^MouseEvent e) (.getY ^MouseEvent e) cell-w cell-h
                                (count (step-grid/rows params)) (step-grid/length params)))
        drag (get-in state [:ui :step-grid :drag])]
    (case phase
      :press (if at
               {:dispatch {:event/type :step-grid/paint-start :target target :row (first at) :step (second at)}}
               {:state state})
      :drag (if (and at drag (= target (:target drag)) (not= at (:at drag)))
              {:dispatch {:event/type :step-grid/paint :target target :row (first at) :step (second at)}}
              {:state state})
      :release (if drag
                 {:dispatch {:event/type :step-grid/paint-end :target target}}
                 {:state state})
      {:state state})))

(defn- handle-paint-start
  "A press on [row step] (row an index): the history transaction a drag is
   (one undo step), and the cell painted with the opposite of its state,
   which the rest of the drag paints with too."
  [{:keys [target row step state time]}]
  (let [fx (pattern-at state target)
        params (when (and fx (not (keyframes-on? fx))) (grid-params state target))
        ref (when params (row-ref params row))]
    (if (and ref (integer? step) (< -1 step (step-grid/length params)))
      (let [on? (not (step-grid/lit? params ref step))
            begun (:state (history-handlers/handle-begin-transaction
                           {:label label
                            :source-id [:step-grid target]
                            :ui-context {:active-tab (get-in state [:ui :active-tab])
                                         :selected-cell (get-in state [:grid :selected-cell])}
                            :state state
                            :time time}))]
        {:state (-> begun
                    (edit target #(step-grid/paint % ref step on?))
                    (assoc-in [:ui :step-grid :drag] {:target target :on? on? :at [row step]})
                    (set-cursor target [row step]))
         :history/skip? true})
      {:state state})))

(defn- handle-paint
  "The drag onto [row step]: every cell from the last one to it painted
   with the press's value."
  [{:keys [target row step state]}]
  (let [{:keys [on? at] :as drag} (get-in state [:ui :step-grid :drag])
        params (grid-params state target)]
    (if (and drag (= target (:target drag)) params (integer? row) (integer? step))
      (let [rs (step-grid/rows params)
            cells (step-grid/line-cells at [row step])]
        {:state (-> state
                    (edit target (fn [p] (reduce (fn [p [r s]] (if-let [ref (get rs r)] (step-grid/paint p ref s on?) p)) p cells)))
                    (assoc-in [:ui :step-grid :drag :at] [row step])
                    (set-cursor target [row step]))})
      {:state state})))

(defn- handle-paint-end
  "The release: the drag's transaction committed, one undo step."
  [{:keys [state]}]
  (if (get-in state [:ui :step-grid :drag])
    {:state (-> (:state (history-handlers/handle-commit-transaction {:state state}))
                (update-in [:ui :step-grid] dissoc :drag))}
    {:state state}))


;; Copy and paste

(defn- handle-copy
  "The grid at target as a :step-grid clip in [:ui :clipboard], and on the
   OS clipboard (:clipboard/write): UI state, no edit."
  [{:keys [target state] :as event}]
  (if-let [params (grid-params state target)]
    (let [clip (clipboard/grid-clip (step-grid/copy params) (h/current-time-ms event))]
      {:state (assoc-in state [:ui :clipboard] clip)
       :clipboard/write clip})
    {:state state}))

(defn- handle-paste
  "The clipboard's grid into the grid at target (routing.step-grid/paste),
   one edit; a clip of another kind does nothing."
  [{:keys [target] :as event}]
  (h/with-clipboard event
    (fn [state clip]
      (when-let [grid (clipboard/payload clip :step-grid)]
        {:state (edit state target #(step-grid/paste % grid))}))))


;; Public API

(defn handle
  "Dispatch :step-grid/* events."
  [{:keys [event/type state] :as event}]
  (case type
    :step-grid/open (handle-open event)
    :step-grid/cursor (handle-cursor event)
    :step-grid/pointer (handle-pointer event)
    :step-grid/paint-start (handle-paint-start event)
    :step-grid/paint (handle-paint event)
    :step-grid/paint-end (handle-paint-end event)
    :step-grid/toggle (handle-edit event (fn [p e] (let [[ref step] (cell state p e)] (step-grid/toggle p ref step))))
    :step-grid/set-length (handle-edit event (fn [p {:keys [n]}] (step-grid/set-length p n)))
    :step-grid/add-row (handle-edit event (fn [p {:keys [ref]}] (step-grid/add-row p (or ref (step-grid/next-row p)))))
    :step-grid/remove-row (handle-edit event (fn [p {:keys [row]}] (step-grid/remove-row p (row-ref p row))))
    :step-grid/set-row (handle-edit event (fn [p {:keys [row ref]}] (step-grid/replace-row p (row-ref p row) ref)))
    :step-grid/move-row (handle-edit event (fn [p {:keys [row delta]}] (step-grid/move-row p (row-ref p row) delta)))
    :step-grid/fill-row (handle-edit event (fn [p {:keys [row every]}] (step-grid/fill-row p (row-ref p row) every)))
    :step-grid/clear-row (handle-edit event (fn [p {:keys [row]}] (step-grid/clear-row p (row-ref p row))))
    :step-grid/clear (handle-edit event (fn [p _] (step-grid/clear p)))
    :step-grid/clear-step (handle-edit event (fn [p e] (step-grid/clear-step p (second (cell state p e)))))
    :step-grid/fill-step (handle-edit event (fn [p e] (step-grid/fill-step p (second (cell state p e)))))
    :step-grid/rotate (handle-edit event (fn [p {:keys [by]}] (step-grid/rotate p by)))
    :step-grid/start-from (handle-edit event (fn [p {:keys [style]}]
                                               (if (some #{style} step-grid/start-styles)
                                                 (step-grid/from-style p style (step-grid/length p))
                                                 p)))
    :step-grid/copy (handle-copy event)
    :step-grid/paste (handle-paste event)
    {}))
