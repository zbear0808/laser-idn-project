(ns laser-show.backend.ilda-test
  (:require [clojure.test :refer :all]
            [laser-show.backend.ilda :as ilda]
            [clojure.java.io :as io])
  (:import [java.nio ByteBuffer ByteOrder]))

(defn create-ilda-header [format num-records]
  (let [buf (ByteBuffer/allocate 32)
        _ (.order buf ByteOrder/BIG_ENDIAN)]
    (.put buf (.getBytes "ILDA" "US-ASCII")) ;; 0-3
    (.put buf (byte-array 3)) ;; 4-6 reserved
    (.put buf (unchecked-byte format)) ;; 7 format code
    (.put buf (.getBytes "TESTFRAM" "US-ASCII")) ;; 8-15 name (8 bytes)
    (.put buf (.getBytes "COMPANY " "US-ASCII")) ;; 16-23 company (8 bytes)
    (.putShort buf (short num-records)) ;; 24-25 num records
    (.putShort buf (short 1)) ;; 26-27 frame num
    (.putShort buf (short 1)) ;; 28-29 total frames
    (.put buf (byte 0)) ;; 30 scanner head
    (.put buf (byte 0)) ;; 31 reserved
    (.array buf)))

(defn create-format-4-point [x y z r g b status]
  (let [buf (ByteBuffer/allocate 10)
        _ (.order buf ByteOrder/BIG_ENDIAN)]
    (.putShort buf (short x))
    (.putShort buf (short y))
    (.putShort buf (short z))
    (.put buf (unchecked-byte status))
    (.put buf (unchecked-byte b))
    (.put buf (unchecked-byte g))
    (.put buf (unchecked-byte r))
    (.array buf)))

(deftest parse-format-4-test
  (testing "Parsing Format 4 (3D True Color)"
    (let [header (create-ilda-header 4 2)
          p1 (create-format-4-point 0 0 0 255 0 0 0) ;; Red, center
          p2 (create-format-4-point 16384 -16384 0 0 255 0 64) ;; Green, Blanked (bit 6 set)
          data (byte-array (concat header p1 p2))]

      (let [frames (ilda/parse-ilda-bytes data)]
        (is (= 1 (count frames)))
        (let [frame (first frames)
              points (:points frame)]
          (is (= 2 (count points)))

          ;; Point 1
          (let [pt1 (first points)]
            ;; pt1 is [x y r g b]
            (is (= [0.0 0.0 1.0 0.0 0.0] pt1)))

          ;; Point 2
          (let [pt2 (second points)]
            ;; Should be blanked (black) because bit 6 is set, even though green component was 255
            (is (= [0.5 -0.5 0.0 0.0 0.0] pt2))))))))

(deftest read-ilda-file-test
  (testing "Reading ILDA file from disk (Format 5)"
    (let [header (create-ilda-header 5 1)
          ;; Format 5 point: X Y Status B G R (8 bytes)
          p1 (let [buf (ByteBuffer/allocate 8)]
               (.order buf ByteOrder/BIG_ENDIAN)
               (.putShort buf (short 32767)) ;; X = ~1.0
               (.putShort buf (short -32768)) ;; Y = -1.0
               (.put buf (unchecked-byte 0)) ;; Status
               (.put buf (unchecked-byte 255)) ;; B
               (.put buf (unchecked-byte 0))   ;; G
               (.put buf (unchecked-byte 0))   ;; R
               (.array buf))
          data (byte-array (concat header p1))
          temp-file (java.io.File/createTempFile "test" ".ild")]

      (io/copy data temp-file)

      (try
        (let [frames (ilda/read-ilda-file (.getAbsolutePath temp-file))]
          (is (= 1 (count frames)))
          (let [pt (first (:points (first frames)))]
            ;; pt is [x y r g b]
            (is (> (first pt) 0.9999))
            (is (= -1.0 (second pt)))
            ;; Format 5 is B G R, so we sent B=255. Result should be Blue.
            (is (= [0.0 0.0 1.0] (subvec pt 2 5)))))
        (finally
          (.delete temp-file))))))
