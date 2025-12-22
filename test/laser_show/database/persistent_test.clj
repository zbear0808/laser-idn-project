(ns laser-show.database.persistent-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.database.persistent :as persist]
            [clojure.java.io :as io]))

;; Clean up test files and reset state before each test
(defn cleanup-fixture [f]
  (reset! persist/!config {:grid {:cols 8 :rows 4}
                           :window {:width 1200 :height 800}
                           :preview {:width 400 :height 400}
                           :idn {:host nil :port 7255}
                           :osc {:enabled false :port 8000}
                           :midi {:enabled false :device nil}})
  (reset! persist/!grid-assignments {})
  (reset! persist/!projectors {})
  (reset! persist/!zones {})
  (reset! persist/!zone-groups {})
  (reset! persist/!cues {})
  (reset! persist/!cue-lists {})
  (reset! persist/!effect-registry {})
  
  (f)
  
  ;; Clean up test config files
  (doseq [file (vals persist/config-files)]
    (let [f (io/file file)]
      (when (.exists f)
        (.delete f)))))

(use-fixtures :each cleanup-fixture)

;; ============================================================================
;; Config Operations Tests
;; ============================================================================

(deftest test-config-operations
  (testing "Get default config"
    (let [config (persist/get-config)]
      (is (= 8 (get-in config [:grid :cols])))
      (is (= 4 (get-in config [:grid :rows])))))
  
  (testing "Get grid config"
    (is (= {:cols 8 :rows 4} (persist/get-grid-config))))
  
  (testing "Get window config"
    (is (= {:width 1200 :height 800} (persist/get-window-config))))
  
  (testing "Update config"
    (persist/update-config! [:grid :cols] 10)
    (is (= 10 (get-in (persist/get-config) [:grid :cols])))))

;; ============================================================================
;; Grid Assignments Tests
;; ============================================================================

(deftest test-grid-assignments
  (testing "Initially no assignments"
    (is (empty? (persist/get-grid-assignments))))
  
  (testing "Set assignment"
    (persist/set-assignment! 0 0 :circle)
    (is (= :circle (persist/get-assignment 0 0))))
  
  (testing "Get all assignments"
    (persist/set-assignment! 0 0 :circle)
    (persist/set-assignment! 1 1 :square)
    (is (= {[0 0] :circle [1 1] :square} (persist/get-grid-assignments))))
  
  (testing "Clear assignment"
    (persist/set-assignment! 0 0 :circle)
    (persist/clear-assignment! 0 0)
    (is (nil? (persist/get-assignment 0 0)))))

;; ============================================================================
;; Projector Operations Tests
;; ============================================================================

(deftest test-projector-operations
  (testing "Initially no projectors"
    (is (empty? (persist/get-projectors))))
  
  (testing "Add projector"
    (let [config {:host "192.168.1.100" :port 7255}]
      (persist/add-projector! :proj-1 config)
      (is (= config (persist/get-projector :proj-1)))))
  
  (testing "Update projector"
    (persist/add-projector! :proj-1 {:host "192.168.1.100"})
    (persist/add-projector! :proj-1 {:host "192.168.1.101"})
    (is (= {:host "192.168.1.101"} (persist/get-projector :proj-1))))
  
  (testing "Remove projector"
    (persist/add-projector! :proj-1 {:host "192.168.1.100"})
    (persist/remove-projector! :proj-1)
    (is (nil? (persist/get-projector :proj-1)))))

;; ============================================================================
;; Zone Operations Tests
;; ============================================================================

(deftest test-zone-operations
  (testing "Initially no zones"
    (is (empty? (persist/get-zones))))
  
  (testing "Add zone"
    (let [zone-config {:x 0 :y 0 :width 100 :height 100}]
      (persist/add-zone! :zone-1 zone-config)
      (is (= zone-config (persist/get-zone :zone-1)))))
  
  (testing "Remove zone"
    (persist/add-zone! :zone-1 {:x 0 :y 0})
    (persist/remove-zone! :zone-1)
    (is (nil? (persist/get-zone :zone-1)))))

;; ============================================================================
;; Zone Group Operations Tests
;; ============================================================================

(deftest test-zone-group-operations
  (testing "Initially no zone groups"
    (is (empty? (persist/get-zone-groups))))
  
  (testing "Add zone group"
    (let [group-config {:zones [:zone-1 :zone-2]}]
      (persist/add-zone-group! :group-1 group-config)
      (is (= group-config (persist/get-zone-group :group-1)))))
  
  (testing "Remove zone group"
    (persist/add-zone-group! :group-1 {:zones [:zone-1]})
    (persist/remove-zone-group! :group-1)
    (is (nil? (persist/get-zone-group :group-1)))))

;; ============================================================================
;; Cue Operations Tests
;; ============================================================================

(deftest test-cue-operations
  (testing "Initially no cues"
    (is (empty? (persist/get-cues))))
  
  (testing "Add cue"
    (let [cue-def {:animation :circle :duration 1000}]
      (persist/add-cue! :cue-1 cue-def)
      (is (= cue-def (persist/get-cue :cue-1)))))
  
  (testing "Remove cue"
    (persist/add-cue! :cue-1 {:animation :circle})
    (persist/remove-cue! :cue-1)
    (is (nil? (persist/get-cue :cue-1)))))

;; ============================================================================
;; Cue List Operations Tests
;; ============================================================================

(deftest test-cue-list-operations
  (testing "Initially no cue lists"
    (is (empty? (persist/get-cue-lists))))
  
  (testing "Add cue list"
    (let [cue-list [:cue-1 :cue-2 :cue-3]]
      (persist/add-cue-list! :list-1 cue-list)
      (is (= cue-list (persist/get-cue-list :list-1)))))
  
  (testing "Remove cue list"
    (persist/add-cue-list! :list-1 [:cue-1])
    (persist/remove-cue-list! :list-1)
    (is (nil? (persist/get-cue-list :list-1)))))

;; ============================================================================
;; Persistence Tests
;; ============================================================================

(deftest test-save-and-load-config
  (testing "Save and load config"
    (persist/update-config! [:grid :cols] 12)
    (persist/save-config!)
    
    ;; Verify file exists
    (is (.exists (io/file (:settings persist/config-files))))
    
    ;; Reset and reload
    (reset! persist/!config {:grid {:cols 8 :rows 4}
                             :window {:width 1200 :height 800}
                             :preview {:width 400 :height 400}
                             :idn {:host nil :port 7255}
                             :osc {:enabled false :port 8000}
                             :midi {:enabled false :device nil}})
    (persist/load-config!)
    
    ;; Check it was restored
    (is (= 12 (get-in (persist/get-config) [:grid :cols])))))

(deftest test-save-and-load-grid-assignments
  (testing "Save and load grid assignments"
    (persist/set-assignment! 0 0 :circle)
    (persist/set-assignment! 1 1 :square)
    (persist/save-grid-assignments!)
    
    ;; Reset and reload
    (reset! persist/!grid-assignments {})
    (persist/load-grid-assignments!)
    
    ;; Check they were restored
    (is (= :circle (persist/get-assignment 0 0)))
    (is (= :square (persist/get-assignment 1 1)))))

(deftest test-save-and-load-projectors
  (testing "Save and load projectors"
    (persist/add-projector! :proj-1 {:host "192.168.1.100"})
    (persist/save-projectors!)
    
    ;; Reset and reload
    (reset! persist/!projectors {})
    (persist/load-projectors!)
    
    ;; Check they were restored
    (is (= {:host "192.168.1.100"} (persist/get-projector :proj-1)))))

(deftest test-save-and-load-all
  (testing "Save and load all persistent state"
    ;; Set various state
    (persist/update-config! [:grid :cols] 12)
    (persist/set-assignment! 2 3 :triangle)
    (persist/add-projector! :proj-2 {:host "192.168.1.102"})
    (persist/add-zone! :zone-5 {:x 50 :y 50})
    
    ;; Save all
    (persist/save-all!)
    
    ;; Reset everything
    (reset! persist/!config {:grid {:cols 8 :rows 4}
                             :window {:width 1200 :height 800}
                             :preview {:width 400 :height 400}
                             :idn {:host nil :port 7255}
                             :osc {:enabled false :port 8000}
                             :midi {:enabled false :device nil}})
    (reset! persist/!grid-assignments {})
    (reset! persist/!projectors {})
    (reset! persist/!zones {})
    
    ;; Load all
    (persist/load-all!)
    
    ;; Verify all restored
    (is (= 12 (get-in (persist/get-config) [:grid :cols])))
    (is (= :triangle (persist/get-assignment 2 3)))
    (is (= {:host "192.168.1.102"} (persist/get-projector :proj-2)))
    (is (= {:x 50 :y 50} (persist/get-zone :zone-5)))))
