(ns laser-show.views.components.target-control
  "One Target control for every place a target is set (zones plan Z4, 06
   §4.5, §7.1): an item, a group, a track or folder, a clip, the cue, a grid
   column, the show, a zone map's lane, a preview cell.

   menu-model is the menu as data (pure): Inherit (what the level would
   inherit, and from where), All, the zones with their lanes in the map in
   use, the groups, then Combine (several refs as one target) and Pin (the
   zones swapped for their lanes)."
  (:require [laser-show.routing.targets :as targets]))


;; The menu as data

(def ^:private every-row-scopes #{:item :group :track :clip :cue :column :show})

(defn- inherit-text [names zone-map inherited]
  (if-let [t (:target inherited)]
    (str "Inherit (" (targets/target-text names zone-map t)
         (when-let [from (targets/from-text inherited)] (str " · " from))
         ")")
    "Inherit"))

(defn- listed-zones
  "The zones the menu lists: A and B, every zone the show uses and the
   target names, in order (Z4-Q3 A); for a preview cell, the ones the show
   uses only."
  [scope zones own]
  (sort-by targets/zone-index
           (into (if (= :preview scope) #{} #{:zone/a :zone/b})
                 (filter targets/zone-ref?)
                 (concat zones (targets/target-refs own)))))

(defn- ordered-groups
  "names' groups in Z2's order (targets/names keeps target-groups' order),
   All left out."
  [names]
  (into [] (comp (keep #(get-in names [:groups %])) (remove #(= :all (:id %)))) (:order names)))

(defn menu-model
  "The Target menu's rows for ctx, as data: each {:kind :text :checked?
   :target} (:target is what a click sets), :kind one of :inherit :master
   :ref (All) :header :zone :group :separator :combine (its :rows, each a
   ref ticked or not, :target the own target with it toggled) :pin. ctx:
   {:scope :own (the level's own target; nil: it inherits) :inherited (the
   resolution it would inherit, for Inherit's text) :names (targets/names)
   :zone-map (the map in use) :zones (the zones the show uses) :audience
   (the refs that reach the crowd)}.
   Scopes: :item :group :track :clip :cue :column, every row; :show, no
   Inherit (nothing set is All); :lane, All and the groups only; :preview,
   Master (all content) for Inherit, no new zone, Combine or Pin."
  [{:keys [scope own inherited names zone-map zones audience]}]
  (let [own (targets/normalize-target own)
        checked? (fn [ref] (= own ref))
        lane? (= :lane scope)
        zone-list (listed-zones scope zones own)
        new-zone (targets/next-zone zone-list)
        zone-text #(targets/target-text names zone-map %)
        effective (or own (:target inherited))
        groups (for [g (ordered-groups names)]
                 {:kind :group :ref (:id g) :text (:name g) :checked? (checked? (:id g))
                  :audience? (contains? audience (:id g)) :target (:id g)})
        known (:groups names)
        missing (for [ref (targets/target-refs own)
                      :when (not (or (targets/zone-ref? ref) (= :all ref) (contains? known ref)))]
                  {:kind :group :ref ref :text (str "⚠ " (targets/ref-name names ref)) :missing? true
                   :checked? true :target ref})]
    (vec
     (concat
      (cond
        (contains? (disj every-row-scopes :show) scope)
        [{:kind :inherit :text (inherit-text names zone-map inherited) :checked? (nil? own) :target nil}]
        (= :preview scope)
        [{:kind :master :text "Master (all content)" :checked? (nil? own) :target nil}])
      [{:kind :ref :ref :all :text "All" :target :all
        :checked? (if (= :show scope) (or (nil? own) (= :all own)) (checked? :all))}]
      (when-not lane?
        (concat
         [{:kind :header :text (str "ZONES · map: " (:name zone-map))}]
         (for [z zone-list]
           {:kind :zone :ref z :text (zone-text z) :checked? (checked? z) :new? false :target z})
         (when-not (= :preview scope)
           [{:kind :zone :ref new-zone :text (str "New zone (" (zone-text new-zone) ")") :checked? false
             :new? true :target new-zone}])))
      [{:kind :header :text "GROUPS"}]
      missing
      groups
      (when (contains? every-row-scopes scope)
        (concat
         [{:kind :separator}
          {:kind :combine :text "Combine"
           :rows (vec (for [ref (concat zone-list (map :id (ordered-groups names)))]
                        {:ref ref
                         :text (if (targets/zone-ref? ref) (zone-text ref) (targets/ref-name names ref))
                         :checked? (boolean (some #{ref} (targets/target-refs own)))
                         :target (targets/toggle own ref)}))}]
         (when (targets/has-zone? effective)
           [{:kind :pin :text (str "Pin: " (targets/target-text (assoc names :labels {}) zone-map effective))
             :target (targets/pin zone-map effective)}])))))))
