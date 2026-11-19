(ns laser-show.views.components.vibe-strip
  "The Perform tab's vibe strip: one chip per vibe, in the order VIBE steps
   through them, in the vibe's colour with its energy, marked now (the current
   vibe) and next (where a VIBE tap goes). A click makes that vibe current
   (:vibe/select)."
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]))

(defn energy-dots
  "A vibe's energy, 1 to 4, as four dots: \"●●○○\"."
  [energy]
  (apply str (for [i (range 4)] (if (< i (or energy 0)) "●" "○"))))

(defn- chip [{:keys [id color energy current? next?] vibe-name :name}]
  {:fx/type :button
   :fx/key id
   :id (str "vibe-chip-" (name id))
   :focus-traversable false
   :style-class (cond-> ["vibe-chip"] current? (conj "vibe-chip-current"))
   :style (when color
            (str "-fx-border-color: " color ";"
                 (when current? (str " -fx-background-color: " color ";"))))
   :text (str vibe-name "  " (energy-dots energy) (cond current? "  · now" next? "  · next"))
   :on-action {:event/type :vibe/select :vibe id}})

(defn vibe-strip
  "The chips, one per vibe (subs/vibe-strip)."
  [{:keys [fx/context]}]
  {:fx/type :flow-pane
   :id "vibe-strip"
   :hgap 10
   :vgap 10
   :children (mapv chip (fx/sub-ctx context subs/vibe-strip))})
