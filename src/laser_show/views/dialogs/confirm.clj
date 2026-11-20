(ns laser-show.views.dialogs.confirm
  "One confirm dialog the setup screens share: deleting a zone group,
   removing a projector, and later the safety questions.

   State lives in [:ui :dialogs :confirm]:
   {:open? :title :message :details :choice :choice-value :confirm-label
    :danger? :confirm-event}
   :choice is optional, {:key :label :options [{:value :text}]}; its combo
   keeps the picked option in :choice-value. Cancel closes the dialog; the
   confirm button dispatches :ui/confirm, which closes it and continues with
   :confirm-event, plus {(:key choice) choice-value} when there's a choice.
   :confirm-event is plain data: whoever opens the dialog builds it fresh."
  (:require [cljfx.api :as fx]
            [laser-show.css.core :as css]
            [laser-show.subs :as subs]))

(defn- choice-row
  [{:keys [choice choice-value]}]
  (let [{:keys [label options]} choice
        text-of (into {} (map (juxt :value :text)) options)
        values (mapv :value options)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text (or label "")
                 :style-class ["label-secondary"]}
                {:fx/type :combo-box
                 :id "confirm-choice"
                 :items values
                 :value (if (contains? text-of choice-value) choice-value (first values))
                 :button-cell (fn [value] {:text (get text-of value "")})
                 :cell-factory {:fx/cell-type :list-cell
                                :describe (fn [value] {:text (get text-of value "")})}
                 :on-value-changed {:event/type :ui/update-dialog-data
                                    :dialog-id :confirm
                                    :updates {:choice-value :fx/event}}}]}))

(defn- confirm-content
  [{:keys [fx/context]}]
  (let [{:keys [title message details choice confirm-label danger?] :as data}
        (fx/sub-ctx context subs/dialog-data :confirm)]
    {:fx/type :v-box
     :id "confirm-dialog"
     :spacing 16
     :padding 20
     :pref-width 460
     :style-class ["container-primary"]
     :children
     (filterv some?
              [{:fx/type :label
                :id "confirm-title"
                :text (or title "")
                :wrap-text true
                :style-class ["header-primary"]}
               (when message
                 {:fx/type :label
                  :id "confirm-message"
                  :text message
                  :wrap-text true
                  :style-class ["label-secondary"]})
               (when details
                 {:fx/type :label
                  :id "confirm-details"
                  :text details
                  :wrap-text true
                  :style-class ["label-hint"]})
               (when choice
                 {:fx/type choice-row
                  :choice choice
                  :choice-value (:choice-value data)})
               {:fx/type :h-box
                :alignment :center-right
                :spacing 8
                :children [{:fx/type :button
                            :id "confirm-cancel"
                            :text "Cancel"
                            :cancel-button true
                            :style-class ["button-secondary"]
                            :on-action {:event/type :ui/close-dialog :dialog-id :confirm}}
                           {:fx/type :button
                            :id "confirm-ok"
                            :text (or confirm-label "OK")
                            :default-button true
                            :style-class [(if danger? "button-danger" "button-primary")]
                            :on-action {:event/type :ui/confirm}}]}])}))

(defn confirm-dialog
  "The shared confirm window. Application-modal, so nothing else can be
   clicked while the question is open."
  [{:keys [fx/context]}]
  (let [open? (fx/sub-ctx context subs/dialog-open? :confirm)
        {:keys [title]} (fx/sub-ctx context subs/dialog-data :confirm)]
    {:fx/type :stage
     :showing open?
     :title (or title "Confirm")
     :modality :application-modal
     :resizable false
     :on-close-request {:event/type :ui/close-dialog :dialog-id :confirm}
     :scene {:fx/type :scene
             :stylesheets (css/dialog-stylesheet-urls)
             :root {:fx/type confirm-content}}}))
