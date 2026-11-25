(ns laser-show.events.timing-handlers-link-test
  "Tests for Link-related timing event handlers."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.handlers.timing :as timing]))


;; Test State Fixtures


(def base-state
  "Base state with Carabiner connected and Link enabled."
  {:backend {:link {:carabiner-connected? true
                    :link-enabled? true
                    :sync-enabled? false
                    :link-bpm 120.0
                    :link-peers 1}}
   :timing {:bpm 120.0}
   :playback {:accumulated-beats 4.5
              :phase-offset-target 0.0}})


;; Link Sync Toggle Tests (BPM sync to app)


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


;; Carabiner Connection Status Tests


(deftest test-carabiner-connected
  (testing "Update Carabiner connection status to connected"
    (let [state (assoc-in base-state [:backend :link :carabiner-connected?] false)
          event {:state state
                 :event/type :timing/carabiner-connected
                 :connected? true}
          result (timing/handle event)
          new-state (:state result)]
      (is (true? (get-in new-state [:backend :link :carabiner-connected?]))))))

(deftest test-carabiner-disconnected
  (testing "Update Carabiner connection status to disconnected"
    (let [event {:state base-state
                 :event/type :timing/carabiner-connected
                 :connected? false}
          result (timing/handle event)
          new-state (:state result)]
      (is (false? (get-in new-state [:backend :link :carabiner-connected?]))))))

(deftest carabiner-connected-is-plain-status-test
  (let [before (assoc (get-in base-state [:backend :link])
                      :carabiner-connected? false :link-peers 2 :auto-connect? true :sync-enabled? true)
        state (assoc-in base-state [:backend :link] before)
        handled #(:state (timing/handle (assoc % :state state :event/type :timing/carabiner-connected)))
        link-after #(get-in (handled %) [:backend :link])]
    (testing "a connect: the flag, the other link keys kept"
      (is (= (assoc before :carabiner-connected? true) (link-after {:connected? true}))))
    (testing "not connected (a connect that failed, or the connection closed, follow-up N5): Link is off, its tempo and peers forgotten"
      (is (= (assoc before :link-enabled? false :link-bpm nil :link-peers 0) (link-after {:connected? false}))
          "following Link's tempo stays selected, for when Link is enabled again")
      (is (= (:timing state) (:timing (handled {:connected? false}))) "the app keeps its tempo"))
    (testing "no listener in app state, even from an event that has one: the connection is the Link owner's (input.link)"
      (is (= (assoc before :carabiner-connected? true)
             (link-after {:connected? true :listener ::listener}))))))

(deftest carabiner-auto-connect-asks-the-link-owner-test
  (let [auto-connect #(timing/handle {:event/type :timing/carabiner-auto-connect :state %})]
    (is (= {:link/connect {}} (auto-connect base-state))
        "start-up connects: the Link owner does, off the event thread")
    (is (= {:link/connect {}} (auto-connect (assoc-in base-state [:backend :link :auto-connect?] true))))
    (is (= {} (auto-connect (assoc-in base-state [:backend :link :auto-connect?] false)))
        "Auto-connect off: nothing connects")))


;; Link Enabled Status Tests


(deftest test-link-enabled
  (testing "Update Link enabled status to true"
    (let [state (assoc-in base-state [:backend :link :link-enabled?] false)
          event {:state state
                 :event/type :timing/link-enabled
                 :enabled? true}
          result (timing/handle event)
          new-state (:state result)]
      (is (true? (get-in new-state [:backend :link :link-enabled?]))))))

(deftest test-link-disabled
  (testing "Update Link enabled status to false"
    (let [event {:state base-state
                 :event/type :timing/link-enabled
                 :enabled? false}
          result (timing/handle event)
          new-state (:state result)]
      (is (false? (get-in new-state [:backend :link :link-enabled?]))))))


;; Link BPM Changed Tests


(deftest test-link-bpm-changed
  (testing "Update Link BPM value"
    (let [event {:state base-state
                 :event/type :timing/link-bpm-changed
                 :bpm 128.5}
          result (timing/handle event)
          new-state (:state result)]
      (is (= 128.5 (get-in new-state [:backend :link :link-bpm]))))))


;; Link Peers Changed Tests


(deftest test-link-peers-changed
  (testing "Update Link peers count"
    (let [event {:state base-state
                 :event/type :timing/link-peers-changed
                 :peers 3}
          result (timing/handle event)
          new-state (:state result)]
      (is (= 3 (get-in new-state [:backend :link :link-peers]))))))


;; Phase Offset Target Tests


(deftest test-set-phase-offset-target
  (testing "Asks the show clock to shift the phase targets by the offset (its numbers: show-time-test)"
    (let [event {:state base-state
                 :event/type :timing/set-phase-offset-target
                 :offset 0.25}
          result (timing/handle event)
          shift (get-in result [:state :playback :requests :shift])]
      (is (uuid? (:id shift)))
      (is (= 0.25 (:offset shift)))
      (is (= (:playback base-state) (dissoc (get-in result [:state :playback]) :requests))
          "only the request: the old target path, which nothing read, isn't written"))))

(deftest test-set-negative-phase-offset
  (testing "Set negative phase offset for backward correction"
    (let [event {:state base-state
                 :event/type :timing/set-phase-offset-target
                 :offset -0.15}
          result (timing/handle event)]
      (is (= -0.15 (get-in result [:state :playback :requests :shift :offset]))))))


;; Sync To Downbeat Tests


(deftest test-sync-to-downbeat-requests-effect
  (testing "leaves the Link query to an effect, which reads the show clock's beats itself"
    (is (= {:link/sync-to-downbeat {}}
           (timing/handle {:state base-state :event/type :timing/sync-to-downbeat})))))


;; Integration Tests


(defn- handle [state event] (:state (timing/handle (assoc event :state state))))

(deftest steady-link-bpm-applies-on-sync-enable-test
  (let [ready (assoc-in base-state [:backend :link :link-bpm] 128.0)]
    (doseq [enable [{:event/type :timing/link-set-sync-enabled :fx/event true}
                    {:event/type :timing/toggle-link-sync}]]
      (let [following (handle ready enable)]
        (is (= 128.0 (get-in following [:timing :bpm])) "no new peer report required")
        (is (= 131.0 (get-in (handle following {:event/type :timing/link-bpm-changed :bpm 131.0})
                             [:timing :bpm])))))
    (let [off (assoc-in ready [:backend :link :link-enabled?] false)
          armed (handle off {:event/type :timing/link-set-sync-enabled :fx/event true})]
      (is (= 120.0 (get-in armed [:timing :bpm])) "the preference alone cannot change tempo")
      (is (= 128.0 (get-in (handle armed {:event/type :timing/link-toggle}) [:timing :bpm]))
          "re-enabling Link resumes the saved sync preference"))
    (is (= 120.0 (get-in (handle (assoc-in ready [:backend :link :link-bpm] nil)
                                 {:event/type :timing/toggle-link-sync}) [:timing :bpm]))
        "no reported tempo yet")))

(deftest test-link-settings-workflow
  (testing "Link settings configuration"
    (let [;; 1. Set auto-connect
          auto-connect-event {:state base-state
                              :event/type :timing/link-set-auto-connect
                              :fx/event true}
          state-1 (:state (timing/handle auto-connect-event))
          
          ;; 2. Set latency compensation
          latency-event {:state state-1
                         :event/type :timing/link-set-latency
                         :fx/event 15}
          state-2 (:state (timing/handle latency-event))
          
          ;; 3. Set beat sync
          beat-sync-event {:state state-2
                           :event/type :timing/link-set-beat-sync
                           :fx/event true}
          state-3 (:state (timing/handle beat-sync-event))]
      
      (is (true? (get-in state-3 [:backend :link :auto-connect?])))
      (is (= 15 (get-in state-3 [:backend :link :latency-ms])))
      (is (true? (get-in state-3 [:backend :link :beat-sync?]))))))
