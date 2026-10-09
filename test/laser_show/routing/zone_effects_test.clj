(ns laser-show.routing.zone-effects-test
  "Unit tests for zone-effects module.
   
   Tests the zone-selector effect system:
   - Zone effect identification  
   - Zone destination resolution
   - Item grouping by resolved zone"
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.routing.zone-effects :as zone-effects]
            [laser-show.animation.effects.zone :as zone]))


;; Test Fixtures

(def default-destination {:zone-group-id :left})

(defn make-timing-ctx
  "Create a timing context for testing."
  [effective-beats]
  {:effective-beats effective-beats
   :bpm 120.0
   :time-ms (long (* effective-beats 500.0))})


;; Test Items

(def item-no-effects
  "Preset with no effects - uses cue chain default"
  {:type :preset
   :id (random-uuid)
   :preset-id :circle
   :effects []
   :enabled? true})

(def item-with-zone-selector
  "Preset with zone-selector targeting :right"
  {:type :preset
   :id (random-uuid)
   :preset-id :circle
   :effects [{:effect-id :zone-selector
              :enabled? true
              :params {:target-zone :right}}]
   :enabled? true})

(def group-with-zone-selector
  "Group with zone-selector - children's zone effects should be ignored"
  {:type :group
   :id (random-uuid)
   :name "Test Group"
   :effects [{:effect-id :zone-selector
              :enabled? true
              :params {:target-zone :center}}]
   :items [{:type :preset
            :id (random-uuid)
            :preset-id :circle
            :effects [{:effect-id :zone-selector
                       :enabled? true
                       :params {:target-zone :left}}]  ;; Should be ignored
            :enabled? true}]
   :enabled? true})

(def item-disabled
  "Disabled preset - should not be included in grouping"
  {:type :preset
   :id (random-uuid)
   :preset-id :circle
   :effects [{:effect-id :zone-selector
              :enabled? true
              :params {:target-zone :right}}]
   :enabled? false})


;; zone-effect? tests

(deftest zone-effect-identification-test
  (testing "zone-effect? identifies zone-selector"
    (is (true? (zone-effects/zone-effect? {:effect-id :zone-selector}))))

  (testing "zone-effect? returns false for non-zone effects"
    (is (false? (zone-effects/zone-effect? {:effect-id :scale})))
    (is (false? (zone-effects/zone-effect? {:effect-id :translate})))
    (is (false? (zone-effects/zone-effect? {:effect-id :color-shift})))
    (is (false? (zone-effects/zone-effect? nil)))))


;; extract-zone-effects tests

(deftest extract-zone-effects-test
  (testing "Empty effects returns empty vector"
    (is (= [] (zone-effects/extract-zone-effects []))))

  (testing "Returns only enabled zone-selector effects"
    (is (= [{:effect-id :zone-selector :enabled? true :params {}}]
           (zone-effects/extract-zone-effects
            [{:effect-id :zone-selector :enabled? true :params {}}]))))

  (testing "Filters out disabled zone-selector"
    (is (= []
           (zone-effects/extract-zone-effects
            [{:effect-id :zone-selector :enabled? false :params {}}]))))

  (testing "Filters out non-zone effects"
    (is (= [{:effect-id :zone-selector :enabled? true :params {:target-zone :left}}]
           (zone-effects/extract-zone-effects
            [{:effect-id :scale :enabled? true :params {}}
             {:effect-id :zone-selector :enabled? true :params {:target-zone :left}}
             {:effect-id :translate :enabled? true :params {}}])))))


;; evaluate-zone tests

(deftest evaluate-zone-test
  (testing "Simple keyword target returns that keyword"
    (is (= :left (zone/evaluate-zone {:target-zone :left})))
    (is (= :right (zone/evaluate-zone {:target-zone :right}))))

  (testing "Missing target-zone defaults to :all"
    (is (= :all (zone/evaluate-zone {})))))


;; resolve-item-zone-destination tests

(deftest resolve-item-zone-destination-test
  (testing "Item without zone effects uses cue chain default"
    (is (= :left
           (zone-effects/resolve-item-zone-destination
            item-no-effects
            (make-timing-ctx 0.0)
            {:default-zone-id (:zone-group-id default-destination)}))))

  (testing "Item with zone-selector uses target zone"
    (is (= :right
           (zone-effects/resolve-item-zone-destination
            item-with-zone-selector
            (make-timing-ctx 0.0)
            {:default-zone-id (:zone-group-id default-destination)}))))

  (testing "Group's zone-selector applies, children's effects ignored at group level"
    (is (= :center
           (zone-effects/resolve-item-zone-destination
            group-with-zone-selector
            (make-timing-ctx 0.0)
            {:default-zone-id (:zone-group-id default-destination)})))) 

  (testing "Item with direct :zone-group-id uses that zone"
    (let [item-direct-zone {:type :preset
                            :id (random-uuid)
                            :zone-group-id :right
                            :preset-id :circle
                            :effects []
                            :enabled? true}]
      (is (= :right
             (zone-effects/resolve-item-zone-destination
              item-direct-zone
              (make-timing-ctx 0.0)
              {:default-zone-id (:zone-group-id default-destination)})))))

  (testing "Item inherits zone from parent group when item has no explicit zone"
    (let [item-child {:type :preset
                      :id (random-uuid)
                      :preset-id :circle
                      :effects []
                      :enabled? true}]
      (is (= :center
             (zone-effects/resolve-item-zone-destination
              item-child
              (make-timing-ctx 0.0)
              {:default-zone-id (:zone-group-id default-destination)
               :inherited-zone :center})))))

  (testing "Item zone override takes precedence over inherited zone"
    (let [item-override {:type :preset
                         :id (random-uuid)
                         :zone-group-id :right
                         :preset-id :circle
                         :effects []
                         :enabled? true}]
      (is (= :right
             (zone-effects/resolve-item-zone-destination
              item-override
              (make-timing-ctx 0.0)
              {:default-zone-id (:zone-group-id default-destination)
               :inherited-zone :center})))))

  (testing "Item resolves zone from track and folder hierarchy"
    (let [tracks [{:id "folder-1" :type :folder :zone-group-id :left :path []}
                  {:id "track-1" :type :track :path ["folder-1"]}]
          item-on-track {:type :preset
                         :id (random-uuid)
                         :track-id "track-1"
                         :preset-id :circle
                         :effects []
                         :enabled? true}]
      (is (= :left
             (zone-effects/resolve-item-zone-destination
              item-on-track
              (make-timing-ctx 0.0)
              {:tracks tracks
               :default-zone-id :all})))))

  (testing "Disabled zone effect is ignored"
    (let [item-disabled-effect {:type :preset
                                :id (random-uuid)
                                :effects [{:effect-id :zone-selector
                                           :enabled? false
                                           :params {:target-zone :right}}]}]
      (is (= :left
             (zone-effects/resolve-item-zone-destination
              item-disabled-effect
              (make-timing-ctx 0.0)
              {:default-zone-id (:zone-group-id default-destination)}))))))

;; resolve-track-effective-zone tests

(deftest resolve-track-effective-zone-test
  (let [tracks [{:id "folder-root" :name "Root" :zone-group-id :left :path []}
                {:id "sub-folder" :name "Sub" :path ["folder-root"]}
                {:id "track-inherited" :name "Child Track" :path ["folder-root" "sub-folder"]}
                {:id "track-override" :name "Overriding Track" :zone-group-id :right :path ["folder-root"]}
                {:id "track-no-zone" :name "Standalone Track" :path []}]]

    (testing "Returns direct track zone if set"
      (is (= :right (zone-effects/resolve-track-effective-zone "track-override" tracks))))

    (testing "Traverses parent folders to find inherited zone"
      (is (= :left (zone-effects/resolve-track-effective-zone "track-inherited" tracks))))

    (testing "Returns nil if neither track nor ancestors have zone"
      (is (nil? (zone-effects/resolve-track-effective-zone "track-no-zone" tracks))))

    (testing "Returns nil for nonexistent track id"
      (is (nil? (zone-effects/resolve-track-effective-zone "unknown" tracks))))

    (testing "Returns nil when tracks vector is empty or nil"
      (is (nil? (zone-effects/resolve-track-effective-zone "track-1" [])))
      (is (nil? (zone-effects/resolve-track-effective-zone "track-1" nil))))))


;; group-items-by-zone tests

(deftest group-items-by-zone-test
  (testing "Groups items by their resolved zone destination"
    (let [items [item-no-effects item-with-zone-selector]
          timing-ctx (make-timing-ctx 0.0)
          result (zone-effects/group-items-by-zone items timing-ctx {:default-zone-id (:zone-group-id default-destination)})]
      ;; item-no-effects → :left (default)
      ;; item-with-zone-selector → :right
      (is (= 1 (count (:left result))))
      (is (= 1 (count (:right result))))
      (is (= item-no-effects (first (:left result))))
      (is (= item-with-zone-selector (first (:right result))))))

  (testing "Item routes to single zone (no multi-zone in new system)"
    (let [items [item-with-zone-selector]
          timing-ctx (make-timing-ctx 0.0)
          result (zone-effects/group-items-by-zone items timing-ctx {:default-zone-id (:zone-group-id default-destination)})]
      ;; New system: each item routes to exactly ONE zone
      (is (= 1 (count (keys result))))
      (is (= 1 (count (:right result))))
      (is (= item-with-zone-selector (first (:right result))))))

  (testing "Disabled items are skipped"
    (let [items [item-disabled item-no-effects]
          timing-ctx (make-timing-ctx 0.0)
          result (zone-effects/group-items-by-zone items timing-ctx {:default-zone-id (:zone-group-id default-destination)})]
      (is (= 1 (count (:left result))))
      (is (= item-no-effects (first (:left result))))
      (is (nil? (:right result)))))

  (testing "Empty items returns empty map"
    (is (= {} (zone-effects/group-items-by-zone [] (make-timing-ctx 0.0) {:default-zone-id (:zone-group-id default-destination)}))))

  (testing "Group with zone-selector routes to single zone"
    (let [items [group-with-zone-selector]
          timing-ctx (make-timing-ctx 0.0)
          result (zone-effects/group-items-by-zone items timing-ctx {:default-zone-id (:zone-group-id default-destination)})]
      ;; Group routes to :center
      (is (= 1 (count (keys result))))
      (is (= 1 (count (:center result))))
      (is (= group-with-zone-selector (first (:center result)))))))
