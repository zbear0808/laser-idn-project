(ns laser-show.events.handlers.zone-groups
  "Event handlers for zone group management.
   
   SIMPLIFIED ARCHITECTURE (v2):
   Zone groups are routing targets that projectors and virtual projectors
   can be assigned to. Users assign projectors to groups, then target
   cues to zone groups rather than individual projectors.
   
   Default groups: :all, :left, :right, :center, :graphics, :crowd"
  (:require [laser-show.events.helpers :as h]))


;; Zone Group Selection


(defn- handle-zone-groups-select
  "Select a zone group for viewing/editing."
  [{:keys [group-id state]}]
  {:state (-> state
              (assoc-in [:zone-group-ui :selected-group] group-id)
              ;; Clear projector/VP selection when zone group is selected
              (assoc-in [:projectors :active-projector] nil)
              (assoc-in [:projectors :active-virtual-projector] nil))})


;; Zone Group CRUD


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
                (assoc-in [:ui :dialogs :zone-group-editor :open?] false)
                h/mark-dirty)}))

(defn- handle-zone-groups-remove
  "Remove a zone group. Also removes this group from all projectors and virtual projectors."
  [{:keys [group-id state]}]
  (let [;; Remove group from zone-groups domain
        new-zone-groups (dissoc (get state :zone-groups) group-id)
        ;; Remove group from all projectors that have it
        projectors (get-in state [:projectors :items] {})
        updated-projectors (reduce-kv
                             (fn [m proj-id proj]
                               (let [current-groups (:zone-groups proj [])
                                     new-groups (vec (remove #{group-id} current-groups))]
                                 (assoc m proj-id (assoc proj :zone-groups new-groups))))
                             {}
                             projectors)
        ;; Remove group from all virtual projectors that have it
        vps (get-in state [:projectors :virtual-projectors] {})
        updated-vps (reduce-kv
                      (fn [m vp-id vp]
                        (let [current-groups (:zone-groups vp [])
                              new-groups (vec (remove #{group-id} current-groups))]
                          (assoc m vp-id (assoc vp :zone-groups new-groups))))
                      {}
                      vps)
        ;; Clear selection if this was selected
        selected (get-in state [:zone-group-ui :selected-group])
        new-selected (if (= selected group-id) nil selected)]
    {:state (-> state
                (assoc :zone-groups new-zone-groups)
                (assoc-in [:zone-group-ui :selected-group] new-selected)
                (assoc-in [:projectors :items] updated-projectors)
                (assoc-in [:projectors :virtual-projectors] updated-vps)
                h/mark-dirty)}))

(defn- handle-zone-groups-update
  "Update zone group properties (name, description, color)."
  [{:keys [group-id updates state]}]
  {:state (-> state
              (update-in [:zone-groups group-id] merge updates)
              h/mark-dirty)})

(defn- handle-zone-groups-duplicate
  "Duplicate a zone group with a new name."
  [{:keys [group-id state]}]
  (let [original (get-in state [:zone-groups group-id])
        new-id (keyword (str "group-" (System/currentTimeMillis)))
        new-group (-> original
                      (assoc :id new-id)
                      (update :name str " (copy)"))]
    {:state (-> state
                (assoc-in [:zone-groups new-id] new-group)
                (assoc-in [:zone-group-ui :selected-group] new-id)
                h/mark-dirty)}))


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
              (assoc-in [:ui :dialogs :zone-group-editor :open?] false)
              h/mark-dirty)})

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
    :zone-groups/remove (handle-zone-groups-remove event)
    :zone-groups/update (handle-zone-groups-update event)
    :zone-groups/duplicate (handle-zone-groups-duplicate event)
    :zone-groups/edit (handle-zone-groups-edit event)
    :zone-groups/save-edit (handle-zone-groups-save-edit event)
    :zone-groups/set-editor-color (handle-zone-groups-set-editor-color event)
    ;; Unknown event in this domain
    {}))
