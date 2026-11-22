(ns laser-show.ui-test.discovery-drivers-test
  "Scan finds every driver's devices (plans/2026-11-19-output-drivers 02 §
   D4): a driver's :discover is run by Scan, each output it finds becomes a
   card on the rig strip, a later scan that doesn't find it says so, the
   card menu turns on every output of its device, a discoverer that fails
   shows a warning, and an output found this way streams through the real
   output path.

   Nothing reaches the network: IDN's discovery
   (idn-hello/discover-devices-with-services) and the interface list are
   replaced for every test, and every driver with :discover is a fake that
   returns data (with-fake-discovery checks that no other one is
   registered). The streaming test sends only to the loopback LSPS receiver
   (laser-show.output.lsps-receiver)."
  {:clj-kondo/config '{:lint-as {laser-show.output.lsps-receiver/with-receiver clojure.core/let}}}
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.events.core :as events]
            [laser-show.idn.hello :as idn-hello]
            [laser-show.output.discovery :as discovery]
            [laser-show.output.driver :as driver]
            [laser-show.output.lsps :as lsps]
            [laser-show.output.lsps-receiver :as rx]
            [laser-show.output.recording-driver :as rd]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene Node Parent]
           [javafx.scene.control Labeled]
           [javafx.scene.input KeyCode]))

(defn- app-state [] (state/get-raw-state))

(def ^:private idn-calls
  "The args IDN's (replaced) discovery was called with."
  (atom []))

(def ^:private nova-devices
  "What the fake :acme/nova driver's :discover returns next."
  (atom []))

(def ^:private fakes
  "The ids of the drivers with :discover that a test registered."
  (atom #{}))

(defn- loaded!
  "Put the registered drivers' descriptors into app state, as startup does
   (:addons/loaded), and wait for them."
  []
  (events/dispatch! {:event/type :addons/loaded :addons [] :drivers (driver/descriptors)})
  (ui/wait-for! #(= (set (keys (driver/descriptors)))
                    (set (keys (get-in (app-state) [:backend :output :drivers]))))))

(defn- register-fake!
  "Register driver (a fake whose :discover only returns data) and put its
   descriptor into app state."
  [d]
  (driver/register! d)
  (swap! fakes conj (:id d))
  (loaded!))

(def ^:private nova
  (assoc (:driver (rd/recording-driver {:id :acme/nova}))
         :name "Acme Nova (test)" :short-name "Nova"
         :fields [{:key :serial :label "Serial" :type :string :required? true :summary? true}]
         :capabilities {:scan-rate {:min 1000 :max 30000 :default 30000}}
         :discover (fn [_] @nova-devices)))

(defn- with-fake-discovery
  "Each fixture: IDN's discovery and the interface list replaced, nova
   registered, and every driver with :discover checked to be a fake."
  [f]
  (reset! idn-calls [])
  (reset! nova-devices [])
  (reset! fakes #{})
  (with-redefs [idn-hello/discover-devices-with-services (fn [& args] (swap! idn-calls conj (vec args)) [])
                discovery/interfaces (constantly [{:name "en0" :up? true :loopback? false :broadcasts ["192.0.2.255"]}])]
    (try
      (register-fake! nova)
      (f)
      (finally
        (output-runtime/drain! 2000)
        (output-runtime/open!)
        (run! driver/unregister! @fakes)))))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app with-fake-discovery)

(defn- only-fakes-discover!
  "Scan only when nothing but the fakes can discover."
  []
  (let [real (remove #(contains? @fakes (:id %)) (filter :discover (driver/registered)))]
    (assert (empty? real) (str "a real :discover is registered: " (mapv :id real)))))

(defn- scan!
  "Press Scan on the Projectors tab and wait for the scan to end."
  [robot]
  (only-fakes-discover!)
  (ui/click! robot "#projectors-tab")
  (let [before (count @idn-calls)]
    (ui/wait-for! #(ui/enabled? "#projectors-scan-button"))
    (ui/click! robot "#projectors-scan-button")
    (ui/wait-for! #(< before (count @idn-calls)))
    (ui/wait-for! #(and (not (get-in (app-state) [:projector-ui :scanning?]))
                        (ui/enabled? "#projectors-scan-button")))))

(defn- device [key & outputs]
  {:device-key key :name (str "Nova " key)
   :outputs (mapv (fn [o] {:output-id o :name (str "Output " o) :driver-config {:serial key :output o}}) outputs)})

(defn- card [id] (str "#rig-card-" (name id)))

(defn- enabled? [id] (get-in (app-state) [:projectors id :enabled?]))

(defn- menu-item-text
  "The text of the open menu's item found by selector (the labels inside
   its container)."
  [selector]
  (let [^Node node (ui/query selector)]
    (ui/on-fx-thread
     #(some (fn [n] (when (instance? Labeled n) (not-empty (.getText ^Labeled n))))
            (tree-seq (fn [n] (instance? Parent n)) (fn [^Parent p] (seq (.getChildrenUnmodifiable p))) node)))))


(deftest driver-device-can-be-enabled-test
  (let [robot (ui/robot)
        id :projector-acme-nova-NV-00123-0]
    (reset! nova-devices [(device "NV-00123" 0)])
    (scan! robot)
    (is (= [["255.255.255.255" 3000 1000]] @idn-calls) "IDN's (replaced) discovery was asked, as before")
    (ui/wait-for! #(some? (ui/query (card id))))
    (ui/assert-text! (str "#rig-card-name-" (name id)) "Nova NV-00123")
    (ui/assert-text! (str "#rig-card-address-" (name id)) "Nova")
    (ui/assert-text! (str "#rig-card-address-detail-" (name id)) "NV-00123")
    (is (= {:driver :acme/nova :driver-config {:serial "NV-00123" :output 0} :stable-id [:device :acme/nova "NV-00123" 0]
            :enabled? false :scan-rate 30000}
           (select-keys (get-in (app-state) [:projectors id]) [:driver :driver-config :stable-id :enabled? :scan-rate]))
        "a disabled projector of its driver, 01 §5.1's shape")
    (is (nil? (ui/query (str "#projector-status-" (name id)))) "found: no status line")
    (ui/assert-text! "#rig-header-count" "Tick On on a card to send it output.")
    (ui/check! robot (str "#rig-card-enable-" (name id)))
    (ui/wait-for! #(true? (enabled? id)))
    (ui/assert-text! "#rig-header-count" "1 output · 1 on")
    (testing "its identity in the inspector"
      (ui/click! robot (str "#rig-card-name-" (name id)))
      (ui/assert-text! "#projector-identity-label" "Identity: Device NV-00123 · output 0"))))

(deftest not-found-after-rescan-test
  (let [robot (ui/robot)
        id :projector-acme-nova-NV-1-0
        status (str "#projector-status-" (name id))]
    (events/write-state! assoc-in [:projectors :bridge]
                         {:name "Bridge" :driver :lsps :driver-config {:host "127.0.0.1" :port 7266 :max-packet-bytes 1400}
                          :stable-id [:manual :lsps :bridge] :zone-groups [] :exclude-from-all? false :tags #{}
                          :enabled? false :scan-rate 25000})
    (reset! nova-devices [(device "NV-1" 0)])
    (scan! robot)
    (ui/wait-for! #(some? (ui/query (card id))))
    (is (nil? (ui/query status)))
    (testing "unplugged: the next scan doesn't find it, and its card says so"
      (reset! nova-devices [])
      (scan! robot)
      (ui/assert-text! status "Not found in the last scan")
      (is (some? (get-in (app-state) [:projectors id])) "the projector stays")
      (is (nil? (ui/query "#projector-status-bridge")) "one added by hand is never looked for"))
    (testing "plugged in again: found, the same projector"
      (reset! nova-devices [(device "NV-1" 0)])
      (scan! robot)
      (ui/wait-for! #(nil? (ui/query status)))
      (is (= 2 (count (:projectors (app-state))))))))

(deftest turn-on-every-output-of-a-device-test
  (let [robot (ui/robot)]
    (reset! nova-devices [(device "NV-7" 0 1) (device "NV-8" 0)])
    (scan! robot)
    (ui/wait-for! #(some? (ui/query (card :projector-acme-nova-NV-7-0))))
    (ui/right-click-node! robot (ui/query (card :projector-acme-nova-NV-7-0)))
    (ui/wait-for! #(some? (ui/query "#rig-card-enable-all-projector-acme-nova-NV-7-0")))
    (is (= "Turn on every output of NV-7"
           (menu-item-text "#rig-card-enable-all-projector-acme-nova-NV-7-0")))
    (ui/click! robot "#rig-card-enable-all-projector-acme-nova-NV-7-0")
    (ui/wait-for! #(and (true? (enabled? :projector-acme-nova-NV-7-0)) (true? (enabled? :projector-acme-nova-NV-7-1))))
    (is (false? (enabled? :projector-acme-nova-NV-8-0)) "another device's output stays off")
    (testing "a device with one output has no such item"
      (ui/right-click-node! robot (ui/query (card :projector-acme-nova-NV-8-0)))
      (ui/wait-for! #(some? (ui/query "#rig-card-identify-projector-acme-nova-NV-8-0")))
      (is (nil? (ui/query "#rig-card-enable-all-projector-acme-nova-NV-8-0")))
      (ui/press! robot KeyCode/ESCAPE))))

(deftest scan-warning-notice-test
  (let [robot (ui/robot)]
    (register-fake! (assoc (:driver (rd/recording-driver {:id :zeta/beam}))
                           :name "Zeta" :short-name "Z"
                           :discover (fn [_] (throw (ex-info "libzeta not found" {})))))
    (reset! nova-devices [(device "NV-1" 0)])
    (scan! robot)
    (ui/assert-text! "#status-notice-text" "Zeta: libzeta not found")
    (is (= :warning (get-in (app-state) [:ui :notice :level])))
    (ui/wait-for! #(some? (ui/query (card :projector-acme-nova-NV-1-0))))
    (is (contains? (:projectors (app-state)) :projector-acme-nova-NV-1-0) "what the others found is still added")))


;; Through the real output path

(defn- found-lsps
  "A driver that streams as LSPS does (lsps/driver's functions) and whose
   :discover finds one device at port: the loopback receiver."
  [port]
  (assoc lsps/driver
         :id :test/found-lsps :name "Found LSPS (test)" :short-name "Found"
         :discover (fn [_] [{:device-key "bridge-1" :name "Bridge 1"
                             :outputs [{:output-id 0 :driver-config {:host "127.0.0.1" :port port}}]}])))

(defn- circle-cue
  "A circle of n points, to All."
  [n]
  {:destination-zone {:zone-group-id :all}
   :items [{:id (random-uuid) :type :preset :preset-id :circle :enabled? true :effects []
            :params {:num-points (dec n)} :zone-group-id :all}]})

(deftest discovered-driver-streams-test
  (rx/with-receiver [r (rx/start!)]
    (let [robot (ui/robot)
          id :projector-test-found-lsps-bridge-1-0
          lit-frames #(filter (fn [f] (pos? (:total f))) (rx/frames (rx/packets r)))]
      (register-fake! (found-lsps (:port r)))
      (scan! robot)
      (ui/wait-for! #(some? (ui/query (card id))))
      (is (= {:host "127.0.0.1" :port (:port r)}
             (get-in (app-state) [:projectors id :driver-config]))
          "the receiver's exact config from :discover; the driver fills its defaults on open")
      (ui/check! robot (str "#rig-card-enable-" (name id)))
      (ui/wait-for! #(true? (enabled? id)))
      (events/write-state! assoc-in [:chains :cue-chains [7 3]] (circle-cue 300))
      (events/dispatch! {:event/type :grid/trigger-cell :col 7 :row 3})
      (ui/wait-for! #(some? (get-in (app-state) [:playback :active-cues [7 3]])))
      (fixtures/with-clock
       (fn [_]
         (ui/click! robot "#stream-btn")
         (ui/wait-for! #(<= 5 (count (lit-frames))))
         (ui/wait-for! #(str/starts-with? (str (ui/node-text (str "#projector-status-" (name id)))) "Streaming"))
         (is (every? #(< 100 (:total %)) (lit-frames)) "the circle's points arrive")
         (is (= [] (rx/violations (rx/packets r) 1400)) "no L-1…L-3 violations")
         (ui/click! robot "#stream-btn")
         (ui/wait-for! #(some :close? (rx/packets r)))
         (ui/assert-text! "#stream-btn" "Stream")
         (let [n (count (rx/packets r))]
           (Thread/sleep 300)
           (is (= n (count (rx/packets r))) "nothing after Stop")))))))
