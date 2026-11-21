(ns laser-show.output.driver
  "The output driver registry (plans/2026-11-19-output-drivers/01 §1): a
   driver is a plain map of data and functions that sends a frame to one
   kind of device. Each projector names its driver (:driver, absent = :idn).

   - The registry is a runtime atom {driver-id driver}. The built-ins
     register when their namespace loads, add-ons at startup. A reload of
     this namespace starts it empty.
   - descriptors is the pure-data copy app state holds
     ([:backend :output :drivers], :addons/loaded), so handlers, subs and
     views never see a function.
   - projector-config gives the config a projector's driver opens with.
   - coerce-field and coerce-config check what is typed into a driver's
     fields (01 §5.4); summary is the line a card shows; clamp-scan-rate
     keeps a scan rate in a driver's :capabilities range. All pure, over
     descriptors.
   - The built-in drivers are registered at the end of this namespace."
  (:require [clojure.string :as str]
            [laser-show.idn.driver :as idn-driver]
            [laser-show.idn.output-config :as output-config]
            [laser-show.idn.stream :as stream]
            [laser-show.output.lsps :as lsps]))

(def ^:private api-version
  "The output API the drivers here are checked against."
  1)

(def ^:private transports
  "What :transport may be. Only used to group drivers in the UI."
  #{:network :usb :other})

(def ^:private field-types
  "The field spec types (01 §5.4)."
  #{:host :string :int :choice :boolean})

(def ^:private required-keys
  [:id :name :api :transport :fields :open :send-frame! :close!])

(def ^:private required-fns
  [:open :send-frame! :close!])

(def ^:private optional-fns
  [:ready? :on-error :discover :describe :status])

(def ^:private !drivers
  "{driver-id driver}: every registered driver."
  (atom {}))


;; Checks

(defn- field-problems
  "What's wrong with the field spec at index i of :fields."
  [i spec]
  (if-not (map? spec)
    [(str "field " i " is not a map")]
    (let [{:keys [key label type options min max]} spec
          what (if (keyword? key) (str "field " key) (str "field " i))]
      (cond-> []
        (not (keyword? key)) (conj (str what ": :key must be a keyword"))
        (not (string? label)) (conj (str what ": :label must be a string"))
        (not (contains? field-types type)) (conj (str what ": :type must be one of :host :string :int :choice :boolean"))
        (and (= :choice type) (not (and (sequential? options) (seq options))))
        (conj (str what ": a :choice needs :options"))
        (and (= :int type) (contains? spec :min) (not (number? min)))
        (conj (str what ": :min must be a number"))
        (and (= :int type) (contains? spec :max) (not (number? max)))
        (conj (str what ": :max must be a number"))))))

(defn- fields-problems [fields]
  (if-not (vector? fields)
    [":fields must be a vector of field specs"]
    (let [ks (keep #(when (map? %) (:key %)) fields)]
      (into (into [] (comp (map-indexed field-problems) cat) fields)
            (for [[k n] (frequencies ks) :when (< 1 n)]
              (str "field " k " appears " n " times"))))))

(defn validate
  "What's wrong with driver against the contract (01 §1, §5.4): a vector
   of problem strings, empty when it is valid."
  [driver]
  (if-not (map? driver)
    ["a driver must be a map"]
    (let [missing (filterv #(not (contains? driver %)) required-keys)
          has? #(contains? driver %)]
      (cond-> (mapv #(str "missing " %) missing)
        (and (has? :id) (not (keyword? (:id driver))))
        (conj ":id must be a keyword")

        (and (has? :name) (not (string? (:name driver))))
        (conj ":name must be a string")

        (and (has? :short-name) (not (and (string? (:short-name driver))
                                           (not (str/blank? (:short-name driver))))))
        (conj ":short-name must be a non-blank string")

        (and (has? :api) (not= api-version (:api driver)))
        (conj (str ":api must be " api-version))

        (and (has? :transport) (not (contains? transports (:transport driver))))
        (conj ":transport must be one of :network :usb :other")

        (has? :fields)
        (into (fields-problems (:fields driver)))

        true
        (into (for [k (concat required-fns optional-fns)
                    :when (and (has? k) (not (fn? (get driver k))))]
                (str k " must be a function")))))))

(defn validate-addon-driver
  "validate, and an add-on's driver id must be qualified (OD-9), so it can't
   take a built-in's or another add-on's."
  [driver]
  (cond-> (validate driver)
    (and (map? driver) (keyword? (:id driver)) (not (qualified-keyword? (:id driver))))
    (conj "add-on driver ids must be qualified keywords")))


;; The registry

(defn register!
  "Add driver to the registry. Throws an ex-info with :problems when it is
   invalid or its id is taken. Returns driver."
  [driver]
  (let [problems (validate driver)]
    (when (seq problems)
      (throw (ex-info (str "Invalid output driver " (pr-str (:id driver)))
                      {:driver (:id driver) :problems problems})))
    (let [id (:id driver)
          [old _] (swap-vals! !drivers #(if (contains? % id) % (assoc % id driver)))]
      (when (contains? old id)
        (let [problem (str "Driver id " id " is already taken")]
          (throw (ex-info problem {:driver id :problems [problem]}))))
      driver)))

(defn unregister!
  "Take the driver with id out of the registry (for tests)."
  [id]
  (swap! !drivers dissoc id)
  nil)

(defn lookup
  "The registered driver with id, or nil."
  [id]
  (get @!drivers id))

(defn registered
  "Every registered driver map."
  []
  (vec (vals @!drivers)))


;; Descriptors (pure data for app state)

(defn- descriptor [driver]
  (cond-> {:id (:id driver)
           :name (:name driver)
           :transport (:transport driver)
           :fields (:fields driver)
           :capabilities (:capabilities driver)
           :discover? (fn? (:discover driver))
           :addon (::addon driver)}
    (:short-name driver) (assoc :short-name (:short-name driver))))

(defn descriptors
  "{id {:id :name :transport :fields :capabilities :discover? :addon}} of
   every registered driver, and :short-name when the driver has one: no
   functions, so it can go into app state. :addon is the add-on that brought
   the driver (its ::addon), nil for a built-in."
  []
  (into {} (map (fn [[id d]] [id (descriptor d)])) @!drivers))


;; Projectors

(defn field-defaults
  "{key default} of the field specs that have a :default."
  [fields]
  (into {} (keep (fn [{:keys [key] :as spec}]
                   (when (contains? spec :default) [key (:default spec)])))
        fields))

(defn projector-driver-id
  "The id of the driver projector uses: its :driver, :idn without one."
  [projector]
  (:driver projector :idn))

(defn- idn-config
  "The config IDN opens with, as multi-engine/create-engine-for-projector
   builds it: port 7255 and service 0 by default, the channel the service,
   the output config from :output-config's bit depths (8/16 for a missing
   one), else output-config/default-config."
  [projector]
  (let [service-id (or (:service-id projector) 0)]
    {:host (:host projector)
     :port (or (:port projector) 7255)
     :service-id service-id
     :channel-id service-id
     :output-config (if-let [cfg (:output-config projector)]
                      (output-config/make-config (or (:color-bit-depth cfg) 8)
                                                 (or (:xy-bit-depth cfg) 16))
                      output-config/default-config)}))

(defn projector-config
  "[driver-id config] for projector (01 §5.2). IDN's config comes from the
   projector's own keys and needs no registered driver; any other driver's
   is its :driver-config over its field defaults. Throws \"Output driver
   :acme/nova is not installed\" for a driver the registry doesn't have
   (OD-10)."
  [projector]
  (let [id (projector-driver-id projector)]
    (if (= :idn id)
      [:idn (idn-config projector)]
      (if-let [driver (lookup id)]
        [id (merge (field-defaults (:fields driver)) (:driver-config projector))]
        (throw (ex-info (str "Output driver " id " is not installed") {:driver id}))))))


;; Fields (01 §5.4), the summary line, the scan rate (pure, over descriptors)

(defn- blank-input? [input]
  (or (nil? input) (and (string? input) (str/blank? input))))

(defn- range-text [min max]
  (cond
    (and min max) (str " must be " min "–" max)
    min (str " must be at least " min)
    :else (str " must be at most " max)))

(defn- coerce-int [{:keys [label min max]} input]
  (let [n (cond
            (integer? input) (long input)
            (string? input) (when (re-matches #"[+-]?\d{1,18}" (str/trim input))
                              (parse-long (str/trim input)))
            :else nil)]
    (cond
      (nil? n) {:error (str label " must be a whole number")}
      (or (and (number? min) (< n (long min))) (and (number? max) (> n (long max))))
      {:error (str label (range-text min max))}
      :else {:ok n})))

(defn- coerce-choice [{:keys [options]} input]
  (if-let [[v] (some (fn [[v :as option]] (when (or (= v input) (and (string? input) (= (str v) input))) option))
                     options)]
    {:ok v}
    {:error (str "Not one of " (str/join ", " (map second options)))}))

(defn- coerce-boolean [{:keys [label]} input]
  (cond
    (boolean? input) {:ok input}
    (= "true" input) {:ok true}
    (= "false" input) {:ok false}
    :else {:error (str label " must be on or off")}))

(defn coerce-field
  "What input (typed text, or a value) is for the field spec (01 §5.4):
   {:ok v} or {:error text}. A blank input is {:error \"Host is required\"}
   for a :required? field, else {:ok nil} (unset: its :default applies).
   :host and :string are trimmed; :int is a whole number in :min–:max;
   :choice one of its :options' values; :boolean true or false."
  [{:keys [type label required?] :as spec} input]
  (if (and (blank-input? input) (not (boolean? input)))
    (if required? {:error (str label " is required")} {:ok nil})
    (case type
      (:host :string) (if (string? input) {:ok (str/trim input)} {:error (str label " must be text")})
      :int (coerce-int spec input)
      :choice (coerce-choice spec input)
      :boolean (coerce-boolean spec input)
      {:error (str label " has an unknown type")})))

(defn coerce-config
  "What inputs ({key typed-text-or-value}) give for fields: {:config {key v}
   :errors {key text}}. A field with no input (or a blank one) takes its
   :default; one that is :required? without a default is an error. Keys no
   field has are left out."
  [fields inputs]
  (reduce (fn [acc {:keys [key] :as spec}]
            (let [input (get inputs key)
                  result (if (and (blank-input? input) (contains? spec :default))
                           {:ok (:default spec)}
                           (coerce-field spec input))]
              (cond
                (contains? result :error) (assoc-in acc [:errors key] (:error result))
                (some? (:ok result)) (assoc-in acc [:config key] (:ok result))
                :else acc)))
          {:config {} :errors {}}
          fields))

(defn display-name
  "What a projector card calls driver id: the descriptor's :short-name, else
   its :name; for a driver that isn't installed, its id without the colon
   (\"acme/nova\")."
  [descriptor id]
  (or (:short-name descriptor) (:name descriptor) (subs (str id) 1)))

(defn default-projector-name
  "What an unnamed projector of driver id is called when it is added:
   display-name and the first number from 1 that no projector in projectors
   ({id projector}) is named (\"LSPS 1\", \"LSPS 2\" …), so its card doesn't
   repeat its settings."
  [projectors descriptor id]
  (let [base (display-name descriptor id)
        taken (into #{} (keep :name) (vals projectors))]
    (first (remove taken (map #(str base " " %) (iterate inc 1))))))

(defn- summary-values
  "The :summary? fields' values of config, as text: a :host field followed
   by a :port is one \"host:port\"."
  [fields config]
  (let [shown (filterv #(and (:summary? %) (some? (get config (:key %)))) fields)]
    (loop [[f & more] shown
           out []]
      (if-not f
        out
        (let [v (str (get config (:key f)))
              nxt (first more)]
          (if (and (= :host (:type f)) nxt (= :port (:key nxt)))
            (recur (rest more) (conj out (str v ":" (get config :port))))
            (recur more (conj out v))))))))

(defn summary-parts
  "[name details] of summary: for IDN [its address nil], for any other
   driver [its display name, its :summary? fields as one line, or nil
   without any], for one that isn't installed [\"acme/nova\" \"not
   installed\"]. A card shows them on two lines."
  [projector descriptors]
  (let [id (projector-driver-id projector)]
    (if (= :idn id)
      (let [{:keys [host service-id]} projector]
        [(if (and (integer? service-id) (pos? service-id)) (str host " #" service-id) (str host)) nil])
      (if-let [d (get descriptors id)]
        (let [values (summary-values (:fields d) (merge (field-defaults (:fields d)) (:driver-config projector)))]
          [(display-name d id) (when (seq values) (str/join " · " values))])
        [(display-name nil id) "not installed"]))))

(defn summary
  "One line for projector on a card or in the projector settings: for IDN,
   its address as before (\"10.0.0.7 #1\"); for any other driver, its name
   and the :summary? fields (\"LSPS · 127.0.0.1:7266\"), the field defaults
   filled in; for one whose driver isn't installed (not in descriptors),
   \"acme/nova · not installed\"."
  [projector descriptors]
  (str/join " · " (remove nil? (summary-parts projector descriptors))))

(defn clamp-scan-rate
  "rate (pts/s) through stream/clamp-scan-rate, then into the driver's
   :capabilities :scan-rate :min–:max when its descriptor says one (01 §1);
   nil unless rate is a finite number."
  [descriptor rate]
  (when-let [r (stream/clamp-scan-rate rate)]
    (let [{lo :min hi :max} (get-in descriptor [:capabilities :scan-rate])]
      (cond-> (long r)
        (number? lo) (max (long lo))
        (number? hi) (min (long hi))))))

(defn default-scan-rate
  "The scan rate a new projector of the driver starts with: its
   :capabilities :scan-rate :default, else stream/DEFAULT_SCAN_RATE, kept in
   its range."
  [descriptor]
  (clamp-scan-rate descriptor (or (get-in descriptor [:capabilities :scan-rate :default])
                                  stream/DEFAULT_SCAN_RATE)))


;; Driver status (:status, 01 §1)

(def ^:const MAX_STATUS_STRING
  "The longest string a driver's :status keeps; longer ones are cut."
  200)

(def ^:const MAX_STATUS_ENTRIES
  "The most entries a vector or map in a driver's :status :info keeps."
  64)

(def ^:const MAX_STATUS_DEPTH
  "How deep collections nest in :info (the :info map itself is level 1);
   a deeper one is dropped."
  4)

(def ^:private status-levels #{:ok :warning :error})

(defn cut-status-string
  "s cut to MAX_STATUS_STRING characters."
  [^String s]
  (if (> (count s) MAX_STATUS_STRING) (subs s 0 MAX_STATUS_STRING) s))

(declare plain-edn)

(defn- plain-entries
  "xs as plain EDN, the ones that aren't dropped, at most
   MAX_STATUS_ENTRIES."
  [xform xs]
  (into [] (comp xform (remove #{::drop}) (take MAX_STATUS_ENTRIES)) xs))

(defn- plain-edn
  "x as plain EDN at nesting level depth, or ::drop: nil, booleans,
   keywords, strings (cut to MAX_STATUS_STRING), finite numbers (a ratio or
   decimal as a double), and vectors and maps (keyword or string keys) of
   those, MAX_STATUS_ENTRIES at most, MAX_STATUS_DEPTH deep."
  [x depth]
  (cond
    (or (nil? x) (boolean? x) (keyword? x)) x
    (string? x) (cut-status-string x)
    (integer? x) x
    (number? x) (let [d (double x)] (if (Double/isFinite d) d ::drop))
    (> (long depth) MAX_STATUS_DEPTH) ::drop
    (vector? x) (plain-entries (map #(plain-edn % (inc (long depth)))) x)
    (map? x) (into {} (plain-entries (keep (fn [[k v]]
                                             (when (or (keyword? k) (string? k))
                                               (let [v (plain-edn v (inc (long depth)))]
                                                 (if (= ::drop v) ::drop [(if (string? k) (cut-status-string k) k) v])))))
                                     x))
    :else ::drop))

(defn sanitize-status
  "What a driver's :status returned, as plain data for app state:
   {:level :ok|:warning|:error :message \"…\" :info {…}} with only the
   keys that are valid. :level must be one of the three; :message a
   non-blank string (cut to MAX_STATUS_STRING); :info a map, made plain
   EDN by dropping anything that isn't (functions, atoms, objects, sets,
   lists, NaN, symbol keys …). nil when nothing valid is left, or when it
   isn't a map."
  [status]
  (when (map? status)
    (let [{:keys [level message info]} status
          info (when (map? info) (plain-edn info 1))
          result (cond-> {}
                   (status-levels level) (assoc :level level)
                   (and (string? message) (not (str/blank? message))) (assoc :message (cut-status-string message))
                   (and (map? info) (seq info)) (assoc :info info))]
      (not-empty result))))


;; Calling into a driver

(defn with-loader
  "(f) with driver's own classloader (::loader, an add-on's) as the thread's
   context classloader, so Clojure finds the add-on's namespaces; the old
   one is put back afterwards. Without a loader, just (f)."
  [driver f]
  (if-let [^ClassLoader loader (::loader driver)]
    (let [thread (Thread/currentThread)
          old (.getContextClassLoader thread)]
      (.setContextClassLoader thread loader)
      (try
        (f)
        (finally
          (.setContextClassLoader thread old))))
    (f)))


;; Built-in drivers

;; IDN (idn/driver.clj)
(register! idn-driver/driver)

;; LSPS (01 §3)
(register! lsps/driver)
