(ns laser-show.output.api-test
  "The add-on authors' facade (plans/2026-11-19-output-drivers/02 D2): the
   same values and behaviour as the namespaces it re-exports."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.test :as log-test]
            [laser-show.animation.types :as t]
            [laser-show.idn.stream :as stream]
            [laser-show.output.api :as api]
            [laser-show.output.driver :as driver]
            [laser-show.output.sample-format :as sf])
  (:import [java.nio ByteBuffer]))

(deftest api-reexports-test
  (is (= 1 api/api-version))
  (is (= [] (driver/validate {:id :test/api :name "API" :api api/api-version :transport :other :fields []
                              :open (fn [_ _]) :send-frame! (fn [_ _]) :close! (fn [_])}))
      "the version the registry checks")
  (is (= [t/X t/Y t/R t/G t/B] [api/X api/Y api/R api/G api/B] [0 1 2 3 4]))
  (is (true? (api/blanked? [0.2 0.3 0.0 0.0 0.0])))
  (is (false? (api/blanked? [0.2 0.3 0.0 0.5 0.0])))
  (is (= (stream/scan-duration-us 600 25000) (api/scan-duration-us 600 25000) 23960))
  (is (= 16384 (api/scale-xy 0.5 :s16)))
  (is (= 128 (api/scale-color 0.5 :u8)))
  (let [layout {:byte-order :little :fields [[:x :u12] [:y :u12] [:r :u8] [:g :u8] [:b :u8] [:i :u8]]}
        compiled (api/compile-layout layout)
        buf (ByteBuffer/allocate 8)]
    (is (= 8 (api/bytes-per-point compiled) (sf/bytes-per-point (sf/compile-layout layout))))
    (is (= 1 (api/encode-points! compiled buf [[0.0 1.0 0.2 0.9 0.1]] 0 1)))
    (is (= [0x00 0x08 0xFF 0x0F 0x33 0xE6 0x1A 0xE6] (mapv #(bit-and % 0xFF) (.array buf))))))

(defn- logged []
  (mapv (juxt :logger-ns :level :throwable :message) (log-test/the-log)))

(deftest api-log-goes-through-tools-logging-test
  (testing "G7: under the add-on's own logger name, at each level, with or without a throwable"
    (log-test/with-log
      (let [e (ex-info "test: boom" {})]
        (is (nil? (api/log :warn "acme.nova.driver" "Nova at 10.0.0.9 is slow")))
        (is (nil? (api/log :error "acme.nova.driver" e "Nova failed")))
        (doseq [level [:trace :debug :info]]
          (api/log level "acme.nova" (str "at " level)))
        (api/log :info "acme.nova" 42)
        (is (= [["acme.nova.driver" :warn nil "Nova at 10.0.0.9 is slow"]
                ["acme.nova.driver" :error e "Nova failed"]
                ["acme.nova" :trace nil "at :trace"]
                ["acme.nova" :debug nil "at :debug"]
                ["acme.nova" :info nil "at :info"]
                ["acme.nova" :info nil "42"]]
               (logged))))))
  (testing "a level the logger has off writes nothing"
    (let [entries (log-test/atomic-log vector)]
      (binding [log/*logger-factory* (log-test/logger-factory entries (fn [_ level] (not= :debug level)))]
        (api/log :debug "acme.nova" "hidden")
        (api/log :info "acme.nova" "shown"))
      (is (= [["acme.nova" :info nil "shown"]] (log-test/entries entries)))))
  (testing "an unknown level is a mistake, said at once"
    (is (thrown-with-msg? IllegalArgumentException #"Unknown log level :warning; use one of :trace :debug :info :warn :error"
                          (api/log :warning "acme.nova" "x"))))
  (testing "a plain function, found from any classloader with requiring-resolve, and the app's
            own logging (logback here) takes it"
    (is (fn? @(requiring-resolve 'laser-show.output.api/log)))
    (is (nil? (api/log :debug "laser-show.test.api-log" "api-log-goes-through-tools-logging-test")))))
