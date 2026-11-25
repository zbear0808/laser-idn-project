(ns laser-show.views.dialogs.timeline-cue-editor
  "Timeline-based cue chain editor dialog.
   
   An alternate editor for cue chains that uses a multi-track timeline
   rather than the list-based layout of the standard cue chain editor.
   
   Provides:
   - Timeline view for sequencing items with start/duration
   - The cue's Target (zones plan Z4) and its zone pattern (Z5)
   - Trigger mode picker
   - Editable chain name"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.events.core :as events]
            [laser-show.views.components.inline-edit :as inline-edit]
            [laser-show.views.components.visual-editors.timeline-editor :as timeline-editor]
            [laser-show.views.components.load-warning :as load-warning]
            [laser-show.views.components.target-control :as target-control]
            [laser-show.views.components.zone-pattern-editor :as zone-pattern-editor]
            [laser-show.views.dialogs.common :as common]
            [laser-show.views.keys :as keys]
            [laser-show.routing.targets :as targets])
  (:import [javafx.scene.input KeyCode KeyEvent]))


;; Main Content


(defn- timeline-cue-editor-content
  "Main content of the timeline-based cue chain editor.
   Center: timeline-editor (its toolbar starts with the editable name)
   Footer: the cue's Target and zone pattern, trigger mode, close button"
  [{:keys [fx/context]}]
  (let [dialog-data (fx/sub-ctx context subs/dialog-data :timeline-cue-editor)
        {:keys [col row]} dialog-data

        ;; Cue chain data
        ;; Only this cell's chain: edits to other cues don't re-render the dialog
        cue-chain (fx/sub-ctx context subs/cue-cell-chain col row)

        ;; Timeline UI state: this cue's view, plus editor-wide prefs
        timeline-ui (fx/sub-ctx context subs/timeline-ui col row)
        timeline-prefs (fx/sub-ctx context subs/timeline-prefs)


        ;; Chain name
        chain-name (:name cue-chain)
        default-name (common/default-name col row)
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
       :cue-key [col row]}

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
                  ;; The cue's zone pattern (zones plan Z5)
                  {:fx/type zone-pattern-editor/pattern-field
                   :id "pattern-chip-cue"
                   :cue-key [col row]}
                  {:fx/type common/trigger-mode-dropdown
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


(defn- handle-space
  "Space plays and pauses the timeline. It only gets here when no control
   used it (buttons do), and never while you type (keys/typing-target?)."
  [^KeyEvent e]
  (when (and (= KeyCode/SPACE (.getCode e))
             (not (or (.isShortcutDown e) (.isControlDown e)))
             (not (keys/typing-target? e)))
    (.consume e)
    (events/dispatch! {:event/type :timeline/toggle-play})))

(defn timeline-cue-editor-dialog
  "The timeline-based cue chain editor dialog window."
  [{:keys [fx/context]}]
  (let [{:keys [col row]} (fx/sub-ctx context subs/dialog-data :timeline-cue-editor)
        chain-name (:name (fx/sub-ctx context subs/cue-cell-chain col row))]
    {:fx/type common/dialog-stage
     :dialog-id :timeline-cue-editor
     :title (common/window-title "Timeline Editor" chain-name col row)
     :claimed-keys #{:space}
     :on-key-extra handle-space
     :content {:fx/type timeline-cue-editor-content}}))
