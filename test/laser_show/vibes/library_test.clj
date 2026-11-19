(ns laser-show.vibes.library-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.state.domains :as domains]
            [laser-show.state.templates :as templates]
            [laser-show.vibes.engine :as engine]
            [laser-show.vibes.library :as library]))

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
      (is (false? (cell? (with-projector state :p3 {:zone-groups [:front]})))))
    (testing "destination :crowd"
      (is (true? (cell? (assoc-in state [:chains :cue-chains [0 1] :destination-zone :zone-group-id] :crowd)))))
    (testing "an item's zone selector names a group a crowd-scanning projector is in"
      (let [rerouted (assoc-in state [:chains :cue-chains [0 1] :items 0 :effects]
                               [{:effect-id :zone-selector :params {:target-zone :front}}])]
        (is (true? (cell? (with-projector rerouted :p3 {:zone-groups [:front] :tags #{:crowd-scanning}}))))
        (is (false? (cell? (with-projector rerouted :p3 {:zone-groups [:front]}))) "untagged")))
    (testing "destination :all, and the crowd-scanning projector is in :all: it does reach the crowd"
      (is (true? (cell? (with-projector state :p3 {:zone-groups [:all] :tags #{:crowd-scanning}})))))
    (testing "a destination with no group set (the zone combo sent none) routes to :all, so it reaches it too"
      (let [unset (assoc-in state [:chains :cue-chains [0 1] :destination-zone] {:zone-group-id nil})]
        (is (true? (cell? (with-projector unset :p3 {:zone-groups [:all] :tags #{:crowd-scanning}}))))
        (is (false? (cell? unset)) "no crowd-scanning projector")
        (is (true? (cell? (-> (update-in state [:chains :cue-chains [0 1]] dissoc :destination-zone)
                              (with-projector :p3 {:zone-groups [:all] :tags #{:crowd-scanning}}))))
            "no destination at all")))
    (testing "an item's or a track's own zone group, inside groups and folders"
      (is (true? (cell? (assoc-in state [:chains :cue-chains [0 1] :items]
                                  [{:type :group :items [{:type :preset :zone-group-id :crowd}]}]))))
      (is (true? (cell? (assoc-in state [:chains :cue-chains [0 1] :tracks]
                                  [{:type :group :items [{:type :track :id :t :zone-group-id :crowd}]}]))))
      (is (true? (cell? (assoc-in state [:chains :cue-chains [0 1] :tracks]
                                  [{:type :track :id :t
                                    :effects [{:effect-id :zone-selector :params {:target-zone :crowd}}]}])))))
    (testing "a virtual projector tagged, or whose projector is"
      (is (true? (cell? (assoc-in state [:virtual-projectors :v1]
                                  {:parent-projector-id :p9 :zone-groups [:all] :tags #{:crowd-scanning}}))))
      (is (true? (cell? (-> (with-projector state :p9 {:zone-groups [:left] :tags #{:crowd-scanning}})
                            (assoc-in [:virtual-projectors :v1] {:parent-projector-id :p9 :zone-groups [:all]}))))))
    (is (= #{:crowd :front} (library/audience-groups (with-projector state :p3 {:zone-groups [:front]
                                                                                :tags [:crowd-scanning]}))))))

(deftest audience-cells-never-play-unless-allowed-test
  (let [state (-> (starter)
                  (assoc-in [:chains :cue-chains [1 0] :destination-zone :zone-group-id] :crowd)
                  (assoc-in [:chains :cue-chains [3 0] :destination-zone :zone-group-id] :crowd)
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
