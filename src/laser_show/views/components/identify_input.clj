(ns laser-show.views.components.identify-input
  "Settings' Identify MIDI input / Identify OSC input (reactivity plan 24):
   one row on each card. Its button waits for the next input of its source
   (:input/midi-start-learn, :input/osc-start-learn), only while that source
   can send one (MIDI on with an input connected, OSC on and listening);
   while it waits it is Cancel, for the attempt it shows, beside the
   waiting prompt. Then the card says what came in, or that nothing did.
   Identifying binds nothing and leaves every input going where it goes."
  (:require [cljfx.api :as fx]
            [laser-show.css.core :as css]
            [laser-show.subs :as subs]))

(defn input-label
  "An identified input (its :type :source :channel :note :control :program
   :id :path) as the user knows it: \"MIDI Ch 2 Note 60\" (channels from
   1), \"MIDI Ch 2 Note 60 off\", \"MIDI Ch 1 CC 7\", \"MIDI Ch 1 Program
   3\", \"OSC /1/push1\"."
  [{:keys [type source channel note control program id path]}]
  (case source
    :midi (str "MIDI Ch " (inc (or channel 0)) " "
               (case type
                 :note-on (str "Note " note)
                 :note-off (str "Note " note " off")
                 :control-change (str "CC " control)
                 :program-change (str "Program " program)
                 (name (or type :input))))
    :osc (str "OSC " (or path id))
    (str (some-> source name) " " (or path id))))

(defn result-text
  "What the card says of its last identify ({:status :captured
   :input-event}, {:status :timeout|:failed}), or nil before one."
  [{:keys [status input-event]}]
  (case status
    :captured (str "Detected " (input-label input-event))
    :timeout "No input received."
    :failed "Could not identify the input."
    nil))

(defn identify-input
  "The Identify row of source's card (props :source, :midi or :osc)."
  [{:keys [fx/context source]}]
  (let [{:keys [learn-id result available?]} (fx/sub-ctx context subs/settings-learn source)
        waiting? (some? learn-id)
        id (str (name source) "-identify")
        source-name (if (= :osc source) "OSC" "MIDI")
        text (result-text result)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children (filterv some?
                        [{:fx/type :button
                          :id (str id "-button")
                          :text (if waiting? "Cancel" (str "Identify " source-name " input"))
                          :style-class [(if waiting? "button-warning" "button-primary")]
                          :disable (and (not waiting?) (not available?))
                          :on-action (if waiting?
                                       {:event/type (if (= :osc source) :input/osc-cancel-learn :input/midi-cancel-learn)
                                        :learn-id learn-id}
                                       {:event/type (if (= :osc source) :input/osc-start-learn :input/midi-start-learn)})}
                         (when waiting?
                           {:fx/type :h-box
                            :id (str id "-waiting")
                            :spacing 4
                            :alignment :center-left
                            :children [{:fx/type :circle
                                        :radius 4
                                        :fill "#2196F3"}
                                       {:fx/type :label
                                        :text (str "Waiting for " source-name " input...")
                                        :style (str "-fx-text-fill: " (css/selection-bg) ";")}]})
                         (when (and (not waiting?) text)
                           {:fx/type :label
                            :id (str id "-result")
                            :text text
                            :style (str "-fx-text-fill: " (css/text-secondary) ";")})])}))
