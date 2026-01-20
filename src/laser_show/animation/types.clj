(ns laser-show.animation.types
  "Core types for laser animation - using primitive float arrays for performance.
   
   Internal Data Format
   ====================
   
   LaserPoint is a 1D float array [x y r g b] using NORMALIZED VALUES:
   - Coordinates (x, y): -1.0 to 1.0 (normalized)
   - Colors (r, g, b): 0.0 to 1.0 (normalized)
   
   LaserFrame is a 2D float array where rows are points and columns are [x y r g b].
   
   Why floats instead of doubles?
   - 32-bit floats use half the memory of 64-bit doubles
   - Better CPU cache utilization for large frames
   - IDN output is max 16-bit anyway, so float precision is more than sufficient
   - SIMD operations can process 2x as many floats per cycle
   
   IDN-Stream Specification Compliance (Revision 002, July 2025)
   =============================================================
   
   At output time, LaserPoint values are converted to IDN-Stream format:
   
   | LaserPoint Index | IDN-Stream Range    | Configurable |
   |------------------|---------------------|--------------|
   | X (0)            | 8-bit or 16-bit     | Yes          |
   | Y (1)            | 8-bit or 16-bit     | Yes          |
   | R (2)            | 8-bit or 16-bit     | Yes          |
   | G (3)            | 8-bit or 16-bit     | Yes          |
   | B (4)            | 8-bit or 16-bit     | Yes          |
   
   Coordinate System (Section 3.4.7):
   - X: Positive = right, Negative = left (front projection)
   - Y: Positive = up, Negative = down
   - Origin (0,0) is at center of projection area
   
   Blanking (Section 3.4.11):
   - Blanking is indicated by r=g=b=0.0 (all color intensities zero)
   - There is no separate 'intensity' or 'blanking' field
   - Use `blanked-point` to create invisible points for beam repositioning")

(set! *unchecked-math* :warn-on-boxed)

;; Point field indices
(def ^:const X 0)
(def ^:const Y 1)
(def ^:const R 2)
(def ^:const G 3)
(def ^:const B 4)
(def ^:const POINT_SIZE 5)

;; Blanking constants
(def ^:const BLANKING_POINTS_PER_JUMP 2)

;; ========== Fast 2D Array Access Macros ==========
;;
;; Clojure's built-in aget/aset are terribly slow for 2D arrays (uses reflection).
;; Multi-dimensional aget is ~1660x slower than single-dimension due to reflection!
;; These macros provide massive speedup by properly type-hinting the intermediate access.
;;
;; Usage:
;;   (aget2d frame i X)      ; fast read
;;   (aset2d frame i X val)  ; fast write

(defmacro aget2d
  "Fast get from 2D double array. frame[i][j]
   Type-hints the intermediate 1D array access to avoid reflection."
  [a i j]
  `(aget ~(with-meta `(aget ~a ~i) {:tag "[D"}) ~j))

(defmacro aset2d
  "Fast set in 2D double array. frame[i][j] = v
   Type-hints the intermediate 1D array access to avoid reflection."
  [a i j v]
  `(aset ~(with-meta `(aget ~a ~i) {:tag "[D"}) ~j (double ~v)))

;; ========== Point Operations ==========

(defn make-point
  "Create a LaserPoint as a float array [x y r g b] with clamped values.
   
   All values must be normalized:
   - x, y: range [-1.0, 1.0], clamped if outside
   - r, g, b: range [0.0, 1.0], clamped if outside
   
   Per IDN-Stream spec, blanking is indicated by r=g=b=0.0."
  (^doubles [x y]
   (make-point x y 1.0 1.0 1.0))
  (^doubles [x y r g b]
   (double-array [(double (max -1.0 (min 1.0 (double x))))
                 (double (max -1.0 (min 1.0 (double y))))
                 (double (max 0.0 (min 1.0 (double r))))
                 (double (max 0.0 (min 1.0 (double g))))
                 (double (max 0.0 (min 1.0 (double b))))])))

(defn blanked-point
  "Create a blanked (invisible) point for beam repositioning.
   Per IDN-Stream spec Section 3.4.11, blanking is done by setting all color intensities to 0."
  ^doubles [x y]
  (double-array [(double (max -1.0 (min 1.0 (double x))))
                (double (max -1.0 (min 1.0 (double y))))
                (double 0.0) (double 0.0) (double 0.0)]))

(defn blanked?
  "Check if a point is blanked (invisible).
   A point is blanked when all color channels are zero (or very close to zero)."
  [^doubles point]
  (let [epsilon (double 1e-6)]
    (and (< (aget point R) epsilon)
         (< (aget point G) epsilon)
         (< (aget point B) epsilon))))

(defn clone-point
  "Create a copy of a point."
  ^doubles [^doubles point]
  (aclone point))

;; ========== Point Accessors ==========

(defn point-x
  "Get x coordinate from point."
  ^double [^doubles point]
  (double (aget point X)))

(defn point-y
  "Get y coordinate from point."
  ^double [^doubles point]
  (double (aget point Y)))

(defn point-r
  "Get red component from point."
  ^double [^doubles point]
  (double (aget point R)))

(defn point-g
  "Get green component from point."
  ^double [^doubles point]
  (double (aget point G)))

(defn point-b
  "Get blue component from point."
  ^double [^doubles point]
  (double (aget point B)))

;; ========== Frame Operations ==========

(defn make-frame
  "Create a LaserFrame as a 2D float array from a sequence of points.
   Each point must be a float array [x y r g b].
   Creates a new frame - use for converting from point sequences."
  ^"[[D" [points]
  (let [pts (vec points)
        n (count pts)]
    (if (zero? n)
      (make-array Double/TYPE 0 POINT_SIZE)
      (let [frame (make-array Double/TYPE n POINT_SIZE)]
        (dotimes [i n]
          (let [^doubles p (nth pts i)]
            (aset2d frame i X (aget p X))
            (aset2d frame i Y (aget p Y))
            (aset2d frame i R (aget p R))
            (aset2d frame i G (aget p G))
            (aset2d frame i B (aget p B))))
        frame))))

(defn empty-frame
  "Create an empty frame (0 points)."
  ^"[[D" []
  (make-array Double/TYPE 0 POINT_SIZE))

(defn frame-point-count
  "Get the number of points in a frame."
  ^long [^"[[D" frame]
  (alength frame))

(defn get-point
  "Get a point from frame as a float array. Returns a view, not a copy."
  ^doubles [^"[[D" frame ^long idx]
  (aget frame idx))

(defn clone-frame
  "Create a deep copy of a frame."
  ^"[[D" [^"[[D" frame]
  (let [n (alength frame)]
    (if (zero? n)
      (empty-frame)
      (let [result (make-array Double/TYPE n POINT_SIZE)]
        (dotimes [i n]
          (let [^doubles src (aget frame i)]
            (aset2d result i X (aget src X))
            (aset2d result i Y (aget src Y))
            (aset2d result i R (aget src R))
            (aset2d result i G (aget src G))
            (aset2d result i B (aget src B))))
        result))))

;; ========== Frame Field Access ==========

(defn frame-get-x
  "Get x coordinate at point index."
  ^double [^"[[D" frame ^long idx]
  (double (aget2d frame idx X)))

(defn frame-get-y
  "Get y coordinate at point index."
  ^double [^"[[D" frame ^long idx]
  (double (aget2d frame idx Y)))

(defn frame-get-r
  "Get red component at point index."
  ^double [^"[[D" frame ^long idx]
  (double (aget2d frame idx R)))

(defn frame-get-g
  "Get green component at point index."
  ^double [^"[[D" frame ^long idx]
  (double (aget2d frame idx G)))

(defn frame-get-b
  "Get blue component at point index."
  ^double [^"[[D" frame ^long idx]
  (double (aget2d frame idx B)))

;; ========== Frame Mutation Helpers ==========

(defn frame-set-x!
  "Set x coordinate at point index."
  [^"[[D" frame ^long idx val]
  (aset2d frame idx X val))

(defn frame-set-y!
  "Set y coordinate at point index."
  [^"[[D" frame ^long idx val]
  (aset2d frame idx Y val))

(defn frame-set-r!
  "Set red component at point index."
  [^"[[D" frame ^long idx val]
  (aset2d frame idx R val))

(defn frame-set-g!
  "Set green component at point index."
  [^"[[D" frame ^long idx val]
  (aset2d frame idx G val))

(defn frame-set-b!
  "Set blue component at point index."
  [^"[[D" frame ^long idx val]
  (aset2d frame idx B val))

;; ========== Internal Helpers ==========

(defn- copy-point-to!
  "Copy a point from src frame to dest frame at dest-idx."
  [^"[[D" dest ^long dest-idx ^"[[D" src ^long src-idx]
  (let [^doubles src-pt (aget src src-idx)]
    (aset2d dest dest-idx X (aget src-pt X))
    (aset2d dest dest-idx Y (aget src-pt Y))
    (aset2d dest dest-idx R (aget src-pt R))
    (aset2d dest dest-idx G (aget src-pt G))
    (aset2d dest dest-idx B (aget src-pt B))))

(defn- write-blanked-point!
  "Write a blanked (invisible) point at position."
  [^"[[D" frame ^long idx x y]
  (aset2d frame idx X x)
  (aset2d frame idx Y y)
  (aset2d frame idx R 0.0)
  (aset2d frame idx G 0.0)
  (aset2d frame idx B 0.0))

;; ========== Frame Concatenation ==========

(defn concat-frames-raw
  "Concatenate multiple frames into one WITHOUT blanking points.
   Use concat-frames-with-blanking for cue chain concatenation.
   Creates a new frame."
  ^"[[D" [frames]
  (let [frame-vec (vec frames)
        total-points (long (reduce (fn [^long acc ^"[[D" f] (+ acc (alength f))) 0 frame-vec))]
    (if (zero? total-points)
      (empty-frame)
      (let [result (make-array Double/TYPE total-points POINT_SIZE)
            !dest-idx (volatile! (long 0))]
        (doseq [^"[[D" frame frame-vec]
          (let [n (alength frame)
                start-idx (long @!dest-idx)]
            (dotimes [i n]
              (copy-point-to! result (+ start-idx i) frame i))
            (vswap! !dest-idx (fn [^long x] (+ x n)))))
        result))))

(defn concat-frames-with-blanking
  "Concatenate multiple frames with blanking points between them.
   
   Inserts blanking jump between each frame:
   1. Blanked point at last position of previous frame (turn off laser)
   2. Blanked point at first position of next frame (move to new position)
   
   This is the primary concatenation function for cue chain rendering.
   Creates a new frame."
  ^"[[D" [frames]
  (let [non-empty-frames (filterv #(pos? (alength ^"[[D" %)) frames)
        frame-count (long (count non-empty-frames))]
    (cond
      (zero? frame-count) (empty-frame)
      (= 1 frame-count) (clone-frame (first non-empty-frames))
      :else
      (let [;; Calculate total size: sum of all points + blanking points between frames
            points-in-frames (long (reduce (fn [^long acc ^"[[D" f] (+ acc (alength f))) 0 non-empty-frames))
            blanking-points (long (* BLANKING_POINTS_PER_JUMP (dec frame-count)))
            total-points (+ points-in-frames blanking-points)
            result (make-array Double/TYPE total-points POINT_SIZE)
            !dest-idx (volatile! (long 0))]
        
        ;; Copy first frame using dotimes
        (let [^"[[D" first-frame (first non-empty-frames)
              first-len (alength first-frame)]
          (dotimes [i first-len]
            (copy-point-to! result i first-frame i))
          (vreset! !dest-idx (long first-len)))
        
        ;; Copy remaining frames with blanking points
        (let [frames-with-prev (map vector
                                    (rest non-empty-frames)
                                    non-empty-frames)]
          (doseq [[^"[[D" curr-frame ^"[[D" prev-frame] frames-with-prev]
            (let [dest-idx (long @!dest-idx)
                  curr-len (long (alength curr-frame))
                  ;; Get last point of previous frame
                  ^doubles last-pt (aget prev-frame (dec (alength prev-frame)))
                  ;; Get first point of current frame
                  ^doubles first-pt (aget curr-frame 0)]
              ;; Write blanking jump
              (write-blanked-point! result dest-idx (aget last-pt X) (aget last-pt Y))
              (write-blanked-point! result (inc dest-idx) (aget first-pt X) (aget first-pt Y))
              ;; Copy current frame points
              (let [points-start (+ dest-idx (long BLANKING_POINTS_PER_JUMP))]
                (dotimes [i curr-len]
                  (copy-point-to! result (+ points-start i) curr-frame i))
                (vreset! !dest-idx (+ points-start curr-len))))))
        result))))

;; ========== Frame to Preview Conversion ==========

(defn frame->preview-points
  "Convert a frame to a vector of the internal point arrays.
   Used for preview rendering - consumer accesses values via types/X, types/Y, etc.
   Returns a vec of double arrays for efficient access."
  ^clojure.lang.PersistentVector [^"[[D" frame]
  (when (pos? (alength frame))
    (vec frame)))
