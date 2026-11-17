(ns laser-show.ui-test.streaming-status-test
  "The toolbar's streaming status and the projector dot show what the output
   runtime reports is actually streaming, against the fake DAC; the Host and
   Port fields apply on Enter or focus loss, not per keystroke."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.backend.streaming-engine :as se]
            [laser-show.css.core :as css]
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
