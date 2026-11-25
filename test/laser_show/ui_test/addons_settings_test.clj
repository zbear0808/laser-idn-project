(ns laser-show.ui-test.addons-settings-test
  "Settings → Add-ons (plans/2026-11-19-output-drivers/02 § D3): what loaded
   from the add-ons folder at startup, Install add-on… and Open add-ons
   folder; app/start! loading the add-ons before the startup scan; and an
   add-on's driver added in Add Manual (its Type combo and driver fields)
   and streaming from the UI through the real output path.

   The jars are real ones, built by laser-show.output.jar-builder from
   test-resources/addons into a temp add-ons folder, and the app is started
   on that folder (app/start-test!'s :addons-dir), as a Mac on Apple Silicon
   (:addons-platform), so every test runs the same on every OS. No real
   file chooser or file manager opens: the chooser effect and the folder
   opener's platform calls are stubbed. Nothing reaches
   ~/LaserShowProjects: the test runner points laser-show.addons-dir and
   laser-show.rig-file at temp folders."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.app :as app]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.input-mapper :as input-mapper]
            [laser-show.input.gamepad :as gamepad]
            [laser-show.input.link :as link]
            [laser-show.output.addons :as addons]
            [laser-show.output.driver :as driver]
            [laser-show.output.jar-builder :as jar]
            [laser-show.output.lsps-receiver :as rx]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.state.core :as state]
            [laser-show.state.rig-store :as rig-store]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [javafx.scene Parent]
           [javafx.scene.control Labeled]))

(def ^:dynamic ^File *dir*
  "This test's temp folder, deleted after it."
  nil)

(defn- with-temp-dir [f]
  (let [dir (jar/temp-dir! "laser-show-addons-ui")]
    (try
      (binding [*dir* dir] (f))
      (finally
        (app/stop!)
        (jar/delete-tree! dir)))))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app with-temp-dir)

(def ^:private mac-arm {:os :macos :arch :aarch64})

(defn- addons-dir ^File []
  (io/file *dir* "Zoë Smith" "addons"))

(defn- start-app!
  "Mount the app again with its add-ons loaded from (addons-dir), with the
   effects in stubs in place of the real ones."
  ([] (start-app! {}))
  ([stubs]
   (with-redefs [events/effects (merge events/effects stubs)]
     (app/start-test! nil {:addons-dir (addons-dir) :addons-platform mac-arm}))
   (ui/sync-fx!)))

(defn- open-settings! []
  (ui/click! (ui/robot) "#settings-tab")
  (ui/scroll-into-view! "#addons-section")
  (ui/assert-visible! "#addons-section"))

(defn- texts
  "The texts of the labels and buttons under the node selector finds."
  [selector]
  (let [root (ui/query selector)]
    (ui/on-fx-thread
     #(into []
            (keep (fn [n] (when (instance? Labeled n) (.getText ^Labeled n))))
            (tree-seq (fn [n] (instance? Parent n))
                      (fn [^Parent p] (seq (.getChildrenUnmodifiable p)))
                      root)))))

(defn- text [selector] (ui/node-text selector))

(defn- fire!
  "Fire a button found by selector, once it is scrolled into view."
  [selector]
  (ui/scroll-into-view! selector)
  (ui/click! (ui/robot) selector))


;; The list


(deftest lists-loaded-failed-and-skipped-addons-test
  (let [sample (jar/build! (addons-dir) "sample")
        _ (jar/build! (addons-dir) "broken")
        _ (jar/build! (addons-dir) "windows-only")]
    (start-app!)
    (open-settings!)
    (is (= (.getAbsolutePath (addons-dir)) (text "#addons-folder-path")) "the folder, as the OS writes it")
    (testing "by file name: broken.jar, sample.jar, windows-only.jar"
      (ui/wait-for! #(some? (ui/query "#addon-row-2")))
      (is (= "Broken add-on 1.0.0" (text "#addon-row-0-title")))
      (is (str/starts-with? (text "#addon-row-0-state") "Failed: Could not load laser-show-test-addon-"))
      (is (str/ends-with? (text "#addon-row-0-state") ".sample.driver: This add-on is broken on purpose"))
      (is (nil? (ui/query "#addon-row-0-drivers")) "no drivers")
      (is (= "broken.jar" (text "#addon-row-0-file")))

      (is (= "Sample add-on 1.0.0" (text "#addon-row-1-title")))
      (is (= "Loaded" (text "#addon-row-1-state")))
      (is (= "Drivers: Sample add-on stream (:test/sample-stream)" (text "#addon-row-1-drivers")))
      (is (= (.getName ^File (:file sample)) (text "#addon-row-1-file")))

      (is (= "Windows-only add-on 1.0.0" (text "#addon-row-2-title")))
      (is (= "Skipped: Not available on macOS (aarch64); it supports Windows" (text "#addon-row-2-state")))
      (is (= "windows-only.jar" (text "#addon-row-2-file")))
      (is (nil? (ui/query "#addon-row-3"))))
    (is (nil? (ui/query "#addons-empty")))
    (is (some? (driver/lookup :test/sample-stream)) "the sample's driver is registered")
    (is (= "Sample add-on stream" (get-in (state/get-raw-state) [:backend :output :drivers :test/sample-stream :name]))
        "and its descriptor is in app state, for the Type picker")
    (is (some #{"To remove an add-on, quit Laser Show and delete its file from the folder."} (texts "#addons-section")))
    (is (not-any? #(re-find #"(?i)trust|secur" %) (texts "#addons-section")) "no trust or security note (Q-7)")
    (testing "stopping the app unloads them, so the next start loads them again"
      (app/stop!)
      (is (nil? (driver/lookup :test/sample-stream)))
      (start-app!)
      (open-settings!)
      (ui/wait-for! #(= "Loaded" (text "#addon-row-1-state"))))))

(deftest empty-addons-folder-test
  (start-app!)
  (open-settings!)
  (is (= (.getAbsolutePath (addons-dir)) (text "#addons-folder-path")))
  (is (.isDirectory (addons-dir)) "made at startup, so there is a folder to open")
  (is (= "No add-ons yet. Install one, or put its .jar in the folder and restart Laser Show."
         (text "#addons-empty")))
  (is (nil? (ui/query "#addon-row-0")))
  (is (= "Install add-on…" (text "#addons-install-button")))
  (is (= "Open add-ons folder" (text "#addons-open-folder-button")))
  (is (= "To remove an add-on, quit Laser Show and delete its file from the folder."
         (text "#addons-remove-hint")))
  (testing "start-test! without :addons-dir loads none, and shows the test runner's temp folder"
    (app/start-test!)
    (ui/sync-fx!)
    (open-settings!)
    (is (= (.getAbsolutePath (addons/addons-dir)) (text "#addons-folder-path")))
    (is (not (str/starts-with? (text "#addons-folder-path")
                               (.getAbsolutePath (io/file (System/getProperty "user.home") "LaserShowProjects"))))
        "never the real ~/LaserShowProjects/addons")))


;; Install add-on…


(deftest install-button-installs-and-shows-pending-test
  (let [staging (io/file *dir* "Downloads")
        {:keys [file]} (jar/build! staging "sample")
        asked (atom [])
        chooser (fn [opts dispatch]
                  (swap! asked conj (dissoc opts :on-result))
                  (dispatch (assoc (:on-result opts) :file-path (.getAbsolutePath ^File file))))]
    (start-app! {:fx/show-file-chooser chooser})
    (open-settings!)
    (ui/assert-visible! "#addons-empty")
    (fire! "#addons-install-button")
    (ui/wait-for! #(some? (ui/query "#addon-pending-row-0")))
    (is (= [{:title "Install add-on" :mode :open :initial-directory (System/getProperty "user.home")
             :extension-filters [{:description "Laser Show add-ons (*.jar)" :extensions ["*.jar"]}]}]
           @asked)
        "a .jar chooser, in the home folder")
    (is (.isFile (io/file (addons-dir) "sample.jar.new")) "copied in as .jar.new, for the next start")
    (is (not (.exists (io/file (addons-dir) "sample.jar"))))
    (ui/assert-text! "#status-notice-text" "Installed Sample add-on 1.0.0. Restart Laser Show to load it.")
    (is (= "Sample add-on 1.0.0" (text "#addon-pending-row-0-title")))
    (is (= "Installed, loads at next start" (text "#addon-pending-row-0-state")))
    (is (= "sample.jar.new" (text "#addon-pending-row-0-file")))
    (is (nil? (ui/query "#addons-empty")))
    (is (nil? (driver/lookup :test/sample-stream)) "nothing loads until the restart")
    (testing "a jar it refuses: an error, and nothing new is pending"
      (let [{bad :file} (jar/build! staging "api2")]
        (start-app! {:fx/show-file-chooser (fn [opts dispatch]
                                             (dispatch (assoc (:on-result opts) :file-path (.getAbsolutePath ^File bad))))})
        (open-settings!)
        (testing "after the restart, the pending jar loaded"
          (ui/wait-for! #(= "Loaded" (text "#addon-row-0-state")))
          (is (= "sample.jar" (text "#addon-row-0-file")))
          (is (nil? (ui/query "#addon-pending-row-0"))))
        (fire! "#addons-install-button")
        (ui/assert-text! "#status-notice-text" "Could not install api2.jar: Built for output API 2; this app has API 1")
        (is (nil? (ui/query "#addon-pending-row-0")))
        (is (not (.exists (io/file (addons-dir) "api2.jar.new"))))))
    (testing "cancelled: nothing"
      (start-app! {:fx/show-file-chooser (fn [opts dispatch] (dispatch (assoc (:on-result opts) :file-path nil)))})
      (open-settings!)
      (fire! "#addons-install-button")
      (ui/sync-fx!)
      (is (nil? (ui/query "#addon-pending-row-0")))
      (is (nil? (get-in (state/get-raw-state) [:ui :notice]))))))


;; Open add-ons folder


(deftest open-folder-button-test
  (let [opened (atom [])]
    (start-app!)
    (open-settings!)
    (try
      (testing "the file manager opens the folder"
        (events/set-folder-opener! {:desktop-open (fn [^File dir] (swap! opened conj (.getAbsolutePath dir)) true)
                                    :os (constantly :macos)})
        (fire! "#addons-open-folder-button")
        (ui/wait-for! #(= [(.getAbsolutePath (addons-dir))] @opened))
        (is (nil? (get-in (state/get-raw-state) [:ui :notice])) "no notice"))
      (testing "nothing can open it: a notice with the full path"
        (events/set-folder-opener! {:desktop-open (constantly false) :os (constantly :macos)})
        (fire! "#addons-open-folder-button")
        (ui/assert-text! "#status-notice-text"
                         (str "Couldn't open the add-ons folder. It is at " (.getAbsolutePath (addons-dir)))))
      (finally
        (events/set-folder-opener! nil)))))


;; Startup


(defn- with-system-property [k v f]
  (let [old (System/getProperty k)]
    (System/setProperty k v)
    (try (f) (finally (if old (System/setProperty k old) (System/clearProperty k))))))

(deftest start-loads-addons-before-the-scan-test
  ;; The real app/start!, with its services that reach devices or the
  ;; network stubbed, and dispatch! recording what it is sent (so the scan
  ;; it asks for never runs)
  (app/stop!)
  (jar/build! (addons-dir) "sample")
  (let [order (atom [])
        load-all! addons/load-all!
        open! output-runtime/open!]
    (with-system-property "laser-show.addons-dir" (.getAbsolutePath (addons-dir))
      (fn []
        (try
          (with-redefs [addons/load-all! (fn [opts]
                                           (let [statuses (load-all! opts)]
                                             (swap! order conj [:load-all (:dir opts) (mapv :state statuses)])
                                             statuses))
                        output-runtime/open! (fn [] (swap! order conj [:open-output]) (open!))
                        output-runtime/install-shutdown-hook! (fn [])
                        rig-store/install-shutdown-hook! (fn [])
                        events/dispatch! (fn [event]
                                           (swap! order conj [:dispatch (:event/type event)
                                                              (select-keys event [:addons :drivers :dir])]))
                        show-clock/start! (fn [])
                        link/connect! (fn [_ _] true)
                        input-mapper/start-mapper! (fn [])
                        gamepad/start! (fn [& _])]
            (app/start!)
            (ui/sync-fx!))
          (finally
            (app/stop!)
            (input-mapper/set-dispatch-fn! nil)))))
    (let [steps (mapv first @order)
          dispatched (filterv #(= :dispatch (first %)) @order)
          [_ _ {:keys [addons drivers dir]}] (first dispatched)]
      (is (= [:load-all (addons-dir) [:loaded]] (first @order)) "the real folder's jars, first")
      (is (< (.indexOf steps :load-all) (.indexOf steps :open-output)) "before the output runtime opens")
      (is (= :addons/loaded (second (first dispatched))) "the first event")
      (is (= [[:test/sample :loaded]] (mapv (juxt :id :state) addons)) "with the statuses")
      (is (nat-int? (:load-ms (first addons))))
      (is (= "Sample add-on stream" (get-in drivers [:test/sample-stream :name])) "and the add-on's driver")
      (is (contains? drivers :idn))
      (is (= (.getAbsolutePath (addons-dir)) dir))
      (is (< (.indexOf (mapv second dispatched) :addons/loaded)
             (.indexOf (mapv second dispatched) :projectors/scan-network))
          "before the startup scan, so it sees every driver"))))


;; Through the real output path


(defn- datagrams
  "What the receiver got, as text."
  [receiver]
  (mapv #(String. ^bytes (:bytes %) StandardCharsets/UTF_8) @(:received receiver)))

(deftest addon-driver-appears-and-streams-from-the-ui-test
  (jar/build! (addons-dir) "udp")
  (let [receiver (rx/start!)]
    (try
      (start-app!)
      (open-settings!)
      (testing "its driver is listed in Settings → Add-ons"
        (is (= "UDP test add-on 1.0.0" (text "#addon-row-0-title")))
        (is (= "Loaded" (text "#addon-row-0-state")))
        (is (= "Drivers: UDP test stream (:test/udp-stream)" (text "#addon-row-0-drivers"))))
      (is (= {:id :test/udp-stream :name "UDP test stream" :transport :network :addon :test/udp}
             (select-keys (get-in (state/get-raw-state) [:backend :output :drivers :test/udp-stream])
                          [:id :name :transport :addon]))
          "its descriptor, which Add Manual's Type combo lists")
      (testing "it is added by hand through Add Manual's Type combo and its fields"
        (ui/click! (ui/robot) "#projectors-tab")
        (ui/click! (ui/robot) "#projectors-add-manual-button")
        (ui/wait-for! #(some #{"Add Projector"} (ui/window-titles)))
        (ui/select! (ui/robot) "#add-projector-driver-combo" ":test/udp-stream")
        (ui/wait-for! #(= :test/udp-stream (get-in (state/get-raw-state) [:ui :dialogs :add-projector-manual :driver])))
        (ui/assert-text! "#driver-field-host" "127.0.0.1")
        (is (= "" (text "#driver-field-port")) "a required field with no default starts empty")
        (ui/assert-disabled! "#add-projector-confirm-button")
        (ui/fill! (ui/robot) "#driver-field-port" (str (:port receiver)))
        (ui/fill! (ui/robot) "#add-projector-name-field" "UDP add-on")
        (ui/wait-for! #(ui/enabled? "#add-projector-confirm-button"))
        (ui/click! (ui/robot) "#add-projector-confirm-button")
        (ui/wait-for! #(some (fn [p] (= :test/udp-stream (:driver p))) (vals (:projectors (state/get-raw-state))))))
      (let [[id p] (some (fn [[id p]] (when (= :test/udp-stream (:driver p)) [id p])) (:projectors (state/get-raw-state)))]
        (is (= {:name "UDP add-on" :driver :test/udp-stream :driver-config {:host "127.0.0.1" :port (:port receiver)}
                :stable-id [:manual :test/udp-stream id] :enabled? true}
               (select-keys p [:name :driver :driver-config :stable-id :enabled?]))
            "01 §5.1's shape, the host's default filled in")
        (ui/wait-for! #(= "UDP test stream" (text (str "#rig-card-address-" (name id)))))
        (is (nil? (ui/query (str "#rig-card-address-detail-" (name id)))) "its fields have no :summary?, so the card has no second line")
        (fixtures/with-clock
         (fn [_]
           (ui/click! (ui/robot) "#stream-btn")
           (ui/assert-text! "#streaming-status-label" "Streaming to 1 of 1")
           (ui/wait-for! #(<= 5 (count (datagrams receiver))))
           (is (every? #(str/starts-with? % (str "output-streaming-" (name id) " ")) (datagrams receiver))
               "sent by the add-on's driver, on the projector's own streaming thread")
           (ui/click! (ui/robot) "#stream-btn")
           (ui/assert-text! "#streaming-status-label" "Not streaming"))))
      (let [n (count (datagrams receiver))]
        (Thread/sleep 300)
        (is (= n (count (datagrams receiver))) "nothing after Stop"))
      (finally
        (rx/stop! receiver)))))
