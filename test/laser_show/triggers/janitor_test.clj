(ns laser-show.triggers.janitor-test
  (:require [clojure.test :refer [deftest is]]
            [laser-show.triggers.janitor :as janitor]))

(deftest removes-expired-entries
  (let [entries {:old {:last-seen 0} :new {:last-seen 900}}]
    (is (= {:new {:last-seen 900}}
           (janitor/remove-expired entries 1000 500)))))
