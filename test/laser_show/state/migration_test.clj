(ns laser-show.state.migration-test
  "Old shows load converted (spec 01 §11, plans/2026-11-19-zones/04 §4): All
   ticks become Exclude from All, empty built-in Left, Right and Center
   become the automatic groups, filled ones stay as \"Left (manual)\", All's
   definition goes, every projector gets a stable id, and no order is
   written. Silent, idempotent, and every projector draws what it drew
   before, but for content aimed at an empty built-in (04 S-2). Then zones
   plan Z4's rows (06 §8.3): every way a show routed becomes a :target, and
   the Zone Selector goes."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.events.handlers.grid :as grid]
            [laser-show.routing.compose :as compose]
            [laser-show.routing.rig :as rig]
            [laser-show.state.domains :as domains]
            [laser-show.state.migration :as migration]
            [laser-show.timeline.shows :as shows]
            [laser-show.vibes.refs :as vibe-refs]))

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


;; Z4's rows (06 §8.3): every way an old show routed becomes a :target

(defn- zone-sel
  ([id zone] (zone-sel id zone true))
  ([id zone on?] {:id id :effect-id :zone-selector :enabled? on? :params (if zone {:target-zone zone} {})}))

(def ^:private scale-fx {:id "fx" :effect-id :scale :enabled? true :params {:x-scale 0.5}})

(deftest destination-becomes-the-cue-target-test
  (is (= {:target :graphics :items []} (migration/cue-z4 {:destination-zone {:zone-group-id :graphics} :items []})))
  (is (= {:items []} (migration/cue-z4 {:destination-zone {:zone-group-id :all} :items []}))
      "All becomes none: levels 5 and 6 are empty on an old show, so it still ends at All")
  (is (= {:items []} (migration/cue-z4 {:destination-zone {:zone-group-id nil} :items []})))
  (is (= {:items []} (migration/cue-z4 {:destination-zone {} :items []})))
  (is (= {:target [:graphics :auto/left] :items []}
         (migration/cue-z4 {:destination-zone {:zone-group-id [:auto/left :graphics]} :items []}))
      "in normal form")
  (is (= {:target :crowd :items []} (migration/cue-z4 {:target :crowd :destination-zone {:zone-group-id :graphics} :items []}))
      "a :target it has stays")
  (is (nil? (migration/cue-z4 nil))))

(deftest zone-groups-become-targets-test
  (is (= {:tracks [{:id "f" :type :group :target :auto/left
                    :items [{:id "t" :type :track :target :graphics} {:id "u" :type :track}]}]
          :items [{:id "i" :type :preset :target :auto/right}
                  {:id "g" :type :group :target :all
                   :items [{:id "c" :type :preset :target :crowd} {:id "d" :type :preset}]}
                  {:id "k" :type :group :track-id "t"
                   :items [{:id "e" :type :preset :target [:graphics :auto/left]}]}]}
         (migration/cue-z4
          {:tracks [{:id "f" :type :group :zone-group-id :auto/left
                     :items [{:id "t" :type :track :zone-group-id :graphics} {:id "u" :type :track}]}]
           :items [{:id "i" :type :preset :zone-group-id :auto/right}
                   {:id "g" :type :group :zone-group-id :all
                    :items [{:id "c" :type :preset :zone-group-id :crowd} {:id "d" :type :preset}]}
                   {:id "k" :type :group :track-id "t"
                    :items [{:id "e" :type :preset :zone-group-id [:auto/left :graphics]}]}]}))
      "items, groups, clips in a group clip, tracks and folders; an item's All stays All"))

(def ^:private section-8-3-before
  {:destination-zone {:zone-group-id :all}
   :items [{:type :preset :id 1
            :effects [{:effect-id :zone-selector :enabled? true :params {:target-zone :left}}
                      {:effect-id :scale :enabled? true}]}
           {:type :group :id 2 :zone-group-id :center
            :items [{:type :preset :id 3 :zone-group-id :right
                     :effects [{:effect-id :zone-selector :enabled? true :params {:target-zone :crowd}}]}]}
           {:type :preset :id 4
            :effects [{:effect-id :zone-selector :enabled? false :params {:target-zone :crowd}}]}]})

(def ^:private section-8-3-after
  {:items [{:type :preset :id 1 :target :left :effects [{:effect-id :scale :enabled? true}]}
           {:type :group :id 2 :target :center
            :items [{:type :preset :id 3 :target :right :effects []}]}
           {:type :preset :id 4 :effects []}]})

(deftest enabled-selector-becomes-the-target-test
  (is (= {:items [{:id 1 :type :preset :target :auto/left :effects [scale-fx]}]}
         (migration/cue-z4 {:items [{:id 1 :type :preset :effects [(zone-sel "z" :auto/left) scale-fx]}]}))
      "the item's own target now, and the effect goes")
  (is (= {:items [{:id 1 :type :group :target :graphics :items [] :effects []}]}
         (migration/cue-z4 {:items [{:id 1 :type :group :items [] :effects [(zone-sel "z" :graphics)]}]}))
      "a group's too")
  (is (= {:items [{:id 1 :type :preset :target :crowd :effects []}]}
         (migration/cue-z4 {:items [{:id 1 :type :preset :effects [{:id "z" :effect-id :zone-selector :params {:target-zone :crowd}}]}]}))
      "one with no :enabled? is on")
  (testing "06 §8.3's example"
    (is (= section-8-3-after (migration/cue-z4 section-8-3-before)))))

(deftest own-zone-beats-the-selector-test
  (is (= {:items [{:id 1 :type :preset :target :auto/even :effects []}]}
         (migration/cue-z4 {:items [{:id 1 :type :preset :zone-group-id :auto/even :effects [(zone-sel "z" :graphics)]}]})))
  (is (= :right (get-in (migration/cue-z4 section-8-3-before) [:items 1 :items 0 :target]))
      "as routing read it: its own zone beat its selector"))

(deftest first-enabled-selector-wins-test
  (let [target #(get-in (migration/cue-z4 {:items [{:id 1 :type :preset :effects %}]}) [:items 0 :target])]
    (is (= :auto/center (target [(zone-sel "a" :crowd false) (zone-sel "b" :auto/center)])) "the first one on")
    (is (= :auto/right (target [(zone-sel "a" :auto/right) (zone-sel "b" :graphics)])) "not the second")
    (is (nil? (target [(zone-sel "a" nil) (zone-sel "b" :graphics)]))
        "the first one on names no zone: routing read nothing, so nothing")
    (is (nil? (target [(zone-sel "a" :crowd false)])) "one that is off never routed")))

(deftest every-selector-goes-test
  (let [cue (migration/cue-z4
             {:tracks [{:id "t" :type :track :effects [(zone-sel "tz" :crowd) scale-fx]}]
              :items [{:id 1 :type :preset :track-id "t"
                       :effects [scale-fx
                                 (zone-sel "a" :crowd false)
                                 {:id "grp" :type :group :items [(zone-sel "b" :graphics) {:id "rot" :effect-id :rotation}]}
                                 (assoc (zone-sel "c" :left) :keyframe-modulator {:enabled? true :keyframes []})]}]})]
    (is (= [scale-fx {:id "grp" :type :group :items [{:id "rot" :effect-id :rotation}]}]
           (get-in cue [:items 0 :effects]))
        "off, inside an effect group, a second one with keyframes: all gone, the rest in order")
    (is (= :left (get-in cue [:items 0 :target]))
        "the first top-level one on gave its zone, its keyframes never routed; the one in the group never did either")
    (is (= {:id "t" :type :track :effects [scale-fx]} (first (:tracks cue))) "a track's goes, and gives it no target"))
  (let [state (migration/zones-z4 {:chains {:effect-chains {[1 2] {:active? true :items [(zone-sel "e" :crowd) scale-fx]}
                                                            [2 2] {:items [scale-fx]}}}})]
    (is (= [scale-fx] (get-in state [:chains :effect-chains [1 2] :items])) "an effects-grid cell's (it never routed)")
    (is (= {:items [scale-fx]} (get-in state [:chains :effect-chains [2 2]])))))

(deftest preview-cells-take-targets-test
  (is (= [{:target nil} {:target :graphics} {:target :auto/left} {:target :auto/right}]
         (get-in (migration/zones-z4 {:config {:preview {:grid-cells [{:zone-group-id nil} {:zone-group-id :graphics}
                                                                      {:target :auto/left}
                                                                      {:target :auto/right :zone-group-id :graphics}]}}})
                 [:config :preview :grid-cells]))
      "the same key; a cell with both shows its :target, so that's what it keeps"))

(deftest z4-runs-after-z2-test
  (let [state (migration/zones-z4 migrated)
        cues (get-in state [:chains :cue-chains])]
    (is (= :auto/left (get-in cues [[0 0] :items 0 :target])) "an empty built-in Left: the automatic Left")
    (is (= :auto/center (get-in cues [[0 0] :target])) "the cue's destination, the same")
    (is (= :right (get-in cues [[0 0] :items 1 :target])) "Right (manual) stays")
    (is (= :auto/left (get-in cues [[1 0] :tracks 0 :target])))
    (is (= [] (get-in cues [[2 0] :items 0 :effects])) "its Zone Selector goes, off as it was")
    (is (= :group-balcony (get-in cues [[3 0] :target])))
    (is (not-any? #(contains? % :destination-zone) (vals cues)))
    (is (= [{:target nil} {:target :auto/left} {:target :right} {:target :auto/center}]
           (get-in state [:config :preview :grid-cells])))))

(deftest migration-is-idempotent-test
  (let [once (migration/zones-z4 migrated)]
    (is (identical? once (migration/zones-z4 once)) "twice is once")
    (is (= section-8-3-after (migration/cue-z4 (migration/cue-z4 section-8-3-before)))))
  (let [fresh (domains/build-initial-state)]
    (is (identical? fresh (migration/zones-z4 fresh)) "a new show has nothing to convert")
    (is (identical? fresh (migration/timeline-shows fresh)) "nor any timeline to move"))
  (let [old (assoc-in (domains/build-initial-state) [:chains :cue-chains [1 1]]
                      {:editor-type :timeline :tracks [{:id "t" :type :track}] :items []})
        once (migration/timeline-shows old)]
    (is (= 1 (count (get-in once [:chains :shows]))))
    (is (identical? once (migration/timeline-shows once)) "timeline-shows twice is once"))
  (let [cue {:target :zone/a :items [{:id 1 :type :preset :target :graphics :effects [scale-fx]}]}]
    (is (identical? cue (migration/cue-z4 cue)))))

(deftest pasted-old-cue-is-migrated-test
  (let [state (assoc-in (domains/build-initial-state) [:ui :clipboard] {:type :cue-chain :data section-8-3-before})
        pasted (grid/handle {:event/type :grid/paste-cell :col 3 :row 1 :state state})]
    (is (= section-8-3-after (get-in pasted [:state :chains :cue-chains [3 1]]))
        "a cue copied from an older build routes the same here")))


;; Zone patterns in a show with old hardware (plans/2026-11-23-cljfx-reactivity/28 §4.5)

(deftest mixed-show-converts-only-declared-pattern-refs-test
  (let [cue-pattern {:id "zp" :effect-id :zone-pattern :enabled? true
                     :params {:style :steps :zones [:left :right :zone/a] :from :left
                              :steps [[:left] [:left :right] []] :step-on :hit}
                     :keyframe-modulator {:enabled? true :restart-on :left
                                          :keyframes [{:position 0.0 :params {:from :right}}]}}
        cell-pattern {:id "cp" :effect-id :zone-pattern :enabled? true
                      :params {:zones [:right :left] :from :right}
                      :keyframe-modulator {:enabled? false
                                           :keyframes [{:position 0.0 :params {:steps [[:left] [:right]]}}
                                                       {:position 1.0 :params {:from :left}}]}}
        anchor {:id "an" :effect-id :scale :enabled? true :params {:anchor :left}}
        cycle {:id "mc" :effect-id :zone-map-cycle :enabled? true :params {:maps [:left :map/odd-even]}}
        state (-> (domains/build-initial-state)
                  (merge old-hardware)
                  (assoc-in [:chains :cue-chains] {[0 0] {:items [{:id "i1" :type :preset :preset-id :circle
                                                                   :effects [(zone-sel "z1" :left)]}]
                                                          :zone-patterns [cue-pattern]}})
                  (assoc-in [:chains :effect-chains] {[1 1] {:active? false
                                                             :items [(zone-sel "z2" :left) cell-pattern anchor cycle]}}))
        convert #(-> % (migration/zones-z2-show old-hardware) (migration/zones-z4))
        once (convert state)
        cue (get-in once [:chains :cue-chains [0 0]])
        cell (get-in once [:chains :effect-chains [1 1] :items])]
    (testing "the empty built-in Left is the automatic Left in every place, source and step; Right (manual) stays"
      (is (= {:style :steps :zones [:auto/left :right :zone/a] :from :auto/left
              :steps [[:auto/left] [:auto/left :right] []] :step-on :hit}
             (get-in cue [:zone-patterns 0 :params])))
      (is (= [{:position 0.0 :params {:from :right}}] (get-in cue [:zone-patterns 0 :keyframe-modulator :keyframes])))
      (is (= {:zones [:right :auto/left] :from :right} (:params (first cell))))
      (is (= [{:position 0.0 :params {:steps [[:auto/left] [:right]]}} {:position 1.0 :params {:from :auto/left}}]
             (get-in (first cell) [:keyframe-modulator :keyframes])))
      (is (= "Right (manual)" (get-in once [:zone-groups :right :name])))
      (is (not (contains? (:zone-groups once) :left))))
    (testing "the old Zone Selector: the item's target now, and gone; the effects grid's goes"
      (is (= :auto/left (get-in cue [:items 0 :target])))
      (is (= [] (get-in cue [:items 0 :effects])))
      (is (= ["cp" "an" "mc"] (mapv :id cell))))
    (testing "the same keyword where no pattern stores a place stays"
      (is (= anchor (second cell)) "another effect's params")
      (is (= cycle (nth cell 2)) "a Map cycle's maps are map ids")
      (is (= :left (get-in cue [:zone-patterns 0 :keyframe-modulator :restart-on])) "a keyframe animation's setting"))
    (is (= once (convert once)) "converting again changes nothing")))


;; Timeline shows leave the grid (plans/2026-11-25-timeline-shows/04 §4.3, §7.1)

(defn- counter-ids
  "04 §7.1's id-fn: u1, u2, … in call order."
  []
  (let [n (atom 0)] #(java.util.UUID. 0 (swap! n inc))))

(defn- u [n] (java.util.UUID. 0 n))

(defn- move [state] (migration/timeline-shows state {:id-fn (counter-ids)}))

(defn- clip [id len & {:as more}]
  (merge {:id id :type :preset :preset-id :circle :track-id "t" :timeline/start 0.0 :timeline/duration len} more))

(def ^:private k36 {:source :midi :channel 0 :note 36})
(def ^:private k37 {:source :midi :channel 0 :note 37})
(def ^:private k38 {:source :midi :channel 0 :note 38})
(def ^:private kk {:source :keyboard :id :k})

(def ^:private case-a
  {:chains {:cue-chains {[0 0] {:items [{:id "i1" :type :preset :preset-id :circle}]}} :shows {}}
   :timing {:bpm 120.0}})

(def ^:private case-b
  {:timing {:bpm 120.0}
   :grid {:column-targets {2 :graphics}}
   :chains {:cue-chains {[0 0] {:items [{:id "i1" :type :preset :preset-id :circle}]}
                         [2 0] {:name "Intro" :editor-type :timeline :trigger-mode :toggle
                                :timeline/chase :mtc :timeline/tempo-bpm 128.0 :timeline/tc-start 0.0
                                :tracks [{:id "t1" :type :track :name "T" :muted? true}]
                                :items [{:id "c1" :type :preset :preset-id :circle :track-id "t1"
                                         :timeline/start 0.0 :timeline/duration 64.0}]}
                         [3 0] {:tracks [{:id "t2" :type :track}]
                                :items [{:id "c2" :type :preset :preset-id :square :track-id "t2"
                                         :timeline/start 0.0 :timeline/duration 8.0}]}}
            :shows {}}
   :config {:input {:trigger-map {k36 {:type :trigger-cue :target [2 0]}
                                  kk {:type :trigger-cue :target [3 0] :behavior :momentary}
                                  k37 {:type :trigger-cue :target [0 0]}
                                  k38 {:type :trigger-effect :target [2 0]}}}
            :vibes {:library {:vibe-1 {:hit {:cells [[2 0] [0 0]]} :accent {:cells []} :flash {:cue [3 0]} :bed nil}}}}})

(def ^:private case-c
  {:timing {:bpm 124.0}
   :chains {:cue-chains {[0 1] {:name "Drop" :editor-type :timeline :timeline/chase :mtc
                                :tracks [{:id "t" :type :track}] :items [(clip "c" 32.0)]}}}})

(def ^:private case-d
  (let [f (fn [tc-start items] (cond-> {:editor-type :timeline :timeline/chase :mtc :timeline/tempo-bpm 120.0
                                        :tracks [{:id "t" :type :track}] :items items}
                                 tc-start (assoc :timeline/tc-start tc-start)))]
    {:timing {:bpm 120.0}
     :chains {:cue-chains {[0 0] (f 0.0 [(clip "a" 64.0)])
                           [1 0] (f 16.0 [(clip "b" 64.0)])
                           [2 0] (f 32.0 [(clip "c" 64.0)])
                           [0 1] (f nil [])}
              :shows {}}}))

(def ^:private case-e
  {:timing {:bpm 120.0}
   :chains {:shows {(u 9) {:name "Set" :editor-type :timeline :timeline/chase :mtc :timeline/tempo-bpm 120.0
                           :timeline/tc-start 0.0 :items [(clip "s" 64.0)]}}
            :cue-chains {[4 2] {:items [{:id "c" :track-id "x" :timeline/start 0.0 :timeline/duration 8.0}]
                                :timeline/chase :mtc :timeline/tempo-bpm 120.0}}}
   :config {:input {:trigger-map {k36 {:type :start-timeline :target [4 2]}
                                  k37 {:type :stop-timeline :target [4 2]}}}}})

(deftest timeline-shows-examples-test
  (testing "Case A: no timelines"
    (let [out (move case-a)]
      (is (identical? case-a out))
      (is (nil? (get-in out [:ui :shows :migrated])))))
  (testing "Case B: two timelines, a column target, triggers and vibes"
    (let [out (move case-b)
          cues (get-in case-b [:chains :cue-chains])]
      (is (= {[0 0] (cues [0 0])} (get-in out [:chains :cue-chains])))
      (is (= {(u 1) (-> (cues [2 0]) (dissoc :trigger-mode) (assoc :target :graphics))
              (u 2) (assoc (cues [3 0]) :name "Timeline A4" :editor-type :timeline)}
             (get-in out [:chains :shows]))
          "u2 has no :target: column 3 has none")
      (is (true? (get-in out [:chains :shows (u 1) :tracks 0 :muted?])) "a track's live mute moves with it")
      (is (= {k36 {:type :toggle-show :target [:show (u 1)]}
              kk {:type :toggle-show :target [:show (u 2)] :behavior :momentary}
              k37 {:type :trigger-cue :target [0 0]}
              k38 {:type :trigger-effect :target [2 0]}}
             (get-in out [:config :input :trigger-map]))
          "the effects grid's [2 0] is untouched")
      (is (= {:vibe-1 {:hit {:cells [[0 0]]} :accent {:cells []} :flash {:cue nil} :bed nil}}
             (get-in out [:config :vibes :library])))
      (is (= {:moved 2 :timecode-off [] :vibe-refs-removed 2 :triggers-rewritten 2}
             (get-in out [:ui :shows :migrated])))))
  (testing "Case C: a follower without a tempo takes the file's BPM"
    (let [out (move case-c)]
      (is (= (assoc (get-in case-c [:chains :cue-chains [0 1]]) :timeline/tempo-bpm 124.0)
             (get-in out [:chains :shows (u 1)])))
      (is (= {} (get-in out [:chains :cue-chains])))
      (is (= 124.0 (get-in out [:timing :bpm])) "read, never written")
      (is (= {:moved 1 :timecode-off [] :vibe-refs-removed 0 :triggers-rewritten 0}
             (get-in out [:ui :shows :migrated])))))
  (testing "Case D: an overlap, and an empty follower"
    (let [out (move case-d)
          library (get-in out [:chains :shows])]
      (is (= ["Timeline A1" "Timeline A2" "Timeline A3" "Timeline B1"] (mapv #(:name (library (u %))) [1 2 3 4]))
          "reading order")
      (is (= [:mtc nil :mtc nil] (mapv #(:timeline/chase (library (u %))) [1 2 3 4]))
          "A2 overlaps A1; A3 touches A1's end; B1 is empty")
      (is (= {:moved 4 :timecode-off ["Timeline A2" "Timeline B1"] :vibe-refs-removed 0 :triggers-rewritten 0}
             (get-in out [:ui :shows :migrated])))))
  (testing "Case E: start and stop triggers, with shows already in the library"
    (let [out (move case-e)
          library (get-in out [:chains :shows])]
      (is (identical? (get-in case-e [:chains :shows (u 9)]) (library (u 9))) "the library's own show is left alone")
      (is (= {:name "Timeline C5" :editor-type :timeline :timeline/tempo-bpm 120.0
              :items (get-in case-e [:chains :cue-chains [4 2] :items])}
             (library (u 1)))
          "no :timeline/chase: [0,4) overlaps \"Set\" [0,32)")
      (is (= {k36 {:type :start-timeline :target [:show (u 1)]} k37 {:type :stop-timeline :target [:show (u 1)]}}
             (get-in out [:config :input :trigger-map])))
      (is (= {:moved 1 :timecode-off ["Timeline C5"] :vibe-refs-removed 0 :triggers-rewritten 2}
             (get-in out [:ui :shows :migrated]))))))

(deftest timeline-shows-is-idempotent-test
  (doseq [[label c] [["A" case-a] ["B" case-b] ["C" case-c] ["D" case-d] ["E" case-e]]]
    (let [once (move c)]
      (is (identical? once (migration/timeline-shows once)) (str "Case " label ": the second run is identical?"))
      (is (identical? once (migration/timeline-shows once {:id-fn (counter-ids)}))))))

(deftest timeline-shows-never-throws-test
  (let [good {:editor-type :timeline :name "Good" :tracks [{:id "t" :type :track}] :items [(clip "g" 4.0)]}
        unreadable {:items 5}
        state {:timing {:bpm 120.0}
               :chains {:cue-chains {[0 0] unreadable [1 0] good [:x 1] good
                                     [2 0] {:editor-type :timeline :timeline/chase :mtc :timeline/tempo-bpm "fast"
                                            :timeline/tc-start "soon" :name 42 :target #{"a"}
                                            :items [{:id "b" :timeline/start "x" :timeline/duration 4.0}]}}
                        :shows {}}}]
    (testing "a cell it can't read stays; the rest move"
      (let [out (move state)]
        (is (= #{[0 0] [:x 1]} (set (keys (get-in out [:chains :cue-chains])))))
        (is (identical? unreadable (get-in out [:chains :cue-chains [0 0]])))
        (is (= ["Good" "Timeline A3"] (mapv #(get-in out [:chains :shows (u %) :name]) [1 2])))
        (is (= ["Timeline A3"] (get-in out [:ui :shows :migrated :timecode-off])) "a range it can't read: off")))
    (testing "malformed surroundings: the state comes back, never an exception"
      (doseq [s [(assoc-in state [:chains :cue-chains] [:not :a :map])
                 (assoc-in state [:chains :shows] [:not :a :map])
                 (assoc-in state [:config :vibes :library] "nope")
                 (assoc-in state [:config :input :trigger-map] [:x])
                 (assoc-in state [:timing :bpm] nil)
                 (assoc-in state [:grid :column-targets] "x")
                 nil
                 {}]]
        (is (map? (or (move s) {})))))
    (testing "an id-fn that gives nothing usable leaves the cells in the grid"
      (is (identical? state (migration/timeline-shows state {:id-fn (constantly nil)})))
      (is (identical? state (migration/timeline-shows state {:id-fn #(throw (ex-info "no ids" {}))}))))))


;; Seeded invariants (04 §4.3 M-1, M-2, M-4, M-5, M-6, M-8); test.check isn't a dependency

(def ^:private show-binding-types #{:trigger-cue :start-timeline :stop-timeline})

(defn- rand-cue
  "One grid cell's cue: plain, a :cue-chain with tracks (not a timeline),
   or a timeline, explicit or inferred."
  [^java.util.Random rnd n]
  (let [pick #(nth % (.nextInt rnd (count %)))
        timed (fn [k] (vec (for [i (range (.nextInt rnd 3))]
                             {:id (str "c" n "-" i) :type :preset :preset-id :circle :track-id (str "t" n)
                              :timeline/start (double (.nextInt rnd 16)) :timeline/duration (double (inc (.nextInt rnd k)))})))
        timeline (cond-> {:items (timed 64)}
                   (.nextBoolean rnd) (assoc :tracks [{:id (str "t" n) :type :track}])
                   (.nextBoolean rnd) (assoc :timeline/chase :mtc)
                   (.nextBoolean rnd) (assoc :timeline/tempo-bpm (double (+ 60 (.nextInt rnd 120))))
                   (.nextBoolean rnd) (assoc :timeline/tc-start (double (.nextInt rnd 120)))
                   (.nextBoolean rnd) (assoc :name (pick ["" "  " "Song" "Intro"]))
                   (.nextBoolean rnd) (assoc :target (pick [:zone/a [] [:graphics :crowd]]))
                   (.nextBoolean rnd) (assoc :id (str "cue" n) :trigger-mode :toggle)
                   (.nextBoolean rnd) (assoc :timeline/length (double (.nextInt rnd 40))))]
    (case (.nextInt rnd 4)
      0 {:items [{:id (str "p" n) :type :preset :preset-id :circle}]}
      1 {:editor-type :cue-chain :tracks [{:id "t" :type :track}] :items [{:id (str "q" n) :timeline/start 0.0}]}
      2 (assoc timeline :editor-type :timeline)
      3 (if (or (seq (:tracks timeline)) (seq (:items timeline)))
          timeline
          (assoc timeline :items [{:id (str "i" n) :track-id "x" :timeline/start 0.0 :timeline/duration 4.0}])))))

(defn- rand-state [^java.util.Random rnd]
  (let [cells (for [c (range 5) r (range 4)] [c r])
        pick #(nth % (.nextInt rnd (count %)))
        cues (into {} (keep (fn [cell] (when (< (.nextInt rnd 3) 2) [cell (rand-cue rnd (hash cell))]))) cells)
        ;; existing enabled shows, disjoint in timecode; their ids may collide with the counter's
        existing (loop [i 0 start 0.0 acc {}]
                   (if (< i (.nextInt rnd 4))
                     (let [len (double (inc (.nextInt rnd 64)))
                           id (if (.nextBoolean rnd) (u (inc (.nextInt rnd 6))) (java.util.UUID. (.nextLong rnd) (.nextLong rnd)))
                           show (cond-> {:name (str "Old " i) :editor-type :timeline :timeline/tempo-bpm 120.0
                                         :timeline/tc-start start :items [(clip (str "o" i) len)]}
                                  (.nextBoolean rnd) (assoc :timeline/chase :mtc))]
                       (recur (inc i) (+ start (/ len 2.0) (.nextInt rnd 20)) (assoc acc id show)))
                     acc))
        trigger-map (into {} (for [i (range (.nextInt rnd 8))]
                               [{:source :midi :channel 0 :note i}
                                (cond-> {:type (pick [:trigger-cue :start-timeline :stop-timeline :trigger-effect :vibe])
                                         :target (pick cells)}
                                  (.nextBoolean rnd) (assoc :behavior (pick [:momentary :follow])))]))
        pool #(vec (distinct (repeatedly (.nextInt rnd 4) (fn [] (pick cells)))))
        vibes (into {} (for [i (range (.nextInt rnd 3))]
                         [(keyword (str "v" i)) {:hit {:cells (pool)} :accent {:cells (pool)}
                                                 :flash {:cue (when (.nextBoolean rnd) (pick cells))}
                                                 :bed (when (.nextBoolean rnd) (pick cells))}]))]
    {:timing {:bpm (double (+ 60 (.nextInt rnd 120)))}
     :grid {:column-targets (into {} (for [c (range 5) :when (.nextBoolean rnd)] [c (pick [:graphics :zone/b])]))}
     :chains {:cue-chains cues
              :effect-chains {[0 0] {:items [{:id "fx" :effect-id :scale}]}}
              :shows existing}
     :config {:input {:trigger-map trigger-map}
              :vibes {:library vibes}}}))

(defn- invariant-problems
  "What breaks M-1, M-2, M-4, M-5 or M-6 (and M-3, M-7) going from before
   to after, as strings."
  [before after]
  (let [cues (get-in before [:chains :cue-chains])
        moved (set (filter #(cue-chains/timeline-cue? (cues %)) (keys cues)))
        library (get-in before [:chains :shows])
        library' (get-in after [:chains :shows])
        added (apply dissoc library' (keys library))
        enabled (->> (vals library') (filter #(= :mtc (:timeline/chase %))) (map shows/tc-range))]
    (cond-> []
      (not= (apply dissoc cues moved) (get-in after [:chains :cue-chains]))
      (conj "M-1: a cue that isn't a timeline changed")
      (not= (get-in before [:chains :effect-chains]) (get-in after [:chains :effect-chains]))
      (conj "M-1: an effect cell changed")
      (some cue-chains/timeline-cue? (vals (get-in after [:chains :cue-chains])))
      (conj "M-2: a timeline is left in the grid")
      (not= (count added) (count moved))
      (conj "M-4: the library didn't grow by the cells moved")
      (not= library (select-keys library' (keys library)))
      (conj "M-4: a show already in the library changed")
      (some #(or (contains? % :id) (not= :timeline (:editor-type %))) (vals added))
      (conj "SH-1: a moved show has an :id, or isn't :editor-type :timeline")
      (some (fn [b] (and (show-binding-types (:type b)) (moved (:target b))))
            (vals (get-in after [:config :input :trigger-map])))
      (conj "M-5: a binding names a moved cell")
      (some #(pos? (vibe-refs/cue-ref-count after %)) moved)
      (conj "M-5: a vibe slot names a moved cell")
      (some nil? enabled)
      (conj "M-6: an enabled show has no timecode range")
      (some (fn [[a b]] (shows/tc-overlap? a b))
            (for [[i a] (map-indexed vector enabled) [j b] (map-indexed vector enabled) :when (< i j)] [a b]))
      (conj "M-6: two enabled shows overlap")
      (not= (get-in before [:timing :bpm]) (get-in after [:timing :bpm]))
      (conj "M-7: the BPM changed")
      (not (identical? after (migration/timeline-shows after)))
      (conj "M-3: a second run changed the state"))))

(deftest timeline-shows-invariants-test
  (let [seen (atom {:moved 0 :timecode-off 0 :kept-on 0 :triggers 0 :vibes 0})]
    (doseq [seed (range 200)]
      (let [before (rand-state (java.util.Random. seed))
            result (try {:after (migration/timeline-shows before {:id-fn (counter-ids)})}
                        (catch Throwable e {:thrown e}))
            problems (if-let [e (:thrown result)]
                       [(str "M-8: it threw " e)]
                       (invariant-problems before (:after result)))
            {:keys [moved timecode-off triggers-rewritten vibe-refs-removed]} (get-in result [:after :ui :shows :migrated])]
        (when (seq problems) (println "timeline-shows-invariants-test failed for seed" seed problems))
        (is (empty? problems) (str "seed " seed))
        (swap! seen #(-> %
                         (update :moved + (or moved 0))
                         (update :timecode-off + (count timecode-off))
                         (update :kept-on + (count (filter (fn [[id s]] (and (= :mtc (:timeline/chase s))
                                                                            (not (contains? (get-in before [:chains :shows]) id))))
                                                           (get-in result [:after :chains :shows]))))
                         (update :triggers + (or triggers-rewritten 0))
                         (update :vibes + (or vibe-refs-removed 0))))))
    (testing "the seeds exercise every branch"
      (is (every? pos? (vals @seen)) (pr-str @seen)))))
