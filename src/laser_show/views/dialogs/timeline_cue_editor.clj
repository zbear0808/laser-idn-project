(ns laser-show.views.dialogs.timeline-cue-editor
  "The timeline editor dialog: the editor of one library show
   (plans/2026-11-25-timeline-shows/05-editor-rekey.md §4.3).

   The dialog's data names the show by :show-id. Provides:
   - Timeline view for sequencing items with start/duration
   - The show's Target (zones plan Z4) and its zone pattern (Z5)
   - Editable show name
   - A placeholder while the show isn't in the library (an undo took it
     away; a redo brings the editor back)"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.events.core :as events]
            [laser-show.timeline.shows :as shows]
            [laser-show.views.components.inline-edit :as inline-edit]
            [laser-show.views.components.visual-editors.timeline-editor :as timeline-editor]
            [laser-show.views.components.load-warning :as load-warning]
            [laser-show.views.components.target-control :as target-control]
            [laser-show.views.components.zone-pattern-editor :as zone-pattern-editor]
            [laser-show.views.dialogs.common :as common]
            [laser-show.views.keys :as keys]
            [laser-show.routing.targets :as targets])
  (:import [javafx.scene.input KeyCode KeyEvent]))


(def ^:private untitled "Untitled")

(def ^:private close-button
  {:fx/type :button
   :text "Close"
   :style-class "button-primary"
   :on-action {:event/type :ui/close-dialog
               :dialog-id :timeline-cue-editor}})


;; Main Content


(defn- show-gone
  "What the dialog shows while its show isn't in the library."
  [_]
  {:fx/type :v-box
   :spacing 0
   :style-class "dialog-content"
   :pref-width 1280
   :pref-height 760
   :min-width 960
   :min-height 560
   :children
   [{:fx/type :v-box
     :v-box/vgrow :always
     :alignment :center
     :children [{:fx/type :label
                 :id "timeline-show-gone"
                 :text "This show is no longer in the library."}]}
    {:fx/type :h-box
     :alignment :center-left
     :spacing 12
     :style-class "dialog-footer"
     :children [{:fx/type :region
                 :h-box/hgrow :always}
                close-button]}]})

(defn- timeline-cue-editor-content
  "Main content of the timeline editor.
   Center: timeline-editor (its toolbar starts with the editable name)
   Footer: the show's Target and zone pattern, close button"
  [{:keys [fx/context]}]
  (let [dialog-data (fx/sub-ctx context subs/dialog-data :timeline-cue-editor)
        {:keys [show-id]} dialog-data

        ;; Only this show: edits to other shows don't re-render the dialog
        show (fx/sub-ctx context subs/show-cue show-id)]
    (if (nil? show)
      {:fx/type show-gone}
      (let [;; Timeline UI state: this show's view, plus editor-wide prefs
            timeline-ui (fx/sub-ctx context subs/timeline-ui show-id)
            timeline-prefs (fx/sub-ctx context subs/timeline-prefs)
            play-key (shows/play-key show-id)
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
           :show-id show-id
           :cue show
           :timeline-ui timeline-ui
           :timeline-prefs timeline-prefs
           ;; The show's editable name sits at the left of the toolbar
           :title {:fx/type inline-edit/inline-edit-text
                   :value (:name show)
                   :placeholder untitled
                   :editing? editing-name?
                   :on-start-edit {:event/type :ui/update-dialog-data
                                   :dialog-id :timeline-cue-editor
                                   :editing-name? true}
                   :on-commit {:event/type :timeline/set-name
                               :show-id show-id}
                   :on-cancel {:event/type :ui/update-dialog-data
                               :dialog-id :timeline-cue-editor
                               :editing-name? false}}}

          ;; While the show is too heavy for a laser (zones plan Z3)
          {:fx/type load-warning/cue-load-warning
           :cue-key play-key}

          ;; Footer
          {:fx/type :h-box
           :alignment :center-left
           :spacing 12
           :style-class "dialog-footer"
           :children [{:fx/type target-control/target-field
                       :id "target-chip-cue"
                       :from-id "target-from-cue"
                       :scope :cue
                       :own (:target show)
                       ;; A show has no column: Inherit is the show default
                       :inherited (targets/cue-fallback nil nil (fx/sub-ctx context subs/default-target))
                       :on-set {:event/type :timeline/set-cue-target :show-id show-id}
                       :tooltip "Where the show's content goes, unless a clip, a group or a track sets its own"}
                      ;; The show's zone pattern (zones plan Z5)
                      {:fx/type zone-pattern-editor/pattern-field
                       :id "pattern-chip-cue"
                       :cue-key play-key}
                      {:fx/type :region
                       :h-box/hgrow :always}
                      close-button]}]}))))


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

(defn- window-title
  "\"Timeline Editor - Intro\", \"Timeline Editor - Untitled\" without a
   name, \"Timeline Editor - (deleted)\" while the show isn't there."
  [show]
  (str "Timeline Editor - "
       (cond
         (nil? show) "(deleted)"
         (seq (:name show)) (:name show)
         :else untitled)))

(defn timeline-cue-editor-dialog
  "The timeline editor's dialog window."
  [{:keys [fx/context]}]
  (let [{:keys [show-id]} (fx/sub-ctx context subs/dialog-data :timeline-cue-editor)
        show (fx/sub-ctx context subs/show-cue show-id)]
    {:fx/type common/dialog-stage
     :dialog-id :timeline-cue-editor
     :title (window-title show)
     :claimed-keys #{:space}
     :on-key-extra handle-space
     :content {:fx/type timeline-cue-editor-content}}))
