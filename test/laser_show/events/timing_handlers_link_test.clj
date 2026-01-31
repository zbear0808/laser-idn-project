(ns laser-show.events.timing-handlers-link-test
  "Tests for Link-related timing event handlers."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.handlers.timing :as timing]))


;; Test State Fixtures


(def base-state
  {:backend {:link {:connected? true
                    :sync-enabled? false
                    :link-bpm 120.0}}
   :playback {:accumulated-beats 4.5
              :phase-offset-target 0.0}})


;; Link Sync Toggle Tests


(deftest test-toggle-link-sync-enable
  (testing "Toggle Link sync from disabled to enabled"
    (let [event {:state base-state
                 :event/type :timing/toggle-link-sync}
          result (timing/handle event)
          new-state (:state result)]
      (is (true? (get-in new-state [:backend :link :sync-enabled?]))))))

(deftest test-toggle-link-sync-disable
  (testing "Toggle Link sync from enabled to disabled"
    (let [state (assoc-in base-state [:backend :link :sync-enabled?] true)
          event {:state state
                 :event/type :timing/toggle-link-sync}
          result (timing/handle event)
          new-state (:state result)]
      (is (false? (get-in new-state [:backend :link :sync-enabled?]))))))


;; Link Connection Status Tests


(deftest test-link-connected
  (testing "Update Link connection status to connected"
    (let [state (assoc-in base-state [:backend :link :connected?] false)
          event {:state state
                 :event/type :timing/link-connected
                 :connected? true}
          result (timing/handle event)
          new-state (:state result)]
      (is (true? (get-in new-state [:backend :link :connected?]))))))

(deftest test-link-disconnected
  (testing "Update Link connection status to disconnected"
    (let [event {:state base-state
                 :event/type :timing/link-connected
                 :connected? false}
          result (timing/handle event)
          new-state (:state result)]
      (is (false? (get-in new-state [:backend :link :connected?]))))))


;; Link BPM Changed Tests


(deftest test-link-bpm-changed
  (testing "Update Link BPM value"
    (let [event {:state base-state
                 :event/type :timing/link-bpm-changed
                 :bpm 128.5}
          result (timing/handle event)
          new-state (:state result)]
      (is (= 128.5 (get-in new-state [:backend :link :link-bpm]))))))


;; Phase Offset Target Tests


(deftest test-set-phase-offset-target
  (testing "Set phase offset target for beat alignment"
    (let [event {:state base-state
                 :event/type :timing/set-phase-offset-target
                 :offset 0.25}
          result (timing/handle event)
          new-state (:state result)]
      (is (= 0.25 (get-in new-state [:playback :phase-offset-target]))))))

(deftest test-set-negative-phase-offset
  (testing "Set negative phase offset for backward correction"
    (let [event {:state base-state
                 :event/type :timing/set-phase-offset-target
                 :offset -0.15}
          result (timing/handle event)
          new-state (:state result)]
      (is (= -0.15 (get-in new-state [:playback :phase-offset-target]))))))


;; Integration Tests


(deftest test-link-bpm-sync-workflow
  (testing "Complete BPM sync workflow"
    (let [;; 1. Enable sync
          enable-event {:state base-state
                        :event/type :timing/toggle-link-sync}
          state-1 (:state (timing/handle enable-event))
          
          ;; 2. Receive BPM update
          bpm-event {:state state-1
                     :event/type :timing/link-bpm-changed
                     :bpm 130.0}
          state-2 (:state (timing/handle bpm-event))
          
          ;; 3. Apply BPM change
          set-bpm-event {:state state-2
                         :event/type :timing/set-bpm
                         :bpm 130.0}
          state-3 (:state (timing/handle set-bpm-event))]
      
      (is (true? (get-in state-3 [:backend :link :sync-enabled?])))
      (is (= 130.0 (get-in state-3 [:backend :link :link-bpm])))
      (is (= 130.0 (get-in state-3 [:timing :bpm]))))))
