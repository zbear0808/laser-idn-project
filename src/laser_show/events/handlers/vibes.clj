(ns laser-show.events.handlers.vibes
  "Event handlers for vibe mode's five roles (HIT, ACCENT, FLASH, VIBE,
   BLACKOUT): laser-show.vibes.engine plays them on [:vibe-runtime].
   
   Dispatched by trigger-map bindings {:type :vibe :role r} and
   {:type :vibe-select :vibe id} (handlers/triggers.clj), with the input's own
   time as :input-time."
  (:require [laser-show.vibes.engine :as engine]))

(defn handle
  "Dispatch vibe events to the engine.
   
   Accepts events with :event/type in the :vibe/* namespace."
  [{:keys [event/type state] :as event}]
  (case type
    :vibe/press (engine/press state event)
    :vibe/release (engine/release state event)
    :vibe/select (engine/select state event)
    :vibe/release-all (engine/release-all state event)
    
    ;; Unknown event in this domain
    {}))
