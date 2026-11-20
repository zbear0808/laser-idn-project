(ns laser-show.state.persistent-test
  "Tests for the persistence system refactoring.
   
   Verifies that:
   - All critical state is saved
   - Files are organized correctly
   - Load/save round-trips work"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
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
                          :zone-groups []
                          :exclude-from-all? false}))


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
    
    (testing "Projectors are the rig's, not the show's: this machine had none"
      (is (= {} (state/get-in-state [:projectors]))))

    (testing "the zip still holds the projectors, as a copy"
      (let [projector (get-in (ser/load-from-zip test-project-file) ["hardware.edn" :projectors :test-proj])]
        (is (= "Test Projector" (:name projector)))
        (is (= "192.168.1.100" (:host projector)))
        (is (= [] (:zone-groups projector)))))))

(deftest test-round-trip
  (testing "Save and load preserves the show with zip format, and leaves the rig"
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
      (is (nil? (state/get-in-state [:projectors :test-proj])) "the rig the load found: none")
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

(deftest old-show-loads-clean-test
  (let [old-groups {:all {:id :all :name "All" :color "#808080"} :left {:id :left :name "Left" :color "#4A90D9"}
                    :right {:id :right :name "Right" :color "#D94A4A"} :center {:id :center :name "Center" :color "#4AD94A"}
                    :graphics {:id :graphics :name "Graphics" :color "#9B59B6"} :crowd {:id :crowd :name "Crowd Scanning" :color "#E67E22"}}
        files (-> (persistent/project-files (domains/build-initial-state))
                  (update "hardware.edn" dissoc :rig)
                  (assoc-in ["hardware.edn" :zone-groups] old-groups)
                  (assoc-in ["hardware.edn" :projectors] {:p {:name "L" :host "10.0.0.5" :service-id 1 :enabled? true
                                                              :unit-id "0A 1B" :zone-groups [:all :left]}})
                  (assoc-in ["content.edn" :chains :cue-chains] {[0 0] {:items [{:id "i" :type :preset :preset-id :circle
                                                                                :zone-group-id :center}]}})
                  (assoc-in ["project-metadata.edn" :config :preview :grid-cells]
                            [{:zone-group-id nil} {:zone-group-id :left} {:zone-group-id :right} {:zone-group-id :center}]))
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
                   (assoc-in [:ui :vibes] {:selected :vibe-2 :slot :fx :wizard {:queue [:accent] :done [:hit]}}))
          initial (get-in (domains/build-initial-state) [:ui :vibes])]
      (is (= {:selected nil :slot :hit :wizard nil} initial))
      (is (= initial (get-in (persistent/new-project-state busy) [:ui :vibes])) "File > New")
      (is (= initial (get-in (persistent/apply-loaded-project busy (persistent/project-files busy) "x.zip") [:ui :vibes]))
          "File > Open"))))

(deftest open-and-new-stop-the-test-pattern-test
  (let [testing-pattern (-> (laser-show.state.domains/build-initial-state)
                            (assoc-in [:projector-ui :calibrating-projector-id] :p)
                            (assoc-in [:projector-ui :identify :p] 12345))]
    (doseq [[label after] [["New" (persistent/new-project-state testing-pattern)]
                           ["Open" (persistent/apply-loaded-project testing-pattern {} "/tmp/show.zip")]]]
      (is (nil? (get-in after [:projector-ui :calibrating-projector-id])) label)
      (is (= {} (get-in after [:projector-ui :identify])) label))))

(deftest safety-round-trips-test
  (let [zone {:limit [-0.9 -0.8 0.9 0.95] :blocked [[-0.2 -1.0 0.2 -0.4] [0.5 0.5 0.6 0.6]]}]
    (state/init-state! (-> (domains/build-initial-state)
                           (assoc-in [:projectors :p] {:name "Laser L" :host "10.0.0.5" :zone-groups [:all]
                                                       :safety zone})))
    (persistent/save-project! test-project-file)
    (is (= zone (get-in (ser/load-from-zip test-project-file) ["hardware.edn" :projectors :p :safety]))
        "Save writes the zone into the zip's copy of the rig")
    (state/swap-state! assoc-in [:projectors :p :safety] {:limit [-0.5 -0.5 0.5 0.5] :blocked []})
    (persistent/load-project! test-project-file)
    (is (= {:limit [-0.5 -0.5 0.5 0.5] :blocked []} (state/get-in-state [:projectors :p :safety]))
        "and Open never puts it back over this machine's (D-30)")))


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

