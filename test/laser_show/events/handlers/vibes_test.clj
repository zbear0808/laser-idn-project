(ns laser-show.events.handlers.vibes-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers :as handlers]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.templates :as templates]))

(defn- starter []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-vibes)
      (assoc-in [:timing :bpm] 120.0)
      (assoc-in [:chains :effect-chains [0 3]] {:items [{:effect-id :scale}] :active? false})))

(defn- run
  "state after event through the pipeline, then each event it dispatches,
   as the agent would."
  [state event]
  (let [effects (events/handle-event-pipeline (merge {:time 0} event {:state state}))
        s (or (:state effects) state)]
    (if-let [next-event (:dispatch effects)]
      (run s next-event)
      s)))

(defn- undo-count [state] (count (get-in state [:history :undo-stack])))

(deftest vibe-domain-is-routed-test
  (let [s0 (starter)
        s1 (:state (handlers/handle-event {:event/type :vibe/press :role :hit :time 1000 :state s0}))]
    (is (some? (get-in s1 [:vibe-runtime :voices :hit])))
    (doseq [[type extra] [[:vibe/release {:role :hit}] [:vibe/select {:vibe :vibe-2}] [:vibe/release-all {}]]]
      (is (not= s1 (:state (handlers/handle-event (merge {:event/type type :time 1500 :state s1} extra))))
          (str type)))
    (is (= {} (handlers/handle-event {:event/type :vibe/unknown :state s0})))
    (testing "the trigger-group domain is gone (V7)"
      (is (= {} (handlers/handle-event {:event/type :trigger-group/toggle-lock :group-id :g :state s0}))))))

(deftest document-roots-stay-identical-test
  (testing "invariant 7: HIT and ACCENT leave every document root as it was"
    (reduce (fn [s event]
              (let [s' (run s event)]
                (is (history/document-roots-identical? s s') (pr-str event))
                s'))
            (-> (starter) (assoc-in [:config :vibes :settings :hit-taps-tempo?] false) (history/mark-clean))
            [{:event/type :vibe/press :role :hit :input-time 1000}
             {:event/type :vibe/release :role :hit :input-time 1090}
             {:event/type :vibe/press :role :accent :input-time 1100}
             {:event/type :vibe/release :role :accent :input-time 1500}
             {:event/type :vibe/press :role :hit :input-time 1600}
             {:event/type :vibe/release :role :hit :input-time 2000}]))
  (testing "a tap of the tempo changes the taps, not the document"
    (let [s0 (history/mark-clean (starter))
          s1 (run s0 {:event/type :vibe/press :role :hit :input-time 1000})]
      (is (= 1 (count (get-in s1 [:timing :tap-times]))))
      (is (= 0 (undo-count s1)))
      (is (false? (get-in s1 [:project :dirty?]))))))

(deftest fx-set-switch-is-live-not-undoable-test
  (let [s0 (-> (starter)
               (assoc-in [:config :vibes :library :vibe-2 :fx] [[0 3]])
               (assoc-in [:config :vibes :library :vibe-1 :flash :fx] [[0 3]])
               (history/mark-clean))
        doc history/extract-document-state]
    (doseq [[what events on?] [["a vibe switch" [{:event/type :vibe/select :vibe :vibe-2 :input-time 1000}] true]
                               ["a flash" [{:event/type :vibe/press :role :flash :input-time 1000}] true]
                               ["blackout" [{:event/type :vibe/select :vibe :vibe-2 :input-time 1000}
                                            {:event/type :vibe/press :role :blackout :input-time 1100}] false]]]
      (let [s (reduce run s0 events)]
        (testing what
          (is (= on? (get-in s [:chains :effect-chains [0 3] :active?])))
          (is (false? (history/undoable-changed? (doc s0) (doc s))) "not undoable")
          (is (false? (history/persisted-changed? (doc s0) (doc s))) "not an unsaved change")
          (is (= 0 (undo-count s)))
          (is (false? (get-in s [:project :dirty?]))))))))
