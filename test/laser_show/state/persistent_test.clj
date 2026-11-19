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
            [laser-show.state.templates :as templates]
            [laser-show.vibes.engine :as engine]
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
  ;; After flattening: :projectors IS the map of projector-id -> config
  (state/assoc-in-state! [:projectors :test-proj]
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
          ;; After flattening: :projectors and :virtual-projectors are separate top-level domains
          (is (some #(= (:path %) [:projectors]) paths))
          (is (some #(= (:path %) [:virtual-projectors]) paths))
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
        ;; After flattening: :projectors IS the map directly (no :items wrapper)
        (is (contains? (:projectors data) :test-proj))
        (is (= [:all] (get-in data [:projectors :test-proj :zone-groups])))))
    
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
      ;; After flattening: :projectors IS the map directly (no :items wrapper)
      (let [projector (state/get-in-state [:projectors :test-proj])]
        (is (= "Test Projector" (:name projector)))
        (is (= "192.168.1.100" (:host projector)))
        (is (= [:all] (:zone-groups projector)))))))

(deftest test-round-trip
  (testing "Save and load preserves all data with zip format"
    (init-test-state!)
    
    ;; Capture original state
    ;; After flattening: :projectors IS the map directly (no :items wrapper)
    (let [original-bpm (state/get-in-state [:timing :bpm])
          original-cues (state/get-in-state [:chains :cue-chains [0 0] :items])
          original-effects (state/get-in-state [:chains :effect-chains [0 0] :items])
          original-projector (state/get-in-state [:projectors :test-proj])]
      
      ;; Save and reload
      (persistent/save-project! test-project-file)
      (state/reset-state! (domains/build-initial-state))
      (persistent/load-project! test-project-file)
      
      ;; Verify everything matches
      (is (= original-bpm (state/get-in-state [:timing :bpm])))
      (is (= original-cues (state/get-in-state [:chains :cue-chains [0 0] :items])))
      (is (= original-effects (state/get-in-state [:chains :effect-chains [0 0] :items])))
      (is (= original-projector (state/get-in-state [:projectors :test-proj]))))))

(deftest test-obsolete-domains-removed
  (testing "Obsolete domains are not in registered domains"
    (let [domains (state/get-registered-domains)]
      (is (not (contains? domains :cues)))
      (is (not (contains? domains :cue-lists)))
      (is (not (contains? domains :effect-registry))))))

(deftest load-normalizes-timeline-cues-and-stays-clean-test
  (let [track-id #uuid "33333333-3333-3333-3333-333333333333"
        untimed {:editor-type :timeline
                 :tracks [{:id track-id :type :track :name "T" :enabled? false}]
                 :items [{:type :preset :id #uuid "44444444-4444-4444-4444-444444444444"
                          :preset-id :circle :effects [] :enabled? true}]}
        saved (assoc-in (domains/build-initial-state) [:chains :cue-chains [0 0]] untimed)]
    (is (persistent/write-project! test-project-file (persistent/project-files saved)))
    (let [loaded (persistent/apply-loaded-project (domains/build-initial-state)
                                                  (persistent/read-project test-project-file)
                                                  test-project-file)
          cue (get-in loaded [:chains :cue-chains [0 0]])]
      (is (= 0.0 (get-in cue [:items 0 :timeline/start])))
      (is (= 4.0 (get-in cue [:items 0 :timeline/duration])))
      (is (= track-id (get-in cue [:items 0 :track-id])))
      (is (true? (get-in cue [:tracks 0 :muted?])) "old disabled track becomes muted")
      (is (false? (get-in loaded [:project :dirty?])) "loading doesn't make the project unsaved"))))

(defn- with-starter-vibes []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-vibes)))

(deftest vibes-survive-save-and-load-test
  (let [saved (-> (with-starter-vibes)
                  (assoc-in [:config :vibes :settings :quick-tap-ms] 120)
                  (assoc-in [:config :vibes :library :vibe-2 :hit :mode] :sustain))]
    (is (persistent/write-project! test-project-file (persistent/project-files saved)))
    (let [loaded (persistent/apply-loaded-project (domains/build-initial-state)
                                                  (persistent/read-project test-project-file)
                                                  test-project-file)]
      (is (= (get-in saved [:config :vibes]) (get-in loaded [:config :vibes])))
      (is (false? (get-in loaded [:project :dirty?]))))))

(deftest old-project-gets-the-default-vibes-test
  (let [files (update-in (persistent/project-files (domains/build-initial-state))
                         ["project-metadata.edn" :config] dissoc :vibes)
        loaded (persistent/apply-loaded-project (with-starter-vibes) files "old.zip")]
    (is (not (contains? (get-in files ["project-metadata.edn" :config]) :vibes)) "a file from before vibes")
    (is (= (:vibes domains/config-initial) (get-in loaded [:config :vibes])) "no vibes, the default settings")
    (is (false? (get-in loaded [:project :dirty?])))))

(deftest new-project-has-starter-vibes-test
  (let [fresh (persistent/new-project-state (-> (domains/build-initial-state)
                                                (assoc-in [:config :vibes :library :mine] {:id :mine})))]
    (is (= (templates/create-starter-vibes) (get-in fresh [:config :vibes])))
    (is (false? (get-in fresh [:project :dirty?])))))

(deftest session-reset-clears-vibe-runtime-test
  (let [busy (-> (with-starter-vibes)
                 (assoc-in [:vibe-runtime :current] :vibe-2)
                 (assoc-in [:vibe-runtime :blackout?] true)
                 (assoc-in [:vibe-runtime :held :hit] {:at 1000 :src :input}))
        initial (:vibe-runtime (domains/build-initial-state))]
    (is (= initial (:vibe-runtime (persistent/new-project-state busy))) "File > New")
    (is (= initial (:vibe-runtime (persistent/apply-loaded-project busy (persistent/project-files busy) "x.zip")))
        "File > Open")))

(deftest vibe-switched-fx-cells-are-saved-off-test
  (let [fx {:items [{:effect-id :scale}] :active? false}
        cells [[0 3] [1 3] [2 3]]
        on (fn [state] (mapv #(get-in state [:chains :effect-chains % :active?]) cells))
        playing (-> (with-starter-vibes)
                    (assoc-in [:chains :effect-chains] (zipmap cells (repeat fx)))
                    (assoc-in [:config :vibes :library :vibe-2 :fx] [[0 3]])
                    (assoc-in [:config :vibes :library :vibe-2 :flash] {:cue nil :fx [[1 3]] :solo? false})
                    (assoc-in [:chains :effect-chains [2 3] :active?] true)
                    (#(:state (engine/select % {:vibe :vibe-2 :time 1000})))
                    (#(:state (engine/press % {:role :flash :time 1100}))))
        files (persistent/project-files playing)]
    (is (= [true true true] (on playing)) "the FX set's, the held flash's, and one switched on by hand")
    (is (= [false false true] (on (get files "content.edn")))
        "vibe mode's switches are the session's: after a load or New nothing would switch them off")
    (is (= [true true true] (on playing)) "the live state stays as it is")
    (testing "so a project loads with them off"
      (is (persistent/write-project! test-project-file files))
      (is (= [false false true] (on (persistent/apply-loaded-project (domains/build-initial-state)
                                                                     (persistent/read-project test-project-file)
                                                                     test-project-file)))))))
