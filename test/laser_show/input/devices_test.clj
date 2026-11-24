(ns laser-show.input.devices-test
  "The device worker (laser-show.input.devices) that owns the MIDI inputs
   and the OSC server (reactivity plan 25). No device or socket is real:
   the MIDI inputs are fakes shaped like javax.sound.midi's
   (input.fixtures/midi-source), and the OSC server is stood in for at
   osc/open-server!."
  (:require [cljfx.api :as fx]
            [cljfx.event-handler :as event-handler]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.logging.test :as log-test]
            [clojure.walk :as walk]
            [laser-show.events.core :as events]
            [laser-show.input.devices :as devices]
            [laser-show.input.fixtures :as fixtures]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc]
            [laser-show.input.router :as router]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains])
  (:import [java.net BindException]
           [javax.sound.midi MidiDevice ShortMessage Transmitter]))

(use-fixtures :each
  (fn [t]
    (state/init-state! (domains/build-initial-state))
    (router/clear-handlers!)
    (devices/open!)
    (try (t)
         (finally
           ;; Whatever a test left open is closed
           (devices/release! 2000)
           (devices/open!)
           (router/clear-handlers!)))))

(defn- app-dispatcher
  "A dispatcher as app/create-app has cljfx make it, without a renderer: a
   new app instance's, its events on the event thread."
  []
  (-> (events/instance-handler (events/new-instance!))
      (fx/wrap-co-effects events/co-effects)
      (fx/wrap-effects events/effects)
      (event-handler/wrap-async {:fx/executor events/event-executor})))

(defn- with-app
  "(f dispatch), dispatch a live app instance's dispatcher; retired after."
  [f]
  (try
    (f (app-dispatcher))
    (finally (events/retire!))))

(defn- handled?
  "Has dispatch's queue handled what it was given before, within ms?"
  [dispatch ms]
  (let [p (promise)]
    (dispatch {:event/type :dev/flush :promise p})
    (deref p ms false)))

(defn- eventually?
  "Does (pred) become true within 5 s?"
  [pred]
  (loop [n 0]
    (cond
      (pred) true
      (< n 250) (do (Thread/sleep 20) (recur (inc n)))
      :else false)))

(defn- input-state [device k] (get-in (state/get-raw-state) [:backend :input device k]))

(defn- answered?
  "Has every request to the device worker had its result applied?"
  []
  (eventually? #(and (empty? (input-state :midi :requests)) (empty? (input-state :osc :requests)))))

(defn- note-on [n] (fixtures/short-message ShortMessage/NOTE_ON 0 n 100))

(defn- counting-notes!
  "An atom with the notes of the MIDI note-ons the input router delivers."
  []
  (let [notes (atom [])]
    (router/register-global-handler! ::notes #(when (and (= :midi (:source %)) (= :note-on (:type %)))
                                               (swap! notes conj (:note %))))
    notes))

(defn- transmitters [source] (count (.getTransmitters ^MidiDevice (:device source))))

(defn- calls-of [calls k] (count (filter #{k} @calls)))


;; The event thread never waits for a device

(deftest device-io-does-not-block-the-event-thread-test
  (doseq [[what request done? bpm] [["Refresh" {:event/type :input/midi-refresh-devices}
                                     #(= ["Pad"] (input-state :midi :available-devices)) 97.0]
                                    ["Connect" {:event/type :input/midi-connect-device :device-name "Pad"}
                                     #(= #{"Pad"} (input-state :midi :connected-devices)) 98.0]
                                    ["Start Server" {:event/type :input/osc-start-server}
                                     #(true? (input-state :osc :server-running?)) 99.0]]]
    (testing what
      (with-app
        (fn [dispatch]
          (let [entered (promise)
                gate (promise)
                held (fn [v] (deliver entered true) (deref gate 5000 nil) v)
                pad (fixtures/midi-source "Pad")]
            (with-redefs [midi/list-devices #(held [pad])
                          osc/open-server! (fn [port _] (held {:port port}))
                          osc/close-server! identity]
              (try
                (dispatch request)
                (is (deref entered 2000 false) "the request is in the device layer, held there")
                (dispatch {:event/type :timing/set-bpm :bpm bpm})
                (is (handled? dispatch 1000) "the next event is handled while it waits")
                (is (= bpm (get-in (state/get-raw-state) [:timing :bpm])))
                (is (not (done?)) "the request's result isn't in yet")
                (finally (deliver gate true)))
              (is (answered?) "then its result comes in")
              (is (done?))
              (devices/release! 2000)
              (devices/open!))))))))


;; One device, one receiver

(deftest second-auto-connect-adds-no-receiver-test
  (with-app
    (fn [dispatch]
      (let [calls (atom [])
            pad (fixtures/midi-source "Pad" calls)
            notes (counting-notes!)]
        (with-redefs [midi/list-devices (constantly [pad])]
          (dispatch {:event/type :input/midi-auto-connect})
          (is (eventually? #(= #{"Pad"} (input-state :midi :connected-devices))))
          (is (answered?))
          (dispatch {:event/type :input/midi-auto-connect})
          (is (handled? dispatch 2000))
          (is (answered?))
          (is (= 1 (transmitters pad)) "one transmitter, one receiver")
          (is (= 1 (calls-of calls :get-transmitter)))
          (fixtures/send-midi! pad (note-on 60))
          (is (= [60] @notes) "one note, one delivery")
          (testing "connecting it by its name, or part of it, is the same input"
            (dispatch {:event/type :input/midi-connect-device :device-name "Pad"})
            (dispatch {:event/type :input/midi-connect-device :device-name "pa"})
            (is (handled? dispatch 2000))
            (is (answered?))
            (fixtures/send-midi! pad (note-on 61))
            (is (= [60 61] @notes))
            (is (= 1 (calls-of calls :get-transmitter)))
            (is (= #{"Pad"} (input-state :midi :connected-devices)))
            (is (= {:midi #{"Pad"} :osc nil} (devices/open-ports)))))))))

(deftest disconnect-closes-the-port-test
  (with-app
    (fn [dispatch]
      (let [pad-calls (atom [])
            keys-calls (atom [])
            pad (fixtures/midi-source "Pad" pad-calls)
            keyboard (fixtures/midi-source "Keys" keys-calls)
            notes (counting-notes!)
            connected #(input-state :midi :connected-devices)]
        (with-redefs [midi/list-devices (constantly [pad keyboard])]
          (dispatch {:event/type :input/midi-connect-device :device-name "Pad"})
          (dispatch {:event/type :input/midi-connect-device :device-name "Keys"})
          (is (eventually? #(= #{"Pad" "Keys"} (connected))))
          (dispatch {:event/type :input/midi-disconnect-device :device-name "Pad"})
          (is (eventually? #(= #{"Keys"} (connected))))
          (is (answered?))
          (testing "its receiver detached, its transmitter and device closed, in that order"
            (is (= [:open :get-transmitter :set-receiver :set-receiver :close-transmitter :close-device]
                   (map #(if (vector? %) (first %) %) @pad-calls)))
            (is (nil? (second (nth @pad-calls 3))) "the second setReceiver detaches"))
          (fixtures/send-midi! pad (note-on 60))
          (fixtures/send-midi! keyboard (note-on 70))
          (is (= [70] @notes) "the other input still plays")
          (testing "disconnecting it again closes nothing"
            (dispatch {:event/type :input/midi-disconnect-device :device-name "Pad"})
            (is (handled? dispatch 2000))
            (is (answered?))
            (is (= 1 (calls-of pad-calls :close-device))))
          (testing "Disconnect All"
            (dispatch {:event/type :input/midi-disconnect-all})
            (is (eventually? #(empty? (connected))))
            (is (answered?))
            (is (= 1 (calls-of keys-calls :close-device)))
            (is (= {:midi #{} :osc nil} (devices/open-ports))))
          (testing "connected again: a receiver of its own, and each note once"
            (dispatch {:event/type :input/midi-connect-device :device-name "Pad"})
            (is (eventually? #(= #{"Pad"} (connected))))
            (is (= 1 (transmitters pad)))
            (fixtures/send-midi! pad (note-on 62))
            (is (= [70 62] @notes))))))))

(deftest a-port-that-outlives-its-disconnect-delivers-nothing-test
  ;; A transmitter that won't let go of our receiver, nor close: a message
  ;; it still sends after the disconnect reaches no one
  (let [receiver (atom nil)
        stuck (reify Transmitter
                (setReceiver [_ r] (if r (reset! receiver r) (throw (IllegalStateException. "stuck"))))
                (getReceiver [_] @receiver)
                (close [_] (throw (IllegalStateException. "stuck"))))
        source (assoc (fixtures/midi-source "Stuck")
                      :device (reify MidiDevice
                                (isOpen [_] true)
                                (getTransmitter [_] stuck)
                                (close [_] (throw (IllegalStateException. "stuck")))))
        notes (counting-notes!)]
    (with-app
      (fn [dispatch]
        (with-redefs [midi/list-devices (constantly [source])]
          (dispatch {:event/type :input/midi-connect-device :device-name "Stuck"})
          (is (eventually? #(= #{"Stuck"} (input-state :midi :connected-devices))))
          (.send ^javax.sound.midi.Receiver @receiver (note-on 60) -1)
          (is (= [60] @notes))
          (dispatch {:event/type :input/midi-disconnect-device :device-name "Stuck"})
          (is (eventually? #(empty? (input-state :midi :connected-devices))))
          (.send ^javax.sound.midi.Receiver @receiver (note-on 61) -1)
          (is (= [60] @notes) "its connection isn't the registered one any more"))))))


;; The OSC server

(defn- with-fake-server
  "(f opened closed): f with osc/open-server! and close-server! standing in
   for the socket, recording the ports opened and the servers closed.
   Opening throws for a port in refused. What f leaves open is released
   while they still stand in."
  ([f] (with-fake-server #{} f))
  ([refused f]
   (let [opened (atom [])
         closed (atom [])]
     (with-redefs [osc/open-server! (fn [port _handler]
                                      (when (contains? refused port)
                                        (throw (BindException. "Address already in use")))
                                      (swap! opened conj port)
                                      {::server port})
                   osc/close-server! (fn [server] (swap! closed conj (::server server)) server)]
       (try
         (f opened closed)
         (finally
           (devices/release! 2000)
           (devices/open!)))))))

(deftest osc-start-and-stop-are-idempotent-test
  (with-app
    (fn [dispatch]
      (with-fake-server
        (fn [opened closed]
          (let [handle! (fn [& events]
                          (run! dispatch events)
                          (is (handled? dispatch 2000))
                          (is (answered?)))]
            (handle! {:event/type :input/osc-start-server})
            (is (= {:server-running? true :enabled? true :port 9000}
                   (select-keys (get-in (state/get-raw-state) [:backend :input :osc]) [:server-running? :enabled? :port])))
            (testing "Start twice: one server"
              (handle! {:event/type :input/osc-start-server})
              (is (= [9000] @opened))
              (is (= [] @closed)))
            (testing "Stop twice: closed once"
              (handle! {:event/type :input/osc-stop-server} {:event/type :input/osc-stop-server})
              (is (= [9000] @closed))
              (is (false? (input-state :osc :server-running?)))
              (is (false? (input-state :osc :enabled?))))
            (testing "the port set when Start is handled, not the one the button was drawn with"
              (handle! {:event/type :input/osc-set-port :port 9100}
                       {:event/type :input/osc-start-server :port 9000})
              (is (= [9000 9100] @opened))
              (is (= 9100 (input-state :osc :port))))
            (testing "a server on another port is stopped first"
              (handle! {:event/type :input/osc-set-port :port 9200}
                       {:event/type :input/osc-start-server})
              (is (= [9000 9100] @closed))
              (is (= [9000 9100 9200] @opened))
              (is (= {:midi #{} :osc 9200} (devices/open-ports))))))))))

(deftest osc-start-failure-shows-a-notice-test
  (with-app
    (fn [dispatch]
      (with-fake-server #{9000}
        (fn [opened _closed]
          (dispatch {:event/type :input/osc-start-server})
          (is (eventually? #(some? (get-in (state/get-raw-state) [:ui :notice]))))
          (is (answered?))
          (is (= {:text "Could not listen for OSC on port 9000" :level :error}
                 (dissoc (get-in (state/get-raw-state) [:ui :notice]) :id)))
          (is (= {:server-running? false :enabled? false}
                 (select-keys (get-in (state/get-raw-state) [:backend :input :osc]) [:server-running? :enabled?])))
          (is (= [] @opened))
          (is (= {:midi #{} :osc nil} (devices/open-ports))))))))


;; What app state holds

(deftest app-state-holds-no-device-objects-test
  (with-app
    (fn [dispatch]
      (with-fake-server
        (fn [_ _]
          (with-redefs [midi/list-devices (constantly [(fixtures/midi-source "Pad")])]
            (run! dispatch [{:event/type :input/midi-refresh-devices}
                            {:event/type :input/midi-auto-connect}
                            {:event/type :input/osc-start-server}])
            (is (eventually? #(and (= #{"Pad"} (input-state :midi :connected-devices))
                                   (input-state :osc :server-running?))))
            (is (answered?))
            (let [plain? #(or (nil? %) (string? %) (keyword? %) (number? %) (boolean? %) (uuid? %) (coll? %))
                  leaves (atom [])]
              (walk/postwalk #(do (when-not (coll? %) (swap! leaves conj %)) %)
                             (-> (get-in (state/get-raw-state) [:backend :input])
                                 (select-keys [:midi :osc])))
              (is (seq @leaves))
              (is (every? plain? @leaves) (str "no device, server or fn in app state: " (remove plain? @leaves))))))))))


;; release! (app/stop!)

(deftest a-stopped-apps-device-result-is-dropped-test
  ;; The result goes back through the dispatcher of the effect that asked
  ;; (reactivity plan 19): the app that asked is gone, so nothing changes
  (log-test/with-log
    (let [entered (promise)
          gate (promise)
          pad (fixtures/midi-source "Pad")
          old (app-dispatcher)]
      (with-redefs [midi/list-devices (fn [] (deliver entered true) (deref gate 5000 nil) [pad])]
        (try
          (old {:event/type :input/midi-connect-device :device-name "Pad"})
          (is (deref entered 2000 false))
          (let [successor (app-dispatcher)
                before (get-in (state/get-raw-state) [:backend :input :midi])]
            (deliver gate true)
            (is (eventually? #(log-test/logged? 'laser-show.events.core :info #"dropped :input/midi-connected"))
                "its result came back to the stopped app")
            (is (= #{"Pad"} (:midi (devices/open-ports))) "the worker connected it (stop! releases it)")
            (is (handled? successor 2000))
            (is (= before (get-in (state/get-raw-state) [:backend :input :midi]))
                "and changed nothing"))
          (finally
            (deliver gate true)
            (events/retire!)))))))

(defn- submitted
  "A dispatch fn for submit! that keeps the results it gets in an atom."
  [results]
  #(swap! results conj %))

(deftest release-closes-each-port-once-test
  (with-fake-server
    (fn [_ closed]
      (let [calls (atom [])
            pad (fixtures/midi-source "Pad" calls)
            results (atom [])]
        (with-redefs [midi/list-devices (constantly [pad])]
          (is (devices/submit! {:op :midi/connect :request-id 1 :auto? true} (submitted results)))
          (is (devices/submit! {:op :osc/start :request-id 2 :port 9000} (submitted results)))
          (is (eventually? #(= 2 (count @results))))
          (is (= [{:event/type :input/midi-connected :request-id 1 :connected-devices #{"Pad"}}
                  {:event/type :input/osc-started :request-id 2 :server-running? true :port 9000}]
                 @results))
          (devices/release! 1000)
          (is (= 1 (calls-of calls :close-device)))
          (is (= [9000] @closed))
          (is (= {:midi #{} :osc nil} (devices/open-ports)))
          (testing "again: nothing more to close"
            (devices/release! 1000)
            (is (= 1 (calls-of calls :close-device)))
            (is (= [9000] @closed)))
          (testing "a request after it is dropped, until open!"
            (is (false? (devices/submit! {:op :midi/connect :request-id 3 :auto? true} (submitted results))))
            (Thread/sleep 50)
            (is (= 2 (count @results)))
            (is (= 1 (calls-of calls :get-transmitter)))
            (devices/open!)
            (is (devices/submit! {:op :midi/connect :request-id 4 :auto? true} (submitted results)))
            (is (eventually? #(= 3 (count @results))))
            (is (= #{"Pad"} (:connected-devices (last @results))))))))))

(deftest a-port-opened-during-release-is-closed-test
  (let [calls (atom [])
        pad (fixtures/midi-source "Pad" calls)
        entered (promise)
        gate (promise)
        results (atom [])]
    (with-redefs [midi/list-devices (fn [] (deliver entered true) (deref gate 5000 nil) [pad])]
      (devices/submit! {:op :midi/connect :request-id 1 :device-name "Pad"} (submitted results))
      (is (deref entered 2000 false))
      ;; stop! comes while the worker is opening it
      (let [released (future (devices/release! 3000))]
        (is (eventually? #(= :closed (:state @@#'devices/!runtime))))
        (deliver gate true)
        (is (nil? (deref released 5000 ::timeout)))
        (is (= 1 (calls-of calls :get-transmitter)) "it was opened")
        (is (= 1 (calls-of calls :close-device)) "and closed at once, once")
        (is (= {:midi #{} :osc nil} (devices/open-ports)))
        (is (= [#{}] (map :connected-devices @results)) "its result says it isn't connected")))))
