(ns laser-show.events.handlers.dev
  "Tooling events, for tests and the REPL (plans/2026-11-20-state-integrity/
   02-direct-state-writes.md §4). Pure.

   - :dev/flush {:promise p}: events/flush!'s marker; its effect delivers p
     once every event queued before it has been handled
   - :dev/write-state {:f :args}: events/write-state!'s write, done by the
     :dev/swap-state effect on the event thread. It returns no :state, so no
     middleware sees it: no undo step, no unsaved flag, no output sync, no
     rig write")

(defn handle
  "Route a :dev/* event to its effects; {} for any other."
  [{:keys [event/type] :as event}]
  (case type
    :dev/flush {:dev/deliver (:promise event)}
    :dev/write-state {:dev/swap-state (select-keys event [:f :args])}
    {}))
