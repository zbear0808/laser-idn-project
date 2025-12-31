(ns laser-show.state.persistent-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.state.persistent :as persist]
            [laser-show.state.core :as state]
            [laser-show.state.queries :as queries]
            [clojure.java.io :as io]))

;; Clean up test files and reset state before/after each test
(defn cleanup-fixture [f]
  ;; Ensure state is initialized
  (when-not (state/initialized?)
    (let [initial-state (require 'laser-show.state.domains)
          domains-ns (find-ns 'laser-show.state.domains)]
      (state/init-state! ((ns-resolve domains-ns 'build-initial-state)))))
  
  ;; Reset all state before test
  (state/reset-all!)
  
  (f)
  
  ;; Clean up test config files after test
  (doseq [file (vals persist/config-files)]
    (let [f (io/file file)]
      (when (.exists f)
        (.delete f))))
  
  ;; Reset state again after test
  (state/reset-all!))

(use-fixtures :each cleanup-fixture)

;; ============================================================================
;; Load/Save Single Tests
;; ============================================================================

(deftest test-save-and-load-settings
  (testing "Save and load settings config"
    ;; Modify config
    (state/update-config! [:grid :cols] 16)
    (state/update-config! [:grid :rows] 8)
    
    ;; Save settings
    (persist/save-single! :settings)
    
    ;; Verify file exists
    (is (.exists (io/file (persist/get-config-path :settings))))
    
    ;; Reset config
    (state/reset-config!)
    
    ;; Verify reset worked
    (is (= 8 (:cols (queries/grid-config))))
    
    ;; Load settings
    (persist/load-single! :settings)
    
    ;; Check it was restored
    (is (= 16 (:cols (queries/grid-config))))
    (is (= 8 (:rows (queries/grid-config))))))

(deftest test-save-and-load-grid
  (testing "Save and load grid cell assignments"
    ;; Set up some cells
    (state/set-cell-preset! 2 3 :triangle)
    (state/set-cell-preset! 5 1 :spiral)
    
    ;; Save grid
    (persist/save-single! :grid)
    
    ;; Verify file exists
    (is (.exists (io/file (persist/get-config-path :grid))))
    
    ;; Reset grid
    (state/reset-grid!)
    
    ;; Verify reset cleared our cells
    (is (= {:preset-id :circle} (queries/cell 0 0)))  ; default cell
    
    ;; Load grid
    (persist/load-single! :grid)
    
    ;; Check cells were restored
    (is (= {:preset-id :triangle} (queries/cell 2 3)))
    (is (= {:preset-id :spiral} (queries/cell 5 1)))))

(deftest test-save-and-load-projectors
  (testing "Save and load projectors"
    ;; Add a projector
    (state/add-projector! :proj-1 {:host "192.168.1.100" :port 7255})
    (state/add-projector! :proj-2 {:host "192.168.1.101" :port 7255})
    
    ;; Save projectors
    (persist/save-single! :projectors)
    
    ;; Reset projectors
    (state/reset-projectors!)
    
    ;; Verify reset
    (is (empty? (queries/projectors)))
    
    ;; Load projectors
    (persist/load-single! :projectors)
    
    ;; Check they were restored
    (is (= {:host "192.168.1.100" :port 7255} (queries/projector :proj-1)))
    (is (= {:host "192.168.1.101" :port 7255} (queries/projector :proj-2)))))

(deftest test-save-and-load-zones
  (testing "Save and load zones"
    ;; Add zones
    (state/add-zone! :zone-1 {:name "Zone 1" :projector-id :proj-1})
    (state/add-zone! :zone-2 {:name "Zone 2" :projector-id :proj-1})
    
    ;; Save zones
    (persist/save-single! :zones)
    
    ;; Reset zones
    (state/reset-zones!)
    
    ;; Verify reset
    (is (empty? (queries/zones)))
    
    ;; Load zones
    (persist/load-single! :zones)
    
    ;; Check they were restored
    (is (= "Zone 1" (:name (queries/zone :zone-1))))
    (is (= "Zone 2" (:name (queries/zone :zone-2))))))

(deftest test-save-and-load-cues
  (testing "Save and load cues"
    ;; Add cues
    (state/add-cue! :cue-1 {:name "Cue 1" :preset-id :circle})
    (state/add-cue! :cue-2 {:name "Cue 2" :preset-id :square})
    
    ;; Save cues
    (persist/save-single! :cues)
    
    ;; Reset cues
    (state/reset-cues!)
    
    ;; Verify reset
    (is (empty? (queries/cues)))
    
    ;; Load cues
    (persist/load-single! :cues)
    
    ;; Check they were restored
    (is (= "Cue 1" (:name (queries/cue :cue-1))))
    (is (= :circle (:preset-id (queries/cue :cue-1))))))

;; ============================================================================
;; Load/Save All Tests
;; ============================================================================

(deftest test-save-and-load-all
  (testing "Save and load all persistent state"
    ;; Set up various state
    (state/update-config! [:grid :cols] 12)
    (state/set-cell-preset! 3 2 :wave)
    (state/add-projector! :proj-test {:host "10.0.0.1"})
    (state/add-zone! :zone-test {:name "Test Zone"})
    (state/add-zone-group! :group-test {:name "Test Group" :zone-ids #{:zone-test}})
    (state/add-cue! :cue-test {:name "Test Cue"})
    (state/add-cue-list! :list-test {:name "Test List" :cue-ids [:cue-test]})
    
    ;; Save all
    (persist/save-to-disk!)
    
    ;; Reset everything
    (state/reset-all!)
    
    ;; Verify reset worked
    (is (= 8 (:cols (queries/grid-config))))
    (is (empty? (queries/projectors)))
    (is (empty? (queries/zones)))
    
    ;; Load all
    (persist/load-from-disk!)
    
    ;; Verify all restored
    (is (= 12 (:cols (queries/grid-config))))
    (is (= {:preset-id :wave} (queries/cell 3 2)))
    (is (= {:host "10.0.0.1"} (queries/projector :proj-test)))
    (is (= "Test Zone" (:name (queries/zone :zone-test))))
    (is (= "Test Group" (:name (queries/zone-group :group-test))))
    (is (= "Test Cue" (:name (queries/cue :cue-test))))
    (is (= "Test List" (:name (queries/cue-list :list-test))))))

;; ============================================================================
;; Utility Function Tests
;; ============================================================================

(deftest test-utility-functions
  (testing "Get config path"
    (is (= "config/settings.edn" (persist/get-config-path :settings)))
    (is (= "config/grid.edn" (persist/get-config-path :grid)))
    (is (= "config/projectors.edn" (persist/get-config-path :projectors))))
  
  (testing "Get persistent keys"
    (let [keys (persist/persistent-keys)]
      (is (contains? (set keys) :settings))
      (is (contains? (set keys) :grid))
      (is (contains? (set keys) :projectors))
      (is (contains? (set keys) :zones))
      (is (contains? (set keys) :cues)))))

;; ============================================================================
;; Edge Cases
;; ============================================================================

(deftest test-load-missing-file
  (testing "Loading missing file doesn't crash"
    ;; Ensure no files exist
    (doseq [file (vals persist/config-files)]
      (let [f (io/file file)]
        (when (.exists f) (.delete f))))
    
    ;; Should not throw - load-single! returns false when file not found
    (is (false? (persist/load-single! :settings)))
    ;; load-from-disk! returns nil but doesn't crash
    (is (nil? (persist/load-from-disk!)))))

(deftest test-partial-save-load
  (testing "Can save/load individual state types independently"
    ;; Save only projectors
    (state/add-projector! :independent {:data "test"})
    (persist/save-single! :projectors)
    
    ;; Modify something else
    (state/add-cue! :temp-cue {:name "Temp"})
    
    ;; Reset projectors
    (state/reset-projectors!)
    
    ;; Load only projectors
    (persist/load-single! :projectors)
    
    ;; Projector should be restored, cue should still be there (wasn't reset)
    (is (= {:data "test"} (queries/projector :independent)))
    (is (= "Temp" (:name (queries/cue :temp-cue))))))
