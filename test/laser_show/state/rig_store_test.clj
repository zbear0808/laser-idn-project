(ns laser-show.state.rig-store-test
  "The rig file on disk (plans/2026-11-19-zones/09 §4, §7): read at startup
   whatever it holds, written atomically with a backup, one window at a
   time, and by a writer that keeps only the newest rig. Every test works in
   a temp folder of its own: none reaches the user's ~/LaserShowProjects."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.rig-store :as rig-store])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time LocalDateTime ZoneId]))

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
