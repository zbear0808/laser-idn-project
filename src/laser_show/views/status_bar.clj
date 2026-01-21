(ns laser-show.views.status-bar
  "Status bar component showing current state information."
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.css.core :as css]))


;; Status Items


(defn status-item
  "A single status bar item with label and value."
  [{:keys [label value]}]
  {:fx/type :h-box
   :spacing 4
   :alignment :center-left
   :children [{:fx/type :label
               :text (str label ":")
               :style (str "-fx-text-fill: " (css/text-muted) "; -fx-font-size: 11;")}
              {:fx/type :label
               :text (str value)
               :style (str "-fx-text-fill: " (css/text-secondary) "; -fx-font-size: 11;")}]})


;; Cell Status


(defn cell-status
  "Status showing active cell."
  [{:keys [fx/context]}]
  (let [active-cell (fx/sub-ctx context subs/active-cell)]
    (if active-cell
      {:fx/type status-item
       :label "Active"
       :value (str "[" (first active-cell) "," (second active-cell) "]")}
      {:fx/type :label :text "" :pref-width 0})))


;; Preset Status


(defn preset-status
  "Status showing active preset."
  [{:keys [fx/context]}]
  (let [preset (fx/sub-ctx context subs/active-preset)]
    (if preset
      {:fx/type status-item
       :label "Preset"
       :value (name preset)}
      {:fx/type :label :text "" :pref-width 0})))


;; Frame Stats


(defn frame-stats-status
  "Status showing frame generation latency stats."
  [{:keys [fx/context]}]
  (let [stats (fx/sub-ctx context subs/frame-stats)]
    (if (and stats (:avg-latency-us stats))
      {:fx/type :h-box
       :spacing 16
       :children [{:fx/type status-item
                   :label "Base"
                   :value (str (long (or (:avg-base-us stats) 0)) "µs")}
                  {:fx/type status-item
                   :label "Effects"
                   :value (str (long (or (:avg-effects-us stats) 0)) "µs")}
                  {:fx/type status-item
                   :label "Total (avg)"
                   :value (str (long (:avg-latency-us stats)) "µs")}
                  {:fx/type status-item
                   :label "p95"
                   :value (str (long (:p95-latency-us stats)) "µs")}
                  {:fx/type status-item
                   :label "max"
                   :value (str (long (:max-latency-us stats)) "µs")}]}
      {:fx/type :label :text "" :pref-width 0})))


;; Project Status


(defn project-status
  "Status showing project dirty state."
  [{:keys [fx/context]}]
  (let [{:keys [dirty? has-project?]} (fx/sub-ctx context subs/project-status)]
    (if has-project?
      {:fx/type :label
       :text (if dirty? "● Modified" "✓ Saved")
       :style (str "-fx-text-fill: "
                   (if dirty? (css/accent-warning) (css/accent-success))
                   "; -fx-font-size: 11;")}
      {:fx/type :label :text "" :pref-width 0})))


;; Main Status Bar


(defn status-bar
  "Main status bar component."
  [{:keys [fx/context]}]
  {:fx/type :h-box
   :style (str "-fx-background-color: " (css/bg-surface) "; "
               "-fx-padding: 4 16; "
               "-fx-border-color: " (css/border-default) "; "
               "-fx-border-width: 1 0 0 0;")
   :spacing 24
   :alignment :center-left
   :children [{:fx/type cell-status}
              {:fx/type preset-status}
              {:fx/type :region :h-box/hgrow :always} ;; Spacer
              {:fx/type frame-stats-status}
              {:fx/type project-status}]})
