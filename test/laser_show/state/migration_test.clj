(ns laser-show.state.migration-test
  "Old shows load converted (spec 01 §11, plans/2026-11-19-zones/04 §4): All
   ticks become Exclude from All, empty built-in Left, Right and Center
   become the automatic groups, filled ones stay as \"Left (manual)\", All's
   definition goes, every projector gets a stable id, and no order is
   written. Silent, idempotent, and every projector draws what it drew
   before, but for content aimed at an empty built-in (04 S-2)."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [laser-show.routing.compose :as compose]
            [laser-show.routing.rig :as rig]
            [laser-show.state.domains :as domains]
            [laser-show.state.migration :as migration]))

(def ^:private unit-l "0A 1B 2C 3D 4E 5F 60 71 82 93 A4 B5 C6 D7 E8 F9")
(def ^:private unit-r "1F 2E 3D 4C 5B 6A 79 88 97 A6 B5 C4 D3 E2 F1 00")
(def ^:private zeros "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")

(def ^:private old-zone-groups
  "The six groups every show had before Z2."
  {:all {:id :all :name "All" :description "All projectors" :color "#808080"}
   :left {:id :left :name "Left" :description "Left side projectors" :color "#4A90D9"}
   :right {:id :right :name "Right" :description "Right side projectors" :color "#D94A4A"}
   :center {:id :center :name "Center" :description "Center projectors" :color "#4AD94A"}
   :graphics {:id :graphics :name "Graphics" :description "Graphics-specific projectors" :color "#9B59B6"}
   :crowd {:id :crowd :name "Crowd Scanning" :description "Audience scanning projectors" :color "#E67E22"}})

(def ^:private old-hardware
  "04 §8's hardware.edn: no :rig. Nothing lists :left or :center; Laser R
   lists :right. Aud doesn't tick All."
  {:projectors {:projector-10-0-0-5-1 {:name "Laser L" :host "10.0.0.5" :service-id 1 :enabled? true
                                       :unit-id unit-l :zone-groups [:all :graphics]}
                :projector-10-0-0-12-1 {:name "Aud" :host "10.0.0.12" :service-id 1 :enabled? true
                                        :unit-id zeros :zone-groups [:crowd] :tags #{:crowd-scanning}}
                :projector-10-0-0-7-1 {:name "Laser R" :host "10.0.0.7" :service-id 1 :enabled? true
                                       :unit-id unit-r :zone-groups [:all :right]}
                :projector-10-0-0-8-1 {:name "Spare" :host "10.0.0.8" :service-id 1 :enabled? false
                                       :zone-groups [:all]}}
   :virtual-projectors {:vp-1 {:name "VP" :parent-projector-id :projector-10-0-0-5-1 :zone-groups [:all]}
                        :vp-2 {:name "VP 2" :parent-projector-id :projector-10-0-0-7-1 :zone-groups [:graphics]}}
   :zone-groups (assoc old-zone-groups :group-balcony {:id :group-balcony :name "Balcony" :color "#123456"})})

(defn- selector [id zone & keyframe-zones]
  (cond-> {:id id :effect-id :zone-selector :enabled? false :params {:target-zone zone}}
    (seq keyframe-zones) (assoc :keyframe-modulator {:enabled? true
                                                     :keyframes (mapv (fn [z] {:position 0.0 :params {:target-zone z}})
                                                                      keyframe-zones)})))

(def ^:private old-cues
  "One reference to a built-in in each place routing.refs knows."
  {[0 0] {:name "Item" :destination-zone {:zone-group-id :center}
          :items [{:id "i1" :type :preset :preset-id :circle :zone-group-id :left}
                  {:id "i2" :type :preset :preset-id :wave :zone-group-id :right}]}
   [1 0] {:name "Timeline" :editor-type :timeline
          :tracks [{:id "f1" :type :folder :zone-group-id :left
                    :items [{:id "t1" :type :track :zone-group-id :center}]}]
          :items [{:id "g1" :type :group :zone-group-id :left
                   :items [{:id "c1" :type :preset :preset-id :circle :zone-group-id :center}]}]}
   [2 0] {:name "Selector"
          :items [{:id "i3" :type :preset :preset-id :circle
                   :effects [(selector "z1" :left :center :graphics)]}]}
   [3 0] {:name "Mine" :destination-zone {:zone-group-id :group-balcony} :items []}})

(def ^:private old-preview
  {:zone-group-filter :left :grid-layout [2 2] :show-labels? true
   :grid-cells [{:zone-group-id nil} {:zone-group-id :left} {:zone-group-id :right} {:zone-group-id :center}]})

(def ^:private old-state
  "The state apply-loaded-project hands the migration: the file's document."
  (-> (domains/build-initial-state)
      (merge old-hardware)
      (assoc-in [:chains :cue-chains] old-cues)
      (assoc-in [:chains :effect-chains] {[0 3] {:items [(selector "z2" :center)]}})
      (assoc-in [:config :preview] old-preview)))

(def ^:private migrated (migration/zones-z2 old-state old-hardware))

(defn- projector [state id] (get-in state [:projectors id]))

(deftest all-membership-becomes-exclude-test
  (is (= {:zone-groups [:graphics] :exclude-from-all? false}
         (select-keys (projector migrated :projector-10-0-0-5-1) [:zone-groups :exclude-from-all?])))
  (is (= {:zone-groups [:crowd] :exclude-from-all? true}
         (select-keys (projector migrated :projector-10-0-0-12-1) [:zone-groups :exclude-from-all?]))
      "a projector that didn't tick All is excluded from it")
  (is (= {:zone-groups [:right] :exclude-from-all? false}
         (select-keys (projector migrated :projector-10-0-0-7-1) [:zone-groups :exclude-from-all?])))
  (is (= [{:zone-groups [] :exclude-from-all? false} {:zone-groups [:graphics] :exclude-from-all? true}]
         (mapv #(select-keys (get-in migrated [:virtual-projectors %]) [:zone-groups :exclude-from-all?]) [:vp-1 :vp-2]))
      "virtual projectors too (S-8)")
  (testing "a flag that is there already is kept, and All still leaves the list"
    (let [hw (assoc-in old-hardware [:projectors :projector-10-0-0-5-1 :exclude-from-all?] true)
          state (migration/zones-z2 (merge old-state hw) hw)]
      (is (= {:zone-groups [:graphics] :exclude-from-all? true}
             (select-keys (projector state :projector-10-0-0-5-1) [:zone-groups :exclude-from-all?]))))))

(deftest empty-built-ins-become-auto-test
  (is (not-any? #(contains? (:zone-groups migrated) %) [:left :center]) "the empty built-ins go")
  (let [cues (get-in migrated [:chains :cue-chains])]
    (is (= :auto/left (get-in cues [[0 0] :items 0 :zone-group-id])) "an item")
    (is (= :auto/center (get-in cues [[0 0] :destination-zone :zone-group-id])) "the cue's destination")
    (is (= :auto/left (get-in cues [[1 0] :tracks 0 :zone-group-id])) "a folder")
    (is (= :auto/center (get-in cues [[1 0] :tracks 0 :items 0 :zone-group-id])) "a track")
    (is (= :auto/left (get-in cues [[1 0] :items 0 :zone-group-id])) "a group clip")
    (is (= :auto/center (get-in cues [[1 0] :items 0 :items 0 :zone-group-id])) "a clip in it")
    (is (= :auto/left (get-in cues [[2 0] :items 0 :effects 0 :params :target-zone])) "a Zone Selector, on or off")
    (is (= [:auto/center :graphics] (mapv #(get-in % [:params :target-zone])
                                          (get-in cues [[2 0] :items 0 :effects 0 :keyframe-modulator :keyframes])))
        "and its keyframes"))
  (is (= :auto/center (get-in migrated [:chains :effect-chains [0 3] :items 0 :params :target-zone])) "an effect cell's")
  (is (= [nil :auto/left :right :auto/center] (mapv :zone-group-id (get-in migrated [:config :preview :grid-cells])))
      "the preview's cells")
  (is (= :auto/left (get-in migrated [:config :preview :zone-group-filter])) "and its filter"))

(deftest built-ins-with-members-become-manual-test
  (is (= (assoc (:right old-zone-groups) :name "Right (manual)") (get-in migrated [:zone-groups :right]))
      "Laser R lists Right: it stays, a group of your own")
  (is (= :right (get-in migrated [:chains :cue-chains [0 0] :items 1 :zone-group-id])) "and what names it still does")
  (testing "a member that is off or virtual counts as one (S-10)"
    (doseq [hw [(assoc-in old-hardware [:projectors :projector-10-0-0-8-1 :zone-groups] [:all :left])
                (assoc-in old-hardware [:virtual-projectors :vp-1 :zone-groups] [:left])]]
      (is (= "Left (manual)" (get-in (migration/zones-z2 (merge old-state hw) hw) [:zone-groups :left :name])))))
  (testing "one you renamed keeps its name"
    (let [hw (assoc-in old-hardware [:zone-groups :right :name] "Stage right")]
      (is (= "Stage right" (get-in (migration/zones-z2 (merge old-state hw) hw) [:zone-groups :right :name])))))
  (testing "a built-in deleted before is left alone, and so is what still names it (S-12)"
    (let [hw (update old-hardware :zone-groups dissoc :left)
          state (migration/zones-z2 (merge old-state hw) hw)]
      (is (= :left (get-in state [:chains :cue-chains [0 0] :items 0 :zone-group-id])))
      (is (not (contains? (:zone-groups state) :left))))))

(deftest custom-groups-are-unchanged-test
  (is (= (select-keys (:zone-groups old-hardware) [:graphics :crowd :group-balcony])
         (dissoc (:zone-groups migrated) :right)))
  (is (= :group-balcony (get-in migrated [:chains :cue-chains [3 0] :destination-zone :zone-group-id])))
  (is (not (contains? (:zone-groups migrated) :all)) "All is built in now (S-9)")
  (is (= :graphics (get-in migrated [:chains :cue-chains [2 0] :items 0 :effects 0 :keyframe-modulator :keyframes 1 :params :target-zone]))))

(deftest order-comes-from-ip-then-name-test
  (is (= [] (get-in migrated [:rig :order])) "no order is written (S-11)")
  (is (= [:projector-10-0-0-5-1 :projector-10-0-0-7-1 :projector-10-0-0-8-1 :projector-10-0-0-12-1]
         (:order (rig/rig-inputs migrated)))))

(deftest stable-ids-are-added-test
  (is (= {:projector-10-0-0-5-1 [:unit unit-l 1]
          :projector-10-0-0-12-1 [:host "10.0.0.12" 1]
          :projector-10-0-0-7-1 [:unit unit-r 1]
          :projector-10-0-0-8-1 [:host "10.0.0.8" 1]}
         (update-vals (:projectors migrated) :stable-id))
      "the unit id where it is usable, else the address")
  (let [hw (assoc-in old-hardware [:projectors :projector-10-0-0-5-1 :stable-id] [:host "10.0.0.5" 1])]
    (is (= [:host "10.0.0.5" 1] (get-in (migration/zones-z2 (merge old-state hw) hw) [:projectors :projector-10-0-0-5-1 :stable-id]))
        "one it has is kept")))

(deftest duplicate-unit-keeps-one-identity-test
  (let [hw (-> old-hardware
               (assoc-in [:projectors :projector-10-0-0-6-1] {:name "Laser L again" :host "10.0.0.6" :service-id 1
                                                              :enabled? false :unit-id unit-l :zone-groups [:all]}))
        state (migration/zones-z2 (merge old-state hw) hw)]
    (is (= [:unit unit-l 1] (get-in state [:projectors :projector-10-0-0-5-1 :stable-id])) "the enabled one keeps it")
    (is (= [:host "10.0.0.6" 1] (get-in state [:projectors :projector-10-0-0-6-1 :stable-id]))
        "the other one is known by its address, and stays where it is")))

(deftest files-with-a-rig-are-left-alone-test
  (let [hw (assoc old-hardware :rig {:order [] :middle :split :split-width 0.75})]
    (is (identical? old-state (migration/zones-z2 old-state hw)))))

(deftest conversion-is-idempotent-test
  (is (= migrated (migration/zones-z2 migrated old-hardware)) "converting again changes nothing")
  (is (= migrated (migration/zones-z2 migrated (merge old-hardware (select-keys migrated [:projectors :virtual-projectors :zone-groups]))))
      "nor does converting what it wrote"))

(defn- sub-frame?
  "Is f a run of consecutive points of frame?"
  [frame f]
  (let [n (count f)]
    (boolean (some #(= f (subvec frame % (+ % n))) (range (inc (- (count frame) n)))))))

(deftest old-show-routes-as-before-test
  (let [renamed {:left :auto/left :center :auto/center}
        old-keys [:all :left :right :center :graphics :crowd :group-balcony]
        frame-of (fn [k] [[0.0 0.0 1.0 1.0 1.0] [0.1 0.0 1.0 1.0 1.0] [(double (.indexOf ^java.util.List old-keys k)) 0.0 1.0 1.0 1.0]])
        old-frames (into {} (map (fn [k] [k (frame-of k)])) old-keys)
        new-frames (into {} (map (fn [k] [(get renamed k k) (frame-of k)])) old-keys)
        ;; Before Z2 an engine drew the frames of the groups its projector listed
        before-draws (fn [ks] (set (keep #(get old-frames %) ks)))
        routing (rig/routing (rig/routing-inputs migrated nil))
        composed #(:frame (compose/projector-frame [{:cell [0 0] :zone-frames new-frames}] routing %))
        after-draws (fn [id] (if-let [out (composed id)]
                               (set (filter #(sub-frame? out %) (vals new-frames)))
                               #{}))]
    (doseq [[id p] (:projectors old-hardware)]
      (testing (name id)
        (let [before (if (:enabled? p true) (before-draws (:zone-groups p)) #{})
              after (after-draws id)]
          (is (= (set/difference before (set (map frame-of (keys renamed))))
                 (set/difference after (set (map frame-of (keys renamed)))))
              "every frame it drew, and no other, but for the converted built-ins")
          (is (set/subset? (set/intersection before (set (map frame-of (keys renamed)))) after))
          (when (seq after)
            (is (= (+ (* 3 (count after)) (* 2 (dec (count after)))) (count (composed id)))
                "each of them once, nothing else: the same points (S-4)")))))
    (testing "the one change (S-2): content aimed at the empty Left now reaches the left laser"
      (is (contains? (after-draws :projector-10-0-0-5-1) (frame-of :left)))
      (is (not (contains? (before-draws [:all :graphics]) (frame-of :left)))))))
