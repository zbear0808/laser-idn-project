(ns laser-show.views.components.target-control
  "One Target control for every place a target is set (zones plan Z4, 06
   §4.5, §7.1): an item, a group, a track or folder, a clip, the cue, a grid
   column, the show, a zone map's lane, a preview cell.

   menu-model is the menu as data (pure): Inherit (what the level would
   inherit, and from where), All, the zones with their lanes in the map in
   use, the groups, then Combine (several refs as one target) and Pin (the
   zones swapped for their lanes). menu-items makes it menu items, a click
   continuing an event template with the row's :target; target-chip is the
   chip that opens it, and target-field the chip with \"Target:\" before
   it and where an inherited target comes from after it."
  (:require [cljfx.api :as fx]
            [clj-font-awesome.core :as fa]
            [clj-font-awesome.fonts :as fa-fonts]
            [clojure.string :as str]
            [laser-show.routing.targets :as targets]
            [laser-show.subs :as subs]))


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


;; The menu as menu items

(defn- pin-graphic
  "FontAwesome's thumbtack, never an emoji (06 §7.1)."
  []
  {:fx/type :text
   :text (fa/unicode! :thumbtack :solid)
   :font (fa-fonts/font :solid 9)
   :fill "#B0B0B0"})

(defn- check [checked? text]
  (str (if checked? "✓ " "   ") text))

(defn menu-items
  "menu-model's rows as menu items; a click continues on-set (an event map)
   with the row's :target. Combine is a submenu of check boxes that stays
   open, so several refs can be ticked in a row."
  [rows on-set]
  (into []
        (keep (fn [{:keys [kind text checked? target audience? rows]}]
                (case kind
                  :header {:fx/type :menu-item :text text :disable true :style-class ["menu-item" "target-menu-header"]}
                  :separator {:fx/type :separator-menu-item}
                  :combine {:fx/type :menu
                            :text text
                            :items (mapv (fn [{:keys [text checked? target]}]
                                           {:fx/type :custom-menu-item
                                            :hide-on-click false
                                            :content {:fx/type :check-box
                                                      :text text
                                                      :selected (boolean checked?)
                                                      :on-action (assoc on-set :target target)}})
                                         rows)}
                  :pin {:fx/type :menu-item
                        :text text
                        :graphic (pin-graphic)
                        :on-action (assoc on-set :target target)}
                  :group (cond-> {:fx/type :menu-item
                                  :text (check checked? (cond-> text audience? (str " · audience")))
                                  :graphic (pin-graphic)
                                  :on-action (assoc on-set :target target)}
                           audience? (assoc :style-class ["menu-item" "target-menu-audience"]))
                  {:fx/type :menu-item
                   :text (check checked? text)
                   :on-action (assoc on-set :target target)})))
        rows))


;; The chip

(defn chip-text
  "What a chip says: the target the level ends up with (its own, else the
   inherited one, in brackets); compact? (the timeline's rows) just the
   zone letters or group names."
  [names zone-map own inherited compact?]
  (let [t (or (targets/normalize-target own) (:target inherited))
        text (if compact?
               (str/join " + " (map #(or (targets/zone-letter %) (targets/ref-name names %)) (targets/target-refs t)))
               (targets/target-text names zone-map t))
        text (if (str/blank? text) "All" text)]
    (if (some? (targets/normalize-target own)) text (str "(" text ")"))))

(defn target-chip
  "The Target chip (06 §4.5): a menu button shaped like the timeline's zone
   chip, saying what the target ends up as (dim, in brackets, when it is
   inherited), its menu menu-items of the scope's rows. Props: :id :scope
   :own (the level's own target) :inherited (what it would inherit, a
   resolution) :on-set (an event map; a choice adds :target) :tooltip
   :compact?."
  [{:keys [fx/context id scope own inherited on-set tooltip compact?]}]
  (let [names (fx/sub-ctx context subs/names-ctx)
        zone-map (fx/sub-ctx context subs/active-zone-map)
        rows (menu-model {:scope scope :own own :inherited inherited :names names :zone-map zone-map
                          :zones (fx/sub-ctx context subs/zones-in-use)
                          :audience (fx/sub-ctx context subs/vibe-audience-groups)})
        own (targets/normalize-target own)
        missing? (some :missing? rows)]
    (cond-> {:fx/type :menu-button
             :text (chip-text names zone-map own inherited compact?)
             :style-class (cond-> ["menu-button" "target-chip"]
                            (nil? own) (conj "target-chip-inherited")
                            missing? (conj "target-chip-missing"))
             :focus-traversable false
             :min-width :use-pref-size
             :items (menu-items rows on-set)}
      id (assoc :id id)
      tooltip (assoc :tooltip {:fx/type :tooltip :text tooltip}))))

(defn target-field
  "\"Target:\", the chip, then, dim, where an inherited target comes from
   (\"from column 3\"). Props: target-chip's, and :from-id."
  [{:keys [own inherited from-id] :as props}]
  (let [from (when (nil? (targets/normalize-target own)) (targets/from-text inherited))]
    {:fx/type :h-box
     :spacing 6
     :alignment :center-left
     :children (cond-> [{:fx/type :label :text "Target:" :min-width :use-pref-size :style-class ["label" "target-field-label"]}
                        (assoc props :fx/type target-chip)]
                 from (conj (cond-> {:fx/type :label :text from :min-width :use-pref-size :style-class ["label" "target-from"]}
                              from-id (assoc :id from-id))))}))
