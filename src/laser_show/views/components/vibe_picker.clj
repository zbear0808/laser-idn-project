(ns laser-show.views.components.vibe-picker
  "The Vibes tab's picker: the cue grid (HIT, ACCENT, the FLASH cue, the
   BED) or the effects grid (the FX set, the FLASH effects) for the vibe
   edited and its slot (subs/vibe-edit-target). A click puts a cell in the
   slot or takes it out (:vibe/toggle-cell, :vibe/toggle-fx). A cell in is
   blue, as a playing cue is on the grid, and its marks say its place in a
   pool, its other roles in the vibe, the other vibes it is in (a dot in
   each one's colour) and why the vibe never plays it.

   The cells are the grid's own (grid-cell/generic-grid-cell, laid out by
   grid-tab), with no menu and no drag; the marks lie on top and don't take
   the mouse, nor any room: a picker cell is as big as a grid cell."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.vibes.library :as library]
            [laser-show.views.components.grid-cell :as grid-cell]
            [laser-show.views.components.grid-tab :as grid-tab]))

(def ^:private role-names
  {:hit "HIT" :accent "ACCENT" :flash-cue "FLASH" :bed "BED" :fx "FX" :flash-fx "FLASH"})

(def ^:private blocked-text
  {:crowd "audience" :timecode "timecode"})

(defn- dot [color]
  {:fx/type :region
   :style-class "vibe-dot"
   :style (str "-fx-background-color: " color ";")
   :min-width 7 :min-height 7 :max-width 7 :max-height 7})

(defn- marks
  "What lies on top of a picker cell (props: :id and the cell's
   subs/vibe-pick-cell): its place in a pool (top left), a dot for each
   other vibe it is in (top right, three and +n), and its other roles in
   the vibe, or why the vibe never plays it (bottom)."
  [{:keys [id pos roles others blocked]}]
  {:fx/type :v-box
   :mouse-transparent true
   :pref-width 0
   :pref-height 0
   :padding 3
   :children [{:fx/type :h-box
               :alignment :center-left
               :children [{:fx/type :label
                           :id (str id "-pos")
                           :style-class "vibe-pick-pos"
                           :text (if pos (str pos) "")}
                          {:fx/type :region :h-box/hgrow :always}
                          {:fx/type :h-box
                           :spacing 2
                           :alignment :center-right
                           :children (cond-> (mapv dot (take 3 others))
                                       (> (count others) 3) (conj {:fx/type :label
                                                                   :style-class "vibe-pick-roles"
                                                                   :text (str "+" (- (count others) 3))}))}]}
              {:fx/type :region :v-box/vgrow :always}
              {:fx/type :label
               :id (str id "-roles")
               :style-class (if blocked "vibe-pick-blocked" "vibe-pick-roles")
               :text (if blocked
                       (blocked-text blocked)
                       (str/join " · " (map role-names roles)))}]})

(defn- pick-cell
  "A picker cell: the grid cell, in (blue) or out of the slot, and its marks.
   The grid cell has no drag configuration, so it is no drag source or
   target."
  [{:keys [col row size font-size id kind cell-type label-fn event label pick]}]
  {:fx/type :stack-pane
   :id id
   :style-class [kind (if (:in? pick) "vibe-pick-in" "vibe-pick-out")]
   :children [(grid-cell/generic-grid-cell {:col col
                                            :row row
                                            :size size
                                            :font-size font-size
                                            :cell-type cell-type
                                            :display-data (assoc label :active? (:in? pick))
                                            ;; A double click is two clicks, as everywhere on the picker
                                            :on-click event
                                            :on-right-click event
                                            :label-fn label-fn})
              (assoc pick :fx/type marks :id id)]})

(defn vibe-pick-cell
  "A cue cell of the picker (props: :col :row, and :size :font-size from
   the grid)."
  [{:keys [fx/context col row size font-size]}]
  (let [{:keys [vibe slot]} (fx/sub-ctx context subs/vibe-edit-target)]
    {:fx/type pick-cell
     :col col
     :row row
     :size size
     :font-size font-size
     :id (format "vibe-pick-%d-%d" col row)
     :kind "vibe-pick"
     :cell-type :pick
     :label-fn grid-cell/cell-label
     :event {:event/type :vibe/toggle-cell :vibe vibe :slot slot}
     :label (fx/sub-ctx context subs/vibe-pick-label col row)
     :pick (fx/sub-ctx context subs/vibe-pick-cell col row)}))

(defn fx-pick-cell
  "An effect cell of the picker (props: :col :row, and :size :font-size from
   the grid)."
  [{:keys [fx/context col row size font-size]}]
  (let [{:keys [vibe slot]} (fx/sub-ctx context subs/vibe-edit-target)]
    {:fx/type pick-cell
     :col col
     :row row
     :size size
     :font-size font-size
     :id (format "vibe-pick-fx-%d-%d" col row)
     :kind "vibe-pick-fx"
     :cell-type :pick-fx
     :label-fn grid-cell/effects-label
     :event {:event/type :vibe/toggle-fx :vibe vibe :slot slot}
     :label (fx/sub-ctx context subs/vibe-pick-fx-label col row)
     :pick (fx/sub-ctx context subs/vibe-pick-fx col row)}))

(def ^:private hints
  {:hit "Click cues to put them in HIT, or take them out. A press plays the next of them, never the same twice in a row."
   :accent "Click cues to put them in ACCENT, or take them out: bigger or rarer ones, layered over the hit."
   :flash-cue "Click the cue FLASH plays while it is held. One cue."
   :bed "Click the cue that plays all through this vibe, under the hits. One cue."
   :fx "Click effect cells that switch on with this vibe and off when it ends."
   :flash-fx "Click effect cells FLASH switches on while it is held."})

(def ^:private cell-size
  "The picker's cells keep a grid cell's first size: they share the tab with
   the editor, so they don't grow with the pane as the Grid tab's do."
  [80 60])

(def ^:private label-px 12)

(defn picker
  "The picker for the slot edited: the cue grid or the effects grid, with
   the grid's row letters and column numbers."
  [{:keys [fx/context]}]
  (let [{:keys [slot]} (fx/sub-ctx context subs/vibe-edit-target)]
    {:fx/type :v-box
     :id "vibe-picker"
     :spacing 4
     :children [{:fx/type :label
                 :id "vibe-picker-hint"
                 :style-class "label-secondary"
                 :wrap-text true
                 :text (hints slot "")}
                {:fx/type grid-tab/grid-header
                 :size cell-size
                 :font-size label-px}
                {:fx/type grid-tab/grid-with-labels
                 :cell-component (if (some #{slot} library/fx-slots) fx-pick-cell vibe-pick-cell)
                 :size cell-size
                 :font-size label-px}]}))
