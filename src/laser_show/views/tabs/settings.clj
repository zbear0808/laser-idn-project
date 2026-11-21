(ns laser-show.views.tabs.settings
  "Settings tab for application configuration.
   
   Includes:
   - Ableton Link configuration
   - MIDI input configuration
   - OSC input configuration
   - Add-ons (output drivers from add-on jars)"
  (:require
   [laser-show.css.core :as css]
   [laser-show.views.components.addon-settings :as addon-settings]
   [laser-show.views.components.link-settings :as link-settings]
   [laser-show.views.components.midi-settings :as midi-settings]
   [laser-show.views.components.osc-settings :as osc-settings]))


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
                        
                        ;; External Sync Section Header
                        {:fx/type :label
                         :text "EXTERNAL SYNC"
                         :style (str "-fx-text-fill: " (css/text-secondary) "; "
                                     "-fx-font-size: 12; "
                                     "-fx-font-weight: bold;")}
                        
                        ;; Ableton Link Configuration Section
                        {:fx/type link-settings/link-settings-section}
                        
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

                        ;; Output Section Header
                        {:fx/type :label
                         :text "OUTPUT"
                         :style (str "-fx-text-fill: " (css/text-secondary) "; "
                                     "-fx-font-size: 12; "
                                     "-fx-font-weight: bold;")}

                        ;; Add-ons Section
                        {:fx/type addon-settings/addon-settings-section}
                        
                        ;; Spacer
                        {:fx/type :region
                         :pref-height 20}]}})
