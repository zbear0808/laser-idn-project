(ns laser-show.views.components.zone-chips
  "Reusable chip components for zone groups.
   
   Used in:
   - Projector zone group assignment (projectors tab)
   - Zone reroute effect editor (custom param renderers)")


(defn zone-group-chip
  "A chip showing a zone group with toggle functionality.
   
   Shows a colored circle indicator:
   - Solid circle when selected
   - Empty (outlined) circle when unselected
   
   Props:
   - :group - Zone group map with :id, :name, :color
   - :selected? - Whether this group is currently selected
   - :on-toggle - Event/handler to call when clicked"
  [{:keys [group selected? on-toggle]}]
  (let [{:keys [name color]} group]
    {:fx/type :h-box
     :spacing 4
     :alignment :center-left
     :padding {:left 6 :right 6 :top 3 :bottom 3}
     :style (str "-fx-background-color: " (if selected? (str color "80") "#404040")
                 "; -fx-background-radius: 12; -fx-border-color: " color
                 "; -fx-border-radius: 12; -fx-cursor: hand;")
     :on-mouse-clicked on-toggle
     :children [{:fx/type :circle
                 :radius 4
                 :fill (if selected? color "transparent")
                 :stroke color
                 :stroke-width 1.5}
                {:fx/type :label
                 :text name
                 :style (str "-fx-text-fill: " (if selected? "white" "#B0B0B0") "; -fx-font-size: 10;")}]}))
