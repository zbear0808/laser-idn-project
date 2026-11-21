(ns laser-show.output.jar-builder
  "Builds add-on jars for the loader's tests from the fixtures in
   test-resources/addons (see its README.md), with java.util.jar.

   - build! makes a jar from a fixture: the sample's files with the
     fixture's own on top, the placeholder namespace root
     laser-show-test-addon renamed to one of its own for each jar, so no two
     jars share a namespace.
   - write-jar! makes a jar from a map of entries.
   - temp-dir! and delete-tree! for the folders the jars go in."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.jar Attributes$Name JarEntry JarOutputStream Manifest]))

(def fixtures-dir
  "Where the fixture sources are (relative to the repo root, where tests run)."
  (io/file "test-resources" "addons"))

(def ^:private placeholder "laser-show-test-addon")

(def ^:private placeholder-path "laser_show_test_addon")

(defonce ^:private !builds (atom 0))

(defn temp-dir!
  "A new empty temp directory."
  ^File [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn delete-tree!
  "Delete file and everything under it. Best effort: a file another process
   (or a classloader, on Windows) holds open is left behind."
  [^File file]
  (when (.isDirectory file)
    (run! delete-tree! (.listFiles file)))
  (.delete file))

(defn- entry-name
  "The jar entry name of file under root: always / separated, whatever the OS."
  [^File root ^File file]
  (str/join "/" (map str (.relativize (.toPath root) (.toPath file)))))

(defn- fixture-files
  "{entry-name File} of every file in the fixture folder."
  [fixture]
  (let [root (io/file fixtures-dir fixture)]
    (when-not (.isDirectory root)
      (throw (ex-info (str "No add-on fixture " fixture) {:fixture fixture})))
    (into {} (comp (filter #(.isFile ^File %))
                   (map (fn [f] [(entry-name root f) f])))
          (file-seq root))))

(defn write-jar!
  "Write a jar to file with a manifest and entries, {name content}, where
   content is a string (written as UTF-8) or a byte array. Returns file."
  ^File [^File file entries]
  (io/make-parents file)
  (let [manifest (doto (Manifest.)
                   (-> .getMainAttributes (.put Attributes$Name/MANIFEST_VERSION "1.0")))]
    (with-open [out (JarOutputStream. (io/output-stream file) manifest)]
      (doseq [[entry content] (sort-by key entries)]
        (.putNextEntry out (JarEntry. ^String entry))
        (.write out ^bytes (if (string? content) (.getBytes ^String content "UTF-8") content))
        (.closeEntry out))))
  file)

(defn- text-entry? [entry]
  (or (str/ends-with? entry ".clj") (str/ends-with? entry ".edn")))

(defn build!
  "Build the fixture (\"sample\", \"api2\" …) into dir as a jar: the sample's
   files with the fixture's on top, its namespaces renamed to a root of
   their own. Returns {:file File :ns-root \"laser-show-test-addon-7\"}.

   opts:
   - :file-name   the jar's name (default \"<fixture>.jar\")
   - :descriptor  laser-show-addon.edn's text, instead of the fixture's
   - :replace     {from to} replaced in every text file, after the renaming
   - :without     entry names to leave out
   - :extra       {entry content} to add, as write-jar! takes them"
  [dir fixture & {:keys [file-name descriptor replace without extra]}]
  (let [n (swap! !builds inc)
        ns-root (str placeholder "-" n)
        path-root (str placeholder-path "_" n)
        rename #(-> %
                    (str/replace placeholder ns-root)
                    (str/replace placeholder-path path-root))
        files (merge (fixture-files "sample") (when (not= "sample" fixture) (fixture-files fixture)))
        entries (into {}
                      (comp (remove (comp (set without) key))
                            (map (fn [[entry ^File f]]
                                   [(rename entry)
                                    (if (text-entry? entry)
                                      (reduce-kv str/replace (rename (slurp f :encoding "UTF-8")) replace)
                                      (Files/readAllBytes (.toPath f)))])))
                      files)
        entries (cond-> (merge entries extra)
                  descriptor (assoc "laser-show-addon.edn" (rename descriptor)))
        file (io/file dir (or file-name (str fixture ".jar")))]
    {:file (write-jar! file entries)
     :ns-root ns-root}))

(defn fixture-ns
  "The symbol of the namespace that is suffix (\"sample.driver\") in the jar
   build! returned."
  [{:keys [ns-root]} suffix]
  (symbol (str ns-root "." suffix)))
