(ns laser-show.views.dialogs.zone-group-editor
  "Dialog for creating and editing zone groups.
   
   Features:
   - Name field
   - Description field  
   - Color picker (preset colors or custom hex)"
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.css.core :as css]))


;; Color Presets


(def color-presets
  "Predefined colors for zone groups."
  [{:id :gray    :color "#808080" :name "Gray"}
   {:id :blue    :color "#4A90D9" :name "Blue"}
   {:id :red     :color "#D94A4A" :name "Red"}
   {:id :green   :color "#4AD94A" :name "Green"}
   {:id :purple  :color "#9B59B6" :name "Purple"}
   {:id :orange  :color "#E67E22" :name "Orange"}
   {:id :cyan    :color "#1ABC9C" :name "Cyan"}
   {:id :yellow  :color "#F1C40F" :name "Yellow"}
   {:id :pink    :color "#E91E63" :name "Pink"}
   {:id :teal    :color "#009688" :name "Teal"}])


;; Color Picker


(defn- color-swatch
  "A clickable color swatch."
  [{:keys [color selected? on-click]}]
  {:fx/type :region
   :pref-width 28
   :pref-height 28
   :style (str "-fx-background-color: " color "; "
              "-fx-background-radius: 4; "
              "-fx-cursor: hand;"
              (when selected?
                " -fx-border-color: white; -fx-border-width: 2; -fx-border-radius: 4;"))
   :on-mouse-clicked on-click})

(defn- color-picker
  "Grid of color swatches with custom hex input."
  [{:keys [selected-color dialog-id]}]
  {:fx/type :v-box
   :spacing 8
   :children [{:fx/type :label
               :text "Color"
               :style-class ["label-secondary"]}
              {:fx/type :flow-pane
               :hgap 6
               :vgap 6
               :children (vec (for [{:keys [color]} color-presets]
                                {:fx/type color-swatch
                                 :fx/key color
                                 :color color
                                 :selected? (= color selected-color)
                                 :on-click {:event/type :zone-groups/set-editor-color
                                            :color color}}))}
              {:fx/type :h-box
               :spacing 8
               :alignment :center-left
               :children [{:fx/type :label
                           :text "Hex:"
                           :style-class ["label-hint"]
                           :style "-fx-font-style: normal;"}
                          {:fx/type :text-field
                           :text (or selected-color "#808080")
                           :pref-width 90
                           :style-class ["text-field" "text-monospace"]
                           :on-text-changed {:event/type :ui/update-dialog-data
                                             :dialog-id dialog-id
                                             :updates {:color :fx/event}}}
                          {:fx/type :region
                           :pref-width 24
                           :pref-height 24
                           :style (str "-fx-background-color: " (or selected-color "#808080") "; "
                                      "-fx-background-radius: 4;")}]}]})


;; Dialog Content


(defn- zone-group-editor-content
  "Content of the zone group editor dialog."
  [{:keys [fx/context]}]
  (let [dialog-data (fx/sub-ctx context subs/dialog-data :zone-group-editor)
        editing? (:editing? dialog-data)
        group-id (:group-id dialog-data)
        name-value (or (:name dialog-data) "")
        description-value (or (:description dialog-data) "")
        color-value (or (:color dialog-data) "#808080")]
    {:fx/type :v-box
     :spacing 16
     :padding 20
     :style "-fx-background-color: #2D2D2D;"
     :pref-width 400
     :children [{:fx/type :label
                 :text (if editing? "Edit Zone Group" "New Zone Group")
                 :style "-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;"}
                {:fx/type :label
                 :text "Zone groups help organize zones into categories for easier routing."
                 :style "-fx-text-fill: #808080; -fx-font-size: 11;"}
                {:fx/type :v-box
                 :spacing 12
                 :children [;; Name field
                            {:fx/type :v-box
                             :spacing 4
                             :children [{:fx/type :label
                                         :text "Name"
                                         :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}
                                        {:fx/type :text-field
                                         :text name-value
                                         :prompt-text "e.g., Left Side, Audience, Stage"
                                         :style "-fx-background-color: #404040; -fx-text-fill: white;"
                                         :on-text-changed {:event/type :ui/update-dialog-data
                                                           :dialog-id :zone-group-editor
                                                           :updates {:name :fx/event}}}]}
                            ;; Description field
                            {:fx/type :v-box
                             :spacing 4
                             :children [{:fx/type :label
                                         :text "Description (optional)"
                                         :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}
                                        {:fx/type :text-field
                                         :text description-value
                                         :prompt-text "Brief description of this zone group"
                                         :style "-fx-background-color: #404040; -fx-text-fill: white;"
                                         :on-text-changed {:event/type :ui/update-dialog-data
                                                           :dialog-id :zone-group-editor
                                                           :updates {:description :fx/event}}}]}
                            ;; Color picker
                            {:fx/type color-picker
                             :selected-color color-value
                             :dialog-id :zone-group-editor}]}
                ;; Buttons
                {:fx/type :h-box
                 :spacing 8
                 :alignment :center-right
                 :padding {:right 8}
                 :children [{:fx/type :button
                             :text "Cancel"
                             :style-class ["button-secondary"]
                             :on-action {:event/type :ui/close-dialog
                                         :dialog-id :zone-group-editor}}
                            {:fx/type :button
                             :text (if editing? "Save Changes" "Create Group")
                             :style-class ["button-primary"]
                             :disable (str/blank? name-value)
                             :on-action (if editing?
                                          {:event/type :zone-groups/save-edit
                                           :group-id group-id
                                           :name name-value
                                           :description description-value
                                           :color color-value}
                                          {:event/type :zone-groups/create-new
                                           :name name-value
                                           :description description-value
                                           :color color-value})}]}]}))


;; Dialog Window


(defn zone-group-editor-dialog
  "The zone group editor dialog window."
  [{:keys [fx/context]}]
  (let [open? (fx/sub-ctx context subs/dialog-open? :zone-group-editor)
        dialog-data (fx/sub-ctx context subs/dialog-data :zone-group-editor)
        editing? (:editing? dialog-data)
        stylesheets (css/dialog-stylesheet-urls)]
    {:fx/type :stage
     :showing open?
     :title (if editing? "Edit Zone Group" "New Zone Group")
     :modality :none
     :on-close-request {:event/type :ui/close-dialog :dialog-id :zone-group-editor}
     :scene {:fx/type :scene
             :stylesheets stylesheets
             :root {:fx/type zone-group-editor-content}}}))
