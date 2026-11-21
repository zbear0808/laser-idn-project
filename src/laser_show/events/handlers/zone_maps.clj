(ns laser-show.events.handlers.zone-maps
  "The zones, the show default and the zone maps (zones plan Z4, 06 §6):
   :zones/* (the show's default target, the zones' labels), :zone-maps/*
   (your maps: made, edited, deleted, selected, used now) and :zone-map/*
   (a switch: a pad's press, its timing, its key). Routed for \"zones\",
   \"zone-maps\" and \"zone-map\" (events/handlers.clj).

   A switch is a performer's: a pad's press or a learned key's reads the
   show clock (the :show-clock/read effect hands it the beats), then lands
   at once or waits for the beat or the bar (routing.zone-maps/schedule).
   It is live: never an undo step, never unsaved (06 §5). Nothing here reads
   the clock itself, and the clock writes no app state: a due switch comes
   back as :show-clock/zone-map-due (handlers/timing.clj)."
  (:require [clojure.string :as str]
            [laser-show.events.handlers.vibes :as vibes]
            [laser-show.routing.targets :as targets]
            [laser-show.routing.zone-maps :as zone-maps])
  (:import [javafx.scene.input MouseEvent]))


;; The show default

(defn- chosen-target
  "The target an event sets: its :target, else a picker's choice (the :id of
   its :fx/event), :inherit meaning none; in normal form."
  [{:keys [fx/event] :as e}]
  (let [t (if (contains? e :target) (:target e) (:id event))]
    (when-not (= :inherit t)
      (targets/normalize-target t))))

(defn- handle-set-default-target
  "Set the show's default target, [:config :default-target]: what a cue
   with none of its own, in a column with none, draws to. None is All."
  [{:keys [state] :as event}]
  {:state (assoc-in state [:config :default-target] (chosen-target event))})


;; The zones' labels

(defn- handle-set-label
  "Name zone (\"Beams\"), or take its label away with a blank one."
  [{:keys [zone label state]}]
  (if (targets/zone-ref? zone)
    (let [text (str/trim (str label))]
      {:state (if (str/blank? text)
                (update-in state [:zones :labels] dissoc zone)
                (assoc-in state [:zones :labels zone] text))})
    {:state state}))


;; Your maps (06 §7.5): the built-ins are derived, never edited

(defn- custom-map
  "Your map id in state, or nil: a built-in one is never edited."
  [state id]
  (when-not (zone-maps/built-in? id)
    (get-in state [:zone-maps id])))

(defn- unique-name
  "base, else base 2, base 3…: a name no map of state has."
  [state base]
  (let [taken (set (keep :name (zone-maps/all-maps state)))]
    (first (remove taken (cons base (for [i (iterate inc 2)] (str base " " i)))))))

(defn- select-map [state id]
  (assoc-in state [:ui :zone-maps :selected] id))

(defn- add-map
  "state with map m as a new one of yours, selected."
  [state m]
  (let [id (zone-maps/next-custom-id state)]
    (-> state
        (assoc-in [:zone-maps id] m)
        (select-map id))))

(defn- handle-create
  "A new map, Left / Right's lanes, selected."
  [{:keys [state]}]
  {:state (add-map state {:name (unique-name state "New map") :lanes [:auto/left :auto/right] :overrides {}})})

(defn- handle-duplicate
  "A copy of any map, built-in or yours, as one of yours: \"Odd / Even
   copy\", selected."
  [{:keys [state] id :map}]
  (if-let [m (zone-maps/map-by-id state id)]
    {:state (add-map state {:name (unique-name state (str (:name m) " copy"))
                            :lanes (vec (:lanes m))
                            :overrides (into {} (:overrides m))})}
    {:state state}))

(defn- edit-map
  "state with (f m & args) for your map id; a built-in or unknown one stays
   as it is."
  [state id f & args]
  (if (custom-map state id)
    (apply update-in state [:zone-maps id] f args)
    state))

(defn- handle-rename
  "Rename your map; a blank name changes nothing."
  [{:keys [name state] id :map}]
  (let [text (str/trim (str name))]
    {:state (if (str/blank? text) state (edit-map state id assoc :name text))}))

(defn- lane-ref
  "What a lane or an override may be: All or a group, one ref in normal
   form, never a zone (06 §7.5); nil for anything else."
  [t]
  (let [t (targets/normalize-target t)]
    (when (and (keyword? t) (not (targets/zone-ref? t)))
      t)))

(defn- event-ref
  "The ref an edit sets: :ref, or a Target chip's :target."
  [event]
  (if (contains? event :ref) (:ref event) (:target event)))

(defn- lane-index? [state id index]
  (and (integer? index) (< -1 index (count (get-in state [:zone-maps id :lanes])))))

(defn- handle-set-lane
  "Lane index of your map goes to the event's ref: All or a group."
  [{:keys [index state] id :map :as event}]
  (let [ref (lane-ref (event-ref event))]
    {:state (if (and ref (lane-index? state id index))
              (edit-map state id update :lanes #(assoc (vec %) index ref))
              state)}))

(defn- handle-add-lane
  "A lane more for your map: All, until you pick its group."
  [{:keys [state] id :map}]
  {:state (edit-map state id update :lanes #(conj (vec %) :all))})

(defn- handle-remove-lane
  "Lane index of your map goes; the zones wrap over the lanes left."
  [{:keys [index state] id :map}]
  {:state (if (lane-index? state id index)
            (edit-map state id update :lanes (fn [lanes] (let [v (vec lanes)] (into (subvec v 0 index) (subvec v (inc index))))))
            state)})

(defn- handle-set-override
  "Send zone of your map to the event's ref (All or a group) whatever its
   lane; nil takes the override away, so the zone wraps again. With :from,
   that zone's override moves to zone."
  [{:keys [zone from state] id :map :as event}]
  (let [given (targets/normalize-target (event-ref event))
        ref (lane-ref given)]
    {:state (if (and (targets/zone-ref? zone)
                     (or (nil? from) (targets/zone-ref? from))
                     (or ref (nil? given)))
              (edit-map state id update :overrides
                        (fn [overrides]
                          (cond-> (dissoc (or overrides {}) from zone)
                            ref (assoc zone ref))))
              state)}))

(defn- map-keys
  "The trigger-map keys learned for map id's pad."
  [state id]
  (keep (fn [[k action]] (when (and (= :zone-map (:type action)) (= id (:target action))) k))
        (get-in state [:config :input :trigger-map])))

(defn- delete-message
  "What goes with map id, as its delete asks (06 §6)."
  [state id]
  (let [n (count (map-keys state id))
        setting? (some? (get-in state [:config :zone-map-pads id]))
        parts (cond-> []
                (= id (get-in state [:zone-map :active]))
                (conj "It's the zone map in use: the show switches to Left / Right.")
                (or (pos? n) setting?)
                (conj (str "Its "
                           (str/join " and its " (cond-> []
                                                   (pos? n) (conj (if (= 1 n) "learned key" (str n " learned keys")))
                                                   setting? (conj "pad setting")))
                           (if (or (> n 1) (and (pos? n) setting?)) " go too." " goes too."))))]
    (if (seq parts) (str/join " " parts) "Nothing else uses it.")))

(defn- handle-request-delete
  "Ask before deleting your map (Z0's confirm dialog, as every delete asks):
   what goes with it, and :zone-maps/delete on yes. A built-in map has no
   Delete: state stays as it is."
  [{:keys [state] id :map}]
  (if-let [m (custom-map state id)]
    {:state (assoc-in state [:ui :dialogs :confirm]
                      {:open? true
                       :title (str "Delete \"" (:name m) "\"?")
                       :message (delete-message state id)
                       :confirm-label "Delete map"
                       :danger? true
                       :confirm-event {:event/type :zone-maps/delete :map id}})}
    {:state state}))

(defn- handle-delete
  "Delete your map, its learned keys, its pad setting and a switch waiting
   for it; while it was in use the show switches to Left / Right. One undo
   step brings the map, its keys and its setting back, not the switch:
   [:zone-map :active] is live (06 §5)."
  [{:keys [state] id :map}]
  (if (custom-map state id)
    {:state (cond-> (-> state
                        (update :zone-maps dissoc id)
                        (update-in [:config :input :trigger-map] #(apply dissoc % (map-keys state id))))
              (contains? (get-in state [:config :zone-map-pads]) id) (update-in [:config :zone-map-pads] dissoc id)
              (= id (get-in state [:zone-map :pending :map])) (assoc-in [:zone-map :pending] nil)
              (= id (get-in state [:zone-map :active])) (zone-maps/commit zone-maps/default-map-id)
              (= id (get-in state [:ui :zone-maps :selected])) (select-map nil))}
    {:state state}))

(defn- handle-select
  "The map the Zone Groups tab's editor shows."
  [{:keys [state] id :map}]
  {:state (select-map state id)})


;; A switch

(defn- handle-pointer
  "A pad's mouse or touch: its press presses the map, waiting for the bar
   with Shift; anything else does nothing (vibes/pointer-action)."
  [{:keys [map fx/event state]}]
  (if (= :press (vibes/pointer-action event))
    {:dispatch {:event/type :zone-map/press :map map
                :bar? (boolean (and (instance? MouseEvent event) (.isShiftDown ^MouseEvent event)))
                :src :pad}}
    {:state state}))

(defn- pad-map?
  "Is id one a pad presses: a map of state's show, or :cycle (the CYCLE
   pad, zones plan Z5: it resumes the Map cycle)?"
  [state id]
  (or (= :cycle id) (some? (zone-maps/map-by-id state id))))

(defn- handle-press
  "A press of map's pad, or of its learned key (:input-time, when it was
   played): the show clock's beats are read, then :zone-map/schedule. An
   unknown map changes nothing; :cycle is the CYCLE pad's."
  [{:keys [map bar? input-time state]}]
  (if (pad-map? state map)
    {:show-clock/read {:on-read {:event/type :zone-map/schedule :map map :bar? (boolean bar?) :input-time input-time}}}
    {:state state}))

(defn- handle-schedule
  "The press, with the show clock read (:clock): at once, on the next beat,
   or on the next bar with Shift or the pad's setting
   ([:config :zone-map-pads map :bar?]) (routing.zone-maps/schedule)."
  [{:keys [map bar? input-time clock state]}]
  (if (pad-map? state map)
    {:state (zone-maps/schedule state map
                                (or (boolean bar?) (true? (get-in state [:config :zone-map-pads map :bar?])))
                                (assoc clock :input-time input-time))}
    {:state state}))

(defn- handle-use-now
  "The map editor's Use now: an edit, not a performance, so at once (spec
   §9). An unknown map changes nothing."
  [{:keys [map state]}]
  {:state (if (zone-maps/map-by-id state map) (zone-maps/commit state map) state)})

(defn- handle-set-pad-bar
  "A pad's \"Switch on the bar\": its presses, and its keys', wait for the
   next bar."
  [{:keys [map bar? state]}]
  (if map
    {:state (if bar?
              (assoc-in state [:config :zone-map-pads map :bar?] true)
              (update-in state [:config :zone-map-pads] dissoc map))}
    {:state state}))

(defn- handle-clear-control
  "The keys learned for map's pad go."
  [{:keys [map state]}]
  {:state (update-in state [:config :input :trigger-map]
                     #(into {} (remove (fn [[_ action]] (and (= :zone-map (:type action)) (= map (:target action))))) %))})


;; Public API

(defn handle
  "Dispatch :zones/*, :zone-maps/* and :zone-map/* events."
  [{:keys [event/type] :as event}]
  (case type
    :zones/set-default-target (handle-set-default-target event)
    :zones/set-label (handle-set-label event)
    :zone-maps/create (handle-create event)
    :zone-maps/duplicate (handle-duplicate event)
    :zone-maps/rename (handle-rename event)
    :zone-maps/set-lane (handle-set-lane event)
    :zone-maps/add-lane (handle-add-lane event)
    :zone-maps/remove-lane (handle-remove-lane event)
    :zone-maps/set-override (handle-set-override event)
    :zone-maps/request-delete (handle-request-delete event)
    :zone-maps/delete (handle-delete event)
    :zone-maps/select (handle-select event)
    :zone-maps/use-now (handle-use-now event)
    :zone-map/pointer (handle-pointer event)
    :zone-map/press (handle-press event)
    :zone-map/schedule (handle-schedule event)
    :zone-map/set-pad-bar (handle-set-pad-bar event)
    :zone-map/clear-control (handle-clear-control event)
    {}))
