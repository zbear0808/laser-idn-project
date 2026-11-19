(ns laser-show.state.templates-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.state.domains :as domains]
            [laser-show.state.templates :as templates]
            [laser-show.vibes.library :as library]))

(deftest starter-vibes-name-starter-cues-test
  (let [cues (templates/create-starter-cue-chains)
        {:keys [order next-id settings] :as vibes} (templates/create-starter-vibes)
        named (fn [vibe] (concat (get-in vibe [:hit :cells]) (get-in vibe [:accent :cells])
                                 [(get-in vibe [:flash :cue])] (some-> (:bed vibe) vector)))
        state (-> (domains/build-initial-state)
                  (templates/apply-starter-cue-chains)
                  (templates/apply-starter-vibes))]
    (is (= [:vibe-1 :vibe-2] order))
    (is (= (set order) (set (keys (:library vibes)))))
    (is (every? #(= % (get-in vibes [:library % :id])) order))
    (is (every? #(< (parse-long (subs (name %) 5)) next-id) order) "a new vibe's id is new")
    (is (= (:settings library/empty-library) settings))
    (doseq [vibe (vals (:library vibes))
            cell (named vibe)]
      (is (seq (get-in cues [cell :items])) (str (:name vibe) " names " cell ", a starter cue")))
    (testing "every cell they name can play"
      (doseq [vibe (vals (:library vibes))
              :let [cells (named vibe)]]
        (is (= (vec (distinct cells)) (library/playable-cells state vibe cells)) (:name vibe))))
    (testing "applied only while there are no vibes, keeping the settings"
      (let [mine (assoc-in (domains/build-initial-state) [:config :vibes :library] {:mine {:id :mine}})]
        (is (identical? mine (templates/apply-starter-vibes mine))))
      (is (= 99 (get-in (templates/apply-starter-vibes
                         (assoc-in (domains/build-initial-state) [:config :vibes :settings :quick-tap-ms] 99))
                        [:config :vibes :settings :quick-tap-ms]))))))
