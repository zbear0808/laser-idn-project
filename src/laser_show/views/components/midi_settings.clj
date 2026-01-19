(ns laser-show.views.components.midi-settings
  "MIDI configuration UI component for the settings tab.
   
   Provides:
   - Enable/disable MIDI input
   - Device discovery and connection
   - Channel filter configuration
   - MIDI learn mode"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.css.core :as css]
            [clojure.string :as str]))


;; Status Indicator


(defn- status-indicator
  "Colored circle indicating status."
  [{:keys [status size]
    :or {size 8}}]
  (let [color (case status
                :connected "#4CAF50"
                :disconnected "#808080"
                :learning "#2196F3"
                "#808080")]
    {:fx/type :circle
     :radius (/ size 2)
     :fill color}))


;; Device Row


(defn- midi-device-row
  "Single MIDI device row with connect/disconnect button."
  [{:keys [device-name connected?]}]
  {:fx/type :h-box
   :spacing 10
   :alignment :center-left
   :padding {:left 8 :right 8 :top 4 :bottom 4}
   :style (str "-fx-background-color: " (css/bg-elevated) "; "
               "-fx-background-radius: 4;")
   :children [{:fx/type status-indicator
               :status (if connected? :connected :disconnected)}
              {:fx/type :label
               :text device-name
               :style (str "-fx-text-fill: " (css/text-primary) ";")
               :h-box/hgrow :always}
              {:fx/type :button
               :text (if connected? "Disconnect" "Connect")
               :style-class [(if connected? "button-warning" "button-info")]
               :on-action {:event/type (if connected?
                                         :input/midi-disconnect-device
                                         :input/midi-connect-device)
                           :device-name device-name}}]})


;; Channel Filter Input


(defn- parse-channel-filter
  "Parse channel filter text into a set of channel numbers.
   Returns nil if empty, or a set of integers."
  [text]
  (when (and text (not (str/blank? text)))
    (try
      (->> (str/split text #"[,\s]+")
           (map str/trim)
           (filter (complement str/blank?))
           (map #(Integer/parseInt %))
           (filter #(and (>= % 0) (<= % 15)))
           set)
      (catch Exception _
        nil))))

(defn- format-channel-filter
  "Format channel filter set as comma-separated string."
  [channels]
  (when (and channels (seq channels))
    (str/join ", " (sort channels))))


;; MIDI Settings Section


(defn midi-settings-section
  "MIDI configuration section for settings tab."
  [{:keys [fx/context]}]
  (let [midi-config (fx/sub-ctx context subs/midi-config)
        enabled? (:enabled midi-config)
        connected-devices (:connected-devices midi-config #{})
        available-devices (:available-devices midi-config [])
        learning? (fx/sub-ctx context subs/midi-learning?)
        channel-filter (:channel-filter midi-config)]
    {:fx/type :v-box
     :spacing 12
     :style (str "-fx-background-color: " (css/bg-elevated) "; "
                 "-fx-padding: 16; "
                 "-fx-background-radius: 8;")
     :children 
     [;; Section Header
      {:fx/type :h-box
       :spacing 8
       :alignment :center-left
       :children [{:fx/type :label
                   :text "MIDI Configuration"
                   :style (str "-fx-font-size: 16; -fx-font-weight: bold; "
                               "-fx-text-fill: " (css/text-primary) ";")}
                  {:fx/type :region :h-box/hgrow :always}
                  {:fx/type :check-box
                   :text "Enabled"
                   :selected enabled?
                   :style (str "-fx-text-fill: " (css/text-secondary) ";")
                   :on-selected-changed {:event/type (if enabled?
                                                       :input/midi-disable
                                                       :input/midi-enable)}}]}
      
      ;; Device Discovery Section
      {:fx/type :v-box
       :spacing 8
       :children [{:fx/type :h-box
                   :spacing 8
                   :alignment :center-left
                   :children [{:fx/type :label
                               :text "Available Devices"
                               :style (str "-fx-text-fill: " (css/text-secondary) ";")
                               :h-box/hgrow :always}
                              {:fx/type :button
                               :text "Refresh"
                               :style-class ["button-secondary"]
                               :on-action {:event/type :input/midi-refresh-devices}}]}
                  
                  {:fx/type :v-box
                   :spacing 4
                   :children (if (seq available-devices)
                               (mapv (fn [device-name]
                                       {:fx/type midi-device-row
                                        :fx/key device-name
                                        :device-name device-name
                                        :connected? (contains? connected-devices device-name)})
                                     available-devices)
                               [{:fx/type :label
                                 :text "No MIDI devices found. Click 'Refresh' to scan."
                                 :style (str "-fx-text-fill: " (css/text-muted) "; "
                                             "-fx-font-style: italic; "
                                             "-fx-padding: 8;")}])}]}
      
      ;; Channel Filter
      {:fx/type :v-box
       :spacing 4
       :children [{:fx/type :label
                   :text "Channel Filter"
                   :style (str "-fx-text-fill: " (css/text-secondary) ";")}
                  {:fx/type :h-box
                   :spacing 8
                   :alignment :center-left
                   :children [{:fx/type :text-field
                               :prompt-text "All channels (leave empty)"
                               :text (format-channel-filter channel-filter)
                               :pref-width 200
                               :style-class ["text-field-dark"]
                               :on-action (fn [evt]
                                            (let [text (.getText (.getSource evt))
                                                  channels (parse-channel-filter text)]
                                              {:event/type :input/midi-set-channel-filter
                                               :channels channels}))}
                              {:fx/type :label
                               :text "(comma-separated, 0-15)"
                               :style (str "-fx-text-fill: " (css/text-muted) "; "
                                           "-fx-font-size: 10;")}]}]}
      
      ;; MIDI Learn Mode
      {:fx/type :v-box
       :spacing 4
       :children [{:fx/type :label
                   :text "MIDI Learn"
                   :style (str "-fx-text-fill: " (css/text-secondary) ";")}
                  {:fx/type :h-box
                   :spacing 8
                   :alignment :center-left
                   :children (filterv some?
                               [{:fx/type :button
                                 :text (if learning? "Cancel Learn" "Start Learn Mode")
                                 :style-class [(if learning? "button-warning" "button-info")]
                                 :on-action {:event/type (if learning?
                                                          :input/midi-cancel-learn
                                                          :input/midi-start-learn)}}
                                (when learning?
                                  {:fx/type :h-box
                                   :spacing 4
                                   :alignment :center-left
                                   :children [{:fx/type status-indicator
                                               :status :learning}
                                              {:fx/type :label
                                               :text "Waiting for MIDI input..."
                                               :style (str "-fx-text-fill: " (css/selection-bg) ";")}]})])}]}
      
      ;; Auto-connect button
      {:fx/type :h-box
       :spacing 8
       :children [{:fx/type :region :h-box/hgrow :always}
                  {:fx/type :button
                   :text "Auto-Connect"
                   :style-class ["button-secondary"]
                   :on-action {:event/type :input/midi-auto-connect}}
                  {:fx/type :button
                   :text "Disconnect All"
                   :style-class ["button-warning"]
                   :disable (empty? connected-devices)
                   :on-action {:event/type :input/midi-disconnect-all}}]}]}))
