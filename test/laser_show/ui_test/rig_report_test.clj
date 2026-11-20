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
