(ns laser-show.events.chain-helpers-test
  "Where chain-config puts each domain's chain, for a grid cell's key and a
   show's (plans/2026-11-25-timeline-shows/05 §7)."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.handlers.chain.helpers :as chain-helpers]))

(def u #uuid "6f1c0000-0000-0000-0000-000000000001")

(deftest chain-config-show-keys-test
  (testing "05 §7's table"
    (is (= [:chains :shows u :items] (:items-path (chain-helpers/chain-config :timeline-items [:show u]))))
    (is (= [:chains :shows u :tracks] (:items-path (chain-helpers/chain-config :timeline-tracks [:show u]))))
    (is (= [:chains :shows u :zone-patterns] (:items-path (chain-helpers/chain-config :cue-zone-patterns [:show u]))))
    (is (= [:chains :cue-chains [2 1] :zone-patterns] (:items-path (chain-helpers/chain-config :cue-zone-patterns [2 1])))
        "a grid cell's key, as today")
    (is (= [:chains :effect-chains [0 0] :items] (:items-path (chain-helpers/chain-config :effect-chains [0 0])))
        "another domain, as today"))
  (testing "the cue's own path, and the entity-key as given"
    (is (= [:chains :shows u] (:metadata-path (chain-helpers/chain-config :timeline-items [:show u]))))
    (is (= [:chains :cue-chains [0 0] :items] (:items-path (chain-helpers/chain-config :timeline-items [0 0]))))
    (is (= [:chains :cue-chains [0 0] :items] (:items-path (chain-helpers/chain-config :cue-chains [0 0]))))
    (is (= [:show u] (:entity-key (chain-helpers/chain-config :cue-zone-patterns [:show u])))))
  (testing "zone-pattern-items-path accepts a show's key for a cue's patterns"
    (is (= [:chains :shows u :zone-patterns] (chain-helpers/zone-pattern-items-path :cue-zone-patterns [:show u])))
    (is (= [:chains :cue-chains [2 1] :zone-patterns] (chain-helpers/zone-pattern-items-path :cue-zone-patterns [2 1])))
    (is (nil? (chain-helpers/zone-pattern-items-path :effect-chains [:show u])) "an effects cell is never a show")
    (is (nil? (chain-helpers/zone-pattern-items-path :cue-zone-patterns [:show "not-a-uuid"])))
    (is (nil? (chain-helpers/zone-pattern-items-path :timeline-items [:show u])) "only the two pattern domains")))

(deftest show-key-edits-resolve-in-the-show-test
  (let [fx-id #uuid "00000000-0000-0000-0000-0000000000f1"
        clip #uuid "00000000-0000-0000-0000-0000000000c1"
        state {:chains {:shows {u {:items [{:id clip :effects [{:id fx-id :effect-id :scale}]}]
                                   :zone-patterns [{:id fx-id :effect-id :zone-pattern}]}}
                        :cue-chains {}}}]
    (is (= [0 :effects 0] (chain-helpers/resolve-effect-instance-path
                           state {:domain :timeline-items :entity-key [:show u] :owner-id clip :effect-instance-id fx-id})))
    (is (= [0] (chain-helpers/resolve-effect-instance-path
                state {:domain :cue-zone-patterns :entity-key [:show u] :effect-instance-id fx-id})))
    (is (= [:chains :shows u :items 0 :effects]
           (chain-helpers/owner-effects-path state {:domain :timeline-items :entity-key [:show u] :owner-id clip})))))
