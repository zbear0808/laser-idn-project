(ns laser-show.events.handlers.zone-maps
  "The zones, the show default and the zone maps (zones plan Z4, 06 §6):
   :zones/* (the show's default target, the zones' labels), :zone-maps/*
   (your maps: made, edited, deleted, selected, used now) and :zone-map/*
   (a switch: a pad's press, its timing, its key). Routed for \"zones\",
   \"zone-maps\" and \"zone-map\" (events/handlers.clj)."
  (:require [laser-show.routing.targets :as targets]))


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


;; Public API

(defn handle
  "Dispatch :zones/*, :zone-maps/* and :zone-map/* events."
  [{:keys [event/type] :as event}]
  (case type
    :zones/set-default-target (handle-set-default-target event)
    {}))
