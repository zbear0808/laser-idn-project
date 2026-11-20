(ns laser-show.events.handlers.rig
  "The rig strip's events (plans/2026-11-19-zones/04 §6): the order of the
   projectors, the middle laser, the split width, and the bar that shows
   where a dragged card would land. Pure. A move writes the whole effective
   order, so the first one fixes every card's place. None of them restarts
   an engine: the frame provider reads the rig live."
  (:require [laser-show.routing.rig :as rig]))

(defn- order [state]
  (rig/effective-order (get-in state [:rig :order]) (:projectors state)))

(defn- with-order
  "state with the rig order ids, or as it is when that is the order it has."
  [state ids]
  (if (= ids (order state))
    state
    (assoc-in state [:rig :order] ids)))

(defn- handle-move-projector
  "Put projector-id before or after (:side :before or :after) the card of
   target, as a drop on a card's left or right half does. Nothing for an
   unknown card or target, a card on itself or another side. The drop bar
   goes either way."
  [{:keys [projector-id target side state]}]
  (let [ids (order state)
        state (assoc-in state [:rig-ui :drop] nil)]
    (if (and (some #{projector-id} ids) (some #{target} ids) (not= projector-id target) (#{:before :after} side))
      (let [others (filterv #(not= projector-id %) ids)
            at (cond-> (.indexOf ^java.util.List others target) (= :after side) inc)]
        {:state (with-order state (into (conj (subvec others 0 at) projector-id) (subvec others at)))})
      {:state state})))

(defn- handle-nudge-projector
  "Move projector-id one place left (a negative :delta) or right (a positive
   one), as ◀ ▶ and Alt+← / Alt+→ do; nothing at the ends."
  [{:keys [projector-id delta state]}]
  (let [ids (order state)
        i (.indexOf ^java.util.List ids projector-id)
        j (when (and (number? delta) (not (zero? delta))) (+ i (if (neg? delta) -1 1)))]
    (if (and (>= i 0) j (< -1 j (count ids)))
      {:state (assoc-in state [:rig :order] (-> ids (assoc i (ids j)) (assoc j projector-id)))}
      {:state state})))

(defn- handle-set-middle
  "How the middle laser of 3, 5, 7… joins Left and Right: one of
   rig/middle-policies; anything else is ignored."
  [{:keys [middle state]}]
  (if (contains? rig/middle-policies middle)
    {:state (assoc-in state [:rig :middle] middle)}
    {:state state}))

(defn- handle-set-split-width
  "The split width, clamped to 0.5-1.0. A slider's drag is one undo step."
  [{:keys [split-width state]}]
  (if (and (number? split-width) (not (Double/isNaN (double split-width))))
    {:state (assoc-in state [:rig :split-width] (max 0.5 (min 1.0 (double split-width))))
     :history/coalesce-key [:rig :split-width]}
    {:state state}))

(defn- handle-drag-over
  "A dragged card is over the card of projector-id, on its :side: the bar
   shows there."
  [{:keys [projector-id side state]}]
  (let [drop {:projector-id projector-id :side side}]
    (if (= drop (get-in state [:rig-ui :drop]))
      {:state state}
      {:state (assoc-in state [:rig-ui :drop] drop)})))

(defn- handle-drag-exit
  "The dragged card left a card: no bar."
  [{:keys [state]}]
  (if (get-in state [:rig-ui :drop])
    {:state (assoc-in state [:rig-ui :drop] nil)}
    {:state state}))

(defn handle
  "Dispatch :rig/* events."
  [{:keys [event/type] :as event}]
  (case type
    :rig/move-projector (handle-move-projector event)
    :rig/nudge-projector (handle-nudge-projector event)
    :rig/set-middle (handle-set-middle event)
    :rig/set-split-width (handle-set-split-width event)
    :rig/drag-over (handle-drag-over event)
    :rig/drag-exit (handle-drag-exit event)
    {}))
