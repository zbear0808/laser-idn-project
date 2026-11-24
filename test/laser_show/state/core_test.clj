(ns laser-show.state.core-test
  (:require [cljfx.api :as fx]
            [clojure.core.cache :as cache]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.logging.test :as log-test]
            [laser-show.state.core :as state]
            [laser-show.subs :as subs]))

(use-fixtures :each
  (fn [t]
    (state/init-state! {:timing {:bpm 120.0}})
    (try
      (t)
      (finally
        (state/guard-writes! nil)))))

(def ^:private writes
  {"swap-state!" #(state/swap-state! assoc-in [:timing :bpm] 99.0)
   "reset-state!" #(state/reset-state! {:timing {:bpm 99.0}})
   "reset-state-vals!" #(state/reset-state-vals! {:timing {:bpm 99.0}})
   "assoc-in-state!" #(state/assoc-in-state! [:timing :bpm] 99.0)
   "swap-state-in-epoch!" #(state/swap-state-in-epoch! (state/epoch) assoc-in [:timing :bpm] 99.0)
   "init-state!" #(state/init-state! {:timing {:bpm 99.0}})})

(defn- bpm [] (get-in (state/get-raw-state) [:timing :bpm]))

(deftest init-starts-an-epoch-that-writes-keep-test
  (let [epoch (state/epoch)]
    (is (number? epoch))
    (doseq [[write-name write!] (dissoc writes "init-state!")]
      (write!)
      (is (= epoch (state/epoch)) (str write-name " keeps it")))
    (state/init-state! {:timing {:bpm 120.0}})
    (is (< epoch (state/epoch)) "init-state! starts another")))

(deftest swap-in-epoch-writes-only-into-that-epoch-test
  (let [epoch (state/epoch)]
    (is (= [{:timing {:bpm 120.0}} {:timing {:bpm 99.0}}]
           (state/swap-state-in-epoch! epoch assoc-in [:timing :bpm] 99.0))
        "[old new], while the context is the one of epoch")
    (state/init-state! {:timing {:bpm 60.0}})
    (is (nil? (state/swap-state-in-epoch! epoch assoc-in [:timing :bpm] 99.0))
        "nil, once init-state! made another")
    (is (= 60.0 (bpm)) "which it leaves as it is")))

(deftest no-guard-writes-from-any-thread-test
  (doseq [[write-name write!] writes]
    (testing write-name
      (state/init-state! {:timing {:bpm 120.0}})
      @(future (write!))
      (is (= 99.0 (bpm))))))

(deftest strict-guard-refuses-a-write-off-the-event-thread-test
  (doseq [[write-name write!] writes]
    (testing write-name
      (state/guard-writes! nil)
      (state/init-state! {:timing {:bpm 120.0}})
      (state/guard-writes! {:allowed? (constantly false) :strict? true})
      (let [e (try (write!) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "the write throws")
        (is (re-find #"outside the event pipeline" (ex-message e)))
        (is (= (.getName (Thread/currentThread)) (:thread (ex-data e)))))
      (is (= 120.0 (bpm)) "the state is unchanged"))))

(deftest guard-allows-the-event-thread-test
  (let [event-thread (atom nil)
        allowed? #(= @event-thread (Thread/currentThread))]
    (state/guard-writes! {:allowed? allowed? :strict? true})
    (doseq [[write-name write!] writes]
      (testing write-name
        (state/guard-writes! nil)
        (state/init-state! {:timing {:bpm 120.0}})
        (state/guard-writes! {:allowed? allowed? :strict? true})
        @(future (reset! event-thread (Thread/currentThread))
                 (write!))
        (is (= 99.0 (bpm)))))))

(deftest lenient-guard-logs-and-writes-test
  (log-test/with-log
    (state/guard-writes! {:allowed? (constantly false) :strict? false})
    (state/swap-state! assoc-in [:timing :bpm] 99.0)
    (is (= 99.0 (bpm)) "the write lands")
    (is (= 1 (count (filter #(= :error (:level %)) (log-test/the-log))))
        "one ERROR")
    (is (log-test/logged? 'laser-show.state.core :error
                          clojure.lang.ExceptionInfo
                          #"outside the event pipeline"))))


;; Snapshot reads (reactivity plan 32): one map, read when the caller
;; decides, with no subscription and no cache access. Only a view's own
;; :fx/context makes a render dependency. cljfx's fx/sub-val on the same
;; root is the oracle for which map a root holds.

(defn- legacy-reads
  "The deprecated readers: [(get-state) (get-in-state path)]."
  [path]
  #_{:clj-kondo/ignore [:deprecated-var]}
  [(state/get-state) (state/get-in-state path)])

(defn- adapter-error
  "What root-context->snapshot throws for x, or nil."
  [x]
  (try (state/root-context->snapshot x) nil
       (catch clojure.lang.ExceptionInfo e e)))

(deftest pinned-cljfx-root-snapshot-test
  (testing "before init-state!: nil; after it, the map it was given, {} too"
    (with-redefs [state/*context (atom nil)]
      (is (nil? (state/root-context->snapshot nil)))
      (is (nil? (state/get-raw-state)))
      (is (= [nil nil] (legacy-reads [:timing :bpm])))
      (let [empty-state {}]
        (state/init-state! empty-state)
        (is (identical? empty-state (state/get-raw-state)) "{}, not nil")
        (is (= [{} nil] (legacy-reads [:timing :bpm])) "a missing path: nil"))))
  (testing "anything but a root context holding a map: an error naming the adapter and the pin"
    (doseq [x [{} {:timing {:bpm 120.0}} "context" [] (Object.) (fx/create-context [120.0])]]
      (let [e (adapter-error x)]
        (is (some? e) (pr-str x))
        (is (re-find #"root-context->snapshot" (str (ex-message e))))
        (is (re-find #"cljfx 1\.10\.6" (str (ex-message e)))))))
  (testing "the maps themselves, through the writes, as cljfx's fx/sub-val sees them"
    (let [a {:timing {:bpm 120.0}}
          a' (with-meta {:timing {:bpm 120.0}} {:copy true})
          _ (state/init-state! a)
          root-a @(state/get-context-atom)
          warmed (fx/sub-val root-a get-in [:timing :bpm])
          snapshot (state/get-raw-state)
          [replaced installed] (state/reset-state-vals! a')
          root-a' @(state/get-context-atom)
          [old new] (state/swap-state-in-epoch! (state/epoch) assoc-in [:timing :bpm] 142.0)
          root-b @(state/get-context-atom)
          _ (state/swap-state! assoc-in [:timing :bpm] 150.0)
          root-c @(state/get-context-atom)]
      (is (= 120.0 warmed))
      (testing "init-state!'s map"
        (is (identical? a snapshot))
        (is (identical? a (state/root-context->snapshot root-a)))
        (is (identical? a (fx/sub-val root-a identity))))
      (testing "reset-state-vals! with an equal map of other metadata: the one it replaced, the one it put"
        (is (= a a'))
        (is (identical? a replaced))
        (is (identical? a' installed))
        (is (identical? a' (state/root-context->snapshot root-a')))
        (is (identical? a' (fx/sub-val root-a' identity)))
        (is (= {:copy true} (meta (state/root-context->snapshot root-a')))))
      (testing "the old root, kept, still holds its map"
        (is (identical? a (state/root-context->snapshot root-a)))
        (is (identical? a (fx/sub-val root-a identity))))
      (testing "swap-state-in-epoch!'s [old new]"
        (is (identical? a' old))
        (is (identical? new (state/root-context->snapshot root-b)))
        (is (identical? new (fx/sub-val root-b identity)))
        (is (= 142.0 (get-in new [:timing :bpm]))))
      (testing "swap-state!: the current snapshot is the new root's map"
        (is (identical? (state/get-raw-state) (state/root-context->snapshot root-c)))
        (is (identical? (state/get-raw-state) (fx/sub-val root-c identity)))
        (is (= [150.0 150.0] (map #(get-in % [:timing :bpm]) (legacy-reads [])))))
      (is (every? true? (map identical? [a a' new] (map state/root-context->snapshot [root-a root-a' root-b])))
          "no write changed an earlier root's map"))))

(def ^:private leaf-evaluations (atom 0))

(defn- counted-bpm
  "A direct subscription (fx/sub-val's f) that counts its evaluations."
  [m]
  (swap! leaf-evaluations inc)
  (get-in m [:timing :bpm]))

(deftest snapshot-reads-leave-the-subscription-cache-alone-test
  ;; A cache of one entry: anything else that uses it evicts the leaf
  (with-redefs [state/cache-factory #(cache/lru-cache-factory % :threshold 1)]
    (state/init-state! {:timing {:bpm 120.0}}))
  (reset! leaf-evaluations 0)
  (let [root @(state/get-context-atom)
        evaluations (fn [] (fx/sub-val root counted-bpm) @leaf-evaluations)
        warm (evaluations)
        after-reads [(do (state/get-raw-state) (evaluations))
                     (do (legacy-reads [:timing :bpm]) (evaluations))
                     (do (state/root-context->snapshot root) (evaluations))
                     (do (state/epoch) (evaluations))]
        [replaced] (state/reset-state-vals! {:timing {:bpm 142.0}})]
    (is (= 1 warm))
    (is (= [1 1 1 1] after-reads) "each read left the leaf's entry: it still hits")
    (is (= 120.0 (get-in replaced [:timing :bpm])))
    (is (= 1 (evaluations)) "the replaced map came from the kept old root without using its cache")))

(defn- global-bpm
  "The control: a memoized selector reading BPM from the global snapshot
   instead of the context it is given, so it records no dependency."
  [_context]
  (second (legacy-reads [:timing :bpm])))

(deftest bound-bpm-follows-a-commit-test
  (let [a {:timing {:bpm 120.0}}
        b {:timing {:bpm 142.0}}
        _ (state/init-state! a)
        root-a @(state/get-context-atom)
        on-a [(fx/sub-ctx root-a global-bpm) (fx/sub-ctx root-a subs/bpm)]
        [replaced] (state/reset-state-vals! b)
        root-b @(state/get-context-atom)
        on-b [(fx/sub-ctx root-b global-bpm) (fx/sub-ctx root-b subs/bpm)]]
    (is (identical? a replaced))
    (is (= [120.0 120.0] on-a))
    (is (= 142.0 (second on-b)) "subs/bpm reads the context it is given: it follows the commit")
    (is (= 120.0 (first on-b)) "the global read recorded no dependency: A's value is reused for B")
    (is (= 142.0 (get-in (state/get-raw-state) [:timing :bpm])) "a fresh snapshot is B's")))
