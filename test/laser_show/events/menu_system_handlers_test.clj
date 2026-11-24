(ns laser-show.events.menu-system-handlers-test
  "Tests for File > Exit and Help menu handlers that return non-:state effects,
   and the rig file's part of Exit and shutdown."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [laser-show.app :as app]
   [laser-show.events.core :as events]
   [laser-show.events.handlers.menu :as menu]
   [laser-show.state.core :as state]
   [laser-show.state.domains :as domains]
   [laser-show.state.history :as history]
   [laser-show.state.rig-store :as rig-store])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :each (fn [f]
                      (state/init-state! (domains/build-initial-state))
                      (events/set-exit-fn! nil)
                      (try (f) (finally (events/set-exit-fn! nil)))))

(defn- with-dev-mode [f]
  (let [old (System/getProperty "laser-show.dev")]
    (System/setProperty "laser-show.dev" "true")
    (try (f)
         (finally (if old
                    (System/setProperty "laser-show.dev" old)
                    (System/clearProperty "laser-show.dev"))))))

(deftest exit-handler-test
  (is (= {:system/exit true}
         (menu/handle {:event/type :file/exit :state (state/get-raw-state)}))))

(deftest exit-effect-is-registered-test
  (testing "the wired pipeline knows :system/exit; with no application running it is ignored"
    (is (nil? (events/event-handler {:event/type :file/exit})))
    (with-dev-mode
      #(is (nil? (events/event-handler {:event/type :file/exit}))))))

(deftest exit-effect-calls-the-registered-exit-fn-test
  (let [exits (atom 0)]
    (events/set-exit-fn! #(swap! exits inc))
    (events/event-handler {:event/type :file/exit})
    (is (= 1 @exits))
    (testing "a dirty project asks first and does not exit"
      (state/swap-state! assoc-in [:project :dirty?] true)
      (events/event-handler {:event/type :file/exit})
      (is (= 1 @exits))
      (is (true? (get-in (state/get-raw-state) [:ui :dialogs :unsaved-confirm :open?]))))
    (testing "Don't Save then exits"
      (events/event-handler {:event/type :file/confirm-discard})
      (is (= 2 @exits)))))


;; The rig file at Exit and shutdown (reactivity plan 21)


(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "menu-system-rig" (make-array FileAttribute 0))))

(defn- delete-tree! [^File dir]
  (doseq [^File f (reverse (file-seq dir))]
    (.delete f)))

(defn- rigged
  "A saved show on a rig of one laser, with this window's rig file at path."
  [path]
  (-> (domains/build-initial-state)
      (assoc :projectors {:p1 {:name "Laser L" :host "10.0.0.1" :port 7255 :enabled? true :zone-groups []
                               :stable-id [:host "10.0.0.1" 0]}})
      (assoc-in [:rig-file :path] path)
      (assoc-in [:project :document-id] (random-uuid))
      (history/mark-clean)))

(defn- confirm-dialog []
  (get-in (state/get-raw-state) [:ui :dialogs :unsaved-confirm]))

(defn- rename-cue!
  "A show edit through the pipeline: cue [2 0] named text."
  [text]
  (events/event-handler {:event/type :cue-chain/set-name :col 2 :row 0 :name text}))

(defn- lock-free?
  "Could another window take path's rig lock now? (It is given back at once.)"
  [path]
  (if-let [k (rig-store/try-lock path)]
    (do (rig-store/release! k) true)
    false))

(defn- eventually
  "Is (pred) true within 2 s?"
  [pred]
  (loop [n 0]
    (cond (pred) true
          (< n 200) (do (Thread/sleep 10) (recur (inc n)))
          :else false)))

(defn- gated-write
  "A stand-in for rig-store/write! that delivers entered once the writer has
   taken its rig and started, then waits for gate before writing it."
  [entered gate]
  (let [write! rig-store/write!]
    (fn [path doc]
      (deliver entered true)
      @gate
      (write! path doc))))

(deftest retry-success-after-a-new-edit-asks-again-test
  ;; Before: once Try again's write was done, its Exit came back forced and
  ;; quit, with an edit made while the rig was being written
  (let [path "/tmp/never-written/rig.edn"
        failed (update (rigged path) :rig-file assoc :status :write-failed :error "No space left on device")
        asked (:state (menu/handle {:event/type :file/exit :state failed}))
        retried (menu/handle {:event/type :file/confirm-retry :state asked})
        exit-then (get-in retried [:dispatch :then])
        written {:event/type :rig/written :path path :seq 1 :at 5000 :then exit-then}
        exits (atom 0)]
    (events/set-exit-fn! #(swap! exits inc))
    (testing "Try again's Exit carries the permission it was given"
      (is (= :rig (get-in asked [:ui :dialogs :unsaved-confirm :reason])))
      (is (= {:event/type :file/exit :force? true :rig-force? true
              :authorized {:document-id (get-in failed [:project :document-id]) :dirty? false}}
             exit-then)))
    (testing "an edit lands while the rig is written: once it is, the show's question, and no exit"
      (state/reset-state! (:state retried))
      (rename-cue! "While the rig was written")
      (events/event-handler written)
      (is (zero? @exits))
      (is (= :saved (get-in (state/get-raw-state) [:rig-file :status])))
      (is (= {:open? true :pending {:event/type :file/exit}} (select-keys (confirm-dialog) [:open? :pending])))
      (is (nil? (:reason (confirm-dialog))) "the show's question, not the rig's")
      (testing "; Don't Save then quits"
        (events/event-handler {:event/type :file/confirm-discard})
        (is (= 1 @exits))))
    (testing "nothing changed meanwhile: it quits, once"
      (reset! exits 0)
      (state/reset-state! (:state retried))
      (events/event-handler written)
      (is (= 1 @exits))
      (is (not (:open? (confirm-dialog)))))))

(deftest exit-waits-for-a-rig-write-in-flight-test
  ;; 00-overview's "19 shutdown → 21 rig writer": a write the writer has
  ;; taken is held at a gate; Exit is asked for; then the gate opens. Before:
  ;; Exit quit at once, and shutdown let go of the lock mid-write
  (let [dir (temp-dir)
        path (str (io/file dir "rig.edn"))
        exits (atom [])
        exit-while-writing! (fn [edit-text]
                              (let [entered (promise)
                                    gate (promise)]
                                (with-redefs [rig-store/write! (gated-write entered gate)]
                                  (try
                                    (state/reset-state! (rigged path))
                                    (events/event-handler {:event/type :rig/retry-write})
                                    (is (true? (deref entered 2000 false)) "the writer has taken the rig")
                                    (events/event-handler {:event/type :file/exit})
                                    (is (empty? @exits) "Exit waits for the write's outcome")
                                    (is (false? (lock-free? path)) "the lock stays held while it runs")
                                    (when edit-text (rename-cue! edit-text))
                                    (deliver gate true)
                                    (is (eventually #(= :saved (get-in (state/get-raw-state) [:rig-file :status]))))
                                    (finally
                                      (deliver gate true))))))]
    (events/set-exit-fn! #(do (swap! exits conj {:state (state/get-raw-state) :lock-free? (lock-free? path)})
                              (#'app/stop-rig-file!)))
    (try
      (is (true? (rig-store/lock! path)) "this window's lock, as start-up takes it")
      (testing "an edit made while the rig was written is asked about before quitting"
        (exit-while-writing! "While the rig was written")
        (is (eventually #(:open? (confirm-dialog))))
        (is (= {:event/type :file/exit} (:pending (confirm-dialog))))
        (is (empty? @exits))
        (is (false? (lock-free? path)) "the lock is still this window's"))
      (testing "nothing changed: Exit quits once the write is done, and only then is the lock let go"
        (exit-while-writing! nil)
        (is (eventually #(= 1 (count @exits))))
        (let [{at-exit :state :keys [lock-free?]} (first @exits)]
          (is (= :saved (get-in at-exit [:rig-file :status])) "the rig was written before the exit")
          (is (false? lock-free?) "held until shutdown"))
        (is (eventually #(lock-free? path)) "let go by shutdown, after the write")
        (is (= 1 (count @exits))))
      (finally
        (rig-store/flush! 2000)
        (rig-store/unlock!)
        (delete-tree! dir)))))

(deftest shutdown-lets-go-of-the-rig-lock-after-the-write-test
  ;; app/stop!'s rig step: before, flush! didn't wait for a write already
  ;; running, so the lock was let go while the file was still being written
  (let [dir (temp-dir)
        path (str (io/file dir "rig.edn"))
        doc {:rig-file/version 1 :projectors {} :virtual-projectors {} :rig {:order []}
             :chains {:projector-effects {}} :zone-groups {}}
        gated! (fn [entered]
                 (is (true? (rig-store/lock! path)))
                 (rig-store/submit! path doc (fn [_]) 0)
                 (is (true? (deref entered 2000 false)) "the writer has taken the rig"))]
    (try
      (testing "the lock is held until the write running is done"
        (let [entered (promise)
              gate (promise)]
          (with-redefs [rig-store/write! (gated-write entered gate)]
            (try
              (gated! entered)
              (let [stopped (future (#'app/stop-rig-file!))]
                (Thread/sleep 100)
                (is (not (realized? stopped)) "shutdown waits for the writer")
                (is (false? (lock-free? path)))
                (deliver gate true)
                (is (not= :timeout (deref stopped 5000 :timeout)))
                (is (.exists (io/file path)) "written")
                (is (lock-free? path) "then let go"))
              (finally
                (deliver gate true))))))
      (finally
        (rig-store/flush! 2000)
        (rig-store/unlock!)
        (delete-tree! dir)))))

(deftest shutdown-wait-for-the-rig-is-bounded-test
  ;; A write that outlasts rig-store/flush-ms doesn't hold shutdown up:
  ;; it warns and lets go of the lock, as before
  (let [dir (temp-dir)
        path (str (io/file dir "rig.edn"))
        entered (promise)
        gate (promise)]
    (with-redefs [rig-store/write! (gated-write entered gate)
                  rig-store/flush-ms 200]
      (try
        (is (true? (rig-store/lock! path)))
        (rig-store/submit! path {:rig-file/version 1} (fn [_]) 0)
        (is (true? (deref entered 2000 false)) "the writer has taken the rig")
        (let [t0 (System/currentTimeMillis)]
          (#'app/stop-rig-file!)
          (is (<= 200 (- (System/currentTimeMillis) t0) 2000) "it waited, at most flush-ms"))
        (is (lock-free? path))
        (finally
          (deliver gate true)
          (rig-store/flush! 2000)
          (rig-store/unlock!)
          (delete-tree! dir))))))

(deftest exit-asks-about-a-rig-write-that-failed-while-it-waited-test
  ;; The write Exit waited for fails: the usual write-failed path (status
  ;; line, notice), then Exit's rig question instead of quitting
  (let [dir (temp-dir)
        path (str (io/file dir "rig.edn"))
        entered (promise)
        gate (promise)
        exits (atom 0)]
    (events/set-exit-fn! #(swap! exits inc))
    (with-redefs [rig-store/write! (fn [_path _doc]
                                     (deliver entered true)
                                     @gate
                                     {:error "No space left on device"})]
      (try
        (state/reset-state! (rigged path))
        (events/event-handler {:event/type :rig/retry-write})
        (is (true? (deref entered 2000 false)) "the writer has taken the rig")
        (events/event-handler {:event/type :file/exit})
        (deliver gate true)
        (is (eventually #(= :rig (:reason (confirm-dialog)))) "the rig's question")
        (let [s (state/get-raw-state)]
          (is (= {:status :write-failed :error "No space left on device"}
                 (select-keys (:rig-file s) [:status :error])))
          (is (= "Rig not saved: No space left on device" (get-in s [:ui :notice :text])))
          (is (= "No space left on device" (:error (confirm-dialog)))))
        (is (zero? @exits))
        (testing "; Quit anyway quits"
          (events/event-handler {:event/type :file/confirm-discard})
          (is (= 1 @exits)))
        (finally
          (deliver gate true)
          (rig-store/flush! 2000)
          (delete-tree! dir))))))

(deftest documentation-handler-test
  (let [result (menu/handle {:event/type :help/documentation :state (state/get-raw-state)})]
    (is (= #{:system/open-url} (set (keys result))))
    (is (re-find #"^https://" (:system/open-url result)))))

(deftest reload-handlers-return-effects-only-test
  (testing "no :state, so the reload can't be overwritten by a stale snapshot"
    (is (= {:dev/reload :styles}
           (menu/handle {:event/type :help/reload-styles :state (state/get-raw-state)})))
    (is (= {:dev/reload :app}
           (menu/handle {:event/type :help/reload-app :state (state/get-raw-state)})))))

(deftest reload-styles-pipeline-keeps-trigger-test
  (testing "the reload trigger bumped by the effect survives the event"
    (events/event-handler {:event/type :help/reload-styles})
    ;; tools.namespace comes in with the test runner; without it the effect
    ;; only logs, and the trigger stays unset.
    (if (try (requiring-resolve 'clojure.tools.namespace.find/find-namespaces-in-dir)
             (catch Throwable _ nil))
      (is (= 1 (get-in (state/get-raw-state) [:styles :reload-trigger])))
      (is (nil? (get-in (state/get-raw-state) [:styles :reload-trigger]))))))

(deftest dev-reloaded-test
  (let [s0 (state/get-raw-state)
        reloaded #(:state (events/handle-event-pipeline {:event/type :dev/reloaded :kind %1 :state %2 :time 1234}))
        unchanged-history-and-dirty (fn [s]
                                      (is (= (:history s0) (:history s)) "no undo entry")
                                      (is (= (get-in s0 [:project :dirty?]) (get-in s [:project :dirty?])) "not dirty")
                                      (is (not (get-in s [:project :dirty?]))))]
    (testing ":styles bumps the reload trigger"
      (let [s (reloaded :styles s0)]
        (is (= 1 (get-in s [:styles :reload-trigger])))
        (is (= 2 (get-in (reloaded :styles s) [:styles :reload-trigger])))
        (unchanged-history-and-dirty s)))
    (testing ":app sets the reload timestamp to the event's :time"
      (let [s (reloaded :app s0)]
        (is (= 1234 (get-in s [:ui :last-reload-timestamp])))
        (is (= (:styles s0) (:styles s)))
        (unchanged-history-and-dirty s)))
    (testing "through the wired pipeline: :time is the co-effect's"
      (let [t0 (System/currentTimeMillis)]
        (events/event-handler {:event/type :dev/reloaded :kind :app})
        (is (<= t0 (get-in (state/get-raw-state) [:ui :last-reload-timestamp]) (System/currentTimeMillis)))))))
