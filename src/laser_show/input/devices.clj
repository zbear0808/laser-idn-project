(ns laser-show.input.devices
  "Owns the MIDI inputs and the OSC server. App state never holds one, only
   their status as plain data ([:backend :input :midi :available-devices
   :connected-devices], [:backend :input :osc :server-running? :port]),
   which comes back as events.

   - One worker thread (input-devices) runs the requests submit! queues, one
     at a time, so listing the MIDI inputs or opening a port never holds up
     the event thread: list the MIDI inputs, connect one (by name, or the
     first for Auto-Connect), disconnect one or all, start or stop the OSC
     server. The input handlers (events/handlers/input.clj) make them with
     registered effects (:input/midi-list, …), each with a :request-id.
   - The worker sends each result as an event, plain data, through the
     dispatch fn of the effect that asked, so a stopped app's is dropped:
     :input/midi-listed, :input/midi-connected and :input/midi-disconnected
     (with the connected inputs' names), :input/osc-started (listening, and
     on which port) and :input/osc-stopped; with :error, a sentence for a
     notice, when it couldn't. Their handlers take only the latest request's.
   - The registry (a runtime atom) holds what is open: each connected
     input's midi-in (device, transmitter, receiver) by its name, and the
     server with its port. Only the worker opens; whoever takes an entry out
     of the registry closes it, so each port is closed once.
   - Connecting an input already connected (the same device: list-devices'
     javax.sound.midi :device, not only its name) or starting the server on
     the port it listens on opens nothing; disconnecting an input that isn't
     connected or stopping a server that doesn't run closes nothing. An
     input hands its messages to the input router (router/dispatch!) only
     while its connection is the registered one: one message, one delivery.
   - release! (app/stop!) closes everything, once, and drops requests until
     open! (app/start!, start-test!); a port the running request opens after
     it is closed at once. Before the first release! the worker starts with
     the first request, so tests without an app can use it."
  (:require [clojure.tools.logging :as log]
            [laser-show.common.util :as u]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc]
            [laser-show.input.router :as router]
            [laser-show.state.core :as state])
  (:import [java.util.concurrent ExecutorService Executors ThreadFactory TimeUnit]))


;; What is open

(def ^:private nothing-open {:midi {} :osc nil})

(defonce ^{:private true
           :doc "{:midi {input-name midi-in} :osc {:server :port}}: every port
   open. A midi-in (midi/open-input!'s) has the :token its handlers check."}
  !registry
  (atom nothing-open))

(defonce ^{:private true
           :doc "{:state :idle|:open|:closed :generation n :executor}: n counts
   the release!s, so a request made before one can't keep what it opens."}
  !runtime
  (atom {:state :idle :generation 0}))

(defonce ^:private lock (Object.))

(defn open-ports
  "What is open now, as plain data: {:midi #{input name …} :osc port, or
   nil when the server doesn't run}."
  []
  (let [{:keys [midi osc]} @!registry]
    {:midi (set (keys midi)) :osc (:port osc)}))

(defn- current?
  "Is the runtime open, with no release! since a request of generation was
   made?"
  [generation]
  (let [{:keys [state] g :generation} @!runtime]
    (and (not= :closed state) (= generation g))))

(defn- keep!
  "Put a port just opened into the registry ((f registry)), unless release!
   came since the request of generation was made. True if it went in; else
   the caller closes it."
  [generation f]
  (locking lock
    (when (current? generation)
      (swap! !registry f)
      true)))

(defn- take-inputs!
  "Take the inputs named names (every one, for nil) out of the registry:
   their midi-ins, the caller's to close."
  [names]
  (let [[old _] (swap-vals! !registry update :midi #(if names (apply dissoc % names) {}))]
    (vals (cond-> (:midi old) names (select-keys names)))))

(defn- take-server!
  "Take the OSC server out of the registry: {:server :port}, the caller's to
   close, or nil."
  []
  (let [[old _] (swap-vals! !registry assoc :osc nil)]
    (:osc old)))


;; MIDI

(defn- registered?
  "Is the connection with token the one registered for input-name?"
  [input-name token]
  (identical? token (get-in @!registry [:midi input-name :token])))

(defn- input-handlers
  "[on-short on-sysex] for input-name's connection with token
   (midi/create-midi-handler and create-sysex-handler, to the input
   router): they read the switch and the channel filter in app state as
   each message comes, and see their input connected only while this
   connection is the registered one, so a port that outlived its disconnect
   delivers nothing."
  [input-name token]
  (let [midi-state #(assoc (get-in (state/get-raw-state) [:backend :input :midi])
                           :connected-devices (if (registered? input-name token) #{input-name} #{}))]
    [(midi/create-midi-handler input-name #(router/dispatch! %) midi-state)
     (midi/create-sysex-handler input-name midi-state)]))

(defn- connect!
  "Connect the input request names (:device-name; with :auto?, the first one
   listed) unless its device is connected already. nil, or what went wrong
   as a sentence for a notice."
  [{:keys [device-name auto?]} generation]
  (let [sources (midi/list-devices)
        source (if auto? (first sources) (midi/find-device sources device-name))
        input-name (:name source)]
    (cond
      (nil? source)
      (if auto? "No MIDI input to connect to" (str "MIDI input " device-name " not found"))

      (u/seek #(identical? (:device source) (:device %)) (vals (:midi @!registry)))
      nil

      :else
      (let [token (Object.)
            [on-short on-sysex] (input-handlers input-name token)]
        ;; Another device listed under its name now (plugged in again)
        ;; replaces the one connected under it
        (run! midi/close-input! (take-inputs! [input-name]))
        (try
          (let [midi-in (midi/open-input! source on-short on-sysex)]
            (if (keep! generation #(assoc-in % [:midi input-name] (assoc midi-in :token token)))
              (log/info "Connected to MIDI input" input-name)
              (midi/close-input! midi-in))
            nil)
          (catch Exception e
            (log/warn "Could not connect to MIDI input" input-name ":" (ex-message e))
            (str "Could not connect to MIDI input " input-name)))))))

(defn- disconnect!
  "Disconnect the input request names (:device-name), or with :all? every
   one: their ports closed."
  [{:keys [device-name all?]}]
  (doseq [midi-in (take-inputs! (when-not all? [device-name]))]
    (midi/close-input! midi-in)
    (log/info "Disconnected from MIDI input" (:name midi-in))))


;; OSC

(defn- osc-state
  "[:backend :input :osc] in app state now: the switch and the address
   mappings the server's handler reads as each message comes."
  []
  (get-in (state/get-raw-state) [:backend :input :osc]))

(defn- start-server!
  "Listen for OSC on request's :port, unless the server listens there
   already; a server on another port is stopped first. nil, or what went
   wrong as a sentence for a notice."
  [{:keys [port]} generation]
  (let [listening (:osc @!registry)]
    (when-not (and listening (= port (:port listening)))
      (some-> (take-server!) :server osc/close-server!)
      (try
        (let [server (osc/open-server! port (osc/create-osc-handler #(router/dispatch! %) osc-state))]
          (when-not (keep! generation #(assoc % :osc {:server server :port port}))
            (osc/close-server! server))
          nil)
        (catch Exception e
          (log/error "Could not start the OSC server on port" port ":" (ex-message e))
          (str "Could not listen for OSC on port " port))))))


;; Requests (worker thread)

(defn- result
  "The event answering request: type, its :request-id, status (what is
   open now), and :error when there is one."
  [type request status error]
  (cond-> (merge {:event/type type :request-id (:request-id request)} status)
    error (assoc :error error)))

(defn- perform
  "Do request; returns its result."
  [{:keys [op] :as request} generation]
  (case op
    :midi/list (result :input/midi-listed request {:available-devices (midi/list-device-names)} nil)
    :midi/connect (let [error (connect! request generation)]
                    (result :input/midi-connected request {:connected-devices (:midi (open-ports))} error))
    :midi/disconnect (do (disconnect! request)
                         (result :input/midi-disconnected request {:connected-devices (:midi (open-ports))} nil))
    :osc/start (let [error (start-server! request generation)
                     port (:osc (open-ports))]
                 (result :input/osc-started request
                         {:server-running? (some? port) :port (or port (:port request))}
                         error))
    :osc/stop (do (some-> (take-server!) :server osc/close-server!)
                  (result :input/osc-stopped request {:server-running? false} nil))))

(defn- run-request!
  "Run request unless release! came since it was queued, and send its
   result through dispatch."
  [request dispatch generation]
  (when (current? generation)
    (try
      (dispatch (perform request generation))
      (catch Throwable t
        (log/error t "Input device request failed:" (:op request))))))

(defn- daemon-factory [thread-name]
  (reify ThreadFactory
    (newThread [_ r]
      (doto (Thread. ^Runnable r ^String thread-name)
        (.setDaemon true)))))

(defn submit!
  "Queue request for the worker thread: {:op … :request-id …}, :op one of
   :midi/list, :midi/connect (:device-name, or :auto?), :midi/disconnect
   (:device-name, or :all?), :osc/start (:port) and :osc/stop. dispatch
   gets its result (see the ns doc). False (logged) after release!, until
   open!."
  [request dispatch]
  (locking lock
    (if (= :closed (:state @!runtime))
      (do (log/warn "Input devices are released (the app stopped); dropping" (:op request))
          false)
      (do
        (when (= :idle (:state @!runtime))
          (swap! !runtime assoc
                 :state :open
                 :executor (Executors/newSingleThreadExecutor (daemon-factory "input-devices"))))
        (let [{:keys [generation ^ExecutorService executor]} @!runtime]
          ;; With the asker's bindings, as a future would have them
          (.execute executor ^Runnable (bound-fn* #(run-request! request dispatch generation)))
          true)))))


;; Open / release

(defn open!
  "Take requests again after release! (app/start!, start-test!): the worker
   starts with the next one."
  []
  (locking lock
    (when (= :closed (:state @!runtime))
      (swap! !runtime assoc :state :idle))))

(defn release!
  "Close every MIDI input and the OSC server, once, on this thread, whatever
   app state says: the requests not yet run are dropped, the one running
   gets up to timeout-ms to finish (a port it opens now is closed at once,
   not kept), and later requests are dropped until open!. Idempotent; nil."
  [timeout-ms]
  (let [{:keys [^ExecutorService executor]}
        (locking lock
          (let [old @!runtime]
            (reset! !runtime {:state :closed :generation (inc (:generation old))})
            old))]
    (when executor
      (.shutdown executor)
      (when-not (.awaitTermination executor timeout-ms TimeUnit/MILLISECONDS)
        (log/warn "Input devices: the running request did not finish within" timeout-ms "ms")
        (.shutdownNow executor)))
    (let [[{:keys [midi osc]} _] (reset-vals! !registry nothing-open)]
      (run! midi/close-input! (vals midi))
      (some-> osc :server osc/close-server!)
      (when (or (seq midi) osc)
        (log/info "Input devices released:" (count midi) "MIDI input(s)"
                  (if osc (str "and the OSC server on port " (:port osc)) "and no OSC server"))))
    nil))
