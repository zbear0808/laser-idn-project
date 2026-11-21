(ns laser-show.output.driver-test
  "The output driver registry and its checks (plans/2026-11-19-output-drivers/01
   §1, §5.2, §5.4): validation, registering, the pure-data descriptors, the
   config a projector's driver opens with, and the context classloader."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.walk :as walk]
            [laser-show.backend.multi-engine :as me]
            [laser-show.idn.driver :as idn-driver]
            [laser-show.idn.output-config :as output-config]
            [laser-show.output.driver :as driver])
  (:import [java.net URL URLClassLoader]))

(defn- test-driver
  "A valid driver that does nothing, shaped like LSPS (01 §3)."
  ([] (test-driver {}))
  ([overrides]
   (merge {:id :test/stream
           :name "Test point stream"
           :api 1
           :transport :network
           :fields [{:key :host :label "Host" :type :host :required? true}
                    {:key :port :label "Port" :type :int :min 1 :max 65535 :default 7266}
                    {:key :max-packet-bytes :label "Packet size" :type :int :min 120 :max 65507 :default 1400}]
           :open (fn [config _ctx] {:config config})
           :send-frame! (fn [_session _frame] nil)
           :close! (fn [_session] nil)}
          overrides)))

(defn- with-test-driver [f]
  (driver/register! (test-driver))
  (try
    (f)
    (finally
      (driver/unregister! :test/stream))))

(use-fixtures :each with-test-driver)

(deftest validate-reports-missing-keys-test
  (is (= [] (driver/validate (test-driver))) "the test driver is valid")
  (is (= ["missing :id" "missing :name" "missing :api" "missing :transport" "missing :fields"
          "missing :open" "missing :send-frame!" "missing :close!"]
         (driver/validate {}))
      "every required key, in the contract's order")
  (is (= ["missing :send-frame!"] (driver/validate (dissoc (test-driver) :send-frame!))))
  (is (= ["a driver must be a map"] (driver/validate nil)))
  (testing "the values of the keys that are there"
    (is (= [":api must be 1"] (driver/validate (test-driver {:api 2}))))
    (is (= [":transport must be one of :network :usb :other"] (driver/validate (test-driver {:transport :serial}))))
    (is (= [":id must be a keyword"] (driver/validate (test-driver {:id "nova"}))))
    (is (= [":name must be a string"] (driver/validate (test-driver {:name nil}))))
    (is (= [":close! must be a function"] (driver/validate (test-driver {:close! :nope}))))
    (is (= [":ready? must be a function" ":discover must be a function"]
           (driver/validate (test-driver {:ready? true :discover []})))
        "the optional functions are checked when present")
    (is (= [] (driver/validate (test-driver {:ready? (fn [_] true) :on-error (fn [_ _]) :discover (fn [_] [])
                                             :describe (fn [_] "h:1") :capabilities {:max-points-per-frame 4096}}))))))

(deftest validate-checks-field-specs-test
  (is (= [] (driver/validate (test-driver {:fields []}))) "no fields is allowed")
  (is (= [] (driver/validate (test-driver {:fields [{:key :mode :label "Mode" :type :choice :options [[:a "A"] [:b "B"]]}
                                                    {:key :fast? :label "Fast" :type :boolean :default false}
                                                    {:key :serial :label "Serial" :type :string :hint "On the box"}]}))))
  (doseq [[fields problems]
          [[{:key :host} [":fields must be a vector of field specs"]]
           [[:host] ["field 0 is not a map"]]
           [[{:label "Host" :type :host}] ["field 0: :key must be a keyword"]]
           [[{:key :host :type :host}] ["field :host: :label must be a string"]]
           [[{:key :host :label "Host" :type :ip}] ["field :host: :type must be one of :host :string :int :choice :boolean"]]
           [[{:key :mode :label "Mode" :type :choice}] ["field :mode: a :choice needs :options"]]
           [[{:key :mode :label "Mode" :type :choice :options []}] ["field :mode: a :choice needs :options"]]
           [[{:key :port :label "Port" :type :int :min "1" :max 65535}] ["field :port: :min must be a number"]]
           [[{:key :port :label "Port" :type :int :max nil}] ["field :port: :max must be a number"]]
           [[{:key :port :label "Port" :type :int} {:key :port :label "Port 2" :type :int}] ["field :port appears 2 times"]]]]
    (is (= problems (driver/validate (test-driver {:fields fields}))) (pr-str fields))))

(deftest unqualified-addon-id-is-invalid-test
  (is (= [] (driver/validate (test-driver {:id :nova}))) "a built-in's id is unqualified")
  (is (= ["add-on driver ids must be qualified keywords"] (driver/validate-addon-driver (test-driver {:id :nova}))))
  (is (= [] (driver/validate-addon-driver (test-driver {:id :acme/nova}))))
  (is (= ["missing :close!"] (driver/validate-addon-driver (dissoc (test-driver {:id :acme/nova}) :close!)))
      "and everything validate checks"))

(deftest register-rejects-taken-id-test
  (let [mine (driver/lookup :test/stream)
        error (try (driver/register! (test-driver {:name "Another"})) nil
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (some? error))
    (is (= ["Driver id :test/stream is already taken"] (:problems (ex-data error))))
    (is (identical? mine (driver/lookup :test/stream)) "the first one stays"))
  (testing "an invalid driver is refused with its problems, and nothing is registered"
    (let [error (try (driver/register! (dissoc (test-driver {:id :test/broken}) :open)) nil
                     (catch clojure.lang.ExceptionInfo e e))]
      (is (= ["missing :open"] (:problems (ex-data error))))
      (is (nil? (driver/lookup :test/broken)))))
  (testing "a valid one goes in, is returned, and comes out"
    (let [d (test-driver {:id :test/other})]
      (try
        (is (identical? d (driver/register! d)))
        (is (identical? d (driver/lookup :test/other)))
        (is (some #(identical? d %) (driver/registered)))
        (finally
          (driver/unregister! :test/other)))
      (is (nil? (driver/lookup :test/other))))))

(deftest descriptors-are-pure-data-test
  (let [d (test-driver {:id :acme/nova :name "Acme Nova" :transport :usb
                        :capabilities {:scan-rate {:min 1000 :max 30000 :default 30000}}
                        :discover (fn [_] [])
                        ::driver/addon :acme/nova-bridge
                        ::driver/loader (URLClassLoader. (make-array URL 0))})]
    (driver/register! d)
    (try
      (let [all (driver/descriptors)]
        (is (= {:id :acme/nova :name "Acme Nova" :transport :usb :fields (:fields d)
                :capabilities {:scan-rate {:min 1000 :max 30000 :default 30000}}
                :discover? true :addon :acme/nova-bridge}
               (get all :acme/nova)))
        (is (= {:id :test/stream :name "Test point stream" :transport :network :fields (:fields (test-driver))
                :capabilities nil :discover? false :addon nil}
               (get all :test/stream))
            "a built-in has no add-on, and no :discover")
        (let [impure (atom [])]
          (walk/postwalk #(do (when-not (or (nil? %) (keyword? %) (string? %) (number? %) (boolean? %)
                                            (map? %) (vector? %) (map-entry? %) (seq? %) (set? %))
                                (swap! impure conj %))
                              %)
                         all)
          (is (= [] @impure) "no functions, no classloader: only data")))
      (finally
        (driver/unregister! :acme/nova)))))

(deftest projector-config-test
  (testing "IDN (01 §5.2), even with no :idn driver registered"
    (with-redefs [driver/lookup (constantly nil)]
      (is (= [:idn {:host "10.0.0.5" :port 7255 :service-id 1 :channel-id 1
                    :output-config (output-config/make-config 8 16)}]
             (driver/projector-config {:host "10.0.0.5" :port 7255 :service-id 1
                                       :output-config {:color-bit-depth 8 :xy-bit-depth 16}})))
      (is (= [:idn {:host "10.0.0.5" :port 7255 :service-id 0 :channel-id 0
                    :output-config output-config/default-config}]
             (driver/projector-config {:host "10.0.0.5"})))
      (is (= :idn (first (driver/projector-config {:driver :idn :host "10.0.0.5"}))) "a :driver :idn is the same")
      (is (= (output-config/make-config 16 16)
             (:output-config (second (driver/projector-config {:host "h" :output-config {:color-bit-depth 16}}))))
          "a missing xy depth is 16, as multi-engine has it")))
  (testing "exactly what multi-engine builds an IDN engine from"
    (doseq [p [{:host "10.0.0.5" :port 7255 :service-id 1 :output-config {:color-bit-depth 8 :xy-bit-depth 16}}
               {:host "10.0.0.5"}
               {:host "10.0.0.9" :port 7000 :service-id 3 :output-config {:xy-bit-depth 8}}]]
      (let [e (me/create-engine-for-projector :test-proj p)]
        (is (= (driver/projector-config p) [(:driver-id e) (:driver-config e)]) (pr-str p)))))
  (testing "another driver: its :driver-config over its field defaults"
    (is (= [:test/stream {:host "h" :port 7266 :max-packet-bytes 1400}]
           (driver/projector-config {:driver :test/stream :driver-config {:host "h" :port 7266}})))
    (is (= [:test/stream {:host "h" :port 9000 :max-packet-bytes 1400}]
           (driver/projector-config {:driver :test/stream :driver-config {:host "h" :port 9000}})))
    (is (= [:test/stream {:port 7266 :max-packet-bytes 1400}]
           (driver/projector-config {:driver :test/stream}))
        "no :driver-config: the defaults only")))

(deftest field-defaults-test
  (is (= {:port 7266 :max-packet-bytes 1400} (driver/field-defaults (:fields (test-driver)))))
  (is (= {:fast? false} (driver/field-defaults [{:key :fast? :label "Fast" :type :boolean :default false}]))
      "a false default is a default")
  (is (= {} (driver/field-defaults []))))

(deftest missing-driver-throws-test
  (let [error (try (driver/projector-config {:driver :acme/nova :driver-config {:serial "NV-00123"}}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (= "Output driver :acme/nova is not installed" (ex-message error)))
    (is (= {:driver :acme/nova} (ex-data error))))
  (is (= :acme/nova (driver/projector-driver-id {:driver :acme/nova})))
  (is (= :idn (driver/projector-driver-id {:host "10.0.0.5"}))))

(deftest with-loader-sets-and-restores-context-classloader-test
  (let [thread (Thread/currentThread)
        before (.getContextClassLoader thread)
        loader (URLClassLoader. (make-array URL 0) before)
        d (test-driver {::driver/loader loader})]
    (is (identical? loader (driver/with-loader d #(.getContextClassLoader (Thread/currentThread))))
        "f runs with the driver's loader")
    (is (identical? before (.getContextClassLoader thread)) "and the old one is back")
    (is (thrown-with-msg? Exception #"boom" (driver/with-loader d #(throw (Exception. "boom")))))
    (is (identical? before (.getContextClassLoader thread)) "even when f throws")
    (is (= 42 (driver/with-loader d (constantly 42))) "f's value comes back")
    (testing "without a loader, f just runs"
      (is (identical? before (driver/with-loader (test-driver) #(.getContextClassLoader (Thread/currentThread))))))))

(deftest idn-driver-is-valid-test
  (is (= [] (driver/validate idn-driver/driver)))
  (is (= [:host :port :service-id] (mapv :key (:fields idn-driver/driver))))
  (is (= {:port 7255 :service-id 0} (driver/field-defaults (:fields idn-driver/driver)))))

(deftest idn-driver-is-registered-test
  (is (identical? idn-driver/driver (driver/lookup :idn)) "registered when laser-show.output.driver loads")
  (is (= {:id :idn :name "IDN (ILDA Digital Network)" :transport :network :discover? false :addon nil}
         (select-keys (get (driver/descriptors) :idn) [:id :name :transport :discover? :addon])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Driver id :idn is already taken"
                        (driver/register! (assoc idn-driver/driver :name "Another IDN")))
      "nothing can replace it"))


;; Fields, the summary line, the scan rate (D2: 01 §5.4)

(def ^:private port-spec {:key :port :label "Port" :type :int :min 1 :max 65535 :default 7266 :summary? true})
(def ^:private host-spec {:key :host :label "Host" :type :host :required? true :summary? true})

(deftest coerce-field-test
  (testing "01 §5.4's table"
    (is (= {:ok 7266} (driver/coerce-field port-spec "7266")))
    (is (= {:error "Port must be 1–65535"} (driver/coerce-field port-spec "99999")))
    (is (= {:error "Host is required"} (driver/coerce-field host-spec "  ")))
    (is (= {:ok "10.0.0.9"} (driver/coerce-field (dissoc host-spec :required?) " 10.0.0.9 ")))
    (is (= {:error "Not one of A, B"} (driver/coerce-field {:key :m :label "Mode" :type :choice :options [[:a "A"] [:b "B"]]} :c)))
    (is (= {:ok true} (driver/coerce-field {:key :f :label "Fast" :type :boolean} true))))
  (testing "and around it"
    (is (= {:ok 7300} (driver/coerce-field port-spec 7300)) "a number as it is")
    (is (= {:ok 7300} (driver/coerce-field port-spec " 7300 ")))
    (is (= {:error "Port must be a whole number"} (driver/coerce-field port-spec "72.5")))
    (is (= {:error "Port must be 1–65535"} (driver/coerce-field port-spec "0")))
    (is (= {:error "Size must be at least 120"} (driver/coerce-field {:key :s :label "Size" :type :int :min 120} "100")))
    (is (= {:ok nil} (driver/coerce-field port-spec "")) "blank and optional: unset, so its default applies")
    (is (= {:ok :b} (driver/coerce-field {:key :m :label "Mode" :type :choice :options [[:a "A"] [:b "B"]]} ":b")))
    (is (= {:ok false} (driver/coerce-field {:key :f :label "Fast" :type :boolean} "false")))
    (is (= {:error "Fast must be on or off"} (driver/coerce-field {:key :f :label "Fast" :type :boolean} "yes")))
    (is (= {:ok "Nova"} (driver/coerce-field {:key :serial :label "Serial" :type :string} " Nova ")))))

(deftest coerce-config-test
  (let [fields (:fields (test-driver))]
    (is (= {:config {:host "127.0.0.1" :port 7266 :max-packet-bytes 1400} :errors {}}
           (driver/coerce-config fields {:host "127.0.0.1"}))
        "the defaults fill in")
    (is (= {:config {:host "h" :port 7300 :max-packet-bytes 1400} :errors {}}
           (driver/coerce-config fields {:host "h" :port "7300" :max-packet-bytes "" :colour "red"}))
        "a blank field takes its default; keys no field has are left out")
    (is (= {:config {:max-packet-bytes 1400} :errors {:host "Host is required" :port "Port must be 1–65535"}}
           (driver/coerce-config fields {:port "99999"})))))

(deftest summary-test
  (let [descriptors {:lsps {:id :lsps :name "LSPS point stream (UDP)" :short-name "LSPS"
                            :fields [host-spec port-spec {:key :max-packet-bytes :label "Size" :type :int :default 1400}]}
                     :acme/nova {:id :acme/nova :name "Acme Nova"
                                :fields [{:key :serial :label "Serial" :type :string :summary? true}
                                         {:key :output :label "Output" :type :int :summary? true}]}
                     :acme/plain {:id :acme/plain :name "Plain" :fields []}}]
    (is (= "LSPS · 127.0.0.1:7266" (driver/summary {:driver :lsps :driver-config {:host "127.0.0.1"}} descriptors))
        "the short name, then host:port, the default filled in")
    (is (= "Acme Nova · NV-00123 · 0" (driver/summary {:driver :acme/nova :driver-config {:serial "NV-00123" :output 0}} descriptors))
        "the name without a short one")
    (is (= "Plain" (driver/summary {:driver :acme/plain :driver-config {:x 1}} descriptors)))
    (is (= "acme/gone · not installed" (driver/summary {:driver :acme/gone :driver-config {:serial "x"}} descriptors)))
    (is (= "10.0.0.7 #1" (driver/summary {:host "10.0.0.7" :service-id 1} descriptors)) "IDN's address line as before")
    (is (= "10.0.0.7" (driver/summary {:host "10.0.0.7" :service-id 0} {})))
    (is (= "LSPS · 127.0.0.1:7266"
           (driver/summary {:driver :lsps :driver-config {:host "127.0.0.1"}} (driver/descriptors)))
        "the built-in LSPS descriptor")
    (testing "in two parts, for a card's two lines"
      (is (= ["LSPS" "127.0.0.1:7266"] (driver/summary-parts {:driver :lsps :driver-config {:host "127.0.0.1"}} descriptors)))
      (is (= ["Plain" nil] (driver/summary-parts {:driver :acme/plain} descriptors)))
      (is (= ["acme/gone" "not installed"] (driver/summary-parts {:driver :acme/gone} descriptors)))
      (is (= ["10.0.0.7 #1" nil] (driver/summary-parts {:host "10.0.0.7" :service-id 1} descriptors))))))

(deftest clamp-scan-rate-test
  (let [narrow {:capabilities {:scan-rate {:min 5000 :max 30000 :default 40000}}}]
    (is (= [5000 30000 20000 20000] (mapv #(driver/clamp-scan-rate narrow %) [2000 40000 20000 20000.4])))
    (is (= [1000 100000] (mapv #(driver/clamp-scan-rate nil %) [500 200000])) "without one, the core's range")
    (is (nil? (driver/clamp-scan-rate narrow "fast")))
    (is (nil? (driver/clamp-scan-rate narrow ##NaN)))
    (is (= 30000 (driver/default-scan-rate narrow)) "its default, in its range")
    (is (= 25000 (driver/default-scan-rate nil)))
    (is (= 25000 (driver/default-scan-rate (get (driver/descriptors) :lsps))))))

(deftest short-name-is-checked-and-described-test
  (is (= [":short-name must be a non-blank string"] (driver/validate (test-driver {:short-name " "}))))
  (is (= [] (driver/validate (test-driver {:short-name "TPS"}))))
  (is (= "LSPS" (get-in (driver/descriptors) [:lsps :short-name])))
  (is (= "IDN" (get-in (driver/descriptors) [:idn :short-name]))))
