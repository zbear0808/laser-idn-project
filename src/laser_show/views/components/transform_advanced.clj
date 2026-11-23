(ns laser-show.views.components.transform-advanced
  "Advanced settings shared by XY transforms in the show and projector editors.")

(defn control
  "Return the disclosure for this transform, or nil for a non-geometric effect.
   The option is per effect instance, independent of its animated parameters."
  [effect-def effect address]
  (when (:transforms-xy? effect-def)
    (let [clamp? (not (false? (:clamp-after? effect)))
          id (:id effect)
          suffix (if (keyword? id) (name id) (str id))]
      {:fx/type :titled-pane
       :fx/key [:transform-advanced id (:effect-path address)]
       :id (str "transform-advanced-" suffix)
       :style-class ["titled-pane" "transform-advanced"]
       :text "Advanced"
       :expanded false
       :animated false
       :content {:fx/type :check-box
                 :id (str "clamp-after-transform-" suffix)
                 :text "Clamp after this transform"
                 :selected clamp?
                 :tooltip {:fx/type :tooltip
                           :text (str "Limit X and Y to −1…1 here. Uncheck to let later transforms bring points "
                                      "back into range before the final clamp.")}
                 :on-action (assoc address :event/type :chain/set-clamp-after
                                   :effect-instance-id id :value (not clamp?))}})))
