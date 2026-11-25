(ns laser-show.views.whole-map-reads-test
  "What the Perform tab's meters and zone map row, and a Target chip, run
   for edits a show makes while they're on screen: a cell switched, an
   effect's or a preset's parameter dragged, a cue renamed (follow-up N9).
   Their views run for what they show only. The selectors under them that
   read a whole grid ran for each such edit (zones-in-use, the Map cycle
   selectors, the meters for a cue outside the vibe); they read it cell by
   cell now. Mounted with views.harness on the app's cache, the selectors'
   bodies counted around it."
  (:require [clojure.test :refer [deftest is]]
            ;; The effects the fixture uses, registered as the app's frame
            ;; service registers them
            [laser-show.animation.effects.intensity]
            [laser-show.animation.effects.shape]
            [laser-show.animation.effects.zone]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.templates :as templates]
            [laser-show.subs :as subs]
            [laser-show.views.components.slider-meters :as slider-meters]
            [laser-show.views.components.target-control :as target-control]
            [laser-show.views.components.zone-map-pads :as zone-map-pads]
            [laser-show.views.harness :as h]))

(def ^:private watched
  "The selectors under these views that read a whole grid, by key."
  {#'subs/zones-in-use :zones-in-use
   #'subs/map-cycle-running? :map-cycle-running?
   #'subs/map-cycle-state :map-cycle-state
   #'subs/vibe-meter :vibe-meter})

(def ^:private show
  "A starter show (8 cues, 2 vibes, the Master) with a cue drawing into zone
   A, so the zone map row shows, a plain effect cell on, and a Map cycle
   cell on."
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-vibes)
      (templates/apply-starter-effects)
      (assoc-in [:chains :cue-chains [2 0] :target] :zone/a)
      (assoc-in [:chains :effect-chains [1 0]] {:name "Size" :active? true
                                                :items [{:id :sc :effect-id :scale :enabled? true :params {:x-scale 1.0}}]})
      (assoc-in [:chains :effect-chains [4 0]] {:active? true :items [(assoc (zone-patterns/cycle-instance) :id :cy)]})))

(deftest live-views-and-their-selectors-for-unrelated-edits-test
  (let [runs (atom {})
        counted (into {} (map (fn [[v k]] (let [f @v] [v (fn [& args] (swap! runs update k (fnil inc 0)) (apply f args))])))
                      watched)
        root {:fx/type :v-box
              :children [{:fx/type slider-meters/meter-row}
                         {:fx/type zone-map-pads/map-pad-row}
                         {:fx/type target-control/target-chip
                          :id "probe-chip"
                          :scope :item
                          :on-set {:event/type :cue-chain/set-item-target :col 2 :row 0 :item-id :probe}}]}
        steps (with-redefs-fn counted
                #(h/each-render show state/cache-factory root
                                [;; [1 0], a plain cell: its switch, then an effect's parameter
                                 (fn [s] (update-in s [:chains :effect-chains [1 0] :active?] not))
                                 (fn [s] (assoc-in s [:chains :effect-chains [1 0] :items 0 :params :x-scale] 1.2))
                                 ;; [7 0], a cue of Lines while Shapes plays: a preset's parameter, its name
                                 (fn [s] (assoc-in s [:chains :cue-chains [7 0] :items 0 :params :length] 0.9))
                                 (fn [s] (assoc-in s [:chains :cue-chains [7 0] :name] "Lines end"))
                                 ;; the Map cycle's own cell switched off: the row shows it
                                 (fn [s] (update-in s [:chains :effect-chains [4 0] :active?] not))]
                                (fn [step]
                                  (let [selectors @runs]
                                    (reset! runs {})
                                    {:views (count (:ran step)) :selectors selectors}))))
        [_ switched dragged cue-dragged renamed cycle-off] steps]
    (is (every? zero? (map :views [switched dragged cue-dragged renamed]))
        "no view runs for them: what they show didn't change")
    (is (pos? (:views cycle-off)) "the zone map row shows the Map cycle off")
    (is (= [{:vibe-meter 2} {:vibe-meter 2} {} {}] (map :selectors [switched dragged cue-dragged renamed]))
        "nor does a selector under them that read a whole grid, but the meters for the effects grid, whose switches they read")
    (is (= {:map-cycle-running? 1 :map-cycle-state 1 :vibe-meter 2} (:selectors cycle-off)))))
