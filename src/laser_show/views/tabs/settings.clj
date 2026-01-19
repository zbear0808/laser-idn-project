(ns laser-show.views.tabs.settings
  "Settings tab for application configuration.
   
   Includes:
   - MIDI input configuration
   - OSC input configuration
   - (Future: Keyboard shortcuts, general preferences, etc.)"
  (:require
   [laser-show.css.core :as css]
   [laser-show.views.components.midi-settings :as midi-settings]
   [laser-show.views.components.osc-settings :as osc-settings]))


;; Placeholder for future keyboard settings


(defn- keyboard-settings-section
  "Keyboard configuration section (placeholder for future implementation)."
  [{:keys [_fx/context]}]
  {:fx/type :v-box
   :spacing 12
   :style (str "-fx-background-color: " (css/bg-elevated) "; "
               "-fx-padding: 16; "
               "-fx-background-radius: 8;")
   :children [{:fx/type :h-box
               :spacing 8
               :alignment :center-left
               :children [{:fx/type :label
                           :text "Keyboard Configuration"
                           :style (str "-fx-font-size: 16; -fx-font-weight: bold; "
                                       "-fx-text-fill: " (css/text-primary) ";")}]}
              {:fx/type :label
               :text "Keyboard shortcuts and hotkey configuration coming soon..."
               :style (str "-fx-text-fill: " (css/text-muted) "; "
                           "-fx-font-style: italic;")}]})


;; Main Settings Tab


(defn settings-tab
  "Complete settings tab with input configuration sections."
  [{:keys [fx/context]}]
  {:fx/type :scroll-pane
   :fit-to-width true
   :style (str "-fx-background-color: " (css/bg-primary) "; "
               "-fx-background: " (css/bg-primary) ";")
   :content {:fx/type :v-box
             :padding 20
             :spacing 20
             :style (str "-fx-background-color: " (css/bg-primary) ";")
             :children [;; Page Title
                        {:fx/type :v-box
                         :spacing 4
                         :children [{:fx/type :label
                                     :text "Settings"
                                     :style (str "-fx-font-size: 24; -fx-font-weight: bold; "
                                                 "-fx-text-fill: " (css/text-primary) ";")}
                                    {:fx/type :label
                                     :text "Configure input devices and application preferences"
                                     :style (str "-fx-text-fill: " (css/text-muted) "; "
                                                 "-fx-font-size: 11;")}]}
                        
                        ;; Input Devices Section Header
                        {:fx/type :label
                         :text "INPUT DEVICES"
                         :style (str "-fx-text-fill: " (css/text-secondary) "; "
                                     "-fx-font-size: 12; "
                                     "-fx-font-weight: bold;")}
                        
                        ;; MIDI Configuration Section
                        {:fx/type midi-settings/midi-settings-section}
                        
                        ;; OSC Configuration Section
                        {:fx/type osc-settings/osc-settings-section}
                        
                        ;; Keyboard Configuration Section (placeholder)
                        {:fx/type keyboard-settings-section}
                        
                        ;; Spacer
                        {:fx/type :region
                         :pref-height 20}]}})
