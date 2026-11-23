(ns laser-show.events.handlers.zone-groups
  "Event handlers for zone group management.
   
   Default groups: :graphics and :crowd. All and the automatic groups are
   built in (laser-show.routing.rig) and can't be deleted.
   Deleting a group asks first (the shared confirm dialog), and re-targets
   every cue, effect and preview reference to it (routing.refs)."
  (:require [clojure.string :as str]
            [laser-show.events.helpers :as h]
            [laser-show.routing.refs :as refs]
            [laser-show.routing.rig :as rig]
            [laser-show.routing.targets :as targets]
            [laser-show.common.util :as u]))


(defn- handle-zone-groups-select
  "Select a zone group for viewing/editing."
  [{:keys [group-id state]}]
  {:state (-> state
              (assoc-in [:zone-group-ui :selected-group] group-id)
              ;; Clear projector/VP selection when zone group is selected
              (assoc-in [:projector-ui :active-projector] nil)
              (assoc-in [:projector-ui :active-virtual-projector] nil))})

(defn- handle-zone-groups-show
  "Open a group (All, an automatic group, a laser's or yours) in the Zone
   Groups tab, as a click on a row's name under the cards does. The card
   stays selected."
  [{:keys [group-id state]}]
  {:state (-> state
              (assoc-in [:zone-group-ui :selected-group] group-id)
              (assoc-in [:ui :active-tab] :zones))})


(defn- handle-zone-groups-add
  "Open dialog to add a new zone group."
  [{:keys [state]}]
  {:state (-> state
              (update-in [:ui :dialogs :zone-group-editor] merge
                         {:open? true
                          :editing? false
                          :group-id nil
                          :name ""
                          :description ""
                          :color "#808080"}))})

(defn unique-group-id
  "A new zone group's id from its name: :group- and the name in lower case,
   with every run of characters other than a-z and 0-9 made one -
   (\"Stage Left\" → :group-stage-left), or :group for a name with none.
   When that id is taken, -2, -3 … is added. The group- prefix keeps it clear
   of the built-in ids (:all :left :right :center :graphics :crowd), and it
   doesn't depend on the time, so two creates in one millisecond differ."
  [state group-name]
  (let [slug (-> (str/lower-case (or group-name ""))
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-+|-+$" ""))
        base (if (str/blank? slug) "group" (str "group-" slug))
        taken? #(contains? (:zone-groups state) %)]
    (->> (cons (keyword base) (map #(keyword (str base "-" %)) (iterate inc 2)))
         (remove taken?)
         first)))

(defn- handle-zone-groups-create-new
  "Create a new zone group from dialog data."
  [{:keys [name description color state]
    :or {description "" color "#808080"}}]
  (let [group-id (unique-group-id state name)
        new-group {:id group-id
                   :name (or name "New Group")
                   :description description
                   :color color}]
    {:state (-> state
                (assoc-in [:zone-groups group-id] new-group)
                (assoc-in [:zone-group-ui :selected-group] group-id)
                (assoc-in [:ui :dialogs :zone-group-editor :open?] false))}))

(defn- strip-membership
  "Every projector's and virtual projector's :zone-groups without group-id."
  [state group-id]
  (let [strip (fn [outputs]
                (reduce-kv (fn [m id output]
                             (assoc m id (update output :zone-groups #(vec (remove #{group-id} %)))))
                           {}
                           outputs))]
    (-> state
        (update :projectors #(strip (or % {})))
        (update :virtual-projectors #(strip (or % {}))))))

(defn plural [n one many]
  (str n " " (if (= 1 n) one many)))

(defn- group-name [state group-id]
  (rig/ref-name state group-id))

(defn cue-list-text
  "The cues as their cells and names, \"A1 Intro · C2 Drop\": at most six, then
   \"and n more\"."
  [state cells]
  (let [shown (take 6 cells)
        label (fn [[col row :as cell]]
                (let [cue-name (get-in state [:chains :cue-chains cell :name])]
                  (str (u/cell-label col row) (when-not (str/blank? cue-name) (str " " cue-name)))))
        more (- (count cells) (count shown))]
    (str/join " · " (cond-> (mapv label shown)
                      (pos? more) (conj (str "and " more " more"))))))

(defn retarget-options
  "Where the cues of a group or laser that goes (ref) can go: every target
   the pickers list (routing.rig/target-groups: All first, the automatic
   groups, each laser, your groups by name), and Nothing (they inherit).
   Never ref itself."
  [state ref]
  (-> (into []
            (comp (remove #(= ref (:id %)))
                  (map (fn [g] {:value (:id g) :text (:name g)})))
            (rig/target-groups state))
      (conj {:value :inherit :text "Nothing (they inherit)"})))

(defn columns-using
  "The grid columns whose default target names ref (zones plan Z4), in
   order."
  [state ref]
  (sort (keep (fn [[col t]] (when (some #{ref} (targets/target-refs t)) col))
              (get-in state [:grid :column-targets]))))

(defn- and-list
  "\"a\", \"a and b\", \"a, b and c\"."
  [parts]
  (if (next parts)
    (str (str/join ", " (butlast parts)) " and " (last parts))
    (first parts)))

(defn maps-using
  "The names of your zone maps with a lane or an override that names ref
   (zones plan Z4), by name."
  [state ref]
  (sort (keep (fn [[_ m]] (when (some #{ref} (concat (:lanes m) (vals (:overrides m)))) (:name m)))
              (:zone-maps state))))

(defn uses-text
  "What a delete's question says uses a group: \"3 cues, column 3, the show
   default, the map Crowd sweep and 2 projectors use it.\", \"1 cue uses
   it.\", \"Nothing uses it.\" (cues, the columns using it, whether the
   show default does, the names of the zone maps using it, how many outputs
   are in it)."
  ([cue-count cols default? outputs]
   (uses-text cue-count cols default? [] outputs))
  ([cue-count cols default? maps outputs]
   (let [parts (cond-> []
                 (pos? cue-count) (conj (plural cue-count "cue" "cues"))
                 (seq cols) (conj (if (next cols)
                                    (str "columns " (and-list (map #(str (inc %)) cols)))
                                    (str "column " (inc (first cols)))))
                 default? (conj "the show default")
                 (seq maps) (conj (if (next maps)
                                    (str "the maps " (and-list maps))
                                    (str "the map " (first maps))))
                 (pos? outputs) (conj (plural outputs "projector" "projectors")))
         one? (and (= 1 (count parts))
                   (or (= 1 cue-count) (= 1 (count cols)) default? (= 1 (count maps)) (= 1 outputs)))]
     (if (empty? parts)
       "Nothing uses it."
       (let [text (and-list parts)]
         (str (str/upper-case (subs text 0 1)) (subs text 1) (if one? " uses it." " use it.")))))))

(defn- handle-zone-groups-request-remove
  "Ask before deleting a zone group: which cues, grid columns, the show
   default, zone maps (zones plan Z4) and projectors use it, and, when
   anything routes to it, where to send it (All by default; Nothing leaves
   a map's lane empty, 06 SQ-13). :all can't be deleted: for :all
   or an unknown group, state is returned as it is."
  [{:keys [group-id state]}]
  (if (or (= :all group-id) (not (contains? (:zone-groups state) group-id)))
    {:state state}
    (let [cues (refs/cues-using (get-in state [:chains :cue-chains]) group-id)
          cols (columns-using state group-id)
          default? (boolean (some #{group-id} (targets/target-refs (get-in state [:config :default-target]))))
          maps (maps-using state group-id)
          outputs (count (filter #(some #{group-id} (:zone-groups %))
                                 (concat (vals (:projectors state)) (vals (:virtual-projectors state)))))
          message (uses-text (count cues) cols default? maps outputs)]
      {:state (assoc-in state [:ui :dialogs :confirm]
                        (cond-> {:open? true
                                 :title (str "Delete \"" (group-name state group-id) "\"?")
                                 :message message
                                 :confirm-label "Delete group"
                                 :danger? true
                                 :confirm-event {:event/type :zone-groups/remove :group-id group-id}}
                          (seq cues) (assoc :details (cue-list-text state cues))
                          (or (seq cues) (seq cols) default? (seq maps))
                          (assoc :choice {:key :retarget-to
                                          :label "Send its cues to:"
                                          :options (retarget-options state group-id)}
                                 :choice-value :all)))})))

(defn retarget-value
  "Where the references to ref (a group or laser that goes) go:
   :retarget-to, All when it's absent, ref itself or nothing a cue can be
   sent to (routing.rig/known-ref?: All, an automatic group, a laser that
   exists, a stored group); nil (they inherit) for :inherit or an explicit
   nil."
  [state ref event]
  (let [target (get event :retarget-to :all)]
    (cond
      (or (nil? target) (= :inherit target)) nil
      (or (= target ref) (not (rig/known-ref? state target))) :all
      :else target)))

(defn- handle-zone-groups-remove
  "Delete a zone group: it leaves every projector and virtual projector, and
   every cue, effect and preview reference to it goes to :retarget-to (see
   retarget-value). One event, so one undo step. :all can't be deleted:
   for :all or an unknown group, state is returned as it is."
  [{:keys [group-id state] :as event}]
  (if (or (= :all group-id) (not (contains? (:zone-groups state) group-id)))
    {:state state}
    (let [target (retarget-value state group-id event)
          cue-count (count (refs/cues-using (get-in state [:chains :cue-chains]) group-id))
          deleted-name (group-name state group-id)
          selected (get-in state [:zone-group-ui :selected-group])
          new-state (-> state
                        (strip-membership group-id)
                        (refs/replace-refs {group-id target})
                        (update :zone-groups dissoc group-id)
                        (assoc-in [:zone-group-ui :selected-group] (when-not (= selected group-id) selected)))
          notice (str "Deleted " deleted-name
                      (when (pos? cue-count)
                        (str " · " (plural cue-count "cue" "cues") " now "
                             (if target (str "go to " (group-name state target)) "inherit"))))]
      (h/show-notice new-state notice :info))))



;; Zone Group Edit Dialog


(defn- handle-zone-groups-edit
  "Open dialog to edit an existing zone group. All and the automatic groups
   can't be edited: state is returned as it is for them."
  [{:keys [group-id state]}]
  (if (rig/derived-ref? group-id)
    {:state state}
    (let [group (get-in state [:zone-groups group-id])]
      {:state (-> state
                  (update-in [:ui :dialogs :zone-group-editor] merge
                             {:open? true
                              :editing? true
                              :group-id group-id
                              :name (:name group "")
                              :description (:description group "")
                              :color (:color group "#808080")}))})))

(defn- handle-zone-groups-save-edit
  "Save changes to an existing zone group from dialog. All and the automatic
   groups are never stored: state is returned as it is for them."
  [{:keys [group-id name description color state]}]
  (if (rig/derived-ref? group-id)
    {:state state}
    {:state (-> state
                (update-in [:zone-groups group-id] merge
                           {:name name
                            :description description
                            :color color})
                (assoc-in [:ui :dialogs :zone-group-editor :open?] false))}))

(defn- handle-zone-groups-set-editor-color
  "Set color in the zone group editor dialog (from color swatch click)."
  [{:keys [color state]}]
  {:state (assoc-in state [:ui :dialogs :zone-group-editor :color] color)})


;; Public API


(defn handle
  "Dispatch zone-group events to their handlers.
   
   Accepts events with :event/type in the :zone-groups/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    :zone-groups/select (handle-zone-groups-select event)
    :zone-groups/show (handle-zone-groups-show event)
    :zone-groups/add (handle-zone-groups-add event)
    :zone-groups/create-new (handle-zone-groups-create-new event)
    :zone-groups/request-remove (handle-zone-groups-request-remove event)
    :zone-groups/remove (handle-zone-groups-remove event)
    :zone-groups/edit (handle-zone-groups-edit event)
    :zone-groups/save-edit (handle-zone-groups-save-edit event)
    :zone-groups/set-editor-color (handle-zone-groups-set-editor-color event)
    ;; Unknown event in this domain
    {}))
