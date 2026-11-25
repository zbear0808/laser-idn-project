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
   [laser-show.state.persistent :as persistent]
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

(deftest try-again-superseded-by-an-autosave-still-quits-test
  ;; Follow-up B9: Try again's write waited behind one the writer was busy
  ;; with, and a rig edit's autosave took its place before the writer took
  ;; it. Before, the Exit waiting for the write went with it: the newer rig
  ;; was written, and the app stayed open
  (let [dir (temp-dir)
        path (str (io/file dir "rig.edn"))
        exits (atom 0)
        entered (promise)
        gate (promise)]
    (events/set-exit-fn! #(swap! exits inc))
    (with-redefs [rig-store/write! (gated-write entered gate)]
      (try
        (state/reset-state! (update (rigged path) :rig-file assoc :status :write-failed :error "No space left on device"))
        (events/event-handler {:event/type :rig/retry-write})
        (is (true? (deref entered 2000 false)) "the writer is busy with a write")
        (events/event-handler {:event/type :file/exit})
        (is (= :rig (:reason (confirm-dialog))) "the rig's question")
        (events/event-handler {:event/type :file/confirm-retry})
        (events/event-handler {:event/type :rig/set-middle :middle :both})
        (deliver gate true)
        (is (eventually #(= 1 @exits)) "the newer rig is written, and Exit carries on")
        (is (= :both (get-in (rig-store/read! path) [:doc :rig :middle])) "with the edit in the file")
        (is (= :saved (get-in (state/get-raw-state) [:rig-file :status])))
        (Thread/sleep 600)
        (is (= 1 @exits) "once")
        (finally
          (deliver gate true)
          (rig-store/flush! 2000)
          (delete-tree! dir))))))

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


;; An Exit asked about again while the dialog asks something else
;; (follow-up B12b)


(defn- asking?
  "Is the dialog open, asking about pending?"
  [pending]
  (= {:open? true :pending pending} (select-keys (confirm-dialog) [:open? :pending])))

(defn- waiting
  "What waits behind the dialog's question, as the dialog parks it."
  []
  (mapv #(select-keys % [:event/type :file-path]) (:queued (confirm-dialog))))

(deftest an-exit-asked-again-while-the-dialog-asks-waits-for-the-answer-test
  ;; Follow-up B12b: an Exit back from its wait for the rig, whose
  ;; permission no longer covered the show (an edit made meanwhile), was
  ;; dropped while the dialog asked something else
  (let [dir (temp-dir)
        a-path (str (io/file dir "A.zip"))
        exits (atom 0)
        clean (assoc-in (rigged nil) [:project :current-file] a-path)
        doc (get-in clean [:project :document-id])
        ;; What Exit's wait for the rig sends back (wait-for-the-rig), Exit
        ;; pressed on the clean show
        back {:event/type :file/exit :force? true :rig-waited? true :authorized {:document-id doc :dirty? false}}
        asked! (fn [question]
                 (state/reset-state! clean)
                 (rename-cue! "Edited while Exit waited")
                 (events/event-handler question)
                 (is (asking? (select-keys question [:event/type])))
                 (events/event-handler back))]
    (events/set-exit-fn! #(swap! exits inc))
    (try
      (testing "back while New's question is open: it waits behind it, once"
        (asked! {:event/type :file/new-project})
        (is (= [{:event/type :file/exit}] (waiting)))
        (events/event-handler back)
        (is (= [{:event/type :file/exit}] (waiting)) "once")
        (is (asking? {:event/type :file/new-project}) "the question open stays")
        (is (zero? @exits))
        (testing "; Cancel: then Exit's own question"
          (events/event-handler {:event/type :file/confirm-cancel})
          (is (asking? {:event/type :file/exit}))
          (is (empty? (waiting)))
          (is (zero? @exits))
          (testing "; Don't Save quits, once"
            (events/event-handler {:event/type :file/confirm-discard})
            (is (= 1 @exits))
            (is (not (:open? (confirm-dialog)))))))
      (testing "Don't Save or Save on New's question: New replaces the show, and the Exit that waited for it does nothing"
        (doseq [answer [:file/confirm-discard :file/confirm-save]]
          (reset! exits 0)
          (asked! {:event/type :file/new-project})
          (events/event-handler {:event/type answer})
          (is (zero? @exits) (name answer))
          (is (not= doc (get-in (state/get-raw-state) [:project :document-id])) "a new show")
          (is (not (:open? (confirm-dialog))))))
      (testing "back while Open's question is open"
        (asked! {:event/type :file/open})
        (is (= [{:event/type :file/exit}] (waiting)))
        (let [asking (state/get-raw-state)]
          (testing "; Don't Save: the chooser first, then Exit's question, the edit still unsaved"
            (reset! exits 0)
            (let [{s :state [open exit] :dispatch-n} (events/handle-event-pipeline {:event/type :file/confirm-discard :state asking})]
              (is (= {:event/type :file/open :force? true} open) "the answer's own continuation")
              (is (contains? (events/handle-event-pipeline (assoc open :state s)) :fx/show-file-chooser))
              (is (= :file/exit (:event/type exit)) "then the Exit")
              (state/reset-state! s)
              (events/event-handler exit)
              (is (asking? {:event/type :file/exit}))
              (is (zero? @exits))))
          (testing "; Save: once the show is saved, the Exit quits, once"
            (reset! exits 0)
            (let [effects (events/handle-event-pipeline {:event/type :file/confirm-save :state asking})
                  {:keys [file-path files on-saved]} (:project/save effects)
                  [exit] (:dispatch-n effects)]
              (is (= [:project/save :dispatch-n] (filterv #{:project/save :dispatch-n} (keys effects)))
                  "the Exit after the save")
              (is (= {:event/type :file/open :force? true} on-saved))
              ;; The save, as :project/save runs it, up to its continuation (the chooser)
              (state/reset-state! (:state effects))
              (is (empty? (waiting)) "the answer took it")
              (is (true? (persistent/write-project! file-path files)))
              (events/event-handler {:event/type :project/mark-clean :file-path file-path})
              (events/event-handler exit)
              (is (= 1 @exits))))))
      (testing "back while Exit's own question is open: that question is its, nothing waits"
        (reset! exits 0)
        (asked! {:event/type :file/exit})
        (is (empty? (waiting)))
        (events/event-handler {:event/type :file/confirm-discard})
        (is (= 1 @exits) "Don't Save quits, once"))
      (testing "a queued Exit back after New or Open replaced its show does nothing"
        (asked! {:event/type :file/new-project})
        (let [[queued] (:queued (confirm-dialog))]
          (is (= doc (:document-id queued)) "it waits for its show")
          (doseq [[label replace!] [["File > New" #(events/event-handler {:event/type :file/new-project})]
                                    ["File > Open" #(events/event-handler {:event/type :file/project-loaded :file-path a-path
                                                                            :contents (persistent/project-files clean)})]]]
            (testing label
              (state/reset-state! clean)
              (replace!)
              (let [replaced (state/get-raw-state)]
                (is (not= doc (get-in replaced [:project :document-id])))
                (is (= {} (events/handle-event-pipeline (assoc queued :state replaced))) "no state, no question")
                (reset! exits 0)
                (events/event-handler queued)
                (is (= [replaced 0] [(state/get-raw-state) @exits])))))
          (testing "; back to its own show, with nothing unsaved by then, it quits"
            (state/reset-state! clean)
            (reset! exits 0)
            (events/event-handler queued)
            (is (= 1 @exits)))))
      (testing "New, Open or Exit pressed while the dialog is open is still ignored"
        (asked! {:event/type :file/new-project})
        (let [before (confirm-dialog)]
          (reset! exits 0)
          (doseq [pressed [{:event/type :file/new-project} {:event/type :file/open} {:event/type :file/exit}]]
            (events/event-handler pressed)
            (is (= before (confirm-dialog)) (str (:event/type pressed))))
          (is (zero? @exits))))
      (finally
        (delete-tree! dir)))))

(deftest an-exit-back-from-its-rig-wait-asks-the-rig-s-question-after-the-one-open-test
  ;; Follow-up B12b: the write Exit waited for failed while the dialog asked
  ;; something else: the rig's question was dropped
  (let [exits (atom 0)
        s0 (-> (rigged nil)
               (update :rig-file assoc :status :write-failed :error "No space left on device"))
        doc (get-in s0 [:project :document-id])
        ;; Exit's question had been answered Don't Save: its permission
        ;; covers the edit
        edited (-> s0
                   (assoc-in [:chains :cue-chains [2 0]] {:name "Edited" :items []})
                   (assoc-in [:project :dirty?] true))
        back {:event/type :file/exit :force? true :rig-waited? true :authorized {:document-id doc :dirty? true}}]
    (events/set-exit-fn! #(swap! exits inc))
    (state/reset-state! edited)
    (events/event-handler {:event/type :file/new-project})
    (is (asking? {:event/type :file/new-project}))
    (events/event-handler back)
    (is (= [{:event/type :file/exit}] (waiting)) "the rig's question waits too")
    (events/event-handler {:event/type :file/confirm-cancel})
    (is (asking? {:event/type :file/exit}) "a permission that throws nothing away: the show's question first")
    (events/event-handler {:event/type :file/confirm-discard})
    (is (= :rig (:reason (confirm-dialog))) "then the rig's")
    (is (zero? @exits))
    (events/event-handler {:event/type :file/confirm-discard})
    (is (= 1 @exits) "Quit anyway quits, once")))

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
