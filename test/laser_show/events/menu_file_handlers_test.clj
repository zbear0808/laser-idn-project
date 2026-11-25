(ns laser-show.events.menu-file-handlers-test
  "Tests for File menu handlers (New / Open / Save / Save As).

   Handler tests call menu/handle directly (pure: event in, effects out).
   Pipeline tests run events through the wired events/event-handler, which
   applies effects synchronously when called directly."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [laser-show.app :as app]
   [laser-show.events.core :as events]
   [laser-show.events.handlers.menu :as menu]
   [laser-show.output.jar-builder :as jar]
   [laser-show.state.core :as state]
   [laser-show.state.domains :as domains]
   [laser-show.state.history :as history]
   [laser-show.state.persistent :as persistent]
   [laser-show.state.serialization :as ser])
  (:import [java.io File]
           [java.nio.file AtomicMoveNotSupportedException Files]
           [javafx.event ActionEvent]))

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


(deftest save-as-result-requests-the-write-test
  (let [result (menu/handle {:event/type :file/save-as-result
                             :file-path test-file
                             :time 1234
                             :state document-state})]
    (testing "the project status stays as it was until the file is written"
      (is (= document-state (:state result))))
    (testing "the zip write is requested as an effect built from the state"
      (is (= test-file (get-in result [:project/save :file-path])))
      (is (= 140.0 (get-in result [:project/save :files "project-metadata.edn" :timing :bpm])))
      (is (= {:items [{:type :preset :preset-id :circle}]}
             (get-in result [:project/save :files "content.edn" :chains :cue-chains [0 0]])))
      (is (= {:event/type :file/save-failed} (get-in result [:project/save :on-failed]))))
    (testing "nothing was written by the handler itself"
      (is (not (.exists (io/file test-file)))))
    (testing "the write's success event brings the file, the time and the clean flag"
      (let [saved (:state (events/handle-event-pipeline {:event/type :project/mark-clean
                                                         :file-path test-file
                                                         :time 1234
                                                         :state document-state}))]
        (is (= test-file (get-in saved [:project :current-file])))
        (is (false? (get-in saved [:project :dirty?])))
        (is (= 1234 (get-in saved [:project :last-saved])))
        (is (= (history/extract-document-state document-state) (get-in saved [:project :saved-document])))))))

(deftest save-with-current-file-test
  (let [state (assoc-in document-state [:project :current-file] test-file)
        result (menu/handle {:event/type :file/save :time 99 :state state})]
    (is (= state (:state result)) "still unsaved until the write succeeds")
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

(deftest save-failed-keeps-project-status-test
  (let [pipeline events/handle-event-pipeline
        requested (pipeline {:event/type :file/save-as-result
                             :file-path test-file
                             :state document-state})
        result (pipeline {:event/type :file/save-failed
                          :file-path test-file
                          :state (:state requested)})]
    (is (= (:project document-state) (get-in requested [:state :project])) "the request changed nothing")
    (is (= (:project document-state) (get-in result [:state :project])) "so the failure has nothing to restore")
    (is (nil? (get-in result [:state :project :current-file])))
    (is (true? (get-in result [:state :project :dirty?])))))

(deftest failed-save-sets-a-notice-test
  (let [result (menu/handle {:event/type :file/save-failed
                             :file-path "test-output/shows/friday.zip"
                             :state document-state})
        {:keys [id] :as notice} (get-in result [:state :ui :notice])]
    (is (= {:text "Could not save friday.zip" :level :error} (dissoc notice :id)))
    (is (some? id))
    (testing "it goes after 6 s, and only that notice"
      (is (= {:event {:event/type :ui/clear-notice :id id} :delay-ms 6000}
             (:dispatch-later result))))
    (testing "nothing else changes: the project status never left what it was"
      (is (= document-state (assoc-in (:state result) [:ui :notice] nil))))))

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
      (is (= #{:all} (set (keys (:zone-groups new-state))))))
    (testing "the projectors stay this machine's rig's (D-30): the show had none"
      (is (identical? (:projectors current) (:projectors new-state))))
    (testing "session state pointing at the old document is reset"
      (is (= {} (get-in new-state [:playback :active-cues])))
      (is (nil? (get-in new-state [:grid :selected-cell])))
      (is (= [] (get-in new-state [:history :undo-stack]))))
    (testing "project status"
      (is (= test-file (get-in new-state [:project :current-file])))
      (is (false? (get-in new-state [:project :dirty?]))))
    (testing "runtime/backend state is kept"
      (assert-runtime-kept new-state))
    (testing "while streaming, the engines go on as they were (through the pipeline)"
      (is (nil? (:multi-engine/refresh (events/handle-event-pipeline {:event/type :file/project-loaded
                                                                       :file-path test-file
                                                                       :contents (zip-contents-of loaded-doc)
                                                                       :state current})))))))

(deftest project-loaded-fills-missing-config-defaults-test
  (let [contents {"project-metadata.edn" {:config {:cue {:trigger-mode :toggle}}}
                  "content.edn" {:chains {:cue-chains {} :effect-chains {}}}}
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

(def ^:private png-bytes
  "A thumbnail's first bytes: binary, and not UTF-8."
  (byte-array (map unchecked-byte [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A 0xFF 0xFE 0x00])))

(defn- names-in [^File dir]
  (set (.list dir)))

(defn- file-bytes [path]
  (vec (Files/readAllBytes (.toPath (io/file path)))))

(deftest corrupt-open-keeps-current-show-test
  (let [^File dir (jar/temp-dir! "corrupt-open")
        a-path (str (io/file dir "A.zip"))
        b-path (str (io/file dir "B.zip"))
        companions {"project-metadata.edn" (pr-str {:timing {:bpm 90.0} :grid {:size [4 2]}})
                    "hardware.edn" (pr-str {:zone-groups {:group-b {:id :group-b :name "B's"}}})}
        b-zip! (fn [entries] (str (jar/write-jar! (io/file b-path) entries)))]
    (try
      ;; Editing A: saved to A.zip, then a cue named twice and one name undone,
      ;; a cell selected and the report of A's rig kept
      (state/reset-state! (with-runtime document-state))
      (events/event-handler {:event/type :file/save-as-result :file-path a-path})
      (events/event-handler {:event/type :cue-chain/set-name :col 2 :row 0 :name "Unsaved"})
      (events/event-handler {:event/type :cue-chain/set-name :col 2 :row 0 :name "Unsaved again"})
      (events/event-handler {:event/type :edit/undo})
      (state/swap-state! #(-> %
                              (assoc-in [:grid :selected-cell] [2 0])
                              (assoc-in [:ui :dialogs :rig-report] {:open? false :mode :report :file "A.zip"
                                                                    :hardware {} :chains {} :pins {}})))
      (let [editing (state/get-raw-state)
            open! (fn [path]
                    (state/reset-state! editing)
                    (events/event-handler {:event/type :file/open-result :file-path path})
                    (state/get-raw-state))
            kept? (fn [label after]
                    (is (= "Could not open B.zip" (get-in after [:ui :notice :text])) label)
                    (is (= editing (assoc-in after [:ui :notice] (get-in editing [:ui :notice])))
                        (str label ": the rest of the state as it was")))]
        (testing "A has a saved baseline, an unsaved cue and both Undo and Redo"
          (is (= a-path (get-in editing [:project :current-file])))
          (is (true? (get-in editing [:project :dirty?])))
          (is (= "Unsaved" (get-in editing [:chains :cue-chains [2 0] :name])))
          (is (= [1 1] (map #(count (get-in editing [:history %])) [:undo-stack :redo-stack]))))
        (testing "B.zip with readable companions, but no show in content.edn"
          (doseq [[label content] [["a Java object, as it prints" "{:chains #object[java.lang.Object 1 \"x\"]}"]
                                   ["nil" "nil"]
                                   ["a vector" "[]"]
                                   ["cues in a vector" "{:chains {:cue-chains [] :effect-chains {}}}"]
                                   ["a second form" "{:chains {:cue-chains {} :effect-chains {}}} {:chains {}}"]]]
            (kept? label (open! (b-zip! (assoc companions "content.edn" content))))))
        (testing "B.zip with no content.edn"
          (kept? "companions only" (open! (b-zip! companions))))
        (testing "B.zip's content is a show, but its hardware.edn is broken: the whole Open is refused"
          (kept? "broken hardware" (open! (b-zip! {"content.edn" "{:chains {:cue-chains {} :effect-chains {}}}"
                                                   "hardware.edn" "{:zone-groups {:group-b"}))))
        (testing "contents that aren't a show, handed to :file/project-loaded"
          (let [contents {"project-metadata.edn" {:timing {:bpm 90.0}}}]
            (is (= {:dispatch {:event/type :file/project-load-failed :file-path b-path}}
                   (menu/handle {:event/type :file/project-loaded :file-path b-path :contents contents :state editing})))
            (state/reset-state! editing)
            (events/event-handler {:event/type :file/project-loaded :file-path b-path :contents contents})
            (kept? "handed in" (state/get-raw-state))))
        (testing "content.edn alone, both grids empty, beside a thumbnail: B opens, empty and clean"
          (let [opened (open! (b-zip! {"content.edn" "{:chains {:cue-chains {} :effect-chains {}}}"
                                       "thumbnail.png" png-bytes}))]
            (is (= {} (get-in opened [:chains :cue-chains])))
            (is (= {} (get-in opened [:chains :effect-chains])))
            (is (= 120.0 (get-in opened [:timing :bpm])) "A's 140 BPM went with A: B has none")
            (is (= b-path (get-in opened [:project :current-file])))
            (is (false? (get-in opened [:project :dirty?])))
            (is (= [[] []] (map #(get-in opened [:history %]) [:undo-stack :redo-stack])))
            (is (nil? (get-in opened [:grid :selected-cell])))
            (is (nil? (get-in opened [:ui :notice])))
            (assert-runtime-kept opened))))
      (finally
        (jar/delete-tree! dir)))))


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
      (is (= [[0 3]] (keys (get-in new-state [:chains :effect-chains]))) "and the Master, the only effect cell")
      (is (= "Master" (get-in new-state [:chains :effect-chains [0 3] :name])))
      (is (seq (get-in new-state [:chains :cue-chains])))
      (is (not= (get-in current [:chains :cue-chains [0 0]])
                (get-in new-state [:chains :cue-chains [0 0]]))))
    (testing "the rig stays: the projectors are as they were"
      (is (identical? (:projectors current) (:projectors new-state))))
    (testing "project and session are reset"
      (is (nil? (get-in new-state [:project :current-file])))
      (is (false? (get-in new-state [:project :dirty?])))
      (is (= {} (get-in new-state [:playback :active-cues]))))
    (testing "runtime/backend state is kept"
      (assert-runtime-kept new-state))
    (testing "while streaming, the engines go on as they were (through the pipeline)"
      (is (nil? (:multi-engine/refresh (events/handle-event-pipeline {:event/type :file/new-project
                                                                       :force? true :state current})))))))

(deftest new-and-open-take-the-notice-away-test
  (let [noticed (assoc-in document-state [:ui :notice]
                          {:id (random-uuid) :text "Could not save friday.zip" :level :error})]
    (testing "File > New: the notice was about the project that went"
      (is (nil? (get-in (menu/handle {:event/type :file/new-project :force? true :state noticed})
                        [:state :ui :notice]))))
    (testing "File > Open: the same, and no notice about safety zones: Open never touches them (D-30)"
      (is (nil? (get-in (menu/handle {:event/type :file/project-loaded
                                      :file-path test-file
                                      :contents (zip-contents-of document-state)
                                      :state noticed})
                        [:state :ui :notice]))))))


;; The show's rig, matched onto this one (zones plan ZR)


(def ^:private two-lasers
  "A show made with Laser L and Laser R, on."
  (-> (domains/build-initial-state)
      (assoc :projectors {:p-l {:name "Laser L" :host "10.0.0.5" :service-id 1 :enabled? true :zone-groups []
                                :stable-id [:host "10.0.0.5" 1]}
                          :p-r {:name "Laser R" :host "10.0.0.7" :service-id 1 :enabled? true :zone-groups []
                                :stable-id [:host "10.0.0.7" 1]}})
      (assoc :rig {:order [:p-l :p-r] :middle :split :split-width 0.75})))

(deftest open-asks-to-set-up-an-empty-rig-test
  (let [open (fn [here show]
               (:state (menu/handle {:event/type :file/project-loaded :file-path "shows/friday.zip"
                                     :contents (zip-contents-of show) :state here})))
        empty-rig (assoc document-state :projectors {})
        asked (open empty-rig two-lasers)]
    (testing "no laser on this rig is on, and friday.zip has lasers: the set-up question"
      (is (= {:open? true :mode :set-up :file "friday.zip"}
             (select-keys (get-in asked [:ui :dialogs :rig-report]) [:open? :mode :file])))
      (is (= (:projectors two-lasers) (get-in asked [:ui :dialogs :rig-report :hardware :projectors]))
          "the show's hardware, kept for the set-up")
      (is (= {} (:projectors asked)) "nothing changes until Set up rig is pressed"))
    (testing "lasers that are all off count as no laser on"
      (is (= :set-up (get-in (open (assoc-in document-state [:projectors :proj-a :enabled?] false) two-lasers)
                             [:ui :dialogs :rig-report :mode]))))
    (testing "a rig with a laser on: no question"
      (is (not= :set-up (get-in (open document-state two-lasers) [:ui :dialogs :rig-report :mode]))))
    (testing "a show with no laser on: nothing to set up"
      (is (not (get-in (open empty-rig (-> two-lasers
                                           (assoc-in [:projectors :p-l :enabled?] false)
                                           (assoc-in [:projectors :p-r :enabled?] false)))
                       [:ui :dialogs :rig-report :open?])))
      (is (not (get-in (open empty-rig (domains/build-initial-state)) [:ui :dialogs :rig-report :open?]))))
    (testing "Not now leaves the rig as it is; the next show asks again"
      (let [not-now (:state (events/handle-event-pipeline {:event/type :ui/close-dialog :dialog-id :rig-report :state asked}))]
        (is (false? (get-in not-now [:ui :dialogs :rig-report :open?])))
        (is (= {} (:projectors not-now)))
        (is (true? (get-in (open not-now two-lasers) [:ui :dialogs :rig-report :open?])))))))

(deftest open-shows-the-report-only-with-a-warning-test
  (let [open (fn [show]
               (:state (menu/handle {:event/type :file/project-loaded :file-path "shows/friday.zip"
                                     :contents (zip-contents-of show) :state document-state})))
        fits (open two-lasers)
        pinned (open (assoc-in two-lasers [:chains :cue-chains [0 0]]
                               {:items [] :destination-zone {:zone-group-id :auto.laser/p-r}}))]
    (testing "a show that fits this rig: no report, but what it kept for one"
      (is (= {:open? false :mode :report :file "friday.zip"}
             (select-keys (get-in fits [:ui :dialogs :rig-report]) [:open? :mode :file]))))
    (testing "a cue pinned to a laser this rig doesn't have: the report"
      (is (= {:open? true :mode :report} (select-keys (get-in pinned [:ui :dialogs :rig-report]) [:open? :mode]))))
    (testing "an ℹ row alone doesn't open it"
      (is (false? (get-in (open (assoc-in two-lasers [:projectors :p-l :safety] {:limit [-0.5 -0.5 0.5 0.5]}))
                          [:ui :dialogs :rig-report :open?]))))))


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
      (is (= #{:state} (set (keys new-result))) "nothing but the new show"))
    (is (nil? (:multi-engine/refresh
               (events/handle-event-pipeline
                {:event/type :file/new-project
                 :state (assoc-in clean-document-state [:backend :streaming :running?] true)})))
        "New keeps the rig, so the engines go on")
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

(deftest exit-waits-for-verified-show-test
  (let [^File dir (jar/temp-dir! "exit-waits")
        a-path (str (io/file dir "A.zip"))
        b-path (str (io/file dir "B.zip"))
        amount [:chains :effect-chains [1 1] :items 0 :params :amount]
        exits (atom [])
        during-write (atom ::not-written)
        save-to-zip! ser/save-to-zip!
        publish! @#'persistent/publish-project!
        exit-with-save! (fn []
                          (events/event-handler {:event/type :file/exit})
                          (events/event-handler {:event/type :file/confirm-save}))]
    (events/set-exit-fn! #(swap! exits conj {:state (state/get-raw-state) :file (persistent/read-project a-path)}))
    (try
      ;; A.zip saved; then a cue named "#object", unsaved
      (state/reset-state! (assoc-in document-state amount {:type :sine :active? false :value 0.2 :min 0.0 :max 1.0}))
      (events/event-handler {:event/type :file/save-as-result :file-path a-path})
      (events/event-handler {:event/type :cue-chain/set-name :col 2 :row 0 :name "#object"})
      (let [a-bytes (file-bytes a-path)
            dirty (state/get-raw-state)
            refused? (fn [label before]
                       (let [after (state/get-raw-state)]
                         (is (empty? @exits) (str label ": no exit"))
                         (is (= a-bytes (file-bytes a-path)) (str label ": A.zip's bytes as they were"))
                         (is (= "Could not save A.zip" (get-in after [:ui :notice :text])) label)
                         (is (= (:project before) (:project after)) (str label ": the file, time, baseline and unsaved flag as they were"))
                         (is (= (:history before) (:history after)) (str label ": the history too"))
                         (is (= (:chains before) (:chains after)) (str label ": and the edit is still there"))))]
        (is (= a-path (get-in dirty [:project :current-file])))
        (is (true? (get-in dirty [:project :dirty?])))
        (testing "an ActionEvent where the amount's value goes: the write is refused, and Exit with it"
          (state/reset-state! (assoc-in dirty (conj amount :value) (ActionEvent.)))
          (let [before (state/get-raw-state)]
            (with-redefs [ser/save-to-zip! (fn [& args]
                                             (reset! during-write (:project (state/get-raw-state)))
                                             (apply save-to-zip! args))]
              (exit-with-save!))
            (is (= (:project before) @during-write) "while it was written, the project was still the unsaved one")
            (refused? "an object" before)
            (is (= #{"A.zip"} (names-in dir)) "no temporary file left")))
        (testing "a cue with :tracks 42 (readable, but this build can't open it): nothing is written"
          (reset! during-write ::not-written)
          (state/reset-state! (assoc-in dirty [:chains :cue-chains [3 0]] {:tracks 42 :items []}))
          (let [before (state/get-raw-state)]
            (with-redefs [ser/save-to-zip! (fn [& args]
                                             (reset! during-write (:project (state/get-raw-state)))
                                             (apply save-to-zip! args))]
              (exit-with-save!))
            (is (= ::not-written @during-write))
            (refused? ":tracks 42" before)))
        (testing "Save As onto a good B.zip, read back, but the move fails: B.zip, A and Exit stay as they were"
          (is (persistent/write-project! b-path (persistent/project-files (assoc-in document-state [:timing :bpm] 77.0))))
          (let [b-bytes (file-bytes b-path)
                moves (atom 0)]
            (state/reset-state! dirty)
            (with-redefs-fn {#'persistent/publish-project! (fn [& _]
                                                             (swap! moves inc)
                                                             (throw (AtomicMoveNotSupportedException. "B.zip.tmp" "B.zip" "can't replace it in one step")))}
              #(events/event-handler {:event/type :file/save-as-result :file-path b-path
                                      :then {:event/type :file/exit :force? true}}))
            (is (= 1 @moves) "written and read back: it got as far as the move")
            (is (= b-bytes (file-bytes b-path)))
            (is (= #{"A.zip" "B.zip"} (names-in dir)) "no temporary file left")
            (is (= (:project dirty) (:project (state/get-raw-state))) "A is still the file, and unsaved")
            (is (empty? @exits))))
        (testing "0.42 and the cue named \"#object\": A.zip is replaced, then marked saved, then Exit, once"
          (state/reset-state! (assoc-in dirty (conj amount :value) 0.42))
          (with-redefs-fn {#'persistent/publish-project! (fn [staged target]
                                                           (reset! during-write (:project (state/get-raw-state)))
                                                           (publish! staged target))}
            exit-with-save!)
          (is (= (:project dirty) @during-write) "still unsaved while it was moved into place")
          (is (= 1 (count @exits)))
          (let [{:keys [state file]} (first @exits)]
            (is (= (persistent/project-files state) file) "at the exit, A.zip reads back as the show")
            (is (= 0.42 (get-in file ["content.edn" :chains :effect-chains [1 1] :items 0 :params :amount :value])))
            (is (= "#object" (get-in file ["content.edn" :chains :cue-chains [2 0] :name])))
            (is (= a-path (get-in state [:project :current-file])))
            (is (false? (get-in state [:project :dirty?])))
            (is (= (history/extract-document-state state) (get-in state [:project :saved-document])))
            (is (= #{"A.zip" "B.zip"} (names-in dir))))))
      (finally
        (events/set-exit-fn! nil)
        (jar/delete-tree! dir)))))

(deftest discard-pipeline-test
  (state/reset-state! document-state)
  (events/event-handler {:event/type :file/new-project})
  (events/event-handler {:event/type :file/confirm-discard})
  (let [s (state/get-raw-state)]
    (is (= 120.0 (get-in s [:timing :bpm])))
    (is (false? (:open? (confirm-dialog s))))
    (is (false? (get-in s [:project :dirty?])))))


;; Exit after the rig file couldn't be written (zones plan ZR)


(def ^:private rig-not-saved
  (-> clean-document-state
      (assoc-in [:rig-file :path] "/tmp/never-written/rig.edn")
      (update :rig-file assoc :status :write-failed :error "No space left on device")))

(deftest exit-after-a-failed-rig-write-asks-first-test
  (let [result (menu/handle {:event/type :file/exit :state rig-not-saved})
        asked (:state result)
        ;; the show had no unsaved changes when it asked: what Exit may
        ;; throw away when it comes back (reactivity plan 21)
        parked-exit {:event/type :file/exit :force? true :rig-force? true
                     :authorized {:document-id nil :dirty? false}}]
    (is (not (contains? result :system/exit)) "it doesn't quit yet")
    (is (= {:open? true :reason :rig :error "No space left on device"
            :pending parked-exit}
           (confirm-dialog asked))
        "it asks, with its own text")
    (testing "Quit anyway quits"
      (let [discarded (menu/handle {:event/type :file/confirm-discard :state asked})]
        (is (false? (:open? (confirm-dialog (:state discarded)))))
        (is (= {:system/exit true} (menu/handle (assoc (:dispatch discarded) :state (:state discarded)))))))
    (testing "Try again writes the rig now, and quits once it is written"
      (let [retried (menu/handle {:event/type :file/confirm-retry :state asked})]
        (is (false? (:open? (confirm-dialog (:state retried)))))
        (is (nil? (:reason (confirm-dialog (:state retried)))))
        (is (= {:event/type :rig/retry-write :then parked-exit}
               (:dispatch retried)))
        (let [written (events/handle-event-pipeline (assoc (:dispatch retried) :state (:state retried)))]
          (is (= parked-exit
                 (get-in written [:rig/write :then]))
              "the write carries the exit, for :rig/written to dispatch"))))
    (testing "Cancel stays"
      (is (false? (:open? (confirm-dialog (:state (menu/handle {:event/type :file/confirm-cancel :state asked}))))))))
  (testing "a show with unsaved changes too: its question first, then the rig's"
    (let [both (assoc-in rig-not-saved [:project :dirty?] true)
          first-q (confirm-dialog (:state (menu/handle {:event/type :file/exit :state both})))]
      (is (nil? (:reason first-q)) "the show's question")
      (is (= :rig (:reason (confirm-dialog (:state (menu/handle {:event/type :file/exit :force? true :state both}))))))))
  (testing "a rig that is saved: Exit quits at once"
    (is (= {:system/exit true}
           (menu/handle {:event/type :file/exit :state (assoc-in rig-not-saved [:rig-file :status] :saved)})))))


;; Work that belongs to the show it started in (reactivity plan 20)


(defn- document-id [state]
  (get-in state [:project :document-id]))

(defn- found-in?
  "Is x anywhere in data?"
  [x data]
  (boolean (some #(= x %) (tree-seq coll? seq data))))

(defn- rename-cue!
  "An edit through the pipeline: cue [2 0] named text."
  [text]
  (events/event-handler {:event/type :cue-chain/set-name :col 2 :row 0 :name text}))

(defn- chooser-result
  "The event the chooser event (File > Open or Save As) asks for in state
   sends back once file-path is picked."
  [event state file-path]
  (assoc (get-in (menu/handle (assoc event :state state)) [:fx/show-file-chooser :on-result])
         :file-path file-path))

(deftest document-id-rotates-on-successful-new-and-open-test
  (let [^File dir (jar/temp-dir! "document-id")
        a-path (str (io/file dir "A.zip"))
        b-path (str (io/file dir "B.zip"))
        id #(document-id (state/get-raw-state))
        run (fn [event] (events/event-handler event) (id))
        save-as (fn [file-path] (chooser-result {:event/type :file/save-as} (state/get-raw-state) file-path))]
    (try
      (is (persistent/write-project! b-path (zip-contents-of (assoc-in document-state [:timing :bpm] 77.0))))
      (spit (io/file dir "not-a-show.zip") "not a zip")
      (testing "the starter show has an identity, a new one at each start"
        (let [starter (#'app/starter-state)]
          (is (uuid? (document-id starter)))
          (is (not= (document-id starter) (document-id (#'app/starter-state))))
          (state/reset-state! starter)))
      (let [a (id)]
        (testing "it is the session's, not the show's: not saved, not in an undo step, no unsaved change"
          (rename-cue! "Edited")
          (let [s (state/get-raw-state)]
            (is (true? (get-in s [:project :dirty?])) "the edit is unsaved")
            (is (= 1 (count (get-in s [:history :undo-stack]))) "and has its undo step")
            (is (not (found-in? a (zip-contents-of s))))
            (is (not (found-in? a (:history s))))
            (is (not (found-in? a (history/extract-document-state s))))))
        (testing "Undo keeps it"
          (is (= a (run {:event/type :edit/undo})))
          (is (false? (get-in (state/get-raw-state) [:project :dirty?]))))
        (testing "Save As, a Save As that fails, and Save keep it"
          (rename-cue! "Edited again")
          (is (= a (run (save-as a-path))))
          (is (= a-path (get-in (state/get-raw-state) [:project :current-file])))
          (is (= a (run (save-as (str a-path "/inside.zip")))))
          (is (= "Could not save inside.zip" (get-in (state/get-raw-state) [:ui :notice :text])))
          (rename-cue! "Edited once more")
          (is (= a (run {:event/type :file/save})))
          (is (false? (get-in (state/get-raw-state) [:project :dirty?]))))
        (testing "an Open that is cancelled, or finds no file or no show, keeps it"
          (is (= a (run {:event/type :file/open-result :file-path nil})))
          (is (= a (run {:event/type :file/open-result :file-path (str (io/file dir "missing.zip"))})))
          (is (= a (run {:event/type :file/open-result :file-path (str (io/file dir "not-a-show.zip"))})))
          (is (= a (run {:event/type :file/project-loaded :file-path b-path :contents {"project-metadata.edn" {}}}))))
        (testing "a New that asks first keeps it, until it goes ahead"
          (rename-cue! "Unsaved")
          (is (= a (run {:event/type :file/new-project})))
          (is (true? (:open? (confirm-dialog (state/get-raw-state)))))
          (is (= a (run {:event/type :file/confirm-cancel})))
          (is (= a (run {:event/type :file/new-project}))))
        (testing "each New and Open that replaces the show gives the next show its own"
          (let [b (run {:event/type :file/confirm-discard})
                c (run {:event/type :file/open-result :file-path b-path})
                d (run {:event/type :file/new-project})
                e (run {:event/type :file/project-loaded :file-path b-path :contents (persistent/read-project b-path)})]
            (is (= 77.0 (get-in (state/get-raw-state) [:timing :bpm])) "B.zip is open")
            (is (every? uuid? [b c d e]))
            (is (= 5 (count (distinct [a b c d e])))))))
      (finally
        (jar/delete-tree! dir)))))

(deftest chooser-open-rechecks-a-document-changed-while-choosing-test
  ;; Before: File > Open asked only before its chooser, so an edit that
  ;; landed while the chooser was open was thrown away without a question
  (let [^File dir (jar/temp-dir! "open-rechecks")
        a-path (str (io/file dir "A.zip"))
        b-path (str (io/file dir "B.zip"))
        clean (-> document-state
                  (assoc-in [:project :document-id] (random-uuid))
                  (assoc-in [:project :current-file] a-path)
                  history/mark-clean)
        now #(state/get-raw-state)
        pick-b (fn [open-event state] (chooser-result open-event state b-path))
        b-open? #(and (= b-path (get-in % [:project :current-file]))
                      (= 77.0 (get-in % [:timing :bpm])))
        asked? #(= {:open? true :pending {:event/type :file/open-result :file-path b-path}}
                   (select-keys (confirm-dialog %) [:open? :pending]))]
    (try
      (is (persistent/write-project! b-path (zip-contents-of (assoc-in document-state [:timing :bpm] 77.0))))
      (testing "the chooser's result says what it may throw away: this show, with no unsaved changes"
        (is (= {:document-id (document-id clean) :dirty? false}
               (:authorized (pick-b {:event/type :file/open} clean)))))
      (testing "nothing changed while choosing: B.zip opens"
        (state/reset-state! clean)
        (events/event-handler (pick-b {:event/type :file/open} clean))
        (is (b-open? (now))))
      (testing "an edit landed while choosing"
        (state/reset-state! clean)
        (rename-cue! "While choosing")
        (events/event-handler (pick-b {:event/type :file/open} clean))
        (let [asking (now)]
          (testing ": nothing is replaced, and the question is asked again, for B.zip"
            (is (asked? asking))
            (is (= "While choosing" (get-in asking [:chains :cue-chains [2 0] :name])))
            (is (= a-path (get-in asking [:project :current-file])))
            (is (true? (get-in asking [:project :dirty?])))
            (is (= (document-id clean) (document-id asking))))
          (testing "; Cancel keeps the show"
            (state/reset-state! asking)
            (events/event-handler {:event/type :file/confirm-cancel})
            (is (= "While choosing" (get-in (now) [:chains :cue-chains [2 0] :name])))
            (is (false? (:open? (confirm-dialog (now))))))
          (testing "; Don't Save opens B.zip"
            (state/reset-state! asking)
            (events/event-handler {:event/type :file/confirm-discard})
            (is (b-open? (now))))
          (testing "; Save writes A.zip with the edit, then opens B.zip"
            (state/reset-state! asking)
            (events/event-handler {:event/type :file/confirm-save})
            (is (b-open? (now)))
            (is (= "While choosing" (get-in (persistent/read-project a-path) ["content.edn" :chains :cue-chains [2 0] :name]))))))
      (testing "the show was replaced while choosing"
        (testing "by one with no unsaved changes: nothing to lose, B.zip opens"
          (state/reset-state! clean)
          (events/event-handler {:event/type :file/new-project})
          (events/event-handler (pick-b {:event/type :file/open} clean))
          (is (b-open? (now))))
        (testing "by one with unsaved changes: they are asked about first"
          (state/reset-state! clean)
          (events/event-handler {:event/type :file/new-project})
          (rename-cue! "In the new show")
          (events/event-handler (pick-b {:event/type :file/open} clean))
          (is (asked? (now)))
          (is (= "In the new show" (get-in (now) [:chains :cue-chains [2 0] :name])))))
      (testing "unsaved changes Don't Save had already let go"
        (state/reset-state! clean)
        (rename-cue! "Confirmed")
        (let [dirty (now)
              confirmed (pick-b {:event/type :file/open :force? true} dirty)]
          (is (= {:document-id (document-id clean) :dirty? true} (:authorized confirmed)))
          (testing ": B.zip opens without asking again"
            (state/reset-state! dirty)
            (events/event-handler confirmed)
            (is (b-open? (now))))
          (testing ", but not over another show's"
            (state/reset-state! dirty)
            (events/event-handler {:event/type :file/new-project :force? true})
            (rename-cue! "Another show's")
            (events/event-handler confirmed)
            (is (asked? (now)))
            (is (= "Another show's" (get-in (now) [:chains :cue-chains [2 0] :name]))))))
      (finally
        (jar/delete-tree! dir)))))

(deftest an-open-asked-about-again-waits-for-the-question-open-test
  ;; Follow-up B12 (b): an Open whose chooser came back once the show had
  ;; changed asks before replacing it. When the dialog was already asking
  ;; something else by then (closing the window while choosing), that Open
  ;; was dropped, only logged
  (let [^File dir (jar/temp-dir! "open-waits")
        a-path (str (io/file dir "A.zip"))
        b-path (str (io/file dir "B.zip"))
        clean (-> document-state
                  (assoc-in [:project :document-id] (random-uuid))
                  (assoc-in [:project :current-file] a-path)
                  history/mark-clean)
        now #(state/get-raw-state)
        exits (atom 0)
        b-open? #(and (= b-path (get-in % [:project :current-file]))
                      (= 77.0 (get-in % [:timing :bpm])))
        asking? (fn [pending] (= {:open? true :pending pending} (select-keys (confirm-dialog (now)) [:open? :pending])))
        waiting #(mapv (fn [e] (select-keys e [:event/type :file-path])) (:queued (confirm-dialog (now))))
        b-back-while-asking! (fn [question]
                               (state/reset-state! clean)
                               (let [picked (chooser-result {:event/type :file/open} clean b-path)]
                                 (rename-cue! "While choosing")
                                 (events/event-handler question)
                                 (is (asking? question))
                                 (events/event-handler picked)
                                 picked))]
    (events/set-exit-fn! #(swap! exits inc))
    (try
      (is (persistent/write-project! b-path (zip-contents-of (assoc-in document-state [:timing :bpm] 77.0))))
      (testing "B.zip comes back while the dialog asks about closing"
        (let [picked (b-back-while-asking! {:event/type :file/exit})]
          (is (asking? {:event/type :file/exit}) "the question open stays")
          (is (= [{:event/type :file/open-result :file-path b-path}] (waiting)) "the Open waits behind it")
          (events/event-handler picked)
          (is (= 1 (count (waiting))) "once")
          (is (not (b-open? (now))))
          (testing "; Cancel: then B.zip's question, the show still as it was"
            (events/event-handler {:event/type :file/confirm-cancel})
            (is (zero? @exits))
            (is (asking? {:event/type :file/open-result :file-path b-path}))
            (is (empty? (waiting)))
            (is (= "While choosing" (get-in (now) [:chains :cue-chains [2 0] :name])))
            (testing "; Don't Save opens it"
              (events/event-handler {:event/type :file/confirm-discard})
              (is (b-open? (now)))
              (is (not (:open? (confirm-dialog (now)))))))))
      (testing "while it asks about a New, Don't Save: the new show, then B.zip, which has nothing to ask"
        (b-back-while-asking! {:event/type :file/new-project})
        (is (= 1 (count (waiting))))
        (events/event-handler {:event/type :file/confirm-discard})
        (is (b-open? (now)))
        (is (not (:open? (confirm-dialog (now))))))
      (testing "Save: A.zip written with the edit, the New, then B.zip"
        (b-back-while-asking! {:event/type :file/new-project})
        (events/event-handler {:event/type :file/confirm-save})
        (is (b-open? (now)))
        (is (= "While choosing" (get-in (persistent/read-project a-path) ["content.edn" :chains :cue-chains [2 0] :name]))))
      (is (zero? @exits))
      (finally
        (events/set-exit-fn! nil)
        (jar/delete-tree! dir)))))

(deftest parked-paste-from-a-replaced-document-does-nothing-test
  ;; Before: a paste waiting for the OS clipboard pasted into the show New or
  ;; Open had put there meanwhile
  (let [clip {:type :cue-chain :data {:items [{:type :preset :preset-id :star}]} :copied-at 1}
        show (-> document-state
                 (assoc-in [:project :document-id] (random-uuid))
                 (assoc-in [:ui :active-tab] :grid)
                 (assoc-in [:grid :selected-cell] [5 2])
                 history/mark-clean)
        now #(state/get-raw-state)
        parked (get-in (menu/handle {:event/type :edit/paste :state show :time 1}) [:clipboard/read :then])]
    (testing "Edit > Paste with nothing copied in this app waits for the OS clipboard, for this show"
      (is (= {:event/type :grid/paste-cell :col 5 :row 2 :document-id (document-id show)} parked)))
    (testing "back in the same show, the read pastes"
      (state/reset-state! show)
      (events/event-handler (assoc parked :clipboard/system clip))
      (is (= (:data clip) (get-in (now) [:chains :cue-chains [5 2]]))))
    (doseq [[label replace!] [["File > New" #(events/event-handler {:event/type :file/new-project})]
                              ["File > Open" #(events/event-handler {:event/type :file/project-loaded :file-path test-file
                                                                      :contents (zip-contents-of show)})]]
            [found what] [[clip "a cue"] [nil "nothing"]]]
      (testing (str "back after " label ", with " what ": nothing at all")
        (state/reset-state! show)
        (replace!)
        (let [replaced (now)
              back (assoc parked :clipboard/system found)]
          (is (not= (document-id show) (document-id replaced)))
          (is (= {} (events/handle-event-pipeline (assoc back :state replaced))) "no state, no notice")
          (events/event-handler back)
          (is (= replaced (now))))))
    (testing "a list's paste belongs to its show too"
      (let [triangle {:type :cue-chain-items :copied-at 5
                      :items [{:id (random-uuid) :type :preset :preset-id :triangle :effects []}]}
            parked (get-in (events/handle-event-pipeline {:event/type :list/paste-items :component-id [:cue-chain 0 0]
                                                          :items-path [:chains :cue-chains [0 0] :items]
                                                          :on-change-event :chain/set-items
                                                          :on-change-params {:domain :cue-chains :entity-key [0 0]}
                                                          :state show})
                           [:clipboard/read :then])]
        (is (= (document-id show) (:document-id parked)))
        (state/reset-state! show)
        (events/event-handler (assoc parked :clipboard/system triangle))
        (is (= [:circle :triangle] (mapv :preset-id (get-in (now) [:chains :cue-chains [0 0] :items])))
            "in the same show, pasted")
        (state/reset-state! show)
        (events/event-handler {:event/type :file/new-project})
        (let [replaced (now)]
          (events/event-handler (assoc parked :clipboard/system triangle))
          (is (= replaced (now)) "after New, nothing"))))))

(deftest save-as-chooser-for-a-replaced-document-saves-nothing-test
  (let [^File dir (jar/temp-dir! "save-as-replaced")
        path (str (io/file dir "A.zip"))
        show (assoc-in document-state [:project :document-id] (random-uuid))
        exits (atom 0)
        chosen (fn [then] (chooser-result (cond-> {:event/type :file/save-as} then (assoc :then then)) show path))]
    (events/set-exit-fn! #(swap! exits inc))
    (try
      (testing "Save As's chooser answers for the show it was opened on"
        (is (= (document-id show) (:document-id (chosen nil)))))
      (testing "that show was replaced while choosing: nothing is written, and what waited for the save doesn't run"
        (state/reset-state! show)
        (events/event-handler {:event/type :file/new-project :force? true})
        (let [replaced (state/get-raw-state)
              back (chosen {:event/type :file/exit :force? true})]
          (is (= {} (events/handle-event-pipeline (assoc back :state replaced))))
          (events/event-handler back)
          (is (= replaced (state/get-raw-state)))
          (is (not (.exists (io/file path))))
          (is (zero? @exits))))
      (testing "the same show: written, then Exit"
        (state/reset-state! show)
        (events/event-handler (chosen {:event/type :file/exit :force? true}))
        (is (= path (get-in (state/get-raw-state) [:project :current-file])))
        (is (= 140.0 (get-in (persistent/read-project path) ["project-metadata.edn" :timing :bpm])))
        (is (= 1 @exits)))
      (finally
        (events/set-exit-fn! nil)
        (jar/delete-tree! dir)))))
