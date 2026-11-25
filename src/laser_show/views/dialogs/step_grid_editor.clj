(ns laser-show.views.dialogs.step-grid-editor
  "The step grid window (zones plan Z6, plans/2026-11-19-zones/08 §7):
   \"Step Grid — Cue A1 \\\"Beams\\\" · Zone pattern\", or \"Step Grid —
   Effects B3 \\\"Run\\\" · Zone pattern\". Its own non-modal window
   (Z6-Q5 A), opened from a cue's Pattern chip (Step grid…), its pattern
   window and an effects cell's editor (:step-grid/open). A row per zone
   and a column per step: click or drag to paint, the keys move the cursor
   and toggle, and ⌘Z / ⇧⌘Z / ⌘Y (Ctrl too) undo and redo as the chain
   editors do. While the pattern's keyframes are on, or the show lock is,
   the grid is greyed out and says why. It shows the pattern it opened on
   wherever that is in its cell now (subs/step-grid), and says so once the
   pattern is gone. The playhead and the Now line
   (\"Step 5 of 8\", \"Not playing\") come from the show clock's newest
   tick, never from app state."
  (:require [cljfx.api :as fx]
            [laser-show.events.core :as events]
            [laser-show.subs :as subs]
            [laser-show.timeline.shows :as shows]
            [laser-show.views.components.step-grid :as step-grid]
            [laser-show.views.dialogs.common :as common])
  (:import [javafx.stage WindowEvent]))

(def ^:private dialog-id :step-grid-editor)

(defn window-title
  "\"Step Grid — Cue A1 \\\"Beams\\\" · Zone pattern\": where the pattern
   is, and its cue's or cell's name when it has one. A show's pattern (its
   entity-key [:show id]) gives \"Step Grid — Show \\\"Intro\\\" · Zone
   pattern\", its name Untitled when it has none."
  [{:keys [domain entity-key]} chain-name]
  (if (shows/play-key? entity-key)
    (str "Step Grid — Show \"" (if (seq chain-name) chain-name "Untitled") "\" · Zone pattern")
    (let [[col row] entity-key]
      (str "Step Grid — " (if (= :effect-chains domain) "Effects " "Cue ") (common/cell-label col row)
           (when (seq chain-name) (str " \"" chain-name "\""))
           " · Zone pattern"))))

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
  (let [{:keys [target editor-id]} (fx/sub-ctx context subs/dialog-data dialog-id)
        grid (when target (fx/sub-ctx context subs/step-grid target))
        guard (when grid (guard-text grid))
        close {:event/type :ui/close-dialog :dialog-id dialog-id :editor-id editor-id}]
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
                         {:fx/type step-grid/grid-view :editor-id editor-id :target target :grid grid}
                         {:fx/type step-grid/moves-and-pads :target target :grid grid}]}]
            (when guard
              [(note "step-grid-guard" guard "zone-pattern-warning" "zone-pattern-warning-warn")])
            (when-let [text (:every-laser-note grid)]
              [(note "step-grid-every-laser-note" text)])
            (when-let [text (:wrap-note grid)]
              [(note "step-grid-wrap-note" text)])
            [(assoc (note "step-grid-fold" (fx/sub-ctx context subs/pattern-fold (get-in grid [:params :zones])))
                    :graphic {:fx/type :label :text "ⓘ" :style-class ["label" "zone-pattern-fold-icon"]})]))}}
        {:fx/type :v-box
         :v-box/vgrow :always
         :alignment :center
         :style-class "dialog-placeholder"
         :children [{:fx/type :label
                     :id "step-grid-missing"
                     :text "This pattern is no longer in this cell."
                     :style-class "dialog-placeholder-text"}]})
      {:fx/type :h-box
       :alignment :center-left
       :spacing 12
       :style-class "dialog-footer"
       :children (cond-> []
                   grid (conj {:fx/type step-grid/now-line :grid grid})
                   :always (into [{:fx/type :region :h-box/hgrow :always}
                                  {:fx/type :button
                                   :id "step-grid-close"
                                   :text "Close"
                                   :min-width :use-pref-size
                                   :style-class "button-primary"
                                   :on-action close}]))}]}))

(defn- close-request
  "The window's own close (its title bar) in session editor-id: consumed at
   once, so the window stays until the close's state hides it, with the
   paint its press holds sealed in the same state (handlers.step-grid/
   close-editor). An event map would be dispatched too late to cancel it."
  [editor-id]
  (fn [^WindowEvent e]
    (.consume e)
    (events/dispatch! {:event/type :ui/close-dialog :dialog-id dialog-id :editor-id editor-id})))

(defn step-grid-editor-dialog
  "The step grid window."
  [{:keys [fx/context]}]
  (let [{:keys [target editor-id]} (fx/sub-ctx context subs/dialog-data dialog-id)
        entity-key (:entity-key target)
        [col row] entity-key
        chain-name (cond
                     (= :effect-chains (:domain target)) (:name (fx/sub-ctx context subs/effect-cell-chain col row))
                     (shows/play-key? entity-key) (fx/sub-val context get-in (conj (shows/cue-path entity-key) :name))
                     :else (:name (fx/sub-ctx context subs/cue-cell-chain col row)))]
    {:fx/type common/dialog-stage
     :dialog-id dialog-id
     :title (window-title target chain-name)
     :min-width 720
     :min-height 460
     :on-close-request (close-request editor-id)
     :content {:fx/type content}}))
