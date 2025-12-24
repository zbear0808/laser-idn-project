(ns laser-show.ui.projector-config
  "Projector configuration dialog.
   UI for managing laser projector connections."
  (:require [seesaw.core :as ss]
            [seesaw.mig :as mig]
            [seesaw.border :as border]
            [laser-show.backend.projectors :as projectors])
  (:import [java.awt Color Font]
           [javax.swing JOptionPane]))

;; ============================================================================
;; Projector List Panel
;; ============================================================================

(defn- create-projector-list-model
  "Create a list model from projectors."
  []
  (vec (projectors/list-projectors)))

(defn- projector-renderer
  "Custom renderer for projector list items."
  [renderer {:keys [value]}]
  (when value
    (let [status-color (if (= :active (:status value))
                         (Color. 100 255 100)
                         (Color. 150 150 150))]
      (ss/config! renderer 
                  :text (str (:name value) " (" (:address value) ":" (:port value) ")")
                  :foreground status-color))))

;; ============================================================================
;; Add/Edit Projector Dialog
;; ============================================================================

(defn- show-projector-dialog
  "Show dialog for adding or editing a projector.
   Returns the projector map if OK was clicked, nil otherwise."
  [parent & {:keys [projector title]
             :or {title "Add Projector"}}]
  (let [id-field (ss/text :text (if projector (name (:id projector)) "")
                          :columns 20
                          :editable? (nil? projector))
        name-field (ss/text :text (or (:name projector) "")
                            :columns 20)
        address-field (ss/text :text (or (:address projector) "192.168.1.100")
                               :columns 15)
        port-field (ss/spinner :model (ss/spinner-model 
                                       (or (:port projector) 7255)
                                       :from 1 :to 65535 :by 1))
        channel-field (ss/spinner :model (ss/spinner-model
                                          (or (:channel-id projector) 0)
                                          :from 0 :to 255 :by 1))
        status-combo (ss/combobox :model [:active :inactive]
                                  :selected-item (or (:status projector) :active))
        
        panel (mig/mig-panel
               :constraints ["" "[right][grow,fill]" ""]
               :items [[(ss/label "ID:") ""]
                       [id-field "wrap"]
                       [(ss/label "Name:") ""]
                       [name-field "wrap"]
                       [(ss/label "IP Address:") ""]
                       [address-field "wrap"]
                       [(ss/label "Port:") ""]
                       [port-field "wrap"]
                       [(ss/label "Channel ID:") ""]
                       [channel-field "wrap"]
                       [(ss/label "Status:") ""]
                       [status-combo "wrap"]])
        result (JOptionPane/showConfirmDialog
                parent
                panel
                title
                JOptionPane/OK_CANCEL_OPTION
                JOptionPane/PLAIN_MESSAGE)]
    
    (when (= result JOptionPane/OK_OPTION)
      (let [id-str (ss/text id-field)
            id-kw (if (empty? id-str)
                    (keyword (str "projector-" (System/currentTimeMillis)))
                    (keyword id-str))]
        (projectors/make-projector
         id-kw
         (ss/text name-field)
         (ss/text address-field)
         :port (ss/value port-field)
         :channel-id (ss/value channel-field)
         :status (ss/selection status-combo))))))

;; ============================================================================
;; Main Configuration Panel
;; ============================================================================

(defn create-projector-config-panel
  "Create the projector configuration panel.
   
   Returns a map with:
   - :panel - The Swing component
   - :refresh! - Refresh the projector list"
  []
  (let [list-model (atom (create-projector-list-model))
        
        projector-list (ss/listbox :model @list-model
                                   :renderer projector-renderer)
        
        refresh! (fn []
                   (reset! list-model (create-projector-list-model))
                   (ss/config! projector-list :model @list-model))
        
        add-btn (ss/button :text "Add"
                           :listen [:action (fn [e]
                                              (when-let [proj (show-projector-dialog 
                                                               (ss/to-root e)
                                                               :title "Add Projector")]
                                                (projectors/register-projector! proj)
                                                (refresh!)))])
        
        edit-btn (ss/button :text "Edit"
                            :listen [:action (fn [e]
                                               (when-let [selected (ss/selection projector-list)]
                                                 (when-let [proj (show-projector-dialog
                                                                  (ss/to-root e)
                                                                  :projector selected
                                                                  :title "Edit Projector")]
                                                   (projectors/update-projector! (:id proj)
                                                                                 (dissoc proj :id :created-at))
                                                   (refresh!))))])
        
        remove-btn (ss/button :text "Remove"
                              :listen [:action (fn [e]
                                                 (when-let [selected (ss/selection projector-list)]
                                                   (let [confirm (JOptionPane/showConfirmDialog
                                                                  (ss/to-root e)
                                                                  (str "Remove projector '" (:name selected) "'?")
                                                                  "Confirm Remove"
                                                                  JOptionPane/YES_NO_OPTION)]
                                                     (when (= confirm JOptionPane/YES_OPTION)
                                                       (projectors/remove-projector! (:id selected))
                                                       (refresh!)))))])
        
        toggle-btn (ss/button :text "Toggle Status"
                              :listen [:action (fn [_]
                                                 (when-let [selected (ss/selection projector-list)]
                                                   (let [new-status (if (= :active (:status selected))
                                                                      :inactive
                                                                      :active)]
                                                     (projectors/set-projector-status! (:id selected) new-status)
                                                     (refresh!))))])
        
        button-panel (ss/horizontal-panel :items [add-btn edit-btn remove-btn toggle-btn]
                                          :border (border/empty-border :thickness 5))
        
        main-panel (ss/border-panel
                    :center (ss/scrollable projector-list)
                    :south button-panel
                    :border (border/to-border "Projectors"))]
    
    {:panel main-panel
     :refresh! refresh!}))

;; ============================================================================
;; Standalone Dialog
;; ============================================================================

(defn show-projector-config-dialog
  "Show the projector configuration dialog."
  [parent]
  (let [config-panel (create-projector-config-panel)
        dialog (ss/dialog :title "Configure Projectors"
                          :content (:panel config-panel)
                          :size [500 :by 400]
                          :modal? true
                          :parent parent)]
    ((:refresh! config-panel))
    (ss/show! dialog)))
