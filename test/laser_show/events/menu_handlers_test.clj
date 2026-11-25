(ns laser-show.events.menu-handlers-test
  "Tests for menu event handlers, focusing on Edit menu clipboard operations."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [laser-show.state.history :as history]
   [laser-show.state.core :as state]
   [laser-show.events.handlers.menu :as menu]
   [laser-show.timeline.shows :as shows]
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
          result (menu/handle (assoc event :time 77))]
      (is (= :cue-chain (get-in result [:state :ui :clipboard :type])))
      (is (= 77 (get-in result [:state :ui :clipboard :copied-at])) "the co-effect's :time")
      (is (= (get-in result [:state :ui :clipboard]) (:clipboard/write result)))
      (is (= {:items [{:preset-id :circle}]}
             (get-in result [:state :ui :clipboard :data]))))))

(deftest handle-edit-copy-grid-fallback-to-active-cue-test
  (testing "Edit > Copy on grid tab without selected cell falls back to active playing cue"
    (let [event {:event/type :edit/copy
                 :state sample-state-playing}
          result (menu/handle (assoc event :time 77))]
      (is (= :cue-chain (get-in result [:state :ui :clipboard :type])))
      (is (= 77 (get-in result [:state :ui :clipboard :copied-at])) "the co-effect's :time")
      (is (= (get-in result [:state :ui :clipboard]) (:clipboard/write result)))
      (is (= {:items [{:preset-id :circle}]}
             (get-in result [:state :ui :clipboard :data]))))))

(deftest copy-fallback-skips-show-keys-test
  (testing "Edit > Copy's fallback takes the first playing grid cue, never a show's key"
    (let [u #uuid "00000000-0000-0000-0000-0000000005a1"
          state (-> base-state
                    (assoc-in [:ui :active-tab] :grid)
                    (assoc-in [:grid :selected-cell] nil)
                    (assoc-in [:chains :cue-chains [1 0]] {:items [{:preset-id :square}]})
                    (assoc-in [:chains :shows u] {:editor-type :timeline :items []})
                    (assoc-in [:playback :active-cues] (array-map (shows/play-key u) {:run (random-uuid)}
                                                                  [1 0] {:run (random-uuid)})))
          result (menu/handle {:event/type :edit/copy :state state :time 77})]
      (is (= :cue-chain (get-in result [:state :ui :clipboard :type])))
      (is (= {:items [{:preset-id :square}]} (get-in result [:state :ui :clipboard :data]))))))

(deftest handle-edit-copy-effects-test
  (testing "Edit > Copy on effects tab with selected cell copies effects chain"
    (let [event {:event/type :edit/copy
                 :state sample-state-with-effects}
          result (menu/handle (assoc event :time 77))]
      (is (= :effects-cell (get-in result [:state :ui :clipboard :type])))
      (is (= 77 (get-in result [:state :ui :clipboard :copied-at])))
      (is (= (get-in result [:state :ui :clipboard]) (:clipboard/write result)))
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
      (is (history/derive-dirty (:state event) (:state result))))))

(deftest edit-paste-with-an-empty-slot-asks-for-the-system-clipboard-test
  (testing "Edit > Paste on the grid tab, nothing copied in this app: the cell's paste waits for the OS clipboard"
    (let [state (assoc-in sample-state-with-cues [:grid :selected-cell] [1 1])
          result (menu/handle {:event/type :edit/paste :state state :time 3})]
      (is (= {:clipboard/read {:then {:event/type :grid/paste-cell :col 1 :row 1}}} result))))
  (testing "on the effects tab, the effects cell's paste"
    (let [result (menu/handle {:event/type :edit/paste :state sample-state-with-effects :time 3})]
      (is (= {:clipboard/read {:then {:event/type :effects/paste-cell :col 0 :row 1}}} result)))))

(deftest handle-edit-clear-cell-grid-test
  (testing "Edit > Clear Cell on grid tab clears selected cell"
    (let [event {:event/type :edit/clear-cell
                 :state sample-state-with-cues}
          result (menu/handle event)]
      (is (nil? (get-in result [:state :chains :cue-chains [0 0]])))
      (is (history/derive-dirty (:state event) (:state result))))))


(deftest edit-copy-on-an-empty-cell-keeps-the-clipboard-test
  ;; p14: before Step 4b Edit > Copy on an empty cell stored {:type :cue-chain
  ;; :data nil}, and Edit > Paste of it emptied the cell it was pasted on
  (let [clip {:type :cue-chain :data {:items [{:preset-id :star}]} :copied-at 1}
        state (-> sample-state-with-cues
                  (assoc-in [:grid :selected-cell] [5 2])
                  (assoc-in [:ui :clipboard] clip))
        result (menu/handle {:event/type :edit/copy :state state :time 9})]
    (is (= {:state state} result) "nothing copied, nothing written"))
  (testing "a clip of an empty cell (another build's, read from the OS clipboard) never empties a cell"
    (let [state (assoc-in sample-state-with-cues [:ui :clipboard] {:type :cue-chain :data nil :copied-at 1})
          result (menu/handle {:event/type :edit/paste :state state :time 9})]
      (is (= {:items [{:preset-id :circle}]} (get-in result [:state :chains :cue-chains [0 0]]))))))


;; The View menu and the removed stubs (dialog shell plan 02 §9)

(deftest toggle-preview-hides-and-restores-test
  (let [toggle #(:state (menu/handle {:event/type :view/toggle-preview :state %}))
        unset (update base-state :ui dissoc :preview-visible?)]
    (testing "unset counts as shown: the first toggle hides it"
      (is (false? (get-in (toggle unset) [:ui :preview-visible?]))))
    (testing "and the next one brings it back"
      (is (true? (get-in (toggle (toggle unset)) [:ui :preview-visible?]))))
    (testing "from the shown default"
      (is (false? (get-in (toggle (assoc-in base-state [:ui :preview-visible?] true)) [:ui :preview-visible?]))))
    (testing "the document isn't touched"
      (is (not (history/derive-dirty unset (toggle unset)))))))

(deftest fullscreen-returns-the-window-effect-test
  (testing "off (or unset): on, and the main window is asked to go fullscreen"
    (doseq [state [(update base-state :ui dissoc :fullscreen?)
                   (assoc-in base-state [:ui :fullscreen?] false)]]
      (let [result (menu/handle {:event/type :view/fullscreen :state state})]
        (is (true? (get-in result [:state :ui :fullscreen?])))
        (is (true? (:window/set-fullscreen result))))))
  (testing "on: off"
    (let [result (menu/handle {:event/type :view/fullscreen
                               :state (assoc-in base-state [:ui :fullscreen?] true)})]
      (is (false? (get-in result [:state :ui :fullscreen?])))
      (is (false? (:window/set-fullscreen result))))))

(deftest fullscreen-changed-stores-what-the-window-did-test
  (testing "the window refused, or the OS left fullscreen: the flag follows it"
    (let [result (menu/handle {:event/type :view/fullscreen-changed :fullscreen? false
                               :state (assoc-in base-state [:ui :fullscreen?] true)})]
      (is (= {:state (assoc-in base-state [:ui :fullscreen?] false)} result) "only the flag, no effect")))
  (testing "the OS put it in fullscreen"
    (is (true? (get-in (menu/handle {:event/type :view/fullscreen-changed :fullscreen? true :state base-state})
                       [:state :ui :fullscreen?])))))

(deftest export-and-check-for-updates-are-gone-test
  (testing "nothing handles them: their menu items went with them"
    (is (= {} (menu/handle {:event/type :file/export :state base-state})))
    (is (= {} (menu/handle {:event/type :help/check-updates :state base-state})))))
