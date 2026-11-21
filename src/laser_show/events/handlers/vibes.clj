(ns laser-show.events.handlers.vibes
  "Event handlers for vibe mode's five roles (HIT, ACCENT, FLASH, VIBE,
   BLACKOUT): laser-show.vibes.engine plays them on [:vibe-runtime].
   
   Dispatched by trigger-map bindings {:type :vibe :role r} and
   {:type :vibe-select :vibe id} (handlers/triggers.clj), with the input's own
   time as :input-time, and by the Perform tab (views/tabs/perform.clj): its
   pads' pointer events (:vibe/pointer), the vibe strip (:vibe/select), a
   pad's menu (:vibe/clear-control) and the main window's focus
   (:vibe/window-focus). :vibe/add-reaction makes a tap reaction for a vibe
   (vibes.reactions/add-to-vibe), an undoable edit.

   The Vibes tab (views/tabs/vibes.clj) edits the vibes (laser-show.vibes
   .library) with :vibe/add, :duplicate, :remove, :move, :rename,
   :set-color, :set-energy, :toggle-cell, :toggle-fx, :set-option and
   :make-from-rows, each one undo step, and what plays follows the edit at
   once (engine/reconcile). :vibe/select-for-edit and :set-slot are its
   selection, :learn-all and :wizard-cancel its Learn all (handlers/input.clj
   chains the learns): UI state, never undone.

   Its Controls panel's faders (plans/2026-11-18-vibe-mode/05):
   :vibe/add-master and :vibe/add-morph make their effect cells
   (vibes.faders), and :vibe/clear-control with a :slot unbinds a fader.

   :vibe/set-show-lock is the Perform tab's show lock (events/show_lock.clj),
   and :vibe/release with :reason :watchdog the show clock's report of a
   stuck key (vibes.watchdog)."
  (:require [laser-show.events.helpers :as h]
            [laser-show.vibes.engine :as engine]
            [laser-show.vibes.faders :as faders]
            [laser-show.vibes.library :as library]
            [laser-show.vibes.reactions :as reactions])
  (:import [javafx.scene.input MouseButton MouseEvent TouchEvent]))


;; The pads

(defn pointer-action
  "What a pad's raw JavaFX event does: :press, :release or nil (nothing).
   One finger is one press and one release: a touch counts through its own
   touch point's events (JavaFX sends each point's events to the pad it went
   down on, typed by that point's state), so the mouse events JavaFX makes
   from a touch (synthesized) are left out. A mouse counts with its primary
   button only: the secondary one opens the pad's menu, as does a Ctrl-click
   on macOS, whose press is a popup trigger."
  [e]
  (condp instance? e
    MouseEvent (let [^MouseEvent e e]
                 (when (and (not (.isSynthesized e)) (= MouseButton/PRIMARY (.getButton e)))
                   (condp = (.getEventType e)
                     MouseEvent/MOUSE_PRESSED (when-not (.isPopupTrigger e) :press)
                     MouseEvent/MOUSE_RELEASED :release
                     nil)))
    TouchEvent (condp = (.getEventType ^TouchEvent e)
                 TouchEvent/TOUCH_PRESSED :press
                 TouchEvent/TOUCH_RELEASED :release
                 nil)
    nil))

(defn- pad-holds?
  "Does a pad's press hold role: the engine's hold, or, when the press played
   nothing (the engine holds nothing), its pulse's?"
  [state role]
  (if-let [held (get-in state [:vibe-runtime :held role])]
    (= :pad (:src held))
    (= :pad (get-in state [:playback :pulses role :src]))))

(defn- handle-pointer
  "A pad's pointer event (:fx/event): a press is the role's :vibe/press from
   the pad; a release is its :vibe/release while the pad's press still holds
   the role, so it never lets go of a key's hold (a key pressed it since, or
   the press opened the menu)."
  [{:keys [state role] e :fx/event}]
  (case (pointer-action e)
    :press {:dispatch {:event/type :vibe/press :role role :src :pad}}
    :release (if (pad-holds? state role)
               {:dispatch {:event/type :vibe/release :role role :src :pad}}
               {})
    {}))

(defn- handle-window-focus
  "The main window lost focus (:fx/event false): a pad's release may never
   come, so every pad's hold ends. A key's hold stays: its release still
   comes."
  [{focused? :fx/event}]
  (if (= false focused?)
    {:dispatch {:event/type :vibe/release-all :only :pad}}
    {}))

(defn- handle-clear-control
  "Unbind the controls of role, or the control learned for the fader slot
   (an undoable edit of the trigger map or the macro map; nothing to unbind
   changes nothing). A key that holds the role lets go of it now: its
   release would reach nothing (engine/release-unbound)."
  [{:keys [state role slot] :as event}]
  (if slot
    (let [m (get-in state [:config :input :macro-map])
          kept (into {} (remove (fn [[_ s]] (= slot s))) m)]
      {:state (cond-> state (not= (count m) (count kept)) (assoc-in [:config :input :macro-map] kept))})
    (let [was (get-in state [:config :input :trigger-map])]
      {:state (-> state
                  (assoc-in [:config :input :trigger-map]
                            (into {} (remove (fn [[_ action]] (library/binds-role? action role))) was))
                  (engine/release-unbound was (h/current-time-ms event)))})))

(defn- handle-set-show-lock
  "The Perform tab's show lock switch (events/show_lock.clj), for the
   session: unlocking also clears the notice of what it refused."
  [{:keys [state locked?]}]
  {:state (cond-> (assoc-in state [:ui :show-lock?] (boolean locked?))
            (not locked?) (assoc-in [:ui :show-lock-notice] nil))})

(defn- handle-add-morph
  "A Morph of kind for the vibe (vibes.faders/add-morph), switched on at once
   if the vibe plays, as any edit of its FX set is (engine/reconcile)."
  [{:keys [state vibe kind] :as event}]
  {:state (engine/reconcile (faders/add-morph state vibe kind) (get-in state [:config :vibes]) (h/current-time-ms event))})


;; The Vibes tab: edits (undoable; an edit with nothing to do, such as an
;; unknown vibe, a bad value or no arguments, leaves state as it is)

(defn- edit
  "state with f applied to the vibes ([:config :vibes]), and what plays
   following the edit (engine/reconcile)."
  [{:keys [state] :as event} f & args]
  (let [was (get-in state [:config :vibes])
        vibes (apply f was args)]
    (if (= was vibes)
      state
      (engine/reconcile (assoc-in state [:config :vibes] vibes) was (h/current-time-ms event)))))

(defn- handle-add
  "An edit that makes vibes (Add, Duplicate, Make from rows): the first new
   one is selected."
  [{:keys [state] :as event} f & args]
  (let [state' (apply edit event f args)
        new-id (first (remove (set (get-in state [:config :vibes :order])) (get-in state' [:config :vibes :order])))]
    {:state (cond-> state' new-id (assoc-in [:ui :vibes :selected] new-id))}))

(defn- handle-remove
  "Delete a vibe. When it was the one edited, its neighbour is next."
  [{:keys [state vibe] :as event}]
  (let [order (vec (get-in state [:config :vibes :order]))
        i (first (keep-indexed #(when (= vibe %2) %1) order))
        state' (edit event library/remove-vibe vibe)]
    {:state (cond-> state'
              (and i (= vibe (library/edited-id state)))
              (assoc-in [:ui :vibes :selected] (or (get order (inc i)) (get order (dec i)))))}))

(defn- event-cell
  "The cell an event names: :cell, or the :col and :row a grid cell's click
   gives (views/components/grid_cell.clj)."
  [{:keys [cell col row]}]
  (or cell (when (and col row) [col row])))

(defn- handle-toggle
  "A click on the picker: the cell in or out of the vibe's slot (f:
   library/toggle-cell on the cue grid, toggle-fx on the effects grid). An
   empty cell isn't put in: it has nothing to play."
  [{:keys [state vibe slot] :as event} f grid]
  (let [cell (event-cell event)]
    {:state (if (or (library/in-slot? (get-in state [:config :vibes]) vibe slot cell)
                    (seq (get-in state [:chains grid cell :items])))
              (edit event f vibe slot cell)
              state)}))

(defn- handle-edit [event f & args]
  {:state (apply edit event f args)})

(defn- handle-add-reaction
  "A tap reaction made for a vibe (reactions/add-to-vibe; a zone reaction's
   places keep clear of the audience groups), which what plays follows as it
   does every edit of the vibes (engine/reconcile): on at once in the FX set
   of the vibe that plays."
  [{:keys [state vibe kind] :as event}]
  (let [was (get-in state [:config :vibes])
        state' (reactions/add-to-vibe state vibe kind (library/audience-groups state))]
    {:state (if (= was (get-in state' [:config :vibes]))
              state
              (engine/reconcile state' was (h/current-time-ms event)))}))


;; The Vibes tab: its selection and Learn all (UI state, never undone)

(defn- handle-select-for-edit [{:keys [state vibe]}]
  {:state (cond-> state (library/vibe state vibe) (assoc-in [:ui :vibes :selected] vibe))})

(defn- handle-set-slot [{:keys [state slot]}]
  {:state (cond-> state (some #{slot} library/slots) (assoc-in [:ui :vibes :slot] slot))})

(defn- handle-learn-all
  "Learn the five controls in a row: the first role's learn now, each next
   one when the one before it captured its key (handlers/input.clj)."
  [{:keys [state]}]
  {:state (assoc-in state [:ui :vibes :wizard] {:queue library/roles :done []})
   :dispatch {:event/type :input/start-learn :target-action {:type :vibe :role (first library/roles)}}})

(defn- handle-wizard-cancel
  "Stop Learn all, and the learn it waits on."
  [{:keys [state]}]
  {:state (assoc-in state [:ui :vibes :wizard] nil)
   :dispatch {:event/type :input/cancel-learn}})


(defn handle
  "Dispatch vibe events to the engine.
   
   Accepts events with :event/type in the :vibe/* namespace."
  [{:keys [event/type state vibe] :as event}]
  (case type
    :vibe/press (engine/press state event)
    :vibe/release (engine/release state event)
    :vibe/select (engine/select state event)
    :vibe/release-all (engine/release-all state event)

    ;; Auto-advance (vibes.rotation): the show clock's reports, the Perform
    ;; tab's AUTO switch
    :vibe/arm (engine/arm state event)
    :vibe/advance (engine/advance state event)
    :vibe/set-auto (engine/set-auto state event)

    ;; The show lock (events/show_lock.clj): the Perform tab's switch
    :vibe/set-show-lock (handle-set-show-lock event)

    :vibe/pointer (handle-pointer event)
    :vibe/window-focus (handle-window-focus event)
    :vibe/clear-control (handle-clear-control event)
    :vibe/add-master {:state (faders/add-master state)}
    :vibe/add-morph (handle-add-morph event)

    ;; The Vibes tab
    :vibe/add (handle-add event library/add-vibe)
    :vibe/duplicate (handle-add event library/duplicate-vibe vibe)
    :vibe/make-from-rows (handle-add event library/make-from-rows
                                     (get-in state [:chains :cue-chains]) (get-in state [:grid :size]))
    :vibe/remove (handle-remove event)
    :vibe/move (handle-edit event library/move-vibe vibe (:to event))
    :vibe/rename (handle-edit event library/rename-vibe vibe (:name event))
    :vibe/set-color (handle-edit event library/set-color vibe (:color event))
    :vibe/set-energy (handle-edit event library/set-energy vibe (:energy event))
    :vibe/toggle-cell (handle-toggle event library/toggle-cell :cue-chains)
    :vibe/toggle-fx (handle-toggle event library/toggle-fx :effect-chains)
    :vibe/set-option (handle-edit event library/set-role-option vibe (:role event) (:key event) (:value event))
    :vibe/select-for-edit (handle-select-for-edit event)
    :vibe/set-slot (handle-set-slot event)
    :vibe/learn-all (handle-learn-all event)
    :vibe/wizard-cancel (handle-wizard-cancel event)

    ;; Tap reactions
    :vibe/add-reaction (handle-add-reaction event)
    
    ;; Unknown event in this domain
    {}))
