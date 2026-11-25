(ns laser-show.views.dialogs.zone-pattern-editor
  "The pattern window (zones plan Z5, 07 §7.1): \"Zone Pattern — Cue A1
   \\\"Beams\\\"\", or \"Zone Pattern — Show \\\"Intro\\\"\", opened from a cue's
   or a show's Pattern chip (Edit pattern…), with the dialog data {:cue-key
   k} (its playback key, [col row] or [:show id]). It is
   the shared effect parameter editor on the cue's pattern, the
   :cue-zone-patterns domain (the cue's :zone-patterns, a chain of one in
   v1), so its parameters take modulators and keyframes as any effect's do,
   and Visual | Numeric switches between the rows its style uses and the
   full list. Remove takes the pattern away."
  (:require [cljfx.api :as fx]
            [laser-show.events.core :as events]
            [laser-show.subs :as subs]
            [laser-show.timeline.shows :as shows]
            [laser-show.views.components.effect-parameter-editor :as effect-param-editor]
            [laser-show.views.dialogs.common :as common]))

(defn window-title
  "\"Zone Pattern — Cue A1 \\\"Beams\\\"\": the cell, and the cue's name when
   it has one. A show's key gives \"Zone Pattern — Show \\\"Intro\\\"\", its
   name Untitled when it has none."
  [cue-key cue-name]
  (if (shows/play-key? cue-key)
    (str "Zone Pattern — Show \"" (if (seq cue-name) cue-name "Untitled") "\"")
    (let [[col row] cue-key]
      (str "Zone Pattern — Cue " (common/cell-label col row)
           (when (seq cue-name) (str " \"" cue-name "\""))))))

(defn- content
  [{:keys [fx/context]}]
  (let [dialog-data (fx/sub-ctx context subs/dialog-data :zone-pattern-editor)
        cue-key (:cue-key dialog-data)
        pattern (fx/sub-ctx context subs/cue-zone-pattern cue-key)
        close {:event/type :ui/close-dialog :dialog-id :zone-pattern-editor}]
    {:fx/type :v-box
     :spacing 0
     :style-class "dialog-content"
     :pref-width 760
     :pref-height 620
     :children
     [(if pattern
        {:fx/type effect-param-editor/effect-parameter-editor
         :v-box/vgrow :always
         :domain :cue-zone-patterns
         :entity-key cue-key
         :effect-path [0]
         :effect pattern
         :effect-def (fx/sub-ctx context subs/effect-descriptor (:effect-id pattern))
         :dialog-data dialog-data}
        {:fx/type :v-box
         :v-box/vgrow :always
         :alignment :center
         :style-class "dialog-placeholder"
         :children [{:fx/type :label
                     :text "This cue has no zone pattern. Pick a style on its Pattern chip."
                     :style-class "dialog-placeholder-text"}]})
      {:fx/type :h-box
       :alignment :center-left
       :spacing 12
       :style-class "dialog-footer"
       :children [{:fx/type :region :h-box/hgrow :always}
                  {:fx/type :button
                   :id "zone-pattern-remove"
                   :text "Remove"
                   :min-width :use-pref-size
                   :disable (nil? pattern)
                   :style-class "button-secondary"
                   :tooltip {:fx/type :tooltip :text "Take the pattern off this cue: its pictures stay in the zones they're drawn into"}
                   :on-action (fn [_]
                                (events/dispatch! {:event/type :zone-patterns/set-cue-pattern :cue-key cue-key :style nil})
                                (events/dispatch! close))}
                  {:fx/type :button
                   :id "zone-pattern-close"
                   :text "Close"
                   :min-width :use-pref-size
                   :style-class "button-primary"
                   :on-action close}]}]}))

(defn zone-pattern-editor-dialog
  "The pattern window."
  [{:keys [fx/context]}]
  (let [cue-key (:cue-key (fx/sub-ctx context subs/dialog-data :zone-pattern-editor))
        cue-name (when (some? cue-key) (fx/sub-val context get-in (conj (shows/cue-path cue-key) :name)))]
    {:fx/type common/dialog-stage
     :dialog-id :zone-pattern-editor
     :title (window-title cue-key cue-name)
     :min-width 640
     :min-height 480
     :content {:fx/type content}}))
