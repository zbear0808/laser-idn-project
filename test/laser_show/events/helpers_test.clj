(ns laser-show.events.helpers-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.helpers :as h]))

(def n0 #uuid "00000000-0000-0000-0000-0000000000a0")
(def n #uuid "00000000-0000-0000-0000-0000000000a1")

(defn- clear [id] {:event {:event/type :ui/clear-notice :id id} :delay-ms h/notice-ms})

(def text "Timecode off for 'A': it needs a BPM")

(defn- merged
  "merge-notice of text at :warning, its new id fixed to n."
  [effects before]
  (with-redefs [random-uuid (constantly n)]
    (h/merge-notice effects before text :warning)))

(deftest merge-notice-examples-test
  (let [s {:chains {:shows {}} :ui {:notice nil}}
        notice {:id n :text text :level :warning}]
    (testing "07 §4.1"
      (is (= {:state (assoc-in s [:ui :notice] notice) :dispatch-later (clear n)}
             (merged {:state s} s))
          "only state: the notice and its timer")
      (let [open {:event/type :ui/open-dialog :dialog-id :x}]
        (is (= {:state (assoc-in s [:ui :notice] notice) :dispatch open :dispatch-later (clear n)}
               (merged {:state s :dispatch open} s))
            "a :dispatch stays"))
      (let [fresh (assoc-in s [:ui :notice] {:id n0 :text "Saved BPM" :level :info})]
        (is (= {:state (assoc-in s [:ui :notice] {:id n :text (str "Saved BPM " text) :level :warning})
                :dispatch-later (clear n)}
               (merged {:state fresh :dispatch-later (clear n0)} s))
            "a fresh notice: one notice, one timer, the more severe level"))
      (let [identify {:event {:event/type :projectors/identify-ended :projector-id :p} :delay-ms 3000}]
        (is (= {:state (assoc-in s [:ui :notice] notice) :dispatch-later [identify (clear n)]}
               (merged {:state s :dispatch-later identify} s))
            "the handler's own timer stays"))
      (let [earlier (assoc-in s [:ui :notice] {:id n0 :text "Saved BPM" :level :info})]
        (is (= {:state (assoc-in s [:ui :notice] notice) :dispatch-later (clear n)}
               (merged {:state earlier} earlier))
            "a notice from an earlier event is replaced; its timer, already running, later clears nothing")))
    (testing "the more severe level wins either way"
      (let [fresh (assoc-in s [:ui :notice] {:id n0 :text "Could not save" :level :error})]
        (is (= :error (get-in (merged {:state fresh :dispatch-later (clear n0)} s) [:state :ui :notice :level])))))
    (testing "a fresh notice's timer goes, every other stays"
      (let [fresh (assoc-in s [:ui :notice] {:id n0 :text "Saved BPM" :level :info})
            other {:event {:event/type :projectors/identify-ended} :delay-ms 3000}
            stale (clear #uuid "00000000-0000-0000-0000-0000000000a9")]
        (is (= [other stale (clear n)]
               (:dispatch-later (merged {:state fresh :dispatch-later [(clear n0) other stale]} s))))
        (is (= [other (clear n)]
               (:dispatch-later (merged {:state fresh :dispatch-later [other (clear n0)]} s))))))
    (testing "a notice not from this event keeps the handler's timers, even a clear for it"
      (let [earlier (assoc-in s [:ui :notice] {:id n0 :text "x" :level :info})]
        (is (= [(clear n0) (clear n)]
               (:dispatch-later (merged {:state earlier :dispatch-later (clear n0)} earlier))))))
    (testing "no effect but :state and :dispatch-later changes"
      (let [effects {:state s :dispatch-n [{:event/type :a}] :history/description "Move Clip"
                     :history/gesture-id :g :output/write-status {:engines 0}}]
        (is (= (dissoc effects :state) (dissoc (merged effects s) :state :dispatch-later)))))
    (testing "invariants: one notice on screen, one timer per notice id"
      (doseq [[effects before] [[{:state s} s]
                                [{:state (assoc-in s [:ui :notice] {:id n0 :text "a" :level :info})
                                  :dispatch-later (clear n0)} s]]]
        (let [out (merged effects before)
              timers (let [v (:dispatch-later out)] (if (map? v) [v] v))]
          (is (= n (get-in out [:state :ui :notice :id])))
          (is (= 1 (count (filter #(= {:event/type :ui/clear-notice :id n} (:event %)) timers))))
          (is (not-any? #(= {:event/type :ui/clear-notice :id n0} (:event %)) timers)))))))
