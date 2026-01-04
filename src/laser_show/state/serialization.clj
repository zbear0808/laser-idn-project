(ns laser-show.state.serialization
  "Centralized EDN serialization/deserialization utilities.
   
   Provides consistent, reusable serialization logic for:
   - Drag & Drop data transfer
   - System clipboard operations
   - File-based persistence
   - Network data exchange (future)
   
   This namespace consolidates duplicated serialization code from:
   - laser-show.ui.drag-drop
   - laser-show.state.clipboard
   - laser-show.state.persistent"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.tools.logging :as log]))


;; Core Serialization Functions


(defn serialize
  "Serialize Clojure data to EDN string.
   
   Options:
   - :pretty? - Pretty print with newlines (default false)
   
   Returns: EDN string
   
   Example:
   (serialize {:type :cue :data {:name \"test\"}})
   => \"{:type :cue, :data {:name \\\"test\\\"}}\""
  [data & {:keys [pretty?] :or {pretty? false}}]
  (if pretty?
    (with-out-str (pprint/pprint data))
    (pr-str data)))

(defn deserialize
  "Deserialize EDN string to Clojure data.
   
   Options:
   - :on-error - Error handler fn: (on-error exception) -> value
                 Default returns nil and prints error
   - :default - Default value to return on error (overrides :on-error)
   - :silent? - Don't print errors (default false)
   
   Returns: Deserialized data or error value
   
   Example:
   (deserialize \"{:type :cue}\")
   => {:type :cue}
   
   (deserialize \"invalid\" :default {})
   => {}"
  [edn-str & {:keys [on-error default silent?]}]
  (try
    (edn/read-string {:eof ::eof} edn-str)
    (catch Exception e
      (if default
        default
        (if on-error
          (on-error e)
          (do
            (when-not silent?
              (log/error "Deserialization error:" (.getMessage e))
              (log/debug "Input string (first 100 chars):" (subs (str edn-str) 0 (min 100 (count (str edn-str))))))
            nil))))))


;; Validation & Type Checking


(defn deserialize-with-schema
  "Deserialize and validate against a schema predicate.
   
   Options:
   - :schema-fn - Predicate function: (schema-fn data) -> boolean
                  Required if you want validation
   - :on-error - Error handler for deserialization errors
   - :on-invalid - Handler for schema validation failures
                   Default returns nil and prints error
   - :default - Default value to return on error or invalid data
   
   Returns: Validated data or nil/default
   
   Example:
   (deserialize-with-schema \"{:type :cue}\"
     :schema-fn (fn [data] (and (map? data) (contains? data :type))))
   => {:type :cue}"
  [edn-str & {:keys [schema-fn on-error on-invalid default]}]
  (let [data (deserialize edn-str :on-error on-error :default default)]
    (if (and data schema-fn)
      (if (schema-fn data)
        data
        (if default
          default
          (if on-invalid
            (on-invalid data)
            (do
              (log/warn "Schema validation failed for data:" data)
              nil))))
      data)))

(defn with-type-check
  "Create a schema validator that checks for a specific :type field.
   
   Returns a predicate function suitable for :schema-fn option.
   
   Example:
   (deserialize-with-schema str :schema-fn (with-type-check :cue))"
  [expected-type]
  (fn [data]
    (and (map? data)
         (= (:type data) expected-type))))

(defn with-required-keys
  "Create a schema validator that checks for required keys.
   
   Returns a predicate function suitable for :schema-fn option.
   
   Example:
   (deserialize-with-schema str :schema-fn (with-required-keys #{:type :data}))"
  [required-keys]
  (fn [data]
    (and (map? data)
         (every? #(contains? data %) required-keys))))


;; File Operations


(defn- ensure-parent-dirs!
  "Ensure parent directories exist for the given filepath."
  [filepath]
  (when-let [parent (.getParentFile (io/file filepath))]
    (when-not (.exists parent)
      (.mkdirs parent))))

(defn save-to-file!
  "Serialize data and save to file.
   
   Options:
   - :pretty? - Pretty print (default true for files)
   - :create-dirs? - Create parent directories (default true)
   - :on-error - Error handler: (on-error exception) -> value
   
   Returns: true on success, false on failure
   
   Example:
   (save-to-file! \"config/settings.edn\" {:grid {:cols 8}})"
  [filepath data & {:keys [pretty? create-dirs? on-error]
                    :or {pretty? true create-dirs? true}}]
  (try
    (when create-dirs?
      (ensure-parent-dirs! filepath))
    (let [edn-str (serialize data :pretty? pretty?)]
      (spit filepath edn-str))
    true
    (catch Exception e
      (if on-error
        (on-error e)
        (do
          (log/error "Error saving to file" filepath ":" (.getMessage e))
          false)))))

(defn load-from-file
  "Load and deserialize data from file.
   
   Options:
   - :if-not-found - Value to return if file doesn't exist (default nil)
   - :on-error - Error handler for read/parse errors
   - :schema-fn - Optional validation predicate
   - :default - Default value on any error
   
   Returns: Deserialized data or default value
   
   Example:
   (load-from-file \"config/settings.edn\" :if-not-found {})"
  [filepath & {:keys [if-not-found on-error schema-fn default]
               :or {if-not-found nil}}]
  (let [file (io/file filepath)]
    (if (.exists file)
      (try
        (let [content (slurp file)
              data (deserialize content :on-error on-error :default default)]
          (if schema-fn
            (if (schema-fn data)
              data
              (or default if-not-found))
            data))
        (catch Exception e
          (if on-error
            (on-error e)
            (do
              (log/error "Error loading file" filepath ":" (.getMessage e))
              (or default if-not-found)))))
      if-not-found)))


;; String/Clipboard Operations


(defn serialize-for-clipboard
  "Serialize data for clipboard transfer.
   Uses pretty-printed format so users can see full EDN in clipboard.
   
   Example:
   (serialize-for-clipboard {:type :cell-assignment :preset-id :circle})"
  [data]
  (serialize data :pretty? true))

(defn deserialize-from-clipboard
  "Deserialize clipboard data with optional validation.
   
   Options:
   - :schema-fn - Validation predicate
   - :required-keys - Set of required keys that must be present
   - :on-error - Error handler
   - :default - Default value on error
   
   Returns: Validated data or nil
   
   Example:
   (deserialize-from-clipboard str :required-keys #{:type})"
  [edn-str & {:keys [schema-fn required-keys on-error default]}]
  (let [validator (cond
                    schema-fn schema-fn
                    required-keys (with-required-keys required-keys)
                    :else nil)]
    (if validator
      (deserialize-with-schema edn-str
                               :schema-fn validator
                               :on-error on-error
                               :default default)
      (deserialize edn-str :on-error on-error :default default))))


;; Data Migrations


(defn migrate-effect-enabled-field
 "Ensure all effects have :enabled? field (add if missing, default true).
  Called during project load for forward compatibility.
  
  This handles both old effects (no field) and ensures consistency."
 [state]
 (update-in state [:effects :cells]
   (fn [cells]
     (when cells
       (into {}
         (map (fn [[coord cell-data]]
                [coord
                 (update cell-data :effects
                   (fn [effects]
                     (mapv (fn [effect]
                             (if (contains? effect :enabled?)
                               effect
                               (assoc effect :enabled? true)))
                           effects)))])
              cells))))))
