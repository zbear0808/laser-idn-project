(ns laser-show.idn.hello
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
   - sequence: Sequence number (0-65535, unsigned 16-bit)"
  [command client-group sequence]
  (let [flags (bit-and client-group 0x0F)  ; Client group in lower 4 bits
        buf (ByteBuffer/allocate 4)]
    (.order buf ByteOrder/BIG_ENDIAN)      ; Network byte order (big endian)
    (.put buf (byte command))
    (.put buf (byte flags))
    ;; Use unchecked-short to handle full unsigned 16-bit range (0-65535)
    ;; Java's short is signed (-32768 to 32767), but bit pattern is same
    (.putShort buf (unchecked-short sequence))
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
  "Sends a byte array as a UDP datagram packet to the specified host and port.
   Optional log-fn callback will be called with the packet data for logging."
  ([^DatagramSocket socket ^bytes data ^String host port]
   (send-packet socket data host port nil))
  ([^DatagramSocket socket ^bytes data ^String host port log-fn]
   (let [address (InetAddress/getByName host)
         packet (DatagramPacket. data (alength data) address (int port))]
     (.send socket packet)
     (when log-fn
       (log-fn data)))))

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
;; IDN-Stream Message Wrapping
;; ============================================================================

(def ^:private sequence-counter (atom 0))

;; ============================================================================
;; DAC Registry - Track discovered and configured laser DACs
;; ============================================================================

(defonce ^:private !dac-registry
  (atom {}))

(defn get-dac-registry
  "Returns the current DAC registry as a map of DAC-id -> DAC-info."
  []
  @!dac-registry)

(defn register-dac!
  "Register a DAC in the registry.
   - dac-id: Unique identifier for the DAC (e.g., hostname or unit-id)
   - dac-info: Map containing DAC information:
     - :address - IP address string
     - :port - Port number (default IDN_HELLO_PORT)
     - :host-name - Human-readable name
     - :unit-id - Hardware unit ID
     - :status - Status flags from scan response
     - :protocol-version - Protocol version map
     - :client-group - Assigned client group (0-15)
     - :last-seen - Timestamp of last communication"
  [dac-id dac-info]
  (swap! !dac-registry assoc dac-id
         (merge {:port IDN_HELLO_PORT
                 :client-group 0
                 :last-seen (System/currentTimeMillis)}
                dac-info)))

(defn unregister-dac!
  "Remove a DAC from the registry."
  [dac-id]
  (swap! !dac-registry dissoc dac-id))

(defn clear-dac-registry!
  "Clear all DACs from the registry."
  []
  (reset! !dac-registry {}))

(defn get-dac
  "Get DAC info by ID. Returns nil if not found."
  [dac-id]
  (get @!dac-registry dac-id))

(defn list-dacs
  "List all registered DAC IDs."
  []
  (keys @!dac-registry))

(defn update-dac!
  "Update a DAC's info in the registry."
  [dac-id updates]
  (when (get @!dac-registry dac-id)
    (swap! !dac-registry update dac-id merge updates
           {:last-seen (System/currentTimeMillis)})))

;; ============================================================================
;; Per-DAC Sequence Counters
;; ============================================================================

(defonce ^:private !dac-sequence-counters
  (atom {}))

(defn get-dac-sequence
  "Get the next sequence number for a specific DAC.
   Each DAC maintains its own sequence counter."
  [dac-id]
  (get (swap! !dac-sequence-counters update dac-id
              (fn [n] (bit-and (inc (or n 0)) 0xFFFF)))
       dac-id))

(defn reset-dac-sequence!
  "Reset the sequence counter for a specific DAC."
  [dac-id]
  (swap! !dac-sequence-counters assoc dac-id 0))

(defn reset-all-dac-sequences!
  "Reset all DAC sequence counters."
  []
  (reset! !dac-sequence-counters {}))

(defn wrap-channel-message
  "Wrap an IDN-Stream channel message with IDN-Hello header for transmission.
   The IDN-Hello header is prepended to the IDN-Stream message.
   
   Per IDN-Hello spec, the packet structure is:
   - IDN-Hello header (4 bytes): command, flags, sequence
   - IDN-Stream channel message (variable length)
   
   Parameters:
   - idn-stream-message: byte array containing the IDN-Stream channel message
   - opts: Optional map with:
     - :client-group - Client group (0-15, default 0)
     - :sequence - Sequence number (default auto-increment)
   
   Returns: byte array with IDN-Hello header + IDN-Stream message"
  ([idn-stream-message]
   (wrap-channel-message idn-stream-message {}))
  ([idn-stream-message {:keys [client-group sequence]
                        :or {client-group 0}}]
   (let [seq-num (or sequence (swap! sequence-counter #(bit-and (inc %) 0xFFFF)))
         header (create-packet-header CMD_RT_CHANNEL_MESSAGE client-group seq-num)
         result (byte-array (+ 4 (alength idn-stream-message)))]
     (System/arraycopy header 0 result 0 4)
     (System/arraycopy idn-stream-message 0 result 4 (alength idn-stream-message))
     result)))

(defn reset-sequence-counter!
  "Reset the auto-increment sequence counter to 0."
  []
  (reset! sequence-counter 0))

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
    (let [buf (ByteBuffer/wrap data 4 (- (count data) 4))  ; Skip header, length is remaining bytes
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
;; Device Ping
;; ============================================================================

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
       (println (format "\n=== Pinging %s ===" host))
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

;; ============================================================================
;; DAC-Targeted Sending Functions
;; ============================================================================

(defn send-to-dac!
  "Send an IDN-Stream message to a specific registered DAC.
   - dac-id: The DAC identifier in the registry
   - socket: UDP socket to use
   - idn-stream-message: The IDN-Stream channel message bytes
   - opts: Optional map with :log-fn for packet logging
   
   Returns true if sent successfully, false if DAC not found."
  ([dac-id socket idn-stream-message]
   (send-to-dac! dac-id socket idn-stream-message {}))
  ([dac-id socket idn-stream-message {:keys [log-fn]}]
   (if-let [dac (get-dac dac-id)]
     (let [{:keys [address port client-group]} dac
           seq-num (get-dac-sequence dac-id)
           header (create-packet-header CMD_RT_CHANNEL_MESSAGE client-group seq-num)
           data (byte-array (+ 4 (alength idn-stream-message)))]
       (System/arraycopy header 0 data 0 4)
       (System/arraycopy idn-stream-message 0 data 4 (alength idn-stream-message))
       (send-packet socket data address port log-fn)
       (update-dac! dac-id {})
       true)
     false)))

(defn send-to-all-dacs!
  "Send an IDN-Stream message to all registered DACs.
   - socket: UDP socket to use
   - idn-stream-message: The IDN-Stream channel message bytes
   - opts: Optional map with :log-fn for packet logging
   
   Returns a map of dac-id -> success boolean."
  ([socket idn-stream-message]
   (send-to-all-dacs! socket idn-stream-message {}))
  ([socket idn-stream-message opts]
   (into {}
         (for [dac-id (list-dacs)]
           [dac-id (send-to-dac! dac-id socket idn-stream-message opts)]))))

(defn ping-dac!
  "Ping a registered DAC by ID.
   Returns {:success true/false :rtt-ms <round-trip-time>} or nil if DAC not found."
  [dac-id]
  (when-let [dac (get-dac dac-id)]
    (let [{:keys [address]} dac
          result (ping-device address)]
      (when (:success result)
        (update-dac! dac-id {}))
      result)))

(defn close-dac-connection!
  "Send a graceful close to a registered DAC.
   - dac-id: The DAC identifier in the registry
   - socket: UDP socket to use"
  [dac-id socket]
  (when-let [dac (get-dac dac-id)]
    (let [{:keys [address port client-group]} dac
          seq-num (get-dac-sequence dac-id)]
      (send-rt-close socket address seq-num client-group))))

(defn abort-dac-connection!
  "Send an abort to a registered DAC.
   - dac-id: The DAC identifier in the registry
   - socket: UDP socket to use"
  [dac-id socket]
  (when-let [dac (get-dac dac-id)]
    (let [{:keys [address port client-group]} dac
          seq-num (get-dac-sequence dac-id)]
      (send-rt-abort socket address seq-num client-group))))

;; ============================================================================
;; Device Discovery
;; ============================================================================

(defn discover-devices
  "Scans the network for IDN-Hello devices.
   - broadcast-address: Network broadcast address (e.g., '192.168.1.255')
   - timeout-ms: How long to wait for responses (default 3000ms)
   
   Returns a vector of discovered device info maps."
  ([broadcast-address]
   (discover-devices broadcast-address 3000))
  ([broadcast-address timeout-ms]
   (let [socket (create-udp-socket)]
     (.setBroadcast socket true)
     (try
       (println "\n=== Scanning for IDN-Hello devices ===")
       (send-scan-request socket broadcast-address 1)
       
       (loop [devices []]
         (if-let [response (receive-packet socket 1024 1000)]
           (do
             (println (format "\nReceived response from %s:%d"
                            (.getHostAddress (:address response))
                            (:port response)))
             (let [header (parse-packet-header (:data response))]
               (if (= (:command header) CMD_SCAN_RESPONSE)
                 (let [device-info (parse-scan-response (:data response))
                       full-info (assoc device-info
                                        :address (.getHostAddress (:address response))
                                        :port (:port response))]
                   (println "Device info:" device-info)
                   (recur (conj devices full-info)))
                 (recur devices))))
           devices))
       (finally
         (.close socket))))))

(defn discover-and-register!
  "Scans the network for IDN-Hello devices and registers them in the DAC registry.
   - broadcast-address: Network broadcast address (e.g., '192.168.1.255')
   - timeout-ms: How long to wait for responses (default 3000ms)
   - opts: Optional map with:
     - :id-fn - Function to generate DAC ID from device info (default: uses :host-name or :address)
     - :clear-existing - If true, clears existing registry before adding (default: false)
   
   Returns the list of registered DAC IDs."
  ([broadcast-address]
   (discover-and-register! broadcast-address 3000 {}))
  ([broadcast-address timeout-ms]
   (discover-and-register! broadcast-address timeout-ms {}))
  ([broadcast-address timeout-ms {:keys [id-fn clear-existing]
                                   :or {id-fn (fn [info]
                                                (let [name (:host-name info)]
                                                  (if (and name (not (str/blank? name)))
                                                    (keyword (str/lower-case (str/replace name #"\s+" "-")))
                                                    (keyword (:address info)))))
                                        clear-existing false}}]
   (when clear-existing
     (clear-dac-registry!))
   (let [devices (discover-devices broadcast-address timeout-ms)]
     (doseq [device devices]
       (let [dac-id (id-fn device)]
         (register-dac! dac-id device)
         (println (format "Registered DAC: %s -> %s" dac-id (:address device)))))
     (vec (list-dacs)))))

(defn get-available-dacs
  "Returns a list of DACs that are available (not offline, not malfunctioning).
   Optionally filters by additional criteria."
  ([]
   (get-available-dacs {}))
  ([{:keys [exclude-occupied]}]
   (for [[dac-id dac-info] (get-dac-registry)
         :let [status (:status dac-info)]
         :when (and (not (:offline status))
                    (not (:malfunction status))
                    (not (:excluded status))
                    (or (not exclude-occupied)
                        (not (:occupied status))))]
     dac-id)))

;; ============================================================================
;; Main Entry Point
;; ============================================================================

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
