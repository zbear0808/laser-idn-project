(ns laser-show.vibes.refs-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.vibes.refs :as refs]))

(def ^:private vibe
  {:id :v :name "V" :allow-audience? false
   :hit {:cells [[0 0] [1 0] [2 0]] :select :shuffle :mode :decay :beats 1.0}
   :accent {:cells [[0 0]] :select :shuffle :mode :decay :beats 2.0}
   :flash {:cue [0 0] :fx [[0 0] [3 3]] :solo? true}
   :bed [0 0]
   :fx [[0 0] [1 1]]})

(def ^:private state
  {:config {:vibes {:order [:v] :next-id 2 :library {:v vibe}}}
   :vibe-runtime {:voices {:hit {:cell [0 0] :run :r1 :mode :decay}
                           :bed {:cell [0 0] :run :r2}
                           :flash {:cell [0 0] :run :r3 :fx [[0 0]] :fx-on [[0 0]] :solo? true}}
                  :duck {:accent {:cell [0 0] :mode :sustain}}
                  :fx-on [[0 0] [1 1]]
                  :bags {[:v :hit] {:items [[0 0] [1 0] [2 0]] :remaining [[2 0] [0 0]] :last [1 0] :seed 1 :n 1}}}})

(defn- v [state] (get-in state [:config :vibes :library :v]))

(deftest move-cue-rewrites-every-role-test
  (let [moved (refs/move-cue state [0 0] [5 5])]
    (is (= [[5 5] [1 0] [2 0]] (get-in (v moved) [:hit :cells])) "order kept")
    (is (= [[5 5]] (get-in (v moved) [:accent :cells])))
    (is (= [5 5] (get-in (v moved) [:flash :cue])))
    (is (= [5 5] (:bed (v moved))))
    (testing "the voices and what a flash ducked follow, so a release still finds its cue"
      (is (= [[5 5] [5 5] [5 5]] (map #(get-in moved [:vibe-runtime :voices % :cell]) [:hit :bed :flash])))
      (is (= [5 5] (get-in moved [:vibe-runtime :duck :accent :cell]))))
    (testing "the bag keeps its cycle"
      (is (= {:items [[5 5] [1 0] [2 0]] :remaining [[2 0] [5 5]] :last [1 0] :seed 1 :n 1}
             (get-in moved [:vibe-runtime :bags [:v :hit]]))))
    (is (= (refs/move-cue state [1 0] [1 2])
           (assoc-in (assoc-in state [:config :vibes :library :v :hit :cells] [[0 0] [1 2] [2 0]])
                     [:vibe-runtime :bags [:v :hit]]
                     {:items [[0 0] [1 2] [2 0]] :remaining [[2 0] [0 0]] :last [1 2] :seed 1 :n 1})))))

(deftest move-dedupes-test
  (let [moved (refs/move-cue state [0 0] [2 0])]
    (is (= [[2 0] [1 0]] (get-in (v moved) [:hit :cells])) "a pool that had [2 0] doesn't get it twice")
    (is (= [[2 0] [1 0]] (get-in moved [:vibe-runtime :bags [:v :hit] :items])))
    (is (= [[2 0]] (get-in moved [:vibe-runtime :bags [:v :hit] :remaining]))))
  (testing "nothing names the cell: the vibes are the same map"
    (is (identical? (get-in state [:config :vibes]) (get-in (refs/move-cue state [7 7] [6 6]) [:config :vibes])))))

(deftest remove-cue-clears-every-role-test
  (let [cleared (refs/remove-cue state [0 0])]
    (is (= [[1 0] [2 0]] (get-in (v cleared) [:hit :cells])))
    (is (= [] (get-in (v cleared) [:accent :cells])))
    (is (nil? (get-in (v cleared) [:flash :cue])))
    (is (nil? (:bed (v cleared))))
    (is (nil? (get-in cleared [:vibe-runtime :duck :accent])) "nothing to start again when the flash ends")
    (is (= [[0 0] [3 3]] (get-in (v cleared) [:flash :fx])) "an effect cell [0 0] is another cell")))

(deftest cue-and-effect-grids-are-separate-test
  (testing "move-cue never touches the effect cells"
    (let [moved (refs/move-cue state [0 0] [5 5])]
      (is (= [[0 0] [1 1]] (:fx (v moved))))
      (is (= [[0 0] [3 3]] (get-in (v moved) [:flash :fx])))
      (is (= [[0 0] [1 1]] (get-in moved [:vibe-runtime :fx-on])))))
  (testing "move-fx moves the FX set, the flash's effect cells and what was switched on, and never a cue"
    (let [moved (refs/move-fx state [0 0] [4 4])]
      (is (= [[4 4] [1 1]] (:fx (v moved))))
      (is (= [[4 4] [3 3]] (get-in (v moved) [:flash :fx])))
      (is (= [[4 4] [1 1]] (get-in moved [:vibe-runtime :fx-on])))
      (is (= [[[4 4]] [[4 4]]] ((juxt :fx :fx-on) (get-in moved [:vibe-runtime :voices :flash]))))
      (is (= [[0 0] [1 0] [2 0]] (get-in (v moved) [:hit :cells])))
      (is (= [0 0] (get-in (v moved) [:flash :cue])))
      (is (= [0 0] (get-in moved [:vibe-runtime :voices :flash :cell])))))
  (testing "remove-fx"
    (let [cleared (refs/remove-fx state [0 0])]
      (is (= [[1 1]] (:fx (v cleared))))
      (is (= [[3 3]] (get-in (v cleared) [:flash :fx])))
      (is (= [[1 1]] (get-in cleared [:vibe-runtime :fx-on])))
      (is (= [] (get-in cleared [:vibe-runtime :voices :flash :fx-on])))
      (is (= [0 0] (:bed (v cleared))))))
  (testing "a state without vibes is left as it is"
    (let [bare {:chains {:cue-chains {[0 0] {:items [:preset]}}}}]
      (is (= bare (refs/move-cue bare [0 0] [1 1])))
      (is (= bare (refs/remove-fx bare [0 0]))))))

(deftest restore-fx-follows-the-restored-cells-test
  ;; Reactivity plan 22: after Undo or Redo the engine's switches are the
  ;; restored cells' that take their on/off from them
  (let [s (assoc state :vibe-runtime {:fx-on [[2 3] [1 1]]
                                      :voices {:flash {:cell [0 0] :run :r3 :fx [[2 3]] :fx-on [[2 3]] :solo? true}}})
        ;; Undo of a move [0 3] -> [2 3], and of the cell [1 1]'s creation
        restored (refs/restore-fx s {[0 3] [2 3] [5 5] [5 5]})]
    (is (= [[0 3]] (get-in restored [:vibe-runtime :fx-on])) "[1 1] is gone: let go of")
    (is (= [[[0 3]] [[0 3]]] ((juxt :fx :fx-on) (get-in restored [:vibe-runtime :voices :flash]))) "and the held flash's")
    (is (= (:config s) (:config restored)) "the vibes are the document's")
    (testing "two cells taking one cell's on/off both take its owner"
      (is (= [[0 3] [2 3]] (get-in (refs/restore-fx s {[2 3] [2 3] [0 3] [2 3]}) [:vibe-runtime :fx-on]))))
    (testing "nothing moved: the runtime as it was"
      (is (identical? (:vibe-runtime s) (:vibe-runtime (refs/restore-fx s {[2 3] [2 3] [1 1] [1 1]})))))
    (testing "a state without vibes is left as it is"
      (let [bare {:chains {:effect-chains {[0 0] {:items []}}}}]
        (is (= bare (refs/restore-fx bare {[0 0] [0 0]})))))))

(deftest trigger-targets-follow-a-move-test
  (let [k1 {:source :midi :channel 0 :note 60}
        k2 {:source :midi :channel 0 :note 61}
        k3 {:source :midi :channel 0 :note 62}
        m {k1 {:type :trigger-cue :target [0 0] :behavior :momentary}
           k2 {:type :trigger-effect :target [0 0]}
           k3 {:type :vibe :role :hit}}]
    (is (= {:type :trigger-cue :target [2 3] :behavior :momentary}
           (get (refs/move-trigger-targets m [0 0] [2 3] :trigger-cue) k1)))
    (is (= (m k2) (get (refs/move-trigger-targets m [0 0] [2 3] :trigger-cue) k2)) "only its own grid")
    (is (= #{k2 k3} (set (keys (refs/remove-trigger-targets m [0 0] :trigger-cue)))))
    (is (= {} (refs/move-trigger-targets {} [0 0] [1 1] :trigger-cue)))
    (is (nil? (refs/remove-trigger-targets nil [0 0] :trigger-effect)))))
