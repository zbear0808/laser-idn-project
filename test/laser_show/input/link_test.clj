(ns laser-show.input.link-test
  "Tests for Ableton Link synchronization service. Carabiner is stood in
   for at beat-carabiner's functions, and on a loopback port of the test's
   own for the beat queries: nothing here reaches a daemon or starts one."
  (:require [beat-carabiner.core :as bc]
            [cljfx.api :as fx]
            [cljfx.event-handler :as event-handler]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as log-test]
            [laser-show.animation.show-time :as show-time]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.timing :as timing]
            [laser-show.input.fixtures :as fixtures]
            [laser-show.input.link :as link]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]))


;; State Management Tests


(deftest test-initial-state
  (testing "Initial Link state structure"
    (is (= {:carabiner-connected? false
            :link-enabled? false
            :sync-enabled? false
            :link-bpm nil
            :link-peers 0}
           link/initial-state))))

(deftest test-enable-disable-sync
  (testing "Enabling BPM sync"
    (let [state link/initial-state
          enabled (link/enable-sync state)]
      (is (true? (:sync-enabled? enabled)))))
  
  (testing "Disabling BPM sync"
    (let [state (link/enable-sync link/initial-state)
          disabled (link/disable-sync state)]
      (is (false? (:sync-enabled? disabled))))))

(deftest test-sync-enabled-query
  (testing "Sync enabled query"
    (is (false? (link/sync-enabled? link/initial-state)))
    (is (true? (link/sync-enabled? (link/enable-sync link/initial-state))))))

(deftest test-connected-query
  (testing "Connection status query (requires both carabiner-connected? and link-enabled?)"
    (is (false? (link/connected? link/initial-state)))
    ;; connected? requires BOTH carabiner-connected? AND link-enabled? to be true
    (is (false? (link/connected? (assoc link/initial-state :carabiner-connected? true))))
    (is (false? (link/connected? (assoc link/initial-state :link-enabled? true))))
    (is (true? (link/connected? (assoc link/initial-state 
                                       :carabiner-connected? true 
                                       :link-enabled? true))))))

(deftest test-carabiner-connected-query
  (testing "Carabiner connection status query"
    (is (false? (link/carabiner-connected? link/initial-state)))
    (is (true? (link/carabiner-connected? (assoc link/initial-state :carabiner-connected? true))))))

(deftest test-link-enabled-query
  (testing "Link enabled status query"
    (is (false? (link/link-enabled? link/initial-state)))
    (is (true? (link/link-enabled? (assoc link/initial-state :link-enabled? true))))))


;; BPM Change Threshold Tests


(deftest test-bpm-changed
  (testing "BPM change detection with threshold"
    (let [bpm-changed? @#'link/bpm-changed?]
      ;; Nil old BPM should trigger change
      (is (true? (bpm-changed? nil 120.0)))
      (doseq [invalid [nil 0 -1 "128" ##NaN ##Inf]]
        (is (false? (bpm-changed? 120.0 invalid))))
      
      ;; Small change below threshold should not trigger
      (is (false? (bpm-changed? 120.0 120.005)))
      
      ;; Change above threshold should trigger
      (is (true? (bpm-changed? 120.0 120.02)))
      (is (true? (bpm-changed? 120.0 119.98)))
      
      ;; Exactly at threshold should trigger
      (is (true? (bpm-changed? 120.0 120.01))))))


;; Status Listener Tests
;; Note: The status listener expects :link-bpm key in the status map (from beat-carabiner)


(def ^:private following
  {:timing {:bpm 128.0}
   :backend {:link (assoc link/initial-state :carabiner-connected? true :link-enabled? true
                          :sync-enabled? true :link-bpm 128.0 :link-peers 1)}})

(defn- apply-event [s e] (:state (timing/handle (assoc e :state s))))

(deftest stable-status-is-quiet-but-corrects-app-tempo-drift-test
  (let [s (atom following)
        sent (atom [])
        listener (link/create-status-listener #(do (swap! sent conj %) (swap! s apply-event %)) #(deref s))
        status {:link-bpm 128.0 :link-peers 1 :sync-mode :off}]
    (listener status)
    (listener (assoc status :link-bpm 128.005))
    (is (empty? @sent) "steady tempo and sub-threshold jitter generate no events")
    (listener (assoc status :link-peers 2))
    (is (= [{:event/type :timing/link-peers-changed :peers 2}] @sent))
    (reset! sent [])
    (swap! s assoc-in [:timing :bpm] 120.0)
    (dotimes [_ 3] (listener (assoc status :link-peers 2)))
    (is (= 128.0 (get-in @s [:timing :bpm])) "compare the report with the app, not just the display cache")
    (is (= 1 (count @sent)) "one correction, then silence")))

(deftest queued-status-respects-the-current-sync-controls-test
  (doseq [flag [:link-enabled? :sync-enabled?]]
    (let [queued (atom [])
          listener (link/create-status-listener #(swap! queued conj %) (constantly following))]
      (listener {:link-bpm 132.0 :link-peers 1})
      (let [off (assoc-in following [:backend :link flag] false)
            result (reduce apply-event off @queued)]
        (is (= 132.0 (get-in result [:backend :link :link-bpm])) "still display the peer tempo")
        (is (= 128.0 (get-in result [:timing :bpm])) (str flag " off before processing"))))))

(deftest consecutive-reports-can-return-to-the-last-applied-values-test
  (let [queued (atom [])
        listener (link/create-status-listener #(swap! queued conj %) (constantly following))]
    ;; The event thread has not applied either report yet.
    (dotimes [_ 3] (listener {:link-bpm 135.0 :link-peers 2}))
    (is (= 2 (count @queued)) "only one BPM and one peer event, even with the event thread behind")
    (listener {:link-bpm 128.0 :link-peers 1})
    (is (= following (reduce apply-event following @queued)))))


;; The connection's owner (reactivity follow-up R5): connect! connects on
;; the Link thread, keeps the status listener out of app state and reports
;; through the dispatcher that asked; release! closes it, once

(defn- with-carabiner
  "(f carabiner) on fresh app state, Carabiner stood in for
   (fixtures/with-carabiner)."
  ([f] (with-carabiner {} f))
  ([opts f]
   (state/init-state! (domains/build-initial-state))
   (fixtures/with-carabiner opts f)))

(def ^:private report! fixtures/report-status!)

(def ^:private calls fixtures/carabiner-calls)

(defn- eventually?
  "Does (pred) become true within 5 s?"
  [pred]
  (loop [n 0]
    (cond
      (pred) true
      (< n 250) (do (Thread/sleep 20) (recur (inc n)))
      :else false)))

(defn- app-dispatcher
  "A dispatcher as app/create-app has cljfx make it, without a renderer: a
   new app instance's, its events on the event thread."
  []
  (-> (events/instance-handler (events/new-instance!))
      (fx/wrap-co-effects events/co-effects)
      (fx/wrap-effects events/effects)
      (event-handler/wrap-async {:fx/executor events/event-executor})))

(defn- handled?
  "Has dispatch's queue handled what it was given before, within ms?"
  [dispatch ms]
  (let [p (promise)]
    (dispatch {:event/type :dev/flush :promise p})
    (deref p ms false)))

(def ^:private connected {:event/type :timing/carabiner-connected :connected? true})

(deftest connect-registers-one-listener-test
  (with-carabiner {:status {:link-bpm 140.0}}
    (fn [carabiner]
      (let [sent (atom [])]
        (is (true? (link/connect! {} #(swap! sent conj %))))
        (is (eventually? #(= 1 (count (filter #{connected} @sent)))))
        (is (= [{:event/type :timing/link-bpm-changed :bpm 140.0} connected] @sent)
            "a tempo that came before the listener was registered is picked up, then the connection reported")
        (is (= 1 (count (:status-listeners @carabiner))))
        (is (= 1 (count (:disconnection-listeners @carabiner))) "and one to hear the connection close")
        (is (link/holds-connection?))
        (testing "connected again: nothing new opens, no second listener"
          (link/connect! {} #(swap! sent conj %))
          (is (eventually? #(= 2 (count (filter #{connected} @sent)))))
          (is (= 1 (calls carabiner :connect)))
          (is (= 1 (calls carabiner :add-listener)))
          (is (= 1 (calls carabiner :add-closed-listener))))
        (testing "one report, one event"
          (report! carabiner {:link-bpm 140.0 :link-peers 2})
          (is (= [{:event/type :timing/link-peers-changed :peers 2}] (drop 3 @sent))))))))

(deftest failed-connection-registers-no-listener-test
  (with-carabiner {:refuse? true}
    (fn [carabiner]
      (let [sent (atom [])]
        (link/connect! {} #(swap! sent conj %))
        (is (eventually? #(seq @sent)))
        (is (= [{:event/type :timing/carabiner-connected :connected? false}] @sent))
        (is (empty? (:status-listeners @carabiner)))
        (is (empty? (:disconnection-listeners @carabiner)))
        (is (not (link/holds-connection?)))
        (link/release! 1000)
        (is (zero? (calls carabiner :disconnect)) "nothing to disconnect")))))

(deftest release-closes-the-connection-once-test
  (with-carabiner
    (fn [carabiner]
      (let [sent (atom [])
            closes #(mapv (partial calls carabiner) [:remove-closed-listener :remove-listener :disconnect])]
        (link/connect! {} #(swap! sent conj %))
        (is (eventually? link/holds-connection?))
        (link/release! 1000)
        (is (= [1 1 1] (closes)) "its listeners removed, and disconnected")
        (is (empty? (:status-listeners @carabiner)))
        (is (empty? (:disconnection-listeners @carabiner)))
        (is (= [connected] @sent) "its own close isn't reported: the app it belongs to is stopping")
        (is (not (link/holds-connection?)))
        (testing "again: nothing more to close"
          (link/release! 1000)
          (is (= [1 1 1] (closes))))
        (testing "a connect after it is dropped, until open!"
          (is (false? (link/connect! {} #(swap! sent conj %))))
          (Thread/sleep 50)
          (is (= 1 (calls carabiner :connect)))
          (link/open!)
          (is (true? (link/connect! {} #(swap! sent conj %))))
          (is (eventually? link/holds-connection?))
          (is (= 2 (calls carabiner :connect))))))))

(deftest a-connect-that-finishes-after-release-is-undone-test
  (with-carabiner
    (fn [carabiner]
      (let [entered (promise)
            gate (promise)
            sent (atom [])
            connect bc/connect]
        (with-redefs [bc/connect (fn [] (deliver entered true) (deref gate 5000 nil) (connect))]
          (link/connect! {} #(swap! sent conj %))
          (is (deref entered 2000 false))
          ;; stop! comes while it connects
          (let [released (future (link/release! 3000))]
            (is (eventually? #(= :closed (:state @@#'link/!runtime))))
            (deliver gate true)
            (is (nil? (deref released 5000 ::timeout)))
            (is (= 1 (calls carabiner :connect)) "it connected")
            (is (= 1 (calls carabiner :disconnect)) "and was disconnected at once, once")
            (is (zero? (calls carabiner :add-listener)) "with no listener")
            (is (not (link/holds-connection?)))
            (is (= [{:event/type :timing/carabiner-connected :connected? false}] @sent)
                "its result says it isn't connected")))))))

(deftest release-during-a-repeated-connect-disconnects-once-test
  (with-carabiner
    (fn [carabiner]
      (let [sent (atom [])]
        (link/connect! {} #(swap! sent conj %))
        (is (eventually? link/holds-connection?))
        (let [entered (promise)
              gate (promise)
              active? bc/active?]
          ;; Connected already: the next connect only checks it is
          (with-redefs [bc/active? (fn [] (deliver entered true) (deref gate 5000 nil) (active?))]
            (link/connect! {} #(swap! sent conj %))
            (is (deref entered 2000 false))
            (let [released (future (link/release! 3000))]
              (is (eventually? #(= :closed (:state @@#'link/!runtime))))
              (deliver gate true)
              (is (nil? (deref released 5000 ::timeout)))
              (is (= [1 1] (mapv (partial calls carabiner) [:remove-listener :disconnect]))
                  "release! removed its listener and disconnected, once")
              (is (not (link/holds-connection?))))))))))

(deftest app-state-holds-no-link-objects-test
  (with-carabiner {:status {:link-bpm 128.0 :link-peers 1}}
    (fn [carabiner]
      (try
        (let [dispatch (app-dispatcher)
              link-state #(get-in (state/get-raw-state) [:backend :link])]
          ;; As app/start! asks for it
          (dispatch {:event/type :timing/carabiner-auto-connect})
          (is (eventually? #(= [true 128.0 1] ((juxt :carabiner-connected? :link-bpm :link-peers) (link-state))))
              "the connection and the listener's first report, by events")
          (is (= 1 (count (:status-listeners @carabiner))))
          (is (not (contains? (link-state) :listener)))
          (is (every? #(or (nil? %) (boolean? %) (number? %)) (vals (link-state)))
              (str "no listener, fn or connection in app state: " (pr-str (link-state)))))
        (finally (events/retire!))))))

(deftest a-stopped-apps-link-reports-are-dropped-test
  ;; They go back through the dispatcher of the effect that asked
  ;; (reactivity plan 19): the app that connected is gone, so its
  ;; listener's reports change nothing in the app after it
  (log-test/with-log
    (with-carabiner
      (fn [carabiner]
        (try
          (let [old (app-dispatcher)]
            (old {:event/type :timing/carabiner-auto-connect})
            (is (eventually? #(true? (get-in (state/get-raw-state) [:backend :link :carabiner-connected?]))))
            (let [successor (app-dispatcher)
                  before (get-in (state/get-raw-state) [:backend :link])]
              (report! carabiner {:link-bpm 133.0 :link-peers 3})
              (is (eventually? #(log-test/logged? 'laser-show.events.core :info #"dropped :timing/link-bpm-changed"))
                  "its report came back to the stopped app")
              (is (handled? successor 2000))
              (is (= before (get-in (state/get-raw-state) [:backend :link])) "and changed nothing")))
          (finally (events/retire!)))))))


;; The connection closing on its own (follow-up N5): Carabiner quit or
;; closed it, or beat-carabiner gave up on it for sending no status

(def ^:private disconnected {:event/type :timing/carabiner-connected :connected? false})

(deftest a-dropped-connection-is-reported-test
  (doseq [[why unexpected?] [["Carabiner closed it" true]
                             ["beat-carabiner's own check found no status" false]]]
    (testing why
      (with-carabiner
        (fn [carabiner]
          (let [sent (atom [])]
            (link/connect! {} #(swap! sent conj %))
            (is (eventually? #(= [connected] @sent)))
            (fixtures/drop-carabiner! carabiner unexpected?)
            (is (= [connected disconnected] @sent) "through the dispatcher that asked")
            (is (not (link/holds-connection?)))
            (is (empty? (:status-listeners @carabiner)) "its listeners are removed")
            (is (empty? (:disconnection-listeners @carabiner)))
            (testing "it is over: nothing comes through it, its close isn't heard twice, release! has nothing to close"
              (report! carabiner {:link-bpm 133.0})
              (fixtures/drop-carabiner! carabiner true)
              (link/release! 1000)
              (is (= [connected disconnected] @sent))
              (is (zero? (calls carabiner :disconnect))))))))))

(deftest a-connect-after-a-drop-connects-again-test
  (with-carabiner {:status {:link-bpm 128.0 :link-peers 1}}
    (fn [carabiner]
      (let [sent (atom [])]
        (link/connect! {} #(swap! sent conj %))
        (is (eventually? link/holds-connection?))
        (fixtures/drop-carabiner! carabiner)
        (reset! sent [])
        (is (true? (link/connect! {} #(swap! sent conj %))) "as start-up's auto-connect asks")
        (is (eventually? #(= connected (last @sent))))
        (is (= 2 (calls carabiner :connect)) "a new connection")
        (is (link/holds-connection?))
        (is (= [1 1] [(count (:status-listeners @carabiner)) (count (:disconnection-listeners @carabiner))])
            "with its own listeners, and only those")
        (testing "its listener reports"
          (report! carabiner {:link-bpm 131.0})
          (is (= {:event/type :timing/link-bpm-changed :bpm 131.0} (last @sent))))
        (testing "and its close is heard too"
          (fixtures/drop-carabiner! carabiner)
          (is (= disconnected (last @sent)))
          (is (not (link/holds-connection?))))))))

(deftest a-close-before-its-listener-is-in-is-reported-test
  ;; Carabiner closed the connection between the connect and the owner's
  ;; disconnection listener going in: beat-carabiner told the listeners it
  ;; had then, not this one
  (with-carabiner
    (fn [carabiner]
      (let [sent (atom [])
            add bc/add-disconnection-listener]
        (with-redefs [bc/add-disconnection-listener (fn [l] (swap! carabiner assoc :connected? false) (add l))]
          (link/connect! {} #(swap! sent conj %))
          (is (eventually? #(seq @sent))))
        (is (= [disconnected] @sent) "not connected")
        (is (not (link/holds-connection?)))
        (is (empty? (:status-listeners @carabiner)))
        (is (empty? (:disconnection-listeners @carabiner)))))))

(deftest a-close-heard-after-a-reconnect-changes-nothing-test
  ;; The connection dropped, and a connect brought it back before
  ;; beat-carabiner's word of the drop came: the connection, and its
  ;; listeners, serve the new one
  (with-carabiner
    (fn [carabiner]
      (let [sent (atom [])]
        (link/connect! {} #(swap! sent conj %))
        (is (eventually? link/holds-connection?))
        (let [[on-closed] (seq (:disconnection-listeners @carabiner))]
          (swap! carabiner assoc :connected? false)
          (link/connect! {} #(swap! sent conj %))
          (is (eventually? #(= [connected connected] @sent)) "connected again")
          (is (= 2 (calls carabiner :connect)))
          (on-closed true)
          (is (= [connected connected] @sent) "the late word of the drop changes nothing")
          (is (link/holds-connection?))
          (is (= 1 (calls carabiner :add-listener)) "the listeners it had serve the new connection")
          (testing "and the next drop is heard"
            (fixtures/drop-carabiner! carabiner)
            (is (= [connected connected disconnected] @sent))))))))

(deftest a-drop-turns-link-off-and-a-reconnect-brings-it-back-test
  (with-carabiner {:status {:link-bpm 128.0 :link-peers 1}}
    (fn [carabiner]
      (try
        (let [dispatch (app-dispatcher)
              link-state #(get-in (state/get-raw-state) [:backend :link])
              bpm #(get-in (state/get-raw-state) [:timing :bpm])]
          (dispatch {:event/type :timing/carabiner-auto-connect})
          (is (eventually? #(= [true 128.0 1] ((juxt :carabiner-connected? :link-bpm :link-peers) (link-state)))))
          (dispatch {:event/type :timing/link-toggle})
          (dispatch {:event/type :timing/link-set-sync-enabled :fx/event true})
          (is (eventually? #(= 128.0 (bpm))) "Link on, its tempo followed")
          (fixtures/drop-carabiner! carabiner)
          (is (eventually? #(false? (:carabiner-connected? (link-state)))))
          (is (= {:link-enabled? false :link-bpm nil :link-peers 0 :sync-enabled? true}
                 (select-keys (link-state) [:link-enabled? :link-bpm :link-peers :sync-enabled?]))
              "Link is off, its tempo and peers forgotten; following its tempo stays selected")
          (is (= 128.0 (bpm)) "the app keeps the tempo it has")
          (testing "connected again: Link can be enabled again, and its tempo is followed again"
            (swap! carabiner assoc-in [:status :link-bpm] 131.0)
            (dispatch {:event/type :timing/carabiner-auto-connect})
            (is (eventually? #(= [true 131.0] ((juxt :carabiner-connected? :link-bpm) (link-state)))))
            (dispatch {:event/type :timing/link-toggle})
            (is (eventually? #(= 131.0 (bpm))))))
        (finally (events/retire!))))))


;; Beat alignment with Link (the user's decision of 2026-11-25: beats line
;; up, bars needn't): the app's beat phase follows Link's, read on a query
;; connection of the owner's own (Carabiner's phase-at-time: beat-carabiner
;; gives no beat, and its beat-at-time would move the session's grid).
;; Carabiner is stood in for twice: beat-carabiner's connection
;; (with-carabiner) and Carabiner's own port (with-phase-server, an
;; in-process loopback server); nothing reaches a daemon

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-9))

(deftest beat-correction-test
  (testing "the shorter way round, from the phase now"
    (is (close? 0.3 (link/beat-correction 0.3 {:current 10.0 :settled 10.0})))
    (is (close? -0.2 (link/beat-correction 0.8 {:current 10.0 :settled 10.0})))
    (is (close? 0.03 (link/beat-correction 0.02 {:current 9.99 :settled 9.99})) "across the beat"))
  (testing "within the deadband: nothing"
    (is (nil? (link/beat-correction 0.01 {:current 10.0 :settled 10.0})))
    (is (nil? (link/beat-correction 0.99 {:current 10.0 :settled 10.0}))))
  (testing "gliding onto Link's beat already: nothing"
    (is (nil? (link/beat-correction 0.3 {:current 10.1 :settled 10.3}))))
  (testing "gliding elsewhere: the correction from where the phase is now, as :shift moves the target from there"
    (is (close? 0.2 (link/beat-correction 0.3 {:current 10.1 :settled 10.6})))))

(defn- with-ticking-clock
  "(f t0) while a thread steps the show clock every 5 ms from app state, as
   the show clock does (show-time/step: its requests, then its clocks), its
   global clock's beats counted from t0 (System/nanoTime); show time reset
   after."
  [f]
  (let [t0 (System/nanoTime)
        running (atom true)]
    (reset! show-time/!runtime (-> show-time/initial-runtime
                                   (assoc :t0-ns t0)
                                   (assoc-in [:global :last-frame-time] 0.0)))
    (let [ticker (future
                   (while @running
                     (let [now-ms (/ (- (System/nanoTime) t0) 1e6)]
                       (swap! show-time/!runtime #(:runtime (show-time/step % (state/get-raw-state) nil now-ms))))
                     (Thread/sleep 5)))]
      (try
        (f t0)
        (finally
          (reset! running false)
          (deref ticker 1000 nil)
          (show-time/reset-runtime!))))))

(defn- link-ahead
  "A Link session at 120 BPM whose beats are ahead (beats) of the app's
   unshifted ones, counted from t0: its beat phase at t-us, microseconds on
   Link's clock."
  [t0 ahead]
  (fn [t-us]
    (let [beats (+ (* (/ (- (* 1000.0 t-us) t0) 1e9) 2.0) ahead)]
      (- beats (Math/floor beats)))))

(defn- with-link-beats
  "(f {:server :carabiner :sent}) with the Link owner connected for an app
   (app-dispatcher) that follows Link at 120 BPM with Align beats with Link
   on (link, merged into [:backend :link], may turn things off or set
   :latency-ms), its show clock ticking, Link's beats :ahead of the app's,
   and a beat check every :interval-ms (50). With :hold? the phase server
   holds its answers until (:hold @server) is delivered. sent: what the
   owner dispatched, in order (an atom), on its way to the app."
  [{:keys [ahead link hold? interval-ms]} f]
  (fixtures/with-phase-server
    (fn [server]
      (state/init-state! (update-in (domains/build-initial-state) [:backend :link] merge
                                    {:carabiner-connected? true :link-enabled? true :sync-enabled? true
                                     :beat-sync? true :link-bpm 120.0}
                                    link))
      (with-redefs [link/alignment-interval-ms (or interval-ms 50)]
        (fixtures/with-carabiner {:status {:port (:port @server) :link-bpm 120.0 :link-peers 1}}
          (fn [carabiner]
            (with-ticking-clock
              (fn [t0]
                (swap! server assoc :phase-at (link-ahead t0 (or ahead 0.0)) :hold (when hold? (promise)))
                (let [app (app-dispatcher)
                      sent (atom [])]
                  (try
                    (link/connect! {} (fn [event] (swap! sent conj event) (app event)))
                    (f {:server server :carabiner carabiner :sent sent})
                    (finally
                      (some-> (:hold @server) (deliver true))
                      (events/retire!))))))))))))

(defn- corrections [sent] (filterv #(= :timing/align-to-link-beat (:event/type %)) @sent))

(defn- shift-asked [] (get-in (state/get-raw-state) [:playback :requests :shift :offset]))

(deftest a-beat-error-beyond-the-deadband-is-corrected-once-the-short-way-test
  (doseq [[ahead expected] [[0.3 0.3] [0.8 -0.2]]]
    (testing (str "Link's beats " ahead " of a beat ahead of the app's")
      (with-link-beats {:ahead ahead}
        (fn [{:keys [server carabiner sent]}]
          (is (eventually? #(seq (corrections sent))) "corrected")
          (Thread/sleep 500)
          (let [[{:keys [offset]} :as all] (corrections sent)]
            (is (= 1 (count all)) "once: the show clock is gliding onto Link's beat, so the checks after it find it settled")
            (is (< (Math/abs (- expected offset)) 0.01) (str "the shorter way round: " offset)))
          (is (< (Math/abs (- expected (or (shift-asked) 0.0))) 0.01) "the show clock moves its phase targets by it (:shift)")
          (is (< 5 (count (:received @server))) "checked all along")
          (is (every? #(str/starts-with? % "phase-at-time ") (:received @server))
              "nothing but queries asked of Carabiner: nothing that changes the session")
          (is (zero? (calls carabiner :beat-at-time)) "nor beat-carabiner's beat-at-time"))))))

(deftest a-beat-error-within-the-deadband-is-left-alone-test
  (with-link-beats {:ahead 0.01}
    (fn [{:keys [server sent]}]
      (is (eventually? #(< 5 (count (:received @server)))) "checked")
      (is (empty? (corrections sent)))
      (is (nil? (shift-asked))))))

(deftest the-latency-compensation-runs-the-beats-ahead-test
  (with-link-beats {:ahead 0.0 :link {:latency-ms 100}}
    (fn [{:keys [sent]}]
      (is (eventually? #(seq (corrections sent))))
      (is (< (Math/abs (- 0.2 (:offset (first (corrections sent))))) 0.01)
          "output 100 ms late, at 120 BPM: the beats run 0.2 of a beat ahead of Link's"))))

(deftest no-beat-check-while-the-app-doesnt-align-test
  (doseq [[why link] [["Link disabled" {:link-enabled? false}]
                      ["its tempo not followed" {:sync-enabled? false}]
                      ["Align beats with Link off" {:beat-sync? false}]]]
    (testing why
      (with-link-beats {:ahead 0.3 :link link}
        (fn [{:keys [server sent]}]
          (Thread/sleep 300)
          (is (empty? (:received @server)) "nothing asked of Carabiner")
          (is (empty? (corrections sent))))))))

(deftest a-dropped-connection-stops-the-beat-checks-test
  (with-link-beats {:ahead 0.0}
    (fn [{:keys [server carabiner]}]
      (is (eventually? #(seq (:received @server))) "a query connection, asked on")
      (fixtures/drop-carabiner! carabiner)
      (is (eventually? #(= (:clients @server) (:closed @server))) "closed with the connection")
      (let [asked (count (:received @server))]
        (Thread/sleep 250)
        (is (= asked (count (:received @server))) "and no check after it")))))

(deftest release-closes-the-query-connection-test
  (with-link-beats {:ahead 0.0}
    (fn [{:keys [server]}]
      (is (eventually? #(seq (:received @server))))
      (link/release! 1000)
      (is (eventually? #(= (:clients @server) (:closed @server))) "closed by release!")
      (let [asked (count (:received @server))]
        (Thread/sleep 250)
        (is (= asked (count (:received @server))) "and no check runs after it")))))

(deftest a-stopped-apps-late-beat-correction-is-dropped-test
  ;; The answer comes back after the app that connected was replaced: its
  ;; correction goes to that app's dispatcher (reactivity plan 19), which
  ;; drops it
  (log-test/with-log
    (with-link-beats {:ahead 0.3 :hold? true}
      (fn [{:keys [server]}]
        (is (eventually? #(seq (:received @server))) "a check's query, its answer held")
        (let [successor (app-dispatcher)]
          (deliver (:hold @server) true)
          (is (eventually? #(log-test/logged? 'laser-show.events.core :info #"dropped :timing/align-to-link-beat"))
              "its correction came back to the stopped app")
          (is (handled? successor 2000))
          (is (nil? (shift-asked)) "and asked nothing of the show clock"))))))

(deftest a-tempo-change-brings-a-check-soon-test
  (with-link-beats {:ahead 0.0 :interval-ms 60000}
    (fn [{:keys [server carabiner]}]
      (Thread/sleep 300)
      (is (empty? (:received @server)) "no check yet: the next is a minute away")
      (fixtures/report-status! carabiner {:link-bpm 121.0})
      (is (eventually? #(seq (:received @server))) "Link's tempo changed: checked soon after"))))
