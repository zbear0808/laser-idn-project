(ns laser-show.triggers.shuffle-bag
  "Pure shuffle bag: each cycle plays every item once, in the order a shuffle
   fn gives the refill, and never the same item twice in a row (the refill
   guard of beyond-vibe/src/engine/shuffle_bag.rs:23-40).

   A bag is data, {:items [...] :remaining [...] :last item}: the shuffle fn
   is passed to next, so a bag's future is a function of its state and the fns
   it is given. seeded makes a shuffle fn from a number; the vibe engine keeps
   :seed and :n (refills so far) beside the bag and passes (seeded (+ seed n))."
  (:refer-clojure :exclude [empty? next sync])
  (:import [java.util SplittableRandom]))

(defn make
  "A bag over items, nothing drawn yet."
  [items]
  {:items (vec items)
   :remaining []
   :last nil})

(defn reset
  "Returns a bag with all items available again."
  [bag]
  (assoc bag :remaining []))

(defn empty?
  [bag]
  (clojure.core/empty? (:items bag)))

(defn seeded
  "A shuffle fn that orders the same items the same way for the same seed:
   Fisher-Yates over java.util.SplittableRandom."
  [seed]
  (fn [items]
    (let [rng (SplittableRandom. (long seed))
          xs (object-array items)]
      (loop [i (dec (alength xs))]
        (when (pos? i)
          (let [j (.nextInt rng (int (inc i)))
                x (aget xs i)]
            (aset xs i (aget xs j))
            (aset xs j x)
            (recur (dec i)))))
      (vec xs))))

(defn- refill
  "items in shuffle-fn's order. When that starts with last (the previous
   pick), its first and last items swap."
  [items shuffle-fn last]
  (let [xs (vec (shuffle-fn items))
        n (count xs)]
    (if (and (> n 1) (= (nth xs 0) last))
      (assoc xs 0 (nth xs (dec n)) (dec n) (nth xs 0))
      xs)))

(defn next
  "Returns [item new-bag]: the next item of this cycle, or of a new one
   (shuffle-fn orders its refill) when this one is done. [nil bag] for an
   empty bag."
  [bag shuffle-fn]
  (if (empty? bag)
    [nil bag]
    (let [remaining (if (seq (:remaining bag))
                      (:remaining bag)
                      (refill (:items bag) shuffle-fn (:last bag)))
          item (first remaining)]
      [item (assoc bag :remaining (vec (rest remaining)) :last item)])))

(defn sync
  "bag over items now. What is gone leaves this cycle, what is new joins its
   end (the last pick waits for the next cycle), and the last pick is kept,
   so the guard still holds. The same items leave the bag as it is."
  [bag items]
  (let [items (vec items)]
    (if (= items (:items bag))
      bag
      (let [now (set items)
            known (conj (set (:items bag)) (:last bag))]
        (assoc bag
               :items items
               :remaining (into (filterv now (:remaining bag)) (remove known) items))))))

(defn remaining-count [bag]
  (count (:remaining bag)))

(defn items [bag]
  (:items bag))
