(ns laser-show.views.components.drag-drop-cell-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.views.components.drag-drop-cell :as dd]))

(deftest payload-round-trips
  (is (= {:type :grid-cell :col 3 :row 1}
         (dd/parse-payload (dd/encode-payload :grid-cell 3 1) :grid-cell))))

(deftest payload-rejects-foreign-or-malformed-input
  (testing "wrong drag type"
    (is (nil? (dd/parse-payload (dd/encode-payload :effects-cell 0 0) :grid-cell))))
  (testing "plain text and non-strings"
    (doseq [s ["hello" "" "   " "{" nil 42 "[1 2 3]" "{:col 0 :row 0 :type :grid-cell}"]]
      (is (nil? (dd/parse-payload s :grid-cell)) (pr-str s))))
  (testing "bad coordinates"
    (is (nil? (dd/parse-payload
               (pr-str {:laser-show/cell-drag true :type :grid-cell :col -1 :row 0})
               :grid-cell)))
    (is (nil? (dd/parse-payload
               (pr-str {:laser-show/cell-drag true :type :grid-cell :col "0" :row 0})
               :grid-cell))))
  (testing "reader eval and tagged literals are not evaluated"
    (let [evaluated (atom false)]
      (intern 'laser-show.views.components.drag-drop-cell-test 'boom!
              (fn [] (reset! evaluated true) {}))
      (is (nil? (dd/parse-payload
                 "#=(laser-show.views.components.drag-drop-cell-test/boom!)"
                 :grid-cell)))
      (is (false? @evaluated)))
    (is (nil? (dd/parse-payload "#foo/bar {:col 0}" :grid-cell))))
  (testing "oversized input"
    (is (nil? (dd/parse-payload (apply str (repeat 2000 "[")) :grid-cell)))))
