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
    (is (true? (:multi-engine/refresh result)))))

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
  (is (= document-state (state/get-raw-state))))


;; New Project


(deftest new-project-resets-document-only-test
  (let [current (-> document-state
                    with-runtime
                    (assoc-in [:project :current-file] test-file)
                    (assoc-in [:playback :active-cues [0 0]] {:trigger-time 1}))
        result (menu/handle {:event/type :file/new-project :state current})
        new-state (:state result)]
    (testing "document is fresh, with starter cues"
      (is (= 120.0 (get-in new-state [:timing :bpm])))
      (is (= {} (:projectors new-state)))
      (is (= {} (get-in new-state [:chains :effect-chains])))
      (is (seq (get-in new-state [:chains :cue-chains])))
      (is (not= (get-in current [:chains :cue-chains [0 0]])
                (get-in new-state [:chains :cue-chains [0 0]]))))
    (testing "project and session are reset"
      (is (nil? (get-in new-state [:project :current-file])))
      (is (false? (get-in new-state [:project :dirty?])))
      (is (= {} (get-in new-state [:playback :active-cues]))))
    (testing "runtime/backend state is kept"
      (assert-runtime-kept new-state))
    (is (true? (:multi-engine/refresh result)))))
