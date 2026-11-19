(ns laser-show.events.handlers.vibes
  "Event handlers for vibe mode's five roles (HIT, ACCENT, FLASH, VIBE,
   BLACKOUT): laser-show.vibes.engine plays them on [:vibe-runtime].
   
   Dispatched by trigger-map bindings {:type :vibe :role r} and
   {:type :vibe-select :vibe id} (handlers/triggers.clj), with the input's own
   time as :input-time, and by the Perform tab (views/tabs/perform.clj): its
   pads' pointer events (:vibe/pointer), the vibe strip (:vibe/select), a
   pad's menu (:vibe/clear-control) and the main window's focus
   (:vibe/window-focus)."
  (:require [laser-show.vibes.engine :as engine]
            [laser-show.vibes.library :as library])
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

(defn- handle-pointer
  "A pad's pointer event (:fx/event): a press is the role's :vibe/press from
   the pad; a release is its :vibe/release while the pad's press still holds
   the role, so it never lets go of a key's hold (a key pressed it since, or
   the press opened the menu)."
  [{:keys [state role] e :fx/event}]
  (case (pointer-action e)
    :press {:dispatch {:event/type :vibe/press :role role :src :pad}}
    :release (if (= :pad (get-in state [:vibe-runtime :held role :src]))
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
  "Unbind the controls of role (an undoable edit of the trigger map)."
  [{:keys [state role]}]
  {:state (update-in state [:config :input :trigger-map]
                     #(into {} (remove (fn [[_ action]] (library/binds-role? action role))) %))})


(defn handle
  "Dispatch vibe events to the engine.
   
   Accepts events with :event/type in the :vibe/* namespace."
  [{:keys [event/type state] :as event}]
  (case type
    :vibe/press (engine/press state event)
    :vibe/release (engine/release state event)
    :vibe/select (engine/select state event)
    :vibe/release-all (engine/release-all state event)
    :vibe/pointer (handle-pointer event)
    :vibe/window-focus (handle-window-focus event)
    :vibe/clear-control (handle-clear-control event)
    
    ;; Unknown event in this domain
    {}))
