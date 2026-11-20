(ns laser-show.state.rig-file-test
  "The rig as a document of its own (plans/2026-11-19-zones/09): what the
   rig file holds, and how a show's lasers and groups are matched onto this
   rig. Pure."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.safety :as safety]
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

(deftest rig-changed-ignores-show-edits-test
  (let [s (rigged)]
    (is (false? (rig-file/rig-changed? s s)))
    (doseq [[label edit] [["a cue" #(assoc-in % [:chains :cue-chains [1 1]] {:items []})]
                          ["an effect cell" #(assoc-in % [:chains :effect-chains [0 3] :active?] false)]
                          ["the tempo" #(assoc-in % [:timing :bpm] 99.0)]
                          ["the show's config" #(assoc-in % [:config :cue :trigger-mode] :toggle)]
                          ["a group no laser is in" #(assoc-in % [:zone-groups :group-beams :name] "Beams 2")]
                          ["a new group" #(assoc-in % [:zone-groups :group-new] {:id :group-new :name "New"})]
                          ["the selection" #(assoc-in % [:projector-ui :active-projector] :projector-10-0-0-5-1)]
                          ["the rig file's own status" #(assoc-in % [:rig-file :written-at] 5)]]]
      (is (false? (rig-file/rig-changed? s (edit s))) label)))
  (testing "a rig path rebuilt with the same value isn't a change"
    (let [s (rigged)]
      (is (false? (rig-file/rig-changed? s (update s :projectors #(into {} %))))))))

(deftest rig-changed-sees-a-renamed-member-group-test
  (let [s (rigged)]
    (doseq [[label edit] [["a projector" #(assoc-in % [:projectors :projector-10-0-0-5-1 :name] "Stage L")]
                          ["a virtual projector" #(assoc-in % [:virtual-projectors :vp-2] {:name "VP 2"})]
                          ["the order" #(assoc-in % [:rig :order] [])]
                          ["the calibration" #(assoc-in % [:chains :projector-effects :projector-10-0-0-5-1 :items] [])]
                          ["the definition of a group Laser L is in" #(assoc-in % [:zone-groups :graphics :name] "Gfx")]
                          ["that group's colour" #(assoc-in % [:zone-groups :graphics :color] "#000000")]]]
      (is (true? (rig-file/rig-changed? s (edit s))) label))))

(def ^:private unit-r "1F 2E 3D 4C 5B 6A 79 88 97 A6 B5 C4 D3 E2 F1 00")

(defn- pinned
  "A cue cell that plays on ref."
  [ref]
  {:items [{:id (str "i-" (name ref)) :type :preset :preset-id :circle}] :destination-zone {:zone-group-id ref}})

(deftest rename-pins-by-stable-id-test
  (let [;; This rig: unit L found at .57, unit R at .7, and another unit at .5
        here (-> (domains/build-initial-state)
                 (assoc :projectors {:projector-10-0-0-57-1 {:name "Laser L" :host "10.0.0.57" :service-id 1 :stable-id [:unit unit-l 1]}
                                     :projector-10-0-0-7-1 {:name "Laser R" :host "10.0.0.7" :service-id 1 :stable-id [:unit unit-r 1]}
                                     :projector-10-0-0-5-1 {:name "Someone else" :host "10.0.0.5" :service-id 1 :stable-id [:unit "99 99" 1]}})
                 (assoc-in [:chains :cue-chains] {[0 0] (pinned :auto.laser/projector-10-0-0-5-1)
                                                  [1 0] (pinned :auto.laser/projector-10-0-0-7-1)
                                                  [2 0] (pinned :auto.laser/projector-10-0-0-9-1)
                                                  [3 0] (pinned :auto.laser/projector-10-0-0-6-1)
                                                  [4 0] (pinned :graphics)})
                 (assoc-in [:chains :cue-chains [5 0]] {:items [{:id "i5" :type :preset :preset-id :wave
                                                                 :zone-group-id :auto.laser/projector-10-0-0-5-1}]})
                 (assoc-in [:config :preview :grid-cells 1 :zone-group-id] :auto.laser/projector-10-0-0-5-1))
        ;; friday.zip's hardware: unit L at .5, unit R at .7, a host-only laser at .9, and one at .6 whose
        ;; unit isn't here
        hardware {:rig {:order []}
                  :projectors {:projector-10-0-0-5-1 {:name "Laser L" :host "10.0.0.5" :service-id 1 :stable-id [:unit unit-l 1]}
                               :projector-10-0-0-7-1 {:name "Laser R" :host "10.0.0.7" :service-id 1 :stable-id [:unit unit-r 1]}
                               :projector-10-0-0-9-1 {:name "SR Beam" :host "10.0.0.9" :service-id 1 :stable-id [:host "10.0.0.9" 1]}
                               :projector-10-0-0-6-1 {:name "Gone" :host "10.0.0.5" :service-id 2 :stable-id [:unit "77 77" 2]}}}
        renamed (rig-file/rename-pins here hardware)
        target (fn [s cell] (get-in s [:chains :cue-chains cell :destination-zone :zone-group-id]))]
    (is (= :auto.laser/projector-10-0-0-57-1 (target renamed [0 0])) "Laser L's pin follows the unit to .57")
    (is (= :auto.laser/projector-10-0-0-57-1 (get-in renamed [:chains :cue-chains [5 0] :items 0 :zone-group-id])) "an item's too")
    (is (= :auto.laser/projector-10-0-0-57-1 (get-in renamed [:config :preview :grid-cells 1 :zone-group-id])) "and a preview cell")
    (is (= :auto.laser/projector-10-0-0-7-1 (target renamed [1 0])) "Laser R has the same id here")
    (is (= :auto.laser/projector-10-0-0-9-1 (target renamed [2 0])) "a laser that isn't here keeps its id, which reaches nothing")
    (is (= :graphics (target renamed [4 0])) "groups are left alone")
    (testing "a laser that isn't here, whose id is another laser's here, gets a free one: its pins never reach that laser"
      (is (= {:projector-10-0-0-5-1 :projector-10-0-0-57-1
              :projector-10-0-0-7-1 :projector-10-0-0-7-1
              :projector-10-0-0-9-1 :projector-10-0-0-9-1
              :projector-10-0-0-6-1 :projector-10-0-0-6-1}
             (rig-file/pin-map here hardware)))
      (let [collides (assoc-in hardware [:projectors :projector-10-0-0-5-1 :stable-id] [:unit "AB CD" 1])
            renamed (rig-file/rename-pins here collides)]
        (is (= :auto.laser/projector-10-0-0-5-1-2 (target renamed [0 0])))
        (is (not (contains? (:projectors here) :projector-10-0-0-5-1-2)))))
    (testing "two lasers whose ids are each other's here swap their pins"
      (let [swapped (-> here
                        (assoc-in [:projectors :projector-10-0-0-57-1 :stable-id] [:unit unit-r 1])
                        (assoc-in [:projectors :projector-10-0-0-7-1 :stable-id] [:unit unit-l 1]))
            renamed (rig-file/rename-pins swapped hardware)]
        (is (= :auto.laser/projector-10-0-0-7-1 (target renamed [0 0])) "L's pin goes to where unit L is here")
        (is (= :auto.laser/projector-10-0-0-57-1 (target renamed [1 0])) "and R's to where unit R is")))
    (testing "a show from before Z2: its projectors are known by their :unit-id"
      (let [old {:projectors {:projector-10-0-0-5-1 {:name "Laser L" :host "10.0.0.5" :service-id 1 :unit-id unit-l
                                                     :zone-groups [:all]}}}]
        (is (= :auto.laser/projector-10-0-0-57-1 (target (rig-file/rename-pins here old) [0 0])))))
    (testing "nothing to rename: the same state"
      (is (identical? here (rig-file/rename-pins here {:projectors {}})))
      (is (identical? here (rig-file/rename-pins here nil))))))

;; Setting the rig up from a show (09 §7)

(def ^:private friday-hardware
  "friday.zip's hardware.edn: Laser L (unit L) at .5 with its zone, groups
   and calibration, SR Beam (host only) at .9, a laser that was off there,
   a virtual projector, and its order."
  {:projectors {:projector-10-0-0-5-1 {:name "Laser L" :host "10.0.0.5" :port 7255 :service-id 1 :unit-id unit-l
                                       :stable-id [:unit unit-l 1] :enabled? true :scan-rate 30000
                                       :zone-groups [:graphics] :exclude-from-all? false :tags #{:graphics}
                                       :safety {:limit [-0.9 -0.8 0.9 0.9] :blocked [[-0.2 -0.2 0.2 0.2]]}}
                :projector-10-0-0-9-1 {:name "SR Beam" :host "10.0.0.9" :port 7255 :service-id 1
                                       :stable-id [:host "10.0.0.9" 1] :enabled? true
                                       :zone-groups [] :exclude-from-all? true}
                :projector-10-0-0-8-1 {:name "Spare" :host "10.0.0.8" :service-id 1 :stable-id [:host "10.0.0.8" 1]
                                       :enabled? false :zone-groups []}}
   :virtual-projectors {:vp-l {:name "L graphics" :parent-projector-id :projector-10-0-0-5-1 :zone-groups [:graphics]}}
   :rig {:order [:projector-10-0-0-9-1 :projector-10-0-0-5-1] :middle :none :split-width 0.9}})

(def ^:private friday-chains
  {:projector-10-0-0-5-1 {:items [{:effect-id :corner-pin :id #uuid "22222222-2222-2222-2222-222222222222"}]}
   :projector-10-0-0-9-1 {:items [{:effect-id :rgb-curves :id #uuid "33333333-3333-3333-3333-333333333333"}]}})

(defn- empty-rig
  "This rig, no laser on yet: the startup scan found unit L at .57 (off,
   default calibration), and a laser at .9 known by its address."
  []
  (-> (domains/build-initial-state)
      (assoc :projectors {:projector-10-0-0-57-1 {:name "Head 1" :host "10.0.0.57" :port 7300 :service-id 1 :unit-id unit-l
                                                  :stable-id [:unit unit-l 1] :enabled? false :zone-groups []
                                                  :exclude-from-all? false :tags #{}}
                          :projector-10-0-0-9-1 {:name "Head 9" :host "10.0.0.9" :port 7255 :service-id 1
                                                 :stable-id [:host "10.0.0.9" 1] :enabled? false :zone-groups []
                                                 :exclude-from-all? false :tags #{}}})
      (assoc-in [:chains :projector-effects] {:projector-10-0-0-57-1 {:items [{:effect-id :rgb-curves}]}})
      (assoc-in [:chains :cue-chains [0 0]] (pinned :auto.laser/projector-10-0-0-57-1))))

(deftest set-up-keeps-rig-ids-and-addresses-test
  (let [s (empty-rig)
        pins (rig-file/pin-map s friday-hardware)
        set-up (rig-file/set-up-from-show s friday-hardware friday-chains pins)
        laser (get-in set-up [:projectors :projector-10-0-0-57-1])]
    (is (false? (rig-file/set-up? s)) "no laser on: the question comes up")
    (is (true? (rig-file/set-up? set-up)))
    (testing "a laser already here keeps its id and address, and takes everything else from the show"
      (is (= {:host "10.0.0.57" :port 7300 :service-id 1 :stable-id [:unit unit-l 1]}
             (select-keys laser [:host :port :service-id :stable-id])))
      (is (= {:name "Laser L" :enabled? true :scan-rate 30000 :zone-groups [:graphics] :tags #{:graphics}
              :safety {:limit [-0.9 -0.8 0.9 0.9] :blocked [[-0.2 -0.2 0.2 0.2]]}}
             (select-keys laser [:name :enabled? :scan-rate :zone-groups :tags :safety])))
      (is (= [:corner-pin] (mapv :effect-id (get-in set-up [:chains :projector-effects :projector-10-0-0-57-1 :items])))
          "and its calibration"))
    (testing "matched by address: the same"
      (is (= ["SR Beam" true "10.0.0.9"] ((juxt :name :enabled? :host) (get-in set-up [:projectors :projector-10-0-0-9-1])))))
    (testing "a laser the show had off doesn't come: the show wasn't made with it"
      (is (not (contains? (:projectors set-up) :projector-10-0-0-8-1))))
    (testing "the order, the middle laser and the split width are the show's, in this rig's ids"
      (is (= {:order [:projector-10-0-0-9-1 :projector-10-0-0-57-1] :middle :none :split-width 0.9} (:rig set-up))))
    (testing "the virtual projector, on its laser here"
      (is (= :projector-10-0-0-57-1 (get-in set-up [:virtual-projectors :vp-l :parent-projector-id]))))
    (testing "a laser that isn't here is added, with its setup"
      (let [s (update (empty-rig) :projectors dissoc :projector-10-0-0-9-1)
            set-up (rig-file/set-up-from-show s friday-hardware friday-chains (rig-file/pin-map s friday-hardware))]
        (is (= (get-in friday-hardware [:projectors :projector-10-0-0-9-1]) (get-in set-up [:projectors :projector-10-0-0-9-1])))
        (is (= [:rgb-curves] (mapv :effect-id (get-in set-up [:chains :projector-effects :projector-10-0-0-9-1 :items]))))))
    (testing "a pin renamed when the show was opened follows a laser the rig found since"
      (let [opened (update (empty-rig) :projectors dissoc :projector-10-0-0-9-1)
            pins-then (rig-file/pin-map opened friday-hardware)
            later (-> opened
                      (assoc-in [:chains :cue-chains [1 0]] (pinned :auto.laser/projector-10-0-0-9-1))
                      (assoc-in [:projectors :projector-10-0-0-90-1] {:name "Found" :host "10.0.0.90" :service-id 1
                                                                      :stable-id [:host "10.0.0.9" 1] :enabled? false}))
            set-up (rig-file/set-up-from-show later friday-hardware friday-chains pins-then)]
        (is (= :auto.laser/projector-10-0-0-90-1 (get-in set-up [:chains :cue-chains [1 0] :destination-zone :zone-group-id])))
        (is (not (contains? (:projectors set-up) :projector-10-0-0-9-1)) "no second laser for the same unit")))))

(deftest set-up-converts-old-hardware-first-test
  (let [old {:projectors {:projector-10-0-0-5-1 {:name "Laser L" :host "10.0.0.5" :service-id 1 :unit-id unit-l
                                                 :enabled? true :zone-groups [:all :graphics]}
                          :projector-10-0-0-12-1 {:name "Aud" :host "10.0.0.12" :service-id 1 :enabled? true
                                                  :zone-groups [:crowd]}}}
        s (empty-rig)
        set-up (rig-file/set-up-from-show s old {} (rig-file/pin-map s old))]
    (is (= {:zone-groups [:graphics] :exclude-from-all? false :stable-id [:unit unit-l 1] :host "10.0.0.57"}
           (select-keys (get-in set-up [:projectors :projector-10-0-0-57-1]) [:zone-groups :exclude-from-all? :stable-id :host]))
        "All's tick became All, and the unit is the one found here")
    (is (= {:zone-groups [:crowd] :exclude-from-all? true :stable-id [:host "10.0.0.12" 1]}
           (select-keys (get-in set-up [:projectors :projector-10-0-0-12-1]) [:zone-groups :exclude-from-all? :stable-id]))
        "a laser that didn't tick All is excluded from it, as Z2 converts it")
    (is (= [:projector-10-0-0-57-1 :projector-10-0-0-12-1] (get-in set-up [:rig :order]))
        "no order in an old show: by its IPs, then names")))

(deftest show-safety-is-normalized-on-the-way-in-test
  (let [s (empty-rig)
        hw (assoc-in friday-hardware [:projectors :projector-10-0-0-5-1 :safety] {:limit [1 1 -1 -0.5] :blocked [[0.5 0.5 0.2 0.2]]})
        set-up (rig-file/set-up-from-show s hw friday-chains (rig-file/pin-map s hw))]
    (is (= {:limit [-1.0 -0.5 1.0 1.0] :blocked [[0.2 0.2 0.5 0.5]]}
           (get-in set-up [:projectors :projector-10-0-0-57-1 :safety]))
        "corners ordered and numbers made doubles, as safety/normalize gives them")
    (is (not (contains? (get-in set-up [:projectors :projector-10-0-0-9-1]) :safety))
        "a laser the show gave no zone gets none: the default, as on any laser")))

(deftest unreadable-show-safety-stays-dark-test
  (let [s (empty-rig)
        bad {:limit [0 0 "x" 1]}
        hw (assoc-in friday-hardware [:projectors :projector-10-0-0-5-1 :safety] bad)
        set-up (rig-file/set-up-from-show s hw friday-chains (rig-file/pin-map s hw))]
    (testing "the set-up copies it as it is: the stage fails closed, the laser stays dark until its zone is set"
      (is (= bad (get-in set-up [:projectors :projector-10-0-0-57-1 :safety])))
      (is (thrown? clojure.lang.ExceptionInfo (safety/normalize (get-in set-up [:projectors :projector-10-0-0-57-1 :safety])))))
    (testing "and the question names it"
      (is (= ["Laser L"] (:unreadable (rig-file/set-up-question s hw)))))))

(deftest set-up-question-test
  (let [q (rig-file/set-up-question (empty-rig) friday-hardware)]
    (is (= ["SR Beam" "Laser L"] (:lasers q)) "the lasers the show was made with, in its order")
    (is (= [{:name "SR Beam" :host "10.0.0.9"}] (:matched-by-ip q)) "matched by IP only: check them")
    (is (= [] (:unreadable q))))
  (is (nil? (rig-file/set-up-question (empty-rig) {:projectors {:p {:name "Off" :host "10.0.0.1" :enabled? false}}}))
      "a show made with no laser on: nothing to set up"))

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
