(ns laser-show.views.dialogs.unsaved-confirm
  "Asks what to do with unsaved changes before File > New, File > Open or
   closing the app would throw them away; and, before closing the app, what
   to do when the rig file couldn't take the last rig changes (zones plan
   ZR, :reason :rig).

   State lives in [:ui :dialogs :unsaved-confirm] ({:open? :pending :reason
   :error}); the buttons dispatch :file/confirm-save (or, for the rig,
   :file/confirm-retry), :file/confirm-discard and :file/confirm-cancel (see
   laser-show.events.handlers.menu)."
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.views.dialogs.common :as common]))

(defn- project-name [file]
  (if file
    (.getName (java.io.File. ^String file))
    "this project"))

(defn- action-label
  "What the parked action does, for the question text. An Open asked about
   again once its file was picked (the show changed meanwhile) names it."
  [pending]
  (case (:event/type pending)
    :file/new-project "creating a new project"
    :file/open "opening another project"
    :file/open-result (str "opening " (project-name (:file-path pending)))
    "closing"))

(defn- rig-confirm-content
  "The rig's question: its last changes couldn't be written."
  [{:keys [error]}]
  {:fx/type :v-box
   :id "unsaved-confirm-dialog"
   :spacing 16
   :padding 20
   :pref-width 440
   :style-class ["dialog-content"]
   :children
   [{:fx/type :label
     :id "unsaved-message"
     :text (str "Your last rig changes couldn't be saved: " error ".")
     :wrap-text true
     :style-class "header-primary"}
    {:fx/type :label
     :text "They include safety zones and calibration."
     :wrap-text true
     :style-class "label-secondary"}
    {:fx/type :h-box
     :alignment :center-right
     :spacing 8
     :children
     [{:fx/type :button
       :id "unsaved-cancel"
       :text "Cancel"
       :cancel-button true
       :style-class ["button-secondary"]
       :on-action {:event/type :file/confirm-cancel}}
      {:fx/type :button
       :id "unsaved-discard"
       :text "Quit anyway"
       :style-class ["button-danger"]
       :on-action {:event/type :file/confirm-discard}}
      {:fx/type :button
       :id "unsaved-retry"
       :text "Try again"
       :default-button true
       :style-class ["button-primary"]
       :on-action {:event/type :file/confirm-retry}}]}]})

(defn- unsaved-confirm-content
  [{:keys [fx/context]}]
  (let [{:keys [pending reason] :as data} (fx/sub-ctx context subs/dialog-data :unsaved-confirm)
        {:keys [file]} (fx/sub-ctx context subs/project-status)]
    (if (= :rig reason)
      (rig-confirm-content data)
      {:fx/type :v-box
       :id "unsaved-confirm-dialog"
       :spacing 16
       :padding 20
       :pref-width 440
       :style-class ["dialog-content"]
       :children
       [{:fx/type :label
         :id "unsaved-message"
         :text (str "Save changes to " (project-name file)
                    " before " (action-label pending) "?")
         :wrap-text true
         :style-class "header-primary"}
        {:fx/type :label
         :text "Your changes will be lost if you don't save them."
         :wrap-text true
         :style-class "label-secondary"}
        {:fx/type :h-box
         :alignment :center-right
         :spacing 8
         :children
         [{:fx/type :button
           :id "unsaved-cancel"
           :text "Cancel"
           :cancel-button true
           :style-class ["button-secondary"]
           :on-action {:event/type :file/confirm-cancel}}
          {:fx/type :button
           :id "unsaved-discard"
           :text "Don't Save"
           :style-class ["button-danger"]
           :on-action {:event/type :file/confirm-discard}}
          {:fx/type :button
           :id "unsaved-save"
           :text "Save"
           :default-button true
           :style-class ["button-primary"]
           :on-action {:event/type :file/confirm-save}}]}]})))

(defn unsaved-confirm-dialog
  "The unsaved-changes confirmation window. Application-modal, so nothing else
   can be clicked while the question is open."
  [{:keys [fx/context]}]
  (let [{:keys [reason]} (fx/sub-ctx context subs/dialog-data :unsaved-confirm)]
    {:fx/type common/dialog-stage
     :dialog-id :unsaved-confirm
     :title (if (= :rig reason) "Rig not saved" "Unsaved changes")
     :modality :application-modal
     :resizable false
     :on-close-request {:event/type :file/confirm-cancel}
     :content {:fx/type unsaved-confirm-content}}))
