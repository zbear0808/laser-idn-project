(ns laser-show.ui.zone-group-config
  "Zone group configuration dialog.
   UI for managing zone groups."
  (:require [seesaw.core :as ss]
            [seesaw.mig :as mig]
            [seesaw.border :as border]
            [laser-show.backend.zone-groups :as zone-groups]
            [laser-show.backend.zones :as zones])
  (:import [java.awt Color Font]
           [javax.swing JOptionPane]))

;; ============================================================================
;; Zone Group List Panel
;; ============================================================================

(defn- create-group-list-model
  "Create a list model from zone groups."
  []
  (vec (zone-groups/list-groups)))

(defn- group-renderer
  "Custom renderer for zone group list items."
  [renderer {:keys [value]}]
  (when value
    (let [zone-count (count (:zone-ids value))
          type-str (name (or (:type value) :custom))]
      (ss/config! renderer 
                  :text (str (:name value) " (" zone-count " zones) [" type-str "]")
                  :foreground (Color. 200 200 255)))))

;; ============================================================================
;; Zone Selection Panel
;; ============================================================================

(defn- create-zone-checkboxes
  "Create checkboxes for all available zones."
  [selected-zone-ids]
  (let [all-zones (zones/list-zones)]
    (into {}
          (map (fn [zone]
                 [(:id zone) 
                  (ss/checkbox :text (:name zone)
                               :selected? (contains? selected-zone-ids (:id zone)))])
               all-zones))))

(defn- get-selected-zone-ids
  "Get the set of selected zone IDs from checkboxes."
  [zone-checkboxes]
  (set (filter (fn [zone-id]
                 (ss/value (get zone-checkboxes zone-id)))
               (keys zone-checkboxes))))

;; ============================================================================
;; Add/Edit Zone Group Dialog
;; ============================================================================

(defn- show-group-dialog
  "Show dialog for adding or editing a zone group.
   Returns the group map if OK was clicked, nil otherwise."
  [parent & {:keys [group title]
             :or {title "Add Zone Group"}}]
  (let [id-field (ss/text :text (if group (name (:id group)) "")
                          :columns 20
                          :editable? (nil? group))
        name-field (ss/text :text (or (:name group) "")
                            :columns 20)
        type-combo (ss/combobox :model [:custom :spatial :functional :preset]
                                :selected-item (or (:type group) :custom))
        
        zone-checkboxes (create-zone-checkboxes (or (:zone-ids group) #{}))
        
        zones-panel (mig/mig-panel
                     :constraints ["wrap 2" "" ""]
                     :items (mapv (fn [[_ cb]] [cb ""]) zone-checkboxes)
                     :border (border/to-border "Zones in Group"))
        
        main-panel (mig/mig-panel
                    :constraints ["" "[grow,fill]" ""]
                    :items [[(mig/mig-panel
                              :constraints ["" "[right][grow,fill]" ""]
                              :items [[(ss/label "ID:") ""]
                                      [id-field "wrap"]
                                      [(ss/label "Name:") ""]
                                      [name-field "wrap"]
                                      [(ss/label "Type:") ""]
                                      [type-combo "wrap"]]) "wrap"]
                            [(ss/scrollable zones-panel 
                                            :preferred-size [350 :by 200]) "wrap"]])]
    
    (let [result (JOptionPane/showConfirmDialog
                  parent
                  main-panel
                  title
                  JOptionPane/OK_CANCEL_OPTION
                  JOptionPane/PLAIN_MESSAGE)]
      (when (= result JOptionPane/OK_OPTION)
        (let [id-str (ss/text id-field)
              id-kw (if (empty? id-str)
                      (keyword (str "group-" (System/currentTimeMillis)))
                      (keyword id-str))]
          (zone-groups/make-zone-group
           id-kw
           (ss/text name-field)
           (get-selected-zone-ids zone-checkboxes)
           :type (ss/selection type-combo)))))))

;; ============================================================================
;; Main Configuration Panel
;; ============================================================================

(defn create-zone-group-config-panel
  "Create the zone group configuration panel.
   
   Returns a map with:
   - :panel - The Swing component
   - :refresh! - Refresh the group list"
  []
  (let [list-model (atom (create-group-list-model))
        
        group-list (ss/listbox :model @list-model
                               :renderer group-renderer)
        
        refresh! (fn []
                   (reset! list-model (create-group-list-model))
                   (ss/config! group-list :model @list-model))
        
        add-btn (ss/button :text "Add"
                           :listen [:action (fn [e]
                                              (when-let [g (show-group-dialog 
                                                            (ss/to-root e)
                                                            :title "Add Zone Group")]
                                                (zone-groups/create-group! g)
                                                (refresh!)))])
        
        edit-btn (ss/button :text "Edit"
                            :listen [:action (fn [e]
                                               (when-let [selected (ss/selection group-list)]
                                                 (when-let [g (show-group-dialog
                                                               (ss/to-root e)
                                                               :group selected
                                                               :title "Edit Zone Group")]
                                                   (zone-groups/update-group! (:id g)
                                                                              (dissoc g :id :created-at))
                                                   (refresh!))))])
        
        remove-btn (ss/button :text "Remove"
                              :listen [:action (fn [e]
                                                 (when-let [selected (ss/selection group-list)]
                                                   (let [confirm (JOptionPane/showConfirmDialog
                                                                  (ss/to-root e)
                                                                  (str "Remove zone group '" (:name selected) "'?")
                                                                  "Confirm Remove"
                                                                  JOptionPane/YES_NO_OPTION)]
                                                     (when (= confirm JOptionPane/YES_OPTION)
                                                       (zone-groups/remove-group! (:id selected))
                                                       (refresh!)))))])
        
        refresh-all-btn (ss/button :text "Refresh All Zones"
                                   :listen [:action (fn [_]
                                                      (zone-groups/refresh-all-zones-group!)
                                                      (refresh!))])
        
        button-panel (ss/horizontal-panel :items [add-btn edit-btn remove-btn refresh-all-btn]
                                          :border (border/empty-border :thickness 5))
        
        main-panel (ss/border-panel
                    :center (ss/scrollable group-list)
                    :south button-panel
                    :border (border/to-border "Zone Groups"))]
    
    {:panel main-panel
     :refresh! refresh!}))

;; ============================================================================
;; Standalone Dialog
;; ============================================================================

(defn show-zone-group-config-dialog
  "Show the zone group configuration dialog."
  [parent]
  (let [config-panel (create-zone-group-config-panel)
        dialog (ss/dialog :title "Configure Zone Groups"
                          :content (:panel config-panel)
                          :size [500 :by 400]
                          :modal? true
                          :parent parent)]
    ((:refresh! config-panel))
    (ss/show! dialog)))
