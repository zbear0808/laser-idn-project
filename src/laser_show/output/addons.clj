(ns laser-show.output.addons
  "Output drivers from add-on jars (plans/2026-11-19-output-drivers/01 §4).

   - addons-dir: where the jars go (the laser-show.addons-dir JVM property,
     else ~/LaserShowProjects/addons).
   - load-all! loads them once, at startup: it swaps in pending installs,
     checks each add-on's laser-show-addon.edn (read as EDN, never
     evaluated), gives each jar its own parent-first URLClassLoader,
     requires its driver namespaces, validates the drivers and registers all
     of an add-on's drivers or none. It returns a status per add-on, as data.
   - install! copies a jar in as <name>.jar.new, which the next load-all!
     swaps in (a loaded jar is locked on Windows).

   Nothing here touches app state or the UI: app/start! passes the statuses
   on in :addons/loaded."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [laser-show.output.driver :as driver])
  (:import [clojure.lang DynamicClassLoader ExceptionInfo]
           [java.io File PushbackReader]
           [java.net URI URL URLClassLoader URLConnection URLStreamHandler URLStreamHandlerFactory]
           [java.nio.file AtomicMoveNotSupportedException CopyOption Files StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.util Locale]
           [java.util.jar JarFile]))

(def descriptor-name
  "The descriptor's name, at the root of an add-on jar (01 §4.1)."
  "laser-show-addon.edn")

(def ^:private app-api
  "The output API this app has."
  1)

(def ^:private supported-apis
  "The output APIs an add-on may be built for."
  #{1})

(def ^:private oses #{:macos :windows :linux})

(def ^:private arches #{:aarch64 :x86-64})

(def ^:private os-names {:macos "macOS" :windows "Windows" :linux "Linux" :other "this OS"})


;; The folder and its files (01 §4.4)

(defn addons-dir
  "The add-ons folder: the laser-show.addons-dir JVM property if it is set,
   else LaserShowProjects/addons in the user's home, next to
   persistent/default-projects-dir."
  ^File []
  (let [prop (System/getProperty "laser-show.addons-dir")]
    (if (str/blank? prop)
      (io/file (System/getProperty "user.home") "LaserShowProjects" "addons")
      (io/file prop))))

(defn- lower-name [^File file]
  (.toLowerCase (.getName file) Locale/ROOT))

(defn- hidden-name?
  "Starts with a dot: .hidden.jar, and macOS's ._name.jar AppleDouble files."
  [^File file]
  (str/starts-with? (.getName file) "."))

(defn addon-file?
  "Whether file counts as an add-on jar: a file (not a folder) whose name
   ends in .jar, in any case, and doesn't start with a dot."
  [^File file]
  (and (.isFile file)
       (not (hidden-name? file))
       (str/ends-with? (lower-name file) ".jar")))

(defn- pending-file?
  "A <name>.jar.new that install! wrote."
  [^File file]
  (and (.isFile file)
       (not (hidden-name? file))
       (str/ends-with? (lower-name file) ".jar.new")))

(defn- files-in
  "The files in dir that pass keep?, sorted by name."
  [^File dir keep?]
  (sort-by #(.getName ^File %) (filter keep? (.listFiles dir))))


;; Platforms (01 §4.4)

(defn current-platform
  "{:os :arch} of this machine, from os.name and os.arch, or of the ones
   given: :os is :macos, :windows, :linux or :other, :arch :aarch64,
   :x86-64 or :other."
  ([] (current-platform (System/getProperty "os.name") (System/getProperty "os.arch")))
  ([os-name os-arch]
   (let [os-name (str os-name)]
     {:os (cond (str/starts-with? os-name "Mac") :macos
                (str/starts-with? os-name "Windows") :windows
                (= "Linux" os-name) :linux
                :else :other)
      :arch (case (str os-arch)
              ("aarch64" "arm64") :aarch64
              ("amd64" "x86_64") :x86-64
              :other)})))

(defn supports-platform?
  "Whether an add-on with :platforms platforms runs on platform. Each entry
   is an OS keyword or an [os arch] pair; nil means everywhere."
  [platforms {:keys [os arch]}]
  (or (nil? platforms)
      (boolean (some #(if (vector? %) (= % [os arch]) (= % os)) platforms))))

(defn- known-platform? [p]
  (or (contains? oses p)
      (and (vector? p) (= 2 (count p)) (contains? oses (first p)) (contains? arches (second p)))))

(defn- platform-name [p]
  (if (vector? p)
    (str (os-names (first p)) " (" (name (second p)) ")")
    (os-names p)))

(defn- join-and [names]
  (if (< (count names) 2)
    (str/join names)
    (str (str/join ", " (butlast names)) " and " (last names))))

(defn- skip-reason [platforms {:keys [os arch]}]
  (str "Not available on " (platform-name [os arch]) "; it supports "
       (join-and (mapv platform-name platforms))))


;; Descriptors (01 §4.1)

(defn- read-descriptor
  "Read the descriptor from in as UTF-8 EDN, so nothing in it is evaluated.
   {:descriptor d} or {:error …}."
  [in label]
  (try
    (with-open [r (PushbackReader. (io/reader in :encoding "UTF-8"))]
      (let [d (edn/read {:eof ::empty} r)]
        (if (= ::empty d)
          {:error (str "Bad " descriptor-name " in " label ": it is empty")}
          {:descriptor d})))
    (catch Exception e
      {:error (str "Can't read " descriptor-name " in " label ": " (ex-message e))})))

(defn- descriptor-problem
  "What's wrong with descriptor d, or nil."
  [d label]
  (let [bad (str "Bad " descriptor-name " in " label ": ")
        {:keys [id api drivers platforms]} d]
    (cond
      (not (map? d))
      (str bad "it must be a map")

      (not (qualified-keyword? id))
      (str bad ":id must be a qualified keyword")

      (not (integer? api))
      (str bad ":api must be the output API it was built for (" app-api ")")

      (not (contains? supported-apis api))
      (str "Built for output API " api "; this app has API " app-api)

      (not (and (vector? drivers) (seq drivers) (every? qualified-symbol? drivers)))
      (str bad ":drivers must be a vector of qualified symbols, the vars holding its drivers")

      (and (some? platforms) (not (and (vector? platforms) (seq platforms))))
      (str bad ":platforms must be a vector of platforms")

      :else
      (when-let [p (first (remove known-platform? platforms))]
        (str "Unknown platform " (pr-str p))))))

(defn- descriptor-keys
  "The descriptor's keys a status carries."
  [d]
  (when (map? d)
    (select-keys d [:id :name :version])))

(defn- bundled
  "What jar entry names show a jar bundles that the app provides: :clojure,
   :laser-show or nil. Any entry under laser_show/ counts, an add-on's own
   laser-show.* namespace too: those names are the app's (G8)."
  [entry-names]
  (cond
    (some #(= "clojure/core__init.class" %) entry-names) :clojure
    (some #(str/starts-with? % "laser_show/") entry-names) :laser-show))

(defn- read-jar
  "Open jar file and read what the checks need: {:descriptor d :bundles b},
   or {:error …}."
  [^File file]
  (let [label (.getName file)]
    (try
      (with-open [jf (JarFile. file)]
        (if-let [entry (.getJarEntry jf descriptor-name)]
          (let [read (read-descriptor (.getInputStream jf entry) label)]
            (cond-> read
              (:descriptor read)
              (assoc :bundles (bundled (map #(.getName ^java.util.jar.JarEntry %)
                                            (enumeration-seq (.entries jf)))))))
          {:error (str "No " descriptor-name " in " label)}))
      (catch Exception e
        {:error (str "Can't read " label ": " (ex-message e))}))))

(defn- bundles-problem [bundles label]
  (case bundles
    :clojure (str label " bundles Clojure; build it with Clojure as a provided dependency")
    :laser-show (str label " has files under laser_show/: add-on namespaces must not start with laser-show., "
                     "and laser-show must be a provided dependency, not bundled")
    nil))


;; Classloaders

(def ^:private uncached-jar-handlers
  "Opens the jar: URLs of an add-on's resources (its sources, as Clojure
   reads them) without the JDK's jar cache, so closing the add-on's loader
   really closes its jar file: Windows locks a jar while it is open."
  (reify URLStreamHandlerFactory
    (createURLStreamHandler [_ protocol]
      (when (= "jar" protocol)
        (proxy [URLStreamHandler] []
          (openConnection [^URL url]
            (doto ^URLConnection (.openConnection (.toURL (URI. (.toExternalForm url))))
              (.setUseCaches false))))))))

(defn- jar-loader
  "A jar's own classloader. Its URL comes from File.toURI, which escapes
   spaces and non-ASCII characters. Lookups go to parent first."
  ^URLClassLoader [^File file ^ClassLoader parent]
  (URLClassLoader. (into-array URL [(.toURL (.toURI file))]) parent uncached-jar-handlers))

(defn- root-message [^Throwable e]
  (let [root (last (take-while some? (iterate #(.getCause ^Throwable %) e)))]
    (or (ex-message root) (.getName (class root)))))

(defn- forget-failed-load!
  "Forget that ns-sym was loaded when it has no namespace: one whose load
   threw (after its ns form had marked it loaded, and Clojure removed it)
   would otherwise be skipped by require when the add-ons load again in the
   same JVM (app/stop! then start), and fail as \"No such namespace\" instead
   of with its own error."
  [ns-sym]
  (when-not (find-ns ns-sym)
    (dosync (commute @#'clojure.core/*loaded-libs* disj ns-sym))))

(defn- resolve-drivers
  "Require each driver var's namespace with loader as the context
   classloader (and Clojure's loader, which a REPL binds), and deref the
   var. {:drivers [[sym value] …]} or {:error …}."
  [syms ^ClassLoader loader]
  (let [thread (Thread/currentThread)
        old (.getContextClassLoader thread)]
    (.setContextClassLoader thread loader)
    (try
      (with-bindings {clojure.lang.Compiler/LOADER (DynamicClassLoader. loader)}
        (reduce (fn [acc sym]
                  (let [ns-sym (symbol (namespace sym))]
                    (forget-failed-load! ns-sym)
                    (if-let [error (try (require ns-sym) nil
                                        (catch Throwable e
                                          (str "Could not load " ns-sym ": " (root-message e))))]
                      (reduced {:error error})
                      (if-let [v (find-var sym)]
                        (update acc :drivers conj [sym @v])
                        (reduced {:error (str "Driver var " sym " is not defined")})))))
                {:drivers []}
                syms))
      (finally
        (.setContextClassLoader thread old)))))


;; Drivers

(defn- driver-problem
  "What's wrong with the driver in var sym, given the ids before it in the
   same add-on, or nil."
  [[sym d] ids-before]
  (let [id (when (map? d) (:id d))
        taken (when (keyword? id) (driver/lookup id))]
    (cond
      taken
      (str "Driver id " id " is already taken by "
           (if-let [addon (::driver/addon taken)] (pr-str addon) "a built-in driver"))

      (and id (contains? ids-before id))
      (str "Driver id " id " appears twice in this add-on")

      :else
      (when-let [problems (seq (driver/validate-addon-driver d))]
        (str "Driver " (if (keyword? id) id sym) ": " (str/join ", " problems))))))

(defn- drivers-problem
  "What's wrong with an add-on's drivers, [[sym value] …], or nil."
  [drivers]
  (let [{:keys [problems]} (reduce (fn [{:keys [ids] :as acc} [_ d :as entry]]
                                     (cond-> (update acc :ids conj (when (map? d) (:id d)))
                                       (driver-problem entry ids) (update :problems conj (driver-problem entry ids))))
                                   {:ids #{} :problems []}
                                   drivers)]
    (when (seq problems)
      (str/join "; " problems))))

(defn- register-all!
  "Register every driver, or none: {:ids […]} or {:error …}."
  [drivers]
  (loop [[d & more] drivers done []]
    (if-not d
      {:ids done}
      (let [error (try (driver/register! d) nil
                       (catch ExceptionInfo e
                         (str "Driver " (:id d) ": " (str/join ", " (:problems (ex-data e))))))]
        (if error
          (do (run! driver/unregister! done)
              {:error error})
          (recur more (conj done (:id d))))))))


;; Loading (01 §4.2)

(defn- ms-since [start-ns]
  (quot (- (System/nanoTime) start-ns) 1000000))

(defn- move! [^File from ^File to]
  (Files/move (.toPath from) (.toPath to) (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))

(defn- swap-pending!
  "Rename every <name>.jar.new over its <name>.jar (step 1). A status for
   each one that fails."
  [^File dir]
  (into []
        (keep (fn [^File f]
                (let [n (.getName f)
                      target (io/file dir (subs n 0 (- (count n) (count ".new"))))]
                  (try
                    (move! f target)
                    (log/info "Add-on" (.getName target) "replaced by its pending install")
                    nil
                    (catch Exception e
                      (log/warn "Could not swap in" (.getAbsolutePath f) (ex-message e))
                      {:source (.getAbsolutePath f) :state :failed
                       :error (str "Can't install " n ": " (ex-message e))})))))
        (files-in dir pending-file?)))

(defn- classpath-sources [^ClassLoader loader]
  (when loader
    (for [^URL url (enumeration-seq (.getResources loader descriptor-name))]
      {:source (str url) :label (str url) :url url :loader loader})))

(defn- jar-sources [^File dir parent]
  (for [^File f (files-in dir addon-file?)]
    {:source (.getAbsolutePath f) :label (.getName f) :file f :parent parent}))

(defn- check-source
  "Read and check a source's descriptor (step 3), before anything in it is
   loaded: {:descriptor d} to go on, or {:status …} to stop."
  [{:keys [source label file url]} platform loaded]
  (let [{:keys [descriptor bundles error]} (if file
                                             (read-jar file)
                                             (try (read-descriptor (.openStream ^URL url) label)
                                                  (catch Exception e
                                                    {:error (str "Can't read " label ": " (ex-message e))})))
        status #(merge (descriptor-keys descriptor) {:source source :state %1 :error %2})]
    (if error
      {:status {:source source :state :failed :error error}}
      (if-let [problem (or (descriptor-problem descriptor label)
                           (bundles-problem bundles label))]
        {:status (status :failed problem)}
        (cond
          (not (supports-platform? (:platforms descriptor) platform))
          {:status (status :skipped (skip-reason (:platforms descriptor) platform))}

          (contains? loaded (:id descriptor))
          {:status (status :failed (str "Add-on " (:id descriptor) " is already loaded from "
                                        (get loaded (:id descriptor))))}

          :else
          {:descriptor descriptor})))))

(defn- load-source
  "Load a checked source's drivers (steps 4-6): its status."
  [{:keys [source file loader parent]} descriptor]
  (let [loader (if file (jar-loader file parent) loader)
        status #(merge (descriptor-keys descriptor) {:source source :state %1 :error %2})
        result (let [{:keys [drivers error]} (resolve-drivers (:drivers descriptor) loader)]
                 (if error
                   {:error error}
                   (or (when-let [problem (drivers-problem drivers)] {:error problem})
                       (register-all! (for [[_ d] drivers]
                                        (cond-> (assoc d ::driver/loader loader ::driver/addon (:id descriptor))
                                          file (assoc ::jar-loader? true)))))))]
    (if-let [error (:error result)]
      (do (when file (.close ^URLClassLoader loader))
          (status :failed error))
      (merge (descriptor-keys descriptor) {:source source :state :loaded :drivers (:ids result)}))))

(defn- load-one
  "The status of one source. Never throws (A-3)."
  [source platform loaded]
  (let [start (System/nanoTime)
        status (try
                 (let [{:keys [status descriptor]} (check-source source platform loaded)]
                   (or status (load-source source descriptor)))
                 (catch Throwable e
                   {:source (:source source) :state :failed
                    :error (str "Could not load " (:label source) ": " (root-message e))}))
        ms (ms-since start)]
    (case (:state status)
      :loaded (log/info "Add-on" (:id status) (:version status) "loaded from" (:source status)
                        "in" ms "ms, drivers" (:drivers status))
      :skipped (log/info "Add-on" (:id status) "skipped:" (:error status) (str "(" (:source status) ")"))
      (log/warn "Add-on from" (:source status) "failed in" ms "ms:" (:error status)))
    (cond-> status
      (= :loaded (:state status)) (assoc :load-ms ms))))

(defn- ensure-dir!
  "Make the folder (step 0). nil, or a status saying why it can't be made."
  [^File dir]
  (try
    (Files/createDirectories (.toPath dir) (make-array FileAttribute 0))
    nil
    (catch Exception e
      (log/warn "Could not create the add-ons folder" (.getAbsolutePath dir) (ex-message e))
      {:source (.getAbsolutePath dir) :state :failed
       :error (str "Can't create the add-ons folder: " (ex-message e))})))

(defn load-all!
  "Load every add-on (01 §4.2) and return a status for each one tried
   (01 §4.3), in order: the classpath's descriptors, then the folder's jars
   by file name. A failed or skipped add-on never stops the others, and
   this never throws.

   opts:
   - :dir               the add-ons folder (default addons-dir)
   - :platform          {:os :arch} to load for (default current-platform)
   - :classpath-loader  the classloader whose laser-show-addon.edn resources
                        load first, with it as their loader (default the
                        context classloader; nil for none)
   - :parent-loader     the parent of each jar's own loader (default the
                        context classloader)"
  [{:keys [dir platform parent-loader] :as opts}]
  (let [context (.getContextClassLoader (Thread/currentThread))
        dir (io/file (or dir (addons-dir)))
        platform (or platform (current-platform))
        dir-status (ensure-dir! dir)
        swap-statuses (when-not dir-status (swap-pending! dir))
        sources (concat (classpath-sources (get opts :classpath-loader context))
                        (when-not dir-status (jar-sources dir (or parent-loader context))))]
    (:statuses
     (reduce (fn [{:keys [loaded] :as acc} source]
               (let [status (load-one source platform loaded)]
                 (cond-> (update acc :statuses conj status)
                   (= :loaded (:state status)) (assoc-in [:loaded (:id status)] (:source status)))))
             {:statuses (into (if dir-status [dir-status] []) swap-statuses)
              :loaded {}}
             sources))))


;; Installing

(defn install!
  "Copy the add-on jar src into dir as <name>.jar.new, which the next
   load-all! swaps in: a loaded jar can't be replaced on Windows. Makes dir
   if it is missing. Returns {:file the .jar.new, :replaced? whether a jar
   of that name is already there, :descriptor its :id :name :version}.
   Throws an ex-info, writing nothing, when src isn't a .jar or its
   descriptor doesn't pass load-all!'s checks."
  [src dir]
  (let [^File src (io/file src)
        ^File dir (io/file dir)
        n (.getName src)
        fail #(throw (ex-info % {:file (.getAbsolutePath src)}))]
    (when-not (str/ends-with? (lower-name src) ".jar")
      (fail (str n " is not a .jar file")))
    (let [{:keys [descriptor bundles error]} (read-jar src)]
      (when-let [problem (or error (descriptor-problem descriptor n) (bundles-problem bundles n))]
        (fail problem))
      (Files/createDirectories (.toPath dir) (make-array FileAttribute 0))
      (let [target (io/file dir (str n ".new"))
            part (io/file dir (str n ".new.part"))
            replaced? (.exists (io/file dir n))]
        (try
          (Files/copy (.toPath src) (.toPath part) (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
          (try
            (Files/move (.toPath part) (.toPath target)
                        (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
            (catch AtomicMoveNotSupportedException _
              (move! part target)))
          (finally
            (Files/deleteIfExists (.toPath part))))
        (log/info "Add-on" (:id descriptor) (:version descriptor) "installed as" (.getAbsolutePath target))
        {:file target :replaced? replaced? :descriptor (descriptor-keys descriptor)}))))


;; Unloading

(defn unload-all!
  "Unregister every add-on driver and close the classloader of each one
   from a jar (a classpath add-on's loader is the app's). Only for
   when no engine runs: app/stop!, after the output runtime is drained, so
   the next start (a REPL restart, the next UI test) loads the add-ons again
   instead of finding their ids taken. A namespace an add-on loaded stays
   loaded in the JVM. Returns the ids it unregistered."
  []
  (into []
        (keep (fn [d]
                (when (::driver/addon d)
                  (driver/unregister! (:id d))
                  (let [loader (::driver/loader d)]
                    (when (and (::jar-loader? d) (instance? URLClassLoader loader))
                      (try
                        (.close ^URLClassLoader loader)
                        (catch Exception e
                          (log/warn "Could not close the classloader of add-on" (::driver/addon d) (ex-message e))))))
                  (:id d))))
        (sort-by (comp str :id) (driver/registered))))
