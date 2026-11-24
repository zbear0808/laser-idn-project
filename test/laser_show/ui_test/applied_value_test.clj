(ns laser-show.ui-test.applied-value-test
  "The applied-value adapter (views/components/applied_value.clj, reactivity
   plan 13) on a spinner, whose value lives in its value factory: a step is
   sent once, and settle-ms after one that doesn't apply the spinner shows
   the value that applies again; one that applies stays; a later step is
   sent whatever earlier renders showed; neither showing what applies nor a
   render sends anything; with a new value factory it goes on working; and
   its listener and timer go with it."
  (:require [cljfx.api :as fx]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.components.applied-value :as applied-value])
  (:import [javafx.scene Node Scene]
           [javafx.scene.control Spinner]
           [javafx.scene.layout StackPane]
           [javafx.stage Stage]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- spinner
  "A spinner showing value, its factory's desc factory."
  [value factory]
  {:fx/type applied-value/applied-control
   :property :value
   :address [:test :count]
   :value value
   :on-change (fn [v] {:event/type :test/set-count :value v})
   :desc {:fx/type :spinner :id "applied-spinner" :pref-width 80 :value-factory factory}})

(def ^:private whole {:fx/type :integer-spinner-value-factory :min 0 :max 10 :amount-to-step-by 1})
(def ^:private halves {:fx/type :double-spinner-value-factory :min 0.0 :max 10.0 :amount-to-step-by 0.5})

(deftest spinner-shows-the-applied-value-test
  (let [sent (atom [])
        steps #(mapv :value (filter (fn [e] (= :test/set-count (:event/type e))) @sent))
        attached (applied-value/attached-count)
        component (volatile! nil)
        render! (fn [value factory]
                  (ui/on-fx-thread #(vswap! component (fn [c] (if c
                                                                (fx/advance-component c (spinner value factory))
                                                                (fx/create-component (spinner value factory))))))
                  (ui/sync-fx!))]
    ;; The app sends nothing on: whatever the spinner sends is the only edit
    (with-redefs [events/dispatch! (fn [event] (swap! sent conj event))]
      (render! 3 whole)
      (let [^Spinner node (fx/instance @component)
            stage (ui/on-fx-thread #(doto (Stage.)
                                      (.setScene (Scene. (StackPane. (into-array Node [node])) 200 80))
                                      (.show)))
            shown #(ui/on-fx-thread (fn [] (.getValue node)))
            step! (fn [n] (ui/on-fx-thread #(if (pos? n) (.increment node (int n)) (.decrement node (int (- n))))))]
        (try
          (is (= 3 (shown)) "the value that applies, in its value factory")
          (is (= (inc attached) (applied-value/attached-count)))
          (testing "a step that doesn't apply: sent once, then the value that applies"
            (step! 1)
            (is (= [4] (steps)))
            (is (ui/wait-for! #(= 3 (shown))))
            (is (= [4] (steps)) "showing what applies sent nothing"))
          (testing "one that applies stays"
            (step! 1)
            (render! 4 whole)
            (Thread/sleep (+ applied-value/settle-ms 100))
            (is (= 4 (shown)))
            (is (= [4 4] (steps)) "the render sent nothing"))
          (testing "a step back to a value rendered before is sent too"
            (step! -1)
            (is (= [4 4 3] (steps)))
            (is (ui/wait-for! #(= 4 (shown)))))
          (testing "a new value factory: steps are still sent, and the value that applies comes back as its number"
            (render! 4.0 halves)
            (is (= 4.0 (shown)))
            (step! 1)
            (is (= [4 4 3 4.5] (steps)))
            (is (ui/wait-for! #(= 4.0 (shown))))
            (is (instance? Double (shown))))
          (finally
            (ui/on-fx-thread #(do (.close ^Stage stage)
                                  (fx/delete-component @component)))))
        (is (= attached (applied-value/attached-count)) "its listener and timer went with it")))))
