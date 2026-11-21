(ns laser-show.ui-test.streaming-status-test
  "The toolbar's streaming status and the projector dot show what the output
   runtime reports is actually streaming, against the fake DAC (and with no
   projector enabled, that, with a link to the Projectors tab); the Host,
   Port and Scan rate fields apply on Enter or focus loss, not per keystroke."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.app :as app]
            [laser-show.backend.multi-engine :as me]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.css.core :as css]
            [laser-show.dev-config :as dev-config]
            [laser-show.events.core :as events]
            [laser-show.idn.driver :as idn-driver]
            [laser-show.idn.fake-dac :as dac]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [java.net UnknownHostException]
           [javafx.scene Node Parent]
           [javafx.scene.control Labeled]
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
  (let [resolve! @#'idn-driver/target-address!]
    (with-redefs-fn {#'idn-driver/target-address! (fn [engine]
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

(deftest status-bar-labels-are-never-cut-off-test
  ;; At 1200 px in dev mode, with two projectors streaming at different rates
  ;; and three failing, the output summary ("60.4–60.7 fps · 3 errors") took
  ;; the bar past its width, and every label was cut off ("Ba…", "Effec…")
  (with-redefs [dev-config/dev-mode? (constantly true)]
    (app/start-test!)
    (ui/sync-fx!)
    (ui/set-size! (ui/main-window) 1200 800)
    (state/swap-state! assoc-in [:chains :cue-chains [0 0]]
                       {:items [{:id (random-uuid) :type :preset :preset-id :circle :enabled? true :effects []}]})
    (events/dispatch! {:event/type :grid/trigger-cell :col 0 :row 0})
    (ui/wait-for! #(some? (ui/query "#status-playing")))
    (let [p (fn [n] {:name n :host "10.0.0.9" :enabled? true :zone-groups [:all]})]
      (state/swap-state!
       #(-> %
            (assoc :projectors {:a (p "Busy one") :b (p "Nova stage left") :c (p "Reopen one") :d (p "Status one") :e (p "Stuck one")})
            (assoc-in [:backend :streaming :running?] true)
            (assoc-in [:backend :streaming :frame-stats]
                      {:avg-base-us 346 :avg-effects-us 0 :avg-latency-us 346 :p95-latency-us 1043 :max-latency-us 2221
                       :avg-idn-us 597 :p95-idn-us 1483 :max-idn-us 10230})
            (assoc-in [:backend :streaming :status]
                      {:engines 5 :wanted 5 :pending? false
                       :projectors {:a {:state :error :error "In use by ShowCtl on 10.0.0.3" :busy? true}
                                    :b {:state :error :error "Output driver :acme/nova is not installed"}
                                    :c {:state :streaming :fps 60.7}
                                    :d {:state :streaming :fps 60.4}
                                    :e {:state :error :kind :stuck :error "The output driver is not responding"}}}))))
    (ui/wait-for! #(= "60.4–60.7 fps · 3 errors" (ui/node-text "#status-output-health")))
    (let [labels (ui/on-fx-thread
                  #(vec (filter (fn [n] (and (instance? Labeled n) (.isVisible ^Node n) (seq (.getText ^Labeled n))))
                                (tree-seq (fn [n] (instance? Parent n))
                                          (fn [^Parent n] (seq (.getChildrenUnmodifiable n)))
                                          (ui/query "#status-bar")))))
          summary (ui/query "#status-output-health")]
      (is (<= 12 (count labels)) "Playing, the frame times and Output, each a label and a value")
      (doseq [^Labeled l labels :when (not= summary l)]
        (is (= (.getText l) (ui/shown-text l)) (str "\"" (.getText l) "\" isn't cut off")))
      (testing "the output summary gives way first, all of it in its tooltip"
        (is (str/includes? (str (ui/on-fx-thread #(some-> (.getTooltip ^Labeled summary) .getText)))
                           "60.4–60.7 fps · 3 errors"))
        (is (str/includes? (str (ui/on-fx-thread #(some-> (.getTooltip ^Labeled summary) .getText)))
                           "Stuck one: The output driver is not responding")
            "and the errors")))))

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
