(ns laser-show.events.grid-handlers-test
  "Tests for grid event handlers focusing on complex state transitions."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [laser-show.state.core :as state]
   [laser-show.events.handlers.grid :as grid]
   [laser-show.state.domains :refer [build-initial-state]]))


;; Test Fixtures
;; Start with actual app initial state and only assoc-in the keys relevant to each test


(def base-state
  "Base state using actual app initial state structure."
  (build-initial-state))

(defn init-state-fixture [f]
  (state/init-state! base-state)
  (f))

(use-fixtures :each init-state-fixture)

(def sample-state-empty
  "Empty grid state - clear out default cue chains from initial state."
  (assoc-in base-state [:chains :cue-chains] {}))

(def sample-state-with-content
  "State with some cue chains populated."
  (-> base-state
      (assoc-in [:chains :cue-chains [0 0]] {:items [{:preset-id :circle}]})
      (assoc-in [:chains :cue-chains [1 1]] {:items [{:preset-id :wave}]})))

(def sample-state-playing
  "State with active playback."
  (-> base-state
      (assoc-in [:chains :cue-chains [0 0]] {:items [{:preset-id :circle}]})
      (assoc-in [:playback :active-cell] [0 0])
      (assoc-in [:playback :playing?] true)
      (assoc-in [:playback :trigger-time] 1000)))


;; Grid Move Cell Tests


(deftest handle-grid-move-cell-basic
  (testing "Moving cell with content to empty location"
    (let [event {:event/type :grid/move-cell
                 :from-col 0
                 :from-row 0
                 :to-col 2
                 :to-row 2
                 :state sample-state-with-content}
          result (grid/handle event)]
      (is (nil? (get-in result [:state :chains :cue-chains [0 0]])))
      (is (= [{:preset-id :circle}]
             (get-in result [:state :chains :cue-chains [2 2] :items])))
      (is (true? (get-in result [:state :project :dirty?]))))))

(deftest handle-grid-move-cell-active
  (testing "Moving active cell updates playback reference"
    (let [event {:event/type :grid/move-cell
                 :from-col 0
                 :from-row 0
                 :to-col 3
                 :to-row 3
                 :state sample-state-playing}
          result (grid/handle event)]
      (is (nil? (get-in result [:state :chains :cue-chains [0 0]])))
      (is (some? (get-in result [:state :chains :cue-chains [3 3]])))
      (is (= [3 3] (get-in result [:state :playback :active-cell])))
      (is (true? (get-in result [:state :playback :playing?])))
      (is (true? (get-in result [:state :project :dirty?]))))))

(deftest handle-grid-move-cell-nonexistent
  (testing "Moving from empty cell does nothing"
    (let [event {:event/type :grid/move-cell
                 :from-col 5
                 :from-row 5
                 :to-col 6
                 :to-row 6
                 :state sample-state-empty}
          result (grid/handle event)]
      (is (nil? (get-in result [:state :chains :cue-chains [6 6]])))
      ;; State unchanged - no dirty flag
      (is (false? (get-in result [:state :project :dirty?]))))))

(deftest handle-grid-move-cell-overwrite
  (testing "Moving cell overwrites destination"
    (let [event {:event/type :grid/move-cell
                 :from-col 0
                 :from-row 0
                 :to-col 1
                 :to-row 1
                 :state sample-state-with-content}
          result (grid/handle event)]
      (is (nil? (get-in result [:state :chains :cue-chains [0 0]])))
      (is (= [{:preset-id :circle}]
             (get-in result [:state :chains :cue-chains [1 1] :items])))
      (is (true? (get-in result [:state :project :dirty?]))))))


;; Grid Paste Cell Tests


(deftest handle-grid-paste-cell-valid
  (testing "Pasting valid cue chain from clipboard"
    (let [clipboard-data {:items [{:preset-id :circle}]}
          state (assoc-in sample-state-empty [:ui :clipboard]
                          {:type :cue-chain :data clipboard-data})
          event {:event/type :grid/paste-cell
                 :col 0
                 :row 0
                 :state state}
          result (grid/handle event)]
      (is (= clipboard-data (get-in result [:state :chains :cue-chains [0 0]])))
      (is (true? (get-in result [:state :project :dirty?]))))))

(deftest handle-grid-paste-cell-wrong-type
  (testing "Pasting non-cue-chain clipboard does nothing"
    (let [state (assoc-in sample-state-empty [:ui :clipboard]
                          {:type :effect-chain :data {:effects []}})
          event {:event/type :grid/paste-cell
                 :col 0
                 :row 0
                 :state state}
          result (grid/handle event)]
      (is (nil? (get-in result [:state :chains :cue-chains [0 0]])))
      (is (false? (get-in result [:state :project :dirty?]))))))

(deftest handle-grid-paste-cell-empty-clipboard
  (testing "Pasting with empty clipboard does nothing"
    (let [event {:event/type :grid/paste-cell
                 :col 0
                 :row 0
                 :state sample-state-empty}
          result (grid/handle event)]
      (is (nil? (get-in result [:state :chains :cue-chains [0 0]])))
      (is (false? (get-in result [:state :project :dirty?]))))))

(deftest handle-grid-paste-cell-overwrite
  (testing "Pasting over existing cell replaces content"
    (let [clipboard-data {:items [{:preset-id :wave}]}
          state (assoc-in sample-state-with-content [:ui :clipboard]
                          {:type :cue-chain :data clipboard-data})
          event {:event/type :grid/paste-cell
                 :col 0
                 :row 0
                 :state state}
          result (grid/handle event)]
      (is (= clipboard-data (get-in result [:state :chains :cue-chains [0 0]])))
      (is (true? (get-in result [:state :project :dirty?]))))))


;; Grid Cell Clicked Tests


(deftest handle-grid-cell-clicked-with-content
  (testing "Clicking cell with content triggers playback and selects cell"
    (let [event {:event/type :grid/cell-clicked
                 :col 0
                 :row 0
                 :has-content? true
                 :state sample-state-with-content
                 :time 5000}
          result (grid/handle event)]
      (is (= [0 0] (get-in result [:state :playback :active-cell])))
      (is (= [0 0] (get-in result [:state :grid :selected-cell])))
      (is (true? (get-in result [:state :playback :playing?])))
      ;; Trigger time should be set (not checking exact value since handler uses System/currentTimeMillis)
      (is (some? (get-in result [:state :playback :trigger-time]))))))

(deftest handle-grid-cell-clicked-empty
  (testing "Clicking empty cell selects it"
    (let [event {:event/type :grid/cell-clicked
                 :col 2
                 :row 2
                 :has-content? false
                 :state sample-state-empty}
          result (grid/handle event)]
      (is (= [2 2] (get-in result [:state :grid :selected-cell])))
      ;; Should not start playback
      (is (false? (get-in result [:state :playback :playing?])))
      (is (nil? (get-in result [:state :playback :active-cell]))))))

(deftest handle-grid-copy-cell-test
  (testing "Copying cell saves cue-chain to internal clipboard"
    (let [event {:event/type :grid/copy-cell
                 :col 0
                 :row 0
                 :state sample-state-with-content}
          result (grid/handle event)]
      (is (= :cue-chain (get-in result [:state :ui :clipboard :type])))
      (is (= {:items [{:preset-id :circle}]}
             (get-in result [:state :ui :clipboard :data]))))))

(deftest handle-grid-clear-cell-test
  (testing "Clearing cell removes cue-chain from state"
    (let [event {:event/type :grid/clear-cell
                 :col 0
                 :row 0
                 :state sample-state-with-content}
          result (grid/handle event)]
      (is (nil? (get-in result [:state :chains :cue-chains [0 0]])))
      (is (true? (get-in result [:state :project :dirty?]))))))

(deftest handle-grid-select-cell-test
  (testing "Selecting cell updates grid selected-cell"
    (let [event {:event/type :grid/select-cell
                 :col 1
                 :row 3
                 :state sample-state-empty}
          result (grid/handle event)]
      (is (= [1 3] (get-in result [:state :grid :selected-cell]))))))


;; Grid Open Cell Editor Tests


(deftest handle-grid-open-cell-editor-test
  (testing "Dispatches to timeline editor for timeline cues"
    (let [state-with-timeline (assoc-in sample-state-empty
                                        [:chains :cue-chains [1 1]]
                                        {:editor-type :timeline :items []})
          event {:event/type :grid/open-cell-editor
                 :col 1
                 :row 1
                 :state state-with-timeline}
          result (grid/handle event)]
      (is (= {:event/type :timeline-cue-editor/open :col 1 :row 1}
             (:dispatch result)))))

  (testing "Dispatches to timeline editor for cues with tracks"
    (let [state-with-tracks (assoc-in sample-state-empty
                                      [:chains :cue-chains [1 1]]
                                      {:tracks [{:id "t1"}] :items []})
          event {:event/type :grid/open-cell-editor
                 :col 1
                 :row 1
                 :state state-with-tracks}
          result (grid/handle event)]
      (is (= {:event/type :timeline-cue-editor/open :col 1 :row 1}
             (:dispatch result)))))

  (testing "Dispatches to cue chain editor for standard cues"
    (let [event {:event/type :grid/open-cell-editor
                 :col 0
                 :row 0
                 :state sample-state-with-content}
          result (grid/handle event)]
      (is (= {:event/type :cue-chain/open-editor :col 0 :row 0}
             (:dispatch result)))))

  (testing "Dispatches to cue chain editor for empty cells"
    (let [event {:event/type :grid/open-cell-editor
                 :col 3
                 :row 3
                 :state sample-state-empty}
          result (grid/handle event)]
      (is (= {:event/type :cue-chain/open-editor :col 3 :row 3}
             (:dispatch result))))))
