(ns laser-show.ui.effect-chain-editor
  "Effect chain editor dialog with direct atom binding.
   
   This component allows editing effect chains for effects grid cells.
   All changes are written directly to the !effects atom - no callbacks needed.
   The preview updates automatically since it reads from the same atom.
   
   Layout:
   ┌──────────────────────────────────────────────────────────────┐
   │ Effect Chain Editor - Cell [col, row]                        │
   ├───────────────────────┬──────────────────────────────────────┤
   │ Effect Chain (Left)   │ Effect Picker / Params (Right)       │
   │                       │                                      │
   │ 1. Scale        [x]   │ [Shape] [Color] [Intensity]          │
   │ 2. Rotate       [x]   │ ┌──────────────────────────┐         │
   │                       │ │ Scale                    │         │
   │ [+ Add Effect]        │ │ Rotate                   │         │
   │                       │ │ Mirror                   │         │
   │                       │ └──────────────────────────┘         │
   │                       │ [Add to Chain]                       │
   │                       │                                      │
   │                       │ ─── Parameters ───                   │
   │                       │ X Scale: [======] 1.5                │
   │                       │ Y Scale: [======] 1.5                │
   └───────────────────────┴──────────────────────────────────────┘
                                                         [Done]
   
   Usage:
   (show-effect-chain-editor! parent-frame col row)"
  (:require [seesaw.core :as ss]
            [seesaw.mig :as mig]
            [laser-show.animation.effects :as fx]
            [laser-show.state.atoms :as state]
            [laser-show.ui.colors :as colors]
            [laser-show.ui.drag-drop :as dnd]
            [laser-show.ui.components.slider :as slider])
  (:import [java.awt Color Font Cursor]
           [javax.swing JDialog JPanel DefaultListModel ListSelectionModel BorderFactory]))

;; ============================================================================
;; Effect Categories
;; ============================================================================

(def effect-categories
  [{:id :shape :name "Shape"}
   {:id :color :name "Color"}
   {:id :intensity :name "Intensity"}])

;; ============================================================================
;; Effect List Item Component (for chain list) - with drag-drop reordering
;; ============================================================================

(def ^:private drop-highlight-color (Color. 100 180 255))

(defn- create-effect-list-item
  "Create a panel representing one effect in the chain.
   Supports drag-drop reordering via the provided callbacks."
  [idx effect selected? on-select on-delete on-reorder]
  (let [effect-def (fx/get-effect (:effect-id effect))
        effect-name (or (:name effect-def) (name (:effect-id effect)))
        category (or (:category effect-def) :shape)
        bg-color (if selected?
                   (colors/get-effect-category-color category false)
                   colors/background-medium)
        
        ;; Track drop zone state for visual feedback
        !drop-highlight (atom nil) ;; :top, :bottom, or nil
        
        default-border (BorderFactory/createMatteBorder 0 0 1 0 (Color. 60 60 60))
        
        delete-btn (ss/button :text "×"
                              :font (Font. "SansSerif" Font/BOLD 14)
                              :foreground Color/WHITE
                              :background (Color. 180 60 60)
                              :focusable? false
                              :listen [:action (fn [_] (on-delete idx))])
        
        ;; Drag handle (visual hint that item is draggable)
        drag-handle (ss/label :text "≡"
                              :foreground colors/text-secondary
                              :font (Font. "SansSerif" Font/BOLD 14))
        
        panel (mig/mig-panel
               :constraints ["insets 8", "[][25!][grow][]", ""]
               :items [[drag-handle ""]
                       [(ss/label :text (str (inc idx) ".")
                                  :foreground colors/text-secondary
                                  :font (Font. "SansSerif" Font/PLAIN 12)) ""]
                       [(ss/label :text effect-name
                                  :foreground colors/text-primary
                                  :font (Font. "SansSerif" 
                                              (if selected? Font/BOLD Font/PLAIN) 
                                              13)) "growx"]
                       [delete-btn "w 28!, h 28!"]]
               :background bg-color)
        
        update-border! (fn []
                         (case @!drop-highlight
                           :top (.setBorder panel (BorderFactory/createMatteBorder 3 0 1 0 drop-highlight-color))
                           :bottom (.setBorder panel (BorderFactory/createMatteBorder 0 0 3 0 drop-highlight-color))
                           (.setBorder panel default-border)))]
    
    (.setBorder panel default-border)
    (.setCursor panel (Cursor/getPredefinedCursor Cursor/HAND_CURSOR))
    
    ;; Mouse listener for selection and hover
    (.addMouseListener panel
      (reify java.awt.event.MouseListener
        (mouseClicked [_ _] (on-select idx))
        (mousePressed [_ _])
        (mouseReleased [_ _])
        (mouseEntered [_ _] 
          (when-not selected?
            (.setBackground panel (Color. 60 60 60))))
        (mouseExited [_ _]
          (.setBackground panel bg-color))))
    
    ;; Make draggable
    (dnd/make-draggable! panel
      {:data-fn (fn []
                  {:type :effect-chain-item
                   :idx idx
                   :effect effect})
       :ghost-fn (fn [comp _data]
                   (let [color (colors/get-effect-category-color category false)]
                     (dnd/create-simple-ghost-image
                      (.getWidth comp) (.getHeight comp)
                      color
                      :opacity 0.7
                      :text effect-name)))})
    
    ;; Make drop target
    (dnd/make-drop-target! panel
      {:accept-fn (fn [transfer-data]
                    (and (= (:type transfer-data) :effect-chain-item)
                         (not= (:idx transfer-data) idx)))
       :on-drag-enter (fn [_data]
                        (reset! !drop-highlight :bottom)
                        (update-border!))
       :on-drag-over (fn [_data _x y]
                       (let [mid-y (/ (.getHeight panel) 2)
                             zone (if (< y mid-y) :top :bottom)]
                         (when (not= @!drop-highlight zone)
                           (reset! !drop-highlight zone)
                           (update-border!))))
       :on-drag-exit (fn []
                       (reset! !drop-highlight nil)
                       (update-border!))
       :on-drop (fn [transfer-data]
                  (let [from-idx (:idx transfer-data)
                        zone @!drop-highlight
                        ;; If dropping on top half, insert before; bottom half, insert after
                        to-idx (if (= zone :top)
                                 idx
                                 (inc idx))
                        ;; Adjust target if moving from before to after
                        adjusted-to (if (< from-idx to-idx)
                                      (dec to-idx)
                                      to-idx)]
                    (reset! !drop-highlight nil)
                    (update-border!)
                    (when (not= from-idx adjusted-to)
                      (on-reorder from-idx adjusted-to))
                    true))})
    
    panel))

;; ============================================================================
;; Effect Picker Component
;; ============================================================================

(defn- create-category-tab-button [cat-id cat-name active? on-click]
  (let [color (colors/get-effect-category-color cat-id false)
        btn (ss/button :text cat-name
                       :font (Font. "SansSerif" (if active? Font/BOLD Font/PLAIN) 11)
                       :background (if active? color colors/background-medium)
                       :foreground colors/text-primary
                       :focusable? false
                       :listen [:action (fn [_] (on-click cat-id))])]
    (.setBorder btn (BorderFactory/createEmptyBorder 8 8 8 8))
    (.setCursor btn (Cursor/getPredefinedCursor Cursor/HAND_CURSOR))
    btn))

(defn- create-effect-picker
  "Create the effect picker with category tabs and list."
  [on-effect-selected]
  (let [!active-category (atom :shape)
        !tab-buttons (atom [])
        
        effect-list-model (DefaultListModel.)
        effect-list (ss/listbox :model effect-list-model
                                :renderer (fn [renderer {:keys [value selected?]}]
                                            (if value
                                              (let [bg (if selected?
                                                         (colors/get-effect-category-color @!active-category false)
                                                         colors/background-medium)]
                                                (ss/config! renderer
                                                            :text (:name value)
                                                            :foreground colors/text-primary
                                                            :background bg))
                                              (ss/config! renderer :text ""))))
        _ (.setSelectionMode effect-list ListSelectionModel/SINGLE_SELECTION)
        _ (.setBackground effect-list colors/background-medium)
        
        update-effect-list! (fn [cat-id]
                              (.clear effect-list-model)
                              (doseq [effect (fx/list-effects-by-category cat-id)]
                                (.addElement effect-list-model effect)))
        
        update-tabs! (fn [active-cat]
                       (doseq [[btn cat-id] @!tab-buttons]
                         (let [active? (= cat-id active-cat)
                               color (colors/get-effect-category-color cat-id false)]
                           (ss/config! btn
                                       :background (if active? color colors/background-medium)
                                       :font (Font. "SansSerif" (if active? Font/BOLD Font/PLAIN) 11)))))
        
        on-tab-click (fn [cat-id]
                       (reset! !active-category cat-id)
                       (update-tabs! cat-id)
                       (update-effect-list! cat-id))
        
        tab-buttons (mapv (fn [cat]
                            (let [btn (create-category-tab-button 
                                       (:id cat) (:name cat)
                                       (= (:id cat) :shape)
                                       on-tab-click)]
                              [btn (:id cat)]))
                          effect-categories)
        _ (reset! !tab-buttons tab-buttons)
        
        tab-panel (ss/horizontal-panel
                   :items (mapv first tab-buttons)
                   :background colors/background-dark)
        
        add-btn (ss/button :text "Add to Chain"
                           :font (Font. "SansSerif" Font/BOLD 12)
                           :listen [:action (fn [_]
                                              (when-let [effect-def (.getSelectedValue effect-list)]
                                                (on-effect-selected (:id effect-def))))])
        
        panel (mig/mig-panel
               :constraints ["insets 5, wrap 1", "[grow, fill]", ""]
               :items [[tab-panel "growx"]
                       [(ss/scrollable effect-list) "grow, h 120!"]
                       [add-btn "right"]]
               :background colors/background-dark)]
    
    (update-effect-list! :shape)
    
    {:panel panel}))

;; ============================================================================
;; Parameter Controls
;; ============================================================================

(defn- create-param-control
  "Create a control for a single parameter.
   Takes the current-value from effect-data, NOT the definition default."
  [param-def current-value col row effect-idx]
  (let [{:keys [key label type default]} param-def
        ;; Use current saved value if available, otherwise fall back to definition default
        initial-value (if (some? current-value) current-value default)
        
        on-change (fn [value]
                    (state/update-effect-param! col row effect-idx key value))
        
        panel (case type
                :float
                (let [ctrl (slider/create-slider 
                            {:min (get param-def :min 0.0)
                             :max (get param-def :max 1.0)
                             :default initial-value
                             :on-change on-change})]
                  (mig/mig-panel
                   :constraints ["insets 4", "[100!][grow, fill][80!]", ""]
                   :items [[(ss/label :text (str label ":")
                                      :foreground colors/text-primary) ""]
                           [(:slider ctrl) "growx"]
                           [(:textfield ctrl) ""]]))
                
                :int
                (let [ctrl (slider/create-slider
                            {:min (get param-def :min 0)
                             :max (get param-def :max 255)
                             :default initial-value
                             :integer? true
                             :on-change on-change})]
                  (mig/mig-panel
                   :constraints ["insets 4", "[100!][grow, fill][80!]", ""]
                   :items [[(ss/label :text (str label ":")
                                      :foreground colors/text-primary) ""]
                           [(:slider ctrl) "growx"]
                           [(:textfield ctrl) ""]]))
                
                :bool
                (let [cb (ss/checkbox :text label
                                      :selected? (boolean initial-value)
                                      :foreground colors/text-primary
                                      :background colors/background-dark)]
                  (.addActionListener cb
                    (reify java.awt.event.ActionListener
                      (actionPerformed [_ _] (on-change (.isSelected cb)))))
                  (mig/mig-panel
                   :constraints ["insets 4", "[grow]", ""]
                   :items [[cb ""]]))
                
                ;; Default: float slider
                (let [ctrl (slider/create-slider 
                            {:min 0.0 :max 1.0 :default (or initial-value 0.5)
                             :on-change on-change})]
                  (mig/mig-panel
                   :constraints ["insets 4", "[100!][grow, fill][80!]", ""]
                   :items [[(ss/label :text (str label ":")
                                      :foreground colors/text-primary) ""]
                           [(:slider ctrl) "growx"]
                           [(:textfield ctrl) ""]])))]
    
    (ss/config! panel :background colors/background-dark)
    {:panel panel :param-key key}))

(defn- create-params-panel
  "Create parameter controls for an effect."
  [effect-def effect-data col row effect-idx]
  (let [param-defs (:parameters effect-def)
        current-params (:params effect-data)
        ;; Pass current value for each param
        controls (mapv (fn [pdef]
                         (let [current-val (get current-params (:key pdef))]
                           (create-param-control pdef current-val col row effect-idx)))
                       param-defs)
        
        panel (if (seq controls)
                (mig/mig-panel
                 :constraints ["insets 0, wrap 1", "[grow, fill]", ""]
                 :items (mapv (fn [ctrl] [(:panel ctrl) "growx"]) controls)
                 :background colors/background-dark)
                (ss/label :text "No parameters"
                          :foreground colors/text-secondary
                          :halign :center))]
    
    (when (instance? JPanel panel)
      (ss/config! panel :background colors/background-dark))
    
    {:panel panel :controls controls}))

;; ============================================================================
;; Main Dialog
;; ============================================================================

(defn show-effect-chain-editor!
  "Show the effect chain editor dialog.
   
   Reads/writes directly to !effects atom at [col row].
   All changes are immediate.
   
   Parameters:
   - parent: Parent component for dialog positioning
   - col, row: Cell coordinates in effects grid"
  [parent col row]
  (let [dialog (JDialog. (ss/to-root parent) 
                         (str "Effect Chain Editor - Cell [" col ", " row "]")
                         true)
        
        ;; UI-local state (selection within dialog, not app state)
        !selected-idx (atom nil)
        
        ;; Forward reference atoms for recursive rebuild
        !rebuild-all (atom nil)
        
        ;; Panels that get dynamically rebuilt
        chain-list-panel (mig/mig-panel
                          :constraints ["insets 0, wrap 1, gap 0", "[grow, fill]", ""]
                          :background colors/background-dark)
        
        params-panel-container (ss/border-panel :background colors/background-dark)
        
        ;; Ensure cell exists
        _ (state/ensure-effect-cell! col row)
        
        ;; Rebuild params panel for selected effect
        rebuild-params! (fn []
                          (.removeAll params-panel-container)
                          (if-let [idx @!selected-idx]
                            (let [cell (state/get-effect-cell col row)
                                  effect (get-in cell [:effects idx])
                                  effect-def (when effect (fx/get-effect (:effect-id effect)))]
                              (if effect-def
                                (let [pp (create-params-panel effect-def effect col row idx)]
                                  (.add params-panel-container (:panel pp) java.awt.BorderLayout/CENTER))
                                (.add params-panel-container 
                                      (ss/label :text "Select an effect" 
                                                :foreground colors/text-secondary)
                                      java.awt.BorderLayout/CENTER)))
                            (.add params-panel-container 
                                  (ss/label :text "Select an effect to edit parameters" 
                                            :foreground colors/text-secondary
                                            :halign :center)
                                  java.awt.BorderLayout/CENTER))
                          (.revalidate params-panel-container)
                          (.repaint params-panel-container))
        
        ;; Rebuild chain list
        rebuild-chain-list! (fn []
                              (.removeAll chain-list-panel)
                              (let [cell (state/get-effect-cell col row)
                                    effects (:effects cell)
                                    selected @!selected-idx]
                                (if (seq effects)
                                  (doseq [[idx effect] (map-indexed vector effects)]
                                    (let [item (create-effect-list-item
                                                idx effect
                                                (= idx selected)
                                                ;; On select
                                                (fn [i]
                                                  (reset! !selected-idx i)
                                                  (when-let [f @!rebuild-all] (f)))
                                                ;; On delete
                                                (fn [i]
                                                  (state/remove-effect-from-cell! col row i)
                                                  (let [new-count (state/get-effect-count col row)]
                                                    (when (and @!selected-idx (>= @!selected-idx new-count))
                                                      (reset! !selected-idx (when (pos? new-count) (dec new-count)))))
                                                  (when-let [f @!rebuild-all] (f)))
                                                ;; On reorder (drag-drop)
                                                (fn [from-idx to-idx]
                                                  (state/reorder-effects-in-cell! col row from-idx to-idx)
                                                  ;; Update selection to follow the moved item
                                                  (when (= @!selected-idx from-idx)
                                                    (reset! !selected-idx to-idx))
                                                  (when-let [f @!rebuild-all] (f))))]
                                      (.add chain-list-panel item)))
                                  (.add chain-list-panel 
                                        (ss/label :text "No effects yet" 
                                                  :foreground colors/text-secondary
                                                  :halign :center))))
                              (.revalidate chain-list-panel)
                              (.repaint chain-list-panel))
        
        rebuild-all! (fn []
                       (rebuild-chain-list!)
                       (rebuild-params!))
        
        _ (reset! !rebuild-all rebuild-all!)
        
        ;; Effect picker - adds to chain
        on-add-effect (fn [effect-id]
                        (let [default-params (fx/get-default-params effect-id)]
                          (state/add-effect-to-cell! col row {:effect-id effect-id
                                                              :params default-params})
                          (let [new-count (state/get-effect-count col row)]
                            (reset! !selected-idx (dec new-count)))
                          (rebuild-all!)))
        
        picker (create-effect-picker on-add-effect)
        
        ;; Separator
        separator (ss/label :text "── Parameters ──"
                            :font (Font. "SansSerif" Font/BOLD 12)
                            :foreground colors/text-secondary
                            :halign :center)
        
        ;; === LEFT PANEL: Effect Chain List ===
        left-panel (mig/mig-panel
                    :constraints ["insets 5, wrap 1", "[180!, fill]", "[grow]"]
                    :items [[(ss/label :text "Effect Chain"
                                       :font (Font. "SansSerif" Font/BOLD 14)
                                       :foreground colors/text-primary) ""]
                            [(ss/scrollable chain-list-panel :border nil) "grow, h 200::"]]
                    :background colors/background-dark)
        
        ;; === RIGHT PANEL: Picker + Params ===
        right-panel (mig/mig-panel
                     :constraints ["insets 5, wrap 1", "[grow, fill]", "[][grow]"]
                     :items [[(:panel picker) "growx"]
                             [separator "growx, gaptop 15"]
                             [(ss/scrollable params-panel-container :border nil) "grow"]]
                     :background colors/background-dark)
        
        ;; Done button
        done-btn (ss/button :text "Done"
                            :font (Font. "SansSerif" Font/BOLD 12)
                            :listen [:action (fn [_] (.dispose dialog))])
        
        ;; Main layout
        main-panel (mig/mig-panel
                    :constraints ["insets 5", "[180!][grow, fill]", "[grow][]"]
                    :items [[left-panel "growy"]
                            [right-panel "grow, wrap"]
                            [done-btn "span 2, right, gaptop 10"]]
                    :background colors/background-dark)]
    
    ;; Initial build
    (rebuild-all!)
    
    ;; NOTE: No atom watch needed!
    ;; - Slider changes write directly to atom (preview sees them automatically)
    ;; - Chain list rebuilds are triggered by add/remove/select callbacks above
    ;; - Adding an atom watch here would cause slider reset loops
    
    (.setContentPane dialog main-panel)
    (.setSize dialog 650 500)
    (.setLocationRelativeTo dialog parent)
    (.setVisible dialog true)))
