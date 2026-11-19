(ns laser-show.views.components.vibe-controls
  "The Vibes tab's Controls panel: a row for each of the five controls
   (HIT, ACCENT, FLASH, VIBE, BLACKOUT) with the keys it answers to, Learn
   (Cancel while it waits for a key) and Clear; and Learn all, which learns
   the five in a row (:vibe/learn-all; handlers/input.clj starts each next
   learn), saying which key it waits for, and when a key pressed is another
   control's already."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.vibes.library :as library]
            [laser-show.views.components.trigger-bank :as trigger-bank]))

(defn- control-row
  "One control (props: :role)."
  [{:keys [fx/context role]}]
  (let [{:keys [label bindings learning?]} (fx/sub-ctx context subs/vibe-control role)
        id (str "vibe-control-" (name role))]
    {:fx/type :h-box
     :id id
     :style-class "vibe-control"
     :spacing 6
     :alignment :center-left
     :children [{:fx/type :label
                 :style-class "vibe-control-role"
                 :min-width 72
                 :text label}
                {:fx/type :label
                 :id (str id "-keys")
                 :style-class (cond-> ["vibe-control-keys"] learning? (conj "vibe-control-waiting"))
                 :max-width Double/MAX_VALUE
                 :h-box/hgrow :always
                 :text (cond
                         learning? "Press a key…"
                         (seq bindings) (str/join " · " (map trigger-bank/binding-label bindings))
                         :else "Not learned")}
                {:fx/type :button
                 :id (str id "-learn")
                 :style-class "button-secondary"
                 :focus-traversable false
                 :text (if learning? "Cancel" "Learn")
                 :on-action (if learning?
                              {:event/type :input/cancel-learn}
                              {:event/type :input/start-learn :target-action {:type :vibe :role role}})}
                {:fx/type :button
                 :id (str id "-clear")
                 :style-class "button-secondary"
                 :focus-traversable false
                 :text "Clear"
                 :disable (empty? bindings)
                 :on-action {:event/type :vibe/clear-control :role role}}]}))

(defn- role-name [role]
  (str/upper-case (name role)))

(defn- learn-all
  "Learn all, or while it runs, the key it waits for and Cancel."
  [{:keys [fx/context]}]
  (let [{:keys [role step of refused]} (fx/sub-ctx context subs/vibe-wizard)
        running? (some? role)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :button
                 :id "vibe-learn-all"
                 :style-class "button-primary"
                 :text "Learn all"
                 :visible (not running?)
                 :managed (not running?)
                 :on-action {:event/type :vibe/learn-all}}
                {:fx/type :label
                 :id "vibe-wizard-status"
                 :style-class "vibe-wizard-status"
                 :wrap-text true
                 :visible running?
                 :managed running?
                 :text (if running?
                         (str "Press the key for " (role-name role) " (" step " of " of ")"
                              (when refused (str " · that key is " (role-name refused) "'s already")))
                         "")}
                {:fx/type :button
                 :id "vibe-wizard-cancel"
                 :style-class "button-secondary"
                 :text "Cancel"
                 :visible running?
                 :managed running?
                 :on-action {:event/type :vibe/wizard-cancel}}]}))

(defn controls-panel
  "The five controls and Learn all."
  [_]
  {:fx/type :v-box
   :id "vibe-controls"
   :spacing 6
   :children (-> [{:fx/type :label :style-class "vibe-section-title" :text "CONTROLS"}]
                 (into (map (fn [role] {:fx/type control-row :fx/key role :role role})) library/roles)
                 (conj {:fx/type learn-all}))})
