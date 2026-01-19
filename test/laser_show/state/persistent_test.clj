(ns laser-show.state.persistent-test
  "Tests for the persistence system refactoring.
   
   Verifies that:
   - All critical state is saved
   - Files are organized correctly
   - Load/save round-trips work"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.serialization :as ser]
            [clojure.java.io :as io]))


;; Test Fixtures


(def test-project-file "test-output/test-project.zip")

(defn cleanup-test-files [f]
  (let [file (io/file test-project-file)
        dir (io/file "test-output")]
    (when (.exists file)
      (.delete file))
    (when-not (.exists dir)
      (.mkdirs dir)))
  (f)
  (let [file (io/file test-project-file)]
    (when (.exists file)
      (.delete file))))

(use-fixtures :each cleanup-test-files)


;; Helper Functions


(defn init-test-state! []
  (state/init-state! (domains/build-initial-state))
  
  ;; Add some test data
  (state/assoc-in-state! [:timing :bpm] 140.0)
  (state/assoc-in-state! [:grid :size] [8 4])
  
  ;; Add a test cue chain
  (state/assoc-in-state! [:chains :cue-chains [0 0] :items]
                         [{:type :preset
                           :id #uuid "11111111-1111-1111-1111-111111111111"
                           :preset-id :test-preset
                           :params {:size 1.0}
                           :effects []
                           :enabled? true}])
  
  ;; Add a test effect chain
  (state/assoc-in-state! [:chains :effect-chains [0 0] :items]
                         [{:type :effect
                           :id #uuid "22222222-2222-2222-2222-222222222222"
                           :effect-id :test-effect
                           :params {:amount 0.5}
                           :enabled? true}])
  
  ;; Add a test projector (with zone-groups directly assigned)
  (state/assoc-in-state! [:projectors :items :test-proj]
                         {:name "Test Projector"
                          :host "192.168.1.100"
                          :port 7255
                          :enabled? true
                          :zone-groups [:all]}))


;; Tests


(deftest test-file-structure
  (testing "Zip file structure uses 3 internal files"
    (is (= 3 (count persistent/zip-filenames)))
    (is (contains? persistent/zip-filenames :project-metadata))
    (is (contains? persistent/zip-filenames :hardware))
    (is (contains? persistent/zip-filenames :content))))

(deftest test-persistence-mapping
  (testing "Persistence mapping includes all critical state"
    (let [mapping persistent/persistent-state-mapping]
      
      (testing "project-metadata includes config, timing, and grid"
        (let [paths (get-in mapping [:project-metadata :paths])]
          (is (some #(= (:path %) [:config]) paths))
          (is (some #(= (:path %) [:timing]) paths))
          (is (some #(= (:path %) [:grid]) paths))))
      
      (testing "hardware includes projectors, virtual-projectors, and zone-groups"
        (let [paths (get-in mapping [:hardware :paths])]
          (is (some #(= (:path %) [:projectors :items]) paths))
          (is (some #(= (:path %) [:projectors :virtual-projectors]) paths))
          (is (some #(= (:path %) [:zone-groups]) paths))))
      
      (testing "content includes all chain types"
        (let [paths (get-in mapping [:content :paths])]
          (is (some #(= (:path %) [:chains :cue-chains]) paths))
          (is (some #(= (:path %) [:chains :effect-chains]) paths))
          (is (some #(= (:path %) [:chains :projector-effects]) paths)))))))

(deftest test-save-project
  (testing "Save project creates zip file with correct contents"
    (init-test-state!)
    
    (is (persistent/save-project! test-project-file))
    
    (testing "Zip file is created"
      (is (.exists (io/file test-project-file))))
    
    (testing "Zip contains all 3 EDN files"
      (let [zip-contents (ser/load-from-zip test-project-file)]
        (is (contains? zip-contents "project-metadata.edn"))
        (is (contains? zip-contents "hardware.edn"))
        (is (contains? zip-contents "content.edn"))))
    
    (testing "project-metadata.edn contains expected data"
      (let [zip-contents (ser/load-from-zip test-project-file)
            data (get zip-contents "project-metadata.edn")]
        (is (contains? data :config))
        (is (contains? data :timing))
        (is (= 140.0 (get-in data [:timing :bpm])))
        (is (contains? data :grid))
        (is (= [8 4] (get-in data [:grid :size])))))
    
    (testing "hardware.edn contains expected data"
      (let [zip-contents (ser/load-from-zip test-project-file)
            data (get zip-contents "hardware.edn")]
        (is (contains? data :projectors))
        (is (contains? (get-in data [:projectors :items]) :test-proj))
        (is (= [:all] (get-in data [:projectors :items :test-proj :zone-groups])))))
    
    (testing "content.edn contains expected data"
      (let [zip-contents (ser/load-from-zip test-project-file)
            data (get zip-contents "content.edn")]
        (is (contains? data :chains))
        (is (contains? (get-in data [:chains :cue-chains]) [0 0]))
        (is (= 1 (count (get-in data [:chains :cue-chains [0 0] :items]))))
        (is (contains? (get-in data [:chains :effect-chains]) [0 0]))
        (is (= 1 (count (get-in data [:chains :effect-chains [0 0] :items]))))))))

(deftest test-load-project
  (testing "Load project restores all data from zip"
    (init-test-state!)
    (persistent/save-project! test-project-file)
    
    ;; Reset state to initial
    (state/reset-state! (domains/build-initial-state))
    
    ;; Verify state is reset (BPM back to default, cue chains back to initial defaults)
    (is (= 120.0 (state/get-in-state [:timing :bpm])))
    (let [initial-cue (state/get-in-state [:chains :cue-chains [0 0]])]
      ;; Initial state has default cues, verify it's not our test data
      (is (not= :test-preset (get-in initial-cue [:items 0 :preset-id]))))
    
    ;; Load project
    (is (persistent/load-project! test-project-file))
    
    (testing "BPM is restored"
      (is (= 140.0 (state/get-in-state [:timing :bpm]))))
    
    (testing "Grid size is restored"
      (is (= [8 4] (state/get-in-state [:grid :size]))))
    
    (testing "Cue chains are restored"
      (let [cue-chain (state/get-in-state [:chains :cue-chains [0 0] :items])]
        (is (= 1 (count cue-chain)))
        (is (= :test-preset (get-in cue-chain [0 :preset-id])))))
    
    (testing "Effect chains are restored"
      (let [effect-chain (state/get-in-state [:chains :effect-chains [0 0] :items])]
        (is (= 1 (count effect-chain)))
        (is (= :test-effect (get-in effect-chain [0 :effect-id])))))
    
    (testing "Projectors are restored with zone groups"
      (let [projector (state/get-in-state [:projectors :items :test-proj])]
        (is (= "Test Projector" (:name projector)))
        (is (= "192.168.1.100" (:host projector)))
        (is (= [:all] (:zone-groups projector)))))))

(deftest test-round-trip
  (testing "Save and load preserves all data with zip format"
    (init-test-state!)
    
    ;; Capture original state
    (let [original-bpm (state/get-in-state [:timing :bpm])
          original-cues (state/get-in-state [:chains :cue-chains [0 0] :items])
          original-effects (state/get-in-state [:chains :effect-chains [0 0] :items])
          original-projector (state/get-in-state [:projectors :items :test-proj])]
      
      ;; Save and reload
      (persistent/save-project! test-project-file)
      (state/reset-state! (domains/build-initial-state))
      (persistent/load-project! test-project-file)
      
      ;; Verify everything matches
      (is (= original-bpm (state/get-in-state [:timing :bpm])))
      (is (= original-cues (state/get-in-state [:chains :cue-chains [0 0] :items])))
      (is (= original-effects (state/get-in-state [:chains :effect-chains [0 0] :items])))
      (is (= original-projector (state/get-in-state [:projectors :items :test-proj]))))))

(deftest test-obsolete-domains-removed
  (testing "Obsolete domains are not in registered domains"
    (let [domains (state/get-registered-domains)]
      (is (not (contains? domains :cues)))
      (is (not (contains? domains :cue-lists)))
      (is (not (contains? domains :effect-registry))))))
