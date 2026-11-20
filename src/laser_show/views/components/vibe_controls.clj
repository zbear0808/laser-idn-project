(ns laser-show.views.components.vibe-controls
  "The Vibes tab's Controls panel: a row for each of the five controls
   (HIT, ACCENT, FLASH, VIBE, BLACKOUT) with the keys it answers to, Learn
   (Cancel while it waits for a key) and Clear; and Learn all, which learns
   the five in a row (:vibe/learn-all; handlers/input.clj starts each next
   learn), saying which key it waits for, and when a key pressed is another
   control's already.

   Then the two faders (plans/2026-11-18-vibe-mode/05), each learned on its
   own: a fader, a knob, a gamepad trigger or an OSC value that moves
   (:input/start-learn with :capture :continuous); and the buttons that wire
   them: Add master intensity, and Add Morph for the vibe edited."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.vibes.faders :as faders]
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

(defn- fader-row
  "One fader (props: :slot): the control learned for it, Learn (Cancel
   while it waits for a control to move) and Clear."
  [{:keys [fx/context slot]}]
  (let [{:keys [label bindings learning?]} (fx/sub-ctx context subs/vibe-fader-control slot)
        id (str "vibe-control-" (name slot))]
    {:fx/type :h-box
     :id id
     :style-class "vibe-fader"
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
                         learning? "Move a fader…"
                         (seq bindings) (str/join " · " (map trigger-bank/fader-label bindings))
                         :else "Not learned")}
                {:fx/type :button
                 :id (str id "-learn")
                 :style-class "button-secondary"
                 :focus-traversable false
                 :text (if learning? "Cancel" "Learn")
                 :on-action (if learning?
                              {:event/type :input/cancel-learn}
                              {:event/type :input/start-learn :target-action {:type :macro :slot slot} :capture :continuous})}
                {:fx/type :button
                 :id (str id "-clear")
                 :style-class "button-secondary"
                 :focus-traversable false
                 :text "Clear"
                 :disable (empty? bindings)
                 :on-action {:event/type :vibe/clear-control :slot slot}}]}))

(defn- fader-buttons
  "Add master intensity (not while there is a Master, or no effect cell is
   free), and Add Morph ▸ Size, Spin, Colour for the vibe edited."
  [{:keys [fx/context]}]
  (let [{:keys [master free? vibe vibe-name]} (fx/sub-ctx context subs/vibe-fader-actions)]
    {:fx/type :flow-pane
     :hgap 6
     :vgap 6
     :children [{:fx/type :button
                 :id "vibe-add-master"
                 :style-class "button-secondary"
                 :focus-traversable false
                 :text "Add master intensity"
                 :disable (or (some? master) (not free?))
                 :on-action {:event/type :vibe/add-master}}
                {:fx/type :menu-button
                 :id "vibe-add-morph"
                 :style-class ["menu-button" "button-secondary"]
                 :focus-traversable false
                 :text (if vibe-name (str "Add Morph to " vibe-name) "Add Morph")
                 :disable (or (nil? vibe) (not free?))
                 :items (mapv (fn [{:keys [kind menu]}]
                                {:fx/type :menu-item
                                 :id (str "vibe-add-morph-" (name kind))
                                 :text menu
                                 :on-action {:event/type :vibe/add-morph :vibe vibe :kind kind}})
                              faders/morph-templates)}]}))

(defn controls-panel
  "The five controls and Learn all, then the two faders and their buttons."
  [_]
  {:fx/type :v-box
   :id "vibe-controls"
   :spacing 6
   :children (-> [{:fx/type :label :style-class "vibe-section-title" :text "CONTROLS"}]
                 (into (map (fn [role] {:fx/type control-row :fx/key role :role role})) library/roles)
                 (conj {:fx/type learn-all})
                 (conj {:fx/type :label :style-class "vibe-section-title" :padding {:top 10} :text "FADERS"})
                 (into (map (fn [slot] {:fx/type fader-row :fx/key slot :slot slot})) faders/slots)
                 (conj {:fx/type fader-buttons}))})
