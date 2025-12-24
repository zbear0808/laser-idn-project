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
            [laser-show.state.clipboard :as clipboard]
            [laser-show.ui.colors :as colors]
            [laser-show.ui.common :as ui-common]
            [laser-show.ui.drag-drop :as dnd]
            [laser-show.ui.components.slider :as slider])
  (:import [java.awt Color Font Cursor KeyboardFocusManager KeyEventDispatcher]
           [java.awt.event KeyEvent InputEvent WindowAdapter]
           [javax.swing JDialog JPanel JPopupMenu JMenuItem DefaultListModel ListSelectionModel BorderFactory KeyStroke AbstractAction SwingUtilities]))

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
   Supports drag-drop reordering via the provided callbacks.
   
   Parameters:
   - idx: Index of this effect in the chain
   - effect: The effect data
   - selected?: Whether this item is currently selected
   - on-click: (fn [idx ctrl? shift?]) - click handler with modifier info
   - on-delete: (fn [idx]) - delete this single effect
   - on-delete-selected: (fn []) - delete all selected effects
   - on-reorder: (fn [from-idx to-idx]) - reorder via drag-drop
   - on-copy: (fn []) - copy action
   - on-paste: (fn []) - paste action
   - selected-count: Number of currently selected items (for context menu)"
  [idx effect selected? on-click on-delete on-delete-selected on-reorder on-copy on-paste selected-count]
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
               :constraints ["insets 8", "[][grow][]", ""]
               :items [[drag-handle ""]
                       [(ss/label :text effect-name
                                  :foreground colors/text-primary
                                  :font (Font. "SansSerif"
                                              (if selected? Font/BOLD Font/PLAIN)
                                              11)) "growx"]
                       [delete-btn "w 28!, h 28!"]]
               :background bg-color)
        
        update-border! (fn []
                         (case @!drop-highlight
                           :top (.setBorder panel (BorderFactory/createMatteBorder 3 0 1 0 drop-highlight-color))
                           :bottom (.setBorder panel (BorderFactory/createMatteBorder 0 0 3 0 drop-highlight-color))
                           (.setBorder panel default-border)))]
    
    (.setBorder panel default-border)
    (.setCursor panel (Cursor/getPredefinedCursor Cursor/HAND_CURSOR))
    
    ;; Mouse listener for selection, hover, and right-click
    (.addMouseListener panel
      (reify java.awt.event.MouseListener
        (mouseClicked [_ e] 
          (when (= (.getButton e) java.awt.event.MouseEvent/BUTTON1)
            (let [ctrl? (.isControlDown e)
                  shift? (.isShiftDown e)]
              (on-click idx ctrl? shift?))))
        (mousePressed [_ e]
          ;; Right-click shows context menu
          (when (= (.getButton e) java.awt.event.MouseEvent/BUTTON3)
            ;; Note: Don't call on-click here - it rebuilds the list and removes this panel
            ;; from the hierarchy before the popup can display
            (let [popup (JPopupMenu.)
                  multi? (> selected-count 1)
                  copy-label (if multi? 
                               (str "Copy " selected-count " Effects")
                               "Copy Effect")
                  delete-label (if (and multi? selected?)
                                 (str "Delete " selected-count " Effects")
                                 "Delete")]
              (.add popup (doto (JMenuItem. copy-label)
                            (.addActionListener 
                              (reify java.awt.event.ActionListener
                                (actionPerformed [_ _] (on-copy))))))
              (.add popup (doto (JMenuItem. "Paste")
                            (.addActionListener
                              (reify java.awt.event.ActionListener
                                (actionPerformed [_ _] (on-paste))))
                            (.setEnabled (clipboard/can-paste-effects?))))
              (.addSeparator popup)
              (.add popup (doto (JMenuItem. delete-label)
                            (.addActionListener
                              (reify java.awt.event.ActionListener
                                (actionPerformed [_ _] 
                                  (if (and multi? selected?)
                                    (on-delete-selected)
                                    (on-delete idx)))))))
              (.show popup panel (.getX e) (.getY e)))))
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
        
        ;; Double-click handler for list
        _ (ui-common/add-double-click-listener! effect-list
            (fn [_]
              (when-let [effect-def (.getSelectedValue effect-list)]
                (on-effect-selected (:id effect-def)))))
        
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
        ;; Multi-select: track set of selected indices and last-clicked for shift-select
        !selected-indices (atom (sorted-set))
        !last-clicked-idx (atom nil)
        
        ;; Forward reference atoms for recursive rebuild
        !rebuild-all (atom nil)
        
        ;; Panels that get dynamically rebuilt
        chain-list-panel (mig/mig-panel
                          :constraints ["insets 0, wrap 1, gap 0", "[grow, fill]", ""]
                          :background colors/background-dark)
        
        params-panel-container (ss/border-panel :background colors/background-dark)
        
        ;; Ensure cell exists
        _ (state/ensure-effect-cell! col row)
        
        ;; Forward references for copy/paste (used in list items)
        !do-copy (atom nil)
        !do-paste (atom nil)
        
        ;; Rebuild params panel for selected effect
        rebuild-params! (fn []
                          (.removeAll params-panel-container)
                          (let [selected @!selected-indices
                                count (count selected)]
                            (cond
                              ;; No selection
                              (zero? count)
                              (.add params-panel-container 
                                    (ss/label :text "Select an effect to edit parameters" 
                                              :foreground colors/text-secondary
                                              :halign :center)
                                    java.awt.BorderLayout/CENTER)
                              
                              ;; Single selection - show params
                              (= 1 count)
                              (let [idx (first selected)
                                    cell (state/get-effect-cell col row)
                                    effect (get-in cell [:effects idx])
                                    effect-def (when effect (fx/get-effect (:effect-id effect)))]
                                (if effect-def
                                  (let [pp (create-params-panel effect-def effect col row idx)]
                                    (.add params-panel-container (:panel pp) java.awt.BorderLayout/CENTER))
                                  (.add params-panel-container 
                                        (ss/label :text "Select an effect" 
                                                  :foreground colors/text-secondary)
                                        java.awt.BorderLayout/CENTER)))
                              
                              ;; Multi selection - show message
                              :else
                              (.add params-panel-container 
                                    (ss/label :text (str count " effects selected - use Copy/Delete") 
                                              :foreground colors/text-secondary
                                              :halign :center)
                                    java.awt.BorderLayout/CENTER)))
                          (.revalidate params-panel-container)
                          (.repaint params-panel-container))
        
        ;; Delete selected effects (in descending order to avoid index shift issues)
        delete-selected! (fn []
                           (let [indices (sort > @!selected-indices)]
                             (doseq [idx indices]
                               (state/remove-effect-from-cell! col row idx))
                             (reset! !selected-indices (sorted-set))
                             (reset! !last-clicked-idx nil)
                             (when-let [f @!rebuild-all] (f))))
        
        ;; Rebuild chain list
        rebuild-chain-list! (fn []
                              (.removeAll chain-list-panel)
                              (let [cell (state/get-effect-cell col row)
                                    effects (:effects cell)
                                    selected @!selected-indices
                                    selected-count (count selected)]
                                (if (seq effects)
                                  (doseq [[idx effect] (map-indexed vector effects)]
                                    (let [item (create-effect-list-item
                                                idx effect
                                                (contains? selected idx)
                                                ;; On click (with ctrl/shift info)
                                                (fn [i ctrl? shift?]
                                                  (let [effect-count (count effects)]
                                                    (cond
                                                      ;; Shift+click: range select from last-clicked
                                                      shift?
                                                      (if-let [last-idx @!last-clicked-idx]
                                                        (let [start (min last-idx i)
                                                              end (max last-idx i)
                                                              range-indices (set (range start (inc end)))]
                                                          (reset! !selected-indices (apply sorted-set range-indices)))
                                                        ;; No prior click, just select this one
                                                        (do
                                                          (reset! !selected-indices (sorted-set i))
                                                          (reset! !last-clicked-idx i)))
                                                      
                                                      ;; Ctrl+click: toggle this item
                                                      ctrl?
                                                      (do
                                                        (if (contains? @!selected-indices i)
                                                          (swap! !selected-indices disj i)
                                                          (swap! !selected-indices conj i))
                                                        (reset! !last-clicked-idx i))
                                                      
                                                      ;; Normal click: select only this item
                                                      :else
                                                      (do
                                                        (reset! !selected-indices (sorted-set i))
                                                        (reset! !last-clicked-idx i))))
                                                  (when-let [f @!rebuild-all] (f)))
                                                ;; On delete single
                                                (fn [i]
                                                  (state/remove-effect-from-cell! col row i)
                                                  ;; Adjust selected indices after deletion
                                                  (let [new-selected (->> @!selected-indices
                                                                          (remove #(= % i))
                                                                          (map #(if (> % i) (dec %) %))
                                                                          (apply sorted-set))]
                                                    (reset! !selected-indices new-selected))
                                                  (when-let [f @!rebuild-all] (f)))
                                                ;; On delete selected
                                                delete-selected!
                                                ;; On reorder (drag-drop)
                                                (fn [from-idx to-idx]
                                                  (state/reorder-effects-in-cell! col row from-idx to-idx)
                                                  ;; Clear multi-selection on reorder (too complex to maintain)
                                                  (reset! !selected-indices (sorted-set to-idx))
                                                  (reset! !last-clicked-idx to-idx)
                                                  (when-let [f @!rebuild-all] (f)))
                                                ;; On copy
                                                (fn [] (when-let [f @!do-copy] (f)))
                                                ;; On paste  
                                                (fn [] (when-let [f @!do-paste] (f)))
                                                ;; Selected count
                                                selected-count)]
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
                          ;; Select only the newly added effect
                          (let [new-count (state/get-effect-count col row)
                                new-idx (dec new-count)]
                            (reset! !selected-indices (sorted-set new-idx))
                            (reset! !last-clicked-idx new-idx))
                          (rebuild-all!)))
        
        picker (create-effect-picker on-add-effect)
        
        ;; Separator
        separator (ss/label :text "── Parameters ──"
                            :font (Font. "SansSerif" Font/BOLD 12)
                            :foreground colors/text-secondary
                            :halign :center)
        
        ;; Copy/paste actions
        _ (reset! !do-copy
            (fn []
              (let [cell (state/get-effect-cell col row)
                    effects (:effects cell)
                    selected @!selected-indices
                    count (count selected)]
                (cond
                  ;; No selection - copy entire chain
                  (zero? count)
                  (when (seq effects)
                    (clipboard/copy-effect-chain! cell))
                  
                  ;; Single selection - copy as single effect
                  (= 1 count)
                  (when-let [effect (get effects (first selected))]
                    (clipboard/copy-effect! effect))
                  
                  ;; Multi selection - copy selected effects as chain
                  :else
                  (let [selected-effects (mapv #(get effects %) (sort selected))]
                    (clipboard/copy-effect-chain! {:effects selected-effects :active true}))))))
        
        _ (reset! !do-paste
            (fn []
              (when-let [effects-to-add (clipboard/get-effects-to-paste)]
                (let [start-idx (state/get-effect-count col row)]
                  ;; Add each effect to the chain
                  (doseq [effect effects-to-add]
                    (state/add-effect-to-cell! col row effect))
                  ;; Select only the pasted effects
                  (let [new-count (state/get-effect-count col row)
                        pasted-indices (range start-idx new-count)]
                    (reset! !selected-indices (apply sorted-set pasted-indices))
                    (reset! !last-clicked-idx (dec new-count))))
                (rebuild-all!))))
        
        do-copy! (fn [] (@!do-copy))
        do-paste! (fn [] (@!do-paste))
        
        ;; Copy/Paste buttons
        copy-btn (ss/button :text "Copy"
                            :font (Font. "SansSerif" Font/PLAIN 11)
                            :focusable? false
                            :listen [:action (fn [_] (do-copy!))])
        paste-btn (ss/button :text "Paste"
                             :font (Font. "SansSerif" Font/PLAIN 11)
                             :focusable? false
                             :listen [:action (fn [_] (do-paste!))])
        
        ;; === LEFT PANEL: Effect Chain List ===
        left-panel (mig/mig-panel
                    :constraints ["insets 5, wrap 1", "[180!, fill]", "[][grow][]"]
                    :items [[(ss/label :text "Effect Chain"
                                       :font (Font. "SansSerif" Font/BOLD 14)
                                       :foreground colors/text-primary) "aligny top"]
                            [(ss/scrollable chain-list-panel :border nil) "grow"]
                            [(ss/horizontal-panel 
                               :items [copy-btn paste-btn]
                               :background colors/background-dark) "right"]]
                    :background colors/background-dark)
        
        ;; === RIGHT PANEL: Picker + Params ===
        right-panel (mig/mig-panel
                     :constraints ["insets 5, wrap 1", "[grow, fill]", "[][grow]"]
                     :items [[(:panel picker) "growx"]
                             [separator "growx, gaptop 5"]
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
    
    ;; Keyboard shortcuts using KeyEventDispatcher for global interception
    ;; This catches Ctrl+C/V before text fields can consume them
    (let [focus-manager (KeyboardFocusManager/getCurrentKeyboardFocusManager)
          
          key-dispatcher (reify KeyEventDispatcher
                           (dispatchKeyEvent [_ e]
                             ;; Only handle KEY_PRESSED events and only if our dialog is the focused window
                             (if (and (= (.getID e) KeyEvent/KEY_PRESSED)
                                      (= (SwingUtilities/getWindowAncestor (.getComponent e)) dialog))
                               (cond
                                 ;; Ctrl+C - Copy
                                 (and (.isControlDown e) (= (.getKeyCode e) KeyEvent/VK_C))
                                 (do
                                   (println "[DEBUG] COPY (Ctrl+C) intercepted")
                                   (do-copy!)
                                   true)
                                 
                                 ;; Ctrl+X - Cut (copy then delete)
                                 (and (.isControlDown e) (= (.getKeyCode e) KeyEvent/VK_X))
                                 (do
                                   (println "[DEBUG] CUT (Ctrl+X) intercepted")
                                   (when (seq @!selected-indices)
                                     (do-copy!)
                                     (delete-selected!))
                                   true)
                                 
                                 ;; Ctrl+V - Paste
                                 (and (.isControlDown e) (= (.getKeyCode e) KeyEvent/VK_V))
                                 (do
                                   (println "[DEBUG] PASTE (Ctrl+V) intercepted")
                                   (do-paste!)
                                   true)
                                 
                                 ;; Delete - delete selected
                                 (= (.getKeyCode e) KeyEvent/VK_DELETE)
                                 (do
                                   (println "[DEBUG] DELETE intercepted")
                                   (when (seq @!selected-indices)
                                     (delete-selected!))
                                   true)
                                 
                                 :else false)
                               false)))]
      
      ;; Add the dispatcher
      (.addKeyEventDispatcher focus-manager key-dispatcher)
      
      ;; Remove dispatcher when dialog closes to prevent memory leaks
      (.addWindowListener dialog
        (proxy [WindowAdapter] []
          (windowClosed [_]
            (println "[DEBUG] Dialog closed, removing key dispatcher")
            (.removeKeyEventDispatcher focus-manager key-dispatcher)))))
    
    (.setContentPane dialog main-panel)
    (.setSize dialog 650 500)
    (.setLocationRelativeTo dialog parent)
    (.setVisible dialog true)))
