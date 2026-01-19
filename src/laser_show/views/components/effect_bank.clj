(ns laser-show.views.components.effect-bank
  "Effect bank component for chain editors.
   
   Displays available effects organized by category tabs (Shape, Color, Intensity).
   Each effect can be added to an effect chain with a single click.
   
   Uses the data-driven tabbed-bank component for the UI structure.
   
   IMPORTANT: This component builds effect category maps at render time since
   effects are registered dynamically. The map is computed once per render
   but the identity remains stable for unchanged effect lists."
  (:require [laser-show.animation.effects :as effects]
            [laser-show.views.components.tabbed-bank :as tabbed-bank]
            [laser-show.views.components.effect-param-ui :as effect-param-ui]))


;; Effect Categories (excludes :calibration which is projector-only)


(def effect-bank-tab-definitions
  "Tab definitions for the effect bank categories."
  [{:id :shape :label "Shape"}
   {:id :color :label "Color"}
   {:id :intensity :label "Intensity"}])

(def effect-bank-tab-definitions-with-zone
  "Tab definitions including zone effects."
  [{:id :shape :label "Shape"}
   {:id :color :label "Color"}
   {:id :intensity :label "Intensity"}
   {:id :zone :label "Zone"}])


;; Effect Category Map Builder


(defn- build-effects-by-category
  "Build a map of category -> effects vector.
   Handles both single keyword categories and set categories.
   Effects with set categories appear in all relevant tabs."
  []
  (let [all-effects (effects/list-effects)]
    (reduce (fn [acc effect-def]
              (let [cat (:category effect-def)
                    ;; Normalize to a set of categories
                    cats (if (set? cat) cat #{cat})
                    ;; Filter out :calibration (projector-only) from effect bank
                    display-cats (disj cats :calibration)]
                ;; Add effect to each non-calibration category it belongs to
                (reduce (fn [inner-acc display-cat]
                          (update inner-acc display-cat (fnil conj []) effect-def))
                        acc
                        display-cats)))
            {}
            all-effects)))


;; Memoized effects map - only rebuilds when registry changes


(def ^:private last-registry-count (atom 0))
(def ^:private cached-effects-map (atom nil))

(defn- get-effects-by-category
  "Get effects map, rebuilding if registry changed.
   This provides stable identity for the map when effects haven't changed."
  []
  (let [current-count (count @effects/!effect-registry)]
    (when (not= current-count @last-registry-count)
      (reset! cached-effects-map (build-effects-by-category))
      (reset! last-registry-count current-count))
    @cached-effects-map))


;; Helper to build default params for an effect


(defn- build-default-params
  "Build default params map from effect definition parameters."
  [effect-def]
  (let [params-map (effect-param-ui/params-vector->map (:parameters effect-def))]
    (into {}
          (for [[k v] params-map]
            [k (:default v)]))))


;; Effect Bank Component


(defn effect-bank
  "Tabbed effect bank showing available effects by category.
   
   Uses the data-driven tabbed-bank component with stable category data.
   
   Props:
   - :active-tab - Currently active category tab (default: :shape)
   - :on-tab-change - Event map for tab changes
   - :item-event-template - Event map template for adding effects
                           Will receive :item-id (effect-id) and :item (effect-def)
   - :include-zone? - Include Zone category (default: false)
   - :pref-height - Height of bank (default: 140)"
  [{:keys [active-tab on-tab-change item-event-template include-zone? pref-height]}]
  (let [effects-map (get-effects-by-category)
        tabs (if include-zone?
               effect-bank-tab-definitions-with-zone
               effect-bank-tab-definitions)]
    {:fx/type tabbed-bank/tabbed-bank
     :tab-definitions tabs
     :active-tab (or active-tab :shape)
     :on-tab-change on-tab-change
     ;; Data-driven: pass pre-computed items map
     :items-by-category effects-map
     ;; Data-driven event template
     :item-event-template item-event-template
     :item-name-key :name
     :item-id-key :id
     :button-style-class "bank-item-btn"
     :empty-text "No effects"
     :pref-height (or pref-height 140)}))
