(ns laser-show.output.discovery
  "Scan (plans/2026-11-19-output-drivers/01 §5.3): IDN's own discovery and
   every registered driver's :discover, all at once, each in its own future.

   - interfaces reads the machine's network interfaces as data, and
     broadcast-addresses picks one broadcast address per interface that is
     up and not loopback from that data (1D's G10), so a test can give it
     made-up interfaces.
   - scan runs the discoverers and puts their devices together, IDN's first:
     {:devices [...] :warnings [\"Acme Nova: libnova not found\" …]}, or
     {:error <IDN's message>} when every discoverer failed. A driver's
     device with a shape the core can't use is left out, with a warning.

   The call and the reading of its result run under the driver's classloader
   in its future. All discoverers, including IDN, share the scan's deadline."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [laser-show.idn.hello :as idn-hello]
            [laser-show.output.driver :as driver])
  (:import [java.net Inet4Address InterfaceAddress NetworkInterface SocketException]))

(def ^:const TIMEOUT_MS
  "How long a scan's discoverers listen: IDN's scan time, and the
   :timeout-ms every driver's :discover gets."
  3000)

;; Broadcast addresses (1D's G10)

(defn- interface-data [^NetworkInterface ni]
  (try
    {:name (.getName ni) :up? (.isUp ni) :loopback? (.isLoopback ni)
     :broadcasts (into [] (keep (fn [^InterfaceAddress a]
                                  (when (instance? Inet4Address (.getAddress a))
                                    (some-> (.getBroadcast a) .getHostAddress))))
                       (.getInterfaceAddresses ni))}
    (catch SocketException _ nil))) ; an interface can disappear during enumeration

(defn interfaces
  "This machine's network interfaces, in NetworkInterface order, as data:
   [{:name \"en0\" :up? true :loopback? false :broadcasts [\"192.168.1.255\"]}].
   :broadcasts are its IPv4 addresses' broadcast addresses. Only reads the
   OS's list; [] when it can't."
  []
  (try
    (into [] (keep interface-data) (some-> (NetworkInterface/getNetworkInterfaces) enumeration-seq))
    (catch Exception e
      (log/warn "Could not list the network interfaces:" (.getMessage e))
      [])))

(defn broadcast-addresses
  "One broadcast address per interface that is up and not loopback, from
   interfaces (as interfaces gives them), without the ones that have none,
   each once, in interface order: [\"192.168.1.255\" \"10.0.0.255\"], or []."
  [interfaces]
  (into []
        (comp (filter :up?) (remove :loopback?) (keep #(first (filter string? (:broadcasts %)))) (distinct))
        interfaces))


;; IDN

(defn- idn-devices
  "IDN's discovery (IDN-Hello: a broadcast, then each device's service
   map), each device as the rest of the app knows it, with :driver :idn."
  [{:keys [broadcast-address timeout-ms]}]
  (log/info (format "Scanning network for IDN devices (broadcast: %s)..." broadcast-address))
  (let [devices (mapv (fn [device]
                        {:address (:address device)
                         :host-name (let [name (:host-name device)]
                                      (if (str/blank? name) (:address device) name))
                         :unit-id (:unit-id device)
                         :port (or (:port device) 7255)
                         :status (:status device)
                         :protocol-version (:protocol-version device)
                         :services (vec (:services device))
                         :relays (vec (:relays device))
                         :driver :idn})
                      (idn-hello/discover-devices-with-services broadcast-address timeout-ms 1000))]
    (log/info (format "Found %d IDN device(s)" (count devices)))
    (doseq [device devices]
      (log/debug (format "  Device %s: %d service(s)" (:address device) (count (:services device)))))
    devices))


;; A driver's devices (01 §5.3's shape)

(defn- plain?
  "Is x plain data that app state and the rig file keep as it is?"
  [x]
  (cond
    (or (nil? x) (boolean? x) (string? x) (keyword? x) (symbol? x) (char? x)
        (integer? x) (decimal? x) (ratio? x) (uuid? x) (inst? x)) true
    (float? x) (Double/isFinite (double x))
    (record? x) false
    (map? x) (every? (fn [[k v]] (and (plain? k) (plain? v))) x)
    (or (vector? x) (set? x) (list? x)) (every? plain? x)
    :else false))

(defn- key-value? [v]
  (or (integer? v) (and (string? v) (not (str/blank? v)))))

(defn- output-problem [outputs]
  (cond
    (not (and (sequential? outputs) (seq outputs))) "it has no :outputs"
    (not-every? #(and (map? %) (key-value? (:output-id %))) outputs) "an output has no :output-id"
    (some #(not (or (nil? (:name %)) (string? (:name %)))) outputs) "an output's :name isn't text"
    (not-every? #(and (map? (:driver-config %)) (plain? (:driver-config %))) outputs)
    "an output's :driver-config isn't a map of plain data"
    :else (when-let [[id] (first (filter (fn [[_ n]] (< 1 n)) (frequencies (map :output-id outputs))))]
            (str "two outputs have :output-id " id))))

(defn- device-problem
  "Why device can't be used, or nil."
  [{:keys [device-key name outputs] :as device}]
  (cond
    (not (map? device)) "left out a device that isn't a map"
    (not (key-value? device-key)) "left out a device without a :device-key"
    :else (when-let [why (or (output-problem outputs)
                             (when-not (or (nil? name) (string? name)) "its :name isn't text"))]
            (str "left out device " device-key ": " why))))

(defn- usable-device
  "device with only the keys the core reads, and :driver."
  [id device]
  (cond-> {:driver id
           :device-key (:device-key device)
           :outputs (mapv #(select-keys % [:output-id :name :driver-config]) (:outputs device))}
    (:name device) (assoc :name (:name device))))

(defn- driver-devices
  "{:devices :warnings} of what a driver's :discover returned: the devices
   the core can use, tagged with :driver, and a warning for each one left
   out (a bad shape, or a key a device before it has)."
  [id label found]
  (when-not (or (nil? found) (sequential? found))
    (throw (ex-info ":discover returned something other than a list of devices" {})))
  (let [{:keys [devices warnings]}
        (reduce (fn [{:keys [seen] :as acc} device]
                  (let [problem (or (device-problem device)
                                    (when (contains? seen (:device-key device))
                                      (str "left out a second device " (:device-key device))))]
                    (if problem
                      (update acc :warnings conj (str label ": " problem))
                      (-> acc
                          (update :seen conj (:device-key device))
                          (update :devices conj (usable-device id device))))))
                {:devices [] :warnings [] :seen #{}}
                found)]
    {:devices devices :warnings warnings}))


;; Running the discoverers

(defn- start-discoverer [{:keys [id name discover] :as d} opts deadline]
  (future
    (let [result (try
                   (driver/with-loader d
                     #(let [found (discover opts)]
                        (if (= :idn id) {:devices found} (driver-devices id name found))))
                   (catch Throwable t
                     {:error (or (not-empty (ex-message t)) (.getName (class t)))}))]
      ;; Even if another future was collected first, don't accept a late result.
      (if (<= (System/nanoTime) (long deadline)) result ::timeout))))

(defn- collect-result [job deadline timeout-ms label]
  (let [ms (max 0 (Math/ceilDiv (long (- (long deadline) (System/nanoTime))) (long 1000000)))
        result (deref job ms ::timeout)
        result (if (= ::timeout result)
                 (do (future-cancel job)
                     {:error (str "no answer within " (if (zero? (mod timeout-ms 1000))
                                                       (quot timeout-ms 1000) (/ timeout-ms 1000.0)) " s")})
                 result)]
    (if-let [error (:error result)] (assoc result :warnings [(str label ": " error)]) result)))

(defn scan
  "Run every discoverer at once (01 §5.3): IDN's discovery on
   broadcast-address, and each registered driver's :discover with
   {:timeout-ms :broadcast-address :broadcast-addresses}. Returns
   {:devices [IDN's … each driver's …] :warnings [\"Acme Nova: libnova not
   found\" …]}, or {:error <IDN's message>} when every one failed (threw or
   didn't answer in time)."
  [{:keys [broadcast-address timeout-ms] :or {timeout-ms TIMEOUT_MS}}]
  (let [opts {:timeout-ms timeout-ms :broadcast-address broadcast-address
              :broadcast-addresses (broadcast-addresses (interfaces))}
        idn (assoc (driver/lookup :idn) :id :idn :name "IDN" :discover idn-devices)
        discoverers (into [idn] (comp (remove #(= :idn (:id %))) (filter :discover))
                          (sort-by :id (driver/registered)))
        deadline (+ (System/nanoTime) (* 1000000 timeout-ms))
        jobs (mapv #(start-discoverer % opts deadline) discoverers)
        results (mapv #(collect-result %1 deadline timeout-ms (:name %2)) jobs discoverers)
        warnings (into [] (mapcat :warnings) results)]
    (doseq [warning warnings] (log/warn warning))
    (if (every? :error results)
      (select-keys (first results) [:error])
      {:devices (into [] (mapcat :devices) results) :warnings warnings})))
