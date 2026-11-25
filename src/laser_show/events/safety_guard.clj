(ns laser-show.events.safety-guard
  "When an edit may let a live projector's light reach somewhere new, ask
   first (zones plan Z1, D-28). Shared by the safety zone events and Undo and
   Redo.

   A projector is outputting while streaming is on and the engines are built
   for it (enabled, with a host: output-runtime/wanted): the operator's
   intent, not the engine's status, which lags and can recover.

   A coordinate field's edit and Reset (plans/2026-11-23-cljfx-reactivity/08),
   a drag on the canvas and a blocked area's removal are judged by the change
   they make to the zone stored when they are handled (zone-edit-proposal),
   a Copy by copy-proposal's, the same for their handler and the show lock.
   Their question is one of their own (ask-review): only its own answer
   continues it (review-answer), and only while the zone is still the one it
   asked about; so are a Copy's, Undo's and Redo's, about the larger areas
   they would give several projectors (growth). The versions that tell,
   moved on by wrap-safety-versions, are in [:projector-ui
   :safety-versions]."
  (:require [clojure.string :as str]
            [laser-show.animation.safety :as safety]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.events.helpers :as h]))

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
             :confirm-event (assoc (h/parked event) :confirmed? true)}))

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

(defn growth
  "{projector-id zone} of growing's projectors, each with its zone in after:
   the larger areas a question about several projectors asks about (a Copy,
   Undo, Redo), and its Allow gives only while they are the same."
  [before after]
  (into {} (map (fn [id] [id (get-in after [:projectors id :safety])])) (growing before after)))


;; The safety versions


(def ^:private versions-path [:projector-ui :safety-versions])

(defn geometry-version
  "The version of projector-id's zone in state: it moves on with every change
   to the zone (wrap-safety-versions). 0 for one never stamped."
  [state projector-id]
  (get-in state (conj versions-path :by-id projector-id :geometry) 0))

(defn rows-version
  "The version of projector-id's blocked areas' layout in state: it moves on
   when its blocked areas may be other rows than before, not with an edit of
   one of their numbers. 0 for one never stamped."
  [state projector-id]
  (get-in state (conj versions-path :by-id projector-id :rows) 0))

(defn- next-serial [state]
  (inc (get-in state (conj versions-path :serial) 0)))

(defn- stamp
  "state with projector-id's geometry version, and with rows? its rows
   version too, the next serial."
  [state projector-id rows?]
  (let [n (next-serial state)]
    (-> state
        (assoc-in (conj versions-path :serial) n)
        (update-in (conj versions-path :by-id projector-id)
                   #(cond-> (assoc (or % {:rows 0}) :geometry n)
                      rows? (assoc :rows n))))))

(defn- restamp
  "new-state with the versions of the projectors whose zone changed from
   state moved on, by event type: a coordinate edit or a drag keeps the
   rows, as does a change that leaves the blocked areas as they were; any
   other change to them moves them on, and so does a projector that wasn't
   there. One that is gone loses its entry."
  [state new-state type]
  (let [old (:projectors state)
        new (:projectors new-state)]
    (reduce (fn [s id]
              (let [o (get old id)
                    n (get new id)]
                (cond
                  (identical? o n) s

                  (not (contains? new id))
                  (if (contains? (get-in s (conj versions-path :by-id)) id)
                    (update-in s (conj versions-path :by-id) dissoc id)
                    s)

                  (not (contains? old id)) (stamp s id true)

                  (= (:safety o) (:safety n)) s

                  (or (contains? #{:projectors/set-safety-coordinate :projectors/drag-safety} type)
                      (= (:blocked (:safety o)) (:blocked (:safety n))))
                  (stamp s id false)

                  :else (stamp s id true))))
            new-state
            (sort-by str (distinct (concat (keys old) (keys new)))))))

(defn wrap-safety-versions
  "Middleware that moves the safety versions on ([:projector-ui
   :safety-versions] {:serial n :by-id {projector-id {:geometry g :rows r}}},
   each stamp the next serial) for what an event actually did to the
   projectors' zones, whatever the event: an edit, Undo and Redo, a scan, a
   show's set-up. A blocked area's field names its row with :rows, and a
   safety question remembers :geometry (ask-review). A refused or asking
   event changes no zone, so it moves nothing; the versions are no document:
   no undo step, no unsaved flag, no rig write. It sits just outside the
   history and the unsaved flag (events/core.clj)."
  [handler]
  (fn [{:keys [state event/type] :as event}]
    (let [effects (handler event)
          new-state (:state effects)]
      (if (or (nil? state) (nil? new-state) (identical? (:projectors state) (:projectors new-state)))
        effects
        (assoc effects :state (restamp state new-state type))))))


;; A coordinate field's edit and Reset (plans/2026-11-23-cljfx-reactivity/08),
;; a drag on the canvas, a blocked area's removal and a Copy (follow-up B3)


(def zone-changed-text
  "What the Allow of a safety question whose zones changed since it asked
   says: it wrote nothing."
  "Safety zone changed; review the edit again")

(defn- readable
  "zone normalized, or nil when it can't be read."
  [zone]
  (try (safety/normalize zone) (catch clojure.lang.ExceptionInfo _ nil)))

(defn- coordinate-value?
  "Is v a number a coordinate can be: finite, in -1..1?"
  [v]
  (and (number? v) (Double/isFinite (double v)) (<= -1.0 (double v) 1.0)))

(defn- corner? [k]
  (and (integer? k) (<= 0 k 3)))

(defn- blocked-path? [path]
  (and (vector? path) (= :blocked (first path))))

(defn- with-coordinate
  "zone (normalized) with the number at path set to v: path exactly
   [:limit k] or [:blocked i k], k 0..3 and i one of zone's rows. nil for
   any other path."
  [zone path v]
  (when (vector? path)
    (let [[what i k] path]
      (cond
        (and (= :limit what) (= 2 (count path)) (corner? i))
        (assoc-in zone [:limit i] v)

        (and (= :blocked what) (= 3 (count path)) (integer? i) (< -1 i (count (:blocked zone))) (corner? k))
        (assoc-in zone [:blocked i k] v)))))

(defn- rect-path?
  "Is path exactly [:limit], or [:blocked i] with i one of zone's rows?"
  [zone path]
  (and (vector? path)
       (or (= [:limit] path)
           (let [[what i] path]
             (and (= :blocked what) (= 2 (count path)) (integer? i) (< -1 i (count (:blocked zone))))))))

(defn zone-with-coordinates
  "zone (normalized) with some numbers of one of its rectangles set: path
   exactly [:limit] or [:blocked i] (i one of zone's rows), coordinates
   {k v} the numbers a canvas drag moves (k 0..3: x0 y0 x1 y1; v finite, in
   -1..1). nil for any other path or map, and when the result isn't a zone
   as it is stored (an edge past its opposite one, a limit with no width or
   height): no other number moves. What the canvas previews, and
   :projectors/drag-safety sets."
  [zone path coordinates]
  (when (and (rect-path? zone path)
             (map? coordinates)
             (seq coordinates)
             (every? corner? (keys coordinates))
             (every? coordinate-value? (vals coordinates)))
    (let [candidate (reduce-kv (fn [z k v] (assoc-in z (conj path k) (double v))) zone coordinates)]
      (when (= candidate (readable candidate))
        candidate))))

(defn zone-edit-proposal
  "What intent would do to its projector's safety zone, judged against the
   zone stored in state now: {:projector-id :before (the stored value as it
   is, nil, or one that can't be read) :after (normalized) :same? (after is
   what applies already: nothing to write)}, or nil when it does nothing. It
   commits nothing: the handler and the show lock both judge this change.
   - {:event/type :projectors/set-safety-coordinate :projector-id :path
     :value}: one number of a zone that can be read. :path is exactly
     [:limit k] or [:blocked i k] (k 0..3: x0 y0 x1 y1), a blocked area's
     with the :row-version its field showed, still its layout's
     (rows-version); :value a finite number in -1..1. nil for anything else,
     and when the number would cross its rectangle's other edge or leave the
     limit no width or height: no other number moves
   - {:event/type :projectors/drag-safety :projector-id :path :coordinates}:
     the numbers a drag on the canvas moved of one rectangle of a zone that
     can be read (zone-with-coordinates), a blocked area's with the
     :row-version it was grabbed in, still its layout's
   - {:event/type :projectors/remove-blocked-area :projector-id :index}: the
     zone, which can be read, without that blocked area, one of its rows;
     with :row-version (its button's) only while that is its layout's
   - {:event/type :projectors/reset-safety :projector-id}: the whole range,
     nothing blocked, even for a zone that can't be read
   It names a projector, never the selection: nil once it is gone."
  [state {:keys [event/type projector-id path value coordinates index row-version]}]
  (when (contains? (:projectors state) projector-id)
    (let [before (get-in state [:projectors projector-id :safety])
          current (readable before)
          proposal (fn [after]
                     {:projector-id projector-id :before before :after after :same? (= after current)})
          rows? (fn [] (= row-version (rows-version state projector-id)))]
      (case type
        :projectors/reset-safety
        (proposal safety/default-safety)

        :projectors/set-safety-coordinate
        (when (and current
                   (coordinate-value? value)
                   (or (not (blocked-path? path)) (rows?)))
          (when-let [candidate (with-coordinate current path (double value))]
            (when (= candidate (readable candidate))
              (proposal candidate))))

        :projectors/drag-safety
        (when (and current (or (not (blocked-path? path)) (rows?)))
          (some-> (zone-with-coordinates current path coordinates) proposal))

        :projectors/remove-blocked-area
        (when (and current
                   (integer? index)
                   (< -1 index (count (:blocked current)))
                   (or (nil? row-version) (rows?)))
          (proposal (update current :blocked #(into (subvec % 0 index) (subvec % (inc index))))))

        nil))))

(defn copy-proposal
  "What a Copy (or Mirror) intent {:from :to :mirror?} would do, judged
   against the zones stored in state now: {:zone (from's, normalized, and
   mirrored left to right with :mirror?) :after (state with that zone on
   each target: every projector in :to that is there, but from)}. nil when
   from isn't there, its zone can't be read, or there is no target."
  [state {:keys [from to mirror?]}]
  (let [projectors (:projectors state)
        zone (when (contains? projectors from) (readable (get-in projectors [from :safety])))
        targets (filterv #(and (contains? projectors %) (not= from %)) (when (sequential? to) to))]
    (when (and zone (seq targets))
      (let [zone (if mirror? (safety/mirror-x zone) zone)]
        {:zone zone
         :after (reduce #(assoc-in %1 [:projectors %2 :safety] zone) state targets)}))))

(defn proposal-widens?
  "Would intent, judged against the zones stored in state now, let light
   reach somewhere new on any projector, outputting or not: a Copy by what
   copy-proposal gives, any other by zone-edit-proposal's change? What the
   show lock refuses (events/show_lock.clj)."
  [state intent]
  (if (= :projectors/copy-safety (:event/type intent))
    (boolean (when-let [{:keys [after]} (copy-proposal state intent)]
               (some #(safety/widens? (get-in state [:projectors % :safety]) (get-in after [:projectors % :safety]))
                     (keys (:projectors after)))))
    (let [{:keys [before after same?] :as proposal} (zone-edit-proposal state intent)]
      (boolean (and proposal (not same?) (safety/widens? before after))))))

(defn- versions
  "{projector-id geometry-version} of ids in state."
  [state ids]
  (into {} (map (fn [id] [id (geometry-version state id)])) ids))

(defn ask-review
  "state with the shared confirm dialog asking about a larger area (ask:
   :title, :message, :confirm-label) for intent, the plain data of what
   asks, as a question of its own: a fresh id from the versions' serial,
   and :safety-review {:id :intent …} with the zones it asks about. For an
   edit of one projector's zone (a coordinate edit, Reset, a drag, a blocked
   area's removal) :geometry, that projector's geometry-version now; for a
   change to several (a Copy, Undo, Redo), :zones, the growth it would give
   ({projector-id zone}, growth's), and their :geometries {projector-id
   version}. Allow continues with {:event/type (intent's) :review-id id
   :confirmed? true}, which review-answer matches to this question only."
  [state {:keys [title message confirm-label intent zones]}]
  (let [id (next-serial state)]
    (-> state
        (assoc-in (conj versions-path :serial) id)
        (ask {:title title :message message :confirm-label confirm-label
              :event {:event/type (:event/type intent) :review-id id}})
        (assoc-in [:ui :dialogs :confirm :safety-review]
                  (if zones
                    {:id id :intent intent :zones zones :geometries (versions state (keys zones))}
                    {:id id :geometry (geometry-version state (:projector-id intent)) :intent intent})))))

(defn review-answer
  "What event answers when it carries the :review-id of the open question
   of its own operation (ask-review): {:intent (the plain intent it asked
   about) :zones (for a question about several projectors, the growth it
   asked about: its handler gives it only while the change gives the same)
   :closed (state with that question closed) :stale? (a zone it asked about
   changed since: a geometry version moved on)}. nil for no :review-id, a
   duplicate, an answer after Cancel or another question's id: no
   permission."
  [state {:keys [event/type review-id]}]
  (let [{:keys [open? safety-review]} (get-in state [:ui :dialogs :confirm])
        {:keys [id geometry geometries intent zones]} safety-review]
    (when (and open? (some? review-id) (= review-id id) (= type (:event/type intent)))
      {:intent intent
       :zones zones
       :closed (assoc-in state [:ui :dialogs :confirm] {:open? false})
       :stale? (if geometries
                 (not= geometries (versions state (keys geometries)))
                 (not= geometry (geometry-version state (:projector-id intent))))})))
