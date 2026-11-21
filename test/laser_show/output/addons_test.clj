(ns laser-show.output.addons-test
  "Loading output drivers from add-on jars (plans/2026-11-19-output-drivers/01
   §4, 02 § D3): the descriptor, the checks and their statuses, all or
   nothing per add-on, the classloaders, installing over a loaded jar, and
   the cross-platform rules (01 §4.4), which run on macOS, Windows and Linux
   in CI.

   The jars are built from test-resources/addons by jar-builder, each with
   namespaces of its own (see test-resources/addons/README.md). Every test
   loads with :classpath-loader nil (no classpath descriptors) and a
   :platform passed in, so it runs the same on every OS, and with a temp
   :dir; laser-show.addons-dir points at a temp folder too, so no test reads
   or makes the real ~/LaserShowProjects/addons. Afterwards every
   add-on driver is unregistered and its classloader closed, as quitting
   would."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.backend.streaming-engine :as se]
            [laser-show.output.addons :as addons]
            [laser-show.output.driver :as driver]
            [laser-show.output.jar-builder :as jar])
  (:import [java.io File]
           [java.net URL URLClassLoader]))

(def ^:private mac-arm {:os :macos :arch :aarch64})
(def ^:private mac-x86 {:os :macos :arch :x86-64})
(def ^:private windows {:os :windows :arch :x86-64})
(def ^:private linux {:os :linux :arch :x86-64})

(defn- with-system-properties
  "(f) with the system properties props set (nil clears one), then the old
   values back."
  [props f]
  (let [old (into {} (map (fn [k] [k (System/getProperty k)])) (keys props))
        put! (fn [m] (doseq [[k v] m]
                       (if (nil? v) (System/clearProperty k) (System/setProperty k v))))]
    (put! props)
    (try (f) (finally (put! old)))))

(def ^:private !dirs (atom []))

(defn- temp-dir! ^File []
  (let [dir (jar/temp-dir! "laser-show-addons-test")]
    (swap! !dirs conj dir)
    dir))

(defn- unload-addons!
  "Unregister every add-on driver and close its classloader."
  []
  (doseq [d (driver/registered) :when (::driver/addon d)]
    (driver/unregister! (:id d))
    (when-let [loader (::driver/loader d)]
      (when (instance? URLClassLoader loader)
        (.close ^URLClassLoader loader)))))

(use-fixtures :once
  (fn [f]
    (let [dir (jar/temp-dir! "laser-show-addons-dir")]
      (try
        (with-system-properties {"laser-show.addons-dir" (.getAbsolutePath (io/file dir "addons"))} f)
        (finally
          (jar/delete-tree! dir))))))

(use-fixtures :each
  (fn [f]
    (try
      (f)
      (finally
        (unload-addons!)
        (run! jar/delete-tree! @!dirs)
        (reset! !dirs [])))))

(defn- load!
  "load-all! on dir as a Mac on Apple Silicon, without classpath descriptors."
  [dir & {:as opts}]
  (addons/load-all! (merge {:dir dir :platform mac-arm :classpath-loader nil} opts)))

(defn- path [built]
  (.getAbsolutePath ^File (:file built)))

(defn- loaded? [built suffix]
  (some? (find-ns (jar/fixture-ns built suffix))))

(def ^:private laser-show-refused
  (str "sample.jar has files under laser_show/: add-on namespaces must not start with laser-show., "
       "and laser-show must be a provided dependency, not bundled"))

(defn- wait-for!
  "Call pred every few ms until it returns truthy or timeout-ms passes.
   Returns pred's value, or nil on timeout."
  [pred timeout-ms]
  (let [deadline (+ (System/nanoTime) (* (long timeout-ms) 1000000))]
    (loop []
      (or (pred)
          (when (< (System/nanoTime) deadline)
            (Thread/sleep 5)
            (recur))))))

(defn- sample-descriptor
  "The sample's descriptor with extra keys, as text."
  [extra]
  (pr-str (merge {:id :test/sample :name "Sample add-on" :version "1.0.0" :api 1
                  :drivers ['laser-show-test-addon.sample.driver/driver]}
                 extra)))



;; Loading

(deftest loads-driver-from-jar-test
  (let [dir (temp-dir!)
        built (jar/build! dir "sample" :extra {"logback.xml" "<configuration/>"})
        statuses (load! dir)
        d (driver/lookup :test/sample-stream)]
    (is (= [{:id :test/sample :name "Sample add-on" :version "1.0.0" :source (path built)
             :state :loaded :drivers [:test/sample-stream]}]
           (mapv #(dissoc % :load-ms) statuses)))
    (is (nat-int? (:load-ms (first statuses))) "how long it took, in ms")
    (is (= "Sample add-on stream" (:name d)) "the driver is registered")
    (is (= :test/sample (::driver/addon d)) "with the add-on that brought it")
    (is (= :test/sample (get-in (driver/descriptors) [:test/sample-stream :addon])))
    (is (loaded? built "sample.driver"))
    (is (not (loaded? built "sample.helper")) "only the descriptor's namespaces are required")
    (testing "its own URLClassLoader, over the jar, under the caller's context classloader"
      (let [^URLClassLoader loader (::driver/loader d)
            parent (.getContextClassLoader (Thread/currentThread))]
        (is (instance? URLClassLoader loader))
        (is (= [(.toURL (.toURI ^File (:file built)))] (vec (.getURLs loader))))
        (is (identical? parent (.getParent loader)))
        (is (= (.getResource parent "logback.xml") (.getResource loader "logback.xml"))
            "parent first: the app's logback.xml, not the jar's")
        (is (some? (.getResource loader "laser-show-addon.edn")) "the jar's own resources")))
    (is (= {:config {:label "x"}} ((:open d) {:label "x"} {:id :p :scan-rate 30000 :fps 60})))))

(deftest api-mismatch-is-not-loaded-test
  (let [dir (temp-dir!)
        built (jar/build! dir "api2")]
    (is (= [{:id :test/api2 :name "API 2 add-on" :version "1.0.0" :source (path built)
             :state :failed :error "Built for output API 2; this app has API 1"}]
           (load! dir)))
    (is (not (loaded? built "sample.driver")) "nothing is required")
    (is (nil? (driver/lookup :test/sample-stream)))))

(deftest missing-descriptor-test
  (let [dir (temp-dir!)
        built (jar/build! dir "sample" :file-name "broken.jar" :without ["laser-show-addon.edn"])]
    (is (= [{:source (path built) :state :failed :error "No laser-show-addon.edn in broken.jar"}]
           (load! dir)))
    (is (not (loaded? built "sample.driver"))))
  (testing "a descriptor that isn't a map, or misses what it needs"
    (doseq [[descriptor error]
            [["[:test/sample]" "Bad laser-show-addon.edn in sample.jar: it must be a map"]
             ["" "Bad laser-show-addon.edn in sample.jar: it is empty"]
             [(sample-descriptor {:id :sample}) "Bad laser-show-addon.edn in sample.jar: :id must be a qualified keyword"]
             [(sample-descriptor {:api nil}) "Bad laser-show-addon.edn in sample.jar: :api must be the output API it was built for (1)"]
             [(sample-descriptor {:drivers []}) "Bad laser-show-addon.edn in sample.jar: :drivers must be a vector of qualified symbols, the vars holding its drivers"]
             [(sample-descriptor {:drivers '[driver]}) "Bad laser-show-addon.edn in sample.jar: :drivers must be a vector of qualified symbols, the vars holding its drivers"]
             [(sample-descriptor {:platforms :macos}) "Bad laser-show-addon.edn in sample.jar: :platforms must be a vector of platforms"]]]
      (let [dir (temp-dir!)
            built (jar/build! dir "sample" :descriptor descriptor)]
        (is (= error (:error (first (load! dir)))) descriptor)
        (is (not (loaded? built "sample.driver")))))))

(deftest unreadable-jar-fails-test
  (let [dir (temp-dir!)
        garbage (io/file dir "garbage.jar")]
    (spit garbage "not a zip")
    (let [[status :as statuses] (load! dir)]
      (is (= 1 (count statuses)))
      (is (= {:source (.getAbsolutePath garbage) :state :failed} (dissoc status :error)))
      (is (str/starts-with? (:error status) "Can't read garbage.jar: ") (:error status))))
  (testing "a jar the app may not read (where the OS lets a test take the permission away)"
    (let [dir (temp-dir!)
          built (jar/build! dir "sample")
          ^File file (:file built)]
      (when (and (.setReadable file false) (not (.canRead file)))
        (try
          (let [[status] (load! dir)]
            (is (= :failed (:state status)))
            (is (str/starts-with? (:error status) "Can't read sample.jar: ") (:error status)))
          (finally
            (.setReadable file true)))))))

(deftest taken-driver-id-registers-nothing-test
  (testing "a driver id another add-on has (A-1, A-2)"
    (let [dir (temp-dir!)
          sample (jar/build! dir "sample")
          taken (jar/build! dir "taken-id")
          [first-status second-status] (load! dir)
          mine (driver/lookup :test/sample-stream)]
      (is (= :loaded (:state first-status)))
      (is (= {:id :test/taken-id :name "Taken id add-on" :version "1.0.0" :source (path taken)
              :state :failed :error "Driver id :test/sample-stream is already taken by :test/sample"}
             second-status))
      (is (nil? (driver/lookup :test/taken-fresh)) "its other driver isn't registered either (A-1)")
      (is (= :test/sample (::driver/addon mine)) "the sample's driver stays (A-2)")
      (is (loaded? sample "sample.driver"))))
  (testing "a built-in's id"
    (let [built-in? (nil? (driver/lookup :idn))
          _ (when built-in?
              (driver/register! {:id :idn :name "IDN" :api 1 :transport :network :fields []
                                 :open (fn [_ _]) :send-frame! (fn [_ _]) :close! (fn [_])}))
          idn (driver/lookup :idn)]
      (try
        (let [dir (temp-dir!)]
          (jar/build! dir "taken-id" :descriptor (sample-descriptor {:id :test/taken-id
                                                                     :drivers ['laser-show-test-addon.taken-id.driver/fresh
                                                                               'laser-show-test-addon.taken-id.driver/idn]}))
          (is (= "Driver id :idn is already taken by a built-in driver" (:error (first (load! dir)))))
          (is (nil? (driver/lookup :test/taken-fresh)))
          (is (identical? idn (driver/lookup :idn)) "the built-in stays"))
        (finally
          (when built-in? (driver/unregister! :idn)))))))

(deftest invalid-driver-registers-nothing-test
  (doseq [[drivers error]
          [['[laser-show-test-addon.taken-id.driver/fresh laser-show-test-addon.taken-id.driver/no-send]
            "Driver :test/no-send: missing :send-frame!"]
           ['[laser-show-test-addon.taken-id.driver/fresh laser-show-test-addon.taken-id.driver/not-a-map]
            "Driver laser-show-test-addon.taken-id.driver/not-a-map: a driver must be a map"]
           ['[laser-show-test-addon.taken-id.driver/fresh laser-show-test-addon.taken-id.driver/nope]
            "Driver var laser-show-test-addon.taken-id.driver/nope is not defined"]
           ['[laser-show-test-addon.taken-id.driver/fresh laser-show-test-addon.taken-id.driver/fresh]
            "Driver id :test/taken-fresh appears twice in this add-on"]
           ['[laser-show-test-addon.taken-id.driver/no-send laser-show-test-addon.taken-id.driver/not-a-map]
            (str "Driver :test/no-send: missing :send-frame!; "
                 "Driver laser-show-test-addon.taken-id.driver/not-a-map: a driver must be a map")]]]
    (let [dir (temp-dir!)
          built (jar/build! dir "taken-id" :descriptor (sample-descriptor {:id :test/taken-id :drivers drivers}))
          [status] (load! dir)]
      (is (= {:state :failed :error (str/replace error "laser-show-test-addon" (:ns-root built))}
             (select-keys status [:state :error]))
          (pr-str drivers))
      (is (nil? (driver/lookup :test/taken-fresh)) "the valid driver isn't registered (A-1)"))))

(deftest broken-namespace-does-not-stop-others-test
  (let [dir (temp-dir!)
        first-broken (jar/build! dir "broken" :file-name "a-broken.jar")
        sample (jar/build! dir "sample")
        last-broken (jar/build! dir "broken" :file-name "z-broken.jar")
        statuses (load! dir)]
    (is (= [[(path first-broken) :failed] [(path sample) :loaded] [(path last-broken) :failed]]
           (mapv (juxt :source :state) statuses))
        "in file name order, each one on its own (A-3)")
    (is (= (str "Could not load " (jar/fixture-ns first-broken "sample.driver") ": This add-on is broken on purpose")
           (:error (first statuses))))
    (is (= :test/broken (:id (first statuses))))
    (is (= :test/sample (::driver/addon (driver/lookup :test/sample-stream))))))

(deftest broken-namespace-fails-the-same-after-a-restart-test
  ;; app/stop! then start-test! in one JVM (a dev REPL, the UI tests) loads
  ;; the folder again: the namespace whose load threw was left in Clojure's
  ;; loaded libs, so require skipped it and the reason became "No such
  ;; namespace"
  (let [dir (temp-dir!)
        broken (jar/build! dir "broken")
        reason (str "Could not load " (jar/fixture-ns broken "sample.driver") ": This add-on is broken on purpose")]
    (is (= [[:failed reason]] (mapv (juxt :state :error) (load! dir))))
    (addons/unload-all!)
    (is (= [[:failed reason]] (mapv (juxt :state :error) (load! dir))) "the same reason the second time")))

(deftest bundled-clojure-is-refused-test
  (let [dir (temp-dir!)
        built (jar/build! dir "bundles-clojure")]
    (is (= [{:id :test/bundles-clojure :name "Add-on that bundles Clojure" :version "1.0.0" :source (path built)
             :state :failed :error "bundles-clojure.jar bundles Clojure; build it with Clojure as a provided dependency"}]
           (load! dir)))
    (is (not (loaded? built "sample.driver"))))
  (testing "laser-show's own code"
    (let [dir (temp-dir!)
          built (jar/build! dir "sample" :extra {"laser_show/output/driver.clj" "(ns laser-show.output.driver)"})]
      (is (= laser-show-refused (:error (first (load! dir)))))
      (is (not (loaded? built "sample.driver"))))))

(deftest laser-show-namespaces-are-refused-test
  (testing "G8: an add-on's own namespace under laser-show. is refused, and the message says why"
    (let [dir (temp-dir!)
          built (jar/build! dir "sample"
                            :extra {"laser_show/acme/nova/driver.clj" "(ns laser-show.acme.nova.driver)"}
                            :descriptor (sample-descriptor {:drivers ['laser-show.acme.nova.driver/driver]}))]
      (is (= [{:id :test/sample :name "Sample add-on" :version "1.0.0" :source (path built)
               :state :failed :error laser-show-refused}]
             (load! dir)))
      (is (nil? (find-ns 'laser-show.acme.nova.driver)) "nothing in it is loaded")
      (is (nil? (driver/lookup :test/sample-stream)))))
  (testing "install! refuses it the same way, writing nothing"
    (let [staging (temp-dir!)
          dir (temp-dir!)
          built (jar/build! staging "sample" :extra {"laser_show/acme/nova/driver.clj" "(ns laser-show.acme.nova.driver)"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo (re-pattern (java.util.regex.Pattern/quote laser-show-refused))
                            (addons/install! (:file built) dir)))
      (is (= [] (vec (.listFiles dir))))))
  (testing "a root that only starts with the same letters is fine: the fixtures' laser_show_test_addon/"
    (let [dir (temp-dir!)]
      (jar/build! dir "sample")
      (is (= [:loaded] (mapv :state (load! dir)))))))

(deftest duplicate-addon-id-test
  (let [dir (temp-dir!)
        first-one (jar/build! dir "sample" :file-name "sample-a.jar")
        second-one (jar/build! dir "sample" :file-name "sample-b.jar")
        statuses (load! dir)]
    (is (= :loaded (:state (first statuses))))
    (is (= {:id :test/sample :name "Sample add-on" :version "1.0.0" :source (path second-one)
            :state :failed :error (str "Add-on :test/sample is already loaded from " (path first-one))}
           (second statuses)))
    (is (loaded? first-one "sample.driver"))
    (is (not (loaded? second-one "sample.driver")) "the second one isn't loaded at all")))

(deftest classpath-descriptors-load-first-test
  (let [cp-dir (temp-dir!)
        on-classpath (jar/build! cp-dir "sample")
        cp-loader (URLClassLoader. (into-array URL [(.toURL (.toURI ^File (:file on-classpath)))])
                                   (.getContextClassLoader (Thread/currentThread)))
        dir (temp-dir!)
        in-folder (jar/build! dir "sample")
        source (str (.getResource cp-loader "laser-show-addon.edn"))]
    (try
      (let [statuses (load! dir :classpath-loader cp-loader)]
        (is (= [{:id :test/sample :name "Sample add-on" :version "1.0.0" :source source
                 :state :loaded :drivers [:test/sample-stream]}
                {:id :test/sample :name "Sample add-on" :version "1.0.0" :source (path in-folder)
                 :state :failed :error (str "Add-on :test/sample is already loaded from " source)}]
               (mapv #(dissoc % :load-ms) statuses)))
        (is (identical? cp-loader (::driver/loader (driver/lookup :test/sample-stream)))
            "required with the classpath's loader, and called with it")
        (is (loaded? on-classpath "sample.driver"))
        (is (not (loaded? in-folder "sample.driver"))))
      (finally
        (unload-addons!)
        (.close cp-loader))))
  (testing "the app's own classpath has no add-on: test-resources is on no test classpath"
    (is (= [] (addons/load-all! {:dir (temp-dir!) :platform mac-arm})))))

(deftest pending-install-is-swapped-at-start-test
  (let [dir (temp-dir!)
        old (jar/build! dir "sample")
        new (jar/build! dir "sample" :file-name "sample.jar.new" :replace {"\"1.0.0\"" "\"2.0.0\""})
        fresh (jar/build! dir "taken-id" :file-name "taken-id.jar.new"
                          :descriptor (sample-descriptor {:id :test/taken-id
                                                          :drivers ['laser-show-test-addon.taken-id.driver/fresh]}))
        statuses (load! dir)]
    (is (= [[(path old) "2.0.0" :loaded] [(.getAbsolutePath (io/file dir "taken-id.jar")) "1.0.0" :loaded]]
           (mapv (juxt :source :version :state) statuses))
        "the new one replaces the old one, and a new add-on is just put in place")
    (is (not (.exists ^File (:file new))))
    (is (not (.exists ^File (:file fresh))))
    (is (loaded? new "sample.driver"))
    (is (not (loaded? old "sample.driver")))
    (is (= 2 (count (.listFiles dir))))))

(deftest descriptor-is-not-evaluated-test
  (System/clearProperty "laser-show.test.read-eval")
  (let [dir (temp-dir!)
        built (jar/build! dir "read-eval")
        [status :as statuses] (load! dir)]
    (is (= 1 (count statuses)))
    (is (= {:source (path built) :state :failed} (dissoc status :error)))
    (is (str/starts-with? (:error status) "Can't read laser-show-addon.edn in read-eval.jar: ") (:error status))
    (is (nil? (System/getProperty "laser-show.test.read-eval")) "#=(…) never ran (A-4)")
    (is (not (loaded? built "sample.driver")))))

(deftest addon-driver-runs-on-another-thread-test
  (let [dir (temp-dir!)
        built (jar/build! dir "sample")
        _ (load! dir)
        d (driver/lookup :test/sample-stream)
        frames @(ns-resolve (jar/fixture-ns built "sample.driver") 'frames)
        calls @(ns-resolve (jar/fixture-ns built "sample.driver") 'calls)
        session ((:open d) {} {:id :p :scan-rate 30000 :fps 60})
        frame [[0.0 0.0 0.0 0.0 0.0] [0.5 0.5 1.0 0.0 0.0]]
        on-thread (fn [thread-name f]
                    (let [result (promise)
                          thread (Thread. ^Runnable #(deliver result (try (f) :sent (catch Throwable e e)))
                                          ^String thread-name)]
                      (.start thread)
                      (.join thread 10000)
                      (deref result 0 :timed-out)))]
    (testing "without the add-on's loader, the jar's other namespaces can't be found"
      (is (instance? Throwable (on-thread "addon-test-plain" #((:send-frame! d) session frame))))
      (is (not (loaded? built "sample.helper"))))
    (testing "through driver/with-loader, another thread sends, and the frames are the driver's"
      (reset! frames [])
      (reset! calls [])
      (is (= :sent (on-thread "addon-test-streaming" #(driver/with-loader d (fn [] ((:send-frame! d) session frame))))))
      (is (= [frame] @frames))
      (is (= [{:thread "addon-test-streaming" :loader (::driver/loader d) :description "2 points"}] @calls))
      (is (loaded? built "sample.helper") "required on that thread, from the jar"))))

(deftest addon-driver-runs-on-the-streaming-thread-test
  (testing "the sample add-on, loaded by load-all!, run by a real engine: its frames arrive in its
            own atom, sent on output-streaming-<id> with the add-on's loader"
    (let [dir (temp-dir!)
          built (jar/build! dir "sample")
          [status] (load! dir)
          d (driver/lookup :test/sample-stream)
          frames @(ns-resolve (jar/fixture-ns built "sample.driver") 'frames)
          calls @(ns-resolve (jar/fixture-ns built "sample.driver") 'calls)
          frame [[0.0 0.0 0.0 0.0 0.0] [0.5 0.5 1.0 0.0 0.0]]
          engine (se/create-driver-engine d {:label "sample"} (constantly frame) :id :addon-test)]
      (is (= :loaded (:state status)))
      (is (not (loaded? built "sample.helper")) "its helper isn't loaded yet")
      (reset! frames [])
      (reset! calls [])
      (try
        (se/start! engine)
        (is (wait-for! #(<= 3 (count @frames)) 2000) "frames arrive")
        (is (= {:config {:label "sample"}} @(:session engine)) "opened by the add-on's :open")
        (finally
          (se/stop! engine)))
      (is (every? #(= frame %) @frames))
      (is (= #{"output-streaming-addon-test"} (set (map :thread @calls))) "on the engine's streaming thread")
      (is (= #{(::driver/loader d)} (set (map :loader @calls))) "with the add-on's loader as its context classloader")
      (is (= #{"2 points"} (set (map :description @calls))))
      (is (loaded? built "sample.helper") "its helper was required on the streaming thread, from the jar"))))


;; Cross-platform (01 §4.4)

(deftest addons-dir-is-built-from-user-home-test
  (let [home (temp-dir!)]
    (with-system-properties {"user.home" (.getAbsolutePath home) "laser-show.addons-dir" nil}
      (fn []
        (is (= (io/file home "LaserShowProjects" "addons") (addons/addons-dir)))
        (is (= (io/file home "LaserShowProjects") (.getParentFile (addons/addons-dir)))
            "next to the projects folder")
        (is (not (.exists (addons/addons-dir))) "only a path: nothing is made")))
    (with-system-properties {"user.home" (.getAbsolutePath home) "laser-show.addons-dir" ""}
      #(is (= (io/file home "LaserShowProjects" "addons") (addons/addons-dir))
           "a blank property is no property"))
    (let [elsewhere (io/file home "elsewhere" "my add-ons")]
      (with-system-properties {"user.home" (.getAbsolutePath home) "laser-show.addons-dir" (.getPath elsewhere)}
        #(is (= elsewhere (addons/addons-dir)) "the property overrides it")))))

(deftest folder-is-created-on-first-load-test
  (let [dir (io/file (temp-dir!) "LaserShowProjects" "addons")]
    (is (not (.exists dir)))
    (is (= [] (load! dir)))
    (is (.isDirectory dir) "there to open and drop jars into")))

(deftest path-with-spaces-and-non-ascii-loads-test
  (let [dir (io/file (temp-dir!) "Zoë Smith" "add ons")
        built (jar/build! dir "sample")
        [status] (load! dir)
        ^URLClassLoader loader (::driver/loader (driver/lookup :test/sample-stream))
        ^URL url (first (.getURLs loader))]
    (is (= {:source (path built) :state :loaded} (select-keys status [:source :state])))
    (is (str/includes? (str url) "%20Smith/add%20ons/sample.jar") "File.toURI escapes the spaces")
    (is (= (.getCanonicalFile ^File (:file built)) (.getCanonicalFile (io/file (.toURI url))))
        "and the URL leads back to the jar")
    (is (loaded? built "sample.driver"))))

(deftest uppercase-jar-extension-loads-test
  (let [dir (temp-dir!)
        built (jar/build! dir "sample" :file-name "Sample.JAR")]
    (is (= [[(path built) :loaded]] (mapv (juxt :source :state) (load! dir))))
    (is (addons/addon-file? (io/file dir "Sample.JAR")))))

(deftest hidden-and-appledouble-files-are-ignored-test
  (let [dir (temp-dir!)
        built (jar/build! dir "sample")]
    (spit (io/file dir "._sample.jar") "AppleDouble garbage")
    (jar/build! dir "api2" :file-name ".hidden.jar")
    (spit (io/file dir ".DS_Store") "x")
    (spit (io/file dir "Thumbs.db") "x")
    (spit (io/file dir "notes.txt") "x")
    (jar/build! (io/file dir "sub-folder") "api2")
    (.mkdirs (io/file dir "folder.jar"))
    (is (= [[(path built) :loaded]] (mapv (juxt :source :state) (load! dir)))
        "a status for the one add-on, none for the rest")
    (testing "addon-file?"
      (is (addons/addon-file? (io/file dir "sample.jar")))
      (doseq [name ["._sample.jar" ".hidden.jar" ".DS_Store" "Thumbs.db" "notes.txt" "sub-folder" "folder.jar"]]
        (is (not (addons/addon-file? (io/file dir name))) name))
      (is (not (addons/addon-file? (io/file dir "missing.jar"))) "a file that isn't there"))))

(deftest current-platform-test
  (is (= mac-arm (addons/current-platform "Mac OS X" "aarch64")))
  (is (= mac-x86 (addons/current-platform "Mac OS X" "x86_64")))
  (is (= windows (addons/current-platform "Windows 11" "amd64")))
  (is (= {:os :windows :arch :aarch64} (addons/current-platform "Windows Server 2025" "arm64")))
  (is (= linux (addons/current-platform "Linux" "amd64")))
  (is (= {:os :other :arch :other} (addons/current-platform "FreeBSD" "riscv64")))
  (is (= {:os :other :arch :other} (addons/current-platform nil nil)))
  (let [{:keys [os arch]} (addons/current-platform)]
    (is (contains? #{:macos :windows :linux :other} os))
    (is (contains? #{:aarch64 :x86-64 :other} arch))))

(deftest platform-mismatch-is-skipped-test
  (testing "supports-platform?"
    (doseq [[platforms platform supported?]
            [[nil mac-arm true]
             [nil windows true]
             [[:windows :linux] mac-arm false]
             [[:windows :linux] linux true]
             [[[:macos :aarch64]] mac-x86 false]
             [[[:macos :aarch64]] mac-arm true]
             [[:macos] mac-x86 true]
             [[:windows [:macos :aarch64]] mac-arm true]]]
      (is (= supported? (addons/supports-platform? platforms platform)) (pr-str platforms platform))))
  (testing "01 §4.4's table, loaded"
    (doseq [[platforms platform error]
            [[nil mac-arm nil]
             [nil windows nil]
             [[:windows :linux] mac-arm "Not available on macOS (aarch64); it supports Windows and Linux"]
             [[[:macos :aarch64]] mac-x86 "Not available on macOS (x86-64); it supports macOS (aarch64)"]
             [[:macos] mac-x86 nil]
             [[:linux [:macos :aarch64] :windows] linux nil]
             [[[:windows :aarch64] :linux [:macos :aarch64]] windows
              "Not available on Windows (x86-64); it supports Windows (aarch64), Linux and macOS (aarch64)"]]]
      (let [dir (temp-dir!)
            built (jar/build! dir "sample" :descriptor (sample-descriptor (when platforms {:platforms platforms})))
            [status] (load! dir :platform platform)]
        (if error
          (do (is (= {:id :test/sample :name "Sample add-on" :version "1.0.0" :source (path built)
                      :state :skipped :error error}
                     status)
                  (pr-str platforms platform))
              (is (not (loaded? built "sample.driver")) "nothing in it is loaded"))
          (is (= :loaded (:state status)) (pr-str platforms platform)))
        (unload-addons!))))
  (testing "windows-only on a Mac, then on Windows"
    (let [dir (temp-dir!)
          built (jar/build! dir "windows-only")]
      (is (= [{:id :test/windows-only :name "Windows-only add-on" :version "1.0.0" :source (path built)
               :state :skipped :error "Not available on macOS (aarch64); it supports Windows"}]
             (load! dir)))
      (is (not (loaded? built "sample.driver")))
      (is (= [:loaded] (mapv :state (load! dir :platform windows))))
      (is (loaded? built "sample.driver")))))

(deftest unknown-platform-fails-test
  (doseq [[platforms error] [[[:beos] "Unknown platform :beos"]
                             [[:windows [:macos :sparc]] "Unknown platform [:macos :sparc]"]
                             [[[:macos]] "Unknown platform [:macos]"]]]
    (let [dir (temp-dir!)
          built (jar/build! dir "sample" :descriptor (sample-descriptor {:platforms platforms}))]
      (is (= [{:id :test/sample :name "Sample add-on" :version "1.0.0" :source (path built)
               :state :failed :error error}]
             (load! dir))
          (pr-str platforms))
      (is (not (loaded? built "sample.driver"))))))

(deftest install-over-a-loaded-jar-is-pending-test
  (let [dir (temp-dir!)
        staging (temp-dir!)
        v1 (jar/build! dir "sample")
        _ (is (= ["1.0.0"] (mapv :version (load! dir))))
        v2 (jar/build! staging "sample" :replace {"\"1.0.0\"" "\"2.0.0\""})
        pending (io/file dir "sample.jar.new")]
    (is (= {:file pending :replaced? true :descriptor {:id :test/sample :name "Sample add-on" :version "2.0.0"}}
           (addons/install! (:file v2) dir))
        "written next to the loaded jar, which stays in use")
    (is (.exists pending))
    (is (= :test/sample (::driver/addon (driver/lookup :test/sample-stream))))
    (is (not (loaded? v2 "sample.driver")))
    (unload-addons!)
    (testing "the next start swaps it in and loads it"
      (let [statuses (load! dir)]
        (is (= [[(path v1) "2.0.0" :loaded]] (mapv (juxt :source :version :state) statuses)))
        (is (not (.exists pending)))
        (is (loaded? v2 "sample.driver"))))))

(deftest unload-all-unregisters-and-closes-test
  (let [dir (temp-dir!)
        built (jar/build! dir "sample")
        _ (is (= [:loaded] (mapv :state (load! dir))))
        ^URLClassLoader loader (::driver/loader (driver/lookup :test/sample-stream))]
    (is (some? (.getResource loader "laser-show-addon.edn")))
    (is (= [:test/sample-stream] (addons/unload-all!)) "the ids it unregistered")
    (is (nil? (driver/lookup :test/sample-stream)))
    (is (some? (driver/lookup :idn)) "the built-ins stay")
    (is (some? (driver/lookup :lsps)))
    (is (nil? (.getResource loader "laser-show-addon.edn")) "its loader is closed")
    (testing "the next load-all! (the app started again) loads it again, nothing taken"
      (is (= [[(path built) :loaded]] (mapv (juxt :source :state) (load! dir))))
      (is (some? (driver/lookup :test/sample-stream))))
    (testing "with nothing loaded, nothing to do"
      (addons/unload-all!)
      (is (= [] (addons/unload-all!))))))

(deftest install-writes-a-pending-jar-test
  (let [staging (temp-dir!)
        dir (io/file (temp-dir!) "not yet" "addons")
        built (jar/build! staging "sample" :file-name "Acme Nova.jar")]
    (is (= {:file (io/file dir "Acme Nova.jar.new") :replaced? false
            :descriptor {:id :test/sample :name "Sample add-on" :version "1.0.0"}}
           (addons/install! (:file built) dir))
        "the folder is made, and nothing was there to replace")
    (is (= ["Acme Nova.jar.new"] (mapv #(.getName ^File %) (.listFiles dir))) "no partial file left")
    (is (nil? (driver/lookup :test/sample-stream)) "nothing is loaded")
    (is (not (loaded? built "sample.driver"))))
  (testing "what it refuses, before writing anything"
    (let [staging (temp-dir!)
          dir (temp-dir!)
          notes (io/file staging "notes.txt")
          no-descriptor (jar/build! staging "sample" :file-name "empty.jar" :without ["laser-show-addon.edn"])
          api2 (jar/build! staging "api2")]
      (spit notes "x")
      (doseq [[src message] [[notes "notes.txt is not a .jar file"]
                             [(:file no-descriptor) "No laser-show-addon.edn in empty.jar"]
                             [(:file api2) "Built for output API 2; this app has API 1"]]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo (re-pattern (java.util.regex.Pattern/quote message))
                              (addons/install! src dir))
            message))
      (is (= [] (vec (.listFiles dir)))))))

(deftest crlf-descriptor-reads-test
  (let [dir (temp-dir!)
        descriptor (str "{:id :test/sample\r\n"
                        " :name \"Zoë's output\"\r\n"
                        " :version \"1.0.0\"\r\n"
                        " :api 1\r\n"
                        " :drivers [laser-show-test-addon.sample.driver/driver]}\r\n")
        built (jar/build! dir "sample" :descriptor descriptor)]
    (is (= [{:id :test/sample :name "Zoë's output" :version "1.0.0" :source (path built)
             :state :loaded :drivers [:test/sample-stream]}]
           (mapv #(dissoc % :load-ms) (load! dir)))
        "CRLF, read as UTF-8")))
