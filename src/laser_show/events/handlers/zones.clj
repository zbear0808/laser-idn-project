(ns laser-show.events.handlers.zones
  "Event handlers for zone management.
   
   Zones are output destinations associated with projectors. Each projector
   has 3 zones automatically created: default, graphics, crowd-scanning.
   
   Zone effects are stored in [:chains :zone-effects zone-id :items]."
  (:require [laser-show.events.helpers :as h]))


;; Zone Selection


(defn- handle-zones-select-zone
  "Select a zone for editing."
  [{:keys [zone-id state]}]
  {:state (-> state
              (assoc-in [:zones :selected-zone] zone-id)
              ;; Clear zone group selection when zone is selected
              (assoc-in [:zone-groups :selected-group] nil))})


;; Zone Enable/Disable


(defn- handle-zones-toggle-enabled
  "Toggle a zone's enabled state."
  [{:keys [zone-id state]}]
  {:state (-> state
              (update-in [:zones :items zone-id :enabled?] not)
              h/mark-dirty)})


;; Zone Group Assignment


(defn- handle-zones-toggle-zone-group
  "Toggle a zone's membership in a zone group."
  [{:keys [zone-id group-id state]}]
  (let [current-groups (get-in state [:zones :items zone-id :zone-groups] [])
        member? (some #{group-id} current-groups)
        new-groups (if member?
                     (vec (remove #{group-id} current-groups))
                     (conj current-groups group-id))]
    {:state (-> state
                (assoc-in [:zones :items zone-id :zone-groups] new-groups)
                h/mark-dirty)}))

(defn- handle-zones-set-zone-groups
  "Set all zone groups for a zone.
   Also used for reordering - the order determines priority."
  [{:keys [zone-id zone-groups state]}]
  {:state (-> state
              (assoc-in [:zones :items zone-id :zone-groups] zone-groups)
              h/mark-dirty)})


;; Zone Settings


(defn- handle-zones-update-settings
  "Update zone settings (name, enabled, etc)."
  [{:keys [zone-id updates state]}]
  {:state (-> state
              (update-in [:zones :items zone-id] merge updates)
              h/mark-dirty)})

(defn- handle-zones-rename
  "Rename a zone."
  [{:keys [zone-id name state]}]
  {:state (-> state
              (assoc-in [:zones :items zone-id :name] name)
              h/mark-dirty)})


;; Zone Effects Editor


(defn- handle-zones-edit-effects
  "Open dialog to edit zone effects (geometry calibration).
   TODO: Implement zone effects editor dialog."
  [{:keys [zone-id state]}]
  ;; For now just log - dialog will be added later
  (println "Edit zone effects for zone:" zone-id)
  {:state state})


;; Public API


(defn handle
  "Dispatch zone events to their handlers.
   
   Accepts events with :event/type in the :zones/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    :zones/select-zone (handle-zones-select-zone event)
    :zones/toggle-enabled (handle-zones-toggle-enabled event)
    :zones/toggle-zone-group (handle-zones-toggle-zone-group event)
    :zones/set-zone-groups (handle-zones-set-zone-groups event)
    :zones/reorder-zone-groups (handle-zones-set-zone-groups event)  ; alias - order is priority
    :zones/update-settings (handle-zones-update-settings event)
    :zones/rename (handle-zones-rename event)
    :zones/edit-effects (handle-zones-edit-effects event)
    ;; Unknown event in this domain
    {}))
