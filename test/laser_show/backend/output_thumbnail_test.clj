(ns laser-show.backend.output-thumbnail-test
  "What a rig card's thumbnail may claim (reactivity plan 18): each engine
   keeps the frame of its last driver call that returned normally, and when
   it returned (streaming-engine/thumbnail), owned by the run and session
   that made the call; output-runtime/thumbnail-observation reads it by
   projector id. Through the real registry, sanitizer, streaming loop and
   error branch, with a recording driver whose sends the test holds and
   releases, a scripted provider, a frame signal released by hand and a fake
   observation clock (timing/nanotime). A return says that the call
   returned: nothing here is a device, a socket or light."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.backend.multi-engine :as me]
            [laser-show.backend.output-errors :as output-errors]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.backend.streaming-engine :as se]
            [laser-show.common.timing :as timing]
            [laser-show.events.core :as events]
            [laser-show.output.recording-driver :as rd]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.views.components.preview :as preview])
  (:import [java.util.concurrent Semaphore TimeUnit]))

(def ^:private clock-ns
  "The fake observation clock: what timing/nanotime says."
  (atom 0))

(defn- at-ms! [ms] (reset! clock-ns (* (long ms) 1000000)))

(def ^:private gates
  "Every gate a test made, released after it."
  (atom []))

(defn- gate []
  (let [g (promise)]
    (swap! gates conj g)
    g))

(defn- with-runtime
  "Each test: fresh app state, the output runtime open, no output errors,
   timing/nanotime on the fake clock. Afterwards every gate is released, the
   runtime drained and opened again."
  [f]
  (state/init-state! (domains/build-initial-state))
  (output-runtime/open!)
  (output-errors/clear!)
  (reset! clock-ns 0)
  (try
    (with-redefs [timing/nanotime (fn ^long [] (long @clock-ns))]
      (f))
    (finally
      (run! #(deliver % true) @gates)
      (reset! gates [])
      (output-runtime/drain! 2000)
      (output-runtime/open!)
      (output-errors/clear!))))

(use-fixtures :each with-runtime)

(defn- eventually
  "(pred)'s first truthy value, polled every 2 ms for up to 5 s; nil if none."
  [pred]
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (or (pred)
          (when (< (System/nanoTime) deadline)
            (Thread/sleep 2)
            (recur))))))

(defn- lane
  "One projector's scripted output: a recording driver whose send i does
   what (get @script i) says ({:entered p} is delivered once the call is in
   the driver, {:gate g} holds it there until g is delivered, then {:fail x}
   throws with ex-data x (true: none), else it returns :result), a provider
   that returns @frame (counting its calls in :provided), and a frame signal
   that lets the engine try one frame per (pulse!) and lets a stopping
   engine go at once. opts go to the recording driver."
  ([id] (lane id {}))
  ([id opts]
   (let [script (atom {})
         frame (atom nil)
         provided (atom 0)
         permits (Semaphore. 0)
         ticks (atom 0)
         current (atom nil)
         rec (rd/recording-driver
              (merge {:id :test/thumbnail
                      :fail-send? (fn [i]
                                    (reset! current i)
                                    (let [{:keys [entered gate fail]} (get @script i)]
                                      (some-> entered (deliver true))
                                      (some-> gate (deref 5000 nil))
                                      fail))
                      :result (fn [_] (:result (get @script @current)))}
                     opts))]
     {:id id :rec rec :script script :frame frame :provided provided
      :provider (fn [] (swap! provided inc) @frame)
      :signal {:tick (fn [] @ticks)
               :running? (constantly true)
               :await (fn [_seen _deadline-ns keep-waiting?]
                        (loop []
                          (when (and (keep-waiting?) (not (.tryAcquire permits 2 TimeUnit/MILLISECONDS)))
                            (recur))))}
      :pulse! (fn [] (swap! ticks inc) (.release permits))})))

(defn- next-send
  "The index the lane's next send will have."
  [{:keys [rec]}]
  (count @(:frames rec)))

(defn- script!
  "What the lane's next send does (lane)."
  [{:keys [script] :as l} step]
  (swap! script assoc (next-send l) step))

(defn- with-lanes
  "Run f with the output runtime building each lane's projector's engine:
   a real driver engine for the lane's recording driver, provider and frame
   signal (the factory output-runtime calls, multi-engine/create-engine-for-
   projector)."
  [lanes f]
  (let [by-id (into {} (map (juxt :id identity)) lanes)]
    (with-redefs [me/create-engine-for-projector
                  (fn [id _projector]
                    (let [{:keys [rec provider signal]} (by-id id)]
                      (se/create-driver-engine (:driver rec) {} provider :id id :frame-signal signal)))]
      (f))))

(defn- projector [id]
  {:name (str "Laser " (name id)) :driver :test/thumbnail :driver-config {} :enabled? true :zone-groups [:all]})

(defn- observation [id] (output-runtime/thumbnail-observation id))

(defn- engine-of [id] (get-in (output-runtime/registry) [id :engine]))

(defn- caption
  "What the card would say of id's observation now (on the fake clock)."
  [id]
  (:caption (preview/thumbnail-presentation (observation id) @clock-ns)))

(defn- opened!
  "Wait until id's engine has a session; its session token."
  [id]
  (eventually #(:session-token (observation id))))

(defn- returned!
  "Pulse the lane once and wait until that send returned: a new :last-ok."
  [{:keys [id pulse!] :as l}]
  (let [before (:last-ok (observation id))
        n (next-send l)]
    (pulse!)
    (eventually #(and (> (next-send l) n)
                      (not (identical? before (:last-ok (observation id))))
                      (= :returned (:knowledge (observation id)))))))

(defn- lit [y] [[0.0 y 0.0 0.0 0.0] [0.5 y 1.0 1.0 1.0]])


(deftest success-publishes-the-sanitized-argument-test
  (let [{:keys [rec frame pulse!] :as p} (lane :p)
        entered (promise)
        release (gate)]
    (with-lanes [p]
      (fn []
        (reset! frame [[0 0 1 0 0] [##NaN 0 1 0 0] [0.5 0 1 0 0]])
        (script! p {:entered entered :gate release})
        (output-runtime/submit-sync! {:p (projector :p)})
        (is (opened! :p))
        (is (= :unknown (:knowledge (observation :p))) "opened, and no call has returned")
        (pulse!)
        (is (deref entered 3000 false) "the first send is in the driver")
        (at-ms! 25)
        (testing "while the call is held, nothing is known"
          (is (= :unknown (:knowledge (observation :p))))
          (is (nil? (:last-ok (observation :p))))
          (is (= "Unknown" (caption :p))))
        (deliver release true)
        (is (eventually #(= :returned (:knowledge (observation :p)))))
        (let [{passed :frame :keys [at-ns source]} (:last-ok (observation :p))]
          (is (identical? (first @(:frames rec)) passed) "the very vector the driver was passed")
          (is (= [[0 0 0.0 0.0 0.0] [0 0 1 0 0] [0.5 0 1 0 0]] passed)
              "sanitized: the NaN point dropped, a dark copy of the lit first point in front")
          (is (= 25000000 at-ns) "stamped when the call returned, on the observation clock")
          (is (= :normal source))
          (is (= "Driver frame" (caption :p)) "a driver that returns nil returned"))
        (testing "{:transfers 0} is still only a return"
          (let [entered (promise)
                release (gate)]
            (script! p {:entered entered :gate release :result {:transfers 0}})
            (pulse!)
            (is (deref entered 3000 false))
            (at-ms! 40)
            (deliver release true)
            (is (eventually #(= 40000000 (get-in (observation :p) [:last-ok :at-ns]))))
            (is (identical? (second @(:frames rec)) (get-in (observation :p) [:last-ok :frame])))
            (is (= "Driver frame" (caption :p)))))
        (testing "a nil from the provider goes out as [], which returns: an empty frame, known"
          (reset! frame nil)
          (at-ms! 60)
          (is (returned! p))
          (is (= {:frame [] :at-ns 60000000 :source :normal} (:last-ok (observation :p))))
          (is (= [] (peek @(:frames rec))))
          (is (= "Empty frame" (caption :p))))
        (is (= 3 (:frames-sent (se/get-stats (engine-of :p)))) "the stats count the three returns as before")))))

(deftest recovery-empty-or-unknown-test
  (let [{:keys [rec frame pulse!] :as p} (lane :p)
        a (lit 0.0)
        b (lit 0.5)
        calls-since (fn [n] (subvec @(:calls rec) n))]
    (with-lanes [p]
      (fn []
        (output-runtime/submit-sync! {:p (projector :p)})
        (let [first-session (opened! :p)]
          (is first-session)
          (reset! frame a)
          (at-ms! 10)
          (is (returned! p))
          (is (identical? a (get-in (observation :p) [:last-ok :frame])) "a frame that needs no change goes as it is")
          (testing "a send throws, the empty frame after it is held, then returns"
            (let [entered (promise)
                  release (gate)
                  n (next-send p)]
              (reset! frame b)
              (at-ms! 20)
              (script! p {:fail true})
              (swap! (:script p) assoc (inc n) {:entered entered :gate release})
              (pulse!)
              (is (deref entered 3000 false) "the recovery call is in the driver")
              (let [{:keys [knowledge last-ok]} (observation :p)]
                (is (= :unknown knowledge) "unknown before the recovery can block")
                (is (= {:frame a :at-ns 10000000 :source :normal} last-ok) "the last return is kept, with its time"))
              (is (= "Unknown" (caption :p)))
              (is (nil? (:points (preview/thumbnail-presentation (observation :p) @clock-ns))) "no points")
              (is (contains? (output-errors/snapshot) [:engine :p]) "the error is reported")
              (at-ms! 30)
              (deliver release true)
              (is (eventually #(= {:frame [] :at-ns 30000000 :source :recovery} (:last-ok (observation :p)))))
              (is (= :returned (:knowledge (observation :p))))
              (is (= "Empty (retry)" (caption :p)))
              (is (= 1 (:frames-sent (se/get-stats (engine-of :p)))) "the recovery send isn't counted as a frame sent")))
          (testing "the next normal return repairs it"
            (reset! frame a)
            (at-ms! 40)
            (is (returned! p))
            (is (= {:frame a :at-ns 40000000 :source :normal} (:last-ok (observation :p))))
            (is (eventually #(not (contains? (output-errors/snapshot) [:engine :p]))) "and clears the error"))
          (testing "a send throws and so does the empty frame: unknown, no new success time"
            (reset! frame b)
            (at-ms! 50)
            (let [n (next-send p)
                  since (count @(:calls rec))]
              (script! p {:fail true})
              (swap! (:script p) assoc (inc n) {:fail true})
              (pulse!)
              (is (eventually #(= [[:send 2] [:on-error] [:send 0]] (calls-since since)))
                  (pr-str (calls-since since))))
            (is (= :unknown (:knowledge (observation :p))))
            (is (= {:frame a :at-ns 40000000 :source :normal} (:last-ok (observation :p))))
            (is (= "Unknown" (caption :p)))
            (at-ms! 70)
            (reset! frame a)
            (is (returned! p))
            (is (= {:frame a :at-ns 70000000 :source :normal} (:last-ok (observation :p))) "repaired by the next return"))
          (testing "{:reopen? true}: the session goes with its success, and nothing is sent to recover"
            (let [n (count @(:calls rec))]
              (script! p {:fail {:reopen? true}})
              (pulse!)
              (is (eventually #(nil? (:session-token (observation :p)))))
              (is (= {:knowledge :unknown :last-ok nil} (select-keys (observation :p) [:knowledge :last-ok]))
                  "no empty return made up")
              (is (= "Unknown" (caption :p)))
              (is (eventually #(= :close (first (peek @(:calls rec))))))
              (is (not-any? #{[:on-error] [:send 0]} (calls-since n)) "no :on-error, no empty frame"))
            (let [second-session (eventually #(:session-token (observation :p)))]
              (is second-session "opened again REOPEN_MS later")
              (is (not= first-session second-session))
              (is (= :unknown (:knowledge (observation :p))) "a new session starts unknown")
              (at-ms! 90)
              (is (returned! p))
              (is (= {:frame a :at-ns 90000000 :source :normal} (:last-ok (observation :p))))
              (is (= second-session (:session-token (observation :p)))))))))))

(deftest only-a-return-renews-freshness-test
  (let [ready? (atom true)
        {:keys [frame provided pulse!] :as p} (lane :p {:ready (fn [] @ready?)})
        a (lit 0.0)
        dispatched (atom [])
        dispatch (fn [event] (swap! dispatched conj event) (events/event-handler event))]
    (state/init-state! (-> (domains/build-initial-state)
                           (assoc :projectors {:p (projector :p)})
                           (assoc-in [:backend :streaming :running?] true)
                           history/mark-clean))
    (with-lanes [p]
      (fn []
        (reset! frame a)
        (output-runtime/submit-sync! (:projectors (state/get-raw-state)) dispatch)
        (is (opened! :p))
        (at-ms! 10)
        (is (returned! p))
        (let [first-ok (:last-ok (observation :p))
              engine (engine-of :p)]
          (testing "a tick the driver isn't ready for is skipped: no provider call, the same last return"
            (reset! ready? false)
            (let [calls @provided]
              (pulse!)
              (is (eventually #(= 1 (:frames-skipped (se/get-stats engine)))))
              (is (= calls @provided) "the provider isn't asked for a skipped tick")
              (is (identical? first-ok (:last-ok (observation :p))))))
          (testing "a send held in the driver renews nothing; its return of the same frame does"
            (reset! ready? true)
            (let [entered (promise)
                  release (gate)]
              (script! p {:entered entered :gate release})
              (pulse!)
              (is (deref entered 3000 false))
              (at-ms! 300)
              (is (identical? first-ok (:last-ok (observation :p))) "not while it is in the driver")
              (is (= "Stale" (caption :p)) "the 10 ms return is 290 ms old")
              (deliver release true)
              (is (eventually #(= 300000000 (get-in (observation :p) [:last-ok :at-ns]))))
              (is (identical? a (get-in (observation :p) [:last-ok :frame])) "the same frame")
              (is (= "Driver frame" (caption :p)))))
          (testing "a burst of returns publishes no event of its own and no frame"
            (eventually #(seq @dispatched))
            (#'output-runtime/publish-status!)
            (let [before (state/get-raw-state)
                  events-before (count @dispatched)]
              (dotimes [i 20]
                (at-ms! (+ 310 i))
                (is (returned! p)))
              (#'output-runtime/publish-status!)
              (let [after (state/get-raw-state)
                    events (subvec @dispatched events-before)]
                (is (every? #(= :output/status (:event/type %)) @dispatched) "only the status summary")
                (is (< (count events) 20) "not one per return")
                (is (every? (fn [{:keys [status]}]
                              (every? #(empty? (select-keys % [:frame :last-ok :thumbnail :at-ns])) (vals (:projectors status))))
                            @dispatched)
                    "no frame or return in it")
                (is (= #{:engines :wanted :pending? :projectors} (set (keys (:status (peek @dispatched)))))
                    "the existing summary")
                (is (= (dissoc before :backend) (dissoc after :backend)) "the document is as it was")
                (is (= (:history before) (:history after)) "no undo entry")
                (is (= false (get-in before [:project :dirty?]) (get-in after [:project :dirty?])) "not dirty")))))))))

(deftest old-return-cannot-revive-a-retired-session-test
  (let [{:keys [frame] :as p} (lane :p)
        {q-frame :frame q-rec :rec :as q} (lane :q)
        a (lit 0.0)
        b (lit 0.5)
        projectors {:p (projector :p) :q (projector :q)}
        only-q? (fn [] (let [f (get-in (observation :q) [:last-ok :frame])]
                         (boolean (and f (some #(identical? f %) @(:frames q-rec))))))
        stop! (fn []
                (output-runtime/submit-stop!)
                (eventually #(and (empty? (output-runtime/registry)) (= :off (observation :p)) (= :off (observation :q)))))
        start! (fn []
                 (output-runtime/submit-sync! projectors)
                 (and (opened! :p) (opened! :q)))]
    (reset! q-frame (lit -0.5))
    (with-lanes [p q]
      (fn []
        (is (start!))
        (at-ms! 10)
        (is (returned! q))
        (doseq [[label late] [["a late return" nil] ["a late throw" true]]]
          (testing (str label " of a stopped engine's send")
            (let [entered (promise)
                  release (gate)
                  old (engine-of :p)
                  ^Thread old-thread @(:thread old)]
              (reset! frame b)
              (script! p {:entered entered :gate release :fail late})
              ((:pulse! p))
              (is (deref entered 3000 false) "the send is held in the driver")
              (is (stop!) "Stop gives up on it after its bounded join")
              (is (nil? (se/thumbnail old)) "its run is over")
              (is (= "Not streaming" (caption :p)) "no demand, nothing left: off, not an empty frame")
              (is (start!) "the same ids, new engines")
              (let [current (engine-of :p)
                    owner (select-keys (observation :p) [:run-token :session-token])]
                (is (not (identical? old current)))
                (is (= :unknown (:knowledge (observation :p))) "nothing inherited")
                (at-ms! 20)
                (reset! frame a)
                (is (returned! p))
                (let [now (observation :p)]
                  (deliver release true)
                  (is (eventually #(not (.isAlive old-thread))) "the old thread ends")
                  (is (identical? now (observation :p)) "the late outcome changes nothing")
                  (is (= owner (select-keys (observation :p) [:run-token :session-token])))
                  (is (identical? a (get-in (observation :p) [:last-ok :frame])))
                  (is (nil? (se/thumbnail old)) "and doesn't bring the old run back"))
                (is (identical? current (engine-of :p))))
              (at-ms! 30)
              (is (returned! q))
              (is (only-q?) "the other projector shows only its own frames"))))
        (testing "a reopen within one engine: the old session's frame goes, the new one starts unknown"
          (let [engine (engine-of :p)
                before (:session-token (observation :p))]
            (script! p {:fail {:reopen? true}})
            ((:pulse! p))
            (is (eventually #(nil? (:session-token (observation :p)))))
            (is (nil? (:last-ok (observation :p))))
            (let [after (eventually #(:session-token (observation :p)))]
              (is (and after (not= before after)))
              (is (= :unknown (:knowledge (observation :p))))
              (at-ms! 40)
              (is (returned! p))
              (is (= after (:session-token (observation :p))))
              (is (identical? engine (engine-of :p)) "the same engine"))))
        (testing "drain: both off"
          (is (only-q?) "q's last observation is its own")
          (output-runtime/drain! 2000)
          (is (= :off (observation :p)))
          (is (= :off (observation :q))))))))
