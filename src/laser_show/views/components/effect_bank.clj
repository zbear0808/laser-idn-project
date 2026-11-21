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


(defn- offered?
  "Does a bank of scope (:item or :global) offer effect-def: one without a
   :scope is offered in both, a zone-stage effect (:scope #{:global}) only
   on the effects grid (zones plan Z5)."
  [scope effect-def]
  (let [s (:scope effect-def)]
    (or (nil? s) (contains? s scope))))

(defn- build-effects-by-category
  "Build a map of category -> effects vector, of the effects a bank of
   scope offers (offered?).
   Handles both single keyword categories and set categories.
   Effects with set categories appear in all relevant tabs. A :hidden?
   effect (the Zone Selector, zones plan Z4) is in none."
  [scope]
  (let [all-effects (filter #(offered? scope %) (remove :hidden? (effects/list-effects)))]
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


(def ^:private cached-effects-maps
  "{:count n :by-scope {scope effects-map}}: the maps, for n registered effects."
  (atom nil))

(defn- get-effects-by-category
  "Get scope's effects map, rebuilding if registry changed.
   This provides stable identity for the map when effects haven't changed."
  [scope]
  (let [current-count (count @effects/!effect-registry)
        cached @cached-effects-maps]
    (or (when (= (:count cached) current-count) (get-in cached [:by-scope scope]))
        (let [m (build-effects-by-category scope)]
          (swap! cached-effects-maps
                 (fn [c] (if (= current-count (:count c))
                           (assoc-in c [:by-scope scope] m)
                           {:count current-count :by-scope {scope m}})))
          m))))

(def ^:private zone-hint
  "The effects grid's Zone tab: the order zone patterns run in (07 §7.2)."
  "Zone patterns run after each cue's own pattern, cell by cell, row by row, left to right.")


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
   - :scope - :item (the default: a cue's items, the timeline's clips and
              tracks) or :global (the effects grid): the effects it offers
              (offered?); the Zone tab shows while it offers one, with its
              hint (zones plan Z5)
   - :fill? - Grow with the parent (default: fixed 80 px)
   - :drag-kind - :effect to make the buttons drag sources (timeline browser)"
  [{:keys [active-tab on-tab-change item-event-template scope fill? drag-kind]}]
  (let [effects-map (get-effects-by-category (or scope :item))
        zone? (seq (:zone effects-map))
        tabs (if zone?
               effect-bank-tab-definitions-with-zone
               effect-bank-tab-definitions)
        active-tab (or active-tab :shape)]
    {:fx/type tabbed-bank/tabbed-bank
     :tab-definitions tabs
     :hint (when (and zone? (= :zone active-tab)) zone-hint)
     :hint-id "effect-bank-hint"
     :active-tab active-tab
     :on-tab-change on-tab-change
     ;; Data-driven: pass pre-computed items map
     :items-by-category effects-map
     ;; Data-driven event template
     :item-event-template item-event-template
     :item-name-key :name
     :item-id-key :id
     :button-style-class "bank-item-btn"
     :empty-text "No effects"
     :fill? fill?
     :drag-kind drag-kind}))
