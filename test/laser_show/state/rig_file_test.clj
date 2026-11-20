(ns laser-show.state.rig-file-test
  "The rig as a document of its own (plans/2026-11-19-zones/09): what the
   rig file holds, and how a show's lasers and groups are matched onto this
   rig. Pure."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.state.domains :as domains]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.rig-file :as rig-file]))

(def ^:private unit-l "0A 1B 2C 3D 4E 5F 60 71 82 93 A4 B5 C6 D7 E8 F9")

(def ^:private laser-l
  {:name "Laser L" :host "10.0.0.5" :port 7255 :service-id 1 :unit-id unit-l
   :stable-id [:unit unit-l 1] :enabled? true :scan-rate 30000
   :output-config {:color-bit-depth 8 :xy-bit-depth 16}
   :zone-groups [:graphics] :tags #{} :exclude-from-all? false
   :safety {:limit [-0.9 -0.8 0.9 0.9] :blocked []}})

(defn- rigged
  "A show on a rig of one laser, with a virtual projector, an order and a
   calibration chain, and show content of every kind."
  []
  (-> (domains/build-initial-state)
      (assoc :projectors {:projector-10-0-0-5-1 laser-l})
      (assoc :virtual-projectors {:vp-1 {:name "VP" :parent-projector-id :projector-10-0-0-5-1 :zone-groups []}})
      (assoc :rig {:order [:projector-10-0-0-5-1] :middle :both :split-width 0.6})
      (assoc-in [:chains :projector-effects :projector-10-0-0-5-1] {:items [{:effect-id :rgb-curves :id #uuid "11111111-1111-1111-1111-111111111111"}]})
      (assoc-in [:chains :cue-chains [0 0]] {:items [{:type :preset :preset-id :circle}]})
      (assoc-in [:chains :effect-chains [0 3]] {:items [{:effect-id :scale}]})
      (assoc-in [:timing :bpm] 140.0)
      (assoc-in [:zone-groups :group-beams] {:id :group-beams :name "Beams" :color "#123456"})))

(deftest rig-document-holds-only-rig-paths-test
  (let [state (rigged)
        doc (rig-file/rig-document state)]
    (testing "§8's shape: the version, the four rig paths and the group copy"
      (is (= #{:rig-file/version :projectors :virtual-projectors :rig :chains :zone-groups} (set (keys doc))))
      (is (= rig-file/version (:rig-file/version doc)))
      (is (= (:projectors state) (:projectors doc)))
      (is (= (:virtual-projectors state) (:virtual-projectors doc)))
      (is (= (:rig state) (:rig doc)))
      (is (= {:projector-effects (get-in state [:chains :projector-effects])} (:chains doc))
          "the calibration, and no cue or effect cell"))
    (testing "nothing of the show: no tempo, grid, config, cues or effects"
      (is (not-any? #(contains? doc %) [:timing :grid :config :playback]))
      (is (= {:graphics (get-in state [:zone-groups :graphics])} (:zone-groups doc))
          "of the show's groups, only the copy of the one Laser L is in"))
    (testing "the paths it holds are the ones the project's mapping marks :rig"
      (is (= (set rig-file/rig-paths) (set (persistent/owned-paths :rig))))
      (is (= #{[:config] [:timing] [:grid] [:zone-groups] [:chains :cue-chains] [:chains :effect-chains]}
             (set (persistent/owned-paths :show))))
      (is (every? #{:show :rig} (map :owner (mapcat :paths (vals persistent/persistent-state-mapping))))
          "every saved path is the show's or the rig's"))
    (testing "a document this build can read"
      (is (rig-file/valid-rig-document? doc))
      (is (not (rig-file/newer? doc))))
    (testing "applied to another state, it gives the rig back and leaves the show"
      (let [other (assoc-in (domains/build-initial-state) [:chains :cue-chains [5 5]] {:items []})
            applied (rig-file/apply-rig-document other doc)]
        (is (= (select-keys state [:projectors :virtual-projectors :rig])
               (select-keys applied [:projectors :virtual-projectors :rig])))
        (is (= (get-in state [:chains :projector-effects]) (get-in applied [:chains :projector-effects])))
        (is (= (get-in other [:chains :cue-chains]) (get-in applied [:chains :cue-chains])) "the show's cues are left")
        (is (= (:zone-groups other) (:zone-groups applied)) "and its groups")
        (is (= (:zone-groups doc) (get-in applied [:rig-file :group-copy])) "the copy is kept beside the rig")))
    (testing "a path the file doesn't have gets its default, and [:rig] its missing keys"
      (let [applied (rig-file/apply-rig-document (rigged) {:rig-file/version 1 :rig {:order [:p]}})]
        (is (= {} (:projectors applied)))
        (is (= {} (get-in applied [:chains :projector-effects])))
        (is (= {:order [:p] :middle :split :split-width 0.75} (:rig applied)))))))

(deftest group-copy-keeps-groups-with-lasers-test
  (let [beams {:id :group-beams :name "Beams" :color "#123456"}
        state (-> (rigged)
                  (assoc-in [:projectors :projector-10-0-0-5-1 :zone-groups] [:graphics :group-beams :all :auto/left])
                  (assoc-in [:virtual-projectors :vp-1 :zone-groups] [:crowd]))]
    (is (= {:graphics (get-in state [:zone-groups :graphics])
            :group-beams beams
            :crowd (get-in state [:zone-groups :crowd])}
           (rig-file/group-copy state))
        "the groups a projector or a virtual projector lists; never All or an automatic group")
    (testing "the show's definition wins over the copy"
      (is (= "Graphics 2" (get-in (rig-file/group-copy (-> state
                                                           (assoc-in [:rig-file :group-copy :graphics :name] "Old")
                                                           (assoc-in [:zone-groups :graphics :name] "Graphics 2")))
                                  [:graphics :name]))))
    (testing "a group the show doesn't define keeps the copy's definition"
      (let [elsewhere (-> state
                          (assoc-in [:rig-file :group-copy] {:group-beams beams})
                          (update :zone-groups dissoc :group-beams))]
        (is (= beams (:group-beams (rig-file/group-copy elsewhere))))))
    (testing "a group no laser lists leaves the copy"
      (is (not (contains? (rig-file/group-copy (-> state
                                                    (assoc-in [:rig-file :group-copy :group-old] {:id :group-old :name "Old"})))
                          :group-old))))))
