(ns laser-show.events.menu-handlers-test
  "Tests for menu event handlers, focusing on Edit menu clipboard operations."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [laser-show.state.core :as state]
   [laser-show.events.handlers.menu :as menu]
   [laser-show.state.domains :refer [build-initial-state]]))

(def base-state
  (build-initial-state))

(defn init-state-fixture [f]
  (state/init-state! base-state)
  (f))

(use-fixtures :each init-state-fixture)

(def sample-state-with-cues
  (-> base-state
      (assoc-in [:ui :active-tab] :grid)
      (assoc-in [:chains :cue-chains [0 0]] {:items [{:preset-id :circle}]})
      (assoc-in [:grid :selected-cell] [0 0])))

(def sample-state-playing
  (-> base-state
      (assoc-in [:ui :active-tab] :grid)
      (assoc-in [:chains :cue-chains [0 0]] {:items [{:preset-id :circle}]})
      (assoc-in [:grid :selected-cell] nil)
      (assoc-in [:playback :active-cues [0 0]] {:trigger-time 1000})))

(def sample-state-with-effects
  (-> base-state
      (assoc-in [:ui :active-tab] :effects)
      (assoc-in [:chains :effect-chains [0 1]] {:items [{:effect-id :scale}] :active? true})
      (assoc-in [:effects :selected-cell] [0 1])))

(deftest handle-edit-copy-grid-selected-test
  (testing "Edit > Copy on grid tab with selected cell copies cue chain"
    (let [event {:event/type :edit/copy
                 :state sample-state-with-cues}
          result (menu/handle event)]
      (is (= :cue-chain (get-in result [:state :ui :clipboard :type])))
      (is (= {:items [{:preset-id :circle}]}
             (get-in result [:state :ui :clipboard :data]))))))

(deftest handle-edit-copy-grid-fallback-to-active-cue-test
  (testing "Edit > Copy on grid tab without selected cell falls back to active playing cue"
    (let [event {:event/type :edit/copy
                 :state sample-state-playing}
          result (menu/handle event)]
      (is (= :cue-chain (get-in result [:state :ui :clipboard :type])))
      (is (= {:items [{:preset-id :circle}]}
             (get-in result [:state :ui :clipboard :data]))))))

(deftest handle-edit-copy-effects-test
  (testing "Edit > Copy on effects tab with selected cell copies effects chain"
    (let [event {:event/type :edit/copy
                 :state sample-state-with-effects}
          result (menu/handle event)]
      (is (= :effects-cell (get-in result [:state :ui :clipboard :type])))
      (is (= {:items [{:effect-id :scale}] :active? true}
             (get-in result [:state :ui :clipboard :data]))))))

(deftest handle-edit-paste-grid-test
  (testing "Edit > Paste on grid tab writes clipboard data to selected cell"
    (let [clipboard-data {:items [{:preset-id :star}]}
          state (-> sample-state-with-cues
                    (assoc-in [:grid :selected-cell] [1 1])
                    (assoc-in [:ui :clipboard] {:type :cue-chain :data clipboard-data}))
          event {:event/type :edit/paste
                 :state state}
          result (menu/handle event)]
      (is (= clipboard-data (get-in result [:state :chains :cue-chains [1 1]])))
      (is (true? (get-in result [:state :project :dirty?]))))))

(deftest handle-edit-clear-cell-grid-test
  (testing "Edit > Clear Cell on grid tab clears selected cell"
    (let [event {:event/type :edit/clear-cell
                 :state sample-state-with-cues}
          result (menu/handle event)]
      (is (nil? (get-in result [:state :chains :cue-chains [0 0]])))
      (is (true? (get-in result [:state :project :dirty?]))))))
