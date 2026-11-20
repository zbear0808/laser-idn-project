(ns laser-show.events.menu-file-handlers-test
  "Tests for File menu handlers (New / Open / Save / Save As).

   Handler tests call menu/handle directly (pure: event in, effects out).
   Pipeline tests run events through the wired events/event-handler, which
   applies effects synchronously when called directly."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [laser-show.events.core :as events]
   [laser-show.events.handlers.menu :as menu]
   [laser-show.state.core :as state]
   [laser-show.state.domains :as domains]
   [laser-show.state.persistent :as persistent]
   [laser-show.state.serialization :as ser]))

(def test-file "test-output/menu-file-handlers-test.zip")

(defn- delete-test-file! []
  (let [f (io/file test-file)]
    (when (.exists f) (.delete f))))

(use-fixtures :each (fn [f]
                      (delete-test-file!)
                      (state/init-state! (domains/build-initial-state))
                      (try (f) (finally (delete-test-file!)))))

(def document-state
  (-> (domains/build-initial-state)
      (assoc-in [:timing :bpm] 140.0)
      (assoc-in [:chains :cue-chains [0 0]] {:items [{:type :preset :preset-id :circle}]})
      (assoc-in [:chains :effect-chains [1 1]] {:items [{:type :effect :effect-id :scale}]})
      (assoc-in [:projectors :proj-a] {:name "A" :host "10.0.0.1" :zone-groups [:all]})
      (assoc-in [:project :dirty?] true)))

(def runtime-state
  "Backend/runtime values that must survive New and Open."
  {:streaming-running? true
   :link {:carabiner-connected? true :link-enabled? true :link-bpm 128.0}
   :midi-devices {"Launchpad" ::device-handle}})

(defn- with-runtime [state]
  (-> state
      (assoc-in [:backend :streaming :running?] (:streaming-running? runtime-state))
      (update-in [:backend :link] merge (:link runtime-state))
      (assoc-in [:backend :input :midi :devices] (:midi-devices runtime-state))
      (assoc-in [:projector-ui :discovered-devices] [{:address "10.0.0.9"}])))

(defn- assert-runtime-kept [state]
  (is (true? (get-in state [:backend :streaming :running?])))
  (is (= 128.0 (get-in state [:backend :link :link-bpm])))
  (is (true? (get-in state [:backend :link :carabiner-connected?])))
  (is (= (:midi-devices runtime-state) (get-in state [:backend :input :midi :devices])))
  (is (= [{:address "10.0.0.9"}] (get-in state [:projector-ui :discovered-devices]))))


;; Save / Save As


(deftest save-as-result-returns-saved-state-test
  (let [result (menu/handle {:event/type :file/save-as-result
                             :file-path test-file
                             :time 1234
                             :state document-state})
        new-state (:state result)]
    (testing "state remembers the file and is clean"
      (is (= test-file (get-in new-state [:project :current-file])))
      (is (false? (get-in new-state [:project :dirty?])))
      (is (= 1234 (get-in new-state [:project :last-saved]))))
    (testing "the zip write is requested as an effect built from the state"
      (is (= test-file (get-in result [:project/save :file-path])))
      (is (= 140.0 (get-in result [:project/save :files "project-metadata.edn" :timing :bpm])))
      (is (= {:items [{:type :preset :preset-id :circle}]}
             (get-in result [:project/save :files "content.edn" :chains :cue-chains [0 0]]))))
    (testing "nothing was written by the handler itself"
      (is (not (.exists (io/file test-file)))))))

(deftest save-with-current-file-test
  (let [state (assoc-in document-state [:project :current-file] test-file)
        result (menu/handle {:event/type :file/save :time 99 :state state})]
    (is (false? (get-in result [:state :project :dirty?])))
    (is (= test-file (get-in result [:state :project :current-file])))
    (is (= test-file (get-in result [:project/save :file-path])))
    (is (not (contains? result :fx/show-file-chooser)))))

(deftest save-without-current-file-opens-save-as-test
  (let [result (menu/handle {:event/type :file/save :state document-state})]
    (is (contains? result :fx/show-file-chooser))
    (is (not (contains? result :project/save)))))

(deftest save-as-cancelled-test
  (let [result (menu/handle {:event/type :file/save-as-result
                             :file-path nil
                             :state document-state})]
    (is (= document-state (:state result)))
    (is (not (contains? result :project/save)))))

(deftest save-failed-restores-project-status-test
  (let [pipeline events/handle-event-pipeline
        saved (:state (pipeline {:event/type :file/save-as-result
                                 :file-path test-file
                                 :state document-state}))
        result (pipeline {:event/type :file/save-failed
                          :file-path test-file
                          :previous-project (:project document-state)
                          :state saved})]
    (is (false? (get-in saved [:project :dirty?])))
    (is (nil? (get-in result [:state :project :current-file])))
    (is (true? (get-in result [:state :project :dirty?])))))

(deftest failed-save-sets-a-notice-test
  (let [result (menu/handle {:event/type :file/save-failed
                             :file-path "test-output/shows/friday.zip"
                             :previous-project (:project document-state)
                             :state document-state})
        {:keys [id] :as notice} (get-in result [:state :ui :notice])]
    (is (= {:text "Could not save friday.zip" :level :error} (dissoc notice :id)))
    (is (some? id))
    (testing "it goes after 6 s, and only that notice"
      (is (= {:event {:event/type :ui/clear-notice :id id} :delay-ms 6000}
             (:dispatch-later result))))
    (testing "the project status is still restored"
      (is (true? (get-in result [:state :project :dirty?]))))))

(deftest save-as-pipeline-test
  (testing "Save As through the wired pipeline writes the zip and keeps the file/clean flag"
    (state/reset-state! document-state)
    (events/event-handler {:event/type :file/save-as-result :file-path test-file})
    (let [s (state/get-raw-state)]
      (is (.exists (io/file test-file)))
      (is (= test-file (get-in s [:project :current-file])))
      (is (false? (get-in s [:project :dirty?])))
      (is (= 140.0 (get-in (ser/load-from-zip test-file) ["project-metadata.edn" :timing :bpm]))))
    (testing "the next Save writes to the remembered file without a chooser"
      (state/swap-state! assoc-in [:timing :bpm] 150.0)
      (state/swap-state! assoc-in [:project :dirty?] true)
      (events/event-handler {:event/type :file/save})
      (is (false? (get-in (state/get-raw-state) [:project :dirty?])))
      (is (= 150.0 (get-in (ser/load-from-zip test-file) ["project-metadata.edn" :timing :bpm]))))))


;; Open


(defn- zip-contents-of [state]
  (persistent/project-files state))

(deftest open-result-requests-load-effect-test
  (let [result (menu/handle {:event/type :file/open-result
                             :file-path test-file
                             :state document-state})]
    (is (not (contains? result :state)))
    (is (= test-file (get-in result [:project/load :file-path])))
    (is (= :file/project-loaded (get-in result [:project/load :on-loaded :event/type])))))

(deftest project-loaded-replaces-document-test
  (let [loaded-doc (-> (domains/build-initial-state)
                       (assoc-in [:timing :bpm] 90.0)
                       (assoc-in [:chains :cue-chains [3 3]] {:items [{:type :preset :preset-id :star}]})
                       (assoc :zone-groups {:all {:id :all :name "All"}}))
        current (-> document-state
                    with-runtime
                    (assoc-in [:playback :active-cues [0 0]] {:trigger-time 1})
                    (assoc-in [:grid :selected-cell] [0 0])
                    (assoc-in [:history :undo-stack] [{:description "x"}]))
        result (menu/handle {:event/type :file/project-loaded
                             :file-path test-file
                             :contents (zip-contents-of loaded-doc)
                             :state current})
        new-state (:state result)]
    (testing "document is replaced, not merged"
      (is (= 90.0 (get-in new-state [:timing :bpm])))
      (is (= {[3 3] {:items [{:type :preset :preset-id :star}]}}
             (get-in new-state [:chains :cue-chains])))
      (is (= {} (get-in new-state [:chains :effect-chains])))
      (is (= {} (:projectors new-state)))
      (is (= #{:all} (set (keys (:zone-groups new-state))))))
    (testing "session state pointing at the old document is reset"
      (is (= {} (get-in new-state [:playback :active-cues])))
      (is (nil? (get-in new-state [:grid :selected-cell])))
      (is (= [] (get-in new-state [:history :undo-stack]))))
    (testing "project status"
      (is (= test-file (get-in new-state [:project :current-file])))
      (is (false? (get-in new-state [:project :dirty?]))))
    (testing "runtime/backend state is kept"
      (assert-runtime-kept new-state))
    (testing "while streaming, the new projectors are synced (through the pipeline)"
      (is (= (:projectors new-state)
             (get-in (events/handle-event-pipeline {:event/type :file/project-loaded
                                                    :file-path test-file
                                                    :contents (zip-contents-of loaded-doc)
                                                    :state current})
                     [:multi-engine/refresh :projectors]))))))

(deftest project-loaded-fills-missing-config-defaults-test
  (let [contents {"project-metadata.edn" {:config {:cue {:trigger-mode :toggle}}}}
        new-state (:state (menu/handle {:event/type :file/project-loaded
                                        :file-path test-file
                                        :contents contents
                                        :state document-state}))]
    (is (= :toggle (get-in new-state [:config :cue :trigger-mode])))
    (is (= (get-in (domains/build-initial-state) [:config :preview])
           (get-in new-state [:config :preview])))
    (is (= 120.0 (get-in new-state [:timing :bpm])))))

(deftest open-pipeline-test
  (testing "Open through the wired pipeline replaces the document and skips undo"
    (persistent/write-project! test-file (zip-contents-of document-state))
    (state/reset-state! (-> (domains/build-initial-state)
                            (assoc-in [:chains :cue-chains [7 3]] {:items [{:type :preset :preset-id :old}]})))
    (events/event-handler {:event/type :file/open-result :file-path test-file})
    (let [s (state/get-raw-state)]
      (is (= 140.0 (get-in s [:timing :bpm])))
      (is (nil? (get-in s [:chains :cue-chains [7 3]])))
      (is (= test-file (get-in s [:project :current-file])))
      (is (= [] (get-in s [:history :undo-stack]))))))

(deftest open-missing-file-keeps-state-test
  (state/reset-state! document-state)
  (events/event-handler {:event/type :file/open-result :file-path "test-output/does-not-exist.zip"})
  (is (= document-state (assoc-in (state/get-raw-state) [:ui :notice] nil))
      "only the notice changed"))

(deftest failed-open-sets-a-notice-test
  (let [result (menu/handle {:event/type :file/project-load-failed
                             :file-path "test-output/does-not-exist.zip"
                             :state document-state})
        {:keys [id] :as notice} (get-in result [:state :ui :notice])]
    (is (= {:text "Could not open does-not-exist.zip" :level :error} (dissoc notice :id)))
    (is (= {:event {:event/type :ui/clear-notice :id id} :delay-ms 6000}
           (:dispatch-later result)))
    (is (= document-state (assoc-in (:state result) [:ui :notice] nil)) "the document is kept"))
  (testing "through the pipeline, as Open does it"
    (state/reset-state! document-state)
    (events/event-handler {:event/type :file/open-result :file-path "test-output/does-not-exist.zip"})
    (is (= "Could not open does-not-exist.zip"
           (get-in (state/get-raw-state) [:ui :notice :text])))))


;; New Project


(deftest new-project-resets-document-only-test
  (let [current (-> document-state
                    with-runtime
                    (assoc-in [:project :current-file] test-file)
                    (assoc-in [:playback :active-cues [0 0]] {:trigger-time 1}))
        result (menu/handle {:event/type :file/new-project :force? true :state current})
        new-state (:state result)]
    (testing "document is fresh, with starter cues"
      (is (= 120.0 (get-in new-state [:timing :bpm])))
      (is (= {} (:projectors new-state)))
      (is (= [[0 3]] (keys (get-in new-state [:chains :effect-chains]))) "and the Master, the only effect cell")
      (is (= "Master" (get-in new-state [:chains :effect-chains [0 3] :name])))
      (is (seq (get-in new-state [:chains :cue-chains])))
      (is (not= (get-in current [:chains :cue-chains [0 0]])
                (get-in new-state [:chains :cue-chains [0 0]]))))
    (testing "project and session are reset"
      (is (nil? (get-in new-state [:project :current-file])))
      (is (false? (get-in new-state [:project :dirty?])))
      (is (= {} (get-in new-state [:playback :active-cues]))))
    (testing "runtime/backend state is kept"
      (assert-runtime-kept new-state))
    (testing "while streaming, the engines are synced to no projectors (through the pipeline)"
      (is (= {} (get-in (events/handle-event-pipeline {:event/type :file/new-project
                                                        :force? true :state current})
                        [:multi-engine/refresh :projectors]))))))

(deftest new-and-open-take-the-notice-away-test
  (let [noticed (assoc-in document-state [:ui :notice]
                          {:id (random-uuid) :text "Could not save friday.zip" :level :error})]
    (testing "File > New: the notice was about the project that went"
      (is (nil? (get-in (menu/handle {:event/type :file/new-project :force? true :state noticed})
                        [:state :ui :notice]))))
    (testing "File > Open: the old notice goes, and the new one says where the safety zones come from"
      (is (= "Safety zones and calibration come from menu-file-handlers-test.zip"
             (get-in (menu/handle {:event/type :file/project-loaded
                                   :file-path test-file
                                   :contents (zip-contents-of document-state)
                                   :state noticed})
                     [:state :ui :notice :text]))))))

(deftest open-says-where-safety-comes-from-test
  (let [open (fn [before show]
               (menu/handle {:event/type :file/project-loaded :file-path "shows/friday.zip"
                             :contents (zip-contents-of show) :state before}))
        no-projectors (assoc document-state :projectors {})]
    (testing "the show has projectors"
      (let [result (open no-projectors document-state)]
        (is (= "Safety zones and calibration come from friday.zip" (get-in result [:state :ui :notice :text])))
        (is (= :info (get-in result [:state :ui :notice :level])))
        (is (= :ui/clear-notice (get-in result [:dispatch-later :event :event/type])) "it goes after a while")))
    (testing "only this machine had projectors: they were replaced too"
      (is (some? (get-in (open document-state no-projectors) [:state :ui :notice]))))
    (testing "neither has a projector: no notice"
      (is (nil? (get-in (open no-projectors no-projectors) [:state :ui :notice]))))))


;; Unsaved-changes guard (New / Open / Exit ask first when the project is dirty)


(defn- confirm-dialog [state]
  (get-in state [:ui :dialogs :unsaved-confirm]))

(def clean-document-state
  (assoc-in document-state [:project :dirty?] false))

(def asking-to-start-over
  "A dirty project with the confirm dialog open for File > New."
  (-> document-state
      (assoc-in [:ui :dialogs :unsaved-confirm] {:open? true
                                                 :pending {:event/type :file/new-project}})))

(deftest dirty-new-project-asks-first-test
  (let [result (menu/handle {:event/type :file/new-project :state document-state})
        new-state (:state result)]
    (is (true? (:open? (confirm-dialog new-state))))
    (is (= {:event/type :file/new-project} (:pending (confirm-dialog new-state))))
    (testing "the document is untouched and nothing else is requested"
      (is (= (:chains document-state) (:chains new-state)))
      (is (= 140.0 (get-in new-state [:timing :bpm])))
      (is (true? (get-in new-state [:project :dirty?])))
      (is (= #{:state} (set (keys result)))))))

(deftest dirty-open-asks-before-the-chooser-test
  (let [result (menu/handle {:event/type :file/open :state document-state})]
    (is (true? (:open? (confirm-dialog (:state result)))))
    (is (= {:event/type :file/open} (:pending (confirm-dialog (:state result)))))
    (is (not (contains? result :fx/show-file-chooser)))))

(deftest dirty-exit-asks-first-test
  (let [result (menu/handle {:event/type :file/exit :state document-state})]
    (is (true? (:open? (confirm-dialog (:state result)))))
    (is (= {:event/type :file/exit} (:pending (confirm-dialog (:state result)))))
    (is (not (contains? result :system/exit)))))

(deftest clean-project-skips-the-guard-test
  (testing "same effect maps as before the guard existed"
    (let [new-result (menu/handle {:event/type :file/new-project :state clean-document-state})]
      (is (= 120.0 (get-in new-result [:state :timing :bpm])))
      (is (= #{:state} (set (keys new-result))) "the output sync comes from the pipeline"))
    (is (some? (:multi-engine/refresh
                (events/handle-event-pipeline
                 {:event/type :file/new-project
                  :state (assoc-in clean-document-state [:backend :streaming :running?] true)}))))
    (is (contains? (menu/handle {:event/type :file/open :state clean-document-state})
                   :fx/show-file-chooser))
    (is (= {:system/exit true}
           (menu/handle {:event/type :file/exit :state clean-document-state})))))

(deftest forced-events-skip-the-guard-test
  (is (= 120.0 (get-in (menu/handle {:event/type :file/new-project :force? true
                                     :state document-state})
                       [:state :timing :bpm])))
  (is (contains? (menu/handle {:event/type :file/open :force? true :state document-state})
                 :fx/show-file-chooser))
  (is (= {:system/exit true}
         (menu/handle {:event/type :file/exit :force? true :state document-state}))))

(deftest second-guarded-event-while-open-is-ignored-test
  (let [result (menu/handle {:event/type :file/exit :state asking-to-start-over})]
    (is (= asking-to-start-over (:state result)))
    (is (= {:event/type :file/new-project}
           (:pending (confirm-dialog (:state result)))))))

(deftest confirm-cancel-keeps-the-document-and-closes-the-dialog-test
  (let [result (menu/handle {:event/type :file/confirm-cancel :state asking-to-start-over})
        new-state (:state result)]
    (is (false? (:open? (confirm-dialog new-state))))
    (is (nil? (:pending (confirm-dialog new-state))))
    (is (= (:chains document-state) (:chains new-state)))
    (is (= #{:state} (set (keys result))))))

(deftest confirm-discard-continues-the-pending-action-test
  (let [result (menu/handle {:event/type :file/confirm-discard :state asking-to-start-over})]
    (is (false? (:open? (confirm-dialog (:state result)))))
    (is (= {:event/type :file/new-project :force? true} (:dispatch result)))
    (testing "the document is still untouched: the forced event replaces it"
      (is (= (:chains document-state) (get-in result [:state :chains]))))))

(deftest confirm-save-with-a-file-waits-for-the-write-test
  (let [state (assoc-in asking-to-start-over [:project :current-file] test-file)
        result (menu/handle {:event/type :file/confirm-save :time 77 :state state})
        new-state (:state result)]
    (is (false? (:open? (confirm-dialog new-state))))
    (testing "the zip is requested, and the pending action runs only after it succeeded"
      (is (= test-file (get-in result [:project/save :file-path])))
      (is (= {:event/type :file/new-project :force? true}
             (get-in result [:project/save :on-saved])))
      (is (not (contains? result :dispatch))))
    (testing "the document is not replaced yet"
      (is (= 140.0 (get-in new-state [:timing :bpm])))
      (is (= (:chains document-state) (:chains new-state))))))

(deftest confirm-save-without-a-file-routes-through-save-as-test
  (let [result (menu/handle {:event/type :file/confirm-save :state asking-to-start-over})]
    (is (false? (:open? (confirm-dialog (:state result)))))
    (is (not (contains? result :project/save)))
    (is (= :file/save-as-result (get-in result [:fx/show-file-chooser :on-result :event/type])))
    (is (= {:event/type :file/new-project :force? true}
           (get-in result [:fx/show-file-chooser :on-result :then])))))

(deftest save-as-result-carries-then-to-the-write-test
  (is (= {:event/type :file/exit :force? true}
         (get-in (menu/handle {:event/type :file/save-as-result
                               :file-path test-file
                               :then {:event/type :file/exit :force? true}
                               :state document-state})
                 [:project/save :on-saved])))
  (testing "a cancelled chooser ends the chain"
    (let [result (menu/handle {:event/type :file/save-as-result
                               :file-path nil
                               :then {:event/type :file/exit :force? true}
                               :state document-state})]
      (is (not (contains? result :project/save)))
      (is (not (contains? result :dispatch))))))

(deftest plain-save-has-no-continuation-test
  (let [state (assoc-in document-state [:project :current-file] test-file)]
    (is (not (contains? (:project/save (menu/handle {:event/type :file/save :state state}))
                        :on-saved)))))

(deftest save-then-continue-pipeline-test
  (testing "Save in the dialog writes the zip, and only then replaces the document"
    (state/reset-state! (assoc-in document-state [:project :current-file] test-file))
    (events/event-handler {:event/type :file/new-project})
    (let [asking (state/get-raw-state)]
      (is (true? (:open? (confirm-dialog asking))))
      (is (= 140.0 (get-in asking [:timing :bpm])) "still the old document while asking")
      (is (not (.exists (io/file test-file)))))
    (events/event-handler {:event/type :file/confirm-save})
    (let [s (state/get-raw-state)]
      (is (.exists (io/file test-file)))
      (is (= 140.0 (get-in (ser/load-from-zip test-file) ["project-metadata.edn" :timing :bpm])))
      (is (= 120.0 (get-in s [:timing :bpm])) "then the fresh project replaced it")
      (is (nil? (get-in s [:project :current-file])))
      (is (false? (:open? (confirm-dialog s)))))))

(deftest failed-write-does-not-continue-test
  (testing "when the zip can't be written the pending action never runs"
    ;; a path below an existing *file* can't be created
    (spit test-file "not a zip")
    (let [unwritable (str test-file "/inside.zip")]
      (state/reset-state! (assoc-in document-state [:project :current-file] unwritable))
      (events/event-handler {:event/type :file/new-project})
      (events/event-handler {:event/type :file/confirm-save})
      (let [s (state/get-raw-state)]
        (is (= 140.0 (get-in s [:timing :bpm])) "the document is still there")
        (is (true? (get-in s [:project :dirty?])) "and still unsaved")
        (is (false? (:open? (confirm-dialog s))))))))

(deftest discard-pipeline-test
  (state/reset-state! document-state)
  (events/event-handler {:event/type :file/new-project})
  (events/event-handler {:event/type :file/confirm-discard})
  (let [s (state/get-raw-state)]
    (is (= 120.0 (get-in s [:timing :bpm])))
    (is (false? (:open? (confirm-dialog s))))
    (is (false? (get-in s [:project :dirty?])))))
