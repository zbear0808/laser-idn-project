(ns laser-show.events.helpers
  "Shared helper functions for event handlers.
   
   These utilities are used across multiple event handler modules to maintain
   consistent behavior and avoid code duplication.")


(defn mark-dirty
  "Mark project as having unsaved changes."
  [state]
  (assoc-in state [:project :dirty?] true))

(defn current-time-ms
  "Get current time from event or system.
   
   Accepts an event map and returns:
   - The :time key from the event if present
   - The current system time in milliseconds otherwise"
  [event]
  (or (:time event) (System/currentTimeMillis)))


(defn ensure-item-fields
  "Ensure item has :id and :enabled? fields.
   Used when adding effects, presets, or other items to chains.
   
   Example:
   (ensure-item-fields {:effect-id :scale})
   => {:effect-id :scale :id #uuid \"...\" :enabled? true}"
  [item]
  (cond-> item
    (not (contains? item :id)) (assoc :id (random-uuid))
    (not (contains? item :enabled?)) (assoc :enabled? true)))


(defn parse-and-clamp-from-text-event
  "Extract text from ActionEvent, parse as double, clamp to bounds.
   Returns nil if parsing fails.
   
   Example:
   (parse-and-clamp-from-text-event action-event 0.0 1.0)
   => 0.5"
  [fx-event min max]
  (let [text-field (.getSource fx-event)
        text (.getText text-field)]
    (when-let [parsed (try (Double/parseDouble text) (catch Exception _ nil))]
      (-> parsed (clojure.core/max min) (clojure.core/min max)))))



(defn handle-copy-to-clipboard
  "Generic copy handler that reads data from state and copies to clipboard.
   
   Parameters:
   - state: Application state
   - source-path: Path to read data from (e.g., [:chains :effect-chains [col row]])
   - clipboard-type: Keyword for clipboard type (e.g., :effects-cell)
   - system-clipboard-fn: Function to call for system clipboard (e.g., clipboard/copy-effects-cell!)
   
   Returns: {:state updated-state} with internal clipboard set
   
   Example:
   (handle-copy-to-clipboard state
                             [:chains :effect-chains [0 1]]
                             :effects-cell
                             clipboard/copy-effects-cell!)"
  [state source-path clipboard-type system-clipboard-fn]
  (let [data (get-in state source-path)
        clip-data {:type clipboard-type :data data}]
    ;; Copy to system clipboard (side effect)
    (when (and system-clipboard-fn data)
      (system-clipboard-fn data))
    ;; Return state update for internal clipboard
    {:state (assoc-in state [:ui :clipboard] clip-data)}))

(defn handle-paste-from-clipboard
  "Generic paste handler that writes clipboard data to target path.
   
   Parameters:
   - state: Application state
   - target-path: Path to write data to (e.g., [:chains :effect-chains [col row]])
   - expected-type: Expected clipboard type keyword (e.g., :effects-cell)
   - transform-fn: Optional function to transform data before pasting (e.g., regenerate-ids)
   
   Returns: {:state updated-state} or {:state state} if clipboard type doesn't match
   
   Example:
   (handle-paste-from-clipboard state
                                [:chains :effect-chains [0 2]]
                                :effects-cell
                                identity)"
  [state target-path expected-type & [transform-fn]]
  (let [cb (get-in state [:ui :clipboard])]
    (if (and cb (= expected-type (:type cb)))
      (let [data (:data cb)
            transformed-data (if transform-fn (transform-fn data) data)]
        {:state (-> state
                    (assoc-in target-path transformed-data)
                    mark-dirty)})
      {:state state})))
