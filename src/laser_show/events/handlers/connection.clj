(ns laser-show.events.handlers.connection
  "Event handlers for multi-projector streaming (one engine per projector).

   - :idn/start-multi-streaming - streaming on (the operator's intent); queues a
     sync of the engines with the current projectors
   - :idn/stop-multi-streaming - streaming off; queues a stop
   - :output/status - what is actually streaming, published by the output
     runtime (backend/output_runtime.clj) at most 4 times a second

   The engines themselves live in the output runtime, never in app state.
   While streaming is on, projector edits that change an engine are synced by
   the output-sync middleware (handlers/output_sync.clj).")


(defn- handle-start-multi-streaming
  "Turn streaming on and queue a sync with the projectors as they are now.
   Pressing it again queues an identical sync, which changes nothing."
  [{:keys [state]}]
  {:state (assoc-in state [:backend :streaming :running?] true)
   :multi-engine/start {:projectors (:projectors state)}})

(defn- handle-stop-multi-streaming
  "Turn streaming off and queue a stop of every engine."
  [{:keys [state]}]
  {:state (assoc-in state [:backend :streaming :running?] false)
   :multi-engine/stop true})

(defn- handle-output-status
  "Store the output runtime's status (pure data; nil when nothing runs).
   Written by the :output/write-status effect at its path only, never as a
   whole-state write-back: without an app this event runs on the runtime's
   status thread, next to whatever the calling thread is doing."
  [{:keys [status]}]
  {:output/write-status status})


;; Public API


(defn handle
  "Dispatch streaming events (:idn/* and :output/*) to their handlers."
  [{:keys [event/type] :as event}]
  (case type
    :idn/start-multi-streaming (handle-start-multi-streaming event)
    :idn/stop-multi-streaming (handle-stop-multi-streaming event)
    :output/status (handle-output-status event)
    
    ;; Unknown event in this domain
    {}))
