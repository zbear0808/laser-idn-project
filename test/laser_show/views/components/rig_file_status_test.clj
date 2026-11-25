(ns laser-show.views.components.rig-file-status-test
  "What the Projectors tab says of the rig file (plans/2026-11-19-zones/09 §7)."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.state.domains :as domains]
            [laser-show.views.components.rig-file-status :as rig-file-status]
            [laser-show.views.harness :as h])
  (:import [java.time LocalDateTime ZoneId]
           [javafx.scene.control Label]))

(def ^:private utc (ZoneId/of "UTC"))

(defn- ms [d h mi]
  (.toEpochMilli (.toInstant (.atZone (LocalDateTime/of 2026 11 (int d) (int h) (int mi) 0) utc))))

(def ^:private saved
  {:path "/tmp/x/rig.edn" :status :saved :read-only? false :written-at (ms 20 14 2) :error nil :saving? false})

(defn- line [shown] (rig-file-status/status-line shown (ms 20 16 0) utc))

(deftest status-line-text-test
  (is (= {:text "Rig saved 14:02" :tone :muted} (line saved)))
  (is (= {:text "Rig saved 19 Nov 14:02" :tone :muted} (line (assoc saved :written-at (ms 19 14 2))))
      "another day: its date too")
  (is (= {:text "Saving rig…" :tone :normal} (line (assoc saved :saving? true))) "a write pending")
  (is (= {:text "Rig not saved: No space left on device" :tone :error :retry? true}
         (line (assoc saved :status :write-failed :error "No space left on device"))))
  (is (= {:text "Rig saves automatically" :tone :muted} (line (assoc saved :status :missing :written-at nil)))
      "nothing written yet")
  (testing "read only: the banner instead"
    (is (= {:text "This rig is open in another Laser Show window. Changes here aren't saved." :tone :warning :banner? true}
           (line (assoc saved :status :locked :read-only? true))))
    (is (= {:text "This rig file comes from a newer version of Laser Show. It is used, but not saved." :tone :warning :banner? true}
           (line (assoc saved :status :newer :read-only? true)))))
  (is (nil? (line (assoc saved :path nil))) "no rig file (tests, the REPL): nothing"))

(deftest the-line-renders-when-what-it-says-changes-test
  ;; Follow-up R3: it read the whole of [:rig-file], whose write counts and
  ;; group copy change with every rig edit, though it shows only whether a
  ;; write is pending
  (let [now (System/currentTimeMillis)
        rig-file {:path "/tmp/x/rig.edn" :status :saved :read-only? false :written-at now :error nil
                  :requested 3 :written 3 :group-copy {}}
        steps (h/each-render (assoc (domains/build-initial-state) :rig-file rig-file)
                             h/lru-512 {:fx/type rig-file-status/rig-file-status}
                             [#(update-in % [:rig-file :requested] inc)
                              #(update-in % [:rig-file :requested] inc)
                              #(assoc-in % [:rig-file :group-copy] {:graphics {:id :graphics :name "Graphics"}})
                              #(update % :rig-file assoc :written 5 :written-at (+ now 1000))]
                             (fn [{:keys [root] :as step}]
                               {:runs (count (h/ran-of step rig-file-status/rig-file-status))
                                :text (.getText ^Label (h/node-by-id root "rig-file-status"))}))]
    (is (= [true false false false true] (map #(boolean (re-matches #"Rig saved \d\d:\d\d" (:text %))) steps))
        "saved, a write asked for, another, the group copy, written")
    (is (= "Saving rig…" (:text (second steps))))
    (is (= [1 1 0 0 1] (map :runs steps)) "only when what it says changes")))

(deftest path-text-test
  (let [home (System/getProperty "user.home")]
    (is (= "~/LaserShowProjects/rig.edn" (rig-file-status/path-text (str home "/LaserShowProjects/rig.edn"))))
    (is (= "/elsewhere/rig.edn" (rig-file-status/path-text "/elsewhere/rig.edn")))))
