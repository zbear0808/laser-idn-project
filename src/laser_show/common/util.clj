(ns laser-show.common.util)

(defn clamp
  "Clamps numeric values (inclusive).
  If either boundary is nil, it will be ignored.
  If `x` is nil, the `default` value is returned."
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
  [f coll]
  (into [] (map-indexed f) coll))

(defn keepv
  [f coll]
  (into [] (keep f) coll))

(defn removev
  [pred coll]
  (into [] (remove pred) coll))

(defn mapcatv
  [f coll]
  (into [] (mapcat f) coll))

(defn consv
  [x coll]
  (into [x] coll))

(defn concatv
  [& colls]
  (into [] cat colls))

(defn filter-keys
  "Takes a map `m` and returns a new map containing only the key-value pairs
  for which `f` returns logical true for the key."
  [m f]
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
  ([key-fn coll] (map-into key-fn identity coll))
  ([key-fn val-fn coll] (map-into {} key-fn val-fn coll))
  ([init key-fn val-fn coll] (into init (map (juxt key-fn val-fn)) coll)))


(defmacro ->map [& symbols]
  "ex > (->map a b c)
   => {:a a, :b b, :c c}"
  (map-into keyword symbols)
  #_(zipmap (map keyword symbols) symbols))



(defmacro ->map&
  "ex > (->map& a b :c 42 :d d-value)
   => {:a a, :b b, :c 42, :d d-value}"
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

(defn merge-in
  "Merges a value into a nested map structure at the given path.
   If no existing value is found at the path, `default` is used as the base
   for the merge."
  [m path value & [default]]
  (let [existing (get-in m path default)
        merged (merge existing value)]
    (assoc-in m path merged)))


(defn exception->map
  "Converts an exception to a map with useful debugging information.
   Includes the message, class, cause, and full stacktrace."
  [^Throwable e]
  {:message (.getMessage e)
   :class (-> e .getClass .getName)
   :cause (when-let [cause (.getCause e)]
            (exception->map cause))
   :stacktrace (mapv str (.getStackTrace e))})
