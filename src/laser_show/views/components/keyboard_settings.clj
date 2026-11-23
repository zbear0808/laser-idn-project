(ns laser-show.views.components.keyboard-settings
  "The keyboard trigger switch and the built-in shortcuts this show's bindings override."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.css.core :as css]
            [laser-show.events.handlers.triggers :as triggers]
            [laser-show.input.events :as input-events]
            [laser-show.input.keyboard :as keyboard]
            [laser-show.subs :as subs])
  (:import [javafx.scene.input KeyCode]))

(def ^:private shortcuts
  "Only keys with an overridable shortcut, as named by the keyboard policy."
  (for [^KeyCode code (KeyCode/values)
        :let [id (keyboard/key-id (.name code))
              {:keys [label]} (keyboard/builtin-shortcut id)]
        :when label]
    [id (str (keyboard/label id) " (" label ")")]))

(defn- overridden-shortcuts [context]
  (let [bindings {:config {:input {:trigger-map (fx/sub-ctx context subs/input-trigger-map)}}
                  :triggers {:definitions (fx/sub-ctx context subs/trigger-definitions)}}]
    (sort (for [[id description] shortcuts
                :when (triggers/bound? bindings (input-events/trigger :keyboard id :pressed))]
            description))))

(defn keyboard-settings-section [{:keys [fx/context]}]
  (let [enabled? (fx/sub-val context get-in [:backend :input :keyboard :enabled?])
        overrides (overridden-shortcuts context)]
    {:fx/type :v-box
     :id "keyboard-settings"
     :style-class "card"
     :style "-fx-background-radius: 8; -fx-border-radius: 8;"
     :padding 16
     :spacing 12
     :children [{:fx/type :h-box
                 :spacing 8
                 :alignment :center-left
                 :children [{:fx/type :label :text "Keyboard" :style-class "header-primary"}
                            {:fx/type :region :h-box/hgrow :always}
                            {:fx/type :check-box
                             :id "keyboard-enabled"
                             :text "Enable keyboard triggers"
                             :style (str "-fx-text-fill: " (css/text-secondary) ";")
                             :selected enabled?
                             :tooltip {:fx/type :tooltip :text "Use Learn on a cue, effect or pad to assign a key."}
                             :on-action {:event/type (if enabled? :input/keyboard-disable :input/keyboard-enable)}}]}
                {:fx/type :v-box
                 :spacing 4
                 :children [{:fx/type :label
                             :id "keyboard-overrides"
                             :text (if (seq overrides)
                                     (str (if enabled? "Overridden shortcuts: " "Shortcuts overridden when enabled: ")
                                          (str/join ", " overrides))
                                     "No built-in shortcuts overridden.")
                             :wrap-text true}
                            {:fx/type :label
                             :id "keyboard-reserved"
                             :text "Escape is reserved for Stop."
                             :style-class "label-secondary"}]}]}))
