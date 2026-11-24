(ns laser-show.events.handlers.rig-autosave
  "Pipeline middleware: the rig file follows the rig (plans/2026-11-19-zones/09
   §4). After any event, when the rig changed (rig-file/rig-changed?: a rig
   path, or the definition of a group the rig's lasers are in), a rig file is
   set and it isn't read only, the effects get :rig/write {:path :doc :seq},
   which hands the rig to the rig writer (state.rig-store/submit!). Every way
   the rig changes is covered so: edits, undo and redo, discovery, a show's
   set-up. A show edit never writes.

   Remembering the definitions is apart from the I/O (reactivity plan 23):
   whenever an event changes a rig path, the show's groups or the copy
   itself, the copy ([:rig-file :group-copy]) is brought to the event's
   final state (rig-file/with-group-copy), whether or not the file can be
   written.

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
  "{:state :rig/write} to write state's rig: its document, and state with
   the request counted ([:rig-file :requested]). opts: :then, an event to
   continue with once the file is written; :now? to write without the half
   second's wait."
  ([state] (write-request state nil))
  ([state {:keys [then now?]}]
   (let [n (inc (get-in state [:rig-file :requested] 0))]
     {:state (assoc-in state [:rig-file :requested] n)
      :rig/write (cond-> {:path (get-in state [:rig-file :path]) :doc (rig-file/rig-document state) :seq n}
                   then (assoc :then then)
                   now? (assoc :now? true))})))

(defn- copy-made-of-changed?
  "Did old to new change, by identity, what the rig's group copy is made
   of: a rig path, the show's groups or the copy itself?"
  [old new]
  (boolean
   (or (some #(not (identical? (get-in old %) (get-in new %))) rig-file/rig-paths)
       (not (identical? (:zone-groups old) (:zone-groups new)))
       (not (identical? (get-in old [:rig-file :group-copy]) (get-in new [:rig-file :group-copy]))))))

(defn wrap-rig-autosave
  "Middleware that keeps the group copy of the event's state and adds
   :rig/write when the event changed the rig and the rig file may be
   written."
  [handler]
  (fn [{:keys [state] :as event}]
    (let [effects (handler event)
          new-state (:state effects)]
      (if (and new-state (copy-made-of-changed? state new-state))
        (let [final (rig-file/with-group-copy new-state)
              effects (cond-> effects (not (identical? final new-state)) (assoc :state final))]
          (if (and (writable? final) (rig-file/rig-changed? state final))
            (merge effects (write-request final))
            effects))
        effects))))
