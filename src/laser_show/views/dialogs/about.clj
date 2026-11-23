(ns laser-show.views.dialogs.about
  "Help > About: the app's name, what it is and its licence, on the shared
   dialog shell. State: [:ui :dialogs :about] {:open?}, set by :help/about.
   Close, Enter, Escape and the window's close all close it."
  (:require [laser-show.views.components.title-bar :as title-bar]
            [laser-show.views.dialogs.common :as common]))

(defn- about-content
  [_props]
  {:fx/type :v-box
   :id "about-dialog"
   :spacing 12
   :padding 20
   :pref-width 380
   :style-class ["container-primary"]
   :children [{:fx/type :h-box
               :spacing 12
               :alignment :center-left
               :children [{:fx/type :image-view
                           :image title-bar/app-icon
                           :fit-width 40
                           :fit-height 40
                           :preserve-ratio true}
                          {:fx/type :label
                           :id "about-title"
                           :text "Laser Show"
                           :style-class ["header-primary"]}]}
              {:fx/type :label
               :id "about-description"
               :text "Live laser show control: cue grids, effects and zones, streamed to IDN and driver projectors."
               :wrap-text true
               :style-class ["label-secondary"]}
              {:fx/type :label
               :id "about-licence"
               :text "Free software under the GNU General Public License v3."
               :wrap-text true
               :style-class ["label-hint"]}
              {:fx/type :h-box
               :alignment :center-right
               :spacing 8
               :children [{:fx/type :button
                           :id "about-documentation"
                           :text "Documentation"
                           :style-class ["button-secondary"]
                           :on-action {:event/type :help/documentation}}
                          {:fx/type :button
                           :id "about-close"
                           :text "Close"
                           :default-button true
                           :cancel-button true
                           :style-class ["button-primary"]
                           :on-action {:event/type :ui/close-dialog :dialog-id :about}}]}]})

(defn about-dialog
  "The About window: application-modal and fixed in size, like the
   question dialogs."
  [_props]
  {:fx/type common/dialog-stage
   :dialog-id :about
   :title "About Laser Show"
   :modality :application-modal
   :resizable false
   :content {:fx/type about-content}})
