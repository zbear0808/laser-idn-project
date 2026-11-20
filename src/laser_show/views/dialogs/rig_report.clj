(ns laser-show.views.dialogs.rig-report
  "A show's rig matched onto this one (plans/2026-11-19-zones/09 §7), after
   File > Open. On a rig with no laser on: \"Set up your rig from
   friday.zip?\" (Q-ZR-3). On a rig that is set up: the report of what
   doesn't fit, with a fix for each.

   State: [:ui :dialogs :rig-report] {:open? :mode (:set-up or :report) :file
   :hardware :chains :pins}, what :file/project-loaded kept of the show's rig
   for as long as the show is open (New and Open reset it)."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.css.core :as css]
            [laser-show.state.rig-file :as rig-file]
            [laser-show.subs :as subs]))


;; The set-up question

(defn- set-up-question
  "rig-file/set-up-question for the open show's hardware on this rig."
  [context]
  (let [{:keys [hardware]} (fx/sub-ctx context subs/dialog-data :rig-report)]
    (rig-file/set-up-question {:projectors (fx/sub-val context :projectors)} hardware)))

(defn- possessive [n] (str n "'s"))

(defn unreadable-text
  "\"Audience's safety zone in friday.zip can't be read: it stays dark until
   you set one.\", for one laser or more."
  [names file]
  (if (= 1 (count names))
    (str (possessive (first names)) " safety zone in " file " can't be read: it stays dark until you set one.")
    (str (str/join " and " [(str/join ", " (map possessive (butlast names))) (possessive (last names))])
         " safety zones in " file " can't be read: they stay dark until you set them.")))

(defn- text-line
  ([id text] (text-line id text "label-secondary"))
  ([id text style-class]
   {:fx/type :label :id id :text text :wrap-text true :min-height :use-pref-size :style-class [style-class]}))

(defn- set-up-content
  [{:keys [fx/context]}]
  (let [{:keys [file]} (fx/sub-ctx context subs/dialog-data :rig-report)
        {:keys [lasers matched-by-ip unreadable]} (fx/sub-ctx context set-up-question)]
    {:fx/type :v-box
     :id "rig-report-dialog"
     :spacing 10
     :padding 20
     :pref-width 520
     :style-class ["container-primary"]
     :children
     (filterv some?
              [(text-line "rig-setup-title" (str "Set up your rig from " file "?") "header-primary")
               (text-line "rig-setup-intro" (str "No laser on this rig is turned on yet. " file " was made with " (count lasers) ":"))
               (assoc (text-line "rig-setup-lasers" (str/join " · " lasers) "label-bold") :style "-fx-padding: 0 0 0 16;")
               (text-line "rig-setup-what" "Their order, groups, safety zones and calibration become your rig.")
               (text-line "rig-setup-addresses" "Lasers found on this network keep their addresses.")
               (when (seq matched-by-ip)
                 (text-line "rig-setup-by-ip" (str "Matched by IP only, check them: "
                                                   (str/join ", " (map #(str (:name %) " (" (:host %) ")") matched-by-ip)))))
               (when (seq unreadable)
                 (assoc (text-line "rig-setup-unreadable" (unreadable-text unreadable file))
                        :style (str "-fx-text-fill: " (css/orange) ";")))
               {:fx/type :h-box
                :alignment :center-right
                :spacing 8
                :children [{:fx/type :button
                            :id "rig-setup-not-now"
                            :text "Not now"
                            :cancel-button true
                            :style-class ["button-secondary"]
                            :on-action {:event/type :ui/close-dialog :dialog-id :rig-report}}
                           {:fx/type :button
                            :id "rig-setup-button"
                            :text "Set up rig"
                            :default-button true
                            :style-class ["button-primary"]
                            :on-action {:event/type :rig/set-up-from-show}}]}])}))


;; The report

(defn- report-content
  [{:keys [fx/context]}]
  (let [{:keys [file]} (fx/sub-ctx context subs/dialog-data :rig-report)]
    {:fx/type :v-box
     :id "rig-report-dialog"
     :spacing 10
     :padding 20
     :pref-width 560
     :style-class ["container-primary"]
     :children [(text-line "rig-report-title" (str file " on this rig") "header-primary")
                (text-line "rig-report-kept" "Your rig's safety zones and calibration are kept.")
                {:fx/type :h-box
                 :alignment :center-right
                 :children [{:fx/type :button
                             :id "rig-report-close"
                             :text "Close"
                             :cancel-button true
                             :default-button true
                             :style-class ["button-secondary"]
                             :on-action {:event/type :ui/close-dialog :dialog-id :rig-report}}]}]}))


(defn rig-report-dialog
  "The set-up question or the report, in its own window. Application-modal,
   so nothing else can be clicked while it is open."
  [{:keys [fx/context]}]
  (let [open? (fx/sub-ctx context subs/dialog-open? :rig-report)
        {:keys [mode file]} (fx/sub-ctx context subs/dialog-data :rig-report)
        set-up? (= :set-up mode)]
    {:fx/type :stage
     :showing open?
     :title (if set-up? "Set up rig" (str file " on this rig"))
     :modality :application-modal
     :resizable false
     :on-close-request {:event/type :ui/close-dialog :dialog-id :rig-report}
     :scene {:fx/type :scene
             :stylesheets (css/dialog-stylesheet-urls)
             :root (if set-up? {:fx/type set-up-content} {:fx/type report-content})}}))
