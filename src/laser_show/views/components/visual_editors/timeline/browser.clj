(ns laser-show.views.components.visual-editors.timeline.browser
  "The timeline editor's browser (right side): presets, which add a clip at
   the cursor on the insert track, and effects, which add to the selected
   clip, track or folder."
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
                        (str "Adds at " cursor-label " on " insert-track-name)
                        "Add a track first"))
      {:fx/type preset-bank/preset-bank
       :v-box/vgrow :always
       :fill? true
       :cell [col row]
       :active-tab (or preset-tab :geometric)
       :on-tab-change {:event/type :timeline/set-add-panel-tab :panel :preset}
       :item-event-template {:event/type :timeline/add-preset-to-track
                             :col col :row row
                             :track-id insert-track-id}}
      (section-header "EFFECTS" "timeline-browser-effect-hint"
                      (if effect-target?
                        (str "Adds to " (inspector/target-name target))
                        "Select a clip, track or folder to add effects"))
      {:fx/type :v-box
       :v-box/vgrow :always
       :disable (not effect-target?)
       :children
       [{:fx/type effect-bank/effect-bank
         :v-box/vgrow :always
         :fill? true
         ;; Zone effects reroute a clip's content; tracks have a zone setting instead
         :include-zone? (= :clip kind)
         :active-tab (if (and (= :zone effect-tab) (not= :clip kind)) :shape (or effect-tab :shape))
         :on-tab-change {:event/type :timeline/set-add-panel-tab :panel :effect}
         :item-event-template (cond-> {:event/type :timeline/add-effect-to-item :col col :row row}
                                (= :clip kind) (assoc :target-item-id (:id entity))
                                (= :track kind) (assoc :target-track-id (:id entity)))}]}]}))
