(ns laser-show.views.dialogs.unsaved-confirm
  "Asks what to do with unsaved changes before File > New, File > Open or
   closing the app would throw them away.

   State lives in [:ui :dialogs :unsaved-confirm] ({:open? :pending}); the
   buttons dispatch :file/confirm-save, :file/confirm-discard and
   :file/confirm-cancel (see laser-show.events.handlers.menu)."
  (:require [cljfx.api :as fx]
            [laser-show.css.core :as css]
            [laser-show.subs :as subs]))

(defn- action-label
  "What the parked action does, for the question text."
  [pending]
  (case (:event/type pending)
    :file/new-project "creating a new project"
    :file/open "opening another project"
    "closing"))

(defn- project-name [file]
  (if file
    (.getName (java.io.File. ^String file))
    "this project"))

(defn- unsaved-confirm-content
  [{:keys [fx/context]}]
  (let [{:keys [pending]} (fx/sub-ctx context subs/dialog-data :unsaved-confirm)
        {:keys [file]} (fx/sub-ctx context subs/project-status)]
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
         :on-action {:event/type :file/confirm-save}}]}]}))

(defn unsaved-confirm-dialog
  "The unsaved-changes confirmation window. Application-modal, so nothing else
   can be clicked while the question is open."
  [{:keys [fx/context]}]
  (let [open? (fx/sub-ctx context subs/dialog-open? :unsaved-confirm)]
    {:fx/type :stage
     :showing open?
     :title "Unsaved changes"
     :modality :application-modal
     :resizable false
     :on-close-request {:event/type :file/confirm-cancel}
     :scene {:fx/type :scene
             :stylesheets (css/dialog-stylesheet-urls)
             :root {:fx/type unsaved-confirm-content}}}))
