(ns laser-show.views.components.visual-editors.timeline.browser
  "The timeline editor's browser (right side): presets and effects.
   
   Click a preset: a clip at the cursor on the insert track. Click an effect:
   it goes on the selected clip, track or folder.
   Drag a preset into a lane: a clip at the drop beat on that row (a folder
   row: its first track), or onto a track header: at the cursor there. Drag
   an effect onto a clip, a lane, a track or folder header, or a position in
   the device chain. Zone effects only go on clips. The drop targets and
   their rules are in timeline.drop."
  (:require [laser-show.views.components.effect-bank :as effect-bank]
            [laser-show.views.components.preset-bank :as preset-bank]
            [laser-show.views.components.visual-editors.timeline.inspector :as inspector]))

(defn- section-header [title id hint]
  {:fx/type :v-box
   :spacing 2
   :padding {:top 8 :left 10 :right 10 :bottom 4}
   :children [{:fx/type :label :text title :style-class "tl-section-label"}
              {:fx/type :label :id id :style-class "tl-hint" :wrap-text true :text hint}]})

(defn browser
  "Props: :col :row :preset-tab :effect-tab :cursor-label :insert-track-id
   :insert-track-name :target (inspector/selection-target)."
  [{:keys [col row preset-tab effect-tab cursor-label insert-track-id insert-track-name target]}]
  (let [{:keys [kind entity]} target
        effect-target? (#{:clip :track} kind)]
    {:fx/type :v-box
     :id "timeline-browser"
     :style-class "tl-browser"
     :children
     [(section-header "PRESETS" "timeline-drawer-hint"
                      (if insert-track-id
                        (str "Click: adds at " cursor-label " on " insert-track-name " · or drag into a lane")
                        "Add a track first"))
      {:fx/type preset-bank/preset-bank
       :v-box/vgrow :always
       :fill? true
       :cell [col row]
       :drag-kind :preset
       :active-tab (or preset-tab :geometric)
       :on-tab-change {:event/type :timeline/set-add-panel-tab :panel :preset}
       :item-event-template {:event/type :timeline/add-preset-to-track
                             :col col :row row
                             :track-id insert-track-id}}
      (section-header "EFFECTS" "timeline-browser-effect-hint"
                      (if effect-target?
                        (str "Adds to " (inspector/target-name target) " · or drag onto a clip or track")
                        "Drag onto a clip, track or folder, or select one first"))
      ;; Without a target a click does nothing, but the effects can still be
      ;; dragged: dimmed, not disabled
      {:fx/type :v-box
       :id "timeline-browser-effects"
       :v-box/vgrow :always
       :style-class (if effect-target? [] ["tl-browser-bank-idle"])
       :children
       [{:fx/type effect-bank/effect-bank
         :v-box/vgrow :always
         :fill? true
         :drag-kind :effect
         :scope :item
         ;; Where content goes is its Target, not an effect (zones plan Z4)
         :active-tab (if (= :zone effect-tab) :shape (or effect-tab :shape))
         :on-tab-change {:event/type :timeline/set-add-panel-tab :panel :effect}
         :item-event-template (cond-> {:event/type :timeline/add-effect-to-item :col col :row row}
                                (= :clip kind) (assoc :target-item-id (:id entity))
                                (= :track kind) (assoc :target-track-id (:id entity)))}]}]}))
