(ns laser-show.views.dialogs.timeline-cue-editor
  "Timeline-based cue chain editor dialog.
   
   An alternate editor for cue chains that uses a multi-track timeline
   rather than the list-based layout of the standard cue chain editor.
   
   Provides:
   - Timeline view for sequencing items with start/duration
   - The cue's Target (zones plan Z4)
   - Trigger mode picker
   - Editable chain name"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.events.core :as events]
            [laser-show.css.core :as css]
            [laser-show.views.components.inline-edit :as inline-edit]
            [laser-show.views.components.visual-editors.timeline-editor :as timeline-editor]
            [laser-show.views.components.load-warning :as load-warning]
            [laser-show.views.components.target-control :as target-control]
            [laser-show.routing.targets :as targets]
            [laser-show.common.util :as u])
  (:import [javafx.scene.input KeyCode KeyEvent]))


;; Trigger Mode Picker (shared with cue-chain-editor)


(def ^:private trigger-mode-options
  [{:id :toggle :name "Toggle"}
   {:id :retrigger :name "Retrigger"}])

(defn- trigger-mode-dropdown
  [{:keys [col row trigger-mode]}]
  (let [current-mode (or trigger-mode :toggle)
        current-option (or (u/seek #(= (:id %) current-mode) trigger-mode-options)
                           (first trigger-mode-options))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text "Trigger:"
                 :style-class "zone-picker-label"}
                {:fx/type :combo-box
                 :value current-option
                 :pref-width 120
                 :items trigger-mode-options
                 :button-cell (fn [mode]
                                {:text (or (:name mode) "Toggle")})
                 :cell-factory {:fx/cell-type :list-cell
                                :describe (fn [mode]
                                            {:text (or (:name mode) "Toggle")})}
                 :on-value-changed {:event/type :cue-chain/set-trigger-mode
                                    :col col
                                    :row row}}]}))


;; Main Content


(defn- timeline-cue-editor-content
  "Main content of the timeline-based cue chain editor.
   Center: timeline-editor (its toolbar starts with the editable name)
   Footer: destination zone, trigger mode, close button"
  [{:keys [fx/context]}]
  (let [dialog-data (fx/sub-ctx context subs/dialog-data :timeline-cue-editor)
        {:keys [col row]} dialog-data

        ;; Cue chain data
        ;; Only this cell's chain: edits to other cues don't re-render the dialog
        cue-chain (fx/sub-ctx context subs/cue-cell-chain col row)

        destination-zone-id (:target cue-chain)

        ;; Timeline UI state: this cue's view, plus editor-wide prefs
        timeline-ui (fx/sub-ctx context subs/timeline-ui col row)
        timeline-prefs (fx/sub-ctx context subs/timeline-prefs)


        ;; Chain name
        chain-name (:name cue-chain)
        default-name (str "Cell " (char (+ 65 (or row 0))) (inc (or col 0)))
        editing-name? (:editing-name? dialog-data false)]
    {:fx/type :v-box
     :spacing 0
     :style-class "dialog-content"
     :pref-width 1280
     :pref-height 760
     :min-width 960
     :min-height 560
     :children
     [{:fx/type timeline-editor/timeline-editor
       :v-box/vgrow :always
       :col col
       :row row
       :cue cue-chain
       :zone-groups (fx/sub-ctx context subs/target-groups-by-id)
       :destination-zone-id destination-zone-id
       :timeline-ui timeline-ui
       :timeline-prefs timeline-prefs
       ;; The cue's editable name sits at the left of the toolbar
       :title {:fx/type inline-edit/inline-edit-text
               :value chain-name
               :placeholder default-name
               :editing? editing-name?
               :on-start-edit {:event/type :ui/update-dialog-data
                               :dialog-id :timeline-cue-editor
                               :editing-name? true}
               :on-commit {:event/type :cue-chain/set-name
                           :dialog-id :timeline-cue-editor
                           :col col
                           :row row}
               :on-cancel {:event/type :ui/update-dialog-data
                           :dialog-id :timeline-cue-editor
                           :editing-name? false}}}

      ;; While the cue is too heavy for a laser (zones plan Z3)
      {:fx/type load-warning/cue-load-warning
       :col col
       :row row}

      ;; Footer
      {:fx/type :h-box
       :alignment :center-left
       :spacing 12
       :style-class "dialog-footer"
       :children [{:fx/type target-control/target-field
                   :id "target-chip-cue"
                   :from-id "target-from-cue"
                   :scope :cue
                   :own (:target cue-chain)
                   :inherited (targets/cue-fallback nil (fx/sub-ctx context subs/column-target col)
                                                    (fx/sub-ctx context subs/default-target) col)
                   :on-set {:event/type :cue-chain/set-target :col col :row row}
                   :tooltip "Where the cue's content goes, unless a clip, a group or a track sets its own"}
                  {:fx/type trigger-mode-dropdown
                   :col col
                   :row row
                   :trigger-mode (:trigger-mode cue-chain)}
                  {:fx/type :region
                   :h-box/hgrow :always}
                  {:fx/type :button
                   :text "Open in Cue Chain Editor"
                   :style-class "button-secondary"
                   :on-action (fn [_]
                                (events/dispatch! {:event/type :ui/close-dialog
                                                   :dialog-id :timeline-cue-editor})
                                (events/dispatch! {:event/type :cue-chain/open-editor
                                                   :col col
                                                   :row row}))}
                  {:fx/type :button
                   :text "Close"
                   :style-class "button-primary"
                   :on-action {:event/type :ui/close-dialog
                               :dialog-id :timeline-cue-editor}}]}]}))


;; Dialog Window


(defn- handle-scene-key-pressed
  "Handles undo/redo shortcuts and Space (play/pause the timeline) at the
   scene level. Space only gets here when no control used it (text fields
   and buttons do)."
  [^KeyEvent e]
  (let [code (.getCode e)
        shortcut? (or (.isShortcutDown e) (.isControlDown e))
        shift? (.isShiftDown e)]
    (when (and (= code KeyCode/SPACE) (not shortcut?)
               (not (instance? javafx.scene.control.TextInputControl (.getTarget e))))
      (.consume e)
      (events/dispatch! {:event/type :timeline/toggle-play}))
    (when shortcut?
      (cond
        (and (= code KeyCode/Z) shift?)
        (do (.consume e) (events/dispatch! {:event/type :edit/redo}))
        (= code KeyCode/Y)
        (do (.consume e) (events/dispatch! {:event/type :edit/redo}))
        (= code KeyCode/Z)
        (do (.consume e) (events/dispatch! {:event/type :edit/undo}))))))

(defn- timeline-cue-editor-scene
  [{:keys [stylesheets]}]
  {:fx/type :scene
   :stylesheets stylesheets
   :on-key-pressed handle-scene-key-pressed
   :root {:fx/type timeline-cue-editor-content}})

(defn timeline-cue-editor-dialog
  "The timeline-based cue chain editor dialog window."
  [{:keys [fx/context]}]
  (let [open? (fx/sub-ctx context subs/dialog-open? :timeline-cue-editor)
        dialog-data (fx/sub-ctx context subs/dialog-data :timeline-cue-editor)
        {:keys [col row]} dialog-data
        chain-name (:name (fx/sub-ctx context subs/cue-cell-chain col row))
        stylesheets (css/dialog-stylesheet-urls)
        cell-id (str "Cell " (char (+ 65 (or row 0))) (inc (or col 0)))
        window-title (str "Timeline Editor - "
                          (if (seq chain-name)
                            (str chain-name " (" cell-id ")")
                            cell-id))]
    {:fx/type :stage
     :showing open?
     :title window-title
     :modality :none
     :on-close-request {:event/type :ui/close-dialog :dialog-id :timeline-cue-editor}
     :scene {:fx/type timeline-cue-editor-scene
             :stylesheets stylesheets}}))
