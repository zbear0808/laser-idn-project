(ns laser-show.views.components.custom-param-renderers
  "Custom parameter renderers for effects with specialized UI needs.
   
   Provides visual editors for effects like:
   - Translate: 2D point dragging for X/Y position
   - Corner Pin: 4-corner quadrilateral manipulation
   - RGB Curves: Photoshop-style curve editor for color channel adjustment
   - Zone Reroute: Zone group selector for routing effects
   
   These components support both:
   1. Legacy props (:col, :row, :effect-idx, :effect-path) for effect chain editor
   2. Event template pattern (:event-template) for generic reuse (e.g., projectors)"
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.views.components.spatial-canvas :as spatial-canvas]
            [laser-show.views.components.curve-canvas :as curve-canvas]
            [laser-show.views.components.tabs :as tabs]))


;; Translate Effect Visual Editor


(defn translate-visual-editor
  "Visual editor for translate effect - single draggable center point.
   
   Supports two usage patterns:
   
   1. Effect chain editor props:
      - :col, :row - Grid cell coordinates
      - :effect-path - Path to effect
      - :current-params - Current parameter values {:x ... :y ...}
      - :param-specs - Parameter specifications from effect definition
   
   2. Event template pattern (for generic reuse, e.g., cue chain items):
      - :current-params - Current parameter values (or :params as alias)
      - :event-template - Base event map for on-point-drag (will add :param-map)
      - :fx-key - (optional) Unique key for spatial canvas
      - :width, :height - (optional) Canvas dimensions, default 280x280
      - :hint-text - (optional) Hint text shown above canvas
      - :bounds - (optional) {:x-min :x-max :y-min :y-max}, defaults from param-specs or ±2.0"
  [{:keys [col row effect-path current-params params param-specs
           event-template fx-key width height hint-text bounds]
    :or {width 280 height 280}}]
  (let [;; Support both :current-params and :params (alias)
        params-map (or current-params params {})
        
        ;; Get x/y values
        x (get params-map :x 0.0)
        y (get params-map :y 0.0)
        
        ;; Get bounds - prefer explicit :bounds, then param-specs, then defaults
        {:keys [x-min x-max y-min y-max]}
        (or bounds
            (when param-specs
              (let [x-spec (first (filter #(= :x (:key %)) param-specs))
                    y-spec (first (filter #(= :y (:key %)) param-specs))]
                {:x-min (or (:min x-spec) -2.0)
                 :x-max (or (:max x-spec) 2.0)
                 :y-min (or (:min y-spec) -2.0)
                 :y-max (or (:max y-spec) 2.0)}))
            {:x-min -2.0 :x-max 2.0 :y-min -2.0 :y-max 2.0})
        
        ;; Build on-point-drag event - use event-template if provided, else legacy pattern
        param-map {:center {:x :x :y :y}}
        on-point-drag-event (if event-template
                              (assoc event-template :param-map param-map)
                              {:event/type :chain/update-spatial-params
                               :domain :effect-chains
                               :entity-key [col row]
                               :effect-path effect-path
                               :param-map param-map})
        
        ;; Determine fx/key for spatial canvas
        canvas-key (or fx-key [col row effect-path])
        
        ;; Hint text
        actual-hint (or hint-text "Drag the point to adjust translation")]
    
    {:fx/type :v-box
     :spacing 8
     :padding 8
     :style-class ["card"]
     :children [{:fx/type :label
                 :text actual-hint
                 :style-class ["label-hint"]}
                
                {:fx/type spatial-canvas/spatial-canvas
                 :fx/key canvas-key
                 :width width
                 :height height
                 :bounds {:x-min x-min :x-max x-max
                         :y-min y-min :y-max y-max}
                 :points [{:id :center
                          :x x
                          :y y
                          :color "#4CAF50"
                          :label ""}]
                 :on-point-drag on-point-drag-event
                 :show-grid true
                 :show-axes true
                 :show-labels true}
                
                {:fx/type :h-box
                 :spacing 12
                 :alignment :center
                 :children [{:fx/type :label
                            :text (format "X: %.3f" x)
                            :style-class ["text-monospace"]}
                           {:fx/type :label
                            :text (format "Y: %.3f" y)
                            :style-class ["text-monospace"]}]}]}))


;; Corner Pin Effect Visual Editor


(defn corner-pin-visual-editor
  "Visual editor for corner pin effect - 4 draggable corners.
   
   Supports two usage patterns:
   
   1. Effect chain editor props:
      - :col, :row - Grid cell coordinates
      - :effect-path - Path to effect
      - :current-params - Current parameter values {:tl-x :tl-y :tr-x ...}
      - :param-specs - Parameter specifications from effect definition
   
   2. Event template pattern (for generic reuse, e.g., projectors):
      - :current-params - Current parameter values (or :params as alias)
      - :event-template - Base event map for on-point-drag (will add :param-map)
      - :reset-event - (optional) Event to dispatch on reset button click
      - :fx-key - (optional) Unique key for spatial canvas
      - :width, :height - (optional) Canvas dimensions, default 280x280
      - :hint-text - (optional) Hint text shown above canvas
      - :bounds - (optional) {:x-min :x-max :y-min :y-max}, defaults to ±1.5 or from param-specs"
  [{:keys [col row effect-path current-params params param-specs
           event-template reset-event fx-key width height hint-text bounds]
    :or {width 280 height 280}}]
  (let [;; Support both :current-params and :params (alias)
        params-map (or current-params params {})
        
        ;; Get corner positions
        tl-x (get params-map :tl-x -1.0)
        tl-y (get params-map :tl-y 1.0)
        tr-x (get params-map :tr-x 1.0)
        tr-y (get params-map :tr-y 1.0)
        bl-x (get params-map :bl-x -1.0)
        bl-y (get params-map :bl-y -1.0)
        br-x (get params-map :br-x 1.0)
        br-y (get params-map :br-y -1.0)
        
        ;; Get bounds - prefer explicit :bounds, then param-specs, then defaults
        {:keys [x-min x-max y-min y-max]}
        (or bounds
            (when param-specs
              (let [x-spec (first (filter #(= :tl-x (:key %)) param-specs))
                    y-spec (first (filter #(= :tl-y (:key %)) param-specs))]
                {:x-min (or (:min x-spec) -2.0)
                 :x-max (or (:max x-spec) 2.0)
                 :y-min (or (:min y-spec) -2.0)
                 :y-max (or (:max y-spec) 2.0)}))
            {:x-min -1.0 :x-max 1.0 :y-min -1.0 :y-max 1.0})
        
        ;; Build on-point-drag event - use event-template if provided, else legacy pattern
        corner-param-map {:tl {:x :tl-x :y :tl-y}
                         :tr {:x :tr-x :y :tr-y}
                         :bl {:x :bl-x :y :bl-y}
                         :br {:x :br-x :y :br-y}}
        on-point-drag-event (if event-template
                              (assoc event-template :param-map corner-param-map)
                              {:event/type :chain/update-spatial-params
                               :domain :effect-chains
                               :entity-key [col row]
                               :effect-path effect-path
                               :param-map corner-param-map})
        
        ;; Determine fx/key for spatial canvas
        canvas-key (or fx-key [col row effect-path])
        
        ;; Hint text
        actual-hint (or hint-text "Drag corners to adjust perspective mapping")]
    
    {:fx/type :v-box
     :spacing 8
     :padding 8
     :style-class ["visual-editor-padded"]
     :children (vec
                (concat
                  [{:fx/type :label
                    :text actual-hint
                    :style-class ["visual-editor-hint"]}
                   
                   {:fx/type spatial-canvas/spatial-canvas
                    :fx/key canvas-key
                    :width width
                    :height height
                    :bounds {:x-min x-min :x-max x-max
                            :y-min y-min :y-max y-max}
                    :points [{:id :tl :x tl-x :y tl-y :color "#FF5722" :label "TL"}
                             {:id :tr :x tr-x :y tr-y :color "#4CAF50" :label "TR"}
                             {:id :bl :x bl-x :y bl-y :color "#2196F3" :label "BL"}
                             {:id :br :x br-x :y br-y :color "#FFC107" :label "BR"}]
                    :lines [{:from :tl :to :tr :color "#7AB8FF" :line-width 2}
                            {:from :tr :to :br :color "#7AB8FF" :line-width 2}
                            {:from :br :to :bl :color "#7AB8FF" :line-width 2}
                            {:from :bl :to :tl :color "#7AB8FF" :line-width 2}]
                    :polygon {:points [:tl :tr :br :bl] :color "#4A6FA520"}
                    :on-point-drag on-point-drag-event
                    :show-grid true
                    :show-axes true
                    :show-labels true}
                   
                   {:fx/type :v-box
                    :spacing 4
                    :children [{:fx/type :h-box
                               :spacing 12
                               :alignment :center
                               :children [{:fx/type :label
                                          :text (format "TL: (%.2f, %.2f)" tl-x tl-y)
                                          :style-class ["visual-editor-coord-tl"]}
                                         {:fx/type :label
                                          :text (format "TR: (%.2f, %.2f)" tr-x tr-y)
                                          :style-class ["visual-editor-coord-tr"]}]}
                              {:fx/type :h-box
                               :spacing 12
                               :alignment :center
                               :children [{:fx/type :label
                                          :text (format "BL: (%.2f, %.2f)" bl-x bl-y)
                                          :style-class ["visual-editor-coord-bl"]}
                                         {:fx/type :label
                                          :text (format "BR: (%.2f, %.2f)" br-x br-y)
                                          :style-class ["visual-editor-coord-br"]}]}]}]
                 
                 ;; Optional reset button when reset-event is provided
                 (when reset-event
                   [{:fx/type :button
                     :text "Reset to Defaults"
                     :style-class ["visual-editor-reset-btn"]
                     :on-action reset-event}])))}))


;; RGB Curves Visual Editor


(def ^:private curve-tab-definitions
 "Tab definitions for the R/G/B channel tabs."
 [{:id :r :label "Red"}
  {:id :g :label "Green"}
  {:id :b :label "Blue"}])

(defn- curve-channel-color
 "Get the color for a curve channel."
 [channel]
 (case channel
   :r "#FF5555"
   :g "#55FF55"
   :b "#5555FF"
   "#FFFFFF"))

(defn- curve-editor-for-channel
 "Single curve editor for one color channel.
   
   Props:
   - :domain - Chain domain (:effect-chains, :projector-effects, or :item-effects)
   - :entity-key - Entity key ([col row] or projector-id)
   - :effect-path - Path to effect
   - :channel - Channel keyword (:r, :g, or :b)
   - :current-points - Control points for this channel"
 [{:keys [domain entity-key effect-path channel current-points]}]
 (let [color (curve-channel-color channel)
       points (or current-points [[0.0 0.0] [1.0 1.0]])
       add-event {:event/type :chain/add-curve-point
                  :domain domain
                  :entity-key entity-key
                  :effect-path effect-path
                  :channel channel}
       update-event {:event/type :chain/update-curve-point
                     :domain domain
                     :entity-key entity-key
                     :effect-path effect-path
                     :channel channel}
       remove-event {:event/type :chain/remove-curve-point
                     :domain domain
                     :entity-key entity-key
                     :effect-path effect-path
                     :channel channel}
       canvas-key [domain entity-key effect-path channel]]
   {:fx/type :v-box
    :spacing 8
    :padding 8
    :children [{:fx/type curve-canvas/curve-canvas
                :fx/key canvas-key
                :width 280
                :height 280
                :color color
                :control-points points
                :on-add-point add-event
                :on-update-point update-event
                :on-remove-point remove-event}
               {:fx/type :label
                :text "Click to add point • Drag to move • Right-click to delete"
                :style-class ["visual-editor-hint"]}]}))

(defn rgb-curves-visual-editor
 "Visual editor for RGB curves effect with tabbed R/G/B interface.
    
    Props:
       - :domain - Chain domain (:effect-chains, :projector-effects, or :item-effects)
       - :entity-key - Entity key ([col row], projector-id, or item-effects map)
       - :effect-path - Path to effect
       - :current-params - Current parameter values
       - :dialog-data - Dialog state containing UI modes and active channel"
 [{:keys [domain entity-key effect-path current-params dialog-data]}]
 (let [
       
       ;; Get active channel from dialog state
       active-channel (get-in dialog-data [:ui-modes effect-path :active-curve-channel] :r)
       
       ;; Get control points for each channel (normalized 0.0-1.0)
       r-points (get current-params :r-curve-points [[0.0 0.0] [1.0 1.0]])
       g-points (get current-params :g-curve-points [[0.0 0.0] [1.0 1.0]])
       b-points (get current-params :b-curve-points [[0.0 0.0] [1.0 1.0]])
       
       ;; Get points for active channel
       active-points (case active-channel
                      :r r-points
                      :g g-points
                      :b b-points
                      r-points)
       
       tab-change-event {:event/type :chain/set-active-curve-channel
                         :domain domain
                         :entity-key entity-key
                         :effect-path effect-path}]
  {:fx/type :v-box
   :spacing 0
   :style-class ["visual-editor"]
   :children [{:fx/type tabs/styled-tab-bar
               :tabs curve-tab-definitions
               :active-tab active-channel
               :on-tab-change tab-change-event}
              {:fx/type curve-editor-for-channel
               :domain domain
               :entity-key entity-key
               :effect-path effect-path
               :channel active-channel
               :current-points active-points}]}))


;; Zone Reroute Visual Editor


(def ^:private mode-labels
 "Labels for zone reroute modes."
 {:replace "Replace"
  :add "Add"
  :filter "Filter"})

(def ^:private mode-descriptions
 "Descriptions for zone reroute modes."
 {:replace "Override destination with these zone groups"
  :add "Add these zone groups to existing destination"
  :filter "Only keep zones that are in BOTH destinations"})

(defn- zone-group-select-chip
 "Clickable chip for selecting/deselecting a zone group."
 [{:keys [group selected? on-toggle]}]
 (let [{:keys [id name color]} group]
   {:fx/type :label
    :text name
    :style (str "-fx-background-color: " (if selected? color (str color "40")) "; "
               "-fx-text-fill: " (if selected? "white" "#808080") "; "
               "-fx-padding: 4 10; "
               "-fx-background-radius: 12; "
               "-fx-font-size: 11; "
               "-fx-cursor: hand;")
    :on-mouse-clicked on-toggle}))

(defn zone-reroute-visual-editor
 "Visual editor for zone-reroute effect - zone group selector with mode.
  
  This component requires fx/context to access zone groups.
  
  Props:
  - :fx/context - cljfx context (required)
  - :current-params - Current parameter values {:mode :target-zone-groups ...}
  - :on-change-event - Base event template for param changes (will add :param-key :value)"
 [{:keys [fx/context current-params on-change-event]}]
 (let [;; Get zone groups from context
       zone-groups (fx/sub-ctx context subs/zone-groups-list)
       
       ;; Get current values
       mode (get current-params :mode :replace)
       target-mode (get current-params :target-mode :zone-groups)
       target-zone-groups (set (get current-params :target-zone-groups [:all]))
       
       ;; Mode selector
       mode-button (fn [m]
                     {:fx/type :button
                      :text (get mode-labels m (name m))
                      :style-class [(if (= mode m)
                                      "visual-editor-mode-btn-active"
                                      "visual-editor-mode-btn")]
                      :on-action (assoc on-change-event :param-key :mode :value m)})]
   
   {:fx/type :v-box
    :spacing 12
    :padding 12
    :style-class ["visual-editor"]
    :children [;; Mode selector
               {:fx/type :v-box
                :spacing 6
                :children [{:fx/type :label
                            :text "ROUTING MODE"
                            :style-class ["visual-editor-section-label"]}
                           {:fx/type :h-box
                            :spacing 4
                            :children [(mode-button :replace)
                                      (mode-button :add)
                                      (mode-button :filter)]}
                           {:fx/type :label
                            :text (get mode-descriptions mode "")
                            :style-class ["visual-editor-description"]}]}
               
               ;; Zone group selector
               {:fx/type :v-box
                :spacing 6
                :children [{:fx/type :label
                            :text "TARGET ZONE GROUPS"
                            :style-class ["visual-editor-section-label"]}
                           {:fx/type :flow-pane
                            :hgap 6
                            :vgap 6
                            :children (vec
                                       (for [group zone-groups]
                                         {:fx/type zone-group-select-chip
                                          :fx/key (:id group)
                                          :group group
                                          :selected? (contains? target-zone-groups (:id group))
                                          :on-toggle (let [new-groups (if (contains? target-zone-groups (:id group))
                                                                       (vec (disj target-zone-groups (:id group)))
                                                                       (vec (conj target-zone-groups (:id group))))]
                                                      (assoc on-change-event
                                                             :param-key :target-zone-groups
                                                             :value (if (empty? new-groups) [:all] new-groups)))}))}
                           {:fx/type :label
                            :text (str "Selected: " (if (empty? target-zone-groups)
                                                     "none"
                                                     (str/join ", " (map name target-zone-groups))))
                            :style-class ["visual-editor-selected-text"]}]}
               
               ;; Info about current routing
               {:fx/type :v-box
                :spacing 4
                :style-class ["visual-editor-info-panel"]
                :children [{:fx/type :label
                            :text "ℹ️ Zone effects modify routing BEFORE frame generation"
                            :style-class ["visual-editor-info-title"]}
                           {:fx/type :label
                            :text (case mode
                                   :replace "This will completely override the cue's destination"
                                   :add "This will add to the cue's existing destination"
                                   :filter "This will restrict to zones matching both destinations"
                                   "")
                            :style-class ["visual-editor-info-text"]}]}]}))
