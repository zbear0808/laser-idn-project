(ns laser-show.events.output-sync-test
  "The output-sync middleware in the event pipeline: a change to what an
   engine is built from requests a sync while streaming; nothing else does.
   Also: the output status written into app state is never an edit."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.events.core :as events]
            [laser-show.output.driver :as driver]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.persistent :as persistent]))

(def pipeline events/handle-event-pipeline)

(def projector-a
  {:name "A" :host "10.0.0.1" :port 7255 :service-id 0 :enabled? true
   :zone-groups [:all] :output-config {:color-bit-depth 8 :xy-bit-depth 16}})

(defn- streaming-state
  ([] (streaming-state true))
  ([running?]
   (-> (domains/build-initial-state)
       (assoc :projectors {:a projector-a
                           :b (assoc projector-a :name "B" :host "10.0.0.2" :enabled? false)})
       (assoc-in [:zone-groups :left] {:id :left :name "Left"})
       (assoc-in [:backend :streaming :running?] running?)
       history/mark-clean)))

(defn- run
  "Run event through the pipeline, then any :dispatch it returns. Returns the
   final effects map (with :state)."
  [state event]
  (let [effects (pipeline (assoc event :state state))]
    (if-let [next-event (:dispatch effects)]
      (run (or (:state effects) state) next-event)
      effects)))

(defn- refresh [effects]
  (:multi-engine/refresh effects))

(deftest fingerprint-change-requests-a-sync-test
  (doseq [[label event] [["host" {:event/type :projectors/update-settings :projector-id :a
                                  :updates {:host "10.0.0.9"}}]
                         ["port" {:event/type :projectors/update-settings :projector-id :a
                                  :updates {:port 7300}}]
                         ["output config" {:event/type :projectors/update-settings :projector-id :a
                                           :updates {:output-config {:color-bit-depth 16
                                                                     :xy-bit-depth 16}}}]
                         ["enable" {:event/type :projectors/toggle-enabled :projector-id :b}]
                         ["disable" {:event/type :projectors/toggle-enabled :projector-id :a}]
                         ["enable all by IP" {:event/type :projectors/enable-all-by-ip
                                              :address "10.0.0.2"}]
                         ["add manual" {:event/type :projectors/add-manual
                                        :name "C" :host "10.0.0.3" :port 7255}]]]
    (testing label
      (let [effects (run (streaming-state) event)]
        (is (= (:projectors (:state effects)) (:projectors (refresh effects)))
            "the sync carries the new projector snapshot")))
    (testing (str label ", not streaming")
      (is (nil? (refresh (run (streaming-state false) event)))))))

(deftest open-keeps-the-engines-test
  (let [other-show (persistent/project-files (-> (domains/build-initial-state)
                                                 (assoc :projectors {:z {:name "Z" :host "10.9.9.9" :enabled? true}})
                                                 (assoc-in [:timing :bpm] 90.0)))]
    (doseq [[label event] [["File > New" {:event/type :file/new-project :force? true}]
                           ["File > Open" {:event/type :file/project-loaded :file-path "/tmp/other.zip" :contents other-show}]]]
      (testing label
        (let [s (streaming-state)
              effects (run s event)]
          (is (identical? (:projectors s) (get-in effects [:state :projectors])) "the rig stays (D-30)")
          (is (nil? (refresh effects)) "so no engine restarts while streaming"))))))

(deftest service-enabled-uses-the-deterministic-id-test
  (let [s (assoc-in (streaming-state) [:projectors :projector-10-0-0-5-0]
                    (assoc projector-a :host "10.0.0.5" :enabled? false))
        effects (run s {:event/type :projectors/set-service-enabled
                        :host "10.0.0.5" :service-id 0 :enabled? true})]
    (is (true? (get-in effects [:state :projectors :projector-10-0-0-5-0 :enabled?])))
    (is (some? (refresh effects)))))

(deftest name-or-zone-change-requests-nothing-test
  (doseq [[label event] [["name" {:event/type :projectors/update-settings :projector-id :a
                                  :updates {:name "Renamed"}}]
                         ["zone group" {:event/type :projectors/toggle-zone-group
                                        :projector-id :a :zone-group-id :left}]
                         ["a disabled projector's host" {:event/type :projectors/update-settings
                                                         :projector-id :b
                                                         :updates {:host "10.0.0.8"}}]
                         ["a blank host" {:event/type :projectors/update-settings :projector-id :a
                                          :updates {:host "  "}}]
                         ["a port out of range" {:event/type :projectors/update-settings
                                                 :projector-id :a :updates {:port 70000}}]
                         ["enable all with no match" {:event/type :projectors/enable-all-by-ip
                                                      :address "10.9.9.9"}]
                         ["content" {:event/type :timing/set-bpm :bpm 128.0}]
                         ["a fit setting (zones plan Z3)" {:event/type :projectors/set-fit :projector-id :a
                                                           :key :min-fps :value 30}]
                         ["the safety limit" {:event/type :projectors/set-safety :projector-id :a
                                              :safety {:limit [-0.5 -0.5 0.5 0.5]}}]
                         ["exclude from All" {:event/type :projectors/set-exclude-from-all :projector-id :a
                                              :exclude? true}]]]
    (testing label
      (is (nil? (refresh (run (streaming-state) event)))))))

(deftest safety-change-requests-no-refresh-test
  (doseq [[label event] [["a smaller zone" {:event/type :projectors/set-safety :projector-id :a
                                           :safety {:limit [-0.5 -0.5 0.5 0.5]}}]
                         ["a confirmed larger zone" {:event/type :projectors/set-safety :projector-id :a
                                                     :safety {:limit [-1 -1 1 1]} :confirmed? true}]
                         ["a blocked area" {:event/type :projectors/add-blocked-area :projector-id :a}]]]
    (testing label
      (is (nil? (refresh (run (streaming-state) event)))
          "safety is read live by the frame provider; no engine restarts"))))

(deftest undo-of-a-host-edit-requests-a-sync-test
  (let [edited (:state (run (streaming-state) {:event/type :projectors/update-settings
                                               :projector-id :a
                                               :updates {:host "10.0.0.9"}}))
        undone (run edited {:event/type :edit/undo})]
    (is (= "10.0.0.1" (get-in undone [:state :projectors :a :host])))
    (is (= "10.0.0.1" (get-in (refresh undone) [:projectors :a :host])))
    (testing "redo too"
      (is (= "10.0.0.9" (get-in (refresh (run (:state undone) {:event/type :edit/redo}))
                                [:projectors :a :host])))))
  (testing "undoing a content edit requests nothing"
    (let [edited (:state (run (streaming-state) {:event/type :projectors/update-settings
                                                 :projector-id :a :updates {:name "Renamed"}}))]
      (is (nil? (refresh (run edited {:event/type :edit/undo})))))))

(deftest output-status-is-not-an-edit-test
  (let [s (streaming-state)
        status {:engines 1 :wanted 1 :pending? false
                :projectors {:a {:state :streaming :fps 59.8}}}
        effects (run s {:event/type :output/status :status status})]
    (is (= {:output/write-status status} effects)
        "only the status path is written; no undo entry, no dirty flag, no sync")
    (testing "through the wired handler"
      (state/init-state! s)
      (events/event-handler {:event/type :output/status :status status})
      (let [after (state/get-raw-state)]
        (is (= status (get-in after [:backend :streaming :status])))
        (is (= (:history s) (:history after)) "no undo entry")
        (is (= (:project s) (:project after)) "not dirty")
        (is (false? (get-in after [:project :dirty?])))))))

;; Projectors with another output driver (plans/2026-11-19-output-drivers
;; 01 §5.5)

(def ^:private bridge
  {:name "Bridge" :driver :lsps :driver-config {:host "127.0.0.1" :port 7266 :max-packet-bytes 1400}
   :stable-id [:manual :lsps :c] :enabled? true :zone-groups [] :scan-rate 25000})

(defn- with-bridge [running?]
  (-> (streaming-state running?)
      (assoc-in [:projectors :c] bridge)
      (assoc-in [:backend :output :drivers] (driver/descriptors))
      history/mark-clean))

(deftest driver-config-edit-resyncs-test
  (doseq [[label event] [["its port" {:event/type :projectors/update-driver-config :projector-id :c :key :port :value "7300"}]
                         ["its host" {:event/type :projectors/update-driver-config :projector-id :c :key :host :value "10.0.0.20"}]
                         ["its scan rate" {:event/type :projectors/update-settings :projector-id :c :updates {:scan-rate 30000}}]
                         ["added by hand" {:event/type :projectors/add-manual :driver :lsps :name "D"
                                           :driver-config {:host "127.0.0.1"}}]]]
    (testing label
      (let [effects (run (with-bridge true) event)]
        (is (some? (refresh effects)))
        (is (= (:projectors (:state effects)) (:projectors (refresh effects))) "the sync carries the new snapshot")))
    (testing (str label ", not streaming")
      (is (nil? (refresh (run (with-bridge false) event))))))
  (testing "an edit that isn't applied, or a name, requests nothing"
    (doseq [event [{:event/type :projectors/update-driver-config :projector-id :c :key :port :value "99999"}
                   {:event/type :projectors/update-driver-config :projector-id :c :key :port :value "7266"}
                   {:event/type :projectors/update-settings :projector-id :c :updates {:name "Renamed"}}]]
      (is (nil? (refresh (run (with-bridge true) event))) (pr-str event)))))

(deftest idn-fingerprint-unchanged-test
  (is (= {:host "10.0.0.1" :port 7255 :service-id 0 :output-config {:color-bit-depth 8 :xy-bit-depth 16}}
         (output-runtime/fingerprint (assoc projector-a :stable-id [:host "10.0.0.1" 0] :name "Renamed")))
      "an IDN projector's fingerprint has no :driver or :driver-config")
  (is (= {:a (output-runtime/fingerprint projector-a)}
         (output-runtime/wanted (:projectors (streaming-state))))
      "and :b, off, isn't wanted")
  (is (= {:driver :lsps :driver-config (:driver-config bridge) :scan-rate 25000}
         (get (output-runtime/wanted {:c bridge}) :c))
      "another driver's is wanted without a host"))
