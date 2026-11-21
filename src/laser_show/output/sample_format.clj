(ns laser-show.output.sample-format
  "The point encoder (plans/2026-11-19-output-drivers/01 §2): a driver
   describes its sample layout as data and frames are encoded into a
   java.nio.ByteBuffer.

     {:byte-order :big          ; or :little; :big when absent
      :fields [[:x :s16] [:y :s16] [:r :u16] [:g :u16] [:b :u16]]}

   - Sources: :x :y :r :g :b; :i (intensity, the largest of r, g and b);
     :dark? (1 when the point is blanked, t/blanked?, else 0); :lit? (its
     inverse: 1 when the point isn't blanked); :zero (padding).
   - Types: :s8 (1 byte), :s16 (2), :u8 (1), :u12 (2 bytes, 0-4095), :u16 (2).
   - x and y clamp to [-1, 1], colours to [0, 1], then scale and round half
     away from zero: signed v × (2^(n-1) - 1), unsigned x and y
     (v + 1)/2 × (2^n - 1), colours c × (2^n - 1). A signed colour is a
     layout error.

   encode-points! allocates nothing per point."
  (:require [clojure.string :as str]
            [laser-show.animation.types :as t])
  (:import [java.nio BufferOverflowException ByteBuffer]))

(set! *warn-on-reflection* true)

(def ^:private sources
  "Each source and the code the encoder dispatches on."
  {:x 0 :y 1 :r 2 :g 3 :b 4 :i 5 :dark? 6 :zero 7 :lit? 8})

(def ^:private source-order [:x :y :r :g :b :i :dark? :lit? :zero])

(def ^:private colour-sources #{:r :g :b :i})

(def ^:private types
  "Each type: its width in bytes, whether it is signed, and its full
   scale: 2^(n-1) - 1 when signed, 2^n - 1 when not."
  {:s8 {:width 1 :signed? true :scale 127}
   :s16 {:width 2 :signed? true :scale 32767}
   :u8 {:width 1 :signed? false :scale 255}
   :u12 {:width 2 :signed? false :scale 4095}
   :u16 {:width 2 :signed? false :scale 65535}})

(def ^:private type-order [:s8 :s16 :u8 :u12 :u16])

(defn- names [ks] (str/join " " (map str ks)))


;; Scaling

(defn- clamp ^double [^double v ^double lo ^double hi]
  (cond (< v lo) lo
        (> v hi) hi
        :else v))

(defn- round-away
  "v rounded to the nearest whole number, halves away from zero."
  ^long [^double v]
  (if (neg? v)
    (- (long (Math/floor (- 0.5 v))))
    (long (Math/floor (+ v 0.5)))))

(defn- xy-code
  "x or y as a type with full scale scale, signed when signed is 1."
  ^long [^double v ^long signed ^double scale]
  (let [v (clamp v -1.0 1.0)]
    (if (== 1 signed)
      (round-away (* v scale))
      (round-away (* (/ (+ v 1.0) 2.0) scale)))))

(defn- colour-code ^long [^double c ^double scale]
  (round-away (* (clamp c 0.0 1.0) scale)))

(defn- type-spec [type]
  (or (get types type)
      (throw (ex-info (str "unknown type " (pr-str type) " (one of " (names type-order) ")")
                      {:type type}))))

(defn scale-xy
  "x or y (normalized, -1 to 1) as type (:s8 :s16 :u8 :u12 :u16), clamped
   and rounded half away from zero: ±32767 for :s16, 0-4095 for :u12."
  ^long [v type]
  (let [{:keys [signed? scale]} (type-spec type)]
    (xy-code (double v) (if signed? 1 0) (double scale))))

(defn scale-color
  "A colour channel (0 to 1) as an unsigned type (:u8 :u12 :u16), clamped
   and rounded half away from zero: 0-255 for :u8. A signed type throws."
  ^long [c type]
  (let [{:keys [signed? scale]} (type-spec type)]
    (when signed?
      (throw (ex-info (str "a colour can't be signed: " type) {:type type})))
    (colour-code (double c) (double scale))))


;; Layouts

(defn- field-problems [i field]
  (if-not (and (vector? field) (= 2 (count field)))
    [(str "field " i " is not a [source type] pair")]
    (let [[source type] field]
      (cond-> []
        (not (contains? sources source))
        (conj (str "field " i ": unknown source " (pr-str source) " (one of " (names source-order) ")"))

        (not (contains? types type))
        (conj (str "field " i ": unknown type " (pr-str type) " (one of " (names type-order) ")"))

        (and (contains? colour-sources source) (get-in types [type :signed?]))
        (conj (str "colour " source " can't be signed"))))))

(defn- layout-problems [layout]
  (if-not (map? layout)
    ["a layout must be a map"]
    (let [{:keys [byte-order fields]} layout]
      (cond-> []
        (and (contains? layout :byte-order) (not (#{:big :little} byte-order)))
        (conj ":byte-order must be :big or :little")

        (not (and (vector? fields) (seq fields)))
        (conj ":fields must be a non-empty vector of [source type] pairs")

        (and (vector? fields) (seq fields))
        (into (comp (map-indexed field-problems) cat) fields)))))

(defn compile-layout
  "Check layout and get it ready for encode-points!. Throws an ex-info
   \"Invalid sample layout: …\" with {:problems [\"colour :r can't be
   signed\" …]}, every problem, when it is invalid."
  [layout]
  (let [problems (layout-problems layout)]
    (when (seq problems)
      (throw (ex-info (str "Invalid sample layout: " (str/join "; " problems))
                      {:problems problems})))
    (let [fields (:fields layout)
          specs (mapv (comp types second) fields)]
      {:layout layout
       :little? (= :little (:byte-order layout))
       :bytes-per-point (reduce + (map :width specs))
       :sources (int-array (map (comp sources first) fields))
       :widths (int-array (map :width specs))
       :signed (int-array (map #(if (:signed? %) 1 0) specs))
       :scales (double-array (map :scale specs))})))

(defn bytes-per-point
  "How many bytes compiled (compile-layout's) encodes each point into."
  ^long [compiled]
  (:bytes-per-point compiled))


;; Encoding

(defn- put-value!
  "Write v's low width bytes (1 or 2) at buf's position."
  [^ByteBuffer buf ^long v ^long width ^long little]
  (if (== 1 width)
    (.put buf (unchecked-byte v))
    (if (== 1 little)
      (do (.put buf (unchecked-byte v))
          (.put buf (unchecked-byte (bit-shift-right v 8))))
      (do (.put buf (unchecked-byte (bit-shift-right v 8)))
          (.put buf (unchecked-byte v))))))

(defn encode-points!
  "Encode frame's points [start, end) at buf's position, by compiled
   (compile-layout's), and return how many were written. The position moves
   on by that count × bytes-per-point; buf's own byte order isn't used or
   changed. Points are [x y r g b] (laser-show.animation.types). When the
   points don't fit in what remains of buf, nothing is written and it
   throws a BufferOverflowException."
  [compiled ^ByteBuffer buf frame start end]
  (let [start (long start)
        end (long end)
        ^ints sources (:sources compiled)
        ^ints widths (:widths compiled)
        ^ints signed (:signed compiled)
        ^doubles scales (:scales compiled)
        little (if (:little? compiled) 1 0)
        n-fields (alength sources)
        n (max 0 (- end start))]
    (when (< (.remaining buf) (* n (long (:bytes-per-point compiled))))
      (throw (BufferOverflowException.)))
    (loop [i start]
      (when (< i end)
        (let [point (nth frame i)
              x (double (nth point t/X))
              y (double (nth point t/Y))
              r (double (nth point t/R))
              g (double (nth point t/G))
              b (double (nth point t/B))]
          (loop [j 0]
            (when (< j n-fields)
              (let [scale (aget scales j)
                    v (case (aget sources j)
                        0 (xy-code x (aget signed j) scale)
                        1 (xy-code y (aget signed j) scale)
                        2 (colour-code r scale)
                        3 (colour-code g scale)
                        4 (colour-code b scale)
                        5 (colour-code (Math/max r (Math/max g b)) scale)
                        6 (if (t/blanked? point) 1 0)
                        8 (if (t/blanked? point) 0 1)
                        0)]
                (put-value! buf v (aget widths j) little)
                (recur (inc j)))))
          (recur (inc i)))))
    n))
