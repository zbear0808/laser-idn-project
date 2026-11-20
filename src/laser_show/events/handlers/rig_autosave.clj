(ns laser-show.events.handlers.rig-autosave
  "Pipeline middleware: the rig file follows the rig (plans/2026-11-19-zones/09
   §4). After any event, when the rig changed (rig-file/rig-changed?: a rig
   path, or the definition of a group the rig's lasers are in), a rig file is
   set and it isn't read only, the effects get :rig/write {:path :doc :seq},
   which hands the rig to the rig writer (state.rig-store/submit!). Every way
   the rig changes is covered so: edits, undo and redo, discovery, a show's
   set-up. A show edit never writes.

   It sits outside wrap-dirty, beside the output sync, so it sees each
   event's final state, and inside the lost-update check, so the request it
   counts in [:rig-file] is part of the event's write."
  (:require [laser-show.state.rig-file :as rig-file]))

(defn writable?
  "Is there a rig file this window may write?"
  [state]
  (boolean (and (get-in state [:rig-file :path])
                (not (get-in state [:rig-file :read-only?])))))

(defn write-request
  "{:state :rig/write} to write state's rig: state with the request counted
   ([:rig-file :requested]) and the group copy it writes kept
   ([:rig-file :group-copy]). opts: :then, an event to continue with once
   the file is written; :now? to write without the half second's wait."
  ([state] (write-request state nil))
  ([state {:keys [then now?]}]
   (let [n (inc (get-in state [:rig-file :requested] 0))
         doc (rig-file/rig-document state)]
     {:state (-> state
                 (assoc-in [:rig-file :requested] n)
                 (assoc-in [:rig-file :group-copy] (:zone-groups doc)))
      :rig/write (cond-> {:path (get-in state [:rig-file :path]) :doc doc :seq n}
                   then (assoc :then then)
                   now? (assoc :now? true))})))

(defn wrap-rig-autosave
  "Middleware that adds :rig/write when the event changed the rig and the
   rig file may be written."
  [handler]
  (fn [{:keys [state] :as event}]
    (let [effects (handler event)
          new-state (:state effects)]
      (if (and new-state (writable? new-state) (rig-file/rig-changed? state new-state))
        (merge effects (write-request new-state))
        effects))))
