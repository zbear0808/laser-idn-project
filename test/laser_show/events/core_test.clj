(ns laser-show.events.core-test
  "Tests for the event wiring in laser-show.events.core: which effects continue
   the current event inline, which call back through the dispatcher they are
   given, the no-app dispatch! fallback, and the dev-mode lost-update detector."
  (:require
   [cljfx.api :as fx]
   [cljfx.event-handler :as event-handler]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.tools.logging.test :as log-test]
   [laser-show.events.core :as events]
   [laser-show.events.handlers :as handlers]
   [laser-show.events.handlers.menu :as menu]
   [laser-show.events.handlers.timing :as timing]
   [laser-show.idn.hello :as idn-hello]
   [laser-show.backend.output-runtime :as output-runtime]
   [laser-show.output.discovery :as discovery]
   [laser-show.output.driver :as driver]
   [laser-show.output.jar-builder :as jar]
   [laser-show.output.recording-driver :as rd]
   [laser-show.state.core :as state]
   [laser-show.state.domains :as domains]
   [laser-show.state.history :as history]
   [laser-show.state.persistent :as persistent]
   [laser-show.state.rig-store :as rig-store])
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
              [:state :dispatch :dispatch-n :dispatch-later :link/connect
               :input/sync-learn :multi-engine/start :multi-engine/stop :multi-engine/refresh
               :input/midi-list :input/midi-connect :input/midi-disconnect :input/osc-start :input/osc-stop
               :output/write-status
               :project/save :project/load :fx/show-file-chooser :system/exit :system/open-url
               :dev/reload :dev/deliver :dev/swap-state :clipboard/write :clipboard/read
               :projectors/scan :addons/install :system/open-folder :window/set-fullscreen
               :rig/write :rig/flush :history/gesture-alarm]))
  (is (not-any? #(contains? events/effects %) [:clipboard/copy-effects :clipboard/paste-effects])
      "no effect copies or pastes behind the clipboard's handlers (keyboard plan Step 4a)")
  (is (not (contains? events/effects :clipboard/paste-projector-effects))
      "a projector chain pastes through the list editor's own clipboard (zones Z0)")
  (is (not (contains? events/effects :timing/calculate-bpm)) "a tap sets the BPM in its own handler's state"))

(deftest gesture-alarm-replacement-and-retirement-test
  ;; Reactivity plan 16 §6: one wake-up for the open press, through the
  ;; dispatcher the effect was given; a cancelled or replaced one that fires
  ;; anyway sends nothing, and a stale cancel can't cancel its successor
  (let [tasks (atom [])
        sent (atom [])
        alarm (get events/effects :history/gesture-alarm)
        g #uuid "00000000-0000-0000-0000-0000000016e1"
        h #uuid "00000000-0000-0000-0000-0000000016e2"
        k #uuid "00000000-0000-0000-0000-0000000016e3"
        state-before (state/get-raw-state)]
    (events/dispose-gesture-alarm!)
    (with-redefs [events/schedule-gesture-wake! (fn [delay-ms task]
                                                  (let [cancelled (atom false)]
                                                    (swap! tasks conj {:delay delay-ms :task task :cancelled cancelled})
                                                    #(reset! cancelled true)))]
      (let [dispatch #(swap! sent conj %)]
        (alarm {:arm {:gesture-id g :delay-ms 60000}} dispatch)
        (alarm {:cancel-id g :arm {:gesture-id h :delay-ms 60000}} dispatch)
        (let [[tg th] @tasks]
          (is (= [60000 60000] (mapv :delay @tasks)))
          (is (true? @(:cancelled tg)) "G's wake-up was cancelled when H's took its place")
          (is (false? @(:cancelled th)))
          ((:task tg))
          (is (= [] @sent) "G's, delivered after all, sends nothing")
          (alarm {:cancel-id g} dispatch)
          (is (false? @(:cancelled th)) "a late cancel for G leaves H's alone")
          ((:task th))
          (is (= [{:event/type :history/gesture-timeout :history/gesture-id h}] @sent) "one live wake-up, H's")
          ((:task th))
          (is (= 1 (count @sent)) "and only once"))
        (testing "retired with its app, a wake-up sends nothing"
          (alarm {:arm {:gesture-id k :delay-ms 1000}} dispatch)
          (let [tk (last @tasks)]
            (events/dispose-gesture-alarm!)
            (is (true? @(:cancelled tk)))
            ((:task tk))
            (is (= 1 (count @sent)))))))
    (is (identical? state-before (state/get-raw-state)) "it never writes app state")))

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

(deftest dispatch-n-continues-with-each-event-in-order-test
  (let [later (atom [])
        handle timing/handle]
    (with-redefs [timing/handle (fn [event]
                                  (if (= :timing/probe (:event/type event))
                                    {:dispatch-n [{:event/type :timing/set-bpm :bpm 99.0}
                                                  {:event/type :timing/set-bpm :bpm 100.0}]}
                                    (handle event)))]
      (events/event-handler {:event/type :timing/probe} #(swap! later conj %)))
    (is (= 100.0 (get-in (state/get-raw-state) [:timing :bpm])) "both ran, the second after the first")
    (is (= [] @later) "now, in the same call, not through the dispatcher")))

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

(deftest state-runs-first-test
  (testing "an inline follow-up that the handler put before :state still lands (w3)"
    (let [handle timing/handle]
      (with-redefs [timing/handle (fn [{:keys [state] :as event}]
                                    (if (= :timing/probe (:event/type event))
                                      (array-map :dispatch {:event/type :timing/set-bpm :bpm 99.0}
                                                 :state (assoc-in state [:ui :probe] 1))
                                      (handle event)))]
        (events/event-handler {:event/type :timing/probe})))
    (is (= 99.0 (get-in (state/get-raw-state) [:timing :bpm])) "the follow-up ran after the write-back")
    (is (= 1 (get-in (state/get-raw-state) [:ui :probe]))))
  (testing "a path write that a middleware's assoc put before :state is kept"
    (let [status {:engines 1 :projectors {}}]
      (with-redefs [timing/handle (fn [{:keys [state]}]
                                    (assoc {:output/write-status status} :state state))]
        (events/event-handler {:event/type :timing/probe}))
      (is (= status (get-in (state/get-raw-state) output-runtime/status-path))))))

(deftest state-first-keeps-the-other-effects-in-order-test
  (let [others [:dispatch :dispatch-later :multi-engine/refresh :rig/write :output/write-status
                :project/save :fx/show-file-chooser :system/exit]
        nine (assoc (reduce #(assoc %1 %2 true) (array-map) others) :state 1)
        ordered (#'events/state-first (constantly nine))]
    (is (instance? clojure.lang.PersistentHashMap nine) "nine keys: a hash map, in hash order")
    (is (= (cons :state (keys (dissoc nine :state))) (map key (ordered {}))))
    (is (= (remove #{:state} (keys nine)) (rest (map key (ordered {})))) "the others in the map's own order")
    (is (= 1 (val (first (ordered {})))))
    (let [small (array-map :dispatch 1 :output/write-status 2 :state 3)]
      (is (= [[:state 3] [:dispatch 1] [:output/write-status 2]]
             (map (juxt key val) ((#'events/state-first (constantly small)) {})))))
    (testing "without :state, unchanged"
      (doseq [m [nil {} {:output/write-status {:engines 0}} (array-map :dispatch 1 :system/exit true)]]
        (is (identical? m ((#'events/state-first (constantly m)) {})) (pr-str m))))
    (testing "the handler gets the event"
      (is (= [[:state {:event/type ::e}]] (map (juxt key val) ((#'events/state-first (fn [e] {:state e})) {:event/type ::e})))))))

(deftest dispatch-later-calls-back-through-the-dispatcher-test
  (let [got (promise)]
    ((get events/effects :dispatch-later) {:event {:event/type ::later} :delay-ms 0}
                                          #(deliver got %))
    (is (= {:event/type ::later} (deref got 2000 ::timeout)))))

(deftest set-fullscreen-reports-back-without-a-main-window-test
  (testing "no main window (no toolkit, or none showing): it reports not fullscreen, so the flag can't stick on"
    (let [got (promise)]
      ((get events/effects :window/set-fullscreen) true #(deliver got %))
      (is (= {:event/type :view/fullscreen-changed :fullscreen? false} (deref got 5000 ::timeout))))))


;; No-app fallback


(deftest fallback-runs-every-effect-test
  (testing "without an app, dispatch! runs effects such as :system/exit"
    (let [exits (atom 0)]
      (events/set-exit-fn! #(swap! exits inc))
      (events/dispatch! {:event/type :file/exit})
      (is (= 1 @exits)))))


;; Waiting for the queue, and writing on the event thread (tests, the REPL)


(defn- on-event-thread
  "(f) run on events/event-executor: its value, or ::timeout after 5 s."
  [f]
  (try
    (.get (.submit events/event-executor ^Callable f) 5 TimeUnit/SECONDS)
    (catch java.util.concurrent.TimeoutException _ ::timeout)))

(deftest flush-test
  (testing "without an app: true at once (the synchronous fallback)"
    (is (true? (events/flush!)))
    (is (true? (events/flush! 0))))
  (testing "on the event thread: false at once, as the event in flight is this one"
    (let [[result ms on-thread?] (on-event-thread #(let [t0 (System/currentTimeMillis)
                                                         result (events/flush!)]
                                                     [result (- (System/currentTimeMillis) t0)
                                                      (events/on-event-thread?)]))]
      (is on-thread?)
      (is (false? result))
      (is (< ms 1000) (str ms " ms"))))
  (is (not (events/on-event-thread?)) "the test thread isn't the event thread"))

(deftest write-state-test
  (let [path [:chains :cue-chains [0 0] :name]
        before (state/get-raw-state)
        submitted (atom [])]
    (with-redefs [output-runtime/submit-sync! (fn [& args] (swap! submitted conj [:sync args]))
                  output-runtime/submit-stop! (fn [& args] (swap! submitted conj [:stop args]))
                  rig-store/submit! (fn [& args] (swap! submitted conj [:rig args]))]
      (is (nil? (events/write-state! assoc-in path "Written")))
      (is (= "Written" (get-in (state/get-raw-state) path)) "without an app, written at once")
      (testing "a write the output sync and the rig autosave would act on, through :state"
        (events/write-state! assoc-in [:backend :streaming :running?] true)
        (events/write-state! update :projectors assoc :p1 {:name "Out 1" :host "10.0.0.5" :service-id 1
                                                           :enabled? true :zone-groups [:all]})
        (is (= "Out 1" (get-in (state/get-raw-state) [:projectors :p1 :name])))))
    (is (= (:history before) (:history (state/get-raw-state))) "no undo step")
    (is (false? (get-in (state/get-raw-state) [:project :dirty?])) "the unsaved flag untouched")
    (is (= [] @submitted) "nothing reaches the output runtime or the rig writer")))


;; App instances (reactivity plan 19)


(defn- dispatcher-of
  "instance's dispatcher as app/create-app has cljfx make it, without a
   renderer: an agent of its own, its events on the event thread."
  [instance]
  (-> (events/instance-handler instance)
      (fx/wrap-co-effects events/co-effects)
      (fx/wrap-effects events/effects)
      (event-handler/wrap-async {:fx/executor events/event-executor})))

(defn- handled?
  "Has dispatch's queue handled what it was given before, within 5 s?"
  [dispatch]
  (let [p (promise)]
    (dispatch {:event/type :dev/flush :promise p})
    (deref p 5000 false)))

(defn- with-instance
  "(f dispatch) with dispatch the dispatcher of a new app instance, live for
   the context now; none is live after."
  [f]
  (try
    (f (dispatcher-of (events/new-instance!)))
    (finally (events/retire!))))

(defn- held-probe
  "(f entered gate) while :timing/probe's handler, once it has its state,
   waits for gate (5 s at most), then returns effects. entered is delivered
   then; the gate opens after f in any case."
  [effects f]
  (let [entered (promise)
        gate (promise)
        handle timing/handle]
    (with-redefs [timing/handle (fn [{:keys [state] :as event}]
                                  (if (= :timing/probe (:event/type event))
                                    (do (deliver entered true)
                                        (deref gate 5000 nil)
                                        (effects state))
                                    (handle event)))]
      (try (f entered gate) (finally (deliver gate true))))))

(defn- bpm [] (get-in (state/get-raw-state) [:timing :bpm]))

(deftest a-retired-instance-does-nothing-test
  (log-test/with-log
    (with-instance
      (fn [dispatch]
        (let [exits (atom 0)]
          (events/set-exit-fn! #(swap! exits inc))
          (dispatch {:event/type :timing/set-bpm :bpm 99.0})
          (is (handled? dispatch))
          (is (= 99.0 (bpm)) "while it is live, as any app's")
          (events/retire!)
          (dispatch {:event/type :timing/set-bpm :bpm 100.0})
          (dispatch {:event/type :file/exit})
          (is (handled? dispatch) "once retired, its queue still says when it is through")
          (is (= 99.0 (bpm)) "but what it is sent changes nothing")
          (is (zero? @exits) "and does nothing")
          (is (log-test/logged? 'laser-show.events.core :info #"dropped :timing/set-bpm"))
          (testing "a new instance retires the one before"
            (let [first-one (dispatcher-of (events/new-instance!))
                  second-one (dispatcher-of (events/new-instance!))]
              (first-one {:event/type :timing/set-bpm :bpm 101.0})
              (second-one {:event/type :timing/set-bpm :bpm 102.0})
              (is (handled? first-one))
              (is (handled? second-one))
              (is (= 102.0 (bpm))))))))))

(deftest an-event-running-as-its-instance-retires-does-nothing-test
  (with-instance
    (fn [dispatch]
      (let [exits (atom 0)]
        (events/set-exit-fn! #(swap! exits inc))
        (held-probe (fn [state] {:state (assoc-in state [:ui :probe] 1)
                                 :dispatch {:event/type :timing/set-bpm :bpm 99.0}
                                 :system/exit true})
                    (fn [entered gate]
                      (dispatch {:event/type :timing/probe})
                      (is (deref entered 2000 false))
                      (events/retire!)
                      (deliver gate true)
                      (is (handled? dispatch))))
        (is (nil? (get-in (state/get-raw-state) [:ui :probe])) "not its write")
        (is (not= 99.0 (bpm)) "nor the event it continues with")
        (is (zero? @exits) "nor any other of its effects")))))

(deftest what-a-retired-instance-continues-inline-does-nothing-test
  (with-instance
    (fn [dispatch]
      (let [exits (atom 0)
            writing (promise)
            gate (promise)
            handle timing/handle]
        (events/set-exit-fn! #(swap! exits inc))
        (with-redefs [persistent/write-project! (fn [& _] (deliver writing true) (deref gate 5000 nil) true)
                      timing/handle (fn [event]
                                      (if (= :timing/probe (:event/type event))
                                        {:project/save {:file-path "/nowhere/show.zip" :files {}
                                                        :on-saved {:event/type :file/exit :force? true}}}
                                        (handle event)))]
          ;; A save, then Exit once it is saved; the app goes while it writes
          (dispatch {:event/type :timing/probe})
          (is (deref writing 2000 false))
          (events/retire!)
          (deliver gate true)
          (is (handled? dispatch)))
        (is (nil? (get-in (state/get-raw-state) [:project :current-file])) "the save's follow-up wrote nothing")
        (is (zero? @exits) "and the Exit after it didn't run")))))

(deftest a-retired-app-s-try-again-never-quits-the-next-app-test
  ;; Follow-up B9: a rig that takes the place of one with a waiting Exit
  ;; carries that Exit on (rig-store/submit!), but only within its app. Here
  ;; the next app's rig edit takes the place of a retired app's Try again,
  ;; whose write was still waiting behind a stuck one
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "core-rig-then" (make-array java.nio.file.attribute.FileAttribute 0)))
        path (str (io/file dir "rig.edn"))
        exits (atom 0)
        entered (promise)
        gate (promise)
        write! rig-store/write!]
    (events/set-exit-fn! #(swap! exits inc))
    (state/swap-state! assoc-in [:rig-file :path] path)
    (with-redefs [rig-store/write! (fn [p doc] (deliver entered true) (deref gate 5000 nil) (write! p doc))]
      (try
        (with-instance
          (fn [first-app]
            (first-app {:event/type :rig/retry-write})
            (is (deref entered 2000 false) "the writer is stuck in a write")
            (first-app {:event/type :rig/retry-write :then {:event/type :file/exit :force? true :rig-force? true}})
            (is (handled? first-app))
            (let [next-app (dispatcher-of (events/new-instance!))]
              (next-app {:event/type :rig/set-middle :middle :both})
              (is (handled? next-app))
              (deliver gate true)
              (is (true? (rig-store/flush! 5000)))
              (is (handled? next-app) "the write's result has come back to the next app")
              (is (= :saved (get-in (state/get-raw-state) [:rig-file :status])))
              (is (= :both (get-in (rig-store/read! path) [:doc :rig :middle])))
              (is (zero? @exits) "the retired app's Exit doesn't quit the next one"))))
        (finally
          (deliver gate true)
          (rig-store/flush! 2000)
          (doseq [^File f (reverse (file-seq dir))] (.delete f)))))))

(deftest a-retired-app-s-queued-exit-never-quits-the-next-app-test
  ;; Follow-up B12b: an Exit back to be asked again while the dialog asks
  ;; something else waits behind that question, in its app's state, and
  ;; comes back once the question is answered. Not for an app stopped while
  ;; it answered: that answer, and the Exit after it, are the stopped app's.
  ;; The next app has the same show here, clean, so only the app instance
  ;; stands between that Exit and quitting
  (let [exits (atom 0)
        show (-> (domains/build-initial-state)
                 (assoc-in [:project :document-id] (random-uuid))
                 (history/mark-clean))
        ;; Exit back from its wait for the rig, pressed on the clean show
        back {:event/type :file/exit :force? true :rig-waited? true
              :authorized {:document-id (get-in show [:project :document-id]) :dirty? false}}
        dialog #(get-in (state/get-raw-state) [:ui :dialogs :unsaved-confirm])
        asking? (fn [pending] (= {:open? true :pending pending} (select-keys (dialog) [:open? :pending])))
        exit-waits-behind-new! (fn [app]
                                 (app {:event/type :cue-chain/set-name :col 2 :row 0 :name "Edited while Exit waited"})
                                 (app {:event/type :file/new-project})
                                 (app back)
                                 (is (handled? app))
                                 (is (asking? {:event/type :file/new-project}))
                                 (is (= [:file/exit] (mapv :event/type (:queued (dialog)))) "the Exit waits behind New's question"))]
    (events/set-exit-fn! #(swap! exits inc))
    (testing "in its own app, the Exit asks once the question is answered"
      (state/reset-state! show)
      (with-instance
        (fn [app]
          (exit-waits-behind-new! app)
          (app {:event/type :file/confirm-cancel})
          (is (handled? app))
          (is (asking? {:event/type :file/exit})))))
    (testing "an app stopped while it answered"
      (state/reset-state! show)
      (let [entered (promise)
            gate (promise)
            handle menu/handle]
        (with-redefs [menu/handle (fn [event]
                                    (when (= :file/confirm-cancel (:event/type event))
                                      (deliver entered true)
                                      (deref gate 5000 nil))
                                    (handle event))]
          (try
            (with-instance
              (fn [first-app]
                (exit-waits-behind-new! first-app)
                (first-app {:event/type :file/confirm-cancel})
                (is (deref entered 2000 false) "Cancel is being answered")
                (events/retire!)
                (state/init-state! (assoc show :marker :next))
                (let [next-app (dispatcher-of (events/new-instance!))]
                  (deliver gate true)
                  (is (handled? first-app))
                  (testing "; and its next Exit back, late"
                    (first-app back)
                    (is (handled? first-app)))
                  (is (handled? next-app))
                  (let [s (state/get-raw-state)]
                    (is (= :next (:marker s)) "the next app's context")
                    (is (= (get-in show [:ui :dialogs]) (get-in s [:ui :dialogs])) "no question, nothing waits"))
                  (is (zero? @exits) "and it doesn't quit"))))
            (finally
              (deliver gate true))))))))

(deftest an-instance-writes-only-into-its-own-context-test
  (log-test/with-log
    (with-instance
      (fn [dispatch]
        (held-probe (fn [state] {:state (assoc-in state [:ui :probe] 1)
                                 :output/write-status {:engines 3}})
                    (fn [entered gate]
                      (dispatch {:event/type :timing/probe})
                      (is (deref entered 2000 false))
                      ;; The context is made again under it, the instance still live
                      (state/init-state! (assoc (domains/build-initial-state) :marker :new))
                      (deliver gate true)
                      (is (handled? dispatch))))
        (let [s (state/get-raw-state)]
          (is (= :new (:marker s)) "the new context is untouched")
          (is (nil? (get-in s [:ui :probe])) "by the write computed from the one before")
          (is (nil? (get-in s output-runtime/status-path)) "and by a path write of that event"))
        (is (log-test/logged? 'laser-show.events.core :info #"not written"))))))

(deftest dev-events-are-routed-test
  (log-test/with-log
    (let [p (promise)]
      (is (= {:dev/deliver p} (handlers/handle-event {:event/type :dev/flush :promise p :state {}})))
      (is (= {:dev/swap-state {:f assoc-in :args [[:ui :probe] 1]}}
             (handlers/handle-event {:event/type :dev/write-state :f assoc-in :args [[:ui :probe] 1]
                                     :state {}})))
      (is (= {:state {:styles {:reload-trigger 1}}}
             (handlers/handle-event {:event/type :dev/reloaded :kind :styles :state {}})))
      (is (= {:state {:ui {:last-reload-timestamp 7}}}
             (handlers/handle-event {:event/type :dev/reloaded :kind :app :state {} :time 7})))
      (testing "through the whole pipeline: no :state, so no middleware adds anything"
        (is (= {:dev/deliver p} (events/handle-event-pipeline {:event/type :dev/flush :promise p
                                                               :state (state/get-raw-state) :time 0}))))
      (testing "and their effects"
        (events/event-handler {:event/type :dev/flush :promise p})
        (is (true? (deref p 0 ::not-delivered)))
        (events/event-handler {:event/type :dev/write-state :f assoc-in :args [[:ui :probe] 1]})
        (is (= 1 (get-in (state/get-raw-state) [:ui :probe])))))
    (is (not-any? #(str/includes? (str (:message %)) "Unknown event domain") (log-test/the-log)))
    (is (not-any? #(= :error (:level %)) (log-test/the-log)))))


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
  [{:keys [idn drivers timeout-ms interfaces broadcast-address scan-id]
    :or {idn (fn [& _] []) interfaces (constantly fake-interfaces) broadcast-address "255.255.255.255"}}]
  (assert (not-any? :discover (driver/registered)) "no real :discover is registered")
  (let [got (promise)]
    (with-redefs [idn-hello/discover-devices-with-services idn
                  discovery/interfaces interfaces]
      (run! driver/register! drivers)
      (try
        ((get events/effects :projectors/scan) (cond-> {:broadcast-address broadcast-address}
                                                 timeout-ms (assoc :timeout-ms timeout-ms)
                                                 scan-id (assoc :scan-id scan-id))
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

(deftest scan-sends-its-id-back-test
  (let [id (random-uuid)
        seen (atom [])]
    (is (= {:event/type :projectors/scan-complete :devices [idn-found] :warnings [] :scan-id id}
           (scan! {:idn (fn [& _] [idn-raw]) :scan-id id
                   :drivers [(discoverer :acme/nova "Acme Nova" (fn [opts] (swap! seen conj opts) []))]}))
        "the completion says which Scan it answers")
    (is (not-any? :scan-id @seen) "a discoverer isn't given it")
    (is (= {:event/type :projectors/scan-failed :error "Network is unreachable" :scan-id id}
           (scan! {:idn (fn [& _] (throw (java.net.SocketException. "Network is unreachable"))) :scan-id id}))
        "and so does a failure")))
