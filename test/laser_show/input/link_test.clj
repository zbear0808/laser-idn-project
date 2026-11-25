(ns laser-show.input.link-test
  "Tests for Ableton Link synchronization service. Carabiner is stood in
   for at beat-carabiner's functions: nothing here reaches a daemon or
   starts one."
  (:require [beat-carabiner.core :as bc]
            [cljfx.api :as fx]
            [cljfx.event-handler :as event-handler]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as log-test]
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


;; Link's beat (follow-up N4): beat-carabiner 8.0.0 gives none, and its
;; beat-at-time is no query but a push that moves the Link session's beat
;; grid; a read-only follower never sends it

(deftest the-beat-read-sends-nothing-to-carabiner-test
  (with-carabiner {:status {:link-bpm 128.0 :link-peers 2}}
    (fn [carabiner]
      (let [sent (atom [])]
        (link/connect! {} #(swap! sent conj %))
        (is (eventually? #(some #{connected} @sent)) "a Link session to read")
        (reset! sent [])
        (is (nil? (link/get-link-beat-position)) "not known: beat-carabiner gives no beat")
        (link/sync-to-downbeat! #(swap! sent conj %) 4.3)
        (is (empty? @sent) "so downbeat sync corrects nothing")
        (is (zero? (calls carabiner :beat-at-time)) "and no beat probe went to Carabiner")))))

(deftest sync-to-downbeat-changes-nothing-while-the-beat-is-unknown-test
  (with-carabiner {:status {:link-bpm 128.0 :link-peers 2}}
    (fn [carabiner]
      (link/connect! {} (fn [_]))
      (is (eventually? link/holds-connection?))
      (events/event-handler {:event/type :timing/sync-to-downbeat})
      (is (nil? (get-in (state/get-raw-state) [:playback :requests :shift])) "no phase shift asked of the show clock")
      (is (zero? (calls carabiner :beat-at-time))))))
