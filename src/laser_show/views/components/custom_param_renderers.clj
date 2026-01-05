(ns laser-show.views.components.custom-param-renderers
  "Custom parameter renderers for effects with specialized UI needs.
   
   Provides visual editors for effects like:
   - Translate: 2D point dragging for X/Y position
   - Corner Pin: 4-corner quadrilateral manipulation
   
   These components support both:
   1. Legacy props (:col, :row, :effect-idx, :effect-path) for effect chain editor
   2. Event template pattern (:event-template) for generic reuse (e.g., projectors)"
  (:require
            [laser-show.views.components.spatial-canvas :as spatial-canvas]))


;; Translate Effect Visual Editor


(defn translate-visual-editor
  "Visual editor for translate effect - single draggable center point.
   
   Props:
   - :col, :row - Grid cell coordinates
   - :effect-idx - Index in effect chain (legacy, top-level)
   - :effect-path - Path to effect (supports nested)
   - :current-params - Current parameter values {:x ... :y ...}
   - :param-specs - Parameter specifications from effect definition"
  [{:keys [col row effect-idx effect-path current-params param-specs]}]
  (let [x (get current-params :x 0.0)
        y (get current-params :y 0.0)
        x-spec (first (filter #(= :x (:key %)) param-specs))
        y-spec (first (filter #(= :y (:key %)) param-specs))
        x-min (or (:min x-spec) -2.0)
        x-max (or (:max x-spec) 2.0)
        y-min (or (:min y-spec) -2.0)
        y-max (or (:max y-spec) 2.0)]
    {:fx/type :v-box
     :spacing 8
     :padding 8
     :style "-fx-background-color: #2A2A2A; -fx-background-radius: 4;"
     :children [{:fx/type :label
                 :text "Drag the point to adjust translation"
                 :style "-fx-text-fill: #808080; -fx-font-size: 10; -fx-font-style: italic;"}
                
                {:fx/type spatial-canvas/spatial-canvas
                 :fx/key [col row (or effect-path effect-idx)]
                 :width 280
                 :height 280
                 :bounds {:x-min x-min :x-max x-max
                         :y-min y-min :y-max y-max}
                 :points [{:id :center
                          :x x
                          :y y
                          :color "#4CAF50"
                          :label ""}]
                 :on-point-drag {:event/type :effects/update-spatial-params
                                :col col
                                :row row
                                :effect-idx effect-idx
                                :effect-path effect-path
                                :param-map {:center {:x :x :y :y}}}
                 :show-grid true
                 :show-axes true
                 :show-labels true}
                
                {:fx/type :h-box
                 :spacing 12
                 :alignment :center
                 :children [{:fx/type :label
                            :text (format "X: %.3f" x)
                            :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11; -fx-font-family: 'Consolas', monospace;"}
                           {:fx/type :label
                            :text (format "Y: %.3f" y)
                            :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11; -fx-font-family: 'Consolas', monospace;"}]}]}))


;; Corner Pin Effect Visual Editor


(defn corner-pin-visual-editor
  "Visual editor for corner pin effect - 4 draggable corners.
   
   Supports two usage patterns:
   
   1. Legacy props (for effect chain editor):
      - :col, :row - Grid cell coordinates
      - :effect-idx - Index in effect chain (legacy, top-level)
      - :effect-path - Path to effect (supports nested)
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
  [{:keys [col row effect-idx effect-path current-params params param-specs
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
            {:x-min -1.5 :x-max 1.5 :y-min -1.5 :y-max 1.5})
        
        ;; Build on-point-drag event - use event-template if provided, else legacy pattern
        corner-param-map {:tl {:x :tl-x :y :tl-y}
                         :tr {:x :tr-x :y :tr-y}
                         :bl {:x :bl-x :y :bl-y}
                         :br {:x :br-x :y :br-y}}
        on-point-drag-event (if event-template
                              (assoc event-template :param-map corner-param-map)
                              {:event/type :effects/update-spatial-params
                               :col col
                               :row row
                               :effect-idx effect-idx
                               :effect-path effect-path
                               :param-map corner-param-map})
        
        ;; Determine fx/key for spatial canvas
        canvas-key (or fx-key [col row (or effect-path effect-idx)])
        
        ;; Hint text
        actual-hint (or hint-text "Drag corners to adjust perspective mapping")]
    
    {:fx/type :v-box
     :spacing 8
     :padding 8
     :style "-fx-background-color: #2A2A2A; -fx-background-radius: 4;"
     :children (vec
                (concat
                  [{:fx/type :label
                    :text actual-hint
                    :style "-fx-text-fill: #808080; -fx-font-size: 10; -fx-font-style: italic;"}
                   
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
                                          :style "-fx-text-fill: #FF5722; -fx-font-size: 10; -fx-font-family: 'Consolas', monospace;"}
                                         {:fx/type :label
                                          :text (format "TR: (%.2f, %.2f)" tr-x tr-y)
                                          :style "-fx-text-fill: #4CAF50; -fx-font-size: 10; -fx-font-family: 'Consolas', monospace;"}]}
                              {:fx/type :h-box
                               :spacing 12
                               :alignment :center
                               :children [{:fx/type :label
                                          :text (format "BL: (%.2f, %.2f)" bl-x bl-y)
                                          :style "-fx-text-fill: #2196F3; -fx-font-size: 10; -fx-font-family: 'Consolas', monospace;"}
                                         {:fx/type :label
                                          :text (format "BR: (%.2f, %.2f)" br-x br-y)
                                          :style "-fx-text-fill: #FFC107; -fx-font-size: 10; -fx-font-family: 'Consolas', monospace;"}]}]}]
                  
                  ;; Optional reset button when reset-event is provided
                  (when reset-event
                    [{:fx/type :button
                      :text "Reset to Defaults"
                      :style "-fx-background-color: #505050; -fx-text-fill: #B0B0B0; -fx-font-size: 10; -fx-padding: 4 12;"
                      :on-action reset-event}])))}))

