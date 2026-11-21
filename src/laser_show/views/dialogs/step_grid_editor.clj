(ns laser-show.views.dialogs.step-grid-editor
  "The step grid window (zones plan Z6, plans/2026-11-19-zones/08 §7):
   \"Step Grid — Cue A1 \\\"Beams\\\" · Zone pattern\", or \"Step Grid —
   Effects B3 \\\"Run\\\" · Zone pattern\". Its own non-modal window
   (Z6-Q5 A), opened from a cue's Pattern chip (Step grid…), its pattern
   window and an effects cell's editor (:step-grid/open). A row per zone
   and a column per step: click or drag to paint, the keys move the cursor
   and toggle, and ⌘Z / ⇧⌘Z / ⌘Y (Ctrl too) undo and redo as the chain
   editors do. While the pattern's keyframes are on, or the show lock is,
   the grid is greyed out and says why."
  (:require [cljfx.api :as fx]
            [laser-show.css.core :as css]
            [laser-show.events.core :as events]
            [laser-show.subs :as subs]
            [laser-show.views.components.step-grid :as step-grid]
            [laser-show.views.dialogs.common :as common])
  (:import [javafx.scene.input KeyCode KeyEvent]))

(def ^:private dialog-id :step-grid-editor)

(defn window-title
  "\"Step Grid — Cue A1 \\\"Beams\\\" · Zone pattern\": where the pattern
   is, and its cue's or cell's name when it has one."
  [{:keys [domain entity-key]} chain-name]
  (let [[col row] entity-key]
    (str "Step Grid — " (if (= :effect-chains domain) "Effects " "Cue ") (common/cell-label col row)
         (when (seq chain-name) (str " \"" chain-name "\""))
         " · Zone pattern")))

(defn guard-text
  "Why the grid is greyed out, or nil: its keyframes are on (08 invariant
   9), or the show lock is."
  [{:keys [keyframes? locked?]}]
  (cond
    keyframes? "This pattern's keyframes are on. Switch them off to edit its grid."
    locked? "The show lock is on. Unlock it on the Perform tab to edit this grid."
    :else nil))

(defn- note [id text & classes]
  {:fx/type :label
   :id id
   :text text
   :wrap-text true
   :style-class (into ["label" "zone-pattern-fold"] classes)})

(defn- content
  [{:keys [fx/context]}]
  (let [{:keys [target]} (fx/sub-ctx context subs/dialog-data dialog-id)
        grid (when target (fx/sub-ctx context subs/step-grid target))
        guard (when grid (guard-text grid))
        close {:event/type :ui/close-dialog :dialog-id dialog-id}]
    {:fx/type :v-box
     :spacing 0
     :style-class "dialog-content"
     :pref-width 940
     :pref-height 600
     :children
     [(if grid
        {:fx/type :scroll-pane
         :v-box/vgrow :always
         :fit-to-width true
         :style-class "scroll-pane-base"
         :content
         {:fx/type :v-box
          :spacing 12
          :padding 14
          :children
          (vec
           (concat
            [{:fx/type :v-box
              :id "step-grid-edit-area"
              :spacing 12
              :disable (some? guard)
              :children [{:fx/type step-grid/toolbar :target target :grid grid}
                         {:fx/type step-grid/grid-view :target target :grid grid}
                         {:fx/type step-grid/moves-and-pads :target target :grid grid}]}]
            (when guard
              [(note "step-grid-guard" guard "zone-pattern-warning" "zone-pattern-warning-warn")])
            (when-let [text (:every-laser-note grid)]
              [(note "step-grid-every-laser-note" text)])
            (when-let [text (:wrap-note grid)]
              [(note "step-grid-wrap-note" text)])
            [(assoc (note "step-grid-fold" (fx/sub-ctx context subs/pattern-fold (:params grid)))
                    :graphic {:fx/type :label :text "ⓘ" :style-class ["label" "zone-pattern-fold-icon"]})]))}}
        {:fx/type :v-box
         :v-box/vgrow :always
         :alignment :center
         :style-class "dialog-placeholder"
         :children [{:fx/type :label
                     :text "There's no zone pattern here to draw on a grid."
                     :style-class "dialog-placeholder-text"}]})
      {:fx/type :h-box
       :alignment :center-left
       :spacing 12
       :style-class "dialog-footer"
       :children [{:fx/type :region :h-box/hgrow :always}
                  {:fx/type :button
                   :id "step-grid-close"
                   :text "Close"
                   :min-width :use-pref-size
                   :style-class "button-primary"
                   :on-action close}]}]}))

(defn- handle-scene-key-pressed
  "Undo and redo, as in the other editors: ⌘Z (Ctrl+Z, and Ctrl on macOS
   too), ⇧⌘Z or ⌘Y."
  [^KeyEvent e]
  (let [code (.getCode e)
        shortcut? (or (.isShortcutDown e) (.isControlDown e))
        shift? (.isShiftDown e)]
    (when shortcut?
      (cond
        (and (= code KeyCode/Z) shift?)
        (do (.consume e) (events/dispatch! {:event/type :edit/redo}))
        (= code KeyCode/Y)
        (do (.consume e) (events/dispatch! {:event/type :edit/redo}))
        (= code KeyCode/Z)
        (do (.consume e) (events/dispatch! {:event/type :edit/undo}))))))

(defn step-grid-editor-dialog
  "The step grid window."
  [{:keys [fx/context]}]
  (let [open? (fx/sub-ctx context subs/dialog-open? dialog-id)
        {:keys [target]} (fx/sub-ctx context subs/dialog-data dialog-id)
        [col row] (:entity-key target)
        chain-name (if (= :effect-chains (:domain target))
                     (:name (fx/sub-ctx context subs/effect-cell-chain col row))
                     (:name (fx/sub-ctx context subs/cue-cell-chain col row)))]
    {:fx/type :stage
     :showing open?
     :title (window-title target chain-name)
     :modality :none
     :min-width 720
     :min-height 460
     :on-close-request {:event/type :ui/close-dialog :dialog-id dialog-id}
     :scene {:fx/type :scene
             :stylesheets (css/dialog-stylesheet-urls)
             :on-key-pressed handle-scene-key-pressed
             :root {:fx/type content}}}))
