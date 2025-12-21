(ns idn-hello.core
  "IDN-Hello Protocol Implementation for Clojure
   Based on ILDA Digital Network Hello Protocol Specification (Draft 2022-03-27)"
  (:require [clojure.string :as str])
  (:import [java.net DatagramSocket DatagramPacket InetAddress]
           [java.nio ByteBuffer ByteOrder]))

;; ============================================================================
;; IDN-Hello Protocol Constants
;; ============================================================================

(def ^:const IDN_HELLO_PORT 7255)
(def ^:const LINK_TIMEOUT_MS 1000)

;; Management Commands
(def ^:const CMD_PING_REQUEST 0x08)
(def ^:const CMD_PING_RESPONSE 0x09)
(def ^:const CMD_CLIENT_GROUP_REQUEST 0x0C)
(def ^:const CMD_CLIENT_GROUP_RESPONSE 0x0D)

;; Discovery Commands
(def ^:const CMD_SCAN_REQUEST 0x10)
(def ^:const CMD_SCAN_RESPONSE 0x11)
(def ^:const CMD_SERVICE_MAP_REQUEST 0x12)
(def ^:const CMD_SERVICE_MAP_RESPONSE 0x13)

;; Realtime Streaming Commands
(def ^:const CMD_RT_CHANNEL_MESSAGE 0x40)
(def ^:const CMD_RT_CHANNEL_MESSAGE_ACK 0x41)
(def ^:const CMD_RT_CLOSE 0x44)
(def ^:const CMD_RT_CLOSE_ACK 0x45)
(def ^:const CMD_RT_ABORT 0x46)
(def ^:const CMD_RT_ACKNOWLEDGE 0x47)

;; ============================================================================
;; Packet Construction Helpers
;; ============================================================================

(defn create-packet-header
  "Creates a 4-byte IDN-Hello packet header.
   - command: Command byte (0x00-0xFF)
   - client-group: Client group number (0-15)
   - sequence: Sequence number (0-65535)"
  [command client-group sequence]
  (let [flags (bit-and client-group 0x0F)  ; Client group in lower 4 bits
        buf (ByteBuffer/allocate 4)]
    (.order buf ByteOrder/BIG_ENDIAN)      ; Network byte order (big endian)
    (.put buf (byte command))
    (.put buf (byte flags))
    (.putShort buf (short sequence))
    (.array buf)))

(defn bytes-to-hex
  "Convert byte array to hex string for debugging"
  [byte-array]
  (str/join " " (map #(format "%02X" (bit-and % 0xFF)) byte-array)))

;; ============================================================================
;; UDP Socket Management
;; ============================================================================

(defn create-udp-socket
  "Creates a UDP socket for sending IDN messages.
   Optionally binds to a specific port."
  ([]
   (DatagramSocket.))
  ([port]
   (DatagramSocket. port)))

(defn send-packet
  "Sends a byte array as a UDP datagram packet to the specified host and port."
  [^DatagramSocket socket ^bytes data ^String host ^long port]
  (let [address (InetAddress/getByName host)
        packet (DatagramPacket. data (alength data) address port)]
    (.send socket packet)
    (println (format "Sent %d bytes to %s:%d" (alength data) host port))
    (println (format "Packet hex: %s" (bytes-to-hex data)))))

(defn receive-packet
  "Receives a UDP packet with optional timeout (in milliseconds)."
  ([^DatagramSocket socket buffer-size]
   (receive-packet socket buffer-size 5000))
  ([^DatagramSocket socket buffer-size timeout-ms]
   (let [buffer (byte-array buffer-size)
         packet (DatagramPacket. buffer buffer-size)]
     (.setSoTimeout socket timeout-ms)
     (try
       (.receive socket packet)
       {:data (java.util.Arrays/copyOf (.getData packet) (.getLength packet))
        :length (.getLength packet)
        :address (.getAddress packet)
        :port (.getPort packet)}
       (catch java.net.SocketTimeoutException e
         (println "Receive timeout")
         nil)))))

;; ============================================================================
;; IDN-Hello Protocol Messages
;; ============================================================================

(defn send-ping
  "Sends a ping request to an IDN-Hello server.
   - socket: UDP socket to use
   - host: Target host address
   - sequence: Sequence number for tracking
   - client-group: Client group (0-15, default 0)
   - payload: Optional payload bytes to echo back"
  ([socket host sequence]
   (send-ping socket host sequence 0 nil))
  ([socket host sequence client-group]
   (send-ping socket host sequence client-group nil))
  ([socket host sequence client-group payload]
   (let [header (create-packet-header CMD_PING_REQUEST client-group sequence)
         data (if payload
                (byte-array (concat header payload))
                header)]
     (send-packet socket data host IDN_HELLO_PORT))))

(defn send-scan-request
  "Sends a network scan request to discover IDN-Hello devices.
   Can be sent as broadcast/multicast to discover multiple devices.
   - socket: UDP socket to use
   - host: Target host address (use broadcast address for network scan)
   - sequence: Sequence number for tracking
   - client-group: Client group (0-15, default 0)"
  ([socket host sequence]
   (send-scan-request socket host sequence 0))
  ([socket host sequence client-group]
   (let [header (create-packet-header CMD_SCAN_REQUEST client-group sequence)]
     (send-packet socket header host IDN_HELLO_PORT))))

(defn send-service-map-request
  "Requests the service map from an IDN-Hello server.
   Returns information about services offered by the server.
   - socket: UDP socket to use
   - host: Target host address
   - sequence: Sequence number for tracking
   - client-group: Client group (0-15, default 0)"
  ([socket host sequence]
   (send-service-map-request socket host sequence 0))
  ([socket host sequence client-group]
   (let [header (create-packet-header CMD_SERVICE_MAP_REQUEST client-group sequence)]
     (send-packet socket header host IDN_HELLO_PORT))))

(defn send-rt-channel-message
  "Sends a realtime channel message to an IDN-Hello server.
   This is the basic streaming command for sending IDN-Stream messages.
   - socket: UDP socket to use
   - host: Target host address
   - sequence: Sequence number (must increment monotonically for streaming)
   - client-group: Client group (0-15, default 0)
   - message-data: IDN-Stream channel message bytes (optional, can be empty for keepalive)"
  ([socket host sequence]
   (send-rt-channel-message socket host sequence 0 nil))
  ([socket host sequence client-group]
   (send-rt-channel-message socket host sequence client-group nil))
  ([socket host sequence client-group message-data]
   (let [header (create-packet-header CMD_RT_CHANNEL_MESSAGE client-group sequence)
         data (if message-data
                (byte-array (concat header message-data))
                header)]
     (send-packet socket data host IDN_HELLO_PORT))))

(defn send-rt-channel-message-with-ack
  "Sends a realtime channel message with acknowledgement request.
   Server will respond with an acknowledgement packet containing status information.
   - socket: UDP socket to use
   - host: Target host address
   - sequence: Sequence number (must increment monotonically for streaming)
   - client-group: Client group (0-15, default 0)
   - message-data: IDN-Stream channel message bytes (optional)"
  ([socket host sequence]
   (send-rt-channel-message-with-ack socket host sequence 0 nil))
  ([socket host sequence client-group]
   (send-rt-channel-message-with-ack socket host sequence client-group nil))
  ([socket host sequence client-group message-data]
   (let [header (create-packet-header CMD_RT_CHANNEL_MESSAGE_ACK client-group sequence)
         data (if message-data
                (byte-array (concat header message-data))
                header)]
     (send-packet socket data host IDN_HELLO_PORT))))

(defn send-rt-close
  "Sends a graceful close command for the realtime connection.
   The server will finish processing, close channels gracefully, and close the session.
   - socket: UDP socket to use
   - host: Target host address
   - sequence: Sequence number
   - client-group: Client group (0-15, default 0)
   - message-data: Optional final IDN-Stream channel message"
  ([socket host sequence]
   (send-rt-close socket host sequence 0 nil))
  ([socket host sequence client-group]
   (send-rt-close socket host sequence client-group nil))
  ([socket host sequence client-group message-data]
   (let [header (create-packet-header CMD_RT_CLOSE client-group sequence)
         data (if message-data
                (byte-array (concat header message-data))
                header)]
     (send-packet socket data host IDN_HELLO_PORT))))

(defn send-rt-abort
  "Sends an abort command to immediately reset the session and close the connection.
   - socket: UDP socket to use
   - host: Target host address
   - sequence: Sequence number
   - client-group: Client group (0-15, default 0)"
  ([socket host sequence]
   (send-rt-abort socket host sequence 0))
  ([socket host sequence client-group]
   (let [header (create-packet-header CMD_RT_ABORT client-group sequence)]
     (send-packet socket header host IDN_HELLO_PORT))))

;; ============================================================================
;; Response Parsing Helpers
;; ============================================================================

(defn parse-packet-header
  "Parses the 4-byte IDN-Hello packet header from received data.
   Returns a map with :command, :client-group, and :sequence"
  [data]
  (when (>= (count data) 4)
    (let [buf (ByteBuffer/wrap data)]
      (.order buf ByteOrder/BIG_ENDIAN)
      {:command (.get buf)
       :client-group (bit-and (.get buf) 0x0F)
       :sequence (.getShort buf)
       :payload (when (> (count data) 4)
                  (java.util.Arrays/copyOfRange data 4 (count data)))})))

(defn parse-scan-response
  "Parses a scan response packet to extract device information.
   Returns a map with device details including unitID, hostname, protocol version, and status."
  [data]
  (when (>= (count data) 40)  ; 4 byte header + 36 byte scan response minimum
    (let [buf (ByteBuffer/wrap data 4 (count data))  ; Skip header
          _ (.order buf ByteOrder/BIG_ENDIAN)
          struct-size (.get buf)
          protocol-version (.get buf)
          major-version (bit-shift-right (bit-and protocol-version 0xF0) 4)
          minor-version (bit-and protocol-version 0x0F)
          status (.get buf)
          _ (.get buf)  ; Reserved byte
          unit-id-bytes (byte-array 16)
          _ (.get buf unit-id-bytes)
          host-name-bytes (byte-array 20)
          _ (.get buf host-name-bytes)]
      {:struct-size struct-size
       :protocol-version {:major major-version :minor minor-version}
       :status {:malfunction (bit-test status 7)
                :offline (bit-test status 6)
                :excluded (bit-test status 5)
                :occupied (bit-test status 4)
                :realtime (bit-test status 0)}
       :unit-id (bytes-to-hex unit-id-bytes)
       :host-name (str/trim (String. host-name-bytes "UTF-8"))})))

;; ============================================================================
;; Example Usage Functions
;; ============================================================================

(defn discover-devices
  "Scans the network for IDN-Hello devices.
   - broadcast-address: Network broadcast address (e.g., '192.168.1.255')
   - timeout-ms: How long to wait for responses (default 3000ms)"
  ([broadcast-address]
   (discover-devices broadcast-address 3000))
  ([broadcast-address timeout-ms]
   (let [socket (create-udp-socket)]
     (.setBroadcast socket true)  ; Enable broadcast
     (try
       (println "\n=== Scanning for IDN-Hello devices ===")
       (send-scan-request socket broadcast-address 1)
       
       ;; Collect responses
       (loop [devices []]
         (if-let [response (receive-packet socket 1024 1000)]
           (do
             (println (format "\nReceived response from %s:%d"
                            (.getHostAddress (:address response))
                            (:port response)))
             (let [header (parse-packet-header (:data response))]
               (when (= (:command header) CMD_SCAN_RESPONSE)
                 (let [device-info (parse-scan-response (:data response))]
                   (println "Device info:" device-info)
                   (recur (conj devices (assoc device-info
                                               :address (.getHostAddress (:address response))
                                               :port (:port response))))))))
           devices))
       (finally
         (.close socket))))))

(defn ping-device
  "Pings an IDN-Hello device and measures round-trip time.
   - host: Target device IP address
   - payload: Optional payload to echo back (useful for timestamp measurements)"
  ([host]
   (ping-device host nil))
  ([host payload]
   (let [socket (create-udp-socket)
         start-time (System/nanoTime)]
     (try
       (println (format "\n=== Pinging %s ===", host))
       (send-ping socket host 100 0 payload)
       
       (if-let [response (receive-packet socket 1024 5000)]
         (let [rtt-ms (/ (- (System/nanoTime) start-time) 1000000.0)
               header (parse-packet-header (:data response))]
           (if (= (:command header) CMD_PING_RESPONSE)
             (do
               (println (format "Ping response received in %.2f ms" rtt-ms))
               (println (format "Sequence: %d, Client group: %d"
                              (:sequence header)
                              (:client-group header)))
               (when (:payload header)
                 (println (format "Payload: %s" (bytes-to-hex (:payload header)))))
               {:success true :rtt-ms rtt-ms :response header})
             (do
               (println "Unexpected response command:" (:command header))
               {:success false})))
         (do
           (println "No response received (timeout)")
           {:success false}))
       (finally
         (.close socket))))))

(defn -main
  "Example usage of the IDN-Hello protocol implementation"
  [& args]
  (println "IDN-Hello Protocol - Clojure Implementation")
  (println "===========================================\n")
  
  ;; Example 1: Discover devices on network
  (comment
    (discover-devices "192.168.1.255"))
  
  ;; Example 2: Ping a specific device
  (comment
    (ping-device "192.168.1.100"))
  
  ;; Example 3: Send a realtime streaming message
  (comment
    (let [socket (create-udp-socket)]
      (try
        ;; Send channel messages with incrementing sequence numbers
        (dotimes [i 10]
          (send-rt-channel-message socket "192.168.1.100" i 0 nil)
          (Thread/sleep 100))
        
        ;; Close the connection gracefully
        (send-rt-close socket "192.168.1.100" 10 0)
        (finally
          (.close socket)))))
  
  (println "\nTo use this library, call the functions from your REPL or code:")
  (println "  (discover-devices \"192.168.1.255\")")
  (println "  (ping-device \"192.168.1.100\")"))
