(ns laser-show.views.components.grid-cell
  "Grid cell components with shared behavior via generic-grid-cell.
   
   This namespace provides:
   - generic-grid-cell: Configurable base component with drag/drop
   - grid-cell: Cue grid cell (wrapper)
   - effects-cell: Effects grid cell (wrapper)
   
   Key features:
   - DRY: Single implementation of drag-and-drop logic
   - CSS Best Practices: State-based styling via CSS classes
   - Flexible: Easy to add new cell types
   
   Styling:
   - All background colors via CSS classes (no inline styles)
   - State classes: grid-cell-empty, grid-cell-content, grid-cell-active,
     grid-cell-held (a cue Stop holds: the output is off, Play resumes it)
   - Type variants: grid-cell-effects"
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [laser-show.subs :as subs]
            [laser-show.events.core :as events]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.vibes.reactions :as reactions]
            [laser-show.views.components.load-warning :as load-warning]
            [laser-show.views.components.shortcuts :as shortcuts]
            [laser-show.views.components.target-control :as target-control]
            [laser-show.views.components.zone-pattern-editor :as zone-pattern-editor]
            [laser-show.views.components.drag-drop-cell :as drag-drop])
  (:import [javafx.scene.input MouseEvent MouseButton]))


;; Style Class Builders


(defn- build-style-classes
  "Build style-class vector based on cell state.
   
   Base class 'grid-cell' is always included.
   Cell type adds 'grid-cell-<type>' (e.g., grid-cell-effects).
   State classes are added based on display-data."
  [cell-type {:keys [active? held? has-content? selected? learning? has-trigger?]}]
  (cond-> ["grid-cell" (str "grid-cell-" (name cell-type))]
    selected? (conj "grid-cell-selected")
    active? (conj "grid-cell-active")
    held? (conj "grid-cell-held")
    has-content? (conj "grid-cell-content")
    (not has-content?) (conj "grid-cell-empty")
    has-trigger? (conj "grid-cell-has-trigger")
    learning? (conj "grid-cell-learning")))


;; Label Functions


(defn cell-label
  "Display text for a cue cell.
   Shows custom name if set, otherwise first preset name, or preset count if multiple.
   Shows 'Timeline' for a timeline cue without presets: none is made on the
   grid any more (timelines are shows), but a cell an Open couldn't move
   still says what it holds."
  [{:keys [name first-preset-id preset-count timeline?]}]
  (cond
    ;; Custom name takes priority
    (seq name) name
    ;; No presets
    (nil? first-preset-id) (if timeline? "Timeline" "")
    ;; Single preset - show preset name
    (= preset-count 1) (-> first-preset-id clojure.core/name (str/replace "-" " "))
    ;; Multiple presets - show first + count
    :else (str (-> first-preset-id clojure.core/name (str/replace "-" " "))
               " +" (dec preset-count))))


(defn- effect-text
  "An effect as a cell names it: its id in words (:hue-shift -> \"hue shift\")."
  [effect-id]
  (-> effect-id clojure.core/name (str/replace "-" " ")))

(defn effects-label
  "Display text for an effects cell, as a cue cell's: the custom name if set,
   otherwise the first effect, plus \"+n\" for the others; \"Groups only\"
   for a cell that holds groups and no effect."
  [{:keys [name first-effect-id effect-count has-content?]}]
  (cond
    (seq name) name
    (nil? first-effect-id) (if has-content? "Groups only" "")
    (= effect-count 1) (effect-text first-effect-id)
    :else (str (effect-text first-effect-id) " +" (dec effect-count))))

(defn effects-tooltip
  "Tooltip for an effects cell: its effects, one per line (:effect-lines,
   when a zone pattern or Map cycle says what it does, zones plan Z5), or
   nil for none."
  [{:keys [effect-ids effect-lines]}]
  (cond
    (seq effect-lines) (str/join "\n" effect-lines)
    (seq effect-ids) (str/join "\n" (map effect-text effect-ids))))


;; Generic Grid Cell Component


(defn generic-grid-cell
  "Generic grid cell with configurable behavior.
   
   Props:
   - :col, :row - Cell coordinates
   - :cell-type - :grid or :effects (used for CSS class)
   - :display-data - Map with cell state (:active?, :has-content?, :selected?)
   - :size - [width height] of the cell (default [80 60])
   - :font-size - Optional size in px of its label (default: the stylesheet's)
   - :on-click - Event map for a single primary click (augmented with :col,
     :row only: its handler reads what the cell holds when it handles it)
   - :on-right-click - Event map for right-click (augmented with :col, :row)
   - :drag-config - Map with :drag-type and :on-drop event; without it the
     cell has no drag handlers at all (the Vibes picker's)
   - :label-fn - Function (fn [display-data] -> string)
   - :tooltip-fn - Optional (fn [display-data] -> string or nil): the cell's
     tooltip, on the label that carries its context menu
   - :badge - Optional node shown top right (a cue's target badge)
   - :context-menu-items - Optional sequence of menu items for context menu. Displayed only when cell is empty.
   
   Example:
   {:fx/type generic-grid-cell
    :col 0 :row 0
    :cell-type :grid
    :display-data {:active? true :has-content? true}
    :on-click {:event/type :grid/trigger-cell}
    :on-right-click {:event/type :cue-chain/open-editor}
    :drag-config {:drag-type :grid-cell
                  :on-drop {:event/type :grid/move-cell}}
    :label-fn cell-label
    :context-menu-items [{:fx/type :menu-item :text \"Create Cue\" ...}]}
    "
  [{:keys [col row cell-type display-data on-click on-right-click
           drag-config label-fn tooltip-fn context-menu-items size font-size badge]}]
  (let [{:keys [active? has-content?]} display-data
        [width height] (or size [80 60])
        label-text (label-fn display-data)
        tooltip-text (when tooltip-fn (tooltip-fn display-data))
        style-classes (build-style-classes cell-type display-data)
        drag-type (:drag-type drag-config)
        on-drop (:on-drop drag-config)
        has-context-menu? (boolean (seq context-menu-items))
        node {:fx/type :stack-pane
              :id (format "%s-cell-%d-%d" (name cell-type) col row)
              :pick-on-bounds true  ; Ensure mouse events are captured by parent, not children
              :min-width width
              :pref-width width
              :min-height height
              :pref-height height
              :style-class style-classes

              ;; Mouse click handler - handle left/right click and double-click
              :on-mouse-clicked (fn [^MouseEvent e]
                                  (let [button (.getButton e)
                                        click-count (.getClickCount e)]
                                    (cond
                                      ;; Double-click left: open editor directly
                                      (and (= button MouseButton/PRIMARY) (>= click-count 2))
                                      (events/dispatch! (assoc on-right-click
                                                               :col col :row row))

                                      ;; Secondary click when no context menu (fallback)
                                      (and (= button MouseButton/SECONDARY) (not has-context-menu?))
                                      (do
                                        (log/debug "Grid cell right-click"
                                                   {:cell-type cell-type
                                                    :col col
                                                    :row row
                                                    :event-type (:event/type on-right-click)})
                                        (events/dispatch! (assoc on-right-click
                                                                 :col col :row row)))

                                      ;; Single left-click: dispatch click event (trigger / select)
                                      (= button MouseButton/PRIMARY)
                                      (events/dispatch! (assoc on-click :col col :row row)))))

              :children (filterv some?
                                 [{:fx/type :v-box
                                   :alignment :center
                                   :spacing 2
                                   :children (filterv some?
                                                      [(when (:learning? display-data)
                                                         {:fx/type :label
                                                          :text "LEARN"
                                                          :style-class "grid-cell-learning-badge"})
                                                       (cond-> {:fx/type :label
                                                                :text label-text
                                                                :style-class "grid-cell-label"}
                                                         font-size (assoc :style (str "-fx-font-size: " font-size "px;")))
                                                       (when active?
                                                         {:fx/type :region
                                                          :pref-width 8
                                                          :pref-height 8
                                                          :style-class "grid-cell-active-indicator"})
                                                       (when (or (:has-trigger? display-data) (:warning display-data))
                                                         {:fx/type :h-box
                                                          :alignment :center
                                                          :spacing 3
                                                          :children (filterv some?
                                                                             [(when (:has-trigger? display-data)
                                                                                {:fx/type :label
                                                                                 :text "⚡"
                                                                                 :style-class "grid-cell-trigger-indicator"})
                                                                              ;; too heavy for a laser (zones plan Z3)
                                                                              (when (:warning display-data)
                                                                                {:fx/type :label
                                                                                 :id (format "%s-cell-warning-%d-%d" (name cell-type) col row)
                                                                                 :text "⚠"
                                                                                 :style-class "grid-cell-warning-indicator"})])})])}
                                  badge])}
        ;; The drag's four handlers on the one node, or none (cljfx can't take
        ;; a nil handler): the cell starts a drag only while it holds
        ;; something, and takes a drop and ends its own drag either way.
        ;; Re-created each render, so they have current values.
        node (cond-> node
               drag-config
               (assoc :on-drag-detected (drag-drop/make-drag-detected-handler
                                         {:drag-type drag-type
                                          :col col
                                          :row row
                                          :has-content? has-content?})
                      :on-drag-done (drag-drop/make-drag-done-handler)
                      :on-drag-over (drag-drop/make-drag-over-handler
                                     {:drag-type drag-type})
                      :on-drag-dropped (drag-drop/make-drag-dropped-handler
                                        {:drag-type drag-type
                                         :col col
                                         :row row
                                         :on-drop on-drop})))]
    (if has-context-menu?
      (cond-> {:fx/type :label
               :content-display :graphic-only
               :padding 0
               :context-menu {:fx/type :context-menu
                              :items context-menu-items}
               :graphic node}
        tooltip-text (assoc :tooltip {:fx/type :tooltip :text tooltip-text}))
      node)))


;; Cue Grid Cell


(defn- cue-tooltip
  "A cue cell's tooltip: its warning (zones plan Z3), then where it goes
   (its badge's line, Z4), then its zone pattern (Z5)."
  [{:keys [warning badge-line pattern-line]}]
  (some->> [(load-warning/cue-warning-text warning) badge-line pattern-line]
           (remove str/blank?)
           seq
           (str/join "\n")))

(defn- target-badge
  "Where the cue goes, top right (zones plan Z4, 06 §8.5): its zones, then
   📌 and what it pins; dim when all of it is inherited; then ⇄ when a zone
   pattern moves it (Z5). Mouse-transparent, so its words are in the cell's
   tooltip."
  [col row {:keys [zones pins inherited? pattern?]}]
  {:fx/type :h-box
   :id (format "grid-cell-badge-%d-%d" col row)
   :stack-pane/alignment :top-right
   :stack-pane/margin 3
   :max-width :use-pref-size
   :max-height :use-pref-size
   :mouse-transparent true
   :alignment :center
   :spacing 2
   :style-class (cond-> ["grid-cell-target-badge"] inherited? (conj "grid-cell-target-badge-inherited"))
   :children (cond-> []
               zones (conj {:fx/type :label :text zones :style-class ["label" "grid-cell-target-badge-text"]})
               pins (conj (target-control/pin-icon 8 "#E0E0E0")
                          {:fx/type :label :text pins :style-class ["label" "grid-cell-target-badge-text"]})
               pattern? (conj {:fx/type :label :id (format "grid-cell-pattern-%d-%d" col row) :text "⇄"
                               :style-class ["label" "grid-cell-target-badge-text" "grid-cell-pattern-glyph"]}))})

(defn grid-cell
  "Cue grid cell - wrapper around generic-grid-cell.
   
   Props:
   - col: Column index
   - row: Row index
   - size, font-size: The cell's [width height], and its label's size
   
   Behavior:
   - Left-click: Trigger the cell (if has content) or select (if empty)
   - Double-click: Open the cue chain editor (the grid holds no timelines)
   - Right-click: Context menu with Copy/Paste/Edit/Clear and Trigger actions
   - Drag from cell: Start dragging cell content
   - Drop on cell: Move cell content"
  [{:keys [fx/context col row size font-size]}]
  (let [display-data (fx/sub-ctx context subs/cell-display-data col row)
        ;; The show a Learn starts in (reactivity plan 24)
        document-id (fx/sub-ctx context subs/document-id)
        has-content? (:has-content? display-data)
        has-trigger? (fx/sub-ctx context subs/cell-has-trigger? :trigger-cue col row)
        learning? (fx/sub-ctx context subs/cell-learning? :trigger-cue col row)
        ;; In play while Stop blacks the output out: held, not playing
        held? (and (:active? display-data) (fx/sub-ctx context subs/output-held?))
        ;; Too heavy for a laser, while it plays (zones plan Z3)
        warning (fx/sub-ctx context subs/cue-warning col row)
        ;; Where it goes (zones plan Z4): only a badge with a zone reads the
        ;; map in use, for its tooltip
        badge (fx/sub-ctx context subs/cell-target-badge col row)
        ;; Its zone pattern (zones plan Z5): ⇄ on the badge, a line in the tooltip
        pattern (fx/sub-ctx context subs/cue-zone-pattern [col row])
        pattern? (and pattern (not (false? (:enabled? pattern))))
        cell-display-data (assoc display-data
                                 :active? (and (:active? display-data) (not held?))
                                 :held? held?
                                 :has-trigger? has-trigger?
                                 :learning? learning?
                                 :warning warning
                                 :badge-line (when badge (fx/sub-ctx context subs/cell-target-tooltip col row))
                                 :pattern-line (when pattern?
                                                 (str "Zone pattern: "
                                                      (zone-pattern-editor/chip-text pattern (fx/sub-ctx context subs/names-ctx)))))
        open-editor-event {:event/type :cue-chain/open-editor}
        paste-off? (not (fx/sub-ctx context subs/can-paste? :cue-chain))
        base-menu-items (if has-content?
                          [{:fx/type :menu-item
                            :text (str "Copy Cue (" (shortcuts/shortcut-text :c) ")")
                            :on-action {:event/type :grid/copy-cell :col col :row row}}
                           {:fx/type :menu-item
                            :text (str "Paste Cue (" (shortcuts/shortcut-text :v) ")")
                            :disable paste-off?
                            :on-action {:event/type :grid/paste-cell :col col :row row}}
                           {:fx/type :menu-item
                            :text "Edit Cue..."
                            :on-action (assoc open-editor-event :col col :row row)}
                           {:fx/type :separator-menu-item}
                           {:fx/type :menu-item
                            :text "Clear Cell"
                            :on-action {:event/type :grid/clear-cell :col col :row row}}]
                          [{:fx/type :menu-item
                            :text "Create Cue"
                            :on-action {:event/type :cue-chain/open-editor :col col :row row}}
                           {:fx/type :menu-item
                            :text (str "Paste Cue (" (shortcuts/shortcut-text :v) ")")
                            :disable paste-off?
                            :on-action {:event/type :grid/paste-cell :col col :row row}}])
        trigger-menu-items (filterv some?
                                    [{:fx/type :separator-menu-item}
                                     (if learning?
                                       {:fx/type :menu-item
                                        :text "Cancel Learning Trigger"
                                        ;; Only the cell that waits reads the attempt its Cancel names
                                        :on-action (merge {:event/type :input/cancel-learn}
                                                          (fx/sub-ctx context subs/learn-for-target {:type :trigger-cue :target [col row]}))}
                                       {:fx/type :menu-item
                                        :text "Learn Trigger..."
                                        :on-action {:event/type :input/start-learn
                                                    :target-action {:type :trigger-cue :target [col row]}
                                                    :document-id document-id}})
                                     (when has-trigger?
                                       {:fx/type :menu-item
                                        :text "Clear Triggers"
                                        :on-action {:event/type :input/clear-cell-triggers
                                                    :target-action {:type :trigger-cue :target [col row]}}})
                                     {:fx/type :menu-item
                                      :id "configure-triggers-menu-item"
                                      :text "Configure Triggers..."
                                      :on-action {:event/type :ui/open-dialog
                                                  :dialog-id :trigger-editor
                                                  :data {:col col
                                                         :row row
                                                         :cell-mode :cue}}}])]
    {:fx/type generic-grid-cell
     :col col
     :row row
     :size size
     :font-size font-size
     :cell-type :grid
     :display-data cell-display-data
     :on-click {:event/type :grid/trigger-cell}
     :on-right-click open-editor-event
     :drag-config {:drag-type :grid-cell
                   :on-drop {:event/type :grid/move-cell}}
     :label-fn cell-label
     :tooltip-fn cue-tooltip
     :badge (when (or badge pattern?) (target-badge col row (assoc badge :pattern? pattern?)))
     :context-menu-items (into base-menu-items trigger-menu-items)}))


;; Effects Grid Cell


(def ^:private reaction-groups
  "The New tap reaction menu's headings, by the role a template follows."
  {:hit "HIT" :accent "ACCENT" :flash "FLASH" :vibe "VIBE" :any "HIT, ACCENT or FLASH"})

(defn- new-reaction-menu
  "New tap reaction ▸, for an empty effects cell: the templates
   (vibes.reactions), under the role each follows. A choice makes the cell
   that reaction (:effects/new-reaction)."
  [col row]
  {:fx/type :menu
   :id "new-reaction-menu"
   :text "New tap reaction"
   :items (into []
                (comp (partition-by :role)
                      (map-indexed (fn [i [{:keys [role]} :as group]]
                                     (concat (when (pos? i) [{:fx/type :separator-menu-item}])
                                             [{:fx/type :menu-item :text (reaction-groups role) :disable true}]
                                             (for [{:keys [kind menu]} group]
                                               {:fx/type :menu-item
                                                :id (str "new-reaction-" (name kind))
                                                :text menu
                                                :on-action {:event/type :effects/new-reaction
                                                            :col col :row row :kind kind}}))))
                      cat)
                reactions/templates)})

(defn- new-zone-pattern-menu
  "New zone pattern ▸, for an empty effects cell (zones plan Z5, 07 §7.2):
   a rule style, or a Map cycle. The cell becomes it, off, and its editor
   opens (:zone-patterns/new-cell). A step grid starts from one of them, in
   that editor (zones plan Z6)."
  [col row]
  {:fx/type :menu
   :id "new-zone-pattern-menu"
   :text "New zone pattern"
   :items (conj (mapv (fn [style]
                        {:fx/type :menu-item
                         :id (str "new-zone-pattern-" (name style))
                         :text (zone-patterns/style-names style)
                         :on-action {:event/type :zone-patterns/new-cell :col col :row row :style style}})
                      zone-patterns/rule-styles)
                {:fx/type :separator-menu-item}
                {:fx/type :menu-item
                 :id "new-zone-pattern-map-cycle"
                 :text "Map cycle"
                 :on-action {:event/type :zone-patterns/new-cell :col col :row row :style :map-cycle}})})

(defn- effect-lines
  "An effects cell's tooltip lines when it stores a zone pattern or a Map
   cycle (zones plan Z5), on or off: each effect it stores in chain order
   (subs/stored-effect-cell), those two saying what they do; nil otherwise."
  [context col row]
  (let [effects (:leaves (fx/sub-ctx context subs/stored-effect-cell col row))]
    (when (some #(contains? zone-patterns/zone-stage-ids (:effect-id %)) effects)
      (let [names (fx/sub-ctx context subs/names-ctx)
            zone-maps (fx/sub-val context :zone-maps)]
        (mapv (fn [{:keys [effect-id params]}]
                (case effect-id
                  :zone-pattern (zone-pattern-editor/cell-tooltip params names)
                  :zone-map-cycle (zone-patterns/describe-cycle params {:zone-maps zone-maps})
                  (effect-text effect-id)))
              effects)))))

(defn effects-cell
  "Effects grid cell - wrapper around generic-grid-cell.
   
   Props:
   - col: Column index
   - row: Row index
   - size, font-size: The cell's [width height], and its label's size
   
   Behavior:
   - Left-click: Toggle the effect cell
   - Double-click: Open effect chain editor dialog
   - Right-click: Context menu with Copy/Paste/Edit/Clear and Trigger actions
   - Drag from cell: Start dragging effects
   - Drop on cell: Move effects"
  [{:keys [fx/context col row size font-size]}]
  (let [display-data (fx/sub-ctx context subs/effect-cell-display-data col row)
        ;; The show a Learn starts in (reactivity plan 24)
        document-id (fx/sub-ctx context subs/document-id)
        has-effects? (:has-effects? display-data)
        ;; Holds anything (effects or groups, on or off): not a slot to fill
        has-content? (:has-content? display-data)
        has-trigger? (fx/sub-ctx context subs/cell-has-trigger? :trigger-effect col row)
        learning? (fx/sub-ctx context subs/cell-learning? :trigger-effect col row)
        adapted-data {:active? (:active? display-data)
                      :selected? (:selected? display-data)
                      :has-content? has-content?
                      :effect-count (:effect-count display-data)
                      :first-effect-id (:first-effect-id display-data)
                      :effect-ids (:effect-ids display-data)
                      :effect-lines (when has-effects? (effect-lines context col row))
                      :has-effects? has-effects?
                      :name (:name display-data)
                      :has-trigger? has-trigger?
                      :learning? learning?}
        context-menu-items (filterv some?
                                    [{:fx/type :menu-item
                                      :text (str "Copy Effects (" (shortcuts/shortcut-text :c) ")")
                                      :on-action {:event/type :effects/copy-cell :col col :row row}}
                                     {:fx/type :menu-item
                                      :text (str "Paste Effects (" (shortcuts/shortcut-text :v) ")")
                                      :disable (not (fx/sub-ctx context subs/can-paste? :effects-cell))
                                      :on-action {:event/type :effects/paste-cell :col col :row row}}
                                     {:fx/type :menu-item
                                      :text "Edit Effect Chain..."
                                      :on-action {:event/type :effect-chain/open-editor :col col :row row}}
                                     (when-not has-content?
                                       (new-reaction-menu col row))
                                     (when-not has-content?
                                       (new-zone-pattern-menu col row))
                                     {:fx/type :separator-menu-item}
                                     (if learning?
                                       {:fx/type :menu-item
                                        :text "Cancel Learning Trigger"
                                        ;; Only the cell that waits reads the attempt its Cancel names
                                        :on-action (merge {:event/type :input/cancel-learn}
                                                          (fx/sub-ctx context subs/learn-for-target {:type :trigger-effect :target [col row]}))}
                                       {:fx/type :menu-item
                                        :text "Learn Trigger..."
                                        :on-action {:event/type :input/start-learn
                                                    :target-action {:type :trigger-effect :target [col row]}
                                                    :document-id document-id}})
                                     (when has-trigger?
                                       {:fx/type :menu-item
                                        :text "Clear Triggers"
                                        :on-action {:event/type :input/clear-cell-triggers
                                                    :target-action {:type :trigger-effect :target [col row]}}})
                                     {:fx/type :menu-item
                                      :text "Configure Triggers..."
                                      :on-action {:event/type :ui/open-dialog
                                                  :dialog-id :trigger-editor
                                                  :data {:col col
                                                         :row row
                                                         :cell-mode :effects}}}
                                     {:fx/type :separator-menu-item}
                                     {:fx/type :menu-item
                                      :text "Clear Cell"
                                      :on-action {:event/type :effects/clear-cell :col col :row row}}])]
    {:fx/type generic-grid-cell
     :col col
     :row row
     :size size
     :font-size font-size
     :cell-type :effects
     :display-data adapted-data
     :on-click {:event/type :effects/toggle-cell}
     :on-right-click {:event/type :effect-chain/open-editor}
     :drag-config {:drag-type :effects-cell
                   :on-drop {:event/type :effects/move-cell}}
     :label-fn effects-label
     :tooltip-fn effects-tooltip
     :context-menu-items context-menu-items}))
