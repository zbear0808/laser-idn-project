(ns laser-show.views.dialogs.registry-test
  (:require [cljfx.api :as fx]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.views.dialogs.rig-report :as rig-report]
            [laser-show.views.root :as root]))

(defn- dialog-defaults [] (get-in (domains/build-initial-state) [:ui :dialogs]))

(deftest every-mounted-dialog-has-a-default-test
  (let [ids (map first root/dialog-registry)
        defaults (dialog-defaults)]
    (is (apply distinct? ids) "each mounted window has its own key")
    (is (= #{} (set/difference (set ids) (set (keys defaults)))))
    (is (every? (comp false? :open?) (vals defaults)) "all windows start closed")))

(deftest every-dialog-default-is-mounted-test
  (is (= #{} (set/difference (set (keys (dialog-defaults)))
                             (set (map first root/dialog-registry))))))

(deftest no-rig-report-before-opening-a-show-test
  (let [fresh (assoc-in (domains/build-initial-state) [:chains :cue-chains [0 0]]
                        {:target :auto.laser/missing})
        ctx (fx/create-context fresh state/cache-factory)
        loaded (fx/create-context (assoc-in fresh [:ui :dialogs :rig-report]
                                           {:open? false :file "friday.zip" :hardware nil})
                                  state/cache-factory)]
    (is (nil? (fx/sub-ctx ctx rig-report/report))
        "a closed dialog default is not a show loaded from a file")
    (is (zero? (fx/sub-ctx ctx rig-report/warning-count))
        "a fresh show must not offer a report to reopen")
    (is (= 1 (fx/sub-ctx loaded rig-report/warning-count))
        "a loaded show without hardware data can still have a missing pin to report")))

(deftest rig-report-counts-shows-test
  ;; plans/2026-11-25-timeline-shows 04 §4.6: the report reads [:chains :shows] too
  (let [id #uuid "00000000-0000-0000-0000-0000000000aa"
        loaded (-> (domains/build-initial-state)
                   (assoc-in [:chains :shows id] {:editor-type :timeline :target :auto.laser/missing :tracks [] :items []})
                   (assoc-in [:ui :dialogs :rig-report] {:open? false :file "friday.zip" :hardware nil}))
        ctx (fx/create-context loaded state/cache-factory)]
    (is (= [{:ref :auto.laser/missing :name "missing" :cues 0 :shows 1}]
           (:missing-pins (fx/sub-ctx ctx rig-report/report))))
    (is (= 1 (fx/sub-ctx ctx rig-report/warning-count)))))
