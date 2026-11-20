(ns laser-show.views.components.rig-file-status-test
  "What the Projectors tab says of the rig file (plans/2026-11-19-zones/09 §7)."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.views.components.rig-file-status :as rig-file-status])
  (:import [java.time LocalDateTime ZoneId]))

(def ^:private utc (ZoneId/of "UTC"))

(defn- ms [d h mi]
  (.toEpochMilli (.toInstant (.atZone (LocalDateTime/of 2026 11 (int d) (int h) (int mi) 0) utc))))

(def ^:private saved
  {:path "/tmp/x/rig.edn" :status :saved :read-only? false :written-at (ms 20 14 2) :error nil :requested 3 :written 3})

(defn- line [rig-file] (rig-file-status/status-line rig-file (ms 20 16 0) utc))

(deftest status-line-text-test
  (is (= {:text "Rig saved 14:02" :tone :muted} (line saved)))
  (is (= {:text "Rig saved 19 Nov 14:02" :tone :muted} (line (assoc saved :written-at (ms 19 14 2))))
      "another day: its date too")
  (is (= {:text "Saving rig…" :tone :normal} (line (assoc saved :requested 4))) "a write pending")
  (is (= {:text "Rig not saved: No space left on device" :tone :error :retry? true}
         (line (assoc saved :status :write-failed :error "No space left on device"))))
  (is (= {:text "Rig saves automatically" :tone :muted} (line (assoc saved :status :missing :written-at nil :requested 0 :written 0)))
      "nothing written yet")
  (testing "read only: the banner instead"
    (is (= {:text "This rig is open in another Laser Show window. Changes here aren't saved." :tone :warning :banner? true}
           (line (assoc saved :status :locked :read-only? true))))
    (is (= {:text "This rig file comes from a newer version of Laser Show. It is used, but not saved." :tone :warning :banner? true}
           (line (assoc saved :status :newer :read-only? true)))))
  (is (nil? (line (assoc saved :path nil))) "no rig file (tests, the REPL): nothing"))

(deftest path-text-test
  (let [home (System/getProperty "user.home")]
    (is (= "~/LaserShowProjects/rig.edn" (rig-file-status/path-text (str home "/LaserShowProjects/rig.edn"))))
    (is (= "/elsewhere/rig.edn" (rig-file-status/path-text "/elsewhere/rig.edn")))))
