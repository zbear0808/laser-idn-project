(ns laser-show.events.handlers.step-grid
  "The step grid's events (zones plan Z6, plans/2026-11-19-zones/08 §6),
   routed for \"step-grid\" (events/handlers.clj). A grid is a zone pattern
   (routing.step-grid). The window names the one it shows by its id, its
   target {:domain :entity-key :effect-instance-id}: the zone pattern with
   that id among a cue's patterns (:cue-zone-patterns) or an effects cell's
   items (:effect-chains). Every event finds it where it is now (locate):
   moved in its cell, or into a group, it is still the window's; once it is
   gone nothing takes its place, and the window's input does nothing. Each
   edit reads and writes the instance's :params there, so it works for both
   alike, and is one undo step, \"Edit Step Grid\"
   (events/handlers/history.clj); a drag is one too, a history transaction
   from press to release. While the pattern's keyframes are on, no edit
   changes it (08 invariant 9), and opening it makes no grid.

   :step-grid/open opens the window ([:ui :dialogs :step-grid-editor]) on a
   pattern and makes it a grid if it isn't one (routing.step-grid/as-grid);
   with :create-first? and a target naming only the cue (a cue's Pattern
   chip while it has none), it gives the cue a new grid first. While the
   show lock is on it only opens the window (events/show_lock.clj refuses
   the edits). The window's input names its target, and does nothing once
   the window shows another pattern or none (shown?). The cursor ([:ui
   :dialogs :step-grid-editor :cursor] [row step]) is UI state, never
   undone. Copy and Paste use the one clipboard (keyboard plan Phase 4,
   Step 4a): Copy puts a :step-grid clip in [:ui :clipboard] and its EDN on
   the OS clipboard; Paste pastes the clipboard's grid, reading the OS
   clipboard first while nothing is copied in this app (h/with-clipboard).

   :step-grid/pointer is the grid body's mouse: it reads the JavaFX event
   and dispatches :step-grid/paint-start (the press: begins the transaction
   and paints the cell with the opposite of its state, Z6-Q3 A), :paint
   (each cell the drag crosses, with that value) and :paint-end (the
   release: commits). The lock refuses the first two; a release always lets
   go of its own drag.

   The window's parameter controls (Rate, Step on, Restart on, Offset,
   Moves, Follows, their modulators) are the pattern window's, :chain/* and
   :modulator/* events: they send the target too, and the pipeline finds
   the pattern for them (wrap-step-grid-control-target)."
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

(defn- address
  "A target as the window keeps it, {:domain :entity-key
   :effect-instance-id}; an event's own keys left out."
  [target]
  (select-keys target [:domain :entity-key :effect-instance-id]))

(defn locate
  "target's zone pattern where it is now, among its cell's
   (chain-helpers/zone-pattern-items-path, routing.step-grid/locate-pattern):
   {:effect-path (among them) :path (in app state) :instance}, or nil when
   they hold no zone pattern with its id. A path is good for the event that
   found it only."
  [state {:keys [domain entity-key effect-instance-id]}]
  (when-let [items-path (chain-helpers/zone-pattern-items-path domain entity-key)]
    (when-let [found (step-grid/locate-pattern (get-in state items-path) effect-instance-id)]
      (assoc found :path (into items-path (:effect-path found))))))

(defn- pattern-at
  "target's zone pattern, or nil."
  [state target]
  (:instance (locate state target)))

(defn shown?
  "Is target the one the open window shows? Input naming another does
   nothing."
  [state target]
  (let [{:keys [open?] shown :target} (get-in state dialog-path)]
    (and (true? open?) (map? target) (= shown (address target)))))

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
   nil when its pattern isn't there."
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
  (if (= (address target) (get-in state (conj dialog-path :target)))
    (assoc-in state (conj dialog-path :cursor) (fit-cursor state target cell))
    state))

(defn- cursor [state] (get-in state (conj dialog-path :cursor) [0 0]))


;; Edits

(defn- edit
  "state with (f params) as target's params, f given the grid as it shows
   (a pattern of another style becomes one first): unchanged while its
   keyframes are on, once its pattern isn't there, and when f changes
   nothing. The cursor stays inside the grid."
  [state target f]
  (let [{fx :instance :keys [path]} (locate state target)]
    (if (or (nil? fx) (keyframes-on? fx))
      state
      (let [params (step-grid/as-grid (:params fx) (laser-count state))
            edited (step-grid/normalize (f params))]
        (if (= edited (:params fx))
          state
          (-> state
              (assoc-in (conj path :params) edited)
              (set-cursor target (cursor state))))))))

(defn- handle-edit
  "An event of the open window whose edit is (f params event)."
  [{:keys [target state] :as event} f]
  {:state (if (shown? state target)
            (edit state target #(f % event))
            state)})

(defn- cell
  "The [ref step] an event names: its :row (a ref or an index) and :step,
   else the cursor's."
  [state params {:keys [row step]}]
  (if (some? step)
    [(row-ref params row) step]
    (let [[r s] (cursor state)]
      [(row-ref params r) s])))


;; Opening the window

(defn- with-first-pattern
  "{:state :target}: state with a new grid as the first pattern of the cue
   target names, 8 steps at 2 a beat over A and B (Z6-Q1 A), and the target
   naming that item; nil unless target names a cue only (no
   :effect-instance-id) and the cue is there, unlocked, with no pattern."
  [state {:keys [domain entity-key effect-instance-id]}]
  (let [items-path (chain-helpers/zone-pattern-items-path domain entity-key)]
    (when (and (= :cue-zone-patterns domain)
               items-path
               (nil? effect-instance-id)
               (not (show-lock/locked? state))
               (some? (get-in state [:chains :cue-chains entity-key]))
               (empty? (get-in state items-path)))
      (let [fx (h/ensure-item-fields (zone-patterns/preset-instance :steps :cue))]
        {:state (assoc-in state items-path [fx])
         :target {:domain domain :entity-key entity-key :effect-instance-id (:id fx)}}))))

(defn- handle-open
  "The step grid window on target's pattern: made a grid (as-grid, its
   Every laser as zones, which the window names), unless the show lock is
   on or its keyframes are; the cursor at the top left, as it was on the
   same grid. With :create-first? (a cue's Pattern chip, while the cue has
   none) target names the cue only, and the cue gets a new grid first
   (with-first-pattern). A pattern that isn't there, or a cue that has one
   by then, opens nothing: no other pattern takes its place."
  [{:keys [target create-first? state]}]
  (let [created (when create-first? (with-first-pattern state target))
        target (if created (:target created) (address target))
        state' (if created (:state created) state)
        found (when (or created (not create-first?)) (locate state' target))
        fx (:instance found)]
    (if (nil? fx)
      {:state state}
      (let [locked? (show-lock/locked? state)
            convert? (and (not locked?) (not (keyframes-on? fx)) (not (step-grid/grid? (:params fx))))
            n (laser-count state)
            same? (= target (get-in state (conj dialog-path :target)))
            state' (cond-> state'
                     convert? (assoc-in (conj (:path found) :params) (step-grid/as-grid (:params fx) n)))]
        {:state (assoc-in state' dialog-path
                          (cond-> {:open? true
                                   :target target
                                   :cursor (if same? (fit-cursor state' target (cursor state)) [0 0])}
                            (and convert? (step-grid/became-zones (get-in fx [:params :zones]) n))
                            (assoc :every-laser (step-grid/became-zones (get-in fx [:params :zones]) n))
                            (and same? (not convert?) (get-in state (conj dialog-path :every-laser)))
                            (assoc :every-laser (get-in state (conj dialog-path :every-laser)))))}))))

(defn- handle-cursor
  "The cursor of the open window's grid: to [row step] (a click), by :move
   [rows steps] (the arrows), or :to :first or :last (Home, End), inside
   the grid."
  [{:keys [target row step move to state]}]
  (if-let [params (when (shown? state target) (grid-params state target))]
    (let [[r s] (cursor state)
          len (step-grid/length params)
          to-cell (cond
                    (sequential? move) [(+ r (long (first move))) (+ s (long (second move)))]
                    (= :first to) [r 0]
                    (= :last to) [r (dec len)]
                    (some? step) [(or (when (integer? row) row) r) step]
                    :else [r s])]
      {:state (set-cursor state target to-cell)})
    {:state state}))


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
   a cell of the open window's grid starts painting it, a drag onto another
   cell paints on, a release ends a drag; anything else does nothing."
  [{:keys [target cell-w cell-h state] e :fx/event}]
  (let [phase (pointer-phase e)
        params (when (and (#{:press :drag} phase) (shown? state target)) (grid-params state target))
        at (when params
             (step-grid/cell-at (.getX ^MouseEvent e) (.getY ^MouseEvent e) cell-w cell-h
                                (count (step-grid/rows params)) (step-grid/length params)))
        drag (get-in state [:ui :step-grid :drag])]
    (case phase
      :press (if at
               {:dispatch {:event/type :step-grid/paint-start :target target :row (first at) :step (second at)}}
               {:state state})
      :drag (if (and at drag (= (address target) (:target drag)) (not= at (:at drag)))
              {:dispatch {:event/type :step-grid/paint :target target :row (first at) :step (second at)}}
              {:state state})
      :release (if drag
                 {:dispatch {:event/type :step-grid/paint-end :target target}}
                 {:state state})
      {:state state})))

(defn- handle-paint-start
  "A press on [row step] (row an index) of the open window's grid: the
   history transaction a drag is (one undo step), and the cell painted with
   the opposite of its state, which the rest of the drag paints with too."
  [{:keys [target row step state time]}]
  (let [fx (when (shown? state target) (pattern-at state target))
        params (when (and fx (not (keyframes-on? fx))) (grid-params state target))
        ref (when params (row-ref params row))]
    (if (and ref (integer? step) (< -1 step (step-grid/length params)))
      (let [target (address target)
            on? (not (step-grid/lit? params ref step))
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
   with the press's value, while the drag's pattern is still the window's
   and there."
  [{:keys [target row step state]}]
  (let [{:keys [on? at] :as drag} (get-in state [:ui :step-grid :drag])
        params (when (and drag (= (address target) (:target drag)) (shown? state target))
                 (grid-params state target))]
    (if (and params (integer? row) (integer? step))
      (let [rs (step-grid/rows params)
            cells (step-grid/line-cells at [row step])]
        {:state (-> state
                    (edit target (fn [p] (reduce (fn [p [r s]] (if-let [ref (get rs r)] (step-grid/paint p ref s on?) p)) p cells)))
                    (assoc-in [:ui :step-grid :drag :at] [row step])
                    (set-cursor target [row step]))})
      {:state state})))

(defn- handle-paint-end
  "The release of the drag on target: the transaction its press began
   committed, one undo step, even once its pattern is gone or the lock came
   on. A release for another target lets go of nothing."
  [{:keys [target state]}]
  (let [drag (get-in state [:ui :step-grid :drag])
        target (address target)]
    (if (and drag (= target (:target drag)))
      {:state (-> (if (= [:step-grid target] (get-in state [:history :transaction :source-id]))
                    (:state (history-handlers/handle-commit-transaction {:state state}))
                    state)
                  (update-in [:ui :step-grid] dissoc :drag))}
      {:state state})))


;; Copy and paste

(defn- handle-copy
  "The open window's grid as a :step-grid clip in [:ui :clipboard], and on
   the OS clipboard (:clipboard/write): UI state, no edit."
  [{:keys [target state] :as event}]
  (if-let [params (when (shown? state target) (grid-params state target))]
    (let [clip (clipboard/grid-clip (step-grid/copy params) (h/current-time-ms event))]
      {:state (assoc-in state [:ui :clipboard] clip)
       :clipboard/write clip})
    {:state state}))

(defn- handle-paste
  "The clipboard's grid into the open window's grid (routing.step-grid/paste),
   one edit; a clip of another kind does nothing. The target is checked
   before the OS clipboard is asked (h/with-clipboard) and again when its
   read comes back, before the clip is taken: once the window shows another
   pattern, or its pattern is gone, the paste does nothing, and the clip
   isn't kept."
  [{:keys [target state] :as event}]
  (if (and (shown? state target) (pattern-at state target))
    (h/with-clipboard event
      (fn [state clip]
        (when-let [grid (clipboard/payload clip :step-grid)]
          {:state (edit state target #(step-grid/paste % grid))})))
    {:state state}))


;; The window's parameter controls

(def ^:private control-namespaces
  "The events of the parameter controls the window shares with the pattern
   window."
  #{"chain" "modulator"})

(defn- control-event?
  "Is event from the window's parameter controls? They say so with
   :dialog-id :step-grid-editor."
  [{:keys [event/type dialog-id]}]
  (and (= :step-grid-editor dialog-id)
       (keyword? type)
       (contains? control-namespaces (namespace type))))

(defn- keep-target
  "effects, with the parameter write they continue with (their :dispatch: a
   :modulator/* event's :chain/update-param on the same pattern) tagged with
   event's target and window too, so the pipeline checks it as it checked
   event."
  [effects {:keys [domain entity-key effect-instance-id dialog-id]} effect-path]
  (let [next-event (:dispatch effects)]
    (if (and (map? next-event)
             (contains? control-namespaces (some-> (:event/type next-event) namespace))
             (= [domain entity-key effect-path] [(:domain next-event) (:entity-key next-event) (:effect-path next-event)])
             (not (contains? next-event :dialog-id)))
      (update effects :dispatch assoc :effect-instance-id effect-instance-id :dialog-id dialog-id)
      effects)))

(defn wrap-step-grid-control-target
  "Pipeline step just outside the show lock (events/core.clj). The window's
   parameter controls (control-event?) send its target, {:domain
   :entity-key :effect-instance-id}, not a place. While the open window
   shows that target, and its pattern is there with its keyframes off, the
   event goes on with :effect-path where the pattern is now, so the lock
   classifies the pattern itself; the write it continues with keeps the
   target (keep-target). Otherwise it does nothing ({}). Every other event
   goes through as it is."
  [handler]
  (fn [{:keys [state] :as event}]
    (if-not (control-event? event)
      (handler event)
      (let [target (address event)
            {fx :instance :keys [effect-path]} (when (shown? state target) (locate state target))]
        (if (and fx (not (keyframes-on? fx)))
          (keep-target (handler (assoc event :effect-path effect-path)) event effect-path)
          {})))))


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
