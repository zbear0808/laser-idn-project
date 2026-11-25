(ns laser-show.test-runner
  "Entry point for the :test, :test-all, :test-timing, :test-integration and
   :test-ui aliases.

   Wraps cognitect.test-runner, so -d/-n/-r/-v/-i/-e work as usual, and adds:

   - name patterns: positional args select the tests whose ns/name contains
     any of them, ignoring case, e.g. `clj -M:test-ui:win tap-tempo`
   - --list: print the selected tests instead of running them
   - --shard K/N: of the selected tests, run only part K of N (see shard),
     so CI can run one suite as N jobs at the same time
   - --skip-dir: never load the namespaces in a directory. The unit aliases
     skip test/laser_show/ui_test, which needs the :test-ui classpath (TestFX).
   - selecting no tests is an error instead of a silent \"Ran 0 tests\"
   - the rig file (state.persistent/default-rig-file) is a temp folder's,
     unless -Dlaser-show.rig-file says otherwise
   - so is the add-ons folder (output.addons/addons-dir), unless
     -Dlaser-show.addons-dir says otherwise

   Directories are skipped here rather than with a default -r because
   cognitect.test-runner ORs -n with -r, so a fixed -r would stop `-n <ns>`
   from narrowing the run."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.namespace.find :as find]
            [cognitect.test-runner :as runner])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- accumulate [m k v]
  (update m k (fnil conj #{}) v))

(defn- parse-shard [s]
  (when-let [[_ k n] (re-matches #"(\d+)/(\d+)" s)]
    [(parse-long k) (parse-long n)]))

(def ^:private cli-options
  (conj runner/cli-options
        ["-l" "--list" "List the selected tests instead of running them."]
        [nil "--shard K/N" "Run only part K of N of the selected tests, e.g. 2/4."
         :parse-fn parse-shard
         :validate [(fn [[k n]] (and k n (<= 1 k n))) "Must be K/N with 1 <= K <= N, e.g. 2/4."]]
        [nil "--skip-dir DIRNAME" "Directory whose namespaces are never loaded."
         :parse-fn str
         :assoc-fn accumulate]))

(defn- namespaces-in [dirs]
  (into (sorted-set) (mapcat #(find/find-namespaces-in-dir (io/file %))) dirs))

(defn- candidate-namespaces
  "Namespaces in the -d dirs minus the --skip-dir ones, narrowed by -n/-r
   the way cognitect.test-runner does it (default: names ending in -test)."
  [{:keys [dir skip-dir] ns-syms :namespace ns-regexes :namespace-regex}]
  (let [skipped (namespaces-in skip-dir)
        regexes (if (or (seq ns-syms) (seq ns-regexes))
                  ns-regexes
                  [#".*\-test$"])]
    (->> (namespaces-in dir)
         (remove skipped)
         (filter #(or (contains? ns-syms %)
                      (some (fn [re] (re-matches re (name %))) regexes))))))

(defn- test-vars [nses]
  (->> nses
       (mapcat (comp vals ns-publics))
       (filter (comp :test meta))
       (sort-by (fn [v]
                  (let [{test-ns :ns line :line} (meta v)]
                    [(str test-ns) (or line 0)])))))

(defn- tag-filter
  "-i/-e: keep tests that have any -i metadata key and none of the -e ones."
  [{:keys [include exclude]}]
  (fn [v]
    (let [m (meta v)]
      (and (or (empty? include) (some m include))
           (not-any? m exclude)))))

(defn- name-filter
  "-v and name patterns. Without either, every test passes."
  [{vars :var} patterns]
  (if (or (seq vars) (seq patterns))
    (let [chosen (set (keep resolve vars))
          patterns (mapv str/lower-case patterns)]
      (fn [v]
        (or (contains? chosen v)
            (let [full-name (str/lower-case (str (symbol v)))]
              (some #(str/includes? full-name %) patterns)))))
    (constantly true)))

(defn shard
  "Part k of n (1-based) of tests: every nth test, from the kth. The tests
   come sorted by namespace and line, so each part takes some of every
   namespace and the parts take about as long as each other, however slow
   one namespace is. Their sizes differ by one test at most."
  [tests k n]
  (into [] (comp (drop (dec k)) (take-nth n)) tests))

(defn- describe [selected eligible patterns [k n]]
  (let [total (count eligible)]
    (str (when (< (count selected) total)
           (str (count selected) " of "))
         total (if (= 1 total) " test" " tests")
         (when (seq patterns)
           (str " matching " (str/join ", " (map pr-str patterns))))
         (when k
           (str ", shard " k "/" n)))))

(defn- print-tests
  "Prints tests grouped by namespace. Expects them sorted by namespace."
  [tests]
  (doseq [group (partition-by (comp :ns meta) tests)]
    (println (str "  " (:ns (meta (first group)))))
    (doseq [v group]
      (println (str "    " (:name (meta v)))))))

(defn- execute
  "Lists or runs the selected tests and returns the process exit code."
  [options patterns]
  (let [dirs (or (:dir options) #{"test"})
        nses (candidate-namespaces (assoc options :dir dirs))
        _ (run! require nses)
        unresolved (remove resolve (:var options))
        eligible (filterv (tag-filter options) (test-vars nses))
        matched (filterv (name-filter options patterns) eligible)
        [k n :as part] (:shard options)
        selected (if part (shard matched k n) matched)]
    (cond
      (seq unresolved)
      (do (println "Could not resolve test var(s):" (str/join ", " unresolved))
          (println "-v needs a fully qualified name (some.ns-test/some-test)."
                   "To match part of a name, pass it without -v.")
          1)

      (empty? matched)
      (do (println (str "No tests selected"
                        (when (seq patterns)
                          (str " by " (str/join ", " (map pr-str patterns))))
                        ". Use --list without a pattern to see all tests."))
          1)

      ;; Only when fewer tests are selected than there are shards: the
      ;; other shards run them.
      (empty? selected)
      (do (println (str "Shard " k "/" n " has no tests: "
                        (describe matched eligible patterns nil) " in all."))
          0)

      (:list options)
      (do (println (str (describe selected eligible patterns part) ":"))
          (print-tests selected)
          0)

      :else
      (do (when (or (seq (:var options)) (seq patterns) part)
            (println (str "Selected " (describe selected eligible patterns part) ":"))
            (print-tests selected))
          (let [{:keys [fail error]}
                (runner/test {:dir dirs
                              :namespace (set (map (comp ns-name :ns meta) selected))
                              :var (set (map symbol selected))})]
            (if (zero? (+ fail error)) 0 1))))))

(defn- print-help [summary]
  (println "\nUSAGE:\n")
  (println "  clj -M:<test alias> [options] [name-pattern ...]\n")
  (println "Name patterns select the tests whose namespace/name contains any of them,")
  (println "ignoring case. For example:\n")
  (println "  clj -M:test-ui:win tap-tempo    UI tests with \"tap-tempo\" in the name")
  (println "  clj -M:test-ui:win --list       list the UI tests without running them")
  (println "  clj -M:test-ui:win --shard 2/4  the second of 4 parts of the UI tests")
  (println "  clj -M:win:test history         unit tests with \"history\" in the name\n")
  (println summary)
  (println "\nAll options may be repeated for a logical OR effect.")
  (println "If neither -n nor -r is supplied, namespaces ending in '-test' are used."))

(defn- isolate-rig-file!
  "Point the JVM property laser-show.rig-file (state.persistent/default-rig-file)
   at a fresh temp folder unless it is set, so no test can reach the user's
   own rig file (~/LaserShowProjects/rig.edn)."
  []
  (when (str/blank? (System/getProperty "laser-show.rig-file"))
    (let [dir (.toFile (Files/createTempDirectory "laser-show-test-rig" (make-array FileAttribute 0)))]
      (.deleteOnExit dir)
      (System/setProperty "laser-show.rig-file" (str (io/file dir "rig.edn"))))))

(defn- isolate-addons-dir!
  "Point the JVM property laser-show.addons-dir (output.addons/addons-dir) at
   a fresh temp folder unless it is set, so no test can reach the user's own
   add-ons (~/LaserShowProjects/addons). The folder itself isn't made: a
   missing one is what a machine without add-ons has."
  []
  (when (str/blank? (System/getProperty "laser-show.addons-dir"))
    (let [dir (.toFile (Files/createTempDirectory "laser-show-test-addons" (make-array FileAttribute 0)))]
      (.deleteOnExit dir)
      (System/setProperty "laser-show.addons-dir" (str (io/file dir "addons"))))))

(defn -main [& args]
  (isolate-rig-file!)
  (isolate-addons-dir!)
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (cond
      errors (do (run! println errors)
                 (print-help summary)
                 (System/exit 1))
      (:test-help options) (print-help summary)
      :else (System/exit (execute options arguments)))))
