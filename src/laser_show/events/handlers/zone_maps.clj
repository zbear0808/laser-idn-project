(ns laser-show.events.handlers.zone-maps
  "The zones, the show default and the zone maps (zones plan Z4, 06 §6):
   :zones/* (the show's default target, the zones' labels), :zone-maps/*
   (your maps: made, edited, deleted, selected, used now) and :zone-map/*
   (a switch: a pad's press, its timing, its key). Routed for \"zones\",
   \"zone-maps\" and \"zone-map\" (events/handlers.clj).

   A switch is a performer's: a pad's press or a learned key's reads the
   show clock (the :show-clock/read effect hands it the beats), then lands
   at once or waits for the beat or the bar (routing.zone-maps/schedule).
   It is live: never an undo step, never unsaved (06 §5). Nothing here reads
   the clock itself, and the clock writes no app state: a due switch comes
   back as :show-clock/zone-map-due (handlers/timing.clj)."
  (:require [laser-show.events.handlers.vibes :as vibes]
            [laser-show.routing.targets :as targets]
            [laser-show.routing.zone-maps :as zone-maps])
  (:import [javafx.scene.input MouseEvent]))


;; The show default

(defn- chosen-target
  "The target an event sets: its :target, else a picker's choice (the :id of
   its :fx/event), :inherit meaning none; in normal form."
  [{:keys [fx/event] :as e}]
  (let [t (if (contains? e :target) (:target e) (:id event))]
    (when-not (= :inherit t)
      (targets/normalize-target t))))

(defn- handle-set-default-target
  "Set the show's default target, [:config :default-target]: what a cue
   with none of its own, in a column with none, draws to. None is All."
  [{:keys [state] :as event}]
  {:state (assoc-in state [:config :default-target] (chosen-target event))})


;; A switch

(defn- handle-pointer
  "A pad's mouse or touch: its press presses the map, waiting for the bar
   with Shift; anything else does nothing (vibes/pointer-action)."
  [{:keys [map fx/event state]}]
  (if (= :press (vibes/pointer-action event))
    {:dispatch {:event/type :zone-map/press :map map
                :bar? (boolean (and (instance? MouseEvent event) (.isShiftDown ^MouseEvent event)))
                :src :pad}}
    {:state state}))

(defn- handle-press
  "A press of map's pad, or of its learned key (:input-time, when it was
   played): the show clock's beats are read, then :zone-map/schedule. An
   unknown map changes nothing."
  [{:keys [map bar? input-time state]}]
  (if (zone-maps/map-by-id state map)
    {:show-clock/read {:on-read {:event/type :zone-map/schedule :map map :bar? (boolean bar?) :input-time input-time}}}
    {:state state}))

(defn- handle-schedule
  "The press, with the show clock read (:clock): at once, on the next beat,
   or on the next bar with Shift or the pad's setting
   ([:config :zone-map-pads map :bar?]) (routing.zone-maps/schedule)."
  [{:keys [map bar? input-time clock state]}]
  (if (zone-maps/map-by-id state map)
    {:state (zone-maps/schedule state map
                                (or (boolean bar?) (true? (get-in state [:config :zone-map-pads map :bar?])))
                                (assoc clock :input-time input-time))}
    {:state state}))

(defn- handle-use-now
  "The map editor's Use now: an edit, not a performance, so at once (spec
   §9). An unknown map changes nothing."
  [{:keys [map state]}]
  {:state (if (zone-maps/map-by-id state map) (zone-maps/commit state map) state)})

(defn- handle-set-pad-bar
  "A pad's \"Switch on the bar\": its presses, and its keys', wait for the
   next bar."
  [{:keys [map bar? state]}]
  (if map
    {:state (if bar?
              (assoc-in state [:config :zone-map-pads map :bar?] true)
              (update-in state [:config :zone-map-pads] dissoc map))}
    {:state state}))

(defn- handle-clear-control
  "The keys learned for map's pad go."
  [{:keys [map state]}]
  {:state (update-in state [:config :input :trigger-map]
                     #(into {} (remove (fn [[_ action]] (and (= :zone-map (:type action)) (= map (:target action))))) %))})


;; Public API

(defn handle
  "Dispatch :zones/*, :zone-maps/* and :zone-map/* events."
  [{:keys [event/type] :as event}]
  (case type
    :zones/set-default-target (handle-set-default-target event)
    :zone-maps/use-now (handle-use-now event)
    :zone-map/pointer (handle-pointer event)
    :zone-map/press (handle-press event)
    :zone-map/schedule (handle-schedule event)
    :zone-map/set-pad-bar (handle-set-pad-bar event)
    :zone-map/clear-control (handle-clear-control event)
    {}))
