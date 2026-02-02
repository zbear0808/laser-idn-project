(ns event-audit
  "Audit script to find unused event handlers and missing handler definitions.
   
   Dynamically scans handler files to discover event namespaces - no manual configuration needed.
   
   Usage from REPL:
     (require '[event-audit :as audit])
     (audit/run-audit!)                    ; Print report to console
     (audit/run-audit! {:save? true})      ; Also save to EDN file
     (audit/run-audit! {:output-dir \"my-dir\"})  ; Custom output directory
   
   Or run specific parts:
     (audit/find-defined-handlers)
     (audit/find-dispatched-events)
     (audit/compare-handlers-and-dispatches)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.pprint :as pp]))

;; =============================================================================
;; Configuration - paths only, no manual namespace lists
;; =============================================================================

(def handlers-dir "src/laser_show/events/handlers")
(def source-dirs ["src/laser_show"])
(def default-output-dir "debug-output")

;; =============================================================================
;; Handler File Discovery
;; =============================================================================

(defn- find-handler-files
  "Recursively find all .clj files in the handlers directory."
  []
  (->> (io/file handlers-dir)
       file-seq
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".clj"))))

(defn- read-file-content
  "Read file content, returning nil on error."
  [file]
  (try
    (slurp file)
    (catch Exception e
      (println "Warning: Could not read file:" (.getPath file) "-" (.getMessage e))
      nil)))

;; =============================================================================
;; Handler Extraction
;; =============================================================================

(defn- extract-case-keywords
  "Extract all namespaced keywords from case statements in a handle function.
   
   Looks for patterns like:
     (case type
       :namespace/event-name (handler-fn event)
       ...)
   
   Returns a set of keywords found as case branches."
  [content]
  (let [;; Find case blocks that dispatch on type/event-type
        ;; Pattern captures everything between 'case type' and the closing empty map {}
        case-pattern #"(?s)\(case\s+(?:type|event/type)\s+([\s\S]*?)\n\s*\{\}"
        ;; Extract namespaced keywords
        keyword-pattern #":([\w-]+)/([\w-]+)"
        case-match (re-find case-pattern content)
        case-body (or (second case-match) "")]
    (->> (re-seq keyword-pattern case-body)
         (map (fn [[_ ns name]]
                (keyword ns name)))
         (into #{}))))

(defn- extract-handler-keywords-from-file
  "Extract all event type keywords that a handler file handles."
  [file]
  (when-let [content (read-file-content file)]
    (let [keywords (extract-case-keywords content)]
      (when (seq keywords)
        {:file (.getName file)
         :path (.getPath file)
         :handlers keywords}))))

(defn find-defined-handlers
  "Find all event types defined in handler files.
   Dynamically scans all .clj files in the handlers directory.
   Returns a map of {:event-type {:file ... :path ...}}"
  []
  (->> (find-handler-files)
       (map extract-handler-keywords-from-file)
       (filter some?)
       (mapcat (fn [{:keys [file path handlers]}]
                 (map (fn [h] [h {:file file :path path}]) handlers)))
       (into {})))

(defn- extract-event-namespaces
  "Extract all unique event namespaces from defined handlers.
   Returns a set of namespace strings like #{\"grid\" \"effects\" \"ui\"}."
  [defined-handlers]
  (->> (keys defined-handlers)
       (map namespace)
       (into #{})))

;; =============================================================================
;; Event Dispatch Extraction
;; =============================================================================

(defn- find-clj-files
  "Recursively find all .clj files in a directory."
  [dir]
  (when (.exists (io/file dir))
    (->> (io/file dir)
         file-seq
         (filter #(.isFile %))
         (filter #(str/ends-with? (.getName %) ".clj")))))

(defn- extract-dispatched-events
  "Extract all namespaced keywords from a file that match the given event namespaces.
   
   Args:
     content - file content string
     file-path - path to file for location tracking
     event-namespaces - set of namespace strings to match
   
   Returns a vector of {:event-type :file :path :line} maps."
  [content file-path event-namespaces]
  (let [keyword-pattern #":([\w-]+)/([\w-]+)"
        matches (re-seq keyword-pattern content)]
    (->> matches
         ;; Filter to only event namespaces we found in handlers
         (filter (fn [[_ ns _]] (contains? event-namespaces ns)))
         ;; Create event info with line numbers
         (map (fn [[full-match ns name]]
                (let [idx (.indexOf content full-match)
                      before-match (when (>= idx 0) (subs content 0 idx))
                      line-num (if before-match
                                 (inc (count (filter #(= % \newline) before-match)))
                                 0)]
                  {:event-type (keyword ns name)
                   :file (-> file-path (str/split #"[/\\]") last)
                   :path file-path
                   :line line-num})))
         (into []))))

(defn find-dispatched-events
  "Find all event types dispatched throughout the codebase.
   Uses event namespaces discovered from handler files.
   Returns a map of {:event-type [{:file ... :path ... :line ...}]}"
  ([] (find-dispatched-events (extract-event-namespaces (find-defined-handlers))))
  ([event-namespaces]
   (let [all-files (mapcat find-clj-files source-dirs)]
     (->> all-files
          (mapcat (fn [file]
                    (when-let [content (read-file-content file)]
                      (extract-dispatched-events content (.getPath file) event-namespaces))))
          (group-by :event-type)
          (into {})))))

;; =============================================================================
;; Heuristic Filters for False Positives
;; =============================================================================

(defn- looks-like-docstring-example?
  "Check if an event appears to be a docstring example.
   Heuristic: keywords with generic names like 'my/', 'some/', 'example/'."
  [event-type]
  (let [ns (namespace event-type)]
    (contains? #{"my" "some" "example" "test" "custom"} ns)))

(defn- only-in-tests?
  "Check if an event is only referenced in test files."
  [dispatch-locations]
  (every? (fn [{:keys [path]}]
            (or (str/includes? path "/test/")
                (str/includes? path "\\test\\")))
          dispatch-locations))

(defn- classify-missing-event
  "Classify why an event might be missing a handler.
   Returns a reason keyword or nil if it's a real missing handler."
  [event-type dispatch-locations]
  (cond
    (looks-like-docstring-example? event-type) :docstring-example
    (only-in-tests? dispatch-locations) :test-only
    :else nil))

;; =============================================================================
;; Analysis and Reporting
;; =============================================================================

(defn compare-handlers-and-dispatches
  "Compare defined handlers with dispatched events.
   Returns {:unused [...] :missing [...] :used [...] :filtered [...]}
   
   Automatically filters out likely false positives (docstring examples, test-only events)."
  ([] (compare-handlers-and-dispatches {}))
  ([_opts]
   (let [defined (find-defined-handlers)
         event-namespaces (extract-event-namespaces defined)
         dispatched (find-dispatched-events event-namespaces)
         defined-set (set (keys defined))
         dispatched-set (set (keys dispatched))
         raw-unused (set/difference defined-set dispatched-set)
         raw-missing (set/difference dispatched-set defined-set)
         used (set/intersection defined-set dispatched-set)
         ;; Classify missing events
         missing-classified (->> raw-missing
                                 (map (fn [evt]
                                        {:event-type evt
                                         :reason (classify-missing-event evt (get dispatched evt))
                                         :dispatched-from (get dispatched evt)})))
         ;; Separate real missing from filtered
         real-missing (filter #(nil? (:reason %)) missing-classified)
         filtered-missing (filter #(some? (:reason %)) missing-classified)]
     {:unused (mapv (fn [evt]
                      {:event-type evt
                       :defined-in (get defined evt)})
                    (sort-by str raw-unused))
      :missing (mapv (fn [{:keys [event-type dispatched-from]}]
                       {:event-type event-type
                        :dispatched-from dispatched-from})
                     (sort-by #(str (:event-type %)) real-missing))
      :filtered (mapv (fn [{:keys [event-type reason dispatched-from]}]
                        {:event-type event-type
                         :reason reason
                         :dispatched-from dispatched-from})
                      (sort-by #(str (:event-type %)) filtered-missing))
      :used (sort-by str used)
      :event-namespaces (sort event-namespaces)
      :stats {:total-defined (count defined-set)
              :total-dispatched (count dispatched-set)
              :unused-count (count raw-unused)
              :missing-count (count real-missing)
              :filtered-count (count filtered-missing)
              :used-count (count used)
              :namespace-count (count event-namespaces)}})))

(defn- format-location
  "Format a file location for display."
  [{:keys [file line]}]
  (if line
    (format "%s:%d" file line)
    file))

(defn print-report
  "Print a formatted report of the analysis."
  [{:keys [unused missing filtered used event-namespaces stats]}]
  (println "\n" (str/join "" (repeat 70 "=")) "\n")
  (println "EVENT HANDLER AUDIT REPORT")
  (println (str/join "" (repeat 70 "=")) "\n")
  
  ;; Stats
  (println "SUMMARY:")
  (println (format "  Total handlers defined:  %d" (:total-defined stats)))
  (println (format "  Total events dispatched: %d" (:total-dispatched stats)))
  (println (format "  Handlers in use:         %d" (:used-count stats)))
  (println (format "  Unused handlers:         %d" (:unused-count stats)))
  (println (format "  Missing handlers:        %d (+ %d filtered)" 
                   (:missing-count stats)
                   (:filtered-count stats 0)))
  (println)
  
  ;; Discovered namespaces
  (println (str/join "" (repeat 70 "-")))
  (println "DISCOVERED EVENT NAMESPACES (from handler files):")
  (println (str/join "" (repeat 70 "-")))
  (println "  " (str/join ", " event-namespaces))
  (println)
  
  ;; Unused handlers
  (println (str/join "" (repeat 70 "-")))
  (println "UNUSED HANDLERS (defined but never dispatched):")
  (println (str/join "" (repeat 70 "-")))
  (if (empty? unused)
    (println "  None - all handlers are in use!")
    (doseq [{:keys [event-type defined-in]} unused]
      (println (format "  %-40s <- %s" 
                       (str event-type) 
                       (:file defined-in)))))
  (println)
  
  ;; Missing handlers
  (println (str/join "" (repeat 70 "-")))
  (println "MISSING HANDLERS (dispatched but no handler found):")
  (println (str/join "" (repeat 70 "-")))
  (if (empty? missing)
    (println "  None - all dispatched events have handlers!")
    (doseq [{:keys [event-type dispatched-from]} missing]
      (println (format "  %-40s" (str event-type)))
      (doseq [loc (take 3 dispatched-from)]
        (println (format "      dispatched from: %s" (format-location loc))))
      (when (> (count dispatched-from) 3)
        (println (format "      ... and %d more locations" (- (count dispatched-from) 3))))))
  (println)
  
  ;; Filtered events (likely false positives)
  (when (seq filtered)
    (println (str/join "" (repeat 70 "-")))
    (println "FILTERED (likely false positives - docstring examples, test-only, etc.):")
    (println (str/join "" (repeat 70 "-")))
    (doseq [{:keys [event-type reason]} filtered]
      (println (format "  %-40s [%s]" (str event-type) (name reason))))
    (println))
  
  (println (str/join "" (repeat 70 "=")))
  (println "END OF REPORT")
  (println (str/join "" (repeat 70 "=")) "\n"))

(defn- generate-timestamp
  "Generate a timestamp string for filenames."
  []
  (let [now (java.time.LocalDateTime/now)
        formatter (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HHmmss")]
    (.format now formatter)))

(defn- ensure-dir-exists
  "Ensure the output directory exists."
  [dir]
  (let [f (io/file dir)]
    (when-not (.exists f)
      (.mkdirs f))))

(defn save-results!
  "Save audit results to an EDN file with pretty printing.
   
   Options:
     :output-dir - directory to save to (default: debug-output)
     :filename   - custom filename (default: event-audit-<timestamp>.edn)
   
   Returns the path to the saved file."
  ([results] (save-results! results {}))
  ([results {:keys [output-dir filename]
             :or {output-dir default-output-dir}}]
   (ensure-dir-exists output-dir)
   (let [timestamp (generate-timestamp)
         fname (or filename (str "event-audit-" timestamp ".edn"))
         path (str output-dir "/" fname)
         file (io/file path)]
     (with-open [w (io/writer file)]
       (pp/pprint results w))
     (println "Results saved to:" path)
     path)))

(defn run-audit!
  "Run the full audit and print the report.
   
   Options:
     :save?      - if true, save results to EDN file (default: false)
     :output-dir - directory for saved file (default: debug-output)
     :filename   - custom filename for saved file
     :quiet?     - if true, skip console report (default: false)
   
   Returns the audit results map."
  ([] (run-audit! {}))
  ([{:keys [save? output-dir filename quiet?]
     :or {save? false quiet? false}}]
   (println "Scanning handler files to discover event namespaces...")
   (let [results (compare-handlers-and-dispatches)]
     (println (format "Found %d event namespaces: %s" 
                      (count (:event-namespaces results))
                      (str/join ", " (:event-namespaces results))))
     (when-not quiet?
       (print-report results))
     (when save?
       (save-results! results {:output-dir (or output-dir default-output-dir)
                               :filename filename}))
     results)))

(defn pprint-results
  "Pretty print the audit results to console."
  [results]
  (pp/pprint results))

;; =============================================================================
;; Detailed Analysis Helpers
;; =============================================================================

(defn show-handler-details
  "Show detailed information about a specific event type."
  [event-type]
  (let [defined (find-defined-handlers)
        event-namespaces (extract-event-namespaces defined)
        dispatched (find-dispatched-events event-namespaces)]
    (println "\nDetails for" event-type)
    (println (str/join "" (repeat 50 "-")))
    (if-let [handler-info (get defined event-type)]
      (println "Defined in:" (:file handler-info))
      (println "NOT DEFINED in any handler"))
    (println)
    (if-let [dispatch-locs (get dispatched event-type)]
      (do
        (println "Dispatched from" (count dispatch-locs) "location(s):")
        (doseq [loc dispatch-locs]
          (println "  -" (format-location loc))))
      (println "NOT DISPATCHED anywhere"))
    (println)))

(defn list-handlers-by-file
  "List all handlers grouped by file."
  []
  (let [defined (find-defined-handlers)]
    (->> defined
         (group-by (fn [[_ v]] (:file v)))
         (sort-by first)
         (mapv (fn [[file handlers]]
                 {:file file
                  :handlers (sort (map first handlers))
                  :count (count handlers)})))))

(defn find-duplicate-handlers
  "Find event types that might be handled in multiple files (potential bugs)."
  []
  (->> (find-handler-files)
       (map extract-handler-keywords-from-file)
       (filter some?)
       (mapcat (fn [{:keys [file handlers]}]
                 (map (fn [h] {:event h :file file}) handlers)))
       (group-by :event)
       (filter (fn [[_ v]] (> (count v) 1)))
       (into {})))

(defn list-handler-files
  "List all discovered handler files."
  []
  (->> (find-handler-files)
       (map #(.getPath %))
       sort))

(comment
  ;; REPL usage examples:
  
  ;; Run full audit (prints to console)
  (run-audit!)
  
  ;; Run audit and save to EDN file
  (run-audit! {:save? true})
  
  ;; Run audit with custom output directory
  (run-audit! {:save? true :output-dir "my-audit-results"})
  
  ;; Run audit quietly (no console output, just save)
  (run-audit! {:save? true :quiet? true})
  
  ;; Get raw results for programmatic use
  (def results (compare-handlers-and-dispatches))
  (:unused results)
  (:missing results)
  (:event-namespaces results)  ; See discovered namespaces
  
  ;; Pretty print results to console
  (pprint-results results)
  
  ;; Save results manually
  (save-results! results)
  (save-results! results {:output-dir "custom-dir" :filename "my-audit.edn"})
  
  ;; Check a specific event
  (show-handler-details :grid/cell-clicked)
  (show-handler-details :file/export)
  
  ;; List handlers by file
  (list-handlers-by-file)
  
  ;; Find potential duplicate handlers
  (find-duplicate-handlers)
  
  ;; List all discovered handler files
  (list-handler-files)
  
  ;; Just get defined handlers
  (find-defined-handlers)
  
  ;; Just get dispatched events
  (find-dispatched-events)
  
  )
