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
    (is (nil? (dd/parse-payload (apply str (repeat 2000 "[")) :grid-cell))))
  (testing "bad coordinates in the current encoding"
    (is (nil? (dd/parse-payload (dd/encode-drag-payload :grid-cell {:col -1 :row 0}) :grid-cell)))
    (is (nil? (dd/parse-payload (dd/encode-drag-payload :grid-cell {:col "0" :row 0}) :grid-cell)))))

(def ^:private preset-payload {:kind :preset :id :square :category :geometric})

(defn- bank-item? [m] (keyword? (:id m)))

(defn- content-string
  "The string drag-content puts on the dragboard (its only entry)."
  [content]
  (let [[[fmt s]] (seq content)]
    (is (= ["application/x-laser-show-drag+bank-item"] (vec (.getIdentifiers ^javafx.scene.input.DataFormat fmt))))
    s))

(deftest bank-payload-round-trips-test
  (is (= (assoc preset-payload :laser-show/drag :bank-item)
         (dd/parse-drag-payload (content-string (dd/drag-content :bank-item preset-payload))
                                :bank-item bank-item?)))
  (testing "sets and namespaced keywords survive"
    (let [p {:kind :effect :id :scale :category #{:shape :color}}]
      (is (= p (dissoc (dd/parse-drag-payload (dd/encode-drag-payload :bank-item p) :bank-item bank-item?)
                       :laser-show/drag))))))

(deftest bank-payload-rejects-foreign-input-test
  (testing "another drag type"
    (is (nil? (dd/parse-drag-payload (dd/encode-drag-payload :grid-cell preset-payload) :bank-item bank-item?)))
    (is (nil? (dd/parse-drag-payload (dd/encode-payload :bank-item 0 0) :bank-item bank-item?))
        "a cell payload under the bank type fails the validator"))
  (testing "untagged maps, plain text and non-strings"
    (doseq [s [(pr-str preset-payload) "square" "" "{" nil 42 "[:preset :square]"]]
      (is (nil? (dd/parse-drag-payload s :bank-item bank-item?)) (pr-str s))))
  (testing "a validator that throws rejects instead of throwing"
    (is (nil? (dd/parse-drag-payload (dd/encode-drag-payload :bank-item preset-payload)
                                     :bank-item #(throw (ex-info "boom" %))))))
  (testing "reader eval, tagged literals and oversized input"
    (is (nil? (dd/parse-drag-payload "#=(java.lang.System/exit 1)" :bank-item bank-item?)))
    (is (nil? (dd/parse-drag-payload "#foo/bar {:id :x}" :bank-item bank-item?)))
    (is (nil? (dd/parse-drag-payload (dd/encode-drag-payload :bank-item (assoc preset-payload :pad (apply str (repeat 1100 "x"))))
                                     :bank-item bank-item?)))))
