(ns laser-show.backend.ilda
  "ILDA file parser.
   Reads ILDA (International Laser Display Association) format files.
   Supports formats 0, 1, 4, and 5."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [laser-show.animation.types :as t])
  (:import [java.nio ByteBuffer ByteOrder]))

(def ^:const ILDA_HEADER_SIZE 32)
(def ^:const FORMAT_3D_COORDS 0)
(def ^:const FORMAT_2D_COORDS 1)
(def ^:const FORMAT_COLOR_PALETTE 2)
(def ^:const FORMAT_3D_TRUE_COLOR 4)
(def ^:const FORMAT_2D_TRUE_COLOR 5)

(def default-palette
  "Standard ILDA palette (partial implementation).
   Colors are normalized [r g b] floats 0.0-1.0."
  (let [c (fn [r g b] [(/ (double r) 255.0) (/ (double g) 255.0) (/ (double b) 255.0)])]
    (reduce-kv assoc {}
               (vec (concat
                      [(c 0 0 0)       ; 0 Black/Blanking
                       (c 255 0 0)     ; 1 Red
                       (c 255 128 0)   ; 2 Orange
                       (c 255 255 0)   ; 3 Yellow
                       (c 0 255 0)     ; 4 Green
                       (c 0 255 255)   ; 5 Cyan
                       (c 0 0 255)     ; 6 Blue
                       (c 255 0 255)   ; 7 Magenta
                       (c 255 255 255)]; 8 White
                      (repeat 247 (c 255 255 255))))))) ; Fill rest with white for safety

(defn- get-palette-color [index palette]
  (get palette index [1.0 1.0 1.0]))

(defn- read-string-bytes [^ByteBuffer buf len]
  (let [b (byte-array len)]
    (.get buf b)
    (String. b "US-ASCII")))

(defn- read-header [^ByteBuffer buf]
  (when (>= (.remaining buf) ILDA_HEADER_SIZE)
    (let [start-pos (.position buf)
          signature (byte-array 4)]
      (.get buf signature)
      (if (= (String. signature "US-ASCII") "ILDA")
        (do
          (.position buf (+ (.position buf) 3)) ;; Skip 3 reserved bytes
          (let [format-code (bit-and (.get buf) 0xFF)
                frame-name (read-string-bytes buf 8)
                company-name (read-string-bytes buf 8)
                num-records (bit-and (.getShort buf) 0xFFFF)
                frame-num (bit-and (.getShort buf) 0xFFFF)
                total-frames (bit-and (.getShort buf) 0xFFFF)
                scanner-head (bit-and (.get buf) 0xFF)
                _ (.get buf)] ;; Skip 1 reserved byte
            {:format format-code
             :num-records num-records
             :frame-num frame-num
             :total-frames total-frames
             :scanner-head scanner-head
             :frame-name frame-name
             :company-name company-name}))
        (do
          (.position buf start-pos) ;; Reset position if not ILDA
          nil)))))

(defn- normalize-coord [val]
  ;; ILDA coordinates are signed 16-bit integers.
  ;; Spec says range is -32768 to 32767.
  ;; We normalize to -1.0 to 1.0.
  (/ (double val) 32768.0))

(defn- read-point-format-0 [^ByteBuffer buf palette]
  (let [x (.getShort buf)
        y (.getShort buf)
        z (.getShort buf)
        status (bit-and (.get buf) 0xFF)
        color-idx (bit-and (.get buf) 0xFF)
        blanked? (bit-test status 6)
        last-point? (bit-test status 7)
        [r g b] (if blanked? [0.0 0.0 0.0] (get-palette-color color-idx palette))]
    {:point [(normalize-coord x) (normalize-coord y) r g b]
     :blanked? blanked?
     :last-point? last-point?}))

(defn- read-point-format-1 [^ByteBuffer buf palette]
  (let [x (.getShort buf)
        y (.getShort buf)
        status (bit-and (.get buf) 0xFF)
        color-idx (bit-and (.get buf) 0xFF)
        blanked? (bit-test status 6)
        last-point? (bit-test status 7)
        [r g b] (if blanked? [0.0 0.0 0.0] (get-palette-color color-idx palette))]
    {:point [(normalize-coord x) (normalize-coord y) r g b]
     :blanked? blanked?
     :last-point? last-point?}))

(defn- read-point-format-4 [^ByteBuffer buf]
  (let [x (.getShort buf)
        y (.getShort buf)
        z (.getShort buf)
        status (bit-and (.get buf) 0xFF)
        b (bit-and (.get buf) 0xFF)
        g (bit-and (.get buf) 0xFF)
        r (bit-and (.get buf) 0xFF)
        blanked? (bit-test status 6)
        last-point? (bit-test status 7)
        ;; If blanked, force color to black
        [r g b] (if blanked? [0.0 0.0 0.0] [(/ (double r) 255.0) (/ (double g) 255.0) (/ (double b) 255.0)])]
    {:point [(normalize-coord x) (normalize-coord y) r g b]
     :blanked? blanked?
     :last-point? last-point?}))

(defn- read-point-format-5 [^ByteBuffer buf]
  (let [x (.getShort buf)
        y (.getShort buf)
        status (bit-and (.get buf) 0xFF)
        b (bit-and (.get buf) 0xFF)
        g (bit-and (.get buf) 0xFF)
        r (bit-and (.get buf) 0xFF)
        blanked? (bit-test status 6)
        last-point? (bit-test status 7)
        ;; If blanked, force color to black
        [r g b] (if blanked? [0.0 0.0 0.0] [(/ (double r) 255.0) (/ (double g) 255.0) (/ (double b) 255.0)])]
    {:point [(normalize-coord x) (normalize-coord y) r g b]
     :blanked? blanked?
     :last-point? last-point?}))

(defn- read-palette-entry [^ByteBuffer buf]
  (let [r (bit-and (.get buf) 0xFF)
        g (bit-and (.get buf) 0xFF)
        b (bit-and (.get buf) 0xFF)]
    [(/ (double r) 255.0) (/ (double g) 255.0) (/ (double b) 255.0)]))

(defn parse-ilda-bytes [^bytes data]
  (let [buf (ByteBuffer/wrap data)]
    (.order buf ByteOrder/BIG_ENDIAN)
    (loop [frames []
           current-palette default-palette]
      (if (or (not (.hasRemaining buf))
              (< (.remaining buf) ILDA_HEADER_SIZE))
        frames
        (let [header (read-header buf)]
          (if (or (nil? header) (zero? (:total-frames header))) ;; Null header check or invalid
             frames
             (let [{:keys [format num-records]} header]
               (cond
                 ;; Format 0: 3D Indexed
                 (= format FORMAT_3D_COORDS)
                 (let [points (vec (for [_ (range num-records)]
                                     (:point (read-point-format-0 buf current-palette))))]
                   (recur (conj frames {:points points :header header}) current-palette))

                 ;; Format 1: 2D Indexed
                 (= format FORMAT_2D_COORDS)
                 (let [points (vec (for [_ (range num-records)]
                                     (:point (read-point-format-1 buf current-palette))))]
                   (recur (conj frames {:points points :header header}) current-palette))

                 ;; Format 2: Palette
                 (= format FORMAT_COLOR_PALETTE)
                 (let [new-colors (for [_ (range num-records)]
                                    (read-palette-entry buf))
                       new-palette (reduce-kv assoc current-palette (vec new-colors))]
                   (recur frames new-palette))

                 ;; Format 4: 3D True Color
                 (= format FORMAT_3D_TRUE_COLOR)
                 (let [points (vec (for [_ (range num-records)]
                                     (:point (read-point-format-4 buf))))]
                   (recur (conj frames {:points points :header header}) current-palette))

                 ;; Format 5: 2D True Color
                 (= format FORMAT_2D_TRUE_COLOR)
                 (let [points (vec (for [_ (range num-records)]
                                     (:point (read-point-format-5 buf))))]
                   (recur (conj frames {:points points :header header}) current-palette))

                 :else
                 (do
                   (log/warn "Unknown or unsupported ILDA format:" format)
                   frames)))))))))

(defn read-ilda-file [filepath]
  (let [path (java.nio.file.Paths/get filepath (into-array String []))]
    (parse-ilda-bytes (java.nio.file.Files/readAllBytes path))))
