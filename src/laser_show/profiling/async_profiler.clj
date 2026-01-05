(ns laser-show.profiling.async-profiler
  "Convenient wrappers for clj-async-profiler for CPU and allocation profiling.
   
   Provides REPL-friendly functions for profiling application performance.
   All flamegraphs are saved to ./profiling-results/ directory.
   
   Quick Start:
   
   ```clojure
   ;; Profile CPU for 10 seconds
   (profile-cpu! 10)
   
   ;; Profile memory allocations for 10 seconds
   (profile-alloc! 10)
   
   ;; Profile a specific code section
   (profile-section! #(your-code-here))
   
   ;; View the most recent flamegraph
   (view-latest!)
   
   ;; Start the web UI to browse all profiles
   (serve-ui! 8080)
   ```"
  (:require [clj-async-profiler.core :as prof]
            [clojure.java.browse :as browse]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; Configuration

(def ^:private default-options
  "Default profiling options for better accuracy."
  {:threads true})

;; CPU Profiling

(defn profile-cpu!
  "Profile CPU usage for the specified duration and generate a flamegraph.
   
   Parameters:
   - duration-sec: How long to profile (in seconds)
   - options: (optional) Additional profiling options map
   
   Returns: Path to the generated flamegraph HTML file.
   
   The flamegraph will be saved to ./profiling-results/results/
   
   Example:
   ```clojure
   ;; Profile for 30 seconds
   (profile-cpu! 30)
   
   ;; Profile with custom options
   (profile-cpu! 10 {:interval 1000000}) ; 1ms sampling interval
   ```"
  ([duration-sec]
   (profile-cpu! duration-sec {}))
  ([duration-sec options]
   (println (format "🔍 Starting CPU profiling for %d seconds..." duration-sec))
   (println "   Interact with the application normally during this time.")
   (let [opts (merge default-options options)
         result (prof/profile opts)]
     (Thread/sleep (* duration-sec 1000))
     (prof/stop opts)
     (println "✅ CPU profiling complete!")
     (println (format "   Flamegraph: %s" result))
     result)))

(defn start-cpu-profiling!
  "Start CPU profiling manually. Call stop-profiling! when done.
   
   Useful when you want to control exactly when profiling starts/stops.
   
   Example:
   ```clojure
   (start-cpu-profiling!)
   ;; ... do something ...
   (stop-profiling!)
   ```"
  ([]
   (start-cpu-profiling! {}))
  ([options]
   (let [opts (merge default-options options)]
     (prof/start opts)
     (println "🔍 CPU profiling started. Call (stop-profiling!) when done."))))

;; Allocation Profiling

(defn profile-alloc!
  "Profile memory allocations for the specified duration and generate a flamegraph.
   
   Parameters:
   - duration-sec: How long to profile (in seconds)
   - options: (optional) Additional profiling options map
   
   Returns: Path to the generated flamegraph HTML file.
   
   The flamegraph will be saved to ./profiling-results/results/
   
   Example:
   ```clojure
   ;; Profile allocations for 30 seconds
   (profile-alloc! 30)
   ```"
  ([duration-sec]
   (profile-alloc! duration-sec {}))
  ([duration-sec options]
   (println (format "🔍 Starting allocation profiling for %d seconds..." duration-sec))
   (println "   Interact with the application normally during this time.")
   (let [opts (merge default-options {:event :alloc} options)
         result (prof/profile opts)]
     (Thread/sleep (* duration-sec 1000))
     (prof/stop opts)
     (println "✅ Allocation profiling complete!")
     (println (format "   Flamegraph: %s" result))
     result)))

(defn start-alloc-profiling!
  "Start allocation profiling manually. Call stop-profiling! when done.
   
   Example:
   ```clojure
   (start-alloc-profiling!)
   ;; ... do something ...
   (stop-profiling!)
   ```"
  ([]
   (start-alloc-profiling! {}))
  ([options]
   (let [opts (merge default-options {:event :alloc} options)]
     (prof/start opts)
     (println "🔍 Allocation profiling started. Call (stop-profiling!) when done."))))

;; Manual Control

(defn stop-profiling!
  "Stop the currently running profiler and generate flamegraph.
   
   Returns: Path to the generated flamegraph HTML file.
   
   Example:
   ```clojure
   (start-cpu-profiling!)
   ;; ... do work ...
   (stop-profiling!)
   ```"
  ([]
   (stop-profiling! {}))
  ([options]
   (let [result (prof/stop (merge default-options options))]
     (println "✅ Profiling stopped!")
     (println (format "   Flamegraph: %s" result))
     result)))

;; Section Profiling

(defn profile-section!
  "Profile a specific section of code (CPU profiling).
   
   Parameters:
   - f: A zero-argument function to profile
   - options: (optional) Additional profiling options map
   
   Returns: The return value of the function.
   
   Example:
   ```clojure
   (profile-section! 
     #(dotimes [i 1000]
        (expensive-computation i)))
   ```"
  ([f]
   (profile-section! f {}))
  ([f options]
   (println "🔍 Profiling code section...")
   (let [opts (merge default-options options)
         result (prof/profile opts (f))]
     (println "✅ Section profiling complete!")
     (println (format "   Flamegraph: %s" (:file result)))
     (:value result))))

(defn profile-alloc-section!
  "Profile memory allocations in a specific section of code.
   
   Parameters:
   - f: A zero-argument function to profile
   - options: (optional) Additional profiling options map
   
   Returns: The return value of the function.
   
   Example:
   ```clojure
   (profile-alloc-section! 
     #(repeatedly 1000 
        (fn [] (vec (range 100)))))
   ```"
  ([f]
   (profile-alloc-section! f {}))
  ([f options]
   (println "🔍 Profiling allocations in code section...")
   (let [opts (merge default-options {:event :alloc} options)
         result (prof/profile opts (f))]
     (println "✅ Allocation profiling complete!")
     (println (format "   Flamegraph: %s" (:file result)))
     (:value result))))

;; Viewing Results

(defn- get-results-dir
  "Get the profiling results directory path."
  []
  (io/file "./profiling-results/results"))

(defn list-profiles
  "List all available profile flamegraphs.
   
   Returns: Vector of maps with :file and :timestamp for each profile.
   
   Example:
   ```clojure
   (list-profiles)
   => [{:file \"01-cpu-flamegraph.html\" :timestamp #inst \"2026-01-04T20:00:00\"}
       {:file \"02-alloc-flamegraph.html\" :timestamp #inst \"2026-01-04T20:05:00\"}]
   ```"
  []
  (let [results-dir (get-results-dir)]
    (when (.exists results-dir)
      (->> (.listFiles results-dir)
           (filter #(str/ends-with? (.getName %) ".html"))
           (map (fn [f]
                  {:file (.getName f)
                   :path (.getAbsolutePath f)
                   :timestamp (java.util.Date. (.lastModified f))}))
           (sort-by :timestamp)
           reverse
           vec))))

(defn get-latest-profile
  "Get the most recent profile flamegraph.
   
   Returns: Map with :file, :path, and :timestamp, or nil if no profiles exist."
  []
  (first (list-profiles)))

(defn view-latest!
  "Open the most recent flamegraph in the default browser.
   
   Example:
   ```clojure
   (view-latest!)
   ```"
  []
  (if-let [profile (get-latest-profile)]
    (do
      (println (format "🌐 Opening flamegraph: %s" (:file profile)))
      (browse/browse-url (str "file://" (:path profile))))
    (println "⚠️  No profiles found. Run a profiling command first.")))

(defn view-profile!
  "Open a specific profile by filename.
   
   Parameters:
   - filename: Name of the HTML file (e.g., \"01-cpu-flamegraph.html\")
   
   Example:
   ```clojure
   (view-profile! \"01-cpu-flamegraph.html\")
   ```"
  [filename]
  (let [profiles (list-profiles)
        profile (first (filter #(= (:file %) filename) profiles))]
    (if profile
      (do
        (println (format "🌐 Opening flamegraph: %s" filename))
        (browse/browse-url (str "file://" (:path profile))))
      (println (format "⚠️  Profile not found: %s" filename)))))

;; Web UI

(defn serve-ui!
  "Start the clj-async-profiler web UI for browsing all profiles.
   
   Parameters:
   - port: Port number to serve on (default: 8080)
   
   The UI will be available at http://localhost:<port>
   
   Example:
   ```clojure
   (serve-ui! 8080)
   ```"
  ([]
   (serve-ui! 8080))
  ([port]
   (prof/serve-ui port)
   (println (format "🌐 Profiler UI started at http://localhost:%d" port))
   (println "   Browse all profiles and generate differential flamegraphs.")))

;; Status

(defn profiler-status
  "Get the current profiler status.
   
   Returns: Map with profiler information including available profiles.
   
   Example:
   ```clojure
   (profiler-status)
   => {:profiling? false
       :profiles-count 5
       :latest-profile \"02-alloc-flamegraph.html\"
       :output-dir \"./profiling-results/\"}
   ```"
  []
  (let [profiles (list-profiles)
        latest (first profiles)]
    {:profiling? (prof/status)
     :profiles-count (count profiles)
     :latest-profile (:file latest)
     :output-dir (System/getProperty "clj-async-profiler.output-dir" "/tmp/clj-async-profiler/")}))

(defn print-status
  "Print the current profiler status to stdout.
   
   Example:
   ```clojure
   (print-status)
   ```"
  []
  (let [{:keys [profiling? profiles-count latest-profile output-dir]} (profiler-status)]
    (println "📊 Profiler Status:")
    (println (format "   Profiling active: %s" (if profiling? "YES" "NO")))
    (println (format "   Profiles saved: %d" profiles-count))
    (when latest-profile
      (println (format "   Latest profile: %s" latest-profile)))
    (println (format "   Output directory: %s" output-dir))))
