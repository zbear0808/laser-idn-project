(ns laser-show.views.components.visual-editors.timeline.inspector
  "Timeline inspector (right side, 280 px): properties of the selected clip,
   clips, track or folder, or of the timeline when nothing is selected.
   See plans/2026-11-08-timeline-rework/05-visual-design.md §3.8."
  (:require [clj-font-awesome.core :as fa]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.presets :as presets]
            [laser-show.common.util :as u]
            [laser-show.events.core :as events]
            [laser-show.routing.zone-effects :as ze]
            [laser-show.timeline.model :as model]
            [laser-show.views.components.effect-bank :as effect-bank])
  (:import [javafx.scene.control TextField]))


(defn- apply-op! [col row op]
  (events/dispatch! {:event/type :timeline/apply-op :col col :row row :op op}))

(defn- title [text] {:fx/type :label :id "timeline-inspector-title" :text text :style-class "tl-inspector-title"})
(defn- field-label [text] {:fx/type :label :text text :style-class "tl-field-label"})

(defn- zone-name [zone-groups zone-id]
  (or (get-in zone-groups [zone-id :name]) (some-> zone-id name) "All"))

(defn- zone-combo
  "Zone combo: an inherit option labelled inherit-label, then every zone."
  [{:keys [zone-groups current inherit-label on-change]}]
  (let [options (into [{:id :inherit :name inherit-label}]
                      (map (fn [[id zg]] {:id id :name (:name zg (name id))}))
                      (sort-by (comp :name val) zone-groups))]
    {:fx/type :combo-box
     :items options
     :value (or (u/seek #(= (:id %) (or current :inherit)) options) (first options))
     :max-width Double/MAX_VALUE
     :button-cell (fn [opt] {:text (:name opt)})
     :cell-factory {:fx/cell-type :list-cell :describe (fn [opt] {:text (:name opt)})}
     :on-value-changed (fn [opt]
                         (when opt
                           (let [zone (when-not (= :inherit (:id opt)) (:id opt))]
                             (when (not= zone current) (on-change zone)))))}))

(defn- beats-field
  "Text field in beats; Enter commits (on-commit beats)."
  [{:keys [id value on-commit]}]
  (let [commit! (fn [^TextField tf]
                  (when-let [v (parse-double (.getText tf))]
                    (when (not= v (double value)) (on-commit v))))]
    {:fx/type :text-field
     :id id
     :text (format "%.2f" (double (or value 0.0)))
     :pref-width 80
     :on-action (fn [e] (commit! (.getSource e)))}))

(defn- leaf-tracks [tracks]
  (filterv model/leaf-track? (tree-seq model/track-group? #(:items % []) {:type :group :items tracks})))

(defn- clip-section
  [{:keys [col row tracks zone-groups add-panel-effect-tab]} clip]
  (let [track (model/find-track tracks (:track-id clip))
        track-zone (ze/resolve-track-effective-zone tracks (:track-id clip) :all)
        leaves (leaf-tracks tracks)]
    [(title "CLIP")
     {:fx/type :label
      :text (or (:name clip) (:name (presets/presets-by-id (:preset-id clip))) "Clip")
      :style-class "tl-inspector-name"}
     {:fx/type :grid-pane
      :hgap 8 :vgap 6
      :children [(assoc (field-label "Start") :grid-pane/row 0 :grid-pane/column 0)
                 (assoc (beats-field {:id "timeline-clip-start"
                                      :value (model/clip-start clip)
                                      :on-commit (fn [v]
                                                   (apply-op! col row {:op :move-clips :clip-ids #{(:id clip)}
                                                                       :delta (- v (model/clip-start clip))
                                                                       :snap? false}))})
                        :grid-pane/row 0 :grid-pane/column 1)
                 (assoc (field-label "Length") :grid-pane/row 1 :grid-pane/column 0)
                 (assoc (beats-field {:id "timeline-clip-length"
                                      :value (model/clip-length clip)
                                      :on-commit (fn [v]
                                                   (apply-op! col row {:op :trim-end :clip-id (:id clip)
                                                                       :to (+ (model/clip-start clip) v)
                                                                       :snap? false}))})
                        :grid-pane/row 1 :grid-pane/column 1)]}
     (field-label "Track")
     {:fx/type :combo-box
      :items leaves
      :value (or (u/seek #(= (:id %) (:id track)) leaves) track)
      :max-width Double/MAX_VALUE
      :button-cell (fn [t] {:text (:name t "Track")})
      :cell-factory {:fx/cell-type :list-cell :describe (fn [t] {:text (:name t "Track")})}
      :on-value-changed (fn [t]
                          (when (and t (not= (:id t) (:track-id clip)))
                            (apply-op! col row {:op :set-clip-track :clip-id (:id clip) :track-id (:id t)})))}
     (field-label "Zone")
     (zone-combo {:zone-groups zone-groups
                  :current (:zone-group-id clip)
                  :inherit-label (str "Inherit from Track (" (zone-name zone-groups track-zone) ")")
                  :on-change #(apply-op! col row {:op :set-clip-zone :id (:id clip) :zone-group-id %})})
     (title "EFFECTS")
     {:fx/type :v-box
      :spacing 2
      :children (if (seq (:effects clip))
                  (mapv (fn [fx]
                          {:fx/type :h-box
                           :alignment :center-left
                           :children [{:fx/type :label
                                       :text (or (:name (effects/get-effect (:effect-id fx)))
                                                 (some-> (:effect-id fx) name))
                                       :style-class "tl-field-value"
                                       :max-width Double/MAX_VALUE
                                       :h-box/hgrow :always}
                                      {:fx/type :button
                                       :style-class ["button" "tl-icon-button"]
                                       :graphic {:fx/type fa/icon :name :xmark :size 10}
                                       :on-action (fn [_]
                                                    (events/dispatch!
                                                     {:event/type :timeline/apply-op :col col :row row
                                                      :op {:op :remove-effect :clip-id (:id clip) :effect-instance-id (:id fx)}}))}]})
                        (:effects clip))
                  [{:fx/type :label :text "No effects" :style-class "tl-field-label"}])}
     {:fx/type effect-bank/effect-bank
      :active-tab (or add-panel-effect-tab :shape)
      :on-tab-change {:event/type :timeline/set-add-panel-tab :panel :effect}
      :item-event-template {:event/type :timeline/add-effect-to-item
                            :col col :row row
                            :target-item-id (:id clip)}
      :include-zone? true}]))

(defn- clips-section [{:keys [col row]} clip-ids]
  [(title "CLIPS")
   {:fx/type :label :text (str (count clip-ids) " clips selected") :style-class "tl-inspector-name"}
   {:fx/type :button
    :text "Delete"
    :style-class "button-secondary"
    :on-action {:event/type :timeline/delete-selection :col col :row row}}])

(defn- track-section [{:keys [col row tracks zone-groups destination-zone-id]} track]
  (let [folder? (model/track-group? track)
        parent (model/parent-track tracks (:id track))
        default-zone (or destination-zone-id :all)
        parent-zone (when parent (ze/resolve-track-effective-zone tracks (:id parent) default-zone))]
    (cond-> [(title (if folder? "FOLDER PROPERTIES" "TRACK PROPERTIES"))
             {:fx/type :label :text (:name track (if folder? "Folder" "Track")) :style-class "tl-inspector-name"}
             (field-label "Zone")
             (zone-combo {:zone-groups zone-groups
                          :current (:zone-group-id track)
                          :inherit-label (if parent
                                           (str "Inherit from Folder (" (zone-name zone-groups parent-zone) ")")
                                           (str "Cue Default Zone (" (zone-name zone-groups default-zone) ")"))
                          :on-change #(apply-op! col row {:op :set-track-zone :id (:id track) :zone-group-id %})})
             {:fx/type :check-box
              :text "Muted"
              :selected (true? (:muted? track))
              :on-selected-changed (fn [m] (apply-op! col row {:op :set-track-muted :track-id (:id track) :muted? m}))}]
      (not folder?)
      (into [(field-label "Colour")
             {:fx/type :flow-pane
              :hgap 6 :vgap 6
              :children (mapv (fn [hex]
                                {:fx/type :button
                                 :style-class (cond-> ["tl-swatch"] (= hex (:color track)) (conj "tl-swatch-selected"))
                                 :style (str "-fx-background-color: " hex ";")
                                 :on-action (fn [_] (apply-op! col row {:op :set-track-color :track-id (:id track) :color hex}))})
                              model/track-palette)}]))))

(defn- timeline-section [{:keys [col row cue]}]
  (let [{:keys [enabled? start duration] :or {start 0.0 duration 4.0}} (:loop cue)
        clips (:items cue)
        end (reduce max 0.0 (keep model/clip-end clips))]
    [(title "TIMELINE")
     {:fx/type :label :text (str (count clips) " clips · ends at beat " (format "%.2f" end)) :style-class "tl-field-value"}
     {:fx/type :check-box
      :text "Loop"
      :selected (boolean enabled?)
      :on-selected-changed (fn [on] (apply-op! col row {:op :set-loop :enabled? on}))}
     {:fx/type :grid-pane
      :hgap 8 :vgap 6
      :children [(assoc (field-label "Loop start") :grid-pane/row 0 :grid-pane/column 0)
                 (assoc (beats-field {:id "timeline-loop-start" :value start
                                      :on-commit #(apply-op! col row {:op :set-loop :start (max 0.0 %)})})
                        :grid-pane/row 0 :grid-pane/column 1)
                 (assoc (field-label "Loop length") :grid-pane/row 1 :grid-pane/column 0)
                 (assoc (beats-field {:id "timeline-loop-length" :value duration
                                      :on-commit #(apply-op! col row {:op :set-loop :duration (max model/min-clip-length %)})})
                        :grid-pane/row 1 :grid-pane/column 1)]}
     {:fx/type :label
      :text "Click the ruler to move the cursor; presets are added at the cursor on the selected track."
      :wrap-text true
      :style-class "tl-field-label"}]))

(defn inspector
  "Props: :col :row :cue :tracks :zone-groups :destination-zone-id :selection
   :selected-track-id :add-panel-effect-tab"
  [{:keys [cue tracks selection selected-track-id] :as props}]
  (let [items (:items cue)
        clip (when (= 1 (count selection))
               (when-let [path (chains/find-path-by-id items (first selection))]
                 (get-in (vec items) path)))
        track (when (and (empty? selection) selected-track-id)
                (model/find-track tracks selected-track-id))]
    {:fx/type :scroll-pane
     :fit-to-width true
     :hbar-policy :never
     :style "-fx-background: #161616; -fx-background-color: #161616;"
     :min-width 280 :pref-width 280 :max-width 280
     :content {:fx/type :v-box
               :style-class "tl-inspector"
               :children (cond
                           clip (clip-section props clip)
                           (> (count selection) 1) (clips-section props selection)
                           track (track-section props track)
                           :else (timeline-section props))}}))
