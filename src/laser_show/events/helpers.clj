(ns laser-show.events.helpers
  "Shared helper functions for event handlers.
   
   These utilities are used across multiple event handler modules to maintain
   consistent behavior and avoid code duplication.
   
   Note: handlers never set [:project :dirty?]. The event pipeline derives it
   (laser-show.events.handlers.history/wrap-dirty)."
  (:require [laser-show.state.clipboard :as clipboard]))


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


(defn finite-number?
  "Is v a number a numeric parameter can hold: not NaN or an infinity?"
  [v]
  (and (number? v) (Double/isFinite (double v))))

(defn parse-number-text
  "The number typed into a numeric field as text, or nil for text that isn't
   a finite number (\"abc\", \"\", \"NaN\", \"Infinity\") or isn't text at
   all (a text field's ActionEvent: the field reads its text on the FX
   thread). Read as Double/parseDouble reads it, clamped to min and max
   (each ignored when nil), and for an :int parameter (int?) rounded to a
   whole number, a long.
   
   Example:
   (parse-number-text \"1.5\" 0.0 1.0 false)
   => 1.0"
  [text min max int?]
  (let [parsed (when (string? text) (parse-double text))
        clamped (when (finite-number? parsed)
                  (cond-> (double parsed)
                    (number? min) (clojure.core/max (double min))
                    (number? max) (clojure.core/min (double max))))]
    (when (finite-number? clamped)
      (if int? (Math/round (double clamped)) clamped))))



(defn first-some
  "Return the first argument that is not nil. Unlike `or`, keeps `false`
   (e.g. an unchecked checkbox's :fx/event)."
  [& values]
  (reduce (fn [_ v] (when (some? v) (reduced v))) nil values))


(def notice-ms
  "How long a notice stays at the left of the status bar, unless clicked away."
  6000)

(defn show-notice
  "Effects that put a notice on screen: state with text at [:ui :notice]
   (level :error, :warning or :info), and the :dispatch-later that clears it
   after notice-ms. Each notice gets its own id, so an earlier notice's timer
   never clears a later one (:ui/clear-notice clears only its id).

   Example:
   (show-notice state \"Could not open show.zip\" :error)
   => {:state (assoc-in state [:ui :notice] {:id #uuid \"…\" :text … :level :error})
       :dispatch-later {:event {:event/type :ui/clear-notice :id #uuid \"…\"} :delay-ms 6000}}"
  [state text level]
  (let [id (random-uuid)]
    {:state (assoc-in state [:ui :notice] {:id id :text text :level level})
     :dispatch-later {:event {:event/type :ui/clear-notice :id id}
                      :delay-ms notice-ms}}))


(defn parked
  "event as plain data, to continue later (after a confirm, or the OS
   clipboard's read): without its co-effects, and without the press it came
   from (:history/gesture-id): the continuation is a discrete edit of its
   own, which neither revives nor needs that press (reactivity plan 16)."
  [event]
  (dissoc event :state :time :fx/event :history/gesture-id))


;; The open show's identity (reactivity plan 20)

(defn document-id
  "The identity of the show open in state, [:project :document-id] (see
   state.domains): new at start and with each File > New and Open; nil in a
   bare state."
  [state]
  (get-in state [:project :document-id]))

(defn for-document
  "event, to come back later, stamped for the show open in state: with its
   document-id as :document-id (none in a bare state). When it is back,
   same-document? says whether that show is still the one open."
  [event state]
  (if-let [id (document-id state)]
    (assoc event :document-id id)
    event))

(defn same-document?
  "Is the show open in state the one event was stamped for (for-document)?
   false once a New or an Open replaced it. An event without :document-id
   counts as a show's without one, so a test that builds such an event by
   hand for an app's show stamps it too."
  [state event]
  (= (:document-id event) (document-id state)))


;; The clipboard (keyboard plan Phase 4, Step 4a)

(defn with-clipboard
  "The one place a paste looks at the clipboard: (f state clip), clip being
   [:ui :clipboard], f's effects, or nil when its target takes nothing from
   clip. While the slot is empty (nothing copied in this app yet), the paste
   first asks the OS clipboard: {:clipboard/read {:then event}} (parked, and
   stamped for the show open now: for-document), and the read continues
   with event and :clipboard/system, the clip it found or nil, which the
   slot takes if it is still empty (clipboard/adopt). A read that comes back
   after New or Open replaced that show does nothing at all ({}: no paste,
   no slot, no notice). A paste after a read that finds nothing to paste
   says so: \"Nothing to paste here\". Without a read, a clip the target
   doesn't take does nothing."
  [{:keys [state] :as event} f]
  (let [read? (contains? event :clipboard/system)]
    (cond
      (and (nil? (get-in state [:ui :clipboard])) (not read?))
      {:clipboard/read {:then (for-document (parked event) state)}}

      (and read? (not (same-document? state event)))
      {}

      :else
      (let [state' (clipboard/adopt state (:clipboard/system event))]
        (or (f state' (get-in state' [:ui :clipboard]))
            (if read?
              (show-notice state' "Nothing to paste here" :info)
              {:state state'}))))))

(defn handle-copy-to-clipboard
  "A cell's copy: the cell at source-path as a clip of clipboard-type
   (clipboard/cell-clip, copied at now) in [:ui :clipboard], and on the OS
   clipboard (:clipboard/write). An empty cell copies nothing: the slot
   keeps what it had, so a paste never empties a cell.

   Example:
   (handle-copy-to-clipboard state [:chains :effect-chains [0 1]] :effects-cell 1763650000000)
   => {:state (assoc-in state [:ui :clipboard] clip) :clipboard/write clip}"
  [state source-path clipboard-type now]
  (if-let [clip (clipboard/cell-clip clipboard-type (get-in state source-path) now)]
    {:state (assoc-in state [:ui :clipboard] clip)
     :clipboard/write clip}
    {:state state}))

(defn handle-paste-from-clipboard
  "A cell's paste (with-clipboard): a clip of expected-type written to
   target-path, through transform-fn when given (for example
   chains/fresh-ids, so the pasted cell has ids of its own); a clip of
   another type, or of an empty cell, does nothing.

   Example:
   (handle-paste-from-clipboard event [:chains :effect-chains [0 2]] :effects-cell)"
  [event target-path expected-type & [transform-fn]]
  (with-clipboard event
    (fn [state clip]
      (when (clipboard/accepts? clip expected-type)
        (let [data (clipboard/payload clip expected-type)]
          {:state (assoc-in state target-path (if transform-fn (transform-fn data) data))})))))


;; What the projector UI names (reactivity plan 14)

(defn prune-projector-ui
  "state with the projector UI's references to projectors and virtual
   projectors it no longer has let go of: the selected card
   ([:projector-ui :active-projector]), the selected virtual projector, the
   projector showing its test pattern, and Identify's flashes. Session UI
   state, never saved or undone: it never selects anything. state as it is
   when everything it names exists."
  [state]
  (let [{:keys [active-projector active-virtual-projector calibrating-projector-id identify]} (:projector-ui state)
        projectors (:projectors state)
        gone? (fn [m id] (and (some? id) (not (contains? m id))))
        kept (into {} (filter #(contains? projectors (key %))) identify)]
    (cond-> state
      (gone? projectors active-projector) (assoc-in [:projector-ui :active-projector] nil)
      (gone? (:virtual-projectors state) active-virtual-projector) (assoc-in [:projector-ui :active-virtual-projector] nil)
      (gone? projectors calibrating-projector-id) (assoc-in [:projector-ui :calibrating-projector-id] nil)
      (< (count kept) (count identify)) (assoc-in [:projector-ui :identify] kept))))
