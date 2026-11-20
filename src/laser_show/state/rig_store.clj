(ns laser-show.state.rig-store
  "The rig file on disk (plans/2026-11-19-zones/09 §4, §7), I/O at the edge:
   state.rig-file is the pure side.

   - read! at startup: whatever the file holds, the app starts. One that
     can't be read is kept aside as rig-unreadable-<time>.edn and the backup
     is used, or an empty rig, with a notice that says which.
   - write!: the temp file is written and forced to disk, the file before it
     becomes rig.edn.bak (only one that reads), then the temp file is moved
     onto rig.edn in one step, so a crash never leaves half a file.
   - lock!: one window at a time writes the rig (rig.edn.lock, held for the
     session; the OS lets go of it if the app dies). Another window uses the
     rig read only.
   - The writer: one daemon thread, rig-writer. submit! keeps only the newest
     rig and writes it write-delay-ms after the last submit; flush! writes the
     pending one now (app/stop!, and a shutdown hook)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [laser-show.state.rig-file :as rig-file]
            [laser-show.state.serialization :as ser])
  (:import [java.io File]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel FileLock OverlappingFileLockException]
           [java.nio.file AccessDeniedException AtomicMoveNotSupportedException CopyOption FileSystemException
            Files NoSuchFileException OpenOption StandardCopyOption StandardOpenOption]
           [java.time Instant ZoneId]
           [java.time.format DateTimeFormatter]
           [java.util Locale]
           [java.util.concurrent ExecutorService Future ScheduledExecutorService ScheduledFuture
            ThreadFactory TimeUnit TimeoutException]))


;; Files beside rig.edn

(defn- sibling
  "rig.edn's file with suffix: rig.edn.tmp, rig.edn.bak, rig.edn.lock."
  ^File [path suffix]
  (io/file (str path suffix)))

(def header
  "The two lines on top of every rig file."
  (str ";; Laser Show rig: your projectors, their order, safety zones and calibration.\n"
       ";; Saved automatically. Delete this file to start with an empty rig.\n"))

(defn- parse
  "The rig document in file, or nil when it can't be read or isn't shaped
   like one (rig-file/valid-rig-document?). A tag this build doesn't know (a
   newer build's) is read as a tagged literal, not as an error."
  [^File file]
  (try
    (let [doc (edn/read-string {:default tagged-literal} (slurp file))]
      (when (rig-file/valid-rig-document? doc)
        doc))
    (catch Exception e
      (log/warn "The rig file" (str file) "can't be read:" (.getMessage e))
      nil)))

(defn- reason
  "Why a write failed, in a few words for the status line."
  [^Throwable e]
  (let [file-name #(or (some-> (.getFile ^FileSystemException e) io/file .getName) "the rig file")]
    (cond
      (instance? AccessDeniedException e) (str "no permission to write " (file-name))
      (instance? NoSuchFileException e) (str (file-name) " doesn't exist")
      (and (instance? FileSystemException e) (.getReason ^FileSystemException e)) (.getReason ^FileSystemException e)
      (not (str/blank? (.getMessage e))) (.getMessage e)
      :else (.getSimpleName (class e)))))


;; Reading

(defn- time-text [pattern ms ^ZoneId zone]
  (.format (DateTimeFormatter/ofPattern pattern Locale/ENGLISH) (.atZone (Instant/ofEpochMilli ms) zone)))

(defn- keep-aside!
  "Move the file that can't be read to rig-unreadable-<yyyy-MM-dd-HHmmss>.edn
   beside it (-2, -3 … when that name is taken). Its new name, or nil when it
   couldn't be moved."
  [^File file now zone]
  (let [base (str "rig-unreadable-" (time-text "yyyy-MM-dd-HHmmss" now zone))
        target (->> (cons base (map #(str base "-" %) (iterate inc 2)))
                    (map #(io/file (.getParentFile (.getAbsoluteFile file)) (str % ".edn")))
                    (remove #(.exists ^File %))
                    first)]
    (try
      (Files/move (.toPath file) (.toPath ^File target) (make-array CopyOption 0))
      (.getName ^File target)
      (catch Exception e
        (log/error e "Could not keep the unreadable rig file aside")
        nil))))

(defn- unreadable-notice [kept backup-ms zone]
  (let [kept-text (when kept (str "kept as " kept))]
    (if backup-ms
      (str "Your rig file couldn't be read, so its backup from " (time-text "d MMM HH:mm" backup-ms zone) " is in use."
           (when kept-text (str " The broken file was " kept-text ".")))
      (str "Your rig file couldn't be read."
           (when kept-text (str " It was " kept-text ","))
           (if kept-text " and the rig starts empty." " The rig starts empty.")))))

(defn read!
  "Read the rig file at path: {:status :doc :notice}.
   - :missing, no doc: there is none (never written, or deleted to start
     over: a rig.edn.bak left behind is not used);
   - :ok with its doc;
   - :recovered with the backup's doc and a notice: the file couldn't be read
     or isn't shaped like a rig, so it was kept aside (keep-aside!) and
     rig.edn.bak is in use;
   - :reset with a notice and no doc: the same, with no backup that reads;
   - :newer with its doc (the file's, or the backup's with the notice): from
     a newer build, so the app uses it read only.
   opts: :read-only? (another window has the file: one that can't be read
   is left where it is); :now (ms, the time in the kept file's name) and
   :zone (for the times), for tests."
  ([path] (read! path {}))
  ([path {:keys [now zone read-only?] :or {now (System/currentTimeMillis) zone (ZoneId/systemDefault)}}]
   (let [file (io/file path)]
     (if-not (.exists file)
       {:status :missing}
       (if-let [doc (parse file)]
         {:status (if (rig-file/newer? doc) :newer :ok) :doc doc}
         (let [kept (when-not read-only? (keep-aside! file now zone))
               backup (sibling path ".bak")
               backup-doc (when (.exists backup) (parse backup))]
           (if backup-doc
             {:status (if (rig-file/newer? backup-doc) :newer :recovered)
              :doc backup-doc
              :notice (unreadable-notice kept (.lastModified backup) zone)}
             {:status :reset
              :notice (unreadable-notice kept nil zone)})))))))


;; Writing

(defn- force-write!
  "Write text to file and force it to disk."
  [^File file ^String text]
  (with-open [channel (FileChannel/open (.toPath file)
                                        (into-array OpenOption [StandardOpenOption/CREATE
                                                                StandardOpenOption/TRUNCATE_EXISTING
                                                                StandardOpenOption/WRITE]))]
    (let [buffer (ByteBuffer/wrap (.getBytes text "UTF-8"))]
      (while (.hasRemaining buffer)
        (.write channel buffer))
      (.force channel true))))

(defn- move-into-place!
  "Move from onto to in one step; where the file system has no atomic move,
   a plain replacing one."
  [^File from ^File to]
  (try
    (Files/move (.toPath from) (.toPath to) (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
    (catch AtomicMoveNotSupportedException _
      (Files/move (.toPath from) (.toPath to) (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))))

(defn write!
  "Write doc (rig-file/rig-document) to path, never leaving half a file:
   1. the header and doc go to rig.edn.tmp, forced to disk;
   2. the current rig.edn, if it reads, is copied to rig.edn.bak;
   3. rig.edn.tmp is moved onto rig.edn (atomically where it can be).
   The folder is made when it isn't there. Returns {:at ms}, or {:error
   reason} with the file and its backup as they were (a failed move leaves
   the backup a copy of the file)."
  [path doc]
  (let [file (io/file path)
        tmp (sibling path ".tmp")]
    (try
      (some-> (.getParentFile (.getAbsoluteFile file)) .toPath (Files/createDirectories (make-array java.nio.file.attribute.FileAttribute 0)))
      (force-write! tmp (str header (binding [*print-length* nil *print-level* nil *print-meta* false]
                                      (ser/serialize doc :pretty? true))))
      (when (and (.exists file) (parse file))
        (Files/copy (.toPath file) (.toPath (sibling path ".bak")) (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))
      (move-into-place! tmp file)
      {:at (System/currentTimeMillis)}
      (catch Exception e
        (log/error e "Could not write the rig file" (str path))
        (try (Files/deleteIfExists (.toPath tmp)) (catch Exception _))
        {:error (reason e)}))))


;; One window at a time

(defonce ^{:private true :doc "{lock-file-path {:channel :lock}}: the locks this process holds."}
  !held
  (atom {}))

(defonce ^{:private true :doc "The lock-file path of the session's lock (lock!), or nil."}
  !session
  (atom nil))

(defn- lock-key [path]
  (str (.normalize (.toAbsolutePath (.toPath (sibling path ".lock"))))))

(defn try-lock
  "Take the lock on path's rig.edn.lock: a key for release!, or nil when
   another window holds it, in this process or another. One taken in this
   process is refused without opening the file again, so the OS's lock is
   never let go of by closing a second channel."
  [path]
  (let [k (lock-key path)]
    (locking !held
      (when-not (contains? @!held k)
        (let [file (io/file k)]
          (.mkdirs (.getParentFile file))
          (let [channel (FileChannel/open (.toPath file) (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
                lock (try (.tryLock channel)
                          (catch OverlappingFileLockException _ nil)
                          (catch Exception e (.close channel) (throw e)))]
            (if lock
              (do (swap! !held assoc k {:channel channel :lock lock})
                  k)
              (do (.close channel)
                  nil))))))))

(defn release!
  "Let go of a lock try-lock gave."
  [k]
  (locking !held
    (when-let [{:keys [^FileChannel channel ^FileLock lock]} (get @!held k)]
      (swap! !held dissoc k)
      (try (.release lock) (finally (.close channel))))
    nil))

(defn lock!
  "Take path's lock for the session: true, or false when another window has
   the rig (it is then read only, Q-ZR-4). A lock file that can't be made
   (no folder, no permission) doesn't stop the app: true, this window writes
   the rig, and a write that fails says why."
  [path]
  (try
    (if-let [k (try-lock path)]
      (do (reset! !session k) true)
      false)
    (catch Exception e
      (log/warn "The rig file can't be locked, so this window writes it anyway:" (str path) (.getMessage e))
      true)))

(defn unlock!
  "Let go of the session's lock, if one is held."
  []
  (when-let [k @!session]
    (reset! !session nil)
    (release! k))
  nil)


;; The writer

(def write-delay-ms
  "How long after the last change the rig is written."
  500)

(defn- daemon-factory [thread-name]
  (reify ThreadFactory
    (newThread [_ r]
      (doto (Thread. ^Runnable r ^String thread-name)
        (.setDaemon true)))))

(defonce ^{:private true :doc "The rig-writer thread, started on first use."}
  !writer
  (delay (java.util.concurrent.Executors/newSingleThreadScheduledExecutor (daemon-factory "rig-writer"))))

(defonce ^{:private true :doc "{:path :doc :on-result :task}: the newest rig not written yet."}
  !pending
  (atom nil))

(defn- write-pending!
  "Take the pending rig and write it (on the rig-writer thread)."
  []
  (when-let [{:keys [path doc on-result]} (first (swap-vals! !pending (constantly nil)))]
    (let [result (write! path doc)]
      (when on-result
        (try (on-result result)
             (catch Throwable t
               (log/error t "The rig writer's result could not be reported")))))))

(defn submit!
  "Keep doc as the rig to write to path, in place of one not written yet,
   and write it delay-ms (write-delay-ms) after the last submit, on the
   rig-writer thread. on-result gets {:at ms} or {:error reason} there; a
   superseded rig's never does."
  ([path doc on-result] (submit! path doc on-result write-delay-ms))
  ([path doc on-result delay-ms]
   (let [^ScheduledExecutorService writer @!writer]
     (locking !pending
       (when-let [^ScheduledFuture task (:task @!pending)]
         (.cancel task false))
       (reset! !pending {:path path :doc doc :on-result on-result
                         :task (.schedule writer ^Runnable write-pending! (long delay-ms) TimeUnit/MILLISECONDS)})))))

(defn flush!
  "Write the pending rig now, waiting up to timeout-ms for it (and for a
   write already running). True when nothing is left to write."
  [timeout-ms]
  (let [^Future task (locking !pending
                       (when-let [{:keys [^ScheduledFuture task]} @!pending]
                         (when task (.cancel task false))
                         (.submit ^ExecutorService @!writer ^Runnable write-pending!)))]
    (if task
      (try
        (.get task (long timeout-ms) TimeUnit/MILLISECONDS)
        true
        (catch TimeoutException _
          (log/warn "The rig file was not written within" timeout-ms "ms")
          false))
      true)))

(defonce ^:private !shutdown-hook (atom nil))

(defn install-shutdown-hook!
  "Register (once) a JVM shutdown hook that writes the pending rig, so a
   change made just before the app ends still reaches the file."
  []
  (locking !shutdown-hook
    (when-not @!shutdown-hook
      (let [hook (Thread. ^Runnable #(flush! 2000) "rig-flush-on-shutdown")]
        (.addShutdownHook (Runtime/getRuntime) hook)
        (reset! !shutdown-hook hook))))
  @!shutdown-hook)
