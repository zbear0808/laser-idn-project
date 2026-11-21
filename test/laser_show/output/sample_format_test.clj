(ns laser-show.output.sample-format-test
  "The point encoder (plans/2026-11-19-output-drivers/01 §2): scaling,
   rounding, clamping, the sources, byte order, layout checks, and the
   invariants E-1 to E-5."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.types :as t]
            [laser-show.output.sample-format :as sf])
  (:import [com.sun.management ThreadMXBean]
           [java.lang.management ManagementFactory]
           [java.nio ByteBuffer]))

(defn- encode
  "The bytes layout encodes frame into, as unsigned ints."
  [layout frame]
  (let [compiled (sf/compile-layout layout)
        buf (ByteBuffer/allocate (* (sf/bytes-per-point compiled) (count frame)))]
    (sf/encode-points! compiled buf frame 0 (count frame))
    (mapv #(bit-and % 0xFF) (.array buf))))

(defn- encode-one
  "The value field [source type] takes for point, read back as a number
   (big-endian, signed for a signed type)."
  [point [source type]]
  (let [bytes (encode {:byte-order :big :fields [[source type]]} [point])
        v (reduce #(+ (* 256 %1) %2) 0 bytes)
        bits (* 8 (count bytes))]
    (if (and (#{:s8 :s16} type) (>= v (bit-shift-left 1 (dec bits))))
      (- v (bit-shift-left 1 bits))
      v)))

(defn- pt
  ([x y] [x y 0.0 0.0 0.0])
  ([x y r g b] [x y r g b]))

(deftest endpoints-test
  (testing "x and y, signed: ±(2^(n-1) - 1), as IDN's normalized->output-xy"
    (is (= -32767 (sf/scale-xy -1.0 :s16)))
    (is (= 32767 (sf/scale-xy 1.0 :s16)))
    (is (= 0 (sf/scale-xy 0.0 :s16)))
    (is (= [-127 0 127] (mapv #(sf/scale-xy % :s8) [-1.0 0.0 1.0]))))
  (testing "x and y, unsigned: (v + 1)/2 × (2^bits - 1)"
    (is (= [0 2048 4095] (mapv #(sf/scale-xy % :u12) [-1.0 0.0 1.0])))
    (is (= [0 128 255] (mapv #(sf/scale-xy % :u8) [-1.0 0.0 1.0])))
    (is (= [0 32768 65535] (mapv #(sf/scale-xy % :u16) [-1.0 0.0 1.0]))))
  (testing "colours: c × (2^bits - 1)"
    (is (= 65535 (sf/scale-color 1.0 :u16)))
    (is (= [0 255] (mapv #(sf/scale-color % :u8) [0.0 1.0])))
    (is (= [0 4095] (mapv #(sf/scale-color % :u12) [0.0 1.0]))))
  (testing "the same through the encoder"
    (is (= -32767 (encode-one (pt -1.0 0.0) [:x :s16])))
    (is (= 32767 (encode-one (pt 1.0 0.0) [:x :s16])))
    (is (= 4095 (encode-one (pt 0.0 1.0) [:y :u12])))
    (is (= 65535 (encode-one (pt 0.0 0.0 1.0 0.0 0.0) [:r :u16])))
    (is (= 255 (encode-one (pt 0.0 0.0 0.0 0.0 1.0) [:b :u8])))))

(deftest rounds-half-away-from-zero-test
  (is (= 16384 (sf/scale-xy 0.5 :s16)) "16383.5")
  (is (= -16384 (sf/scale-xy -0.5 :s16)) "-16383.5")
  (is (= -16384 (encode-one (pt 0.0 -0.5) [:y :s16])))
  (is (= 128 (sf/scale-color 0.5 :u8)) "127.5")
  (is (= 128 (encode-one (pt 0.0 0.0 0.5 0.0 0.0) [:r :u8])))
  (is (= 2048 (sf/scale-xy 0.0 :u12)) "2047.5")
  (is (= 64 (sf/scale-xy 0.5 :s8)) "63.5")
  (is (= -64 (sf/scale-xy -0.5 :s8)) "-63.5")
  (is (= 16383 (sf/scale-xy (/ 16383.4 32767) :s16)) "below the half rounds toward zero")
  (is (= -16383 (sf/scale-xy (/ -16383.4 32767) :s16))))

(deftest clamps-test
  (is (= 32767 (sf/scale-xy 2.0 :s16)))
  (is (= -32767 (sf/scale-xy -7.5 :s16)))
  (is (= 4095 (sf/scale-xy 1.5 :u12)))
  (is (= 0 (sf/scale-xy -1.5 :u12)))
  (is (= 255 (sf/scale-color 3.0 :u8)))
  (is (= 0 (sf/scale-color -0.5 :u16)))
  (is (= 32767 (encode-one (pt 2.0 0.0) [:x :s16])) "x 2.0 :s16 (01 §2)")
  (is (= 65535 (encode-one (pt 0.0 0.0 0.0 1.2 0.0) [:g :u16])))
  (is (= 0 (encode-one (pt 0.0 0.0 0.0 -1.0 0.0) [:g :u16])))
  (testing "E-4: nothing finite throws"
    (doseq [v [Double/MAX_VALUE (- Double/MAX_VALUE) 1e300 -1e-300 0.0 -0.0]]
      (is (= [(sf/scale-xy (max -1.0 (min 1.0 v)) :s16)
              (sf/scale-color (max 0.0 (min 1.0 v)) :u16)]
             [(encode-one [v 0.0 0.0 0.0 0.0] [:x :s16])
              (encode-one [0.0 0.0 v 0.0 0.0] [:r :u16])])
          (str v)))))

(deftest intensity-is-the-largest-channel-test
  (is (= 230 (encode-one [0 0 0.2 0.9 0.1] [:i :u8])) "01 §2's example")
  (is (= 255 (encode-one [0 0 1.0 0.0 0.0] [:i :u8])))
  (is (= 255 (encode-one [0 0 0.0 0.0 2.0] [:i :u8])) "clamped, as the colours are")
  (is (= 32768 (encode-one [0 0 0.1 0.5 0.3] [:i :u16])))
  (is (= 0 (encode-one [0 0 0.0 0.0 0.0] [:i :u12]))))

(deftest dark-point-encodes-zero-colour-test
  (let [layout {:byte-order :big
                :fields [[:r :u16] [:g :u8] [:b :u12] [:i :u16] [:dark? :u8] [:zero :u16]]}]
    (testing "E-3: 0 in every colour field and in :i, 1 in :dark?"
      (doseq [dark [[0.3 -0.2 0.0 0.0 0.0] [0.3 -0.2 1e-7 5e-7 9.9e-7] [0.3 -0.2 -0.5 0.0 -1.0]]]
        (is (t/blanked? dark))
        (is (= [0 0 0 0 0 0 0 1 0 0] (encode layout [dark])) (pr-str dark))))
    (testing "a lit point: 0 in :dark?, and :zero is always 0"
      (is (= [0xFF 0xFF 0 0 0 0xFF 0xFF 0 0 0] (encode layout [[0 0 1.0 0.0 0.0]])))))
  (is (= 1 (encode-one [0 0 0 0 0] [:dark? :s16])) "any type will do for :dark?"))

(deftest lit-source-test
  (testing "G9: :lit? is 1 when the point isn't dark, the inverse of :dark?"
    (is (= 1 (encode-one [0 0 1.0 0.0 0.0] [:lit? :u8])))
    (is (= 1 (encode-one [0 0 0.0 0.0 1e-5] [:lit? :u8])) "one colour at 1e-6 or more is lit")
    (is (= 0 (encode-one [0.3 -0.2 0.0 0.0 0.0] [:lit? :u8])))
    (is (= 0 (encode-one [0.3 -0.2 1e-7 5e-7 9.9e-7] [:lit? :u8])) "all below 1e-6 is dark (t/blanked?)")
    (is (= 0 (encode-one [0.3 -0.2 -0.5 0.0 -1.0] [:lit? :u8])) "negative colours are dark too"))
  (testing "next to :dark?, always the other one"
    (let [layout {:byte-order :big :fields [[:dark? :u8] [:lit? :u8]]}]
      (is (= [0 1] (encode layout [[0 0 0.2 0.9 0.1]])))
      (is (= [1 0] (encode layout [[0 0 0 0 0]])))
      (doseq [point [[0 0 0 0 0] [0.5 0.5 1 1 1] [0 0 1e-6 0 0] [0 0 9e-7 0 0]]]
        (let [[dark lit] (encode layout [point])]
          (is (= 1 (+ dark lit)) (pr-str point))
          (is (= (if (t/blanked? point) 0 1) lit) (pr-str point))))))
  (testing "any type, like :dark?"
    (is (= 1 (encode-one [0 0 1 1 1] [:lit? :s16])))
    (is (= 1 (encode-one [0 0 1 1 1] [:lit? :u12])))
    (is (= 4 (sf/bytes-per-point (sf/compile-layout {:fields [[:lit? :s8] [:dark? :s8] [:x :s16]]}))))))

(deftest little-endian-frame-test
  (let [layout {:byte-order :little
                :fields [[:x :u12] [:y :u12] [:r :u8] [:g :u8] [:b :u8] [:i :u8]]}]
    (is (= 8 (sf/bytes-per-point (sf/compile-layout layout))))
    (is (= [0x00 0x08 0xFF 0x0F 0x33 0xE6 0x1A 0xE6] (encode layout [[0.0 1.0 0.2 0.9 0.1]]))
        "01 §2's frame example"))
  (testing "the same point, big-endian"
    (is (= [0x08 0x00 0x0F 0xFF 0x33 0xE6 0x1A 0xE6]
           (encode {:byte-order :big :fields [[:x :u12] [:y :u12] [:r :u8] [:g :u8] [:b :u8] [:i :u8]]}
                   [[0.0 1.0 0.2 0.9 0.1]]))))
  (testing "signed 16-bit, both orders: -16384 is C000"
    (is (= [0xC0 0x00] (encode {:byte-order :big :fields [[:x :s16]]} [[-0.5 0 0 0 0]])))
    (is (= [0x00 0xC0] (encode {:byte-order :little :fields [[:x :s16]]} [[-0.5 0 0 0 0]])))
    (is (= [0x81] (encode {:byte-order :big :fields [[:y :s8]]} [[0 -1.0 0 0 0]])) "-127")))

(deftest encode-writes-whole-points-test
  (let [compiled (sf/compile-layout {:byte-order :big :fields [[:x :s16] [:y :s16] [:r :u8] [:g :u8] [:b :u8]]})
        frame (vec (for [i (range 10)] [(/ i 10.0) 0.0 0.0 1.0 0.0]))
        buf (ByteBuffer/allocate 100)]
    (is (= 7 (sf/bytes-per-point compiled)))
    (.position buf 3)
    (testing "E-1: points [start, end) at the position, which moves on by count × bytes-per-point"
      (is (= 4 (sf/encode-points! compiled buf frame 2 6)))
      (is (= (+ 3 (* 4 7)) (.position buf)))
      (is (= 6553 (.getShort buf 3)) "the first is point 2: 0.2 × 32767 = 6553.4 → 6553"))
    (is (= 0 (sf/encode-points! compiled buf frame 5 5)) "an empty range writes nothing")
    (is (= 31 (.position buf)))
    (testing "a range that doesn't fit writes nothing and throws"
      (is (thrown? java.nio.BufferOverflowException (sf/encode-points! compiled buf frame 0 10)))
      (is (= 31 (.position buf))))))

(deftest invalid-layout-lists-problems-test
  (let [problems (fn [layout]
                   (try (sf/compile-layout layout) nil
                        (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (is (= {:problems ["colour :r can't be signed"]} (problems {:byte-order :big :fields [[:r :s16]]})) "01 §2")
    (is (= {:problems ["colour :g can't be signed" "colour :i can't be signed"]}
           (problems {:byte-order :big :fields [[:x :s16] [:g :s8] [:i :s16]]}))
        "every problem, in order")
    (is (= {:problems [":byte-order must be :big or :little"]} (problems {:byte-order :middle :fields [[:x :s16]]})))
    (is (= {:problems [":fields must be a non-empty vector of [source type] pairs"]} (problems {:byte-order :big :fields []})))
    (is (= {:problems [":fields must be a non-empty vector of [source type] pairs"]} (problems {:byte-order :big})))
    (is (= {:problems ["field 0 is not a [source type] pair"
                       "field 1: unknown source :z (one of :x :y :r :g :b :i :dark? :lit? :zero)"
                       "field 2: unknown type :u32 (one of :s8 :s16 :u8 :u12 :u16)"]}
           (problems {:byte-order :big :fields [:x [:z :s16] [:y :u32]]})))
    (is (= {:problems ["a layout must be a map"]} (problems [[:x :s16]])))
    (is (= "Invalid sample layout: colour :r can't be signed"
           (try (sf/compile-layout {:byte-order :big :fields [[:r :s16]]})
                (catch clojure.lang.ExceptionInfo e (ex-message e))))))
  (testing "valid layouts"
    (is (= 10 (sf/bytes-per-point (sf/compile-layout {:byte-order :big :fields [[:x :s16] [:y :s16] [:r :u16] [:g :u16] [:b :u16]]}))))
    (is (= 4 (sf/bytes-per-point (sf/compile-layout {:byte-order :little :fields [[:x :s8] [:zero :s8] [:dark? :u16]]}))))
    (is (= 2 (sf/bytes-per-point (sf/compile-layout {:fields [[:x :u8] [:y :u8]]})))
        "no :byte-order: big-endian")))

(deftest scale-functions-reject-unknown-types-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown type :u32" (sf/scale-xy 0.0 :u32)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"can't be signed" (sf/scale-color 0.5 :s16))))

(def ^:private types [:s8 :s16 :u8 :u12 :u16])

(def ^:private full-scale {:s8 127 :s16 32767 :u8 255 :u12 4095 :u16 65535})

(deftest monotonic-property-test
  (testing "E-2: the mapping never decreases as the input grows, the endpoints are exact,
            and the encoder agrees with scale-xy and scale-color (seeded, 2,000 cases)"
    (let [rng (java.util.Random. 20261120)]
      (dotimes [_ 2000]
        (let [type (nth types (.nextInt rng (count types)))
              color-type (nth (remove #{:s8 :s16} types) (.nextInt rng 3))
              a (- (* 3.0 (.nextDouble rng)) 1.5)
              b (+ a (* (.nextDouble rng) (if (.nextBoolean rng) 1e-4 1.0)))
              ca (- (* 1.6 (.nextDouble rng)) 0.3)
              cb (+ ca (* (.nextDouble rng) (if (.nextBoolean rng) 1e-4 1.0)))]
          (testing (pr-str [type a b color-type ca cb])
            (is (<= (sf/scale-xy a type) (sf/scale-xy b type)))
            (is (<= (sf/scale-color ca color-type) (sf/scale-color cb color-type)))
            (is (= (sf/scale-xy a type) (encode-one [a 0.0 0.0 0.0 0.0] [:x type])))
            (is (= (sf/scale-xy b type) (encode-one [0.0 b 0.0 0.0 0.0] [:y type])))
            (is (= (sf/scale-color ca color-type) (encode-one [0.0 0.0 0.0 0.0 ca] [:b color-type])))
            (is (<= (if (#{:s8 :s16} type) (- (full-scale type)) 0) (sf/scale-xy a type) (full-scale type))
                "in range")))))
    (doseq [type types]
      (is (= (if (#{:s8 :s16} type) (- (full-scale type)) 0) (sf/scale-xy -1.0 type)) (str type))
      (is (= (full-scale type) (sf/scale-xy 1.0 type)) (str type)))
    (doseq [type [:u8 :u12 :u16]]
      (is (= 0 (sf/scale-color 0.0 type)))
      (is (= (full-scale type) (sf/scale-color 1.0 type))))))

(defn- allocated-bytes ^long []
  (.getCurrentThreadAllocatedBytes ^ThreadMXBean (ManagementFactory/getThreadMXBean)))

(deftest encode-allocates-nothing-per-point-test
  (testing "E-5: bytes this thread allocated encoding 100 frames of 1,000 points
            (a count, not a timing), with every source in the layout. One boxed
            double per point would be 1.6 MB; the bound is a fraction of a byte per
            point, which leaves room for the per-call result and the counter itself"
    (let [compiled (sf/compile-layout {:byte-order :little
                                       :fields [[:x :s16] [:y :u12] [:r :u16] [:g :u8] [:b :u12]
                                                [:i :u8] [:dark? :u8] [:lit? :u8] [:zero :s8]]})
          rng (java.util.Random. 7)
          frame (vec (for [i (range 1000)]
                       (if (even? i)
                         [(.nextDouble rng) (- (.nextDouble rng)) 0.0 0.0 0.0]
                         [(- (* 2.4 (.nextDouble rng)) 1.2) (.nextDouble rng)
                          (.nextDouble rng) (.nextDouble rng) (* 1.2 (.nextDouble rng))])))
          buf (ByteBuffer/allocateDirect (* 1000 (sf/bytes-per-point compiled)))
          encode-100! #(dotimes [_ 100]
                         (.clear buf)
                         (sf/encode-points! compiled buf frame 0 1000))]
      (encode-100!)
      (allocated-bytes)
      (let [before (allocated-bytes)]
        (encode-100!)
        (let [bytes (- (allocated-bytes) before)]
          (is (< bytes 16384) (str bytes " bytes for 100,000 points")))))))
