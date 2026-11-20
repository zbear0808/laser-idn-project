(ns laser-show.ui-test.streaming-status-test
  "The toolbar's streaming status and the projector dot show what the output
   runtime reports is actually streaming, against the fake DAC (and with no
   projector enabled, that, with a link to the Projectors tab); the Host,
   Port and Scan rate fields apply on Enter or focus loss, not per keystroke."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.backend.multi-engine :as me]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.backend.streaming-engine :as se]
            [laser-show.css.core :as css]
            [laser-show.events.core :as events]
            [laser-show.idn.fake-dac :as dac]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [java.net UnknownHostException]
           [javafx.scene.paint Color]
           [javafx.scene.shape Circle]))

(def ^:dynamic *dac* nil)

(defn- with-fake-dac [f]
  (let [fake (dac/start!)]
    (try
      (binding [*dac* fake] (f))
      (finally
        (output-runtime/drain! 2000)
        (dac/stop! fake)))))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app with-fake-dac)

(defn- projector [service-id host]
  {:name (str "Fake " service-id) :host host :port (:port *dac*) :service-id service-id
   :enabled? true :zone-groups [:all] :output-config {:color-bit-depth 8 :xy-bit-depth 16}})

(defn- with-unresolvable
  "Run f with host failing to resolve, as a DNS lookup would."
  [host f]
  (let [resolve! @#'se/target-address!]
    (with-redefs-fn {#'se/target-address! (fn [engine]
                                            (if (= host (:target-host engine))
                                              (throw (UnknownHostException. (str host ": unknown host")))
                                              (resolve! engine)))}
      f)))

(defn- label-text []
  (ui/node-text "#streaming-status-label"))

(defn- fill-of [selector]
  (.getFill ^Circle (ui/query selector)))

(deftest toolbar-shows-streaming-n-of-m-test
  (let [robot (ui/robot)]
    (state/swap-state! assoc :projectors {:a (projector 1 "127.0.0.1")
                                          :b (assoc (projector 2 "127.0.0.1") :enabled? false)})
    (ui/assert-text! "#streaming-status-label" "Not streaming")
    (ui/assert-text! "#stream-btn" "Stream")
    (ui/click! robot "#stream-btn")
    (ui/assert-text! "#stream-btn" "Stop")
    (ui/assert-text! "#streaming-status-label" "Streaming to 1 of 1")
    (ui/assert-visible! ".status-indicator-connected")
    (ui/click! robot "#stream-btn")
    (ui/assert-text! "#streaming-status-label" "Not streaming")
    (ui/assert-text! "#stream-btn" "Stream")
    (is (dac/quiet? *dac* 300) "nothing streams after Stop")))

(deftest toolbar-counts-errors-test
  (with-unresolvable "unresolvable.test"
    (fn []
      (let [robot (ui/robot)]
        (state/swap-state! assoc :projectors {:a (projector 1 "127.0.0.1")
                                              :b (projector 2 "unresolvable.test")})
        (ui/click! robot "#stream-btn")
        (ui/assert-text! "#streaming-status-label" "Streaming to 1 of 2 · 1 error")
        (ui/click! robot "#stream-btn")
        (ui/assert-text! "#streaming-status-label" "Not streaming")))))

(deftest no-projectors-enabled-links-to-the-projectors-tab-test
  (let [robot (ui/robot)]
    (state/swap-state! assoc :projectors {:b (assoc (projector 2 "127.0.0.1") :enabled? false)})
    (ui/click! robot "#stream-btn")
    (ui/assert-text! "#streaming-status-label" "No projectors enabled")
    (ui/assert-text! "#stream-btn" "Stop")
    (ui/assert-visible! "#streaming-projectors-link")
    (ui/click! robot "#streaming-projectors-link")
    (ui/wait-for! #(= :projectors (get-in (state/get-raw-state) [:ui :active-tab])))
    (testing "enabling one streams it, and the link goes"
      (state/swap-state! assoc-in [:projectors :b :enabled?] true)
      (events/dispatch! {:event/type :idn/start-multi-streaming})
      (ui/assert-text! "#streaming-status-label" "Streaming to 1 of 1")
      (ui/wait-for! #(not (ui/visible? "#streaming-projectors-link"))))
    (ui/click! robot "#stream-btn")
    (ui/assert-text! "#streaming-status-label" "Not streaming")))

(deftest projector-dot-shows-status-test
  (with-unresolvable "unresolvable.test"
    (fn []
      (let [robot (ui/robot)]
        (state/swap-state! assoc :projectors {:a (projector 1 "127.0.0.1")
                                              :b (projector 2 "unresolvable.test")})
        (ui/click! robot "#projectors-tab")
        (ui/wait-for! #(some? (ui/query "#projector-dot-a")))
        (is (= (Color/web (css/text-muted)) (fill-of "#projector-dot-a")) "not streaming")
        (ui/click! robot "#stream-btn")
        (ui/wait-for! #(= (Color/web (css/green)) (fill-of "#projector-dot-a")))
        (ui/wait-for! #(= (Color/web (css/red)) (fill-of "#projector-dot-b")))
        (ui/wait-for! #(str/starts-with? (str (ui/node-text "#projector-status-a")) "Streaming"))
        (is (str/includes? (ui/node-text "#projector-status-b")
                           "UnknownHostException: unresolvable.test")
            "the error says why")
        (ui/click! robot "#stream-btn")
        (ui/wait-for! #(= (Color/web (css/text-muted)) (fill-of "#projector-dot-a")))
        (ui/wait-for! #(nil? (ui/query "#projector-status-b")))))))

(deftest host-field-commits-on-enter-test
  (let [robot (ui/robot)
        host #(get-in (state/get-raw-state) [:projectors :a :host])
        port #(get-in (state/get-raw-state) [:projectors :a :port])]
    (state/swap-state! #(-> %
                            (assoc :projectors {:a (projector 1 "127.0.0.1")})
                            (assoc-in [:projector-ui :active-projector] :a)))
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(some? (ui/query "#projector-host-field")))
    (ui/fill! robot "#projector-host-field" "192.168.1")
    (ui/sync-fx!)
    (is (= "127.0.0.1" (host)) "typing alone applies nothing")
    (ui/fill! robot "#projector-host-field" "192.168.1.50")
    (ui/submit! robot "#projector-host-field")
    (ui/wait-for! #(= "192.168.1.50" (host)))
    (ui/fill! robot "#projector-host-field" "   ")
    (ui/submit! robot "#projector-host-field")
    (ui/assert-text! "#projector-host-field" "192.168.1.50")
    (is (= "192.168.1.50" (host)) "a blank host is put back, not applied")
    (ui/fill! robot "#projector-port-field" "abc")
    (ui/submit! robot "#projector-port-field")
    (ui/assert-text! "#projector-port-field" (str (port)))
    (ui/fill! robot "#projector-port-field" "7300")
    (ui/focus! robot "#projector-host-field")
    (ui/wait-for! #(= 7300 (port)))
    (is (= "192.168.1.50" (host)))))

(deftest scan-rate-field-commits-on-enter-test
  (let [robot (ui/robot)
        rate #(get-in (state/get-raw-state) [:projectors :a :scan-rate])]
    (state/swap-state! #(-> %
                            (assoc :projectors {:a (assoc (projector 1 "127.0.0.1") :scan-rate 25000)})
                            (assoc-in [:projector-ui :active-projector] :a)))
    (ui/click! robot "#projectors-tab")
    (ui/assert-text! "#projector-scan-rate-field" "25000")
    (ui/fill! robot "#projector-scan-rate-field" "30k")
    (ui/sync-fx!)
    (is (= 25000 (rate)) "typing alone applies nothing")
    (ui/submit! robot "#projector-scan-rate-field")
    (ui/wait-for! #(= 30000 (rate)))
    (ui/assert-text! "#projector-scan-rate-field" "30000")
    (ui/fill! robot "#projector-scan-rate-field" "fast")
    (ui/submit! robot "#projector-scan-rate-field")
    (ui/assert-text! "#projector-scan-rate-field" "30000")
    (is (= 30000 (rate)) "text that isn't a rate is put back, not applied")
    (ui/fill! robot "#projector-scan-rate-field" "500")
    (ui/submit! robot "#projector-scan-rate-field")
    (ui/wait-for! #(= 1000 (rate)))
    (ui/assert-text! "#projector-scan-rate-field" "1000")
    (ui/fill! robot "#projector-scan-rate-field" "25,000")
    (ui/focus! robot "#projector-host-field")
    (ui/wait-for! #(= 25000 (rate)))))

(deftest projector-status-says-when-the-scan-rate-limits-the-fps-test
  (let [robot (ui/robot)
        lit (vec (for [i (range 1000)] [(- (/ (double i) 1000) 0.5) 0.0 1.0 1.0 1.0]))]
    (with-redefs-fn {#'me/projector-frame (constantly lit)}
      (fn []
        (state/swap-state! assoc :projectors {:a (assoc (projector 1 "127.0.0.1") :scan-rate 25000)})
        (ui/click! robot "#projectors-tab")
        (ui/wait-for! #(some? (ui/query "#projector-dot-a")))
        (ui/click! robot "#stream-btn")
        (ui/wait-for! #(str/ends-with? (str (ui/node-text "#projector-status-a")) "(limited by scan rate)"))
        (is (re-matches #"Streaming · 2[456]\.\d fps \(limited by scan rate\)"
                        (ui/node-text "#projector-status-a"))
            "1 001 samples at 25 000 pts/s: about 25 fps")
        (ui/click! robot "#stream-btn")
        (ui/wait-for! #(nil? (ui/query "#projector-status-a")))))))


;; The LOW FPS chip (zones plan Z3, 05 §7)

(defn- circle-cue
  "One circle of n points (a dark one and n - 1 lit), to target."
  [n target]
  {:destination-zone {:zone-group-id :all}
   :items [{:id (random-uuid) :type :preset :preset-id :circle :enabled? true :effects []
            :params {:num-points (dec n)} :target target}]})

(defn- play! [cue]
  (state/swap-state! assoc-in [:chains :cue-chains [7 3]] cue)
  (events/dispatch! {:event/type :grid/trigger-cell :col 7 :row 3})
  (ui/wait-for! #(some? (get-in (state/get-raw-state) [:playback :active-cues [7 3]]))))

(deftest low-fps-chip-names-the-laser-and-opens-its-card-test
  (let [robot (ui/robot)]
    (state/swap-state! assoc :projectors {:a (assoc (projector 1 "127.0.0.1") :name "Laser L" :scan-rate 25000)
                                          :b (assoc (projector 2 "127.0.0.1") :name "Laser R" :scan-rate 25000)})
    (play! (circle-cue 3000 :auto.laser/b))
    (is (not (ui/visible? "#low-fps-chip")))
    (fixtures/with-clock
      (fn [_]
        (ui/click! robot "#stream-btn")
        (ui/assert-text! "#low-fps-chip" "LOW FPS · Laser R 14 fps")
        (is (= "Laser R: 14 fps (minimum 20) · −40% pts\nClick to see it."
               (ui/on-fx-thread #(.getText (.getTooltip ^javafx.scene.control.Label (ui/query "#low-fps-chip"))))))
        (testing "a click opens its card"
          (ui/click! robot "#low-fps-chip")
          (ui/wait-for! #(= :projectors (get-in (state/get-raw-state) [:ui :active-tab])))
          (ui/assert-text! "#projector-config-title" "CONFIGURE: Laser R"))
        (testing "both low: the lowest, and +1"
          (state/assoc-in-state! [:chains :cue-chains [7 3] :items 0 :target] :all)
          (ui/wait-for! #(= "LOW FPS · Laser L 14 fps +1" (ui/node-text "#low-fps-chip"))))
        (ui/click! robot "#stream-btn")))
    (testing "gone when nothing streams"
      (ui/wait-for! #(not (ui/visible? "#low-fps-chip"))))))

(deftest light-laser-shows-no-chip-test
  (let [robot (ui/robot)]
    (state/swap-state! assoc :projectors {:a (assoc (projector 1 "127.0.0.1") :scan-rate 25000)})
    (play! (circle-cue 400 :all))
    (fixtures/with-clock
      (fn [_]
        (ui/click! robot "#stream-btn")
        (ui/wait-for! #(get-in (state/get-raw-state) [:backend :streaming :status :projectors :a :load]))
        (ui/sync-fx!)
        (is (not (ui/visible? "#low-fps-chip")) "400 points at 25,000 pts/s: 62 fps")
        (is (false? (get-in (state/get-raw-state) [:backend :streaming :status :projectors :a :low?])))
        (ui/click! robot "#stream-btn")))))
