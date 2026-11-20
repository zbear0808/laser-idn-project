(ns laser-show.events.safety-guard
  "When an edit may let a live projector's light reach somewhere new, ask
   first (zones plan Z1, D-28). Shared by the safety zone events and Undo and
   Redo.

   A projector is outputting while streaming is on and the engines are built
   for it (enabled, with a host: output-runtime/wanted): the operator's
   intent, not the engine's status, which lags and can recover."
  (:require [clojure.string :as str]
            [laser-show.animation.safety :as safety]
            [laser-show.backend.output-runtime :as output-runtime]))

(defn outputting?
  "Is projector-id's light going out?"
  [state projector-id]
  (boolean (and (get-in state [:backend :streaming :running?])
                (contains? (output-runtime/wanted {projector-id (get-in state [:projectors projector-id])})
                           projector-id))))

(defn projector-name
  "A projector's name for a question, or its id."
  [state projector-id]
  (let [n (get-in state [:projectors projector-id :name])]
    (if (and (string? n) (not (str/blank? n))) n (name projector-id))))

(defn names-text
  "\"Laser L\", \"Laser L and Laser R\", \"Laser L, Laser C and Laser R\"."
  [names]
  (let [names (vec names)]
    (if (< (count names) 2)
      (str/join names)
      (str (str/join ", " (pop names)) " and " (peek names)))))

(defn parked
  "event as plain data, to continue after a confirm: without its co-effects."
  [event]
  (dissoc event :state :time :fx/event))

(defn asking?
  "Is the shared confirm dialog already asking something?"
  [state]
  (boolean (get-in state [:ui :dialogs :confirm :open?])))

(defn ask
  "state with the shared confirm dialog asking about a larger area: on yes it
   continues with event, :confirmed? true."
  [state {:keys [title message confirm-label event]}]
  (assoc-in state [:ui :dialogs :confirm]
            {:open? true
             :title (or title "Allow light in a larger area?")
             :message message
             :confirm-label (or confirm-label "Allow")
             :confirm-event (assoc (parked event) :confirmed? true)}))

(defn growing
  "The outputting projectors (in before) whose safety zone after would let
   light somewhere before's doesn't, in id order."
  [before after]
  (->> (keys (:projectors after))
       (filter #(outputting? before %))
       (filter #(safety/widens? (get-in before [:projectors % :safety])
                                (get-in after [:projectors % :safety])))
       (sort-by str)
       vec))
