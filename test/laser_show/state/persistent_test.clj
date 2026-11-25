(ns laser-show.state.persistent-test
  "Tests for the persistence system refactoring.
   
   Verifies that:
   - All critical state is saved
   - Files are organized correctly
   - Load/save round-trips work"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.output.jar-builder :as jar]
            [laser-show.routing.legacy-shows-test :as legacy]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.rig-file :as rig-file]
            [laser-show.state.rig-store :as rig-store]
            [laser-show.state.serialization :as ser]
            [laser-show.state.templates :as templates]
            [laser-show.vibes.engine :as engine]
            [clojure.java.io :as io])
  (:import [clojure.lang ExceptionInfo]
           [java.io File IOException]
           [java.nio.file AtomicMoveNotSupportedException Files]
           [javafx.event ActionEvent]))


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


(defn test-state []
  (-> (domains/build-initial-state)
      ;; Add some test data
      (assoc-in [:timing :bpm] 140.0)
      (assoc-in [:grid :size] [8 4])
      ;; Add a test cue chain
      (assoc-in [:chains :cue-chains [0 0] :items]
                [{:type :preset
                  :id #uuid "11111111-1111-1111-1111-111111111111"
                  :preset-id :test-preset
                  :params {:size 1.0}
                  :effects []
                  :enabled? true}])
      ;; Add a test effect chain
      (assoc-in [:chains :effect-chains [0 0] :items]
                [{:type :effect
                  :id #uuid "22222222-2222-2222-2222-222222222222"
                  :effect-id :test-effect
                  :params {:amount 0.5}
                  :enabled? true}])
      ;; Add a test projector (with zone-groups directly assigned)
      ;; After flattening: :projectors IS the map of projector-id -> config
      (assoc-in [:projectors :test-proj]
                {:name "Test Projector"
                 :host "192.168.1.100"
                 :port 7255
                 :enabled? true
                 :zone-groups []
                 :exclude-from-all? false})))

(defn- save!
  "What File > Save does: write the project zip, then mark state saved."
  [state zip-path]
  (when (persistent/write-project! zip-path (persistent/project-files state))
    (persistent/mark-saved state zip-path (System/currentTimeMillis))))

(defn- open
  "What File > Open does: read the zip, then apply it over state.
   nil if the zip can't be read."
  [state zip-path]
  (when-let [contents (persistent/read-project zip-path)]
    (persistent/apply-loaded-project state contents zip-path)))


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
          (is (some #(= {:path [:chains :shows] :owner :show} %) paths) "the timeline show library")
          (is (some #(= (:path %) [:chains :effect-chains]) paths))
          (is (some #(= (:path %) [:chains :projector-effects]) paths)))))))

(deftest test-save-project
  (testing "Save project creates zip file with correct contents"
    (is (save! (test-state) test-project-file))
    
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
        (is (= [] (get-in data [:projectors :test-proj :zone-groups])))))
    
    (testing "content.edn contains expected data"
      (let [zip-contents (ser/load-from-zip test-project-file)
            data (get zip-contents "content.edn")]
        (is (contains? data :chains))
        (is (contains? (get-in data [:chains :cue-chains]) [0 0]))
        (is (= 1 (count (get-in data [:chains :cue-chains [0 0] :items]))))
        (is (contains? (get-in data [:chains :effect-chains]) [0 0]))
        (is (= 1 (count (get-in data [:chains :effect-chains [0 0] :items]))))))))

(deftest test-load-project
  (testing "Load project restores the show from zip; this machine's rig stays as it is"
    (save! (test-state) test-project-file)
    
    ;; Reset state to initial
    (let [initial (domains/build-initial-state)]
      
      ;; Verify state is reset (BPM back to default, cue chains back to initial defaults)
      (is (= 120.0 (get-in initial [:timing :bpm])))
      (let [initial-cue (get-in initial [:chains :cue-chains [0 0]])]
        ;; Initial state has default cues, verify it's not our test data
        (is (not= :test-preset (get-in initial-cue [:items 0 :preset-id]))))
      
      ;; Load project
      (let [loaded (open initial test-project-file)]
        (is loaded)
        
        (testing "BPM is restored"
          (is (= 140.0 (get-in loaded [:timing :bpm]))))
        
        (testing "Grid size is restored"
          (is (= [8 4] (get-in loaded [:grid :size]))))
        
        (testing "Cue chains are restored"
          (let [cue-chain (get-in loaded [:chains :cue-chains [0 0] :items])]
            (is (= 1 (count cue-chain)))
            (is (= :test-preset (get-in cue-chain [0 :preset-id])))))
        
        (testing "Effect chains are restored"
          (let [effect-chain (get-in loaded [:chains :effect-chains [0 0] :items])]
            (is (= 1 (count effect-chain)))
            (is (= :test-effect (get-in effect-chain [0 :effect-id])))))
        
        (testing "Projectors are the rig's, not the show's: this machine had none"
          (is (= {} (get-in loaded [:projectors]))))))

    (testing "the zip still holds the projectors, as a copy"
      (let [projector (get-in (ser/load-from-zip test-project-file) ["hardware.edn" :projectors :test-proj])]
        (is (= "Test Projector" (:name projector)))
        (is (= "192.168.1.100" (:host projector)))
        (is (= [] (:zone-groups projector)))))))

(deftest test-round-trip
  (testing "Save and load preserves the show with zip format, and leaves the rig"
    (let [state (test-state)
          ;; Capture original state
          ;; After flattening: :projectors IS the map directly (no :items wrapper)
          original-bpm (get-in state [:timing :bpm])
          original-cues (get-in state [:chains :cue-chains [0 0] :items])
          original-effects (get-in state [:chains :effect-chains [0 0] :items])
          original-projector (get-in state [:projectors :test-proj])
          
          ;; Save and reload
          _ (save! state test-project-file)
          loaded (open (domains/build-initial-state) test-project-file)]
      
      ;; Verify everything matches
      (is (= original-bpm (get-in loaded [:timing :bpm])))
      (is (= original-cues (get-in loaded [:chains :cue-chains [0 0] :items])))
      (is (= original-effects (get-in loaded [:chains :effect-chains [0 0] :items])))
      (is (nil? (get-in loaded [:projectors :test-proj])) "the rig the load found: none")
      (is (= original-projector (get-in (ser/load-from-zip test-project-file) ["hardware.edn" :projectors :test-proj]))
          "and the zip's copy of the projector, as it was"))))

(deftest rig-is-saved-with-the-hardware-test
  (let [rig {:order [:p2 :p1] :middle :both :split-width 0.6}
        here {:order [:p9] :middle :none :split-width 0.9}
        saved (assoc (domains/build-initial-state) :rig rig)
        files (persistent/project-files saved)]
    (is (= rig (get-in files ["hardware.edn" :rig])) "in hardware.edn, beside the projectors")
    (is (persistent/write-project! test-project-file files))
    (testing "Open leaves this machine's rig as it is: the zip's is only a copy"
      (let [on-another-rig (history/mark-clean (assoc (domains/build-initial-state) :rig here))
            loaded (persistent/apply-loaded-project on-another-rig
                                                    (persistent/read-project test-project-file)
                                                    test-project-file)]
        (is (identical? (:rig on-another-rig) (:rig loaded)))
        (is (false? (get-in loaded [:project :dirty?])))))
    (testing "File > New keeps the rig"
      (is (identical? (:rig saved) (:rig (persistent/new-project-state saved)))))))

(defn- pre-z2-show-files
  "A show's files as a build from before zones Z2 saved them: hardware with
   no :rig, the six built-in groups, a projector listing All and Left, a cue
   and preview cells routed by :zone-group-id."
  []
  (let [old-groups {:all {:id :all :name "All" :color "#808080"} :left {:id :left :name "Left" :color "#4A90D9"}
                    :right {:id :right :name "Right" :color "#D94A4A"} :center {:id :center :name "Center" :color "#4AD94A"}
                    :graphics {:id :graphics :name "Graphics" :color "#9B59B6"} :crowd {:id :crowd :name "Crowd Scanning" :color "#E67E22"}}]
    (-> (persistent/project-files (domains/build-initial-state))
        (update "hardware.edn" dissoc :rig)
        (assoc-in ["hardware.edn" :zone-groups] old-groups)
        (assoc-in ["hardware.edn" :projectors] {:p {:name "L" :host "10.0.0.5" :service-id 1 :enabled? true
                                                    :unit-id "0A 1B" :zone-groups [:all :left]}})
        (assoc-in ["content.edn" :chains :cue-chains] {[0 0] {:items [{:id "i" :type :preset :preset-id :circle
                                                                      :zone-group-id :center}]}})
        (assoc-in ["project-metadata.edn" :config :preview :grid-cells]
                  [{:zone-group-id nil} {:zone-group-id :left} {:zone-group-id :right} {:zone-group-id :center}]))))

(deftest old-show-loads-clean-test
  (let [files (pre-z2-show-files)
        loaded (persistent/apply-loaded-project (domains/build-initial-state) files "old.zip")]
    (is (= {} (:projectors loaded)) "the show's projectors are a copy: this rig's stay (none here)")
    (is (= #{:left :graphics :crowd} (set (keys (:zone-groups loaded)))) "All is built in; the empty Right and Center went")
    (is (= "Left (manual)" (get-in loaded [:zone-groups :left :name])) "the show's own hardware lists Left")
    (is (= :auto/center (get-in loaded [:chains :cue-chains [0 0] :items 0 :target])) "then Z4's: it's a :target")
    (is (= [nil :left :auto/right :auto/center] (mapv :target (get-in loaded [:config :preview :grid-cells]))))
    (is (false? (get-in loaded [:project :dirty?])) "converted before the baseline: not an unsaved change")
    (is (empty? (get-in loaded [:history :undo-stack])))
    (testing "saved again, it loads as it is"
      (let [again (persistent/apply-loaded-project (domains/build-initial-state) (persistent/project-files loaded) "new.zip")]
        (is (= (select-keys loaded [:projectors :zone-groups :rig :chains]) (select-keys again [:projectors :zone-groups :rig :chains])))
        (is (= (get-in loaded [:config :preview]) (get-in again [:config :preview])))))))

(deftest test-obsolete-domains-removed
  (testing "Obsolete domains are not in registered domains"
    (let [domains (state/get-registered-domains)]
      (is (not (contains? domains :cues)))
      (is (not (contains? domains :cue-lists)))
      (is (not (contains? domains :effect-registry))))))

;; Timeline show ids (plans/2026-11-25-timeline-shows)

(def ^:private u1 #uuid "00000000-0000-0000-0000-000000000001")
(def ^:private u2 #uuid "00000000-0000-0000-0000-000000000002")
(def ^:private track-1 #uuid "00000000-0000-0000-0000-0000000000f1")
(def ^:private track-2 #uuid "00000000-0000-0000-0000-0000000000f2")

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
          ;; The grid's timeline moved into the show library (timeline shows
          ;; plan 04 §4.3), found by the name the migration gave it
          cue (some (fn [[_ show]] (when (= "Timeline A1" (:name show)) show)) (get-in loaded [:chains :shows]))]
      (is (nil? (get-in loaded [:chains :cue-chains [0 0]])) "the cell left the grid")
      (is (= 1 (get-in loaded [:ui :shows :migrated :moved])))
      (is (= 0.0 (get-in cue [:items 0 :timeline/start])))
      (is (= 4.0 (get-in cue [:items 0 :timeline/duration])))
      (is (= track-id (get-in cue [:items 0 :track-id])))
      (is (true? (get-in cue [:tracks 0 :muted?])) "old disabled track becomes muted")
      (is (false? (get-in loaded [:project :dirty?])) "loading doesn't make the project unsaved"))
    (testing "an untimed show in the library opens normalized, and clean (timeline shows plan 04 §4.2)"
      (let [saved (assoc-in (domains/build-initial-state) [:chains :shows u1] (assoc untimed :name "Untimed"))]
        (is (persistent/write-project! test-project-file (persistent/project-files saved)))
        (let [loaded (persistent/apply-loaded-project (domains/build-initial-state)
                                                      (persistent/read-project test-project-file)
                                                      test-project-file)
              show (get-in loaded [:chains :shows u1])]
          (is (= "Untimed" (:name show)))
          (is (= 0.0 (get-in show [:items 0 :timeline/start])))
          (is (= 4.0 (get-in show [:items 0 :timeline/duration])))
          (is (= track-id (get-in show [:items 0 :track-id])))
          (is (true? (get-in show [:tracks 0 :muted?])) "old disabled track becomes muted")
          (is (false? (get-in loaded [:project :dirty?])) "normalized before the saved baseline")
          (is (empty? (get-in loaded [:history :undo-stack]))))))))

;; Timeline shows (plans/2026-11-25-timeline-shows/04 §4.2, §8)

(def ^:private library
  "Two shows as the library holds them: normalized, with :show/info, and a
   muted track."
  {u1 {:name "One More Time" :editor-type :timeline
       :show/info {:artist "Daft Punk" :album "Discovery" :key "F#m" :genre "House"}
       :timeline/tempo-bpm 122.7 :timeline/tc-start 3600.0 :timeline/tc-fps 25 :timeline/chase :mtc
       :tracks [{:id track-1 :type :track :name "Track 1" :muted? true}]
       :items [{:id #uuid "00000000-0000-0000-0000-0000000000c1" :type :preset :preset-id :circle
                :track-id track-1 :timeline/start 0.0 :timeline/duration 16.0}]}
   u2 {:name "Around the World" :editor-type :timeline
       :show/info {:artist "Daft Punk"}
       :tracks [{:id track-2 :type :track :name "Track 1"}]
       :items []}})

(deftest shows-survive-save-and-load-test
  (let [saved (-> (test-state)
                  (assoc-in [:chains :shows] library)
                  history/mark-clean)
        files (persistent/project-files saved)]
    (is (= library (get-in files ["content.edn" :chains :shows])) "in content.edn, beside the grids")
    (is (persistent/write-project! test-project-file files) "#uuid keys print and read back")
    (is (= files (persistent/read-project test-project-file)))
    (let [loaded (persistent/apply-loaded-project (domains/build-initial-state)
                                                  (persistent/read-project test-project-file)
                                                  test-project-file)]
      (is (= library (get-in loaded [:chains :shows])))
      (is (every? uuid? (keys (get-in loaded [:chains :shows]))))
      (is (true? (get-in loaded [:chains :shows u1 :tracks 0 :muted?])) "a track's mute is saved, though it is live")
      (is (false? (get-in loaded [:project :dirty?])))
      (is (empty? (get-in loaded [:history :undo-stack]))))
    (testing "an older file, without the library: none, and clean"
      (let [old (update-in files ["content.edn" :chains] dissoc :shows)
            loaded (persistent/apply-loaded-project saved old "old.zip")]
        (is (not (contains? (get-in old ["content.edn" :chains]) :shows)))
        (is (= {} (get-in loaded [:chains :shows])) "the old show's library goes")
        (is (false? (get-in loaded [:project :dirty?])))))))

(deftest session-reset-keeps-show-search-test
  (testing "the Shows tab's search and sort survive New and Open; its selection, edit, manual show and banner, and the editor's views, go"
    (let [busy (-> (test-state)
                   (assoc-in [:chains :shows] library)
                   (assoc-in [:ui :shows] {:query "daft" :scope :artist :sort {:column :bpm :dir :desc}
                                           :selected #{u1} :editing {:show-id u1 :column :title :text "One"}
                                           :manual u1 :migrated {:moved 2 :timecode-off [] :vibe-refs-removed 0 :triggers-rewritten 0}})
                   (assoc-in [:ui :timeline-editor] {u1 {:selection #{} :cursor-beat 4.0}}))
          expected {:query "daft" :scope :artist :sort {:column :bpm :dir :desc}
                    :selected #{} :editing nil :manual nil :migrated nil}]
      (is (= {:query "" :scope :all :sort {:column :title :dir :asc} :selected #{} :editing nil :manual nil :migrated nil}
             (get-in (domains/build-initial-state) [:ui :shows])) "the defaults")
      (doseq [[label s] [["New" (persistent/new-project-state busy)]
                         ["Open" (persistent/apply-loaded-project busy (persistent/project-files busy) "x.zip")]]]
        (testing label
          (is (= expected (get-in s [:ui :shows])))
          (is (= {} (get-in s [:ui :timeline-editor])))))
      (testing "nothing set yet: the defaults"
        (is (= (get-in (domains/build-initial-state) [:ui :shows])
               (get-in (persistent/new-project-state (update busy :ui dissoc :shows)) [:ui :shows])))))))

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


;; The faders (plans/2026-11-18-vibe-mode/05)

(deftest new-project-has-the-master-test
  (let [fresh (persistent/new-project-state (-> (domains/build-initial-state)
                                                (assoc-in [:chains :effect-chains [4 1]] {:items [{:effect-id :scale}]})))]
    (is (= [[0 3]] (keys (get-in fresh [:chains :effect-chains]))) "File > New: the old cells go, the Master comes")
    (is (= ["Master" true] ((juxt :name :active?) (get-in fresh [:chains :effect-chains [0 3]]))))
    (is (= {} (get-in fresh [:config :input :macro-map])) "no fader learned yet")
    (is (false? (get-in fresh [:project :dirty?])))))

(deftest macro-map-survives-save-and-load-test
  (let [m {[:midi 0 7] :slider-1 [:osc "/fader/2"] :slider-2 [:gamepad 0 :trigger-left] :slider-2}
        saved (-> (with-starter-vibes)
                  (templates/apply-starter-effects)
                  (assoc-in [:config :input :macro-map] m))]
    (is (persistent/write-project! test-project-file (persistent/project-files saved)))
    (let [loaded (persistent/apply-loaded-project (domains/build-initial-state)
                                                  (persistent/read-project test-project-file)
                                                  test-project-file)]
      (is (= m (get-in loaded [:config :input :macro-map])))
      (is (= (get-in saved [:chains :effect-chains]) (get-in loaded [:chains :effect-chains])) "the Master too")
      (is (false? (get-in loaded [:project :dirty?]))))))

(deftest old-project-without-macro-map-loads-test
  (testing "invariant 7: a file from before the faders has no :macro-map, and gets no Master"
    (let [files (-> (persistent/project-files (domains/build-initial-state))
                    (assoc-in ["project-metadata.edn" :config :input] {:trigger-map {{:source :midi :channel 0 :note 36} {:type :vibe :role :hit}}}))
          loaded (persistent/apply-loaded-project (persistent/new-project-state (domains/build-initial-state)) files "old.zip")]
      (is (nil? (get-in loaded [:config :input :macro-map])) "[:config] merges shallowly: readers take nil as {}")
      (is (= 1 (count (get-in loaded [:config :input :trigger-map]))))
      (is (= {} (get-in loaded [:chains :effect-chains])) "its effects grid as it was: empty")
      (is (false? (get-in loaded [:project :dirty?]))))))

(deftest session-reset-clears-the-vibes-tab-selection-test
  (testing "the Vibes tab's selection names the old project's vibes: New and Open reset it with the rest of the session (03 §10)"
    (let [busy (-> (with-starter-vibes)
                   (assoc-in [:ui :vibes] {:selected :vibe-2 :slot :fx}))
          initial (get-in (domains/build-initial-state) [:ui :vibes])]
      (is (= {:selected nil :slot :hit} initial))
      (is (= initial (get-in (persistent/new-project-state busy) [:ui :vibes])) "File > New")
      (is (= initial (get-in (persistent/apply-loaded-project busy (persistent/project-files busy) "x.zip") [:ui :vibes]))
          "File > Open"))))

(deftest session-reset-ends-learning-test
  (testing "a learn attempt (Learn all's too) and Settings' cards are the old show's session: New and Open start them afresh (reactivity plan 24)"
    (let [busy (-> (with-starter-vibes)
                   (persistent/begin-document)
                   (assoc-in [:ui :input-learn] {:active {:learn-id #uuid "00000000-0000-0000-0000-0000000024f1"
                                                          :owner :learn-all :capture :press
                                                          :target-action {:type :vibe :role :accent}
                                                          :wizard {:queue [:accent] :done [:hit]}}
                                                 :settings {:midi {:status :timeout}}}))
          initial (get-in (domains/build-initial-state) [:ui :input-learn])]
      (is (= {:active nil :settings {}} initial))
      (is (= initial (get-in (persistent/new-project-state busy) [:ui :input-learn])) "File > New")
      (is (= initial (get-in (persistent/apply-loaded-project busy (persistent/project-files busy) "x.zip") [:ui :input-learn]))
          "File > Open")
      (is (not (re-find #"input-learn|learn-id" (pr-str (persistent/project-files busy)))) "and none of it is saved"))))

(deftest open-and-new-stop-the-test-pattern-test
  (let [testing-pattern (-> (laser-show.state.domains/build-initial-state)
                            (assoc-in [:projector-ui :calibrating-projector-id] :p)
                            (assoc-in [:projector-ui :identify :p] 12345))]
    (doseq [[label after] [["New" (persistent/new-project-state testing-pattern)]
                           ["Open" (persistent/apply-loaded-project testing-pattern
                                                                    {"content.edn" {:chains {:cue-chains {} :effect-chains {}}}}
                                                                    "/tmp/show.zip")]]]
      (is (nil? (get-in after [:projector-ui :calibrating-projector-id])) label)
      (is (= {} (get-in after [:projector-ui :identify])) label))))

(deftest safety-round-trips-test
  (let [zone {:limit [-0.9 -0.8 0.9 0.95] :blocked [[-0.2 -1.0 0.2 -0.4] [0.5 0.5 0.6 0.6]]}
        saved (save! (-> (domains/build-initial-state)
                         (assoc-in [:projectors :p] {:name "Laser L" :host "10.0.0.5" :zone-groups [:all]
                                                     :safety zone}))
                     test-project-file)]
    (is (= zone (get-in (ser/load-from-zip test-project-file) ["hardware.edn" :projectors :p :safety]))
        "Save writes the zone into the zip's copy of the rig")
    (let [loaded (open (assoc-in saved [:projectors :p :safety] {:limit [-0.5 -0.5 0.5 0.5] :blocked []})
                       test-project-file)]
      (is (= {:limit [-0.5 -0.5 0.5 0.5] :blocked []} (get-in loaded [:projectors :p :safety]))
          "and Open never puts it back over this machine's (D-30)"))))


;; The rig in its own file (zones plan ZR): Open and New are the show's

(def ^:private unit-l "0A 1B 2C 3D 4E 5F 60 71 82 93 A4 B5 C6 D7 E8 F9")

(defn- on-a-rig
  "A saved show on this machine's rig: Laser L (unit L, at .57 here) in
   Beams, a virtual projector, an order and Laser L's calibration."
  []
  (-> (with-starter-vibes)
      (assoc :projectors {:projector-10-0-0-57-1 {:name "Laser L" :host "10.0.0.57" :port 7255 :service-id 1
                                                  :unit-id unit-l :stable-id [:unit unit-l 1] :enabled? true
                                                  :zone-groups [:group-beams] :exclude-from-all? false
                                                  :safety {:limit [-0.5 -0.5 0.5 0.5] :blocked []}}})
      (assoc :virtual-projectors {:vp-1 {:name "VP" :parent-projector-id :projector-10-0-0-57-1 :zone-groups []}})
      (assoc :rig {:order [:projector-10-0-0-57-1] :middle :both :split-width 0.6})
      (assoc-in [:chains :projector-effects :projector-10-0-0-57-1] {:items [{:effect-id :rgb-curves :id #uuid "11111111-1111-1111-1111-111111111111"}]})
      (assoc-in [:zone-groups :group-beams] {:id :group-beams :name "Beams" :color "#123456"})
      (history/mark-clean)))

(defn- friday
  "friday.zip, made on another rig: Laser L is the same unit, at .5 there,
   with its own safety zone, calibration and order."
  []
  (-> (domains/build-initial-state)
      (assoc :projectors {:projector-10-0-0-5-1 {:name "Laser L (Friday)" :host "10.0.0.5" :port 7255 :service-id 1
                                                 :unit-id unit-l :stable-id [:unit unit-l 1] :enabled? true
                                                 :zone-groups [] :exclude-from-all? false
                                                 :safety {:limit [-1.0 -1.0 1.0 1.0] :blocked []}}
                          :projector-10-0-0-9-1 {:name "SR Beam" :host "10.0.0.9" :port 7255 :service-id 1
                                                 :stable-id [:host "10.0.0.9" 1] :enabled? true :zone-groups []}})
      (assoc :rig {:order [:projector-10-0-0-9-1 :projector-10-0-0-5-1] :middle :none :split-width 0.9})
      (assoc-in [:chains :projector-effects :projector-10-0-0-5-1] {:items [{:effect-id :corner-pin :id #uuid "22222222-2222-2222-2222-222222222222"}]})
      (assoc-in [:chains :cue-chains [0 0]] {:items [{:type :preset :preset-id :circle}]
                                             :destination-zone {:zone-group-id :auto.laser/projector-10-0-0-5-1}})
      (assoc-in [:timing :bpm] 128.0)
      (persistent/project-files)))

(def ^:private rig-paths [[:projectors] [:virtual-projectors] [:rig] [:chains :projector-effects]])

(deftest open-keeps-the-rig-test
  (let [here (on-a-rig)
        loaded (persistent/apply-loaded-project here (friday) "shows/friday.zip")]
    (doseq [path rig-paths]
      (is (identical? (get-in here path) (get-in loaded path)) (str path " is this machine's, as it was")))
    (is (= 128.0 (get-in loaded [:timing :bpm])) "the show is friday's")
    (is (= :auto.laser/projector-10-0-0-57-1 (get-in loaded [:chains :cue-chains [0 0] :target]))
        "its cue pinned to Laser L follows the unit to its id here")
    (is (false? (get-in loaded [:project :dirty?])) "renamed before the baseline: not unsaved")))

(deftest open-never-changes-safety-or-calibration-test
  (let [here (on-a-rig)
        loaded (persistent/apply-loaded-project here (friday) "shows/friday.zip")]
    (is (= {:limit [-0.5 -0.5 0.5 0.5] :blocked []} (get-in loaded [:projectors :projector-10-0-0-57-1 :safety]))
        "the show's full-area zone for the same unit never comes in")
    (is (= [:rgb-curves] (mapv :effect-id (get-in loaded [:chains :projector-effects :projector-10-0-0-57-1 :items])))
        "nor its calibration")
    (is (not (contains? (:projectors loaded) :projector-10-0-0-9-1)) "nor a laser this rig doesn't have")))

(deftest new-project-keeps-the-rig-test
  (let [here (assoc-in (on-a-rig) [:chains :cue-chains [3 3]] {:items [{:type :preset :preset-id :star}]})
        fresh (persistent/new-project-state here)]
    (doseq [path rig-paths]
      (is (identical? (get-in here path) (get-in fresh path)) (str path ": the same, so no engine restarts (invariant 2)")))
    (is (nil? (get-in fresh [:chains :cue-chains [3 3]])) "the show is new")
    (is (= [[0 3]] (keys (get-in fresh [:chains :effect-chains]))) "with the Master")
    (is (= {} (get-in fresh [:config :input :trigger-map])) "and nothing learned yet (S-ZR-3)")
    (is (false? (get-in fresh [:project :dirty?])))))

(deftest new-project-brings-the-rigs-groups-test
  (let [here (on-a-rig)
        fresh (persistent/new-project-state here)]
    (is (= {:id :group-beams :name "Beams" :color "#123456"} (get-in fresh [:zone-groups :group-beams]))
        "Laser L is in Beams: the group comes into the new show")
    (is (= #{:graphics :crowd :group-beams} (set (keys (:zone-groups fresh)))) "beside the default groups")
    (testing "a group no laser is in stays behind"
      (is (not (contains? (:zone-groups (persistent/new-project-state (assoc-in here [:zone-groups :group-old] {:id :group-old :name "Old"})))
                          :group-old))))
    (testing "one the open show doesn't define comes from the rig's copy"
      (let [elsewhere (-> here
                          (update :zone-groups dissoc :group-beams)
                          (assoc-in [:rig-file :group-copy :group-beams] {:id :group-beams :name "Beams (copy)" :color "#123456"}))]
        (is (= "Beams (copy)" (get-in (persistent/new-project-state elsewhere) [:zone-groups :group-beams :name])))))
    (testing "a rig group named like a default wins over it"
      (let [renamed (-> here
                        (assoc-in [:projectors :projector-10-0-0-57-1 :zone-groups] [:graphics])
                        (assoc-in [:zone-groups :graphics :name] "Gfx"))]
        (is (= "Gfx" (get-in (persistent/new-project-state renamed) [:zone-groups :graphics :name])))))))

(deftest save-still-writes-the-rig-copy-test
  (let [here (on-a-rig)
        files (persistent/project-files here)]
    (is (= (:projectors here) (get-in files ["hardware.edn" :projectors])) "an older build opens it with its setup (Q-ZR-2)")
    (is (= (:virtual-projectors here) (get-in files ["hardware.edn" :virtual-projectors])))
    (is (= (:rig here) (get-in files ["hardware.edn" :rig])))
    (is (= (get-in here [:chains :projector-effects]) (get-in files ["content.edn" :chains :projector-effects])))))

(deftest old-built-ins-are-judged-by-the-shows-own-hardware-test
  (let [old-show (fn [listing]
                   (-> (persistent/project-files (domains/build-initial-state))
                       (update "hardware.edn" dissoc :rig)
                       (assoc-in ["hardware.edn" :zone-groups] {:left {:id :left :name "Left" :color "#4A90D9"}
                                                                :right {:id :right :name "Right" :color "#D94A4A"}})
                       (assoc-in ["hardware.edn" :projectors] {:p {:name "L" :host "10.0.0.5" :zone-groups listing}})
                       (assoc-in ["content.edn" :chains :cue-chains] {[0 0] {:items [] :destination-zone {:zone-group-id :left}}
                                                                      [1 0] {:items [] :destination-zone {:zone-group-id :right}}})))
        ;; This rig's laser is in a Right of its own, from an earlier show
        here (-> (on-a-rig)
                 (assoc-in [:projectors :projector-10-0-0-57-1 :zone-groups] [:right]))
        loaded (persistent/apply-loaded-project here (old-show [:all :left]) "old.zip")]
    (is (= "Left (manual)" (get-in loaded [:zone-groups :left :name])) "its own laser was in Left, though no laser here is")
    (is (= :left (get-in loaded [:chains :cue-chains [0 0] :target])))
    (is (not (contains? (:zone-groups loaded) :right)) "nothing of its own was in Right, though a laser here is (S-ZR-7)")
    (is (= :auto/right (get-in loaded [:chains :cue-chains [1 0] :target])))
    (is (= [:right] (get-in loaded [:projectors :projector-10-0-0-57-1 :zone-groups])) "and the rig is left alone")))


;; A projector whose output driver isn't installed (output drivers OD-10)

(def ^:private nova-laser
  {:name "Nova 00123" :driver :acme/nova :driver-config {:serial "NV-00123" :output 0 :lead-ms 40}
   :stable-id [:device :acme/nova "NV-00123" 0] :enabled? true :zone-groups [] :exclude-from-all? false
   :tags #{:graphics} :scan-rate 30000 :safety {:limit [-0.9 -0.8 0.9 0.9] :blocked []}})

(deftest unknown-driver-projector-round-trips-test
  (let [state (-> (domains/build-initial-state)
                  (assoc :projectors {:projector-acme-nova-NV-00123-0 nova-laser})
                  (assoc :rig {:order [:projector-acme-nova-NV-00123-0] :middle :split :split-width 0.75}))]
    (is (nil? (get-in state [:backend :output :drivers :acme/nova])) "not installed")
    (testing "through rig.edn: written, read and applied as it was"
      (let [dir (.toFile (java.nio.file.Files/createTempDirectory "unknown-driver" (make-array java.nio.file.attribute.FileAttribute 0)))
            path (str (io/file dir "rig.edn"))]
        (try
          (rig-store/write! path (rig-file/rig-document state))
          (let [{:keys [status doc]} (rig-store/read! path)
                applied (rig-file/apply-rig-document (domains/build-initial-state) doc)]
            (is (= :ok status))
            (is (= nova-laser (get-in applied [:projectors :projector-acme-nova-NV-00123-0]))))
          (finally
            (doseq [^java.io.File f (reverse (file-seq dir))] (.delete f))))))
    (testing "through the show's zip: its copy of the rig, and set up on an empty rig from it"
      (save! state test-project-file)
      (let [hardware (get (ser/load-from-zip test-project-file) "hardware.edn")
            set-up (rig-file/set-up-from-show (domains/build-initial-state) hardware {}
                                              (rig-file/pin-map (domains/build-initial-state) hardware))]
        (is (= nova-laser (get-in hardware [:projectors :projector-acme-nova-NV-00123-0])))
        (is (= nova-laser (get-in set-up [:projectors :projector-acme-nova-NV-00123-0])))))))

(deftest zone-data-survives-save-and-load-test
  (testing "zones plan Z4: your maps, the zones' labels, the grid's column defaults and the show's"
    (let [maps {:map/custom-1 {:id :map/custom-1 :name "Crowd sweep" :lanes [:auto/left :crowd] :overrides {:zone/c :auto/center}}}
          show (-> (domains/build-initial-state)
                   (assoc :zone-maps maps)
                   (assoc-in [:zones :labels] {:zone/a "Beams"})
                   (assoc-in [:grid :column-targets] {0 :zone/a 2 [:zone/b :graphics]})
                   (assoc-in [:config :default-target] :zone/c))
          files (persistent/project-files show)
          loaded (persistent/apply-loaded-project (domains/build-initial-state) files "zones.zip")]
      (is (= {0 :zone/a 2 [:zone/b :graphics]} (get-in files ["project-metadata.edn" :grid :column-targets])))
      (is (= {0 :zone/a 2 [:zone/b :graphics]} (get-in loaded [:grid :column-targets])))
      (is (= :zone/c (get-in loaded [:config :default-target])))
      (is (= maps (:zone-maps loaded)))
      (is (= {:labels {:zone/a "Beams"}} (:zones loaded)))
      (is (= maps (get-in files ["content.edn" :zone-maps])) "with the show's content")
      (is (false? (get-in loaded [:project :dirty?])))
      (testing "a file without them: none"
        (let [old (-> files
                      (update-in ["project-metadata.edn" :grid] dissoc :column-targets)
                      (update-in ["project-metadata.edn" :config] dissoc :default-target)
                      (update "content.edn" dissoc :zone-maps :zones))
              loaded (persistent/apply-loaded-project show old "old.zip")]
          (is (= {} (get-in loaded [:grid :column-targets])))
          (is (nil? (get-in loaded [:config :default-target])))
          (is (= {} (:zone-maps loaded)))
          (is (= {:labels {}} (:zones loaded))))))))

(deftest show-reopens-on-its-map-test
  (let [show (-> (domains/build-initial-state)
                 (assoc :zone-maps {:map/custom-1 {:id :map/custom-1 :name "Mine" :lanes [:all]}})
                 (assoc-in [:zone-map :active] :map/custom-1))
        files (persistent/project-files show)]
    (is (= {:active :map/custom-1} (get-in files ["project-metadata.edn" :zone-map])) "saved, the map last used (D-14)")
    (is (= :map/custom-1 (get-in (persistent/apply-loaded-project (domains/build-initial-state) files "s.zip") [:zone-map :active])))
    (testing "a file from before: Left / Right"
      (is (= :map/left-right (get-in (persistent/apply-loaded-project show (update files "project-metadata.edn" dissoc :zone-map) "o.zip")
                                     [:zone-map :active]))))
    (testing "New Project: Left / Right"
      (is (= :map/left-right (get-in (persistent/new-project-state show) [:zone-map :active]))))
    (testing "loading it is no unsaved change, and no undo step"
      (let [loaded (persistent/apply-loaded-project (domains/build-initial-state) files "s.zip")]
        (is (false? (get-in loaded [:project :dirty?])))
        (is (empty? (get-in loaded [:history :undo-stack])))))))

(deftest pending-switch-is-not-saved-test
  (let [show (assoc-in (domains/build-initial-state) [:zone-map :pending] {:id 1 :map :map/unison :at-beat 8.0 :bar? false})
        files (persistent/project-files show)]
    (is (= {:active :map/left-right} (get-in files ["project-metadata.edn" :zone-map])) "only the map in use")
    (is (not-any? #(= :pending %) (tree-seq coll? seq files)))))

(deftest session-reset-clears-the-pending-switch-test
  (let [busy (-> (domains/build-initial-state)
                 (assoc-in [:zone-map :pending] {:id 1 :map :map/unison :at-beat 8.0 :bar? false}))]
    (is (nil? (get-in (persistent/apply-loaded-project busy (persistent/project-files busy) "x.zip") [:zone-map :pending]))
        "Open: the pending beat belongs to the old show")
    (is (nil? (get-in (persistent/new-project-state busy) [:zone-map :pending])) "New")))


;; Zones plan Z5: a cue's patterns are show content; the pads' style and the
;; cycle's pause are the session's (Z5-Q3 A)

(deftest cue-patterns-survive-save-and-load-test
  (let [pattern {:id #uuid "00000000-0000-0000-0000-0000000000a1" :effect-id :zone-pattern :enabled? true
                 :params {:style :alternate :zones [:zone/a :zone/b] :source :auto :from :all :rate 1.0 :offset 0.0
                          :step-on :hit :clock :cue :follow-pads? true :restart-on :own :path :loop :width 1 :seed 0}}
        cycle {:id #uuid "00000000-0000-0000-0000-0000000000c1" :effect-id :zone-map-cycle :enabled? true
               :params {:maps [:map/left-right :map/odd-even] :rate 0.25 :offset 0.0 :path :loop :step-on :clock}}
        show (-> (domains/build-initial-state)
                 (templates/apply-starter-cue-chains)
                 (assoc-in [:chains :cue-chains [0 0] :zone-patterns] [pattern])
                 (assoc-in [:chains :effect-chains [2 1]] {:active? false :items [cycle]})
                 (assoc-in [:config :pattern-pads] {:random {:bar? true}}))
        files (persistent/project-files show)
        loaded (persistent/apply-loaded-project (domains/build-initial-state) files "z5.zip")]
    (is (= [pattern] (get-in loaded [:chains :cue-chains [0 0] :zone-patterns])) "with the cue, through Z4's migration")
    (is (= [cycle] (get-in loaded [:chains :effect-chains [2 1] :items])))
    (is (= {:random {:bar? true}} (get-in loaded [:config :pattern-pads])) "a pad's setting is the show's")
    (is (false? (get-in loaded [:project :dirty?])))))

(defn- pinned-patterns
  "plans/2026-11-23-cljfx-reactivity/28 §7.1's pattern fields, every pin
   to pin: a cue's step grid (a rest first and last), an effect cell's
   pattern in a group, a base source and two sparse keyframes."
  [pin]
  {:cue [{:id #uuid "00000000-0000-0000-0000-0000000000b1" :effect-id :zone-pattern :enabled? false
          :params {:style :steps :from pin :zones [:zone/c pin] :steps [[] [:zone/c pin] [pin] []]}}]
   :cell [{:id #uuid "00000000-0000-0000-0000-0000000000b2" :type :group :enabled? false
           :items [{:id #uuid "00000000-0000-0000-0000-0000000000b3" :effect-id :zone-pattern :enabled? true
                    :params {:from pin}
                    :keyframe-modulator {:enabled? false
                                         :keyframes [{:position 0.0 :params {:zones [pin]}}
                                                     {:position 1.0 :params {:steps [[pin] []]}}]}}]}]})

(defn- with-pinned-patterns [state pin]
  (let [{:keys [cue cell]} (pinned-patterns pin)]
    (-> state
        (assoc-in [:chains :cue-chains [2 0]] {:items [{:type :preset :id #uuid "00000000-0000-0000-0000-0000000000b4" :preset-id :circle}]
                                               :zone-patterns cue})
        (assoc-in [:chains :effect-chains [2 0]] {:active? false :items cell}))))

(deftest pattern-pins-round-trip-across-rigs-test
  (let [rig-a (-> (domains/build-initial-state)
                  (assoc :projectors {:projector-10-0-0-5-1 {:name "Laser L" :host "10.0.0.5" :port 7255 :service-id 1
                                                             :unit-id unit-l :stable-id [:unit unit-l 1] :enabled? true
                                                             :zone-groups [] :exclude-from-all? false}})
                  (history/mark-clean))
        pin-a :auto.laser/projector-10-0-0-5-1
        pin-b :auto.laser/projector-10-0-0-57-1
        zip (fn [n] (doto (java.io.File/createTempFile (str "pattern-pins-" n) ".zip") (.deleteOnExit)))
        [zip-1 zip-2] [(zip 1) (zip 2)]
        patterns (fn [s] {:cue (get-in s [:chains :cue-chains [2 0] :zone-patterns])
                          :cell (get-in s [:chains :effect-chains [2 0] :items])})
        opened-clean (fn [here loaded label]
                       (doseq [path rig-paths]
                         (is (identical? (get-in here path) (get-in loaded path)) (str label ": " path " is this machine's")))
                       (is (empty? (get-in loaded [:history :undo-stack])) label)
                       (is (false? (get-in loaded [:project :dirty?])) (str label ": renamed before the baseline")))]
    (try
      (is (some? (save! (with-pinned-patterns rig-a pin-a) (str zip-1))) "made on rig A, where Laser L is .5")
      (let [rig-b (on-a-rig)
            on-b (open rig-b (str zip-1))]
        (testing "opened on rig B, where the same unit is .57: both grids, every keyframe, order and rests kept"
          (is (= (pinned-patterns pin-b) (patterns on-b)))
          (opened-clean rig-b on-b "on B"))
        (is (some? (save! on-b (str zip-2))) "saved again on B: its copy of the rig is B's now")
        (testing "reopened on B: as it was"
          (let [again (open rig-b (str zip-2))]
            (is (= (pinned-patterns pin-b) (patterns again)))
            (opened-clean rig-b again "again on B")))
        (testing "and opened on A again: back on the unit's id there"
          (let [back (open rig-a (str zip-2))]
            (is (= (pinned-patterns pin-a) (patterns back)))
            (opened-clean rig-a back "back on A"))))
      (finally
        (.delete zip-1)
        (.delete zip-2)))))

(deftest pattern-pads-are-not-saved-test
  (let [show (-> (domains/build-initial-state)
                 (assoc :pattern-pads {:style :chase :pending {:id 1 :style :off :at-beat 9.0 :bar? false}})
                 (assoc-in [:zone-map :cycle-paused?] true)
                 (assoc-in [:playback :pulses :zone-step] {:id 7}))
        files (persistent/project-files show)]
    (is (not-any? #{:pattern-pads} (keys (apply merge (vals files)))) "the pads' live style isn't saved: a show opens As set")
    (is (= {:active :map/left-right} (get-in files ["project-metadata.edn" :zone-map])) "nor the cycle's pause")
    (is (not-any? #(= :zone-step %) (tree-seq coll? seq files)) "nor the Zone step key's presses")
    (is (= {:style nil :pending nil} (:pattern-pads (persistent/apply-loaded-project show files "x.zip"))))))

(deftest session-reset-clears-pads-and-pause-test
  (let [busy (-> (domains/build-initial-state)
                 (assoc :pattern-pads {:style :chase :pending {:id 1 :style :off :at-beat 9.0 :bar? false}})
                 (assoc-in [:zone-map :cycle-paused?] true)
                 (assoc-in [:playback :pulses :zone-step] {:id 7}))]
    (doseq [[label s] [["Open" (persistent/apply-loaded-project busy (persistent/project-files busy) "x.zip")]
                       ["New" (persistent/new-project-state busy)]]]
      (testing label
        (is (= {:style nil :pending nil} (:pattern-pads s)) "As set again")
        (is (false? (get-in s [:zone-map :cycle-paused?])))
        (is (nil? (get-in s [:playback :pulses :zone-step])) "the Zone step key's count starts again")))))


;; Only a readable show opens, and a save replaces the file only once what it
;; wrote reads back (plans/2026-11-23-cljfx-reactivity/02)

(def ^:private empty-grids
  "content.edn of a show with both grids empty."
  {:chains {:cue-chains {} :effect-chains {}}})

(def ^:private png-bytes
  "A thumbnail's first bytes: binary, and not UTF-8."
  (byte-array (map unchecked-byte [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A 0xFF 0xFE 0x00])))

(defn- names-in [^File dir]
  (set (.list dir)))

(defn- file-bytes [path]
  (vec (Files/readAllBytes (.toPath (io/file path)))))

(deftest checked-project-files-test
  (let [good (persistent/project-files (test-state))
        refused (fn [files]
                  (try
                    (persistent/checked-project-files files)
                    ::accepted
                    (catch ExceptionInfo e
                      (select-keys (ex-data e) [:member :path]))))]
    (is (identical? good (persistent/checked-project-files good)) "a show comes back as it is")
    (is (= ::accepted (refused {"content.edn" empty-grids})) "content.edn alone")
    (is (= ::accepted (refused (assoc good "thumbnail.png" png-bytes))) "another member isn't looked at")
    (testing "content.edn is required, and holds a show"
      (doseq [[label files path]
              [["missing" (dissoc good "content.edn") []]
               ["nil" (assoc good "content.edn" nil) []]
               ["a vector" (assoc good "content.edn" []) []]
               ["no :chains" (assoc good "content.edn" {:zone-maps {}}) [:chains]]
               ["no effects grid" (update-in good ["content.edn" :chains] dissoc :effect-chains) [:chains :effect-chains]]
               ["cues in a vector" (assoc-in good ["content.edn" :chains :cue-chains] []) [:chains :cue-chains]]
               ["a cell that isn't [col row]" (assoc-in good ["content.edn" :chains :cue-chains :a] {:items []}) [:chains :cue-chains :a]]
               ["a cell that isn't a map" (assoc-in good ["content.edn" :chains :effect-chains [0 1]] [:scale]) [:chains :effect-chains [0 1]]]
               ["items that aren't maps" (assoc-in good ["content.edn" :chains :cue-chains [0 0] :items] [:circle]) [:chains :cue-chains [0 0] :items]]
               ["zone maps that aren't a map" (assoc-in good ["content.edn" :zone-maps] [:map/custom-1]) [:zone-maps]]
               ["calibration that isn't a map" (assoc-in good ["content.edn" :chains :projector-effects] []) [:chains :projector-effects]]
               ["shows in a vector" (assoc-in good ["content.edn" :chains :shows] []) [:chains :shows]]
               ["a show id that isn't a uuid" (assoc-in good ["content.edn" :chains :shows] {"abc" {:editor-type :timeline}}) [:chains :shows "abc"]]
               ["a show that isn't a map" (assoc-in good ["content.edn" :chains :shows] {u1 [:x]}) [:chains :shows u1]]
               ["a show that isn't a timeline" (assoc-in good ["content.edn" :chains :shows] {u1 {:name "x"}}) [:chains :shows u1 :editor-type]]
               ["a show's items that aren't maps" (assoc-in good ["content.edn" :chains :shows] {u1 {:editor-type :timeline :items [:circle]}}) [:chains :shows u1 :items]]
               ["a show's tracks that aren't maps" (assoc-in good ["content.edn" :chains :shows] {u1 {:editor-type :timeline :tracks {:a 1}}}) [:chains :shows u1 :tracks]]
               ["a show's info that isn't text" (assoc-in good ["content.edn" :chains :shows] {u1 {:editor-type :timeline :show/info {:artist 3}}}) [:chains :shows u1 :show/info]]
               ["a show's info that isn't a map" (assoc-in good ["content.edn" :chains :shows] {u1 {:editor-type :timeline :show/info "Daft Punk"}}) [:chains :shows u1 :show/info]]]]
        (is (= {:member "content.edn" :path path} (refused files)) label)))
    (testing "shows (timeline shows plan 04 §7.2): absent, empty, or {uuid timeline}; what normalizing fixes is accepted"
      (is (= ::accepted (refused (update-in good ["content.edn" :chains] dissoc :shows))) "absent: a file from before the library")
      (is (= ::accepted (refused (assoc-in good ["content.edn" :chains :shows] {}))))
      (is (= ::accepted (refused (assoc-in good ["content.edn" :chains :shows]
                                           {u1 {:editor-type :timeline :items [{:id "c" :timeline/start -4}]}})))
          "clip timing is normalize-timeline-cue's, and item ids may be strings")
      (is (= ::accepted (refused (assoc-in good ["content.edn" :chains :shows]
                                           {u1 {:editor-type :timeline :show/info {:artist "Daft Punk" :album "Discovery"}
                                                :tracks [{:id track-1 :type :track}] :items []}})))))
    (testing "the others may be missing; one that is there is a map, with a map (or nothing) at its paths"
      (is (= ::accepted (refused (dissoc good "hardware.edn" "project-metadata.edn"))))
      (is (= ::accepted (refused (update good "hardware.edn" dissoc :rig :zone-groups))) "an old one's paths default")
      (doseq [[label files member path]
              [["hardware.edn nil" (assoc good "hardware.edn" nil) "hardware.edn" []]
               ["hardware.edn a string" (assoc good "hardware.edn" "{:projectors {}}") "hardware.edn" []]
               ["groups in a vector" (assoc-in good ["hardware.edn" :zone-groups] [:graphics]) "hardware.edn" [:zone-groups]]
               ["settings in a vector" (assoc-in good ["project-metadata.edn" :config] []) "project-metadata.edn" [:config]]
               ["timing a number" (assoc-in good ["project-metadata.edn" :timing] 120.0) "project-metadata.edn" [:timing]]]]
        (is (= {:member member :path path} (refused files)) label)))
    (testing "apply-loaded-project checks first, so what isn't a show replaces nothing"
      (is (thrown? ExceptionInfo (persistent/apply-loaded-project (test-state) {"project-metadata.edn" {}} "x.zip"))))))

(defn- by-show-name
  "doc with its show library keyed by show name: the timeline shows Open
   moves out of the grid get a fresh id each time."
  [doc]
  (update-in doc [:chains :shows] #(into {} (map (fn [[_ s]] [(:name s) s])) %)))

(deftest checked-open-accepts-old-and-empty-shows-test
  (let [^File dir (jar/temp-dir! "checked-open")
        zip-of! (fn [file-name files]
                  (let [path (str (io/file dir file-name))]
                    (is (ser/save-to-zip! path files) "written as an older build wrote it")
                    path))]
    (try
      (testing "the six shows from before zones Z4"
        (doseq [show legacy/show-names]
          (testing show
            (let [files (legacy/show-file show)
                  read (persistent/read-project (zip-of! (str show ".zip") files))
                  opened (persistent/apply-loaded-project (legacy/rig-state) read (str show ".zip"))]
              (is (= files read) "read as written")
              (is (= (by-show-name (history/extract-document-state (legacy/open-show show)))
                     (by-show-name (history/extract-document-state opened)))
                  "and opened as legacy-shows-test opens it (moved timelines get new ids each Open)")
              (is (false? (get-in opened [:project :dirty?])))
              (is (empty? (get-in opened [:history :undo-stack])))))))
      (testing "one from before zones Z2: its hardware has no :rig"
        (let [files (pre-z2-show-files)
              read (persistent/read-project (zip-of! "pre-z2.zip" files))
              opened (persistent/apply-loaded-project (domains/build-initial-state) read "old.zip")]
          (is (= files read))
          (is (= (history/extract-document-state (persistent/apply-loaded-project (domains/build-initial-state) files "old.zip"))
                 (history/extract-document-state opened))
              "opened as old-show-loads-clean-test opens it")
          (is (= "Left (manual)" (get-in opened [:zone-groups :left :name])))
          (is (false? (get-in opened [:project :dirty?])))))
      (testing "content.edn alone, both grids empty, beside a thumbnail: an empty show, the rest as a new one has it"
        (let [path (str (jar/write-jar! (io/file dir "empty.zip") {"content.edn" (pr-str empty-grids)
                                                                    "thumbnail.png" png-bytes}))
              read (persistent/read-project path)
              here (test-state)
              opened (persistent/apply-loaded-project here read path)]
          (is (= {"content.edn" empty-grids} read))
          (is (= {} (get-in opened [:chains :cue-chains])))
          (is (= {} (get-in opened [:chains :effect-chains])))
          (is (= 120.0 (get-in opened [:timing :bpm])) "test-state's 140 BPM was the old show's")
          (is (= (get-in (domains/build-initial-state) [:grid :size]) (get-in opened [:grid :size])))
          (is (identical? (:projectors here) (:projectors opened)) "this machine's rig stays")
          (is (= path (get-in opened [:project :current-file])))
          (is (false? (get-in opened [:project :dirty?])))))
      (finally
        (jar/delete-tree! dir)))))

(deftest rejected-save-preserves-existing-archive-test
  (let [^File dir (jar/temp-dir! "rejected-save")
        path (str (io/file dir "A.zip"))
        good (persistent/project-files (test-state))
        newer (persistent/project-files (assoc-in (test-state) [:timing :bpm] 99.0))]
    (try
      (is (persistent/write-project! path good))
      (let [saved-bytes (file-bytes path)
            saved (persistent/read-project path)
            kept? (fn [label]
                    (is (= saved-bytes (file-bytes path)) (str label ": A.zip's bytes as they were"))
                    (is (= saved (persistent/read-project path)) (str label ": and its show"))
                    (is (= #{"A.zip"} (names-in dir)) (str label ": no temporary file left")))]
        (is (= good saved) "a save reads back as written")
        (testing "a Java object in any member"
          (doseq [[member at] [["project-metadata.edn" [:config :cue :on-enter]]
                               ["hardware.edn" [:projectors :test-proj :handle]]
                               ["content.edn" [:chains :effect-chains [0 0] :items 0 :params :amount]]]]
            (is (false? (persistent/write-project! path (assoc-in newer (into [member] at) (ActionEvent.)))) member)
            (kept? member)))
        (testing "a member left out"
          (is (false? (persistent/write-project! path (dissoc newer "hardware.edn"))))
          (kept? "without hardware.edn"))
        (testing "a writer that fails, having written half a file"
          (with-redefs [ser/save-to-zip! (fn [zip-path _files & _]
                                           (spit zip-path "PK half a zip")
                                           (throw (IOException. "No space left on device")))]
            (is (false? (persistent/write-project! path newer))))
          (kept? "the write threw")
          (with-redefs [ser/save-to-zip! (fn [zip-path _files & _]
                                           (spit zip-path "PK half a zip")
                                           false)]
            (is (false? (persistent/write-project! path newer))))
          (kept? "the write said it failed"))
        (testing "a writer that says it wrote the show, but wrote another, readable one"
          (let [save-to-zip! ser/save-to-zip!]
            (with-redefs [ser/save-to-zip! (fn [zip-path files & opts]
                                             (apply save-to-zip! zip-path (assoc-in files ["content.edn" :chains :cue-chains] {}) opts))]
              (is (false? (persistent/write-project! path newer)))))
          (kept? "read back as another show"))
        (testing "the move onto A.zip fails"
          (let [moves (atom 0)]
            (with-redefs-fn {#'persistent/publish-project! (fn [& _]
                                                             (swap! moves inc)
                                                             (throw (AtomicMoveNotSupportedException. "A.zip.tmp" "A.zip" "can't replace it in one step")))}
              #(is (false? (persistent/write-project! path newer))))
            (is (= 1 @moves) "written and read back: it got as far as the move"))
          (kept? "the move failed"))
        (testing "Save As to a new file, refused: there is still no file"
          (let [b (str (io/file dir "B.zip"))]
            (is (false? (persistent/write-project! b (assoc-in newer ["content.edn" :chains :cue-chains [0 0] :items 0 :params :size] (Object.)))))
            (is (not (.exists (io/file b))))
            (kept? "B.zip refused")))
        (testing "a save that reads back replaces A.zip"
          (is (persistent/write-project! path newer))
          (is (= newer (persistent/read-project path)))
          (is (= #{"A.zip"} (names-in dir)))))
      (finally
        (jar/delete-tree! dir)))))
