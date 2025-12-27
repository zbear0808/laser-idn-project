(ns laser-show.common.util)

(defn clamp
  "Given a numeric value, ensure it falls between the given values (inclusive).
  If either boundary is nil, it will be ignored. If `x` is nil, the `default`
  value is returned."
  ([x low]
   (clamp x low nil nil))
  ([x low high]
   (clamp x low high nil))
  ([x low high default]
   (cond
     (nil? x) default
     (and low  (< x low)) low
     (and high (> x high)) high
     :else x)))

(defn filter-indexed
  ([pred]
   (keep-indexed
    (fn [i x] (when (pred i x) x))))
  ([pred coll]
   (keep-indexed
    (fn [i x] (when (pred i x) x))
    coll)))

(defn remove-indexed
  ([pred]
   (filter-indexed (complement pred)))
  ([pred coll]
   (filter-indexed (complement pred) coll)))

(defn filterv-indexed
  [pred coll]
  (into [] (filter-indexed pred) coll))

(defn removev-indexed
  [pred coll]
  (into [] (remove-indexed pred) coll))

(defn keepv-indexed
  [pred coll]
  (into [] (keep-indexed pred) coll))

(defn mapv-indexed
  ([f coll]
   (into [] (map-indexed f) coll)))

(defn keepv
  "Like keep, but returns a vector."
  [f coll]
  (into [] (keep f) coll))

(defn removev
  "Like remove, but returns a vector."
  [pred coll]
  (into [] (remove pred) coll))

(defn filter-keys
  "Takes a map `m` and returns a new map containing only the key-value pairs
  for which `f` returns logical true for the key."
  [f m]
  (when m
    (persistent!
     (reduce-kv
      (fn [m k v]
        (if (f k) m (dissoc! m k)))
      (transient m)
      m))))


(defn filter-vals
  "Takes a map `m` and returns a new map containing only the key-value pairs
  for which `f` returns logical true for the value."
  [m f]
  (when m
    (persistent!
     (reduce-kv
      (fn [m k v]
        (if (f v) m (dissoc! m k)))
      (transient m)
      m))))

(defn remove-keys
  "Takes a map `m` and returns a new map containing only the key-value pairs
  for which `f` returns logical false for the key."
  [m f]
  (filter-keys (complement f) m))

(defn remove-vals
  "Takes a map `m` and returns a new map containing only the key-value pairs
  for which `f` returns logical false for the value."
  [m f]
  (filter-vals (complement f) m))


(defn map-into
  "Returns a new collection consisting of `init` with a pair `[(kf x) (vf x)]`
  added for each element `x` in `xs`. If `init` is unspecified, it will default
  to an empty map. If `vf` is unspecified, it will default to `identity`."
  ([kf xs] (map-into kf identity xs))
  ([kf vf xs] (map-into {} kf vf xs))
  ([init kf vf xs] (into init (map (juxt kf vf)) xs)))


(defmacro ->map [& symbols]
  "ex > (->map a b c)
   => {:a a, :b b, :c c}"
  (zipmap (map keyword symbols) symbols))



(defmacro ->map&
  [& args]
  (let [syms (take-while symbol? args)
        kvs  (drop-while symbol? args)
        entries (concat
                 (mapcat (fn [s] [(keyword s) s]) syms)
                 kvs)]
    (apply hash-map entries)))



(defn assoc-some
  "Associates one or more key-value pairs to the given map as long as
  value is non-nil."
  [m & kvs]
  (when-not (even? (count kvs))
    (throw (ex-info "assoc-some expects an even number of kv pairs" {})))
  (into (or m {})
        (comp
         (map vec)
         (filter (comp some? second)))
        (partition 2 kvs)))
