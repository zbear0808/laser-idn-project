(ns laser-show.backend.packet-logger
  "Packet logging functionality for debugging IDN protocol communication.
   Logs packets to a file with format: PACKET_TYPE seq=N | HEX BYTES"
  (:require [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.io BufferedWriter]))

;; ============================================================================
;; Command Name Mapping
;; ============================================================================

(def ^:private command-names
  "Maps IDN-Hello command bytes to readable names."
  {0x08 "PING_REQUEST"
   0x09 "PING_RESPONSE"
   0x0C "CLIENT_GROUP_REQUEST"
   0x0D "CLIENT_GROUP_RESPONSE"
   0x10 "SCAN_REQUEST"
   0x11 "SCAN_RESPONSE"
   0x12 "SERVICE_MAP_REQUEST"
   0x13 "SERVICE_MAP_RESPONSE"
   0x40 "RT_CHANNEL_MESSAGE"
   0x41 "RT_CHANNEL_MESSAGE_ACK"
   0x44 "RT_CLOSE"
   0x45 "RT_CLOSE_ACK"
   0x46 "RT_ABORT"
   0x47 "RT_ACKNOWLEDGE"})

(defn command-name
  "Returns the readable name for a command byte, or UNKNOWN_XX if not recognized."
  [cmd-byte]
  (let [cmd (bit-and cmd-byte 0xFF)]
    (get command-names cmd (format "UNKNOWN_%02X" cmd))))

;; ============================================================================
;; Packet Formatting
;; ============================================================================

(defn- bytes-to-hex
  "Convert byte array to hex string for logging."
  [byte-array]
  (str/join " " (map #(format "%02X" (bit-and % 0xFF)) byte-array)))

(defn- parse-sequence-from-packet
  "Extract sequence number from packet header (bytes 2-3, big-endian)."
  [data]
  (if (>= (alength data) 4)
    (let [b2 (bit-and (aget data 2) 0xFF)
          b3 (bit-and (aget data 3) 0xFF)]
      (bit-or (bit-shift-left b2 8) b3))
    0))

(defn format-packet-log-entry
  "Formats a packet for logging: PACKET_TYPE seq=N | HEX BYTES"
  [data]
  (when (and data (pos? (alength data)))
    (let [cmd-byte (aget data 0)
          cmd-name (command-name cmd-byte)
          sequence (parse-sequence-from-packet data)
          hex-bytes (bytes-to-hex data)]
      (format "%s seq=%d | %s" cmd-name sequence hex-bytes))))

;; ============================================================================
;; File Operations
;; ============================================================================

(defn start-logging!
  "Opens a log file for packet logging. Returns a BufferedWriter handle.
   If the file already exists, it will be overwritten.
   Returns nil if the file cannot be opened."
  [file-path]
  (try
    (let [writer (io/writer file-path :append false)]
      (println (format "Packet logging started: %s" file-path))
      writer)
    (catch Exception e
      (println (format "Failed to start packet logging: %s" (.getMessage e)))
      nil)))

(defn stop-logging!
  "Closes the log file handle."
  [^BufferedWriter writer]
  (when writer
    (try
      (.close writer)
      (println "Packet logging stopped")
      (catch Exception e
        (println (format "Error closing log file: %s" (.getMessage e)))))))

(defn log-packet!
  "Writes a formatted packet entry to the log file.
   Returns true if successful, false otherwise."
  [^BufferedWriter writer data]
  (when writer
    (try
      (when-let [entry (format-packet-log-entry data)]
        (locking writer
          (.write writer entry)
          (.newLine writer)
          (.flush writer))
        true)
      (catch Exception e
        (println (format "Error logging packet: %s" (.getMessage e)))
        false))))

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn create-log-callback
  "Creates a logging callback function that can be passed to send-packet.
   The callback captures the writer in a closure."
  [writer]
  (when writer
    (fn [data]
      (log-packet! writer data))))
