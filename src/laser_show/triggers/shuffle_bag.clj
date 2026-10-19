(ns laser-show.triggers.shuffle-bag
  "Pure shuffle-bag utilities. A bag emits every item once before refilling."
  (:refer-clojure :exclude [empty? next]))

(defn make
  ([items] (make items shuffle))
  ([items shuffle-fn]
   {:items (vec items)
    :remaining []
    :shuffle-fn shuffle-fn}))

(defn reset
  "Returns a bag with all items available again."
  [bag]
  (assoc bag :remaining []))

(defn empty?
  [bag]
  (clojure.core/empty? (:items bag)))

(defn next
  "Returns [item new-bag], or [nil bag] for an empty bag."
  [bag]
  (if (empty? bag)
    [nil bag]
    (let [remaining (if (seq (:remaining bag))
                      (:remaining bag)
                      (vec ((:shuffle-fn bag shuffle) (:items bag))))
          item (first remaining)]
      [item (assoc bag :remaining (vec (rest remaining)))])))

(defn remaining-count [bag]
  (count (:remaining bag)))

(defn items [bag]
  (:items bag))
