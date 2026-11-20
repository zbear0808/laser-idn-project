(ns laser-show.events.handlers.zone-groups
  "Event handlers for zone group management.
   
   Default groups: :all, :left, :right, :center, :graphics, :crowd.
   Deleting a group asks first (the shared confirm dialog), and re-targets
   every cue, effect and preview reference to it (routing.refs)."
  (:require [clojure.string :as str]
            [laser-show.events.helpers :as h]
            [laser-show.routing.refs :as refs]
            [laser-show.views.dialogs.common :as common]))


(defn- handle-zone-groups-select
  "Select a zone group for viewing/editing."
  [{:keys [group-id state]}]
  {:state (-> state
              (assoc-in [:zone-group-ui :selected-group] group-id)
              ;; Clear projector/VP selection when zone group is selected
              (assoc-in [:projector-ui :active-projector] nil)
              (assoc-in [:projector-ui :active-virtual-projector] nil))})


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

(defn- handle-zone-groups-create-new
  "Create a new zone group from dialog data."
  [{:keys [name description color state]
    :or {description "" color "#808080"}}]
  (let [group-id (keyword (str "group-" (System/currentTimeMillis)))
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

(defn- plural [n one many]
  (str n " " (if (= 1 n) one many)))

(defn- group-name [state group-id]
  (get-in state [:zone-groups group-id :name] (name group-id)))

(defn- cue-list-text
  "The cues as their cells and names, \"A1 Intro · C2 Drop\": at most six, then
   \"and n more\"."
  [state cells]
  (let [shown (take 6 cells)
        label (fn [[col row :as cell]]
                (let [cue-name (get-in state [:chains :cue-chains cell :name])]
                  (str (common/cell-label col row) (when-not (str/blank? cue-name) (str " " cue-name)))))
        more (- (count cells) (count shown))]
    (str/join " · " (cond-> (mapv label shown)
                      (pos? more) (conj (str "and " more " more"))))))

(defn- retarget-options
  "Where a deleted group's cues can go: All, the other groups by name, and
   Nothing (they inherit). Never the group itself."
  [state group-id]
  (let [groups (:zone-groups state)
        others (->> (dissoc groups group-id :all)
                    (sort-by (fn [[id g]] [(str/lower-case (or (:name g) "")) (name id)]))
                    (mapv (fn [[id g]] {:value id :text (or (:name g) (name id))})))]
    (-> (if (contains? groups :all) [{:value :all :text (group-name state :all)}] [])
        (into others)
        (conj {:value :inherit :text "Nothing (they inherit)"}))))

(defn- handle-zone-groups-request-remove
  "Ask before deleting a zone group: which cues and projectors use it, and,
   when cues do, where to send them (All by default). :all can't be deleted:
   for :all or an unknown group, state is returned as it is."
  [{:keys [group-id state]}]
  (if (or (= :all group-id) (not (contains? (:zone-groups state) group-id)))
    {:state state}
    (let [cues (refs/cues-using (get-in state [:chains :cue-chains]) group-id)
          outputs (count (filter #(some #{group-id} (:zone-groups %))
                                 (concat (vals (:projectors state)) (vals (:virtual-projectors state)))))
          message (cond
                    (and (seq cues) (pos? outputs))
                    (str (plural (count cues) "cue" "cues") " and " (plural outputs "projector" "projectors") " use it.")
                    (seq cues) (str (plural (count cues) "cue" "cues") (if (= 1 (count cues)) " uses it." " use it."))
                    (pos? outputs) (str (plural outputs "projector" "projectors") (if (= 1 outputs) " uses it." " use it."))
                    :else "Nothing uses it.")]
      {:state (assoc-in state [:ui :dialogs :confirm]
                        (cond-> {:open? true
                                 :title (str "Delete \"" (group-name state group-id) "\"?")
                                 :message message
                                 :confirm-label "Delete group"
                                 :danger? true
                                 :confirm-event {:event/type :zone-groups/remove :group-id group-id}}
                          (seq cues) (assoc :details (cue-list-text state cues)
                                            :choice {:key :retarget-to
                                                     :label "Send its cues to:"
                                                     :options (retarget-options state group-id)}
                                            :choice-value :all)))})))

(defn- retarget-value
  "Where the deleted group's references go: :retarget-to, All when it's
   absent, the deleted group itself or no group; nil (they inherit) for
   :inherit or an explicit nil."
  [state group-id event]
  (let [target (get event :retarget-to :all)]
    (cond
      (or (nil? target) (= :inherit target)) nil
      (or (= target group-id) (not (contains? (:zone-groups state) target))) :all
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
  "Open dialog to edit an existing zone group."
  [{:keys [group-id state]}]
  (let [group (get-in state [:zone-groups group-id])]
    {:state (-> state
                (update-in [:ui :dialogs :zone-group-editor] merge
                           {:open? true
                            :editing? true
                            :group-id group-id
                            :name (:name group "")
                            :description (:description group "")
                            :color (:color group "#808080")}))}))

(defn- handle-zone-groups-save-edit
  "Save changes to an existing zone group from dialog."
  [{:keys [group-id name description color state]}]
  {:state (-> state
              (update-in [:zone-groups group-id] merge
                         {:name name
                          :description description
                          :color color})
              (assoc-in [:ui :dialogs :zone-group-editor :open?] false))})

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
    :zone-groups/add (handle-zone-groups-add event)
    :zone-groups/create-new (handle-zone-groups-create-new event)
    :zone-groups/request-remove (handle-zone-groups-request-remove event)
    :zone-groups/remove (handle-zone-groups-remove event)
    :zone-groups/edit (handle-zone-groups-edit event)
    :zone-groups/save-edit (handle-zone-groups-save-edit event)
    :zone-groups/set-editor-color (handle-zone-groups-set-editor-color event)
    ;; Unknown event in this domain
    {}))
