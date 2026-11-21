(ns laser-show.output.api
  "The output API for add-on drivers (API 1): the only namespace an add-on
   may depend on. See docs/OUTPUT_DRIVERS.md for the driver contract.

   - api-version: the version a driver's :api must be;
   - X Y R G B: the indexes of a point's values. A frame is a vector of
     [x y r g b] points, x and y normalized to -1..1 (+x right, +y up),
     colours to 0..1;
   - blanked?: whether a point is dark;
   - compile-layout, bytes-per-point, encode-points!, scale-xy and
     scale-color: the point encoder (laser-show.output.sample-format);
   - scan-duration-us: how long a frame takes to draw at a scan rate;
   - log: write to the app's log under a logger name of the add-on's own.

   Everything else here is a re-export: the behaviour is the namespaces'
   behind it. Nothing here reaches app state or events (01 §1.4 C-6)."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as tlog]
            [clojure.tools.logging.impl :as tlog-impl]
            [laser-show.animation.types :as t]
            [laser-show.idn.stream :as stream]
            [laser-show.output.sample-format :as sf]))

(def api-version
  "The output API this app has. A driver's :api must be this."
  1)


;; Points

(def ^:const X "Index of x in a point, -1 (left) to 1 (right)." t/X)
(def ^:const Y "Index of y in a point, -1 (down) to 1 (up)." t/Y)
(def ^:const R "Index of red in a point, 0 to 1." t/R)
(def ^:const G "Index of green in a point, 0 to 1." t/G)
(def ^:const B "Index of blue in a point, 0 to 1." t/B)

(defn blanked?
  "Whether point is dark: every colour below 1e-6 (a NaN counts as dark)."
  [point]
  (t/blanked? point))


;; The point encoder

(defn compile-layout
  "Check a sample layout, {:byte-order :big|:little :fields [[source type]
   …]}, and get it ready for encode-points!. Sources :x :y :r :g :b :i
   :dark? :lit? :zero; types :s8 :s16 :u8 :u12 :u16. Throws an ex-info with
   {:problems [...]} listing every problem."
  [layout]
  (sf/compile-layout layout))

(defn bytes-per-point
  "How many bytes a compiled layout encodes each point into."
  ^long [compiled]
  (sf/bytes-per-point compiled))

(defn encode-points!
  "Encode frame's points [start, end) at buf's (a java.nio.ByteBuffer)
   position by a compiled layout, and return how many were written. The
   values are clamped, scaled and rounded half away from zero; nothing is
   allocated per point. Throws a BufferOverflowException, having written
   nothing, when they don't fit."
  [compiled buf frame start end]
  (sf/encode-points! compiled buf frame start end))

(defn scale-xy
  "x or y (-1 to 1) as type (:s8 :s16 :u8 :u12 :u16), clamped and rounded
   half away from zero: ±32767 for :s16, 0-4095 for :u12."
  ^long [v type]
  (sf/scale-xy v type))

(defn scale-color
  "A colour (0 to 1) as an unsigned type (:u8 :u12 :u16), clamped and
   rounded half away from zero."
  ^long [c type]
  (sf/scale-color c type))


;; Timing

(defn scan-duration-us
  "How long a frame of n points takes to draw at scan-rate (pts/s), in µs:
   round(10^6 × (n − 1) / scan-rate), the first point untimed, at least 1
   and at most 0xFFFFFF."
  ^long [^long n ^long scan-rate]
  (stream/scan-duration-us n scan-rate))


;; Logging

(def ^:private log-levels [:trace :debug :info :warn :error])

(defn log
  "Write message to the app's log (clojure.tools.logging, so the app's
   logback setup) at level, one of :trace :debug :info :warn :error, under
   logger-name, a name of the add-on's own such as \"acme.nova\". With
   throwable, its stack trace goes with it. A plain function, so an add-on
   needs no macros and no logging library of its own:

     (api/log :warn \"acme.nova\" \"Nova at 10.0.0.9 is slow\")
     (api/log :error \"acme.nova\" e \"Nova failed\")

   Nothing is written when the logger has the level off. Returns nil.
   Throws an IllegalArgumentException for any other level."
  ([level logger-name message]
   (log level logger-name nil message))
  ([level logger-name throwable message]
   (when-not (some #{level} log-levels)
     (throw (IllegalArgumentException.
             (str "Unknown log level " (pr-str level) "; use one of " (str/join " " log-levels)))))
   (let [logger (tlog-impl/get-logger tlog/*logger-factory* (str logger-name))]
     (when (tlog-impl/enabled? logger level)
       (tlog/log* logger level (when (instance? Throwable throwable) throwable) (str message))))
   nil))
