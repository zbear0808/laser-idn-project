(ns laser-show.events.handlers.zone-patterns
  "Zone patterns and the pattern pads (zones plan Z5, 07 §6): :zone-patterns/*
   (a cue's pattern from its Pattern chip, a new pattern or Map cycle cell)
   and :pattern-pads/* (a pad's press, its timing, its key, and the STEP
   pad). Routed for \"zone-patterns\" and \"pattern-pads\"
   (events/handlers.clj).

   A cue keeps its patterns in :zone-patterns, a chain of one in v1, so the
   pattern window edits it with the chain, modulator and keyframe events on
   the :cue-zone-patterns domain (chain/helpers' chain-config).

   A pad's press is a performer's: it reads the show clock (the
   :show-clock/read effect hands it the beats), then switches the style of
   every pattern that follows the pads at once or on the next beat, or bar
   (routing.zone-patterns/schedule-style, the map pads' rule). It is live:
   never an undo step, never unsaved, never saved (Z5-Q3 A). The clock writes
   no app state: a due switch comes back as :show-clock/pattern-due
   (handlers/timing.clj). STEP presses the Zone step key, one more pulse
   source ([:playback :pulses :zone-step]), which the clock stamps at its
   next tick."
  (:require [laser-show.events.handlers.vibes :as vibes]
            [laser-show.events.helpers :as h]
            [laser-show.routing.zone-patterns :as zone-patterns])
  (:import [javafx.scene.input MouseEvent]))


;; A cue's pattern (07 §7.1)

(defn- style? [style] (some #{style} zone-patterns/styles))

(defn- handle-set-cue-pattern
  "The cue at [col row]'s zone pattern: a style on a cue without one adds
   its preset (on the cue's clock); on a cue with one it changes the style
   only, as a pad switches it, so the zones, the rate and the rest stay. nil
   takes it away. An empty cell, or a style that isn't one, changes
   nothing."
  [{:keys [col row style state]}]
  (let [path [:chains :cue-chains [col row]]
        cue (get-in state path)]
    (cond
      (nil? cue) {:state state}
      (nil? style) {:state (update-in state path dissoc :zone-patterns)}
      (not (style? style)) {:state state}
      (seq (:zone-patterns cue)) {:state (assoc-in state (conj path :zone-patterns 0 :params :style) style)}
      :else {:state (assoc-in state (conj path :zone-patterns)
                              [(h/ensure-item-fields (zone-patterns/preset-instance style :cue))])})))

(defn- handle-new-cell
  "An empty effects cell becomes a zone pattern of style (counting the
   show's beats), or a Map cycle (:map-cycle), and its editor opens. It
   starts off: a pattern moves pictures at once, unlike a tap reaction,
   which is identity at rest (:effects/new-reaction)."
  [{:keys [col row style state]}]
  (if (and (some? col) (some? row)
           (empty? (get-in state [:chains :effect-chains [col row] :items]))
           (or (= :map-cycle style) (style? style)))
    (let [fx (h/ensure-item-fields (if (= :map-cycle style)
                                     (zone-patterns/cycle-instance)
                                     (zone-patterns/preset-instance style :cell)))]
      {:state (-> state
                  (assoc-in [:chains :effect-chains [col row]] {:active? false :items [fx]})
                  (assoc-in [:effects :selected-cell] [col row]))
       :dispatch {:event/type :effect-chain/open-editor :col col :row row}})
    {:state state}))


;; The pattern pads (07 §7.3, spec §9)

(defn- pad? [pad] (some #{pad} zone-patterns/pad-ids))

(defn- handle-pointer
  "A pad's mouse or touch: its press presses the pad, waiting for the bar
   with Shift, and STEP's (:pad :step) presses the Zone step key; anything
   else does nothing (vibes/pointer-action)."
  [{:keys [pad fx/event state]}]
  (cond
    (not= :press (vibes/pointer-action event)) {:state state}
    (= :step pad) {:dispatch {:event/type :pattern-pads/step :src :pad}}
    :else {:dispatch {:event/type :pattern-pads/press :pad pad
                      :bar? (boolean (and (instance? MouseEvent event) (.isShiftDown ^MouseEvent event)))
                      :src :pad}}))

(defn- handle-press
  "A press of pad (one of zone-patterns/pad-ids), or of its learned key
   (:input-time, when it was played): the show clock's beats are read, then
   :pattern-pads/schedule. An unknown pad changes nothing."
  [{:keys [pad bar? input-time state]}]
  (if (pad? pad)
    {:show-clock/read {:on-read {:event/type :pattern-pads/schedule :pad pad :bar? (boolean bar?) :input-time input-time}}}
    {:state state}))

(defn- handle-schedule
  "The press, with the show clock read (:clock): the patterns' style at
   once, on the next beat, or on the next bar with Shift or the pad's
   setting ([:config :pattern-pads pad :bar?])."
  [{:keys [pad bar? input-time clock state]}]
  (if (pad? pad)
    {:state (zone-patterns/schedule-style state (zone-patterns/pad-style pad)
                                          (or (boolean bar?) (true? (get-in state [:config :pattern-pads pad :bar?])))
                                          (assoc clock :input-time input-time))}
    {:state state}))

(defn- handle-step
  "STEP, or its learned key: a press of the Zone step key, the next id of
   [:playback :pulses :zone-step], as a cue start pulses :cue; the show
   clock stamps it at its next tick, and the patterns that step on it step.
   Nothing during blackout, as no press pulses then."
  [{:keys [state]}]
  {:state (if (get-in state [:vibe-runtime :blackout?])
            state
            (update-in state [:playback :pulses :zone-step :id] (fnil inc 0)))})

(defn- handle-set-pad-bar
  "A pad's \"Switch on the bar\": its presses, and its keys', wait for the
   next bar."
  [{:keys [pad bar? state]}]
  (if (pad? pad)
    {:state (if bar?
              (assoc-in state [:config :pattern-pads pad :bar?] true)
              (update-in state [:config :pattern-pads] dissoc pad))}
    {:state state}))

(defn- pad-binding?
  "Is the trigger-map action a key of pad (:step: the Zone step key)?"
  [pad action]
  (if (= :step pad)
    (= :zone-step (:type action))
    (and (= :pattern-pad (:type action)) (= pad (:target action)))))

(defn- handle-clear-control
  "The keys learned for pad (a pattern pad, or :step) go."
  [{:keys [pad state]}]
  {:state (update-in state [:config :input :trigger-map]
                     #(into {} (remove (fn [[_ action]] (pad-binding? pad action))) %))})


;; Public API

(defn handle
  "Dispatch :zone-patterns/* and :pattern-pads/* events."
  [{:keys [event/type] :as event}]
  (case type
    :zone-patterns/set-cue-pattern (handle-set-cue-pattern event)
    :zone-patterns/new-cell (handle-new-cell event)
    :pattern-pads/pointer (handle-pointer event)
    :pattern-pads/press (handle-press event)
    :pattern-pads/schedule (handle-schedule event)
    :pattern-pads/step (handle-step event)
    :pattern-pads/set-pad-bar (handle-set-pad-bar event)
    :pattern-pads/clear-control (handle-clear-control event)
    {}))
