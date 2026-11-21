(ns laser-show.output.discovery-test
  "Scan's broadcast addresses (plans/2026-11-19-output-drivers/01 §5.3, 1D's
   G10), worked out from the network interfaces as data. Nothing here
   sends anything: interfaces only reads the machine's own list."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.output.discovery :as discovery]))

(defn- iface [name & {:keys [up? loopback? broadcasts] :or {up? true loopback? false broadcasts []}}]
  {:name name :up? up? :loopback? loopback? :broadcasts broadcasts})

(deftest broadcast-addresses-from-interfaces-test
  (testing "01 §5.3's rows"
    (is (= ["192.168.1.255" "10.0.0.255"]
           (discovery/broadcast-addresses [(iface "lo0" :loopback? true :broadcasts ["127.255.255.255"])
                                           (iface "en0" :broadcasts ["192.168.1.255"])
                                           (iface "en1" :broadcasts ["10.0.0.255"])]))
        "Wi-Fi and Ethernet, both up: in interface order")
    (is (= ["10.0.255.255"] (discovery/broadcast-addresses [(iface "eth0" :broadcasts ["10.0.255.255"])]))
        "a /16")
    (is (= [] (discovery/broadcast-addresses [(iface "lo" :loopback? true :broadcasts ["127.255.255.255"])]))
        "only loopback")
    (is (= [] (discovery/broadcast-addresses [(iface "en0")])) "no IPv4")
    (is (= [] (discovery/broadcast-addresses [])) "no interfaces"))
  (testing "an interface that is down, a point-to-point link with no broadcast, and one address twice"
    (is (= ["10.0.0.255" "172.16.255.255"]
           (discovery/broadcast-addresses [(iface "en0" :up? false :broadcasts ["192.168.1.255"])
                                           (iface "utun0" :broadcasts [nil])
                                           (iface "en1" :broadcasts [nil "10.0.0.255" "10.1.0.255"])
                                           (iface "bridge0" :broadcasts ["10.0.0.255"])
                                           (iface "en2" :broadcasts ["172.16.255.255"])]))
        "one usable IPv4 broadcast per interface, each address once")))

(deftest interfaces-are-data-test
  (let [found (discovery/interfaces)]
    (is (vector? found))
    (is (every? #(and (string? (:name %)) (boolean? (:up? %)) (boolean? (:loopback? %)) (vector? (:broadcasts %)))
                found)
        "this machine's interfaces, read as data (nothing is sent)")
    (is (every? string? (discovery/broadcast-addresses found)))))
