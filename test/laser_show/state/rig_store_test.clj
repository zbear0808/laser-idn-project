(ns laser-show.state.rig-store-test
  "The rig file on disk (plans/2026-11-19-zones/09 §4, §7): read at startup
   whatever it holds, written atomically with a backup, one window at a
   time, and by a writer that keeps only the newest rig. Every test works in
   a temp folder of its own: none reaches the user's ~/LaserShowProjects."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.rig-file :as rig-file]
            [laser-show.state.rig-store :as rig-store])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time LocalDateTime ZoneId]
           [java.util.concurrent Executors ScheduledExecutorService ScheduledThreadPoolExecutor TimeUnit]))

(def ^:dynamic ^File *dir* nil)

(defn- delete-tree! [^File dir]
  (doseq [^File f (reverse (file-seq dir))]
    (.delete f)))

(use-fixtures :each
  (fn [t]
    (let [dir (.toFile (Files/createTempDirectory "rig-store-test" (make-array FileAttribute 0)))]
      (try (binding [*dir* dir] (t))
           (finally (delete-tree! dir))))))

(defn- file ^File [name] (io/file *dir* name))
(defn- rig-path [] (str (file "rig.edn")))
(defn- names [] (set (map #(.getName ^File %) (.listFiles *dir*))))

(defn- doc [laser-name]
  {:rig-file/version 1
   :projectors {:projector-10-0-0-5-1 {:name laser-name :host "10.0.0.5" :service-id 1 :enabled? true
                                       :tags #{:graphics} :safety {:limit [-0.9 -0.8 0.9 0.9] :blocked []}}}
   :virtual-projectors {}
   :rig {:order [:projector-10-0-0-5-1] :middle :split :split-width 0.75}
   :chains {:projector-effects {:projector-10-0-0-5-1 {:items [{:effect-id :rgb-curves :id #uuid "11111111-1111-1111-1111-111111111111"}]}}}
   :zone-groups {:graphics {:id :graphics :name "Graphics" :color "#9B59B6"}}})

(def ^:private utc (ZoneId/of "UTC"))

(defn- ms
  "Epoch ms of a UTC date-time."
  [y mo d h mi s]
  (.toEpochMilli (.toInstant (.atZone (LocalDateTime/of (int y) (int mo) (int d) (int h) (int mi) (int s)) utc))))


;; Reading

(deftest missing-file-is-an-empty-rig-test
  (is (= {:status :missing} (rig-store/read! (rig-path))))
  (is (empty? (names)) "reading creates nothing"))

(deftest round-trip-test
  (is (= #{:at} (set (keys (rig-store/write! (rig-path) (doc "Laser L"))))))
  (let [{:keys [status] read-doc :doc} (rig-store/read! (rig-path))]
    (is (= :ok status))
    (is (= (doc "Laser L") read-doc) "uuids, sets and namespaced keys come back as they were"))
  (is (= #{"rig.edn"} (names)) "no temp file left, and no backup of a file that wasn't there"))

(deftest header-comment-is-skipped-test
  (rig-store/write! (rig-path) (doc "Laser L"))
  (let [lines (str/split-lines (slurp (rig-path)))]
    (is (= [";; Laser Show rig: your projectors, their order, safety zones and calibration."
            ";; Saved automatically. Delete this file to start with an empty rig."]
           (take 2 lines))))
  (testing "a file edited by hand, with comments of its own"
    (spit (rig-path) (str ";; mine\n" (pr-str (doc "Laser M")) "\n;; the end\n"))
    (is (= (doc "Laser M") (:doc (rig-store/read! (rig-path)))))))

(deftest unreadable-file-is-kept-aside-and-the-backup-used-test
  (rig-store/write! (rig-path) (doc "Laser L"))
  (rig-store/write! (rig-path) (doc "Laser L 2"))
  (.setLastModified (file "rig.edn.bak") (ms 2026 11 19 14 2 0))
  (spit (rig-path) "{:rig-file/version 1 :projectors {:p")
  (let [result (rig-store/read! (rig-path) {:now (ms 2026 11 19 14 3 12) :zone utc})]
    (is (= :recovered (:status result)))
    (is (= (doc "Laser L") (:doc result)) "the backup: the file before the last write")
    (is (= (str "Your rig file couldn't be read, so its backup from 19 Nov 14:02 is in use. "
                "The broken file was kept as rig-unreadable-2026-11-19-140312.edn.")
           (:notice result))))
  (is (= "{:rig-file/version 1 :projectors {:p" (slurp (file "rig-unreadable-2026-11-19-140312.edn")))
      "the broken file, as it was")
  (is (= #{"rig-unreadable-2026-11-19-140312.edn" "rig.edn.bak"} (names)) "rig.edn is gone until the next write")
  (testing "a file of the wrong shape is unreadable too, and a second one in the same second gets its own name"
    (spit (rig-path) "[:not :a :rig]")
    (is (= :recovered (:status (rig-store/read! (rig-path) {:now (ms 2026 11 19 14 3 12) :zone utc}))))
    (is (contains? (names) "rig-unreadable-2026-11-19-140312-2.edn"))))

(deftest unreadable-file-without-backup-starts-empty-test
  (spit (rig-path) "{:rig-file/version \"one\"}")
  (let [result (rig-store/read! (rig-path) {:now (ms 2026 11 19 14 3 12) :zone utc})]
    (is (= {:status :reset
            :notice "Your rig file couldn't be read. It was kept as rig-unreadable-2026-11-19-140312.edn, and the rig starts empty."}
           result)))
  (testing "a backup that can't be read either is no help"
    (spit (rig-path) "garbage {")
    (spit (file "rig.edn.bak") "more garbage {")
    (is (= :reset (:status (rig-store/read! (rig-path) {:now (ms 2026 11 19 15 0 0) :zone utc}))))))

(deftest deleted-file-starts-empty-even-with-a-backup-test
  (rig-store/write! (rig-path) (doc "Laser L"))
  (rig-store/write! (rig-path) (doc "Laser L 2"))
  (.delete (file "rig.edn"))
  (is (= {:status :missing} (rig-store/read! (rig-path))) "deleting the file is how you start over")
  (is (= #{"rig.edn.bak"} (names)) "the backup is left alone"))

(deftest newer-version-is-read-only-test
  (spit (rig-path) (pr-str (assoc (doc "Laser L") :rig-file/version 2 :lasers/new-thing #{1})))
  (let [{:keys [status] read-doc :doc} (rig-store/read! (rig-path))]
    (is (= :newer status) "used, but never written: the app keeps it read only")
    (is (= "Laser L" (get-in read-doc [:projectors :projector-10-0-0-5-1 :name]))))
  (testing "an unknown tag from a newer build doesn't make it unreadable"
    (spit (rig-path) "{:rig-file/version 3 :projectors {} :future #laser/thing [1 2]}")
    (is (= :newer (:status (rig-store/read! (rig-path)))))
    (is (contains? (names) "rig.edn") "and it is never kept aside")))


;; Writing

(deftest write-is-atomic-test
  (rig-store/write! (rig-path) (doc "Laser L"))
  (rig-store/write! (rig-path) (doc "Laser L 2"))
  (let [good (slurp (rig-path))
        backup (slurp (file "rig.edn.bak"))]
    (is (= (doc "Laser L") (:doc (rig-store/read! (str (file "rig.edn.bak"))))) "the previous good file is the backup")
    (testing "the temp file can't be written: the file and its backup stay as they were"
      (.mkdirs (file "rig.edn.tmp"))
      (spit (file "rig.edn.tmp/x") "in the way")
      (is (string? (:error (rig-store/write! (rig-path) (doc "Laser L 3")))))
      (is (= good (slurp (rig-path))))
      (is (= backup (slurp (file "rig.edn.bak"))))
      (delete-tree! (file "rig.edn.tmp")))
    (testing "the move fails: the file stays the old one, which is also the backup now, and no temp file is left"
      (with-redefs-fn {#'rig-store/move-into-place! (fn [& _] (throw (java.io.IOException. "No space left on device")))}
        #(is (= {:error "No space left on device"} (rig-store/write! (rig-path) (doc "Laser L 4")))))
      (is (= good (slurp (rig-path))))
      (is (= good (slurp (file "rig.edn.bak"))))
      (is (= #{"rig.edn" "rig.edn.bak"} (names))))
    (testing "a file that can't be read never becomes the backup"
      (spit (rig-path) "broken {")
      (is (contains? (rig-store/write! (rig-path) (doc "Laser L 5")) :at))
      (is (= good (slurp (file "rig.edn.bak"))))
      (is (= (doc "Laser L 5") (:doc (rig-store/read! (rig-path))))))))

(deftest second-lock-is-refused-test
  (let [first-lock (rig-store/try-lock (rig-path))]
    (is (some? first-lock))
    (is (nil? (rig-store/try-lock (rig-path))) "another window: refused")
    (rig-store/release! first-lock)
    (let [again (rig-store/try-lock (rig-path))]
      (is (some? again) "free again once released")
      (rig-store/release! again)))
  (testing "the session's lock"
    (is (true? (rig-store/lock! (rig-path))))
    (try
      (is (nil? (rig-store/try-lock (rig-path))))
      (finally (rig-store/unlock!)))
    (let [again (rig-store/try-lock (rig-path))]
      (is (some? again) "unlock! lets go of it")
      (rig-store/release! again))
    (is (nil? (rig-store/unlock!)) "unlock! with nothing held does nothing")))

(deftest writer-keeps-only-the-newest-test
  (let [results (atom [])
        done (promise)
        on-result (fn [r] (swap! results conj r) (deliver done r))
        last-submit (do (rig-store/submit! (rig-path) (doc "A") on-result)
                        (rig-store/submit! (rig-path) (doc "B") on-result)
                        (System/currentTimeMillis))]
    (rig-store/submit! (rig-path) (doc "C") on-result)
    (is (not (.exists (file "rig.edn"))) "nothing is written at once")
    (let [{:keys [at]} (deref done 5000 nil)]
      (is (some? at))
      (is (<= 450 (- at last-submit)) "half a second after the last submit"))
    (Thread/sleep 700)
    (is (= 1 (count @results)) "one write, for the newest")
    (is (= (doc "C") (:doc (rig-store/read! (rig-path)))))
    (is (not (.exists (file "rig.edn.bak"))) "the superseded ones were never written")))

(deftest flush-writes-the-pending-document-test
  (let [results (atom [])
        t0 (System/currentTimeMillis)]
    (rig-store/submit! (rig-path) (doc "A") #(swap! results conj %))
    (is (true? (rig-store/flush! 2000)))
    (is (= 1 (count @results)) "written, and its result reported, before flush! returns")
    (is (< (- (:at (first @results)) t0) 450) "without waiting for the half second")
    (is (= (doc "A") (:doc (rig-store/read! (rig-path)))))
    (Thread/sleep 600)
    (is (= 1 (count @results)) "and only once")
    (is (true? (rig-store/flush! 2000)) "nothing pending: at once")))

(defn- gated-write
  "A stand-in for rig-store/write! that delivers entered once the writer has
   taken its rig and started, then waits for gate before writing it."
  [entered gate]
  (let [write! rig-store/write!]
    (fn [path doc]
      (deliver entered true)
      @gate
      (write! path doc))))

(deftest flush-waits-for-an-in-flight-write-test
  ;; Before: the writer took the rig before its I/O, and flush! said true at
  ;; once with nothing left in the slot, while that write was still running
  (let [entered (promise)
        gate (promise)
        results (atom [])]
    (with-redefs [rig-store/write! (gated-write entered gate)]
      (try
        (rig-store/submit! (rig-path) (doc "A") #(swap! results conj %) 0)
        (is (true? (deref entered 2000 false)) "the writer has taken A and is writing it")
        (testing "a wait shorter than the write: false, at its end"
          (let [t0 (System/currentTimeMillis)]
            (is (false? (rig-store/flush! 200)))
            (is (<= 200 (- (System/currentTimeMillis) t0))))
          (is (empty? @results))
          (is (not (.exists (file "rig.edn")))))
        (testing "flush! waits for the write running, and its result"
          (let [flushed (future (rig-store/flush! 5000))]
            (Thread/sleep 100)
            (is (not (realized? flushed)) "not while A is being written")
            (deliver gate true)
            (is (true? (deref flushed 5000 :timeout)))
            (is (= 1 (count @results)) "A's result was reported before flush! returned")
            (is (= (doc "A") (:doc (rig-store/read! (rig-path)))))))
        (finally
          (deliver gate true))))))

(deftest superseding-rig-carries-the-waiting-then-test
  ;; Follow-up B9: Exit's Try again submits its rig with what waits for it;
  ;; before, an autosave that took its place before the writer took it
  ;; dropped that, so Exit had to be pressed again
  (let [results (atom [])
        report (fn [label] #(swap! results conj [label (:then %) (contains? % :at)]))
        while-busy (fn [f]
                     (let [entered (promise)
                           gate (promise)]
                       (reset! results [])
                       (with-redefs [rig-store/write! (gated-write entered gate)]
                         (try
                           (rig-store/submit! (rig-path) (doc "A") (report :a) 0)
                           (is (true? (deref entered 2000 false)) "the writer is writing A")
                           (f)
                           (deliver gate true)
                           (is (true? (rig-store/flush! 5000)))
                           (finally
                             (deliver gate true))))))]
    (testing "a rig with no then of its own takes the waiting one over"
      (while-busy (fn []
                    (rig-store/submit! (rig-path) (doc "B") (report :b) 0 :exit)
                    (rig-store/submit! (rig-path) (doc "C") (report :c) 0)))
      (is (= [[:a nil true] [:c :exit true]] @results) "B was never written nor reported; C carries its then")
      (is (= (doc "C") (:doc (rig-store/read! (rig-path))))))
    (testing "a rig with a then of its own keeps its own: there is only ever one"
      (while-busy (fn []
                    (rig-store/submit! (rig-path) (doc "B") (report :b) 0 :first-exit)
                    (rig-store/submit! (rig-path) (doc "C") (report :c) 0 :second-exit)))
      (is (= [[:a nil true] [:c :second-exit true]] @results)))
    (testing "a rig the writer has taken already reports its own then; the next one carries none"
      (let [entered (promise)
            gate (promise)]
        (reset! results [])
        (with-redefs [rig-store/write! (gated-write entered gate)]
          (try
            (rig-store/submit! (rig-path) (doc "B") (report :b) 0 :exit)
            (is (true? (deref entered 2000 false)) "the writer is writing B")
            (rig-store/submit! (rig-path) (doc "C") (report :c) 0)
            (deliver gate true)
            (is (true? (rig-store/flush! 5000)))
            (finally
              (deliver gate true)))))
      (is (= [[:b :exit true] [:c nil true]] @results)))
    (testing "the rig that carries it fails: the then comes with the failure, once"
      (let [entered (promise)
            gate (promise)]
        (reset! results [])
        (with-redefs [rig-store/write! (fn [_ _] (deliver entered true) @gate {:error "No space left on device"})]
          (try
            (rig-store/submit! (rig-path) (doc "A") (report :a) 0)
            (is (true? (deref entered 2000 false)))
            (rig-store/submit! (rig-path) (doc "B") (report :b) 0 :exit)
            (rig-store/submit! (rig-path) (doc "C") (report :c) 0)
            (deliver gate true)
            (is (true? (rig-store/flush! 5000)))
            (finally
              (deliver gate true)))))
      (is (= [[:a nil false] [:c :exit false]] @results)))))

(defn- inline-writer
  "A rig writer that runs a task due at once inside schedule, before submit!
   goes on: as when the writer's thread starts it straight away. Other
   delays, and what a task due at once returns (done), are delegate's."
  ^ScheduledThreadPoolExecutor [^ScheduledExecutorService delegate]
  (proxy [ScheduledThreadPoolExecutor] [1]
    (schedule [task delay unit]
      (if (zero? delay)
        (do (.run ^Runnable task)
            (.schedule delegate ^Runnable (fn []) 0 TimeUnit/MILLISECONDS))
        (.schedule delegate ^Runnable task (long delay) ^TimeUnit unit)))))

(deftest zero-delay-submit-always-writes-its-document-test
  ;; Before: submit! scheduled the write before keeping the rig, so a write
  ;; due at once (Retry, Exit's Try again) that started straight away found
  ;; the rig before it, or none: that one was written and reported, the new
  ;; one never
  (let [delegate (Executors/newSingleThreadScheduledExecutor)
        inline (inline-writer delegate)
        results (atom [])
        report (fn [label] #(swap! results conj [label %]))]
    (try
      (with-redefs-fn {#'rig-store/!writer (delay inline)}
        (fn []
          (testing "nothing pending: the rig submitted is written"
            (rig-store/submit! (rig-path) (doc "B") (report :b) 0)
            (is (= (doc "B") (:doc (rig-store/read! (rig-path)))))
            (is (= [:b] (map first @results))))
          (testing "one pending, superseded by one due at once: only the new one is written and reported"
            (reset! results [])
            (rig-store/submit! (rig-path) (doc "C") (report :c) 3600000)
            (rig-store/submit! (rig-path) (doc "D") (report :d) 0)
            (is (= (doc "D") (:doc (rig-store/read! (rig-path)))))
            (is (= [:d] (map first @results)) "C was superseded: never written, never reported")
            (is (true? (rig-store/flush! 2000)) "nothing is left to write")
            (is (= [:d] (map first @results))))))
      (finally
        (.shutdownNow delegate)
        (.shutdownNow inline)))))

(deftest restored-member-definition-survives-rig-file-round-trip-test
  ;; Reactivity plan 23: what the Undo of a group's last member's removal
  ;; asks to write brings the group back from disk
  (let [balcony {:id :group-balcony :name "Balcony" :description "House" :color "#2468AC"}
        handle (fn [state event] (events/handle-event-pipeline (assoc event :state state :time 1000)))
        s0 (-> (domains/build-initial-state)
               (assoc :projectors {:p {:name "Balcony L" :host "10.0.0.7" :port 7255 :enabled? false
                                       :zone-groups [:group-balcony] :stable-id [:host "10.0.0.7" 0]}})
               (update :rig-file assoc :path (rig-path) :group-copy {:group-balcony balcony})
               (history/mark-clean))
        removed (handle s0 {:event/type :projectors/remove :projector-id :p})
        undone (handle (:state removed) {:event/type :edit/undo})
        redone (handle (:state undone) {:event/type :edit/redo})
        new-show-from-disk (fn [request]
                             (is (contains? (rig-store/write! (rig-path) (:doc request)) :at))
                             (let [{:keys [status] read-doc :doc} (rig-store/read! (rig-path))]
                               (is (= :ok status))
                               (persistent/new-project-state (rig-file/apply-rig-document (domains/build-initial-state) read-doc))))]
    (is (= balcony (get-in (new-show-from-disk (:rig/write undone)) [:zone-groups :group-balcony]))
        "Undo's request, written and read: New brings A exactly")
    (is (not (contains? (:zone-groups (new-show-from-disk (:rig/write redone))) :group-balcony))
        "Redo's: no Balcony")))


;; Where it lives

(deftest default-rig-file-test
  (let [home-rig (str (System/getProperty "user.home") File/separator "LaserShowProjects")]
    (is (not (str/starts-with? (str (.getAbsoluteFile (io/file (persistent/default-rig-file)))) home-rig))
        "under the test runner, laser-show.rig-file is a temp folder: no test reaches the user's rig")
    (let [before (System/getProperty "laser-show.rig-file")]
      (try
        (System/setProperty "laser-show.rig-file" "/elsewhere/rig.edn")
        (is (= "/elsewhere/rig.edn" (persistent/default-rig-file)) "the JVM property wins")
        (System/clearProperty "laser-show.rig-file")
        (is (= (str persistent/default-projects-dir "/rig.edn") (persistent/default-rig-file))
            "else ~/LaserShowProjects/rig.edn, beside the shows (Q-ZR-1)")
        (finally
          (if before
            (System/setProperty "laser-show.rig-file" before)
            (System/clearProperty "laser-show.rig-file")))))))
