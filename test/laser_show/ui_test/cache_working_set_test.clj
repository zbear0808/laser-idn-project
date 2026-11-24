(ns laser-show.ui-test.cache-working-set-test
  "The app's subscription cache (state.core/cache-factory) holds what a
   render of the Grid tab with both chain editors open looks up, and as
   much again (reactivity plan 36). Each render looks up every
   subscription and function component on screen; a cache smaller than
   that evicts all of it each time, and every view body runs again for any
   change. Here a change no view reads runs no body, and another cell's
   rename only that cell's."
  (:require [cljfx.lifecycle :as lifecycle]
            [clojure.core.cache :as cache]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.app :as app]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- capacity
  "How many entries a cache the app's factory makes keeps."
  []
  (count (seq (reduce #(cache/miss %1 %2 %2) (state/cache-factory {}) (range 65536)))))

(defn- working-set
  "How many entries the app's last render looked up: those its context's
   cache tags with that context's generation (cljfx 1.10.6's private keys,
   the deps.edn pin)."
  []
  (let [ctx @(state/get-context-atom)
        generation (:cljfx.context/generation ctx)]
    (count (filter #(= generation (:cljfx.context/generation (val %)))
                   (seq @(:cljfx.context/*cache ctx))))))

(defn- showing? [title]
  (some #(and % (str/starts-with? % title)) (ui/window-titles)))

(deftest the-grid-and-both-editors-fit-in-half-the-cache-test
  (let [ran (atom [])
        call-context-fn @#'lifecycle/call-context-fn]
    (with-redefs-fn {#'lifecycle/call-context-fn (fn [context desc]
                                                   (swap! ran conj desc)
                                                   (call-context-fn context desc))}
      (fn []
        ;; Mounted again, so each function component's key in the cache has
        ;; the counting fn
        (app/start-test!)
        (ui/sync-fx!)
        (events/dispatch! {:event/type :cue-chain/open-editor :col 0 :row 0})
        (events/dispatch! {:event/type :effect-chain/open-editor :col 0 :row 3})
        (ui/wait-for! #(and (showing? "Cue Chain Editor") (showing? "Effects Chain")))
        (let [render! (fn [f & args]
                        (reset! ran [])
                        (apply events/write-state! f args)
                        (ui/sync-fx!)
                        @ran)
              unrelated! #(count (render! assoc ::unrelated %))]
          ;; The windows' first layouts can still size a view anew
          (loop [n 0]
            (when (and (< n 10) (pos? (unrelated! n)))
              (recur (inc n))))
          (testing "five changes no view reads: no view body runs"
            (is (= [0 0 0 0 0] (mapv unrelated! (range 10 15)))))
          (testing "a render looks up less than half of what the cache keeps"
            (let [n (working-set)]
              (is (< 500 n) "the Grid's and both editors' views and subscriptions")
              (is (< (* 2 n) (capacity)))))
          (testing "another cell's rename: only that cell's views run"
            (let [descs (render! assoc-in [:chains :cue-chains [3 0] :name] "Renamed")]
              (is (= #{[3 0]} (into #{} (map (juxt :col :row)) descs))))))))))
