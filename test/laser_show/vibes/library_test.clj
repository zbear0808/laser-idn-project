(ns laser-show.vibes.library-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.state.domains :as domains]
            [laser-show.state.templates :as templates]
            [laser-show.vibes.engine :as engine]
            [laser-show.vibes.library :as library]
            [laser-show.vibes.reactions :as reactions]))

(def ^:private shape {:items [{:type :preset :preset-id :circle}]
                      :destination-zone {:zone-group-id :all}})

(def ^:private chasing
  {:editor-type :timeline :timeline/chase :mtc
   :items [{:type :preset :preset-id :circle :timeline/start 0.0 :timeline/duration 4.0}]})

(defn- starter []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-vibes)))

(deftest playable-cells-skips-empty-and-chasing-test
  (let [state (-> (starter)
                  (assoc-in [:chains :cue-chains [1 0]] {:items []})
                  (update-in [:chains :cue-chains] dissoc [2 0])
                  (assoc-in [:chains :cue-chains [3 0]] chasing))
        vibe (library/vibe state :vibe-1)]
    (is (= [[0 0]] (library/playable-cells state vibe [[0 0] [1 0] [2 0] [3 0]]))
        "no items, no cue, and a timeline following MIDI timecode are left out")
    (is (= [[0 0] [4 0]] (library/playable-cells state vibe [[0 0] [4 0] [0 0]])) "in order, each once")
    (is (= [] (library/playable-cells state vibe nil)))))

(deftest current-vibe-falls-back-to-first-test
  (let [state (starter)]
    (is (= :vibe-1 (library/current-id state)) "nothing current yet: the first of :order")
    (is (= :vibe-2 (library/current-id (assoc-in state [:vibe-runtime :current] :vibe-2))))
    (is (= :vibe-1 (library/current-id (assoc-in state [:vibe-runtime :current] :gone)))
        "a vibe that was undone or deleted: the first")
    (is (= "Shapes" (:name (library/current-vibe (assoc-in state [:vibe-runtime :current] :gone)))))
    (is (nil? (library/current-id (assoc-in state [:config :vibes] library/empty-library))))
    (testing "next wraps"
      (is (= :vibe-2 (library/next-id state :vibe-1)))
      (is (= :vibe-1 (library/next-id state :vibe-2)))
      (is (= :vibe-1 (library/next-id state :gone))))
    (testing "settings missing from a file get their defaults"
      (is (= 150 (:quick-tap-ms (library/settings (assoc-in state [:config :vibes :settings] {:lock-hold-ms 500})))))
      (is (= 500 (:lock-hold-ms (library/settings (assoc-in state [:config :vibes :settings] {:lock-hold-ms 500}))))))))

(defn- with-projector [state id projector]
  (assoc-in state [:projectors id] (merge {:name (name id) :host "10.0.0.3" :zone-groups [:all] :tags #{}}
                                          projector)))

(deftest audience-cell-test
  (let [state (assoc-in (starter) [:chains :cue-chains [0 1]] shape)
        cell? #(library/audience-cell? %1 [0 1])]
    (testing "destination :all, no item rerouted, no projector tagged"
      (is (false? (cell? (with-projector state :p3 {:zone-groups [:front] :exclude-from-all? true})))))
    (testing "destination :crowd"
      (is (true? (cell? (assoc-in state [:chains :cue-chains [0 1] :target] :crowd)))))
    (testing "an item's target names a group a crowd-scanning projector is in"
      (let [rerouted (assoc-in state [:chains :cue-chains [0 1] :items 0 :target] :front)]
        (is (true? (cell? (with-projector rerouted :p3 {:zone-groups [:front] :tags #{:crowd-scanning}
                                                        :exclude-from-all? true}))))
        (is (false? (cell? (with-projector rerouted :p3 {:zone-groups [:front] :exclude-from-all? true}))) "untagged")))
    (testing "destination :all, and the crowd-scanning projector is in :all: it does reach the crowd"
      (is (true? (cell? (with-projector state :p3 {:zone-groups [:all] :tags #{:crowd-scanning}})))))
    (testing "a cue with no target routes to :all, so it reaches it too"
      (let [unset (assoc-in state [:chains :cue-chains [0 1] :target] nil)]
        (is (true? (cell? (with-projector unset :p3 {:zone-groups [:all] :tags #{:crowd-scanning}}))))
        (is (false? (cell? unset)) "no crowd-scanning projector")
        (is (true? (cell? (-> (update-in state [:chains :cue-chains [0 1]] dissoc :target)
                              (with-projector :p3 {:zone-groups [:all] :tags #{:crowd-scanning}}))))
            "no target at all")))
    (testing "an item's, a track's or a folder's own target, inside groups and folders: where its content goes (zones plan Z4)"
      (let [on-track (fn [tracks] (-> state
                                      (assoc-in [:chains :cue-chains [0 1] :tracks] tracks)
                                      (assoc-in [:chains :cue-chains [0 1] :items 0 :track-id] :t)))]
        (is (true? (cell? (assoc-in state [:chains :cue-chains [0 1] :items]
                                    [{:type :group :items [{:type :preset :target :crowd}]}]))))
        (is (true? (cell? (on-track [{:type :group :items [{:type :track :id :t :target :crowd}]}]))))
        (is (true? (cell? (on-track [{:type :group :id :f :target [:crowd :graphics] :items [{:type :track :id :t}]}])))
            "a union's every ref")
        (is (false? (cell? (assoc-in state [:chains :cue-chains [0 1] :tracks]
                                     [{:type :group :items [{:type :track :id :t :target :crowd}]}])))
            "a track with no clip on it sends nothing there")
        (is (false? (cell? (-> state
                               (assoc-in [:chains :cue-chains [0 1] :target] :crowd)
                               (assoc-in [:chains :cue-chains [0 1] :items 0 :target] :graphics))))
            "the cue's own target, which every item overrides, sends nothing there")))
    (testing "a virtual projector tagged, or whose projector is"
      (is (true? (cell? (assoc-in state [:virtual-projectors :v1]
                                  {:parent-projector-id :p9 :zone-groups [:all] :tags #{:crowd-scanning}}))))
      (is (true? (cell? (-> (with-projector state :p9 {:zone-groups [:left] :tags #{:crowd-scanning}})
                            (assoc-in [:virtual-projectors :v1] {:parent-projector-id :p9 :zone-groups [:all]}))))))
    (is (= #{:crowd :front :auto.laser/p3}
           (library/audience-groups (with-projector state :p3 {:zone-groups [:front] :tags [:crowd-scanning]
                                                               :exclude-from-all? true})))
        "its groups and its own; not All, nor the automatic groups, which it is out of")))

(deftest crowd-laser-in-all-makes-all-audience-test
  (let [state (-> (starter)
                  (with-projector :aud {:host "10.0.0.1" :zone-groups [] :tags #{:crowd-scanning}})
                  (assoc-in [:chains :cue-chains [0 1]] shape))]
    (is (contains? (library/audience-groups state) :all) "with nothing ticked, it plays All (D-20)")
    (is (true? (library/audience-cell? state [0 1])) "so every cue on All is an audience cue")))

(deftest excluded-crowd-laser-leaves-all-alone-test
  (let [state (-> (starter)
                  (with-projector :aud {:host "10.0.0.1" :zone-groups [:crowd] :tags #{:crowd-scanning}
                                        :exclude-from-all? true})
                  (with-projector :main {:host "10.0.0.2" :zone-groups []})
                  (assoc-in [:chains :cue-chains [0 1]] shape))]
    (is (= #{:crowd :auto.laser/aud} (library/audience-groups state)))
    (is (false? (library/audience-cell? state [0 1])) "a cue on All isn't an audience cue")
    (is (true? (library/audience-cell? (assoc-in state [:chains :cue-chains [0 1] :target]
                                                 :auto.laser/aud)
                                       [0 1]))
        "one aimed at the scanner itself is")))

(deftest crowd-laser-at-the-left-end-makes-left-audience-test
  (let [state (-> (starter)
                  (with-projector :aud {:host "10.0.0.1" :zone-groups [] :tags #{:crowd-scanning}})
                  (with-projector :main {:host "10.0.0.2" :zone-groups []}))]
    (is (contains? (library/audience-groups state) :auto/left))
    (is (not (contains? (library/audience-groups state) :auto/right)))
    (testing "moved to the right end, Right reaches the audience instead"
      (let [moved (assoc-in state [:rig :order] [:main :aud])]
        (is (contains? (library/audience-groups moved) :auto/right))
        (is (not (contains? (library/audience-groups moved) :auto/left)))))))

(deftest audience-cells-never-play-unless-allowed-test
  (let [state (-> (starter)
                  (assoc-in [:chains :cue-chains [1 0] :target] :crowd)
                  (assoc-in [:chains :cue-chains [3 0] :target] :crowd)
                  (assoc-in [:config :vibes :settings :hit-taps-tempo?] false))
        hits (fn [state]
               (loop [s state t 1000 acc #{}]
                 (if (> t 41000)
                   acc
                   (let [s (:state (engine/press s {:role :hit :time t}))
                         acc (conj acc (get-in s [:vibe-runtime :voices :hit :cell]))]
                     (recur (:state (engine/release s {:role :hit :time (+ t 50)})) (+ t 500) acc)))))]
    (is (= [[0 0] [2 0]] (library/playable-cells state (library/vibe state :vibe-1) [[0 0] [1 0] [2 0] [3 0]])))
    (is (= #{[0 0] [2 0]} (hits state)) "81 presses never pick the two crowd cues")
    (is (= #{[0 0] [1 0] [2 0] [3 0]} (hits (assoc-in state [:config :vibes :library :vibe-1 :allow-audience?] true)))
        "unless the vibe allows them")))


;; Zones and the audience (zones plan Z4, 06 §8.6)

(defn- sweep-rig
  "p1-p4, and p5 scanning the crowd, out of All (and so of every automatic
   group, D-33) and alone in Crowd Scanning; the map Crowd sweep: Left and
   Crowd, with C on Center."
  []
  (-> (reduce (fn [s [id n]] (with-projector s id {:host (str "10.0.0." n) :zone-groups []}))
              (starter)
              [[:p1 1] [:p2 2] [:p3 3] [:p4 4]])
      (with-projector :p5 {:host "10.0.0.5" :zone-groups [:crowd] :tags #{:crowd-scanning} :exclude-from-all? true})
      (assoc-in [:zone-maps :map/custom-1] {:name "Crowd sweep" :lanes [:auto/left :crowd] :overrides {:zone/c :auto/center}})))

(defn- on
  "state with the cue at [0 1] drawing to t, or, at cell, a cue doing so."
  ([state t] (on state [0 1] t))
  ([state cell t] (assoc-in state [:chains :cue-chains cell] {:items [{:type :preset :preset-id :circle :target t}]})))

(deftest zone-in-any-map-reaches-the-crowd-test
  (let [s (sweep-rig)
        cell? #(library/audience-cell? % [0 1])]
    (is (false? (cell? (on s :zone/a))) "A: Left, Odd, All (p5 is out of it) or Left")
    (is (true? (cell? (on s :zone/b))) "B: Crowd sweep sends it to Crowd Scanning")
    (is (true? (cell? (on s :auto.laser/p5))) "pinned to the scanner")
    (is (false? (cell? (on (update s :zone-maps dissoc :map/custom-1) :zone/b))) "once Crowd sweep goes, no map sends B there")
    (testing "every map counts, not the one in use: a switch can come while the cue plays"
      (is (true? (cell? (assoc-in (on s :zone/b) [:zone-map :active] :map/odd-even)))))
    (testing "its column's default and the show's count"
      (let [inherits (assoc-in s [:chains :cue-chains [0 1]] {:items [{:type :preset :preset-id :circle}]})]
        (is (true? (cell? (assoc-in inherits [:grid :column-targets 0] :zone/b))))
        (is (true? (cell? (assoc-in inherits [:config :default-target] :zone/b))))
        (is (false? (cell? (assoc-in inherits [:grid :column-targets 1] :zone/b))) "another column's doesn't")))
    (testing "a vibe leaves it out of its pool unless it allows the audience"
      (let [state (on s :zone/b)
            vibe (library/vibe state :vibe-1)]
        (is (= [] (library/playable-cells state vibe [[0 1]])))
        (is (= [[0 1]] (library/playable-cells state (assoc vibe :allow-audience? true) [[0 1]])))))))

(deftest override-counts-test
  (let [s (sweep-rig)
        cell? #(library/audience-cell? % [0 1])]
    (is (false? (cell? (on s :zone/c))) "C would wrap to Crowd's lane? no: to Left; and its override sends it to Center")
    (is (true? (cell? (on (assoc-in s [:zone-maps :map/custom-1 :overrides :zone/c] :crowd) :zone/c))) "an override to Crowd")
    (is (true? (cell? (on (assoc-in s [:zone-maps :map/custom-1 :overrides :zone/a] :crowd) :zone/a))))))

(deftest unison-counts-when-all-reaches-the-crowd-test
  (let [s (assoc-in (sweep-rig) [:projectors :p5 :exclude-from-all?] false)
        cell? #(library/audience-cell? % [0 1])]
    (is (true? (cell? (on s :all))) "All reaches p5")
    (is (true? (cell? (on s :zone/a))) "and so every zone does, through Unison")
    (is (false? (cell? (on (sweep-rig) :zone/a))) "p5 out of All: no map sends A to it")))

(deftest lint-names-the-zone-and-its-map-test
  (let [s (on (sweep-rig) [1 0] :zone/b)
        text "Shapes: A2 reaches the audience through Zone B (the Crowd sweep map sends it to Crowd Scanning), so this vibe never plays it. Allow audience cells, or take it out"]
    (is (some #(= {:level :warn :vibe :vibe-1 :text text} %) (library/lint s)))
    (is (not-any? #(= text (:text %)) (library/lint (assoc-in s [:config :vibes :library :vibe-1 :allow-audience?] true)))
        "unless the vibe allows the audience")))


;; Editing the vibes (Phase 3)

(defn- vibes [state] (get-in state [:config :vibes]))

(deftest add-vibe-makes-a-unique-id-test
  (let [v0 (vibes (starter))
        v1 (library/add-vibe v0)]
    (is (= [:vibe-1 :vibe-2 :vibe-3] (:order v1)) "last in the order")
    (is (= 4 (:next-id v1)))
    (is (= (library/new-vibe :vibe-3 3 "Vibe 3") (get-in v1 [:library :vibe-3])))
    (is (= {:name "Vibe 3" :color "#E67E22" :energy 2 :allow-audience? false}
           (select-keys (get-in v1 [:library :vibe-3]) [:name :color :energy :allow-audience?]))
        "the palette's third colour, as the starter vibes have the first two")
    (is (= (dissoc v0 :order :next-id :library) (dissoc v1 :order :next-id :library)) "the settings stay")
    (testing "an id a hand-edited file already uses is skipped"
      (let [v (-> v0 (assoc-in [:library :vibe-3] {:id :vibe-3}) (update :order conj :vibe-3))]
        (is (= :vibe-4 (peek (:order (library/add-vibe v)))))))
    (testing "a library without :next-id starts at 1"
      (is (= [:vibe-1] (:order (library/add-vibe (dissoc library/empty-library :next-id))))))))

(deftest ids-come-from-next-id-test
  (let [v (-> (vibes (starter)) library/add-vibe (library/remove-vibe :vibe-3) library/add-vibe)]
    (is (= [:vibe-1 :vibe-2 :vibe-4] (:order v)) "deleting the newest vibe and adding one: a new id")
    (is (nil? (get-in v [:library :vibe-3])))
    (is (= 5 (:next-id v)))))

(deftest remove-vibe-drops-it-from-order-test
  (let [v0 (vibes (starter))
        v1 (library/remove-vibe v0 :vibe-1)]
    (is (= [:vibe-2] (:order v1)))
    (is (= #{:vibe-2} (set (keys (:library v1)))))
    (is (= 3 (:next-id v1)) ":next-id stays")
    (is (= v0 (library/remove-vibe v0 :gone)))
    (is (= [:vibe-1] (:order (library/remove-vibe (update v0 :library dissoc :vibe-2) :vibe-2)))
        "an id in :order whose vibe is gone goes too")))

(deftest duplicate-vibe-test
  (let [v0 (library/add-vibe (vibes (starter)))
        v1 (library/duplicate-vibe v0 :vibe-1)]
    (is (= [:vibe-1 :vibe-4 :vibe-2 :vibe-3] (:order v1)) "right after the original")
    (is (= (assoc (get-in v0 [:library :vibe-1]) :id :vibe-4 :name "Shapes copy") (get-in v1 [:library :vibe-4])))
    (is (= v0 (library/duplicate-vibe v0 :gone)))))

(deftest reorder-test
  (let [v0 (library/add-vibe (vibes (starter)))
        order #(:order (library/move-vibe v0 %1 %2))]
    (is (= [:vibe-2 :vibe-1 :vibe-3] (order :vibe-1 1)))
    (is (= [:vibe-3 :vibe-1 :vibe-2] (order :vibe-3 0)))
    (is (= [:vibe-2 :vibe-3 :vibe-1] (order :vibe-1 99)) "clamped")
    (is (= [:vibe-3 :vibe-1 :vibe-2] (order :vibe-3 -1)))
    (is (= (:order v0) (order :gone 0)))
    (is (= (:order v0) (order :vibe-1 nil)))))

(deftest toggle-cell-test
  (let [v0 (library/add-vibe (vibes (starter)))
        in (fn [v slot] (library/slot-cells (get-in v [:library :vibe-3]) slot))
        clicks (fn [slot & cells] (reduce #(library/toggle-cell %1 :vibe-3 slot %2) v0 cells))]
    (doseq [slot [:hit :accent]]
      (testing (str slot ": a click adds at the end, so the pool's order is the clicks'; a click on a cell in it takes it out")
        (is (= [[5 0] [2 0] [7 0]] (in (clicks slot [5 0] [2 0] [7 0]) slot)))
        (is (= [[5 0] [7 0]] (in (clicks slot [5 0] [2 0] [7 0] [2 0]) slot)))))
    (doseq [slot [:flash-cue :bed]]
      (testing (str slot ": one cell, replaced, and cleared by a click on it")
        (is (= [[5 0]] (in (clicks slot [5 0]) slot)))
        (is (= [[2 0]] (in (clicks slot [5 0] [2 0]) slot)))
        (is (= [] (in (clicks slot [5 0] [5 0]) slot)))))
    (is (nil? (get-in (clicks :flash-cue [5 0] [5 0]) [:library :vibe-3 :flash :cue])))
    (is (= {:cue nil :fx [] :solo? true} (get-in (library/toggle-cell v0 :vibe-1 :flash-cue [6 0]) [:library :vibe-1 :flash]))
        "a click on the FLASH cue clears the cue only")
    (doseq [slot [:fx :flash-fx]]
      (testing (str slot ": effect cells, added at the end or taken out")
        (let [v (reduce #(library/toggle-fx %1 :vibe-3 slot %2) v0 [[0 3] [1 3] [0 3] [2 3]])]
          (is (= [[1 3] [2 3]] (in v slot))))))
    (testing "a cell may be in several roles and several vibes"
      (let [v (-> v0 (library/toggle-cell :vibe-1 :accent [0 0]) (library/toggle-cell :vibe-3 :hit [0 0]))]
        (is (library/in-slot? v :vibe-1 :hit [0 0]))
        (is (library/in-slot? v :vibe-1 :accent [0 0]))
        (is (library/in-slot? v :vibe-3 :hit [0 0]))))
    (testing "an unknown vibe, a slot of the other grid, or no cell change nothing"
      (is (= v0 (library/toggle-cell v0 :gone :hit [0 0])))
      (is (= v0 (library/toggle-cell v0 :vibe-3 :fx [0 0])))
      (is (= v0 (library/toggle-fx v0 :vibe-3 :hit [0 3])))
      (is (= v0 (library/toggle-cell v0 :vibe-3 :hit nil))))))

(deftest set-single-replaces-test
  (let [v0 (vibes (starter))]
    (is (= [1 1] (get-in (library/set-single v0 :vibe-1 :flash-cue [1 1]) [:library :vibe-1 :flash :cue])))
    (is (= [1 1] (get-in (library/set-single v0 :vibe-1 :bed [1 1]) [:library :vibe-1 :bed])))
    (is (nil? (get-in (library/set-single v0 :vibe-1 :flash-cue nil) [:library :vibe-1 :flash :cue])) "nil clears it")
    (is (= v0 (library/set-single v0 :vibe-1 :hit [1 1])) "a pool isn't one cell")
    (is (= v0 (library/set-single v0 :gone :bed [1 1])))))

(deftest editing-a-vibe-takes-only-good-values-test
  (let [v0 (vibes (starter))
        vibe-1 #(get-in % [:library :vibe-1])]
    (is (= "Groove" (:name (vibe-1 (library/rename-vibe v0 :vibe-1 "  Groove ")))) "trimmed")
    (is (= v0 (library/rename-vibe v0 :vibe-1 "   ")) "a blank name changes nothing")
    (is (= "#123ABC" (:color (vibe-1 (library/set-color v0 :vibe-1 "#123ABC")))))
    (is (= v0 (library/set-color v0 :vibe-1 "blue")))
    (is (= 4 (:energy (vibe-1 (library/set-energy v0 :vibe-1 4)))))
    (is (= v0 (library/set-energy v0 :vibe-1 5)))
    (is (= v0 (library/set-energy v0 :vibe-1 0)))
    (testing "options"
      (is (= :gate (get-in (vibe-1 (library/set-role-option v0 :vibe-1 :hit :mode :gate)) [:hit :mode])))
      (is (= :sequence (get-in (vibe-1 (library/set-role-option v0 :vibe-1 :accent :select :sequence)) [:accent :select])))
      (is (= 0.5 (get-in (vibe-1 (library/set-role-option v0 :vibe-1 :hit :beats 1/2)) [:hit :beats])))
      (is (false? (get-in (vibe-1 (library/set-role-option v0 :vibe-1 :flash :solo? false)) [:flash :solo?])))
      (is (true? (:allow-audience? (vibe-1 (library/set-role-option v0 :vibe-1 nil :allow-audience? true)))))
      (doseq [[role k v] [[:hit :mode :loud] [:hit :beats 0] [:hit :beats "2"] [:flash :mode :gate]
                          [:flash :solo? nil] [nil :allow-audience? "yes"] [:bed :mode :decay]]]
        (is (= v0 (library/set-role-option v0 :vibe-1 role k v)) (pr-str [role k v]))))
    (is (= v0 (library/rename-vibe v0 :gone "Groove")))))

(deftest make-from-rows-test
  (let [cue {:items [{:type :preset :preset-id :circle}]}
        chains (merge (into {} (for [col (range 8)] [[col 0] cue]))
                      (into {} (for [col (range 3)] [[col 1] cue]))
                      {[5 2] {:items []}})
        v0 (vibes (starter))
        v1 (library/make-from-rows v0 chains [8 4])]
    (is (= [:vibe-1 :vibe-2 :vibe-3 :vibe-4] (:order v1)))
    (is (= 5 (:next-id v1)))
    (is (= (assoc-in (library/new-vibe :vibe-3 3 "Row A") [:hit :cells] (vec (for [col (range 8)] [col 0])))
           (get-in v1 [:library :vibe-3])))
    (is (= {:id :vibe-3 :name "Row A" :color "#E67E22" :energy 2
            :hit {:cells (vec (for [col (range 8)] [col 0])) :select :shuffle :mode :decay :beats 1.0}}
           (select-keys (get-in v1 [:library :vibe-3]) [:id :name :color :energy :hit]))
        "the data example of 03 §7")
    (is (= ["Row B" [[0 1] [1 1] [2 1]]] ((juxt :name (comp :cells :hit)) (get-in v1 [:library :vibe-4]))))
    (is (= (:library v0) (select-keys (:library v1) [:vibe-1 :vibe-2])) "invariant 4: no vibe changes")
    (is (= v1 (library/make-from-rows v1 chains [8 4])) "idempotent: the rows are pools already")
    (testing "a row whose cues are a vibe's HIT pool already, in any order, is left out"
      (let [v (assoc-in v0 [:library :vibe-2 :hit :cells] [[2 1] [0 1] [1 1]])]
        (is (= ["Row A"] (map #(get-in % [:name]) (vals (apply dissoc (:library (library/make-from-rows v chains [8 4]))
                                                               [:vibe-1 :vibe-2])))))))
    (is (= v0 (library/make-from-rows v0 {} [8 4])) "an empty grid makes nothing")))


;; Lint (03 §7): one assertion per row

(defn- lint-texts [state] (mapv (juxt :level :text) (library/lint state)))

(defn- has-row? [state level text]
  (some #{[level text]} (lint-texts state)))

(defn- learned-all [state]
  (assoc-in state [:config :input :trigger-map]
            (into {} (map-indexed (fn [i role] [{:source :midi :channel 0 :note (+ 36 i)} {:type :vibe :role role}])
                                  library/roles))))

(deftest lint-test
  (let [s0 (learned-all (starter))
        with-vibe (fn [state id m] (update-in state [:config :vibes :library id] merge m))]
    (is (= [] (library/lint s0)) "the starter show with its five controls: nothing to fix")
    (testing "error: HIT has no playable cell, and no tap reaction"
      (is (has-row? (with-vibe s0 :vibe-1 {:hit {:cells []}}) :error "Shapes: HIT does nothing (no cues, no tap reactions)")))
    (testing "error: a pool cell with no cue, or one that follows timecode"
      (is (has-row? (update-in s0 [:chains :cue-chains] dissoc [2 0]) :error "Shapes: A3 can't be played (empty)"))
      (is (has-row? (assoc-in s0 [:chains :cue-chains [2 0]] chasing) :error "Shapes: A3 can't be played (follows timecode)")))
    (testing "error: the flash cue can't be played"
      (is (has-row? (update-in s0 [:chains :cue-chains] dissoc [6 0]) :error "Shapes: FLASH cue A7 can't be played (empty)")))
    (testing "warn: neither a flash cue nor flash effects"
      (is (has-row? (with-vibe s0 :vibe-1 {:flash {:cue nil :fx [] :solo? true}}) :warn "Shapes: FLASH does nothing in this vibe"))
      (is (not (has-row? (with-vibe s0 :vibe-1 {:flash {:cue nil :fx [[0 3]] :solo? true}})
                         :warn "Shapes: FLASH does nothing in this vibe"))
          "flash effects are something"))
    (testing "error: :order and the library disagree"
      (is (has-row? (update-in s0 [:config :vibes :order] conj :gone) :error "Order and library disagree"))
      (is (has-row? (update-in s0 [:config :vibes :order] pop) :error "Order and library disagree")))
    (testing "warn: a HIT pool of one"
      (is (has-row? (with-vibe s0 :vibe-2 {:hit {:cells [[5 0]]}}) :warn "Lines: one cue in HIT repeats on every press")))
    (testing "warn: an audience cell in a vibe that doesn't allow them, naming the zone group and the projector"
      (let [crowd (assoc-in s0 [:chains :cue-chains [1 0] :target] :crowd)
            text "Shapes: A2 reaches the audience through Crowd Scanning, so this vibe never plays it. Allow audience cells, or take it out"]
        (is (has-row? crowd :warn text))
        (is (not (has-row? (with-vibe crowd :vibe-1 {:allow-audience? true}) :warn text)) "unless it allows them"))
      (is (has-row? (assoc-in s0 [:projectors :p3] {:name "P3" :zone-groups [:all] :tags #{:crowd-scanning}})
                    :warn "Lines: A6 reaches the audience through All (P3), so this vibe never plays it. Allow audience cells, or take it out")))
    (testing "warn: the same cell in HIT and ACCENT"
      (is (has-row? (with-vibe s0 :vibe-1 {:accent {:cells [[1 0]]}}) :warn "Shapes: A2 is in both HIT and ACCENT")))
    (testing "warn: two vibes with the same name"
      (is (has-row? (with-vibe s0 :vibe-2 {:name "Shapes"}) :warn "Two vibes are called Shapes")))
    (testing "info: fewer than five controls learned"
      (is (= [[:info "0 of 5 controls learned"]] (lint-texts (starter))))
      (is (has-row? (update-in s0 [:config :input :trigger-map] dissoc {:source :midi :channel 0 :note 36})
                    :info "4 of 5 controls learned")))
    (testing "worst first, and each row says which vibe"
      (let [rows (library/lint (-> (starter) (with-vibe :vibe-2 {:hit {:cells []}}) (with-vibe :vibe-1 {:hit {:cells [[0 0]]}})))]
        (is (= [:error :warn :info] (mapv :level rows)))
        (is (= [:vibe-2 :vibe-1 nil] (mapv :vibe rows)))))))

(deftest lint-names-the-automatic-group-and-its-scanner-test
  (let [state (-> (learned-all (starter))
                  (assoc-in [:projectors :aud] {:name "Aud" :host "10.0.0.1" :zone-groups [] :tags #{:crowd-scanning}})
                  (assoc-in [:projectors :main] {:name "Main" :host "10.0.0.2" :zone-groups []})
                  (assoc-in [:chains :cue-chains [1 0] :target] :auto/left))]
    (is (has-row? state :warn "Shapes: A2 reaches the audience through Left (Aud), so this vibe never plays it. Allow audience cells, or take it out")
        "Aud is the left laser, though it lists no group: the derived groups count, named as the pickers name them")
    (is (has-row? (assoc-in state [:chains :cue-chains [1 0] :target] :auto.laser/aud)
                  :warn "Shapes: A2 reaches the audience through Aud, so this vibe never plays it. Allow audience cells, or take it out")
        "a cue aimed at the scanner's own group names it once")
    (is (not-any? #(re-find #"A2 reaches" (:text %)) (library/lint (assoc-in state [:rig :order] [:main :aud])))
        "with Main on the left, Left doesn't reach the audience")))


;; Lint of the tap reactions (04 §7)

(defn- react
  "state with tap reactions of kinds made for the vibe with id."
  [state id & kinds]
  (reduce #(reactions/add-to-vibe %1 id %2) state kinds))

(defn- rows-about [state id]
  (into [] (comp (filter #(= id (:vibe %))) (map (juxt :level :text))) (library/lint state)))

(defn- unbind
  "state without the keys of roles."
  [state & roles]
  (update-in state [:config :input :trigger-map]
             #(into {} (remove (fn [[_ action]] (some (partial library/binds-role? action) roles))) %)))

(deftest lint-effects-only-role-is-info-test
  (let [s0 (learned-all (starter))
        with-vibe (fn [state id m] (update-in state [:config :vibes :library id] merge m))
        no-hits (with-vibe s0 :vibe-1 {:hit {:cells []}})
        does-nothing [:error "Shapes: HIT does nothing (no cues, no tap reactions)"]]
    (testing "HIT with no cue to play but reactions: it moves effects only, an info row instead of the error"
      (is (= [does-nothing] (rows-about no-hits :vibe-1)))
      (is (= [[:info "Shapes: HIT moves effects only (Punch size)"]] (rows-about (react no-hits :vibe-1 :punch-size) :vibe-1)))
      (is (= [[:info "Shapes: HIT moves effects only (Punch size, Step colour)"]]
             (rows-about (react no-hits :vibe-1 :punch-size :step-colour) :vibe-1)))
      (is (= [[:info "Shapes: HIT moves effects only (Energy size)"]] (rows-about (react no-hits :vibe-1 :energy-size) :vibe-1))
          "one that follows HIT, ACCENT or FLASH")
      (is (= [does-nothing] (rows-about (react no-hits :vibe-2 :punch-size) :vibe-1)) "Lines' reaction isn't Shapes'")
      (is (= [does-nothing] (rows-about (assoc-in no-hits [:chains :effect-chains [7 3]]
                                                  (assoc (reactions/reaction-cell :punch-size nil) :active? true))
                                        :vibe-1))
          "nor is a cell switched on on the Effects tab: it is in no vibe, and can be switched off"))
    (testing "ACCENT: an info row when only reactions follow it; nothing at all is no row, as before"
      (let [no-accents (with-vibe s0 :vibe-1 {:accent {:cells []}})]
        (is (= [] (rows-about no-accents :vibe-1)))
        (is (= [[:info "Shapes: ACCENT moves effects only (Flip)"]] (rows-about (react no-accents :vibe-1 :flip) :vibe-1)))))
    (testing "FLASH with no cue and no effects does nothing, unless a reaction follows it"
      (let [no-flash (with-vibe s0 :vibe-1 {:flash {:cue nil :fx [] :solo? true}})]
        (is (= [[:warn "Shapes: FLASH does nothing in this vibe"]] (rows-about no-flash :vibe-1)))
        (is (= [[:info "Shapes: FLASH moves effects only (Swell)"]] (rows-about (react no-flash :vibe-1 :swell) :vibe-1)))
        (is (= [[:info "Shapes: FLASH moves effects only (Strobe)"]] (rows-about (react no-flash :vibe-1 :strobe) :vibe-1))
            "the Strobe, in its flash effects")
        (is (= [] (rows-about (with-vibe no-flash :vibe-1 {:flash {:cue nil :fx [[0 3]] :solo? true}}) :vibe-1))
            "flash effects that follow no press: no row, as before")))
    (is (= [] (rows-about (react s0 :vibe-1 :punch-size :flip :swell) :vibe-1)) "roles that play cues: no row")))

(deftest lint-reaction-on-an-unbound-role-test
  (let [s0 (learned-all (starter))
        warns (fn [state] (into [] (comp (filter #(= :warn (:level %))) (map (juxt :vibe :text))) (library/lint state)))]
    (is (= [[:vibe-1 "Shapes · Scatter listens to ACCENT, which has no key yet"]]
           (warns (-> s0 (unbind :accent) (react :vibe-1 :scatter)))))
    (is (= [] (warns (react s0 :vibe-1 :scatter))) "ACCENT has its key")
    (is (= [[:vibe-2 "Lines · Fade in listens to VIBE, which has no key yet"]]
           (warns (-> s0 (unbind :vibe) (react :vibe-2 :fade-in)))))
    (testing "one that follows HIT, ACCENT or FLASH is heard while one of them has a key"
      (is (= [] (warns (-> s0 (unbind :accent :flash) (react :vibe-1 :energy-size)))))
      (is (= [[:vibe-1 "Shapes · Energy size listens to HIT, ACCENT or FLASH, none of which has a key yet"]]
             (warns (-> s0 (unbind :hit :accent :flash) (react :vibe-1 :energy-size))))))
    (testing "a cue of its pools whose effect follows a role"
      (is (= [[:vibe-1 "Shapes · square listens to ACCENT, which has no key yet"]]
             (warns (-> (unbind s0 :accent)
                        (assoc-in [:chains :cue-chains [1 0] :items 0 :effects]
                                  [{:effect-id :rotation :enabled? true
                                    :params {:angle {:type :step :source :accent :min 0.0 :max 90.0}}}]))))))
    (testing "a cell switched on on the Effects tab is no vibe's reaction"
      (is (= [] (warns (-> (unbind s0 :accent)
                           (assoc-in [:chains :effect-chains [7 3]] (assoc (reactions/reaction-cell :scatter nil) :active? true)))))))
    (testing "while no key is bound at all, the controls' row says so, not one row a reaction"
      (let [s (react (starter) :vibe-1 :scatter :punch-size)]
        (is (= [] (warns s)))
        (is (has-row? s :info "0 of 5 controls learned"))))))
