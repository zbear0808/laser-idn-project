(ns laser-show.views.components.visual-editors.timeline.header
  "Track header column of the timeline editor (220 px).
   
   Rows come from laser-show.timeline.layout/rows, the same rows the lane
   canvas draws, so every header is exactly as tall as its lane. The rows sit
   in a clipped pane translated by the viewport's vertical scroll.
   
   Each row: colour strip, indent, folder chevron, name (double-click to
   rename), Target chip (click for the Target menu, zones plan Z4), mute
   toggle. Right-click for rename / mute / Target / colour / add track
   inside / delete. Rows drag to reorder
   through the shared list drag-and-drop (:list/perform-drop ->
   :timeline/update-tracks). Presets and effects dragged from the browser
   drop on a row too (bank-drop-target!, rules in timeline.drop)."
  (:require [cljfx.api :as fx]
            [clj-font-awesome.core :as fa]
            [laser-show.events.core :as events]
            [laser-show.timeline.layout :as layout]
            [laser-show.timeline.model :as model]
            [laser-show.views.components.drag-drop-cell :as dd]
            [laser-show.views.components.list-dnd :as dnd]
            [laser-show.views.components.target-control :as target-control]
            [laser-show.views.components.visual-editors.timeline.drop :as drop])
  (:import [javafx.css PseudoClass]
           [javafx.event EventHandler EventType]
           [javafx.scene Node]
           [javafx.scene.input DragEvent KeyCode KeyEvent MouseButton MouseEvent TransferMode]
           [javafx.scene.layout Region]
           [javafx.scene.shape Rectangle]))


(defn- apply-op! [col row op]
  (events/dispatch! {:event/type :timeline/apply-op :col col :row row :op op}))

(defn- track-on-set
  "The Target event for row r: a choice adds :target."
  [col row r]
  {:event/type :timeline/set-track-target :col col :row row :track-id (:id r)})

(defn- target-menu-items
  "The Target menu for row r (target-control), in the :track scope."
  [{:keys [col row explain menu-ctx]} r]
  (target-control/menu-items
   (target-control/menu-model (assoc menu-ctx
                                     :scope :track
                                     :own (get-in r [:track :target])
                                     :inherited (get-in explain [:track-inherits (:id r)])))
   (track-on-set col row r)))

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
                      :text "Target"
                      :items (target-menu-items props r)}]
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

(defn- target-chip
  "Row r's Target chip, compact: \"A\", \"(A)\" when the track inherits it."
  [{:keys [col row explain]} idx r]
  {:fx/type target-control/target-chip
   :id (str "timeline-track-target-" idx)
   :scope :track
   :own (get-in r [:track :target])
   :inherited (get-in explain [:track-inherits (:id r)])
   :on-set (track-on-set col row r)
   :compact? true
   :tooltip (if (= :folder (:kind r))
              "Where this folder's clips go, unless a track or a clip in it sets its own"
              "Where this track's clips go, unless a clip sets its own")})

(def ^:private drop-target-class (PseudoClass/getPseudoClass "drop-target"))

(defn- header-drop
  "The browser drop on this row, or nil. The row's cell and track are in its
   userData (set every render, so they're never stale)."
  [^Node node ^DragEvent e]
  (when-let [payload (dd/read-payload (.getDragboard e) :bank-item drop/valid-payload?)]
    (let [{:keys [col row track-id]} (.getUserData node)]
      (drop/drop-target payload {:type :header :track-id track-id} {:col col :row row}))))

(defn- track-list
  "The list drag-and-drop's command address for the tracks of the cell in
   node's userData (set every render): a row or the column the editor keeps
   when it shows another cell reorders that cell's tracks."
  [^Node node]
  (when-let [{:keys [col row]} (.getUserData node)]
    {:component-id [:timeline-tracks col row]
     :items-path [:chains :cue-chains [col row] :tracks]
     :on-change-event :timeline/update-tracks
     :on-change-params {:col col :row row}}))

(defn- bank-drop-target!
  "Make a header row a drop target for browser drags. Uses addEventHandler:
   the setOnDrag* slots belong to the list drag-and-drop (track reorder),
   and every handler on a node runs. Only :bank-item drags are touched."
  [^Node node]
  (let [on! (fn [^EventType type f]
              (.addEventHandler node type (reify EventHandler (handle [_ e] (f e)))))
        mark! #(.pseudoClassStateChanged node drop-target-class (boolean %))
        bank? #(dd/has-payload? (.getDragboard ^DragEvent %) :bank-item)]
    (on! DragEvent/DRAG_OVER
         (fn [^DragEvent e]
           (when (bank? e)
             (let [t (header-drop node e)]
               (when t (.acceptTransferModes e (into-array TransferMode [TransferMode/COPY])))
               (mark! t)
               (.consume e)))))
    (on! DragEvent/DRAG_EXITED (fn [_] (mark! false)))
    (on! DragEvent/DRAG_DROPPED
         (fn [^DragEvent e]
           (when (bank? e)
             (let [t (header-drop node e)]
               (mark! false)
               (when t (events/dispatch! (:event t)))
               (.setDropCompleted e (some? t))
               (.consume e)))))))

(defn- header-row
  [{:keys [col row selected-track-id] :as props} idx r]
  (let [folder? (= :folder (:kind r))
        selected? (= selected-track-id (:id r))
        track-id (:id r)]
    {:fx/type fx/ext-on-instance-lifecycle
     :fx/key track-id
     :on-created (fn [node]
                   (let [address #(track-list node)]
                     (dnd/setup-drag-source! node track-id address)
                     (dnd/setup-drag-target! node track-id folder? address))
                   (bank-drop-target! node))
     ;; A graphic-only Label hosts the row: only Controls take :context-menu
     :desc
     {:fx/type :label
      :id (str "timeline-track-header-" idx)
      :user-data {:col col :row row :track-id track-id}
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
                   :tooltip {:fx/type :tooltip :text (if (get-in r [:track :collapsed?]) "Expand" "Collapse")}
                   :on-action (fn [_]
                                (apply-op! col row {:op :set-collapsed :track-id track-id
                                                    :collapsed? (not (get-in r [:track :collapsed?]))}))}
                  {:fx/type :region :min-width 6 :pref-width 6})
                (name-node props r)
                (target-chip props idx r)
                {:fx/type :toggle-button
                 :text "M"
                 :id (str "timeline-track-mute-" idx)
                 :style-class ["toggle-button" "tl-mute"]
                 :focus-traversable false
                 :selected (true? (get-in r [:track :muted?]))
                 :tooltip {:fx/type :tooltip :text "Mute"}
                 :on-action (fn [_]
                              (apply-op! col row {:op :set-track-muted :track-id track-id
                                                  :muted? (not (true? (get-in r [:track :muted?])))}))}])}}}))

(defn header-column
  "Props: :rows :view :col :row :tracks :selected-track-id :explain (how
   the cue's levels resolve: subs/cue-target-explain) :renaming-id
   :swap-state :on-scroll"
  [{:keys [fx/context rows view col row on-scroll] :as props}]
  (let [props (assoc props :menu-ctx (target-control/menu-context context))]
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
                     (dnd/setup-container-drop-target! pane #(track-list pane)))
       :desc {:fx/type :pane
              :min-height 0
              :pref-height 0
              :user-data {:col col :row row}
              :on-scroll on-scroll
              :children [{:fx/type :v-box
                          :spacing (:row-gap layout/metrics)
                          :translate-y (- (double (:scroll-y view 0.0)))
                          :pref-width (:header-w layout/metrics)
                          :children (into [] (map-indexed (fn [i r] (header-row props i r))) rows)}]}}]}))
