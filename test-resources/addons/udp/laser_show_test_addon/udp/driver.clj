(ns laser-show-test-addon.udp.driver
  "The udp add-on's driver: one datagram per frame to its :host and :port,
   \"<the sending thread's name> <point count> <lit point count>\" in UTF-8,
   so a test's loopback receiver sees the frames arrive and which thread
   sent them. It depends on laser-show.output.api only, as an add-on must."
  (:require [laser-show.output.api :as api])
  (:import [java.net DatagramPacket DatagramSocket InetAddress]
           [java.nio.charset StandardCharsets]))

(defn- open [{:keys [host port]} {:keys [scan-rate]}]
  {:socket (DatagramSocket.)
   :address (InetAddress/getByName host)
   :port port
   :scan-rate scan-rate})

(defn- send-frame! [{:keys [^DatagramSocket socket ^InetAddress address port scan-rate]} frame]
  (let [lit (count (remove api/blanked? frame))
        text (str (.getName (Thread/currentThread)) " " (count frame) " " lit)
        bytes (.getBytes text StandardCharsets/UTF_8)]
    (.send socket (DatagramPacket. bytes (alength bytes) address (int port)))
    {:transfers 1 :duration-us (api/scan-duration-us (count frame) scan-rate)}))

(defn- close! [{:keys [^DatagramSocket socket]}]
  (.close socket))

(def driver
  {:id :test/udp-stream
   :name "UDP test stream"
   :api 1
   :transport :network
   :fields [{:key :host :label "Host" :type :host :required? true :default "127.0.0.1"}
            {:key :port :label "Port" :type :int :min 1 :max 65535 :required? true}]
   :open open
   :send-frame! send-frame!
   :close! close!
   :describe (fn [{:keys [host port]}] (str host ":" port))})
