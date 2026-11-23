(ns laser-show.ui-test.rig-report-test
  "The rig file on screen (plans/2026-11-19-zones/09 §7): the Projectors
   tab's line about it and the read-only banner, and, after opening a show,
   the set-up question and the report. Each test has a temp folder of its
   own for its rig file, so none reaches the user's ~/LaserShowProjects."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.app :as app]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.rig-store :as rig-store]
            [laser-show.state.templates :as templates]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [javafx.scene.control Control]))

(def ^:dynamic ^File *dir* nil)

(defn- with-temp-dir
  "Each test's own folder, deleted after the app has stopped (and written
   what it had pending)."
  [t]
  (let [dir (.toFile (Files/createTempDirectory "rig-report-test" (make-array FileAttribute 0)))]
    (try (binding [*dir* dir] (t))
         (finally (doseq [^File f (reverse (file-seq dir))] (.delete f))))))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each with-temp-dir fixtures/with-app)

(defn- app-state [] (state/get-raw-state))
(defn- rig-path [] (str (io/file *dir* "rig.edn")))

(defn- starter []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-vibes)
      (templates/apply-starter-effects)
      (history/mark-clean)))

(defn- start-with-rig!
  "Mount the app on this test's rig file, as app/start! does with the real one."
  ([] (start-with-rig! (starter)))
  ([state]
   (app/start-test! (assoc-in state [:rig-file :path] (rig-path)))
   (ui/sync-fx!)))

(defn- laser [n host]
  {:name n :host host :port 7255 :service-id 1 :enabled? true :zone-groups [] :exclude-from-all? false
   :stable-id [:host host 1] :output-config {:color-bit-depth 8 :xy-bit-depth 16}})

(def ^:private rig-doc
  {:rig-file/version 1
   :projectors {:projector-10-0-0-5-1 (assoc (laser "Laser L" "10.0.0.5") :zone-groups [:group-beams])}
   :virtual-projectors {}
   :rig {:order [] :middle :split :split-width 0.75}
   :chains {:projector-effects {}}
   :zone-groups {:group-beams {:id :group-beams :name "Beams" :color "#123456"}}})

(defn- tooltip-text [selector]
  (ui/on-fx-thread #(some-> ^Control (ui/query selector) .getTooltip .getText)))

(defn- show-zip!
  "Write a show to this test's folder as name, made on the rig of lasers
   ({id projector}) with cues (cue-chains); return its path."
  [name lasers cues & {:keys [groups]}]
  (let [path (str (io/file *dir* name))
        show (cond-> (-> (domains/build-initial-state)
                         (assoc :projectors lasers)
                         (assoc-in [:chains :cue-chains] cues))
               groups (update :zone-groups merge groups))]
    (persistent/write-project! path (persistent/project-files show))
    path))

(defn- open! [path]
  (events/dispatch! {:event/type :file/open-result :file-path path}))


;; The rig file's line

(deftest rig-status-line-says-saved-test
  (let [robot (ui/robot)]
    (start-with-rig!)
    (ui/click! robot "#projectors-tab")
    (ui/assert-text! "#rig-file-status" "Rig saves automatically")
    (is (= (rig-path) (tooltip-text "#rig-file-status")) "its tooltip says where")
    (rig-store/write! (rig-path) rig-doc)
    (start-with-rig!)
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(some? (ui/query "#rig-card-projector-10-0-0-5-1")))
    (testing "the rig in the file is the one on the strip, and its group comes into the new show"
      (is (= "Laser L" (get-in (app-state) [:projectors :projector-10-0-0-5-1 :name])))
      (is (= "Beams" (get-in (app-state) [:zone-groups :group-beams :name])))
      (is (false? (get-in (app-state) [:project :dirty?])) "a fresh show, not an unsaved one"))
    (ui/uncheck! robot "#rig-card-enable-projector-10-0-0-5-1")
    (ui/wait-for! 5000 #(re-matches #"Rig saved \d\d:\d\d" (str (ui/node-text "#rig-file-status"))))
    (is (false? (get-in (:doc (rig-store/read! (rig-path))) [:projectors :projector-10-0-0-5-1 :enabled?]))
        "the file follows, within half a second")
    (is (false? (get-in (app-state) [:project :dirty?])) "and the show's * doesn't come on")
    (testing "Undo puts it back, in the file too, and the show is still as saved"
      (events/dispatch! {:event/type :edit/undo})
      (ui/wait-for! #(true? (get-in (app-state) [:projectors :projector-10-0-0-5-1 :enabled?])))
      (ui/wait-for! 5000 #(true? (get-in (:doc (rig-store/read! (rig-path))) [:projectors :projector-10-0-0-5-1 :enabled?])))
      (is (false? (get-in (app-state) [:project :dirty?]))))))

(deftest read-only-rig-shows-a-banner-test
  (let [robot (ui/robot)
        other-window (do (rig-store/write! (rig-path) rig-doc)
                         (rig-store/try-lock (rig-path)))
        written (slurp (rig-path))]
    (try
      (start-with-rig!)
      (ui/click! robot "#projectors-tab")
      (ui/assert-text! "#rig-file-banner" "This rig is open in another Laser Show window. Changes here aren't saved.")
      (ui/wait-for! #(some? (ui/query "#rig-card-projector-10-0-0-5-1")))
      (testing "the rig is used, but a change never reaches the file"
        (ui/uncheck! robot "#rig-card-enable-projector-10-0-0-5-1")
        (ui/wait-for! #(false? (get-in (app-state) [:projectors :projector-10-0-0-5-1 :enabled?])))
        (Thread/sleep 800)
        (is (= written (slurp (rig-path)))))
      (finally
        (rig-store/release! other-window))))
  (testing "a file from a newer build: used, but not saved"
    (spit (rig-path) (pr-str (assoc rig-doc :rig-file/version 2)))
    (start-with-rig!)
    (ui/click! (ui/robot) "#projectors-tab")
    (ui/assert-text! "#rig-file-banner" "This rig file comes from a newer version of Laser Show. It is used, but not saved.")
    (is (true? (get-in (app-state) [:rig-file :read-only?])))))


;; Opening a show on a rig with no laser on

(deftest open-on-an-empty-rig-asks-to-set-it-up-test
  (let [robot (ui/robot)
        friday (show-zip! "friday.zip"
                          {:projector-10-0-0-5-1 (assoc (laser "Laser L" "10.0.0.5") :safety {:limit [-0.9 -0.9 0.9 0.9] :blocked []})
                           :projector-10-0-0-7-1 (laser "Laser R" "10.0.0.7")}
                          {[0 0] {:items [] :destination-zone {:zone-group-id :auto.laser/projector-10-0-0-5-1}}})]
    (start-with-rig!)
    (open! friday)
    (ui/assert-text! "#rig-setup-title" "Set up your rig from friday.zip?")
    (ui/assert-text! "#rig-setup-lasers" "Laser L · Laser R")
    (testing "Not now leaves the rig as it is"
      (ui/click! robot "#rig-setup-not-now")
      (ui/wait-for! #(not (get-in (app-state) [:ui :dialogs :rig-report :open?])))
      (is (= {} (:projectors (app-state)))))
    (testing "the next show opened asks again; Set up rig makes its lasers this rig"
      (open! friday)
      (ui/click! robot "#rig-setup-button")
      (ui/wait-for! #(= #{:projector-10-0-0-5-1 :projector-10-0-0-7-1} (set (keys (:projectors (app-state))))))
      (is (= {:limit [-0.9 -0.9 0.9 0.9] :blocked []} (get-in (app-state) [:projectors :projector-10-0-0-5-1 :safety])))
      (ui/wait-for! #(not (ui/visible? "#rig-setup-title")))
      (ui/wait-for! 5000 #(= 2 (count (:projectors (:doc (rig-store/read! (rig-path)))))))
      (is (= "Set Up Rig From Show" (get-in (app-state) [:history :undo-stack 0 :description])) "one undo step")
      (is (false? (get-in (app-state) [:project :dirty?])) "and the show isn't unsaved for it"))))


;; The report, after opening a show on a rig that is set up

(defn- start-on-laser-l!
  "Mount the app on a rig file holding Laser L, on."
  []
  (rig-store/write! (rig-path) rig-doc)
  (start-with-rig!))

(deftest cancel-on-top-of-rig-report-keeps-the-report-open-test
  (let [robot (ui/robot)
        id :projector-10-0-0-5-1
        show (show-zip! "graphics.zip"
                        {id (assoc (laser "Laser L" "10.0.0.5") :safety {:limit [-0.5 -0.5 0.5 0.5] :blocked []})}
                        {[0 0] {:items [] :target :graphics}})]
    (start-on-laser-l!)
    (open! show)
    (ui/click! robot "#rig-report-use-show-projector-10-0-0-5-1")
    (ui/assert-text! "#confirm-title" "Replace Laser L's safety zone with the show's?")
    (is (ui/visible? "#rig-report-title") "the report stays mounted underneath its question")
    (ui/press! robot javafx.scene.input.KeyCode/ESCAPE)
    (ui/wait-for! #(not (ui/visible? "#confirm-title")))
    (ui/assert-text! "#rig-report-title" "graphics.zip on this rig")
    (ui/click! robot "#rig-report-close")
    (ui/wait-for! #(not (ui/visible? "#rig-report-title")))))

(deftest report-choose-lasers-opens-the-group-test
  (let [robot (ui/robot)
        show (show-zip! "graphics.zip" {:projector-10-0-0-5-1 (laser "Laser L" "10.0.0.5")}
                        {[0 0] {:items [] :destination-zone {:zone-group-id :graphics}}})]
    (start-on-laser-l!)
    (open! show)
    (ui/assert-text! "#rig-report-title" "graphics.zip on this rig")
    (ui/assert-text! "#rig-report-group-graphics" "⚠ Graphics (used by 1 cue) has no laser on this rig.")
    (ui/click! robot "#rig-report-choose-graphics")
    (ui/wait-for! #(= [:zones :graphics] [(get-in (app-state) [:ui :active-tab]) (get-in (app-state) [:zone-group-ui :selected-group])]))
    (ui/wait-for! #(not (ui/visible? "#rig-report-title")))
    (testing "the Projectors tab's link reopens it while the show is open"
      (ui/click! robot "#projectors-tab")
      (ui/assert-text! "#rig-report-link" "What doesn't fit (1)")
      (ui/click! robot "#rig-report-link")
      (ui/assert-visible! "#rig-report-title"))))

(deftest report-pin-to-repins-the-cues-test
  (let [robot (ui/robot)
        show (show-zip! "beams.zip" {:projector-10-0-0-5-1 (laser "Laser L" "10.0.0.5")
                                     :projector-10-0-0-9-1 (laser "SR Beam" "10.0.0.9")}
                        {[0 0] {:items [] :destination-zone {:zone-group-id :auto.laser/projector-10-0-0-9-1}}
                         [1 0] {:items [] :destination-zone {:zone-group-id :auto.laser/projector-10-0-0-9-1}}})]
    (start-on-laser-l!)
    (open! show)
    (ui/assert-text! "#rig-report-pin-projector-10-0-0-9-1" "⚠ 2 cues are pinned to SR Beam, which isn't on this rig.")
    (ui/click! robot "#rig-report-pin-to-projector-10-0-0-9-1")
    (ui/click! robot "#rig-report-pin-projector-10-0-0-9-1-to-projector-10-0-0-5-1")
    (ui/wait-for! #(= [:auto.laser/projector-10-0-0-5-1 :auto.laser/projector-10-0-0-5-1]
                      (mapv (fn [cell] (get-in (app-state) [:chains :cue-chains cell :target])) [[0 0] [1 0]])))
    (ui/wait-for! #(nil? (ui/query "#rig-report-pin-projector-10-0-0-9-1")))
    (is (true? (get-in (app-state) [:project :dirty?])) "a show edit: unsaved")
    (is (= "Re-pin Cues" (get-in (app-state) [:history :undo-stack 0 :description])))))


;; Closing the window after the rig file couldn't be written

(deftest closing-after-a-failed-rig-write-asks-test
  (let [exits (atom 0)
        stage (ui/main-window)]
    (events/set-exit-fn! #(swap! exits inc))
    (try
      (events/write-state! update :rig-file assoc :path (rig-path) :status :write-failed :error "No space left on device")
      (ui/on-fx-thread #(javafx.event.Event/fireEvent stage (javafx.stage.WindowEvent. stage javafx.stage.WindowEvent/WINDOW_CLOSE_REQUEST)))
      (ui/assert-text! "#unsaved-message" "Your last rig changes couldn't be saved: No space left on device.")
      (ui/assert-visible! "#unsaved-retry")
      (is (zero? @exits) "it doesn't quit yet")
      (ui/click! (ui/robot) "#unsaved-discard")
      (ui/wait-for! #(= 1 @exits))
      (finally (events/set-exit-fn! nil)))))


;; Forget this laser… (Q-ZR-6)

(deftest forget-a-laser-from-its-card-test
  (let [robot (ui/robot)]
    (start-on-laser-l!)
    (events/dispatch! {:event/type :projectors/scan-complete
                       :devices [{:address "10.0.0.9" :host-name "DAC" :unit-id "AA" :port 7255 :status {}
                                  :services [{:service-id 1 :name "Head 1"}] :relays []}]})
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(some? (ui/query "#rig-card-projector-10-0-0-9-1")))
    (testing "only a laser that is off can be forgotten from its card"
      (ui/right-click-node! robot (ui/query "#rig-card-projector-10-0-0-5-1"))
      (ui/wait-for! #(some? (ui/query "#rig-card-identify-projector-10-0-0-5-1")))
      (is (nil? (ui/query "#rig-card-forget-projector-10-0-0-5-1")))
      (ui/press! robot javafx.scene.input.KeyCode/ESCAPE))
    (ui/right-click-node! robot (ui/query "#rig-card-projector-10-0-0-9-1"))
    (ui/click! robot "#rig-card-forget-projector-10-0-0-9-1")
    (ui/assert-text! "#confirm-title" "Forget Head 1?")
    (ui/click! robot "#confirm-ok")
    (ui/wait-for! #(not (contains? (:projectors (app-state)) :projector-10-0-0-9-1)))
    (ui/wait-for! 5000 #(not (contains? (:projectors (:doc (rig-store/read! (rig-path)))) :projector-10-0-0-9-1)))
    (is (= "Forget Laser" (get-in (app-state) [:history :undo-stack 0 :description])))))
