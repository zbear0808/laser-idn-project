(ns laser-show.events.handlers.dev
  "Tooling events, for tests, the REPL and the dev reloads (plans/2026-11-20-state-integrity/
   02-direct-state-writes.md §4). Pure.

   - :dev/flush {:promise p}: events/flush!'s marker; its effect delivers p
     once every event queued before it has been handled
   - :dev/write-state {:f :args}: events/write-state!'s write, done by the
     :dev/swap-state effect on the event thread. It returns no :state, so no
     middleware sees it: no undo step, no unsaved flag, no output sync, no
     rig write
   - :dev/reloaded {:kind :styles}: a style reload's trigger, [:styles
     :reload-trigger] bumped, so the stylesheet URLs are read again
   - :dev/reloaded {:kind :app}: a code reload's, [:ui
     :last-reload-timestamp] set to the event's :time, so the views render
     again with the reloaded code")

(defn handle
  "Route a :dev/* event to its effects; {} for any other."
  [{:keys [event/type] :as event}]
  (case type
    :dev/flush {:dev/deliver (:promise event)}
    :dev/write-state {:dev/swap-state (select-keys event [:f :args])}
    :dev/reloaded (case (:kind event)
                    :styles {:state (update-in (:state event) [:styles :reload-trigger] (fnil inc 0))}
                    :app {:state (assoc-in (:state event) [:ui :last-reload-timestamp] (:time event))}
                    {})
    {}))
