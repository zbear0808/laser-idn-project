(ns laser-show.views.dialogs.cell-trigger-editor
  "Dedicated dialog for configuring MIDI, OSC, and Keyboard triggers for a grid cell.
   
   Separates trigger configuration from cue and effect content pickers, since triggers
   apply to the cell as a whole."
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.css.core :as css]
            [laser-show.views.components.trigger-bank :as trigger-bank]))

(defn- cell-trigger-editor-content
  "Main content of the trigger editor dialog."
  [{:keys [fx/context]}]
  (let [dialog-data (fx/sub-ctx context subs/dialog-data :trigger-editor)
        {:keys [col row cell-mode] :or {cell-mode :cue}} dialog-data
        trigger-map (fx/sub-ctx context subs/input-trigger-map)
        learning-target (fx/sub-ctx context subs/input-learning-target)
        cell-label (if (= cell-mode :effects)
                     (let [display-data (fx/sub-ctx context subs/effect-cell-display-data col row)]
                       (or (:name display-data) "Effect Chain"))
                     (let [display-data (fx/sub-ctx context subs/cell-display-data col row)]
                       (or (:name display-data) "Cue Chain")))
        cell-coord-str (str "[" (char (+ 65 (or row 0))) ", " (inc (or col 0)) "]")
        mode-title (if (= cell-mode :effects) "Effect Cell" "Cue Cell")]
    {:fx/type :v-box
     :spacing 12
     :padding 16
     :style-class ["dialog-content"]
     :pref-width 420
     :children
     [;; Header
      {:fx/type :v-box
       :spacing 4
       :style-class "dialog-header"
       :children
       [{:fx/type :label
         :text (str mode-title " " cell-coord-str)
         :style-class "header-primary"}
        {:fx/type :label
         :text (str "Target: " cell-label)
         :style-class "label-secondary"}]}

      ;; Embedded Trigger Bank
      {:fx/type :v-box
       :v-box/vgrow :always
       :children
       [{:fx/type trigger-bank/trigger-bank
         :col col
         :row row
         :cell-mode cell-mode
         :trigger-map trigger-map
         :learning-target learning-target}]}

      ;; Footer
      {:fx/type :h-box
       :alignment :center-right
       :padding {:top 8}
       :style-class "dialog-footer"
       :children
       [{:fx/type :button
         :text "Done"
         :style-class ["button-primary"]
         :on-action {:event/type :ui/close-dialog
                     :dialog-id :trigger-editor}}]}]}))

(defn cell-trigger-editor-dialog
  "The cell trigger editor dialog window."
  [{:keys [fx/context]}]
  (let [open? (fx/sub-ctx context subs/dialog-open? :trigger-editor)
        dialog-data (fx/sub-ctx context subs/dialog-data :trigger-editor)
        {:keys [col row cell-mode] :or {cell-mode :cue}} dialog-data
        mode-title (if (= cell-mode :effects) "Effect Cell" "Cue Cell")
        cell-coord-str (str "[" (char (+ 65 (or row 0))) ", " (inc (or col 0)) "]")
        stylesheets (css/dialog-stylesheet-urls)]
    {:fx/type :stage
     :showing open?
     :title (str "Triggers — " mode-title " " cell-coord-str)
     :width 440
     :height 380
     :modality :none
     :on-close-request {:event/type :ui/close-dialog :dialog-id :trigger-editor}
     :scene {:fx/type :scene
             :stylesheets stylesheets
             :root {:fx/type cell-trigger-editor-content}}}))

