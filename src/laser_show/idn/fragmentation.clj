(ns laser-show.idn.fragmentation
  "IDN-Stream frame fragmentation support.
   
   Implements application-layer fragmentation per IDN-Stream spec Section 6.2.
   Splits large frames into multiple IDN messages that fit within MTU limits.
   
   Fragmentation is preferred over IP fragmentation for:
   - Better reliability (each fragment is independent UDP packet)
   - Predictable buffer management on receiver
   - Avoids PMTUD issues with firewalls
   
   Chunk Types for Fragmentation:
   - 0x02 - Frame Samples (entire chunk, no fragmentation)
   - 0x03 - Frame Samples (first fragment)
   - 0xC0 - Frame Samples (sequel fragment)
   
   CCLF Bit Behavior:
   - For first fragment (0x03): CCLF=1 means configuration header present
   - For sequel fragments (0xC0): CCLF=1 means LAST fragment of the data chunk
   
   Timestamp Field for Fragments:
   - First fragment: Normal timestamp in microseconds
   - Sequel fragments: Timestamp field contains first_fragment_timestamp + fragment_number"
  (:require [laser-show.idn.stream :as idn-stream]
            [laser-show.idn.output-config :as output-config])
  (:import [java.nio ByteBuffer ByteOrder]))


;; Configuration Constants


(def ^:const DEFAULT_TARGET_MTU
  "Default target MTU (conservative for Ethernet MTU of 1500 minus headers)"
  1400)

(def ^:const IDN_HELLO_HEADER_SIZE
  "IDN-Hello header size (added by hello/wrap-channel-message)"
  4)

(def ^:const MIN_POINTS_PER_FRAGMENT
  "Minimum points per fragment (spec requires at least 2 for start/end)"
  2)

(def ^:const MAX_FRAGMENTS_PER_FRAME
  "Maximum fragments per frame (practical limit)"
  256)


;; Packet Size Calculations


(defn calculate-max-points-per-fragment
  "Calculate maximum points that fit in a single fragment.
   
   Parameters:
   - output-config: OutputConfig for bit depth settings
   - opts: Optional map with:
     - :target-mtu - Target packet size in bytes (default 1400)
     - :include-config? - Whether first fragment includes config (default true)
   
   Returns: Integer max points per fragment"
  [{:keys [color-bit-depth xy-bit-depth] :as out-config}
   & {:keys [target-mtu include-config?]
      :or {target-mtu DEFAULT_TARGET_MTU
           include-config? true}}]
  (let [bytes-per-pt (output-config/bytes-per-sample out-config)
        tags (idn-stream/get-tags-for-config out-config)
        [_scwc padded-tags] (idn-stream/calculate-scwc tags)
        ;; Available space for sample data
        overhead (if include-config?
                   (+ idn-stream/channel-message-header-size
                      idn-stream/channel-config-header-size
                      (idn-stream/service-config-size padded-tags)
                      idn-stream/frame-chunk-header-size
                      IDN_HELLO_HEADER_SIZE)
                   (+ idn-stream/channel-message-header-size
                      idn-stream/frame-chunk-header-size
                      IDN_HELLO_HEADER_SIZE))
        available-bytes (- target-mtu overhead)]
    (max MIN_POINTS_PER_FRAGMENT
         (quot available-bytes bytes-per-pt))))

(defn needs-fragmentation?
  "Check if a frame needs to be fragmented.
   
   Parameters:
   - frame: Vector of points
   - output-config: OutputConfig for bit depth settings
   - opts: Optional map with :target-mtu
   
   Returns: Boolean"
  [frame out-config & {:keys [target-mtu]
                       :or {target-mtu DEFAULT_TARGET_MTU}}]
  (let [point-count (count frame)
        ;; First fragment has config overhead
        max-first (calculate-max-points-per-fragment out-config
                                                     :target-mtu target-mtu
                                                     :include-config? true)]
    (> point-count max-first)))

(defn fragment-count
  "Calculate number of fragments needed for a frame.
   
   Parameters:
   - point-count: Number of points in frame
   - output-config: OutputConfig for bit depth settings
   - opts: Optional map with :target-mtu
   
   Returns: Integer fragment count (1 if no fragmentation needed)"
  [point-count out-config & {:keys [target-mtu]
                             :or {target-mtu DEFAULT_TARGET_MTU}}]
  (let [max-first (calculate-max-points-per-fragment out-config
                                                     :target-mtu target-mtu
                                                     :include-config? true)
        max-sequel (calculate-max-points-per-fragment out-config
                                                      :target-mtu target-mtu
                                                      :include-config? false)]
    (if (<= point-count max-first)
      1
      (let [remaining (- point-count max-first)
            sequel-count (int (Math/ceil (/ remaining (double max-sequel))))]
        (min MAX_FRAGMENTS_PER_FRAME (inc sequel-count))))))

(defn split-frame-into-fragments
  "Split a frame into fragments that fit within MTU.
   
   Parameters:
   - frame: Vector of points
   - output-config: OutputConfig for bit depth settings
   - opts: Optional map with:
     - :target-mtu - Target packet size (default 1400)
   
   Returns: Vector of frame fragments (each is a vector of points)
   First fragment may be smaller to account for config header."
  [frame out-config & {:keys [target-mtu]
                       :or {target-mtu DEFAULT_TARGET_MTU}}]
  (let [point-count (count frame)
        max-first (calculate-max-points-per-fragment out-config
                                                     :target-mtu target-mtu
                                                     :include-config? true)
        max-sequel (calculate-max-points-per-fragment out-config
                                                      :target-mtu target-mtu
                                                      :include-config? false)]
    (if (<= point-count max-first)
      ;; No fragmentation needed
      [frame]
      ;; Split into fragments
      (loop [remaining (vec frame)
             fragments []
             first? true]
        (if (empty? remaining)
          fragments
          (let [max-pts (if first? max-first max-sequel)
                frag-size (min max-pts (count remaining))
                fragment (subvec remaining 0 frag-size)
                rest-pts (subvec remaining frag-size)]
            (recur rest-pts
                   (conj fragments fragment)
                   false)))))))


;; Binary Writing Helpers for Fragments


(defn- write-sequel-channel-message-header!
  "Write channel message header for sequel fragment.
   
   For sequel fragments:
   - Chunk type is 0xC0 (CHUNK_TYPE_FRAME_SAMPLES_SEQUEL)
   - CCLF bit (bit 6) indicates LAST fragment when set
   - Timestamp field contains base_timestamp + fragment_number
   
   CNL byte structure:
   - Bit 7: Always 1 for channel messages
   - Bit 6: CCLF (last fragment flag for sequel)
   - Bits 5-0: Channel ID"
  [^ByteBuffer buf total-size channel-id base-timestamp-us fragment-number last?]
  (let [channel-msg-bit 0x80               ; Bit 7 ALWAYS 1 for channel messages
        cclf-bit (if last? 0x40 0x00)      ; Bit 6 is CCLF (last fragment indicator)
        cnl-byte (bit-or channel-msg-bit cclf-bit (bit-and channel-id 0x3F))
        ;; Per spec: sequel timestamp = base_timestamp + fragment_number
        sequel-timestamp (+ base-timestamp-us fragment-number)]
    (.putShort buf (short total-size))
    (.put buf (unchecked-byte cnl-byte))
    (.put buf (unchecked-byte idn-stream/CHUNK_TYPE_FRAME_SAMPLES_SEQUEL))
    (.putInt buf (unchecked-int (bit-and sequel-timestamp 0xFFFFFFFF)))))

(defn- write-points!
  "Write sample points to buffer."
  [^ByteBuffer buf points out-config]
  (doseq [point points]
    (idn-stream/write-point! buf point out-config)))


;; Packet Generation Functions


(defn write-first-fragment-packet!
  "Write the first fragment of a fragmented frame to buffer.
   
   Uses chunk type 0x03 (CHUNK_TYPE_FRAME_SAMPLES_FIRST).
   CCLF bit indicates config presence.
   
   Parameters:
   - buf: ByteBuffer to write to
   - fragment: Vector of points for this fragment
   - channel-id: IDN channel ID
   - timestamp-us: Timestamp in microseconds
   - duration-us: Total frame duration
   - opts: Map with :output-config, :service-id, :include-config?, :single-scan?
   
   Returns: byte array of the packet"
  [^ByteBuffer buf fragment channel-id timestamp-us duration-us
   {:keys [output-config service-id include-config? single-scan?]
    :or {output-config output-config/default-config
         service-id 0
         include-config? true
         single-scan? false}}]
  (let [point-count (count fragment)
        tags (idn-stream/get-tags-for-config output-config)
        [scwc padded-tags] (idn-stream/calculate-scwc tags)
        total-size (if include-config?
                     (idn-stream/packet-size-with-config point-count padded-tags output-config)
                     (idn-stream/packet-size-without-config point-count output-config))
        cfl-flags idn-stream/CFL_ROUTING
        chunk-flags (if single-scan? 0x01 0x00)]
    
    (.clear buf)
    
    ;; Use CHUNK_TYPE_FRAME_SAMPLES_FIRST (0x03) for first fragment
    (idn-stream/write-channel-message-header! buf total-size channel-id
                                              idn-stream/CHUNK_TYPE_FRAME_SAMPLES_FIRST
                                              timestamp-us include-config?)
    
    (when include-config?
      (idn-stream/write-channel-config-header! buf scwc cfl-flags service-id
                                               idn-stream/SERVICE_MODE_GRAPHIC_DISCRETE)
      (idn-stream/write-service-config-tags! buf padded-tags))
    
    (idn-stream/write-frame-chunk-header! buf chunk-flags duration-us)
    
    (write-points! buf fragment output-config)
    
    (let [result (byte-array total-size)]
      (.position buf 0)
      (.get buf result 0 total-size)
      result)))

(defn write-sequel-fragment-packet!
  "Write a sequel fragment (not first, possibly last) to buffer.
   
   Uses chunk type 0xC0 (CHUNK_TYPE_FRAME_SAMPLES_SEQUEL).
   CCLF bit indicates whether this is the last fragment.
   Timestamp field = base_timestamp + fragment_number.
   
   Parameters:
   - buf: ByteBuffer to write to
   - fragment: Vector of points for this fragment
   - channel-id: IDN channel ID
   - base-timestamp-us: Timestamp from first fragment
   - fragment-number: 1-based fragment number
   - last?: Whether this is the last fragment
   - opts: Map with :output-config
   
   Returns: byte array of the packet"
  [^ByteBuffer buf fragment channel-id base-timestamp-us fragment-number last?
   {:keys [output-config]
    :or {output-config output-config/default-config}}]
  (let [point-count (count fragment)
        total-size (idn-stream/packet-size-without-config point-count output-config)
        ;; Sequel fragments don't have the SCM/Once flags - just continuation data
        ;; Duration field is 0 for sequels per spec
        chunk-flags 0x00
        sequel-duration-us 0]
    
    (.clear buf)
    
    (write-sequel-channel-message-header! buf total-size channel-id
                                          base-timestamp-us fragment-number last?)
    
    (idn-stream/write-frame-chunk-header! buf chunk-flags sequel-duration-us)
    
    (write-points! buf fragment output-config)
    
    (let [result (byte-array total-size)]
      (.position buf 0)
      (.get buf result 0 total-size)
      result)))

(defn frame->fragmented-packets
  "Convert a frame to one or more IDN packets, fragmenting if necessary.
   
   Parameters:
   - buf: Pre-allocated ByteBuffer
   - frame: Vector of points
   - channel-id: IDN channel ID (0-63)
   - timestamp-us: Base timestamp in microseconds
   - duration-us: Frame duration in microseconds
   - opts: Optional map with:
     - :output-config - OutputConfig (default standard)
     - :service-id - Target service ID (default 0)
     - :include-config? - Include config in first packet (default true)
     - :target-mtu - Target packet size (default 1400)
     - :single-scan? - Draw frame only once (default false)
   
   Returns: Vector of byte arrays, each a complete IDN packet
   - Single element if no fragmentation needed
   - Multiple elements for fragmented frames"
  [buf frame channel-id timestamp-us duration-us
   & {:keys [output-config service-id include-config? target-mtu single-scan?]
      :or {output-config output-config/default-config
           service-id 0
           include-config? true
           target-mtu DEFAULT_TARGET_MTU
           single-scan? false}}]
  (let [opts {:output-config output-config
              :service-id service-id
              :include-config? include-config?
              :single-scan? single-scan?}]
    (if-not (needs-fragmentation? frame output-config :target-mtu target-mtu)
      ;; No fragmentation needed - use existing single-packet functions
      [(if include-config?
         (idn-stream/frame->packet-with-config buf frame channel-id timestamp-us duration-us
                                               :service-id service-id
                                               :output-config output-config
                                               :single-scan? single-scan?)
         (idn-stream/frame->packet buf frame channel-id timestamp-us duration-us
                                   :output-config output-config
                                   :single-scan? single-scan?))]
      ;; Fragmentation needed
      (let [fragments (split-frame-into-fragments frame output-config :target-mtu target-mtu)
            fragment-count (count fragments)]
        (into []
              (map-indexed
               (fn [idx frag]
                 (cond
                   ;; First fragment
                   (zero? idx)
                   (write-first-fragment-packet! buf frag channel-id timestamp-us duration-us opts)
                   
                   ;; Last fragment (sequel)
                   (= idx (dec fragment-count))
                   (write-sequel-fragment-packet! buf frag channel-id timestamp-us idx true
                                                  {:output-config output-config})
                   
                   ;; Middle fragment (sequel, not last)
                   :else
                   (write-sequel-fragment-packet! buf frag channel-id timestamp-us idx false
                                                  {:output-config output-config})))
               fragments))))))


;; Statistics and Debugging


(defn fragmentation-stats
  "Get fragmentation statistics for a frame.
   
   Parameters:
   - frame: Vector of points
   - output-config: OutputConfig for bit depth settings
   - opts: Optional map with :target-mtu
   
   Returns map with:
   - :point-count - Total points in frame
   - :needs-fragmentation? - Whether fragmentation is needed
   - :fragment-count - Number of fragments
   - :points-per-fragment - Vector of point counts per fragment"
  [frame out-config & {:keys [target-mtu]
                       :or {target-mtu DEFAULT_TARGET_MTU}}]
  (let [point-count (count frame)
        needs-frag? (needs-fragmentation? frame out-config :target-mtu target-mtu)
        frags (if needs-frag?
                (split-frame-into-fragments frame out-config :target-mtu target-mtu)
                [frame])]
    {:point-count point-count
     :needs-fragmentation? needs-frag?
     :fragment-count (count frags)
     :points-per-fragment (mapv count frags)}))
