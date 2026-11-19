(ns laser-show.views.status-bar
  "Status bar component showing current state information."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.css.core :as css]
            [laser-show.views.components.grid-cell :as grid-cell]
            [laser-show.views.dialogs.common :as common]
            [clj-font-awesome.core :as fa]))


;; Status Items


(defn status-item
  "A single status bar item with label and value. :id and :tooltip (text)
   go on the value; :value-color replaces its text colour."
  [{:keys [id label value value-width monospace? alignment value-color tooltip]
    :or {alignment :center-left}}]
  {:fx/type :h-box
   :spacing 4
   :alignment :center-left
   :children [{:fx/type :label
               :text (str label ":")
               :style (str "-fx-text-fill: " (css/text-muted) "; -fx-font-size: 11;")}
              (cond-> {:fx/type :label
                       :text (str value)
                       :style (str "-fx-text-fill: " (or value-color (css/text-secondary)) "; "
                                   "-fx-font-size: 11;"
                                   (when monospace?
                                     " -fx-font-family: 'Consolas', 'Monaco', monospace;"))}
                id (assoc :id id)
                tooltip (assoc :tooltip {:fx/type :tooltip :text tooltip})
                value-width (assoc :min-width value-width
                                   :pref-width value-width
                                   :alignment alignment))]})


;; Notice


(def ^:private notice-looks
  {:error {:icon :circle-exclamation :color css/red}
   :warning {:icon :triangle-exclamation :color css/accent-warning}
   :info {:icon :circle-info :color css/text-secondary}})

(defn notice-status
  "The notice a failure leaves ([:ui :notice]), at the left. It goes after
   6 s (its timer) or on a click."
  [{:keys [fx/context]}]
  (if-let [{:keys [id text level]} (fx/sub-ctx context subs/notice)]
    (let [{:keys [icon color]} (get notice-looks level (:info notice-looks))]
      {:fx/type :h-box
       :id "status-notice"
       :spacing 4
       :alignment :center-left
       :style "-fx-cursor: hand;"
       :on-mouse-clicked {:event/type :ui/clear-notice :id id}
       :children [{:fx/type fa/icon
                   :name icon
                   :size 10
                   :color (color)}
                  {:fx/type :label
                   :id "status-notice-text"
                   :text text
                   :style (str "-fx-text-fill: " (color) "; -fx-font-size: 11;")}]})
    {:fx/type :label :text "" :pref-width 0}))


;; What Plays


(def ^:private max-named-cues 2)

(defn playing-list
  "\"A1 circle · A2 square · +2\": the first max-named-cues of the cues
   that play (subs/playing-cues, in grid order) by cell and by the name on
   their cell, then how many more. nil when nothing plays."
  [cues]
  (when (seq cues)
    (let [named (for [{:keys [col row] :as cue} (take max-named-cues cues)]
                  (str/trim (str (common/cell-label col row) " " (grid-cell/cell-label cue))))
          more (- (count cues) max-named-cues)]
      (str/join " · " (cond-> (vec named)
                        (pos? more) (conj (str "+" more)))))))

(defn playing-status
  "What plays (playing-list). Held cues don't play: nothing draws them."
  [{:keys [fx/context]}]
  (if-let [text (playing-list (fx/sub-ctx context subs/playing-cues))]
    {:fx/type status-item
     :id "status-playing"
     :label "Playing"
     :value text}
    {:fx/type :label :text "" :pref-width 0}))


;; Frame Stats


(defn- format-us
  "Format microseconds with consistent width (5 digits, right-aligned)."
  [us]
  (format "%5dµs" (long (or us 0))))

(defn frame-stats-status
  "Status showing frame generation latency stats and IDN streaming stats."
  [{:keys [fx/context]}]
  (let [{:keys [avg-base-us avg-effects-us avg-latency-us
                p95-latency-us max-latency-us
                avg-idn-us p95-idn-us max-idn-us] :as stats}
        (fx/sub-ctx context subs/frame-stats)]
    (if (and stats avg-latency-us)
      {:fx/type :h-box
       :spacing 16
       :children (cond-> [{:fx/type status-item
                           :label "Base"
                           :value (format-us avg-base-us)
                           :value-width 56
                           :monospace? true}
                          {:fx/type status-item
                           :label "Effects"
                           :value (format-us avg-effects-us)
                           :value-width 56
                           :monospace? true}
                          {:fx/type status-item
                           :label "Total (avg)"
                           :value (format-us avg-latency-us)
                           :value-width 56
                           :monospace? true}
                          {:fx/type status-item
                           :label "p95"
                           :value (format-us p95-latency-us)
                           :value-width 56
                           :monospace? true}
                          {:fx/type status-item
                           :label "max"
                           :value (format-us max-latency-us)
                           :value-width 56
                           :monospace? true}]
                  avg-idn-us
                  (conj {:fx/type status-item
                         :label "IDN"
                         :value (str (format-us avg-idn-us)
                                     " (p95: " (format-us p95-idn-us)
                                     ", max: " (format-us max-idn-us) ")")
                         :value-width 245
                         :monospace? true}))}
      {:fx/type :label :text "" :pref-width 0})))


;; Output Health


(defn output-health-text
  "How the output does while streaming is on, from the toolbar's connection
   status (subs/connection-status) and each projector's output status
   ([:backend :streaming :status :projectors]): the frame rate the projectors
   draw (the slowest and the fastest) and how many fail. nil while streaming
   is off or has nothing to stream, which the toolbar says."
  [{:keys [running? state errors]} projectors]
  (let [rates (sort (keep (fn [{s :state fps :fps}] (when (= :streaming s) fps)) (vals projectors)))
        rate (when (seq rates)
               (let [low (double (first rates))
                     high (double (last rates))]
                 (if (== low high)
                   (format "%.1f fps" low)
                   (format "%.1f–%.1f fps" low high))))
        failing (when (seq errors)
                  (str (count errors) (if (= 1 (count errors)) " error" " errors")))]
    (when running?
      (case state
        :starting "Starting…"
        (:streaming :error) (or (not-empty (str/join " · " (remove nil? [rate failing])))
                                "Starting…")
        nil))))

(defn output-health
  "The output's health (output-health-text); the errors in its tooltip."
  [{:keys [fx/context]}]
  (let [{:keys [errors] :as connection} (fx/sub-ctx context subs/connection-status)
        text (output-health-text connection (:projectors (fx/sub-ctx context subs/streaming-status)))]
    (if text
      {:fx/type status-item
       :id "status-output-health"
       :label "Output"
       :value text
       :value-color (when (seq errors) (css/red))
       :tooltip (when (seq errors) (str/join "\n" errors))}
      {:fx/type :label :text "" :pref-width 0})))


;; Project Status


(defn project-status
  "Status showing project dirty state."
  [{:keys [fx/context]}]
  (let [{:keys [dirty? has-project?]} (fx/sub-ctx context subs/project-status)]
    (if has-project?
      {:fx/type :h-box
       :spacing 4
       :alignment :center-left
       :children [{:fx/type fa/icon
                   :name (if dirty? :circle :check)
                   :size 10
                   :style-class [(if dirty? "icon-warning" "icon-success")]}
                  {:fx/type :label
                   :text (if dirty? "Modified" "Saved")
                   :style (str "-fx-text-fill: "
                              (if dirty? (css/accent-warning) (css/accent-success))
                              "; -fx-font-size: 11;")}]}
      {:fx/type :label :text "" :pref-width 0})))


;; Main Status Bar


(defn status-bar
  "Main status bar component: the notice and what plays at the left; the
   output's health at the right, with the frame times in dev mode."
  [{:keys [fx/context]}]
  {:fx/type :h-box
   :id "status-bar"
   :style (str "-fx-background-color: " (css/bg-surface) "; "
               "-fx-padding: 4 16; "
               "-fx-border-color: " (css/border-default) "; "
               "-fx-border-width: 1 0 0 0;")
   :spacing 24
   :alignment :center-left
   :children (cond-> [{:fx/type notice-status}
                      {:fx/type playing-status}
                      {:fx/type :region :h-box/hgrow :always}] ;; Spacer
               (fx/sub-ctx context subs/dev-mode?) (conj {:fx/type frame-stats-status})
               true (conj {:fx/type output-health}
                          {:fx/type project-status}))})
