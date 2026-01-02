(ns laser-show.idn.stream
  "IDN-Stream packet format converter.
   Implements ILDA Digital Network Stream Specification (Revision 002, July 2025).
   https://www.ilda.com/resources/StandardsDocs/ILDA-IDN-Stream-rev002.pdf
   
   Converts LaserFrame objects (with normalized point values) to binary IDN 
   packet format for transmission. Supports configurable bit depths for both
   color (8/16-bit) and position (8/16-bit)."
  (:require [laser-show.animation.types :as t]
            [laser-show.idn.output-config :as output-config])
  (:import [java.nio ByteBuffer ByteOrder]))

(def ^:const CONTENT_ID_CHANNEL_MSG_BASE
  "Content identifier base for channel messages (0x8000-0xFFFF)"
  0x8000)

;; Chunk Types (Section 2.1)
(def ^:const CHUNK_TYPE_VOID 0x00)
(def ^:const CHUNK_TYPE_WAVE_SAMPLES 0x01)
(def ^:const CHUNK_TYPE_FRAME_SAMPLES 0x02)
(def ^:const CHUNK_TYPE_FRAME_SAMPLES_FIRST 0x03)
(def ^:const CHUNK_TYPE_OCTET_SEGMENT 0x10)
(def ^:const CHUNK_TYPE_OCTET_STRING 0x11)
(def ^:const CHUNK_TYPE_DIMMER_LEVELS 0x18)
(def ^:const CHUNK_TYPE_AUDIO_WAVE 0x20)
(def ^:const CHUNK_TYPE_FRAME_SAMPLES_SEQUEL 0xC0)

;; Service Modes (Section 2.2)
(def ^:const SERVICE_MODE_VOID 0x00)
(def ^:const SERVICE_MODE_GRAPHIC_CONTINUOUS 0x01)
(def ^:const SERVICE_MODE_GRAPHIC_DISCRETE 0x02)
(def ^:const SERVICE_MODE_EFFECTS_CONTINUOUS 0x03)
(def ^:const SERVICE_MODE_EFFECTS_DISCRETE 0x04)
(def ^:const SERVICE_MODE_DMX512_CONTINUOUS 0x05)
(def ^:const SERVICE_MODE_DMX512_DISCRETE 0x06)
(def ^:const SERVICE_MODE_AUDIO_CONTINUOUS 0x0C)

;; Configuration Tags (Section 3.4)
(def ^:const TAG_VOID 0x0000)
(def ^:const TAG_BREAK 0x1000)
(def ^:const TAG_NOP 0x4000)
(def ^:const TAG_PRECISION 0x4010)
(def ^:const TAG_HINT_NO_SHUTTER 0x4100)
(def ^:const TAG_HINT_WITH_SHUTTER 0x4101)
(def ^:const TAG_X 0x4200)
(def ^:const TAG_Y 0x4210)
(def ^:const TAG_Z 0x4220)
(def ^:const TAG_INTENSITY 0x5C10)
(def ^:const TAG_BEAM_BRUSH 0x5C20)
(def ^:const TAG_WAVELEN_PREFIX 0x5C00)

;; Color wavelength tags (Section 3.4.8)
;; Tag format: 0x5www where www is 10-bit wavelength
(defn color-tag
  "Create a color tag for a specific wavelength in nm.
   Wavelength is encoded in 10 bits (0-1023nm range)."
  [wavelength-nm]
  (bit-or 0x5000 (bit-and wavelength-nm 0x3FF)))

;; Standard ISP-DB25 color wavelengths (Section 3.4.10)
(def ^:const TAG_COLOR_RED (color-tag 638))      ; 0x527E
(def ^:const TAG_COLOR_GREEN (color-tag 532))    ; 0x5214
(def ^:const TAG_COLOR_BLUE (color-tag 460))     ; 0x51CC
(def ^:const TAG_COLOR_DEEP_BLUE (color-tag 445)); 0x51BD
(def ^:const TAG_COLOR_YELLOW (color-tag 577))   ; 0x5241
(def ^:const TAG_COLOR_CYAN (color-tag 488))     ; 0x51E8

;; Channel Configuration Flags (Section 2.2)
(def ^:const CFL_ROUTING 0x01)
(def ^:const CFL_CLOSE 0x02)


;; Default Configuration


(def ^:const DEFAULT_CHANNEL_ID 0)
;; Per spec Section 2.2: Service ID 0x00 = connect to default service
(def ^:const DEFAULT_SERVICE_ID 0)

;; Maximum points per packet - conservative value to avoid UDP fragmentation
(def ^:const MAX_POINTS_PER_PACKET 150)


;; Tag Configurations for Different Bit Depths


(def tags-16bit-xy-8bit-color
  "Standard ISP-DB25: 16-bit XY, 8-bit RGB"
  [TAG_X TAG_PRECISION    ; X, 16-bit
   TAG_Y TAG_PRECISION    ; Y, 16-bit
   TAG_COLOR_RED          ; Red, 8-bit
   TAG_COLOR_GREEN        ; Green, 8-bit
   TAG_COLOR_BLUE         ; Blue, 8-bit
   TAG_VOID])             ; Padding for 32-bit alignment

(def tags-16bit-xy-16bit-color
  "High precision: 16-bit XY, 16-bit RGB"
  [TAG_X TAG_PRECISION        ; X, 16-bit
   TAG_Y TAG_PRECISION        ; Y, 16-bit
   TAG_COLOR_RED TAG_PRECISION    ; Red, 16-bit
   TAG_COLOR_GREEN TAG_PRECISION  ; Green, 16-bit
   TAG_COLOR_BLUE TAG_PRECISION]) ; Blue, 16-bit (10 bytes, no padding needed)

(def tags-8bit-xy-8bit-color
  "Compact: 8-bit XY, 8-bit RGB"
  [TAG_X                  ; X, 8-bit
   TAG_Y                  ; Y, 8-bit
   TAG_COLOR_RED          ; Red, 8-bit
   TAG_COLOR_GREEN        ; Green, 8-bit
   TAG_COLOR_BLUE         ; Blue, 8-bit
   TAG_VOID])             ; Padding (5 bytes -> 6 for alignment)

(def tags-8bit-xy-16bit-color
  "High color: 8-bit XY, 16-bit RGB"
  [TAG_X                      ; X, 8-bit
   TAG_Y                      ; Y, 8-bit
   TAG_COLOR_RED TAG_PRECISION    ; Red, 16-bit
   TAG_COLOR_GREEN TAG_PRECISION  ; Green, 16-bit
   TAG_COLOR_BLUE TAG_PRECISION   ; Blue, 16-bit
   TAG_VOID])                 ; Padding for alignment

;; Default tags (for backward compatibility)
(def default-graphic-tags tags-16bit-xy-8bit-color)

(defn get-tags-for-config
  "Get appropriate tag configuration for output config."
  [{:keys [color-bit-depth xy-bit-depth]}]
  (case [xy-bit-depth color-bit-depth]
    [16 8]  tags-16bit-xy-8bit-color
    [16 16] tags-16bit-xy-16bit-color
    [8 8]   tags-8bit-xy-8bit-color
    [8 16]  tags-8bit-xy-16bit-color
    tags-16bit-xy-8bit-color)) ; default


;; SCWC Calculation and Tag Alignment


(defn calculate-scwc
  "Calculate Service Configuration Word Count (SCWC) and ensure 32-bit alignment.
   Per IDN-Stream spec Section 3.4.2: Tag arrays must be 32-bit (4 byte) aligned.
   Tags are 16-bit (2 bytes) each, so we need an even number of tags.
   
   Returns [scwc padded-tags] where:
   - scwc: Number of 32-bit words in the tag array
   - padded-tags: Tag vector padded with TAG_VOID if necessary for alignment"
  [tags]
  (let [tag-count (count tags)
        ;; Pad to even number of tags for 32-bit boundary alignment
        padded-tags (if (odd? tag-count)
                      (conj (vec tags) TAG_VOID)
                      (vec tags))
        ;; SCWC = number of 32-bit words = (tag-count / 2) after padding
        scwc (quot (count padded-tags) 2)]
    [scwc padded-tags]))


;; Packet Size Calculations


(defn channel-message-header-size
  "Size of channel message header (without configuration)"
  []
  8)

(defn channel-config-header-size
  "Size of channel configuration header"
  []
  4)

(defn service-config-size
  "Size of service configuration (tag array) in bytes"
  [tags]
  (* 2 (count tags)))

(defn frame-chunk-header-size
  "Size of frame samples data chunk header"
  []
  4)

(defn packet-size-with-config
  "Calculate total IDN message size with channel configuration"
  [point-count tags output-config]
  (let [bytes-per-pt (output-config/bytes-per-sample output-config)]
    (+ (channel-message-header-size)
       (channel-config-header-size)
       (service-config-size tags)
       (frame-chunk-header-size)
       (* bytes-per-pt point-count))))

(defn packet-size-without-config
  "Calculate total IDN message size without channel configuration"
  [point-count output-config]
  (let [bytes-per-pt (output-config/bytes-per-sample output-config)]
    (+ (channel-message-header-size)
       (frame-chunk-header-size)
       (* bytes-per-pt point-count))))

(defn max-points-for-size
  "Calculate maximum points that fit in given packet size (without config)"
  [max-size output-config]
  (let [bytes-per-pt (output-config/bytes-per-sample output-config)]
    (quot (- max-size (channel-message-header-size) (frame-chunk-header-size))
          bytes-per-pt)))


;; Binary Writing Helpers


(defn write-channel-message-header!
  "Write IDN-Stream channel message header to ByteBuffer.
   
   Per Section 2.1:
   - Total Size (2 bytes): Total message size
   - CNL (1 byte): Channel message bit (always 1) + CCLF bit + Channel ID (0-63)
   - Chunk Type (1 byte): Type of data chunk
   - Timestamp (4 bytes): Timestamp in microseconds
   
   CNL byte structure (Section 2.1):
   - Bit 7 (MSB): Always 1 for channel messages (content ID range 0x8000-0xFFFF)
   - Bit 6: CCLF (config present for first fragment, last fragment flag for sequel)
   - Bits 5-0: Channel ID (0-63)"
  [^ByteBuffer buf total-size channel-id chunk-type timestamp has-config?]
  (let [channel-msg-bit 0x80               ; Bit 7 ALWAYS 1 for channel messages
        cclf-bit (if has-config? 0x40 0x00) ; Bit 6 is CCLF
        cnl-byte (bit-or channel-msg-bit cclf-bit (bit-and channel-id 0x3F))]
    (.putShort buf (short total-size))
    (.put buf (unchecked-byte cnl-byte))
    (.put buf (unchecked-byte chunk-type))
    (.putInt buf (int timestamp))))

(defn write-channel-config-header!
  "Write IDN-Stream channel configuration header to ByteBuffer.
   
   Per Section 2.2:
   - SCWC (1 byte): Service Configuration Word Count
   - CFL (1 byte): Channel flags (Routing, Close, SDM)
   - Service ID (1 byte): Target service identifier
   - Service Mode (1 byte): Service operating mode"
  [^ByteBuffer buf scwc flags service-id service-mode]
  (.put buf (unchecked-byte scwc))
  (.put buf (unchecked-byte flags))
  (.put buf (unchecked-byte service-id))
  (.put buf (unchecked-byte service-mode)))

(defn write-service-config-tags!
  "Write service configuration tags (16-bit words) to ByteBuffer."
  [^ByteBuffer buf tags]
  (doseq [tag tags]
    (.putShort buf (short tag))))

(defn write-frame-chunk-header!
  "Write frame samples data chunk header to ByteBuffer.
   
   Per Section 6.2:
   - Flags (1 byte): SCM bits and Once flag
   - Duration (3 bytes): Frame duration in microseconds"
  [^ByteBuffer buf flags duration-us]
  (.put buf (unchecked-byte flags))
  (.put buf (unchecked-byte (bit-and (bit-shift-right duration-us 16) 0xFF)))
  (.put buf (unchecked-byte (bit-and (bit-shift-right duration-us 8) 0xFF)))
  (.put buf (unchecked-byte (bit-and duration-us 0xFF))))

(defn write-point!
  "Write a single LaserPoint to ByteBuffer based on output config.
   
   Point values are normalized:
   - x, y: -1.0 to 1.0
   - r, g, b: 0.0 to 1.0
   
   Output format depends on config bit depths."
  [^ByteBuffer buf point output-config]
  (let [{:keys [color-bit-depth xy-bit-depth]} output-config
        ;; Convert using output-config functions
        x-out (output-config/normalized->output-xy (:x point) xy-bit-depth)
        y-out (output-config/normalized->output-xy (:y point) xy-bit-depth)
        r-out (output-config/normalized->output-color (:r point) color-bit-depth)
        g-out (output-config/normalized->output-color (:g point) color-bit-depth)
        b-out (output-config/normalized->output-color (:b point) color-bit-depth)]
    ;; Write XY
    (if (= xy-bit-depth 16)
      (do (.putShort buf (short x-out))
          (.putShort buf (short y-out)))
      (do (.put buf (unchecked-byte x-out))
          (.put buf (unchecked-byte y-out))))
    ;; Write RGB
    ;; Note: For 16-bit unsigned values (0-65535), we use unchecked-short
    ;; because Java short is signed (-32768 to 32767)
    (if (= color-bit-depth 16)
      (do (.putShort buf (unchecked-short r-out))
          (.putShort buf (unchecked-short g-out))
          (.putShort buf (unchecked-short b-out)))
      (do (.put buf (unchecked-byte r-out))
          (.put buf (unchecked-byte g-out))
          (.put buf (unchecked-byte b-out))))))


;; Frame to Packet Conversion


(defn frame->packet-with-config
  "Convert a LaserFrame to an IDN packet with channel configuration.
   Use this when opening a channel or when configuration needs to be sent.
   
   Parameters:
   - frame: LaserFrame record with :points vector (normalized values)
   - channel-id: IDN channel ID (0-63)
   - timestamp-us: Timestamp in microseconds
   - duration-us: Frame duration in microseconds
   - opts: Optional map with:
     - :service-id - Target service ID (default 0)
     - :service-mode - Service mode (default GRAPHIC_DISCRETE)
     - :output-config - OutputConfig for bit depth (default 8-bit color, 16-bit XY)
     - :close? - Set close flag (default false)
     - :single-scan? - Draw frame only once (default false)
   
   Returns: byte array ready to send"
  [frame channel-id timestamp-us duration-us & {:keys [service-id service-mode output-config close? single-scan?]
                                                :or {service-id DEFAULT_SERVICE_ID
                                                     service-mode SERVICE_MODE_GRAPHIC_DISCRETE
                                                     output-config output-config/default-config
                                                     close? false
                                                     single-scan? false}}]
  (let [points (take MAX_POINTS_PER_PACKET (:points frame))
        point-count (count points)
        tags (get-tags-for-config output-config)
        ;; Calculate SCWC with proper 32-bit alignment
        [scwc padded-tags] (calculate-scwc tags)
        total-size (packet-size-with-config point-count padded-tags output-config)
        cfl-flags (bit-or CFL_ROUTING (if close? CFL_CLOSE 0))
        chunk-flags (if single-scan? 0x01 0x00)
        buf (ByteBuffer/allocate total-size)]

    (.order buf ByteOrder/BIG_ENDIAN)

    (write-channel-message-header! buf total-size channel-id
                                   CHUNK_TYPE_FRAME_SAMPLES timestamp-us true)
    (write-channel-config-header! buf scwc cfl-flags service-id service-mode)
    (write-service-config-tags! buf padded-tags)  ; Use padded tags
    (write-frame-chunk-header! buf chunk-flags duration-us)

    (doseq [point points]
      (write-point! buf point output-config))

    (.array buf)))

(defn frame->packet
  "Convert a LaserFrame to an IDN packet without channel configuration.
   Use this for subsequent frames after channel is already configured.
   
   Parameters:
   - frame: LaserFrame record with :points vector (normalized values)
   - channel-id: IDN channel ID (0-63)
   - timestamp-us: Timestamp in microseconds
   - duration-us: Frame duration in microseconds
   - opts: Optional map with:
     - :output-config - OutputConfig for bit depth (default 8-bit color, 16-bit XY)
     - :single-scan? - Draw frame only once (default false)
   
   Returns: byte array ready to send"
  [frame channel-id timestamp-us duration-us & {:keys [output-config single-scan?]
                                                :or {output-config output-config/default-config
                                                     single-scan? false}}]
  (let [points (take MAX_POINTS_PER_PACKET (:points frame))
        point-count (count points)
        total-size (packet-size-without-config point-count output-config)
        chunk-flags (if single-scan? 0x01 0x00)
        buf (ByteBuffer/allocate total-size)]

    (.order buf ByteOrder/BIG_ENDIAN)

    (write-channel-message-header! buf total-size channel-id
                                   CHUNK_TYPE_FRAME_SAMPLES timestamp-us false)
    (write-frame-chunk-header! buf chunk-flags duration-us)

    (doseq [point points]
      (write-point! buf point output-config))

    (.array buf)))

(defn empty-frame-packet
  "Create a packet for an empty frame (no points).
   Per spec Section 6.2: empty sample array voids the frame buffer."
  ([channel-id timestamp-us]
   (empty-frame-packet channel-id timestamp-us output-config/default-config))
  ([channel-id timestamp-us output-config]
   (frame->packet (t/empty-frame) channel-id timestamp-us 0
                  :output-config output-config)))

(defn close-channel-packet
  "Create a packet to close a channel.
   Uses Void chunk type with Close flag set."
  [channel-id timestamp-us]
  (let [total-size (+ (channel-message-header-size) (channel-config-header-size))
        buf (ByteBuffer/allocate total-size)]

    (.order buf ByteOrder/BIG_ENDIAN)

    (write-channel-message-header! buf total-size channel-id
                                   CHUNK_TYPE_VOID timestamp-us true)
    (write-channel-config-header! buf 0 CFL_CLOSE 0 SERVICE_MODE_VOID)

    (.array buf)))


;; Packet Parsing (for validation and debugging)


(defn parse-channel-message-header
  "Parse channel message header from byte array.
   Returns map with header fields.
   
   CNL byte structure:
   - Bit 7: Always 1 for channel messages
   - Bit 6: CCLF (config present)
   - Bits 5-0: Channel ID"
  [^bytes packet]
  (when (>= (alength packet) 8)
    (let [buf (ByteBuffer/wrap packet)]
      (.order buf ByteOrder/BIG_ENDIAN)
      (let [total-size (.getShort buf)
            cnl-byte (bit-and (.get buf) 0xFF)
            chunk-type (bit-and (.get buf) 0xFF)
            timestamp (.getInt buf)]
        {:total-size total-size
         :is-channel-msg? (bit-test cnl-byte 7)  ; Bit 7: always 1 for channel messages
         :has-config? (bit-test cnl-byte 6)      ; Bit 6: CCLF (config present)
         :channel-id (bit-and cnl-byte 0x3F)
         :chunk-type chunk-type
         :timestamp timestamp}))))

(defn parse-channel-config-header
  "Parse channel configuration header from byte array at given offset.
   Returns map with config fields."
  [^bytes packet offset]
  (when (>= (alength packet) (+ offset 4))
    (let [buf (ByteBuffer/wrap packet)]
      (.order buf ByteOrder/BIG_ENDIAN)
      (.position buf offset)
      {:scwc (bit-and (.get buf) 0xFF)
       :flags (bit-and (.get buf) 0xFF)
       :service-id (bit-and (.get buf) 0xFF)
       :service-mode (bit-and (.get buf) 0xFF)})))

(defn packet-info
  "Get comprehensive information about a packet for debugging.
   Returns map with all parsed header fields."
  [^bytes packet]
  (when (>= (alength packet) 8)
    (let [msg-header (parse-channel-message-header packet)
          config-offset 8
          config-header (when (:has-config? msg-header)
                          (parse-channel-config-header packet config-offset))]
      (merge msg-header
             {:packet-size (alength packet)}
             (when config-header
               {:config config-header})))))

(defn validate-packet
  "Validate that a packet has correct structure.
   Returns map with :valid? boolean and optional :error string"
  [^bytes packet]
  (try
    (when (< (alength packet) 8)
      (throw (ex-info "Packet too small for channel message header"
                      {:size (alength packet) :min-required 8})))

    (let [info (packet-info packet)
          expected-size (:total-size info)
          actual-size (alength packet)]

      (when (not= expected-size actual-size)
        (throw (ex-info "Packet size mismatch"
                        {:expected expected-size :actual actual-size})))

      {:valid? true :info info})

    (catch Exception e
      {:valid? false
       :error (.getMessage e)
       :exception e})))


;; Utilities


(defn microseconds-now
  "Get current time in microseconds (for timestamps)"
  []
  (* (System/currentTimeMillis) 1000))

(defn frame-duration-us
  "Calculate frame duration in microseconds for given FPS"
  [fps]
  (long (/ 1000000 fps)))


;; Legacy compatibility - bytes-per-sample for default config
(def bytes-per-sample
  "Bytes per sample for default configuration (8-bit color, 16-bit XY): 
   X(2) + Y(2) + R(1) + G(1) + B(1) = 7"
  7)
