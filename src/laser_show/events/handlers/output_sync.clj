(ns laser-show.events.handlers.output-sync
  "Pipeline middleware: keeps the streaming engines in step with the projectors.

   After any event, while streaming is on, if what the engines are built from
   (output-runtime/wanted: the enabled projectors' host, port, service, output
   config and scan rate) differs between the event's state and the new state,
   the effects get :multi-engine/refresh {:projectors <new projectors>}. Every
   edit path is covered this way (settings, Add Manual, enable toggles, undo,
   New, Open) without each handler asking for it. Names and zone groups aren't
   part of it: the frame provider reads them live."
  (:require [laser-show.backend.output-runtime :as output-runtime]))

(defn sync-needed?
  "True when streaming is on in new-state and the engines built from the
   projectors of old-state and new-state differ."
  [old-state new-state]
  (let [old-projectors (:projectors old-state)
        new-projectors (:projectors new-state)]
    (boolean
     (and (get-in new-state [:backend :streaming :running?])
          (not (identical? old-projectors new-projectors))
          (not= (output-runtime/wanted old-projectors)
                (output-runtime/wanted new-projectors))))))

(defn wrap-output-sync
  "Middleware that adds :multi-engine/refresh when sync-needed?. It sits
   outside wrap-history and wrap-dirty, so it sees each event's final state."
  [handler]
  (fn [{:keys [state] :as event}]
    (let [effects (handler event)
          new-state (:state effects)]
      (if (and new-state (sync-needed? state new-state))
        (assoc effects :multi-engine/refresh {:projectors (:projectors new-state)})
        effects))))
