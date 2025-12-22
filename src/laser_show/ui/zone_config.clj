(ns laser-show.ui.zone-config
  "Zone configuration dialog.
   UI for managing zones with transformations and blocked regions."
  (:require [seesaw.core :as ss]
            [seesaw.mig :as mig]
            [seesaw.border :as border]
            [laser-show.backend.zones :as zones]
            [laser-show.backend.projectors :as projectors])
  (:import [java.awt Color Font]
           [javax.swing JOptionPane]))

;; ============================================================================
;; Zone List Panel
;; ============================================================================

(defn- create-zone-list-model
  "Create a list model from zones."
  []
  (vec (zones/list-zones)))

(defn- zone-renderer
  "Custom renderer for zone list items."
  [renderer {:keys [value]}]
  (when value
    (let [enabled-color (if (:enabled value)
                          (Color. 100 255 100)
                          (Color. 150 150 150))
          tags-str (clojure.string/join ", " (map name (:tags value)))
          proj-name (or (:name (projectors/get-projector (:projector-id value)))
                        (name (:projector-id value)))]
      (ss/config! renderer 
                  :text (str (:name value) " → " proj-name " [" tags-str "]")
                  :foreground enabled-color))))

;; ============================================================================
;; Tag Selection Panel
;; ============================================================================

(defn- create-tag-checkboxes
  "Create checkboxes for zone tags."
  [initial-tags]
  (let [tags [:safe :crowd-scanning :graphics :effects :restricted]]
    (into {}
          (map (fn [tag]
                 [tag (ss/checkbox :text (name tag)
                                   :selected? (contains? initial-tags tag))])
               tags))))

(defn- get-selected-tags
  "Get the set of selected tags from checkboxes."
  [tag-checkboxes]
  (set (filter (fn [tag]
                 (ss/value (get tag-checkboxes tag)))
               (keys tag-checkboxes))))

;; ============================================================================
;; Transformation Panel
;; ============================================================================

(defn- create-viewport-panel
  "Create panel for viewport configuration."
  [initial-viewport]
  (let [x-min (ss/spinner :model (ss/spinner-model 
                                  (or (:x-min initial-viewport) -1.0)
                                  :from -1.0 :to 1.0 :by 0.1))
        x-max (ss/spinner :model (ss/spinner-model
                                  (or (:x-max initial-viewport) 1.0)
                                  :from -1.0 :to 1.0 :by 0.1))
        y-min (ss/spinner :model (ss/spinner-model
                                  (or (:y-min initial-viewport) -1.0)
                                  :from -1.0 :to 1.0 :by 0.1))
        y-max (ss/spinner :model (ss/spinner-model
                                  (or (:y-max initial-viewport) 1.0)
                                  :from -1.0 :to 1.0 :by 0.1))]
    {:panel (mig/mig-panel
             :constraints ["" "[right][grow,fill][right][grow,fill]" ""]
             :items [[(ss/label "X Min:") ""]
                     [x-min ""]
                     [(ss/label "X Max:") ""]
                     [x-max "wrap"]
                     [(ss/label "Y Min:") ""]
                     [y-min ""]
                     [(ss/label "Y Max:") ""]
                     [y-max "wrap"]]
             :border (border/to-border "Viewport"))
     :get-value (fn []
                  {:x-min (ss/value x-min)
                   :x-max (ss/value x-max)
                   :y-min (ss/value y-min)
                   :y-max (ss/value y-max)})}))

(defn- create-transform-panel
  "Create panel for scale, offset, rotation."
  [initial-transforms]
  (let [scale-x (ss/spinner :model (ss/spinner-model
                                    (or (get-in initial-transforms [:scale :x]) 1.0)
                                    :from 0.1 :to 2.0 :by 0.1))
        scale-y (ss/spinner :model (ss/spinner-model
                                    (or (get-in initial-transforms [:scale :y]) 1.0)
                                    :from 0.1 :to 2.0 :by 0.1))
        offset-x (ss/spinner :model (ss/spinner-model
                                     (or (get-in initial-transforms [:offset :x]) 0.0)
                                     :from -1.0 :to 1.0 :by 0.1))
        offset-y (ss/spinner :model (ss/spinner-model
                                     (or (get-in initial-transforms [:offset :y]) 0.0)
                                     :from -1.0 :to 1.0 :by 0.1))
        rotation (ss/spinner :model (ss/spinner-model
                                     (or (:rotation initial-transforms) 0.0)
                                     :from -6.28 :to 6.28 :by 0.1))]
    {:panel (mig/mig-panel
             :constraints ["" "[right][grow,fill][right][grow,fill]" ""]
             :items [[(ss/label "Scale X:") ""]
                     [scale-x ""]
                     [(ss/label "Scale Y:") ""]
                     [scale-y "wrap"]
                     [(ss/label "Offset X:") ""]
                     [offset-x ""]
                     [(ss/label "Offset Y:") ""]
                     [offset-y "wrap"]
                     [(ss/label "Rotation (rad):") ""]
                     [rotation "span 3, wrap"]]
             :border (border/to-border "Transformations"))
     :get-value (fn []
                  {:scale {:x (ss/value scale-x) :y (ss/value scale-y)}
                   :offset {:x (ss/value offset-x) :y (ss/value offset-y)}
                   :rotation (ss/value rotation)})}))

;; ============================================================================
;; Add/Edit Zone Dialog
;; ============================================================================

(defn- show-zone-dialog
  "Show dialog for adding or editing a zone.
   Returns the zone map if OK was clicked, nil otherwise."
  [parent & {:keys [zone title]
             :or {title "Add Zone"}}]
  (let [projector-list (projectors/list-projectors)
        projector-ids (map :id projector-list)
        
        id-field (ss/text :text (if zone (name (:id zone)) "")
                          :columns 20
                          :editable? (nil? zone))
        name-field (ss/text :text (or (:name zone) "")
                            :columns 20)
        projector-combo (ss/combobox :model (vec projector-ids)
                                     :selected-item (or (:projector-id zone)
                                                        (first projector-ids)))
        priority-field (ss/spinner :model (ss/spinner-model
                                           (or (:priority zone) 1)
                                           :from 1 :to 100 :by 1))
        enabled-check (ss/checkbox :text "Enabled"
                                   :selected? (if zone (:enabled zone) true))
        
        tag-checkboxes (create-tag-checkboxes (or (:tags zone) zones/default-tags))
        tags-panel (mig/mig-panel
                    :constraints ["wrap 3" "" ""]
                    :items (mapv (fn [[_ cb]] [cb ""]) tag-checkboxes)
                    :border (border/to-border "Tags"))
        
        viewport-panel (create-viewport-panel (get-in zone [:transformations :viewport]))
        transform-panel (create-transform-panel (:transformations zone))
        
        main-panel (mig/mig-panel
                    :constraints ["" "[grow,fill]" ""]
                    :items [[(mig/mig-panel
                              :constraints ["" "[right][grow,fill]" ""]
                              :items [[(ss/label "ID:") ""]
                                      [id-field "wrap"]
                                      [(ss/label "Name:") ""]
                                      [name-field "wrap"]
                                      [(ss/label "Projector:") ""]
                                      [projector-combo "wrap"]
                                      [(ss/label "Priority:") ""]
                                      [priority-field "wrap"]
                                      [enabled-check "span 2, wrap"]]) "wrap"]
                            [tags-panel "wrap"]
                            [(:panel viewport-panel) "wrap"]
                            [(:panel transform-panel) "wrap"]])]
    
    (let [result (JOptionPane/showConfirmDialog
                  parent
                  (ss/scrollable main-panel :preferred-size [450 :by 500])
                  title
                  JOptionPane/OK_CANCEL_OPTION
                  JOptionPane/PLAIN_MESSAGE)]
      (when (= result JOptionPane/OK_OPTION)
        (let [id-str (ss/text id-field)
              id-kw (if (empty? id-str)
                      (keyword (str "zone-" (System/currentTimeMillis)))
                      (keyword id-str))
              viewport ((:get-value viewport-panel))
              transforms ((:get-value transform-panel))]
          (zones/make-zone
           id-kw
           (ss/text name-field)
           (ss/selection projector-combo)
           :tags (get-selected-tags tag-checkboxes)
           :transformations (assoc transforms :viewport viewport)
           :blocked-regions (or (:blocked-regions zone) [])
           :priority (ss/value priority-field)
           :enabled (ss/value enabled-check)))))))

;; ============================================================================
;; Main Configuration Panel
;; ============================================================================

(defn create-zone-config-panel
  "Create the zone configuration panel.
   
   Returns a map with:
   - :panel - The Swing component
   - :refresh! - Refresh the zone list"
  []
  (let [list-model (atom (create-zone-list-model))
        
        zone-list (ss/listbox :model @list-model
                              :renderer zone-renderer)
        
        refresh! (fn []
                   (reset! list-model (create-zone-list-model))
                   (ss/config! zone-list :model @list-model))
        
        add-btn (ss/button :text "Add"
                           :listen [:action (fn [e]
                                              (when-let [z (show-zone-dialog 
                                                            (ss/to-root e)
                                                            :title "Add Zone")]
                                                (zones/create-zone! z)
                                                (refresh!)))])
        
        edit-btn (ss/button :text "Edit"
                            :listen [:action (fn [e]
                                               (when-let [selected (ss/selection zone-list)]
                                                 (when-let [z (show-zone-dialog
                                                               (ss/to-root e)
                                                               :zone selected
                                                               :title "Edit Zone")]
                                                   (zones/update-zone! (:id z)
                                                                       (dissoc z :id :created-at))
                                                   (refresh!))))])
        
        remove-btn (ss/button :text "Remove"
                              :listen [:action (fn [e]
                                                 (when-let [selected (ss/selection zone-list)]
                                                   (let [confirm (JOptionPane/showConfirmDialog
                                                                  (ss/to-root e)
                                                                  (str "Remove zone '" (:name selected) "'?")
                                                                  "Confirm Remove"
                                                                  JOptionPane/YES_NO_OPTION)]
                                                     (when (= confirm JOptionPane/YES_OPTION)
                                                       (zones/remove-zone! (:id selected))
                                                       (refresh!)))))])
        
        toggle-btn (ss/button :text "Toggle Enabled"
                              :listen [:action (fn [_]
                                                 (when-let [selected (ss/selection zone-list)]
                                                   (if (:enabled selected)
                                                     (zones/disable-zone! (:id selected))
                                                     (zones/enable-zone! (:id selected)))
                                                   (refresh!)))])
        
        button-panel (ss/horizontal-panel :items [add-btn edit-btn remove-btn toggle-btn]
                                          :border (border/empty-border :thickness 5))
        
        main-panel (ss/border-panel
                    :center (ss/scrollable zone-list)
                    :south button-panel
                    :border (border/to-border "Zones"))]
    
    {:panel main-panel
     :refresh! refresh!}))

;; ============================================================================
;; Standalone Dialog
;; ============================================================================

(defn show-zone-config-dialog
  "Show the zone configuration dialog."
  [parent]
  (let [config-panel (create-zone-config-panel)
        dialog (ss/dialog :title "Configure Zones"
                          :content (:panel config-panel)
                          :size [600 :by 500]
                          :modal? true
                          :parent parent)]
    ((:refresh! config-panel))
    (ss/show! dialog)))
