(ns laser-show.views.components.visual-editors.timeline.header
  "Track header column of the timeline editor (220 px).
   
   Rows come from laser-show.timeline.layout/rows, the same rows the lane
   canvas draws, so every header is exactly as tall as its lane. The rows sit
   in a clipped pane translated by the viewport's vertical scroll.
   
   Each row: colour strip, indent, folder chevron, name (double-click to
   rename), zone chip (click for the zone menu), mute toggle. Right-click for
   rename / colour / zone / add track inside / delete. Rows drag to reorder
   through the shared list drag-and-drop (:list/perform-drop ->
   :timeline/update-tracks)."
  (:require [cljfx.api :as fx]
            [clj-font-awesome.core :as fa]
            [laser-show.events.core :as events]
            [laser-show.timeline.layout :as layout]
            [laser-show.timeline.model :as model]
            [laser-show.views.components.list-dnd :as dnd])
  (:import [javafx.scene.input KeyCode KeyEvent MouseButton MouseEvent]
           [javafx.scene.layout Region]
           [javafx.scene.shape Rectangle]))


(defn- apply-op! [col row op]
  (events/dispatch! {:event/type :timeline/apply-op :col col :row row :op op}))

(defn- zone-name [zone-groups zone-id]
  (or (get-in zone-groups [zone-id :name])
      (some-> zone-id name)
      "All"))

(defn- zone-menu-items
  "Inherit + every zone group, each dispatching :set-track-zone."
  [{:keys [col row zone-groups destination-zone-id tracks rows-by-id]} r]
  (let [track (:track r)
        parent (model/parent-track tracks (:id r))
        default-zone (or destination-zone-id :all)
        inherited-name (if parent
                         (str "Inherit from Folder ("
                              (zone-name zone-groups (or (:zone-id (get rows-by-id (:id parent))) default-zone)) ")")
                         (str "Cue Default Zone (" (zone-name zone-groups default-zone) ")"))
        current (:zone-group-id track)]
    (into [{:fx/type :menu-item
            :text (str (if (nil? current) "✓ " "   ") inherited-name)
            :on-action (fn [_] (apply-op! col row {:op :set-track-zone :id (:id r) :zone-group-id nil}))}
           {:fx/type :separator-menu-item}]
          (map (fn [[zid zg]]
                 {:fx/type :menu-item
                  :text (str (if (= zid current) "✓ " "   ") (:name zg (name zid)))
                  :on-action (fn [_] (apply-op! col row {:op :set-track-zone :id (:id r) :zone-group-id zid}))}))
          (sort-by (comp :name val) zone-groups))))

(defn- context-menu [{:keys [col row swap-state] :as props} r]
  (let [folder? (= :folder (:kind r))
        muted? (true? (get-in r [:track :muted?]))]
    {:fx/type :context-menu
     :items (cond-> [{:fx/type :menu-item
                      :text "Rename"
                      :on-action (fn [_] (swap-state assoc :renaming-id (:id r)))}
                     {:fx/type :menu-item
                      :text (if muted? "Unmute" "Mute")
                      :on-action (fn [_] (apply-op! col row {:op :set-track-muted :track-id (:id r) :muted? (not muted?)}))}
                     {:fx/type :menu
                      :text "Zone"
                      :items (zone-menu-items props r)}]
              (not folder?)
              (conj {:fx/type :menu
                     :text "Colour"
                     :items (mapv (fn [hex]
                                    {:fx/type :menu-item
                                     :text hex
                                     :graphic {:fx/type :region
                                               :min-width 12 :min-height 12
                                               :style (str "-fx-background-color: " hex "; -fx-background-radius: 6;")}
                                     :on-action (fn [_] (apply-op! col row {:op :set-track-color :track-id (:id r) :color hex}))})
                                  model/track-palette)})
              folder?
              (conj {:fx/type :menu-item
                     :text "Add Track Inside"
                     :on-action (fn [_] (apply-op! col row {:op :add-track :parent-id (:id r)}))})
              true
              (conj {:fx/type :separator-menu-item}
                    {:fx/type :menu-item
                     :text (if folder? "Delete Folder" "Delete Track")
                     :on-action (fn [_] (apply-op! col row {:op :delete-tracks :track-ids #{(:id r)}}))}))}))

(defn- name-node [{:keys [col row renaming-id swap-state]} r]
  (let [track (:track r)
        folder? (= :folder (:kind r))]
    (if (= renaming-id (:id r))
      {:fx/type :text-field
       :id "timeline-track-rename"
       :text (:name track "")
       :style-class ["text-field" "tl-header-name-input"]
       :h-box/hgrow :always
       :on-action (fn [e]
                    (let [text (.getText ^javafx.scene.control.TextField (.getSource e))]
                      (when (seq text)
                        (apply-op! col row {:op :rename-track :track-id (:id r) :name text}))
                      (swap-state assoc :renaming-id nil)))
       :on-key-pressed (fn [^KeyEvent e]
                         (when (= KeyCode/ESCAPE (.getCode e))
                           (.consume e)
                           (swap-state assoc :renaming-id nil)))}
      {:fx/type :label
       :text (cond-> (:name track (if folder? "Folder" "Track"))
               folder? (.toUpperCase))
       :style-class (cond-> ["tl-header-name"]
                      folder? (conj "tl-header-name-folder")
                      (:muted? r) (conj "tl-header-name-muted"))
       :max-width Double/MAX_VALUE
       :h-box/hgrow :always
       :min-width 0})))

(defn- zone-chip [{:keys [zone-groups destination-zone-id] :as props} r]
  (let [inherited? (:zone-inherited? r)
        zname (zone-name zone-groups (or (:zone-id r) destination-zone-id :all))]
    {:fx/type :menu-button
     :text (if inherited? (str "(" zname ")") zname)
     :style-class (cond-> ["menu-button" "tl-zone-chip"] inherited? (conj "tl-zone-chip-inherited"))
     :focus-traversable false
     :items (zone-menu-items props r)}))

(defn- header-row
  [{:keys [col row selected-track-id items-path] :as props} idx r]
  (let [folder? (= :folder (:kind r))
        selected? (= selected-track-id (:id r))
        track-id (:id r)
        drop-props {:on-change-event :timeline/update-tracks
                    :on-change-params {:col col :row row}
                    :items-path items-path}
        component-id [:timeline-tracks col row]]
    {:fx/type fx/ext-on-instance-lifecycle
     :fx/key track-id
     :on-created (fn [node]
                   (dnd/setup-drag-source! node track-id component-id items-path)
                   (dnd/setup-drag-target! node track-id folder? component-id drop-props))
     ;; A graphic-only Label hosts the row: only Controls take :context-menu
     :desc
     {:fx/type :label
      :id (str "timeline-track-header-" idx)
      :content-display :graphic-only
      :padding 0
      :min-height (:h r) :pref-height (:h r) :max-height (:h r)
      :min-width (:header-w layout/metrics) :pref-width (:header-w layout/metrics)
      :max-width (:header-w layout/metrics)
      :context-menu (context-menu props r)
      :graphic
      {:fx/type :h-box
      :style-class (cond-> ["tl-header-row"]
                     folder? (conj "tl-header-row-folder")
                     selected? (conj "tl-header-row-selected"))
      :min-height (:h r) :pref-height (:h r) :max-height (:h r)
      :min-width (:header-w layout/metrics) :pref-width (:header-w layout/metrics)
      :on-mouse-clicked (fn [^MouseEvent e]
                          (when (= MouseButton/PRIMARY (.getButton e))
                            (if (= 2 (.getClickCount e))
                              ((:swap-state props) assoc :renaming-id track-id)
                              (events/dispatch! {:event/type :timeline/select-track
                                                 :col col :row row :track-id track-id}))))
      :children
      (filterv some?
               [{:fx/type :region
                 :min-width 4 :pref-width 4 :max-width 4
                 :max-height Double/MAX_VALUE
                 :style (str "-fx-background-color: " (or (:color r) "#555555") ";")}
                {:fx/type :region
                 :min-width (* (:indent-px layout/metrics) (:depth r))
                 :pref-width (* (:indent-px layout/metrics) (:depth r))}
                (if folder?
                  {:fx/type :button
                   :style-class ["button" "tl-chevron"]
                   :focus-traversable false
                   :graphic {:fx/type fa/icon
                             :name (if (get-in r [:track :collapsed?]) :chevron-right :chevron-down)
                             :size 10}
                   :on-action (fn [_]
                                (apply-op! col row {:op :set-collapsed :track-id track-id
                                                    :collapsed? (not (get-in r [:track :collapsed?]))}))}
                  {:fx/type :region :min-width 6 :pref-width 6})
                (name-node props r)
                (zone-chip props r)
                {:fx/type :toggle-button
                 :text "M"
                 :id (str "timeline-track-mute-" idx)
                 :style-class ["toggle-button" "tl-mute"]
                 :focus-traversable false
                 :selected (true? (get-in r [:track :muted?]))
                 :on-action (fn [_]
                              (apply-op! col row {:op :set-track-muted :track-id track-id
                                                  :muted? (not (true? (get-in r [:track :muted?])))}))}])}}}))

(defn header-column
  "Props: :rows :view :col :row :tracks :selected-track-id :zone-groups
   :destination-zone-id :renaming-id :swap-state :on-scroll"
  [{:keys [rows view col row on-scroll] :as props}]
  (let [items-path [:chains :cue-chains [col row] :tracks]
        props (assoc props :items-path items-path
                     :rows-by-id (into {} (map (juxt :id identity)) rows))]
    {:fx/type :v-box
     :style-class "tl-header-column"
     :children
     [{:fx/type :h-box
       :style-class "tl-header-corner"
       :children [{:fx/type :label :text "TRACKS" :style-class "tl-section-label"}
                  {:fx/type :region :h-box/hgrow :always}
                  {:fx/type :button
                   :id "timeline-header-add-track"
                   :style-class ["button" "tl-icon-button"]
                   :focus-traversable false
                   :tooltip {:fx/type :tooltip :text "Add track"}
                   :graphic {:fx/type fa/icon :name :plus :size 11}
                   :on-action {:event/type :timeline/add-track :col col :row row}}]}
      {:fx/type fx/ext-on-instance-lifecycle
       :v-box/vgrow :always
       :on-created (fn [^Region pane]
                     (let [clip (Rectangle.)]
                       (.bind (.widthProperty clip) (.widthProperty pane))
                       (.bind (.heightProperty clip) (.heightProperty pane))
                       (.setClip pane clip))
                     (dnd/setup-container-drop-target!
                      pane [:timeline-tracks col row]
                      {:on-change-event :timeline/update-tracks
                       :on-change-params {:col col :row row}
                       :items-path items-path}))
       :desc {:fx/type :pane
              :min-height 0
              :pref-height 0
              :on-scroll on-scroll
              :children [{:fx/type :v-box
                          :spacing (:row-gap layout/metrics)
                          :translate-y (- (double (:scroll-y view 0.0)))
                          :pref-width (:header-w layout/metrics)
                          :children (into [] (map-indexed (fn [i r] (header-row props i r))) rows)}]}}]}))
