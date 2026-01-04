(ns laser-show.views.dialogs.add-projector-manual
  "Dialog for manually adding a projector by entering IP address."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.css.core :as css]))


;; Dialog Content


(defn- add-projector-content
  "Content of the add projector dialog."
  [{:keys [fx/context]}]
  (let [dialog-data (fx/sub-ctx context subs/dialog-data :add-projector-manual)
        name-value (or (:name dialog-data) "")
        host-value (or (:host dialog-data) "")
        port-value (or (:port dialog-data) "7255")]
    {:fx/type :v-box
     :spacing 16
     :padding 20
     :style "-fx-background-color: #2D2D2D;"
     :pref-width 380
     :children [{:fx/type :label
                 :text "Add Projector Manually"
                 :style "-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;"}
                {:fx/type :label
                 :text "Enter the connection details for your IDN laser projector."
                 :style "-fx-text-fill: #808080; -fx-font-size: 11;"}
                {:fx/type :v-box
                 :spacing 12
                 :children [{:fx/type :h-box
                             :spacing 8
                             :alignment :center-left
                             :children [{:fx/type :label
                                         :text "Name:"
                                         :pref-width 50
                                         :style "-fx-text-fill: #B0B0B0;"}
                                        {:fx/type :text-field
                                         :text name-value
                                         :prompt-text "My Projector"
                                         :pref-width 280
                                         :style "-fx-background-color: #404040; -fx-text-fill: white;"
                                         :on-text-changed {:event/type :ui/update-dialog-data
                                                           :dialog-id :add-projector-manual
                                                           :updates {:name :fx/event}}}]}
                            {:fx/type :h-box
                             :spacing 8
                             :alignment :center-left
                             :children [{:fx/type :label
                                         :text "Host:"
                                         :pref-width 50
                                         :style "-fx-text-fill: #B0B0B0;"}
                                        {:fx/type :text-field
                                         :text host-value
                                         :prompt-text "192.168.1.100"
                                         :pref-width 180
                                         :style "-fx-background-color: #404040; -fx-text-fill: white;"
                                         :on-text-changed {:event/type :ui/update-dialog-data
                                                           :dialog-id :add-projector-manual
                                                           :updates {:host :fx/event}}}
                                        {:fx/type :label
                                         :text "Port:"
                                         :style "-fx-text-fill: #B0B0B0;"}
                                        {:fx/type :text-field
                                         :text port-value
                                         :prompt-text "7255"
                                         :pref-width 60
                                         :style "-fx-background-color: #404040; -fx-text-fill: white;"
                                         :on-text-changed {:event/type :ui/update-dialog-data
                                                           :dialog-id :add-projector-manual
                                                           :updates {:port :fx/event}}}]}]}
                {:fx/type :h-box
                 :spacing 8
                 :alignment :center-right
                 :children [{:fx/type :button
                             :text "Cancel"
                             :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-padding: 8 20;"
                             :on-action {:event/type :ui/close-dialog
                                         :dialog-id :add-projector-manual}}
                            {:fx/type :button
                             :text "Add Projector"
                             :style "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-padding: 8 20;"
                             :disable (str/blank? host-value)
                             :on-action {:event/type :projectors/add-manual
                                         :name (if (str/blank? name-value) host-value name-value)
                                         :host host-value
                                         :port (try (Integer/parseInt port-value) (catch Exception _ 7255))}}]}]}))


;; Dialog Window


(defn add-projector-manual-dialog
  "The add projector dialog window."
  [{:keys [fx/context]}]
  (let [open? (fx/sub-ctx context subs/dialog-open? :add-projector-manual)
        stylesheets (css/dialog-stylesheet-urls)]
    {:fx/type :stage
     :showing open?
     :title "Add Projector"
     :modality :none
     :on-close-request {:event/type :ui/close-dialog :dialog-id :add-projector-manual}
     :scene {:fx/type :scene
             :stylesheets stylesheets
             :root {:fx/type add-projector-content}}}))
