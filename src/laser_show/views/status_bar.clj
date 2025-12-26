(ns laser-show.views.status-bar
  "Status bar component showing current state information."
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]))

;; ============================================================================
;; Status Items
;; ============================================================================

(defn status-item
  "A single status bar item with label and value."
  [{:keys [label value]}]
  {:fx/type :h-box
   :spacing 4
   :alignment :center-left
   :children [{:fx/type :label
               :text (str label ":")
               :style "-fx-text-fill: #808080; -fx-font-size: 11;"}
              {:fx/type :label
               :text (str value)
               :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}]})

;; ============================================================================
;; Cell Status
;; ============================================================================

(defn cell-status
  "Status showing active/selected cell."
  [{:keys [fx/context]}]
  (let [active-cell (fx/sub-ctx context subs/active-cell)
        selected-cell (fx/sub-ctx context subs/selected-cell)
        children (cond-> []
                   active-cell 
                   (conj {:fx/type status-item
                          :label "Active"
                          :value (str "[" (first active-cell) "," (second active-cell) "]")})
                   selected-cell
                   (conj {:fx/type status-item
                          :label "Selected"
                          :value (str "[" (first selected-cell) "," (second selected-cell) "]")}))]
    {:fx/type :h-box
     :spacing 16
     :alignment :center-left
     :children (if (empty? children)
                 [{:fx/type :label :text "" :pref-width 0}]
                 children)}))

;; ============================================================================
;; Preset Status
;; ============================================================================

(defn preset-status
  "Status showing active preset."
  [{:keys [fx/context]}]
  (let [preset (fx/sub-ctx context subs/active-preset)]
    (if preset
      {:fx/type status-item
       :label "Preset"
       :value (name preset)}
      {:fx/type :label :text "" :pref-width 0})))

;; ============================================================================
;; Frame Stats
;; ============================================================================

(defn frame-stats-status
  "Status showing frame rendering stats."
  [{:keys [fx/context]}]
  (let [stats (fx/sub-ctx context subs/frame-stats)]
    {:fx/type :h-box
     :spacing 16
     :children [{:fx/type status-item
                 :label "Frame"
                 :value (str (:last-render-ms stats 0) "ms")}
                {:fx/type status-item
                 :label "FPS"
                 :value (str (:fps stats 0))}]}))

;; ============================================================================
;; Project Status
;; ============================================================================

(defn project-status
  "Status showing project dirty state."
  [{:keys [fx/context]}]
  (let [{:keys [dirty? has-project?]} (fx/sub-ctx context subs/project-status)]
    (if has-project?
      {:fx/type :label
       :text (if dirty? "● Modified" "✓ Saved")
       :style (str "-fx-text-fill: "
                   (if dirty? "#FF9800" "#4CAF50")
                   "; -fx-font-size: 11;")}
      {:fx/type :label :text "" :pref-width 0})))

;; ============================================================================
;; Main Status Bar
;; ============================================================================

(defn status-bar
  "Main status bar component."
  [{:keys [fx/context]}]
  {:fx/type :h-box
   :style "-fx-background-color: #252525; -fx-padding: 4 16; -fx-border-color: #3D3D3D; -fx-border-width: 1 0 0 0;"
   :spacing 24
   :alignment :center-left
   :children [{:fx/type cell-status}
              {:fx/type preset-status}
              {:fx/type :region :h-box/hgrow :always} ;; Spacer
              {:fx/type frame-stats-status}
              {:fx/type project-status}]})
