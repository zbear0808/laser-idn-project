(ns laser-show.events.core-test
  "Tests for the event wiring in laser-show.events.core: which effects continue
   the current event inline, which call back through the dispatcher they are
   given, the no-app dispatch! fallback, and the dev-mode lost-update detector."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.tools.logging.test :as log-test]
   [laser-show.events.core :as events]
   [laser-show.events.handlers :as handlers]
   [laser-show.events.handlers.timing :as timing]
   [laser-show.idn.hello :as idn-hello]
   [laser-show.output.discovery :as discovery]
   [laser-show.output.driver :as driver]
   [laser-show.output.jar-builder :as jar]
   [laser-show.output.recording-driver :as rd]
   [laser-show.state.core :as state]
   [laser-show.state.domains :as domains])
  (:import [java.io File]
           [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each (fn [f]
                      (state/init-state! (domains/build-initial-state))
                      (events/set-dispatch-fn! nil)
                      (events/set-exit-fn! nil)
                      (try (f) (finally (events/set-exit-fn! nil)))))

(defn- with-dev-property
  "Run f with the laser-show.dev system property set to value (nil clears it),
   then restore it, so these tests don't depend on how the JVM was started."
  [value f]
  (let [old (System/getProperty "laser-show.dev")]
    (if value
      (System/setProperty "laser-show.dev" value)
      (System/clearProperty "laser-show.dev"))
    (try (f)
         (finally (if old
                    (System/setProperty "laser-show.dev" old)
                    (System/clearProperty "laser-show.dev"))))))


;; Wiring


(deftest effects-and-co-effects-are-shared-maps-test
  (is (= #{:state :time} (set (keys events/co-effects))))
  (is (every? #(contains? events/effects %)
              [:state :dispatch :dispatch-later :link/sync-to-downbeat
               :input/await-learn :multi-engine/start :multi-engine/stop :multi-engine/refresh
               :output/write-status
               :project/save :project/load :fx/show-file-chooser :system/exit :system/open-url
               :dev/reload :clipboard/copy-effects :clipboard/paste-effects
               :clipboard/write :clipboard/read
               :projectors/scan :addons/install :system/open-folder]))
  (is (not (contains? events/effects :clipboard/paste-projector-effects))
      "a projector chain pastes through the list editor's own clipboard (zones Z0)")
  (is (not (contains? events/effects :timing/calculate-bpm)) "a tap sets the BPM in its own handler's state"))

(deftest dispatch-continues-inline-test
  (testing "a :dispatch follow-up runs now, in the same call, not through the dispatcher"
    (let [exits (atom 0)
          later (atom [])]
      (events/set-exit-fn! #(swap! exits inc))
      (state/swap-state! assoc-in [:ui :dialogs :unsaved-confirm]
                         {:open? true :pending {:event/type :file/exit}})
      (events/event-handler {:event/type :file/confirm-discard} #(swap! later conj %))
      (is (= 1 @exits))
      (is (= [] @later))
      (is (false? (get-in (state/get-raw-state) [:ui :dialogs :unsaved-confirm :open?]))))))

(deftest tap-tempo-sets-bpm-in-one-state-test
  (let [later (atom [])]
    (doseq [t [1000 1400 1800]]
      (events/event-handler {:event/type :timing/tap-tempo :input-time t} #(swap! later conj %)))
    (is (= [] @later))
    (is (= 150.0 (get-in (state/get-raw-state) [:timing :bpm])) "taps 400 ms apart")
    (testing "the handler's state has the BPM, and nothing follows it"
      (let [result (timing/handle {:event/type :timing/tap-tempo :time 2200
                                   :state (assoc-in (state/get-raw-state) [:timing :bpm] 90.0)})]
        (is (= [:state] (keys result)))
        (is (= 150.0 (get-in result [:state :timing :bpm])))))))

(deftest dispatch-later-calls-back-through-the-dispatcher-test
  (let [got (promise)]
    ((get events/effects :dispatch-later) {:event {:event/type ::later} :delay-ms 0}
                                          #(deliver got %))
    (is (= {:event/type ::later} (deref got 2000 ::timeout)))))


;; No-app fallback


(deftest fallback-runs-every-effect-test
  (testing "without an app, dispatch! runs effects such as :system/exit"
    (let [exits (atom 0)]
      (events/set-exit-fn! #(swap! exits inc))
      (events/dispatch! {:event/type :file/exit})
      (is (= 1 @exits)))))


;; Lost-update detector (dev mode)


(defn- detector-entries []
  (filterv #(and (= "laser-show.events.core" (str (:logger-ns %)))
                 (str/includes? (str (:message %)) "lost update"))
           (log-test/the-log)))

(defn- set-bpm-with-a-write-in-between!
  "Handle :timing/set-bpm while write! changes app state between the event's
   snapshot and its write-back."
  [write!]
  (let [handle timing/handle]
    (with-redefs [timing/handle (fn [event] (write!) (handle event))]
      (events/event-handler {:event/type :timing/set-bpm :bpm 128.0}))))

(deftest lost-update-detector-test
  (with-dev-property "true"
    (fn []
      (testing "a write by another writer between snapshot and reset logs a WARN"
        (log-test/with-log
          (set-bpm-with-a-write-in-between!
           #(state/assoc-in-state! [:projectors :elsewhere] {:name "written meanwhile"}))
          (let [entries (detector-entries)]
            (is (= 1 (count entries)))
            (is (= :warn (:level (first entries))))
            (is (str/includes? (:message (first entries)) ":timing/set-bpm"))
            (is (str/includes? (:message (first entries)) ":projectors")))
          (is (= 128.0 (get-in (state/get-raw-state) [:timing :bpm])))))
      (testing "the cues and transport are the event flow's alone now: a write to them in between is a WARN"
        (log-test/with-log
          (set-bpm-with-a-write-in-between!
           #(state/assoc-in-state! [:playback :active-cues [0 0]] {:run (random-uuid)}))
          (is (= 1 (count (filter #(= :warn (:level %)) (detector-entries)))))))
      (testing "nothing in between logs nothing"
        (log-test/with-log
          (events/event-handler {:event/type :timing/set-bpm :bpm 100.0})
          (is (empty? (detector-entries)))))
      (testing "the snapshot does not end up in app state"
        (is (empty? (meta (state/get-raw-state))))))))

(deftest lost-update-check-has-no-exemptions-test
  (testing "the show clock writes app state only through events now, so a write to what
            its mirror wrote is a lost update like any other (show clock 03 §7, invariant 3)"
    (with-dev-property "true"
      (fn []
        (doseq [[path value] [[[:timing :beat-index] 2]
                              [[:backend :streaming :frame-stats] {:avg-latency-us 10}]
                              [[:backend :input :mtc] {:state :running :fps 25 :text "00:00:01:00"}]
                              [[:timing :global-clock] {:accumulated-beats 4.0}]
                              [[:playback :positions] {[0 0] 2.5}]
                              [[:backend :streaming :current-frame] {:points []}]]]
          (log-test/with-log
            (set-bpm-with-a-write-in-between! #(state/assoc-in-state! path value))
            (is (= 1 (count (filter #(= :warn (:level %)) (detector-entries)))) (str path))))))))

(deftest lost-update-detector-is-off-outside-dev-mode-test
  (with-dev-property nil
    (fn []
      (log-test/with-log
        (set-bpm-with-a-write-in-between!
         #(state/assoc-in-state! [:projectors :elsewhere] {:name "written meanwhile"}))
        (is (empty? (detector-entries)))))))


;; :fx/event placeholders


(defn- seen-by-handler
  "The event as the pipeline's handlers see it."
  [event]
  ((#'events/with-fx-event identity) event))

(deftest fx-event-placeholder-test
  (testing "what the handler sees (UX plan 00 §3, Phase 0)"
    (is (= {:name "Balcony"}
           (:updates (seen-by-handler {:event/type :ui/update-dialog-data :dialog-id :zone-group-editor
                                       :updates {:name :fx/event} :fx/event "Balcony"}))))
    (is (false? (:enabled? (seen-by-handler {:event/type :projectors/set-service-enabled :host "10.0.0.5"
                                             :service-id 1 :enabled? :fx/event :fx/event false}))))
    (is (= 0.4 (:brightness (seen-by-handler {:event/type :projectors/set-calibration-brightness
                                              :brightness :fx/event :fx/event 0.4}))))
    (doseq [unchanged [{:event/type :timing/set-bpm :bpm 128.0}
                       {:event/type :x :updates {:label ":fx/event"}}
                       {:event/type :x :updates {:name {:nested :fx/event}} :fx/event "deeper"}]]
      (is (identical? unchanged (seen-by-handler unchanged)) (pr-str unchanged)))
    (testing ":state is never looked at"
      (let [state {:placeholder :fx/event}
            event (seen-by-handler {:event/type :x :state state :name :fx/event :fx/event "x"})]
        (is (identical? state (:state event)))
        (is (= "x" (:name event))))))
  (let [state (assoc-in (domains/build-initial-state) [:projectors :projector-10-0-0-5-1]
                        {:name "Out 1" :host "10.0.0.5" :service-id 1 :enabled? true :zone-groups [:all]})
        after (fn [event] (:state (events/handle-event-pipeline (assoc event :state state))))]
    (testing "through the pipeline, the handlers of the views' placeholders store the value"
      (is (= "Balcony" (get-in (after {:event/type :ui/update-dialog-data :dialog-id :zone-group-editor
                                       :updates {:name :fx/event} :fx/event "Balcony"})
                               [:ui :dialogs :zone-group-editor :name])))
      (is (false? (get-in (after {:event/type :projectors/set-service-enabled :host "10.0.0.5" :service-id 1
                                  :enabled? :fx/event :fx/event false})
                          [:projectors :projector-10-0-0-5-1 :enabled?])))
      (is (= 0.4 (get-in (after {:event/type :projectors/set-calibration-brightness
                                 :brightness :fx/event :fx/event 0.4})
                         [:projector-ui :calibration-brightness]))))
    (testing "a placeholder without :fx/event (dispatched by hand) is left, with a warning"
      (log-test/with-log
        (is (= :fx/event (get-in (after {:event/type :ui/update-dialog-data :dialog-id :zone-group-editor
                                         :updates {:name :fx/event}})
                                 [:ui :dialogs :zone-group-editor :name])))
        (is (some #(and (= :warn (:level %))
                        (= "laser-show.events.core" (str (:logger-ns %)))
                        (str/includes? (str (:message %)) ":ui/update-dialog-data"))
                  (log-test/the-log)))))))


;; Add-ons: installing and the folder (plans/2026-11-19-output-drivers/02 § D3)


(defn- with-temp-dir
  "(f dir) with a new temp folder, deleted after."
  [f]
  (let [dir (jar/temp-dir! "laser-show-core-test")]
    (try (f dir) (finally (jar/delete-tree! dir)))))

(defn- install-through-the-pipeline
  "Install add-on… from the chooser's result for src into addons-dir: the
   event the :addons/install effect dispatches back, then that event run,
   as the app would."
  [src addons-dir]
  (state/swap-state! assoc-in [:backend :output :dir] (.getAbsolutePath ^File addons-dir))
  (let [got (promise)]
    (events/event-handler {:event/type :addons/install-picked :file-path (.getAbsolutePath ^File src)}
                          #(deliver got %))
    (let [event (deref got 5000 ::timeout)]
      (when (map? event)
        (events/event-handler event (fn [_])))
      event)))

(deftest install-effect-dispatches-installed-test
  (with-temp-dir
    (fn [dir]
      (let [{:keys [file]} (jar/build! (io/file dir "downloads") "sample")
            addons-dir (io/file dir "Zoë Smith" "addons")
            event (install-through-the-pipeline file addons-dir)
            pending (io/file addons-dir "sample.jar.new")]
        (is (= {:event/type :addons/installed
                :file (.getAbsolutePath pending)
                :replaced? false
                :descriptor {:id :test/sample :name "Sample add-on" :version "1.0.0"}}
               event)
            "from the effect's own thread, through dispatch")
        (is (.isFile pending) "the folder made, the jar in it as .jar.new")
        (is (= "Installed Sample add-on 1.0.0. Restart Laser Show to load it."
               (get-in (state/get-raw-state) [:ui :notice :text])))
        (is (= [(.getAbsolutePath pending)]
               (mapv :file (get-in (state/get-raw-state) [:backend :output :pending-installs]))))
        (testing "again, with a jar of that name already loaded from the folder: Replaced"
          (jar/build! addons-dir "sample")
          (is (true? (:replaced? (install-through-the-pipeline file addons-dir))))
          (is (= "Replaced Sample add-on 1.0.0. Restart Laser Show to load it."
                 (get-in (state/get-raw-state) [:ui :notice :text]))))))))

(deftest install-effect-dispatches-failed-for-a-bad-jar-test
  (with-temp-dir
    (fn [dir]
      (let [{:keys [file]} (jar/build! (io/file dir "downloads") "api2")
            addons-dir (io/file dir "addons")
            event (install-through-the-pipeline file addons-dir)]
        (is (= {:event/type :addons/install-failed
                :file-path (.getAbsolutePath ^File file)
                :error "Built for output API 2; this app has API 1"}
               event))
        (is (empty? (.listFiles addons-dir)) "nothing written")
        (is (= {:text "Could not install api2.jar: Built for output API 2; this app has API 1" :level :error}
               (select-keys (get-in (state/get-raw-state) [:ui :notice]) [:text :level])))
        (is (= [] (get-in (state/get-raw-state) [:backend :output :pending-installs])))
        (testing "a file that isn't a jar"
          (let [txt (doto (io/file dir "notes.txt") (spit "hi"))]
            (is (= "notes.txt is not a .jar file" (:error (install-through-the-pipeline txt addons-dir))))))))))

(defn- open-folder
  "Run Open add-ons folder for path with the platform calls stubbed by
   opener: {:dispatched [events] :calls [[step path] …]}."
  [path opener]
  (let [calls (atom [])
        dispatched (atom [])
        record (fn [step result]
                 (fn [^File dir]
                   (swap! calls conj [step (.getAbsolutePath dir)])
                   (if (instance? Throwable result) (throw result) result)))]
    (state/swap-state! assoc-in [:backend :output :dir] path)
    (events/set-folder-opener! {:desktop-open (record :desktop (:desktop opener))
                                :xdg-open (record :xdg-open (:xdg-open opener))
                                :os (constantly (:os opener))})
    (try
      ;; What the handler asks for (open-folder-runs-effect-test); the
      ;; effect's future is waited for
      (let [effect (:system/open-folder (handlers/handle-event {:event/type :addons/open-folder
                                                                :state (state/get-raw-state)}))]
        (deref ((get events/effects :system/open-folder) effect #(swap! dispatched conj %)) 5000 nil)
        {:dispatched @dispatched :calls @calls})
      (finally
        (events/set-folder-opener! nil)))))

(deftest open-folder-falls-back-to-xdg-open-test
  (with-temp-dir
    (fn [dir]
      (let [path (.getAbsolutePath (io/file dir "LaserShowProjects" "addons"))]
        (testing "Desktop can open it: only that"
          (let [{:keys [calls dispatched]} (open-folder path {:desktop true :os :linux})]
            (is (= [[:desktop path]] calls))
            (is (= [] dispatched))
            (is (.isDirectory (io/file path)) "made first, so there is something to open")))
        (testing "on Linux, Desktop can't (no OPEN, or it throws): xdg-open"
          (doseq [desktop [false (RuntimeException. "no desktop")]]
            (let [{:keys [calls dispatched]} (open-folder path {:desktop desktop :xdg-open true :os :linux})]
              (is (= [[:desktop path] [:xdg-open path]] calls))
              (is (= [] dispatched) "opened: no notice"))))
        (testing "not on Linux: no xdg-open"
          (is (= [[:desktop path]] (:calls (open-folder path {:desktop false :xdg-open true :os :macos})))))))))

(deftest open-folder-falls-back-to-a-notice-test
  (with-temp-dir
    (fn [dir]
      (let [path (.getAbsolutePath (io/file dir "Zoë Smith" "addons"))
            notice (str "Couldn't open the add-ons folder. It is at " path)]
        (doseq [opener [{:desktop false :os :macos}
                        {:desktop false :xdg-open false :os :linux}
                        {:desktop (RuntimeException. "no") :xdg-open (java.io.IOException. "no xdg-open") :os :linux}]]
          (testing (pr-str opener)
            (let [{:keys [dispatched]} (open-folder path opener)]
              (is (= [{:event/type :addons/open-folder-failed :path path}] dispatched))
              (events/event-handler (first dispatched) (fn [_]))
              (is (= {:text notice :level :info}
                     (select-keys (get-in (state/get-raw-state) [:ui :notice]) [:text :level]))))))
        (testing "a folder that can't be made: the notice too, and nothing is asked to open it"
          (let [file (doto (io/file dir "a-file") (spit "x"))
                path (.getAbsolutePath (io/file file "addons"))
                {:keys [dispatched calls]} (open-folder path {:desktop true :os :macos})]
            (is (= [] calls))
            (is (= [{:event/type :addons/open-folder-failed :path path}] dispatched))))))))


;; Scan (output drivers D4, 01 §5.3). IDN's discovery is always stubbed and
;; every discoverer is a fake that returns data: nothing is sent on the
;; network, and the interfaces are made up too


(def ^:private fake-interfaces
  [{:name "lo0" :up? true :loopback? true :broadcasts ["127.255.255.255"]}
   {:name "en0" :up? true :loopback? false :broadcasts ["192.168.1.255"]}
   {:name "en1" :up? true :loopback? false :broadcasts ["10.0.0.255"]}])

(def ^:private idn-raw
  "A device as idn-hello/discover-devices-with-services returns it."
  {:address "10.0.0.5" :host-name "" :unit-id "0A 1B" :port nil :status {:ok true}
   :protocol-version {:major 1 :minor 0} :services [{:service-id 1 :name "Out 1"}] :relays nil :extra 1})

(def ^:private idn-found
  "idn-raw as the scan reports it (as before D4, with :driver :idn)."
  {:address "10.0.0.5" :host-name "10.0.0.5" :unit-id "0A 1B" :port 7255 :status {:ok true}
   :protocol-version {:major 1 :minor 0} :services [{:service-id 1 :name "Out 1"}] :relays [] :driver :idn})

(def ^:private nova-device
  {:device-key "NV-00123" :name "Nova 00123"
   :outputs [{:output-id 0 :name "Output A" :driver-config {:serial "NV-00123" :output 0}}]})

(def ^:private zeta-device
  {:device-key "0x1A2B" :name "Zeta 1A2B"
   :outputs [{:output-id 0 :name "Left" :driver-config {:host "10.0.0.9" :port 7300}}
             {:output-id 1 :name "Right" :driver-config {:host "10.0.0.9" :port 7301}}]})

(defn- discoverer
  "A recording driver with a fake discovery function. Warnings use its full name."
  [id n discover]
  (assoc (:driver (rd/recording-driver {:id id})) :name n :short-name "Test" :discover discover))

(defn- scan!
  "Run the :projectors/scan effect as Scan does, with IDN's discovery
   replaced by idn, the interfaces by fake-interfaces and drivers
   registered: the event it dispatched."
  [{:keys [idn drivers timeout-ms interfaces broadcast-address]
    :or {idn (fn [& _] []) interfaces (constantly fake-interfaces) broadcast-address "255.255.255.255"}}]
  (assert (not-any? :discover (driver/registered)) "no real :discover is registered")
  (let [got (promise)]
    (with-redefs [idn-hello/discover-devices-with-services idn
                  discovery/interfaces interfaces]
      (run! driver/register! drivers)
      (try
        ((get events/effects :projectors/scan) (cond-> {:broadcast-address broadcast-address}
                                                 timeout-ms (assoc :timeout-ms timeout-ms))
                                               #(deliver got %))
        (deref got 10000 ::timeout)
        (finally
          (run! #(driver/unregister! (:id %)) drivers))))))

(deftest scan-runs-every-discoverer-test
  (let [^CountDownLatch started (CountDownLatch. 3)
        seen (atom {})
        loader (clojure.lang.DynamicClassLoader. (.getContextClassLoader (Thread/currentThread)))
        meet! (fn [k opts]
                (.countDown started)
                (swap! seen assoc k {:opts opts
                                     :loader (.getContextClassLoader (Thread/currentThread))
                                     :together? (.await started 2 TimeUnit/SECONDS)}))
        nova (assoc (discoverer :acme/nova "Acme Nova"
                                (fn [opts]
                                  (meet! :nova opts)
                                  [(update nova-device :outputs
                                           (fn [os] (map (fn [o]
                                                           (when-not (identical? loader (.getContextClassLoader (Thread/currentThread)))
                                                             (throw (ex-info "outputs read outside the driver's loader" {})))
                                                           o) os)))]))
                    ::driver/loader loader)
        zeta (discoverer :zeta/beam "Zeta" (fn [opts] (meet! :zeta opts) [zeta-device]))
        event (scan! {:idn (fn [& args] (meet! :idn (vec args)) [idn-raw]) :drivers [zeta nova]})]
    (is (= {:event/type :projectors/scan-complete
            :devices [idn-found (assoc nova-device :driver :acme/nova) (assoc zeta-device :driver :zeta/beam)]
            :warnings []}
           event)
        "IDN first, then the drivers by id, each device tagged with its :driver")
    (is (= #{:idn :nova :zeta} (set (keys @seen))))
    (is (every? :together? (vals @seen)) "all three ran at once")
    (is (= ["255.255.255.255" 3000 1000] (:opts (:idn @seen))) "IDN's own scan, as before")
    (is (= {:timeout-ms 3000 :broadcast-address "255.255.255.255" :broadcast-addresses ["192.168.1.255" "10.0.0.255"]}
           (:opts (:nova @seen))
           (:opts (:zeta @seen)))
        "each discoverer gets the timeout, the scan's broadcast address and one per interface (G10)")
    (is (identical? loader (:loader (:nova @seen))) "with the add-on's classloader")
    (is (not (identical? loader (:loader (:zeta @seen)))))))

(deftest discoverers-get-broadcast-addresses-test
  (doseq [[networks expected] [[fake-interfaces ["192.168.1.255" "10.0.0.255"]]
                               [[{:up? true :loopback? false :broadcasts ["10.0.255.255"]}] ["10.0.255.255"]]
                               [[] []]]]
    (let [reads (atom 0)
          seen (atom [])
          discover (fn [opts] (swap! seen conj opts) [])
          opts {:timeout-ms 3000 :broadcast-address "192.0.2.255" :broadcast-addresses expected}]
      (scan! {:interfaces #(do (swap! reads inc) networks) :broadcast-address "192.0.2.255"
              :drivers [(discoverer :acme/nova "Nova" discover) (discoverer :zeta/beam "Zeta" discover)]})
      (is (= 1 @reads) "one interface snapshot for the entire scan")
      (is (= [opts opts] @seen) "the scan's selected broadcast is independent of the interface list"))))

(deftest invalid-driver-device-is-dropped-test
  (let [nova (discoverer :acme/nova "Acme Nova"
                         (fn [_] [nova-device
                                  {:name "no key" :outputs (:outputs nova-device)}
                                  {:device-key "NV-2" :name "Nova 2"}
                                  {:device-key "NV-3" :outputs [{:output-id 0 :driver-config {:f inc}}]}
                                  {:device-key "NV-4" :outputs [{:name "A" :driver-config {}}]}
                                  {:device-key "NV-5" :outputs [{:output-id 0 :driver-config {}} {:output-id 0 :driver-config {}}]}
                                  {:device-key "NV-6" :name 6 :outputs [{:output-id 0 :driver-config {}}]}
                                  "junk"
                                  (assoc nova-device :name "Nova again")
                                  {:device-key 7 :outputs [{:output-id "a" :driver-config {:n 1.5}}]}]))
        {:keys [devices warnings]} (scan! {:drivers [nova]})]
    (is (= [(assoc nova-device :driver :acme/nova)
            {:driver :acme/nova :device-key 7 :outputs [{:output-id "a" :driver-config {:n 1.5}}]}]
           devices)
        "only the devices with a usable shape: a key, outputs with ids and plain-data configs")
    (is (= ["Acme Nova: left out a device without a :device-key"
            "Acme Nova: left out device NV-2: it has no :outputs"
            "Acme Nova: left out device NV-3: an output's :driver-config isn't a map of plain data"
            "Acme Nova: left out device NV-4: an output has no :output-id"
            "Acme Nova: left out device NV-5: two outputs have :output-id 0"
            "Acme Nova: left out device NV-6: its :name isn't text"
            "Acme Nova: left out a device that isn't a map"
            "Acme Nova: left out a second device NV-00123"]
           warnings))))

(deftest failing-discoverer-gives-warning-test
  (let [zeta (discoverer :zeta/beam "Zeta" (fn [_] [zeta-device]))]
    (testing "one throws: its message, the others' devices"
      (is (= {:event/type :projectors/scan-complete
              :devices [idn-found (assoc zeta-device :driver :zeta/beam)]
              :warnings ["Acme Nova: libnova not found"]}
             (scan! {:idn (fn [& _] [idn-raw])
                     :drivers [zeta (discoverer :acme/nova "Acme Nova" (fn [_] (throw (ex-info "libnova not found" {}))))]}))))
    (testing "an Error (a native library missing), one with no message, a result that isn't a list"
      (is (= ["Acme Nova: no nova in java.library.path"]
             (:warnings (scan! {:drivers [(discoverer :acme/nova "Acme Nova"
                                                      (fn [_] (throw (UnsatisfiedLinkError. "no nova in java.library.path"))))]}))))
      (is (= ["Acme Nova: java.lang.IllegalStateException"]
             (:warnings (scan! {:drivers [(discoverer :acme/nova "Acme Nova" (fn [_] (throw (IllegalStateException.))))]}))))
      (is (= ["Acme Nova: :discover returned something other than a list of devices"]
             (:warnings (scan! {:drivers [(discoverer :acme/nova "Acme Nova" (fn [_] nova-device))]}))))
      (is (= {:event/type :projectors/scan-complete :devices [] :warnings []}
             (scan! {:drivers [(discoverer :acme/nova "Acme Nova" (fn [_] nil))]}))
          "nil is no devices"))
    (testing "IDN fails, a driver answers: its devices, and IDN's error as a warning"
      (is (= {:event/type :projectors/scan-complete
              :devices [(assoc zeta-device :driver :zeta/beam)]
              :warnings ["IDN: Network is unreachable"]}
             (scan! {:idn (fn [& _] (throw (java.net.SocketException. "Network is unreachable")))
                     :drivers [zeta]}))))))

(deftest slow-discoverer-times-out-test
  (let [stuck (promise)]
    (try
      (is (= {:event/type :projectors/scan-complete :devices [idn-found]
              :warnings ["Acme Nova: no answer within 3 s" "Zeta: no answer within 3 s"]}
             (scan! {:idn (fn [& _] [idn-raw])
                     :drivers [(discoverer :acme/nova "Acme Nova" (fn [_] @stuck []))
                               (discoverer :zeta/beam "Zeta" (fn [{:keys [timeout-ms]}]
                                                              (Thread/sleep (+ timeout-ms 300)) [zeta-device]))]}))
          "a stalled driver and a driver that answers late both miss the same deadline")
      (testing "IDN can time out too; an empty successful discovery still counts"
        (is (= {:event/type :projectors/scan-complete :devices [] :warnings ["IDN: no answer within 0.2 s"]}
               (scan! {:timeout-ms 200 :idn (fn [& _] (deref stuck 1000 nil) [])
                       :drivers [(discoverer :acme/nova "Acme Nova" (constantly []))]}))))
      (finally (deliver stuck true)))))

(deftest all-discoverers-fail-gives-scan-failed-test
  (let [unreachable (fn [& _] (throw (java.net.SocketException. "Network is unreachable")))
        called (promise)]
    (testing "IDN and every driver fail: IDN's error, as before"
      (is (= {:event/type :projectors/scan-failed :error "Network is unreachable"}
             (scan! {:idn unreachable
                     :drivers [(discoverer :acme/nova "Acme Nova"
                                           (fn [_] (deliver called true) (throw (ex-info "libnova not found" {}))))]})))
      (is (realized? called) "a failure in IDN cannot bypass the other discoverers"))
    (testing "one fails, one times out"
      (let [stuck (promise)]
        (try
          (is (= {:event/type :projectors/scan-failed :error "Network is unreachable"}
                 (scan! {:idn unreachable :timeout-ms 100
                         :drivers [(discoverer :acme/nova "Acme Nova" (fn [_] @stuck []))]})))
          (finally (deliver stuck true)))))
    (testing "IDN alone, as before"
      (is (= {:event/type :projectors/scan-failed :error "Network is unreachable"} (scan! {:idn unreachable}))))))

(deftest idn-only-scan-unchanged-test
  (let [calls (atom [])
        idn (fn [& args] (swap! calls conj (vec args)) [idn-raw (assoc idn-raw :address "10.0.0.6" :host-name "Stage DAC" :port 7300)])]
    (is (not-any? :discover (driver/registered)) "the built-in drivers have no :discover")
    (is (= {:event/type :projectors/scan-complete
            :devices [idn-found (assoc idn-found :address "10.0.0.6" :host-name "Stage DAC" :port 7300)]
            :warnings []}
           (scan! {:idn idn})))
    (is (= [["255.255.255.255" 3000 1000]] @calls) "IDN's discovery is asked as before, once")
    (is (= {:event/type :projectors/scan-complete :devices [] :warnings []} (scan! {})) "nothing found")))
