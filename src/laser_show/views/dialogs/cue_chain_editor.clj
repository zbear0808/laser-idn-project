(ns laser-show.views.dialogs.cue-chain-editor
  "Cue chain editor dialog.
   
   Two-column layout:
   - Left column (280px): Cue chain list (top) + Item effects list (bottom) with drag-and-drop reordering
   - Right column: Preset bank + Preset params + Effect bank + Effect params with custom renderers
   
   Features:
   - Multi-select with Click, Ctrl+Click, Shift+Click for both cue items and effects
   - Keyboard shortcuts: Ctrl+C (copy), Ctrl+V (paste), Ctrl+A (select all), Delete
   - Drag-and-drop reordering in both cue chain and effects
   - Copy/paste through the one clipboard ([:ui :clipboard])
   - Effect grouping within items
   - Custom parameter renderers (RGB curves, spatial editors)"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.animation.presets :as presets]
            [laser-show.animation.chains :as chains]
            [laser-show.events.core :as events]
            [laser-show.views.components.list :as list]
            [laser-show.views.components.inline-edit :as inline-edit]
            [laser-show.views.components.preset-bank :as preset-bank]
            [laser-show.views.components.preset-param-editor :as preset-param-editor]
            [laser-show.views.components.effect-parameter-editor :as effect-param-editor]
            [laser-show.views.components.effect-bank :as effect-bank]
            [laser-show.views.components.load-warning :as load-warning]
            [laser-show.views.components.target-control :as target-control]
            [laser-show.views.components.zone-pattern-editor :as zone-pattern-editor]
            [laser-show.views.dialogs.common :as common]
            [laser-show.routing.targets :as targets]
            [clojure.string :as str]))


;; Effect Bank (using data-driven component)


(defn- effect-bank-tabs
  "Tabbed effect bank using data-driven effect-bank component, adding to
   the item :item-id: its click names the item by its id, never its place
   (follow-up N14)."
  [{:keys [col row item-id active-effect-tab]}]
  {:fx/type effect-bank/effect-bank
   :active-tab (or active-effect-tab :shape)
   :on-tab-change {:event/type :cue-chain/set-effect-tab}
   ;; Data-driven event template - the handler receives :item-id, the
   ;; effect's id, and finds the item it adds to by :owner-id
   :item-event-template {:event/type :cue-chain/add-effect-from-bank
                         :col col
                         :row row
                         :owner-id item-id}
   ;; An item's effects: no zone pattern (a cue has its own, zones plan Z5)
   :scope :item})


;; Parameter Editor for Effects (using shared component)


(defn- item-effect-parameter-editor
  "Parameter editor for the selected effect within an item.
   Uses the shared effect-parameter-editor component with keyframe support,
   with the effect's definition as published (subs/effect-descriptor)."
  [{:keys [fx/context col row item-path item selected-effect-ids dialog-data]}]
  (let [item-effects (:effects item [])
        first-selected-id (first selected-effect-ids)
        ;; Use hierarchical path finding for nested effects
        effect-path (when first-selected-id
                      (chains/find-path-by-id item-effects first-selected-id))
        ;; Use path to retrieve the effect from nested structure
        selected-effect (when effect-path
                          (chains/get-item-at-path item-effects effect-path))
        effect-def (when selected-effect
                     (fx/sub-ctx context subs/effect-descriptor (:effect-id selected-effect)))]
    (if selected-effect
      ;; Use shared effect parameter editor component: its edits name the
      ;; effect and the item by their ids (follow-up N14); item-path is
      ;; where it shows the effect
      {:fx/type effect-param-editor/effect-parameter-editor
       :domain :cue-chains
       :entity-key [col row]
       :effect-path effect-path
       :item-path item-path
       :owner-id (:id item)
       :effect selected-effect
       :effect-def effect-def
       :dialog-data dialog-data}
      ;; No effect selected - show placeholder
      {:fx/type :v-box
       :spacing 8
       :style-class "dialog-section"
       :children [{:fx/type :label
                   :text "PARAMETERS"
                   :style-class "header-section"}
                  {:fx/type :label
                   :text "Select an effect from the chain"
                   :style-class "dialog-placeholder-text"}]})))


;; The cue's and each item's target (zones plan Z4): the Target chip


(def ^:private cue-target-tooltip
  "Where the cue's content goes, unless an item, a group or a track sets its own")

(defn- routing-label
  "base-name and where the item goes (06 §7.2): \"Circle [A · Beams → Left ·
   column 3]\", \"Star [Graphics, pinned]\", \"[All]\"."
  [{:keys [names zone-map explain]} base-name item]
  (let [res (get-in explain [:items (:id item)])
        t (:target res)
        text (cond
               (or (nil? t) (= :all t)) "All"
               (targets/has-zone? t) (targets/target-text names zone-map t)
               :else (str (targets/target-text names zone-map t) ", pinned"))
        from (some-> (targets/from-text res) (str/replace #"^from " ""))]
    (str base-name " [" text (when from (str " · " from)) "]")))

(defn- cue-chain-item-label
  "Custom list-editor label renderer for cue chain preset items: the
   preset's name and where it goes."
  [menu-ctx item]
  (let [preset-def (presets/presets-by-id (:preset-id item))]
    (routing-label menu-ctx (or (:name preset-def) (:name item) "Unknown Preset") item)))

(defn- cue-chain-group-label
  "Custom list-editor label renderer for cue chain groups: the group's name
   and where it goes."
  [menu-ctx group]
  (routing-label menu-ctx (or (:name group) "Group") group))

(defn- item-menu-target
  "What the Target menu of item-id in the cue at [col row] reads of the
   item: {:own (its own target) :group?}, nil once it is gone. The menu
   renders again when this does, not for each change to the cue, nor when
   the item moves: its choice names the item (follow-up R2)."
  [context col row item-id]
  (let [items (:items (fx/sub-ctx context subs/cue-cell-chain col row))]
    (when-let [item-path (chains/find-path-by-id items item-id)]
      (let [item (get-in items item-path)]
        {:own (:target item) :group? (chains/group? item)}))))

(defn- item-target-menu
  "The menu of the item or group item-id of the cue at [col row]: Target ▸,
   the Target menu (none once the item is gone). A component of its own:
   what only the menu reads (the zones the show uses, the groups) renders
   it again, not the list's rows."
  [{:keys [fx/context col row item-id]}]
  (let [{:keys [own group?] :as target} (fx/sub-ctx context item-menu-target col row item-id)
        explain (fx/sub-ctx context subs/cue-target-explain col row)]
    {:fx/type :context-menu
     :items (if target
              (let [rows (target-control/menu-model (assoc (target-control/menu-context context)
                                                           :scope (if group? :group :item)
                                                           :own own
                                                           :inherited (get-in explain [:item-inherits item-id])))]
                [{:fx/type :menu
                  :text "Target"
                  :items (target-control/menu-items rows {:event/type :cue-chain/set-item-target
                                                          :col col
                                                          :row row
                                                          :item-id item-id})}])
              [])}))

(defn- cue-chain-context-menu
  "An item's or group's menu, as the cue list asks for it ([this col row]):
   item-target-menu."
  [col row item-or-group]
  {:fx/type item-target-menu :col col :row row :item-id (:id item-or-group)})


;; Middle Section (Preset Config)


(defn- middle-section
  "Middle section with preset bank and parameter editor, for the selected
   item (:selected-item; nil when none or several are selected). It doesn't
   take the item's place: its edits name the item (R2, N10)."
  [{:keys [fx/context col row active-preset-tab selected-item]}]
  (let [is-preset? (and selected-item (= :preset (:type selected-item)))
        is-group? (and selected-item (chains/group? selected-item))
        explain (fx/sub-ctx context subs/cue-target-explain col row)
        item-target (fn [scope]
                      {:fx/type target-control/target-field
                       :id "target-chip-item"
                       :from-id "target-from-item"
                       :scope scope
                       :own (:target selected-item)
                       :inherited (get-in explain [:item-inherits (:id selected-item)])
                       :on-set {:event/type :cue-chain/set-item-target :col col :row row :item-id (:id selected-item)}
                       :tooltip (if (= :group scope)
                                  "Where the group's content goes, unless an item in it sets its own"
                                  "Where this preset goes")})]
    {:fx/type :v-box
     :spacing 0
     :children (filterv
                some?
                [;; Preset bank tabs (top)
                 {:fx/type preset-bank/preset-bank
                  :cell [col row]
                  :active-tab active-preset-tab
                  :on-tab-change {:event/type :cue-chain/set-preset-tab}}


                 ;; Preset/Group parameter editor (bottom)
                 (if is-preset?
                   {:fx/type :v-box
                    :v-box/vgrow :always
                    :spacing 4
                    :children [{:fx/type :h-box
                                :style-class "dialog-section"
                                :children [(item-target :item)]}
                               {:fx/type preset-param-editor/preset-param-editor
                                :v-box/vgrow :always
                                :cell [col row]
                                :preset-instance selected-item}]}
                   (when is-group?
                     {:fx/type :v-box
                      :style-class "dialog-section"
                      :v-box/vgrow :always
                      :spacing 8
                      :children [{:fx/type :label
                                  :text "GROUP PROPERTIES"
                                  :style-class "header-section"}
                                 {:fx/type :label
                                  :text (str "Name: " (:name selected-item "Unnamed Group"))
                                  :style-class "group-properties-label"}
                                 {:fx/type :label
                                  :text (str "Items: " (count (:items selected-item [])))
                                  :style-class "group-properties-label"}
                                 (item-target :group)
                                 {:fx/type :label
                                  :text "Items inside inherit this target unless they have their own."
                                  :style-class "dialog-placeholder-text"}]}))

                 (when (not selected-item)
                   {:fx/type :v-box
                    :v-box/vgrow :always
                    :style-class "dialog-section"
                    :children [{:fx/type :label
                                :text "ITEM PROPERTIES"
                                :style-class "header-section"}
                               {:fx/type :label
                                :text "Select a preset or group from the chain"
                                :style-class "dialog-placeholder-text"}]})])}))


;; NOTE: get-item-effects-selected-ids removed - now reading from subs/list-ui-state


;; Main Dialog Content


(defn- cue-chain-editor-content
  "Main content of the cue chain editor dialog with 2-column layout.
   Left column: Cue chain list (top) + Item effects list (bottom).
   Right column: Preset config + Effect bank + Effect params."
  [{:keys [fx/context]}]
  (let [;; Get cue-chain-editor state from unified dialogs path
        dialog-data (fx/sub-ctx context subs/dialog-data :cue-chain-editor)
        {:keys [col row active-preset-tab active-effect-tab item-effects-ui]} dialog-data

        ;; Read cue chain selection from canonical list-ui state
        cue-list-state (fx/sub-ctx context subs/list-ui-state [:cue-chain col row])
        selected-ids (:selected-ids cue-list-state #{})

        ;; Read item effects selection from canonical list-ui state
        item-effects-list-state (fx/sub-ctx context subs/list-ui-state [:item-effects col row])
        selected-effect-ids (:selected-ids item-effects-list-state #{})

        ;; This cell's cue only: another cell's edit leaves the editor as it is
        cue-chain (fx/sub-ctx context subs/cue-cell-chain col row)

        ;; For parameter editor - resolve first selected ID to path
        first-selected-id (when (= 1 (count selected-ids))
                            (first selected-ids))
        first-selected-path (when first-selected-id
                              (chains/find-path-by-id (:items cue-chain) first-selected-id))

        ;; Get selected item for effects
        selected-item (when first-selected-path
                        (chains/get-item-at-path (:items cue-chain) first-selected-path))
        has-item? (boolean selected-item)
        item-effects (:effects selected-item [])

        ;; Dialog data for custom renderers (UI modes, etc.)
        ;; Merge ui-modes and item-effects-ui together so effects can access both
        effect-dialog-data (merge dialog-data item-effects-ui {:item-effects-ui item-effects-ui})

        ;; What the items' labels read: where each item goes. The list gets
        ;; it as data with top-level label functions, so its description
        ;; stays the same while this does (each row's Target menu is a
        ;; component reading its own)
        label-ctx {:names (fx/sub-ctx context subs/names-ctx)
                   :zone-map (fx/sub-ctx context subs/active-zone-map)
                   :explain (fx/sub-ctx context subs/cue-target-explain col row)}
        ;; The item effects' names, as published (data for that list)
        effect-descriptors (fx/sub-ctx context subs/effect-descriptors)

        ;; Get chain name for header
        chain-name (:name cue-chain)
        default-name (common/default-name col row)
        editing-name? (:editing-name? dialog-data false)]
    {:fx/type :v-box
     :spacing 0
     :style-class "dialog-content"
     :pref-width 1120
     :pref-height 760
     :children [;; Header with editable name (double-click to edit)
                {:fx/type :h-box
                 :alignment :center-left
                 :style-class "dialog-header"
                 :children [{:fx/type inline-edit/inline-edit-text
                             :value chain-name
                             :placeholder default-name
                             :editing? editing-name?
                             :on-start-edit {:event/type :ui/update-dialog-data
                                             :dialog-id :cue-chain-editor
                                             :editing-name? true}
                             :on-commit {:event/type :cue-chain/set-name
                                         :col col
                                         :row row}
                             :on-cancel {:event/type :ui/update-dialog-data
                                         :dialog-id :cue-chain-editor
                                         :editing-name? false}}]}
                ;; Main content area - 2 columns
                {:fx/type :h-box
                 :spacing 0
                 :v-box/vgrow :always
                 :children [;; Left column - cue chain list (top) + item effects (bottom)
                            {:fx/type :v-box
                             :style-class "cue-chain-left-column"
                             :children [;; Cue chain list (top)
                                        {:fx/type :v-box
                                         :pref-height 300
                                         :children [{:fx/type list/list-editor
                                                     :v-box/vgrow :always
                                                     :items (:items cue-chain)
                                                     :component-id [:cue-chain col row]
                                                     :item-id-key :preset-id
                                                     :item-registry-fn presets/presets-by-id
                                                     :fallback-label "Unknown Preset"
                                                     :get-item-label [cue-chain-item-label label-ctx]
                                                     :get-group-label [cue-chain-group-label label-ctx]
                                                     :get-context-menu [cue-chain-context-menu col row]
                                                     :on-change-event :chain/set-items
                                                     :on-change-params {:domain :cue-chains :entity-key [col row]}
                                                     :items-path [:chains :cue-chains [col row] :items]
                                                     :header-label "CUE CHAIN"
                                                     :empty-text "No presets\nAdd from bank →"}]}

                                        ;; Item effects list (bottom) - always present, shows placeholder if no selection
                                        {:fx/type :v-box
                                         :v-box/vgrow :always
                                         :style-class "dialog-separator"
                                         :children (if has-item?
                                                     [{:fx/type list/list-editor
                                                       :v-box/vgrow :always
                                                       :items item-effects
                                                       ;; Use stable component-id without the item to avoid state fragmentation
                                                       :component-id [:item-effects col row]
                                                       :item-id-key :effect-id
                                                       :item-registry-fn [get effect-descriptors]
                                                       :fallback-label "Unknown Effect"
                                                       ;; The item by its id, never its place: a change made before
                                                       ;; a reorder or a delete lands on its own item, or on none
                                                       ;; (follow-up N14)
                                                       :on-change-event :cue-chain/set-item-effects
                                                       :on-change-params {:col col :row row :item-id first-selected-id}
                                                       ;; Its effects, found where the item is when an input is handled
                                                       :items-path {:domain :cue-chains :entity-key [col row] :owner-id first-selected-id}
                                                       :header-label "ITEM EFFECTS"
                                                       :empty-text "No effects\nAdd from bank →"
                                                       :allow-groups? true}]
                                                     ;; Placeholder when no item selected
                                                     [{:fx/type :v-box
                                                       :v-box/vgrow :always
                                                       :style-class "dialog-placeholder"
                                                       :alignment :center
                                                       :children [{:fx/type :label
                                                                   :text "Select an item above\nto edit its effects"
                                                                   :style-class "dialog-placeholder-text"}]}])}]}

                            ;; Right column - preset config + effect bank + effect params
                            {:fx/type :v-box
                             :h-box/hgrow :always
                             :spacing 0
                             :children (filterv some?
                                                [;; Preset bank + params
                                                 {:fx/type middle-section
                                                  :col col :row row
                                                  :active-preset-tab active-preset-tab
                                                  :selected-item selected-item}

                                                 ;; Effect bank (when item selected)
                                                 (when has-item?
                                                   {:fx/type effect-bank-tabs
                                                    :col col :row row
                                                    :item-id first-selected-id
                                                    :active-effect-tab active-effect-tab})

                                                 ;; Effect parameter editor (when effect selected)
                                                 (when (and has-item? (seq selected-effect-ids))
                                                   {:fx/type item-effect-parameter-editor
                                                    :v-box/vgrow :always
                                                    :col col :row row
                                                    :item-path first-selected-path
                                                    :item selected-item
                                                    :selected-effect-ids selected-effect-ids
                                                    :dialog-data effect-dialog-data})])}]}

                ;; While the cue is too heavy for a laser (zones plan Z3)
                {:fx/type load-warning/cue-load-warning
                 :col col
                 :row row}

                ;; Footer with the cue's Target, its zone pattern, trigger mode, and close button;
                ;; nothing in it shrinks below its text
                {:fx/type :h-box
                 :alignment :center-left
                 :spacing 12
                 :style-class "dialog-footer"
                 :children [{:fx/type target-control/target-field
                             :id "target-chip-cue"
                             :from-id "target-from-cue"
                             :scope :cue
                             :own (:target cue-chain)
                             :inherited (targets/cue-fallback nil (fx/sub-ctx context subs/column-target col)
                                                              (fx/sub-ctx context subs/default-target) col)
                             :on-set {:event/type :cue-chain/set-target :col col :row row}
                             :tooltip cue-target-tooltip}
                            ;; The cue's zone pattern (zones plan Z5)
                            {:fx/type zone-pattern-editor/pattern-field
                             :id "pattern-chip-cue"
                             :col col
                             :row row}
                            {:fx/type common/trigger-mode-dropdown
                             :col col
                             :row row
                             :trigger-mode (:trigger-mode cue-chain)}
                            {:fx/type :button
                             :text "⚡ Triggers..."
                             :min-width :use-pref-size
                             :style-class "button-secondary"
                             :tooltip {:fx/type :tooltip
                                       :text "The keys, MIDI and OSC messages that play this cue"}
                             :on-action {:event/type :ui/open-dialog
                                         :dialog-id :trigger-editor
                                         :data {:col col
                                                :row row
                                                :cell-mode :cue}}}
                            {:fx/type :region
                             :h-box/hgrow :always}
                            {:fx/type :button
                             :text "Open in Timeline Editor"
                             :min-width :use-pref-size
                             :style-class "button-secondary"
                             :tooltip {:fx/type :tooltip
                                       :text "Make this a timeline cue, its presets clips on tracks (Undo turns it back)"}
                             :on-action (fn [_]
                                          (events/dispatch! {:event/type :ui/close-dialog
                                                             :dialog-id :cue-chain-editor})
                                          (events/dispatch! {:event/type :timeline-cue-editor/open
                                                             :col col
                                                             :row row}))}
                            {:fx/type :button
                             :text "Close"
                             :min-width :use-pref-size
                             :style-class "button-primary"
                             :on-action {:event/type :ui/close-dialog
                                         :dialog-id :cue-chain-editor}}]}]}))


;; Dialog Window


(defn cue-chain-editor-dialog
  "The cue chain editor dialog window."
  [{:keys [fx/context]}]
  (let [{:keys [col row]} (fx/sub-ctx context subs/dialog-data :cue-chain-editor)
        chain-name (:name (fx/sub-ctx context subs/cue-cell-chain col row))]
    {:fx/type common/dialog-stage
     :dialog-id :cue-chain-editor
     :title (common/window-title "Cue Chain Editor" chain-name col row)
     :min-width 960
     :min-height 640
     :content {:fx/type cue-chain-editor-content}}))
