;; Read-only diagnostics for ../2026-11-23-cljfx-reactivity-audit.md.
;; Run in a fresh JVM, from the repo root, with the app's JDK and OS alias:
;; clojure -J-Djavafx.enablePreview=true -J-Djavafx.suppressPreviewWarning=true -J--enable-native-access=ALL-UNNAMED -M:mac-arm:test-ui-headless plans/probes/cljfx_reactivity_2026_11_23.clj
;; The grid probe creates and deletes the real starter view on the headless FX
;; thread. No app/start!, services, devices, project file or live REPL involved.
(ns audit.cljfx-reactivity
  (:require [cljfx.api :as fx]
            [cljfx.lifecycle :as lifecycle]
            [clojure.core.cache :as cache]
            [clojure.repl :as repl]
            [clojure.string :as str]
            [laser-show.app :as app]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.views.root :as root])
  (:import [javafx.application Platform]))

(defn- opts [ctx]
  {:fx/context ctx
   :fx.opt/type->lifecycle #(or (fx/keyword->lifecycle %) (fx/fn->lifecycle-with-context %))
   :fx.opt/map-event-handler (constantly nil)})

(defn- dependencies []
  (state/init-state! {:timing {:bpm 120}})
  (let [ref (state/get-context-atom)
        global (fn [_] (state/get-in-state [:timing :bpm]))
        bound (fn [ctx] (fx/sub-val ctx get-in [:timing :bpm]))
        values #(mapv (partial fx/sub-ctx @ref) [global bound])
        before (values)]
    (state/swap-state! assoc-in [:timing :bpm] 142)
    {:before before :after (values) :database (get-in (state/get-raw-state) [:timing :bpm])}))

(defn- renderer-options []
  (let [lc (constantly :custom) err (fn [_]) seen (atom nil)
        capture (fn [& kvs] (reset! seen (apply hash-map kvs)) (fn [_]))
        check (fn [options]
                (with-redefs [fx/create-renderer capture fx/mount-renderer (fn [& _])]
                  (apply fx/create-app (atom (fx/create-context {}))
                         :event-handler (constantly {}) :desc-fn (constantly {}) options))
                {:custom-lifecycle? (identical? lc (get-in @seen [:opts :fx.opt/type->lifecycle]))
                 :custom-error-handler? (identical? err (:error-handler @seen))})]
    {:as-in-app (check [:opts {:fx.opt/type->lifecycle lc} :error-handler err])
     :documented-error-key (check [:renderer-error-handler err])}))

(defn- reachable-caches [ctx]
  ;; Inspect the pinned library's internals for this diagnostic only. Follow
  ;; ctx -> cache -> child-description key -> explicitly supplied context.
  (loop [work [ctx] seen #{}]
    (if-let [c (peek work)]
      (let [a (:cljfx.context/*cache c) work (pop work)]
        (if (seen a)
          (recur work seen)
          (let [children (keep (fn [[k _]] (when (map? (second k)) (:fx/context (second k)))) (seq @a))]
            (recur (into work children) (conj seen a)))))
      (count seen))))

(defn- child-props [explicit? updates cache-size]
  (let [count-child (atom 0) leaf (constantly :leaf)
        child (fn [{:keys [fx/context]}]
                (swap! count-child inc) (fx/sub-val context :stable)
                {:fx/type fx/ext-instance-factory :create leaf})
        parent (fn [{:keys [fx/context]}]
                 (fx/sub-val context :n)
                 (cond-> {:fx/type child} explicit? (assoc :fx/context context)))
        desc {:fx/type parent} ctx (fx/create-context {:n 0 :stable 1} #(cache/lru-cache-factory % :threshold cache-size))
        [last-ctx component] (reduce (fn [[c comp] n]
                                       (let [c' (fx/swap-context c assoc :n n)]
                                         [c' (fx/advance-component comp desc (opts c'))]))
                                     [ctx (fx/create-component desc (opts ctx))] (range 1 (inc updates)))]
    (try {:child-calls @count-child :reachable-cache-atoms (reachable-caches last-ctx)}
         (finally (fx/delete-component component (opts last-ctx))))))

(defn- starter-grid [capacity]
  (let [s (#'app/starter-state) desc {:fx/type root/root-view}
        ctx (fx/create-context s #(cache/lru-cache-factory % :threshold capacity))
        original @#'lifecycle/call-context-fn counts (atom {})
        instrumented (fn [c d]
                       (let [n (repl/demunge (.getName (class (:fx/type d))))]
                         (when (str/starts-with? n "laser-show.views.") (swap! counts update n (fnil inc 0))))
                       (original c d))]
    (state/init-state! s)
    (with-redefs-fn {#'lifecycle/call-context-fn instrumented #'events/dispatch! (constantly nil)}
      #(deref
        (fx/on-fx-thread
         (let [comp (fx/create-component desc (opts ctx)) initial (reduce + (vals @counts))
               [c comp updates] (reduce (fn [[c comp totals] n]
                                          (reset! counts {})
                                          (let [c' (fx/swap-context c assoc ::unobserved n)
                                                comp' (fx/advance-component comp desc (opts c'))]
                                            [c' comp' (conj totals (reduce + (vals @counts)))]))
                                        [ctx comp []] (range 5))]
           (fx/delete-component comp (opts c))
           {:capacity capacity :initial initial :view-calls-per-unrelated-change updates}))))))

(try
  (Platform/setImplicitExit false)
  (prn :global-vs-bound (dependencies))
  (prn :renderer-options (renderer-options))
  (doseq [[updates capacity] [[20 512] [100 8]]]
    (prn :child-props {:updates updates :capacity capacity
                       :injected (child-props false updates capacity)
                       :explicit (child-props true updates capacity)}))
  (doseq [capacity [512 4096]] (prn :starter-grid (starter-grid capacity)))
  (finally (Platform/exit) (shutdown-agents)))
