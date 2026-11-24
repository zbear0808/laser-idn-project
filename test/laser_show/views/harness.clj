(ns laser-show.views.harness
  "Views mounted on a context of their own, on the FX thread, with cljfx's
   context-aware lifecycle and the context in the options, as
   fx/create-app's renderer has them; no app, and events/dispatch! only
   records. Then what that context's cache holds: the function components,
   the description each one got and the one it returned, the contexts in
   them, and the bodies that ran for each render (reactivity plans 33 and
   34).

   It looks into a context and its cache by cljfx 1.10.6's private keys
   (the deps.edn pin), and counts bodies by wrapping cljfx's private
   call-context-fn: a diagnostic for tests only."
  (:require [cljfx.api :as fx]
            [cljfx.lifecycle :as lifecycle]
            [clojure.core.cache :as cache]
            [clojure.repl :as repl]
            [laser-show.events.core :as events])
  (:import [java.lang.reflect Field Modifier]
           [java.util IdentityHashMap]
           [javafx.application Platform]
           [javafx.scene Node Parent]))


;; The FX thread, cljfx's options


(defn on-fx
  "(f) on the FX thread: its value, or what it threw, thrown here."
  [f]
  (let [result (promise)]
    (Platform/runLater (bound-fn [] (deliver result (try [(f) nil] (catch Throwable t [nil t])))))
    (let [[v t] (deref result 60000 [nil (ex-info "No result from the FX thread in 60 s" {})])]
      (if t (throw t) v))))

(defn opts
  "The options a component on ctx gets from fx/create-app's renderer: ctx,
   cljfx's context-aware function lifecycle and a map event handler (one
   that does nothing)."
  [ctx]
  {:fx/context ctx
   :fx.opt/type->lifecycle #(or (fx/keyword->lifecycle %) (fx/fn->lifecycle-with-context %))
   :fx.opt/map-event-handler (fn [_])})

(defn mount!
  "desc created on a context of state with cache-factory's cache, then
   advanced once for each of changes (fns of the state), each on a context
   derived from the one before, as the app's commits are. (f step) after
   the last render, or with each? after the first render and after each
   change; step: {:ctx the context :root the component's node :ran the
   descriptions the function components whose bodies ran for that render
   got :dispatched each event events/dispatch! got so far}. Then the
   component deleted. All on the FX thread; f's value, or values."
  [state cache-factory desc changes f each?]
  (let [ran (atom [])
        dispatched (atom [])
        call-context-fn @#'lifecycle/call-context-fn]
    (with-redefs-fn {#'lifecycle/call-context-fn (with-meta (fn [context desc]
                                                              (swap! ran conj desc)
                                                              (call-context-fn context desc))
                                                   {::counts-bodies true})
                     #'events/dispatch! (fn [event & _] (swap! dispatched conj event) nil)}
      (fn []
        (on-fx
         (fn []
           (let [ctx (volatile! (fx/create-context state cache-factory))
                 component (volatile! (fx/create-component desc (opts @ctx)))
                 now #(let [step {:ctx @ctx :root (fx/instance @component) :ran @ran :dispatched @dispatched}]
                        (reset! ran [])
                        (f step))]
             (try
               (let [seen (volatile! (if each? [(now)] []))]
                 (doseq [change changes]
                   (reset! ran [])
                   (vswap! ctx fx/swap-context change)
                   (vswap! component fx/advance-component desc (opts @ctx))
                   (when each? (vswap! seen conj (now))))
                 (if each? @seen (now)))
               (finally (fx/delete-component @component (opts @ctx)))))))))))

(defn mounted
  "mount!: f's value after the last render."
  [state cache-factory desc changes f]
  (mount! state cache-factory desc changes f false))

(defn each-render
  "mount!: f's values after the first render and after each change."
  [state cache-factory desc changes f]
  (mount! state cache-factory desc changes f true))

(def lru-512
  "A 512-entry LRU, the app's cache before reactivity plan 36 (now
   state.core/cache-factory's 4096)."
  #(cache/lru-cache-factory % :threshold 512))

(def unbounded
  "A cache that keeps every entry: every description a function component
   got stays in it."
  cache/basic-cache-factory)

(defn unrelated
  "A change no view reads."
  [state]
  (update state ::unrelated (fnil inc 0)))


;; What a context's cache holds


(defn- context? [x]
  (and (map? x) (contains? x :cljfx.context/*cache)))

(defn- closed-over
  "What function f closes over."
  [f]
  (for [^Field field (.getDeclaredFields (class f))
        :when (not (Modifier/isStatic (.getModifiers field)))]
    (.get (doto field (.setAccessible true)) f)))

(defn contexts-in
  "The contexts in x: x itself, or one in its maps (keys and values),
   vectors, sets and realized sequences, a context ending the walk. With
   fns?, also one a function in it closes over."
  ([x] (contexts-in x false))
  ([x fns?]
   (let [seen (IdentityHashMap.)
         found (volatile! [])]
     (letfn [(walk [x]
               (cond
                 (context? x) (vswap! found conj x)
                 (or (nil? x) (.containsKey seen x)) nil
                 :else (do (.put seen x true)
                           (cond
                             (map? x) (run! (fn [[k v]] (walk k) (walk v)) x)
                             (or (vector? x) (set? x)) (run! walk x)
                             (instance? clojure.lang.LazySeq x) (when (realized? x) (run! walk x))
                             (sequential? x) (run! walk x)
                             (and fns? (fn? x)) (run! walk (closed-over x))))))]
       (walk x)
       @found))))

(def ^:private cljfx-call-context-fn
  "What cljfx subscribes a function component with: the component's cache
   key is [call-context-fn the-description-it-got] (mount! wraps it)."
  @#'lifecycle/call-context-fn)

(defn- component-key? [k]
  (and (vector? k)
       (let [f (first k)]
         (or (identical? cljfx-call-context-fn f) (::counts-bodies (meta f))))
       (map? (second k))))

(defn function-components
  "The function components in ctx's cache: {:desc the description each one
   got (before cljfx put its context in), :value the one it returned}."
  [ctx]
  (into []
        (keep (fn [[k entry]]
                (when (component-key? k)
                  {:desc (second k) :value (:cljfx.context/value entry)})))
        (seq @(:cljfx.context/*cache ctx))))

(defn reachable-cache-atoms
  "How many caches ctx's cache leads to: itself, then the cache of each
   context in the description a function component in a cache reached got,
   each cache once."
  [ctx]
  (loop [todo [(:cljfx.context/*cache ctx)] seen #{}]
    (if-let [a (peek todo)]
      (if (contains? seen a)
        (recur (pop todo) seen)
        (recur (into (pop todo)
                     (for [[k _] (seq @a)
                           :when (component-key? k)
                           c (contexts-in (second k))]
                       (:cljfx.context/*cache c)))
               (conj seen a)))
      (count seen))))

(defn type-name [t]
  (if (fn? t) (repl/demunge (.getName (class t))) t))

(defn with-context
  "Which of ctx's function components have a context: given one (in the
   description it got), giving one (in the one it returned), or keeping one
   in a function of either. Each a set of their types."
  [ctx]
  (let [types (fn [pred]
                (into (sorted-set) (comp (filter pred) (map (comp type-name :fx/type :desc))) (function-components ctx)))]
    {:given (types #(seq (contexts-in (:desc %))))
     :returned (types #(seq (contexts-in (:value %))))
     :closed-over (types #(seq (contexts-in [(:desc %) (:value %)] true)))}))

(def none {:given #{} :returned #{} :closed-over #{}})

(defn given
  "The descriptions function components of type f got, in ctx's cache."
  [ctx f]
  (into [] (keep #(when (identical? f (:fx/type (:desc %))) (:desc %))) (function-components ctx)))

(defn returned
  "The descriptions function components of type f returned."
  [ctx f]
  (into [] (keep #(when (identical? f (:fx/type (:desc %))) (:value %))) (function-components ctx)))

(defn ran-types
  "{type-name n}: how many bodies of each type ran for a step's render."
  [{:keys [ran]}]
  (frequencies (map (comp type-name :fx/type) ran)))

(defn ran-of
  "The descriptions the bodies of type f that ran for a step's render got."
  [{:keys [ran]} f]
  (filterv #(identical? f (:fx/type %)) ran))

(defn descs-in
  "Every map in desc's tree."
  [desc]
  (filter map? (tree-seq coll? seq desc)))


;; Nodes


(defn nodes
  "node and every node under it (a control's skin's too)."
  [^Node node]
  (cons node (when (instance? Parent node) (mapcat nodes (.getChildrenUnmodifiable ^Parent node)))))

(defn node-by-id [root id]
  (some #(when (= id (.getId ^Node %)) %) (nodes root)))

(defn kept-by-node
  "What node keeps for itself: its user data and its properties."
  [^Node node]
  [(.getUserData node) (into {} (.getProperties node))])
