(ns event-audit
  "Audit script to find unused event handlers and missing handler definitions.
   
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
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.pprint :as pp]))

;; =============================================================================
;; Configuration


;; =============================================================================

(def handlers-dir "src/laser_show/events/handlers")
(def source-dirs ["src/laser_show"])
(def test-dirs ["test"])

;; Event types to ignore in the "missing handlers" report
;; These are typically docstring examples or intentional test cases
(def ignored-missing-events
  #{;; Docstring examples - these appear in component documentation
    :my/cancel-edit :my/item-click :my/set-name :my/start-edit 
    :my/tab-change :my/update-param :some/event
    ;; Test-only events - intentionally testing unknown event handling
    :keyframe/unknown-type :modulator/unknown-type :zone-groups/unknown-event
    :custom/set-items})

;; Event types to ignore in the "unused handlers" report
;; These may be called via effects or are planned features
(def ignored-unused-events
  #{;; Called via effect system, not direct dispatch
    :fx/event})

;; =============================================================================
;; Handler Extraction
;; =============================================================================

(defn- read-file-forms
  "Read all forms from a Clojure file, returning them as a sequence.
   Uses a simple regex-based approach to avoid reader macro issues."
  [file]
  (try
    (slurp file)
    (catch Exception e
      (println "Warning: Could not read file:" (.getPath file) "-" (.getMessage e))
      nil)))

(defn- extract-case-keywords
  "Extract keywords from case statements in a handle function.
   Looks for patterns like:
     (case type
       :namespace/event-name (handler-fn event)
       ...)
   
   Strategy: Find the (defn handle ...) function and extract all namespaced
   keywords that appear as case branches (keywords at the start of a line
   or after whitespace in the case body)."
  [content]
  (let [;; Look for (defn handle or (defn- handle followed by case type
        ;; Then extract all namespaced keywords that look like event types
        ;; We look for keywords that match the domain patterns used in handlers
        handle-fn-pattern #"(?s)\(defn-?\s+handle\s+.*?\(case\s+(?:type|event/type)(.*?)\n\s*;;\s*Unknown"
        ;; Fallback: just find case type blocks
        case-pattern #"(?s)\(case\s+(?:type|event/type)\s+([\s\S]*?)\n\s*\{\}"
        ;; Extract keywords from case branches - namespaced keywords
        keyword-pattern #":([\w-]+)/([\w-]+)"]
    (let [;; Try to find handle function first
          handle-match (re-find handle-fn-pattern content)
          case-match (re-find case-pattern content)
          case-body (or (second handle-match) (second case-match) "")]
      (->> (re-seq keyword-pattern case-body)
           (map (fn [[_ ns name]]
                  (keyword ns name)))
           (into #{})))))

(defn- extract-handler-keywords-from-file
  "Extract all event type keywords that a handler file handles."
  [file]
  (when-let [content (read-file-forms file)]
    (let [keywords (extract-case-keywords content)]
      (when (seq keywords)
        {:file (.getName file)
         :path (.getPath file)
         :handlers keywords}))))

(defn find-defined-handlers
  "Find all event types defined in handler files.
   Returns a map of {:event-type {:file ... :path ...}}"
  []
  (let [handler-files (->> (io/file handlers-dir)
                           file-seq
                           (filter #(.isFile %))
                           (filter #(str/ends-with? (.getName %) ".clj")))]
    (->> handler-files
         (map extract-handler-keywords-from-file)
         (filter some?)
         (mapcat (fn [{:keys [file path handlers]}]
                   (map (fn [h] [h {:file file :path path}]) handlers)))
         (into {}))))

;; =============================================================================
;; Event Dispatch Extraction
;; =============================================================================

;; Event domains that we care about - these are the namespaces used in event handlers
(def event-domains
  #{"grid" "effects" "effect-chain" "cue-chain" "projectors" "zone-groups"
    "timing" "transport" "ui" "preview" "project" "idn" "config"
    "file" "edit" "view" "help" "chain" "list" "modulator" "keyframe" "input"})

(defn- extract-dispatched-events
  "Extract all namespaced keywords from a file that match our event domains.
   
   Simple approach: find ALL namespaced keywords in the file that have a namespace
   matching one of our event domains. This catches all patterns including:
     - {:event/type :namespace/event-name}
     - :on-change-event :namespace/event-name
     - (if condition :namespace/event-a :namespace/event-b)
     - etc."
  [content file-path]
  (let [;; Find all namespaced keywords
        keyword-pattern #":([\w-]+)/([\w-]+)"
        matches (re-seq keyword-pattern content)]
    (->> matches
         ;; Filter to only event domains we care about
         (filter (fn [[_ ns _]] (contains? event-domains ns)))
         ;; Create event info
         (map (fn [[full-match ns name]]
                (let [;; Find line number for this match
                      idx (.indexOf content full-match)
                      before-match (when (>= idx 0) (subs content 0 idx))
                      line-num (if before-match
                                 (inc (count (filter #(= % \newline) before-match)))
                                 0)]
                  {:event-type (keyword ns name)
                   :file (-> file-path (str/split #"[/\\]") last)
                   :path file-path
                   :line line-num})))
         (into []))))

(defn- find-clj-files
  "Recursively find all .clj files in a directory."
  [dir]
  (when (.exists (io/file dir))
    (->> (io/file dir)
         file-seq
         (filter #(.isFile %))
         (filter #(str/ends-with? (.getName %) ".clj")))))

(defn find-dispatched-events
  "Find all event types dispatched throughout the codebase.
   Only scans source files, NOT test files (to avoid false positives from test data).
   Returns a map of {:event-type [{:file ... :path ... :line ...}]}"
  []
  (let [;; Only scan source files, not test files
        all-files (mapcat find-clj-files source-dirs)]
    (->> all-files
         (mapcat (fn [file]
                   (when-let [content (read-file-forms file)]
                     (extract-dispatched-events content (.getPath file)))))
         (group-by :event-type)
         (into {}))))

;; =============================================================================
;; Analysis and Reporting
;; =============================================================================

(defn compare-handlers-and-dispatches
  "Compare defined handlers with dispatched events.
   Returns {:unused [...] :missing [...] :used [...] :ignored-unused [...] :ignored-missing [...]}
   
   Options:
     :include-ignored? - if true, includes ignored events in unused/missing (default false)"
  ([] (compare-handlers-and-dispatches {}))
  ([{:keys [include-ignored?] :or {include-ignored? false}}]
   (let [defined (find-defined-handlers)
         dispatched (find-dispatched-events)
         defined-set (set (keys defined))
         dispatched-set (set (keys dispatched))
         raw-unused (set/difference defined-set dispatched-set)
         raw-missing (set/difference dispatched-set defined-set)
         used (set/intersection defined-set dispatched-set)
         ;; Separate ignored from actionable
         ignored-unused (set/intersection raw-unused ignored-unused-events)
         ignored-missing (set/intersection raw-missing ignored-missing-events)
         ;; Filter out ignored unless requested
         unused (if include-ignored? 
                  raw-unused 
                  (set/difference raw-unused ignored-unused-events))
         missing (if include-ignored?
                   raw-missing
                   (set/difference raw-missing ignored-missing-events))]
     {:unused (mapv (fn [evt]
                      {:event-type evt
                       :defined-in (get defined evt)})
                    (sort-by str unused))
      :missing (mapv (fn [evt]
                       {:event-type evt
                        :dispatched-from (get dispatched evt)})
                     (sort-by str missing))
      :ignored-unused (mapv (fn [evt]
                              {:event-type evt
                               :defined-in (get defined evt)})
                            (sort-by str ignored-unused))
      :ignored-missing (mapv (fn [evt]
                               {:event-type evt
                                :dispatched-from (get dispatched evt)})
                             (sort-by str ignored-missing))
      :used (sort-by str used)
      :stats {:total-defined (count defined-set)
              :total-dispatched (count dispatched-set)
              :unused-count (count unused)
              :missing-count (count missing)
              :ignored-unused-count (count ignored-unused)
              :ignored-missing-count (count ignored-missing)
              :used-count (count used)}})))

(defn- format-location
  "Format a file location for display."
  [{:keys [file path line]}]
  (if line
    (format "%s:%d" file line)
    file))

(defn print-report
  "Print a formatted report of the analysis."
  [{:keys [unused missing ignored-unused ignored-missing used stats]}]
  (println "\n" (str/join "" (repeat 70 "=")) "\n")
  (println "EVENT HANDLER AUDIT REPORT")
  (println (str/join "" (repeat 70 "=")) "\n")
  
  ;; Stats
  (println "SUMMARY:")
  (println (format "  Total handlers defined:  %d" (:total-defined stats)))
  (println (format "  Total events dispatched: %d" (:total-dispatched stats)))
  (println (format "  Handlers in use:         %d" (:used-count stats)))
  (println (format "  Unused handlers:         %d (+ %d ignored)" 
                   (:unused-count stats) 
                   (:ignored-unused-count stats 0)))
  (println (format "  Missing handlers:        %d (+ %d ignored)"
                   (:missing-count stats)
                   (:ignored-missing-count stats 0)))
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
  
  ;; Note about ignored events
  (when (or (seq ignored-unused) (seq ignored-missing))
    (println (str/join "" (repeat 70 "-")))
    (println "IGNORED (docstring examples, test events, etc.):")
    (println (str/join "" (repeat 70 "-")))
    (when (seq ignored-unused)
      (println "  Unused:" (str/join ", " (map #(str (:event-type %)) ignored-unused))))
    (when (seq ignored-missing)
      (println "  Missing:" (str/join ", " (map #(str (:event-type %)) ignored-missing))))
    (println "  (Edit ignored-*-events sets in event_audit.clj to customize)")
    (println))
  
  (println (str/join "" (repeat 70 "=")))
  (println "END OF REPORT")
  (println (str/join "" (repeat 70 "=")) "\n"))

(def default-output-dir "debug-output")

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
   (println "Scanning codebase for event handlers and dispatches...")
   (let [results (compare-handlers-and-dispatches)]
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
        dispatched (find-dispatched-events)]
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
  (let [handler-files (->> (io/file handlers-dir)
                           file-seq
                           (filter #(.isFile %))
                           (filter #(str/ends-with? (.getName %) ".clj")))]
    (->> handler-files
         (map extract-handler-keywords-from-file)
         (filter some?)
         (mapcat (fn [{:keys [file handlers]}]
                   (map (fn [h] {:event h :file file}) handlers)))
         (group-by :event)
         (filter (fn [[_ v]] (> (count v) 1)))
         (into {}))))

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
  
  ;; Just get defined handlers
  (find-defined-handlers)
  
  ;; Just get dispatched events
  (find-dispatched-events)
  
  )
