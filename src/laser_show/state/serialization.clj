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
            [clojure.tools.logging :as log])
  (:import [java.util.zip ZipOutputStream ZipInputStream ZipEntry ZipFile CRC32]
           [java.io ByteArrayOutputStream ByteArrayInputStream PushbackReader StringReader]
           [java.nio ByteBuffer]
           [java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets]))


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
      (cond
        default default
        on-error (on-error e)
        :else (do
                (when-not silent?
                  (log/error "Deserialization error:" (.getMessage e))
                  (log/debug "Input string (first 100 chars):" (subs (str edn-str) 0 (min 100 (count (str edn-str))))))
                nil)))))


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
    (cond
      ;; No data or no schema - return data as-is
      (not (and data schema-fn)) data
      ;; Schema validates - return data
      (schema-fn data) data
      ;; Schema fails - use fallback
      default default
      on-invalid (on-invalid data)
      :else (do
              (log/warn "Schema validation failed for data:" data)
              nil))))

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
    (if-not (.exists file)
      if-not-found
      (try
        (let [content (slurp file)
              data (deserialize content :on-error on-error :default default)]
          (cond
            (not schema-fn) data
            (schema-fn data) data
            :else (or default if-not-found)))
        (catch Exception e
          (cond
            on-error (on-error e)
            :else (do
                    (log/error "Error loading file" filepath ":" (.getMessage e))
                    (or default if-not-found))))))))


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
   - :on-invalid - Handler for schema validation failures
   - :default - Default value on error
   
   Returns: Validated data or nil
   
   Example:
   (deserialize-from-clipboard str :required-keys #{:type})"
  [edn-str & {:keys [schema-fn required-keys on-error on-invalid default]}]
  (let [validator (cond
                    schema-fn schema-fn
                    required-keys (with-required-keys required-keys)
                    :else nil)]
    (if validator
      (deserialize-with-schema edn-str
                               :schema-fn validator
                               :on-error on-error
                               :on-invalid on-invalid
                               :default default)
      (deserialize edn-str :on-error on-error :default default))))


;; ZIP File Operations


(defn save-to-zip!
  "Save multiple files to a zip archive.
   
   Parameters:
   - zip-path: Path to the zip file to create
   - files: Map of {filename data} where data will be serialized to EDN
   
   Options:
   - :pretty? - Pretty print EDN (default true)
   - :create-dirs? - Create parent directories (default true)
   - :on-error - Error handler: (on-error exception) -> value
   
   Returns: true on success, false on failure
   
   Example:
   (save-to-zip! \"project.zip\"
                 {\"metadata.edn\" {:config {...}}
                  \"hardware.edn\" {:projectors {...}}})"
  [zip-path files & {:keys [pretty? create-dirs? on-error]
                     :or {pretty? true create-dirs? true}}]
  (try
    (when create-dirs?
      (ensure-parent-dirs! zip-path))
    
    (with-open [file-out (io/output-stream zip-path)
                zip-out (ZipOutputStream. file-out)]
      (doseq [[filename data] files]
        (let [entry (ZipEntry. filename)
              edn-str (serialize data :pretty? pretty?)]
          (.putNextEntry zip-out entry)
          (.write zip-out (.getBytes edn-str "UTF-8"))
          (.closeEntry zip-out))))
    true
    (catch Exception e
      (if on-error
        (on-error e)
        (do
          (log/error "Error saving to zip" zip-path ":" (.getMessage e))
          false)))))

(defn- utf-8-text
  "bytes as UTF-8 text. Throws on bytes that aren't UTF-8, instead of
   putting replacement characters in their place."
  ^String [^bytes bytes]
  (try
    (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
                  (ByteBuffer/wrap bytes)))
    (catch CharacterCodingException _
      (throw (ex-info "it isn't UTF-8 text" {})))))

(def ^:private end-of-text
  "What read-one-edn's reads return at the end of the text. Only this
   object is it: no form in a file can be."
  (Object.))

(defn- read-one-edn
  "The EDN form that the member entry-name's bytes are: UTF-8 text with
   exactly one form, then nothing but whitespace and comments. The standard
   tags (#inst, #uuid) read; any other tag, an empty member, a form cut short
   or a second form throws, naming entry-name."
  [entry-name bytes]
  (let [reader (PushbackReader. (StringReader. (utf-8-text bytes)))
        read-next #(edn/read {:eof end-of-text} reader)
        form (read-next)]
    (cond
      (identical? end-of-text form)
      (throw (ex-info (str entry-name " is empty") {:entry entry-name}))

      (not (identical? end-of-text (read-next)))
      (throw (ex-info (str entry-name " has more than one form") {:entry entry-name}))

      :else form)))

(defn- entry-bytes
  "All of entry's bytes, read to its end, with the size and CRC-32 its zip
   records for them."
  ^bytes [^ZipFile zip ^ZipEntry entry]
  (let [bytes (with-open [in (.getInputStream zip entry)]
                (.readAllBytes in))]
    (cond
      (not= (.getSize entry) (alength bytes))
      (throw (ex-info (str "it is " (alength bytes) " bytes, its zip says " (.getSize entry)) {}))

      (not= (.getCrc entry) (.getValue (doto (CRC32.) (.update bytes))))
      (throw (ex-info "its CRC-32 isn't the one its zip records" {}))

      :else bytes)))

(defn- cause-text [^Throwable t]
  (or (ex-message t) (.getName (class t))))

(defn- load-strict
  "load-from-zip :strict?: {name form} of the members named in filenames
   (every file member when nil), or throws ex-info with :zip-path, and
   :entry when one member is the reason."
  [zip-path filenames]
  (try
    (with-open [zip (ZipFile. (io/file zip-path))]
      (let [entries (filterv (fn [^ZipEntry e]
                               (and (not (.isDirectory e))
                                    (or (nil? filenames) (contains? filenames (.getName e)))))
                             (enumeration-seq (.entries zip)))]
        (when-let [twice (some (fn [[entry-name n]] (when (> n 1) entry-name))
                               (frequencies (map #(.getName ^ZipEntry %) entries)))]
          (throw (ex-info (str twice " is in the zip more than once") {:entry twice})))
        (into {}
              (map (fn [^ZipEntry e]
                     (let [entry-name (.getName e)]
                       (try
                         [entry-name (read-one-edn entry-name (entry-bytes zip e))]
                         (catch Exception t
                           (throw (ex-info (if (= entry-name (:entry (ex-data t)))
                                             (cause-text t)
                                             (str entry-name ": " (cause-text t)))
                                           {:entry entry-name}
                                           t)))))))
              entries)))
    (catch Exception t
      (throw (ex-info (cause-text t)
                      (merge (select-keys (ex-data t) [:entry]) {:zip-path (str zip-path)})
                      t)))))

(defn load-from-zip
  "Load files from a zip archive and deserialize EDN content.
   
   Parameters:
   - zip-path: Path to the zip file to read
   
   Options:
   - :if-not-found - Value to return if file doesn't exist (default nil)
   - :on-error - Error handler for read/parse errors (not with :strict?)
   - :filenames - Optional set of filenames to load (loads all if not specified)
   - :strict? - Read a project file (state.persistent/read-project): the zip
     by its central directory, and each member named in :filenames read to
     its end, with the size and CRC-32 the zip records, as exactly one EDN
     form (read-one-edn). A member that isn't, a name the zip holds twice,
     or a file that isn't a whole zip throws ex-info: its :zip-path, and its
     :entry when one member is the reason. Never a partial map, and nothing
     in place of a member that can't be read. Other members (folders, a
     thumbnail) aren't read at all.
   
   Returns: Map of {filename data} or default value
   
   Example:
   (load-from-zip \"project.zip\")
   => {\"metadata.edn\" {:config {...}}, \"hardware.edn\" {:projectors {...}}}"
  [zip-path & {:keys [if-not-found on-error filenames strict?]
               :or {if-not-found nil}}]
  (let [file (io/file zip-path)]
    (cond
      (not (.exists file))
      if-not-found

      strict?
      (load-strict zip-path filenames)

      :else
      (try
        (with-open [file-in (io/input-stream zip-path)
                    zip-in (ZipInputStream. file-in)]
          (loop [result {}]
            (if-let [entry (.getNextEntry zip-in)]
              (let [filename (.getName entry)]
                (if (or (nil? filenames) (contains? filenames filename))
                  (let [baos (ByteArrayOutputStream.)]
                    (io/copy zip-in baos)
                    (let [content (.toString baos "UTF-8")
                          data (deserialize content :on-error on-error)]
                      (recur (assoc result filename data))))
                  (recur result)))
              result)))
        (catch Exception e
          (cond
            on-error (on-error e)
            :else (do
                    (log/error "Error loading from zip" zip-path ":" (.getMessage e))
                    if-not-found)))))))


